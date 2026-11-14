"""弦乐组：小提琴、中提琴（手持 + 弓）、大提琴、低音提琴（方块，大提琴带琴凳）、灵猫竖琴。约定见 docs/INSTRUMENT_ART.md。

提琴一家的琴身做法（小提琴、中提琴、大提琴、低音提琴共用 bowed_body）：
- 轮廓是一条折线（下琴腰、C 腰、上琴腰），沿折线一段段立起绕 z 转过去的薄板，就是侧板（外侧面是虎纹枫木）；
  薄板朝前、朝后那条窄面是面板 / 背板的漆边，比面板高出一点点，盖住面板的边。
- 面板 / 背板是几条横排的长方体（每条只画正面、背面），两端都藏在侧板下面，所以轮廓是斜的、弯的，不是台阶。
- 面板、背板的贴图按世界坐标画（fill 在每一条上接得上）：云杉直纹 + 中间鼓起的明暗 + f 孔；背板是对称的虎纹。
"""
import math

import numpy as np

import instrument_kit as kit
from instrument_kit import cube, STARBLOOM_SMALL

NS = kit.NS
GAP = 0.06          # 有重叠的同向面之间错开的距离（比 kit.MIN_GAP 大一点：0.05 正好在检查的边界上，浮点误差会报错）
ALL = ("north", "south", "east", "west", "up", "down")

PREVIEW = [
    "item/hexen_violin", "item/hexen_violin_held", "item/hexen_violin_playing", "item/hexen_violin_bow",
    "item/hexen_viola", "item/hexen_viola_playing",
    "block/hexen_cello", "item/hexen_cello",
    "block/hexen_double_bass", "item/hexen_double_bass",
    "block/hexen_concert_harp", "item/hexen_concert_harp",
]

# —— 这一组自己的材质（新名字，不改 kit 里已有的）——
kit.PALETTE.setdefault("spruce_varnished", kit.ramp("#6e4220", "#8c5a2c", "#a8723b", "#c08b4c", "#d6a462"))   # 上过清漆的云杉面板（小提琴、大提琴）
kit.PALETTE.setdefault("spruce_amber", kit.ramp("#55301a", "#6f4024", "#88532f", "#9f663b", "#b67b4b"))       # 中提琴、低音提琴的面板（漆深一档）
kit.PALETTE.setdefault("horsehair", kit.ramp("#b9b09c", "#cdc5b1", "#ddd6c4", "#ebe5d6", "#f6f2e8"))           # 弓毛
kit.PALETTE.setdefault("gut", kit.ramp("#b49a6c", "#c8b083", "#d9c49b", "#e6d4b0", "#f2e5c8"))                 # 竖琴羊肠弦
kit.PALETTE.setdefault("string_red", kit.ramp("#5e1215", "#7c1b1f", "#99272a", "#b43a3a", "#cc5250"))         # C 弦（红）
kit.PALETTE.setdefault("string_navy", kit.ramp("#121a3a", "#1b2753", "#27386e", "#354a8a", "#4861a6"))        # F 弦（深蓝）
kit.PALETTE.setdefault("honey_maple", kit.ramp("#7c4f2a", "#9a6838", "#b5814a", "#cb9a5f", "#dfb47a"))         # 竖琴共鸣箱：上过油的枫木（蜂蜜色）
kit.MATERIALS.setdefault("flamed_honey", ("honey_maple", kit.flamed, 2.1))
kit.MATERIALS.setdefault("spruce_varnished", ("spruce_varnished", kit.fine_wood, 2.2))
kit.MATERIALS.setdefault("spruce_amber", ("spruce_amber", kit.fine_wood, 2.2))
kit.MATERIALS.setdefault("horsehair", ("horsehair", kit.fine_wood, 2.4))
kit.MATERIALS.setdefault("gut", ("gut", kit.flat, 2.6))
kit.MATERIALS.setdefault("string_red", ("string_red", kit.flat, 2.4))
kit.MATERIALS.setdefault("string_navy", ("string_navy", kit.flat, 2.6))
kit.BEVEL.setdefault("horsehair", 0.2)


# —— 小工具 ——

def keep(*faces, **specs):
    """只画列出的面：keep("north", "up", south={"mat": ...})。"""
    out = {f: None for f in ALL}
    for f in faces:
        out.pop(f)
    for f, spec in specs.items():
        out[f] = spec
    return out


def prism(c, apothem, lo, hi, mat, sheet, sides=8, axis="y", start=0.0, faces=None, **kw):
    """同 kit.polygon_prism，但条与条之间按 GAP 错开（kit 的 0.05 正好压在检查线上）。"""
    n = sides // 2
    half_w = apothem * math.tan(math.pi / sides)
    els = []
    for i in range(n):
        g = i * GAP
        ang = start + 180.0 / n * i
        a, b = c
        if axis == "y":
            frm, to = (a - apothem, lo + g, b - half_w), (a + apothem, hi - g, b + half_w)
            origin, rot = (a, (lo + hi) / 2, b), dict(x=0, y=ang, z=0)
            hidden, caps = ("north", "south"), {"up": "top", "down": "bottom"}
        elif axis == "x":
            frm, to = (lo + g, a - half_w, b - apothem), (hi - g, a + half_w, b + apothem)
            origin, rot = ((lo + hi) / 2, a, b), dict(x=ang, y=0, z=0)
            hidden, caps = ("up", "down"), {"east": "top", "west": "bottom"}
        else:
            frm, to = (a - apothem, b - half_w, lo + g), (a + apothem, b + half_w, hi - g)
            origin, rot = (a, b, (lo + hi) / 2), dict(x=0, y=0, z=ang)
            hidden, caps = ("up", "down"), {"south": "top", "north": "bottom"}
        fs = {h: None for h in hidden}
        for face, cap in caps.items():
            if faces and cap in faces:
                fs[face] = faces[cap]
        e = cube(frm, to, mat, sheet, grain=axis, faces=fs, **kw)
        if abs(ang % 360) > 1e-9:
            e["rotation"] = kit.rot_euler(origin=origin, **rot)
        els.append(e)
    return els


def zrot(e, angle, origin):
    """绕 z 轴转（欧拉角写法）。"""
    if abs(angle) > 1e-9:
        e["rotation"] = kit.rot_euler(z=angle, origin=origin)
    return e


def bar(p0, p1, w, h, mat, sheet, ext=0.0, **kw):
    """xy 平面里从 p0 到 p1 的一根杆（宽 w 在 xy 平面里、厚 h 沿 z，中心 z = p0[2]），绕 z 转过去。ext：两头各加长。"""
    x0, y0, z = p0
    x1, y1, _ = p1
    L = math.hypot(x1 - x0, y1 - y0)
    ang = math.degrees(math.atan2(y1 - y0, x1 - x0))
    e = cube((x0 - ext, y0 - w / 2, z - h / 2), (x0 + L + ext, y0 + w / 2, z + h / 2), mat, sheet, grain="x", **kw)
    return zrot(e, ang, (x0, y0, z))


def _col(pal, t):
    return kit._tone(kit.PALETTE[pal], np.asarray(t, dtype=np.float32))


def _hash(i, salt=0):
    """整数格 → −1..1 的确定性噪声（按世界坐标取，相邻元素的贴图接得上）。"""
    i = np.asarray(i, dtype=np.int64)
    h = (i * 374761393 + salt * 668265263) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return (h & 0xFFFF) / 32767.5 - 1.0


def world_paint(key, rect, face, fn):
    """按世界坐标画一个面：rect=(x0, x1, y0, y1)，face 是 "north"（u 朝 −x）或 "south"（u 朝 +x），v 都朝 −y。
    fn(X, Y, hx, hy) 返回 (h, w, 3) 颜色，X、Y 是每个贴图像素中心的世界坐标，hx、hy 是半个像素的世界长度。"""
    x0, x1, y0, y1 = rect

    def paint(img, w, h, rng):
        i = (np.arange(w) + 0.5) / w
        j = (np.arange(h) + 0.5) / h
        xs = x1 - i * (x1 - x0) if face == "north" else x0 + i * (x1 - x0)
        ys = y1 - j * (y1 - y0)
        X, Y = np.meshgrid(xs, ys)
        return fn(X, Y, (x1 - x0) / w / 2, (y1 - y0) / h / 2)
    return kit.custom(key, paint)


# —— 提琴琴身 ——

class Outline:
    """右半边轮廓 chain=[(dx, y), ...]（从下到上，dx 是离中线的距离），整条轮廓左右对称。"""

    def __init__(self, cx, chain):
        self.cx, self.chain = cx, chain
        self.ys = np.array([y for _, y in chain])
        self.dxs = np.array([d for d, _ in chain])

    def hw(self, y):
        return np.interp(y, self.ys, self.dxs)

    def polygon(self):
        cx, ch = self.cx, self.chain
        # 逆时针：右下角 → 右边往上 → 顶边 → 左边往下 → 左下角（再接回右下角是底边）
        return [(cx + d, y) for d, y in ch] + [(cx - d, y) for d, y in reversed(ch)]

    def edges_right(self):
        """右半边的边（含底边、顶边的右半段），算距离用。"""
        ch = self.chain
        segs = [((0.0, ch[0][1]), ch[0]), ((0.0, ch[-1][1]), ch[-1])]
        segs += [(ch[i], ch[i + 1]) for i in range(len(ch) - 1)]
        return segs

    def dist(self, d, y):
        best = 1e9
        for (ax, ay), (bx, by) in self.edges_right():
            vx, vy = bx - ax, by - ay
            t = max(0.0, min(1.0, ((d - ax) * vx + (y - ay) * vy) / max(1e-12, vx * vx + vy * vy)))
            best = min(best, math.hypot(d - ax - t * vx, y - ay - t * vy))
        return best


def fill_rows(ol, band, step=0.05):
    """面板 / 背板的横条：每条 [ya, yb] 半宽 w，四个角都落在侧板（宽 band）下面，条与条首尾相接。"""
    y_lo, y_hi = ol.ys[0] + band * 0.5, ol.ys[-1] - band * 0.5
    rows, a = [], y_lo
    while a < y_hi - 1e-6:
        best, b = None, a + step
        while b <= y_hi + 1e-9:
            ys = np.linspace(a, b, 16)
            w = float(min(ol.hw(y) for y in ys)) - 0.03
            if all(ol.dist(w, y) <= band * 0.82 for y in ys):
                best = (b, w)
            elif best is not None:
                break
            b += step
        assert best is not None, f"row from {a:.2f} cannot be fitted"
        rows.append((float(a), float(best[0]), best[1]))
        a = best[0]
    # 收尾那条太短：去掉也盖得住（顶边的侧板够宽）就去掉
    if len(rows) > 1 and rows[-1][1] - rows[-1][0] < 0.3 and check_cover(ol, band, rows[:-1]) == 0:
        rows.pop()
    return rows


def bowed_body(s, ol, z0, z1, band, mats, top_fn, back_fn, tag):
    """提琴琴身：侧板（绕 z 转的薄板）+ 面板 / 背板横条。mats: rib（侧板外侧）、edge（漆边）。"""
    P = ol.polygon()
    P.append(P[0])
    segs = [(P[i], P[i + 1]) for i in range(len(P) - 1)]
    m = len(segs)

    def direction(seg):
        (ax, ay), (bx, by) = seg
        L = math.hypot(bx - ax, by - ay)
        return (bx - ax) / L, (by - ay) / L, L

    el = []
    zc = (z0 + z1) / 2
    for i, seg in enumerate(segs):
        (px, py), _ = seg
        dx, dy, L = direction(seg)
        pdx, pdy, _ = direction(segs[i - 1])
        ndx, ndy, _ = direction(segs[(i + 1) % m])
        turn_in = pdx * dy - pdy * dx
        turn_out = dx * ndy - dy * ndx
        # 凹进去的拐角（C 腰）两段各加长一点，内侧不留缺口；凸出去的拐角不加长（外侧会冒尖）
        e0 = band * math.tan(math.asin(min(1.0, abs(turn_in))) / 2) if turn_in < -1e-6 else 0.0
        e1 = band * math.tan(math.asin(min(1.0, abs(turn_out))) / 2) if turn_out < -1e-6 else 0.0
        lvl = i % 2 if not (m % 2 and i == m - 1) else 2
        za, zb = z0 + GAP * lvl, z1 - GAP * lvl
        # 漆边：正面用面板的料压暗一档（像镶线），背面用背板的料压暗一档
        front = {"mat": mats["top"], "bevel": 0, "tone": mats.get("edge_tone", 1.2), "grain": "x"}
        back = {"mat": mats["back"], "bevel": 0, "tone": mats.get("edge_tone", 1.2)}
        e = cube((px - e0, py, za), (px + L + e1, py + band, zb), mats["rib"], s, grain="x",
                 faces=keep(down={"mat": mats["rib"], "bevel": 0}, north=front, south=back), seed=i % 3)
        el.append(zrot(e, math.degrees(math.atan2(dy, dx)), (px, py, zc)))
    rows = fill_rows(ol, band)
    for k, (ya, yb, w) in enumerate(rows):
        x0, x1 = ol.cx - w, ol.cx + w
        rect = (x0, x1, ya, yb)
        front = {"mat": mats["top"], "bevel": 0, "deco": world_paint((tag, "top", k, rect), rect, "north", top_fn)}
        back = {"mat": mats["back"], "bevel": 0, "deco": world_paint((tag, "back", k, rect), rect, "south", back_fn)}
        el.append(cube((x0, ya, z0 + 2 * GAP), (x1, yb, z1 - 2 * GAP), mats["top"], s, grain="y",
                       faces=keep(north=front, south=back)))
    return el, rows


def check_cover(ol, band, rows, n=60):
    """自查：轮廓里的每一点都被侧板或面板横条盖住，横条没有伸出轮廓。返回漏掉的点数。"""
    miss = 0
    ys = np.linspace(ol.ys[0] + 0.01, ol.ys[-1] - 0.01, n)
    for y in ys:
        hw = float(ol.hw(y))
        for d in np.linspace(0, hw - 0.01, n // 2):
            if ol.dist(d, y) <= band * 0.98:
                continue
            if not any(ya - 1e-6 <= y <= yb + 1e-6 and d <= w + 1e-6 for ya, yb, w in rows):
                miss += 1
    return miss


def plate_top(ol, pal, fholes, band, base=2.3):
    """面板：云杉直纹（按世界 x 取，条与条对得上）、中间鼓起来亮一点、两侧 f 孔。
    fholes：右边那只 f 孔的折线 [(dx, y), ...]（从上到下），左边镜像。每一行贴图像素只点一个：
    那一格露在漆边外面的部分不到 0.25 像素时往里挪一格（看不见的 f 孔没意义）。"""
    cx = ol.cx
    hole = _col("rosewood", 0.9)
    fy = np.array([y for _, y in fholes])[::-1]
    fx = np.array([d for d, _ in fholes])[::-1]

    def fn(X, Y, hx, hy):
        d = X - cx
        r = np.clip(np.abs(d) / np.maximum(0.5, ol.hw(Y)), 0, 1)
        col = np.floor(d * 1.0 + 0.5)
        t = base - 0.35 + 0.75 * (1 - r ** 2) + 0.16 * _hash(col, 3) + 0.06 * _hash(col * 7 + np.floor(Y * 1.3), 5)
        out = _col(pal, t)
        for j in range(X.shape[0]):
            y = Y[j, 0]
            if not fy[0] - hy * 0.5 <= y <= fy[-1] + hy * 0.5:
                continue
            want = float(np.interp(y, fy, fx))
            inner = float(ol.hw(y)) - band          # 漆边内缘
            for sgn in (1, -1):
                dd = sgn * d[j]
                cand = [i for i in range(X.shape[1]) if dd[i] > 0 and abs(dd[i] - want) <= hx + 1e-6]
                if not cand:
                    continue
                i = cand[0]
                if inner - (dd[i] - hx) < 0.25:      # 这一格几乎全在漆边底下：往里挪一格
                    inward = [k for k in range(X.shape[1]) if 0 < dd[k] < dd[i] - 1e-6]
                    if not inward:
                        continue
                    i = max(inward, key=lambda k: dd[k])
                out[j, i] = hole
        return out
    return fn


def plate_back(ol, pal, base=2.3, star=None):
    """背板：两片对拼的虎纹枫木（虎纹从中缝往两边斜下去）、中间鼓起；star=(y, 色阶, 图案) 在中缝镶一朵星辰花（暗金）。"""
    cx = ol.cx
    gilt = kit.PALETTE["gilt"]

    def fn(X, Y, hx, hy):
        d = X - cx
        r = np.clip(np.abs(d) / np.maximum(0.5, ol.hw(Y)), 0, 1)
        t = base - 0.3 + 0.6 * (1 - r ** 2) + 0.5 * np.sin(1.3 * Y - 0.55 * np.abs(d)) + 0.05 * _hash(np.floor(d + 0.5), 9)
        out = _col(pal, t)
        if star:
            # 图案直接按贴图像素印（贴图像素不一定正好 1 个单位，按世界坐标取样会把小图案吃掉）：
            # 以 (cx, sy) 所在的那个像素为中心，只印在包含它的那一条上（位置要选在一条横条里放得下的地方）
            sy, tone, pat = star
            js = [j for j in range(X.shape[0]) if abs(Y[j, 0] - sy) <= hy + 1e-6]
            is_ = [i for i in range(X.shape[1]) if abs(X[0, i] - cx) <= hx + 1e-6]
            if js and is_:
                jc, ic, n = js[0], max(is_, key=lambda i: X[0, i]), len(pat)
                for j, row in enumerate(pat):
                    for i, c in enumerate(row):
                        jj, ii = jc + j - n // 2, ic + i - n // 2
                        if c == "#" and 0 <= jj < X.shape[0] and 0 <= ii < X.shape[1]:
                            out[jj, ii] = kit._tone(gilt, np.array(tone - 0.25 * j / max(1, n - 1)))
        return out
    return fn


# —— 小提琴、中提琴 ——

# 小提琴在物品坐标里：竖着、正面（面板）朝北（−z），腮托那头在下（y≈1），琴头在上（y≈16）。
# HeldRig.bowedUnderChin 的支点 (8, 1, 9)、托琴颈 (8, 13, 11)、弓触弦点 (8, 5, 6.4) 都按这个摆法。
VIOLIN_CX = 8.0
VIOLIN_OUTLINE = Outline(VIOLIN_CX, [(1.86, 1.0), (2.7, 1.6), (3.0, 2.72), (2.85, 3.75), (2.37, 4.48), (1.8, 4.87),
                                     (1.8, 6.16), (2.28, 6.55), (2.43, 7.79), (1.98, 9.0), (1.14, 9.6)])
VIOLIN_Z = (7.0, 9.0)
VIOLIN_BAND = 0.5
VIOLIN_FHOLES = [(1.0, 5.55), (1.25, 4.7)]      # 小提琴太小：f 孔只有琴马上方一像素宽的一道

KINDS = {
    "violin": {"sheet": "hexen_violin", "rib": "flamed_varnish", "edge": "varnish", "top": "spruce_varnished",
               "back": "flamed_varnish", "back_pal": "varnish", "top_pal": "spruce_varnished", "wood": "flamed_varnish"},
    "viola": {"sheet": "hexen_viola", "rib": "flamed_amber", "edge": "amber", "top": "spruce_amber",
              "back": "flamed_amber", "back_pal": "amber", "top_pal": "spruce_amber", "wood": "flamed_amber"},
}


def violin_held(s, kind):
    """不带弓的小提琴 / 中提琴（同一套几何，漆色不同）。"""
    k = KINDS[kind]
    ol = VIOLIN_OUTLINE
    mats = {"rib": k["rib"], "edge": k["edge"], "top": k["top"], "back": k["back"], "edge_tone": 1.1}
    el, _ = bowed_body(s, ol, *VIOLIN_Z, VIOLIN_BAND, mats,
                       plate_top(ol, k["top_pal"], VIOLIN_FHOLES, VIOLIN_BAND, base=2.2),
                       plate_back(ol, k["back_pal"], base=2.3), ("violin_body", kind))
    wood = k["wood"]
    plate = VIOLIN_Z[0] + 2 * GAP     # 面板正面
    # 指板（乌木）悬在面板上，琴颈在它后面
    el.append(cube((7.35, 5.75, 6.6), (8.65, 13.0, 7.0), "ebony", s, grain="y", faces=keep("north", "east", "west", "up", "down")))
    el.append(cube((7.42, 9.6, 7.0), (8.58, 13.0, 8.3), wood, s, grain="y", faces=keep("south", "east", "west")))
    # 弦轴箱（前面是开口）、琴头涡卷（八角柱，侧面看是圆的）
    el.append(cube((7.38, 13.0, 7.0), (8.62, 14.9, 8.4), wood, s, grain="y",
                   faces=keep("south", "east", "west", north={"mat": k["edge"], "tone": 0.7})))
    el += prism((15.35, 7.75), 0.68, 7.45, 8.55, wood, s, sides=8, axis="x", start=22.5)
    # 弦轴：两根横穿弦轴箱的轴，四个轴头
    for y in (13.45, 14.25):
        el.append(cube((6.75, y, 7.55), (9.25, y + 0.35, 7.9), "ebony", s, grain="x", faces=keep("north", "south", "up", "down")))
        for x0, x1 in ((6.4, 6.8), (9.2, 9.6)):
            el.append(cube((x0, y - 0.15, 7.4), (x1, y + 0.5, 8.05), "ebony", s, grain="y", bevel=0.5))
    # 琴马（枫木）、拉弦板（乌木）、腮托（玫瑰木，在面板上、拉弦板左边）
    el.append(cube((6.95, 4.15, 6.56), (9.05, 4.45, plate), "maple", s, grain="x", faces=keep("north", "east", "west", "up", "down")))
    el.append(cube((7.3, 1.45, 6.62), (8.7, 3.4, plate), "ebony", s, grain="y", faces=keep("north", "east", "west", "up", "down")))
    el.append(cube((5.5, 1.15, 6.8), (7.1, 2.25, plate), "rosewood", s, grain="x", faces=keep("north", "east", "west", "up", "down")))
    # 四根钢弦：从拉弦板经琴马到琴枕
    for x in (7.55, 7.85, 8.15, 8.45):
        el.append(cube((x - 0.045, 3.3, 6.47), (x + 0.045, 13.0, 6.55), "steel", s, grain="y", faces=keep("north", "east", "west")))
    return el


def bow_elements(s):
    """琴弓（物品坐标）：弓杆沿 +y、在 x≈7.85，弓毛在 +x 一侧、外面 x = 8.35（HeldRig 的弓毛线），弓根在 y 1–2.6、握点 (5.1, 1.8)。
    弓杆是深色的伯南布哥木（直的：弓杆的弧度在这个尺寸看不出来），银色尾螺丝和缠线，乌木弓根上一颗珍珠眼，弓头是象牙色的尖片。"""
    el = []
    el.append(cube((7.71, 0.55, 7.83), (8.05, 15.1, 8.17), "rosewood", s, grain="y", faces=keep("north", "south", "east", "west")))
    el.append(cube((7.6, 0.2, 7.76), (8.12, 0.6, 8.24), "silver", s, grain="y"))                                  # 尾端螺丝
    el.append(cube((7.65, 2.6, 7.77), (8.11, 3.6, 8.23), "silver", s, grain="y", faces=keep("north", "south", "west", "up", "down")))  # 缠线
    pearl = kit.pixels([(0, 0)], "ivory", 3.0)
    el.append(cube((8.0, 1.0, 7.68), (8.42, 2.6, 8.32), "ebony", s, grain="y",
                   faces=keep("east", "up", "down", north={"mat": "ebony", "deco": pearl}, south={"mat": "ebony", "deco": pearl})))
    el.append(cube((8.17, 2.6, 7.84), (8.35, 15.0, 8.16), "horsehair", s, grain="y", faces=keep("east", "north", "south", "west")))
    el.append(cube((7.62, 15.0, 7.76), (8.38, 15.75, 8.24), "rosewood", s, grain="y", faces=keep("north", "south", "up", "west", east="ivory")))
    return el


def _violin_family(out, kind):
    k = KINDS[kind]
    s = out.sheet(k["sheet"])
    held = violin_held(s, kind)
    if kind == "violin":
        bow = bow_elements(s)
        sheets = [s]
    else:
        v = violin_family(out, "violin")
        bow = v["bow"]
        sheets = [s, v["sheets"][0]]
    # 物品：弓竖着靠在琴的左后方（同旧模型）
    item = held + kit.shifted(bow, -5.2, 0, 1.6)
    # 第一人称演奏：弓横搭在琴马和指板之间的弦上，弓毛贴着弦，弓根朝惯用手（−x）
    # 弓的 +y → 琴的 +x，弓的 +x（弓毛）→ 琴的 +z（朝弦）。这个转动正好在欧拉角的万向锁上（y = −90°），
    # kit.rigid 拆不出来，直接写欧拉角 (−90, −90, 0)（弓的元素本身都没有转动）
    across = []
    for e in bow:
        assert "rotation" not in e
        e = dict(e, rotation=kit.rot_euler(-90, -90, 0, origin=(8.35, 8.0, 8.0)))
        across.append(e)
    across = kit.shifted(across, -3.05, 4.9 - 8.0, 6.45 - 8.0)
    playing = held + across
    return {"item": item, "held": held, "playing": playing, "bow": bow, "sheets": sheets}


def violin_family(out, kind):
    """小提琴 / 中提琴一家的元素与贴图页（同一个 Out 里只造一次）。乐谱架那边（winds）也调它。
    返回 {"item", "held", "playing", "bow", "sheets"}：item 带弓（物品栏、手里），held 不带弓（HeldRig 画在身上），
    playing 是第一人称演奏（弓横搭在弦上），bow 是单独的弓（三种弓弦乐器共用，贴图在小提琴页上）。"""
    return out.memo(("violin_family", kind), lambda: _violin_family(out, kind))


def held_model(out, name, el, sheets, budget):
    """没有显示变换的物品模型（items/held/ 用：HeldRig、乐谱架画）。"""
    kit.lint(f"item/{name}", el, budget)
    tex = {s.name: s.texture for s in sheets} | {"particle": sheets[0].texture}
    out.raw(kit.ASSETS / f"models/item/{name}.json", {"textures": tex, "elements": el})


def mirror_hands(d):
    for side in ("thirdperson", "firstperson"):
        r = d.get(f"{side}_righthand")
        if r:
            d[f"{side}_lefthand"] = {"rotation": [r["rotation"][0], -r["rotation"][1], -r["rotation"][2]],
                                     "translation": [-r["translation"][0], r["translation"][1], r["translation"][2]],
                                     "scale": r["scale"]}
    return d


def held_display(el, gui_rot, hands, gui=15.0, ground=7.0, fixed=13.0):
    """手持乐器：物品栏 / 地上 / 展示框按 kit.fit 算；第一 / 第三人称用调好的旧值（hands）。"""
    gs, gt = kit.fit(el, gui_rot, gui)
    rs, rt = kit.fit(el, (0, 0, 0), ground, depth=True)
    fs, ft = kit.fit(el, (0, 180, 0), fixed)
    d = {"gui": {"rotation": list(gui_rot), "translation": gt, "scale": [gs] * 3},
         "ground": {"rotation": [0, 0, 0], "translation": [rt[0], round(rt[1] + 2.0, 3), 0], "scale": [rs] * 3},
         "fixed": {"rotation": [0, 180, 0], "translation": ft, "scale": [fs] * 3}}
    d.update(hands)
    return mirror_hands(d)


VIOLIN_HANDS = {
    "violin": {"firstperson_righthand": {"rotation": [0, 100, 15], "translation": [1, 2, 0], "scale": [0.5] * 3},
               "thirdperson_righthand": {"rotation": [0, 90, -20], "translation": [0, 1, 1], "scale": [0.55] * 3}},
    "viola": {"firstperson_righthand": {"rotation": [0, 100, 15], "translation": [1, 2, 0], "scale": [0.5] * 3},
              "thirdperson_righthand": {"rotation": [0, 90, -20], "translation": [0, 1.5, 1], "scale": [0.62] * 3}},
}
VIOLIN_PLAYING = {
    "violin": {"firstperson_righthand": {"rotation": [109, -3.5, 143], "translation": [-8, 5.5, -2], "scale": [0.6] * 3}},
    "viola": {"firstperson_righthand": {"rotation": [109, -3.5, 143], "translation": [-8, 5.5, -2], "scale": [0.66] * 3}},
}
VIOLIN_GUI = (20, 200, -30)        # 物品栏里斜着放（琴头朝左上），比竖着大一圈


def build_violins(out):
    for kind in ("violin", "viola"):
        f = violin_family(out, kind)
        name = f"hexen_{kind}"
        out.item_model(name, f["item"], f["sheets"], "held", held_display(f["item"], VIOLIN_GUI, VIOLIN_HANDS[kind]))
        out.item_model(f"{name}_playing", f["playing"], f["sheets"], "held", held_display(f["playing"], VIOLIN_GUI, VIOLIN_PLAYING[kind]))
        held_model(out, f"{name}_held", f["held"], f["sheets"][:1], "held")
        if kind == "violin":
            held_model(out, "hexen_violin_bow", f["bow"], f["sheets"], "tool")


# —— 大提琴、低音提琴 ——

# 琴在自己的坐标里竖着画（琴脚尖在原点，y 沿琴身向上，x 向东，z 朝琴背，正面朝北），再按 SEATED 后仰、左倾，
# 琴脚尖放到 endpin（同 tools/instrument_art.py 的 SEATED 和 HeldRig.CELLO / BASS，数一个都不改）。
SEATED = {
    "hexen_cello": {"endpin": (8.8, 0, 2.1), "lean": 25, "tilt": 16, "contact": (0, 11.5, -2.5), "neck_z": -1.0,
                    "neck": (12.5, 21.5), "feet": (8, -2.25, 12.5), "bow": 0.85},
    "hexen_double_bass": {"endpin": (7, 0, 3.2), "lean": 14, "tilt": 10, "contact": (0, 13.6, -3.4), "neck_z": -1.5,
                          "neck": (15.0, 26.5), "feet": (8, 0, 11.5), "bow": 0.8},
}


def seated(name, local):
    s = SEATED[name]
    return kit.rigid(kit.shifted(local, *s["endpin"]), kit.euler(s["lean"], 0, s["tilt"]), s["endpin"])


def big_bowed(s, spec, tag):
    """大提琴 / 低音提琴（局部坐标）。spec 里是这件琴的尺寸。"""
    ol = Outline(0.0, spec["outline"])
    z0, z1 = spec["z"]
    band = spec["band"]
    mats = {"rib": spec["wood"], "edge": spec["edge"], "top": spec["top"], "back": spec["wood"], "edge_tone": 1.1}
    el, rows = bowed_body(s, ol, z0, z1, band, mats, plate_top(ol, spec["top_pal"], spec["fholes"], band, base=2.2),
                          plate_back(ol, spec["back_pal"], base=2.3, star=spec.get("star")), tag)
    plate = z0 + 2 * GAP
    wood = spec["wood"]
    fb_x, (fb_y0, nut), (fb_z0, fb_z1) = spec["fingerboard"]
    # 尾柱（钢）和尾柱座（乌木）
    ep_r, ep_top = spec["endpin"]
    el.append(cube((-ep_r, 0, -ep_r), (ep_r, ep_top - 0.3, ep_r), "steel", s, grain="y", faces=keep("north", "south", "east", "west", "down")))
    el.append(cube((-0.5, ep_top - 0.4, -0.5), (0.5, ol.ys[0] + 0.25, 0.5), "ebony", s, grain="y", faces=keep("north", "south", "east", "west", "down")))
    # 指板、琴颈、弦轴箱、涡卷
    el.append(cube((-fb_x, fb_y0, fb_z0), (fb_x, nut, fb_z1), "ebony", s, grain="y", faces=keep("north", "east", "west", "up", "down")))
    nx, (nz0, nz1) = spec["neck"]
    body_top = ol.ys[-1]
    el.append(cube((-nx, body_top - 0.2, fb_z1), (nx, nut, nz1), wood, s, grain="y", faces=keep("south", "east", "west")))
    (px, (py0, py1), (pz0, pz1)) = spec["pegbox"]
    el.append(cube((-px, nut, pz0), (px, py1, pz1), wood, s, grain="y",
                   faces=keep("south", "east", "west", north={"mat": "shadow", "tone": 1.4})))
    (sy, sz, sa, sx) = spec["scroll"]
    el += prism((sy, sz), sa, -sx, sx, wood, s, sides=8, axis="x", start=22.5)
    # 弦轴（低音提琴是黄铜的机械弦钮）
    pm, pw, ph = spec["peg_mat"], spec["peg_span"], spec["peg_size"]
    for y in spec["pegs"]:
        el.append(cube((-pw, y, pz0 + 0.35), (pw, y + ph, pz0 + 0.35 + ph), pm, s, grain="x", faces=keep("north", "south", "up", "down")))
        hs = spec["peg_head"]
        for x0, x1 in ((-pw - hs, -pw + 0.05), (pw - 0.05, pw + hs)):
            el.append(cube((x0, y - hs / 2 + ph / 2, pz0 + 0.35 + ph / 2 - hs / 2), (x1, y + hs / 2 + ph / 2, pz0 + 0.35 + ph / 2 + hs / 2),
                           pm, s, grain="y", bevel=0.5))
    # 琴马、拉弦板
    (bx, (by0, by1), bz) = spec["bridge"]
    el.append(cube((-bx, by0, bz), (bx, by1, plate), "maple", s, grain="x", faces=keep("north", "east", "west", "up", "down")))
    (tx, (ty0, ty1), tz) = spec["tailpiece"]
    el.append(cube((-tx, ty0, tz), (tx, ty1, plate), "ebony", s, grain="y", faces=keep("north", "east", "west", "up", "down")))
    # 四根弦
    (sxs, (sy0, sy1), (sz0, sz1), sw) = spec["strings"]
    for x in sxs:
        el.append(cube((x - sw / 2, sy0, sz0), (x + sw / 2, sy1, sz1), "steel", s, grain="y", faces=keep("north", "east", "west")))
    return el, ol, rows


CELLO = {
    "outline": [(2.23, 5.0), (3.24, 5.88), (3.6, 7.5), (3.42, 9.0), (2.84, 10.06), (2.16, 10.63), (2.16, 12.5),
                (2.74, 13.06), (2.92, 14.88), (2.38, 16.63), (1.37, 17.5)],
    "z": (-1.2, 1.2), "band": 0.55,
    "wood": "flamed_varnish", "edge": "varnish", "top": "spruce_varnished", "top_pal": "spruce_varnished", "back_pal": "varnish",
    "fholes": [(1.1, 12.45), (1.2, 12.1), (1.35, 11.0), (1.55, 9.9), (1.7, 9.6)], "star": (14.7, 2.5, STARBLOOM_SMALL),
    "endpin": (0.18, 5.0),
    "fingerboard": (0.78, (12.2, 21.8), (-2.2, -1.4)),
    "neck": (0.62, (-1.4, 0.3)),
    "pegbox": (0.66, (21.8, 24.2), (-1.25, 0.45)),
    "scroll": (24.9, -0.35, 0.82, 0.58),
    "pegs": (22.3, 23.3), "peg_span": 1.25, "peg_size": 0.4, "peg_head": 0.7, "peg_mat": "ebony",
    "bridge": (1.3, (10.5, 10.9), -2.33),
    "tailpiece": (0.8, (5.6, 9.7), -1.65),
    "strings": ((-0.45, -0.15, 0.15, 0.45), (9.6, 21.8), (-2.46, -2.36), 0.1),
}

BASS = {
    "outline": [(3.12, 3.0), (4.58, 4.05), (5.2, 6.5), (4.94, 8.78), (4.16, 10.35), (3.43, 11.05), (3.43, 13.15),
                (3.95, 13.85), (4.1, 15.4), (3.75, 16.8), (2.4, 18.9), (1.25, 20.5)],
    "z": (-1.6, 1.6), "band": 0.6,
    "wood": "flamed_amber", "edge": "amber", "top": "spruce_amber", "top_pal": "spruce_amber", "back_pal": "amber",
    "fholes": [(1.95, 14.6), (2.05, 13.6), (2.2, 12.2), (2.35, 10.8), (2.55, 10.4)], "star": (7.55, 2.4, STARBLOOM_SMALL),
    "endpin": (0.22, 3.2),
    "fingerboard": (0.92, (15.0, 26.9), (-3.0, -2.0)),
    "neck": (0.78, (-2.0, 0.2)),
    "pegbox": (0.8, (26.9, 29.0), (-1.85, 0.4)),
    "scroll": (29.75, -0.8, 0.85, 0.7),
    "pegs": (27.3, 28.2), "peg_span": 1.65, "peg_size": 0.4, "peg_head": 0.75, "peg_mat": "brass",
    "bridge": (1.65, (11.6, 12.1), -3.24),
    "tailpiece": (1.1, (4.4, 10.5), -2.05),
    "strings": ((-0.6, -0.2, 0.2, 0.6), (10.4, 26.9), (-3.36, -3.25), 0.12),
}


def stool(s):
    """大提琴手的小凳（方块坐标，琴后面）：胡桃木腿、金色脚套、牙板上一圈金线，酒红丝绒坐垫，坐面高 7.1。"""
    el = []
    for x, z in ((4.6, 10.2), (10.4, 10.2), (4.6, 14.6), (10.4, 14.6)):
        el.append(cube((x, 0.55, z), (x + 1, 5.4, z + 1), "walnut", s, grain="y", faces=keep("north", "south", "east", "west")))
        el.append(cube((x - 0.1, 0, z - 0.1), (x + 1.1, 0.55, z + 1.1), "gilt", s, faces=keep("north", "south", "east", "west", "up")))
    for z in (10.45, 14.85):
        el.append(cube((5.6, 2.0, z), (10.4, 2.5, z + 0.5), "walnut", s, grain="x", faces=keep("north", "south", "up", "down")))
    apron = {"mat": "walnut", "deco": kit.frame_line("gilt", 0, 3.0)}
    el.append(cube((4.2, 5.4, 9.8), (11.8, 6.2, 16.0), "walnut", s, grain="x",
                   faces=keep("down", "up", north=apron, south=apron, east=apron, west=apron)))
    buttons = kit.pixels([(1, 1), (5, 1), (1, 3), (5, 3)], "gilt", 2.9)
    el.append(cube((4.4, 6.2, 10.0), (11.6, 6.75, 15.8), "velvet", s, faces=keep("north", "south", "east", "west")))
    el.append(cube((4.75, 6.75, 10.35), (11.25, 7.1, 15.45), "velvet", s, seed=1,
                   faces=keep("north", "south", "east", "west", up={"mat": "velvet", "deco": buttons})))
    return el


def build_seated(out):
    for name, spec in (("hexen_cello", CELLO), ("hexen_double_bass", BASS)):
        s = out.sheet(name)
        local, ol, rows = big_bowed(s, spec, (name, "body"))
        assert check_cover(ol, spec["band"], rows) == 0, f"{name}: body not covered"
        el = seated(name, local)
        if name == "hexen_cello":
            el += stool(s)
        else:
            ex, _, ez = SEATED[name]["endpin"]
            el.append(cube((ex - 0.9, 0, ez - 0.9), (ex + 0.9, 0.3, ez + 0.9), "lacquer_black", s, faces=keep("north", "south", "east", "west", "up")))
        out.block_model(name, el, [s], "block")
        out.item_parent(name, name, kit.display(el, gui_rot=(25, 160, 0)))
        out.particle_only(f"{name}_part", s.texture)


# —— 灵猫竖琴 ——

# 方块坐标（朝北放置）：下半格是主块，模型画满 y 0–32。下面先按琴身居中画（琴弦在 z = 8 的平面里，x 5.9–13），
# 画完整架往演奏者那一侧（南）挪 HARP_SHIFT：琴弦落在 z = 13，占地 x 1–15、z 9–16（ConcertHarpBlock 的碰撞箱；
# 底座和踏板伸出格子 0.4–1.5 像素，在琴凳前沿之前）。琴凳上的人肩膀离格子正中的弦约 13.5 像素，森林精灵、神狐的手臂
# 只有 7.5 像素左右，挪近了 MusicianPoses.harp 才能把手放在弦上（那边的 HARP_STRINGS_Z 同这里）。立柱在左（x≈3.5），
# 共鸣箱从立柱脚斜着升到右上，琴颈是一条 S 形的「和声曲线」从柱冠连到共鸣箱顶。
HARP_SHIFT = 5.0                   # 整架往南挪多少像素
HARP_COLUMN = (3.5, 8.0)            # 立柱中心 (x, z)，挪之前
HARP_COLUMN_MAT = "gilt"           # 立柱的料：金（按任务说明）；维护者想压金色比例可以改成 "marble"
HARP_BOARD = ((5.6, 2.8), (13.2, 23.6))   # 音板正面那条线（下端在底座上、上端在琴颈下）
HARP_NECK = [(4.4, 27.0), (6.4, 26.3), (8.4, 25.8), (10.2, 25.95), (11.9, 26.35), (13.3, 25.95), (14.6, 24.7)]
HARP_NECK_T = 2.0                  # 琴颈的厚（y）


def catmull(pts, n):
    """过控制点的 Catmull-Rom 曲线，均匀取 n+1 个点。"""
    p = [pts[0]] + list(pts) + [pts[-1]]
    out = []
    segs = len(pts) - 1
    for k in range(n + 1):
        t = k / n * segs
        i = min(int(t), segs - 1)
        u = t - i
        p0, p1, p2, p3 = (np.array(p[i + j], float) for j in range(4))
        out.append(tuple(0.5 * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u + (-p0 + 3 * p1 - 3 * p2 + p3) * u ** 3)))
    return out


def neck_dots():
    """琴颈侧面：下沿一排黄铜小圆片（调音盘、弦钉），隔一个像素一颗。"""
    brass = kit.PALETTE["brass"]

    def fn(img, w, h, rng):
        for x in range(1, w - 1, 2):
            img[h - 1, x] = kit._tone(brass, np.array(3.3))
        return img
    return kit.custom("harp_neck_dots", fn)


def harp_elements(s):
    el = []
    cx, cz = HARP_COLUMN
    # —— 底座：胡桃木台座、金色脚、朝演奏者（南）一侧七个踏板，台座南面画踏板槽 ——
    pedals = [4.0 + i * 1.2 for i in range(7)]
    slot = _col("shadow", 2.6)

    def plinth_south(X, Y, hx, hy):
        t = 2.0 + 0.15 * _hash(np.floor(Y * 3), 2) + 0.1 * _hash(np.floor(X * 0.5), 4)
        out = _col("walnut", t)
        for x in pedals:
            out[(np.abs(X - (x + 0.25)) <= hx) & (np.abs(Y - 1.3) <= hy * 1.01)] = slot
        return out
    south = {"mat": "walnut", "bevel": 0, "deco": world_paint(("harp_plinth_s",), (2.4, 13.6, 0.6, 2.3), "south", plinth_south)}
    north = {"mat": "walnut", "bevel": 0.4}
    el.append(cube((2.4, 0.6, 4.6), (13.6, 2.3, 11.4), "walnut", s, grain="x",
                   faces=keep("east", "west", north=north, south=south)))
    el.append(cube((2.2, 2.3, 4.4), (13.8, 2.8, 11.6), "gilt", s, grain="x", faces=keep("north", "south", "east", "west", "down", up="walnut_light")))
    for x in (2.6, 12.0):
        for z in (4.8, 9.8):
            el.append(cube((x, 0, z), (x + 1.4, 0.6, z + 1.4), "gilt", s, faces=keep("north", "south", "east", "west")))
    for x in pedals:
        el.append(cube((x, 0.95, 11.4), (x + 0.5, 1.35, 12.5), "brass", s, grain="z", faces=keep("south", "east", "west", "up", "down")))
    # —— 立柱：八角柱、柱础、柱颈环，顶上方的柱冠（灵猫坐在上面）——
    col = HARP_COLUMN_MAT
    el += prism((cx, cz), 1.35, 2.8, 3.9, "gilt", s, sides=8, start=22.5)
    el += prism((cx, cz), 0.92, 3.9, 25.5, col, s, sides=8, start=22.5, tone=2.5 if col == "gilt" else None)
    el += prism((cx, cz), 1.3, 25.5, 26.3, "gilt", s, sides=8, start=22.5, seed=1)
    el.append(cube((cx - 1.6, 26.3, cz - 1.6), (cx + 1.6, 28.0, cz + 1.6), "gilt", s, deco=kit.frame_line("gilt", 0, 1.7)))
    # —— 灵猫（白色大理石纹，金眼、金耳内、金项圈），面朝北坐着，尾巴绕过柱冠垂到前面 ——
    m = "marble"
    el.append(cube((cx - 1.3, 28.0, cz - 0.4), (cx + 1.3, 29.8, cz + 1.9), m, s, faces=keep("north", "south", "east", "west", "up")))   # 后腿、身子
    el.append(cube((cx - 1.0, 28.0, cz - 1.1), (cx + 1.0, 30.2, cz), m, s, seed=1, faces=keep("north", "east", "west", "up")))           # 胸
    el.append(cube((cx - 0.9, 28.0, cz - 1.55), (cx + 0.9, 28.45, cz - 1.1), m, s, seed=2,
                   faces=keep("east", "west", "up", north={"mat": m, "deco": kit.custom("harp_paws", _paw_line)})))          # 前爪
    el.append(cube((cx - 1.07, 29.35, cz - 1.17), (cx + 1.07, 29.65, cz + 0.2), "gilt", s, faces=keep("north", "east", "west", "up", "down")))  # 项圈
    face = kit.custom("harp_cat_face", _cat_face)
    el.append(cube((cx - 1.5, 29.7, cz - 1.5), (cx + 1.5, 31.3, cz + 0.6), m, s, seed=3,
                   faces=keep("south", "east", "west", "up", "down", north={"mat": m, "deco": face, "bevel": 0.3})))        # 头
    nose = kit.pixels([(0, 0)], "marble", 0.6)
    el.append(cube((cx - 0.5, 29.75, cz - 1.8), (cx + 0.5, 30.3, cz - 1.5), m, s, seed=4,
                   faces=keep("east", "west", "up", "down", north={"mat": m, "deco": nose})))                                  # 鼻口
    ear_in = kit.pixels([(0, 0)], "marble", 1.2)
    for sgn in (-1, 1):
        x0 = cx + sgn * 1.05
        e = cube((x0 - 0.4, 31.25, cz - 1.0), (x0 + 0.4, 31.9, cz - 0.3), m, s, seed=5,
                 faces=keep("south", "east", "west", "up", north={"mat": m, "deco": ear_in}))
        el.append(zrot(e, -sgn * 14, (x0, 31.25, cz - 0.65)))
    tail = [(cx - 0.2, 28.4, cz + 2.0), (cx - 1.9, 27.7, cz + 1.75), (cx - 2.05, 26.8, cz - 0.9), (cx - 1.2, 25.8, cz - 1.95)]
    for i, (a, b) in enumerate(zip(tail, tail[1:])):
        d = np.subtract(b, a) / np.linalg.norm(np.subtract(b, a)) * 0.2      # 两头各伸 0.2，拐弯处不留缝
        a, b = tuple(np.subtract(a, d) if i else a), tuple(np.add(b, d))
        el.append(kit.segment(a, b, 0.5, 0.5, m, s, seed=6 + i, faces=keep("north", "south", "up", "down", *(("east",) if i == 2 else ()))))
    # —— 共鸣箱：音板一侧（A，厚 2.4）+ 背板一侧（B，转 δ，下宽上窄），整体斜靠在立柱右边 ——
    (bx0, by0), (bx1, by1) = HARP_BOARD
    L = math.hypot(bx1 - bx0, by1 - by0)
    tilt = -math.degrees(math.atan2(bx1 - bx0, by1 - by0))
    depth_top, depth_bottom = 3.0, 4.4
    delta = math.degrees(math.atan2(depth_bottom - depth_top, L))
    box_el = []
    side = {"mat": "flamed_honey", "deco": kit.custom("harp_box_side", _box_side)}
    box_el.append(cube((bx0, by0, 6.0), (bx0 + depth_top, by0 + L, 10.0), "flamed_honey", s, grain="y",
                       faces=keep("up", west={"mat": "spruce", "grain": "y"}, north=side, south=side)))
    holes = kit.custom("harp_sound_holes", _sound_holes)
    b = cube((bx0 + depth_bottom - 2.0, by0, 6.06), (bx0 + depth_bottom, by0 + L, 9.94), "flamed_honey", s, grain="y", seed=1,
             faces=keep("north", "south", "up", east={"mat": "flamed_honey", "deco": holes}))
    box_el.append(zrot(b, delta, (bx0 + depth_bottom, by0, 8.0)))
    eyelets = kit.custom("harp_eyelets", _eyelets)
    box_el.append(cube((bx0 - 0.3, by0 + 0.8, 7.6), (bx0, by0 + L - 0.4, 8.4), "walnut", s, grain="y",
                       faces=keep("north", "south", "up", "down", west={"mat": "walnut", "deco": eyelets})))
    el += kit.rigid(box_el, kit.euler(0, 0, tilt), (bx0, by0, 8.0))
    # —— 琴颈：S 形，九段转过去的板（相邻三段错开 z），下沿一排黄铜调音盘 ——
    pts = catmull(HARP_NECK, 9)
    dots = neck_dots()
    for i, (a, b2) in enumerate(zip(pts, pts[1:])):
        g = GAP * (i % 3)
        e = bar((a[0], a[1], 8.0), (b2[0], b2[1], 8.0), HARP_NECK_T, 2.0 - 2 * g, "walnut", s, ext=0.3, seed=i % 3,
                faces=keep("up", "down", north={"mat": "walnut", "deco": dots}, south={"mat": "walnut", "deco": dots},
                           **({"east": "walnut"} if i == len(pts) - 2 else {})))
        el.append(e)
    # 琴颈落在共鸣箱顶上的地方：一块浅胡桃木的托
    el.append(zrot(cube((bx1 - 0.4, by1 - 0.3, 6.6), (bx1 + 2.0, by1 + 0.35, 9.4), "walnut_light", s, grain="x"), tilt, (bx1, by1, 8.0)))
    # —— 琴弦：低音三根钢弦，其余羊肠弦，C 弦红、F 弦深蓝 ——
    nx = np.array([p[0] for p in pts])
    ny = np.array([p[1] - HARP_NECK_T / 2 for p in pts])
    sin_t, cos_t = math.sin(math.radians(-tilt)), math.cos(math.radians(-tilt))
    bar_x0, bar_y0 = bx0 - 0.3 * cos_t, by0 + 0.3 * sin_t           # 弦钉条正面那条线的起点
    for i in range(16):
        x = 5.9 + 0.47 * i
        yl = (x - bar_x0) / sin_t
        yb = bar_y0 + yl * cos_t - 0.2
        yt = float(np.interp(x, nx, ny)) + 0.25
        if i < 3:
            mat = "steel"
        else:
            mat = {0: "string_red", 3: "string_navy"}.get((i - 3) % 7, "gut")
        el.append(cube((x - 0.09, yb, 7.91), (x + 0.09, yt, 8.09), mat, s, grain="y", faces=keep("north", "south", "east", "west")))
    return kit.shifted(el, 0, 0, HARP_SHIFT)


def _paw_line(img, w, h, rng):
    if w >= 2:
        img[:, w // 2] = img[:, w // 2] * 0.8
    return img


def _cat_face(img, w, h, rng):
    """3×2 的猫脸：上排两只蓝宝石眼。"""
    eye = kit._tone(kit.PALETTE["velvet_navy"], np.array(2.3))
    if w >= 3 and h >= 1:
        img[0, 0] = eye
        img[0, w - 1] = eye
    return img


def _box_side(img, w, h, rng):
    """共鸣箱侧面（3 像素宽）：沿长边镶两朵金色的小星辰花。"""
    gilt = kit.PALETTE["gilt"]
    x0 = (w - 3) // 2
    for k in (0.3, 0.62):
        y0 = int(h * k)
        for j, row in enumerate(STARBLOOM_SMALL):
            for i, c in enumerate(row):
                if c == "#" and 0 <= y0 + j < h and 0 <= x0 + i < w:
                    img[y0 + j, x0 + i] = kit._tone(gilt, np.array(3.3 - 0.3 * j))
    return img


def _sound_holes(img, w, h, rng):
    """背板上三个椭圆音孔（2×3 像素，深色），沿长边排开，越往上越小。"""
    dark = kit._tone(kit.PALETTE["shadow"], np.array(3.6))
    x0 = (w - 2) // 2
    for k, n in ((0.28, 3), (0.5, 3), (0.72, 2)):
        y0 = int(h * k)
        for j in range(n):
            for i in range(2):
                if 0 <= y0 + j < h and 0 <= x0 + i < w:
                    img[y0 + j, x0 + i] = dark
    return img


def _eyelets(img, w, h, rng):
    """弦钉条：中间一列金色弦孔。"""
    g = kit._tone(kit.PALETTE["gilt"], np.array(3.3))
    for y in range(1, h - 1, 2):
        img[y, w // 2] = g
    return img


def build_harp(out):
    s = out.sheet("hexen_concert_harp")
    el = harp_elements(s)
    out.block_model("hexen_concert_harp", el, [s], "large", particle=out.particle("maple"))  # 碎成共鸣箱的枫木
    out.item_parent("hexen_concert_harp", "hexen_concert_harp", kit.display(el, gui_rot=(15, 20, 0)))    # 从演奏者那一侧看：立柱在左、共鸣箱升向右上
    out.particle_only("hexen_concert_harp_part", s.texture)


def build(out):
    build_violins(out)
    build_seated(out)
    build_harp(out)
