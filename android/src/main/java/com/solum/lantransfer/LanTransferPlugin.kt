package com.solum.lantransfer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
  /** One client (socket + worker thread) per host:port, so sends to different
   *  displays run in parallel. Calls that name no host use the last connect()'s.
   *  ponytail: clients live for the app's lifetime (2 idle threads per display
   *  address ever used); evict on disconnect if stores ever reach dozens. */
  private val clients = java.util.concurrent.ConcurrentHashMap<String, LanTransferClient>()
  @Volatile private var current: LanTransferClient? = null
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

  /** Opens the full system Wi-Fi settings screen (Settings.ACTION_WIFI_SETTINGS).
   *  Uses the foreground Activity context (activity ?: context) so Samsung One UI,
   *  tablets, and Android 14+ (API 34–36) Background Activity Launch (BAL) rules
   *  cleanly allow the window transition.
   *  Includes fallbacks to ACTION_WIRELESS_SETTINGS and ACTION_SETTINGS. */
  @PluginMethod
  fun openWifiSettings(call: PluginCall) {
    android.util.Log.d("LanTransfer", "openWifiSettings called: activity=$activity, context=$context")
    val act = activity ?: context
    try {
      val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      act.startActivity(wifiIntent)
      android.util.Log.d("LanTransfer", "openWifiSettings: launched ACTION_WIFI_SETTINGS")
      call.resolve()
    } catch (e: Exception) {
      android.util.Log.w("LanTransfer", "ACTION_WIFI_SETTINGS failed: ${e.message}")
      try {
        val wirelessIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        act.startActivity(wirelessIntent)
        android.util.Log.d("LanTransfer", "openWifiSettings: launched ACTION_WIRELESS_SETTINGS")
        call.resolve()
      } catch (e2: Exception) {
        android.util.Log.w("LanTransfer", "ACTION_WIRELESS_SETTINGS failed: ${e2.message}")
        try {
          val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
          }
          act.startActivity(settingsIntent)
          android.util.Log.d("LanTransfer", "openWifiSettings: launched ACTION_SETTINGS")
          call.resolve()
        } catch (e3: Exception) {
          android.util.Log.e("LanTransfer", "all settings intents failed: ${e3.message}")
          call.reject(e3.message ?: "Unable to open Wi-Fi settings")
        }
      }
    }
  }

  /** Returns { supported: true/false } indicating whether this device has a
   *  Wi-Fi hotspot / tethering feature that the user can enable.
   *  Checks by querying whether PackageManager can resolve ACTION_TETHER_SETTINGS (android.settings.TETHER_SETTINGS).
   *  No extra permissions required. */
  @PluginMethod
  fun isHotspotSupported(call: PluginCall) {
    val intent = Intent("android.settings.TETHER_SETTINGS")
    val activities = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    val supported = activities.isNotEmpty()
    android.util.Log.d("LanTransfer", "isHotspotSupported: $supported (${activities.size} activities found)")
    call.resolve(JSObject().apply { put("supported", supported) })
  }

  /** Opens the system Hotspot / Tethering settings screen so the user can enable
   *  the mobile Wi-Fi hotspot for Case 2 (phone-as-AP) transfers.
   *  Uses android.settings.TETHER_SETTINGS (direct hotspot page) with fallback to
   *  ACTION_WIRELESS_SETTINGS → ACTION_SETTINGS. */
  @PluginMethod
  fun openHotspotSettings(call: PluginCall) {
    android.util.Log.d("LanTransfer", "openHotspotSettings called")
    val act = activity ?: context
    val intents = listOf(
      "android.settings.TETHER_SETTINGS",
      Settings.ACTION_WIRELESS_SETTINGS,
      Settings.ACTION_SETTINGS
    )
    for (action in intents) {
      try {
        val intent = Intent(action).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        act.startActivity(intent)
        android.util.Log.d("LanTransfer", "openHotspotSettings: launched $action")
        call.resolve()
        return
      } catch (e: Exception) {
        android.util.Log.w("LanTransfer", "openHotspotSettings: $action failed: ${e.message}")
      }
    }
    call.reject("Unable to open hotspot settings")
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
      try { server?.start(port) } catch (e: Exception) {
        return call.reject("Server could not bind port $port: ${e.message ?: e}", "SERVER_BIND_FAILED")
      }

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

    val c = clientFor(host, port)
    c.setCrypto(cryptoCfg)
    try {
      c.connect(host, port, sessionKey)  // blocking; throws on failure
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
    try { server?.start(port) } catch (e: Exception) {
      return call.reject("Server could not bind port $port: ${e.message ?: e}", "SERVER_BIND_FAILED")
    }

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

    val c = clientFor(host, port)
    c.setCrypto(cryptoCfg)
    c.enqueue({ c.connect(host, port, sessionKey) }) { e ->
      if (e != null) call.reject(e.message ?: "CONNECT_FAILED", "CONNECT_FAILED")
      else call.resolve(JSObject().apply {
        put("connected", true)
        put("role", "client")
        put("remoteHost", host)
        put("remotePort", port)
      })
    }
  }

  @PluginMethod
  fun disconnect(call: PluginCall) {
    // One connection when a host is named, otherwise all of them. Queued behind
    // pending sends, so it can't cut one off mid-write.
    val targets = if (call.getString("host").isNullOrBlank()) clients.values.toList() else listOfNotNull(targetOf(call))
    if (targets.isEmpty()) return call.resolve()
    val left = java.util.concurrent.atomic.AtomicInteger(targets.size)
    for (c in targets) c.enqueue({ c.disconnect() }) { if (left.decrementAndGet() == 0) call.resolve() }
  }

  @PluginMethod
  fun getConnectionInfo(call: PluginCall) {
    call.resolve(JSObject().apply {
      put("connected", current?.isConnected() == true)
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
    val c = targetOf(call) ?: return call.reject("Not connected", "NOT_CONNECTED")
    try {
      c.sendString(text) { e -> if (e != null) call.reject(e.message ?: "SEND_FAILED", "SEND_FAILED") else call.resolve() }
    } catch (e: Exception) { call.reject(e.message ?: "SEND_FAILED", "SEND_FAILED") }
  }

  @PluginMethod
  fun sendBase64(call: PluginCall) {
    val base64 = call.getString("base64") ?: return call.reject("base64 is required")
    val c = targetOf(call) ?: return call.reject("Not connected", "NOT_CONNECTED")
    try {
      c.sendBase64(base64) { e -> if (e != null) call.reject(e.message ?: "SEND_FAILED", "SEND_FAILED") else call.resolve() }
    } catch (e: Exception) { call.reject(e.message ?: "SEND_FAILED", "SEND_FAILED") }
  }

  @PluginMethod
  fun sendFile(call: PluginCall) {
    val path = call.getString("path") ?: return call.reject("path is required")
    val c = targetOf(call) ?: return call.reject("Not connected", "NOT_CONNECTED")
    try {
      c.sendFile(path) { e -> if (e != null) call.reject(e.message ?: "SEND_FAILED", "SEND_FAILED") else call.resolve() }
    } catch (e: Exception) { call.reject(e.message ?: "SEND_FAILED", "SEND_FAILED") }
  }

  /** The Activity is going away (or being recreated): release port 8082 and the
   *  client socket. Otherwise the old instance kept listening and the new one
   *  could not bind, so payloads went to a server whose bridge was dead. */
  override fun handleOnDestroy() {
    try { server?.stop() } catch (_: Exception) {}
    for (c in clients.values) try { c.disconnect() } catch (_: Exception) {}
    try { discovery?.stopAdvertise(); discovery?.stopDiscovery() } catch (_: Exception) {}
    super.handleOnDestroy()
  }

  private fun ensureServer() {
    if (server == null) {
      server = LanTransferServer(context) { event, data ->
        notifyListeners(event, data)
      }
    }
  }

  private fun clientFor(host: String, port: Int): LanTransferClient =
    clients.getOrPut("$host:$port") { LanTransferClient(context, this) }.also { current = it }

  /** The connection a send/disconnect names (host + port), else the last connect()'s. */
  private fun targetOf(call: PluginCall): LanTransferClient? {
    val host = call.getString("host"); val port = call.getInt("port")
    return if (host.isNullOrBlank() || port == null) current else clients["$host:$port"]
  }
}