"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.RokuClient = void 0;
class RokuClient {
    ip;
    connected = false;
    async connect(ip) {
        this.ip = ip;
        console.log(`[Roku] Connecting to ${ip}:8060`);
        this.connected = true;
    }
    async sendCommand(command) {
        if (!this.connected)
            throw new Error('Not connected to Roku');
        console.log(`[Roku] Sending command: ${JSON.stringify(command)} to ${this.ip}`);
    }
    async disconnect() {
        console.log(`[Roku] Disconnecting from ${this.ip}`);
        this.connected = false;
    }
}
exports.RokuClient = RokuClient;
//# sourceMappingURL=roku.client.js.map