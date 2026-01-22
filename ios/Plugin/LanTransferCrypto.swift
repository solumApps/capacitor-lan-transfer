import Foundation
import CryptoKit
import CommonCrypto

final class LanTransferCrypto {
  struct CryptoConfig {
    var enabled: Bool
    var passphrase: String
    var iterations: Int
    var chunkSize: Int
    var requireEncryption: Bool
  }

  struct HeaderMaterial {
    let salt16: Data     // 16
    let baseNonce8: Data // 8
  }

  static func randomHeaderMaterial() -> HeaderMaterial {
    var salt = Data(count: 16)
    _ = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }

    var nonce8 = Data(count: 8)
    _ = nonce8.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 8, $0.baseAddress!) }

    return HeaderMaterial(salt16: salt, baseNonce8: nonce8)
  }

  static func pbkdf2KeySHA256(passphrase: String, salt: Data, iterations: Int, keyLen: Int = 32) throws -> Data {
    let passwordData = passphrase.data(using: .utf8) ?? Data()
    var derived = Data(count: keyLen)

    let result = derived.withUnsafeMutableBytes { derivedBytes in
      salt.withUnsafeBytes { saltBytes in
        passwordData.withUnsafeBytes { passBytes in
          CCKeyDerivationPBKDF(
            CCPBKDFAlgorithm(kCCPBKDF2),
            passBytes.bindMemory(to: Int8.self).baseAddress,
            passwordData.count,
            saltBytes.bindMemory(to: UInt8.self).baseAddress,
            salt.count,
            CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
            UInt32(iterations),
            derivedBytes.bindMemory(to: UInt8.self).baseAddress,
            keyLen
          )
        }
      }
    }

    if result != kCCSuccess {
      throw NSError(domain: "LanTransfer", code: Int(result), userInfo: [
        NSLocalizedDescriptionKey: "PBKDF2 failed"
      ])
    }
    return derived
  }

  static func nonce12(baseNonce8: Data, counter: Int) -> AES.GCM.Nonce {
    var n = Data()
    n.append(baseNonce8) // 8
    var c = UInt32(counter).bigEndian
    withUnsafeBytes(of: &c) { n.append(contentsOf: $0) } // +4 = 12
    return try! AES.GCM.Nonce(data: n)
  }

  static func encryptChunk(key: SymmetricKey, nonce: AES.GCM.Nonce, plain: Data) throws -> (ciphertext: Data, tag: Data) {
    let sealed = try AES.GCM.seal(plain, using: key, nonce: nonce)
    return (sealed.ciphertext, sealed.tag) // tag = 16 bytes
  }

  static func decryptChunk(key: SymmetricKey, nonce: AES.GCM.Nonce, ciphertext: Data, tag: Data) throws -> Data {
    let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
    return try AES.GCM.open(box, using: key)
  }
}