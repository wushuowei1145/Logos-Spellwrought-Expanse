"""管乐组：长笛、双簧管、巴松管、小号（手持，各有物品 / 演奏 / held 三份模型）和乐谱架（空 + 架上搁着各件乐器）。

坐标沿用旧模型（tools/instrument_art.py 的 flute/oboe/bassoon/trumpet_elements、music_stand_elements），
HeldRig 的支点和手位都落在原处：
- 长笛沿 x 横放，管轴 y=8、z=8，吹孔中心 (2.8, 8, 8)；
- 双簧管竖放，管轴 x=8、z=8，喇叭口在下，哨片尖 (8, 15.6, 8)，按键在 −Z 面；
- 巴松管竖放，管靴在下（y −5.4）、喇叭口在上（y 21.4），哨片尖 = BASSOON_REED；
- 小号沿 x 横放，号嘴前沿 (0.4, 9, 8)，活塞在 x 6–9；魔女之森旗挂在下面。
圆管都是正八边形柱（polygon_prism），喇叭口是一圈斜板拼的锥（cone()）；接口银箍、指孔、星辰花画在贴图上。
"""
import copy
import math

import numpy as np

import instrument_kit as kit
from instrument_kit import cube

PREVIEW = [
    "item/hexen_flute", "item/hexen_oboe", "item/hexen_bassoon", "item/hexen_trumpet",
    "item/hexen_music_stand", "block/hexen_music_stand_none",
    "block/hexen_music_stand_flute", "block/hexen_music_stand_oboe",
    "block/hexen_music_stand_bassoon", "block/hexen_music_stand_trumpet",
    "block/hexen_music_stand_violin", "block/hexen_music_stand_viola",
]

BANNER = "logos_dimension:block/instrument/hexenwald_banner"
BASSOON_REED = (12.33, 12.1, 12.5)   # 巴松管哨片尖（HeldRig.bassoon 的支点）
CUTOUT = "minecraft:cutout"


# —— 画法小工具 ——

def col(mat, tone):
    return kit._tone(kit.PALETTE[kit.MATERIALS[mat][0]], np.array(float(tone)))


def paint(key, fn):
    return kit.custom(("winds",) + tuple(key), fn)


def bands(key, marks, along="v"):
    """管身上的一圈圈（银箍、接口线）：marks = [(frac, mat, tone)]，frac 是沿管轴从 lo 端（0）到 hi 端（1）的位置。
    along="v"：竖管（行，第 0 行在上 = hi 端）；"u"：横管（列，第 0 列在 lo 端，北面要 flip_u，见 prism()）。"""
    marks = tuple(marks)

    def fn(img, w, h, rng):
        for f, mat, tone in marks:
            c = col(mat, tone)
            if along == "v":
                img[min(h - 1, max(0, int((1 - f) * h))), :] = c
            else:
                img[:, min(w - 1, max(0, int(f * w)))] = c
        return img
    return paint(("bands", key, marks, along), fn)


GAP = 0.06   # 叠在一起的端面错开的距离（比 MIN_GAP 多一点，转过的元素算出来的位置有浮点误差）
_CL = [1.0]  # 间隙倍数：谱架上缩小摆放的乐器按 1/缩放 造，缩完之后间隙仍 ≥ MIN_GAP


def gap():
    return GAP * _CL[0]


def for_scale(fn, s, k):
    """造一份准备缩到 k 倍摆放的乐器（错开的间隙先放大 1/k 倍）。"""
    _CL[0] = 1.0 / k
    try:
        return fn(s)
    finally:
        _CL[0] = 1.0


def prism(c, a, lo, hi, mat, s, axis="y", sides=8, caps=None, start=0.0, **kw):
    """正多边形柱，同 kit.polygon_prism（sides/2 条长方体依次转过去），只是端面错开 gap()；
    横管（axis x）把北面的 u 翻过来，让画上去的箍在每一面都对齐到同一个 x。caps={"top": ..., "bottom": ...}。"""
    n = sides // 2
    half_w = a * math.tan(math.pi / sides)
    els = []
    for i in range(n):
        g = i * gap()
        ang = start + 180.0 / n * i
        p, q = c
        if axis == "y":
            frm, to = (p - a, lo + g, q - half_w), (p + a, hi - g, q + half_w)
            rot = {"origin": [p, (lo + hi) / 2, q], "x": 0, "y": round(ang, 4), "z": 0}
            fs = {"north": None, "south": None}
            cap = {"up": "top", "down": "bottom"}
        else:
            frm, to = (lo + g, p - half_w, q - a), (hi - g, p + half_w, q + a)
            rot = {"origin": [(lo + hi) / 2, p, q], "x": round(ang, 4), "y": 0, "z": 0}
            fs = {"up": None, "down": None}
            cap = {"east": "top", "west": "bottom"}
        for face, k in cap.items():
            if caps and k in caps:
                fs[face] = caps[k]
        e = cube(frm, to, mat, s, grain=axis, faces=fs, **kw)
        if abs(ang % 360) > 1e-9:
            e["rotation"] = {"origin": [round(v, 4) for v in rot["origin"]], "x": rot["x"], "y": rot["y"], "z": rot["z"]}
        if axis == "x" and "north" in e["faces"]:
            r = e["faces"]["north"]["uv"]
            e["faces"]["north"]["uv"] = kit.FaceRef(r.sheet, r.slot, not r.flip_u, r.flip_v)
        els.append(e)
    return els


def cone(p0, rot, length, r0, r1, sides, t, s, outer, inner, rim=None, start=0.0, seed=0):
    """喇叭口：sides 块斜板拼成的空心锥，沿局部 +x 从半径 r0 张到 r1，再用 rot（3×3）转到实际方向、平移到 p0。
    板宽按大口一端的正多边形边长取（大口严丝合缝，小口一端彼此交叠，藏进接上来的管子里）。
    outer / inner / rim 是外面、里面、口沿那一窄条的面规格。"""
    phi = math.degrees(math.atan2(r1 - r0, length))
    slant = math.hypot(length, r1 - r0)
    w = 2 * r1 * math.tan(math.pi / sides)
    els = []
    for i in range(sides):
        e = cube((0, r0 - t, -w / 2), (slant, r0, w / 2), outer if isinstance(outer, str) else outer["mat"], s, grain="x",
                 faces={"up": outer, "down": inner, "east": rim or outer, "west": None}, seed=seed)
        e["rotation"] = {"origin": [0.0, round(r0, 4), 0.0], "x": 0, "y": 0, "z": round(phi, 4)}
        els += kit.rigid([e], kit.euler(x=start + 360.0 / sides * i), (0, 0, 0))
    return kit.shifted(kit.rigid(els, rot, (0, 0, 0)), *p0)


def rounded_box(cx, cz, ax, az, ch, lo, hi, mat, s, side=None, end=None, top=None, bottom=None, seed=0):
    """圆角长方柱（巴松管靴）：中间一块宽（±x 两个大面）、一块长（±z 两个窄面），四个 45° 斜角各一块。
    六块的顶 / 底面依次错开 gap()。side：±x 大面的规格；end：±z 面；top / bottom：顶 / 底。"""
    g = gap()
    lx, lz = az - ch, ax - ch     # x 大面的半宽（沿 z）、z 面的半宽（沿 x）
    caps = lambda: {"up": top, "down": bottom}
    els = [
        cube((cx - ax, lo, cz - lx), (cx + ax, hi, cz + lx), mat, s, grain="y", seed=seed,
             faces={"north": None, "south": None, "east": side or mat, "west": side or mat, **caps()}),
        cube((cx - lz, lo + g, cz - az), (cx + lz, hi - g, cz + az), mat, s, grain="y", seed=seed,
             faces={"east": None, "west": None, "north": end or mat, "south": end or mat, **caps()}),
    ]
    d = 0.75 * ch                 # 斜角块往里伸的深度（盖住两块之间的缺角）
    hw = ch * math.sqrt(2) / 2    # 斜角块半宽 = 斜边长的一半
    for sx in (1, -1):
        for sz in (1, -1):
            mx, mz = cx + sx * (ax - ch / 2), cz + sz * (az - ch / 2)
            ang = math.degrees(math.atan2(sx, sz))
            e = cube((mx - hw, lo + 2 * g, mz - d), (mx + hw, hi - 2 * g, mz), mat, s, grain="y", seed=seed,
                     faces={"north": None, "east": None, "west": None, "south": end or mat, **caps()})
            e["rotation"] = kit.rot_euler(0, ang, 0, (mx, (lo + hi) / 2, mz))
            els.append(e)
    return els


def bar(frm, to, mat, s, drop=(), **kw):
    """普通长方体；drop 里的面不画（贴在别的元素上、看不见）。"""
    faces = dict(kw.pop("faces", {}) or {})
    for f in drop:
        faces[f] = None
    return cube(frm, to, mat, s, faces=faces, **kw)


def rod(p0, p1, size, mat, s, **kw):
    return kit.segment(p0, p1, size, size, mat, s, **kw)


def arc(center, radius, a0, a1, n, size, depth, mat, s, ext=0.08, **kw):
    """xy 平面上的一段圆弧（弯管，center=(x, y, z)，角度从 +x 量起）：n 段直杆，两头各多伸出 ext 把外侧的缝盖住。
    depth=(d0, d1)：相邻两段沿 z 的厚度轮流取 d0、d1（交叠处的侧面错开，不闪）。"""
    cx, cy, cz = center
    pts = []
    for i in range(n + 1):
        a = math.radians(a0 + (a1 - a0) * i / n)
        pts.append((cx + radius * math.cos(a), cy + radius * math.sin(a), cz))
    els = []
    for k, (p, q) in enumerate(zip(pts, pts[1:])):
        d = [b - a for a, b in zip(p, q)]
        ln = math.sqrt(sum(v * v for v in d))
        u = [v / ln for v in d]
        els.append(kit.segment(tuple(p[i] - u[i] * ext for i in range(3)), tuple(q[i] + u[i] * ext for i in range(3)),
                               depth[k % 2], size, mat, s, **kw))
    return els


# —— 长笛 ——

def flute_elements(s):
    """银长笛：头节 / 主节 / 尾节一根八边形管（接口金箍、尾端开口画在贴图上），金色笛头冠、金色唇托上的吹孔，
    主节顶上一排银键杯、外侧一根连杆，尾节一个滚轴键。"""
    el = []
    L0, L1 = 0.8, 15.4
    f = lambda x: (x - L0) / (L1 - L0)
    tube_bands = bands("flute", [(f(5.1), "gilt", 3.0), (f(5.6), "silver", 1.9),
                                 (f(12.6), "gilt", 3.0), (f(13.1), "silver", 1.9), (f(15.2), "silver", 3.6)], along="u")
    el += prism((8, 8), 0.5, L0, L1, "silver", s, axis="x", deco=tube_bands, bevel=0,
                caps={"top": {"mat": "shadow", "tone": 1.6}, "bottom": None})
    # 笛头冠（金）：盖住管子的起点
    el += prism((8, 8), 0.58, 0.3, 1.25, "gilt", s, axis="x", caps={"top": "gilt", "bottom": {"mat": "gilt", "tone": 3.2}})
    # 唇托（金）与吹孔
    el.append(bar((2.15, 8.44, 7.56), (3.45, 8.6, 8.44), "gilt", s, drop=("down",), tone=2.8))
    el.append(bar((2.6, 8.6, 7.8), (3.0, 8.66, 8.2), "shadow", s, drop=("down",)))
    # 键杯：左手三个、右手三个、尾节一个；外侧（−z 偏上）一根连杆
    for i, x in enumerate((5.95, 7.0, 8.05, 9.55, 10.6, 11.65, 13.35)):
        el.append(bar((x, 8.5, 7.66), (x + 0.68, 8.64, 8.34), "silver", s, drop=("down",), tone=2.9 if i % 3 else 3.2))
    el.append(bar((5.6, 8.2, 7.38), (14.2, 8.38, 7.56), "silver", s, tone=2.2))
    el.append(bar((14.1, 8.15, 8.45), (14.9, 8.35, 8.66), "silver", s, tone=2.8))
    return kit.prune_hidden(el)


# —— 双簧管 ——

def oboe_elements(s):
    """乌木双簧管：喇叭口是一圈斜板（口沿一道细银边），喇叭颈一道银箍，两节管身八边形、接口银箍；
    −Z 面六个指孔环键和两根连杆，+Z 面托指架和八度键；顶上缠红线的哨座和芦苇哨片，尖在 (8, 15.6, 8)。"""
    el = []
    rim = {"mat": "silver", "tone": 3.2}
    bell_out = {"mat": "ebony"}
    el += cone((8, 2.7, 8), kit.euler(z=-90), 2.3, 0.58, 0.96, 8, 0.12, s, bell_out,
               {"mat": "shadow", "tone": 1.2, "deco": kit.gradient("u", 0.6, "start")}, rim)
    el += prism((8, 8), 0.68, 2.36, 2.86, "silver", s, caps={"top": "silver", "bottom": "silver"})
    low = bands("oboe_low", [(0.97, "silver", 3.2)])
    el += prism((8, 8), 0.6, 2.65, 8.1, "ebony", s, deco=low, bevel=0,
                caps={"top": {"mat": "silver", "tone": 3.0}, "bottom": {"mat": "shadow"}})
    up = bands("oboe_up", [(0.98, "silver", 3.3), (0.02, "silver", 2.6)])
    el += prism((8, 8), 0.53, 8.05, 13.45, "ebony", s, deco=up, bevel=0, caps={"top": {"mat": "silver", "tone": 3.0}, "bottom": None})
    # 哨座（红线）与哨片
    el.append(bar((7.74, 13.4, 7.74), (8.26, 14.35, 8.26), "felt_red", s, drop=("down",)))
    el.append(bar((7.6, 14.35, 7.88), (8.4, 15.6, 8.12), "spruce", s, drop=("down",), tone=3.0))
    # 指孔环键（−Z 面）：下节右手三个，上节左手三个
    for y in (3.6, 4.75, 5.9):
        el.append(bar((7.75, y, 7.27), (8.25, y + 0.34, 7.46), "silver", s, drop=("south",), tone=2.6))
    for y in (9.2, 10.35, 11.5):
        el.append(bar((7.76, y, 7.33), (8.24, y + 0.34, 7.5), "silver", s, drop=("south",), tone=2.6))
    # 连杆：上节在左前、下节在右前
    el.append(bar((7.38, 8.7, 7.38), (7.53, 12.9, 7.53), "silver", s, tone=2.0))
    el.append(bar((8.47, 2.95, 7.38), (8.62, 7.8, 7.53), "silver", s, tone=2.0))
    # 背面（+Z）：八度键板、右手托指架；侧面的低音键、侧键
    el.append(bar((7.76, 11.9, 8.5), (8.24, 12.9, 8.68), "silver", s, drop=("north",), tone=3.0))
    el.append(bar((7.72, 5.9, 8.55), (8.28, 6.45, 9.05), "silver", s, drop=("north",), tone=2.8))
    el.append(bar((8.55, 2.95, 7.8), (8.76, 3.95, 8.2), "silver", s, drop=("west",), tone=2.8))
    el.append(bar((7.26, 9.7, 7.84), (7.46, 10.7, 8.16), "silver", s, drop=("east",), tone=2.8))
    return kit.prune_hidden(el)


# —— 巴松管 ——

def bassoon_elements(s):
    """红褐枫木巴松管：圆角管靴（底下银色 U 形盖、两个大侧面上一朵金色星辰花印记），长管 + 喇叭节（口沿象牙白），
    翼管（朝演奏者一面鼓起，三个指孔），银色弯管（一段段沿曲线转过去）和哨片，托手架、连杆与几个键。"""
    el = []
    boot_side = {"mat": "amber", "deco": [bands("bsn_boot", [(0.96, "silver", 3.0)]),
                                           kit.stamp([".#.", "#o#", "#.#"], "gilt", 2.1, center_tone=3.7)]}
    boot_end = {"mat": "amber", "deco": bands("bsn_boot", [(0.96, "silver", 3.0)])}
    el += rounded_box(8, 8.1, 1.3, 2.0, 0.65, -5.0, 1.5, "amber", s, side=boot_side, end=boot_end,
                      top={"mat": "amber", "tone": 2.4}, bottom=None)
    g = gap()
    el += rounded_box(8, 8.1, 1.3 + g, 2.0 + g, 0.67, -5.4, -4.55, "silver", s, top={"mat": "silver", "tone": 3.0},
                      bottom={"mat": "silver", "tone": 1.8})
    # 长管（−Z 一侧）、喇叭节、象牙白口沿（顶面是黑洞洞的管口）
    el += prism((8, 7.25), 0.9, 1.3, 16.9, "amber", s, deco=bands("bsn_long", [(0.985, "silver", 3.1)]), bevel=0,
                caps={"top": None, "bottom": None})
    el += prism((8, 7.25), 1.0, 16.75, 20.6, "amber", s, deco=bands("bsn_bell", [(0.03, "silver", 3.1)]), bevel=0,
                caps={"top": None, "bottom": {"mat": "silver", "tone": 3.0}}, seed=1)
    el += prism((8, 7.25), 1.1, 20.45, 21.4, "ivory", s, caps={"top": {"mat": "shadow", "tone": 1.0}, "bottom": "ivory"})
    # 翼管（+Z 一侧）与朝演奏者那面的指孔鼓包
    el += prism((8, 9.0), 0.72, 1.3, 9.9, "amber", s, deco=bands("bsn_wing", [(0.97, "silver", 3.2)]), bevel=0,
                caps={"top": {"mat": "silver", "tone": 2.8}, "bottom": None}, seed=2)
    holes = kit.pixels([(0, 0), (0, 2), (0, 4)], "amber", 0.1)
    el.append(bar((7.42, 3.0, 9.5), (8.58, 7.5, 9.98), "amber", s, drop=("north",), faces={"south": {"mat": "amber", "deco": holes}}))
    # 弯管：从翼管顶上竖着出来，顺着曲线弯向 +x +z，接到哨片根
    u = [b - a for a, b in zip((11.75, 12.0, 11.95), BASSOON_REED)]
    n = math.sqrt(sum(v * v for v in u))
    u = [v / n for v in u]
    reed0 = tuple(BASSOON_REED[i] - 1.6 * u[i] for i in range(3))
    p = [(8.0, 9.75, 9.0), (8.0, 11.0, 9.0)]
    c1, c2 = (8.0, 12.2, 9.0), tuple(reed0[i] - 1.6 * u[i] for i in range(3))
    for k in range(1, 5):
        t = k / 4
        p.append(tuple((1 - t) ** 3 * p[1][i] + 3 * (1 - t) ** 2 * t * c1[i] + 3 * (1 - t) * t ** 2 * c2[i] + t ** 3 * reed0[i] for i in range(3)))
    for a, b in zip(p, p[1:]):
        el.append(rod(a, b, 0.34, "silver", s, tone=2.6))
    reed = {"mat": "spruce", "tone": 3.0, "deco": kit.pixels([(0, 0)], "felt_red", 2.2)}
    el.append(kit.segment(reed0, BASSOON_REED, 0.62, 0.24, "spruce", s,
                          faces={"north": dict(reed, flip_u=True), "south": reed, "up": reed, "down": reed, "west": None,
                                 "east": {"mat": "spruce", "tone": 3.3}}))
    # 托手架（右手）、管靴背面的两个键、长管外侧和翼管外侧的连杆、长管上端的两个低音键
    el.append(bar((7.85, -0.6, 10.05), (8.15, -0.3, 10.6), "silver", s, drop=("north",)))
    el.append(bar((7.45, -0.85, 10.55), (8.55, 0.25, 11.0), "rosewood", s))
    for y in (-3.4, -2.0):
        el.append(bar((7.7, y, 10.05), (8.3, y + 0.45, 10.26), "silver", s, drop=("north",), tone=3.0))
    el.append(bar((8.95, 2.2, 6.97), (9.13, 15.8, 7.15), "silver", s, tone=2.2))
    el.append(bar((7.06, 3.0, 8.92), (7.24, 9.4, 9.1), "silver", s, tone=2.2))
    for y in (12.6, 14.0):
        el.append(bar((7.66, y, 6.16), (8.34, y + 0.45, 6.4), "silver", s, drop=("south",), tone=3.0))
    return kit.prune_hidden(el)


# —— 小号 ——

def trumpet_elements(s, banner=True):
    """黄铜小号：银号嘴、八边形导管直通到喇叭（号嘴一端、喇叭一端的接口画箍），喇叭口两层斜板锥（喇叭颈一道金箍），
    三个八边形活塞筒、短杆和珍珠白按键，下面一圈主调音管（后弯 + 下管 + 前弯）；
    魔女之森王室旗用现成的贴图（#banner），两只金环挂在下管上。"""
    el = []
    # 号嘴：杯（八边形）+ 柄
    el += prism((9, 8), 0.44, 0.4, 1.0, "silver", s, axis="x", caps={"bottom": {"mat": "shadow", "tone": 1.4}, "top": "silver"})
    el.append(bar((0.95, 8.82, 7.82), (2.1, 9.18, 8.18), "silver", s, tone=2.6, drop=("west",)))
    # 导管 + 喇叭管（一根）
    f = lambda x: (x - 2.0) / (13.0 - 2.0)
    lead = bands("tpt_lead", [(f(2.1), "brass", 3.6), (f(2.6), "brass", 1.6), (f(12.2), "brass", 3.4)], along="u")
    el += prism((9, 8), 0.27, 2.0, 13.0, "brass", s, axis="x", deco=lead, bevel=0,
                caps={"top": {"mat": "shadow"}, "bottom": None})
    # 喇叭颈的金箍、两层喇叭口
    el += prism((9, 8), 0.52, 12.6, 13.35, "gilt", s, axis="x", caps={"top": "gilt", "bottom": "gilt"})
    inner = {"mat": "brass", "tone": 1.0, "deco": kit.gradient("u", 0.45, "start")}
    el += cone((12.9, 9, 8), kit.euler(), 1.75, 0.36, 0.86, 8, 0.1, s, {"mat": "brass"}, inner, seed=1)
    flare = {"mat": "brass", "deco": bands("tpt_flare", [(1.0, "brass", 3.8)], along="u")}
    el += cone((14.55, 9, 8), kit.euler(), 1.05, 0.8, 2.05, 12, 0.1, s, flare, inner, {"mat": "brass", "tone": 3.6}, seed=2)
    # 三个活塞筒（顶盖、底盖画在筒上）、短杆、珍珠白按键
    valve = bands("tpt_valve", [(0.95, "brass", 3.7), (0.05, "brass", 3.4)])
    for x in (6.4, 7.5, 8.6):
        el += prism((x, 8), 0.42, 6.4, 10.3, "brass", s, deco=valve, bevel=0,
                    caps={"top": {"mat": "brass", "tone": 3.2}, "bottom": {"mat": "brass", "tone": 2.2}})
        el.append(bar((x - 0.11, 10.3, 7.89), (x + 0.11, 10.62, 8.11), "silver", s, drop=("down",)))
        el.append(bar((x - 0.33, 10.62, 7.67), (x + 0.33, 10.86, 8.33), "brass", s, tone=2.8,
                      faces={"up": {"mat": "ivory", "tone": 3.6}}))
    # 主调音管：后弯（喇叭管弯）、下管、前弯
    el += arc((3.9, 7.975, 8), 1.025, 90, 270, 4, 0.46, (0.42, 0.3), "brass", s)
    el.append(bar((3.85, 6.73, 7.73), (11.65, 7.21, 8.27), "brass", s, drop=("east", "west")))
    el += arc((11.6, 7.975, 8), 1.025, -90, 90, 4, 0.46, (0.42, 0.3), "brass", s)
    if banner:
        el.append({"from": [3.75, -1.05, 7.98], "to": [11.25, 6.45, 8.02], "faces": {
            "north": {"texture": "#banner", "uv": [0, 0, 16, 16]},
            "south": {"texture": "#banner", "uv": [16, 0, 0, 16]}}})
        for x in (4.35, 10.35):
            el.append(bar((x, 6.55, 7.66), (x + 0.3, 7.35, 8.34), "gilt", s, tone=2.8))
    return kit.prune_hidden(el)


# —— 乐谱架 ——

def score_deco():
    """谱页：摊开的两页，每页两条淡淡的谱线、几个棕墨音符，中缝一道浅影。"""
    def fn(img, w, h, rng):
        line = col("parchment", 1.5)
        ink = col("walnut", 1.6)
        gut = col("parchment", 1.9)
        mid = w // 2
        for r in (1, 3):
            if r < h:
                img[r, 1:mid] = line
                img[r, mid + 1:w - 1] = line
        for x, y in ((2, 2), (3, 1), (8, 2), (7, 3)):
            if x < w and y < h:
                img[y, x] = ink
        img[:, mid] = gut
        return img
    return paint(("score", 2), fn)


def desk_back_deco():
    """谱台背面：一圈浅胡桃木镶线，中间一朵金色星辰花。"""
    light = col("walnut_light", 3.0)
    gold = kit.stamp(kit.STARBLOOM, "gilt", 3.2)

    def fn(img, w, h, rng):
        img[0, :] = light
        img[h - 1, :] = light * 0.85
        img[:, 0] = light
        img[:, w - 1] = light * 0.85
        return gold(img, w, h, rng)
    return paint(("desk_back",), fn)


DESK_TILT = kit.rot_axis("x", 20, (8, 10.6, 7.4))


def music_stand_elements(s):
    """胡桃木乐谱架：四条 S 形外撇的腿（脚尖金套），旋木立柱（底座鼓包、金色调高箍），
    向后斜 20° 的谱台（正面一张摊开的谱，背面镶线和金星辰花），下沿托条正面一道金线。都在 MusicStandBlock 的碰撞箱里。"""
    el = []
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        p0 = (8 + 0.5 * dx, 2.05, 8 + 0.5 * dz)
        p1 = (8 + 2.55 * dx, 0.95, 8 + 2.55 * dz)
        p2 = (8 + 4.45 * dx, 0.48, 8 + 4.45 * dz)
        el.append(kit.segment(p0, p1, 0.8, 0.64, "walnut", s, faces={"west": None}))
        q1 = tuple(p1[i] - 0.25 * (p2[i] - p1[i]) / 1.9 for i in range(3))
        el.append(kit.segment(q1, p2, 0.68, 0.54, "walnut", s, faces={"west": None, "east": None}, seed=1))
        x, z = 8 + 4.45 * dx, 8 + 4.45 * dz
        el.append(bar((x - 0.5, 0.0, z - 0.5), (x + 0.5, 0.72, z + 0.5), "gilt", s, tone=2.6))
    knop = bands("stand_knop", [(0.15, "walnut_light", 3.2), (0.85, "walnut_light", 3.0)])
    el += prism((8, 8), 0.98, 0.55, 2.45, "walnut", s, deco=knop, caps={"top": "walnut", "bottom": "walnut"})
    el += prism((8, 8), 0.5, 2.3, 11.1, "walnut", s, caps={"top": None, "bottom": None}, seed=1)
    el += prism((8, 8), 0.7, 6.0, 6.7, "gilt", s, caps={"top": "gilt", "bottom": "gilt"})
    # 谱台、谱、托条、背后的转轴座
    el.append(cube((2, 10.6, 7.1), (14, 16.15, 7.6), "walnut", s, rot=DESK_TILT, grain="x",
                   faces={"south": {"mat": "walnut", "deco": desk_back_deco()}, "up": "walnut_light"}))
    el.append(cube((2.5, 10.95, 6.98), (13.5, 15.85, 7.04), "parchment", s, rot=DESK_TILT, grain="x",
                   faces={"north": {"mat": "parchment", "deco": score_deco(), "bevel": 0.3}, "south": None}))
    el.append(bar((2.08, 10.25, 6.3), (13.92, 10.8, 7.55), "walnut", s, faces={"north": {"mat": "gilt", "tone": 2.7}}))
    el.append(bar((7.3, 10.5, 7.45), (8.7, 11.7, 8.75), "walnut_light", s, drop=("north",)))
    el.append(bar((7.65, 10.85, 8.75), (8.35, 11.45, 9.05), "gilt", s, drop=("north",), tone=2.8))
    return kit.prune_hidden(el)


def oboe_on_stand(so, s):
    """双簧管喇叭口朝下立在小木座上（缩到 0.8），微微后仰靠着托条。"""
    el = kit.shifted(kit.scaled(for_scale(oboe_elements, so, 0.8), 0.8, (8, 0.4, 8)), 4.2, 0.1, -3.6)
    el = kit.rigid(el, kit.euler(9, 0, 0), (12.2, 0.5, 4.4))
    base = prism((12.2, 4.4), 1.15, 0.0, 0.5, "walnut", s, caps={"top": {"mat": "walnut_light", "tone": 2.4}, "bottom": None}, seed=3)
    return el + base


def bassoon_on_stand(sb):
    """管靴着地立在谱架左前方（缩到 0.55），后仰并朝谱台一侧斜，喇叭口那头靠在谱台上。"""
    el = kit.shifted(kit.scaled(for_scale(bassoon_elements, sb, 0.55), 0.55, (8, -5.4, 8)), -6.8, 5.7, -3.4)
    return kit.rigid(el, kit.euler(14, 0, -4), (1.2, 0.3, 4.6))


def violin_on_stand(fam, kind):
    """小提琴 / 中提琴斜靠在谱架右前方，琴弓靠在左边（摆法同旧模型）。"""
    k, dx, dz, lean = (0.72, 3.6, -3.8, (14, (11.5, 0, 4.6))) if kind == "violin" else (0.8, 3.4, -3.9, (12, (11.4, 0, 4.4)))
    body = kit.shifted(kit.scaled(fam["held"], k, (8, 0, 8)), dx, 0.2, dz)
    body = kit.rigid(body, kit.euler(lean[0], 0, 0), lean[1])
    kb = 0.8 if kind == "violin" else 0.85
    bow = kit.shifted(fam["bow"], -5.2, 0, 1.6)       # 弓模型 → 旧小提琴模型里弓的位置
    bow = kit.shifted(kit.scaled(bow, kb, (3, 0, 9.6)), 0.6, 0.2, -5.6)
    bow = kit.rigid(bow, kit.euler(0, 0, -12), (3.4, 0, 4.0))
    return unfight(body + bow)


# 面往后退（沿法线反方向）时改 from / to 的哪一个分量、朝哪边改
_BACK = {"north": ("from", 2, 1), "south": ("to", 2, -1), "west": ("from", 0, 1), "east": ("to", 0, -1),
         "down": ("from", 1, 1), "up": ("to", 1, -1)}


def unfight(elements, clear=0.056, tries=16):
    """别的组的元素缩小摆放后，原来刚够 MIN_GAP 的间隙也跟着缩了：把每一对太近的平行面里靠后的那个面
    再往后退一点（改元素自己坐标里的 from / to，转动不变，贴图不变；元素太薄退不动就把前面那个面往前推）。
    改动都在几百分之一像素，看不出来。"""
    els = copy.deepcopy(elements)
    for _ in range(tries):
        hits = kit.zfights(els)
        if not hits:
            return els
        world = {(i, f): (pts, n) for i, f, pts, n in kit._faces_world(els)}
        need = {}
        for ia, fa, ib, fb, _d, _a in hits:
            (pa, na), pb = world[(ia, fa)], world[(ib, fb)][0]
            da, db = float(na @ pa[0]), float(na @ pb[0])
            back, front = ((ia, fa), (ib, fb)) if da <= db else ((ib, fb), (ia, fa))
            delta = clear - abs(da - db)
            key = (back, front)
            need[key] = max(need.get(key, 0.0), delta)
        done = set()
        for (back, front), delta in need.items():
            (i, f) = back
            if back in done or front in done:
                continue
            end, ax, sgn = _BACK[f]
            e = els[i]
            if e["to"][ax] - e["from"][ax] > delta + 0.02:
                e[end][ax] = round(e[end][ax] + sgn * delta, 4)
                done.add(back)
            else:
                (j, g) = front
                end, ax, sgn = _BACK[g]
                els[j][end][ax] = round(els[j][end][ax] - sgn * delta, 4)
                done.add(front)
    raise AssertionError(f"unfight: still {len(kit.zfights(els))} z-fights")


# —— 显示变换 ——

def mirror(r):
    return {"rotation": [r["rotation"][0], -r["rotation"][1], -r["rotation"][2]],
            "translation": [-r["translation"][0], r["translation"][1], r["translation"][2]], "scale": r["scale"]}


GUI_ROT = {"hexen_flute": (20, 200, -40), "hexen_trumpet": (20, 200, 0), "hexen_oboe": (20, 200, -40), "hexen_bassoon": (20, 200, -40)}
FIXED_ROT = {"hexen_flute": (0, 180, -40), "hexen_trumpet": (0, 180, 0), "hexen_oboe": (0, 180, -40), "hexen_bassoon": (0, 180, -40)}
# 第一 / 第三人称：旧模型调好的数，原样保留（左手由右手镜像）
FIRST_BASE = {"rotation": [0, 100, 15], "translation": [1, 2, 0], "scale": [0.5] * 3}
THIRD = {
    "hexen_flute": {"rotation": [0, 90, 55], "translation": [0, 2, 0.5], "scale": [0.6] * 3},
    "hexen_trumpet": {"rotation": [0, 90, 0], "translation": [0, 0, 1], "scale": [0.55] * 3},
    "hexen_oboe": {"rotation": [0, 90, -15], "translation": [0, 1.5, 1], "scale": [0.55] * 3},
    "hexen_bassoon": {"rotation": [0, 90, -10], "translation": [0, 2.5, 1], "scale": [0.42] * 3},
}
FIRST_PLAYING = {
    "hexen_flute": {"rotation": [0, 170, 0], "translation": [-4, 6, -2], "scale": [0.6] * 3},
    "hexen_trumpet": {"rotation": [-8, 105, 0], "translation": [-7, 6, -2], "scale": [0.6] * 3},
    "hexen_oboe": {"rotation": [70, 0, 0], "translation": [-7, 6, -2], "scale": [0.6] * 3},
    "hexen_bassoon": {"rotation": [0, 0, 30], "translation": [-10, 2.5, 3.5], "scale": [0.5] * 3},
}


def held_display(name, el, playing=False):
    gs, gt = kit.fit(el, GUI_ROT[name], 15.5, max_scale=1.6)
    rs, rt = kit.fit(el, (0, 0, 0), 8.0, depth=True)
    fs, ft = kit.fit(el, FIXED_ROT[name], 13.0)
    d = {"gui": {"rotation": list(GUI_ROT[name]), "translation": gt, "scale": [gs] * 3},
         "ground": {"rotation": [0, 0, 0], "translation": [rt[0], round(rt[1] + 2.0, 3), 0], "scale": [rs] * 3},
         "fixed": {"rotation": list(FIXED_ROT[name]), "translation": ft, "scale": [fs] * 3}}
    fp = FIRST_PLAYING[name] if playing else FIRST_BASE
    d["firstperson_righthand"] = fp
    d["firstperson_lefthand"] = mirror(fp)
    if not playing:
        d["thirdperson_righthand"] = THIRD[name]
        d["thirdperson_lefthand"] = mirror(THIRD[name])
    return d


# —— 写文件 ——

def build(out):
    sheets = {k: out.sheet(f"hexen_{k}") for k in ("flute", "oboe", "bassoon", "trumpet", "music_stand")}
    inst = {
        "hexen_flute": flute_elements(sheets["flute"]),
        "hexen_oboe": oboe_elements(sheets["oboe"]),
        "hexen_bassoon": bassoon_elements(sheets["bassoon"]),
        "hexen_trumpet": trumpet_elements(sheets["trumpet"]),
    }
    own = {"hexen_flute": sheets["flute"], "hexen_oboe": sheets["oboe"], "hexen_bassoon": sheets["bassoon"], "hexen_trumpet": sheets["trumpet"]}
    for name, el in inst.items():
        tex = {"banner": BANNER} if name == "hexen_trumpet" else None
        for suffix, playing in (("", False), ("_playing", True), ("_held", False)):
            out.item_model(name + suffix, el, [own[name]], "held", held_display(name, el, playing), textures=tex)
            if name == "hexen_trumpet":
                out.files[-1][1]["render_type"] = CUTOUT

    st = sheets["music_stand"]
    stand = music_stand_elements(st)
    out.block_model("hexen_music_stand_none", stand, [st], "block")
    out.item_parent("hexen_music_stand", "hexen_music_stand_none", kit.display(stand))
    variants = {
        "flute": (kit.shifted(inst["hexen_flute"], 0, 3.35, -1.3), [sheets["flute"]]),
        "trumpet": (kit.shifted(inst["hexen_trumpet"], 0, 2.3, -1.9), [sheets["trumpet"]]),
        "oboe": (oboe_on_stand(sheets["oboe"], st), [sheets["oboe"]]),
        "bassoon": (bassoon_on_stand(sheets["bassoon"]), [sheets["bassoon"]]),
    }
    fam = _violin_family(out)
    if fam:
        for kind in ("violin", "viola"):
            variants[kind] = (violin_on_stand(fam[kind], kind), list(fam[kind]["sheets"]))
    else:
        PREVIEW[:] = [r for r in PREVIEW if not r.endswith(("_violin", "_viola"))]
    for holds, (el, extra_sheets) in variants.items():
        trumpet = holds == "trumpet"
        out.block_model(f"hexen_music_stand_{holds}", stand + el, [st] + extra_sheets, "block",
                        textures={"banner": BANNER} if trumpet else None, extra={"render_type": CUTOUT} if trumpet else None)


def _violin_family(out):
    """弦乐组的小提琴 / 中提琴（strings.violin_family）；那边还没写好时返回 None，靠琴的两个谱架模型留给旧脚本。
    单独跑管乐组时（这个 Out 里弦乐组没造过），借来的元素照用，但弦乐组的贴图页不由这边写出（从 out.sheets 里拿掉，
    FaceRef 照样能算 uv）；那边出错就跳过两个靠琴的谱架。"""
    try:
        from instrument_models import strings
    except ImportError:
        return None
    fn = getattr(strings, "violin_family", None)
    if fn is None:
        return None
    if ("violin_family", "violin") in out.cache:          # 完整生成：弦乐组已经造过
        return {k: fn(out, k) for k in ("violin", "viola")}
    before = set(out.sheets)
    try:
        fam = {k: fn(out, k) for k in ("violin", "viola")}
    except Exception as e:  # noqa: BLE001 — 弦乐组还在改，单独跑管乐组时不让它拦住
        print(f"winds: strings.violin_family failed ({e!r}); violin/viola music stands keep the old models")
        fam = None
    for name in set(out.sheets) - before:
        del out.sheets[name]
    return fam
