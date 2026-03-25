import { AuthService } from './auth.service';
export declare class AuthController {
    private readonly authService;
    constructor(authService: AuthService);
    register(body: Record<string, any>): Promise<unknown>;
    login(body: Record<string, any>): Promise<unknown>;
}
