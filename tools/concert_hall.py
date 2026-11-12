"""魔女之森 · 星辰花之都 —— 灵猫大音乐厅（Concert Hall）结构生成器。

生成内容：
- data/logos_dimension/structure/concert_hall/tile_x_y_z.nbt  32³ 拼图分块模板
- data/logos_dimension/structure/concert_hall/concert_hall_full.nbt  完整单文件蓝图（供检视与测试）
- data/logos_dimension/worldgen/template_pool/concert_hall/*.json  拼图模板池
- data/logos_dimension/worldgen/processor_list/concert_hall_preserve.json  结构虚空忽略处理器
- data/logos_dimension/worldgen/structure/concert_hall.json  世界生成结构定义
- data/logos_dimension/worldgen/structure_set/concert_hall.json  世界生成结构集
- data/logos_dimension/tags/worldgen/biome/has_structure/concert_hall.json  群系标签
- data/logos_dimension/concert_hall_manifest.json  乐团席位、管风琴链路与唯一地板审计清单

设计要点：
1. 建筑风格参考设定集《魔女之森（Hexenwald）· 星辰花之都》与灵猫（Mystical Cat）一族：
   外立面采用白大理石（方解石、平滑石英、石英柱）、英伦古典红砖拱窗（参考灵猫门 catGate）、
   深色板岩与深色橡木孟莎屋顶、太玄黄铜与金饰山墙；南北山墙均塑造成标志性的「灵猫双耳山墙」
   （内嵌暖白/粉色耳廓、暖黄蛙明灯猫眼与五瓣星辰花玫瑰花窗），门廊两侧端坐白石灵猫守护雕像。
2. 室内采用经典鞋盒式交响音乐厅（Shoebox Concert Hall）声学比例：
   高耸的藻井声学天顶、星辰花水晶吊灯、胡桃木（深色橡木）与云杉声学护墙板、二层红丝绒王室包厢回廊、
   精简的三排池座，确保管风琴客户端射线声学（OrganRoom / RoomAcoustics）自动识别为封闭大厅混响。
3. 乐团所在的舞台地板（含主舞台与三层木管/铜管/打击乐阶梯台面及踏步）唯一使用
   `minecraft:bamboo_planks`（竹木板），该方块在整座音乐厅的其它任何位置均绝不使用（0 次），
   以便后续直接替换为专属舞台方块。
4. 乐团的座位都是星辰花琴凳（starbloom_bench）：钢琴、大键琴、钢片琴、竖琴、管风琴前的琴凳和那件乐器关联（坐上去的就是它的演奏者）；
   弦乐、木管、铜管声部的乐谱架只放乐谱，琴凳本身加入乐团，由坐在上面、手拿乐器的人或生物演奏（docs/ORCHESTRA.md 第 14 节）。
   测试场景只要完整的单文件蓝图：python tools/concert_hall.py --full-only <输出.nbt>（同时写同名 .litematic；不写分块、世界生成与 builds/）。
5. 交响乐团按现代标准交响乐席位（Stokowski / 美式标准席位）完整摆放现有全部 15 种乐器，
   北立面正中建造五塔对称式大型交响管风琴，演奏台（hexen_organ_console）通过连续风箱（organ_wind_chest）
   直通全部锡管（organ_tin_pipe）、木管（organ_wood_pipe）与水平齐奏皇家簧管（organ_trumpet_pipe）。
"""
from collections import deque, Counter
from pathlib import Path
import gzip
import json
import math
import shutil
import struct
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from blueprint_nbt import read_nbt, write_structure  # noqa: E402
from elven_habitation import fix_connectable_blockstates  # noqa: E402

R = ROOT / "src/main/resources"
D = R / "data/logos_dimension"
NS = "logos_dimension:"
DATA_VERSION = 4790

# 乐团舞台唯一专用地板方块（严禁在舞台地板以外的任何地方使用）
ORCHESTRA_FLOOR_BLOCK = "minecraft:bamboo_planks"

# 分块尺寸与坐标范围：X ∈ [-48, 47], Y ∈ [-4, 59], Z ∈ [-48, 47] -> 3 × 2 × 3 共 18 个 32³ 分块
TILE = 32
ORIGIN = (-48, -4, -48)
GRID = (3, 2, 3)


class _Long(int):
    """NBT TAG_Long (tag 4) 包装类型。"""


class _LongArray(list):
    """NBT TAG_Long_Array (tag 12) 包装类型。"""


def pack_block_pos(x: int, y: int, z: int) -> _Long:
    """按 Minecraft BlockPos.asLong(x, y, z) 规则打包为有符号 64 位整数（TAG_Long）。"""
    u = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)
    if u >= (1 << 63):
        u -= (1 << 64)
    return _Long(u)


def write_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def state(name: str, **props) -> dict:
    full = name if ":" in name else NS + name
    if not props:
        return {"Name": full}
    return {"Name": full, "Properties": {k: str(v).lower() for k, v in props.items()}}


class HallBuilder:
    """音乐厅三维体素与方块实体构建器。"""

    def __init__(self):
        self.cells: dict[tuple[int, int, int], dict] = {}
        self.entities: dict[tuple[int, int, int], dict] = {}
        self.orchestra_floor_positions: set[tuple[int, int, int]] = set()
        self.instrument_placements: list[dict] = []
        self.podium_pos: tuple[int, int, int] | None = None
        self.bound_members: list[tuple[int, int, int]] = []
        self.chair_placements: list[dict] = []
        self.bench_placements: list[dict] = []

    def put(self, x: int, y: int, z: int, name: str, **props):
        s = state(name, **props)
        if s["Name"] == ORCHESTRA_FLOOR_BLOCK and (x, y, z) not in self.orchestra_floor_positions:
            raise ValueError(f"禁止在乐团地板区域外使用 {ORCHESTRA_FLOOR_BLOCK}: {(x, y, z)}")
        self.cells[(x, y, z)] = s
        self.entities.pop((x, y, z), None)

    def put_chair(self, x: int, y: int, z: int, look_dir: str, material: str = "minecraft:dark_oak_stairs", role: str = ""):
        """放置充当座椅的楼梯方块：look_dir 为坐姿面朝方向，自动取反作为楼梯靠背 facing！"""
        backrest_facing = {"north": "south", "south": "north", "east": "west", "west": "east"}[look_dir]
        self.put(x, y, z, material, facing=backrest_facing, half="bottom", shape="straight", waterlogged=False)
        self.chair_placements.append(
            {"pos": (x, y, z), "look_dir": look_dir, "facing": backrest_facing, "role": role}
        )

    def put_bench(self, x: int, y: int, z: int, look_dir: str, role: str = "", bind: bool = False,
                  seat_for: str | None = None, link: str | None = None, duet: bool = False):
        """放置星辰花琴凳：look_dir 为坐的人面朝的方向（即琴凳的 facing）。
        seat_for：手持乐器声部的座位（坐上去的人拿这件乐器演奏），bind 时琴凳本身加入指挥台的乐团；
        link：正前方要关联的方块乐器（钢琴、大键琴、钢片琴、竖琴、管风琴演奏台），由 verify_and_audit 核对关联规则；
        duet：星辰花双人琴凳（两格宽，另一格在坐的人右手边即 look_dir 顺时针方向，坐的人坐在两格交界），两格宽的钢琴前用。"""
        if duet:
            sx, sz = self._cw(look_dir)
            self.put(x, y, z, "starbloom_duet_bench", facing=look_dir, plays="none", side="false")
            self.put(x + sx, y, z + sz, "starbloom_duet_bench", facing=look_dir, plays="none", side="true")
        else:
            self.put(x, y, z, "starbloom_bench", facing=look_dir, plays="none")
        self._inst_be(x, y, z, None, bind=bind)
        self.bench_placements.append({"pos": (x, y, z), "look_dir": look_dir, "role": role, "seat_for": seat_for, "link": link,
                                      "duet": duet})
        if seat_for:
            self.instrument_placements.append(
                {"kind": f"starbloom_bench[{seat_for}]", "section": role, "pos": [x, y, z], "facing": look_dir, "bound": bind}
            )

    def put_orchestra_floor(self, x: int, y: int, z: int):
        """放置乐团舞台专用地板方块，并登记其坐标。"""
        self.orchestra_floor_positions.add((x, y, z))
        self.cells[(x, y, z)] = state(ORCHESTRA_FLOOR_BLOCK)
        self.entities.pop((x, y, z), None)

    def fill(self, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int, name: str, **props):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.put(x, y, z, name, **props)

    def air(self, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int):
        self.fill(x0, y0, z0, x1, y1, z1, "minecraft:air")

    # —— 指挥台、乐器与方块实体精确放置辅助 ——

    def place_podium(self, x: int, y: int, z: int, facing: str = "north"):
        """放置两格宽指挥台（hexen_podium）：主块 side=false，指挥右手边侧块 side=true。"""
        rx, rz = self._cw(facing)
        self.put(x, y, z, "hexen_podium", facing=facing, side="false", score="false")
        self.put(x + rx, y, z + rz, "hexen_podium", facing=facing, side="true", score="false")
        self.podium_pos = (x, y, z)
        self.entities[(x, y, z)] = {
            "id": "logos_dimension:podium",
            "origin": pack_block_pos(x, y, z),
            "members": [],
            "masks": [],
            "locked": [],
            "tempo": 100,
            "gains": [0] * 16,
            "mute": 0,
            "solo": 0,
            "powered": False,
        }

    def _inst_be(self, x: int, y: int, z: int, extra: dict | None = None, bind: bool = True):
        be = {
            "id": "logos_dimension:instrument",
            "part_mask": 65535,
            "stop_with_signal": False,
            "powered": False,
        }
        if bind and self.podium_pos is not None:
            if len(self.bound_members) >= 32:
                raise ValueError(f"指挥台最多绑定 32 件乐器，尝试绑定第 {len(self.bound_members) + 1} 件: {(x, y, z)}")
            px, py, pz = self.podium_pos
            be["origin"] = pack_block_pos(x, y, z)
            be["orchestra"] = pack_block_pos(px, py, pz)
            self.bound_members.append((x, y, z))
            podium_be = self.entities[self.podium_pos]
            podium_be["members"].append(pack_block_pos(x, y, z))
            podium_be["masks"].append(0)
            podium_be["locked"].append(0)
        if extra:
            be.update(extra)
        self.entities[(x, y, z)] = be

    @staticmethod
    def _cw(facing: str) -> tuple[int, int]:
        return {"north": (1, 0), "east": (0, 1), "south": (-1, 0), "west": (0, -1)}[facing]

    @staticmethod
    def _back(facing: str) -> tuple[int, int]:
        return {"north": (0, 1), "east": (-1, 0), "south": (0, -1), "west": (1, 0)}[facing]

    def place_grand_piano(self, x: int, y: int, z: int, facing: str, section: str, bind: bool = True):
        """放置 2×2 星辰花三角钢琴（part=0 为主块，带方块实体）。"""
        sx, sz = self._cw(facing)
        bx, bz = self._back(facing)
        offsets = [(0, 0), (sx, sz), (bx, bz), (sx + bx, sz + bz)]
        for part, (dx, dz) in enumerate(offsets):
            self.put(x + dx, y, z + dz, "starbloom_grand_piano", facing=facing, part=part)
        self._inst_be(x, y, z, bind=bind)
        self.instrument_placements.append(
            {"kind": "starbloom_grand_piano", "section": section, "pos": [x, y, z], "facing": facing, "bound": bind}
        )

    def place_organ_console(self, x: int, y: int, z: int, facing: str, section: str, bind: bool = True):
        """放置 3 宽 × 2 高管风琴演奏台（wide=true，主块在下排正中；part 位：1 顺时针一格、2 上一格、4 逆时针一格；
        全部 10 个音栓默认全开 stops=1023）。"""
        sx, sz = self._cw(facing)
        for part in (0, 1, 4, 2, 3, 6):
            side = 1 if (part & 1) else -1 if (part & 4) else 0
            dy = 1 if (part & 2) else 0
            self.put(x + side * sx, y + dy, z + side * sz, "hexen_organ_console", facing=facing, part=part, wide="true")
        self._inst_be(x, y, z, {"stops": 1023, "reverb": 0}, bind=bind)
        self.instrument_placements.append(
            {"kind": "hexen_organ_console", "section": section, "pos": [x, y, z], "facing": facing, "bound": bind}
        )

    def place_tall_instrument(self, x: int, y: int, z: int, block_name: str, facing: str, section: str, bind: bool = True):
        """放置两格高乐器：灵猫竖琴、管钟、大提琴、低音提琴（lower 为主块）。"""
        self.put(x, y, z, block_name, facing=facing, half="lower")
        self.put(x, y + 1, z, block_name, facing=facing, half="upper")
        self._inst_be(x, y, z, bind=bind)
        self.instrument_placements.append(
            {"kind": block_name, "section": section, "pos": [x, y, z], "facing": facing, "bound": bind}
        )

    def place_harpsichord(self, x: int, y: int, z: int, facing: str, section: str, bind: bool = True):
        """放置 1×2 大键琴（tail=false 为主块，琴尾向 facing 反方向伸出一格）。"""
        bx, bz = self._back(facing)
        self.put(x, y, z, "hexen_harpsichord", facing=facing, tail="false")
        self.put(x + bx, y, z + bz, "hexen_harpsichord", facing=facing, tail="true")
        self._inst_be(x, y, z, bind=bind)
        self.instrument_placements.append(
            {"kind": "hexen_harpsichord", "section": section, "pos": [x, y, z], "facing": facing, "bound": bind}
        )

    def place_single_instrument(self, x: int, y: int, z: int, block_name: str, facing: str, section: str, bind: bool = True):
        """放置单格乐器：定音鼓、钢片琴、钟琴。"""
        self.put(x, y, z, block_name, facing=facing)
        self._inst_be(x, y, z, bind=bind)
        self.instrument_placements.append(
            {"kind": block_name, "section": section, "pos": [x, y, z], "facing": facing, "bound": bind}
        )

    def place_music_stand(self, x: int, y: int, z: int, facing: str, holds: str, section: str, bind: bool = True):
        """放置乐谱架（holds=手持乐器名，并在方块实体内写入对应乐器物品栈）。"""
        self.put(x, y, z, "hexen_music_stand", facing=facing, holds=holds)
        extra = None
        if holds != "none":
            extra = {"instrument": {"id": f"logos_dimension:hexen_{holds}", "count": 1}}
        self._inst_be(x, y, z, extra, bind=bind and holds != "none")
        self.instrument_placements.append(
            {"kind": f"hexen_music_stand[{holds}]", "section": section, "pos": [x, y, z], "facing": facing, "bound": bind and holds != "none"}
        )

    def place_organ_pipe_column(self, x: int, y0: int, z: int, kind: str, height: int, facing: str = "south"):
        """在风箱正上方叠放一列锡管或木管，自动按高度设置 single / bottom / middle / top。"""
        block_name = f"organ_{kind}_pipe"
        for i in range(height):
            if height == 1:
                shape = "single"
            elif i == 0:
                shape = "bottom"
            elif i == height - 1:
                shape = "top"
            else:
                shape = "middle"
            self.put(x, y0 + i, z, block_name, facing=facing, shape=shape)


def build_foundation_and_plaza(b: HallBuilder):
    """地基、南侧星辰花前庭广场、英伦红砖绿篱花坛与双生灵猫守护石雕。"""
    # 1. 主体建筑与广场石质基座（Y = -4 .. 0）
    b.fill(-30, -4, -34, 30, -1, 42, "minecraft:stone_bricks")
    b.fill(-28, 0, -32, 28, 0, 42, "minecraft:polished_diorite")
    # 基座外缘雕花石砖压边
    for x in range(-30, 31):
        for z in (-34, 42):
            b.put(x, 0, z, "minecraft:chiseled_stone_bricks")
    for z in range(-34, 43):
        for x in (-30, 30):
            b.put(x, 0, z, "minecraft:chiseled_stone_bricks")

    # 2. 南侧星辰花前庭广场（Z = 26 .. 41）
    b.fill(-20, 0, 26, 20, 0, 41, "minecraft:smooth_quartz")
    for x in range(-20, 21):
        for z in range(26, 42):
            if (x + z) % 4 == 0:
                b.put(x, 0, z, "minecraft:calcite")
    # 广场中心五瓣星辰花镶嵌图案（中心 (0, 0, 33)）
    cx, cz = 0, 33
    for dx in range(-6, 7):
        for dz in range(-6, 7):
            dist = math.hypot(dx, dz)
            if dist <= 1.6:
                b.put(cx + dx, 0, cz + dz, "minecraft:gold_block")
            elif dist <= 5.5:
                angle = math.atan2(dz, dx)
                petal = math.cos(5 * (angle + math.pi / 2))
                if dist <= 3.6 + 1.6 * max(0.0, petal):
                    b.put(cx + dx, 0, cz + dz, "minecraft:lapis_block" if dist > 2.6 else "minecraft:chiseled_quartz_block")
            elif 5.5 < dist <= 6.4:
                b.put(cx + dx, 0, cz + dz, "logos_dimension:taixuan_brass_block")

    # 3. 广场东西两侧：灵猫门（catGate）风格的英伦红砖石隅花坛与修剪云杉绿篱
    for sign in (-1, 1):
        x_inner, x_outer = sign * 15, sign * 25
        x0, x1 = min(x_inner, x_outer), max(x_inner, x_outer)
        b.fill(x0, 1, 28, x1, 1, 39, "minecraft:bricks")
        for x in (x0, x1):
            for z in (28, 33, 39):
                b.put(x, 1, z, "minecraft:chiseled_stone_bricks")
                b.put(x, 2, z, "minecraft:stone_brick_wall")
                b.put(x, 3, z, "minecraft:lantern", hanging=False, waterlogged=False)
        # 花坛内草土、修剪整齐的云杉叶篱与星辰花
        b.fill(x0 + 1, 1, 29, x1 - 1, 1, 38, "minecraft:grass_block", snowy=False)
        for z in range(29, 39):
            b.put(x0 + 1, 2, z, "minecraft:spruce_leaves", distance=1, persistent=True, waterlogged=False)
            b.put(x1 - 1, 2, z, "minecraft:spruce_leaves", distance=1, persistent=True, waterlogged=False)
        for x in range(x0 + 2, x1 - 1):
            for z in range(30, 38):
                if (x + z) % 2 == 0:
                    b.put(x, 2, z, "logos_dimension:starflower")
                else:
                    b.put(x, 2, z, "minecraft:lily_of_the_valley")

    # 4. 广场南端迎宾石阶（Z = 42 .. 45, Y = -3 .. 0）
    for step, z in enumerate(range(42, 46)):
        y = -step
        for x in range(-12, 13):
            b.fill(x, -4, z, x, y - 1, z, "minecraft:stone_bricks")
            b.put(x, y, z, "minecraft:quartz_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)

    # 5. 通往门廊的宽阔白玉石阶（Z = 24 .. 26, Y = 1 .. 2）
    b.fill(-16, 1, 25, 16, 1, 25, "minecraft:quartz_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
    b.fill(-16, 1, 23, 16, 2, 24, "minecraft:smooth_quartz")
    b.fill(-16, 2, 24, 16, 2, 24, "minecraft:quartz_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
    # 中央红毯迎宾道
    for z in range(26, 42):
        if math.hypot(0, z - 33) > 6.5:
            for x in range(-2, 3):
                b.put(x, 0, z, "minecraft:red_wool")

    # 6. 门廊阶前双生「灵猫守护石雕」（X = -11 与 X = +11, Z = 26）
    for sx in (-11, 11):
        # 雕像基座（Y = 1 .. 3）
        b.fill(sx - 1, 1, 25, sx + 1, 2, 27, "minecraft:quartz_bricks")
        b.fill(sx - 1, 3, 25, sx + 1, 3, 27, "minecraft:chiseled_quartz_block")
        # 端坐的灵猫身体（Y = 4 .. 6）与前爪
        b.fill(sx - 1, 4, 25, sx + 1, 6, 26, "minecraft:smooth_quartz")
        b.put(sx - 1, 4, 27, "minecraft:quartz_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
        b.put(sx + 1, 4, 27, "minecraft:quartz_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
        b.put(sx, 5, 26, "logos_dimension:taixuan_brass_block")  # 胸前星辰花金铃
        # 灵猫头部（Y = 7 .. 8）与琥珀猫眼
        b.fill(sx - 1, 7, 25, sx + 1, 8, 26, "minecraft:smooth_quartz")
        b.put(sx - 1, 7, 26, "minecraft:ochre_froglight", axis="y")
        b.put(sx + 1, 7, 26, "minecraft:ochre_froglight", axis="y")
        # 灵猫尖耳（Y = 9）
        b.put(sx - 1, 9, 25, "minecraft:quartz_stairs", facing="east", half="bottom", shape="straight", waterlogged=False)
        b.put(sx + 1, 9, 25, "minecraft:quartz_stairs", facing="west", half="bottom", shape="straight", waterlogged=False)
        # 身后翘起的灵猫长尾（Z = 24, Y = 4 .. 8）
        for ty in range(4, 9):
            b.put(sx, ty, 24, "minecraft:quartz_pillar", axis="y")


def build_hall_shell_and_roof(b: HallBuilder):
    """音乐厅主体外墙、古典壁柱、尖拱彩窗、藻井天顶与南北双面灵猫耳山墙。"""
    # 主体范围：X ∈ [-22, 22], Z ∈ [-28, 22], 室内地面基础标高 Y = 1..2，檐口高 Y = 25
    b.fill(-22, 1, -28, 22, 1, 22, "minecraft:polished_diorite")
    b.fill(-21, 2, -27, 21, 24, 21, "minecraft:air")

    # 1. 东西两侧外墙（X = -22 与 X = 22）：白大理石墙体 + 英伦红砖壁龛 + 石隅柱 + 尖拱彩窗
    bay_centers = [-18, -11, -4, 3, 10]
    for wx in (-22, 22):
        inward = -1 if wx > 0 else 1
        outward = -inward
        for z in range(-28, 23):
            for y in range(2, 25):
                # 底部勒脚、腰线与顶部古典檐口
                if y in (2, 3):
                    b.put(wx, y, z, "minecraft:stone_bricks" if y == 2 else "minecraft:chiseled_stone_bricks")
                elif y in (10, 23, 24):
                    b.put(wx, y, z, "minecraft:chiseled_quartz_block" if y == 23 else "logos_dimension:taixuan_brass_block" if y == 24 else "minecraft:smooth_quartz")
                else:
                    b.put(wx, y, z, "minecraft:calcite" if (y + z) % 3 else "minecraft:smooth_quartz")

        # 壁柱（每 7 格一道古典凹槽石英柱 + 外侧飞扶壁墩）
        pilaster_zs = [-28, -22, -15, -8, -1, 6, 13, 16, 22]
        for pz in pilaster_zs:
            for y in range(2, 24):
                b.put(wx, y, pz, "minecraft:quartz_pillar", axis="y")
                # 外侧扶壁石隅（参考 catGate 的石隅交错）
                b.put(wx + outward, y, pz, "minecraft:stone_bricks" if y % 2 == 0 else "minecraft:chiseled_stone_bricks")
            b.put(wx, 23, pz, "logos_dimension:taixuan_brass_block")
            b.put(wx + outward, 24, pz, "minecraft:stone_brick_slab", type="bottom", waterlogged=False)

        # 五开间高耸尖拱星辰花彩窗与英伦红砖窗间墙
        for cz in bay_centers:
            # 窗框外圈红砖与平滑石英尖拱
            for dz in range(-2, 3):
                for y in range(4, 22):
                    if abs(dz) == 2 or y == 4:
                        b.put(wx, y, cz + dz, "minecraft:bricks")
                    else:
                        # 尖拱顶部收敛
                        arch_limit = 20 - abs(dz) * 2
                        if y <= arch_limit:
                            # 魔女之森夜蓝 + 星辰金彩窗
                            if dz == 0 and y in (11, 12, 17):
                                glass = "minecraft:yellow_stained_glass"
                            elif (y + dz) % 2 == 0:
                                glass = "minecraft:blue_stained_glass"
                            else:
                                glass = "minecraft:light_blue_stained_glass"
                            b.put(wx, y, cz + dz, glass)
                        elif y == arch_limit + 1:
                            b.put(wx, y, cz + dz, "minecraft:chiseled_quartz_block")
                        else:
                            b.put(wx, y, cz + dz, "minecraft:bricks")

        # 室内一侧（X = ±21）胡桃木与云杉声学护墙板及镀金壁柱
        for z in range(-26, 15):
            for y in range(2, 10):
                if z in pilaster_zs:
                    b.put(wx + inward, y, z, "minecraft:stripped_dark_oak_log", axis="y")
                elif all(abs(z - cz) > 1 for cz in bay_centers):
                    b.put(wx + inward, y, z, "minecraft:dark_oak_planks" if y in (2, 9) else "minecraft:spruce_planks")
            for y in range(11, 22):
                if z in pilaster_zs:
                    b.put(wx + inward, y, z, "minecraft:quartz_pillar", axis="y")
                    if y in (14, 21):
                        b.put(wx + inward, y, z, "logos_dimension:taixuan_brass_block")

    # 2. 北侧山墙与管风琴后殿背墙（Z = -28 .. -26）
    for x in range(-22, 23):
        for y in range(2, 25):
            if abs(x) in (22, 16, 11, 0):
                b.put(x, y, -28, "minecraft:quartz_pillar", axis="y")
            elif y in (2, 3):
                b.put(x, y, -28, "minecraft:stone_bricks")
            elif y == 24:
                b.put(x, y, -28, "logos_dimension:taixuan_brass_block")
            else:
                b.put(x, y, -28, "minecraft:calcite")
        # 管风琴后方深胡桃木共鸣背壁（Z = -27）
        if abs(x) <= 21:
            for y in range(2, 24):
                b.put(x, y, -27, "minecraft:dark_oak_planks" if abs(x) <= 13 else "minecraft:smooth_quartz")

    # 3. 南侧外墙（Z = 22）与古典六柱门廊（Z = 24）
    for x in range(-22, 23):
        for y in range(2, 25):
            if y == 24:
                b.put(x, y, 22, "logos_dimension:taixuan_brass_block")
            elif abs(x) in (22, 15, 8):
                b.put(x, y, 22, "minecraft:quartz_pillar", axis="y")
            elif 4 <= abs(x) <= 14 and 12 <= y <= 19:
                b.put(x, y, 22, "minecraft:blue_stained_glass" if (x + y) % 2 == 0 else "minecraft:yellow_stained_glass")
            else:
                b.put(x, y, 22, "minecraft:smooth_quartz" if (x + y) % 2 == 0 else "minecraft:calcite")
    # 南门三道拱门入口（Z = 22, X = -7..-5, -2..2, 5..7, Y = 3..8）
    for ax0, ax1 in ((-7, -5), (-2, 2), (5, 7)):
        b.air(ax0, 3, 22, ax1, 7, 22)
        for x in range(ax0, ax1 + 1):
            b.put(x, 8, 22, "minecraft:chiseled_quartz_block")
            b.put(x, 2, 22, "minecraft:red_wool" if ax0 == -2 else "minecraft:smooth_quartz")

    # 南门廊六根科林斯巨柱（Z = 24, X = -15, -9, -3, 3, 9, 15）
    for px in (-15, -9, -3, 3, 9, 15):
        b.put(px, 3, 24, "minecraft:chiseled_quartz_block")
        for y in range(4, 22):
            b.put(px, y, 24, "minecraft:quartz_pillar", axis="y")
        b.put(px, 22, 24, "logos_dimension:taixuan_brass_block")
        b.put(px, 23, 24, "minecraft:chiseled_quartz_block")
    # 门廊顶梁与天花（Z = 23..24, Y = 23..24）
    b.fill(-16, 23, 23, 16, 24, 24, "minecraft:smooth_quartz")
    b.fill(-16, 24, 24, 16, 24, 24, "logos_dimension:taixuan_brass_block")

    # 4. 室内藻井声学天顶（Y = 24, Z = -26 .. 21, X = -21 .. 21）
    for x in range(-21, 22):
        for z in range(-26, 22):
            if x % 6 == 0 or z % 7 == 0:
                b.put(x, 24, z, "minecraft:dark_oak_planks")
                if x % 6 == 0 and z % 7 == 0:
                    b.put(x, 23, z, "logos_dimension:taixuan_brass_block")
            elif (x % 6 in (1, 5)) or (z % 7 in (1, 6)):
                b.put(x, 24, z, "minecraft:smooth_quartz")
            else:
                b.put(x, 24, z, "minecraft:calcite")
                if abs(x % 6 - 3) == 0 and abs(z % 7 - 3) == 0:
                    b.put(x, 24, z, "minecraft:pearlescent_froglight", axis="y")

    # 5. 双坡古典深色板岩屋顶（Y = 25 .. 38）与黄铜正脊
    for z in range(-29, 25):
        for step in range(0, 23):
            y = 25 + (step * 12) // 22
            lx, rx = -22 + step, 22 - step
            mat_stair = "minecraft:deepslate_tile_stairs"
            mat_block = "minecraft:deepslate_tiles"
            if z in (-29, 24):
                mat_stair = "minecraft:quartz_stairs"
                mat_block = "minecraft:smooth_quartz"
            b.put(lx, y, z, mat_stair, facing="east", half="bottom", shape="straight", waterlogged=False)
            b.put(rx, y, z, mat_stair, facing="west", half="bottom", shape="straight", waterlogged=False)
            if y > 25:
                b.put(lx, y - 1, z, mat_block)
                b.put(rx, y - 1, z, mat_block)
        # 屋脊（X = 0, Y = 37..38）
        b.put(0, 37, z, "logos_dimension:taixuan_brass_block")
        b.put(0, 38, z, "logos_dimension:taixuan_brass_ridge_slab", type="bottom", waterlogged=False)

    # 封实南北山墙三角区（Z = -28 与 Z = 22，以及南门廊山墙 Z = 24）
    for gz, half_w in ((-28, 21), (22, 21), (24, 16)):
        for x in range(-half_w, half_w + 1):
            max_y = 25 + ((22 - abs(x)) * 12) // 22
            for y in range(25, max_y):
                b.put(x, y, gz, "minecraft:smooth_quartz" if (x + y) % 2 == 0 else "minecraft:calcite")

    # 6. 标志性「灵猫双耳山墙」（South Gable Z = 24 与 North Gable Z = -28）
    for gz in (24, -28):
        # 左右两侧高高翘起的灵猫尖耳（X = -12..-6 与 X = 6..12, Y = 31..42）
        for sign in (-1, 1):
            ear_cx = sign * 9
            for dy in range(0, 11):
                y = 31 + dy
                hw = max(0, 3 - dy // 3)
                for dx in range(-hw, hw + 1):
                    x = ear_cx + dx
                    if abs(dx) == hw or dy >= 8:
                        # 猫耳外廓：深色板岩与金边勾勒
                        b.put(x, y, gz, "logos_dimension:taixuan_brass_block" if dy == 10 else "minecraft:deepslate_tiles")
                    else:
                        # 猫耳内廓：粉白磨制花岗岩/方解石
                        b.put(x, y, gz, "minecraft:polished_granite" if dy < 6 else "minecraft:calcite")
            # 耳下暖黄猫眼窗（Y = 28..29）
            for dx in (-1, 0, 1):
                b.put(ear_cx + dx, 28, gz, "minecraft:ochre_froglight", axis="y")
                b.put(ear_cx + dx, 29, gz, "minecraft:ochre_froglight", axis="y")
                b.put(ear_cx + dx, 30, gz, "minecraft:gold_block")
                b.put(ear_cx + dx, 27, gz, "minecraft:dark_oak_planks")
        # 山墙正中五瓣星辰花玫瑰花窗（X = -3..3, Y = 27..33）
        for dx in range(-3, 4):
            for dy in range(-3, 4):
                d = math.hypot(dx, dy)
                if d <= 1.2:
                    b.put(dx, 30 + dy, gz, "minecraft:ochre_froglight", axis="y")
                elif d <= 2.6:
                    b.put(dx, 30 + dy, gz, "minecraft:yellow_stained_glass" if (dx + dy) % 2 == 0 else "minecraft:blue_stained_glass")
                elif d <= 3.5:
                    b.put(dx, 30 + dy, gz, "logos_dimension:taixuan_brass_block")


def build_foyer_and_galleries(b: HallBuilder):
    """南侧门厅（Foyer）、东西折返大楼梯、二层王室包厢回廊与精简池座。"""
    # 1. 门厅与主厅之间的隔音隔墙（Z = 15, X = -21 .. 21, Y = 2 .. 23）
    for x in range(-21, 22):
        for y in range(2, 24):
            if abs(x) in (21, 14, 7, 0):
                b.put(x, y, 15, "minecraft:quartz_pillar", axis="y")
            elif y == 10:
                b.put(x, y, 15, "logos_dimension:taixuan_brass_block")
            else:
                b.put(x, y, 15, "minecraft:smooth_quartz" if y > 10 else "minecraft:dark_oak_planks")

    # 门厅地面（Z = 16 .. 21, X = -21 .. 21, Y = 2）
    for x in range(-21, 22):
        for z in range(16, 22):
            if abs(x) <= 2:
                b.put(x, 2, z, "minecraft:red_wool")
            elif (x + z) % 2 == 0:
                b.put(x, 2, z, "minecraft:calcite")
            else:
                b.put(x, 2, z, "minecraft:smooth_quartz")

    # 门厅二层挑空走廊楼板（Z = 16 .. 21, X = -14 .. 14, Y = 10；东西两侧 X = ±15..±20 留给折返楼梯井）
    for x in range(-21, 22):
        for z in range(16, 22):
            if abs(x) == 21 or (abs(x) <= 14 and (abs(x) >= 6 or z <= 18)):
                b.put(x, 10, z, "minecraft:smooth_quartz")
                if abs(x) == 6 and z > 18:
                    b.put(x, 11, z, "minecraft:iron_bars")
            if z == 19 and abs(x) <= 6:
                b.put(x, 11, z, "minecraft:iron_bars")
            if abs(x) == 14 and 17 <= z <= 21:
                b.put(x, 11, z, "minecraft:iron_bars")

    # 门厅东西两侧通往二层包厢的古典双跑折返大楼梯（U-turn Grand Staircases）：
    # - 内侧第一跑（X = ±15..±17）：从 Z = 16 一层地面（Y = 2）向南拾级而上（Z = 17, 18, 19 -> Y = 3, 4, 5）
    # - 南端折返休息平台（Z = 20..21）：内半侧（X = ±15..±17）标高 Y = 6，中间过渡踏步（X = ±18）Y = 7，外半侧（X = ±19..±20）标高 Y = 7
    # - 外侧第二跑（X = ±18..±20）：从休息平台向北拾级而上（Z = 19, 18, 17 -> Y = 8, 9, 10），在 Z = 16 平稳抵达二层平台（Y = 10）！
    for sign in (-1, 1):
        inner_xs = [sign * x for x in (15, 16, 17)]
        outer_xs = [sign * x for x in (18, 19, 20)]
        all_xs = sorted(inner_xs + outer_xs)
        outward_dir = "east" if sign > 0 else "west"

        # 先将整个楼梯井上方（Z = 16..21, Y = 3..16）完全清空，保证每一级台阶头顶均有充足净空
        b.air(min(all_xs), 3, 16, max(all_xs), 16, 21)

        # 第一跑（内侧，向南上楼，facing="south"）
        for step_idx, z in enumerate((17, 18, 19)):
            y = 3 + step_idx
            for x in inner_xs:
                b.fill(x, 2, z, x, y - 1, z, "minecraft:quartz_bricks")
                b.put(x, y, z, "minecraft:quartz_stairs", facing="south", half="bottom", shape="straight", waterlogged=False)

        # 南端折返休息平台（Z = 20..21）
        for z in (20, 21):
            for x in inner_xs:
                b.fill(x, 2, z, x, 5, z, "minecraft:quartz_bricks")
                b.put(x, 6, z, "minecraft:smooth_quartz")
            mid_x = sign * 18
            b.fill(mid_x, 2, z, mid_x, 6, z, "minecraft:quartz_bricks")
            b.put(mid_x, 7, z, "minecraft:quartz_stairs", facing=outward_dir, half="bottom", shape="straight", waterlogged=False)
            for x in (sign * 19, sign * 20):
                b.fill(x, 2, z, x, 6, z, "minecraft:quartz_bricks")
                b.put(x, 7, z, "minecraft:smooth_quartz")

        # 第二跑（外侧，向北上楼，facing="north"）
        for step_idx, z in enumerate((19, 18, 17)):
            y = 8 + step_idx
            for x in outer_xs:
                b.fill(x, 2, z, x, y - 1, z, "minecraft:quartz_bricks")
                b.put(x, y, z, "minecraft:quartz_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)

        # 二层顶部抵达平台（Z = 16, X = ±18..±20, Y = 10）与雕花栏杆
        for x in outer_xs:
            b.fill(x, 2, 16, x, 9, 16, "minecraft:quartz_bricks")
            b.put(x, 10, 16, "minecraft:smooth_quartz")
        # 内外两跑之间的中隔护栏与灯柱（X = ±17 与 ±18 交界）
        b.put(sign * 17, 11, 16, "minecraft:chiseled_quartz_block")
        b.put(sign * 17, 12, 16, "minecraft:lantern", hanging=False, waterlogged=False)

    # 门厅接待台与魔女之森藏书角（使用附属 mod 的精灵家具方块装饰）
    for sign, facing in ((-1, "east"), (1, "west")):
        bx = sign * 12
        for z in (17, 18, 19):
            b.put(bx, 3, z, "logos_dimension:elven_bookshelf", facing=facing, half="lower")
            b.put(bx, 4, z, "logos_dimension:elven_bookshelf", facing=facing, half="upper")
        b.put(sign * 9, 3, 18, "logos_dimension:elven_reading_table", facing="south")
        b.put(sign * 9, 3, 20, "logos_dimension:elven_flower_planter", facing="south")

    # 门厅通往主演奏厅的一层三道声学大门（Z = 15, X = -2..2 及 X = ±9..±11, Y = 3..7）
    for ax0, ax1 in ((-11, -9), (-2, 2), (9, 11)):
        b.air(ax0, 3, 15, ax1, 7, 15)
        for x in range(ax0, ax1 + 1):
            b.put(x, 2, 15, "minecraft:red_wool")
            b.put(x, 8, 15, "logos_dimension:taixuan_brass_block")

    # 门厅二层通往东西包厢与王室包厢的五道拱门（Z = 15, X = ±18..±20、±10..±12、-2..2, Y = 11..15）
    for ax0, ax1 in ((-20, -18), (-12, -10), (-2, 2), (10, 12), (18, 20)):
        b.air(ax0, 11, 15, ax1, 15, 15)
        for x in range(ax0, ax1 + 1):
            b.put(x, 10, 15, "minecraft:red_wool" if abs(x) <= 2 else "minecraft:dark_oak_planks")
            b.put(x, 16, 15, "logos_dimension:taixuan_brass_block")

    # 2. 主厅二层东西包厢回廊（X = -20..-17 与 X = 17..20, Z = -3 .. 14, Y = 10）
    for sign in (-1, 1):
        xs = range(17, 21) if sign > 0 else range(-20, -16)
        rail_x = 17 if sign > 0 else -17
        seat_x = 18 if sign > 0 else -18
        # 西包厢观众面朝东（+X）看舞台、东包厢观众面朝西（-X）看舞台
        look_dir = "east" if sign < 0 else "west"
        for z in range(-3, 15):
            for x in xs:
                b.put(x, 10, z, "minecraft:dark_oak_planks")
                b.put(x, 9, z, "minecraft:smooth_quartz")
            # 包厢前缘黄铜镶边与红丝绒护栏（在 Z = 12..14 敞开与南侧王室包厢互通！）
            b.put(rail_x, 10, z, "logos_dimension:taixuan_brass_block")
            if z <= 11:
                b.put(rail_x, 11, z, "minecraft:red_wool" if z % 3 != 0 else "minecraft:chiseled_quartz_block")
                if z % 3 == 0:
                    b.put(rail_x, 12, z, "minecraft:lantern", hanging=False, waterlogged=False)
                elif -2 <= z <= 10 and z % 2 == 0:
                    b.put_chair(seat_x, 11, z, look_dir=look_dir, role="二层东西包厢观众席")
        # 包厢下方托臂柱（Z = -3, 3, 9, 14）
        for pz in (-3, 3, 9, 14):
            for y in range(2, 10):
                b.put(rail_x, y, pz, "minecraft:quartz_pillar", axis="y")

    # 3. 南侧二层「女王与灵猫宰相王室包厢」（Z = 11 .. 14, X = -16 .. 16, Y = 10）
    for x in range(-16, 17):
        for z in range(11, 15):
            b.put(x, 10, z, "minecraft:red_wool" if abs(x) <= 5 else "minecraft:dark_oak_planks")
            b.put(x, 9, z, "minecraft:smooth_quartz")
        b.put(x, 10, 11, "logos_dimension:taixuan_brass_block")
        b.put(x, 11, 11, "minecraft:blue_wool" if abs(x) <= 4 else "minecraft:red_wool")
    # 王室包厢宝座与星辰花桌
    for rx in (-2, 0, 2):
        b.put(rx, 11, 13, "logos_dimension:elven_leaf_seat", facing="north")
    b.put(-4, 11, 13, "logos_dimension:elven_flower_planter", facing="north")
    b.put(4, 11, 13, "logos_dimension:elven_flower_planter", facing="north")

    # 4. 主演奏厅一层池座地面与精简的 3 排古典观众席（Z = -3 .. 14, X = -16 .. 16, Y = 1..2）
    for x in range(-20, 21):
        for z in range(-3, 15):
            floor_y = 1 if z <= 3 else 2
            for fy in range(1, floor_y + 1):
                if abs(x) <= 2:
                    b.put(x, fy, z, "minecraft:red_wool")
                elif (x + z) % 5 == 0:
                    b.put(x, fy, z, "minecraft:calcite")
                else:
                    b.put(x, fy, z, "minecraft:polished_diorite")
    # 精简池座（仅在 Z = 1, 5, 9 设三排古典长椅，观众面朝北看舞台，楼梯靠背朝南 facing="south"）
    for row_z, seat_y in ((1, 2), (5, 3), (9, 3)):
        for x in range(-12, 13):
            if abs(x) <= 2 or abs(x) == 8:
                continue
            b.put_chair(x, seat_y, row_z, look_dir="north", role="一层池座观众席")

    # 5. 三座「星辰花水晶大吊灯」（Z = -13, -3, 7, X = 0, Y = 17 .. 23）
    for cz in (-13, -3, 7):
        for y in range(20, 24):
            b.put(0, y, cz, "minecraft:iron_chain", axis="y")
        b.put(0, 19, cz, "logos_dimension:taixuan_brass_block")
        b.put(0, 18, cz, "minecraft:pearlescent_froglight", axis="y")
        b.put(0, 17, cz, "minecraft:end_rod", facing="down")
        for dx, dz in ((-2, 0), (2, 0), (0, -2), (0, 2), (-1, -1), (-1, 1), (1, -1), (1, 1)):
            b.put(dx, 19, cz + dz, "logos_dimension:taixuan_brass_ridge_slab", type="top", waterlogged=False)
            b.put(dx, 20, cz + dz, "minecraft:end_rod", facing="up")
            if abs(dx) + abs(dz) == 2:
                b.put(dx, 18, cz + dz, "minecraft:ochre_froglight", axis="y")


def build_orchestra_stage(b: HallBuilder):
    """搭建交响乐团阶梯舞台：台面全部且唯一使用 `minecraft:bamboo_planks`。"""
    # 舞台两侧胡桃木声学反射侧墙（X = -16..-20 与 X = 16..20, Z = -26 .. -4）
    for sign in (-1, 1):
        wall_x = sign * 16
        for z in range(-26, -3):
            for y in range(2, 19):
                if z in (-26, -20, -13, -4):
                    if y in (6, 12):
                        b.put(wall_x, y, z, "minecraft:ochre_froglight", axis="y")
                    elif y in (8, 18):
                        b.put(wall_x, y, z, "logos_dimension:taixuan_brass_block")
                    else:
                        b.put(wall_x, y, z, "minecraft:quartz_pillar", axis="y")
                else:
                    b.put(wall_x, y, z, "minecraft:dark_oak_planks" if (y + z) % 3 else "minecraft:spruce_planks")
            # 侧墙背后的支撑结构填充
            for bx in range(min(wall_x + sign, sign * 20), max(wall_x + sign, sign * 20) + 1):
                b.put(bx, 2, z, "minecraft:dark_oak_planks")

    # 舞台上方悬挂式「星辰花声学反声云板与舞台聚光灯」（Y = 15..16, Z = -18, -10）
    for cz in (-18, -10):
        for cx in (-9, 0, 9):
            for y in range(17, 24):
                b.put(cx, y, cz, "minecraft:iron_chain", axis="y")
            for dx in range(-2, 3):
                for dz in range(-1, 2):
                    if dx == 0 and dz == 0:
                        b.put(cx, 16, cz, "minecraft:ochre_froglight", axis="y")
                    elif abs(dx) == 2 and dz == 0:
                        b.put(cx + dx, 16, cz + dz, "minecraft:pearlescent_froglight", axis="y")
                    else:
                        b.put(cx + dx, 16, cz + dz, "logos_dimension:taixuan_brass_ridge_slab", type="top", waterlogged=False)

    # 舞台两侧登台短阶（X = -15 与 X = 15, Z = -3）
    for sx in (-15, 15):
        b.put(sx, 2, -3, "minecraft:dark_oak_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)

    # 乐团舞台四级声学阶梯（Z = -23 .. -4, X = -15 .. 15）
    # - 主舞台（指挥台、弦乐与独奏区）：Z = -12 .. -4, 台面标高 Y = 2
    # - 第一阶梯（木管前排、竖琴、钢片琴、钟琴）：Z = -15 .. -13, 台面标高 Y = 3
    # - 第二阶梯（巴松管、皇家小号铜管组）：Z = -18 .. -16, 台面标高 Y = 4
    # - 第三阶梯（定音鼓组、管钟、管风琴演奏台）：Z = -23 .. -19, 台面标高 Y = 5
    for z in range(-23, -3):
        if z >= -12:
            top_y = 2
        elif z >= -15:
            top_y = 3
        elif z >= -18:
            top_y = 4
        else:
            top_y = 5
        for x in range(-15, 16):
            # 舞台下方共鸣腔底座用深色橡木支撑，最上层台面与阶梯立面全部使用唯一方块 `minecraft:bamboo_planks`
            for y in range(1, top_y):
                if (z == -13 and y == 2) or (z == -16 and y == 3) or (z == -19 and y == 4) or (z == -4 and y == 1):
                    b.put_orchestra_floor(x, y, z)
                else:
                    b.put(x, y, z, "minecraft:dark_oak_planks")
            b.put_orchestra_floor(x, top_y, z)

    # 在主舞台前排正中放置真正的两格宽指挥台（hexen_podium）：
    # 主块 (-1, 3, -5)[facing=north, side=false]，右侧块 (0, 3, -5)[facing=north, side=true]，指挥站立中心为 (0.0, 3.5, -4.5)
    b.place_podium(-1, 3, -5, facing="north")


def build_pipe_organ(b: HallBuilder):
    """在舞台正北方（Z = -26 .. -20）搭建现实音乐厅布局的五塔交响管风琴与相连风箱。"""
    # 1. 管风琴演奏台：位于第三阶梯正中（X = 0, Y = 6, Z = -20, facing=south），距离指挥台 15.0 格（在 16 格召集扇形内），并绑定至指挥台！
    #    3 宽、左右对称：X = -1..1, Y = 6..7, Z = -20，主块 (0, 6, -20) 在下排正中
    b.place_organ_console(0, 6, -20, "south", "管风琴演奏台 (Organ Console)", bind=True)
    # 演奏台正中前面的管风琴师琴凳（X = 0, Y = 6, Z = -19，管风琴师面朝北看演奏台键盘）
    b.put_bench(0, 6, -19, look_dir="north", role="管风琴师琴凳", link="hexen_organ_console")

    # 2. 管风琴木质基座柜体（Z = -26 .. -24, X = -14 .. 14, Y = 2 .. 8）
    for x in range(-14, 15):
        for z in range(-26, -23):
            for y in range(2, 9):
                if z == -24 and y == 7 and abs(x) in (4, 8, 12):
                    b.put(x, y, z, "minecraft:ochre_froglight", axis="y")
                elif z == -24 and y == 6 and abs(x) in (4, 8, 12):
                    b.put(x, y, z, "logos_dimension:taixuan_brass_block")
                else:
                    b.put(x, y, z, "minecraft:dark_oak_planks" if (x + y) % 2 == 0 else "minecraft:stripped_dark_oak_log")
    # 管风琴共鸣背壁暖光灯带（Z = -27, Y = 12, 16）照亮银色锡管与金色簧管
    for x in (-9, -5, 0, 5, 9):
        for y in (12, 16):
            b.put(x, y, -27, "minecraft:ochre_froglight", axis="y")

    # 3. 连续风箱网络（organ_wind_chest）：从演奏台背面（Z = -21..-23）连通至全部音管底座
    # 3a. 导风主干（Z = -21..-23, X = 0, Y = 7，并在 Z = -23 升至 Y = 8），直接紧贴演奏台上半格 (0, 7, -20) 的北侧面，
    #     并在 (0, 8, -25) 处向上托起主立面风箱横梁，保留 (0, 9, -24) 的正中皇家簧管！
    for z in (-21, -22, -23):
        b.put(0, 7, z, "logos_dimension:organ_wind_chest")
    b.put(0, 8, -23, "logos_dimension:organ_wind_chest")
    b.put(0, 8, -25, "logos_dimension:organ_wind_chest")

    # 3b. 前排水平齐奏皇家簧管风箱（Trompette en chamade）：Z = -24, Y = 8, X = -3 .. +3（共 7 格）
    #     其正上方 Y = 9 放置朝南横伸的黄金簧管（organ_trumpet_pipe[facing=south]）
    for x in range(-3, 4):
        b.put(x, 8, -24, "logos_dimension:organ_wind_chest")
        b.put(x, 9, -24, "logos_dimension:organ_trumpet_pipe", facing="south")
        b.instrument_placements.append(
            {"kind": "organ_trumpet_pipe", "section": "管风琴水平皇家簧管排 (Chamade Reeds)", "pos": [x, 9, -24], "facing": "south"}
        )

    # 3c. 主立面五塔对称锡管风箱横梁（Great & Pedal Principal Facade）：Z = -25, Y = 9, X = -10 .. +10（共 21 格）
    tin_heights = {
        -10: 9, -9: 9,          # 西侧 16' 低音大塔 (West Pedal Tower)
        -8: 5, -7: 4,           # 西外侧音管窗 (West Outer Flat)
        -6: 8, -5: 8,           # 西副塔 (West Positiv Tower)
        -4: 5, -3: 6, -2: 7,    # 西内侧音管窗 (West Inner Flat)
        -1: 9, 0: 11, 1: 9,     # 中央主音栓大塔 (Central Great Tower)
        2: 7, 3: 6, 4: 5,       # 东内侧音管窗 (East Inner Flat)
        5: 8, 6: 8,             # 东副塔 (East Positiv Tower)
        7: 4, 8: 5,             # 东外侧音管窗 (East Outer Flat)
        9: 9, 10: 9,            # 东侧 16' 低音大塔 (East Pedal Tower)
    }
    for x, h in sorted(tin_heights.items()):
        b.put(x, 9, -25, "logos_dimension:organ_wind_chest")
        b.place_organ_pipe_column(x, 10, -25, "tin", h, facing="south")
        b.instrument_placements.append(
            {"kind": "organ_tin_pipe", "section": "管风琴正面锡管立面 (Tin Principal Facade)", "pos": [x, 10, -25], "height": h, "facing": "south"}
        )

    # 3d. 后排木管音栓阵列（Swell & Bourdon Wood Rank）：Z = -26, Y = 9, X = -5 .. +5（共 11 格）
    wood_heights = {-5: 3, -4: 4, -3: 4, -2: 5, -1: 5, 0: 6, 1: 5, 2: 5, 3: 4, 4: 4, 5: 3}
    for x, h in sorted(wood_heights.items()):
        b.put(x, 9, -26, "logos_dimension:organ_wind_chest")
        b.place_organ_pipe_column(x, 10, -26, "wood", h, facing="south")
        b.instrument_placements.append(
            {"kind": "organ_wood_pipe", "section": "管风琴后排木管阵列 (Wood Flute/Bourdon Rank)", "pos": [x, 10, -26], "height": h, "facing": "south"}
        )

    # 4. 管风琴古典胡桃木雕花外框、塔冠与灵猫耳顶饰（不遮挡音管上方与正面）
    for x in (-12, -11, 11, 12):
        for y in range(9, 21):
            b.put(x, y, -25, "minecraft:stripped_dark_oak_log" if abs(x) == 11 else "minecraft:dark_oak_planks", axis="y")
        b.put(x, 21, -25, "logos_dimension:taixuan_brass_block")
    # 五座音管塔顶部的胡桃木与镀金雕花塔冠（位于各列音管最高处之上 1..2 格，不影响 OrganStructure 扫描）
    for x, h in tin_heights.items():
        crown_y = 10 + h
        b.put(x, crown_y, -25, "logos_dimension:taixuan_brass_block")
        b.put(x, crown_y + 1, -25, "minecraft:dark_oak_stairs", facing="north", half="bottom", shape="straight", waterlogged=False)
    # 中央主塔顶部的星辰花金徽与两侧副塔顶部的灵猫双耳尖冠
    b.put(0, 22, -25, "minecraft:gold_block")
    b.put(0, 23, -25, "minecraft:ochre_froglight", axis="y")
    for tx in (-6, -5, 5, 6):
        b.put(tx, 19, -25, "minecraft:gold_block")
        if tx in (-6, 6):
            b.put(tx, 20, -25, "minecraft:quartz_stairs", facing="west" if tx < 0 else "east", half="bottom", shape="straight", waterlogged=False)


def populate_orchestra_instruments(b: HallBuilder):
    """按现实交响乐团标准席位摆放全部 15 种乐器与演奏席（共 45 件可演奏乐器，其中核心 32 件绑定至指挥台）。"""
    # 注：指挥台 hexen_podium 已在 build_orchestra_stage 中放置于 (-1, 3, -5) 与 (0, 3, -5)，
    #     管风琴演奏台已在 build_pipe_organ 中放置于 (0, 6, -20) 并作为第 1 件绑定乐器。

    # 1. 独奏协奏曲与数字低音位置（Solo / Continuo，指挥台左前方，舞台西前侧，2 件均绑定）：
    #    - 星辰花三角钢琴：主块 (-4, 3, -6)，facing=west（琴键朝西、琴尾向东、琴盖朝南向观众敞开！）
    #    - 大键琴：主块 (-3, 3, -9)，facing=west（琴尾在 (-2, 3, -9)）
    b.place_grand_piano(-4, 3, -6, "west", "独奏星辰花三角钢琴 (Solo Grand Piano)", bind=True)
    # 钢琴琴键那一排是 (-4, 3, -6) 和北边的 (-4, 3, -7)：双人琴凳主块 (-5, 3, -7)，另一格在南边 (-5, 3, -6)，钢琴手坐在两格交界
    b.put_bench(-5, 3, -7, look_dir="east", role="三角钢琴独奏席", link="starbloom_grand_piano", duet=True)
    b.place_harpsichord(-3, 3, -9, "west", "数字低音/独奏大键琴 (Harpsichord)", bind=True)
    b.put_bench(-4, 3, -9, look_dir="east", role="大键琴演奏席", link="hexen_harpsichord")

    # 2. 第一小提琴声部（First Violins，舞台西侧前排，指挥左侧）：6 架（4 架绑定 + 2 架外翼声部扩充）
    first_violins = [
        (-11, -7, True), (-9, -7, True), (-7, -7, True), (-8, -9, True),
        (-13, -7, False), (-13, -9, False),
    ]
    for x, z, bind in first_violins:
        b.place_music_stand(x, 3, z, "north", "none", "第一小提琴 (First Violins)", bind=bind)
        b.put_bench(x, 3, z - 1, look_dir="south", role="第一小提琴坐席", bind=bind, seat_for="violin")

    # 3. 第二小提琴声部（Second Violins，第一小提琴内后侧）：4 架（3 架绑定 + 1 架外翼声部扩充）
    second_violins = [
        (-10, -9, True), (-9, -11, True), (-7, -11, True),
        (-11, -11, False),
    ]
    for x, z, bind in second_violins:
        b.place_music_stand(x, 3, z, "north", "none", "第二小提琴 (Second Violins)", bind=bind)
        b.put_bench(x, 3, z - 1, look_dir="south", role="第二小提琴坐席", bind=bind, seat_for="violin")

    # 4. 中提琴声部（Violas，舞台中央正对指挥）：4 架（3 架绑定 + 1 架声部扩充）
    violas = [
        (0, -8, True), (2, -8, True), (1, -11, True),
        (-2, -11, False),
    ]
    for x, z, bind in violas:
        b.place_music_stand(x, 3, z, "north", "none", "中提琴 (Violas)", bind=bind)
        b.put_bench(x, 3, z - 1, look_dir="south", role="中提琴坐席", bind=bind, seat_for="viola")

    # 5. 大提琴声部（Cellos，舞台东侧前排，指挥右侧）：5 把大提琴（3 把绑定 + 2 把外侧扩充，自带红丝绒琴凳）
    cellos = [
        (5, -7, True), (7, -7, True), (6, -10, True),
        (9, -7, False), (9, -10, False),
    ]
    for x, z, bind in cellos:
        b.place_tall_instrument(x, 3, z, "hexen_cello", "south", "大提琴 (Cellos)", bind=bind)

    # 6. 低音提琴声部（Double Basses，舞台最东侧弧形排布，大提琴外后方）：3 把低音提琴（2 把绑定 + 1 把后排扩充）
    double_basses = [
        (12, 3, -8, True), (12, 3, -11, True),
        (13, 4, -14, False),
    ]
    for x, y, z, bind in double_basses:
        b.place_tall_instrument(x, y, z, "hexen_double_bass", "west", "低音提琴 (Double Basses)", bind=bind)

    # 7. 第一阶梯（Riser 1, Y = 4, Z = -15 .. -13）：
    # 7a. 竖琴与键盘打击乐声部（舞台西侧：2 架灵猫竖琴 + 钢片琴 + 钟琴）
    b.place_tall_instrument(-10, 4, -13, "hexen_concert_harp", "south", "第一竖琴 (First Concert Harp)", bind=True)
    b.put_bench(-10, 4, -14, look_dir="south", role="第一竖琴坐席", link="hexen_concert_harp")
    b.place_tall_instrument(-12, 4, -13, "hexen_concert_harp", "south", "第二竖琴 (Second Concert Harp)", bind=False)
    b.put_bench(-12, 4, -14, look_dir="south", role="第二竖琴坐席", link="hexen_concert_harp")

    b.place_single_instrument(-8, 4, -13, "hexen_celesta", "north", "钢片琴 (Celesta)", bind=True)
    b.put_bench(-8, 4, -14, look_dir="south", role="钢片琴坐席", link="hexen_celesta")

    b.place_single_instrument(-6, 4, -13, "hexen_glockenspiel", "north", "钟琴 (Glockenspiel)", bind=True)

    # 7b. 木管前排（舞台正中：左侧 2 支长笛 Flutes，右侧 2 支双簧管 Oboes，全部绑定）
    for x in (-3, -1):
        b.place_music_stand(x, 4, -13, "north", "none", "长笛声部 (Flutes)", bind=False)
        b.put_bench(x, 4, -14, look_dir="south", role="长笛坐席", bind=True, seat_for="flute")
    for x in (2, 4):
        b.place_music_stand(x, 4, -13, "north", "none", "双簧管声部 (Oboes)", bind=False)
        b.put_bench(x, 4, -14, look_dir="south", role="双簧管坐席", bind=True, seat_for="oboe")

    # 8. 第二阶梯（Riser 2, Y = 5, Z = -18 .. -16）：
    # 8a. 木管后排（舞台正中偏左：2 支巴松管 Bassoons，全部绑定）
    for x in (-3, -1):
        b.place_music_stand(x, 5, -16, "north", "none", "巴松管声部 (Bassoons)", bind=False)
        b.put_bench(x, 5, -17, look_dir="south", role="巴松管坐席", bind=True, seat_for="bassoon")

    # 8b. 铜管声部（舞台正中偏右：4 支皇家小号 Royal Trumpets，2 支绑定 + 2 支扩充）
    for x, bind in ((2, True), (4, True), (6, False), (8, False)):
        b.place_music_stand(x, 5, -16, "north", "none", "皇家小号声部 (Royal Trumpets)", bind=False)
        b.put_bench(x, 5, -17, look_dir="south", role="皇家小号坐席", bind=bind, seat_for="trumpet")

    # 9. 第三阶梯（Riser 3, Y = 6, Z = -23 .. -19）：
    # 9a. 定音鼓声部（舞台左后方：4 台定音鼓两两并排，2 台绑定 + 2 台扩充，facing=south 踏板朝北对准鼓手）。
    #     每一对鼓后面一张双人琴凳（主块在东、另一格在西），两格各对着一面鼓，坐在正中的鼓手左右手各打一面
    for x, bind in ((-5, True), (-4, True), (-9, False), (-8, False)):
        b.place_single_instrument(x, 6, -19, "hexen_timpani", "south", "定音鼓组 (Timpani)", bind=bind)
    b.put_bench(-4, 6, -20, look_dir="south", role="首席定音鼓琴凳", link="hexen_timpani", duet=True)
    b.put_bench(-8, 6, -20, look_dir="south", role="副定音鼓琴凳", link="hexen_timpani", duet=True)

    # 9b. 管钟声部（舞台右后方打击乐区：2 架管钟 Tubular Bells，1 架绑定 + 1 架扩充）。
    #     facing=north：敲管钟的人站在管钟北面（同一级台阶上）面朝指挥，管钟在他和指挥之间
    b.place_tall_instrument(5, 6, -19, "hexen_tubular_bells", "north", "第一管钟 (Tubular Bells I)", bind=True)
    b.place_tall_instrument(8, 6, -19, "hexen_tubular_bells", "north", "第二管钟 (Tubular Bells II)", bind=False)


def verify_and_audit(b: HallBuilder) -> dict:
    """生成阶段自检：验证唯一舞台地板方块、楼梯通行净空、座椅朝向、指挥台 32 乐器绑定及管风琴 BFS 连通性。"""
    # 1. 验证 `minecraft:bamboo_planks` 仅出现在登记的乐团舞台地板集合中
    actual_floor = {pos for pos, s in b.cells.items() if s["Name"] == ORCHESTRA_FLOOR_BLOCK}
    assert actual_floor == b.orchestra_floor_positions, (
        f"唯一舞台地板集合不一致: diff={actual_floor ^ b.orchestra_floor_positions}"
    )
    for x, y, z in actual_floor:
        assert -15 <= x <= 15 and -23 <= z <= -4 and 1 <= y <= 5, f"舞台地板越界: {(x, y, z)}"

    # 2. 验证门厅东西两侧折返楼梯可真实走上二楼并进入东西包厢与王室包厢（每级台阶上方至少 4 格空气净空）
    for sign in (-1, 1):
        in_x = sign * 16
        out_x = sign * 19
        walk_path = [
            (in_x, 2, 16),   # 一层门厅起点
            (in_x, 3, 17),   # 第一跑第 1 级
            (in_x, 4, 18),   # 第一跑第 2 级
            (in_x, 5, 19),   # 第一跑第 3 级
            (in_x, 6, 20),   # 休息平台内侧
            (sign * 18, 7, 20),  # 休息平台过渡级
            (out_x, 7, 20),  # 休息平台外侧
            (out_x, 8, 19),  # 第二跑第 1 级
            (out_x, 9, 18),  # 第二跑第 2 级
            (out_x, 10, 17), # 第二跑第 3 级
            (out_x, 10, 16), # 二层顶部平台
            (out_x, 10, 15), # 穿过 Z=15 拱门
            (out_x, 10, 14), # 进入二层东西包厢
            (sign * 16, 10, 13), # 步入二层王室包厢
        ]
        for idx, (wx, wy, wz) in enumerate(walk_path):
            foot_block = b.cells.get((wx, wy, wz), {}).get("Name", "minecraft:air")
            assert foot_block != "minecraft:air", f"楼梯路径支撑方块缺失 ({idx}): {(wx, wy, wz)}"
            for clearance_dy in (1, 2, 3, 4):
                head_pos = (wx, wy + clearance_dy, wz)
                head_block = b.cells.get(head_pos, {}).get("Name", "minecraft:air")
                assert head_block == "minecraft:air", (
                    f"楼梯路径净空被阻挡 ({idx}): pos={head_pos}, block={head_block}"
                )

    # 3. 验证全部座椅楼梯的靠背朝向（facing 必须与坐姿面朝方向 look_dir 相反）
    opposite_dir = {"north": "south", "south": "north", "east": "west", "west": "east"}
    assert len(b.chair_placements) > 0, "未登记任何座椅楼梯"
    for chair in b.chair_placements:
        pos = chair["pos"]
        cell = b.cells.get(pos, {})
        assert cell.get("Name", "").endswith("_stairs"), f"座椅位置已被非楼梯方块覆盖: {chair}"
        expected_facing = opposite_dir[chair["look_dir"]]
        actual_facing = cell.get("Properties", {}).get("facing")
        assert actual_facing == expected_facing, (
            f"座椅方向错误: {chair}, actual={actual_facing}, expected={expected_facing}"
        )

    # 4. 验证真正的指挥台（hexen_podium）与 32 件绑定乐器 + 舞台总乐器数 > 32
    assert b.podium_pos == (-1, 3, -5), f"指挥台主块位置异常: {b.podium_pos}"
    assert b.cells.get((-1, 3, -5)) == state("hexen_podium", facing="north", side="false", score="false")
    assert b.cells.get((0, 3, -5)) == state("hexen_podium", facing="north", side="true", score="false")
    podium_be = b.entities.get((-1, 3, -5), {})
    assert podium_be.get("id") == "logos_dimension:podium", f"指挥台方块实体缺失: {podium_be}"
    assert len(b.bound_members) == 32, f"指挥台绑定乐器数量必须等于 32: {len(b.bound_members)}"
    assert len(podium_be.get("members", [])) == 32, "指挥台 NBT members 长度必须等于 32"

    # 验证全部 32 件绑定乐器均在 PodiumBlockEntity.gather() 的 16 格扇形与 ±80° 范围内
    stand_x, stand_y, stand_z = 0.0, 3.5, -4.5
    cos_80 = math.cos(math.radians(80.0))
    for mx, my, mz in b.bound_members:
        dx = (mx + 0.5) - stand_x
        dy = my - int(math.floor(stand_y))
        dz = (mz + 0.5) - stand_z
        horiz = math.hypot(dx, dz)
        cos_angle = (-dz) / horiz
        assert 0.5 <= horiz <= 16.0, f"绑定乐器超出指挥台 16 格范围: {(mx, my, mz)} horiz={horiz:.2f}"
        assert -4 <= dy <= 6, f"绑定乐器超出高度差范围: {(mx, my, mz)} dy={dy}"
        assert cos_angle >= cos_80, f"绑定乐器超出指挥台前方 ±80° 扇形: {(mx, my, mz)} cos={cos_angle:.3f}"
        inst_be = b.entities.get((mx, my, mz), {})
        assert inst_be.get("orchestra") == pack_block_pos(-1, 3, -5), f"乐器未写入 orchestra 绑定: {(mx, my, mz)}"

    # 5. 完全按照 Java `OrganStructure.scan` 逻辑模拟 BFS，验证管风琴演奏台与全部风箱、音管连通
    console_main = (0, 6, -20)
    console_cells = [(x, y, -20) for x in (-1, 0, 1) for y in (6, 7)]
    for c in console_cells:
        assert b.cells.get(c, {}).get("Name") == "logos_dimension:hexen_organ_console", f"缺失演奏台格子: {c}"

    seen = set(console_cells)
    queue = deque()
    dirs = [(1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)]
    for cx, cy, cz in console_cells:
        for dx, dy, dz in dirs:
            queue.append((cx + dx, cy + dy, cz + dz))

    chests = tin = wood = reed = 0
    budget = 256
    while queue and budget > 0:
        p = queue.popleft()
        if p in seen:
            continue
        seen.add(p)
        px, py, pz = p
        if abs(px - console_main[0]) > 24 or abs(py - console_main[1]) > 24 or abs(pz - console_main[2]) > 24:
            continue
        if b.cells.get(p, {}).get("Name") != "logos_dimension:organ_wind_chest":
            continue
        chests += 1
        budget -= 1
        for h in range(1, 33):
            if budget <= 0:
                break
            up = (px, py + h, pz)
            up_name = b.cells.get(up, {}).get("Name")
            if up_name == "logos_dimension:organ_tin_pipe":
                tin += 1
            elif up_name == "logos_dimension:organ_wood_pipe":
                wood += 1
            elif up_name == "logos_dimension:organ_trumpet_pipe":
                reed += 1
            else:
                break
            budget -= 1
        for dx, dy, dz in dirs:
            queue.append((px + dx, py + dy, pz + dz))

    total_chests_in_hall = sum(1 for s in b.cells.values() if s["Name"] == "logos_dimension:organ_wind_chest")
    total_tin_in_hall = sum(1 for s in b.cells.values() if s["Name"] == "logos_dimension:organ_tin_pipe")
    total_wood_in_hall = sum(1 for s in b.cells.values() if s["Name"] == "logos_dimension:organ_wood_pipe")
    total_reed_in_hall = sum(1 for s in b.cells.values() if s["Name"] == "logos_dimension:organ_trumpet_pipe")

    assert chests == total_chests_in_hall and chests > 0, f"风箱未全部连通: scanned={chests}, total={total_chests_in_hall}"
    assert tin == total_tin_in_hall and tin > 0, f"锡管未全部连通: scanned={tin}, total={total_tin_in_hall}"
    assert wood == total_wood_in_hall and wood > 0, f"木管未全部连通: scanned={wood}, total={total_wood_in_hall}"
    assert reed == total_reed_in_hall and reed > 0, f"簧管未全部连通: scanned={reed}, total={total_reed_in_hall}"
    assert chests + tin + wood + reed <= 256, f"超过 OrganStructure.MAX_BLOCKS (256): {chests + tin + wood + reed}"

    # 5b. 星辰花琴凳：要关联的琴凳正前方就是那件乐器，并且坐在它的演奏那一面（同 StarbloomBenchBlock.linked）：
    #     键盘（钢琴、大键琴、钢片琴、管风琴演奏台）的 facing 朝着弹的人，竖琴同大提琴、人坐在琴后和琴朝同一个方向。
    step = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    opposite = {"north": "south", "south": "north", "east": "west", "west": "east"}
    assert b.bench_placements, "未放置任何星辰花琴凳"
    for bench in b.bench_placements:
        bx, by, bz = bench["pos"]
        name = NS + ("starbloom_duet_bench" if bench["duet"] else "starbloom_bench")
        seats = [(bx, by, bz)]
        if bench["duet"]:
            sx, sz = HallBuilder._cw(bench["look_dir"])
            seats.append((bx + sx, by, bz + sz))
        for sx, sy, sz in seats:
            cell = b.cells.get((sx, sy, sz), {})
            assert cell.get("Name") == name, f"琴凳被覆盖: {bench} at {(sx, sy, sz)}"
            assert cell.get("Properties", {}).get("facing") == bench["look_dir"], f"琴凳朝向错误: {bench}"
        if not bench["link"]:
            continue
        # 每一格前面都是那件乐器的琴键一排（双人琴凳两格都对着两格宽的钢琴）
        dx, dz = step[bench["look_dir"]]
        for sx, sy, sz in seats:
            front = b.cells.get((sx + dx, sy, sz + dz), {})
            assert front.get("Name") == NS + bench["link"], f"琴凳前面不是要关联的乐器: {bench} -> {front}"
            facing = front.get("Properties", {}).get("facing")
            expected = bench["look_dir"] if bench["link"] in ("hexen_concert_harp", "hexen_timpani") else opposite[bench["look_dir"]]
            assert facing == expected, f"琴凳不在乐器的演奏那一面: {bench}, instrument facing={facing}, expected={expected}"

    # 6. 统计全部 15 种乐器与音管数量（验证总可演奏乐器 > 32，且 32 件绑定乐器覆盖全部 15 种可演奏乐器类型）
    counts = Counter(item["kind"] for item in b.instrument_placements)
    bound_counts = Counter(item["kind"] for item in b.instrument_placements if item.get("bound"))
    playable_kinds = [
        "starbloom_grand_piano",
        "hexen_concert_harp",
        "hexen_glockenspiel",
        "hexen_celesta",
        "hexen_tubular_bells",
        "hexen_harpsichord",
        "hexen_organ_console",
        "hexen_cello",
        "hexen_double_bass",
        "hexen_timpani",
        "starbloom_bench[violin]",
        "starbloom_bench[viola]",
        "starbloom_bench[flute]",
        "starbloom_bench[oboe]",
        "starbloom_bench[bassoon]",
        "starbloom_bench[trumpet]",
    ]
    for pk in playable_kinds:
        assert counts[pk] > 0, f"缺少可演奏乐器类型: {pk}"
        assert bound_counts[pk] > 0, f"指挥台绑定的 32 件乐器未覆盖类型: {pk}"
    for ok in ("organ_tin_pipe", "organ_wood_pipe", "organ_trumpet_pipe"):
        assert counts[ok] > 0, f"缺少管风琴音管类型: {ok}"

    total_playable = sum(counts[pk] for pk in playable_kinds)
    assert total_playable > 32, f"舞台可演奏乐器总数应大于 32: {total_playable}"

    return {
        "orchestra_floor_block": ORCHESTRA_FLOOR_BLOCK,
        "orchestra_floor_count": len(actual_floor),
        "podium": {
            "pos": list(b.podium_pos),
            "facing": "north",
            "bound_instruments": len(b.bound_members),
            "total_playable_instruments_on_stage": total_playable,
            "bound_instrument_counts": dict(bound_counts),
        },
        "organ_scan": {
            "chests": chests,
            "tin_pipes": tin,
            "wood_pipes": wood,
            "trumpet_pipes": reed,
            "total_organ_blocks": chests + tin + wood + reed,
            "max_blocks_limit": 256,
            "hearing_range": min(64, 16 + tin + wood + reed),
        },
        "instrument_counts": dict(counts),
        "chair_count": len(b.chair_placements),
        "benches": [
            {"pos": list(x["pos"]), "facing": x["look_dir"], "role": x["role"], "seat_for": x["seat_for"], "link": x["link"],
             "duet": x["duet"]}
            for x in b.bench_placements
        ],
        "total_cells": len(b.cells),
        "total_block_entities": len(b.entities),
    }


def connector(cells: dict, tags: dict, pos: tuple[int, int, int], front: str, name: str, target: str, pool: str):
    """在分块边界放置拼图连接方块（final_state 保存原方块，生成后自动还原）。"""
    old = cells.get(pos)
    if not old:
        final = "minecraft:structure_void"
    else:
        props = ",".join(f"{k}={v}" for k, v in old["Properties"].items()) if old.get("Properties") else ""
        final = old["Name"] + (f"[{props}]" if props else "")
    orientation = front + ("_north" if front in ("up", "down") else "_up")
    cells[pos] = state("minecraft:jigsaw", orientation=orientation)
    tags[pos] = {
        "id": "minecraft:jigsaw",
        "name": NS + name,
        "target": NS + target if ":" not in target else target,
        "pool": NS + "concert_hall/" + pool if pool != "empty" else "minecraft:empty",
        "joint": "aligned",
        "final_state": final,
    }


def write_pool(name: str, templates: list[str]):
    write_json(
        D / "worldgen/template_pool/concert_hall" / f"{name}.json",
        {
            "name": NS + "concert_hall/" + name,
            "fallback": "minecraft:empty",
            "elements": [
                {
                    "weight": 1,
                    "element": {
                        "element_type": "minecraft:single_pool_element",
                        "location": NS + "concert_hall/" + n,
                        "processors": NS + "concert_hall_preserve",
                        "override_liquid_settings": "ignore_waterlogging",
                        "projection": "rigid",
                    },
                }
                for n in templates
            ],
        },
    )


def _nbt_text(value: str) -> bytes:
    raw = value.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def _nbt_kind(value) -> int:
    if isinstance(value, bool):
        return 1
    if isinstance(value, _Long):
        return 4
    if isinstance(value, int):
        return 3
    if isinstance(value, float):
        return 6
    if isinstance(value, str):
        return 8
    if isinstance(value, _LongArray):
        return 12
    if isinstance(value, list):
        return 9
    if isinstance(value, dict):
        return 10
    raise TypeError(f"Unsupported NBT value: {type(value)}")


def _nbt_payload(value) -> bytes:
    if isinstance(value, bool):
        return struct.pack(">b", 1 if value else 0)
    if isinstance(value, _Long):
        return struct.pack(">q", int(value))
    if isinstance(value, int):
        return struct.pack(">i", value)
    if isinstance(value, float):
        return struct.pack(">d", value)
    if isinstance(value, str):
        return _nbt_text(value)
    if isinstance(value, _LongArray):
        return struct.pack(">i", len(value)) + b"".join(struct.pack(">q", w) for w in value)
    if isinstance(value, list):
        elem_kind = _nbt_kind(value[0]) if value else 10
        return bytes([elem_kind]) + struct.pack(">i", len(value)) + b"".join(_nbt_payload(v) for v in value)
    if isinstance(value, dict):
        return b"".join(bytes([_nbt_kind(v)]) + _nbt_text(k) + _nbt_payload(v) for k, v in value.items()) + b"\x00"
    raise TypeError(f"Unsupported NBT value: {type(value)}")


def write_hall_structure(path: Path, size: list[int], cells: dict, block_entities: dict | None = None, data_version: int = DATA_VERSION):
    """写出支持 TAG_Long / TAG_Byte 的原版 StructureTemplate `.nbt` 文件。"""
    palette = []
    lookup = {}
    blocks = []
    block_entities = block_entities or {}
    for pos, st in sorted(cells.items(), key=lambda v: (v[0][1], v[0][2], v[0][0])):
        key = json.dumps(st, sort_keys=True)
        index = lookup.get(key)
        if index is None:
            index = len(palette)
            lookup[key] = index
            palette.append(st)
        entry = {"pos": list(pos), "state": index}
        if pos in block_entities:
            entry["nbt"] = block_entities[pos]
        blocks.append(entry)
    doc = {"DataVersion": data_version, "size": list(size), "palette": palette, "blocks": blocks, "entities": []}
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = gzip.compress(b"\x0a\x00\x00" + _nbt_payload(doc), mtime=0)
    path.write_bytes(raw)


def write_litematic(path: Path, cells: dict, entities: dict, data_version: int = DATA_VERSION) -> dict:
    """将完整建筑导出为 Litematica 原生 `.litematic` 投影文件（Version 6），并执行往返解包校验。"""
    non_air = {
        pos: s
        for pos, s in cells.items()
        if s["Name"] not in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void")
    }
    xs = [p[0] for p in non_air]
    ys = [p[1] for p in non_air]
    zs = [p[2] for p in non_air]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    min_z, max_z = min(zs), max(zs)
    sx, sy, sz = max_x - min_x + 1, max_y - min_y + 1, max_z - min_z + 1

    # Litematica 调色板第 0 项固定为 minecraft:air
    air_state = {"Name": "minecraft:air"}
    palette = [air_state]
    lookup = {json.dumps(air_state, sort_keys=True): 0}
    for pos in sorted(non_air, key=lambda p: (p[1], p[2], p[0])):
        st = non_air[pos]
        key = json.dumps(st, sort_keys=True)
        if key not in lookup:
            lookup[key] = len(palette)
            palette.append(st)

    bits = max(2, (len(palette) - 1).bit_length())
    volume = sx * sy * sz
    num_words = (volume * bits + 63) // 64
    words = [0] * num_words

    for (x, y, z), st in non_air.items():
        rx, ry, rz = x - min_x, y - min_y, z - min_z
        index = (ry * sz + rz) * sx + rx
        val = lookup[json.dumps(st, sort_keys=True)]
        bit = index * bits
        word, shift = divmod(bit, 64)
        words[word] |= (val << shift) & ((1 << 64) - 1)
        if shift + bits > 64:
            words[word + 1] |= val >> (64 - shift)

    signed_words = _LongArray(w if w < (1 << 63) else w - (1 << 64) for w in words)

    tile_entities = []
    for (x, y, z), be in sorted(entities.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        if (x, y, z) not in non_air:
            continue
        entry = dict(be)
        entry["x"] = x - min_x
        entry["y"] = y - min_y
        entry["z"] = z - min_z
        tile_entities.append(entry)

    ts = _Long(1794400000000)
    doc = {
        "MinecraftDataVersion": data_version,
        "Version": 6,
        "SubVersion": 1,
        "Metadata": {
            "Name": "灵猫大音乐厅 (Hexenwald Concert Hall)",
            "Author": "Logos Dimension",
            "Description": "魔女之森·星辰花之都——灵猫交响音乐厅（含完整交响乐团席位与五塔管风琴）",
            "RegionCount": 1,
            "TimeCreated": ts,
            "TimeModified": ts,
            "TotalBlocks": len(non_air),
            "TotalVolume": volume,
            "EnclosingSize": {"x": sx, "y": sy, "z": sz},
        },
        "Regions": {
            "ConcertHall": {
                "Position": {"x": 0, "y": 0, "z": 0},
                "Size": {"x": sx, "y": sy, "z": sz},
                "BlockStatePalette": palette,
                "BlockStates": signed_words,
                "TileEntities": tile_entities,
                "Entities": [],
                "PendingBlockTicks": [],
                "PendingFluidTicks": [],
            }
        },
    }

    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = gzip.compress(b"\x0a\x00\x00" + _nbt_payload(doc), mtime=0)
    path.write_bytes(raw)

    # 往返解包校验：确保 Litematica 解码器读出的每一个非空气方块及方块实体数量 100% 一致
    decoded = read_nbt(path)
    reg = decoded["Regions"]["ConcertHall"]
    dec_pal = reg["BlockStatePalette"]
    dec_words = [n & ((1 << 64) - 1) for n in reg["BlockStates"]]
    mask = (1 << bits) - 1
    dec_count = 0
    for idx in range(volume):
        bit = idx * bits
        w, sh = divmod(bit, 64)
        v = dec_words[w] >> sh
        if sh + bits > 64:
            v |= dec_words[w + 1] << (64 - sh)
        blk = dec_pal[v & mask]
        if blk["Name"] != "minecraft:air":
            dec_count += 1
    assert dec_count == len(non_air), f"Litematic 往返校验方块数不一致: {dec_count} != {len(non_air)}"
    assert len(reg["TileEntities"]) == len(tile_entities), "Litematic 方块实体数不一致"
    return {"bounds_min": [min_x, min_y, min_z], "size": [sx, sy, sz], "non_air_blocks": len(non_air)}


def export_structure(b: HallBuilder, audit_info: dict) -> dict:
    """将完整建筑切分为 32³ 拼图分块并写出 NBT、模板池与世界生成 JSON。"""
    fix_connectable_blockstates(b.cells)

    ox, oy, oz = ORIGIN
    gx, gy, gz = GRID
    root = (1, 0, 1)  # 包含世界原点 (0, 0, 0) 的中心底层分块

    # 1. 按分块归类所有方块与方块实体
    raw_tiles: dict[tuple[int, int, int], tuple[dict, dict]] = {
        (tx, ty, tz): ({}, {}) for tx in range(gx) for ty in range(gy) for tz in range(gz)
    }
    for (x, y, z), s in b.cells.items():
        tx, ty, tz = (x - ox) // TILE, (y - oy) // TILE, (z - oz) // TILE
        lx, ly, lz = (x - ox) % TILE, (y - oy) % TILE, (z - oz) % TILE
        assert (tx, ty, tz) in raw_tiles, f"坐标超出分块网格: {(x, y, z)}"
        raw_tiles[(tx, ty, tz)][0][(lx, ly, lz)] = s

    for (x, y, z), be in b.entities.items():
        tx, ty, tz = (x - ox) // TILE, (y - oy) // TILE, (z - oz) // TILE
        lx, ly, lz = (x - ox) % TILE, (y - oy) % TILE, (z - oz) % TILE
        raw_tiles[(tx, ty, tz)][1][(lx, ly, lz)] = be

    # 2. 从 root 分块出发构建 BFS 生成树，添加 jigsaw 接口
    parent = {root: None}
    queue = deque([root])
    order = []
    directions = [
        ((1, 0, 0), "east"),
        ((-1, 0, 0), "west"),
        ((0, 1, 0), "up"),
        ((0, -1, 0), "down"),
        ((0, 0, 1), "south"),
        ((0, 0, -1), "north"),
    ]
    while queue:
        p = queue.popleft()
        order.append(p)
        for delta, face in directions:
            n = (p[0] + delta[0], p[1] + delta[1], p[2] + delta[2])
            if 0 <= n[0] < gx and 0 <= n[1] < gy and 0 <= n[2] < gz and n not in parent:
                if raw_tiles[n][0] or n == root:
                    parent[n] = (p, face)
                    queue.append(n)

    opposite = {"east": "west", "west": "east", "up": "down", "down": "up", "north": "south", "south": "north"}
    ports = {
        "east": (31, 1, 1),
        "west": (0, 1, 1),
        "up": (1, 31, 1),
        "down": (1, 0, 1),
        "south": (1, 1, 31),
        "north": (1, 1, 0),
    }

    for child, relation in parent.items():
        if relation is None:
            continue
        p, face = relation
        name = "tile_" + "_".join(map(str, child))
        pc, pe = raw_tiles[p]
        cc, ce = raw_tiles[child]
        connector(pc, pe, ports[face], face, "out_" + name, "in_" + name, name)
        connector(cc, ce, ports[opposite[face]], opposite[face], "in_" + name, "none", "empty")

    # 在 root 分块的 (16, 4, 16)（对应建筑坐标 (0, 0, 0)）放置起始锚点
    rc, re = raw_tiles[root]
    connector(rc, re, (16, 4, 16), "up", "hall_anchor", "none", "empty")

    # 清理旧文件并写出所有分块 NBT 与模板池
    out_dir = D / "structure/concert_hall"
    pool_dir = D / "worldgen/template_pool/concert_hall"
    if out_dir.exists():
        for f in out_dir.glob("*.nbt"):
            f.unlink()
    if pool_dir.exists():
        for f in pool_dir.glob("*.json"):
            f.unlink()

    for p in order:
        c, e = raw_tiles[p]
        name = "tile_" + "_".join(map(str, p))
        write_hall_structure(out_dir / f"{name}.nbt", [TILE, TILE, TILE], c, e, data_version=DATA_VERSION)
        write_pool(name, [name])

    write_pool("start", ["tile_1_0_1"])

    # 同时导出一份紧凑边界的完整单文件 NBT 蓝图与 Litematica `.litematic` 投影文件
    non_air = {
        pos: s
        for pos, s in b.cells.items()
        if s["Name"] not in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void")
    }
    min_x = min(p[0] for p in non_air)
    min_y = min(p[1] for p in non_air)
    min_z = min(p[2] for p in non_air)
    max_x = max(p[0] for p in non_air)
    max_y = max(p[1] for p in non_air)
    max_z = max(p[2] for p in non_air)
    tight_size = [max_x - min_x + 1, max_y - min_y + 1, max_z - min_z + 1]
    tight_cells = {(x - min_x, y - min_y, z - min_z): s for (x, y, z), s in non_air.items()}
    tight_entities = {
        (x - min_x, y - min_y, z - min_z): be for (x, y, z), be in b.entities.items() if (x, y, z) in non_air
    }
    nbt_full_path = out_dir / "concert_hall_full.nbt"
    write_hall_structure(nbt_full_path, tight_size, tight_cells, tight_entities, data_version=DATA_VERSION)

    # 导出 `.litematic` 与 `.nbt` 至 builds/ 与 run/schematics/ 目录供投影模组直接加载
    builds_dir = ROOT.parent.parent / "builds/concert-hall-2026-11-11"
    run_schem_dir = ROOT / "run/schematics"
    litematic_info = write_litematic(builds_dir / "concert_hall.litematic", b.cells, b.entities, data_version=DATA_VERSION)
    shutil.copy2(nbt_full_path, builds_dir / "concert_hall.nbt")
    run_schem_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(builds_dir / "concert_hall.litematic", run_schem_dir / "concert_hall.litematic")
    shutil.copy2(nbt_full_path, run_schem_dir / "concert_hall.nbt")

    # 3. 写出处理器列表、世界生成结构、结构集与群系标签
    write_json(
        D / "worldgen/processor_list/concert_hall_preserve.json",
        {"processors": [{"processor_type": "minecraft:block_ignore", "blocks": [{"Name": "minecraft:structure_void"}]}]},
    )
    write_json(
        D / "tags/worldgen/biome/has_structure/concert_hall.json",
        {"values": ["logos_dimension:cloudsea_starflower_glade"]},
    )
    write_json(
        D / "worldgen/structure/concert_hall.json",
        {
            "type": "minecraft:jigsaw",
            "biomes": "#logos_dimension:has_structure/concert_hall",
            "step": "surface_structures",
            "terrain_adaptation": "none",
            "spawn_overrides": {
                kind: {"bounding_box": "full", "spawns": []} for kind in ("monster", "creature", "ambient")
            },
            "start_pool": "logos_dimension:concert_hall/start",
            "start_jigsaw_name": "logos_dimension:hall_anchor",
            "size": 16,
            "start_height": {"absolute": -4},
            "project_start_to_heightmap": "WORLD_SURFACE_WG",
            "max_distance_from_center": {"horizontal": 128, "vertical": 256},
            "use_expansion_hack": False,
        },
    )
    write_json(
        D / "worldgen/structure_set/concert_hall.json",
        {
            "structures": [{"structure": "logos_dimension:concert_hall", "weight": 1}],
            "placement": {
                "type": "minecraft:random_spread",
                "spacing": 28,
                "separation": 16,
                "salt": 976243,
            },
        },
    )

    manifest = {
        "structure": "logos_dimension:concert_hall",
        "style": "魔女之森（Hexenwald）· 星辰花之都 —— 灵猫古典交响音乐厅",
        "tiles": len(order),
        "root_tile": "tile_1_0_1",
        "anchor": [16, 4, 16],
        "size": [gx * TILE, gy * TILE, gz * TILE],
        **audit_info,
    }
    write_json(D / "concert_hall_manifest.json", manifest)
    return manifest


def write_full_only(b: HallBuilder, path: Path, audit_info: dict):
    """只写一份紧凑边界的完整单文件蓝图（与 export_structure 的 concert_hall_full.nbt 相同）、同名的 .litematic 投影和旁边的清单；
    测试场景直接加载 .nbt，试玩包带上 .nbt 和 .litematic。"""
    fix_connectable_blockstates(b.cells)
    non_air = {
        pos: s
        for pos, s in b.cells.items()
        if s["Name"] not in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void")
    }
    min_x = min(p[0] for p in non_air)
    min_y = min(p[1] for p in non_air)
    min_z = min(p[2] for p in non_air)
    size = [max(p[0] for p in non_air) - min_x + 1, max(p[1] for p in non_air) - min_y + 1, max(p[2] for p in non_air) - min_z + 1]
    cells = {(x - min_x, y - min_y, z - min_z): s for (x, y, z), s in non_air.items()}
    entities = {(x - min_x, y - min_y, z - min_z): be for (x, y, z), be in b.entities.items() if (x, y, z) in non_air}
    path.parent.mkdir(parents=True, exist_ok=True)
    write_hall_structure(path, size, cells, entities, data_version=DATA_VERSION)
    write_litematic(path.with_suffix(".litematic"), b.cells, b.entities, data_version=DATA_VERSION)
    write_json(path.with_suffix(".json"), {"offset": [min_x, min_y, min_z], "size": size, **audit_info})


def main():
    if "--full-only" in sys.argv:
        out = Path(sys.argv[sys.argv.index("--full-only") + 1])
        b = HallBuilder()
        build_foundation_and_plaza(b)
        build_hall_shell_and_roof(b)
        build_foyer_and_galleries(b)
        build_orchestra_stage(b)
        build_pipe_organ(b)
        populate_orchestra_instruments(b)
        write_full_only(b, out, verify_and_audit(b))
        print(f"wrote {out}")
        return
    b = HallBuilder()
    build_foundation_and_plaza(b)
    build_hall_shell_and_roof(b)
    build_foyer_and_galleries(b)
    build_orchestra_stage(b)
    build_pipe_organ(b)
    populate_orchestra_instruments(b)
    audit_info = verify_and_audit(b)
    manifest = export_structure(b, audit_info)
    print(json.dumps(manifest, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
