# Roku Remote Module Design + Integration (UniRemote)

## 1) System Architecture Diagram (text-based)

```text
+---------------------------- UniRemote App -----------------------------+
|                                                                       |
|  [RemoteViewModel]                                                    |
|      |                                                                |
|      +--> [DiscoveryCoordinator]                                      |
|      |        |                                                       |
|      |        +--> [RemoteControlDiscovery]                           |
|      |                 |                                              |
|      |                 +--> [NsdDeviceDiscoveryEngine] (mDNS/NSD)     |
|      |                 +--> [RokuSsdpDiscovery] (SSDP + XML parse)    |
|      |                                                                |
|      +--> [ConnectionViewModel]                                       |
|      |        |                                                       |
|      |        +--> [DeviceConnectionManager]                          |
|      |                 |                                              |
|      |                 +--> [RokuController] (ECP HTTP client)        |
|      |                 +--> [Other TV Controllers...]                 |
|      |                                                                |
|      +--> [DeviceRepository + AppPreferences]                         |
|               |                                                       |
|               +--> cache known devices, online/offline, last TV       |
|                                                                       |
+-----------------------------------------------------------------------+
                               |
                               | LAN only (private IP)
                               v
+----------------------------- Roku TV ----------------------------------+
| SSDP/UPnP discovery  | ECP HTTP API                                  |
| M-SEARCH             | /query/device-info                            |
| device-description   | /query/apps                                   |
| XML                  | /keypress/{key}, /launch/{appId}              |
+-----------------------------------------------------------------------+
```

## 2) Module Breakdown

### Discovery Module
- `RokuSsdpDiscovery`
- Sends SSDP M-SEARCH with `ST: roku:ecp`
- Reads `LOCATION` from SSDP response
- Downloads XML device description
- Extracts:
  - IP
  - friendly name
  - device type/model
  - MAC (if present in XML)
- Exposes helper for manual IP fallback:
  - `buildManualRokuDevice(ip, name)`

### Control Module (ECP)
- `RokuController`
- Core APIs:
  - `POST /keypress/{key}`
  - `POST /launch/{appId}`
  - `GET /query/apps`
  - `GET /query/device-info`
- Keypress POST uses no retry to avoid double-press behavior
- Idempotent GET endpoints use bounded retry with short backoff

### Power Module
- Wake chain for Roku in `ConnectionViewModel.wakeTV()`:
  1. WoL magic packet (if MAC exists)
  2. Retry ECP reachability (`tryConnectSilently`/`connectTo`)
  3. Send `Home` key if reachable
- Handles Roku standby limitation gracefully (deep sleep may not wake)

## 3) API Design (Endpoints + payloads)

### Discovery-facing (internal app API)
- `RemoteControlDiscovery.discover(): Flow<List<TvDevice>>`
- Merges NSD + Roku SSDP discovery streams

### Manual fallback API (internal app API)
- `RemoteViewModel.connectToManualRokuIp(ip: String, name: String)`
- Validation: private LAN IPv4 only

### ECP HTTP (Roku side)
- `GET /query/device-info`
  - Returns XML device metadata
- `GET /query/apps`
  - Returns installed apps XML list
- `POST /keypress/Home` (and other keys)
  - Fire-and-forget command dispatch
- `POST /launch/{appId}`
  - Launch app by Roku app id

## 4) Sample Code

### Node.js (Express + SSDP + ECP)

```js
// minimal sample, LAN-only
const express = require('express');
const dgram = require('dgram');
const fetch = require('node-fetch');
const { XMLParser } = require('fast-xml-parser');

const app = express();
app.use(express.json());

function discoverRoku(timeoutMs = 3000) {
  return new Promise((resolve) => {
    const socket = dgram.createSocket('udp4');
    const devices = new Map();
    const msg = Buffer.from(
      'M-SEARCH * HTTP/1.1\r\n' +
      'HOST: 239.255.255.250:1900\r\n' +
      'MAN: "ssdp:discover"\r\n' +
      'MX: 2\r\n' +
      'ST: roku:ecp\r\n\r\n'
    );

    socket.on('message', async (buf, rinfo) => {
      const text = buf.toString('utf8');
      const lines = text.split(/\r?\n/);
      const headers = {};
      for (const line of lines) {
        const i = line.indexOf(':');
        if (i > 0) headers[line.slice(0, i).trim().toLowerCase()] = line.slice(i + 1).trim();
      }
      const location = headers.location;
      if (!location || devices.has(location)) return;

      try {
        const xml = await (await fetch(location, { timeout: 2000 })).text();
        const parser = new XMLParser({ ignoreAttributes: false });
        const parsed = parser.parse(xml);
        const dev = parsed?.root?.device || {};
        devices.set(location, {
          ip: new URL(location).hostname || rinfo.address,
          name: dev.friendlyName || 'Roku TV',
          deviceType: dev.deviceType || 'roku:ecp'
        });
      } catch (_) {}
    });

    socket.bind(() => {
      socket.setBroadcast(true);
      socket.send(msg, 0, msg.length, 1900, '239.255.255.250');
    });

    setTimeout(() => {
      socket.close();
      resolve(Array.from(devices.values()));
    }, timeoutMs);
  });
}

app.get('/roku/discover', async (_req, res) => {
  res.json({ devices: await discoverRoku() });
});

app.post('/roku/:ip/keypress/:key', async (req, res) => {
  const { ip, key } = req.params;
  const r = await fetch(`http://${ip}:8060/keypress/${encodeURIComponent(key)}`, { method: 'POST' });
  res.status(r.ok ? 200 : 502).json({ ok: r.ok });
});

app.post('/roku/:ip/launch/:appId', async (req, res) => {
  const { ip, appId } = req.params;
  const r = await fetch(`http://${ip}:8060/launch/${encodeURIComponent(appId)}`, { method: 'POST' });
  res.status(r.ok ? 200 : 502).json({ ok: r.ok });
});

app.get('/roku/:ip/apps', async (req, res) => {
  const xml = await (await fetch(`http://${req.params.ip}:8060/query/apps`)).text();
  res.type('application/xml').send(xml);
});

app.get('/roku/:ip/device-info', async (req, res) => {
  const xml = await (await fetch(`http://${req.params.ip}:8060/query/device-info`)).text();
  res.type('application/xml').send(xml);
});

app.listen(3000, () => console.log('Roku LAN gateway listening on :3000'));
```

### Android (HTTP client example)

```kotlin
suspend fun sendRokuHome(ip: String): Boolean = withContext(Dispatchers.IO) {
    val req = Request.Builder()
        .url("http://$ip:8060/keypress/Home")
        .post("".toRequestBody())
        .build()
    runCatching {
        NetworkClient.instance.newCall(req).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}
```

## 5) Mock Server (test without real Roku)

```js
// quick Roku ECP mock
const express = require('express');
const app = express();

app.use(express.text({ type: '*/*' }));

app.get('/query/device-info', (_req, res) => {
  res.type('application/xml').send(`
<device-info>
  <friendly-device-name>Mock Roku TV</friendly-device-name>
  <model-name>MockModel</model-name>
  <power-mode>PowerOn</power-mode>
</device-info>`);
});

app.get('/query/apps', (_req, res) => {
  res.type('application/xml').send(`
<apps>
  <app id="12">Netflix</app>
  <app id="837">YouTube</app>
</apps>`);
});

app.post('/keypress/:key', (req, res) => {
  console.log('keypress', req.params.key);
  res.status(200).send('OK');
});

app.post('/launch/:appId', (req, res) => {
  console.log('launch', req.params.appId);
  res.status(200).send('OK');
});

app.listen(8060, () => console.log('Mock Roku ECP on :8060'));
```

## 6) Step-by-step integration in existing app

1. Add Roku SSDP discovery class and XML parser.
2. Merge discovery output (NSD + Roku SSDP) inside `RemoteControlDiscovery`.
3. Keep existing UI intact; discovery list gets Roku devices automatically.
4. Keep ECP control in `RokuController` with retry for GET only.
5. Add manual IP API in ViewModel for fallback integration paths.
6. Add Roku-specific wake fallback chain in `ConnectionViewModel`.
7. Validate by compiling and testing on LAN.

## Scalability direction (DLNA/Chromecast future)
- Keep protocol adapters isolated (`*Controller`, `*Discovery`)
- Keep transport-agnostic orchestration in `DeviceConnectionManager`
- Continue using merged discovery stream pattern per protocol
- Optional next: add unified capability model (`supportsWake`, `supportsApps`, `supportsMouse`)
