# Transport Security Compatibility Scope

This project enforces strict transport defaults for release builds.

## Production Defaults

In `app/build.gradle.kts`:

- `ENABLE_INSECURE_DEVICE_PROTOCOLS=false`
- `ENABLE_INSECURE_DLNA_CASTING=false`

Debug builds set both flags to `true` for local device interoperability testing.

## Network Security Config Alignment

- `app/src/main/res/xml/network_security_config.xml` denies cleartext by default (`cleartextTrafficPermitted="false"`).
- `app/src/debug/res/xml/network_security_config.xml` enables cleartext only for debug interoperability.
- Runtime policy (`TransportSecurityPolicy`) still blocks cleartext destinations outside LAN/loopback for allowed flows.

## Release Decision (2026-04-13)

- Decision: keep LAN cleartext DLNA disabled in release builds.
- Status: accepted.
- Rationale: this preserves a predictable production baseline where transport security, build flags, and runtime policy all align.
- Compatibility trade-off: some legacy DLNA renderers that only support plaintext HTTP control/media may not work in release.
- Future exception path (if product requirement changes): ship a dedicated compatibility variant with explicit sign-off, clear user disclosure, and LAN-only runtime enforcement.

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
- Mirroring service accepts credentials from `Authorization: Bearer ...` header only.
- Notifications/logs must not include raw token values.
