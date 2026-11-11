"""Generate crisp multi-resolution Windows .ico (`windows/assets/pocketdeck.ico`) using Pillow."""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw


def render_master_icon(master_size: int = 1024) -> Image.Image:
    """Render 4x supersampled PocketDeck icon (slate card + phone bezel + bidirectional transfer arrows)."""
    img = Image.new("RGBA", (master_size, master_size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    pad = int(master_size * 0.06)
    bg_box = [pad, pad, master_size - pad, master_size - pad]
    d.rounded_rectangle(
        bg_box,
        radius=int(master_size * 0.22),
        fill=(30, 41, 59, 255),      # #1e293b
        outline=(59, 130, 246, 255),  # #3b82f6
        width=max(4, int(master_size * 0.028)),
    )

    # Phone silhouette
    pw = int(master_size * 0.44)
    ph = int(master_size * 0.68)
    px = (master_size - pw) // 2
    py = (master_size - ph) // 2
    d.rounded_rectangle(
        [px, py, px + pw, py + ph],
        radius=int(master_size * 0.075),
        fill=(15, 23, 42, 255),       # #0f172a
        outline=(96, 165, 250, 255),  # #60a5fa
        width=max(4, int(master_size * 0.032)),
    )

    # Top speaker pill
    sp_w = int(pw * 0.28)
    sp_h = max(6, int(master_size * 0.016))
    sp_x = (master_size - sp_w) // 2
    sp_y = py + int(ph * 0.07)
    d.rounded_rectangle(
        [sp_x, sp_y, sp_x + sp_w, sp_y + sp_h],
        radius=sp_h // 2,
        fill=(148, 163, 184, 255),
    )

    # Center blue transfer badge
    bw = int(pw * 0.68)
    bh = int(ph * 0.32)
    bx = (master_size - bw) // 2
    by = (master_size - bh) // 2 + int(ph * 0.02)
    d.rounded_rectangle(
        [bx, by, bx + bw, by + bh],
        radius=int(master_size * 0.045),
        fill=(37, 99, 235, 255),      # #2563eb
    )

    # Crisp geometric bidirectional arrows inside badge
    stroke = max(6, int(master_size * 0.024))
    head = int(bh * 0.16)
    left_x = bx + int(bw * 0.22)
    right_x = bx + int(bw * 0.78)
    top_y = by + int(bh * 0.36)
    bot_y = by + int(bh * 0.64)

    # Right-pointing top arrow (->)
    d.line([(left_x, top_y), (right_x, top_y)], fill=(255, 255, 255, 255), width=stroke)
    d.line([(right_x - head, top_y - head), (right_x, top_y)], fill=(255, 255, 255, 255), width=stroke)
    d.line([(right_x - head, top_y + head), (right_x, top_y)], fill=(255, 255, 255, 255), width=stroke)

    # Left-pointing bottom arrow (<-)
    d.line([(left_x, bot_y), (right_x, bot_y)], fill=(191, 219, 254, 255), width=stroke)
    d.line([(left_x + head, bot_y - head), (left_x, bot_y)], fill=(191, 219, 254, 255), width=stroke)
    d.line([(left_x + head, bot_y + head), (left_x, bot_y)], fill=(191, 219, 254, 255), width=stroke)

    return img


def generate_ico(dest: Path) -> Path:
    dest.parent.mkdir(parents=True, exist_ok=True)
    master = render_master_icon(1024)
    base_256 = master.resize((256, 256), Image.Resampling.LANCZOS)
    sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
    base_256.save(dest, format="ICO", sizes=sizes)
    return dest


def main() -> int:
    default_out = Path(__file__).resolve().parents[1] / "assets" / "pocketdeck.ico"
    target = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else default_out
    saved = generate_ico(target)
    print(f"Saved icon: {saved} ({saved.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
