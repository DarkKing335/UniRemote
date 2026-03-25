import { Module } from '@nestjs/common';
import { TypeOrmModule } from '@nestjs/typeorm';
import { MacrosService } from './macros.service';
import { MacrosController } from './macros.controller';
import { Macro } from '../../../models/macro.entity';
import { User } from '../../../models/user.entity';

@Module({
  imports: [TypeOrmModule.forFeature([Macro, User])],
  providers: [MacrosService],
  controllers: [MacrosController],
})
export class MacrosModule {}
