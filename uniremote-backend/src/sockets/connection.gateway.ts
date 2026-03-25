import {
  WebSocketGateway,
  SubscribeMessage,
  MessageBody,
  ConnectedSocket,
  OnGatewayConnection,
  OnGatewayDisconnect,
  WebSocketServer,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';

@WebSocketGateway({ cors: { origin: '*' }, namespace: '/connection' })
export class ConnectionGateway
  implements OnGatewayConnection, OnGatewayDisconnect
{
  @WebSocketServer()
  server: Server;

  private connectedDevices: Map<string, string> = new Map(); // socketId -> deviceId

  handleConnection(client: Socket) {
    console.log(`Client Connected: ${client.id}`);
  }

  handleDisconnect(client: Socket) {
    console.log(`Client Disconnected: ${client.id}`);
    const deviceId = this.connectedDevices.get(client.id);
    if (deviceId) {
      this.connectedDevices.delete(client.id);
      this.server.emit('device_disconnected', { deviceId });
    }
  }

  @SubscribeMessage('register_device')
  handleRegisterDevice(
    @MessageBody() data: { deviceId: string; type: string },
    @ConnectedSocket() client: Socket,
  ) {
    this.connectedDevices.set(client.id, data.deviceId);
    console.log(`Device Registered: ${data.deviceId} (${data.type})`);

    // Broadcast newly connected device
    this.server.emit('device_connected', data);
    return { event: 'registered', data: { status: 'ok' } };
  }

  // Example message to proxy a command from mobile app to TV wrapper layer
  @SubscribeMessage('send_command')
  handleSendCommand(
    @MessageBody()
    data: { targetDeviceId: string; action: string; payload?: unknown },
    @ConnectedSocket() _client: Socket,
  ) {
    console.log(`Received command ${data.action} for ${data.targetDeviceId}`);
    // Ideally we route this via the protocol adapters
    this.server.emit(`command_to_${data.targetDeviceId}`, {
      action: data.action,
      payload: data.payload,
    });
  }
}
