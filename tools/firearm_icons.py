# -*- coding: utf-8 -*-
"""弹药的物品图标（16×16 像素画）与物品模型。

用法：uv run --with numpy --with pillow python tools/firearm_icons.py [预览目录]

全部沿同一条对角线斜放（底在左下、头在右上），左上受光，一像素深色描边：
- 霰弹：纸壳、金属底火座、末端一圈封口。纸壳颜色分金属：铜绿壳铜底、铁红壳黄铜底、下界合金黑壳暗底。
- 步枪弹：黄铜弹壳，肩部收窄，弹头是金属本色（铜、钢、下界合金）。
- 空弹头：黄铜弹壳，弹头是一枚削尖的紫水晶。
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'

OUTLINE = (34, 22, 24)
# 每种颜色：亮、固有、暗
BRASS = ((238, 200, 118), (196, 150, 72), (132, 92, 40))
COPPER = ((236, 150, 100), (190, 104, 62), (122, 62, 36))
STEEL = ((222, 226, 232), (168, 172, 182), (104, 108, 120))
NETHERITE = ((112, 98, 106), (74, 64, 70), (44, 38, 44))
AMETHYST = ((226, 186, 250), (168, 118, 216), (104, 68, 156))
HULLS = {
    'copper': ((92, 150, 96), (60, 116, 70), (36, 76, 46)),
    'iron': ((214, 78, 60), (178, 46, 40), (120, 28, 28)),
    'netherite': ((84, 76, 88), (52, 46, 56), (30, 26, 34)),
}
CRIMPS = {'copper': (44, 92, 54), 'iron': (150, 36, 34), 'netherite': (40, 34, 44)}
BASES = {'copper': COPPER, 'iron': BRASS, 'netherite': NETHERITE}
TIPS = {'copper': COPPER, 'iron': STEEL, 'netherite': NETHERITE}

A, B = np.array([3.2, 12.8]), np.array([12.6, 3.4])


def draw(paint):
    """沿 A→B 的轴画一个回转体：paint(t) 返回 (颜色, 半径)，t 是轴向 0–1；颜色是单色或（亮、固有、暗）三元组。"""
    img = np.zeros((16, 16, 4), np.uint8)
    axis = (B - A) / np.linalg.norm(B - A)
    side = np.array([axis[1], -axis[0]])
    length = np.linalg.norm(B - A)
    for y in range(16):
        for x in range(16):
            p = np.array([x + .5, y + .5]) - A
            t, s = p.dot(axis) / length, p.dot(side)
            if not 0 <= t <= 1:
                continue
            colours, radius = paint(t)
            if abs(s) > radius:
                continue
            if isinstance(colours[0], int):
                col = colours
            else:
                shade = 0 if s > radius * .45 else 1 if s > -radius * .25 else 2   # 左上受光
                col = colours[shade]
            img[y, x] = (*col, 255)
    solid = img[..., 3] > 0
    edge = np.zeros_like(solid)
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        edge |= np.roll(np.roll(solid, dy, 0), dx, 1)
    img[edge & ~solid] = (*OUTLINE, 255)
    return Image.fromarray(img, 'RGBA')


def shell(metal):
    hull, crimp, base = HULLS[metal], CRIMPS[metal], BASES[metal]

    def paint(t):
        if t < .28:
            return (base[2] if abs(t - .27) < .03 else base), 2.6
        if t > .93:
            return crimp, 2.6
        return hull, 2.6
    return draw(paint)


def rifle(tip):
    def paint(t):
        if t < .06:
            return BRASS[2], 2.2                       # 底缘
        if t < .52:
            return BRASS, 2.0                          # 弹壳
        if t < .58:
            return BRASS, 2.0 - (t - .52) / .06 * .4   # 收肩
        u = (t - .58) / .42                            # 弹头：圆尖
        return tip, 1.6 * max(0., 1 - u * u) ** .5
    return draw(paint)


def blank():
    def paint(t):
        if t < .06:
            return BRASS[2], 2.2
        if t < .5:
            return BRASS, 2.0
        u = (t - .5) / .5                              # 紫水晶：削尖的晶体
        return AMETHYST, 2.1 * (1 - u ** 1.4)
    return draw(paint)


ICONS = {
    'copper_buckshot_shell': lambda: shell('copper'),
    'buckshot_shell': lambda: shell('iron'),
    'netherite_buckshot_shell': lambda: shell('netherite'),
    'copper_rifle_round': lambda: rifle(TIPS['copper']),
    'iron_rifle_round': lambda: rifle(TIPS['iron']),
    'netherite_rifle_round': lambda: rifle(TIPS['netherite']),
    'blank_round': blank,
}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def main():
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    sheet = Image.new('RGBA', (16 * len(ICONS) + 2 * (len(ICONS) + 1), 20), (198, 198, 198, 255))
    for i, (name, make) in enumerate(ICONS.items()):
        icon = make()
        icon.save(ASSETS / f'textures/item/{name}.png')
        write_json(ASSETS / f'models/item/{name}.json',
                   {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'logos_dimension:item/{name}'}})
        write_json(ASSETS / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{name}'}})
        sheet.alpha_composite(icon, (2 + i * 18, 2))
    if out:
        out.mkdir(parents=True, exist_ok=True)
        sheet.resize((sheet.width * 8, sheet.height * 8), Image.NEAREST).save(out / 'rounds_x8.png')
    print('ok')


if __name__ == '__main__':
    main()
