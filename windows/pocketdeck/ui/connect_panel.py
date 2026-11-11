"""UltraViewer-style Connection Panel: Partner ID + 6-digit Password + LAN & ADB Auto-Discovery."""
from __future__ import annotations

import threading
from typing import Callable

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QGuiApplication
from PySide6.QtWidgets import (
    QComboBox,
    QDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QPushButton,
    QRadioButton,
    QVBoxLayout,
    QWidget,
)

from ..adb.manager import AdbDevice, AdbManager
from ..config import Settings, format_partner_id
from ..discovery import DiscoveredPeer, LanDiscovery, local_ipv4_addresses
from .setup_wizard import SetupWizardDialog
from .theme import APP_QSS


class ConnectDialog(QDialog):
    """UltraViewer-inspired connection hub for DeckLink (ID + Password) and ADB (USB / Wi-Fi)."""

    _scan_ready = Signal(list, list)

    def __init__(
        self,
        settings: Settings,
        discovery: LanDiscovery,
        adb: AdbManager,
        host_port_fn: Callable[[], int],
        on_connect_decklink: Callable[[str, str, bool], None],
        on_connect_adb: Callable[[str, bool], None],
        parent: QWidget | None = None,
    ):
        super().__init__(parent)
        self.settings = settings
        self.discovery = discovery
        self.adb = adb
        self._host_port_fn = host_port_fn
        self._on_connect_decklink = on_connect_decklink
        self._on_connect_adb = on_connect_adb
        self._scanning = False
        self._closed = False

        self.setWindowTitle("Kết nối PocketDeck — Điều khiển & Truyền File")
        self.setMinimumWidth(680)
        self.setStyleSheet(APP_QSS)
        self._scan_ready.connect(self._populate_discovered)

        root = QVBoxLayout(self)
        root.setSpacing(12)

        # Top split: Left = Your ID/Pass | Right = Partner ID/Pass
        cards_row = QHBoxLayout()
        cards_row.setSpacing(12)

        # --- Left Card: Allow Remote Control ---
        my_box = QGroupBox("Cho phép điều khiển / nhận file")
        my_layout = QVBoxLayout(my_box)
        my_layout.setSpacing(8)

        lbl_my_id_cap = QLabel("ID của bạn")
        lbl_my_id_cap.setObjectName("MutedLabel")
        my_layout.addWidget(lbl_my_id_cap)

        id_row = QHBoxLayout()
        self.lbl_my_id = QLabel(self.settings.formatted_id)
        self.lbl_my_id.setObjectName("BigCode")
        self.lbl_my_id.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        btn_copy_id = QPushButton("Sao chép")
        btn_copy_id.clicked.connect(self._copy_my_credentials)
        id_row.addWidget(self.lbl_my_id, 1)
        id_row.addWidget(btn_copy_id)
        my_layout.addLayout(id_row)

        lbl_my_pw_cap = QLabel("Mật khẩu phiên (6 số)")
        lbl_my_pw_cap.setObjectName("MutedLabel")
        my_layout.addWidget(lbl_my_pw_cap)

        pw_row = QHBoxLayout()
        self.lbl_my_pw = QLabel(self.settings.active_password())
        self.lbl_my_pw.setObjectName("BigCode")
        self.lbl_my_pw.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        btn_refresh_pw = QPushButton("Đổi mới")
        btn_refresh_pw.clicked.connect(self._refresh_password)
        pw_row.addWidget(self.lbl_my_pw, 1)
        pw_row.addWidget(btn_refresh_pw)
        my_layout.addLayout(pw_row)

        self.lbl_lan_ip = QLabel("")
        self.lbl_lan_ip.setObjectName("MutedLabel")
        self.lbl_lan_ip.setWordWrap(True)
        my_layout.addWidget(self.lbl_lan_ip)
        my_layout.addStretch(1)
        self._update_lan_label()

        # --- Right Card: Connect to Partner ---
        peer_box = QGroupBox("Điều khiển / Truyền file tới Đối tác")
        peer_form = QFormLayout(peer_box)
        peer_form.setSpacing(10)

        self.target_combo = QComboBox()
        self.target_combo.setEditable(True)
        self.target_combo.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        if self.target_combo.lineEdit() is not None:
            self.target_combo.lineEdit().setPlaceholderText("Nhập ID 9 số (vd: 482 910 375) hoặc IP:Cổng")
        for rec in self.settings.get("recent_partners") or []:
            rid = str(rec.get("id") or "")
            rname = str(rec.get("name") or "")
            if rid:
                disp = f"{format_partner_id(rid)} — {rname}" if rname else format_partner_id(rid)
                self.target_combo.addItem(disp, rid)
        self.target_combo.currentIndexChanged.connect(self._on_recent_picked)
        peer_form.addRow("ID đối tác:", self.target_combo)

        self.pw_edit = QLineEdit()
        self.pw_edit.setPlaceholderText("Nhập mật khẩu 6 số hiển thị trên điện thoại")
        self.pw_edit.returnPressed.connect(self._connect_partner_clicked)
        peer_form.addRow("Mật khẩu:", self.pw_edit)

        self.rad_mirror = QRadioButton("Chiếu màn hình & Điều khiển từ xa")
        self.rad_files = QRadioButton("Mở ngay cửa sổ Truyền File 2 chiều (kiểu UltraViewer)")
        self.rad_mirror.setChecked(True)
        peer_form.addRow("", self.rad_mirror)
        peer_form.addRow("", self.rad_files)

        self.btn_connect = QPushButton("Kết nối tới đối tác  →")
        self.btn_connect.setObjectName("PrimaryBtn")
        self.btn_connect.clicked.connect(self._connect_partner_clicked)
        peer_form.addRow("", self.btn_connect)

        cards_row.addWidget(my_box, 1)
        cards_row.addWidget(peer_box, 1)
        root.addLayout(cards_row)

        # --- Bottom Box: Auto-Discovered LAN & USB ADB Devices ---
        disc_box = QGroupBox("Thiết bị phát hiện tự động trong mạng Wi-Fi & Cáp USB (ADB)")
        disc_layout = QVBoxLayout(disc_box)

        self.peer_list = QListWidget()
        self.peer_list.setMinimumHeight(140)
        self.peer_list.itemDoubleClicked.connect(self._on_item_activated)
        disc_layout.addWidget(self.peer_list)

        disc_btns = QHBoxLayout()
        self.btn_scan = QPushButton("Quét lại thiết bị")
        self.btn_scan.clicked.connect(self.refresh_devices)

        self.btn_use_selected = QPushButton("Kết nối thiết bị đang chọn")
        self.btn_use_selected.clicked.connect(self._connect_selected_item)

        btn_wizard = QPushButton("Hướng dẫn USB / Cài PocketDeck.apk...")
        btn_wizard.clicked.connect(self._open_wizard)

        btn_close = QPushButton("Đóng")
        btn_close.clicked.connect(self.close)

        disc_btns.addWidget(self.btn_scan)
        disc_btns.addWidget(self.btn_use_selected)
        disc_btns.addWidget(btn_wizard)
        disc_btns.addStretch(1)
        disc_btns.addWidget(btn_close)
        disc_layout.addLayout(disc_btns)
        root.addWidget(disc_box)

        self._timer = QTimer(self)
        self._timer.setInterval(3500)
        self._timer.timeout.connect(self.refresh_devices)
        self._timer.start()
        self.refresh_devices()

    def closeEvent(self, event) -> None:  # noqa: N802
        self._closed = True
        self._timer.stop()
        super().closeEvent(event)

    def _update_lan_label(self) -> None:
        ips = local_ipv4_addresses()
        port = self._host_port_fn() or int(self.settings["port"])
        if ips:
            joined = ", ".join(f"{ip}:{port}" for ip in ips[:2])
            self.lbl_lan_ip.setText(f"Địa chỉ LAN trực tiếp: {joined}")
        else:
            self.lbl_lan_ip.setText(f"Cổng lắng nghe DeckLink: TCP {port}")

    def _copy_my_credentials(self) -> None:
        cb = QGuiApplication.clipboard()
        if cb is not None:
            cb.setText(f"ID: {self.settings.formatted_id} | Mật khẩu: {self.settings.active_password()}")

    def _refresh_password(self) -> None:
        new_pw = self.settings.refresh_password()
        self.lbl_my_pw.setText(new_pw)

    def _on_recent_picked(self, index: int) -> None:
        data = self.target_combo.itemData(index)
        if data and self.target_combo.lineEdit() is not None:
            self.target_combo.lineEdit().setText(format_partner_id(str(data)))

    def _raw_target_text(self) -> str:
        idx = self.target_combo.currentIndex()
        txt = self.target_combo.currentText().strip()
        if idx >= 0:
            data = self.target_combo.itemData(idx)
            if data and txt.startswith(format_partner_id(str(data))):
                return str(data)
        return txt

    def _connect_partner_clicked(self) -> None:
        target = self._raw_target_text()
        pw = self.pw_edit.text().strip()
        if not target:
            QMessageBox.warning(self, "Thiếu ID đối tác", "Vui lòng nhập ID 9 chữ số hoặc địa chỉ IP của thiết bị Android.")
            return
        if not pw:
            QMessageBox.warning(self, "Thiếu mật khẩu", "Vui lòng nhập mật khẩu phiên (6 chữ số) hiển thị trên thiết bị đối tác.")
            return
        self._on_connect_decklink(target, pw, self.rad_files.isChecked())
        self.accept()

    def refresh_devices(self) -> None:
        if self._scanning or self._closed:
            return
        self._scanning = True
        self._update_lan_label()

        def _worker() -> None:
            try:
                self.discovery.broadcast_discover()
                lan_peers = self.discovery.peers()
                adb_devs = self.adb.list_devices() if self.adb.available else []
                if not self._closed:
                    try:
                        self._scan_ready.emit(lan_peers, adb_devs)
                    except RuntimeError:
                        pass
            finally:
                self._scanning = False

        threading.Thread(target=_worker, daemon=True).start()

    def _populate_discovered(self, lan_peers: list[DiscoveredPeer], adb_devs: list[AdbDevice]) -> None:
        self.peer_list.clear()
        for p in lan_peers:
            plat = "Android" if "android" in p.platform.lower() else "Windows"
            item = QListWidgetItem(
                f"📶 [{plat} DeckLink]  {p.name}   —   ID: {p.formatted_id}   ({p.host}:{p.port})"
            )
            item.setData(Qt.ItemDataRole.UserRole, ("decklink", f"{p.host}:{p.port}", p.formatted_id))
            self.peer_list.addItem(item)

        for d in adb_devs:
            mode = "Wi-Fi ADB" if d.is_wireless else "Cáp USB ADB"
            if d.state == "device":
                label = f"🔌 [{mode}]  {d.display_name}   —   Sẵn sàng (Kết nối trực tiếp không cần mật khẩu)"
            else:
                label = f"⚠️ [{mode}]  {d.display_name}   —   Trạng thái: {d.state} (Hãy bấm Cho phép trên điện thoại)"
            item = QListWidgetItem(label)
            item.setData(Qt.ItemDataRole.UserRole, ("adb", d.serial, d.state))
            self.peer_list.addItem(item)

        if self.peer_list.count() == 0:
            empty = QListWidgetItem(
                "Chưa thấy thiết bị tự động. Bạn vẫn có thể nhập thẳng ID 9 số + Mật khẩu ở khung trên hoặc cắm cáp USB."
            )
            empty.setFlags(Qt.ItemFlag.NoItemFlags)
            self.peer_list.addItem(empty)

    def _connect_selected_item(self) -> None:
        cur = self.peer_list.currentItem()
        if cur is not None:
            self._on_item_activated(cur)

    def _on_item_activated(self, item: QListWidgetItem) -> None:
        payload = item.data(Qt.ItemDataRole.UserRole)
        if not payload:
            return
        kind, addr, meta = payload
        if kind == "decklink":
            if self.target_combo.lineEdit() is not None:
                self.target_combo.lineEdit().setText(addr)
            self.pw_edit.setFocus()
            if self.pw_edit.text().strip():
                self._connect_partner_clicked()
        elif kind == "adb":
            if meta != "device":
                QMessageBox.information(
                    self,
                    "Thiết bị chưa cấp quyền ADB",
                    "Hãy mở khoá màn hình điện thoại Android và bấm 'Always allow / Luôn cho phép gỡ lỗi USB'.",
                )
                return
            self._on_connect_adb(str(addr), self.rad_files.isChecked())
            self.accept()

    def _open_wizard(self) -> None:
        dlg = SetupWizardDialog(
            self.adb,
            on_connect_adb=lambda serial: (self._on_connect_adb(serial, self.rad_files.isChecked()), self.accept()),
            parent=self,
        )
        dlg.exec()
