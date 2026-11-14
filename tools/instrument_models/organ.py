"""管风琴组：演奏台（3 宽 / 旧存档的 2 宽）、风箱、锡音管、木音管、号角管。

约定见 docs/INSTRUMENT_ART.md，玩法和方块见 docs/PIPE_ORGAN.md。两张贴图页：
- hexen_organ_console：两种演奏台共用。
- organ_pipes：风箱、锡管、木管、号角管共用（材质一致）。

坐标（FACING=north，键盘 / 管口 / 喇叭朝北，z 小的一侧）：
- 3 宽演奏台 x −16–32、y 0–32、z 0–16，主块在下排正中（x 0–16）；旧 2 宽 x 0–32，主块在左下。
- 下层手键盘顶面 y = KEY_TOP = 14.8（MusicianPoses.keyTop），键盘前沿 z≈3.2、两边各留琴身边。
- 音管一格一节：bottom / single 带锥形管脚和管口，top / single 带开口，middle 只有管身；
  管身的截面、明暗在每一节都一样，上下两节在 y 0 / 16 处没有倒角线，叠起来是一根完整的管。
"""
import math

import numpy as np

import instrument_kit as kit
from instrument_kit import cube, custom, rigid, euler, STARBLOOM

KEY_TOP = 14.8      # 下层手键盘顶面（MusicianPoses.keyTop PIPE_ORGAN）
CAP_STEP = 0.06     # 多边形柱端盖错开的高度（比 MIN_GAP 稍大，免得浮点误差判成共面）

PREVIEW = [
    "block/hexen_organ_console_wide", "item/hexen_organ_console", "block/hexen_organ_console",
    "block/organ_wind_chest", "item/organ_wind_chest",
    "block/organ_tin_pipe_single", "block/organ_tin_pipe_bottom", "block/organ_tin_pipe_middle", "block/organ_tin_pipe_top",
    "item/organ_tin_pipe",
    "block/organ_wood_pipe_single", "block/organ_wood_pipe_bottom", "block/organ_wood_pipe_middle", "block/organ_wood_pipe_top",
    "item/organ_wood_pipe",
    "block/organ_trumpet_pipe", "item/organ_trumpet_pipe",
]


# —— 材质（新名字，登记进工具箱）——

def _tin_body(w, h, along, rng, base=2.35):
    """抛光锡管身：顺着管长的一条柔和高光，偏左；没有亮点噪声（一面墙几十根管，噪声远看是花的）。"""
    if along == "n":
        return kit.metal(w, h, along, rng, base)
    cross, length = (w, h) if along == "v" else (h, w)
    c = (np.arange(cross) + 0.5) / cross
    prof = base + 0.55 * np.exp(-((c - 0.28) ** 2) / 0.03) - 0.4 * c
    t = np.repeat(prof[:, None], length, axis=1) + rng.normal(0, 0.03, (cross, length))
    return t.T if along == "v" else t


def _pipe_wood(w, h, along, rng, base=2.0):
    """木音管的管板：克制的胡桃木直纹（纹线浅，远看是干净的一块），左右两条棱一亮一暗；上下不加倒角，叠起来没有接缝。"""
    if along == "n":
        return kit.wood(w, h, along, rng, base)
    t = kit._streaks(w, h, along, rng, 0.8, 0.32, base)
    if along == "v" and w >= 3:
        t[:, 0] += 0.45
        t[:, -1] -= 0.5
    return t


kit.MATERIALS["organ_tin"] = ("tin", _tin_body, 2.35)
kit.MATERIALS["organ_pipe_walnut"] = ("walnut", _pipe_wood, 2.0)

WOODS = {"walnut", "walnut_light", "maple", "spruce", "rosewood", "organ_pipe_walnut"}


def C(mat, t):
    """材质 mat 第 t 色阶的颜色（RGB 浮点）。"""
    return kit._tone(kit.PALETTE[kit.MATERIALS[mat][0]], np.array(float(t)))


def box(frm, to, mat, s, faces=None, grain=None, **kw):
    """cube() 加一条：木头的面如果正好是木纹的端面（年轮），改成顺着这一面自己的长边走纹（除非明确给了 grain）。"""
    size = [to[i] - frm[i] for i in range(3)]
    if grain is None:
        grain = "xyz"[int(np.argmax(size))]
    faces = dict(faces or {})
    for face, ((ua, _), (va, _), na) in kit.FACE_AXES.items():
        if na != grain:
            continue
        spec = faces.get(face, mat)
        if spec is None:
            continue
        spec = {"mat": spec} if isinstance(spec, str) else dict(spec)
        if spec.get("mat", mat) in WOODS and "grain" not in spec:
            spec["grain"] = ua if size[kit.AXIS[ua]] >= size[kit.AXIS[va]] else va
            faces[face] = spec
    return cube(frm, to, mat, s, faces=faces, grain=grain, **kw)


def prism(c, apothem, lo, hi, mat, s, sides=8, axis="y", start=0.0, side=None, top=None, bottom=None, **kw):
    """正多边形柱（同 kit.polygon_prism 的拼法），但只有给了端盖的那一头才错开：
    没有端盖的一头所有条都齐平，上下两格的管身接起来没有缺口。side 是侧面的覆盖（材质 / 装饰 / 倒角）。"""
    n = sides // 2
    # 每条宽 1.5%：相邻两面在棱上交叉约 0.02 像素，而不是恰好碰在一条线上（那样光栅化会漏出一条透光的细缝）
    half_w = apothem * math.tan(math.pi / sides) * 1.015
    els = []
    for i in range(n):
        g = i * CAP_STEP
        l0 = lo + (g if bottom is not None else 0.0)
        h1 = hi - (g if top is not None else 0.0)
        ang = start + 180.0 / n * i
        a, b = c
        if axis == "y":
            frm, to = (a - apothem, l0, b - half_w), (a + apothem, h1, b + half_w)
            origin = (a, (lo + hi) / 2, b)
            rot = {"origin": [round(v, 4) for v in origin], "x": 0, "y": round(ang, 4), "z": 0}
            sides_ = ("east", "west")
            hidden = ("north", "south")
            caps = {"up": top, "down": bottom}
        elif axis == "z":
            frm, to = (a - apothem, b - half_w, l0), (a + apothem, b + half_w, h1)
            origin = (a, b, (lo + hi) / 2)
            rot = {"origin": [round(v, 4) for v in origin], "x": 0, "y": 0, "z": round(ang, 4)}
            sides_ = ("east", "west")
            hidden = ("up", "down")
            caps = {"south": top, "north": bottom}
        else:
            raise ValueError(axis)
        # 有端盖时各条的端盖高低错开 CAP_STEP，台阶的竖边就是条的长侧面：留着（画成管腔里的暗色），贴着边看不会透过去
        # 内壁两头一像素和端盖的亮边同色：它们在棱上和管口那一圈露出来的那一点不会变成黑点
        capped = top is not None or bottom is not None
        rim_mat = mat if mat in ("brass", "gilt", "tin") else "tin"
        rim_tone = 3.0 if rim_mat == "tin" else 2.0
        inner = {"mat": "iron", "bevel": 0, "deco": open_end(rim_mat, rim_tone, 1.0, key="inner_" + rim_mat)}
        fs = {h: (inner if capped else None) for h in hidden}
        for f in sides_:
            if side is not None:
                fs[f] = side
        fs.update(caps)
        e = cube(frm, to, mat, s, grain=axis, faces=fs, **kw)
        if abs(ang % 360) > 1e-9:
            e["rotation"] = rot
        els.append(e)
    return els


def frustum(c, r0, r1, a0, a1, mat, s, sides=8, thick=0.35, width_r=None, outer=None, inner=None, ends=None, seed=0):
    """竖着的截锥（锥形管脚、喇叭口）：sides 块薄板，每块先绕 z 斜过去、再绕 y 转到自己的方位。
    r0 / r1 是 a0 / a1 两头的边心距；板宽按 width_r（默认大的那头）算，大头不留缝。"""
    cx, cz = c
    rm, am = (r0 + r1) / 2, (a0 + a1) / 2
    length = math.hypot(a1 - a0, r1 - r0)
    rw = max(r0, r1) if width_r is None else width_r
    w = 2 * math.tan(math.pi / sides) * rw
    tilt = -math.degrees(math.atan2(r1 - r0, a1 - a0))
    fs = {"east": outer or mat, "west": inner or mat, "up": ends or mat, "down": ends or mat, "north": mat, "south": mat}
    els = []
    for k in range(sides):
        e = cube((cx + rm - thick, am - length / 2, cz - w / 2), (cx + rm, am + length / 2, cz + w / 2), mat, s,
                 grain="y", faces=fs, seed=seed)
        e["rotation"] = kit.rot_euler(z=tilt, origin=(cx + rm, am, cz))
        els += rigid([e], euler(0, 360.0 / sides * k, 0), (cx, am, cz))
    return els


# —— 装饰 ——

def manual_top():
    """手键盘顶面（u 沿 x、v=0 在键盘前沿）：每个白键 2 像素，后六成里一组二、一组三的乌木黑键。"""
    def fn(img, w, h, rng):
        out = np.empty((h, w, 3), np.float32)
        back = h - max(2, int(round(h * 0.6)))
        for x in range(w):
            k, p = divmod(x, 2)
            note = k % 7
            sharp = p == 1 and note in (0, 1, 3, 4, 5)
            for y in range(h):
                if y >= back and sharp:
                    out[y, x] = C("ebony", 3.6 if y == back else 1.6)
                else:
                    t = 3.25 - 0.12 * y
                    if p == 0 and y < back:
                        t -= 0.55          # 白键之间的缝（很淡）
                    if y == 0:
                        t += 0.35          # 键头的亮边
                    out[y, x] = C("ivory", t)
        return out
    return custom("organ_manual_top", fn)


def key_fronts():
    """白键前脸：象牙色，每 2 像素一道缝。"""
    def fn(img, w, h, rng):
        out = np.empty((h, w, 3), np.float32)
        for x in range(w):
            out[:, x] = C("ivory", 2.9 if x % 2 else 1.7)
        return out
    return custom("organ_key_fronts", fn)


def pedal_top():
    """脚键盘顶面：枫木白键一根一像素、中间一像素胡桃木缝；后半截的缝上是凸起的乌木黑键。"""
    def fn(img, w, h, rng):
        out = np.empty((h, w, 3), np.float32)
        back = h - max(2, int(round(h * 0.55)))
        for x in range(w):
            k, p = divmod(x, 2)
            sharp = p == 1 and (k % 7) in (0, 1, 3, 4, 5)
            for y in range(h):
                if p == 0:
                    out[y, x] = C("maple", (2.4 if y == 0 else 1.7) - 0.12 * y)
                elif y >= back and sharp:
                    out[y, x] = C("ebony", 3.3 if y == back else 1.8)
                else:
                    out[y, x] = C("walnut", 1.0)
        return out
    return custom("organ_pedal_top", fn)


ROUNDEL = [".#####.", "#######", "#######", "#######", "#######", "#######", ".#####."]


def starbloom_panel():
    """正面镶板（凸起，边缘靠倒角）：正中一块浅胡桃木圆章，上面一朵象牙白星辰花（金花心）。"""
    def fn(img, w, h, rng):
        x0, y0 = (w - 7) // 2, (h - 7) // 2
        for j, row in enumerate(ROUNDEL):
            for i, ch in enumerate(row):
                if ch == "#":
                    edge = j in (0, 6) or i in (0, 6) or (j in (1, 5) and i in (1, 5))
                    if edge:
                        img[y0 + j, x0 + i] = C("gilt", 2.5 - 0.06 * (i + j))
                    else:
                        img[y0 + j, x0 + i] = C("walnut_light", 1.0)
        for j, row in enumerate(STARBLOOM):
            for i, ch in enumerate(row):
                if ch == "#":
                    img[y0 + 1 + j, x0 + 1 + i] = C("ivory", 3.3 - 0.3 * j)
        img[y0 + 3, x0 + 3] = C("gilt", 3.6)
        return img
    return custom("organ_starbloom_panel", fn)


def stringing(inset=1, tone=2.2, mat="walnut_light"):
    """镶线：比底色亮一档的浅胡桃木细线（不用枫木：一像素宽的亮线在格子尺度上太抢）。"""
    return kit.frame_line(mat, inset, tone)


def fluted_pilaster():
    """壁柱：两道凹槽、顶上一行金色柱头、底下一行浅色柱础。"""
    def fn(img, w, h, rng):
        for x in range(w):
            for y in range(h):
                if y == 0:
                    img[y, x] = C("gilt", 3.0 - 0.3 * (x == w - 1))
                elif y == 1 or y == h - 1:
                    img[y, x] = C("walnut_light", 2.6 - 0.3 * (x == w - 1))
                elif 0 < x < w - 1 and x % 2 == 1:
                    img[y, x] = C("walnut", 0.9)
        return img
    return custom("organ_pilaster", fn)


def knob_face():
    """音栓拉钮的象牙面（2×2）：左上亮，右下那一点暗一档、偏棕，是刻着名字的那一行。"""
    def fn(img, w, h, rng):
        out = np.empty((h, w, 3), np.float32)
        for y in range(h):
            for x in range(w):
                out[y, x] = C("ivory", 3.9 - 0.45 * (x + y))
        out[h - 1, w - 1] = C("ivory", 1.4) * 0.8 + C("rosewood", 3.0) * 0.2
        return out
    return custom("organ_knob_face", fn)


def collar_face():
    """拉钮根部的黄檀木圈：一圈亮边，中间（被象牙头挡住的地方）暗。"""
    def fn(img, w, h, rng):
        out = img.copy()
        out[0, :] = C("rosewood", 4.0)
        out[:, 0] = C("rosewood", 3.6)
        return out
    return custom("organ_collar_face", fn)


def nameplate():
    """键盘后面的铭牌：中间一行像字的淡金点。"""
    def fn(img, w, h, rng):
        y = h // 2
        word = "##.#.###..#.##.#"
        x0 = (w - len(word)) // 2
        for i, ch in enumerate(word):
            if ch == "#" and 0 <= x0 + i < w:
                img[y, x0 + i] = C("gilt", 3.2)
        return img
    return custom("organ_nameplate", fn)


def score_page():
    """谱架上打开的谱：两页，五线谱的细线和几个音符，中缝暗一点。"""
    def fn(img, w, h, rng):
        ink = C("rosewood", 2.6)
        for y in range(1, h - 1, 2):
            img[y, 1:w - 1] = img[y, 1:w - 1] * 0.72 + ink * 0.28
        mid = w // 2
        img[:, mid] = img[:, mid] * 0.86
        for x, y in ((2, 1), (4, 3), (mid + 2, 3), (w - 3, 1)):
            if 0 <= x < w and 0 <= y < h:
                img[y, x] = img[y, x] * 0.45 + ink * 0.55
        return img
    return custom("organ_score", fn)


def mouth_rows(spec, key):
    """在面上从下往上数第几行画什么：spec = [(row_from_bottom, mat, tone, x_margin), ...]。"""
    def fn(img, w, h, rng):
        for row, mat, tone, margin in spec:
            y = h - 1 - row
            if 0 <= y < h:
                img[y, margin:w - margin] = C(mat, tone)
        return img
    return custom(("organ_rows", key), fn)


def open_end(rim_mat="tin", rim=3.4, inner=1.1, key="tin"):
    """开口的管顶（每条多边形柱的端盖）：两头一像素亮边，中间是管腔里的暗金属。"""
    def fn(img, w, h, rng):
        out = np.empty((h, w, 3), np.float32)
        out[:] = C("iron", inner)
        out[:, 0] = C(rim_mat, rim)
        out[:, -1] = C(rim_mat, rim - 0.6)
        return out
    return custom(("organ_open_end", rim_mat, rim, inner, key), fn)


def square_open_end():
    """方木管的开口：一像素宽的管壁（端面纹），里面是暗的。"""
    def fn(img, w, h, rng):
        out = img.copy()
        out[1:h - 1, 1:w - 1] = C("shadow", 2.6)
        out[1:h - 1, 1] = C("shadow", 1.4)
        out[1, 1:w - 1] = C("shadow", 1.0)
        return out
    return custom("organ_square_open_end", fn)


def socket_top():
    """风箱顶的管脚板：四边一道暗槽（木板边）、正中一个黄铜圈的管脚孔（圆角）。"""
    def fn(img, w, h, rng):
        groove = 0.82
        img[1, 1:w - 1] *= groove
        img[h - 2, 1:w - 1] *= groove
        img[1:h - 1, 1] *= groove
        img[1:h - 1, w - 2] *= groove
        cx, cy = w // 2, h // 2
        for y in range(cy - 2, cy + 2):
            for x in range(cx - 2, cx + 2):
                edge = y in (cy - 2, cy + 1) or x in (cx - 2, cx + 1)
                corner = y in (cy - 2, cy + 1) and x in (cx - 2, cx + 1)
                if corner:
                    img[y, x] = C("brass", 1.4)
                elif edge:
                    img[y, x] = C("brass", 3.3 if y == cy - 2 or x == cx - 2 else 2.4)
                else:
                    img[y, x] = C("shadow", 1.2)
        return img
    return custom("organ_socket_top", fn)


def chest_panel():
    """风箱侧面（两根角柱之间可见）：画出来的凸起镶板（上、左边亮，下、右边暗），正中一朵浅木星辰花、金花心。"""
    def fn(img, w, h, rng):
        x0, x1, y0, y1 = 3, w - 4, 1, h - 2
        img[y0, x0:x1 + 1] = img[y0, x0:x1 + 1] * 1.3
        img[y0:y1 + 1, x0] = img[y0:y1 + 1, x0] * 1.2
        img[y1, x0:x1 + 1] = img[y1, x0:x1 + 1] * 0.68
        img[y0:y1 + 1, x1] = img[y0:y1 + 1, x1] * 0.72
        sx, sy = (w - 5) // 2, (h - 5) // 2
        for j, row in enumerate(STARBLOOM):
            for i, ch in enumerate(row):
                if ch == "#":
                    img[sy + j, sx + i] = C("walnut_light", 3.6 - 0.25 * j)
        img[sy + 2, sx + 2] = C("gilt", 3.4)
        return img
    return custom("organ_chest_panel", fn)


def mitre_mouth():
    """锡管管口（3 宽 × 4 高）：淡金描边的尖拱上唇、一道暗缝、淡金下唇。"""
    rows = ["tGt", "GTG", "ddd", "GGG"]

    def fn(img, w, h, rng):
        out = img.copy()
        for j, row in enumerate(rows[:h]):
            for i, ch in enumerate(row[:w]):
                if ch == "G":
                    out[j, i] = C("gilt", 3.3 - 0.3 * (i == w - 1))
                elif ch == "T":
                    out[j, i] = C("tin", 3.6)
                elif ch == "d":
                    out[j, i] = C("iron", 0.6)
        return out
    return custom("organ_mitre_mouth", fn)


# —— 演奏台 ——

WIDE = dict(x0=-16, x1=32, cx=8, man=(-6, 22), keys=(-5.5, 21.5), pedal=(-10, 26),
            panels=(-8, 8, 24), pilasters=(0, 16), jambs=((-16, -6), (22, 32)), crown=13)
NARROW = dict(x0=0, x1=32, cx=16, man=(2, 30), keys=(2.5, 29.5), pedal=(1, 31),
              panels=(8, 24), pilasters=(16,), jambs=None, crown=9)

# 高度（像素）
PLINTH, CASE_TOP, WAIST = 1.4, 12.0, 13.0
LOW_BED, LOW_KEYS = (13.0, 14.0), (14.0, KEY_TOP)
UP_BED, UP_KEYS = (15.0, 16.0), (16.0, 16.8)
NAME_TOP = 17.6
CORNICE = (24.5, 25.5)
BACK_Z = 13.2       # 键盘后面板（谱架后面）的前脸
CASE_Z = 5.2        # 下层琴身前脸


def console_elements(s, L):
    el = []
    x0, x1, cx = L["x0"], L["x1"], L["cx"]
    m0, m1 = L["man"]
    k0, k1 = L["keys"]

    # 脚键盘：胡桃木底框，顶面画枫木白键与凸起的乌木黑键
    p0, p1 = L["pedal"]
    el.append(box((p0, 0, 0), (p1, 1.2, 5.0), "walnut", s, grain="x",
                  faces={"up": {"mat": "walnut", "deco": pedal_top(), "bevel": 0}, "south": None, "down": None}))

    # 下层琴身：踢脚线、柜体、三块星辰花镶板、凹槽壁柱
    el.append(box((x0, 0, 4.6), (x1, PLINTH, 16), "walnut", s, grain="x", tone=1.5, faces={"down": None}))
    el.append(box((x0, PLINTH, CASE_Z), (x1, CASE_TOP, 16), "walnut", s, grain="x",
                  faces={"up": None, "down": None, "south": {"mat": "walnut", "deco": stringing()},
                         "west": {"mat": "walnut", "deco": stringing()}, "east": {"mat": "walnut", "deco": stringing()}}))
    for c in L["panels"]:
        el.append(box((c - 5.5, 2.4, CASE_Z - 0.5), (c + 5.5, 11.4, CASE_Z), "walnut", s, grain="x", seed=3,
                      faces={"south": None, "north": {"mat": "walnut", "deco": starbloom_panel(), "seed": 3}}))
    for c in L["pilasters"]:
        el.append(box((c - 1.5, PLINTH, CASE_Z - 0.4), (c + 1.5, CASE_TOP, CASE_Z), "walnut", s, grain="y",
                      faces={"south": None, "down": None, "north": {"mat": "walnut_light", "deco": fluted_pilaster()}}))
    # 腰线：一条淡金
    el.append(box((x0, CASE_TOP, 3.4), (x1, WAIST, 16), "walnut", s, grain="x",
                  faces={"north": "gilt", "down": None}))

    # 下层、上层手键盘（键床 + 琴键）。键盘前的木条上一排象牙小按钮
    el.append(box((m0, LOW_BED[0], 3.0), (m1, LOW_BED[1], BACK_Z), "walnut", s, grain="x",
                  faces={"down": None, "north": {"mat": "walnut_light", "tone": 1.6}}))
    el.append(box((k0, LOW_KEYS[0], 3.2), (k1, LOW_KEYS[1], 8.4), "ivory", s, grain="x",
                  faces={"down": None, "south": None, "up": {"mat": "ivory", "deco": manual_top(), "bevel": 0},
                         "north": {"mat": "ivory", "deco": key_fronts(), "bevel": 0}}))
    el.append(box((m0, UP_BED[0], 6.6), (m1, UP_BED[1], BACK_Z), "walnut", s, grain="x",
                  faces={"north": {"mat": "walnut_light", "tone": 1.6}}))
    el.append(box((k0, UP_KEYS[0], 6.8), (k1, UP_KEYS[1], 10.6), "ivory", s, grain="x",
                  faces={"down": None, "south": None, "up": {"mat": "ivory", "deco": manual_top(), "bevel": 0},
                         "north": {"mat": "ivory", "deco": key_fronts(), "bevel": 0}}))
    # 铭牌条
    el.append(box((m0, UP_BED[1], 10.6), (m1, NAME_TOP, BACK_Z), "walnut", s, grain="x",
                  faces={"down": None, "north": {"mat": "walnut", "deco": nameplate()}}))

    # 键盘后的面板（窄款音栓也在上面）
    back_x = (m0, m1) if L["jambs"] else (x0, x1)
    el.append(box((back_x[0], WAIST, BACK_Z), (back_x[1], CORNICE[0], 16), "walnut", s, grain="x",
                  faces={"up": None, "down": None, "south": {"mat": "walnut", "deco": stringing()},
                         "north": {"mat": "walnut", "deco": stringing(1, 2.3)}}))

    # 谱架：向后仰的胡桃木板、托条、打开的谱
    d0, d1 = cx - 6.5, cx + 6.5
    tilt = kit.rot_euler(x=14, origin=(cx, NAME_TOP, 11.0))
    el.append(box((d0, NAME_TOP, 11.0), (d1, NAME_TOP + 6.2, 11.6), "walnut", s, grain="x", rot=tilt,
                  faces={"north": {"mat": "walnut", "deco": stringing(0, 2.6)}}))
    el.append(box((d0 + 1, NAME_TOP + 0.5, 10.82), (d1 - 1, NAME_TOP + 5.6, 10.95), "parchment", s, grain="x", rot=tilt,
                  faces={"south": None, "north": {"mat": "parchment", "deco": score_page()}}))
    el.append(box((d0 - 0.2, NAME_TOP, 10.2), (d1 + 0.2, NAME_TOP + 0.7, 11.0), "walnut_light", s, grain="x",
                  faces={"south": None}))

    # 音栓
    if L["jambs"]:
        for j0, j1 in L["jambs"]:
            inner = "east" if j0 < cx else "west"
            el.append(box((j0, WAIST, 3.6), (j1, CORNICE[0], 16), "walnut", s, grain="y",
                          faces={"up": None, "down": None, "north": {"mat": "walnut", "grain": "y", "seed": 5},
                                 "south": {"mat": "walnut", "deco": stringing()},
                                 "west": {"mat": "walnut", "deco": stringing()}, "east": {"mat": "walnut", "deco": stringing()}}))
            # 帽檐：外侧齐边，里侧和正面挑出一点，正面、里侧一条淡金
            c0, c1 = (j0, j1 + 0.4) if j0 < cx else (j0 - 0.4, j1)
            el.append(box((c0, CORNICE[0], 3.2), (c1, CORNICE[1], 16), "walnut", s, grain="z",
                          faces={"north": "gilt", inner: "gilt", "down": None}))
            # 2 列 × 4 行拉钮
            for i in range(2):
                for j in range(4):
                    x = (j0 + j1) / 2 + (i - 0.5) * 3.6
                    y = WAIST + 2.0 + j * 2.65
                    el += knob(s, x, y, 3.6)
        cor = (m0 + 0.4, m1 - 0.4)
    else:
        # 窄款：两侧扶手；拉钮在谱架两边的面板上，各 2 列 × 2 行
        for a0, a1 in ((x0, m0), (m1, x1)):
            el.append(box((a0, WAIST, 3.5), (a1, 19.0, BACK_Z), "walnut", s, grain="z",
                          faces={"down": None, "up": "walnut_light"}))
        for side in (-1, 1):
            for i in range(2):
                for j in range(2):
                    x = cx + side * (11.4 - i * 3.0)
                    y = 19.3 + j * 3.0
                    el += knob(s, x, y, BACK_Z)
        cor = (x0, x1)
    # 檐口：一条淡金
    el.append(box((cor[0], CORNICE[0], 12.8), (cor[1], CORNICE[1], 16), "walnut", s, grain="x",
                  faces={"north": "gilt", "down": None}))

    # 顶上一排装饰锡管，中间最高
    n = L["crown"]
    half = (n - 1) / 2
    for k in range(n):
        x = cx + (k - half) * 3.0
        h = 2.6 + 2.85 * (1 - (abs(k - half) / half) ** 1.4)
        foot_top = CORNICE[1] + 1.0
        top = foot_top + h
        el.append(box((x - 0.5, CORNICE[1], 13.9), (x + 0.5, foot_top, 14.9), "tin", s, grain="y",
                      faces={"down": None, "up": None}))
        mouth = mouth_rows([(1, "tin", 0.5, 0), (2, "gilt", 2.8, 0)], "crown")
        el.append(box((x - 1, foot_top, 13.4), (x + 1, top, 15.4), "organ_tin", s, grain="y", seed=k,
                      faces={"down": None, "up": {"mat": "tin", "tone": 1.2, "bevel": 0},
                             "north": {"mat": "organ_tin", "deco": mouth, "bevel": 0, "seed": k}}))
    return el


def knob(s, x, y, face_z):
    """一个音栓拉钮（两个元素）：贴着面板的黄檀木圈（2.6 见方、0.4 厚），上面伸出 1 像素的象牙头（1.8 见方）。
    x、y 是中心，face_z 是它插进去的那个面。"""
    return [
        box((x - 1.3, y - 1.3, face_z - 0.4), (x + 1.3, y + 1.3, face_z), "rosewood", s, grain="x",
            faces={"south": None, "north": {"mat": "rosewood", "deco": collar_face(), "bevel": 0}}),
        box((x - 0.9, y - 0.9, face_z - 1.4), (x + 0.9, y + 0.9, face_z - 0.4), "ivory", s, grain="z",
            faces={"south": None, "north": {"mat": "ivory", "deco": knob_face(), "bevel": 0}}),
    ]


# —— 风箱 ——

def wind_chest_elements(s):
    """一格的胡桃木柜：踢脚、四根角柱、凹进去的镶板、一道淡金收边、顶上开着管脚孔的管脚板。
    外轮廓是整格（角柱、踢脚、檐口、顶板都到格子边），凹进去的地方四面都被自己围住，挨着别的方块不会透视。"""
    cull = {"north": "north", "south": "south", "west": "west", "east": "east", "up": "up", "down": "down"}

    def faces_cull(fs, frm, to):
        out = {}
        for f, spec in fs.items():
            if spec is None:
                out[f] = None
                continue
            spec = {"mat": spec} if isinstance(spec, str) else dict(spec)
            ax, sgn = {"north": (2, -1), "south": (2, 1), "west": (0, -1), "east": (0, 1), "down": (1, -1), "up": (1, 1)}[f]
            v = to[ax] if sgn > 0 else frm[ax]
            if v in (0, 16):
                spec["cullface"] = cull[f]
            out[f] = spec
        return out

    def B(frm, to, mat, fs=None, **kw):
        fs = dict(fs or {})
        for f in kit.FACE_AXES:
            fs.setdefault(f, mat)
        return box(frm, to, mat, s, faces=faces_cull(fs, frm, to), **kw)

    # 踢脚的顶面要留着：镶板凹进去 0.5，这一圈顶面就是凹槽的底，删了会从缝里看穿方块
    el = [B((0, 0, 0), (16, 1.5, 16), "walnut", grain="x", tone=1.5)]
    for x, z in ((0, 0), (14.4, 0), (0, 14.4), (14.4, 14.4)):
        el.append(B((x, 1.5, z), (x + 1.6, 13, z + 1.6), "walnut_light", grain="y", fs={"up": None, "down": None}))
    ns = {"mat": "walnut", "deco": chest_panel(), "grain": "x"}
    we = {"mat": "walnut", "deco": chest_panel(), "grain": "z"}
    el.append(B((0.5, 1.5, 0.5), (15.5, 13, 15.5), "walnut", grain="x",
                fs={"up": None, "down": None, "north": ns, "south": ns, "west": we, "east": we}))
    el.append(B((0, 13, 0), (16, 14, 16), "gilt", grain="x", fs={"up": None, "down": "walnut"}))
    el.append(B((0, 14, 0), (16, 16, 16), "walnut", grain="x",
                fs={"down": None, "up": {"mat": "walnut_light", "deco": socket_top(), "grain": "x"}}))
    return el


# —— 音管 ——

TIN_R = 3.5      # 锡管管身边心距（碰撞箱半宽 3.5）
TIN_FOOT = 4.5   # 锥形管脚的顶（管口在它上面）
TIN_TOE = 2.0    # 管脚尖的边心距
TIN_TOP_RIM = 15.0  # 开口那一圈管口的下沿
WOOD = (4, 12)   # 木管管身（碰撞箱半宽 4）
WOOD_BODY0 = 3.0  # 木管管身下端（下面是管脚）


def tin_pipe_elements(s, shape):
    foot = shape in ("single", "bottom")
    top = shape in ("single", "top")
    lo = TIN_FOOT if foot else 0.0
    body_side = {"mat": "organ_tin", "bevel": 0}
    # 开口的那一节：管身到 15 为止（齐平、没有内壁），上面单独一圈 1 像素的管口，端盖错开、带内壁，贴着边看不透
    hi = TIN_TOP_RIM if top else 16
    el = prism((8, 8), TIN_R, lo, hi, "organ_tin", s, side=body_side)
    if top:
        el += prism((8, 8), TIN_R, TIN_TOP_RIM, 16, "organ_tin", s, side=body_side,
                    top={"mat": "tin", "deco": open_end(), "bevel": 0})
    if foot:
        el += frustum((8, 8), TIN_TOE, TIN_R, 0, TIN_FOOT, "tin", s, thick=0.4,
                      outer={"mat": "organ_tin", "bevel": 0, "grain": "y"}, inner={"mat": "shadow"},
                      ends={"mat": "tin", "bevel": 0})
        # 管口：压平的一片贴在正面那一棱上（同宽），淡金描边的尖拱上唇、暗缝、淡金下唇
        hw = TIN_R * math.tan(math.pi / 8)
        el.append(box((8 - hw, 3.8, 8 - TIN_R - 0.2), (8 + hw, 7.8, 8 - TIN_R - 0.05), "tin", s, grain="y",
                      faces={"south": None, "north": {"mat": "organ_tin", "deco": mitre_mouth(), "bevel": 0}}))
    return el


def wood_pipe_elements(s, shape):
    foot = shape in ("single", "bottom")
    top = shape in ("single", "top")
    a, b = WOOD
    lo = WOOD_BODY0 if foot else 0.0
    side = {"mat": "organ_pipe_walnut", "bevel": 0, "grain": "y"}
    front = dict(side, tone=2.35, seed=1)   # 正面的管板（开管口的那块）浅一档
    if foot:
        front["deco"] = mouth_rows([(3, "shadow", 0.6, 1), (4, "walnut_light", 3.5, 1), (5, "walnut_light", 1.4, 1)], "wood_mouth")
    fs = {"north": front, "south": side, "east": side, "west": side,
          "down": None, "up": {"mat": "walnut", "deco": square_open_end(), "grain": "n", "cullface": "up"} if top else None}
    el = [cube((a, lo, a), (b, 16, b), "organ_pipe_walnut", s, grain="y", faces=fs)]
    if foot:
        el.append(box((6.7, 0, 6.7), (9.3, WOOD_BODY0, 9.3), "walnut_light", s, grain="y",
                      faces={"up": None, "down": {"mat": "walnut_light", "cullface": "down"}}))
        el.append(box((a, WOOD_BODY0, a - 0.6), (b, WOOD_BODY0 + 3.0, a), "walnut_light", s, grain="x",
                      faces={"south": None, "down": None}))
    return el


TRUMPET_Y = 9.5     # 横管中心高
TRUMPET_BACK = 10.4  # 横管尾端（z）
TRUMPET_THROAT = 2.6  # 锥管接喇叭口处（z）


def trumpet_elements(s):
    """号角管（水平号角，朝北）：胡桃木短座和淡金线脚、黄铜靴（簧片在里面）一直升到横管下面，
    细长的黄铜管向北伸出，接口一圈淡金箍，最后张开成喇叭口；口里是暗的。"""
    el = []
    el.append(box((5.5, 0, 5.5), (10.5, 1.4, 10.5), "walnut", s, grain="x", faces={"down": {"mat": "walnut", "cullface": "down"}}))
    el.append(box((6.0, 1.4, 6.0), (10.0, 1.9, 10.0), "gilt", s, grain="x", faces={"down": None, "up": "walnut_light"}))
    cz = 8.6
    el += prism((8, cz), 1.15, 1.9, TRUMPET_Y - 0.2, "brass", s)
    y = TRUMPET_Y
    # 锥形管：沿 +y 造好，再绕 x 转 −90° 让 +y 朝北（−z）
    # 横管：细长的八角管（这么细的锥管用斜板拼会露棱，直管更干净），尾端一个淡金盖
    el += prism((8, y), 1.2, TRUMPET_THROAT + 0.3, TRUMPET_BACK, "brass", s, axis="z", start=22.5,
                top={"mat": "gilt", "tone": 2.8})
    bell = frustum((8, TRUMPET_THROAT), 1.3, 3.2, y, y + 2.3, "brass", s, thick=0.35,
                   outer={"mat": "brass"}, inner={"mat": "brass", "tone": 0.8}, ends={"mat": "gilt", "tone": 3.2})
    el += rigid(bell, euler(-90, 0, 0), (8, y, TRUMPET_THROAT))
    # 喇叭口和锥管接口处一圈淡金箍，盖住两组板在细头交叠出来的棱
    el += prism((8, y), 1.95, TRUMPET_THROAT - 0.75, TRUMPET_THROAT + 0.35, "gilt", s, axis="z",
                top={"mat": "gilt", "tone": 2.6}, start=22.5)
    el.append(box((6.4, y - 1.6, 1.55), (9.6, y + 1.6, 1.65), "shadow", s, grain="x",
                  faces={"south": None, "up": None, "down": None, "east": None, "west": None}))
    return el


# —— 生成 ——

def build(out):
    cs = out.sheet("hexen_organ_console")
    wide = kit.prune_hidden(console_elements(cs, WIDE))
    narrow = kit.prune_hidden(console_elements(cs, NARROW))
    out.block_model("hexen_organ_console_wide", wide, [cs], "large")
    out.block_model("hexen_organ_console", narrow, [cs], "large")
    out.particle_only("hexen_organ_console_part", cs.texture)
    out.item_parent("hexen_organ_console", "hexen_organ_console_wide", kit.display(wide, gui_rot=(25, 195, 0)))

    ps = out.sheet("organ_pipes")
    chest = kit.prune_hidden(wind_chest_elements(ps))
    out.block_model("organ_wind_chest", chest, [ps], "block", ao=True)
    out.item_parent("organ_wind_chest", "organ_wind_chest", kit.display(chest))

    for kind, fn in (("tin", tin_pipe_elements), ("wood", wood_pipe_elements)):
        for shape in ("single", "bottom", "middle", "top"):
            el = fn(ps, shape)
            out.block_model(f"organ_{kind}_pipe_{shape}", el, [ps], "pipe")
            if shape == "single":
                out.item_parent(f"organ_{kind}_pipe", f"organ_{kind}_pipe_single", kit.display(el, gui_rot=(15, 195, 0)))

    tr = trumpet_elements(ps)
    out.block_model("organ_trumpet_pipe", tr, [ps], "pipe")
    out.item_parent("organ_trumpet_pipe", "organ_trumpet_pipe", kit.display(tr, gui_rot=(30, 135, 0)))
