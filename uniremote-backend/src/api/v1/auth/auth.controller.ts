import { Controller, Post, Body } from '@nestjs/common';
import { AuthService } from './auth.service';

@Controller('v1/auth')
export class AuthController {
  constructor(private readonly authService: AuthService) {}

  @Post('register')
  async register(@Body() body: Record<string, any>): Promise<unknown> {
    return this.authService.register(
      String(body.username),
      String(body.password),
    );
  }

  @Post('login')
  async login(@Body() body: Record<string, any>): Promise<unknown> {
    return this.authService.login(String(body.username), String(body.password));
  }
}
