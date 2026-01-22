import Foundation
import Network
import CryptoKit

final class LanTransferClient {
  private let queue = DispatchQueue(label: "LanTransferClientQueue")
  private var conn: NWConnection?

  private(set) var connected: Bool = false
  private(set) var remoteHost: String?
  private(set) var remotePort: Int?

  var crypto: LanTransferCrypto.CryptoConfig?

  var onStatus: (([String: Any]) -> Void)?
  var onProgress: (([String: Any]) -> Void)?
  var onError: (([String: Any]) -> Void)?

  func connect(host: String, port: Int, sessionKey: String?) {
    disconnect()
    let h = NWEndpoint.Host(host)
    let p = NWEndpoint.Port(rawValue: UInt16(port)) ?? .any
    let c = NWConnection(host: h, port: p, using: .tcp)
    conn = c
    remoteHost = host
    remotePort = port

    c.stateUpdateHandler = { [weak self] state in
      guard let self = self else { return }
      switch state {
      case .ready:
        self.connected = true
        self.onStatus?([
          "role": "client",
          "status": "client_connected",
          "remoteHost": host,
          "remotePort": port
        ])
        let hello = LanTransferProtocol.helloFrame(sessionKey: sessionKey)
        self.sendFrame(hello) { result in
          if case .failure(let err) = result {
            self.onError?(["role":"client","message":"HELLO send failed: \(err)","code":"SEND_FAILED"])
            self.disconnect()
          }
        }
      case .failed(let error):
        self.connected = false
        self.onError?(["role":"client","message":"Connect failed: \(error)","code":"CONNECT_FAILED"])
      case .cancelled:
        self.connected = false
        self.onStatus?(["role":"client","status":"client_disconnected"])
      default:
        break
      }
    }

    c.start(queue: queue)
  }

  func disconnect() {
    connected = false
    conn?.cancel()
    conn = nil
    onStatus?(["role":"client","status":"client_disconnected"])
  }

  func sendString(_ text: String) throws {
    let data = text.data(using: .utf8) ?? Data()
    try sendData(data, encoding: LanTransferProtocol.encodingUtf8)
  }

  func sendBase64(_ base64: String) throws {
    guard let data = Data(base64Encoded: base64) else { throw NSError(domain: "LanTransfer", code: 2) }
    try sendData(data, encoding: LanTransferProtocol.encodingBinary)
  }

  func sendFile(path: String) {
    guard let _ = conn, connected else { return }

    let url: URL
    if path.hasPrefix("file://"), let u = URL(string: path) {
      url = u
    } else {
      url = URL(fileURLWithPath: path)
    }

    guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
          let sizeNum = attrs[.size] as? NSNumber else {
      onError?(["role":"client","message":"File not found/readable: \(path)","code":"FILE_ERROR"])
      return
    }

    let totalPlain = sizeNum.int64Value
    sendStream(totalPlain: totalPlain, encoding: LanTransferProtocol.encodingBinary) { chunkSize, writeChunk in
      guard let fh = try? FileHandle(forReadingFrom: url) else {
        self.onError?(["role":"client","message":"Unable to open file: \(url.path)","code":"FILE_ERROR"])
        return
      }
      defer { try? fh.close() }

      while true {
        let data = try? fh.read(upToCount: chunkSize)
        let chunk = data ?? Data()
        if chunk.isEmpty { break }
        writeChunk(chunk)
      }
    }
  }

  private func sendData(_ data: Data, encoding: UInt8) throws {
    sendStream(totalPlain: Int64(data.count), encoding: encoding) { chunkSize, writeChunk in
      var offset = 0
      while offset < data.count {
        let end = min(offset + chunkSize, data.count)
        writeChunk(data.subdata(in: offset..<end))
        offset = end
      }
    }
  }

  private func sendStream(
    totalPlain: Int64,
    encoding: UInt8,
    producer: @escaping (_ chunkSize: Int, _ writeChunk: (Data) -> Void) -> Void
  ) {
    guard let conn = conn, connected else { return }

    let cfg = crypto
    let encrypted = (cfg?.enabled == true)
    let chunkSize = cfg?.chunkSize ?? 65_536
    let iters = cfg?.iterations ?? 100_000
    let encodingStr = (encoding == LanTransferProtocol.encodingUtf8) ? "utf8" : "binary"

    onStatus?([
      "role": "client",
      "status": "send_started",
      "totalBytes": Int(totalPlain),
      "encoding": encodingStr,
      "encrypted": encrypted
    ])

    let headerMat = LanTransferCrypto.randomHeaderMaterial()
    let header = LanTransferProtocol.dataHeaderFrameV2(
      encoding: encoding,
      encrypted: encrypted,
      totalPlainBytes: totalPlain,
      chunkSize: Int32(chunkSize),
      salt16: headerMat.salt16,
      baseNonce8: headerMat.baseNonce8
    )

    sendFrame(header) { [weak self] result in
      guard let self = self else { return }
      switch result {
      case .failure(let err):
        self.onError?(["role":"client","message":"Header send failed: \(err)","code":"SEND_FAILED"])
      case .success:
        var hasher = SHA256()
        var sentPlain: Int64 = 0

        func emitProgress() {
          let pct = (totalPlain == 0) ? 100.0 : (Double(sentPlain) * 100.0 / Double(totalPlain))
          self.onProgress?([
            "role": "client", "direction": "send",
            "bytesTransferred": Int(sentPlain),
            "totalBytes": Int(totalPlain),
            "percent": pct
          ])
        }

        func emitSendComplete(encryptedFlag: Bool) {
          let digest = hasher.finalize()
          let hex = digest.map { String(format: "%02x", $0) }.joined()
          self.onStatus?([
            "role": "client",
            "status": "send_complete",
            "totalBytes": Int(totalPlain),
            "encoding": encodingStr,
            "encrypted": encryptedFlag,
            "sha256": hex
          ])
        }

        if !encrypted {
          producer(chunkSize) { chunk in
            hasher.update(data: chunk)
            conn.send(content: chunk, completion: .contentProcessed { _ in })
            sentPlain += Int64(chunk.count)
            emitProgress()
          }
          emitSendComplete(encryptedFlag: false)
          return
        }

        guard let pass = cfg?.passphrase, !pass.isEmpty else {
          self.onError?(["role":"client","message":"Encryption enabled but passphrase missing","code":"NO_PASSPHRASE"])
          return
        }

        do {
          let keyData = try LanTransferCrypto.pbkdf2KeySHA256(passphrase: pass, salt: headerMat.salt16, iterations: iters)
          let key = SymmetricKey(data: keyData)
          var counter = 0

          producer(chunkSize) { plainChunk in
            do {
              hasher.update(data: plainChunk)

              let nonce = LanTransferCrypto.nonce12(baseNonce8: headerMat.baseNonce8, counter: counter)
              let sealed = try LanTransferCrypto.encryptChunk(key: key, nonce: nonce, plain: plainChunk)

              var frame = Data()
              frame.appendUInt32(UInt32(plainChunk.count))
              frame.append(sealed.ciphertext)
              frame.append(sealed.tag)

              conn.send(content: frame, completion: .contentProcessed { _ in })
              sentPlain += Int64(plainChunk.count)
              counter += 1
              emitProgress()
            } catch {
              self.onError?(["role":"client","message":"Encrypt/send failed: \(error)","code":"SEND_FAILED"])
            }
          }

          emitSendComplete(encryptedFlag: true)
        } catch {
          self.onError?(["role":"client","message":"Key derivation failed: \(error)","code":"KDF_FAILED"])
        }
      }
    }
  }

  private func sendFrame(_ data: Data, completion: @escaping (Result<Void, Error>) -> Void) {
    guard let conn = conn else {
      completion(.failure(NSError(domain: "LanTransfer", code: 999)))
      return
    }
    conn.send(content: data, completion: .contentProcessed { error in
      if let error = error { completion(.failure(error)) }
      else { completion(.success(())) }
    })
  }
}

private extension Data {
  mutating func appendUInt32(_ value: UInt32) {
    var v = value.bigEndian
    withUnsafeBytes(of: &v) { append(contentsOf: $0) }
  }
}