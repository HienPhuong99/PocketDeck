"""Tests for DeckLink v1 framing, encryption, UltraViewer ID/Password auth, and LAN discovery."""
from __future__ import annotations

import asyncio
from pathlib import Path

import pytest

from pocketdeck.config import Settings, format_partner_id, normalize_partner_target
from pocketdeck.discovery import LanDiscovery, parse_host_port
from pocketdeck.protocol.framing import (
    AUDIO_CODEC_PCM16,
    CH_CONTROL,
    DIR_CLIENT_TO_SERVER,
    DIR_SERVER_TO_CLIENT,
    VIDEO_KIND_DATA,
    AuthenticationError,
    AuthRateLimiter,
    ProtocolError,
    SessionCipher,
    compute_auth_proof,
    derive_session_key,
    pack_audio_payload,
    pack_file_chunk,
    pack_frame,
    pack_json,
    pack_video_payload,
    random_nonce,
    read_frame,
    unpack_audio_payload,
    unpack_file_chunk,
    unpack_json,
    unpack_video_payload,
)
from pocketdeck.protocol.session import DeckLinkHostServer, SessionCallbacks, connect_decklink


def test_partner_id_formatting_and_settings(tmp_path: Path) -> None:
    assert format_partner_id("482910375") == "482 910 375"
    assert normalize_partner_target("482 910 375") == "482910375"
    assert normalize_partner_target("482-910-375") == "482910375"
    assert normalize_partner_target("192.168.1.50:7200") == "192.168.1.50:7200"
    assert parse_host_port("482 910 375") is None
    assert parse_host_port("192.168.1.50") == ("192.168.1.50", 7200)
    assert parse_host_port("10.0.0.8:7555") == ("10.0.0.8", 7555)

    cfg = Settings(path=tmp_path / "settings.json")
    assert len(cfg.device_id) == 9 and cfg.device_id.isdigit()
    assert len(cfg.active_password()) == 6 and cfg.active_password().isdigit()
    saved_id = cfg.device_id
    cfg2 = Settings(path=tmp_path / "settings.json")
    assert cfg2.device_id == saved_id


def test_cipher_and_tamper_detection() -> None:
    s_nonce = random_nonce()
    c_nonce = random_nonce()
    key = derive_session_key("123456", s_nonce, c_nonce)
    c2s_enc = SessionCipher(key, DIR_CLIENT_TO_SERVER, DIR_SERVER_TO_CLIENT)
    c2s_dec = SessionCipher(key, DIR_SERVER_TO_CLIENT, DIR_CLIENT_TO_SERVER)

    wire = pack_frame(CH_CONTROL, 7, pack_json({"action": "tap", "x": 500, "y": 900}), cipher=c2s_enc)

    async def _roundtrip(raw: bytes, expected_seq: int = 7) -> bytes:
        reader = asyncio.StreamReader()
        reader.feed_data(raw)
        reader.feed_eof()
        _, _, body = await read_frame(reader, expected_seq=expected_seq, cipher=c2s_dec)
        return body

    decoded = asyncio.run(_roundtrip(wire))
    assert unpack_json(decoded) == {"action": "tap", "x": 500, "y": 900}

    # Tampering channel byte in header must fail AES-GCM AAD check
    tampered = bytearray(wire)
    tampered[3] = 0x07  # swap CH_CONTROL -> CH_CHAT
    with pytest.raises(ProtocolError):
        asyncio.run(_roundtrip(bytes(tampered)))


def test_binary_subpayload_codecs() -> None:
    vp = pack_video_payload(VIDEO_KIND_DATA, 123456789, 1080, 2400, b"\x00\x00\x00\x01\x65hello")
    assert unpack_video_payload(vp) == (VIDEO_KIND_DATA, 123456789, 1080, 2400, b"\x00\x00\x00\x01\x65hello")

    ap = pack_audio_payload(AUDIO_CODEC_PCM16, 48000, 2, 999888, b"\x01\x02\x03\x04")
    assert unpack_audio_payload(ap) == (AUDIO_CODEC_PCM16, 48000, 2, 999888, b"\x01\x02\x03\x04")

    uid = b"0123456789abcdef"
    fp = pack_file_chunk(uid, 524288, b"chunkdata")
    assert unpack_file_chunk(fp) == (uid, 524288, b"chunkdata")


def test_rate_limiter() -> None:
    rl = AuthRateLimiter(max_failures=3, window_sec=60.0, lockout_sec=30.0)
    assert not rl.is_locked("10.0.0.2", now=100.0)
    assert not rl.record_failure("10.0.0.2", now=101.0)
    assert not rl.record_failure("10.0.0.2", now=102.0)
    assert rl.record_failure("10.0.0.2", now=103.0)
    assert rl.is_locked("10.0.0.2", now=120.0)
    assert not rl.is_locked("10.0.0.2", now=135.0)


def test_live_session_auth_and_multiplexing(tmp_path: Path) -> None:
    async def _run() -> None:
        received_controls: list[dict] = []
        received_chats: list[dict] = []
        received_clips: list[str] = []
        received_video: list[tuple] = []

        def make_srv_cbs(_peer) -> SessionCallbacks:
            return SessionCallbacks(
                on_control=received_controls.append,
                on_chat=received_chats.append,
                on_clipboard=received_clips.append,
            )

        host_sessions = []
        srv = DeckLinkHostServer(
            device_id="482910375",
            device_name="Android Pixel 9",
            password_provider=lambda: "654321",
            host="127.0.0.1",
            port=0,
            platform="android",
            screen_size_provider=lambda: (1080, 2400),
            session_factory=make_srv_cbs,
            on_session_started=host_sessions.append,
            download_dir=tmp_path / "srv_dl",
        )
        port = await srv.start()
        try:
            # Wrong password must fail
            with pytest.raises(AuthenticationError):
                await connect_decklink(
                    host="127.0.0.1",
                    port=port,
                    password="000000",
                    client_id="111222333",
                    client_name="Windows PC",
                )

            video_ready = asyncio.Event()

            def on_vid(*args) -> None:
                received_video.append(args)
                video_ready.set()

            client = await connect_decklink(
                host="127.0.0.1",
                port=port,
                password="654321",
                client_id="111222333",
                client_name="Windows PC",
                callbacks=SessionCallbacks(on_video=on_vid),
                download_dir=tmp_path / "cli_dl",
            )
            assert client.peer.device_name == "Android Pixel 9"
            assert (client.peer.screen_width, client.peer.screen_height) == (1080, 2400)

            await client.send_control("text", text="Xin chào Việt Nam")
            await client.send_chat("Windows PC", "Gửi file báo cáo nhé!")
            await client.send_clipboard("OTP: 889922")

            await asyncio.sleep(0.08)
            assert received_controls == [{"action": "text", "text": "Xin chào Việt Nam"}]
            assert received_chats[0]["text"] == "Gửi file báo cáo nhé!"
            assert received_clips == ["OTP: 889922"]

            assert len(host_sessions) == 1
            await host_sessions[0].send_video(VIDEO_KIND_DATA, 33333, 1080, 2400, b"\x00\x00\x00\x01\x65frame")
            await asyncio.wait_for(video_ready.wait(), timeout=2.0)
            assert received_video[0][4] == b"\x00\x00\x00\x01\x65frame"

            await client.close()
        finally:
            await srv.stop()

    asyncio.run(_run())


def test_udp_partner_id_resolution() -> None:
    # Pick a free UDP port so tests never collide
    import socket

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.bind(("127.0.0.1", 0))
        uport = s.getsockname()[1]

    host_disc = LanDiscovery(
        device_id="777888999",
        device_name="Samsung Tab S9",
        tcp_port=7399,
        platform="android",
        udp_port=uport,
        enable_mdns=False,
    )
    cli_disc = LanDiscovery(
        device_id="123123123",
        device_name="PC Phuong",
        tcp_port=7200,
        platform="windows",
        udp_port=0,
        enable_mdns=False,
    )
    cli_disc.udp_port = uport  # target port for active resolve query
    host_disc.start()
    try:
        ip, port = cli_disc.resolve_partner("777 888 999", timeout=1.5)
        assert port == 7399
        assert ip != ""
    finally:
        host_disc.stop()
