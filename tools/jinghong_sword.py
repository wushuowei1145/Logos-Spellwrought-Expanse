# -*- coding: utf-8 -*-
"""惊鸿剑：网格、贴图、物品模型、图标与预览，全部由本脚本生成。

用法：uv run --with numpy --with pillow python tools/jinghong_sword.py [预览目录]

照凌霄的规格做（原点在剑柄里，主手握点 0.65，剑尖 +Y，刃宽沿 X、剑面法线沿 Z，骨骼同名），
所以直接共用凌霄的动作。之后有一把武器要同时用五把这种剑，所以精度压低：
17 个方块（凌霄 58 个）、64×64 贴图（凌霄 128×128）、16 像素图标改 32 像素。
样式和凌霄分开：玄青柳叶剑身、中间一道青光脊线、燕翼剑格、单条朱红剑穗、黑漆剑鞘。
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
NAME = 'jinghong_sword'
TEX = 64

# ---------------------------------------------------------------- 配色

PALETTE = {  # 名字: (色块序号, 颜色)。色块 4×4，前两行，取中心点。
    'edge': (0, (214, 226, 238)),
    'edge_dark': (1, (150, 166, 186)),
    'body': (2, (58, 72, 94)),
    'core': (3, (150, 240, 255)),
    'guard': (4, (178, 188, 204)),
    'guard_dark': (5, (98, 110, 130)),
    'gem': (6, (112, 226, 250)),
    'grip': (7, (40, 44, 78)),
    'ribbon': (8, (184, 46, 60)),
    'ribbon_dark': (9, (128, 30, 44)),
    'lacquer': (10, (30, 32, 44)),
    'fitting': (11, (160, 170, 186)),
}


def swatch(name):
    i = PALETTE[name][0]
    x, y = (i % 16) * 4, (i // 16) * 4
    return [x + 1.5, y + 1.5, x + 1.5, y + 1.5]


# 贴图上的条带：(u0, v0, u1, v1)，像素。条带顶端对着 +Y（剑尖、剑鞘尾）。
TAPER = (0, 8, 5, 12)
BLADE = (0, 12, 5, 64)
SHEATH = (8, 8, 14, 64)
GRIP = (16, 8, 19, 20)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def paint_texture():
    img = np.zeros((TEX, TEX, 4), np.uint8)
    for name, (i, rgb) in PALETTE.items():
        x, y = (i % 16) * 4, (i // 16) * 4
        img[y:y + 4, x:x + 4] = (*rgb, 255)
    C = {k: v[1] for k, v in PALETTE.items()}

    def put(x, y, rgb):
        img[y, x] = (*rgb, 255)

    # 剑身：刃口亮银、剑面玄青带淡淡的锻纹、中间一道青光脊线。剑尖一段（TAPER）接在上面。
    top, bottom = TAPER[1], BLADE[3]
    for y in range(top, bottom):
        s = (y - top) / (bottom - top - 1)  # 0 剑尖 → 1 剑根
        # 锻纹：很淡的长波，只在受光的一侧（第 1 列）看得出。
        ripple = 0.5 + 0.5 * math.sin(y * 0.55)
        body = mix(C['body'], C['edge_dark'], 0.12 + 0.16 * ripple)
        put(0, y, C['edge'])
        put(4, y, C['edge_dark'])
        put(1, y, body)
        put(3, y, C['body'])
        # 脊线：剑尖一段最亮，到剑根前几像素淡回剑面。
        glow = 1.0 if s < 0.8 else max(0.0, 1 - (s - 0.8) / 0.2)
        put(2, y, mix(mix(C['body'], C['edge'], 0.3), C['core'], glow))
    # 剑根两道银箍（剑面上的刻线）。
    for y in (bottom - 5, bottom - 8):
        for x in (1, 3):
            put(x, y, C['edge_dark'])

    # 剑鞘：黑漆，两侧一像素暗边，中间银线，三处菱形银饰。
    x0, y0, x1, y1 = SHEATH
    dark = mix(C['lacquer'], (0, 0, 0), 0.4)
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(x, y, dark if x in (x0, x1 - 1) else C['lacquer'])
        sheen = 0.18 + 0.12 * math.sin(y * 0.45)
        put(x0 + 1, y, mix(C['lacquer'], C['fitting'], sheen * 0.6))
    cx = (x0 + x1 - 1) / 2
    for cy in (y0 + 10, y0 + 28, y0 + 46):
        for y in range(cy - 3, cy + 4):
            for x in range(x0 + 1, x1 - 1):
                d = abs(x - cx) + abs(y - cy)
                if d <= 1.5:
                    put(x, y, C['gem'])
                elif d <= 3:
                    put(x, y, C['fitting'])

    # 剑柄：深靛缠绳，每三行一道银线，中间一列压暗作绳缝。
    x0, y0, x1, y1 = GRIP
    for y in range(y0, y1):
        for x in range(x0, x1):
            if (y - y0) % 3 == 2:
                put(x, y, C['fitting'] if x != x0 + 1 else C['guard_dark'])
            else:
                put(x, y, mix(C['grip'], (0, 0, 0), 0.3) if x == x0 + 1 else C['grip'])
    return Image.fromarray(img, 'RGBA')


# 剑身上的五段激活刻度：剑脊（第 2 列）从剑根往剑尖分五段，每段 8 像素、隔 2 像素，第 0 段最靠剑根。
GAUGE = [(53, 61), (43, 51), (33, 41), (23, 31), (13, 21)]


def paint_glow(segments=None):
    """发光层（叠加混合，白色按透明度出光，颜色由渲染时给）。segments 为 None 时整把剑发光：剑脊、刃口和
    用 edge 色块的剑侧、剑尖、剑格宝石（身后的剑激活、万剑归宗的虚影剑）；为整数时只亮剑脊上的前几段刻度
    （手里的剑显示身后激活了几把），五段全亮时剑尖一段也亮起来。"""
    img = np.zeros((TEX, TEX, 4), np.uint8)

    def put(x, y, a):
        img[y, x] = (255, 255, 255, max(int(img[y, x, 3]), a))
    top, bottom = TAPER[1], BLADE[3]
    if segments is None:
        for y in range(top, bottom):
            put(2, y, 255); put(1, y, 150); put(3, y, 150); put(0, y, 110); put(4, y, 110)
        for name in ('edge', 'gem', 'edge_dark'):
            i = PALETTE[name][0]
            cx, cy = (i % 16) * 4 + 1, (i // 16) * 4 + 1
            put(cx, cy, 200 if name != 'edge_dark' else 120)
    else:
        for k in range(segments):
            y0, y1 = GAUGE[k]
            for y in range(y0, y1):
                put(2, y, 255); put(1, y, 160); put(3, y, 160)
        if segments >= len(GAUGE):
            for y in range(top, GAUGE[-1][0]):
                put(2, y, 255); put(1, y, 130); put(3, y, 130)
    return Image.fromarray(img, 'RGBA')


# ---------------------------------------------------------------- 网格

def uv_for(face_uv):
    u0, v0, u1, v1 = face_uv
    return [u0 + .02, v0 + .02, u1 - .02, v1 - .02]


def cube(name, start, end, color=None, front=None, part='weapon', bone='Weapon', rot=0.0, pivot=None):
    """front：南北两面用的条带；其余面和没给 front 的面用色块 color。"""
    start = [round(v, 4) for v in start]
    end = [round(v, 4) for v in end]
    if pivot is None:
        pivot = [round((a + b) / 2, 4) for a, b in zip(start, end)]
    side = swatch(color)
    uv = {f: side for f in ('north', 'south', 'east', 'west', 'up', 'down')}
    if front:
        uv['north'] = uv['south'] = uv_for(front)
    return dict(name=name, part=part, bone=bone, start=start, end=end, pivot=[round(v, 4) for v in pivot],
                rotation=[0, 0, rot], uv=uv)


def wing(name, root, angle, length, thick, depth, color):
    """从 root 向 -X 伸出的一片（右侧镜像传负长度），绕 Z 转 angle 度。"""
    x, y = root
    if length > 0:
        start, end = [x - length, y - thick / 2, -depth], [x, y + thick / 2, depth]
    else:
        start, end = [x, y - thick / 2, -depth], [x - length, y + thick / 2, depth]
    return cube(name, start, end, color, rot=angle, pivot=[x, y, 0])


def tip_of(root, angle, length):
    """wing 的外端。"""
    a = math.radians(angle)
    dx, dy = -length, 0.0
    return root[0] + dx * math.cos(a) - dy * math.sin(a), root[1] + dx * math.sin(a) + dy * math.cos(a)


BLADE_HALF, BLADE_TOP, TAPER_TOP = 0.6, 20.3, 21.4
TIP_SIDE = 0.594  # 对角线 0.84，等于剑尖一段的宽度
TIP_Y = round(TAPER_TOP + TIP_SIDE / math.sqrt(2), 4)


def cubes():
    out = [
        cube('willow_blade', [-BLADE_HALF, 2.7, -0.15], [BLADE_HALF, BLADE_TOP, 0.15], 'edge', BLADE),
        cube('blade_taper', [-0.42, BLADE_TOP, -0.13], [0.42, TAPER_TOP, 0.13], 'edge', TAPER),
        cube('blade_tip', [-TIP_SIDE / 2, TAPER_TOP - TIP_SIDE / 2, -0.12], [TIP_SIDE / 2, TAPER_TOP + TIP_SIDE / 2, 0.12],
             'edge', rot=45),
        cube('guard_core', [-0.8, 1.95, -0.34], [0.8, 2.7, 0.34], 'guard'),
        cube('guard_gem', [-0.2, 2.12, -0.4], [0.2, 2.52, 0.4], 'gem', bone='ysmGlowJade', rot=45),
    ]
    # 燕翼剑格：两片向剑身方向斜起，外端再翘一片。
    for side, sign in (('left', 1), ('right', -1)):
        root = (-0.72 * sign, 2.3)
        a1 = -24 * sign
        out.append(wing(f'guard_wing_{side}', root, a1, 1.15 * sign, 0.34, 0.24, 'guard'))
        root2 = tip_of(root, a1, 1.15 * sign)
        out.append(wing(f'guard_feather_{side}', root2, -52 * sign, 0.62 * sign, 0.24, 0.2, 'guard_dark'))
    out += [
        cube('wrapped_grip', [-0.34, -1.9, -0.3], [0.34, 1.95, 0.3], 'grip', GRIP),
        cube('pommel', [-0.31, -2.56, -0.32], [0.31, -1.94, 0.32], 'guard', rot=45),
        cube('ribbon_upper', [-0.15, -3.75, -0.05], [0.15, -2.62, 0.05], 'ribbon', bone='TasselLeft0', rot=-4,
             pivot=[0, -2.7, 0]),
        cube('ribbon_tail', [-0.2, -4.9, -0.05], [0.2, -3.7, 0.05], 'ribbon_dark', bone='TasselLeft1', rot=-8,
             pivot=[0, -3.7, 0]),
        cube('sheath_body', [-0.78, 2.7, -0.32], [0.78, 21.9, 0.32], 'lacquer', SHEATH, part='sheath', bone='Sheath'),
        cube('sheath_mouth', [-0.9, 2.35, -0.38], [0.9, 3.0, 0.38], 'fitting', part='sheath', bone='Sheath'),
        cube('sheath_band', [-0.84, 11.6, -0.36], [0.84, 11.95, 0.36], 'fitting', part='sheath', bone='Sheath'),
        cube('sheath_chape', [-0.86, 21.4, -0.36], [0.86, 22.15, 0.36], 'fitting', part='sheath', bone='Sheath'),
    ]
    return out


def bones():
    """与凌霄同名同层级，动作直接共用；剑穗只用左边两节。"""
    out = [
        dict(name='ysmGlowJade', parent='Weapon', pivot=[0, 2.32, 0]),
        dict(name='TipLocator', parent='Weapon', pivot=[0, TIP_Y, 0]),
        dict(name='RideFrontLocator', parent='Weapon', pivot=[0.25, 8.333333, 0.18]),
        dict(name='RideRearLocator', parent='Weapon', pivot=[-0.25, 5.083333, 0.18]),
    ]
    for side in ('Left', 'Right'):
        parent = 'Weapon'
        for i, y in enumerate((-2.7, -3.7, -4.8)):
            out.append(dict(name=f'Tassel{side}{i}', parent=parent, pivot=[0, y, 0]))
            parent = f'Tassel{side}{i}'
    return out


def mesh():
    return {
        'texture': f'logos_dimension:textures/item/{NAME}_rig.png',
        'texture_size': [TEX, TEX],
        'scale': 1.0,
        'bones': bones(),
        'cubes': cubes(),
        'grip': [0, 0.65, 0],
        'tip': [0, TIP_Y, 0],
    }


# ---------------------------------------------------------------- 物品模型

OFFSET = (8, 1, 8)


def item_model(m):
    k = 16 / TEX
    elements = []
    for c in m['cubes']:
        if c['part'] != 'weapon':
            continue
        e = {'name': c['name'],
             'from': [round(c['start'][i] + OFFSET[i], 4) for i in range(3)],
             'to': [round(c['end'][i] + OFFSET[i], 4) for i in range(3)],
             'faces': {f: {'uv': [round(v * k, 4) for v in uv], 'texture': '#0'} for f, uv in c['uv'].items()}}
        if any(c['rotation']):
            e['rotation'] = {'origin': [round(c['pivot'][i] + OFFSET[i], 4) for i in range(3)],
                             'x': c['rotation'][0], 'y': c['rotation'][1], 'z': c['rotation'][2]}
        elements.append(e)
    lingxiao = json.loads((ASSETS / 'models/item/lingxiao_sword.json').read_text(encoding='utf-8'))
    return {
        'textures': {'0': f'logos_dimension:item/{NAME}_rig', 'particle': f'logos_dimension:item/{NAME}'},
        'gui_light': 'front',
        'display': lingxiao['display'],  # 同尺寸，手持与掉落的摆放照凌霄
        'elements': elements,
        'groups': [{'name': 'wand_tip', 'origin': [8, round(TIP_Y + 1, 4), 8], 'children': []}],
    }


# ---------------------------------------------------------------- 图标

def front_sample(m, tex, x, y):
    """正面（-Z 一侧）看去 (x, y) 处的颜色；只看剑本身。"""
    best, rgba = None, None
    for c in m['cubes']:
        if c['part'] != 'weapon':
            continue
        px, py = c['pivot'][0], c['pivot'][1]
        a = math.radians(-c['rotation'][2])
        lx = px + (x - px) * math.cos(a) - (y - py) * math.sin(a)
        ly = py + (x - px) * math.sin(a) + (y - py) * math.cos(a)
        (x0, y0, z0), (x1, y1, _) = c['start'], c['end']
        if not (x0 <= lx < x1 and y0 <= ly < y1):
            continue
        if best is not None and z0 >= best:
            continue
        u0, v0, u1, v1 = c['uv']['north']
        u = u0 + (lx - x0) / (x1 - x0) * (u1 - u0)
        v = v0 + (y1 - ly) / (y1 - y0) * (v1 - v0)
        best, rgba = z0, tex[min(int(v), TEX - 1), min(int(u), TEX - 1)]
    return rgba


def icon(m, tex, size=32):
    """剑沿对角线从左下（剑柄）指向右上（剑尖），刃宽放大一点，免得 32 像素里只剩一条线。"""
    lo, hi = -5.0, TIP_Y + 0.1
    scale = (hi - lo) / ((size - 3) * math.sqrt(2))
    widen = 1.35
    img = np.zeros((size, size, 4), np.uint8)
    for py in range(size):
        for px in range(size):
            # 屏幕点 → 剑的坐标：沿对角线是 y，垂直对角线是 x。
            sx, sy = px + 0.5 - 1.5, size - (py + 0.5) - 1.5
            along = (sx + sy) / math.sqrt(2) * scale + lo
            across = (sx - sy) / math.sqrt(2) * scale / widen
            c = front_sample(m, tex, across, along)
            if c is not None:
                img[py, px] = c
    # 一像素深色描边。
    solid = img[..., 3] > 0
    outline = np.zeros_like(solid)
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        outline |= np.roll(np.roll(solid, dy, 0), dx, 1)
    outline &= ~solid
    img[outline] = (22, 26, 40, 255)
    return Image.fromarray(img, 'RGBA')


# ---------------------------------------------------------------- 预览

def preview(m, tex_img, out_dir):
    sys.path.insert(0, str(ROOT / 'tools'))
    from combat_author import affine, euler_matrix
    from combat_render import Camera, render, sheet, weapon_quads

    tex = np.asarray(tex_img).astype(np.float32)
    lingxiao = json.loads((ASSETS / 'combat/weapons/lingxiao_sword.json').read_text(encoding='utf-8'))
    ltex = np.asarray(Image.open(ASSETS / 'textures/item/lingxiao_sword_rig.png').convert('RGBA')).astype(np.float32)
    I = np.eye(4)
    size = (240, 420)
    views = [('正面', Camera(0, 0, 14, (0, 8.5, 0), size)), ('三分之四', Camera(-35, 15, 14, (0, 8.5, 0), size)),
             ('侧面', Camera(90, 0, 14, (0, 8.5, 0), size)), ('背面', Camera(180, 0, 14, (0, 8.5, 0), size))]
    rows = []
    for label, weapon, t in (('惊鸿', m, tex), ('凌霄', lingxiao, ltex)):
        panels = [(cap, render(weapon_quads(weapon, t, I, 'weapon'), cam, ground=False)) for cap, cam in views]
        panels.append(('剑鞘', render(weapon_quads(weapon, t, I, 'sheath'), views[0][1], ground=False)))
        rows.append((label, panels))
    sheet(rows, '惊鸿剑 对照凌霄（同比例）', out_dir / 'compare.png')

    # 局部特写：剑格、剑柄、剑尖。
    close = [('剑格', Camera(-25, 12, 46, (0, 1.6, 0), (300, 300))), ('剑尖', Camera(-25, 12, 46, (0, 20.2, 0), (300, 300))),
             ('剑格背面', Camera(155, 12, 46, (0, 1.6, 0), (300, 300)))]
    rows = [(label, [(cap, render(weapon_quads(w, t, I, 'weapon'), cam, ground=False)) for cap, cam in close])
            for label, w, t in (('惊鸿', m, tex), ('凌霄', lingxiao, ltex))]
    sheet(rows, '细部', out_dir / 'detail.png')

    # 五把成扇，看一起用时的样子。
    quads = []
    for i in range(5):
        # 绕剑柄下方 10 格的点排成扇形，剑柄彼此错开。
        R = euler_matrix((0, 0, (i - 2) * 18))
        M = affine(R, R @ np.array([0.0, 10, 0]) - np.array([0.0, 10, 0]) + np.array([0, 0, i * 0.01]))
        quads += weapon_quads(m, tex, M, 'weapon')
    fan = render(quads, Camera(0, 0, 9, (0, 10, 0), (520, 360)), ground=False)
    dark = render(quads, Camera(0, 0, 9, (0, 10, 0), (520, 360)), bg=(26, 28, 38), ground=False)
    sheet([('五把', [('浅底', fan), ('深底', dark)])], '五把成扇', out_dir / 'five.png')

    ic = icon(m, tex)
    big = Image.new('RGBA', (32 * 12 * 2 + 24, 32 * 12), (60, 60, 70, 255))
    big.alpha_composite(ic.resize((384, 384), Image.NEAREST), (0, 0))
    li = Image.open(ASSETS / 'textures/item/lingxiao_sword.png').convert('RGBA').resize((384, 384), Image.NEAREST)
    big.alpha_composite(li, (384 + 24, 0))
    big.save(out_dir / 'icon_vs_lingxiao.png')
    tex_img.resize((TEX * 8, TEX * 8), Image.NEAREST).save(out_dir / 'texture_x8.png')


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def main():
    m = mesh()
    tex_img = paint_texture()
    tex = np.asarray(tex_img)
    write_json(ASSETS / f'combat/weapons/{NAME}.json', m)
    tex_img.save(ASSETS / f'textures/item/{NAME}_rig.png')
    paint_glow().save(ASSETS / f'textures/item/{NAME}_glow.png')
    for n in range(1, len(GAUGE) + 1):
        paint_glow(n).save(ASSETS / f'textures/item/{NAME}_gauge_{n}.png')
    icon(m, tex).save(ASSETS / f'textures/item/{NAME}.png')
    write_json(ASSETS / f'models/item/{NAME}.json', item_model(m))
    write_json(ASSETS / f'models/item/{NAME}_icon.json',
               {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'logos_dimension:item/{NAME}'}})
    write_json(ASSETS / f'items/{NAME}.json', {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:display_context',
        'cases': [{'when': ['gui', 'fixed'], 'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{NAME}_icon'}}],
        'fallback': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{NAME}'}}})
    print(f'{len(m["cubes"])} 个方块（剑 {sum(c["part"] == "weapon" for c in m["cubes"])}），剑尖 {TIP_Y}')
    if len(sys.argv) > 1:
        out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
        preview(m, tex_img, out)
        print('预览：', out)


if __name__ == '__main__':
    main()
