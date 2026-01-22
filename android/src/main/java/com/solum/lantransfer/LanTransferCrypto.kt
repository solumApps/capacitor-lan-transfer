package com.solum.lantransfer

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object LanTransferCrypto {
  private val rng = SecureRandom()

  data class CryptoConfig(
    val enabled: Boolean,
    val passphrase: String,
    val iterations: Int = 100_000,
    val chunkSize: Int = 65_536,
    val requireEncryption: Boolean = false
  )

  data class HeaderMaterial(
    val salt: ByteArray,      // 16 bytes
    val baseNonce8: ByteArray // 8 bytes
  )

  fun randomHeaderMaterial(): HeaderMaterial {
    val salt = ByteArray(16).also { rng.nextBytes(it) }
    val baseNonce8 = ByteArray(8).also { rng.nextBytes(it) }
    return HeaderMaterial(salt, baseNonce8)
  }

  fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKey {
    val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
    val skf = try {
      SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    } catch (_: Exception) {
      SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
    }
    val keyBytes = skf.generateSecret(spec).encoded
    return SecretKeySpec(keyBytes, "AES")
  }

  fun nonce12(baseNonce8: ByteArray, counter: Int): ByteArray {
    val nonce = ByteArray(12)
    System.arraycopy(baseNonce8, 0, nonce, 0, 8)
    nonce[8] = ((counter ushr 24) and 0xFF).toByte()
    nonce[9] = ((counter ushr 16) and 0xFF).toByte()
    nonce[10] = ((counter ushr 8) and 0xFF).toByte()
    nonce[11] = (counter and 0xFF).toByte()
    return nonce
  }

  fun encryptChunk(key: SecretKey, nonce12: ByteArray, plain: ByteArray, len: Int): Pair<ByteArray, ByteArray> {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce12))
    val out = cipher.doFinal(plain, 0, len) // ciphertext + tag(16)
    val tag = out.copyOfRange(out.size - 16, out.size)
    val ct = out.copyOfRange(0, out.size - 16)
    return Pair(ct, tag)
  }

  fun decryptChunk(key: SecretKey, nonce12: ByteArray, ct: ByteArray, tag16: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce12))
    val combined = ByteArray(ct.size + 16)
    System.arraycopy(ct, 0, combined, 0, ct.size)
    System.arraycopy(tag16, 0, combined, ct.size, 16)
    return cipher.doFinal(combined)
  }
}