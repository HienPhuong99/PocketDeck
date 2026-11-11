"""Jitter-buffered stereo audio player and multi-codec (PCM16 / Opus / AAC) decoder."""
from __future__ import annotations

import collections
import logging
import threading
from typing import Any

import av
import numpy as np

from ..protocol.framing import AUDIO_CODEC_AAC, AUDIO_CODEC_OPUS, AUDIO_CODEC_PCM16

log = logging.getLogger(__name__)

OUTPUT_RATE = 48000


class AudioPlayer:
    """Low-latency jitter-buffered sounddevice stereo output stream (48 kHz int16)."""

    PREBUFFER = int(OUTPUT_RATE * 0.06)
    MAX_BUFFER = int(OUTPUT_RATE * 0.32)

    def __init__(self, sample_rate: int = OUTPUT_RATE, open_device: bool = True):
        self.sample_rate = sample_rate
        self._chunks: collections.deque[np.ndarray] = collections.deque()
        self._buffered = 0
        self._lock = threading.Lock()
        self._started = False
        self.gain = 1.0
        self.muted = False
        self.recorder: Any = None
        self._stream = None
        if open_device:
            try:
                import sounddevice as sd

                self._stream = sd.OutputStream(
                    samplerate=self.sample_rate,
                    channels=2,
                    dtype="int16",
                    latency="low",
                    callback=self._callback,
                )
                self._stream.start()
            except Exception as exc:
                log.debug("Audio speaker output unavailable: %s", exc)

    def close(self) -> None:
        if self._stream is not None:
            try:
                self._stream.stop()
                self._stream.close()
            except Exception:
                pass
            self._stream = None

    def flush(self) -> None:
        with self._lock:
            self._chunks.clear()
            self._buffered = 0
            self._started = False

    def write(self, pcm: np.ndarray) -> None:
        """Accepts int16 stereo ndarray shaped (N, 2)."""
        if pcm.size == 0:
            return
        if pcm.ndim == 1:
            pcm = np.column_stack((pcm, pcm))
        elif pcm.shape[1] == 1:
            pcm = np.repeat(pcm, 2, axis=1)

        rec = self.recorder
        if rec is not None:
            rec.audio(pcm)

        with self._lock:
            self._chunks.append(pcm)
            self._buffered += len(pcm)
            while self._buffered > self.MAX_BUFFER and self._chunks:
                self._buffered -= len(self._chunks.popleft())
            if not self._started and self._buffered >= self.PREBUFFER:
                self._started = True

    def _callback(self, outdata: np.ndarray, frames: int, _time_info: Any, _status: Any) -> None:
        outdata.fill(0)
        with self._lock:
            if not self._started:
                return
            pos = 0
            while pos < frames and self._chunks:
                chunk = self._chunks[0]
                n = min(frames - pos, len(chunk))
                outdata[pos : pos + n] = chunk[:n]
                if n == len(chunk):
                    self._chunks.popleft()
                else:
                    self._chunks[0] = chunk[n:]
                self._buffered -= n
                pos += n
            if pos < frames:
                self._started = False
        if self.muted:
            outdata.fill(0)
        elif self.gain < 0.999:
            np.multiply(outdata, self.gain, out=outdata, casting="unsafe")


class MultiCodecAudioReceiver:
    """Decodes PCM16, Opus, or AAC frames and resamples to 48 kHz stereo int16 for AudioPlayer."""

    def __init__(self, player: AudioPlayer):
        self.player = player
        self._codec_type: int | None = None
        self._decoder = None
        self._resampler = av.AudioResampler(format="s16", layout="stereo", rate=player.sample_rate)

    def feed(self, codec: int, sample_rate: int, channels: int, data: bytes, extradata: bytes = b"") -> None:
        if not data:
            return
        if codec == AUDIO_CODEC_PCM16:
            ch = max(1, min(2, int(channels or 2)))
            usable = (len(data) // (2 * ch)) * (2 * ch)
            if usable <= 0:
                return
            arr = np.frombuffer(data[:usable], dtype="<i2").reshape(-1, ch)
            if ch == 1:
                arr = np.repeat(arr, 2, axis=1)
            if sample_rate and sample_rate != self.player.sample_rate:
                af = av.AudioFrame.from_ndarray(arr.reshape(1, -1), format="s16", layout="stereo")
                af.sample_rate = int(sample_rate)
                for rf in self._resampler.resample(af):
                    self.player.write(rf.to_ndarray().reshape(-1, 2))
            else:
                self.player.write(arr.copy())
            return

        name = "opus" if codec == AUDIO_CODEC_OPUS else "aac" if codec == AUDIO_CODEC_AAC else None
        if name is None:
            return
        if self._decoder is None or self._codec_type != codec:
            self._decoder = av.CodecContext.create(name, "r")
            if extradata:
                self._decoder.extradata = extradata
            self._decoder.sample_rate = int(sample_rate or OUTPUT_RATE)
            self._codec_type = codec
        try:
            frames = self._decoder.decode(av.Packet(data))
        except av.error.FFmpegError:
            return
        for f in frames:
            for rf in self._resampler.resample(f):
                self.player.write(rf.to_ndarray().reshape(-1, 2))
