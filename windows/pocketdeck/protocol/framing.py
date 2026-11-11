"""DeckLink v1 binary framing, HMAC challenge-response auth, and AES-256-GCM session encryption."""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import secrets
import struct
import time
from collections import defaultdict
from dataclasses import dataclass
from typing import Any

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

MAGIC = b"PD"
PROTOCOL_VERSION = 1
HEADER_STRUCT = struct.Struct(">2sBBII")  # magic(2), version(1), channel(1), seq(4), length(4) = 12 bytes
HEADER_SIZE = HEADER_STRUCT.size
MAX_FRAME_SIZE = 8 * 1024 * 1024  # 8 MiB safety cap per frame

# Multiplexed Channels
CH_AUTH = 0x01
CH_CONTROL = 0x02
CH_VIDEO = 0x03
CH_AUDIO = 0x04
CH_FILE_CTRL = 0x05
CH_FILE_DATA = 0x06
CH_CHAT = 0x07
CH_CLIPBOARD = 0x08

DIR_CLIENT_TO_SERVER = b"C2S\x00"
DIR_SERVER_TO_CLIENT = b"S2C\x00"


class ProtocolError(Exception):
    """Raised when a DeckLink frame or handshake violates protocol rules."""


class AuthenticationError(ProtocolError):
    """Raised when partner password verification fails or is rate-limited."""


def compute_auth_proof(password: str, server_nonce: bytes, client_nonce: bytes, role: str) -> str:
    """Compute hex HMAC-SHA256 proof for 'client' or 'server'."""
    if len(server_nonce) != 32 or len(client_nonce) != 32:
        raise ValueError("Nonces must be 32 bytes each")
    key = password.strip().encode("utf-8")
    msg = server_nonce + client_nonce + role.encode("ascii")
    return hmac.new(key, msg, hashlib.sha256).hexdigest()


def verify_auth_proof(expected_hex: str, candidate_hex: str) -> bool:
    return hmac.compare_digest(expected_hex.lower(), (candidate_hex or "").lower())


def derive_session_key(password: str, server_nonce: bytes, client_nonce: bytes) -> bytes:
    """Derive 32-byte AES-256-GCM session key via HKDF-SHA256."""
    hkdf = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=server_nonce + client_nonce,
        info=b"PocketDeck-v1-SessionKey",
    )
    return hkdf.derive(password.strip().encode("utf-8"))


def make_aad(version: int, channel: int, seq: int) -> bytes:
    """Bind envelope metadata into AES-GCM Additional Authenticated Data."""
    return struct.pack(">BBI", version, channel, seq)


def make_nonce(direction_prefix: bytes, seq: int) -> bytes:
    """12-byte deterministic unique nonce per direction and sequence number."""
    return direction_prefix[:4] + struct.pack(">Q", seq)


class SessionCipher:
    """Directional AES-256-GCM encryptor/decryptor with strict sequence tracking."""

    def __init__(self, key: bytes, send_dir: bytes, recv_dir: bytes):
        if len(key) != 32:
            raise ValueError("AES-256-GCM key must be 32 bytes")
        self._aead = AESGCM(key)
        self._send_dir = send_dir[:4]
        self._recv_dir = recv_dir[:4]

    def encrypt(self, channel: int, seq: int, plaintext: bytes) -> bytes:
        nonce = make_nonce(self._send_dir, seq)
        aad = make_aad(PROTOCOL_VERSION, channel, seq)
        return self._aead.encrypt(nonce, plaintext, aad)

    def decrypt(self, channel: int, seq: int, ciphertext: bytes) -> bytes:
        nonce = make_nonce(self._recv_dir, seq)
        aad = make_aad(PROTOCOL_VERSION, channel, seq)
        try:
            return self._aead.decrypt(nonce, ciphertext, aad)
        except Exception as exc:
            raise ProtocolError(f"Frame decryption/authentication failed on ch={channel} seq={seq}") from exc


def pack_frame(channel: int, seq: int, payload: bytes, cipher: SessionCipher | None = None) -> bytes:
    """Serialize one DeckLink frame into wire bytes."""
    body = cipher.encrypt(channel, seq, payload) if (cipher is not None and channel != CH_AUTH) else payload
    if len(body) > MAX_FRAME_SIZE:
        raise ProtocolError(f"Frame payload too large: {len(body)} bytes")
    header = HEADER_STRUCT.pack(MAGIC, PROTOCOL_VERSION, channel, seq & 0xFFFFFFFF, len(body))
    return header + body


def unpack_header(header_bytes: bytes) -> tuple[int, int, int]:
    """Unpack 12-byte header -> (channel, seq, body_length)."""
    if len(header_bytes) != HEADER_SIZE:
        raise ProtocolError("Incomplete frame header")
    magic, ver, channel, seq, length = HEADER_STRUCT.unpack(header_bytes)
    if magic != MAGIC:
        raise ProtocolError(f"Invalid frame magic: {magic!r}")
    if ver != PROTOCOL_VERSION:
        raise ProtocolError(f"Unsupported DeckLink protocol version: {ver}")
    if length > MAX_FRAME_SIZE:
        raise ProtocolError(f"Declared frame length exceeds limit: {length}")
    return channel, seq, length


async def read_frame(
    reader: asyncio.StreamReader,
    expected_seq: int | None = None,
    cipher: SessionCipher | None = None,
) -> tuple[int, int, bytes]:
    """Read and optionally decrypt one full DeckLink frame from an asyncio StreamReader."""
    hdr = await reader.readexactly(HEADER_SIZE)
    channel, seq, length = unpack_header(hdr)
    if expected_seq is not None and seq != expected_seq:
        raise ProtocolError(f"Unexpected frame sequence: got {seq}, expected {expected_seq}")
    body = await reader.readexactly(length) if length else b""
    if cipher is not None and channel != CH_AUTH:
        body = cipher.decrypt(channel, seq, body)
    return channel, seq, body


def pack_json(obj: dict[str, Any]) -> bytes:
    return json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def unpack_json(data: bytes) -> dict[str, Any]:
    obj = json.loads(data.decode("utf-8"))
    if not isinstance(obj, dict):
        raise ProtocolError("Expected JSON object payload")
    return obj


# Binary sub-headers for media and file chunks
VIDEO_HDR = struct.Struct(">BQHH")  # kind(1), pts_us(8), width(2), height(2) = 13 bytes
AUDIO_HDR = struct.Struct(">BIBQ")  # codec(1), sample_rate(4), channels(1), pts_us(8) = 14 bytes
FILE_CHUNK_HDR = struct.Struct(">16sQ")  # xfer_uuid(16), offset(8) = 24 bytes

VIDEO_KIND_DATA = 0
VIDEO_KIND_CONFIG = 1

AUDIO_CODEC_PCM16 = 1
AUDIO_CODEC_OPUS = 2
AUDIO_CODEC_AAC = 3


def pack_video_payload(kind: int, pts_us: int, width: int, height: int, data: bytes) -> bytes:
    return VIDEO_HDR.pack(kind, pts_us & 0xFFFFFFFFFFFFFFFF, width & 0xFFFF, height & 0xFFFF) + data


def unpack_video_payload(payload: bytes) -> tuple[int, int, int, int, bytes]:
    if len(payload) < VIDEO_HDR.size:
        raise ProtocolError("Truncated video frame payload")
    kind, pts_us, width, height = VIDEO_HDR.unpack_from(payload, 0)
    return kind, pts_us, width, height, payload[VIDEO_HDR.size :]


def pack_audio_payload(codec: int, sample_rate: int, channels: int, pts_us: int, data: bytes) -> bytes:
    return AUDIO_HDR.pack(codec, sample_rate, channels, pts_us & 0xFFFFFFFFFFFFFFFF) + data


def unpack_audio_payload(payload: bytes) -> tuple[int, int, int, int, bytes]:
    if len(payload) < AUDIO_HDR.size:
        raise ProtocolError("Truncated audio frame payload")
    codec, sample_rate, channels, pts_us = AUDIO_HDR.unpack_from(payload, 0)
    return codec, sample_rate, channels, pts_us, payload[AUDIO_HDR.size :]


def pack_file_chunk(xfer_uuid_bytes: bytes, offset: int, chunk: bytes) -> bytes:
    if len(xfer_uuid_bytes) != 16:
        raise ValueError("xfer_uuid_bytes must be 16 bytes")
    return FILE_CHUNK_HDR.pack(xfer_uuid_bytes, offset) + chunk


def unpack_file_chunk(payload: bytes) -> tuple[bytes, int, bytes]:
    if len(payload) < FILE_CHUNK_HDR.size:
        raise ProtocolError("Truncated file data chunk")
    uid, offset = FILE_CHUNK_HDR.unpack_from(payload, 0)
    return uid, offset, payload[FILE_CHUNK_HDR.size :]


@dataclass
class AuthRateLimiter:
    """Protects the 6-digit session password against brute-force attempts per peer IP."""

    max_failures: int = 5
    window_sec: float = 60.0
    lockout_sec: float = 60.0

    def __post_init__(self) -> None:
        self._failures: dict[str, list[float]] = defaultdict(list)
        self._locked_until: dict[str, float] = {}

    def is_locked(self, ip: str, now: float | None = None) -> bool:
        t = time.monotonic() if now is None else now
        until = self._locked_until.get(ip, 0.0)
        if until > t:
            return True
        if ip in self._locked_until:
            del self._locked_until[ip]
        return False

    def record_failure(self, ip: str, now: float | None = None) -> bool:
        """Record a failed attempt; returns True if IP is now locked out."""
        t = time.monotonic() if now is None else now
        recent = [ts for ts in self._failures[ip] if t - ts <= self.window_sec]
        recent.append(t)
        self._failures[ip] = recent
        if len(recent) >= self.max_failures:
            self._locked_until[ip] = t + self.lockout_sec
            self._failures[ip].clear()
            return True
        return False

    def record_success(self, ip: str) -> None:
        self._failures.pop(ip, None)
        self._locked_until.pop(ip, None)


def random_nonce() -> bytes:
    return secrets.token_bytes(32)
