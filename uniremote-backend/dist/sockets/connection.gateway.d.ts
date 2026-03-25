import { OnGatewayConnection, OnGatewayDisconnect } from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
export declare class ConnectionGateway implements OnGatewayConnection, OnGatewayDisconnect {
    server: Server;
    private connectedDevices;
    handleConnection(client: Socket): void;
    handleDisconnect(client: Socket): void;
    handleRegisterDevice(data: {
        deviceId: string;
        type: string;
    }, client: Socket): {
        event: string;
        data: {
            status: string;
        };
    };
    handleSendCommand(data: {
        targetDeviceId: string;
        action: string;
        payload?: unknown;
    }, _client: Socket): void;
}
