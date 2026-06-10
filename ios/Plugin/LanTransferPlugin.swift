import Foundation
import Capacitor

@objc(LanTransferPlugin)
public class LanTransferPlugin: CAPPlugin {
  private let server = LanTransferServer()
  private let client = LanTransferClient()
  private let discovery = LanTransferDiscovery()

  public override func load() {
    server.onStatus = { [weak self] obj in self?.notifyListeners("status", data: obj) }
    server.onProgress = { [weak self] obj in self?.notifyListeners("progress", data: obj) }
    server.onReceiveComplete = { [weak self] obj in self?.notifyListeners("receiveComplete", data: obj) }
    server.onError = { [weak self] obj in self?.notifyListeners("error", data: obj) }

    client.onStatus = { [weak self] obj in self?.notifyListeners("status", data: obj) }
    client.onProgress = { [weak self] obj in self?.notifyListeners("progress", data: obj) }
    client.onError = { [weak self] obj in self?.notifyListeners("error", data: obj) }

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
              connect(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
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

    client.crypto = cryptoCfg
    client.connect(host: host, port: serverPort, sessionKey: sessionKey)

    call.resolve([
      "connected": true,
      "role": "client",
      "remoteHost": host,
      "remotePort": serverPort
    ])
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
    client.crypto = parseCrypto(call)
    client.connect(host: host, port: port, sessionKey: call.getString("sessionKey"))
    call.resolve(["connected": true, "role": "client", "remoteHost": host, "remotePort": port])
  }

  @objc func disconnect(_ call: CAPPluginCall) { client.disconnect(); call.resolve() }

  @objc func getConnectionInfo(_ call: CAPPluginCall) {
    call.resolve([
      "connected": client.connected,
      "role": "client",
      "remoteHost": client.remoteHost as Any,
      "remotePort": client.remotePort as Any
    ])
  }

  @objc func setReceiveMode(_ call: CAPPluginCall) {
    server.receiveMode = call.getString("receiveMode") ?? "file"
    server.receiveFileName = call.getString("receiveFileName")
    call.resolve()
  }

  @objc func sendString(_ call: CAPPluginCall) {
    guard let text = call.getString("text") else { call.reject("text is required"); return }
    do { try client.sendString(text); call.resolve() }
    catch { call.reject("sendString failed: \(error)") }
  }

  @objc func sendBase64(_ call: CAPPluginCall) {
    guard let base64 = call.getString("base64") else { call.reject("base64 is required"); return }
    do { try client.sendBase64(base64); call.resolve() }
    catch { call.reject("sendBase64 failed: \(error)") }
  }

  @objc func sendFile(_ call: CAPPluginCall) {
    guard let path = call.getString("path") else { call.reject("path is required"); return }
    client.sendFile(path: path)
    call.resolve()
  }
}