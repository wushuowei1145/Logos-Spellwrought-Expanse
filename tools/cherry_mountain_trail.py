"""灵狐樱野 · 盘山古道与溪流生成器 (Phase 3: Mountain Trail & Creek Generator)

以原版 Jigsaw 模板池系统实现：
1. trail_start: 山脚起点，含小水潭、竹林簇、石径与双格高石灯笼。
2. trail_stair: 粗粝山阶，依坡向上爬升，侧旁伴生潺潺跌水溪流与竹护栏。
3. trail_bridge: 溪涧石板桥，横跨山涧细流，桥侧配洗手石钵与竹出水口。
4. trail_turn: 之字回折台，转角宽敞休憩处，石壁嵌有路边小石龛与小石灯。
5. trail_torii_ascent: 朱红鸟居石阶，穿行朱漆鸟居与灰瓦屋脊。
6. trail_summit_stair: 登顶幽径，两侧高耸岩壁与繁密白樱完全遮蔽聚落视线。
7. trail_grand_torii: 聚落山门大鸟居，悬挂注连绳与纸垂，守候两座灵狐石像，穿过后豁然开朗！

所有模板均自带智能下扎石柱地基（footings），自适应陡峭山坡，不悬空、不掩埋。
"""
import json
import math
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "src/main/resources/data/logos_dimension"
STRUCTURE_DIR = DATA / "structure/cherry_mountain_trail"
POOL_DIR = DATA / "worldgen/template_pool/cherry_mountain_trail"

sys.path.insert(0, str(Path(__file__).resolve().parent))
from blueprint_nbt import write_structure

NS = "logos_dimension"
MC = "minecraft"

# 基础辅助函数
def st(name, **props):
    full = name if ":" in name else f"{MC}:{name}"
    res = {"Name": full}
    if props:
        res["Properties"] = {k: str(v).lower() for k, v in props.items()}
    return res

VOID = st("structure_void")
AIR = st("air")
WATER = st("water", level="0")

# 石材调色板
STONE_BRICK = st("stone_bricks")
MOSSY_STONE_BRICK = st("mossy_stone_bricks")
CRACKED_STONE_BRICK = st("cracked_stone_bricks")
COBBLESTONE = st("cobblestone")
MOSSY_COBBLE = st("mossy_cobblestone")
STONE = st("stone")
SMOOTH_STONE = st("smooth_stone")
LIMESTONE = st(f"{NS}:journey_limestone")

# 台阶与楼梯
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

# 和风特殊方块
LACQUER_LOG = st(f"{NS}:journey_lacquer_log", axis="y")
LACQUER_LOG_X = st(f"{NS}:journey_lacquer_log", axis="x")
LACQUER_PLANKS = st(f"{NS}:journey_lacquer_planks")
LACQUER_SLAB = st(f"{NS}:journey_lacquer_slab", type="bottom", waterlogged="false")
LACQUER_FENCE = st(f"{NS}:journey_lacquer_fence", waterlogged="false")

KAWARA_ROOF = st(f"{NS}:journey_kawara_roof")
def kawara_stair(facing="north", half="bottom"):
    return st(f"{NS}:journey_kawara_stairs", facing=facing, half=half, shape="straight", waterlogged="false")
KAWARA_SLAB = st(f"{NS}:journey_kawara_slab", type="bottom", waterlogged="false")

STONE_LANTERN = st(f"{NS}:journey_stone_lantern", waterlogged="false")
STONE_LANTERN_LOWER = st(f"{NS}:journey_stone_lantern_tall", half="lower", waterlogged="false")
STONE_LANTERN_UPPER = st(f"{NS}:journey_stone_lantern_tall", half="upper", waterlogged="false")
PAPER_LANTERN_HANG = st(f"{NS}:journey_paper_lantern", hanging="true", waterlogged="false")

def fox_statue(facing="south"):
    return st(f"{NS}:journey_fox_statue", facing=facing, waterlogged="false")

STONE_BASIN = st(f"{NS}:journey_stone_basin", waterlogged="false")
def bamboo_spout(facing="south"):
    return st(f"{NS}:journey_bamboo_spout", facing=facing, waterlogged="false")

def stepping_stone(t=0):
    return st(f"{NS}:journey_stepping_stone", type=str(t), waterlogged="false")

def shrine_hokora(facing="south"):
    return st(f"{NS}:journey_shrine_hokora", facing=facing, waterlogged="false")

def shimenawa(facing="south"):
    return st(f"{NS}:journey_shimenawa", facing=facing, waterlogged="false")

def white_petals(amount=2):
    return st(f"{NS}:journey_white_petals", flower_amount=str(amount), north="false", east="false", south="false", west="false")

BAMBOO_FENCE = st("bamboo_fence", waterlogged="false")
CHERRY_LEAVES = st("cherry_leaves", persistent="true", distance="1", waterlogged="false")
CHERRY_LOG = st("cherry_log", axis="y")

# 辅助生成器
def random_paving(rng):
    r = rng.random()
    if r < 0.45: return STONE_BRICK
    if r < 0.75: return MOSSY_STONE_BRICK
    if r < 0.90: return COBBLESTONE
    return CRACKED_STONE_BRICK

def add_joint(cells, entities, pos, face, name, target, pool, final_state="minecraft:air"):
    orientation = "up_north" if face == "up" else f"{face}_up"
    cells[pos] = st("jigsaw", orientation=orientation)
    entities[pos] = {
        "id": "minecraft:jigsaw",
        "name": f"{NS}:{name}" if not name.startswith("minecraft:") else name,
        "target": f"{NS}:{target}" if not target.startswith("minecraft:") else target,
        "pool": "minecraft:empty" if pool == "empty" else f"{NS}:cherry_mountain_trail/{pool}",
        "joint": "rollable" if face == "up" else "aligned",
        "final_state": final_state
    }

def add_footings(cells, entities, floor_y=0):
    """为悬空的石质地基底部自动配置向下探底的柱体拼图（footing）"""
    bases = {}
    for (x, y, z), s in cells.items():
        name = s["Name"]
        if name in {"minecraft:air", "minecraft:structure_void", "minecraft:jigsaw", "minecraft:water"}:
            continue
        if (x, z) not in bases or y < bases[x, z][0]:
            bases[x, z] = (y, s)
    
    for (x, z), (y, s) in sorted(bases.items()):
        name = s["Name"]
        if y <= floor_y + 1 and any(tag in name for tag in ["stone", "cobble", "limestone", "brick", "kawara", "lacquer"]):
            if (x + z) % 2 == 0:  # 棋盘格交错采样，既扎实又不冗余
                final_mat = "minecraft:mossy_cobblestone" if "cobble" in name else "minecraft:stone_bricks"
                add_joint(cells, entities, (x, y, z), "up", "footing", "minecraft:bottom", "footings", final_mat)

def fill_box(cells, p1, p2, state):
    x1, y1, z1 = p1
    x2, y2, z2 = p2
    for x in range(min(x1, x2), max(x1, x2) + 1):
        for y in range(min(y1, y2), max(y1, y2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                cells[x, y, z] = state

def clear_path(cells, p1, p2):
    fill_box(cells, p1, p2, AIR)

# -------------------------------------------------------------
# 1. trail_start: 山脚起点 (溪水汇聚潭、竹林、双格高春日石灯笼、散落落樱)
# -------------------------------------------------------------
def build_trail_start():
    rng = random.Random(101)
    size = [11, 8, 11]
    cells = {}
    entities = {}
    fill_box(cells, (0, 0, 0), (10, 7, 10), VOID)

    # 1. 基础地表石基 (Y=0)
    for x in range(11):
        for z in range(11):
            cells[x, 0, z] = LIMESTONE if rng.random() < 0.4 else STONE

    # 2. 迎宾小道 (Z 从 10 到 0, X=4..6, Y=1)
    for z in range(11):
        for x in (4, 5, 6):
            cells[x, 1, z] = random_paving(rng)
            # 行走净空 (Y=2..4)
            for y in range(2, 5):
                cells[x, y, z] = AIR

    # 3. 散落踏步石与白落樱
    cells[4, 2, 9] = stepping_stone(0)
    cells[6, 2, 7] = stepping_stone(1)
    cells[5, 2, 5] = white_petals(2)
    cells[4, 2, 3] = white_petals(3)
    cells[6, 2, 1] = white_petals(1)

    # 4. 溪水汇聚潭 (东侧 X=7..9, Z=4..8)
    for x in range(7, 10):
        for z in range(4, 9):
            cells[x, 0, z] = MOSSY_COBBLE if rng.random() < 0.6 else COBBLESTONE
            cells[x, 1, z] = WATER
            cells[x, 2, z] = AIR
    # 潭沿石护
    for z in range(3, 10):
        cells[7, 1, z] = MOSSY_COBBLE
    for x in range(7, 10):
        cells[x, 1, 9] = COBBLESTONE
        cells[x, 1, 3] = MOSSY_COBBLE

    # 5. 竹林簇与竹护栏 (东侧池旁与西侧山脚)
    cells[8, 1, 2] = BAMBOO_FENCE
    cells[9, 1, 2] = BAMBOO_FENCE
    cells[8, 2, 2] = BAMBOO_FENCE
    for x, z in [(2, 2), (2, 3), (1, 3)]:
        cells[x, 1, z] = BAMBOO_FENCE

    # 6. 西侧迎宾高石灯笼 (X=2, Z=8)
    cells[2, 1, 8] = STONE_BRICK
    cells[2, 2, 8] = STONE_LANTERN_LOWER
    cells[2, 3, 8] = STONE_LANTERN_UPPER
    cells[2, 4, 8] = AIR

    # 7. Jigsaw 端口
    # 南端自然接驳（若山脚继续延伸，或作为结构起点）
    add_joint(cells, entities, (5, 1, 10), "south", "trail_start_in", "trail_top", "empty", "minecraft:mossy_stone_bricks")
    # 北端登山口向上接驳 (Y=1, Z=0)
    add_joint(cells, entities, (5, 1, 0), "north", "trail_start_exit", "trail_bottom", "stairs", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=0)
    return "trail_start", size, cells, entities

# -------------------------------------------------------------
# 2. trail_stair: 粗粝山阶 (爬升 3 格，旁伴随跌水溪流与竹护栏)
# -------------------------------------------------------------
def build_trail_stair():
    rng = random.Random(102)
    size = [9, 9, 10]
    cells = {}
    entities = {}
    fill_box(cells, (0, 0, 0), (8, 8, 9), VOID)

    # 阶梯从 Z=9 (Y=1) 攀升至 Z=0 (Y=4)，步进 dy=+3
    # Z=8..9: Y=1
    # Z=5..7: Y=2
    # Z=2..4: Y=3
    # Z=0..1: Y=4
    steps = [
        (8, 9, 1),
        (5, 7, 2),
        (2, 4, 3),
        (0, 1, 4),
    ]

    for z_min, z_max, y_floor in steps:
        for z in range(z_min, z_max + 1):
            # 铺路 (X=3..5)
            for x in (3, 4, 5):
                cells[x, y_floor, z] = random_paving(rng)
                for dy in range(1, 4):
                    cells[x, y_floor + dy, z] = AIR
                # 底部实心防漏
                cells[x, y_floor - 1, z] = STONE

            # 阶梯起步采用石砖楼梯
            if z == z_max and y_floor > 1:
                for x in (3, 4, 5):
                    cells[x, y_floor, z] = stone_stair(facing="north") if rng.random() < 0.7 else mossy_stair(facing="north")

            # 西侧竹护栏 (X=2)
            cells[2, y_floor + 1, z] = BAMBOO_FENCE
            cells[2, y_floor, z] = STONE_BRICK

            # 东侧流水细渠 (X=6..7)
            cells[6, y_floor - 1, z] = MOSSY_COBBLE
            cells[7, y_floor - 1, z] = COBBLESTONE
            cells[6, y_floor, z] = WATER
            cells[7, y_floor, z] = MOSSY_COBBLE
            cells[6, y_floor + 1, z] = AIR
            cells[7, y_floor + 1, z] = AIR

    # 阶梯中间小平台放置矮石灯笼 (X=2, Z=5, Y=3)
    cells[2, 3, 5] = STONE_BRICK
    cells[2, 4, 5] = STONE_LANTERN
    cells[2, 5, 5] = AIR

    # 散落落樱
    cells[4, 3, 6] = white_petals(2)
    cells[5, 4, 3] = white_petals(1)

    # Jigsaw 端口
    # 底部入口 (Z=9, Y=1, X=4)
    add_joint(cells, entities, (4, 1, 9), "south", "trail_bottom", "trail_top", "empty", "minecraft:stone_bricks")
    # 顶部出口 (Z=0, Y=4, X=4)
    add_joint(cells, entities, (4, 4, 0), "north", "trail_top", "trail_bottom", "bridge", "minecraft:mossy_stone_bricks")

    add_footings(cells, entities, floor_y=1)
    return "trail_stair", size, cells, entities

# -------------------------------------------------------------
# 3. trail_bridge: 溪涧石板桥 (横穿溪谷、洗心石钵与竹出水口)
# -------------------------------------------------------------
def build_trail_bridge():
    rng = random.Random(103)
    size = [11, 9, 11]
    cells = {}
    entities = {}
    fill_box(cells, (0, 0, 0), (10, 8, 10), VOID)

    # 溪谷在 Z=4..6 横穿东(X=10)向西(X=0)，深在 Y=0..1
    for x in range(11):
        for z in range(4, 7):
            cells[x, 0, z] = MOSSY_COBBLE if rng.random() < 0.6 else COBBLESTONE
            cells[x, 1, z] = WATER
            for y in range(2, 6):
                cells[x, y, z] = AIR

    # 石板桥跨越溪谷 (X=4..6, Z=3..7, 桥面 Y=2..3)
    # 桥拱引道
    for x in (4, 5, 6):
        cells[x, 2, 2] = random_paving(rng)
        cells[x, 2, 8] = random_paving(rng)
        cells[x, 2, 3] = stone_stair(facing="north")
        cells[x, 2, 7] = stone_stair(facing="south")
        # 桥面平整 (Z=4..6)
        for z in range(4, 7):
            cells[x, 3, z] = smooth_slab("bottom") if x == 5 else stone_slab("bottom")
            cells[x, 2, z] = stone_slab("top")
            for y in range(4, 7):
                cells[x, y, z] = AIR

    # 桥两侧朱红木栏杆 (X=3 与 X=7)
    for z in range(3, 8):
        cells[3, 3, z] = LACQUER_FENCE
        cells[7, 3, z] = LACQUER_FENCE

    # 南北引道平整 (Z=0..2 与 Z=8..10)
    for z in list(range(0, 3)) + list(range(8, 11)):
        for x in (4, 5, 6):
            cells[x, 2, z] = random_paving(rng)
            for y in range(3, 6):
                cells[x, y, z] = AIR
            cells[x, 1, z] = STONE

    # 桥西头洗心石钵与竹出水口 (X=2, Z=8)
    cells[2, 2, 8] = MOSSY_COBBLE
    cells[2, 3, 8] = STONE_BASIN
    cells[1, 3, 8] = BAMBOO_FENCE
    cells[1, 4, 8] = bamboo_spout(facing="east")
    cells[3, 2, 8] = stepping_stone(2)

    # 桥东侧小石灯笼 (X=8, Z=3)
    cells[8, 2, 3] = STONE_BRICK
    cells[8, 3, 3] = STONE_LANTERN

    # Jigsaw 端口
    add_joint(cells, entities, (5, 2, 10), "south", "trail_bottom", "trail_top", "empty", "minecraft:stone_bricks")
    add_joint(cells, entities, (5, 2, 0), "north", "trail_top", "trail_bottom", "turn", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=1)
    return "trail_bridge", size, cells, entities

# -------------------------------------------------------------
# 4. trail_turn: 之字回折台 (转角平台、路旁小石龛与休憩坐石)
# -------------------------------------------------------------
def build_trail_turn():
    rng = random.Random(104)
    size = [11, 8, 11]
    cells = {}
    entities = {}
    fill_box(cells, (0, 0, 0), (10, 7, 10), VOID)

    # 入口从南 (X=5, Z=10, Y=2)，在中心 (X=4..7, Z=4..7) 扩展为平整转角台 (Y=2)
    # 出口向西折转 (X=0, Z=5, Y=2)
    for x in range(3, 8):
        for z in range(3, 8):
            cells[x, 2, z] = random_paving(rng)
            cells[x, 1, z] = STONE
            for y in range(3, 7):
                cells[x, y, z] = AIR

    # 南向通道 (Z=8..10, X=4..6)
    for z in range(8, 11):
        for x in (4, 5, 6):
            cells[x, 2, z] = random_paving(rng)
            cells[x, 1, z] = STONE
            for y in range(3, 7):
                cells[x, y, z] = AIR

    # 西向通道 (X=0..2, Z=4..6)
    for x in range(0, 3):
        for z in (4, 5, 6):
            cells[x, 2, z] = random_paving(rng)
            cells[x, 1, z] = STONE
            for y in range(3, 7):
                cells[x, y, z] = AIR

    # 东北角石壁挡墙与路边石龛 (X=7..9, Z=2..4)
    for x in range(7, 10):
        for z in range(2, 5):
            for y in range(1, 6):
                cells[x, y, z] = MOSSY_STONE_BRICK if rng.random() < 0.5 else STONE_BRICK

    # 在挡墙凹处供奉路边神龛 (Hokora)
    cells[7, 3, 3] = shrine_hokora(facing="west")
    cells[7, 2, 3] = STONE_BRICK
    # 神龛前小石灯与踏步石
    cells[6, 3, 3] = stepping_stone(3)
    cells[6, 3, 2] = STONE_LANTERN
    cells[6, 2, 2] = MOSSY_COBBLE

    # 平台边沿散落落樱
    cells[5, 3, 5] = white_petals(3)
    cells[4, 3, 6] = white_petals(1)

    # Jigsaw 端口
    # 南侧入口
    add_joint(cells, entities, (5, 2, 10), "south", "trail_bottom", "trail_top", "empty", "minecraft:stone_bricks")
    # 西侧出口 (面对 West)
    add_joint(cells, entities, (0, 2, 5), "west", "trail_top", "trail_bottom", "torii", "minecraft:stone_bricks")

    add_footings(cells, entities, floor_y=1)
    return "trail_turn", size, cells, entities

# -------------------------------------------------------------
# 5. trail_torii_ascent: 朱红鸟居石阶 (穿行中段鸟居，拾级而上)
# -------------------------------------------------------------
def build_trail_torii_ascent():
    rng = random.Random(105)
    size = [9, 10, 11]
    cells = {}
    entities = {}
    fill_box(cells, (0, 0, 0), (8, 9, 10), VOID)

    # 阶梯攀爬：从 Z=10 (Y=1) 爬升至 Z=0 (Y=4)
    steps = [
        (8, 10, 1),
        (5, 7, 2),
        (3, 4, 3),
        (0, 2, 4),
    ]
    for z_min, z_max, y_floor in steps:
        for z in range(z_min, z_max + 1):
            for x in (3, 4, 5):
                cells[x, y_floor, z] = random_paving(rng)
                cells[x, y_floor - 1, z] = STONE
                for dy in range(1, 5):
                    cells[x, y_floor + dy, z] = AIR
            if z == z_max and y_floor > 1:
                for x in (3, 4, 5):
                    cells[x, y_floor, z] = stone_stair(facing="north")

            # 左右护栏
            cells[2, y_floor + 1, z] = BAMBOO_FENCE
            cells[6, y_floor + 1, z] = BAMBOO_FENCE

    # 在 Z=5 (台阶平台 Y=2) 跨立朱漆木鸟居 (Torii)
    # 两侧石质柱础 (X=2 与 X=6, Y=2)
    cells[2, 2, 5] = STONE_BRICK
    cells[6, 2, 5] = STONE_BRICK
    # 朱漆柱身 (Y=3..5)
    for y in (3, 4, 5):
        cells[2, y, 5] = LACQUER_LOG
        cells[6, y, 5] = LACQUER_LOG
    # 贯 (Nuki 穿梁, Y=4)
    for x in range(2, 7):
        cells[x, 4, 5] = LACQUER_SLAB
    # 笠木与岛木 (Kasagi 上梁与黑瓦顶, Y=6)
    for x in range(1, 8):
        cells[x, 6, 5] = KAWARA_ROOF
    # 檐角起翘 (Y=7, X=0 与 X=8)
    cells[0, 7, 5] = kawara_stair(facing="west")
    cells[8, 7, 5] = kawara_stair(facing="east")

    # 鸟居两侧石灯笼 (X=2 与 X=6, Z=6, Y=2)
    cells[2, 3, 6] = STONE_LANTERN
    cells[6, 3, 6] = STONE_LANTERN

    # Jigsaw 端口
    add_joint(cells, entities, (4, 1, 10), "south", "trail_bottom", "trail_top", "empty", "minecraft:stone_bricks")
    add_joint(cells, entities, (4, 4, 0), "north", "trail_top", "trail_bottom", "summit", "minecraft:mossy_stone_bricks")

    add_footings(cells, entities, floor_y=1)
    return "trail_torii_ascent", size, cells, entities

# -------------------------------------------------------------
# 6. trail_summit_stair: 登顶幽径 (岩壁与密植白樱完全遮蔽聚落视线)
# -------------------------------------------------------------
def build_trail_summit_stair():
    rng = random.Random(106)
    size = [11, 12, 13]
    cells = {}
    entities = {}
    fill_box(cells, (0, 0, 0), (10, 11, 12), VOID)

    # 阶梯攀爬：从 Z=12 (Y=1) 攀升至 Z=0 (Y=5)
    # 两侧耸立天然巨石壁 (X=0..2 与 X=8..10, Y=1..9) 彻底挡住横向和前方视线
    for z in range(13):
        # 阶梯高度映射
        yf = 1 + int((12 - z) * 4 / 12)
        # 中央狭窄石阶 (X=4..6)
        for x in (4, 5, 6):
            cells[x, yf, z] = random_paving(rng)
            cells[x, yf - 1, z] = STONE
            # 仅预留头顶 3 格净空
            for dy in range(1, 4):
                cells[x, yf + dy, z] = AIR
        if z % 3 == 0 and yf > 1:
            for x in (4, 5, 6):
                cells[x, yf, z] = stone_stair(facing="north")

        # 左右高耸石壁 (X=0..3 与 X=7..10)
        for y in range(yf, yf + 6):
            for x in (1, 2, 3):
                cells[x, y, z] = MOSSY_STONE_BRICK if rng.random() < 0.4 else STONE
            for x in (7, 8, 9):
                cells[x, y, z] = MOSSY_STONE_BRICK if rng.random() < 0.4 else STONE

        # 头顶茂密樱叶穹顶 (Y=yf+4..yf+5, X=3..7) 挡住天空与前方聚落远景
        for x in (3, 4, 5, 6, 7):
            cells[x, yf + 4, z] = CHERRY_LEAVES
            cells[x, yf + 5, z] = CHERRY_LEAVES

    # 在阶梯石壁夹道中点缀小石灯引路
    cells[3, 3, 9] = STONE_LANTERN
    cells[7, 5, 4] = STONE_LANTERN

    # 阶梯地面落樱
    cells[5, 2, 10] = white_petals(2)
    cells[4, 4, 6] = white_petals(3)
    cells[6, 5, 2] = white_petals(4)

    # Jigsaw 端口
    add_joint(cells, entities, (5, 1, 12), "south", "trail_bottom", "trail_top", "empty", "minecraft:stone_bricks")
    # 顶部出口通往大鸟居山门 (Z=0, Y=5, X=5)
    add_joint(cells, entities, (5, 5, 0), "north", "trail_top", "trail_bottom", "grand_torii", "minecraft:mossy_stone_bricks")

    add_footings(cells, entities, floor_y=1)
    return "trail_summit_stair", size, cells, entities

# -------------------------------------------------------------
# 7. trail_grand_torii: 聚落山门大鸟居 (世外桃源入口、注连绳、灵狐石像、豁然开朗)
# -------------------------------------------------------------
def build_trail_grand_torii():
    rng = random.Random(107)
    size = [15, 14, 13]
    cells = {}
    entities = {}
    fill_box(cells, (0, 0, 0), (14, 13, 12), VOID)

    # 1. 宽阔前庭广场 (X=1..13, Z=0..11, Y=2)
    for x in range(1, 14):
        for z in range(12):
            cells[x, 2, z] = random_paving(rng)
            cells[x, 1, z] = STONE
            for y in range(3, 8):
                cells[x, y, z] = AIR

    # 2. 宏伟神山大鸟居 (位于 Z=7, 跨度 X=4..10, 柱子在 X=4 与 X=10)
    # 石雕柱础 (Y=2..3)
    for x in (4, 10):
        cells[x, 2, 7] = STONE_BRICK
        cells[x, 3, 7] = STONE_BRICK
    # 粗壮朱漆圆木柱身 (Y=4..8)
    for y in range(4, 9):
        cells[4, y, 7] = LACQUER_LOG
        cells[10, y, 7] = LACQUER_LOG

    # 贯 (Nuki 下横梁, Y=6)
    for x in range(3, 12):
        cells[x, 6, 7] = LACQUER_PLANKS
    # 岛木与笠木 (Shimaki 与 Kasagi 主横梁, Y=9)
    for x in range(2, 13):
        cells[x, 9, 7] = LACQUER_PLANKS
    # 顶覆灰瓦大屋脊 (Y=10)
    for x in range(1, 14):
        cells[x, 10, 7] = KAWARA_ROOF
    # 飞檐微挑 (Y=11, X=0 与 X=14)
    cells[0, 11, 7] = kawara_stair(facing="west")
    cells[14, 11, 7] = kawara_stair(facing="east")

    # 梁下悬挂神圣注连绳与纸垂 (Shimenawa, Y=8, X=5..9)
    for x in range(5, 10):
        cells[x, 8, 7] = shimenawa(facing="south")

    # 檐下两盏和纸灯笼 (Y=8, X=3 与 X=11)
    cells[3, 8, 7] = PAPER_LANTERN_HANG
    cells[11, 8, 7] = PAPER_LANTERN_HANG

    # 3. 鸟居前左右守卫灵狐石像 (Fox Statues, Z=8, X=2 与 X=12)
    # 左座狐像（面向东侧道路）
    cells[2, 2, 8] = STONE_BRICK
    cells[2, 3, 8] = fox_statue(facing="east")
    # 右座狐像（面向西侧道路）
    cells[12, 2, 8] = STONE_BRICK
    cells[12, 3, 8] = fox_statue(facing="west")

    # 4. 广场落樱与踏步石装饰
    cells[6, 3, 5] = white_petals(4)
    cells[8, 3, 4] = white_petals(2)
    cells[7, 3, 9] = white_petals(3)
    cells[5, 3, 10] = stepping_stone(1)
    cells[9, 3, 10] = stepping_stone(0)

    # 5. Jigsaw 端口
    # 南侧入口（接登山幽径 trail_summit_stair，Z=12, Y=2, X=7）
    add_joint(cells, entities, (7, 2, 12), "south", "trail_bottom", "trail_top", "empty", "minecraft:stone_bricks")
    # 北侧出口（通往神狐聚落主街入口广场，Z=0, Y=2, X=7）
    add_joint(cells, entities, (7, 2, 0), "north", "village_entrance", "village_start", "village_plaza", "minecraft:polished_andesite")

    add_footings(cells, entities, floor_y=1)
    return "trail_grand_torii", size, cells, entities

# -------------------------------------------------------------
# 写入所有 NBT 蓝图
# -------------------------------------------------------------
def generate_all_structures():
    builders = [
        build_trail_start,
        build_trail_stair,
        build_trail_bridge,
        build_trail_turn,
        build_trail_torii_ascent,
        build_trail_summit_stair,
        build_trail_grand_torii
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
            "location": f"{NS}:cherry_mountain_trail/{location}",
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

def generate_pools():
    POOL_DIR.mkdir(parents=True, exist_ok=True)

    # 1. 结构起点池 (start): 默认以大鸟居为锚点山门向下延伸，豁然开朗
    write_json(POOL_DIR / "start.json", {
        "name": f"{NS}:cherry_mountain_trail/start",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("trail_grand_torii")
        ]
    })

    # 2. summit 池 (通往遮蔽视线的登顶幽径)
    write_json(POOL_DIR / "summit.json", {
        "name": f"{NS}:cherry_mountain_trail/summit",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("trail_summit_stair")
        ]
    })

    # 3. torii 池 (通往中段朱红鸟居石阶)
    write_json(POOL_DIR / "torii.json", {
        "name": f"{NS}:cherry_mountain_trail/torii",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("trail_torii_ascent")
        ]
    })

    # 4. turn 池 (通往之字回折台)
    write_json(POOL_DIR / "turn.json", {
        "name": f"{NS}:cherry_mountain_trail/turn",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("trail_turn")
        ]
    })

    # 5. bridge 池 (通往跨溪石板桥)
    write_json(POOL_DIR / "bridge.json", {
        "name": f"{NS}:cherry_mountain_trail/bridge",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("trail_bridge")
        ]
    })

    # 6. stairs 池 (通往粗粝山阶)
    write_json(POOL_DIR / "stairs.json", {
        "name": f"{NS}:cherry_mountain_trail/stairs",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("trail_stair")
        ]
    })

    # 7. grand_torii 池
    write_json(POOL_DIR / "grand_torii.json", {
        "name": f"{NS}:cherry_mountain_trail/grand_torii",
        "fallback": "minecraft:empty",
        "elements": [
            pool_element("trail_grand_torii")
        ]
    })

    # 8. village_plaza 池 (由大鸟居直通聚落主入口大广场)
    write_json(POOL_DIR / "village_plaza.json", {
        "name": f"{NS}:cherry_mountain_trail/village_plaza",
        "fallback": "minecraft:empty",
        "elements": [
            {
                "weight": 1,
                "element": {
                    "element_type": "minecraft:single_pool_element",
                    "location": f"{NS}:fox_settlement/street_plaza",
                    "processors": f"{NS}:settlement_preserve_void",
                    "override_liquid_settings": "ignore_waterlogging",
                    "projection": "rigid"
                }
            }
        ]
    })

    # 9. footings 探底石柱池 (利用 settlement_footing 下扎支墩)
    write_json(POOL_DIR / "footings.json", {
        "name": f"{NS}:cherry_mountain_trail/footings",
        "fallback": "minecraft:empty",
        "elements": [
            feature_element("settlement_footing")
        ]
    })
    print("  -> 模板池 JSON 配置完成。")

# -------------------------------------------------------------
# 配置结构与结构集 (Structure & Structure Set)
# -------------------------------------------------------------
def generate_structure_config():
    struct_path = DATA / "worldgen/structure/cherry_mountain_trail.json"
    write_json(struct_path, {
        "type": "minecraft:jigsaw",
        "biomes": "#logos_dimension:has_structure/cherry_mountain_trail",
        "step": "surface_structures",
        "spawn_overrides": {},
        "terrain_adaptation": "none",
        "start_pool": f"{NS}:cherry_mountain_trail/start",
        "size": 7,
        "start_height": {
            "absolute": -1
        },
        "project_start_to_heightmap": "WORLD_SURFACE_WG",
        "use_expansion_hack": False,
        "max_distance_from_center": 96
    })

    set_path = DATA / "worldgen/structure_set/cherry_mountain_trail.json"
    write_json(set_path, {
        "structures": [
            {
                "structure": f"{NS}:cherry_mountain_trail",
                "weight": 1
            }
        ],
        "placement": {
            "type": "minecraft:random_spread",
            "salt": 1572398,
            "spacing": 28,
            "separation": 12,
            "spread_type": "linear"
        }
    })

    # 挂载生物群系标签
    tag_path = DATA / "tags/worldgen/biome/has_structure/cherry_mountain_trail.json"
    write_json(tag_path, {
        "values": [
            f"{NS}:cloudsea_white_cherry"
        ]
    })
    print("  -> 结构与结构集注册完成: cherry_mountain_trail")

def main():
    print("=== 开始生成灵狐樱野盘山古道 (Phase 3) ===")
    generate_all_structures()
    generate_pools()
    generate_structure_config()
    print("=== 灵狐樱野盘山古道生成完毕 ===")

if __name__ == "__main__":
    main()
