import { registerPlugin } from '@capacitor/core';
const LanTransfer = registerPlugin('LanTransfer', {
    web: () => import('./web').then(m => new m.LanTransferWeb()),
});
export * from './definitions';
export { LanTransfer };
