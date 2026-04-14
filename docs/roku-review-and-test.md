# Roku Branch Review - Changes and Test Guide

## Branch
- Branch name: Roku
- Repository: https://github.com/DarkKing335/UniRemote

## Scope
This branch adds Roku-focused discovery, control stability, power fallback behavior, and regression tests without changing UI/UX.

## Main Changes

### 1) Roku Discovery over SSDP + XML
- Added: `app/src/main/java/com/example/uniremote/network/RokuSsdpDiscovery.kt`
- Features:
  - Sends SSDP `M-SEARCH` with `ST: roku:ecp`
  - Parses SSDP headers and `LOCATION`
  - Fetches Roku device description XML
  - Extracts friendly name/device metadata
  - Builds Roku `TvDevice`

### 2) LAN-only Security Filtering
- Manual IP fallback accepts only private LAN IPv4:
  - `192.168.x.x`
  - `10.x.x.x`
  - `172.16.x.x - 172.31.x.x`
- SSDP discovery path now filters to LAN IP before adding device to control list.

### 3) Merge Discovery Pipelines
- Updated: `app/src/main/java/com/example/uniremote/network/RemoteControlDiscovery.kt`
- Merged NSD discovery + Roku SSDP discovery.
- Prefers Roku SSDP identity for Roku IP endpoints.

### 4) Roku ECP Control Hardening
- Updated: `app/src/main/java/com/example/uniremote/network/RokuController.kt`
- Added safe retry for idempotent GET endpoints:
  - `/query/device-info`
  - `/query/apps`
- Kept no-retry behavior for keypress POST to avoid duplicate key presses.

### 5) Manual IP + Wake Fallback Behavior
- Updated:
  - `app/src/main/java/com/example/uniremote/viewmodel/ConnectionViewModel.kt`
  - `app/src/main/java/com/example/uniremote/viewmodel/RemoteViewModel.kt`
- Added manual Roku IP connect API.
- Roku wake chain behavior:
  1. Try WoL (if MAC is available)
  2. Retry ECP reachability
  3. Send Home key when reachable

### 6) Regression Tests
- Added: `app/src/test/java/com/example/uniremote/network/RokuSsdpDiscoveryTest.kt`
- Coverage:
  - LAN/private IP allowlist logic
  - Public IP rejection for manual fallback
  - SSDP header parsing
  - Roku XML parsing and MAC normalization
  - IP selection logic between parsed URL IP and sender IP

## Validation Performed

### Unit Test
```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.uniremote.network.RokuSsdpDiscoveryTest"
```
Expected: BUILD SUCCESSFUL

### Kotlin Compile
```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

## Manual Smoke Test (Optional)
1. Ensure phone and TV are on same LAN.
2. Trigger scan from app.
3. Verify Roku appears from discovery list.
4. Connect and send keys: Home, Up/Down, Play/Pause.
5. Launch app from Roku app list.
6. Optional: test manual IP connect with:
   - valid LAN IP -> should connect path
   - public IP (e.g. 8.8.8.8) -> should be rejected

## Notes
- This branch intentionally does not modify UI screens/components.
- Existing non-Roku branch changes remain untouched unless required for compilation/integration.
