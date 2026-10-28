package com.solum.lantransfer
import com.getcapacitor.JSObject
import android.content.Context
import android.util.Base64
import com.getcapacitor.Plugin
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LanTransferServer(
  private val context: Context,
  private val emitEvent: (event: String, data:JSObject) -> Unit
) {
  private val executor = Executors.newCachedThreadPool()
  private val handler = Executors.newSingleThreadExecutor()
  private var serverSocket: ServerSocket? = null
  // Every live connection. A new one must NOT close the previous: that reset a
  // payload still being read (truncated media, lost deploy acks).
  private val sockets: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap()) // newKeySet() needs API 24
  private val fileSeq = AtomicLong()
  private val running = AtomicBoolean(false)

  @Volatile private var sessionKey: String? = null
  @Volatile private var receiveMode: String = "file" // "file" | "memory"
  @Volatile private var receiveFileName: String? = null
  @Volatile private var crypto: LanTransferCrypto.CryptoConfig? = null

  fun setSessionKey(key: String?) { sessionKey = key }
  fun setReceiveMode(mode: String, fileName: String?) { receiveMode = mode; receiveFileName = fileName }
  fun setCrypto(config: LanTransferCrypto.CryptoConfig?) { crypto = config }

  fun isRunning(): Boolean = running.get()
  fun getPort(): Int = serverSocket?.localPort ?: 0

  /** Binds right here, so a failure (e.g. port 8082 still held by an old instance)
   *  throws and the JS call rejects. It used to bind later on a worker thread:
   *  startServer had already said "running", the failure was swallowed and the
   *  display silently stopped receiving. Only the accept loop runs in the background. */
  fun start(port: Int) {
    if (running.get()) return
    val ss = ServerSocket()
    try {
      // reuseAddress so a quick restart (or TIME_WAIT after idle) can re-bind the
      // port immediately — avoids the server being un-connectable for ~60s.
      ss.reuseAddress = true
      ss.bind(java.net.InetSocketAddress(port))
    } catch (e: Exception) {
      ss.closeQuietly()
      throw e
    }
    serverSocket = ss
    running.set(true)
    emitStatus(
      mapOf(
        "role" to "server",
        "status" to "server_started",
        "ip" to NetUtils.getLocalIpv4Address(),
        "port" to ss.localPort
      )
    )

    executor.execute {
      try {
        // One bad connection (a peer that reset before we set options, EMFILE, …)
        // must not end the loop: it used to fall through to stop() and the display
        // refused every later deploy until the app was restarted.
        while (running.get() && !ss.isClosed) {
          val socket = try { ss.accept() } catch (e: Exception) {
            if (!running.get() || ss.isClosed) break
            emitError("ACCEPT_FAILED", "Accept failed (server keeps running): ${e.message ?: e}")
            Thread.sleep(250)
            continue
          }
          try {
            socket.keepAlive = true
            socket.tcpNoDelay = true
            // A sender that vanished mid-payload (Wi-Fi drop) errors out instead of
            // holding the handler and a half-written temp file forever.
            socket.soTimeout = 60_000
            // Large receive buffer → higher LAN throughput on big media transfers.
            try { socket.receiveBufferSize = 1 shl 20 } catch (_: Exception) {}
            sockets.add(socket)

            emitStatus(
              mapOf(
                "role" to "server",
                "status" to "connection_accepted",
                "remoteHost" to socket.inetAddress?.hostAddress,
                "remotePort" to socket.port
              )
            )

            // Accepted immediately (nothing is closed or left in the backlog), but
            // HANDLED one at a time in accept order: receiveComplete must follow
            // send order — the app pairs each 'mediaMeta' with the binary after it.
            handler.execute { handleConnection(socket) }
          } catch (e: Exception) {
            sockets.remove(socket)
            socket.closeQuietly()
          }
        }
      } catch (e: Exception) {
        if (running.get() && serverSocket === ss) emitError("SERVER_ERROR", "Server error: ${e.message ?: e}")
      } finally {
        // Only tear down if this loop still owns the server: after stop()+start()
        // the old loop's late finally used to close the NEW server socket.
        if (serverSocket === ss) stop()
      }
    }
  }

  fun stop() {
    running.set(false)
    sockets.forEach { it.closeQuietly() }
    sockets.clear()
    serverSocket?.closeQuietly()
    serverSocket = null

    emitStatus(mapOf(
            "role" to "server",
            "status" to "server_stopped"
        ))
  }

  private fun handleConnection(socket: Socket) {
    var outFile: File? = null
    var gotHello = false
    var complete = false
    try {
      val input = BufferedInputStream(socket.getInputStream())
      val output = BufferedOutputStream(socket.getOutputStream())

      // HELLO
      val clientKey = LanTransferProtocol.readHello(input)
      gotHello = true
      val expected = sessionKey
      if (!expected.isNullOrEmpty() && clientKey != expected) {
        emitError("BAD_SESSION_KEY", "Session key mismatch; closing connection.")
        socket.closeQuietly()
        return
      }

      // DATA header
      val header = LanTransferProtocol.readDataHeaderV2(input)
      val totalPlain = header.totalPlainBytes
      val encoding = header.encoding
      val encodingStr = if (encoding == LanTransferProtocol.ENCODING_UTF8) "utf8" else "binary"

      emitStatus(
                mapOf(
                    "role" to "server",
                    "status" to "receive_started",
                    "totalBytes" to totalPlain,
                    "encoding" to encodingStr,
                    "encrypted" to header.encrypted
                )
            )

      val cfg = crypto
      if (cfg?.requireEncryption == true && !header.encrypted) {
        emitError("ENCRYPTION_REQUIRED", "Server requires encryption but received unencrypted payload.")
        socket.closeQuietly()
        return
      }

      val digest = MessageDigest.getInstance("SHA-256")

      val (sink, file, memSink) = createReceiveSink(encoding)
      outFile = file
      sink.use { out ->
        if (!header.encrypted) {
          copyPlainWithProgressAndHash(input, out, totalPlain, digest)
        } else {
          val pass = cfg?.passphrase ?: run {
            emitError("NO_PASSPHRASE", "Encrypted payload received but server passphrase not set.")
            return
          }
          val key = LanTransferCrypto.deriveKey(pass, header.salt16, cfg.iterations)

          val chunkCipherBuf = ByteArray(header.chunkSize)
          val tagBuf = ByteArray(16)
          var counter = 0
          var plainReceived = 0L

          while (plainReceived < totalPlain) {
            val chunkLen = LanTransferProtocol.readEncryptedChunkHeader(input)
            if (chunkLen <= 0 || chunkLen > header.chunkSize) throw IOException("Bad chunkLen=$chunkLen")

            LanTransferProtocol.readFully(input, chunkCipherBuf, chunkLen)
            LanTransferProtocol.readFully(input, tagBuf, 16)

            val nonce = LanTransferCrypto.nonce12(header.baseNonce8, counter)
            val plain = LanTransferCrypto.decryptChunk(
              key, nonce,
              chunkCipherBuf.copyOfRange(0, chunkLen),
              tagBuf
            )

            digest.update(plain)
            out.write(plain)

            plainReceived += plain.size.toLong()
            counter += 1

            emitReceiveProgress(plainReceived, totalPlain)
          }
        }
      }

      val sha = HashUtils.toHex(digest.digest())

      // receiveComplete includes sha256
      if (receiveMode == "file") {
        val js = JSObject()
                js.put("role", "server")
                js.put("encoding", encodingStr)
                js.put("totalBytes", totalPlain)
                js.put("filePath", outFile?.absolutePath ?: "")
                js.put("sha256", sha)
                emitEvent("receiveComplete", js)
      } else {
        val bytes = memSink?.toByteArray() ?: ByteArray(0)
        val js = JSObject()
        js.put("role", "server")
        js.put("encoding", encodingStr)
        js.put("totalBytes", totalPlain)
        if (encoding == LanTransferProtocol.ENCODING_UTF8) {
           js.put("text", String(bytes, Charsets.UTF_8))
        } else {
           js.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        js.put("sha256", sha)
        emitEvent("receiveComplete", js)
      }

      complete = true
      output.flush()
    } catch (e: Exception) {
      // A reachability probe connects and closes without sending a byte — not an error.
      if (gotHello) emitError("CONNECTION_ERROR", "Connection error: ${e.message ?: e}")
    } finally {
      // A failed transfer must not leave a multi-MB partial temp file behind.
      if (!complete) outFile?.delete()
      socket.closeQuietly()
      sockets.remove(socket)
      emitStatus(mapOf(
          "role" to "server",
          "status" to "connection_closed"
      ))
    }
  }

  private fun emitReceiveProgress(bytes: Long, total: Long) {
    val percent = if (total == 0L) 100.0 else (bytes * 100.0 / total)
    emitStatus(mapOf(
      "role" to "server",
      "direction" to "receive",
      "bytesTransferred" to bytes,
      "totalBytes" to total,
      "percent" to percent
    ))
  }

  private fun createReceiveSink(encoding: Byte): Triple<OutputStream, File?, ByteArrayOutputStream?> {
    if (receiveMode == "memory") {
      val baos = ByteArrayOutputStream()
      return Triple(baos, null, baos)
    }
    val ext = if (encoding == LanTransferProtocol.ENCODING_UTF8) "txt" else "bin"
    val name = receiveFileName?.takeIf { it.isNotBlank() } ?: "lantransfer_${System.currentTimeMillis()}_${fileSeq.incrementAndGet()}.$ext"
    val file = File(context.filesDir, name)
    file.parentFile?.mkdirs()
    return Triple(BufferedOutputStream(FileOutputStream(file)), file, null)
  }

  private fun copyPlainWithProgressAndHash(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long,
    digest: MessageDigest
  ) {
    // 256 KB buffer (was 64 KB) → fewer iterations / syscalls.
    val buffer = ByteArray(256 * 1024)
    var transferred = 0L
    var lastEmit = 0L

    while (transferred < totalBytes) {
      val remaining = totalBytes - transferred
      val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
      val read = input.read(buffer, 0, toRead)
      if (read <= 0) throw EOFException("Unexpected end of stream")

      digest.update(buffer, 0, read)
      output.write(buffer, 0, read)
      transferred += read.toLong()

      // Throttle progress: at most every ~1 MB (or at the end). Per-64KB emits
      // flood the Capacitor bridge — costly on the memory-constrained LCD.
      if (transferred - lastEmit >= 1_048_576L || transferred >= totalBytes) {
        emitReceiveProgress(transferred, totalBytes); lastEmit = transferred
      }
    }
    output.flush()
  }

  private fun emitStatus(data: Map<String, Any?>) {
      val js = JSObject()
      data.forEach { (k, v) -> js.put(k, v) }
      emitEvent("status", js)
  }

  private fun emitError(code: String, message: String) {
      val js = JSObject()
      js.put("role", "server")
      js.put("message", message)
      js.put("code", code)
      emitEvent("error", js)
  }

  private fun Closeable.closeQuietly() { try { close() } catch (_: Exception) {} }
}