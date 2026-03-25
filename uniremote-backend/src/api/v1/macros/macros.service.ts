import { Injectable, NotFoundException } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { Macro } from '../../../models/macro.entity';
import { User } from '../../../models/user.entity';

@Injectable()
export class MacrosService {
  constructor(
    @InjectRepository(Macro)
    private macrosRepository: Repository<Macro>,
    @InjectRepository(User)
    private userRepository: Repository<User>,
  ) {}

  async createMacro(
    userId: string,
    name: string,
    commands: any[],
  ): Promise<Macro> {
    const user = await this.userRepository.findOne({ where: { id: userId } });
    if (!user) throw new NotFoundException('User not found');

    const macro = this.macrosRepository.create({ name, commands, user });
    return this.macrosRepository.save(macro);
  }

  async getMacros(userId: string): Promise<Macro[]> {
    return this.macrosRepository.find({
      where: { user: { id: userId } },
    });
  }

  async deleteMacro(userId: string, macroId: string): Promise<void> {
    const macro = await this.macrosRepository.findOne({
      where: { id: macroId, user: { id: userId } },
    });
    if (!macro) throw new NotFoundException('Macro not found for user');
    await this.macrosRepository.remove(macro);
  }
}
