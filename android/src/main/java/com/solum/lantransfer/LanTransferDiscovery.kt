package com.solum.lantransfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.getcapacitor.JSObject

/**
 * mDNS/NSD advertising (LCD) + discovery (mobile).
 * Service type: _newtontouch._tcp. — each LCD registers a UNIQUE instance name
 * plus TXT records (deviceName, storeId, appVersion) so multiple advertising LCDs
 * are distinguishable in the scan list. Manual IP connect remains the fallback.
 */
class LanTransferDiscovery(
  private val context: Context,
  private val emit: (event: String, data: JSObject) -> Unit
) {
  companion object { const val SERVICE_TYPE = "_newtontouch._tcp." }

  private val nsd: NsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
  private var multicastLock: WifiManager.MulticastLock? = null

  private var regListener: NsdManager.RegistrationListener? = null
  private var discListener: NsdManager.DiscoveryListener? = null

  // ---------- ADVERTISE (LCD) ----------
  fun advertise(instanceName: String, port: Int, txt: Map<String, String>) {
    stopAdvertise()
    acquireMulticastLock()   // so the LCD can answer multicast mDNS queries reliably
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
        emit("error", JSObject().apply { put("role", "server"); put("code", "ADVERTISE_FAILED"); put("message", "NSD register failed: $code") })
      }
      override fun onServiceUnregistered(s: NsdServiceInfo) {}
      override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) {}
    }
    regListener = listener
    nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
  }

  fun stopAdvertise() {
    regListener?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
    regListener = null
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
        emit("error", JSObject().apply { put("code", "DISCOVERY_FAILED"); put("message", "start failed: $code") })
      }
      override fun onStopDiscoveryFailed(t: String, code: Int) {}
      override fun onServiceFound(s: NsdServiceInfo) { enqueueResolve(s) }
      override fun onServiceLost(s: NsdServiceInfo) {
        emit("deviceLost", JSObject().apply { put("name", s.serviceName) })
      }
    }
    discListener = listener
    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
  }

  fun stopDiscovery() {
    discListener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
    discListener = null
    synchronized(this) { resolveQueue.clear(); resolving = false }
    if (regListener == null) releaseMulticastLock()   // keep lock if still advertising
  }

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
        val data = JSObject().apply {
          put("name", s.serviceName)
          put("host", s.host?.hostAddress ?: "")
          put("port", s.port)
          s.attributes?.forEach { (k, v) -> put(k, v?.let { String(it, Charsets.UTF_8) } ?: "") }
        }
        emit("deviceFound", data)
        synchronized(this@LanTransferDiscovery) { resolving = false; pumpResolve() }
      }
    }
    try { nsd.resolveService(service, listener) }
    catch (_: Exception) { resolving = false; pumpResolve() }
  }

  private fun acquireMulticastLock() {
    try {
      val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      multicastLock = wifi.createMulticastLock("newtontouch-nsd").apply { setReferenceCounted(true); acquire() }
    } catch (_: Exception) {}
  }

  private fun releaseMulticastLock() {
    try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    multicastLock = null
  }
}
