package com.solum.lantransfer
import com.getcapacitor.JSObject
import android.content.Context
import android.util.Base64
import com.getcapacitor.Plugin
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LanTransferServer(
  private val context: Context,
  private val emitEvent: (event: String, data:JSObject) -> Unit
) {
  private val executor = Executors.newCachedThreadPool()
  private var serverSocket: ServerSocket? = null
  // Every live connection. A new one must NOT close the previous: that reset a
  // payload still being read (truncated media, lost deploy acks).
  private val sockets = ConcurrentHashMap.newKeySet<Socket>()
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

  fun start(port: Int) {
    if (running.get()) return
    running.set(true)

    executor.execute {
      var ss: ServerSocket? = null
      try {
        // reuseAddress so a quick restart (or TIME_WAIT after idle) can re-bind the
        // port immediately — avoids the server being un-connectable for ~60s.
        ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(java.net.InetSocketAddress(port))
        serverSocket = ss

        emitStatus(
                    mapOf(
                        "role" to "server",
                        "status" to "server_started",
                        "ip" to NetUtils.getLocalIpv4Address(),
                        "port" to ss.localPort
                    )
                )

        while (running.get()) {
          val socket = ss.accept()
          socket.keepAlive = true
          socket.tcpNoDelay = true
          // A sender that vanished mid-payload (Wi-Fi drop) errors out instead of
          // holding a thread and a half-written temp file forever.
          socket.soTimeout = 60_000
          // Large receive buffer → higher LAN throughput on big media transfers.
          try { socket.receiveBufferSize = 1 shl 20 } catch (_: Exception) {}
          sockets.add(socket)

          emitStatus(
                        mapOf(
                            "role" to "server",
                            "status" to "connection_accepted",
                            "remoteHost" to socket.inetAddress.hostAddress,
                            "remotePort" to socket.port
                        )
                    )

          executor.execute { handleConnection(socket) }
        }
      } catch (e: Exception) {
        // accept() throws when stop() closes the socket — that is not an error.
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
    try {
      val input = BufferedInputStream(socket.getInputStream())
      val output = BufferedOutputStream(socket.getOutputStream())

      // HELLO
      val clientKey = LanTransferProtocol.readHello(input)
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

      val (sink, outFile, memSink) = createReceiveSink(encoding)
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

      output.flush()
    } catch (e: Exception) {
      emitError("CONNECTION_ERROR", "Connection error: ${e.message ?: e}")
    } finally {
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