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
exports.MacrosService = void 0;
const common_1 = require("@nestjs/common");
const typeorm_1 = require("@nestjs/typeorm");
const typeorm_2 = require("typeorm");
const macro_entity_1 = require("../../../models/macro.entity");
const user_entity_1 = require("../../../models/user.entity");
let MacrosService = class MacrosService {
    macrosRepository;
    userRepository;
    constructor(macrosRepository, userRepository) {
        this.macrosRepository = macrosRepository;
        this.userRepository = userRepository;
    }
    async createMacro(userId, name, commands) {
        const user = await this.userRepository.findOne({ where: { id: userId } });
        if (!user)
            throw new common_1.NotFoundException('User not found');
        const macro = this.macrosRepository.create({ name, commands, user });
        return this.macrosRepository.save(macro);
    }
    async getMacros(userId) {
        return this.macrosRepository.find({
            where: { user: { id: userId } },
        });
    }
    async deleteMacro(userId, macroId) {
        const macro = await this.macrosRepository.findOne({
            where: { id: macroId, user: { id: userId } },
        });
        if (!macro)
            throw new common_1.NotFoundException('Macro not found for user');
        await this.macrosRepository.remove(macro);
    }
};
exports.MacrosService = MacrosService;
exports.MacrosService = MacrosService = __decorate([
    (0, common_1.Injectable)(),
    __param(0, (0, typeorm_1.InjectRepository)(macro_entity_1.Macro)),
    __param(1, (0, typeorm_1.InjectRepository)(user_entity_1.User)),
    __metadata("design:paramtypes", [typeorm_2.Repository,
        typeorm_2.Repository])
], MacrosService);
//# sourceMappingURL=macros.service.js.map