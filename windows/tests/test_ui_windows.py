"""Offscreen PySide6 UI tests for PhoneWindow, ConnectDialog, UltraViewer FileTransferWindow, Settings & Wizard."""
from __future__ import annotations

import asyncio
import os
import threading
import time
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication  # noqa: E402

from pocketdeck.adb.manager import AdbManager  # noqa: E402
from pocketdeck.config import Settings  # noqa: E402
from pocketdeck.discovery import LanDiscovery  # noqa: E402
from pocketdeck.ui import ConnectDialog, PhoneWindow, SettingsDialog, SetupWizardDialog  # noqa: E402
from pocketdeck.ui.theme import format_bytes, format_eta, format_speed, save_app_ico  # noqa: E402
from tools.fake_android import FakeAndroidDevice  # noqa: E402


def _ensure_qapp() -> QApplication:
    app = QApplication.instance()
    if app is None:
        app = QApplication(["PocketDeckTests"])
    return app  # type: ignore[return-value]


def _pump_until(app: QApplication, predicate, timeout: float = 8.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        app.processEvents()
        if predicate():
            return True
        time.sleep(0.03)
    app.processEvents()
    return bool(predicate())


def test_theme_helpers_and_dialogs(tmp_path: Path) -> None:
    app = _ensure_qapp()
    assert format_bytes(512) == "512 B"
    assert format_bytes(2048) == "2.0 KB"
    assert format_speed(1024 * 1024 * 5) == "5.0 MB/s"
    assert format_eta(125) == "02:05"

    ico_path = save_app_ico(tmp_path / "pocketdeck.ico")
    assert ico_path.is_file() and ico_path.stat().st_size > 500

    settings = Settings(path=tmp_path / "settings.json", local_mode=True)
    dlg_set = SettingsDialog(settings)
    dlg_set.name_edit.setText("Máy Trạm Kỹ Thuật")
    dlg_set.bitrate_spin.setValue(12)
    dlg_set._save_and_accept()
    assert settings["name"] == "Máy Trạm Kỹ Thuật"
    assert settings["bitrate_mbps"] == 12

    adb = AdbManager()
    wiz = SetupWizardDialog(adb)
    app.processEvents()
    wiz.close()

    disc = LanDiscovery(settings.device_id, settings["name"], 0, udp_port=0, enable_mdns=False)
    conn_dlg = ConnectDialog(
        settings=settings,
        discovery=disc,
        adb=adb,
        host_port_fn=lambda: 7200,
        on_connect_decklink=lambda *_a: None,
        on_connect_adb=lambda *_a: None,
    )
    app.processEvents()
    conn_dlg.close()


def test_phone_window_and_ultraviewer_file_window_live_e2e(tmp_path: Path) -> None:
    app = _ensure_qapp()

    # Run FakeAndroidDevice in its own background event loop
    fake_loop = asyncio.new_event_loop()
    fake = FakeAndroidDevice(
        device_id="482910375",
        password="654321",
        host="127.0.0.1",
        port=0,
        width=360,
        height=780,
        fps=20,
        storage_dir=tmp_path / "android_storage",
    )
    port_holder: list[int] = []
    ready_evt = threading.Event()

    def _run_fake() -> None:
        asyncio.set_event_loop(fake_loop)
        p = fake_loop.run_until_complete(fake.start())
        port_holder.append(p)
        ready_evt.set()
        fake_loop.run_forever()
        fake_loop.run_until_complete(fake.stop())
        fake_loop.close()

    fake_thread = threading.Thread(target=_run_fake, daemon=True)
    fake_thread.start()
    assert ready_evt.wait(timeout=5.0)
    fake_port = port_holder[0]

    grab_dir = tmp_path / "grabs"
    settings = Settings(path=tmp_path / "win_settings.json", local_mode=True)
    settings["port"] = 0
    settings["download_dir"] = str(tmp_path / "win_downloads")

    win = PhoneWindow(settings=settings, open_audio_device=False, grab_dir=grab_dir, enable_tray=False)
    win.show()
    app.processEvents()

    try:
        # Standby grab should be captured before connecting
        assert _pump_until(app, lambda: (grab_dir / "standby.png").is_file(), timeout=3.0)

        # Connect PhoneWindow to FakeAndroidDevice and open FileTransferWindow automatically
        win.backend.submit(win.backend.connect_decklink_async(f"127.0.0.1:{fake_port}", "654321", open_files=True))

        # Wait until live H.264 video frame renders inside PhoneWindow AND FileTransferWindow opens
        assert _pump_until(app, lambda: win._qimage is not None and win.file_win is not None, timeout=8.0)
        assert _pump_until(app, lambda: (grab_dir / "connected.png").is_file(), timeout=4.0)

        fwin = win.file_win
        assert fwin is not None

        # Wait until remote Android filesystem entries load into right pane
        assert _pump_until(app, lambda: fwin.remote_pane.table.rowCount() >= 2, timeout=5.0)

        # Navigate remote pane into Download and send a Vietnamese file from Windows -> Android
        fwin.remote_pane.navigate(str(tmp_path / "android_storage" / "Download"))
        assert _pump_until(
            app,
            lambda: fwin.remote_pane.current_path.endswith("Download"),
            timeout=4.0,
        )

        src_file = tmp_path / "báo-cáo-thử-nghiệm.txt"
        payload = "Nội dung truyền file qua cửa sổ đôi kiểu UltraViewer trên PocketDeck!"
        src_file.write_text(payload, encoding="utf-8")

        fwin.send_local_paths([str(src_file)])
        expected_remote = tmp_path / "android_storage" / "Download" / "báo-cáo-thử-nghiệm.txt"
        assert _pump_until(app, lambda: expected_remote.is_file(), timeout=6.0)
        assert expected_remote.read_text(encoding="utf-8") == payload

        # Send a live chat message from FileTransferWindow -> FakeAndroidDevice
        fwin.chat_input.setText("Xin chào từ giao diện Windows!")
        fwin.send_chat_message()
        assert _pump_until(app, lambda: len(fake.received_chats) == 1, timeout=4.0)
        assert fake.received_chats[0]["text"] == "Xin chào từ giao diện Windows!"
    finally:
        win.close()
        app.processEvents()
        fake_loop.call_soon_threadsafe(fake_loop.stop)
        fake_thread.join(timeout=4.0)
