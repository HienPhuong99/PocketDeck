"""Multiplexed DeckLink v1 Session, Client Connector, and Host Server."""
from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from .file_transfer import FileTransferManager, LocalFileSystem, TransferProgress
from .framing import (
    CH_AUDIO,
    CH_AUTH,
    CH_CHAT,
    CH_CLIPBOARD,
    CH_CONTROL,
    CH_FILE_CTRL,
    CH_FILE_DATA,
    CH_VIDEO,
    DIR_CLIENT_TO_SERVER,
    DIR_SERVER_TO_CLIENT,
    PROTOCOL_VERSION,
    AuthenticationError,
    AuthRateLimiter,
    ProtocolError,
    SessionCipher,
    compute_auth_proof,
    derive_session_key,
    pack_audio_payload,
    pack_frame,
    pack_json,
    pack_video_payload,
    random_nonce,
    read_frame,
    unpack_audio_payload,
    unpack_json,
    unpack_video_payload,
    verify_auth_proof,
)

log = logging.getLogger(__name__)


@dataclass
class PeerInfo:
    device_id: str
    device_name: str
    platform: str
    remote_ip: str
    screen_width: int = 1080
    screen_height: int = 2400
    capabilities: list[str] = field(default_factory=list)


@dataclass
class SessionCallbacks:
    on_video: Callable[[int, int, int, int, bytes], None] | None = None  # kind, pts_us, w, h, data
    on_audio: Callable[[int, int, int, int, bytes], None] | None = None  # codec, rate, ch, pts_us, data
    on_control: Callable[[dict[str, Any]], None] | None = None
    on_chat: Callable[[dict[str, Any]], None] | None = None
    on_clipboard: Callable[[str], None] | None = None
    on_transfer_progress: Callable[[TransferProgress], None] | None = None
    on_disconnected: Callable[[str], None] | None = None


class DeckLinkSession:
    """Active authenticated DeckLink v1 session (either client or server side)."""

    def __init__(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
        cipher: SessionCipher,
        send_seq_start: int,
        recv_seq_start: int,
        peer: PeerInfo,
        callbacks: SessionCallbacks | None = None,
        download_dir: str | Path | None = None,
    ):
        self._reader = reader
        self._writer = writer
        self._cipher = cipher
        self._send_seq = send_seq_start
        self._recv_seq = recv_seq_start
        self.peer = peer
        self.callbacks = callbacks or SessionCallbacks()
        self._write_lock = asyncio.Lock()
        self._closed = False
        self._read_task: asyncio.Task[None] | None = None

        self.files = FileTransferManager(
            send_ctrl=lambda obj: self.send_json(CH_FILE_CTRL, obj),
            send_data=lambda raw: self.send_raw(CH_FILE_DATA, raw),
            local_fs=LocalFileSystem(),
            default_download_dir=download_dir,
            on_progress=self.callbacks.on_transfer_progress,
        )

    @property
    def is_closed(self) -> bool:
        return self._closed

    def start_reader(self) -> asyncio.Task[None]:
        if self._read_task is None:
            self._read_task = asyncio.create_task(self._read_loop())
        return self._read_task

    async def send_raw(self, channel: int, payload: bytes) -> None:
        if self._closed:
            raise ConnectionError("DeckLink session is closed")
        async with self._write_lock:
            frame = pack_frame(channel, self._send_seq, payload, cipher=self._cipher)
            self._send_seq = (self._send_seq + 1) & 0xFFFFFFFF
            self._writer.write(frame)
            await self._writer.drain()

    async def send_json(self, channel: int, obj: dict[str, Any]) -> None:
        await self.send_raw(channel, pack_json(obj))

    async def send_control(self, action: str, **params: Any) -> None:
        msg = {"action": action, **params}
        await self.send_json(CH_CONTROL, msg)

    async def send_video(self, kind: int, pts_us: int, width: int, height: int, h264_annexb: bytes) -> None:
        await self.send_raw(CH_VIDEO, pack_video_payload(kind, pts_us, width, height, h264_annexb))

    async def send_audio(self, codec: int, sample_rate: int, channels: int, pts_us: int, audio_bytes: bytes) -> None:
        await self.send_raw(CH_AUDIO, pack_audio_payload(codec, sample_rate, channels, pts_us, audio_bytes))

    async def send_chat(self, sender: str, text: str) -> dict[str, Any]:
        msg = {
            "id": uuid.uuid4().hex[:12],
            "sender": sender,
            "text": text,
            "ts": time.time(),
        }
        await self.send_json(CH_CHAT, msg)
        return msg

    async def send_clipboard(self, text: str) -> None:
        await self.send_json(CH_CLIPBOARD, {"text": text})

    async def _read_loop(self) -> None:
        reason = "closed"
        try:
            while not self._closed:
                channel, _, body = await read_frame(
                    self._reader,
                    expected_seq=self._recv_seq,
                    cipher=self._cipher,
                )
                self._recv_seq = (self._recv_seq + 1) & 0xFFFFFFFF
                await self._dispatch(channel, body)
        except (asyncio.IncompleteReadError, ConnectionError):
            reason = "connection_closed"
        except asyncio.CancelledError:
            reason = "cancelled"
        except Exception as exc:
            reason = str(exc)
            log.warning("DeckLink session loop ended with error: %s", exc)
        finally:
            await self.close(reason=reason)

    async def _dispatch(self, channel: int, body: bytes) -> None:
        if channel == CH_VIDEO:
            if self.callbacks.on_video is not None:
                kind, pts_us, w, h, data = unpack_video_payload(body)
                self.callbacks.on_video(kind, pts_us, w, h, data)
        elif channel == CH_AUDIO:
            if self.callbacks.on_audio is not None:
                codec, rate, ch, pts_us, data = unpack_audio_payload(body)
                self.callbacks.on_audio(codec, rate, ch, pts_us, data)
        elif channel == CH_CONTROL:
            if self.callbacks.on_control is not None:
                self.callbacks.on_control(unpack_json(body))
        elif channel == CH_FILE_CTRL:
            await self.files.handle_control(unpack_json(body))
        elif channel == CH_FILE_DATA:
            await self.files.handle_data(body)
        elif channel == CH_CHAT:
            if self.callbacks.on_chat is not None:
                self.callbacks.on_chat(unpack_json(body))
        elif channel == CH_CLIPBOARD:
            if self.callbacks.on_clipboard is not None:
                obj = unpack_json(body)
                self.callbacks.on_clipboard(str(obj.get("text") or ""))

    async def close(self, reason: str = "closed") -> None:
        if self._closed:
            return
        self._closed = True
        self.files.close()
        if self._read_task is not None and self._read_task is not asyncio.current_task():
            self._read_task.cancel()
        try:
            self._writer.close()
            await self._writer.wait_closed()
        except Exception:
            pass
        if self.callbacks.on_disconnected is not None:
            try:
                self.callbacks.on_disconnected(reason)
            except Exception:
                pass


async def connect_decklink(
    host: str,
    port: int,
    password: str,
    client_id: str,
    client_name: str,
    platform: str = "windows",
    callbacks: SessionCallbacks | None = None,
    download_dir: str | Path | None = None,
    timeout: float = 10.0,
) -> DeckLinkSession:
    """Connect as a DeckLink client to a remote Android/Windows host using its 6-digit password."""
    reader, writer = await asyncio.wait_for(
        asyncio.open_connection(host, port, limit=4 * 1024 * 1024),
        timeout=timeout,
    )
    try:
        # 1. Read server hello (seq 0)
        ch, _, raw_hello = await asyncio.wait_for(read_frame(reader, expected_seq=0), timeout=timeout)
        if ch != CH_AUTH:
            raise ProtocolError("Expected CH_AUTH hello from server")
        hello = unpack_json(raw_hello)
        if hello.get("op") != "hello" or int(hello.get("version") or 0) != PROTOCOL_VERSION:
            raise ProtocolError("Incompatible DeckLink server hello")

        server_nonce = bytes.fromhex(str(hello.get("server_nonce") or ""))
        client_nonce = random_nonce()
        proof = compute_auth_proof(password, server_nonce, client_nonce, "client")

        # 2. Send client auth (seq 0)
        auth_req = {
            "op": "auth",
            "client_id": client_id,
            "client_name": client_name,
            "platform": platform,
            "client_nonce": client_nonce.hex(),
            "proof": proof,
            "requested": ["video", "audio", "control", "file_transfer", "chat", "clipboard"],
        }
        writer.write(pack_frame(CH_AUTH, 0, pack_json(auth_req)))
        await writer.drain()

        # 3. Read server auth_result (seq 1)
        ch2, _, raw_res = await asyncio.wait_for(read_frame(reader, expected_seq=1), timeout=timeout)
        if ch2 != CH_AUTH:
            raise ProtocolError("Expected CH_AUTH response")
        res = unpack_json(raw_res)
        if res.get("op") != "auth_ok":
            raise AuthenticationError(str(res.get("error") or "Sai mật khẩu kết nối"))

        expected_srv = compute_auth_proof(password, server_nonce, client_nonce, "server")
        if not verify_auth_proof(expected_srv, str(res.get("server_proof") or "")):
            raise AuthenticationError("Xác thực hai chiều với thiết bị đối tác thất bại")

        session_key = derive_session_key(password, server_nonce, client_nonce)
        cipher = SessionCipher(session_key, send_dir=DIR_CLIENT_TO_SERVER, recv_dir=DIR_SERVER_TO_CLIENT)
        peer = PeerInfo(
            device_id=str(hello.get("device_id") or ""),
            device_name=str(hello.get("device_name") or host),
            platform=str(hello.get("platform") or "android"),
            remote_ip=host,
            screen_width=int(res.get("screen_width") or 1080),
            screen_height=int(res.get("screen_height") or 2400),
            capabilities=list(hello.get("capabilities") or []),
        )
        session = DeckLinkSession(
            reader=reader,
            writer=writer,
            cipher=cipher,
            send_seq_start=1,
            recv_seq_start=2,
            peer=peer,
            callbacks=callbacks,
            download_dir=download_dir,
        )
        session.start_reader()
        return session
    except Exception:
        writer.close()
        raise


class DeckLinkHostServer:
    """DeckLink v1 Host Server accepting incoming remote control & file-transfer connections."""

    def __init__(
        self,
        device_id: str,
        device_name: str,
        password_provider: Callable[[], str],
        host: str = "0.0.0.0",
        port: int = 7200,
        platform: str = "windows",
        screen_size_provider: Callable[[], tuple[int, int]] | None = None,
        session_factory: Callable[[PeerInfo], SessionCallbacks] | None = None,
        on_session_started: Callable[[DeckLinkSession], None] | None = None,
        download_dir: str | Path | None = None,
    ):
        self.device_id = device_id
        self.device_name = device_name
        self._password_provider = password_provider
        self.host = host
        self.requested_port = port
        self.port = 0
        self.platform = platform
        self._screen_size_provider = screen_size_provider or (lambda: (1080, 2400))
        self._session_factory = session_factory
        self._on_session_started = on_session_started
        self.download_dir = download_dir

        self._rate_limiter = AuthRateLimiter()
        self._server: asyncio.AbstractServer | None = None
        self.sessions: set[DeckLinkSession] = set()

    async def start(self) -> int:
        try:
            self._server = await asyncio.start_server(
                self._handle_client,
                host=self.host,
                port=self.requested_port,
                limit=4 * 1024 * 1024,
            )
        except OSError:
            self._server = await asyncio.start_server(
                self._handle_client,
                host=self.host,
                port=0,
                limit=4 * 1024 * 1024,
            )
        self.port = self._server.sockets[0].getsockname()[1]
        log.info("DeckLink host server listening on %s:%d", self.host, self.port)
        return self.port

    async def stop(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            self._server = None
        for sess in list(self.sessions):
            await sess.close("server_stopped")
        self.sessions.clear()

    async def _handle_client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        peername = writer.get_extra_info("peername") or ("0.0.0.0", 0)
        remote_ip = str(peername[0])

        if self._rate_limiter.is_locked(remote_ip):
            err = {"op": "auth_fail", "error": "Quá nhiều lần nhập sai mật khẩu. Vui lòng thử lại sau 60 giây."}
            writer.write(pack_frame(CH_AUTH, 0, pack_json(err)))
            await writer.drain()
            writer.close()
            return

        try:
            server_nonce = random_nonce()
            hello = {
                "op": "hello",
                "version": PROTOCOL_VERSION,
                "device_id": self.device_id,
                "device_name": self.device_name,
                "platform": self.platform,
                "server_nonce": server_nonce.hex(),
                "capabilities": ["video", "audio", "control", "file_transfer", "chat", "clipboard"],
            }
            writer.write(pack_frame(CH_AUTH, 0, pack_json(hello)))
            await writer.drain()

            ch, _, raw_auth = await asyncio.wait_for(read_frame(reader, expected_seq=0), timeout=15.0)
            if ch != CH_AUTH:
                raise ProtocolError("Expected CH_AUTH")
            auth = unpack_json(raw_auth)
            if auth.get("op") != "auth":
                raise ProtocolError("Invalid auth op")

            client_nonce = bytes.fromhex(str(auth.get("client_nonce") or ""))
            password = self._password_provider()
            expected_client_proof = compute_auth_proof(password, server_nonce, client_nonce, "client")

            if not verify_auth_proof(expected_client_proof, str(auth.get("proof") or "")):
                locked = self._rate_limiter.record_failure(remote_ip)
                msg = (
                    "Quá nhiều lần thử sai. Tạm khoá 60 giây."
                    if locked
                    else "Sai mật khẩu phiên (6 chữ số)."
                )
                writer.write(pack_frame(CH_AUTH, 1, pack_json({"op": "auth_fail", "error": msg})))
                await writer.drain()
                writer.close()
                return

            self._rate_limiter.record_success(remote_ip)
            sw, sh = self._screen_size_provider()
            srv_proof = compute_auth_proof(password, server_nonce, client_nonce, "server")
            ok_resp = {
                "op": "auth_ok",
                "server_proof": srv_proof,
                "screen_width": sw,
                "screen_height": sh,
            }
            writer.write(pack_frame(CH_AUTH, 1, pack_json(ok_resp)))
            await writer.drain()

            session_key = derive_session_key(password, server_nonce, client_nonce)
            cipher = SessionCipher(session_key, send_dir=DIR_SERVER_TO_CLIENT, recv_dir=DIR_CLIENT_TO_SERVER)
            peer = PeerInfo(
                device_id=str(auth.get("client_id") or ""),
                device_name=str(auth.get("client_name") or remote_ip),
                platform=str(auth.get("platform") or "unknown"),
                remote_ip=remote_ip,
                capabilities=list(auth.get("requested") or []),
            )
            cbs = self._session_factory(peer) if self._session_factory else SessionCallbacks()
            orig_disc = cbs.on_disconnected

            session_ref: list[DeckLinkSession] = []

            def _on_disc(reason: str) -> None:
                if session_ref:
                    self.sessions.discard(session_ref[0])
                if orig_disc:
                    orig_disc(reason)

            cbs.on_disconnected = _on_disc
            session = DeckLinkSession(
                reader=reader,
                writer=writer,
                cipher=cipher,
                send_seq_start=2,
                recv_seq_start=1,
                peer=peer,
                callbacks=cbs,
                download_dir=self.download_dir,
            )
            session_ref.append(session)
            self.sessions.add(session)
            if self._on_session_started is not None:
                self._on_session_started(session)
            await session.start_reader()
        except Exception as exc:
            log.debug("DeckLink client handshake failed from %s: %s", remote_ip, exc)
            try:
                writer.close()
            except Exception:
                pass
