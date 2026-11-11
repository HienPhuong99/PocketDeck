# PocketDeck

> Bộ ứng dụng phản chiếu màn hình, điều khiển từ xa và **truyền file 2 chiều + Chat kiểu UltraViewer** đa nền tảng cho **Android ↔ Windows ↔ Android ↔ iPhone**.

## Thành phần trong Kho mã nguồn

- **`windows/` — PocketDeck for Windows (`.exe` / Python + PySide6)**:
  - Cửa sổ nổi hình điện thoại không viền trên Windows, tự xoay ngang/dọc, quay màn hình MP4 có tiếng, chụp ảnh màn hình, gõ tiếng Việt mượt mà.
  - Kết nối thiết bị Android qua **ID 9 số + Mật khẩu 6 số (DeckLink)** không cần cáp USB/ADB, hoặc kết nối trực tiếp qua **USB / Wireless Debugging (ADB + scrcpy)**.
  - Tích hợp **Cửa sổ Truyền File 2 ngăn + Chat kiểu UltraViewer** (`Ctrl+F`), hỗ trợ gửi/nhận nhiều file & thư mục, kiểm tra SHA-256 và tự động nối tiếp (Resume) khi đứt mạng.
- **`android/` — PocketDeck for Android (`.apk` / Kotlin + Jetpack Compose)**:
  - Cho phép máy tính Windows hoặc điện thoại Android khác xem màn hình, nghe tiếng & điều khiển từ xa qua **ID + Mật khẩu**.
  - Giao diện **Truyền File 2 ngăn + Chat kiểu UltraViewer** ngay trên Android, kèm **Cổng Web QR/PIN** để nhận/gửi file nhanh với iPhone.
  - Chế độ **Đầu nhận AirPlay (AirPlay Receiver)**: biến điện thoại / máy tính bảng / Android TV thành màn hình nhận phản chiếu từ iPhone / iPad giống [PocketPane](https://github.com/HienPhuong99/pocketpane).

## Tài liệu

- Kế hoạch & Kiến trúc: [docs/PLAN.md](docs/PLAN.md)
- Đặc tả Giao thức DeckLink v1: [docs/PROTOCOL.md](docs/PROTOCOL.md)

## Giấy phép

Phát hành theo giấy phép [GPL-3.0](LICENSE).
