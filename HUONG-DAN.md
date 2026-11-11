# Hướng dẫn Sử dụng PocketDeck (Windows & Android)

Tài liệu này hướng dẫn chi tiết cách cài đặt và sử dụng trọn bộ **PocketDeck** cho người dùng phổ thông lẫn kỹ thuật viên.

---

## Phần 1. Cài đặt trên Máy tính Windows

### Cách A: Chạy nhanh bằng trình cài đặt tự động (Khuyên dùng)
1. Tải kho mã nguồn PocketDeck về máy tính.
2. Nháy đúp chuột vào file **`install.bat`**.
   - Trình cài đặt sẽ tự tạo môi trường Python riêng (`.venv`), cài thư viện giao diện & giải mã hình ảnh, tải sẵn bộ công cụ Android (`adb` + `scrcpy-server`), và đặt biểu tượng **PocketDeck** ra màn hình **Desktop** và **Start Menu**.
3. Mở **PocketDeck** từ màn hình Desktop hoặc nháy đúp **`run.bat`**.

### Cách B: Đóng gói thành file `.exe` độc lập để mang sang máy khác
1. Nháy đúp **`build.bat`**.
2. Sau khoảng 1 phút, thư mục **`dist\PocketDeck\`** sẽ được tạo ra cùng file **`PocketDeck.exe`** (bên trong đã nhúng sẵn cả file `PocketDeck-android.apk` để cài sang điện thoại qua cáp USB chỉ với 1 cú nhấp chuột).

---

## Phần 2. Cài đặt trên Điện thoại / Máy tính bảng Android

Bạn có thể chọn 1 trong 2 cách:
1. **Cài trực tiếp trên điện thoại**:
   - Mở hoặc chép file **`dist-apk/PocketDeck-debug.apk`** sang điện thoại Android (yêu cầu Android 8.0 trở lên).
   - Chạm vào file APK và chọn **Cài đặt** (nếu máy hỏi quyền cài ứng dụng từ nguồn này, hãy bật cho phép).
2. **Cài tự động 1 chạm từ máy tính Windows**:
   - Cắm cáp USB nối điện thoại với máy tính (bật *Gỡ lỗi USB / USB Debugging* trên điện thoại).
   - Trên cửa sổ PocketDeck Windows, bấm nút **Trợ lý Cài đặt** và nhấn **Cài đặt PocketDeck APK sang điện thoại**.

---

## Phần 3. Kết nối & Điều khiển từ xa kiểu UltraViewer (Không cần cáp USB)

Chế độ này hoạt động giữa **Windows ↔ Android** hoặc **Android ↔ Android** chung mạng Wi-Fi / LAN:

1. **Trên thiết bị cần chia sẻ màn hình (ví dụ: Điện thoại Android)**:
   - Mở ứng dụng **PocketDeck**, ở tab **Kết nối**, bạn sẽ thấy **ID của bạn (9 chữ số)** (ví dụ: `482 910 375`) và **Mật khẩu phiên (6 chữ số)**.
   - Bấm nút **Chia sẻ màn hình** và nhấn *Bắt đầu ngay* trên hộp thoại xác nhận của Android để phát hình ảnh H.264 và âm thanh hệ thống.
   - *(Tuỳ chọn để cho phép bên kia chạm/vuốt điều khiển máy bạn)*: Chạm vào thẻ **Quyền Trợ năng điều khiển từ xa** -> bật dịch vụ **PocketDeck** trong phần *Trợ năng (Accessibility)* của Android.

2. **Trên thiết bị điều khiển (Máy tính Windows hoặc một máy Android thứ hai)**:
   - Mở **PocketDeck** -> bấm **Kết nối** (`Ctrl+K` trên Windows, hoặc mục *Điều khiển Đối tác* trên Android).
   - Nhập **ID đối tác (9 chữ số)** (hoặc chọn ngay tên điện thoại hiện tự động trong danh sách *Thiết bị trong mạng LAN*) và nhập **Mật khẩu 6 số**.
   - Nhấn **Bắt đầu điều khiển**:
     - Màn hình điện thoại sẽ hiện lên mượt mà kèm âm thanh trực tiếp.
     - Dùng chuột để chạm/vuốt, cuộn con lăn để lướt trang, nhấn chuột phải để *Quay lại (Back)*, hoặc gõ trực tiếp Tiếng Việt từ bàn phím máy tính!
     - Trên Android, bạn có thể bấm biểu tượng **Thu nhỏ PiP (Picture-in-Picture)** để vừa xem màn hình đối tác ở góc nhỏ vừa mở ứng dụng khác.

---

## Phần 4. Truyền File 2 chiều & Chat kiểu UltraViewer

Tính năng truyền file hoạt động ngay trong phiên kết nối (hoặc bấm nút **Chỉ Truyền File** nếu bạn không cần chiếu màn hình):

### Trên Máy tính Windows
1. Nhấn tổ hợp phím **`Ctrl + F`** (hoặc bấm biểu tượng **Thư mục `⇄`** trên thanh công cụ).
2. Cửa sổ **Truyền File 2 ngăn kiểu UltraViewer** sẽ mở ra:
   - **Ngăn bên trái (Máy tính của bạn)**: Duyệt ổ đĩa `C:\`, `D:\`, `Desktop`, `Downloads`, `Documents`...
   - **Ngăn bên phải (Điện thoại Android đối tác)**: Duyệt trực tiếp `Bộ nhớ trong`, `Download`, `DCIM (Ảnh & Video)`, `Pictures`, `Documents`...
3. Chọn một hoặc nhiều file / cả thư mục rồi bấm:
   - **`Gửi sang Điện thoại ➔`** (hoặc kéo-thả trực tiếp từ Windows Explorer vào ngăn bên phải).
   - **`⬅ Nhận về Máy tính`** để tải ảnh, video gốc không nén từ điện thoại về PC.
4. **Thanh tiến trình & Tự nối tiếp (Resume)**:
   - Hiển thị tốc độ thực (`MB/s`), phần trăm và thời gian còn lại (`ETA`), kèm nút Tạm dừng / Tiếp tục / Huỷ.
   - Mọi file đều được kiểm tra mã băm **SHA-256** chống lỗi hỏng dữ liệu; nếu rớt mạng giữa chừng, lần gửi lại sẽ tự nối tiếp từ phần `.pocketdeck.part` thay vì gửi lại từ đầu.
5. **Khung Chat trực tiếp** ở phía dưới giúp hai bên nhắn tin Tiếng Việt và nhận thông báo ngay khi mỗi file truyền xong.

### Trên Điện thoại Android
1. Chuyển sang tab **Truyền File** trên thanh điều hướng dưới cùng.
2. Chuyển đổi linh hoạt giữa ngăn **Máy này** và **Đối tác** (hoặc xem song song trên màn hình ngang / máy tính bảng):
   - Tích chọn nhiều file/thư mục -> bấm **Gửi sang Đối tác** hoặc **Tải về Máy này**.
   - Bấm biểu tượng **Khung Chat** để nhắn tin trực tiếp với máy tính hoặc điện thoại đối tác.

### Truyền file nhanh với iPhone / iPad / Trình duyệt bất kỳ (Không cần cài app trên iPhone)
1. Trên PocketDeck Android, mở tab **Truyền File** -> bật công tắc **Cổng Web QR truyền file nhanh**.
2. Dùng camera iPhone quét mã QR hiển thị trên màn hình Android (kèm mã PIN 4 số bảo vệ).
3. Safari trên iPhone sẽ mở trang truyền file nội bộ tốc độ cao: tải ảnh/video/tài liệu lên Android hoặc tải file từ Android về iPhone ngay tức thì!

---

## Phần 5. Kết nối qua Cáp USB hoặc Gỡ lỗi Không dây (Không cần cài app trước)

Nếu bạn muốn chiếu màn hình điện thoại Android lên Windows mà **không cần cài đặt bất kỳ ứng dụng nào lên điện thoại**:
1. Trên điện thoại Android, vào *Cài đặt -> Thông tin điện thoại -> chạm 7 lần vào Số bản dựng (Build number)* để bật *Tuỳ chọn nhà phát triển*, sau đó bật **Gỡ lỗi USB (USB Debugging)** (hoặc **Gỡ lỗi không dây / Wireless Debugging** trên Android 11+).
2. Cắm cáp USB vào máy tính (và nhấn *Cho phép gỡ lỗi USB* trên điện thoại).
3. Mở PocketDeck trên Windows -> bấm **Kết nối (`Ctrl+K`)** -> chuyển sang thẻ **USB / ADB Không dây**:
   - Chọn tên điện thoại của bạn và bấm **Chiếu & Điều khiển ngay**, **hoặc** bấm **Ghép đôi Không dây** bằng mã ghép nối 6 số của Android 11+ để rút cáp dùng qua Wi-Fi!
   - Cửa sổ **Truyền File 2 ngăn UltraViewer (`Ctrl+F`)** vẫn hoạt động đầy đủ ở chế độ ADB!

---

## Phần 6. Biến Điện thoại / Máy tính bảng / Android TV thành Đầu nhận AirPlay cho iPhone

Giống như ứng dụng gốc **PocketPane** trên Windows, **PocketDeck for Android** tích hợp sẵn đầu nhận **AirPlay + giải mã phần cứng FairPlay SAP**:
1. Kết nối thiết bị Android và iPhone / iPad vào cùng một mạng Wi-Fi.
2. Mở PocketDeck trên Android -> chọn tab **AirPlay** -> gạt bật **Đầu nhận AirPlay (iPhone / iPad)**.
3. Vuốt mở **Trung tâm điều khiển (Control Center)** trên iPhone -> chạm vào biểu tượng **Phản chiếu màn hình (Screen Mirroring)** -> chọn **`PocketDeck (...)`**.
4. Màn hình và âm thanh của iPhone sẽ hiển thị trực tiếp trên thiết bị Android với giải mã phần cứng `MediaCodec` mượt mà (hỗ trợ cả thu nhỏ cửa sổ nổi **PiP**)!

---

## Phần 7. Giải đáp & Xử lý Sự cố Thường gặp

- **Không tìm thấy thiết bị trong danh sách mạng LAN?**
  - Hãy kiểm tra cả 2 máy đang bắt cùng một mạng Wi-Fi / Router (không dùng mạng Wi-Fi Khách / Guest có bật *AP Isolation*). Bạn cũng có thể nhập trực tiếp địa chỉ `IP:7200` vào ô ID đối tác (ví dụ `192.168.1.25:7200`).
- **Xem được màn hình Android nhưng bấm chuột chưa điều khiển được?**
  - Trên điện thoại Android đang chia sẻ màn hình, hãy vào tab **Kết nối** của PocketDeck và bật quyền **Trợ năng (Accessibility Service)** cho PocketDeck. (Vì chính sách bảo mật của hệ điều hành Android, ứng dụng bắt buộc phải được người dùng bật Trợ năng mới có thể mô phỏng thao tác chạm/vuốt giúp bạn).
- **Android 13/14/15 ẩn mục bật Trợ năng cho file APK cài ngoài (*Cài đặt bị hạn chế / Restricted setting*)?**
  - Mở *Cài đặt Android -> Ứng dụng -> PocketDeck -> chạm biểu tượng dấu 3 chấm `⋮` ở góc trên bên phải -> chọn **Cho phép cài đặt bị hạn chế (Allow restricted settings)***, sau đó quay lại bật quyền Trợ năng bình thường.
