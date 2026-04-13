# Release Real-Device Validation Execution Log

Date: 2026-04-13
Workspace: E:/App/UniRemote
Checklist Source: docs/release-real-device-validation.md

## Execution Summary

- Automated release verification: PASS
- Physical-device execution: PARTIAL (1 connected device validated)
- Ship gate decision: PENDING (full matrix requires 3 target classes)

## Evidence Collected

### 1) Build Artifact Verification

- app/build/outputs/apk/release/app-release-unsigned.apk: PRESENT
- app/build/outputs/apk/release/output-metadata.json: PRESENT
- app/build/outputs/mapping/release/mapping.txt: PRESENT
- Unit test results directory: app/build/test-results/testDebugUnitTest (PRESENT)

### 2) Full Rebuild + Tests

Executed command:

- ./gradlew --rerun-tasks :app:testDebugUnitTest :app:assembleRelease

Observed result:

- BUILD SUCCESSFUL
- 74 actionable tasks: 74 executed
- Test XML aggregate: FILES=16 TESTS=60 FAILURES=0 ERRORS=0 SKIPPED=0

### 3) Signing Readiness

Executed command:

- ./gradlew :app:signingReport

Observed result:

- Variant debug: configured
- Variant release: Config=null, Store=null, Alias=null

Status:

- RELEASE SIGNING NOT CONFIGURED in Gradle for this workspace.
- Internal signing completed using debug keystore for non-Play internal validation:
  - Output: app/build/outputs/apk/release/app-release-internal-debugsigned.apk
  - Signer DN: C=US, O=Android, CN=Android Debug
  - SHA-256: 4afc944ad165dc4d0c88aea4c57aaa08d273b8453820880e64806345ace563aa

### 4) Device Connectivity Readiness

Executed command:

- adb detection via PATH lookup
- local SDK adb direct path check

Observed result:

- ADB_FOUND=0
- ADB_LOCAL_FOUND=1
- Connected devices: 1 (wireless adb target)

Status:

- Device-side execution is possible using local SDK adb path.

### 5) On-Device Execution Snapshot

Executed commands:

- adb install -r app/build/outputs/apk/release/app-release-internal-debugsigned.apk
- adb shell monkey -p com.example.uniremote -c android.intent.category.LAUNCHER 1
- adb logcat -d token scan

Observed result:

- Install status: Success
- Package verification: package:com.example.uniremote
- Log leakage scan: LOG_HIT_COUNT=0 for pattern token|Bearer|Authorization

## Checklist Execution Status

### Preflight

1. Sign release APK with production keystore: BLOCKED
   - Reason: release signing config is null in Gradle.
   - Interim for internal testing: completed with debug keystore signed artifact.
2. Install on at least 3 physical targets: PARTIAL
   - Current: installed and verified on 1 connected device.
3. Same LAN SSID setup: NOT EXECUTED
4. Disable developer-only toggles in release run: NOT EXECUTED

### Validation Matrix

1. Connection reliability (IP change reconnect): NOT EXECUTED (physical test required)
2. Controller fallback correctness: NOT EXECUTED (physical/simulated device test required)
3. Discovery timeout behavior: NOT EXECUTED (physical runtime observation required)
4. Security policy enforcement: PARTIAL
   - Static/build validation done in code and tests.
   - Device log leakage spot-check executed (no hits for token/Bearer/Authorization).
   - Mirroring-notification verification NOT EXECUTED.

## Required Next Actions to Unblock Ship Gate

1. Configure release signing:
   - Add a release signing config (keystore file, alias, passwords) via local secrets or CI.
2. Add platform-tools adb to PATH for standard command workflow.
3. Connect at least 2 more target classes and execute all steps in docs/release-real-device-validation.md.
4. Record PASS/FAIL per matrix item and keep logs/screenshots for traceability.

## Final Gate

- Connection reliability: PENDING
- Controller fallback correctness: PENDING
- Discovery timeout lifecycle: PENDING
- Security policy enforcement: PARTIAL (log scan done, mirroring check pending)
- No blocker/crash in 30-minute smoke session: PENDING

Final production ship approval for physical-device gate: NOT YET APPROVED
