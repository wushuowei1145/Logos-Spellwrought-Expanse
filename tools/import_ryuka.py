# -*- coding: utf-8 -*-
"""从美术交付导入流火（魔力凝成的打刀）的物品资源。

用法（需要 pillow）：
    uv run --with pillow python tools/import_ryuka.py "<Minecraft MOD 目录>/art/liuhuo/v04_uv128"

- 几何取 ryuka.bbmodel（维护者最后手改的源文件，柄上的挂坠已经删掉）；去掉刀身两侧用方块做的火焰
  （flame_*）和凝聚动画用的光点（mana_mote_*），火焰改由运行时的狐火表面效果画，凝聚与消散改用附属的消散效果。
- 网格按 GEOMETRY 缩小到打刀的单位（刀身 18，比打刀长约一成），原点在刀镡与刀柄之间，刀尖 +Y，刃 +X。
- 贴图按材质区域重新上色成白樱的淡粉，保留原图的明暗纹理（魔力纹、花瓣脉络、柄卷）；
  发光遮罩同样换色，运行时叠成刀身的自发光层。
- 精简到和神狐打刀相当（见“精简”一节）：刀身 96 行并成约 20 段顺着弧线斜放的方块，柄上的麦穗纹画进贴图，刀镡的花只留花心和花瓣，
  每个面的贴图按方块大小取，Logos 表面火焰和凝聚、消散切的片才不会多到拖慢游戏。
- 物品图标 32×32 由新贴图生成；手持、掉落和魔力缠绕用立体模型，像素坐标 = 网格 + (8, 1, 8)。
"""
import json
import math
import zlib
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
NAME = 'ryuka'
# 美术交付里的文件名还是最初的拼音（流火），游戏里的 id 用日文音读 Ryūka
SOURCE = 'liuhuo'
GEOMETRY = 0.25
SCALE = 1.25
# 晶刃略透明（刀柄、刀镡同一张贴图，一起略透）
ALPHA = 0.88
DROP = ('flame_', 'mana_mote')
SIDES = ('north', 'south', 'west', 'east', 'up', 'down')

# 白樱配色：每个材质区域一条由暗到亮的色阶，原图在该区域里的相对明暗映射到色阶上。
# 取色参考附属的白樱叶（cloudsea_white_cherry_leaf_*：#ebdbe4、#f0e0e9、#ffeff8、#ffffff）和原版樱花叶的粉。
RAMPS = {
    'blade_front': ('#d38fb3', '#eab8d0', '#f6d4e4', '#fbe6f0', '#ffffff'),
    'blade_back': ('#d38fb3', '#eab8d0', '#f6d4e4', '#fbe6f0', '#ffffff'),
    'spine': ('#c98aac', '#e2afc8', '#f0cde0'),
    'edge': ('#f6dbe8', '#fff3f9', '#ffffff'),
    'petal': ('#e8c3d5', '#f4d9e6', '#fbebf3', '#ffffff'),
    'petal_light': ('#efd2e0', '#f8e4ee', '#fff4f9', '#ffffff'),
    'ivory': ('#eedbe5', '#f9ecf2', '#fffafc'),
    'gold': ('#cfa98a', '#e6cbb0', '#f6e7d6'),
    'rose': ('#d995b6', '#ebb9d0', '#f6d6e5'),
    'burgundy': ('#b47a99', '#cf9bb6', '#e6c1d3'),
}
GLOW = ('blade_front', 'blade_back', 'spine', 'edge')


def hex_rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def luma(p):
    return .299 * p[0] + .587 * p[1] + .114 * p[2]


def ramp(colors, t):
    cs = [hex_rgb(c) for c in colors]
    x = max(0.0, min(1.0, t)) * (len(cs) - 1)
    i = min(int(x), len(cs) - 2)
    f = x - i
    return tuple(round(cs[i][k] + (cs[i + 1][k] - cs[i][k]) * f) for k in range(3))


def recolor(texture, regions, glow=False):
    """按区域把原图的相对明暗映射到白樱色阶；不在色阶表里的区域（挂坠、火焰）清空。"""
    out = Image.new('RGBA', texture.size, (0, 0, 0, 0))
    src = texture.load()
    dst = out.load()
    for name, (u0, v0, u1, v1) in regions.items():
        if name not in RAMPS or (glow and name not in GLOW):
            continue
        px = [(u, v) for v in range(v0, v1) for u in range(u0, u1) if src[u, v][3] > 0]
        if not px:
            continue
        # 按明暗排名取色：区域里最多的那种亮度落在色阶中段，最暗的纹路和最亮的高光落在两端
        ls = [luma(src[u, v]) for u, v in px]
        order = sorted(set(ls))
        rank = {l: i for i, l in enumerate(order)}
        below = {}
        for l in ls:
            below[l] = below.get(l, 0) + 1
        cumulative, seen = {}, 0
        for l in order:
            cumulative[l] = (seen + below[l] / 2) / len(ls)
            seen += below[l]
        for (u, v), l in zip(px, ls):
            t = cumulative[l] if len(order) > 1 else 1.0
            dst[u, v] = ramp(RAMPS[name], t) + (255,)
    return out


def groups_to_drop(model):
    """要丢掉的方块，和刀身（blade 组里留下的）方块。"""
    groups = {g['uuid']: g for g in model['groups']}
    dropped, blade = set(), set()

    def walk(node, drop, in_blade):
        if isinstance(node, str):
            (dropped if drop else blade if in_blade else set()).add(node)
            return
        name = groups[node['uuid']]['name']
        for child in node['children']:
            walk(child, drop or name.startswith(DROP), in_blade or name == 'blade')
    for node in model['outliner']:
        walk(node, False, False)
    return dropped, blade


def cubes(model):
    """刀身的方块标 fade：魔力耗尽时只有它们散去，刀柄和刀镡留着。"""
    dropped, blade = groups_to_drop(model)
    out = []
    for e in model['elements']:
        if e['uuid'] in dropped:
            continue
        lo = [min(a, b) * GEOMETRY for a, b in zip(e['from'], e['to'])]
        hi = [max(a, b) * GEOMETRY for a, b in zip(e['from'], e['to'])]
        cube = {'name': e['name'], 'part': 'weapon',
                'start': [round(v, 5) for v in lo], 'end': [round(v, 5) for v in hi],
                'pivot': [round(v * GEOMETRY, 5) for v in e.get('origin', [0, 0, 0])],
                'rotation': [round(v, 5) for v in (e.get('rotation') or [0, 0, 0])],
                'uv': {s: [round(v, 5) for v in e['faces'][s]['uv']] for s in SIDES}}
        if e['uuid'] in blade:
            cube['fade'] = True
        out.append(cube)
    return out


# ---- 精简 ----------------------------------------------------------------------------------
# 2026-11-08 试玩：一拿出来就严重掉帧。原网格 496 块，刀柄、刀镡的小方块每个面都贴一整块材质区域（28×32 格），
# 而 Logos 的表面火焰和附属的凝聚、消散都按贴图格子把面切成小片，每帧画约一百万片。
# 这里精简到和神狐打刀相当（打刀 98 块，最多多三成），每个面的贴图按方块实际大小取，片数降到几千。
BLADE_SEGMENTS = 20      # 刀身原来 96 行（每行刀背、刀身、刃三块），并成约这么多段斜放的方块
OVERLAP = 0.01           # 段与段接缝处各多出的长度
MIN_ROWS = 3             # 每段至少几行：刀尖收得快，不设下限分段都会花在刀尖的一行一行上
TIP_ROWS = 5             # 只有最后这几行可以一行一段，刀尖才是尖的
TEXELS = 6               # 刀柄、刀镡的面按每单位约 6 格取贴图（刀身本来就是每单位 5–7 格）
DROP_DETAIL = ('_petal_tip_', '_pollen_')   # 花只留花心和五片花瓣
DECOR = ('wheat_', 'grip_spark')            # 柄上的麦穗纹和光点画进柄的贴图
GRIP = (100, 64, 114, 128)                  # 柄正反面的烘焙贴图，放在原挂坠、火焰用过的空区域
LAYERS = ('spine', 'blade', 'edge')


def merge_blade(blade, strips):
    """
    刀身 96 行并成几段斜放的方块：每段顺着刀的弧线转一个角度（只绕 Z 轴），所以并得再少，刀背和刃也不起台阶。
    分段按误差二分出来（刀尖收得快，段就短些）。刀身三层（刀背、刀身、刃）每段共用一个转角和枢轴，拼缝对得上。
    刀身贴图横向本来每单位 20 格（竖向 7 格），横向减半（见 halve_blade），strips 是刀身用的几条材质区域。
    """
    rows = {}
    for c in blade:
        _, i, layer = c['name'].split('_')
        rows.setdefault(int(i), {})[layer] = c
    index = sorted(rows)
    y = {i: (rows[i]['blade']['start'][1] + rows[i]['blade']['end'][1]) / 2 for i in index}
    center = {i: (rows[i]['spine']['start'][0] + rows[i]['edge']['end'][0]) / 2 for i in index}

    def fit(group):
        """中线 x = a + b·y：斜率用前后各多一行算，段与段之间转角接得顺。"""
        k = index.index(group[0])
        wide = index[max(0, k - 1):k + len(group) + 1]
        my = sum(y[i] for i in wide) / len(wide)
        mc = sum(center[i] for i in wide) / len(wide)
        var = sum((y[i] - my) ** 2 for i in wide)
        b = sum((y[i] - my) * (center[i] - mc) for i in wide) / var if var else 0
        a = sum(center[i] - b * y[i] for i in group) / len(group)
        return a, b

    def offsets(group, a, b):
        """每层左右边离中线多远（水平量），和这几行各自的偏差。"""
        out = {}
        for layer in LAYERS:
            for side, axis in (('start', 0), ('end', 0)):
                os = [rows[i][layer][side][axis] - (a + b * y[i]) for i in group]
                out[layer, side] = (sum(os) / len(os), max(abs(o - sum(os) / len(os)) for o in os))
        return out

    def error(group):
        a, b = fit(group)
        return max(e for _, e in offsets(group, a, b).values())

    def groups(tol):
        out, cur = [], []
        for i in index:
            if len(cur) >= (1 if i >= index[-TIP_ROWS] else MIN_ROWS) and error(cur + [i]) > tol:
                out.append(cur)
                cur = []
            cur.append(i)
        return out + [cur]
    lo, hi = 0.0, 4.0
    for _ in range(40):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if len(groups(mid)) > BLADE_SEGMENTS else (lo, mid)
    out = []
    parts = groups(hi)
    for n, group in enumerate(parts):
        a, b = fit(group)
        cos = 1 / math.sqrt(1 + b * b)
        bottom, top = rows[group[0]]['blade']['start'][1], rows[group[-1]]['blade']['end'][1]
        py = (bottom + top) / 2
        px = a + b * py
        # 斜放后沿刀身方向的长度；接缝两边各多出一点免得透光，刀尖那段的顶端不加
        half = (top - bottom) / cos / 2
        lo_y, hi_y = py - half - OVERLAP, py + half + (OVERLAP if n < len(parts) - 1 else 0)
        rotation = round(-math.degrees(math.atan(b)), 4)
        off = offsets(group, a, b)
        for layer in LAYERS:
            cs = [rows[i][layer] for i in group]
            low, high = cs[0], cs[-1]
            z0 = sum(c['start'][2] for c in cs) / len(cs)
            z1 = sum(c['end'][2] for c in cs) / len(cs)
            start = [round(px + off[layer, 'start'][0] * cos, 5), round(lo_y, 5), round(z0, 5)]
            end = [round(px + off[layer, 'end'][0] * cos, 5), round(hi_y, 5), round(z1, 5)]
            # 越往上的行 v 越小：并起来是最上一行的 v 起点到最下一行的 v 终点
            uv = {s: [low['uv'][s][0], min(high['uv'][s][1], low['uv'][s][1]), low['uv'][s][2], max(high['uv'][s][3], low['uv'][s][3])]
                  for s in ('north', 'south', 'west', 'east')}
            uv['up'], uv['down'] = list(high['uv']['up']), list(low['uv']['down'])
            for face in uv.values():
                r0 = next(r[0] for r in strips if r[0] <= min(face[0], face[2]) < r[2])
                face[0], face[2] = r0 + (face[0] - r0) / 2, r0 + (face[2] - r0) / 2
            # 两侧窄面（深 0.3 左右）原来也贴满整条宽度：按深度只取中间一两格
            for s in ('west', 'east'):
                w = max(1, round((end[2] - start[2]) * TEXELS))
                mid = round((uv[s][0] + uv[s][2] - w) / 2)
                uv[s][0], uv[s][2] = mid, mid + w
            out.append({'name': f'blade_{n:02d}_{layer}', 'part': 'weapon', 'start': start, 'end': end,
                        'pivot': [round(px, 5), round(py, 5), 0], 'rotation': [0, 0, rotation], 'uv': uv, 'fade': True})
    return out


BLADE_STRIPS = ('blade_front', 'blade_back', 'spine', 'edge')


def halve_blade(images, regions):
    """刀身几条材质区域横向缩成一半宽，放在各自区域的左半边（底色贴图和发光层一起）。"""
    for name in BLADE_STRIPS:
        u0, v0, u1, v1 = regions[name]
        for img in images:
            img.paste(img.crop((u0, v0, u1, v1)).resize(((u1 - u0) // 2, v1 - v0), Image.BOX), (u0, v0))


def region_of(rect, regions):
    for name, r in regions.items():
        if [round(v) for v in rect] == list(r):
            return name
    raise ValueError(f'贴图不是整块材质区域：{rect}')


def fit_uv(cube):
    """把面上的整块材质区域换成区域里按方块大小取的一小块（位置按名字散开，免得每块都一样）。"""
    for side, (a, b) in face_dims(cube).items():
        u0, v0, u1, v1 = cube['uv'][side]
        rw, rh = abs(u1 - u0), abs(v1 - v0)
        tw, th = min(rw, max(1, round(a * TEXELS))), min(rh, max(1, round(b * TEXELS)))
        h = zlib.crc32(f"{cube['name']}/{side}".encode())
        u, v = min(u0, u1) + h % int(rw - tw + 1), min(v0, v1) + (h // 7919) % int(rh - th + 1)
        cube['uv'][side] = [u, v, u + tw, v + th]


def inside(cube, x, y):
    """(x, y) 是否落在方块正面的投影里（方块只绕 Z 轴转）。"""
    px, py = cube['pivot'][0], cube['pivot'][1]
    a = -math.radians(cube['rotation'][2])
    lx = px + (x - px) * math.cos(a) - (y - py) * math.sin(a)
    ly = py + (x - px) * math.sin(a) + (y - py) * math.cos(a)
    return cube['start'][0] <= lx <= cube['end'][0] and cube['start'][1] <= ly <= cube['end'][1]


def bake_grip(base, core, decor, regions):
    """柄的正反面：底色是柄卷，麦穗纹和光点按原来方块的位置和颜色画上去（4×4 采样，盖住三成以上就上色）。"""
    u0, v0, u1, v1 = GRIP
    w, h = u1 - u0, v1 - v0
    base.paste(base.crop(tuple(regions['burgundy'])).resize((w, h), Image.BOX), (u0, v0))
    colors = {}
    for c in decor:
        name = region_of(c['uv']['north'], regions)
        if name not in colors:
            r = regions[name]
            px = [p for p in (base.getpixel((u, v)) for v in range(r[1], r[3]) for u in range(r[0], r[2])) if p[3] > 0]
            colors[name] = tuple(sum(p[i] for p in px) // len(px) for i in range(3)) + (255,)
    front = [(c, colors[region_of(c['uv']['north'], regions)]) for c in decor if c['end'][2] < 0]
    back = sorted((round(c['start'][0], 3), round(c['start'][1], 3)) for c in decor if c['start'][2] > 0)
    assert back == sorted((round(c['start'][0], 3), round(c['start'][1], 3)) for c, _ in front), '柄两面的纹样应当一样'
    x0, y0, x1, y1 = core['start'][0], core['start'][1], core['end'][0], core['end'][1]
    out = base.load()
    for i in range(w):
        for j in range(h):
            hits = {}
            for a in range(4):
                for b in range(4):
                    x = x0 + (i + (a + .5) / 4) / w * (x1 - x0)
                    y = y1 - (j + (b + .5) / 4) / h * (y1 - y0)
                    top = None
                    for c, color in front:
                        if inside(c, x, y):
                            top = color
                    if top:
                        hits[top] = hits.get(top, 0) + 1
            if hits and sum(hits.values()) >= 5:
                out[u0 + i, v0 + j] = max(hits, key=hits.get)
    # 正面 x 小的一边对 u0；背面的角点顺序反过来，u 也反过来，纹样才不左右颠倒
    core['uv']['north'] = [u0, v0, u1, v1]
    core['uv']['south'] = [u1, v0, u0, v1]


def simplify(cubes, base, glow, regions):
    blade = [c for c in cubes if c.get('fade')]
    decor = [c for c in cubes if c['name'].startswith(DECOR)]
    rest = [c for c in cubes if not c.get('fade') and not c['name'].startswith(DECOR) and not any(d in c['name'] for d in DROP_DETAIL)]
    core = next(c for c in rest if c['name'] == 'handle_mana_core')
    for c in rest:
        fit_uv(c)
    bake_grip(base, core, decor, regions)
    halve_blade((base, glow), regions)
    return merge_blade(blade, [regions[n] for n in BLADE_STRIPS]) + rest


def write(path, value):
    path.write_bytes((json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))


def icon(texture, regions):
    """32×32 斜放的刀：柄在左下，刀尖朝右上，刀身向刀背一侧弯。"""
    def mean(name):
        u0, v0, u1, v1 = regions[name]
        px = [texture.getpixel((u, v)) for v in range(v0, v1) for u in range(u0, u1)]
        px = [p for p in px if p[3] > 0]
        return tuple(sum(p[i] for p in px) // len(px) for i in range(3)) + (255,)

    def shade(c, k):
        return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (255,)
    grip, gold, petal, blade, spine, edge = (mean(n) for n in ('burgundy', 'gold', 'petal', 'blade_front', 'spine', 'edge'))
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))

    def put(x, y, c):
        if 0 <= x < 32 and 0 <= y < 32:
            img.putpixel((x, y), c)
    path = [(3 + i, 28 - i) for i in range(26)]
    for i, (x, y) in enumerate(path):
        if i < 1:
            put(x, y, gold); put(x + 1, y, shade(gold, .8))
        elif i < 7:
            put(x, y, grip); put(x + 1, y, shade(grip, .8)); put(x, y - 1, shade(grip, 1.15))
        elif i == 7:
            for dx, dy in ((-2, 1), (-1, 2), (-2, 2), (1, -2), (2, -1), (2, -2), (-1, 1), (1, -1), (0, 0), (0, 1), (1, 0)):
                put(x + dx, y + dy, petal)
            put(x - 2, y + 2, shade(petal, .9)); put(x + 2, y - 2, (255, 255, 255, 255))
        else:
            lift = (i - 8) * (i - 8) // 60
            put(x - lift, y - lift, spine); put(x + 1 - lift, y - lift, blade); put(x + 1 - lift, y + 1 - lift, edge)
    return img


# 物品模型只在地上（缩到 0.42）出现，另外是 Logos 表面火焰贴着的表面：火焰按贴图格子每格画一片，
# 所以这里的贴图再粗一半（每单位约 3 格），片数和神狐打刀相当；手上看到的刀是战斗网格，贴图不受影响。
ITEM_TEXELS = 3


def face_dims(cube):
    dx, dy, dz = (e - s for s, e in zip(cube['start'], cube['end']))
    return {'north': (dx, dy), 'south': (dx, dy), 'west': (dz, dy), 'east': (dz, dy), 'up': (dx, dz), 'down': (dx, dz)}


def coarse(uv, a, b):
    """从这个面贴图的起点取按大小算的一块，方向不变。"""
    u0, v0, u1, v1 = uv
    du = max(1, min(abs(u1 - u0), round(a * ITEM_TEXELS)))
    dv = max(1, min(abs(v1 - v0), round(b * ITEM_TEXELS)))
    return [u0, v0, u0 + math.copysign(du, u1 - u0), v0 + math.copysign(dv, v1 - v0)]


def item_model(weapon):
    w, h = weapon['texture_size']
    offset = (8, 1, 8)
    elements = []
    for cube in weapon['cubes']:
        dims = face_dims(cube)
        element = {'name': cube['name'],
                   'from': [round(cube['start'][i] + offset[i], 4) for i in range(3)],
                   'to': [round(cube['end'][i] + offset[i], 4) for i in range(3)],
                   'faces': {side: {'uv': [round(uv[0] * 16 / w, 4), round(uv[1] * 16 / h, 4),
                                           round(uv[2] * 16 / w, 4), round(uv[3] * 16 / h, 4)], 'texture': '#0'}
                             for side, uv in ((side, coarse(uv, *dims[side])) for side, uv in cube['uv'].items())}}
        if any(cube['rotation']):
            x, y, z = cube['rotation']
            element['rotation'] = {'origin': [round(cube['pivot'][i] + offset[i], 4) for i in range(3)], 'x': x, 'y': y, 'z': z}
        elements.append(element)
    hand = {'rotation': [0, 0, 0], 'translation': [0, 4.4, 1], 'scale': [0.7, 0.7, 0.7]}
    return {'textures': {'0': f'logos_dimension:item/{NAME}_rig', 'particle': f'logos_dimension:item/{NAME}'},
            'gui_light': 'front', 'display': {
                'thirdperson_righthand': hand, 'thirdperson_lefthand': hand,
                'firstperson_righthand': {'rotation': [0, -30, 15], 'translation': [1.2, 4.0, 1], 'scale': [0.55, 0.55, 0.55]},
                'firstperson_lefthand': {'rotation': [0, 30, -15], 'translation': [1.2, 4.0, 1], 'scale': [0.55, 0.55, 0.55]},
                'ground': {'rotation': [0, 0, 90], 'translation': [0, 1.5, 0], 'scale': [0.42, 0.42, 0.42]}},
            'elements': elements}


def load(art):
    model = json.load(open(art / f'{SOURCE}.bbmodel', encoding='utf-8'))
    regions = json.load(open(art / 'uv_layout.json', encoding='utf-8'))['regions']
    texture = Image.open(art / f'{SOURCE}.png').convert('RGBA')
    glow = Image.open(art / f'{SOURCE}_glowmask.png').convert('RGBA')
    size = [model['resolution']['width'], model['resolution']['height']]
    return model, regions, texture, glow, size


def main():
    art = Path(sys.argv[1])
    model, regions, texture, glow, size = load(art)
    base = recolor(texture, regions)
    lit = recolor(glow, regions, glow=True)
    weapon = {'texture': f'logos_dimension:textures/item/{NAME}_rig.png', 'texture_size': size,
              'scale': SCALE, 'alpha': ALPHA, 'glow': f'logos_dimension:textures/item/{NAME}_glow.png',
              'cubes': simplify(cubes(model), base, lit, regions)}
    write(ASSETS / f'combat/weapons/{NAME}.json', weapon)
    base.save(ASSETS / f'textures/item/{NAME}_rig.png')
    lit.save(ASSETS / f'textures/item/{NAME}_glow.png')
    icon(base, regions).save(ASSETS / f'textures/item/{NAME}.png')
    write(ASSETS / f'models/item/{NAME}_icon.json',
          {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'logos_dimension:item/{NAME}'}})
    write(ASSETS / f'models/item/{NAME}.json', item_model(weapon))
    write(ASSETS / f'items/{NAME}.json', {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:display_context',
        'cases': [{'when': ['gui', 'fixed'], 'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{NAME}_icon'}}],
        'fallback': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{NAME}'}}})
    print('imported', NAME, len(weapon['cubes']), 'cubes')


if __name__ == '__main__':
    main()
