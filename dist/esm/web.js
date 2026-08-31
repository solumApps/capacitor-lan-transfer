import { WebPlugin } from '@capacitor/core';
export class LanTransferWeb extends WebPlugin {
    async initialize(_options) {
        throw new Error('LanTransfer: Web is not supported (native Android/iOS only).');
    }
    async startServer() {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async stopServer() {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async getServerInfo() {
        return { running: false, ip: '0.0.0.0', port: 0 };
    }
    async connect() {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async disconnect() {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async getConnectionInfo() {
        return { connected: false, role: 'client' };
    }
    async setReceiveMode(_options) {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async sendString(_options) {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async sendBase64(_options) {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async sendFile(_options) {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async advertise() {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async stopAdvertise() {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async startDiscovery() {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async stopDiscovery() {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async getNetworkInfo() {
        return { ip: '0.0.0.0', interfaces: [] };
    }
    async openWifiSettings() {
        throw new Error('LanTransfer: Web is not supported.');
    }
    async checkReachable() {
        return { reachable: false };
    }
}
