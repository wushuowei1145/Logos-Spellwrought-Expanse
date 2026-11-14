"""键盘组：星辰花三角钢琴、大键琴、钢片琴、单人 / 双人琴凳。约定见 docs/INSTRUMENT_ART.md。

坐标：模型按 FACING=north 画，键盘朝北（z 小的一侧），演奏的人坐在北边、面朝 +z，所以低音在 +x：
钢琴和大键琴的直边（spine）都在 +x，弯边（bentside）在 −x。

做法
- 弯边是沿折线一段段转过去的薄板（band()，同 kit.wall()，但每段能单独指定面、种子、按世界坐标画的面）；
  折线由控制点经 Chaikin 磨圆、按弧长每 2 像素一段（smooth_line()），直边保持一整块，没有台阶。
- 弯边围起来的面（音板、琴盖）用轴对齐的长条（cover()）铺满，长条的台阶边藏在弯边那一圈板的厚度里，
  音板 / 琴盖的面按世界坐标统一画（world_face()），跨元素的琴弦、纹样、金线是连着的。
- 琴盖先按「合上」的样子造，再整体绕直边的铰链转开（kit.rigid）。

接触点（MusicianPoses / StarbloomBenchBlock）：键顶 钢琴 13、大键琴 11.7 / 12.7（两层手键盘，keyTop 取 12.2）、钢片琴 11.2；
键盘左右各留 2.5（钢琴）/ 1.5（大键琴、钢片琴）的琴身边；凳面 10。
"""
import math

import numpy as np

import instrument_kit as kit
from instrument_kit import cube, STARBLOOM_SMALL

LEVEL = 0.06          # 交叠的薄板之间错开的高度（比 MIN_GAP 多一点余量，转动后的浮点误差不会让它掉到 0.05 以下）
BENCH_TOP = 10        # 凳面高（像素），同 StarbloomBenchBlock.TOP
PIANO_KEY_TOP = 13.0
HARPSICHORD_KEY_TOPS = (11.7, 12.7)
CELESTA_KEY_TOP = 11.2

PREVIEW = [
    "block/starbloom_grand_piano", "item/starbloom_grand_piano",
    "block/hexen_harpsichord", "item/hexen_harpsichord",
    "block/hexen_celesta", "item/hexen_celesta",
    "block/starbloom_bench", "item/starbloom_bench",
    "block/starbloom_duet_bench", "item/starbloom_duet_bench",
]


# ———————————————————— 通用小工具 ————————————————————

def tone(mat, t):
    """材质 mat 在色阶 t（可以是数组）上的颜色，(…, 3) float。"""
    return kit._tone(kit.PALETTE[kit.MATERIALS[mat][0]], np.asarray(t, np.float32))


def hnoise(a, b, seed=0):
    """整数格点上的确定性噪声 → [0, 1]。"""
    a = np.asarray(np.floor(a), np.int64)
    b = np.asarray(np.floor(b), np.int64)
    h = (a * 374761393 + b * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h = h ^ (h >> 16)
    return (h & 0xFFFF) / 65535.0


def vnoise(x, z, scale, seed=0):
    """平滑的值噪声（双线性 + smoothstep），scale 是格子大小。"""
    u, v = np.asarray(x) / scale, np.asarray(z) / scale
    i, j = np.floor(u), np.floor(v)
    fu, fv = u - i, v - j
    fu, fv = fu * fu * (3 - 2 * fu), fv * fv * (3 - 2 * fv)
    a, b = hnoise(i, j, seed), hnoise(i + 1, j, seed)
    c, d = hnoise(i, j + 1, seed), hnoise(i + 1, j + 1, seed)
    return (a * (1 - fu) + b * fu) * (1 - fv) + (c * (1 - fu) + d * fu) * fv


def face_points(e, face):
    """元素 e 的某一面上每个贴图像素中心的世界坐标（已按元素自己的转动转过），(h, w, 3)。"""
    f, t = e["from"], e["to"]
    size = [t[i] - f[i] for i in range(3)]
    (ua, us), (va, vs), na = kit.FACE_AXES[face]
    ai, vi, ni = kit.AXIS[ua], kit.AXIS[va], kit.AXIS[na]
    w, h = kit._px(size[ai]), kit._px(size[vi])
    uu = (np.arange(w) + 0.5) / w
    vv = (np.arange(h) + 0.5) / h
    ucoord = f[ai] + uu * size[ai] if us > 0 else t[ai] - uu * size[ai]
    vcoord = f[vi] + vv * size[vi] if vs > 0 else t[vi] - vv * size[vi]
    plane = {"north": f[2], "south": t[2], "west": f[0], "east": t[0], "down": f[1], "up": t[1]}[face]
    pts = np.zeros((h, w, 3))
    pts[..., ai] = ucoord[None, :]
    pts[..., vi] = vcoord[:, None]
    pts[..., ni] = plane
    m = kit.element_matrix(e)
    if m:
        o = np.array(e["rotation"]["origin"], float)
        pts = o + (pts - o) @ np.array(m).T
    return pts, w, h


def world_face(e, face, sheet, tag, paint, shade=None):
    """给 e 的 face 现画一块贴图：paint(pts) 收每个像素中心的世界坐标 (h, w, 3)，返回颜色 (h, w, 3)。
    shade=(上缘, 下缘) 在第一行 / 最后一行乘一点明暗（代替倒角，只在需要的边上）。"""
    pts, w, h = face_points(e, face)

    def painter():
        img = np.asarray(paint(pts), np.float32)
        if shade:
            img[0] *= shade[0]
            img[-1] *= shade[1]
        return np.clip(img, 0, 255).astype(np.uint8)
    key = ("world", tag, tuple(e["from"]), tuple(e["to"]), repr(e.get("rotation")), face)
    slot = sheet.add(key, w, h, painter)
    e["faces"][face] = {"texture": "#" + sheet.name, "uv": kit.FaceRef(sheet, slot)}


def rows(spec, key):
    """按行（v 方向，0 在上）覆盖颜色：spec = {行号(负数从下数): (材质, 色阶)}。"""
    def fn(img, w, h, rng):
        for r, (mat, t) in spec.items():
            rr = r if r >= 0 else h + r
            if 0 <= rr < h:
                img[rr, :] = tone(mat, t + 0.15 * (rng.random(w) - 0.5))
        return img
    return kit.custom(("rows", key), fn)


def edge_light(top=1.1, bottom=0.88, key="edge"):
    """只在上下两行加明暗（拼接面左右不留缝）。"""
    def fn(img, w, h, rng):
        if h >= 2:
            img[0] = img[0] * top
            img[-1] = img[-1] * bottom
        return img
    return kit.custom(("edge_light", top, bottom, key), fn)


def pattern_deco(pattern, colors, key, at=None):
    """把字符图案印到面上：colors = {字符: (材质, 色阶)}，'.' 透明。at=(x, y) 左上角，默认居中。"""
    def fn(img, w, h, rng):
        ph, pw = len(pattern), len(pattern[0])
        x0, y0 = at if at else ((w - pw) // 2, (h - ph) // 2)
        for j, row in enumerate(pattern):
            for i, ch in enumerate(row):
                if ch in colors and 0 <= x0 + i < w and 0 <= y0 + j < h:
                    mat, t = colors[ch]
                    img[y0 + j, x0 + i] = tone(mat, t)
        return img
    return kit.custom(("pattern", tuple(pattern), tuple(sorted(colors.items())), key, at), fn)


# —— 折线、多边形 ——

def chaikin(points, n=4):
    """Chaikin 细分（开折线，保留两端）：拐角磨圆。"""
    p = [tuple(map(float, q)) for q in points]
    for _ in range(n):
        out = [p[0]]
        for a, b in zip(p[:-1], p[1:]):
            out.append((0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]))
            out.append((0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1]))
        out.append(p[-1])
        p = out
    return p


def smooth_line(ctrl, seg=2.0):
    """控制点 → 圆顺的折线：Chaikin 磨圆后按弧长等分（每段约 seg 像素），共线的段并成一段。
    控制折线里沿坐标轴的直边原样保留（两端点就是折线的点），这样直边是不转的长方体，和前板、端板严丝合缝。"""
    dense = [tuple(p) for p in chaikin(ctrl, 5)]
    n = len(dense)
    cum = [0.0]
    for a, b in zip(dense[:-1], dense[1:]):
        cum.append(cum[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
    # 找出 dense 里沿坐标轴的直段（Chaikin 在共线的控制点上切出来的点正好落在线上）
    straight = []
    i = 0
    while i < n - 1:
        ax = 0 if abs(dense[i + 1][0] - dense[i][0]) < 1e-9 else 1 if abs(dense[i + 1][1] - dense[i][1]) < 1e-9 else None
        if ax is None:
            i += 1
            continue
        j = i + 1
        while j < n - 1 and abs(dense[j + 1][ax] - dense[i][ax]) < 1e-9:
            j += 1
        if cum[j] - cum[i] > 0.3:
            straight.append((i, j))
        i = j
    anchors = sorted({0, n - 1} | {k for run in straight for k in run})
    runs = set(straight)
    pts = [dense[0]]
    for a, b in zip(anchors[:-1], anchors[1:]):
        if (a, b) in runs:
            pts.append(dense[b])
            continue
        k = max(1, int(round((cum[b] - cum[a]) / seg)))
        xs = np.interp(np.linspace(cum[a], cum[b], k + 1)[1:], cum[a:b + 1], [p[0] for p in dense[a:b + 1]])
        zs = np.interp(np.linspace(cum[a], cum[b], k + 1)[1:], cum[a:b + 1], [p[1] for p in dense[a:b + 1]])
        pts += list(zip(xs, zs))
        pts[-1] = dense[b]
    out = [pts[0]]
    for i in range(1, len(pts) - 1):
        if abs(kit._turn(out[-1], pts[i], pts[i + 1])) > 0.3:
            out.append(pts[i])
    out.append(pts[-1])
    return [(round(float(x), 3), round(float(z), 3)) for x, z in out]


def rod(p0, p1, w, h, mat, sheet, **kw):
    """从 p0 到 p1 的一根杆（截面 w×h）：沿坐标轴的直接是长方体；斜的同 kit.segment，但以中点为转动原点（坐标不出界）。"""
    d = [b - a for a, b in zip(p0, p1)]
    axis = [i for i in range(3) if abs(d[i]) > 1e-9]
    if len(axis) == 1:
        i = axis[0]
        lo = [min(p0[i], p1[i])] * 3
        hi = [max(p0[i], p1[i])] * 3
        half = {0: (0, h / 2, w / 2), 1: (w / 2, 0, h / 2), 2: (w / 2, h / 2, 0)}[i]
        frm = [p0[k] - half[k] if k != i else lo[k] for k in range(3)]
        to = [p0[k] + half[k] if k != i else hi[k] for k in range(3)]
        return cube(frm, to, mat, sheet, grain="xyz"[i], **kw)
    mid = [(a + b) / 2 for a, b in zip(p0, p1)]
    L = math.sqrt(sum(v * v for v in d))
    e = kit.segment((mid[0] - L / 2, mid[1], mid[2]), (mid[0] + L / 2, mid[1], mid[2]), w, h, mat, sheet, **kw)
    seg = kit.segment(p0, p1, w, h, mat, sheet, **kw)
    e["rotation"] = dict(seg["rotation"], origin=[round(v, 4) for v in mid])
    return e


def offset_line(points, d):
    """把开折线往「内侧」（沿 (dz, −dx) 方向，band(outside=-1) 长厚度的那一侧）平移 d，拐角斜接。"""
    pts = np.asarray(points, float)
    n = len(pts)
    normals = []
    for i in range(n - 1):
        dx, dz = pts[i + 1] - pts[i]
        L = math.hypot(dx, dz)
        normals.append(np.array([dz, -dx]) / L)
    out = []
    for i in range(n):
        if i == 0:
            nv, k = normals[0], 1.0
        elif i == n - 1:
            nv, k = normals[-1], 1.0
        else:
            m = normals[i - 1] + normals[i]
            m /= np.linalg.norm(m)
            nv, k = m, 1.0 / max(0.3, float(m @ normals[i - 1]))
        out.append(tuple(pts[i] + nv * d * k))
    return out


def xsec(poly, z):
    """闭合多边形在 z 处的横截（最左、最右）。"""
    xs = []
    n = len(poly)
    for i in range(n):
        (x0, z0), (x1, z1) = poly[i], poly[(i + 1) % n]
        if (z0 <= z < z1) or (z1 <= z < z0):
            xs.append(x0 + (z - z0) / (z1 - z0) * (x1 - x0))
    return (min(xs), max(xs)) if len(xs) >= 2 else None


def cover(outer, inner, z_start, step=0.25):
    """用轴对齐长条 (x0, x1, za, zb) 铺满 inner，每条都在 outer 里面（台阶边落在 outer 与 inner 之间的那圈里）。"""
    zin = max(p[1] for p in inner)
    zout = max(p[1] for p in outer)

    def allowed(za, zb):
        lo, hi = -1e9, 1e9
        for z in np.linspace(za + 1e-4, zb - 1e-4, max(3, int((zb - za) / 0.05) + 1)):
            c = xsec(outer, z)
            if c is None:
                return None
            lo, hi = max(lo, c[0]), min(hi, c[1])
        return (lo, hi) if hi > lo else None

    def needed(za, zb):
        lo, hi = 1e9, -1e9
        for z in np.linspace(za, zb, max(3, int((zb - za) / 0.05) + 1)):
            c = xsec(inner, z)
            if c:
                lo, hi = min(lo, c[0]), max(hi, c[1])
        return (lo, hi) if hi >= lo else None

    out = []
    za = z_start
    while za < zin - 1e-6:
        best = None
        zb = za + step
        while zb <= zout + 1e-9:
            al, ne = allowed(za, zb), needed(za, min(zb, zin))
            if al is None or (ne and (al[0] > ne[0] + 1e-6 or al[1] < ne[1] - 1e-6)):
                break
            best = (round(al[0], 3), round(al[1], 3), round(za, 3), round(zb, 3))
            if zb >= zin:
                break
            zb += step
        assert best, f"cover failed at z={za}"
        out.append(best)
        za = best[3]
    return out


def dist_line(x, z, line):
    """到开折线的距离（数组）。"""
    d = np.full(np.shape(x), 1e9)
    for (x0, z0), (x1, z1) in zip(line[:-1], line[1:]):
        ex, ez = x1 - x0, z1 - z0
        L2 = ex * ex + ez * ez
        t = np.clip(((x - x0) * ex + (z - z0) * ez) / L2, 0, 1)
        d = np.minimum(d, np.hypot(x - (x0 + t * ex), z - (z0 + t * ez)))
    return d


def band(points, y0, y1, thick, mat, sheet, faces=None, outside=-1, tag="band", levels=2, world=None, **kw):
    """同 kit.wall()：沿水平折线立一圈薄板（每段一块绕 y 转过去的板，拐角处按 _ext() 延长）。
    每段以自己的中点为转动原点（坐标不出 −16–32）；相邻的段按 levels 个高度轮流上下各缩 0.06，交叠处不闪
    （厚的圈拐角多时用 3）。faces(i, n) 返回第 i 段（共 n 段）的面设置；每段的种子不同（纹理不重复）。
    outside=-1 时厚度长在 (dz, −dx) 一侧：外表面（折线上）是 south，内表面 north，起端 west、末端 east。
    world={局部面名: (paint, shade)} 这些面按世界坐标现画（见 world_face）。直边段不转、面名换过，这里按局部名处理好。"""
    els = []
    n = len(points)
    for i in range(n - 1):
        (x0, z0), (x1, z1) = points[i], points[i + 1]
        dx, dz = x1 - x0, z1 - z0
        length = math.hypot(dx, dz)
        ang = math.degrees(math.atan2(-dz, dx))
        ext0 = _ext(points[i - 1], points[i], points[i + 1], thick, outside) if i > 0 else 0.0
        ext1 = _ext(points[i], points[i + 1], points[i + 2], thick, outside) if i < n - 2 else 0.0
        z_lo, z_hi = (0, thick) if outside > 0 else (-thick, 0)
        g = LEVEL * (i % levels)
        fs = faces(i, n - 1) if callable(faces) else faces
        mx, mz = (x0 + x1) / 2, (z0 + z1) / 2
        lo = (mx - length / 2 - ext0, y0 + g, mz + z_lo)
        hi = (mx + length / 2 + ext1, y1 - g, mz + z_hi)
        q = round(ang / 90.0)
        remap = {}
        if abs(ang - 90.0 * q) < 1e-6:
            # 正好沿坐标轴的段（直边）：不转，直接摆成轴对齐的长方体，面名跟着换（长直边转 90° 后原始坐标会出界）
            q %= 4
            remap = [{}, {"east": "north", "west": "south", "south": "east", "north": "west"},
                     {"east": "west", "west": "east", "south": "north", "north": "south"},
                     {"east": "south", "west": "north", "south": "west", "north": "east"}][q]
            m = kit.euler(y=90.0 * q)
            cs = [kit._apply(m, [x - mx, 0.0, z - mz]) for x in (lo[0], hi[0]) for z in (lo[2], hi[2])]
            frm = (round(mx + min(c[0] for c in cs), 4), lo[1], round(mz + min(c[2] for c in cs), 4))
            to = (round(mx + max(c[0] for c in cs), 4), hi[1], round(mz + max(c[2] for c in cs), 4))
            wf = None if fs is None else {remap.get(k, k): v for k, v in fs.items()}
            e = cube(frm, to, mat, sheet, grain="x" if q % 2 == 0 else "z", faces=wf, seed=(tag, i), **kw)
        else:
            e = cube(lo, hi, mat, sheet, grain="x", faces=fs, seed=(tag, i), **kw)
            e["rotation"] = {"origin": [round(mx, 4), round(y0, 4), round(mz, 4)], "x": 0, "y": round(ang, 4), "z": 0}
        for lf, (paint, shade) in (world or {}).items():
            world_face(e, remap.get(lf, lf), sheet, (tag, lf), paint, shade=shade)
        els.append(e)
    return els


def _ext(a, b, c, thick, outside):
    """拐角处两块板各延长多少：往厚度那一侧拐（凸角）不延长——外表面正好交在折线顶点上，端面藏在下一块里；
    往另一侧拐（凹角）延长 thick·tan(θ/2)，把内侧的楔形缝补上（延长的部分落在邻板的厚度里，外面看不见）。"""
    t = kit._turn(a, b, c)
    convex = t < 0 if outside < 0 else t > 0
    if convex:
        # 拐得比直角还急（锐角）：两块都缩回一点，内侧的角才不会从邻板外表面戳出去（顶点处留一个很小的缺口）
        return -thick * math.tan(math.radians(abs(t) - 90)) if abs(t) > 90 else 0.0
    return thick * abs(math.tan(math.radians(t) / 2))


def closed(line):
    return list(line)


# 大朵五瓣星辰花（11×11，琴盖内侧的镶嵌）：'g' 黄铜描边、'i' 象牙花瓣、'o' 金色花心
STARBLOOM_INLAY = [
    "....ggg....",
    "...giiig...",
    "...giiig...",
    ".gg.gig.gg.",
    "giig.g.giig",
    "giiigogiiig",
    ".ggggogggg.",
    "..gig.gig..",
    ".giig.giig.",
    ".giig.giig.",
    "..gg...gg..",
]


# —— 键盘 ——

def keyboard_deco(key, reverse=False, key_w=2, offset=0):
    """琴键顶面（u 沿 +x，v 沿 +z：第 0 行在前沿）：白键 key_w 像素宽，右缘一像素略暗当键缝；
    后 60% 的行里画黑键（每个八度 2 + 3 个）。reverse：大键琴的反色键盘（乌木自然键、骨白升键）。"""
    nat, sharp = ("ebony", "ivory") if reverse else ("ivory", "ebony")
    nat_t, sharp_t = (2.3, 2.6) if reverse else (3.0, 1.6)
    gap = 0.9 if reverse else 0.45   # 乌木键的键缝要压得更深才看得出来

    def fn(img, w, h, rng):
        out = np.zeros((h, w, 3), np.float32)
        black_rows = range(max(1, int(round(h * 0.4))), h)
        for x in range(w):
            k = (x + offset) // key_w
            edge = (x + offset) % key_w == key_w - 1
            t = nat_t - (gap if edge else 0.0) + 0.06 * (rng.random() - 0.5)
            for y in range(h):
                tt = t + (0.35 if y == 0 else 0.0) - 0.08 * y / max(1, h - 1)
                out[y, x] = tone(nat, tt)
            # 黑键：落在白键 C D | F G A 的右缘（每 7 个白键一组）
            if (x + offset) % key_w == key_w - 1 and k % 7 in (0, 1, 3, 4, 5):
                for y in black_rows:
                    out[y, x] = tone(sharp, sharp_t + (0.5 if y == black_rows[0] else 0.0))
        return out
    return kit.custom(("keyboard", key, reverse, key_w, offset), fn)


def key_front_deco(key, reverse=False, key_w=2, offset=0):
    """琴键前沿（north 面，u 沿 −x）：一排键头，键缝一像素略暗。"""
    nat = "ebony" if reverse else "ivory"
    nat_t = 1.6 if reverse else 2.7

    def fn(img, w, h, rng):
        out = np.zeros((h, w, 3), np.float32)
        for x in range(w):
            xx = w - 1 - x  # north 面 u 反向：换回 +x 的序号
            edge = (xx + offset) % key_w == key_w - 1
            out[:, x] = tone(nat, nat_t - (0.6 if edge else 0.0))
        return out
    return kit.custom(("keyfront", key, reverse, key_w, offset), fn)


# ———————————————————— 星辰花三角钢琴 ————————————————————
# 占 x 0–32、z 0–32（主块 x 0–16、z 0–16；+x 是低音直边，+z 是琴尾）。

P_RIM_TOP = 16.6      # 琴身上沿（琴盖铰链高）
P_CASE_Y0 = 9.0       # 琴身底
P_FRONT_Z = 8.6       # 前板背面：弯边、音板从这里往后
P_LID_Z0 = 11.0       # 琴盖前沿（前面一截露出弦轴板和谱架）
P_LID_ANGLE = 28.0
P_SB_Y = 14.10        # 音板长条顶面（弯边内衬三层高度 14.30 / 14.24 / 14.18 之下）
P_RIM_T, P_LINER_T = 1.0, 2.0
P_LID_T = 1.0


def piano_outline():
    """琴身外轮廓（开折线）：前板背面左端 → 高音侧短直边 → 微微内凹的弯边 → 圆琴尾（半径 8.9 的圆弧，
    与弯边、低音直边都相切）→ 低音直边 → 前板背面右端。"""
    ctrl = [(1.0, P_FRONT_Z), (1.0, 11.2), (1.0, 12.2), (3.4, 14.9), (5.9, 17.7), (7.9, 20.6), (9.6, 23.4),
            (11.5, 26.0), (13.8, 27.9)]
    cx, cz, r = 22.1, 23.0, 8.9
    ctrl += [(cx + r * math.cos(math.radians(a)), cz + r * math.sin(math.radians(a))) for a in range(132, -1, -12)]
    ctrl += [(31.0, 20.5), (31.0, P_FRONT_Z)]
    return smooth_line(ctrl)


def marble_paint(seed=0, base=3.05, gilt_below=None):
    """世界坐标的白色大理石纹漆（跨元素连续）：几乎平的漆面，稀疏的淡灰纹斜着游走。
    gilt_below：世界 y 低于它的部分画成淡金（琴身底边那一圈金线，各个面对得上）。"""
    def paint(pts):
        x, y, z = pts[..., 0], pts[..., 1], pts[..., 2]
        u, v = x + 0.45 * y, z - 0.35 * y
        warp = vnoise(u, v, 6.0, seed) + 0.5 * vnoise(u, v, 2.5, seed + 1)
        w = np.abs(np.sin(0.16 * u + 0.11 * v + 3.4 * warp))
        t = base + 0.04 * (vnoise(u, v, 1.6, seed + 2) - 0.5) - 0.28 * (w < 0.06) - 0.08 * (w < 0.16)
        img = tone("marble", t)
        if gilt_below is not None:
            g = tone("gilt", 2.55 + 0.1 * (hnoise(u, v, seed + 3) - 0.5))
            img = np.where((y < gilt_below)[..., None], g, img)
        return img
    return paint


PIANO_MARBLE = marble_paint(3, gilt_below=P_CASE_Y0 + 0.95)
SIDE_SHADE = (1.06, 0.97)


def marble_faces(e, s, faces, tag="piano_marble", paint=None):
    for f in faces:
        world_face(e, f, s, tag, paint or PIANO_MARBLE, shade=SIDE_SHADE if f not in ("up", "down") else None)
    return e


def piano_soundboard_paint(line):
    """音板（世界坐标 x, z）：云杉底、古铜色铁骨架（前面一整块、沿边一圈、三根斜撑）、弦轴、制音器、弦（低音缠铜）、琴马。"""
    def paint(pts):
        x, z = pts[..., 0], pts[..., 2]
        d = dist_line(x, z, line)
        ix = np.floor(x)
        t = 2.45 + 0.35 * (hnoise(ix, 0, 11) - 0.5) + 0.12 * (hnoise(ix, np.floor(z / 4), 12) - 0.5)
        img = tone("spruce", t)
        strut = np.zeros(x.shape, bool)
        for (ax, az), (bx, bz) in (((8.0, 12.5), (13.0, 25.5)), ((16.0, 12.5), (19.5, 29.2)), ((24.0, 12.5), (25.5, 30.6))):
            ex, ez = bx - ax, bz - az
            tt = np.clip(((x - ax) * ex + (z - az) * ez) / (ex * ex + ez * ez), 0, 1)
            strut |= np.hypot(x - (ax + tt * ex), z - (az + tt * ez)) < 0.7
        plate = (z < 12.6) | (d < 2.4) | strut
        img = np.where(plate[..., None], tone("gilt", 1.3 + 0.25 * (vnoise(x, z, 3, 5) - 0.5)), img)
        for hx in (5.0, 11.0, 21.0, 27.0):
            img = np.where((np.hypot(x - hx, z - 11.3) < 0.85)[..., None], tone("shadow", 2.5), img)
        bridge = (d >= 4.6) & (d < 5.5) & (z > 14.0) & (x < 29.0)
        img = np.where(bridge[..., None], tone("walnut_light", 2.2), img)
        # 弦：只在底色上压一层（一像素的弦太粗，画实了满眼条纹）
        string = (ix % 2 == 0) & (z > 9.4) & (d >= 2.4) & (x < 30.2)
        scol = np.where((x > 22.0)[..., None], tone("copper", 1.6), tone("steel", 2.0))
        img = np.where(string[..., None], 0.55 * img + 0.45 * scol, img)
        # 制音器：一道乌木横条，每隔一格略亮一点（一块块的制音木）
        damper = (z >= 12.6) & (z < 13.6) & (x > 2.5) & (x < 29.6)
        img = np.where(damper[..., None], tone("ebony", 2.2), img)
        img = np.where((damper & (ix % 2 == 0))[..., None], tone("ebony", 2.9), img)
        # 弦轴：两排交错，只比骨架略亮（不要棋盘格）
        pins = (((np.floor(z) == 9) & (ix % 2 == 0)) | ((np.floor(z) == 10) & (ix % 2 == 1))) & (x > 2.0) & (x < 30.0)
        img = np.where(pins[..., None], 0.6 * img + 0.4 * tone("steel", 2.6), img)
        return img
    return paint


def lid_under_paint(poly, center, motif):
    """琴盖内侧（世界坐标，琴盖合上时）：胡桃木底、离边一像素的金线、正中一朵星辰花镶嵌（象牙花瓣、金描边）。"""
    def paint(pts):
        x, z = pts[..., 0], pts[..., 2]
        d = dist_line(x, z, poly + [poly[0]])
        t = 1.9 + 0.3 * (hnoise(np.floor(x), 0, 31) - 0.5) + 0.1 * (hnoise(np.floor(x), np.floor(z / 5), 32) - 0.5)
        img = tone("walnut", t)
        img = np.where(((d >= 1.0) & (d < 1.9))[..., None], tone("gilt", 2.8), img)
        n = len(motif)
        cx, cz = center
        ix = np.floor(x - (cx - n / 2)).astype(int)
        iz = np.floor(z - (cz - n / 2)).astype(int)
        cols = {"g": tone("gilt", 2.3), "i": tone("ivory", 3.0), "o": tone("gilt", 3.5)}
        for j in range(n):
            for i in range(n):
                ch = motif[j][i]
                if ch in cols:
                    img = np.where(((ix == i) & (iz == j))[..., None], cols[ch], img)
        return img
    return paint


def piano_legs(s):
    """三条腿：上粗下细的胡桃木，顶块下沿一道金箍，金脚套。"""
    el = []
    collar = rows({-1: ("gilt", 2.6)}, "piano_leg_collar")
    for k, (cx, cz) in enumerate(((3.6, 3.4), (28.4, 3.4), (20.6, 27.0))):
        top = P_CASE_Y0 + (0.28 if k == 2 else 0.0)  # 琴尾那条腿顶着音板底（比琴身下沿高 0.28）
        el.append(cube((cx - 1.6, 7.4, cz - 1.6), (cx + 1.6, top, cz + 1.6), "walnut", s, grain="y",
                       faces={"up": None, "down": "walnut", **{f: {"mat": "walnut", "deco": collar} for f in ("north", "south", "east", "west")}}, seed=k))
        el.append(cube((cx - 1.25, 3.0, cz - 1.25), (cx + 1.25, 7.4, cz + 1.25), "walnut", s, grain="y", faces={"up": None}, seed=k))
        el.append(cube((cx - 0.95, 0.9, cz - 0.95), (cx + 0.95, 3.0, cz + 0.95), "walnut", s, grain="y", faces={"up": None}, seed=k))
        el.append(cube((cx - 1.15, 0.0, cz - 1.15), (cx + 1.15, 0.9, cz + 1.15), "gilt", s, faces={"down": None}))
    return el


def piano_lyre(s):
    """踏板琴架：顶块、两根外弯的琴架臂、两根金色细柱、踏板箱、三个黄铜踏板、两根往后的斜撑。"""
    el = []
    el.append(cube((13.6, 8.2, 3.7), (18.4, P_CASE_Y0, 5.1), "walnut", s, faces={"up": None}))
    for side in (-1, 1):
        x_top, x_mid, x_bot = 16 + side * 1.9, 16 + side * 2.35, 16 + side * 1.7
        el.append(kit.segment((x_top, 8.2, 4.4), (x_mid, 5.2, 4.4), 0.7, 0.7, "walnut", s, seed=side))
        el.append(kit.segment((x_mid, 5.2, 4.4), (x_bot, 2.2, 4.4), 0.58, 0.7, "walnut", s, seed=side + 3))
        el.append(cube((16 + side * 0.6 - 0.2, 2.2, 4.2), (16 + side * 0.6 + 0.2, 8.2, 4.6), "gilt", s, faces={"up": None, "down": None}))
    el.append(cube((13.4, 0.9, 3.6), (18.6, 2.2, 5.2), "walnut", s))
    for x in (14.3, 15.6, 16.9):
        el.append(cube((x, 1.15, 1.9), (x + 0.8, 1.6, 3.6), "brass", s, grain="z", faces={"south": None}))
    for side in (-1, 1):
        el.append(kit.segment((16 + side * 1.6, 1.8, 5.2), (16 + side * 1.6, P_CASE_Y0 + 0.3, 10.5), 0.5, 0.5, "walnut", s, seed=side + 7))
    return el


def piano_keyboard(s):
    el = []
    # 两侧键盘端木（琴身的延伸）：大理石，底边一圈金线
    for x0, x1 in ((1.0, 2.5), (29.5, 31.0)):
        e = cube((x0, P_CASE_Y0, 0.0), (x1, 14.4, 6.4), "marble", s, grain="z",
                 faces={"south": None, "down": "walnut", "north": None, "east": None, "west": None, "up": None})
        el.append(marble_faces(e, s, ("north", "east", "west", "up")))
    # 键床：正面上沿一道胡桃木键前挡
    e = cube((2.5, P_CASE_Y0, 0.3), (29.5, 12.0, 6.4), "marble", s,
             faces={"west": None, "east": None, "south": None, "down": "walnut", "north": None, "up": None})
    marble_faces(e, s, ("north", "up"), tag="piano_keybed",
                 paint=lambda p: np.where((p[..., 1] > 11.0)[..., None], tone("walnut", 1.7), PIANO_MARBLE(p)))
    el.append(e)
    el.append(cube((2.5, 12.0, 0.6), (29.5, PIANO_KEY_TOP, 5.4), "ivory", s,
                   faces={"west": None, "east": None, "south": None, "down": None,
                          "up": {"mat": "ivory", "deco": keyboard_deco("piano", offset=1), "bevel": 0},
                          "north": {"mat": "ivory", "deco": key_front_deco("piano", offset=1), "bevel": 0}}))
    # 键盘后的挡板：胡桃木，贴着琴键一行红毡，正中一块小金铭牌
    fall = [rows({-2: ("felt_red", 2.1), -1: ("felt_red", 1.5)}, "fallboard"),
            pattern_deco(["###"], {"#": ("gilt", 3.1)}, "nameplate", at=(12, 1))]
    el.append(cube((2.5, 12.0, 5.4), (29.5, P_RIM_TOP, 6.4), "walnut", s, grain="x",
                   faces={"west": None, "east": None, "south": None, "down": None,
                          "north": {"mat": "walnut", "deco": fall}}))
    # 前板（琴身前壁）：外面大理石、里面胡桃木
    e = cube((1.0, P_CASE_Y0, 6.4), (31.0, P_RIM_TOP, P_FRONT_Z), "marble", s, grain="x",
             faces={"south": {"mat": "walnut", "deco": edge_light(1.15, 1.0, "rim_in"), "bevel": 0}, "down": "walnut",
                    "north": None, "west": None, "east": None, "up": None})
    el.append(marble_faces(e, s, ("north", "west", "east", "up")))
    return el


def piano_desk(s):
    """谱架：胡桃木镂空花格板、下沿托条，两端是小猫头卷饰（各两只金色尖耳）；整体往后仰 14°。"""
    x0, x1, y0, h = 9.0, 23.0, P_RIM_TOP, 6.0
    zf, zb = 7.4, 8.0
    el = []

    def fret(img, w, hh, rng):
        """14×6：浅胡桃木外框，里面压暗的凹板，两侧各一个菱形镂空，正中一朵金色小星辰花。"""
        out = img.copy()
        out[1:-1, 1:-1] = out[1:-1, 1:-1] * 0.62
        out[0, :] = tone("walnut_light", 2.8)
        out[-1, :] = tone("walnut_light", 1.6)
        out[:, 0] = tone("walnut_light", 2.4)
        out[:, -1] = tone("walnut_light", 1.7)
        cy = (hh - 3) // 2
        for cx in (3, w - 4):
            for j, i in ((0, 1), (1, 0), (1, 2), (2, 1)):
                out[cy + j, cx - 1 + i] = tone("walnut_light", 2.2)
            out[cy + 1, cx] = tone("shadow", 3.0)
        cx = w // 2 - 1
        for j, row in enumerate(STARBLOOM_SMALL):
            for i, ch in enumerate(row):
                if ch == "#":
                    out[cy + j, cx + i] = tone("gilt", 3.3 - 0.3 * j)
        return out
    el.append(cube((x0, y0, zf), (x1, y0 + h, zb), "walnut", s, grain="x",
                   faces={"north": {"mat": "walnut", "deco": kit.custom("piano_desk_fret3", fret), "bevel": 0}, "down": None}))
    el.append(cube((x0 - 0.2, y0, zf - 0.9), (x1 + 0.2, y0 + 0.7, zf), "walnut_light", s, grain="x", faces={"down": None, "south": None}))
    for cx in (x0 + 0.75, x1 - 0.75):
        el.append(cube((cx - 0.75, y0 + h, zf + 0.05), (cx + 0.75, y0 + h + 0.9, zb - 0.05), "walnut_light", s, faces={"down": None}))
        for ex in (-0.45, 0.45):
            ear = cube((cx + ex - 0.32, y0 + h + 0.9 - 0.32, zf + 0.15), (cx + ex + 0.32, y0 + h + 0.9 + 0.32, zb - 0.15), "gilt", s)
            ear["rotation"] = kit.rot_euler(0, 0, 45, (cx + ex, y0 + h + 0.9, (zf + zb) / 2))
            el.append(ear)
    return kit.rigid(el, kit.euler(x=14), (16, y0, zf))


def piano_case(s, line):
    el = []

    def rim_faces(i, n):
        return {"south": None, "up": None, "north": {"mat": "walnut", "bevel": 0, "deco": edge_light(1.15, 1.0, "rim_in")},
                "down": "walnut", "west": {"mat": "marble", "bevel": 0, "seed": 0}, "east": {"mat": "marble", "bevel": 0, "seed": 0}}
    el += band(line, P_CASE_Y0, P_RIM_TOP, P_RIM_T, "marble", s, rim_faces, tag="rim",
               world={"south": (PIANO_MARBLE, SIDE_SHADE), "up": (PIANO_MARBLE, None)})
    inner1 = offset_line(line, P_RIM_T)
    sb = piano_soundboard_paint(line)
    el += band(inner1, P_CASE_Y0 + 0.35, P_SB_Y + 0.2, P_LINER_T, "spruce", s, levels=3, tag="liner",
               faces=lambda i, n: {"up": None, "north": None, "south": None, "west": {"mat": "spruce", "bevel": 0, "seed": 0}, "east": {"mat": "spruce", "bevel": 0, "seed": 0}, "down": "walnut"},
               world={"up": (sb, None)})
    outer = closed(offset_line(line, 0.08))
    inner = closed(offset_line(line, P_RIM_T + P_LINER_T))
    for x0, x1, za, zb in cover(outer, inner, P_FRONT_Z):
        e = cube((x0, P_CASE_Y0 + 0.28, za), (x1, P_SB_Y, zb), "spruce", s,
                 faces={"up": None, "north": None, "south": None, "west": None, "east": None, "down": "walnut"})
        world_face(e, "up", s, "piano_sb", sb)
        el.append(e)
    # 低音直边上的三个金铰链
    for zc in (13.5, 20.5, 27.0):
        el.append(cube((31.0, P_RIM_TOP - 0.7, zc - 0.8), (31.25, P_RIM_TOP, zc + 0.8), "gilt", s, faces={"west": None}))
    return el


def piano_lid(s, line):
    """琴盖：先按合上画（下表面贴着琴身上沿），再绕低音直边上的铰链转开 P_LID_ANGLE。
    沿弯边一圈宽 3.2 的板（band）+ 中间几条长条（cover）；上面大理石、内侧胡桃木镶星辰花、边缘一圈金。"""
    lid_line = [(1.0, P_LID_Z0)] + [p for p in line if p[1] > P_LID_Z0 + 1e-6] + [(31.0, P_LID_Z0)]
    H = P_RIM_TOP
    D = 1.3            # 弯边一圈板只要盖住长条的台阶；越窄，板与板接缝处那 0.06 的小台阶越短
    poly = closed(lid_line)
    under = lid_under_paint(poly, (18.5, 22.0), STARBLOOM_INLAY)
    top = marble_paint(7)

    def lid_faces(i, n):
        f = {"up": None, "down": None, "south": "gilt", "north": {"mat": "marble", "bevel": 0}, "west": {"mat": "marble", "bevel": 0, "seed": 0}, "east": {"mat": "marble", "bevel": 0, "seed": 0}}
        if i == 0:
            f["west"] = "gilt"
        if i == n - 1:
            f["east"] = "gilt"
        return f
    el = band(lid_line, H, H + P_LID_T, D, "marble", s, lid_faces, tag="lid", levels=2,
              world={"up": (top, None), "down": (under, None)})
    outer = closed(offset_line(lid_line, 0.06))
    inner = closed(offset_line(lid_line, D))
    for x0, x1, za, zb in cover(outer, inner, P_LID_Z0 + 0.07):
        fs = {"up": None, "down": None, "west": None, "east": None, "north": None, "south": None}
        if abs(za - P_LID_Z0 - 0.07) < 1e-6:
            fs["north"] = "gilt"  # 前沿（比两端弯边板的端面退后 0.07，不共面）
        e = cube((x0, H + 0.12, za), (x1, H + P_LID_T - 0.12, zb), "marble", s, faces=fs)
        world_face(e, "up", s, "piano_lid_top", top)
        world_face(e, "down", s, "piano_lid_under", under)
        el.append(e)
    return kit.rigid(el, kit.euler(z=-P_LID_ANGLE), (31.0, H, 0.0))


def piano_prop(s):
    """琴盖撑杆：立在高音侧弯边的上沿，略往里斜，顶到琴盖内侧。"""
    th = math.radians(P_LID_ANGLE)
    zp = 17.5
    pts = offset_line(piano_outline(), 0.5)
    xb = min(p[0] for p in pts if abs(p[1] - zp) < 2.0) + 0.3
    xt = xb + 2.2
    lo = 0.12  # 长条下表面比铰链高 0.12：转过之后 x' = 31 − t·cos + lo·sin，y' = H + t·sin + lo·cos
    t = (31.0 - xt + lo * math.sin(th)) / math.cos(th)
    yt = P_RIM_TOP + t * math.sin(th) + lo * math.cos(th) + 0.2
    return [kit.segment((xb, P_RIM_TOP, zp), (xt, yt, zp), 0.55, 0.55, "walnut_light", s)]


def piano_elements(s):
    line = piano_outline()
    el = []
    el += piano_legs(s)
    el += piano_lyre(s)
    el += piano_keyboard(s)
    el += piano_case(s, line)
    el += piano_desk(s)
    el += piano_lid(s, line)
    el += piano_prop(s)
    return el


def build_piano(out):
    s = out.sheet("starbloom_grand_piano")
    el = kit.prune_hidden(piano_elements(s))
    out.block_model("starbloom_grand_piano", el, [s], "large", particle=out.particle("marble"))  # 白钢琴碎成白漆，不是底面的胡桃木
    out.particle_only("starbloom_grand_piano_part", s.texture)
    out.item_parent("starbloom_grand_piano", "starbloom_grand_piano", kit.display(el))


# ———————————————————— 大键琴 ————————————————————
# 一格宽、两格深（LongInstrumentBlock：主块 z 0–16 是键盘，TAIL 块 z 16–32）。直边（低音）在 +x = 15，
# 弯边内凹地收向琴尾，琴尾是一小段斜直边。白色大理石纹琴身上下两道金线；车削腿的胡桃木琴架带牙板和横撑；
# 两层乌木自然键、骨白升键的反色键盘；琴盖内侧是魔女之森的星夜（深蓝夜空、金星、新月、森林剪影），
# 音板上画着一圈小花和一朵金色星辰花音窗。

H_CASE_Y0 = 9.0
H_RIM_TOP = 15.0       # 琴身上沿（琴盖铰链高）
H_FRONT_Z = 7.0        # 前板背面：弯边、音板从这里往后
H_JACK_Z = (8.6, 9.6)  # 跳杆横档（前面 z 7–8.6 露出弦轴板）
H_LID_Z0 = 9.6         # 琴盖前沿（落在高音侧的直边上）
H_LID_ANGLE = 38.0
H_SB_Y = 13.1          # 音板长条顶面
H_RIM_T, H_LINER_T = 0.8, 1.6
H_LID_T = 0.8
H_SPINE_X = 15.0


def harpsichord_outline():
    """外轮廓（开折线）：前板背面左端 → 高音侧短直边 → 内凹弯边（越往后越贴近直边方向）→ 斜的琴尾 → 直边 → 前板背面右端。"""
    ctrl = [(1.0, H_FRONT_Z), (1.0, 9.6), (1.0, 11.0), (2.3, 12.9), (3.5, 15.0), (4.6, 17.5), (5.6, 20.3), (6.5, 23.2),
            (7.3, 26.2), (8.0, 29.0), (8.5, 30.6)]
    line = smooth_line(ctrl, seg=2.0)
    # 弯边到琴尾、琴尾到直边是两个干脆的拐角（不磨圆）
    return line + [(H_SPINE_X, 31.7), (H_SPINE_X, H_FRONT_Z)]


H_MARBLE = marble_paint(11, gilt_below=H_CASE_Y0 + 0.95)


def harpsichord_marble(pts):
    """琴身外表面：大理石纹，底边一道金线，上沿下面再一道细金线（法式琴身的金色饰带）。"""
    img = H_MARBLE(pts)
    y = pts[..., 1]
    return np.where(((y >= 13.0) & (y < 14.0))[..., None], tone("gilt", 2.45), img)


def harpsichord_soundboard_paint(line):
    """音板：云杉；前面弦轴板；沿弯边离边 4 像素一道琴马，琴马外侧（没有弦的地方）一串淡彩小花；
    琴马内侧是弦（低音黄铜、高音钢），弦下面一朵金色星辰花音窗。"""
    rose_c = (11.4, 15.4)   # 琴马和直边之间
    bent = [p for p in line if p[0] < H_SPINE_X - 0.5 and p[1] > H_FRONT_Z + 0.5]   # 弯边 + 琴尾

    def paint(pts):
        x, z = pts[..., 0], pts[..., 2]
        d = dist_line(x, z, bent)
        ix, iz = np.floor(x), np.floor(z)
        t = 2.75 + 0.3 * (hnoise(ix, 0, 41) - 0.5) + 0.1 * (hnoise(ix, np.floor(z / 4), 42) - 0.5)
        img = tone("spruce", t)
        # 弦轴板：两排交错的弦轴，只比木色略亮
        wrest = z < H_JACK_Z[0]
        img = np.where(wrest[..., None], tone("walnut_light", 2.2), img)
        pins = wrest & (((iz == 7) & (ix % 2 == 0)) | ((iz == 8) & (ix % 2 == 1))) & (x > 1.6) & (x < 14.4)
        img = np.where(pins[..., None], tone("walnut_light", 3.6), img)
        # 音窗：一圈胡桃木细环，里面金色小星辰花
        rx, rz = x - rose_c[0], z - rose_c[1]
        r = np.hypot(rx, rz)
        img = np.where(((r >= 1.7) & (r < 2.4))[..., None], tone("walnut", 2.2), img)
        img = np.where((r < 1.7)[..., None], tone("shadow", 3.0), img)
        sx = np.floor(rx + 1.5).astype(int)
        sz = np.floor(rz + 1.5).astype(int)
        for j, row in enumerate(STARBLOOM_SMALL):
            for i, ch in enumerate(row):
                if ch == "#":
                    img = np.where(((sx == i) & (sz == j))[..., None], tone("gilt", 3.3 - 0.3 * j), img)
        # 琴马
        bridge = (d >= 4.0) & (d < 4.8) & (z > H_JACK_Z[1])
        img = np.where(bridge[..., None], tone("walnut", 2.4), img)
        # 弦：琴马以内，每两列一根，压一层
        string = (ix % 2 == 1) & (z > H_JACK_Z[1]) & (d >= 4.8) & (x < 14.2)
        scol = np.where((x > 9.0)[..., None], tone("brass", 2.4), tone("steel", 2.2))
        img = np.where(string[..., None], 0.55 * img + 0.45 * scol, img)
        # 琴马外侧的小花：沿弯边每 6 像素一朵十字小花（淡红、淡蓝、象牙轮流，花心一点金），之间一两片叶
        free = (d >= 1.0) & (d < 3.9) & (z > H_JACK_Z[1] + 1.4)
        k = np.floor((z - 11.0) / 6.0)
        cz = 11.0 + (k + 0.5) * 6.0
        fz = np.floor(z - cz + 1.5).astype(int)
        fd = np.floor(d - 2.4 + 1.5).astype(int)
        cross = ((fz == 1) & (fd >= 0) & (fd <= 2)) | ((fd == 1) & (fz >= 0) & (fz <= 2))
        center = (fz == 1) & (fd == 1)
        kk = (k.astype(int) % 3)[..., None]
        pcol = np.where(kk == 0, tone("velvet", 3.0) * 0.6 + tone("ivory", 2.8) * 0.4,
                        np.where(kk == 1, tone("velvet_navy", 3.6) * 0.6 + tone("ivory", 2.8) * 0.4, tone("ivory", 3.0)))
        img = np.where((free & cross & ~center)[..., None], pcol, img)
        img = np.where((free & center)[..., None], tone("gilt", 3.2), img)
        leaf = free & ((fz == 3) & (fd == 0) | (fz == 4) & (fd == 1))
        img = np.where(leaf[..., None], tone("walnut_light", 3.0), img)
        return img
    return paint


def night_sky_paint(poly):
    """琴盖内侧（琴盖合上时的坐标）：魔女之森的星夜。画面「上」是远离铰链（x 小）的一边：
    深蓝夜空往上渐暗，稀疏的单像素金星，一朵小星辰花当最亮的星，象牙色新月，沿铰链一排尖顶的黑森林；离边一像素一道金线。"""
    def paint(pts):
        x, z = pts[..., 0], pts[..., 2]
        hgt = H_SPINE_X - x                    # 离铰链多远 = 画面里的高度
        d = dist_line(x, z, poly + [poly[0]])
        img = tone("velvet_navy", 0.6 + 1.9 * np.clip(1 - hgt / 11.0, 0, 1))
        ix, iz = np.floor(x), np.floor(z)
        h = hnoise(ix, iz, 57)
        far = hgt > 5.0
        img = np.where(((h > 0.962) & far)[..., None], tone("gilt", 3.5), img)
        img = np.where(((h > 0.93) & (h <= 0.962) & far)[..., None], tone("velvet_navy", 4.2), img)
        # 新月
        r1 = np.hypot(x - 4.6, z - 13.6)
        r2 = np.hypot(x - 3.9, z - 12.6)
        img = np.where(((r1 < 2.2) & (r2 >= 2.0))[..., None], tone("ivory", 3.4), img)
        # 小星辰花（3×3，最亮的星）
        sx = np.floor(x - 6.0).astype(int)
        sz = np.floor(z - 21.0).astype(int)
        for j, row in enumerate(STARBLOOM_SMALL):
            for i, ch in enumerate(row):
                if ch == "#":
                    img = np.where(((sx == j) & (sz == i))[..., None], tone("gilt", 3.6 - 0.3 * j), img)
        # 森林：沿铰链一排尖顶的树（中间有棵高的），几乎黑、带一点蓝
        k = np.floor((z + 0.6) / 2.4)
        frac = (z + 0.6) / 2.4 - k
        tall = 1.6 + 2.6 * hnoise(k, 0, 58)
        tree = 1.0 + tall * (1 - 2 * np.abs(frac - 0.5))
        img = np.where((hgt < tree)[..., None], tone("velvet_navy", 0.25) * 0.5 + tone("shadow", 2.0) * 0.5, img)
        img = np.where(((d >= 0.8) & (d < 1.6))[..., None], tone("gilt", 2.7), img)
        return img
    return paint


def turned_leg(s, cx, cz, k, top=H_CASE_Y0):
    """车削腿：圆饼脚、细颈、花瓶肚（一道金箍）、细颈、方顶块。"""
    ring = rows({2: ("gilt", 2.6)}, "hs_vase_ring")
    return [
        cube((cx - 0.9, 0.0, cz - 0.9), (cx + 0.9, 0.8, cz + 0.9), "walnut_light", s, faces={"down": None}, seed=k),
        cube((cx - 0.5, 0.8, cz - 0.5), (cx + 0.5, 3.2, cz + 0.5), "walnut", s, grain="y", faces={"up": None, "down": None}, seed=k),
        cube((cx - 0.9, 3.2, cz - 0.9), (cx + 0.9, 5.6, cz + 0.9), "walnut", s, grain="y",
             faces={f: {"mat": "walnut", "deco": ring} for f in ("north", "south", "east", "west")}, seed=k),
        cube((cx - 0.5, 5.6, cz - 0.5), (cx + 0.5, 6.6, cz + 0.5), "walnut", s, grain="y", faces={"up": None, "down": None}, seed=k),
        cube((cx - 0.9, 6.6, cz - 0.9), (cx + 0.9, top, cz + 0.9), "walnut", s, grain="y", faces={"up": None}, seed=k),
    ]


def harpsichord_stand(s):
    """琴架：四条车削腿，腿顶之间一圈牙板（左边那块顺着弯边斜过去），下面一圈横撑。"""
    legs = [(2.4, 2.4), (13.6, 2.4), (9.4, 26.4), (13.6, 29.0)]
    el = []
    for k, (cx, cz) in enumerate(legs):
        el += turned_leg(s, cx, cz, k, top=H_CASE_Y0 + (0.25 if cz > 10 else 0.0))
    fl, fr, bl, br = legs
    apron = rows({0: ("gilt", 2.5)}, "hs_apron")
    el.append(cube((fl[0] + 0.9, 7.6, fl[1] - 0.3), (fr[0] - 0.9, H_CASE_Y0, fl[1] + 0.3), "walnut", s, grain="x",
                   faces={"up": None, "north": {"mat": "walnut", "deco": apron}, "south": {"mat": "walnut", "deco": apron}}))
    el.append(cube((fr[0] - 0.3, 7.6, fr[1] + 0.9), (fr[0] + 0.3, H_CASE_Y0, br[1] - 0.9), "walnut", s, grain="z",
                   faces={"up": None, "east": {"mat": "walnut", "deco": apron}, "west": {"mat": "walnut", "deco": apron}}))
    for (ax, az), (bx, bz), seed in ((fl, bl, 1), (bl, br, 2)):
        L = math.hypot(bx - ax, bz - az)
        ux, uz = (bx - ax) / L, (bz - az) / L
        el.append(rod((ax + ux * 0.9, 8.3, az + uz * 0.9), (bx - ux * 0.9, 8.3, bz - uz * 0.9), 0.6, 1.4, "walnut", s, seed=seed))
    for (ax, az), (bx, bz), seed in ((fl, bl, 3), (fr, br, 4)):
        L = math.hypot(bx - ax, bz - az)
        ux, uz = (bx - ax) / L, (bz - az) / L
        el.append(rod((ax + ux * 0.5, 1.9, az + uz * 0.5), (bx - ux * 0.5, 1.9, bz - uz * 0.5), 0.6, 0.6, "walnut", s, seed=seed))
    # 两根侧撑之间的横撑
    z = 15.0
    xl = fl[0] + (bl[0] - fl[0]) * (z - fl[1]) / (bl[1] - fl[1])
    el.append(cube((xl + 0.25, 1.67, z - 0.25), (fr[0] - 0.25, 2.13, z + 0.25), "walnut", s, grain="x"))
    return el


def harpsichord_front(s):
    """键盘一段：两侧薄的琴身端板、键床、两层手键盘、名牌板、前板。"""
    el = []
    for x0, x1, out_face in ((1.0, 1.6, "west"), (14.4, 15.0, "east")):
        inner = "east" if out_face == "west" else "west"
        e = cube((x0, H_CASE_Y0, 0.0), (x1, 13.8, 6.0), "marble", s, grain="z",
                 faces={"south": None, "down": "walnut", out_face: None, "north": None, "up": None,
                        inner: {"mat": "walnut_light", "bevel": 0}})
        el.append(marble_faces(e, s, (out_face, "north", "up"), tag="hs_marble", paint=harpsichord_marble))
    e = cube((1.6, H_CASE_Y0, 0.2), (14.4, 11.0, 6.0), "walnut_light", s, grain="x",
             faces={"west": None, "east": None, "south": None, "down": "walnut", "north": None})
    world_face(e, "north", s, "hs_keybed", lambda p: np.where((p[..., 1] < H_CASE_Y0 + 0.95)[..., None],
                                                               tone("gilt", 2.5), tone("walnut_light", 2.0)), shade=(1.1, 1.0))
    el.append(e)
    lo, hi = HARPSICHORD_KEY_TOPS
    keys = {"west": None, "east": None, "down": None, "south": None,
            "up": {"mat": "ebony", "deco": keyboard_deco("hs", reverse=True), "bevel": 0},
            "north": {"mat": "ebony", "deco": key_front_deco("hs_front", reverse=True), "bevel": 0}}
    el.append(cube((1.6, 11.0, 0.4), (14.4, lo, 3.4), "ebony", s, faces=keys))
    el.append(cube((1.6, 11.0, 3.4), (14.4, 12.0, 6.0), "walnut_light", s, grain="x",
                   faces={"west": None, "east": None, "down": None, "south": None, "up": None}))
    el.append(cube((1.6, 12.0, 2.6), (14.4, hi, 5.2), "ebony", s, faces=dict(keys, down="ebony")))

    def nameboard(img, w, h, rng):
        out = img.copy()
        word = "##.#.###.##.#"   # 一行看不清字的题铭
        for i, ch in enumerate(word[:w]):
            if ch == "#":
                out[1, i] = tone("gilt", 2.9)
        return out
    el.append(cube((1.6, 12.0, 5.2), (14.4, 14.8, 6.0), "walnut_light", s, grain="x",
                   faces={"west": None, "east": None, "down": None, "south": None,
                          "north": {"mat": "walnut_light", "deco": kit.custom("hs_nameboard", nameboard)}}))
    e = cube((1.0, H_CASE_Y0, 6.0), (H_SPINE_X, H_RIM_TOP, H_FRONT_Z), "marble", s, grain="x",
             faces={"south": {"mat": "walnut_light", "bevel": 0, "deco": edge_light(1.12, 1.0, "hs_in")}, "down": "walnut",
                    "north": None, "west": None, "east": None, "up": None})
    el.append(marble_faces(e, s, ("north", "west", "east", "up"), tag="hs_marble", paint=harpsichord_marble))
    # 跳杆横档：胡桃木，正面一道金线
    xj = xsec(closed(harpsichord_outline()), H_JACK_Z[1])[0] + 0.3   # 左端埋进弯边的板里
    el.append(cube((xj, 14.0, H_JACK_Z[0]), (13.9, 15.3, H_JACK_Z[1]), "walnut", s, grain="x",
                   faces={"north": {"mat": "walnut", "deco": rows({0: ("gilt", 2.7)}, "hs_jack")}, "down": None}))
    return el


def harpsichord_case(s, line):
    el = []

    def rim_faces(i, n):
        return {"south": None, "up": None, "north": {"mat": "walnut_light", "bevel": 0, "deco": edge_light(1.12, 1.0, "hs_in")},
                "down": "walnut", "west": {"mat": "marble", "bevel": 0, "seed": 0}, "east": {"mat": "marble", "bevel": 0, "seed": 0}}
    el += band(line, H_CASE_Y0, H_RIM_TOP, H_RIM_T, "marble", s, rim_faces, tag="hs_rim",
               world={"south": (harpsichord_marble, SIDE_SHADE), "up": (H_MARBLE, None)})
    sb = harpsichord_soundboard_paint(line)
    el += band(offset_line(line, H_RIM_T), H_CASE_Y0 + 0.35, H_SB_Y + 0.2, H_LINER_T, "spruce", s, levels=3, tag="hs_liner",
               faces=lambda i, n: {"up": None, "north": None, "south": None, "west": {"mat": "spruce", "bevel": 0, "seed": 0}, "east": {"mat": "spruce", "bevel": 0, "seed": 0}, "down": "walnut"},
               world={"up": (sb, None)})
    outer = closed(offset_line(line, 0.08))
    inner = closed(offset_line(line, H_RIM_T + H_LINER_T))
    for x0, x1, za, zb in cover(outer, inner, H_FRONT_Z):
        e = cube((x0, H_CASE_Y0 + 0.28, za), (x1, H_SB_Y, zb), "spruce", s,
                 faces={"up": None, "north": None, "south": None, "west": None, "east": None, "down": "walnut"})
        world_face(e, "up", s, "hs_sb", sb)
        el.append(e)
    for zc in (14.0, 22.0, 29.0):
        el.append(cube((H_SPINE_X, H_RIM_TOP - 0.6, zc - 0.7), (H_SPINE_X + 0.22, H_RIM_TOP, zc + 0.7), "gilt", s, faces={"west": None}))
    return el


def harpsichord_lid(s, line):
    lid_line = [(1.0, H_LID_Z0)] + [p for p in line if p[1] > H_LID_Z0 + 1e-6] + [(H_SPINE_X, H_LID_Z0)]
    H, D = H_RIM_TOP, 1.3
    poly = closed(lid_line)
    under = night_sky_paint(poly)
    top = marble_paint(13)

    def lid_faces(i, n):
        f = {"up": None, "down": None, "south": "gilt", "north": {"mat": "marble", "bevel": 0}, "west": {"mat": "marble", "bevel": 0, "seed": 0}, "east": {"mat": "marble", "bevel": 0, "seed": 0}}
        if i == 0:
            f["west"] = "gilt"
        if i == n - 1:
            f["east"] = "gilt"
        return f
    el = band(lid_line, H, H + H_LID_T, D, "marble", s, lid_faces, tag="hs_lid", levels=2,
              world={"up": (top, None), "down": (under, None)})
    outer = closed(offset_line(lid_line, 0.06))
    inner = closed(offset_line(lid_line, D))
    for x0, x1, za, zb in cover(outer, inner, H_LID_Z0 + 0.07):
        fs = {"up": None, "down": None, "west": None, "east": None, "north": None, "south": None}
        if abs(za - H_LID_Z0 - 0.07) < 1e-6:
            fs["north"] = "gilt"
        e = cube((x0, H + 0.12, za), (x1, H + H_LID_T - 0.12, zb), "marble", s, faces=fs)
        world_face(e, "up", s, "hs_lid_top", top)
        world_face(e, "down", s, "hs_lid_under", under)
        el.append(e)
    return kit.rigid(el, kit.euler(z=-H_LID_ANGLE), (H_SPINE_X, H, 0.0))


def harpsichord_prop(s, line):
    th = math.radians(H_LID_ANGLE)
    zp = 20.0
    xb = min(p[0] for p in offset_line(line, 0.4) if abs(p[1] - zp) < 2.2) + 0.2
    xt = xb + 2.6
    lo = 0.12
    t = (H_SPINE_X - xt + lo * math.sin(th)) / math.cos(th)
    yt = H_RIM_TOP + t * math.sin(th) + lo * math.cos(th) + 0.2
    return [kit.segment((xb, H_RIM_TOP, zp), (xt, yt, zp), 0.45, 0.45, "walnut_light", s)]


def harpsichord_elements(s):
    line = harpsichord_outline()
    el = []
    el += harpsichord_stand(s)
    el += harpsichord_front(s)
    el += harpsichord_case(s, line)
    el += harpsichord_lid(s, line)
    el += harpsichord_prop(s, line)
    return el


def build_harpsichord(out):
    s = out.sheet("hexen_harpsichord")
    el = kit.prune_hidden(harpsichord_elements(s))
    out.block_model("hexen_harpsichord", el, [s], "large", particle=out.particle("marble"))
    out.particle_only("hexen_harpsichord_part", s.texture)
    out.item_parent("hexen_harpsichord", "hexen_harpsichord", kit.display(el))


# ———————————————————— 钢片琴 ————————————————————
# 一格的立式琴柜：键盘架在前面两条小弯腿上，后面是大理石纹琴柜（胡桃木底座和檐口、两侧胡桃木壁柱），
# 下半正面一块凸起的镶板（金线框、星辰花镶嵌），上半是深蓝衬布上的胡桃木花格出声窗。

C_BODY_Z = 6.6        # 琴柜正面
C_SHELF_Y = 9.4       # 键床底
C_MARBLE = marble_paint(21)


def celesta_panel(img, w, h, rng):
    """下半正面的出声窗（11×7）：浅胡桃木框，里面深蓝衬布上一层胡桃木菱格，正中一朵小金星辰花。"""
    out = img.copy()
    for y in range(h):
        for x in range(w):
            on = ((x + y) % 3 == 0) or ((x - y) % 3 == 0)
            out[y, x] = tone("walnut", 1.9) if on else tone("velvet_navy", 1.5)
    cx, cy = w // 2, h // 2
    for j in range(-2, 3):
        for i in range(-2, 3):
            if abs(i) + abs(j) <= 3:
                out[cy + j, cx + i] = tone("velvet_navy", 1.2)
    for j, row in enumerate(STARBLOOM_SMALL):
        for i, ch in enumerate(row):
            if ch == "#":
                out[cy - 1 + j, cx - 1 + i] = tone("gilt", 3.3 - 0.3 * j)
    out[0, :] = tone("walnut_light", 2.8)
    out[:, 0] = tone("walnut_light", 2.5)
    out[-1, :] = tone("walnut_light", 1.6)
    out[:, -1] = tone("walnut_light", 1.7)
    return out


def celesta_side_paint(pts):
    """侧面、背面：大理石纹，离边一像素一圈浅胡桃木细镶线（不用金，金只留给几处点缀）。"""
    img = C_MARBLE(pts)
    y = pts[..., 1]
    u = pts[..., 2] if np.ptp(pts[..., 0]) < 1e-6 else pts[..., 0]
    lo_u, hi_u = u.min() - 0.5, u.max() + 0.5
    line = ((np.abs(y - 3.5) < 0.5) | (np.abs(y - 12.5) < 0.5)) & (u > lo_u + 1.0) & (u < hi_u - 1.0)
    line |= ((np.abs(u - (lo_u + 1.5)) < 0.5) | (np.abs(u - (hi_u - 1.5)) < 0.5)) & (y > 3.0) & (y < 13.0)
    return np.where(line[..., None], tone("walnut_light", 2.4), img)


def celesta_elements(s):
    el = []
    gilt_top = rows({0: ("gilt", 2.6)}, "c_gilt_top")
    gilt_bottom = rows({-1: ("gilt", 2.6)}, "c_gilt_bottom")
    # 底座（上沿金线）、琴柜、檐口（下沿金线）
    el.append(cube((0.5, 0.0, C_BODY_Z - 0.4), (15.5, 2.0, 15.7), "walnut", s, grain="x",
                   faces={"down": None, **{f: {"mat": "walnut", "deco": gilt_top} for f in ("north", "south", "east", "west")}}))
    body = cube((0.8, 2.0, C_BODY_Z), (15.2, 14.0, 15.4), "marble", s,
                faces={"down": None, "up": None, "north": None, "east": None, "west": None, "south": None})
    world_face(body, "north", s, "c_front", C_MARBLE, shade=SIDE_SHADE)
    for f in ("east", "west", "south"):
        world_face(body, f, s, "c_side", celesta_side_paint, shade=SIDE_SHADE)
    el.append(body)
    el.append(cube((0.4, 14.0, C_BODY_Z - 0.4), (15.6, 16.0, 15.8), "walnut", s, grain="x",
                   faces={**{f: {"mat": "walnut", "deco": gilt_bottom} for f in ("north", "south", "east", "west")}}))
    # 两侧壁柱：浅胡桃木，柱头、柱础各一道金线
    for x0 in (0.6, 14.4):
        el.append(cube((x0, 2.0, C_BODY_Z - 0.3), (x0 + 1.0, 14.0, C_BODY_Z + 0.3), "walnut_light", s, grain="y",
                       faces={"up": None, "down": None,
                              **{f: {"mat": "walnut_light", "deco": [gilt_top, gilt_bottom]} for f in ("north", "east", "west")}}))
    # 下半正面的出声窗（凸出 0.2）
    el.append(cube((2.5, 2.2, C_BODY_Z - 0.2), (13.5, 9.2, C_BODY_Z), "walnut_light", s,
                   faces={"south": None, "north": {"mat": "walnut_light", "deco": kit.custom("c_panel", celesta_panel), "bevel": 0}}))
    # 键盘：键床（正面上沿胡桃木键前挡）、两侧端木、琴键、挡板
    e = cube((1.6, C_SHELF_Y, 2.0), (14.4, 10.5, C_BODY_Z), "marble", s,
             faces={"west": None, "east": None, "south": None, "down": "walnut", "north": None, "up": None})
    marble_faces(e, s, ("north", "up"), tag="c_keybed",
                 paint=lambda p: np.where((p[..., 1] > 9.95)[..., None], tone("walnut", 1.7), C_MARBLE(p)))
    el.append(e)
    for x0, x1 in ((0.8, 1.6), (14.4, 15.2)):
        e = cube((x0, C_SHELF_Y, 2.0), (x1, 12.4, C_BODY_Z - 0.3), "marble", s, grain="z",
                 faces={"south": None, "down": "walnut", "north": None, "east": None, "west": None, "up": None})
        el.append(marble_faces(e, s, ("north", "east", "west", "up"), tag="c_marble", paint=C_MARBLE))
    el.append(cube((1.6, 10.5, 2.3), (14.4, CELESTA_KEY_TOP, 5.8), "ivory", s,
                   faces={"west": None, "east": None, "south": None, "down": None,
                          "up": {"mat": "ivory", "deco": keyboard_deco("celesta", offset=1), "bevel": 0},
                          "north": {"mat": "ivory", "deco": key_front_deco("celesta", offset=1), "bevel": 0}}))
    el.append(cube((1.6, 10.5, 5.8), (14.4, 12.6, C_BODY_Z), "walnut", s, grain="x",
                   faces={"west": None, "east": None, "south": None, "down": None,
                          "north": {"mat": "walnut", "deco": rows({-1: ("felt_red", 2.0)}, "c_fall")}}))
    # 两条小弯腿（膝盖往前），金脚；一个黄铜踏板
    for k, x in enumerate((2.3, 13.7)):
        el.append(rod((x, C_SHELF_Y, 3.9), (x, 4.8, 3.1), 1.0, 1.0, "walnut", s, seed=k))
        el.append(rod((x, 4.8, 3.1), (x, 0.9, 3.7), 0.8, 0.8, "walnut", s, seed=k + 2))
        el.append(cube((x - 0.6, 0.0, 2.9), (x + 0.6, 0.9, 4.1), "gilt", s, faces={"down": None}))
    el.append(cube((7.2, 0.6, 4.4), (8.8, 1.1, C_BODY_Z - 0.4), "brass", s, grain="z", faces={"south": None}))
    return el


def build_celesta(out):
    s = out.sheet("hexen_celesta")
    el = kit.prune_hidden(celesta_elements(s))
    out.block_model("hexen_celesta", el, [s], "block", particle=out.particle("marble"))
    out.item_parent("hexen_celesta", "hexen_celesta", kit.display(el))


# ———————————————————— 星辰花琴凳（单人 / 双人） ————————————————————
# 坐的人面朝 −z（凳面前沿 z=3，同 StarbloomBenchBlock.FRONT_EDGE），凳面高 BENCH_TOP=10。
# 胡桃木腿上粗下细、金脚套；凳框上沿一道细金线；前后牙板正中一朵小星辰花；酒红丝绒坐垫鼓起来，扣出几个金扣的凹窝。

def tufted(buttons, key):
    """丝绒坐垫顶面的拉扣：每个扣一像素金色，四周一圈压暗（凹窝）。buttons 是 (u, v) 像素坐标。"""
    def fn(img, w, h, rng):
        out = img.copy()
        for u, v in buttons:
            for du, dv in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                if 0 <= u + du < w and 0 <= v + dv < h:
                    out[v + dv, u + du] = out[v + dv, u + du] * 0.78
            if 0 <= u < w and 0 <= v < h:
                out[v, u] = tone("gilt", 2.7)
        return out
    return kit.custom(("tufted", key, tuple(buttons)), fn)


def apron_flowers(xs, key):
    """牙板正面：底下一行浅胡桃木（线脚），给定 u 位置刻一朵小星辰花（浅胡桃木花瓣、金色花心）。"""
    def fn(img, w, h, rng):
        out = img.copy()
        out[-1, :] = tone("walnut_light", 2.4)
        for cx in xs:
            for j, row in enumerate(STARBLOOM_SMALL):
                for i, ch in enumerate(row):
                    x, y = cx - 1 + i, j
                    if ch == "#" and 0 <= x < w and 0 <= y < h:
                        out[y, x] = tone("walnut_light", 3.7 - 0.3 * j)
            if 0 <= cx < w and h > 1:
                out[1, cx] = tone("gilt", 3.3)
        return out
    return kit.custom(("apron_flowers", key, tuple(xs)), fn)


def bench_common(s, x0, x1, legs, flower_us, key, stretchers=()):
    """x0–x1 宽的琴凳（z 3–13）：legs 是腿中心 (x, z)；flower_us 是前后牙板上小花的位置（像素）。"""
    el = []
    for k, (cx, cz) in enumerate(legs):
        el.append(cube((cx - 0.8, 6.0, cz - 0.8), (cx + 0.8, 7.8, cz + 0.8), "walnut", s, grain="y", faces={"up": None}, seed=k))
        el.append(cube((cx - 0.6, 1.0, cz - 0.6), (cx + 0.6, 6.0, cz + 0.6), "walnut", s, grain="y", faces={"up": None}, seed=k))
        el.append(cube((cx - 0.7, 0.0, cz - 0.7), (cx + 0.7, 1.0, cz + 0.7), "gilt", s, faces={"down": None}))
    lx0, lx1 = legs[0][0] + 0.8, legs[-1][0] - 0.8
    flowers = apron_flowers(flower_us, key)
    for z0, z1, face in ((legs[0][1] - 0.5, legs[0][1], "north"), (legs[1][1], legs[1][1] + 0.5, "south")):
        el.append(cube((lx0, 4.0, z0), (lx1, 7.8, z1), "walnut", s, grain="x",
                       faces={"up": None, face: {"mat": "walnut", "deco": flowers, "bevel": 0}}))
    zl0, zl1 = legs[0][1] + 0.8, legs[1][1] - 0.8
    for xa, xb in ((legs[0][0] - 0.25, legs[0][0] + 0.25), (legs[-1][0] - 0.25, legs[-1][0] + 0.25)):
        el.append(cube((xa, 4.0, zl0), (xb, 7.8, zl1), "walnut", s, grain="z",
                       faces={"up": None, **{f: {"mat": "walnut", "deco": rows({-1: ("walnut_light", 2.4)}, "bench_side_apron"), "bevel": 0}
                                             for f in ("east", "west")}}))
    for (a, b) in stretchers:
        el.append(rod(a, b, 0.6, 0.6, "walnut", s))
    # 凳框：上沿一道细金线
    frame = rows({0: ("gilt", 2.7)}, "bench_frame")
    el.append(cube((x0, 7.8, 3.0), (x1, 9.4, 13.0), "walnut", s, grain="x",
                   faces={**{f: {"mat": "walnut", "deco": frame, "bevel": 0} for f in ("north", "south", "east", "west")}}))
    # 坐垫：下层满铺，上层内缩、鼓起
    el.append(cube((x0 + 0.35, 9.4, 3.35), (x1 - 0.35, 9.75, 12.65), "velvet", s, faces={"down": None}))
    return el


def bench_elements(s):
    """单人琴凳：x 1–15。"""
    legs = [(1.9, 3.9), (1.9, 12.1), (14.1, 3.9), (14.1, 12.1)]
    el = bench_common(s, 1.0, 15.0, legs, [5], "single")
    buttons = tufted([(2, 2), (6, 2), (10, 2), (4, 5), (8, 5)], "single")   # 顶面 13×8 像素
    el.append(cube((1.75, 9.75, 4.0), (14.25, BENCH_TOP, 12.0), "velvet", s, faces={"down": None, "up": {"mat": "velvet", "deco": buttons}}, seed=2))
    return el


def duet_bench_elements(s):
    """双人琴凳：两格宽（x 1–31，主块 x 0–16，另一格在 +x），四条腿 + 一副工字横撑。"""
    legs = [(1.9, 3.9), (1.9, 12.1), (30.1, 3.9), (30.1, 12.1)]
    stretch = [((1.9, 2.0, 4.5), (1.9, 2.0, 11.5)), ((30.1, 2.0, 4.5), (30.1, 2.0, 11.5)),
               ((2.2, 2.6, 8.0), (29.8, 2.6, 8.0))]
    el = bench_common(s, 1.0, 31.0, legs, [6, 20], "duet", stretchers=stretch)
    buttons = tufted([(x, 2) for x in range(2, 28, 4)] + [(x, 5) for x in range(4, 26, 4)], "duet")  # 顶面 29×8
    el.append(cube((1.75, 9.75, 4.0), (30.25, BENCH_TOP, 12.0), "velvet", s, faces={"down": None, "up": {"mat": "velvet", "deco": buttons}}, seed=3))
    return el


def build_benches(out):
    s = out.sheet("starbloom_bench")
    el = kit.prune_hidden(bench_elements(s))
    out.block_model("starbloom_bench", el, [s], "block")
    out.item_parent("starbloom_bench", "starbloom_bench", kit.display(el))
    s = out.sheet("starbloom_duet_bench")
    el = kit.prune_hidden(duet_bench_elements(s))
    out.block_model("starbloom_duet_bench", el, [s], "block")
    out.particle_only("starbloom_duet_bench_part", s.texture)
    out.item_parent("starbloom_duet_bench", "starbloom_duet_bench", kit.display(el))


def build(out):
    build_piano(out)
    build_harpsichord(out)
    build_celesta(out)
    build_benches(out)
