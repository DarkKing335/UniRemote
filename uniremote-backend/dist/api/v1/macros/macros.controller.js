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
exports.MacrosController = void 0;
const common_1 = require("@nestjs/common");
const macros_service_1 = require("./macros.service");
const auth_guard_1 = require("../../../middleware/auth.guard");
let MacrosController = class MacrosController {
    macrosService;
    constructor(macrosService) {
        this.macrosService = macrosService;
    }
    async createMacro(req, body) {
        return this.macrosService.createMacro(req.user.userId, String(body.name), body.commands);
    }
    async getMacros(req) {
        return this.macrosService.getMacros(req.user.userId);
    }
    async deleteMacro(req, id) {
        await this.macrosService.deleteMacro(req.user.userId, id);
        return { success: true };
    }
};
exports.MacrosController = MacrosController;
__decorate([
    (0, common_1.Post)(),
    __param(0, (0, common_1.Request)()),
    __param(1, (0, common_1.Body)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, Object]),
    __metadata("design:returntype", Promise)
], MacrosController.prototype, "createMacro", null);
__decorate([
    (0, common_1.Get)(),
    __param(0, (0, common_1.Request)()),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object]),
    __metadata("design:returntype", Promise)
], MacrosController.prototype, "getMacros", null);
__decorate([
    (0, common_1.Delete)(':id'),
    __param(0, (0, common_1.Request)()),
    __param(1, (0, common_1.Param)('id')),
    __metadata("design:type", Function),
    __metadata("design:paramtypes", [Object, String]),
    __metadata("design:returntype", Promise)
], MacrosController.prototype, "deleteMacro", null);
exports.MacrosController = MacrosController = __decorate([
    (0, common_1.Controller)('v1/macros'),
    (0, common_1.UseGuards)(auth_guard_1.JwtAuthGuard),
    __metadata("design:paramtypes", [macros_service_1.MacrosService])
], MacrosController);
//# sourceMappingURL=macros.controller.js.map