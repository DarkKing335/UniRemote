# Release Real-Device Validation

Date: 2026-04-13

## Build Artifact

- Release assemble status: SUCCESS
- APK: app/build/outputs/apk/release/app-release-unsigned.apk
- Metadata: app/build/outputs/apk/release/output-metadata.json
- Proguard mapping: app/build/outputs/mapping/release/mapping.txt

## Preflight

1. Sign the release APK with production keystore (or produce signed CI artifact).
2. Install on at least 3 physical targets:
   - Android/Google TV class device
   - LG or Samsung device
   - Roku or Sony fallback path device
3. Ensure phone and TVs are on the same LAN SSID.
4. Disable developer-only toggles and use release configuration only.

## Validation Matrix

### 1) Connection Reliability (IP Change Reconnect)

Steps:

1. Pair/connect TV and confirm remote control works.
2. Reboot router or renew TV DHCP lease so TV IP changes.
3. Re-open app and reconnect via discovered device card.

Expected:

- App reconnects without re-pairing when credentials are valid.
- Updated endpoint is used and persisted.
- No stale-IP connection attempts after successful reconnect.

Fail if:

- Re-pairing is required unexpectedly.
- Connection keeps using old IP.
- Connection state shows connected while commands fail.

### 2) Controller Fallback Correctness

Steps:

1. Test Panasonic and Hisense branded devices (or simulated discovery entries).
2. Attempt connect and run basic controls (D-pad, home, volume).
3. Test UNKNOWN-brand path with multiple protocol opportunities.

Expected:

- Stub controllers are not selected as successful primary controllers.
- Fallback chain selects a functional controller in priority order.
- Connection status reflects actual command capability.

Fail if:

- Stub/unsupported controller reports connected.
- Fallback order is bypassed unexpectedly.
- UI reports connected but command dispatch fails immediately.

### 3) Discovery Timeout Behavior

Steps:

1. Start scan on a network with no discoverable TVs.
2. Observe scan lifecycle for at least 12 seconds.
3. Repeat scan while rapidly entering/leaving discovery screen.

Expected:

- Scan finalizes cleanly around configured timeout window.
- No runaway discovery jobs.
- Final state marks scan complete and remains stable.

Fail if:

- Scan never finalizes.
- Duplicate/stacked discovery jobs appear.
- Offline/online flicker persists after timeout.

### 4) Security Policy Enforcement

Steps:

1. Attempt plaintext-only paths in release build.
2. Attempt non-LAN cleartext target URL where possible.
3. Start mirroring and inspect notification text and logs.

Expected:

- Release blocks insecure compatibility protocols by default.
- Cleartext traffic is denied by default and only allowed where runtime LAN policy permits.
- No auth token appears in URL, notification, or logs.
- Mirroring uses Authorization header workflow (no URL token query).

Fail if:

- Plaintext fallback succeeds in release unexpectedly.
- Non-LAN cleartext is accepted.
- Any token material is visible in logs, URLs, or notification content.

## Quick Verification Commands

```powershell
# Install signed artifact
adb install -r <signed-release-apk>

# Verify no token leakage in logs during pairing/mirroring sessions
adb logcat -d | Select-String -Pattern "token|Bearer|Authorization" -CaseSensitive

# Optional: clear logs before run
adb logcat -c
```

## Ship Gate (All Must Pass)

- Connection reliability: PASS
- Controller fallback correctness: PASS
- Discovery timeout lifecycle: PASS
- Security policy enforcement: PASS
- No blocker/crash in 30-minute smoke session: PASS

If any item fails, do not ship and open a blocking defect with repro steps, logs, and affected device model/firmware.
