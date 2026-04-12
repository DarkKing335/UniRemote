# Transport Security Compatibility Scope

This project enforces strict transport defaults for release builds.

## Production Defaults

In `app/build.gradle.kts`:

- `ENABLE_INSECURE_DEVICE_PROTOCOLS=false`
- `ENABLE_INSECURE_DLNA_CASTING=false`

Debug builds set both flags to `true` for local device interoperability testing.

## Protocol Scope

The following protocols are plaintext by vendor or ecosystem limitation and are now behind compatibility mode:

- LG WebOS SSAP over `ws://`
- Roku ECP over `http://`
- Samsung non-TLS remote API (`ws://` and `http://` on legacy ports)
- Sony Bravia IRCC-IP fallback over `http://` when `https://` is unavailable
- DLNA local media serving over `http://`

## Secure-First Behavior

- Samsung uses secure transport (`wss://` / `https://`) when the TV endpoint is TLS-capable.
- Sony attempts `https://` first and only uses `http://` when compatibility mode is enabled.

## URL Credential Policy

- URL query token authentication has been removed from Samsung WebSocket connect flow.
- DLNA media URLs no longer embed per-session token credentials.
- Authentication material must not be carried in URL query parameters.
