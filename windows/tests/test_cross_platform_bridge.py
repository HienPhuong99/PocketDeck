"""Live TCP cross-platform integration test: Python (Windows Client) <-> Kotlin (Android Host).

Exercises real DeckLink v1 wire framing, mutual HMAC-SHA256 challenge-response authentication,
AES-256-GCM directional session encryption, remote directory browsing, 256 KiB multi-chunk
bidirectional file transfer with SHA-256 verification (including Vietnamese Unicode filenames),
live chat, clipboard sync, and remote control events over an actual loopback TCP socket.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest

from pocketdeck.protocol.session import AuthenticationError, SessionCallbacks, connect_decklink


def _find_java() -> Path | None:
    candidates: list[Path] = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    candidates.append(
        Path.home() / ".pocketdeck-tools" / "jdk-17" / "bin" / ("java.exe" if os.name == "nt" else "java")
    )
    for c in candidates:
        if c.is_file():
            return c
    which_java = shutil.which("java")
    return Path(which_java) if which_java else None


def _ensure_kotlin_test_classpath(repo_root: Path, java_bin: Path) -> str | None:
    cp_file = repo_root / "android" / "app" / "build" / "tmp" / "unit_test_classpath.txt"
    host_class = (
        repo_root
        / "android"
        / "app"
        / "build"
        / "tmp"
        / "kotlin-classes"
        / "debugUnitTest"
        / "com"
        / "pocketdeck"
        / "android"
        / "protocol"
        / "CrossPlatformHostMainKt.class"
    )
    if cp_file.is_file() and host_class.is_file():
        txt = cp_file.read_text(encoding="utf-8").strip()
        if txt:
            return txt

    # Try generating via Gradle wrapper if Android SDK is installed locally or in CI
    sdk_dir: Path | None = None
    for env_key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env_key):
            p = Path(os.environ[env_key])
            if p.is_dir():
                sdk_dir = p
                break
    if sdk_dir is None:
        local_sdk = Path.home() / ".pocketdeck-tools" / "android-sdk"
        if local_sdk.is_dir():
            sdk_dir = local_sdk

    if sdk_dir is None:
        return None

    gradlew = repo_root / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradlew.is_file():
        return None

    env = dict(os.environ)
    env["JAVA_HOME"] = str(java_bin.parent.parent)
    env["ANDROID_HOME"] = str(sdk_dir)
    env["ANDROID_SDK_ROOT"] = str(sdk_dir)
    proc = subprocess.run(
        [str(gradlew), "printDebugUnitTestClasspath", "--no-daemon"],
        cwd=str(repo_root / "android"),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=240,
        check=False,
    )
    if proc.returncode != 0 or not cp_file.is_file():
        return None
    return cp_file.read_text(encoding="utf-8").strip()


def test_python_windows_to_kotlin_android_live_bridge(tmp_path: Path) -> None:
    repo_root = Path(__file__).resolve().parents[2]
    java_bin = _find_java()
    if java_bin is None:
        pytest.skip("Java runtime not found; skipping Kotlin JVM live bridge test")

    classpath = _ensure_kotlin_test_classpath(repo_root, java_bin)
    if not classpath:
        pytest.skip("Android SDK / Kotlin unit test classpath not present in this environment")

    android_storage = tmp_path / "kotlin_android_storage"
    (android_storage / "Download").mkdir(parents=True, exist_ok=True)
    # Create a >512 KiB binary file (3 chunks of 256 KiB) with Vietnamese Unicode filename on Android side
    android_sample = android_storage / "Download" / "Ảnh_từ_Android_Tiếng_Việt.bin"
    android_payload = (b"PocketDeck-Kotlin-Android-Chunk-" * 20_000) + os.urandom(4096)
    android_sample.write_bytes(android_payload)

    pc_root = tmp_path / "pc_workspace"
    pc_send_dir = pc_root / "send"
    pc_recv_dir = pc_root / "recv"
    pc_send_dir.mkdir(parents=True, exist_ok=True)
    pc_recv_dir.mkdir(parents=True, exist_ok=True)

    # Create a >512 KiB binary file on PC side to push to Android
    pc_sample = pc_send_dir / "Báo_cáo_từ_Windows_2026.bin"
    pc_payload = (b"PocketDeck-Python-Windows-Chunk-" * 21_000) + os.urandom(4096)
    pc_sample.write_bytes(pc_payload)

    proc = subprocess.Popen(
        [
            str(java_bin),
            "-Dfile.encoding=UTF-8",
            "-cp",
            classpath,
            "com.pocketdeck.android.protocol.CrossPlatformHostMainKt",
            str(android_storage),
            "555666777",
            "654321",
        ],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )

    try:
        assert proc.stdout is not None
        ready_line = proc.stdout.readline().strip()
        assert ready_line.startswith("READY PORT="), f"Kotlin host failed to start: {ready_line} / {proc.stderr.read() if proc.stderr else ''}"
        port = int(ready_line.split("=", 1)[1])

        async def _exercise_bridge() -> None:
            # 1. Verify wrong password fails cleanly with AuthenticationError
            with pytest.raises(AuthenticationError):
                await connect_decklink(
                    host="127.0.0.1",
                    port=port,
                    password="000000",
                    client_id="111222333",
                    client_name="Windows PC Pytest",
                    download_dir=pc_recv_dir,
                )

            # 2. Connect with valid 6-digit password & set up chat/progress listeners
            chat_replies: list[dict] = []
            chat_evt = asyncio.Event()
            pulled_done_evt = asyncio.Event()
            loop = asyncio.get_running_loop()

            def on_chat(msg: dict) -> None:
                chat_replies.append(msg)
                loop.call_soon_threadsafe(chat_evt.set)

            def on_progress(prog) -> None:
                if prog.direction == "receive" and prog.status == "completed" and prog.filename == android_sample.name:
                    loop.call_soon_threadsafe(pulled_done_evt.set)

            sess = await connect_decklink(
                host="127.0.0.1",
                port=port,
                password="654321",
                client_id="111222333",
                client_name="Windows PC Pytest",
                callbacks=SessionCallbacks(on_chat=on_chat, on_transfer_progress=on_progress),
                download_dir=pc_recv_dir,
            )

            try:
                assert sess.peer.device_id == "555666777"
                assert sess.peer.platform == "android"
                assert sess.peer.screen_width == 1080 and sess.peer.screen_height == 2400

                # 3. Browse remote Android roots & Download folder via UltraViewer RPC
                rfs = sess.files.remote_fs()
                roots = await rfs.list_roots()
                assert any(r.name == "Bộ nhớ trong" for r in roots)

                dl_listing = await rfs.list_dir(str(android_storage / "Download"))
                assert any(e.name == android_sample.name and e.size == len(android_payload) for e in dl_listing.entries)

                # Create a new remote folder on Android from Windows
                created_remote_dir = await rfs.mkdir(str(android_storage / "Download"), "Tu_PC_Gui_Sang")
                assert Path(created_remote_dir).is_dir()

                # 4. Push multi-chunk file (Windows Python -> Android Kotlin) with SHA-256 verification
                push_results = await sess.files.send_items([pc_sample], remote_dest_dir=created_remote_dir)
                assert len(push_results) == 1
                assert push_results[0].status == "completed", f"Push failed: {push_results[0].error}"
                landed_on_android = Path(created_remote_dir) / pc_sample.name
                assert landed_on_android.is_file()
                assert hashlib.sha256(landed_on_android.read_bytes()).hexdigest() == hashlib.sha256(pc_payload).hexdigest()

                # 5. Pull multi-chunk file (Android Kotlin -> Windows Python) with SHA-256 verification
                await sess.files.request_pull([str(android_sample)], local_dest_dir=pc_recv_dir)
                await asyncio.wait_for(pulled_done_evt.wait(), timeout=15.0)
                landed_on_pc = pc_recv_dir / android_sample.name
                assert landed_on_pc.is_file()
                assert hashlib.sha256(landed_on_pc.read_bytes()).hexdigest() == hashlib.sha256(android_payload).hexdigest()

                # 6. Live bidirectional Chat + Clipboard + Control over AES-256-GCM channels
                await sess.send_chat("Windows User", "Xin chào Android từ Python!")
                await asyncio.wait_for(chat_evt.wait(), timeout=5.0)
                assert any("Phản hồi từ Kotlin: Xin chào Android từ Python!" in str(m.get("text", "")) for m in chat_replies)

                await sess.send_clipboard("Clipboard Tiếng Việt 123")
                await sess.send_control("tap", x=540, y=1200)
                await asyncio.sleep(0.15)

                assert (android_storage / "last_clipboard.txt").read_text(encoding="utf-8") == "Clipboard Tiếng Việt 123"
                ctrl_logged = json.loads((android_storage / "last_control.json").read_text(encoding="utf-8"))
                assert ctrl_logged["action"] == "tap"
                assert int(ctrl_logged["x"]) == 540 and int(ctrl_logged["y"]) == 1200
            finally:
                await sess.close()

        asyncio.run(_exercise_bridge())
    finally:
        if proc.stdin:
            try:
                proc.stdin.write("QUIT\n")
                proc.stdin.flush()
            except Exception:
                pass
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
