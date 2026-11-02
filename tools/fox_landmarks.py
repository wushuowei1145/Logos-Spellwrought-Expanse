"""神狐聚落的地标：温泉旅馆、神社、老樱树与庭院小品。

坐标约定同 fox_kit：x 向东、z 向南、正面朝北（z=0 一侧），y=0 为地面层。
"""
import random

from fox_kit import *  # noqa: F401,F403

RYOKAN_W, RYOKAN_D = 57, 53


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
    gable_roof(c, 25, 31, 9, 11, 6, ridge_axis='z', eave=1)
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
    # 楼梯：靠西墙的直梯
    for k in range(8):
        c.set(mx0 + 1, 1 + k, mz0 + 2 + k, B('spruce_stairs', facing='south', half='bottom', shape='straight', waterlogged=False))
        c.clear(mx0 + 1, 2 + k, mz0 + 2 + k, mx0 + 1, 4 + k, mz0 + 2 + k)
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

    # ---- 中庭：樱树、池、踏步石、石灯、手水钵 ----
    cz0, cz1 = 25, 38
    c.box(12, 0, cz0, 44, 0, cz1, B('moss_block'))
    pond(c, 16, 29, 24, 35, 0, seed=7)
    cherry_tree(c, 35, 1, 31, height=6, radius=5, seed=11)
    for (x, z) in ((28, 26), (29, 28), (28, 30), (29, 32), (28, 34), (29, 36), (28, 38)):
        c.set(x, 1, z, L('journey_stepping_stone'))
    c.set(40, 1, 36, stone_lantern())
    c.set(22, 1, 27, stone_lantern())
    c.set(41, 1, 27, L('journey_stone_basin', waterlogged=False))
    c.set(42, 1, 27, L('journey_bamboo_spout', facing='west'))

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

    # ---- 露天风吕：岩石围合，竹垣，面朝南面山景 ----
    rz0 = 47
    c.box(12, 0, rz0, 44, 0, D, B('moss_block'))
    for x in range(16, 41):
        for z in range(rz0, D):
            if x in (16, 40) or z in (rz0, D - 1):
                c.set(x, 0, z, B('mossy_cobblestone') if (x + z) % 3 else L('journey_hot_spring_stone'))
                if (x * 7 + z) % 4 == 0:
                    c.set(x, 1, z, B('mossy_cobblestone'))
            else:
                c.set(x, 0, z, B('water', level=0))
                c.set(x, -1, z, L('journey_hot_spring_stone'))
    for x in (12, 44):
        pts = [(x, z) for z in range(rz0, D + 1)]
        fence_line(c, pts, 1, wood='bamboo')
        fence_line(c, pts, 2, wood='bamboo')
    c.set(41, 1, rz0 + 1, L('journey_bamboo_spout', facing='west'))
    for x, z in ((14, rz0 + 1), (42, rz0 + 3)):
        c.set(x, 1, z, stone_lantern())
    cherry_tree(c, 48, 1, 47, height=5, radius=3, seed=21)
    return c


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
        c.set(cx + s, 1, z0 - 1, B('stone_brick_stairs', facing='south', half='bottom', shape='straight', waterlogged=False))
        c.set(cx + s, 0, z0 - 2, B('stone_brick_stairs', facing='south', half='bottom', shape='straight', waterlogged=False))
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
    c.set(cx, 2, z0 - 1, B('dark_oak_planks'))
    c.set(cx - 1, 2, z0 - 1, B('dark_oak_stairs', facing='east', half='bottom', shape='straight', waterlogged=False))
    c.set(cx + 1, 2, z0 - 1, B('dark_oak_stairs', facing='west', half='bottom', shape='straight', waterlogged=False))
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
    """手水舍：四根朱柱的小亭，中央石水钵与竹出水口。"""
    for x in (x0, x0 + 4):
        for z in (z0, z0 + 3):
            c.box(x, 1, z, x, 4, z, LACQUER_LOG)
    c.box(x0, 0, z0, x0 + 4, 0, z0 + 3, B('stone_bricks'))
    c.box(x0, 5, z0, x0 + 4, 5, z0 + 3, LACQUER_PLANKS)
    hip_roof(c, x0, x0 + 4, z0, z0 + 3, 6, eave=1)
    for x in range(x0 + 1, x0 + 4):
        c.set(x, 1, z0 + 1, L('journey_stone_basin', waterlogged=False))
        c.set(x, 1, z0 + 2, L('journey_stone_basin', waterlogged=False))
    c.set(x0 + 2, 2, z0 + 3, L('journey_bamboo_spout', facing='north'))


def ema_rack(c, x0, z0, length=5):
    """绘马挂：深木框与小瓦顶，两层浅色木牌（桦木活板门）。"""
    for x in (x0, x0 + length - 1):
        c.box(x, 1, z0, x, 3, z0, B('dark_oak_log', axis='y'))
    for x in range(x0, x0 + length):
        c.set(x, 4, z0, tile_slab('bottom'))
        if x0 < x < x0 + length - 1:
            c.set(x, 3, z0, B('dark_oak_slab', type='top', waterlogged=False))
            c.set(x, 2, z0, B('birch_trapdoor', facing='south', half='top', open=True, powered=False, waterlogged=False))
            c.set(x, 1, z0, B('birch_trapdoor', facing='south', half='top', open=True, powered=False, waterlogged=False))


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
