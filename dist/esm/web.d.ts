import { WebPlugin } from '@capacitor/core';
import type { LanTransferPlugin, InitializeOptions, ServerInfo, ConnectionInfo, SendStringOptions, SendBase64Options, SendFileOptions, SetReceiveModeOptions, NetworkInfo, CheckReachableResult } from './definitions';
export declare class LanTransferWeb extends WebPlugin implements LanTransferPlugin {
    initialize(_options: InitializeOptions): Promise<ServerInfo | ConnectionInfo>;
    startServer(): Promise<ServerInfo>;
    stopServer(): Promise<void>;
    getServerInfo(): Promise<ServerInfo>;
    connect(): Promise<ConnectionInfo>;
    disconnect(): Promise<void>;
    getConnectionInfo(): Promise<ConnectionInfo>;
    setReceiveMode(_options: SetReceiveModeOptions): Promise<void>;
    sendString(_options: SendStringOptions): Promise<void>;
    sendBase64(_options: SendBase64Options): Promise<void>;
    sendFile(_options: SendFileOptions): Promise<void>;
    advertise(): Promise<void>;
    stopAdvertise(): Promise<void>;
    startDiscovery(): Promise<void>;
    stopDiscovery(): Promise<void>;
    getNetworkInfo(): Promise<NetworkInfo>;
    openWifiSettings(): Promise<void>;
    isHotspotSupported(): Promise<{
        supported: boolean;
    }>;
    openHotspotSettings(): Promise<void>;
    checkReachable(): Promise<CheckReachableResult>;
}
