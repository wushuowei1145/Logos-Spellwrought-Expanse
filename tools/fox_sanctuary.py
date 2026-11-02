"""神狐聚落：山顶聚落的设计数据与建筑模板（全部由代码生成）。

输出：
- data/logos_dimension/fox_sanctuary/layout.json  地形高度图、地表类型、入口、模板分块与可替换地块
- data/logos_dimension/structure/fox_sanctuary/*.nbt  模板
- worldgen 结构、结构集与群系标签

设计坐标 (u, v)：u 向东、v 向南，核心区 176×176，高度相对主街标高 P（地表那一层方块的 y）。
入口石阶在西侧，山路由 Java 结构从入口往北绕山下行（FoxSanctuaryStructure）。
"""
from pathlib import Path
import base64
import math
import random
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from blueprint_nbt import write_structure  # noqa: E402
from fox_kit import *  # noqa: E402,F401,F403
import fox_buildings as fb  # noqa: E402
import fox_landmarks as fl  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
D = ROOT / 'src/main/resources/data/logos_dimension'
OUT = D / 'structure/fox_sanctuary'
DATA_VERSION = 4790
S = 176
TILE = 44
TILE_MIN_Y = -16

# 地表类型（与 SanctuaryLayout 一致）
SOFT_TURF, TURF, LAWN, MOSS, COARSE, PATH, GRAVEL, STONE = range(8)

ENTRANCE_V = 88
ENTRANCE_DROP = 14
STAIR_LEN = 22
BAND = 14
STREET_U0, STREET_U1 = 24, 126
SHRINE_U = 132
FIELD_V0 = 114
RYOKAN_U0, RYOKAN_V0 = 94, 101


def street_v(u):
    """主街中线：自入口向东顺地形略作 S 形弯曲。"""
    t = (u - 22) / 104
    return ENTRANCE_V + 5.5 * math.sin(t * math.pi) - 2.5 * math.sin(t * 2 * math.pi)


def smooth(t):
    t = np.clip(t, 0, 1)
    return t * t * (3 - 2 * t)


class Site:
    def __init__(self):
        u, v = np.meshgrid(np.arange(S), np.arange(S), indexing='xy')
        self.u, self.v = u, v
        self.h = np.zeros((S, S))
        self.g = np.full((S, S), LAWN, dtype=np.int8)
        self.c = Canvas()
        self.lots = []
        self.variants = {}
        self.rnd = random.Random(20261102)

    def H(self, u, v):
        return int(round(self.h[v, u]))

    def flat(self, u0, v0, u1, v1, y, ground=None):
        self.h[v0:v1 + 1, u0:u1 + 1] = y
        if ground is not None:
            self.g[v0:v1 + 1, u0:u1 + 1] = ground

    def lot(self, kind, u0, v0, w, d, turns, y=None):
        """登记一块可替换地块：名义占地 w×d（旋转前），旋转后左上角对齐 (u0, v0)。模板原点为建筑坐标 (-2, -2, -2)。"""
        cw = [rotate_pos(x, z, turns) for x, z in ((0, 0), (w - 1, 0), (0, d - 1), (w - 1, d - 1))]
        ax, az = u0 - min(p[0] for p in cw), v0 - min(p[1] for p in cw)
        ox, oz = rotate_pos(-2, -2, turns)
        if y is None:
            y = int(np.round(np.median(self.h[v0:v0 + (d if turns % 2 == 0 else w), u0:u0 + (w if turns % 2 == 0 else d)])))
        fw, fd = (w, d) if turns % 2 == 0 else (d, w)
        self.flat(u0, v0, u0 + fw - 1, v0 + fd - 1, y, COARSE)
        self.lots.append({'kind': kind, 'u': ax + ox, 'y': y - 2, 'v': az + oz, 'turns': turns})
        return y

    def put(self, building, u0, v0, turns=0, y=None):
        """把固定建筑贴进聚落画布（旋转后左上角对齐）。"""
        if y is None:
            y = self.H(u0, v0)
        self.c.paste(building, u0, y, v0, turns)


# ==================== 地形 ====================
def base_terrain(s):
    u, v, h, g = s.u, s.v, s.h, s.g
    # 北侧住宅区向外缓缓抬高 0→3
    h += np.floor(np.clip((62 - v) / 42, 0, 1) * 3.6)
    # 西南田地：向南逐级下降的台地
    field = (u >= 16) & (u <= 90) & (v >= FIELD_V0)
    steps = np.clip((v - FIELD_V0) // 8 + 1, 0, 6)
    h[:] = np.where(field, -steps, h)
    g[:] = np.where(field, COARSE, g)
    # 东北神社山包：顶面 +16
    r = np.sqrt(((u - 144) / 25.0) ** 2 + ((v - 40) / 28.0) ** 2)
    hill = 16 * smooth((1.0 - r) / 0.45)
    h[:] = np.where(hill >= 0.5, np.maximum(h, np.round(hill)), h)  # 只在山包范围内抬高，别把田地台阶抹回 0
    g[:] = np.where(r < 1.0, MOSS, g)
    g[:] = np.where(r < 0.55, LAWN, g)


def edge_band(s):
    """外缘：由内侧高度降到边界 -12，并在内侧堆一道遮挡视线的土埂；入口处切出石阶槽。"""
    u, v, h, g = s.u, s.v, s.h, s.g
    e = np.minimum(np.minimum(u, v), np.minimum(S - 1 - u, S - 1 - v)).astype(float)
    inner = h.copy()
    band = -12 + (inner + 12) * smooth(e / BAND)
    berm = 2.2 * np.exp(-((e - BAND + 1.5) / 2.6) ** 2)
    h[:] = np.where(e < BAND, band, h) + np.where(e < BAND + 4, berm, 0)
    g[:] = np.where(e < BAND - 1, SOFT_TURF, g)
    g[:] = np.where((e >= BAND - 1) & (e < BAND + 3), TURF, g)
    cut = (u < STAIR_LEN + 3) & (np.abs(v - ENTRANCE_V) <= 3)
    stair = -ENTRANCE_DROP + np.clip(u, 0, STAIR_LEN) * ENTRANCE_DROP / STAIR_LEN
    h[:] = np.where(cut, np.floor(stair), h)
    g[:] = np.where(cut, STONE, g)
    near = (u < 6) & (np.abs(v - ENTRANCE_V) <= 9) & ~cut
    h[:] = np.where(near, np.minimum(h, -ENTRANCE_DROP + 1 + np.abs(v - ENTRANCE_V) * 0.4), h)
    g[:] = np.where(near, TURF, g)


# ==================== 入口石阶与大鸟居 ====================
def entrance(s):
    c = s.c
    for u in range(0, STAIR_LEN + 1):
        y = s.H(u, ENTRANCE_V)
        for v in range(ENTRANCE_V - 1, ENTRANCE_V + 2):
            rising = u > 0 and s.H(u - 1, ENTRANCE_V) < y
            c.set(u, y, v, B('stone_brick_stairs', facing='east', half='bottom', shape='straight', waterlogged=False) if rising
                  else (B('mossy_stone_bricks') if (u * 3 + v) % 5 == 0 else B('stone_bricks')))
        # 两侧石垣：石阶槽比两旁低，露出的立面砌石
        for v in (ENTRANCE_V - 2, ENTRANCE_V + 2, ENTRANCE_V - 3, ENTRANCE_V + 3):
            c.set(u, y, v, B('mossy_cobblestone'))
        for v in (ENTRANCE_V - 4, ENTRANCE_V + 4):
            top = s.H(u, v)
            for yy in range(y, top + 1):
                c.set(u, yy, v, B('mossy_stone_bricks') if (yy + u) % 3 else B('cobblestone'))
        if u % 6 == 3:
            for v in (ENTRANCE_V - 2, ENTRANCE_V + 2):
                c.set(u, y + 1, v, stone_lantern())
    # 石阶顶端的大鸟居：穿过它，聚落才在眼前展开
    fl.torii(c, STAIR_LEN, 1, ENTRANCE_V, axis='z', big=True)
    fl.fox_pair(c, STAIR_LEN + 3, 0, ENTRANCE_V, axis='z', gap=8)


# ==================== 主街与商铺 ====================
def street(s):
    c = s.c
    for u in range(STREET_U0 - 2, STREET_U1 + 9):
        vc = street_v(u)
        for v in range(int(math.floor(vc - 3)), int(math.ceil(vc + 3)) + 1):
            dv = v - vc
            if abs(dv) <= 2.2:
                st = PAVING if (u + v * 3) % 7 else B('stone_bricks')
            else:
                st = B('stone_bricks') if (u + v) % 4 else B('mossy_stone_bricks')
            s.h[v, u] = 0
            c.set(u, 0, v, st)
        # 北侧小水渠：低于路面的石砌浅沟
        vw = int(math.floor(vc - 4))
        s.h[vw, u] = 0
        c.set(u, -1, vw, B('stone_bricks'))
        c.set(u, 0, vw, B('water', level=0))
    lots_north, lots_south = [], []
    # 北侧：入口到神社前，留出食摊广场与几条巷口
    u = STREET_U0 + 2
    while u + fb.SHOP_W <= STREET_U1 - 2:
        if 64 <= u <= 82:
            u = 84
            continue
        lots_north.append(u)
        u += fb.SHOP_W + (3 if len(lots_north) % 3 == 0 else 1)
    u = STREET_U0 + 2
    while u + fb.SHOP_W <= RYOKAN_U0 - 2:
        lots_south.append(u)
        u += fb.SHOP_W + (3 if len(lots_south) % 3 == 0 else 1)
    for u in lots_north:
        vc = street_v(u + fb.SHOP_W / 2)
        front = int(math.floor(vc - 5))
        s.lot('shop', u, front - fb.SHOP_D + 1, fb.SHOP_W, fb.SHOP_D, 2, y=0)
        # 门前跨水渠的石板
        for x in range(u + 3, u + 6):
            c.set(x, 0, int(math.floor(street_v(x) - 4)), B('stone_brick_slab', type='top', waterlogged=False))
        for x in range(u, u + fb.SHOP_W):
            for v in range(front + 1, int(math.floor(street_v(x) - 4))):
                c.set(x, 0, v, PAVING)
                s.h[v, x] = 0
    for u in lots_south:
        vc = street_v(u + fb.SHOP_W / 2)
        front = int(math.ceil(vc + 4))
        s.lot('shop', u, front, fb.SHOP_W, fb.SHOP_D, 0, y=0)
        for x in range(u, u + fb.SHOP_W):
            for v in range(int(math.ceil(street_v(x) + 3)) + 1, front):
                c.set(x, 0, v, PAVING)
                s.h[v, x] = 0
    s.variants['shop'] = sorted(fb.SHOP_VARIANTS)

    def lanes(lots):
        out = []
        for a, b in zip(lots, lots[1:]):
            if b - (a + fb.SHOP_W) >= 2:
                out.append(a + fb.SHOP_W + (b - a - fb.SHOP_W) // 2 - 1)
        return out
    s.lanes_n, s.lanes_s = lanes(lots_north), lanes(lots_south)
    return lots_north, lots_south


def food_plaza(s):
    """主街北侧的食摊广场：几处屋台、水井、长凳与一棵樱。"""
    c = s.c
    u0, u1 = 64, 82
    v1 = int(math.floor(street_v(73) - 5))
    v0 = v1 - 14
    s.flat(u0, v0, u1, v1, 0, STONE)
    for u in range(u0, u1 + 1):
        for v in range(v0, v1 + 1):
            c.set(u, 0, v, B('gravel') if (u * 7 + v * 3) % 5 == 0 else B('coarse_dirt') if (u + v) % 3 == 0 else PAVING)
    for i, (u, v, turns) in enumerate(((u0 + 1, v0 + 1, 2), (u0 + 7, v0 + 1, 2), (u0 + 13, v0 + 1, 2), (u0 + 1, v0 + 8, 1))):
        s.lot('stall', u, v, 5, 5, turns, y=0)
    s.variants['stall'] = sorted(fb.STALL_VARIANTS)
    # 水井
    wu, wv = u1 - 3, v1 - 4
    c.box(wu - 1, 0, wv - 1, wu + 1, 1, wv + 1, B('cobblestone'))
    c.set(wu, 0, wv, B('water', level=0))
    c.set(wu, 1, wv, AIR)
    c.set(wu, -1, wv, B('water', level=0))
    for du, dv in ((-1, -1), (1, 1)):
        c.box(wu + du, 2, wv + dv, wu + du, 3, wv + dv, fence('spruce'))
    c.box(wu - 1, 4, wv - 1, wu + 1, 4, wv + 1, tile_slab('bottom'))
    c.set(wu, 3, wv, B('chain', axis='y', waterlogged=False))
    for u in (u0 + 3, u0 + 9, u0 + 15):
        c.set(u, 1, v1 - 2, spruce_slab('bottom'))
        c.set(u + 1, 1, v1 - 2, spruce_slab('bottom'))
    fl.cherry_tree(c, u1 - 2, 1, v0 + 2, height=6, radius=4, seed=31)
    for u in (u0, u1):
        c.set(u, 1, v1, tall_lantern('lower'))
        c.set(u, 2, v1, tall_lantern('upper'))


# ==================== 神社参道与境内 ====================
def shrine(s):
    c = s.c
    su = SHRINE_U
    v_bottom, v_top = int(math.floor(street_v(su) - 5)), 46
    # 街尾小广场
    s.flat(STREET_U1 - 2, v_bottom - 2, su + 6, v_bottom + 8, 0, STONE)
    for u in range(STREET_U1 - 2, su + 7):
        for v in range(v_bottom - 2, v_bottom + 9):
            c.set(u, 0, v, PAVING if (u + v) % 5 else B('stone_bricks'))
    # 石阶：自广场向北升到山包顶面，两侧千本鸟居
    n = v_bottom - 2 - v_top
    for i in range(n + 1):
        v = v_bottom - 2 - i
        y = int(math.floor(i * 16 / n))
        prev = int(math.floor((i - 1) * 16 / n)) if i else 0
        for u in range(su - 3, su + 4):
            s.h[v, u] = y
            s.g[v, u] = STONE
        for u in range(su - 1, su + 2):
            c.set(u, y, v, B('stone_brick_stairs', facing='north', half='bottom', shape='straight', waterlogged=False) if y > prev
                  else B('stone_bricks'))
        for u in (su - 2, su + 2, su - 3, su + 3):
            c.set(u, y, v, B('mossy_cobblestone'))
        if i % 2 == 0 and 2 <= i <= n - 2:
            fl.torii(c, su, y + 1, v, axis='x')
    # 街尾巨大鸟居
    fl.torii(c, su, 1, v_bottom + 3, axis='x', big=True)
    fl.fox_pair(c, su, 0, v_bottom + 6, axis='x', gap=10)
    for u in (su - 6, su + 6):
        c.set(u, 1, v_bottom + 5, tall_lantern('lower'))
        c.set(u, 2, v_bottom + 5, tall_lantern('upper'))
    # 境内：平整空地，参道转向东边的拝殿
    s.flat(su - 4, 26, 160, v_top, 16, LAWN)
    for u in range(su - 4, 161):
        for v in range(26, v_top + 1):
            c.set(u, 16, v, B('gravel') if (u + v) % 6 else B('coarse_dirt'))
    for u in range(su, 150):
        for v in (v_top - 2, v_top - 1, v_top):
            c.set(u, 16, v, PAVING if (u + v) % 4 else B('stone_bricks'))
    for v in range(40, v_top):
        for u in (147, 148, 149):
            c.set(u, 16, v, PAVING)
    for u in range(su + 4, 147, 5):
        for v in (v_top - 3, v_top + 1):
            c.set(u, 17, v, tall_lantern('lower'))
            c.set(u, 18, v, tall_lantern('upper'))
    fl.fox_pair(c, 148, 16, v_top - 4, axis='x', gap=6)
    # 拝殿朝南，本殿在其后
    hb = Canvas()
    fl.haiden(hb, 0, 4)
    s.put(hb, 142, 26, turns=2, y=16)
    hn = Canvas()
    fl.honden(hn, 0, 0)
    s.put(hn, 145, 16, turns=2, y=16)
    s.flat(140, 12, 158, 40, 16, LAWN)
    ch = Canvas()
    fl.chozuya(ch, 0, 0)
    s.put(ch, su - 3, 36, turns=1, y=16)
    fl.ema_racks(c, 151, 17, 44, count=3, facing='north')
    fl.great_cherry(c, 156, 17, 32, seed=9)
    c.set(152, 17, 36, L('journey_shrine_hokora', facing='west'))


# ==================== 温泉旅馆 ====================
def ryokan(s):
    s.flat(RYOKAN_U0, RYOKAN_V0, RYOKAN_U0 + fl.RYOKAN_W - 1, RYOKAN_V0 + fl.RYOKAN_D - 1, 0, MOSS)
    s.put(fl.ryokan(), RYOKAN_U0, RYOKAN_V0, 0, y=0)
    # 门前接主街的石板
    for u in range(RYOKAN_U0 + 26, RYOKAN_U0 + 31):
        for v in range(int(math.ceil(street_v(u) + 3)), RYOKAN_V0):
            s.c.set(u, 0, v, PAVING)
            s.h[v, u] = 0


# ==================== 住宅 ====================
def residences(s):
    """住宅自主街向北逐渐疏开：三排，越往外间距越大，屋前屋后有菜园与柴棚。"""
    rows = [(52, 0, 2), (34, 2, 5), (18, 3, 9)]
    placed = []
    for v0, jitter, gap in rows:
        u = 18 + s.rnd.randint(0, 3)
        while u + 13 < 126:
            vv = v0 + s.rnd.randint(-jitter, jitter)
            if u > 116 and vv < 40:
                break
            s.lot('house', u, vv, 13, 13, 2)
            placed.append((u, vv))
            u += 13 + gap + s.rnd.randint(0, gap)
    s.variants['house'] = sorted(fb.HOUSE_VARIANTS)
    # 巷道：从主街北侧巷口向北的碎石小路
    for lane_u in s.lanes_n + [72]:
        for v in range(14, int(street_v(lane_u) - 5)):
            for du in (0, 1):
                if s.g[v, lane_u + du] != COARSE:
                    s.g[v, lane_u + du] = GRAVEL
    for v in (48, 30):
        for u in range(16, 124):
            if s.g[v, u] == LAWN:
                s.g[v, u] = PATH


# ==================== 田地 ====================
def fields(s):
    """西南台地：高处旱田，低处小块水田；台地边缘砌石作田埂，地头有灌溉沟与农具棚。"""
    c = s.c
    rnd = random.Random(7)
    crops = [('wheat', 7), ('carrots', 7), ('potatoes', 7), ('beetroots', 3)]
    for step in range(1, 7):
        v0 = FIELD_V0 + (step - 1) * 8
        v1 = min(v0 + 7, S - BAND - 2)
        if v0 > v1:
            continue
        y = -step
        paddy = step >= 4
        for u in range(17, 90):
            for v in range(v0, v1 + 1):
                edge = v == v1 or u in (17, 89) or (u - 17) % 18 == 0
                if edge:
                    c.set(u, y, v, B('mossy_cobblestone') if v == v1 else B('grass_block', snowy=False))
                    continue
                if paddy:
                    c.set(u, y - 1, v, B('mud'))
                    c.set(u, y, v, B('water', level=0) if (u + v) % 9 else B('water', level=0))
                    c.set(u, y + 1, v, AIR)
                    if (u + v) % 3:
                        c.set(u, y, v, L('journey_rice', age=rnd.choice([5, 6, 7])))
                else:
                    if (u - 17) % 9 == 4:
                        c.set(u, y, v, B('water', level=0))
                        continue
                    c.set(u, y, v, B('farmland', moisture=7))
                    name, age = crops[((u - 17) // 18 + step) % len(crops)]
                    c.set(u, y + 1, v, B(name, age=rnd.randint(max(0, age - 2), age)))
    # 地头的灌溉沟：沿田地西缘自上而下
    for v in range(FIELD_V0, S - BAND - 1):
        y = s.H(16, v)
        c.set(16, y, v, B('water', level=0))
        c.set(16, y - 1, v, B('stone'))
    # 农具棚与稻草人
    shed = Canvas()
    fb.tool_shed(shed)
    s.put(shed, 92 - 7, FIELD_V0 - 1, 0, y=0)
    c.set(52, -1, FIELD_V0 + 11, B('spruce_fence', north=False, south=False, east=False, west=False, waterlogged=False))
    c.set(52, 0, FIELD_V0 + 11, B('hay_block', axis='y'))
    c.set(52, 1, FIELD_V0 + 11, B('carved_pumpkin', facing='north'))
    # 田间小径接回主街：南侧商铺之间的巷口
    for lane in s.lanes_s:
        for u in (lane, lane + 1):
            for v in range(int(street_v(u) + 4), FIELD_V0):
                s.g[v, u] = PATH


# ==================== 外缘植被 ====================
def rim_planting(s):
    """土埂上的樱与竹：从山路上看不到聚落，进了大鸟居才豁然开朗。"""
    c = s.c
    rnd = random.Random(11)
    for i in range(70):
        side = rnd.randint(0, 3)
        t = rnd.randint(12, S - 13)
        e = rnd.randint(BAND - 3, BAND + 1)
        u, v = [(t, e), (S - 1 - e, t), (t, S - 1 - e), (e, t)][side]
        if abs(v - ENTRANCE_V) < 10 and u < 30:
            continue
        if any(abs(u - pu) < 6 and abs(v - pv) < 6 for pu, pv in getattr(s, '_trees', [])):
            continue
        s._trees = getattr(s, '_trees', []) + [(u, v)]
        y = s.H(u, v) + 1
        if rnd.random() < 0.35:
            for du in range(-2, 3):
                for dv in range(-2, 3):
                    if rnd.random() < 0.55:
                        hgt = rnd.randint(8, 13)
                        for k in range(hgt):
                            leaves = 'large' if k >= hgt - 2 else 'small' if k == hgt - 3 else 'none'
                            c.set(u + du, y + k, v + dv, B('bamboo', age=1, leaves=leaves, stage=0))
                        c.set(u + du, y - 1, v + dv, B('podzol', snowy=False))
        else:
            fl.cherry_tree(c, u, y, v, height=rnd.randint(5, 7), radius=rnd.randint(3, 5), seed=i)


# ==================== 输出 ====================
def write_templates(s):
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob('*.nbt'):
        old.unlink()
    tiles = []
    ys = [k[1] for k in s.c.cells]
    top = max(ys)
    height = top - TILE_MIN_Y + 1
    for tu in range(0, S, TILE):
        for tv in range(0, S, TILE):
            cells = {}
            for (x, y, z), st in s.c.cells.items():
                if tu <= x < tu + TILE and tv <= z < tv + TILE and y >= TILE_MIN_Y:
                    cells[(x - tu, y - TILE_MIN_Y, z - tv)] = st
            if not cells:
                continue
            name = f'core_{tu // TILE}_{tv // TILE}'
            write_structure(OUT / f'{name}.nbt', [TILE, height, TILE], cells, data_version=DATA_VERSION)
            tiles.append({'template': name, 'u': tu, 'v': tv})
    for table, (w, d) in ((fb.SHOP_VARIANTS, (fb.SHOP_W, fb.SHOP_D)), (fb.HOUSE_VARIANTS, (13, 13)), (fb.STALL_VARIANTS, (5, 5))):
        for name, make in table.items():
            b = make()
            size = [w + 4, 24, d + 4]
            cells = {}
            for (x, y, z), st in b.cells.items():
                k = (x + 2, y + 2, z + 2)
                if all(0 <= k[i] < size[i] for i in range(3)):
                    cells[k] = st
            write_structure(OUT / f'{name}.nbt', size, cells, data_version=DATA_VERSION)
    return tiles


def layout(s, tiles):
    obj = {
        'size': S,
        'heights': base64.b64encode(np.round(s.h).astype(np.int8).tobytes()).decode(),
        'ground': base64.b64encode(s.g.astype(np.int8).tobytes()).decode(),
        'entrance': {'u': -1, 'v': ENTRANCE_V, 'y': -ENTRANCE_DROP},
        'tile_min_y': TILE_MIN_Y,
        'tiles': tiles,
        'lots': s.lots,
        'variants': s.variants,
    }
    write_json(D / 'fox_sanctuary/layout.json', obj)


def worldgen():
    write_json(D / 'worldgen/structure/fox_sanctuary.json', {
        'type': f'{NS}:fox_sanctuary',
        'biomes': f'#{NS}:has_structure/fox_sanctuary',
        'step': 'surface_structures',
        'spawn_overrides': {'monster': {'bounding_box': 'piece', 'spawns': []}},
        'terrain_adaptation': 'none',
    })
    # 间距与 FoxSanctuaryStructure 中的常量一致：候选很密，由结构自己挑出每片樱野里最居中的一处
    write_json(D / 'worldgen/structure_set/fox_sanctuary.json', {
        'structures': [{'structure': f'{NS}:fox_sanctuary', 'weight': 1}],
        'placement': {'type': 'minecraft:random_spread', 'salt': 20261102, 'spacing': 8, 'separation': 4, 'spread_type': 'linear'},
    })
    write_json(D / 'tags/worldgen/biome/has_structure/fox_sanctuary.json', {'values': [f'{NS}:cloudsea_white_cherry']})


WILD = {
    'trees_cloudsea_white_cherry': 'fox_wild_trees',
    'fallen_tree_cloudsea_white_cherry': 'fox_wild_fallen_tree',
    'forest_rock_outcrop': 'fox_wild_rock_outcrop',
    'cherry_pebbles': 'fox_wild_pebbles',
    'cloudsea_white_cherry_floor': 'fox_wild_floor',
    'cherry_white_flowers': 'fox_wild_flowers',
}


def wild_features():
    """樱野的树、倒木、岩块与碎石只长在自然草皮上：聚落的屋顶、街道与草坪不会冒出随机的树。
    复制原放置规则加一条地表判定，只替换樱野群系里的引用（原规则仍供其他群系使用）。"""
    import json as _json
    pf = D / 'worldgen/placed_feature'
    ground = {'type': 'minecraft:block_predicate_filter', 'predicate': {
        'type': 'minecraft:matching_blocks', 'offset': [0, -1, 0],
        'blocks': [f'{NS}:cloudsea_white_cherry_ground', 'minecraft:dirt']}}
    for src, dst in WILD.items():
        obj = _json.loads((pf / f'{src}.json').read_text(encoding='utf-8'))
        placement = [m for m in obj['placement'] if m.get('type') != 'minecraft:biome']
        # 结构整形发生在特征阶段，那时只更新最终高度图；*_WG 还停在整形前，树会落进填出的土里
        for m in placement:
            if m.get('heightmap') in ('OCEAN_FLOOR_WG', 'WORLD_SURFACE_WG'):
                m['heightmap'] = m['heightmap'][:-3]
        obj['placement'] = placement + [ground, {'type': 'minecraft:biome'}]
        write_json(pf / f'{dst}.json', obj)
    biome = D / 'worldgen/biome/cloudsea_white_cherry.json'
    obj = _json.loads(biome.read_text(encoding='utf-8'))
    obj['features'] = [[f'{NS}:{WILD[f.split(":")[1]]}' if f.split(':')[1] in WILD else f for f in step] for step in obj['features']]
    # 樱野常有一层薄雾：近处清楚，百余格外渐渐隐去，上山时看不清山顶
    attrs = obj.setdefault('attributes', {})
    attrs['minecraft:visual/fog_start_distance'] = 24.0
    attrs['minecraft:visual/fog_end_distance'] = 240.0
    write_json(biome, obj)


def compose():
    s = Site()
    base_terrain(s)
    street(s)
    food_plaza(s)
    shrine(s)
    ryokan(s)
    residences(s)
    fields(s)
    edge_band(s)
    entrance(s)
    rim_planting(s)
    return s


def main():
    s = compose()
    tiles = write_templates(s)
    layout(s, tiles)
    worldgen()
    wild_features()
    print('layout', int(s.h.min()), int(s.h.max()), 'cells', len(s.c.cells), 'lots', len(s.lots), 'tiles', len(tiles))
    return s


if __name__ == '__main__':
    main()
