"""Tests for UltraViewer-style Dual-Pane File System, Multi-File/Folder Transfer, Resume, and Security."""
from __future__ import annotations

import asyncio
import hashlib
import os
from pathlib import Path

import pytest

from pocketdeck.protocol.file_transfer import PART_SUFFIX, safe_resolve_under, sanitize_relative_path
from pocketdeck.protocol.session import DeckLinkHostServer, SessionCallbacks, connect_decklink


def test_path_traversal_blocked(tmp_path: Path) -> None:
    assert sanitize_relative_path("HinhAnh/anh 1.png") == Path("HinhAnh") / "anh 1.png"
    with pytest.raises(ValueError):
        sanitize_relative_path("../../Windows/System32/cmd.exe")
    with pytest.raises(ValueError):
        sanitize_relative_path("C:/evil.exe")
    with pytest.raises(ValueError):
        safe_resolve_under(tmp_path, "../escape.txt")


def test_dual_pane_fs_and_bidirectional_transfer_with_resume(tmp_path: Path) -> None:
    async def _run() -> None:
        android_root = tmp_path / "android_storage"
        pc_root = tmp_path / "pc_storage"
        android_dl = android_root / "Download"
        pc_dl = pc_root / "Downloads"
        android_dl.mkdir(parents=True)
        pc_dl.mkdir(parents=True)

        # Create Vietnamese Unicode folders & binary multi-chunk files on PC
        src_folder = pc_root / "Hồ Sơ PCCC 2026"
        (src_folder / "Bản vẽ").mkdir(parents=True)
        (src_folder / "ghi_chú.txt").write_text("Phương án chữa cháy tự động", encoding="utf-8")
        big_payload = os.urandom(620 * 1024)  # > 2 chunks of 256 KiB
        (src_folder / "Bản vẽ" / "mat_bang.bin").write_bytes(big_payload)

        # Also create a 900 KiB file on Android to test Pull ('<- Tải về') and Resume
        remote_video = android_dl / "quay_man_hinh.mp4"
        video_bytes = os.urandom(900 * 1024)
        remote_video.write_bytes(video_bytes)

        # Pre-seed a 300 KiB .pocketdeck.part on PC to verify automatic Resume!
        part_file = pc_dl / ("quay_man_hinh.mp4" + PART_SUFFIX)
        part_file.write_bytes(video_bytes[: 300 * 1024])

        client_progress_events = []

        srv = DeckLinkHostServer(
            device_id="555666777",
            device_name="Xiaomi 14",
            password_provider=lambda: "888999",
            host="127.0.0.1",
            port=0,
            platform="android",
            download_dir=android_dl,
        )
        port = await srv.start()
        try:
            client = await connect_decklink(
                host="127.0.0.1",
                port=port,
                password="888999",
                client_id="999000111",
                client_name="PC Windows",
                callbacks=SessionCallbacks(on_transfer_progress=client_progress_events.append),
                download_dir=pc_dl,
            )

            # 1. Remote FileSystem browsing, mkdir, rename, delete
            rfs = client.files.remote_fs()
            roots = await rfs.list_roots()
            assert len(roots) >= 1

            created_dir = await rfs.mkdir(str(android_dl), "Thư mục mới")
            assert Path(created_dir).is_dir()
            renamed_dir = await rfs.rename(created_dir, "Đã đổi tên")
            assert Path(renamed_dir).name == "Đã đổi tên"
            listing = await rfs.list_dir(str(android_dl))
            names = [e.name for e in listing.entries]
            assert "Đã đổi tên" in names and "quay_man_hinh.mp4" in names
            await rfs.delete(renamed_dir)
            assert not Path(renamed_dir).exists()

            # 2. Send recursive folder PC -> Android ('Gửi sang ->')
            sent = await client.files.send_items([src_folder], str(android_dl))
            assert len(sent) == 2
            assert all(p.status == "completed" for p in sent)

            copied_txt = android_dl / "Hồ Sơ PCCC 2026" / "ghi_chú.txt"
            copied_bin = android_dl / "Hồ Sơ PCCC 2026" / "Bản vẽ" / "mat_bang.bin"
            assert copied_txt.read_text(encoding="utf-8") == "Phương án chữa cháy tự động"
            assert hashlib.sha256(copied_bin.read_bytes()).hexdigest() == hashlib.sha256(big_payload).hexdigest()

            # 3. Pull file Android -> PC ('<- Tải về') with automatic 300 KiB Resume!
            await client.files.request_pull([str(remote_video)], pc_dl)
            pulled_target = pc_dl / "quay_man_hinh.mp4"
            for _ in range(60):
                if pulled_target.exists():
                    break
                await asyncio.sleep(0.05)

            assert pulled_target.exists()
            assert not part_file.exists()
            assert hashlib.sha256(pulled_target.read_bytes()).hexdigest() == hashlib.sha256(video_bytes).hexdigest()

            recv_progs = [p for p in client_progress_events if p.direction == "receive" and p.status == "completed"]
            assert len(recv_progs) >= 1
            assert recv_progs[-1].resumed_from == 300 * 1024

            await client.close()
        finally:
            await srv.stop()

    asyncio.run(_run())
