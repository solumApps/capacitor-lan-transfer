import Foundation
import Capacitor

@objc(LanTransferPlugin)
public class LanTransferPlugin: CAPPlugin {
  private let server = LanTransferServer()
  /// One client (connection + serial queue) per host:port, so sends to different
  /// displays run in parallel. Calls that name no host use the last connect()'s.
  private var clients: [String: LanTransferClient] = [:]
  private var current: LanTransferClient?
  private let clientsLock = NSLock()
  private let discovery = LanTransferDiscovery()

  public override func load() {
    server.onStatus = { [weak self] obj in self?.notifyListeners("status", data: obj) }
    server.onProgress = { [weak self] obj in self?.notifyListeners("progress", data: obj) }
    server.onReceiveComplete = { [weak self] obj in self?.notifyListeners("receiveComplete", data: obj) }
    server.onError = { [weak self] obj in self?.notifyListeners("error", data: obj) }

    discovery.onEvent = { [weak self] event, data in
      self?.notifyListeners(event, data: data)
    }
  }

  // MARK: - Discovery (Bonjour + UDP broadcast fallback)

  @objc func advertise(_ call: CAPPluginCall) {
    guard let name = call.getString("name"), let port = call.getInt("port") else {
      call.reject("name and port are required"); return
    }
    var txt: [String: String] = [:]
    if let o = call.getObject("txt") {
      for (k, v) in o { txt[k] = "\(v)" }
    }
    discovery.advertise(name: name, port: port, txt: txt)
    call.resolve()
  }

  @objc func stopAdvertise(_ call: CAPPluginCall) {
    discovery.stopAdvertise()
    call.resolve()
  }

  @objc func startDiscovery(_ call: CAPPluginCall) {
    discovery.startDiscovery()
    call.resolve()
  }

  @objc func stopDiscovery(_ call: CAPPluginCall) {
    discovery.stopDiscovery()
    call.resolve()
  }

  // MARK: - Network info / reachability

  @objc func getNetworkInfo(_ call: CAPPluginCall) {
    let interfaces = NetUtils.allIPv4Addresses().map { i -> [String: Any] in
      ["name": i.name, "ip": i.ip]
    }
    call.resolve([
      "ip": NetUtils.localIPv4Address(),
      "interfaces": interfaces
    ])
  }

  @objc func checkReachable(_ call: CAPPluginCall) {
    guard let host = call.getString("host"), let port = call.getInt("port") else {
      call.reject("host and port are required"); return
    }
    let timeoutMs = call.getInt("timeoutMs") ?? 2500
    DispatchQueue.global(qos: .utility).async {
      let started = Date()
      let fd = socket(AF_INET, SOCK_STREAM, 0)
      var ok = false
      if fd >= 0 {
        var tv = timeval(tv_sec: timeoutMs / 1000, tv_usec: Int32((timeoutMs % 1000) * 1000))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = UInt16(port).bigEndian
        if inet_pton(AF_INET, host, &addr.sin_addr) == 1 {
          let res = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
              Darwin.connect(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size)) // the POSIX call, not this plugin's connect(_:)
            }
          }
          ok = (res == 0)
        }
        close(fd)
      }
      call.resolve([
        "reachable": ok,
        "latencyMs": Int(Date().timeIntervalSince(started) * 1000)
      ])
    }
  }

  private func parseCrypto(_ call: CAPPluginCall) -> LanTransferCrypto.CryptoConfig? {
    guard let enc = call.getObject("encryption") else { return nil }
    let enabled = enc["enabled"] as? Bool ?? false
    if !enabled {
      return LanTransferCrypto.CryptoConfig(enabled: false, passphrase: "", iterations: 100_000, chunkSize: 65_536, requireEncryption: false)
    }
    guard let pass = enc["passphrase"] as? String else { return nil }
    let iterations = enc["iterations"] as? Int ?? 100_000
    let chunkSize = enc["chunkSize"] as? Int ?? 65_536
    let requireEncryption = enc["requireEncryption"] as? Bool ?? false
    return LanTransferCrypto.CryptoConfig(enabled: true, passphrase: pass, iterations: iterations, chunkSize: chunkSize, requireEncryption: requireEncryption)
  }

  @objc func initialize(_ call: CAPPluginCall) {
    let role = call.getString("role") ?? "client"
    let sessionKey = call.getString("sessionKey")
    let receiveMode = call.getString("receiveMode") ?? "file"
    let receiveFileName = call.getString("receiveFileName")
    let cryptoCfg = parseCrypto(call)

    if role == "server" {
      server.sessionKey = sessionKey
      server.receiveMode = receiveMode
      server.receiveFileName = receiveFileName
      server.crypto = cryptoCfg

      do {
        try server.start(port: call.getInt("port") ?? 0)
        call.resolve([
          "running": true,
          "ip": NetUtils.localIPv4Address(),
          "port": Int(server.port)
        ])
      } catch {
        call.reject("Failed to start server: \(error)")
      }
      return
    }

    guard let host = call.getString("host"),
          let serverPort = call.getInt("serverPort") else {
      call.reject("host and serverPort are required for client initialize()")
      return
    }

    let c = client(host: host, port: serverPort)
    c.crypto = cryptoCfg
    c.connect(host: host, port: serverPort, sessionKey: sessionKey) { err in
      if let err = err { call.reject("Connect failed: \(err.localizedDescription)", "CONNECT_FAILED"); return }
      call.resolve([
        "connected": true,
        "role": "client",
        "remoteHost": host,
        "remotePort": serverPort
      ])
    }
  }

  @objc func startServer(_ call: CAPPluginCall) {
    server.sessionKey = call.getString("sessionKey")
    server.crypto = parseCrypto(call)
    do {
      try server.start(port: call.getInt("port") ?? 0)
      call.resolve([
        "running": true,
        "ip": NetUtils.localIPv4Address(),
        "port": Int(server.port)
      ])
    } catch {
      call.reject("Failed to start server: \(error)")
    }
  }

  @objc func stopServer(_ call: CAPPluginCall) { server.stop(); call.resolve() }

  @objc func getServerInfo(_ call: CAPPluginCall) {
    call.resolve([
      "running": server.running,
      "ip": NetUtils.localIPv4Address(),
      "port": Int(server.port)
    ])
  }

  @objc func connect(_ call: CAPPluginCall) {
    guard let host = call.getString("host"),
          let port = call.getInt("port") else {
      call.reject("host and port are required")
      return
    }
    let c = client(host: host, port: port)
    c.crypto = parseCrypto(call)
    // Resolves only once the connection is ready (was: immediately, so a send
    // that arrived first was silently dropped); rejects on refusal/timeout.
    c.connect(host: host, port: port, sessionKey: call.getString("sessionKey")) { err in
      if let err = err { call.reject("Connect failed: \(err.localizedDescription)", "CONNECT_FAILED") }
      else { call.resolve(["connected": true, "role": "client", "remoteHost": host, "remotePort": port]) }
    }
  }

  /// Closes the named connection, or every one when no host is given.
  @objc func disconnect(_ call: CAPPluginCall) {
    clientsLock.lock()
    let targets = call.getString("host") == nil ? Array(clients.values) : [target(of: call)].compactMap { $0 }
    clientsLock.unlock()
    let group = DispatchGroup()
    for c in targets { group.enter(); c.disconnect { group.leave() } }
    group.notify(queue: .global()) { call.resolve() }
  }

  @objc func getConnectionInfo(_ call: CAPPluginCall) {
    clientsLock.lock(); let c = current; clientsLock.unlock()
    call.resolve([
      "connected": c?.connected ?? false,
      "role": "client",
      "remoteHost": c?.remoteHost as Any,
      "remotePort": c?.remotePort as Any
    ])
  }

  /// The client for host:port (created on first use, with events that name it).
  private func client(host: String, port: Int) -> LanTransferClient {
    clientsLock.lock(); defer { clientsLock.unlock() }
    let key = "\(host):\(port)"
    if let c = clients[key] { current = c; return c }
    let c = LanTransferClient()
    let tag: ([String: Any]) -> [String: Any] = { var o = $0; o["remoteHost"] = host; o["remotePort"] = port; return o }
    c.onStatus = { [weak self] obj in self?.notifyListeners("status", data: tag(obj)) }
    c.onProgress = { [weak self] obj in self?.notifyListeners("progress", data: tag(obj)) }
    c.onError = { [weak self] obj in self?.notifyListeners("error", data: tag(obj)) }
    clients[key] = c
    current = c
    return c
  }

  /// The connection a send/disconnect names (host + port), else the last connect()'s.
  /// Callers hold `clientsLock`.
  private func target(of call: CAPPluginCall) -> LanTransferClient? {
    if let host = call.getString("host"), let port = call.getInt("port") { return clients["\(host):\(port)"] }
    return current
  }

  private func sendTarget(_ call: CAPPluginCall) -> LanTransferClient? {
    clientsLock.lock(); defer { clientsLock.unlock() }
    guard let c = target(of: call) else { call.reject("Not connected", "NOT_CONNECTED"); return nil }
    return c
  }

  @objc func setReceiveMode(_ call: CAPPluginCall) {
    server.receiveMode = call.getString("receiveMode") ?? "file"
    server.receiveFileName = call.getString("receiveFileName")
    call.resolve()
  }

  @objc func sendString(_ call: CAPPluginCall) {
    guard let text = call.getString("text") else { call.reject("text is required"); return }
    guard let c = sendTarget(call) else { return }
    c.sendString(text) { err in
      if let err = err { call.reject("sendString failed: \(err.localizedDescription)", "SEND_FAILED") } else { call.resolve() }
    }
  }

  @objc func sendBase64(_ call: CAPPluginCall) {
    guard let base64 = call.getString("base64") else { call.reject("base64 is required"); return }
    guard let c = sendTarget(call) else { return }
    c.sendBase64(base64) { err in
      if let err = err { call.reject("sendBase64 failed: \(err.localizedDescription)", "SEND_FAILED") } else { call.resolve() }
    }
  }

  @objc func sendFile(_ call: CAPPluginCall) {
    guard let path = call.getString("path") else { call.reject("path is required"); return }
    guard let c = sendTarget(call) else { return }
    c.sendFile(path: path) { err in
      if let err = err { call.reject("sendFile failed: \(err.localizedDescription)", "SEND_FAILED") } else { call.resolve() }
    }
  }
}