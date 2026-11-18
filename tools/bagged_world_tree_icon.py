# -*- coding: utf-8 -*-
"""袋装世界树的物品图标（16×16，代码画的）：一只扎口的布袋子，袋口一捧土，土上长着一株迷你世界树。

python tools/bagged_world_tree_icon.py            写 src/main/resources/assets/logos_dimension/textures/item/bagged_world_tree.png
python tools/bagged_world_tree_icon.py --preview  另存一张放大 16 倍的预览到 build/bagged_world_tree_preview.png

光从左上来：树冠、袋子的左上亮，右下暗；描边用各自的深色（同原版物品），不用纯黑。袋口的布在绳子上面收拢、往两边翘开，
绳结的两头垂在右边，袋身一道褶。树冠里有一颗淡绿的生命晶（她额冠、胸针上的那种光）。
"""
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'src/main/resources/assets/logos_dimension/textures/item/bagged_world_tree.png'

PALETTE = {
    '.': None,
    # 树冠：深、中、亮、高光，正中一颗生命晶
    'K': '2f6e46', 'G': '4f9e62', 'L': '86cf8a', 'H': 'bdecb0', 'E': '7ef2bc',
    # 树干
    'T': '7b5536', 't': '5a3b25',
    # 土
    'S': '6b4429', 's': '4a2d1b',
    # 布袋：底色、阴影、深阴影、亮面
    'B': 'c8a067', 'b': '9a7445', 'D': '73522f', 'W': 'e6c88f',
    # 扎口的绳子
    'C': '5e3f22', 'c': '8a6236',
}

ROWS = [
    '......KKKK......',
    '....KKGGLGKK....',
    '...KGGLHHLGGK...',
    '..KGGLHHLGGEGK..',
    '..KGGGLLGGGGGK..',
    '..KKGGGGGEGGKK..',
    '....KKKTtKKK....',
    '...W...Tt..b....',
    '...WbsSTtSsbb...',
    '....CcCCCCcC....',
    '...bBWBBBBbCc...',
    '..bBWWBBBbBbDc..',
    '..bBWWBBBbBBbD..',
    '..bBWBBBBbBbbD..',
    '...DbBBBBBbbD...',
    '....DDDDDDDD....',
]


def draw():
    assert len(ROWS) == 16 and all(len(r) == 16 for r in ROWS), [len(r) for r in ROWS]
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(ROWS):
        for x, ch in enumerate(row):
            hexc = PALETTE[ch]
            if hexc:
                img.putpixel((x, y), (int(hexc[0:2], 16), int(hexc[2:4], 16), int(hexc[4:6], 16), 255))
    return img


def main():
    img = draw()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(OUT)
    if '--preview' in sys.argv:
        prev = ROOT / 'build' / 'bagged_world_tree_preview.png'
        prev.parent.mkdir(parents=True, exist_ok=True)
        bg = Image.new('RGBA', (256, 256), (139, 139, 139, 255))
        bg.alpha_composite(img.resize((256, 256), Image.NEAREST))
        bg.save(prev)
        print(prev)


if __name__ == '__main__':
    main()
