import {
  Controller,
  Get,
  Post,
  Delete,
  Body,
  Param,
  UseGuards,
  Request,
} from '@nestjs/common';
import { MacrosService } from './macros.service';
import { JwtAuthGuard } from '../../../middleware/auth.guard';

interface RequestWithUser {
  user: { userId: string };
}

@Controller('v1/macros')
@UseGuards(JwtAuthGuard)
export class MacrosController {
  constructor(private readonly macrosService: MacrosService) {}

  @Post()
  async createMacro(@Request() req: RequestWithUser, @Body() body: Record<string, any>) {
    return this.macrosService.createMacro(
      req.user.userId,
      String(body.name),
      body.commands,
    );
  }

  @Get()
  async getMacros(@Request() req: RequestWithUser) {
    return this.macrosService.getMacros(req.user.userId);
  }

  @Delete(':id')
  async deleteMacro(@Request() req: RequestWithUser, @Param('id') id: string) {
    await this.macrosService.deleteMacro(req.user.userId, id);
    return { success: true };
  }
}
