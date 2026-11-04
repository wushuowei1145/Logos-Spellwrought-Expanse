"""神狐聚落的体素工具：方块状态、带旋转的画布、和风木构常用构件。

建筑在自己的局部坐标里设计：x 向东、z 向南、正面朝北（z=0 一侧临街），y=0 为地面那一层方块。
放进聚落画布时按顺时针旋转 turns 次，方块状态（朝向、轴向、连接）一并旋转，规则与原版模板一致。
"""
import json
import math

NS = 'logos_dimension'


def B(name, **props):
    if ':' not in name:
        name = 'minecraft:' + name
    state = {'Name': name}
    if props:
        state['Properties'] = {k: str(v).lower() if isinstance(v, bool) else str(v) for k, v in props.items()}
    return state


def L(name, **props):
    return B(f'{NS}:{name}', **props)


AIR = B('air')
DIRS = ['north', 'east', 'south', 'west']


def rotate_dir(d, turns):
    if d in DIRS:
        return DIRS[(DIRS.index(d) + turns) % 4]
    return d


def rotate_state(state, turns):
    turns %= 4
    if not turns or 'Properties' not in state:
        return state
    p = dict(state['Properties'])
    for key in ('facing', 'horizontal_facing'):
        if key in p:
            p[key] = rotate_dir(p[key], turns)
    if 'axis' in p and turns % 2 == 1 and p['axis'] in ('x', 'z'):
        p['axis'] = 'z' if p['axis'] == 'x' else 'x'
    if 'rotation' in p:
        p['rotation'] = str((int(p['rotation']) + 4 * turns) % 16)
    if any(d in p for d in DIRS):
        old = {d: p.get(d) for d in DIRS}
        for d in DIRS:
            if old[d] is not None:
                p[rotate_dir(d, turns)] = old[d]
    if 'shape' in p and p['shape'] in ('north_south', 'east_west') and turns % 2 == 1:
        p['shape'] = 'east_west' if p['shape'] == 'north_south' else 'north_south'
    return {'Name': state['Name'], 'Properties': p}


_FLIP_NS = {'north': 'south', 'south': 'north'}
_HANDED = {'left': 'right', 'right': 'left', 'inner_left': 'inner_right', 'inner_right': 'inner_left',
           'outer_left': 'outer_right', 'outer_right': 'outer_left'}


def mirror_state(state):
    """南北镜像（z 翻转，同原版 Mirror.LEFT_RIGHT）：南北朝向互换，南北连接互换，楼梯转角、门铰、箱子左右与绘马挂左右互换。"""
    if 'Properties' not in state:
        return state
    p = dict(state['Properties'])
    for key in ('facing', 'horizontal_facing'):
        if p.get(key) in _FLIP_NS:
            p[key] = _FLIP_NS[p[key]]
    if 'north' in p and 'south' in p:
        p['north'], p['south'] = p['south'], p['north']
    if 'rotation' in p:
        p['rotation'] = str((8 - int(p['rotation'])) % 16)
    for key in ('shape', 'hinge', 'type'):
        if p.get(key) in _HANDED:
            p[key] = _HANDED[p[key]]
    if 'left' in p and 'right' in p:
        p['left'], p['right'] = p['right'], p['left']
    return {'Name': state['Name'], 'Properties': p}


def rotate_pos(x, z, turns):
    """绕原点顺时针旋转（与原版 Rotation.CLOCKWISE_90 相同：(x, z) → (-z, x)）。"""
    for _ in range(turns % 4):
        x, z = -z, x
    return x, z


class Canvas:
    """稀疏体素：键 (x, y, z) → 方块状态。"""

    def __init__(self):
        self.cells = {}

    def set(self, x, y, z, state):
        if state is None:
            return
        self.cells[(x, y, z)] = state

    def get(self, x, y, z):
        return self.cells.get((x, y, z))

    def box(self, x0, y0, z0, x1, y1, z1, state):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, state)

    def clear(self, x0, y0, z0, x1, y1, z1):
        self.box(x0, y0, z0, x1, y1, z1, AIR)

    def paste(self, other, ox, oy, oz, turns=0, overwrite=True):
        """把局部画布按顺时针 turns 次旋转后贴到 (ox, oy, oz)；旋转后的包围盒左上角对齐。"""
        if not other.cells:
            return
        xs = [k[0] for k in other.cells]
        zs = [k[2] for k in other.cells]
        w, d = max(xs) + 1, max(zs) + 1
        corners = [rotate_pos(x, z, turns) for x, z in ((0, 0), (w - 1, 0), (0, d - 1), (w - 1, d - 1))]
        mx = min(c[0] for c in corners)
        mz = min(c[1] for c in corners)
        for (x, y, z), s in other.cells.items():
            rx, rz = rotate_pos(x, z, turns)
            key = (ox + rx - mx, oy + y, oz + rz - mz)
            if overwrite or key not in self.cells:
                self.cells[key] = rotate_state(s, turns)

    def bounds(self):
        xs = [k[0] for k in self.cells]
        ys = [k[1] for k in self.cells]
        zs = [k[2] for k in self.cells]
        return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))

    def normalized(self):
        """平移到原点开始，返回 (size, cells)。"""
        (x0, y0, z0), (x1, y1, z1) = self.bounds()
        cells = {(x - x0, y - y0, z - z0): s for (x, y, z), s in self.cells.items()}
        return [x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1], cells


# ---------- 材料 ----------
POST = B('stripped_dark_oak_log', axis='y')
BEAM_X = B('stripped_dark_oak_log', axis='x')
BEAM_Z = B('stripped_dark_oak_log', axis='z')
DARK_PLANKS = B('dark_oak_planks')
FLOOR = B('spruce_planks')
PLASTER = L('journey_white_plaster')
WAINSCOT = L('journey_wood_wainscot')
TILE = L('journey_kawara_roof')
STONE_BASE = B('stone_bricks')
PAVING = B('polished_andesite')
TATAMI_X = L('journey_tatami', axis='x')
TATAMI_Z = L('journey_tatami', axis='z')
LACQUER_LOG = L('journey_lacquer_log', axis='y')
LACQUER_PLANKS = L('journey_lacquer_planks')


def tile_stairs(facing, half='bottom', shape='straight'):
    return L('journey_kawara_stairs', facing=facing, half=half, shape=shape, waterlogged=False)


def tile_slab(kind='bottom'):
    return L('journey_kawara_slab', type=kind, waterlogged=False)


def lacquer_stairs(facing, half='bottom'):
    return L('journey_lacquer_stairs', facing=facing, half=half, shape='straight', waterlogged=False)


def lacquer_slab(kind='bottom'):
    return L('journey_lacquer_slab', type=kind, waterlogged=False)


def lacquer_fence(**sides):
    return L('journey_lacquer_fence', **{d: sides.get(d, False) for d in DIRS}, waterlogged=False)


def dark_stairs(facing, half='bottom'):
    return B('dark_oak_stairs', facing=facing, half=half, shape='straight', waterlogged=False)


def spruce_slab(kind='bottom'):
    return B('spruce_slab', type=kind, waterlogged=False)


def dark_slab(kind='bottom'):
    return B('dark_oak_slab', type=kind, waterlogged=False)


def shoji_window(facing):
    return L('journey_shoji_window', facing=facing, waterlogged=False)


def shoji_door(facing, half, hinge='left', open_=False):
    return L('journey_shoji_door', facing=facing, half=half, hinge=hinge, open=open_, powered=False)


def noren(facing, color='indigo'):
    name = 'journey_noren' if color == 'indigo' else f'journey_noren_{color}'
    return L(name, facing=facing)


def paper_lantern(hanging=True):
    return L('journey_paper_lantern', hanging=hanging, waterlogged=False)


def stone_lantern():
    return L('journey_stone_lantern')


def tall_lantern(half):
    return L('journey_stone_lantern_tall', half=half)


def trapdoor(facing, half='bottom', open_=True, wood='spruce'):
    return B(f'{wood}_trapdoor', facing=facing, half=half, open=open_, powered=False, waterlogged=False)


def fence(wood='spruce', **sides):
    return B(f'{wood}_fence', **{d: sides.get(d, False) for d in DIRS}, waterlogged=False)


def fence_line(canvas, pts, y, wood='spruce', state_fn=None):
    """沿一串相邻格子放栅栏并接好左右连接。"""
    s = set(pts)
    for (x, z) in pts:
        sides = {'north': (x, z - 1) in s, 'south': (x, z + 1) in s, 'west': (x - 1, z) in s, 'east': (x + 1, z) in s}
        canvas.set(x, y, z, state_fn(**sides) if state_fn else fence(wood, **sides))


# ---------- 构件 ----------
def gable_roof(c, x0, x1, z0, z1, y, ridge_axis='x', eave=1, ridge_cap=True, material='kawara'):
    """人字形灰瓦屋顶：屋脊沿 ridge_axis，四周出檐 eave 格，檐口用倒置楼梯收边。
    覆盖 [x0, x1]×[z0, z1]（不含出檐），自 y 起一层层收向屋脊。返回屋脊高度。"""
    stairs = tile_stairs
    if ridge_axis == 'x':
        a0, a1 = z0 - eave, z1 + eave
        layer = 0
        while a0 + layer <= a1 - layer:
            lo, hi = a0 + layer, a1 - layer
            yy = y + layer
            for x in range(x0 - eave, x1 + eave + 1):
                if lo == hi:
                    c.set(x, yy, lo, tile_slab('bottom') if not ridge_cap else TILE)
                elif hi - lo == 1:
                    c.set(x, yy, lo, stairs('south'))
                    c.set(x, yy, hi, stairs('north'))
                else:
                    c.set(x, yy, lo, stairs('south'))
                    c.set(x, yy, hi, stairs('north'))
                    if layer > 0:
                        # 山墙内侧填白墙，屋面下方用深色木
                        for zz in range(lo + 1, hi):
                            if x in (x0, x1):
                                c.set(x, yy, zz, PLASTER)
            # 山墙两端的博风：倒置楼梯与出檐
            layer += 1
        ridge = y + layer - 1
        if ridge_cap:
            for x in range(x0 - eave - 1, x1 + eave + 2):
                c.set(x, ridge + 1, (a0 + a1) // 2 if (a1 - a0) % 2 == 0 else a0 + layer - 1, tile_slab('bottom'))
        return ridge
    else:
        a0, a1 = x0 - eave, x1 + eave
        layer = 0
        while a0 + layer <= a1 - layer:
            lo, hi = a0 + layer, a1 - layer
            yy = y + layer
            for z in range(z0 - eave, z1 + eave + 1):
                if lo == hi:
                    c.set(lo, yy, z, tile_slab('bottom') if not ridge_cap else TILE)
                else:
                    c.set(lo, yy, z, stairs('east'))
                    c.set(hi, yy, z, stairs('west'))
                    if layer > 0:
                        for xx in range(lo + 1, hi):
                            if z in (z0, z1):
                                c.set(xx, yy, z, PLASTER)
            layer += 1
        ridge = y + layer - 1
        if ridge_cap:
            for z in range(z0 - eave - 1, z1 + eave + 2):
                c.set((a0 + a1) // 2 if (a1 - a0) % 2 == 0 else a0 + layer - 1, ridge + 1, z, tile_slab('bottom'))
        return ridge


def hip_roof(c, x0, x1, z0, z1, y, eave=1):
    """四坡（寄棟）灰瓦屋顶：四面同时收进，最后剩一条屋脊。返回屋脊高度。"""
    lx0, lx1, lz0, lz1 = x0 - eave, x1 + eave, z0 - eave, z1 + eave
    yy = y
    while lx0 <= lx1 and lz0 <= lz1:
        for x in range(lx0, lx1 + 1):
            for z in range(lz0, lz1 + 1):
                edge_n, edge_s, edge_w, edge_e = z == lz0, z == lz1, x == lx0, x == lx1
                if not (edge_n or edge_s or edge_w or edge_e):
                    continue
                if lx0 == lx1 or lz0 == lz1:
                    c.set(x, yy, z, TILE)
                    continue
                if edge_n and edge_w:
                    c.set(x, yy, z, tile_stairs('south', shape='outer_left'))
                elif edge_n and edge_e:
                    c.set(x, yy, z, tile_stairs('south', shape='outer_right'))
                elif edge_s and edge_w:
                    c.set(x, yy, z, tile_stairs('north', shape='outer_right'))
                elif edge_s and edge_e:
                    c.set(x, yy, z, tile_stairs('north', shape='outer_left'))
                elif edge_n:
                    c.set(x, yy, z, tile_stairs('south'))
                elif edge_s:
                    c.set(x, yy, z, tile_stairs('north'))
                elif edge_w:
                    c.set(x, yy, z, tile_stairs('east'))
                else:
                    c.set(x, yy, z, tile_stairs('west'))
        lx0 += 1
        lx1 -= 1
        lz0 += 1
        lz1 -= 1
        yy += 1
    # 屋脊压一层瓦片
    return yy - 1


def pent_roof(c, x0, x1, z, y, facing='north', depth=2):
    """临街一层的披檐（庇）：自墙面向外伸出 depth 格，逐格下降。"""
    dz = -1 if facing == 'north' else 1
    for k in range(depth):
        for x in range(x0, x1 + 1):
            c.set(x, y - k // 2, z + dz * (k + 1), tile_stairs('south' if facing == 'north' else 'north') if k % 2 == 0 else tile_slab('top'))


def timber_wall(c, x0, x1, z0, z1, y0, y1, posts_every=3, wainscot=1):
    """真壁造的墙：深色柱子按间距立起，柱间下段木腰墙、上段白灰泥，顶上一道横梁。"""
    pts = []
    if z0 == z1:
        pts = [(x, z0) for x in range(x0, x1 + 1)]
    elif x0 == x1:
        pts = [(x0, z) for z in range(z0, z1 + 1)]
    n = len(pts)
    for i, (x, z) in enumerate(pts):
        is_post = i == 0 or i == n - 1 or i % posts_every == 0
        for y in range(y0, y1 + 1):
            if is_post:
                c.set(x, y, z, POST)
            elif y == y1:
                c.set(x, y, z, BEAM_X if z0 == z1 else BEAM_Z)
            elif y < y0 + wainscot:
                c.set(x, y, z, WAINSCOT)
            else:
                c.set(x, y, z, PLASTER)


def interior_air(c, x0, x1, z0, z1, y0, y1):
    c.clear(x0, y0, z0, x1, y1, z1)


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def skirt_roof(c, x0, z0, x1, z1, y, overhang=1):
    """楼层之间的腰檐：沿矩形外围伸出 overhang 格，灰瓦楼梯高边朝墙。"""
    for k in range(overhang):
        ax0, az0, ax1, az1 = x0 - 1 - k, z0 - 1 - k, x1 + 1 + k, z1 + 1 + k
        yy = y - k
        for x in range(ax0, ax1 + 1):
            for z in (az0, az1):
                corner_w, corner_e = x == ax0, x == ax1
                facing = 'south' if z == az0 else 'north'
                if corner_w or corner_e:
                    shape = ('outer_left' if corner_w else 'outer_right') if z == az0 else ('outer_right' if corner_w else 'outer_left')
                    c.set(x, yy, z, tile_stairs(facing, shape=shape))
                else:
                    c.set(x, yy, z, tile_stairs(facing))
        for z in range(az0 + 1, az1):
            c.set(ax0, yy, z, tile_stairs('east'))
            c.set(ax1, yy, z, tile_stairs('west'))


def balcony(c, x0, z0, x1, z1, y, sides=('north',), rail='lacquer'):
    """外廊与栏杆：在矩形外围一格铺木板，外沿立栏杆（朱漆或深木）。"""
    pts = []
    for side in sides:
        if side == 'north':
            pts += [(x, z0 - 1) for x in range(x0 - 1, x1 + 2)]
        if side == 'south':
            pts += [(x, z1 + 1) for x in range(x0 - 1, x1 + 2)]
        if side == 'west':
            pts += [(x0 - 1, z) for z in range(z0 - 1, z1 + 2)]
        if side == 'east':
            pts += [(x1 + 1, z) for z in range(z0 - 1, z1 + 2)]
    for (x, z) in pts:
        c.set(x, y, z, B('spruce_slab', type='top', waterlogged=False))
    fn = lacquer_fence if rail == 'lacquer' else (lambda **s: fence('dark_oak', **s))
    rail_pts = []
    for side in sides:
        if side == 'north':
            rail_pts += [(x, z0 - 2) for x in range(x0 - 2, x1 + 3)]
        if side == 'south':
            rail_pts += [(x, z1 + 2) for x in range(x0 - 2, x1 + 3)]
        if side == 'west':
            rail_pts += [(x0 - 2, z) for z in range(z0 - 2, z1 + 3)]
        if side == 'east':
            rail_pts += [(x1 + 2, z) for z in range(z0 - 2, z1 + 3)]
    for (x, z) in rail_pts:
        c.set(x, y, z, B('spruce_slab', type='top', waterlogged=False))
    fence_line(c, rail_pts, y + 1, state_fn=fn)


def wa_building(c, x0, z0, x1, z1, y0=0, floors=1, floor_h=4, roof='hip', ridge_axis='x', eave=2,
                windows=True, skirts=True, tatami=True, bays=3, lit=True):
    """通用和风木构楼：石基、每层真壁造外墙与障子窗、层间腰檐、顶层灰瓦屋顶。
    返回 (各层地板 y 列表, 屋脊 y)。门洞等由调用方再开。"""
    c.box(x0, y0, z0, x1, y0, z1, STONE_BASE)
    levels = []
    y = y0 + 1
    for f in range(floors):
        top = y + floor_h - 1
        fy = y - 1 if f else y0
        c.box(x0 + 1, fy, z0 + 1, x1 - 1, fy, z1 - 1, FLOOR)
        c.clear(x0 + 1, y, z0 + 1, x1 - 1, top, z1 - 1)
        if tatami:
            # 整块榻榻米铺在地板层，地面与门槛齐平；纵横交错铺成市松
            for x in range(x0 + 1, x1):
                for z in range(z0 + 1, z1):
                    c.set(x, fy, z, TATAMI_X if ((x - x0 - 1) // 2 + (z - z0 - 1)) % 2 else TATAMI_Z)
        ws = 1 if f == 0 else 0  # 一层带木腰墙，上层整面白壁
        timber_wall(c, x0, x1, z0, z0, y, top, posts_every=bays, wainscot=ws)
        timber_wall(c, x0, x1, z1, z1, y, top, posts_every=bays, wainscot=ws)
        timber_wall(c, x0, x0, z0, z1, y, top, posts_every=bays, wainscot=ws)
        timber_wall(c, x1, x1, z0, z1, y, top, posts_every=bays, wainscot=ws)
        if windows:
            for x in range(x0 + 1, x1):
                if (x - x0) % bays and (x - x0) % bays != bays - 1 or bays <= 2:
                    if (x - x0) % bays:
                        c.set(x, y + 1, z0, shoji_window('north'))
                        c.set(x, y + 1, z1, shoji_window('south'))
            for z in range(z0 + 1, z1):
                if (z - z0) % bays:
                    c.set(x0, y + 1, z, shoji_window('west'))
                    c.set(x1, y + 1, z, shoji_window('east'))
        if lit:
            c.set((x0 + x1) // 2, top, (z0 + z1) // 2, paper_lantern(True))
        levels.append(y)
        y = top + 1
        c.box(x0, y, z0, x1, y, z1, DARK_PLANKS)
        if skirts and f < floors - 1:
            skirt_roof(c, x0, z0, x1, z1, y, overhang=1)
        y += 1
    # 屋面从墙顶横梁那一层起出檐，压住墙头，不在墙与屋面之间留一道缝
    if roof == 'hip':
        ridge = hip_roof(c, x0, x1, z0, z1, y - 2, eave=eave)
    else:
        ridge = gable_roof(c, x0, x1, z0, z1, y - 2, ridge_axis=ridge_axis, eave=eave)
    return levels, ridge


def door(c, x, y, z, facing, hinge='left'):
    """障子拉门（两格高）。"""
    c.set(x, y, z, shoji_door(facing, 'lower', hinge))
    c.set(x, y + 1, z, shoji_door(facing, 'upper', hinge))


def low_table(c, x, y, z):
    c.set(x, y, z, B('dark_oak_trapdoor', facing='north', half='top', open=False, powered=False, waterlogged=False))


def cushion(c, x, y, z, color='red'):
    c.set(x, y, z, B(f'{color}_carpet'))
