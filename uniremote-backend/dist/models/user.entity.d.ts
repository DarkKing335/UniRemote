import { Macro } from './macro.entity';
export declare class User {
    id: string;
    username: string;
    passwordHash: string;
    macros: Macro[];
    createdAt: Date;
}
