package com.solum.lantransfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.getcapacitor.JSObject
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Layered discovery:
 *  1) mDNS/NSD (primary) — service type _newtontouch._tcp. with TXT records.
 *  2) UDP broadcast probe/response (fallback) — works on phone hotspots and APs
 *     that filter multicast/mDNS. The advertising side (LCD) listens on a fixed
 *     UDP port; the scanning side (mobile) broadcasts a probe to 255.255.255.255
 *     AND every subnet-directed broadcast address; the LCD replies unicast with
 *     its identity + the IP on the prober's subnet.
 *  3) Manual IP connect remains the last-resort path (app-level).
 *
 * Both layers run simultaneously; the app dedupes results by instance name.
 */
class LanTransferDiscovery(
  private val context: Context,
  private val emit: (event: String, data: JSObject) -> Unit
) {
  companion object {
    const val SERVICE_TYPE = "_newtontouch._tcp."
    const val UDP_DISCOVERY_PORT = 53517
    const val PROBE_PREFIX = "NTLT1?"           // probe datagram
    const val RESP_PREFIX = "NTLT1!"            // response datagram: prefix + JSON
    const val PROBE_INTERVAL_MS = 2000L
    const val UDP_LOST_AFTER_MS = 6500L
  }

  private val nsd: NsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
  private var multicastLock: WifiManager.MulticastLock? = null

  private var regListener: NsdManager.RegistrationListener? = null
  private var discListener: NsdManager.DiscoveryListener? = null

  // ---------- advertised identity (shared by NSD + UDP responder) ----------
  @Volatile private var advertisedName: String? = null
  @Volatile private var advertisedPort: Int = 0
  @Volatile private var advertisedTxt: Map<String, String> = emptyMap()

  // ---------- ADVERTISE (LCD) ----------
  fun advertise(instanceName: String, port: Int, txt: Map<String, String>) {
    stopAdvertise()
    acquireMulticastLock()   // so the LCD can answer multicast mDNS queries reliably

    advertisedName = instanceName
    advertisedPort = port
    advertisedTxt = txt

    val info = NsdServiceInfo().apply {
      serviceName = instanceName
      serviceType = SERVICE_TYPE
      setPort(port)
      txt.forEach { (k, v) -> setAttribute(k, v) }
    }
    val listener = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(s: NsdServiceInfo) {
        emit("status", JSObject().apply { put("role", "server"); put("status", "advertising"); put("serviceName", s.serviceName) })
      }
      override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) {
        // Non-fatal: UDP responder + manual IP still work.
        emit("error", JSObject().apply { put("role", "server"); put("code", "ADVERTISE_FAILED"); put("message", "NSD register failed: $code (UDP fallback still active)") })
      }
      override fun onServiceUnregistered(s: NsdServiceInfo) {}
      override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
    }
    regListener = listener
    try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    catch (e: Exception) {
      emit("error", JSObject().apply { put("role", "server"); put("code", "ADVERTISE_FAILED"); put("message", "NSD register threw: ${e.message} (UDP fallback still active)") })
    }

    startUdpResponder()
  }

  fun stopAdvertise() {
    regListener?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
    regListener = null
    advertisedName = null
    stopUdpResponder()
    if (discListener == null) releaseMulticastLock()   // keep lock if a scan is also running
  }

  // ---------- DISCOVER (mobile) ----------
  fun startDiscovery() {
    stopDiscovery()
    acquireMulticastLock()
    val listener = object : NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(t: String) {}
      override fun onDiscoveryStopped(t: String) {}
      override fun onStartDiscoveryFailed(t: String, code: Int) {
        // Non-fatal: UDP probing still runs.
        emit("error", JSObject().apply { put("role", "discovery"); put("code", "DISCOVERY_FAILED"); put("message", "NSD start failed: $code (UDP fallback still active)") })
      }
      override fun onStopDiscoveryFailed(t: String, code: Int) {}
      override fun onServiceFound(s: NsdServiceInfo) { enqueueResolve(s) }
      override fun onServiceLost(s: NsdServiceInfo) {
        synchronized(nsdNames) { nsdNames.remove(s.serviceName) }
        emit("deviceLost", JSObject().apply { put("name", s.serviceName) })
      }
    }
    discListener = listener
    try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    catch (e: Exception) {
      emit("error", JSObject().apply { put("role", "discovery"); put("code", "DISCOVERY_FAILED"); put("message", "NSD discover threw: ${e.message} (UDP fallback still active)") })
    }

    startUdpProber()
  }

  fun stopDiscovery() {
    discListener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
    discListener = null
    stopUdpProber()
    synchronized(this) { resolveQueue.clear(); resolving = false }
    synchronized(nsdNames) { nsdNames.clear() }
    if (regListener == null) releaseMulticastLock()   // keep lock if still advertising
  }

  // Names currently known via NSD — so the UDP-lost logic never removes a device
  // that mDNS still sees.
  private val nsdNames = HashSet<String>()

  // NSD allows only ONE active resolve at a time — concurrent resolveService() calls
  // fail silently. Serialize them through a queue (the classic NSD-discovery fix).
  private val resolveQueue = ArrayDeque<NsdServiceInfo>()
  private var resolving = false

  @Synchronized private fun enqueueResolve(service: NsdServiceInfo) {
    resolveQueue.addLast(service)
    pumpResolve()
  }

  @Synchronized private fun pumpResolve() {
    if (resolving) return
    val service = resolveQueue.removeFirstOrNull() ?: return
    resolving = true
    val listener = object : NsdManager.ResolveListener {
      override fun onResolveFailed(s: NsdServiceInfo, code: Int) {
        if (code == NsdManager.FAILURE_ALREADY_ACTIVE) {
          try { Thread.sleep(200) } catch (_: InterruptedException) {}
          synchronized(this@LanTransferDiscovery) { resolveQueue.addLast(s); resolving = false; pumpResolve() }
        } else {
          synchronized(this@LanTransferDiscovery) { resolving = false; pumpResolve() }
        }
      }
      override fun onServiceResolved(s: NsdServiceInfo) {
        val hostAddr = s.host?.hostAddress ?: ""
        // Prefer IPv4; an IPv6 link-local here is unusable for the TCP client.
        val data = JSObject().apply {
          put("name", s.serviceName)
          put("host", hostAddr)
          put("port", s.port)
          put("via", "mdns")
          s.attributes?.forEach { (k, v) -> put(k, v?.let { String(it, Charsets.UTF_8) } ?: "") }
        }
        synchronized(nsdNames) { nsdNames.add(s.serviceName) }
        if (hostAddr.isNotEmpty() && !hostAddr.contains(":")) emit("deviceFound", data)
        synchronized(this@LanTransferDiscovery) { resolving = false; pumpResolve() }
      }
    }
    try { nsd.resolveService(service, listener) }
    catch (_: Exception) { resolving = false; pumpResolve() }
  }

  // ---------- UDP RESPONDER (advertising side) ----------
  private var udpResponderThread: Thread? = null
  @Volatile private var udpResponderRunning = false
  @Volatile private var udpResponderSocket: DatagramSocket? = null

  private fun startUdpResponder() {
    stopUdpResponder()
    udpResponderRunning = true
    udpResponderThread = Thread({
      var sock: DatagramSocket? = null
      try {
        sock = DatagramSocket(null).apply {
          reuseAddress = true
          broadcast = true
          bind(InetSocketAddress(UDP_DISCOVERY_PORT))
          soTimeout = 1000
        }
        udpResponderSocket = sock
        val buf = ByteArray(2048)
        while (udpResponderRunning) {
          val pkt = DatagramPacket(buf, buf.size)
          try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue }
          try {
            val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
            if (!msg.startsWith(PROBE_PREFIX)) continue
            val name = advertisedName ?: continue
            // Reply with the local IP on the prober's subnet so the address we
            // advertise is the one the peer can actually reach.
            val localIp = NetUtils.addressMatchingPeer(pkt.address)?.ip ?: NetUtils.getLocalIpv4Address()
            val json = JSONObject()
            advertisedTxt.forEach { (k, v) -> json.put(k, v) }
            json.put("v", 1)
            json.put("name", name)
            json.put("ip", localIp)
            json.put("port", advertisedPort)
            val payload = (RESP_PREFIX + json.toString()).toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(payload, payload.size, pkt.address, pkt.port))
          } catch (_: Exception) { /* malformed probe — ignore */ }
        }
      } catch (e: Exception) {
        if (udpResponderRunning) {
          emit("error", JSObject().apply { put("role", "server"); put("code", "UDP_RESPONDER_FAILED"); put("message", "UDP discovery responder failed: ${e.message}") })
        }
      } finally {
        try { sock?.close() } catch (_: Exception) {}
        udpResponderSocket = null
      }
    }, "ntlt-udp-responder").apply { isDaemon = true; start() }
  }

  private fun stopUdpResponder() {
    udpResponderRunning = false
    try { udpResponderSocket?.close() } catch (_: Exception) {}
    udpResponderThread = null
  }

  // ---------- UDP PROBER (scanning side) ----------
  private var udpProberThread: Thread? = null
  @Volatile private var udpProberRunning = false
  @Volatile private var udpProberSocket: DatagramSocket? = null

  private fun startUdpProber() {
    stopUdpProber()
    udpProberRunning = true
    udpProberThread = Thread({
      var sock: DatagramSocket? = null
      val udpSeen = HashMap<String, Long>()   // name -> last reply time (UDP-only lost detection)
      try {
        sock = DatagramSocket().apply {
          broadcast = true
          soTimeout = 400
        }
        udpProberSocket = sock
        val probe = PROBE_PREFIX.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(4096)
        var lastProbeAt = 0L
        while (udpProberRunning) {
          val now = System.currentTimeMillis()
          if (now - lastProbeAt >= PROBE_INTERVAL_MS) {
            lastProbeAt = now
            for (b in NetUtils.broadcastAddresses()) {
              try { sock.send(DatagramPacket(probe, probe.size, InetAddress.getByName(b), UDP_DISCOVERY_PORT)) }
              catch (_: Exception) { /* interface without broadcast — skip */ }
            }
            // Expire UDP-only devices that stopped replying (unless mDNS still sees them).
            val expired = udpSeen.filterValues { now - it > UDP_LOST_AFTER_MS }.keys.toList()
            for (name in expired) {
              udpSeen.remove(name)
              val stillViaNsd = synchronized(nsdNames) { nsdNames.contains(name) }
              if (!stillViaNsd) emit("deviceLost", JSObject().apply { put("name", name) })
            }
          }
          val pkt = DatagramPacket(buf, buf.size)
          try { sock.receive(pkt) } catch (_: SocketTimeoutException) { continue }
          try {
            val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
            if (!msg.startsWith(RESP_PREFIX)) continue
            val json = JSONObject(msg.substring(RESP_PREFIX.length))
            val name = json.optString("name", "")
            if (name.isEmpty()) continue
            val host = json.optString("ip", pkt.address.hostAddress ?: "")
            val port = json.optInt("port", 0)
            if (host.isEmpty() || port <= 0) continue
            val first = !udpSeen.containsKey(name)
            udpSeen[name] = System.currentTimeMillis()
            // Re-emit only on first sight or after expiry — app replaces by name anyway.
            if (first) {
              val data = JSObject().apply {
                json.keys().forEach { k -> if (k != "v") put(k, json.opt(k)?.toString() ?: "") }
                put("name", name)
                put("host", host)
                put("port", port)
                put("via", "udp")
              }
              emit("deviceFound", data)
            }
          } catch (_: Exception) { /* malformed response — ignore */ }
        }
      } catch (e: Exception) {
        if (udpProberRunning) {
          emit("error", JSObject().apply { put("role", "discovery"); put("code", "UDP_PROBE_FAILED"); put("message", "UDP discovery probe failed: ${e.message}") })
        }
      } finally {
        try { sock?.close() } catch (_: Exception) {}
        udpProberSocket = null
      }
    }, "ntlt-udp-prober").apply { isDaemon = true; start() }
  }

  private fun stopUdpProber() {
    udpProberRunning = false
    try { udpProberSocket?.close() } catch (_: Exception) {}
    udpProberThread = null
  }

  // ---------- multicast lock (needed to RECEIVE mDNS on Android) ----------
  private fun acquireMulticastLock() {
    try {
      if (multicastLock?.isHeld == true) return
      val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      multicastLock = wifi.createMulticastLock("newtontouch-nsd").apply { setReferenceCounted(false); acquire() }
    } catch (_: Exception) {}
  }

  private fun releaseMulticastLock() {
    try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    multicastLock = null
  }
}
