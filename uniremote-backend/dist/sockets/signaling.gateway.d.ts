import { Server, Socket } from 'socket.io';
export declare class SignalingGateway {
    server: Server;
    handleOffer(data: {
        targetId: string;
        sdp: unknown;
        senderId: string;
    }, _client: Socket): void;
    handleAnswer(data: {
        targetId: string;
        sdp: unknown;
        senderId: string;
    }, _client: Socket): void;
    handleIceCandidate(data: {
        targetId: string;
        candidate: unknown;
        senderId: string;
    }, _client: Socket): void;
}
