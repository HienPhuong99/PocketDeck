# Báo cáo Kỹ thuật, Công cụ Đã Cài đặt & Kiểm định Chất lượng (PocketDeck)

## 1. Tổng quan Dự án
**PocketDeck** được xây dựng theo yêu cầu phát triển một giải pháp tương tự [PocketPane](https://github.com/HienPhuong99/pocketpane) hoạt động trọn vẹn cho hệ sinh thái **Android** và **Windows**, bao gồm **cả 2 phiên bản (`PocketDeck.exe` trên Windows và `PocketDeck-debug.apk` trên Android)** cùng hệ thống **Truyền File 2 ngăn + Chat trực tiếp kiểu UltraViewer**.

---

## 2. Danh mục Đầy đủ Mọi Repo / MCP / SDK / Gói Thư viện Đã Cài Đặt

Theo đúng yêu cầu báo cáo mọi công cụ đã cài đặt để làm việc độc lập 100%:

1. **`morluto/rea` (`rea-agents@6.7.0`)**:
   - Đã cài đặt toàn cục qua `npm install --global rea-agents` (`https://github.com/morluto/rea`).
   - Đã đăng ký MCP server `rea` vào cấu hình `~/.gemini/config/mcp_config.json` và tích hợp skill `reverse-engineer-anything` tại `~/.gemini/config/skills/reverse-engineer-anything/SKILL.md`.
2. **Môi trường ảo Python 3.13 (`~/.pocketdeck-tools/venv`)**:
   - `PySide6 6.12.0` (Giao diện Qt6 hiện đại cho Windows)
   - `av 19.0.2` (PyAV / FFmpeg giải mã H.264/AAC/Opus & đóng gói Fragmented MP4)
   - `numpy 2.5.4`, `sounddevice 0.5.6` (Xử lý khung hình & âm thanh độ trễ thấp)
   - `cryptography 50.0.2` (Mã hoá phiên `AES-256-GCM`, `HMAC-SHA256`, `HKDF`)
   - `zeroconf 0.154.0` (Quảng bá & khám phá dịch vụ mDNS `_pocketdeck._tcp.local.`)
   - `qrcode 8.2`, `Pillow 13.0.0` (Sinh mã QR ghép nối & biểu tượng đa độ phân giải `.ico`)
   - `pytest 9.2.0` (Khung kiểm thử tự động)
   - `pyinstaller 6.22.3` (Đóng gói bản chạy độc lập `PocketDeck.exe`)
3. **Eclipse Temurin OpenJDK 17 Portable (`~/.pocketdeck-tools/jdk-17`, `17.0.21+9`)**:
   - Phục vụ biên dịch Kotlin, Jetpack Compose và chạy kiểm thử JUnit / cầu nối JVM độc lập.
4. **Android SDK (`~/.pocketdeck-tools/android-sdk`)**:
   - `cmdline-tools;latest`, `platform-tools` (`r37.0.1`), `platforms;android-35`, `build-tools;35.0.0`, `build-tools;34.0.0` (kèm `apksigner` kiểm định chữ ký số APK).
5. **Gradle 8.10.2 Portable (`~/.pocketdeck-tools/gradle-8.10.2`) & Gradle Wrapper (`android/gradlew`)**:
   - Khởi tạo chuẩn Gradle Wrapper chính thức trong kho mã nguồn để mọi máy khác có thể build ngay không cần cài đặt Gradle thủ công.
6. **Genymobile/scrcpy v5.0.1 Windows x64 (`~/.pocketdeck-tools/scrcpy`)**:
   - Cung cấp `adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll` và `scrcpy-server` (Apache-2.0) cho chế độ kết nối không cần cài app trước và được nhúng tự động vào `PocketDeck.exe`.

---

## 3. Điểm nhấn Kỹ thuật & Kiến trúc Bảo mật

1. **Giao thức ghép kênh DeckLink v1 (`windows/pocketdeck/protocol/` & `android/.../protocol/`)**:
   - Ghép 8 kênh logic (`CH_AUTH`, `CH_CONTROL`, `CH_VIDEO`, `CH_AUDIO`, `CH_FILE_CTRL`, `CH_FILE_DATA`, `CH_CHAT`, `CH_CLIPBOARD`) trên một kết nối TCP duy nhất (mặc định cổng `7200`).
   - Xác thực thách thức - phản hồi hai chiều (`Mutual HMAC-SHA256`) bằng mật khẩu 6 số mà không bao giờ truyền mật khẩu dạng rõ qua mạng; khoá tạm 60 giây sau 5 lần nhập sai liên tiếp (`AuthRateLimiter`).
   - Toàn bộ gói tin sau bước bắt tay đều được mã hoá `AES-256-GCM` với vector khởi tạo phân tách chiều gửi/nhận (`C2S\0` / `S2C\0` + số thứ tự 64-bit) và gắn chặt header 12 byte vào `AAD` chống giả mạo hoặc phát lại gói tin.
2. **Engine Truyền File 2 chiều kiểu UltraViewer (`FileTransferManager` & `FileTransferEngine`)**:
   - Cắt khối `256 KiB`, truyền song song cùng luồng hình ảnh mà không làm đứng khung hình nhờ cơ chế điều tiết luồng.
   - Ghi vào file tạm `.pocketdeck.part`, tự động nối tiếp từ offset dở dang khi kết nối lại, và đối chiếu mã băm toàn file `SHA-256` trước khi đổi tên sang file đích.
   - Chặn triệt để tấn công leo thang thư mục (`Path Traversal` như `../../Windows/System32`) bằng `safe_resolve_under` trên cả Python và Kotlin.
3. **Chuyển ngữ Thuật toán FairPlay SAP sang Kotlin thuần (`PlayFair.kt`, `FairPlayGarble.kt`, `FairPlayTables.kt`)**:
   - Chuyển đổi chính xác qua cây cú pháp trừu tượng (`AST`) sang lớp bọc số nguyên 32-bit `CInt` để tránh sai lệch độ ưu tiên toán tử bit trong Kotlin và xử lý chuẩn tràn số học không dấu `uint32_t` (`(((i.toLong() - 155L) and 0xFFFFFFFFL) % 210L)`).
   - Giải mã chính xác **21/21 vector mẫu FairPlay thực tế thu từ iPhone** ngay trong bộ kiểm thử JUnit.
   - Tích hợp bộ giải mã dòng `AesCtrStream` theo từng byte (không bị trễ khối 16 byte cuối của JCE) và xử lý số nguyên 64-bit không dấu `streamConnectionID` trong `BinaryPlist.kt`.

---

## 4. Kết quả Kiểm định Thực tế

| Hạng mục kiểm thử | Lệnh thực thi | Kết quả |
| :--- | :--- | :--- |
| **Android JUnit Suite** (DeckLink v1, AES-GCM tamper, Path traversal, UDP Discovery, BinaryPlist, Pairing Ed25519/X25519, **21 vector iPhone FairPlay**) | `android\gradlew.bat testDebugUnitTest --no-daemon` | **PASS 100%** (`BUILD SUCCESSFUL`, 0 cảnh báo Kotlin) |
| **Windows Pytest Suite** (Framing, Auth, RateLimiter, Dual-Pane File Transfer + Resume, MP4 Orientation Split Recorder, ADB/scrcpy v5, FakeAndroid E2E, Offscreen PySide6 UI) | `pytest windows/tests -v` | **PASS 17/17** (`0 failed`, `0 skipped`) |
| **Cầu nối TCP Trực tiếp Python (Windows) ↔ Kotlin (Android)** (`test_cross_platform_bridge.py`) | Khởi chạy `CrossPlatformHostMainKt` trên JVM thật & kết nối từ Python `connect_decklink` | **PASS 100%** (Xác thực 2 chiều, duyệt thư mục, gửi & nhận file đa khối Tiếng Việt kiểm tra SHA-256, Chat, Clipboard, Chạm) |
| **Kiểm định File cài đặt Android (`PocketDeck-debug.apk`)** | `apksigner verify --verbose dist-apk\PocketDeck-debug.apk` | **Verifies** (`APK Signature Scheme v2: true`, kích thước `20,769,335` bytes ~ 19.8 MB) |
| **Kiểm thử khói Bản đóng gói Độc lập Windows (`PocketDeck.exe`)** | `dist\PocketDeck\PocketDeck.exe --local --no-audio --quit-after 2` | **Exit code `0`** (đã nhúng sẵn `PocketDeck-android.apk` + `adb.exe` + `scrcpy-server`) |
| **Kịch bản Kiểm thử Tổng hợp 1 lệnh** | `test-all.bat` | **PASS 100%** |

---

## 5. Lịch sử Tiến độ & Các Mốc Push GitHub

Mỗi Task hoàn thành đều đã được kiểm thử và đẩy trực tiếp lên nhánh `main` tại `https://github.com/HienPhuong99/PocketDeck`:
- **Task 1 (`7b7d72a`)**: Khởi tạo kho mã nguồn, kiến trúc tổng thể (`docs/PLAN.md`) & đặc tả giao thức (`docs/PROTOCOL.md`).
- **Task 2 (`40ecf7b`)**: Lõi giao thức DeckLink v1, khám phá mạng LAN & truyền file 2 chiều có nối tiếp trên Windows.
- **Task 3 (`8be68ce`)**: Engine giải mã H.264/Audio, quay Fragmented MP4, kết nối ADB/scrcpy v5.0.1 & trình giả lập `FakeAndroidDevice`.
- **Task 4 (`7aaa0b5`)**: Giao diện Windows hoàn chỉnh (`PhoneWindow`, `FileTransferWindow` UltraViewer, `ConnectDialog`, `SetupWizard`, `SettingsDialog`).
- **Task 5 (`f4628c7`)**: Khởi tạo dự án Android Gradle/Compose, lõi giao thức DeckLink Kotlin, `FileTransferEngine` & cổng `WebFilePortal` QR/PIN.
- **Task 6 (`f3b5a76`)**: Đầu nhận AirPlay + thuật toán FairPlay SAP (vượt qua 21 vector iPhone), `ScreenShareService` & `RemoteControlService`.
- **Task 7 (`625e451`)**: Trọn bộ giao diện Jetpack Compose Material 3 trên Android, `ViewerActivity` PiP & xuất bản dựng sẵn `dist-apk/PocketDeck-debug.apk`.
- **Task 8 (`288ebe7`)**: Kiểm thử cầu nối TCP thực Python ↔ Kotlin, đóng gói `PocketDeck.exe` độc lập bằng PyInstaller & bộ script tự động hoá (`.bat`, `docs/ci-workflow.yml`).
- **Task 9**: Tự kiểm toán bảo mật & bản quyền, hoàn thiện trọn bộ tài liệu Tiếng Việt (`README.md`, `HUONG-DAN.md`, `docs/REPORT.md`) và xác nhận toàn bộ bài kiểm thử xanh 100%.
