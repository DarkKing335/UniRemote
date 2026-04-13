"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
var __metadata = (this && this.__metadata) || function (k, v) {
    if (typeof Reflect === "object" && typeof Reflect.metadata === "function") return Reflect.metadata(k, v);
};
var __param = (this && this.__param) || function (paramIndex, decorator) {
    return function (target, key) { decorator(target, key, paramIndex); }
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ConnectionGateway = void 0;
const websockets_1 = require("@nestjs/websockets");
const socket_io_1 = require("socket.io");
let ConnectionGateway = class ConnectionGateway {
    server;
    connectedDevices = new Map();
    handleConnection(client) {
        console.log(`Client Connected: ${client.id}`);
    }
    handleDisconnect(client) {
        console.log(`Client Disconnected: ${client.id}`);
        const deviceId = this.connectedDevices.get(client.id);
        if (deviceId) {
            this.connectedDevices.delete(client.id);
            this.server.emit('device_disconnected', { deviceId });
        }
    }
    handleRegisterDevice(data, client) {
        this.connectedDevices.set(client.id, data.deviceId);
        console.log(`Device Registered: ${data.deviceId} (${data.type})`);
        this.server.emit('device_connected', data);
        return { event: 'registered', data: { status: 'ok' } };
    }
    handleSendCommand(data, _client) {
        console.log(`Received command ${data.action} for ${data.targetDeviceId}`);
        this.server.emit(`command_to_${data.targetDeviceId}`, {
            action: data.action,
            payload: data.payload,
        });
    }
};
exports.ConnectionGateway = ConnectionGateway;
__decorate([
    (0, websockets_1.WebSocketServer)(),
    __metadata("design:type", socket_io_1.Server)
], ConnectionGateway.prototype, "server", void 0);
__decorate([
    (0, websockets_1.SubscribeMessage)('register_device'),
    __param(0, (0, websockets_1.MessageBody)()),
    __param(1, (0, websockets_1.ConnectedSocket)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, socket_io_1.Socket]),
    __metadata("design:returntype", void 0)
], ConnectionGateway.prototype, "handleRegisterDevice", null);
__decorate([
    (0, websockets_1.SubscribeMessage)('send_command'),
    __param(0, (0, websockets_1.MessageBody)()),
    __param(1, (0, websockets_1.ConnectedSocket)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, socket_io_1.Socket]),
    __metadata("design:returntype", void 0)
], ConnectionGateway.prototype, "handleSendCommand", null);
exports.ConnectionGateway = ConnectionGateway = __decorate([
    (0, websockets_1.WebSocketGateway)({ cors: { origin: '*' }, namespace: '/connection' })
], ConnectionGateway);
//# sourceMappingURL=connection.gateway.js.map