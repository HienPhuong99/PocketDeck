"""PocketDeck for Windows — CLI & GUI entrypoint (`python -m pocketdeck`)."""
from __future__ import annotations

import argparse
import ctypes
import logging
import os
import sys
from pathlib import Path

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication

from . import APP_NAME, __version__
from .config import Settings
from .ui.phone_window import PhoneWindow
from .ui.theme import make_app_icon


def _configure_logging(log_file: Path, verbose: bool = False) -> None:
    level = logging.DEBUG if verbose else logging.INFO
    handlers: list[logging.Handler] = []
    try:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.FileHandler(log_file, mode="w", encoding="utf-8"))
    except OSError:
        pass
    if sys.stderr is not None:
        handlers.append(logging.StreamHandler(sys.stderr))
    logging.basicConfig(
        level=level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        handlers=handlers,
    )


def _set_windows_app_id() -> None:
    if os.name == "nt":
        try:
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("PocketDeck.Windows.App.1")
        except Exception:
            pass


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    ap = argparse.ArgumentParser(prog="PocketDeck", description="Mirror, control, and transfer files with Android")
    ap.add_argument("--version", action="version", version=f"{APP_NAME} {__version__}")
    ap.add_argument("--local", action="store_true", help="Bind loopback only and isolate settings (no LAN/Firewall)")
    ap.add_argument("--port", type=int, default=None, help="Override local DeckLink TCP port")
    ap.add_argument("--connect", metavar="ID_OR_HOST", default="", help="Auto-connect to Partner ID or host:port")
    ap.add_argument("--password", default="", help="6-digit session password for --connect")
    ap.add_argument("--files", action="store_true", help="Open UltraViewer Dual-Pane File Transfer Window on connect")
    ap.add_argument("--adb", metavar="SERIAL", default="", help="Auto-connect via ADB serial (or 'auto')")
    ap.add_argument("--record", action="store_true", help="Auto-start MP4 recording once video begins")
    ap.add_argument("--tray", action="store_true", help="Start minimized in Windows System Tray")
    ap.add_argument("--no-audio", action="store_true", help="Disable speaker audio device output")
    ap.add_argument("--grab", metavar="DIR", default="", help="Save rendered window PNG snapshots into DIR")
    ap.add_argument("--quit-after", type=float, default=0.0, help="Cleanly exit after N seconds")
    ap.add_argument("-v", "--verbose", action="store_true", help="Enable debug logging")
    return ap.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    _set_windows_app_id()

    settings = Settings(local_mode=bool(args.local))
    if args.port is not None:
        settings["port"] = int(args.port)

    _configure_logging(settings.data_dir / "pocketdeck.log", verbose=bool(args.verbose))

    app = QApplication.instance() or QApplication(sys.argv[:1])
    app.setApplicationName(APP_NAME)
    app.setWindowIcon(make_app_icon())
    app.setQuitOnLastWindowClosed(not bool(args.tray))

    grab_dir = Path(args.grab).resolve() if args.grab else None
    win = PhoneWindow(
        settings=settings,
        open_audio_device=not bool(args.no_audio),
        grab_dir=grab_dir,
        enable_tray=not bool(args.local),
    )

    if args.tray:
        win.hide()
    else:
        win.show()

    if args.connect:
        QTimer.singleShot(
            120,
            lambda: win.backend.submit(
                win.backend.connect_decklink_async(args.connect, args.password, open_files=bool(args.files))
            ),
        )
    elif args.adb:
        def _auto_adb() -> None:
            serial = args.adb
            if serial == "auto":
                ready = [d.serial for d in win.backend.adb.list_devices() if d.state == "device"]
                serial = ready[0] if ready else ""
            if serial:
                win.backend.submit(win.backend.connect_adb_async(serial, open_files=bool(args.files)))

        QTimer.singleShot(150, _auto_adb)

    if args.record:
        def _check_auto_rec() -> None:
            if win.recorder is None and win.backend.decoder.last_frame is not None:
                win.toggle_recording()

        rec_timer = QTimer(win)
        rec_timer.setInterval(400)
        rec_timer.timeout.connect(_check_auto_rec)
        rec_timer.start()

    if args.quit_after and args.quit_after > 0:
        QTimer.singleShot(int(args.quit_after * 1000), win._quit_app)

    return int(app.exec())


if __name__ == "__main__":
    raise SystemExit(main())
