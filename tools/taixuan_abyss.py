"""太玄深渊：浮空宗门下的深渊、雾层、四根镇渊柱、铁链、链台、雾底场地和下去的路。全部离线画进模板。

画布 x、z 是 0..251（中心 126），y 是 0..307，世界高度 = 画布 y + Y0。原版拼接把起点锚放在世界 y=120，
锚在画布 y=144，所以 Y0 = -24。深渊口按云岫峰林的台地（世界 y≈92）设计，选址时要求深渊口一圈的地面不低于 80。

高度（世界 y）：
  雾底场地 -16，雾层 14～34（顶面起伏），链台 62，深渊石壁封水到 76，深渊口约 92，
  镇渊柱 70～178（顶上一座亭子），铁链挂在柱子 150 的高度，主岛顶面约 205。
"""
import math
import random

import numpy as np

from taixuan_kit import B, L, AIR, STONE, WHITE, TRIM, WOOD, BEAM_X, BEAM_Z, PLANK, roof, rail
import taixuan_terrain as terrain

Y0 = -24
SIZE_XZ = 252
SIZE_Y = 308
C = 126


def cy(world):
    return world - Y0


ARENA = cy(-16)
MIST_BOTTOM, MIST_TOP = cy(14), cy(34)
PLATFORM = cy(62)
LINING_TOP = cy(76)
RIM = cy(92)
TOWER_BASE, TOWER_TOP = cy(70), cy(178)
ANCHOR = cy(150)
PLATFORM_R = 15
TOWER_R, TOWER_HALF = 84, 6
# 四根镇渊柱在四个斜角：东南、西南、西北、东北（x = C + r·cos a，z = C + r·sin a）
TOWER_ANGLES = (math.pi / 4, 3 * math.pi / 4, 5 * math.pi / 4, 7 * math.pi / 4)
TOWERS = [(C + round(TOWER_R * math.cos(a)), C + round(TOWER_R * math.sin(a))) for a in TOWER_ANGLES]
GATE_TOWER = 1  # 西南那根，顶上有桥通到山门岛

IRON = L('taixuan_dark_iron')
MIST = L('taixuan_abyss_mist')
BRASS = L('taixuan_brass_block')
CARVED = L('taixuan_carved_brick')
POLISHED = B('polished_andesite')

# 导出时要显式写空气的范围：深渊竖井（按函数算）、各浮岛和桥的上方（按列）、铁链周围（按球）
CLEAR_COLUMNS = {}
CLEAR_SPHERES = []
# 需要往下接地的位置（镇渊柱底座、深渊口的平台）
FOOTINGS = []
REPORT = {}
# 走得通的路线：每条是一串“脚下那格”（地板方块），脚下有碰撞、上面两格没有，审计和世界测试都按这条规则查
ROUTES = {}


def route(name, x, y, z):
    ROUTES.setdefault(name, []).append([int(x), int(y), int(z)])


def clear_column(x, z, y_from):
    if 0 <= x < SIZE_XZ and 0 <= z < SIZE_XZ:
        CLEAR_COLUMNS[x, z] = min(CLEAR_COLUMNS.get((x, z), SIZE_Y), y_from)


# ---------------------------------------------------------------- 竖井
def shaft_radius(y, theta):
    """深渊半径：雾底约 50 格，往上慢慢放宽，石壁顶以上再张开到约 60；带一圈起伏，不是正圆。y、theta 可为数组。"""
    y = np.asarray(y, float)
    low = 50 + 4 * (y - ARENA) / (LINING_TOP - ARENA)
    high = 54 + np.minimum(6, (y - LINING_TOP) * .25)
    base = np.where(y <= LINING_TOP, low, high)
    n = terrain.noise(np.cos(theta) * 2.2 + y / 19, np.sin(theta) * 2.2 - y / 23)
    return base + (n - .5) * 7


BASIN_R = 104
BASIN_TOP = cy(264)  # 峰林柱顶最高约 y=260


def basin_radius(theta):
    """深渊口外清出来的一片空地：半径约 104 格，带 ±8 格起伏。theta 可为数组。"""
    return BASIN_R + (terrain.noise(np.cos(theta) * 2.6 + 11.0, np.sin(theta) * 2.6 + 5.0) - .5) * 16


def in_basin(x, y, z):
    """导出时用：深渊口高度以上、空地半径以内的原地形（峰林的石柱和柱脚坡）清成空气，
    镇渊柱和侧峰不会埋在石柱里；深渊口以下保留原来的台地，空地的地面就是原地面（高出深渊口的部分削平到口沿）。"""
    dx, dz = np.asarray(x, float) - C, np.asarray(z, float) - C
    y = np.asarray(y)
    return (y >= RIM) & (y <= BASIN_TOP) & (np.hypot(dx, dz) <= basin_radius(np.arctan2(dz, dx)))


def approach(c, x0, x1, z0, step_z, top_y, steps=12, axis='z'):
    """入口外接一段下行石阶，到口沿以下 steps 格。台阶头顶不写空气：原地面高的地方台阶埋在地里，
    低的地方露出来、底下接地；从哪一级的高度走过来都能踩上去。step_z 是往外走的方向（+1/-1）。"""
    facing = {('z', 1): 'north', ('z', -1): 'south', ('x', 1): 'west', ('x', -1): 'east'}[axis, step_z]
    for k in range(1, steps + 1):
        y = top_y - k
        for w in range(x0, x1 + 1):
            x, z = (w, z0 + step_z * k) if axis == 'z' else (z0 + step_z * k, w)
            c.set(x, y, z, B('stone_brick_stairs', facing=facing, half='bottom', shape='straight', waterlogged=False))
            c.set(x, y - 1, z, STONE)
            if w in (x0, x1):FOOTINGS.append((x, y - 1, z))


# 空地地面的记号方块：模板里只在这一层写它，原版的规则处理器在放的时候看那一格原来是什么——
# 原来是空的（原地面更低）就不放，是水就放水，是土石（原地面够高、被削平）就换成草方块。别处不用这种方块
FLOOR_MARK = B('dead_horn_coral_block')


def basin_floor(c):
    """空地地面：深渊口以外、空地半径以内，口沿下一格（RIM-1）还空着的格子写记号方块，放的时候按原地形变成草或不放。"""
    X, Z, D, T = _grid(int(BASIN_R + 9))
    R = basin_radius(T)
    S = shaft_radius(RIM - 1, T)
    count = 0
    for i, j in zip(*np.nonzero((D > S + 1) & (D <= R))):
        x, z = int(X[i, j]), int(Z[i, j])
        if 0 <= x < SIZE_XZ and 0 <= z < SIZE_XZ and c.get(x, RIM - 1, z) is None:
            c.set(x, RIM - 1, z, FLOOR_MARK);count += 1
    REPORT['basin_floor'] = count
    REPORT['basin'] = {'radius': BASIN_R, 'wobble': 8, 'top': BASIN_TOP}


def in_shaft(x, y, z):
    """导出时用：这些格子在深渊竖井里，要清成空气。"""
    dx, dz = np.asarray(x, float) - C, np.asarray(z, float) - C
    return (np.asarray(y) > ARENA) & (np.hypot(dx, dz) <= shaft_radius(y, np.arctan2(dz, dx)))


def _grid(r):
    xs = np.arange(C - r, C + r + 1)
    X, Z = np.meshgrid(xs, xs, indexing='ij')
    dx, dz = X - C, Z - C
    return X, Z, np.hypot(dx, dz), np.arctan2(dz, dx)


STRATA = ('stone', 'andesite', 'calcite', 'stone', 'diorite', 'tuff', 'andesite', 'stone')


def _rock(x, y, z, face):
    """石壁：起伏的水平层理，朝深渊那面偶尔长苔。"""
    warp = (terrain.noise(x / 21.0, z / 21.0) - .5) * 7
    band = int(math.floor((y + warp) / 3.5)) % len(STRATA)
    speck = terrain.noise(x / 1.9 + y * .37, z / 1.9 - y * .21)
    if face and speck > .74:
        return B('mossy_cobblestone' if speck > .86 else 'moss_block')
    if speck < .12:
        return B('tuff')
    return B(STRATA[band])


def shaft(c):
    """深渊石壁（三格厚，封住地下水）、雾底的地面、雾层。"""
    X, Z, D, T = _grid(66)
    lining = floor = mist = 0
    for y in range(ARENA - 2, LINING_TOP + 1):
        R = shaft_radius(y, T)
        ring = (D > R) & (D <= R + 3)
        inner = D <= R + 1
        for i, j in zip(*np.nonzero(ring)):
            x, z = int(X[i, j]), int(Z[i, j])
            if c.get(x, y, z) is None:
                c.set(x, y, z, _rock(x, y, z, bool(inner[i, j])));lining += 1
    # 雾底：三层石头，顶层按圈铺深板岩砖，外圈碎石与苔
    R = shaft_radius(ARENA, T)
    for i, j in zip(*np.nonzero(D <= R + 3)):
        x, z, d = int(X[i, j]), int(Z[i, j]), float(D[i, j])
        for y in (ARENA - 2, ARENA - 1):c.set(x, y, z, B('deepslate'))
        speck = terrain.noise(x / 2.3, z / 2.3)
        if d < 40:
            ring = int(d / 4) % 2
            top = 'polished_deepslate' if ring else 'deepslate_tiles'
            if speck > .78:top = 'cracked_deepslate_tiles'
            elif speck < .14:top = 'mossy_cobblestone'
        else:
            top = 'mossy_cobblestone' if speck > .6 else 'tuff' if speck > .3 else 'cobbled_deepslate'
        c.set(x, ARENA, z, B(top));floor += 1
    # 雾层：底在世界 14，顶面在 31～37 之间起伏，像一片云海
    for i, j in zip(*np.nonzero(D <= 64)):
        x, z = int(X[i, j]), int(Z[i, j])
        top = MIST_TOP - 3 + int(round(6 * terrain.noise(x / 13.0, z / 13.0)))
        for y in range(MIST_BOTTOM, top + 1):
            if float(D[i, j]) <= float(shaft_radius(y, T[i, j])) and c.get(x, y, z) is None:
                c.set(x, y, z, MIST);mist += 1
    REPORT['shaft'] = {'lining_blocks': lining, 'floor_blocks': floor, 'mist_blocks': mist}


# ---------------------------------------------------------------- 雾底场地
def arena(c):
    """雾底：中间一座两级石台（以后放 boss），一圈八根断柱，柱上魂灯。"""
    for dx in range(-7, 8):
        for dz in range(-7, 8):
            d = math.hypot(dx, dz)
            if d <= 7:c.set(C + dx, ARENA + 1, C + dz, B('smooth_stone'))
            if d <= 4.5:c.set(C + dx, ARENA + 2, C + dz, B('polished_diorite'))
            if d <= 1.5:c.set(C + dx, ARENA + 3, C + dz, IRON)
    rng = random.Random(2026)
    pillars = []
    for k in range(8):
        a = k * math.pi / 4 + math.pi / 8
        px, pz = C + round(28 * math.cos(a)), C + round(28 * math.sin(a))
        h = rng.randint(5, 15)
        for y in range(ARENA + 1, ARENA + 1 + h):
            for dx in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    top = y == ARENA + h
                    # 断口：顶层随机缺几块，中间那块留着托魂灯
                    if top and (dx or dz) and rng.random() < .45:continue
                    c.set(px + dx, y, pz + dz, POLISHED if (y - ARENA) % 5 == 0 else B('stone_bricks') if rng.random() > .2 else B('cracked_stone_bricks'))
        c.set(px, ARENA + 1 + h, pz, B('soul_lantern', hanging=False, waterlogged=False))
        pillars.append([px, ARENA + 1, pz, h])
    REPORT['arena'] = {'floor_y': ARENA, 'dais': [C, ARENA + 3, C], 'pillars': pillars}


# ---------------------------------------------------------------- 链台
def octagon(dx, dz, r):
    return abs(dx) <= r and abs(dz) <= r and abs(dx) + abs(dz) <= r * 1.414


TRIGRAMS = ('111', '000', '100', '011', '010', '101', '001', '110')  # 乾坤震巽坎离艮兑，自内向外


def _floor_state(dx, dz):
    d = math.hypot(dx, dz)
    if d < 5:
        # 太极：上半个小圆黑、下半个小圆白，两点反色
        up, down = math.hypot(dx, dz - 2.5), math.hypot(dx, dz + 2.5)
        if up < .9:return B('calcite')
        if down < .9:return B('polished_blackstone')
        if up <= 2.5:return B('polished_blackstone')
        if down <= 2.5:return B('calcite')
        return B('polished_blackstone') if dx < 0 else B('calcite')
    if d < 6:return BRASS
    if d < 11.8:
        a = math.atan2(dz, dx)
        k = round(a / (math.pi / 4)) % 8
        u = d
        v = (math.atan2(dz, dx) - k * math.pi / 4)
        v = (v + math.pi) % (2 * math.pi) - math.pi
        v *= d
        for bar, radius in zip(TRIGRAMS[k], (7.5, 9.0, 10.5)):
            if abs(u - radius) < .55 and abs(v) < 1.6:
                if bar == '0' and abs(v) < .55:return POLISHED
                return IRON
        return B('polished_deepslate') if abs(v) < .5 else POLISHED
    if d < 12.8:return IRON
    return CARVED


def platform(c):
    """八角链台：太极、黄铜圈、八卦、玄铁圈、云纹石边；底下倒收成尖，边上一圈海晶灯。"""
    r = PLATFORM_R
    for dx in range(-r, r + 1):
        for dz in range(-r, r + 1):
            if not octagon(dx, dz, r):continue
            x, z = C + dx, C + dz
            c.set(x, PLATFORM, z, _floor_state(dx, dz))
            edge = not octagon(dx + (1 if dx > 0 else -1), dz, r) or not octagon(dx, dz + (1 if dz > 0 else -1), r)
            c.set(x, PLATFORM - 1, z, B('sea_lantern') if edge and (dx + dz) % 4 == 0 else CARVED if edge else STONE)
            c.set(x, PLATFORM - 2, z, IRON if edge else STONE)
            if edge:c.set(x, PLATFORM + 1, z, B('polished_andesite_slab', type='bottom', waterlogged=False))
    # 底下倒收成尖
    for k, rr in enumerate(range(r - 2, 1, -2)):
        y = PLATFORM - 3 - k
        for dx in range(-rr, rr + 1):
            for dz in range(-rr, rr + 1):
                if octagon(dx, dz, rr):c.set(C + dx, y, C + dz, CARVED if k % 2 == 0 else STONE)
    REPORT['platform_bottom'] = PLATFORM - 3 - len(range(r - 2, 1, -2))
    # 拴链子的铁桩：四个斜角接镇渊柱，东西两角接主岛
    posts = {}
    for name, (dx, dz) in {'se': (10, 10), 'sw': (-10, 10), 'nw': (-10, -10), 'ne': (10, -10), 'e': (13, 0), 'w': (-13, 0)}.items():
        for y in (PLATFORM + 1, PLATFORM + 2):c.set(C + dx, y, C + dz, IRON)
        posts[name] = (C + dx, PLATFORM + 3, C + dz)
    # 南边留一个口，接深渊口下来的吊桥
    for dx in (-1, 0, 1):c.set(C + dx, PLATFORM + 1, C + r, AIR)
    for z in range(C + r, C - 1, -1):route('platform', C, PLATFORM, z)
    REPORT['platform'] = {'center': [C, PLATFORM, C], 'radius': r}
    return posts


# ---------------------------------------------------------------- 铁链
def _unit(v):
    n = np.linalg.norm(v)
    return v / n if n > 1e-9 else v


def chain(c, a, b, sag=6.0, pitch=5):
    """一串大链环：沿下垂的曲线每 5 格一环，一环 7 格长、3 格宽，相邻两环相互垂直。周围清出空气。"""
    a, b = np.array(a, float), np.array(b, float)
    ts = np.linspace(0, 1, 600)
    pts = a[None] + (b - a)[None] * ts[:, None]
    pts[:, 1] -= sag * 4 * ts * (1 - ts)
    s = np.concatenate([[0], np.cumsum(np.linalg.norm(np.diff(pts, axis=0), axis=1))])
    links = 0
    for i in range(int(s[-1] // pitch) + 1):
        k = min(int(np.searchsorted(s, i * pitch)), len(pts) - 1)
        p = pts[k]
        t = _unit(pts[min(k + 3, len(pts) - 1)] - pts[max(k - 3, 0)])
        up = np.array([0., 1., 0.])
        n1 = _unit(np.cross(t, up)) if abs(t @ up) < .97 else np.array([1., 0., 0.])
        n2 = _unit(np.cross(t, n1))
        n = n1 if i % 2 == 0 else n2
        for u in np.linspace(-3, 3, 31):
            for v in (-1, 1):
                q = p + u * t + v * n
                c.set(int(round(q[0])), int(round(q[1])), int(round(q[2])), IRON)
        for v in np.linspace(-1, 1, 11):
            for u in (-3, 3):
                q = p + u * t + v * n
                c.set(int(round(q[0])), int(round(q[1])), int(round(q[2])), IRON)
        CLEAR_SPHERES.append((tuple(float(x) for x in p), 4.0))
        links += 1
    return links


# ---------------------------------------------------------------- 镇渊柱
def _toward_center(x, z):
    return (1 if C > x else -1), (1 if C > z else -1)


def tower(c, cx, cz, index):
    """镇渊柱：十七格见方的底座，十三格见方的柱身（转角立柱、中缝云纹、每十四格一道腰线），
    里面一道回旋石阶从深渊口那层一直通到顶；顶上是平台、栏杆、一座黄铜顶的亭子和一只香炉。"""
    sx, sz = _toward_center(cx, cz)
    h = TOWER_HALF
    # 底座
    for dx in range(-h - 2, h + 3):
        for dz in range(-h - 2, h + 3):
            for y in range(TOWER_BASE, TOWER_BASE + 6):
                c.set(cx + dx, y, cz + dz, CARVED if y == TOWER_BASE + 4 else STONE)
            c.set(cx + dx, TOWER_BASE + 6, cz + dz, POLISHED)
            if max(abs(dx), abs(dz)) == h + 2:FOOTINGS.append((cx + dx, TOWER_BASE, cz + dz))
    # 柱身
    for y in range(TOWER_BASE + 7, TOWER_TOP):
        band = (y - TOWER_BASE) % 14 == 0
        for dx in range(-h, h + 1):
            for dz in range(-h, h + 1):
                d = max(abs(dx), abs(dz))
                x, z = cx + dx, cz + dz
                if d >= h - 1:
                    if abs(dx) >= h - 1 and abs(dz) >= h - 1:state = POLISHED
                    elif band:state = CARVED
                    elif d == h and (abs(dx) <= 1 or abs(dz) <= 1):
                        state = CARVED if y % 3 == 0 else B('stone_bricks')
                    else:state = B('stone_bricks') if (x + y * 3 + z) % 9 else B('cracked_stone_bricks')
                    c.set(x, y, z, state)
                elif d <= 1:
                    c.set(x, y, z, B('shroomlight') if y % 8 == 0 and d == 1 and dx == 0 else POLISHED)
                elif y < RIM:c.set(x, y, z, STONE)
                elif y == RIM:c.set(x, y, z, POLISHED)
                else:c.set(x, y, z, AIR)
    # 回旋石阶：沿内圈（离中心 4 格那一圈）一格升一格，从门口那层开始
    door_face = 'north' if sz < 0 else 'south'  # 门开在朝深渊中心的一面，出门就是深渊口外的空地
    ring = []
    for dx in range(-4, 5):ring.append((dx, -4))
    for dz in range(-3, 5):ring.append((4, dz))
    for dx in range(3, -5, -1):ring.append((dx, 4))
    for dz in range(3, -4, -1):ring.append((-4, dz))
    start = ring.index((0, 4) if door_face == 'south' else (0, -4))
    y = RIM + 1
    steps = 0
    i = start
    while y < TOWER_TOP:
        dx, dz = ring[i % len(ring)]
        nx, nz = ring[(i + 1) % len(ring)]
        facing = 'east' if nx > dx else 'west' if nx < dx else 'south' if nz > dz else 'north'
        c.set(cx + dx, y - 1, cz + dz, STONE)
        c.set(cx + dx, y, cz + dz, B('stone_brick_stairs', facing=facing, half='bottom', shape='straight', waterlogged=False))
        for yy in range(y + 1, y + 4):
            if c.get(cx + dx, yy, cz + dz) in (None, AIR):c.set(cx + dx, yy, cz + dz, AIR)
        route(f'tower_{index}_stairs', cx + dx, y, cz + dz)
        y += 1;i += 1;steps += 1
    # 门：三格宽四格高，开在背对中心的一面；门外一块平台往下接地
    dz_door = h if door_face == 'south' else -h
    out = 1 if door_face == 'south' else -1
    for dx in (-1, 0, 1):
        for dzz in (dz_door, dz_door - out):
            for yy in range(RIM + 1, RIM + 5):c.set(cx + dx, yy, cz + dzz, AIR)
            c.set(cx + dx, RIM, cz + dzz, POLISHED)
        for k in range(1, 4):
            c.set(cx + dx, RIM, cz + dz_door + out * k, POLISHED)
            for yy in range(RIM + 1, RIM + 5):c.set(cx + dx, yy, cz + dz_door + out * k, AIR)
            FOOTINGS.append((cx + dx, RIM, cz + dz_door + out * k))
    for k in range(-1, 4):route(f'tower_{index}_door', cx, RIM, cz + dz_door + out * k)
    approach(c, cx - 1, cx + 1, cz + dz_door + out * 3, out, RIM + 1)
    # 顶：平台、栏杆、亭子、香炉；楼梯尽头开口
    top = TOWER_TOP
    c.box(cx - h, top, cz - h, cx + h, top, cz + h, POLISHED)
    for k in range(0, 3):
        ddx, ddz = ring[(i - 1 - k) % len(ring)]
        c.set(cx + ddx, top, cz + ddz, AIR)
    # 楼梯口最后一级做在顶面那层里，踩上来正好和顶面齐平，不用跳
    dx, dz = ring[i % len(ring)]
    nx, nz = ring[(i + 1) % len(ring)]
    facing = 'east' if nx > dx else 'west' if nx < dx else 'south' if nz > dz else 'north'
    c.set(cx + dx, top, cz + dz, B('stone_brick_stairs', facing=facing, half='bottom', shape='straight', waterlogged=False))
    route(f'tower_{index}_stairs', cx + dx, top, cz + dz)
    route(f'tower_{index}_stairs', cx + nx, top, cz + nz)
    openings = []
    if index == GATE_TOWER:openings = [(cx + h, cz + dd) for dd in (-1, 0, 1)] + [(cx + dd, cz + h) for dd in (-1, 0, 1)]
    rail(c, cx - h, cz - h, cx + h, cz + h, top + 1, openings)
    for px in (-3, 3):
        for pz in (-3, 3):
            c.box(cx + px, top + 1, cz + pz, cx + px, top + 4, cz + pz, WOOD)
    for k in range(-3, 4):
        c.set(cx + k, top + 5, cz - 3, BEAM_X);c.set(cx + k, top + 5, cz + 3, BEAM_X)
        c.set(cx - 3, top + 5, cz + k, BEAM_Z);c.set(cx + 3, top + 5, cz + k, BEAM_Z)
    roof(c, cx - 3, top + 6, cz - 3, 7, 7, eave=2)
    # 香炉放在亭子正中：楼梯口按门的朝向落在外圈，转角那几格可能就是楼梯出口
    kx, kz = cx, cz
    c.set(kx, top + 1, kz, BRASS)
    c.set(kx, top + 2, kz, B('campfire', lit=True, signal_fire=False, facing='north', waterlogged=False))
    # 拴链子的铁块：转角朝深渊中心那一侧，伸出三格
    ax, az = cx + sx * (h + 2), cz + sz * (h + 2)
    for ddx in (-1, 0, 1):
        for ddz in (-1, 0, 1):
            for yy in (ANCHOR - 1, ANCHOR, ANCHOR + 1):
                c.set(ax + ddx - sx, yy, az + ddz - sz, IRON)
    return {'center': [cx, cz], 'stair_steps': steps, 'door': [cx, RIM + 1, cz + dz_door + out * 3],
            'anchor': [ax + sx, ANCHOR, az + sz], 'top': top}


# ---------------------------------------------------------------- 吊桥与栈道
WALK_START = math.pi / 2 + .17  # 栈道起点在观景台西头（南偏西约 10°）


def walk_start():
    """栈道第一格中线的位置（深渊口那一层）。"""
    r = float(shaft_radius(RIM, WALK_START)) - 3.2
    return C + r * math.cos(WALK_START), C + r * math.sin(WALK_START)


def stair_bridge(c):
    """深渊口南边的观景台（石台，往下接地），一道铁链扶手的木吊桥从这里斜下到链台南口；栈道从观景台西头下去。"""
    wx, wz = walk_start()
    x0, x1 = min(C - 4, int(math.floor(wx)) - 2), C + 4
    z_rim = min(C + 59, int(math.floor(wz)) - 1)
    z_end = C + 66
    z_plat = C + PLATFORM_R + 1
    # 观景台：两层石头，上面清出空气；外圈往下接地
    for x in range(x0, x1 + 1):
        for z in range(z_rim, z_end + 1):
            c.set(x, RIM - 1, z, B('stone_bricks') if (x + z) % 7 else B('chiseled_stone_bricks'))
            c.set(x, RIM - 2, z, STONE)
            for yy in range(RIM, RIM + 6):
                if c.get(x, yy, z) in (None, MIST):c.set(x, yy, z, AIR)
            if x in (x0, x1) or z in (z_rim, z_end):FOOTINGS.append((x, RIM - 2, z))
    # 栏杆：北边（深渊那边）留桥口和栈道口，南边留进来的口
    walk_gap = [(xx, z_rim) for xx in range(x0, x0 + 4)] + [(x0, zz) for zz in range(z_rim, z_rim + 4)]
    rail(c, x0, z_rim, x1, z_end, RIM, [(C + dd, z_rim) for dd in (-1, 0, 1)] + [(C + dd, z_end) for dd in (-1, 0, 1)] + walk_gap)
    for z in range(z_rim, z_end + 1):route('rim_terrace', C, RIM - 1, z)
    approach(c, C - 1, C + 1, z_end, 1, RIM)
    for x in range(x0 + 1, C + 1):route('rim_terrace', x, RIM - 1, z_rim + 1)
    # 桥面：站立高度从链台面（PLATFORM+1）升到深渊口的石台面（RIM），每格最多升一格；
    # 升的那一格桥面块换成朝南的楼梯，人从低处走上来正好踩上楼梯
    length = z_rim - z_plat
    last = PLATFORM + 1
    for i, z in enumerate(range(z_plat, z_rim)):
        level = PLATFORM + 1 + round((RIM - PLATFORM - 1) * (i + 1) / length)
        rise = level > last
        for dx in (-1, 0, 1):
            x = C + dx
            c.set(x, level - 1, z, B('dark_oak_stairs', facing='south', half='bottom', shape='straight', waterlogged=False) if rise else PLANK)
            if rise:c.set(x, level - 2, z, PLANK)
            for yy in range(level, level + 4):c.set(x, yy, z, AIR)
        for side in (-2, 2):
            c.set(C + side, level - 1, z, IRON if i % 6 == 0 else PLANK)
            c.set(C + side, level, z, B('iron_chain', axis='y', waterlogged=False) if i % 3 else B('dark_oak_fence', north=False, south=False, east=False, west=False, waterlogged=False))
            if i % 9 == 0:c.set(C + side, level + 1, z, B('lantern', hanging=False, waterlogged=False))
        route('stair_bridge', C, level - 1, z)
        last = level
    REPORT['stair_bridge'] = {'from': [C, PLATFORM, z_plat], 'to': [C, RIM - 1, z_rim], 'length': length,
                              'terrace': [x0, RIM - 1, z_rim, x1, z_end]}


def plank_walk(c):
    """沿深渊石壁往下转一圈的木栈道，从深渊口观景台一直到雾底；内沿有栏杆，隔一段挂一盏灯，栏杆下面隔几格一根托木。
    走在雾层那一段就是在云里走。每格最多高半格（台阶用半砖），上下都走得动。

    分三遍画：先沿螺旋线把经过的每一列定一个地板高度（第一次经过时最高，后面不再改），
    再把朝深渊中心那一侧没有栈道的格子当内沿立栏杆，最后挂灯、加托木。螺旋半径跟着石壁起伏，
    一边走一边画的话，前一段的栏杆会落在后一段的走道上。"""
    a0 = WALK_START
    s, h = 0.0, float(RIM)
    floor = {}
    lanes = []
    while h > ARENA + 1:
        a = a0 + s / 47.0
        r = float(shaft_radius(round(h), a)) - 3.2
        surface = math.floor(h * 2) / 2
        ux, uz = math.cos(a), math.sin(a)
        row = []
        # 从内往外：内沿两格是栈道本身，外面一直铺到石壁，不留缝
        for o in (-2.0, -1.0, 0.0, 1.0, 2.0, 3.0):
            x, z = int(round(C + (r + o) * ux)), int(round(C + (r + o) * uz))
            if o > 0 and math.hypot(x - C, z - C) > float(shaft_radius(round(h), math.atan2(z - C, x - C))) + .5:break
            y = int(surface) - 1 if surface.is_integer() else int(math.floor(surface))
            if (x, z) not in floor and c.get(x, y, z) not in (None, AIR, MIST):
                if o > 0:break
                continue
            floor.setdefault((x, z), surface)
            row.append((x, z))
        lanes.append((s, ux, uz, row))
        s += .4
        h = RIM - s / 3.0
    # 观景台也算走道，挨着它的格子不立栏杆
    x0, _, z0, x1, z1 = REPORT['stair_bridge']['terrace']
    walkable = set(floor) | {(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)}

    def inner_edge(x, z):
        d = math.hypot(x - C, z - C)
        return any((x + dx, z + dz) not in walkable for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))
                   if math.hypot(x + dx - C, z + dz - C) < d - .3)

    edges = {xz for xz in floor if inner_edge(*xz)}
    placed = 0
    for (x, z), surface in floor.items():
        if (x, z) in edges or surface.is_integer():
            # 内沿一律铺整块（比走道高半格也没关系，上面立栏杆）
            y = int(math.ceil(surface)) - 1;state = PLANK
        else:
            y = int(math.floor(surface));state = B('dark_oak_slab', type='bottom', waterlogged=False)
        c.set(x, y, z, state);placed += 1
        for yy in range(y + 1, y + 4):
            if c.get(x, yy, z) is None:c.set(x, yy, z, AIR)
        if (x, z) in edges:c.set(x, y + 1, z, B('dark_oak_fence', north=False, south=False, east=False, west=False, waterlogged=False))
    # 灯和托木：沿线每隔一段，挂在离走道中线最近的内沿栏杆上
    last_lamp = last_bracket = -99
    for s_at, ux, uz, row in lanes:
        posts = [xz for xz in row if xz in edges]
        if not posts:continue
        x, z = posts[0]
        y = int(math.ceil(floor[x, z])) - 1
        if s_at - last_lamp > 14 and c.get(x, y + 2, z) in (None, AIR, MIST):
            c.set(x, y + 2, z, B('lantern', hanging=False, waterlogged=False));last_lamp = s_at
        if s_at - last_bracket > 4 and c.get(x, y - 1, z) in (None, AIR, MIST):
            facing = 'east' if ux < -.7 else 'west' if ux > .7 else 'south' if uz < 0 else 'north'
            c.set(x, y - 1, z, B('dark_oak_stairs', facing=facing, half='top', shape='straight', waterlogged=False));last_bracket = s_at
    # 路线：每一步取走道里第一格不是栏杆的
    seen = set()
    for _, _, _, row in lanes:
        lane = next((xz for xz in row if xz not in edges), None)
        if lane is None or lane in seen:continue
        seen.add(lane)
        surface = floor[lane]
        route('plank_walk', lane[0], int(surface) - 1 if surface.is_integer() else int(math.floor(surface)), lane[1])
    REPORT['plank_walk'] = {'start_angle': a0, 'length': round(s), 'blocks': placed, 'railing': len(edges), 'end_angle': a0 + s / 47.0}


# ---------------------------------------------------------------- 瀑布
def waterfall(c, x, y, z, fx, fz):
    """岩壁里一格泉眼（除了朝外那面都用石头封住）、一格横流、整条落水柱一直画到雾层里一只封住的接水池。"""
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if (x + dx, z + dz) != (fx, fz):c.set(x + dx, y, z + dz, B('mossy_cobblestone'))
    c.set(x, y + 1, z, B('stone'))
    c.set(x, y - 1, z, B('stone'));c.set(x, y, z, B('water', level=0))
    c.set(fx, y, fz, B('water', level=1))
    basin = MIST_TOP - 5
    for yy in range(y - 1, basin, -1):c.set(fx, yy, fz, B('water', level=8))
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            c.set(fx + dx, basin - 1, fz + dz, STONE)
            if dx or dz:c.set(fx + dx, basin, fz + dz, STONE)
    c.set(fx, basin, fz, B('water', level=8))
    return [x, y, z, fx, fz, basin]
