import { TypeOrmModuleOptions } from '@nestjs/typeorm';
import { User } from '../models/user.entity';
import { Macro } from '../models/macro.entity';

export const databaseConfig: TypeOrmModuleOptions = {
  type: 'postgres',
  url: process.env.DB_URL,
  entities: [User, Macro],
  synchronize: true, // Auto-create tables (set to false in production)
};
