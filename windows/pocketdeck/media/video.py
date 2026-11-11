"""Low-latency H.264 Annex-B video decoder with DPI-exact BGRA scaling and stream metrics."""
from __future__ import annotations

import collections
import logging
import queue
import threading
import time
from pathlib import Path
from typing import Callable

import av
import numpy as np
from av.video.reformatter import VideoReformatter
from PIL import Image

log = logging.getLogger(__name__)


def to_bgra(frame: av.VideoFrame, target: tuple[int, int] | None, scaler: VideoReformatter) -> np.ndarray:
    """Convert an av.VideoFrame to a BGRA ndarray shaped (h, w, 4), preserving source aspect."""
    tw, th = target or (frame.width, frame.height)
    tw, th = max(16, int(tw)), max(16, int(th))
    if abs(tw / th - frame.width / frame.height) > 0.02:
        th = max(16, round(tw * frame.height / frame.width))
    plane = scaler.reformat(frame, width=tw, height=th, format="bgra", interpolation="AREA").planes[0]
    rows = np.frombuffer(plane, np.uint8)[: th * plane.line_size].reshape(th, plane.line_size)
    return rows[:, : tw * 4].reshape(th, tw, 4)


def save_frame_png(frame: av.VideoFrame, dest: Path) -> Path:
    """Save full-resolution av.VideoFrame to a PNG file."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    rgb = frame.to_ndarray(format="rgb24")
    Image.fromarray(rgb).save(dest, format="PNG")
    return dest


class VideoDecoder(threading.Thread):
    """Decodes Annex-B H.264 access units on a background thread and emits newest BGRA frame."""

    def __init__(self, on_frame: Callable[[np.ndarray | None, int, int], None]):
        super().__init__(name="pocketdeck-video-decoder", daemon=True)
        self.on_frame = on_frame
        self.q: queue.Queue[bytes | None] = queue.Queue(maxsize=240)
        self.target: tuple[int, int] | None = None
        self.visible: bool = True
        self._scaler = VideoReformatter()
        self.last_frame: av.VideoFrame | None = None
        self.recorder: object | None = None
        self._sps_pps: bytes = b""

        # Live telemetry for Ctrl+I overlay
        self.total_bytes = 0
        self.decoded_frames = 0
        self.dropped_packets = 0
        self.source_size: tuple[int, int] = (0, 0)
        self._fps_times: collections.deque[float] = collections.deque(maxlen=90)
        self._byte_samples: collections.deque[tuple[float, int]] = collections.deque(maxlen=90)
        self._codec = None
        self._stop = False

    def set_config(self, sps_pps_annexb: bytes) -> None:
        """Store codec configuration (SPS/PPS) to prepend to the next access unit."""
        self._sps_pps = bytes(sps_pps_annexb)

    def submit(self, annexb: bytes, is_config: bool = False) -> None:
        if not annexb:
            return
        now = time.monotonic()
        self.total_bytes += len(annexb)
        self._byte_samples.append((now, len(annexb)))

        if is_config:
            self._sps_pps = bytes(annexb)
            # Also feed codec config immediately so decoder initializes early
            payload = bytes(annexb)
        elif self._sps_pps:
            payload = self._sps_pps + annexb
            self._sps_pps = b""
        else:
            payload = annexb

        try:
            self.q.put_nowait(payload)
        except queue.Full:
            self.dropped_packets += 1
            if self.dropped_packets % 60 == 1:
                log.warning("Video decoder queue full, dropped %d packets", self.dropped_packets)

    def reset(self) -> None:
        self._sps_pps = b""
        try:
            self.q.put_nowait(None)
        except queue.Full:
            pass

    def stop(self) -> None:
        self._stop = True
        try:
            self.q.put_nowait(b"")
        except queue.Full:
            pass

    @property
    def fps(self) -> float:
        times = list(self._fps_times)
        if len(times) < 2:
            return 0.0
        span = times[-1] - times[0]
        return (len(times) - 1) / span if span > 0.02 else 0.0

    @property
    def bitrate_mbps(self) -> float:
        samples = list(self._byte_samples)
        if len(samples) < 2:
            return 0.0
        now = time.monotonic()
        recent = [(t, b) for t, b in samples if now - t <= 2.0]
        if len(recent) < 2:
            return 0.0
        span = recent[-1][0] - recent[0][0]
        if span <= 0.05:
            return 0.0
        total_b = sum(b for _, b in recent[1:])
        return (total_b * 8.0) / (span * 1_000_000.0)

    def _new_codec(self):
        c = av.CodecContext.create("h264", "r")
        try:
            c.thread_type = "SLICE"
        except Exception:
            pass
        c.options = {"flags": "low_delay"}
        return c

    def run(self) -> None:
        while not self._stop:
            data = self.q.get()
            if self._stop:
                break
            if data is None:
                self._codec = None
                self.last_frame = None
                self._fps_times.clear()
                self._byte_samples.clear()
                continue
            if not data:
                continue
            if self._codec is None:
                self._codec = self._new_codec()
            try:
                frames = self._codec.decode(av.Packet(data))
            except av.error.FFmpegError as exc:
                log.debug("H.264 decode error: %s", exc)
                continue
            if not frames:
                continue
            now = time.monotonic()
            for f in frames:
                self.decoded_frames += 1
                self._fps_times.append(now)
                self.source_size = (f.width, f.height)
                rec = self.recorder
                if rec is not None:
                    rec.video(f)  # type: ignore[attr-defined]

            if not self.q.empty():
                continue  # Keep decoding references without wasting CPU scaling stale frames

            newest = frames[-1]
            self.last_frame = newest
            try:
                img = to_bgra(newest, self.target, self._scaler) if self.visible else None
                self.on_frame(img, newest.width, newest.height)
            except Exception:
                log.exception("BGRA conversion error")
