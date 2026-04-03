# DLNA Feature Update (Branch: DLNA)

## Muc tieu
Bo sung full flow DLNA casting cho Android:
- Discover DLNA renderer tren cung mang LAN
- Chon media tu dien thoai
- Tao local HTTP URL bang NanoHTTPD
- Gui lenh AVTransport Play/Stop va lay Position qua Cling UPnP

## Tinh nang da co
1. Discover renderer DLNA trong LAN
2. Chon TV/renderer tren UI
3. Pick media (video/audio) tu may
4. Cast media len TV bang URL noi bo
5. Stop playback
6. Lay vi tri playback (Position)

## File thay doi chinh
- app/build.gradle.kts
- app/src/main/AndroidManifest.xml
- app/src/main/java/com/uniremote/dlna/MainActivity.kt
- app/src/main/java/com/uniremote/dlna/dlna/DlnaManager.kt
- app/src/main/java/com/uniremote/dlna/dlna/DlnaRenderer.kt
- app/src/main/java/com/uniremote/dlna/dlna/DlnaUpnpService.kt
- app/src/main/java/com/uniremote/dlna/dlna/NanoHttpMediaServer.kt
- app/src/main/res/layout/activity_main.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/values/themes.xml

## Luu y cho team
- Dien thoai va TV phai o cung Wi-Fi/LAN.
- App can quyen network va multicast de discover DLNA.
- Neu TV khong nhan media URL, kiem tra IP local cua phone va firewall/router.

## Cach lay code ve may team
```bash
git fetch origin
git checkout DLNA
git pull origin DLNA
```

## Huong mo rong tiep
- Tach CastManager interface de gom DLNA + Google Cast + Mirror trong cung mot router.
- Them retry logic va trang thai playback realtime cho UI.
