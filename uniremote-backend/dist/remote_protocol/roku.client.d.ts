import { TVProtocolClient } from './protocol.factory';
export declare class RokuClient implements TVProtocolClient {
    private ip;
    private connected;
    connect(ip: string): Promise<void>;
    sendCommand(command: any): Promise<void>;
    disconnect(): Promise<void>;
}
