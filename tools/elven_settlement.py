"""世界树精灵聚落：盘旋外廊、多层依附木屋群、高空主枝悬吊屋、树根集市、玻璃花房与跨树廊桥网。"""
import math
import numpy as np

NS = 'logos_dimension:'


def state(name, **props):
    return {
        'Name': name if ':' in name else NS + name,
        **({'Properties': {k: str(v).lower() for k, v in props.items()}} if props else {})
    }


def trunk_center(y):
    return math.sin(y / 48.0) * 2.5, math.sin(y / 59.0) * 2.0


def trunk_outer_radius(y, theta):
    r = (23.5 - y * 0.018 if y <= 96 else 21.77 - (y - 96) * 0.22) + 5.0 * math.exp(-y / 14.0)
    ridge = 1.0 + 0.07 * math.cos(theta * 9.0 - y * 0.018) + 0.035 * math.sin(theta * 5.0 + y * 0.04)
    return r * ridge


def _basis(angle):
    """返回沿径向向外 (rx, rz) 与切向逆时针 (tx, tz) 的连续正交单位向量。"""
    rx, rz = math.cos(angle), math.sin(angle)
    tx, tz = -math.sin(angle), math.cos(angle)
    return (rx, rz), (tx, tz)


def _cardinal_basis(angle):
    """将任意角度吸附到最近的四个正交主轴方向之一，使建筑墙体、地板、窗户与楼梯屋顶在体素网格上 100% 严丝合缝无漏洞。"""
    ca, sa = math.cos(angle), math.sin(angle)
    if abs(ca) >= abs(sa):
        rx, rz = (1, 0) if ca >= 0 else (-1, 0)
        tx, tz = (0, 1) if rx == 1 else (0, -1)
    else:
        rx, rz = (0, 1) if sa >= 0 else (0, -1)
        tx, tz = (-1, 0) if rz == 1 else (1, 0)
    return (rx, rz), (tx, tz)


def _vec_facing(vx, vz):
    """根据世界坐标方向向量返回正交朝向（north/south/east/west）。"""
    if abs(vx) >= abs(vz):
        return 'east' if vx >= 0 else 'west'
    return 'south' if vz >= 0 else 'north'


def main_bough_curve(index, t):
    """精确复现 elven_tree_study.world_tree() 中 8 条主枝的三次贝塞尔中心线坐标与半径。"""
    rng = np.random.default_rng(31)
    for i in range(8):
        reach = 55.0 + rng.uniform(-5.0, 7.0)
        end_y = 148.0 + rng.uniform(-5.0, 18.0)
        if i == index:
            a = i * math.tau / 8.0
            p0 = np.array([math.cos(a) * 8.0, 83.0 + (i % 3) * 10.0, math.sin(a) * 8.0])
            p3 = np.array([math.cos(a) * reach, end_y, math.sin(a) * reach])
            p1 = np.array([p3[0] * 0.42, 113.0, p3[2] * 0.42])
            p2 = np.array([p3[0] * 0.90, p3[1] - 6.0, p3[2] * 0.90])
            pt = (1 - t) ** 3 * p0 + 3 * (1 - t) ** 2 * t * p1 + 3 * (1 - t) * t * t * p2 + t ** 3 * p3
            rad = 8.0 * (1 - t) + 2.3 * t
            return pt, rad
    raise ValueError(index)


def build_walkway_segment(cells, p0, p1, width=4, rail=True, lamp_every=9, strut_to_trunk=False, clear_ends=False):
    """使用二维逆向投影光栅化铺设无漏洞、双层实心底梁、无悬空半砖踏步与正交四连通护栏的木栈道。
    当 clear_ends=True 时，自动清除栈道两端衔接平台边沿的护栏与花坛，确保玩家与精灵寻路完全畅通且零高差衔接。
    """
    x0, y0, z0 = float(p0[0]), float(p0[1]), float(p0[2])
    x1, y1, z1 = float(p1[0]), float(p1[1]), float(p1[2])
    dx, dz = x1 - x0, z1 - z0
    dist = math.hypot(dx, dz)
    if dist < 0.5:
        return
    ux, uz = dx / dist, dz / dist
    px, pz = -uz, ux
    half_w = width / 2.0
    rail_w = half_w + (0.85 if rail else 0.0)
    flat_seg = (abs(y1 - y0) <= 0.6)

    def eval_point(wx, wz):
        rx, rz = wx - x0, wz - z0
        proj = rx * ux + rz * uz
        perp = rx * px + rz * pz
        t = max(0.0, min(1.0, proj / dist))
        yf = y0 + (y1 - y0) * t
        return proj, perp, yf

    margin = int(math.ceil(rail_w)) + 3
    min_x = int(math.floor(min(x0, x1))) - margin
    max_x = int(math.ceil(max(x0, x1))) + margin
    min_z = int(math.floor(min(z0, z1))) - margin
    max_z = int(math.ceil(max(z0, z1))) + margin

    deck_cells = {}
    rail_cells = {}
    end_clear_cells = []

    for wx in range(min_x, max_x + 1):
        for wz in range(min_z, max_z + 1):
            proj, perp, yf = eval_point(wx, wz)
            d_perp = abs(perp)
            if clear_ends and (-2.2 <= proj < 0.5 or dist - 0.5 < proj <= dist + 2.2) and d_perp <= half_w + 0.35:
                end_clear_cells.append((wx, int(round(y0 if proj < 0.5 else y1)), wz))
            if proj < -0.65 or proj > dist + 0.65:
                continue
            if d_perp > rail_w:
                continue
            yi = int(math.floor(yf))
            frac = yf - yi
            if d_perp <= half_w:
                deck_cells[wx, wz] = (yi, frac, proj, perp)
            elif rail:
                if strut_to_trunk and perp < 0:
                    deck_cells[wx, wz] = (yi, frac, proj, perp)
                elif 0.35 <= proj <= dist - 0.35:
                    is_outer_rim = False
                    for sx in (-1, 0, 1):
                        for sz in (-1, 0, 1):
                            if sx == 0 and sz == 0:
                                continue
                            _, nb_perp, _ = eval_point(wx + sx, wz + sz)
                            if abs(nb_perp) > rail_w:
                                is_outer_rim = True
                                break
                        if is_outer_rim:
                            break
                    if is_outer_rim:
                        rail_cells[wx, wz] = (yi, frac, proj, perp)
                    else:
                        deck_cells[wx, wz] = (yi, frac, proj, perp)
                else:
                    deck_cells[wx, wz] = (yi, frac, proj, perp)

    all_cells = {**deck_cells, **rail_cells}

    # 1. 铺设双层实心木栈道桥面（杜绝悬空上层半砖与阶梯断层漏洞）
    for (wx, wz), (yi, frac, proj, perp) in deck_cells.items():
        min_nb_y = yi
        for sdx, sdz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (wx + sdx, wz + sdz) in all_cells:
                min_nb_y = min(min_nb_y, all_cells[wx + sdx, wz + sdz][0])
        for dy in range(1, 5):
            cells[wx, yi + dy, wz] = state('minecraft:air')
        cells[wx, min_nb_y - 1, wz] = state('elven_heartwood', axis='y')
        for fill_y in range(min_nb_y, yi):
            cells[wx, fill_y, wz] = state('elven_pale_planks')
        if flat_seg or frac >= 0.5:
            cells[wx, yi, wz] = state('elven_pale_planks')
        else:
            cells[wx, yi - 1, wz] = state('elven_pale_planks')
            cells[wx, yi, wz] = state('minecraft:birch_slab', type='bottom', waterlogged=False)

    # 2. 若紧贴世界树干，将内侧桥面严密延伸贴合至树皮外壁，并向树干打入承重斜撑
    if strut_to_trunk:
        for step in range(int(dist) + 1):
            t = step / max(1.0, dist)
            cx = x0 + dx * t
            cz = z0 + dz * t
            yi = int(math.floor(y0 + (y1 - y0) * t))
            tcx, tcz = trunk_center(yi)
            ang = math.atan2(cz - tcz, cx - tcx)
            rx, rz = math.cos(ang), math.sin(ang)
            r_bark = trunk_outer_radius(yi, ang)
            r_curr = math.hypot(cx - tcx, cz - tcz)
            for r_fill in np.linspace(r_bark - 0.5, r_curr, 6):
                fx = int(round(tcx + rx * r_fill))
                fz = int(round(tcz + rz * r_fill))
                if (fx, fz) not in deck_cells and (fx, fz) not in rail_cells:
                    for dy in range(1, 5):
                        cells[fx, yi + dy, fz] = state('minecraft:air')
                    cells[fx, yi, fz] = state('elven_pale_planks')
                    cells[fx, yi - 1, fz] = state('elven_heartwood', axis='y')
            if step % 6 == 0:
                for k in range(1, 6):
                    sx = int(round(cx - k * rx))
                    sz = int(round(cz - k * rz))
                    cells[sx, yi - 1 - k, sz] = state('elven_heartwood', axis='y')

    # 3. 铺设外沿路缘木与正交四连通护栏（含高低落差处的双格立柱过渡）
    for (wx, wz), (yi, frac, proj, perp) in rail_cells.items():
        min_nb_y = yi
        max_rail_nb_y = yi
        for sdx, sdz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (wx + sdx, wz + sdz) in all_cells:
                min_nb_y = min(min_nb_y, all_cells[wx + sdx, wz + sdz][0])
            if (wx + sdx, wz + sdz) in rail_cells:
                max_rail_nb_y = max(max_rail_nb_y, rail_cells[wx + sdx, wz + sdz][0])
        for dy in range(2, 5):
            cells[wx, yi + dy, wz] = state('minecraft:air')
        cells[wx, min_nb_y - 1, wz] = state('elven_heartwood', axis='y')
        for fill_y in range(min_nb_y, yi):
            cells[wx, fill_y, wz] = state('minecraft:spruce_planks')
        cells[wx, yi, wz] = state('minecraft:spruce_planks')
        cells[wx, yi + 1, wz] = state('minecraft:birch_fence')
        if max_rail_nb_y > yi:
            cells[wx, yi + 2, wz] = state('minecraft:birch_fence')
        elif lamp_every and abs(proj - round(proj / lamp_every) * lamp_every) < 0.45 and 1.5 < proj < dist - 1.5:
            cells[wx, yi + 2, wz] = state('minecraft:birch_fence')
            cells[wx, yi + 3, wz] = state('elven_leaf_lamp', facing='north')

    # 4. 清除两端交界处的护栏、花坛或树叶阻挡，使栈桥与平台地面无缝平接
    for cwx, cwy, cwz in end_clear_cells:
        cells[cwx, cwy, cwz] = state('elven_pale_planks')
        cells[cwx, cwy - 1, cwz] = state('elven_heartwood', axis='y')
        for dy in range(1, 5):
            old_name = cells.get((cwx, cwy + dy, cwz), {}).get('Name', '')
            if any(k in old_name for k in ('fence', 'planter', 'canopy', 'leaf', 'slab', 'vine')):
                cells[cwx, cwy + dy, cwz] = state('minecraft:air')


def build_trestle_pier(cells, cx, y_top, cz):
    """在跨树长栈桥中段建造直达地表的精灵木构高桥墩（呼应概念图1右侧高架木桥墩）。"""
    cx, y_top, cz = int(round(cx)), int(round(y_top)), int(round(cz))
    for dx in range(-3, 4):
        for dz in range(-3, 4):
            if abs(dx) == 3 or abs(dz) == 3:
                cells[cx + dx, y_top - 1, cz + dz] = state('elven_heartwood', axis='y')
    for k in range(1, 6):
        for sx, sz in [(-1, -1), (-1, 1), (1, -1), (1, 1)]:
            cells[cx + sx * (1 + k // 2), y_top - 6 + k, cz + sz * (1 + k // 2)] = state('elven_heartwood', axis='y')
    for y in range(0, y_top):
        for sx, sz in [(-1, -1), (-1, 1), (1, -1), (1, 1)]:
            cells[cx + sx, y, cz + sz] = state('elven_heartwood', axis='y')
        if y % 6 == 0 and y > 2:
            for d in (-1, 0, 1):
                cells[cx + d, y, cz - 1] = state('elven_heartwood', axis='x')
                cells[cx + d, y, cz + 1] = state('elven_heartwood', axis='x')
                cells[cx - 1, y, cz + d] = state('elven_heartwood', axis='z')
                cells[cx + 1, y, cz + d] = state('elven_heartwood', axis='z')
            cells[cx, y + 1, cz] = state('elven_leaf_lamp', facing='north')


def build_trunk_cottage(cells, sp_node, two_story=False, style=0):
    """在主盘旋栈道外侧悬挑平台建造正交对齐的奶油白墙木架精灵屋。
    房屋主体位于主盘旋栈道外缘之外 (dr >= 0)，不截断主栈道坡面；
    通过 build_walkway_segment 从正门 (dr=1, dt=0) 向螺旋栈道中心 (sp_x, y_floor, sp_z) 架设平桥，彻底消除 1.5 格高差与挡路护栏。
    """
    sp_x, sp_y, sp_z, angle = sp_node
    y_floor = int(math.floor(sp_y))
    (rx, rz), (tx, tz) = _cardinal_basis(angle)
    # 锚点设于螺旋栈道外沿之外，确保即使在斜角处房屋侧翼也不会切入螺旋栈道造成高差断层
    ax = int(round(sp_x + math.cos(angle) * 4.0 + rx * 2.0))
    az = int(round(sp_z + math.sin(angle) * 4.0 + rz * 2.0))
    r_outer = 10 if two_story else 9
    t_half = 6 if two_story else 5

    def to_world(dr, dt, dy):
        return ax + dr * rx + dt * tx, y_floor + dy, az + dr * rz + dt * tz

    def put(dr, dt, dy, name, **props):
        wx, wy, wz = to_world(dr, dt, dy)
        cells[wx, wy, wz] = state(name, **props)
        return wx, wy, wz

    # 1. 铺设房屋悬挑主平台 (dr = 0 .. r_outer, dt = -t_half .. t_half)
    top_clear = 15 if two_story else 11
    for dr in range(0, r_outer + 1):
        for dt in range(-t_half, t_half + 1):
            for dy in range(1, top_clear + 1):
                put(dr, dt, dy, 'minecraft:air')
            edge = (dr in (0, r_outer) or abs(dt) == t_half)
            put(dr, dt, 0, 'minecraft:spruce_planks' if edge else 'elven_pale_planks')
            put(dr, dt, -1, 'elven_heartwood' if edge else 'minecraft:spruce_planks', **({'axis': 'y'} if edge else {}))

    # 2. 深入世界树干下方的长斜撑木托架（从 dy = -2 起向下延伸，绝不触碰上方螺旋栈道）
    beam_axis = 'x' if abs(rx) >= abs(rz) else 'z'
    cross_axis = 'x' if abs(tx) >= abs(tz) else 'z'
    for dt in (-t_half + 1, 0, t_half - 1):
        for k in range(1, 9):
            wx, _, wz = to_world(r_outer - 1 - k * 2, dt, -1 - k)
            if math.hypot(wx, wz) >= 15.5:
                put(r_outer - 1 - k * 2, dt, -1 - k, 'elven_heartwood', axis='y')
                put(r_outer - k * 2, dt, -1 - k, 'elven_heartwood', axis=beam_axis)

    # 3. 平台护栏与花坛（内侧 dr=0 在 dt in -1..1 处留出 3 格宽敞大门洞）
    out_facing = _vec_facing(rx, rz)
    in_facing = _vec_facing(-rx, -rz)
    t_pos_facing = _vec_facing(tx, tz)
    t_neg_facing = _vec_facing(-tx, -tz)

    for dt in range(-t_half, t_half + 1):
        put(r_outer, dt, 1, 'minecraft:birch_fence')
        if abs(dt) >= 2:
            put(0, dt, 1, 'minecraft:birch_fence')
        if dt % 3 == 0:
            put(r_outer - 1, dt, 1, 'elven_flower_planter', facing=in_facing)
            put(r_outer, dt, 0, 'elven_canopy', distance=1, persistent=True, waterlogged=False, season=0)
            for hang in range(1, 3 + (abs(dt) % 3)):
                put(r_outer, dt, -hang, 'elven_lamp_vine', facing=out_facing)
    for dr in range(0, r_outer + 1):
        for side in (-t_half, t_half):
            put(dr, side, 1, 'minecraft:birch_fence')
    for dt in (-t_half, t_half):
        put(r_outer, dt, 2, 'minecraft:birch_fence')
        put(r_outer, dt, 3, 'elven_leaf_lamp', facing=out_facing)

    # 4. 房屋主体（位于 dr = 1 .. r_outer - 2）
    h_r0, h_r1 = 1, r_outer - 2
    h_t0, h_t1 = -t_half + 1, t_half - 1
    wall_h = 8 if two_story else 4

    for dy in range(1, wall_h + 1):
        for dr in range(h_r0, h_r1 + 1):
            for dt in range(h_t0, h_t1 + 1):
                is_corner = (dr in (h_r0, h_r1)) and (dt in (h_t0, h_t1))
                is_wall = (dr in (h_r0, h_r1)) or (dt in (h_t0, h_t1))
                if is_corner:
                    put(dr, dt, dy, 'elven_heartwood', axis='y')
                elif is_wall:
                    if two_story and dy == 5:
                        put(dr, dt, dy, 'elven_heartwood', axis=cross_axis if dr in (h_r0, h_r1) else beam_axis)
                    else:
                        put(dr, dt, dy, 'journey_lime_plaster')
                elif two_story and dy == 5:
                    put(dr, dt, dy, 'elven_pale_planks')
                else:
                    put(dr, dt, dy, 'minecraft:air')

    # 内侧正门（3 格宽 dt in -1..1）与外侧观景门洞
    for dt in (-1, 0, 1):
        for dy in (1, 2, 3):
            put(h_r0, dt, dy, 'minecraft:air')
    for dt in (-1, 0):
        for dy in (1, 2, 3):
            put(h_r1, dt, dy, 'minecraft:air')

    # 5. 架设正门至螺旋栈道中心 (sp_x, y_floor, sp_z) 的 3 格宽水平短桥，并自动清除两端交界护栏
    door_world = to_world(h_r0, 0, 0)
    build_walkway_segment(cells, (sp_x, y_floor, sp_z), door_world, width=3, rail=True, lamp_every=0, clear_ends=True)
    for dt in (-1, 0, 1):
        for dr in (0, 1, 2):
            put(dr, dt, 0, 'elven_pale_planks')
            for dy in (1, 2, 3):
                put(dr, dt, dy, 'minecraft:air')

    if two_story:
        put(h_r0 + 1, h_t1 - 1, 5, 'elven_climbing_rope')
        for ry_rope in range(1, 5):
            put(h_r0 + 1, h_t1 - 1, ry_rope, 'elven_climbing_rope')
        for dt in range(h_t0 + 1, h_t1):
            stripe = 'minecraft:moss_block' if dt % 2 == 0 else 'minecraft:white_wool'
            put(h_r1 + 1, dt, 4, stripe)
            put(h_r1 + 2, dt, 3, stripe)

    # 窗户与窗台花槽
    win_levels = [2, 6] if two_story else [2]
    for wy_off in win_levels:
        for side in (h_t0, h_t1):
            for dr in range(h_r0 + 2, h_r1 - 1):
                if two_story and side == h_t1 and dr == h_r0 + 1:
                    continue
                put(dr, side, wy_off, 'minecraft:glass_pane')
                put(dr, side, wy_off + 1, 'minecraft:glass_pane')
            put((h_r0 + h_r1) // 2, side + (-1 if side < 0 else 1), wy_off - 1, 'elven_flower_planter',
                facing=t_neg_facing if side < 0 else t_pos_facing)
        for dt in (h_t0 + 2, h_t1 - 2):
            put(h_r1, dt, wy_off, 'minecraft:glass_pane')
            put(h_r1, dt, wy_off + 1, 'minecraft:glass_pane')

    # 6. 双坡深木屋顶（带实心木板衬底与严密山墙封口）
    roof_base = wall_h + 1
    half_span = (h_t1 - h_t0) // 2 + 1
    stair_block = 'minecraft:dark_oak_stairs' if style % 2 else 'minecraft:spruce_stairs'
    plank_backing = 'minecraft:dark_oak_planks' if style % 2 else 'minecraft:spruce_planks'
    for dt in range(h_t0 - 1, h_t1 + 2):
        rise = max(0, (half_span - abs(dt)) // (1 if style % 2 == 0 else 2))
        ry = roof_base + rise
        stair_face = t_pos_facing if dt < 0 else t_neg_facing
        for dr in range(h_r0 - 1, h_r1 + 2):
            if dt == 0:
                put(dr, dt, ry, plank_backing)
                if ry > roof_base:
                    put(dr, dt, ry - 1, 'elven_heartwood', axis=beam_axis)
            else:
                put(dr, dt, ry, stair_block, facing=stair_face, half='bottom', shape='straight', waterlogged=False)
                if abs(dt) <= half_span - 1 and ry > roof_base:
                    put(dr, dt, ry - 1, 'elven_pale_planks')
            if dr in (h_r0, h_r1) and abs(dt) <= half_span - 1:
                for fill_y in range(roof_base, ry):
                    put(dr, dt, fill_y, 'journey_lime_plaster')
        if abs(dt) == half_span:
            for dr in range(h_r0, h_r1 + 1, 2):
                put(dr, dt, ry + 1, 'elven_canopy', distance=1, persistent=True, waterlogged=False, season=0)
            for dr in (h_r0 - 1, h_r1 + 1):
                put(dr, dt, ry - 1, 'elven_leaf_lamp', facing=out_facing)

    # 7. 室内精灵家具与照明（桌在 h_t0 + 1，椅在 h_t0 + 2，椅面朝 -t 即 t_neg_facing 正对阅览桌）
    put(h_r0 + 2, h_t0 + 1, 1, 'elven_reading_table', facing=t_pos_facing)
    put(h_r0 + 2, h_t0 + 2, 1, 'elven_leaf_seat', facing=t_neg_facing)
    put(h_r1 - 1, h_t1 - 1, 1, 'elven_bookshelf', facing=t_neg_facing, half='lower')
    put(h_r1 - 1, h_t1 - 1, 2, 'elven_bookshelf', facing=t_neg_facing, half='upper')
    put((h_r0 + h_r1) // 2, 0, wall_h, 'elven_leaf_lamp', facing=out_facing)

    wx, wy, wz = to_world((h_r0 + h_r1) // 2, 0, 1)
    return {'center': [wx, wy, wz], 'floor': y_floor, 'angle': angle, 'two_story': two_story}


# 玻璃花房培养罐可种植的非金属作物与炼金草药清单（严禁金属荚：copper/iron/gold/netherite_pod）
GREENHOUSE_JAR_PLANTS = [
    ('logos:mandrake_seed', 'logos:mandrake_crop', 3, 'logos:raw_mash', 3),
    ('logos:starlight_moss_spore', 'logos:starlight_moss_crop', 3, None, 0),
    ('logos:mercury_spore', 'logos:mercury_mushroom_crop', 2, 'logos:raw_mash', 2),
    ('logos:niter_sprout', 'logos:niter_sprout_crop', 3, None, 0),
    ('logos_dimension:journey_berry_bramble', 'logos_dimension:journey_berry_bramble', 3, 'logos:raw_mash', 3),
    ('minecraft:sweet_berries', 'minecraft:sweet_berry_bush', 3, None, 0),
    ('minecraft:carrot', 'minecraft:carrots', 7, 'logos:solar_amalgam', 2),
    ('minecraft:wheat_seeds', 'minecraft:wheat', 7, None, 0),
    ('minecraft:beetroot_seeds', 'minecraft:beetroots', 3, 'logos:raw_mash', 2),
    ('minecraft:potato', 'minecraft:potatoes', 7, None, 0),
    ('minecraft:nether_wart', 'minecraft:nether_wart', 3, 'logos:raw_mash', 3),
    ('minecraft:torchflower_seeds', 'minecraft:torchflower_crop', 1, None, 0),
]


def _cultivation_jar_nbt(seed_id, plant_id, age, medium_id=None, medium_uses=0):
    """构造 Logos 培养罐方块实体 NBT（包含非金属种子、植株状态、生长阶段与魔力）。"""
    nbt = {
        'id': 'logos:cultivation_jar',
        'seed': {'stacks': [{'id': seed_id, 'count': 1}]},
        'plant': plant_id,
        'age': int(age),
        'mana': 500.0,
    }
    if medium_id:
        nbt['medium'] = {'id': medium_id, 'count': 1}
        nbt['medium_uses'] = int(medium_uses)
    return nbt


def build_greenhouse_terrace(cells, sp_node, block_entities=None, variant=0):
    """在主盘旋栈道外侧挑台建造正交对齐的精灵玻璃花房温室（通过 3 格宽平桥与螺旋栈道同高程无缝连接）。"""
    sp_x, sp_y, sp_z, angle = sp_node
    y_floor = int(math.floor(sp_y))
    (rx, rz), (tx, tz) = _cardinal_basis(angle)
    ax = int(round(sp_x + math.cos(angle) * 4.0 + rx * 2.0))
    az = int(round(sp_z + math.sin(angle) * 4.0 + rz * 2.0))
    r_outer = 10
    t_half = 6
    out_facing = _vec_facing(rx, rz)

    def to_world(dr, dt, dy):
        return ax + dr * rx + dt * tx, y_floor + dy, az + dr * rz + dt * tz

    def put(dr, dt, dy, name, **props):
        wx, wy, wz = to_world(dr, dt, dy)
        cells[wx, wy, wz] = state(name, **props)
        return wx, wy, wz

    # 1. 花房主平台 (dr = 0 .. r_outer)
    for dr in range(0, r_outer + 1):
        for dt in range(-t_half, t_half + 1):
            for dy in range(1, 11):
                put(dr, dt, dy, 'minecraft:air')
            edge = (abs(dt) == t_half or dr in (0, r_outer))
            put(dr, dt, 0, 'minecraft:spruce_planks' if edge else 'elven_pale_planks')
            put(dr, dt, -1, 'elven_heartwood' if edge else 'minecraft:spruce_planks', **({'axis': 'y'} if edge else {}))

    for dt in (-t_half + 1, 0, t_half - 1):
        for k in range(1, 8):
            wx, _, wz = to_world(r_outer - 1 - k * 2, dt, -1 - k)
            if math.hypot(wx, wz) >= 15.5:
                put(r_outer - 1 - k * 2, dt, -1 - k, 'elven_heartwood', axis='y')

    for dt in range(-t_half, t_half + 1):
        put(r_outer, dt, 1, 'minecraft:birch_fence')
        if abs(dt) >= 2:
            put(0, dt, 1, 'minecraft:birch_fence')
    for dr in range(0, r_outer + 1):
        for side in (-t_half, t_half):
            put(dr, side, 1, 'minecraft:birch_fence')
    for dt in (-t_half, t_half):
        put(r_outer, dt, 2, 'minecraft:birch_fence')
        put(r_outer, dt, 3, 'elven_leaf_lamp', facing=out_facing)

    # 2. 玻璃花房四壁与完整封闭的拱顶/山墙 (dr = 1 .. 8)
    for dy in range(1, 5):
        for dr in range(1, 9):
            for dt in range(-5, 6):
                edge_r = dr in (1, 8)
                edge_t = dt in (-5, 5)
                if edge_r and edge_t:
                    put(dr, dt, dy, 'elven_pale_planks')
                elif edge_r or edge_t:
                    if dr == 1 and dt in (-1, 0, 1) and dy <= 3:
                        put(dr, dt, dy, 'minecraft:air')
                    elif dr in (1, 5, 8) and dy == 4:
                        put(dr, dt, dy, 'elven_pale_planks')
                    else:
                        put(dr, dt, dy, 'distillation_glass')
                else:
                    put(dr, dt, dy, 'minecraft:air')

    # 正门 3 格宽平接栈桥直连螺旋栈道中心 (sp_x, y_floor, sp_z)
    build_walkway_segment(cells, (sp_x, y_floor, sp_z), to_world(1, 0, 0), width=3, rail=True, lamp_every=0, clear_ends=True)
    for dt in (-1, 0, 1):
        for dr in (0, 1, 2):
            put(dr, dt, 0, 'elven_pale_planks')
            for dy in (1, 2, 3):
                put(dr, dt, dy, 'minecraft:air')

    for dt in range(-5, 6):
        rise = 3 - abs(dt) // 2
        ry = 4 + rise
        for dr in range(1, 9):
            is_rib = dr in (1, 5, 8) or dt == 0
            put(dr, dt, ry, 'elven_pale_planks' if is_rib else 'distillation_glass')
            if abs(dt) in (1, 3):
                put(dr, dt, ry - 1, 'elven_pale_planks')
            if dr in (1, 8) and abs(dt) < 5:
                for fill_y in range(5, ry):
                    put(dr, dt, fill_y, 'elven_pale_planks' if dt == 0 else 'distillation_glass')

    # 3. 花房内部：两侧木制培育台 + 中央苔藓花圃 + 10 座种满非金属草药与作物的 Logos 培养罐
    jar_slots = [
        (2, -4, 2, True),
        (4, -4, 2, True),
        (6, -4, 2, True),
        (2, 4, 2, True),
        (4, 4, 2, True),
        (6, 4, 2, True),
        (3, -2, 1, False),
        (6, -2, 1, False),
        (3, 2, 1, False),
        (6, 2, 1, False),
    ]
    for dr in range(2, 8):
        for side_dt in (-4, 4):
            put(dr, side_dt, 1, 'minecraft:spruce_planks' if dr in (2, 4, 6) else 'elven_reading_table',
                **({'facing': out_facing} if dr not in (2, 4, 6) else {}))
    for dr in (2, 4, 7):
        for dt in (-2, 2):
            put(dr, dt, 1, 'elven_flower_planter', facing=out_facing)
    for dr in (3, 4, 5, 6):
        put(dr, 0, 1, 'minecraft:moss_block' if dr in (3, 6) else 'elven_reading_table',
            **({'facing': out_facing} if dr in (4, 5) else {}))

    placed_jars = []
    seen_pos = set()
    for idx, (dr, dt, dy, _) in enumerate(jar_slots):
        plant_spec = GREENHOUSE_JAR_PLANTS[(variant * 7 + idx) % len(GREENHOUSE_JAR_PLANTS)]
        seed_id, plant_id, age, medium_id, medium_uses = plant_spec
        wx, wy, wz = put(dr, dt, dy, 'logos:cultivation_jar', medium=(medium_id is not None))
        if (wx, wy, wz) not in seen_pos:
            seen_pos.add((wx, wy, wz))
            if block_entities is not None:
                block_entities[wx, wy, wz] = _cultivation_jar_nbt(seed_id, plant_id, age, medium_id, medium_uses)
            placed_jars.append({'pos': [wx, wy, wz], 'seed': seed_id, 'plant': plant_id, 'age': age})

    put(5, 0, 2, 'elven_bough_light')
    put(5, 0, 6, 'elven_leaf_lamp', facing=out_facing)
    return placed_jars


def build_market_stall(cells, cx, y_floor, cz, angle):
    """在树根拱间平台建造正交对齐的绿白条纹遮阳篷精灵集市摊位（后侧 3 格宽敞口与支路栈道平接）。"""
    ax, ay, az = int(round(cx)), int(round(y_floor)), int(round(cz))
    (rx, rz), (tx, tz) = _cardinal_basis(angle)
    out_facing = _vec_facing(rx, rz)

    def put(dr, dt, dy, name, **props):
        wx = ax + dr * rx + dt * tx
        wz = az + dr * rz + dt * tz
        wy = ay + dy
        cells[wx, wy, wz] = state(name, **props)

    for dr in range(-4, 5):
        for dt in range(-5, 6):
            for dy in range(1, 7):
                put(dr, dt, dy, 'minecraft:air')
            edge = abs(dr) == 4 or abs(dt) == 5
            put(dr, dt, 0, 'minecraft:spruce_planks' if edge else 'elven_pale_planks')
            put(dr, dt, -1, 'elven_heartwood' if edge else 'minecraft:spruce_planks', **({'axis': 'y'} if edge else {}))
            if edge and dr >= 2:
                put(dr, dt, 1, 'minecraft:birch_fence')
    for dr in (-4, 4):
        for dt in (-5, 5):
            for y_down in range(1, ay + 3):
                put(dr, dt, -y_down, 'elven_heartwood', axis='y')

    for dr in (-2, 2):
        for dt in (-4, 4):
            for dy in range(1, 4):
                put(dr, dt, dy, 'minecraft:birch_fence')

    for dt in range(-4, 5):
        stripe = 'minecraft:moss_block' if dt % 2 == 0 else 'minecraft:white_wool'
        for dr in range(-3, 4):
            dy = 4 if dr <= 0 else 3
            put(dr, dt, dy, stripe)
            if dr == 0:
                put(dr, dt, 3, 'elven_pale_planks')

    for dt in range(-3, 4):
        put(1, dt, 1, 'elven_reading_table' if dt % 2 == 0 else 'elven_flower_planter', facing=out_facing)
    for dt in (-4, 4):
        put(3, dt, 2, 'elven_leaf_lamp', facing=out_facing)


def build_suspended_bough_house(cells, bough_index, t_pos, y_deck, style=0):
    """在世界树第 bough_index 条主枝正下方指定高程 y_deck 悬吊吊篮木屋，四角长索直通主枝木心。"""
    bough_pt, bough_r = main_bough_curve(bough_index, t_pos)
    cx = int(round(bough_pt[0]))
    cz = int(round(bough_pt[2]))
    y_branch = int(round(bough_pt[1]))
    y_deck = int(round(y_deck))

    def put(dx, dy, dz, name, **props):
        cells[cx + dx, y_deck + dy, cz + dz] = state(name, **props)

    # 1. 清空吊屋与吊索周围散落树叶，使吊索和吊屋通道清晰畅通
    for dx in range(-7, 8):
        for dz in range(-7, 8):
            for y_abs in range(y_deck - 6, y_branch + 2):
                at = (cx + dx, y_abs, cz + dz)
                if at in cells and ('canopy' in cells[at]['Name'] or 'leaf' in cells[at]['Name']):
                    cells[at] = state('minecraft:air')

    bough_next, _ = main_bough_curve(bough_index, min(1.0, t_pos + 0.08))
    bough_prev, _ = main_bough_curve(bough_index, max(0.0, t_pos - 0.08))
    tangent = bough_next - bough_prev
    horiz_len = max(1.0, math.hypot(tangent[0], tangent[2]))
    slope_r = tangent[1] / horiz_len
    rad_dir = np.array([tangent[0] / horiz_len, tangent[2] / horiz_len])

    # 2. 倒金字塔木桁架吊篮底座 (dy = -5 .. -1)
    for depth in range(1, 5):
        span = 6 - depth
        for dx in range(-span, span + 1):
            for dz in range(-span, span + 1):
                if abs(dx) == span or abs(dz) == span or dx == 0 or dz == 0:
                    put(dx, -depth, dz, 'elven_heartwood' if (dx == 0 or dz == 0) else 'minecraft:spruce_planks',
                        **({'axis': 'y'} if (dx == 0 or dz == 0) else {}))
    put(0, -5, 0, 'elven_heartwood', axis='y')
    put(0, -6, 0, 'elven_leaf_lamp', facing='north')
    for sx, sz in [(-3, -3), (-3, 3), (3, -3), (3, 3)]:
        for hang in range(2, 6 + (style % 3)):
            put(sx, -hang, sz, 'elven_lamp_vine', facing='north')

    # 3. 11x11 环形木甲板与外圈护栏 (dy = 0, 1)
    for dx in range(-5, 6):
        for dz in range(-5, 6):
            if abs(dx) == 5 and abs(dz) == 5:
                continue
            for dy in range(1, 8):
                put(dx, dy, dz, 'minecraft:air')
            put(dx, 0, dz, 'minecraft:spruce_planks' if max(abs(dx), abs(dz)) == 5 else 'elven_pale_planks')
            put(dx, -1, dz, 'elven_heartwood' if max(abs(dx), abs(dz)) == 5 else 'minecraft:spruce_planks',
                **({'axis': 'y'} if max(abs(dx), abs(dz)) == 5 else {}))
            if max(abs(dx), abs(dz)) == 5 and not (abs(dx) <= 1 or abs(dz) <= 1):
                put(dx, 1, dz, 'minecraft:birch_fence')
    for sx, sz in [(-4, -4), (-4, 4), (4, -4), (4, 4)]:
        put(sx, 1, sz, 'minecraft:birch_fence')

    for sx, sz in [(-4, -5), (4, -5), (-4, 5), (4, 5)]:
        put(sx, 1, sz, 'elven_flower_planter', facing='north')
        put(sx, 0, sz, 'elven_canopy', distance=1, persistent=True, waterlogged=False, season=0)

    # 4. 7x7 白墙木框小屋主体 (dx, dz in -3..3, dy = 1..4)
    for dy in range(1, 5):
        for dx in range(-3, 4):
            for dz in range(-3, 4):
                corner = abs(dx) == 3 and abs(dz) == 3
                wall = abs(dx) == 3 or abs(dz) == 3
                if corner:
                    put(dx, dy, dz, 'elven_heartwood', axis='y')
                elif wall:
                    if dy <= 3 and ((dx == 0 and abs(dz) == 3) or (dz == 0 and abs(dx) == 3)):
                        put(dx, dy, dz, 'minecraft:air')
                    elif dy in (2, 3) and (abs(dx) == 1 or abs(dz) == 1):
                        put(dx, dy, dz, 'minecraft:glass_pane')
                    else:
                        put(dx, dy, dz, 'journey_lime_plaster')
                else:
                    put(dx, dy, dz, 'minecraft:air')

    # 5. 双坡深木屋顶（带实心木板衬底与严密山墙，dy = 5..8）
    stair_block = 'minecraft:dark_oak_stairs' if style % 2 == 0 else 'minecraft:spruce_stairs'
    plank_backing = 'minecraft:dark_oak_planks' if style % 2 == 0 else 'minecraft:spruce_planks'
    for dx in range(-4, 5):
        rise = 4 - abs(dx)
        ry = 4 + rise
        for dz in range(-4, 5):
            if dx == 0:
                put(dx, ry, dz, plank_backing)
                put(dx, ry - 1, dz, 'elven_heartwood', axis='z')
            else:
                put(dx, ry, dz, stair_block, facing='east' if dx < 0 else 'west',
                    half='bottom', shape='straight', waterlogged=False)
                if abs(dx) <= 3 and ry > 5:
                    put(dx, ry - 1, dz, 'elven_pale_planks')
            if abs(dz) == 3 and abs(dx) < 3:
                for fill_y in range(5, ry):
                    put(dx, fill_y, dz, 'journey_lime_plaster')
        if abs(dx) == 4:
            for dz in (-4, 4):
                put(dx, ry + 1, dz, 'elven_canopy', distance=1, persistent=True, waterlogged=False, season=0)
                put(dx, ry - 1, dz, 'elven_leaf_lamp', facing='north')

    # 室内家具：桌在 dz=1，椅在 dz=2（南侧），因此椅子必须朝北 (facing='north') 正对桌子！
    put(1, 1, 1, 'elven_reading_table', facing='south')
    put(1, 1, 2, 'elven_leaf_seat', facing='north')
    put(-2, 1, -2, 'elven_bookshelf', facing='south', half='lower')
    put(-2, 2, -2, 'elven_bookshelf', facing='south', half='upper')
    put(0, 4, 0, 'elven_leaf_lamp', facing='north')

    # 6. 四角悬吊长索直达斜向主枝
    ropes = []
    for sx, sz in [(-4, -4), (-4, 4), (4, -4), (4, 4)]:
        proj = sx * rad_dir[0] + sz * rad_dir[1]
        y_corner_top = int(round(y_branch + proj * slope_r - 1))
        cells[cx + sx, y_deck + 4, cz + sz] = state('elven_heartwood', axis='y')
        for y_abs in range(y_deck + 5, y_corner_top):
            cells[cx + sx, y_abs, cz + sz] = state('elven_climbing_rope')
        ropes.append([cx + sx, cz + sz, y_deck + 5, y_corner_top - 1])
        steps_in = max(abs(sx), abs(sz))
        for k in range(steps_in + 1):
            bx = cx + int(round(sx * (1 - k / steps_in)))
            bz = cz + int(round(sz * (1 - k / steps_in)))
            cells[bx, y_corner_top, bz] = state('elven_heartwood', axis='x' if abs(sx) >= abs(sz) else 'z')
        cells[cx + sx // 2, y_corner_top - 1, cz + sz // 2] = state('elven_lamp_vine', facing='north')
        cells[cx + sx // 2, y_corner_top - 2, cz + sz // 2] = state('elven_leaf_lamp', facing='north')

    return {'center': [cx, y_deck, cz], 'floor': y_deck, 'anchor_y': y_branch, 'ropes': ropes}


def build_giant_tree_complex(cells, conifer, index, center_xz):
    """在外围近卫巨木上建造正交对齐的环抱式主树屋与双层观景露台（统一主层高程 y_floor=26，与跨树栈桥零高差衔接）。"""
    ox, oz = center_xz
    angle_to_world = math.atan2(-oz, -ox)
    (rx, rz), (tx, tz) = _basis(angle_to_world)
    y_floor = 26
    frame = 'cloudsea_fir_heart_log' if conifer else 'elven_heartwood'
    roof = 'dark_oak' if conifer else 'spruce'

    # 1. 围绕巨木树干的双层厚环形大露台 (半径 4.0..10.2) 与 Rosenfeld 四连通外圈护栏
    r_max = 10.2
    for dx in range(-11, 12):
        for dz in range(-11, 12):
            r = math.hypot(dx, dz)
            if 4.0 <= r <= r_max:
                for dy in range(1, 9):
                    at = (ox + dx, y_floor + dy, oz + dz)
                    if at not in cells or ('log' not in cells[at]['Name'] and 'heartwood' not in cells[at]['Name']):
                        cells[at] = state('minecraft:air')
                is_rim = any(
                    math.hypot(dx + sx, dz + sz) > r_max
                    for sx in (-1, 0, 1) for sz in (-1, 0, 1)
                    if not (sx == 0 and sz == 0)
                )
                cells[ox + dx, y_floor, oz + dz] = state('minecraft:spruce_planks' if is_rim else 'elven_pale_planks')
                cells[ox + dx, y_floor - 1, oz + dz] = state(frame if is_rim else 'minecraft:spruce_planks',
                                                             **({'axis': 'y'} if is_rim else {}))
                if is_rim:
                    cells[ox + dx, y_floor + 1, oz + dz] = state('minecraft:birch_fence')

    for a in np.linspace(0, math.tau, 8, endpoint=False):
        for k in range(1, 6):
            bx = ox + int(round(math.cos(a) * (9 - k)))
            bz = oz + int(round(math.sin(a) * (9 - k)))
            cells[bx, y_floor - 1 - k, bz] = state(frame, axis='y')

    # 2. 在背向世界树一侧使用正交主轴建造白墙木架树屋
    house_angle = angle_to_world + math.pi
    (hrx, hrz), (htx, htz) = _cardinal_basis(house_angle)
    in_facing = _vec_facing(-hrx, -hrz)
    out_facing = _vec_facing(hrx, hrz)
    t_pos_facing = _vec_facing(htx, htz)
    t_neg_facing = _vec_facing(-htx, -htz)
    beam_axis = 'x' if abs(hrx) >= abs(hrz) else 'z'

    def put_h(dr, dt, dy, name, **props):
        wx = ox + dr * hrx + dt * htx
        wz = oz + dr * hrz + dt * htz
        wy = y_floor + dy
        cells[wx, wy, wz] = state(name, **props)

    for dr in range(4, 9):
        for dt in range(-4, 5):
            put_h(dr, dt, 0, 'elven_pale_planks')
            put_h(dr, dt, -1, 'minecraft:spruce_planks')

    for dy in range(1, 5):
        for dr in range(4, 9):
            for dt in range(-4, 5):
                corner = dr in (4, 8) and dt in (-4, 4)
                wall = dr in (4, 8) or dt in (-4, 4)
                if corner:
                    put_h(dr, dt, dy, frame, axis='y')
                elif wall:
                    if dr == 4 and dt in (-1, 0, 1) and dy <= 3:
                        put_h(dr, dt, dy, 'minecraft:air')
                    elif dy in (2, 3) and abs(dt) == 4 and dr in (5, 6, 7):
                        put_h(dr, dt, dy, 'minecraft:glass_pane')
                    else:
                        put_h(dr, dt, dy, 'journey_lime_plaster')
                else:
                    put_h(dr, dt, dy, 'minecraft:air')

    for dt in range(-5, 6):
        rise = (5 - abs(dt)) if conifer else (5 - abs(dt)) // 2 + 1
        ry = 4 + rise
        stair_face = t_pos_facing if dt < 0 else t_neg_facing
        for dr in range(3, 10):
            if dt == 0:
                put_h(dr, dt, ry, f'minecraft:{roof}_planks')
                put_h(dr, dt, ry - 1, frame, axis=beam_axis)
            else:
                put_h(dr, dt, ry, f'minecraft:{roof}_stairs', facing=stair_face,
                      half='bottom', shape='straight', waterlogged=False)
                if abs(dt) <= 4 and ry > 5:
                    put_h(dr, dt, ry - 1, 'elven_pale_planks')
            if dr in (4, 8) and abs(dt) <= 4:
                for fill_y in range(5, ry):
                    put_h(dr, dt, fill_y, 'journey_lime_plaster')
        if abs(dt) == 5:
            put_h(8, dt, ry - 1, 'elven_leaf_lamp', facing=out_facing)

    put_h(6, -2, 1, 'elven_reading_table', facing=t_pos_facing)
    put_h(6, -1, 1, 'elven_leaf_seat', facing=t_neg_facing)
    put_h(7, 2, 1, 'elven_bookshelf', facing=t_neg_facing, half='lower')
    put_h(7, 2, 2, 'elven_bookshelf', facing=t_neg_facing, half='upper')
    put_h(6, 0, 4, 'elven_leaf_lamp', facing=in_facing)

    # 3. 偶数树增设二层树冠观景露台 (y_upper = y_floor + 12)，通过攀爬绳梯连通
    y_upper = None
    if index % 2 == 0:
        y_upper = y_floor + 12
        r_up_max = 8.2
        for dx in range(-9, 10):
            for dz in range(-9, 10):
                r = math.hypot(dx, dz)
                if 3.8 <= r <= r_up_max:
                    for dy in range(1, 7):
                        at = (ox + dx, y_upper + dy, oz + dz)
                        if at not in cells or ('log' not in cells[at]['Name'] and 'heartwood' not in cells[at]['Name']):
                            cells[at] = state('minecraft:air')
                    is_rim = any(
                        math.hypot(dx + sx, dz + sz) > r_up_max
                        for sx in (-1, 0, 1) for sz in (-1, 0, 1)
                        if not (sx == 0 and sz == 0)
                    )
                    cells[ox + dx, y_upper, oz + dz] = state('minecraft:spruce_planks' if is_rim else 'elven_pale_planks')
                    cells[ox + dx, y_upper - 1, oz + dz] = state(frame if is_rim else 'minecraft:spruce_planks',
                                                                 **({'axis': 'y'} if is_rim else {}))
                    if is_rim:
                        cells[ox + dx, y_upper + 1, oz + dz] = state('minecraft:birch_fence')
        up_rx = ox + htx * 6
        up_rz = oz + htz * 6
        cells[up_rx, y_upper + 3, up_rz] = state(frame, axis='y')
        for ry in range(y_floor + 1, y_upper + 3):
            cells[up_rx, ry, up_rz] = state('elven_climbing_rope')
        cells[up_rx - htx, y_upper + 1, up_rz - htz] = state('minecraft:air')

    # 4. 直通地面的可攀爬绳梯（设在侧向 tx, tz 方向 r=9 处，避开正对世界树的跨树桥入口 rx, rz）
    rope_x = ox + int(round(tx * 9))
    rope_z = oz + int(round(tz * 9))
    cells[rope_x, y_floor + 3, rope_z] = state(frame, axis='y')
    for ry in range(2, y_floor + 3):
        cells[rope_x, ry, rope_z] = state('elven_climbing_rope')
    for cdy in range(1, 4):
        cells[ox + int(round(tx * 8)), y_floor + cdy, oz + int(round(tz * 8))] = state('minecraft:air')
        cells[rope_x, y_floor + cdy, rope_z] = state('elven_climbing_rope')

    port_in = (ox + int(round(rx * 9.5)), y_floor, oz + int(round(rz * 9.5)))
    return {
        'kind': 'fir' if conifer else 'broadleaf',
        'floor': y_floor,
        'upper_floor': y_upper,
        'rope': [rope_x, 2, rope_z],
        'rope_top': y_floor + 2,
        'bridge_port': list(port_in),
        'center': [ox, y_floor, oz]
    }


def carve_interior_portals(cells, spiral_a, levels=None):
    """在 world_floors 与 tree_lights 之后打通世界树五层内廊 (Y = 17, 33, 49, 65, 81) 与外圈螺旋栈道的通道，
    从树心内廊 (r = 8.0) 贯通至螺旋栈道同高程点，自动避开内廊旋转楼梯并清除通道及出口处任何阻挡的树叶与护栏。
    """
    stair_Protected = set()
    stair_angles_by_floor = {fy: [] for fy in [17, 33, 49, 65, 81]}
    if levels:
        for sx, sy, sz, _ in levels.get('stairs', []):
            dx, dz = (1, 0) if abs(sx) >= abs(sz) else (0, 1)
            for w in (-1, 0, 1):
                for cy in (sy, sy + 1, sy + 2):
                    stair_Protected.add((sx + dx * w, cy, sz + dz * w))
            for fy in stair_angles_by_floor:
                if fy - 2 <= sy <= fy + 4:
                    stair_angles_by_floor[fy].append(math.atan2(sz, sx))
        for fy in levels.get('floors', []):
            stair_Protected.add((7, fy, 0))

    portals = []
    for fy in [17, 33, 49, 65, 81]:
        best = min(spiral_a, key=lambda p: abs(p[1] - fy))
        sp_x, sp_y, sp_z, sp_theta = best
        # 选择内廊起点角度 theta_in，若旋转楼梯恰好在该高度经过该方向，则沿内廊偏转避开楼梯踏步
        theta_in = sp_theta
        for offset in (0.0, 0.45, -0.45, 0.85, -0.85):
            cand = sp_theta + offset
            if all(abs(math.atan2(math.sin(cand - sa), math.cos(cand - sa))) > 0.38 for sa in stair_angles_by_floor[fy]):
                theta_in = cand
                break
        x0, z0 = math.cos(theta_in) * 7.8, math.sin(theta_in) * 7.8
        x1, z1 = sp_x, sp_z
        dx, dz = x1 - x0, z1 - z0
        dist = math.hypot(dx, dz)
        ux, uz = dx / dist, dz / dist
        px, pz = -uz, ux
        half_w = 1.65

        min_x = int(math.floor(min(x0, x1))) - 5
        max_x = int(math.ceil(max(x0, x1))) + 5
        min_z = int(math.floor(min(z0, z1))) - 5
        max_z = int(math.ceil(max(z0, z1))) + 5

        for wx in range(min_x, max_x + 1):
            for wz in range(min_z, max_z + 1):
                proj = (wx - x0) * ux + (wz - z0) * uz
                perp = (wx - x0) * px + (wz - z0) * pz
                if proj < -1.0 or proj > dist + 3.0:
                    continue
                d_perp = abs(perp)
                if d_perp <= half_w and proj <= dist + 1.2:
                    if (wx, fy, wz) not in stair_Protected:
                        cells[wx, fy, wz] = state('elven_pale_planks')
                    if (wx, fy - 1, wz) not in stair_Protected and math.hypot(wx, wz) >= 11.5:
                        cells[wx, fy - 1, wz] = state('elven_heartwood', axis='y')
                    for dy in range(1, 5):
                        if (wx, fy + dy, wz) not in stair_Protected:
                            cells[wx, fy + dy, wz] = state('minecraft:air')
                elif d_perp <= half_w + 2.0 and dist < proj <= dist + 3.0:
                    for dy in range(1, 5):
                        old_n = cells.get((wx, fy + dy, wz), {}).get('Name', '')
                        if any(k in old_n for k in ('canopy', 'leaf', 'fence', 'vine')):
                            cells[wx, fy + dy, wz] = state('minecraft:air')
                elif half_w < d_perp <= half_w + 1.0 and (4.5 <= proj <= 6.0 or dist - 3.5 <= proj <= dist - 2.0):
                    for dy in range(1, 4):
                        if (wx, fy + dy, wz) not in stair_Protected:
                            cells[wx, fy + dy, wz] = state('elven_heartwood', axis='y')
                    if (wx, fy + 4, wz) not in stair_Protected:
                        cells[wx, fy + 4, wz] = state('elven_leaf_lamp', facing='north')
        portals.append({
            'floor': fy,
            'inner_entry': [int(round(x0)), fy, int(round(z0))],
            'outer_exit': [int(round(x1)), fy, int(round(z1))]
        })
    return portals


def build_world_tree_settlement(cells, homes_info, levels=None, block_entities=None):
    """在世界树外圈、树根、树冠主枝及外围 8 棵巨木之间构建完整的立体精灵聚落。"""
    # 1. 主盘旋外廊 spiral_a：从东南根台 (-0.34*pi, Y=5) 逆时针盘升 2.25 圈至 Y=84（每圈高差 35.1 格，绝不自交）
    spiral_a = []
    for i in range(441):
        t = i / 440.0
        theta = -0.34 * math.pi + t * 2.25 * math.tau
        y = 5.0 + t * 79.0
        cx0, cz0 = trunk_center(y)
        r = trunk_outer_radius(y, theta) + 3.0
        spiral_a.append((cx0 + math.cos(theta) * r, y, cz0 + math.sin(theta) * r, theta))
    for i in range(0, len(spiral_a) - 5, 5):
        build_walkway_segment(cells, spiral_a[i][:3], spiral_a[i + 5][:3], width=4, rail=True, lamp_every=7, strut_to_trunk=True)

    # 西南迎宾分廊：从西南前广场 (-0.66*pi, Y=5) 沿西侧顺时针平稳上升，在 spiral_a[114] (theta=+0.828*pi, Y=25.47) 同高程无缝汇入主螺旋
    merge_node = spiral_a[114]
    sw_branch = []
    for i in range(81):
        t = i / 80.0
        theta = -0.66 * math.pi - t * 0.512 * math.pi
        y = 5.0 + t * (merge_node[1] - 5.0)
        cx0, cz0 = trunk_center(y)
        r = trunk_outer_radius(y, theta) + 3.0
        sw_branch.append((cx0 + math.cos(theta) * r, y, cz0 + math.sin(theta) * r, theta))
    for i in range(0, len(sw_branch) - 5, 5):
        build_walkway_segment(
            cells, sw_branch[i][:3], sw_branch[i + 5][:3],
            width=4, rail=True, lamp_every=7, strut_to_trunk=True, clear_ends=(i + 5 == 80)
        )

    # 东南、西南双侧落地迎宾阶梯
    se_top = spiral_a[0][:3]
    build_walkway_segment(cells, (se_top[0] + 8, 0, se_top[2] - 12), se_top, width=4, rail=True, lamp_every=5, clear_ends=True)
    sw_top = sw_branch[0][:3]
    build_walkway_segment(cells, (sw_top[0] - 8, 0, sw_top[2] - 12), sw_top, width=4, rail=True, lamp_every=5, clear_ends=True)

    # 2. 树根林间集市（先建 6 座摊位，再用 clear_ends=True 将支路栈道平接至摊位后侧敞口，零高差无挡路）
    market_specs = [
        (-0.26 * math.pi, 5, 38),
        (-0.74 * math.pi, 5, 38),
        (-0.10 * math.pi, 5, 39),
        (-0.90 * math.pi, 5, 39),
        (0.18 * math.pi, 6, 39),
        (0.82 * math.pi, 6, 39),
    ]
    all_low_walk = spiral_a[:40] + sw_branch[:30]
    for m_angle, my, mr in market_specs:
        mx = math.cos(m_angle) * mr
        mz = math.sin(m_angle) * mr
        build_market_stall(cells, mx, my, mz, m_angle)
        (mrx, mrz), _ = _cardinal_basis(m_angle)
        stall_back = (mx - mrx * 4, my, mz - mrz * 4)
        near = min(all_low_walk, key=lambda p: math.hypot(p[0] - stall_back[0], p[2] - stall_back[2]))
        build_walkway_segment(cells, stall_back, near[:3], width=3, rail=True, lamp_every=7, clear_ends=True)

    # 3. 建造外围 8 棵近卫巨木树屋（统一主露台高程 y_floor = 26），再架设水平跨树长桥与外环桥并自动打开连接口护栏
    giant_houses = []
    for i, gx, gz in homes_info:
        conifer = (i % 3 == 0)
        spec = build_giant_tree_complex(cells, conifer, i, (gx, gz))
        giant_houses.append(spec)

    for i, gx, gz in homes_info:
        spec = giant_houses[i]
        a_i = math.atan2(gz, gx)
        y_floor = spec['floor']
        giant_port = tuple(spec['bridge_port'])

        if i == 6:
            # 正南巨木分叉双桥：从主螺旋与西南分廊平稳升至桥墩 (Y=24)，再以 2 格微坡平接至巨木露台 (Y=26)
            for side_pt in (spiral_a[35][:3], sw_branch[45][:3]):
                mid = ((side_pt[0] + giant_port[0]) * 0.5, 24, (side_pt[2] + giant_port[2]) * 0.5)
                build_trestle_pier(cells, mid[0], mid[1], mid[2])
                build_walkway_segment(cells, side_pt, mid, width=3, rail=True, lamp_every=7, clear_ends=True)
                build_walkway_segment(cells, mid, giant_port, width=3, rail=True, lamp_every=7, clear_ends=True)
        else:
            best_sp = min(
                spiral_a[:220] + sw_branch,
                key=lambda p: abs(p[1] - y_floor) + 8.0 * abs(math.atan2(math.sin(p[3] - a_i), math.cos(p[3] - a_i)))
            )
            trunk_port = best_sp[:3]
            # 桥墩顶面与巨木露台同高 (y_floor = 26)，使桥墩至巨木露台段 100% 水平零高差接入！
            mid = (
                (trunk_port[0] + giant_port[0]) * 0.5,
                y_floor,
                (trunk_port[2] + giant_port[2]) * 0.5,
            )
            build_trestle_pier(cells, mid[0], mid[1], mid[2])
            build_walkway_segment(cells, trunk_port, mid, width=4, rail=True, lamp_every=7, clear_ends=True)
            build_walkway_segment(cells, mid, giant_port, width=4, rail=True, lamp_every=7, clear_ends=True)

    for idx in range(len(giant_houses)):
        g0 = giant_houses[idx]
        g1 = giant_houses[(idx + 1) % len(giant_houses)]
        c0, c1 = g0['center'], g1['center']
        vec_x, vec_z = c1[0] - c0[0], c1[2] - c0[2]
        d = math.hypot(vec_x, vec_z)
        ux, uz = vec_x / d, vec_z / d
        p0 = (c0[0] + ux * 9.5, g0['floor'], c0[2] + uz * 9.5)
        p1 = (c1[0] - ux * 9.5, g1['floor'], c1[2] - uz * 9.5)
        build_walkway_segment(cells, p0, p1, width=3, rail=True, lamp_every=9, clear_ends=True)

    # 4. 建造 2 座挑台玻璃花房温室与 18 座依附木屋（位于螺旋栈道外侧，正门 3 格宽平桥零高差接入栈道）
    gh_se_pt = spiral_a[52]
    gh_sw_pt = sw_branch[56]
    jars_se = build_greenhouse_terrace(cells, gh_se_pt, block_entities=block_entities, variant=0)
    jars_sw = build_greenhouse_terrace(cells, gh_sw_pt, block_entities=block_entities, variant=1)
    cultivation_jars = jars_se + jars_sw

    trunk_house_nodes = [
        (spiral_a[22], True, 0),    # 1. 东南前广场双层主商屋
        (sw_branch[22], True, 1),   # 2. 西南前广场双层主商屋
        (spiral_a[38], False, 2),   # 3. 东侧低层木屋
        (spiral_a[78], False, 3),   # 4. 东北低层木屋
        (spiral_a[98], True, 4),    # 5. 北侧双层主屋
        (spiral_a[124], False, 5),  # 6. 西北低中层木屋
        (spiral_a[144], False, 6),  # 7. 西侧中层木屋
        (spiral_a[168], False, 7),  # 8. 西南二层木屋
        (spiral_a[188], True, 8),   # 9. 南拱门正上方观景双层核心大屋
        (spiral_a[210], False, 9),  # 10. 东南中层木屋
        (spiral_a[232], False, 10), # 11. 正东中层木屋
        (spiral_a[258], False, 11), # 12. 东北中高层木屋
        (spiral_a[282], True, 12),  # 13. 正北中高层双层屋
        (spiral_a[304], False, 13), # 14. 西北中高层木屋
        (spiral_a[320], False, 14), # 15. 西偏北高层木屋
        (spiral_a[356], False, 15), # 16. 西南高层木屋
        (spiral_a[382], False, 16), # 17. 正南高层木屋
        (spiral_a[408], False, 17), # 18. 东南树冠下木屋
    ]
    trunk_houses = []
    for node_pt, two_story, h_style in trunk_house_nodes:
        trunk_houses.append(build_trunk_cottage(cells, node_pt, two_story=two_story, style=h_style))

    # 5. 建造高空 12 座主枝悬吊木屋（统一在 Y=84 云端高程，通过水平悬索桥互联并与主盘旋栈道顶端平接，彻底消除陡峭悬空断头阶梯）
    suspended_houses = []
    inner_ring = []
    outer_pairs = []
    for i in range(8):
        y_in = 84
        sh_inner = build_suspended_bough_house(cells, i, t_pos=0.46, y_deck=y_in, style=i)
        suspended_houses.append(sh_inner)
        inner_ring.append(sh_inner)
        if i % 2 == 0:
            y_out = 84
            sh_outer = build_suspended_bough_house(cells, i, t_pos=0.78, y_deck=y_out, style=i + 1)
            suspended_houses.append(sh_outer)
            outer_pairs.append((sh_inner, sh_outer))

    # 内外环悬吊屋平缓连接桥（高差仅 2 格，clear_ends=True 自动打开两侧甲板护栏与花坛）
    for sh_in, sh_out in outer_pairs:
        c_in, c_out = sh_in['center'], sh_out['center']
        vx, vz = c_out[0] - c_in[0], c_out[2] - c_in[2]
        dist = math.hypot(vx, vz)
        ux, uz = vx / dist, vz / dist
        build_walkway_segment(
            cells,
            (c_in[0] + ux * 4.5, c_in[1], c_in[2] + uz * 4.5),
            (c_out[0] - ux * 4.5, c_out[1], c_out[2] - uz * 4.5),
            width=3, rail=True, lamp_every=6, clear_ends=True
        )

    # 内环 8 座悬吊屋环形云端栈桥（高差仅 1 格，clear_ends=True 无缝平接甲板）
    for i in range(8):
        s0 = inner_ring[i]['center']
        s1 = inner_ring[(i + 1) % 8]['center']
        vx, vz = s1[0] - s0[0], s1[2] - s0[2]
        d = math.hypot(vx, vz)
        ux, uz = vx / d, vz / d
        build_walkway_segment(
            cells,
            (s0[0] + ux * 4.5, s0[1], s0[2] + uz * 4.5),
            (s1[0] - ux * 4.5, s1[1], s1[2] - uz * 4.5),
            width=3, rail=True, lamp_every=7, clear_ends=True
        )

    # 从主盘旋栈道顶端 (spiral_a[440], Y=84) 与次顶端 (spiral_a[422], Y=81) 架设两座平缓云端引桥直通内环悬吊屋，无任何断头悬空阶梯
    for sp_idx, ring_idx in ((440, 0), (422, 7)):
        sp_top = spiral_a[sp_idx][:3]
        s_target = inner_ring[ring_idx]['center']
        vx, vz = s_target[0] - sp_top[0], s_target[2] - sp_top[2]
        d = math.hypot(vx, vz)
        ux, uz = vx / d, vz / d
        build_walkway_segment(
            cells,
            sp_top,
            (s_target[0] - ux * 4.5, s_target[1], s_target[2] - uz * 4.5),
            width=3, rail=True, lamp_every=6, clear_ends=True
        )

    # 6. 重新校验并锚定全部巨木攀爬绳与高空悬吊屋四角承重长索
    for g_spec in giant_houses:
        rx0, ry0, rz0 = g_spec['rope']
        frame_block = 'cloudsea_fir_heart_log' if g_spec['kind'] == 'fir' else 'elven_heartwood'
        cells[rx0, g_spec['rope_top'] + 1, rz0] = state(frame_block, axis='y')
        for ry in range(ry0, g_spec['rope_top'] + 1):
            cells[rx0, ry, rz0] = state('elven_climbing_rope')

    for sh in suspended_houses:
        for rx0, rz0, r_bot, r_top in sh['ropes']:
            cells[rx0, r_bot - 1, rz0] = state('elven_heartwood', axis='y')
            for y_r in range(r_bot, r_top + 1):
                cells[rx0, y_r, rz0] = state('elven_climbing_rope')
            cells[rx0, r_top + 1, rz0] = state('elven_heartwood', axis='y')

    return {
        'trunk_houses': trunk_houses,
        'giant_houses': giant_houses,
        'suspended_houses': suspended_houses,
        'market_stalls': len(market_specs),
        'greenhouse': True,
        'cultivation_jars': cultivation_jars,
        'spiral_a': spiral_a,
    }
