"""神狐聚落与温泉旅馆全景建筑生成器 (Phase 4: Fox Spirit Settlement Architecture)

包含：
1. 主街街道骨架 (street_plaza, street_straight, street_curve, street_shrine_junction)
2. 繁华市街商铺与食摊 (shop_tea_house, shop_wagashi, shop_noodle)
3. 外围民居与向阳梯田 (dwelling_residence, dwelling_farm_terrace)
4. 核心地标一：温泉旅馆 · 此花之宿 (ryokan_entrance_hall, ryokan_hot_spring)
5. 核心地标二：山巅神狐神社 (shrine_ascent_torii, shrine_main_sanctuary)

所有建筑结构完全使用原版 Jigsaw 模板池系统及 NBT 数据驱动，配备智能下扎石柱地基（footings）。
"""
import json
import math
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "src/main/resources/data/logos_dimension"
STRUCTURE_DIR = DATA / "structure/fox_settlement"
POOL_DIR = DATA / "worldgen/template_pool/fox_settlement"

sys.path.insert(0, str(Path(__file__).resolve().parent))
from blueprint_nbt import write_structure

NS = "logos_dimension"
MC = "minecraft"

def st(name, **props):
    full = name if ":" in name else f"{MC}:{name}"
    res = {"Name": full}
    if props:
        res["Properties"] = {k: str(v).lower() for k, v in props.items()}
    return res

VOID = st("structure_void")
AIR = st("air")
WATER = st("water", level="0")

# 基础石材与木材
STONE_BRICK = st("stone_bricks")
MOSSY_STONE_BRICK = st("mossy_stone_bricks")
CRACKED_STONE_BRICK = st("cracked_stone_bricks")
COBBLESTONE = st("cobblestone")
MOSSY_COBBLE = st("mossy_cobblestone")
STONE = st("stone")
POLISHED_ANDESITE = st("polished_andesite")
SMOOTH_STONE = st("smooth_stone")
LIMESTONE = st(f"{NS}:journey_limestone")

def stone_stair(facing="north", half="bottom"):
    return st("stone_brick_stairs", facing=facing, half=half, shape="straight", waterlogged="false")

def mossy_stair(facing="north", half="bottom"):
    return st("mossy_stone_brick_stairs", facing=facing, half=half, shape="straight", waterlogged="false")

def stone_slab(slab_type="bottom"):
    return st("stone_brick_slab", type=slab_type, waterlogged="false")

def mossy_slab(slab_type="bottom"):
    return st("mossy_stone_brick_slab", type=slab_type, waterlogged="false")

def smooth_slab(slab_type="bottom"):
    return st("smooth_stone_slab", type=slab_type, waterlogged="false")

SPRUCE_LOG = st("spruce_log", axis="y")
SPRUCE_PLANKS = st("spruce_planks")
SPRUCE_FENCE = st("spruce_fence", waterlogged="false")
BAMBOO_FENCE = st("bamboo_fence", waterlogged="false")
CHERRY_LEAVES = st("cherry_leaves", persistent="true", distance="1", waterlogged="false")
CHERRY_LOG = st("cherry_log", axis="y")
FARMLAND = st("farmland", moisture="7")
WHEAT = st("wheat", age="7")

# 和风特殊方块
PLASTER = st(f"{NS}:journey_white_plaster")
WAINSCOT = st(f"{NS}:journey_wood_wainscot")
KAWARA_ROOF = st(f"{NS}:journey_kawara_roof")
def kawara_stair(facing="north", half="bottom"):
    return st(f"{NS}:journey_kawara_stairs", facing=facing, half=half, shape="straight", waterlogged="false")
KAWARA_SLAB = st(f"{NS}:journey_kawara_slab", type="bottom", waterlogged="false")

LACQUER_LOG = st(f"{NS}:journey_lacquer_log", axis="y")
LACQUER_PLANKS = st(f"{NS}:journey_lacquer_planks")
def lacquer_stair(facing="north", half="bottom"):
    return st(f"{NS}:journey_lacquer_stairs", facing=facing, half=half, shape="straight", waterlogged="false")
LACQUER_SLAB = st(f"{NS}:journey_lacquer_slab", type="bottom", waterlogged="false")
LACQUER_FENCE = st(f"{NS}:journey_lacquer_fence", waterlogged="false")

def tatami(facing="north"):
    return st(f"{NS}:journey_tatami", facing=facing)
def tatami_slab(facing="north"):
    return st(f"{NS}:journey_tatami_slab", facing=facing, type="bottom", waterlogged="false")

def shoji_door(facing="north", half="lower"):
    return st(f"{NS}:journey_shoji_door", facing=facing, half=half, hinge="left", open="false", powered="false")
def shoji_window(facing="north"):
    return st(f"{NS}:journey_shoji_window", facing=facing, waterlogged="false")

PAPER_LANTERN = st(f"{NS}:journey_paper_lantern", hanging="false", waterlogged="false")
PAPER_LANTERN_HANG = st(f"{NS}:journey_paper_lantern", hanging="true", waterlogged="false")
STONE_LANTERN = st(f"{NS}:journey_stone_lantern", waterlogged="false")
STONE_LANTERN_LOWER = st(f"{NS}:journey_stone_lantern_tall", half="lower", waterlogged="false")
STONE_LANTERN_UPPER = st(f"{NS}:journey_stone_lantern_tall", half="upper", waterlogged="false")

HOT_SPRING_STONE = st(f"{NS}:journey_hot_spring_stone")
def bamboo_spout(facing="south"):
    return st(f"{NS}:journey_bamboo_spout", facing=facing, waterlogged="false")
def fox_statue(facing="south"):
    return st(f"{NS}:journey_fox_statue", facing=facing, waterlogged="false")
STONE_BASIN = st(f"{NS}:journey_stone_basin", waterlogged="false")
def stepping_stone(t=0):
    return st(f"{NS}:journey_stepping_stone", type=str(t), waterlogged="false")
def shrine_hokora(facing="south"):
    return st(f"{NS}:journey_shrine_hokora", facing=facing, waterlogged="false")
def noren(facing="south"):
    return st(f"{NS}:journey_noren", facing=facing, waterlogged="false")
def shimenawa(facing="south"):
    return st(f"{NS}:journey_shimenawa", facing=facing, waterlogged="false")
def white_petals(amount=2):
    return st(f"{NS}:journey_white_petals", flower_amount=str(amount), north="false", east="false", south="false", west="false")

def random_paving(rng):
    r = rng.random()
    if r < 0.40: return POLISHED_ANDESITE
    if r < 0.70: return STONE_BRICK
    if r < 0.88: return MOSSY_STONE_BRICK
    return CRACKED_STONE_BRICK

def add_joint(cells, entities, pos, face, name, target, pool, final_state="minecraft:air"):
    orientation = "up_north" if face == "up" else f"{face}_up"
    cells[pos] = st("jigsaw", orientation=orientation)
    entities[pos] = {
        "id": "minecraft:jigsaw",
        "name": f"{NS}:{name}" if not name.startswith("minecraft:") else name,
        "target": f"{NS}:{target}" if not target.startswith("minecraft:") else target,
        "pool": "minecraft:empty" if pool == "empty" else f"{NS}:fox_settlement/{pool}",
        "joint": "rollable" if face == "up" else "aligned",
        "final_state": final_state
    }

def add_footings(cells, entities, floor_y=0):
    bases = {}
    for (x, y, z), s in cells.items():
        name = s["Name"]
        if name in {"minecraft:air", "minecraft:structure_void", "minecraft:jigsaw", "minecraft:water"}:
            continue
        if (x, z) not in bases or y < bases[x, z][0]:
            bases[x, z] = (y, s)
    
    for (x, z), (y, s) in sorted(bases.items()):
        name = s["Name"]
        if y <= floor_y + 1 and any(tag in name for tag in ["stone", "cobble", "limestone", "brick", "kawara", "lacquer", "plaster", "wainscot"]):
            if (x + z) % 2 == 0:
                final_mat = "minecraft:stone_bricks"
                add_joint(cells, entities, (x, y, z), "up", "footing", "minecraft:bottom", "footings", final_mat)

def fill_box(cells, p1, p2, state):
    x1, y1, z1 = p1
    x2, y2, z2 = p2
    for x in range(min(x1, x2), max(x1, x2) + 1):
        for y in range(min(y1, y2), max(y1, y2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                cells[x, y, z] = state

# -------------------------------------------------------------
# 1. street_plaza: 聚落主入口迎客广场
# -------------------------------------------------------------
def build_street_plaza():
    rng = random.Random(201)
    size = [15, 8, 15]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (14, 7, 14), VOID)

    # 铺设平整广场 (Y=1)
    for x in range(1, 14):
        for z in range(1, 14):
            cells[x, 1, z] = random_paving(rng)
            cells[x, 0, z] = STONE
            for y in range(2, 6):
                cells[x, y, z] = AIR

    # 广场中央小石景观与长明高石灯笼 (X=7, Z=7)
    cells[7, 1, 7] = STONE_BRICK
    cells[7, 2, 7] = STONE_LANTERN_LOWER
    cells[7, 3, 7] = STONE_LANTERN_UPPER
    cells[7, 4, 7] = AIR

    # 左右休闲木长凳与落樱
    for z in (6, 8):
        cells[5, 2, z] = lacquer_stair(facing="east")
        cells[9, 2, z] = lacquer_stair(facing="west")

    cells[6, 2, 4] = white_petals(3)
    cells[8, 2, 10] = white_petals(2)
    cells[4, 2, 8] = stepping_stone(0)
    cells[10, 2, 6] = stepping_stone(1)

    # Jigsaw 端口
    # 南端接大鸟居山门 (Z=14, Y=1, X=7)
    add_joint(cells, entities, (7, 1, 14), "south", "village_start", "village_entrance", "mountain_trail_entry", "minecraft:polished_andesite")
    # 北端继续沿主街前进 (Z=0, Y=1, X=7)
    add_joint(cells, entities, (7, 1, 0), "north", "street_main", "street_in", "street_segments", "minecraft:polished_andesite")
    # 东侧商铺插槽 (X=14, Y=1, Z=7)
    add_joint(cells, entities, (14, 1, 7), "east", "shop_slot", "shop_front", "shops", "minecraft:stone_bricks")
    # 西侧商铺插槽 (X=0, Y=1, Z=7)
    add_joint(cells, entities, (0, 1, 7), "west", "shop_slot", "shop_front", "shops", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "street_plaza", size, cells, entities

# -------------------------------------------------------------
# 2. street_straight: 主街直道段 (两旁有商铺插槽与悬挂纸灯笼)
# -------------------------------------------------------------
def build_street_straight():
    rng = random.Random(202)
    size = [11, 7, 11]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (10, 6, 10), VOID)

    # 主路面 (X=3..7, Z=0..10, Y=1)
    for z in range(11):
        for x in range(3, 8):
            cells[x, 1, z] = random_paving(rng)
            cells[x, 0, z] = STONE
            for y in range(2, 6):
                cells[x, y, z] = AIR

    # 两侧石沟沿 (X=2 与 X=8)
    for z in range(11):
        cells[2, 1, z] = MOSSY_STONE_BRICK
        cells[8, 1, z] = MOSSY_STONE_BRICK
        cells[2, 0, z] = STONE
        cells[8, 0, z] = STONE

    # 路旁和纸路灯杆 (X=2, Z=5 与 X=8, Z=5)
    for px in (2, 8):
        cells[px, 2, 5] = LACQUER_FENCE
        cells[px, 3, 5] = LACQUER_FENCE
        cells[px, 4, 5] = PAPER_LANTERN

    cells[5, 2, 3] = white_petals(2)
    cells[6, 2, 8] = white_petals(1)

    # 端口
    add_joint(cells, entities, (5, 1, 10), "south", "street_in", "street_main", "empty", "minecraft:polished_andesite")
    add_joint(cells, entities, (5, 1, 0), "north", "street_main", "street_in", "street_segments", "minecraft:polished_andesite")
    # 两侧商铺插槽
    add_joint(cells, entities, (10, 1, 5), "east", "shop_slot", "shop_front", "shops", "minecraft:stone_bricks")
    add_joint(cells, entities, (0, 1, 5), "west", "shop_slot", "shop_front", "shops", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "street_straight", size, cells, entities

# -------------------------------------------------------------
# 3. street_curve: 主街微弯弧道段
# -------------------------------------------------------------
def build_street_curve():
    rng = random.Random(203)
    size = [11, 7, 11]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (10, 6, 10), VOID)

    # 弯道从南(X=5, Z=10) 蜿蜒至西北(X=3, Z=0)
    for z in range(11):
        cx = 5 if z > 6 else 4 if z > 3 else 3
        for dx in (-1, 0, 1, 2):
            x = cx + dx
            if 0 <= x < 11:
                cells[x, 1, z] = random_paving(rng)
                cells[x, 0, z] = STONE
                for y in range(2, 6):
                    cells[x, y, z] = AIR

    cells[4, 2, 6] = white_petals(3)
    cells[5, 2, 2] = white_petals(2)
    # 转角矮石灯笼
    cells[2, 1, 6] = STONE_BRICK
    cells[2, 2, 6] = STONE_LANTERN

    add_joint(cells, entities, (5, 1, 10), "south", "street_in", "street_main", "empty", "minecraft:polished_andesite")
    add_joint(cells, entities, (3, 1, 0), "north", "street_main", "street_in", "street_junction", "minecraft:polished_andesite")
    add_joint(cells, entities, (10, 1, 5), "east", "shop_slot", "shop_front", "dwellings", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "street_curve", size, cells, entities

# -------------------------------------------------------------
# 4. street_shrine_junction: 街尾地标分流广场 (左转温泉旅馆，直走神社参道)
# -------------------------------------------------------------
def build_street_shrine_junction():
    rng = random.Random(204)
    size = [15, 8, 15]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (14, 7, 14), VOID)

    # 铺设开阔丁字路口 (Y=1)
    for x in range(1, 14):
        for z in range(1, 14):
            cells[x, 1, z] = random_paving(rng)
            cells[x, 0, z] = STONE
            for y in range(2, 6):
                cells[x, y, z] = AIR

    # 分流口中央伫立灵狐石像与双春日石灯笼 (X=7, Z=5)
    cells[7, 1, 5] = STONE_BRICK
    cells[7, 2, 5] = fox_statue(facing="south")
    cells[5, 1, 5] = STONE_BRICK
    cells[5, 2, 5] = STONE_LANTERN
    cells[9, 1, 5] = STONE_BRICK
    cells[9, 2, 5] = STONE_LANTERN

    # 落樱
    cells[6, 2, 8] = white_petals(4)
    cells[8, 2, 8] = white_petals(3)

    # 端口
    # 南入口来自主街
    add_joint(cells, entities, (7, 1, 14), "south", "street_in", "street_main", "empty", "minecraft:polished_andesite")
    # 北出口通向神社参道 (千本鸟居)
    add_joint(cells, entities, (7, 1, 0), "north", "shrine_ascent_start", "shrine_start", "shrine_ascent", "minecraft:stone_bricks")
    # 西出口通往温泉旅馆 · 此花之宿 (西侧大门)
    add_joint(cells, entities, (0, 1, 7), "west", "ryokan_slot", "ryokan_front", "ryokan", "minecraft:polished_andesite")
    # 东出口通往向阳梯田
    add_joint(cells, entities, (14, 1, 7), "east", "terrace_slot", "terrace_front", "terraces", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "street_shrine_junction", size, cells, entities

# -------------------------------------------------------------
# 5. shop_tea_house: 和风茶寮 (榻榻米、茶席、障子门窗、深檐暖帘)
# -------------------------------------------------------------
def build_shop_tea_house():
    rng = random.Random(205)
    size = [9, 8, 9]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (8, 7, 8), VOID)

    # 1. 石台基 (Y=1)
    for x in range(1, 8):
        for z in range(1, 8):
            cells[x, 1, z] = STONE_BRICK
            cells[x, 0, z] = STONE

    # 2. 室内铺设榻榻米 (X=2..6, Z=2..6, Y=2)
    for x in range(2, 7):
        for z in range(2, 7):
            cells[x, 2, z] = tatami(facing="north" if z % 2 == 0 else "east")
            for y in range(3, 6):
                cells[x, y, z] = AIR

    # 3. 茶席矮几与茶碗 (中心 X=4, Z=4)
    cells[4, 3, 4] = LACQUER_SLAB
    cells[4, 3, 3] = stepping_stone(0)
    cells[4, 3, 5] = stepping_stone(1)

    # 4. 外墙 (Y=2..4)
    # 下部腰墙 (Y=2)，上部白灰泥墙 (Y=3..4)
    for y in (2, 3, 4):
        mat = WAINSCOT if y == 2 else PLASTER
        for x in (1, 7):
            for z in range(1, 8):
                cells[x, y, z] = mat
        for z in (1, 7):
            for x in range(1, 8):
                cells[x, y, z] = mat

    # 侧窗开障子窗 (X=1 与 X=7, Z=4, Y=3)
    cells[1, 3, 4] = shoji_window(facing="west")
    cells[7, 3, 4] = shoji_window(facing="east")

    # 门面朝南 (Z=7): 开双扇障子门 (X=4) 与 暖帘 (Y=4)
    cells[4, 2, 7] = shoji_door(facing="south", half="lower")
    cells[4, 3, 7] = shoji_door(facing="south", half="upper")
    cells[4, 4, 7] = noren(facing="south")
    cells[3, 4, 7] = PAPER_LANTERN_HANG
    cells[5, 4, 7] = PAPER_LANTERN_HANG

    # 5. 黑陶瓦出檐屋顶 (Y=5..6)
    for x in range(0, 9):
        for z in range(0, 9):
            if x == 0 or x == 8 or z == 0 or z == 8:
                facing = "north" if z == 0 else "south" if z == 8 else "west" if x == 0 else "east"
                cells[x, 5, z] = kawara_stair(facing=facing)
            else:
                cells[x, 5, z] = KAWARA_ROOF
                cells[x, 6, z] = KAWARA_SLAB if (2 <= x <= 6 and 2 <= z <= 6) else AIR

    # 门前踏步引道 (Z=8, X=4)
    cells[4, 1, 8] = stepping_stone(2)
    add_joint(cells, entities, (4, 1, 8), "south", "shop_front", "shop_slot", "empty", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "shop_tea_house", size, cells, entities

# -------------------------------------------------------------
# 6. shop_wagashi: 和菓子点心铺 (商品展示案台、暖帘招牌)
# -------------------------------------------------------------
def build_shop_wagashi():
    rng = random.Random(206)
    size = [9, 8, 9]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (8, 7, 8), VOID)

    for x in range(1, 8):
        for z in range(1, 8):
            cells[x, 1, z] = STONE_BRICK
            cells[x, 0, z] = STONE
            for y in range(2, 6):
                cells[x, y, z] = AIR

    # 墙体
    for y in (2, 3, 4):
        mat = WAINSCOT if y == 2 else PLASTER
        for x in (1, 7):
            for z in range(1, 8): cells[x, y, z] = mat
        for z in (1,):
            for x in range(1, 8): cells[x, y, z] = mat

    # 开放式点心售卖案台 (Z=6, X=2..6, Y=2)
    for x in range(2, 7):
        cells[x, 2, 6] = LACQUER_PLANKS
        cells[x, 3, 6] = stepping_stone(x % 4)  # 充当精致点心盘
        cells[x, 4, 6] = noren(facing="south")

    # 门头和纸灯笼
    cells[2, 4, 7] = PAPER_LANTERN_HANG
    cells[6, 4, 7] = PAPER_LANTERN_HANG

    # 屋顶
    for x in range(0, 9):
        for z in range(0, 9):
            if x == 0 or x == 8 or z == 0 or z == 8:
                facing = "north" if z == 0 else "south" if z == 8 else "west" if x == 0 else "east"
                cells[x, 5, z] = kawara_stair(facing=facing)
            else:
                cells[x, 5, z] = KAWARA_ROOF
                if 2 <= x <= 6 and 2 <= z <= 6:
                    cells[x, 6, z] = KAWARA_SLAB

    cells[4, 1, 8] = stepping_stone(3)
    add_joint(cells, entities, (4, 1, 8), "south", "shop_front", "shop_slot", "empty", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "shop_wagashi", size, cells, entities

# -------------------------------------------------------------
# 7. shop_noodle: 面档食摊 (热气大锅、吧台长凳、暖帘)
# -------------------------------------------------------------
def build_shop_noodle():
    rng = random.Random(207)
    size = [9, 7, 9]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (8, 6, 8), VOID)

    for x in range(1, 8):
        for z in range(1, 8):
            cells[x, 1, z] = STONE_BRICK
            cells[x, 0, z] = STONE
            for y in range(2, 5):
                cells[x, y, z] = AIR

    # 后厨挡墙 (Z=2)
    for y in (2, 3):
        for x in range(2, 7):
            cells[x, y, 2] = WAINSCOT

    # 煮面铁锅 (石水钵 + 热气石模拟煮面汤锅, X=3, Z=3)
    cells[3, 1, 3] = HOT_SPRING_STONE
    cells[3, 2, 3] = STONE_BASIN

    # 食客吧台 (Z=5, X=3..6, Y=2) 与 吧台长凳 (Z=6, Y=2)
    for x in range(3, 7):
        cells[x, 2, 5] = SPRUCE_PLANKS
        cells[x, 2, 6] = lacquer_stair(facing="north")
        cells[x, 4, 5] = noren(facing="south")

    # 深檐遮雨棚 (Y=4..5)
    for x in range(1, 8):
        for z in range(3, 8):
            cells[x, 4, z] = KAWARA_ROOF
    cells[2, 4, 6] = PAPER_LANTERN_HANG
    cells[6, 4, 6] = PAPER_LANTERN_HANG

    cells[4, 1, 8] = stepping_stone(0)
    add_joint(cells, entities, (4, 1, 8), "south", "shop_front", "shop_slot", "empty", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "shop_noodle", size, cells, entities

# -------------------------------------------------------------
# 8. dwelling_residence: 外围和风民居 (缘侧长廊、榻榻米客堂、柴棚与小菜圃)
# -------------------------------------------------------------
def build_dwelling_residence():
    rng = random.Random(208)
    size = [11, 8, 11]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (10, 7, 10), VOID)

    # 地基
    for x in range(1, 10):
        for z in range(1, 10):
            cells[x, 1, z] = STONE_BRICK
            cells[x, 0, z] = STONE
            for y in range(2, 6):
                cells[x, y, z] = AIR

    # 房屋主体在西北 (X=2..7, Z=2..7)
    for x in range(2, 8):
        for z in range(2, 8):
            cells[x, 2, z] = tatami(facing="north" if x % 2 == 0 else "east")

    # 墙体
    for y in (2, 3, 4):
        mat = WAINSCOT if y == 2 else PLASTER
        for x in (2, 7):
            for z in range(2, 8): cells[x, y, z] = mat
        for z in (2, 7):
            for x in range(2, 8): cells[x, y, z] = mat

    # 南面缘侧长廊 (Z=8, X=3..6, Y=2)
    for x in range(3, 7):
        cells[x, 2, 8] = SPRUCE_PLANKS
        cells[x, 2, 7] = shoji_door(facing="south", half="lower")
        cells[x, 3, 7] = shoji_door(facing="south", half="upper")

    # 侧面障子窗
    cells[2, 3, 4] = shoji_window(facing="west")
    cells[7, 3, 4] = shoji_window(facing="east")

    # 东侧小院与柴棚 (X=8..9, Z=3..6)
    cells[8, 2, 3] = SPRUCE_LOG
    cells[9, 2, 3] = SPRUCE_LOG
    cells[8, 3, 3] = SPRUCE_LOG
    cells[9, 3, 3] = SPRUCE_LOG
    cells[8, 2, 6] = BAMBOO_FENCE
    cells[9, 2, 6] = BAMBOO_FENCE

    # 黑瓦屋顶
    for x in range(1, 9):
        for z in range(1, 9):
            cells[x, 5, z] = KAWARA_ROOF
            if 3 <= x <= 6 and 3 <= z <= 6:
                cells[x, 6, z] = KAWARA_SLAB

    # 引道
    cells[5, 1, 10] = stepping_stone(1)
    add_joint(cells, entities, (5, 1, 10), "south", "dwelling_front", "shop_slot", "empty", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "dwelling_residence", size, cells, entities

# -------------------------------------------------------------
# 9. dwelling_farm_terrace: 向阳坡地梯田 (水稻田、灌溉水渠与农具棚)
# -------------------------------------------------------------
def build_dwelling_farm_terrace():
    rng = random.Random(209)
    size = [13, 7, 13]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (12, 6, 12), VOID)

    # 分为高台菜田 (Y=2, Z=1..5) 与 低处水田 (Y=1, Z=7..11)
    # 1. 高台菜田 (Z=1..5)
    for x in range(2, 11):
        for z in range(1, 6):
            cells[x, 1, z] = STONE
            cells[x, 2, z] = FARMLAND
            cells[x, 3, z] = WHEAT
            cells[x, 4, z] = AIR

    # 梯田石质挡墙 (Z=6, Y=1..2)
    for x in range(1, 12):
        cells[x, 1, 6] = MOSSY_STONE_BRICK
        cells[x, 2, 6] = STONE_BRICK

    # 2. 低处水稻梯田 (Z=7..11)
    for x in range(2, 11):
        for z in range(7, 12):
            cells[x, 0, z] = STONE
            # 中央灌溉水渠 (X=6)
            if x == 6:
                cells[x, 1, z] = WATER
            else:
                cells[x, 1, z] = FARMLAND
                cells[x, 2, z] = WHEAT
            cells[x, 3, z] = AIR

    # 农具小棚 (X=1..3, Z=1..3, Y=2..4)
    for x in range(1, 4):
        for z in range(1, 4):
            cells[x, 4, z] = KAWARA_ROOF
    cells[1, 2, 1] = SPRUCE_LOG
    cells[3, 2, 1] = SPRUCE_LOG
    cells[1, 2, 3] = SPRUCE_LOG
    cells[3, 2, 3] = SPRUCE_LOG
    cells[2, 2, 2] = BAMBOO_FENCE

    # 通往街道的田埂小径
    for z in range(0, 13):
        cells[11, 1, z] = random_paving(rng)
        cells[11, 2, z] = AIR

    add_joint(cells, entities, (0, 1, 6), "west", "terrace_front", "terrace_slot", "empty", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "dwelling_farm_terrace", size, cells, entities

# -------------------------------------------------------------
# 10. ryokan_entrance_hall: 温泉旅馆大门 · 此花之宿 (深檐门廊、回廊环绕和风中庭、白樱庭院)
# -------------------------------------------------------------
def build_ryokan_entrance_hall():
    rng = random.Random(210)
    size = [17, 11, 17]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (16, 10, 16), VOID)

    # 1. 宽阔石基 (Y=1)
    for x in range(1, 16):
        for z in range(1, 16):
            cells[x, 1, z] = STONE_BRICK
            cells[x, 0, z] = STONE
            for y in range(2, 8):
                cells[x, y, z] = AIR

    # 2. 回廊 (缘侧) 环绕四边 (X=2..14, Z=2..14, 宽 2 格)
    # 回廊铺设木质地板 (SPRUCE_PLANKS)
    for x in range(2, 15):
        for z in range(2, 15):
            is_corridor = (x in (2, 3, 13, 14) or z in (2, 3, 13, 14))
            if is_corridor:
                cells[x, 2, z] = SPRUCE_PLANKS
            else:
                # 庭院自然土壤与枯山水铺地
                cells[x, 2, z] = stepping_stone((x + z) % 4) if (x + z) % 3 == 0 else LIMESTONE

    # 3. 回廊内侧缘侧矮栏 (LACQUER_FENCE)
    for x in range(4, 13):
        cells[x, 3, 4] = LACQUER_FENCE
        cells[x, 3, 12] = LACQUER_FENCE
    for z in range(4, 13):
        cells[4, 3, z] = LACQUER_FENCE
        cells[12, 3, z] = LACQUER_FENCE

    # 4. 中庭核心：和风枯山水锦鲤池与百年中庭白樱神树 (X=7..9, Z=7..9)
    # 小水池 (X=6..8, Z=6..8)
    for x in range(6, 9):
        for z in range(6, 9):
            cells[x, 1, z] = MOSSY_COBBLE
            cells[x, 2, z] = WATER
    cells[6, 3, 8] = bamboo_spout(facing="east")
    cells[6, 2, 8] = MOSSY_COBBLE
    cells[8, 3, 6] = STONE_BASIN

    # 中庭白樱树干与繁密落樱花冠
    cells[9, 2, 9] = CHERRY_LOG
    cells[9, 3, 9] = CHERRY_LOG
    cells[9, 4, 9] = CHERRY_LOG
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            for dy in (5, 6):
                cells[9 + dx, dy, 9 + dz] = CHERRY_LEAVES
    # 树下散落落樱
    cells[8, 3, 9] = white_petals(4)
    cells[9, 3, 8] = white_petals(3)
    cells[10, 3, 9] = white_petals(2)

    # 5. 外墙与深檐屋檐
    # 外墙 (X=2..14, Z=2) 与 迎客大门 (东侧 X=14, 正对主街)
    for y in (2, 3, 4):
        mat = WAINSCOT if y == 2 else PLASTER
        for x in range(2, 15):
            cells[x, y, 2] = mat
        for z in range(2, 15):
            cells[2, y, z] = mat

    # 东向正对主街大门 (X=14, Z=7..9)
    # 宽阔深檐迎客大门：暖帘 + 纸灯笼 + 双开障子门
    cells[14, 2, 8] = shoji_door(facing="east", half="lower")
    cells[14, 3, 8] = shoji_door(facing="east", half="upper")
    cells[14, 4, 8] = noren(facing="east")
    cells[14, 4, 7] = PAPER_LANTERN_HANG
    cells[14, 4, 9] = PAPER_LANTERN_HANG

    # 6. 四周回廊大黑陶瓦坡屋顶 (Y=6..8)
    for x in range(1, 16):
        for z in range(1, 16):
            if x in (1, 15) or z in (1, 15):
                facing = "north" if z == 1 else "south" if z == 15 else "west" if x == 1 else "east"
                cells[x, 6, z] = kawara_stair(facing=facing)
            elif x in (2, 3, 13, 14) or z in (2, 3, 13, 14):
                cells[x, 7, z] = KAWARA_ROOF

    # 7. 端口配置
    # 东门迎客插槽（接入主街 junction）
    add_joint(cells, entities, (16, 1, 8), "east", "ryokan_front", "ryokan_slot", "empty", "minecraft:polished_andesite")
    # 西后门通向露天温泉池 (X=0, Z=8, Y=1)
    add_joint(cells, entities, (0, 1, 8), "west", "ryokan_back", "onsen_front", "ryokan_onsen", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "ryokan_entrance_hall", size, cells, entities

# -------------------------------------------------------------
# 11. ryokan_hot_spring: 露天温泉池 (温泉水蒸气、温泉石、竹出水口、竹篱围绕)
# -------------------------------------------------------------
def build_ryokan_hot_spring():
    rng = random.Random(211)
    size = [15, 8, 15]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (14, 7, 14), VOID)

    # 露天温泉汤池：中央宽阔温润浴池 (X=3..11, Z=3..11)
    # 池底全面铺设 HOT_SPRING_STONE，使水面自然升腾蒸汽粒子并提供生命恢复
    for x in range(2, 13):
        for z in range(2, 13):
            cells[x, 0, z] = HOT_SPRING_STONE
            cells[x, 1, z] = WATER
            cells[x, 2, z] = AIR
            cells[x, 3, z] = AIR
            cells[x, 4, z] = AIR

    # 温泉天然岩石池岸 (粗粝石块、苔石、踏步石环绕)
    for x in range(2, 13):
        for z in (2, 12):
            cells[x, 1, z] = MOSSY_COBBLE if rng.random() < 0.6 else COBBLESTONE
            cells[x, 2, z] = stepping_stone(x % 4)
    for z in range(2, 13):
        for x in (2, 12):
            cells[x, 1, z] = MOSSY_COBBLE if rng.random() < 0.6 else COBBLESTONE
            cells[x, 2, z] = stepping_stone(z % 4)

    # 温泉后壁竹出水口源泉 (西侧 X=3, Z=7)
    cells[2, 2, 7] = BAMBOO_FENCE
    cells[2, 3, 7] = bamboo_spout(facing="east")

    # 温泉外围幽静竹篱遮蔽 (X=1..13, Z=1..13 外沿)
    for x in range(1, 14):
        cells[x, 2, 1] = BAMBOO_FENCE
        cells[x, 3, 1] = BAMBOO_FENCE
        cells[x, 2, 13] = BAMBOO_FENCE
        cells[x, 3, 13] = BAMBOO_FENCE
    for z in range(1, 14):
        cells[1, 2, z] = BAMBOO_FENCE
        cells[1, 3, z] = BAMBOO_FENCE

    # 池角矮石灯笼夜照
    cells[3, 2, 3] = STONE_LANTERN
    cells[11, 2, 11] = STONE_LANTERN

    # 漂浮在水岸边的白落樱
    cells[4, 2, 4] = white_petals(3)
    cells[10, 2, 10] = white_petals(2)

    # 接入东侧旅馆回廊后门
    add_joint(cells, entities, (14, 1, 7), "east", "onsen_front", "ryokan_back", "empty", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "ryokan_hot_spring", size, cells, entities

# -------------------------------------------------------------
# 12. shrine_ascent_torii: 神社参道千本鸟居阶梯 (依山直上，密布朱红鸟居与狐像)
# -------------------------------------------------------------
def build_shrine_ascent_torii():
    rng = random.Random(212)
    size = [9, 10, 13]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (8, 9, 12), VOID)

    # 阶梯攀爬：从 Z=12 (Y=1) 攀登至 Z=0 (Y=5)
    for z in range(13):
        yf = 1 + int((12 - z) * 4 / 12)
        for x in (3, 4, 5):
            cells[x, yf, z] = random_paving(rng)
            cells[x, yf - 1, z] = STONE
            for dy in range(1, 5):
                cells[x, yf + dy, z] = AIR
        if z % 3 == 0 and yf > 1:
            for x in (3, 4, 5):
                cells[x, yf, z] = stone_stair(facing="north")

        # 左右石栏杆
        cells[2, yf + 1, z] = LACQUER_FENCE
        cells[6, yf + 1, z] = LACQUER_FENCE

    # 在阶梯沿途架设两座千本朱红鸟居 (分别在 Z=9 与 Z=3)
    for torii_z, y_base in [(9, 2), (3, 4)]:
        # 柱础
        cells[2, y_base, torii_z] = STONE_BRICK
        cells[6, y_base, torii_z] = STONE_BRICK
        for y in range(y_base + 1, y_base + 4):
            cells[2, y, torii_z] = LACQUER_LOG
            cells[6, y, torii_z] = LACQUER_LOG
        for x in range(2, 7):
            cells[x, y_base + 3, torii_z] = LACQUER_PLANKS
        for x in range(1, 8):
            cells[x, y_base + 4, torii_z] = KAWARA_ROOF

    # 阶梯入口两侧守卫狐像 (Z=11, Y=2)
    cells[1, 2, 11] = STONE_BRICK
    cells[1, 3, 11] = fox_statue(facing="east")
    cells[7, 2, 11] = STONE_BRICK
    cells[7, 3, 11] = fox_statue(facing="west")

    # 端口
    add_joint(cells, entities, (4, 1, 12), "south", "shrine_start", "shrine_ascent_start", "empty", "minecraft:stone_bricks")
    add_joint(cells, entities, (4, 5, 0), "north", "shrine_top", "shrine_bottom", "shrine_sanctuary", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=1)
    return "shrine_ascent_torii", size, cells, entities

# -------------------------------------------------------------
# 13. shrine_main_sanctuary: 山巅神狐神社主殿、手水舍与千古白樱神木
# -------------------------------------------------------------
def build_shrine_main_sanctuary():
    rng = random.Random(213)
    size = [17, 13, 17]
    cells = {}; entities = {}
    fill_box(cells, (0, 0, 0), (16, 12, 16), VOID)

    # 1. 宽阔神圣参拜平整平台 (Y=1)
    for x in range(1, 16):
        for z in range(1, 16):
            cells[x, 1, z] = random_paving(rng)
            cells[x, 0, z] = STONE
            for y in range(2, 8):
                cells[x, y, z] = AIR

    # 2. 手水舍 (Chozuya, 位于西南角 X=2..5, Z=10..13)
    for x in range(2, 6):
        for z in range(10, 14):
            cells[x, 5, z] = KAWARA_ROOF
    for px, pz in [(2, 10), (5, 10), (2, 13), (5, 13)]:
        for y in range(2, 5):
            cells[px, y, pz] = SPRUCE_LOG
    # 手水舍中心水钵与竹出水
    cells[3, 2, 11] = STONE_BASIN
    cells[4, 2, 11] = bamboo_spout(facing="west")
    cells[3, 1, 11] = MOSSY_COBBLE

    # 3. 千古白樱神树 (包裹注连绳纸垂, 东南角 X=12, Z=11)
    cells[12, 2, 11] = CHERRY_LOG
    cells[12, 3, 11] = CHERRY_LOG
    cells[12, 4, 11] = CHERRY_LOG
    cells[12, 5, 11] = CHERRY_LOG
    # 神木树干悬挂神圣注连绳 (Y=3)
    cells[12, 3, 10] = shimenawa(facing="north")
    cells[12, 3, 12] = shimenawa(facing="south")
    cells[11, 3, 11] = shimenawa(facing="west")
    cells[13, 3, 11] = shimenawa(facing="east")
    # 繁盛白樱花冠 (Y=6..8)
    for dx in (-2, -1, 0, 1, 2):
        for dz in (-2, -1, 0, 1, 2):
            for dy in (6, 7, 8):
                if abs(dx) + abs(dz) <= 3:
                    cells[12 + dx, dy, 11 + dz] = CHERRY_LEAVES

    # 4. 神社本殿 (Main Sanctuary, 位于正北 X=5..11, Z=2..7)
    # 抬高双格石基 (Y=2..3)
    for x in range(5, 12):
        for z in range(2, 8):
            cells[x, 2, z] = STONE_BRICK
            cells[x, 3, z] = STONE_BRICK

    # 朱漆立柱与白灰泥墙 (Y=4..6)
    for x in (5, 8, 11):
        for z in (2, 7):
            for y in (4, 5, 6):
                cells[x, y, z] = LACQUER_LOG
    # 墙面
    for x in range(6, 11):
        for y in (4, 5, 6):
            cells[x, y, 2] = PLASTER
    for z in range(3, 7):
        for y in (4, 5, 6):
            cells[5, y, z] = PLASTER
            cells[11, y, z] = PLASTER

    # 供奉核心：石龛 (Hokora) 与 赛钱箱/灵狐坐像 (X=8, Z=5, Y=4)
    cells[8, 4, 5] = shrine_hokora(facing="south")
    cells[7, 4, 5] = fox_statue(facing="south")
    cells[9, 4, 5] = fox_statue(facing="south")

    # 本殿正面开敞走廊与注连绳大梁 (Z=7, Y=6)
    for x in range(6, 11):
        cells[x, 6, 7] = shimenawa(facing="south")
    cells[6, 5, 7] = PAPER_LANTERN_HANG
    cells[10, 5, 7] = PAPER_LANTERN_HANG

    # 本殿宏伟重檐歇山顶 (Y=7..9)
    for x in range(4, 13):
        for z in range(1, 9):
            if x in (4, 12) or z in (1, 8):
                facing = "north" if z == 1 else "south" if z == 8 else "west" if x == 4 else "east"
                cells[x, 7, z] = kawara_stair(facing=facing)
            else:
                cells[x, 8, z] = KAWARA_ROOF
                if 6 <= x <= 10 and 3 <= z <= 6:
                    cells[x, 9, z] = KAWARA_SLAB

    # 前台参拜石阶 (Z=8, X=7..9, Y=2..3)
    for x in (7, 8, 9):
        cells[x, 3, 8] = stone_stair(facing="south")
        cells[x, 2, 8] = STONE_BRICK

    # 平台左右石灯笼
    cells[4, 2, 8] = STONE_LANTERN
    cells[12, 2, 8] = STONE_LANTERN

    # 满地落樱
    cells[7, 2, 10] = white_petals(4)
    cells[9, 2, 10] = white_petals(3)
    cells[8, 2, 12] = white_petals(2)

    # 端口接参道千本鸟居 (Z=16, X=8, Y=1)
    add_joint(cells, entities, (8, 1, 16), "south", "shrine_bottom", "shrine_top", "empty", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "shrine_main_sanctuary", size, cells, entities

# -------------------------------------------------------------
# 写入所有 NBT 蓝图
# -------------------------------------------------------------
def generate_all_settlement_structures():
    builders = [
        build_street_plaza,
        build_street_straight,
        build_street_curve,
        build_street_shrine_junction,
        build_shop_tea_house,
        build_shop_wagashi,
        build_shop_noodle,
        build_dwelling_residence,
        build_dwelling_farm_terrace,
        build_ryokan_entrance_hall,
        build_ryokan_hot_spring,
        build_shrine_ascent_torii,
        build_shrine_main_sanctuary
    ]
    STRUCTURE_DIR.mkdir(parents=True, exist_ok=True)
    results = []
    for builder in builders:
        name, size, cells, entities = builder()
        out_path = STRUCTURE_DIR / f"{name}.nbt"
        write_structure(out_path, size, cells, entities, data_version=4790, entities=[])
        print(f"  -> 生成蓝图: {name}.nbt (尺寸: {size}, 方块: {len(cells)}, 拼图/实体: {len(entities)})")
        results.append((name, size))
    return results

# -------------------------------------------------------------
# 配置 Jigsaw 模板池 (Template Pools)
# -------------------------------------------------------------
def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

def pool_element(location, processors="settlement_preserve_void", projection="rigid", weight=1):
    return {
        "weight": weight,
        "element": {
            "element_type": "minecraft:single_pool_element",
            "location": f"{NS}:fox_settlement/{location}",
            "processors": f"{NS}:{processors}",
            "override_liquid_settings": "ignore_waterlogging",
            "projection": projection
        }
    }

def feature_element(feature, weight=1):
    return {
        "weight": weight,
        "element": {
            "element_type": "minecraft:feature_pool_element",
            "feature": f"{NS}:{feature}",
            "projection": "rigid"
        }
    }

def generate_settlement_pools():
    POOL_DIR.mkdir(parents=True, exist_ok=True)

    # 1. 聚落起点池 (start): 聚落入口大广场
    write_json(POOL_DIR / "start.json", {
        "name": f"{NS}:fox_settlement/start",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("street_plaza")
        ]
    })

    # 1b. 登山古道山门入池 (mountain_trail_entry)
    write_json(POOL_DIR / "mountain_trail_entry.json", {
        "name": f"{NS}:fox_settlement/mountain_trail_entry",
        "fallback": "minecraft:empty",
        "elements": [
            {
                "weight": 1,
                "element": {
                    "element_type": "minecraft:single_pool_element",
                    "location": f"{NS}:cherry_mountain_trail/trail_grand_torii",
                    "processors": f"{NS}:settlement_preserve_void",
                    "override_liquid_settings": "ignore_waterlogging",
                    "projection": "rigid"
                }
            }
        ]
    })

    # 2. 主街街道段池 (street_segments)
    write_json(POOL_DIR / "street_segments.json", {
        "name": f"{NS}:fox_settlement/street_segments",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("street_straight", weight=2),
            pool_element("street_curve", weight=1)
        ]
    })

    # 3. 街尾分流路口池 (street_junction)
    write_json(POOL_DIR / "street_junction.json", {
        "name": f"{NS}:fox_settlement/street_junction",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("street_shrine_junction")
        ]
    })

    # 4. 商铺与食摊池 (shops)
    write_json(POOL_DIR / "shops.json", {
        "name": f"{NS}:fox_settlement/shops",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("shop_tea_house", weight=2),
            pool_element("shop_wagashi", weight=2),
            pool_element("shop_noodle", weight=2)
        ]
    })

    # 5. 民居住宅池 (dwellings)
    write_json(POOL_DIR / "dwellings.json", {
        "name": f"{NS}:fox_settlement/dwellings",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("dwelling_residence")
        ]
    })

    # 6. 梯田池 (terraces)
    write_json(POOL_DIR / "terraces.json", {
        "name": f"{NS}:fox_settlement/terraces",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("dwelling_farm_terrace")
        ]
    })

    # 7. 温泉旅馆大堂池 (ryokan)
    write_json(POOL_DIR / "ryokan.json", {
        "name": f"{NS}:fox_settlement/ryokan",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("ryokan_entrance_hall")
        ]
    })

    # 8. 温泉露天汤池 (ryokan_onsen)
    write_json(POOL_DIR / "ryokan_onsen.json", {
        "name": f"{NS}:fox_settlement/ryokan_onsen",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("ryokan_hot_spring")
        ]
    })

    # 9. 神社千本鸟居参道池 (shrine_ascent)
    write_json(POOL_DIR / "shrine_ascent.json", {
        "name": f"{NS}:fox_settlement/shrine_ascent",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("shrine_ascent_torii")
        ]
    })

    # 10. 神社本殿神域池 (shrine_sanctuary)
    write_json(POOL_DIR / "shrine_sanctuary.json", {
        "name": f"{NS}:fox_settlement/shrine_sanctuary",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("shrine_main_sanctuary")
        ]
    })

    # 11. footings 石柱支撑池
    write_json(POOL_DIR / "footings.json", {
        "name": f"{NS}:fox_settlement/footings",
        "fallback": "minecraft:empty",
        "elements": [
            feature_element("settlement_footing")
        ]
    })
    print("  -> 聚落模板池 JSON 配置完成。")

# -------------------------------------------------------------
# 配置结构与结构集 (Structure & Structure Set)
# -------------------------------------------------------------
def generate_settlement_config():
    struct_path = DATA / "worldgen/structure/fox_settlement.json"
    write_json(struct_path, {
        "type": "minecraft:jigsaw",
        "biomes": "#logos_dimension:has_structure/fox_settlement",
        "step": "surface_structures",
        "spawn_overrides": {},
        "terrain_adaptation": "none",
        "start_pool": f"{NS}:fox_settlement/start",
        "size": 7,
        "start_height": {
            "absolute": -1
        },
        "project_start_to_heightmap": "WORLD_SURFACE_WG",
        "use_expansion_hack": False,
        "max_distance_from_center": 112
    })

    set_path = DATA / "worldgen/structure_set/fox_settlement.json"
    write_json(set_path, {
        "structures": [
            {
                "structure": f"{NS}:fox_settlement",
                "weight": 1
            }
        ],
        "placement": {
            "type": "minecraft:random_spread",
            "salt": 1789234,
            "spacing": 36,
            "separation": 16,
            "spread_type": "linear"
        }
    })

    tag_path = DATA / "tags/worldgen/biome/has_structure/fox_settlement.json"
    write_json(tag_path, {
        "values": [
            f"{NS}:cloudsea_white_cherry"
        ]
    })
    print("  -> 结构与结构集注册完成: fox_settlement")

def main():
    print("=== 开始生成神狐聚落与温泉旅馆全景建筑 (Phase 4) ===")
    generate_all_settlement_structures()
    generate_settlement_pools()
    generate_settlement_config()
    print("=== 神狐聚落与温泉旅馆全景建筑生成完毕 ===")

if __name__ == "__main__":
    main()
