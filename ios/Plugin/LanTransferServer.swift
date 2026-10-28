import Foundation
import Network
import CryptoKit

final class LanTransferServer {
  private let queue = DispatchQueue(label: "LanTransferServerQueue")
  private var listener: NWListener?
  // Every live connection. A new one must NOT cancel the previous: that cut off
  // payloads still being read (truncated media, lost deploy acks).
  private var connections: [ObjectIdentifier: NWConnection] = [:]

  private(set) var running: Bool = false
  private(set) var port: UInt16 = 0

  var sessionKey: String?
  var receiveMode: String = "file" // "file" | "memory"
  var receiveFileName: String?
  var crypto: LanTransferCrypto.CryptoConfig?

  var onStatus: (([String: Any]) -> Void)?
  var onProgress: (([String: Any]) -> Void)?
  var onReceiveComplete: (([String: Any]) -> Void)?
  var onError: (([String: Any]) -> Void)?

  private var fileSeq = 0

  /// start/stop run on `queue`, where every connection and listener callback also
  /// runs — they used to mutate the same state from the plugin's thread.
  func start(port desiredPort: Int) throws {
    try queue.sync { try startOnQueue(port: desiredPort) }
  }

  private func startOnQueue(port desiredPort: Int) throws {
    // `running` only turns true on .ready, so it can't guard a second start().
    if listener != nil { return }

    let params = NWParameters.tcp
    let nwPort: NWEndpoint.Port = (desiredPort == 0)
      ? .any
      : (NWEndpoint.Port(rawValue: UInt16(desiredPort)) ?? .any)

    let listener = try NWListener(using: params, on: nwPort)
    self.listener = listener

    listener.stateUpdateHandler = { [weak self, weak listener] state in
      // A stopped listener's late .cancelled must not reset the state of the
      // NEW one (it let a later start() open a second listener).
      guard let self = self, let listener = listener, self.listener === listener else { return }
      switch state {
      case .ready:
        self.running = true
        self.port = listener.port?.rawValue ?? 0
        self.onStatus?([
          "role": "server",
          "status": "server_started",
          "ip": NetUtils.localIPv4Address(),
          "port": Int(self.port)
        ])
      case .failed(let error):
        self.running = false
        self.onError?(["role":"server","message":"Server failed: \(error)","code":"SERVER_ERROR"])
      case .cancelled:
        self.running = false
        self.onStatus?(["role":"server","status":"server_stopped"])
      default:
        break
      }
    }

    listener.newConnectionHandler = { [weak self] conn in
      guard let self = self else { return }
      let id = ObjectIdentifier(conn)
      self.connections[id] = conn
      conn.stateUpdateHandler = { [weak self] state in
        switch state {
        case .cancelled, .failed: self?.queue.async { self?.connections[id] = nil }
        default: break
        }
      }

      self.onStatus?(["role":"server","status":"connection_accepted"])
      conn.start(queue: self.queue)
      self.handleConnection(conn)
    }

    listener.start(queue: queue)
  }

  func stop() {
    queue.sync {
      connections.values.forEach { $0.cancel() }
      connections.removeAll()
      listener?.cancel()
      listener = nil
      running = false
      port = 0
      onStatus?(["role": "server", "status": "server_stopped"])
    }
  }

  private func handleConnection(_ conn: NWConnection) {
    // HELLO: prefix 7 then key bytes
    readExactly(conn, count: 7) { [weak self] res in
      guard let self = self else { return }
      switch res {
      case .failure(let err): self.fail("HELLO read failed: \(err)", conn)
      case .success(let prefix):
        let keyLen = Int(prefix.readUInt16(at: 5))
        self.readExactly(conn, count: keyLen) { res2 in
          switch res2 {
          case .failure(let err2): self.fail("HELLO key read failed: \(err2)", conn)
          case .success(let keyData):
            do {
              let key = try LanTransferProtocol.parseHello(prefix7: prefix, keyData: keyData)
              if let expected = self.sessionKey, !expected.isEmpty, key != expected {
                self.onError?(["role":"server","message":"Session key mismatch; closing connection.","code":"BAD_SESSION_KEY"])
                conn.cancel()
                return
              }
              self.readDataHeader(conn, afterPayload: false)
            } catch {
              self.fail("Bad HELLO frame", conn)
            }
          }
        }
      }
    }
  }

  /// The connection stays open after each payload for the next DATA frame. Between
  /// payloads, EOF is a normal close and 60 s without a new frame ends it.
  private func readDataHeader(_ conn: NWConnection, afterPayload: Bool) {
    var idle: DispatchWorkItem?
    if afterPayload {
      let w = DispatchWorkItem { conn.cancel() }
      idle = w
      queue.asyncAfter(deadline: .now() + 60, execute: w)
    }
    // v2 header is 43 bytes
    readExactly(conn, count: 43) { [weak self] res in
      idle?.cancel()
      guard let self = self else { return }
      switch res {
      case .failure(let err):
        if afterPayload { conn.cancel() } else { self.fail("DATA header read failed: \(err)", conn) }
      case .success(let hdrData):
        do {
          let hdr = try LanTransferProtocol.parseDataHeaderV2(hdrData)

          if (self.crypto?.requireEncryption == true) && !hdr.encrypted {
            self.onError?([
              "role": "server",
              "message": "Server requires encryption but received unencrypted payload.",
              "code": "ENCRYPTION_REQUIRED"
            ])
            self.nakAndClose(conn)
            return
          }

          let encStr = (hdr.encoding == LanTransferProtocol.encodingUtf8) ? "utf8" : "binary"
          self.onStatus?([
            "role": "server",
            "status": "receive_started",
            "totalBytes": Int(hdr.totalPlainBytes),
            "encoding": encStr,
            "encrypted": hdr.encrypted
          ])

          self.receivePayload(conn, header: hdr)
        } catch {
          self.fail("Bad DATA header", conn)
        }
      }
    }
  }

  private func receivePayload(_ conn: NWConnection, header: LanTransferProtocol.DataHeaderV2) {
    let totalPlain = max(0, header.totalPlainBytes)
    var receivedPlain: Int64 = 0
    var hasher = SHA256()

    let writeToFile = (receiveMode == "file")
    var fileURL: URL?
    var fileHandle: FileHandle?
    var mem = Data()

    if writeToFile {
      let ext = (header.encoding == LanTransferProtocol.encodingUtf8) ? "txt" : "bin"
      let name = (receiveFileName?.isEmpty == false)
        ? receiveFileName!
        : "lantransfer_\(Int(Date().timeIntervalSince1970 * 1000))_\(nextFileSeq()).\(ext)"
      let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
      let url = dir.appendingPathComponent(name)
      FileManager.default.createFile(atPath: url.path, contents: nil)
      fileURL = url
      fileHandle = try? FileHandle(forWritingTo: url)
    }

    var lastEmit = Date.distantPast
    func emitProgress() {
      // At most every 200 ms (plus the final one): slow links still move, fast ones
      // don't flood the bridge.
      let now = Date()
      guard receivedPlain >= totalPlain || now.timeIntervalSince(lastEmit) >= 0.2 else { return }
      lastEmit = now
      let pct = (totalPlain == 0) ? 100.0 : (Double(receivedPlain) * 100.0 / Double(totalPlain))
      onProgress?([
        "role": "server",
        "direction": "receive",
        "bytesTransferred": Int(receivedPlain),
        "totalBytes": Int(totalPlain),
        "percent": pct
      ])
    }

    func finish() {
      try? fileHandle?.close()
      // Delivery ACK, then wait for the next payload on the same connection. An
      // older sender never reads the byte and closes — that is a normal EOF.
      defer {
        conn.send(content: Data([LanTransferProtocol.ack]), completion: .contentProcessed { _ in })
        readDataHeader(conn, afterPayload: true)
      }
      let encodingStr = (header.encoding == LanTransferProtocol.encodingUtf8) ? "utf8" : "binary"
      let digest = hasher.finalize()
      let hex = digest.map { String(format: "%02x", $0) }.joined()

      if writeToFile {
        onReceiveComplete?([
          "role": "server",
          "encoding": encodingStr,
          "totalBytes": Int(totalPlain),
          "filePath": fileURL?.path ?? "",
          "sha256": hex
        ])
      } else {
        if header.encoding == LanTransferProtocol.encodingUtf8 {
          onReceiveComplete?([
            "role": "server",
            "encoding": "utf8",
            "totalBytes": Int(totalPlain),
            "text": String(data: mem, encoding: .utf8) ?? "",
            "sha256": hex
          ])
        } else {
          onReceiveComplete?([
            "role": "server",
            "encoding": "binary",
            "totalBytes": Int(totalPlain),
            "base64": mem.base64EncodedString(),
            "sha256": hex
          ])
        }
      }
    }

    // Plain mode: read totalPlain bytes
    if !header.encrypted {
      if totalPlain == 0 { finish(); return }   // nothing to read: complete now (used to hang)
      readStreamPlain(conn, totalBytes: totalPlain) { chunk in
        hasher.update(data: chunk)
        receivedPlain += Int64(chunk.count)
        if writeToFile { try? fileHandle?.write(contentsOf: chunk) } else { mem.append(chunk) }
        emitProgress()
        if receivedPlain >= totalPlain { finish() }
      }
      return
    }

    // Encrypted mode
    guard let cfg = crypto, cfg.enabled, !cfg.passphrase.isEmpty else {
      onError?(["role":"server","message":"Encrypted payload received but passphrase not set.","code":"NO_PASSPHRASE"])
      nakAndClose(conn)
      return
    }

    do {
      let keyData = try LanTransferCrypto.pbkdf2KeySHA256(passphrase: cfg.passphrase, salt: header.salt16, iterations: cfg.iterations)
      let key = SymmetricKey(data: keyData)
      var counter = 0

      func loop() {
        if receivedPlain >= totalPlain { finish(); return }

        // chunkLen u32
        readExactly(conn, count: 4) { [weak self] resLen in
          guard let self = self else { return }
          switch resLen {
          case .failure(let err): self.fail("Encrypted chunkLen read failed: \(err)", conn)
          case .success(let lenData):
            let chunkLen = Int(lenData.readUInt32(at: 0))
            if chunkLen <= 0 || chunkLen > Int(header.chunkSize) {
              self.fail("Bad chunkLen \(chunkLen)", conn)
              return
            }

            // ciphertext chunkLen + tag16
            self.readExactly(conn, count: chunkLen + 16) { resChunk in
              switch resChunk {
              case .failure(let err2):
                self.fail("Encrypted chunk read failed: \(err2)", conn)
              case .success(let blob):
                let ct = blob.subdata(in: 0..<chunkLen)
                let tag = blob.subdata(in: chunkLen..<(chunkLen+16))

                do {
                  let nonce = LanTransferCrypto.nonce12(baseNonce8: header.baseNonce8, counter: counter)
                  let plain = try LanTransferCrypto.decryptChunk(key: key, nonce: nonce, ciphertext: ct, tag: tag)

                  hasher.update(data: plain)
                  receivedPlain += Int64(plain.count)
                  counter += 1

                  if writeToFile { try? fileHandle?.write(contentsOf: plain) } else { mem.append(plain) }
                  emitProgress()

                  loop()
                } catch {
                  self.fail("Decrypt failed: \(error)", conn)
                }
              }
            }
          }
        }
      }

      loop()
    } catch {
      onError?(["role":"server","message":"Key derivation failed: \(error)","code":"KDF_FAILED"])
      nakAndClose(conn)
    }
  }

  private func readStreamPlain(_ conn: NWConnection, totalBytes: Int64, onChunk: @escaping (Data) -> Void) {
    var remaining = totalBytes
    func step() {
      if remaining <= 0 { return }
      let maxLen = min(64 * 1024, Int(remaining))
      conn.receive(minimumIncompleteLength: 1, maximumLength: maxLen) { data, _, isComplete, error in
        if let error = error { self.fail("Receive failed: \(error)", conn); return }
        if isComplete && (data == nil || data!.isEmpty) { self.fail("Unexpected end of stream", conn); return }
        let chunk = data ?? Data()
        remaining -= Int64(chunk.count)
        onChunk(chunk)
        step()
      }
    }
    step()
  }

  private func readExactly(_ conn: NWConnection, count: Int, completion: @escaping (Result<Data, Error>) -> Void) {
    if count == 0 { completion(.success(Data())); return }
    var buffer = Data()

    func step() {
      let remaining = count - buffer.count
      conn.receive(minimumIncompleteLength: 1, maximumLength: remaining) { data, _, isComplete, error in
        if let error = error { completion(.failure(error)); return }
        if isComplete && (data == nil || data!.isEmpty) {
          completion(.failure(NSError(domain: "LanTransfer", code: 999)))
          return
        }
        if let data = data { buffer.append(data) }
        if buffer.count >= count { completion(.success(buffer.prefix(count))) }
        else { step() }
      }
    }

    step()
  }

  private func nextFileSeq() -> Int { fileSeq += 1; return fileSeq }

  private func fail(_ message: String, _ conn: NWConnection) {
    onError?(["role":"server","message":message,"code":"SERVER_ERROR"])
    nakAndClose(conn)
  }

  /// Tell the sender this payload failed (instead of a bare close it could take for
  /// an older receiver's normal end), then close.
  private func nakAndClose(_ conn: NWConnection) {
    conn.send(content: Data([LanTransferProtocol.nak]), completion: .contentProcessed { _ in conn.cancel() })
  }
}

// MARK: - Data helpers

private extension Data {
  func readUInt16(at offset: Int) -> UInt16 {
    let sub = self.subdata(in: offset..<(offset+2))
    return sub.withUnsafeBytes { $0.load(as: UInt16.self).bigEndian }
  }
  func readUInt32(at offset: Int) -> UInt32 {
    let sub = self.subdata(in: offset..<(offset+4))
    return sub.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
  }
}