/* eslint-disable @typescript-eslint/require-await */
import { TVProtocolClient } from './protocol.factory';

export class WebOSClient implements TVProtocolClient {
  private ip: string;
  private connected = false;

  async connect(ip: string): Promise<void> {
    // In a real implementation this would use a library like lgtv2 over ws://ip:3000
    this.ip = ip;
    console.log(`[WebOS] Connecting to ${ip}:3000`);
    this.connected = true;
  }

  async sendCommand(command: any): Promise<void> {
    if (!this.connected) throw new Error('Not connected to WebOS TV');
    console.log(
      `[WebOS] Sending command: ${JSON.stringify(command)} to ${this.ip}`,
    );
  }

  async disconnect(): Promise<void> {
    console.log(`[WebOS] Disconnecting from ${this.ip}`);
    this.connected = false;
  }
}
