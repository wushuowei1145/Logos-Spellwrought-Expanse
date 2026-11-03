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
    """这一格上方几格是空的；y=1 时地面那层也不能被铺过（石板、碎石、小路都算占用）。"""
    c = s.c
    ground = c.get(u, s.H(u, v), v)
    if y == 1 and ground is not None and ground['Name'] not in ('minecraft:grass_block', 'minecraft:moss_block'):
        return False
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
    """横跨主街的纸灯笼串：两侧路沿各立一根朱漆栅栏柱，柱顶之间拉一道铁链，链下隔一格吊一盏灯笼。
    两侧商铺是随机变体，有两种只有一层、墙面退在檐后两格，链子未必够得到墙，所以两端都立柱，不靠房子。"""
    c = s.c
    for u in range(u0, u1, step):
        if 62 <= u <= 84:  # 食摊广场那段另有布置
            continue
        vc = street_v(u)
        va, vb = int(math.floor(vc - 3)), int(math.ceil(vc + 3))  # 北侧水渠内沿、南侧铺面外沿
        for v in (va, vb):
            for y in range(s.H(u, v) + 1, 8):
                c.set(u, y, v, lacquer_fence())
        for v in range(va + 1, vb):
            c.set(u, 7, v, B('iron_chain', axis='z', waterlogged=False))
        for v in range(va + 1, vb, 2):
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
    # 拱桥东西横跨，两端落在岸上
    fl.bridge_over(c, pool, 'x', int(pc_v), 1)
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


def end_plot(s, kind, box, rnd):
    """街尾空地：'tea' 茶庭、'pagoda' 五重塔、'bamboo' 竹林小径。"""
    {'tea': tea_garden, 'pagoda': pagoda_garden, 'bamboo': bamboo_grove}[kind](s, *box, rnd)


def _ground(s, u0, v0, u1, v1, rnd, mix=(('moss_block', 0.45), ('grass_block', 0.35), ('gravel', 0.2))):
    c = s.c
    for u in range(u0, u1 + 1):
        for v in range(v0, v1 + 1):
            s.h[v, u] = 0
            s.g[v, u] = 3  # MOSS
            r, acc = rnd.random(), 0
            for name, w in mix:
                acc += w
                if r < acc:
                    c.set(u, 0, v, B(name, snowy=False) if name == 'grass_block' else B(name))
                    break


def pagoda_garden(s, u0, v0, u1, v1, rnd):
    """五重塔的院子：碎石院心、石板参道从北口进来，四角石灯、两株樱、松与灌木，低矮竹垣围起。"""
    c = s.c
    _ground(s, u0, v0, u1, v1, rnd)
    w, d = u1 - u0, v1 - v0
    cx, cz = u0 + w // 2, v0 + d // 2 + 2
    for u in range(cx - 7, cx + 8):
        for v in range(cz - 7, cz + 8):
            c.set(u, 0, v, B('gravel'))
    for v in range(v0, cz - 6):
        for u in (cx - 1, cx, cx + 1):
            c.set(u, 0, v, PAVING if (u + v) % 4 else B('stone_bricks'))
    fl.pagoda(c, cx, 0, cz)
    for du, dv in ((-7, -7), (7, -7), (-7, 7), (7, 7)):
        c.set(cx + du, 1, cz + dv, tall_lantern('lower'))
        c.set(cx + du, 2, cz + dv, tall_lantern('upper'))
    fl.cherry_tree(c, u0 + 3, 1, v1 - 4, height=7, radius=4, seed=401)
    fl.cherry_tree(c, u1 - 3, 1, v0 + 4, height=6, radius=4, seed=402)
    fl.pine(c, u0 + 3, 1, v0 + 4, seed=403)
    for (u, v) in ((u1 - 2, v1 - 3), (u0 + 2, cz), (u1 - 2, cz + 2)):
        fl.shrub(c, u, 1, v, flowering=rnd.random() < 0.5)
    c.set(cx - 3, 1, v0 + 2, L('journey_stone_basin', waterlogged=False))
    c.set(cx - 4, 1, v0 + 2, L('journey_bamboo_spout', facing='east', waterlogged=False))
    fl.bench(c, cx + 3, 1, v0 + 2, 'north', length=2)
    pts = [(u, v1) for u in range(u0, u1 + 1)] + [(u0, v) for v in range(v0 + 2, v1)] + [(u1, v) for v in range(v0 + 2, v1)]
    fence_line(c, pts, 1, wood='bamboo')


def bamboo_grove(s, u0, v0, u1, v1, rnd):
    """竹林小径：密竹之间一条蜿蜒的碎石小径，中段一小块空地，石龛、石灯与长凳。"""
    c = s.c
    _ground(s, u0, v0, u1, v1, rnd, mix=(('podzol', 0.55), ('moss_block', 0.3), ('coarse_dirt', 0.15)))
    w, d = u1 - u0, v1 - v0
    path = set()
    for t in range(0, d + 1):
        v = v0 + t
        uc = u0 + w / 2 + (w / 3.2) * math.sin(t / d * math.pi * 2.2)
        for du in (-1, 0, 1):
            path.add((int(round(uc + du)), v))
    mid_v = v0 + d // 2
    mid_u = int(round(u0 + w / 2 + (w / 3.2) * math.sin(0.5 * math.pi * 2.2)))
    clearing = {(u, v) for u in range(mid_u - 3, mid_u + 4) for v in range(mid_v - 2, mid_v + 3)}
    for (u, v) in path | clearing:
        if u0 <= u <= u1:
            c.set(u, 0, v, B('gravel'))
    for (u, v) in sorted(path):
        if (u + v) % 5 == 0 and u0 <= u <= u1:
            c.set(u, 1, v, L('journey_stepping_stone', waterlogged=False))
    open_ = path | clearing
    for u in range(u0, u1 + 1):
        for v in range(v0, v1 + 1):
            if (u, v) in open_ or any((u + du, v + dv) in open_ for du in (-1, 1) for dv in (-1, 1)):
                continue
            if rnd.random() < 0.55:
                h = rnd.randint(8, 14)
                c.set(u, 0, v, B('podzol', snowy=False))
                for k in range(h):
                    leaves = 'large' if k >= h - 2 else 'small' if k == h - 3 else 'none'
                    c.set(u, 1 + k, v, B('bamboo', age=1, leaves=leaves, stage=0))
    c.set(mid_u, 1, mid_v - 2, L('journey_shrine_hokora', facing='south'))
    for du in (-2, 2):
        c.set(mid_u + du, 1, mid_v - 2, stone_lantern())
    fl.bench(c, mid_u - 1, 1, mid_v + 2, 'south', length=3)


def greenery(s, area, rnd, lawn=2):
    """在空草坪上留出 5×5 的园景地块：网格加抖动，每处要求周围一圈都是空草坪。
    地块里放什么（樱、松、花坛、竹丛、灌木与石灯、水井或只有草）由聚落生成时现场挑，见 GARDEN_VARIANTS。"""
    u0, v0, u1, v1 = area
    for gu in range(u0, u1, 7):
        for gv in range(v0, v1, 7):
            u, v = gu + rnd.randint(0, 3), gv + rnd.randint(0, 3)
            ok = all(0 <= u + du < 176 and 0 <= v + dv < 176 and s.g[v + dv, u + du] == lawn and _free(s, u + du, v + dv)
                     for du in range(-2, 3) for dv in range(-2, 3))
            box = (u - 2, v - 2, u + 2, v + 2)
            if not ok or any(box[0] <= o[2] + 1 and o[0] - 1 <= box[2] and box[1] <= o[3] + 1 and o[1] - 1 <= box[3] for o in s.boxes):
                continue
            s.lot('garden', u - 2, v - 2, 5, 5, 0, y=s.H(u, v), ground=None)
    # 重复的名字是权重：樱与松多一些
    s.variants['garden'] = sorted(GARDEN_VARIANTS) + ['garden_cherry_a', 'garden_cherry_b', 'garden_pine']


def _garden(build):
    def make():
        c = Canvas()
        build(c, random.Random(0))
        return c
    return make


def _cherry_a(c, rnd):
    fl.cherry_tree(c, 2, 1, 2, height=7, radius=3, seed=511)


def _cherry_b(c, rnd):
    fl.cherry_tree(c, 2, 1, 2, height=5, radius=3, seed=512)


def _pine(c, rnd):
    fl.pine(c, 2, 1, 2, seed=513)


def _bed(c, rnd):
    """花坛：一圈苔石围着花。"""
    for du in (-1, 0, 1):
        for dv in (-1, 0, 1):
            if du or dv:
                c.set(2 + du, 0, 2 + dv, B('mossy_cobblestone'))
            c.set(2 + du, 1, 2 + dv, B(('poppy', 'oxeye_daisy', 'allium', 'azure_bluet', 'cornflower', 'pink_tulip')[(du + 1) * 3 + dv + 1 & 5]))
    c.set(2, 0, 2, B('grass_block', snowy=False))


def _bamboo(c, rnd):
    fl.bamboo_clump(c, 1, 1, 3, 3, 1, seed=514, density=0.6)


def _shrub(c, rnd):
    fl.shrub(c, 2, 1, 2, flowering=True)
    c.set(4, 1, 2, stone_lantern())


def _well(c, rnd):
    """井与水桶。"""
    c.box(1, 1, 1, 3, 1, 3, B('cobblestone'))
    c.set(2, 1, 2, B('water', level=0))
    c.set(2, 0, 2, B('water', level=0))
    for du, dv in ((-1, -1), (1, 1)):
        c.box(2 + du, 2, 2 + dv, 2 + du, 3, 2 + dv, fence('spruce'))
    c.box(1, 4, 1, 3, 4, 3, tile_slab('bottom'))
    c.set(4, 1, 2, B('barrel', facing='up', open=False))


def _grass(c, rnd):
    """留空：只有几丛草与野花。"""
    for (x, z, name) in ((1, 1, 'short_grass'), (3, 2, 'fern'), (2, 3, 'oxeye_daisy'), (0, 3, 'short_grass')):
        c.set(x, 1, z, B(name))


#: 园景地块的变体；地块 5×5，模板外扩 2 格，树冠可以伸出地块
GARDEN_VARIANTS = {
    'garden_cherry_a': _garden(_cherry_a), 'garden_cherry_b': _garden(_cherry_b), 'garden_pine': _garden(_pine),
    'garden_bed': _garden(_bed), 'garden_bamboo': _garden(_bamboo), 'garden_shrub': _garden(_shrub),
    'garden_well': _garden(_well), 'garden_grass': _garden(_grass),
}


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
