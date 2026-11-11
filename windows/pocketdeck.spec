# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller specification for standalone PocketDeck for Windows (`dist/PocketDeck/PocketDeck.exe`)."""
from __future__ import annotations

import shutil
from pathlib import Path

SPEC_DIR = Path(SPECPATH).resolve()
REPO_ROOT = SPEC_DIR.parent

ICON_FILE = SPEC_DIR / "assets" / "pocketdeck.ico"

datas: list[tuple[str, str]] = []
if ICON_FILE.is_file():
    datas.append((str(ICON_FILE), "assets"))

# Bundle compiled Android APK inside PocketDeck.exe distribution for 1-click USB install
apk_candidates = [
    REPO_ROOT / "dist-apk" / "PocketDeck-debug.apk",
    REPO_ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk",
]
for apk in apk_candidates:
    if apk.is_file():
        staged_dir = REPO_ROOT / "build" / "pyinstaller-staging"
        staged_dir.mkdir(parents=True, exist_ok=True)
        staged_apk = staged_dir / "PocketDeck-android.apk"
        shutil.copy2(apk, staged_apk)
        datas.append((str(staged_apk), "."))
        break

# Bundle essential ADB + scrcpy-server binaries (~7 MB) when present locally
scrcpy_dirs = [
    REPO_ROOT / "tools" / "scrcpy",
    Path.home() / "AppData" / "Local" / "PocketDeck" / "tools" / "scrcpy",
    Path.home() / ".pocketdeck-tools" / "scrcpy",
]
essential_tools = ("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "scrcpy-server")
for sdir in scrcpy_dirs:
    if (sdir / "adb.exe").is_file() and (sdir / "scrcpy-server").is_file():
        for fname in essential_tools:
            fp = sdir / fname
            if fp.is_file():
                datas.append((str(fp), "tools/scrcpy"))
        break

excludes = [
    "tkinter",
    "unittest",
    "pytest",
    "matplotlib",
    "scipy",
    "pandas",
    "PySide6.QtWebEngineCore",
    "PySide6.QtWebEngineWidgets",
    "PySide6.QtQml",
    "PySide6.QtQuick",
    "PySide6.QtQuickWidgets",
    "PySide6.Qt3DCore",
    "PySide6.Qt3DRender",
    "PySide6.QtPdf",
    "PySide6.QtDesigner",
    "PySide6.QtSql",
    "PySide6.QtTest",
]

hiddenimports = [
    "zeroconf._utils.ipaddress",
    "zeroconf._handlers.answers",
    "_sounddevice_data",
]

a = Analysis(
    [str(SPEC_DIR / "pocketdeck.pyw")],
    pathex=[str(SPEC_DIR)],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=excludes,
    noarchive=False,
    optimize=0,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="PocketDeck",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=str(ICON_FILE) if ICON_FILE.is_file() else None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="PocketDeck",
)
