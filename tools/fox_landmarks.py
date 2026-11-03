"""神狐聚落的地标：温泉旅馆、神社、老樱树与庭院小品。

坐标约定同 fox_kit：x 向东、z 向南、正面朝北（z=0 一侧），y=0 为地面层。
"""
import random

from fox_kit import *  # noqa: F401,F403

RYOKAN_W, RYOKAN_D = 57, 61


def cherry_leaf(season=0):
    return L('cloudsea_white_cherry_leaf', distance=1, persistent=True, waterlogged=False, season=season)


def cherry_tree(c, x, y, z, height=6, radius=4, seed=0):
    """庭院白樱：树干上部微偏，伞形树冠带几处空洞与垂枝。"""
    rnd = random.Random(seed)
    log = L('cloudsea_white_cherry_log', axis='y')
    lean = rnd.choice([(1, 0), (-1, 0), (0, 1), (0, -1)])
    tx, tz = x, z
    for k in range(height):
        if k == int(height * 0.6):
            tx, tz = x + lean[0], z + lean[1]
            c.set(tx, y + k - 1, tz, L('cloudsea_white_cherry_log', axis='x' if lean[0] else 'z'))
        c.set(tx, y + k, tz, log)
    top = y + height
    for dy in range(-2, 3):
        r = radius - (abs(dy) if dy > 0 else abs(dy) * 0.5)
        for dx in range(-radius, radius + 1):
            for dz in range(-radius, radius + 1):
                d = (dx * dx + dz * dz) ** 0.5
                if d <= r and rnd.random() > 0.15 * (d / max(r, 1)) and c.get(tx + dx, top + dy, tz + dz) is None:
                    c.set(tx + dx, top + dy, tz + dz, cherry_leaf())
    for _ in range(radius * 3):
        dx, dz = rnd.randint(-radius, radius), rnd.randint(-radius, radius)
        if (dx * dx + dz * dz) ** 0.5 <= radius - 0.5:
            c.set(tx + dx, top - 3, tz + dz, cherry_leaf())


def great_cherry(c, x, y, z, seed=5):
    """神社的老樱：两格粗的树干、三根斜出的大枝、宽阔低垂的树冠，树干系注连绳。"""
    rnd = random.Random(seed)
    log = L('cloudsea_white_cherry_log', axis='y')
    for k in range(9):
        for dx in (0, 1):
            for dz in (0, 1):
                c.set(x + dx, y + k, z + dz, log)
    for dx, dz in ((-1, 0), (2, 1), (0, 2), (1, -1)):
        c.set(x + dx, y, z + dz, log)
    # 注连绳绕干一圈
    for dx, dz, facing in ((-1, 0, 'west'), (-1, 1, 'west'), (2, 0, 'east'), (2, 1, 'east'), (0, -1, 'north'), (1, -1, 'north'), (0, 2, 'south'), (1, 2, 'south')):
        if c.get(x + dx, y + 3, z + dz) is None or c.get(x + dx, y + 3, z + dz)['Name'] != log['Name']:
            c.set(x + dx, y + 3, z + dz, L('journey_shimenawa', facing=facing))
    crowns = []
    for ang in (0.3, 2.4, 4.4):
        import math
        bx, bz = x, z
        for k in range(7):
            px = x + round(math.cos(ang) * (k + 1) * 0.9)
            pz = z + round(math.sin(ang) * (k + 1) * 0.9)
            py = y + 8 + k // 2
            c.set(px, py, pz, L('cloudsea_white_cherry_log', axis='x' if abs(math.cos(ang)) > 0.7 else 'z'))
            bx, bz = px, pz
        crowns.append((bx, y + 12, bz))
    crowns.append((x, y + 13, z))
    for (cx, cy, cz) in crowns:
        R = 6
        for dx in range(-R, R + 1):
            for dy in range(-3, 3):
                for dz in range(-R, R + 1):
                    d = (dx * dx + (dy * 1.7) ** 2 + dz * dz) ** 0.5
                    if d <= R and rnd.random() > 0.1 + 0.35 * (d / R) ** 3 and c.get(cx + dx, cy + dy, cz + dz) is None:
                        c.set(cx + dx, cy + dy, cz + dz, cherry_leaf())
        for _ in range(22):
            dx, dz = rnd.randint(-R + 1, R - 1), rnd.randint(-R + 1, R - 1)
            for k in range(rnd.randint(1, 3)):
                if c.get(cx + dx, cy - 4 - k, cz + dz) is None:
                    c.set(cx + dx, cy - 4 - k, cz + dz, cherry_leaf())


def pond(c, x0, z0, x1, z1, y, seed=1):
    """椭圆池塘：水面低于地面一格，碎石底，苔石岸，浮叶点缀。"""
    rnd = random.Random(seed)
    cx, cz = (x0 + x1) / 2, (z0 + z1) / 2
    rx, rz = (x1 - x0) / 2 + 0.5, (z1 - z0) / 2 + 0.5
    for x in range(x0 - 2, x1 + 3):
        for z in range(z0 - 2, z1 + 3):
            d = ((x - cx) / rx) ** 2 + ((z - cz) / rz) ** 2
            if d <= 1:
                c.set(x, y - 2, z, B('gravel'))
                c.set(x, y - 1, z, B('water', level=0))
                c.set(x, y, z, B('lily_pad') if d > 0.5 and rnd.random() < 0.14 else AIR)
            elif d <= 1.45:
                c.set(x, y - 1, z, B('stone'))
                c.set(x, y, z, B('mossy_cobblestone') if rnd.random() < 0.45 else B('moss_block'))


def shrub(c, x, y, z, flowering=False):
    leaf = B('flowering_azalea_leaves' if flowering else 'azalea_leaves', distance=1, persistent=True, waterlogged=False)
    c.box(x - 1, y, z, x + 1, y, z, leaf)
    c.box(x, y, z - 1, x, y, z + 1, leaf)
    c.set(x, y + 1, z, leaf)


def pine(c, x, y, z, seed=0):
    """修剪过的庭院松：深色主干横出几层平展的针叶团。"""
    rnd = random.Random(seed)
    for k in range(5):
        c.set(x, y + k, z, B('dark_oak_log', axis='y'))
    leaf = B('spruce_leaves', distance=1, persistent=True, waterlogged=False)
    for (h, dx, dz, r) in ((3, -2, 0, 1.6), (4, 2, 1, 1.8), (5, 0, -1, 2.2), (6, 0, 0, 1.4)):
        for ax in range(-2, 3):
            for az in range(-2, 3):
                if (ax * ax + az * az) ** 0.5 <= r:
                    c.set(x + dx + ax, y + h, z + dz + az, leaf)
        if dx or dz:
            c.set(x + (1 if dx > 0 else -1 if dx < 0 else 0), y + h - 1, z + (1 if dz > 0 else -1 if dz < 0 else 0), B('dark_oak_log', axis='x' if dx else 'z'))


ROCKS = ('mossy_cobblestone', 'cobblestone', 'andesite', 'stone', 'mossy_cobblestone', 'tuff')


def rock(rnd):
    return B(rnd.choice(ROCKS))


def blob(cx, cz, rx, rz, seed, wobble=0.22):
    """不规则池的边界：返回 (x, z) → 归一化半径，小于 1 在池内。"""
    import math
    rnd = random.Random(seed)
    phases = [rnd.random() * 6.28 for _ in range(3)]

    def f(x, z):
        a = math.atan2(z - cz, x - cx)
        r = 1 + wobble * (math.sin(a * 2 + phases[0]) * 0.6 + math.sin(a * 3 + phases[1]) * 0.3 + math.sin(a * 5 + phases[2]) * 0.2)
        return math.hypot((x - cx) / rx, (z - cz) / rz) / r
    return f


def rock_pool(c, cx, cz, rx, rz, depth=2, seed=1, hot=False, lilies=True):
    """岩石围合的不规则池：池底碎石（温泉为温泉石），岸边一两层高低错落的苔石，浮叶与蕨。返回池内格子。"""
    rnd = random.Random(seed)
    f = blob(cx, cz, rx, rz, seed)
    inside = []
    for x in range(int(cx - rx * 1.5) - 2, int(cx + rx * 1.5) + 3):
        for z in range(int(cz - rz * 1.5) - 2, int(cz + rz * 1.5) + 3):
            d = f(x, z)
            if d < 1:
                inside.append((x, z))
                for k in range(depth):
                    c.set(x, -k, z, B('water', level=0))
                c.set(x, -depth, z, L('journey_hot_spring_stone') if hot else B('gravel'))
                if hot and depth >= 2:
                    # 地热石上铺一层含水石半砖，站在池底时头露出水面；地热石照样加热上面整段水柱
                    slab = 'andesite_slab' if rnd.random() < 0.25 else 'stone_slab'
                    c.set(x, 1 - depth, z, B(slab, type='bottom', waterlogged=True))
                c.set(x, 1, z, AIR)
                if lilies and not hot and d > 0.55 and rnd.random() < 0.12:
                    c.set(x, 1, z, B('lily_pad'))
            elif d < 1.3:
                for k in range(1, depth + 1):
                    c.set(x, -k, z, B('stone'))
                c.set(x, 0, z, rock(rnd))
                h = rnd.random()
                if h < 0.45:
                    c.set(x, 1, z, rock(rnd))
                    if h < 0.12:
                        c.set(x, 2, z, rock(rnd))
                    elif rnd.random() < 0.3:
                        c.set(x, 2, z, B('moss_carpet'))
                elif h < 0.6:
                    c.set(x, 1, z, B(rnd.choice(('fern', 'short_grass', 'moss_carpet'))))
    return inside


def arched_bridge(c, x0, z0, y, length=7, axis='x'):
    """朱漆扶手的木拱桥：两端台阶，中段抬高一格。"""
    profile = [('stairs', 0), ('slab_top', 0), ('slab', 1), ('slab', 1), ('slab', 1), ('slab_top', 0), ('stairs_back', 0)][:length]
    up = 'east' if axis == 'x' else 'south'
    down = 'west' if axis == 'x' else 'north'
    for i, (kind, dy) in enumerate(profile):
        for w in (0, 1):
            x, z = (x0 + i, z0 + w) if axis == 'x' else (x0 + w, z0 + i)
            if kind == 'stairs':
                st = B('spruce_stairs', facing=up, half='bottom', shape='straight', waterlogged=False)
            elif kind == 'stairs_back':
                st = B('spruce_stairs', facing=down, half='bottom', shape='straight', waterlogged=False)
            elif kind == 'slab_top':
                st = B('spruce_slab', type='top', waterlogged=False)
            else:
                st = B('spruce_slab', type='bottom', waterlogged=False)
            c.set(x, y + dy, z, st)
        for w in (-1, 2):
            if 0 < i < length - 1:
                x, z = (x0 + i, z0 + w) if axis == 'x' else (x0 + w, z0 + i)
                sides = {'east': True, 'west': True} if axis == 'x' else {'north': True, 'south': True}
                c.set(x, y + dy + 1, z, lacquer_fence(**sides))


def bamboo_clump(c, x0, z0, x1, z1, y, seed=0, density=0.6):
    rnd = random.Random(seed)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if rnd.random() < density:
                c.set(x, y - 1, z, B('podzol', snowy=False))
                h = rnd.randint(7, 12)
                for k in range(h):
                    leaves = 'large' if k >= h - 2 else 'small' if k == h - 3 else 'none'
                    c.set(x, y + k, z, B('bamboo', age=1, leaves=leaves, stage=0))


def fence_with_pillars(c, pts, y, height=2, every=4, lantern=True):
    """竹垣：两层竹栅栏，每隔几格一根石砖柱，柱顶石灯。"""
    fence_line(c, pts, y, wood='bamboo')
    if height > 1:
        fence_line(c, pts, y + 1, wood='bamboo')
    for i, (x, z) in enumerate(pts):
        if i % every == 0:
            for k in range(height):
                c.set(x, y + k, z, B('stone_bricks'))
            if lantern:
                c.set(x, y + height, z, stone_lantern())


def bench(c, x, y, z, facing, length=3, axis='x'):
    for i in range(length):
        bx, bz = (x + i, z) if axis == 'x' else (x, z + i)
        c.set(bx, y, bz, B('spruce_stairs', facing=facing, half='bottom', shape='straight', waterlogged=False))


# ==================== 温泉旅馆 ====================
def ryokan():
    """此花亭式的温泉旅馆：临街门与前庭，三层本馆，木廊围合的中庭樱树与池，两翼客房与食堂，后部内汤与露天风吕。"""
    c = Canvas()
    W, D = RYOKAN_W - 1, RYOKAN_D - 1
    c.box(0, 0, 0, W, 0, D, B('moss_block'))
    # ---- 门与竹垣 ----
    gx0, gx1 = 24, 32
    for x in (gx0, gx1):
        c.box(x, 1, 1, x, 4, 1, POST)
    c.box(gx0, 5, 1, gx1, 5, 1, BEAM_X)
    gable_roof(c, gx0, gx1, 1, 1, 6, ridge_axis='x', eave=1)
    c.set(gx0 + 1, 4, 0, paper_lantern(True))
    c.set(gx1 - 1, 4, 0, paper_lantern(True))
    fence_pts = [(x, 1) for x in range(0, gx0)] + [(x, 1) for x in range(gx1 + 1, W + 1)]
    fence_line(c, fence_pts, 1, wood='bamboo')
    fence_line(c, fence_pts, 2, wood='bamboo')
    # 前庭：石板参道、石灯、杜鹃、小池
    for z in range(0, 12):
        for x in (27, 28, 29):
            c.set(x, 0, z, PAVING if (x + z) % 3 else B('stone_bricks'))
    for x, z in ((22, 5), (34, 5)):
        c.set(x, 1, z, tall_lantern('lower'))
        c.set(x, 2, z, tall_lantern('upper'))
    for i, (x, z) in enumerate(((19, 7), (37, 8), (16, 4), (40, 4), (5, 8), (51, 8))):
        shrub(c, x, 1, z, flowering=i % 2 == 0)
    pond(c, 7, 3, 13, 7, 0, seed=3)
    pine(c, 46, 1, 4, seed=4)

    # ---- 本馆：三层，二三层朱栏外廊 ----
    mx0, mz0, mx1, mz1 = 12, 12, 44, 24
    levels, ridge = wa_building(c, mx0, mz0, mx1, mz1, 0, floors=3, floor_h=4, roof='hip', eave=2, bays=4)
    # 玄关：正中深檐门廊、暖帘、门灯、长凳
    for x in range(26, 31):
        c.clear(x, 1, mz0, x, 3, mz0)
        c.set(x, 0, mz0, FLOOR)
        c.set(x, 3, mz0, noren('north', 'indigo'))
    for x in (25, 31):
        c.box(x, 1, 9, x, 4, 9, POST)
    c.box(24, 0, 9, 32, 0, 11, PAVING)
    c.box(25, 5, 9, 31, 5, 11, DARK_PLANKS)
    gable_roof(c, 25, 31, 9, 11, 5, ridge_axis='z', eave=1)
    c.set(26, 4, 10, paper_lantern(True))
    c.set(30, 4, 10, paper_lantern(True))
    for x in (24, 32):
        c.set(x, 1, 10, spruce_slab('bottom'))
    # 一层：帐场柜台与休憩处
    c.box(mx0 + 3, 1, mz0 + 5, mx0 + 9, 1, mz0 + 5, B('dark_oak_slab', type='top', waterlogged=False))
    c.set(mx0 + 4, 2, mz0 + 5, B('bell', attachment='floor', facing='north', powered=False))
    c.set(mx0 + 8, 2, mz0 + 5, B('flower_pot'))
    for x in range(mx0 + 18, mx0 + 26, 3):
        low_table(c, x, 1, mz0 + 6)
        cushion(c, x - 1, 1, mz0 + 6)
        cushion(c, x + 1, 1, mz0 + 6)
    # 楼梯：每层一跑，靠西墙两列并排，一层到二层在外侧一列，二层到三层在里侧一列
    for f in range(len(levels) - 1):
        base, rise = levels[f], levels[f + 1] - levels[f]
        x = mx0 + 1 + f
        for k in range(rise):
            z = mz0 + 2 + k
            c.set(x, base + k, z, B('spruce_stairs', facing='south', half='bottom', shape='straight', waterlogged=False))
            c.clear(x, base + k + 1, z, x, base + k + 3, z)
        # 上层地板在梯段上方开洞
        for k in range(rise - 1):
            c.set(x, levels[f + 1] - 1, mz0 + 2 + k, AIR)
        # 梯脚前留一格平地
        c.set(x, base - 1, mz0 + 1, FLOOR)
    for y in levels[1:]:
        balcony(c, mx0, mz0, mx1, mz1, y - 1, sides=('north', 'south'))
        for x in range(mx0 + 2, mx1 - 1, 4):
            c.set(x, y + 3, mz0 - 2, paper_lantern(True))
        for x in range(mx0 + 6, mx1 - 1, 8):
            c.box(x, y, mz0 + 1, x, y + 2, mz1 - 1, B('spruce_planks'))
            door(c, x, y, mz0 + 6, 'east')
            low_table(c, x + 3, y, mz0 + 6)
            cushion(c, x + 2, y, mz0 + 6)
            cushion(c, x + 4, y, mz0 + 6)
            c.set(x + 2, y, mz0 + 2, B('flower_pot'))
    door(c, 28, 1, mz1, 'south')

    # ---- 中庭：碎石地与苔，岩岸池、竹筧瀑口、木拱桥、踏步石、松与樱、竹丛与石灯 ----
    cz0, cz1 = 25, 38
    rnd = random.Random(41)
    for x in range(12, 45):
        for z in range(cz0, cz1 + 1):
            r = rnd.random()
            c.set(x, 0, z, B('moss_block') if r < 0.28 else B('coarse_dirt') if r < 0.33 else B('gravel'))
    rock_pool(c, 20, 31, 6.5, 4.2, depth=2, seed=7)
    # 池西岸叠石，竹筧往池里注水
    for (x, z, h) in ((13, 30, 3), (13, 31, 2), (14, 29, 2), (13, 32, 1), (14, 32, 2)):
        for k in range(h):
            c.set(x, 1 + k, z, rock(rnd))
    c.set(14, 3, 31, L('journey_bamboo_spout', facing='east', waterlogged=False))
    arched_bridge(c, 17, 30, 1, axis='x')
    # 踏步石：北廊门口 → 拱桥 → 浴屋门
    for (x, z) in ((28, 26), (27, 27), (26, 28), (25, 29), (28, 33), (29, 34), (28, 35), (29, 36), (28, 37), (28, 38)):
        c.set(x, 1, z, L('journey_stepping_stone', waterlogged=False))
    for x in range(24, 30):
        for z in (31, 32):
            c.set(x, 0, z, PAVING)
            c.set(x, 1, z, AIR)
    cherry_tree(c, 36, 1, 30, height=7, radius=5, seed=11)
    pine(c, 25, 1, 36, seed=12)
    bamboo_clump(c, 40, 34, 43, 37, 1, seed=13)
    fence_line(c, [(x, 33) for x in range(39, 45)], 1, wood='bamboo')
    c.set(23, 1, 27, tall_lantern('lower'))
    c.set(23, 2, 27, tall_lantern('upper'))
    c.set(33, 1, 36, stone_lantern())
    c.set(41, 1, 27, L('journey_stone_basin', waterlogged=False))
    c.set(42, 1, 27, L('journey_bamboo_spout', facing='west', waterlogged=False))
    for i, (x, z) in enumerate(((31, 26), (38, 28), (16, 37), (33, 33), (42, 30), (13, 27))):
        shrub(c, x, 1, z, flowering=i % 2 == 0)
    for _ in range(40):
        x, z = rnd.randint(12, 44), rnd.randint(cz0, cz1)
        if c.get(x, 1, z) is None and c.get(x, 0, z) in (B('moss_block'), B('gravel'), B('coarse_dirt')):
            c.set(x, 1, z, B(rnd.choice(('fern', 'short_grass', 'moss_carpet', 'oxeye_daisy'))))

    # ---- 两翼：西侧二层客房，东侧食堂 ----
    wa_building(c, 0, 10, 9, 40, 0, floors=2, floor_h=4, roof='hip', eave=1, bays=3)
    for z in range(13, 40, 6):
        door(c, 9, 1, z, 'east')
    wa_building(c, 47, 10, 56, 40, 0, floors=1, floor_h=5, roof='hip', eave=1, bays=3)
    for z in range(13, 38, 3):
        low_table(c, 51, 1, z)
        low_table(c, 52, 1, z)
        cushion(c, 50, 1, z)
        cushion(c, 53, 1, z)
    for z in range(13, 40, 6):
        door(c, 47, 1, z, 'west')

    # ---- 木廊：中庭两侧有顶外廊，连接本馆与后部浴场 ----
    for x0 in (10, 45):
        c.box(x0, 0, cz0 - 1, x0 + 1, 0, cz1 + 1, FLOOR)
        c.clear(x0, 1, cz0 - 1, x0 + 1, 3, cz1 + 1)
        px = x0 + 1 if x0 == 10 else x0
        for z in range(cz0 - 1, cz1 + 2, 3):
            c.box(px, 1, z, px, 3, z, POST)
        c.box(x0, 4, cz0 - 1, x0 + 1, 4, cz1 + 1, DARK_PLANKS)
        for z in range(cz0 - 1, cz1 + 2):
            c.set(x0, 5, z, tile_slab('bottom'))
            c.set(x0 + 1, 5, z, tile_slab('bottom'))
        for z in range(cz0 + 1, cz1, 4):
            c.set(px, 3, z + 1, paper_lantern(True))
        # 朝中庭一侧的低扶手，中段留两格出口
        rail = [(px, z) for z in range(cz0, cz1 + 1) if (z - cz0 + 1) % 3 and z not in (31, 32)]
        fence_line(c, rail, 1, wood='spruce')

    # ---- 内汤：一层浴屋 ----
    bx0, bz0, bx1, bz1 = 14, 40, 42, 46
    wa_building(c, bx0, bz0, bx1, bz1, 0, floors=1, floor_h=5, roof='gable', ridge_axis='x', eave=1, tatami=False, bays=4)
    c.box(bx0 + 1, 0, bz0 + 1, bx1 - 1, 0, bz1 - 1, B('smooth_stone'))
    c.clear(bx0 + 1, 1, bz0 + 1, bx1 - 1, 1, bz1 - 1)
    for x in range(bx0 + 4, bx1 - 3):
        for z in range(bz0 + 2, bz1 - 1):
            c.set(x, 0, z, B('water', level=0))
            c.set(x, -1, z, L('journey_hot_spring_stone'))
    door(c, 28, 1, bz0, 'north')
    c.set(27, 3, bz0, noren('north', 'red'))
    c.set(29, 3, bz0, noren('north', 'indigo'))
    door(c, 28, 1, bz1, 'south')

    # ---- 露天风吕：木平台与长凳，岩岸温泉池（入水石阶、池中立石），竹筧注水，两侧竹垣石柱灯与竹林，朝南看山景 ----
    rz0 = 47
    rnd = random.Random(53)
    for x in range(12, 45):
        for z in range(rz0, D + 1):
            c.set(x, 0, z, B('moss_block') if rnd.random() < 0.6 else B('coarse_dirt'))
            c.set(x, 1, z, AIR)
    # 浴屋南门外的木平台：长凳，几根立柱撑一道小檐，檐下挂纸灯笼
    c.box(14, 0, rz0, 42, 0, rz0 + 2, B('spruce_planks'))
    bench(c, 17, 1, rz0 + 1, 'north', length=4)
    bench(c, 34, 1, rz0 + 1, 'north', length=4)
    for x in range(14, 43):
        c.set(x, 4, rz0 + 2, tile_slab('bottom'))
        c.set(x, 4, rz0 + 1, tile_slab('bottom'))
    for x in (15, 23, 33, 41):
        c.box(x, 1, rz0 + 2, x, 3, rz0 + 2, POST)
        c.set(x + 1, 3, rz0 + 1, paper_lantern(True))
    pool = rock_pool(c, 28, 54, 11.5, 4.2, depth=2, seed=55, hot=True, lilies=False)
    # 平台一侧的入水石阶
    for x in range(25, 32):
        c.set(x, 0, rz0 + 3, B('stone_brick_stairs', facing='north', half='bottom', shape='straight', waterlogged=True))
        c.set(x, -1, rz0 + 3, B('stone_bricks'))
        c.set(x, 1, rz0 + 3, AIR)
    # 池中立石
    for (x, z) in ((22, 55), (34, 53), (30, 57)):
        if (x, z) in pool:
            c.set(x, 0, z, B('mossy_cobblestone'))
            c.set(x, 1, z, B('moss_carpet'))
    # 东岸叠石，竹筧注水
    for (x, z, h) in ((40, 52, 3), (41, 53, 2), (40, 54, 2), (41, 51, 1)):
        for k in range(h):
            c.set(x, 1 + k, z, rock(rnd))
    c.set(39, 3, 52, L('journey_bamboo_spout', facing='west', waterlogged=False))
    # 两侧竹垣与石柱灯，平台两端一丛竹林
    for x in (12, 44):
        fence_with_pillars(c, [(x, z) for z in range(rz0 + 3, D + 1)], 1, height=2, every=4)
    bamboo_clump(c, 12, rz0, 13, rz0 + 2, 1, seed=57, density=0.8)
    bamboo_clump(c, 43, rz0, 44, rz0 + 2, 1, seed=58, density=0.8)
    # 南沿只种低灌木，留出看山的视野
    for x in range(15, 42, 4):
        shrub(c, x, 1, D, flowering=(x // 4) % 2 == 0)
    c.set(16, 1, rz0 + 4, stone_lantern())
    c.set(38, 1, D - 1, tall_lantern('lower'))
    c.set(38, 2, D - 1, tall_lantern('upper'))
    cherry_tree(c, 42, 1, D - 2, height=7, radius=5, seed=21)
    pine(c, 14, 1, D - 2, seed=22)
    ryokan_yards(c, W, D)
    return c


def ryokan_yards(c, W, D):
    """露天风吕两侧的后院与门前东侧：西边客房翼外的枯山水，东边食堂后的菜园、柴棚与水井，门前一处野点。"""
    rnd = random.Random(61)
    # ---- 西后院：枯山水，客房翼南端的檐廊看得到 ----
    gx0, gz0, gx1, gz1 = 1, 43, 10, 52
    for x in range(gx0 - 1, gx1 + 2):
        for z in range(gz0 - 1, gz1 + 2):
            edge = x in (gx0 - 1, gx1 + 1) or z in (gz0 - 1, gz1 + 1)
            c.set(x, 0, z, PAVING if edge else B('gravel'))
    # 三组立石，石脚一圈苔岛
    for (x, z, hs) in ((3, 46, (2, 1, 1)), (8, 49, (1, 1)), (6, 44, (1,))):
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if gx0 <= x + dx <= gx1 and gz0 <= z + dz <= gz1 and rnd.random() < 0.75:
                    c.set(x + dx, 0, z + dz, B('moss_block'))
        for i, h in enumerate(hs):
            rx, rz = (x, z) if i == 0 else (x + (1 if i == 1 else 0), z + (0 if i == 1 else 1))
            for k in range(h):
                c.set(rx, 1 + k, rz, rock(rnd))
        c.set(x - 1, 1, z, B('moss_carpet'))
    # 客房翼外的檐廊
    c.box(0, 0, 41, 9, 0, 41, FLOOR)
    for x in (0, 9):
        c.box(x, 1, 41, x, 3, 41, POST)
    for x in range(0, 10):
        c.set(x, 4, 41, tile_slab('bottom'))
    c.set(4, 3, 41, paper_lantern(True))
    # 南半：踏步石通到看庭的长凳，松、樱、石灯、灌木
    for (x, z) in ((5, 54), (6, 55), (5, 56), (6, 57)):
        c.set(x, 1, z, L('journey_stepping_stone', waterlogged=False))
    bench(c, 4, 1, 58, 'south', length=3)
    pine(c, 2, 1, 55, seed=62)
    cherry_tree(c, 9, 1, 57, height=6, radius=4, seed=63)
    c.set(8, 1, 54, stone_lantern())
    for (x, z) in ((1, 59), (10, 53)):
        shrub(c, x, 1, z, flowering=x > 5)
    # ---- 东后院：食堂后面的菜园、柴棚、水井 ----
    for x in range(47, 55):
        for z in range(43, 49):
            if z == 45:
                c.set(x, 0, z, B('water', level=0))
                continue
            c.set(x, 0, z, B('farmland', moisture=7))
            crop, age = (('carrots', 7), ('potatoes', 7), ('beetroots', 3), ('wheat', 7))[(x - 47) // 2]
            c.set(x, 1, z, B(crop, age=rnd.randint(age - 2, age)))
    for x in range(46, 56):
        for z in (42, 49):
            c.set(x, 0, z, B('coarse_dirt'))
    for z in range(43, 49):
        for x in (46, 55):
            c.set(x, 0, z, B('coarse_dirt'))
    # 柴棚：四柱小檐，背墙码着柴，门口劈柴墩与酒桶
    sx0, sz0, sx1, sz1 = 51, 52, 55, 55
    for x in (sx0, sx1):
        for z in (sz0, sz1):
            c.box(x, 1, z, x, 3, z, POST)
    c.box(sx0, 4, sz0, sx1, 4, sz1, B('spruce_slab', type='bottom', waterlogged=False))
    for x in range(sx0 + 1, sx1):
        c.box(x, 1, sz1, x, 2, sz1, B('oak_log', axis='x'))
        c.set(x, 1, sz1 - 1, B('oak_log', axis='z') if x % 2 else B('birch_log', axis='z'))
    c.set(sx0 + 2, 1, sz0 - 1, B('stripped_oak_log', axis='y'))
    c.set(sx1, 1, sz0 - 1, B('barrel', facing='up', open=False))
    c.set(sx1 - 1, 1, sz0 - 1, B('hay_block', axis='y'))
    # 水井
    wx, wz = 48, 53
    c.box(wx - 1, 1, wz - 1, wx + 1, 1, wz + 1, B('cobblestone'))
    c.set(wx, 1, wz, B('water', level=0))
    c.set(wx, 0, wz, B('water', level=0))
    for dx, dz in ((-1, -1), (1, 1)):
        c.box(wx + dx, 2, wz + dz, wx + dx, 3, wz + dz, fence('spruce'))
    c.box(wx - 1, 4, wz - 1, wx + 1, 4, wz + 1, tile_slab('bottom'))
    c.set(wx + 2, 1, wz, B('barrel', facing='up', open=False))
    # 角落竹丛、松、樱与灌木
    bamboo_clump(c, 55, 41, 56, 43, 1, seed=64, density=0.7)
    pine(c, 54, 1, 58, seed=65)
    cherry_tree(c, 47, 1, 58, height=6, radius=4, seed=66)
    c.set(50, 1, 57, stone_lantern())
    shrub(c, 51, 1, 50, flowering=True)
    for (x, z) in ((46, 51), (46, 53), (46, 55)):
        c.set(x, 1, z, L('journey_stepping_stone', waterlogged=False))
    # ---- 两侧院子靠田地、靠山一边围竹垣 ----
    fence_line(c, [(0, z) for z in range(42, D + 1)] + [(x, D) for x in range(1, 11)], 1, wood='bamboo')
    fence_line(c, [(W, z) for z in range(44, D + 1)] + [(x, D) for x in range(46, W)], 1, wood='bamboo')
    # ---- 门前东侧：野点，红毡与大伞 ----
    for x in range(49, 53):
        for z in range(4, 7):
            c.set(x, 1, z, B('red_carpet'))
    c.box(53, 1, 5, 53, 3, 5, fence('spruce'))
    for dx, dz in ((0, 0), (-1, 0), (1, 0), (0, -1), (0, 1)):
        c.set(53 + dx, 4, 5 + dz, B('red_wool'))
    c.set(53, 5, 5, B('red_wool'))
    c.set(50, 1, 5, B('decorated_pot', facing='north', cracked=False, waterlogged=False))
    c.set(54, 1, 7, stone_lantern())


# ==================== 神社 ====================
def torii(c, x, y, z, axis='x', big=False):
    """鸟居：朱漆柱、半格厚的贯、细额束、朱漆岛木，顶上出挑的灰瓦笠木。
    axis 为横梁方向；普通门洞 3 宽 4 格半高，大鸟居柱距 6、通高约 12。y 为柱脚所在层。"""
    half = 3 if big else 2
    top = 10 if big else 6         # 柱顶
    nuki_h = top - 2 if big else top - 1
    span = half + 1

    def at(s, h):
        return (x + s, y + h, z) if axis == 'x' else (x, y + h, z + s)

    for s in (-half, half):
        for h in range(-1 if big else 0, top + 1):
            c.set(*at(s, h), LACQUER_LOG)
        c.set(*at(s, -1), B('stone_bricks'))
        if big:
            for ds in (-1, 1):  # 柱脚石座
                c.set(*at(s + ds, 0), B('stone_brick_slab', type='bottom', waterlogged=False))
    for s in range(-span, span + 1):
        if abs(s) != half:
            c.set(*at(s, nuki_h), lacquer_slab('top'))
    c.set(*at(0, nuki_h + 1), L('journey_lacquer_fence', north=False, south=False, east=False, west=False, waterlogged=False))
    if big:
        c.set(*at(0, nuki_h + 2 - 1), LACQUER_PLANKS)
        c.set(*at(0, nuki_h + 1), B('dark_oak_planks'))
    for s in range(-span, span + 1):
        c.set(*at(s, top + 1), LACQUER_PLANKS)
    for s in range(-span - 1, span + 2):
        c.set(*at(s, top + 2), tile_slab('bottom'))
    if big:
        for s in range(-span, span + 1):
            c.set(*at(s, top + 2), TILE)
        for s in (-span - 1, span + 1):
            c.set(*at(s, top + 2), tile_slab('bottom'))
            c.set(*at(s + (1 if s > 0 else -1), top + 2), tile_slab('top'))


def haiden(c, x0, z0):
    """拝殿：朱柱白壁，抬高的木地板与正面台阶，前檐挂铃与注连绳，正面朝北（z0 一侧）。"""
    W, D = 12, 10
    x1, z1 = x0 + W, z0 + D
    c.box(x0, 0, z0, x1, 1, z1, B('stone_bricks'))
    c.box(x0 + 1, 2, z0 + 1, x1 - 1, 2, z1 - 1, FLOOR)
    c.clear(x0 + 1, 3, z0 + 1, x1 - 1, 7, z1 - 1)
    for x in range(x0, x1 + 1, 3):
        for z in (z0, z1):
            c.box(x, 2, z, x, 7, z, LACQUER_LOG)
    for z in range(z0, z1 + 1, 3):
        for x in (x0, x1):
            c.box(x, 2, z, x, 7, z, LACQUER_LOG)
    for x in range(x0, x1 + 1):
        for z in (z0, z1):
            if c.get(x, 3, z) is None or c.get(x, 3, z) == AIR:
                if z == z1:
                    c.box(x, 3, z, x, 6, z, PLASTER)
                    c.set(x, 2, z, WAINSCOT)
    for z in range(z0, z1 + 1):
        for x in (x0, x1):
            if c.get(x, 3, z) in (None, AIR):
                c.box(x, 3, z, x, 6, z, PLASTER)
                c.set(x, 2, z, WAINSCOT)
                c.set(x, 4, z, shoji_window('west' if x == x0 else 'east'))
    # 正面：格子门
    for x in range(x0 + 1, x1):
        if (x - x0) % 3:
            c.set(x, 2, z0, lacquer_fence(east=True, west=True))
            c.set(x, 5, z0, shoji_window('north'))
            c.set(x, 6, z0, shoji_window('north'))
    c.box(x0, 7, z0, x1, 7, z1, LACQUER_PLANKS)
    c.box(x0, 8, z0, x1, 8, z1, DARK_PLANKS)
    gable_roof(c, x0, x1, z0, z1, 8, ridge_axis='x', eave=2)
    # 向拝：正面中央伸出的檐与台阶
    cx = (x0 + x1) // 2
    for s in range(-2, 3):
        # 两级石阶接到与殿内地板同高的石台，赛钱箱放在石台上
        c.set(cx + s, 1, z0 - 3, B('stone_brick_stairs', facing='south', half='bottom', shape='straight', waterlogged=False))
        c.set(cx + s, 1, z0 - 2, B('stone_bricks'))
        c.set(cx + s, 2, z0 - 2, B('stone_brick_stairs', facing='south', half='bottom', shape='straight', waterlogged=False))
        c.box(cx + s, 1, z0 - 1, cx + s, 2, z0 - 1, B('stone_bricks'))
    for s in (-3, 3):
        c.box(cx + s, 1, z0 - 3, cx + s, 6, z0 - 3, LACQUER_LOG)
    c.box(cx - 3, 7, z0 - 3, cx + 3, 7, z0 - 1, LACQUER_PLANKS)
    for s in range(-4, 5):
        c.set(cx + s, 8, z0 - 4, tile_stairs('south'))
        c.set(cx + s, 8, z0 - 3, tile_slab('bottom'))
    # 铃、注连绳、赛钱箱
    c.set(cx, 6, z0 - 2, B('bell', attachment='ceiling', facing='north', powered=False))
    for s in range(-2, 3):
        c.set(cx + s, 6, z0 - 1, L('journey_shimenawa', facing='north'))
    # 铃下垂两格鈴緒，最下一格带穗；台阶上正中是赛钱箱
    c.set(cx, 5, z0 - 2, L('journey_suzu_rope', bottom=False))
    c.set(cx, 4, z0 - 2, L('journey_suzu_rope', bottom=True))
    c.set(cx, 3, z0 - 1, L('journey_offering_box', facing='north'))
    c.set(cx, 3, z0 + 6, L('journey_shrine_hokora', facing='north'))
    c.set(cx, 3, z0 + 3, B('red_carpet'))
    return x1, z1


def honden(c, x0, z0):
    """本殿：小而高，朱柱白壁，较陡的屋面，经短廊与拝殿相连。"""
    W, D = 6, 6
    x1, z1 = x0 + W, z0 + D
    c.box(x0, 0, z0, x1, 2, z1, B('stone_bricks'))
    c.box(x0 + 1, 3, z0 + 1, x1 - 1, 3, z1 - 1, FLOOR)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            if not edge:
                continue
            corner = x in (x0, x1) and z in (z0, z1)
            for y in range(3, 8):
                c.set(x, y, z, LACQUER_LOG if corner or (x - x0) % 3 == 0 and z in (z0, z1) else PLASTER)
    c.clear(x0 + 1, 4, z0 + 1, x1 - 1, 7, z1 - 1)
    c.set(x0 + 3, 4, z0 + 3, L('journey_shrine_hokora', facing='north'))
    c.set(x0 + 3, 4, z0, B('dark_oak_door', facing='north', half='lower', hinge='left', open=False, powered=False))
    c.set(x0 + 3, 5, z0, B('dark_oak_door', facing='north', half='upper', hinge='left', open=False, powered=False))
    c.box(x0, 8, z0, x1, 8, z1, LACQUER_PLANKS)
    gable_roof(c, x0, x1, z0, z1, 9, ridge_axis='z', eave=1)
    return x1, z1


def chozuya(c, x0, z0):
    """手水舍：四根朱柱的小亭。地面一层石砖台基，四边各一级朝里的石阶上到中间的石砖台；
    台上一圈含水石砖楼梯围成水槽，楼梯高的一半在外沿当槽壁、低的一半朝里盛水，四角用内角楼梯封住，水不外流；
    中央一块石砖托着石水钵，竹筧注水。"""
    for x in (x0, x0 + 4):
        for z in (z0, z0 + 4):
            c.box(x, 1, z, x, 5, z, LACQUER_LOG)
    c.box(x0, 0, z0, x0 + 4, 0, z0 + 4, B('stone_bricks'))
    cx, cz = x0 + 2, z0 + 2
    c.box(cx - 1, 1, cz - 1, cx + 1, 1, cz + 1, B('stone_bricks'))
    # 外圈一级石阶，朝里上台
    for s in (-1, 0, 1):
        for (x, z, f) in ((cx + s, z0, 'south'), (cx + s, z0 + 4, 'north'), (x0, cz + s, 'east'), (x0 + 4, cz + s, 'west')):
            c.set(x, 1, z, B('stone_brick_stairs', facing=f, half='bottom', shape='straight', waterlogged=False))
    c.set(cx, 2, cz, B('stone_bricks'))
    c.set(cx, 3, cz, L('journey_stone_basin', waterlogged=False))
    # 水槽：楼梯的 facing 是高的一侧，朝外；四角是两面都高的内角（inner_left 为 facing 加逆时针一侧）
    corners = {(-1, -1): ('north', 'inner_left'), (1, -1): ('north', 'inner_right'),
               (-1, 1): ('south', 'inner_right'), (1, 1): ('south', 'inner_left')}
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            if dx == 0 and dz == 0:
                continue
            if dx and dz:
                facing, shape = corners[(dx, dz)]
            else:
                facing, shape = ('west' if dx < 0 else 'east' if dx > 0 else 'north' if dz < 0 else 'south'), 'straight'
            c.set(cx + dx, 2, cz + dz, B('stone_brick_stairs', facing=facing, half='bottom', shape=shape, waterlogged=True))
    c.set(cx, 3, cz + 1, L('journey_bamboo_spout', facing='north', waterlogged=False))
    c.box(x0, 6, z0, x0 + 4, 6, z0 + 4, LACQUER_PLANKS)
    hip_roof(c, x0, x0 + 4, z0, z0 + 4, 6, eave=1)


def ema_racks(c, x0, y, z0, count=3, rows=2, facing='north'):
    """绘马挂拼成的大架子（默认 3 宽 2 高），朝北时左边在西；叠层多少交错，看起来挂得满满的。"""
    assert facing == 'north'
    for r in range(rows):
        for i in range(count):
            c.set(x0 + i, y + r, z0, L('journey_ema_rack', facing=facing, layers=(4, 3, 4, 2, 3, 4)[(i + r * 2) % 6],
                                      left=i > 0, right=i < count - 1, up=r < rows - 1))


def fox_pair(c, x, y, z, axis='x', gap=4, facing_in=True):
    """一对狐像，相向而立，立在石台上。"""
    for s in (-1, 1):
        px, pz = (x + s * gap // 2, z) if axis == 'x' else (x, z + s * gap // 2)
        c.set(px, y, pz, B('stone_bricks'))
        if axis == 'x':
            f = ('east' if s < 0 else 'west') if facing_in else ('west' if s < 0 else 'east')
        else:
            f = ('south' if s < 0 else 'north') if facing_in else ('north' if s < 0 else 'south')
        c.set(px, y + 1, pz, L('journey_fox_statue', facing=f))
