# -*- coding: utf-8 -*-
"""弹药的物品图标（16×16 像素画）与物品模型。

用法：uv run --with numpy --with pillow python tools/firearm_icons.py [预览目录]

全部沿同一条对角线斜放（底在左下、头在右上），左上受光，一像素深色描边：
- 霰弹：纸壳、金属底火座、末端一圈封口。纸壳颜色分金属：铜绿壳铜底、铁红壳黄铜底、下界合金黑壳暗底。
- 步枪弹：黄铜弹壳，肩部收窄，弹头是金属本色（铜、钢、下界合金）。
- 空弹头：黄铜弹壳，弹头是一枚削尖的紫水晶。
- 弹药图纸：一张蓝图纸，白线画一发弹，角上一枚金色术式印。
- 刻了术式、改装过的弹各有一套贴图（下面「外观」一节），物品模型按组件 round_finish 选。
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


def blueprint():
    """弹药图纸：一张蓝图纸（深蓝底、浅蓝网格、右上角折起），纸上用白线画一发斜放的弹，边上一枚金色的术式印。"""
    img = np.zeros((16, 16, 4), np.uint8)
    paper, grid, edge, ink, fold = (52, 92, 156), (84, 126, 190), (24, 44, 86), (236, 242, 250), (150, 180, 224)
    for y in range(1, 15):
        for x in range(2, 14):
            col = grid if (x - 2) % 3 == 0 or (y - 1) % 3 == 0 else paper
            img[y, x] = (*col, 255)
    # 右上角折起一个小三角
    for y in range(1, 4):
        for x in range(11, 14):
            if x - 11 > y - 1:
                img[y, x] = (0, 0, 0, 0)
            elif x - 11 == y - 1:
                img[y, x] = (*fold, 255)
    # 白线画一发斜放的步枪弹的轮廓
    for t in np.linspace(0, 1, 40):
        cx, cy = 4.5 + 6 * t, 11.5 - 6 * t
        for side in (-1.2, 1.2):
            x, y = int(round(cx + side * .7)), int(round(cy + side * .7))
            if 2 <= x < 14 and 1 <= y < 15:
                img[y, x] = (*ink, 255)
    img[11, 4] = img[12, 5] = (*ink, 255)
    img[4, 11] = (*ink, 255)
    # 左下角一枚金色术式印
    for y, x in ((12, 11), (12, 12), (13, 11), (13, 12)):
        img[y, x] = (232, 190, 90, 255)
    solid = img[..., 3] > 0
    border = np.zeros_like(solid)
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        border |= np.roll(np.roll(solid, dy, 0), dx, 1)
    img[border & ~solid] = (*edge, 255)
    return Image.fromarray(img, 'RGBA')


# ---------------------------------------------------------------- 外观（术式档位、改装）
# 一发弹的外观组件 logos_dimension:round_finish（docs/FIREARMS.md 第 9、10 节）：物品模型按它换贴图。
# 术式弹按档位换弹头（霰弹换封口和一道色环）：基础在金属上加金纹，晶辉紫水晶尖，回响幽匿纹，炼成死灰黑尖，
# 白垩白尖，天穹蓝里透金，贤者红色晶尖。改装弹：燃烧黄尖、水银镜银尖、烟火弹多一层按烟火之星染色的星纹。

GOLD = ((255, 226, 120), (232, 186, 70), (160, 112, 30))
SCULK = ((60, 140, 150), (22, 66, 78), (10, 32, 40))
CAPUT = ((86, 64, 62), (48, 34, 34), (24, 16, 16))
CHALK = ((252, 250, 244), (226, 222, 210), (176, 170, 156))
FIRMAMENT = ((126, 176, 246), (58, 104, 200), (28, 54, 126))
SAGE = ((255, 132, 128), (200, 34, 48), (112, 12, 26))
SULFUR = ((252, 240, 130), (226, 200, 56), (154, 124, 22))
MERCURY = ((252, 253, 255), (198, 208, 222), (122, 132, 152))
FIREWORK_TIP = ((96, 86, 98), (62, 54, 66), (36, 30, 40))
SPECK_SCULK, SPECK_GOLD = (90, 232, 226), (246, 206, 96)

# 每种外观：弹头（霰弹的封口、色环）的颜色、是不是削尖的晶体、点缀、金纹
FINISHES = {
    'spell_1': dict(tip=None, crystal=False, speck=SPECK_GOLD, gold=True),
    'spell_2': dict(tip=AMETHYST, crystal=True, speck=None, gold=False),
    'spell_3': dict(tip=SCULK, crystal=False, speck=SPECK_SCULK, gold=False),
    'spell_4': dict(tip=CAPUT, crystal=False, speck=None, gold=False),
    'spell_5': dict(tip=CHALK, crystal=False, speck=None, gold=False),
    'spell_6': dict(tip=FIRMAMENT, crystal=False, speck=SPECK_GOLD, gold=False),
    'spell_7': dict(tip=SAGE, crystal=True, speck=None, gold=False),
    'sulfur': dict(tip=SULFUR, crystal=False, speck=None, gold=False),
    'mercury': dict(tip=MERCURY, crystal=False, speck=None, gold=False),
    'firework': dict(tip=FIREWORK_TIP, crystal=False, speck=None, gold=False),
}
SPELLS = [f'spell_{i}' for i in range(1, 8)]
MODS = ['sulfur', 'mercury', 'firework']


def draw_ts(paint):
    """和 draw 一样沿对角线画回转体，但 paint(t, s, x, y) 能按横向位置和像素坐标上点缀；返回 None 表示这里没东西。"""
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
            got = paint(t, s, x, y)
            if got is None:
                continue
            colours, radius = got
            if abs(s) > radius:
                continue
            if isinstance(colours[0], int):
                col = colours
            else:
                shade = 0 if s > radius * .45 else 1 if s > -radius * .25 else 2
                col = colours[shade]
            img[y, x] = (*col, 255)
    solid = img[..., 3] > 0
    edge = np.zeros_like(solid)
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        edge |= np.roll(np.roll(solid, dy, 0), dx, 1)
    img[edge & ~solid] = (*OUTLINE, 255)
    return Image.fromarray(img, 'RGBA')


def speckled(x, y):
    """固定的伪随机点缀：大约五分之一的像素。"""
    return (x * 7 + y * 13) % 5 == 0


def tip_paint(f, metal_tip, t0, pointed):
    """弹头部分（t ≥ t0）：外观的颜色、晶体削尖、点缀；基础档保留金属本色加一道金纹。"""
    def paint(t, s, x, y):
        u = (t - t0) / (1 - t0)
        if f['crystal'] or pointed:
            radius = 2.0 * (1 - u ** 1.4)
        else:
            radius = 1.6 * max(0., 1 - u * u) ** .5
        if f['tip'] is None:
            # 基础档：金属本色，一道金环加几点金纹
            colour = GOLD if abs(u - .35) < .09 or (f['speck'] and speckled(x, y)) else metal_tip
        else:
            colour = f['tip']
            if f['speck'] and speckled(x, y) and abs(s) < radius - .4:
                colour = f['speck']
        return colour, radius
    return paint


def rifle_finish(metal_tip, finish):
    f = FINISHES[finish]
    tip = tip_paint(f, metal_tip, .55 if f['crystal'] else .58, False)

    def paint(t, s, x, y):
        if t < .06:
            return BRASS[2], 2.2
        if t < .52:
            if f['gold'] and (abs(t - .2) < .025 or abs(t - .38) < .025):
                return GOLD, 2.0
            return BRASS, 2.0
        if t < .58 and not f['crystal']:
            return BRASS, 2.0 - (t - .52) / .06 * .4
        return tip(t, s, x, y)
    return draw_ts(paint)


def blank_finish(finish):
    f = FINISHES[finish]
    tip = tip_paint(dict(f, tip=f['tip'] or AMETHYST), AMETHYST, .5, True)

    def paint(t, s, x, y):
        if t < .06:
            return BRASS[2], 2.2
        if t < .5:
            if f['gold'] and (abs(t - .18) < .025 or abs(t - .36) < .025):
                return GOLD, 2.0
            return BRASS, 2.0
        return tip(t, s, x, y)
    return draw_ts(paint)


def shell_finish(metal, finish):
    """霰弹：纸壳不变，封口和一道色环换成外观的颜色；基础档在纸壳上加两道金纹。"""
    f = FINISHES[finish]
    hull, base = HULLS[metal], BASES[metal]
    mark = f['tip'] or GOLD

    def paint(t, s, x, y):
        if t < .28:
            return (base[2] if abs(t - .27) < .03 else base), 2.6
        if t > .9:
            colour = mark
            if f['speck'] and speckled(x, y):
                colour = f['speck']
            return colour, 2.6
        if .62 < t < .7 and finish != 'spell_1':
            return mark, 2.6
        if f['gold'] and (abs(t - .45) < .03 or abs(t - .75) < .03):
            return GOLD, 2.6
        return hull, 2.6
    return draw_ts(paint)


def firework_mark(kind):
    """烟火弹多出来的一层：一颗白色的小星（按烟火之星的颜色染色），霰弹在纸壳中间，步枪弹在弹头上。"""
    img = np.zeros((16, 16, 4), np.uint8)
    axis = (B - A) / np.linalg.norm(B - A)
    t = .62 if kind == 'buckshot' else .78
    cx, cy = A + axis * np.linalg.norm(B - A) * t
    for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        x, y = int(cx + dx), int(cy + dy)
        img[y, x] = (255, 255, 255, 255)
    return Image.fromarray(img, 'RGBA')


def finished_icons():
    """每种弹在每种外观下的贴图：名字 → (贴图, 是否带烟火染色层)。"""
    out = {}
    tips = {'copper': COPPER, 'iron': STEEL, 'netherite': NETHERITE}
    for metal in ('copper', 'iron', 'netherite'):
        shell_id = 'buckshot_shell' if metal == 'iron' else f'{metal}_buckshot_shell'
        rifle_id = f'{metal}_rifle_round'
        for finish in SPELLS + MODS:
            out[f'{shell_id}_{finish}'] = (shell_id, finish, shell_finish(metal, finish))
            out[f'{rifle_id}_{finish}'] = (rifle_id, finish, rifle_finish(tips[metal], finish))
    for finish in SPELLS:
        out[f'blank_round_{finish}'] = ('blank_round', finish, blank_finish(finish))
    return out


def write_finishes(out_dir=None):
    icons = finished_icons()
    marks = {'buckshot': firework_mark('buckshot'), 'rifle': firework_mark('rifle')}
    for kind, img in marks.items():
        img.save(ASSETS / f'textures/item/firework_mark_{kind}.png')
    by_item = {}
    for name, (item, finish, img) in icons.items():
        img.save(ASSETS / f'textures/item/{name}.png')
        textures = {'layer0': f'logos_dimension:item/{name}'}
        if finish == 'firework':
            textures['layer1'] = f'logos_dimension:item/firework_mark_{"buckshot" if "buckshot" in item else "rifle"}'
        write_json(ASSETS / f'models/item/{name}.json', {'parent': 'minecraft:item/generated', 'textures': textures})
        by_item.setdefault(item, []).append((finish, name))
    for item, cases in by_item.items():
        def model(name, finish):
            m = {'type': 'minecraft:model', 'model': f'logos_dimension:item/{name}'}
            if finish == 'firework':
                m['tints'] = [{'type': 'minecraft:constant', 'value': -1}, {'type': 'minecraft:firework', 'default': -7697782}]
            return m
        write_json(ASSETS / f'items/{item}.json', {'model': {
            'type': 'minecraft:select', 'property': 'minecraft:component', 'component': 'logos_dimension:round_finish',
            'cases': [{'when': finish, 'model': model(name, finish)} for finish, name in cases],
            'fallback': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{item}'}}})
    if out_dir:
        # 预览：每行一种弹，从左到右是普通、七档术式、三种改装
        rows = ['copper_buckshot_shell', 'buckshot_shell', 'netherite_buckshot_shell', 'copper_rifle_round', 'iron_rifle_round',
                'netherite_rifle_round', 'blank_round']
        cols = [None] + SPELLS + MODS
        sheet = Image.new('RGBA', (18 * len(cols) + 2, 18 * len(rows) + 2), (198, 198, 198, 255))
        for r, item in enumerate(rows):
            for c, finish in enumerate(cols):
                path = ASSETS / f'textures/item/{item if finish is None else f"{item}_{finish}"}.png'
                if not path.exists():
                    continue
                icon = Image.open(path).convert('RGBA')
                if finish == 'firework':
                    tinted = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
                    mark = np.array(marks['buckshot' if 'buckshot' in item else 'rifle'])
                    mark[..., :3] = (mark[..., :3] * np.array([240, 90, 60]) / 255).astype(np.uint8)
                    tinted = Image.fromarray(mark, 'RGBA')
                    icon = Image.alpha_composite(icon, tinted)
                sheet.alpha_composite(icon, (2 + c * 18, 2 + r * 18))
        out_dir.mkdir(parents=True, exist_ok=True)
        sheet.resize((sheet.width * 6, sheet.height * 6), Image.NEAREST).save(out_dir / 'finishes_x6.png')


ICONS = {
    'copper_buckshot_shell': lambda: shell('copper'),
    'buckshot_shell': lambda: shell('iron'),
    'netherite_buckshot_shell': lambda: shell('netherite'),
    'copper_rifle_round': lambda: rifle(TIPS['copper']),
    'iron_rifle_round': lambda: rifle(TIPS['iron']),
    'netherite_rifle_round': lambda: rifle(TIPS['netherite']),
    'blank_round': blank,
    'ammo_blueprint': blueprint,
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
    write_finishes(out)
    print('ok')


if __name__ == '__main__':
    main()
