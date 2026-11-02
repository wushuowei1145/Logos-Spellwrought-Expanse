"""神狐聚落的软件预览：按真实贴图的平均色画等轴体素图，用来在进游戏前检查体量与配色。

这是代码绘制的示意图，不代表实机观感；实机效果以客户端截图为准。
"""
from pathlib import Path
import io
import zipfile

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OWN = ROOT / 'src/main/resources/assets/logos_dimension/textures/block'
CLIENT = Path.home() / '.gradle/caches/neoformruntime/artifacts/minecraft_26.1.2_client.jar'

TINT = {
    'grass_block': (124, 160, 90), 'short_grass': (110, 150, 80), 'fern': (100, 140, 75), 'oak_leaves': (90, 140, 60),
    'spruce_leaves': (70, 110, 70), 'azalea_leaves': (100, 140, 70), 'water': (60, 110, 190), 'lily_pad': (60, 130, 50),
    'vine': (80, 120, 60), 'birch_leaves': (110, 150, 80), 'dark_oak_leaves': (70, 120, 50),
}
SUFFIXES = ['_stairs', '_slab', '_fence_gate', '_fence', '_wall', '_trapdoor', '_door', '_button', '_pressure_plate', '_pane']
_cache = {}
_zip = None


def _load(name):
    global _zip
    p = OWN / f'{name}.png'
    if p.exists():
        return Image.open(p).convert('RGBA')
    if _zip is None and CLIENT.exists():
        _zip = zipfile.ZipFile(CLIENT)
    if _zip is not None:
        try:
            return Image.open(io.BytesIO(_zip.read(f'assets/minecraft/textures/block/{name}.png'))).convert('RGBA')
        except KeyError:
            return None
    return None


def _avg(img):
    a = np.asarray(img.crop((0, 0, img.width, min(img.height, img.width)))).astype(float)
    m = a[..., 3] > 20
    if not m.any():
        return None
    return tuple(int(v) for v in a[m][:, :3].mean(0))


def colors(state):
    name = state['Name'].split(':')[1]
    if name in _cache:
        return _cache[name]
    base = name
    for s in SUFFIXES:
        if base.endswith(s):
            base = base[:-len(s)]
            break
    cands = [base, base + '_0', base + '_side', base + '_stage7', base + '_stage3', base + 's', base + '_planks', base.replace('brick', 'bricks'), base + '_block', base.replace('_lower', '').replace('_upper', '')]
    if base.startswith('journey_kawara'):
        cands.insert(0, 'journey_kawara_roof')
    if base.startswith('journey_lacquer'):
        cands.insert(0, 'journey_lacquer_planks')
    if base.startswith('journey_tatami'):
        cands.insert(0, 'journey_tatami_top')
    if base == 'journey_shoji_door':
        cands.insert(0, 'journey_shoji_door_top')
    side = top = None
    for c in cands:
        img = _load(c)
        if img is not None:
            side = _avg(img)
            t = _load(c + '_top')
            top = _avg(t) if t is not None else side
            break
    if side is None:
        side = top = (200, 0, 200)
    key = base.replace('_top', '')
    if key in TINT or name in TINT:
        tint = TINT.get(name, TINT.get(key))
        top = tint
        if name != 'grass_block':
            side = tint
        else:
            side = (134, 96, 67)
    if name == 'grass_block':
        top = TINT['grass_block']
    _cache[name] = (top, side)
    return top, side


PARTIAL = ('_slab', '_fence', '_trapdoor', 'lantern', 'noren', 'shimenawa', 'carpet', 'torch', 'flower', 'grass', 'fern', 'petals', 'bamboo', 'rice', 'wheat', 'carrots', 'potatoes', 'beetroots', 'pad', 'window', 'door', 'pot', 'stone_basin', 'stepping', 'statue', 'hokora', 'spout')


def render(cells, scale=4, yaw=0, light=(1.0, 0.82, 0.64), bg=(233, 236, 224)):
    """等轴投影：x 向右下、z 向左下、y 向上。yaw 为 90° 的倍数，旋转视角。"""
    pts = {}
    for (x, y, z), s in cells.items():
        if s['Name'] in ('minecraft:air', 'minecraft:structure_void', 'minecraft:cave_air'):
            continue
        for _ in range(yaw % 4):
            x, z = -z, x
        pts[(x, y, z)] = s
    if not pts:
        return Image.new('RGB', (10, 10), bg)
    occ = set(k for k, s in pts.items() if not any(p in s['Name'] for p in PARTIAL))
    xs = [k[0] for k in pts]
    ys = [k[1] for k in pts]
    zs = [k[2] for k in pts]

    def proj(x, y, z):
        return ((x - z) * scale * 0.866, (x + z) * scale * 0.5 - y * scale)

    corners = [proj(x, y, z) for x in (min(xs), max(xs) + 1) for y in (min(ys), max(ys) + 1) for z in (min(zs), max(zs) + 1)]
    ox = -min(c[0] for c in corners) + 10
    oy = -min(c[1] for c in corners) + 10
    w = int(max(c[0] for c in corners) + ox + 10)
    h = int(max(c[1] for c in corners) + oy + 10)
    img = Image.new('RGB', (w, h), bg)
    d = ImageDraw.Draw(img)

    def P(x, y, z):
        a, b = proj(x, y, z)
        return (a + ox, b + oy)

    for (x, y, z) in sorted(pts, key=lambda k: (k[0] + k[2], k[1])):
        s = pts[(x, y, z)]
        top, side = colors(s)
        hgt = 0.5 if '_slab' in s['Name'] and s.get('Properties', {}).get('type') == 'bottom' else 1.0
        y0 = y + (0.5 if '_slab' in s['Name'] and s.get('Properties', {}).get('type') == 'top' else 0)
        y1 = y0 + hgt
        if (x, y + 1, z) not in occ:
            d.polygon([P(x, y1, z), P(x + 1, y1, z), P(x + 1, y1, z + 1), P(x, y1, z + 1)], fill=tuple(int(c * light[0]) for c in top))
        if (x, y, z + 1) not in occ:
            d.polygon([P(x, y0, z + 1), P(x + 1, y0, z + 1), P(x + 1, y1, z + 1), P(x, y1, z + 1)], fill=tuple(int(c * light[1]) for c in side))
        if (x + 1, y, z) not in occ:
            d.polygon([P(x + 1, y0, z), P(x + 1, y0, z + 1), P(x + 1, y1, z + 1), P(x + 1, y1, z)], fill=tuple(int(c * light[2]) for c in side))
    return img


def top_down(cells, scale=3, bg=(233, 236, 224)):
    """俯视：每列取最高方块的顶面颜色，按高度微调明暗。"""
    best = {}
    for (x, y, z), s in cells.items():
        if s['Name'] in ('minecraft:air', 'minecraft:structure_void'):
            continue
        if (x, z) not in best or y > best[(x, z)][0]:
            best[(x, z)] = (y, s)
    xs = [k[0] for k in best]
    zs = [k[1] for k in best]
    ys = [v[0] for v in best.values()]
    x0, z0 = min(xs), min(zs)
    img = Image.new('RGB', ((max(xs) - x0 + 1) * scale, (max(zs) - z0 + 1) * scale), bg)
    d = ImageDraw.Draw(img)
    lo, hi = min(ys), max(ys)
    for (x, z), (y, s) in best.items():
        top, _ = colors(s)
        f = 0.7 + 0.45 * (y - lo) / max(1, hi - lo)
        d.rectangle([(x - x0) * scale, (z - z0) * scale, (x - x0 + 1) * scale - 1, (z - z0 + 1) * scale - 1], fill=tuple(min(255, int(c * f)) for c in top))
    return img
