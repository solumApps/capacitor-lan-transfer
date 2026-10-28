export type Role = 'server' | 'client';
export type ReceiveMode = 'memory' | 'file';
export type PayloadEncoding = 'utf8' | 'binary';

export interface EncryptionOptions {
  enabled: boolean;
  passphrase: string;
  iterations?: number; // default 100000
  chunkSize?: number; // default 65536
  requireEncryption?: boolean; // server-side default false
}

export interface InitializeOptions {
  role: Role;

  // Server
  port?: number; // 0 = auto

  // Client
  host?: string;
  serverPort?: number;

  sessionKey?: string;

  receiveMode?: ReceiveMode;
  receiveFileName?: string;

  encryption?: EncryptionOptions;
}

export interface ServerInfo {
  running: boolean;
  ip: string;
  port: number;
}

export interface ConnectionInfo {
  connected: boolean;
  role: Role;
  remoteHost?: string;
  remotePort?: number;
}

export interface SendStringOptions {
  text: string;
}

export interface SendBase64Options {
  base64: string; // raw base64 bytes (not data URL)
}

export interface SendFileOptions {
  /**
   * Absolute filesystem path OR a URI:
   * - file://... (Capacitor Filesystem typically returns this)
   * - content://... (Android pickers often return this; size must be detectable)
   */
  path: string;
}

export interface SetReceiveModeOptions {
  receiveMode: ReceiveMode;
  receiveFileName?: string;
}

export interface ProgressEvent {
  role: Role;
  direction: 'send' | 'receive';
  bytesTransferred: number; // plaintext bytes processed
  totalBytes: number;       // total plaintext bytes
  percent: number;          // 0..100
}

export interface ReceiveCompleteEvent {
  role: Role;
  encoding: PayloadEncoding;
  totalBytes: number;

  text?: string;
  base64?: string;
  filePath?: string;

  /** Hex SHA-256 of PLAINTEXT bytes received */
  sha256?: string;
}

export interface StatusEvent {
  role: Role;
  status:
    | 'server_started'
    | 'server_stopped'
    | 'client_connected'
    | 'client_disconnected'
    | 'connection_accepted'
    | 'connection_closed'
    | 'receive_started'
    | 'send_started'
    | 'send_complete';

  ip?: string;
  port?: number;
  remoteHost?: string;
  remotePort?: number;

  totalBytes?: number;
  encoding?: PayloadEncoding;
  encrypted?: boolean;

  /** Hex SHA-256 of PLAINTEXT bytes (send_complete) */
  sha256?: string;
}

export interface ErrorEvent {
  role: Role;
  message: string;
  code?: string;
}

export interface AdvertiseOptions {
  /** Unique instance name shown in the scan list, e.g. "Newton-LCD-01". */
  name: string;
  /** Port the file server listens on (same as startServer). */
  port: number;
  /** TXT records to distinguish devices, e.g. { deviceName, storeId, appVersion }. */
  txt?: Record<string, string>;
}

/** Emitted on the scanning (mobile) side as services are resolved/lost.
 *  Devices can be found via mDNS (primary) or the UDP broadcast fallback
 *  (`via: 'udp'`) — the fallback works on phone hotspots and APs that filter
 *  multicast. Dedupe by `name`. */
export interface DeviceFoundEvent {
  name: string;        // NSD instance name
  host: string;        // resolved IPv4
  port: number;
  via?: 'mdns' | 'udp' | string;
  // plus any TXT keys (deviceName, storeId, appVersion, …)
  [key: string]: string | number | undefined;
}
export interface DeviceLostEvent {
  name: string;
}

export interface NetworkInterfaceInfo {
  /** OS interface name, e.g. wlan0, ap0, swlan0, eth0 (Android) / en0, bridge100 (iOS). */
  name: string;
  /** IPv4 address on that interface. */
  ip: string;
  /** CIDR prefix length, when known (Android). */
  prefixLength?: number;
}

export interface NetworkInfo {
  /** Best single IPv4 (Wi-Fi preferred, hotspot/AP next, cellular last). */
  ip: string;
  /** Every usable non-loopback IPv4 — show ALL of these on a pairing screen. */
  interfaces: NetworkInterfaceInfo[];
}

export interface CheckReachableOptions {
  host: string;
  port: number;
  /** Default 2500 ms. */
  timeoutMs?: number;
}

export interface CheckReachableResult {
  reachable: boolean;
  latencyMs?: number;
}

export interface LanTransferPlugin {
  initialize(options: InitializeOptions): Promise<ServerInfo | ConnectionInfo>;

  startServer(options?: { port?: number; sessionKey?: string; encryption?: EncryptionOptions }): Promise<ServerInfo>;
  stopServer(): Promise<void>;
  getServerInfo(): Promise<ServerInfo>;

  /** Reuses the open connection when it is to the same host/port and still alive. */
  connect(options: { host: string; port: number; sessionKey?: string; encryption?: EncryptionOptions }): Promise<ConnectionInfo>;
  disconnect(): Promise<void>;
  getConnectionInfo(): Promise<ConnectionInfo>;

  setReceiveMode(options: SetReceiveModeOptions): Promise<void>;

  /** Sends resolve once the receiver has confirmed the payload (see README "Delivery"). */
  sendString(options: SendStringOptions): Promise<void>;
  sendBase64(options: SendBase64Options): Promise<void>;
  sendFile(options: SendFileOptions): Promise<void>;

  // ---- layered discovery (mDNS/NSD primary + UDP broadcast fallback) ----
  /** LCD: advertise this device on the LAN (mDNS + UDP responder on :53517). */
  advertise(options: AdvertiseOptions): Promise<void>;
  stopAdvertise(): Promise<void>;
  /** Mobile: scan for advertising devices (mDNS browse + UDP broadcast probes);
   *  results arrive via 'deviceFound'/'deviceLost'. */
  startDiscovery(): Promise<void>;
  stopDiscovery(): Promise<void>;

  /** Enumerate all non-loopback IPv4 interfaces (works in hotspot/AP mode too). */
  getNetworkInfo(): Promise<NetworkInfo>;
  /** Android: open the system Wi-Fi settings screen so the user can turn Wi-Fi on
   *  without leaving the app. No-op rejection on web/iOS. */
  openWifiSettings(): Promise<void>;
  /** Android: returns { supported: boolean } indicating whether the device supports
   *  Wi-Fi hotspot / tethering. Returns false on web/iOS. */
  isHotspotSupported(): Promise<{ supported: boolean }>;
  /** Android: open the system Hotspot / Tethering settings screen directly.
   *  No-op rejection on web/iOS. */
  openHotspotSettings(): Promise<void>;
  /** TCP connect probe — distinguishes "wrong IP / offline" from
   *  "network blocks device-to-device traffic (client isolation)". */
  checkReachable(options: CheckReachableOptions): Promise<CheckReachableResult>;

  addListener(
    eventName: 'deviceFound',
    listenerFunc: (event: DeviceFoundEvent) => void
  ): Promise<{ remove: () => Promise<void> }>;

  addListener(
    eventName: 'deviceLost',
    listenerFunc: (event: DeviceLostEvent) => void
  ): Promise<{ remove: () => Promise<void> }>;

  addListener(
    eventName: 'progress',
    listenerFunc: (event: ProgressEvent) => void
  ): Promise<{ remove: () => Promise<void> }>;

  addListener(
    eventName: 'receiveComplete',
    listenerFunc: (event: ReceiveCompleteEvent) => void
  ): Promise<{ remove: () => Promise<void> }>;

  addListener(
    eventName: 'status',
    listenerFunc: (event: StatusEvent) => void
  ): Promise<{ remove: () => Promise<void> }>;

  addListener(
    eventName: 'error',
    listenerFunc: (event: ErrorEvent) => void
  ): Promise<{ remove: () => Promise<void> }>;
}