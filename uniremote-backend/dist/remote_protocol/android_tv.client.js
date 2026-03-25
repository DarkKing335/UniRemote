"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.AndroidTVClient = void 0;
class AndroidTVClient {
    ip;
    connected = false;
    async connect(ip) {
        this.ip = ip;
        console.log(`[AndroidTV] Connecting to ${ip}:6466`);
        this.connected = true;
    }
    async sendCommand(command) {
        if (!this.connected)
            throw new Error('Not connected to Android TV');
        console.log(`[AndroidTV] Sending command: ${JSON.stringify(command)} to ${this.ip}`);
    }
    async disconnect() {
        console.log(`[AndroidTV] Disconnecting from ${this.ip}`);
        this.connected = false;
    }
}
exports.AndroidTVClient = AndroidTVClient;
//# sourceMappingURL=android_tv.client.js.map