"""雾底场地正下方的空洞：剑祟第三阶段「坠渊」的舞台（docs/JIAN_SUI_BOSS.md 第 2.2、10 节）。

整块写进太玄的模板，不在运行时挖：
  - 最底一层模板（世界 -24..3）中间 3×3 块跟着重新导出，空洞顶部的空气、新加的一层洞顶都在里面；
  - 下面再接一层 9 块 28×35×28 的模板（画布 y -35..-1，世界 -59..-25）。原版拼接不放超出世界底的部件，
    按 28 格对齐最多只能再往下一层，所以这一层是 35 格高的非立方模板，底正好在永远不会是基岩的 -59。

空洞（世界 y）：空气 -57..-20，半径 34；洞底两层 -59、-58；洞顶在原来的 -17、-18 两层深板岩下面加一层 -19；
四周三格厚的层理石壁封住含水层（-54 以上是水，以下是岩浆）和洞穴。

里面是塌下来的场地，能踩的都是方块，材料和上面的场地一样：
  - 起点碎块（脚在 -49），5×5 的地板碎块；
  - 垫脚的碎块：地板碎块和断柱截面，里外交错着绕竖轴盘上去不到一圈，所以从顶块往下看，每一块上面都没有别的碎块；
  - 两处落脚台（爬过三分之一、三分之二），掉下去回到到过的最高一处；
  - 两处故意高 2 格，要踩插住的巨剑的剑格才上得去，旁边各有一条多走几跳的绕路；
  - 剑祟所在的顶块（脚在 -29）：石台断下来的一块，闪长岩那层带玄铁心，外圈平滑石只剩几块；
  - 碎块底下挂着倒锥形的碎石和断掉的地基；碎块上方写看不见的光源方块。
只看的碎块（断柱、下坠的魂灯、斜着的地板块、断链）不进模板，由客户端画，这里只生成它们的方块清单和摆放（fall_debris.json）。
"""
import json
import math
import random
from pathlib import Path

import numpy as np

from taixuan_kit import B, L, AIR
import taixuan_terrain as terrain
from taixuan_abyss import C, cy, IRON, POLISHED, _rock, _grid

RADIUS = 36                       # 空气的半径
LINING = 3                        # 石壁厚度
AIR_TOP, AIR_BOTTOM = cy(-20), cy(-57)
CEILING = cy(-19)
FLOOR = (cy(-59), cy(-58))
BOTTOM = cy(-59)                  # 空洞那一层模板的底（画布 y = -35）
HEIGHT = -BOTTOM                  # 那一层模板的高度（35）
TILES = [(x, z) for x in (3, 4, 5) for z in (3, 4, 5)]  # 空洞那一层模板在 3×3 的哪几列（瓦片编号）
VOID = cy(-54)                    # 脚低于这里算掉进虚空
TOP = cy(-30)                     # 顶块闪长岩那层（脚在 -29）
START = cy(-50)                   # 起点碎块的面（脚在 -49）
WALKABLE_R = 21.5                 # 能踩的碎块（含绕路）都在这个半径以内
SEED = 2026_11_22

DEEP = B('deepslate')
REPORT = {}

# 第 k 跳（从 1 数）上去几格；第 26 跳落到顶块上。两处 +2 要借剑格（或者走绕路）
RISES = (1, 1, 0, 1, 1, 1, 0, 1, 1,
         0, 2, 1, 0, 1, 1, 0, 1, 1,
         0, 1, 2, 1, 0, 1, 0, 1)
CHECKPOINTS = (9, 18)
STEPS = tuple(k for k, r in enumerate(RISES, 1) if r == 2)
assert sum(RISES) == (TOP + 1) - (START + 1) == 20 and len(RISES) == 26


def in_cavity(x, y, z):
    """导出时用：空洞里没画东西的格子写成空气。"""
    dx, dz = np.asarray(x, float) - C, np.asarray(z, float) - C
    y = np.asarray(y)
    return (y >= AIR_BOTTOM) & (y <= AIR_TOP) & (np.hypot(dx, dz) <= RADIUS)


# ---------------------------------------------------------------- 碎块
class Stone:
    """一块能踩的碎块：顶面一层 cells（x, z），顶面方块在 y，脚在 y + 1。body 是它所有的方块（含底下的倒锥）。"""

    def __init__(self, kind, cells, y):
        self.kind, self.cells, self.y = kind, sorted(cells), y
        self.body = {}
        xs = [x + .5 for x, _ in cells];zs = [z + .5 for _, z in cells]
        self.center = (sum(xs) / len(xs), sum(zs) / len(zs))

    def radius(self):
        return max(math.hypot(x + .5 - C, z + .5 - C) for x, z in self.cells)

    def as_json(self):
        return {'kind': self.kind, 'top': [round(self.center[0], 2), self.y, round(self.center[1], 2)],
                'feet': self.y + 1, 'cells': [list(c) for c in self.cells]}


def gap(a, b):
    """两块碎块顶面最近的两格之间隔多远（格边到格边，0 表示挨着）。"""
    best = 99.0
    for ax, az in a.cells:
        for bx, bz in b.cells:
            dx, dz = max(0, abs(ax - bx) - 1), max(0, abs(az - bz) - 1)
            best = min(best, math.hypot(dx, dz))
    return best


def shape(kind, rng):
    """顶面的形状（相对锚点的格子）。"""
    if kind == 'start':
        cells = {(x, z) for x in range(-2, 3) for z in range(-2, 3)}
        cells -= {(-2, -2), (2, 2)}
    elif kind == 'checkpoint':
        cells = {(x, z) for x in range(-2, 2) for z in range(-2, 2)}
        cells -= {rng.choice([(-2, -2), (1, -2), (-2, 1), (1, 1)])}
    elif kind == 'pillar':
        cells = {(x, z) for x in (-1, 0, 1) for z in (-1, 0, 1)}
        if rng.random() < .5:cells -= {rng.choice([(-1, -1), (1, -1), (-1, 1), (1, 1)])}
    else:
        w, d = rng.choice([(2, 3), (3, 2), (3, 3), (3, 3), (2, 2), (3, 4), (4, 3)])
        cells = {(x, z) for x in range(-(w // 2), w - w // 2) for z in range(-(d // 2), d - d // 2)}
        if len(cells) > 6 and rng.random() < .6:
            cells -= {rng.choice(sorted(c for c in cells if abs(c[0]) + abs(c[1]) >= 2))}
    return cells


def place(kind, cells, cx, cz, y):
    ax, az = int(math.floor(cx)), int(math.floor(cz))
    return Stone(kind, [(ax + dx, az + dz) for dx, dz in cells], y)


def floor_top(x, z, origin, rng_speck):
    """地板碎块的面：和雾底一样按圈相间（磨制深板岩、深板岩瓷砖），零星裂纹瓷砖和苔石；
    圈按这块碎块原来所在的位置（origin）算，所以碎块上看到的是一段弧。"""
    d = math.hypot(x - origin[0], z - origin[1])
    speck = terrain.noise(x / 2.3 + rng_speck, z / 2.3 - rng_speck)
    if speck > .8:return B('cracked_deepslate_tiles')
    if speck < .1:return B('mossy_cobblestone')
    return B('polished_deepslate') if int(d / 4) % 2 else B('deepslate_tiles')


def pillar_block(y, rng):
    return POLISHED if y % 5 == 0 else B('cracked_stone_bricks') if rng.random() < .2 else B('stone_bricks')


def rubble(rng):
    return rng.choice((B('cobbled_deepslate'), B('cobbled_deepslate'), DEEP, B('tuff'), B('cobblestone'), B('deepslate')))


def build_body(stone, rng):
    """碎块的方块：顶面、地基（两层深板岩），底下挂一个往下收的倒锥；断柱截面往下接一段断柱，底端参差。"""
    body = stone.body
    cells = set(stone.cells)
    if stone.kind == 'pillar':
        depth = rng.randint(4, 7)
        for x, z in cells:
            bottom = stone.y - depth + rng.randint(0, 2)
            for y in range(bottom, stone.y + 1):body[x, y, z] = pillar_block(y - stone.y + 5, rng)
        return
    origin = (stone.center[0] - rng.uniform(-30, 30), stone.center[1] - rng.uniform(-30, 30))
    speck = rng.uniform(0, 50)
    for x, z in cells:
        body[x, stone.y, z] = B('smooth_stone') if stone.kind == 'dais' else floor_top(x, z, origin, speck)
        body[x, stone.y - 1, z] = DEEP
        body[x, stone.y - 2, z] = DEEP
    # 倒锥：一层比一层往里收，边缘随机缺
    cx, cz = stone.center
    r0 = max(math.hypot(x + .5 - cx, z + .5 - cz) for x, z in cells) + .3
    depth = rng.randint(2, 4) + (2 if len(cells) >= 12 else 0)
    for k in range(1, depth + 1):
        r = r0 * (1 - k / (depth + 1)) ** .9
        for x, z in cells:
            if math.hypot(x + .5 - cx, z + .5 - cz) <= r + rng.uniform(-.4, .3):
                body[x, stone.y - 2 - k, z] = rubble(rng)


def build_top(rng):
    """顶块：石台断下来的一块。闪长岩那层（r≤4.5）带 3×3 玄铁心，下面一层平滑石，外圈平滑石只剩几块；
    再往下是地板层、两层深板岩和一个大的倒锥。返回 (方块, 外圈还在的平滑石格子)。"""
    body = {}
    ring = set()
    X, Z, D, T = _grid(8)
    keep = lambda t: terrain.noise(math.cos(t) * 1.7 + 4.0, math.sin(t) * 1.7 + 9.0) > .38
    for i, j in zip(*np.nonzero(D <= 7.2)):
        x, z, d, t = int(X[i, j]), int(Z[i, j]), float(D[i, j]), float(T[i, j])
        edge = 6.2 + 1.4 * terrain.noise(math.cos(t) * 3 + 1, math.sin(t) * 3 + 2)
        if d <= 4.5:
            body[x, TOP, z] = B('polished_diorite')
            body[x, TOP - 1, z] = B('smooth_stone')
            if d <= 1.5:body[x, TOP + 1, z] = IRON
        elif d <= edge and keep(t):
            body[x, TOP - 1, z] = B('smooth_stone');ring.add((x, z))
        if d <= min(edge, 6.0):
            body[x, TOP - 2, z] = B('deepslate_tiles') if int(d) % 2 else B('polished_deepslate')
            body[x, TOP - 3, z] = DEEP
            body[x, TOP - 4, z] = DEEP
    for k in range(1, 7):
        r = 5.6 * (1 - k / 7.5) ** .8
        for i, j in zip(*np.nonzero(D <= r + .6)):
            x, z, d = int(X[i, j]), int(Z[i, j]), float(D[i, j])
            if d <= r + rng.uniform(-.5, .4):body[x, TOP - 4 - k, z] = rubble(rng)
    return body, ring


def ring_stone(ring):
    """外圈的平滑石当成一块碎块（脚在 -30），只用来算最后一跳的距离。"""
    s = Stone('dais_ring', list(ring), TOP - 1)
    return s


R_IN, R_OUT = 11.0, 14.5        # 主路里外两圈（碎块中心离轴）
MAIN_R = 16.6                     # 主路的碎块不出这个半径；外面那一圈留给绕路
DETOUR_BAND = (16.8, WALKABLE_R)  # 绕路的碎块在这一圈里


def free(s, others, margin=1.5):
    """离别的碎块够远：上下差不到 7 格的，顶面之间至少隔 margin 格。"""
    for o in others:
        if abs(o.y - s.y) >= 7 or math.dist(s.center, o.center) > 9:continue
        if gap(s, o) < margin:return False
    return True


def layout(seed):
    """主路：起点 → 25 块碎块 → 顶块。里外两圈交错，角度一直往前（每跳约 0.19 弧度），整条路不到一圈，
    所以从顶块往外看，每一块上面都没有别的碎块。返回 (主路碎块, 转过的角度, 顶块方块, 外圈)。"""
    rng = random.Random(seed)
    theta0 = math.radians(rng.uniform(0, 360))
    stones = [place('start', shape('start', rng), C + 12 * math.cos(theta0), C + 12 * math.sin(theta0), START)]
    theta, feet = theta0, START + 1
    for k in range(1, 26):
        rise = RISES[k - 1]
        feet += rise
        kind = 'checkpoint' if k in CHECKPOINTS else 'pillar' if rng.random() < .3 else 'floor'
        if k >= 22:r_t = (12.6, 11.6, 10.6, 9.4)[k - 22]
        else:r_t = R_OUT if k % 2 else R_IN
        want = 1.0 if rise == 2 else 1.5 if rise == 1 else 2.0
        lo, hi = (1.0, 1.2) if rise == 2 else (1.0, 2.0) if rise == 1 else (1.0, 2.8)
        best = None
        for _ in range(6):
            cells = shape(kind, rng)
            for i in range(36):
                t = theta + .10 + i * .01
                for j in range(13):
                    r = r_t - 1.2 + j * .2
                    s = place(kind, cells, C + r * math.cos(t), C + r * math.sin(t), feet - 1)
                    if s.radius() > MAIN_R or min(math.hypot(x + .5 - C, z + .5 - C) for x, z in s.cells) < 8.0:continue
                    g = gap(stones[-1], s)
                    if not lo <= g <= hi or not free(s, stones[:-1]):continue
                    cost = abs(g - want) + .25 * abs(r - r_t) + 2 * abs(t - theta - .19)
                    if best is None or cost < best[0]:best = (cost, s, t)
            if best:break
        if best is None:return None
        _, stone, theta = best
        stones.append(stone)
    top, ring = build_top(rng)
    return stones, theta - theta0, top, ring


def detour(stones, k, rng, taken):
    """第 k 跳是 +2（第 k-1 块到第 k 块）：另有一条绕路从这一跳之前的某块（k-1、k-2 或 k-3）出发，
    走外面那一圈的四块碎块，每跳最多 +1，接回这一跳之后的某块（k+1 或 k+2），不靠剑格也上得去。"""
    feet = lambda s: s.y + 1
    options = []
    for s0 in (k - 1, k - 2, k - 3):
        for g0 in (k + 1, k + 2):
            total = feet(stones[g0]) - feet(stones[s0])
            if 1 <= total <= 5:options.append((s0, g0, total))
    rng.shuffle(options)
    for s0, g0, total in options:
        a, goal = stones[s0], stones[g0]
        others = [s for i, s in enumerate(stones) if i not in (s0, g0)] + taken
        # 五跳（四块）分配 total 格：每跳 0 或 1
        rises = [1] * total + [0] * (5 - total)

        def ok(s, prev, path):
            radii = [math.hypot(x + .5 - C, z + .5 - C) for x, z in s.cells]
            if max(radii) > DETOUR_BAND[1] or min(radii) < DETOUR_BAND[0] - 1.5:return False
            if not 1.0 <= gap(prev, s) <= 2.0:return False
            if not free(s, others + [q for q in path[:-1] if q is not prev]):return False
            return gap(s, goal) >= 1.5 or len(path) == 4

        def search(path, prev, f, depth, order):
            if depth == 4:
                g = gap(prev, goal)
                return path if 1.0 <= g <= 2.0 and feet(goal) - f == order[4] else None
            f += order[depth]
            for _ in range((30, 24, 18, 18)[depth]):
                cells = shape('floor', rng)
                t = rng.uniform(0, 2 * math.pi);d = rng.uniform(3.0, 5.2)
                s = place('floor', cells, prev.center[0] + d * math.cos(t), prev.center[1] + d * math.sin(t), f - 1)
                if not ok(s, prev, path + [s]):continue
                found = search(path + [s], s, f, depth + 1, order)
                if found:return found
            return None

        for _ in range(4):
            rng.shuffle(rises)
            path = search([], a, feet(a), 0, rises)
            if path:return {'from': s0, 'to': g0, 'stones': path}
    raise AssertionError(('绕路放不下', k))


def solid_cells(stones, top):
    cells = {}
    for i, s in enumerate(stones):
        for p in s.body:cells[p] = i
    for p in top:cells[p] = 'top'
    return cells


def line_blocked(cells, a, b, skip):
    """a→b 的线段经过别的碎块的格子吗（skip 是这条线终点那一块）。"""
    n = int(math.dist(a, b) / .2) + 1
    for i in range(n + 1):
        t = i / n
        p = tuple(int(math.floor(a[j] + (b[j] - a[j]) * t)) for j in range(3))
        owner = cells.get(p)
        if owner is not None and owner != skip and owner != 'top':return p
    return None


def launch_point(target, offset=0.0):
    """剑祟出手的位置：顶块边缘朝着目标那一侧（可以偏开 offset 弧度），离轴 6.5 格，手的高度（脚上 2.5 格）。"""
    t = math.atan2(target[2] - C, target[0] - C) + offset
    return (C + 6.5 * math.cos(t), TOP + 1 + 2.5, C + 6.5 * math.sin(t))


LAUNCH_OFFSETS = [math.radians(d) for d in (0, 15, -15, 30, -30, 45, -45, 60, -60)]


def check(stones, detours, top, ring):
    """跳得过去、头顶不碰、从顶块看得到每一块。不满足就报错，换种子重排。"""
    problems = []
    route = stones + [ring_stone(ring)]
    for k in range(1, len(route)):
        a, b = route[k - 1], route[k]
        rise = b.y - a.y
        g = gap(a, b)
        limit = 1.2 if rise == 2 else 2.2 if rise == 1 else 3.2
        if rise > 2 or g > limit or g < .99:problems.append(('跳不过去', k, rise, round(g, 2)))
    for k, d in detours.items():
        chain = [stones[d['from']]] + d['stones'] + [stones[d['to']]]
        for i in range(1, len(chain)):
            rise, g = chain[i].y - chain[i - 1].y, gap(chain[i - 1], chain[i])
            if rise > 1 or g > 2.2 or g < .99:problems.append(('绕路跳不过去', k, i, rise, round(g, 2)))
    every = stones + [s for d in detours.values() for s in d['stones']]
    cells = solid_cells(every, top)
    # 头顶：每块碎块顶面以上 4 格不能有别的方块（跳起来头顶 3.1 格）
    for i, s in enumerate(every):
        for x, z in s.cells:
            for dy in range(1, 5):
                owner = cells.get((x, s.y + dy, z))
                if owner is not None and owner != i:problems.append(('头顶有东西', i, (x, s.y + dy, z), owner))
    # 跳的路上头顶也不能碰：两块之间的空当，从低的那块脚往上 3.3 格
    for k in range(1, len(stones)):
        a, b = stones[k - 1], stones[k]
        lo = min(a.y, b.y) + 1
        for t in np.linspace(0, 1, 21):
            x, z = a.center[0] + (b.center[0] - a.center[0]) * t, a.center[1] + (b.center[1] - a.center[1]) * t
            for dy in (0, 1, 2, 3):
                owner = cells.get((int(math.floor(x)), lo + dy + (1 if t > .5 and b.y > a.y else 0), int(math.floor(z))))
                if owner is not None and owner not in (k, k - 1):problems.append(('跳的路上碰头', k, owner))
    # 不重叠、不挨着
    for i in range(len(every)):
        for j in range(i + 1, len(every)):
            if gap(every[i], every[j]) < .99 and abs(every[i].y - every[j].y) < 6:problems.append(('挨得太近', i, j))
    # 能踩的都在半径 16.5 以内，顶块以外的不进 7 格
    for i, s in enumerate(every):
        if s.radius() > WALKABLE_R:problems.append(('太靠外', i, round(s.radius(), 1)))
    # 从顶块看得到
    blocked = []
    for i, s in enumerate(every):
        target = (s.center[0], s.y + 2.2, s.center[1])
        if math.hypot(s.center[0] - C, s.center[1] - C) < 9.5 and s.y + 1 >= TOP - 3:continue
        # 剑祟沿顶块边缘走到合适的地方再出手：边缘上有一处（偏开不超过 60°）看得到就行
        hits = [line_blocked(cells, launch_point(target, o), target, i) for o in LAUNCH_OFFSETS]
        if all(hits):blocked.append((i, hits[0], cells[hits[0]]))
    if blocked:problems.append(('顶块看不到', blocked[:6]))
    return problems


def build(c):
    """把空洞画进画布：石壁、洞底、洞顶、碎块、顶块、光源。返回写进 manifest 的空洞布局。"""
    for seed in range(SEED, SEED + 400):
        rng = random.Random(seed * 7 + 1)
        laid = layout(seed)
        if laid is None:continue
        stones, turn, top, ring = laid
        for s in stones:build_body(s, rng)
        detours = {}
        try:
            for k in STEPS:detours[k] = detour(stones, k, rng, [s for d in detours.values() for s in d['stones']])
        except AssertionError:
            continue
        for d in detours.values():
            for s in d['stones']:build_body(s, rng)
        problems = check(stones, detours, top, ring)
        # 路线转过一圈多一点也行：上面几块和下面几块隔着十几格，检查已经保证头顶、跳的路上、从顶块看过去都不挡
        if not problems and turn < 2 * math.pi * 1.3:break
    else:
        raise AssertionError('空洞的碎块排不出来')
    REPORT['seed'] = seed

    # 石壁、洞底、洞顶
    X, Z, D, T = _grid(RADIUS + LINING + 1)
    lining = 0
    for i, j in zip(*np.nonzero(D <= RADIUS + LINING)):
        x, z, d = int(X[i, j]), int(Z[i, j]), float(D[i, j])
        for y in FLOOR:c.set(x, y, z, DEEP if y == FLOOR[1] else B('deepslate'));lining += 1
        c.set(x, CEILING, z, DEEP);lining += 1
        if d > RADIUS:
            for y in range(AIR_BOTTOM, AIR_TOP + 1):
                c.set(x, y, z, _rock(x, y, z, d <= RADIUS + 1));lining += 1

    every = stones + [s for d in detours.values() for s in d['stones']]
    for s in every:
        for (x, y, z), state in s.body.items():c.set(x, y, z, state)
    for (x, y, z), state in top.items():c.set(x, y, z, state)

    # 落脚台上放一盏魂灯（立在碎块上，角上那格）
    lanterns = []
    for k in CHECKPOINTS:
        s = stones[k]
        x, z = max(s.cells, key=lambda p: math.hypot(p[0] + .5 - C, p[1] + .5 - C))
        c.set(x, s.y + 1, z, B('soul_lantern', hanging=False, waterlogged=False))
        lanterns.append([x, s.y + 1, z])

    # 看不见的光源：每块碎块顶面上方 4 格（不占跳的空间），起点、落脚台、顶块多一些
    lights = []

    def light(x, y, z, level):
        if c.get(x, y, z) in (None, AIR):
            c.set(x, y, z, B('light', level=level, waterlogged=False));lights.append([x, y, z, level])

    for i, s in enumerate(every):
        x, z = int(math.floor(s.center[0])), int(math.floor(s.center[1]))
        level = 13 if s.kind == 'start' else 12 if s.kind == 'checkpoint' else 10
        light(x, s.y + 5, z, level)
    for k in range(4):
        t = k * math.pi / 2 + math.pi / 4
        light(int(round(C + 5 * math.cos(t))), TOP + 6, int(round(C + 5 * math.sin(t))), 9)

    debris = props(stones, detours, random.Random(seed + 99))
    for p in debris['props']:
        if p['piece'].startswith('lantern'):
            x, y, z = (int(math.floor(v)) for v in (p['at'][0] + C, p['at'][1] - (-24), p['at'][2] + C))
            light(x, y, z, 11)

    start = stones[0]
    REPORT.update({'lining_blocks': lining, 'stones': len(every), 'turn_degrees': round(math.degrees(turn), 1)})
    return {
        'radius': RADIUS, 'lining': LINING, 'air': [AIR_BOTTOM, AIR_TOP], 'floor': list(FLOOR), 'ceiling': CEILING,
        'bottom': BOTTOM, 'tiles': [list(t) for t in TILES], 'void_feet': VOID,
        'start': start.as_json(),
        'route': [s.as_json() for s in stones[1:]],
        'checkpoints': list(CHECKPOINTS), 'steps': list(STEPS),
        'detours': {str(k): {'from': d['from'], 'to': d['to'], 'stones': [s.as_json() for s in d['stones']]} for k, d in detours.items()},
        'top': {'center': [C, TOP, C], 'feet': TOP + 1, 'radius': 4.5, 'heart': [C, TOP + 1, C],
                'ring': [list(p) for p in sorted(ring)]},
        'lanterns': lanterns, 'lights': lights, 'exit_crack': [round(start.center[0], 2), start.y, round(start.center[1], 2)],
        'seed': seed, 'debris': debris,
    }


# ---------------------------------------------------------------- 只看的碎块（客户端画）
def _pillar_piece(rng, length, broken_top=True):
    blocks = []
    for y in range(length):
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                # 两端断口参差
                if (y == length - 1 and broken_top or y == 0) and (dx or dz) and rng.random() < .4:continue
                state = POLISHED if y % 5 == 2 else B('cracked_stone_bricks') if rng.random() < .25 else B('stone_bricks')
                blocks.append((state, dx, y, dz))
    return blocks


def _slab_piece(rng, w, d, thick):
    blocks = []
    origin = (rng.uniform(-30, 30), rng.uniform(-30, 30))
    speck = rng.uniform(0, 40)
    for x in range(-(w // 2), w - w // 2):
        for z in range(-(d // 2), d - d // 2):
            if (abs(x) == w // 2 or abs(z) == d // 2) and rng.random() < .25:continue
            blocks.append((floor_top(x, z, origin, speck), x, 0, z))
            for t in range(1, thick + 1):
                if t == thick and rng.random() < .35:continue
                blocks.append((DEEP if t < thick else rubble(rng), x, -t, z))
    return blocks


def _chain_piece():
    """一串断链：九节玄铁链环，每节长 7、宽 3，相邻两节转 90°；往上弓成一道弧，一端是断开的半节。"""
    blocks = set()
    links = 9
    for i in range(links):
        s = (i - (links - 1) / 2) / ((links - 1) / 2)
        # 沿竖直方向排开，中间往外弓
        p = np.array([2.6 * (1 - s * s), i * 5.0 - (links - 1) * 2.5, 0.0])
        t = np.array([-5.2 * s / ((links - 1) / 2) / 5.0, 1.0, 0.0])
        t /= np.linalg.norm(t)
        n1 = np.array([0., 0., 1.]);n2 = np.cross(t, n1)
        n = n1 if i % 2 == 0 else n2
        half = 3 if i < links - 1 else 1  # 最上面一节断了，只剩半个
        for u in np.linspace(-3, half, 31):
            for v in (-1, 1):
                q = p + u * t + v * n;blocks.add(tuple(int(round(k)) for k in q))
        for v in np.linspace(-1, 1, 11):
            for u in (-3,) + ((3,) if half == 3 else ()):
                q = p + u * t + v * n;blocks.add(tuple(int(round(k)) for k in q))
    return [(IRON, x, y, z) for x, y, z in sorted(blocks)]


def _lantern_piece(rng, chunk):
    blocks = [(B('soul_lantern', hanging=False, waterlogged=False), 0, 1, 0)]
    if chunk:
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if (dx or dz) and rng.random() < .3:continue
                blocks.append((B('stone_bricks') if rng.random() > .2 else B('cracked_stone_bricks'), dx, 0, dz))
                if rng.random() < .5:blocks.append((POLISHED, dx, -1, dz))
    return blocks


def _center(blocks):
    xs = [b[1] for b in blocks];ys = [b[2] for b in blocks];zs = [b[3] for b in blocks]
    cx, cy_, cz = (min(xs) + max(xs) + 1) / 2, (min(ys) + max(ys) + 1) / 2, (min(zs) + max(zs) + 1) / 2
    r = max(math.dist((x + .5, y + .5, z + .5), (cx, cy_, cz)) for _, x, y, z in blocks) + .9
    return [round(cx, 2), round(cy_, 2), round(cz, 2)], round(r, 2)


def _state(s):
    p = s.get('Properties')
    return s['Name'] + ('[' + ','.join(f'{k}={v}' for k, v in sorted(p.items())) + ']' if p else '')


def props(stones, detours, rng):
    """只看的碎块：方块清单（相对中心）和摆放（离轴 18–24 格，各高度），各自慢慢翻转，不往上飘。"""
    pieces = {
        'pillar_a': _pillar_piece(rng, 9), 'pillar_b': _pillar_piece(rng, 6), 'pillar_c': _pillar_piece(rng, 12),
        'slab_a': _slab_piece(rng, 6, 5, 2), 'slab_b': _slab_piece(rng, 4, 7, 2), 'slab_c': _slab_piece(rng, 8, 6, 3),
        'lantern_a': _lantern_piece(rng, True), 'lantern_b': _lantern_piece(rng, False),
        'chain': _chain_piece(),
    }
    out = {}
    for name, blocks in pieces.items():
        center, radius = _center(blocks)
        out[name] = {'center': center, 'radius': radius, 'blocks': [[_state(s), x, y, z] for s, x, y, z in blocks]}
    order = ['pillar_a', 'slab_a', 'lantern_a', 'pillar_b', 'slab_b', 'lantern_b', 'pillar_c', 'slab_c', 'lantern_a',
             'slab_a', 'pillar_b', 'lantern_b', 'slab_b', 'pillar_a', 'slab_c', 'lantern_a']
    placed = []
    stage_y = (cy(-55), cy(-22))
    for i, name in enumerate(order):
        r = out[name]['radius']
        for _ in range(200):
            t = (i / len(order)) * 2 * math.pi + rng.uniform(-.2, .2)
            d = rng.uniform(23.5 + min(r, 3.0) * .3, 28.5 - min(r, 4.0) * .3)
            y = rng.uniform(stage_y[0] + 3, stage_y[1] - 2)
            at = (d * math.cos(t), y, d * math.sin(t))
            if all(math.dist(at, q['_at']) > r + q['_r'] + 1.0 for q in placed):break
        axis = np.array([rng.uniform(-1, 1), rng.uniform(-.4, .4), rng.uniform(-1, 1)])
        axis /= np.linalg.norm(axis)
        placed.append({'piece': name, '_at': at, '_r': r,
                       'at': [round(at[0], 2), round(at[1] + (-24), 2), round(at[2], 2)],
                       'axis': [round(float(v), 3) for v in axis], 'speed': round(rng.uniform(2.5, 7.0) * rng.choice((-1, 1)), 2),
                       'tilt': [round(rng.uniform(-35, 35), 1), round(rng.uniform(0, 360), 1), round(rng.uniform(-35, 35), 1)],
                       'phase': round(rng.uniform(0, 360), 1)})
    # 断链挂在一侧，竖着，最长
    t = rng.uniform(0, 2 * math.pi)
    placed.append({'piece': 'chain', '_at': None, '_r': 0, 'at': [round(26.0 * math.cos(t), 2), -37.0, round(26.0 * math.sin(t), 2)],
                   'axis': [0.0, 1.0, 0.0], 'speed': 1.2, 'tilt': [8.0, round(math.degrees(t) + 90, 1), 0.0], 'phase': 0.0, 'sway': 6.0})
    for p in placed:
        p.pop('_at');p.pop('_r')
    return {'pieces': out, 'props': placed}


def write_debris(path, debris):
    """客户端画只看的碎块用：方块清单 + 摆放（x、z 相对空洞的竖轴、按画布朝向，y 是世界高度）。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(debris, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))


if __name__ == '__main__':
    from fox_kit import Canvas
    c = Canvas()
    info = build(c)
    print(json.dumps({k: v for k, v in REPORT.items()}, ensure_ascii=False))
    print('route', [(s['feet'], round(math.hypot(s['top'][0] - C, s['top'][2] - C), 1)) for s in info['route']])
