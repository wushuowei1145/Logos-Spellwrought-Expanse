# -*- coding: utf-8 -*-
"""从美术交付导入神狐和弓的物品资源，并生成和弓用的箭（矢）。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/import_yumi.py "<Minecraft MOD 目录>/art/fox_yumi/v01"

- combat 网格：贴图改成 textures/item/fox_yumi_<配色>_rig.png，加 scale（乘在骨架的武器缩放上，第三人称 1 倍，
  弓与玩家等高），加 bones（交付的分节弓身、弦与搭箭点骨骼，挂在 Weapon 下；另加放箭的 BowArrow），
  加 attachments（箭网格挂在 BowArrow 上）。
- 箭：combat/weapons/yumi_arrow.json 与 textures/entity/projectiles/yumi_arrow.png。原点在箭筈（搭在弦上的一端），
  箭头朝 -Z，与弓的射向相同。搭在弓上、飞行中的箭都用它。
- 物品图标：按各配色贴图上弓背、握把的颜色生成 32×32 像素画，斜放、上弭朝右上；物品栏与展示框用它。
- 手持、掉落用的立体物品模型取弓网格（静止、不拉弦），坐标为网格坐标 + (8, 8, 8)，握点在模型中心。
"""
import json
import math
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
# 骨架的武器缩放乘上它：第三人称 0.8 × 1.625 = 1.3，弓高 41.6 像素，约玩家身高的 1.3 倍（真实的和弓约 2.2 米）。
SCALE = 1.625
# 弦上的搭箭点（交付的 attachment_points.json 的 nock_rest），箭筈在这里。
NOCK = [0.0, 0.85, 3.08965625]

# ---------------------------------------------------------------- 箭


ARROW_LENGTH = 21.0
COLORS = {
    'shaft': ['#CDB27A', '#C4A86F', '#D6BD88', '#BFA266', '#CDB27A', '#C9AD74', '#B89C62', '#D2B884'],
    'node': '#8E7347',
    'nock': '#4A463C',
    'nock_light': '#625C4E',
    'thread': '#5B2A24',
    'point': '#6E6A66',
    'point_light': '#A39E96',
    'feather': '#E9E3D3',
    'feather_band': '#3E3833',
    'feather_edge': '#C9C0AD',
}


def hex_rgba(c):
    c = c.lstrip('#')
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def arrow_texture():
    """16×16：每种材质一块，长面沿 u 拉伸。"""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    put = img.putpixel
    # 箆（竹杆）：8×1，深浅交替的竹纹；节：1×1。
    for u, c in enumerate(COLORS['shaft']):
        put((u, 0), hex_rgba(c))
    put((8, 0), hex_rgba(COLORS['node']))
    # 筈、矧糸（缠线）、鏃（箭头）各 2×1。
    put((0, 2), hex_rgba(COLORS['nock'])); put((1, 2), hex_rgba(COLORS['nock_light']))
    put((2, 2), hex_rgba(COLORS['thread'])); put((3, 2), hex_rgba(COLORS['thread']))
    put((4, 2), hex_rgba(COLORS['point'])); put((5, 2), hex_rgba(COLORS['point_light']))
    # 羽：8×2，白底深色横斑（鹰羽），上缘略暗。
    for u in range(8):
        band = u in (1, 2, 5, 6)
        put((u, 4), hex_rgba(COLORS['feather_edge'] if not band else COLORS['feather_band']))
        put((u, 5), hex_rgba(COLORS['feather_band'] if band else COLORS['feather']))
    return img


UV = {
    'shaft_long': [0, 0, 8, 1], 'shaft_end': [0, 0, 1, 1], 'node': [8, 0, 9, 1],
    'nock': [0, 2, 2, 3], 'thread': [2, 2, 4, 3], 'point': [4, 2, 6, 3],
    'feather': [0, 4, 8, 6], 'feather_end': [0, 5, 1, 6],
}


def arrow_cube(name, lo, hi, long_uv, end_uv=None, pivot=(0, 0, 0), rotation=(0, 0, 0)):
    """沿 Z 的长方块：四个长面用 long_uv（u 沿 Z），两端用 end_uv。"""
    end_uv = end_uv or long_uv
    return {'name': name, 'part': 'arrow', 'bone': 'BowArrow', 'start': [round(v, 4) for v in lo], 'end': [round(v, 4) for v in hi],
            'pivot': list(pivot), 'rotation': list(rotation),
            'uv': {'north': UV[end_uv], 'south': UV[end_uv], 'east': UV[long_uv], 'west': UV[long_uv],
                   'up': UV[long_uv], 'down': UV[long_uv]}}


def arrow_mesh():
    """和弓的箭：竹杆、筈、三片鹰羽、缠线与箭头。原点在筈底，箭头朝 -Z，单位与弓网格相同。"""
    L = ARROW_LENGTH
    cubes = [
        arrow_cube('nock', (-0.17, -0.17, -0.8), (0.17, 0.17, 0.0), 'nock'),
        arrow_cube('shaft', (-0.11, -0.11, -(L - 0.8)), (0.11, 0.11, -0.8), 'shaft_long', 'shaft_end'),
        arrow_cube('thread_back', (-0.14, -0.14, -1.25), (0.14, 0.14, -1.0), 'thread'),
        arrow_cube('thread_front', (-0.14, -0.14, -5.35), (0.14, 0.14, -5.1), 'thread'),
        arrow_cube('point_base', (-0.16, -0.16, -(L - 0.4)), (0.16, 0.16, -(L - 0.8)), 'point'),
        arrow_cube('point_tip', (-0.09, -0.09, -L), (0.09, 0.09, -(L - 0.4)), 'point'),
    ]
    # 三片羽绕箭杆 120° 一片，后段高、前段收窄。
    for i, angle in enumerate((0, 120, -120)):
        for tag, z0, z1, h in (('back', -1.25, -4.3, 0.55), ('front', -4.3, -5.1, 0.32)):
            cube = arrow_cube(f'feather_{i}_{tag}', (-0.02, 0.11, z1), (0.02, 0.11 + h, z0), 'feather', 'feather_end',
                              pivot=(0, 0, (z0 + z1) / 2), rotation=(0, 0, angle))
            cubes.append(cube)
    return {'texture': 'logos_dimension:textures/entity/projectiles/yumi_arrow.png', 'texture_size': [16, 16],
            'length': L, 'cubes': cubes}

# ---------------------------------------------------------------- 弓


def face(texture, cube, side='east'):
    u0, v0, u1, v1 = (int(round(x)) for x in cube['uv'][side])
    return [[texture.getpixel((u, v)) for u in range(min(u0, u1), max(u0, u1))] for v in range(min(v0, v1), max(v0, v1))]


def mean(rows):
    px = [p for row in rows for p in row if p[3] > 0]
    return tuple(sum(p[i] for p in px) // len(px) for i in range(3)) + (255,)


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (255,)


def bow_bones(geo):
    """交付几何里 Weapon 下的骨骼（Bedrock 空间换回 Blockbench 空间：x 取反），再加放箭的 BowArrow。"""
    bones = {b['name']: b for b in geo['minecraft:geometry'][0]['bones']}
    out = []

    def under_weapon(name):
        while name:
            if name == 'Weapon':
                return True
            name = bones[name].get('parent')
        return False
    for b in geo['minecraft:geometry'][0]['bones']:
        if b['name'] != 'Weapon' and under_weapon(b['name']):
            p = b['pivot']
            out.append({'name': b['name'], 'parent': b['parent'], 'pivot': [round(-p[0] + 0.0, 5), round(p[1], 5), round(p[2], 5)]})
    out.append({'name': 'BowArrow', 'parent': 'Weapon', 'pivot': NOCK})
    return out


def icon(texture, cubes):
    """32×32：弓斜放，上弭在右上、下弭在左下，弓背弯向左上，弦是直线。"""
    by = {c['name']: c for c in cubes}
    back = mean(face(texture, by['upper_03_back'], 'north'))
    belly = mean(face(texture, by['upper_03_belly'], 'north'))
    grip = mean(face(texture, by['grip_wrapped_leather'], 'north'))
    rattan = mean(face(texture, next(c for c in cubes if 'rattan' in c['name'] or 'binding' in c['name']), 'north'))
    horn = (74, 70, 60, 255)
    string = (192, 173, 131, 255)
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))

    def put(x, y, c):
        if 0 <= x < 32 and 0 <= y < 32:
            img.putpixel((x, y), c)
    # 弓的侧面轮廓（交付 build_asset.py 的 UPPER / LOWER），y 向上为弓身、z 为弦侧。
    upper = [(0, 0), (2.4, -.28), (5.1, -.62), (7.8, -.70), (10.5, -.40), (13.2, .18), (15.7, 1.02), (18.0, 2.18),
             (19.8, 2.73), (20.7, 2.98), (21.5, 3.18)]
    lower = [(0, 0), (-2.3, .06), (-4.5, .44), (-6.4, 1.22), (-8.0, 2.20), (-9.35, 2.70), (-10.05, 2.91), (-10.5, 3.04)]
    # 弓身长 32，图上对角线长约 38 像素；弓背（-z）朝左上，弦（+z）在右下。
    k = 1.15

    def to_px(y, z):
        a = (y + 10.5) * k  # 沿对角线
        b = z * k * 1.6     # 垂直对角线，放大弓的弯度
        return 3 + a / math.sqrt(2) + b / math.sqrt(2), 29 - a / math.sqrt(2) + b / math.sqrt(2)
    outline = lower[::-1] + upper[1:]
    curve = []
    for (y0, z0), (y1, z1) in zip(outline, outline[1:]):
        for s in np.linspace(0, 1, 24, endpoint=False):
            curve.append((y0 + (y1 - y0) * s, z0 + (z1 - z0) * s))
    curve.append(outline[-1])
    # 弦：两弭之间的直线。
    a, b = to_px(-10.5, 3.04), to_px(21.5, 3.18)
    for s in np.linspace(0, 1, 60):
        put(int(round(a[0] + (b[0] - a[0]) * s)), int(round(a[1] + (b[1] - a[1]) * s)), string)
    for y, z in curve:
        x, yy = to_px(y, z - 0.35)
        x, yy = int(round(x)), int(round(yy))
        if -1.3 <= y <= 1.3:
            c = grip
        elif abs(y - 1.6) < 0.4 or abs(y + 1.6) < 0.4 or abs(y - 10.5) < 0.5:
            c = rattan
        elif y > 21.0 or y < -10.1:
            c = horn
        else:
            c = back
        put(x, yy, c)
        put(x + 1, yy, shade(belly if c == back else c, 0.85))
    return img


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))


def item_model(name, weapon):
    """静止的弓（不拉弦）：像素坐标 = 网格坐标 + (8, 8, 8)，握点在模型中心。弓身在 YZ 面里，弦朝 +Z。"""
    w, h = weapon['texture_size']
    offset = (8, 8, 8)
    elements = []
    for cube in weapon['cubes']:
        lo = [min(cube['start'][i], cube['end'][i]) + offset[i] for i in range(3)]
        hi = [max(cube['start'][i], cube['end'][i]) + offset[i] for i in range(3)]
        element = {'name': cube['name'], 'from': [round(v, 4) for v in lo], 'to': [round(v, 4) for v in hi],
                   'faces': {side: {'uv': [round(uv[0] * 16 / w, 4), round(uv[1] * 16 / h, 4), round(uv[2] * 16 / w, 4), round(uv[3] * 16 / h, 4)],
                                    'texture': '#0'} for side, uv in cube['uv'].items()}}
        if any(cube['rotation']):
            x, y, z = cube['rotation']
            element['rotation'] = {'origin': [round(cube['pivot'][i] + offset[i], 4) for i in range(3)], 'x': x, 'y': y, 'z': z}
        elements.append(element)
    # 原版持物把模型的 +Y 对着手臂前方：手臂平举（骷髅拉弓）时弓竖直、弦朝自己。和弓比原版弓大一倍。
    hand = {'rotation': [0, 90, 0], 'translation': [0, 2.5, 1.5], 'scale': [0.75, 0.75, 0.75]}
    return {'textures': {'0': f'logos_dimension:item/{name}_rig', 'particle': f'logos_dimension:item/{name}'},
            'gui_light': 'front', 'display': {
                'thirdperson_righthand': hand, 'thirdperson_lefthand': hand,
                'firstperson_righthand': {'rotation': [0, 90, 20], 'translation': [1.2, 3.2, 1], 'scale': [0.5, 0.5, 0.5]},
                'firstperson_lefthand': {'rotation': [0, 90, 20], 'translation': [1.2, 3.2, 1], 'scale': [0.5, 0.5, 0.5]},
                'ground': {'rotation': [0, 0, 90], 'translation': [0, 1.5, 0], 'scale': [0.36, 0.36, 0.36]}},
            'elements': elements}


def main():
    art = Path(sys.argv[1])
    variants = json.load(open(art / 'variants.json', encoding='utf-8'))['variants']
    arrow = arrow_mesh()
    write(ASSETS / 'combat/weapons/yumi_arrow.json', arrow)
    (ASSETS / 'textures/entity/projectiles').mkdir(parents=True, exist_ok=True)
    arrow_texture().save(ASSETS / 'textures/entity/projectiles/yumi_arrow.png')
    for v in variants:
        name = f"fox_yumi_{v['id']}"
        mesh = json.load(open(art / f'exports/{name}.mesh.json', encoding='utf-8'))
        geo = json.load(open(art / f'models/{name}.geo.json', encoding='utf-8'))
        weapon = {'texture': f'logos_dimension:textures/item/{name}_rig.png', 'texture_size': mesh['texture_size'],
                  'scale': SCALE, 'bones': bow_bones(geo),
                  'attachments': [{'mesh': 'yumi_arrow', 'bone': 'BowArrow', 'origin': NOCK}],
                  'cubes': mesh['cubes']}
        write(ASSETS / f'combat/weapons/{name}.json', weapon)
        shutil.copyfile(art / f'textures/{name}.png', ASSETS / f'textures/item/{name}_rig.png')
        texture = Image.open(art / f'textures/{name}.png').convert('RGBA')
        icon(texture, mesh['cubes']).save(ASSETS / f'textures/item/{name}.png')
        write(ASSETS / f'models/item/{name}_icon.json',
              {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'logos_dimension:item/{name}'}})
        write(ASSETS / f'models/item/{name}.json', item_model(name, weapon))
        write(ASSETS / f'items/{name}.json', {'model': {
            'type': 'minecraft:select', 'property': 'minecraft:display_context',
            'cases': [{'when': ['gui', 'fixed'], 'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{name}_icon'}}],
            'fallback': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{name}'}}})
        print('imported', name)


if __name__ == '__main__':
    main()
