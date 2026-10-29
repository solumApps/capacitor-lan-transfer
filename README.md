# capacitor-lan-transfer

Capacitor (Android + iOS) plugin for **large LAN TCP transfers** with:

- **Server/client roles**
- **connect → send → done** workflow (one transfer per connection; reconnect for the next)
- Streaming transfers with **progress** events
- Optional **AES‑256‑GCM encryption** (PBKDF2 key derivation, streaming chunks)
- **SHA‑256 integrity hash** of **PLAINTEXT** on sender and receiver
- **Layered discovery** (since 0.2.0): mDNS/Bonjour primary + **UDP broadcast fallback**
  (works on phone hotspots and APs that filter multicast) + manual IP as last resort
- **Interface enumeration** (`getNetworkInfo`) that works in **hotspot/AP mode**
  (never relies on `WifiManager.getConnectionInfo`, which returns 0.0.0.0 in AP mode)
- **TCP reachability probe** (`checkReachable`) to tell "wrong IP / offline" apart
  from "AP blocks device‑to‑device traffic (client isolation)"

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

In your host app `ios/App/App/Info.plist` add **both** keys (required on iOS 14+
for any local-network traffic AND for Bonjour browse/advertise):

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>This app discovers and transfers content to displays on your local network.</string>
<key>NSBonjourServices</key>
<array>
  <string>_newtontouch._tcp</string>
</array>
```

> The Bonjour service type must match what the plugin uses: `_newtontouch._tcp`.
> Without `NSBonjourServices`, `startDiscovery()`/`advertise()` silently find nothing on iOS 14+.

Then:

```bash
npx cap sync ios
```

### Android

The plugin's library manifest declares (merged into your app automatically):

- `android.permission.INTERNET`
- `android.permission.ACCESS_NETWORK_STATE`
- `android.permission.ACCESS_WIFI_STATE` (MulticastLock)
- `android.permission.CHANGE_WIFI_MULTICAST_STATE` (required to RECEIVE mDNS/UDP multicast)
- `android.permission.NEARBY_WIFI_DEVICES` with `neverForLocation` (Android 13+ NSD)

On Android 13+ the plugin requests `NEARBY_WIFI_DEVICES` at runtime the first time
`startDiscovery()` is called. If the user denies it, mDNS discovery yields nothing
but the **UDP broadcast fallback and manual IP connect still work**.

Make sure to apply these changes to the root build.gradle file of the android folder:

- ` buildscript { ext { kotlin_version = '1.9.24'} } `
- ` buildscript { dependencies { classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version" } } `

No runtime prompt is needed for basic LAN TCP.

---

## Discovery (since 0.2.0)

Discovery is **layered** so it works across AP types:

1. **mDNS / NSD / Bonjour** (primary) — service type `_newtontouch._tcp.`, TXT records included.
2. **UDP broadcast probe** (fallback, automatic) — the advertising side listens on UDP
   `53517`; the scanning side broadcasts `NTLT1?` to `255.255.255.255` **and** every
   subnet‑directed broadcast address every 2 s; the advertiser replies unicast with
   `NTLT1!{json}` containing its name, the IP **on the prober's subnet**, port and TXT
   data. This path works on **phone hotspots** and APs that filter multicast.
3. **Manual IP** — `getNetworkInfo()` on the receiver lists every local IPv4 to show on
   a pairing screen; the sender connects directly with `connect({ host, port })`.

```ts
// Receiver (advertise) — server binds 0.0.0.0, so one server covers all interfaces:
await LanTransfer.startServer({ port: 8082 });
await LanTransfer.advertise({ name: 'Newton-LCD-01', port: 8082, txt: { deviceName: 'Lobby' } });
const net = await LanTransfer.getNetworkInfo();   // every non-loopback IPv4 (incl. ap0/swlan0/bridge100)

// Sender (scan):
await LanTransfer.addListener('deviceFound', d => console.log(d.name, d.host, d.port, d.via)); // via: 'mdns' | 'udp'
await LanTransfer.addListener('deviceLost', d => console.log('lost', d.name));
await LanTransfer.startDiscovery();
// later: await LanTransfer.stopDiscovery(); await LanTransfer.stopAdvertise();

// Sender (diagnose a failed connect):
const r = await LanTransfer.checkReachable({ host: '192.168.43.17', port: 8082, timeoutMs: 2500 });
```

### No‑AP / hotspot mode

When there is no usable Wi‑Fi AP: enable the **phone hotspot** and join the
receiver (LCD) to it. mDNS is often blocked on hotspots, but the **UDP fallback is
not** — the receiver appears in the scan with `via: 'udp'`. Manual IP also works:
the receiver's hotspot address (typically `192.168.43.x` / `172.20.10.x`) is listed
by `getNetworkInfo()`. The transfer server binds `0.0.0.0`, so no extra setup is
needed on either side.

---

## API overview

### initialize(options)

- `role: 'server'` starts server and returns `{ ip, port }`
- `role: 'client'` connects to `{ host, serverPort }` and returns connection info

### Sending

- `sendString({ text, host?, port? })`
- `sendBase64({ base64, host?, port? })`
- `sendFile({ path, host?, port? })`

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
- `send_complete` (the receiver confirmed the payload; includes `sha256`)

### Delivery and connection reuse

After each payload the receiver writes one byte: `0x06` (ACK — written and
`receiveComplete` emitted) or `0x15` (NAK — rejected/failed). `sendString` /
`sendBase64` / `sendFile` resolve on the ACK, so a resolved send means
*delivered*, not just buffered. The connection then stays open: calling
`connect()` again with the same host/port reuses it (no new TCP handshake per
payload), which is what makes many small files fast on a weak Wi-Fi link. An idle
connection is closed by the receiver after 60 s and reopened by the next
`connect()`. Call `disconnect()` when done.

Older receivers send no reply and close after one payload: the client treats that
close as delivered and reconnects for the next payload, so mixed versions work.
Progress events are throttled by time (about 5 per second plus the final one).

### Several receivers at once

Every `host:port` gets its own connection and worker, so sends to different
receivers run in parallel (deploying to 3 displays takes about as long as 1).
Name the receiver on each send — `sendString({ text, host, port })`,
`sendBase64({ base64, host, port })`, `sendFile({ path, host, port })` — after a
`connect({ host, port })` to it. A send without `host` uses the most recent
`connect()` (single-receiver code keeps working); one that names a receiver
nobody connected to rejects with `NOT_CONNECTED`. Client `progress`, `status`
and `error` events carry `remoteHost` / `remotePort`. `disconnect({ host, port })`
closes one connection, `disconnect()` all of them.

For a big file going to several receivers, write it to disk once and `sendFile`
it to each: `sendBase64` would carry a full copy through the native bridge (and
decode it) for every receiver.

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

- Ensure `NSLocalNetworkUsageDescription` **and** `NSBonjourServices` (`_newtontouch._tcp`) exist in `Info.plist`.
- Ensure both devices are on the same Wi‑Fi.
- Some networks block device-to-device traffic (AP isolation) — see below.

### Android: connection refused

- Verify server IP/port printed by the server.
- Ensure both devices are on same Wi‑Fi subnet.
- Ensure you're not trying to use a cellular IP.

### Nothing found while scanning

- Many APs (guest networks, enterprise Wi‑Fi, hotspots) filter multicast → mDNS is dead,
  but the UDP fallback should still find devices (`via: 'udp'`). If even UDP finds
  nothing, the AP probably has **client isolation** — use `checkReachable` to confirm:
  if the gateway answers but the peer doesn't, isolation is the cause. Workaround:
  **phone hotspot** (enable on the phone, join the display to it), or manual IP on a
  network that allows peer traffic.
- Android 13+: make sure the `NEARBY_WIFI_DEVICES` runtime permission wasn't denied
  (Settings → Apps → your app → Permissions → Nearby devices).

### Progress stuck / incorrect

- Progress relies on knowing the **total plaintext bytes**.
- For Android `content://...` URIs, size must be detectable (metadata/descriptor). For your recommended flow (Filesystem `file://...`), this is not an issue.

---

## Notes / limitations

- Designed for **one transfer per connection** (connect → send → done).
- For huge payloads, prefer `receiveMode: 'file'`.
- iOS servers should run in foreground for best App Store compatibility.
