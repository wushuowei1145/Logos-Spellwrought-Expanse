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
import sys
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
    "varnish": "minecraft:block/jungle_planks",
    "banner": "logos_dimension:block/instrument/hexenwald_banner",
    # 中提琴、巴松管：深一些的红褐色清漆；低音提琴用深色胡桃木；定音鼓的铜鼓腔、演奏凳的红丝绒、鼓槌的毡头
    "amber": "minecraft:block/mangrove_planks",
    "copper": "minecraft:block/copper_block",
    "velvet": "minecraft:block/red_wool",
    "felt": "minecraft:block/white_wool",
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


def organ_console_wide_elements():
    """3 宽演奏台（FACING=north，x −16–32，主块在中间 x 0–16）：手键盘和脚键盘居中，左右两块放音栓拉钮的侧板，
    正面三块星辰花镶嵌板、顶上一排中间最高的装饰音管，整台左右对称。旧的 2 宽模型（organ_console_elements）留给旧存档。"""
    el = []
    # 脚键盘：胡桃木框 + 琴键贴图，居中
    el.append(box((-10, 0, 0), (26, 1.2, 5), "walnut"))
    el.append(box((-9, 1.2, 0.5), (25, 1.8, 4.5), "white", faces={"up": "keys", "down": None}, uv={"up": [0, 0, 16, 16]}))
    # 下层琴身与金色腰线
    el.append(box((-16, 0, 5), (32, 12.5, 16), "walnut"))
    for x in (-12, 4, 20):
        el.append(box((x, 2.5, 4.8), (x + 8, 10.5, 4.85), "inlay", faces={"south": None, "up": None, "down": None, "east": None, "west": None},
                      uv={"north": [0, 0, 16, 16]}))
        el.append(box((x - 0.4, 2.1, 4.85), (x + 8.4, 10.9, 4.95), "gold", faces={"south": None, "up": None, "down": None, "east": None, "west": None}))
    el.append(box((-16, 12.5, 4.8), (32, 13, 16), "gold"))
    # 左右音栓侧板，正面各 3 列 × 4 行象牙色拉钮
    el.append(box((-16, 13, 3.5), (-6, 24, 16), "walnut"))
    el.append(box((22, 13, 3.5), (32, 24, 16), "walnut"))
    for x0 in (-14.4, 23.6):
        for i in range(3):
            for j in range(4):
                x, y = x0 + i * 2.6, 14.8 + j * 2.3
                el.append(box((x, y, 2.7), (x + 1.3, y + 1.3, 3.5), "lacquer", faces={"south": None}))
    el.append(box((-16, 23.6, 3.3), (-6, 24, 16), "gold"))
    el.append(box((22, 23.6, 3.3), (32, 24, 16), "gold"))
    # 下层、上层手键盘
    el.append(box((-6, 13, 3), (22, 14, 9), "walnut"))
    el.append(box((-5.5, 14, 3.2), (21.5, 14.8, 8), "white", faces={"up": "keys", "down": None}, uv={"up": [0, 0, 16, 16]}))
    el.append(box((-6, 15, 6.5), (22, 16, 11), "walnut"))
    el.append(box((-5.5, 16, 6.7), (21.5, 16.8, 10.5), "white", faces={"up": "keys", "down": None}, uv={"up": [0, 0, 16, 16]}))
    # 后面板与谱架（居中）
    el.append(box((-6, 16, 11), (22, 24, 13), "walnut"))
    tilt = rotation("x", -15, (8, 17, 10.6))
    el.append(box((2, 17, 10.2), (14, 22.5, 10.8), "walnut", rot=tilt))
    # 檐口、装饰音管（中间最高）、顶上镶嵌带
    el.append(box((-16, 24, 11), (32, 25, 16), "walnut"))
    el.append(box((-16, 24.8, 10.8), (32, 25.2, 16), "gold"))
    heights = [3, 3.6, 4.2, 4.8, 5.4, 6.1, 6.8, 6.1, 5.4, 4.8, 4.2, 3.6, 3]
    start = 8 - (12 * 2.95 + 1.8) / 2
    for i, h in enumerate(heights):
        x = start + i * 2.95
        el.append(box((x, 25.2, 12.8), (x + 1.8, 25.2 + h, 14.6), "iron"))
        el.append(box((x + 0.3, 25.6, 12.7), (x + 1.5, 26.2, 12.8), "black", faces={"south": None, "up": None, "down": None}))
    el.append(box((-16, 30.6, 12), (32, 32, 16), "walnut", faces={"north": "inlay"}))
    el.append(box((-16, 30.4, 11.8), (32, 30.7, 16), "gold"))
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
    wide = organ_console_wide_elements()
    wide_tex = {k: T[k] for k in used(wide)} | {"particle": T["walnut"]}
    write(blocks / "hexen_organ_console_wide.json", {"ambientocclusion": False, "textures": wide_tex, "elements": wide})
    write(items / "hexen_organ_console.json", {"ambientocclusion": False, "textures": wide_tex,
        "elements": shifted(wide, 0, -8, 0), "display": display(0.3, ground=0.15, rot_y=210)})
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
        for part in range(7):
            for wide in ("true", "false"):
                main = "hexen_organ_console_wide" if wide == "true" else "hexen_organ_console"
                model = main if part == 0 else "hexen_organ_console_part"
                v = {"model": f"logos_dimension:block/{model}"}
                if y and part == 0:
                    v["y"] = y
                variants[f"facing={facing},part={part},wide={wide}"] = v
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


# —— 第二批：钟琴、钢片琴、管钟、大键琴 ——
# 都按 FACING=north 画（演奏者在北面，z 小的一侧）；钟琴、钢片琴一格，管钟两格高，大键琴一宽两深（z 0–32）。

def glockenspiel_elements():
    el = []
    # 支架：两条腿、横撑、脚
    for x in (2, 13):
        el.append(box((x, 0, 6), (x + 1, 10, 7), "walnut"))
        el.append(box((x - 0.5, 0, 4), (x + 1.5, 1, 9), "walnut"))
    el.append(box((3, 4, 6.2), (13, 5, 6.8), "walnut"))
    # 琴框与金边、两道毛毡
    el.append(box((1, 10, 3), (15, 11.5, 13), "walnut"))
    el.append(box((1, 11.5, 3), (15, 11.8, 3.4), "gold"))
    el.append(box((1, 11.5, 12.6), (15, 11.8, 13), "gold"))
    el.append(box((1.6, 11.5, 4.2), (14.4, 11.75, 4.8), "red", faces={"down": None}))
    el.append(box((1.6, 11.5, 9.6), (14.4, 11.75, 10.2), "red", faces={"down": None}))
    # 下排白键对应的钢条（左长右短），上排半音钢条略高、隔开两处空位
    for i in range(9):
        x = 2.0 + i * 1.37
        length = 6.6 - i * 0.3
        el.append(box((x, 11.75, 4.0), (x + 1.0, 12.25, 4.0 + length), "iron", faces={"down": None}))
    for i in range(8):
        if i in (2, 6):
            continue
        x = 2.0 + i * 1.37 + 0.68
        length = 3.2 - i * 0.15
        el.append(box((x, 12.0, 9.4), (x + 0.9, 12.5, 9.4 + length), "iron", faces={"down": None}))
    # 两支琴槌搁在一侧
    for dz in (0, 0.9):
        el.append(box((10.4, 12.3, 11.4 + dz), (14.4, 12.6, 11.7 + dz), "walnut"))
        el.append(box((14.2, 12.15, 11.25 + dz), (15.0, 12.85, 11.85 + dz), "lacquer"))
    return el


def celesta_elements():
    el = []
    # 腿、下柜（正面一块星辰花镶嵌板）、踏板
    for x in (1, 13.5):
        el.append(box((x, 0, 3), (x + 1.5, 9, 4.5), "walnut"))
    el.append(box((0.5, 2, 6), (15.5, 9, 15.5), "walnut"))
    # 正面一块方形镶嵌板（整张贴图缩成 6×6），后面衬一道金边
    el.append(box((5, 2.5, 5.85), (11, 8.5, 5.9), "inlay", faces={"south": None, "up": None, "down": None, "east": None, "west": None},
                  uv={"north": [0, 0, 16, 16]}))
    el.append(box((4.6, 2.1, 5.9), (11.4, 8.9, 6), "gold", faces={"south": None, "up": None, "down": None, "east": None, "west": None}))
    el.append(box((7.3, 0.4, 4), (8.7, 1, 6), "gold"))
    # 键盘
    el.append(box((1, 9, 2), (15, 10.5, 6.5), "walnut"))
    el.append(box((1.5, 10.5, 2.2), (14.5, 11.2, 5.8), "white", faces={"up": "keys", "down": None}, uv={"up": [0, 0, 16, 16]}))
    el.append(box((0.5, 9, 2), (1.5, 12, 6.5), "walnut"))
    el.append(box((14.5, 9, 2), (15.5, 12, 6.5), "walnut"))
    # 上柜：大理石纹面板、金色檐口、谱架
    el.append(box((0.5, 10.5, 6.5), (15.5, 15, 15.5), "walnut"))
    el.append(box((2, 11.4, 6.4), (14, 14.4, 6.5), "marble", faces={"south": None, "up": None, "down": None, "east": None, "west": None}))
    el.append(box((0.4, 14.8, 6.3), (15.6, 15.3, 15.6), "gold", faces={"up": "walnut"}))
    el.append(box((4.5, 15.3, 8.5), (11.5, 16, 9.1), "walnut"))
    return el


def tubular_bells_elements():
    el = []
    el.append(box((1, 0.6, 4), (15, 2, 12), "walnut"))
    for x, z in ((1.4, 4.4), (13.1, 4.4), (1.4, 10.1), (13.1, 10.1)):
        el.append(box((x, 0, z), (x + 1.5, 0.6, z + 1.5), "gold"))
    # 两根立柱、顶梁、顶上的星辰花牌饰
    el.append(box((1, 2, 7), (2.5, 29, 9), "walnut"))
    el.append(box((13.5, 2, 7), (15, 29, 9), "walnut"))
    el.append(box((0.5, 28.5, 6.5), (15.5, 30.5, 9.5), "walnut"))
    el.append(box((0.4, 30.5, 6.4), (15.6, 30.8, 9.6), "gold"))
    el.append(box((5, 30.8, 7.4), (11, 32, 8.6), "walnut", faces={"north": "inlay"}, uv={"north": [0, 4, 16, 12]}))
    # 八根铜管：左长右短，挂在顶梁下的小钩上
    # 圆一点的细管（两块交叉），管与管之间留出缝
    for i in range(8):
        x = 3.1 + i * 1.37
        length = 22 - i * 1.4
        top = 27.2
        el.append(box((x, top - length, 7.65), (x + 0.8, top, 8.35), "gold"))
        el.append(box((x + 0.1, top - length, 7.55), (x + 0.7, top, 8.45), "gold"))
        el.append(box((x - 0.1, top - 0.7, 7.45), (x + 0.9, top, 8.55), "gold"))
        el.append(box((x + 0.3, top, 7.9), (x + 0.5, 28.5, 8.1), "iron"))
    # 搁在底座上的皮槌
    el.append(box((3, 2, 10), (9, 2.4, 10.4), "walnut"))
    el.append(box((8.6, 1.8, 9.7), (10.2, 2.8, 10.7), "red"))
    return el


HARPSICHORD_SLICES = [(5, 14, 15), (14, 20, 13.5), (20, 25, 11.5), (25, 29, 9), (29, 31.5, 6)]
HARPSICHORD_LID = 25.0  # 以左侧直边（x=1）为铰链，右侧弯边抬起


def harpsichord_elements():
    el = []
    for x, z in ((1.5, 2), (13, 2), (2, 27), (5, 27)):
        el.append(box((x, 0, z), (x + 1.5, 9, z + 1.5), "walnut"))
    # 键床、两层手键盘、两侧扶手、谱板
    el.append(box((1, 9, 0), (15, 11, 5.2), "walnut"))
    el.append(box((1.5, 11, 0.4), (14.5, 11.7, 3.4), "white", faces={"up": "keys", "down": None}, uv={"up": [0, 0, 16, 16]}))
    el.append(box((1.5, 11, 3.4), (14.5, 12, 5), "walnut"))
    el.append(box((1.5, 12, 2.6), (14.5, 12.7, 5), "white", faces={"up": "keys", "down": None}, uv={"up": [0, 0, 16, 16]}))
    el.append(box((1, 11, 0), (1.5, 13.5, 5.2), "walnut"))
    el.append(box((14.5, 11, 0), (15, 13.5, 5.2), "walnut"))
    el.append(box((1.5, 12.7, 5), (14.5, 14, 5.4), "walnut"))
    el.append(box((1.5, 13.6, 4.9), (14.5, 13.8, 5.0), "gold", faces={"south": None, "down": None}))
    # 琴身：白漆、金色底边，顶面云杉音板；音板上一枚金色玫瑰花窗与几根琴弦
    for z0, z1, xr in HARPSICHORD_SLICES:
        el.append(box((1, 9, z0), (xr, 9.6, z1), "gold"))
        el.append(box((1, 9.6, z0), (xr, 14.4, z1), "lacquer", faces={"up": "spruce", "down": "walnut"}))
        el.append(box((xr - 0.6, 14.4, z0), (xr, 15, z1), "lacquer"))
    el.append(box((1, 14.4, 5), (1.6, 15, 31.5), "lacquer"))
    el.append(box((1, 14.4, 5), (15, 15, 5.6), "lacquer"))
    el.append(box((6.5, 14.4, 17), (8.5, 14.5, 19), "gold", faces={"down": None}))
    for x in (3, 5, 7.5, 10, 12.5):
        z_end = next((z1 for z0, z1, xr in reversed(HARPSICHORD_SLICES) if xr > x + 0.5), 14)
        el.append(box((x, 14.45, 6.5), (x + 0.25, 14.65, z_end - 0.8), "iron", faces={"down": None}))
    # 琴盖：外白漆、内侧星辰花镶嵌，撑开
    lid = rotation("z", HARPSICHORD_LID, (1, 15, 16))
    for z0, z1, xr in HARPSICHORD_SLICES:
        el.append(box((1, 15, z0), (xr, 15.5, z1), "lacquer", faces={"down": "inlay", "north": "gold", "south": "gold",
                                                                     "east": "gold", "west": "gold"}, rot=lid))
    stick_x = 12.0
    rise = (stick_x - 1) * math.tan(math.radians(HARPSICHORD_LID))
    el.append(box((stick_x, 15, 15.5), (stick_x + 0.5, 15 + rise - 0.3, 16), "walnut"))
    return el


def mallet():
    blocks = ASSETS / "models/block"
    items = ASSETS / "models/item"
    yrot = {"north": 0, "east": 90, "south": 180, "west": 270}
    specs = {
        "hexen_glockenspiel": (glockenspiel_elements(), "iron"),
        "hexen_celesta": (celesta_elements(), "walnut"),
        "hexen_tubular_bells": (tubular_bells_elements(), "gold"),
        "hexen_harpsichord": (harpsichord_elements(), "lacquer"),
    }
    for name, (el, particle) in specs.items():
        tex = {k: T[k] for k in used(el)} | {"particle": T[particle]}
        write(blocks / f"{name}.json", {"ambientocclusion": False, "textures": tex, "elements": el})
        write(ASSETS / f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{name}"}})
        if name == "hexen_tubular_bells":
            write(items / f"{name}.json", {"ambientocclusion": False, "textures": tex, "elements": shifted(el, 0, -8, 0),
                                           "display": display(0.5, ground=0.25, rot_y=200)})
            write(blocks / f"{name}_part.json", {"textures": {"particle": T[particle]}})
        elif name == "hexen_harpsichord":
            write(items / f"{name}.json", {"ambientocclusion": False, "textures": tex, "elements": shifted(el, 0, 0, -8),
                                           "display": display(0.45, ground=0.22)})
            write(blocks / f"{name}_part.json", {"textures": {"particle": T[particle]}})
        else:
            write(items / f"{name}.json", {"parent": f"logos_dimension:block/{name}"})

    def single(name):
        return {"variants": {f"facing={f}": ({"model": f"logos_dimension:block/{name}"} | ({"y": y} if y else {}))
                             for f, y in yrot.items()}}
    write(ASSETS / "blockstates/hexen_glockenspiel.json", single("hexen_glockenspiel"))
    write(ASSETS / "blockstates/hexen_celesta.json", single("hexen_celesta"))
    variants = {}
    for f, y in yrot.items():
        for half in ("lower", "upper"):
            m = "hexen_tubular_bells" if half == "lower" else "hexen_tubular_bells_part"
            variants[f"facing={f},half={half}"] = {"model": f"logos_dimension:block/{m}"} | ({"y": y} if y and half == "lower" else {})
    write(ASSETS / "blockstates/hexen_tubular_bells.json", {"variants": variants})
    variants = {}
    for f, y in yrot.items():
        for tail in ("false", "true"):
            m = "hexen_harpsichord" if tail == "false" else "hexen_harpsichord_part"
            variants[f"facing={f},tail={tail}"] = {"model": f"logos_dimension:block/{m}"} | ({"y": y} if y and tail == "false" else {})
    write(ASSETS / "blockstates/hexen_harpsichord.json", {"variants": variants})

    def loot(name, cond=None):
        conditions = [{"condition": "minecraft:survives_explosion"}]
        if cond:
            conditions.append({"condition": "minecraft:block_state_property", "block": f"logos_dimension:{name}", "properties": cond})
        return {"type": "minecraft:block", "pools": [{"rolls": 1, "conditions": conditions,
                "entries": [{"type": "minecraft:item", "name": f"logos_dimension:{name}"}]}]}
    write(DATA / "loot_table/blocks/hexen_glockenspiel.json", loot("hexen_glockenspiel"))
    write(DATA / "loot_table/blocks/hexen_celesta.json", loot("hexen_celesta"))
    write(DATA / "loot_table/blocks/hexen_tubular_bells.json", loot("hexen_tubular_bells", {"half": "lower"}))
    write(DATA / "loot_table/blocks/hexen_harpsichord.json", loot("hexen_harpsichord", {"tail": "false"}))

    def shaped(name, pattern, key):
        write(DATA / f"recipe/{name}.json", {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": pattern,
                                             "key": key, "result": {"id": f"logos_dimension:{name}", "count": 1}})
    shaped("hexen_glockenspiel", ["I I", "INI", "S S"], {"I": "minecraft:iron_ingot", "N": "minecraft:note_block", "S": "minecraft:stick"})
    shaped("hexen_celesta", ["III", "PNP", "P P"], {"I": "minecraft:iron_ingot", "N": "minecraft:note_block", "P": "minecraft:dark_oak_planks"})
    shaped("hexen_tubular_bells", ["CCC", "CNC", "P P"], {"C": "minecraft:copper_ingot", "N": "minecraft:note_block", "P": "minecraft:dark_oak_planks"})
    shaped("hexen_harpsichord", ["QQQ", "SNS", "P P"], {"Q": "minecraft:quartz_block", "S": "minecraft:string", "N": "minecraft:note_block",
                                                        "P": "minecraft:dark_oak_planks"})


# —— 手持乐器与乐谱架 ——
# 乐器按「竖放、正面朝北」画在 0–16 格里；物品有两版模型：平时拿着的、演奏时（使用中）的，后者把乐器摆到下巴或嘴边。
# 乐谱架的四种方块状态：空、靠着小提琴、谱台边横放长笛、谱台上搁小号（王室旗垂下来）。

def violin_elements(bow=True):
    el = []
    # 琴身：下宽、腰细、上宽，红褐色清漆（原版丛林木板），正面云杉
    el.append(box((4.8, 1, 7), (11.2, 5, 9), "varnish", faces={"north": "spruce"}))
    el.append(box((5.8, 5, 7.1), (10.2, 6.5, 8.9), "varnish", faces={"north": "spruce"}))
    el.append(box((5.2, 6.5, 7), (10.8, 9.5, 9), "varnish", faces={"north": "spruce"}))
    # f 孔、琴马、拉弦板、腮托
    for x in (6.2, 9.3):
        el.append(box((x, 3.2, 6.95), (x + 0.5, 6.2, 7), "black", faces={"south": None, "up": None, "down": None, "east": None, "west": None}))
    el.append(box((6.8, 3.8, 6.6), (9.2, 4.4, 7), "maple"))
    el.append(box((7.3, 1.3, 6.7), (8.7, 3.4, 7), "black"))
    el.append(box((4.9, 0.6, 7.2), (6.6, 1.6, 8.8), "black"))
    # 指板、琴颈、琴头与弦轴
    el.append(box((7.3, 6, 6.6), (8.7, 14, 7), "black"))
    el.append(box((7.4, 9.5, 7), (8.6, 14, 8.2), "varnish"))
    el.append(box((7.2, 14, 7.2), (8.8, 15.4, 8.4), "varnish"))
    el.append(box((7.5, 15.4, 7.4), (8.5, 16, 8.4), "varnish"))
    for y in (14.3, 14.9):
        el.append(box((6.7, y, 7.6), (9.3, y + 0.35, 7.95), "black"))
    # 四根弦
    for x in (7.5, 7.8, 8.1, 8.4):
        el.append(box((x, 3.4, 6.55), (x + 0.1, 14, 6.6), "iron", faces={"south": None, "up": None, "down": None}))
    if bow:
        el.append(box((2.6, 0.4, 9.4), (3.0, 15.6, 9.8), "varnish"))
        el.append(box((3.0, 1.2, 9.5), (3.15, 15.0, 9.7), "white"))
        el.append(box((2.4, 1.0, 9.3), (3.4, 2.6, 9.9), "black"))
    return el


def flute_elements():
    el = []
    el.append(box((0.6, 7.5, 7.5), (15.4, 8.5, 8.5), "iron"))
    el.append(box((0.3, 7.6, 7.6), (0.6, 8.4, 8.4), "iron"))
    # 吹孔板与按键
    el.append(box((2.2, 8.5, 7.6), (3.4, 8.7, 8.4), "iron"))
    el.append(box((2.6, 8.7, 7.85), (3.0, 8.75, 8.15), "black", faces={"down": None}))
    for i, x in enumerate((6.0, 7.1, 8.2, 9.6, 10.7, 11.8, 13.2)):
        el.append(box((x, 8.5, 7.7), (x + 0.7, 8.75, 8.3), "white" if i % 3 else "gold"))
    el.append(box((14.0, 7.4, 7.4), (15.4, 8.6, 8.6), "iron"))
    return el


def trumpet_elements(banner=True):
    el = []
    # 号嘴、导管
    el.append(box((0.4, 8.6, 7.6), (1.4, 9.4, 8.4), "gold"))
    el.append(box((1.4, 8.75, 7.75), (9.5, 9.25, 8.25), "gold"))
    # 三个活塞与活塞帽
    for x in (6.0, 7.1, 8.2):
        el.append(box((x, 6.6, 7.4), (x + 0.8, 10.4, 8.6), "gold"))
        el.append(box((x - 0.1, 10.4, 7.3), (x + 0.9, 10.9, 8.7), "lacquer"))
    # 主调音管的弯
    el.append(box((4.5, 6.6, 7.8), (6.0, 7.1, 8.2), "gold"))
    el.append(box((4.3, 6.6, 7.8), (4.8, 9.0, 8.2), "gold"))
    el.append(box((9.0, 6.6, 7.8), (11.0, 7.1, 8.2), "gold"))
    # 喇叭口：越来越粗
    for x0, x1, r in ((9.5, 11.5, 0.45), (11.5, 13.2, 0.8), (13.2, 14.6, 1.3), (14.6, 15.6, 2.1)):
        el.append(box((x0, 9 - r, 8 - r), (x1, 9 + r, 8 + r), "gold"))
    el.append(box((15.55, 7.4, 6.4), (15.6, 10.6, 9.6), "black", faces={"west": None, "north": None, "south": None, "up": None, "down": None}))
    if banner:
        # 王室旗：挂在导管下，正反两面同一张图（透明处镂空）
        el.append(box((3.0, 1.2, 7.98), (10.5, 8.7, 8.02), "banner", faces={"up": None, "down": None, "east": None, "west": None},
                      uv={"north": [0, 0, 16, 16], "south": [16, 0, 0, 16]}))
        for x in (3.2, 10.0):
            el.append(box((x, 8.7, 7.9), (x + 0.3, 8.8, 8.1), "gold"))
    return el


def music_stand_elements():
    el = []
    # 底座十字、金色小球脚、立杆与铜箍
    el.append(box((3, 0, 7.4), (13, 0.8, 8.6), "walnut"))
    el.append(box((7.4, 0, 3), (8.6, 0.8, 13), "walnut"))
    for x, z in ((2.6, 7.6), (12.6, 7.6), (7.6, 2.6), (7.6, 12.6)):
        el.append(box((x, 0, z), (x + 0.8, 0.6, z + 0.8), "gold"))
    el.append(box((7.4, 0.8, 7.4), (8.6, 11, 8.6), "walnut"))
    el.append(box((7.2, 6, 7.2), (8.8, 6.6, 8.8), "gold"))
    # 谱台：向后微倾的木板，下沿托条，正面一张乐谱（五线）
    tilt = rotation("x", 20, (8, 10.6, 7.4))
    el.append(box((2, 10.6, 7.1), (14, 16, 7.7), "walnut", rot=tilt))
    el.append(box((2.6, 11.0, 7.0), (13.4, 15.5, 7.1), "white", faces={"south": None, "up": None, "down": None, "east": None, "west": None}, rot=tilt))
    for y in (12.2, 12.6, 13.0, 13.4, 13.8):
        el.append(box((3.0, y, 6.98), (13.0, y + 0.08, 7.0), "black", faces={"south": None, "up": None, "down": None, "east": None, "west": None}, rot=tilt))
    el.append(box((2, 10.3, 6.2), (14, 10.9, 7.4), "walnut"))
    el.append(box((2, 10.85, 6.2), (14, 10.95, 6.4), "gold", faces={"down": None}))
    return el


def scaled(elements, k, anchor):
    """以 anchor 为中心整体缩放（摆在乐谱架旁边的乐器要比拿在手上的小一些）。"""
    ax, ay, az = anchor
    out = []
    for e in elements:
        e = json.loads(json.dumps(e))
        e["from"] = [round(ax + (e["from"][i] - anchor[i]) * k, 3) for i, ax in enumerate(anchor)]
        e["to"] = [round(ax + (e["to"][i] - anchor[i]) * k, 3) for i, ax in enumerate(anchor)]
        if "rotation" in e:
            o = e["rotation"]["origin"]
            e["rotation"]["origin"] = [round(anchor[i] + (o[i] - anchor[i]) * k, 3) for i in range(3)]
        out.append(e)
    return out


def transformed(elements, dx, dy, dz, rot=None):
    out = shifted(elements, dx, dy, dz)
    if rot:
        for e in out:
            assert "rotation" not in e
            e["rotation"] = rot
    return out


# —— 任意转动 ——
# 26.1 的模型元素除了单轴 axis/angle，还认 {"x", "y", "z"} 欧拉角（度）：先绕 x、再绕 y、再绕 z（原版 rotationZYX）。
# 下面用它把整件乐器当刚体转（大提琴后仰又左倾、斜靠在乐谱架上的巴松管），已带转动的元素与新的转动合成成一个。

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


def _element_matrix(e):
    r = e.get("rotation")
    if not r:
        return None
    if "axis" in r:
        return euler(**{r["axis"]: r["angle"]})
    return euler(r.get("x", 0), r.get("y", 0), r.get("z", 0))


def _decompose(m):
    y = -math.asin(max(-1.0, min(1.0, m[2][0])))
    x = math.atan2(m[2][1], m[2][2])
    z = math.atan2(m[1][0], m[0][0])
    return math.degrees(x), math.degrees(y), math.degrees(z)


def rigid(elements, m, origin):
    """整组元素绕 origin 转 m（3×3）。每个元素的长方体本身不动，只是平移后换成合成的欧拉角转动。"""
    out = []
    for e in elements:
        e = json.loads(json.dumps(e))
        m1 = _element_matrix(e)
        o1 = list(origin) if m1 is None else e["rotation"]["origin"]
        m1 = m1 or euler()
        moved = _apply(m, [o1[i] - origin[i] for i in range(3)])
        t = [origin[i] + moved[i] - o1[i] for i in range(3)]
        x, y, z = _decompose(_matmul(m, m1))
        e["from"] = [round(e["from"][i] + t[i], 3) for i in range(3)]
        e["to"] = [round(e["to"][i] + t[i], 3) for i in range(3)]
        e["rotation"] = {"origin": [round(o1[i] + t[i], 3) for i in range(3)], "x": round(x, 3), "y": round(y, 3), "z": round(z, 3)}
        out.append(e)
    return out


def segment(p0, p1, size, tex):
    """从 p0 到 p1 的一根细杆（方截面，边长 size）：沿 +X 画好再转过去。"""
    d = [b - a for a, b in zip(p0, p1)]
    length = math.sqrt(sum(v * v for v in d))
    ux, uy, uz = (v / length for v in d)
    h = size / 2
    e = box((p0[0], p0[1] - h, p0[2] - h), (p0[0] + length, p0[1] + h, p0[2] + h), tex)
    beta = -math.degrees(math.asin(max(-1.0, min(1.0, uz))))
    alpha = math.degrees(math.atan2(uy, ux))
    e["rotation"] = {"origin": [round(v, 3) for v in p0], "x": 0, "y": round(beta, 3), "z": round(alpha, 3)}
    return e


def retextured(elements, mapping):
    out = json.loads(json.dumps(elements))
    for e in out:
        for f in e["faces"].values():
            key = f["texture"][1:]
            f["texture"] = "#" + mapping.get(key, key)
    return out


def check_bounds(name, elements):
    """原版只收 −16–32 之内的元素坐标。"""
    for e in elements:
        for v in e["from"] + e["to"]:
            assert -16 <= v <= 32, f"{name}: element out of range {e['from']} {e['to']}"


def disc(cx, cz, r, y0, y1, tex, faces=None):
    """近似的圆柱：宽扁、方、窄长三块交叠（同一贴图、按坐标取 uv，重叠的面看不出接缝）。"""
    a, b, c = r, r * 0.62, r * 0.86
    return [box((cx - a, y0, cz - b), (cx + a, y1, cz + b), tex, faces=faces),
            box((cx - c, y0, cz - c), (cx + c, y1, cz + c), tex, faces=faces),
            box((cx - b, y0, cz - a), (cx + b, y1, cz + a), tex, faces=faces)]


# —— 中提琴、双簧管、巴松管（手持）——

def viola_elements(bow=True):
    """中提琴：小提琴的样子，清漆深一些（原版红树木板）；拿在手上时 HeldRig 把它放大一圈。"""
    return retextured(violin_elements(bow), {"varnish": "amber"})


def oboe_elements():
    """双簧管：乌木黑的管身、银键，竖着画：喇叭口在下，哨片尖在 (8, 15.6, 8)，对着嘴（HeldRig.oboe 的支点）。
    按键在 −Z 面（演奏时朝前上方），托指架和八度键在 +Z 面（朝演奏者），双手在 +Z 一侧 y 5、y 11 处扶着。"""
    el = []
    # 喇叭口：外沿一圈银边，往上收细
    el.append(box((7.0, 0.4, 7.0), (9.0, 1.4, 9.0), "black"))
    el.append(box((6.9, 0.4, 6.9), (9.1, 0.7, 9.1), "iron"))
    el.append(box((7.25, 1.4, 7.25), (8.75, 2.6, 8.75), "black"))
    # 下节、中间的银箍、上节（越往上越细）
    el.append(box((7.4, 2.6, 7.4), (8.6, 8.1, 8.6), "black"))
    el.append(box((7.3, 8.0, 7.3), (8.7, 8.5, 8.7), "iron"))
    el.append(box((7.45, 8.5, 7.45), (8.55, 11.4, 8.55), "black"))
    el.append(box((7.5, 11.4, 7.5), (8.5, 13.4, 8.5), "black"))
    el.append(box((7.4, 13.3, 7.4), (8.6, 13.7, 8.6), "iron"))
    # 哨片：缠红线的管座、扁的芦苇哨片
    el.append(box((7.65, 13.7, 7.65), (8.35, 14.5, 8.35), "red"))
    el.append(box((7.55, 14.5, 7.85), (8.45, 15.6, 8.15), "maple"))
    # 正面的指孔环、侧面的长连杆，背面的托指架与八度键
    front = {"south": None}
    for y in (3.3, 4.4, 5.5, 9.0, 10.0, 11.0):
        el.append(box((7.6, y, 7.2), (8.4, y + 0.5, 7.42), "iron", faces=front))
    el.append(box((8.55, 3.0, 7.7), (8.75, 12.8, 7.9), "iron"))
    el.append(box((7.7, 6.0, 8.58), (8.3, 6.6, 9.1), "iron"))
    el.append(box((7.8, 12.0, 8.53), (8.2, 13.0, 8.7), "iron"))
    return el


# 巴松管弯管尽头的哨片尖（HeldRig.bassoon 的支点）
BASSOON_REED = (12.33, 12.1, 12.5)


def bassoon_elements():
    """巴松管：红褐色枫木（原版红树木板）、银键，竖着画：管靴在下（y −5.4）、喇叭口在上（y 21.4）。
    长管（−Z 一侧）和翼管（+Z，演奏时朝着人）并排插在管靴上；翼管顶上的细弯管朝 +X、+Z 弯过去，把哨片送到嘴边。
    演奏时 +X 朝演奏者右手边：管靴在右胯前、喇叭口越过左肩。"""
    el = []
    # 管靴：两根管并排的粗段，底下一个金属 U 形盖
    el.append(box((6.6, -5.0, 6.2), (9.4, 1.4, 9.9), "amber"))
    el.append(box((6.8, -5.4, 6.4), (9.2, -5.0, 9.7), "iron"))
    el.append(box((6.5, 1.2, 6.1), (9.5, 1.8, 10.0), "iron"))
    # 长管、接口银箍、喇叭口节（顶上一圈象牙白）
    el.append(box((6.85, 1.8, 6.35), (9.15, 16.8, 8.15), "amber"))
    el.append(box((6.75, 16.6, 6.25), (9.25, 17.2, 8.25), "iron"))
    el.append(box((6.7, 17.2, 6.2), (9.3, 20.7, 8.3), "amber"))
    el.append(box((6.6, 20.6, 6.1), (9.4, 21.4, 8.4), "lacquer"))
    # 翼管（左手按孔的那一节）与顶上的银箍
    el.append(box((7.1, 1.8, 8.25), (8.9, 9.7, 9.75), "amber"))
    el.append(box((7.0, 9.6, 8.15), (9.0, 10.1, 9.85), "iron"))
    # 弯管：先竖着出来一小段，再弯向嘴边；尽头是哨片
    el.append(box((8.3, 10.1, 8.75), (8.7, 11.3, 9.15), "iron"))
    bend, base = (8.5, 11.2, 8.95), (11.75, 12.0, 11.95)
    el.append(segment(bend, base, 0.4, "iron"))
    el.append(segment(base, BASSOON_REED, 0.55, "maple"))
    # 管靴上的托手架（右手）、翼管与管靴上的指孔环（朝演奏者）、长管侧面的长连杆
    el.append(box((7.5, -0.8, 9.9), (8.5, 0.4, 10.7), "black"))
    back = {"north": None}
    for y in (3.6, 5.2, 6.8):
        el.append(box((7.6, y, 9.73), (8.4, y + 0.5, 9.95), "iron", faces=back))
    for y in (-3.4, -2.0):
        el.append(box((7.6, y, 9.88), (8.4, y + 0.5, 10.1), "iron", faces=back))
    el.append(box((9.12, 2.2, 6.9), (9.32, 15.8, 7.1), "iron"))
    el.append(box((6.68, 3.0, 8.9), (6.88, 9.2, 9.1), "iron"))
    return el


# —— 大提琴、低音提琴、定音鼓（方块）——

# 坐着（站着）拉的琴：琴按竖直、正面朝北画在自己的坐标里（琴脚尖在原点，y 沿琴身向上，x 向东，z 朝琴背），
# 先后仰 lean°（琴头朝演奏者）、再向左倾 tilt°（琴头朝西），琴脚尖放在 endpin（方块像素，朝北放置）。
# 和客户端 HeldRig 的 CELLO / BASS 是同一组数：contact 是弓毛压在弦上的点，左手沿 neck_z 那条线（指板与琴颈中间）
# 在 neck 范围里找直臂够得着的位置。演奏者的位置见 SeatedInstrumentBlock.feet。
SEATED = {
    "hexen_cello": {"endpin": (8.8, 0, 2.1), "lean": 25, "tilt": 16, "contact": (0, 11.5, -2.5), "neck_z": -1.0,
                    "neck": (12.5, 21.5), "feet": (8, -2.25, 12.5), "bow": 0.85},
    "hexen_double_bass": {"endpin": (7, 0, 3.2), "lean": 14, "tilt": 10, "contact": (0, 13.6, -3.4), "neck_z": -1.5,
                          "neck": (15.0, 26.5), "feet": (8, 0, 11.5), "bow": 0.8},
}


def cello_elements():
    """大提琴（琴的局部坐标，见 SEATED）：尾柱 y 0–5，琴身 5–17.5，琴颈、弦轴箱、琴头到 25.6。"""
    el = []
    el.append(box((-0.2, 0, -0.2), (0.2, 4.6, 0.2), "iron"))
    el.append(box((-0.5, 4.4, -0.5), (0.5, 5.0, 0.5), "black"))
    # 琴身：下宽、腰细、上宽；面板云杉，背板、侧板清漆
    for y0, y1, hw in ((5.0, 5.8, 2.6), (5.8, 9.2, 3.6), (9.2, 10.0, 3.0), (10.0, 12.2, 2.2),
                       (12.2, 13.0, 2.6), (13.0, 16.4, 2.9), (16.4, 17.5, 2.0)):
        el.append(box((-hw, y0, -1.2), (hw, y1, 1.2), "varnish", faces={"north": "spruce"}))
    only_front = {"south": None, "up": None, "down": None, "east": None, "west": None}
    for x in (-1.5, 1.1):
        el.append(box((x, 9.6, -1.25), (x + 0.4, 11.8, -1.2), "black", faces=only_front))
    # 拉弦板、琴马
    el.append(box((-0.8, 5.6, -1.6), (0.8, 9.8, -1.2), "black"))
    el.append(box((-1.2, 10.4, -2.35), (1.2, 10.9, -1.2), "maple"))
    # 指板（悬在琴身上方）、琴颈、弦轴箱和弦轴、琴头（侧面的涡卷）
    el.append(box((-0.75, 12.0, -2.2), (0.75, 21.8, -1.4), "black"))
    el.append(box((-0.6, 17.5, -1.4), (0.6, 21.8, 0.2), "varnish"))
    el.append(box((-0.6, 21.8, -1.2), (0.6, 24.2, 0.4), "varnish"))
    for y in (22.3, 23.3):
        el.append(box((-1.3, y, -0.6), (1.3, y + 0.4, -0.2), "black"))
    el.append(box((-0.55, 24.2, -1.6), (0.55, 25.6, 0.6), "varnish"))
    el.append(box((-0.6, 24.6, -1.2), (0.6, 25.2, 0.2), "black", faces={"north": None, "south": None, "up": None, "down": None}))
    # 四根弦：越过琴马到琴枕
    strings = {"south": None, "up": None, "down": None}
    for x in (-0.45, -0.15, 0.15, 0.45):
        el.append(box((x - 0.05, 9.8, -2.45), (x + 0.05, 21.8, -2.35), "iron", faces=strings))
    return el


def bass_elements():
    """低音提琴（琴的局部坐标，见 SEATED）：尾柱 y 0–3，斜肩的琴身 3–20.5，琴颈到 26.9，弦轴箱的铜弦钮、琴头到 30.4。"""
    el = []
    el.append(box((-0.25, 0, -0.25), (0.25, 3.2, 0.25), "iron"))
    el.append(box((-0.6, 2.8, -0.6), (0.6, 3.4, 0.6), "black"))
    for y0, y1, hw in ((3.0, 3.8, 4.0), (3.8, 9.0, 5.2), (9.0, 10.0, 4.5), (10.0, 13.0, 3.4), (13.0, 14.0, 3.9),
                       (14.0, 17.0, 4.2), (17.0, 18.6, 3.2), (18.6, 20.5, 1.9)):
        el.append(box((-hw, y0, -1.6), (hw, y1, 1.6), "walnut", faces={"north": "spruce"}))
    only_front = {"south": None, "up": None, "down": None, "east": None, "west": None}
    for x in (-2.3, 1.8):
        el.append(box((x, 11.0, -1.65), (x + 0.5, 14.2, -1.6), "black", faces=only_front))
    el.append(box((-1.1, 4.6, -2.0), (1.1, 11.0, -1.6), "black"))
    el.append(box((-1.6, 11.6, -3.25), (1.6, 12.2, -1.6), "maple"))
    el.append(box((-0.9, 15.0, -3.0), (0.9, 26.9, -2.0), "black"))
    el.append(box((-0.75, 20.5, -2.0), (0.75, 26.9, 0.0), "walnut"))
    el.append(box((-0.75, 26.9, -1.8), (0.75, 29.0, 0.2), "walnut"))
    for y in (27.3, 28.2):
        el.append(box((-1.7, y, -0.8), (1.7, y + 0.4, -0.4), "gold"))
    el.append(box((-0.7, 29.0, -2.0), (0.7, 30.4, 0.4), "walnut"))
    el.append(box((-0.75, 29.4, -1.5), (0.75, 30.0, 0.0), "black", faces={"north": None, "south": None, "up": None, "down": None}))
    strings = {"south": None, "up": None, "down": None}
    for x in (-0.6, -0.2, 0.2, 0.6):
        el.append(box((x - 0.06, 11.0, -3.35), (x + 0.06, 26.9, -3.25), "iron", faces=strings))
    return el


def seated_instrument(name, local):
    s = SEATED[name]
    return rigid(shifted(local, *s["endpin"]), euler(s["lean"], 0, s["tilt"]), s["endpin"])


def cello_block_elements():
    el = seated_instrument("hexen_cello", cello_elements())
    # 琴后的演奏凳：胡桃木凳腿、红丝绒坐垫，坐面高 7.1（坐着的大腿正好压在上面）
    for x, z in ((4.6, 10.2), (10.4, 10.2), (4.6, 14.6), (10.4, 14.6)):
        el.append(box((x, 0, z), (x + 1, 5.4, z + 1), "walnut"))
    el.append(box((4.6, 2.0, 10.7), (11.4, 2.5, 11.1), "walnut"))
    el.append(box((4.6, 2.0, 14.9), (11.4, 2.5, 15.3), "walnut"))
    el.append(box((4.2, 5.4, 9.8), (11.8, 6.2, 16.0), "walnut"))
    el.append(box((4.4, 6.2, 10.0), (11.6, 7.1, 15.8), "velvet"))
    return el


def bass_block_elements():
    s = SEATED["hexen_double_bass"]
    el = seated_instrument("hexen_double_bass", bass_elements())
    # 尾柱下的防滑垫
    ex, _, ez = s["endpin"]
    el.append(box((ex - 0.9, 0, ez - 0.9), (ex + 0.9, 0.25, ez + 0.9), "black"))
    return el


def timpani_elements():
    """定音鼓：铜鼓腔（近似半球）、白鼓皮、铁鼓圈和六根调音拉杆，十字底座带小轮；朝演奏者（南）的一侧是踏板，鼓皮上搁两支毡头槌。"""
    el = []
    for x0, z0, x1, z1 in ((2.6, 7.4, 13.4, 8.6), (7.4, 2.6, 8.6, 13.4)):
        el.append(box((x0, 0.4, z0), (x1, 1.0, z1), "iron"))
    for x, z in ((2.4, 7.5), (12.6, 7.5), (7.5, 2.4), (7.5, 12.6)):
        el.append(box((x, 0, z), (x + 1, 0.8, z + 1), "black"))
    el.append(box((7.3, 1.0, 7.3), (8.7, 3.2, 8.7), "iron"))
    for y0, y1, r in ((3.0, 4.2, 3.2), (4.2, 5.6, 4.6), (5.6, 7.4, 5.6), (7.4, 9.6, 6.1), (9.6, 12.3, 6.3)):
        el += disc(8, 8, r, y0, y1, "copper")
    el += disc(8, 8, 6.6, 12.2, 13.0, "iron")
    el += disc(8, 8, 6.3, 13.0, 13.2, "lacquer", faces={"down": None})
    for i in range(6):
        a = math.pi / 6 + i * math.pi / 3
        x, z = 8 + 6.8 * math.cos(a), 8 + 6.8 * math.sin(a)
        el.append(box((x - 0.2, 7.2, z - 0.2), (x + 0.2, 12.6, z + 0.2), "iron"))
        el.append(box((x - 0.4, 12.6, z - 0.4), (x + 0.4, 13.5, z + 0.4), "iron"))
    # 踏板：从中柱伸向演奏者
    el.append(box((7.5, 0.6, 8.6), (8.5, 1.2, 12.6), "iron"))
    el.append(box((6.5, 0.4, 12.4), (9.5, 1.3, 15.6), "iron"))
    el.append(box((7.0, 1.3, 12.8), (9.0, 1.5, 15.2), "black"))
    # 两支鼓槌：胡桃木槌杆、白毡槌头，斜搁在鼓皮上
    for (x0, z0), (x1, z1) in (((4.2, 9.6), (10.0, 6.4)), ((5.0, 11.0), (10.8, 8.4))):
        el.append(segment((x0, 13.45, z0), (x1, 13.45, z1), 0.3, "walnut"))
        el.append(box((x1 - 0.5, 13.2, z1 - 0.5), (x1 + 0.5, 14.1, z1 + 0.5), "felt"))
    return el


def handheld():
    blocks = ASSETS / "models/block"
    items = ASSETS / "models/item"
    inst = {
        "hexen_violin": violin_elements(),
        "hexen_flute": flute_elements(),
        "hexen_trumpet": trumpet_elements(),
        "hexen_viola": viola_elements(),
        "hexen_oboe": oboe_elements(),
        "hexen_bassoon": bassoon_elements(),
    }
    particle = {"hexen_violin": "varnish", "hexen_flute": "iron", "hexen_trumpet": "gold",
                "hexen_viola": "amber", "hexen_oboe": "black", "hexen_bassoon": "amber"}
    # 平时拿着：竖着握在手里。演奏时第三人称的乐器由玩家层画在下巴、嘴边、身前（HeldRig，用下面的 *_held / 弓模型），
    # 手上的 _playing 只在第一人称看得到
    normal = {
        "hexen_violin": {"thirdperson_righthand": {"rotation": [0, 90, -20], "translation": [0, 1, 1], "scale": [0.55] * 3}},
        "hexen_flute": {"thirdperson_righthand": {"rotation": [0, 90, 55], "translation": [0, 2, 0.5], "scale": [0.6] * 3}},
        "hexen_trumpet": {"thirdperson_righthand": {"rotation": [0, 90, 0], "translation": [0, 0, 1], "scale": [0.55] * 3}},
        "hexen_viola": {"thirdperson_righthand": {"rotation": [0, 90, -20], "translation": [0, 1.5, 1], "scale": [0.62] * 3}},
        "hexen_oboe": {"thirdperson_righthand": {"rotation": [0, 90, -15], "translation": [0, 1.5, 1], "scale": [0.55] * 3}},
        "hexen_bassoon": {"thirdperson_righthand": {"rotation": [0, 90, -10], "translation": [0, 2.5, 1], "scale": [0.42] * 3}},
    }
    playing = {
        # 第一人称抬高一些，露在屏幕底部的演奏条上面
        "hexen_violin": {"firstperson_righthand": {"rotation": [109, -3.5, 143], "translation": [-8, 5.5, -2], "scale": [0.6] * 3}},
        "hexen_flute": {"firstperson_righthand": {"rotation": [0, 170, 0], "translation": [-4, 6, -2], "scale": [0.6] * 3}},
        "hexen_trumpet": {"firstperson_righthand": {"rotation": [-8, 105, 0], "translation": [-7, 6, -2], "scale": [0.6] * 3}},
        "hexen_viola": {"firstperson_righthand": {"rotation": [109, -3.5, 143], "translation": [-8, 5.5, -2], "scale": [0.66] * 3}},
        # 双簧管：哨片朝自己、管身往前下方伸出去（比真实的角度平一些，好露在演奏条上面），按键朝上
        "hexen_oboe": {"firstperson_righthand": {"rotation": [70, 0, 0], "translation": [-7, 6, -2], "scale": [0.6] * 3}},
        # 巴松管：同第三人称的摆法（管身离竖直 30°、往左上方伸），哨片在嘴边、弯管朝自己
        "hexen_bassoon": {"firstperson_righthand": {"rotation": [0, 0, 30], "translation": [-10, 2.5, 3.5], "scale": [0.5] * 3}},
    }
    # 长的管乐器在物品栏里斜着放
    gui = {"hexen_oboe": {"rotation": [20, 200, -40], "translation": [0, 0, 0], "scale": [0.9] * 3},
           "hexen_bassoon": {"rotation": [20, 200, -40], "translation": [0, 0, 0], "scale": [0.56] * 3}}
    fixed = {"hexen_bassoon": {"rotation": [0, 180, -40], "translation": [0, 0, 0], "scale": [0.56] * 3}}
    for name, el in inst.items():
        tex = {k: T[k] for k in used(el)} | {"particle": T[particle[name]]}
        base = {"gui": gui.get(name, {"rotation": [20, 200, 0], "translation": [0, 0, 0], "scale": [0.9] * 3}),
                "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.45] * 3},
                "fixed": fixed.get(name, {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.9] * 3}),
                "firstperson_righthand": {"rotation": [0, 100, 15], "translation": [1, 2, 0], "scale": [0.5] * 3}}
        for variant, extra in (("", normal[name]), ("_playing", playing[name])):
            if variant == "_playing" and name in ("hexen_violin", "hexen_viola"):
                # 第一人称演奏时弓横搭在琴马和指板之间的弦上，弓根朝惯用手
                el = violin_elements(bow=False) + [
                    box((-1.5, 4.6, 5.9), (13.7, 5.0, 6.3), "varnish"),
                    box((-0.7, 4.7, 6.3), (13.0, 4.9, 6.5), "white"),
                    box((-1.7, 4.4, 5.8), (-0.1, 5.2, 6.5), "black")]
                if name == "hexen_viola":
                    el = retextured(el, {"varnish": "amber"})
                tex = {k: T[k] for k in used(el)} | {"particle": T[particle[name]]}
            disp = dict(base) | extra
            if "thirdperson_righthand" in disp:
                r = disp["thirdperson_righthand"]
                disp["thirdperson_lefthand"] = {"rotation": [r["rotation"][0], -r["rotation"][1], -r["rotation"][2]],
                                                "translation": [-r["translation"][0], r["translation"][1], r["translation"][2]], "scale": r["scale"]}
            if "firstperson_righthand" in disp:
                r = disp["firstperson_righthand"]
                disp["firstperson_lefthand"] = {"rotation": [r["rotation"][0], -r["rotation"][1], -r["rotation"][2]],
                                                "translation": [-r["translation"][0], r["translation"][1], r["translation"][2]], "scale": r["scale"]}
            check_bounds(name, el)
            model = {"textures": tex, "elements": el, "display": disp}
            if name == "hexen_trumpet":
                model["render_type"] = "minecraft:cutout"
            write(items / f"{name}{variant}.json", model)
        write(ASSETS / f"items/{name}.json", {"model": {"type": "minecraft:condition", "property": "minecraft:using_item",
            "on_true": {"type": "minecraft:model", "model": f"logos_dimension:item/{name}_playing"},
            "on_false": {"type": "minecraft:model", "model": f"logos_dimension:item/{name}"}}})
    # 玩家层画的演奏中的乐器：弓弦乐器不带弓，弓单独一件（弓杆居中，弓毛在 +X 一侧；中提琴、大提琴、低音提琴共用这把弓）。
    # 物品定义放在 items/held/ 里：只供渲染用，不是物品
    held = {"hexen_violin": violin_elements(bow=False), "hexen_flute": flute_elements(), "hexen_trumpet": trumpet_elements(),
            "hexen_viola": viola_elements(bow=False), "hexen_oboe": oboe_elements(), "hexen_bassoon": bassoon_elements(),
            "hexen_violin_bow": shifted([e for e in violin_elements() if e["from"][0] < 4][:3], 5.2, 0, -1.6)}
    for name, el in held.items():
        model_name = name if name.endswith("_bow") else f"{name}_held"
        tex = {k: T[k] for k in used(el)} | {"particle": T[particle.get(name, "varnish")]}
        model = {"textures": tex, "elements": el}
        if name == "hexen_trumpet":
            model["render_type"] = "minecraft:cutout"
        write(items / f"{model_name}.json", model)
        short = model_name.removeprefix("hexen_").removesuffix("_held")
        write(ASSETS / f"items/held/{short}.json", {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{model_name}"}})

    # 乐谱架：空、靠着小提琴（琴弓靠在另一侧）、谱台边横放长笛、谱台上搁小号；
    # 中提琴同小提琴的摆法、大一圈；双簧管立在谱架前的小木座上、靠着托条；巴松管立在左前方、斜靠着谱台
    stand = music_stand_elements()
    lean = rotation("x", 14, (11.5, 0, 4.6))
    lean_bow = rotation("z", -12, (3.4, 0, 4.0))
    variants = {
        "none": stand,
        "violin": stand + transformed(scaled(violin_elements(bow=False), 0.72, (8, 0, 8)), 3.6, 0.2, -3.8, lean)
                  + transformed(scaled([e for e in violin_elements() if e["from"][0] < 4][:3], 0.8, (3, 0, 9.6)), 0.6, 0.2, -5.6, lean_bow),
        "flute": stand + shifted(flute_elements(), 0, 3.35, -1.3),
        "trumpet": stand + shifted(trumpet_elements(), 0, 2.3, -1.9),
        "viola": stand + transformed(scaled(viola_elements(bow=False), 0.8, (8, 0, 8)), 3.4, 0.2, -3.9, rotation("x", 12, (11.4, 0, 4.4)))
                 + transformed(scaled([e for e in viola_elements() if e["from"][0] < 4][:3], 0.85, (3, 0, 9.6)), 0.6, 0.2, -5.6, lean_bow),
        "oboe": stand + oboe_on_stand(),
        "bassoon": stand + bassoon_on_stand(),
    }
    for holds, el in variants.items():
        check_bounds(f"music stand {holds}", el)
        tex = {k: T[k] for k in used(el)} | {"particle": T["walnut"]}
        model = {"ambientocclusion": False, "textures": tex, "elements": el}
        if holds == "trumpet":
            model["render_type"] = "minecraft:cutout"
        write(blocks / f"hexen_music_stand_{holds}.json", model)
    yrot = {"north": 0, "east": 90, "south": 180, "west": 270}
    bs = {}
    for f, y in yrot.items():
        for holds in variants:
            bs[f"facing={f},holds={holds}"] = {"model": f"logos_dimension:block/hexen_music_stand_{holds}"} | ({"y": y} if y else {})
    write(ASSETS / "blockstates/hexen_music_stand.json", {"variants": bs})
    write(items / "hexen_music_stand.json", {"parent": "logos_dimension:block/hexen_music_stand_none"})
    write(ASSETS / "items/hexen_music_stand.json", {"model": {"type": "minecraft:model", "model": "logos_dimension:item/hexen_music_stand"}})
    write(DATA / "loot_table/blocks/hexen_music_stand.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
        "conditions": [{"condition": "minecraft:survives_explosion"}],
        "entries": [{"type": "minecraft:item", "name": "logos_dimension:hexen_music_stand"}]}]})

    shaped("hexen_violin", [" PS", "PSP", "SP "], {"P": "minecraft:jungle_planks", "S": "minecraft:string"})
    shaped("hexen_flute", ["  I", " I ", "I  "], {"I": "minecraft:iron_ingot"})
    shaped("hexen_trumpet", ["  G", "GGG", "WG "], {"G": "minecraft:gold_ingot", "W": "minecraft:blue_wool"})
    shaped("hexen_music_stand", ["PPP", " S ", "SSS"], {"P": "minecraft:dark_oak_planks", "S": "minecraft:stick"})
    shaped("hexen_viola", [" PS", "PSP", "SP "], {"P": "minecraft:mangrove_planks", "S": "minecraft:string"})
    shaped("hexen_oboe", ["  R", " P ", "P  "], {"R": "minecraft:sugar_cane", "P": "minecraft:dark_oak_planks"})
    shaped("hexen_bassoon", ["  R", "PIP", "PP "], {"R": "minecraft:sugar_cane", "P": "minecraft:mangrove_planks", "I": "minecraft:iron_ingot"})

    png(ASSETS / "textures/block/instrument/hexenwald_banner.png", hexenwald_banner("night"))


def shaped(name, pattern, key):
    write(DATA / f"recipe/{name}.json", {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": pattern,
                                         "key": key, "result": {"id": f"logos_dimension:{name}", "count": 1}})


def oboe_on_stand():
    # 喇叭口朝下立在小木座上（缩到 0.8），微微后仰靠着谱台托条
    el = shifted(scaled(oboe_elements(), 0.8, (8, 0.4, 8)), 4.2, 0.1, -3.6)
    el = rigid(el, euler(9, 0, 0), (12.2, 0.5, 4.4))
    return el + disc(12.2, 4.4, 1.3, 0, 0.5, "walnut")


def bassoon_on_stand():
    # 管靴着地立在谱架左前方（缩到 0.55），后仰并朝谱台一侧斜，喇叭口那头靠在谱台上
    el = shifted(scaled(bassoon_elements(), 0.55, (8, -5.4, 8)), -6.8, 5.7, -3.4)
    return rigid(el, euler(14, 0, -4), (1.2, 0.3, 4.6))


def loot(name, cond=None):
    conditions = [{"condition": "minecraft:survives_explosion"}]
    if cond:
        conditions.append({"condition": "minecraft:block_state_property", "block": f"logos_dimension:{name}", "properties": cond})
    return {"type": "minecraft:block", "pools": [{"rolls": 1, "conditions": conditions,
            "entries": [{"type": "minecraft:item", "name": f"logos_dimension:{name}"}]}]}


def orchestra():
    """大提琴、低音提琴（两格高：下半格的模型画整件琴和凳子，上半格只有粒子贴图）与定音鼓。"""
    blocks = ASSETS / "models/block"
    items = ASSETS / "models/item"
    yrot = {"north": 0, "east": 90, "south": 180, "west": 270}
    specs = {
        "hexen_cello": (cello_block_elements(), "varnish"),
        "hexen_double_bass": (bass_block_elements(), "walnut"),
        "hexen_timpani": (timpani_elements(), "copper"),
    }
    for name, (el, particle) in specs.items():
        check_bounds(name, el)
        tex = {k: T[k] for k in used(el)} | {"particle": T[particle]}
        write(blocks / f"{name}.json", {"ambientocclusion": False, "textures": tex, "elements": el})
        write(ASSETS / f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{name}"}})
        if name == "hexen_timpani":
            write(items / f"{name}.json", {"parent": f"logos_dimension:block/{name}", "display": display(0.62, ground=0.3)})
            write(ASSETS / f"blockstates/{name}.json", {"variants": {
                f"facing={f}": ({"model": f"logos_dimension:block/{name}"} | ({"y": y} if y else {})) for f, y in yrot.items()}})
            write(DATA / f"loot_table/blocks/{name}.json", loot(name))
            continue
        item_el = shifted(el, 0, -5, 0)
        check_bounds(name + " item", item_el)
        write(items / f"{name}.json", {"ambientocclusion": False, "textures": tex, "elements": item_el,
                                       "display": display(0.5, ground=0.25, rot_y=200)})
        write(blocks / f"{name}_part.json", {"textures": {"particle": T[particle]}})
        variants = {}
        for f, y in yrot.items():
            for half in ("lower", "upper"):
                m = name if half == "lower" else f"{name}_part"
                variants[f"facing={f},half={half}"] = {"model": f"logos_dimension:block/{m}"} | ({"y": y} if y and half == "lower" else {})
        write(ASSETS / f"blockstates/{name}.json", {"variants": variants})
        write(DATA / f"loot_table/blocks/{name}.json", loot(name, {"half": "lower"}))
    shaped("hexen_cello", [" PS", "PNP", "SP "], {"P": "minecraft:jungle_planks", "S": "minecraft:string", "N": "minecraft:note_block"})
    shaped("hexen_double_bass", [" PS", "PNP", "PPS"], {"P": "minecraft:dark_oak_planks", "S": "minecraft:string", "N": "minecraft:note_block"})
    shaped("hexen_timpani", ["LLL", "CNC", "C C"], {"L": "minecraft:leather", "C": "minecraft:copper_ingot", "N": "minecraft:note_block"})
    seated_report()


def seated_report():
    """打印坐着（站着）拉的琴上几个关键点在玩家模型里的位置，核对 HeldRig 的布局（直臂够不够得着、琴颈是否让开头）。"""
    ms = 0.9375
    for name, s in SEATED.items():
        m = euler(s["lean"], 0, s["tilt"])
        fx, fy, fz = s["feet"]

        def model(local):
            w = [s["endpin"][i] + v for i, v in enumerate(_apply(m, local))]
            return [-(w[0] - fx) / ms, 24 - (w[1] - fy) / ms, (w[2] - fz) / ms]
        c = model(list(s["contact"]))
        # 弓根该在的位置（弓搭在 contact、弓杆沿琴的横向）离右肩多远：直臂够得着大约 9
        right = _apply(m, [1, 0, 0])
        frog = [c[0] - right[0] * 8.7 * s["bow"] / ms, c[1] - right[1] * 8.7 * s["bow"] / ms, c[2] + right[2] * 8.7 * s["bow"] / ms]
        dist = math.dist(frog, (-6, 2, 0))
        lo, hi = s["neck"]
        y = hi
        while y > lo and math.dist(model([0, y, s["neck_z"]]), (6, 2, 0)) < 9:
            y -= 0.25
        print(f"{name}: bow contact {[round(v, 2) for v in c]} (frog {dist:.1f} px from the right shoulder), "
              f"left hand at neck y {y:.2f} {[round(v, 2) for v in model([0, y, s['neck_z']])]}")


def concert_hall_icon(size=32, scale=4):
    """「音乐厅音源」资源包图标：深红丝绒幕布、金色帷幔、聚光灯下一把大提琴；按 32×32 像素画，再放大 4 倍。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    px = img.load()
    folds = [0.6, 0.78, 0.95, 1.0, 0.86, 0.7]
    for y in range(size):
        for x in range(size):
            k = folds[x % 6]
            px[x, y] = (int(132 * k), int(22 * k), int(38 * k), 255)
    for y in range(26, size):
        for x in range(size):
            seam = (x + (y - 26) * 5) % 9 == 0
            px[x, y] = ((66, 40, 25) if seam else (96, 60, 37)) + (255,)
    # 聚光灯：一束暖光从帷幔下打到地板上
    for y in range(5, size):
        half = 3.5 + (y - 5) * 0.32
        for x in range(size):
            d = abs(x + 0.5 - 16) / half
            if d < 1:
                a = 0.5 * (1 - d * d) * (0.75 if y >= 26 else 1)
                r, g, b, _ = px[x, y]
                px[x, y] = (int(r + (255 - r) * a), int(g + (228 - g) * a), int(b + (172 - b) * a), 255)
    gold, gold_hi, gold_lo = (222, 180, 72), (250, 222, 140), (150, 108, 36)
    for x in range(size):
        drop = [1, 2, 3, 3, 3, 3, 2, 1][x % 8]
        for y in range(0, 3 + drop):
            px[x, y] = (gold_hi if y == 0 else gold_lo if y == 2 + drop else gold) + (255,)
    # 大提琴：琴身逐行的半宽，琴颈、指板、琴马、f 孔、尾柱
    amber, amber_hi, amber_lo = (176, 86, 42), (214, 128, 66), (104, 46, 22)
    rows = {12: 1.5, 13: 2.5, 14: 3.0, 15: 3.5, 16: 3.5, 17: 3.0, 18: 2.5, 19: 3.0, 20: 4.0, 21: 4.5, 22: 4.5, 23: 4.5, 24: 4.0, 25: 3.0}
    cx = 16.0
    for y, hw in rows.items():
        x0, x1 = int(round(cx - hw)), int(round(cx + hw))
        for x in range(x0, x1):
            edge = x in (x0, x1 - 1) or y in (12, 25)
            px[x, y] = (amber_lo if edge else amber_hi if x < cx - hw / 2 else amber) + (255,)
    for y in range(4, 12):
        for x in (15, 16):
            px[x, y] = (amber_lo if y < 7 else amber) + (255,)
    px[17, 5] = amber_lo + (255,)
    px[14, 6] = amber_lo + (255,)
    for y in range(8, 20):
        px[15, y] = (24, 18, 18, 255)
        px[16, y] = (196, 196, 204, 255) if y > 9 else (24, 18, 18, 255)
    for x in range(14, 18):
        px[x, 21] = (232, 214, 170, 255)
    for y in (19, 20, 21):
        px[13, y] = (30, 14, 8, 255)
        px[18, y] = (30, 14, 8, 255)
    for y in (26, 27):
        px[16, y] = (176, 176, 186, 255)
    return img.resize((size * scale, size * scale), Image.NEAREST)


def hexenwald_banner(style, size=32):
    """魔女之森王室旗：星辰花之都的五瓣星辰花（金）居中、上方一顶小王冠，金边、燕尾、底下金穗；夜空里点几颗星。
    style：night（夜蓝底，正式用的这一版）、ivory（象牙白底、蓝花）、parted（左蓝右白，金花跨在中间）。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    navy, navy2 = (29, 42, 91, 255), (22, 32, 72, 255)
    ivory, ivory2 = (238, 230, 208, 255), (222, 212, 186, 255)
    gold, gold_hi, gold_lo = (222, 180, 72, 255), (250, 222, 140, 255), (160, 118, 40, 255)
    star = (240, 236, 210, 255)
    flower_col = gold
    cut = size - 7          # 燕尾从这一行开始
    for y in range(size):
        for x in range(size):
            inside = y < cut or (y < size - 1 and (abs(x - size / 2 + 0.5) > (y - cut) * 2.2))
            if not inside:
                continue
            if style == "ivory":
                c = ivory if y < size * 0.6 else ivory2
            elif style == "parted":
                c = navy if x < size // 2 else ivory
            else:
                c = navy if y < size * 0.55 else navy2
            px[x, y] = c
    # 金边
    for y in range(size):
        for x in range(size):
            if px[x, y][3] == 0:
                continue
            edge = x in (0, size - 1) or y == 0 or any(px[x + dx, y + dy][3] == 0 for dx, dy in ((1, 0), (-1, 0), (0, 1))
                                                       if 0 <= x + dx < size and 0 <= y + dy < size)
            if edge:
                px[x, y] = gold_lo if y > cut else gold
    # 星辰花：五片圆圆的花瓣围着亮金的花心，花瓣外缘深一点的金色勾边；和王冠之间留一道空
    cx, cy = size / 2 - 0.5, size * 0.55
    if style == "ivory":
        flower_col = (44, 64, 140, 255)
    edge_col = gold_lo if style != "ivory" else (28, 40, 96, 255)
    petal_r, petal_d = 2.7, 4.1
    centers = [(cx + math.cos(-math.pi / 2 + k * 2 * math.pi / 5) * petal_d,
                cy + math.sin(-math.pi / 2 + k * 2 * math.pi / 5) * petal_d) for k in range(5)]
    for y in range(size):
        for x in range(size):
            if px[x, y][3] == 0:
                continue
            d_center = math.hypot(x - cx, y - cy)
            d_petal = min(math.hypot(x - a, y - b) for a, b in centers)
            if d_center <= 1.7:
                px[x, y] = gold_hi
            elif d_petal <= petal_r - 0.8:
                px[x, y] = flower_col
            elif d_petal <= petal_r:
                px[x, y] = edge_col
    px[int(round(cx)), int(round(cy))] = (255, 250, 230, 255)
    # 王冠
    top = 3
    for x in range(int(cx) - 4, int(cx) + 6):
        for y in range(top + 2, top + 5):
            px[x, y] = gold
    for x in (int(cx) - 4, int(cx), int(cx) + 1, int(cx) + 5):
        px[x, top + 1] = gold
        px[x, top] = gold_hi
    px[int(cx) - 2, top + 1] = gold
    px[int(cx) + 3, top + 1] = gold
    for x in range(int(cx) - 3, int(cx) + 5, 2):
        px[x, top + 3] = (170, 40, 52, 255)
    # 点几颗星
    for x, y in ((5, 9), (25, 7), (7, 19), (24, 18), (16, 22), (4, 14), (27, 12)):
        if px[x, y][3] and px[x, y][:3] in (navy[:3], navy2[:3], ivory[:3], ivory2[:3]):
            px[x, y] = star if style != "ivory" else (150, 160, 200, 255)
    # 燕尾下的金穗
    for x in range(1, size - 1, 2):
        y = size - 1
        while y > 0 and px[x, y][3] == 0:
            y -= 1
        if y < size - 1:
            px[x, y + 1] = gold_lo
    return img


def banner_previews(out_dir):
    """王室旗的三版，各放大 8 倍存成 PNG，给维护者挑。"""
    out_dir.mkdir(parents=True, exist_ok=True)
    for style in ("night", "ivory", "parted"):
        img = hexenwald_banner(style)
        img.resize((img.width * 8, img.height * 8), Image.NEAREST).save(out_dir / f"banner_{style}.png")


# —— 指挥台、指挥棒 ——

def only(face):
    """只画一面的薄片（谱子、镶嵌）。"""
    return {f: None for f in ("north", "south", "east", "west", "up", "down") if f != face}


def halves(frm, to, tex, faces=None, rot=None):
    """跨两格的长条按 x=16 切成两段：每段的贴图按坐标取，不会被拉宽。"""
    if frm[0] < 16 < to[0]:
        return [box(frm, (16, to[1], to[2]), tex, faces=faces, rot=rot), box((16, frm[1], frm[2]), to, tex, faces=faces, rot=rot)]
    return [box(frm, to, tex, faces=faces, rot=rot)]


# 谱板向乐团（−z）倾：绕 x 转，托条朝着指挥。谱架立在台面前沿（离指挥 4 格像素多），垂着的手、挥到拍点的手臂都碰不到
PODIUM_TILT = {"origin": [16, 17.0, 3.6], "x": -32, "y": 0, "z": 0}


def podium_elements(score=False):
    """指挥台（两格宽 x 0–32，指挥面朝 −z，站在 x=16、z=8 的台面中央，台面高 8）。
    台前（朝乐团）一级踏步，立面一排星辰花镶嵌；深胡桃木台身，台面下一道金线；台后两角各一根矮栏柱，顶上金球，
    柱间一道低扶栏；台面前沿正中一座斜谱架，放了总谱时摊开一本谱子（两页五线谱、红封面露一圈边）。"""
    el = []
    # 踏步与星辰花
    el += halves((1, 0, 0), (31, 4, 3), "walnut")
    el += halves((0.8, 3.6, -0.2), (31.2, 4.1, 3), "gold", faces={"down": None})
    for i in range(6):
        x = 2.4 + i * 4.75
        el.append(box((x, 0.8, -0.1), (x + 3.2, 3.2, 0), "inlay", faces=only("north"), uv={"north": [0, 0, 8, 6]}))
    # 台身、金线、略探出的台面
    el += halves((0.4, 0, 3), (31.6, 6.6, 15.6), "walnut")
    el += halves((0.2, 6.6, 2.8), (31.8, 7.1, 15.8), "gold")
    el += halves((0, 7.1, 2.6), (32, 8, 16), "walnut")
    # 台后的栏柱、金球与扶栏
    for x in (1.0, 29.0):
        el.append(box((x - 0.3, 8, 12.9), (x + 2.3, 9, 15.5), "walnut"))
        el.append(box((x, 9, 13.2), (x + 2, 16.6, 15.2), "walnut"))
        el.append(box((x - 0.15, 16.6, 13.05), (x + 2.15, 17.1, 15.35), "gold"))
        el += disc(x + 1, 14.2, 0.95, 17.1, 18.9, "gold")
        el += disc(x + 1, 14.2, 0.55, 16.9, 19.3, "gold")
    el += halves((3, 14.9, 13.7), (29, 15.8, 14.7), "walnut")
    el += halves((3, 15.8, 13.8), (29, 16.05, 14.6), "gold", faces={"down": None})
    for x in (10.2, 21.0):
        el.append(box((x, 8, 13.8), (x + 0.9, 14.9, 14.6), "walnut"))
    # 斜谱架
    el.append(box((14.0, 8, 2.6), (18.0, 8.8, 5.0), "walnut"))
    el.append(box((15.2, 8.8, 2.8), (16.8, 17.0, 4.4), "walnut"))
    el.append(box((15.0, 12.0, 2.6), (17.0, 12.6, 4.6), "gold"))
    el.append(box((9.5, 17.0, 3.3), (22.5, 23.4, 3.9), "walnut", rot=PODIUM_TILT))
    el.append(box((9.5, 16.6, 3.9), (22.5, 17.4, 4.8), "walnut", rot=PODIUM_TILT))
    el.append(box((9.5, 17.35, 3.9), (22.5, 17.45, 4.8), "gold", faces={"down": None}, rot=PODIUM_TILT))
    if score:
        el.append(box((10.0, 17.5, 3.9), (22.0, 23.3, 4.0), "red", faces=only("south"), rot=PODIUM_TILT))
        for x0, x1 in ((10.4, 15.8), (16.2, 21.6)):
            el.append(box((x0, 17.8, 4.0), (x1, 23.0, 4.1), "white", faces=only("south"), rot=PODIUM_TILT))
            for y in (18.6, 19.0, 19.4, 19.8, 20.2, 21.0, 21.4, 21.8, 22.2, 22.6):
                el.append(box((x0 + 0.4, y, 4.1), (x1 - 0.4, y + 0.08, 4.12), "black", faces=only("south"), rot=PODIUM_TILT))
        el.append(box((15.8, 17.7, 4.0), (16.2, 23.1, 4.07), "walnut", faces=only("south"), rot=PODIUM_TILT))
    return el


def baton_icon():
    """指挥棒：金色尾球、软木色握柄、白色细杆，从左下到右上。16×16。"""
    img = Image.new("RGBA", (16, 16))
    px = img.load()
    gold, gold_hi, gold_lo = (214, 172, 72, 255), (246, 220, 140, 255), (150, 112, 44, 255)
    cork, cork_lo = (196, 156, 104, 255), (138, 100, 60, 255)
    white, white_lo, tip = (242, 240, 232, 255), (190, 188, 180, 255), (255, 255, 252, 255)
    for (x, y), c in {(1, 13): gold_hi, (2, 13): gold, (1, 14): gold, (2, 14): gold_lo, (3, 14): gold_lo, (1, 12): gold_lo}.items():
        px[x, y] = c
    for x, y in ((3, 12), (4, 11), (5, 10), (6, 9)):
        px[x, y] = cork
        px[x + 1, y] = cork
        px[x, y + 1] = cork_lo
    px[3, 13] = cork_lo
    px[7, 9] = gold
    px[7, 8] = gold_hi
    for i, (x, y) in enumerate(((8, 7), (9, 6), (10, 5), (11, 4), (12, 3), (13, 2))):
        px[x, y] = white
        if i < 3:
            px[x, y + 1] = white_lo
    px[14, 1] = tip
    return img


def podium():
    blocks = ASSETS / "models/block"
    items = ASSETS / "models/item"
    yrot = {"north": 0, "east": 90, "south": 180, "west": 270}
    for name, el in (("hexen_podium", podium_elements(False)), ("hexen_podium_score", podium_elements(True))):
        check_bounds(name, el)
        write(blocks / f"{name}.json", {"ambientocclusion": False, "textures": {k: T[k] for k in used(el)} | {"particle": T["walnut"]},
                                        "elements": el})
    write(blocks / "hexen_podium_part.json", {"textures": {"particle": T["walnut"]}})
    item_el = shifted(podium_elements(False), -8, -4, 0)
    check_bounds("hexen_podium item", item_el)
    write(items / "hexen_podium.json", {"ambientocclusion": False, "textures": {k: T[k] for k in used(item_el)} | {"particle": T["walnut"]},
                                        "elements": item_el, "display": display(0.36)})
    write(ASSETS / "items/hexen_podium.json", {"model": {"type": "minecraft:model", "model": "logos_dimension:item/hexen_podium"}})
    variants = {}
    for f, y in yrot.items():
        for side in ("false", "true"):
            for score in ("false", "true"):
                m = "hexen_podium_part" if side == "true" else ("hexen_podium_score" if score == "true" else "hexen_podium")
                variants[f"facing={f},score={score},side={side}"] = {"model": f"logos_dimension:block/{m}"} | ({"y": y} if y and side == "false" else {})
    write(ASSETS / "blockstates/hexen_podium.json", {"variants": variants})
    write(DATA / "loot_table/blocks/hexen_podium.json", loot("hexen_podium", {"side": "false"}))
    shaped("hexen_podium", [" L ", "PGP", "PPP"], {"L": "minecraft:lectern", "G": "minecraft:gold_ingot", "P": "minecraft:dark_oak_planks"})
    png(ASSETS / "textures/item/hexen_baton.png", baton_icon())
    # 原版拿剑的转法（z 55°）让棒子垂直于手臂；转到 111° 时棒子顺着手臂往前、略往上翘，握柄仍在拳里（同原版剑柄的位置）
    write(items / "hexen_baton.json", {"parent": "minecraft:item/handheld", "textures": {"layer0": "logos_dimension:item/hexen_baton"},
                                       "display": {
        "thirdperson_righthand": {"rotation": [0, -90, 111], "translation": [0, -0.74, -3.33], "scale": [0.6, 0.6, 0.6]},
        "thirdperson_lefthand": {"rotation": [0, 90, -111], "translation": [0, -0.74, -3.33], "scale": [0.6, 0.6, 0.6]},
        "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 1.43, 0.31], "scale": [0.45, 0.45, 0.45]},
        "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 1.43, 0.31], "scale": [0.45, 0.45, 0.45]}}})
    write(ASSETS / "items/hexen_baton.json", {"model": {"type": "minecraft:model", "model": "logos_dimension:item/hexen_baton"}})
    write(DATA / "recipe/hexen_baton.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
                                             "ingredients": ["minecraft:stick", "minecraft:gold_nugget"],
                                             "result": {"id": "logos_dimension:hexen_baton", "count": 1}})



# —— 星辰花琴凳 ——

BENCH_TOP = 10  # 凳面高（像素），同 StarbloomBenchBlock.TOP


def bench_elements():
    """星辰花琴凳（坐的人面朝 −z，凳子前沿朝着要弹的乐器）：四条胡桃木腿配金脚，牙板上一排星辰花，
    凳框上一圈金线，红丝绒坐垫上三颗金扣。x 1–15、z 3–13，凳面高 BENCH_TOP。"""
    el = []
    for x, z in ((2, 4), (12.5, 4), (2, 10.5), (12.5, 10.5)):
        el.append(box((x, 0.6, z), (x + 1.5, 8.4, z + 1.5), "walnut"))
        el.append(box((x - 0.2, 0, z - 0.2), (x + 1.7, 0.6, z + 1.7), "gold"))
    el.append(box((1.5, 6.2, 3.5), (14.5, 8.4, 4.1), "walnut"))
    el.append(box((1.5, 6.2, 11.9), (14.5, 8.4, 12.5), "walnut"))
    el.append(box((1.5, 6.2, 4.1), (2.1, 8.4, 11.9), "walnut"))
    el.append(box((13.9, 6.2, 4.1), (14.5, 8.4, 11.9), "walnut"))
    for x in (4.2, 7.3, 10.4):
        el.append(box((x, 6.6, 3.4), (x + 1.4, 8.0, 3.5), "inlay", faces=only("north"), uv={"north": [2, 2, 6, 6]}))
        el.append(box((x, 6.6, 12.5), (x + 1.4, 8.0, 12.6), "inlay", faces=only("south"), uv={"south": [2, 2, 6, 6]}))
    el.append(box((1, 8.4, 3), (15, 8.95, 13), "walnut"))
    el.append(box((1.2, 8.95, 3.2), (14.8, 9.15, 12.8), "gold", faces={"down": None}))
    el.append(box((1.6, 9.15, 3.6), (14.4, BENCH_TOP, 12.4), "velvet", faces={"down": None}))
    for x in (4.6, 7.75, 10.9):
        el.append(box((x, BENCH_TOP, 7.75), (x + 0.5, BENCH_TOP + 0.08, 8.25), "gold", faces={"down": None}))
    return el


def duet_bench_elements():
    """星辰花双人琴凳：两格宽（x 1–31，主块 x 0–16，另一格在 +x，即坐的人右手边），坐的人坐在正中，
    两格宽的钢琴、管风琴前用。做法同单人琴凳：四条腿配金脚、中间加一根横撑，牙板一排星辰花，红丝绒坐垫上五颗金扣。"""
    el = []
    for x, z in ((2, 4), (28.5, 4), (2, 10.5), (28.5, 10.5)):
        el.append(box((x, 0.6, z), (x + 1.5, 8.4, z + 1.5), "walnut"))
        el.append(box((x - 0.2, 0, z - 0.2), (x + 1.7, 0.6, z + 1.7), "gold"))
    el.append(box((3.5, 2.2, 7.2), (28.5, 3.2, 8.2), "walnut"))
    el.append(box((1.5, 6.2, 3.5), (30.5, 8.4, 4.1), "walnut"))
    el.append(box((1.5, 6.2, 11.9), (30.5, 8.4, 12.5), "walnut"))
    el.append(box((1.5, 6.2, 4.1), (2.1, 8.4, 11.9), "walnut"))
    el.append(box((29.9, 6.2, 4.1), (30.5, 8.4, 11.9), "walnut"))
    for i in range(7):
        x = 4.2 + i * 3.75
        el.append(box((x, 6.6, 3.4), (x + 1.4, 8.0, 3.5), "inlay", faces=only("north"), uv={"north": [2, 2, 6, 6]}))
        el.append(box((x, 6.6, 12.5), (x + 1.4, 8.0, 12.6), "inlay", faces=only("south"), uv={"south": [2, 2, 6, 6]}))
    el.append(box((1, 8.4, 3), (31, 8.95, 13), "walnut"))
    el.append(box((1.2, 8.95, 3.2), (30.8, 9.15, 12.8), "gold", faces={"down": None}))
    el.append(box((1.6, 9.15, 3.6), (30.4, BENCH_TOP, 12.4), "velvet", faces={"down": None}))
    for i in range(5):
        x = 5.6 + i * 5.1
        el.append(box((x, BENCH_TOP, 7.75), (x + 0.5, BENCH_TOP + 0.08, 8.25), "gold", faces={"down": None}))
    return el


def duet_bench():
    el = duet_bench_elements()
    blocks = ASSETS / "models/block"
    tex = {k: T[k] for k in used(el)} | {"particle": T["walnut"]}
    write(blocks / "starbloom_duet_bench.json", {"ambientocclusion": False, "textures": tex, "elements": el})
    write(blocks / "starbloom_duet_bench_part.json", {"textures": {"particle": T["walnut"]}})
    write(ASSETS / "models/item/starbloom_duet_bench.json", {"ambientocclusion": False, "textures": tex,
        "elements": shifted(el, -8, 0, 0), "display": display(0.45, ground=0.25)})
    write(ASSETS / "items/starbloom_duet_bench.json", {"model": {"type": "minecraft:model", "model": "logos_dimension:item/starbloom_duet_bench"}})
    yrot = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for f, y in yrot.items():
        for plays in ("none", "violin", "flute", "trumpet", "viola", "oboe", "bassoon"):
            for side in ("false", "true"):
                model = "starbloom_duet_bench_part" if side == "true" else "starbloom_duet_bench"
                variants[f"facing={f},plays={plays},side={side}"] = {"model": f"logos_dimension:block/{model}"} | ({"y": y} if y and side == "false" else {})
    write(ASSETS / "blockstates/starbloom_duet_bench.json", {"variants": variants})
    write(DATA / "loot_table/blocks/starbloom_duet_bench.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "conditions": [
        {"condition": "minecraft:survives_explosion"},
        {"condition": "minecraft:block_state_property", "block": "logos_dimension:starbloom_duet_bench", "properties": {"side": "false"}}],
        "entries": [{"type": "minecraft:item", "name": "logos_dimension:starbloom_duet_bench"}]}]})
    write(DATA / "recipe/starbloom_duet_bench.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": ["logos_dimension:starbloom_bench", "logos_dimension:starbloom_bench"],
        "result": {"id": "logos_dimension:starbloom_duet_bench", "count": 1}})


def bench():
    el = bench_elements()
    check_bounds("starbloom_bench", el)
    blocks = ASSETS / "models/block"
    tex = {k: T[k] for k in used(el)} | {"particle": T["walnut"]}
    write(blocks / "starbloom_bench.json", {"ambientocclusion": False, "textures": tex, "elements": el})
    write(ASSETS / "models/item/starbloom_bench.json", {"parent": "logos_dimension:block/starbloom_bench", "display": display(0.62, ground=0.3)})
    write(ASSETS / "items/starbloom_bench.json", {"model": {"type": "minecraft:model", "model": "logos_dimension:item/starbloom_bench"}})
    yrot = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for f, y in yrot.items():
        for plays in ("none", "violin", "flute", "trumpet", "viola", "oboe", "bassoon"):
            variants[f"facing={f},plays={plays}"] = {"model": "logos_dimension:block/starbloom_bench"} | ({"y": y} if y else {})
    write(ASSETS / "blockstates/starbloom_bench.json", {"variants": variants})
    write(DATA / "loot_table/blocks/starbloom_bench.json", loot("starbloom_bench"))
    shaped("starbloom_bench", ["RRR", "PGP", "P P"], {"R": "minecraft:red_wool", "P": "minecraft:dark_oak_planks", "G": "minecraft:gold_nugget"})


def main():
    models()
    blockstates()
    loot_and_recipes()
    art()
    organ()
    mallet()
    handheld()
    orchestra()
    podium()
    bench()
    duet_bench()
    png(ROOT / "resourcepacks/concert_hall/pack.png", concert_hall_icon())
    if len(sys.argv) > 1:
        banner_previews(Path(sys.argv[1]))
    print("instrument art written")


if __name__ == "__main__":
    main()
