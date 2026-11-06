"""放大看牌画的一块，每个像素放大成方块，边上带坐标。审像素级问题用。

用法：python tools/tarot_art/zoom.py 00_fool 28 20 92 62
"""
from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

TEXTURES = Path(__file__).resolve().parents[2] / 'src/main/resources/assets/logos_dimension/textures/item/tarot/major'


def main(argv):
    card_id = argv[0]
    x0, y0, x1, y1 = (int(v) for v in argv[1:5])
    im = Image.open(TEXTURES / f'{card_id}.png').convert('RGB').crop((x0, y0, x1, y1))
    k = max(min(1200 // (x1 - x0), 1200 // (y1 - y0)), 4)
    out = im.resize((im.width * k, im.height * k), Image.NEAREST)
    d = ImageDraw.Draw(out, 'RGBA')
    for x in range(x0 - x0 % 10 + 10, x1, 10):
        d.line([((x - x0) * k, 0), ((x - x0) * k, out.height)], fill=(255, 60, 60, 120))
        d.rectangle([(x - x0) * k + 1, 0, (x - x0) * k + 22, 11], fill=(0, 0, 0, 200))
        d.text(((x - x0) * k + 2, 0), str(x), fill=(255, 255, 90, 255))
    for y in range(y0 - y0 % 10 + 10, y1, 10):
        d.line([(0, (y - y0) * k), (out.width, (y - y0) * k)], fill=(255, 60, 60, 120))
        d.rectangle([0, (y - y0) * k + 1, 22, (y - y0) * k + 12], fill=(0, 0, 0, 200))
        d.text((2, (y - y0) * k + 1), str(y), fill=(255, 255, 90, 255))
    out_dir = Path(os.environ.get('TAROT_GRID_DIR', Path(tempfile.gettempdir()) / 'tarot_grid'))
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f'zoom_{card_id}_{x0}_{y0}_{x1}_{y1}.png'
    out.save(path)
    print(path)


if __name__ == '__main__':
    main(sys.argv[1:])
