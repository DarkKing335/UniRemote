"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.databaseConfig = void 0;
const user_entity_1 = require("../models/user.entity");
const macro_entity_1 = require("../models/macro.entity");
exports.databaseConfig = {
    type: 'postgres',
    url: process.env.DB_URL,
    entities: [user_entity_1.User, macro_entity_1.Macro],
    synchronize: true,
};
//# sourceMappingURL=database.config.js.map