"""Start-with-Windows helper using HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run (full Unicode)."""
from __future__ import annotations

import logging
import os
import sys
from pathlib import Path

from . import APP_NAME

log = logging.getLogger(__name__)

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def build_startup_command() -> str:
    """Build the command stored in HKCU Run (--tray mode so it starts quietly in system tray)."""
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" --tray'
    launcher = Path(__file__).resolve().parents[1] / "pocketdeck.pyw"
    pyw = Path(sys.executable).with_name("pythonw.exe")
    exe = pyw if pyw.exists() else Path(sys.executable)
    return f'"{exe}" "{launcher}" --tray'


def is_enabled() -> bool:
    if os.name != "nt":
        return False
    try:
        import winreg

        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_READ) as key:
            val, _ = winreg.QueryValueEx(key, APP_NAME)
            return bool(val)
    except OSError:
        return False


def set_enabled(enable: bool) -> bool:
    if os.name != "nt":
        return False
    try:
        import winreg

        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            if enable:
                winreg.SetValueEx(key, APP_NAME, 0, winreg.REG_SZ, build_startup_command())
            else:
                try:
                    winreg.DeleteValue(key, APP_NAME)
                except FileNotFoundError:
                    pass
        return True
    except OSError as exc:
        log.warning("Could not update Windows startup entry: %s", exc)
        return False


def refresh_if_enabled() -> None:
    """Keep startup path fresh if user moved the application folder."""
    if is_enabled():
        set_enabled(True)
