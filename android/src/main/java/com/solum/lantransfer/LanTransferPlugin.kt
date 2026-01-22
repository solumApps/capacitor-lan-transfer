package com.solum.lantransfer

import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin

@CapacitorPlugin(name = "LanTransfer")
class LanTransferPlugin : Plugin() {

  private var server: LanTransferServer? = null
  private var client: LanTransferClient? = null

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
    client?.connect(host, port, sessionKey)

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
    client?.connect(host, port, sessionKey)

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
    if (server == null) server = LanTransferServer(context, this)
  }

  private fun ensureClient() {
    if (client == null) client = LanTransferClient(context, this)
  }
}