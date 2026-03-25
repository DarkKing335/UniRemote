import { Repository } from 'typeorm';
import { Macro } from '../../../models/macro.entity';
import { User } from '../../../models/user.entity';
export declare class MacrosService {
    private macrosRepository;
    private userRepository;
    constructor(macrosRepository: Repository<Macro>, userRepository: Repository<User>);
    createMacro(userId: string, name: string, commands: any[]): Promise<Macro>;
    getMacros(userId: string): Promise<Macro[]>;
    deleteMacro(userId: string, macroId: string): Promise<void>;
}
