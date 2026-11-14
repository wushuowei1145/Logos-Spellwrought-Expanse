"""打击乐与指挥组：定音鼓、钟琴、管钟、工位上画在手里的三种琴槌、指挥台（含摊开的总谱）、指挥棒贴图。

约定见 docs/INSTRUMENT_ART.md。坐标沿用 tools/instrument_art.py（模型按 FACING=north 画），Java 姿势靠的点：
- 定音鼓：鼓皮中心 (8, 8)、顶面 y≈13.3（StationPoses.drumHead 在鼓皮中心往鼓手一侧 2.5 像素处落槌）；踏板在南面（鼓手一侧）。
- 钟琴：演奏者站在北面朝南（InstrumentPosts.feet (8, 0, −6)）。前排自然音条 x 2–14、从 z 4 往后、顶面 y 12.3；
  槌落在模型的 (2.5 + 10.5·h, 12.3, 6)（StationPoses 里转 180° 之前的坐标），左长右短。
- 管钟：两格高；管中心 x = 3.5 + 1.37·i、z = 8，管顶 27.2，敲在管顶帽靠演奏者一侧 (x, 26.9, 7.3)。
- 琴槌（items/held/，不是物品）：握点 (8, 1.5, 8)，槌杆沿 +Y，槌头中心 y：钟琴 10.7、管钟 9.8、定音鼓 10.8。
- 指挥台：x 0–32，台面顶 y 8，指挥站在 (16, 8, 8) 面朝 −z；谱架按 PODIUM_TILT 斜着，位置不变。
"""
import math

import numpy as np
from PIL import Image

import instrument_kit as kit
from instrument_kit import cube, frame_line, STARBLOOM, STARBLOOM_SMALL

PREVIEW = [
    "block/hexen_timpani", "item/hexen_timpani",
    "block/hexen_glockenspiel", "item/hexen_glockenspiel",
    "block/hexen_tubular_bells", "item/hexen_tubular_bells",
    "block/hexen_podium", "block/hexen_podium_score", "item/hexen_podium",
    "item/hexen_glockenspiel_mallet_held", "item/hexen_timpani_mallet_held", "item/hexen_chime_hammer_held",
    "item/hexen_baton",
]

ALL = ("north", "south", "east", "west", "up", "down")


def only(*keep, **spec):
    """只留 keep 这几面（其余 None）；spec 给留下的面细调。"""
    d = {f: None for f in ALL if f not in keep}
    d.update(spec)
    return d


def drop(*gone, **spec):
    d = {f: None for f in gone}
    d.update(spec)
    return d


# —— 自己的材质 ——

def _kettle(w, h, along, rng, base=2.5):
    """抛光铜腔：几乎平的漆面一样干净，带一点锤打的小凹点（不画条纹，几块面拼起来看不出接缝）。"""
    t = np.full((h, w), base, np.float32) + rng.normal(0, 0.04, (h, w))
    for _ in range(int(w * h / 9)):
        t[rng.integers(0, h), rng.integers(0, w)] += rng.choice((-0.16, 0.12))
    return t


kit.PALETTE["rawhide"] = kit.ramp("#6e4f2a", "#8c6837", "#a88147", "#c09a5d", "#d6b57c")  # 生皮锤头（半透明的琥珀色）
kit.MATERIALS["kettle_copper"] = ("copper", _kettle, 2.5)
kit.MATERIALS["rawhide"] = ("rawhide", kit.lacquer, 2.2)


# —— 装饰 ——

def flower_line(pattern=STARBLOOM_SMALL, line="walnut_light", flower="gilt", line_tone=2.7, flower_tone=3.5):
    """「—✿—」：面中央一朵星辰花，两侧各一道细镶线（同一行，离边一像素）。"""
    lpal = kit.PALETTE[kit.MATERIALS[line][0]]
    fpal = kit.PALETTE[kit.MATERIALS[flower][0]]

    def fn(img, w, h, rng):
        ph, pw = len(pattern), len(pattern[0])
        x0, y0 = (w - pw) // 2, (h - ph) // 2
        mid = y0 + ph // 2
        if 0 <= mid < h:
            for x in range(1, w - 1):
                if x < x0 - 1 or x > x0 + pw:
                    img[mid, x] = kit._tone(lpal, np.array(line_tone - 0.3 * (x / w)))
        for j, row in enumerate(pattern):
            for i, c in enumerate(row):
                x, y = x0 + i, y0 + j
                if c != "." and 0 <= x < w and 0 <= y < h:
                    img[y, x] = kit._tone(fpal, np.array(flower_tone - 0.3 * j / max(1, ph - 1)))
        return img
    return kit.custom(("flower_line", tuple(pattern), line, flower, line_tone, flower_tone), fn)


def row_line(row, mat="gilt", tone=3.3):
    """在第 row 行（负数从下数）画一整行颜色：踏步前沿的铜包边、桌沿金线。"""
    col = kit._tone(kit.PALETTE[kit.MATERIALS[mat][0]], np.array(tone))

    def fn(img, w, h, rng):
        img[row % h, :] = col
        return img
    return kit.custom(("row_line", row, mat, tone), fn)


def bar_top(length_px, nodes=(0.224, 0.776), tone=3.25):
    """音条顶面（1 像素宽，顺着贴图 v）：亮银、靠演奏者一头略亮，两个支点钉只淡淡暗一点。"""
    pal = kit.PALETTE["silver"]

    def fn(img, w, h, rng):
        t = tone + 0.35 * (1 - (np.arange(h) + 0.5) / h)
        out = np.repeat(kit._tone(pal, t)[:, None, :], w, axis=1)
        for f in nodes:
            y = min(h - 1, int(f * h))
            out[y, :] = out[y, :] * 0.84
        return out
    return kit.custom(("glock_bar_top", length_px, nodes, tone), fn)


def head_top(ang, size_x, size_z, apothem):
    """定音鼓皮（正多边形柱的一条，绕 y 转了 ang 度）的顶面：按世界坐标画（离鼓心的距离 + 很淡的斑纹），
    几条交叠的长方体颜色接得上。中间亮、外圈一道深一点的皮边。"""
    pal = kit.PALETTE["skin"]

    def fn(img, w, h, rng):
        u = (np.arange(w) + 0.5) / w * size_x - size_x / 2
        v = (np.arange(h) + 0.5) / h * size_z - size_z / 2
        dx, dz = np.meshgrid(u, v)
        a = math.radians(ang)
        wx = dx * math.cos(a) + dz * math.sin(a)
        wz = -dx * math.sin(a) + dz * math.cos(a)
        r = np.hypot(wx, wz)
        t = 3.15 - 0.55 * (r / apothem) ** 2
        t = t + 0.10 * np.sin(wx * 1.9 + 0.7 * np.sin(wz * 1.3)) * np.cos(wz * 1.2 - 0.4)
        t = np.where(r > apothem - 1.0, t - 0.75, t)
        return kit._tone(pal, t)
    return kit.custom(("timpani_head", round(ang, 3), round(size_x, 3), round(size_z, 3), apothem), fn)


def score_page(side):
    """总谱的一页（约 6×6 像素）：隔行三道褐色谱线（整行），每行线上两颗深一点的音符；靠书脊那一列暗一点（装订缝的影）。
    远看是一页一页的横线，近看才认得出音符。"""
    pal = kit.PALETTE["parchment"]
    line = np.array([168, 142, 106], np.float32)
    ink = np.array([96, 70, 50], np.float32)
    notes = {"left": [(2, 1), (4, 1), (1, 3), (3, 3), (2, 5), (4, 5)],
             "right": [(1, 1), (3, 1), (2, 3), (4, 3), (1, 5), (3, 5)]}[side]

    def fn(img, w, h, rng):
        out = kit._tone(pal, np.full((h, w), 3.2, np.float32))
        for y in (1, 3, 5):
            if y < h:
                out[y, 1:w - 1] = line
        for x, y in notes:
            if x < w and y < h:
                out[y, x] = ink
        gutter = w - 1 if side == "left" else 0
        out[:, gutter] = out[:, gutter] * 0.82
        return out
    return kit.custom(("score_page", side, 3), fn)


# —— 几何 ——

def radial(elements, theta, c, y=0.0):
    """沿 +x 画好的一组元素绕 (c, y) 的竖轴转 theta 度（kit.euler 的 y 方向：+x 转向 −z）。"""
    if abs(theta % 360) < 1e-9:
        return elements
    return kit.rigid(elements, kit.euler(y=theta), (c[0], y, c[1]))


def bowl_panels(s, c, prof, sides, thick=0.3, mat="kettle_copper", start=0.0):
    """绕竖轴一圈的斜板拼成的旋转体（定音鼓铜腔）：prof 是从下往上的 (半径, y)，相邻两点之间一圈 sides 块板。
    每块板外表面正好落在剖面线上（厚度朝里），只画朝外的一面；宽度按这一圈较宽一端的正多边形边长，
    窄的一端相邻两块互相插进去（板与板之间都有角度，不共面）。"""
    tan = math.tan(math.pi / sides)
    out = []
    for j in range(len(prof) - 1):
        (r0, y0), (r1, y1) = prof[j], prof[j + 1]
        length = math.hypot(r1 - r0, y1 - y0)
        beta = math.degrees(math.atan2(-(r1 - r0), y1 - y0))
        w = 2 * max(r0, r1) * tan + 0.06
        for k in range(sides):
            e = cube((c[0] + r0 - thick, y0 - 0.03, c[1] - w / 2), (c[0] + r0, y0 + length + 0.03, c[1] + w / 2), mat, s,
                     grain="y", faces=only("east", east={"bevel": 0, "seed": j}))
            if abs(beta) > 1e-9:
                e["rotation"] = {"origin": [round(c[0] + r0, 4), round(y0, 4), round(c[1], 4)], "x": 0, "y": 0, "z": round(beta, 4)}
            out += radial([e], start + 360.0 / sides * k, c, y0)
    return out


STAGGER = 0.06            # 正多边形柱相邻两条的端面错开量：比 MIN_GAP 多一点，浮点误差下也不会被当成共面


def prism(c, apothem, lo, hi, mat, s, sides=8, axis="y", start=0.0, top="keep", bottom="keep", sides_face="keep", **kw):
    """同 kit.polygon_prism，但：相邻两条错开 STAGGER（kit 的 MIN_GAP 正好压线，浮点算出来可能差一点点被判成共面）；
    顶面 / 底面 / 侧面可以按每一条给不同的面：top/bottom/sides_face 是 "keep"（同材质）、None（不画）、材质名、面字典，
    或者 fn(i, ang, size_u, size_v) → 面（size 是这一面在模型里的两边长）。"""
    n = sides // 2
    half_w = apothem * math.tan(math.pi / sides)
    out = []
    for i in range(n):
        g = i * STAGGER
        ang = start + 180.0 / n * i
        a, b = c

        def pick(spec):
            if callable(spec):
                return spec(i, ang, 2 * apothem, 2 * half_w)
            return mat if spec == "keep" else spec
        if axis == "y":
            frm, to = (a - apothem, lo + g, b - half_w), (a + apothem, hi - g, b + half_w)
            origin, rot = (a, (lo + hi) / 2, b), ("y", ang)
            faces = {"north": None, "south": None, "up": pick(top), "down": pick(bottom), "east": pick(sides_face), "west": pick(sides_face)}
        elif axis == "x":
            frm, to = (lo + g, a - half_w, b - apothem), (hi - g, a + half_w, b + apothem)
            origin, rot = ((lo + hi) / 2, a, b), ("x", ang)
            faces = {"up": None, "down": None, "east": pick(top), "west": pick(bottom), "north": pick(sides_face), "south": pick(sides_face)}
        else:
            raise ValueError(axis)
        e = cube(frm, to, mat, s, grain=axis, faces=faces, **kw)
        if abs(ang % 360) > 1e-9:
            r = {"origin": [round(v, 4) for v in origin], "x": 0, "y": 0, "z": 0}
            r[rot[0]] = round(rot[1], 4)
            e["rotation"] = r
        out.append(e)
    return out


def prism_y(c, apothem, lo, hi, mat, s, **kw):
    return prism(c, apothem, lo, hi, mat, s, axis="y", **kw)


def ring_wall(c, apothem, y0, y1, thick, mat, s, sides=12, start=0.0, **kw):
    """闭合的正多边形薄圈（内表面的边心距 = apothem，厚度朝外）：每个拐角两段都延长，不留缝；段数须为偶数。"""
    rad = apothem / math.cos(math.pi / sides)
    pts = []
    for k in range(sides):
        a = math.radians(start + 360.0 / sides * k + 180.0 / sides)
        pts.append((c[0] + rad * math.cos(a), c[1] - rad * math.sin(a)))
    loop = [pts[-1]] + pts + [pts[0], pts[1]]
    els = kit.wall(loop, y0, y1, thick, mat, s, outside=1, **kw)[1:-1]
    for k, e in enumerate(els):
        if k % 2 == 0:  # kit.wall 里奇数段上下各缩了 MIN_GAP，再多缩一点（浮点）
            e["from"][1] = round(e["from"][1] + 0.01, 4)
            e["to"][1] = round(e["to"][1] - 0.01, 4)
    return els


# ============================================================ 定音鼓

T_C = (8.0, 8.0)          # 鼓心
T_HEAD_TOP = 13.3         # 鼓皮顶面（StationPoses.drumHead）
T_R = 6.25                # 鼓皮 / 铜腔上沿的边心距（旧模型鼓皮半径 6.3）
T_PROFILE = [(2.25, 3.3), (4.0, 4.6), (5.45, 6.6), (T_R, 9.3), (T_R, 12.65)]  # 铜腔剖面（下 → 上）


def timpani_elements(s):
    """定音鼓：十二边的抛光铜腔（竖直的上沿 + 三圈越往下越收的斜板 + 底盖），奶油色小牛皮鼓皮（外圈深一道皮边），
    黄铜鼓圈与六根调音拉杆（腔上的耳座、圈上的支耳、顶上金色 T 形调音把手），黑铁三脚架（金色小轮、三根斜撑），
    南面（鼓手一侧）一块黄铜踏板；鼓皮上干干净净（槌在演奏者手里）。"""
    el = []
    c = T_C
    # 铜腔
    el += bowl_panels(s, c, T_PROFILE, 12)
    el += prism_y(c, 2.3, 2.9, 3.9, "kettle_copper", s, sides=12, top=None, bevel=0)
    # 鼓皮：只画顶面（侧面藏在鼓圈里），每一条按自己的转角画
    el += prism_y(c, T_R, 12.55, T_HEAD_TOP + 0.1, "skin", s, sides=12, bottom=None, sides_face=None,
                  top=lambda i, ang, sx, sz: {"mat": "skin", "deco": head_top(ang, sx, sz, T_R), "bevel": 0})
    # 黄铜鼓圈（比鼓皮最高的一条高 0.15）
    el += ring_wall(c, T_R, 12.45, 13.55, 0.55, "brass", s, faces={"down": None})
    # 六根调音拉杆：腔上耳座、拉杆、圈上支耳、金色 T 形把手
    r_rod = T_R + 0.55 + 0.45
    for k in range(6):
        part = [
            cube((c[0] + 6.0, 8.7, c[1] - 0.35), (c[0] + r_rod + 0.35, 9.3, c[1] + 0.35), "brass", s, faces=drop("west")),
            cube((c[0] + r_rod - 0.2, 9.3, c[1] - 0.2), (c[0] + r_rod + 0.2, 13.85, c[1] + 0.2), "brass", s, faces=drop("up", "down")),
            cube((c[0] + T_R + 0.4, 12.9, c[1] - 0.35), (c[0] + r_rod + 0.35, 13.35, c[1] + 0.35), "brass", s, faces=drop("west")),
            cube((c[0] + r_rod - 0.2, 13.85, c[1] - 0.7), (c[0] + r_rod + 0.2, 14.2, c[1] + 0.7), "gilt", s),
        ]
        el += radial(part, 60.0 * k, c)
    # 三脚架：中柱、轮毂、三条腿、金色小轮、三根斜撑
    el.append(cube((6.9, 0.25, 6.9), (9.1, 1.25, 9.1), "iron", s, faces=drop("down")))
    el += prism_y(c, 0.6, 1.0, 3.35, "iron", s, sides=8, top=None, bottom=None)
    for theta in (90.0, 210.0, 330.0):
        a = math.radians(theta)
        fx, fz = c[0] + 6.1 * math.cos(a), c[1] - 6.1 * math.sin(a)
        el.append(kit.segment((c[0], 0.75, c[1]), (fx, 0.6, fz), 0.8, 0.7, "iron", s, faces=drop("west")))
        el.append(cube((fx - 0.55, 0, fz - 0.55), (fx + 0.55, 0.95, fz + 0.55), "gilt", s, faces=drop("down")))
        tx, tz = c[0] + 5.75 * math.cos(a), c[1] - 5.75 * math.sin(a)
        el.append(kit.segment((fx, 0.95, fz), (tx, 7.4, tz), 0.5, 0.5, "iron", s, faces=drop("west", "east")))
    # 踏板（南面）：连杆、跟座、铁踏板、黄铜踏面
    el.append(cube((7.6, 0.45, 9.0), (8.4, 1.05, 11.0), "iron", s, faces=drop("north")))
    el.append(cube((7.1, 0, 10.8), (8.9, 1.3, 11.7), "iron", s, faces=drop("down")))
    tilt = {"origin": [8, 0.6, 11.7], "x": -12, "y": 0, "z": 0}
    el.append(cube((6.7, 0.3, 11.7), (9.3, 0.85, 15.2), "iron", s, rot=dict(tilt), faces=drop("north")))
    el.append(cube((7.0, 0.85, 12.0), (9.0, 1.0, 14.9), "brass", s, rot=dict(tilt), faces=drop("down")))
    # 鼓皮上不搁槌：演奏者手里自己有一对（StationPoses），再搁两支一面鼓上就有四支，显得乱
    return el


# ============================================================ 钟琴

G_TOP = 12.3              # 前排音条顶面（StationPoses）
G_NAT = [(2.0 + 1.37 * i, 5.3 - 0.21 * i) for i in range(9)]                          # (x0, 长)，宽 1.0，从 z 4.0 往后
G_ACC = [(3.185 + 1.37 * j - 0.45, 3.7 - 0.12 * j) for j in range(8) if j not in (2, 6)]  # 后排半音（宽 0.9），从 z 9.9 往后


def glockenspiel_elements(s):
    """钟琴：胡桃木琴盒（内衬深蓝丝绒、前后沿金边），前排九根自然音条、后排六根半音条（抬高 0.3），
    都架在红毡条上（后一道毡条顺着音条节点斜着走）；两条胡桃木支架（八边立柱、金色脚套），两腿之间一块「—✿—」横板；
    琴盒后沿放两支象牙头小琴槌。"""
    el = []
    # 琴盒：底板（顶面是丝绒衬）+ 四道边框
    el.append(cube((1.0, 9.8, 3.0), (15.0, 10.6, 15.2), "walnut", s, faces={"up": "velvet_navy"}))
    rim = {"down": None}
    el.append(cube((1.0, 10.6, 3.0), (15.0, 11.6, 3.8), "walnut", s, faces=rim | {"up": "gilt", "north": {"mat": "walnut", "deco": frame_line("walnut_light", 0, 3.0)}}))
    el.append(cube((1.0, 10.6, 13.8), (15.0, 11.6, 15.2), "walnut", s, faces=rim | {"up": "walnut_light"}))
    el.append(cube((1.0, 10.6, 3.8), (1.8, 11.6, 13.8), "walnut", s, faces=rim | {"north": None, "south": None, "up": "walnut_light"}))
    el.append(cube((14.2, 10.6, 3.8), (15.0, 11.6, 13.8), "walnut", s, faces=rim | {"north": None, "south": None, "up": "walnut_light"}, seed=1))
    # 毡条：前排两道（后一道斜）、后排两道（后一道斜）
    felt = {"up": {"mat": "felt_red", "tone": 1.6}, "down": None}
    nat_y0 = G_TOP - 0.5
    acc_y0 = G_TOP - 0.2
    el.append(cube((1.8, 10.6, 4.6), (14.2, nat_y0, 5.3), "walnut_light", s, faces=felt | {"east": None, "west": None}))
    el.append(kit.segment((1.8, (10.6 + nat_y0) / 2, 8.15), (14.2, (10.6 + nat_y0) / 2, 6.75), 0.7, nat_y0 - 10.6, "walnut_light", s,
                          faces={"up": {"mat": "felt_red", "tone": 1.6}, "down": None}))
    el.append(cube((1.8, 10.6, 10.25), (14.2, acc_y0, 10.95), "walnut_light", s, faces=felt | {"east": None, "west": None}))
    el.append(kit.segment((1.8, (10.6 + acc_y0) / 2, 12.85), (14.2, (10.6 + acc_y0) / 2, 12.05), 0.7, acc_y0 - 10.6, "walnut_light", s,
                          faces={"up": {"mat": "felt_red", "tone": 1.6}, "down": None}))
    # 音条
    for i, (x0, length) in enumerate(G_NAT):
        px = kit._px(length)
        el.append(cube((x0, nat_y0, 4.0), (x0 + 1.0, G_TOP, 4.0 + length), "silver", s, grain="z",
                       faces={"down": None, "up": {"mat": "silver", "deco": bar_top(px), "bevel": 0}}))
    for j, (x0, length) in enumerate(G_ACC):
        px = kit._px(length)
        el.append(cube((x0, acc_y0, 9.9), (x0 + 0.9, G_TOP + 0.3, 9.9 + length), "silver", s, grain="z",
                       faces={"down": None, "up": {"mat": "silver", "deco": bar_top(px, tone=3.05), "bevel": 0}}, seed=1))
    # 支架：两条腿（立柱、上托、脚、金色脚套）
    for x in (2.3, 12.7):
        el += prism_y((x + 0.5, 9.1), 0.5, 0.95, 9.15, "walnut", s, sides=8, top=None, bottom=None)
        el.append(cube((x - 0.1, 9.0, 5.0), (x + 1.1, 9.8, 13.2), "walnut", s, faces=drop("up")))
        el.append(cube((x - 0.1, 0.3, 5.2), (x + 1.1, 1.1, 13.0), "walnut", s))
        for z0, z1 in ((4.5, 5.2), (13.0, 13.7)):
            el.append(cube((x - 0.2, 0, z0), (x + 1.2, 1.2, z1), "gilt", s, faces=drop("down")))
    # 两腿之间一块三像素高的横板，前后都是「—✿—」镶嵌
    board = {"mat": "walnut", "deco": flower_line()}
    el.append(cube((3.3, 2.4, 8.8), (12.7, 5.4, 9.4), "walnut", s, faces={"east": None, "west": None, "north": board, "south": board}))
    # 后沿上的两支小琴槌
    for z, x0, seed in ((14.15, 4.4, 0), (14.85, 5.4, 1)):
        el.append(cube((x0, 11.6, z - 0.18), (x0 + 6.6, 11.95, z + 0.18), "maple", s, faces=drop("down", "east"), seed=seed))
        el.append(cube((x0 + 6.6, 11.6, z - 0.42), (x0 + 7.45, 12.4, z + 0.42), "ivory", s, faces=drop("down"), seed=seed))
    return el


# ============================================================ 管钟

B_TUBES = [(3.5 + 1.37 * i, 22.0 - 1.4 * i) for i in range(8)]   # (x 中心, 管长含顶帽)，z 8，左长右短
B_TOP = 27.2              # 管顶（顶帽上沿）
B_CX = 8.3                # 管排的中线：框架左右对称在这条线上


def tubular_bells_elements(s):
    """管钟（两格高）：胡桃木底座（金色小脚、顶面一圈浅胡桃木镶线）、两根立柱（上下金箍）、三像素高的横梁（前后都是「—✿—」镶嵌），
    梁上一道金边檐口和两颗金顶饰；八根黄铜八边管、金色顶帽，各由一根细绳挂在梁下；
    管后一道红毡制音条，右柱后面的拉杆连到底座前的黄铜制音踏板；底座前半横放一把生皮锤。"""
    el = []
    # 底座与脚
    el.append(cube((0.7, 0.7, 4.6), (15.9, 3.0, 11.4), "walnut", s,
                   faces={"up": {"mat": "walnut", "deco": frame_line("walnut_light", 1, 3.0)}}))
    for x in (1.1, 14.3):
        for z in (5.0, 9.8):
            el.append(cube((x, 0, z), (x + 1.2, 0.7, z + 1.2), "gilt", s, faces=drop("up")))
    # 立柱与金箍
    for x0 in (B_CX - 7.1, B_CX + 5.9):
        el.append(cube((x0, 3.0, 7.4), (x0 + 1.2, 28.0, 8.6), "walnut", s, faces=drop("up", "down")))
        el.append(cube((x0 - 0.15, 3.0, 7.25), (x0 + 1.35, 3.6, 8.75), "gilt", s, faces=drop("down")))
        el.append(cube((x0 - 0.15, 27.4, 7.25), (x0 + 1.35, 28.0, 8.75), "gilt", s, faces=drop("up")))
    # 横梁、檐口、顶饰
    beam = {"mat": "walnut", "deco": flower_line()}
    el.append(cube((0.8, 28.0, 6.9), (15.8, 31.0, 9.1), "walnut", s, faces={"north": beam, "south": beam, "up": None}))
    el.append(cube((0.6, 31.0, 6.7), (16.0, 31.35, 9.3), "gilt", s, faces={"up": "walnut_light", "down": None}))
    for x0 in (B_CX - 7.1, B_CX + 5.9):
        el.append(cube((x0 + 0.15, 31.35, 7.55), (x0 + 1.05, 31.75, 8.45), "gilt", s, faces=drop("down")))
        el.append(cube((x0 + 0.35, 31.75, 7.75), (x0 + 0.85, 32.0, 8.25), "gilt", s, faces=drop("down"), seed=1))
    # 管、顶帽、挂绳（顶帽压住管口 0.4，错开的端面不留缝）
    for i, (x, length) in enumerate(B_TUBES):
        bottom = B_TOP - length
        el += prism_y((x, 8.0), 0.5, bottom, B_TOP - 0.2, "brass", s, sides=8, top=None, bottom="shadow")
        el += prism_y((x, 8.0), 0.62, B_TOP - 0.7, B_TOP, "gilt", s, sides=8)
        el.append(cube((x - 0.1, B_TOP, 7.9), (x + 0.1, 28.0, 8.1), "iron", s, faces=drop("up", "down")))
    # 制音条（红毡面贴着管）、拉杆、踏板
    el.append(cube((B_CX - 7.1, 24.4, 8.6), (B_CX + 7.1, 25.2, 9.2), "walnut", s, faces={"north": "felt_red"}))
    el.append(cube((B_CX + 6.3, 3.0, 9.2), (B_CX + 6.6, 24.4, 9.5), "iron", s, faces=drop("up", "down")))
    el.append(cube((10.9, 0, 4.1), (12.7, 0.7, 4.8), "iron", s, faces=drop("down", "south", "up")))
    el.append(cube((11.0, 0.2, 2.9), (12.6, 0.55, 4.6), "brass", s, rot={"origin": [11.8, 0.4, 4.6], "x": 8, "y": 0, "z": 0},
                   faces=drop("south")))
    # 底座前半横放的皮锤（浅色锤柄、八边生皮锤头，敲击面浅色）
    el.append(cube((3.6, 3.0, 5.55), (9.3, 3.4, 5.95), "walnut_light", s, faces=drop("down", "east")))
    el += prism((3.62, 5.75), 0.62, 9.3, 10.9, "rawhide", s, sides=8, axis="x", top="skin", bottom="skin")
    return el


# ============================================================ 琴槌（工位上画在手里）

def glockenspiel_mallet(s):
    """钟琴小琴槌：金色尾帽、深色握把、藤色细杆、金箍、象牙色硬槌头（中心 y 10.7）。"""
    return [
        cube((7.7, 0, 7.7), (8.3, 0.4, 8.3), "gilt", s),
        cube((7.74, 0.4, 7.74), (8.26, 3.2, 8.26), "walnut", s, faces=drop("down")),
        cube((7.82, 3.2, 7.82), (8.18, 9.55, 8.18), "maple", s, faces=drop("down", "up")),
        cube((7.74, 9.55, 7.74), (8.26, 9.95, 8.26), "gilt", s, faces=drop("up")),
        cube((7.35, 10.15, 7.35), (8.65, 11.25, 8.65), "ivory", s),
        cube((7.55, 9.95, 7.55), (8.45, 11.45, 8.45), "ivory", s, seed=1),
    ]


def timpani_mallet(s):
    """定音鼓槌：金色尾帽、胡桃木杆（握把略粗）、金箍、奶油色毡球（八边柱 + 上下鼓起，中心 y 10.8）。"""
    el = [
        cube((7.7, 0, 7.7), (8.3, 0.35, 8.3), "gilt", s),
        cube((7.72, 0.35, 7.72), (8.28, 3.0, 8.28), "walnut", s, faces=drop("down")),
        cube((7.8, 3.0, 7.8), (8.2, 9.9, 8.2), "walnut", s, faces=drop("down", "up"), seed=1),
        cube((7.72, 9.9, 7.72), (8.28, 10.2, 8.28), "gilt", s, faces=drop("up")),
    ]
    el += prism_y((8, 8), 0.85, 10.25, 11.35, "felt", s, sides=8, start=22.5)
    el.append(cube((7.4, 10.0, 7.4), (8.6, 11.6, 8.6), "felt", s, seed=1))
    return el


def chime_hammer(s):
    """管钟皮锤：金色尾帽、胡桃木锤柄、横着的八边生皮锤头（x 6.2–9.8，中心 y 9.8），两端敲击面是浅色皮。"""
    el = [
        cube((7.6, 0, 7.6), (8.4, 0.4, 8.4), "gilt", s),
        cube((7.65, 0.4, 7.65), (8.35, 9.1, 8.35), "walnut", s, faces=drop("down", "up")),
    ]
    el += prism((9.8, 8.0), 0.8, 6.2, 9.8, "rawhide", s, sides=8, axis="x", start=22.5, top="skin", bottom="skin")
    return el


# ============================================================ 指挥台

PODIUM_TILT = {"origin": [16, 17.0, 3.6], "x": -32, "y": 0, "z": 0}


def podium_elements(s, score=False):
    """指挥台（两格宽 x 0–32，指挥面朝 −z，站在 x=16、z=8，台面顶 y 8）。
    朝乐团一级踏步（立面「—✿—」镶嵌、前沿铜包边）；胡桃木台身四面是镶框的板，台面下一道金线脚，
    探出的台面上铺酒红丝绒垫（金滚边）；台后两角各一根栏柱（金帽、八边金顶饰），扶手、底档和六根细栏杆；
    台面前沿正中一座斜谱架（八边立杆、金箍、背面一朵星辰花），放了总谱时摊开一本红封面的谱子。"""
    el = []
    # 踏步
    el.append(cube((1.0, 0, 0), (31.0, 4.0, 3.0), "walnut", s,
                   faces={"down": None, "south": None,
                          "north": {"mat": "walnut", "deco": flower_line()},
                          "up": {"mat": "walnut", "deco": row_line(0, "brass", 3.0)}}))
    # 台身：侧面镶框板
    panel = {"mat": "walnut", "deco": frame_line("walnut_light", 1, 2.9)}
    el.append(cube((0.4, 0, 3.0), (31.6, 6.6, 15.6), "walnut", s,
                   faces={"down": None, "up": None, "east": panel, "west": panel, "south": panel}))
    el.append(cube((0.25, 6.6, 2.85), (31.75, 7.0, 15.75), "gilt", s, faces=drop("up", "down")))
    el.append(cube((0.0, 7.0, 2.6), (32.0, 7.6, 16.0), "walnut", s))
    el.append(cube((2.6, 7.6, 5.6), (29.4, 8.0, 13.4), "velvet", s,
                   faces={"down": None, "up": {"mat": "velvet", "deco": frame_line("gilt", 0, 2.9)}}))
    # 台后栏杆
    for x0 in (0.7, 29.5):
        el.append(cube((x0, 7.6, 13.9), (x0 + 1.8, 14.6, 15.7), "walnut", s, faces=drop("down", "up")))
        el.append(cube((x0 - 0.15, 14.6, 13.75), (x0 + 1.95, 15.0, 15.85), "gilt", s))
        el += prism_y((x0 + 0.9, 14.8), 0.75, 14.85, 16.3, "gilt", s, sides=8, start=22.5, bottom=None)
        el.append(cube((x0 + 0.6, 16.1, 14.5), (x0 + 1.2, 16.8, 15.1), "gilt", s, faces=drop("down"), seed=1))
    el.append(cube((2.5, 13.6, 14.1), (29.5, 14.5, 15.5), "walnut", s, faces={"east": None, "west": None, "up": "walnut_light"}))
    el.append(cube((2.5, 7.6, 14.3), (29.5, 8.3, 15.3), "walnut", s, faces=drop("east", "west", "down")))
    for x in (6.25, 10.15, 14.05, 17.95, 21.85, 25.75):
        el.append(cube((x - 0.35, 8.3, 14.45), (x + 0.35, 13.6, 15.15), "walnut", s, faces=drop("up", "down")))
    # 谱架：底座、八边立杆、金箍、托块、斜谱板与托条
    el.append(cube((14.0, 7.6, 2.6), (18.0, 8.8, 5.0), "walnut", s,
                   faces={"down": None, "north": {"mat": "walnut", "deco": row_line(-1, "gilt", 3.0)},
                          "south": {"mat": "walnut", "deco": row_line(-1, "gilt", 3.0)}}))
    el += prism_y((16.0, 3.6), 0.6, 8.6, 17.3, "walnut", s, sides=8, start=22.5, top=None, bottom=None)
    el += prism_y((16.0, 3.6), 0.8, 11.9, 12.5, "gilt", s, sides=8, start=22.5)
    el.append(cube((14.8, 16.2, 2.9), (17.2, 17.2, 4.3), "walnut", s, faces=drop("up")))
    back = {"mat": "walnut", "deco": flower_line(STARBLOOM, flower_tone=3.4)}
    el.append(cube((9.5, 17.0, 3.3), (22.5, 23.4, 3.9), "walnut", s, rot=dict(PODIUM_TILT),
                   faces={"north": back, "up": "gilt", "south": {"mat": "walnut", "deco": frame_line("walnut_light", 1, 2.8)}}))
    el.append(cube((9.5, 16.6, 3.9), (22.5, 17.4, 4.8), "walnut", s, rot=dict(PODIUM_TILT), faces={"up": "walnut_light", "north": None}))
    if score:
        el += score_book(s)
    return el


def score_book(s):
    """摊开的总谱：红封面露一圈边，两页纸从书脊往外微微翘起（各转 7°），整本跟着谱板斜过去。"""
    book = [cube((9.9, 17.4, 3.9), (22.1, 23.25, 4.0), "velvet", s, faces=only("south", "up", "east", "west"))]
    for side, x0, x1, ang in (("left", 10.2, 15.95, 7.0), ("right", 16.05, 21.8, -7.0)):
        outer = "west" if side == "left" else "east"
        page = cube((x0, 17.5, 4.0), (x1, 23.05, 4.3), "parchment", s,
                    faces=only("south", "up", "north", outer, south={"mat": "parchment", "deco": score_page(side), "bevel": 0},
                               north={"mat": "parchment", "tone": 2.2}))
        page["rotation"] = {"origin": [16.0, 17.5, 4.0], "x": 0, "y": ang, "z": 0}
        book.append(page)
    return kit.rigid(book, kit.euler(x=PODIUM_TILT["x"]), PODIUM_TILT["origin"])


# ============================================================ 指挥棒（16×16 平面贴图）

def baton_icon():
    """指挥棒：金色尾球、软木梨形握柄（靠尾球一端鼓起三像素宽、往上收成两像素）、金箍、白漆细杆渐细到笔尖，
    左下 → 右上（同旧图的对角线）。
    光从左上来：每一段左上一侧亮、右下一侧暗。"""
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    g = [tuple(int(v) for v in c) + (255,) for c in kit.PALETTE["gilt"]]
    k = [(104, 72, 44, 255), (146, 106, 66, 255), (182, 140, 92, 255), (210, 172, 122, 255)]   # 软木
    w = [tuple(int(v) for v in c) + (255,) for c in kit.PALETTE["ivory"]]
    pix = {
        # 尾球
        (1, 13): g[4], (2, 13): g[3], (1, 14): g[2], (2, 14): g[0],
        # 软木握柄：中线 x+y=15，左上 x+y=14 亮，右下 x+y=16 暗；中段一颗木孔
        (2, 12): k[3], (3, 12): k[2], (3, 13): k[1], (4, 12): k[1],
        (3, 11): k[3], (4, 11): k[1],
        (4, 10): k[3], (5, 10): k[2],
        (5, 9): k[3], (6, 9): k[1],
        # 金箍
        (6, 8): g[4], (7, 8): g[2], (7, 9): g[0],
        # 白漆细杆：靠握柄两格带一道浅灰的影子，往上渐细
        (7, 7): w[4], (8, 7): w[2], (8, 6): w[4], (9, 6): w[1],
        (9, 5): w[4], (10, 4): w[4], (11, 3): w[3], (12, 2): w[3], (13, 1): w[4],
    }
    for (x, y), c in pix.items():
        px[x, y] = c
    return img


# ============================================================ 写文件

def build(out):
    # 定音鼓
    s = out.sheet("hexen_timpani")
    el = kit.prune_hidden(timpani_elements(s))
    out.block_model("hexen_timpani", el, [s], "large")
    out.item_parent("hexen_timpani", "hexen_timpani", kit.display(el))

    # 钟琴
    s = out.sheet("hexen_glockenspiel")
    el = kit.prune_hidden(glockenspiel_elements(s))
    out.block_model("hexen_glockenspiel", el, [s], "block")
    out.item_parent("hexen_glockenspiel", "hexen_glockenspiel", kit.display(el))

    # 管钟（两格高，上半格只给粒子）
    s = out.sheet("hexen_tubular_bells")
    el = kit.prune_hidden(tubular_bells_elements(s))
    out.block_model("hexen_tubular_bells", el, [s], "large")
    out.particle_only("hexen_tubular_bells_part", s.texture)
    out.item_parent("hexen_tubular_bells", "hexen_tubular_bells", kit.display(el))

    # 琴槌（items/held/ 指向这些模型；不是物品，没有显示变换）
    s = out.sheet("hexen_mallets")
    for name, fn in (("hexen_glockenspiel_mallet_held", glockenspiel_mallet), ("hexen_timpani_mallet_held", timpani_mallet),
                     ("hexen_chime_hammer_held", chime_hammer)):
        out.item_model(name, fn(s), [s], "tool", {})

    # 指挥台
    s = out.sheet("hexen_podium")
    el = kit.prune_hidden(podium_elements(s, False))
    out.block_model("hexen_podium", el, [s], "large")
    out.block_model("hexen_podium_score", kit.prune_hidden(podium_elements(s, True)), [s], "large")
    out.particle_only("hexen_podium_part", s.texture)
    out.item_parent("hexen_podium", "hexen_podium", kit.display(el))

    # 指挥棒：重画 16×16 贴图，模型和手持变换同旧脚本（姿势调过，不改）
    path = kit.ASSETS / "textures/item/hexen_baton.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    baton_icon().save(path, optimize=True)
    out.raw(kit.ASSETS / "models/item/hexen_baton.json", {
        "parent": "minecraft:item/handheld", "textures": {"layer0": "logos_dimension:item/hexen_baton"},
        "display": {
            "thirdperson_righthand": {"rotation": [0, -90, 111], "translation": [0, -0.74, -3.33], "scale": [0.6, 0.6, 0.6]},
            "thirdperson_lefthand": {"rotation": [0, 90, -111], "translation": [0, -0.74, -3.33], "scale": [0.6, 0.6, 0.6]},
            "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 1.43, 0.31], "scale": [0.45, 0.45, 0.45]},
            "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 1.43, 0.31], "scale": [0.45, 0.45, 0.45]}}})
