"""Simulated Android phone speaking DeckLink v1 (H.264 video + PCM audio + Control + File Transfer + Chat).

Run standalone for manual E2E testing against PocketDeck for Windows:
    python windows/tools/fake_android.py --port 7200 --password 123456
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import math
import sys
import tempfile
import time
from fractions import Fraction
from pathlib import Path
from typing import Any

import av
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from pocketdeck.discovery import LanDiscovery  # noqa: E402
from pocketdeck.protocol.file_transfer import DirListing, FsRoot, LocalFileSystem  # noqa: E402
from pocketdeck.protocol.framing import AUDIO_CODEC_PCM16, VIDEO_KIND_DATA  # noqa: E402
from pocketdeck.protocol.session import DeckLinkHostServer, DeckLinkSession, PeerInfo, SessionCallbacks  # noqa: E402


class _FakeAndroidFs(LocalFileSystem):
    def __init__(self, root: Path):
        self.root = root

    async def list_roots(self) -> list[FsRoot]:
        return [
            FsRoot("Bộ nhớ trong", str(self.root), "android"),
            FsRoot("Download", str(self.root / "Download"), "folder"),
            FsRoot("DCIM", str(self.root / "DCIM"), "folder"),
            FsRoot("Documents", str(self.root / "Documents"), "folder"),
        ]

    async def list_dir(self, path: str) -> DirListing:
        return await super().list_dir(path or str(self.root))


class FakeAndroidDevice:
    """Self-contained virtual Android host streaming live H.264 + stereo audio over DeckLink v1."""

    def __init__(
        self,
        device_id: str = "482910375",
        device_name: str = "Pixel 9 Pro (Simulated)",
        password: str = "123456",
        host: str = "127.0.0.1",
        port: int = 0,
        width: int = 540,
        height: int = 1170,
        fps: int = 24,
        storage_dir: Path | None = None,
        enable_discovery: bool = False,
    ):
        self.device_id = device_id
        self.device_name = device_name
        self.password = password
        self.host = host
        self.requested_port = port
        self.width = width
        self.height = height
        self.fps = fps
        self._tmp_dir = tempfile.TemporaryDirectory() if storage_dir is None else None
        self.storage_dir = Path(storage_dir) if storage_dir is not None else Path(self._tmp_dir.name)  # type: ignore[union-attr]
        self._seed_sample_files()

        self.received_controls: list[dict[str, Any]] = []
        self.received_chats: list[dict[str, Any]] = []
        self.last_touch: tuple[int, int] | None = None
        self.last_text: str = "Sẵn sàng nhận điều khiển"
        self.landscape: bool = False

        self._stream_tasks: list[asyncio.Task[None]] = []
        self.server = DeckLinkHostServer(
            device_id=self.device_id,
            device_name=self.device_name,
            password_provider=lambda: self.password,
            host=self.host,
            port=self.requested_port,
            platform="android",
            screen_size_provider=self._current_size,
            session_factory=self._make_callbacks,
            on_session_started=self._on_session_started,
            download_dir=self.storage_dir / "Download",
        )
        self.discovery = (
            LanDiscovery(
                device_id=self.device_id,
                device_name=self.device_name,
                tcp_port=0,
                platform="android",
            )
            if enable_discovery
            else None
        )

    def _seed_sample_files(self) -> None:
        for sub in ("Download", "DCIM", "Documents"):
            (self.storage_dir / sub).mkdir(parents=True, exist_ok=True)
        readme = self.storage_dir / "Download" / "huong-dan-android.txt"
        if not readme.exists():
            readme.write_text("File mẫu trên máy Android ảo để kiểm thử truyền file kiểu UltraViewer.", encoding="utf-8")
        sample_img = self.storage_dir / "DCIM" / "anh-mau.png"
        if not sample_img.exists():
            img = Image.new("RGB", (320, 200), (24, 98, 171))
            d = ImageDraw.Draw(img)
            d.rectangle([20, 20, 300, 180], outline=(255, 255, 255), width=3)
            d.text((40, 90), "PocketDeck Sample Photo", fill=(255, 255, 255))
            img.save(sample_img, format="PNG")

    def _current_size(self) -> tuple[int, int]:
        return (self.height, self.width) if self.landscape else (self.width, self.height)

    def _make_callbacks(self, _peer: PeerInfo) -> SessionCallbacks:
        def on_ctrl(msg: dict[str, Any]) -> None:
            self.received_controls.append(msg)
            act = msg.get("action")
            if act in ("tap", "touch_down", "touch_move"):
                self.last_touch = (int(msg.get("x") or 0), int(msg.get("y") or 0))
            elif act == "text":
                self.last_text = str(msg.get("text") or "")
            elif act == "rotate":
                self.landscape = not self.landscape

        def on_chat(msg: dict[str, Any]) -> None:
            self.received_chats.append(msg)

        return SessionCallbacks(on_control=on_ctrl, on_chat=on_chat)

    def _on_session_started(self, session: DeckLinkSession) -> None:
        session.files.local_fs = _FakeAndroidFs(self.storage_dir)
        self._stream_tasks.append(asyncio.create_task(self._stream_media_to(session)))

    async def start(self) -> int:
        port = await self.server.start()
        if self.discovery is not None:
            self.discovery.update_info(tcp_port=port)
            self.discovery.start()
        return port

    async def stop(self) -> None:
        if self.discovery is not None:
            self.discovery.stop()
        for t in list(self._stream_tasks):
            t.cancel()
        self._stream_tasks.clear()
        await self.server.stop()
        if self._tmp_dir is not None:
            self._tmp_dir.cleanup()
            self._tmp_dir = None

    def _render_frame(self, idx: int, w: int, h: int) -> np.ndarray:
        img = Image.new("RGB", (w, h), (18, 24, 38))
        draw = ImageDraw.Draw(img)
        # Clean status header bar
        draw.rectangle([0, 0, w, 56], fill=(30, 41, 59))
        draw.text((20, 18), f"PocketDeck Android  |  ID: {self.device_id}", fill=(226, 232, 240))

        # Animated indicator card
        bar_y = 90 + int(40 * math.sin(idx * 0.18))
        draw.rounded_rectangle([28, bar_y, w - 28, bar_y + 130], radius=18, fill=(37, 99, 235))
        draw.text((48, bar_y + 26), f"Frame #{idx}  ({w}x{h})", fill=(255, 255, 255))
        draw.text((48, bar_y + 68), f"Text: {self.last_text[:34]}", fill=(219, 234, 254))

        if self.last_touch is not None:
            tx, ty = self.last_touch
            r = 24
            draw.ellipse([tx - r, ty - r, tx + r, ty + r], outline=(74, 222, 128), width=4)

        return np.asarray(img, dtype=np.uint8)

    async def _stream_media_to(self, session: DeckLinkSession) -> None:
        enc = None
        cur_size = (0, 0)
        idx = 0
        t0 = time.perf_counter()
        sample_rate = 48000
        audio_samples_per_tick = sample_rate // max(1, self.fps)
        phase = 0.0

        try:
            while not session.is_closed:
                w, h = self._current_size()
                w, h = w & ~1, h & ~1
                if (w, h) != cur_size or enc is None:
                    enc = av.CodecContext.create("libx264", "w")
                    enc.width, enc.height = w, h
                    enc.pix_fmt = "yuv420p"
                    enc.framerate = Fraction(self.fps, 1)
                    enc.time_base = Fraction(1, 1000)
                    enc.options = {"preset": "ultrafast", "tune": "zerolatency", "g": str(self.fps)}
                    enc.open()
                    cur_size = (w, h)

                rgb = self._render_frame(idx, w, h)
                vf = av.VideoFrame.from_ndarray(rgb, format="rgb24").reformat(format="yuv420p")
                pts_ms = int((time.perf_counter() - t0) * 1000)
                vf.pts = max(idx, pts_ms)
                packets = enc.encode(vf)
                for pkt in packets:
                    await session.send_video(VIDEO_KIND_DATA, vf.pts * 1000, w, h, bytes(pkt))

                # Gentle low-amplitude 440 Hz sine wave stereo PCM16
                t_arr = (np.arange(audio_samples_per_tick, dtype=np.float32) + phase) / sample_rate
                phase += audio_samples_per_tick
                tone = (np.sin(2.0 * math.pi * 440.0 * t_arr) * 3200.0).astype("<i2")
                stereo = np.column_stack((tone, tone))
                await session.send_audio(AUDIO_CODEC_PCM16, sample_rate, 2, vf.pts * 1000, stereo.tobytes())

                idx += 1
                await asyncio.sleep(1.0 / max(1, self.fps))
        except (asyncio.CancelledError, ConnectionError):
            pass
        except Exception:
            logging.getLogger(__name__).exception("FakeAndroid media stream failed")


async def _main() -> None:
    ap = argparse.ArgumentParser(description="Run a simulated Android PocketDeck host")
    ap.add_argument("--id", default="482910375", help="9-digit Partner ID")
    ap.add_argument("--password", default="123456", help="6-digit session password")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=7200)
    ap.add_argument("--seconds", type=float, default=0.0, help="Auto-stop after N seconds (0 = forever)")
    args = ap.parse_args()

    dev = FakeAndroidDevice(
        device_id=args.id,
        password=args.password,
        host=args.host,
        port=args.port,
        enable_discovery=True,
    )
    port = await dev.start()
    print(f"FakeAndroid ready -> ID: {args.id} | Password: {args.password} | TCP {args.host}:{port}", flush=True)
    try:
        if args.seconds > 0:
            await asyncio.sleep(args.seconds)
        else:
            await asyncio.Event().wait()
    finally:
        await dev.stop()


if __name__ == "__main__":
    asyncio.run(_main())
