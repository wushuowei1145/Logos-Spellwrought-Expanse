"""乐器美术工具箱：逐面手绘贴图页、统一调色板、物品显示变换、防闪烁与预算检查。

各组乐器的生成脚本在 tools/instrument_models/ 下，都用这里的工具；约定见 docs/INSTRUMENT_ART.md。

核心做法
- 每个乐器家族一张自己的贴图页 textures/block/instrument/<页名>.png（2 的幂、全不透明）。
- 每个元素的每个面按「1 个模型单位 = 1 像素」（原版 16 像素/格的密度）在页上分到自己的一块，
  按材质（胡桃木、白漆、淡金……）和面的朝向现画：木纹顺着元素的长边走，金属有高光带，边缘有一像素的倒角明暗。
- 同样的面（材质、尺寸、纹理方向、装饰都一样）共用一块，变体（手持 / 演奏 / 谱架上）引用同一份。
- 元素用 cube() 造，面上挂的是 FaceRef；写文件时（Out.flush）先排版、画好贴图页，再把 FaceRef 换成 uv。

坐标沿用原版模型：单位是模型像素，方块 0–16，元素只能在 −16–32 之内；欧拉角转动 {"x","y","z"}（先 x、再 y、再 z）。
"""
from __future__ import annotations

import copy
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources"
ASSETS = RES / "assets/logos_dimension"
DATA = RES / "data/logos_dimension"
TEX_DIR = ASSETS / "textures/block/instrument"
NS = "logos_dimension"

# 预算（元素个数）。方块模型烘进区块网格，只在区块重建时花时间；物品模型每帧都要画（乐团里每个 NPC 手上一件），所以更紧。
BUDGET = {
    "large": 220,     # 两格以上的大乐器：钢琴、管风琴演奏台、竖琴、大键琴、定音鼓、管钟、指挥台
    "block": 110,     # 一格的乐器和家具：钢片琴、钟琴、风箱、乐谱架、琴凳
    "pipe": 28,       # 管风琴音管（音乐厅里一面墙几十根）
    "held": 70,       # 手持乐器的物品模型（小提琴、长笛……演奏中的 NPC 每人一件）
    "tool": 12,       # 琴弓、琴槌、指挥棒
}
MIN_GAP = 0.05        # 同向、平行、有重叠的两个面之间至少要隔开的距离（模型像素），否则远处会闪


# —— 调色板：每种材质五个色阶，暗 → 亮 ——

def _hex(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def ramp(*colors):
    return np.array([_hex(c) for c in colors], dtype=np.float32)


PALETTE = {
    # 木
    "walnut": ramp("#24170f", "#342116", "#452c1d", "#573826", "#6c4733"),      # 深色胡桃木：琴身、琴腿、柜体
    "walnut_light": ramp("#4a2f1e", "#5f3d27", "#744c31", "#8a5c3c", "#a0704b"),  # 浅一档的胡桃木：雕花凸起、扶手面
    "spruce": ramp("#8e6a3d", "#ab8450", "#c49c63", "#d8b479", "#e9cc95"),      # 云杉音板、提琴面板
    "maple": ramp("#b58f62", "#c9a679", "#d9b98d", "#e6caa1", "#f1dcb8"),       # 白枫木：琴马、键盘框、谱架
    "varnish": ramp("#3d170a", "#5c2410", "#7d3416", "#9b471f", "#b9612d"),     # 提琴清漆（橘红褐）
    "amber": ramp("#30120a", "#4a1c0e", "#632814", "#7c361b", "#954825"),       # 深一档的清漆：中提琴、巴松管
    "rosewood": ramp("#1f0f0b", "#2e1610", "#3f1f16", "#52291d", "#673526"),    # 指板以外的深色硬木：弦轴、尾柱帽
    "ebony": ramp("#141110", "#1e1916", "#2a2320", "#38302b", "#4a3f39"),       # 乌木：黑键、指板、双簧管管身
    # 漆与石
    "marble": ramp("#aca598", "#c7c0b3", "#dcd6cb", "#e9e5dc", "#f6f3ec"),      # 白色大理石纹漆（星辰花钢琴）
    "ivory": ramp("#c9bfa7", "#ddd4bf", "#ebe4d3", "#f4efe3", "#fdfaf2"),       # 白键、象牙镶条
    "lacquer_black": ramp("#131417", "#1c1d22", "#26282e", "#33363e", "#474b55"),  # 黑漆
    # 金属
    "gilt": ramp("#6a4e1e", "#94742f", "#c29a45", "#e0bf6c", "#f5e1a0"),        # 淡金收边、雕饰、铰链
    "brass": ramp("#5a3f12", "#8a651f", "#b98c32", "#d8b052", "#f0d784"),       # 黄铜：小号、管钟、踏板
    "silver": ramp("#5a6068", "#878e97", "#b1b8c0", "#d1d6dc", "#eff2f5"),      # 银：长笛、按键
    "tin": ramp("#696d73", "#93979d", "#b4b8bd", "#cccfd3", "#e3e5e8"),         # 锡铅合金：管风琴金属管
    "copper": ramp("#55260f", "#7d3c1c", "#a35429", "#c26f3b", "#de935e"),      # 定音鼓铜腔
    "iron": ramp("#18191c", "#26282c", "#35383d", "#474b51", "#5e636a"),        # 黑铁：支架、尾柱、谱架杆
    "steel": ramp("#7c8188", "#9ea3a9", "#bcc0c5", "#d5d8dc", "#eceef0"),       # 琴弦
    # 织物、皮、纸
    "velvet": ramp("#320b12", "#4c121d", "#661927", "#802332", "#9a3341"),      # 酒红丝绒：琴凳、指挥台垫
    "velvet_navy": ramp("#0d1329", "#151f40", "#1f2d58", "#2a3b70", "#384d8b"),  # 魔女之森深蓝：旗、谱架衬
    "felt": ramp("#9d927c", "#bcb19a", "#d1c7b2", "#e1d9c7", "#efe9dc"),        # 琴槌毡头
    "felt_red": ramp("#3e0d10", "#581418", "#721c21", "#8c262b", "#a63438"),    # 键盘前的红毡条、制音毡
    "skin": ramp("#b19d78", "#c8b58f", "#d9c9a5", "#e7dbbb", "#f3ead2"),        # 定音鼓皮
    "parchment": ramp("#c2ad80", "#d4c296", "#e2d3ad", "#ece1c2", "#f6eed9"),   # 谱页、纸带
    "shadow": ramp("#060505", "#0b0908", "#110e0c", "#181411", "#201b17"),      # 腔体内部、音孔、管口
}


# —— 随机数（确定性：同样的种子画出同样的贴图）——

def _rng(*key):
    h = hashlib.sha1(repr(key).encode()).digest()
    return np.random.default_rng(int.from_bytes(h[:8], "little"))


def _tone(r, t):
    """t 是 0–4 之间的连续色阶，线性插值。"""
    t = np.clip(t, 0, 4)
    i = np.floor(t).astype(int)
    j = np.minimum(i + 1, 4)
    f = (t - i)[..., None]
    return r[i] * (1 - f) + r[j] * f


# —— 材质画法 ——
# 每个画法收 (w, h, along, rng)：along 是纹理（木纹、拉丝）在贴图里的方向，"u" 横、"v" 竖、"n" 是端面（看到的是截面）。
# 返回 (h, w) 的「色阶」数组（0–4 连续值），最后统一按材质的五色阶上色；这样倒角、装饰也都能在色阶上加减。

def _streaks(w, h, along, rng, n_lines, depth, base):
    """顺着 along 方向的条纹：每一行（或列）一个随机偏移，再加几条深色的纹线。"""
    if along == "u":
        cross, length = h, w
    else:
        cross, length = w, h
    rows = base + rng.normal(0, 0.28, cross)
    rows = np.convolve(rows, [0.25, 0.5, 0.25], mode="same") if cross > 2 else rows
    t = np.repeat(rows[:, None], length, axis=1)
    # 纹线沿长度方向有一点起伏（在相邻两行之间游走）
    for _ in range(max(0, int(n_lines * cross / 6 + rng.random()))):
        c = rng.integers(0, cross)
        start = rng.integers(0, max(1, length))
        run = rng.integers(max(2, length // 3), max(3, length + 1))
        for k in range(start, min(length, start + run)):
            if rng.random() < 0.18:
                c = int(np.clip(c + rng.choice((-1, 1)), 0, cross - 1))
            t[c, k] -= depth
    t += rng.normal(0, 0.12, t.shape)
    return t if along == "u" else t.T


def wood(w, h, along, rng, base=2.0):
    if along == "n":  # 端面：年轮一样的同心深浅
        yy, xx = np.mgrid[0:h, 0:w]
        r = np.hypot(xx - w * 0.3, yy - h * 1.2)
        return base - 0.35 + 0.35 * np.sin(r * 1.3) + rng.normal(0, 0.12, (h, w))
    return _streaks(w, h, along, rng, 1.1, 0.75, base)


def fine_wood(w, h, along, rng, base=2.2):
    """细直纹（云杉、枫木）：纹线浅而密。"""
    if along == "n":
        return wood(w, h, along, rng, base)
    return _streaks(w, h, along, rng, 2.0, 0.45, base)


def flamed(w, h, along, rng, base=2.2):
    """虎纹枫木（提琴背板、侧板）：横过木纹的明暗波纹。"""
    t = fine_wood(w, h, along, rng, base)
    yy, xx = np.mgrid[0:h, 0:w]
    k = xx if along == "u" else yy   # 沿木纹
    c = yy if along == "u" else xx   # 横过木纹
    # 虎纹是横过木纹的一道道明暗，略斜、略弯
    t += 0.55 * np.sin(k * 1.25 + c * 0.45 + np.sin(c * 0.8 + rng.random() * 6) * 0.7)
    return t


def lacquer(w, h, along, rng, base=3.0):
    """漆面：几乎平，细微颗粒和一条斜的柔光。"""
    yy, xx = np.mgrid[0:h, 0:w]
    t = np.full((h, w), base, np.float32) + rng.normal(0, 0.06, (h, w))
    phase = rng.random() * 20
    band = np.exp(-(((xx + yy * 0.6 + phase) % 22) - 11) ** 2 / 6.0)
    return t + 0.45 * band


def marble(w, h, along, rng, base=3.1):
    """白色大理石纹漆：漆面上几条很淡的灰纹，斜着游走。"""
    t = lacquer(w, h, along, rng, base)
    for _ in range(max(1, int(w * h / 90))):
        x, y = rng.random() * w, rng.random() * h
        dx, dy = rng.choice((-1, 1)) * (0.6 + rng.random() * 0.6), 0.5 + rng.random() * 0.5
        for _ in range(int(6 + rng.random() * 14)):
            xi, yi = int(x) % w, int(y) % h
            t[yi, xi] -= 0.55 + rng.random() * 0.4
            x += dx + rng.normal(0, 0.4)
            y += dy + rng.normal(0, 0.4)
    return t


def metal(w, h, along, rng, base=2.4, hot=True):
    """金属：顺着长边的高光带（看起来是圆的），两侧暗；端面是一圈亮边。"""
    if along == "n":
        yy, xx = np.mgrid[0:h, 0:w]
        d = np.minimum.reduce([xx, yy, w - 1 - xx, h - 1 - yy])
        return base + np.where(d == 0, 0.9, -0.3) + rng.normal(0, 0.08, (h, w))
    cross = h if along == "u" else w
    length = w if along == "u" else h
    c = (np.arange(cross) + 0.5) / cross
    prof = base + 1.3 * np.exp(-((c - 0.32) ** 2) / 0.02) - 1.0 * c ** 2 if hot else base + 0.0 * c
    t = np.repeat(prof[:, None], length, axis=1) + rng.normal(0, 0.07, (cross, length))
    if hot:  # 偶尔一颗亮点
        n = int(cross * length / 40)
        for _ in range(n):
            t[rng.integers(0, cross), rng.integers(0, length)] += 0.8
    return t if along == "u" else t.T


def gilt(w, h, along, rng, base=2.4):
    return metal(w, h, along, rng, base)


def fabric(w, h, along, rng, base=2.2):
    """丝绒、毡：细碎的织纹，中间略亮（鼓起来的垫子）。"""
    yy, xx = np.mgrid[0:h, 0:w]
    t = base + 0.18 * ((xx + yy) % 2) + rng.normal(0, 0.14, (h, w))
    cx, cy = (w - 1) / 2, (h - 1) / 2
    d = np.hypot((xx - cx) / max(1, w / 2), (yy - cy) / max(1, h / 2))
    return t + 0.5 * np.clip(1 - d, 0, 1)


def skin(w, h, along, rng, base=2.6):
    yy, xx = np.mgrid[0:h, 0:w]
    t = base + rng.normal(0, 0.1, (h, w))
    cx, cy = (w - 1) / 2, (h - 1) / 2
    d = np.hypot((xx - cx) / max(1, w / 2), (yy - cy) / max(1, h / 2))
    return t + 0.6 * np.clip(1 - d, 0, 1) - 0.4 * (d > 0.85)


def flat(w, h, along, rng, base=2.0):
    return np.full((h, w), base, np.float32) + rng.normal(0, 0.05, (h, w))


# 材质名 → (调色板, 画法, 默认色阶)。各组脚本可以往里加自己的（不要改已有的）。
MATERIALS = {
    "walnut": ("walnut", wood, 2.0),
    "walnut_light": ("walnut_light", wood, 2.0),
    "spruce": ("spruce", fine_wood, 2.2),
    "maple": ("maple", fine_wood, 2.2),
    "flamed_varnish": ("varnish", flamed, 2.2),
    "varnish": ("varnish", fine_wood, 2.2),
    "flamed_amber": ("amber", flamed, 2.2),
    "amber": ("amber", fine_wood, 2.2),
    "rosewood": ("rosewood", wood, 2.2),
    "ebony": ("ebony", lacquer, 2.0),
    "marble": ("marble", marble, 3.0),
    "ivory": ("ivory", lacquer, 2.8),
    "lacquer_black": ("lacquer_black", lacquer, 1.8),
    "gilt": ("gilt", gilt, 2.3),
    "brass": ("brass", metal, 2.3),
    "silver": ("silver", metal, 2.4),
    "tin": ("tin", metal, 2.3),
    "copper": ("copper", metal, 2.2),
    "iron": ("iron", metal, 1.8),
    "steel": ("steel", metal, 2.6),
    "velvet": ("velvet", fabric, 2.0),
    "velvet_navy": ("velvet_navy", fabric, 2.0),
    "felt": ("felt", fabric, 2.4),
    "felt_red": ("felt_red", fabric, 2.0),
    "skin": ("skin", skin, 2.4),
    "parchment": ("parchment", flat, 2.8),
    "shadow": ("shadow", flat, 1.5),
}

# 倒角：不同材质边缘亮暗的力度（金属强、织物弱）
BEVEL = {"gilt": 1.1, "brass": 1.1, "silver": 1.0, "tin": 0.8, "copper": 0.9, "steel": 0.6, "iron": 0.8,
         "marble": 0.7, "ivory": 0.6, "lacquer_black": 0.9, "ebony": 0.7, "velvet": 0.5, "velvet_navy": 0.5,
         "felt": 0.3, "felt_red": 0.4, "skin": 0.3, "parchment": 0.3, "shadow": 0.0}
DEFAULT_BEVEL = 0.65


# —— 面与贴图页 ——

FACE_AXES = {
    # 面 → (u 方向对应的模型轴与正负, v 方向对应的模型轴与正负, 法线轴)。v 向下。与原版自动 uv 一致。
    "north": (("x", -1), ("y", -1), "z"),
    "south": (("x", 1), ("y", -1), "z"),
    "east": (("z", -1), ("y", -1), "x"),
    "west": (("z", 1), ("y", -1), "x"),
    "up": (("x", 1), ("z", 1), "y"),
    "down": (("x", 1), ("z", -1), "y"),
}
AXIS = {"x": 0, "y": 1, "z": 2}


class FaceRef:
    """面在贴图页上的一块；写文件时换成 uv。深拷贝时保持同一个对象（变体共用）。"""

    def __init__(self, sheet, slot, flip_u=False, flip_v=False):
        self.sheet, self.slot, self.flip_u, self.flip_v = sheet, slot, flip_u, flip_v

    def __deepcopy__(self, memo):
        return self

    def uv(self):
        x, y, w, h = self.sheet.place(self.slot)
        sx, sy = 16.0 / self.sheet.width, 16.0 / self.sheet.height
        u0, v0, u1, v1 = x * sx, y * sy, (x + w) * sx, (y + h) * sy
        if self.flip_u:
            u0, u1 = u1, u0
        if self.flip_v:
            v0, v1 = v1, v0
        return [round(u0, 5), round(v0, 5), round(u1, 5), round(v1, 5)]


class Sheet:
    """一张贴图页。add() 登记一块要画的面，pack()/save() 排版并画出来。"""

    def __init__(self, name, max_size=256):
        self.name = name
        self.max_size = max_size
        self.slots = []          # (w, h, painter)
        self.index = {}          # key → slot
        self.pos = None
        self.width = self.height = None
        self.mean = {}           # slot → 画出来的平均颜色（选破坏粒子的材质用）

    @property
    def texture(self):
        return f"{NS}:block/instrument/{self.name}"

    def add(self, key, w, h, painter):
        if key in self.index:
            return self.index[key]
        self.slots.append((w, h, painter))
        self.index[key] = len(self.slots) - 1
        self.pos = None
        return self.index[key]

    def place(self, slot):
        if self.pos is None:
            self.pack()
        return self.pos[slot]

    def pack(self):
        """货架排版：按高度从大到小一排一排放，每块四周留 1 像素的延展边（远处 mipmap 不串色）。"""
        order = sorted(range(len(self.slots)), key=lambda i: (-self.slots[i][1], -self.slots[i][0]))
        area = sum((w + 2) * (h + 2) for w, h, _ in self.slots)
        size = 16
        while size * size < area * 1.15:
            size *= 2
        while True:
            pos, x, y, shelf = {}, 0, 0, 0
            ok = True
            for i in order:
                w, h, _ = self.slots[i]
                if w + 2 > size:
                    ok = False
                    break
                if x + w + 2 > size:
                    x, y, shelf = 0, y + shelf, 0
                if y + h + 2 > size:
                    ok = False
                    break
                pos[i] = (x + 1, y + 1, w, h)
                x += w + 2
                shelf = max(shelf, h + 2)
            if ok:
                break
            size *= 2
            assert size <= self.max_size, f"sheet {self.name} needs more than {self.max_size}px"
        # 页面高度能收就收（宽高都保持 2 的幂）
        used = max((p[1] + p[3] + 1 for p in pos.values()), default=1)
        height = 16
        while height < used:
            height *= 2
        self.pos, self.width, self.height = pos, size, height

    def image(self):
        if self.pos is None:
            self.pack()
        img = np.zeros((self.height, self.width, 3), np.uint8)
        img[:] = (90, 80, 72)  # 空白处填不透明的灰（整页不透明 → 原版按实心方块的渲染层画，最便宜）
        for i, (w, h, painter) in enumerate(self.slots):
            x, y, _, _ = self.pos[i]
            tile = painter()
            assert tile.shape[:2] == (h, w), (self.name, tile.shape, (h, w))
            self.mean[i] = tile.reshape(-1, tile.shape[-1])[:, :3].astype(np.float32).mean(axis=0)
            img[y:y + h, x:x + w] = tile
            # 延展边
            img[y - 1, x:x + w] = tile[0]
            img[y + h, x:x + w] = tile[-1]
            img[y - 1:y + h + 1, x - 1] = img[y - 1:y + h + 1, x]
            img[y - 1:y + h + 1, x + w] = img[y - 1:y + h + 1, x + w - 1]
        return Image.fromarray(img, "RGB")

    def save(self):
        TEX_DIR.mkdir(parents=True, exist_ok=True)
        self.image().save(TEX_DIR / f"{self.name}.png", optimize=True)


def _px(v):
    """模型长度 → 像素数（至少 1）。"""
    return max(1, int(math.floor(v + 0.5)))


def paint_face(mat, w, h, along, seed, bevel=None, deco=None, tone=None):
    """画一块面：材质色阶 + 倒角 + 装饰，返回 (h, w, 3) uint8。"""
    pal_name, fn, base = MATERIALS[mat]
    pal = PALETTE[pal_name]
    rng = _rng(mat, w, h, along, seed)
    t = fn(w, h, along, rng, base if tone is None else tone).astype(np.float32)
    k = BEVEL.get(mat, DEFAULT_BEVEL) if bevel is None else bevel
    if k:
        if h >= 3:
            t[0, :] += 0.9 * k
            t[-1, :] -= 0.8 * k
        if w >= 3:
            t[:, 0] += 0.35 * k
            t[:, -1] -= 0.45 * k
    out = _tone(pal, t)
    if deco:
        for d in (deco if isinstance(deco, (list, tuple)) else [deco]):
            out = d(out, w, h, _rng("deco", mat, w, h, seed))
    return np.clip(out, 0, 255).astype(np.uint8)


def _face_dims(face, size):
    (ua, _), (va, _), _ = FACE_AXES[face]
    return size[AXIS[ua]], size[AXIS[va]]


def _along(face, grain):
    (ua, _), (va, _), na = FACE_AXES[face]
    if grain == na:
        return "n"
    return "u" if grain == ua else "v"


def cube(frm, to, mat, sheet, faces=None, grain=None, bevel=None, deco=None, rot=None, shade=True, seed=0, tone=None):
    """一个长方体元素。

    mat    默认材质名（MATERIALS 的键）
    sheet  这个元素用的贴图页
    faces  按面覆盖：{"north": None} 不画这一面；{"up": "gilt"} 换材质；
           {"north": {"mat": ..., "deco": ..., "bevel": ..., "tone": ..., "flip_u": bool}} 细调
    grain  木纹 / 拉丝方向的模型轴 "x"/"y"/"z"，默认取最长边
    deco   所有面都加的装饰（见下面的装饰函数），也可以在 faces 里按面给
    rot    rotation 字段（axis/angle 或 x/y/z 欧拉角，含 origin），用 rot_axis()/rot_euler() 造
    seed   同样大小、同样材质的面想画得不一样时换个种子
    """
    size = [to[i] - frm[i] for i in range(3)]
    assert all(s >= 0 for s in size), (frm, to)
    if grain is None:
        grain = "xyz"[int(np.argmax(size))]
    out = {}
    for face in FACE_AXES:
        spec = (faces or {}).get(face, mat)
        if spec is None:
            continue
        if isinstance(spec, str):
            spec = {"mat": spec}
        m = spec.get("mat", mat)
        fw, fh = _face_dims(face, size)
        if fw <= 1e-6 or fh <= 1e-6:
            continue  # 零厚度那一面不存在
        pw, ph = _px(fw), _px(fh)
        along = _along(face, spec.get("grain", grain))
        fdeco = spec.get("deco", deco)
        fb = spec.get("bevel", bevel)
        ft = spec.get("tone", tone)
        fseed = spec.get("seed", seed)
        key = (m, pw, ph, along, fb, ft, fseed, _deco_key(fdeco))
        slot = sheet.add(key, pw, ph, (lambda m=m, pw=pw, ph=ph, along=along, fseed=fseed, fb=fb, fdeco=fdeco, ft=ft:
                                       paint_face(m, pw, ph, along, fseed, fb, fdeco, ft)))
        ref = FaceRef(sheet, slot, spec.get("flip_u", False), spec.get("flip_v", False))
        f = {"texture": "#" + sheet.name, "uv": ref}
        if spec.get("rotation"):
            f["rotation"] = spec["rotation"]
        if spec.get("cullface"):
            f["cullface"] = spec["cullface"]
        out[face] = f
    e = {"from": [round(v, 4) for v in frm], "to": [round(v, 4) for v in to], "faces": out}
    if not shade:
        e["shade"] = False
    if rot:
        e["rotation"] = rot
    return e


def _deco_key(d):
    if d is None:
        return None
    if isinstance(d, (list, tuple)):
        return tuple(_deco_key(x) for x in d)
    return getattr(d, "key", None) or (getattr(d, "__name__", "deco"), id(d))


# —— 装饰：(颜色数组, w, h, rng) → 颜色数组。都带 key，方便同样的面共用 ——

def deco(key):
    def wrap(fn):
        fn.key = key
        return fn
    return wrap


def frame_line(mat="gilt", inset=1, tone=3.2):
    """面板上画一圈细线（镶线 / 金色收边线），离边 inset 像素。"""
    col = _tone(PALETTE[MATERIALS[mat][0]], np.array(tone))

    @deco(("frame_line", mat, inset, tone))
    def fn(img, w, h, rng):
        if w > 2 * inset + 1 and h > 2 * inset + 1:
            img[inset, inset:w - inset] = col
            img[h - 1 - inset, inset:w - inset] = col * 0.82
            img[inset:h - inset, inset] = col * 0.95
            img[inset:h - inset, w - 1 - inset] = col * 0.82
        return img
    return fn


STARBLOOM = ["..#..", ".###.", "#####", ".#.#.", "#...#"]  # 五瓣星辰花（5×5）
STARBLOOM_SMALL = [".#.", "###", "#.#"]                 # 3×3 的小星花


def stamp(pattern, mat="gilt", tone=3.4, at=None, center_tone=None):
    """在面上（默认居中）印一个像素图案：'#' 用材质亮色；'o' 用 center_tone（花心）。"""
    pal = PALETTE[MATERIALS[mat][0]]

    @deco(("stamp", tuple(pattern), mat, tone, at, center_tone))
    def fn(img, w, h, rng):
        ph, pw = len(pattern), len(pattern[0])
        x0, y0 = at if at else ((w - pw) // 2, (h - ph) // 2)
        for j, row in enumerate(pattern):
            for i, c in enumerate(row):
                x, y = x0 + i, y0 + j
                if 0 <= x < w and 0 <= y < h and c != ".":
                    t = center_tone if (c == "o" and center_tone is not None) else tone - 0.25 * (j / max(1, ph - 1))
                    img[y, x] = _tone(pal, np.array(t))
        return img
    return fn


def gradient(axis="v", amount=0.75, toward="end"):
    """沿 u 或 v 渐暗（提琴面板边缘、管口内侧……），乘在颜色上。toward="both" 两头暗。"""
    @deco(("gradient", axis, amount, toward))
    def fn(img, w, h, rng):
        n = h if axis == "v" else w
        x = (np.arange(n) + 0.5) / n
        if toward == "end":
            k = 1 - (1 - amount) * x
        elif toward == "start":
            k = 1 - (1 - amount) * (1 - x)
        else:
            k = 1 - (1 - amount) * (np.abs(x - 0.5) * 2) ** 1.5
        return img * (k[:, None, None] if axis == "v" else k[None, :, None])
    return fn


def pixels(points, mat, tone=3.0):
    """在给定的像素坐标上点色：points 是 [(x, y), ...]（贴图里的 u, v，左上为原点）。"""
    col = _tone(PALETTE[MATERIALS[mat][0]], np.array(tone))

    @deco(("pixels", tuple(points), mat, tone))
    def fn(img, w, h, rng):
        for x, y in points:
            if 0 <= x < w and 0 <= y < h:
                img[y, x] = col
        return img
    return fn


def custom(key, fn):
    """任意装饰：fn(img, w, h, rng) → img。key 要唯一且稳定（同样的 key 视为同一种装饰）。"""
    fn.key = ("custom", key)
    return fn


# —— 转动与变换 ——

def rot_axis(axis, angle, origin, rescale=False):
    r = {"axis": axis, "angle": round(angle, 4), "origin": [round(v, 4) for v in origin]}
    if rescale:
        r["rescale"] = True
    return r


def rot_euler(x=0.0, y=0.0, z=0.0, origin=(8, 8, 8)):
    """原版 26.1 的欧拉角转动：先绕 x、再绕 y、再绕 z（rotationZYX），角度任意。"""
    return {"origin": [round(v, 4) for v in origin], "x": round(x, 4), "y": round(y, 4), "z": round(z, 4)}


def _matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _apply(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def euler(x=0.0, y=0.0, z=0.0):
    cx, sx = math.cos(math.radians(x)), math.sin(math.radians(x))
    cy, sy = math.cos(math.radians(y)), math.sin(math.radians(y))
    cz, sz = math.cos(math.radians(z)), math.sin(math.radians(z))
    rx = [[1, 0, 0], [0, cx, -sx], [0, sx, cx]]
    ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
    rz = [[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]]
    return _matmul(rz, _matmul(ry, rx))


def element_matrix(e):
    r = e.get("rotation")
    if not r:
        return None
    if "axis" in r:
        return euler(**{r["axis"]: r["angle"]})
    return euler(r.get("x", 0), r.get("y", 0), r.get("z", 0))


def _decompose(m):
    """m = Rz·Ry·Rx → (x, y, z) 度。y = ±90° 时 x 和 z 绕的是同一根轴（万向锁），取 x = 0、全部算到 z 上。"""
    y = -math.asin(max(-1.0, min(1.0, m[2][0])))
    if abs(m[2][0]) > 0.999999:
        return 0.0, math.degrees(y), math.degrees(math.atan2(-m[0][1], m[1][1]))
    x = math.atan2(m[2][1], m[2][2])
    z = math.atan2(m[1][0], m[0][0])
    return math.degrees(x), math.degrees(y), math.degrees(z)


def shifted(elements, dx, dy, dz):
    out = copy.deepcopy(elements)
    for e in out:
        e["from"] = [round(e["from"][0] + dx, 4), round(e["from"][1] + dy, 4), round(e["from"][2] + dz, 4)]
        e["to"] = [round(e["to"][0] + dx, 4), round(e["to"][1] + dy, 4), round(e["to"][2] + dz, 4)]
        if "rotation" in e:
            o = e["rotation"]["origin"]
            e["rotation"]["origin"] = [round(o[0] + dx, 4), round(o[1] + dy, 4), round(o[2] + dz, 4)]
    return out


def scaled(elements, k, anchor):
    """以 anchor 为中心整体缩放（贴图块不变，像素密度跟着变；只用于摆件，比如谱架上的小乐器）。"""
    out = copy.deepcopy(elements)
    for e in out:
        e["from"] = [round(anchor[i] + (e["from"][i] - anchor[i]) * k, 4) for i in range(3)]
        e["to"] = [round(anchor[i] + (e["to"][i] - anchor[i]) * k, 4) for i in range(3)]
        if "rotation" in e:
            o = e["rotation"]["origin"]
            e["rotation"]["origin"] = [round(anchor[i] + (o[i] - anchor[i]) * k, 4) for i in range(3)]
    return out


def rigid(elements, m, origin):
    """整组元素绕 origin 转 m（3×3，可用 euler() 造）。已带转动的元素与新转动合成。"""
    out = []
    for e in copy.deepcopy(elements):
        m1 = element_matrix(e)
        o1 = list(origin) if m1 is None else e["rotation"]["origin"]
        m1 = m1 or euler()
        moved = _apply(m, [o1[i] - origin[i] for i in range(3)])
        t = [origin[i] + moved[i] - o1[i] for i in range(3)]
        x, y, z = _decompose(_matmul(m, m1))
        e["from"] = [round(e["from"][i] + t[i], 4) for i in range(3)]
        e["to"] = [round(e["to"][i] + t[i], 4) for i in range(3)]
        e["rotation"] = {"origin": [round(o1[i] + t[i], 4) for i in range(3)], "x": round(x, 4), "y": round(y, 4), "z": round(z, 4)}
        out.append(e)
    return out


def segment(p0, p1, w, h, mat, sheet, roll=0.0, **kw):
    """从 p0 到 p1 的一根杆（截面 w×h，w 在水平方向）：沿 +X 画好再转过去。用来画斜撑、弯弧的一段、琴弓。"""
    d = [b - a for a, b in zip(p0, p1)]
    length = math.sqrt(sum(v * v for v in d))
    ux, uy, uz = (v / length for v in d)
    e = cube((p0[0], p0[1] - h / 2, p0[2] - w / 2), (p0[0] + length, p0[1] + h / 2, p0[2] + w / 2), mat, sheet, grain="x", **kw)
    # +X 先绕 x 自转 roll，再绕 z 抬起 alpha，最后绕 y 转到水平方向 psi；合成后拆成原版的欧拉角
    alpha = math.degrees(math.asin(max(-1.0, min(1.0, uy))))
    psi = math.degrees(math.atan2(-uz, ux)) if math.hypot(ux, uz) > 1e-9 else 0.0
    m = _matmul(euler(0, psi, 0), _matmul(euler(0, 0, alpha), euler(roll, 0, 0)))
    x, y, z = _decompose(m)
    e["rotation"] = {"origin": [round(v, 4) for v in p0], "x": round(x, 4), "y": round(y, 4), "z": round(z, 4)}
    return e


def wall(points, y0, y1, thick, mat, sheet, outside=1, **kw):
    """沿水平折线 points=[(x, z), ...] 立一道薄墙（琴身弯边、竖琴共鸣箱的侧壁……）。
    每段是一块绕 y 转过去的板，相邻两段在拐角处各延长一点，外侧不留缝。outside=±1 决定厚度长在折线哪一边。"""
    els = []
    n = len(points)
    for i in range(n - 1):
        (x0, z0), (x1, z1) = points[i], points[i + 1]
        dx, dz = x1 - x0, z1 - z0
        length = math.hypot(dx, dz)
        ang = math.degrees(math.atan2(-dz, dx))  # 绕 y 转 ang 后，+X 指向这一段
        ext0 = ext1 = 0.0
        if i > 0:
            ext0 = thick * 0.5 * abs(math.tan(math.radians(_turn(points[i - 1], points[i], points[i + 1]) / 2)))
        if i < n - 2:
            ext1 = thick * 0.5 * abs(math.tan(math.radians(_turn(points[i], points[i + 1], points[i + 2]) / 2)))
        z_lo, z_hi = (0, thick) if outside > 0 else (-thick, 0)
        g = MIN_GAP if i % 2 else 0.0  # 相邻两段在拐角处交叠，顶面 / 底面错开一点
        e = cube((x0 - ext0, y0 + g, z0 + z_lo), (x0 + length + ext1, y1 - g, z0 + z_hi), mat, sheet, grain="x", **kw)
        e["rotation"] = {"origin": [round(x0, 4), round(y0, 4), round(z0, 4)], "x": 0, "y": round(ang, 4), "z": 0}
        els.append(e)
    return els


def _turn(a, b, c):
    a1 = math.atan2(b[1] - a[1], b[0] - a[0])
    a2 = math.atan2(c[1] - b[1], c[0] - b[0])
    d = math.degrees(a2 - a1)
    return (d + 180) % 360 - 180


def polygon_prism(c, apothem, lo, hi, mat, sheet, sides=8, axis="y", start=0.0, faces=None, **kw):
    """正多边形柱（圆柱的近似）：sides/2 条长方体，每条宽 2·apothem·tan(180°/sides)、长 2·apothem，
    依次转 360°/sides 叠在一起，拼出的正好是正 sides 边形（每条长方体的两个端面就是多边形的两条边）。
    每多一条，两端各缩进 MIN_GAP，交叠的顶面 / 底面错开不闪；条与条之间藏在里面的长侧面不画。

    c      截面中心：axis="y" 时是 (x, z)，"x" 时是 (y, z)，"z" 时是 (x, y)
    lo, hi 沿 axis 的范围
    start  整体再转的角度（让一条边朝正前方用 0，让一个角朝正前方用 180/sides）
    faces  按「端面」覆盖：{"top": ..., "bottom": ...}（沿 axis 正 / 负的两个盖）
    """
    n = sides // 2
    half_w = apothem * math.tan(math.pi / sides)
    els = []
    for i in range(n):
        g = i * MIN_GAP
        ang = start + 180.0 / n * i
        a, b = c
        if axis == "y":
            frm, to = (a - apothem, lo + g, b - half_w), (a + apothem, hi - g, b + half_w)
            origin = (a, (lo + hi) / 2, b)
            rot = {"origin": [round(v, 4) for v in origin], "x": 0, "y": round(ang, 4), "z": 0}
            hidden = ("north", "south")
            caps = {"up": "top", "down": "bottom"}
        elif axis == "x":
            frm, to = (lo + g, a - half_w, b - apothem), (hi - g, a + half_w, b + apothem)
            origin = ((lo + hi) / 2, a, b)
            rot = {"origin": [round(v, 4) for v in origin], "x": round(ang, 4), "y": 0, "z": 0}
            hidden = ("up", "down")
            caps = {"east": "top", "west": "bottom"}
        else:
            frm, to = (a - apothem, b - half_w, lo + g), (a + apothem, b + half_w, hi - g)
            origin = (a, b, (lo + hi) / 2)
            rot = {"origin": [round(v, 4) for v in origin], "x": 0, "y": 0, "z": round(ang, 4)}
            hidden = ("up", "down")
            caps = {"south": "top", "north": "bottom"}
        fs = {h: None for h in hidden}
        for face, cap in caps.items():
            if faces and cap in faces:
                fs[face] = faces[cap]
        e = cube(frm, to, mat, sheet, grain=axis, faces=fs, **kw)
        if abs(ang % 360) > 1e-9:
            e["rotation"] = rot
        els.append(e)
    return els


# —— 物品显示变换 ——

def _corners(elements):
    pts = []
    for e in elements:
        f, t = e["from"], e["to"]
        m = element_matrix(e)
        o = e["rotation"]["origin"] if m else None
        for x in (f[0], t[0]):
            for y in (f[1], t[1]):
                for z in (f[2], t[2]):
                    p = [x, y, z]
                    if m:
                        p = [o[i] + v for i, v in enumerate(_apply(m, [p[i] - o[i] for i in range(3)]))]
                    pts.append(p)
    return np.array(pts, dtype=np.float64)


def bounds(elements):
    p = _corners(elements)
    return p.min(axis=0), p.max(axis=0)


def _display_matrix(rot):
    rx, ry, rz = (math.radians(a) for a in rot)
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    mx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    my = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    mz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return mx @ my @ mz  # 原版 ItemTransform：Quaternionf.rotationXYZ


def fit(elements, rotation, extent, center=True, max_scale=1.0, depth=False):
    """按 rotation 转过之后，最长的屏幕边（x 或 y；depth 时连 z 一起算，地上的物品会转圈）正好是 extent 像素时的缩放，
    和把包围盒中心挪到原点的平移。"""
    p = _corners(elements) - 8.0
    r = _display_matrix(rotation)
    q = p @ r.T
    lo, hi = q.min(axis=0), q.max(axis=0)
    span = max(hi[0] - lo[0], hi[1] - lo[1], (hi[2] - lo[2]) if depth else 0, 1e-6)
    s = min(max_scale, extent / span)
    c = (lo + hi) / 2 * s
    t = [round(-c[0], 3), round(-c[1], 3), 0.0] if center else [0.0, 0.0, 0.0]
    t = [max(-80.0, min(80.0, v)) for v in t]
    return round(s, 4), t


def display(elements, gui_rot=(30, 225, 0), gui=15.0, ground=6.0, fixed=12.0, third=8.0, first=9.0, hand=None, ground_lift=2.0):
    """方块乐器、家具的物品显示：物品栏里撑满 15/16 格、地上最长 6 像素、手里 8–9 像素（原版方块是 6 和 6.4）。
    hand 给了就用它覆盖第一 / 第三人称（手持乐器有自己的姿势）。"""
    gs, gt = fit(elements, gui_rot, gui)
    gr, gtt = fit(elements, (0, 0, 0), ground, depth=True)
    fs, ft = fit(elements, (0, 180, 0), fixed)
    ts, tt = fit(elements, (75, 45, 0), third)
    ps, pt = fit(elements, (0, 45, 0), first)
    d = {
        "gui": {"rotation": list(gui_rot), "translation": gt, "scale": [gs] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [gtt[0], round(gtt[1] + ground_lift, 3), 0], "scale": [gr] * 3},
        "fixed": {"rotation": [0, 180, 0], "translation": ft, "scale": [fs] * 3},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [tt[0], round(tt[1] + 2.5, 3), 0], "scale": [ts] * 3},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": pt, "scale": [ps] * 3},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": pt, "scale": [ps] * 3},
    }
    if hand:
        d.update(hand)
    return d


# —— 检查：边界、预算、共面闪烁 ——

def _faces_world(elements):
    """每个面的四个角（世界坐标，已转动）和法线。"""
    out = []
    for idx, e in enumerate(elements):
        f, t = e["from"], e["to"]
        m = element_matrix(e)
        o = e["rotation"]["origin"] if m else None

        def tr(p):
            if not m:
                return np.array(p, float)
            return np.array([o[i] + v for i, v in enumerate(_apply(m, [p[i] - o[i] for i in range(3)]))])
        x0, y0, z0 = f
        x1, y1, z1 = t
        quads = {
            "north": [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0)],
            "south": [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)],
            "west": [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)],
            "east": [(x1, y0, z0), (x1, y0, z1), (x1, y1, z1), (x1, y1, z0)],
            "down": [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)],
            "up": [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
        }
        normals = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0), "down": (0, -1, 0), "up": (0, 1, 0)}
        for face in e["faces"]:
            pts = np.array([tr(p) for p in quads[face]])
            n = np.array(normals[face], float)
            if m:
                n = np.array(_apply(m, list(n)))
            out.append((idx, face, pts, n / np.linalg.norm(n)))
    return out


def _clip(subject, clip):
    """凸多边形裁剪（Sutherland–Hodgman），二维。"""
    def inside(p, a, b):
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= -1e-9

    def inter(p, q, a, b):
        x1, y1, x2, y2 = p[0], p[1], q[0], q[1]
        x3, y3, x4, y4 = a[0], a[1], b[0], b[1]
        den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if abs(den) < 1e-12:
            return q
        tt = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den
        return (x1 + tt * (x2 - x1), y1 + tt * (y2 - y1))
    out = subject
    for i in range(len(clip)):
        a, b = clip[i], clip[(i + 1) % len(clip)]
        inp, out = out, []
        if not inp:
            break
        s = inp[-1]
        for p in inp:
            if inside(p, a, b):
                if not inside(s, a, b):
                    out.append(inter(s, p, a, b))
                out.append(p)
            elif inside(s, a, b):
                out.append(inter(s, p, a, b))
            s = p
    return out


def _area(poly):
    if len(poly) < 3:
        return 0.0
    return abs(sum(poly[i][0] * poly[(i + 1) % len(poly)][1] - poly[(i + 1) % len(poly)][0] * poly[i][1] for i in range(len(poly)))) / 2


def zfights(elements, gap=MIN_GAP):
    """同向、平行、距离小于 gap、投影有重叠（> 0.0004 平方像素）的两个面。返回 [(i, face, j, face, 距离, 面积)]。"""
    faces = _faces_world(elements)
    groups = {}
    for f in faces:
        key = tuple(np.round(f[3], 3) + 0.0)
        groups.setdefault(key, []).append((float(f[3] @ f[2][0]),) + f)
    hits = []
    for group in groups.values():
        group.sort(key=lambda g: g[0])
        for a in range(len(group)):
            da, ia, fa, pa, na = group[a]
            for b in range(a + 1, len(group)):
                db, ib, fb, pb, nb = group[b]
                if db - da >= gap - 1e-3:  # 错开正好 MIN_GAP 的（转动后的浮点误差让它变成 0.04999…）不算
                    break
                if ia == ib or float(na @ nb) < 0.9999:
                    continue
                hit = _overlap(pa, na, pb)
                if hit > 4e-4:
                    hits.append((ia, fa, ib, fb, round(abs(da - db), 4), round(hit, 4)))
    return hits


def _overlap(pa, na, pb):
    """两个平行面投到 a 所在平面后重叠的面积。"""
    u = pa[1] - pa[0]
    u = u / np.linalg.norm(u)
    v = np.cross(na, u)
    A = [(float((p - pa[0]) @ u), float((p - pa[0]) @ v)) for p in pa]
    B = [(float((p - pa[0]) @ u), float((p - pa[0]) @ v)) for p in pb]
    if sum(A[i][0] * A[(i + 1) % 4][1] - A[(i + 1) % 4][0] * A[i][1] for i in range(4)) < 0:
        A = A[::-1]
    if sum(B[i][0] * B[(i + 1) % 4][1] - B[(i + 1) % 4][0] * B[i][1] for i in range(4)) < 0:
        B = B[::-1]
    return _area(_clip(A, B))


def lint(name, elements, budget, strict=True):
    """边界、预算、共面闪烁。strict 时有问题就抛异常（生成脚本跑不过去）。"""
    problems = []
    for i, e in enumerate(elements):
        for v in e["from"] + e["to"]:
            if not -16 <= v <= 32:
                problems.append(f"element {i} out of the -16..32 range: {e['from']} {e['to']}")
                break
        if not e["faces"]:
            problems.append(f"element {i} has no faces")
    cap = BUDGET.get(budget, budget) if isinstance(budget, str) else budget
    if len(elements) > cap:
        problems.append(f"{len(elements)} elements, budget {budget} = {cap}")
    for ia, fa, ib, fb, dist, area in zfights(elements):
        problems.append(f"z-fight: element {ia}.{fa} and {ib}.{fb} ({dist} apart, overlap {area})")
    if problems and strict:
        raise AssertionError(f"{name}:\n  " + "\n  ".join(problems))
    return problems


def prune_hidden(elements):
    """去掉被别的（不转动的）元素整块挡住的面：贴在另一个长方体表面上、整个落在它截面里的面。
    省三角形，也顺带消掉一部分共面。只看单个遮挡物，不做多个元素拼起来的覆盖。"""
    boxes = [(i, e["from"], e["to"]) for i, e in enumerate(elements) if "rotation" not in e]
    norm = {"north": (2, -1), "south": (2, 1), "west": (0, -1), "east": (0, 1), "down": (1, -1), "up": (1, 1)}
    eps = 1e-4
    out = copy.deepcopy(elements)
    for i, e in enumerate(out):
        if "rotation" in e:
            continue
        f, t = e["from"], e["to"]
        for face in list(e["faces"]):
            ax, sgn = norm[face]
            plane = t[ax] if sgn > 0 else f[ax]
            others = [a for a in range(3) if a != ax]
            for j, bf, bt in boxes:
                if j == i:
                    continue
                inside = bf[ax] - eps <= plane < bt[ax] - eps if sgn > 0 else bf[ax] + eps < plane <= bt[ax] + eps
                if not inside:
                    continue
                if all(bf[a] - eps <= f[a] and t[a] <= bt[a] + eps for a in others):
                    del e["faces"][face]
                    break
    return [e for e in out if e["faces"]]


# —— 写文件 ——

def _encode(obj):
    if isinstance(obj, FaceRef):
        return obj.uv()
    if isinstance(obj, dict):
        return {k: _encode(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_encode(v) for v in obj]
    if isinstance(obj, (np.floating,)):
        return float(obj)
    return obj


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(_encode(obj), ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


class Out:
    """收集模型，flush() 时先画贴图页，再写 JSON（这时 FaceRef 才能换成 uv）。

    所有组共用一个 Out（instrument_models.build_all）。同名的贴图页只有一张：sheet(name) 第二次拿到的是同一个对象。
    一张页上的面只能由一个函数登记（否则单独跑某一组时排版会不一样）；别的组要用这些元素，就调那个函数，
    并用 memo() 包起来，保证同一个 Out 里只造一次。
    """

    def __init__(self):
        self.sheets = {}
        self.files = []
        self.cache = {}

    def sheet(self, name, max_size=256):
        if name not in self.sheets:
            self.sheets[name] = Sheet(name, max_size)
        return self.sheets[name]

    def memo(self, key, fn):
        """同一个 Out 里 fn() 只跑一次（比如「小提琴一家的元素和贴图页」），之后返回同一份结果。"""
        if key not in self.cache:
            self.cache[key] = fn()
        return self.cache[key]

    def block_model(self, name, elements, sheets, budget, particle=None, ao=False, extra=None, textures=None):
        """textures：另外引用的现成贴图 {键: "logos_dimension:block/..."}（比如小号上的魔女之森旗），元素里用 "#键"。"""
        lint(f"block/{name}", elements, budget)
        tex = {s.name: s.texture for s in sheets} | (textures or {})
        tex["particle"] = particle or sheets[0].texture
        obj = {"ambientocclusion": ao, "textures": tex, "elements": elements}
        if extra:
            obj.update(extra)
        self.files.append((ASSETS / f"models/block/{name}.json", obj))

    def item_model(self, name, elements, sheets, budget, disp, particle=None, textures=None):
        lint(f"item/{name}", elements, budget)
        tex = {s.name: s.texture for s in sheets} | (textures or {})
        tex["particle"] = particle or sheets[0].texture
        self.files.append((ASSETS / f"models/item/{name}.json",
                           {"ambientocclusion": False, "textures": tex, "elements": elements, "display": disp}))

    def item_parent(self, name, block_model, disp):
        """物品直接用方块模型（加上自己的显示变换）。"""
        self.files.append((ASSETS / f"models/item/{name}.json", {"parent": f"{NS}:block/{block_model}", "display": disp}))

    def particle_only(self, name, texture):
        """多格乐器的附属块：不画模型，只给破坏粒子一张贴图。"""
        self.files.append((ASSETS / f"models/block/{name}.json", {"textures": {"particle": texture}}))

    def raw(self, path, obj):
        self.files.append((path, obj))

    def flush(self):
        for s in self.sheets.values():
            s.pack()
            s.save()
        self._particles()
        for path, obj in self.files:
            write_json(path, obj)
        names = ", ".join(f"{s.name} {s.width}x{s.height}" for s in self.sheets.values())
        print(f"wrote {len(self.files)} files; sheets: {names}")


# —— 破坏粒子 ——
# 原版的破坏粒子从 particle 贴图里随机取小块；用整张贴图页会是五颜六色的碎屑（还有页上的灰底）。
# 所以方块模型的 particle 如果是自己的贴图页，就换成「这件乐器面积最大的那种材质」的一张 16×16 色块，
# 多格乐器的 _part 跟着主模型走。色块按画出来的平均颜色找最近的材质（自定义画法的面也算得出来）。

PARTICLE_SKIP = {"shadow", "steel"}


def _nearest_material(color):
    best, name = None, None
    for m, (pal, _fn, base) in MATERIALS.items():
        if m in PARTICLE_SKIP or pal not in PALETTE:
            continue
        ref = _tone(PALETTE[pal], np.array(base))
        d = float(((ref - color) ** 2).sum())
        if best is None or d < best:
            best, name = d, m
    return name


def _dominant_material(obj):
    area = {}
    for e in obj.get("elements", []):
        for f in e["faces"].values():
            ref = f.get("uv")
            if not isinstance(ref, FaceRef) or ref.slot not in ref.sheet.mean:
                continue
            w, h = ref.sheet.slots[ref.slot][:2]
            m = _nearest_material(ref.sheet.mean[ref.slot])
            if m:
                area[m] = area.get(m, 0) + w * h
    return max(area, key=area.get) if area else None


def _out_particles(self):
    sheet_textures = {s.texture for s in self.sheets.values()}
    final, mats = {}, set(getattr(self, "particle_mats", ()))
    for path, obj in self.files:
        tex = obj.get("textures") if isinstance(obj, dict) else None
        if not tex or path.parent.name != "block" or "elements" not in obj:
            continue
        if tex.get("particle") in sheet_textures:
            m = _dominant_material(obj)
            if m:
                tex["particle"] = f"{NS}:block/instrument/particle_{m}"
                mats.add(m)
        final[path.stem] = tex.get("particle")
    # 多格乐器的 _part（只有粒子贴图）跟着主模型
    for path, obj in self.files:
        tex = obj.get("textures") if isinstance(obj, dict) else None
        if not tex or path.parent.name != "block" or not path.stem.endswith("_part"):
            continue
        main = final.get(path.stem[:-5])
        if main and tex.get("particle") in sheet_textures:
            tex["particle"] = main
    for m in sorted(mats):
        tile = paint_face(m, 16, 16, "u", "particle", bevel=0)
        TEX_DIR.mkdir(parents=True, exist_ok=True)
        Image.fromarray(tile, "RGB").save(TEX_DIR / f"particle_{m}.png", optimize=True)


def _out_particle(self, mat):
    """指定破坏粒子的材质（自动挑的不合适时用，比如白钢琴的底面胡桃木面积更大）：返回贴图 id，flush 时画出色块。"""
    if not hasattr(self, "particle_mats"):
        self.particle_mats = set()
    self.particle_mats.add(mat)
    return f"{NS}:block/instrument/particle_{mat}"


Out.particle = _out_particle
Out._particles = _out_particles
