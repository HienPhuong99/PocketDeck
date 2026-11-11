"""UltraViewer-style Dual-Pane File System & Chunked File Transfer Engine (with SHA-256 & Resume)."""
from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import shutil
import string
import time
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Callable

from .framing import CH_FILE_CTRL, CH_FILE_DATA, pack_file_chunk, unpack_file_chunk

log = logging.getLogger(__name__)

CHUNK_SIZE = 256 * 1024  # 256 KiB per binary frame
PART_SUFFIX = ".pocketdeck.part"


@dataclass
class FsRoot:
    name: str
    path: str
    kind: str = "folder"  # "drive", "folder", "android"


@dataclass
class FsEntry:
    name: str
    path: str
    is_dir: bool
    size: int
    mtime: float


@dataclass
class DirListing:
    path: str
    parent: str | None
    entries: list[FsEntry]
    free_bytes: int = 0


@dataclass
class TransferProgress:
    xfer_id: str
    direction: str  # "send" or "receive"
    filename: str
    rel_path: str
    total_bytes: int
    transferred_bytes: int = 0
    resumed_from: int = 0
    speed_bps: float = 0.0
    status: str = "queued"  # queued, active, paused, completed, cancelled, error
    error: str = ""
    saved_path: str = ""
    started_at: float = field(default_factory=time.monotonic)

    @property
    def percent(self) -> float:
        if self.total_bytes <= 0:
            return 100.0 if self.status == "completed" else 0.0
        return min(100.0, (self.transferred_bytes / self.total_bytes) * 100.0)

    @property
    def eta_seconds(self) -> float | None:
        rem = max(0, self.total_bytes - self.transferred_bytes)
        if rem == 0:
            return 0.0
        if self.speed_bps <= 1.0:
            return None
        return rem / self.speed_bps


class FileSystemProvider(ABC):
    """Unified interface implemented by LocalFileSystem, RemoteDeckLinkFileSystem, and AdbFileSystem."""

    @abstractmethod
    async def list_roots(self) -> list[FsRoot]:
        raise NotImplementedError

    @abstractmethod
    async def list_dir(self, path: str) -> DirListing:
        raise NotImplementedError

    @abstractmethod
    async def mkdir(self, parent_dir: str, name: str) -> str:
        raise NotImplementedError

    @abstractmethod
    async def delete(self, path: str) -> None:
        raise NotImplementedError

    @abstractmethod
    async def rename(self, path: str, new_name: str) -> str:
        raise NotImplementedError


def sanitize_relative_path(rel_path: str) -> Path:
    """Validate a relative path from network peer and block any '..' or drive traversal."""
    normalized = (rel_path or "").replace("\\", "/").strip("/")
    if not normalized:
        raise ValueError("Empty relative file path")
    parts = PurePosixPath(normalized).parts
    clean_parts: list[str] = []
    for p in parts:
        if p in ("", ".", "..") or ":" in p:
            raise ValueError(f"Unsafe relative path segment: {p!r}")
        clean_parts.append(p)
    return Path(*clean_parts)


def safe_resolve_under(dest_dir: Path, rel_path: str) -> Path:
    base = dest_dir.resolve()
    rel = sanitize_relative_path(rel_path)
    candidate = (base / rel).resolve()
    try:
        candidate.relative_to(base)
    except ValueError as exc:
        raise ValueError(f"Path escapes destination directory: {rel_path!r}") from exc
    return candidate


class LocalFileSystem(FileSystemProvider):
    """Local host filesystem browser (Windows/POSIX)."""

    def _list_roots_sync(self) -> list[FsRoot]:
        roots: list[FsRoot] = []
        home = Path.home()
        special = [
            ("Desktop", home / "Desktop"),
            ("Downloads", home / "Downloads"),
            ("Documents", home / "Documents"),
            ("Pictures", home / "Pictures"),
            ("Videos", home / "Videos"),
        ]
        for label, p in special:
            if p.exists():
                roots.append(FsRoot(name=label, path=str(p), kind="folder"))

        if os.name == "nt":
            bitmask = 0
            try:
                import ctypes

                bitmask = ctypes.windll.kernel32.GetLogicalDrives()
            except Exception:
                bitmask = 0
            for idx, letter in enumerate(string.ascii_uppercase):
                drive = f"{letter}:\\"
                if (bitmask & (1 << idx)) or os.path.exists(drive):
                    roots.append(FsRoot(name=f"Ổ đĩa ({letter}:)", path=drive, kind="drive"))
        else:
            roots.append(FsRoot(name="Root (/)", path="/", kind="drive"))
        return roots

    async def list_roots(self) -> list[FsRoot]:
        return await asyncio.to_thread(self._list_roots_sync)

    def _list_dir_sync(self, path: str) -> DirListing:
        target = Path(path).expanduser().resolve() if path else Path.home().resolve()
        if not target.exists() or not target.is_dir():
            raise FileNotFoundError(f"Thư mục không tồn tại: {target}")

        entries: list[FsEntry] = []
        with os.scandir(target) as it:
            for item in it:
                try:
                    st = item.stat(follow_symlinks=False)
                    is_d = item.is_dir(follow_symlinks=False)
                    entries.append(
                        FsEntry(
                            name=item.name,
                            path=str(target / item.name),
                            is_dir=is_d,
                            size=0 if is_d else int(st.st_size),
                            mtime=float(st.st_mtime),
                        )
                    )
                except OSError:
                    continue

        entries.sort(key=lambda e: (not e.is_dir, e.name.lower()))
        parent = str(target.parent) if target.parent != target else None
        free_b = 0
        try:
            free_b = shutil.disk_usage(target).free
        except OSError:
            pass
        return DirListing(path=str(target), parent=parent, entries=entries, free_bytes=free_b)

    async def list_dir(self, path: str) -> DirListing:
        return await asyncio.to_thread(self._list_dir_sync, path)

    async def mkdir(self, parent_dir: str, name: str) -> str:
        clean_name = name.strip().replace("/", "_").replace("\\", "_")
        if not clean_name or clean_name in (".", ".."):
            raise ValueError("Tên thư mục không hợp lệ")
        target = (Path(parent_dir).resolve() / clean_name)
        await asyncio.to_thread(target.mkdir, parents=True, exist_ok=True)
        return str(target)

    async def delete(self, path: str) -> None:
        target = Path(path).resolve()
        if target.parent == target:
            raise PermissionError("Không thể xoá thư mục gốc")

        def _del() -> None:
            if target.is_dir() and not target.is_symlink():
                shutil.rmtree(target)
            elif target.exists():
                target.unlink()

        await asyncio.to_thread(_del)

    async def rename(self, path: str, new_name: str) -> str:
        clean_name = new_name.strip().replace("/", "_").replace("\\", "_")
        if not clean_name or clean_name in (".", ".."):
            raise ValueError("Tên mới không hợp lệ")
        src = Path(path).resolve()
        dst = src.parent / clean_name
        await asyncio.to_thread(src.rename, dst)
        return str(dst)


def collect_local_files(paths: list[str | Path]) -> list[tuple[Path, str, int, float]]:
    """Expand files & directories into (abs_file_path, rel_posix_path, size, mtime)."""
    out: list[tuple[Path, str, int, float]] = []
    for raw in paths:
        p = Path(raw).resolve()
        if not p.exists():
            continue
        if p.is_file():
            st = p.stat()
            out.append((p, p.name, int(st.st_size), float(st.st_mtime)))
        elif p.is_dir():
            root_parent = p.parent
            for dirpath, _, filenames in os.walk(p):
                d = Path(dirpath)
                for fn in sorted(filenames):
                    fp = d / fn
                    try:
                        st = fp.stat()
                    except OSError:
                        continue
                    rel = fp.relative_to(root_parent).as_posix()
                    out.append((fp, rel, int(st.st_size), float(st.st_mtime)))
    return out


@dataclass
class _IncomingState:
    progress: TransferProgress
    final_path: Path
    part_path: Path
    fh: Any
    hasher: Any
    expected_offset: int
    mtime: float
    last_tick_t: float = field(default_factory=time.monotonic)
    last_tick_bytes: int = 0


@dataclass
class _OutgoingControl:
    progress: TransferProgress
    accepted_evt: asyncio.Event = field(default_factory=asyncio.Event)
    done_evt: asyncio.Event = field(default_factory=asyncio.Event)
    pause_evt: asyncio.Event = field(default_factory=asyncio.Event)
    resume_offset: int = 0
    cancelled: bool = False
    error: str = ""
    saved_path: str = ""

    def __post_init__(self) -> None:
        self.pause_evt.set()  # unpaused initially


class FileTransferManager:
    """Manages remote filesystem RPCs and bidirectional chunked transfers over a DeckLink session."""

    def __init__(
        self,
        send_ctrl: Callable[[dict[str, Any]], asyncio.Future | Any],
        send_data: Callable[[bytes], asyncio.Future | Any],
        local_fs: LocalFileSystem | None = None,
        default_download_dir: str | Path | None = None,
        on_progress: Callable[[TransferProgress], None] | None = None,
    ):
        self._send_ctrl = send_ctrl
        self._send_data = send_data
        self.local_fs = local_fs or LocalFileSystem()
        self.default_download_dir = Path(default_download_dir or Path.home() / "Downloads" / "PocketDeck")
        self.default_download_dir.mkdir(parents=True, exist_ok=True)
        self.on_progress = on_progress

        self._rpc_waiters: dict[str, asyncio.Future[dict[str, Any]]] = {}
        self._incoming: dict[bytes, _IncomingState] = {}
        self._outgoing: dict[str, _OutgoingControl] = {}
        self._transfers: dict[str, TransferProgress] = {}

    def all_transfers(self) -> list[TransferProgress]:
        return list(self._transfers.values())

    def _notify(self, prog: TransferProgress) -> None:
        self._transfers[prog.xfer_id] = prog
        if self.on_progress is not None:
            try:
                self.on_progress(prog)
            except Exception:
                log.debug("on_progress callback failed", exc_info=True)

    # ------------------------------------------------------------------ RPC client (RemoteDeckLinkFileSystem)
    async def rpc_call(self, payload: dict[str, Any], timeout: float = 15.0) -> dict[str, Any]:
        req_id = uuid.uuid4().hex[:12]
        msg = dict(payload)
        msg["req_id"] = req_id
        loop = asyncio.get_running_loop()
        fut: asyncio.Future[dict[str, Any]] = loop.create_future()
        self._rpc_waiters[req_id] = fut
        try:
            await self._send_ctrl(msg)
            resp = await asyncio.wait_for(fut, timeout=timeout)
            if resp.get("error"):
                raise RuntimeError(str(resp["error"]))
            return resp
        finally:
            self._rpc_waiters.pop(req_id, None)

    def remote_fs(self) -> "RemoteDeckLinkFileSystem":
        return RemoteDeckLinkFileSystem(self)

    # ------------------------------------------------------------------ Controls
    def pause_transfer(self, xfer_id: str) -> None:
        out = self._outgoing.get(xfer_id)
        if out is not None and out.progress.status == "active":
            out.pause_evt.clear()
            out.progress.status = "paused"
            out.progress.speed_bps = 0.0
            self._notify(out.progress)

    def resume_transfer(self, xfer_id: str) -> None:
        out = self._outgoing.get(xfer_id)
        if out is not None and out.progress.status == "paused":
            out.progress.status = "active"
            out.pause_evt.set()
            self._notify(out.progress)

    async def cancel_transfer(self, xfer_id: str) -> None:
        out = self._outgoing.get(xfer_id)
        if out is not None:
            out.cancelled = True
            out.pause_evt.set()
            out.accepted_evt.set()
            out.done_evt.set()
            out.progress.status = "cancelled"
            self._notify(out.progress)
            await self._send_ctrl({"op": "xfer_cancel", "xfer_id": xfer_id})
            return

        uid = bytes.fromhex(xfer_id) if len(xfer_id) == 32 else None
        inc = self._incoming.pop(uid, None) if uid else None
        if inc is not None:
            try:
                inc.fh.close()
            except Exception:
                pass
            inc.progress.status = "cancelled"
            self._notify(inc.progress)
            await self._send_ctrl({"op": "xfer_cancel", "xfer_id": xfer_id})

    # ------------------------------------------------------------------ Outgoing Send & Pull
    async def send_items(self, local_paths: list[str | Path], remote_dest_dir: str = "") -> list[TransferProgress]:
        """Send one or more local files/folders to the remote partner's `remote_dest_dir`."""
        expanded = await asyncio.to_thread(collect_local_files, local_paths)
        results: list[TransferProgress] = []
        for abs_path, rel_path, size, mtime in expanded:
            prog = await self._send_single_file(abs_path, rel_path, size, mtime, remote_dest_dir)
            results.append(prog)
        return results

    async def request_pull(self, remote_paths: list[str], local_dest_dir: str | Path) -> None:
        """Ask the remote partner to push `remote_paths` into our `local_dest_dir` (UltraViewer 'Receive <-')."""
        dest = str(Path(local_dest_dir).resolve())
        await self._send_ctrl({"op": "xfer_pull", "paths": list(remote_paths), "dest_dir": dest})

    async def _send_single_file(
        self,
        abs_path: Path,
        rel_path: str,
        size: int,
        mtime: float,
        remote_dest_dir: str,
    ) -> TransferProgress:
        uid = uuid.uuid4()
        xfer_id = uid.hex
        prog = TransferProgress(
            xfer_id=xfer_id,
            direction="send",
            filename=abs_path.name,
            rel_path=rel_path,
            total_bytes=size,
            status="queued",
        )
        ctrl = _OutgoingControl(progress=prog)
        self._outgoing[xfer_id] = ctrl
        self._notify(prog)

        try:
            await self._send_ctrl(
                {
                    "op": "xfer_offer",
                    "xfer_id": xfer_id,
                    "dest_dir": remote_dest_dir,
                    "rel_path": rel_path,
                    "size": size,
                    "mtime": mtime,
                }
            )
            await asyncio.wait_for(ctrl.accepted_evt.wait(), timeout=20.0)
            if ctrl.cancelled:
                prog.status = "cancelled"
                self._notify(prog)
                return prog
            if ctrl.error:
                prog.status = "error"
                prog.error = ctrl.error
                self._notify(prog)
                return prog

            offset = max(0, min(size, ctrl.resume_offset))
            prog.resumed_from = offset
            prog.transferred_bytes = offset
            prog.status = "active"
            self._notify(prog)

            hasher = hashlib.sha256()
            last_t = time.monotonic()
            last_b = offset

            with abs_path.open("rb") as fh:
                # Hash prefix if resuming so full-file SHA-256 remains exact
                if offset > 0:
                    rem_prefix = offset
                    while rem_prefix > 0:
                        blk = await asyncio.to_thread(fh.read, min(CHUNK_SIZE, rem_prefix))
                        if not blk:
                            break
                        hasher.update(blk)
                        rem_prefix -= len(blk)

                cur = offset
                while cur < size:
                    await ctrl.pause_evt.wait()
                    if ctrl.cancelled or ctrl.error:
                        break
                    chunk = await asyncio.to_thread(fh.read, CHUNK_SIZE)
                    if not chunk:
                        break
                    hasher.update(chunk)
                    await self._send_data(pack_file_chunk(uid.bytes, cur, chunk))
                    cur += len(chunk)
                    prog.transferred_bytes = cur

                    now = time.monotonic()
                    dt = now - last_t
                    if dt >= 0.12 or cur >= size:
                        prog.speed_bps = (cur - last_b) / max(dt, 1e-3)
                        last_t = now
                        last_b = cur
                        self._notify(prog)

            if ctrl.cancelled:
                prog.status = "cancelled"
                self._notify(prog)
                return prog
            if ctrl.error:
                prog.status = "error"
                prog.error = ctrl.error
                self._notify(prog)
                return prog

            await self._send_ctrl({"op": "xfer_eof", "xfer_id": xfer_id, "sha256": hasher.hexdigest()})
            await asyncio.wait_for(ctrl.done_evt.wait(), timeout=30.0)
            if ctrl.error:
                prog.status = "error"
                prog.error = ctrl.error
            elif ctrl.cancelled:
                prog.status = "cancelled"
            else:
                prog.status = "completed"
                prog.saved_path = ctrl.saved_path
            self._notify(prog)
            return prog
        except Exception as exc:
            prog.status = "error"
            prog.error = str(exc)
            self._notify(prog)
            return prog
        finally:
            self._outgoing.pop(xfer_id, None)

    # ------------------------------------------------------------------ Incoming Frame Handlers
    async def handle_control(self, msg: dict[str, Any]) -> None:
        op = msg.get("op")
        req_id = msg.get("req_id")

        if op == "rpc_resp" and req_id:
            fut = self._rpc_waiters.get(str(req_id))
            if fut is not None and not fut.done():
                fut.set_result(msg)
            return

        if op in ("fs_roots", "fs_list", "fs_mkdir", "fs_delete", "fs_rename"):
            await self._handle_fs_rpc(op, str(req_id or ""), msg)
            return

        if op == "xfer_offer":
            await self._handle_offer(msg)
        elif op == "xfer_accept":
            ctrl = self._outgoing.get(str(msg.get("xfer_id") or ""))
            if ctrl is not None:
                ctrl.resume_offset = int(msg.get("resume_offset") or 0)
                ctrl.accepted_evt.set()
        elif op == "xfer_eof":
            await self._handle_eof(msg)
        elif op == "xfer_done":
            ctrl = self._outgoing.get(str(msg.get("xfer_id") or ""))
            if ctrl is not None:
                ctrl.saved_path = str(msg.get("saved_path") or "")
                ctrl.done_evt.set()
        elif op == "xfer_error":
            xfer_id = str(msg.get("xfer_id") or "")
            err = str(msg.get("error") or "Transfer failed")
            ctrl = self._outgoing.get(xfer_id)
            if ctrl is not None:
                ctrl.error = err
                ctrl.accepted_evt.set()
                ctrl.done_evt.set()
            uid = bytes.fromhex(xfer_id) if len(xfer_id) == 32 else None
            inc = self._incoming.pop(uid, None) if uid else None
            if inc is not None:
                try:
                    inc.fh.close()
                except Exception:
                    pass
                inc.progress.status = "error"
                inc.progress.error = err
                self._notify(inc.progress)
        elif op == "xfer_cancel":
            xfer_id = str(msg.get("xfer_id") or "")
            ctrl = self._outgoing.get(xfer_id)
            if ctrl is not None:
                ctrl.cancelled = True
                ctrl.pause_evt.set()
                ctrl.accepted_evt.set()
                ctrl.done_evt.set()
            uid = bytes.fromhex(xfer_id) if len(xfer_id) == 32 else None
            inc = self._incoming.pop(uid, None) if uid else None
            if inc is not None:
                try:
                    inc.fh.close()
                except Exception:
                    pass
                inc.progress.status = "cancelled"
                self._notify(inc.progress)
        elif op == "xfer_pull":
            paths = [str(p) for p in (msg.get("paths") or [])]
            dest_dir = str(msg.get("dest_dir") or "")
            asyncio.create_task(self.send_items(paths, dest_dir))

    async def _handle_fs_rpc(self, op: str, req_id: str, msg: dict[str, Any]) -> None:
        resp: dict[str, Any] = {"op": "rpc_resp", "req_id": req_id}
        try:
            if op == "fs_roots":
                roots = await self.local_fs.list_roots()
                resp["roots"] = [{"name": r.name, "path": r.path, "kind": r.kind} for r in roots]
            elif op == "fs_list":
                listing = await self.local_fs.list_dir(str(msg.get("path") or ""))
                resp["path"] = listing.path
                resp["parent"] = listing.parent
                resp["free_bytes"] = listing.free_bytes
                resp["entries"] = [
                    {
                        "name": e.name,
                        "path": e.path,
                        "is_dir": e.is_dir,
                        "size": e.size,
                        "mtime": e.mtime,
                    }
                    for e in listing.entries
                ]
            elif op == "fs_mkdir":
                created = await self.local_fs.mkdir(str(msg.get("parent_dir") or ""), str(msg.get("name") or ""))
                resp["created"] = created
            elif op == "fs_delete":
                await self.local_fs.delete(str(msg.get("path") or ""))
                resp["ok"] = True
            elif op == "fs_rename":
                renamed = await self.local_fs.rename(str(msg.get("path") or ""), str(msg.get("new_name") or ""))
                resp["renamed"] = renamed
        except Exception as exc:
            resp["error"] = str(exc)
        await self._send_ctrl(resp)

    async def _handle_offer(self, msg: dict[str, Any]) -> None:
        xfer_id = str(msg.get("xfer_id") or "")
        if len(xfer_id) != 32:
            return
        uid = bytes.fromhex(xfer_id)
        dest_dir_raw = str(msg.get("dest_dir") or "").strip()
        dest_base = Path(dest_dir_raw) if dest_dir_raw else self.default_download_dir
        rel_path = str(msg.get("rel_path") or "")
        total_size = int(msg.get("size") or 0)
        mtime = float(msg.get("mtime") or time.time())

        try:
            dest_base.mkdir(parents=True, exist_ok=True)
            final_path = safe_resolve_under(dest_base, rel_path)
            final_path.parent.mkdir(parents=True, exist_ok=True)
            part_path = final_path.with_name(final_path.name + PART_SUFFIX)

            resume_offset = 0
            hasher = hashlib.sha256()
            if part_path.exists():
                existing_len = part_path.stat().st_size
                if 0 < existing_len <= total_size:
                    resume_offset = existing_len
                    with part_path.open("rb") as rf:
                        while True:
                            blk = await asyncio.to_thread(rf.read, CHUNK_SIZE)
                            if not blk:
                                break
                            hasher.update(blk)
                    fh = part_path.open("ab")
                else:
                    fh = part_path.open("wb")
            else:
                fh = part_path.open("wb")

            prog = TransferProgress(
                xfer_id=xfer_id,
                direction="receive",
                filename=final_path.name,
                rel_path=rel_path,
                total_bytes=total_size,
                transferred_bytes=resume_offset,
                resumed_from=resume_offset,
                status="active",
            )
            self._incoming[uid] = _IncomingState(
                progress=prog,
                final_path=final_path,
                part_path=part_path,
                fh=fh,
                hasher=hasher,
                expected_offset=resume_offset,
                mtime=mtime,
                last_tick_bytes=resume_offset,
            )
            self._notify(prog)
            await self._send_ctrl({"op": "xfer_accept", "xfer_id": xfer_id, "resume_offset": resume_offset})
        except Exception as exc:
            log.warning("Rejected file offer %s: %s", rel_path, exc)
            await self._send_ctrl({"op": "xfer_error", "xfer_id": xfer_id, "error": str(exc)})

    async def handle_data(self, payload: bytes) -> None:
        uid, offset, chunk = unpack_file_chunk(payload)
        inc = self._incoming.get(uid)
        if inc is None:
            return
        if offset != inc.expected_offset:
            inc.fh.close()
            self._incoming.pop(uid, None)
            err = f"Offset mismatch: got {offset}, expected {inc.expected_offset}"
            inc.progress.status = "error"
            inc.progress.error = err
            self._notify(inc.progress)
            await self._send_ctrl({"op": "xfer_error", "xfer_id": inc.progress.xfer_id, "error": err})
            return

        await asyncio.to_thread(inc.fh.write, chunk)
        inc.hasher.update(chunk)
        inc.expected_offset += len(chunk)
        inc.progress.transferred_bytes = inc.expected_offset

        now = time.monotonic()
        dt = now - inc.last_tick_t
        if dt >= 0.12 or inc.expected_offset >= inc.progress.total_bytes:
            inc.progress.speed_bps = (inc.expected_offset - inc.last_tick_bytes) / max(dt, 1e-3)
            inc.last_tick_t = now
            inc.last_tick_bytes = inc.expected_offset
            self._notify(inc.progress)

    async def _handle_eof(self, msg: dict[str, Any]) -> None:
        xfer_id = str(msg.get("xfer_id") or "")
        if len(xfer_id) != 32:
            return
        uid = bytes.fromhex(xfer_id)
        inc = self._incoming.pop(uid, None)
        if inc is None:
            return
        try:
            await asyncio.to_thread(inc.fh.flush)
            inc.fh.close()
            expected_sha = str(msg.get("sha256") or "").lower()
            actual_sha = inc.hasher.hexdigest().lower()
            if inc.expected_offset != inc.progress.total_bytes:
                raise ValueError(f"Incomplete transfer ({inc.expected_offset}/{inc.progress.total_bytes} bytes)")
            if expected_sha and expected_sha != actual_sha:
                inc.part_path.unlink(missing_ok=True)
                raise ValueError("Mã kiểm tra toàn vẹn SHA-256 không khớp!")

            await asyncio.to_thread(os.replace, inc.part_path, inc.final_path)
            try:
                os.utime(inc.final_path, (inc.mtime, inc.mtime))
            except OSError:
                pass
            inc.progress.status = "completed"
            inc.progress.saved_path = str(inc.final_path)
            self._notify(inc.progress)
            await self._send_ctrl({"op": "xfer_done", "xfer_id": xfer_id, "saved_path": str(inc.final_path)})
        except Exception as exc:
            inc.progress.status = "error"
            inc.progress.error = str(exc)
            self._notify(inc.progress)
            await self._send_ctrl({"op": "xfer_error", "xfer_id": xfer_id, "error": str(exc)})

    def close(self) -> None:
        for inc in list(self._incoming.values()):
            try:
                inc.fh.close()
            except Exception:
                pass
        self._incoming.clear()
        for fut in list(self._rpc_waiters.values()):
            if not fut.done():
                fut.cancel()
        self._rpc_waiters.clear()


class RemoteDeckLinkFileSystem(FileSystemProvider):
    """Remote partner filesystem view backed by DeckLink `CH_FILE_CTRL` RPCs."""

    def __init__(self, manager: FileTransferManager):
        self._mgr = manager

    async def list_roots(self) -> list[FsRoot]:
        resp = await self._mgr.rpc_call({"op": "fs_roots"})
        return [
            FsRoot(name=str(r.get("name") or ""), path=str(r.get("path") or ""), kind=str(r.get("kind") or "folder"))
            for r in (resp.get("roots") or [])
        ]

    async def list_dir(self, path: str) -> DirListing:
        resp = await self._mgr.rpc_call({"op": "fs_list", "path": path})
        entries = [
            FsEntry(
                name=str(e.get("name") or ""),
                path=str(e.get("path") or ""),
                is_dir=bool(e.get("is_dir")),
                size=int(e.get("size") or 0),
                mtime=float(e.get("mtime") or 0.0),
            )
            for e in (resp.get("entries") or [])
        ]
        parent_raw = resp.get("parent")
        return DirListing(
            path=str(resp.get("path") or path),
            parent=str(parent_raw) if parent_raw else None,
            entries=entries,
            free_bytes=int(resp.get("free_bytes") or 0),
        )

    async def mkdir(self, parent_dir: str, name: str) -> str:
        resp = await self._mgr.rpc_call({"op": "fs_mkdir", "parent_dir": parent_dir, "name": name})
        return str(resp.get("created") or "")

    async def delete(self, path: str) -> None:
        await self._mgr.rpc_call({"op": "fs_delete", "path": path})

    async def rename(self, path: str, new_name: str) -> str:
        resp = await self._mgr.rpc_call({"op": "fs_rename", "path": path, "new_name": new_name})
        return str(resp.get("renamed") or "")
