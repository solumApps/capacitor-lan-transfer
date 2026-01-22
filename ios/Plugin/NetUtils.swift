import Foundation

final class NetUtils {
  static func localIPv4Address() -> String {
    var address: String = "127.0.0.1"

    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else {
      return address
    }
    defer { freeifaddrs(ifaddr) }

    let preferredOrder = ["en0", "en1", "bridge0", "pdp_ip0"]

    func score(_ name: String) -> Int {
      if let idx = preferredOrder.firstIndex(of: name) { return idx }
      return preferredOrder.count + 10
    }

    var best: (score: Int, ip: String)?

    var ptr = firstAddr
    while true {
      let interface = ptr.pointee
      let family = interface.ifa_addr.pointee.sa_family

      if family == UInt8(AF_INET) {
        let name = String(cString: interface.ifa_name)
        var addr = interface.ifa_addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }

        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        let ipCString = inet_ntop(AF_INET, &addr.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN))
        if let ipCString = ipCString {
          let ip = String(cString: ipCString)
          if !ip.hasPrefix("169.254.") && ip != "127.0.0.1" {
            let cand = (score: score(name), ip: ip)
            if best == nil || cand.score < best!.score { best = cand }
          }
        }
      }

      if ptr.pointee.ifa_next == nil { break }
      ptr = ptr.pointee.ifa_next.pointee
    }

    if let best = best { address = best.ip }
    return address
  }
}