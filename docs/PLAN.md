# PocketDeck – Kế hoạch Phát triển & Kiến trúc Hệ thống

PocketDeck là bộ giải pháp phản chiếu màn hình, điều khiển từ xa và **truyền file 2 chiều kiểu UltraViewer** đa nền tảng, lấy cảm hứng từ [PocketPane](https://github.com/HienPhuong99/pocketpane) và mở rộng hoàn chỉnh cho hệ sinh thái **Android ↔ Windows ↔ Android ↔ iPhone**.

---

## 1. Tổng quan 2 Phiên bản trong Monorepo

| Phiên bản | Thư mục | Công nghệ | Vai trò chính |
|---|---|---|---|
| **PocketDeck for Windows** | `windows/` | Python 3.11+, PySide6, PyAV (FFmpeg), Cryptography, Zeroconf, ADB + `scrcpy-server`, PyInstaller | Cửa sổ nổi hình điện thoại không viền trên Windows: chiếu & điều khiển điện thoại Android (qua ID/Mật khẩu **DeckLink** không cần cáp hoặc qua **USB / Wireless ADB**), quay MP4, chụp ảnh, gõ tiếng Việt, kèm **Cửa sổ Truyền File 2 ngăn & Chat kiểu UltraViewer**. |
| **PocketDeck for Android** | `android/` | Kotlin 2.0, Jetpack Compose Material 3, Coroutines, `MediaProjection`, `MediaCodec`, `AccessibilityService`, NSD/mDNS, FairPlay SAP | File cài đặt `.apk` chạy trực tiếp trên điện thoại / máy tính bảng / Android TV: **(1)** Cho phép PC hoặc máy Android khác xem & điều khiển từ xa qua ID + Mật khẩu; **(2)** Truyền File 2 ngăn + Chat kiểu UltraViewer + Cổng Web QR cho iPhone; **(3)** Biến máy Android thành **Đầu nhận AirPlay** để chiếu màn hình iPhone/iPad lên Android giống PocketPane. |

---

## 2. Giải quyết Bài toán "Truyền File kiểu UltraViewer" trên Android & Windows

Người dùng quen với **UltraViewer** thích nhất 3 trải nghiệm:
1. **Kết nối bằng ID 9 số + Mật khẩu phiên 6 số** (hoặc chọn ngay thiết bị tự hiện trong mạng nội bộ).
2. **Cửa sổ Truyền File 2 ngăn (Dual-Pane File Transfer)**:
   - Bên trái: **Thiết bị của tôi** (duyệt thư mục, ổ đĩa, Downloads, Ảnh/DCIM, Tài liệu...).
   - Bên phải: **Thiết bị đối tác** (duyệt cây thư mục của máy bên kia theo thời gian thực).
   - Chọn nhiều file hoặc cả thư mục → bấm **Gửi sang (`→`)** hoặc **Nhận về (`←`)**, hoặc kéo-thả trực tiếp từ Windows Explorer vào cửa sổ điện thoại.
   - Có **thanh tiến độ phần trăm, tốc độ thực `MB/s`, thời gian còn lại `ETA`, kiểm tra mã băm `SHA-256`, tự động nối tiếp (`Resume`) khi đứt mạng**, và nút Tạm dừng / Huỷ.
3. **Khung Chat nhanh đính kèm thông báo File**: vừa điều khiển / hỗ trợ từ xa vừa nhắn tin và gửi file tức thì.

PocketDeck hiện thực hóa trọn vẹn cả 3 trải nghiệm trên qua **3 kênh vận chuyển thống nhất**:
- **Kênh 1 – DeckLink Native (`_pocketdeck._tcp`)**: Giữa Windows ↔ Android hoặc Android ↔ Android (qua ID 9 số + Mật khẩu 6 số).
- **Kênh 2 – ADB Sync Adapter**: Khi kết nối máy Android với Windows qua cáp USB / Wireless Debugging mà chưa cài APK, cửa sổ Truyền File 2 ngăn của Windows tự động chuyển sang nói chuyện qua ADB (`ls`/`stat`/`push`/`pull`) với giao diện không đổi!
- **Kênh 3 – Web File Portal (QR + Mã PIN 4 số)**: Dành riêng cho trường hợp **iPhone chiếu AirPlay lên máy Android** (do iOS không mở hệ thống file qua AirPlay): máy Android tạo cổng Web cục bộ tốc độ cao, quét mã QR trên iPhone là gửi/nhận ảnh, video, tài liệu về thẳng `Download/PocketDeck`.

---

## 3. Lộ trình 9 Task Thực hiện

1. **Task 1**: Khởi tạo Repo, chuỗi công cụ build độc lập (Python venv, OpenJDK 17, Android SDK, `scrcpy`), đặc tả giao thức `docs/PROTOCOL.md`.
2. **Task 2**: Lõi giao thức `DeckLink v1` (xác thực HMAC ID/Password, mã hoá phiên AES-GCM) & Engine Truyền File 2 chiều + Chat + Clipboard trên Python.
3. **Task 3**: Engine Đa phương tiện (H.264 low-delay decoder, Audio player, Fragmented MP4 recorder) + Engine ADB/scrcpy + Trình giả lập `fake_android.py`.
4. **Task 4**: Giao diện PySide6 trên Windows (`PhoneWindow`, `FileTransferWindow` 2 ngăn kiểu UltraViewer, `ConnectPanel`, `SetupWizard` tự cài APK qua cáp, `SettingsDialog` Việt/Anh).
5. **Task 5**: Khởi tạo dự án Gradle Kotlin + Jetpack Compose (`android/`) + Lõi `DeckLink`, `FileTransferEngine` & `WebFilePortal` trên Android.
6. **Task 6**: Đầu nhận AirPlay trên Android (`AirPlayReceiver`, `BinaryPlist`, `AirPlayPairing`, `PlayFair` kiểm chứng với 21 vector mẫu iPhone, `AirPlayMediaDecoder`) + `ScreenShareService` & `RemoteControlService`.
7. **Task 7**: Giao diện Jetpack Compose Material 3 hoàn chỉnh trên Android (4 Tab + Màn hình Xem PiP) & build thành công file cài đặt `.apk`.
8. **Task 8**: Kiểm thử tích hợp chéo Windows ↔ Android, đóng gói `PocketDeck.exe` kèm sẵn `.apk`, hoàn thiện `install.bat`, `build.bat`, `build-android.bat` & GitHub Actions CI.
9. **Task 9**: Rà soát chất lượng toàn diện (Self-Audit), hoàn thiện `README.md`, `HUONG-DAN.md`, `docs/REPORT.md`.
