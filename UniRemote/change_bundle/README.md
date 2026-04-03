# Change Bundle

Folder nay gom cac file thay doi chinh cho DLNA de team review nhanh va cherry-pick neu can.

## Danh sach

### Scaffold Android project files
- settings.gradle.kts
- build.gradle.kts
- gradle.properties
- app/build.gradle.kts

### Add DLNA core managers
- app/src/main/java/com/uniremote/dlna/dlna/DlnaManager.kt
- app/src/main/java/com/uniremote/dlna/dlna/NanoHttpMediaServer.kt
- app/src/main/java/com/uniremote/dlna/dlna/DlnaUpnpService.kt
- app/src/main/java/com/uniremote/dlna/dlna/DlnaRenderer.kt

### Build UI for discover cast
- app/src/main/java/com/uniremote/dlna/MainActivity.kt
- app/src/main/res/layout/activity_main.xml

### Wire permissions manifest network
- app/src/main/AndroidManifest.xml
