# -*- coding: utf-8 -*-
"""弹药的物品图标（16×16 像素画）与物品模型。

用法：uv run --with numpy --with pillow python tools/firearm_icons.py [预览目录]

霰弹：红色纸壳、黄铜底火座，斜放（底在左下、封口在右上），一像素深色描边。
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'

HULL = ((178, 46, 40), (214, 78, 60), (120, 28, 28))      # 纸壳：固有色、亮、暗
BRASS = ((196, 150, 72), (238, 200, 118), (132, 92, 40))
CRIMP = (150, 36, 34)
OUTLINE = (34, 22, 24)


def shell():
    """沿对角线画一个圆柱：轴从 (3.5, 12.5) 到 (12.5, 3.5)，半径 2.6；轴向 0–0.28 是黄铜、末端一圈封口。"""
    img = np.zeros((16, 16, 4), np.uint8)
    a, b = np.array([3.2, 12.8]), np.array([12.6, 3.4])
    axis = (b - a) / np.linalg.norm(b - a)
    side = np.array([axis[1], -axis[0]])
    length = np.linalg.norm(b - a)
    for y in range(16):
        for x in range(16):
            p = np.array([x + .5, y + .5]) - a
            t, s = p.dot(axis) / length, p.dot(side)
            if not (0 <= t <= 1 and abs(s) <= 2.6):
                continue
            shade = 0 if s > 1.2 else 1 if s > -.6 else 2   # 左上受光
            if t < .28:
                col = BRASS[[1, 0, 2][shade]]
                if abs(t - .27) < .03:
                    col = BRASS[2]
            elif t > .93:
                col = CRIMP
            else:
                col = HULL[[1, 0, 2][shade]]
            img[y, x] = (*col, 255)
    solid = img[..., 3] > 0
    edge = np.zeros_like(solid)
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        edge |= np.roll(np.roll(solid, dy, 0), dx, 1)
    img[edge & ~solid] = (*OUTLINE, 255)
    return Image.fromarray(img, 'RGBA')


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def main():
    name = 'buckshot_shell'
    icon = shell()
    icon.save(ASSETS / f'textures/item/{name}.png')
    write_json(ASSETS / f'models/item/{name}.json',
               {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'logos_dimension:item/{name}'}})
    write_json(ASSETS / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{name}'}})
    if len(sys.argv) > 1:
        out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
        icon.resize((256, 256), Image.NEAREST).save(out / f'{name}_x16.png')
    print('ok')


if __name__ == '__main__':
    main()
