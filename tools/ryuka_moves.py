# -*- coding: utf-8 -*-
"""流火（魔力凝成的打刀）的动作编排，以及烘焙、导出与预览。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/ryuka_moves.py <美术目录> <输出目录> [动作名…] [--gif]

<美术目录> 是放 art/liuhuo 的上级目录（本机为 “Minecraft MOD”）。编排坐标见 combat_author.py 开头：
刀与手在躯干坐标里，以两肩中点为原点、臂长为单位；前 -Z、上 +Y、角色右手 +X。blade 是刀尖方向，edge 是刃的朝向。

这把刀没有重量：每一刀从起点到终点只用两三刻（arc），刃始终朝着刀走的方向；挥到终点定住一下（hold）再接下一刀，
不然看上去像乱挥。轻刀所以单手持握，左手空出来做平衡。
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

from combat_author import Baker, bedrock, euler_matrix, npc_parried, player_skeleton
from combat_render import Camera, mannequin_quads, render, sheet, weapon_quads

ROOT = Path(__file__).resolve().parent.parent
# 刀尖（武器网格单位），预览画轨迹用。
TIP = np.array([-2.25, 18.0, 0, 1])

# ---------------------------------------------------------------- 常用姿势

HANG_L = (-0.42, -0.98, 0.04)
HANG_R = (0.42, -0.98, 0.04)
# 待机：右手单手垂在身侧，刀尖斜指右前下（没有手腕时这样握得正）；左手放松。
IDLE = dict(grip=(0.44, -0.86, -0.16), blade=(0.32, -0.16, -0.93), edge=(0.2, -0.97, 0.05),
            up=(0, 8, 0), hips=(0, -0.03, 0), lmode='free', lhand=(-0.44, -0.92, -0.06),
            rfoot=(0.16, 0, -0.18, -10), lfoot=(-0.2, 0, 0.16, 20),
            # 第一人称看不到腰下，刀在右前方、刀尖斜指前上。
            fp=dict(grip=(0.34, -0.5, -0.62), blade=(-0.12, 0.3, -0.95), edge=(0.2, -0.95, -0.3)))
# 格挡：刀竖在身前略向左斜，左掌抵住刀背。
GUARD = dict(grip=(0.22, -0.38, -0.72), blade=(-0.32, 0.94, -0.1), edge=(0.05, 0.1, -1), up=(0, -6, 0), hips=(0, -0.06, 0),
             lmode='mune', mune=10.0, rfoot=(0.2, 0, -0.18, -5), lfoot=(-0.24, 0, 0.18, 20))


def k(t, base=None, **kw):
    key = dict(base or {})
    key.update(kw)
    key['t'] = t
    return key


def unit(v):
    v = np.asarray(v, float)
    return v / np.linalg.norm(v)


def slerp(a, b, u):
    a, b = unit(a), unit(b)
    d = float(np.clip(a @ b, -1, 1))
    w = math.acos(d)
    if w < 1e-5:
        return a
    return (math.sin((1 - u) * w) * a + math.sin(u * w) * b) / math.sin(w)


def arc(t0, t1, grips, blades, n=6, hold=None, **extra):
    """一刀：刀尖方向 blades[0] → blades[1] → blades[2]（沿球面转过去），握点沿过 grips[1] 的弧线走，
    刃始终朝着刀尖走的方向。t0 到 t1 中间快、两头慢；hold 给出时在终点定住到这一刻。extra 加在每一帧上。"""
    g0, g1, g2 = (np.asarray(g, float) for g in grips)
    c = 2 * g1 - (g0 + g2) / 2

    def blade(u):
        return slerp(blades[0], blades[1], u * 2) if u <= .5 else slerp(blades[1], blades[2], u * 2 - 1)
    keys = []
    for i in range(n + 1):
        s = i / n
        u = s * s * (3 - 2 * s)
        du = 1e-3
        b = blade(u)
        travel = blade(min(1, u + du)) - blade(max(0, u - du))
        edge = travel - (travel @ b) * b
        if np.linalg.norm(edge) < 1e-6:
            edge = np.cross(b, (0, 1, 0))
        grip = (1 - u) ** 2 * g0 + 2 * u * (1 - u) * c + u * u * g2
        keys.append(k(t0 + (t1 - t0) * s, grip=tuple(grip), blade=tuple(b), edge=tuple(unit(edge)), **extra))
    if hold is not None:
        keys.append(dict(keys[-1], t=hold, ease='hold'))
    return keys


def last(keys):
    """一刀定住时的握点、刀尖、刃（下一刀从这里起手）。"""
    key = keys[-1]
    return dict(grip=key['grip'], blade=key['blade'], edge=key['edge'])

# ---------------------------------------------------------------- 动作


def clips():
    out = []

    out.append(dict(name='idle', length=2.4, loop='loop', keys=[
        k(0.0, IDLE),
        k(1.2, IDLE, grip=(0.44, -0.83, -0.2), up=(2, 9, 0), hips=(0, -0.04, 0), lhand=(-0.44, -0.9, -0.08)),
        k(2.4, IDLE),
    ]))

    # 凝聚：右手在胸前虚握，刀从柄往尖长出来；左手两指从刀镡沿刀背抹到刀身中段，最后刀一抖收进待机。
    hold = dict(grip=(0.2, -0.36, -0.62), blade=(-0.1, 0.92, -0.38), edge=(0.05, 0.38, 0.92))
    out.append(dict(name='manifest', length=0.9, loop='once', markers={'cancel': 0.7}, keys=[
        # 右手一开始就握在柄上（刀从拳中长出来），从垂手的位置抬到胸前
        k(0.0, grip=(0.44, -0.9, -0.1), blade=(0.2, 0.6, -0.78), edge=(0.05, 0.78, 0.6), lmode='free', lhand=HANG_L, up=(0, 0, 0)),
        k(0.14, hold, up=(0, -10, 0), lhand=(-0.12, -0.38, -0.66), hips=(0, -0.03, 0)),
        k(0.22, lhand=(-0.08, -0.22, -0.7)),
        k(0.5, hold, lhand=(-0.16, 0.5, -0.86), up=(0, -14, 0), ease='hold'),
        *arc(0.56, 0.66, [hold['grip'], (0.36, -0.5, -0.5), IDLE['grip']],
             [hold['blade'], (0.5, 0.2, -0.85), IDLE['blade']], n=4),
        k(0.62, lhand=(-0.4, -0.7, -0.1), up=(0, 12, 0)),
        k(0.9, IDLE),
    ]))

    # 一：右向左横一闪。刀先拉到右后方蓄一下，横扫过身前，定在左肩外。
    s1 = arc(0.05, 0.14, [(0.62, -0.28, 0.08), (0.08, -0.26, -0.82), (-0.52, -0.3, -0.36)],
             [(0.85, 0.06, 0.52), (0, 0.02, -1), (-0.9, 0.04, 0.42)], hold=0.22)
    out.append(dict(name='slash_1', length=0.34, loop='once', markers={'contact': 0.1, 'cancel': 0.22}, keys=[
        k(0.0, IDLE),
        k(0.04, up=(0, -34, 0), lhand=(-0.36, -0.42, -0.58), hips=(0, -0.06, 0)),
        *s1,
        k(0.14, up=(0, 40, 0), lhand=(-0.62, -0.56, 0.32)),
        k(0.34, up=(0, 36, 0), lhand=(-0.6, -0.62, 0.28)),
    ]))

    # 二：反手逆袈裟，左下撩到右上，刀举过右肩定住。
    s2 = arc(0.04, 0.13, [last(s1)['grip'], (0.06, -0.22, -0.84), (0.48, 0.22, -0.36)],
             [(-0.72, -0.62, 0.3), (0.04, 0.36, -0.93), (0.58, 0.76, 0.3)], hold=0.22)
    out.append(dict(name='slash_2', length=0.34, loop='once', markers={'contact': 0.1, 'cancel': 0.22}, keys=[
        k(0.0, last(s1), up=(0, 36, 0), lmode='free', lhand=(-0.6, -0.62, 0.28)),
        k(0.04, up=(0, 26, 0), lhand=(-0.5, -0.66, 0.1), hips=(0, -0.08, 0)),
        *s2,
        k(0.13, up=(0, -30, 0), lhand=(-0.48, -0.3, -0.5), hips=(0, -0.03, 0)),
        k(0.34, up=(0, -28, 0), lhand=(-0.46, -0.34, -0.46)),
    ]))

    # 三：右上袈裟斩到左下，身体压低前倾，刀尖停在左前方的地面上方。
    s3 = arc(0.05, 0.15, [last(s2)['grip'], (0.04, -0.24, -0.88), (-0.34, -0.74, -0.52)],
             [(0.46, 0.86, 0.22), (-0.08, 0.08, -1), (-0.52, -0.74, -0.42)], hold=0.26)
    out.append(dict(name='slash_3', length=0.38, loop='once', markers={'contact': 0.12, 'cancel': 0.26}, keys=[
        k(0.0, last(s2), up=(0, -28, 0), lmode='free', lhand=(-0.46, -0.34, -0.46)),
        k(0.05, up=(-6, -34, 0), lhand=(-0.4, -0.2, -0.56)),
        *s3,
        k(0.15, up=(14, 30, 0), hips=(0, -0.14, -0.06), lhand=(-0.64, -0.5, 0.3),
          rfoot=(0.16, 0, -0.42, -8), lfoot=(-0.2, 0, 0.26, 22)),
        k(0.38, up=(12, 28, 0), hips=(0, -0.13, -0.05), lhand=(-0.62, -0.52, 0.28)),
    ]))

    # 四：逆胴。三停在左下方，借势把刀收到左后方平举，腰往右一拧，刀从左到右平着扫过身前（与一相反），
    # 定在右侧；第一、第三人称同一套，不转身（2026-11-08 维护者：不要转一圈）。
    wind = arc(0.02, 0.07, [last(s3)['grip'], (-0.46, -0.56, -0.5), (-0.5, -0.36, -0.28)],
               [last(s3)['blade'], (-0.8, -0.42, -0.42), (-0.92, -0.04, 0.38)], n=3)
    s4 = arc(0.09, 0.19, [(-0.5, -0.36, -0.28), (0.06, -0.34, -0.88), (0.6, -0.3, -0.22)],
             [(-0.92, -0.04, 0.38), (0, -0.02, -1), (0.9, 0.02, 0.42)], hold=0.36)
    out.append(dict(name='slash_4', length=0.5, loop='once', markers={'contact': 0.16, 'cancel': 0.36}, keys=[
        k(0.0, last(s3), up=(12, 28, 0), hips=(0, -0.13, -0.05), lmode='free', lhand=(-0.62, -0.52, 0.28)),
        *wind,
        k(0.07, up=(4, 40, 0), hips=(0, -0.1, -0.02), lhand=(-0.22, -0.48, -0.56)),
        *s4,
        k(0.19, up=(6, -38, 0), hips=(0, -0.11, -0.04), lhand=(-0.64, -0.5, 0.3)),
        k(0.36, up=(6, -36, 0), hips=(0, -0.11, -0.04), lhand=(-0.62, -0.52, 0.28)),
        k(0.5, up=(2, -30, 0), hips=(0, -0.08, -0.02), lhand=(-0.58, -0.56, 0.22)),
    ]))

    # 五：落樱终式。后撤半步、刀举到右后上方蓄一拍，一步踏前从右上斩到左下（放出刀气），定住半拍。
    raise_ = dict(grip=(0.38, 0.42, -0.04), blade=(0.28, 0.72, 0.64), edge=(0.2, -0.6, 0.78))
    s5 = arc(0.16, 0.24, [raise_['grip'], (0.04, -0.12, -0.92), (-0.36, -0.7, -0.56)],
             [raise_['blade'], (-0.04, 0.2, -1), (-0.56, -0.72, -0.4)], hold=0.5)
    out.append(dict(name='slash_5', length=0.62, loop='once', markers={'contact': 0.2, 'cancel': 0.44}, keys=[
        k(0.0, last(s4), up=(2, -30, 0), lmode='free', lhand=(-0.58, -0.56, 0.22), hips=(0, -0.08, -0.02)),
        k(0.1, raise_, up=(-8, -26, 0), hips=(0, -0.04, 0.14), lhand=(-0.36, -0.3, -0.66),
          rfoot=(0.16, 0, 0.04, -10), lfoot=(-0.2, 0, 0.32, 22)),
        k(0.16, raise_, up=(-10, -28, 0), hips=(0, -0.05, 0.16), lhand=(-0.34, -0.26, -0.68), ease='hold'),
        *s5,
        k(0.24, up=(16, 32, 0), hips=(0, -0.16, -0.24), lhand=(-0.66, -0.48, 0.34),
          rfoot=(0.16, 0, -0.66, -6), lfoot=(-0.2, 0, 0.3, 25)),
        k(0.5, up=(15, 30, 0), hips=(0, -0.15, -0.23), lhand=(-0.64, -0.5, 0.32), ease='hold'),
        k(0.62, up=(8, 20, 0), hips=(0, -0.1, -0.14), lhand=(-0.55, -0.66, 0.2)),
    ]))

    # 格挡：刀竖在身前，左掌抵住刀背。按下立刻进入，靠过渡时长做到“马上”。
    out.append(dict(name='guard', length=1.6, loop='loop', keys=[
        k(0.0, GUARD),
        k(0.8, GUARD, grip=(0.22, -0.37, -0.73), hips=(0, -0.065, 0)),
        k(1.6, GUARD),
    ]))

    # 弹刀：受击瞬间刀向右上外崩，左手离开刀背，留出接突刺的架势。
    ready = dict(grip=(0.42, -0.42, -0.45), blade=(0.1, 0.2, -1), edge=(0.2, -1, 0))
    out.append(dict(name='parry', length=0.36, loop='once', markers={'cancel': 0.1}, keys=[
        k(0.0, GUARD),
        k(0.01, lmode='free', lhand=(-0.3, -0.06, -0.68), blend=0.02),
        k(0.04, grip=(0.4, -0.24, -0.6), blade=(0.55, 0.8, -0.24), edge=(0.88, -0.42, -0.2), up=(0, -16, 0),
          lhand=(-0.55, -0.5, -0.25)),
        k(0.12, grip=(0.42, -0.22, -0.58), blade=(0.6, 0.76, -0.22), edge=(0.86, -0.46, -0.18), up=(0, -18, 0), ease='hold'),
        k(0.36, ready, up=(0, -10, 0), lhand=(-0.45, -0.6, -0.1)),
    ]))

    # 弹刀反击：极快的单手平突，左手前引，踏步前冲。
    out.append(dict(name='riposte', length=0.3, loop='once', markers={'contact': 0.06, 'cancel': 0.2}, keys=[
        k(0.0, ready, up=(0, -10, 0), lmode='free', lhand=(-0.45, -0.6, -0.1)),
        k(0.025, grip=(0.5, -0.42, -0.02), blade=(0, 0.04, -1), edge=(1, 0, 0), up=(0, -24, 0),
          lhand=(-0.25, -0.3, -0.75), lhand_rot=(-75, 0, 0), ease='hold', space='hips'),
        k(0.06, grip=(0.14, -0.3, -0.98), blade=(0, 0.02, -1), edge=(1, 0, 0), up=(4, 28, 0),
          lhand=(-0.62, -0.5, 0.35), lhand_rot=(0, 0, 0), hips=(0, -0.12, -0.25), space='hips',
          rfoot=(0.16, 0, -0.75, -6), lfoot=(-0.2, 0, 0.12, 25)),
        k(0.2, grip=(0.14, -0.3, -0.97), blade=(0, 0.03, -1), edge=(1, 0, 0), up=(4, 26, 0), space='hips',
          hips=(0, -0.12, -0.25), ease='hold'),
        k(0.3, IDLE),
    ]))

    # 被弹反：刀被磕向右后上方，左手甩开，身子后仰、胸口敞开；顶住一下露出破绽，再收回待机。
    parried_peak = dict(grip=(0.66, 0.10, -0.18), blade=(0.55, 0.76, 0.34), edge=(0.72, -0.62, -0.3), up=(14, -24, 0), chest=(4, -10, 0),
                        lhand=(-0.68, -0.52, 0.16), hips=(0, -0.05, 0.08),
                        fp=dict(grip=(0.5, 0.0, -0.46), blade=(0.5, 0.82, -0.28), edge=(0.8, -0.56, 0.2), up=(6, -10, 0), chest=(0, 0, 0)))
    out.append(dict(name='parried', length=0.5, loop='once', keys=[
        k(0.0, grip=(0.34, -0.16, -0.66), blade=(0.28, 0.6, -0.75), edge=(0.92, -0.2, 0.34), up=(0, -8, 0),
          lmode='free', lhand=(-0.42, -0.5, -0.32)),
        k(0.07, parried_peak, ease='hold', rfoot=(0.16, 0, 0.0, -10), lfoot=(-0.2, 0, 0.3, 25)),
        k(0.32, parried_peak, grip=(0.62, 0.02, -0.2), blade=(0.5, 0.72, 0.46), up=(11, -22, 0), ease='hold',
          fp=dict(parried_peak['fp'], grip=(0.48, -0.08, -0.46))),
        k(0.5, IDLE),
    ]))
    out.append(npc_parried(out[-1]))

    # 散去：手腕一转把刀甩到右侧，刀从尖往柄散成花瓣；散完右手松开垂下。
    flick = dict(grip=(0.58, -0.5, -0.3), blade=(0.92, -0.3, 0.24))
    out.append(dict(name='dissipate', length=0.6, loop='hold', keys=[
        k(0.0, IDLE),
        *arc(0.04, 0.14, [IDLE['grip'], (0.52, -0.5, -0.5), flick['grip']], [IDLE['blade'], (0.75, -0.2, -0.65), flick['blade']], n=4,
             hold=0.42),
        k(0.14, up=(0, -10, 0), lhand=(-0.5, -0.8, 0.06)),
        k(0.42, up=(0, -6, 0)),
        k(0.48, rmode='free', rhand=HANG_R, lmode='free', lhand=HANG_L, blend=0.12),
        k(0.6, up=(0, 0, 0), hips=(0, 0, 0)),
    ]))
    for clip in out:
        clip.setdefault('stance', {'rfoot': IDLE['rfoot'], 'lfoot': IDLE['lfoot']})
    return out

# ---------------------------------------------------------------- 骨架

# 流火网格的 scale（weapons/ryuka.json）：骨架的武器缩放乘上它。第三人称 0.8 × 1.25 = 1，第一人称 0.58 × 1.25。
WEAPON_SCALE = 1.25
FP_SCALE = 0.58 * WEAPON_SCALE
# 握点在刀柄上的位置（武器网格单位，原点在刀镡与刀柄之间，柄在 -5.75 到 0）：右手贴着刀镡，左手在柄尾。
GRIPS = (-1.4, -4.2)


def skeletons():
    # 流火没有刀鞘；鞘骨骼照常算（不画），位置借打刀的佩刀处。
    R = euler_matrix((90, -25, 90))
    mouth = np.array([-4.4, 15.0, -2.3])
    player = player_skeleton(ROOT / 'src/main/resources/assets/logos_dimension/combat/skeletons/player_tp.json',
                             weapon_scale=1.0, carry=(mouth - R @ np.array([0, 2.3, 0]), R))
    shoulder = np.array([0, 4, -3.5])
    fp = player_skeleton(ROOT / 'src/main/resources/assets/logos_dimension/combat/skeletons/player_fp.json',
                         weapon_scale=FP_SCALE, carry=(mouth + shoulder - R @ np.array([0, 2.3 * FP_SCALE, 0]), R))
    # 第一人称取景与打刀相同：根骨同长剑第一人称，手的目标前移、抬高，刀留在画面里。
    fp.root = ((15.13, -1.82, 0), (0, 0, 12))
    fp.reach = np.array([-3.0, 3.0, -5.0])
    player.body = ((-4.4, 12.0, -2.5), (4.4, 21.0, 2.5))
    for sk, tag in ((player, 'player'), (fp, 'fp')):
        sk.grips = GRIPS
        sk.tag = tag
    return player, fp

# ---------------------------------------------------------------- 预览


def mesh(skeleton, W):
    s = skeleton.weapon_scale
    T = np.eye(4); T[:3, 3] = skeleton.weapon_pivot
    return W['Weapon'] @ T @ np.diag([s, s, s, 1.0])


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
    player, fp = skeletons()
    weapon = json.load(open(ROOT / 'src/main/resources/assets/logos_dimension/combat/weapons/ryuka.json', encoding='utf-8'))
    tex = np.asarray(Image.open(ROOT / 'src/main/resources/assets/logos_dimension/textures/item/ryuka_rig.png').convert('RGBA')).astype(np.float32)
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
            print(f'{who} {clip_name} {side} 手够不着 {lo:.2f}–{hi:.2f}s，最多差 {m:.2f} 臂长')
        if who == 'player':
            by = {}
            for clip_name, t, side, r in baker.residuals:
                by.setdefault((clip_name, side), []).append(r)
            for (clip_name, side), rs in sorted(by.items()):
                if side == 'Right':
                    print(f'{who} {clip_name} {side} 刀柄偏角 平均 {sum(rs) / len(rs):.0f}° 最大 {max(rs):.0f}°')
    bones = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
             'LeftArm', 'LeftForeArm', 'LeftHand', 'Weapon', 'Sheath']
    assets = ROOT / 'src/main/resources/assets/logos_dimension/combat/animations'
    for who, sk, view in (('player', player, 'tp'), ('fp', fp, 'fp')):
        # 第三人称的 Root 只带转身（现在没有招式转身，留着接口），由客户端转原版身体；第一人称的 Root 是取景。
        data = bedrock(all_clips, {c['name']: baked[(who, c['name'])] for c in all_clips}, sk, bones + ['Root'])
        text = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        (assets / f'ryuka_{view}.animation.json').write_text(text, encoding='utf-8')
    cams = [Camera(-35, 8, 6.2, (0, 16, -2), (230, 270)), Camera(80, 6, 6.2, (0, 16, -2), (230, 270)),
            Camera(180, 70, 5.0, (0, 14, 0), (230, 270))]

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
        times = frames_for(clip, 8)
        for v, cam in enumerate(cams):
            panels = []
            for t in times:
                tag = next((n for n, mt in (clip.get('markers') or {}).items() if abs(mt - t) < 1e-3), '')
                panels.append((f'{t:.2f}s {tag}', draw(clip, t, cam, 12)))
            rows.append((('左前', '右侧', '俯视')[v], panels))
        sheet(rows, f"流火 {clip['name']}  {clip['length']}s  标记 {clip.get('markers', {})}", out / f"{clip['name']}.png")
        if gif:
            images = []
            for i in range(int(clip['length'] * 40) + 1):
                t = i / 40
                a = draw(clip, t, cams[0], 8)
                b = draw(clip, t, cams[2], 8)
                im = Image.new('RGB', (a.width * 2, a.height)); im.paste(a, (0, 0)); im.paste(b, (a.width, 0))
                images.append(im)
            # 放慢一倍看：每帧 1/40 秒的动作，播 50 毫秒
            images[0].save(out / f"{clip['name']}.gif", save_all=True, append_images=images[1:], duration=50, loop=0)
        print('rendered', clip['name'])


if __name__ == '__main__':
    main()
