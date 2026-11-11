"""Tests for fragmented MP4 screen recording (including orientation change) and end-to-end FakeAndroid session."""
from __future__ import annotations

import asyncio
import time
from pathlib import Path

import av
import numpy as np

from pocketdeck.media.audio import AudioPlayer, MultiCodecAudioReceiver
from pocketdeck.media.record import Recorder
from pocketdeck.media.video import VideoDecoder, save_frame_png
from pocketdeck.protocol.session import SessionCallbacks, connect_decklink
from tools.fake_android import FakeAndroidDevice


def test_fragmented_mp4_recorder_and_rotation_split(tmp_path: Path) -> None:
    rec = Recorder(tmp_path, prefix="AndroidTest", audio=True, stamp="2026-11-11")
    # Portrait frames (320x640)
    for i in range(6):
        arr = np.full((640, 320, 3), (20 * i) % 255, dtype=np.uint8)
        rec.video(av.VideoFrame.from_ndarray(arr, format="rgb24"))
        pcm = np.zeros((1600, 2), dtype="<i2")
        rec.audio(pcm)
        time.sleep(0.04)

    # Rotate to Landscape (640x320) -> must split cleanly into part (2).mp4
    for i in range(6):
        arr = np.full((320, 640, 3), (40 * i) % 255, dtype=np.uint8)
        rec.video(av.VideoFrame.from_ndarray(arr, format="rgb24"))
        pcm = np.zeros((1600, 2), dtype="<i2")
        rec.audio(pcm)
        time.sleep(0.04)

    saved = rec.stop()
    assert rec.error is None
    assert len(saved) == 2
    assert saved[0].name == "AndroidTest 2026-11-11.mp4"
    assert saved[1].name == "AndroidTest 2026-11-11 (2).mp4"

    # Verify both MP4 containers decode cleanly via PyAV
    with av.open(str(saved[0])) as c1:
        assert c1.streams.video[0].width == 320 and c1.streams.video[0].height == 640
    with av.open(str(saved[1])) as c2:
        assert c2.streams.video[0].width == 640 and c2.streams.video[0].height == 320


def test_fake_android_end_to_end_video_audio_control_and_files(tmp_path: Path) -> None:
    async def _run() -> None:
        decoded_frames: list[tuple[int, int]] = []
        frame_evt = asyncio.Event()
        loop = asyncio.get_running_loop()

        def on_decoded(bgra, w: int, h: int) -> None:
            if bgra is not None:
                decoded_frames.append((w, h))
                loop.call_soon_threadsafe(frame_evt.set)

        decoder = VideoDecoder(on_frame=on_decoded)
        decoder.target = (270, 585)
        decoder.start()
        player = AudioPlayer(open_device=False)
        audio_rx = MultiCodecAudioReceiver(player)

        fake = FakeAndroidDevice(
            device_id="482910375",
            password="246810",
            host="127.0.0.1",
            port=0,
            width=360,
            height=780,
            fps=20,
            storage_dir=tmp_path / "fake_phone",
        )
        port = await fake.start()
        try:
            cbs = SessionCallbacks(
                on_video=lambda _kind, _pts, _w, _h, h264: decoder.submit(h264),
                on_audio=lambda codec, rate, ch, _pts, raw: audio_rx.feed(codec, rate, ch, raw),
            )
            sess = await connect_decklink(
                host="127.0.0.1",
                port=port,
                password="246810",
                client_id="900800700",
                client_name="Test PC",
                callbacks=cbs,
                download_dir=tmp_path / "pc_downloads",
            )
            await asyncio.wait_for(frame_evt.wait(), timeout=5.0)
            assert decoded_frames[0] == (360, 780)
            assert decoder.last_frame is not None

            shot = save_frame_png(decoder.last_frame, tmp_path / "shot.png")
            assert shot.exists() and shot.stat().st_size > 500

            await sess.send_control("tap", x=180, y=390)
            await sess.send_control("text", text="Kiểm thử Tiếng Việt")
            await asyncio.sleep(0.1)
            assert fake.last_touch == (180, 390)
            assert fake.last_text == "Kiểm thử Tiếng Việt"

            # Verify UltraViewer remote directory listing against FakeAndroid storage
            listing = await sess.files.remote_fs().list_dir(str(tmp_path / "fake_phone" / "Download"))
            assert any(e.name == "huong-dan-android.txt" for e in listing.entries)

            await sess.close()
        finally:
            decoder.stop()
            player.close()
            await fake.stop()

    asyncio.run(_run())
