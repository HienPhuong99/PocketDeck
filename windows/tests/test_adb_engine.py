"""Tests for ADB device parsing, scrcpy v5.x binary protocol, Vietnamese text injection, and AdbFileSystem."""
from __future__ import annotations

import asyncio
import struct
from pathlib import Path
from types import SimpleNamespace

from pocketdeck.adb.adb_fs import AdbFileSystem, AdbTransferEngine, parse_android_stat_lines
from pocketdeck.adb.manager import AdbManager, parse_adb_devices
from pocketdeck.adb.stream import (
    AKEYCODE_HOME,
    AMOTION_EVENT_ACTION_DOWN,
    AMOTION_EVENT_ACTION_UP,
    PACKET_FLAG_CONFIG,
    PACKET_FLAG_KEY_FRAME,
    PACKET_FLAG_SESSION,
    SC_INJECT_SCROLL,
    SC_INJECT_TEXT,
    SC_INJECT_TOUCH,
    SC_SET_CLIPBOARD,
    ScrcpyControlSerializer,
    parse_scrcpy_stream_header,
)


def test_parse_adb_devices() -> None:
    sample = """List of devices attached
R58N123456A            device product:dm3qxxx model:SM_S918B device:dm3q transport_id:4
192.168.1.88:5555      device product:husky model:Pixel_8_Pro device:husky transport_id:7
emulator-5554          unauthorized transport_id:9
"""
    devs = parse_adb_devices(sample)
    assert len(devs) == 3
    assert devs[0].serial == "R58N123456A"
    assert devs[0].state == "device"
    assert not devs[0].is_wireless
    assert "SM S918B" in devs[0].display_name
    assert devs[1].is_wireless
    assert devs[2].state == "unauthorized"


def test_scrcpy_v5_stream_headers() -> None:
    # Session metadata header (v5.0.1 writeSessionMeta)
    flags_hi = (PACKET_FLAG_SESSION >> 32) | 1
    sess_hdr = struct.pack(">III", flags_hi, 1080, 2400)
    assert parse_scrcpy_stream_header(sess_hdr) == ("session", 1080, 2400, False, False)

    # Config packet
    cfg_hdr = struct.pack(">QI", PACKET_FLAG_CONFIG, 38)
    assert parse_scrcpy_stream_header(cfg_hdr) == ("frame", 0, 38, True, False)

    # Keyframe packet at pts=16666 us
    kf_hdr = struct.pack(">QI", PACKET_FLAG_KEY_FRAME | 16666, 42000)
    assert parse_scrcpy_stream_header(kf_hdr) == ("frame", 16666, 42000, False, True)


def test_scrcpy_control_serializer_and_vietnamese_ime() -> None:
    down = ScrcpyControlSerializer.inject_touch(AMOTION_EVENT_ACTION_DOWN, 540, 1200, 1080, 2400)
    assert down[0] == SC_INJECT_TOUCH
    assert len(down) == 32

    up = ScrcpyControlSerializer.inject_touch(AMOTION_EVENT_ACTION_UP, 540, 1200, 1080, 2400)
    # Pressure & buttons must be zeroed on ACTION_UP
    _, _, _, _, _, _, _, p_u16, _, btns = struct.unpack(">BBQiiHHHii", up)
    assert p_u16 == 0 and btns == 0

    scr = ScrcpyControlSerializer.inject_scroll(300, 600, 1080, 2400, hscroll=0.0, vscroll=-2.0)
    assert scr[0] == SC_INJECT_SCROLL
    assert len(scr) == 21

    home = ScrcpyControlSerializer.press_keycode(AKEYCODE_HOME)
    assert len(home) == 28  # 14 bytes DOWN + 14 bytes UP

    # Pure ASCII uses direct SC_INJECT_TEXT
    ascii_pkt = ScrcpyControlSerializer.inject_text_smart("hello 123", sequence=5)
    assert ascii_pkt[0] == SC_INJECT_TEXT

    # Vietnamese diacritics automatically route via SC_SET_CLIPBOARD with paste=True
    vi_pkt = ScrcpyControlSerializer.inject_text_smart("Xin chào Phượng!", sequence=9)
    assert vi_pkt[0] == SC_SET_CLIPBOARD
    _, seq, paste_flag, byte_len = struct.unpack_from(">BQ?I", vi_pkt, 0)
    assert seq == 9 and paste_flag is True
    assert vi_pkt[14 : 14 + byte_len].decode("utf-8") == "Xin chào Phượng!"


def test_adb_filesystem_and_mock_transfer(tmp_path: Path) -> None:
    stat_sample = """directory|4096|1730000100|DCIM
regular file|2097152|1730000200|Báo cáo nghiệm thu.pdf
regular empty file|0|1730000300|ghi_chu.txt
__DF__
Filesystem     1K-blocks     Used Available Use% Mounted on
/dev/fuse      115343360 45000000  70343360  39% /storage/emulated
"""
    entries = parse_android_stat_lines(stat_sample.partition("__DF__")[0], "/sdcard/Download")
    assert len(entries) == 3
    assert entries[0].name == "DCIM" and entries[0].is_dir
    assert entries[1].name == "Báo cáo nghiệm thu.pdf" and entries[1].size == 2097152

    calls: list[list[str]] = []

    class MockAdb(AdbManager):
        def __init__(self) -> None:
            self.adb_path = Path(__file__)
            self.scrcpy_server_path = Path(__file__)

        def run(self, args: list[str], serial: str | None = None, timeout: float = 15.0, check: bool = True):
            calls.append(args)
            if args[0] == "shell" and "stat -c" in args[1]:
                return SimpleNamespace(returncode=0, stdout=stat_sample.encode("utf-8"), stderr=b"")
            if args[0] == "pull":
                dst = Path(args[2])
                dst.write_bytes(b"pulled-from-android")
            return SimpleNamespace(returncode=0, stdout=b"OK", stderr=b"")

    async def _run() -> None:
        adb = MockAdb()
        fs = AdbFileSystem(adb, "R58N123456A")
        listing = await fs.list_dir("/sdcard/Download")
        assert listing.free_bytes == 70343360 * 1024
        assert len(listing.entries) == 3

        src_file = tmp_path / "hop_dong.docx"
        src_file.write_bytes(b"noi-dung-hop-dong")

        xfer = AdbTransferEngine(adb, "R58N123456A")
        sent = await xfer.send_items([src_file], "/sdcard/Download/PocketDeck")
        assert sent[0].status == "completed"

        pulled = await xfer.pull_items(["/sdcard/Download/anh.jpg"], tmp_path / "pulled")
        assert pulled[0].status == "completed"
        assert (tmp_path / "pulled" / "anh.jpg").read_bytes() == b"pulled-from-android"

    asyncio.run(_run())
