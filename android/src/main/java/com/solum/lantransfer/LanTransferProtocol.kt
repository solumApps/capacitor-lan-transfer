package com.solum.lantransfer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

object LanTransferProtocol {
  // "LNT2"
  private val MAGIC = byteArrayOf(0x4C, 0x4E, 0x54, 0x32)

  private const val TYPE_HELLO: Byte = 0x01
  private const val TYPE_DATA: Byte = 0x02

  const val ENCODING_UTF8: Byte = 0x01
  const val ENCODING_BINARY: Byte = 0x02

  const val FLAG_ENCRYPTED: Byte = 0x01

  data class DataHeader(
    val encoding: Byte,
    val flags: Byte,
    val totalPlainBytes: Long,
    val chunkSize: Int,
    val salt16: ByteArray,
    val baseNonce8: ByteArray
  ) {
    val encrypted: Boolean get() = (flags.toInt() and FLAG_ENCRYPTED.toInt()) != 0
  }

  fun writeHello(out: OutputStream, sessionKey: String?) {
    val dos = DataOutputStream(out)
    dos.write(MAGIC)
    dos.writeByte(TYPE_HELLO.toInt())

    val keyBytes = (sessionKey ?: "").toByteArray(Charsets.UTF_8)
    dos.writeShort(keyBytes.size)
    if (keyBytes.isNotEmpty()) dos.write(keyBytes)
    dos.flush()
  }

  fun readHello(input: InputStream): String {
    val dis = DataInputStream(input)
    val magic = ByteArray(4)
    dis.readFully(magic)
    require(magic.contentEquals(MAGIC)) { "Bad magic" }

    val type = dis.readByte()
    require(type == TYPE_HELLO) { "Expected HELLO" }

    val keyLen = dis.readUnsignedShort()
    val keyBytes = ByteArray(keyLen)
    if (keyLen > 0) dis.readFully(keyBytes)
    return String(keyBytes, Charsets.UTF_8)
  }

  fun writeDataHeaderV2(
    out: OutputStream,
    encoding: Byte,
    encrypted: Boolean,
    totalPlainBytes: Long,
    chunkSize: Int,
    salt16: ByteArray,
    baseNonce8: ByteArray
  ) {
    require(salt16.size == 16) { "salt must be 16 bytes" }
    require(baseNonce8.size == 8) { "baseNonce must be 8 bytes" }

    val flags: Byte = if (encrypted) FLAG_ENCRYPTED else 0x00
    val dos = DataOutputStream(out)

    dos.write(MAGIC)
    dos.writeByte(TYPE_DATA.toInt())
    dos.writeByte(encoding.toInt())
    dos.writeByte(flags.toInt())
    dos.writeLong(totalPlainBytes)
    dos.writeInt(chunkSize)
    dos.write(salt16)
    dos.write(baseNonce8)
    dos.flush()
  }

  fun readDataHeaderV2(input: InputStream): DataHeader {
    val dis = DataInputStream(input)
    val magic = ByteArray(4)
    dis.readFully(magic)
    require(magic.contentEquals(MAGIC)) { "Bad magic" }

    val type = dis.readByte()
    require(type == TYPE_DATA) { "Expected DATA" }

    val encoding = dis.readByte()
    val flags = dis.readByte()
    val totalPlain = dis.readLong()
    val chunkSize = dis.readInt()

    val salt16 = ByteArray(16); dis.readFully(salt16)
    val baseNonce8 = ByteArray(8); dis.readFully(baseNonce8)

    require(totalPlain >= 0) { "Invalid total size" }
    require(chunkSize in 1024..(1024 * 1024)) { "Invalid chunkSize" }

    return DataHeader(
      encoding = encoding,
      flags = flags,
      totalPlainBytes = totalPlain,
      chunkSize = chunkSize,
      salt16 = salt16,
      baseNonce8 = baseNonce8
    )
  }

  // Encrypted chunk frame: int32 plainChunkLen + ciphertext(plainChunkLen) + tag(16)
  fun writeEncryptedChunk(out: OutputStream, plainChunkLen: Int, ciphertext: ByteArray, tag16: ByteArray) {
    val dos = DataOutputStream(out)
    dos.writeInt(plainChunkLen)
    dos.write(ciphertext, 0, plainChunkLen)
    dos.write(tag16, 0, 16)
  }

  fun readEncryptedChunkHeader(input: InputStream): Int {
    val dis = DataInputStream(input)
    return dis.readInt()
  }

  fun readFully(input: InputStream, buf: ByteArray, len: Int) {
    val dis = DataInputStream(input)
    dis.readFully(buf, 0, len)
  }
}