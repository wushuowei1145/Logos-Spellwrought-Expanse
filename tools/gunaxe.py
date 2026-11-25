# -*- coding: utf-8 -*-
"""铳刃（暂名）：转轮步枪，枪管下装一条长刃。网格、贴图、物品模型、图标与预览都由本脚本生成。

用法：uv run --with numpy --with pillow python tools/gunaxe.py [预览目录]

坐标照近战武器的约定：原点在主手握点（枪托握颈），枪口 +Y，刃口朝 +X（枪的“下方”），
枪的上方（准星、击锤）在 −X，左右两侧是 ±Z。网格 scale 1.25，第三人称正好 1 格 = 16。

外观参考 TaCZ 老枪包的做法：比例修长；倒角、螺丝、准星、箍这类小件都用方块做出来；
贴图以平整的固有色为主，只加很轻的明暗和一圈亮边。贴图不手画：每个面在贴图上各占一块，
按这一面在网格里的位置逐格取色，所以转过角度的方块之间颜色也连得上。
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
sys.path.insert(0, str(ROOT / 'tools'))
from combat_author import euler_matrix, matrix_euler  # noqa: E402
from combat_render import FACES, cube_local  # noqa: E402

NAME = 'gunaxe'
D = 4          # 每单位几个贴图格
CAP = 8       # 每个面每个方向最多几格：大面基本是平的，不需要更细，也省得表面特效按格子画太多片
SCALE = 1.25

# ---------------------------------------------------------------- 尺寸（网格单位，第三人称 1 单位 = 1/16 格）

BORE = -1.45            # 枪膛轴线的 X
B_HALF = 0.42           # 八棱枪管的半宽（对边距离的一半）
BARREL_Y0, MUZZLE = 5.9, 18.5
CX, CYL_R = -0.8, 1.0   # 转轮轴线与半径；最上面的弹巢对着枪膛
CYL_Y0, CYL_Y1 = 1.65, 5.35
CHAMBER_R = BORE - CX   # 弹巢中心到轴线（负号：朝上）
FORE_Y0, FORE_Y1 = 6.2, 10.1
FORE_GRIP = (-0.45, 8.2, 0.0)
BUTT = -10.0
BELLY = ((0.72, -2.2), (2.55, BUTT))   # 托身下沿


def belly_x(y):
    (x0, y0), (x1, y1) = BELLY
    return x0 + (x1 - x0) * (y - y0) / (y1 - y0)


# 刃：脊线贴着枪管底，过了枪口往上收成刀尖；刃口从刃根的缺口一路到刀尖。
SPINE = [(-1.0, 10.45), (-1.0, 18.45), (-1.2, 21.6)]
_EDGE_V3 = [(0.55, 10.45), (0.85, 10.95), (1.35, 11.15), (1.85, 11.6), (2.15, 12.6), (2.25, 14.0), (2.2, 15.6),
            (2.0, 17.2), (1.55, 18.7), (0.8, 20.0), (-0.1, 21.0), (-1.2, 21.6)]
BLADE_DEPTH = 0.68  # v03 的刃太宽，按刃脊往回收到这个比例
EDGE = [(round(-1.0 + (x + 1.0) * BLADE_DEPTH, 3), y) if y < 21.5 else (x, y) for x, y in _EDGE_V3]
EDGE_W = 0.5

# ---------------------------------------------------------------- 配色（固有色、亮、暗）

C = {
    'steel': ((74, 78, 86), (122, 128, 140), (44, 46, 52)),
    'dark': ((52, 55, 62), (92, 98, 110), (30, 32, 36)),
    'brass': ((178, 134, 64), (226, 186, 110), (118, 84, 38)),
    'wood': ((118, 52, 33), (146, 68, 42), (84, 34, 22)),
    'blade': ((126, 132, 144), (214, 220, 230), (84, 90, 102)),
    'bore': ((16, 16, 20), (16, 16, 20), (16, 16, 20)),
}


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def hash2(ix, iy):
    h = (ix * 374761393 + iy * 668265263) & 0xffffffff
    h = (h ^ (h >> 13)) * 1274126177 & 0xffffffff
    return ((h ^ (h >> 16)) & 0xffff) / 65535.0


def vnoise(x, y):
    ix, iy = math.floor(x), math.floor(y)
    fx, fy = x - ix, y - iy
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a, b = hash2(ix, iy), hash2(ix + 1, iy)
    c, d = hash2(ix, iy + 1), hash2(ix + 1, iy + 1)
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy


# ---------------------------------------------------------------- 方块

CUBES = []


def put(name, center, size, mat, rot=(0, 0, 0), frame=None, bone='Weapon', outline=True, tag=None):
    """按中心、尺寸、欧拉角放一个方块；frame=(绕 Z 的角度, 枢轴) 时先在那个斜着的坐标系里摆，再整体转过去。
    最终方块绕自己的中心转，所以多段旋转可以合成一组欧拉角（原版物品模型和动作网格都支持三轴）。"""
    c = np.asarray(center, float)
    R = euler_matrix(rot)
    if frame is not None:
        a, P = frame
        Rf = euler_matrix((0, 0, a))
        P = np.array([P[0], P[1], 0.0])
        c = Rf @ (c - P) + P
        R = Rf @ R
    e = [0.0, 0.0, 0.0] if np.allclose(R, np.eye(3)) else [float(v) for v in matrix_euler(R)]
    h = np.asarray(size, float) / 2
    CUBES.append(dict(name=name, part='weapon', bone=bone, start=[round(v, 4) for v in c - h],
                      end=[round(v, 4) for v in c + h], pivot=[round(v, 4) for v in c],
                      rotation=[round(v, 3) + 0.0 for v in e], mat=mat, outline=outline, tag=tag))


def box(name, x0, x1, y0, y1, hz, mat, z=0.0, **kw):
    put(name, ((x0 + x1) / 2, (y0 + y1) / 2, z), (x1 - x0, y1 - y0, 2 * hz), mat, **kw)


def line_frame(p0, p1):
    """沿线段 p0→p1 的坐标系：局部 +Y 顺着线段，局部 +X 在前进方向右手边。返回 (角度, 中点, 长度)。"""
    p0, p1 = np.asarray(p0, float), np.asarray(p1, float)
    t = p1 - p0
    return math.degrees(math.atan2(-t[0], t[1])), (p0 + p1) / 2, float(np.linalg.norm(t))


def slab(name, p0, p1, width, hz, mat, side=-1, overlap=0.0, z=0.0, **kw):
    """一面贴在线段 p0→p1 上、向一侧铺 width 的方块。side=-1 铺向前进方向的左手边（局部 −X）。"""
    a, m, L = line_frame(p0, p1)
    cx = m[0] + side * width / 2
    put(name, (cx, m[1], z), (width, L + overlap, 2 * hz), mat, frame=(a, m), **kw)


def bar(name, p0, p1, depth, hz, mat, c=0.12, side=-1, chamfer=(True, True), **kw):
    """带倒角的长条：一面贴在线段上，向 side 一侧厚 depth，左右各 hz。chamfer=(贴线一侧, 另一侧) 两条棱是否倒角。"""
    a, m, L = line_frame(p0, p1)
    x_line, x_far = m[0], m[0] + side * depth
    lo, hi = min(x_line, x_far), max(x_line, x_far)
    cut_lo = chamfer[0] if side > 0 else chamfer[1]
    cut_hi = chamfer[1] if side > 0 else chamfer[0]
    lo2, hi2 = lo + (c if cut_lo else 0), hi - (c if cut_hi else 0)
    put(name, ((lo2 + hi2) / 2, m[1], 0), (hi2 - lo2, L, 2 * hz), mat, frame=(a, m), **kw)
    put(name + '_core', ((lo + hi) / 2, m[1], 0), (hi - lo, L - 0.002, 2 * (hz - c)), mat, frame=(a, m), **kw)
    s = c * math.sqrt(2)
    for zs in (-1, 1):
        for xs, cut, end in ((lo + c, cut_lo, "lo"), (hi - c, cut_hi, "hi")):
            if cut:
                put(f'{name}_bevel{"n" if zs < 0 else "s"}_{end}', (xs, m[1], zs * (hz - c)),
                    (s, L - 0.004, s), mat, rot=(0, 45, 0), frame=(a, m), tag='bevel', **kw)


def screw(name, x, y, side_z, r=0.13, mat='steel'):
    for zs in (-1, 1):
        put(f'{name}_{"n" if zs < 0 else "s"}', (x, y, zs * side_z), (2 * r / math.sqrt(2) * 1.0, 2 * r / math.sqrt(2), 0.06),
            mat, rot=(0, 0, 45), tag='screw')


def build():
    CUBES.clear()
    # ---- 枪托：握颈、托身（上沿贴腮、下沿斜下去）、铜托底板。
    bar('wrist', (-0.72, -2.6), (-0.72, 0.62), 1.44, 0.48, 'wood', c=0.14, side=1, outline=False)
    bar('comb_nose', (-0.72, -2.35), (-1.22, -3.6), 1.0, 0.6, 'wood', c=0.16, side=-1, chamfer=(True, False),
        outline=False)
    bar('stock_comb', (-1.22, -3.6), (-1.3, BUTT), 1.4, 0.62, 'wood', c=0.16, side=-1, chamfer=(True, False),
        outline=False)
    bar('stock_belly', BELLY[0], BELLY[1], 1.7, 0.62, 'wood', c=0.16, side=1, chamfer=(True, False),
        outline=False)
    box('stock_fill', -0.3, 0.8, BUTT + 0.05, -3.0, 0.6, 'wood', outline=False)
    box('stock_neck', -0.75, 0.72, -3.4, -2.3, 0.54, 'wood', outline=False)
    box('butt_plate', -1.38, 2.65, BUTT - 0.28, BUTT, 0.64, 'brass')
    slab('butt_heel', (-1.38, BUTT - 0.28), (-1.38, BUTT + 0.55), 0.16, 0.6, 'brass', side=1)
    slab('butt_toe', (2.65, BUTT + 0.75), (2.65, BUTT - 0.28), 0.16, 0.6, 'brass', side=1)
    put('butt_screw', (0.65, BUTT - 0.3, 0), (0.18, 0.04, 0.18), 'steel', rot=(0, 45, 0), tag='screw')
    sy = -6.4
    bx = belly_x(sy)
    put('sling_stud', (bx + 0.1, sy, 0), (0.3, 0.3, 0.22), 'steel')
    rx, r = bx + 0.5, 0.24  # 背带环：四根细条围成一圈，挂在托身下面
    for i, (dx, dy, w, h) in enumerate(((-r, 0, 0.07, 2 * r), (r, 0, 0.07, 2 * r), (0, -r, 2 * r, 0.07),
                                        (0, r, 2 * r, 0.07))):
        put(f'sling_ring_{i}', (rx + dx, sy + dy, 0), (w + 0.07, h + 0.07, 0.07), 'steel', tag='ring')
    slab('upper_tang', (-0.72, 0.62), (-0.76, -1.7), 0.12, 0.22, 'steel', side=1)

    # ---- 机匣：后挡板、顶带、底框、前框；转轮 16 棱，前面有六个弹巢。
    box('frame_rear', -2.05, 0.6, 0.55, CYL_Y0 + 0.02, 0.6, 'steel')
    put('frame_round', (-1.95, 0.72, 0), (0.6, 0.6, 1.18), 'steel', rot=(0, 0, 45))
    box('top_strap', -2.2, -1.8, CYL_Y0 - 0.05, CYL_Y1 + 0.05, 0.3, 'steel')
    box('frame_bottom', 0.2, 0.6, CYL_Y0 - 0.05, CYL_Y1 + 0.05, 0.52, 'steel')
    box('frame_front', -2.0, 0.6, CYL_Y1 - 0.02, BARREL_Y0 + 0.35, 0.58, 'steel')
    slab('frame_chin', (0.6, CYL_Y1 + 0.1), (0.16, FORE_Y0 + 0.9), 0.55, 0.5, 'steel', side=-1, overlap=0.05)
    for i, (x, y) in enumerate(((-1.2, 1.1), (0.25, 1.1), (-1.5, 5.85), (0.25, 5.85))):
        screw(f'frame_screw_{i}', x, y, 0.62)
    revolver_cylinder()
    put('cylinder_pin', (CX, CYL_Y1 + 0.2, 0), (0.3, 0.5, 0.3), 'dark', bone='Crane')
    # 击锤：锤身和往后上翘的锤柄。
    box('hammer', -2.62, -1.98, 0.62, 1.28, 0.16, 'dark', bone='Hammer')
    slab('hammer_spur', (-2.62, 0.62), (-3.05, 0.05), 0.3, 0.15, 'dark', side=1, overlap=0.1, bone='Hammer')
    put('hammer_grip', (-2.95, 0.12, 0), (0.22, 0.1, 0.34), 'dark', rot=(0, 0, 37), bone='Hammer')
    # 扳机与铜护圈。
    slab('trigger', (0.6, 1.55), (1.15, 1.3), 0.18, 0.07, 'dark', side=1, bone='Trigger')
    slab('trigger_tip', (1.15, 1.3), (1.38, 0.95), 0.16, 0.07, 'dark', side=1, overlap=0.05, bone='Trigger')
    slab('guard_front', (0.6, 2.45), (1.55, 2.25), 0.2, 0.11, 'brass', side=-1, overlap=0.1)
    slab('guard_bottom', (1.55, 2.25), (1.62, 0.35), 0.2, 0.11, 'brass', side=-1, overlap=0.15)
    slab('guard_back', (1.62, 0.35), (1.15, -0.35), 0.2, 0.11, 'brass', side=-1, overlap=0.15)
    slab('guard_tail', (1.15, -0.35), (0.78, -1.2), 0.16, 0.12, 'brass', side=-1, overlap=0.1)

    # ---- 枪管、准星、照门、护木。
    hexagon('barrel', BARREL_Y0, MUZZLE - 0.3, B_HALF, 'barrel', 'Weapon', cx=BORE)
    hexagon('muzzle_crown', MUZZLE - 0.32, MUZZLE - 0.06, B_HALF + 0.07, 'barrel', 'Weapon', cx=BORE)
    hexagon('muzzle_lip', MUZZLE - 0.06, MUZZLE + 0.02, B_HALF + 0.01, 'barrel', 'Weapon', cx=BORE)
    box('front_sight_base', -1.96, -1.84, 17.3, 18.0, 0.18, 'steel')
    box('front_sight', -2.25, -1.9, 17.5, 17.85, 0.05, 'brass')
    for zs in (-1, 1):
        box(f'rear_sight_{"n" if zs < 0 else "s"}', -2.15, -1.86, 7.0, 7.3, 0.08, 'steel', z=zs * 0.16)
    box('rear_sight_base', -1.92, -1.84, 6.85, 7.45, 0.26, 'steel')
    bar('fore_end', (-1.1, FORE_Y0), (-1.1, FORE_Y1), 1.25, 0.5, 'wood', c=0.14, side=1, chamfer=(False, True),
        outline=False)
    slab('fore_nose', (0.15, FORE_Y1 - 0.5), (-0.35, FORE_Y1), 0.5, 0.5, 'wood', side=-1, outline=False)
    box('fore_cap', -1.95, 0.25, FORE_Y1, FORE_Y1 + 0.35, 0.54, 'steel')
    screw('fore_screw', -0.45, 8.9, 0.5, r=0.11)

    # ---- 刃：刃口一圈薄而亮，刃身稍厚，刃脊最厚；刃根一道缺口，前面两道箍把刃脊夹在枪管上。
    edge_strip()
    for i, y in enumerate((11.0, 17.0)):
        box(f'blade_band_{i}', -1.97, -0.62, y, y + 0.32, 0.27, 'steel')
        screw(f'blade_band_screw_{i}', -1.1, y + 0.16, 0.27, r=0.09)
    return CUBES


# ---------------------------------------------------------------- 转轮

HEX_SIDE = 2 * CYL_R / math.sqrt(3)
WIN_Y0, WIN_Y1 = CYL_Y0 + 0.5, CYL_Y1 - 0.5   # 窗口（看得见子弹的那一段）


def chamber_angle(k):
    """第 k 个弹巢（0 在最上面、对着枪膛）的方向，XZ 平面里从 +X 量起，度。"""
    return 180.0 + 60.0 * k


def hexagon(name, y0, y1, a, mat, bone, cx=None, **kw):
    """沿 Y、平面朝上的六棱柱：三个转开 60° 的长方体拼成，a 是轴心到平面的距离，轴心在 (cx, 0)。"""
    side = 2 * a / math.sqrt(3)
    for i in range(3):
        put(f'{name}_{i}', (CX if cx is None else cx, (y0 + y1) / 2, 0), (2 * a, y1 - y0 - 0.002 * i, side), mat,
            rot=(0, 60 * i, 0), bone=bone, **kw)


def on_face(k, radial, tangent):
    """弹巢 k 那一面上的点：沿面的法线 radial、沿面的切向 tangent（XZ 平面）。"""
    a = math.radians(chamber_angle(k))
    return (CX + radial * math.cos(a) - tangent * math.sin(a), radial * math.sin(a) + tangent * math.cos(a))


def revolver_cylinder():
    """六棱转轮：前后两块带倒角的端板，中间六面各开一扇窗，露出里面的子弹；子弹每发一根骨骼（Round0 在最上面）。"""
    a, cap = CYL_R, 0.1
    hexagon('cylinder_rear_cap', CYL_Y0 + 0.02, CYL_Y0 + 0.02 + cap, a - cap, 'cyl', 'Cylinder')
    hexagon('cylinder_rear', CYL_Y0 + 0.02 + cap, WIN_Y0 + 0.01, a, 'cyl', 'Cylinder')
    hexagon('cylinder_front', WIN_Y1 - 0.01, CYL_Y1 - 0.02 - cap, a, 'cyl', 'Cylinder')
    hexagon('cylinder_front_cap', CYL_Y1 - 0.02 - cap, CYL_Y1 - 0.02, a - cap, 'cyl', 'Cylinder')
    hexagon('cylinder_hub', WIN_Y0, WIN_Y1, 0.3, 'hub', 'Cylinder', outline=False)
    # 窗框：每面靠两条棱各一根窄条，相邻两面的条在棱上接住。
    t, w = 0.18, 0.22
    for k in range(6):
        for sgn in (-1, 1):
            x, z = on_face(k, a - t / 2, sgn * (HEX_SIDE / 2 - w / 2))
            put(f'cylinder_bar_{k}_{"l" if sgn < 0 else "r"}', (x, (WIN_Y0 + WIN_Y1) / 2, z), (t, WIN_Y1 - WIN_Y0 + 0.02, w),
                'cyl', rot=(0, -chamber_angle(k), 0), bone='Cylinder')
    # 弹巢之间的隔板：从中轴到棱，空的弹巢看进去是一个暗槽，不会透到对面。
    for k in range(6):
        b = math.radians(chamber_angle(k) + 30)
        r0, r1 = 0.28, a + 0.05
        rm = (r0 + r1) / 2
        put(f'cylinder_wall_{k}', (CX + rm * math.cos(b), (WIN_Y0 + WIN_Y1) / 2, rm * math.sin(b)),
            (r1 - r0, WIN_Y1 - WIN_Y0 + 0.02, 0.08), 'pocket', rot=(0, -(chamber_angle(k) + 30), 0), bone='Cylinder',
            outline=False)
    # 子弹：铜壳在后，弹头在前，方截面，平面对着窗口。
    r, half = abs(CHAMBER_R), 0.21
    case_y0, case_y1 = CYL_Y0 + 0.15, CYL_Y1 - 1.0
    for k in range(6):
        x, z = on_face(k, r, 0.0)
        bone = f'Round{k}'
        put(f'round_{k}_case', (x, (case_y0 + case_y1) / 2, z), (2 * half, case_y1 - case_y0, 2 * half), 'case',
            rot=(0, -chamber_angle(k), 0), bone=bone, outline=False)
        put(f'round_{k}_bullet', (x, (case_y1 + CYL_Y1 - 0.25) / 2, z), (2 * half - 0.08, CYL_Y1 - 0.25 - case_y1, 2 * half - 0.08),
            'bullet', rot=(0, -chamber_angle(k), 0), bone=bone, outline=False)


# ---------------------------------------------------------------- 刃的几何

def polygon():
    return SPINE + EDGE[::-1]


def in_polygon(p):
    x, y = p
    inside = False
    pts = polygon()
    for i in range(len(pts)):
        (x0, y0), (x1, y1) = pts[i], pts[i - 1]
        if (y0 > y) != (y1 > y) and x < x0 + (y - y0) * (x1 - x0) / (y1 - y0):
            inside = not inside
    return inside


def seg_dist(p, a, b):
    p, a, b = np.asarray(p, float), np.asarray(a, float), np.asarray(b, float)
    t = np.clip(np.dot(p - a, b - a) / max(np.dot(b - a, b - a), 1e-9), 0, 1)
    return float(np.linalg.norm(p - (a + t * (b - a))))


def edge_dist(x, y):
    return min(seg_dist((x, y), EDGE[i], EDGE[i + 1]) for i in range(len(EDGE) - 1))


def spine_dist(x, y):
    return min(seg_dist((x, y), SPINE[i], SPINE[i + 1]) for i in range(len(SPINE) - 1))


def rect_inside(cx, cy, w, h, a, test):
    """绕 (cx, cy) 转 a 度、宽 w（局部 X）高 h 的矩形，取样点是否都满足 test。"""
    ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
    for i in range(7):
        for j in range(7):
            lx, ly = (i / 6 - 0.5) * w, (j / 6 - 0.5) * h
            if not test(cx + lx * ca - ly * sa, cy + lx * sa + ly * ca):
                return False
    return True


def edge_strip():
    body = lambda x, y: in_polygon((x, y)) and edge_dist(x, y) >= EDGE_W * 0.85
    # 刃口：沿刃口每段一条，铺向刃身。
    for k in range(len(EDGE) - 1):
        slab(f'blade_edge_{k}', EDGE[k], EDGE[k + 1], EDGE_W + 0.12, 0.08 + 0.003 * (k % 2), 'blade', side=-1,
             overlap=0.12, outline=False)
    # 刃身：从刃口往里退 EDGE_W 的线开始，往刃脊铺到出界为止。
    for k in range(len(EDGE) - 1):
        a, m, L = line_frame(EDGE[k], EDGE[k + 1])
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        inward = np.array([-ca, -sa])
        w = 0.1
        while True:
            wn = w + 0.05
            ctr = np.array(m) + inward * (EDGE_W + wn / 2)
            if not rect_inside(ctr[0], ctr[1], wn, L * 0.98, a, body):
                break
            w = wn
        if w > 0.15:
            ctr = np.array(m) + inward * (EDGE_W + w / 2)
            put(f'blade_body_{k}', (ctr[0], ctr[1], 0), (w, L, 0.3 + 0.004 * (k % 2)), 'blade', rot=(0, 0, a),
                outline=False)
    # 剩下的空当用横着的长条补（不转角度），只取完全在刃身里的部分。
    y = SPINE[0][1] + 0.1
    k = 0
    while y < EDGE[-1][1] - 0.4:
        h = 0.8 if y < 18.5 else 0.45
        xs = np.arange(-1.2, 2.4, 0.05)
        ok = [all(body(x, yy) for yy in np.linspace(y, y + h, 5)) for x in xs]
        best, run = None, None
        for x, good in zip(xs, ok):
            if good:
                run = (run[0], x) if run else (x, x)
                if best is None or run[1] - run[0] > best[1] - best[0]:
                    best = run
            else:
                run = None
        if best and best[1] - best[0] > 0.2:
            box(f'blade_fill_{k}', best[0], best[1], y, y + h, 0.148, 'blade', outline=False)
            k += 1
        y += h - 0.02
    # 刃脊：最厚的一条，贴着枪管底。
    slab('blade_spine', SPINE[0], SPINE[1], 0.42, 0.19, 'spine', side=1)
    slab('blade_clip', SPINE[1], SPINE[2], 0.36, 0.16, 'blade', side=1, overlap=0.1, outline=False)


# ---------------------------------------------------------------- 取色

def paint(c, P, n, s, t, border):
    x, y, z = P
    mat = c['mat']
    base, hi, lo = C.get(mat, C['steel'])
    up, down, side = n[0] < -0.6, n[0] > 0.6, abs(n[2]) > 0.6
    if mat == 'cyl':
        base, hi, lo = C['steel']
        rx, rz = x - CX, z
        rr = math.hypot(rx, rz) or 1.0
        if (n[0] * rx + n[2] * rz) / rr < -0.3:      # 朝里的面：窗里看到的内壁
            return mix(lo, C['bore'][0], 0.5)
        if n[1] > 0.6 or n[1] < -0.6:                 # 前后端面：弹巢口
            for i in range(6):
                cx, cz = on_face(i, abs(CHAMBER_R), 0.0)
                if math.hypot(x - cx, z - cz) < 0.24:
                    return C['bore'][0]
            col = mix(base, hi, 0.15)
        else:
            col = mix(base, hi, 0.3) if n[0] < -0.4 else mix(base, lo, 0.25) if n[0] > 0.4 else base
        return mix(col, hi, 0.4) if border else col
    if mat in ('hub', 'pocket'):
        return mix(C['bore'][0], C['steel'][2], 0.35 if mat == 'hub' else 0.55)
    if mat == 'case':
        col = (196, 150, 72) if n[0] > -0.4 else (232, 192, 112)
        return mix(col, (150, 104, 46), 0.25 * vnoise(y * 4, x * 4 + z * 4))
    if mat == 'bullet':
        return (170, 104, 70) if n[0] > -0.4 else (204, 136, 96)
    if mat in ('steel', 'dark', 'barrel'):
        base, hi, lo = C['dark'] if mat == 'dark' else C['steel']
        col = mix(base, hi, 0.45) if up else mix(base, lo, 0.35) if down else base
        if side:  # 侧面上亮下暗，很轻
            col = mix(col, lo, max(0.0, min(1.0, (x + 1.5) / 4)) * 0.25)
        if mat == 'barrel' and n[1] > 0.6:
            col = C['bore'][0] if math.hypot(x - BORE, z) < 0.2 else col
        if border:
            col = mix(col, hi, 0.35)
        return col
    if mat == 'brass':
        col = mix(base, hi, 0.4) if up else mix(base, lo, 0.3) if down else base
        col = mix(col, hi, 0.1 * vnoise(x * 5, y * 5))
        return mix(col, lo, 0.35) if border else col
    if mat == 'wood':
        if c['tag'] == 'bevel':
            col = mix(base, hi, 0.35)
        else:
            col = mix(base, hi, 0.3) if up else mix(base, lo, 0.35) if down else base
            if side:
                col = mix(col, lo, max(0.0, min(1.0, (x + 1.0) / 3.5)) * 0.3)
        # 很淡的木纹，顺着长度方向。
        g = vnoise(x * 2.2 + z * 3, y * 0.25)
        return mix(col, lo if g < 0.5 else hi, abs(g - 0.5) * 0.25)
    if mat == 'blade':
        d = edge_dist(x, y)
        if not side:
            return mix(base, hi, 0.7) if d < EDGE_W + 0.05 else base
        if d < EDGE_W:
            col = mix(hi, mix(base, hi, 0.3), (d / EDGE_W) ** 0.8)
        else:
            col = mix(base, lo, min(1.0, (d - EDGE_W) / 2.5) * 0.35)  # 往刃脊略暗
            col = mix(col, hi, 0.06 * vnoise(x * 1.5, y * 7))       # 顺着刃长的拉丝
        return col
    if mat == 'spine':
        base, hi, lo = C['steel']
        col = mix(base, hi, 0.45) if up else base
        return mix(col, hi, 0.3) if border else col
    raise ValueError(mat)


def face_dims(c, name):
    size = np.asarray(c['end']) - np.asarray(c['start'])
    if name in ('north', 'south'):
        return size[0], size[1]
    if name in ('east', 'west'):
        return size[2], size[1]
    return size[0], size[2]


def bake(cubes):
    """给每个面分一块贴图，按网格位置逐格取色；返回贴图，并把 uv 写进方块（动作网格的约定）。"""
    blocks = []
    for c in cubes:
        for name in FACES:
            fw, fh = face_dims(c, name)
            cap = 16 if c['mat'] == 'cyl' and name in ('up', 'down') else CAP
            bw = max(1, min(cap, math.ceil(fw * (2 * D if cap > CAP else D) - 0.1)))
            bh = max(1, min(cap, math.ceil(fh * (2 * D if cap > CAP else D) - 0.1)))
            blocks.append((c, name, bw, bh))
    order = sorted(range(len(blocks)), key=lambda i: (-blocks[i][3], -blocks[i][2]))
    for W in (64, 128, 256, 512):
        x = y = shelf = 0
        place = {}
        for i in order:
            bw, bh = blocks[i][2], blocks[i][3]
            if x + bw > W:
                x, y, shelf = 0, y + shelf, 0
            place[i] = (x, y)
            x += bw
            shelf = max(shelf, bh)
        if y + shelf <= W:
            break
    img = np.zeros((W, W, 4), np.uint8)
    for i, (c, name, bw, bh) in enumerate(blocks):
        u0, v0 = place[i]
        T = cube_local(c['pivot'], c['rotation'])
        lo, hi = np.asarray(c['start'], float), np.asarray(c['end'], float)
        corners = [lo + (hi - lo) * np.array(k) for k in FACES[name]]
        O, U, V = corners[2], corners[3] - corners[2], corners[1] - corners[2]
        nrm = T[:3, :3] @ np.cross(U, V)
        nrm = nrm / (np.linalg.norm(nrm) or 1)
        # 亮边只画在格子够细的方向上：长面上一格可能有一两个单位长，画上去就是一大块亮斑。
        su, sv = np.linalg.norm(U) / bw, np.linalg.norm(V) / bh
        for j in range(bh):
            for k in range(bw):
                s, t = (k + 0.5) / bw, (j + 0.5) / bh
                P = (T @ np.append(O + s * U + t * V, 1))[:3]
                border = c['outline'] and ((k in (0, bw - 1) and bw > 2 and su <= 0.35) or
                                           (j in (0, bh - 1) and bh > 2 and sv <= 0.35))
                col = paint(c, P, nrm, s, t, border)
                img[v0 + j, u0 + k] = (*[int(round(v)) for v in col], 255)
        c.setdefault('uv', {})[name] = [round(u0 + .02, 4), round(v0 + .02, 4), round(u0 + bw - .02, 4),
                                        round(v0 + bh - .02, 4)]
    return Image.fromarray(img, 'RGBA'), W


# ---------------------------------------------------------------- 网格

def bones_spec():
    return [
        dict(name='Crane', parent='Weapon', pivot=[0.4, CYL_Y1, 0.45]),   # 转轮摆出去的铰点（摆向哪侧待定）
        dict(name='Cylinder', parent='Crane', pivot=[CX, (CYL_Y0 + CYL_Y1) / 2, 0]),
        *[dict(name=f'Round{k}', parent='Cylinder',
               pivot=[round(v, 4) for v in (on_face(k, abs(CHAMBER_R), 0.0)[0], (CYL_Y0 + CYL_Y1) / 2,
                                            on_face(k, abs(CHAMBER_R), 0.0)[1])]) for k in range(6)],
        dict(name='Hammer', parent='Weapon', pivot=[-2.0, 0.85, 0]),
        dict(name='Trigger', parent='Weapon', pivot=[0.6, 1.5, 0]),
        dict(name='MuzzleLocator', parent='Weapon', pivot=[BORE, MUZZLE, 0]),
        dict(name='ForeGripLocator', parent='Weapon', pivot=list(FORE_GRIP)),
    ]


def mesh():
    cubes = build()
    tex, size = bake(cubes)
    out = [{k: c[k] for k in ('name', 'part', 'bone', 'start', 'end', 'pivot', 'rotation', 'uv')} for c in cubes]
    return {
        'texture': f'logos_dimension:textures/item/{NAME}_rig.png',
        'texture_size': [size, size],
        'scale': SCALE,
        'bones': bones_spec(),
        'cubes': out,
        'grip': [0, 0, 0],
        'tip': list(EDGE[-1]) + [0],
    }, tex


OFFSET = (8, 1, 8)


def item_model(m):
    """原版物品模型：坐标 = 网格 + (8, 1, 8)。原版每个面的 U 方向和动作网格相反，所以 u0、u1 对调。"""
    k = 16 / m['texture_size'][0]
    elements = []
    for c in m['cubes']:
        if c['bone'].startswith('Spare'):
            continue
        e = {'name': c['name'],
             'from': [round(c['start'][i] + OFFSET[i], 4) for i in range(3)],
             'to': [round(c['end'][i] + OFFSET[i], 4) for i in range(3)],
             'faces': {f: {'uv': [round(uv[2] * k, 4), round(uv[1] * k, 4), round(uv[0] * k, 4), round(uv[3] * k, 4)],
                           'texture': '#0'} for f, uv in c['uv'].items()}}
        if any(c['rotation']):
            e['rotation'] = {'origin': [round(c['pivot'][i] + OFFSET[i], 4) for i in range(3)],
                             'x': c['rotation'][0], 'y': c['rotation'][1], 'z': c['rotation'][2]}
        elements.append(e)
    spear = json.loads((ASSETS / 'models/item/pack_oath_spear.json').read_text(encoding='utf-8'))
    return {
        'textures': {'0': f'logos_dimension:item/{NAME}_rig', 'particle': f'logos_dimension:item/{NAME}'},
        'gui_light': 'front',
        'display': spear['display'],  # 原点同在主握点；摆放之后在游戏里再调
        'elements': elements,
        'groups': [{'name': 'wand_tip', 'origin': [BORE + 8, MUZZLE + 1, 8], 'children': []}],
    }


# ---------------------------------------------------------------- 图标与预览

FLAT = np.array([[0, 1, 0], [-1, 0, 0], [0, 0, 1]], float)  # 枪口朝画面右、枪的上方朝上（从 +Z 看）


def icon(m, tex, size=32):
    from combat_author import affine
    from combat_render import Camera, render, weapon_quads
    a = math.radians(35)
    R = np.array([[math.cos(a), -math.sin(a), 0], [math.sin(a), math.cos(a), 0], [0, 0, 1]]) @ FLAT
    quads = weapon_quads(m, tex, affine(R, np.zeros(3)), 'weapon')
    cam = Camera(180, 0, (size - 2) / 30.5, R @ np.array([0.4, 5.8, 0]), (size, size))
    im = np.asarray(render(quads, cam, bg=(255, 0, 255), ground=False)).copy()
    solid = ~((im[..., 0] == 255) & (im[..., 1] == 0) & (im[..., 2] == 255))
    out = np.zeros((size, size, 4), np.uint8)
    out[solid, :3] = im[solid]
    out[solid, 3] = 255
    outline = np.zeros_like(solid)
    for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        outline |= np.roll(np.roll(solid, dy, 0), dx, 1)
    outline &= ~solid
    out[outline] = (24, 22, 30, 255)
    return Image.fromarray(out, 'RGBA')


def preview(m, tex_img, out_dir):
    from combat_author import affine
    from combat_render import Camera, render, sheet, weapon_quads, mannequin_quads
    tex = np.asarray(tex_img).astype(np.float32)
    q = weapon_quads(m, tex, affine(FLAT, np.zeros(3)), 'weapon')
    size = (900, 300)
    ctr = FLAT @ np.array([0.4, 5.8, 0])
    views = [('右侧', Camera(180, 0, 26, ctr, size)), ('左侧', Camera(0, 0, 26, ctr, size)),
             ('四分之三（右前上）', Camera(145, 22, 24, ctr, size)), ('四分之三（左后上）', Camera(-30, 25, 24, ctr, size)),
             ('俯视', Camera(180, 89, 26, ctr, size))]
    sheet([(cap, [(cap, render(q, cam, ground=False))]) for cap, cam in views], '铳刃（暂名） v05', out_dir / 'overview.png')
    close = [('机匣与转轮', Camera(150, 14, 70, FLAT @ np.array([-0.6, 3.2, 0]), (420, 320))),
             ('转轮（左下）', Camera(-20, -20, 70, FLAT @ np.array([-0.6, 3.4, 0]), (420, 320))),
             ('刃根与护木', Camera(150, 12, 50, FLAT @ np.array([0.4, 10.0, 0]), (420, 320))),
             ('枪口与刀尖', Camera(130, 18, 50, FLAT @ np.array([0.0, 19.2, 0]), (420, 320))),
             ('枪托', Camera(200, 12, 38, FLAT @ np.array([0.6, -5.8, 0]), (420, 320))),
             ('刃（左侧）', Camera(20, 8, 44, FLAT @ np.array([0.6, 15.5, 0]), (420, 320)))]
    sheet([('细部', [(cap, render(q, cam, ground=False)) for cap, cam in close[:3]]),
           ('', [(cap, render(q, cam, ground=False)) for cap, cam in close[3:]])], '细部', out_dir / 'detail.png')

    # 转轮单独看：满弹、剩三发、端面；转轮骨骼和子弹骨骼都在。
    cyl_bones = {'Cylinder', 'Crane'} | {f'Round{k}' for k in range(6)}
    def cyl(hidden=()):
        cubes = [c for c in m['cubes'] if c['bone'] in cyl_bones and c['bone'] not in hidden]
        return weapon_quads(dict(m, cubes=cubes), tex, affine(FLAT, np.zeros(3)), 'weapon')
    cc = FLAT @ np.array([CX, (CYL_Y0 + CYL_Y1) / 2, 0])
    panels = [('满弹（右侧）', render(cyl(), Camera(180, 0, 70, cc, (320, 300)), ground=False)),
              ('满弹（斜上）', render(cyl(), Camera(150, 30, 70, cc, (320, 300)), ground=False)),
              ('剩三发', render(cyl(('Round0', 'Round2', 'Round3')), Camera(150, 30, 70, cc, (320, 300)), ground=False)),
              ('前端面', render(cyl(), Camera(90, 0, 70, cc, (320, 300)), ground=False)),
              ('装在枪上', render(q, Camera(160, 18, 60, cc, (320, 300)), ground=False))]
    sheet([('转轮', panels)], '六棱转轮', out_dir / 'cylinder.png')

    sk = json.loads((ASSETS / 'combat/skeletons/player_tp.json').read_text(encoding='utf-8'))
    ws = sk['weapon_scale']
    quads = mannequin_quads({b: np.eye(4) for b in ['Head', 'UpBody', 'RightArm', 'RightForeArm', 'RightHand',
                                                    'LeftArm', 'LeftForeArm', 'LeftHand', 'RightLeg',
                                                    'RightLowerLeg', 'RightFoot', 'LeftLeg', 'LeftLowerLeg',
                                                    'LeftFoot']})
    xs = 14
    for name in (NAME, 'lingxiao_sword', 'pack_oath_spear'):
        w = m if name == NAME else json.loads((ASSETS / f'combat/weapons/{name}.json').read_text(encoding='utf-8'))
        t = tex if name == NAME else np.asarray(Image.open(ASSETS / w['texture'].split(':')[1]).convert('RGBA')).astype(np.float32)
        s = ws * w.get('scale', 1)
        lo = min(min(c['start'][1], c['end'][1]) for c in w['cubes'])
        quads += weapon_quads(w, t, affine(np.eye(3) * s, np.array([xs, -lo * s, 0])), 'weapon')
        xs += 14
    cmp = render(quads, Camera(0, 0, 6.5, (20, 18, 0), (520, 300)), ground=False)
    sheet([('比例', [('狼牙长枪 / 凌霄 / 铳刃 / 假人（第三人称大小）', cmp)])], '大小对照', out_dir / 'scale.png')
    icon(m, tex).resize((256, 256), Image.NEAREST).save(out_dir / 'icon_x8.png')
    tex_img.resize((tex_img.width * 4, tex_img.height * 4), Image.NEAREST).save(out_dir / 'texture_x4.png')


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


# ---------------------------------------------------------------- Blockbench 工程
# 导出成 Bedrock 格式的 .bbmodel 给维护者手改，改完用 tools/import_gunaxe.py 读回来。
# 工程坐标就是网格坐标（原点在主手握点），骨骼是同名的组，贴图内嵌。
# 面的 UV 按 Blockbench（也就是原版）的方向写：每个面的 U 和动作网格相反，导出、读回各对调一次。

def _uuid(*parts):
    import uuid
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'logos_dimension/gunaxe/' + '/'.join(parts)))


def to_bbmodel(m, tex_img):
    import base64
    import io
    buf = io.BytesIO()
    tex_img.save(buf, 'PNG')
    W, H = m['texture_size']
    m = dict(m, cubes=[c for c in m['cubes'] if not c['bone'].startswith('Spare')],
             bones=[b for b in m['bones'] if not b['name'].startswith('Spare')])
    parents = {'Weapon': None, **{b['name']: b['parent'] for b in m['bones']}}
    pivots = {'Weapon': [0, 0, 0], **{b['name']: b['pivot'] for b in m['bones']}}
    children = {g: [] for g in parents}
    for i, c in enumerate(m['cubes']):
        uid = _uuid('cube', str(i), c['name'])
        children[c['bone']].append(uid)
    for g, par in parents.items():
        if par is not None:
            children[par].append(('group', g))
    elements = []
    for i, c in enumerate(m['cubes']):
        elements.append({
            'name': c['name'], 'type': 'cube', 'uuid': _uuid('cube', str(i), c['name']),
            'from': c['start'], 'to': c['end'], 'origin': c['pivot'], 'rotation': c['rotation'],
            'box_uv': False, 'autouv': 0, 'export': True, 'visibility': True,
            'faces': {f: {'uv': [uv[2], uv[1], uv[0], uv[3]], 'texture': 0} for f, uv in c['uv'].items()}})
    groups = [{'name': g, 'uuid': _uuid('group', g), 'origin': [round(v, 4) for v in pivots[g]],
               'rotation': [0, 0, 0], 'export': True, 'visibility': True, 'isOpen': g == 'Weapon'} for g in parents]

    def node(g):
        kids = [node(ch[1]) if isinstance(ch, tuple) else ch for ch in children[g]]
        return {'uuid': _uuid('group', g), 'isOpen': g == 'Weapon', 'children': kids}
    return {
        'meta': {'format_version': '5.0', 'model_format': 'bedrock', 'box_uv': False},
        'name': NAME, 'model_identifier': NAME, 'geometry_name': NAME,
        'resolution': {'width': W, 'height': H},
        'elements': elements, 'groups': groups, 'outliner': [node('Weapon')],
        'textures': [{'uuid': _uuid('texture'), 'id': '0', 'name': f'{NAME}_rig.png', 'width': W, 'height': H,
                      'uv_width': W, 'uv_height': H, 'internal': True, 'mode': 'bitmap',
                      'source': 'data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()}],
    }


def from_bbmodel(bb):
    """读回 Blockbench 工程：组 → 骨骼（Weapon 之外），方块归属所在的组，贴图取内嵌的那张。"""
    import base64
    import io
    groups = {g['uuid']: g for g in bb['groups']}
    elems = {e['uuid']: e for e in bb['elements']}
    bones, cubes = [], []

    known = {b['name'] for b in bones_spec()}

    def walk(nd, parent, folder=np.eye(4)):
        """parent：所属的骨骼名。不认识的组只是文件夹：里面的方块归最近的骨骼，文件夹的旋转烘进方块。"""
        g = groups[nd['uuid']]
        name = g['name']
        rot = g.get('rotation') or [0, 0, 0]
        if parent is None or name in known:
            if parent is not None:
                bones.append(dict(name=name, parent=parent, pivot=[round(v, 4) for v in g['origin']]))
            owner, folder = name, np.eye(4)
        else:
            owner = parent
            if any(rot):
                folder = folder @ cube_local(g['origin'], rot)
        for ch in nd.get('children', []):
            if isinstance(ch, dict):
                walk(ch, owner, folder)
            elif ch in elems and elems[ch].get('type', 'cube') == 'cube' and elems[ch].get('export', True):
                e = elems[ch]
                lo = np.array([min(a, b) for a, b in zip(e['from'], e['to'])], float)
                hi = np.array([max(a, b) for a, b in zip(e['from'], e['to'])], float)
                piv = np.array(e.get('origin', [0, 0, 0]), float)
                r = list(e.get('rotation') or [0, 0, 0])
                if not np.allclose(folder, np.eye(4)):
                    R = folder[:3, :3] @ euler_matrix(r)
                    p2 = (folder @ np.append(piv, 1))[:3]
                    lo, hi = p2 + (lo - piv), p2 + (hi - piv)
                    piv, r = p2, [float(v) for v in matrix_euler(R)]
                cubes.append({'name': e['name'], 'part': 'weapon', 'bone': owner,
                              'start': [round(v, 5) for v in lo], 'end': [round(v, 5) for v in hi],
                              'pivot': [round(v, 5) for v in piv],
                              'rotation': [round(v, 5) for v in r],
                              'uv': {f: [d['uv'][2], d['uv'][1], d['uv'][0], d['uv'][3]]
                                     for f, d in e['faces'].items() if d.get('texture') is not None}})
    roots = [nd for nd in bb['outliner'] if isinstance(nd, dict)]
    weapon = [nd for nd in roots if groups[nd['uuid']]['name'] == 'Weapon']
    if len(weapon) != 1:
        raise SystemExit('工程里要有且只有一个名为 Weapon 的根组（武器原点、主手握点）')
    walk(weapon[0], None)
    tex = bb['textures'][0]
    src = tex.get('source', '')
    if src.startswith('data:'):
        tex_img = Image.open(io.BytesIO(base64.b64decode(src.split(',', 1)[1]))).convert('RGBA')
    else:  # 贴图另存成文件时按工程里记的路径读
        tex_img = Image.open(tex['path']).convert('RGBA')
    res = bb.get('resolution', {})
    W, H = res.get('width', tex_img.width), res.get('height', tex_img.height)
    if (W, H) != tex_img.size:  # 改过分辨率时按贴图实际大小换算 UV
        for c in cubes:
            c['uv'] = {f: [uv[0] * tex_img.width / W, uv[1] * tex_img.height / H, uv[2] * tex_img.width / W,
                           uv[3] * tex_img.height / H] for f, uv in c['uv'].items()}
    return {
        'texture': f'logos_dimension:textures/item/{NAME}_rig.png',
        'texture_size': [tex_img.width, tex_img.height],
        'scale': SCALE,
        'bones': bones,
        'cubes': cubes,
        'grip': [0, 0, 0],
        'tip': blade_tip(cubes),
    }, tex_img


def blade_tip(cubes):
    """刀尖：刃上所有方块角点里最靠前（+Y）的那个。"""
    best = None
    for c in cubes:
        if not c['name'].startswith('blade'):
            continue
        T = cube_local(c['pivot'], c['rotation'])
        for corner in np.array(np.meshgrid(*zip(c['start'], c['end']))).T.reshape(-1, 3):
            p = (T @ np.append(corner, 1))[:3]
            if best is None or p[1] > best[1]:
                best = p
    return [round(float(best[0]), 4), round(float(best[1]), 4), 0.0]


# 换弹用的备用转轮：转轮和六发子弹的方块各复制一份，挂在 SpareCylinder / SpareRound0..5 上。
# 不进 Blockbench 工程、不进物品模型；动作里平时缩成 0，换弹时它充当被顶出去的旧转轮。
SPARE = 'Spare'


def add_spare(m):
    cubes = [c for c in m['cubes'] if not c['bone'].startswith(SPARE)]
    bones = [b for b in m['bones'] if not b['name'].startswith(SPARE)]
    rename = {'Cylinder': SPARE + 'Cylinder', **{f'Round{k}': f'{SPARE}Round{k}' for k in range(6)}}
    for b in list(bones):
        if b['name'] in rename:
            bones.append(dict(b, name=rename[b['name']], parent='Weapon' if b['name'] == 'Cylinder' else rename[b['parent']]))
    for c in list(cubes):
        if c['bone'] in rename:
            cubes.append(dict(c, name='spare_' + c['name'], bone=rename[c['bone']]))
    return dict(m, bones=bones, cubes=cubes)


def write_assets(m, tex_img, preview_dir=None):
    m = add_spare(m)
    tex = np.asarray(tex_img).astype(np.float32)
    write_json(ASSETS / f'combat/weapons/{NAME}.json', m)
    tex_img.save(ASSETS / f'textures/item/{NAME}_rig.png')
    icon(m, tex).save(ASSETS / f'textures/item/{NAME}.png')
    write_json(ASSETS / f'models/item/{NAME}.json', item_model(m))
    write_json(ASSETS / f'models/item/{NAME}_icon.json',
               {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'logos_dimension:item/{NAME}'}})
    write_json(ASSETS / f'items/{NAME}.json', {'model': {
        'type': 'minecraft:select', 'property': 'minecraft:display_context',
        'cases': [{'when': ['gui', 'fixed'], 'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{NAME}_icon'}}],
        'fallback': {'type': 'minecraft:model', 'model': f'logos_dimension:item/{NAME}'}}})
    cells = sum(max(1, round(abs(uv[2] - uv[0]))) * max(1, round(abs(uv[3] - uv[1])))
                for c in m['cubes'] for uv in c['uv'].values())
    ys = [v for c in m['cubes'] for v in (c['start'][1], c['end'][1])]
    print(f'{len(m["cubes"])} cubes, texture {tex_img.width}x{tex_img.height}, {cells} cells, '
          f'length {max(ys) - min(ys):.1f} units = {(max(ys) - min(ys)) * SCALE * 0.8 / 16:.2f} blocks (third person)')
    if preview_dir:
        out = Path(preview_dir); out.mkdir(parents=True, exist_ok=True)
        preview(m, tex_img, out)
        print('preview:', out)


def main():
    """生成初稿：写资源；给了 --bbmodel 时另存一份 Blockbench 工程（维护者手改之后改用 import_gunaxe.py）。"""
    args = sys.argv[1:]
    bb_out = None
    if '--bbmodel' in args:
        i = args.index('--bbmodel')
        bb_out = Path(args[i + 1])
        del args[i:i + 2]
    m, tex_img = mesh()
    write_assets(m, tex_img, args[0] if args else None)
    if bb_out:
        bb_out.parent.mkdir(parents=True, exist_ok=True)
        bb_out.write_text(json.dumps(to_bbmodel(m, tex_img), ensure_ascii=False, indent=1), encoding='utf-8',
                          newline='\n')
        print('bbmodel:', bb_out)


if __name__ == '__main__':
    main()
