package com.solum.lantransfer

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
  fun getLocalIpv4Address(): String {
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces()
      for (intf in interfaces) {
        if (!intf.isUp || intf.isLoopback) continue
        val addrs = intf.inetAddresses
        for (addr in addrs) {
          if (addr is Inet4Address && !addr.isLoopbackAddress) {
            val ip = addr.hostAddress ?: continue
            if (!ip.startsWith("169.254.")) return ip
          }
        }
      }
    } catch (_: Exception) {}
    return "127.0.0.1"
  }
}