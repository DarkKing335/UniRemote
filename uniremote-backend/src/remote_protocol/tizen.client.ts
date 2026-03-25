/* eslint-disable @typescript-eslint/require-await */
import { TVProtocolClient } from './protocol.factory';

export class TizenClient implements TVProtocolClient {
  private ip: string;
  private connected = false;

  async connect(ip: string): Promise<void> {
    // Uses ws://ip:8001/api/v2/channels/samsung.remote.control
    this.ip = ip;
    console.log(`[Tizen] Connecting to ${ip}:8001`);
    this.connected = true;
  }

  async sendCommand(command: any): Promise<void> {
    if (!this.connected) throw new Error('Not connected to Tizen TV');
    console.log(
      `[Tizen] Sending command: ${JSON.stringify(command)} to ${this.ip}`,
    );
  }

  async disconnect(): Promise<void> {
    console.log(`[Tizen] Disconnecting from ${this.ip}`);
    this.connected = false;
  }
}
