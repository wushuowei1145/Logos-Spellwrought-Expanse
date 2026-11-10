"""魔女之森乐器的美术与数据：星辰花三角钢琴、灵猫竖琴的方块模型，纸带图标，方块状态、掉落、配方。

    uv run --no-project --with pillow python tools/instrument_art.py

外观（见 docs/HEXENWALD_INSTRUMENTS.md 第 1 节）：白色大理石纹（原版方解石）配深色胡桃木（原版深色橡木板）、
淡金收边（原版金块）、云杉音板；钢琴的琴盖内侧和竖琴共鸣箱用星辰花镶嵌。能用原版贴图的地方都按路径引用，
只新画四张：琴键、星辰花镶嵌、空白纸带、打孔纸带。

坐标约定：模型按 FACING=north 画，键盘（钢琴）/ 琴弦正面（竖琴）朝北（z 小的一侧）。
钢琴占 x 0–32、z 0–32（主块在 x 0–16、z 0–16，「侧边」是 +x，「琴尾」是 +z），和 GrandPianoBlock 的 part 偏移一致。
竖琴占 y 0–32（下半是主块）。物品模型是同样的元素平移到居中。
"""
import json
import math
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources"
ASSETS = RES / "assets/logos_dimension"
DATA = RES / "data/logos_dimension"

T = {
    "marble": "minecraft:block/calcite",
    "lacquer": "minecraft:block/quartz_block_top",
    "walnut": "minecraft:block/dark_oak_planks",
    "gold": "minecraft:block/gold_block",
    "spruce": "minecraft:block/spruce_planks",
    "maple": "minecraft:block/birch_planks",
    "iron": "minecraft:block/iron_block",
    "white": "minecraft:block/white_concrete",
    "red": "minecraft:block/red_concrete",
    "blue": "minecraft:block/blue_concrete",
    "keys": "logos_dimension:block/instrument/piano_keys",
    "inlay": "logos_dimension:block/instrument/starbloom_inlay",
    "black": "minecraft:block/black_concrete",
}


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


# —— 模型元素 ——

def _span(a0, a1, flip):
    """沿一个方向截取贴图：长度不足 16 时按坐标取对应的一段，否则整张拉伸。flip 用于侧面（贴图 v 向下、坐标 y 向上）。"""
    w = a1 - a0
    if w >= 16:
        return 0.0, 16.0
    start = (16 - (a1 % 16 or 16)) if flip else a0 % 16
    start = min(max(0.0, start), 16 - w)
    return round(start, 3), round(start + w, 3)


def _uv(a0, a1, b0, b1, flip_v):
    u0, u1 = _span(a0, a1, False)
    v0, v1 = _span(b0, b1, flip_v)
    return [u0, v0, u1, v1]


def box(frm, to, tex, faces=None, rot=None, uv=None):
    """一个长方体。tex 是默认贴图键；faces 可按面覆盖贴图键（值为 None 表示不画这一面）。"""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    spans = {
        "north": (x0, x1, y0, y1), "south": (x0, x1, y0, y1),
        "east": (z0, z1, y0, y1), "west": (z0, z1, y0, y1),
        "up": (x0, x1, z0, z1), "down": (x0, x1, z0, z1),
    }
    out = {}
    for face, (a0, a1, b0, b1) in spans.items():
        key = (faces or {}).get(face, tex)
        if key is None:
            continue
        face_uv = _uv(a0, a1, b0, b1, face not in ("up", "down"))
        if uv and face in uv:
            face_uv = uv[face]
        out[face] = {"texture": "#" + key, "uv": face_uv}
    e = {"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to], "faces": out}
    if rot:
        e["rotation"] = rot
    return e


def rotation(axis, angle, origin):
    return {"axis": axis, "angle": round(angle, 3), "origin": [round(v, 3) for v in origin]}


def shifted(elements, dx, dy, dz):
    out = []
    for e in elements:
        e = json.loads(json.dumps(e))
        e["from"] = [round(e["from"][0] + dx, 3), round(e["from"][1] + dy, 3), round(e["from"][2] + dz, 3)]
        e["to"] = [round(e["to"][0] + dx, 3), round(e["to"][1] + dy, 3), round(e["to"][2] + dz, 3)]
        if "rotation" in e:
            o = e["rotation"]["origin"]
            e["rotation"]["origin"] = [round(o[0] + dx, 3), round(o[1] + dy, 3), round(o[2] + dz, 3)]
        out.append(e)
    return out


# —— 钢琴 ——

# 琴身的台阶（z0, z1, 左缘 x）：右侧（低音侧，x=31）是直边，左侧（高音侧）是弯边，逐渐收向琴尾
PIANO_SLICES = [(6.5, 13, 1), (13, 18, 2), (18, 22, 4), (22, 25, 7), (25, 28, 11), (28, 30, 16), (30, 31.5, 22)]
RIGHT = 31.0
LID_ANGLE = -25.0  # 绕 z 轴、以低音侧直边为铰链，高音侧抬起


def piano_elements():
    el = []
    # 琴腿：胡桃木，底部金色脚轮
    for x0, z0 in ((3, 2), (26, 2), (19, 26)):
        el.append(box((x0, 1, z0), (x0 + 3, 9, z0 + 3), "walnut"))
        el.append(box((x0 + 0.5, 0, z0 + 0.5), (x0 + 2.5, 1, z0 + 2.5), "gold"))
    # 踏板琴架（lyre）与三个金踏板
    el.append(box((15, 1.6, 4), (17, 9, 5), "walnut"))
    el.append(box((14, 1, 3.6), (18, 1.6, 5.2), "gold"))
    for x in (14.2, 15.6, 17.0):
        el.append(box((x, 0.6, 1.8), (x + 0.8, 1.2, 4.2), "gold"))
    # 键盘：键床、两侧扶手、琴键、金色键挡、谱板
    # 键床与两侧扶手用大理石纹，底面胡桃木：从正面看是一架白琴，胡桃木只做点缀
    el.append(box((2, 9, 0), (30, 12, 6.5), "marble", faces={"down": "walnut"}))
    el.append(box((1, 9, 0), (2.5, 14, 6.5), "marble", faces={"down": "walnut"}))
    el.append(box((29.5, 9, 0), (31, 14, 6.5), "marble", faces={"down": "walnut"}))
    el.append(box((1.8, 8.6, -0.2), (30.2, 9, 6.5), "gold", faces={"up": None}))
    el.append(box((2.5, 12, 0.6), (29.5, 13, 5.5), "white", faces={"up": "keys", "down": None},
                  uv={"up": [0, 0, 16, 16]}))
    el.append(box((2.5, 11.4, 0.2), (29.5, 12.1, 0.6), "gold"))
    el.append(box((2.5, 12, 5.5), (29.5, 15, 6.5), "walnut"))
    el.append(box((2.5, 14.4, 5.35), (29.5, 14.75, 5.5), "gold", faces={"south": None, "down": None}))
    # 琴身：金色底边 + 方解石侧面，顶面是云杉音板（琴盖打开时看得见）
    for z0, z1, xl in PIANO_SLICES:
        el.append(box((xl, 9, z0), (RIGHT, 10, z1), "gold"))
        el.append(box((xl, 10, z0), (RIGHT, 16, z1), "marble", faces={"up": "spruce", "down": "walnut"}))
        # 琴身上沿：一圈薄薄的方解石边
        el.append(box((xl, 16, z0), (xl + 0.8, 17, z1), "marble"))
    el.append(box((RIGHT - 0.8, 16, 6.5), (RIGHT, 17, 31.5), "marble"))
    el.append(box((22, 16, 30.7), (RIGHT, 17, 31.5), "marble"))
    el.append(box((1, 16, 6.5), (RIGHT, 17, 7.3), "marble"))
    # 金色铁骨架与几根琴弦
    el.append(box((5, 16, 8), (29.5, 16.4, 12), "gold", faces={"down": None}))
    el.append(box((9, 16, 12), (29.5, 16.4, 20), "gold", faces={"down": None}))
    for i, x in enumerate((8, 11.5, 15, 18.5, 22, 25.5, 28.5)):
        z_end = next((z1 for z0, z1, xl in reversed(PIANO_SLICES) if xl < x - 0.5), 13)
        el.append(box((x, 16.4, 8.5), (x + 0.3, 16.7, z_end - 0.8), "iron", faces={"down": None}))
    # 谱架：向后微倾，两端是小小的猫耳卷饰
    tilt = rotation("x", -15, (16, 15, 7.3))
    el.append(box((10, 15, 7), (22, 21, 7.6), "walnut", rot=tilt))
    for x in (10, 20.8):
        el.append(box((x, 21, 7.05), (x + 1.2, 22.2, 7.55), "gold", rot=tilt))
    # 琴盖：上面白漆、内侧星辰花镶嵌，以直边为铰链撑开
    lid = rotation("z", LID_ANGLE, (RIGHT, 17, 16))
    for z0, z1, xl in PIANO_SLICES:
        el.append(box((xl, 17, z0), (RIGHT, 17.6, z1), "lacquer", faces={"down": "inlay", "north": "gold", "south": "gold",
                                                                         "west": "gold", "east": "gold"}, rot=lid))
    # 琴盖撑杆（胡桃木），高度按琴盖的角度算
    stick_x, stick_z = 6.5, 15.5
    rise = (RIGHT - stick_x) * math.tan(math.radians(-LID_ANGLE))
    el.append(box((stick_x, 17, stick_z), (stick_x + 0.6, 17 + rise - 0.3, stick_z + 0.6), "walnut"))
    return el


# —— 竖琴 ——

def harp_elements():
    el = []
    # 底座：胡桃木 + 金边、四只小脚、七个踏板
    el.append(box((3, 0.6, 4), (13, 2.6, 12), "walnut"))
    el.append(box((2.8, 2.6, 3.8), (13.2, 3.1, 12.2), "gold"))
    for x, z in ((3.4, 4.4), (11.1, 4.4), (3.4, 10.1), (11.1, 10.1)):
        el.append(box((x, 0, z), (x + 1.5, 0.6, z + 1.5), "gold"))
    for i in range(7):
        x = 4.6 + i * 1.05
        el.append(box((x, 0.9, 3), (x + 0.5, 1.3, 4), "gold"))
    # 立柱与柱冠（金）
    el.append(box((2.5, 3.1, 6.6), (4.5, 27, 9.4), "gold"))
    el.append(box((2.2, 3.1, 6.3), (4.8, 4.2, 9.7), "gold"))
    el.append(box((1.6, 26.6, 6.1), (5.4, 28, 9.9), "gold"))
    # 柱顶坐着的灵猫：身体、头、两只耳朵、绕柱垂下的尾巴
    el.append(box((2.1, 28, 6.8), (4.9, 29.8, 9.4), "gold"))
    el.append(box((2.3, 29.4, 6.3), (4.7, 31.2, 8.5), "gold"))
    el.append(box((2.4, 31.2, 6.7), (3.1, 32, 7.5), "gold"))
    el.append(box((3.9, 31.2, 6.7), (4.6, 32, 7.5), "gold"))
    el.append(box((4.6, 24.5, 8.4), (5.1, 28.2, 8.9), "gold"))
    # 共鸣箱：斜放，正面是云杉音板（带星辰花镶嵌的音孔饰带），侧面枫木
    bx0, by0, bx1, by1 = 6.0, 3.1, 14.6, 24.2
    length = math.hypot(bx1 - bx0, by1 - by0)
    angle = -math.degrees(math.atan2(bx1 - bx0, by1 - by0))
    cx, cy = (bx0 + bx1) / 2, (by0 + by1) / 2
    lean = rotation("z", angle, (cx, cy, 8))
    el.append(box((cx - 1.7, cy - length / 2, 6.0), (cx + 1.7, cy + length / 2, 10.0), "maple",
                  faces={"north": "spruce"}, rot=lean))
    el.append(box((cx - 0.6, cy - length / 2 + 3, 5.9), (cx + 0.6, cy + length / 2 - 3, 6.0), "inlay",
                  faces={"south": None, "up": None, "down": None, "east": None, "west": None}, rot=lean))
    # 琴颈：从柱冠到共鸣箱顶端的 S 形，枫木 + 金色上沿
    neck = [(4.5, 25.6, 7.6, 27.8), (7.6, 25.2, 10.4, 27.3), (10.4, 24.4, 12.9, 26.6), (12.9, 23.4, 15.3, 25.8)]
    for x0, y0, x1, y1 in neck:
        el.append(box((x0, y0, 7.0), (x1, y1, 9.0), "maple"))
        el.append(box((x0, y1, 6.9), (x1, y1 + 0.35, 9.1), "gold", faces={"down": None}))

    def bottom(x):
        return by0 + (x - bx0) * (by1 - by0) / (bx1 - bx0) + 1.0

    def top(x):
        for x0, y0, x1, y1 in neck:
            if x <= x1:
                return y0
        return neck[-1][1]

    # 琴弦：C 弦红、F 弦蓝，其余白
    x, i = 5.2, 0
    while x < 14.2:
        yb, yt = max(3.1, bottom(x)), top(x)
        if yt - yb > 1.0:
            color = "red" if i % 7 == 0 else "blue" if i % 7 == 3 else "white"
            el.append(box((x, yb, 7.88), (x + 0.2, yt, 8.12), color, faces={"up": None, "down": None}))
            i += 1
        x += 0.58
    return el


def display(gui_scale, ground=0.18, rot_y=225):
    return {
        "gui": {"rotation": [30, rot_y, 0], "translation": [0, 0, 0], "scale": [gui_scale] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [ground] * 3},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [gui_scale * 1.1] * 3},
        "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [ground * 0.6] * 3},
        "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [ground * 0.6] * 3},
        "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [ground * 0.6] * 3},
    }


def textures(keys):
    return {k: T[k] for k in keys} | {"particle": T["marble" if "marble" in keys else "maple"]}


def used(elements):
    keys = set()
    for e in elements:
        for f in e["faces"].values():
            keys.add(f["texture"][1:])
    return sorted(keys)


def models():
    piano = piano_elements()
    harp = harp_elements()
    blocks = ASSETS / "models/block"
    write(blocks / "starbloom_grand_piano.json", {
        "ambientocclusion": False, "textures": textures(used(piano)), "elements": piano})
    write(blocks / "hexen_concert_harp.json", {
        "ambientocclusion": False, "textures": textures(used(harp)), "elements": harp})
    # 附属块不画模型（RenderShape.INVISIBLE），只给破坏粒子一张贴图
    write(blocks / "starbloom_grand_piano_part.json", {"textures": {"particle": T["marble"]}})
    write(blocks / "hexen_concert_harp_part.json", {"textures": {"particle": T["maple"]}})
    items = ASSETS / "models/item"
    write(items / "starbloom_grand_piano.json", {
        "ambientocclusion": False, "textures": textures(used(piano)), "elements": shifted(piano, -8, -3, -8),
        "display": display(0.36)})
    write(items / "hexen_concert_harp.json", {
        "ambientocclusion": False, "textures": textures(used(harp)), "elements": shifted(harp, 0, -8, 0),
        "display": display(0.5, ground=0.25, rot_y=200)})
    for name in ("blank_music_roll", "music_roll"):
        write(items / f"{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"logos_dimension:item/{name}"}})
    for name in ("starbloom_grand_piano", "hexen_concert_harp", "blank_music_roll", "music_roll"):
        write(ASSETS / f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{name}"}})


def blockstates():
    yrot = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for facing, y in yrot.items():
        for part in range(4):
            model = "starbloom_grand_piano" if part == 0 else "starbloom_grand_piano_part"
            v = {"model": f"logos_dimension:block/{model}"}
            if y and part == 0:
                v["y"] = y
            variants[f"facing={facing},part={part}"] = v
    write(ASSETS / "blockstates/starbloom_grand_piano.json", {"variants": variants})
    variants = {}
    for facing, y in yrot.items():
        for half in ("lower", "upper"):
            model = "hexen_concert_harp" if half == "lower" else "hexen_concert_harp_part"
            v = {"model": f"logos_dimension:block/{model}"}
            if y and half == "lower":
                v["y"] = y
            variants[f"facing={facing},half={half}"] = v
    write(ASSETS / "blockstates/hexen_concert_harp.json", {"variants": variants})


def loot_and_recipes():
    def loot(block, prop, value):
        return {"type": "minecraft:block", "pools": [{"rolls": 1, "conditions": [
            {"condition": "minecraft:survives_explosion"},
            {"condition": "minecraft:block_state_property", "block": f"logos_dimension:{block}", "properties": {prop: value}}],
            "entries": [{"type": "minecraft:item", "name": f"logos_dimension:{block}"}]}]}
    write(DATA / "loot_table/blocks/starbloom_grand_piano.json", loot("starbloom_grand_piano", "part", "0"))
    write(DATA / "loot_table/blocks/hexen_concert_harp.json", loot("hexen_concert_harp", "half", "lower"))
    write(DATA / "recipe/starbloom_grand_piano.json", {
        "type": "minecraft:crafting_shaped", "category": "misc",
        "pattern": ["QGQ", "PNP", "S S"],
        "key": {"Q": "minecraft:quartz_block", "G": "minecraft:gold_ingot", "P": "minecraft:dark_oak_planks",
                "N": "minecraft:note_block", "S": "minecraft:stick"},
        "result": {"id": "logos_dimension:starbloom_grand_piano", "count": 1}})
    write(DATA / "recipe/hexen_concert_harp.json", {
        "type": "minecraft:crafting_shaped", "category": "misc",
        "pattern": [" GS", "GNS", "PPP"],
        "key": {"G": "minecraft:gold_ingot", "S": "minecraft:string", "N": "minecraft:note_block", "P": "minecraft:birch_planks"},
        "result": {"id": "logos_dimension:hexen_concert_harp", "count": 1}})
    write(DATA / "recipe/blank_music_roll.json", {
        "type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": ["minecraft:paper", "minecraft:paper", "minecraft:paper", "minecraft:string"],
        "result": {"id": "logos_dimension:blank_music_roll", "count": 1}})
    write(DATA / "recipe/music_roll_copy.json", {"type": "logos_dimension:music_roll_copy"})


# —— 贴图 ——

def png(path, img):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


def piano_keys():
    """琴键顶视图：上方（北、靠近演奏者）是白键前端，下方是黑键。64×64，铺满琴键顶面：
    16 个白键各 4 像素（1 像素分隔线），黑键 2 像素宽、跨在两个白键的分界上，长度到白键的六成。"""
    size = 64
    img = Image.new("RGBA", (size, size), (244, 240, 230, 255))
    px = img.load()
    for x in range(size):
        for y in range(size):
            shade = 246 - (3 if y > 56 else 0)
            px[x, y] = (shade, shade - 5, shade - 16, 255) if x % 4 else (176, 168, 152, 255)
    # 黑键：每个八度（7 个白键）在第 1、2、4、5、6 个白键之后
    for k in range(15):
        if k % 7 in (0, 1, 3, 4, 5):
            bx = (k + 1) * 4 - 1
            for x in (bx, bx + 1):
                for y in range(24, size):
                    hi = 58 if y < 27 else 26
                    px[x, y] = (hi, hi - 4, hi - 6, 255)
    return img


def starbloom_inlay():
    """胡桃木底上的星辰花镶嵌（五瓣小金花）。16×16。"""
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    for x in range(16):
        for y in range(16):
            grain = (int(3 * math.sin((y + 0.4 * x) * 1.3)) + (x * 7 + y * 3) % 3)
            px[x, y] = (66 + grain, 44 + grain, 28 + grain, 255)
    gold, light, core = (214, 172, 72, 255), (246, 214, 122, 255), (250, 240, 200, 255)
    for cx, cy, r in ((4, 4, 2), (11, 10, 2), (12, 3, 1), (4, 12, 1)):
        for k in range(5):
            a = -math.pi / 2 + k * 2 * math.pi / 5
            for step in range(1, r + 1):
                x, y = round(cx + math.cos(a) * step), round(cy + math.sin(a) * step)
                if 0 <= x < 16 and 0 <= y < 16:
                    px[x, y] = gold if step == r else light
        px[cx, cy] = core
    # 细金线收边
    for x in range(16):
        px[x, 0] = px[x, 15] = (150, 116, 52, 255)
    return img


def roll_icon(punched):
    """斜放的一卷纸带：米色纸卷、两端木轴、金色系带；打孔纸带露出一截带孔的纸，加一枚星辰花封蜡。"""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    paper, paper_dark, paper_light = (236, 226, 196, 255), (196, 182, 146, 255), (250, 244, 224, 255)
    wood, wood_dark = (122, 82, 46, 255), (82, 52, 28, 255)
    ribbon = (214, 172, 72, 255)
    # 纸卷主体：从左下到右上的斜圆柱（宽 5 像素）
    for t in range(3, 13):
        cx, cy = t, 15 - t
        for d in range(-2, 3):
            for xx, yy in ((cx + d, cy + d), (cx + d, cy + d + 1)):
                if 0 <= xx < 16 and 0 <= yy < 16:
                    px[xx, yy] = paper_light if d == -2 else paper_dark if d == 2 else paper
    # 两端木轴
    for (cx, cy) in ((2, 13), (13, 2)):
        for dx in range(-1, 2):
            for dy in range(-1, 2):
                x, y = cx + dx, cy + dy
                if 0 <= x < 16 and 0 <= y < 16 and abs(dx) + abs(dy) < 2:
                    px[x, y] = wood if dx + dy <= 0 else wood_dark
    # 系带
    for d in range(-2, 3):
        x, y = 8 + d, 7 + d
        if 0 <= x < 16 and 0 <= y + 1 < 16:
            px[x, y] = ribbon
            px[x, y + 1] = ribbon
    if punched:
        # 垂下的一截纸带，上面两列打孔
        for y in range(9, 16):
            for x in range(10, 15):
                px[x, y] = paper if x < 14 else paper_dark
        for y, x in ((10, 11), (12, 12), (13, 11), (15, 12), (11, 13), (14, 13)):
            px[x, y] = (58, 44, 32, 255)
        # 红色封蜡与星点
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1), (-1, 0), (0, -1)):
            px[5 + dx, 4 + dy] = (168, 36, 40, 255)
        px[5, 4] = (236, 200, 110, 255)
    return img


def art():
    tex = ASSETS / "textures"
    png(tex / "block/instrument/piano_keys.png", piano_keys())
    png(tex / "block/instrument/starbloom_inlay.png", starbloom_inlay())
    png(tex / "item/blank_music_roll.png", roll_icon(False))
    png(tex / "item/music_roll.png", roll_icon(True))


# —— 管风琴（docs/PIPE_ORGAN.md）——
# 演奏台按 FACING=north 画：键盘朝北（z 小），占 x 0–32、y 0–32、z 0–16；主块在 x 0–16、y 0–16，「侧边」是 +x。

def organ_console_elements():
    el = []
    # 脚键盘：胡桃木框 + 琴键贴图
    el.append(box((1, 0, 0), (31, 1.2, 5), "walnut"))
    el.append(box((2, 1.2, 0.5), (30, 1.8, 4.5), "white", faces={"up": "keys", "down": None}, uv={"up": [0, 0, 16, 16]}))
    # 下层琴身与金色腰线
    el.append(box((0, 0, 5), (32, 12.5, 16), "walnut"))
    # 正面两块方形星辰花镶嵌板（整张贴图缩成 8×8），左右各一，中间留胡桃木
    for x in (5, 19):
        el.append(box((x, 2.5, 4.8), (x + 8, 10.5, 4.85), "inlay", faces={"south": None, "up": None, "down": None, "east": None, "west": None},
                      uv={"north": [0, 0, 16, 16]}))
        el.append(box((x - 0.4, 2.1, 4.85), (x + 8.4, 10.9, 4.95), "gold", faces={"south": None, "up": None, "down": None, "east": None, "west": None}))
    el.append(box((0, 12.5, 4.8), (32, 13, 16), "gold"))
    # 两侧扶手
    el.append(box((0, 13, 3.5), (2, 19, 16), "walnut"))
    el.append(box((30, 13, 3.5), (32, 19, 16), "walnut"))
    # 下层手键盘
    el.append(box((2, 13, 3), (30, 14, 9), "walnut"))
    el.append(box((2.5, 14, 3.2), (29.5, 14.8, 8), "white", faces={"up": "keys", "down": None}, uv={"up": [0, 0, 16, 16]}))
    # 上层手键盘
    el.append(box((2, 15, 6.5), (30, 16, 11), "walnut"))
    el.append(box((2.5, 16, 6.7), (29.5, 16.8, 10.5), "white", faces={"up": "keys", "down": None}, uv={"up": [0, 0, 16, 16]}))
    # 后面板：两侧各两列象牙色音栓拉钮
    el.append(box((2, 16, 11), (30, 24, 13), "walnut"))
    for x in (3.2, 5.4, 25.4, 27.6):
        for y in (17.2, 19.4, 21.6):
            el.append(box((x, y, 10.2), (x + 1.3, y + 1.3, 11), "lacquer", faces={"south": None}))
    # 谱架
    tilt = rotation("x", -15, (16, 17, 10.6))
    el.append(box((10, 17, 10.2), (22, 22.5, 10.8), "walnut", rot=tilt))
    # 顶上一小排装饰音管（中间最高）与金色檐口、星辰花镶嵌
    el.append(box((0, 24, 11), (32, 25, 16), "walnut"))
    el.append(box((0, 24.8, 10.8), (32, 25.2, 16), "gold"))
    heights = [3, 4, 5, 6, 6.8, 6, 5, 4, 3]
    for i, h in enumerate(heights):
        x = 3.2 + i * 2.95
        el.append(box((x, 25.2, 12.8), (x + 1.8, 25.2 + h, 14.6), "iron"))
        el.append(box((x + 0.3, 25.6, 12.7), (x + 1.5, 26.2, 12.8), "black", faces={"south": None, "up": None, "down": None}))
    el.append(box((0, 30.6, 12), (32, 32, 16), "walnut", faces={"north": "inlay"}))
    el.append(box((0, 30.4, 11.8), (32, 30.7, 16), "gold"))
    return el


def organ_wind_chest_elements():
    return [
        box((0, 0, 0), (16, 15, 16), "inlay", faces={"up": "walnut", "down": "walnut"}),
        # 顶面胡桃木，只在四边留一道细金边
        box((0, 15, 0), (16, 16, 16), "gold", faces={"up": "walnut", "down": None}),
    ]


def organ_pipe_elements(kind, shape):
    """kind: tin / wood；shape: single / bottom / middle / top。"""
    el = []
    has_foot = shape in ("single", "bottom")
    has_top = shape in ("single", "top")
    y0 = 3 if has_foot else 0
    if kind == "tin":
        # 八角形的管身（两块交叉）
        el.append(box((5.5, y0, 4.5), (10.5, 16, 11.5), "iron"))
        el.append(box((4.5, y0, 5.5), (11.5, 16, 10.5), "iron"))
        if has_foot:
            el.append(box((6.5, 0, 6.5), (9.5, 3, 9.5), "iron"))
            el.append(box((6, 4, 4.4), (10, 5.5, 4.5), "black", faces={"south": None}))
            el.append(box((6, 5.5, 4.3), (10, 6.2, 4.5), "gold", faces={"south": None}))
            el.append(box((4.4, 2.8, 5.4), (11.6, 3.3, 10.6), "gold"))
        if has_top:
            el.append(box((4.4, 15.2, 5.4), (11.6, 16, 10.6), "gold"))
            el.append(box((6, 16, 6), (10, 16.02, 10), "black", faces={"down": None}))
    else:
        el.append(box((4, y0, 4), (12, 16, 12), "walnut"))
        if has_foot:
            el.append(box((6, 0, 6), (10, 3, 10), "walnut"))
            el.append(box((5, 3.5, 3.9), (11, 5, 4), "black", faces={"south": None}))
            el.append(box((5, 5, 3.8), (11, 5.6, 4), "maple", faces={"south": None}))
        if has_top:
            el.append(box((3.8, 15, 3.8), (12.2, 16, 12.2), "maple"))
            el.append(box((5, 16, 5), (11, 16.02, 11), "black", faces={"down": None}))
    return el


def organ_trumpet_elements():
    el = [box((6, 0, 6), (10, 9, 10), "gold"), box((5.5, 0, 5.5), (10.5, 1, 10.5), "walnut")]
    # 向北横伸、越来越粗的喇叭
    for z0, z1, r in ((4, 10, 1.0), (2, 4, 1.6), (0.6, 2, 2.6)):
        el.append(box((8 - r, 9 - r, z0), (8 + r, 9 + r, z1), "gold"))
    el.append(box((4.5, 5.5, 0), (11.5, 12.5, 0.6), "gold"))
    el.append(box((6, 7, -0.02), (10, 11, 0), "black", faces={"south": None, "up": None, "down": None, "east": None, "west": None}))
    return el


def organ():
    blocks = ASSETS / "models/block"
    items = ASSETS / "models/item"
    con = organ_console_elements()
    tex = {k: T[k] for k in used(con)} | {"particle": T["walnut"]}
    write(blocks / "hexen_organ_console.json", {"ambientocclusion": False, "textures": tex, "elements": con})
    write(blocks / "hexen_organ_console_part.json", {"textures": {"particle": T["walnut"]}})
    write(items / "hexen_organ_console.json", {"ambientocclusion": False, "textures": tex,
        "elements": shifted(con, -8, -8, 0), "display": display(0.42, ground=0.2, rot_y=210)})
    chest = organ_wind_chest_elements()
    write(blocks / "organ_wind_chest.json", {"textures": {k: T[k] for k in used(chest)} | {"particle": T["walnut"]}, "elements": chest})
    for kind in ("tin", "wood"):
        for shape in ("single", "bottom", "middle", "top"):
            e = organ_pipe_elements(kind, shape)
            particle = T["iron"] if kind == "tin" else T["walnut"]
            write(blocks / f"organ_{kind}_pipe_{shape}.json", {"ambientocclusion": False,
                "textures": {k: T[k] for k in used(e)} | {"particle": particle}, "elements": e})
    trumpet = organ_trumpet_elements()
    write(blocks / "organ_trumpet_pipe.json", {"ambientocclusion": False,
        "textures": {k: T[k] for k in used(trumpet)} | {"particle": T["gold"]}, "elements": trumpet})
    for name, model in (("organ_wind_chest", "organ_wind_chest"), ("organ_tin_pipe", "organ_tin_pipe_single"),
                        ("organ_wood_pipe", "organ_wood_pipe_single"), ("organ_trumpet_pipe", "organ_trumpet_pipe")):
        write(items / f"{name}.json", {"parent": f"logos_dimension:block/{model}"})
    for name in ("hexen_organ_console", "organ_wind_chest", "organ_tin_pipe", "organ_wood_pipe", "organ_trumpet_pipe"):
        write(ASSETS / f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{name}"}})

    yrot = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for facing, y in yrot.items():
        for part in range(4):
            model = "hexen_organ_console" if part == 0 else "hexen_organ_console_part"
            v = {"model": f"logos_dimension:block/{model}"}
            if y and part == 0:
                v["y"] = y
            variants[f"facing={facing},part={part}"] = v
    write(ASSETS / "blockstates/hexen_organ_console.json", {"variants": variants})
    write(ASSETS / "blockstates/organ_wind_chest.json", {"variants": {"": {"model": "logos_dimension:block/organ_wind_chest"}}})
    for kind in ("tin", "wood"):
        variants = {}
        for facing, y in yrot.items():
            for shape in ("single", "bottom", "middle", "top"):
                v = {"model": f"logos_dimension:block/organ_{kind}_pipe_{shape}"}
                if y:
                    v["y"] = y
                variants[f"facing={facing},shape={shape}"] = v
        write(ASSETS / f"blockstates/organ_{kind}_pipe.json", {"variants": variants})
    variants = {}
    for facing, y in yrot.items():
        v = {"model": "logos_dimension:block/organ_trumpet_pipe"}
        if y:
            v["y"] = y
        variants[f"facing={facing}"] = v
    write(ASSETS / "blockstates/organ_trumpet_pipe.json", {"variants": variants})

    def self_drop(name):
        return {"type": "minecraft:block", "pools": [{"rolls": 1, "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [{"type": "minecraft:item", "name": f"logos_dimension:{name}"}]}]}
    write(DATA / "loot_table/blocks/hexen_organ_console.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "conditions": [
        {"condition": "minecraft:survives_explosion"},
        {"condition": "minecraft:block_state_property", "block": "logos_dimension:hexen_organ_console", "properties": {"part": "0"}}],
        "entries": [{"type": "minecraft:item", "name": "logos_dimension:hexen_organ_console"}]}]})
    for name in ("organ_wind_chest", "organ_tin_pipe", "organ_wood_pipe", "organ_trumpet_pipe"):
        write(DATA / f"loot_table/blocks/{name}.json", self_drop(name))

    def shaped(name, pattern, key, count):
        write(DATA / f"recipe/{name}.json", {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": pattern,
            "key": key, "result": {"id": f"logos_dimension:{name}", "count": count}})
    shaped("hexen_organ_console", ["QQQ", "GNG", "PPP"],
           {"Q": "minecraft:quartz_block", "G": "minecraft:gold_ingot", "N": "minecraft:note_block", "P": "minecraft:dark_oak_planks"}, 1)
    shaped("organ_wind_chest", ["PPP", "LGL", "PPP"],
           {"P": "minecraft:dark_oak_planks", "L": "minecraft:leather", "G": "minecraft:gold_nugget"}, 2)
    shaped("organ_tin_pipe", ["I", "I", "G"], {"I": "minecraft:iron_ingot", "G": "minecraft:gold_nugget"}, 3)
    shaped("organ_wood_pipe", ["P", "P", "G"], {"P": "minecraft:dark_oak_planks", "G": "minecraft:gold_nugget"}, 3)
    shaped("organ_trumpet_pipe", ["  G", "GG ", "G  "], {"G": "minecraft:gold_ingot"}, 2)


def main():
    models()
    blockstates()
    loot_and_recipes()
    art()
    organ()
    print("instrument art written")


if __name__ == "__main__":
    main()
