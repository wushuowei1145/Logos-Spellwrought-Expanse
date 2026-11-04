# -*- coding: utf-8 -*-
"""群誓·狼牙长枪的动作编排，以及烘焙、导出与预览。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/oath_spear_moves.py <输出目录> [动作名…] [--gif]

编排坐标见 combat_author.py 开头：枪与手以两肩中点为原点、臂长为单位；前 -Z、上 +Y、角色右手 +X。
blade 是枪尖方向，edge 是枪锋宽面的方向（网格 +X）。多数关键帧写在髋部坐标里（space='hips'），
枪朝的就是角色正前方，躯干怎么拧都不影响指向。grip 是右手的位置，rpos / lpos 是手握在枪杆上的位置
（网格单位：-16 枪尾、-4 下段皮握、约 5 为重心、19 以上是銎口与枪锋）。
"""
import json
import math
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image

from combat_author import Baker, bedrock, euler_matrix, marker_list, player_skeleton, unit
from combat_render import Camera, mannequin_quads, render, sheet, weapon_quads

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
TIP = np.array([0, 27.4, 0, 1])
# 握点（网格单位）：右手默认握下段皮握，左手默认在前；可握范围不进銎口、不出枪尾。
GRIPS = (-4.0, 8.0)
SLIDE = (-12.0, 18.0)
# 重心附近：转枪时握这里，两头一样长。
BALANCE = 5.0

# ---------------------------------------------------------------- 常用姿势


def k(t, base=None, **kw):
    key = dict(base or {})
    key.update(kw)
    key['t'] = t
    return key


# 待机：重步兵的低位双手构，左肩在前、右手把枪尾收在右胯，枪尖平指前方略向上。
IDLE = dict(space='hips', grip=(0.40, -0.80, 0.10), blade=(0.0, 0.18, -0.98), edge=(1, 0, 0), rpos=GRIPS[0],
            up=(-3, -28, 0), lmode='slide', lhand=(-0.10, -0.40, -0.74), rmode='sword',
            rfoot=(0.22, 0, 0.22, -25), lfoot=(-0.14, 0, -0.26, 15))
# 背在身后：枪斜挂在背上，枪尖在右肩上方、枪尾在左小腿外侧（躯干坐标，枪跟着身子走）。
BACK = dict(grip=(0.22, 0.02, 0.36), blade=(0.6, 0.8, 0.0), edge=(0.8, -0.6, 0.0), rpos=8.0)
HANG_L = (-0.42, -0.98, 0.04)
HANG_R = (0.42, -0.98, 0.04)
# 连刺的收枪位：右手收回胯前，枪平端；每一刺从这里出去。
COIL = dict(space='hips', grip=(0.36, -0.62, 0.22), rpos=GRIPS[0], lmode='slide', lhand=(-0.08, -0.40, -0.72), rmode='sword')


def overhead_spin(t0, t1, turns, start_deg, grip, wobble=0.05, sign=1):
    """举过头顶平转：枪横着、绕竖直轴转圈，手在头顶略偏右前；拳头竖着，杆在拳中绕前臂转（raxial）。"""
    keys = []
    steps = int(round(turns * 8))
    for i in range(steps + 1):
        th = math.radians(start_deg + sign * 360 * turns * i / steps)
        blade = (math.sin(th), 0.0, -math.cos(th))
        w = wobble * math.sin(th * 2)
        keys.append(dict(t=t0 + (t1 - t0) * i / steps, grip=(grip[0] + w, grip[1], grip[2] + w), blade=blade,
                         edge=(0, 1, 0), rpos=BALANCE, raxial=True))
    return keys


def side_spin(t0, t1, deg0, deg1, grip):
    """右侧竖直面里转枪（风车）：杆在右手里绕向外伸的前臂转。角度 0 为枪尖朝前，增大是枪尖向上、向后（向后转）。"""
    keys = []
    steps = max(2, int(round(abs(deg1 - deg0) / 45)))
    for i in range(steps + 1):
        th = math.radians(deg0 + (deg1 - deg0) * i / steps)
        keys.append(dict(t=t0 + (t1 - t0) * i / steps, grip=grip, blade=(0.0, math.sin(th), -math.cos(th)),
                         edge=(1, 0, 0), rpos=BALANCE, raxial=True))
    return keys

# ---------------------------------------------------------------- 动作


def flurry(name, seed):
    """一秒八刺：每一刺 0.125 秒，0.045 秒刺到头（contact）、短暂顶住，再收回。落点在前方随机偏开，相邻两刺不落在同一处。"""
    rng = random.Random(seed)
    aims, last = [], None
    while len(aims) < 8:
        yaw, pitch = rng.uniform(-15, 15), rng.uniform(-7, 15)
        if last and math.hypot(yaw - last[0], pitch - last[1]) < 9:
            continue
        aims.append((yaw, pitch, rng.uniform(0.78, 1.0), rng.uniform(-6, 6)))
        last = aims[-1]
    keys, contacts = [], []

    def aim_dir(yaw, pitch):
        y, p = math.radians(yaw), math.radians(pitch)
        return (math.sin(y) * math.cos(p), math.sin(p), -math.cos(y) * math.cos(p))  # 偏航为正是偏向右手

    for i, (yaw, pitch, depth, roll) in enumerate(aims):
        t0 = i * 0.125
        d = np.array(aim_dir(yaw, pitch))
        # 收枪：枪已经转向这一刺的落点；躯干略侧向它。
        keys.append(k(t0, COIL, blade=tuple(d * 0.6 + np.array([0, 0.06, -0.4])), edge=(1, 0, 0),
                      up=(-4, -24 + yaw * 0.6, roll * 0.5)))
        # 刺到头：右手把枪送出，前手不动、杆从中滑过；身子压上去。
        reach = np.array(COIL['grip']) + d * (0.95 * depth) + np.array([-0.06, 0.04, 0])
        # 第一人称的手整体前移过（reach），刺出的距离要短一些，否则手够不到枪杆。
        fp_reach = np.array(COIL['grip']) + d * (0.62 * depth)
        keys.append(k(t0 + 0.045, COIL, grip=tuple(reach), blade=tuple(d), edge=(1, 0, 0),
                      up=(-14, -10 + yaw * 0.9, roll), ease='lin', fp=dict(grip=tuple(fp_reach))))
        keys.append(k(t0 + 0.07, COIL, grip=tuple(reach - d * 0.05), blade=tuple(d), edge=(1, 0, 0),
                      up=(-13, -11 + yaw * 0.9, roll), ease='hold', fp=dict(grip=tuple(fp_reach - d * 0.05))))
        contacts.append(round(t0 + 0.045, 3))
    keys.append(k(1.0, COIL, blade=(0, 0.12, -0.99), edge=(1, 0, 0), up=(-4, -24, 0)))
    keys[0] = k(0.0, COIL, blade=(0, 0.12, -0.99), edge=(1, 0, 0), up=(-4, -24, 0))
    return dict(name=name, length=1.0, loop='once', markers={'contact': contacts, 'tip_fx': contacts}, keys=keys)


def clips():
    out = []

    out.append(dict(name='idle', length=2.4, loop='loop', keys=[
        k(0.0, IDLE),
        k(1.2, IDLE, grip=(0.40, -0.77, 0.08), blade=(0.0, 0.21, -0.98), up=(-1, -27, 0)),
        k(2.4, IDLE),
    ]))

    # 拔枪：右手越过右肩抓住背上的枪杆，往上提到头顶，平转两圈，压下来左手接住，收成待机。
    draw_keys = [
        k(0.0, BACK, rmode='free', rhand=HANG_R, lmode='free', lhand=HANG_L, up=(0, 0, 0)),
        k(0.16, BACK, rmode='sword', blend=0.16, up=(0, -10, 0), chest=(0, -6, 0)),
        k(0.30, grip=(0.32, 0.78, -0.08), blade=(-0.9, 0.12, 0.4), edge=(0, 1, 0), rpos=BALANCE, raxial=True,
          up=(-2, -6, 0), chest=(0, 0, 0)),
    ]
    draw_keys += overhead_spin(0.36, 0.96, 2, -65, (0.32, 0.82, -0.12))[0:]
    draw_keys += [
        k(1.10, space='hips', grip=(0.42, -0.45, -0.05), blade=(0.0, 0.32, -0.95), edge=(1, 0, 0), rpos=-1.0,
          raxial=False, lmode='slide', lhand=(-0.12, -0.36, -0.74), blend=0.14, up=(-8, -22, 0)),
        k(1.30, IDLE),
    ]
    out.append(dict(name='draw', length=1.3, loop='once', markers={'cancel': 1.16}, keys=draw_keys))

    # 收枪：左手松开，举过头顶平转一圈，顺势背回身后，右手松开垂下。
    sheathe_keys = [
        k(0.0, IDLE),
        k(0.12, space='hips', grip=(0.40, -0.30, -0.10), blade=(0.0, 0.5, -0.86), edge=(1, 0, 0), rpos=0.0,
          lmode='free', lhand=HANG_L, blend=0.12, up=(-4, -20, 0)),
        k(0.24, grip=(0.30, 0.78, -0.10), blade=(0.0, 0.15, -0.99), edge=(0, 1, 0), rpos=BALANCE, raxial=True, up=(0, -8, 0)),
    ]
    sheathe_keys += overhead_spin(0.28, 0.76, 1, 0, (0.30, 0.82, -0.12))
    sheathe_keys += [
        k(0.96, grip=(0.30, 0.30, 0.30), blade=(0.62, 0.78, 0.1), edge=(0.78, -0.62, 0), rpos=7.0, raxial=False,
          up=(0, -6, 0), chest=(0, -4, 0)),
        k(1.10, BACK, up=(0, -4, 0)),
        k(1.24, BACK, rmode='free', rhand=HANG_R, blend=0.16, up=(0, 0, 0)),
        k(1.45, BACK, rmode='free', rhand=HANG_R),
    ]
    out.append(dict(name='sheathe', length=1.45, loop='once', keys=sheathe_keys))

    # 第一击：罗马式弓步直刺。右手把枪从胯边送出，前手不动、枪杆从中滑过，身子压上去。
    out.append(dict(name='thrust_1', length=0.5, loop='once', markers={'input_open': 0.08, 'contact': 0.16, 'cancel': 0.3}, keys=[
        k(0.0, IDLE),
        k(0.07, IDLE, grip=(0.44, -0.74, 0.34), blade=(0.0, 0.12, -0.99), up=(-1, -36, 0), ease='hold'),
        k(0.16, IDLE, grip=(0.22, -0.56, -0.78), blade=(0.0, 0.07, -1.0), up=(-16, -6, 0), ease='lin',
          fp=dict(grip=(0.26, -0.58, -0.56))),
        k(0.22, IDLE, grip=(0.22, -0.57, -0.74), blade=(0.0, 0.07, -1.0), up=(-15, -7, 0), ease='hold',
          fp=dict(grip=(0.26, -0.59, -0.53))),
        k(0.30, IDLE, grip=(0.30, -0.66, -0.36), blade=(0.0, 0.12, -0.99), up=(-9, -16, 0)),
        k(0.50, IDLE),
    ]))

    # 第二击：拧腰横扫。枪甩到右后方蓄势，一记大弧从右扫到左，枪锋前缘领路。
    out.append(dict(name='sweep_2', length=0.62, loop='once', markers={'input_open': 0.08, 'contact': 0.24, 'cancel': 0.4}, keys=[
        k(0.0, IDLE, grip=(0.30, -0.66, -0.36), blade=(0.0, 0.12, -0.99), up=(-9, -16, 0)),
        k(0.11, space='hips', grip=(0.62, -0.52, -0.02), blade=(0.86, 0.12, -0.5), edge=(-0.5, 0, -0.86), rpos=-7.0,
          lmode='slide', lhand=(-0.05, -0.38, -0.7), up=(-4, -62, -4), ease='hold'),
        k(0.24, space='hips', grip=(0.20, -0.46, -0.58), blade=(0.0, 0.06, -1.0), edge=(-1, 0, 0), rpos=-7.0,
          up=(-8, -12, 0), ease='lin'),
        k(0.33, space='hips', grip=(-0.22, -0.5, -0.38), blade=(-0.9, 0.04, -0.42), edge=(-0.42, 0, 0.9), rpos=-7.0,
          up=(-6, 40, 4)),
        k(0.40, space='hips', grip=(-0.22, -0.5, -0.36), blade=(-0.92, 0.04, -0.38), edge=(-0.38, 0, 0.92), rpos=-7.0,
          up=(-5, 42, 4), ease='hold'),
        k(0.62, space='hips', grip=(0.10, -0.55, -0.25), blade=(-0.5, 0.1, -0.86), edge=(-0.86, 0, 0.5), rpos=-5.0,
          up=(-4, 20, 0)),
    ]))

    # 第三击：斯巴达式过肩刺。枪提到右肩上反手握住，左臂像持盾一样前护，自上而下狠狠扎出。
    out.append(dict(name='overhand_3', length=0.66, loop='once', markers={'input_open': 0.08, 'contact': 0.27, 'cancel': 0.42}, keys=[
        k(0.0, space='hips', grip=(0.10, -0.55, -0.25), blade=(-0.5, 0.1, -0.86), edge=(-0.86, 0, 0.5), rpos=-5.0,
          lmode='slide', lhand=(-0.05, -0.38, -0.7), up=(-4, 20, 0)),
        k(0.13, space='hips', grip=(0.48, 0.30, 0.16), blade=(0.0, -0.16, -0.99), edge=(1, 0, 0), rpos=1.5,
          lmode='free', lhand=(-0.40, -0.22, -0.52), lhand_rot=(-75, 0, 0), blend=0.1, up=(4, -34, 0), ease='hold'),
        k(0.27, space='hips', grip=(0.24, -0.06, -0.80), blade=(0.0, -0.30, -0.95), edge=(1, 0, 0), rpos=1.5,
          lhand=(-0.38, -0.36, -0.30), up=(-20, -4, 0), ease='lin'),
        k(0.33, space='hips', grip=(0.24, -0.08, -0.76), blade=(0.0, -0.31, -0.95), edge=(1, 0, 0), rpos=1.5,
          up=(-19, -5, 0), ease='hold'),
        k(0.42, space='hips', grip=(0.34, -0.20, -0.42), blade=(0.0, -0.2, -0.98), edge=(1, 0, 0), rpos=0.0,
          up=(-12, -16, 0)),
        k(0.66, space='hips', grip=(0.38, -0.86, 0.06), blade=(0.0, -0.22, -0.97), edge=(1, 0, 0), rpos=GRIPS[0],
          lmode='slide', lhand=(-0.10, -0.46, -0.70), blend=0.14, up=(-10, -30, 0)),
    ]))

    # 第四击：上挑。压低枪尖贴地，一记自下而上的大挑，把锥形里的目标挑上半空，身子后仰、枪指天。
    out.append(dict(name='launch_4', length=0.86, loop='once', markers={'input_open': 0.1, 'contact': 0.36, 'cancel': 0.6}, keys=[
        k(0.0, space='hips', grip=(0.38, -0.86, 0.06), blade=(0.0, -0.22, -0.97), edge=(1, 0, 0), rpos=GRIPS[0],
          lmode='slide', lhand=(-0.10, -0.46, -0.70), up=(-10, -30, 0)),
        k(0.17, space='hips', grip=(0.38, -0.90, 0.02), blade=(0.0, -0.26, -0.96), edge=(1, 0, 0), rpos=-6.0,
          lhand=(-0.12, -0.55, -0.62), up=(-30, -34, 0), ease='hold'),
        k(0.36, space='hips', grip=(0.28, -0.52, -0.50), blade=(0.0, 0.58, -0.81), edge=(1, 0, 0), rpos=-6.0,
          lhand=(-0.08, -0.30, -0.70), up=(4, -14, 0)),
        k(0.47, space='hips', grip=(0.22, 0.22, -0.40), blade=(0.04, 0.97, -0.24), edge=(1, 0, 0), rpos=-6.0,
          lhand=(-0.05, 0.05, -0.52), up=(14, -6, 0), fp=dict(grip=(0.24, 0.06, -0.3))),
        k(0.60, space='hips', grip=(0.22, 0.20, -0.38), blade=(0.04, 0.96, -0.27), edge=(1, 0, 0), rpos=-6.0,
          up=(13, -7, 0), ease='hold', fp=dict(grip=(0.24, 0.05, -0.29))),
        k(0.86, IDLE),
    ]))

    # 按住右键：三段一秒八刺（落点各不相同），接一记向后转枪下砸。
    for name, seed in (('flurry_a', 11), ('flurry_b', 23), ('flurry_c', 37)):
        out.append(flurry(name, seed))

    # 收尾：向后转枪一圈（右手伸向身侧，枪在右侧竖直面里转），举过头顶左手接住，双手狠狠砸下。
    slam = [k(0.0, COIL, blade=(0, 0.12, -0.99), edge=(1, 0, 0), up=(-4, -24, 0)),
            k(0.08, grip=(0.86, 0.22, -0.12), blade=(0.0, 0.30, -0.95), edge=(1, 0, 0), rpos=BALANCE, raxial=True,
              lmode='free', lhand=HANG_L, blend=0.08, up=(0, -10, 0))]
    slam += side_spin(0.10, 0.50, 18, 360 + 110, (0.86, 0.26, -0.10))[1:]
    slam += [
        k(0.56, grip=(0.34, 0.78, 0.06), blade=(0.0, 0.62, 0.78), edge=(1, 0, 0), rpos=BALANCE, raxial=False,
          lmode='slide', lhand=(-0.05, 0.62, -0.12), blend=0.1, up=(14, -12, 0), chest=(6, 0, 0)),
        k(0.62, grip=(0.32, 0.80, 0.10), blade=(0.0, 0.70, 0.71), edge=(1, 0, 0), rpos=BALANCE, up=(16, -12, 0), ease='hold'),
        k(0.72, space='hips', grip=(0.16, -0.32, -0.62), blade=(0.0, -0.56, -0.83), edge=(1, 0, 0), rpos=GRIPS[0],
          lhand=(-0.08, -0.30, -0.64), up=(-28, -8, 0), chest=(-6, 0, 0), ease='lin'),
        k(0.84, space='hips', grip=(0.16, -0.34, -0.60), blade=(0.0, -0.57, -0.82), edge=(1, 0, 0), rpos=GRIPS[0],
          up=(-26, -9, 0), chest=(-4, 0, 0), ease='hold'),
        k(1.15, IDLE),
    ]
    out.append(dict(name='slam', length=1.15, loop='once', markers={'contact': 0.72, 'cancel': 0.92}, keys=slam))

    for clip in out:
        clip.setdefault('stance', {'rfoot': IDLE['rfoot'], 'lfoot': IDLE['lfoot']})
        fp_aim(clip)
    return out


# 第一人称：枪握在画面右下，平行于视线会让枪尖停在准星右下；整体往左、往上偏一点，刺出去正对准星。
FP_AIM_YAW, FP_AIM_PITCH = 14.0, 6.0


def fp_aim(clip):
    R = euler_matrix((FP_AIM_PITCH, FP_AIM_YAW, 0))
    for key in clip['keys']:
        if key.get('space') != 'hips' or 'blade' not in key:
            continue
        fp = dict(key.get('fp', {}))
        fp.setdefault('blade', tuple(R @ np.asarray(key['blade'], float)))
        fp.setdefault('edge', tuple(R @ np.asarray(key['edge'], float)))
        key['fp'] = fp

# ---------------------------------------------------------------- 骨架


# 网格的 scale：骨架的武器缩放乘上它。第三人称 0.8 × 1.25 = 1，第一人称 0.58 × 1.25。
WEAPON_SCALE = 1.25
FP_SCALE = 0.58 * WEAPON_SCALE


def skeletons():
    dummy = (np.zeros(3), np.eye(3))  # 长枪没有鞘，Sheath 骨骼不用
    player = player_skeleton(ROOT / 'src/main/resources/assets/logos_dimension/combat/skeletons/player_tp.json',
                             weapon_scale=1.0, carry=dummy)
    fp = player_skeleton(ROOT / 'src/main/resources/assets/logos_dimension/combat/skeletons/player_fp.json',
                         weapon_scale=FP_SCALE, carry=dummy)
    # 第一人称根骨与长剑、打刀相同；长枪端得低，手的目标统一抬高、前移，让枪身留在画面里。
    fp.root = ((15.13, -1.82, 0), (0, 0, 12))
    fp.reach = np.array([-3.0, 3.0, -5.0])
    player.body = ((-4.4, 12.0, -2.5), (4.4, 21.0, 2.5))
    for sk in (player, fp):
        sk.grips, sk.slide = GRIPS, SLIDE
    player.tag, fp.tag = 'player', 'fp'
    return player, fp

# ---------------------------------------------------------------- 预览


def mesh(skeleton, W):
    s = skeleton.weapon_scale
    T = np.eye(4); T[:3, 3] = skeleton.weapon_pivot
    return W['Weapon'] @ T @ np.diag([s, s, s, 1.0])


def frames_for(clip, n):
    times = [clip['length'] * i / (n - 1) for i in range(n)]
    for t, name in marker_list(clip.get('markers')):
        if name in ('contact', 'cancel') and len(times) < n + 4:
            times.append(t)
    return sorted(set(round(t, 3) for t in times))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    out = Path(args[0])
    only = set(args[1:])
    gif = '--gif' in sys.argv
    out.mkdir(parents=True, exist_ok=True)
    player, fp = skeletons()
    weapon = json.load(open(ASSETS / 'combat/weapons/pack_oath_spear.json', encoding='utf-8'))
    tex = np.asarray(Image.open(ASSETS / 'textures/item/pack_oath_spear.png').convert('RGBA')).astype(np.float32)
    all_clips = clips()
    baked = {}
    for who, sk in (('player', player), ('fp', fp)):
        baker = Baker(sk)
        for clip in all_clips:
            baked[(who, clip['name'])] = baker.bake(clip, 60)
        worst = {}
        for clip_name, t, side, over in baker.warnings:
            key = (clip_name, side)
            lo, hi, m = worst.get(key, (t, t, 0))
            worst[key] = (min(lo, t), max(hi, t), max(m, over))
        for (clip_name, side), (lo, hi, m) in sorted(worst.items()):
            print(f'{who} {clip_name} {side} reach short {lo:.2f}-{hi:.2f}s max {m:.2f} arm')
        if who == 'player':
            by = {}
            for clip_name, t, side, r in baker.residuals:
                by.setdefault((clip_name, side), []).append(r)
            for (clip_name, side), rs in sorted(by.items()):
                print(f'{who} {clip_name} {side} grip offset avg {sum(rs) / len(rs):.0f} max {max(rs):.0f}')
    bones = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
             'LeftArm', 'LeftForeArm', 'LeftHand', 'Weapon', 'Sheath']
    for who, sk, view in (('player', player, 'tp'), ('fp', fp, 'fp')):
        data = bedrock(all_clips, {c['name']: baked[(who, c['name'])] for c in all_clips}, sk,
                       bones + (['Root'] if view == 'fp' else []))
        text = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        (ASSETS / f'combat/animations/oath_spear_{view}.animation.json').write_text(text, encoding='utf-8')
    cams = [Camera(-35, 8, 4.6, (0, 18, -8), (300, 300)), Camera(80, 6, 4.6, (0, 18, -8), (300, 300))]

    def draw(clip, t, cam, trail_from):
        frames = baked[('player', clip['name'])]
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
        pose = {n: (p, euler_matrix(e)) for n, (p, e) in frames[i][1].items()}
        W = player.world(pose)
        quads = weapon_quads(weapon, tex, mesh(player, W), 'weapon') + mannequin_quads(W)
        trail = []
        for j in range(max(0, i - trail_from), i + 1):
            pj = {n: (p, euler_matrix(e)) for n, (p, e) in frames[j][1].items()}
            trail.append((mesh(player, player.world(pj)) @ TIP)[:3])
        return render(quads, cam, trail=trail if len(trail) > 1 else None)

    for clip in all_clips:
        if only and clip['name'] not in only:
            continue
        rows = []
        times = frames_for(clip, 9)
        for v, cam in enumerate(cams):
            panels = []
            for t in times:
                tag = ','.join(n for mt, n in marker_list(clip.get('markers')) if abs(mt - t) < 1e-3 and n != 'tip_fx')
                panels.append((f'{t:.2f}s {tag}', draw(clip, t, cam, 15)))
            rows.append((('左前' if v == 0 else '右侧'), panels))
        sheet(rows, f"{clip['name']}  {clip['length']}s", out / f"{clip['name']}.png")
        if gif:
            images = []
            for i in range(int(round(clip['length'] * 30)) + 1):
                t = i / 30
                a = draw(clip, t, cams[0], 10)
                b = draw(clip, t, cams[1], 10)
                im = Image.new('RGB', (a.width * 2, a.height)); im.paste(a, (0, 0)); im.paste(b, (a.width, 0))
                images.append(im)
            images[0].save(out / f"{clip['name']}.gif", save_all=True, append_images=images[1:], duration=33, loop=0)
        print('rendered', clip['name'])


if __name__ == '__main__':
    main()
