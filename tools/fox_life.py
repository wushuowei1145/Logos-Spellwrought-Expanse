"""神狐聚落的生活气息：街上的灯笼串、巷口杂物、街尾空地的茶庭、住宅区的树与花坛、草坪上的草与野花。

都写进聚落画布（设计坐标 u, v；y 相对主街标高 P，地表那层方块为 H(u, v)）。
只放在没有地块、街道或其他构件的空格上，避免与模板互相覆盖。
"""
import math
import random

from fox_kit import *  # noqa: F401,F403
import fox_landmarks as fl

POTTED = ('potted_azalea_bush', 'potted_flowering_azalea_bush', 'potted_fern', 'potted_bamboo', 'potted_cherry_sapling',
          'potted_spruce_sapling', 'potted_red_tulip', 'potted_lily_of_the_valley')
LAWN_PLANTS = (('short_grass', 60), ('fern', 12), ('oxeye_daisy', 6), ('azure_bluet', 5), ('cornflower', 4),
               ('lily_of_the_valley', 3), ('pink_tulip', 2))


def _free(s, u, v, y=1):
    c = s.c
    return all(c.get(u, s.H(u, v) + k, v) is None for k in range(y, y + 3))


def _pick(rnd, table):
    total = sum(w for _, w in table)
    r = rnd.random() * total
    for name, w in table:
        r -= w
        if r <= 0:
            return name
    return table[0][0]


def lantern_strings(s, street_v, u0=30, u1=124, step=12):
    """横跨主街的纸灯笼串：一道铁链挂在两侧屋檐之上，链下每隔一格吊一盏灯笼。"""
    c = s.c
    for u in range(u0, u1, step):
        if 62 <= u <= 84:  # 食摊广场那段另有布置
            continue
        vc = street_v(u)
        va, vb = int(math.floor(vc - 6)), int(math.ceil(vc + 5))
        for v in range(va, vb + 1):
            c.set(u, 7, v, B('iron_chain', axis='z', waterlogged=False))
        for v in range(va + 2, vb - 1, 2):
            c.set(u, 6, v, paper_lantern(True))


def alley_props(s, street_v, rnd):
    """巷口两侧：立灯、酒桶、盆栽、干草，巷子中间留出通道。"""
    c = s.c
    for lanes, north in ((s.lanes_n, True), (s.lanes_s, False)):
        for lane in lanes:
            for du in (-1, 2):
                u = lane + du
                for dv in range(0, 4):
                    v = int(math.floor(street_v(u) - 5)) - dv if north else int(math.ceil(street_v(u) + 4)) + dv
                    if not (0 <= u < 176 and 0 <= v < 176) or not _free(s, u, v):
                        continue
                    y = s.H(u, v) + 1
                    r = rnd.random()
                    if dv == 0:
                        c.set(u, y, v, tall_lantern('lower'))
                        c.set(u, y + 1, v, tall_lantern('upper'))
                    elif r < 0.35:
                        c.set(u, y, v, B('barrel', facing='up', open=False))
                        if rnd.random() < 0.4:
                            c.set(u, y + 1, v, B(rnd.choice(POTTED)))
                    elif r < 0.6:
                        c.set(u, y, v, B(rnd.choice(POTTED)))
                    elif r < 0.75:
                        c.set(u, y, v, B('hay_block', axis='y'))


def tea_garden(s, u0, v0, u1, v1, rnd):
    """街尾空地上的茶庭：池与拱桥、四柱亭、踏步石、樱与松、竹丛、石灯与花灌木。"""
    c = s.c
    for u in range(u0, u1 + 1):
        for v in range(v0, v1 + 1):
            s.h[v, u] = 0
            s.g[v, u] = 3  # MOSS
            r = rnd.random()
            c.set(u, 0, v, B('moss_block') if r < 0.45 else B('grass_block', snowy=False) if r < 0.8 else B('gravel'))
    w, d = u1 - u0, v1 - v0
    pc_u, pc_v = u0 + w * 0.45, v0 + d * 0.5
    pool = fl.rock_pool(c, pc_u, pc_v, w * 0.22, d * 0.18, depth=2, seed=91)
    # 拱桥横跨池的窄处
    fl.arched_bridge(c, int(pc_u) - 3, int(pc_v) - 1, 1, axis='x')
    # 四柱亭：在池东侧
    au, av = int(u0 + w * 0.78), int(v0 + d * 0.3)
    c.box(au - 2, 0, av - 2, au + 2, 0, av + 2, B('spruce_planks'))
    for du in (-2, 2):
        for dv in (-2, 2):
            c.box(au + du, 1, av + dv, au + du, 3, av + dv, POST)
    c.box(au - 2, 4, av - 2, au + 2, 4, av + 2, DARK_PLANKS)
    hip_roof(c, au - 2, au + 2, av - 2, av + 2, 4, eave=1)
    fl.bench(c, au - 1, 1, av + 1, 'south', length=3)
    c.set(au, 1, av - 1, B('dark_oak_trapdoor', facing='north', half='top', open=False, powered=False, waterlogged=False))
    c.set(au, 3, av, paper_lantern(True))
    # 踏步石小径：西口 → 桥 → 亭
    path = []
    for t in range(0, 40):
        f = t / 39
        pu = u0 + 1 + (au - 3 - (u0 + 1)) * f
        pv = pc_v + 4 * math.sin(f * math.pi * 1.5)
        path.append((int(round(pu)), int(round(pv))))
    for i, (u, v) in enumerate(path):
        if i % 2 == 0 and (u, v) not in pool and c.get(u, 1, v) is None and c.get(u, 0, v) not in (B('water', level=0),):
            c.set(u, 1, v, L('journey_stepping_stone', waterlogged=False))
    fl.cherry_tree(c, int(u0 + w * 0.2), 1, int(v0 + d * 0.2), height=7, radius=5, seed=92)
    fl.cherry_tree(c, int(u0 + w * 0.6), 1, int(v0 + d * 0.85), height=6, radius=4, seed=93)
    fl.pine(c, int(u0 + w * 0.15), 1, int(v0 + d * 0.8), seed=94)
    fl.bamboo_clump(c, u1 - 2, v0, u1, v1, 1, seed=95, density=0.55)
    for (fu, fv) in ((0.3, 0.3), (0.62, 0.15), (0.7, 0.65)):
        u, v = int(u0 + w * fu), int(v0 + d * fv)
        if c.get(u, 1, v) is None:
            c.set(u, 1, v, tall_lantern('lower'))
            c.set(u, 2, v, tall_lantern('upper'))
    for _ in range(14):
        u, v = rnd.randint(u0 + 1, u1 - 3), rnd.randint(v0 + 1, v1 - 1)
        if all(c.get(u + du, 1, v + dv) is None for du in (-1, 0, 1) for dv in (-1, 0, 1)) and (u, v) not in pool:
            fl.shrub(c, u, 1, v, flowering=rnd.random() < 0.5)
    c.set(u0 + 2, 1, int(pc_v) + 3, L('journey_stone_basin', waterlogged=False))
    c.set(u0 + 3, 1, int(pc_v) + 3, L('journey_bamboo_spout', facing='west', waterlogged=False))


def greenery(s, area, rnd, lawn=2):
    """在空草坪上点树、花坛、竹丛与石灯：网格加抖动，每处要求周围一圈都是空草坪。"""
    c = s.c
    u0, v0, u1, v1 = area
    for gu in range(u0, u1, 7):
        for gv in range(v0, v1, 7):
            u, v = gu + rnd.randint(0, 3), gv + rnd.randint(0, 3)
            ok = all(0 <= u + du < 176 and 0 <= v + dv < 176 and s.g[v + dv, u + du] == lawn and _free(s, u + du, v + dv)
                     for du in range(-2, 3) for dv in range(-2, 3))
            if not ok:
                continue
            y = s.H(u, v) + 1
            r = rnd.random()
            if r < 0.3:
                fl.cherry_tree(c, u, y, v, height=rnd.randint(5, 7), radius=rnd.randint(3, 4), seed=rnd.randint(0, 9999))
            elif r < 0.45:
                fl.pine(c, u, y, v, seed=rnd.randint(0, 9999))
            elif r < 0.6:
                # 花坛：一圈苔石围着花
                for du in (-1, 0, 1):
                    for dv in (-1, 0, 1):
                        if du or dv:
                            c.set(u + du, y - 1, v + dv, B('mossy_cobblestone'))
                        c.set(u + du, y, v + dv, B(rnd.choice(('poppy', 'oxeye_daisy', 'allium', 'azure_bluet', 'cornflower', 'pink_tulip'))))
                c.set(u, y - 1, v, B('grass_block', snowy=False))
            elif r < 0.72:
                fl.bamboo_clump(c, u - 1, v - 1, u + 1, v + 1, y, seed=rnd.randint(0, 9999), density=0.6)
            elif r < 0.84:
                fl.shrub(c, u, y, v, flowering=rnd.random() < 0.6)
                c.set(u + 2, y, v, stone_lantern())
            else:
                # 井与水桶
                c.box(u - 1, y, v - 1, u + 1, y, v + 1, B('cobblestone'))
                c.set(u, y, v, B('water', level=0))
                c.set(u, y - 1, v, B('water', level=0))
                for du, dv in ((-1, -1), (1, 1)):
                    c.box(u + du, y + 1, v + dv, u + du, y + 2, v + dv, fence('spruce'))
                c.box(u - 1, y + 3, v - 1, u + 1, y + 3, v + 1, tile_slab('bottom'))
                c.set(u + 2, y, v, B('barrel', facing='up', open=False))


def lawn_cover(s, rnd, lawn=2):
    """草坪上铺一层稀疏的草与野花，免得一片灰绿光秃。"""
    c = s.c
    for v in range(176):
        for u in range(176):
            if s.g[v, u] != lawn or rnd.random() > 0.2:
                continue
            y = s.H(u, v)
            if c.get(u, y, v) is not None or c.get(u, y + 1, v) is not None:
                continue
            c.set(u, y + 1, v, B(_pick(rnd, LAWN_PLANTS)))
