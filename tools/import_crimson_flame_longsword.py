# -*- coding: utf-8 -*-
"""导入炽焰长剑（art/crimson_flame_longsword/v01/crimson_flame_longsword.bbmodel）。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/import_crimson_flame_longsword.py [美术目录]

产出：
- assets/logos_dimension/combat/weapons/crimson_flame_longsword.json
- assets/logos_dimension/textures/item/crimson_flame_longsword_rig.png
- assets/logos_dimension/textures/item/crimson_flame_longsword.png（32×32 物品图标）
- assets/logos_dimension/models/item/crimson_flame_longsword.json（坐标 = 网格 + (8, 1, 8)）
- assets/logos_dimension/models/item/crimson_flame_longsword_icon.json
- assets/logos_dimension/items/crimson_flame_longsword.json
"""
import base64
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
DEFAULT_ART = ROOT.parents[1] / 'art/crimson_flame_longsword/v01'
NAME = 'crimson_flame_longsword'
OFFSET = (8.0, 1.0, 8.0)

sys.path.insert(0, str(ROOT / 'tools'))
from combat_author import affine  # noqa: E402
from combat_render import Camera, render, weapon_quads  # noqa: E402


def write_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def build_combat_mesh(bb: dict):
    W = int(bb['resolution']['width'])
    H = int(bb['resolution']['height'])
    cubes = []
    for el in bb['elements']:
        lo = [round(min(el['from'][i], el['to'][i]), 5) for i in range(3)]
        hi = [round(max(el['from'][i], el['to'][i]), 5) for i in range(3)]
        piv = [round(float(v), 5) for v in el['origin']]
        rot = [round(float(v), 4) + 0.0 for v in (el.get('rotation') or [0.0, 0.0, 0.0])]
        # Blockbench（及原版物品模型）六个面的 U 方向与 LongswordRig / combat_render 相反，对调 u0/u1。
        uv = {}
        for f, fd in el['faces'].items():
            u0, v0, u1, v1 = fd['uv']
            uv[f] = [u1, v0, u0, v1]
        cubes.append({
            'name': el['name'],
            'part': 'weapon',
            'start': lo,
            'end': hi,
            'pivot': piv,
            'rotation': rot,
            'uv': uv,
        })
    return {
        'texture': f'logos_dimension:textures/item/{NAME}_rig.png',
        'texture_size': [W, H],
        'scale': 1.0,
        'cubes': cubes,
    }


def build_item_model(bb: dict):
    W = int(bb['resolution']['width'])
    H = int(bb['resolution']['height'])
    elements = []
    for el in bb['elements']:
        lo = [round(min(el['from'][i], el['to'][i]) + OFFSET[i], 5) for i in range(3)]
        hi = [round(max(el['from'][i], el['to'][i]) + OFFSET[i], 5) for i in range(3)]
        assert all(-16 <= v <= 32 for v in lo + hi), f'Out of bounds: {el["name"]} {lo} {hi}'
        faces = {}
        for f, fd in el['faces'].items():
            u0, v0, u1, v1 = fd['uv']
            faces[f] = {
                'uv': [round(u0 * 16.0 / W, 5), round(v0 * 16.0 / H, 5),
                       round(u1 * 16.0 / W, 5), round(v1 * 16.0 / H, 5)],
                'texture': '#0',
            }
        item = {
            'name': el['name'],
            'from': lo,
            'to': hi,
            'faces': faces,
        }
        rot = [round(float(v), 4) + 0.0 for v in (el.get('rotation') or [0.0, 0.0, 0.0])]
        if any(abs(v) > 1e-6 for v in rot):
            item['rotation'] = {
                'origin': [round(el['origin'][i] + OFFSET[i], 5) for i in range(3)],
                'x': rot[0],
                'y': rot[1],
                'z': rot[2],
            }
        elements.append(item)

    display = {
        'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 5.0, 1], 'scale': [0.72, 0.72, 0.72]},
        'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 5.0, 1], 'scale': [0.72, 0.72, 0.72]},
        'firstperson_righthand': {'rotation': [0, -30, 15], 'translation': [1.2, 4.4, 1], 'scale': [0.56, 0.56, 0.56]},
        'firstperson_lefthand': {'rotation': [0, 30, -15], 'translation': [1.2, 4.4, 1], 'scale': [0.56, 0.56, 0.56]},
        'gui': {'rotation': [8, -25, -35], 'translation': [0, -1, 0], 'scale': [0.46, 0.46, 0.46]},
        'ground': {'rotation': [0, 0, 90], 'translation': [0, 1.5, 0], 'scale': [0.42, 0.42, 0.42]},
        'fixed': {'rotation': [0, 0, 0], 'translation': [0, -1, 0], 'scale': [0.44, 0.44, 0.44]},
    }
    return {
        'credit': 'Crimson Flame Longsword (Logos Dimension)',
        'textures': {
            '0': f'logos_dimension:item/{NAME}_rig',
            'particle': f'logos_dimension:item/{NAME}',
        },
        'gui_light': 'front',
        'ambientocclusion': False,
        'display': display,
        'elements': elements,
        'groups': [{'name': 'wand_tip', 'origin': [8.0, 30.38, 8.0], 'children': []}],
    }


def render_icon(weapon: dict, tex_img: Image.Image, size: int = 32) -> Image.Image:
    tex = np.asarray(tex_img.convert('RGBA')).astype(np.float32)
    # Vanilla Minecraft swords point from bottom-left (pommel) to top-right (tip).
    # In combat_render Camera(0, 0): right = -X, up = +Y, so +44° tilts +Y toward top-right.
    ang = math.radians(44.0)
    R = np.array([
        [math.cos(ang), -math.sin(ang), 0.0],
        [math.sin(ang), math.cos(ang), 0.0],
        [0.0, 0.0, 1.0],
    ], float)
    quads = weapon_quads(weapon, tex, affine(R, np.zeros(3)), 'weapon')
    cam = Camera(0.0, 0.0, (size - 6) / 26.5, R @ np.array([0.0, 12.35, 0.0]), (size, size))
    im = np.asarray(render(quads, cam, bg=(255, 0, 255), ground=False)).copy()
    solid = ~((im[..., 0] == 255) & (im[..., 1] == 0) & (im[..., 2] == 255))
    out = np.zeros((size, size, 4), np.uint8)
    out[solid, :3] = im[solid]
    out[solid, 3] = 255
    outline = np.zeros_like(solid)
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        outline |= np.roll(np.roll(solid, dy, 0), dx, 1)
    outline &= ~solid
    out[outline] = (24, 22, 30, 255)
    return Image.fromarray(out, 'RGBA')


def main():
    art_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_ART
    bb_path = art_dir / f'{NAME}.bbmodel'
    bb = json.loads(bb_path.read_text(encoding='utf-8'))

    png_bytes = base64.b64decode(bb['textures'][0]['source'].split(',', 1)[1])
    rig_tex_path = ASSETS / f'textures/item/{NAME}_rig.png'
    rig_tex_path.parent.mkdir(parents=True, exist_ok=True)
    rig_tex_path.write_bytes(png_bytes)

    tex_img = Image.open(rig_tex_path).convert('RGBA')
    weapon = build_combat_mesh(bb)
    write_json(ASSETS / f'combat/weapons/{NAME}.json', weapon)
    write_json(ASSETS / f'models/item/{NAME}.json', build_item_model(bb))

    icon_img = render_icon(weapon, tex_img, size=32)
    icon_img.save(ASSETS / f'textures/item/{NAME}.png')
    write_json(ASSETS / f'models/item/{NAME}_icon.json', {
        'parent': 'minecraft:item/handheld',
        'textures': {'layer0': f'logos_dimension:item/{NAME}'},
    })
    write_json(ASSETS / f'items/{NAME}.json', {
        'model': {
            'type': 'minecraft:select',
            'property': 'minecraft:display_context',
            'cases': [{'when': ['gui', 'fixed'], 'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{NAME}_icon'}}],
            'fallback': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{NAME}'},
        },
    })
    print(f'Imported {NAME}: {len(weapon["cubes"])} cubes, tex {weapon["texture_size"]}')


if __name__ == '__main__':
    main()
