"use strict";
var __decorate = (this && this.__decorate) || function (decorators, target, key, desc) {
    var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
    if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
    else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
    return c > 3 && r && Object.defineProperty(target, key, r), r;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.ProtocolFactory = void 0;
const common_1 = require("@nestjs/common");
const webos_client_1 = require("./webos.client");
const tizen_client_1 = require("./tizen.client");
const apple_tv_bridge_1 = require("./apple_tv.bridge");
const android_tv_client_1 = require("./android_tv.client");
const roku_client_1 = require("./roku.client");
let ProtocolFactory = class ProtocolFactory {
    getClient(osType) {
        switch (osType.toLowerCase()) {
            case 'webos':
                return new webos_client_1.WebOSClient();
            case 'tizen':
                return new tizen_client_1.TizenClient();
            case 'tvos':
                return new apple_tv_bridge_1.AppleTVBridge();
            case 'androidtv':
                return new android_tv_client_1.AndroidTVClient();
            case 'roku':
                return new roku_client_1.RokuClient();
            default:
                throw new common_1.NotImplementedException(`Protocol for ${osType} is not supported yet`);
        }
    }
};
exports.ProtocolFactory = ProtocolFactory;
exports.ProtocolFactory = ProtocolFactory = __decorate([
    (0, common_1.Injectable)()
], ProtocolFactory);
//# sourceMappingURL=protocol.factory.js.map