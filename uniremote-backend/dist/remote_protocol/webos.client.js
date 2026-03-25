"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.WebOSClient = void 0;
class WebOSClient {
    ip;
    connected = false;
    async connect(ip) {
        this.ip = ip;
        console.log(`[WebOS] Connecting to ${ip}:3000`);
        this.connected = true;
    }
    async sendCommand(command) {
        if (!this.connected)
            throw new Error('Not connected to WebOS TV');
        console.log(`[WebOS] Sending command: ${JSON.stringify(command)} to ${this.ip}`);
    }
    async disconnect() {
        console.log(`[WebOS] Disconnecting from ${this.ip}`);
        this.connected = false;
    }
}
exports.WebOSClient = WebOSClient;
//# sourceMappingURL=webos.client.js.map