import {
  Entity,
  Column,
  PrimaryGeneratedColumn,
  CreateDateColumn,
  OneToMany,
} from 'typeorm';
import { Macro } from './macro.entity';

@Entity('users')
export class User {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column({ unique: true })
  username: string;

  @Column()
  passwordHash: string;

  @OneToMany(() => Macro, (macro) => macro.user)
  macros: Macro[];

  @CreateDateColumn()
  createdAt: Date;
}
