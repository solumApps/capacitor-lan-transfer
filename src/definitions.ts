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

export interface LanTransferPlugin {
  initialize(options: InitializeOptions): Promise<ServerInfo | ConnectionInfo>;

  startServer(options?: { port?: number; sessionKey?: string; encryption?: EncryptionOptions }): Promise<ServerInfo>;
  stopServer(): Promise<void>;
  getServerInfo(): Promise<ServerInfo>;

  connect(options: { host: string; port: number; sessionKey?: string; encryption?: EncryptionOptions }): Promise<ConnectionInfo>;
  disconnect(): Promise<void>;
  getConnectionInfo(): Promise<ConnectionInfo>;

  setReceiveMode(options: SetReceiveModeOptions): Promise<void>;

  sendString(options: SendStringOptions): Promise<void>;
  sendBase64(options: SendBase64Options): Promise<void>;
  sendFile(options: SendFileOptions): Promise<void>;

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