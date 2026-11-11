"""Step-by-step Android Connection & APK Setup Wizard (with live status lights & Wireless Debugging pairing)."""
from __future__ import annotations

import threading
from pathlib import Path
from typing import Callable

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtWidgets import (
    QDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from ..adb.manager import AdbDevice, AdbManager, find_bundled_apk
from .theme import APP_QSS


class SetupWizardDialog(QDialog):
    """Guides users through Android USB/Wireless Debugging OR 1-click installing PocketDeck.apk."""

    _check_done = Signal(bool, bool, list, bool)
    _action_done = Signal(bool, str)

    def __init__(
        self,
        adb: AdbManager,
        on_connect_adb: Callable[[str], None] | None = None,
        parent: QWidget | None = None,
    ):
        super().__init__(parent)
        self.adb = adb
        self.on_connect_adb = on_connect_adb
        self._devices: list[AdbDevice] = []
        self._busy = False
        self._closed = False

        self.setWindowTitle("Hướng dẫn Kết nối & Cài đặt Android")
        self.setMinimumWidth(540)
        self.setStyleSheet(APP_QSS)

        self._check_done.connect(self._apply_status)
        self._action_done.connect(self._on_action_result)

        root = QVBoxLayout(self)
        root.setSpacing(12)

        intro = QLabel(
            "PocketDeck hỗ trợ 2 cách kết nối với điện thoại Android:\n"
            "• Cách 1 (Khuyên dùng – giống UltraViewer): Cài PocketDeck.apk lên máy Android rồi nhập ID + Mật khẩu 6 số.\n"
            "• Cách 2 (Không cần cài app): Bật Gỡ lỗi USB / Gỡ lỗi không dây (ADB) để chiếu màn hình & truyền file ngay."
        )
        intro.setWordWrap(True)
        intro.setObjectName("MutedLabel")
        root.addWidget(intro)

        # Status Steps Box
        steps_box = QGroupBox("Kiểm tra tự động từng bước (cập nhật mỗi 3 giây)")
        steps_layout = QVBoxLayout(steps_box)
        self.lbl_step1 = QLabel("⏳ Bước 1: Đang kiểm tra bộ công cụ Android (adb + scrcpy-server)...")
        self.lbl_step2 = QLabel("⏳ Bước 2: Đang tìm điện thoại Android qua cáp USB hoặc Wi-Fi...")
        self.lbl_step3 = QLabel("⏳ Bước 3: Kiểm tra gói cài đặt PocketDeck-android.apk đi kèm...")
        for lbl in (self.lbl_step1, self.lbl_step2, self.lbl_step3):
            lbl.setWordWrap(True)
            steps_layout.addWidget(lbl)

        action_row = QHBoxLayout()
        self.btn_connect_now = QPushButton("Chiếu & Điều khiển ngay qua ADB")
        self.btn_connect_now.setObjectName("PrimaryBtn")
        self.btn_connect_now.setEnabled(False)
        self.btn_connect_now.clicked.connect(self._connect_selected_adb)

        self.btn_install_apk = QPushButton("Cài PocketDeck.apk sang điện thoại (1 chạm)")
        self.btn_install_apk.setEnabled(False)
        self.btn_install_apk.clicked.connect(self._install_apk_to_phone)

        self.btn_switch_wifi = QPushButton("Chuyển USB sang Không dây (TCP 5555)")
        self.btn_switch_wifi.setEnabled(False)
        self.btn_switch_wifi.clicked.connect(self._enable_wifi_adb)

        action_row.addWidget(self.btn_connect_now)
        action_row.addWidget(self.btn_install_apk)
        steps_layout.addLayout(action_row)
        steps_layout.addWidget(self.btn_switch_wifi)
        root.addWidget(steps_box)

        # Wireless Debugging Pairing (Android 11+)
        wifi_box = QGroupBox("Ghép đôi Gỡ lỗi Không dây (Android 11+ không cần cắm cáp)")
        wifi_form = QFormLayout(wifi_box)
        self.pair_addr = QLineEdit()
        self.pair_addr.setPlaceholderText("Ví dụ: 192.168.1.25:39123 (IP & Cổng trong mục Pair device with pairing code)")
        self.pair_code = QLineEdit()
        self.pair_code.setPlaceholderText("Mã ghép đôi 6 chữ số (ví dụ: 482915)")
        self.conn_addr = QLineEdit()
        self.conn_addr.setPlaceholderText("IP & Cổng chính của Wireless Debugging (ví dụ: 192.168.1.25:41555)")

        pair_btns = QHBoxLayout()
        btn_pair = QPushButton("1. Ghép đôi (Pair)")
        btn_pair.clicked.connect(self._do_pair)
        btn_conn = QPushButton("2. Kết nối Wi-Fi ADB")
        btn_conn.clicked.connect(self._do_wifi_connect)
        pair_btns.addWidget(btn_pair)
        pair_btns.addWidget(btn_conn)

        wifi_form.addRow("IP:Cổng ghép đôi:", self.pair_addr)
        wifi_form.addRow("Mã 6 số:", self.pair_code)
        wifi_form.addRow("IP:Cổng kết nối:", self.conn_addr)
        wifi_form.addRow("", pair_btns)
        root.addWidget(wifi_box)

        bottom = QHBoxLayout()
        self.status_hint = QLabel("")
        self.status_hint.setObjectName("MutedLabel")
        close_btn = QPushButton("Đóng")
        close_btn.clicked.connect(self.close)
        bottom.addWidget(self.status_hint, 1)
        bottom.addWidget(close_btn)
        root.addLayout(bottom)

        self._timer = QTimer(self)
        self._timer.setInterval(3000)
        self._timer.timeout.connect(self.refresh_checks)
        self._timer.start()
        self.refresh_checks()

    def closeEvent(self, event) -> None:  # noqa: N802
        self._closed = True
        self._timer.stop()
        super().closeEvent(event)

    def refresh_checks(self) -> None:
        if self._busy or self._closed:
            return

        def _worker() -> None:
            has_adb = self.adb.available
            has_srv = bool(self.adb.scrcpy_server_path and self.adb.scrcpy_server_path.is_file())
            devs = self.adb.list_devices() if has_adb else []
            has_apk = find_bundled_apk() is not None
            if not self._closed:
                try:
                    self._check_done.emit(has_adb, has_srv, devs, has_apk)
                except RuntimeError:
                    pass

        threading.Thread(target=_worker, daemon=True).start()

    def _apply_status(self, has_adb: bool, has_srv: bool, devs: list[AdbDevice], has_apk: bool) -> None:
        self._devices = devs
        if has_adb and has_srv:
            self.lbl_step1.setText("🟢 Bước 1: Công cụ Android (adb + scrcpy-server) đã sẵn sàng.")
        else:
            self.lbl_step1.setText("🔴 Bước 1: Thiếu adb hoặc scrcpy-server. Hãy chạy install.bat để tải tự động.")

        ready = [d for d in devs if d.state == "device"]
        unauth = [d for d in devs if d.state == "unauthorized"]
        if ready:
            names = ", ".join(d.display_name for d in ready)
            self.lbl_step2.setText(f"🟢 Bước 2: Đã nhận diện {len(ready)} thiết bị sẵn sàng: {names}")
        elif unauth:
            self.lbl_step2.setText(
                "🟠 Bước 2: Đã thấy điện thoại nhưng chưa cấp quyền! Hãy mở khoá màn hình điện thoại và bấm 'Cho phép / Always allow USB debugging'."
            )
        else:
            self.lbl_step2.setText(
                "🟠 Bước 2: Chưa thấy thiết bị ADB. Hãy cắm cáp USB (bật Tuỳ chọn nhà phát triển > Gỡ lỗi USB) hoặc ghép đôi Không dây bên dưới."
            )

        apk_path: Path | None = find_bundled_apk()
        if has_apk and apk_path is not None:
            self.lbl_step3.setText(f"🟢 Bước 3: Đã có sẵn {apk_path.name} để cài 1 chạm sang điện thoại.")
        else:
            self.lbl_step3.setText("🟠 Bước 3: Chưa build gói PocketDeck-android.apk trong thư mục dist-apk.")

        self.btn_connect_now.setEnabled(bool(ready and has_srv))
        self.btn_install_apk.setEnabled(bool(ready and has_apk))
        self.btn_switch_wifi.setEnabled(any(not d.is_wireless for d in ready))

    def _run_bg(self, label: str, fn: Callable[[], str]) -> None:
        self._busy = True
        self.status_hint.setText(label)

        def _work() -> None:
            try:
                msg = fn()
                self._action_done.emit(True, msg)
            except Exception as exc:
                self._action_done.emit(False, str(exc))

        threading.Thread(target=_work, daemon=True).start()

    def _on_action_result(self, ok: bool, message: str) -> None:
        self._busy = False
        self.status_hint.setText(message[:90])
        if ok:
            QMessageBox.information(self, "PocketDeck Android", message)
        else:
            QMessageBox.warning(self, "Không thể thực hiện", message)
        self.refresh_checks()

    def _first_ready_serial(self) -> str | None:
        for d in self._devices:
            if d.state == "device":
                return d.serial
        return None

    def _connect_selected_adb(self) -> None:
        serial = self._first_ready_serial()
        if serial and self.on_connect_adb is not None:
            self.on_connect_adb(serial)
            self.accept()

    def _install_apk_to_phone(self) -> None:
        serial = self._first_ready_serial()
        if not serial:
            return
        self._run_bg(
            "Đang cài đặt PocketDeck.apk sang điện thoại...",
            lambda: f"Đã cài đặt PocketDeck thành công lên {serial}!\n{self.adb.install_apk(serial)}",
        )

    def _enable_wifi_adb(self) -> None:
        usb_devs = [d for d in self._devices if d.state == "device" and not d.is_wireless]
        if not usb_devs:
            return
        serial = usb_devs[0].serial

        def _switch() -> str:
            ip = self.adb.device_wlan_ip(serial)
            self.adb.enable_tcpip(serial, 5555)
            if ip:
                self.adb.connect_wireless(f"{ip}:5555")
                return f"Đã bật kết nối không dây tại {ip}:5555! Bạn có thể rút cáp USB ngay bây giờ."
            return "Đã bật cổng TCP 5555 trên điện thoại. Hãy nhập IP Wi-Fi của máy để kết nối."

        self._run_bg("Đang chuyển sang kết nối Wi-Fi...", _switch)

    def _do_pair(self) -> None:
        addr = self.pair_addr.text().strip()
        code = self.pair_code.text().strip()
        if not addr or not code:
            QMessageBox.warning(self, "Thiếu thông tin", "Vui lòng nhập đầy đủ IP:Cổng ghép đôi và mã 6 chữ số.")
            return
        self._run_bg("Đang ghép đôi Wireless Debugging...", lambda: self.adb.pair_wireless(addr, code))

    def _do_wifi_connect(self) -> None:
        addr = self.conn_addr.text().strip()
        if not addr:
            QMessageBox.warning(self, "Thiếu địa chỉ", "Vui lòng nhập địa chỉ IP:Cổng kết nối của điện thoại.")
            return
        self._run_bg("Đang kết nối Wi-Fi ADB...", lambda: self.adb.connect_wireless(addr))
