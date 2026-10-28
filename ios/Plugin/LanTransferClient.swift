import Foundation
import Network
import CryptoKit

/// Client side of the transfer. Every call (connect, send, disconnect) runs in
/// order on `queue`, one at a time, and reports back only when it is really done:
/// connect when the connection is ready and the HELLO is out, a send when every
/// byte has been handed to the network stack, disconnect after the sends before it.
///
/// It used to resolve connect before the connection was ready (a send arriving
/// first was silently dropped), report send_complete as soon as the chunks were
/// queued, and never fail a refused connect (NWConnection just sits in .waiting).
final class LanTransferClient {
  private let queue = DispatchQueue(label: "LanTransferClientQueue")
  private var conn: NWConnection?

  private(set) var connected: Bool = false
  private(set) var remoteHost: String?
  private(set) var remotePort: Int?

  var crypto: LanTransferCrypto.CryptoConfig?
  /// A connect that isn't ready by then fails (refused/unreachable peers otherwise wait forever).
  var connectTimeout: TimeInterval = 7
  /// A send that makes no progress for this long fails (peer lost power/Wi-Fi).
  var writeStallTimeout: TimeInterval = 30

  var onStatus: (([String: Any]) -> Void)?
  var onProgress: (([String: Any]) -> Void)?
  var onError: (([String: Any]) -> Void)?

  // MARK: - Serial operation queue

  private var ops: [(@escaping () -> Void) -> Void] = []
  private var busy = false

  /// Runs `op` after every earlier op has called its `next`. Always on `queue`.
  private func enqueue(_ op: @escaping (_ next: @escaping () -> Void) -> Void) {
    queue.async {
      self.ops.append(op)
      self.runNext()
    }
  }

  private func runNext() {
    guard !busy, !ops.isEmpty else { return }
    busy = true
    let op = ops.removeFirst()
    op { self.queue.async { self.busy = false; self.runNext() } }
  }

  private static func error(_ message: String, code: Int = 1) -> NSError {
    NSError(domain: "LanTransfer", code: code, userInfo: [NSLocalizedDescriptionKey: message])
  }

  // MARK: - Connect / disconnect

  func connect(host: String, port: Int, sessionKey: String?, completion: @escaping (Error?) -> Void) {
    enqueue { next in
      self.closeCurrent()
      guard let p = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else {
        completion(Self.error("Invalid port \(port)")); next(); return
      }
      let c = NWConnection(host: NWEndpoint.Host(host), port: p, using: .tcp)
      self.conn = c
      self.remoteHost = host
      self.remotePort = port

      var finished = false
      var timer: DispatchWorkItem?
      func finish(_ err: Error?) {
        guard !finished else { return }
        finished = true
        timer?.cancel()
        if let err = err {
          c.cancel()
          if self.conn === c { self.conn = nil; self.connected = false }
          self.onError?(["role": "client", "message": "Connect failed: \(err.localizedDescription)", "code": "CONNECT_FAILED"])
        }
        completion(err)
        next()
      }
      let t = DispatchWorkItem { finish(Self.error("Connect to \(host):\(port) timed out after \(Int(self.connectTimeout)) s")) }
      timer = t
      self.queue.asyncAfter(deadline: .now() + self.connectTimeout, execute: t)

      c.stateUpdateHandler = { [weak self] state in
        guard let self = self else { return }
        switch state {
        case .ready:
          guard !finished else { return }
          self.connected = true
          self.onStatus?(["role": "client", "status": "client_connected", "remoteHost": host, "remotePort": port])
          c.send(content: LanTransferProtocol.helloFrame(sessionKey: sessionKey), completion: .contentProcessed { err in
            finish(err)
          })
        case .waiting(let err):
          finish(err)   // refused / no route: NWConnection would otherwise wait forever
        case .failed(let err):
          if self.conn === c { self.connected = false }
          finish(err)
        case .cancelled:
          if self.conn === c { self.connected = false }
        default:
          break
        }
      }
      c.start(queue: self.queue)
    }
  }

  /// Queued behind any pending send, so it can't cut one off.
  func disconnect(completion: (() -> Void)? = nil) {
    enqueue { next in
      self.closeCurrent()
      completion?()
      next()
    }
  }

  private func closeCurrent() {
    guard let c = conn else { return }
    conn = nil
    connected = false
    c.cancel()   // graceful: data already handed to the stack is still delivered
    onStatus?(["role": "client", "status": "client_disconnected"])
  }

  // MARK: - Sends (completion fires once every byte is handed to the stack)

  func sendString(_ text: String, completion: @escaping (Error?) -> Void) {
    sendPayload(encoding: LanTransferProtocol.encodingUtf8, completion: completion) {
      let data = text.data(using: .utf8) ?? Data()
      return (Int64(data.count), Self.dataChunks(data))
    }
  }

  func sendBase64(_ base64: String, completion: @escaping (Error?) -> Void) {
    sendPayload(encoding: LanTransferProtocol.encodingBinary, completion: completion) {
      guard let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters) else {
        throw Self.error("Invalid base64 payload", code: 2)
      }
      return (Int64(data.count), Self.dataChunks(data))
    }
  }

  func sendFile(path: String, completion: @escaping (Error?) -> Void) {
    sendPayload(encoding: LanTransferProtocol.encodingBinary, completion: completion) {
      let url = (path.hasPrefix("file://") ? URL(string: path) : nil) ?? URL(fileURLWithPath: path)
      let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
      guard let size = (attrs[.size] as? NSNumber)?.int64Value else { throw Self.error("File not readable: \(path)") }
      let fh = try FileHandle(forReadingFrom: url)
      return (size, { maxLen in
        let chunk = (try? fh.read(upToCount: maxLen)) ?? Data()
        if chunk.isEmpty { try? fh.close(); return nil }
        return chunk
      })
    }
  }

  private static func dataChunks(_ data: Data) -> (Int) -> Data? {
    var offset = 0
    return { maxLen in
      guard offset < data.count else { return nil }
      let end = min(offset + maxLen, data.count)
      defer { offset = end }
      return data.subdata(in: offset..<end)
    }
  }

  /// `open` runs on the client queue (base64 decode / file open stay off the caller's thread).
  private func sendPayload(
    encoding: UInt8,
    completion: @escaping (Error?) -> Void,
    open: @escaping () throws -> (total: Int64, next: (Int) -> Data?)
  ) {
    enqueue { next in
      var done = false
      var stall: DispatchWorkItem?
      func finish(_ err: Error?) {
        guard !done else { return }
        done = true
        stall?.cancel()
        if let err = err {
          self.onError?(["role": "client", "message": "Send failed: \(err.localizedDescription)", "code": "SEND_FAILED"])
        }
        completion(err)
        next()
      }
      // No progress for writeStallTimeout → the peer is gone: drop the connection and fail.
      func armStall(_ c: NWConnection) {
        stall?.cancel()
        let w = DispatchWorkItem {
          c.forceCancel()
          if self.conn === c { self.conn = nil; self.connected = false }
          finish(Self.error("Send stalled — no progress for \(Int(self.writeStallTimeout)) s"))
        }
        stall = w
        self.queue.asyncAfter(deadline: .now() + self.writeStallTimeout, execute: w)
      }

      guard let c = self.conn, self.connected else { finish(Self.error("Not connected")); return }
      let source: (total: Int64, next: (Int) -> Data?)
      do { source = try open() } catch { finish(error); return }

      let cfg = self.crypto
      let encrypted = (cfg?.enabled == true)
      let chunkSize = cfg?.chunkSize ?? 65_536
      let encodingStr = (encoding == LanTransferProtocol.encodingUtf8) ? "utf8" : "binary"
      let headerMat = LanTransferCrypto.randomHeaderMaterial()
      var key: SymmetricKey?
      if encrypted {
        guard let pass = cfg?.passphrase, !pass.isEmpty else { finish(Self.error("Encryption enabled but passphrase missing")); return }
        do {
          key = SymmetricKey(data: try LanTransferCrypto.pbkdf2KeySHA256(passphrase: pass, salt: headerMat.salt16, iterations: cfg?.iterations ?? 100_000))
        } catch { finish(error); return }
      }

      self.onStatus?(["role": "client", "status": "send_started", "totalBytes": Int(source.total), "encoding": encodingStr, "encrypted": encrypted])
      let header = LanTransferProtocol.dataHeaderFrameV2(
        encoding: encoding, encrypted: encrypted, totalPlainBytes: source.total,
        chunkSize: Int32(chunkSize), salt16: headerMat.salt16, baseNonce8: headerMat.baseNonce8)

      var hasher = SHA256()
      var sent: Int64 = 0
      var lastEmit: Int64 = 0
      var counter = 0

      // One chunk in flight at a time: each send waits for the previous one's
      // completion, so errors surface and memory stays bounded for big videos.
      func sendNext() {
        guard !done else { return }
        guard let plain = source.next(chunkSize) else {
          let hex = hasher.finalize().map { String(format: "%02x", $0) }.joined()
          self.onStatus?(["role": "client", "status": "send_complete", "totalBytes": Int(source.total),
                          "encoding": encodingStr, "encrypted": encrypted, "sha256": hex])
          finish(nil)
          return
        }
        hasher.update(data: plain)
        var frame = plain
        if let key = key {
          do {
            let sealed = try LanTransferCrypto.encryptChunk(
              key: key, nonce: LanTransferCrypto.nonce12(baseNonce8: headerMat.baseNonce8, counter: counter), plain: plain)
            var f = Data()
            var len = UInt32(plain.count).bigEndian
            withUnsafeBytes(of: &len) { f.append(contentsOf: $0) }
            f.append(sealed.ciphertext)
            f.append(sealed.tag)
            frame = f
          } catch { finish(error); return }
          counter += 1
        }
        c.send(content: frame, completion: .contentProcessed { err in
          if let err = err { finish(err); return }
          sent += Int64(plain.count)
          armStall(c)
          if sent - lastEmit >= 1_048_576 || sent >= source.total {
            lastEmit = sent
            let pct = source.total == 0 ? 100.0 : Double(sent) * 100.0 / Double(source.total)
            self.onProgress?(["role": "client", "direction": "send", "bytesTransferred": Int(sent),
                              "totalBytes": Int(source.total), "percent": pct])
          }
          sendNext()
        })
      }

      armStall(c)
      c.send(content: header, completion: .contentProcessed { err in
        if let err = err { finish(err); return }
        sendNext()
      })
    }
  }
}
