/* eslint-disable @typescript-eslint/require-await */
import { TVProtocolClient } from './protocol.factory';

export class RokuClient implements TVProtocolClient {
  private ip: string;
  private connected = false;

  async connect(ip: string): Promise<void> {
    // Uses External Control Protocol (ECP), REST HTTP on port 8060
    this.ip = ip;
    console.log(`[Roku] Connecting to ${ip}:8060`);
    this.connected = true;
  }

  async sendCommand(command: any): Promise<void> {
    if (!this.connected) throw new Error('Not connected to Roku');
    // Example: HTTP POST http://<ip>:8060/keypress/<key>
    console.log(
      `[Roku] Sending command: ${JSON.stringify(command)} to ${this.ip}`,
    );
  }

  async disconnect(): Promise<void> {
    console.log(`[Roku] Disconnecting from ${this.ip}`);
    this.connected = false;
  }
}
