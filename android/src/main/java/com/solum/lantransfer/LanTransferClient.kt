package com.solum.lantransfer

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
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey

class LanTransferClient(
  private val context: Context,
  private val plugin: Plugin
) {
  private val executor = Executors.newSingleThreadExecutor()
  private var socket: Socket? = null
  private val connected = AtomicBoolean(false)

  @Volatile private var crypto: LanTransferCrypto.CryptoConfig? = null
  fun setCrypto(config: LanTransferCrypto.CryptoConfig?) { crypto = config }

  fun isConnected(): Boolean = connected.get()

  fun connect(host: String, port: Int, sessionKey: String?, timeoutMs: Int = 7000) {
    disconnect()
    val s = Socket()
    s.tcpNoDelay = true
    s.connect(InetSocketAddress(host, port), timeoutMs)
    socket = s
    connected.set(true)

    plugin.notifyListeners("status", mapOf(
      "role" to "client",
      "status" to "client_connected",
      "remoteHost" to host,
      "remotePort" to port
    ))

    val out = BufferedOutputStream(s.getOutputStream())
    LanTransferProtocol.writeHello(out, sessionKey)
    out.flush()
  }

  fun disconnect() {
    connected.set(false)
    try { socket?.close() } catch (_: Exception) {}
    socket = null
    plugin.notifyListeners("status", mapOf(
      "role" to "client",
      "status" to "client_disconnected"
    ))
  }

  fun sendString(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    sendStream(ByteArrayInputStream(bytes), bytes.size.toLong(), LanTransferProtocol.ENCODING_UTF8)
  }

  fun sendBase64(base64: String) {
    val bytes = Base64.decode(base64, Base64.DEFAULT)
    sendStream(ByteArrayInputStream(bytes), bytes.size.toLong(), LanTransferProtocol.ENCODING_BINARY)
  }

  fun sendFile(pathOrUri: String) {
    val (stream, total) = openStreamAndSize(pathOrUri)
    sendStream(BufferedInputStream(stream), total, LanTransferProtocol.ENCODING_BINARY)
  }

  private fun sendStream(input: InputStream, totalPlain: Long, encoding: Byte) {
    val s = socket ?: throw IllegalStateException("Not connected")
    val out = BufferedOutputStream(s.getOutputStream())
    val cfg = crypto

    executor.execute {
      input.use { ins ->
        try {
          val encrypted = (cfg?.enabled == true)
          val chunkSize = cfg?.chunkSize ?: 65_536
          val iters = cfg?.iterations ?: 100_000
          val encodingStr = if (encoding == LanTransferProtocol.ENCODING_UTF8) "utf8" else "binary"

          plugin.notifyListeners("status", mapOf(
            "role" to "client",
            "status" to "send_started",
            "totalBytes" to totalPlain,
            "encoding" to encodingStr,
            "encrypted" to encrypted
          ))

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
            val buffer = ByteArray(64 * 1024)
            var plainSent = 0L
            while (plainSent < totalPlain) {
              val remaining = totalPlain - plainSent
              val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
              val read = ins.read(buffer, 0, toRead)
              if (read <= 0) throw EOFException("Unexpected end of stream")

              digest.update(buffer, 0, read)
              out.write(buffer, 0, read)

              plainSent += read.toLong()
              emitProgress(plainSent, totalPlain)
            }

            out.flush()
            emitSendComplete(totalPlain, encodingStr, false, HashUtils.toHex(digest.digest()))
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

            plainSent += read.toLong()
            counter += 1
            emitProgress(plainSent, totalPlain)
          }

          out.flush()
          emitSendComplete(totalPlain, encodingStr, true, HashUtils.toHex(digest.digest()))
        } catch (e: Exception) {
          plugin.notifyListeners("error", mapOf(
            "role" to "client",
            "message" to ("Send failed: " + (e.message ?: e.toString())),
            "code" to "SEND_FAILED"
          ))
        }
      }
    }
  }

  private fun emitProgress(bytes: Long, total: Long) {
    val percent = if (total == 0L) 100.0 else (bytes * 100.0 / total)
    plugin.notifyListeners("progress", mapOf(
      "role" to "client",
      "direction" to "send",
      "bytesTransferred" to bytes,
      "totalBytes" to total,
      "percent" to percent
    ))
  }

  private fun emitSendComplete(totalPlain: Long, encodingStr: String, encrypted: Boolean, sha256: String) {
    plugin.notifyListeners("status", mapOf(
      "role" to "client",
      "status" to "send_complete",
      "totalBytes" to totalPlain,
      "encoding" to encodingStr,
      "encrypted" to encrypted,
      "sha256" to sha256
    ))
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