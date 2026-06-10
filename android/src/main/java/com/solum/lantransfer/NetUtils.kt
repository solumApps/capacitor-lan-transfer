package com.solum.lantransfer

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Interface enumeration that works in ALL modes — Wi-Fi client, Ethernet, and
 * hotspot/AP mode (ap0/swlan0/softap). Never uses WifiManager.getConnectionInfo(),
 * which returns 0.0.0.0 when the device is hosting the hotspot.
 */
object NetUtils {

  data class IfaceIp(
    val name: String,
    val ip: String,
    val prefixLength: Int,
    val broadcast: String?
  )

  /**
   * All usable (up, non-loopback, non-link-local) IPv4 addresses, best first.
   * Ranking: Wi-Fi client > hotspot/AP > ethernet/bridge/usb > unknown > cellular/tunnels.
   */
  fun getLocalIpv4Addresses(): List<IfaceIp> {
    val out = ArrayList<IfaceIp>()
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
      for (intf in interfaces) {
        try {
          if (!intf.isUp || intf.isLoopback) continue
          for (ia in intf.interfaceAddresses) {
            val addr = ia.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
              val ip = addr.hostAddress ?: continue
              if (ip.startsWith("169.254.")) continue
              out.add(IfaceIp(intf.name, ip, ia.networkPrefixLength.toInt(), ia.broadcast?.hostAddress))
            }
          }
        } catch (_: Exception) {}
      }
    } catch (_: Exception) {}
    return out.sortedBy { rank(it.name) }
  }

  private fun rank(name: String): Int {
    val n = name.lowercase()
    return when {
      n.startsWith("wlan") -> 0
      n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("wifi") -> 1
      n.startsWith("eth") || n.startsWith("en") || n.startsWith("bridge") || n.startsWith("usb") -> 2
      n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
        n.startsWith("ppp") || n.startsWith("clat") || n.startsWith("tun") || n.startsWith("v4-") -> 9
      else -> 5
    }
  }

  /** Best single IPv4 (kept for backward compatibility with existing callers). */
  fun getLocalIpv4Address(): String =
    getLocalIpv4Addresses().firstOrNull()?.ip ?: "127.0.0.1"

  /** The local address that shares a subnet with [peer], if any — used so UDP
   *  discovery replies advertise the IP the peer can actually reach. */
  fun addressMatchingPeer(peer: InetAddress): IfaceIp? {
    val peerBytes = peer.address ?: return null
    if (peerBytes.size != 4) return null
    for (cand in getLocalIpv4Addresses()) {
      try {
        val local = InetAddress.getByName(cand.ip).address
        if (sameSubnet(local, peerBytes, cand.prefixLength)) return cand
      } catch (_: Exception) {}
    }
    return null
  }

  private fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
    if (a.size != 4 || b.size != 4) return false
    var bits = prefix.coerceIn(0, 32)
    var i = 0
    while (bits >= 8) {
      if (a[i] != b[i]) return false
      i++; bits -= 8
    }
    if (bits == 0) return true
    val mask = (0xFF shl (8 - bits)) and 0xFF
    return (a[i].toInt() and mask) == (b[i].toInt() and mask)
  }

  /** Subnet-directed broadcast addresses of every interface + the limited broadcast.
   *  Probing all of these covers normal APs AND phone hotspots. */
  fun broadcastAddresses(): List<String> {
    val set = LinkedHashSet<String>()
    set.add("255.255.255.255")
    for (cand in getLocalIpv4Addresses()) cand.broadcast?.let { set.add(it) }
    return set.toList()
  }
}
