# -*- coding: utf-8 -*-
"""神狐打刀的动作编排，以及烘焙、导出与预览。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/uchigatana_moves.py <美术目录> <输出目录> [动作名…] [--gif]

<美术目录> 是放 fox_uchigatana/v02 与 MinecraftLogosMod/fox_model 的上级目录（本机为 “Minecraft MOD”）。
编排坐标见 combat_author.py 开头：刀与手在躯干坐标里，以两肩中点为原点、臂长为单位；
前 -Z、上 +Y、角色右手 +X。blade 是刀尖方向，edge 是刃的朝向。
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

from combat_author import Baker, bbmodel_skeleton, bedrock, euler_matrix, player_skeleton
from combat_render import Camera, Geo, mannequin_quads, render, sheet, weapon_quads

ROOT = Path(__file__).resolve().parent.parent
TIP = np.array([-0.4, 15.4, 0, 1])

# ---------------------------------------------------------------- 常用姿势

IDLE = dict(grip=(0.40, -0.80, -0.30), blade=(0.22, -0.42, -0.88), edge=(0, -1, 0),
            up=(0, 12, 0), hips=(0, -0.04, 0), lmode='saya',
            rfoot=(0.16, 0, -0.22, -10), lfoot=(-0.2, 0, 0.18, 25),
            # 第一人称看不到腰下的下段构，改为刀在右前方、刀尖斜指前上的中段单手构。
            fp=dict(grip=(0.34, -0.5, -0.62), blade=(-0.12, 0.3, -0.95), edge=(0.2, -0.95, -0.3)))
GUARD = dict(grip=(0.22, -0.38, -0.55), blade=(-0.75, 0.62, -0.22), edge=(0.1, 0.2, -1),
             up=(0, -4, 0), hips=(0, -0.07, 0), lmode='mune', mune=9.0,
             rfoot=(0.22, 0, -0.18, -5), lfoot=(-0.24, 0, 0.2, 20))
HANG_L = (-0.42, -0.98, 0.04)
HANG_R = (0.42, -0.98, 0.04)


def k(t, base=None, **kw):
    key = dict(base or {})
    key.update(kw)
    key['t'] = t
    return key


def spin_keys(t0, t1, turns, grip, tilt=8):
    """手腕转刀：刀在身体右侧的竖直面里绕握点转圈，刀尖 前 → 下 → 后 → 上。"""
    keys = []
    steps = int(turns * 8)
    axis_tilt = euler_matrix((0, 0, -tilt))  # 转圈的平面略向外倾，避开手臂
    for i in range(steps + 1):
        th = math.radians(360 * turns * i / steps)
        blade = axis_tilt @ np.array([0, -math.sin(th), -math.cos(th)])
        edge = axis_tilt @ np.array([0, -math.cos(th), math.sin(th)])
        wob = 0.04 * math.sin(th)
        keys.append(dict(t=t0 + (t1 - t0) * i / steps, grip=(grip[0], grip[1] + wob, grip[2]),
                         blade=tuple(blade), edge=tuple(edge)))
    return keys

# ---------------------------------------------------------------- 动作


def clips():
    out = []

    out.append(dict(name='idle', length=2.4, loop='loop', keys=[
        k(0.0, IDLE),
        k(1.2, IDLE, grip=(0.40, -0.78, -0.31), up=(2, 12, 0), hips=(0, -0.05, 0)),
        k(2.4, IDLE),
    ]))

    # 拔刀：右手越过身体握柄，左手推鞘口、向后拉鞘，刀横着拔出后挽一个刀花收到待机。
    out.append(dict(name='draw', length=1.0, loop='once', markers={'cancel': 0.85}, keys=[
        k(0.0, rmode='free', rhand=HANG_R, lmode='free', lhand=HANG_L, sheathed=0, up=(0, 0, 0)),
        k(0.14, rmode='sword', lmode='saya', blend=0.14, sheathed=0, up=(0, 22, 0), chest=(0, 12, 0),
          saya=(0, -18, 0), hips=(0, -0.05, 0)),
        k(0.24, sheathed=2.0, saya=(0, -28, 0), saya_off=(0, 0, 0.1),
          fox=dict(saya=(0, -34, 0), saya_off=(0, 0, 0.25), chest=(0, 22, 0))),
        # 神狐的刀约为手臂的 2.2 倍，左手要把鞘向后猛拉、转开，刀才拔得到底。
        k(0.40, sheathed=13.4, ease='lin', saya=(0, -42, 4), saya_off=(0, 0.02, 0.32), up=(0, 0, 0), chest=(0, -6, 0),
          fox=dict(saya=(0, -50, 0), saya_off=(0, 0, 0.58), up=(0, 6, 0), chest=(0, 26, 0))),
        k(0.52, grip=(0.72, -0.30, -0.55), blade=(0.75, 0.05, -0.55), edge=(0.5, 0.1, 0.8), up=(0, -22, 0),
          chest=(0, -8, 0), saya=(0, -20, 0), saya_off=(0, 0, 0.12)),
        k(0.66, grip=(0.55, -0.35, -0.55), blade=(0.6, 0.55, 0.2), edge=(0.2, -0.3, 1), up=(0, -10, 0), chest=(0, 0, 0)),
        k(0.82, grip=(0.48, -0.62, -0.42), blade=(0.25, -0.2, -0.95), edge=(0, -1, 0), saya=(0, 0, 0), saya_off=(0, 0, 0)),
        k(1.0, IDLE),
    ]))

    # 第一刀：右手单手逆袈裟，从右下撩向左上。
    out.append(dict(name='slash_1', length=0.48, loop='once', markers={'input_open': 0.08, 'contact': 0.15, 'cancel': 0.3}, keys=[
        k(0.0, IDLE),
        k(0.07, grip=(0.52, -0.66, 0.02), blade=(0.38, -0.3, 0.88), edge=(0.1, 0.8, 0.2), up=(0, -22, 0),
          lmode='free', lhand=(-0.62, -0.62, 0.25), blend=0.08, ease='hold'),
        k(0.15, grip=(0.12, -0.42, -0.72), blade=(-0.55, 0.32, -0.77), edge=(-0.4, 0.9, 0.1), up=(0, 14, 0),
          lhand=(-0.66, -0.5, 0.35)),
        k(0.22, grip=(-0.22, 0.08, -0.55), blade=(-0.5, 0.8, 0.3), edge=(-0.3, 0.2, 0.95), up=(0, 32, 0),
          lhand=(-0.62, -0.48, 0.42)),
        k(0.30, grip=(-0.18, 0.04, -0.56), blade=(-0.45, 0.82, 0.32), edge=(-0.3, 0.2, 0.95), up=(0, 30, 0), ease='hold'),
        k(0.48, grip=(0.1, -0.35, -0.55), blade=(-0.2, 0.55, -0.8), edge=(-0.6, 0.1, 0.4), up=(0, 18, 0),
          lhand=(-0.5, -0.7, 0.15)),
    ]))

    # 第二刀：双手左袈裟，从左上斩向右下。
    out.append(dict(name='slash_2', length=0.52, loop='once', markers={'input_open': 0.08, 'contact': 0.18, 'cancel': 0.32}, keys=[
        k(0.0, grip=(-0.18, 0.04, -0.56), blade=(-0.45, 0.82, 0.32), edge=(-0.3, 0.2, 0.95), up=(0, 30, 0),
          lmode='free', lhand=(-0.62, -0.48, 0.42)),
        k(0.08, grip=(-0.22, 0.22, -0.35), blade=(-0.3, 0.7, 0.65), edge=(0.3, 0.35, -0.9), up=(0, 26, 0),
          lmode='two', blend=0.07, ease='hold'),
        k(0.18, grip=(0.08, -0.45, -0.72), blade=(0.45, -0.1, -0.9), edge=(0.6, -0.8, 0), up=(0, -12, 0)),
        k(0.26, grip=(0.34, -0.74, -0.36), blade=(0.55, -0.6, 0.3), edge=(0.3, -0.2, 0.95), up=(0, -24, 0)),
        k(0.32, grip=(0.34, -0.72, -0.38), blade=(0.55, -0.62, 0.28), edge=(0.3, -0.2, 0.95), up=(0, -22, 0), ease='hold'),
        k(0.52, grip=(0.4, -0.72, -0.35), blade=(0.3, -0.45, -0.85), edge=(0, -1, 0), up=(0, -8, 0)),
    ]))

    # 第三刀：右手单手横一文字，大幅拧身从右向左横扫，左手前指。
    out.append(dict(name='slash_3', length=0.52, loop='once', markers={'input_open': 0.08, 'contact': 0.2, 'cancel': 0.34}, keys=[
        k(0.0, grip=(0.4, -0.72, -0.35), blade=(0.3, -0.45, -0.85), edge=(0, -1, 0), up=(0, -8, 0),
          lmode='two'),
        k(0.09, grip=(0.55, -0.42, 0.05), blade=(0.85, 0.05, 0.5), edge=(0.4, 0, -1), up=(0, -40, 0),
          lmode='free', lhand=(-0.22, -0.3, -0.85), lhand_rot=(-80, 0, 0), blend=0.08, ease='hold'),
        k(0.20, grip=(0.1, -0.5, -0.82), blade=(-0.2, 0.0, -1), edge=(-1, 0, 0), up=(0, 14, 0),
          lhand=(-0.5, -0.45, -0.3)),
        k(0.28, grip=(-0.45, -0.45, -0.45), blade=(-0.95, 0.05, 0.25), edge=(0.2, 0, 1), up=(0, 44, 0),
          lhand=(-0.62, -0.42, 0.4), lhand_rot=(0, 0, 0)),
        k(0.34, grip=(-0.44, -0.46, -0.44), blade=(-0.95, 0.02, 0.28), edge=(0.2, 0, 1), up=(0, 42, 0), ease='hold'),
        k(0.52, grip=(0.05, -0.55, -0.6), blade=(-0.4, 0.2, -0.9), edge=(-0.3, -0.9, 0), up=(0, 20, 0),
          lhand=(-0.55, -0.7, 0.1)),
    ]))

    # 第四刀：双手举过头顶，踏步唐竹割，收在低位停顿。
    out.append(dict(name='slash_4', length=0.78, loop='once', markers={'input_open': 0.1, 'contact': 0.38, 'cancel': 0.58}, keys=[
        k(0.0, grip=(0.05, -0.55, -0.6), blade=(-0.4, 0.2, -0.9), edge=(-0.3, -0.9, 0), up=(0, 20, 0),
          lmode='free', lhand=(-0.55, -0.7, 0.1)),
        k(0.14, grip=(0.06, 0.5, -0.42), blade=(0, 0.6, 0.8), edge=(0, 0.8, -0.6), up=(-10, 4, 0),
          lmode='two', blend=0.1, hips=(0, 0.02, 0)),
        k(0.28, grip=(0.06, 0.58, -0.36), blade=(0, 0.42, 0.9), edge=(0, 0.9, -0.42), up=(-14, 4, 0), hips=(0, 0.03, 0.02),
          ease='hold', rfoot=(0.16, 0.06, -0.22, -10)),
        k(0.38, grip=(0.06, -0.3, -0.85), blade=(0, -0.15, -1), edge=(0, -1, -0.1), up=(16, 0, 0),
          hips=(0, -0.16, -0.12), rfoot=(0.16, 0, -0.6, -6), lfoot=(-0.2, 0, 0.3, 25)),
        k(0.46, grip=(0.06, -0.72, -0.6), blade=(0, -0.75, -0.66), edge=(0, -0.66, 0.75), up=(20, 0, 0),
          hips=(0, -0.2, -0.14)),
        k(0.58, grip=(0.06, -0.72, -0.6), blade=(0, -0.76, -0.65), edge=(0, -0.65, 0.76), up=(19, 0, 0),
          hips=(0, -0.2, -0.14), ease='hold'),
        k(0.78, IDLE, lmode='saya', blend=0.15),
    ]))

    # 防御：右手握柄，左掌托刀背，刀斜架在身前。按下立刻进入，靠过渡时长做到“马上”。
    out.append(dict(name='guard', length=1.6, loop='loop', keys=[
        k(0.0, GUARD),
        k(0.8, GUARD, grip=(0.22, -0.37, -0.56), hips=(0, -0.075, 0)),
        k(1.6, GUARD),
    ]))

    # 弹刀：受击瞬间刀向右上外崩，左手离刀，留出接突刺的架势。
    out.append(dict(name='parry', length=0.42, loop='once', markers={'cancel': 0.12}, keys=[
        k(0.0, GUARD),
        # 左手先离开刀背，再随刀外崩甩开，不跟着刀背被扯出去。
        k(0.01, lmode='free', lhand=(-0.3, -0.05, -0.68), blend=0.02),
        k(0.05, grip=(0.4, -0.22, -0.6), blade=(0.55, 0.78, -0.3), edge=(0.9, -0.2, -0.3), up=(0, -16, 0),
          lhand=(-0.55, -0.5, -0.25)),
        k(0.14, grip=(0.42, -0.2, -0.58), blade=(0.6, 0.76, -0.25), edge=(0.9, -0.3, -0.2), up=(0, -18, 0), ease='hold'),
        k(0.42, grip=(0.42, -0.42, -0.45), blade=(0.1, 0.2, -1), edge=(0.2, -1, 0), up=(0, -10, 0),
          lhand=(-0.45, -0.6, -0.1)),
    ]))

    # 弹刀反击：极快的平突，刃朝外，踏步前冲。
    out.append(dict(name='riposte', length=0.42, loop='once', markers={'contact': 0.09, 'cancel': 0.24}, keys=[
        k(0.0, grip=(0.42, -0.42, -0.45), blade=(0.1, 0.2, -1), edge=(0.2, -1, 0), up=(0, -10, 0),
          lmode='free', lhand=(-0.45, -0.6, -0.1)),
        k(0.04, grip=(0.5, -0.42, -0.02), blade=(0, 0.04, -1), edge=(1, 0, 0), up=(0, -24, 0),
          lhand=(-0.25, -0.3, -0.75), lhand_rot=(-75, 0, 0), ease='hold', space='hips'),
        k(0.09, grip=(0.14, -0.3, -0.98), blade=(0, 0.02, -1), edge=(1, 0, 0), up=(4, 28, 0),
          lhand=(-0.62, -0.5, 0.35), lhand_rot=(0, 0, 0), hips=(0, -0.12, -0.25), space='hips',
          rfoot=(0.16, 0, -0.75, -6), lfoot=(-0.2, 0, 0.12, 25)),
        k(0.24, grip=(0.14, -0.3, -0.97), blade=(0, 0.03, -1), edge=(1, 0, 0), up=(4, 26, 0), space='hips',
          hips=(0, -0.12, -0.25), ease='hold'),
        k(0.42, IDLE, lmode='saya', blend=0.15),
    ]))

    # 收刀：血振 → 手腕转刀两圈 → 刀横到身前、刀背搭在左手鞘口 → 刀尖入鞘、缓推到底 → 残心后松手。
    spins = spin_keys(0.34, 1.06, 2, grip=(0.62, -0.22, -0.5), tilt=14)
    out.append(dict(name='sheathe', length=2.3, loop='hold', keys=[
        k(0.0, IDLE),
        k(0.12, grip=(0.48, -0.22, -0.62), blade=(0.25, 0.6, -0.75), edge=(0.2, -0.6, -0.8), up=(0, -4, 0),
          lmode='free', lhand=(-0.48, -0.85, 0.05), blend=0.1),
        k(0.22, grip=(0.58, -0.58, -0.48), blade=(0.4, -0.5, -0.75), edge=(0.2, -0.85, 0.45), up=(0, -10, 0), ease='hold'),
        *spins,
        k(1.18, grip=(0.5, -0.36, -0.62), blade=(-0.7, -0.05, 0.7), edge=(0, 1, 0.1), up=(0, 10, 0),
          lmode='saya', blend=0.16, saya=(0, -30, 0), saya_off=(0, 0, -0.05)),
        k(1.4, sheathed=13.4, saya=(0, -50, 0), saya_off=(0, 0.02, -0.16), chest=(0, 14, 0),
          fox=dict(saya=(0, -50, 0), saya_off=(0, 0, 0.58), chest=(0, 26, 0))),
        k(1.62, sheathed=8.0, saya=(0, -34, 0), saya_off=(0, 0, -0.08), chest=(0, 8, 0), ease='lin',
          fox=dict(saya=(0, -40, 0), saya_off=(0, 0, 0.4), chest=(0, 20, 0))),
        k(1.9, sheathed=0.0, saya=(0, 0, 0), saya_off=(0, 0, 0), up=(0, 14, 0), chest=(0, 4, 0)),
        k(2.0, sheathed=0.0, up=(0, 12, 0), chest=(0, 0, 0)),
        k(2.05, rmode='free', rhand=HANG_R, lmode='free', lhand=HANG_L, blend=0.25),
        k(2.3, up=(0, 0, 0), hips=(0, 0, 0)),
    ]))
    for clip in out:
        # 没写脚步的帧保持待机的弓步。
        clip.setdefault('stance', {'rfoot': IDLE['rfoot'], 'lfoot': IDLE['lfoot']})
    return out

# ---------------------------------------------------------------- 骨架


# 打刀网格的 scale：骨架的武器缩放乘上它。第三人称 0.8 × 1.25 = 1，第一人称 0.58 × 1.25。
WEAPON_SCALE = 1.25
FP_SCALE = 0.58 * WEAPON_SCALE


def skeletons(art):
    review = art / 'art/fox_uchigatana/v02/review/fox_wearing_miko.geo.json'
    # 神狐的佩刀朝向和鞘口位置取自美术交付的观察工程（那里刀是 2 倍）。
    # 神狐手臂短，刀按 1.6 倍挂，刀长约为手臂的 2.2 倍，拔刀、收刀才够得到；鞘口仍在原处。
    R = euler_matrix((90, -25, 90))
    fox_scale = 1.6
    fox_mouth = np.array([-4.55, 29.3, -6.2]) + R @ np.array([0, 2.3 * 2, 0])
    fox_carry = (fox_mouth - R @ np.array([0, 2.3 * fox_scale, 0]), R)
    fox = bbmodel_skeleton(art / 'MinecraftLogosMod/fox_model/divine_fox/body/divine_fox_body.bbmodel',
                           hand_grip=(0, -2.29, -0.3), weapon_scale=fox_scale, carry=fox_carry)
    # 玩家：鞘口落在左腰前侧，朝向与神狐相同。
    mouth = np.array([-4.4, 15.0, -2.3])
    player = player_skeleton(ROOT / 'src/main/resources/assets/logos_dimension/combat/skeletons/player_tp.json',
                             weapon_scale=1.0, carry=(mouth - R @ np.array([0, 2.3, 0]), R))
    # 第一人称：肩更高、更靠前，武器按长剑第一人称的比例缩小（0.58 / 0.8）。
    shoulder = np.array([0, 4, -3.5])
    fp = player_skeleton(ROOT / 'src/main/resources/assets/logos_dimension/combat/skeletons/player_fp.json',
                         weapon_scale=FP_SCALE, carry=(mouth + shoulder - R @ np.array([0, 2.3 * FP_SCALE, 0]), R))
    # 第一人称：根骨与长剑第一人称相同（身体靠右，双臂从右下伸进画面）；
    # 打刀的架势比长剑低，手的目标统一前移、抬高（reach，模型像素），让刀身留在画面里、手肘不贴镜头。
    fp.root = ((15.13, -1.82, 0), (0, 0, 12))
    fp.reach = np.array([-3.0, 3.0, -5.0])
    player.tag, fp.tag, fox.tag = 'player', 'fp', 'fox'
    return player, fp, fox, review

# ---------------------------------------------------------------- 预览


def mesh(skeleton, W):
    s = skeleton.weapon_scale
    out = {}
    for name in ('Weapon', 'Sheath'):
        M = W[name] @ np.diag([1, 1, 1, 1.0])
        p = skeleton.weapon_pivot
        T = np.eye(4); T[:3, 3] = p
        S = np.diag([s, s, s, 1.0])
        out[name] = M @ T @ S
    return out


def frames_for(clip, n):
    times = [clip['length'] * i / (n - 1) for i in range(n)]
    for name, t in (clip.get('markers') or {}).items():
        if name in ('contact', 'cancel'):
            times.append(t)
    return sorted(set(round(t, 3) for t in times))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    art, out = Path(args[0]), Path(args[1])
    only = set(args[2:])
    gif = '--gif' in sys.argv
    out.mkdir(parents=True, exist_ok=True)
    player, fp, fox, review = skeletons(art)
    weapon = json.load(open(art / 'art/fox_uchigatana/v02/resources/assets/logos_dimension/combat/weapons/fox_uchigatana_ink.json', encoding='utf-8'))
    tex = np.asarray(Image.open(art / 'art/fox_uchigatana/v02/textures/fox_uchigatana_ink.png').convert('RGBA')).astype(np.float32)
    fox_geo = Geo(review, str(review).replace('.geo.json', '.png'), skip=('Weapon', 'Sheath'))
    all_clips = clips()
    baked = {}
    for who, sk in (('player', player), ('fp', fp), ('fox', fox)):
        baker = Baker(sk)
        for clip in all_clips:
            baked[(who, clip['name'])] = baker.bake(clip, 60)
        worst = {}
        for clip_name, t, side, over in baker.warnings:
            key = (clip_name, side)
            lo, hi, m = worst.get(key, (t, t, 0))
            worst[key] = (min(lo, t), max(hi, t), max(m, over))
        for (clip_name, side), (lo, hi, m) in sorted(worst.items()):
            print(f'{who} {clip_name} {side} 手够不着 {lo:.2f}–{hi:.2f}s，最多差 {m:.2f} 臂长')
        if who == 'player':
            # 没有腕关节，刀柄穿过拳心的方向会随动作偏一点；列出每段动作的平均与最大偏角。
            by = {}
            for clip_name, t, side, r in baker.residuals:
                by.setdefault((clip_name, side), []).append(r)
            for (clip_name, side), rs in sorted(by.items()):
                print(f'{who} {clip_name} {side} 刀柄偏角 平均 {sum(rs) / len(rs):.0f}° 最大 {max(rs):.0f}°')
    # 玩家的两份动作写进附属资源；只导出玩家渲染用得到的骨骼。
    bones = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
             'LeftArm', 'LeftForeArm', 'LeftHand', 'Weapon', 'Sheath']
    assets = ROOT / 'src/main/resources/assets/logos_dimension/combat/animations'
    for who, sk, view in (('player', player, 'tp'), ('fp', fp, 'fp')):
        data = bedrock(all_clips, {c['name']: baked[(who, c['name'])] for c in all_clips}, sk,
                       bones + (['Root'] if view == 'fp' else []))
        text = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        (assets / f'uchigatana_{view}.animation.json').write_text(text, encoding='utf-8')
    cams = {'player': [Camera(-35, 8, 6.2, (0, 16, -2), (230, 270)), Camera(80, 6, 6.2, (0, 16, -2), (230, 270))],
            'fox': [Camera(-35, 8, 3.6, (0, 26, -3), (230, 270)), Camera(80, 6, 3.6, (0, 26, -3), (230, 270))]}

    def draw(who, sk, clip, t, cam, trail_from):
        frames = baked[(who, clip['name'])]
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
        pose = {n: (p, euler_matrix(e)) for n, (p, e) in frames[i][1].items()}
        W = sk.world(pose)
        m = mesh(sk, W)
        quads = weapon_quads(weapon, tex, m['Weapon'], 'weapon') + weapon_quads(weapon, tex, m['Sheath'], 'sheath')
        quads += mannequin_quads(W) if who == 'player' else fox_geo.quads(pose)
        trail = []
        for j in range(max(0, i - trail_from), i + 1):
            pj = {n: (p, euler_matrix(e)) for n, (p, e) in frames[j][1].items()}
            trail.append((mesh(sk, sk.world(pj))['Weapon'] @ TIP)[:3])
        return render(quads, cam, trail=trail if len(trail) > 1 else None)

    for clip in all_clips:
        if only and clip['name'] not in only:
            continue
        rows = []
        times = frames_for(clip, 8)
        for who, sk in (('player', player), ('fox', fox)):
            for v, cam in enumerate(cams[who]):
                panels = []
                for t in times:
                    tag = next((n for n, mt in (clip.get('markers') or {}).items() if abs(mt - t) < 1e-3), '')
                    panels.append((f'{t:.2f}s {tag}', draw(who, sk, clip, t, cam, 15)))
                rows.append((('玩家' if who == 'player' else '神狐') + ('·左前' if v == 0 else '·右侧'), panels))
        sheet(rows, f"{clip['name']}  {clip['length']}s  标记 {clip.get('markers', {})}", out / f"{clip['name']}.png")
        if gif:
            images = []
            for i in range(int(clip['length'] * 20) + 1):
                t = i / 20
                a = draw('player', player, clip, t, cams['player'][0], 15)
                b = draw('fox', fox, clip, t, cams['fox'][0], 15)
                im = Image.new('RGB', (a.width * 2, a.height)); im.paste(a, (0, 0)); im.paste(b, (a.width, 0))
                images.append(im)
            images[0].save(out / f"{clip['name']}.gif", save_all=True, append_images=images[1:], duration=50,
                           loop=0)
        print('rendered', clip['name'])


if __name__ == '__main__':
    main()
