import Foundation
import Darwin

/**
 * Layered discovery (mirror of the Android implementation):
 *  1) Bonjour (primary) — service type _newtontouch._tcp. with TXT records.
 *     Requires NSLocalNetworkUsageDescription + NSBonjourServices in the HOST app
 *     Info.plist on iOS 14+.
 *  2) UDP broadcast probe/response (fallback) — works on phone hotspots and APs
 *     that filter multicast/mDNS. Fixed UDP port 53517, plaintext probe
 *     "NTLT1?" answered with "NTLT1!{json}".
 */
final class LanTransferDiscovery: NSObject {
  static let serviceType = "_newtontouch._tcp."
  static let udpPort: UInt16 = 53517
  static let probePrefix = "NTLT1?"
  static let respPrefix = "NTLT1!"
  static let probeIntervalMs: Int = 2000
  static let udpLostAfterMs: Int = 6500

  /// (eventName, payload)
  var onEvent: ((String, [String: Any]) -> Void)?

  // ---------- advertise state ----------
  private var netService: NetService?
  private var advertisedName: String?
  private var advertisedPort: Int = 0
  private var advertisedTxt: [String: String] = [:]

  // ---------- browse state ----------
  private var browser: NetServiceBrowser?
  private var resolvingServices: [NetService] = []
  private var bonjourNames = Set<String>()
  private let stateLock = NSLock()

  // ---------- UDP state ----------
  private var responderRunning = false
  private var proberRunning = false
  private var responderFD: Int32 = -1
  private var proberFD: Int32 = -1

  // MARK: - ADVERTISE (receiver side)

  func advertise(name: String, port: Int, txt: [String: String]) {
    stopAdvertise()
    advertisedName = name
    advertisedPort = port
    advertisedTxt = txt

    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      let svc = NetService(domain: "local.", type: LanTransferDiscovery.serviceType, name: name, port: Int32(port))
      var txtData: [String: Data] = [:]
      for (k, v) in txt { txtData[k] = v.data(using: .utf8) ?? Data() }
      svc.setTXTRecord(NetService.data(fromTXTRecord: txtData))
      svc.delegate = self
      svc.publish()
      self.netService = svc
    }

    startUdpResponder()
  }

  func stopAdvertise() {
    DispatchQueue.main.async { [weak self] in
      self?.netService?.stop()
      self?.netService = nil
    }
    advertisedName = nil
    stopUdpResponder()
  }

  // MARK: - DISCOVER (sender side)

  func startDiscovery() {
    stopDiscovery()
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      let b = NetServiceBrowser()
      b.delegate = self
      b.searchForServices(ofType: LanTransferDiscovery.serviceType, inDomain: "local.")
      self.browser = b
    }
    startUdpProber()
  }

  func stopDiscovery() {
    DispatchQueue.main.async { [weak self] in
      self?.browser?.stop()
      self?.browser = nil
      self?.resolvingServices.removeAll()
    }
    stateLock.lock(); bonjourNames.removeAll(); stateLock.unlock()
    stopUdpProber()
  }

  // MARK: - UDP responder

  private func startUdpResponder() {
    stopUdpResponder()
    responderRunning = true
    DispatchQueue.global(qos: .utility).async { [weak self] in
      guard let self = self else { return }
      let fd = socket(AF_INET, SOCK_DGRAM, 0)
      guard fd >= 0 else { return }
      self.responderFD = fd
      var on: Int32 = 1
      setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &on, socklen_t(MemoryLayout<Int32>.size))
      setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &on, socklen_t(MemoryLayout<Int32>.size))
      var tv = timeval(tv_sec: 1, tv_usec: 0)
      setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

      var addr = sockaddr_in()
      addr.sin_family = sa_family_t(AF_INET)
      addr.sin_port = LanTransferDiscovery.udpPort.bigEndian
      addr.sin_addr.s_addr = INADDR_ANY
      let bindRes = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
      }
      if bindRes != 0 {
        close(fd); self.responderFD = -1
        self.onEvent?("error", ["role": "server", "code": "UDP_RESPONDER_FAILED", "message": "UDP bind failed (port \(LanTransferDiscovery.udpPort))"])
        return
      }

      var buf = [UInt8](repeating: 0, count: 2048)
      while self.responderRunning {
        var from = sockaddr_in()
        var fromLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        let n = withUnsafeMutablePointer(to: &from) {
          $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
            recvfrom(fd, &buf, buf.count, 0, sa, &fromLen)
          }
        }
        if n <= 0 { continue }
        guard let msg = String(bytes: buf[0..<n], encoding: .utf8),
              msg.hasPrefix(LanTransferDiscovery.probePrefix),
              let name = self.advertisedName else { continue }

        var peerBuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        var fromAddrCopy = from.sin_addr
        let peerIp = inet_ntop(AF_INET, &fromAddrCopy, &peerBuf, socklen_t(INET_ADDRSTRLEN)).map { String(cString: $0) } ?? ""
        let localIp = NetUtils.addressMatchingPeer(peerIp)?.ip ?? NetUtils.localIPv4Address()

        var json: [String: Any] = [:]
        for (k, v) in self.advertisedTxt { json[k] = v }
        json["v"] = 1
        json["name"] = name
        json["ip"] = localIp
        json["port"] = self.advertisedPort
        guard let body = try? JSONSerialization.data(withJSONObject: json),
              let bodyStr = String(data: body, encoding: .utf8) else { continue }
        let payload = Array((LanTransferDiscovery.respPrefix + bodyStr).utf8)
        _ = withUnsafePointer(to: &from) {
          $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
            sendto(fd, payload, payload.count, 0, sa, fromLen)
          }
        }
      }
      close(fd)
      self.responderFD = -1
    }
  }

  private func stopUdpResponder() {
    responderRunning = false
    if responderFD >= 0 { close(responderFD); responderFD = -1 }
  }

  // MARK: - UDP prober

  private func startUdpProber() {
    stopUdpProber()
    proberRunning = true
    DispatchQueue.global(qos: .utility).async { [weak self] in
      guard let self = self else { return }
      let fd = socket(AF_INET, SOCK_DGRAM, 0)
      guard fd >= 0 else { return }
      self.proberFD = fd
      var on: Int32 = 1
      setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &on, socklen_t(MemoryLayout<Int32>.size))
      var tv = timeval(tv_sec: 0, tv_usec: 400_000)
      setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

      let probe = Array(LanTransferDiscovery.probePrefix.utf8)
      var buf = [UInt8](repeating: 0, count: 4096)
      var lastProbeAt: Int = 0
      var udpSeen: [String: Int] = [:]   // name -> last seen (ms)

      func nowMs() -> Int { Int(Date().timeIntervalSince1970 * 1000) }

      while self.proberRunning {
        let now = nowMs()
        if now - lastProbeAt >= LanTransferDiscovery.probeIntervalMs {
          lastProbeAt = now
          for b in NetUtils.broadcastAddresses() {
            var dest = sockaddr_in()
            dest.sin_family = sa_family_t(AF_INET)
            dest.sin_port = LanTransferDiscovery.udpPort.bigEndian
            if inet_pton(AF_INET, b, &dest.sin_addr) != 1 { continue }
            _ = withUnsafePointer(to: &dest) {
              $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                sendto(fd, probe, probe.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
              }
            }
          }
          // Expire UDP-only devices that stopped replying.
          for (name, last) in udpSeen where now - last > LanTransferDiscovery.udpLostAfterMs {
            udpSeen.removeValue(forKey: name)
            self.stateLock.lock()
            let stillBonjour = self.bonjourNames.contains(name)
            self.stateLock.unlock()
            if !stillBonjour { self.onEvent?("deviceLost", ["name": name]) }
          }
        }

        var from = sockaddr_in()
        var fromLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        let n = withUnsafeMutablePointer(to: &from) {
          $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
            recvfrom(fd, &buf, buf.count, 0, sa, &fromLen)
          }
        }
        if n <= 0 { continue }
        guard let msg = String(bytes: buf[0..<n], encoding: .utf8),
              msg.hasPrefix(LanTransferDiscovery.respPrefix) else { continue }
        let jsonStr = String(msg.dropFirst(LanTransferDiscovery.respPrefix.count))
        guard let data = jsonStr.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = obj["name"] as? String, !name.isEmpty,
              let host = obj["ip"] as? String, !host.isEmpty,
              let port = obj["port"] as? Int, port > 0 else { continue }

        let first = (udpSeen[name] == nil)
        udpSeen[name] = nowMs()
        if first {
          var out: [String: Any] = [:]
          for (k, v) in obj where k != "v" { out[k] = v }
          out["name"] = name
          out["host"] = host
          out["port"] = port
          out["via"] = "udp"
          self.onEvent?("deviceFound", out)
        }
      }
      close(fd)
      self.proberFD = -1
    }
  }

  private func stopUdpProber() {
    proberRunning = false
    if proberFD >= 0 { close(proberFD); proberFD = -1 }
  }
}

// MARK: - NetServiceDelegate (advertise + resolve)

extension LanTransferDiscovery: NetServiceDelegate {
  public func netServiceDidPublish(_ sender: NetService) {
    onEvent?("status", ["role": "server", "status": "advertising", "serviceName": sender.name])
  }

  public func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
    onEvent?("error", ["role": "server", "code": "ADVERTISE_FAILED",
                       "message": "Bonjour publish failed: \(errorDict) (UDP fallback still active)"])
  }

  public func netServiceDidResolveAddress(_ sender: NetService) {
    var host = ""
    for addrData in sender.addresses ?? [] {
      let found: String? = addrData.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> String? in
        guard let base = raw.baseAddress, raw.count >= MemoryLayout<sockaddr>.size else { return nil }
        let sa = base.assumingMemoryBound(to: sockaddr.self)
        guard sa.pointee.sa_family == sa_family_t(AF_INET) else { return nil }
        var sin = base.assumingMemoryBound(to: sockaddr_in.self).pointee
        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard let c = inet_ntop(AF_INET, &sin.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN)) else { return nil }
        return String(cString: c)
      }
      if let ip = found, !ip.hasPrefix("169.254.") { host = ip; break }
    }
    guard !host.isEmpty else { return }

    var out: [String: Any] = ["name": sender.name, "host": host, "port": sender.port, "via": "mdns"]
    if let txtData = sender.txtRecordData() {
      for (k, v) in NetService.dictionary(fromTXTRecord: txtData) {
        out[k] = String(data: v, encoding: .utf8) ?? ""
      }
    }
    stateLock.lock(); bonjourNames.insert(sender.name); stateLock.unlock()
    onEvent?("deviceFound", out)

    DispatchQueue.main.async { [weak self] in
      self?.resolvingServices.removeAll { $0 === sender }
    }
  }

  public func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
    DispatchQueue.main.async { [weak self] in
      self?.resolvingServices.removeAll { $0 === sender }
    }
  }
}

// MARK: - NetServiceBrowserDelegate

extension LanTransferDiscovery: NetServiceBrowserDelegate {
  public func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
    service.delegate = self
    resolvingServices.append(service)   // retain while resolving
    service.resolve(withTimeout: 8)
  }

  public func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
    stateLock.lock(); bonjourNames.remove(service.name); stateLock.unlock()
    onEvent?("deviceLost", ["name": service.name])
  }

  public func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
    onEvent?("error", ["code": "DISCOVERY_FAILED",
                       "message": "Bonjour browse failed: \(errorDict) (UDP fallback still active)"])
  }
}
