import { User } from './user.entity';
export declare class Macro {
    id: string;
    name: string;
    commands: any[];
    user: User;
    createdAt: Date;
}
