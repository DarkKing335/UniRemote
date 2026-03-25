"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.AppleTVBridge = void 0;
class AppleTVBridge {
    ip;
    connected = false;
    async connect(ip) {
        this.ip = ip;
        console.log(`[AppleTV] Connecting via MRP to ${ip}`);
        this.connected = true;
    }
    async sendCommand(command) {
        if (!this.connected)
            throw new Error('Not connected to Apple TV');
        console.log(`[AppleTV] Sending command: ${JSON.stringify(command)} to ${this.ip}`);
    }
    async disconnect() {
        console.log(`[AppleTV] Disconnecting from ${this.ip}`);
        this.connected = false;
    }
}
exports.AppleTVBridge = AppleTVBridge;
//# sourceMappingURL=apple_tv.bridge.js.map