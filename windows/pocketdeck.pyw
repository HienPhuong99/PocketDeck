"""Windowless launcher for PocketDeck on Windows (double-clickable via pythonw.exe)."""
from __future__ import annotations

import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from pocketdeck.__main__ import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
