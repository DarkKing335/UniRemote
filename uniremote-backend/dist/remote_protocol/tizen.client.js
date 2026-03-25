"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.TizenClient = void 0;
class TizenClient {
    ip;
    connected = false;
    async connect(ip) {
        this.ip = ip;
        console.log(`[Tizen] Connecting to ${ip}:8001`);
        this.connected = true;
    }
    async sendCommand(command) {
        if (!this.connected)
            throw new Error('Not connected to Tizen TV');
        console.log(`[Tizen] Sending command: ${JSON.stringify(command)} to ${this.ip}`);
    }
    async disconnect() {
        console.log(`[Tizen] Disconnecting from ${this.ip}`);
        this.connected = false;
    }
}
exports.TizenClient = TizenClient;
//# sourceMappingURL=tizen.client.js.map