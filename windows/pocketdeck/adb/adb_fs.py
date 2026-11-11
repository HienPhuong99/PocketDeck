"""ADB-backed FileSystemProvider & TransferEngine for the UltraViewer-style Dual-Pane File Manager."""
from __future__ import annotations

import asyncio
import os
import posixpath
import shlex
import time
import uuid
from pathlib import Path
from typing import Callable

from ..protocol.file_transfer import DirListing, FileSystemProvider, FsEntry, FsRoot, TransferProgress, collect_local_files
from .manager import AdbManager


def parse_android_stat_lines(output: str, parent_posix: str) -> list[FsEntry]:
    """Parse `stat -c '%F|%s|%Y|%n'` lines emitted on Android toybox/busybox."""
    entries: list[FsEntry] = []
    for raw in (output or "").splitlines():
        line = raw.strip()
        if not line or "|" not in line or "No such file" in line:
            continue
        parts = line.split("|", 3)
        if len(parts) != 4:
            continue
        ftype, size_s, mtime_s, full_or_name = parts
        name = posixpath.basename(full_or_name.rstrip("/"))
        if not name or name in (".", ".."):
            continue
        is_dir = "directory" in ftype.lower() or "symbolic link" in ftype.lower()
        try:
            size = 0 if is_dir else int(size_s)
        except ValueError:
            size = 0
        try:
            mtime = float(mtime_s)
        except ValueError:
            mtime = 0.0
        entries.append(
            FsEntry(
                name=name,
                path=posixpath.join(parent_posix.rstrip("/") or "/", name),
                is_dir=is_dir,
                size=size,
                mtime=mtime,
            )
        )
    entries.sort(key=lambda e: (not e.is_dir, e.name.lower()))
    return entries


class AdbFileSystem(FileSystemProvider):
    """Implements the exact same FileSystemProvider interface over `adb shell` + `adb push/pull`."""

    def __init__(self, adb: AdbManager, serial: str):
        self.adb = adb
        self.serial = serial

    async def list_roots(self) -> list[FsRoot]:
        return [
            FsRoot("Bộ nhớ trong (/sdcard)", "/sdcard", "android"),
            FsRoot("Download", "/sdcard/Download", "folder"),
            FsRoot("Ảnh & Video (DCIM)", "/sdcard/DCIM", "folder"),
            FsRoot("Pictures", "/sdcard/Pictures", "folder"),
            FsRoot("Documents", "/sdcard/Documents", "folder"),
            FsRoot("Movies", "/sdcard/Movies", "folder"),
        ]

    async def list_dir(self, path: str) -> DirListing:
        clean = posixpath.normpath((path or "/sdcard").replace("\\", "/"))
        if not clean.startswith("/"):
            clean = "/" + clean
        quoted = shlex.quote(clean)
        # Use toybox `stat` for exact size + mtime + UTF-8 filenames with spaces/Vietnamese
        script = f"cd {quoted} && stat -c '%F|%s|%Y|%n' * .[!.]* 2>/dev/null; echo '__DF__'; df -k {quoted} 2>/dev/null"
        proc = await asyncio.to_thread(self.adb.run, ["shell", script], self.serial, 15.0, False)
        text = proc.stdout.decode("utf-8", "replace")
        stat_part, _, df_part = text.partition("__DF__")
        entries = parse_android_stat_lines(stat_part, clean)

        free_bytes = 0
        df_lines = [ln.strip() for ln in df_part.splitlines() if ln.strip()]
        if len(df_lines) >= 2:
            cols = df_lines[-1].split()
            if len(cols) >= 4 and cols[3].isdigit():
                free_bytes = int(cols[3]) * 1024

        parent = posixpath.dirname(clean) if clean not in ("/", "/sdcard", "/storage/emulated/0") else (
            "/" if clean != "/" else None
        )
        return DirListing(path=clean, parent=parent, entries=entries, free_bytes=free_bytes)

    async def mkdir(self, parent_dir: str, name: str) -> str:
        clean_name = name.strip().replace("/", "_").replace("\\", "_")
        if not clean_name or clean_name in (".", ".."):
            raise ValueError("Tên thư mục không hợp lệ")
        target = posixpath.join(parent_dir.rstrip("/") or "/sdcard", clean_name)
        await asyncio.to_thread(self.adb.run, ["shell", f"mkdir -p {shlex.quote(target)}"], self.serial, 10.0, True)
        return target

    async def delete(self, path: str) -> None:
        clean = posixpath.normpath(path.replace("\\", "/"))
        if clean in ("/", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0"):
            raise PermissionError("Không được xoá thư mục gốc của thiết bị Android")
        await asyncio.to_thread(self.adb.run, ["shell", f"rm -rf {shlex.quote(clean)}"], self.serial, 20.0, True)

    async def rename(self, path: str, new_name: str) -> str:
        clean_name = new_name.strip().replace("/", "_").replace("\\", "_")
        if not clean_name or clean_name in (".", ".."):
            raise ValueError("Tên mới không hợp lệ")
        clean_src = posixpath.normpath(path.replace("\\", "/"))
        dst = posixpath.join(posixpath.dirname(clean_src), clean_name)
        await asyncio.to_thread(
            self.adb.run,
            ["shell", f"mv {shlex.quote(clean_src)} {shlex.quote(dst)}"],
            self.serial,
            10.0,
            True,
        )
        return dst


class AdbTransferEngine:
    """Push & pull files/folders over ADB with live TransferProgress updates matching FileTransferManager."""

    def __init__(
        self,
        adb: AdbManager,
        serial: str,
        on_progress: Callable[[TransferProgress], None] | None = None,
    ):
        self.adb = adb
        self.serial = serial
        self.on_progress = on_progress

    def _emit(self, p: TransferProgress) -> None:
        if self.on_progress is not None:
            self.on_progress(p)

    async def send_items(self, local_paths: list[str | Path], remote_dest_dir: str = "/sdcard/Download/PocketDeck") -> list[TransferProgress]:
        expanded = await asyncio.to_thread(collect_local_files, local_paths)
        results: list[TransferProgress] = []
        base_remote = (remote_dest_dir or "/sdcard/Download/PocketDeck").replace("\\", "/").rstrip("/")

        for abs_path, rel_path, size, _ in expanded:
            prog = TransferProgress(
                xfer_id=uuid.uuid4().hex,
                direction="send",
                filename=abs_path.name,
                rel_path=rel_path,
                total_bytes=size,
                status="active",
            )
            self._emit(prog)
            t0 = time.monotonic()
            remote_target = f"{base_remote}/{rel_path}"
            remote_parent = posixpath.dirname(remote_target)
            try:
                await asyncio.to_thread(
                    self.adb.run,
                    ["shell", f"mkdir -p {shlex.quote(remote_parent)}"],
                    self.serial,
                    10.0,
                    True,
                )
                await asyncio.to_thread(
                    self.adb.run,
                    ["push", str(abs_path), remote_target],
                    self.serial,
                    300.0,
                    True,
                )
                # Trigger Android MediaScanner so photos/videos show up in Gallery immediately
                await asyncio.to_thread(
                    self.adb.run,
                    [
                        "shell",
                        "am",
                        "broadcast",
                        "-a",
                        "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                        "-d",
                        shlex.quote(f"file://{remote_target}"),
                    ],
                    self.serial,
                    5.0,
                    False,
                )
                dt = max(1e-3, time.monotonic() - t0)
                prog.transferred_bytes = size
                prog.speed_bps = size / dt
                prog.status = "completed"
                prog.saved_path = remote_target
            except Exception as exc:
                prog.status = "error"
                prog.error = str(exc)
            self._emit(prog)
            results.append(prog)
        return results

    async def pull_items(self, remote_paths: list[str], local_dest_dir: str | Path) -> list[TransferProgress]:
        dest = Path(local_dest_dir).resolve()
        dest.mkdir(parents=True, exist_ok=True)
        results: list[TransferProgress] = []

        for rpath in remote_paths:
            clean_r = rpath.replace("\\", "/")
            fname = posixpath.basename(clean_r.rstrip("/")) or "item"
            local_target = dest / fname
            prog = TransferProgress(
                xfer_id=uuid.uuid4().hex,
                direction="receive",
                filename=fname,
                rel_path=fname,
                total_bytes=0,
                status="active",
            )
            self._emit(prog)
            t0 = time.monotonic()
            try:
                await asyncio.to_thread(
                    self.adb.run,
                    ["pull", clean_r, str(local_target)],
                    self.serial,
                    300.0,
                    True,
                )
                total_sz = 0
                if local_target.is_file():
                    total_sz = local_target.stat().st_size
                elif local_target.is_dir():
                    for dp, _, fns in os.walk(local_target):
                        for fn in fns:
                            total_sz += (Path(dp) / fn).stat().st_size
                dt = max(1e-3, time.monotonic() - t0)
                prog.total_bytes = total_sz
                prog.transferred_bytes = total_sz
                prog.speed_bps = total_sz / dt
                prog.status = "completed"
                prog.saved_path = str(local_target)
            except Exception as exc:
                prog.status = "error"
                prog.error = str(exc)
            self._emit(prog)
            results.append(prog)
        return results
