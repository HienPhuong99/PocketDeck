"""scrcpy 5.x protocol client: live H.264 video, raw PCM audio, real-time touch/scroll, & Vietnamese IME."""
from __future__ import annotations

import asyncio
import logging
import secrets
import socket
import struct
import subprocess
from typing import Callable

from .manager import CREATE_NO_WINDOW, AdbManager

log = logging.getLogger(__name__)

# scrcpy ControlMessage types (verified against Genymobile/scrcpy v5.0.1 ControlMessage.java)
SC_INJECT_KEYCODE = 0
SC_INJECT_TEXT = 1
SC_INJECT_TOUCH = 2
SC_INJECT_SCROLL = 3
SC_BACK_OR_SCREEN_ON = 4
SC_EXPAND_NOTIFICATIONS = 5
SC_EXPAND_SETTINGS = 6
SC_COLLAPSE_PANELS = 7
SC_GET_CLIPBOARD = 8
SC_SET_CLIPBOARD = 9
SC_SET_DISPLAY_POWER = 10
SC_ROTATE_DEVICE = 11

# Android MotionEvent / KeyEvent actions
AMOTION_EVENT_ACTION_DOWN = 0
AMOTION_EVENT_ACTION_UP = 1
AMOTION_EVENT_ACTION_MOVE = 2

AKEY_EVENT_ACTION_DOWN = 0
AKEY_EVENT_ACTION_UP = 1

# Android KeyCodes
AKEYCODE_HOME = 3
AKEYCODE_BACK = 4
AKEYCODE_VOLUME_UP = 24
AKEYCODE_VOLUME_DOWN = 25
AKEYCODE_POWER = 26
AKEYCODE_ENTER = 66
AKEYCODE_DEL = 67
AKEYCODE_APP_SWITCH = 187

POINTER_ID_MOUSE = 0xFFFFFFFFFFFFFFFE  # -2 signed 64-bit as used by scrcpy

# Streamer v5.0.1 flags (verified against device/Streamer.java)
PACKET_FLAG_SESSION = 1 << 63
PACKET_FLAG_CONFIG = 1 << 62
PACKET_FLAG_KEY_FRAME = 1 << 61
PTS_MASK = (1 << 61) - 1

CODEC_ID_H264 = 0x68323634  # 'h264'
CODEC_ID_RAW = 0x00726177  # '\0raw'


def _u16_fixed_point(val: float) -> int:
    clamped = max(0.0, min(1.0, val))
    return 0xFFFF if clamped >= 1.0 else int(round(clamped * 65536.0)) & 0xFFFF


def _i16_scroll_fixed(scroll_units: float) -> int:
    # ControlMessageReader multiplies i16FixedPointToFloat(short) by 16 -> range [-16, 16]
    norm = max(-1.0, min(1.0, scroll_units / 16.0))
    scaled = int(round(norm * 32767.0))
    return max(-32768, min(32767, scaled))


class ScrcpyControlSerializer:
    """Pure binary serializer for scrcpy v5.x ControlMessage frames."""

    @staticmethod
    def inject_touch(
        action: int,
        x: int,
        y: int,
        screen_w: int,
        screen_h: int,
        pressure: float = 1.0,
        action_button: int = 1,
        buttons: int = 1,
        pointer_id: int = POINTER_ID_MOUSE,
    ) -> bytes:
        p_u16 = _u16_fixed_point(pressure if action != AMOTION_EVENT_ACTION_UP else 0.0)
        btns = 0 if action == AMOTION_EVENT_ACTION_UP else buttons
        act_btn = action_button if action in (AMOTION_EVENT_ACTION_DOWN, AMOTION_EVENT_ACTION_UP) else 0
        return struct.pack(
            ">BBQiiHHHii",
            SC_INJECT_TOUCH,
            action & 0xFF,
            pointer_id & 0xFFFFFFFFFFFFFFFF,
            int(x),
            int(y),
            max(1, int(screen_w)) & 0xFFFF,
            max(1, int(screen_h)) & 0xFFFF,
            p_u16,
            act_btn,
            btns,
        )

    @staticmethod
    def inject_scroll(
        x: int,
        y: int,
        screen_w: int,
        screen_h: int,
        hscroll: float = 0.0,
        vscroll: float = 0.0,
        buttons: int = 0,
    ) -> bytes:
        return struct.pack(
            ">BiiHHhhi",
            SC_INJECT_SCROLL,
            int(x),
            int(y),
            max(1, int(screen_w)) & 0xFFFF,
            max(1, int(screen_h)) & 0xFFFF,
            _i16_scroll_fixed(hscroll),
            _i16_scroll_fixed(vscroll),
            buttons,
        )

    @staticmethod
    def inject_keycode(action: int, keycode: int, repeat: int = 0, meta_state: int = 0) -> bytes:
        return struct.pack(">BBiii", SC_INJECT_KEYCODE, action & 0xFF, keycode, repeat, meta_state)

    @classmethod
    def press_keycode(cls, keycode: int, meta_state: int = 0) -> bytes:
        return cls.inject_keycode(AKEY_EVENT_ACTION_DOWN, keycode, 0, meta_state) + cls.inject_keycode(
            AKEY_EVENT_ACTION_UP, keycode, 0, meta_state
        )

    @staticmethod
    def inject_ascii_text(text: str) -> bytes:
        raw = text.encode("utf-8")[:300]
        return struct.pack(">BI", SC_INJECT_TEXT, len(raw)) + raw

    @staticmethod
    def set_clipboard(text: str, paste: bool = False, sequence: int = 0) -> bytes:
        raw = text.encode("utf-8")[: (1 << 18) - 16]
        return struct.pack(">BQ?I", SC_SET_CLIPBOARD, sequence & 0xFFFFFFFFFFFFFFFF, paste, len(raw)) + raw

    @classmethod
    def inject_text_smart(cls, text: str, sequence: int = 1) -> bytes:
        """Inject ASCII directly via key-char injector, or Unicode/Vietnamese cleanly via clipboard-paste."""
        if not text:
            return b""
        if all(32 <= ord(ch) < 127 for ch in text) and len(text) <= 280:
            return cls.inject_ascii_text(text)
        return cls.set_clipboard(text, paste=True, sequence=sequence)

    @staticmethod
    def back_or_screen_on() -> bytes:
        return struct.pack(">BB", SC_BACK_OR_SCREEN_ON, AKEY_EVENT_ACTION_DOWN) + struct.pack(
            ">BB", SC_BACK_OR_SCREEN_ON, AKEY_EVENT_ACTION_UP
        )

    @staticmethod
    def expand_notifications() -> bytes:
        return struct.pack(">B", SC_EXPAND_NOTIFICATIONS)

    @staticmethod
    def expand_settings() -> bytes:
        return struct.pack(">B", SC_EXPAND_SETTINGS)

    @staticmethod
    def collapse_panels() -> bytes:
        return struct.pack(">B", SC_COLLAPSE_PANELS)

    @staticmethod
    def set_display_power(on: bool) -> bytes:
        return struct.pack(">B?", SC_SET_DISPLAY_POWER, on)

    @staticmethod
    def rotate_device() -> bytes:
        return struct.pack(">B", SC_ROTATE_DEVICE)


def parse_scrcpy_stream_header(hdr12: bytes) -> tuple[str, int, int, bool, bool]:
    """Parse 12-byte scrcpy v5.x stream header.

    Returns:
      - ("session", width, height, False, False) when PACKET_FLAG_SESSION is set
      - ("frame", pts_us, packet_size, is_config, is_keyframe) otherwise
    """
    if len(hdr12) != 12:
        raise ValueError("Expected 12-byte scrcpy stream header")
    u64_be = struct.unpack_from(">Q", hdr12, 0)[0]
    if u64_be & PACKET_FLAG_SESSION:
        _, width, height = struct.unpack(">III", hdr12)
        return "session", width, height, False, False
    packet_size = struct.unpack_from(">I", hdr12, 8)[0]
    is_config = bool(u64_be & PACKET_FLAG_CONFIG)
    is_key = bool(u64_be & PACKET_FLAG_KEY_FRAME)
    pts_us = u64_be & PTS_MASK
    return "frame", pts_us, packet_size, is_config, is_key


def _pick_free_tcp_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


class ScrcpySession:
    """Manages an active ADB tunnel + scrcpy-server process + async video/audio/control sockets."""

    SERVER_REMOTE_PATH = "/data/local/tmp/scrcpy-server-pocketdeck.jar"
    SERVER_VERSION = "5.0.1"

    def __init__(
        self,
        adb: AdbManager,
        serial: str,
        max_size: int = 1920,
        max_fps: int = 60,
        bitrate_bps: int = 8_000_000,
        enable_audio: bool = True,
        on_video: Callable[[bytes, bool, int, int], None] | None = None,
        on_audio_pcm: Callable[[bytes], None] | None = None,
        on_clipboard: Callable[[str], None] | None = None,
        on_disconnected: Callable[[str], None] | None = None,
    ):
        self.adb = adb
        self.serial = serial
        self.max_size = max_size
        self.max_fps = max_fps
        self.bitrate_bps = bitrate_bps
        self.enable_audio = enable_audio
        self.on_video = on_video
        self.on_audio_pcm = on_audio_pcm
        self.on_clipboard = on_clipboard
        self.on_disconnected = on_disconnected

        self.device_name = serial
        self.screen_width = 1080
        self.screen_height = 2400
        self._scid = secrets.randbelow(0x7FFFFFFF)
        self._local_port = 0
        self._proc: subprocess.Popen[bytes] | None = None
        self._ctrl_writer: asyncio.StreamWriter | None = None
        self._tasks: list[asyncio.Task[None]] = []
        self._closed = False
        self._clip_seq = 1

    async def start(self) -> None:
        if self.adb.scrcpy_server_path is None or not self.adb.scrcpy_server_path.is_file():
            raise FileNotFoundError("Không tìm thấy scrcpy-server")
        if self.adb.adb_path is None:
            raise FileNotFoundError("Không tìm thấy adb.exe")

        await asyncio.to_thread(
            self.adb.run,
            ["push", str(self.adb.scrcpy_server_path), self.SERVER_REMOTE_PATH],
            self.serial,
            25.0,
        )
        self._local_port = _pick_free_tcp_port()
        sock_name = f"localabstract:scrcpy_{self._scid:08x}"
        await asyncio.to_thread(
            self.adb.run,
            ["forward", f"tcp:{self._local_port}", sock_name],
            self.serial,
            10.0,
        )

        server_cmd = [
            str(self.adb.adb_path),
            "-s",
            self.serial,
            "shell",
            f"CLASSPATH={self.SERVER_REMOTE_PATH}",
            "app_process",
            "/",
            "com.genymobile.scrcpy.Server",
            self.SERVER_VERSION,
            f"scid={self._scid:08x}",
            "log_level=info",
            "tunnel_forward=true",
            "video=true",
            "video_codec=h264",
            f"audio={'true' if self.enable_audio else 'false'}",
            "audio_codec=raw",
            "control=true",
            f"max_size={self.max_size}",
            f"max_fps={self.max_fps}",
            f"video_bit_rate={self.bitrate_bps}",
        ]
        self._proc = subprocess.Popen(
            server_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            creationflags=CREATE_NO_WINDOW,
        )

        # Connect sockets in order: video (reads dummy byte), audio, control
        v_reader, v_writer = await self._connect_tunnel_with_retry()
        a_reader: asyncio.StreamReader | None = None
        a_writer: asyncio.StreamWriter | None = None
        if self.enable_audio:
            a_reader, a_writer = await asyncio.open_connection("127.0.0.1", self._local_port, limit=2 * 1024 * 1024)
        c_reader, c_writer = await asyncio.open_connection("127.0.0.1", self._local_port, limit=1024 * 1024)
        self._ctrl_writer = c_writer

        # Device metadata: 64-byte UTF-8 device name on first socket
        raw_name = await asyncio.wait_for(v_reader.readexactly(64), timeout=8.0)
        self.device_name = raw_name.split(b"\x00", 1)[0].decode("utf-8", "replace").strip() or self.serial

        # Video codec id (4 bytes)
        raw_vc = await asyncio.wait_for(v_reader.readexactly(4), timeout=8.0)
        vc_id = struct.unpack(">I", raw_vc)[0]
        if vc_id != CODEC_ID_H264:
            raise RuntimeError(f"Unsupported scrcpy video codec ID: 0x{vc_id:08x}")

        self._tasks.append(asyncio.create_task(self._video_loop(v_reader, v_writer)))
        if a_reader is not None and a_writer is not None:
            self._tasks.append(asyncio.create_task(self._audio_loop(a_reader, a_writer)))
        self._tasks.append(asyncio.create_task(self._control_read_loop(c_reader)))

    async def _connect_tunnel_with_retry(self) -> tuple[asyncio.StreamReader, asyncio.StreamWriter]:
        last_err: Exception | None = None
        for _ in range(45):
            if self._proc is not None and self._proc.poll() is not None:
                out = b""
                if self._proc.stdout:
                    out = self._proc.stdout.read() or b""
                raise RuntimeError(f"scrcpy-server exited early: {out.decode('utf-8', 'replace').strip()}")
            try:
                r, w = await asyncio.open_connection("127.0.0.1", self._local_port, limit=4 * 1024 * 1024)
                dummy = await asyncio.wait_for(r.readexactly(1), timeout=0.35)
                if dummy == b"\x00":
                    return r, w
                w.close()
            except Exception as exc:
                last_err = exc
                await asyncio.sleep(0.12)
        raise TimeoutError(f"Không thể kết nối tới scrcpy-server trên thiết bị ({last_err})")

    async def _video_loop(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            while not self._closed:
                hdr = await reader.readexactly(12)
                kind, a, b, is_cfg, _ = parse_scrcpy_stream_header(hdr)
                if kind == "session":
                    self.screen_width, self.screen_height = max(1, a), max(1, b)
                    continue
                payload = await reader.readexactly(b) if b > 0 else b""
                if payload and self.on_video is not None:
                    self.on_video(payload, is_cfg, self.screen_width, self.screen_height)
        except (asyncio.IncompleteReadError, ConnectionError, asyncio.CancelledError):
            pass
        finally:
            writer.close()
            await self.close("video_stream_closed")

    async def _audio_loop(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            raw_ac = await reader.readexactly(4)
            ac_id = struct.unpack(">I", raw_ac)[0]
            if ac_id == 0:
                log.info("Audio capture disabled on target Android device (continuing video-only)")
                return
            if ac_id != CODEC_ID_RAW:
                log.warning("Unexpected scrcpy audio codec 0x%08x", ac_id)
                return
            while not self._closed:
                hdr = await reader.readexactly(12)
                kind, _, pkt_size, is_cfg, _ = parse_scrcpy_stream_header(hdr)
                if kind == "session":
                    continue
                pcm_bytes = await reader.readexactly(pkt_size) if pkt_size > 0 else b""
                if not is_cfg and pcm_bytes and self.on_audio_pcm is not None:
                    self.on_audio_pcm(pcm_bytes)
        except (asyncio.IncompleteReadError, ConnectionError, asyncio.CancelledError):
            pass
        finally:
            writer.close()

    async def _control_read_loop(self, reader: asyncio.StreamReader) -> None:
        """Read DeviceMessages from Android (type 0 = clipboard update, type 1 = ack_clipboard, type 2 = uhid)."""
        try:
            while not self._closed:
                msg_type = (await reader.readexactly(1))[0]
                if msg_type == 0:  # TYPE_CLIPBOARD
                    ln = struct.unpack(">I", await reader.readexactly(4))[0]
                    txt = (await reader.readexactly(ln)).decode("utf-8", "replace")
                    if self.on_clipboard is not None:
                        self.on_clipboard(txt)
                elif msg_type == 1:  # TYPE_ACK_CLIPBOARD (8-byte sequence)
                    await reader.readexactly(8)
                elif msg_type == 2:  # TYPE_UHID_OUTPUT (2-byte id + 2-byte size + data)
                    _, sz = struct.unpack(">HH", await reader.readexactly(4))
                    if sz:
                        await reader.readexactly(sz)
        except (asyncio.IncompleteReadError, ConnectionError, asyncio.CancelledError):
            pass

    async def send_control_bytes(self, raw: bytes) -> None:
        if self._closed or self._ctrl_writer is None or not raw:
            return
        self._ctrl_writer.write(raw)
        await self._ctrl_writer.drain()

    async def send_text(self, text: str) -> None:
        self._clip_seq += 1
        await self.send_control_bytes(ScrcpyControlSerializer.inject_text_smart(text, sequence=self._clip_seq))

    async def close(self, reason: str = "closed") -> None:
        if self._closed:
            return
        self._closed = True
        if self._ctrl_writer is not None:
            try:
                self._ctrl_writer.close()
            except Exception:
                pass
            self._ctrl_writer = None
        for t in list(self._tasks):
            if t is not asyncio.current_task():
                t.cancel()
        self._tasks.clear()
        if self._proc is not None:
            try:
                self._proc.terminate()
            except Exception:
                pass
            self._proc = None
        if self._local_port:
            try:
                await asyncio.to_thread(
                    self.adb.run,
                    ["forward", "--remove", f"tcp:{self._local_port}"],
                    self.serial,
                    4.0,
                    False,
                )
            except Exception:
                pass
            self._local_port = 0
        if self.on_disconnected is not None:
            try:
                self.on_disconnected(reason)
            except Exception:
                pass
