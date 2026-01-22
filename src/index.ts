import { registerPlugin } from '@capacitor/core';
import type { LanTransferPlugin } from './definitions';

const LanTransfer = registerPlugin<LanTransferPlugin>('LanTransfer', {
  web: () => import('./web').then(m => new m.LanTransferWeb()),
});

export * from './definitions';
export { LanTransfer };