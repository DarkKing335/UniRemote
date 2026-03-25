/* eslint-disable @typescript-eslint/require-await */
import { TVProtocolClient } from './protocol.factory';

export class AppleTVBridge implements TVProtocolClient {
  private ip: string;
  private connected = false;

  async connect(ip: string): Promise<void> {
    // Uses MediaRemote Protocol (MRP) typically on port 49152+
    this.ip = ip;
    console.log(`[AppleTV] Connecting via MRP to ${ip}`);
    this.connected = true;
  }

  async sendCommand(command: any): Promise<void> {
    if (!this.connected) throw new Error('Not connected to Apple TV');
    console.log(
      `[AppleTV] Sending command: ${JSON.stringify(command)} to ${this.ip}`,
    );
  }

  async disconnect(): Promise<void> {
    console.log(`[AppleTV] Disconnecting from ${this.ip}`);
    this.connected = false;
  }
}
