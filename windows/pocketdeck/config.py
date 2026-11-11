"""Persistent settings, Partner ID (9 digits), and Session Password management."""
from __future__ import annotations

import json
import logging
import os
import secrets
import socket
from pathlib import Path
from typing import Any

from . import APP_NAME

log = logging.getLogger(__name__)


def format_partner_id(raw_id: str) -> str:
    """Format a 9-digit ID as 'XXX XXX XXX' like UltraViewer."""
    digits = "".join(ch for ch in str(raw_id) if ch.isdigit())
    if len(digits) == 9:
        return f"{digits[:3]} {digits[3:6]} {digits[6:]}"
    return str(raw_id).strip()


def normalize_partner_target(raw: str) -> str:
    """Normalize user input: keep IP/host:port intact, or collapse 9-digit ID spaces."""
    s = (raw or "").strip()
    only_digits = "".join(ch for ch in s if ch.isdigit())
    if len(only_digits) == 9 and all(ch.isdigit() or ch in " -." for ch in s) and s.count(".") != 3:
        return only_digits
    return s


def generate_partner_id() -> str:
    """Generate a random 9-digit Partner ID (first digit 1..9)."""
    first = secrets.randbelow(9) + 1
    rest = secrets.randbelow(100_000_000)
    return f"{first}{rest:08d}"


def generate_session_password(length: int = 6) -> str:
    """Generate a numeric session password (default 6 digits)."""
    return "".join(str(secrets.randbelow(10)) for _ in range(length))


def default_data_dir(local_mode: bool = False) -> Path:
    base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
    folder = f"{APP_NAME}-Local" if local_mode else APP_NAME
    p = Path(base) / folder
    p.mkdir(parents=True, exist_ok=True)
    return p


def default_download_dir() -> Path:
    home = Path.home()
    dl = home / "Downloads" / APP_NAME
    dl.mkdir(parents=True, exist_ok=True)
    return dl


def default_media_dirs() -> tuple[Path, Path]:
    home = Path.home()
    pics = home / "Pictures" / APP_NAME
    vids = home / "Videos" / APP_NAME
    pics.mkdir(parents=True, exist_ok=True)
    vids.mkdir(parents=True, exist_ok=True)
    return pics, vids


class Settings:
    """JSON-backed application configuration."""

    DEFAULTS: dict[str, Any] = {
        "name": "",
        "device_id": "",
        "fixed_password": "",
        "use_fixed_password": False,
        "language": "vi",
        "port": 7200,
        "width": 1920,
        "height": 1080,
        "fps": 60,
        "bitrate_mbps": 8,
        "audio": True,
        "always_on_top": False,
        "opacity": 1.0,
        "keep_awake": True,
        "show_nav_bar": True,
        "visible_on_lan": True,
        "download_dir": "",
        "window_x": None,
        "window_y": None,
        "window_h": 760,
        "ed25519_seed": "",
        "recent_partners": [],
    }

    def __init__(self, path: Path | None = None, local_mode: bool = False):
        self.local_mode = local_mode
        self.data_dir = path.parent if path else default_data_dir(local_mode)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.path = path or (self.data_dir / "settings.json")
        self._data: dict[str, Any] = dict(self.DEFAULTS)
        self.session_password: str = generate_session_password()
        self.load()

    def load(self) -> None:
        if self.path.exists():
            try:
                loaded = json.loads(self.path.read_text(encoding="utf-8"))
                if isinstance(loaded, dict):
                    self._data.update(loaded)
            except Exception as exc:
                log.warning("Failed to read settings %s: %s", self.path, exc)

        changed = False
        if not self._data.get("name"):
            self._data["name"] = socket.gethostname() or "Windows-PC"
            changed = True
        dev_id = str(self._data.get("device_id") or "")
        if len(dev_id) != 9 or not dev_id.isdigit():
            self._data["device_id"] = generate_partner_id()
            changed = True
        if not self._data.get("ed25519_seed") or len(str(self._data["ed25519_seed"])) != 64:
            self._data["ed25519_seed"] = secrets.token_hex(32)
            changed = True
        if not self._data.get("download_dir"):
            self._data["download_dir"] = str(default_download_dir())
            changed = True

        if self._data.get("use_fixed_password") and self._data.get("fixed_password"):
            self.session_password = str(self._data["fixed_password"])

        if changed:
            self.save()

    def save(self) -> None:
        try:
            tmp = self.path.with_suffix(".json.tmp")
            tmp.write_text(json.dumps(self._data, indent=2, ensure_ascii=False), encoding="utf-8")
            tmp.replace(self.path)
        except Exception as exc:
            log.warning("Failed to save settings %s: %s", self.path, exc)

    def refresh_password(self) -> str:
        self.session_password = generate_session_password()
        if self._data.get("use_fixed_password"):
            self._data["fixed_password"] = self.session_password
            self.save()
        return self.session_password

    def active_password(self) -> str:
        if self._data.get("use_fixed_password") and self._data.get("fixed_password"):
            return str(self._data["fixed_password"])
        return self.session_password

    def add_recent_partner(self, partner_id: str, name: str = "", host: str = "") -> None:
        items: list[dict[str, str]] = list(self._data.get("recent_partners") or [])
        norm = normalize_partner_target(partner_id)
        items = [it for it in items if it.get("id") != norm]
        items.insert(0, {"id": norm, "name": name, "host": host})
        self._data["recent_partners"] = items[:12]
        self.save()

    @property
    def device_id(self) -> str:
        return str(self._data["device_id"])

    @property
    def formatted_id(self) -> str:
        return format_partner_id(self.device_id)

    def __getitem__(self, key: str) -> Any:
        return self._data[key]

    def __setitem__(self, key: str, value: Any) -> None:
        self._data[key] = value

    def get(self, key: str, default: Any = None) -> Any:
        return self._data.get(key, default)
