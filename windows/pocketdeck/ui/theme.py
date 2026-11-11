"""Clean dark UI theme, human-readable formatters, and crisp programmatic app icon."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QRectF, Qt
from PySide6.QtGui import QColor, QFont, QIcon, QPainter, QPainterPath, QPen, QPixmap

APP_QSS = """
QDialog, QMainWindow, QWidget#RootSurface {
    background-color: #0f172a;
    color: #e2e8f0;
    font-family: "Segoe UI", system-ui, sans-serif;
    font-size: 13px;
}
QGroupBox {
    background-color: #1e293b;
    border: 1px solid #334155;
    border-radius: 10px;
    margin-top: 10px;
    padding: 14px 12px 12px 12px;
    font-weight: 600;
    color: #f8fafc;
}
QGroupBox::title {
    subcontrol-origin: margin;
    left: 12px;
    padding: 0 6px;
    color: #93c5fd;
}
QLabel {
    color: #e2e8f0;
}
QLabel#MutedLabel {
    color: #94a3b8;
    font-size: 12px;
}
QLabel#BigCode {
    color: #f8fafc;
    font-family: "Consolas", "Cascadia Mono", monospace;
    font-size: 20px;
    font-weight: 700;
    background-color: #0f172a;
    border: 1px solid #334155;
    border-radius: 8px;
    padding: 6px 10px;
}
QLineEdit, QComboBox, QSpinBox {
    background-color: #0f172a;
    color: #f8fafc;
    border: 1px solid #475569;
    border-radius: 7px;
    padding: 6px 10px;
    selection-background-color: #2563eb;
}
QLineEdit:focus, QComboBox:focus, QSpinBox:focus {
    border: 1px solid #3b82f6;
}
QPushButton {
    background-color: #334155;
    color: #f8fafc;
    border: 1px solid #475569;
    border-radius: 7px;
    padding: 7px 14px;
    font-weight: 600;
}
QPushButton:hover {
    background-color: #475569;
}
QPushButton:pressed {
    background-color: #1e293b;
}
QPushButton:disabled {
    background-color: #1e293b;
    color: #64748b;
    border-color: #334155;
}
QPushButton#PrimaryBtn {
    background-color: #2563eb;
    border-color: #1d4ed8;
    color: #ffffff;
}
QPushButton#PrimaryBtn:hover {
    background-color: #1d4ed8;
}
QPushButton#DangerBtn {
    background-color: #dc2626;
    border-color: #b91c1c;
    color: #ffffff;
}
QTableWidget, QTreeWidget, QListWidget, QTextEdit {
    background-color: #0f172a;
    alternate-background-color: #162033;
    color: #e2e8f0;
    border: 1px solid #334155;
    border-radius: 8px;
    gridline-color: #1e293b;
    selection-background-color: #1d4ed8;
    selection-color: #ffffff;
}
QHeaderView::section {
    background-color: #1e293b;
    color: #cbd5e1;
    border: none;
    border-bottom: 1px solid #334155;
    border-right: 1px solid #334155;
    padding: 6px 8px;
    font-weight: 600;
}
QProgressBar {
    background-color: #1e293b;
    border: 1px solid #334155;
    border-radius: 6px;
    text-align: center;
    color: #f8fafc;
    height: 18px;
}
QProgressBar::chunk {
    background-color: #2563eb;
    border-radius: 5px;
}
QMenu {
    background-color: #1e293b;
    color: #f8fafc;
    border: 1px solid #475569;
    padding: 4px;
}
QMenu::item {
    padding: 6px 24px 6px 14px;
    border-radius: 5px;
}
QMenu::item:selected {
    background-color: #2563eb;
    color: #ffffff;
}
QMenu::separator {
    height: 1px;
    background: #334155;
    margin: 4px 6px;
}
"""


def format_bytes(num_bytes: int | float) -> str:
    n = float(max(0, num_bytes))
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024.0 or unit == "TB":
            return f"{int(n)} B" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024.0
    return f"{n:.1f} TB"


def format_speed(bps: float) -> str:
    if bps <= 0:
        return "—"
    return f"{format_bytes(bps)}/s"


def format_eta(seconds: float | None) -> str:
    if seconds is None:
        return "—"
    s = max(0, int(round(seconds)))
    m, sec = divmod(s, 60)
    h, m = divmod(m, 60)
    if h > 0:
        return f"{h:d}:{m:02d}:{sec:02d}"
    return f"{m:02d}:{sec:02d}"


def make_app_pixmap(size: int = 128) -> QPixmap:
    """Render a crisp geometric PocketDeck icon (slate rounded card + phone bezel + bidirectional arrow)."""
    pm = QPixmap(size, size)
    pm.fill(Qt.GlobalColor.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.RenderHint.Antialiasing, True)

    pad = size * 0.06
    bg_rect = QRectF(pad, pad, size - 2 * pad, size - 2 * pad)
    bg_path = QPainterPath()
    bg_path.addRoundedRect(bg_rect, size * 0.22, size * 0.22)
    p.fillPath(bg_path, QColor("#1e293b"))
    p.setPen(QPen(QColor("#3b82f6"), max(2.0, size * 0.03)))
    p.drawPath(bg_path)

    # Phone silhouette
    pw, ph = size * 0.42, size * 0.66
    px, py = (size - pw) / 2.0, (size - ph) / 2.0
    phone_rect = QRectF(px, py, pw, ph)
    phone_path = QPainterPath()
    phone_path.addRoundedRect(phone_rect, size * 0.07, size * 0.07)
    p.fillPath(phone_path, QColor("#0f172a"))
    p.setPen(QPen(QColor("#60a5fa"), max(2.0, size * 0.035)))
    p.drawPath(phone_path)

    # Accent transfer badge inside phone
    badge_rect = QRectF(px + pw * 0.18, py + ph * 0.34, pw * 0.64, ph * 0.32)
    badge_path = QPainterPath()
    badge_path.addRoundedRect(badge_rect, size * 0.04, size * 0.04)
    p.fillPath(badge_path, QColor("#2563eb"))

    font = QFont("Segoe UI", max(8, int(size * 0.14)), QFont.Weight.Bold)
    p.setFont(font)
    p.setPen(QColor("#ffffff"))
    p.drawText(badge_rect, Qt.AlignmentFlag.AlignCenter, "⇄")

    p.end()
    return pm


def make_app_icon() -> QIcon:
    icon = QIcon()
    for sz in (16, 32, 48, 64, 128, 256):
        icon.addPixmap(make_app_pixmap(sz))
    return icon


def save_app_ico(dest: Path) -> Path:
    """Save multi-size .ico file using Pillow from rendered QPixmap."""
    from PIL import Image

    dest.parent.mkdir(parents=True, exist_ok=True)
    pm = make_app_pixmap(256)
    qimg = pm.toImage().convertToFormat(pm.toImage().Format.Format_RGBA8888)
    ptr = qimg.constBits()
    raw = bytes(ptr)
    img = Image.frombytes("RGBA", (qimg.width(), qimg.height()), raw)
    img.save(dest, format="ICO", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    return dest
