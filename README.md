# UNIVERSAL TV REMOTE - Khi Smartphone Trở Thành "Quyền Năng" Điều Khiển Giải Trí

Chào thầy và các bạn, nhóm A1 giới thiệu dự án cuối kỳ **Universal TV Remote** - ứng dụng Android giúp biến smartphone thành remote đa năng cho nhiều dòng Smart TV phổ biến hiện nay.

Ứng dụng được thiết kế theo hướng **pair một lần, dùng lâu dài**: kết nối lần đầu để xác thực, sau đó tái sử dụng credential đã lưu để vào TV nhanh và hạn chế thao tác lặp lại.

---

## Bài Toán Dự Án Giải Quyết

- Mất remote vật lý, hết pin, khó nhập văn bản trên TV.
- Mỗi hãng TV dùng protocol riêng, khó đồng bộ trải nghiệm điều khiển.
- Nhu cầu cast media và mirroring ngay trong cùng một app.

---

## Tính Năng Nổi Bật

- Tự động quét TV trong LAN qua NSD/mDNS + dedupe theo endpoint.
- Điều khiển all-in-one: D-pad, numpad, âm lượng, kênh, phím chức năng.
- Touchpad/gesture + fallback D-pad khi TV không hỗ trợ pointer protocol.
- Bàn phím ảo và nhập text từ điện thoại vào TV.
- Quick launch app (YouTube, Netflix, Prime Video, Spotify, ...).
- Wake-on-LAN (magic packet) để đánh thức TV từ standby.
- Cast media (URL/local file) và screen mirroring.
- Cơ chế fallback protocol theo thứ tự ưu tiên, trong đó ADB là phương án cuối.

---

## Kiến Trúc Protocol Strategy

Dự án không phụ thuộc IR (hồng ngoại). Thay vào đó là hệ thống strategy theo brand/protocol:

- **Samsung**: WebSocket remote API (`ws/wss`) + token.
- **LG webOS**: SSAP WebSocket + `client-key`.
- **Android TV / Google TV / Xiaomi / nhiều Sony Android TV**: Google TV Remote Protocol V2 (`6466/6467`) + PIN pairing.
- **Roku**: ECP HTTP API (`8060`) - không cần pairing token.
- **Sony Bravia đời cũ (non-Android)**: IRCC-IP SOAP/JSON + PSK token.
- **Fire TV / Android-based fallback**: ADB-over-network (`5555`) khi protocol native thất bại.

Hệ thống kết nối trung tâm nằm trong `DeviceConnectionManager`, tự động chọn controller theo brand và fallback chain.

---

## Universal TV Remote - Real-World Pairing & Connection Behavior

### 1. One-Time Pairing Principle

Với phần lớn Smart TV, pairing chỉ cần thực hiện 1 lần:

1. Kết nối lần đầu: TV hiện PIN hoặc popup cấp quyền.
2. Người dùng xác nhận trên TV/điện thoại.
3. App lưu credential theo từng thiết bị.
4. Các lần sau app tái sử dụng credential để kết nối nhanh, hạn chế yêu cầu nhập lại.

### 2. Detailed Behavior by Platform

#### Android TV / Google TV / Sony Android TV / Xiaomi (Google TV Remote Protocol V2)

- Lần đầu:
  - TV hiện PIN.
  - Người dùng nhập PIN trên app.
- Sau pairing:
  - Tạo secure session, đánh dấu đã pair.
  - Lần sau có thể kết nối nhanh qua credential đã tồn tại.
- Nếu pair hết hạn/invalid:
  - App kích hoạt luồng re-pair.

#### Samsung Smart TV (WebSocket)

- Lần đầu:
  - TV hiện popup cho phép điều khiển.
  - Người dùng chọn Allow.
- Hệ thống nhận token từ kênh WebSocket và lưu lại.
- Lần sau:
  - Gửi token đã lưu.
  - Thường không cần popup lại.

#### LG Smart TV (webOS SSAP)

- Lần đầu:
  - TV hiện yêu cầu pairing/PIN.
- TV trả về `client-key`.
- App lưu `client-key`.
- Lần sau:
  - Tái sử dụng `client-key` để kết nối nhanh.

#### Roku TV (ECP)

- Không cần pairing.
- Không cần token.
- App gọi HTTP command trực tiếp trong cùng mạng LAN.

#### Sony Bravia non-Android (IRCC-IP fallback)

- App mở đăng ký, TV hiện mã PIN.
- Người dùng nhập PIN.
- Hệ thống lấy PSK/auth cookie và lưu token.
- Các lần sau dùng token đã lưu để điều khiển.

#### Fire TV / Android fallback (ADB)

- Yêu cầu bật ADB over network trên TV.
- Lần đầu có thể cần chấp nhận fingerprint RSA.
- Dùng cho trường hợp fallback cuối khi native protocol không hoạt động.

### 3. Token Persistence System

Credential được lưu theo device ID:

- Samsung -> token
- LG -> client-key
- Sony IRCC -> PSK/auth token
- Android TV/Google TV -> trạng thái pairing + TLS material liên quan

Lưu trữ:

- Metadata thiết bị: DataStore
- Credential nhạy cảm: `EncryptedSharedPreferences` (qua `SecureCredentialStore`)

Mỗi lần reconnect:

1. Nạp credential đã lưu.
2. Inject vào request kết nối.
3. Bỏ qua pairing nếu credential vẫn hợp lệ.

### 4. Connection Experience (User Perspective)

**Lần đầu:**

1. Chọn TV
2. TV hiện PIN/popup
3. Xác nhận pairing
4. Kết nối thành công

**Lần sau:**

1. Mở app
2. Chọn lại TV
3. Kết nối nhanh hơn với credential đã lưu

### 5. Fallback Mechanism

Nếu native protocol thất bại, hệ thống thử fallback theo thứ tự ưu tiên theo brand. Với Android-family TV, ADB được dùng như phương án cuối để đảm bảo tính tương thích.

---

## Nền Tảng / Hãng TV Đã Hỗ Trợ

- Samsung
- LG
- Sony (Android TV và Bravia IRCC fallback)
- Google TV / Android TV
- Xiaomi TV
- Roku TV
- Fire TV
- Hisense / TCL / Vizio / Panasonic và một số brand khác qua chain fallback

Mức độ hỗ trợ có thể khác nhau theo firmware/phiên bản hệ điều hành của TV.

---

## Cast & Mirroring

- DLNA discovery + playback control (play/pause/seek/volume).
- Google Cast renderer support (hybrid list với DLNA renderer).
- Cast URL media và local media.
- Screen mirroring qua foreground service + media projection.
- Bảo mật stream session theo cơ chế authorization header.

Tài liệu tham khảo thêm:

- `docs/release-real-device-validation.md`
- `docs/release-real-device-validation-execution-2026-04-13.md`
- `docs/transport-security-compatibility.md`

---

## Bảo Mật & Chế Độ Release

Release build ưu tiên secure-by-default:

- `ENABLE_INSECURE_DEVICE_PROTOCOLS=false`
- `ENABLE_INSECURE_DLNA_CASTING=false`

Điều này giúp giảm rủi ro transport security trong production, nhưng có thể ảnh hưởng một số TV legacy chỉ hỗ trợ cleartext protocol.

---

## Công Nghệ Chính

- Kotlin + Jetpack Compose
- Coroutines + StateFlow
- OkHttp (WebSocket/HTTP)
- Android DataStore
- AndroidX Security Crypto (`EncryptedSharedPreferences`)
- jUPnP + NanoHTTPD (DLNA)
- Google Cast Framework
- dadb (ADB over TCP)

---

## Build & Run

Yêu cầu:

- JDK 11
- Android SDK (compile/target SDK 36)
- Android device/emulator (min SDK 24)

Lệnh thường dùng (Windows PowerShell):

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease
```

## Cau Hinh WOLRelay (tu chon)

Ung dung da ho tro WOLRelay API voi endpoint POST /wake (JSON: {"mac":"AA:BB:CC:DD:EE:FF"}).

Them URL relay vao file gradle.properties (root project):

```properties
WOL_RELAY_URL=http://your-relay-host:5000
```

Khi bam Wake TV:

- Neu co WOL_RELAY_URL: app thu goi relay truoc.
- Neu relay that bai: app tu fallback sang magic packet WoL noi bo trong LAN.

Lưu ý: release signing hiện tại có thể chưa được cấu hình sẵn trong workspace local.

APK mới sau lần build gần nhất:

- `E:\App\UniRemote\app\build\outputs\apk\debug\app-debug.apk`
- `app/build/outputs/apk/debug/app-debug.apk`

---

## Team A1

- Nguyễn Anh Bằng (Leader) - 3120223012
  - Điều phối, kiến trúc hệ thống, protocol strategy.
- Nguyễn Đức Hà Nha - 3120223134
  - UI/UX, tối ưu trải nghiệm điều khiển, Sliding Strip.
- Trần Quốc Kiệt - 3120223100
  - Kết nối, cast media, ổn định hệ thống.

---

## Kết Luận

Universal TV Remote hướng đến trải nghiệm thực tế: **pair once, use repeatedly**, kết hợp đa giao thức native và cơ chế lưu credential an toàn, từ đó giảm thao tác và tăng tốc độ kết nối trong hệ sinh thái Smart TV đa dạng.
