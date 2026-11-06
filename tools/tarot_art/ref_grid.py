"""给终稿盖一层 112×192 坐标网格，方便读出轮廓坐标。只是读图工具，输出不进入贴图。

用法：python tools/tarot_art/ref_grid.py 00_fool              整张，每 8 格一条线，每 16 格标数字
      python tools/tarot_art/ref_grid.py 00_fool 30 20 70 60   放大 x 30–70、y 20–60 这一块，每 2 格一条线
"""
from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
from registry import reference_image  # noqa: E402

W, H = 112, 192


def build(card_id, box=None):
    ref = reference_image(card_id)
    if ref is None:
        raise SystemExit('找不到终稿：把 art/mystical_cat_tarot 放在仓库的上级目录，或设置 TAROT_REFERENCE')
    x0, y0, x1, y1 = box or (0, 0, W, H)
    sx = ref.width / W
    sy = ref.height / H
    crop = ref.crop((int(x0 * sx), int(y0 * sy), int(x1 * sx), int(y1 * sy)))
    k = 7 if box is None else max(min(1400 // (x1 - x0), 1400 // (y1 - y0)), 4)
    out = crop.resize(((x1 - x0) * k, (y1 - y0) * k), Image.LANCZOS)
    d = ImageDraw.Draw(out, 'RGBA')
    minor = 8 if box is None else 2
    major = 16 if box is None else 10
    for x in range(x0 - x0 % minor + minor, x1, minor):
        col = (255, 60, 60, 170) if x % major == 0 else (255, 255, 255, 60)
        d.line([((x - x0) * k, 0), ((x - x0) * k, out.height)], fill=col)
        if x % major == 0:
            d.rectangle([(x - x0) * k + 1, 0, (x - x0) * k + 24, 11], fill=(0, 0, 0, 200))
            d.text(((x - x0) * k + 2, 0), str(x), fill=(255, 255, 90, 255))
    for y in range(y0 - y0 % minor + minor, y1, minor):
        col = (255, 60, 60, 170) if y % major == 0 else (255, 255, 255, 60)
        d.line([(0, (y - y0) * k), (out.width, (y - y0) * k)], fill=col)
        if y % major == 0:
            d.rectangle([0, (y - y0) * k + 1, 24, (y - y0) * k + 12], fill=(0, 0, 0, 200))
            d.text((2, (y - y0) * k + 1), str(y), fill=(255, 255, 90, 255))
    return out


def main(argv):
    card_id = argv[0]
    box = tuple(int(v) for v in argv[1:5]) if len(argv) >= 5 else None
    out_dir = Path(os.environ.get('TAROT_GRID_DIR', Path(tempfile.gettempdir()) / 'tarot_grid'))
    out_dir.mkdir(parents=True, exist_ok=True)
    name = f"{card_id}{'_' + '_'.join(map(str, box)) if box else ''}.png"
    path = out_dir / name
    build(card_id, box).save(path)
    print(path)


if __name__ == '__main__':
    main(sys.argv[1:])
