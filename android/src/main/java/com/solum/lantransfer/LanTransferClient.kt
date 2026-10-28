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
  private var socket: Socket? = null
  private val connected = AtomicBoolean(false)

  @Volatile private var crypto: LanTransferCrypto.CryptoConfig? = null
  fun setCrypto(config: LanTransferCrypto.CryptoConfig?) { crypto = config }

  fun isConnected(): Boolean = connected.get()

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
        connected.set(true)
        plugin.emit("status", JSObject().apply {
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

    plugin.emit("error", JSObject().apply {
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
    plugin.emit("status", JSObject().apply {
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

          plugin.emit("status", JSObject().apply {
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
            var lastEmit = 0L
            while (plainSent < totalPlain) {
              val remaining = totalPlain - plainSent
              val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
              val read = ins.read(buffer, 0, toRead)
              if (read <= 0) throw EOFException("Unexpected end of stream")

              digest.update(buffer, 0, read)
              out.write(buffer, 0, read)
              lastWrite.set(System.currentTimeMillis())

              plainSent += read.toLong()
              // Throttle progress: emit at most every ~1 MB (or at the end).
              // Per-64KB emits flood the Capacitor bridge and slow the transfer.
              if (plainSent - lastEmit >= 1_048_576L || plainSent >= totalPlain) {
                emitProgress(plainSent, totalPlain); lastEmit = plainSent
              }
            }

            out.flush()
            emitSendComplete(totalPlain, encodingStr, false, HashUtils.toHex(digest.digest()))
            done(null)
            return@use
          }

          val pass = cfg?.passphrase ?: throw IllegalStateException("Encryption enabled but passphrase missing")
          val key: SecretKey = LanTransferCrypto.deriveKey(pass, headerMat.salt, iters)

          val buffer = ByteArray(chunkSize)
          var counter = 0
          var plainSent = 0L

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
            emitProgress(plainSent, totalPlain)
          }

          out.flush()
          emitSendComplete(totalPlain, encodingStr, true, HashUtils.toHex(digest.digest()))
          done(null)
        }
      } catch (t: Throwable) { // incl. OutOfMemoryError on a huge video: fail the send, don't crash
        plugin.emit("error", JSObject().apply {
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

  private fun emitProgress(bytes: Long, total: Long) {
    val percent = if (total == 0L) 100.0 else (bytes * 100.0 / total)
    plugin.emit("progress", JSObject().apply {
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
    plugin.emit("status", JSObject().apply {
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