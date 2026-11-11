"""浮岛的细部：岛腹倒垂的悬岩、岛与岛之间漂着的浮石、草地上的山花和散石。

在 build_abyss 之后、clearance 之前调用：路线、铁链、瀑布、镇渊柱和殿阁都已经画好，
这里只往空处加东西，并且离这些都留出几格（keep_out）。全部离线画进模板，游戏里没有填块器。
"""
import math
import random

from taixuan_kit import B, AIR
import taixuan_terrain as terrain
import taixuan_abyss as abyss
import taixuan_landscape as landscape

ROCK = {'minecraft:' + n for n in ('stone', 'andesite', 'calcite', 'diorite', 'tuff', 'dripstone_block', 'mossy_cobblestone')}
STRATA = ('stone', 'andesite', 'stone', 'tuff', 'andesite', 'calcite', 'stone', 'diorite')


class KeepOut:
    """不能放细部的地方：路线上方的人行空间、铁链周围、吊链和瀑布所在的整列、镇渊柱、殿阁包围盒。"""

    def __init__(self, landmarks, waterfalls, hanging):
        self.routes = {}
        for points in abyss.ROUTES.values():
            for x, y, z in points:
                self.routes.setdefault((x // 8, z // 8), []).append((x, y, z))
        self.spheres = list(abyss.CLEAR_SPHERES)
        self.columns = [(x, z, 6) for x, _, z in hanging] + [(fx, fz, 4) for _, _, _, fx, fz, _ in waterfalls]
        self.columns += [(x, z, abyss.TOWER_HALF + 5) for x, z in abyss.TOWERS]
        self.boxes = [(l['origin'][0] - 3, l['origin'][1] - 2, l['origin'][2] - 3,
                       l['origin'][0] + l['size'][0] + 3, l['origin'][1] + l['size'][1] + 3, l['origin'][2] + l['size'][2] + 3) for l in landmarks]

    def blocked(self, x, y, z):
        for bx in (x // 8 - 1, x // 8, x // 8 + 1):
            for bz in (z // 8 - 1, z // 8, z // 8 + 1):
                for px, py, pz in self.routes.get((bx, bz), ()):
                    if abs(px - x) <= 3 and abs(pz - z) <= 3 and py - 3 <= y <= py + 6:return True
        for (cx, cy, cz), r in self.spheres:
            if (x - cx) ** 2 + (y - cy) ** 2 + (z - cz) ** 2 < (r + 3) ** 2:return True
        for cx, cz, r in self.columns:
            if max(abs(x - cx), abs(z - cz)) <= r:return True
        for x0, y0, z0, x1, y1, z1 in self.boxes:
            if x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1:return True
        return False


def _empty(c, x, y, z):
    s = c.get(x, y, z)
    return s is None or s['Name'] == 'minecraft:air'


def _stratum(y, x, z, seed):
    warp = (terrain.noise(x / 17.0 + seed, z / 17.0) - .5) * 6
    return B(STRATA[int(math.floor((y + warp) / 3.0)) % len(STRATA)])


def spires(c, keep, seed=7):
    """岛腹倒垂的悬岩：在岛底朝下的面上挑一些点，往下长一根收尖的石笋，石头按层理上色，尖上是滴水石和钟乳石。"""
    rng = random.Random(seed)
    # 岛底朝下的面：岩石，下面一格是空的，高度在深渊口以上
    bottoms = [(x, y, z) for (x, y, z), s in c.cells.items()
               if s['Name'] in ROCK and y > abyss.RIM + 30 and _empty(c, x, y - 1, z)]
    rng.shuffle(bottoms)
    taken = []
    made = []
    for x, y, z in bottoms:
        if len(made) >= 34:break
        if any((x - tx) ** 2 + (z - tz) ** 2 < 11 ** 2 for tx, tz in taken):continue
        # 底面再往上 3 格还是岩石，说明是岛腹而不是薄薄的崖檐
        if not all(c.get(x, y + k, z) and c.get(x, y + k, z)['Name'] in ROCK for k in (1, 2, 3)):continue
        length = rng.randint(9, 24)
        r0 = rng.uniform(2.2, 4.2)
        lean = (rng.uniform(-.18, .18), rng.uniform(-.18, .18))
        cells = []
        ok = True
        for d in range(length):
            t = d / length
            r = r0 * (1 - t) ** .85 + .35
            cx, cz = x + lean[0] * d, z + lean[1] * d
            yy = y - 1 - d
            if yy <= abyss.RIM + 14:ok = False;break
            for dx in range(-5, 6):
                for dz in range(-5, 6):
                    wob = (terrain.noise((x + dx) / 2.1 + d * .3, (z + dz) / 2.1) - .5) * .9
                    if (dx + x - cx) ** 2 + (dz + z - cz) ** 2 > (r + wob) ** 2:continue
                    p = (x + dx, yy, z + dz)
                    if keep.blocked(*p):ok = False;break
                    cells.append((p, t))
                if not ok:break
            if not ok:break
        if not ok or len(cells) < 12:continue
        for (px, py, pz), t in cells:
            if not _empty(c, px, py, pz):continue
            speck = terrain.noise(px / 1.6 + py * .4, pz / 1.6)
            if t > .72:state = B('dripstone_block') if speck > .35 else B('tuff')
            elif speck > .83:state = B('mossy_cobblestone')
            else:state = _stratum(py, px, pz, seed)
            c.set(px, py, pz, state)
        # 尖上挂一两格钟乳石（下面必须是空的）
        tip = min(cells, key=lambda q: q[0][1])[0]
        tx, ty, tz = tip
        if _empty(c, tx, ty - 1, tz) and _empty(c, tx, ty - 2, tz) and not keep.blocked(tx, ty - 2, tz):
            c.set(tx, ty, tz, B('dripstone_block'))
            c.set(tx, ty - 1, tz, B('pointed_dripstone', thickness='frustum', vertical_direction='down', waterlogged=False))
            c.set(tx, ty - 2, tz, B('pointed_dripstone', thickness='tip', vertical_direction='down', waterlogged=False))
        taken.append((x, z))
        made.append([x, y, z, length])
    return made


def _rock_blob(c, x, y, z, rx, ry, rz, rng, grass):
    """一块浮石：上面是扁圆的顶，下面收成尖；顶上有草的话铺草皮和泥土。"""
    placed = []
    depth = ry + rng.randint(2, 5)
    for dy in range(-depth, ry + 1):
        if dy >= 0:k = math.sqrt(max(0.0, 1 - (dy / (ry + .5)) ** 2))
        else:k = (1 + dy / (depth + 1)) ** 1.3
        for dx in range(-rx - 1, rx + 2):
            for dz in range(-rz - 1, rz + 2):
                wob = (terrain.noise((x + dx) / 2.3, (z + dz) / 2.3 + dy * .2) - .5) * .8
                if (dx / (rx * k + .3 + wob)) ** 2 + (dz / (rz * k + .3 + wob)) ** 2 > 1:continue
                placed.append((x + dx, y + dy, z + dz))
    top = {}
    for px, py, pz in placed:top[px, pz] = max(py, top.get((px, pz), -999))
    for px, py, pz in placed:
        if grass and py == top[px, pz] and py >= y:state = B('grass_block', snowy=False)
        elif grass and py == top[px, pz] - 1 and py >= y - 1:state = B('dirt')
        else:
            speck = terrain.noise(px / 1.7 + py * .3, pz / 1.7)
            state = B('mossy_cobblestone') if speck > .82 else B('tuff') if speck < .15 else _stratum(py, px, pz, 3)
        c.set(px, py, pz, state)
    return top


def floating_rocks(c, keep, count=18, seed=11):
    """岛与岛之间漂着的浮石：离别的方块至少 7 格，整块都不碰路线、铁链和殿阁；一半顶上长草、蕨或一株矮松。"""
    rng = random.Random(seed)
    made = []
    tries = 0
    while len(made) < count and tries < 4000:
        tries += 1
        x, z = rng.randint(14, 237), rng.randint(14, 237)
        # 只在空地上空：空地外面是峰林的石柱，浮石会嵌进去
        if math.hypot(x - abyss.C, z - abyss.C) > float(abyss.basin_radius(math.atan2(z - abyss.C, x - abyss.C))) - 12:continue
        y = rng.randint(abyss.RIM + 34, 282)
        rx, rz, ry = rng.randint(2, 5), rng.randint(2, 5), rng.randint(1, 3)
        reach = max(rx, rz) + 7
        if any(keep.blocked(x + dx, y + dy, z + dz) for dx in (-reach, 0, reach) for dz in (-reach, 0, reach) for dy in (-ry - 8, 0, ry + 4)):continue
        # 离已有方块（岛、桥、殿阁、别的浮石）至少 7 格：粗查一个立方体的格点
        near = False
        for dx in range(-reach, reach + 1, 2):
            for dz in range(-reach, reach + 1, 2):
                for dy in range(-ry - 9, ry + 6, 2):
                    if not _empty(c, x + dx, y + dy, z + dz):near = True;break
                if near:break
            if near:break
        if near:continue
        # 不悬在岛的正上方（下面 40 格以内是岛或殿阁的话，看起来像掉下来的碎石）
        if any(not _empty(c, x + dx, y + dy, z + dz) for dx in range(-rx, rx + 1, 2) for dz in range(-rz, rz + 1, 2)
               for dy in range(-ry - 9, -40, -3)):continue
        grass = rng.random() < .55
        top = _rock_blob(c, x, y, z, rx, ry, rz, rng, grass)
        if grass:
            for (px, pz), py in sorted(top.items()):
                if rng.random() < .22 and _empty(c, px, py + 1, pz):
                    c.set(px, py + 1, pz, B(rng.choice(('short_grass', 'short_grass', 'fern', 'azure_bluet', 'lily_of_the_valley'))))
        made.append([x, y, z, rx, ry, rz])
    return made


FLOWERS = ('short_grass',) * 8 + ('fern',) * 3 + ('azure_bluet', 'oxeye_daisy', 'lily_of_the_valley', 'cornflower', 'white_tulip')


def meadow(c, islands, keep, seed=5):
    """草地上的山花和散石：只在仍是草方块、上面空着的地方；路线和殿阁周围不放。"""
    rng = random.Random(seed)
    flowers = stones = 0
    for spec in islands:
        for (x, z), y in sorted(spec['surface'].items()):
            s = c.get(x, y, z)
            if not s or s['Name'] != 'minecraft:grass_block' or not _empty(c, x, y + 1, z) or not _empty(c, x, y + 2, z):continue
            roll = rng.random()
            if roll > .16 or keep.blocked(x, y + 1, z):continue
            if roll < .006:
                # 一两格的散石，苔石或安山岩
                c.set(x, y + 1, z, B('mossy_cobblestone' if rng.random() < .5 else 'andesite'))
                stones += 1
            elif roll < .012 and _empty(c, x, y + 3, z):
                tall = rng.choice(('peony', 'lilac', 'rose_bush'))
                c.set(x, y + 1, z, B(tall, half='lower'));c.set(x, y + 2, z, B(tall, half='upper'))
                flowers += 1
            else:
                c.set(x, y + 1, z, B(rng.choice(FLOWERS)));flowers += 1
    return {'flowers': flowers, 'stones': stones}


def edge_pines(c, islands, keep, seed=23):
    """崖边的松：浮峰草地边上（旁边一格是空的）每隔十来格一棵，七到十一格高；不挨着路线、殿阁、铁链。"""
    rng = random.Random(seed)
    planted = []
    for spec in islands:
        surface = spec['surface']
        edges = [(x, z) for (x, z) in surface
                 if any((x + dx, z + dz) not in surface for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
        rng.shuffle(edges)
        want = max(2, len(surface) // 900)
        mine = []
        for x, z in edges:
            if len(mine) >= want:break
            y = surface[x, z]
            s = c.get(x, y, z)
            if not s or s['Name'] != 'minecraft:grass_block':continue
            if any((x - px) ** 2 + (z - pz) ** 2 < 10 ** 2 for px, _, pz in planted + mine):continue
            if any(keep.blocked(x + dx, y + dy, z + dz) for dx in (-4, 0, 4) for dz in (-4, 0, 4) for dy in (1, 6, 11)):continue
            if not all(_empty(c, x + dx, y + dy, z + dz) for dx in (-3, 0, 3) for dz in (-3, 0, 3) for dy in (2, 7, 11)):continue
            p = landscape.pine(c, x, y, z, seed=rng.randrange(1 << 20), height=rng.randint(7, 11))
            if p:mine.append(p)
        planted += mine
    return planted


def build(c, islands, landmarks, waterfalls, hanging):
    keep = KeepOut(landmarks, waterfalls, hanging)
    return {'spires': spires(c, keep), 'floating_rocks': floating_rocks(c, keep), 'pines': edge_pines(c, islands, keep),
            **meadow(c, islands, keep)}
