"""腐化世界树的树心战场：加粗树干、扩大大厅、祭坛、出怪隧道、环形高台、坡道和树根掩体。

坐标同世界树模板（腐败之心在 (0, 9, 0)，大厅地板顶面 y = 1，入口拱门在北侧 -z）。
只算形状，方块由 blighted_elven_forest.py 按原有的腐化规则写进各个 32³ 部件。
设计见 docs/CORRUPTED_QUEEN_REMAKE.md 第 4 节。
"""
import math
import numpy as np

# 区域范围（含）：树干加粗后最外约 37 格，留余量。
X0, X1 = -44, 44
Y0, Y1 = -4, 64
Z0, Z1 = -44, 44

HEART = (0, 9, 0)
HALL_R = 24.0          # 大厅半径
FLAT_TOP = 28          # 竖直墙到这一高度，之后收成穹顶
DOME_TOP = 40          # 穹顶收到原树心竖井半径 13 的高度
SHAFT_R = 13.0
SWELL = 9.0            # 树干加粗的格数（底部），往上逐渐收回原树干
SWELL_FULL, SWELL_END = 24, 58
FLOOR_Y = 1            # 地板方块所在高度，站立高度 2

ALTAR_Z = 18.5         # 祭坛在入口对面（南，+z）
ALTAR_TIERS = ((2, 5.2), (3, 4.3), (4, 3.4))
TUNNEL_ANGLES = (45.0, 180.0, 315.0)   # 出怪隧道，按 atan2(z, x) 的角度
TUNNEL_FROM, TUNNEL_TO, TUNNEL_HALF, TUNNEL_HEIGHT = 20.0, 30.0, 2.6, 4.6
GALLERY_Y = (9, 10)    # 环形高台的两层，顶面 y = 10，站立高度 11
GALLERY_INNER = 20.8
ARCH_GAP = 10.5        # 入口拱门上方高台断开的半宽
RAMPS = ((110.0, 148.0), (-10.0, 28.0), (196.0, 234.0))
ROOT_ANGLES = (62.0, 162.0, 303.0, 333.0)
ARCH_ROOT = (160.0, 200.0, 11.0)       # 西侧一道贴地拱起的树根


def old_trunk(y, theta):
    """原世界树树干在 y 高度、theta 方向的半径（elven_tree_study.world_tree 的公式）。"""
    r = np.where(y <= 96, 23.5 - y * .018, 21.77 - (y - 96) * .22) + 5 * np.exp(-np.maximum(y, 0) / 14)
    ridge = 1 + .07 * np.cos(theta * 9 - y * .018) + .035 * np.sin(theta * 5 + y * .04)
    return r, ridge


def swell(y):
    y = np.asarray(y, dtype=float)
    t = np.clip((y - SWELL_FULL) / (SWELL_END - SWELL_FULL), 0, 1)
    return np.where(y < 0, 0.0, SWELL * np.cos(t * math.pi / 2))


def hall_radius(y):
    """大厅在 y 高度的半径；穹顶以上交回原竖井，返回 0 表示不归这里管。"""
    y = np.asarray(y, dtype=float)
    dome = SHAFT_R + (HALL_R - SHAFT_R) * np.cos(np.clip((y - FLAT_TOP) / (DOME_TOP - FLAT_TOP), 0, 1) * math.pi / 2)
    return np.where(y < 2, 0.0, np.where(y <= FLAT_TOP, HALL_R, np.where(y <= DOME_TOP, dome, 0.0)))


def protected_radius(y):
    """不长腐肉、拆空家具的内侧范围：大厅半径再往里 3 格墙面。"""
    r = hall_radius(y)
    return np.where(r > 0, r + 3, 0.0)


def _bezier(points, t):
    a, b, c = (np.array(p, dtype=float) for p in points)
    return (1 - t) ** 2 * a + 2 * t * (1 - t) * b + t * t * c


def _tube(grid_xyz, samples, radii):
    """离一串采样点（各自半径）不超过半径的格子。"""
    x, y, z = grid_xyz
    mask = np.zeros(x.shape, dtype=bool)
    for (px, py, pz), r in zip(samples, radii):
        lo = [int(math.floor(v - r)) for v in (px, py, pz)]
        hi = [int(math.ceil(v + r)) for v in (px, py, pz)]
        sx = slice(max(lo[0] - X0, 0), min(hi[0] - X0 + 1, x.shape[0]))
        sy = slice(max(lo[1] - Y0, 0), min(hi[1] - Y0 + 1, x.shape[1]))
        sz = slice(max(lo[2] - Z0, 0), min(hi[2] - Z0 + 1, x.shape[2]))
        d = (x[sx, sy, sz] + .5 - px) ** 2 + (y[sx, sy, sz] + .5 - py) ** 2 + (z[sx, sy, sz] + .5 - pz) ** 2
        mask[sx, sy, sz] |= d <= r * r
    return mask


def _polar(angle_deg, r, y):
    a = math.radians(angle_deg)
    return (math.cos(a) * r, y, math.sin(a) * r)


AIR, WALL, FEATURE, LUMEN, STAIRS = 1, 2, 3, 4, 5


def build():
    """返回 (labels, markers)。labels[x - X0, y - Y0, z - Z0]：0 不改，其余见上面的常数。"""
    xs = np.arange(X0, X1 + 1)
    ys = np.arange(Y0, Y1 + 1)
    zs = np.arange(Z0, Z1 + 1)
    x, y, z = np.meshgrid(xs, ys, zs, indexing='ij')
    yf = y.astype(float)
    cx, cz = np.sin(yf / 48) * 2.5, np.sin(yf / 59) * 2
    theta = np.arctan2(z - cz, x - cx)
    dist_trunk = np.hypot(x - cx, z - cz)
    r_old, ridge = old_trunk(yf, theta)
    inside_new = (y >= 0) & (dist_trunk <= (r_old + swell(yf)) * ridge)
    dist = np.hypot(x, z)
    hall = dist < hall_radius(yf)
    arch = (z < 0) & (y >= 2) & (y < 35) & ((x / 9) ** 2 + (np.maximum(0, y - 19) / 16) ** 2 < 1)

    shaft = (y >= 2) & (y < 96) & (dist < np.where(y < 82, SHAFT_R, SHAFT_R - (y - 82) * .22))

    labels = np.zeros(x.shape, dtype=np.int8)
    # 加粗后的树干：大厅、上方竖井和拱门以外全部是实心树壁（也封住旧内廊通向外圈栈道的洞口）。
    wall_zone = inside_new & (y <= SWELL_END) & ~hall & ~shaft
    labels[wall_zone] = WALL
    labels[(hall | arch) & (inside_new | hall)] = AIR
    labels[(y == FLOOR_Y) & (dist < HALL_R)] = LUMEN

    # 出怪隧道：从大厅墙脚往树壁里挖进去的拱形壁龛，不打通到外面。
    for angle in TUNNEL_ANGLES:
        a = math.radians(angle)
        along = x * math.cos(a) + z * math.sin(a)
        across = np.abs(-x * math.sin(a) + z * math.cos(a))
        h = (y - 2) / TUNNEL_HEIGHT
        alcove = (along >= TUNNEL_FROM) & (along <= TUNNEL_TO) & (y >= 2) & (h < 1) & (across < TUNNEL_HALF * np.sqrt(np.clip(1 - h * h, 0, 1)))
        labels[alcove & inside_new] = AIR

    grid = (x, y, z)
    features = np.zeros(x.shape, dtype=bool)
    # 祭坛：三层收小的树根圆台，顶面中央一小片发光。
    altar_d = np.hypot(x + .5, z + .5 - ALTAR_Z)
    for ty, tr in ALTAR_TIERS:
        features |= (y == ty) & (altar_d <= tr)
    # 环形高台：贴着墙一圈，入口拱门上方断开。
    ring = (dist >= GALLERY_INNER) & (dist < HALL_R + 1) & ((y == GALLERY_Y[0]) | (y == GALLERY_Y[1]))
    ring &= ~((z < 0) & (np.abs(x) < ARCH_GAP))
    features |= ring
    # 坡道：沿墙从地板盘上高台。
    for a0, a1 in RAMPS:
        samples, radii = [], []
        for i in range(48):
            t = i / 47
            ang = a0 + (a1 - a0) * t
            r = 21.4 + .8 * t
            top = 2 + 8 * t
            samples.append(_polar(ang, r, top - 1.2))
            radii.append(1.55)
        features |= _tube(grid, samples, radii)
    # 树根掩体：从墙上探出，弯进地面。
    for angle in ROOT_ANGLES:
        anchor, mid, foot = _polar(angle, 24.6, 18), _polar(angle + 4, 19, 10), _polar(angle + 8, 14, 1.5)
        samples = [_bezier((anchor, mid, foot), i / 40) for i in range(41)]
        radii = [1.5 + .45 * (i / 40) for i in range(41)]
        features |= _tube(grid, samples, radii)
    a0, a1, r = ARCH_ROOT
    samples, radii = [], []
    for i in range(41):
        t = i / 40
        ang = a0 + (a1 - a0) * t
        samples.append(_polar(ang, r, 1.5 + 4.2 * math.sin(t * math.pi)))
        radii.append(1.35 + .4 * abs(.5 - t))
    features |= _tube(grid, samples, radii)
    labels[features & (labels == AIR) | features & (labels == LUMEN)] = FEATURE
    labels[(y == 4) & (altar_d <= 1.6)] = LUMEN

    # 入口：拱门里的地面铺平，门口两级木阶接到外面的地面。
    mouth = None
    for zz in range(0, Z0 - 1, -1):
        if not inside_new[0 - X0, FLOOR_Y - Y0, zz - Z0]:
            mouth = zz + 1
            break
    assert mouth is not None and mouth < -HALL_R
    for xx in range(-4, 5):
        labels[xx - X0, FLOOR_Y - Y0, mouth - Z0] = STAIRS
        labels[xx - X0, 0 - Y0, mouth - 1 - Z0] = STAIRS

    rel = lambda p: [round(p[0] - HEART[0], 2), round(p[1] - HEART[1], 2), round(p[2] - HEART[2], 2)]
    markers = {
        '说明': '相对腐败之心方块的偏移（模板朝向 south）；运行时按 queen_heart 的 facing 旋转。y 为站立高度。',
        'hall_radius': HALL_R,
        'dome_top': DOME_TOP - HEART[1],
        'floor': FLOOR_Y + 1 - HEART[1],
        'altar': rel((0, ALTAR_TIERS[-1][0] + 1, ALTAR_Z)),
        'tunnels': [rel(_polar(a, (TUNNEL_FROM + TUNNEL_TO) / 2 + 2, 2)) for a in TUNNEL_ANGLES],
        'gallery': {'stand': GALLERY_Y[1] + 1 - HEART[1], 'inner': GALLERY_INNER, 'outer': HALL_R},
        'ramps': [[rel(_polar(a0, 21.4, 2)), rel(_polar(a1, 22.2, GALLERY_Y[1] + 1))] for a0, a1 in RAMPS],
        'entrance': rel((0, 2, -HALL_R + 1)),
        'gate': rel((0, 1, mouth - 4)),
        'exit_walk': [rel((0, 2, -8)), rel((0, 2, -HALL_R + 3))],
    }
    return labels, markers


if __name__ == '__main__':
    labels, markers = build()
    for k in (AIR, WALL, FEATURE, LUMEN, STAIRS):
        print(k, int((labels == k).sum()))
    print(markers)
