# -*- coding: utf-8 -*-
"""从美术交付导入神狐薙刀（三种刃型 × 四套刀装，共 12 把）的物品资源。

用法（需要 pillow）：
    uv run --with pillow python tools/import_naginata.py "<Minecraft MOD 目录>/art/fox_naginata/v01"

- combat 网格：原样复制交付的 exports/*.mesh.json（武器 + 刃套），scale 1.25 乘在骨架的武器缩放上，第三人称为 1 倍。
- 贴图：四张共用图集放 textures/item/fox_naginata_<刀装>.png，每把一张 32×32 图标放 fox_naginata_<刃型>_<刀装>.png。
- 手持、掉落与魔力缠绕用的立体物品模型取网格的武器部分（不含刃套），像素坐标 = 网格 + (8, 1, 8)，与其他武器相同。
- 物品栏与展示框用平面图标：按交付的配色画一支斜放的薙刀，刃长、刃宽、弧度随刃型变化。
"""
import json
import shutil
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
OFFSET = (8, 1, 8)


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))


def rgb(text):
    return tuple(int(text[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (255,)


def mean_face(texture, cube, side='north'):
    u0, v0, u1, v1 = (int(round(x)) for x in cube['uv'][side])
    px = [texture.getpixel((u, v)) for v in range(min(v0, v1), max(v0, v1)) for u in range(min(u0, u1), max(u0, u1))]
    px = [p for p in px if p[3] > 0]
    return tuple(sum(p[i] for p in px) // len(px) for i in range(3)) + (255,)


def icon(texture, cubes, palette, head):
    """沿对角线从左下（石突）到右上（刀尖），刃背按刃型弯出弧度。"""
    by = {c['name']: c for c in cubes}
    ji = mean_face(texture, by[f'{head["id"]}_blade_02_ji'])
    ha = mean_face(texture, by[f'{head["id"]}_blade_02_ha'])
    mune = mean_face(texture, by[f'{head["id"]}_blade_02_mune'])
    iron = mean_face(texture, by['ishizuki_body_center'])
    guard = mean_face(texture, by['plain_tsuba_1'])
    brass = mean_face(texture, by['habaki'])
    shaft, light = rgb(palette['shaft']), rgb(palette['shaft_light'])
    grip, accent = rgb(palette['grip']), rgb(palette['accent'])
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))

    def put(x, y, c):
        if 0 <= x < 32 and 0 <= y < 32:
            img.putpixel((x, y), c)
    # 杆身：从 (1, 30) 走到 (18, 13)，两像素宽，亮面在上。
    for i in range(18):
        x, y = 1 + i, 30 - i
        if i < 2:
            c = iron
        elif i == 5:
            c = accent
        elif i in (8, 9, 13, 14):
            c = grip
        else:
            c = shaft
        put(x, y, light if c == shaft else c)
        put(x + 1, y, shade(c, .72))
    # 刀镡、刀鎺。
    x, y = 19, 12
    for dx, dy in ((-2, -1), (-1, -1), (-1, 0), (0, 0), (0, 1), (1, 1), (1, 2)):
        put(x + dx, y + dy, guard)
    put(x + 1, y - 1, brass); put(x, y - 1, brass); put(x + 1, y, shade(brass, .8))
    # 刃：从刀鎺往右上走，越靠近刀尖越向上弯（刃背在左上、刃口在右下）。
    length, width, sori = head['pixels'], head['width'], head['bend']
    for i in range(length):
        u = i / max(1, length - 1)
        lift = int(round(sori * u * u))
        bx, by_ = 21 + i - lift, 10 - i - lift // 2
        put(bx, by_, mune)
        for w in range(1, width + 1):
            if u > 0.8 and w > 1:
                continue
            put(bx + w, by_, ji if w < width else ha)
            put(bx + w, by_ + 1, ha if w == width else ji)
    return img


def item_model(name, finish, weapon):
    w, h = weapon['texture_size']
    elements = []
    for cube in weapon['cubes']:
        if cube['part'] != 'weapon':
            continue
        lo = [min(cube['start'][i], cube['end'][i]) + OFFSET[i] for i in range(3)]
        hi = [max(cube['start'][i], cube['end'][i]) + OFFSET[i] for i in range(3)]
        assert all(-16 <= v <= 32 for v in lo + hi), cube['name']
        element = {'name': cube['name'], 'from': [round(v, 4) for v in lo], 'to': [round(v, 4) for v in hi],
                   'faces': {side: {'uv': [round(uv[0] * 16 / w, 4), round(uv[1] * 16 / h, 4), round(uv[2] * 16 / w, 4), round(uv[3] * 16 / h, 4)],
                                    'texture': '#0'} for side, uv in cube['uv'].items()}}
        if any(cube['rotation']):
            x, y, z = cube['rotation']
            element['rotation'] = {'origin': [round(cube['pivot'][i] + OFFSET[i], 4) for i in range(3)], 'x': x, 'y': y, 'z': z}
        elements.append(element)
    # 原版持物：握点（网格原点）落在手心；手持尺寸与第三人称骨架里相近。
    hand = {'rotation': [0, 0, 0], 'translation': [0, 5.6, 1], 'scale': [0.8, 0.8, 0.8]}
    return {'textures': {'0': f'logos_dimension:item/fox_naginata_{finish}', 'particle': f'logos_dimension:item/{name}'},
            'gui_light': 'front', 'display': {
                'thirdperson_righthand': hand, 'thirdperson_lefthand': hand,
                'firstperson_righthand': {'rotation': [0, -30, 15], 'translation': [1.2, 5.0, 1], 'scale': [0.5, 0.5, 0.5]},
                'firstperson_lefthand': {'rotation': [0, 30, -15], 'translation': [1.2, 5.0, 1], 'scale': [0.5, 0.5, 0.5]},
                'ground': {'rotation': [0, 0, 90], 'translation': [-2, 1.5, 0], 'scale': [0.42, 0.42, 0.42]}},
            'elements': elements}


# 图标上的刃型：像素长度、刃宽、弧度。
HEADS = {'standard': dict(pixels=9, width=2, bend=2), 'slender': dict(pixels=8, width=1, bend=2), 'broad': dict(pixels=10, width=3, bend=3)}


def main():
    art = Path(sys.argv[1])
    data = json.load(open(art / 'variants.json', encoding='utf-8'))
    (ASSETS / 'textures/item').mkdir(parents=True, exist_ok=True)
    for finish in data['palettes']:
        shutil.copyfile(art / f'textures/fox_naginata_{finish}.png', ASSETS / f'textures/item/fox_naginata_{finish}.png')
    for v in data['variants']:
        name = f"fox_naginata_{v['id']}"
        finish = v['palette']
        delivered = json.load(open(art / f'exports/{name}.mesh.json', encoding='utf-8'))
        assert delivered['texture'] == f'logos_dimension:textures/item/fox_naginata_{finish}.png', name
        # fp_sheath：第一人称也画刃套，拔刀、收刀时拿在左手上。
        weapon = {'texture': delivered['texture'], 'texture_size': delivered['texture_size'], 'scale': delivered['scale'],
                  'fp_sheath': True, 'cubes': delivered['cubes']}
        write(ASSETS / f'combat/weapons/{name}.json', weapon)
        texture = Image.open(art / f'textures/fox_naginata_{finish}.png').convert('RGBA')
        head = dict(HEADS[v['head']], id=v['head'])
        icon(texture, weapon['cubes'], data['palettes'][finish], head).save(ASSETS / f'textures/item/{name}.png')
        write(ASSETS / f'models/item/{name}_icon.json',
              {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'logos_dimension:item/{name}'}})
        write(ASSETS / f'models/item/{name}.json', item_model(name, finish, weapon))
        write(ASSETS / f'items/{name}.json', {'model': {
            'type': 'minecraft:select', 'property': 'minecraft:display_context',
            'cases': [{'when': ['gui', 'fixed'], 'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{name}_icon'}}],
            'fallback': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{name}'}}})
        print('imported', name, len(weapon['cubes']))


if __name__ == '__main__':
    main()
