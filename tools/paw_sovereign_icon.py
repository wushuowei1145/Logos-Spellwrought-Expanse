# -*- coding: utf-8 -*-
"""爪印金镑的图标叠层（docs/KOHAKU_COMPANION.md 第 2 节）：底下是灵猫金镑的贴图（textures/item/cat_sovereign.png，按路径复用），
这一层把金镑正面的猫头盖成金面，再压一只小猫爪。

python tools/paw_sovereign_icon.py     写 textures/item/paw_sovereign_paw.png（16×16，只有全透和不透）
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent / 'src/main/resources/assets/logos_dimension/textures/item'
FACE = (226, 178, 58, 255)
EMBLEM = (168, 116, 28, 255)
# 猫爪：四个趾、一个掌垫（x, y），正中在金镑中心
PAW = [(7, 5), (9, 5), (6, 6), (10, 6),
       (7, 8), (8, 8), (9, 8),
       (6, 9), (7, 9), (8, 9), (9, 9), (10, 9),
       (7, 10), (8, 10), (9, 10)]


def main():
    coin = Image.open(ROOT / 'cat_sovereign.png').convert('RGBA')
    src = coin.load()
    out = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = out.load()
    # 正面原来的猫头盖成金面
    for y in range(4, 12):
        for x in range(4, 12):
            if src[x, y] == EMBLEM:
                px[x, y] = FACE
    for x, y in PAW:
        px[x, y] = EMBLEM
    out.save(ROOT / 'paw_sovereign_paw.png')
    print('ok', ROOT / 'paw_sovereign_paw.png')


if __name__ == '__main__':
    main()
