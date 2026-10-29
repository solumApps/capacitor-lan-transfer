#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

// Registers the plugin methods with the Capacitor ObjC bridge.
// Without this file the Swift methods are never exposed to JS on iOS.
CAP_PLUGIN(LanTransferPlugin, "LanTransfer",
  CAP_PLUGIN_METHOD(initialize, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(isHotspotSupported, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(openWifiSettings, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(openHotspotSettings, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(startServer, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(stopServer, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(getServerInfo, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(connect, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(disconnect, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(getConnectionInfo, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(setReceiveMode, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(sendString, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(sendBase64, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(sendFile, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(advertise, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(stopAdvertise, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(startDiscovery, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(stopDiscovery, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(getNetworkInfo, CAPPluginReturnPromise);
  CAP_PLUGIN_METHOD(checkReachable, CAPPluginReturnPromise);
)
