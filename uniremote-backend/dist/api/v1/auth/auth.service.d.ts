import { JwtService } from '@nestjs/jwt';
import { Repository } from 'typeorm';
import { User } from '../../../models/user.entity';
export declare class AuthService {
    private userRepository;
    private jwtService;
    constructor(userRepository: Repository<User>, jwtService: JwtService);
    register(username: string, pass: string): Promise<unknown>;
    login(username: string, pass: string): Promise<unknown>;
}
