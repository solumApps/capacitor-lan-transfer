import { WebPlugin } from '@capacitor/core';
import type {
  LanTransferPlugin,
  InitializeOptions,
  ServerInfo,
  ConnectionInfo,
  SendStringOptions,
  SendBase64Options,
  SendFileOptions,
  SetReceiveModeOptions,
  NetworkInfo,
  CheckReachableResult,
} from './definitions';

export class LanTransferWeb extends WebPlugin implements LanTransferPlugin {
  async initialize(_options: InitializeOptions): Promise<ServerInfo | ConnectionInfo> {
    throw new Error('LanTransfer: Web is not supported (native Android/iOS only).');
  }
  async startServer(): Promise<ServerInfo> {
    throw new Error('LanTransfer: Web is not supported.');
  }
  async stopServer(): Promise<void> {
    throw new Error('LanTransfer: Web is not supported.');
  }
  async getServerInfo(): Promise<ServerInfo> {
    return { running: false, ip: '0.0.0.0', port: 0 };
  }

  async connect(): Promise<ConnectionInfo> {
    throw new Error('LanTransfer: Web is not supported.');
  }
  async disconnect(): Promise<void> {
    throw new Error('LanTransfer: Web is not supported.');
  }
  async getConnectionInfo(): Promise<ConnectionInfo> {
    return { connected: false, role: 'client' };
  }

  async setReceiveMode(_options: SetReceiveModeOptions): Promise<void> {
    throw new Error('LanTransfer: Web is not supported.');
  }

  async sendString(_options: SendStringOptions): Promise<void> {
    throw new Error('LanTransfer: Web is not supported.');
  }
  async sendBase64(_options: SendBase64Options): Promise<void> {
    throw new Error('LanTransfer: Web is not supported.');
  }
  async sendFile(_options: SendFileOptions): Promise<void> {
    throw new Error('LanTransfer: Web is not supported.');
  }

  async advertise(): Promise<void> {
    throw new Error('LanTransfer: Web is not supported.');
  }
  async stopAdvertise(): Promise<void> {
    throw new Error('LanTransfer: Web is not supported.');
  }
  async startDiscovery(): Promise<void> {
    throw new Error('LanTransfer: Web is not supported.');
  }
  async stopDiscovery(): Promise<void> {
    throw new Error('LanTransfer: Web is not supported.');
  }

  async getNetworkInfo(): Promise<NetworkInfo> {
    return { ip: '0.0.0.0', interfaces: [] };
  }
  async checkReachable(): Promise<CheckReachableResult> {
    return { reachable: false };
  }
}