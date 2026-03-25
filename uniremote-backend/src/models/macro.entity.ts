import {
  Entity,
  Column,
  PrimaryGeneratedColumn,
  CreateDateColumn,
  ManyToOne,
} from 'typeorm';
import { User } from './user.entity';

@Entity('macros')
export class Macro {
  @PrimaryGeneratedColumn('uuid')
  id: string;

  @Column()
  name: string;

  // JSON array for the sequence of commands
  @Column({ type: 'jsonb', default: [] })
  commands: any[];

  @ManyToOne(() => User, (user) => user.macros)
  user: User;

  @CreateDateColumn()
  createdAt: Date;
}
