import { MacrosService } from './macros.service';
interface RequestWithUser {
    user: {
        userId: string;
    };
}
export declare class MacrosController {
    private readonly macrosService;
    constructor(macrosService: MacrosService);
    createMacro(req: RequestWithUser, body: Record<string, any>): Promise<import("../../../models/macro.entity").Macro>;
    getMacros(req: RequestWithUser): Promise<import("../../../models/macro.entity").Macro[]>;
    deleteMacro(req: RequestWithUser, id: string): Promise<{
        success: boolean;
    }>;
}
export {};
