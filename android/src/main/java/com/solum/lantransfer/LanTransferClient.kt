package com.solum.lantransfer
import com.getcapacitor.JSObject
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.getcapacitor.Plugin
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey

class LanTransferClient(
  private val context: Context,
  private val plugin: LanTransferPlugin
) {
  private val executor = Executors.newSingleThreadExecutor()
  private val stallTimer = Executors.newSingleThreadScheduledExecutor()
  /** A send with no bytes written for this long is aborted (see sendStream). */
  internal var writeStallMs = 30_000L
  /** How long a sent payload may wait for the receiver's ACK. */
  internal var ackTimeoutMs = 30_000
  private var socket: Socket? = null
  private val connected = AtomicBoolean(false)
  /** host:port|key of the open socket, and whether its receiver ACKs payloads — then
   *  the socket carries the next payload too (no new handshake per file). */
  private var target: String? = null
  private var reusable = false

  @Volatile private var crypto: LanTransferCrypto.CryptoConfig? = null
  fun setCrypto(config: LanTransferCrypto.CryptoConfig?) { crypto = config }

  fun isConnected(): Boolean = connected.get()

  /** host/port of the last connect(): every event names its connection, so a caller
   *  sending to several displays at once can tell their progress apart. */
  @Volatile private var remote: Pair<String, Int>? = null
  private fun emit(event: String, data: JSObject) {
    remote?.let { (h, p) -> data.put("remoteHost", h); data.put("remotePort", p) }
    plugin.emit(event, data)
  }

  /** Run [work] on the client thread, after any queued connect/send, and report
   *  the outcome. Keeps connect() off Capacitor's plugin thread — it is shared by
   *  every plugin, and a 2 x 7 s connect timeout there froze all Filesystem calls. */
  fun enqueue(work: () -> Unit, done: (Exception?) -> Unit) {
    executor.execute {
      try { work(); done(null) } catch (e: Exception) { done(e) }
    }
  }

  /**
   * Connects synchronously (runs on Capacitor's background thread). Resolves the
   * plugin call only after the socket is truly connected — fixes the "first attempt
   * after idle fails, second works" race. Closes any stale socket first, enables
   * SO_KEEPALIVE, and retries twice (300 ms apart) before throwing.
   */
  fun connect(host: String, port: Int, sessionKey: String?, timeoutMs: Int = 7000) {
    val key = "$host:$port|${sessionKey ?: ""}"
    remote = host to port
    val open = socket
    if (open != null && connected.get() && reusable && target == key && peerOpen(open)) {
      emit("status", JSObject().apply {
        put("role", "client")
        put("status", "client_connected")
        put("remoteHost", host)
        put("remotePort", port)
        put("reused", true)
      })
      return
    }
    disconnect() // close any stale socket before reconnecting

    var lastErr: Exception? = null
    var attempt = 0
    while (attempt < 2) {
      attempt++
      val s = Socket()
      try {
        s.tcpNoDelay = true
        s.keepAlive = true
        // Large send buffer → higher LAN throughput on big media transfers.
        try { s.sendBufferSize = 1 shl 20 } catch (_: Exception) {}
        s.connect(InetSocketAddress(host, port), timeoutMs)

        val out = BufferedOutputStream(s.getOutputStream())
        LanTransferProtocol.writeHello(out, sessionKey)
        out.flush()

        socket = s
        target = key
        reusable = false
        connected.set(true)
        emit("status", JSObject().apply {
          put("role", "client")
          put("status", "client_connected")
          put("remoteHost", host)
          put("remotePort", port)
        })
        return
      } catch (e: Exception) {
        lastErr = e
        connected.set(false)
        // Close the socket that FAILED — this used to close the (null) field and
        // leak 2 fds per refused connect; on the LCD's 1024-fd limit that ended in
        // accept() failing with EMFILE and the receiver shutting down.
        try { s.close() } catch (_: Exception) {}
        socket = null
        if (attempt < 2) { try { Thread.sleep(300) } catch (_: InterruptedException) {} }
      }
    }

    emit("error", JSObject().apply {
      put("role", "client")
      put("code", "CONNECT_FAILED")
      put("message", lastErr?.message ?: "connect failed")
    })
    throw lastErr ?: IOException("connect failed")
  }


  fun disconnect() {
    connected.set(false)
    try { socket?.close() } catch (_: Exception) {}
    socket = null
    target = null
    reusable = false
    emit("status", JSObject().apply {
      put("role", "client")
      put("status", "client_disconnected")
    })
  }

  // The send methods run on the client thread and call [done] once the bytes are
  // flushed (or failed), so the JS promise means "sent", not merely "queued".
  fun sendString(text: String, done: (Exception?) -> Unit) {
    sendStream({ val b = text.toByteArray(Charsets.UTF_8); ByteArrayInputStream(b) to b.size.toLong() },
      LanTransferProtocol.ENCODING_UTF8, done)
  }

  fun sendBase64(base64: String, done: (Exception?) -> Unit) {
    sendStream({ val b = Base64.decode(base64, Base64.DEFAULT); ByteArrayInputStream(b) to b.size.toLong() },
      LanTransferProtocol.ENCODING_BINARY, done)
  }

  fun sendFile(pathOrUri: String, done: (Exception?) -> Unit) {
    sendStream({ val (stream, total) = openStreamAndSize(pathOrUri); BufferedInputStream(stream) to total },
      LanTransferProtocol.ENCODING_BINARY, done)
  }

  private fun sendStream(open: () -> Pair<InputStream, Long>, encoding: Byte, done: (Exception?) -> Unit) {
    val cfg = crypto

    executor.execute {
      var watchdog: ScheduledFuture<*>? = null
      try {
        // Decode/open HERE, on the client thread: decoding a multi-MB video on
        // Capacitor's shared plugin thread stalled every plugin (and could OOM).
        val (input, totalPlain) = open()
        input.use { ins ->
          // Read the socket HERE: a connect() queued just before has now run.
          val s = socket ?: throw IllegalStateException("Not connected")
          // write() has no timeout: a peer that lost power/Wi-Fi mid-send blocked it
          // for ~15 min and wedged every later connect/send queued on this thread.
          // Closing the socket after writeStallMs without progress makes it throw.
          val lastWrite = AtomicLong(System.currentTimeMillis())
          watchdog = stallTimer.scheduleAtFixedRate({
            if (System.currentTimeMillis() - lastWrite.get() > writeStallMs) { try { s.close() } catch (_: Exception) {} }
          }, 1, 1, TimeUnit.SECONDS)
          val out = BufferedOutputStream(s.getOutputStream())
          val encrypted = (cfg?.enabled == true)
          val chunkSize = cfg?.chunkSize ?: 65_536
          val iters = cfg?.iterations ?: 100_000
          val encodingStr = if (encoding == LanTransferProtocol.ENCODING_UTF8) "utf8" else "binary"

          emit("status", JSObject().apply {
            put("role", "client")
            put("status", "send_started")
            put("totalBytes", totalPlain)
            put("encoding", encodingStr)
            put("encrypted", encrypted)
          })

          val headerMat = LanTransferCrypto.randomHeaderMaterial()
          LanTransferProtocol.writeDataHeaderV2(
            out = out,
            encoding = encoding,
            encrypted = encrypted,
            totalPlainBytes = totalPlain,
            chunkSize = chunkSize,
            salt16 = headerMat.salt,
            baseNonce8 = headerMat.baseNonce8
          )

          val digest = MessageDigest.getInstance("SHA-256")

          if (!encrypted) {
            // 256 KB buffer (was 64 KB) → fewer loop iterations / syscalls.
            val buffer = ByteArray(256 * 1024)
            var plainSent = 0L
            var lastEmitAt = 0L
            while (plainSent < totalPlain) {
              val remaining = totalPlain - plainSent
              val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
              val read = ins.read(buffer, 0, toRead)
              if (read <= 0) throw EOFException("Unexpected end of stream")

              digest.update(buffer, 0, read)
              out.write(buffer, 0, read)
              lastWrite.set(System.currentTimeMillis())

              plainSent += read.toLong()
              // Throttle progress by time: several updates a second on a slow link,
              // no bridge flood on a fast one.
              val now = System.currentTimeMillis()
              if (now - lastEmitAt >= LanTransferServer.PROGRESS_INTERVAL_MS || plainSent >= totalPlain) {
                emitProgress(plainSent, totalPlain); lastEmitAt = now
              }
            }

            out.flush()
            watchdog?.cancel(false)
            awaitDelivery(s)
            emitSendComplete(totalPlain, encodingStr, false, HashUtils.toHex(digest.digest()))
            done(null)
            return@use
          }

          val pass = cfg?.passphrase ?: throw IllegalStateException("Encryption enabled but passphrase missing")
          val key: SecretKey = LanTransferCrypto.deriveKey(pass, headerMat.salt, iters)

          val buffer = ByteArray(chunkSize)
          var counter = 0
          var plainSent = 0L
          var lastEmitAt = 0L

          while (plainSent < totalPlain) {
            val remaining = totalPlain - plainSent
            val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
            val read = ins.read(buffer, 0, toRead)
            if (read <= 0) throw EOFException("Unexpected end of stream")

            digest.update(buffer, 0, read)

            val nonce = LanTransferCrypto.nonce12(headerMat.baseNonce8, counter)
            val (ct, tag) = LanTransferCrypto.encryptChunk(key, nonce, buffer, read)
            LanTransferProtocol.writeEncryptedChunk(out, read, ct, tag)
            lastWrite.set(System.currentTimeMillis())

            plainSent += read.toLong()
            counter += 1
            val now = System.currentTimeMillis()
            if (now - lastEmitAt >= LanTransferServer.PROGRESS_INTERVAL_MS || plainSent >= totalPlain) {
              emitProgress(plainSent, totalPlain); lastEmitAt = now
            }
          }

          out.flush()
          watchdog?.cancel(false)
          awaitDelivery(s)
          emitSendComplete(totalPlain, encodingStr, true, HashUtils.toHex(digest.digest()))
          done(null)
        }
      } catch (t: Throwable) { // incl. OutOfMemoryError on a huge video: fail the send, don't crash
        // The stream may be cut mid-frame: never reuse this socket for the next payload.
        if (socket != null) disconnect()
        emit("error", JSObject().apply {
          put("role", "client")
          put("code", "SEND_FAILED")
          put("message", t.message ?: t.toString())
        })
        done(t as? Exception ?: Exception(t.toString(), t))
      } finally {
        watchdog?.cancel(false)
      }
    }
  }

  /** Blocks until the receiver answers the payload just written, so a resolved send
   *  means delivered (it used to mean "in the local socket buffer", and closing the
   *  socket for the next payload could still cut the last megabyte off).
   *  ACK → keep the socket for the next payload. NAK → the receiver rejected it.
   *  EOF on a socket that never ACKed → an older receiver that closes after every
   *  payload once it has read it all: delivered, and the next connect() reconnects. */
  private fun awaitDelivery(s: Socket) {
    s.soTimeout = ackTimeoutMs
    val reply = try { s.getInputStream().read() } catch (e: java.net.SocketTimeoutException) {
      throw IOException("No delivery confirmation from the receiver within ${ackTimeoutMs / 1000} s")
    }
    when (reply) {
      LanTransferProtocol.ACK.toInt() -> { reusable = true; s.soTimeout = 0 }
      LanTransferProtocol.NAK.toInt() -> { disconnect(); throw IOException("Receiver rejected the payload") }
      -1 -> {
        val acked = reusable
        disconnect()
        if (acked) throw IOException("Receiver closed the connection before confirming")
      }
      else -> { disconnect(); throw IOException("Unexpected reply from receiver: $reply") }
    }
  }

  /** A 1 ms read times out on a live idle socket and returns -1 once the peer has
   *  closed it (e.g. the receiver's 60 s idle timeout). Nothing else may arrive
   *  between payloads, so data also means "don't reuse". */
  private fun peerOpen(s: Socket): Boolean {
    if (s.isClosed) return false
    return try {
      s.soTimeout = 1
      s.getInputStream().read()
      false
    } catch (_: java.net.SocketTimeoutException) { true }
    catch (_: Exception) { false }
    finally { try { s.soTimeout = 0 } catch (_: Exception) {} }
  }

  private fun emitProgress(bytes: Long, total: Long) {
    val percent = if (total == 0L) 100.0 else (bytes * 100.0 / total)
    emit("progress", JSObject().apply {
      put("role", "client")
      put("direction", "send")
      put("bytesTransferred", bytes)
      put("totalBytes", total)
      put("percent", percent)
    })
  }

  private fun emitSendComplete(
    totalPlain: Long,
    encodingStr: String,
    encrypted: Boolean,
    sha256: String
  ) {
    emit("status", JSObject().apply {
      put("role", "client")
      put("status", "send_complete")
      put("totalBytes", totalPlain)
      put("encoding", encodingStr)
      put("encrypted", encrypted)
      put("sha256", sha256)
    })
  }


  private fun openStreamAndSize(pathOrUri: String): Pair<InputStream, Long> {
    // file://... (Capacitor Filesystem)
    if (pathOrUri.startsWith("file://")) {
      val filePath = pathOrUri.removePrefix("file://")
      val file = File(filePath)
      require(file.exists()) { "File not found: $filePath" }
      return Pair(FileInputStream(file), file.length())
    }

    // content://... (pickers)
    if (pathOrUri.startsWith("content://")) {
      val uri = Uri.parse(pathOrUri)
      val size = queryContentSize(uri)
        ?: throw IllegalStateException("Unable to determine content size for URI (required for progress): $pathOrUri")

      val ins = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Unable to open InputStream for URI: $pathOrUri")

      return Pair(ins, size)
    }

    // raw filesystem path
    val file = File(pathOrUri)
    require(file.exists()) { "File not found: $pathOrUri" }
    return Pair(FileInputStream(file), file.length())
  }

  private fun queryContentSize(uri: Uri): Long? {
    try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
          val idx = c.getColumnIndex(OpenableColumns.SIZE)
          if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx)
        }
      }
    } catch (_: Exception) {}

    try {
      context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
        val len = afd.length
        if (len > 0) return len
      }
    } catch (_: Exception) {}

    return null
  }
}