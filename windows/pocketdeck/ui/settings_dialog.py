"""Settings Dialog replacing manual JSON editing (Vietnamese + English)."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDialog,
    QFileDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLineEdit,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from .. import autostart
from ..config import Settings
from .theme import APP_QSS

QUALITY_PRESETS = [
    ("720p (Nhẹ, tiết kiệm pin)", 1280, 720),
    ("1080p Full HD (Khuyên dùng)", 1920, 1080),
    ("1440p 2K (Siêu nét)", 2560, 1440),
]


class SettingsDialog(QDialog):
    """User-friendly configuration dialog for PocketDeck."""

    def __init__(self, settings: Settings, parent: QWidget | None = None):
        super().__init__(parent)
        self.settings = settings
        self.setWindowTitle("Cài đặt PocketDeck")
        self.setMinimumWidth(480)
        self.setStyleSheet(APP_QSS)

        root = QVBoxLayout(self)
        root.setSpacing(12)

        # 1. General & Security
        gen_box = QGroupBox("Thiết bị & Bảo mật kiểu UltraViewer")
        gen_form = QFormLayout(gen_box)

        self.name_edit = QLineEdit(str(settings["name"]))
        gen_form.addRow("Tên máy tính hiển thị:", self.name_edit)

        self.use_fixed_pw = QCheckBox("Sử dụng mật khẩu cố định để truy cập không cần người trực")
        self.use_fixed_pw.setChecked(bool(settings.get("use_fixed_password", False)))
        gen_form.addRow("", self.use_fixed_pw)

        self.fixed_pw_edit = QLineEdit(str(settings.get("fixed_password") or ""))
        self.fixed_pw_edit.setPlaceholderText("Nhập 6 chữ số hoặc mật khẩu riêng...")
        self.fixed_pw_edit.setEchoMode(QLineEdit.EchoMode.PasswordEchoOnEdit)
        gen_form.addRow("Mật khẩu cố định:", self.fixed_pw_edit)

        dl_row = QHBoxLayout()
        self.dl_edit = QLineEdit(str(settings["download_dir"]))
        browse_btn = QPushButton("Chọn...")
        browse_btn.clicked.connect(self._browse_download_dir)
        dl_row.addWidget(self.dl_edit, 1)
        dl_row.addWidget(browse_btn)
        gen_form.addRow("Thư mục nhận file:", dl_row)

        root.addWidget(gen_box)

        # 2. Stream & Window
        stream_box = QGroupBox("Chất lượng Hình ảnh, Âm thanh & Cửa sổ")
        stream_form = QFormLayout(stream_box)

        self.quality_combo = QComboBox()
        cur_h = int(settings.get("height", 1080))
        sel_idx = 1
        for i, (label, w, h) in enumerate(QUALITY_PRESETS):
            self.quality_combo.addItem(label, (w, h))
            if h == cur_h:
                sel_idx = i
        self.quality_combo.setCurrentIndex(sel_idx)
        stream_form.addRow("Độ phân giải luồng:", self.quality_combo)

        self.fps_combo = QComboBox()
        self.fps_combo.addItem("60 FPS (Mượt nhất)", 60)
        self.fps_combo.addItem("30 FPS (Nhẹ máy)", 30)
        self.fps_combo.setCurrentIndex(0 if int(settings.get("fps", 60)) >= 60 else 1)
        stream_form.addRow("Tốc độ khung hình:", self.fps_combo)

        self.bitrate_spin = QSpinBox()
        self.bitrate_spin.setRange(2, 32)
        self.bitrate_spin.setSuffix(" Mbps")
        self.bitrate_spin.setValue(int(settings.get("bitrate_mbps", 8)))
        stream_form.addRow("Bitrate tối đa:", self.bitrate_spin)

        self.audio_chk = QCheckBox("Phát âm thanh điện thoại ra loa máy tính")
        self.audio_chk.setChecked(bool(settings.get("audio", True)))
        stream_form.addRow("", self.audio_chk)

        self.nav_chk = QCheckBox("Hiện thanh nút điều hướng Android (◁ Quay lại  ○ Home  □ Đa nhiệm)")
        self.nav_chk.setChecked(bool(settings.get("show_nav_bar", True)))
        stream_form.addRow("", self.nav_chk)

        self.awake_chk = QCheckBox("Giữ máy tính không tự ngủ khi đang phản chiếu")
        self.awake_chk.setChecked(bool(settings.get("keep_awake", True)))
        stream_form.addRow("", self.awake_chk)

        self.autostart_chk = QCheckBox("Khởi động ngầm cùng Windows dưới khay hệ thống")
        self.autostart_chk.setChecked(autostart.is_enabled())
        stream_form.addRow("", self.autostart_chk)

        root.addWidget(stream_box)

        # Action buttons
        btn_row = QHBoxLayout()
        btn_row.addStretch(1)
        cancel_btn = QPushButton("Huỷ")
        cancel_btn.clicked.connect(self.reject)
        save_btn = QPushButton("Lưu cài đặt")
        save_btn.setObjectName("PrimaryBtn")
        save_btn.clicked.connect(self._save_and_accept)
        btn_row.addWidget(cancel_btn)
        btn_row.addWidget(save_btn)
        root.addLayout(btn_row)

    def _browse_download_dir(self) -> None:
        chosen = QFileDialog.getExistingDirectory(self, "Chọn thư mục nhận file", self.dl_edit.text())
        if chosen:
            self.dl_edit.setText(chosen)

    def _save_and_accept(self) -> None:
        name = self.name_edit.text().strip()
        if name:
            self.settings["name"] = name
        self.settings["use_fixed_password"] = self.use_fixed_pw.isChecked()
        fpw = self.fixed_pw_edit.text().strip()
        if fpw:
            self.settings["fixed_password"] = fpw

        dl = self.dl_edit.text().strip()
        if dl:
            Path(dl).mkdir(parents=True, exist_ok=True)
            self.settings["download_dir"] = dl

        w, h = self.quality_combo.currentData()
        self.settings["width"] = int(w)
        self.settings["height"] = int(h)
        self.settings["fps"] = int(self.fps_combo.currentData())
        self.settings["bitrate_mbps"] = int(self.bitrate_spin.value())
        self.settings["audio"] = self.audio_chk.isChecked()
        self.settings["show_nav_bar"] = self.nav_chk.isChecked()
        self.settings["keep_awake"] = self.awake_chk.isChecked()
        self.settings.save()

        if not self.settings.local_mode:
            autostart.set_enabled(self.autostart_chk.isChecked())
        self.accept()
