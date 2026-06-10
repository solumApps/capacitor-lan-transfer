package com.solum.lantransfer

import android.Manifest
import android.os.Build
import com.getcapacitor.*
import com.getcapacitor.PermissionState
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
  name = "LanTransfer",
  permissions = [
    Permission(alias = "nearby", strings = [Manifest.permission.NEARBY_WIFI_DEVICES])
  ]
)
class LanTransferPlugin : Plugin() {

  private var server: LanTransferServer? = null
  private var client: LanTransferClient? = null
  private var discovery: LanTransferDiscovery? = null


  fun emit(event:String, data:JSObject){
    notifyListeners(event,data)
  }

  private fun ensureDiscovery(): LanTransferDiscovery {
    val d = discovery ?: LanTransferDiscovery(context) { ev, data -> emit(ev, data) }.also { discovery = it }
    return d
  }

  @PluginMethod
  fun advertise(call: PluginCall) {
    val name = call.getString("name")
    val port = call.getInt("port")
    if (name.isNullOrBlank() || port == null) { call.reject("name and port are required"); return }
    val txt = HashMap<String, String>()
    call.getObject("txt")?.let { o -> o.keys().forEach { k -> txt[k] = o.getString(k) ?: "" } }
    ensureDiscovery().advertise(name, port, txt)
    call.resolve()
  }

  @PluginMethod
  fun stopAdvertise(call: PluginCall) { discovery?.stopAdvertise(); call.resolve() }

  @PluginMethod
  fun startDiscovery(call: PluginCall) {
    // Android 13+ requires NEARBY_WIFI_DEVICES for NSD discovery/resolve, else it
    // silently returns nothing. Request it once, then start.
    if (Build.VERSION.SDK_INT >= 33 && getPermissionState("nearby") != PermissionState.GRANTED) {
      requestPermissionForAlias("nearby", call, "onNearbyResult")
      return
    }
    ensureDiscovery().startDiscovery()
    call.resolve()
  }

  @PermissionCallback
  private fun onNearbyResult(call: PluginCall) {
    // Proceed regardless — if denied, discovery just yields nothing and manual IP still works.
    ensureDiscovery().startDiscovery()
    call.resolve()
  }

  @PluginMethod
  fun stopDiscovery(call: PluginCall) { discovery?.stopDiscovery(); call.resolve() }

  /** All non-loopback IPv4 interfaces (wlan/ap/eth/...), best first — lets the LCD
   *  pairing screen show every reachable address (incl. hotspot interfaces). */
  @PluginMethod
  fun getNetworkInfo(call: PluginCall) {
    val arr = JSArray()
    NetUtils.getLocalIpv4Addresses().forEach { i ->
      arr.put(JSObject().apply {
        put("name", i.name)
        put("ip", i.ip)
        put("prefixLength", i.prefixLength)
      })
    }
    call.resolve(JSObject().apply {
      put("ip", NetUtils.getLocalIpv4Address())
      put("interfaces", arr)
    })
  }

  /** TCP reachability probe (connect+close). Used by the sender to distinguish
   *  "peer offline / wrong IP" from "AP blocks device-to-device traffic". */
  @PluginMethod
  fun checkReachable(call: PluginCall) {
    val host = call.getString("host") ?: return call.reject("host is required")
    val port = call.getInt("port") ?: return call.reject("port is required")
    val timeout = call.getInt("timeoutMs") ?: 2500
    Thread({
      val started = System.currentTimeMillis()
      var ok = false
      try {
        java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(host, port), timeout) }
        ok = true
      } catch (_: Exception) {}
      call.resolve(JSObject().apply {
        put("reachable", ok)
        put("latencyMs", System.currentTimeMillis() - started)
      })
    }, "ntlt-reach-probe").start()
  }


  private fun parseCrypto(call: PluginCall): LanTransferCrypto.CryptoConfig? {
    val enc = call.getObject("encryption") ?: return null
    val enabled = enc.getBoolean("enabled") ?: false
    if (!enabled) return LanTransferCrypto.CryptoConfig(enabled = false, passphrase = "")
    val pass = enc.getString("passphrase") ?: return null
    val iterations = enc.getInteger("iterations") ?: 100_000
    val chunkSize = enc.getInteger("chunkSize") ?: 65_536
    val requireEncryption = enc.getBoolean("requireEncryption") ?: false
    return LanTransferCrypto.CryptoConfig(
      enabled = true,
      passphrase = pass,
      iterations = iterations,
      chunkSize = chunkSize,
      requireEncryption = requireEncryption
    )
  }

  @PluginMethod
  fun initialize(call: PluginCall) {
    val role = call.getString("role") ?: "client"
    val sessionKey = call.getString("sessionKey")
    val receiveMode = call.getString("receiveMode") ?: "file"
    val receiveFileName = call.getString("receiveFileName")
    val cryptoCfg = parseCrypto(call)

    if (role == "server") {
      val port = call.getInt("port") ?: 0
      ensureServer()
      server?.setSessionKey(sessionKey)
      server?.setReceiveMode(receiveMode, receiveFileName)
      server?.setCrypto(cryptoCfg)
      server?.start(port)

      call.resolve(JSObject().apply {
        put("running", true)
        put("ip", NetUtils.getLocalIpv4Address())
        put("port", server?.getPort() ?: 0)
      })
      return
    }

    val host = call.getString("host")
    val port = call.getInt("serverPort")
    if (host.isNullOrBlank() || port == null) {
      call.reject("host and serverPort are required for client initialize()")
      return
    }

    ensureClient()
    client?.setCrypto(cryptoCfg)
    try {
      client?.connect(host, port, sessionKey)  // blocking; throws on failure
    } catch (e: Exception) {
      call.reject(e.message ?: "CONNECT_FAILED", "CONNECT_FAILED")
      return
    }

    call.resolve(JSObject().apply {
      put("connected", true)
      put("role", "client")
      put("remoteHost", host)
      put("remotePort", port)
    })
  }

  @PluginMethod
  fun startServer(call: PluginCall) {
    val port = call.getInt("port") ?: 0
    val sessionKey = call.getString("sessionKey")
    val cryptoCfg = parseCrypto(call)

    ensureServer()
    server?.setSessionKey(sessionKey)
    server?.setCrypto(cryptoCfg)
    server?.start(port)

    call.resolve(JSObject().apply {
      put("running", true)
      put("ip", NetUtils.getLocalIpv4Address())
      put("port", server?.getPort() ?: 0)
    })
  }

  @PluginMethod
  fun stopServer(call: PluginCall) {
    server?.stop()
    call.resolve()
  }

  @PluginMethod
  fun getServerInfo(call: PluginCall) {
    call.resolve(JSObject().apply {
      put("running", server?.isRunning() == true)
      put("ip", NetUtils.getLocalIpv4Address())
      put("port", server?.getPort() ?: 0)
    })
  }

  @PluginMethod
  fun connect(call: PluginCall) {
    val host = call.getString("host")
    val port = call.getInt("port")
    val sessionKey = call.getString("sessionKey")
    val cryptoCfg = parseCrypto(call)

    if (host.isNullOrBlank() || port == null) {
      call.reject("host and port are required")
      return
    }

    ensureClient()
    client?.setCrypto(cryptoCfg)
    try {
      client?.connect(host, port, sessionKey)  // blocking; throws on failure
    } catch (e: Exception) {
      call.reject(e.message ?: "CONNECT_FAILED", "CONNECT_FAILED")
      return
    }

    call.resolve(JSObject().apply {
      put("connected", true)
      put("role", "client")
      put("remoteHost", host)
      put("remotePort", port)
    })
  }

  @PluginMethod
  fun disconnect(call: PluginCall) {
    client?.disconnect()
    call.resolve()
  }

  @PluginMethod
  fun getConnectionInfo(call: PluginCall) {
    call.resolve(JSObject().apply {
      put("connected", client?.isConnected() == true)
      put("role", "client")
    })
  }

  @PluginMethod
  fun setReceiveMode(call: PluginCall) {
    ensureServer()
    server?.setReceiveMode(call.getString("receiveMode") ?: "file", call.getString("receiveFileName"))
    call.resolve()
  }

  @PluginMethod
  fun sendString(call: PluginCall) {
    val text = call.getString("text") ?: return call.reject("text is required")
    ensureClient()
    client?.sendString(text)
    call.resolve()
  }

  @PluginMethod
  fun sendBase64(call: PluginCall) {
    val base64 = call.getString("base64") ?: return call.reject("base64 is required")
    ensureClient()
    client?.sendBase64(base64)
    call.resolve()
  }

  @PluginMethod
  fun sendFile(call: PluginCall) {
    val path = call.getString("path") ?: return call.reject("path is required")
    ensureClient()
    client?.sendFile(path)
    call.resolve()
  }

  private fun ensureServer() {
    if (server == null) {
      server = LanTransferServer(context) { event, data ->
        notifyListeners(event, data)
      }
    }
  }

  private fun ensureClient() {
    if (client == null) client = LanTransferClient(context, this)
  }
}