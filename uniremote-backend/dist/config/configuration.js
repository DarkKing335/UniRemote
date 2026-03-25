"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.default = () => ({
    port: parseInt(process.env.PORT || '3000', 10),
    database: {
        url: process.env.DB_URL,
    },
    jwt: {
        secret: process.env.JWT_SECRET,
    },
});
//# sourceMappingURL=configuration.js.map