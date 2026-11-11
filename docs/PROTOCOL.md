# Đặc tả Kỹ thuật Giao thức DeckLink v1 (`PROTOCOL.md`)

Giao thức **DeckLink v1** là giao thức nhị phân đa kênh chạy trên một kết nối TCP duy nhất (mặc định cổng `7200`, tự động cấp phát nếu bận), dùng chung giữa **PocketDeck for Windows (Python)** và **PocketDeck for Android (Kotlin)**.

---

## 1. Định danh Thiết bị kiểu UltraViewer (Partner ID & Session Password)

- **Partner ID (9 chữ số, hiển thị dạng `XXX XXX XXX`)**:
  - Trên mạng nội bộ (LAN / Wi-Fi / VPN), Partner ID có thể mã hóa trực tiếp thông tin định tuyến nhanh hoặc được phân giải tức thì thông qua:
    1. **mDNS / NSD**: Dịch vụ `_pocketdeck._tcp.local.` chứa thuộc tính TXT `id=<9_digits>`, `name=<device_name>`, `platform=android|windows`, `ver=1`.
    2. **UDP LAN Discovery Broadcast (cổng `7201`)**: Gói tin JSON ngắn `{"op":"resolve","id":"123456789"}` → thiết bị khớp ID phản hồi `{"op":"here","id":"123456789","name":"...","port":7200}`.
    3. **Smart Subnet Fallback**: Người dùng cũng có thể nhập trực tiếp `IP` hoặc `IP:Port` vào ô ID đối tác khi đi qua VPN / khác subnet.
- **Session Password (6 chữ số)**:
  - Mặc định sinh ngẫu nhiên bằng CSPRNG mỗi lần khởi động (hoặc đổi bằng 1 nút bấm), có tuỳ chọn đặt mật khẩu cá nhân cố định.
  - Không bao giờ truyền mật khẩu dạng rõ (plaintext) trên mạng.

---

## 2. Khung Gói Tin Nhị Phân (Binary Frame Envelope)

Mọi thông điệp trên kết nối TCP đều tuân theo header cố định **14 byte** (Big-Endian):

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   Magic 'P'   |   Magic 'D'   |    Version    |    Channel    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|     Flags     |                  Reserved (0)                 |
|               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|               |                 Payload Length                |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                 Payload Length (tiếp - uint32)                |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     Payload (0 .. 16 MiB)                     |
+---------------------------------------------------------------+
```

Cụ thể cấu trúc `>2sBBHI` (10 byte) hoặc chuẩn **12 byte** `>2sBBII`:
- `magic` (2 bytes): `b"PD"` (`0x50 0x44`)
- `version` (1 byte, `uint8`): `0x01`
- `channel` (1 byte, `uint8`): Mã kênh logic
- `seq` (4 bytes, `uint32` big-endian): Số thứ tự gói tin tăng dần bắt đầu từ `0` trên mỗi chiều gửi
- `length` (4 bytes, `uint32` big-endian): Độ dài payload (tối đa `8_388_608` bytes = 8 MiB)

> Tổng header cố định = **12 bytes** (`struct.Struct(">2sBBII")`).
> Sau bước xác thực thành công, toàn bộ payload có `channel != CH_AUTH` được mã hoá bằng **AES-256-GCM** với:
> - `session_key = HKDF-SHA256(ikm=password_utf8, salt=server_nonce || client_nonce, info=b"PocketDeck-v1-SessionKey", len=32)`
> - `nonce (12 bytes) = direction_prefix (4 bytes: b"C2S\x00" hoặc b"S2C\x00") || uint64_be(seq)`
> - Nhờ dùng `seq` chống phát lại (replay protection) và GCM tag 16 bytes ở cuối payload, mọi gói điều khiển & truyền file đều được bảo vệ toàn vẹn và bí mật trên Wi-Fi công cộng!

---

## 3. Danh sách Kênh Đa Hợp (Multiplexed Channels)

| Mã | Tên kênh | Định dạng Payload (trước khi bọc AES-GCM) | Mô tả |
|---|---|---|---|
| `0x01` | `CH_AUTH` | UTF-8 JSON | Bắt tay phiên bản, trao đổi `nonce` 32-byte, xác thực HMAC-SHA256 của mật khẩu + thông tin thiết bị. |
| `0x02` | `CH_CONTROL` | UTF-8 JSON | Sự kiện điều khiển: `tap`, `long_press`, `swipe`, `touch_down`, `touch_move`, `touch_up`, `scroll`, `key`, `text` (hỗ trợ đầy đủ Unicode Tiếng Việt), `global_action` (`back`, `home`, `recents`, `notifications`, `lock`), `quality_request`, `ping`/`pong`. |
| `0x03` | `CH_VIDEO` | Binary (`uint8 kind` + `uint64 pts_us` + `uint16 width` + `uint16 height` + Annex-B H.264 bytes) | `kind=1`: SPS/PPS config + kích thước khung hình; `kind=0`: H.264 access unit độ trễ thấp. |
| `0x04` | `CH_AUDIO` | Binary (`uint8 codec` + `uint32 sample_rate` + `uint8 channels` + `uint64 pts_us` + audio bytes) | `codec=1`: PCM S16LE; `codec=2`: Opus; `codec=3`: AAC. |
| `0x05` | `CH_FILE_CTRL` | UTF-8 JSON | Điều khiển Truyền File kiểu UltraViewer: `fs_roots`, `fs_list`, `fs_mkdir`, `fs_delete`, `fs_rename`, `xfer_offer`, `xfer_accept` (kèm `resume_offset`), `xfer_complete` (kiểm tra `sha256`), `xfer_cancel`, `xfer_pause`, `xfer_error`. |
| `0x06` | `CH_FILE_DATA` | Binary (`16-byte transfer_id_uuid` + `uint64 offset` + raw chunk bytes, mặc định 256 KiB/chunk) | Truyền dữ liệu file tốc độ cao, tách riêng khỏi JSON để đạt băng thông Wi-Fi tối đa và không tốn bộ nhớ Base64! |
| `0x07` | `CH_CHAT` | UTF-8 JSON | Tin nhắn Chat trực tiếp (`{"id": "...", "sender": "...", "text": "...", "ts": 1700000000}`) giống cửa sổ Chat của UltraViewer. |
| `0x08` | `CH_CLIPBOARD` | UTF-8 JSON | Đồng bộ Clipboard 2 chiều (`{"text": "..."}`). |

---

## 4. Quy trình Bắt tay Xác thực (`CH_AUTH`)

1. **Server → Client (`hello`)**:
   ```json
   {
     "op": "hello",
     "version": 1,
     "device_id": "148293501",
     "device_name": "Pixel 8 Pro",
     "platform": "android",
     "server_nonce": "<64-char hex = 32 random bytes>",
     "capabilities": ["video", "audio", "control", "file_transfer", "chat", "clipboard"]
   }
   ```
2. **Client → Server (`auth`)**:
   - Client sinh `client_nonce` (32 random bytes).
   - Tính `proof = HMAC-SHA256(key = password.encode("utf-8"), msg = bytes.fromhex(server_nonce) + bytes.fromhex(client_nonce) + b"client")`.
   ```json
   {
     "op": "auth",
     "client_id": "892104332",
     "client_name": "Windows-PC",
     "platform": "windows",
     "client_nonce": "<64-char hex>",
     "proof": "<64-char hex>",
     "requested": ["video", "audio", "control", "file_transfer", "chat", "clipboard"]
   }
   ```
3. **Server → Client (`auth_result`)**:
   - Server kiểm tra `proof` bằng so sánh thời gian hằng số (`hmac.compare_digest` / `MessageDigest.isEqual`). Sau tối đa 5 lần sai liên tiếp từ cùng IP trong 60 giây, Server tạm khoá IP đó 60 giây chống dò mật khẩu 6 số!
   - Nếu đúng, Server gửi `server_proof = HMAC-SHA256(key = password, msg = server_nonce + client_nonce + b"server")`:
   ```json
   {
     "op": "auth_ok",
     "server_proof": "<64-char hex>",
     "screen_width": 1080,
     "screen_height": 2400
   }
   ```
   - Từ gói tin tiếp theo trở đi (`seq` tiếp theo trên cả 2 chiều), chế độ mã hoá phiên **AES-256-GCM** lập tức kích hoạt!

---

## 5. Quy trình Truyền File Kiểu UltraViewer (`CH_FILE_CTRL` + `CH_FILE_DATA`)

1. **Duyệt thư mục từ xa**:
   - Gửi `{"op": "fs_roots", "req_id": "r1"}` → Trả về danh sách gốc (Windows: các ổ `C:\`, `D:\`, `Desktop`, `Downloads`, `Pictures`; Android: `Bộ nhớ trong`, `Download`, `DCIM (Ảnh & Video)`, `Documents`, `Pictures`, `PocketDeck`).
   - Gửi `{"op": "fs_list", "req_id": "r2", "path": "/storage/emulated/0/Download"}` → Trả về danh sách `entries`: `[{"name": "bao-cao.pdf", "is_dir": false, "size": 1048576, "mtime": 1730000000}]` + dung lượng trống `free_bytes`.
2. **Gửi File / Thư mục có Resume & Kiểm tra SHA-256**:
   - Bên gửi phát `xfer_offer`:
     ```json
     {
       "op": "xfer_offer",
       "xfer_id": "550e8400e29b41d4a716446655440000",
       "dest_dir": "D:/Downloads",
       "rel_path": "HinhAnh/anh1.jpg",
       "size": 5242880,
       "mtime": 1730000000,
       "sha256": "<optional precomputed or final>"
     }
     ```
   - Bên nhận kiểm tra file tạm `.part` tại `dest_dir/rel_path.pocketdeck.part`:
     - Nếu đã có một phần từ lần truyền bị ngắt trước đó (`0 < part_size <= size`), trả về `{"op": "xfer_accept", "xfer_id": "...", "resume_offset": part_size}`!
     - Nếu truyền mới, trả về `resume_offset: 0`.
   - Bên gửi đọc file từ `resume_offset`, gửi liên tiếp các khung nhị phân `CH_FILE_DATA` (`16-byte UUID bytes` + `8-byte uint64 offset` + tối đa `262144` bytes dữ liệu), đồng thời băm SHA-256 luồng đọc.
   - Kết thúc file, bên gửi phát `{"op": "xfer_eof", "xfer_id": "...", "sha256": "<full_file_sha256_hex>"}`.
   - Bên nhận đối chiếu SHA-256 toàn bộ file `.part`: nếu khớp 100%, đổi tên nguyên tử sang tên file đích, giữ nguyên `mtime`, và trả về `{"op": "xfer_done", "xfer_id": "...", "saved_path": "..."}`!
