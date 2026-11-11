"""Fragmented MP4 screen + audio recorder (crash-safe, auto-splits on orientation change)."""
from __future__ import annotations

import logging
import queue
import threading
import time
from fractions import Fraction
from pathlib import Path

import av
import numpy as np

from .audio import OUTPUT_RATE

log = logging.getLogger(__name__)

MAX_FPS = 30
QUEUE_LIMIT = 90
GOP_S = 2
AUDIO_RESYNC = OUTPUT_RATE // 10
MOVFLAGS = "frag_keyframe+empty_moov+default_base_moof"
MS = Fraction(1, 1000)


class Recorder:
    """Re-encodes decoded video frames + stereo PCM into crash-resilient fragmented MP4."""

    def __init__(self, folder: Path, prefix: str = "Android", audio: bool = True, stamp: str | None = None):
        self.folder = Path(folder)
        self.prefix = prefix
        self.with_audio = audio
        self.stamp = stamp or time.strftime("%Y-%m-%d %H-%M-%S")
        self.paths: list[Path] = []
        self.t0 = time.perf_counter()
        self._part_t0 = self.t0
        self.dropped = 0
        self.frames = 0
        self.error: str | None = None
        self._q: queue.Queue[tuple[str, object, float] | None] = queue.Queue()
        self._last_video = -1.0
        self._last_pts = -1
        self._out = None
        self._vs = None
        self._as = None
        self._size: tuple[int, int] | None = None
        self._audio_next: int | None = None
        self._thread = threading.Thread(target=self._run, name="pocketdeck-recorder", daemon=True)
        self._thread.start()

    @property
    def seconds(self) -> float:
        return time.perf_counter() - self.t0

    @property
    def alive(self) -> bool:
        return self.error is None and self._thread.is_alive()

    def video(self, frame: av.VideoFrame) -> None:
        now = time.perf_counter()
        if self.error is not None or now - self._last_video < 1.0 / MAX_FPS:
            return
        self._last_video = now
        if self._q.qsize() >= QUEUE_LIMIT:
            self.dropped += 1
            return
        self._q.put(("v", frame, now))

    def audio(self, pcm: np.ndarray) -> None:
        if self.with_audio and self.error is None:
            self._q.put(("a", pcm.copy(), time.perf_counter()))

    def stop(self) -> list[Path]:
        self._q.put(None)
        self._thread.join(timeout=10.0)
        return [p for p in self.paths if p.exists()]

    def _run(self) -> None:
        try:
            while True:
                item = self._q.get()
                if item is None:
                    break
                kind, data, t = item
                if kind == "v":
                    self._encode_video(data, t)  # type: ignore[arg-type]
                elif self._out is not None:
                    self._encode_audio(data, t)  # type: ignore[arg-type]
        except Exception as exc:
            log.exception("Screen recording failed")
            self.error = str(exc) or type(exc).__name__
            while not self._q.empty():
                try:
                    self._q.get_nowait()
                except queue.Empty:
                    break
        finally:
            self._close()

    def _open(self, w: int, h: int, t: float) -> None:
        part = len(self.paths) + 1
        suffix = f" ({part})" if part > 1 else ""
        name = f"{self.prefix} {self.stamp}{suffix}.mp4"
        self.folder.mkdir(parents=True, exist_ok=True)
        path = self.folder / name
        out = av.open(str(path), "w", options={"movflags": MOVFLAGS, "flush_packets": "1"})
        vs = out.add_stream(
            "libx264",
            rate=MAX_FPS,
            options={"preset": "ultrafast", "crf": "23", "g": str(GOP_S * MAX_FPS)},
        )
        vs.width, vs.height = w, h
        vs.pix_fmt = "yuv420p"
        vs.time_base = vs.codec_context.time_base = MS
        astream = None
        if self.with_audio:
            astream = out.add_stream("aac", rate=OUTPUT_RATE, layout="stereo")
            astream.time_base = Fraction(1, OUTPUT_RATE)
        self._out, self._vs, self._as, self._size = out, vs, astream, (w, h)
        self._audio_next = None
        self._last_pts = -1
        self._part_t0 = t
        self.paths.append(path)
        log.info("Recording started: %s (%dx%d)", path, w, h)

    def _close(self) -> None:
        out, self._out = self._out, None
        if out is None:
            return
        try:
            for p in self._vs.encode():
                out.mux(p)
            if self._as is not None:
                for p in self._as.encode():
                    out.mux(p)
        except Exception:
            log.debug("Flush warning on recording close", exc_info=True)
        try:
            out.close()
        except Exception:
            log.debug("Close warning on recording close", exc_info=True)

    def _encode_video(self, frame: av.VideoFrame, t: float) -> None:
        w, h = max(2, frame.width & ~1), max(2, frame.height & ~1)
        if self._size != (w, h):
            self._close()
            self._open(w, h, t)
        f = frame.reformat(width=w, height=h, format="yuv420p")
        f.pts = self._last_pts = max(int((t - self._part_t0) * 1000), self._last_pts + 1)
        f.time_base = MS
        for p in self._vs.encode(f):
            self._out.mux(p)
        self.frames += 1

    def _encode_audio(self, pcm: np.ndarray, t: float) -> None:
        start = int((t - self._part_t0) * OUTPUT_RATE) - len(pcm)
        if self._audio_next is None or start > self._audio_next + AUDIO_RESYNC:
            self._audio_next = max(0, start)
        f = av.AudioFrame.from_ndarray(pcm.reshape(1, -1), format="s16", layout="stereo")
        f.sample_rate = OUTPUT_RATE
        f.pts = self._audio_next
        f.time_base = self._as.time_base
        self._audio_next += len(pcm)
        for p in self._as.encode(f):
            self._out.mux(p)
