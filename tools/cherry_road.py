"""灵狐樱野的盘山路：日式野外风、石砖半砖路基、竹护栏。

做法：生成一个"螺旋盘山路"结构模板（NBT），在樱野里稀有生成；
形状是绕坡两圈的坡道，外沿竹护栏，转角放石灯笼，路基用磨制石砖半砖 + 苔石砖半砖混铺。
"""
from pathlib import Path
import gzip, io, json, struct, random

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "src/main/resources/data/logos_dimension"
STRUCT = DATA / "structure/cherry_road.nbt"
NS = "logos_dimension"

SIZE = 49          # 幻方尺寸，中心在 (24,·,24)
H = 22             # 总升高度
WIDTH = 3          # 路面宽（2 石砖 + 1 护栏边）

# ---------- 极简 NBT 写入 ----------
def _s(v):
    b = v.encode("utf-8")
    return struct.pack(">H", len(b)) + b

def _tag(tid, name, payload):
    return struct.pack(">b", tid) + _s(name) + payload

def nbt_compound(items):
    out = struct.pack(">b", 10) + _s("")
    for name, tid, payload in items:
        out += _tag(tid, name, payload)
    return out + struct.pack(">b", 0)

def nbt_list(tid, elems):
    return struct.pack(">b", tid) + struct.pack(">i", len(elems)) + b"".join(elems)

def nbt_int(v):    return struct.pack(">i", v)
def nbt_byte(v):   return struct.pack(">b", v)
def nbt_string(v): return _s(v)

# ---------- 方块调色板 ----------
PALETTE = [
    {"Name": "minecraft:stone_brick_slab", "Properties": {"type": "bottom", "waterlogged": "false"}},
    {"Name": "minecraft:mossy_stone_brick_slab", "Properties": {"type": "bottom", "waterlogged": "false"}},
    {"Name": "minecraft:polished_andesite_slab", "Properties": {"type": "bottom", "waterlogged": "false"}},
    {"Name": "minecraft:stone_bricks"},
    {"Name": "minecraft:mossy_stone_bricks"},
    {"Name": "minecraft:bamboo_fence"},
    {"Name": "minecraft:lantern", "Properties": {"hanging": "false", "waterlogged": "false"}},
    {"Name": "minecraft:stone_brick_wall"},
]

def build():
    rng = random.Random("cherry-road")
    blocks = []
    filled = {}

    def put(x, y, z, state):
        if not (0 <= x < SIZE and 0 <= y < H + 6 and 0 <= z < SIZE):
            return
        filled[(x, y, z)] = state

    # 之字形盘山：两段直坡 + 一个回折平台，比螺旋更像山道
    lane = 3                      # 路面宽
    leg = 26                      # 每段长度
    rise = 12                     # 每段爬升
    for leg_i in range(2):
        y0 = leg_i * rise
        z = 8 + (leg * leg_i)
        xs = 8 if leg_i % 2 == 0 else 8 + lane + 3
        for step in range(leg):
            y = y0 + int(step * rise / leg)
            x = xs if leg_i % 2 == 0 else xs + leg - 1 - step
            # 路面：半砖为主，偶尔整块石砖做台阶感
            for d in range(lane):
                yy = y
                roll = rng.random()
                if roll < .70: st = 0
                elif roll < .90: st = 1
                else: st = 2
                put(x, yy, z + d, st)
                # 下面垫实，避免悬空
                put(x, yy - 1, z + d, 4 if rng.random() < .3 else 3)
            # 外侧竹护栏（每个台阶一根柱子 + 横栏）
            outer = z - 1 if leg_i == 0 else z + lane
            put(x, y + 1, outer, 5)
            # 偶尔放石灯笼
            if step % 11 == 0:
                put(x, y, outer, 6)
        # 回折平台：铺宽一点，转角处放灯笼与坐石
        if leg_i == 0:
            py0 = y0 + rise
            for dx in range(6):
                for dz in range(lane + 3):
                    put(xs + leg - 1 - dx, py0, z + dz, 0)
            put(xs + leg - 1, py0 + 1, z + lane + 1, 5)
            put(xs + leg - 1, py0 + 1, z + lane + 2, 5)
            put(xs + leg - 1, py0, z + lane, 6)

    for pos, state in filled.items():
        blocks.append((pos, state))
    return blocks

def write_nbt(blocks):
    size = [SIZE, H + 6, SIZE]
    pal_items = []
    for p in PALETTE:
        items = [(("Name"), 8, nbt_string(p["Name"]))]
        if "Properties" in p:
            props = b""
            for k, v in p["Properties"].items():
                props += _tag(8, k, nbt_string(v))
            props += struct.pack(">b", 0)
            items.append(("Properties", 10, props))
        pal_items.append(nbt_compound(items))

    block_items = []
    for pos, state in sorted(blocks, key=lambda b: b[0][1]):
        payload = struct.pack(">i", pos[0]) + struct.pack(">i", pos[1]) + struct.pack(">i", pos[2])
        block_items.append(nbt_compound([
            ("pos", 9, nbt_list(3, [struct.pack(">i", c) for c in pos])),
            ("state", 3, nbt_int(state)),
        ]))

    root = nbt_compound([
        ("size", 9, nbt_list(3, [struct.pack(">i", s) for s in size])),
        ("palette", 9, nbt_list(10, pal_items)),
        ("blocks", 9, nbt_list(10, block_items)),
        ("entities", 9, nbt_list(10, [])),
        ("DataVersion", 3, nbt_int(4790)),
    ])
    STRUCT.parent.mkdir(parents=True, exist_ok=True)
    with open(STRUCT, "wb") as f:
        f.write(gzip.compress(root))
    return len(block_items)

def write_json():
    def w(path, obj):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    w(DATA / "worldgen/template_pool/cherry_road/start.json", {
        "name": "logos_dimension:cherry_road/start",
        "fallback": "minecraft:empty",
        "elements": [{
            "weight": 1,
            "element": {
                "element_type": "minecraft:single_pool_element",
                "location": "logos_dimension:cherry_road",
                "processors": "minecraft:empty",
                "projection": "rigid",
            },
        }],
    })

    w(DATA / "worldgen/structure/cherry_road.json", {
        "type": "minecraft:jigsaw",
        "biomes": "#logos_dimension:has_structure/cherry_road",
        "step": "surface_structures",
        "spawn_overrides": {},
        "terrain_adaptation": "beard_thin",
        "start_pool": "logos_dimension:cherry_road/start",
        "size": 1,
        "start_height": {"absolute": 0},
        "project_start_to_heightmap": "WORLD_SURFACE_WG",
        "use_expansion_hack": False,
        "max_distance_from_center": 80,
    })

    w(DATA / "worldgen/structure_set/cherry_road.json", {
        "structures": [{"structure": "logos_dimension:cherry_road", "weight": 1}],
        "placement": {
            "type": "minecraft:random_spread",
            "salt": 1572398,
            "spacing": 26,
            "separation": 10,
            "spread_type": "linear",
        },
    })

    w(DATA / "tags/worldgen/biome/has_structure/cherry_road.json", {
        "values": ["logos_dimension:cloudsea_white_cherry"],
    })

def main():
    blocks = build()
    n = write_nbt(blocks)
    write_json()
    print(f"盘山路结构写好：{n} 个方块")

if __name__ == "__main__":
    main()
