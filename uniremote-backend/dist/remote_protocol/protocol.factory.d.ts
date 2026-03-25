export interface TVProtocolClient {
    connect(ip: string): Promise<void>;
    sendCommand(command: any): Promise<void>;
    disconnect(): Promise<void>;
}
export declare class ProtocolFactory {
    getClient(osType: string): TVProtocolClient;
}
