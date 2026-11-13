# -*- coding: utf-8 -*-
"""神狐薙刀的动作编排，以及烘焙、导出与预览。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/naginata_moves.py <Minecraft MOD 目录> <输出目录> [动作名…] [--gif] [--fox]

编排坐标见 combat_author.py 开头：武器与手以两肩中点为原点、臂长为单位；前 -Z、上 +Y、角色右手 +X。
blade 是刃尖方向，edge 是刃口方向（网格 +X），grip 是右手的位置；rpos / lpos 是手握在杆上的位置（网格单位：
-10.4 石突、0 主握、5.5 前握、16 杆顶、24.5 刃尖）。攻击的关键帧都写在髋部坐标里（space='hips'），躯干怎么拧都不影响刀路。

第二版（2026-11-08，维护者看过第一版后：太花哨、转圈不好看）按传统薙刀术的照片重做：
- 平时右手单手把杆立在身体右侧，出招时左手才搭上来，收招后放开。
- 挥砍只用一种握法：左手在前靠刃、右手在后靠石突，两手拉开。刃靠杠杆走大弧：后手往回拉、前手往前压。
- 没有转圈、没有换手，每一刀都是一条从举起到落下的大弧。
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

from combat_author import Baker, bbmodel_skeleton, bedrock, euler_matrix, frame, hermite, channel, marker_list, npc_parried, player_skeleton, slerp, unit
from combat_render import Camera, Geo, mannequin_quads, render, sheet, weapon_quads

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
TIP = np.array([-1.12, 24.45, 0, 1])
BUTT = np.array([0, -10.445, 0, 1])
# 握点（网格单位）：单手立持握主握点；挥砍时右手滑到后面靠石突，左手搭在前面。
MAIN, REAR, FRONT = 0.0, -7.0, 3.0
# 重心附近：拔刀、收刀时单手横持握这里，刃套才够得着。
BALANCE = 6.5
# 刃套中心（网格单位）：左手拿这里；套在刃上时刃套网格与武器网格重合。
COVER = np.array([-0.5, 20.6, 0.0])
# 刃套全部退出刃所要滑过的距离。
COVER_OFF = 9.0

# ---------------------------------------------------------------- 工具


def k(t, base=None, **kw):
    key = dict(base or {})
    key.update(kw)
    key['t'] = t
    return key


def lerp(a, b, w):
    return np.asarray(a, float) + (np.asarray(b, float) - np.asarray(a, float)) * w


def smooth(u):
    return u * u * (3 - 2 * u)


def stroke(u):
    """一刀的节奏：起手慢、中段最快、收得更快。"""
    return smooth(u ** 1.2)


def along_dirs(dirs, u):
    """依次经过 dirs 的方向，u 从 0 到 1，每段走大圆。"""
    n = len(dirs) - 1
    x = min(n - 1e-9, max(0.0, u) * n)
    i = int(x)
    w = x - i
    a, b = unit(dirs[i]), unit(dirs[i + 1])
    th = math.acos(float(np.clip(np.dot(a, b), -1, 1)))
    if th < 1e-6:
        return a
    return (math.sin((1 - w) * th) * a + math.sin(w * th) * b) / math.sin(th)


def along_points(points, u):
    """依次经过 points 的 Catmull-Rom 曲线，u 从 0 到 1。"""
    p = [np.asarray(v, float) for v in points]
    if len(p) == 2:
        return lerp(p[0], p[1], u)
    n = len(p) - 1
    x = min(n - 1e-9, max(0.0, u) * n)
    i = int(x)
    w = x - i
    p0, p1, p2, p3 = p[max(0, i - 1)], p[i], p[i + 1], p[min(n, i + 2)]
    return 0.5 * ((2 * p1) + (-p0 + p2) * w + (2 * p0 - 5 * p1 + 4 * p2 - p3) * w * w + (-p0 + 3 * p1 - 3 * p2 + p3) * w ** 3)


def swing(t0, t1, dirs, hands, ups, n=8, s=stroke, lead=1, **extra):
    """一段挥动：刃依次指向 dirs，右手依次经过 hands（髋部坐标），躯干依次是 ups（欧拉角）。
    刃口朝运动方向（lead=1，砍下去）或背向运动方向（lead=-1，举起来时刀背在前），所以举刀和砍下去刃口朝向一致。"""
    keys = []
    for i in range(n + 1):
        u = i / n
        w = s(u)
        d = along_dirs(dirs, w)
        move = along_dirs(dirs, min(1.0, w + 0.03)) - along_dirs(dirs, max(0.0, w - 0.03))
        move = move - d * np.dot(move, d)
        key = dict(extra, t=round(t0 + (t1 - t0) * u, 4), space='hips', grip=tuple(along_points(hands, w)), blade=tuple(d),
                   up=tuple(along_points(ups, w)))
        if np.linalg.norm(move) > 1e-6:
            key['edge'] = tuple(lead * unit(move))
        keys.append(key)
    # 起止两帧的运动方向取不准时，沿用相邻帧的刃口。
    for i, key in enumerate(keys):
        if 'edge' not in key:
            key['edge'] = keys[i + 1 if i == 0 else i - 1].get('edge', (0, 0, -1))
    return keys


def contact_time(t0, t1, dirs, target, s=stroke):
    """这一刀在 [t0, t1] 里刃最接近 target 方向的时刻（取两位小数），用作接触时刻。"""
    best = max((float(np.dot(along_dirs(dirs, s(i / 400)), unit(target))), i) for i in range(401))
    return round(t0 + (t1 - t0) * best[1] / 400, 2)


def fp_hand(g):
    """第一人称的右手：整体往前、往下收，举到脸边的手不贴镜头，刀路仍在画面中间。"""
    return (0.85 * g[0], 0.75 * g[1] - 0.10, 0.55 * g[2] - 0.50)


# ---------------------------------------------------------------- 常用姿势

HANG_L = (-0.42, -0.98, 0.04)
# 待机：立持。右手在胸前单手握杆，杆竖在身体右侧，石突近膝、刃高出头顶，刃口朝前；左手自然垂下。
IDLE = dict(space='hips', grip=(0.50, -0.56, -0.18), blade=(-0.05, 1.0, -0.10), edge=(0.1, 0.0, -1.0),
            rpos=MAIN, rmode='sword', raxial=False, lmode='free', lhand=HANG_L, up=(0, -6, 0), hips=(0, -0.02, 0),
            rfoot=(0.16, 0, 0.06, -15), lfoot=(-0.14, 0, -0.08, 8),
            # 第一人称：杆竖在画面右侧，刃面朝镜头。
            fp=dict(grip=(0.42, -0.46, -0.70), blade=(-0.10, 0.97, -0.22), edge=(-1, 0, 0)))
IDLE_B = dict(IDLE, grip=(0.50, -0.54, -0.18), blade=(-0.06, 1.0, -0.08), up=(0, -5, 0), hips=(0, -0.01, 0),
              fp=dict(grip=(0.42, -0.45, -0.70), blade=(-0.11, 0.97, -0.21), edge=(-1, 0, 0)))
# 双手握（挥砍）：左手在前靠刃。
TWO = dict(lmode='two', lpos=FRONT, blend=0.12)
# 格挡：中段受け。杆斜架在身前，两手拉开，石突在右下、刃在左上，刃口朝外。
GUARD = dict(space='hips', grip=(0.40, -0.52, -0.62), blade=(-0.72, 0.60, -0.34), edge=(-0.25, 0.22, -0.94),
             rpos=-1.0, lmode='two', lpos=9.0, rmode='sword', raxial=False, up=(2, -6, 0), hips=(0, -0.06, 0),
             rfoot=(0.22, 0, 0.18, -15), lfoot=(-0.2, 0, -0.2, 10))
# 拔刀、收刀：单手把杆横到胸前、刃朝左前，左手够得着刃套。
ACROSS = dict(space='hips', grip=(0.40, -0.38, -0.52), blade=(-0.90, 0.30, -0.30), edge=(0, 0.4, -1.0), rpos=BALANCE)
PULLED = dict(ACROSS, grip=(0.78, -0.34, -0.40))

# 四刀的关键姿势（右手位置、刃的方向、躯干）。
W1 = dict(R=(-0.08, -0.58, -0.52), d=(0.45, 0.80, 0.36), up=(4, -24, -2))      # 袈裟：刃举到右肩后
K1 = dict(R=(0.36, 0.14, -0.16), d=(-0.58, -0.55, -0.60), up=(-10, 24, 3))     # 袈裟：斩到左前下方，右手收到右耳边
K2 = dict(R=(-0.05, -0.55, -0.42), d=(0.55, 0.80, -0.20), up=(4, -30, -2))     # 逆袈裟：撩到右上
W3 = dict(R=(0.35, -0.45, -0.25), d=(-0.85, -0.10, 0.50), up=(0, 40, 2))        # 脛払い：刃从头顶送到左后方
K3 = dict(R=(0.40, -0.40, -0.25), d=(0.70, -0.38, -0.60), up=(-16, -40, -2))   # 脛払い：贴地扫到右前方
W4 = dict(R=(0.08, -0.28, -0.38), d=(0.0, 0.60, 0.80), up=(10, -4, 0))          # 面：举过头顶，刃在脑后
K4 = dict(R=(0.34, 0.06, -0.08), d=(0.0, -0.32, -0.95), up=(-16, -6, 0))       # 面：劈到正前方，刃略向下

# ---------------------------------------------------------------- 动作


def back_to_idle(t0, t1, start, via_dir, via_hand):
    """收招：左手放开，刃从收势经过 via 回到立持，右手滑回主握点。"""
    keys = [k(t0 + 0.06, lmode='free', lhand=HANG_L, blend=0.16)]
    keys += swing(t0, t1, [start['d'], via_dir, IDLE['blade']], [start['R'], via_hand, IDLE['grip']], [start['up'], IDLE['up']],
                  n=6, s=smooth, lead=-1)[1:-1]
    # 第一人称立持的刃口朝镜头，和挥动时差得多：收招的每一帧把刃口逐步转过去（经过朝下），不在最后一帧里翻。
    target = np.asarray(IDLE['fp']['edge'], float)
    for i, key in enumerate(keys[1:], 1):
        w = smooth(i / (len(keys) - 1 + 1))
        key['fp'] = dict(key.get('fp', {}), edge=tuple(along_dirs([key['edge'], (0.0, -1.0, 0.0), target], w)))
    keys.append(k(t1, IDLE, ease='hold'))
    return keys


def clips():
    out = []
    markers = {}

    out.append(dict(name='idle', length=2.4, loop='loop', keys=[k(0.0, IDLE), k(1.2, IDLE_B), k(2.4, IDLE)]))

    # 拔刀：立持（刃套套着）→ 单手把杆横到胸前，左手握住刃套 → 右手往右抽、刃套留在左手里退下来 →
    # 左手把刃套插到左腰，右手把杆立回去。
    out.append(dict(name='draw', length=1.2, loop='once', keys=[
        k(0.0, IDLE, cover=0.0, cover_w=0.0),
        k(0.26, ACROSS, cover=0.0, cover_w=0.0, up=(0, 6, 0)),
        k(0.28, lmode='saya', blend=0.12),
        k(0.40, ACROSS, cover=0.0, cover_w=0.0, up=(0, 6, 0), ease='hold'),
        k(0.62, PULLED, cover=COVER_OFF, cover_w=0.0, up=(0, -4, 0), ease='hold'),
        k(0.84, IDLE, grip=(0.52, -0.50, -0.24), blade=(-0.2, 0.96, -0.2), rpos=MAIN, cover=COVER_OFF, cover_w=1.0, lmode='saya'),
        k(0.88, lmode='free', lhand=HANG_L, blend=0.2),
        k(1.2, IDLE, cover=COVER_OFF, cover_w=1.0, lmode='free'),
    ]))

    # 第一刀 袈裟斬り：左手搭上来，刃举到右肩后，杠杆一压，从右上斩到左前下方。
    cut = [W1['d'], (0.25, 0.95, -0.15), (-0.30, 0.15, -0.94), K1['d']]
    hands = [W1['R'], (0.15, -0.30, -0.45), (0.30, 0.00, -0.30), K1['R']]
    kesa = [k(0.0, IDLE), k(0.03, TWO)]
    kesa += swing(0.0, 0.17, [IDLE['blade'], W1['d']], [IDLE['grip'], W1['R']], [IDLE['up'], W1['up']], n=4, s=smooth, lead=-1,
                  rpos=REAR)[1:]
    kesa[-1]['ease'] = 'hold'
    kesa += swing(0.22, 0.42, cut, hands, [W1['up'], (0, -8, 0), (-6, 12, 2), K1['up']])[1:]
    kesa[-1]['ease'] = 'hold'
    markers['kesa_1'] = contact_time(0.22, 0.42, cut, (0, 0, -1))
    kesa.append(dict(kesa[-1], t=0.50, ease='hold'))
    kesa += back_to_idle(0.52, 0.82, K1, (-0.2, 0.6, -0.75), (0.45, -0.30, -0.35))
    out.append(dict(name='kesa_1', length=0.82, loop='once', markers={'input_open': 0.08, 'contact': markers['kesa_1'], 'cancel': 0.5},
                    keys=kesa))

    # 第二刀 逆袈裟：刃在左下翻过来，后手往下压、前手往上挑，从左下撩到右上。
    turn = (-0.66, -0.50, -0.56)
    rise = [turn, (-0.20, -0.05, -0.98), (0.45, 0.60, -0.66), K2['d']]
    kaeshi = [k(0.0, space='hips', grip=K1['R'], blade=K1['d'], edge=swing(0, 1, [W1['d'], K1['d']], [K1['R'], K1['R']], [K1['up'], K1['up']])[-1]['edge'],
                rpos=REAR, lmode='two', lpos=FRONT, up=K1['up'], hips=(0, -0.05, 0))]
    # 刃在左下转过来：刃口从朝下转成朝上（杆在手里转半圈）。
    kaeshi.append(k(0.08, space='hips', grip=(0.30, 0.02, -0.25), blade=turn, edge=tuple(unit(np.cross(turn, (0, 1, 0)))), up=(-8, 22, 3)))
    kaeshi += swing(0.10, 0.30, rise, [(0.30, 0.02, -0.25), (0.10, -0.35, -0.50), (-0.02, -0.50, -0.48), K2['R']],
                    [(-8, 22, 3), (-4, 4, 0), (2, -18, -2), K2['up']])
    kaeshi[-1]['ease'] = 'hold'
    markers['kaeshi_2'] = contact_time(0.10, 0.30, rise, (0, 0, -1))
    kaeshi.append(dict(kaeshi[-1], t=0.40, ease='hold'))
    kaeshi += back_to_idle(0.44, 0.74, K2, (0.2, 0.95, -0.2), (0.3, -0.45, -0.3))
    out.append(dict(name='kaeshi_2', length=0.74, loop='once', markers={'input_open': 0.08, 'contact': markers['kaeshi_2'], 'cancel': 0.44},
                    keys=kaeshi))

    # 第三刀 脛払い：刃从右上举过头顶送到左后方，压低，贴着地面从左扫到右（打小腿）。
    over = [K2['d'], (0.0, 1.0, -0.05), (-0.60, 0.60, 0.50), W3['d']]
    low = [W3['d'], (-0.75, -0.35, -0.55), (0.0, -0.42, -0.90), K3['d']]
    sune = [k(0.0, space='hips', grip=K2['R'], blade=K2['d'], edge=(0.2, -0.3, -0.95), rpos=REAR, lmode='two', lpos=FRONT, up=K2['up'],
              hips=(0, -0.05, 0))]
    sune[0]['edge'] = None
    sune += swing(0.0, 0.22, over, [K2['R'], (0.15, -0.20, -0.40), (0.30, -0.30, -0.30), W3['R']], [K2['up'], (0, 0, 0), (2, 30, 2), W3['up']],
                  n=6, s=smooth, lead=-1)[1:]
    sune[-1]['ease'] = 'hold'
    sune[0]['edge'] = sune[1]['edge']
    sune += swing(0.25, 0.46, low, [W3['R'], (0.30, -0.45, -0.40), (0.22, -0.42, -0.42), K3['R']], [W3['up'], (-10, 20, 2), (-16, -6, 0), K3['up']])[1:]
    sune[-1]['ease'] = 'hold'
    markers['sune_3'] = contact_time(0.25, 0.46, low, (0, -0.42, -0.9))
    sune.append(dict(sune[-1], t=0.54, ease='hold', hips=(0, -0.1, 0)))
    sune += back_to_idle(0.56, 0.84, K3, (0.6, 0.6, -0.5), (0.5, -0.4, -0.3))
    out.append(dict(name='sune_3', length=0.84, loop='once', markers={'input_open': 0.08, 'contact': markers['sune_3'], 'cancel': 0.54},
                    keys=sune))

    # 第四刀 面：刃从右前下方举过头顶，在脑后停一拍，双手一刀劈到正前方，停住（残心）。
    raise_ = [K3['d'], (0.60, 0.30, -0.40), (0.10, 0.90, -0.10), W4['d']]
    chop = [W4['d'], (0.0, 1.0, 0.0), (0.0, 0.30, -0.95), K4['d']]
    men = [k(0.0, space='hips', grip=K3['R'], blade=K3['d'], edge=(0.6, 0.3, -0.5), rpos=REAR, lmode='two', lpos=FRONT, up=K3['up'],
             hips=(0, -0.08, 0))]
    men[0]['edge'] = None
    men += swing(0.0, 0.28, raise_, [K3['R'], (0.30, -0.20, -0.40), (0.15, -0.20, -0.30), W4['R']], [K3['up'], (-4, -20, 0), (6, -6, 0), W4['up']],
                 n=7, s=smooth, lead=-1)[1:]
    men[-1]['ease'] = 'hold'
    men[0]['edge'] = men[1]['edge']
    men.append(dict(men[-1], t=0.36, ease='hold'))
    men += swing(0.36, 0.52, chop, [W4['R'], (0.15, -0.10, -0.25), (0.30, 0.05, -0.15), K4['R']], [W4['up'], (8, -4, 0), (-8, -6, 0), K4['up']])[1:]
    men[-1]['ease'] = 'hold'
    markers['men_4'] = contact_time(0.36, 0.52, chop, (0, 0, -1))
    men.append(dict(men[-1], t=0.78, ease='hold', hips=(0, -0.08, 0)))
    men += back_to_idle(0.80, 1.10, K4, (0.1, 0.7, -0.7), (0.45, -0.30, -0.40))
    out.append(dict(name='men_4', length=1.1, loop='once', markers={'input_open': 0.1, 'contact': markers['men_4'], 'cancel': 0.8}, keys=men))

    # 格挡：立即双手架起；被击中时杆被压回身前一下，再撑回去。
    out.append(dict(name='guard', length=1.6, loop='loop', keys=[
        k(0.0, GUARD),
        k(0.8, GUARD, grip=(0.40, -0.50, -0.63), blade=(-0.71, 0.61, -0.34), up=(2, -5, 0), hips=(0, -0.05, 0)),
        k(1.6, GUARD),
    ]))
    out.append(dict(name='guard_hit', length=0.34, loop='once', keys=[
        k(0.0, GUARD),
        k(0.05, GUARD, grip=(0.38, -0.47, -0.42), blade=(-0.76, 0.56, -0.22), up=(10, 2, -2), hips=(0, -0.08, 0), ease='hold'),
        k(0.34, GUARD),
    ]))

    # 被弹反（招式文件的保留招式，见 COMBAT_SKELETON）：砍出去的刃被猛地磕向右后上方，左手脱开甩到身后，身子后仰、
    # 胸口敞开；顶住一下露出破绽，再收回单手立持。NPC 版由 npc_parried 拉长中间顶住的一段。
    peak = dict(space='hips', grip=(0.62, 0.12, -0.10), blade=(0.45, 0.80, 0.40), edge=(0.75, -0.55, -0.35), up=(14, -24, 0), chest=(4, -10, 0),
                hips=(0, -0.05, 0.08),
                fp=dict(grip=(0.50, 0.0, -0.46), blade=(0.5, 0.82, -0.28), edge=(0.8, -0.56, 0.2), up=(6, -10, 0), chest=(0, 0, 0)))
    out.append(dict(name='parried', length=0.5, loop='once', keys=[
        k(0.0, space='hips', grip=(0.25, -0.20, -0.60), blade=(0.0, 0.30, -0.95), edge=(0, -0.95, -0.3), rpos=REAR, lmode='two', lpos=FRONT,
          up=(0, -6, 0)),
        k(0.02, lmode='free', lhand=(-0.68, -0.52, 0.16), blend=0.06),
        k(0.07, peak, ease='hold', rfoot=(0.16, 0, 0.0, -10), lfoot=(-0.2, 0, 0.3, 25)),
        k(0.32, peak, grip=(0.60, 0.04, -0.14), blade=(0.40, 0.78, 0.48), up=(11, -22, 0), ease='hold',
          fp=dict(peak['fp'], grip=(0.48, -0.08, -0.46))),
        k(0.36, lmode='free', lhand=HANG_L, blend=0.14),
        k(0.42, space='hips', grip=(0.55, -0.26, -0.16), blade=(0.18, 0.89, 0.19), edge=(0.43, -0.28, -0.68), rpos=MAIN,
          fp=dict(grip=(0.45, -0.27, -0.58), blade=(0.2, 0.9, -0.25), edge=(-0.1, -0.25, -0.95))),
        k(0.5, IDLE),
    ]))
    out.append(npc_parried(out[-1]))

    # 收刀：拔刀倒过来。单手把杆横到胸前，左手从左腰取回刃套，从刃尖套回去，杆立回身体右侧。
    out.append(dict(name='sheathe', length=1.3, loop='once', keys=[
        k(0.0, IDLE, cover=COVER_OFF, cover_w=1.0),
        k(0.10, lmode='saya', blend=0.16),
        k(0.30, PULLED, cover=COVER_OFF, cover_w=1.0, up=(0, -4, 0)),
        k(0.50, PULLED, cover=COVER_OFF, cover_w=0.0, up=(0, -4, 0), ease='hold'),
        k(0.72, ACROSS, cover=0.0, cover_w=0.0, up=(0, 6, 0), ease='hold'),
        k(0.80, lmode='free', lhand=HANG_L, blend=0.16),
        k(1.06, IDLE, cover=0.0, cover_w=0.0),
        k(1.3, IDLE, cover=0.0, cover_w=0.0),
    ]))

    for clip in out:
        clip.setdefault('stance', {'rfoot': IDLE['rfoot'], 'lfoot': IDLE['lfoot']})
        for key in clip['keys']:
            # 第一人称：没写自己的位置时，右手按 fp_hand 收进画面。
            if key.get('space') == 'hips' and 'grip' in key and 'grip' not in key.get('fp', {}):
                key['fp'] = dict(key.get('fp', {}), grip=fp_hand(key['grip']))
    return out

# ---------------------------------------------------------------- 烘焙


class NaginataBaker(Baker):
    """刃套跟着编排走：cover_w = 0 时套在刃上（cover 是沿刃退出的距离），1 时挂在腰上，中间由左手带着过去。
    右手的握点够不着时沿“肩—握点”连线收回来，武器跟着收，手不离杆（第一人称的 reach、神狐的短手臂都会够不着）。"""

    def sword_key(self, clip, key):
        grip, blade, edge = super().sword_key(clip, key)
        sk = self.sk
        S = sk.pivot('RightArm')
        reach = 0.95 * (np.linalg.norm(sk.pivot('RightForeArm') - S) + np.linalg.norm(sk.pivot('RightHand') - sk.pivot('RightForeArm')) + abs(sk.hand_grip[1]))
        d = grip - S
        if np.linalg.norm(d) > reach:
            grip = S + d / np.linalg.norm(d) * reach
        return grip, blade, edge

    def sheath(self, clip, t):
        hip_o, hip_R = self.sk.carry
        keys = [key for key in clip['keys'] if 'cover_w' in key]
        if not keys:
            return hip_o, hip_R
        s = self.sk.weapon_scale
        w = float(np.atleast_1d(hermite(channel(clip, 'cover_w', 1.0), t))[0])
        w = max(0.0, min(1.0, w))
        slide = float(np.atleast_1d(hermite(channel(clip, 'cover', 0.0), t))[0])
        o_w, R_w = self.sword(clip, t)
        center = COVER * s
        on = o_w + R_w @ (center + np.array([0, slide * s, 0]))
        hip = hip_o + hip_R @ center
        R = slerp(R_w, hip_R, w)
        return on + (hip - on) * w - R @ center, R


# ---------------------------------------------------------------- 骨架

# 网格的 scale：骨架的武器缩放乘上它。第三人称 0.8 × 1.25 = 1，第一人称 0.58 × 1.25。
WEAPON_SCALE = 1.25
FP_SCALE = 0.58 * WEAPON_SCALE
# 神狐：薙刀与身高的比例和玩家相近（美术静态持握是 2 倍，转圈时会入地）。
FOX_SCALE = 1.7


def hip_carry(center, scale):
    """刃套挂在左腰后：刃尖朝下朝后，给出刃套中心的位置（躯干坐标、模型单位）。"""
    R = frame((-0.08, -0.86, 0.5), (0.9, -0.2, 0.3))
    return np.asarray(center, float) - R @ (COVER * scale), R


def skeletons(art):
    player = player_skeleton(ASSETS / 'combat/skeletons/player_tp.json', weapon_scale=1.0, carry=hip_carry((-4.9, 13.5, 1.6), 1.0))
    fp = player_skeleton(ASSETS / 'combat/skeletons/player_fp.json', weapon_scale=FP_SCALE, carry=hip_carry((-4.9, 2.0, 1.6), FP_SCALE))
    fp.root = ((15.13, -1.82, 0), (0, 0, 12))
    # 比长枪再抬高一点：横扫贴着准星下方过。
    fp.reach = np.array([-3.0, 5.0, -5.0])
    fox = bbmodel_skeleton(art / 'MinecraftLogosMod/fox_model/divine_fox/body/divine_fox_body.bbmodel',
                           hand_grip=(0, -2.29, -0.3), weapon_scale=FOX_SCALE, carry=hip_carry((-5.0, 27.0, 2.0), FOX_SCALE))
    player.body = ((-4.4, 12.0, -2.5), (4.4, 21.0, 2.5))
    fox.body = ((-3.4, 24.0, -2.6), (3.4, 32.5, 2.6))
    for sk in (player, fp, fox):
        sk.grips = (MAIN, FRONT)
        sk.saya_grip = float(COVER[1])
        sk.two_slides = True
    player.tag, fp.tag, fox.tag = 'player', 'fp', 'fox'
    return player, fp, fox

# ---------------------------------------------------------------- 预览


def mesh(skeleton, W, bone='Weapon'):
    s = skeleton.weapon_scale
    T = np.eye(4); T[:3, 3] = skeleton.weapon_pivot
    return W[bone] @ T @ np.diag([s, s, s, 1.0])


def frames_for(clip, n):
    times = [clip['length'] * i / (n - 1) for i in range(n)]
    for t, name in marker_list(clip.get('markers')):
        if name in ('contact', 'cancel') and len(times) < n + 4:
            times.append(t)
    return sorted(set(round(t, 3) for t in times))


def report(who, baker, frames, sk):
    worst = {}
    for clip_name, t, side, over in baker.warnings:
        key = (clip_name, side)
        lo, hi, m = worst.get(key, (t, t, 0))
        worst[key] = (min(lo, t), max(hi, t), max(m, over))
    for (clip_name, side), (lo, hi, m) in sorted(worst.items()):
        print(f'{who} {clip_name} {side} 手够不着 {lo:.2f}-{hi:.2f}s，最多差 {m:.2f} 臂长')
    if who == 'fp':
        return
    by = {}
    for clip_name, t, side, r in baker.residuals:
        by.setdefault((clip_name, side), []).append(r)
    for (clip_name, side), rs in sorted(by.items()):
        print(f'{who} {clip_name} {side} 杆偏角 平均 {sum(rs) / len(rs):.0f}° 最大 {max(rs):.0f}°')
    # 武器最低点（模型单位，脚底 0）：刃尖和石突都不能入地。
    for clip_name, fs in frames.items():
        low = (1e9, 0, '')
        for t, eulers in fs:
            pose = {n: (p[0], euler_matrix(p[1])) for n, p in eulers.items()}
            M = mesh(sk, sk.world(pose))
            for name, point in (('刃尖', TIP), ('石突', BUTT)):
                y = float((M @ point)[1])
                if y < low[0]:
                    low = (y, t, name)
        print(f'{who} {clip_name} 最低点 {low[0]:.1f} 单位（{low[2]}，{low[1]:.2f}s）')


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    art, out = Path(args[0]), Path(args[1])
    only = set(args[2:])
    gif, with_fox = '--gif' in sys.argv, '--fox' in sys.argv
    out.mkdir(parents=True, exist_ok=True)
    player, fp, fox = skeletons(art)
    source = art / 'art/fox_naginata/v01'
    weapon = json.load(open(source / 'exports/fox_naginata_standard_wood.mesh.json', encoding='utf-8'))
    tex = np.asarray(Image.open(source / 'textures/fox_naginata_wood.png').convert('RGBA')).astype(np.float32)
    baked = {}
    views = {'player': 'tp', 'fp': 'fp', 'fox': 'tp'}
    sets = {'tp': clips(), 'fp': clips()}
    for who, sk in (('player', player), ('fp', fp), ('fox', fox)):
        baker = NaginataBaker(sk)
        frames = {}
        for clip in sets[views[who]]:
            frames[clip['name']] = baker.bake(clip, 60)
            baked[(who, clip['name'])] = frames[clip['name']]
        report(who, baker, frames, sk)
    bones = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
             'LeftArm', 'LeftForeArm', 'LeftHand', 'Weapon', 'Sheath']
    for who, sk, view in (('player', player, 'tp'), ('fp', fp, 'fp')):
        data = bedrock(sets[view], {c['name']: baked[(who, c['name'])] for c in sets[view]}, sk,
                       bones + (['Root'] if view == 'fp' else []))
        text = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        (ASSETS / f'combat/animations/naginata_{view}.animation.json').write_text(text, encoding='utf-8', newline='\n')
    # 神狐还没接进游戏（这一轮只给玩家）；动作烘焙到模型目录，以后 divine_fox_assets.py 合并。
    fox_out = art / 'MinecraftLogosMod/fox_model/divine_fox/Animation/naginata'
    fox_out.mkdir(parents=True, exist_ok=True)
    data = bedrock(sets['tp'], {c['name']: baked[('fox', c['name'])] for c in sets['tp']}, fox, bones)
    (fox_out / 'divine_fox_naginata.animation.json').write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8', newline='\n')

    fox_geo = None
    if with_fox:
        skip = ('Weapon', 'Sheath', 'NaginataShaft', 'NaginataBlade', 'NaginataFittings')
        fox_geo = Geo(source / 'review/fox_holding_kyudo.geo.json', source / 'review/fox_holding_kyudo.png', skip=skip)
    cams = {'player': [Camera(-35, 8, 5.4, (0, 16, -6), (300, 300)), Camera(80, 6, 5.4, (0, 16, -6), (300, 300)), Camera(-180, 55, 5.4, (0, 16, -6), (300, 300))],
            'fox': [Camera(-35, 8, 3.9, (0, 28, -8), (300, 300)), Camera(80, 6, 3.9, (0, 28, -8), (300, 300))]}

    def draw(who, sk, clip, t, cam, trail_from):
        frames = baked[(who, clip['name'])]
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
        pose = {n: (p[0], euler_matrix(p[1])) for n, p in frames[i][1].items()}
        W = sk.world(pose)
        quads = weapon_quads(weapon, tex, mesh(sk, W), 'weapon') + weapon_quads(weapon, tex, mesh(sk, W, 'Sheath'), 'sheath')
        quads += mannequin_quads(W) if who == 'player' else fox_geo.quads(pose)
        trail = []
        for j in range(max(0, i - trail_from), i + 1):
            pj = {n: (p[0], euler_matrix(p[1])) for n, p in frames[j][1].items()}
            trail.append((mesh(sk, sk.world(pj)) @ TIP)[:3])
        return render(quads, cam, trail=trail if len(trail) > 1 else None)

    for clip in sets['tp']:
        if only and clip['name'] not in only:
            continue
        rows = []
        times = frames_for(clip, 9)
        for who, sk in (('player', player), ('fox', fox)) if with_fox else (('player', player),):
            for v, cam in enumerate(cams[who]):
                panels = []
                for t in times:
                    tag = ','.join(n for mt, n in marker_list(clip.get('markers')) if abs(mt - t) < 1e-3)
                    panels.append((f'{t:.2f}s {tag}', draw(who, sk, clip, t, cam, 15)))
                rows.append((('玩家' if who == 'player' else '神狐') + ('·左前', '·右侧', '·俯视')[v], panels))
        sheet(rows, f"{clip['name']}  {clip['length']}s", out / f"{clip['name']}.png")
        if gif:
            images = []
            for i in range(int(round(clip['length'] * 30)) + 1):
                t = i / 30
                a = draw('player', player, clip, t, cams['player'][0], 10)
                b = draw('player', player, clip, t, cams['player'][2], 10)
                im = Image.new('RGB', (a.width * 2, a.height)); im.paste(a, (0, 0)); im.paste(b, (a.width, 0))
                images.append(im)
            images[0].save(out / f"{clip['name']}.gif", save_all=True, append_images=images[1:], duration=33, loop=0)
        print('rendered', clip['name'])


if __name__ == '__main__':
    main()
