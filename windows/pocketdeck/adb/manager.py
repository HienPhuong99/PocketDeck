"""ADB device discovery, Wireless Debugging pairing, APK installer, and binary locator."""
from __future__ import annotations

import logging
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from ..config import default_data_dir

log = logging.getLogger(__name__)

CREATE_NO_WINDOW = 0x08000000 if os.name == "nt" else 0


@dataclass
class AdbDevice:
    serial: str
    state: str  # "device", "unauthorized", "offline"
    model: str = ""
    product: str = ""
    transport_id: str = ""

    @property
    def is_wireless(self) -> bool:
        return ":" in self.serial or "._adb-tls-connect." in self.serial

    @property
    def display_name(self) -> str:
        clean_model = self.model.replace("_", " ").strip()
        if clean_model:
            return f"{clean_model} ({self.serial})"
        return self.serial


def parse_adb_devices(output: str) -> list[AdbDevice]:
    """Parse `adb devices -l` stdout into structured AdbDevice records."""
    devices: list[AdbDevice] = []
    for raw_line in (output or "").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], parts[1]
        kv: dict[str, str] = {}
        for tok in parts[2:]:
            if ":" in tok:
                k, v = tok.split(":", 1)
                kv[k] = v
        devices.append(
            AdbDevice(
                serial=serial,
                state=state,
                model=kv.get("model", ""),
                product=kv.get("product", ""),
                transport_id=kv.get("transport_id", ""),
            )
        )
    return devices


def find_adb_and_scrcpy() -> tuple[Path | None, Path | None]:
    """Locate `adb.exe`/`adb` and `scrcpy-server` across bundled, user-local, and dev paths."""
    exe_name = "adb.exe" if os.name == "nt" else "adb"
    candidates: list[Path] = []

    if getattr(sys, "frozen", False):
        meipass = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
        candidates.append(meipass / "tools" / "scrcpy")
        candidates.append(Path(sys.executable).parent / "tools" / "scrcpy")

    repo_root = Path(__file__).resolve().parents[3]
    candidates.extend(
        [
            repo_root / "tools" / "scrcpy",
            default_data_dir() / "tools" / "scrcpy",
            Path.home() / ".pocketdeck-tools" / "scrcpy",
            Path.home() / ".pocketdeck-tools" / "android-sdk" / "platform-tools",
        ]
    )

    adb_path: Path | None = None
    server_path: Path | None = None

    for folder in candidates:
        if adb_path is None and (folder / exe_name).is_file():
            adb_path = folder / exe_name
        if server_path is None and (folder / "scrcpy-server").is_file():
            server_path = folder / "scrcpy-server"

    if adb_path is None:
        which_adb = shutil.which(exe_name)
        if which_adb:
            adb_path = Path(which_adb)

    return adb_path, server_path


def find_bundled_apk() -> Path | None:
    """Locate PocketDeck Android APK for 1-click USB installation onto a connected phone."""
    candidates: list[Path] = []
    if getattr(sys, "frozen", False):
        meipass = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
        exe_dir = Path(sys.executable).parent
        candidates.extend(
            [
                meipass / "PocketDeck-android.apk",
                meipass / "PocketDeck-debug.apk",
                exe_dir / "PocketDeck-android.apk",
                exe_dir / "PocketDeck-debug.apk",
            ]
        )

    repo_root = Path(__file__).resolve().parents[3]
    candidates.extend(
        [
            repo_root / "dist-apk" / "PocketDeck-debug.apk",
            repo_root / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk",
        ]
    )
    for c in candidates:
        if c.is_file():
            return c
    return None


class AdbManager:
    """Thin, safe wrapper around the `adb` command-line binary."""

    def __init__(self, adb_path: Path | None = None, scrcpy_server_path: Path | None = None):
        found_adb, found_srv = find_adb_and_scrcpy()
        self.adb_path = adb_path or found_adb
        self.scrcpy_server_path = scrcpy_server_path or found_srv

    @property
    def available(self) -> bool:
        return self.adb_path is not None and self.adb_path.is_file()

    def run(
        self,
        args: list[str],
        serial: str | None = None,
        timeout: float = 15.0,
        check: bool = True,
    ) -> subprocess.CompletedProcess[bytes]:
        if not self.available or self.adb_path is None:
            raise FileNotFoundError("Không tìm thấy công cụ adb.exe. Hãy chạy install.bat hoặc tải công cụ Android.")
        cmd = [str(self.adb_path)]
        if serial:
            cmd.extend(["-s", serial])
        cmd.extend(args)
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            creationflags=CREATE_NO_WINDOW,
            check=False,
        )
        if check and proc.returncode != 0:
            err = proc.stderr.decode("utf-8", "replace").strip() or proc.stdout.decode("utf-8", "replace").strip()
            raise RuntimeError(err or f"adb exited with code {proc.returncode}")
        return proc

    def list_devices(self) -> list[AdbDevice]:
        if not self.available:
            return []
        proc = self.run(["devices", "-l"], timeout=8.0, check=False)
        return parse_adb_devices(proc.stdout.decode("utf-8", "replace"))

    def pair_wireless(self, host_port: str, pairing_code: str) -> str:
        """Pair with Android 11+ Wireless Debugging (`adb pair ip:port code`)."""
        proc = self.run(["pair", host_port.strip(), pairing_code.strip()], timeout=20.0, check=True)
        return proc.stdout.decode("utf-8", "replace").strip()

    def connect_wireless(self, host_port: str) -> str:
        target = host_port.strip()
        if ":" not in target:
            target = f"{target}:5555"
        proc = self.run(["connect", target], timeout=12.0, check=True)
        out = proc.stdout.decode("utf-8", "replace").strip()
        if "cannot connect" in out.lower() or "failed to connect" in out.lower():
            raise RuntimeError(out)
        return out

    def enable_tcpip(self, serial: str, port: int = 5555) -> str:
        proc = self.run(["tcpip", str(port)], serial=serial, timeout=10.0, check=True)
        return proc.stdout.decode("utf-8", "replace").strip()

    def device_wlan_ip(self, serial: str) -> str:
        proc = self.run(["shell", "ip", "-f", "inet", "addr", "show", "wlan0"], serial=serial, timeout=6.0, check=False)
        txt = proc.stdout.decode("utf-8", "replace")
        for line in txt.splitlines():
            s = line.strip()
            if s.startswith("inet "):
                return s.split()[1].split("/")[0]
        return ""

    def install_apk(self, serial: str, apk_path: Path | None = None) -> str:
        target_apk = apk_path or find_bundled_apk()
        if target_apk is None or not target_apk.is_file():
            raise FileNotFoundError("Không tìm thấy file cài đặt PocketDeck-android.apk")
        proc = self.run(["install", "-r", str(target_apk)], serial=serial, timeout=90.0, check=True)
        return proc.stdout.decode("utf-8", "replace").strip()
