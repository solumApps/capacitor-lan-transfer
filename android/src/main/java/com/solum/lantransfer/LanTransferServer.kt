package com.solum.lantransfer

import android.content.Context
import android.util.Base64
import com.getcapacitor.Plugin
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LanTransferServer(
  private val context: Context,
  private val plugin: Plugin,
) {
  private val executor = Executors.newCachedThreadPool()
  private var serverSocket: ServerSocket? = null
  private var activeSocket: Socket? = null
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
      try {
        val ss = ServerSocket(port)
        serverSocket = ss

        plugin.notifyListeners("status", mapOf(
          "role" to "server",
          "status" to "server_started",
          "ip" to NetUtils.getLocalIpv4Address(),
          "port" to ss.localPort
        ))

        while (running.get()) {
          val socket = ss.accept()
          activeSocket?.closeQuietly()
          activeSocket = socket

          plugin.notifyListeners("status", mapOf(
            "role" to "server",
            "status" to "connection_accepted",
            "remoteHost" to socket.inetAddress.hostAddress,
            "remotePort" to socket.port
          ))

          executor.execute { handleConnection(socket) }
        }
      } catch (e: Exception) {
        plugin.notifyListeners("error", mapOf(
          "role" to "server",
          "message" to ("Server error: " + (e.message ?: e.toString())),
          "code" to "SERVER_ERROR"
        ))
      } finally {
        stop()
      }
    }
  }

  fun stop() {
    running.set(false)
    activeSocket?.closeQuietly()
    activeSocket = null
    serverSocket?.closeQuietly()
    serverSocket = null

    plugin.notifyListeners("status", mapOf(
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
        plugin.notifyListeners("error", mapOf(
          "role" to "server",
          "message" to "Session key mismatch; closing connection.",
          "code" to "BAD_SESSION_KEY"
        ))
        socket.closeQuietly()
        return
      }

      // DATA header
      val header = LanTransferProtocol.readDataHeaderV2(input)
      val totalPlain = header.totalPlainBytes
      val encoding = header.encoding
      val encodingStr = if (encoding == LanTransferProtocol.ENCODING_UTF8) "utf8" else "binary"

      plugin.notifyListeners("status", mapOf(
        "role" to "server",
        "status" to "receive_started",
        "totalBytes" to totalPlain,
        "encoding" to encodingStr,
        "encrypted" to header.encrypted
      ))

      val cfg = crypto
      if (cfg?.requireEncryption == true && !header.encrypted) {
        plugin.notifyListeners("error", mapOf(
          "role" to "server",
          "message" to "Server requires encryption but received unencrypted payload.",
          "code" to "ENCRYPTION_REQUIRED"
        ))
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
            plugin.notifyListeners("error", mapOf(
              "role" to "server",
              "message" to "Encrypted payload received but server passphrase not set.",
              "code" to "NO_PASSPHRASE"
            ))
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
        plugin.notifyListeners("receiveComplete", mapOf(
          "role" to "server",
          "encoding" to encodingStr,
          "totalBytes" to totalPlain,
          "filePath" to (outFile?.absolutePath ?: ""),
          "sha256" to sha
        ))
      } else {
        val bytes = memSink?.toByteArray() ?: ByteArray(0)
        if (encoding == LanTransferProtocol.ENCODING_UTF8) {
          plugin.notifyListeners("receiveComplete", mapOf(
            "role" to "server",
            "encoding" to "utf8",
            "totalBytes" to totalPlain,
            "text" to String(bytes, Charsets.UTF_8),
            "sha256" to sha
          ))
        } else {
          plugin.notifyListeners("receiveComplete", mapOf(
            "role" to "server",
            "encoding" to "binary",
            "totalBytes" to totalPlain,
            "base64" to Base64.encodeToString(bytes, Base64.NO_WRAP),
            "sha256" to sha
          ))
        }
      }

      output.flush()
    } catch (e: Exception) {
      plugin.notifyListeners("error", mapOf(
        "role" to "server",
        "message" to ("Connection error: " + (e.message ?: e.toString())),
        "code" to "CONNECTION_ERROR"
      ))
    } finally {
      socket.closeQuietly()
      plugin.notifyListeners("status", mapOf(
        "role" to "server",
        "status" to "connection_closed"
      ))
    }
  }

  private fun emitReceiveProgress(bytes: Long, total: Long) {
    val percent = if (total == 0L) 100.0 else (bytes * 100.0 / total)
    plugin.notifyListeners("progress", mapOf(
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
    val name = receiveFileName?.takeIf { it.isNotBlank() } ?: "lantransfer_${System.currentTimeMillis()}.$ext"
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
    val buffer = ByteArray(64 * 1024)
    var transferred = 0L

    while (transferred < totalBytes) {
      val remaining = totalBytes - transferred
      val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
      val read = input.read(buffer, 0, toRead)
      if (read <= 0) throw EOFException("Unexpected end of stream")

      digest.update(buffer, 0, read)
      output.write(buffer, 0, read)
      transferred += read.toLong()

      emitReceiveProgress(transferred, totalBytes)
    }
    output.flush()
  }

  private fun Closeable.closeQuietly() { try { close() } catch (_: Exception) {} }
}