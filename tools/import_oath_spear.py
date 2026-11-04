# -*- coding: utf-8 -*-
"""导入群誓·狼牙长枪的美术交付（art/pack_oath_spear/v02）。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/import_oath_spear.py <美术目录 art/pack_oath_spear/v02>

- combat 网格：原样复制方块，加 scale（乘在骨架的武器缩放上，第三人称为 1 倍，枪长约 2.7 格）。
- 物品模型：按网格重新生成，像素坐标 = 网格坐标 + (8, 1, 8)，与长剑、打刀相同，魔力缠绕按它的表面定位。
  交付的模型是网格 × 0.65 再平移到 (8, 8, 8)；各视角的展示变换按同一组几何换算，拿在手里、掉在地上、
  物品栏里的样子与交付一致。
- 戳刺残影贴图：从枪头方块（枪锋与銎口）的正面渲染一张剪影，枪尖朝贴图右方（+U），淡钢蓝的半透明光影。
"""
import json
import math
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
NAME = 'pack_oath_spear'
# 第三人称骨架的武器缩放是 0.8，乘上它正好是美术原尺寸。
SCALE = 1.25
OFFSET = (8, 1, 8)
# 交付模型：像素 = 网格 × 0.65 + (8, 8, 8)。
DELIVERED_SCALE = 0.65


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))


def rotation_xyz(r):
    """原版 ItemTransform 的旋转：Quaternionf.rotationXYZ，即 Rx·Ry·Rz（度）。"""
    x, y, z = (math.radians(v) for v in r)
    rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    rz = np.array([[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]])
    return rx @ ry @ rz


def convert_display(display):
    """原版先把模型像素减 8 居中，再 平移·旋转·缩放：final = T + R·s·(p − 8)。
    交付模型 p = 0.65·m + 8，新模型 p' = m + (8, 1, 8)。令两者相同：s' = 0.65·s，T' = T + R·s'·(0, 7, 0)。"""
    out = {}
    for context, t in display.items():
        s = np.array(t.get('scale', [1, 1, 1]), float) * DELIVERED_SCALE
        R = rotation_xyz(t.get('rotation', [0, 0, 0]))
        shift = np.array([8 - OFFSET[0], 8 - OFFSET[1], 8 - OFFSET[2]], float) * s
        T = np.array(t.get('translation', [0, 0, 0]), float) + R @ shift
        out[context] = {'rotation': t.get('rotation', [0, 0, 0]),
                        'translation': [round(float(v), 4) for v in T],
                        'scale': [round(float(v), 4) for v in s]}
        assert all(abs(v) <= 80 for v in out[context]['translation']), (context, out[context])
    return out


def item_model(weapon, display):
    w, h = weapon['texture_size']
    elements = []
    for cube in weapon['cubes']:
        lo = [min(cube['start'][i], cube['end'][i]) + OFFSET[i] for i in range(3)]
        hi = [max(cube['start'][i], cube['end'][i]) + OFFSET[i] for i in range(3)]
        assert all(-16 <= v <= 32 for v in lo + hi), cube['name']
        element = {'name': cube['name'], 'from': [round(v, 4) for v in lo], 'to': [round(v, 4) for v in hi],
                   'faces': {side: {'uv': [round(uv[0] * 16 / w, 4), round(uv[1] * 16 / h, 4),
                                           round(uv[2] * 16 / w, 4), round(uv[3] * 16 / h, 4)], 'texture': '#0'}
                             for side, uv in cube['uv'].items()}}
        if any(cube['rotation']):
            x, y, z = cube['rotation']
            element['rotation'] = {'origin': [round(cube['pivot'][i] + OFFSET[i], 4) for i in range(3)], 'x': x, 'y': y, 'z': z}
        elements.append(element)
    return {'credit': '群誓长枪', 'textures': {'0': f'logos_dimension:item/{NAME}', 'particle': f'logos_dimension:item/{NAME}'},
            'gui_light': 'front', 'display': display, 'elements': elements}


def tip_sprite(weapon, texture, px=8):
    """枪头正面剪影：取 y ≥ 19（銎口以上）的方块，按北面贴图逐像素画，近的盖住远的；枪尖朝右。"""
    w, h = weapon['texture_size']
    tex = np.asarray(texture.convert('RGBA'))
    cubes = [c for c in weapon['cubes'] if min(c['start'][1], c['end'][1]) >= 19.0]
    xs = [v for c in cubes for v in (c['start'][0], c['end'][0])]
    ys = [v for c in cubes for v in (c['start'][1], c['end'][1])]
    x0, x1, y0, y1 = min(xs) - 0.6, max(xs) + 0.6, min(ys) - 0.6, max(ys) + 0.6
    W, H = int(math.ceil((y1 - y0) * px)), int(math.ceil((x1 - x0) * px))
    rgba = np.zeros((H, W, 4), np.float32)
    depth = np.full((H, W), np.inf)
    for c in cubes:
        lo = np.minimum(c['start'], c['end']); hi = np.maximum(c['start'], c['end'])
        pivot, rot = np.array(c['pivot'], float), c['rotation']
        a = math.radians(rot[2])  # 交付的斜线都只绕 z 转（正面看是斜的）
        Rz = np.array([[math.cos(a), -math.sin(a)], [math.sin(a), math.cos(a)]])
        u0, v0, u1, v1 = c['uv']['north']
        # 北面（z 最小，朝 -Z，正面看到的那面）逐采样点反算回方块局部坐标。
        for j in range(H):
            for i in range(W):
                mx, my = x1 - (j + 0.5) / px, y0 + (i + 0.5) / px  # 贴图 +U = 网格 +Y（枪尖），+V = 网格 -X
                q = np.linalg.solve(Rz, np.array([mx, my]) - pivot[:2]) + pivot[:2]
                if not (lo[0] <= q[0] <= hi[0] and lo[1] <= q[1] <= hi[1]):
                    continue
                z = lo[2]
                if z >= depth[j, i]:
                    continue
                # 北面贴图：U 从 +X 到 -X，V 从 +Y 到 -Y。
                fu = (hi[0] - q[0]) / max(hi[0] - lo[0], 1e-6); fv = (hi[1] - q[1]) / max(hi[1] - lo[1], 1e-6)
                tu = int(min(max(u0 + (u1 - u0) * fu, 0), w - 1e-3)); tv = int(min(max(v0 + (v1 - v0) * fv, 0), h - 1e-3))
                color = tex[tv, tu]
                if color[3] < 8:
                    continue
                depth[j, i] = z
                rgba[j, i] = color
    # 残影：亮度保留明暗，色调统一成淡钢蓝；外加一圈柔和的光晕。
    lum = (rgba[..., :3] @ np.array([0.3, 0.55, 0.15]))[..., None] / 255.0
    solid = (rgba[..., 3] > 0).astype(np.float32)
    steel = np.array([0.74, 0.88, 1.0])
    core = np.clip(0.7 + 0.5 * lum, 0, 1) * steel
    pad = 6
    canvas = np.zeros((H + 2 * pad, W + 2 * pad), np.float32)
    canvas[pad:pad + H, pad:pad + W] = solid
    halo = np.asarray(Image.fromarray((canvas * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(3)), np.float32) / 255.0
    out = np.zeros((H + 2 * pad, W + 2 * pad, 4), np.float32)
    out[..., :3] = steel * 0.85
    out[..., 3] = np.clip(halo * 0.75, 0, 1)
    body = np.zeros_like(out)
    body[pad:pad + H, pad:pad + W, :3] = core
    body[pad:pad + H, pad:pad + W, 3] = solid * 0.92
    a = body[..., 3:4]
    out[..., :3] = body[..., :3] * a + out[..., :3] * (1 - a)
    out[..., 3] = np.maximum(out[..., 3], body[..., 3])
    # 尾部（銎口一侧）淡出，枪尖最实：看起来是往前飞的残影。
    u = np.linspace(0, 1, out.shape[1])[None, :]
    ramp = 0.2 + 0.8 * np.clip((u - 0.08) / 0.5, 0, 1) ** 1.5
    out[..., 3] *= ramp
    return Image.fromarray(np.clip(out * 255, 0, 255).astype(np.uint8), 'RGBA')


def main():
    art = Path(sys.argv[1])
    res = art / 'resources/assets/logos_dimension'
    delivered = json.load(open(res / f'combat/weapons/{NAME}.json', encoding='utf-8'))
    weapon = {'texture': f'logos_dimension:textures/item/{NAME}.png', 'texture_size': delivered['texture_size'],
              'scale': SCALE, 'cubes': delivered['cubes']}
    write(ASSETS / f'combat/weapons/{NAME}.json', weapon)
    (ASSETS / 'textures/item').mkdir(parents=True, exist_ok=True)
    shutil.copyfile(art / f'textures/{NAME}.png', ASSETS / f'textures/item/{NAME}.png')
    model = json.load(open(res / f'models/item/{NAME}.json', encoding='utf-8'))
    write(ASSETS / f'models/item/{NAME}.json', item_model(weapon, convert_display(model['display'])))
    write(ASSETS / f'items/{NAME}.json', {'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{NAME}'}})
    sprite = tip_sprite(weapon, Image.open(art / f'textures/{NAME}.png'))
    (ASSETS / 'textures/vfx').mkdir(parents=True, exist_ok=True)
    sprite.save(ASSETS / 'textures/vfx/spear_tip_ghost.png')
    print('imported', NAME, 'cubes', len(weapon['cubes']), 'tip sprite', sprite.size)


if __name__ == '__main__':
    main()
