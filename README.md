# capacitor-lan-transfer

Capacitor (Android + iOS) plugin for **large LAN TCP transfers** with:

- **Server/client roles**
- **connect → send → done** workflow (one transfer per connection; reconnect for the next)
- Streaming transfers with **progress** events
- Optional **AES‑256‑GCM encryption** (PBKDF2 key derivation, streaming chunks)
- **SHA‑256 integrity hash** of **PLAINTEXT** on sender and receiver

> Web is not supported.

---

## Features

- ✅ Android + iOS
- ✅ Server mode returns **IP + port**
- ✅ Client mode connects by **IP + port**
- ✅ Large payload streaming with real-time **progress**
- ✅ Optional **AES‑GCM** encryption (no third-party dependencies)
- ✅ Integrity verification via **SHA‑256**
- ✅ `sendFile()` supports `file://...` URIs from Capacitor Filesystem
- ✅ Android `sendFile()` also supports `content://...` URIs when size is detectable

---

## Install

### 1) Build the plugin (from plugin folder)

```bash
npm i
npm run build
```

### 2) Install into your Ionic Angular + Capacitor app

```bash
# from your app folder
npm i https://github.com/solumApps/capacitor-lan-transfer.git
npx cap sync
```

---

## Platform configuration

### iOS (required)

In your host app: `ios/App/App/Info.plist` add:

- `NSLocalNetworkUsageDescription` (String)

Example value:
> "This app transfers data to another device on your local network."

Then:

```bash
npx cap sync ios
```

### Android

Plugin manifest includes:

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`

Make sure to do apply these changes root build.grade file of android folder

- ` buildscript { ext { kotlin_version = '1.9.24'} } `
- ` buildscript { dependencies { classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version" } } `

No runtime prompt is needed for basic LAN TCP.

---

## API overview

### initialize(options)

- `role: 'server'` starts server and returns `{ ip, port }`
- `role: 'client'` connects to `{ host, serverPort }` and returns connection info

### Sending

- `sendString({ text })`
- `sendBase64({ base64 })`
- `sendFile({ path })`

`sendFile({ path })` accepts:

- `file://...` URI (**Capacitor Filesystem returns this**) ✅
- Android: `content://...` if size is detectable (some pickers) ✅
- raw absolute filesystem path ✅

---

## Events

### status

Possible values:

- `server_started`, `server_stopped`
- `client_connected`, `client_disconnected`
- `connection_accepted`, `connection_closed`
- `receive_started` (server received header; includes `totalBytes`, `encoding`, `encrypted`)
- `send_started` (client started sending; includes `totalBytes`, `encoding`, `encrypted`)
- `send_complete` (client finished sending; includes `sha256`)

### progress

- emitted during **send** (client) and **receive** (server)
- percentage is based on **PLAINTEXT bytes**:
  - `bytesTransferred` / `totalBytes`

### receiveComplete

- includes:
  - `filePath` (file mode) OR `text` / `base64` (memory mode)
  - `sha256` (hex SHA‑256 of PLAINTEXT bytes received)

### error

- `{ role, message, code? }`

---

## Recommended usage for large JSON (Ionic Angular): write file → sendFile

This is the best approach for large JSON (especially if it contains base64 images).

### `sendPayloadJson.ts` (client helper)

```ts
import { Filesystem, Directory, Encoding } from '@capacitor/filesystem';
import { LanTransfer } from 'capacitor-lan-transfer';

export async function sendPayloadJson(payload: unknown) {
  const fileName = `payload_${Date.now()}.json`;

  // 1) Write JSON to a local temp file (returns file://... uri)
  const { uri } = await Filesystem.writeFile({
    path: fileName,
    directory: Directory.Cache,
    data: JSON.stringify(payload),
    encoding: Encoding.UTF8,
  });

  // 2) Send it (plugin accepts file:// URIs)
  await LanTransfer.sendFile({ path: uri });

  // 3) Optional cleanup
  try {
    await Filesystem.deleteFile({ path: fileName, directory: Directory.Cache });
  } catch {
    // ignore
  }
}
```

---

## Usage examples

### A) Server (receiver)

```ts
import { LanTransfer } from 'capacitor-lan-transfer';

export async function startServer() {
  const info = await LanTransfer.initialize({
    role: 'server',
    port: 0, // 0 = auto pick port
    receiveMode: 'file',
    receiveFileName: `incoming_${Date.now()}.json`,

    // encryption is optional; omit to allow plaintext by default
    // encryption: {
    //   enabled: true,
    //   passphrase: 'shared-secret',
    //   requireEncryption: false
    // }
  });

  console.log('Server running at:', info.ip, info.port);

  await LanTransfer.addListener('status', e => {
    if (e.status === 'receive_started') {
      console.log(`Incoming: ${e.totalBytes} bytes, enc=${e.encoding}, encrypted=${e.encrypted}`);
    }
  });

  await LanTransfer.addListener('progress', e => {
    if (e.role === 'server' && e.direction === 'receive') {
      console.log(`Server receive: ${e.percent.toFixed(1)}%`);
    }
  });

  await LanTransfer.addListener('receiveComplete', e => {
    console.log('Receive complete:');
    console.log('  filePath:', e.filePath);
    console.log('  sha256:', e.sha256);
  });

  await LanTransfer.addListener('error', e => {
    console.error('Server error:', e);
  });

  return info; // { ip, port }
}
```

Stop server:

```ts
await LanTransfer.stopServer();
```

---

### B) Client (sender: connect → send → done)

```ts
import { LanTransfer } from 'capacitor-lan-transfer';
import { sendPayloadJson } from './sendPayloadJson';

export async function sendToServer(serverIp: string, serverPort: number, payload: unknown) {
  await LanTransfer.initialize({
    role: 'client',
    host: serverIp,
    serverPort,

    // encryption optional:
    // encryption: { enabled: true, passphrase: 'shared-secret' }
  });

  await LanTransfer.addListener('status', e => {
    if (e.status === 'send_started') {
      console.log(`Send started: ${e.totalBytes} bytes, enc=${e.encoding}, encrypted=${e.encrypted}`);
    }
    if (e.status === 'send_complete') {
      console.log('Send complete sha256:', e.sha256);
    }
  });

  await LanTransfer.addListener('progress', e => {
    if (e.role === 'client' && e.direction === 'send') {
      console.log(`Client send: ${e.percent.toFixed(1)}%`);
    }
  });

  await LanTransfer.addListener('error', e => {
    console.error('Client error:', e);
  });

  // connect → send → done
  await sendPayloadJson(payload);

  await LanTransfer.disconnect();
}
```

---

## Optional AES‑GCM encryption (AES‑256‑GCM)

Encryption is optional. If omitted, transfers are plaintext.

### Server (decrypt incoming; can also require encryption)

```ts
await LanTransfer.initialize({
  role: 'server',
  port: 0,
  receiveMode: 'file',
  encryption: {
    enabled: true,
    passphrase: 'your-passphrase',
    iterations: 100000,
    chunkSize: 65536,
    requireEncryption: false // default false; set true to reject plaintext
  }
});
```

### Client (encrypt outgoing)

```ts
await LanTransfer.initialize({
  role: 'client',
  host: '192.168.1.50',
  serverPort: 51515,
  encryption: {
    enabled: true,
    passphrase: 'your-passphrase'
  }
});

await LanTransfer.sendFile({ path: 'file://...' });
```

**Notes:**

- `progress` and `sha256` are computed on **PLAINTEXT** bytes.
- If server has `requireEncryption: true`, plaintext sends will be rejected.

---

## Build & run (host app)

### Android build & run

```bash
npx cap sync android
npx cap open android
```

### iOS build & run

```bash
npx cap sync ios
npx cap open ios
```

---

## Troubleshooting

### iOS: cannot connect / no LAN prompt

- Ensure `NSLocalNetworkUsageDescription` exists in `Info.plist`.
- Ensure both devices are on the same Wi‑Fi.
- Some networks block device-to-device traffic (AP isolation).

### Android: connection refused

- Verify server IP/port printed by the server.
- Ensure both devices are on same Wi‑Fi subnet.
- Ensure you're not trying to use a cellular IP.

### Progress stuck / incorrect

- Progress relies on knowing the **total plaintext bytes**.
- For Android `content://...` URIs, size must be detectable (metadata/descriptor). For your recommended flow (Filesystem `file://...`), this is not an issue.

---

## Notes / limitations

- Designed for **one transfer per connection** (connect → send → done).
- For huge payloads, prefer `receiveMode: 'file'`.
- iOS servers should run in foreground for best App Store compatibility.
