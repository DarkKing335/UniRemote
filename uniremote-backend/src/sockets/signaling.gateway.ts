import {
  WebSocketGateway,
  SubscribeMessage,
  MessageBody,
  ConnectedSocket,
  WebSocketServer,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';

@WebSocketGateway({ cors: { origin: '*' }, namespace: '/signaling' })
export class SignalingGateway {
  @WebSocketServer()
  server: Server;

  // WebRTC Signaling: Offer
  @SubscribeMessage('offer')
  handleOffer(
    @MessageBody() data: { targetId: string; sdp: unknown; senderId: string },
    @ConnectedSocket() _client: Socket,
  ) {
    this.server.emit(`offer_${data.targetId}`, {
      sdp: data.sdp,
      senderId: data.senderId,
    });
  }

  // WebRTC Signaling: Answer
  @SubscribeMessage('answer')
  handleAnswer(
    @MessageBody() data: { targetId: string; sdp: unknown; senderId: string },
    @ConnectedSocket() _client: Socket,
  ) {
    this.server.emit(`answer_${data.targetId}`, {
      sdp: data.sdp,
      senderId: data.senderId,
    });
  }

  // WebRTC Signaling: ICE Candidate
  @SubscribeMessage('ice_candidate')
  handleIceCandidate(
    @MessageBody() data: { targetId: string; candidate: unknown; senderId: string },
    @ConnectedSocket() _client: Socket,
  ) {
    this.server.emit(`ice_candidate_${data.targetId}`, {
      candidate: data.candidate,
      senderId: data.senderId,
    });
  }
}
