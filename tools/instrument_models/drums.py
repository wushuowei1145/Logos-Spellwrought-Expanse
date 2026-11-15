"""鼓与小件打击乐：大鼓、小军鼓、吊镲、三角铁，和演奏时画在手里的四种槌 / 棒。约定见 docs/INSTRUMENT_ART.md。

模型按 FACING=north 画；演奏者站在北面（脚 (8, 0, −6)）面朝 +z，所以他的右手在西边（−x）。Java 姿势靠的点（±0.3 像素）：
- 大鼓：鼓轴沿 z，鼓皮 z = 4.5（演奏者一侧）/ 11.5，半径 7，鼓心 (8, 8.6)；落槌点 (6.5, 10.0, 4.4)。碰撞箱 (1,0,4)–(15,16,12)。
- 小军鼓：鼓心 (8, ·, 6.5)，半径 ≈4.25，上鼓皮 y 13.5，鼓身 y 10–13.5；右手 (6.4, 13.6, 5.2)、左手 (9.6, 13.6, 5.2)。碰撞箱 (3,0,2)–(13,14,11)。
- 吊镲：镲心 (8, 15.0, 7.5)，半径 ≈5.5，北沿略低；镲面顶过 (5.0, 15.0, 4.2) 和 (11.0, 15.0, 4.2)。碰撞箱 (2,0,2)–(14,16,13)，镲顶不过 16.5。
- 三角铁：挂在 z = 6.0 的竖直平面里，顶角 (8, ≈15.2)，底边 y ≈9.6、x 4.75–11.25，右下角开口；落点底边前面 (8, 9.8, 5.7)。碰撞箱 (3,0,4)–(14,16,11)。
- 手里的槌 / 棒（items/held/）：握点 (8, 1.5, 8)，沿 +Y；大鼓槌毛球中心 y 11.0、军鼓棒尖 11.2、镲槌毛线头中心 10.8、三角铁棒尖 9.3。

做法同 percussion.py：圆的东西是正多边形柱（一条条长方体叠出来，端面按 GAP 错开），鼓皮、镲面按世界坐标画
（几条交叠的长方体颜色接得上），大鼓的鼓徽只落在两条轴对齐的长条上（像素格不歪）。
"""
import math

import numpy as np

import instrument_kit as kit
from instrument_kit import cube
from instrument_models.keyboards import world_face
from instrument_models.percussion import ring_wall, radial, drop

PREVIEW = [
    "block/hexen_bass_drum", "item/hexen_bass_drum",
    "block/hexen_snare_drum", "item/hexen_snare_drum",
    "block/hexen_suspended_cymbal", "item/hexen_suspended_cymbal",
    "block/hexen_triangle", "item/hexen_triangle",
    "item/hexen_bass_drum_beater_held", "item/hexen_snare_stick_held",
    "item/hexen_cymbal_mallet_held", "item/hexen_triangle_beater_held",
]

GAP = 0.06          # 交叠的平行面之间错开的距离（比 kit.MIN_GAP 多一点，转过的元素有浮点误差）
ALL = ("north", "south", "east", "west", "up", "down")

# —— 自己的材质：镲用的青铜（比黄铜暗一点、偏橙）。不参与破坏粒子的自动挑选（不改别的乐器的粒子）——
kit.PALETTE["bronze"] = kit.ramp("#3f240c", "#6c4416", "#9a6a25", "#c3923c", "#e6be69")
kit.MATERIALS["bronze"] = ("bronze", kit.metal, 2.3)
kit.BEVEL["bronze"] = 1.0
kit.PARTICLE_SKIP.add("bronze")


def pal(name, t):
    """调色板 name 在色阶 t（可以是数组）上的颜色。"""
    return kit._tone(kit.PALETTE[name], np.asarray(t, np.float32))


def mcol(mat, t):
    return pal(kit.MATERIALS[mat][0], t)


def keep(*faces, **specs):
    """只画列出的面：keep("north", "up", south={...})。"""
    out = {f: None for f in ALL}
    for f in faces:
        out.pop(f)
    out.update(specs)
    return out


# —— 几何 ——

def strips(c, apothem, lo, hi, mat, s, sides=12, axis="y", angles=None, top="keep", bottom="keep", side="keep",
           stagger_lo=True, stagger_hi=True, **kw):
    """正多边形柱，每条长方体按 angles 给的顺序叠（第一条最长、端面在最外）。
    axis="y"：c=(x, z)；"x"：c=(y, z)；"z"：c=(x, y)。top / bottom 是沿 axis 正 / 负的端面，side 是多边形的边面；
    值可以是 "keep"、None、材质名、面字典，或 fn(i, ang) → 面。stagger_lo / stagger_hi=False：那一端不错开（那一端的面不画时用）。"""
    n = sides // 2
    half_w = apothem * math.tan(math.pi / sides)
    if angles is None:
        angles = [180.0 / n * i for i in range(n)]
    out = []
    for i, ang in enumerate(angles):
        gl = i * GAP if stagger_lo else 0.0
        g = i * GAP if stagger_hi else 0.0
        a, b = c

        def pick(spec):
            if callable(spec):
                return spec(i, ang)
            return mat if spec == "keep" else spec
        if axis == "y":
            frm, to = (a - apothem, lo + gl, b - half_w), (a + apothem, hi - g, b + half_w)
            origin, key = (a, (lo + hi) / 2, b), "y"
            faces = {"north": None, "south": None, "up": pick(top), "down": pick(bottom), "east": pick(side), "west": pick(side)}
        elif axis == "x":
            frm, to = (lo + gl, a - half_w, b - apothem), (hi - g, a + half_w, b + apothem)
            origin, key = ((lo + hi) / 2, a, b), "x"
            faces = {"up": None, "down": None, "east": pick(top), "west": pick(bottom), "north": pick(side), "south": pick(side)}
        else:
            frm, to = (a - apothem, b - half_w, lo + gl), (a + apothem, b + half_w, hi - g)
            origin, key = (a, b, (lo + hi) / 2), "z"
            faces = {"up": None, "down": None, "south": pick(top), "north": pick(bottom), "east": pick(side), "west": pick(side)}
        e = cube(frm, to, mat, s, grain=axis, faces=faces, **kw)
        if abs(ang % 360) > 1e-9:
            r = {"origin": [round(v, 4) for v in origin], "x": 0, "y": 0, "z": 0}
            r[key] = round(ang, 4)
            e["rotation"] = r
        out.append(e)
    return out


def zturn(e, ang, c):
    """绕过 c=(x, y) 的 z 轴转 ang 度（+x 转向 +y）。"""
    if abs(ang % 360) > 1e-9:
        lo, hi = e["from"][2], e["to"][2]
        e["rotation"] = {"origin": [round(c[0], 4), round(c[1], 4), round((lo + hi) / 2, 4)], "x": 0, "y": 0, "z": round(ang, 4)}
    return e


def ring_z(c, a_in, a_out, z0, z1, mat, s, sides=12, faces=None, **kw):
    """绕 z 轴的正多边形圈（鼓圈）：每条边一块板，外沿正好在拐角相接、里沿互相插进去；奇数块前后各缩 GAP。"""
    L = 2 * a_out * math.tan(math.pi / sides)
    out = []
    for k in range(sides):
        g = GAP if k % 2 else 0.0
        fs = {"up": None, "down": None}
        fs.update(faces or {})
        e = cube((c[0] + a_in, c[1] - L / 2, z0 + g), (c[0] + a_out, c[1] + L / 2, z1 - g), mat, s, grain="y", faces=fs,
                 seed=k % 3, **kw)
        out.append(zturn(e, 360.0 / sides * k, c))
    return out


def on_z(e, ang, c):
    """沿 +x 画好的小件转到鼓轴周围 ang 度的位置。"""
    return zturn(e, ang, c)


# —— 画法 ——

def end_lines(mat="gilt", tone=3.0, at=1):
    """鼓身每一面两端各一列细线（顺着鼓轴的面，u = 鼓轴方向）。"""
    def fn(img, w, h, rng):
        for x in sorted({at, w - 1 - at}):
            if 0 <= x < w:
                img[:, x] = mcol(mat, tone - 0.25 * np.linspace(0, 1, h))
        return img
    return kit.custom(("drum_end_lines", mat, tone, at), fn)


def rows_deco(spec, key):
    """按行覆盖颜色：spec = {行(负数从下数): (材质, 色阶)}。"""
    def fn(img, w, h, rng):
        for r, (mat, t) in spec.items():
            rr = r if r >= 0 else h + r
            if 0 <= rr < h:
                img[rr, :] = mcol(mat, t)
        return img
    return kit.custom(("drum_rows", key), fn)


# ============================================================ 大鼓

BD_C = (8.0, 8.6)          # 鼓心 (x, y)
BD_ZN, BD_ZS = 4.5, 11.5   # 两面鼓皮
BD_HOOP = (6.1, 7.0)       # 鼓圈里外边心距
BD_HEAD = 6.5              # 鼓皮八边形的边心距（盖住鼓圈的十二边形内孔）
BD_SHELL = 6.45
BD_RODS = [30.0 + 60.0 * k for k in range(6)]   # 拉杆的位置（避开两侧的支架）
BD_HEAD_ORDER = [0.0, 90.0, 45.0, 135.0]        # 轴对齐的两条在最前：鼓徽的像素格是正的

# 鼓徽：深蓝圆章上一朵淡金小星辰花（7×7，第一行在上）
CREST = [
    "..nnn..",
    ".nnnnn.",
    "nnn#nnn",
    "nn###nn",
    "nn#n#nn",
    ".nnnnn.",
    "..nnn..",
]


def bd_head_paint(crest, pitch):
    """鼓皮按世界坐标画：奶油色小牛皮、中间亮、近鼓圈一圈深一点的皮边；crest 时在中心画鼓徽（按这一条的像素格取格子）。"""
    def paint(pts):
        x = pts[..., 0] - BD_C[0]
        y = pts[..., 1] - BD_C[1]
        r = np.hypot(x, y)
        t = 3.15 - 0.5 * (r / 6.1) ** 2 + 0.07 * np.sin(x * 1.7 + 0.6 * np.sin(y * 1.3)) * np.cos(y * 1.1 - 0.4)
        t = np.where(r > 5.45, t - 0.5, t)
        img = pal("skin", t)
        if crest:
            i = np.rint(x / pitch[0]).astype(int)
            j = np.rint(y / pitch[1]).astype(int)
            for (yy, xx) in zip(*np.nonzero((np.abs(i) <= 3) & (np.abs(j) <= 3))):
                ch = CREST[3 - j[yy, xx]][i[yy, xx] + 3]
                if ch == "g":
                    img[yy, xx] = pal("gilt", 2.7 + 0.25 * (j[yy, xx] / 3))
                elif ch == "n":
                    img[yy, xx] = pal("velvet_navy", 2.4 + 0.2 * (j[yy, xx] / 3))
                elif ch == "#":
                    img[yy, xx] = pal("gilt", 3.5 + 0.15 * (j[yy, xx] / 3))
        return img
    return paint


def bass_drum_elements(s):
    """音乐会大鼓：深色胡桃木鼓身（靠两头各一道浅胡桃木细线）、浅胡桃木鼓圈、六根黄铜拉杆（鼓身中段黄铜耳座、两头金色卡爪），
    奶油色小牛皮鼓皮，两面都画魔女之森鼓徽（深蓝圆章上一朵金星辰花：演奏者和观众都看得到，物品栏图标也是）；
    黑铁摇架：两侧 A 字架（顶上金色转轴座）、落地横档、底下一根拉档、四只金脚。"""
    el = []
    cx, cy = BD_C
    # 鼓身（端面藏在鼓皮后面）
    el += strips(BD_C, BD_SHELL, 4.6, 11.4, "walnut", s, sides=12, axis="z", top=None, bottom=None,
                 side={"mat": "walnut", "deco": end_lines("walnut_light", 2.4, 1)})
    # 鼓皮：前后各一个八边形（轴对齐的两条在最外），只画朝外的端面
    for zlo, zhi, face, crest in ((BD_ZN, BD_ZN + 0.5, "north", True), (BD_ZS - 0.5, BD_ZS, "south", True)):
        heads = strips(BD_C, BD_HEAD, zlo, zhi, "skin", s, sides=8, axis="z", angles=BD_HEAD_ORDER,
                       top=None, bottom=None, side=None)
        for i, e in enumerate(heads):
            ang = BD_HEAD_ORDER[i]
            hw = BD_HEAD * math.tan(math.pi / 8)
            pitch = (2 * BD_HEAD / kit._px(2 * BD_HEAD), 2 * hw / kit._px(2 * hw))
            if ang == 90.0:
                pitch = pitch[::-1]
            world_face(e, face, s, f"bd_head_{face}_{i}", bd_head_paint(crest and ang in (0.0, 90.0), pitch))
        el += heads
    # 鼓圈（前后）
    for z0, z1 in ((4.08, 5.08), (10.92, 11.92)):
        el += ring_z(BD_C, BD_HOOP[0], BD_HOOP[1], z0, z1, "walnut_light", s)
    # 拉杆、耳座、卡爪
    for ang in BD_RODS:
        part = [
            cube((cx + 6.95, cy - 0.15, 4.3), (cx + 7.25, cy + 0.15, 11.7), "brass", s, grain="z", faces=drop("north", "south")),
            cube((cx + 6.3, cy - 0.3, 7.4), (cx + 6.98, cy + 0.3, 8.6), "brass", s, faces=drop("west"), seed=1),
            cube((cx + 6.65, cy - 0.3, 4.0), (cx + 7.4, cy + 0.3, 4.75), "gilt", s, faces=drop("west")),
            cube((cx + 6.65, cy - 0.3, 11.25), (cx + 7.4, cy + 0.3, 12.0), "gilt", s, faces=drop("west")),
        ]
        el += [on_z(e, ang, BD_C) for e in part]
    # 摇架（黑铁）：两侧 A 字架（两条腿左右错开 0.06，不共面）+ 金色转轴座、落地横档 + 金脚，底下一根拉档
    for side in (-1, 1):
        def X(x):                          # 西侧的 x → 这一侧
            return x if side < 0 else 16.0 - x
        for zf, xc, seed in ((4.7, 1.39, 0), (11.3, 1.45, 1)):
            el.append(kit.segment((X(xc), 9.0, 8.0), (X(xc), 0.7, zf), 0.3, 0.5, "iron", s, faces={"west": None}, seed=seed))
        x0, x1 = sorted((X(1.0), X(1.66)))
        el.append(cube((x0, 0.35, 4.1), (x1, 0.9, 11.9), "iron", s, faces=drop("down")))
        for z0 in (4.1, 11.1):
            el.append(cube((x0, 0.0, z0), (x1, 0.35, z0 + 0.8), "gilt", s, faces=drop("up")))
        b0, b1 = sorted((X(1.0), X(1.6)))
        if side < 0:
            el += strips((cy, 8.0), 0.72, b0, b1, "gilt", s, sides=8, axis="x", top=None, stagger_hi=False)
        else:
            el += strips((cy, 8.0), 0.72, b0, b1, "gilt", s, sides=8, axis="x", bottom=None, stagger_lo=False)
    el.append(cube((1.66, 0.4, 7.7), (14.34, 0.85, 8.3), "iron", s, faces=drop("east", "west")))
    return el


# ============================================================ 小军鼓

SN_C = (8.0, 6.5)
SN_TOP = 13.5
SN_SHELL = 4.0
SN_HEAD_ORDER = [30.0, 150.0, 0.0, 60.0, 120.0, 90.0]   # 两个落点所在的长条在最上（顶面 13.5 / 13.44）
SN_RODS = [22.5 + 45.0 * k for k in range(8)]


def sn_head_paint(top):
    """军鼓皮（磨砂白膜）：中间略亮、近鼓圈一圈暗；下鼓皮暗一档。"""
    def paint(pts):
        x = pts[..., 0] - SN_C[0]
        z = pts[..., 2] - SN_C[1]
        r = np.hypot(x, z)
        base = 3.25 if top else 2.4
        t = base - 0.45 * (r / 3.9) ** 2 + 0.05 * np.sin(x * 2.1) * np.cos(z * 1.7)
        t = np.where(r > 3.45, t - 0.45, t)
        return pal("ivory" if top else "skin", t)
    return paint


def wires_deco():
    """响弦：一根根钢丝（每列一根，亮暗相间）。"""
    def fn(img, w, h, rng):
        for x in range(w):
            img[:, x] = mcol("steel", (3.4 if x % 2 == 0 else 1.6) - 0.3 * np.linspace(0, 1, h))
        return img
    return kit.custom(("snare_wires",), fn)


def snare_elements(s):
    """小军鼓：胡桃木鼓身、上下浅胡桃木鼓圈（同大鼓）、八根黄铜拉杆（黄铜耳座、金色杆头露出上鼓圈）、磨砂白上鼓皮；
    下鼓皮底下一排钢响弦，北面（演奏者一侧）黄铜响弦开关和一根金色扳手，南面一块挂弦板；
    黑铁三脚篮架：三条腿（金脚）、调高金箍、篮架托盘、三只托爪伸出鼓圈外再往上弯（金色爪头扣住下鼓圈），托盘后一颗金色倾角旋钮。"""
    el = []
    cx, cz = SN_C
    # 鼓身 + 上下鼓皮（端面按世界坐标画）
    shell = strips(SN_C, SN_SHELL, 10.0, SN_TOP, "walnut", s, sides=12, axis="y", angles=SN_HEAD_ORDER, top=None, bottom=None,
                   side="walnut")
    for i, e in enumerate(shell):
        world_face(e, "up", s, f"sn_head_top_{i}", sn_head_paint(True))
        world_face(e, "down", s, f"sn_head_bottom_{i}", sn_head_paint(False))
    el += shell
    # 鼓圈
    el += ring_wall(SN_C, 3.9, 12.95, 13.9, 0.55, "walnut_light", s)
    el += ring_wall(SN_C, 3.9, 9.6, 10.55, 0.55, "walnut_light", s, seed=1)
    # 拉杆与耳座
    for ang in SN_RODS:
        part = [
            cube((cx + 4.35, 9.45, cz - 0.15), (cx + 4.65, 14.0, cz + 0.15), "brass", s, faces={"up": {"mat": "gilt", "tone": 3.2}}),
            cube((cx + 3.85, 11.15, cz - 0.3), (cx + 4.38, 12.35, cz + 0.3), "brass", s, faces=drop("west"), seed=1),
        ]
        el += radial(part, ang, SN_C)
    # 响弦、开关、挂弦板
    el.append(cube((cx - 1.5, 9.8, cz - 3.3), (cx + 1.5, 9.9, cz + 3.3), "steel", s, grain="z",
                   faces=keep("down", "east", "west", "north", "south", down={"mat": "steel", "deco": wires_deco(), "bevel": 0})))
    el.append(cube((cx - 0.38, 10.75, 2.2), (cx + 0.38, 12.25, 2.55), "brass", s, faces=drop("south")))
    lever = cube((cx - 0.12, 11.0, 2.04), (cx + 0.12, 12.55, 2.2), "gilt", s, faces=drop("south"), seed=1)
    lever["rotation"] = {"origin": [cx, 11.2, 2.12], "x": 0, "y": 0, "z": -18}
    el.append(lever)
    el.append(cube((cx - 0.4, 10.6, 10.45), (cx + 0.4, 11.6, 10.85), "brass", s, faces=drop("north")))
    # 支架：三脚、调高管、金箍
    el += strips(SN_C, 0.65, 1.9, 2.9, "iron", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5])
    el += strips(SN_C, 0.38, 2.6, 8.6, "iron", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5], top=None, bottom=None, seed=1)
    el += strips(SN_C, 0.58, 5.5, 6.15, "gilt", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5])
    for a in (60.0, 180.0, 300.0):
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        fx, fz = cx + 4.0 * ca, cz - 4.0 * sa
        el.append(kit.segment((cx + 0.45 * ca, 2.4, cz - 0.45 * sa), (fx, 0.45, fz), 0.5, 0.45, "iron", s, faces={"west": None}))
        el.append(cube((fx - 0.45, 0, fz - 0.45), (fx + 0.45, 0.6, fz + 0.45), "gilt", s, faces=drop("down")))
    # 篮架：托盘、倾角旋钮、三只托爪（从鼓底伸出鼓圈外再往上弯，金色爪头扣住下鼓圈）
    el += strips((cx, cz), 0.75, 8.4, 9.1, "iron", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5], seed=2)
    el.append(cube((cx - 0.3, 8.5, cz + 0.83), (cx + 0.3, 9.0, cz + 1.35), "gilt", s))
    for a in (0.0, 120.0, 240.0):
        arm = [
            kit.segment((cx + 0.5, 8.8, cz), (cx + 4.75, 9.25, cz), 0.32, 0.35, "iron", s, faces={"west": None}),
            cube((cx + 4.55, 9.05, cz - 0.22), (cx + 4.92, 10.7, cz + 0.22), "iron", s, faces=drop("up"), seed=1),
            cube((cx + 4.3, 10.6, cz - 0.28), (cx + 4.98, 10.95, cz + 0.28), "gilt", s, faces=drop("down")),
        ]
        el += radial(arm, a, SN_C)
    return el


# ============================================================ 吊镲

CY_C = (8.0, 7.5)          # 镲心 (x, z)
CY_Y = 15.36               # 镲身（钟口外沿）的高度：镲面在两个落点处正好 15.0
CY_TILT = -3.0             # 绕 x 轴：北沿往下
CY_BOW = 4.0               # 镲身从钟口往外沿下斜的角度
CY_R0, CY_R = 1.4, 5.45    # 镲身板从钟口里面开始，外沿半径（外沿的边正对 ±x、±z，碰撞箱里放得下）
CY_PANELS = 16


def cymbal_top_paint(bell=False):
    """镲面：按世界坐标画同心车纹（一圈圈很淡的明暗）、两道对着的扇形高光（车削金属的反光），外沿暗一点；钟面更亮。"""
    def paint(pts):
        x = pts[..., 0] - CY_C[0]
        z = pts[..., 2] - CY_C[1]
        r = np.hypot(x, z)
        phi = np.arctan2(z, x)
        t = 2.35 + 0.42 * np.cos(2 * (phi + 0.7)) ** 3 * np.clip(r / 3.0, 0, 1)
        t = t + 0.15 * np.sin(r * 2.9)
        t = t - 0.35 * np.clip((r - 4.6) / 0.9, 0, 1)
        if bell:
            t = 2.9 + 0.3 * np.cos(2 * (phi + 0.7)) + 0.12 * np.sin(r * 3.2)
        return pal("bronze", t)
    return paint


def cymbal_elements(s):
    """吊镲：十六块微微下斜的青铜板拼成的镲身（顶面按世界坐标画车纹和扇形高光），两层钟、酒红毡垫、黄铜蝶形螺母，
    整只往北（演奏者一侧）低 3°；黑铁吊臂架：三脚（金脚）、两节立管（金色调高箍）、关节、斜吊臂（后端配重）、倾角器。"""
    cx, cz = CY_C
    yc = CY_Y
    tilt_origin = (cx, yc, cz)
    disc = []
    beta = CY_BOW
    w = 2 * CY_R * math.tan(math.pi / CY_PANELS) + 0.04
    r1 = CY_R0 + (CY_R - CY_R0) / math.cos(math.radians(beta))
    for k in range(CY_PANELS):
        e = cube((cx + CY_R0, yc - 0.22, cz - w / 2), (cx + r1, yc, cz + w / 2), "bronze", s, grain="x",
                 faces=keep("east", "down", east={"mat": "bronze", "tone": 2.0}, down={"mat": "bronze", "tone": 1.9}), seed=k % 2)
        e["rotation"] = {"origin": [cx + CY_R0, yc, cz], "x": 0, "y": 0, "z": -beta}
        disc += radial([e], 360.0 / CY_PANELS * k, (cx, cz), yc)
    # 钟（两层，只错开顶面）、毡垫、蝶形螺母
    bell = []
    bell += strips((cx, cz), 1.7, yc - 0.3, yc + 0.25, "bronze", s, sides=8, angles=[0, 45, 90, 135], bottom=None,
                   stagger_lo=False, top=None)
    bell += strips((cx, cz), 1.05, yc - 0.1, yc + 0.5, "bronze", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5], bottom=None,
                   stagger_lo=False, top=None, seed=1)
    bell += strips((cx, cz), 0.62, yc + 0.3, yc + 0.74, "felt_red", s, sides=8, angles=[0, 45, 90, 135], bottom=None,
                   stagger_lo=False)
    bell.append(cube((cx - 0.25, yc + 0.68, cz - 0.25), (cx + 0.25, yc + 0.98, cz + 0.25), "brass", s, faces=drop("down")))
    wing = cube((cx - 0.8, yc + 0.7, cz - 0.07), (cx + 0.8, yc + 1.06, cz + 0.07), "brass", s, faces=drop("down"), seed=1)
    wing["rotation"] = {"origin": [cx, yc + 0.85, cz], "x": 0, "y": 30, "z": 0}
    bell.append(wing)
    top = kit.rigid(disc + bell, kit.euler(x=CY_TILT), tilt_origin)
    # 镲身顶面、钟顶按世界坐标画（转过之后再画）
    for i, e in enumerate(top[:CY_PANELS]):
        world_face(e, "up", s, f"cy_top_{i}", cymbal_top_paint())
    for i, e in enumerate(top[CY_PANELS:CY_PANELS + 8]):
        world_face(e, "up", s, f"cy_bell_{i}", cymbal_top_paint(True))
    el = list(top)
    # 架子
    tx, tz = 11.2, 9.2                       # 立管
    el += strips((tx, tz), 0.62, 1.5, 2.5, "iron", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5])
    el += strips((tx, tz), 0.42, 2.4, 7.0, "iron", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5], top=None, bottom=None, seed=1)
    el += strips((tx, tz), 0.62, 6.8, 7.4, "gilt", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5])
    el += strips((tx, tz), 0.3, 7.3, 10.9, "iron", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5], top=None, bottom=None, seed=2)
    for a in (90.0, 210.0, 330.0):
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        fx, fz = tx + 2.7 * ca, tz - 2.7 * sa
        el.append(kit.segment((tx + 0.4 * ca, 2.0, tz - 0.4 * sa), (fx, 0.42, fz), 0.45, 0.42, "iron", s, faces={"west": None}))
        el.append(cube((fx - 0.42, 0, fz - 0.42), (fx + 0.42, 0.55, fz + 0.42), "gilt", s, faces=drop("down")))
    # 关节、吊臂、配重、倾角器、立杆
    el.append(cube((tx - 0.5, 10.8, tz - 0.5), (tx + 0.5, 11.8, tz + 0.5), "iron", s))
    el.append(cube((tx + 0.5, 11.0, tz - 0.25), (tx + 0.95, 11.6, tz + 0.25), "gilt", s, faces=drop("west")))
    K = (tx, 11.3, tz)
    T = (cx, 13.55, cz)
    d = [T[i] - K[i] for i in range(3)]
    L = math.sqrt(sum(v * v for v in d))
    B = tuple(K[i] - d[i] * 1.3 / L for i in range(3))
    el.append(kit.segment(B, T, 0.42, 0.42, "iron", s))
    el.append(cube((B[0] - 0.42, B[1] - 0.42, B[2] - 0.42), (B[0] + 0.42, B[1] + 0.42, B[2] + 0.42), "gilt", s, seed=1))
    el.append(cube((cx - 0.38, 13.3, cz - 0.38), (cx + 0.38, 14.05, cz + 0.38), "iron", s, seed=1))
    el.append(cube((cx - 0.13, 14.05, cz - 0.13), (cx + 0.13, yc - 0.3, cz + 0.13), "iron", s, faces=drop("up", "down")))
    return el


# ============================================================ 三角铁

TR_Z = 6.0
TR_ROD = 0.44
TR_BAR_Y = 13.6
TR_SIDE = 6.5
TR_POST = (12.2, 8.0)


def triangle_elements(s):
    """三角铁（钢条，右下角开口）挂在小架子上：胡桃木三脚（金脚套）、旋木立柱（底座鼓包、金色调高箍）、
    柱顶金色帽，一根黄铜横臂伸到三角铁顶上，臂端金夹子、一小段酒红挂绳。"""
    el = []
    h = TR_SIDE * math.sqrt(3) / 2
    bl = (8.0 - TR_SIDE / 2, TR_BAR_Y)
    br = (8.0 + TR_SIDE / 2, TR_BAR_Y)
    ap = (8.0, TR_BAR_Y + h)
    r2 = TR_ROD / 2

    def unit(p, q):
        d = (q[0] - p[0], q[1] - p[1])
        n = math.hypot(*d)
        return d[0] / n, d[1] / n
    # 底边（演奏者敲的前面 z = 6 − 0.21），左边、右边（右边下端留开口）
    el.append(cube((bl[0] - 0.25, TR_BAR_Y - r2, TR_Z - r2), (br[0] - 1.0, TR_BAR_Y + r2, TR_Z + r2), "silver", s, grain="x"))
    u = unit(bl, ap)
    p0 = (bl[0] - 0.2 * u[0], bl[1] - 0.2 * u[1], TR_Z)
    p1 = (ap[0] + 0.22 * u[0], ap[1] + 0.22 * u[1], TR_Z)
    el.append(kit.segment(p0, p1, TR_ROD - 2 * GAP, TR_ROD, "silver", s))
    u = unit(ap, br)
    p0 = (ap[0] - 0.22 * u[0], ap[1] - 0.22 * u[1], TR_Z)
    p1 = (br[0] - 1.1 * u[0], br[1] - 1.1 * u[1], TR_Z)
    el.append(kit.segment(p0, p1, TR_ROD, TR_ROD, "silver", s, seed=1))
    # 挂绳（一小圈酒红绳套住顶角，前后各比钢条宽出 0.06）与夹子
    el.append(cube((7.88, ap[1] - 0.28, TR_Z - r2 - GAP), (8.12, 19.6, TR_Z + r2 + GAP), "felt_red", s, faces=drop("up")))
    el.append(cube((7.66, 19.5, TR_Z - 0.36), (8.34, 19.98, TR_Z + 0.36), "gilt", s))
    # 横臂（黄铜，从柱顶斜到三角铁上方）
    px, pz = TR_POST
    el.append(kit.segment((px, 19.6, pz), (8.0, 19.6, TR_Z), 0.32, 0.32, "brass", s))
    # 立柱：三脚、鼓包、柱身、调高箍、柱顶帽
    for a in (60.0, 180.0, 300.0):
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        q0 = (px + 0.45 * ca, 1.7, pz - 0.45 * sa)
        q1 = (px + 1.6 * ca, 0.8, pz - 1.6 * sa)
        q2 = (px + 2.65 * ca, 0.42, pz - 2.65 * sa)
        el.append(kit.segment(q0, q1, 0.62, 0.5, "walnut", s, faces={"west": None}))
        m = tuple(q1[i] - 0.2 * (q2[i] - q1[i]) for i in range(3))
        el.append(kit.segment(m, q2, 0.52, 0.42, "walnut", s, faces={"west": None, "east": None}, seed=1))
        fx, fz = px + 2.65 * ca, pz - 2.65 * sa
        el.append(cube((fx - 0.4, 0, fz - 0.4), (fx + 0.4, 0.6, fz + 0.4), "gilt", s, faces=drop("down")))
    el += strips(TR_POST, 0.75, 0.45, 2.0, "walnut_light", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5],
                 bottom=None, side={"mat": "walnut_light", "deco": rows_deco({0: ("walnut", 1.6), -1: ("walnut", 1.4)}, "tr_knop")})
    el += strips(TR_POST, 0.4, 1.9, 19.4, "walnut", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5], top=None, bottom=None, seed=1)
    el += strips(TR_POST, 0.56, 10.2, 10.8, "gilt", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5])
    el += strips(TR_POST, 0.52, 19.35, 20.0, "gilt", s, sides=8, angles=[0, 45, 90, 135], bottom=None, stagger_lo=False, seed=1)
    return el


# ============================================================ 手里的槌 / 棒（items/held/，没有显示变换）

def fluff():
    """羊毛球：更碎的明暗。"""
    def fn(img, w, h, rng):
        return img * (0.94 + 0.12 * rng.random((h, w, 1)))
    return kit.custom(("fluff",), fn)


def yarn():
    """毛线缠的槌头：一道道横纹。"""
    def fn(img, w, h, rng):
        k = 1.0 + 0.1 * ((np.arange(h) % 2) - 0.5)
        return img * k[:, None, None]
    return kit.custom(("yarn",), fn)


def bass_drum_beater(s):
    """大鼓槌：金色尾帽、胡桃木握把、细一点的胡桃木杆、金箍、奶油色羊毛大球（八边柱 + 上下鼓起，中心 y 11.0）。"""
    el = [
        cube((7.68, 0, 7.68), (8.32, 0.35, 8.32), "gilt", s),
        cube((7.7, 0.35, 7.7), (8.3, 3.4, 8.3), "walnut", s, faces=drop("down")),
        cube((7.78, 3.4, 7.78), (8.22, 9.6, 8.22), "walnut", s, faces=drop("down", "up"), seed=1,
             deco=rows_deco({0: ("gilt", 3.1)}, "beater_ferrule")),
    ]
    el += strips((8, 8), 1.6, 10.15, 11.85, "felt", s, sides=8, angles=[0, 45, 90, 135], deco=fluff())
    el += strips((8, 8), 1.12, 9.42, 12.58, "felt", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5], deco=fluff(), seed=1)
    return el


def snare_stick(s):
    """军鼓棒：白枫木，握端粗、往上收细，尖上一颗小橄榄头（中心 y 11.2）；握端一道淡金细线。"""
    band = rows_deco({-3: ("gilt", 3.0)}, "stick_band")
    return [
        cube((7.72, 0, 7.72), (8.28, 6.2, 8.28), "maple", s, faces={"north": {"mat": "maple", "deco": band}, "south": {"mat": "maple", "deco": band},
                                                                    "east": {"mat": "maple", "deco": band}, "west": {"mat": "maple", "deco": band}}),
        cube((7.77, 6.2, 7.77), (8.23, 8.5, 8.23), "maple", s, faces=drop("down"), seed=1),
        cube((7.83, 8.5, 7.83), (8.17, 10.1, 8.17), "maple", s, faces=drop("down"), seed=2),
        cube((7.89, 10.1, 7.89), (8.11, 10.85, 8.11), "maple", s, faces=drop("down", "up"), seed=3),
        cube((7.77, 10.88, 7.77), (8.23, 11.52, 8.23), "maple", s, tone=2.7),
        cube((7.84, 10.78, 7.84), (8.16, 11.62, 8.16), "maple", s, tone=2.7, seed=1),
    ]


def cymbal_mallet(s):
    """吊镲槌：金色尾帽、胡桃木握把、白枫木细杆、金箍、魔女之森深蓝毛线头（中心 y 10.8，半径 ≈1.1）。"""
    el = [
        cube((7.7, 0, 7.7), (8.3, 0.35, 8.3), "gilt", s),
        cube((7.72, 0.35, 7.72), (8.28, 3.0, 8.28), "walnut", s, faces=drop("down")),
        cube((7.81, 3.0, 7.81), (8.19, 9.7, 8.19), "maple", s, faces=drop("down", "up")),
        cube((7.73, 9.45, 7.73), (8.27, 9.8, 8.27), "gilt", s, faces=drop("up")),
    ]
    el += strips((8, 8), 1.1, 10.2, 11.4, "velvet_navy", s, sides=8, angles=[0, 45, 90, 135], deco=yarn(), tone=2.4)
    el += strips((8, 8), 0.74, 9.7, 11.9, "velvet_navy", s, sides=8, angles=[22.5, 67.5, 112.5, 157.5], deco=yarn(), tone=2.4, seed=1)
    return el


def triangle_beater(s):
    """三角铁棒：金色尾帽、黑漆握把、金箍、细钢棒（尖 y 9.3）。"""
    return [
        cube((7.7, 0, 7.7), (8.3, 0.3, 8.3), "gilt", s),
        cube((7.72, 0.3, 7.72), (8.28, 3.0, 8.28), "lacquer_black", s, faces=drop("down")),
        cube((7.76, 3.0, 7.76), (8.24, 3.3, 8.24), "gilt", s, faces=drop("down")),
        cube((7.88, 3.3, 7.88), (8.12, 9.3, 8.12), "steel", s, faces=drop("down")),
    ]


# ============================================================ 写文件

def build(out):
    for name, sheet, fn, mat in (("hexen_bass_drum", "hexen_bass_drum", bass_drum_elements, "walnut"),
                                 ("hexen_snare_drum", "hexen_snare_drum", snare_elements, "walnut"),
                                 ("hexen_suspended_cymbal", "hexen_suspended_cymbal", cymbal_elements, "brass"),
                                 ("hexen_triangle", "hexen_triangle", triangle_elements, "walnut")):
        s = out.sheet(sheet)
        el = kit.prune_hidden(fn(s))
        out.block_model(name, el, [s], "block", particle=out.particle(mat))
        out.item_parent(name, name, kit.display(el))

    s = out.sheet("hexen_drum_tools")
    for name, fn in (("hexen_bass_drum_beater_held", bass_drum_beater), ("hexen_snare_stick_held", snare_stick),
                     ("hexen_cymbal_mallet_held", cymbal_mallet), ("hexen_triangle_beater_held", triangle_beater)):
        out.item_model(name, fn(s), [s], "tool", {})
