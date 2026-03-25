import { Injectable, NotImplementedException } from '@nestjs/common';
import { WebOSClient } from './webos.client';
import { TizenClient } from './tizen.client';
import { AppleTVBridge } from './apple_tv.bridge';
import { AndroidTVClient } from './android_tv.client';
import { RokuClient } from './roku.client';

export interface TVProtocolClient {
  connect(ip: string): Promise<void>;
  sendCommand(command: any): Promise<void>;
  disconnect(): Promise<void>;
}

@Injectable()
export class ProtocolFactory {
  // In a real app these instances might be transient or scoped per IP
  getClient(osType: string): TVProtocolClient {
    switch (osType.toLowerCase()) {
      case 'webos':
        return new WebOSClient();
      case 'tizen':
        return new TizenClient();
      case 'tvos':
        return new AppleTVBridge();
      case 'androidtv':
        return new AndroidTVClient();
      case 'roku':
        return new RokuClient();
      default:
        throw new NotImplementedException(
          `Protocol for ${osType} is not supported yet`,
        );
    }
  }
}
