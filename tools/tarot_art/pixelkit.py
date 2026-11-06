"""灵猫塔罗的原生像素画板：所有形状都落在 112×192 网格上，没有缩放、抗锯齿或半透明。

坐标约定：
- 顶点和圆心都用像素索引，(0, 0) 是左上角像素，(111, 191) 是右下角像素。
- 画面中线在 55.5：x 与 111-x 互为镜像，对称图形用这对坐标写。
- rect 的两个角都包含在内。

每张牌有两层：base 是不透明的牌画，glow 是发光层。发光层的不透明像素在游戏里按满亮度显示，
同时也写进 base，这样不支持发光的环境里牌面不会露洞。
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image

W, H = 112, 192
YY, XX = np.mgrid[0:H, 0:W]
BAYER4 = (np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) + 0.5) / 16
BAYER = BAYER4[YY % 4, XX % 4]


# ---------------------------------------------------------------- 色板
# 每条色阶从暗到亮，5 或 6 档。暗部偏冷、亮部偏暖，接近原版材质那种略灰的色调。
# 公共牌框的深靛、旧金、象牙、蓝青都从这里延伸，牌画和牌框放在一起不会跳色。
INK = '#14121f'
NAVY = ['#10142c', '#1a2047', '#272f63', '#374481', '#5063a3']
NIGHT = ['#0b0f24', '#131938', '#1c254e', '#273366', '#364680']
SKY = ['#233660', '#2e4876', '#3b5c8c', '#5079a2', '#7098ba', '#a4c4d2']
DAY = ['#4d7fb5', '#6d9cc9', '#90b8d9', '#b8d3e4', '#dbe9ec']
DUSK = ['#8c6a54', '#b58f63', '#d9b673', '#eed292', '#f9ecbe']
TEAL = ['#123a46', '#1c6673', '#2fa0ab', '#59dcd9', '#c0fdf2']
GOLD = ['#4b2f1e', '#83572a', '#b8853a', '#e3b34f', '#f7d87e', '#fff3bb']
RED = ['#2f0c18', '#551422', '#82202e', '#ab3838', '#d36a50']
ORANGE = ['#6a2612', '#a44318', '#d46e1f', '#ef9e30', '#fbd162']
FIRE = ['#6e1f10', '#b83a14', '#e66a18', '#f7a82a', '#ffe26a', '#fff8c4']
IVORY = ['#857656', '#ad9e78', '#d0c29c', '#e9dfbe', '#faf4dc']
WHITE = ['#8d919a', '#b7bbbd', '#dcdcd4', '#f3f2e8', '#ffffff']
SAGE = ['#293b28', '#43593a', '#627b4d', '#88a169', '#b3c58c']
GRASS = ['#2d4a20', '#42692b', '#5c8a37', '#7da949', '#a5c868']
LEAF = ['#1c3a20', '#2c552b', '#3f7437', '#5a9446', '#83b45e']
DIRT = ['#30201a', '#4a3327', '#664735', '#815d45', '#9d785b']
WOOD = ['#2e1d10', '#50331a', '#744d27', '#9c6e38', '#c29655']
LEATHER = ['#26170f', '#40281a', '#5e3d27', '#7c5536', '#9f7650']
STONE = ['#30323c', '#4a4d57', '#696c74', '#898c93', '#aeb0b3']
SLATE = ['#24223a', '#393650', '#535069', '#716d85', '#9b97a8']
SAND = ['#6d5740', '#94785a', '#b89a74', '#d7bd93', '#efdcb5']
PURPLE = ['#181238', '#281f5c', '#3b3182', '#5448a6', '#7c72c9']
PLUM = ['#190d24', '#2a1535', '#401f4a', '#5a2e62', '#7c4780']
SKIN = ['#8e5a4b', '#c78d75', '#e9b89b', '#f8dbc0', '#fff1de']
PINK = ['#8d4b5a', '#c07a80', '#e6a8a4', '#f6cfc4', '#ffe9df']
HAIR_BLACK = ['#121020', '#1f1c30', '#312d47', '#484460', '#6b6682']
HAIR_BLUEBLACK = ['#0f1228', '#1a2042', '#28325f', '#3a4a80', '#5a70a6']
HAIR_VIOLETBLACK = ['#160f28', '#261a42', '#3a2a60', '#51407f', '#7566a4']
HAIR_SILVER = ['#57536f', '#847f9c', '#b0adc2', '#d8d6e2', '#f6f5f9']
HAIR_LILAC = ['#4b3f70', '#75679b', '#a497c5', '#cbc2e0', '#eee9f5']
HAIR_BROWN = ['#28150e', '#452619', '#683d25', '#8c5834', '#b17c4c']
HAIR_HONEY = ['#76491c', '#ac7b2a', '#dbac47', '#f2d271', '#fdeea5']
HAIR_AUBURN = ['#37130f', '#5c2217', '#88371f', '#b2552f', '#d87e49']
BLUE_IRIS, GREEN_IRIS, GOLD_IRIS, VIOLET_IRIS = '#3f9fc4', '#4e9a57', '#d6a026', '#8467c4'
RED_IRIS, GREY_IRIS, BROWN_IRIS = '#b0432f', '#7f8aa6', '#7a4a26'


def rgb(c):
    """把 '#rrggbb' 或 (r, g, b) 变成元组；结果都是 0–255 的整数。"""
    if isinstance(c, str):
        return tuple(bytes.fromhex(c[1:]))
    return tuple(int(v) for v in c)


def dim(c, k):
    """按比例压暗一个颜色，主要给预览和半隐藏的远景用。"""
    r, g, b = rgb(c)
    return (int(r * k), int(g * k), int(b * k))


# ---------------------------------------------------------------- 蒙版：所有函数都返回 H×W 的布尔数组
def blank():
    return np.zeros((H, W), bool)


def rect(x0, y0, x1, y1):
    """两角都包含在内。"""
    m = blank()
    m[max(y0, 0):max(y1 + 1, 0), max(x0, 0):max(x1 + 1, 0)] = True
    return m


def _segment(x0, y0, x1, y1):
    """Bresenham，顶点取四舍五入到整数像素。"""
    x0, y0, x1, y1 = round(x0), round(y0), round(x1), round(y1)
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    out = []
    while True:
        out.append((x0, y0))
        if x0 == x1 and y0 == y1:
            return out
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy


def _perfect(chain):
    """去掉折线上的 L 形多余点，让 1 像素线没有疙瘩。"""
    out = []
    for p in chain:
        if out and p == out[-1]:
            continue
        out.append(p)
    i = 1
    while i < len(out) - 1:
        a, b, c = out[i - 1], out[i], out[i + 1]
        if (a[0] == b[0] or a[1] == b[1]) and (c[0] == b[0] or c[1] == b[1]) and a[0] != c[0] and a[1] != c[1]:
            del out[i]
            continue
        i += 1
    return out


def _stamp(m, chain, width=1):
    """沿像素链盖章。奇数宽以像素为圆心；偶数宽的圆心落在像素右下角，w=2 就是 2×2 的方块。"""
    if width == 1:
        for x, y in chain:
            if 0 <= x < W and 0 <= y < H:
                m[y, x] = True
        return m
    even = width % 2 == 0
    off = 0.5 if even else 0
    limit = (width / 2) ** 2 if even else ((width - 1) / 2 + 0.25) ** 2
    reach = width // 2 + 1
    for x, y in chain:
        for yy in range(max(y - reach, 0), min(y + reach + 1, H)):
            for xx in range(max(x - reach, 0), min(x + reach + 1, W)):
                if (xx - x - off) ** 2 + (yy - y - off) ** 2 <= limit:
                    m[yy, xx] = True
    return m


def line(pts, w=1):
    """折线。w 为像素宽；1 像素时自动去掉 L 形疙瘩。"""
    chain = []
    for a, b in zip(pts, pts[1:]):
        chain += _segment(*a, *b)
    return _stamp(blank(), _perfect(chain), w)


def _spline(pts, closed=False, steps=None):
    """Catmull-Rom：曲线穿过每个控制点。"""
    p = [np.array(q, float) for q in pts]
    if closed:
        ring = [p[-1]] + p + [p[0], p[1]]
    else:
        ring = [p[0]] + p + [p[-1]]
    out = []
    for i in range(1, len(ring) - 2):
        a, b, c, d = ring[i - 1], ring[i], ring[i + 1], ring[i + 2]
        n = steps or max(4, int(np.hypot(*(c - b)) * 2))
        for k in range(n):
            t = k / n
            q = 0.5 * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t ** 2 + (3 * b - a - 3 * c + d) * t ** 3)
            out.append(tuple(q))
    out.append(tuple(ring[-2]))
    return out


def curve(pts, w=1, closed=False):
    """穿过控制点的平滑曲线。"""
    chain = [(round(x), round(y)) for x, y in _spline(pts, closed)]
    filled = []
    for a, b in zip(chain, chain[1:]):
        filled += _segment(*a, *b)
    return _stamp(blank(), _perfect(filled), w)


def taper(pts, w0, w1):
    """由粗到细的笔触，发绺、尾巴、羽尖、火舌都用它。w 是像素直径。"""
    samples = _spline(pts)
    m = blank()
    n = max(len(samples) - 1, 1)
    for i, (x, y) in enumerate(samples):
        r = (w0 + (w1 - w0) * i / n) / 2
        x0, x1 = int(np.floor(x - r - 1)), int(np.ceil(x + r + 1))
        y0, y1 = int(np.floor(y - r - 1)), int(np.ceil(y + r + 1))
        for yy in range(max(y0, 0), min(y1 + 1, H)):
            for xx in range(max(x0, 0), min(x1 + 1, W)):
                if (xx - x) ** 2 + (yy - y) ** 2 <= max(r, 0.5) ** 2:
                    m[yy, xx] = True
    return m


def poly(pts):
    """多边形。顶点是像素索引，顶点和边本身都在蒙版内，细尖角不会丢。"""
    m = blank()
    pts = [(float(x), float(y)) for x, y in pts]
    ys = [p[1] for p in pts]
    for y in range(max(int(np.ceil(min(ys))), 0), min(int(np.floor(max(ys))), H - 1) + 1):
        xs = []
        for (x0, y0), (x1, y1) in zip(pts, pts[1:] + pts[:1]):
            if y0 == y1:
                continue
            lo, hi = (y0, y1) if y0 < y1 else (y1, y0)
            if lo <= y < hi:
                xs.append(x0 + (y - y0) * (x1 - x0) / (y1 - y0))
        xs.sort()
        for a, b in zip(xs[::2], xs[1::2]):
            x0, x1 = int(np.ceil(a - 1e-6)), int(np.floor(b + 1e-6))
            m[y, max(x0, 0):max(x1 + 1, 0)] = True
    for a, b in zip(pts, pts[1:] + pts[:1]):
        _stamp(m, _segment(*a, *b))
    return m


def blob(pts):
    """穿过控制点的封闭平滑形状，披风、云、叶团用它。"""
    return poly(_spline(pts, closed=True))


def ellipse(cx, cy, rx, ry=None):
    """实心椭圆。cx、cy 可以是半整数，这样偶数宽也严格对称。"""
    ry = rx if ry is None else ry
    return ((XX - cx) / (rx + 0.25)) ** 2 + ((YY - cy) / (ry + 0.25)) ** 2 <= 1


def ring(cx, cy, rx, ry=None, w=1):
    """椭圆环，像素沿外缘走。"""
    ry = rx if ry is None else ry
    outer_mask = ellipse(cx, cy, rx, ry)
    inner_mask = outer_mask
    for _ in range(w):
        inner_mask = shrink(inner_mask)
    return outer_mask & ~inner_mask


def shift(m, dx, dy):
    """把蒙版平移，移出画面的部分丢弃。"""
    out = blank()
    ys0, ys1 = max(0, -dy), min(H, H - dy)
    xs0, xs1 = max(0, -dx), min(W, W - dx)
    if ys1 > ys0 and xs1 > xs0:
        out[ys0 + dy:ys1 + dy, xs0 + dx:xs1 + dx] = m[ys0:ys1, xs0:xs1]
    return out


def grow(m, n=1, diag=False):
    """向外扩 n 像素。diag=True 时连对角也扩。"""
    out = m
    for _ in range(n):
        o = out | shift(out, 1, 0) | shift(out, -1, 0) | shift(out, 0, 1) | shift(out, 0, -1)
        if diag:
            o |= shift(out, 1, 1) | shift(out, -1, -1) | shift(out, 1, -1) | shift(out, -1, 1)
        out = o
    return out


def shrink(m, n=1):
    """向内收 n 像素。"""
    return ~grow(~m, n)


def edge(m):
    """蒙版内部贴边的一圈像素（8 连通的细线）。"""
    return m & ~shrink(m)


def outer(m):
    """蒙版外部紧贴的一圈像素。"""
    return grow(m) & ~m


def mirror(m, axis=55.5):
    """绕竖线镜像，默认是画面中线。"""
    out = blank()
    for x in range(W):
        xx = int(round(2 * axis - x))
        if 0 <= xx < W:
            out[:, xx] = m[:, x]
    return out


def blocky(m, n=2, ox=0, oy=0):
    """把蒙版量化成 n×n 的方格：山、崖、云这类大地形用它做出方块阶梯边。"""
    out = blank()
    for y in range(oy % n - n, H, n):
        for x in range(ox % n - n, W, n):
            cx, cy = x + n // 2, y + n // 2
            if 0 <= cx < W and 0 <= cy < H and m[cy, cx]:
                out[max(y, 0):y + n, max(x, 0):x + n] = True
    return out


def flip_pts(pts, axis=55.5):
    """镜像一串坐标，省得左右各写一遍。"""
    return [(2 * axis - x, y) for x, y in pts]


# ---------------------------------------------------------------- 牌面
class Card:
    def __init__(self, card_id='', bg=None):
        self.id = card_id
        self.rgb = np.zeros((H, W, 3), np.uint8)
        self.gl = np.zeros((H, W, 4), np.uint8)
        if bg is not None:
            self.rgb[:] = rgb(bg)

    # 基础绘制 ------------------------------------------------------
    def paint(self, mask, color):
        """用一种颜色填蒙版。后画的盖先画的，发光层也一起被盖住。"""
        self.rgb[mask] = rgb(color)
        self.gl[mask] = 0

    def px(self, x, y, color):
        if 0 <= x < W and 0 <= y < H:
            self.rgb[y, x] = rgb(color)
            self.gl[y, x] = 0

    def pxs(self, pts, color):
        for x, y in pts:
            self.px(x, y, color)

    def sprite(self, x, y, rows, pal, flip=False, vflip=False, glow=''):
        """ASCII 精灵。rows 是字符串列表，'.' 和空格透明。pal 把字符映射到颜色。
        glow 里列出的字符写进发光层。五官、手、小图标这类要一格格摆的东西都用它。"""
        rows = list(rows)
        if vflip:
            rows = rows[::-1]
        width = max(len(r) for r in rows)
        for j, r in enumerate(rows):
            r = r.ljust(width, '.')
            if flip:
                r = r[::-1]
            for i, ch in enumerate(r):
                if ch in '. ':
                    continue
                xx, yy = x + i, y + j
                if not (0 <= xx < W and 0 <= yy < H):
                    continue
                self.rgb[yy, xx] = rgb(pal[ch])
                self.gl[yy, xx] = (*rgb(pal[ch]), 255) if ch in glow else (0, 0, 0, 0)

    def sprite_mask(self, x, y, rows, flip=False, vflip=False):
        """精灵占到的像素，描边或投影用。"""
        m = blank()
        rows = list(rows)
        if vflip:
            rows = rows[::-1]
        width = max(len(r) for r in rows)
        for j, r in enumerate(rows):
            r = r.ljust(width, '.')
            if flip:
                r = r[::-1]
            for i, ch in enumerate(r):
                if ch not in '. ' and 0 <= x + i < W and 0 <= y + j < H:
                    m[y + j, x + i] = True
        return m

    # 体积 ----------------------------------------------------------
    def volume(self, mask, ramp, base=2, light=(-1, -1), shadow=2, hi=1, deep=0):
        """硬边体积：先平涂，再按光向给背光侧压一条暗带、迎光侧提一条亮带。全套默认光从左上来。"""
        lx, ly = light
        self.paint(mask, ramp[base])
        if shadow and base > 0:
            keep = mask.copy()
            for k in range(1, shadow + 1):
                keep &= shift(mask, lx * k, ly * k)
            self.paint(mask & ~keep, ramp[base - 1])
        if deep and base > 1:
            keep = mask.copy()
            for k in range(1, deep + 1):
                keep &= shift(mask, lx * k, ly * k)
            self.paint(mask & ~keep, ramp[base - 2])
        if hi and base < len(ramp) - 1:
            keep = mask.copy()
            for k in range(1, hi + 1):
                keep &= shift(mask, -lx * k, -ly * k)
            self.paint(mask & ~keep, ramp[base + 1])

    def shape(self, mask, ramp, base=2, light=(-1, -1), shadow=2, hi=1, deep=0, line_color=None):
        """体积加内描边，是画大块衣物、器物的主力。描边默认取色阶最暗一档，不用纯黑。"""
        self.volume(mask, ramp, base, light, shadow, hi, deep)
        self.paint(edge(mask), line_color or ramp[0])

    def fold(self, pts, color, clip=None, w=1):
        """衣褶或结构线：一条贴在形状内部的曲线。"""
        m = curve(pts, w)
        if clip is not None:
            m &= clip
        self.paint(m, color)

    def trim(self, pts, clip=None, ramp=GOLD, w=1, base=3, smooth=True, shadow=False):
        """烫金滚边：一条实线。shadow=True 时下方压一条暗线，只在宽边上用，细边压了会变成虚线。"""
        make = curve if smooth else line
        m = make(pts, w)
        if clip is not None:
            m &= clip
        if shadow:
            under = shift(m, 0, 1) & ~m
            if clip is not None:
                under &= clip
            self.paint(under, ramp[base - 2])
        self.paint(m, ramp[base])

    # 渐变与抖动 -----------------------------------------------------
    def dither(self, mask, c1, c2, t=0.5):
        """有序抖动：t 越大 c2 越多。"""
        self.paint(mask, c1)
        self.paint(mask & (BAYER < t), c2)

    def vgrad(self, mask, stops, y0=None, y1=None, soft=0.22):
        """分档竖向渐变：色带之间只留一窄条有序抖动，避免整幅都是噪点。"""
        ys = YY[mask]
        if ys.size == 0:
            return
        y0 = ys.min() if y0 is None else y0
        y1 = ys.max() if y1 is None else y1
        self._grad(mask, (YY - y0) / max(y1 - y0, 1), stops, soft)

    def rgrad(self, mask, cx, cy, r, stops, soft=0.22, ry=None):
        """径向渐变：stops[0] 在中心。"""
        ry = r if ry is None else ry
        self._grad(mask, np.hypot((XX - cx) / r, (YY - cy) / ry), stops, soft)

    def _grad(self, mask, t, stops, soft):
        cols = np.array([rgb(s) for s in stops], np.uint8)
        v = np.clip(t, 0, 1) * (len(stops) - 1)
        lo = np.floor(v).astype(int)
        frac = v - lo
        # frac 落在色带中段时才抖动，两头保持平涂
        d = np.clip((frac - (1 - soft) / 2) / max(soft, 1e-6), 0, 1)
        idx = lo + (d > BAYER)
        idx = np.clip(idx, 0, len(stops) - 1)
        self.rgb[mask] = cols[idx[mask]]
        self.gl[mask] = 0

    # 原版风材质 -----------------------------------------------------
    def speckle(self, mask, colors, p=0.15, seed=1):
        """原版材质那种逐像素的轻微颜色跳动。"""
        rng = np.random.default_rng(seed)
        roll = rng.random((H, W))
        step = p / len(colors)
        for i, col in enumerate(colors):
            self.paint(mask & (roll >= i * step) & (roll < (i + 1) * step), col)

    def stone_bricks(self, mask, ramp=STONE, bw=11, bh=6, ox=0, oy=0, seed=3, base=2):
        """石砖，错缝排列。每块砖略有明暗差，上沿受光、缝是最暗一档。"""
        rng = np.random.default_rng(seed)
        self.paint(mask, ramp[base])
        tones = rng.integers(-1, 2, (64, 64))
        for y in range(H):
            row = (y - oy) // bh
            for x in range(W):
                if not mask[y, x]:
                    continue
                off = ox + (bw // 2 if row % 2 else 0)
                col = (x - off) // bw
                u, v = (x - off) % bw, (y - oy) % bh
                if v == bh - 1 or u == bw - 1:
                    tone = 0
                elif v == 0 or u == 0:
                    tone = min(base + 1 + tones[row % 64, col % 64], len(ramp) - 1)
                else:
                    tone = max(base + tones[row % 64, col % 64], 1)
                self.rgb[y, x] = rgb(ramp[tone])
                self.gl[y, x] = 0
        self.speckle(mask & ~shrink(mask, 0), [ramp[base - 1]], 0.04, seed + 1)

    def planks(self, mask, ramp=WOOD, h=5, seed=5, base=2, vertical=False):
        """木板：板缝、错开的接缝和少量木纹。"""
        rng = np.random.default_rng(seed)
        self.paint(mask, ramp[base])
        roll = rng.random((H, W))
        shifts = rng.integers(0, 24, 64)
        for y in range(H):
            for x in range(W):
                if not mask[y, x]:
                    continue
                a, b = (x, y) if vertical else (y, x)
                row = a // h
                if a % h == h - 1:
                    self.rgb[y, x] = rgb(ramp[0])
                elif (b + shifts[row % 64]) % 24 == 0:
                    self.rgb[y, x] = rgb(ramp[0])
                elif a % h == 0:
                    self.rgb[y, x] = rgb(ramp[base + 1])
                elif roll[y // 2, x] < 0.14:
                    self.rgb[y, x] = rgb(ramp[base - 1])

    def soil(self, mask, ramp=DIRT, seed=6, base=2):
        """泥土：原版泥土块的颗粒感。"""
        self.paint(mask, ramp[base])
        self.speckle(mask, [ramp[base - 1], ramp[base + 1], ramp[base - 1], ramp[max(base - 2, 0)]], 0.34, seed)

    def turf(self, mask, ramp=GRASS, seed=7, base=2):
        """草面：比泥土更碎的亮暗点。"""
        self.paint(mask, ramp[base])
        self.speckle(mask, [ramp[base + 1], ramp[base - 1], ramp[base + 1], ramp[min(base + 2, len(ramp) - 1)]], 0.36, seed)

    def grass_lip(self, grass_mask, ramp=GRASS, seed=8, length=5):
        """草方块侧面的锯齿垂边：把草面向下拖出不规则的一列列垂滴。"""
        rng = np.random.default_rng(seed)
        out = blank()
        for x in range(W):
            ys = np.nonzero(grass_mask[:, x])[0]
            if ys.size == 0:
                continue
            bottom = ys.max()
            n = int(rng.integers(1, length + 1))
            if x % 2 == 0:
                n = max(1, n - 2)
            out[bottom + 1:min(bottom + 1 + n, H), x] = True
        self.paint(out, ramp[1])
        self.speckle(out, [ramp[2]], 0.4, seed + 1)
        return out

    def leaves(self, mask, ramp=LEAF, seed=9, base=2):
        """树叶：原版橡树叶那种成团的明暗和小黑洞。"""
        rng = np.random.default_rng(seed)
        self.paint(mask, ramp[base])
        noise = rng.random((H // 2 + 1, W // 2 + 1))
        big = noise[YY // 2, XX // 2]
        self.paint(mask & (big > 0.72), ramp[base + 1])
        self.paint(mask & (big < 0.24), ramp[base - 1])
        fine = rng.random((H, W))
        self.paint(mask & (fine < 0.05), ramp[0])
        self.paint(mask & (fine > 0.95), ramp[base + 2])

    def bark(self, mask, ramp=WOOD, seed=10, base=2):
        """树皮：竖向断续条纹。"""
        rng = np.random.default_rng(seed)
        self.paint(mask, ramp[base])
        for x in range(0, W):
            y = int(rng.integers(0, 6))
            while y < H:
                n = int(rng.integers(2, 7))
                tone = rng.choice([0, 1, 1, 3])
                if rng.random() < 0.5:
                    self.paint(mask & rect(x, y, x, y + n), ramp[tone])
                y += n + int(rng.integers(1, 5))

    def water(self, mask, ramp=SKY, seed=11, base=2):
        """水面：横向短亮条，近似原版水方块的流纹。"""
        rng = np.random.default_rng(seed)
        self.paint(mask, ramp[base])
        ys, xs = np.nonzero(mask)
        if ys.size == 0:
            return
        for _ in range(max(ys.size // 40, 3)):
            i = rng.integers(0, ys.size)
            n = int(rng.integers(2, 7))
            tone = rng.choice([base - 1, base - 1, base + 1])
            self.paint(mask & rect(xs[i], ys[i], xs[i] + n, ys[i]), ramp[tone])

    def cloud(self, x, y, w, h, ramp=IVORY, seed=12, base=4):
        """方块云：平底、由几段矩形叠起来，呼应原版的方云。返回蒙版。"""
        rng = np.random.default_rng(seed)
        m = rect(x, y + h - 2, x + w - 1, y + h - 1)
        cols = max(w // 7, 2)
        for i in range(cols):
            cw = int(rng.integers(w // cols + 1, w // cols + 5))
            cx = x + int((w - cw) * (i + 0.5) / cols)
            ch = int(h * (0.45 + 0.55 * (1 - abs(2 * (i + 0.5) / cols - 1))) * rng.uniform(0.75, 1.0))
            m |= rect(cx, y + h - ch, cx + cw - 1, y + h - 1)
        self.paint(m, ramp[base])
        self.paint(m & ~shift(m, 0, -2), ramp[base - 1])
        self.paint(m & ~shift(m, -1, 0) & ~rect(0, 0, W, y + h - 3), ramp[base - 1])
        return m

    # 发光 ----------------------------------------------------------
    def glow(self, mask, color):
        """写进发光层：游戏里这些像素按满亮度显示。"""
        self.rgb[mask] = rgb(color)
        self.gl[mask] = (*rgb(color), 255)

    def glow_px(self, pts, color):
        for x, y in pts:
            if 0 <= x < W and 0 <= y < H:
                self.rgb[y, x] = rgb(color)
                self.gl[y, x] = (*rgb(color), 255)

    def aura(self, mask, color, n=1, t=0.5, clip=None):
        """魔光的余晖：贴着发光形状铺一圈有序抖动的暗色。它不发光，只在白天也能读出光的范围。"""
        m = grow(mask, n, diag=True) & ~mask
        if clip is not None:
            m &= clip
        self.paint(m & (BAYER < t), color)

    def node(self, x, y, r=2, ramp=TEAL):
        """Logos 蓝青菱形节点。r=1 是最小可读的十字菱，r≥2 带亮芯和暗外缘。"""
        m = (np.abs(XX - x) + np.abs(YY - y)) <= r
        core = (np.abs(XX - x) + np.abs(YY - y)) <= max(r - 1, 0)
        self.paint(outer(m), ramp[0])
        self.glow(m, ramp[2] if r >= 2 else ramp[3])
        self.glow(core, ramp[3])
        self.glow_px([(x, y)], ramp[4])
        if r >= 3:
            self.glow_px([(x - 1, y - 1), (x - r + 1, y)], ramp[4])

    def node_h(self, x, y, rx=7, ry=3, ramp=TEAL):
        """扁菱形踏点（愚者崖外那种透视感的蓝青踏板）。"""
        d = np.abs(XX - x) / (rx + 0.5) + np.abs(YY - y) / (ry + 0.5)
        m = d <= 1
        inner = d <= 0.62
        self.glow(m, ramp[4])
        self.glow(inner, ramp[2])
        self.paint(outer(m), ramp[1])

    def glow_line(self, pts, color=TEAL[3], smooth=False, w=1):
        make = curve if smooth else line
        self.glow(make(pts, w), color)

    def star4(self, x, y, r=3, color=GOLD[4], core=GOLD[5], glow=True):
        """四芒星：竖臂和横臂渐尖。"""
        m = blank()
        for k in range(-r, r + 1):
            if 0 <= x + k < W and 0 <= y < H:
                m[y, x + k] = True
            if 0 <= y + k < H and 0 <= x < W:
                m[y + k, x] = True
        if r >= 3:
            m |= rect(x - 1, y - 1, x + 1, y + 1) & ((np.abs(XX - x) + np.abs(YY - y)) <= 1)
        (self.glow if glow else self.paint)(m, color)
        (self.glow_px if glow else self.pxs)([(x, y)], core)

    def infinity(self, cx, cy, a=9, b=4, w=1):
        """横向双环 ∞ 的蒙版，a 是半宽，b 是半高。cx 用半整数时严格对称。"""
        pts = []
        for i in range(160):
            t = 2 * np.pi * i / 160
            d = 1 + np.sin(t) ** 2
            pts.append((cx + a * np.cos(t) / d, cy + 2.83 * b * np.sin(t) * np.cos(t) / d))
        chain = [(round(x), round(y)) for x, y in pts + pts[:1]]
        filled = []
        for p, q in zip(chain, chain[1:]):
            filled += _segment(*p, *q)
        return _stamp(blank(), _perfect(filled), w)

    # 输出 ----------------------------------------------------------
    def art_image(self):
        return Image.fromarray(np.dstack([self.rgb, np.full((H, W), 255, np.uint8)]), 'RGBA')

    def glow_image(self):
        out = self.gl.copy()
        out[out[..., 3] < 255] = 0
        return Image.fromarray(out, 'RGBA')

    def stats(self):
        """牌面用色数、发光像素比例：给 lint 和自查用。"""
        colors = np.unique(self.rgb.reshape(-1, 3), axis=0).shape[0]
        glow_px = int((self.gl[..., 3] == 255).sum())
        return {'colors': colors, 'glow_pixels': glow_px, 'glow_ratio': round(glow_px / (W * H), 4)}
