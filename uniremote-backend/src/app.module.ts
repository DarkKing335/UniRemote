import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { TypeOrmModule } from '@nestjs/typeorm';
import { databaseConfig } from './config/database.config';
import configuration from './config/configuration';

import { AuthModule } from './api/v1/auth/auth.module';
import { MacrosModule } from './api/v1/macros/macros.module';
import { SocketsModule } from './sockets/sockets.module';

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      load: [configuration],
    }),
    TypeOrmModule.forRoot(databaseConfig),
    AuthModule,
    MacrosModule,
    SocketsModule,
  ],
})
export class AppModule {}
