# -*- coding: utf-8 -*-
"""从美术交付导入神狐打刀的物品资源。

用法（需要 pillow）：
    uv run --with pillow python tools/import_uchigatana.py "<Minecraft MOD 目录>/art/fox_uchigatana/v02"

- combat 网格：贴图路径改成 *_rig.png，并加 scale（乘在骨架的武器缩放上，第三人称为 1 倍）。
- 刀的贴图改名为 textures/item/fox_uchigatana_<配色>_rig.png，物品图标用不带 _rig 的名字。
- 物品图标按各配色贴图上刀柄、刀镡、刀身的颜色生成 32×32 像素画，斜放、刀尖朝右上；物品栏与展示框用它。
- 手持、掉落与魔力缠绕用的立体物品模型取刀网格的刀身部分（不含刀鞘），坐标与长剑约定相同。
"""
import json
import shutil
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
SCALE = 1.25


def face(texture, cube, side='north'):
    """方块某一面的贴图像素（按行）。"""
    u0, v0, u1, v1 = (int(round(x)) for x in cube['uv'][side])
    return [[texture.getpixel((u, v)) for u in range(min(u0, u1), max(u0, u1))] for v in range(min(v0, v1), max(v0, v1))]


def mean(rows):
    px = [p for row in rows for p in row if p[3] > 0]
    return tuple(sum(p[i] for p in px) // len(px) for i in range(3)) + (255,)


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (255,)


def icon(texture, cubes):
    by = {c['name']: c for c in cubes}
    tsuka = face(texture, by['tsuka_core'])
    column = [row[len(row) // 2] for row in tsuka][::-1]  # 从柄头到刀镡
    blade = mean(face(texture, by['blade_02_shinogi']))
    edge = mean(face(texture, by['blade_02_ha']))
    back = mean(face(texture, by['blade_02_mune']))
    tsuba = mean(face(texture, by['tsuba_octagon_1']))
    brass = mean(face(texture, by['habaki_brass']))
    cap = mean(face(texture, by['kashira_center']))
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))

    def put(x, y, c):
        if 0 <= x < 32 and 0 <= y < 32:
            img.putpixel((x, y), c)
    # 沿对角线从左下（柄头）到右上（刀尖），每一步 x+1、y-1；刀身末段略向上弯出浅弧。
    path = [(3 + i, 28 - i) for i in range(26)]
    for i, (x, y) in enumerate(path):
        if i < 2:
            put(x, y, cap); put(x + 1, y, shade(cap, .7))
        elif i < 9:
            c = column[min(len(column) - 1, (i - 2) * len(column) // 7)]
            put(x, y, c); put(x + 1, y, shade(c, .75)); put(x, y - 1, shade(c, 1.1))
        elif i == 9:
            for dx, dy in ((-2, 2), (-1, 1), (0, 0), (1, -1), (2, -2), (-1, 2), (2, -1), (1, 1), (0, 1), (1, 0)):
                put(x + dx, y + dy, tsuba)
            put(x + 2, y - 2, shade(tsuba, 1.3))
        elif i == 10:
            put(x, y, brass); put(x + 1, y, shade(brass, .8))
        else:
            lift = 1 if i > 20 else 0
            put(x, y - lift, back); put(x + 1, y - lift, blade); put(x + 1, y + 1 - lift, edge)
    tip = path[-1]
    put(tip[0] + 1, tip[1] - 2, edge); put(tip[0] + 2, tip[1] - 3, shade(edge, 1.15))
    return img


def write(path, value):
    path.write_bytes((json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))


def item_model(name, weapon):
    """刀身部分的立体物品模型：像素坐标 = 网格坐标 + (8, 1, 8)，与长剑相同，魔力缠绕按它的表面定位。"""
    w, h = weapon['texture_size']
    offset = (8, 1, 8)
    elements = []
    for cube in weapon['cubes']:
        if cube['part'] != 'weapon':
            continue
        lo = [min(cube['start'][i], cube['end'][i]) + offset[i] for i in range(3)]
        hi = [max(cube['start'][i], cube['end'][i]) + offset[i] for i in range(3)]
        element = {'name': cube['name'], 'from': [round(v, 4) for v in lo], 'to': [round(v, 4) for v in hi],
                   'faces': {side: {'uv': [round(uv[0] * 16 / w, 4), round(uv[1] * 16 / h, 4), round(uv[2] * 16 / w, 4), round(uv[3] * 16 / h, 4)],
                                    'texture': '#0'} for side, uv in cube['uv'].items()}}
        if any(cube['rotation']):
            x, y, z = cube['rotation']
            element['rotation'] = {'origin': [round(cube['pivot'][i] + offset[i], 4) for i in range(3)], 'x': x, 'y': y, 'z': z}
        elements.append(element)
    hand = {'rotation': [0, 0, 0], 'translation': [0, 5.6, 1], 'scale': [0.8, 0.8, 0.8]}
    return {'textures': {'0': f'logos_dimension:item/{name}_rig', 'particle': f'logos_dimension:item/{name}'},
            'gui_light': 'front', 'display': {
                'thirdperson_righthand': hand, 'thirdperson_lefthand': hand,
                'firstperson_righthand': {'rotation': [0, -30, 15], 'translation': [1.2, 5.0, 1], 'scale': [0.63, 0.63, 0.63]},
                'firstperson_lefthand': {'rotation': [0, 30, -15], 'translation': [1.2, 5.0, 1], 'scale': [0.63, 0.63, 0.63]},
                'ground': {'rotation': [0, 0, 90], 'translation': [0, 1.5, 0], 'scale': [0.48, 0.48, 0.48]}},
            'elements': elements}


def main():
    art = Path(sys.argv[1])
    variants = json.load(open(art / 'variants.json', encoding='utf-8'))['variants']
    for v in variants:
        name = f"fox_uchigatana_{v['id']}"
        weapon = json.load(open(art / f'resources/assets/logos_dimension/combat/weapons/{name}.json', encoding='utf-8'))
        weapon = {'texture': f'logos_dimension:textures/item/{name}_rig.png', 'texture_size': weapon['texture_size'],
                  'scale': SCALE, 'cubes': weapon['cubes']}
        write(ASSETS / f'combat/weapons/{name}.json', weapon)
        shutil.copyfile(art / f'textures/{name}.png', ASSETS / f'textures/item/{name}_rig.png')
        texture = Image.open(art / f'textures/{name}.png').convert('RGBA')
        icon(texture, weapon['cubes']).save(ASSETS / f'textures/item/{name}.png')
        write(ASSETS / f'models/item/{name}_icon.json',
              {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'logos_dimension:item/{name}'}})
        write(ASSETS / f'models/item/{name}.json', item_model(name, weapon))
        # 物品栏与展示框用平面图标；手持、掉落和魔力缠绕用与刀网格一致的立体模型。
        write(ASSETS / f'items/{name}.json', {'model': {
            'type': 'minecraft:select', 'property': 'minecraft:display_context',
            'cases': [{'when': ['gui', 'fixed'], 'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{name}_icon'}}],
            'fallback': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{name}'}}})
        print('imported', name)


if __name__ == '__main__':
    main()
