import { Module } from '@nestjs/common';
import { ConnectionGateway } from './connection.gateway';
import { SignalingGateway } from './signaling.gateway';

@Module({
  providers: [ConnectionGateway, SignalingGateway],
})
export class SocketsModule {}
