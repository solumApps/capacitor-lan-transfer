import Foundation

final class NetUtils {

  struct IfaceIp {
    let name: String
    let ip: String
    let netmask: String
    let broadcast: String?
  }

  /// All usable (non-loopback, non-link-local) IPv4 addresses, best first.
  /// en0 = Wi-Fi, bridge100 = personal hotspot, en1/bridge0 = other LAN, pdp_ip0 = cellular (last).
  static func allIPv4Addresses() -> [IfaceIp] {
    var out: [IfaceIp] = []

    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return out }
    defer { freeifaddrs(ifaddr) }

    var ptr: UnsafeMutablePointer<ifaddrs>? = firstAddr
    while let p = ptr {
      defer { ptr = p.pointee.ifa_next }
      let interface = p.pointee
      guard let ifaAddr = interface.ifa_addr, ifaAddr.pointee.sa_family == UInt8(AF_INET) else { continue }
      let flags = Int32(interface.ifa_flags)
      if (flags & IFF_UP) == 0 || (flags & IFF_LOOPBACK) != 0 { continue }

      let name = String(cString: interface.ifa_name)
      var addr = ifaAddr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
      var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
      guard let ipC = inet_ntop(AF_INET, &addr.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN)) else { continue }
      let ip = String(cString: ipC)
      if ip.hasPrefix("169.254.") || ip == "127.0.0.1" { continue }

      var maskStr = "255.255.255.0"
      var bcast: String? = nil
      if let ifaMask = interface.ifa_netmask {
        var mask = ifaMask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
        var mbuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        if let mC = inet_ntop(AF_INET, &mask.sin_addr, &mbuf, socklen_t(INET_ADDRSTRLEN)) {
          maskStr = String(cString: mC)
        }
        // broadcast = ip | ~mask
        let ipNum = addr.sin_addr.s_addr
        let maskNum = mask.sin_addr.s_addr
        var b = in_addr(s_addr: ipNum | ~maskNum)
        var bbuf = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        if let bC = inet_ntop(AF_INET, &b, &bbuf, socklen_t(INET_ADDRSTRLEN)) {
          bcast = String(cString: bC)
        }
      }
      out.append(IfaceIp(name: name, ip: ip, netmask: maskStr, broadcast: bcast))
    }

    func rank(_ name: String) -> Int {
      if name == "en0" { return 0 }
      if name.hasPrefix("bridge") { return 1 }   // bridge100 = personal hotspot host side
      if name.hasPrefix("en") { return 2 }
      if name.hasPrefix("pdp_ip") || name.hasPrefix("utun") || name.hasPrefix("ipsec") { return 9 }
      return 5
    }
    return out.sorted { rank($0.name) < rank($1.name) }
  }

  static func localIPv4Address() -> String {
    return allIPv4Addresses().first?.ip ?? "127.0.0.1"
  }

  /// Subnet-directed broadcast addresses of every interface + the limited broadcast.
  static func broadcastAddresses() -> [String] {
    var seen = Set<String>()
    var out: [String] = ["255.255.255.255"]
    seen.insert("255.255.255.255")
    for cand in allIPv4Addresses() {
      if let b = cand.broadcast, !seen.contains(b) {
        seen.insert(b)
        out.append(b)
      }
    }
    return out
  }

  /// Local address on the same subnet as `peer` (dotted IPv4), if any.
  static func addressMatchingPeer(_ peer: String) -> IfaceIp? {
    var peerAddr = in_addr()
    guard inet_pton(AF_INET, peer, &peerAddr) == 1 else { return nil }
    for cand in allIPv4Addresses() {
      var ipAddr = in_addr()
      var maskAddr = in_addr()
      guard inet_pton(AF_INET, cand.ip, &ipAddr) == 1,
            inet_pton(AF_INET, cand.netmask, &maskAddr) == 1 else { continue }
      if (ipAddr.s_addr & maskAddr.s_addr) == (peerAddr.s_addr & maskAddr.s_addr) {
        return cand
      }
    }
    return nil
  }
}
