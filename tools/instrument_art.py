"""魔女之森乐器的数据：方块状态、掉落、配方、物品定义，纸带图标、魔女之森旗、音乐厅音源包图标。

    uv run --no-project --with pillow --with numpy python tools/instrument_art.py [<旗子预览目录>]

模型和模型贴图不在这里：2026-11-14 起由 tools/instrument_models/ 按组生成（键盘、管风琴、弦乐、管乐、打击乐与指挥台），
共用工具 tools/instrument_kit.py，规范见 docs/INSTRUMENT_ART.md。本脚本最后调用 instrument_models.build_all()。

坐标约定（模型那边沿用）：按 FACING=north 画，键盘 / 正面朝北（z 小的一侧）；多格乐器的附属块只有破坏粒子。
"""
import json
import math
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "src/main/resources"
ASSETS = RES / "assets/logos_dimension"
DATA = RES / "data/logos_dimension"
YROT = {"north": 0, "east": 90, "south": 180, "west": 270}
# 琴凳 / 双人琴凳记着坐在上面的人拿的手持乐器
PLAYS = ("none", "violin", "flute", "trumpet", "viola", "oboe", "bassoon")


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def item_def(name, model=None):
    """items/<name>.json：物品用的模型（默认 item/<name>）。"""
    write(ASSETS / f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"logos_dimension:{model or 'item/' + name}"}})


def single(name):
    """只有朝向的方块状态。"""
    return {"variants": {f"facing={f}": ({"model": f"logos_dimension:block/{name}"} | ({"y": y} if y else {})) for f, y in YROT.items()}}


def two_part(name, prop, main_value, values):
    """两格的乐器：prop=main_value 的那格画主模型（跟着朝向转），另一格是只有粒子的 _part。"""
    variants = {}
    for f, y in YROT.items():
        for v in values:
            m = name if v == main_value else f"{name}_part"
            variants[f"facing={f},{prop}={v}"] = {"model": f"logos_dimension:block/{m}"} | ({"y": y} if y and v == main_value else {})
    return {"variants": variants}

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



# —— 贴图（模型以外）——

def png(path, img):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)

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
    png(tex / "item/blank_music_roll.png", roll_icon(False))
    png(tex / "item/music_roll.png", roll_icon(True))
    for name in ("starbloom_grand_piano", "hexen_concert_harp", "blank_music_roll", "music_roll"):
        item_def(name)
    for name in ("blank_music_roll", "music_roll"):
        write(ASSETS / f"models/item/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"logos_dimension:item/{name}"}})


# —— 管风琴（docs/PIPE_ORGAN.md）——

def organ():
    for name in ("hexen_organ_console", "organ_wind_chest", "organ_tin_pipe", "organ_wood_pipe", "organ_trumpet_pipe"):
        item_def(name)
    variants = {}
    for facing, y in YROT.items():
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
        for facing, y in YROT.items():
            for shape in ("single", "bottom", "middle", "top"):
                v = {"model": f"logos_dimension:block/organ_{kind}_pipe_{shape}"}
                if y:
                    v["y"] = y
                variants[f"facing={facing},shape={shape}"] = v
        write(ASSETS / f"blockstates/organ_{kind}_pipe.json", {"variants": variants})
    write(ASSETS / "blockstates/organ_trumpet_pipe.json", single("organ_trumpet_pipe"))

    def self_drop(name):
        return {"type": "minecraft:block", "pools": [{"rolls": 1, "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [{"type": "minecraft:item", "name": f"logos_dimension:{name}"}]}]}
    write(DATA / "loot_table/blocks/hexen_organ_console.json", loot("hexen_organ_console", {"part": "0"}))
    for name in ("organ_wind_chest", "organ_tin_pipe", "organ_wood_pipe", "organ_trumpet_pipe"):
        write(DATA / f"loot_table/blocks/{name}.json", self_drop(name))

    def shaped_n(name, pattern, key, count):
        write(DATA / f"recipe/{name}.json", {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": pattern,
            "key": key, "result": {"id": f"logos_dimension:{name}", "count": count}})
    shaped_n("hexen_organ_console", ["QQQ", "GNG", "PPP"],
             {"Q": "minecraft:quartz_block", "G": "minecraft:gold_ingot", "N": "minecraft:note_block", "P": "minecraft:dark_oak_planks"}, 1)
    shaped_n("organ_wind_chest", ["PPP", "LGL", "PPP"],
             {"P": "minecraft:dark_oak_planks", "L": "minecraft:leather", "G": "minecraft:gold_nugget"}, 2)
    shaped_n("organ_tin_pipe", ["I", "I", "G"], {"I": "minecraft:iron_ingot", "G": "minecraft:gold_nugget"}, 3)
    shaped_n("organ_wood_pipe", ["P", "P", "G"], {"P": "minecraft:dark_oak_planks", "G": "minecraft:gold_nugget"}, 3)
    shaped_n("organ_trumpet_pipe", ["  G", "GG ", "G  "], {"G": "minecraft:gold_ingot"}, 2)


# —— 钟琴、钢片琴、管钟、大键琴 ——

def mallet():
    for name in ("hexen_glockenspiel", "hexen_celesta", "hexen_tubular_bells", "hexen_harpsichord"):
        item_def(name)
    write(ASSETS / "blockstates/hexen_glockenspiel.json", single("hexen_glockenspiel"))
    write(ASSETS / "blockstates/hexen_celesta.json", single("hexen_celesta"))
    write(ASSETS / "blockstates/hexen_tubular_bells.json", two_part("hexen_tubular_bells", "half", "lower", ("lower", "upper")))
    write(ASSETS / "blockstates/hexen_harpsichord.json", two_part("hexen_harpsichord", "tail", "false", ("false", "true")))
    write(DATA / "loot_table/blocks/hexen_glockenspiel.json", loot("hexen_glockenspiel"))
    write(DATA / "loot_table/blocks/hexen_celesta.json", loot("hexen_celesta"))
    write(DATA / "loot_table/blocks/hexen_tubular_bells.json", loot("hexen_tubular_bells", {"half": "lower"}))
    write(DATA / "loot_table/blocks/hexen_harpsichord.json", loot("hexen_harpsichord", {"tail": "false"}))
    shaped("hexen_glockenspiel", ["I I", "INI", "S S"], {"I": "minecraft:iron_ingot", "N": "minecraft:note_block", "S": "minecraft:stick"})
    shaped("hexen_celesta", ["III", "PNP", "P P"], {"I": "minecraft:iron_ingot", "N": "minecraft:note_block", "P": "minecraft:dark_oak_planks"})
    shaped("hexen_tubular_bells", ["CCC", "CNC", "P P"], {"C": "minecraft:copper_ingot", "N": "minecraft:note_block", "P": "minecraft:dark_oak_planks"})
    shaped("hexen_harpsichord", ["QQQ", "SNS", "P P"], {"Q": "minecraft:quartz_block", "S": "minecraft:string", "N": "minecraft:note_block",
                                                        "P": "minecraft:dark_oak_planks"})


# —— 手持乐器与乐谱架 ——

HANDHELD = ("hexen_violin", "hexen_flute", "hexen_trumpet", "hexen_viola", "hexen_oboe", "hexen_bassoon")
# 只供渲染的模型（items/held/，不是物品）：演奏时画在身上的乐器（弓弦乐器不带弓）、弓、工位上手里的琴槌
HELD = ("hexen_violin", "hexen_flute", "hexen_trumpet", "hexen_viola", "hexen_oboe", "hexen_bassoon",
        "hexen_violin_bow", "hexen_glockenspiel_mallet", "hexen_chime_hammer", "hexen_timpani_mallet")


def handheld():
    for name in HANDHELD:
        # 使用中（演奏）换成 _playing 模型：第一人称把乐器摆到下巴、嘴边
        write(ASSETS / f"items/{name}.json", {"model": {"type": "minecraft:condition", "property": "minecraft:using_item",
            "on_true": {"type": "minecraft:model", "model": f"logos_dimension:item/{name}_playing"},
            "on_false": {"type": "minecraft:model", "model": f"logos_dimension:item/{name}"}}})
    for name in HELD:
        model_name = name if name.endswith("_bow") else f"{name}_held"
        short = model_name.removeprefix("hexen_").removesuffix("_held")
        item_def(f"held/{short}", f"item/{model_name}")
    bs = {}
    for f, y in YROT.items():
        for holds in PLAYS:
            bs[f"facing={f},holds={holds}"] = {"model": f"logos_dimension:block/hexen_music_stand_{holds}"} | ({"y": y} if y else {})
    write(ASSETS / "blockstates/hexen_music_stand.json", {"variants": bs})
    item_def("hexen_music_stand")
    write(DATA / "loot_table/blocks/hexen_music_stand.json", loot("hexen_music_stand"))
    shaped("hexen_violin", [" PS", "PSP", "SP "], {"P": "minecraft:jungle_planks", "S": "minecraft:string"})
    shaped("hexen_flute", ["  I", " I ", "I  "], {"I": "minecraft:iron_ingot"})
    shaped("hexen_trumpet", ["  G", "GGG", "WG "], {"G": "minecraft:gold_ingot", "W": "minecraft:blue_wool"})
    shaped("hexen_music_stand", ["PPP", " S ", "SSS"], {"P": "minecraft:dark_oak_planks", "S": "minecraft:stick"})
    shaped("hexen_viola", [" PS", "PSP", "SP "], {"P": "minecraft:mangrove_planks", "S": "minecraft:string"})
    shaped("hexen_oboe", ["  R", " P ", "P  "], {"R": "minecraft:sugar_cane", "P": "minecraft:dark_oak_planks"})
    shaped("hexen_bassoon", ["  R", "PIP", "PP "], {"R": "minecraft:sugar_cane", "P": "minecraft:mangrove_planks", "I": "minecraft:iron_ingot"})
    # 小号上垂下来的魔女之森旗（管乐组的模型引用这张）
    png(ASSETS / "textures/block/instrument/hexenwald_banner.png", hexenwald_banner("night"))


def shaped(name, pattern, key):
    write(DATA / f"recipe/{name}.json", {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": pattern,
                                         "key": key, "result": {"id": f"logos_dimension:{name}", "count": 1}})

def loot(name, cond=None):
    conditions = [{"condition": "minecraft:survives_explosion"}]
    if cond:
        conditions.append({"condition": "minecraft:block_state_property", "block": f"logos_dimension:{name}", "properties": cond})
    return {"type": "minecraft:block", "pools": [{"rolls": 1, "conditions": conditions,
            "entries": [{"type": "minecraft:item", "name": f"logos_dimension:{name}"}]}]}



def orchestra():
    """大提琴、低音提琴（两格高：下半格画整件琴和凳子，上半格只有粒子）与定音鼓。"""
    for name in ("hexen_cello", "hexen_double_bass", "hexen_timpani"):
        item_def(name)
    write(ASSETS / "blockstates/hexen_timpani.json", single("hexen_timpani"))
    write(DATA / "loot_table/blocks/hexen_timpani.json", loot("hexen_timpani"))
    for name in ("hexen_cello", "hexen_double_bass"):
        write(ASSETS / f"blockstates/{name}.json", two_part(name, "half", "lower", ("lower", "upper")))
        write(DATA / f"loot_table/blocks/{name}.json", loot(name, {"half": "lower"}))
    shaped("hexen_cello", [" PS", "PNP", "SP "], {"P": "minecraft:jungle_planks", "S": "minecraft:string", "N": "minecraft:note_block"})
    shaped("hexen_double_bass", [" PS", "PNP", "PPS"], {"P": "minecraft:dark_oak_planks", "S": "minecraft:string", "N": "minecraft:note_block"})
    shaped("hexen_timpani", ["LLL", "CNC", "C C"], {"L": "minecraft:leather", "C": "minecraft:copper_ingot", "N": "minecraft:note_block"})


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



# —— 指挥台、指挥棒、琴凳 ——

def podium():
    item_def("hexen_podium")
    variants = {}
    for f, y in YROT.items():
        for side in ("false", "true"):
            for score in ("false", "true"):
                m = "hexen_podium_part" if side == "true" else ("hexen_podium_score" if score == "true" else "hexen_podium")
                variants[f"facing={f},score={score},side={side}"] = {"model": f"logos_dimension:block/{m}"} | ({"y": y} if y and side == "false" else {})
    write(ASSETS / "blockstates/hexen_podium.json", {"variants": variants})
    write(DATA / "loot_table/blocks/hexen_podium.json", loot("hexen_podium", {"side": "false"}))
    shaped("hexen_podium", [" L ", "PGP", "PPP"], {"L": "minecraft:lectern", "G": "minecraft:gold_ingot", "P": "minecraft:dark_oak_planks"})
    # 指挥棒的贴图和模型在打击乐组（instrument_models/percussion.py）
    item_def("hexen_baton")
    write(DATA / "recipe/hexen_baton.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
                                             "ingredients": ["minecraft:stick", "minecraft:gold_nugget"],
                                             "result": {"id": "logos_dimension:hexen_baton", "count": 1}})


def bench():
    item_def("starbloom_bench")
    variants = {}
    for f, y in YROT.items():
        for plays in PLAYS:
            variants[f"facing={f},plays={plays}"] = {"model": "logos_dimension:block/starbloom_bench"} | ({"y": y} if y else {})
    write(ASSETS / "blockstates/starbloom_bench.json", {"variants": variants})
    write(DATA / "loot_table/blocks/starbloom_bench.json", loot("starbloom_bench"))
    shaped("starbloom_bench", ["RRR", "PGP", "P P"], {"R": "minecraft:red_wool", "P": "minecraft:dark_oak_planks", "G": "minecraft:gold_nugget"})


def duet_bench():
    item_def("starbloom_duet_bench")
    variants = {}
    for f, y in YROT.items():
        for plays in PLAYS:
            for side in ("false", "true"):
                model = "starbloom_duet_bench_part" if side == "true" else "starbloom_duet_bench"
                variants[f"facing={f},plays={plays},side={side}"] = {"model": f"logos_dimension:block/{model}"} | ({"y": y} if y and side == "false" else {})
    write(ASSETS / "blockstates/starbloom_duet_bench.json", {"variants": variants})
    write(DATA / "loot_table/blocks/starbloom_duet_bench.json", loot("starbloom_duet_bench", {"side": "false"}))
    write(DATA / "recipe/starbloom_duet_bench.json", {"type": "minecraft:crafting_shapeless", "category": "misc",
        "ingredients": ["logos_dimension:starbloom_bench", "logos_dimension:starbloom_bench"],
        "result": {"id": "logos_dimension:starbloom_duet_bench", "count": 1}})


def main():
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
    # 全部乐器的模型与模型贴图
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import instrument_models
    instrument_models.build_all()
    if len(sys.argv) > 1:
        banner_previews(Path(sys.argv[1]))
    print("instrument art written")


if __name__ == "__main__":
    main()
