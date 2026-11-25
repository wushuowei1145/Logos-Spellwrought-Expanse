# -*- coding: utf-8 -*-
"""铳刃（暂名 gunaxe）的动作编排，以及烘焙、导出与预览。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/gunaxe_moves.py <输出目录> [动作名…] [--gif]

编排坐标同 combat_author.py：以两肩中点为原点、臂长为单位；前 -Z、上 +Y、角色右手 +X。
blade 是枪口方向（网格 +Y），edge 是刃口朝向（网格 +X，枪的“下方”）；多数关键帧写在髋部坐标里（space='hips'）。
右手握枪托握颈（网格原点）。近战全是单手大开大合，像单手抡一柄长柄斧，左手空着配重；
只有举枪瞄准时左手才握护木前端（lpos，网格单位），换弹时左手取放转轮。

枪上的骨骼由烘焙时算出（GunBaker）：
- hammer / trigger：击锤前倒、扳机后扣的角度（度）。
- cyl_spin：转轮绕自身轴转过的角度；开一枪转 60°（六棱、六发，转完和原来一模一样）。
- 换弹：开始时备用转轮（SpareCylinder）顶替旧转轮坐在机匣里，真转轮（Cylinder）藏起来；
  左手从腰后取出“新转轮”（就是真转轮，cyl_hold 为 1 时跟着左手拳心走），从枪的左侧把它压进机匣，
  旧的（备用转轮）被从右侧顶出去、翻滚着掉落，然后缩成 0。cyl_show / spare_show 控制显示。
标记：contact 命中、fire 开枪（枪口火光、后坐）、feed 新转轮到位（服务端补满弹药）、cancel 可接下一招。
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

from combat_author import (Baker, affine, bedrock, channel, euler_matrix, frame, hermite, marker_list, npc_parried,
                           player_skeleton, rot_x, rot_y, rot_z, translate, unit)
from combat_render import Camera, box, mannequin_quads, render, sheet
from yumi_moves import FP_ARMS, FirstPerson, add_bow, render_fp

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
WEAPON = json.loads((ASSETS / 'combat/weapons/gunaxe.json').read_text(encoding='utf-8'))
TIP = np.array(WEAPON['tip'] + [1.0])
MUZZLE = next(b['pivot'] for b in WEAPON['bones'] if b['name'] == 'MuzzleLocator')
CYL = np.array(next(b['pivot'] for b in WEAPON['bones'] if b['name'] == 'Cylinder'), float)
# 右手握点就是网格原点（单手挥砍、突刺都只用右手）；左手只在瞄准时握护木，换弹时取转轮。
GRIPS = (0.0, 8.0)
SLIDE = (3.0, 9.6)


def cube_top(*names):
    """几个方块的顶（网格 X 最小处，枪的上方为负 X），前后取中点。"""
    cs = [next(c for c in WEAPON['cubes'] if c['name'] == n) for n in names]
    x = min(min(c['start'][0], c['end'][0]) for c in cs)
    y = sum(c['start'][1] + c['end'][1] for c in cs) / (2 * len(cs))
    return np.array([x, y, 0.0])


# 第一人称瞄准的视线：照门两耳的顶 → 准星顶，从模型里取，手改模型后跟着变。
# 照门要高过它后面的机匣后沿（frame_round 最高 -2.37）和顶梁（top_strap -2.20），否则瞄准时被挡住（main 里检查）。
# 击锤比视线高，瞄准时第一人称把它往后扳倒（AIM_HAMMER）。
SIGHT_BACK = cube_top('rear_sight_n', 'rear_sight_s')
SIGHT_FRONT = cube_top('front_sight')
NOTCH = cube_top('rear_sight_base')          # 照门缺口底
AIM_HAMMER = -120.0
# LongswordClient.hand 给第一人称骨架多转了 2°（绕镜头 X 轴往上抬）。瞄准时反着转回来，视线才正对屏幕中心；预览也照样转。
FP_TILT = 2.0
FP_TILT_M = affine(rot_x(FP_TILT), np.zeros(3))
# 换弹时左手拳心到转轮中心的偏移（网格单位）：拳头在转轮外侧（-Z，枪的左面）。
HOLD = np.array([0.0, 0.0, 1.55])
# 第一人称：窗口正对镜头，拳头挡在转轮前面就看不到换弹了；改成从枪口那一侧捏住转轮，拳头在转轮前方、略靠外。
HOLD_FP = np.array([0.0, -2.3, 0.9])
BUTT_Y = min(min(c['start'][1], c['end'][1]) for c in WEAPON['cubes'])   # 托底板（网格 Y）
BUTT_CLEAR = 5.0                                                          # 第一人称托底板离眼睛至少这么远

# ---------------------------------------------------------------- 常用姿势


def k(t, base=None, **kw):
    key = dict(base or {})
    key.update(kw)
    key['t'] = t
    return key


STANCE = dict(rfoot=(0.22, 0, 0.24, -25), lfoot=(-0.14, 0, -0.26, 15))
WIDE = dict(rfoot=(0.26, 0, 0.30, -30), lfoot=(-0.20, 0, -0.34, 10))     # 挥大招时两脚分得更开
LUNGE = dict(lfoot=(-0.16, 0, -0.72, 10), rfoot=(0.22, 0, 0.42, -30))    # 弓步


def fpc(blade, edge=None, window=None, hand=None, point=None, at=None, **extra):
    """第一人称的一帧：直接按镜头写枪在哪、朝哪。hand 是右手（网格原点）相对眼睛的位置，
    或者用 point（网格坐标）+ at 让枪上某一点落在眼前某处。x 向右、y 向上、z 向前为负（模型单位）。
    window 给出时按“转轮窗口朝向”算刃向。身子不拧（只影响手臂从哪伸进来）。extra 覆盖其他字段（例如左手）。"""
    if edge is None:
        edge = window_edge(blade, window)
    cam = dict(blade=tuple(blade), edge=tuple(edge), point=tuple(point if point is not None else (0, 0, 0)),
               at=tuple(at if at is not None else hand))
    return dict(cam=cam, up=(0, 0, 0), chest=(0, 0, 0), head=(0, 0, 0), **extra)


def window_edge(blade, window):
    """给定枪口方向和“枪左面（转轮窗口）要朝向的方向”，算出刃向（网格 +X）。"""
    y = unit(np.asarray(blade, float))
    w = np.asarray(window, float)
    z = -unit(w - y * np.dot(w, y))          # 网格 +Z 是枪的右面，左面朝 window
    return tuple(np.round(np.cross(y, z), 4))


# 第一人称里空着的左手收在画面左下外面。
FP_LEFT_AWAY = (-0.55, -0.95, 0.0)


def fp1(blade, edge=None, hand=None, lhand=FP_LEFT_AWAY):
    """第一人称单手的一帧：左手不在枪上，收到画面外。"""
    return fpc(blade, edge, hand=hand, lhand=lhand)


# 第一人称待机：右手在画面右下角外，枪身从右下斜进画面，枪托落在镜头下面看不到（像 FPS 端枪）。
FP_IDLE = fp1(hand=(7.0, -7.5, -8.5), blade=(-0.15, 0.12, -0.98), edge=(0.15, -0.99, 0.0))
# 待机：单手提着，像提一柄长柄斧：右手在右胯前，枪口斜指前下方、刃朝下；左手空着垂在身侧。
IDLE = dict(space='hips', grip=(0.46, -0.84, -0.14), blade=(0.10, -0.22, -0.97), edge=(0.05, -0.97, 0.22),
            rpos=GRIPS[0], lmode='free', lhand=(-0.44, -0.86, 0.02), rmode='sword', up=(-2, -12, 0), fp=FP_IDLE)
# 举枪瞄准（第三人称）：枪托抵右肩，左手前移握在机匣前，身子侧过来。只有这时左手才上枪。
AIM = dict(space='hips', grip=(0.31, 0.14, -0.66), blade=(0.0, 0.02, -1.0), edge=(0, -1, 0), rpos=GRIPS[0],
           lmode='two', lpos=3.4, rmode='sword', up=(-2, -36, 0), head=(6, 0, -8), blend=0.12)
# 第一人称瞄准：照门、准星连成的线穿过眼睛，照门在眼前 ads 个单位（GunBaker 按 ads 算出握点）。
AIM_FP = dict(ads=10.2, up=(0, 0, 0), head=(0, 0, 0), chest=(0, 0, 0))
AIM_ROOT = ((2.0, -3.0, 0), (0, 0, 2))   # 第一人称瞄准时根骨居中，左手从左下方伸进来托枪

# 枪上的骨骼：平时的样子（换弹、开枪之外）。
GUN_REST = dict(hammer=0.0, trigger=0.0, cyl_spin=0.0, cyl_hold=0.0, cyl_show=1.0, spare_show=0.0)


def gun(t, **kw):
    """只写枪上骨骼的关键帧。"""
    return dict(t=t, **kw)


def swing(**kw):
    """单手挥砍的一帧（髋部坐标）。"""
    return dict(space='hips', rpos=GRIPS[0], lmode='free', **kw)


# ---------------------------------------------------------------- 动作


def clips():
    out = []

    out.append(dict(name='idle', length=2.4, loop='loop', keys=[
        k(0.0, IDLE),
        k(1.2, IDLE, grip=(0.46, -0.82, -0.13), blade=(0.10, -0.19, -0.98), lhand=(-0.44, -0.84, 0.03), up=(-1, -11, 0),
          fp=fp1(hand=(7.0, -7.3, -8.5), blade=(-0.15, 0.14, -0.98), edge=(0.15, -0.99, 0.0))),
        k(2.4, IDLE),
    ]))

    # 第一击：过头斜劈。整条胳膊把枪抡到右肩后上方（刃朝上、枪头在脑后），身子后仰右拧蓄满；
    # 一刀从右上劈到左下，身子跟着压下去、左拧，左手往后甩配重。
    a1_follow = swing(grip=(-0.30, -0.58, -0.48), blade=(-0.78, -0.48, -0.40), edge=(-0.30, -0.25, 0.92),
                      lhand=(-0.62, -0.28, 0.42), up=(-20, 38, 0), **WIDE,
                      fp=fp1(hand=(-5.0, -8.5, -8.0), blade=(-0.72, -0.55, -0.42), edge=(-0.30, -0.25, 0.92)))
    a1_rest = dict(a1_follow, grip=(-0.12, -0.62, -0.42), blade=(-0.58, -0.50, -0.64), edge=(-0.45, -0.30, 0.84),
                   lhand=(-0.52, -0.60, 0.22), up=(-12, 26, 0),
                   fp=fp1(hand=(-2.0, -8.5, -8.5), blade=(-0.60, -0.48, -0.64), edge=(-0.45, -0.30, 0.84)))
    out.append(dict(name='attack_1', length=0.72, loop='once',
                    markers={'input_open': 0.1, 'contact': 0.3, 'cancel': 0.48}, keys=[
        k(0.0, IDLE),
        # 抡上去：枪口先朝天（刃朝前），再倒到脑后。
        k(0.08, swing(grip=(0.56, 0.18, -0.12), blade=(0.22, 0.95, -0.20), edge=(0.05, 0.20, -0.98),
                      lhand=(-0.45, -0.30, -0.45), up=(4, -32, 0),
                      fp=fp1(hand=(9.0, -1.5, -7.0), blade=(0.25, 0.92, -0.30), edge=(-0.10, 0.32, -0.94)))),
        k(0.16, swing(grip=(0.52, 0.52, 0.28), blade=(0.30, 0.22, 0.93), edge=(0.05, 0.97, -0.22),
                      lhand=(-0.45, 0.02, -0.62), up=(8, -48, 0), chest=(4, 0, 0), **WIDE,
                      fp=fp1(hand=(12.0, 4.0, -7.0), blade=(0.30, 0.95, -0.05), edge=(-0.20, 0.10, -0.97))), ease='hold'),
        k(0.24, swing(grip=(0.42, 0.62, -0.38), blade=(0.05, 0.98, -0.15), edge=(-0.25, 0.10, -0.96),
                      lhand=(-0.50, -0.10, -0.30), up=(2, -22, 0),
                      fp=fp1(hand=(6.0, 3.0, -8.0), blade=(0.0, 0.95, -0.30), edge=(-0.30, 0.25, -0.92))), ease='lin'),
        k(0.30, swing(grip=(0.14, -0.08, -0.94), blade=(-0.45, -0.35, -0.82), edge=(-0.55, -0.80, 0.25),
                      lhand=(-0.58, -0.30, 0.20), up=(-18, 12, 0),
                      fp=fp1(hand=(3.0, -2.5, -10.0), blade=(-0.80, -0.30, -0.52), edge=(-0.45, -0.85, 0.25))), ease='lin'),
        k(0.40, a1_follow, ease='hold'),
        k(0.72, a1_rest),
    ]))

    # 第二击：反手上撩。枪从左下方翻过来（刃朝右上），胳膊反手一撩，从左下抡到右上方，身子跟着右拧展开。
    a2_follow = swing(grip=(0.72, 0.42, -0.32), blade=(0.62, 0.72, -0.30), edge=(0.35, 0.05, 0.94),
                      lhand=(-0.60, -0.20, -0.42), up=(8, -48, 0), **WIDE,
                      fp=fp1(hand=(9.0, 3.0, -7.0), blade=(0.65, 0.70, -0.30), edge=(0.35, 0.05, 0.94)))
    a2_rest = dict(a2_follow, grip=(0.66, 0.30, -0.28), blade=(0.58, 0.64, -0.50), edge=(0.40, 0.20, 0.89),
                   lhand=(-0.52, -0.50, -0.20), up=(4, -40, 0),
                   fp=fp1(hand=(8.5, 2.0, -7.5), blade=(0.58, 0.62, -0.52), edge=(0.40, 0.20, 0.89)))
    out.append(dict(name='attack_2', length=0.7, loop='once',
                    markers={'input_open': 0.1, 'contact': 0.3, 'cancel': 0.46}, keys=[
        k(0.0, a1_rest),
        k(0.09, swing(grip=(-0.18, -0.64, -0.40), blade=(-0.62, -0.52, -0.58), edge=(-0.45, 0.40, 0.80),
                      lhand=(-0.50, -0.50, 0.25), up=(-14, 34, 0),
                      fp=fp1(hand=(-3.0, -9.0, -8.5), blade=(-0.66, -0.50, -0.55), edge=(-0.45, 0.45, 0.75)))),
        k(0.18, swing(grip=(-0.26, -0.66, -0.30), blade=(-0.70, -0.45, -0.55), edge=(0.35, 0.50, -0.79),
                      lhand=(-0.40, -0.45, 0.35), up=(-14, 48, 0), **WIDE,
                      fp=fp1(hand=(-5.0, -9.0, -8.0), blade=(-0.70, -0.45, -0.55), edge=(0.35, 0.50, -0.79))), ease='hold'),
        k(0.30, swing(grip=(0.34, -0.10, -0.92), blade=(0.35, 0.25, -0.90), edge=(0.60, 0.75, 0.20),
                      lhand=(-0.58, -0.30, -0.10), up=(-4, -8, 0),
                      fp=fp1(hand=(2.5, -3.0, -11.0), blade=(0.35, 0.25, -0.90), edge=(0.60, 0.75, 0.20))), ease='lin'),
        k(0.38, a2_follow, ease='hold'),
        k(0.70, a2_rest),
    ]))

    # 第三击：抡圆横扫。胳膊整条甩到右后方、身子拧到底，一记齐胸的大横扫从右抡到左，胳膊伸直、够得最远。
    a3_follow = swing(grip=(-0.62, -0.20, -0.52), blade=(-0.92, 0.0, -0.38), edge=(-0.38, 0, 0.92),
                      lhand=(0.10, -0.55, 0.50), up=(-6, 55, 4), **WIDE,
                      fp=fp1(hand=(-5.5, -3.5, -9.0), blade=(-0.90, 0.0, -0.40), edge=(-0.40, 0, 0.92)))
    a3_rest = dict(a3_follow, grip=(-0.40, -0.35, -0.55), blade=(-0.70, -0.10, -0.70), edge=(-0.70, 0, 0.70),
                   lhand=(-0.20, -0.70, 0.30), up=(-4, 34, 0),
                   fp=fp1(hand=(-3.0, -5.0, -9.5), blade=(-0.70, -0.10, -0.70), edge=(-0.70, 0, 0.70)))
    out.append(dict(name='attack_3', length=0.78, loop='once',
                    markers={'input_open': 0.1, 'contact': 0.32, 'cancel': 0.5}, keys=[
        k(0.0, a2_rest),
        k(0.08, swing(grip=(0.80, 0.05, -0.05), blade=(0.85, 0.30, -0.42), edge=(0, 1, 0),
                      lhand=(-0.45, -0.25, -0.50), up=(0, -60, -2),
                      fp=fp1(hand=(9.5, 0.0, -7.0), blade=(0.80, 0.30, -0.50), edge=(0, 1, 0)))),
        k(0.18, swing(grip=(0.84, -0.15, 0.30), blade=(0.85, 0.05, 0.52), edge=(0.52, 0, -0.85),
                      lhand=(-0.40, -0.10, -0.62), up=(-2, -72, 0), **WIDE,
                      fp=fp1(hand=(12.0, -3.0, -7.0), blade=(0.70, 0.70, 0.10), edge=(0.40, -0.30, -0.87))), ease='hold'),
        k(0.26, swing(grip=(0.56, -0.15, -0.66), blade=(0.75, 0.03, -0.65), edge=(-0.65, 0, -0.75),
                      lhand=(-0.50, -0.25, -0.30), up=(-4, -38, 0),
                      fp=fp1(hand=(8.0, -3.0, -9.0), blade=(0.75, 0.03, -0.65), edge=(-0.65, 0, -0.75))), ease='lin'),
        k(0.32, swing(grip=(0.06, -0.15, -0.98), blade=(-0.05, 0.03, -1.0), edge=(-1, 0, 0.05),
                      lhand=(-0.50, -0.40, 0.20), up=(-6, -6, 0),
                      fp=fp1(hand=(4.0, -3.0, -11.0), blade=(-0.50, 0.05, -0.86), edge=(-0.86, 0, 0.50))), ease='lin'),
        k(0.40, a3_follow),
        k(0.50, a3_follow, ease='hold'),
        k(0.78, a3_rest),
    ]))

    # 第四击：单手弓步突刺。枪收到右胸侧、左手前指目标，一步跨出，胳膊连枪直直捅出去，左手往后甩。
    thrust = swing(grip=(0.18, -0.10, -1.02), blade=(-0.02, 0.03, -1.0), edge=(0, -1, 0.03),
                   lhand=(-0.55, -0.45, 0.45), up=(-14, 5, 0), **LUNGE,
                   fp=fp1(hand=(2.0, -3.5, -15.0), blade=(-0.08, 0.10, -0.99), edge=(0, -0.99, 0.10)))
    thrust_rest = dict(thrust, grip=(0.22, -0.16, -0.92), up=(-12, 0, 0),
                       fp=fp1(hand=(2.5, -4.0, -13.5), blade=(-0.08, 0.10, -0.99), edge=(0, -0.99, 0.10)))
    out.append(dict(name='attack_4', length=0.62, loop='once',
                    markers={'input_open': 0.1, 'contact': 0.24, 'cancel': 0.4}, keys=[
        k(0.0, a3_rest),
        k(0.14, swing(grip=(0.55, -0.30, 0.22), blade=(0.02, 0.06, -1.0), edge=(0, -1, 0.06),
                      lhand=(-0.35, -0.12, -0.62), up=(-2, -45, 0), **STANCE,
                      fp=fp1(hand=(8.0, -7.0, -5.0), blade=(-0.08, 0.06, -0.99), edge=(0, -1, 0.06),
                             lhand=(-0.30, -0.25, -0.75))), ease='hold'),
        k(0.24, thrust, ease='lin'),
        k(0.34, thrust, grip=(0.18, -0.11, -1.0), ease='hold',
          fp=fp1(hand=(2.0, -3.6, -14.6), blade=(-0.08, 0.10, -0.99), edge=(0, -0.99, 0.10))),
        k(0.62, thrust_rest),
    ]))

    # 铳击：刺到底顺势扣扳机。单手扛不住后坐：整条胳膊连枪被掀到头顶前上方，人被顶得后仰、后脚滑退，再把枪压回来。
    kick = dict(thrust, grip=(0.40, 0.45, -0.55), blade=(0.05, 0.86, -0.50), edge=(0, -0.50, -0.86),
                lhand=(-0.62, -0.35, 0.10), up=(14, -26, 0), chest=(8, 0, 0), head=(12, 0, 0), hips=(0, -0.02, 0.12),
                lfoot=(-0.16, 0, -0.46, 10), rfoot=(0.22, 0, 0.60, -30),
                fp=fp1(hand=(5.0, 1.0, -10.0), blade=(-0.05, 0.85, -0.52), edge=(0, -0.52, -0.85)))
    shot_end = dict(thrust, grip=(0.38, -0.30, -0.70), blade=(0.05, -0.10, -0.99), edge=(0, -0.99, 0.10),
                    lhand=(-0.48, -0.70, 0.15), up=(-6, -14, 0), lfoot=(-0.14, 0, -0.38, 12), rfoot=(0.22, 0, 0.40, -28),
                    fp=fp1(hand=(4.5, -5.0, -11.0), blade=(-0.14, 0.15, -0.98), edge=(0.10, -0.99, 0.15)))
    out.append(dict(name='shot_5', length=0.95, loop='once', markers={'fire': 0.06, 'cancel': 0.66}, keys=[
        k(0.0, thrust_rest),
        k(0.06, thrust_rest, grip=(0.22, -0.16, -0.94), up=(-13, 1, 0), ease='hold',
          fp=fp1(hand=(2.5, -4.0, -13.7), blade=(-0.08, 0.10, -0.99), edge=(0, -0.99, 0.10))),
        k(0.12, kick, ease='lin'),
        k(0.22, kick, grip=(0.40, 0.42, -0.53), blade=(0.05, 0.82, -0.57), ease='hold',
          fp=fp1(hand=(5.0, 0.5, -10.2), blade=(-0.05, 0.80, -0.60), edge=(0, -0.60, -0.80))),
        k(0.50, shot_end),
        k(0.95, IDLE, **STANCE),
        gun(0.0, trigger=0.0, hammer=0.0, cyl_spin=0.0), gun(0.05, trigger=16.0), gun(0.06, hammer=28.0, ease='lin'),
        gun(0.32, trigger=16.0, hammer=28.0, cyl_spin=0.0, ease='hold'),
        gun(0.50, trigger=0.0, hammer=0.0, cyl_spin=60.0), gun(0.95, cyl_spin=60.0),
    ]))

    # 空膛的第五下：刺到底扣扳机，只有“咔哒”一声；枪口失望地一沉，收回待机（之后自动换弹）。
    dry_dip = dict(thrust_rest, grip=(0.24, -0.22, -0.86), blade=(0.0, -0.08, -1.0), edge=(0, -1, -0.08), up=(-10, -4, 0),
                   fp=fp1(hand=(3.0, -5.2, -12.6), blade=(-0.08, 0.0, -1.0), edge=(0, -1, 0)))
    out.append(dict(name='dry_5', length=0.55, loop='once', markers={'fire': 0.06, 'cancel': 0.3}, keys=[
        k(0.0, thrust_rest),
        k(0.06, thrust_rest, grip=(0.22, -0.16, -0.93), ease='hold',
          fp=fp1(hand=(2.5, -4.0, -13.6), blade=(-0.08, 0.10, -0.99), edge=(0, -0.99, 0.10))),
        k(0.18, dry_dip, ease='hold'),
        k(0.55, IDLE, **STANCE),
        gun(0.0, trigger=0.0, hammer=0.0), gun(0.05, trigger=16.0), gun(0.06, hammer=28.0, ease='lin'),
        gun(0.30, trigger=16.0, hammer=28.0, ease='hold'), gun(0.45, trigger=0.0, hammer=0.0),
    ]))

    # 举枪瞄准（按住右键）：左手上枪握住护木前端，枪托抵肩、贴腮，枪口随呼吸轻轻晃。
    out.append(dict(name='aim', length=2.0, loop='loop', fp_root=AIM_ROOT, keys=[
        gun(0.0, hammer=0.0, fp=dict(hammer=AIM_HAMMER)),
        k(0.0, AIM, fp=AIM_FP),
        k(1.0, AIM, grip=(0.31, 0.15, -0.66), blade=(0.003, 0.025, -1.0), fp=dict(AIM_FP, sway=(0.03, 0.04))),
        k(2.0, AIM, fp=AIM_FP),
    ]))

    # 瞄准开枪：后坐把枪口顶起二十多度、枪托撞肩、上身后仰，0.5 秒内压回瞄准。
    aim_kick = dict(AIM, grip=(0.33, 0.24, -0.50), blade=(0.0, 0.42, -0.91), edge=(0, -0.91, -0.42),
                    up=(10, -36, 0), head=(12, 0, -8), chest=(4, 0, 0))
    out.append(dict(name='aim_shot', length=0.6, loop='once', markers={'fire': 0.03, 'cancel': 0.45}, fp_root=AIM_ROOT, keys=[
        k(0.0, AIM, fp=AIM_FP),
        k(0.03, AIM, ease='hold', fp=AIM_FP),
        k(0.08, aim_kick, ease='lin', fp=dict(AIM_FP, kick=(24, 2.4))),
        k(0.16, aim_kick, grip=(0.33, 0.22, -0.52), blade=(0.0, 0.38, -0.92), ease='hold', fp=dict(AIM_FP, kick=(20, 2.0))),
        k(0.60, AIM, fp=AIM_FP),
        gun(0.0, trigger=0.0, hammer=0.0, cyl_spin=0.0, fp=dict(hammer=AIM_HAMMER)), gun(0.02, trigger=16.0),
        gun(0.03, hammer=28.0, ease='lin', fp=dict(hammer=AIM_HAMMER)),
        gun(0.18, trigger=16.0, hammer=28.0, cyl_spin=0.0, ease='hold', fp=dict(hammer=AIM_HAMMER)),
        gun(0.36, trigger=0.0, hammer=0.0, cyl_spin=60.0, fp=dict(hammer=AIM_HAMMER)), gun(0.6, cyl_spin=60.0),
    ]))

    # 瞄准时空膛扣扳机：只有“咔哒”一声，枪口轻轻一沉。
    out.append(dict(name='aim_dry', length=0.35, loop='once', markers={'fire': 0.03, 'cancel': 0.2}, fp_root=AIM_ROOT, keys=[
        k(0.0, AIM, fp=AIM_FP),
        k(0.03, AIM, ease='hold', fp=AIM_FP),
        k(0.12, AIM, grip=(0.31, 0.12, -0.66), blade=(0.0, -0.01, -1.0), ease='hold', fp=dict(AIM_FP, kick=(-2, 0.0))),
        k(0.35, AIM, fp=AIM_FP),
        gun(0.0, trigger=0.0, hammer=0.0, fp=dict(hammer=AIM_HAMMER)), gun(0.02, trigger=16.0),
        gun(0.03, hammer=28.0, ease='lin', fp=dict(hammer=AIM_HAMMER)),
        gun(0.18, trigger=16.0, hammer=28.0, ease='hold', fp=dict(hammer=AIM_HAMMER)),
        gun(0.32, trigger=0.0, hammer=0.0, fp=dict(hammer=AIM_HAMMER)),
    ]))

    out.append(reload_clip())

    # 被弹反：枪连胳膊被磕向右上方，身子后仰、胸口敞开，左手甩开；顶住一下露出破绽，再压回待机。
    peak = swing(grip=(0.62, 0.18, 0.08), blade=(0.30, 0.86, 0.40), edge=(0.1, 0.40, -0.91),
                 lhand=(-0.66, -0.40, 0.14), up=(13, -32, 0), chest=(4, -8, 0),
                 fp=fp1(hand=(9.0, 1.5, -7.0), blade=(0.20, 0.90, -0.38), edge=(0, -0.38, -0.92)))
    out.append(dict(name='parried', length=0.5, loop='once', keys=[
        k(0.0, IDLE),
        k(0.07, peak, ease='hold'),
        k(0.32, peak, grip=(0.60, 0.10, 0.10), blade=(0.28, 0.82, 0.50), up=(10, -30, 0), ease='hold',
          fp=fp1(hand=(9.0, 0.9, -7.0), blade=(0.20, 0.88, -0.42), edge=(0, -0.42, -0.90))),
        k(0.5, IDLE),
    ]))
    out.append(npc_parried(out[-1]))

    for clip in out:
        clip.setdefault('stance', dict(STANCE))
        clip['keys'].insert(0, gun(0.0, **{f: v for f, v in GUN_REST.items()
                                           if not any(f in key for key in clip['keys'])}))
    return out


# 换弹时旧转轮被顶出去：从 EJECT 秒起脱离枪身，按网格坐标的初速度飞出（+Z 是枪的右面），之后在世界里自由落体、翻滚。
EJECT = 1.21
EJECT_V = np.array([0.0, 2.0, 15.0])        # 网格单位/秒
EJECT_SPIN = np.array([-320.0, 140.0, 520.0])  # 度/秒
GRAVITY = 220.0                              # 网格单位/秒²（约原版掉落物的重力）


def reload_clip():
    """换弹（2.4 秒，节奏参考 TaCZ 步枪的战术换弹）：
    0–0.24 枪横到胸前偏左、枪口指向左前上方，转轮的左面（窗口）翻过来朝着眼睛；左手离开护木。
    0.24–0.7 左手探到左后腰，取出新转轮。
    0.7–1.12 新转轮举到转轮左面外侧，对准。
    1.12–1.3 一把压进去：旧转轮从右面被顶出去，翻滚着掉落。1.3 新转轮到位（feed）。
    1.34–1.7 左掌一抹，新转轮飞转一圈；1.7–1.95 左手放下、枪摆回来，击锤扳下。1.95–2.4 回到单手待机。
    第一人称：枪横在画面中下方，转轮窗口正对镜头。"""
    blade = (-0.55, 0.30, -0.78)
    fp_blade = (-0.85, 0.30, -0.43)
    hold_fp = fpc(point=CYL, at=(0.5, -4.5, -11.0), blade=fp_blade, window=(0.05, 0.45, 0.89))
    hold = dict(space='hips', grip=(0.14, -0.30, -0.40), blade=blade, edge=window_edge(blade, (0.25, 0.75, 0.6)),
                rpos=GRIPS[0], up=(-6, -4, 0), head=(20, 18, 0), chest=(4, 0, 0), fp=hold_fp)
    above = np.array([CYL[0], CYL[1], -HOLD[2] - 2.6])   # 拳心在转轮左面外侧
    seat = np.array([CYL[0], CYL[1], -HOLD[2]])           # 压到位：转轮中心回到原处
    above_fp = CYL - HOLD_FP + np.array([0, 0, -2.6])
    seat_fp = CYL - HOLD_FP

    def lw(v, fp_v, cam=hold_fp):
        """左手的武器坐标目标；第一人称用自己的握法。"""
        return dict(lweapon=tuple(v), fp=dict(cam, lweapon=tuple(fp_v)))
    pouch = (-0.42, -0.86, 0.22)
    keys = [
        k(0.0, IDLE),
        k(0.24, hold, lmode='free', lhand=(-0.30, -0.40, -0.35), blend=0.12, ease='hold'),
        k(0.45, hold, lhand=(-0.40, -0.75, 0.12)),
        k(0.62, hold, lhand=pouch, ease='hold'),
        k(0.70, hold, lhand=pouch),
        k(0.95, hold, lhand=(-0.22, -0.25, -0.50)),
        k(1.00, hold, lmode='weapon', blend=0.12,
          **lw(above + np.array([0, 0, -1.2]), above_fp + np.array([0, 0, -1.2]))),
        k(1.12, hold, ease='hold', **lw(above, above_fp)),
        k(1.30, hold, grip=(0.14, -0.33, -0.40), ease='lin',
          **lw(seat, seat_fp, fpc(point=CYL, at=(0.5, -4.9, -11.0), blade=fp_blade, window=(0.05, 0.45, 0.89)))),
        k(1.36, hold, grip=(0.14, -0.31, -0.40), ease='hold',
          **lw(seat + np.array([0, 0, -0.5]), seat_fp + np.array([0, 0, -0.5]))),
        # 掌心一抹转轮（从后往前），带它飞转一圈。
        k(1.46, hold, **lw(seat + np.array([0, 2.6, -0.4]), seat_fp + np.array([0, 1.2, -0.6]))),
        k(1.58, hold, lhand=(-0.40, -0.70, -0.15), blend=0.2),
        k(1.75, IDLE, grip=(0.26, -0.40, -0.10), blade=(-0.20, 0.0, -0.98), up=(-4, -18, 0),
          fp=fpc(hand=(5.5, -6.5, -10.0), blade=(-0.40, 0.10, -0.91), edge=(0.10, -0.99, 0.05))),
        k(2.00, IDLE, grip=(0.30, -0.48, 0.04)),
        k(2.40, IDLE),
        # 枪上的骨骼：开始时备用转轮顶替旧转轮，真转轮藏起来，等左手取出来时再出现。
        gun(0.0, cyl_show=1.0, spare_show=0.0, cyl_hold=0.0, cyl_spin=0.0, hammer=0.0),
        gun(0.01, cyl_show=0.0, spare_show=1.0),
        gun(0.62, cyl_show=0.0, cyl_hold=1.0),
        gun(0.63, cyl_show=1.0),
        gun(1.30, cyl_hold=1.0, ease='hold'),
        gun(1.33, cyl_hold=0.0, ease='lin'),
        gun(1.38, cyl_spin=0.0),
        gun(1.75, cyl_spin=360.0, ease='hold'),
        gun(1.78, hammer=0.0), gun(1.84, hammer=-18.0), gun(1.94, hammer=0.0),
        gun(1.75, spare_show=1.0), gun(1.76, spare_show=0.0),
    ]
    return dict(name='reload', length=2.4, loop='once', markers={'feed': 1.3, 'cancel': 2.05}, keys=keys,
                eject=EJECT)

# ---------------------------------------------------------------- 烘焙


class GunBaker(Baker):
    def bake(self, clip, fps):
        """第一人称按动作取景：fp_root / fp_reach 换掉骨架的根骨位置和手部偏移（烘进每帧的 Root）。"""
        sk = self.sk
        saved = getattr(sk, 'root', None), getattr(sk, 'reach', 0)
        if getattr(sk, 'tag', None) == 'fp':
            sk.root = clip.get('fp_root', saved[0])
            sk.reach = np.asarray(clip.get('fp_reach', saved[1]), float)
        self.eject_frame = None
        try:
            return super().bake(clip, fps)
        finally:
            sk.root, sk.reach = saved

    def scalar(self, clip, t, field, default):
        return float(np.atleast_1d(hermite(channel(clip, field, default), t))[0])

    def vec(self, clip, t, field, default):
        return np.asarray(hermite(channel(clip, field, default), t), float)

    def mesh_to_world(self, W):
        sk = self.sk
        return W['Weapon'] @ translate(sk.weapon_pivot) @ np.diag([sk.weapon_scale] * 3 + [1.0])

    def torso(self, clip, t):
        """这一时刻躯干（UpBody）在骨架世界里的矩阵，只看身子的通道（与 Baker.pose 相同）。"""
        sk = self.sk
        pose = {'AllBody': (hermite(channel(clip, 'hips', (0, 0, 0)), t) * sk.hip,
                            euler_matrix(hermite(channel(clip, 'hip_rot', (0, 0, 0)), t))),
                'UpBody': (np.zeros(3), euler_matrix(hermite(channel(clip, 'up', (0, 0, 0)), t)))}
        root = getattr(sk, 'root', None)
        if root is not None:
            pose['Root'] = (np.asarray(root[0], float), euler_matrix(root[1]))
        return sk.world(pose)['UpBody']

    def sword_key(self, clip, key):
        if key.get('cam') is not None:
            # 第一人称按镜头写的一帧：枪上 point 那一点落在眼前 at 处，枪口、刃朝给定的方向（镜头坐标就是骨架世界坐标）。
            sk = self.sk
            cam = key['cam']
            R = frame(cam['blade'], cam['edge'])
            eye = FirstPerson().eye
            origin = eye + np.asarray(cam['at'], float) - R @ (np.asarray(cam['point'], float) * sk.weapon_scale)
            # 枪托不贴到镜头上：托底板离眼睛不到 BUTT_CLEAR 时，整把枪沿视线往前推（单手挥砍时枪托最容易糊脸）。
            butt = origin + R @ np.array([0.0, BUTT_Y * sk.weapon_scale, 0.0])
            depth = eye[2] - butt[2]
            if depth < BUTT_CLEAR:
                origin = origin + np.array([0.0, 0.0, -(BUTT_CLEAR - depth)])
            T = self.torso(clip, key['t'])
            return (np.linalg.inv(T) @ np.append(origin, 1))[:3], T[:3, :3].T @ R[:, 1], T[:3, :3].T @ R[:, 0]
        if key.get('ads') is None:
            return super().sword_key(clip, key)
        # 第一人称瞄准：照门缺口、准星连成的线正对屏幕中心，刃朝下；照门在眼前 ads 个单位。sway / kick 叠加晃动和后坐。
        sk = self.sk
        s = sk.weapon_scale
        eye = FirstPerson().eye
        pitch, back = key.get('kick', (0.0, 0.0))
        sx, sy = key.get('sway', (0.0, 0.0))
        # 视线（SIGHT_BACK → SIGHT_FRONT）对准镜头正前方，枪的上方（-X）朝上；再绕眼睛反转 FP_TILT，抵掉游戏里多抬的那 2°。
        fwd = unit(SIGHT_FRONT - SIGHT_BACK)
        up = unit(np.array([-1.0, 0, 0]) - fwd * np.dot([-1.0, 0, 0], fwd))
        back_axis = -fwd
        right = np.cross(up, back_axis)
        R = euler_matrix((pitch, 0, 0)) @ np.column_stack([right, up, back_axis]).T
        at = np.array([sx, sy, -key['ads'] + back]) - R @ (SIGHT_BACK * s)
        untilt = rot_x(-FP_TILT)
        R, origin = untilt @ R, eye + untilt @ at
        T = self.torso(clip, key['t'])
        Ti = np.linalg.inv(T)
        return (Ti @ np.append(origin, 1))[:3], T[:3, :3].T @ R[:, 1], T[:3, :3].T @ R[:, 0]

    def pose(self, clip, t, prev=None):
        pose = super().pose(clip, t, prev)
        sk = self.sk
        s = sk.weapon_scale
        Z, I, one, none = np.zeros(3), np.eye(3), (1.0, 1.0, 1.0), (0.0, 0.0, 0.0)
        W = sk.world(pose)
        pose['Crane'] = (Z, I)
        pose['Hammer'] = (Z, rot_z(-self.scalar(clip, t, 'hammer', 0.0)))
        pose['Trigger'] = (Z, rot_z(self.scalar(clip, t, 'trigger', 0.0)))
        for b in ('MuzzleLocator', 'ForeGripLocator'):
            pose[b] = (Z, I)
        # 转轮：平时只转；换弹时跟着左手拳心（cyl_hold），压到位后回到原处。
        off = self.vec(clip, t, 'cyl_off', (0.0, 0.0, 0.0))
        hold = self.scalar(clip, t, 'cyl_hold', 0.0)
        if hold > 1e-3:
            fist = (W['LeftHand'] @ np.append(sk.pivot('LeftHand') + sk.hand_grip, 1))[:3]
            local = (np.linalg.inv(self.mesh_to_world(W)) @ np.append(fist, 1))[:3]
            off = off * (1 - hold) + (local + getattr(sk, 'hold', HOLD) - CYL) * hold
        shown = self.scalar(clip, t, 'cyl_show', 1.0) >= 0.5
        pose['Cylinder'] = (s * off, rot_y(self.scalar(clip, t, 'cyl_spin', 0.0)), one if shown else none)
        spare = self.scalar(clip, t, 'spare_show', 0.0) >= 0.5
        off, R = self.vec(clip, t, 'spare_off', (0.0, 0.0, 0.0)), euler_matrix(self.vec(clip, t, 'spare_rot', (0.0, 0.0, 0.0)))
        if clip.get('eject') is not None and t > clip['eject']:
            off, R = self.ejected(clip, t, W)
        pose['SpareCylinder'] = (s * off, R, one if spare else none)
        for i in range(6):
            pose[f'Round{i}'] = (Z, I)
            pose[f'SpareRound{i}'] = (Z, I)
        return pose

    def ejected(self, clip, t, W):
        """被顶出去的旧转轮：脱离时刻的位置、速度换到世界里，自由落体、匀速翻滚，再换回此刻的枪坐标。"""
        sk = self.sk
        s = sk.weapon_scale
        if self.eject_frame is None:
            W0 = sk.world(super().pose(clip, clip['eject']))
            self.eject_frame = self.mesh_to_world(W0)
        M0, M = self.eject_frame, self.mesh_to_world(W)
        tau = t - clip['eject']
        p = (M0 @ np.append(CYL + EJECT_V * tau, 1))[:3] + np.array([0, -0.5 * GRAVITY * s * tau * tau, 0])
        local = (np.linalg.inv(M) @ np.append(p, 1))[:3]
        R = (M[:3, :3] / s).T @ (M0[:3, :3] / s) @ euler_matrix(EJECT_SPIN * tau)
        return local - CYL, R

# ---------------------------------------------------------------- 骨架


WEAPON_SCALE = WEAPON['scale']
FP_SCALE = 0.58 * WEAPON_SCALE


def skeletons():
    dummy = (np.zeros(3), np.eye(3))  # 没有鞘
    player = player_skeleton(ASSETS / 'combat/skeletons/player_tp.json', weapon_scale=1.0, carry=dummy)
    fp = player_skeleton(ASSETS / 'combat/skeletons/player_fp.json', weapon_scale=FP_SCALE, carry=dummy)
    # 第一人称根骨与长枪相同：枪端得低，手的目标统一抬高、前移，让枪身留在画面里。
    # 第一人称：枪按镜头摆（每帧的 fpc），根骨只决定手臂从哪伸进来：肩在镜头右下前方，前臂从画面下沿伸进来。
    fp.root = ((6.0, -4.0, -4.0), (0, 0, 6))
    fp.reach = np.array([-4.0, 5.0, -2.0])
    player.body = ((-4.4, 12.0, -2.5), (4.4, 21.0, 2.5))
    for sk in (player, fp):
        sk.grips, sk.slide = GRIPS, SLIDE
        add_bow(sk, WEAPON)
    player.tag, fp.tag = 'player', 'fp'
    fp.hold = HOLD_FP
    return player, fp

# ---------------------------------------------------------------- 预览


def weapon_quads(sk, W, tex):
    s = sk.weapon_scale
    base = translate(sk.weapon_pivot) @ np.diag([s, s, s, 1.0])
    quads = []
    for c in WEAPON['cubes']:
        quads += box(W[c['bone']] @ base, c['start'], c['end'], c['pivot'], c['rotation'], uv=c['uv'], tex=tex)
    return quads


def mesh_world(sk, W):
    s = sk.weapon_scale
    return W['Weapon'] @ translate(sk.weapon_pivot) @ np.diag([s, s, s, 1.0])


def in_game_fp(W):
    """预览用：骨架世界坐标换成游戏里第一人称看到的样子（绕眼睛多抬 FP_TILT，同 LongswordClient.hand）。"""
    eye = FirstPerson().eye
    M = translate(eye) @ FP_TILT_M @ translate(-eye)
    return {k: M @ v for k, v in W.items()}


def sight_check(fp, pose):
    """第一人称瞄准时照门缺口被挡住多少：比照门近、又冒进缺口里的方块（在 1280×720 上按像素算，每个面取 9×9 个点）。
    返回 {方块名: 挡住的像素}、照门和准星在屏幕上的位置、缺口深度（像素）。"""
    W = in_game_fp(fp.world(pose))
    cam = FirstPerson(size=(1280, 720))
    M = mesh_world(fp, W)
    rear = cam.project((M @ np.append(SIGHT_BACK, 1))[:3])
    notch = cam.project((M @ np.append(NOTCH, 1))[:3])
    front = cam.project((M @ np.append(SIGHT_FRONT, 1))[:3])
    half = max(abs(c['end'][2]) for c in WEAPON['cubes'] if c['name'].startswith('rear_sight')) * fp.weapon_scale * cam.f / rear[2]
    s = fp.weapon_scale
    base = translate(fp.weapon_pivot) @ np.diag([s, s, s, 1.0])
    grid = np.linspace(0, 1, 9)
    covered = {}
    for c in WEAPON['cubes']:
        if c['name'].startswith(('rear_sight', 'front_sight')) or c['bone'].startswith(('Round', 'Spare')):
            continue
        top = np.inf
        for q in box(W[c['bone']] @ base, c['start'], c['end'], c['pivot'], c['rotation']):
            O, U, V = q[0], q[1], q[2]
            for a in grid:
                for b in grid:
                    h = cam.project(O + a * U + b * V)
                    if h is not None and h[2] < rear[2] and abs(h[0] - rear[0]) < half:
                        top = min(top, h[1])
        if top < notch[1] - 0.25:
            covered[c['name']] = notch[1] - top
    return covered, rear, front, notch[1] - rear[1]


def frames_for(clip, n):
    times = [clip['length'] * i / (n - 1) for i in range(n)]
    for t, name in marker_list(clip.get('markers')):
        if name in ('contact', 'fire', 'feed') and len(times) < n + 4:
            times.append(t)
    return sorted(set(round(t, 3) for t in times))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    out = Path(args[0])
    only = set(args[1:])
    gif = '--gif' in sys.argv
    out.mkdir(parents=True, exist_ok=True)
    player, fp = skeletons()
    tex = np.asarray(Image.open(ASSETS / 'textures/item/gunaxe_rig.png').convert('RGBA')).astype(np.float32)
    all_clips = clips()
    baked = {}
    for who, sk in (('player', player), ('fp', fp)):
        baker = GunBaker(sk)
        for clip in all_clips:
            baked[(who, clip['name'])] = baker.bake(clip, 60)
        worst = {}
        for clip_name, t, side, over in baker.warnings:
            lo, hi, m = worst.get((clip_name, side), (t, t, 0))
            worst[(clip_name, side)] = (min(lo, t), max(hi, t), max(m, over))
        for (clip_name, side), (lo, hi, m) in sorted(worst.items()):
            print(f'{who} {clip_name} {side} reach short {lo:.2f}-{hi:.2f}s max {m:.2f} arm')
        if who == 'player':
            by = {}
            for clip_name, t, side, r in baker.residuals:
                by.setdefault((clip_name, side), []).append(r)
            for (clip_name, side), rs in sorted(by.items()):
                if max(rs) > 40:
                    print(f'{who} {clip_name} {side} grip offset avg {sum(rs) / len(rs):.0f} max {max(rs):.0f}')
        if who == 'player':
            for clip in all_clips:
                low = min((mesh_world(sk, sk.world({n: (v[0], euler_matrix(v[1])) + ((v[2],) if len(v) > 2 else ())
                                                    for n, v in e.items()})) @ TIP)[1] for _, e in baked[(who, clip['name'])])
                if low < 1.0:
                    print(f'{clip["name"]} blade tip goes down to y={low:.1f} (ground 0)')
    _, aim_pose = baked[('fp', 'aim')][0]
    covered, rear, front, depth = sight_check(fp, {n: (v[0], euler_matrix(v[1])) + ((v[2],) if len(v) > 2 else ())
                                                    for n, v in aim_pose.items()})
    print(f"aim sights on 1280x720: rear ({rear[0]:.1f}, {rear[1]:.1f}) front ({front[0]:.1f}, {front[1]:.1f}), notch {depth:.1f} px deep"
          + ''.join(f"; {n} covers {v:.1f} px" for n, v in covered.items()))
    if any(v > depth / 2 for v in covered.values()):
        print('WARNING: rear notch more than half hidden; raise the rear sight in the bbmodel')
    gun_bones = [b['name'] for b in WEAPON['bones']]
    bones = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
             'LeftArm', 'LeftForeArm', 'LeftHand', 'RightLeg', 'LeftLeg', 'Weapon', *gun_bones]
    for who, sk, view in (('player', player, 'tp'), ('fp', fp, 'fp')):
        data = bedrock(all_clips, {c['name']: baked[(who, c['name'])] for c in all_clips}, sk,
                       bones + (['Root'] if view == 'fp' else []))
        text = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        (ASSETS / f'combat/animations/gunaxe_{view}.animation.json').write_text(text, encoding='utf-8')

    cams = [Camera(-35, 8, 4.6, (0, 18, -8), (300, 300)), Camera(80, 6, 4.6, (0, 18, -8), (300, 300))]
    close = Camera(-40, 18, 11.0, (-3, 18, -7), (300, 300))   # 换弹特写：从左前上方看胸前
    fp_cam = FirstPerson()

    def posed(who, clip, t):
        frames = baked[(who, clip['name'])]
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
        return i, {n: (v[0], euler_matrix(v[1])) + ((v[2],) if len(v) > 2 else ()) for n, v in frames[i][1].items()}

    def draw(clip, t, cam, trail_from):
        i, pose = posed('player', clip, t)
        W = player.world(pose)
        quads = weapon_quads(player, W, tex) + mannequin_quads(W)
        trail = []
        frames = baked[('player', clip['name'])]
        for j in range(max(0, i - trail_from), i + 1):
            pj = {n: (v[0], euler_matrix(v[1])) + ((v[2],) if len(v) > 2 else ()) for n, v in frames[j][1].items()}
            trail.append((mesh_world(player, player.world(pj)) @ TIP)[:3])
        return render(quads, cam, trail=trail if len(trail) > 1 else None)

    def draw_fp(clip, t):
        _, pose = posed('fp', clip, t)
        W = in_game_fp(fp.world(pose))
        quads = weapon_quads(fp, W, tex)
        for bone, boxes in FP_ARMS.items():
            for start, end, color in boxes:
                quads += box(W[bone], start, end, color=color)
        im = render_fp(quads, fp_cam)
        # 准星
        from PIL import ImageDraw
        d = ImageDraw.Draw(im)
        cx, cy = im.width // 2, im.height // 2
        d.line([(cx - 4, cy), (cx + 4, cy)], fill=(255, 255, 255)); d.line([(cx, cy - 4), (cx, cy + 4)], fill=(255, 255, 255))
        return im

    for clip in all_clips:
        if only and clip['name'] not in only:
            continue
        times = frames_for(clip, 9 if clip['length'] > 1 else 7)
        rows = []
        for v, cam in enumerate(cams):
            panels = []
            for t in times:
                tag = ','.join(n for mt, n in marker_list(clip.get('markers')) if abs(mt - t) < 1e-3)
                panels.append((f'{t:.2f}s {tag}', draw(clip, t, cam, 15)))
            rows.append((('左前' if v == 0 else '右侧'), panels))
        if clip['name'] == 'reload':
            rows.append(('特写', [(f'{t:.2f}s', draw(clip, t, close, 0)) for t in times]))
        sheet(rows, f"{clip['name']}  {clip['length']}s", out / f"{clip['name']}.png")
        sheet([('第一人称', [(f'{t:.2f}s', draw_fp(clip, t)) for t in times])], f"{clip['name']} 第一人称",
              out / f"{clip['name']}_fp.png")
        if gif:
            images = []
            for i in range(int(round(clip['length'] * 30)) + 1):
                t = i / 30
                a = draw(clip, t, cams[0], 10)
                b = draw(clip, t, cams[1], 10)
                c = draw_fp(clip, t).resize((300 * 427 // 240, 300))
                im = Image.new('RGB', (a.width * 2 + c.width, a.height)); im.paste(a, (0, 0)); im.paste(b, (a.width, 0))
                im.paste(c, (a.width * 2, 0))
                images.append(im)
            images[0].save(out / f"{clip['name']}.gif", save_all=True, append_images=images[1:], duration=33, loop=0)
        print('rendered', clip['name'])


if __name__ == '__main__':
    main()
