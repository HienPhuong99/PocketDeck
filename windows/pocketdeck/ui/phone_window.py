"""Floating Android Phone Window + Async Runtime Controller (DeckLink & ADB/scrcpy)."""
from __future__ import annotations

import asyncio
import concurrent.futures
import ctypes
import functools
import logging
import math
import os
import threading
import time
from pathlib import Path
from typing import Any, Coroutine

import numpy as np
from PySide6.QtCore import QObject, QPoint, QPointF, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import (
    QAction,
    QColor,
    QFont,
    QFontMetrics,
    QGuiApplication,
    QImage,
    QPainter,
    QPainterPath,
    QPen,
    QPixmap,
)
from PySide6.QtWidgets import QApplication, QMenu, QMessageBox, QSystemTrayIcon, QWidget

from .. import APP_NAME
from ..adb.adb_fs import AdbFileSystem, AdbTransferEngine
from ..adb.manager import AdbManager
from ..adb.stream import (
    AKEYCODE_APP_SWITCH,
    AKEYCODE_BACK,
    AKEYCODE_DEL,
    AKEYCODE_ENTER,
    AKEYCODE_HOME,
    AMOTION_EVENT_ACTION_DOWN,
    AMOTION_EVENT_ACTION_MOVE,
    AMOTION_EVENT_ACTION_UP,
    ScrcpyControlSerializer,
    ScrcpySession,
)
from ..config import Settings, default_media_dirs
from ..discovery import LanDiscovery, UDP_DISCOVERY_PORT
from ..media.audio import AudioPlayer, MultiCodecAudioReceiver
from ..media.record import Recorder
from ..media.video import VideoDecoder, save_frame_png
from ..protocol.file_transfer import LocalFileSystem, TransferProgress
from ..protocol.framing import AUDIO_CODEC_PCM16, VIDEO_KIND_CONFIG
from ..protocol.session import DeckLinkHostServer, DeckLinkSession, PeerInfo, SessionCallbacks, connect_decklink
from .connect_panel import ConnectDialog
from .file_window import FileTransferWindow
from .settings_dialog import SettingsDialog
from .setup_wizard import SetupWizardDialog
from .theme import APP_QSS, format_bytes, make_app_icon

log = logging.getLogger(__name__)

SHADOW = 18
TOP_BAR_H = 38
NAV_BAR_H = 36
DEFAULT_ASPECT = 1080 / 2400

ES_CONTINUOUS = 0x80000000
ES_SYSTEM_REQUIRED = 0x00000001
ES_DISPLAY_REQUIRED = 0x00000002


def _set_keep_awake(enabled: bool) -> None:
    if os.name != "nt":
        return
    try:
        flags = ES_CONTINUOUS | (ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED if enabled else 0)
        ctypes.windll.kernel32.SetThreadExecutionState(flags)
    except Exception:
        pass


@functools.lru_cache(maxsize=64)
def _ui_font(px_size: int, bold: bool = False) -> QFont:
    f = QFont("Segoe UI")
    f.setPixelSize(max(9, px_size))
    f.setWeight(QFont.Weight.DemiBold if bold else QFont.Weight.Normal)
    return f


@functools.lru_cache(maxsize=64)
def _mono_font(px_size: int, bold: bool = True) -> QFont:
    f = QFont("Consolas")
    f.setPixelSize(max(10, px_size))
    f.setWeight(QFont.Weight.Bold if bold else QFont.Weight.Normal)
    return f


class _FrameBridge(QObject):
    """Single-slot frame coalescer + cross-thread event bridge to Qt GUI thread."""

    frame_ready = Signal(object, int, int)
    connected = Signal(str, str, int, int, bool)  # mode, label, w, h, open_files
    disconnected = Signal(str)
    hint = Signal(str)
    transfer_progress = Signal(object)
    chat_received = Signal(str, str)
    clipboard_received = Signal(str)
    _notify_frame = Signal()

    def __init__(self) -> None:
        super().__init__()
        self._latest: tuple[np.ndarray | None, int, int] | None = None
        self._lock = threading.Lock()
        self._notify_frame.connect(self._deliver_latest, Qt.ConnectionType.QueuedConnection)

    def post_frame(self, arr: np.ndarray | None, w: int, h: int) -> None:
        with self._lock:
            already_queued = self._latest is not None
            self._latest = (arr, w, h)
        if not already_queued:
            self._notify_frame.emit()

    def _deliver_latest(self) -> None:
        with self._lock:
            item, self._latest = self._latest, None
        if item is not None:
            self.frame_ready.emit(*item)


class AsyncBackend:
    """Runs DeckLink host/client, LAN discovery, and ADB/scrcpy sessions on a dedicated asyncio loop."""

    def __init__(self, settings: Settings, bridge: _FrameBridge, open_audio_device: bool = True):
        self.settings = settings
        self.bridge = bridge
        self.adb = AdbManager()
        self.decoder = VideoDecoder(on_frame=bridge.post_frame)
        self.decoder.start()

        self.audio_player = AudioPlayer(open_device=open_audio_device and bool(settings.get("audio", True)))
        self.audio_rx = MultiCodecAudioReceiver(self.audio_player)

        self.loop = asyncio.new_event_loop()
        self._ready_evt = threading.Event()
        self.host_server: DeckLinkHostServer | None = None
        self.discovery = LanDiscovery(
            device_id=settings.device_id,
            device_name=str(settings["name"]),
            tcp_port=int(settings["port"]),
            platform="windows",
            udp_port=0 if settings.local_mode else UDP_DISCOVERY_PORT,
            enable_mdns=not settings.local_mode and bool(settings.get("visible_on_lan", True)),
        )

        self.active_mode: str = ""  # "", "decklink", "adb"
        self.active_label: str = ""
        self.decklink_session: DeckLinkSession | None = None
        self.scrcpy_session: ScrcpySession | None = None
        self.adb_serial: str = ""
        self.adb_fs: AdbFileSystem | None = None
        self.adb_xfer: AdbTransferEngine | None = None
        self._ignore_clip_echo: str = ""

        self._thread = threading.Thread(target=self._run_loop, name="pocketdeck-async-loop", daemon=True)
        self._thread.start()
        self._ready_evt.wait(timeout=5.0)

    @property
    def host_port(self) -> int:
        return self.host_server.port if self.host_server is not None else int(self.settings["port"])

    def submit(self, coro: Coroutine[Any, Any, Any]) -> concurrent.futures.Future[Any]:
        return asyncio.run_coroutine_threadsafe(coro, self.loop)

    def _run_loop(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.loop.run_until_complete(self._startup())
        self._ready_evt.set()
        try:
            self.loop.run_forever()
        finally:
            self.loop.run_until_complete(self._cleanup())
            self.loop.close()

    async def _startup(self) -> None:
        self.host_server = DeckLinkHostServer(
            device_id=self.settings.device_id,
            device_name=str(self.settings["name"]),
            password_provider=self.settings.active_password,
            host="127.0.0.1" if self.settings.local_mode else "0.0.0.0",
            port=int(self.settings["port"]),
            platform="windows",
            session_factory=self._make_session_callbacks,
            on_session_started=self._on_inbound_session,
            download_dir=str(self.settings["download_dir"]),
        )
        bound_port = await self.host_server.start()
        self.discovery.update_info(
            device_name=str(self.settings["name"]),
            tcp_port=bound_port,
            visible=bool(self.settings.get("visible_on_lan", True)),
        )
        self.discovery.start()

    async def _cleanup(self) -> None:
        await self.disconnect_active()
        self.discovery.stop()
        if self.host_server is not None:
            await self.host_server.stop()

    def shutdown(self) -> None:
        self.decoder.stop()
        self.audio_player.close()
        if self.loop.is_running():
            self.loop.call_soon_threadsafe(self.loop.stop)
        self._thread.join(timeout=3.0)

    def _make_session_callbacks(self, peer: PeerInfo) -> SessionCallbacks:
        def _on_video(kind: int, _pts: int, _w: int, _h: int, data: bytes) -> None:
            self.decoder.submit(data, is_config=(kind == VIDEO_KIND_CONFIG))

        def _on_audio(codec: int, rate: int, ch: int, _pts: int, raw: bytes) -> None:
            if self.settings.get("audio", True):
                self.audio_rx.feed(codec, rate, ch, raw)

        def _on_chat(msg: dict[str, Any]) -> None:
            sender = str(msg.get("sender") or peer.device_name or "Đối tác")
            text = str(msg.get("text") or "")
            if text:
                self.bridge.chat_received.emit(sender, text)
                self.bridge.hint.emit(f"💬 {sender}: {text[:42]}")

        def _on_clip(text: str) -> None:
            if text and text != self._ignore_clip_echo:
                self.bridge.clipboard_received.emit(text)

        def _on_prog(prog: TransferProgress) -> None:
            self.bridge.transfer_progress.emit(prog)
            if prog.status == "completed":
                arrow = "Đã gửi" if prog.direction == "send" else "Đã nhận"
                self.bridge.hint.emit(f"✓ {arrow}: {prog.filename} ({format_bytes(prog.total_bytes)})")

        def _on_disc(reason: str) -> None:
            if self.decklink_session is not None and self.decklink_session.is_closed:
                self.decklink_session = None
                if self.active_mode == "decklink":
                    self.active_mode = ""
                    self.active_label = ""
                    self.decoder.reset()
                    self.audio_player.flush()
                    self.bridge.disconnected.emit(reason)

        return SessionCallbacks(
            on_video=_on_video,
            on_audio=_on_audio,
            on_chat=_on_chat,
            on_clipboard=_on_clip,
            on_transfer_progress=_on_prog,
            on_disconnected=_on_disc,
        )

    def _on_inbound_session(self, session: DeckLinkSession) -> None:
        if self.decklink_session is None and self.scrcpy_session is None:
            self.decklink_session = session
            self.active_mode = "decklink"
            self.active_label = session.peer.device_name or session.peer.device_id
            self.bridge.connected.emit(
                "decklink",
                self.active_label,
                session.peer.screen_width,
                session.peer.screen_height,
                False,
            )
            self.bridge.hint.emit(f"Đã kết nối từ {self.active_label}")

    async def disconnect_active(self) -> None:
        sess, self.decklink_session = self.decklink_session, None
        scrcpy, self.scrcpy_session = self.scrcpy_session, None
        self.adb_serial = ""
        self.adb_fs = None
        self.adb_xfer = None
        self.active_mode = ""
        self.active_label = ""
        if sess is not None:
            await sess.close("user_disconnect")
        if scrcpy is not None:
            await scrcpy.close("user_disconnect")
        self.decoder.reset()
        self.audio_player.flush()

    async def connect_decklink_async(self, target: str, password: str, open_files: bool = False) -> None:
        await self.disconnect_active()
        self.bridge.hint.emit(f"Đang tìm & xác thực {target}...")
        try:
            host, port = await asyncio.to_thread(
                self.discovery.resolve_partner,
                target,
                int(self.settings["port"]),
                2.5,
            )
            dummy_peer = PeerInfo(device_id=target, device_name=target, platform="android", remote_ip=host)
            cbs = self._make_session_callbacks(dummy_peer)
            sess = await connect_decklink(
                host=host,
                port=port,
                password=password,
                client_id=self.settings.device_id,
                client_name=str(self.settings["name"]),
                platform="windows",
                callbacks=cbs,
                download_dir=str(self.settings["download_dir"]),
            )
            dummy_peer.device_name = sess.peer.device_name
            self.decklink_session = sess
            self.active_mode = "decklink"
            self.active_label = sess.peer.device_name or sess.peer.device_id or host
            self.settings.add_recent_partner(sess.peer.device_id or target, self.active_label, f"{host}:{port}")
            self.bridge.connected.emit(
                "decklink",
                self.active_label,
                sess.peer.screen_width,
                sess.peer.screen_height,
                open_files,
            )
        except Exception as exc:
            self.bridge.hint.emit(f"Không kết nối được: {exc}")
            self.bridge.disconnected.emit(str(exc))

    async def connect_adb_async(self, serial: str, open_files: bool = False) -> None:
        await self.disconnect_active()
        self.adb_serial = serial
        self.adb_fs = AdbFileSystem(self.adb, serial)
        self.adb_xfer = AdbTransferEngine(self.adb, serial, on_progress=self.bridge.transfer_progress.emit)
        self.active_mode = "adb"
        self.active_label = f"ADB {serial}"

        if self.adb.scrcpy_server_path and self.adb.scrcpy_server_path.is_file():
            self.bridge.hint.emit(f"Đang khởi động phản chiếu scrcpy cho {serial}...")
            try:
                scrcpy = ScrcpySession(
                    adb=self.adb,
                    serial=serial,
                    max_size=max(int(self.settings["width"]), int(self.settings["height"])),
                    max_fps=int(self.settings["fps"]),
                    bitrate_bps=int(self.settings["bitrate_mbps"]) * 1_000_000,
                    enable_audio=bool(self.settings.get("audio", True)),
                    on_video=lambda data, is_cfg, _w, _h: self.decoder.submit(data, is_config=is_cfg),
                    on_audio_pcm=lambda pcm: self.audio_rx.feed(AUDIO_CODEC_PCM16, 48000, 2, pcm)
                    if self.settings.get("audio", True)
                    else None,
                    on_clipboard=lambda txt: self.bridge.clipboard_received.emit(txt) if txt else None,
                    on_disconnected=self._on_scrcpy_disc,
                )
                await scrcpy.start()
                self.scrcpy_session = scrcpy
                self.active_label = scrcpy.device_name
                self.bridge.connected.emit(
                    "adb",
                    self.active_label,
                    scrcpy.screen_width,
                    scrcpy.screen_height,
                    open_files,
                )
                return
            except Exception as exc:
                log.warning("scrcpy stream start failed on %s: %s", serial, exc)
                self.bridge.hint.emit(f"Chế độ ADB Truyền File sẵn sàng ({exc})")

        # Fallback: ADB File Transfer mode even if scrcpy stream is unavailable
        self.bridge.connected.emit("adb", self.active_label, 1080, 2400, True)

    def _on_scrcpy_disc(self, reason: str) -> None:
        self.scrcpy_session = None
        if self.active_mode == "adb":
            self.active_mode = ""
            self.active_label = ""
            self.decoder.reset()
            self.audio_player.flush()
            self.bridge.disconnected.emit(reason)

    # ------------------------------------------------------------------ Input Dispatch
    def send_touch(self, action: str, nx: float, ny: float, src_w: int, src_h: int) -> None:
        px = max(0, min(src_w - 1, int(round(nx * src_w))))
        py = max(0, min(src_h - 1, int(round(ny * src_h))))
        if self.decklink_session is not None and not self.decklink_session.is_closed:
            self.submit(
                self.decklink_session.send_control(
                    action,
                    x=px,
                    y=py,
                    nx=round(nx, 5),
                    ny=round(ny, 5),
                    screen_w=src_w,
                    screen_h=src_h,
                )
            )
        elif self.scrcpy_session is not None:
            am_map = {
                "touch_down": AMOTION_EVENT_ACTION_DOWN,
                "touch_move": AMOTION_EVENT_ACTION_MOVE,
                "touch_up": AMOTION_EVENT_ACTION_UP,
            }
            am_act = am_map.get(action)
            if am_act is not None:
                raw = ScrcpyControlSerializer.inject_touch(am_act, px, py, src_w, src_h)
                self.submit(self.scrcpy_session.send_control_bytes(raw))

    def send_scroll(self, nx: float, ny: float, src_w: int, src_h: int, hscroll: float, vscroll: float) -> None:
        px = max(0, min(src_w - 1, int(round(nx * src_w))))
        py = max(0, min(src_h - 1, int(round(ny * src_h))))
        if self.decklink_session is not None and not self.decklink_session.is_closed:
            self.submit(
                self.decklink_session.send_control(
                    "scroll",
                    x=px,
                    y=py,
                    nx=round(nx, 5),
                    ny=round(ny, 5),
                    hscroll=hscroll,
                    vscroll=vscroll,
                )
            )
        elif self.scrcpy_session is not None:
            raw = ScrcpyControlSerializer.inject_scroll(px, py, src_w, src_h, hscroll=hscroll, vscroll=vscroll)
            self.submit(self.scrcpy_session.send_control_bytes(raw))

    def send_nav(self, nav_name: str) -> None:
        """nav_name in ('back', 'home', 'recents', 'notifications', 'rotate', 'enter', 'backspace')."""
        if self.decklink_session is not None and not self.decklink_session.is_closed:
            self.submit(self.decklink_session.send_control("key", key=nav_name))
        elif self.scrcpy_session is not None:
            raw = b""
            if nav_name == "back":
                raw = ScrcpyControlSerializer.back_or_screen_on()
            elif nav_name == "home":
                raw = ScrcpyControlSerializer.press_keycode(AKEYCODE_HOME)
            elif nav_name == "recents":
                raw = ScrcpyControlSerializer.press_keycode(AKEYCODE_APP_SWITCH)
            elif nav_name == "notifications":
                raw = ScrcpyControlSerializer.expand_notifications()
            elif nav_name == "rotate":
                raw = ScrcpyControlSerializer.rotate_device()
            elif nav_name == "enter":
                raw = ScrcpyControlSerializer.press_keycode(AKEYCODE_ENTER)
            elif nav_name == "backspace":
                raw = ScrcpyControlSerializer.press_keycode(AKEYCODE_DEL)
            if raw:
                self.submit(self.scrcpy_session.send_control_bytes(raw))

    def send_text(self, text: str) -> None:
        if not text:
            return
        self._ignore_clip_echo = text
        if self.decklink_session is not None and not self.decklink_session.is_closed:
            self.submit(self.decklink_session.send_control("text", text=text))
        elif self.scrcpy_session is not None:
            self.submit(self.scrcpy_session.send_text(text))


class PhoneWindow(QWidget):
    """Floating borderless Android phone-shaped window with UltraViewer File Transfer & Recording."""

    def __init__(
        self,
        settings: Settings,
        open_audio_device: bool = True,
        grab_dir: Path | None = None,
        enable_tray: bool = True,
    ):
        super().__init__()
        self.settings = settings
        self.grab_dir = grab_dir
        self._grabbed_standby = False
        self._grabbed_live = False

        self.bridge = _FrameBridge()
        self.backend = AsyncBackend(settings, self.bridge, open_audio_device=open_audio_device)

        self.setWindowTitle(APP_NAME)
        self.setWindowIcon(make_app_icon())
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, True)
        self.setAttribute(Qt.WidgetAttribute.WA_InputMethodEnabled, True)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        self.setMouseTracking(True)
        self.setAcceptDrops(True)
        self._apply_window_flags()
        self.setWindowOpacity(max(0.35, min(1.0, float(settings.get("opacity", 1.0)))))

        self._qimage: QImage | None = None
        self._frame_buf: np.ndarray | None = None
        self.src_size: tuple[int, int] = (1080, 2400)
        self.aspect: float = DEFAULT_ASPECT
        self.phone_height: int = max(460, min(1200, int(settings.get("window_h", 740))))

        self.control_enabled: bool = True
        self.fullscreen: bool = False
        self.hovering: bool = False
        self.show_stats: bool = False
        self._hint_text: str = ""
        self._hint_until: float = 0.0

        # Window dragging & touch feedback
        self._win_drag_offset: QPoint | None = None
        self._touching: bool = False
        self._touch_pts: list[QPointF] = []
        self._ripples: list[tuple[QPointF, float]] = []

        # Cached shadow pixmap & clickable hitboxes
        self._shadow_cache_key: tuple[int, int] = (0, 0)
        self._shadow_pm: QPixmap | None = None
        self._top_hits: list[tuple[QRectF, str]] = []
        self._nav_hits: list[tuple[QRectF, str]] = []
        self._standby_hits: list[tuple[QRectF, str]] = []

        # Dialogs & Recording
        self.file_win: FileTransferWindow | None = None
        self.recorder: Recorder | None = None
        self.tray: QSystemTrayIcon | None = None

        # Signals
        self.bridge.frame_ready.connect(self._on_video_frame)
        self.bridge.connected.connect(self._on_session_connected)
        self.bridge.disconnected.connect(self._on_session_disconnected)
        self.bridge.hint.connect(self.show_hint)
        self.bridge.transfer_progress.connect(self._on_transfer_progress)
        self.bridge.chat_received.connect(self._on_chat_received)
        self.bridge.clipboard_received.connect(self._on_remote_clipboard)

        self._tick_timer = QTimer(self)
        self._tick_timer.setInterval(250)
        self._tick_timer.timeout.connect(self._on_periodic_tick)
        self._tick_timer.start()

        if enable_tray and QSystemTrayIcon.isSystemTrayAvailable():
            self._init_tray()

        self._resize_to_phone_height(self.phone_height)
        wx, wy = settings.get("window_x"), settings.get("window_y")
        if isinstance(wx, int) and isinstance(wy, int):
            self.move(wx, wy)

    # ------------------------------------------------------------------ Window Geometry & Shadow
    def _apply_window_flags(self) -> None:
        flags = Qt.WindowType.FramelessWindowHint | Qt.WindowType.Window
        if self.settings.get("always_on_top", False):
            flags |= Qt.WindowType.WindowStaysOnTopHint
        self.setWindowFlags(flags)

    def _nav_extra_h(self) -> int:
        return NAV_BAR_H if self.settings.get("show_nav_bar", True) and not self.fullscreen else 0

    def _resize_to_phone_height(self, phone_h: int) -> None:
        self.phone_height = max(420, min(1400, int(phone_h)))
        phone_w = max(240, int(round(self.phone_height * self.aspect)))
        total_w = phone_w + SHADOW * 2
        total_h = TOP_BAR_H + self.phone_height + self._nav_extra_h() + SHADOW * 2
        self.resize(total_w, total_h)
        dpr = max(1.0, self.devicePixelRatioF())
        self.backend.decoder.target = (int(phone_w * dpr), int(self.phone_height * dpr))

    def _phone_rect(self) -> QRectF:
        if self.fullscreen:
            return QRectF(0, 0, self.width(), self.height())
        w = self.width() - SHADOW * 2
        h = self.height() - TOP_BAR_H - self._nav_extra_h() - SHADOW * 2
        return QRectF(SHADOW, SHADOW + TOP_BAR_H, max(120, w), max(120, h))

    def _screen_rect(self) -> QRectF:
        pr = self._phone_rect()
        if self.fullscreen:
            return pr
        bezel = max(7.0, min(12.0, pr.width() * 0.026))
        return pr.adjusted(bezel, bezel, -bezel, -bezel)

    def _ensure_shadow_pixmap(self, pr: QRectF) -> QPixmap:
        key = (int(pr.width()), int(pr.height()))
        if self._shadow_pm is not None and self._shadow_cache_key == key:
            return self._shadow_pm
        pm = QPixmap(int(pr.width()) + SHADOW * 2, int(pr.height()) + SHADOW * 2)
        pm.fill(Qt.GlobalColor.transparent)
        p = QPainter(pm)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        radius = min(pr.width(), pr.height()) * 0.085
        for step in range(SHADOW, 1, -2):
            alpha = int(52 * ((SHADOW - step) / SHADOW) ** 2.1)
            if alpha <= 0:
                continue
            r = QRectF(SHADOW - step * 0.45, SHADOW - step * 0.35 + 4, pr.width() + step * 0.9, pr.height() + step * 0.9)
            path = QPainterPath()
            path.addRoundedRect(r, radius + step * 0.35, radius + step * 0.35)
            p.fillPath(path, QColor(2, 6, 23, alpha))
        p.end()
        self._shadow_cache_key = key
        self._shadow_pm = pm
        return pm

    # ------------------------------------------------------------------ Painting
    def paintEvent(self, _event) -> None:  # noqa: N802
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)

        pr = self._phone_rect()
        sr = self._screen_rect()
        outer_radius = 0.0 if self.fullscreen else min(pr.width(), pr.height()) * 0.082
        inner_radius = 0.0 if self.fullscreen else max(12.0, outer_radius - 7.0)

        if not self.fullscreen:
            sh_pm = self._ensure_shadow_pixmap(pr)
            p.drawPixmap(int(pr.x() - SHADOW), int(pr.y() - SHADOW), sh_pm)

            # Titanium slate outer chassis
            chassis = QPainterPath()
            chassis.addRoundedRect(pr, outer_radius, outer_radius)
            p.fillPath(chassis, QColor("#1e293b"))
            p.setBrush(Qt.BrushStyle.NoBrush)
            p.setPen(QPen(QColor("#475569"), 1.4))
            p.drawPath(chassis)

        # Inner screen clip
        screen_path = QPainterPath()
        screen_path.addRoundedRect(sr, inner_radius, inner_radius)
        p.save()
        p.setClipPath(screen_path)
        p.fillRect(sr, QColor("#0b1120"))

        if self._qimage is not None:
            self._standby_hits.clear()
            img_w, img_h = self._qimage.width(), self._qimage.height()
            scale = min(sr.width() / max(1, img_w), sr.height() / max(1, img_h))
            dw, dh = img_w * scale, img_h * scale
            target_rect = QRectF(sr.x() + (sr.width() - dw) / 2.0, sr.y() + (sr.height() - dh) / 2.0, dw, dh)
            p.drawImage(target_rect, self._qimage)
        else:
            self._paint_standby_screen(p, sr)

        # Android punch-hole camera
        if not self.fullscreen and sr.height() >= sr.width():
            cam_r = max(4.5, sr.width() * 0.017)
            p.setPen(QPen(QColor("#1e293b"), 1.0))
            p.setBrush(QColor("#020617"))
            p.drawEllipse(QPointF(sr.center().x(), sr.top() + cam_r * 2.3), cam_r, cam_r)

        # Touch trail & ripples
        now = time.monotonic()
        if len(self._touch_pts) >= 2:
            p.setPen(QPen(QColor(96, 165, 250, 175), 3.2, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
            for i in range(1, len(self._touch_pts)):
                p.drawLine(self._touch_pts[i - 1], self._touch_pts[i])

        alive_ripples: list[tuple[QPointF, float]] = []
        for pt, t0 in self._ripples:
            age = now - t0
            if age < 0.35:
                prog = age / 0.35
                alpha = int(170 * (1.0 - prog))
                rad = 8.0 + 22.0 * prog
                p.setPen(QPen(QColor(96, 165, 250, alpha), 2.2))
                p.setBrush(Qt.BrushStyle.NoBrush)
                p.drawEllipse(pt, rad, rad)
                alive_ripples.append((pt, t0))
        self._ripples = alive_ripples

        if self.show_stats:
            self._paint_stats_overlay(p, sr)

        p.restore()

        if not self.fullscreen:
            self._paint_top_bar(p, pr)
            if self.settings.get("show_nav_bar", True):
                self._paint_nav_bar(p, pr)

        p.end()

        if self.grab_dir is not None:
            if not self._grabbed_standby and self._qimage is None:
                self._grabbed_standby = True
                QTimer.singleShot(0, lambda: self._save_window_grab("standby.png"))
            elif not self._grabbed_live and self._qimage is not None:
                self._grabbed_live = True
                QTimer.singleShot(0, lambda: self._save_window_grab("connected.png"))

    def _paint_standby_screen(self, p: QPainter, sr: QRectF) -> None:
        self._standby_hits.clear()
        cx = sr.center().x()
        pad = max(18.0, sr.width() * 0.075)
        y = sr.top() + sr.height() * 0.09

        # Title
        p.setFont(_ui_font(22, bold=True))
        p.setPen(QColor("#f8fafc"))
        p.drawText(QRectF(sr.left() + pad, y, sr.width() - 2 * pad, 30), Qt.AlignmentFlag.AlignCenter, "PocketDeck")
        y += 32

        p.setFont(_ui_font(12, bold=False))
        p.setPen(QColor("#94a3b8"))
        subtitle = (
            f"Đang kết nối: {self.backend.active_label}"
            if self.backend.active_mode
            else "Chiếu màn hình • Điều khiển • Truyền File"
        )
        p.drawText(QRectF(sr.left() + pad, y, sr.width() - 2 * pad, 22), Qt.AlignmentFlag.AlignCenter, subtitle)
        y += 34

        # UltraViewer ID + Password Card
        card_h = 128.0
        card_r = QRectF(sr.left() + pad, y, sr.width() - 2 * pad, card_h)
        cpath = QPainterPath()
        cpath.addRoundedRect(card_r, 12, 12)
        p.fillPath(cpath, QColor("#162033"))
        p.setPen(QPen(QColor("#334155"), 1.0))
        p.drawPath(cpath)
        self._standby_hits.append((card_r, "copy_creds"))

        p.setFont(_ui_font(11, bold=True))
        p.setPen(QColor("#93c5fd"))
        p.drawText(QRectF(card_r.left() + 14, card_r.top() + 10, card_r.width() - 28, 18), Qt.AlignmentFlag.AlignLeft, "ID CỦA MÁY NÀY (BẤM ĐỂ SAO CHÉP)")

        p.setFont(_mono_font(21, bold=True))
        p.setPen(QColor("#f8fafc"))
        p.drawText(
            QRectF(card_r.left() + 14, card_r.top() + 30, card_r.width() - 28, 30),
            Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
            self.settings.formatted_id,
        )

        p.setFont(_ui_font(11, bold=False))
        p.setPen(QColor("#94a3b8"))
        p.drawText(
            QRectF(card_r.left() + 14, card_r.top() + 68, card_r.width() - 28, 18),
            Qt.AlignmentFlag.AlignLeft,
            f"Mật khẩu phiên:  {self.settings.active_password()}   •   Cổng {self.backend.host_port}",
        )
        ips_txt = f"Chế độ: {'Sẵn sàng nhận kết nối' if not self.backend.active_mode else self.backend.active_label}"
        p.drawText(
            QRectF(card_r.left() + 14, card_r.top() + 92, card_r.width() - 28, 18),
            Qt.AlignmentFlag.AlignLeft,
            ips_txt,
        )
        y += card_h + 22

        # Action Buttons inside screen
        btns = [
            ("connect", "Kết nối Điện thoại (Ctrl+K)", "#2563eb", "#1d4ed8"),
            ("files", "Truyền File 2 chiều (Ctrl+F)", "#1e293b", "#334155"),
            ("wizard", "Hướng dẫn USB / Cài APK", "#1e293b", "#334155"),
            ("settings", "Cài đặt Chất lượng & Bảo mật", "#1e293b", "#334155"),
        ]
        btn_h = 40.0
        btn_font_px = max(11, min(13, int((sr.width() - 2 * pad) / 21)))
        for act_id, label, bg_hex, border_hex in btns:
            if y + btn_h > sr.bottom() - 46:
                break
            br = QRectF(sr.left() + pad, y, sr.width() - 2 * pad, btn_h)
            bp = QPainterPath()
            bp.addRoundedRect(br, 9, 9)
            p.fillPath(bp, QColor(bg_hex))
            p.setBrush(Qt.BrushStyle.NoBrush)
            p.setPen(QPen(QColor(border_hex), 1.2))
            p.drawPath(bp)
            p.setFont(_ui_font(btn_font_px, bold=True))
            p.setPen(QColor("#ffffff" if act_id == "connect" else "#e2e8f0"))
            p.drawText(br, Qt.AlignmentFlag.AlignCenter, label)
            self._standby_hits.append((br, act_id))
            y += btn_h + 10

        # Footer hint
        p.setFont(_ui_font(11, bold=False))
        p.setPen(QColor("#64748b"))
        p.drawText(
            QRectF(cx - (sr.width() - 24) / 2, sr.bottom() - 34, sr.width() - 24, 22),
            Qt.AlignmentFlag.AlignCenter,
            "Chuột phải mở Menu  •  Kéo thả file vào đây để gửi",
        )

    def _paint_top_bar(self, p: QPainter, pr: QRectF) -> None:
        self._top_hits.clear()
        show_bar = self.hovering or self._qimage is None or self.recorder is not None or time.monotonic() < self._hint_until
        if not show_bar:
            return

        bar_r = QRectF(pr.left(), pr.top() - TOP_BAR_H + 4, pr.width(), TOP_BAR_H - 8)
        bp = QPainterPath()
        bp.addRoundedRect(bar_r, 10, 10)
        p.fillPath(bp, QColor(15, 23, 42, 235))
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.setPen(QPen(QColor("#334155"), 1.0))
        p.drawPath(bp)

        # Status dot + title / hint / recording timer
        dot_x = bar_r.left() + 12
        dot_y = bar_r.center().y()
        if self.recorder is not None and self.recorder.alive:
            p.setBrush(QColor("#ef4444"))
            p.setPen(Qt.PenStyle.NoPen)
            p.drawEllipse(QPointF(dot_x, dot_y), 4.5, 4.5)
            secs = int(self.recorder.seconds)
            status_txt = f"REC {secs // 60:02d}:{secs % 60:02d}"
        elif time.monotonic() < self._hint_until and self._hint_text:
            p.setBrush(QColor("#3b82f6"))
            p.setPen(Qt.PenStyle.NoPen)
            p.drawEllipse(QPointF(dot_x, dot_y), 4.0, 4.0)
            status_txt = self._hint_text
        elif self.backend.active_mode:
            p.setBrush(QColor("#22c55e"))
            p.setPen(Qt.PenStyle.NoPen)
            p.drawEllipse(QPointF(dot_x, dot_y), 4.0, 4.0)
            fps = self.backend.decoder.fps
            status_txt = f"{self.backend.active_label} • {fps:.0f}fps" if fps >= 1 else self.backend.active_label
        else:
            p.setBrush(QColor("#64748b"))
            p.setPen(Qt.PenStyle.NoPen)
            p.drawEllipse(QPointF(dot_x, dot_y), 4.0, 4.0)
            status_txt = "PocketDeck"

        # Right-side compact pill buttons
        pills = [
            ("close", "✕", 24),
            ("min", "—", 24),
            ("files", "Tệp ⇄", 48),
            ("connect", "Kết nối", 54),
        ]
        cur_right = bar_r.right() - 6
        p.setFont(_ui_font(11, bold=True))
        for act_id, txt, w in pills:
            r = QRectF(cur_right - w, bar_r.top() + 4, w, bar_r.height() - 8)
            cur_right -= w + 4
            rp = QPainterPath()
            rp.addRoundedRect(r, 6, 6)
            bg = QColor("#dc2626") if act_id == "close" and self.hovering else QColor("#1e293b")
            if act_id == "files" and self.backend.active_mode:
                bg = QColor("#2563eb")
            p.fillPath(rp, bg)
            p.setPen(QColor("#f8fafc"))
            p.drawText(r, Qt.AlignmentFlag.AlignCenter, txt)
            self._top_hits.append((r, act_id))

        # Left status text clipped before buttons
        avail_w = max(40.0, cur_right - (bar_r.left() + 22))
        p.setFont(_ui_font(12, bold=True))
        p.setPen(QColor("#e2e8f0"))
        fm = QFontMetrics(p.font())
        elided = fm.elidedText(status_txt, Qt.TextElideMode.ElideRight, int(avail_w))
        p.drawText(
            QRectF(bar_r.left() + 22, bar_r.top(), avail_w, bar_r.height()),
            Qt.AlignmentFlag.AlignVCenter | Qt.AlignmentFlag.AlignLeft,
            elided,
        )

    def _paint_nav_bar(self, p: QPainter, pr: QRectF) -> None:
        self._nav_hits.clear()
        nav_r = QRectF(pr.left(), pr.bottom() + 4, pr.width(), NAV_BAR_H - 6)
        np_path = QPainterPath()
        np_path.addRoundedRect(nav_r, 10, 10)
        p.fillPath(np_path, QColor(15, 23, 42, 235))
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.setPen(QPen(QColor("#334155"), 1.0))
        p.drawPath(np_path)

        third = nav_r.width() / 3.0
        items = [
            ("back", "◁ Quay lại"),
            ("home", "○ Home"),
            ("recents", "□ Đa nhiệm"),
        ]
        p.setFont(_ui_font(11, bold=True))
        p.setPen(QColor("#cbd5e1"))
        for idx, (act, label) in enumerate(items):
            r = QRectF(nav_r.left() + idx * third, nav_r.top(), third, nav_r.height())
            short_lbl = label.split()[0] if third < 95 else label
            p.drawText(r, Qt.AlignmentFlag.AlignCenter, short_lbl)
            self._nav_hits.append((r, act))

    def _paint_stats_overlay(self, p: QPainter, sr: QRectF) -> None:
        dec = self.backend.decoder
        sw, sh = dec.source_size if dec.source_size != (0, 0) else self.src_size
        lines = [
            f"Chế độ: {self.backend.active_mode.upper() or 'IDLE'} ({self.backend.active_label or '—'})",
            f"Nguồn H.264: {sw}×{sh}  |  {dec.fps:.1f} FPS  |  {dec.bitrate_mbps:.2f} Mbps",
            f"Đã giải mã: {dec.decoded_frames} khung  |  Rơi gói: {dec.dropped_packets}",
            f"Điều khiển: {'BẬT' if self.control_enabled else 'TẮT'}  |  Âm thanh: {'TẮT TIẾNG' if self.backend.audio_player.muted else 'BẬT'}",
        ]
        box_r = QRectF(sr.left() + 10, sr.top() + 30, min(sr.width() - 20, 360), 86)
        bp = QPainterPath()
        bp.addRoundedRect(box_r, 8, 8)
        p.fillPath(bp, QColor(2, 6, 23, 215))
        p.setPen(QPen(QColor("#334155"), 1.0))
        p.drawPath(bp)

        p.setFont(_mono_font(11, bold=False))
        p.setPen(QColor("#e2e8f0"))
        for i, ln in enumerate(lines):
            p.drawText(
                QRectF(box_r.left() + 10, box_r.top() + 8 + i * 18, box_r.width() - 20, 18),
                Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter,
                ln,
            )

    # ------------------------------------------------------------------ Video & Session Callbacks
    def _on_video_frame(self, arr: np.ndarray | None, src_w: int, src_h: int) -> None:
        if src_w > 0 and src_h > 0:
            self.src_size = (src_w, src_h)
            new_aspect = src_w / src_h
            if abs(new_aspect - self.aspect) > 0.04:
                self.aspect = new_aspect
                if not self.fullscreen:
                    # Swap height seamlessly on portrait <-> landscape rotation
                    if src_w > src_h and self.phone_height > 620:
                        self._resize_to_phone_height(max(420, int(round(self.phone_height * 0.62))))
                    else:
                        self._resize_to_phone_height(self.phone_height)

        if arr is not None:
            self._frame_buf = arr
            h, w, _ = arr.shape
            self._qimage = QImage(arr.data, w, h, w * 4, QImage.Format.Format_ARGB32)
        self.update()

    def _on_session_connected(self, mode: str, label: str, w: int, h: int, open_files: bool) -> None:
        if w > 0 and h > 0:
            self.src_size = (w, h)
            self.aspect = w / h
            if not self.fullscreen:
                self._resize_to_phone_height(self.phone_height)
        if self.settings.get("keep_awake", True):
            _set_keep_awake(True)
        self.show_hint(f"Đã kết nối {label} ({mode.upper()})")
        if open_files:
            self.open_file_transfer_window()
        self.update()

    def _on_session_disconnected(self, _reason: str) -> None:
        self._qimage = None
        self._frame_buf = None
        self.aspect = DEFAULT_ASPECT
        if not self.fullscreen:
            self._resize_to_phone_height(self.phone_height)
        _set_keep_awake(False)
        if self.recorder is not None:
            self.toggle_recording()
        self.update()

    def _on_transfer_progress(self, prog: TransferProgress) -> None:
        if self.file_win is not None:
            self.file_win.on_transfer_progress_threadsafe(prog)

    def _on_chat_received(self, sender: str, text: str) -> None:
        if self.file_win is None:
            self.open_file_transfer_window()
        if self.file_win is not None:
            self.file_win.on_incoming_chat_threadsafe(sender, text)

    def _on_remote_clipboard(self, text: str) -> None:
        cb = QGuiApplication.clipboard()
        if cb is not None and cb.text() != text:
            cb.setText(text)
            self.show_hint("Đã đồng bộ Clipboard từ điện thoại")

    def _on_periodic_tick(self) -> None:
        if self.recorder is not None or self.show_stats or (time.monotonic() < self._hint_until):
            self.update()

    def show_hint(self, message: str, duration: float = 3.0) -> None:
        self._hint_text = message
        self._hint_until = time.monotonic() + duration
        self.update()

    # ------------------------------------------------------------------ Dialogs & Features
    def open_connect_dialog(self, prefer_files: bool = False) -> None:
        dlg = ConnectDialog(
            settings=self.settings,
            discovery=self.backend.discovery,
            adb=self.backend.adb,
            host_port_fn=lambda: self.backend.host_port,
            on_connect_decklink=lambda target, pw, files: self.backend.submit(
                self.backend.connect_decklink_async(target, pw, files)
            ),
            on_connect_adb=lambda serial, files: self.backend.submit(
                self.backend.connect_adb_async(serial, files)
            ),
            parent=self,
        )
        if prefer_files:
            dlg.rad_files.setChecked(True)
        dlg.exec()

    def open_file_transfer_window(self) -> None:
        if not self.backend.active_mode:
            self.open_connect_dialog(prefer_files=True)
            return

        if self.file_win is not None and self.file_win.isVisible():
            self.file_win.raise_()
            self.file_win.activateWindow()
            return

        local_fs = LocalFileSystem()
        dl_dir = str(self.settings["download_dir"])

        if self.backend.active_mode == "decklink" and self.backend.decklink_session is not None:
            sess = self.backend.decklink_session
            self.file_win = FileTransferWindow(
                peer_label=self.backend.active_label,
                local_fs=local_fs,
                remote_fs=sess.files.remote_fs(),
                submit_coro=self.backend.submit,
                send_files_fn=lambda paths, rdir: sess.files.send_items(paths, rdir),
                pull_files_fn=lambda rpaths, ldir: sess.files.request_pull(rpaths, ldir),
                pause_fn=sess.files.pause_transfer,
                resume_fn=sess.files.resume_transfer,
                cancel_fn=sess.files.cancel_transfer,
                send_chat_fn=lambda txt: sess.send_chat(str(self.settings["name"]), txt),
                initial_local_dir=dl_dir,
                parent=None,
            )
        elif self.backend.active_mode == "adb" and self.backend.adb_fs is not None and self.backend.adb_xfer is not None:
            adb_fs = self.backend.adb_fs
            adb_xfer = self.backend.adb_xfer
            self.file_win = FileTransferWindow(
                peer_label=self.backend.active_label,
                local_fs=local_fs,
                remote_fs=adb_fs,
                submit_coro=self.backend.submit,
                send_files_fn=lambda paths, rdir: adb_xfer.send_items(paths, rdir),
                pull_files_fn=lambda rpaths, ldir: adb_xfer.pull_items(rpaths, ldir),
                initial_local_dir=dl_dir,
                initial_remote_dir="/sdcard/Download",
                parent=None,
            )
        else:
            return

        self.file_win.show()
        self.file_win.raise_()
        self.file_win.activateWindow()

    def open_wizard_dialog(self) -> None:
        dlg = SetupWizardDialog(
            adb=self.backend.adb,
            on_connect_adb=lambda serial: self.backend.submit(self.backend.connect_adb_async(serial, False)),
            parent=self,
        )
        dlg.exec()

    def open_settings_dialog(self) -> None:
        dlg = SettingsDialog(self.settings, parent=self)
        if dlg.exec():
            self.backend.discovery.update_info(device_name=str(self.settings["name"]))
            self._resize_to_phone_height(self.phone_height)
            self.update()

    def save_screenshot(self, to_clipboard_only: bool = False) -> Path | None:
        frame = self.backend.decoder.last_frame
        if frame is None:
            self.show_hint("Chưa có hình ảnh trực tiếp để chụp")
            return None
        pics_dir, _ = default_media_dirs()
        stamp = time.strftime("%Y-%m-%d %H-%M-%S")
        out = pics_dir / f"PocketDeck {stamp}.png"
        if not to_clipboard_only:
            save_frame_png(frame, out)
        if self._qimage is not None:
            cb = QGuiApplication.clipboard()
            if cb is not None:
                cb.setImage(self._qimage.copy())
        self.show_hint("Đã chép ảnh màn hình vào Clipboard" if to_clipboard_only else f"Đã lưu ảnh: {out.name}")
        return None if to_clipboard_only else out

    def toggle_recording(self) -> None:
        if self.recorder is not None:
            rec, self.recorder = self.recorder, None
            self.backend.decoder.recorder = None
            self.backend.audio_player.recorder = None
            saved = rec.stop()
            if saved:
                self.show_hint(f"Đã lưu video: {saved[-1].name}")
            self.update()
            return

        if self.backend.decoder.last_frame is None:
            self.show_hint("Hãy kết nối màn hình điện thoại trước khi quay video")
            return
        _, vids_dir = default_media_dirs()
        rec = Recorder(vids_dir, prefix="PocketDeck", audio=bool(self.settings.get("audio", True)))
        self.recorder = rec
        self.backend.decoder.recorder = rec
        self.backend.audio_player.recorder = rec
        self.show_hint("Đang quay màn hình MP4 (Ctrl+R để dừng)")
        self.update()

    def toggle_fullscreen(self) -> None:
        self.fullscreen = not self.fullscreen
        if self.fullscreen:
            self.showFullScreen()
        else:
            self.showNormal()
            self._resize_to_phone_height(self.phone_height)
        self.update()

    def toggle_always_on_top(self) -> None:
        cur = not bool(self.settings.get("always_on_top", False))
        self.settings["always_on_top"] = cur
        self.settings.save()
        pos = self.pos()
        self._apply_window_flags()
        self.move(pos)
        self.show()
        self.show_hint("Đã ghim cửa sổ luôn nổi" if cur else "Đã bỏ ghim cửa sổ")

    def _save_window_grab(self, filename: str) -> None:
        if self.grab_dir is None:
            return
        self.grab_dir.mkdir(parents=True, exist_ok=True)
        pm = self.grab()
        pm.save(str(self.grab_dir / filename), "PNG")

    # ------------------------------------------------------------------ Mouse / Touch / Wheel / Drag-Drop
    def enterEvent(self, event) -> None:  # noqa: N802
        self.hovering = True
        self.update()
        super().enterEvent(event)

    def leaveEvent(self, event) -> None:  # noqa: N802
        self.hovering = False
        self.update()
        super().leaveEvent(event)

    def _screen_norm(self, pos: QPointF) -> tuple[float, float]:
        sr = self._screen_rect()
        nx = max(0.0, min(1.0, (pos.x() - sr.left()) / max(1.0, sr.width())))
        ny = max(0.0, min(1.0, (pos.y() - sr.top()) / max(1.0, sr.height())))
        return nx, ny

    def mousePressEvent(self, event) -> None:  # noqa: N802
        pos = event.position()
        if event.button() == Qt.MouseButton.RightButton:
            self._show_context_menu(event.globalPosition().toPoint())
            return
        if event.button() != Qt.MouseButton.LeftButton:
            return

        # 1. Top bar buttons
        for r, act in self._top_hits:
            if r.contains(pos):
                self._trigger_ui_action(act)
                return

        # 2. Bottom Android nav bar buttons
        for r, nav in self._nav_hits:
            if r.contains(pos):
                self.backend.send_nav(nav)
                self._ripples.append((r.center(), time.monotonic()))
                self.update()
                return

        # 3. Standby screen buttons (when no video stream active)
        if self._qimage is None:
            for r, act in self._standby_hits:
                if r.contains(pos):
                    self._trigger_ui_action(act)
                    return

        # 4. Live touch on phone screen when connected & control enabled (unless Alt held to drag window)
        sr = self._screen_rect()
        alt_drag = bool(event.modifiers() & Qt.KeyboardModifier.AltModifier)
        if self._qimage is not None and self.control_enabled and not alt_drag and sr.contains(pos):
            self._touching = True
            self._touch_pts = [pos]
            self._ripples.append((pos, time.monotonic()))
            nx, ny = self._screen_norm(pos)
            self.backend.send_touch("touch_down", nx, ny, *self.src_size)
            self.update()
            return

        # 5. Otherwise drag floating window
        if not self.fullscreen:
            self._win_drag_offset = event.globalPosition().toPoint() - self.frameGeometry().topLeft()

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        pos = event.position()
        if self._touching:
            self._touch_pts.append(pos)
            if len(self._touch_pts) > 24:
                self._touch_pts = self._touch_pts[-24:]
            nx, ny = self._screen_norm(pos)
            self.backend.send_touch("touch_move", nx, ny, *self.src_size)
            self.update()
            return
        if self._win_drag_offset is not None and not self.fullscreen:
            self.move(event.globalPosition().toPoint() - self._win_drag_offset)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if event.button() != Qt.MouseButton.LeftButton:
            return
        if self._touching:
            self._touching = False
            pos = event.position()
            nx, ny = self._screen_norm(pos)
            self.backend.send_touch("touch_up", nx, ny, *self.src_size)
            self._touch_pts.clear()
            self.update()
        if self._win_drag_offset is not None:
            self._win_drag_offset = None
            self.settings["window_x"] = self.x()
            self.settings["window_y"] = self.y()
            self.settings.save()

    def wheelEvent(self, event) -> None:  # noqa: N802
        delta_y = event.angleDelta().y()
        delta_x = event.angleDelta().x()
        if event.modifiers() & Qt.KeyboardModifier.ControlModifier:
            if not self.fullscreen and delta_y != 0:
                factor = math.pow(1.06, delta_y / 120.0)
                self._resize_to_phone_height(int(round(self.phone_height * factor)))
                self.settings["window_h"] = self.phone_height
                self.settings.save()
            return

        if self._qimage is not None and self.control_enabled and self._screen_rect().contains(event.position()):
            nx, ny = self._screen_norm(event.position())
            self.backend.send_scroll(nx, ny, *self.src_size, hscroll=delta_x / 120.0, vscroll=delta_y / 120.0)

    def _trigger_ui_action(self, act: str) -> None:
        if act == "close":
            self.close()
        elif act == "min":
            self.showMinimized()
        elif act == "connect":
            self.open_connect_dialog()
        elif act == "files":
            self.open_file_transfer_window()
        elif act == "wizard":
            self.open_wizard_dialog()
        elif act == "settings":
            self.open_settings_dialog()
        elif act == "copy_creds":
            cb = QGuiApplication.clipboard()
            if cb is not None:
                cb.setText(f"ID: {self.settings.formatted_id} | Mật khẩu: {self.settings.active_password()}")
                self.show_hint("Đã sao chép ID & Mật khẩu vào Clipboard")

    # ------------------------------------------------------------------ Keyboard & Vietnamese IME
    def inputMethodEvent(self, event) -> None:  # noqa: N802
        committed = event.commitString()
        if committed and self.control_enabled and self.backend.active_mode:
            self.backend.send_text(committed)
        event.accept()

    def keyPressEvent(self, event) -> None:  # noqa: N802
        key = event.key()
        mods = event.modifiers()
        ctrl = bool(mods & Qt.KeyboardModifier.ControlModifier)
        shift = bool(mods & Qt.KeyboardModifier.ShiftModifier)

        if key == Qt.Key.Key_F11:
            self.toggle_fullscreen()
            return
        if key == Qt.Key.Key_Escape and self.fullscreen:
            self.toggle_fullscreen()
            return

        if ctrl:
            if key == Qt.Key.Key_F:
                self.open_file_transfer_window()
                return
            if key == Qt.Key.Key_K:
                self.open_connect_dialog()
                return
            if key == Qt.Key.Key_S:
                self.save_screenshot(to_clipboard_only=shift)
                return
            if key == Qt.Key.Key_R:
                self.toggle_recording()
                return
            if key == Qt.Key.Key_M:
                self.backend.audio_player.muted = not self.backend.audio_player.muted
                self.show_hint("Đã tắt tiếng điện thoại" if self.backend.audio_player.muted else "Đã bật tiếng điện thoại")
                return
            if key == Qt.Key.Key_I:
                self.show_stats = not self.show_stats
                self.update()
                return
            if key in (Qt.Key.Key_Plus, Qt.Key.Key_Equal):
                self._resize_to_phone_height(int(self.phone_height * 1.08))
                return
            if key == Qt.Key.Key_Minus:
                self._resize_to_phone_height(int(self.phone_height / 1.08))
                return
            if key == Qt.Key.Key_V and self.control_enabled and self.backend.active_mode:
                cb = QGuiApplication.clipboard()
                if cb is not None and cb.text():
                    self.backend.send_text(cb.text()[:2000])
                    self.show_hint("Đã dán văn bản sang điện thoại")
                return

        if not self.control_enabled or not self.backend.active_mode:
            super().keyPressEvent(event)
            return

        if key == Qt.Key.Key_Backspace:
            self.backend.send_nav("backspace")
        elif key in (Qt.Key.Key_Return, Qt.Key.Key_Enter):
            self.backend.send_nav("enter")
        elif key == Qt.Key.Key_Escape:
            self.backend.send_nav("back")
        elif key == Qt.Key.Key_Home:
            self.backend.send_nav("home")
        else:
            txt = event.text()
            if txt and all(ch.isprintable() for ch in txt):
                self.backend.send_text(txt)

    # ------------------------------------------------------------------ Drag & Drop Files / APK
    def dragEnterEvent(self, event) -> None:  # noqa: N802
        if event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event) -> None:  # noqa: N802
        paths = [u.toLocalFile() for u in event.mimeData().urls() if u.isLocalFile()]
        if not paths:
            return
        event.acceptProposedAction()

        if not self.backend.active_mode:
            self.show_hint("Hãy kết nối tới điện thoại trước khi thả file")
            self.open_connect_dialog()
            return

        # Special convenience: dropping a single .apk over ADB installs it immediately!
        if len(paths) == 1 and paths[0].lower().endswith(".apk") and self.backend.active_mode == "adb":
            apk_file = Path(paths[0])
            self.show_hint(f"Đang cài đặt {apk_file.name} lên điện thoại...")

            async def _install_dropped_apk() -> None:
                try:
                    await asyncio.to_thread(self.backend.adb.install_apk, self.backend.adb_serial, apk_file)
                    self.bridge.hint.emit(f"✓ Đã cài đặt {apk_file.name} thành công!")
                except Exception as exc:
                    self.bridge.hint.emit(f"Lỗi cài APK: {exc}")

            self.backend.submit(_install_dropped_apk())
            return

        # Otherwise send all dropped files/folders straight to the partner!
        if self.backend.active_mode == "decklink" and self.backend.decklink_session is not None:
            self.show_hint(f"Đang gửi {len(paths)} mục sang {self.backend.active_label}...")
            self.backend.submit(self.backend.decklink_session.files.send_items(paths, ""))
        elif self.backend.active_mode == "adb" and self.backend.adb_xfer is not None:
            self.show_hint(f"Đang đẩy {len(paths)} mục qua ADB...")
            self.backend.submit(self.backend.adb_xfer.send_items(paths, "/sdcard/Download/PocketDeck"))

    # ------------------------------------------------------------------ Context Menu & System Tray
    def _build_menu(self, parent_widget: QWidget | None = None) -> QMenu:
        menu = QMenu(parent_widget or self)
        menu.setStyleSheet(APP_QSS)

        act_conn = QAction("🔌 Kết nối Điện thoại / Đối tác... (Ctrl+K)", menu)
        act_conn.triggered.connect(self.open_connect_dialog)
        menu.addAction(act_conn)

        act_files = QAction("📂 Truyền File 2 chiều & Chat kiểu UltraViewer (Ctrl+F)", menu)
        act_files.triggered.connect(self.open_file_transfer_window)
        menu.addAction(act_files)

        if self.backend.active_mode:
            act_disc = QAction(f"❌ Ngắt kết nối ({self.backend.active_label})", menu)
            act_disc.triggered.connect(lambda: self.backend.submit(self.backend.disconnect_active()))
            menu.addAction(act_disc)

        menu.addSeparator()

        act_ctrl = QAction("🖱️ Bật điều khiển chuột & bàn phím", menu)
        act_ctrl.setCheckable(True)
        act_ctrl.setChecked(self.control_enabled)
        act_ctrl.triggered.connect(lambda v: setattr(self, "control_enabled", bool(v)))
        menu.addAction(act_ctrl)

        act_shot = QAction("📸 Chụp màn hình PNG (Ctrl+S)", menu)
        act_shot.triggered.connect(lambda: self.save_screenshot(False))
        menu.addAction(act_shot)

        rec_lbl = "⏹️ Dừng quay màn hình MP4 (Ctrl+R)" if self.recorder else "🔴 Quay video màn hình MP4 (Ctrl+R)"
        act_rec = QAction(rec_lbl, menu)
        act_rec.triggered.connect(self.toggle_recording)
        menu.addAction(act_rec)

        act_rot = QAction("🔄 Xoay màn hình điện thoại", menu)
        act_rot.triggered.connect(lambda: self.backend.send_nav("rotate"))
        menu.addAction(act_rot)

        menu.addSeparator()

        act_top = QAction("📌 Luôn nổi trên cùng (Always on Top)", menu)
        act_top.setCheckable(True)
        act_top.setChecked(bool(self.settings.get("always_on_top", False)))
        act_top.triggered.connect(self.toggle_always_on_top)
        menu.addAction(act_top)

        act_stats = QAction("📊 Hiện thông số luồng H.264 (Ctrl+I)", menu)
        act_stats.setCheckable(True)
        act_stats.setChecked(self.show_stats)
        act_stats.triggered.connect(lambda v: (setattr(self, "show_stats", bool(v)), self.update()))
        menu.addAction(act_stats)

        op_menu = menu.addMenu("🌗 Độ trong suốt cửa sổ")
        for pct in (100, 90, 75, 50):
            val = pct / 100.0
            a = QAction(f"{pct}%", op_menu)
            a.triggered.connect(lambda _c=False, v=val: self._set_opacity(v))
            op_menu.addAction(a)

        act_wiz = QAction("🛠️ Hướng dẫn USB / Cài PocketDeck.apk...", menu)
        act_wiz.triggered.connect(self.open_wizard_dialog)
        menu.addAction(act_wiz)

        act_set = QAction("⚙️ Cài đặt...", menu)
        act_set.triggered.connect(self.open_settings_dialog)
        menu.addAction(act_set)

        menu.addSeparator()
        act_quit = QAction("Thoát PocketDeck", menu)
        act_quit.triggered.connect(self._quit_app)
        menu.addAction(act_quit)
        return menu

    def _set_opacity(self, val: float) -> None:
        self.settings["opacity"] = val
        self.settings.save()
        self.setWindowOpacity(val)

    def _show_context_menu(self, global_pos: QPoint) -> None:
        self._build_menu().exec(global_pos)

    def _init_tray(self) -> None:
        tray = QSystemTrayIcon(make_app_icon(), self)
        tray.setToolTip(f"{APP_NAME} — ID: {self.settings.formatted_id}")
        tray.setContextMenu(self._build_menu())
        tray.activated.connect(self._on_tray_activated)
        tray.show()
        self.tray = tray

    def _on_tray_activated(self, reason: QSystemTrayIcon.ActivationReason) -> None:
        if reason in (QSystemTrayIcon.ActivationReason.Trigger, QSystemTrayIcon.ActivationReason.DoubleClick):
            self.showNormal()
            self.raise_()
            self.activateWindow()

    def _quit_app(self) -> None:
        if self.tray is not None:
            self.tray.hide()
        self.close()
        app = QApplication.instance()
        if app is not None:
            app.quit()

    def closeEvent(self, event) -> None:  # noqa: N802
        _set_keep_awake(False)
        self._tick_timer.stop()
        if self.recorder is not None:
            self.recorder.stop()
            self.recorder = None
        if self.file_win is not None:
            self.file_win.close()
        self.backend.shutdown()
        super().closeEvent(event)
