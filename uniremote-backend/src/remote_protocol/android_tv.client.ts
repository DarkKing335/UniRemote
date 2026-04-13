/* eslint-disable @typescript-eslint/require-await */
import { TVProtocolClient } from './protocol.factory';

export class AndroidTVClient implements TVProtocolClient {
  private ip: string;
  private connected = false;

  async connect(ip: string): Promise<void> {
    // Uses V2 API Protocol Buffers over TLS (port 6466 or 6467)
    this.ip = ip;
    console.log(`[AndroidTV] Connecting to ${ip}:6466`);
    this.connected = true;
  }

  async sendCommand(command: any): Promise<void> {
    if (!this.connected) throw new Error('Not connected to Android TV');
    console.log(
      `[AndroidTV] Sending command: ${JSON.stringify(command)} to ${this.ip}`,
    );
  }

  async disconnect(): Promise<void> {
    console.log(`[AndroidTV] Disconnecting from ${this.ip}`);
    this.connected = false;
  }
}
