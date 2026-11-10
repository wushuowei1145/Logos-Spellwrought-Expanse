# -*- coding: utf-8 -*-
"""九尾妖狐 白梅（人形）的动作编排：过场（跪坐、起身、单膝跪地、拍地）与第一阶段的流火招式，烘焙到 Boss 自己的骨架上。

用法（需要 numpy、pillow）：
    python tools/shiraume_moves.py <Minecraft MOD 目录> [--preview <输出目录> [动作名…]]

<Minecraft MOD 目录> 下面要有 MinecraftLogosMod/fox_model/sealed_nine_tailed_fox（维护者的模型目录）。
写出 `fox_model/sealed_nine_tailed_fox/Animation/shiraume_moves.animation.json`，再由 tools/shiraume_assets.py 并进附属的动画文件。

编排坐标同 combat_author.py：刀与手在躯干坐标里，以两肩中点为原点、臂长为单位；前 -Z、上 +Y、角色右手 +X。
招式的节奏见 docs/SHIRAUME_BOSS.md 第 4 节：明显的起手、极快的一下挥出、挥到底定住。
标记：contact 命中、cancel 可接下一招、eye_flash 双眼红光（即将挥出），其余见各动作。服务端的招式文件
（data/logos_dimension/combat/movesets/shiraume.json）照这里的时刻写，ShiraumeMovesTest 核对两边一致。
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

from combat_author import Baker, bbmodel_skeleton, bedrock, euler_matrix, matrix_euler
from combat_render import Camera, Geo, render, sheet, weapon_quads
from ryuka_moves import GRIPS, TIP, arc, k, last, unit

ROOT = Path(__file__).resolve().parent.parent
MODEL = Path('MinecraftLogosMod/fox_model/sealed_nine_tailed_fox')
SOURCE = MODEL / 'source/sealed_nine_tailed_fox_4.bbmodel'
OUT = MODEL / 'Animation/shiraume_moves.animation.json'
#: 流火挂在 Boss 手上的倍数（武器网格单位 → 模型像素）。刀全长约 23.75 单位，按 2.4 倍约 57 像素，
#: 是 Boss 臂长（27.3）的 2.1 倍，与玩家拿流火的比例相近（玩家约 2 倍）。
WEAPON_SCALE = 2.4
#: 拳心相对手部骨骼枢轴的位置（模型像素，手的局部坐标）：手掌方块 y 34.4–45.0，枢轴 44.6。
HAND_GRIP = (0, -4.9, -0.3)
UPPER = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand', 'LeftArm', 'LeftForeArm', 'LeftHand', 'Weapon']
LOWER = ['AllBody', 'DownBody', 'RightLeg', 'RightLowerLeg', 'RightFoot', 'LeftLeg', 'LeftLowerLeg', 'LeftFoot']
#: 下摆八片（Skirt 下，各分上下两段）：0–3 在角色右侧，4–7 在左侧；0、7 在正前，3、4 在正后。
PANELS = [(i, side, face) for i, side, face in (
    (0, 'Right', 'front'), (1, 'Right', 'side_front'), (2, 'Right', 'side_back'), (3, 'Right', 'back'),
    (4, 'Left', 'back'), (5, 'Left', 'side_back'), (6, 'Left', 'side_front'), (7, 'Left', 'front'))]


def edge(blade, toward):
    """刃的朝向：toward 去掉沿刀身的分量。"""
    b = unit(blade)
    t = np.asarray(toward, float)
    e = t - (t @ b) * b
    return tuple(unit(e))


def stance(clip, **feet):
    clip['stance'] = {'rfoot': feet.get('rfoot', IDLE['rfoot']), 'lfoot': feet.get('lfoot', IDLE['lfoot'])}
    return clip

# ---------------------------------------------------------------- 常用姿势

# 待机：右手单手持刀垂在身侧，刀尖斜指右前下方，左手放松；右脚在前的半身。
_IDLE_BLADE = (0.30, -0.55, -0.78)
IDLE = dict(grip=(0.40, -0.92, -0.22), blade=_IDLE_BLADE, edge=edge(_IDLE_BLADE, (0, -1, 0)), up=(2, 10, 0), hips=(0, -0.06, 0),
            rmode='sword', lmode='free', lhand=(-0.40, -0.92, -0.02), rfoot=(0.14, 0, -0.22, -6), lfoot=(-0.20, 0, 0.18, 22))
# 跪坐：臀部落在脚跟上，双手放在大腿上，低着头。刀还没有凝出来（客户端不画），握点放在右腿上。
SEIZA = dict(grip=(0.2, -0.86, -0.45), blade=(0, 0, -1), edge=(0, -1, 0), up=(6, 0, 0), hips=(0, -0.63, 0), head=(-24, 0, 0),
             rmode='free', rhand=(0.21, -0.86, -0.45), lmode='free', lhand=(-0.21, -0.86, -0.45),
             rfoot=(0.088, -0.02, 0.07, 0), lfoot=(-0.088, -0.02, 0.07, 0))
# 双手举刀过头（上段），刀尖斜指身后上方。
_JODAN_BLADE = (0, 0.62, 0.78)
JODAN = dict(grip=(0.08, 0.62, -0.22), blade=_JODAN_BLADE, edge=edge(_JODAN_BLADE, (0, 1, -0.6)), up=(-8, 0, 0), hips=(0, -0.05, 0),
             rmode='sword', lmode='two', rfoot=(0.14, 0, -0.26, -4), lfoot=(-0.18, 0, 0.2, 14))
# 右肩上方举刀（袈裟斩的起手）。
_HIGH_R_BLADE = (-0.25, 0.55, 0.8)
HIGH_R = dict(grip=(0.52, 0.42, 0.02), blade=_HIGH_R_BLADE, edge=edge(_HIGH_R_BLADE, (0.2, 0.8, -0.6)))
# 左肩上方举刀（燕返第二刀的起手）。
_HIGH_L_BLADE = (0.3, 0.55, 0.78)
HIGH_L = dict(grip=(-0.42, 0.40, -0.05), blade=_HIGH_L_BLADE, edge=edge(_HIGH_L_BLADE, (-0.2, 0.8, -0.6)))
# 刀压到左下身后（逆袈裟的起手）。
_LOW_L_BLADE = (-0.6, -0.55, 0.58)
LOW_L = dict(grip=(-0.35, -0.85, -0.15), blade=_LOW_L_BLADE, edge=edge(_LOW_L_BLADE, (0.2, 0.3, -0.9)))
# 刀拉到右后方（横一文字的起手），刃朝前。
_BACK_R_BLADE = (0.8, -0.05, 0.6)
BACK_R = dict(grip=(0.62, -0.42, 0.25), blade=_BACK_R_BLADE, edge=edge(_BACK_R_BLADE, (-0.6, 0, -0.8)))
# 刀收在右腰，刀尖朝前、刃朝上（突刺的起手）。
_POISE_BLADE = (-0.05, 0.05, -1)
POISE = dict(grip=(0.45, -0.55, 0.35), blade=_POISE_BLADE, edge=edge(_POISE_BLADE, (0, 1, 0)))
# 刀拖在右后下方（冲刺、投技的蓄势）。
_TRAIL_BLADE = (0.3, -0.42, 0.86)
TRAIL = dict(grip=(0.5, -0.72, 0.3), blade=_TRAIL_BLADE, edge=edge(_TRAIL_BLADE, (0, -1, 0)))
# 刀尖插在身前地上撑着（单膝跪地、喘息）。
_PLANT_BLADE = (0.1, -0.8, -0.59)
PLANT = dict(grip=(0.4, -0.32, -0.55), blade=_PLANT_BLADE, edge=edge(_PLANT_BLADE, (0, 0, -1)))


def hold_key(t, base):
    return dict(k(t, base), ease='hold')


def recover(t0, t1, frm=None):
    """从挥到底的姿势收回待机：t0 开始动，t1 回到待机。"""
    keys = [k(t1, IDLE)]
    if frm is not None:
        keys.insert(0, k(t0, frm))
    return keys

# ---------------------------------------------------------------- 动作


def clips():
    out = []
    add = out.append

    # ---- 过场与身体
    add(dict(name='seiza', length=2.4, loop='loop', legs=True, keys=[
        k(0.0, SEIZA), k(1.2, SEIZA, up=(9, 0, 0), head=(-22, 0, 0)), k(2.4, SEIZA)]))

    # 起身：抬头 → 右膝立起 → 右手往身侧一握，流火从拳中长出来（1.4–2.8 秒，客户端按标记画凝聚）→ 站起、落到持刀的站姿
    reach = dict(grip=(0.62, -0.62, -0.42), blade=(0.35, -0.75, -0.56), edge=edge((0.35, -0.75, -0.56), (0, 0, -1)))
    add(dict(name='rise', length=3.0, loop='hold', legs=True, markers={'manifest': 1.4, 'manifest_end': 2.8}, keys=[
        k(0.0, SEIZA),
        k(0.6, SEIZA, head=(0, 0, 0), up=(2, 0, 0)),
        k(1.1, SEIZA, hips=(0, -0.42, -0.04), up=(-6, 0, 0), head=(-4, 0, 0), rfoot=(0.12, 0, -0.32, -4),
          lhand=(-0.24, -0.9, -0.35)),
        k(1.4, reach, rmode='sword', blend=0.3, hips=(0, -0.4, -0.04), up=(-4, -12, 0), head=(0, -10, 0),
          lhand=(-0.24, -0.88, -0.35)),
        k(2.0, reach, hips=(0, -0.22, 0), up=(0, -6, 0), lfoot=(-0.2, 0, 0.12, 18), head=(0, -6, 0)),
        k(2.6, IDLE, hips=(0, -0.08, 0), head=(0, 0, 0)),
        k(3.0, IDLE),
    ]))

    # 单膝跪地（变身）：左膝着地、右脚在前，刀尖插进身前的地面撑着，低头
    kneel = dict(PLANT, up=(-14, -6, 0), hips=(0, -0.45, 0.02), head=(-18, 0, 0), lmode='free', lhand=(-0.12, -0.95, -0.55),
                 rfoot=(0.15, 0, -0.32, -4), lfoot=(-0.15, 0.02, 0.35, 10))
    add(dict(name='kneel', length=1.4, loop='hold', legs=True, keys=[
        k(0.0, IDLE),
        k(0.35, IDLE, hips=(0, -0.2, 0.04), up=(-6, 0, 0), lfoot=(-0.17, 0, 0.3, 14)),
        k(0.8, kneel, blend=0.2),
        k(1.4, kneel, up=(-18, -6, 0)),
    ]))
    add(dict(name='kneel_hold', length=1.6, loop='loop', legs=True, keys=[
        k(0.0, kneel, up=(-18, -6, 0)), k(0.8, kneel, up=(-15, -6, 0)), k(1.6, kneel, up=(-18, -6, 0))]))
    # 拍地：左手高高抬起，一掌拍在身前的地上（0.5 秒，冲击波与光柱从这一刻起）
    add(dict(name='slam', length=1.0, loop='hold', legs=True, markers={'contact': 0.5}, keys=[
        k(0.0, kneel, up=(-18, -6, 0)),
        k(0.36, kneel, up=(8, 6, 0), head=(4, 0, 0), lhand=(-0.38, 0.5, -0.28)),
        k(0.5, kneel, up=(-40, 4, 0), hips=(0, -0.5, 0.02), head=(-6, 0, 0), lhand=(-0.28, -0.95, -0.72)),
        hold_key(1.0, dict(kneel, up=(-38, 4, 0), hips=(0, -0.5, 0.02), head=(-4, 0, 0), lhand=(-0.28, -0.95, -0.72))),
    ]))

    # ---- 待机（只有上半身：走动时腿交给行走动作）
    add(dict(name='idle', length=2.4, loop='loop', legs=False, keys=[
        k(0.0, IDLE), k(1.2, IDLE, grip=(0.40, -0.89, -0.24), up=(4, 11, 0)), k(2.4, IDLE)]))

    # ---- 独立招式
    # 袈裟斩：刀举到右肩上方，拧身蓄住；一下斩到左下，压低身子定住
    s = arc(0.64, 0.76, [HIGH_R['grip'], (0.18, -0.1, -0.95), (-0.4, -0.82, -0.42)],
            [HIGH_R['blade'], (-0.05, 0.2, -0.98), (-0.62, -0.62, -0.48)], hold=1.0)
    add(dict(name='kesa', length=1.5, loop='once', legs=True, markers={'eye_flash': 0.55, 'contact': 0.7, 'cancel': 1.05}, keys=[
        k(0.0, IDLE),
        k(0.15, grip=(0.5, -0.2, -0.3), blade=(0.4, 0.6, -0.7), edge=edge((0.4, 0.6, -0.7), (0, 1, 0))),
        k(0.45, HIGH_R, up=(0, -28, -4), hips=(0, -0.08, 0), lhand=(-0.35, -0.3, -0.55)),
        hold_key(0.62, dict(HIGH_R, up=(0, -30, -4))),
        *s,
        k(0.7, up=(0, 34, 4), hips=(0, -0.16, 0), rfoot=(0.18, 0, -0.36, -8), lhand=(-0.55, -0.6, 0.2)),
        k(1.0, up=(0, 32, 4), hips=(0, -0.15, 0)),
        *recover(1.0, 1.5),
    ]))

    # 逆袈裟：刀压到左下身后、沉腰；从左下撩到右上
    s = arc(0.52, 0.64, [LOW_L['grip'], (0.05, -0.45, -0.95), (0.55, 0.35, -0.25)],
            [LOW_L['blade'], (0.05, 0.15, -0.99), (0.55, 0.75, 0.35)], hold=0.9)
    add(dict(name='gyaku', length=1.4, loop='once', legs=True, markers={'eye_flash': 0.45, 'contact': 0.58, 'cancel': 0.95}, keys=[
        k(0.0, IDLE),
        k(0.4, LOW_L, up=(-6, 30, 0), hips=(0, -0.16, 0), lhand=(-0.6, -0.5, 0.25)),
        hold_key(0.5, dict(LOW_L, up=(-6, 32, 0))),
        *s,
        k(0.58, up=(4, -30, 0), hips=(0, -0.05, 0), rfoot=(0.16, 0, -0.3, -6), lhand=(-0.3, -0.4, -0.6)),
        k(0.9, up=(6, -28, 0)),
        *recover(0.9, 1.4),
    ]))

    # 横一文字：刀拉到右后方、大幅拧身；横扫过身前定在左边
    s = arc(0.72, 0.86, [BACK_R['grip'], (0.1, -0.45, -0.95), (-0.62, -0.45, 0.1)],
            [BACK_R['blade'], (0.0, -0.05, -1.0), (-0.85, -0.05, 0.52)], hold=1.15)
    add(dict(name='yoko', length=1.6, loop='once', legs=True, markers={'eye_flash': 0.62, 'contact': 0.79, 'cancel': 1.2}, keys=[
        k(0.0, IDLE),
        k(0.55, BACK_R, up=(0, -45, 0), hips=(0, -0.1, 0), lhand=(-0.2, -0.4, -0.7)),
        hold_key(0.7, dict(BACK_R, up=(0, -47, 0))),
        *s,
        k(0.79, up=(0, 50, 0), hips=(0, -0.13, 0), rfoot=(0.2, 0, -0.3, -20), lhand=(-0.62, -0.55, 0.32)),
        k(1.15, up=(0, 48, 0)),
        *recover(1.15, 1.6),
    ]))

    # 突き：刀收到右腰、刀尖朝前，伏低；往前冲出一大步直刺（服务端在 0.58–0.75 秒把她往前送 4 格）
    thrust = dict(grip=(0.12, -0.28, -1.05), blade=(-0.02, 0.03, -1), edge=edge((-0.02, 0.03, -1), (0, 1, 0)))
    add(dict(name='tsuki', length=1.5, loop='once', legs=True,
             markers={'eye_flash': 0.5, 'lunge': 0.58, 'contact': [0.64, 0.72], 'cancel': 1.05}, keys=[
        k(0.0, IDLE),
        k(0.45, POISE, up=(0, -20, 0), hips=(0, -0.18, 0), lhand=(-0.1, -0.35, -0.75), lfoot=(-0.2, 0, 0.3, 24)),
        hold_key(0.55, dict(POISE, up=(0, -22, 0))),
        k(0.64, thrust, up=(0, 10, 0), hips=(0, -0.2, -0.04), rfoot=(0.15, 0, -0.5, -4), lhand=(-0.45, -0.6, 0.2)),
        hold_key(1.0, dict(thrust, up=(0, 8, 0))),
        *recover(1.0, 1.5),
    ]))

    # 一字斩：双手举刀过头（raise），停住（hold，随机 0.5–1.5 秒由服务端定），竖直斩下（cut）
    add(dict(name='ichimonji_raise', length=0.6, loop='hold', legs=True, keys=[
        k(0.0, IDLE), k(0.2, JODAN, grip=(0.2, 0.0, -0.45), blade=(0, 0.95, -0.3), edge=edge((0, 0.95, -0.3), (0, 0, -1))),
        k(0.6, JODAN)]))
    add(dict(name='ichimonji_hold', length=1.0, loop='loop', legs=True, keys=[
        k(0.0, JODAN), k(0.5, JODAN, grip=(0.08, 0.66, -0.2), up=(-10, 0, 0)), k(1.0, JODAN)]))
    cut = arc(0.28, 0.36, [(0.08, 0.7, -0.2), (0.05, 0.1, -1.0), (0.02, -0.85, -0.85)],
              [(0, 0.66, 0.75), (0, 0.35, -0.94), (0, -0.8, -0.6)], hold=0.7)
    for name, flash in (('ichimonji_cut', True), ('ichimonji_cut_combo', False)):
        markers = {'contact': 0.33, 'cancel': 0.75}
        if flash:
            markers['eye_flash'] = 0.02
        add(dict(name=name, length=1.2, loop='once', legs=True, markers=markers, keys=[
            k(0.0, JODAN),
            k(0.25, JODAN, grip=(0.08, 0.7, -0.2), up=(-12, 0, 0), hips=(0, -0.02, 0)),
            *cut,
            k(0.33, up=(-24, 0, 0), hips=(0, -0.24, 0), rfoot=(0.15, 0, -0.42, -4)),
            k(0.7, up=(-22, 0, 0)),
            *recover(0.7, 1.2),
        ]))

    # 转身横扫：背后挨了一刀，猛一拧身把刀拉到右后方（服务端在 0.06–0.36 秒把她整个转过来），沉腰横扫一整圈，
    # 挥到底压低定住；起手只有 0.14 秒，红光一闪刀就到
    s = arc(0.26, 0.38, [BACK_R['grip'], (0.1, -0.5, -0.95), (-0.62, -0.5, 0.1)],
            [BACK_R['blade'], (0.0, -0.08, -1.0), (-0.85, -0.08, 0.52)], hold=0.62)
    add(dict(name='turn_sweep', length=1.0, loop='once', legs=True, markers={'eye_flash': 0.14, 'contact': 0.32, 'cancel': 0.7}, keys=[
        k(0.0, IDLE),
        k(0.14, BACK_R, up=(-6, -55, 0), hips=(0, -0.2, 0), lhand=(-0.2, -0.45, -0.7), lfoot=(-0.24, 0, 0.26, 40)),
        hold_key(0.24, dict(BACK_R, up=(-6, -57, 0), hips=(0, -0.22, 0))),
        *s,
        k(0.32, up=(-4, 58, 0), hips=(0, -0.27, 0), rfoot=(0.24, 0, -0.3, -30), lhand=(-0.65, -0.55, 0.35)),
        k(0.62, up=(-2, 55, 0), hips=(0, -0.24, 0)),
        *recover(0.62, 1.0),
    ]))

    # ---- 连续招式
    # 燕返一：右上劈向左下，挥到底停一下（被弹反时服务端跳过这一停，直接接二）
    t1 = arc(0.42, 0.52, [HIGH_R['grip'], (0.15, -0.1, -0.95), (-0.38, -0.8, -0.42)],
             [HIGH_R['blade'], (-0.05, 0.2, -0.98), (-0.6, -0.62, -0.5)], hold=0.9)
    add(dict(name='tsubame_1', length=0.9, loop='once', legs=True, markers={'eye_flash': 0.36, 'contact': 0.47, 'cancel': 0.9}, keys=[
        k(0.0, IDLE),
        k(0.36, HIGH_R, up=(0, -26, -4), hips=(0, -0.08, 0), lhand=(-0.35, -0.3, -0.55)),
        hold_key(0.4, dict(HIGH_R, up=(0, -27, -4))),
        *t1,
        k(0.47, up=(0, 30, 4), hips=(0, -0.15, 0), rfoot=(0.18, 0, -0.34, -8), lhand=(-0.55, -0.6, 0.2)),
        k(0.9, up=(0, 28, 4)),
    ]))
    # 燕返二：从左下举到左上，劈向右下
    t2 = arc(0.34, 0.44, [HIGH_L['grip'], (-0.1, -0.1, -0.95), (0.48, -0.8, -0.4)],
             [HIGH_L['blade'], (0.05, 0.2, -0.98), (0.62, -0.62, -0.48)], hold=0.9)
    add(dict(name='tsubame_2', length=0.9, loop='once', legs=True, markers={'contact': 0.39, 'cancel': 0.9}, keys=[
        k(0.0, last(t1), up=(0, 28, 4), hips=(0, -0.15, 0), rfoot=(0.18, 0, -0.34, -8), lmode='free', lhand=(-0.55, -0.6, 0.2)),
        k(0.3, HIGH_L, up=(0, 26, 4), hips=(0, -0.08, 0), lhand=(-0.3, -0.35, -0.6)),
        hold_key(0.33, dict(HIGH_L, up=(0, 27, 4))),
        *t2,
        k(0.39, up=(0, -30, -4), hips=(0, -0.15, 0), lfoot=(-0.2, 0, 0.1, 30), lhand=(-0.5, -0.5, 0.15)),
        k(0.9, up=(0, -28, -4)),
    ]))
    # 燕返三：从右下举成上段（连招版，不闪红光）；随后 ichimonji_hold → ichimonji_cut_combo
    add(dict(name='ichimonji_raise_combo', length=0.6, loop='hold', legs=True, keys=[
        k(0.0, last(t2), up=(0, -28, -4), hips=(0, -0.15, 0), lmode='free', lhand=(-0.5, -0.5, 0.15)),
        k(0.25, JODAN, grip=(0.25, 0.1, -0.4), blade=(0.1, 0.95, -0.2), edge=edge((0.1, 0.95, -0.2), (0, 0, -1)), lmode='two', blend=0.2),
        k(0.6, JODAN)]))

    # 三连：袈裟 → 逆袈裟 → 横一文字，节奏快，最后接后跳
    a1 = arc(0.32, 0.42, [HIGH_R['grip'], (0.15, -0.1, -0.95), (-0.4, -0.8, -0.42)],
             [HIGH_R['blade'], (-0.05, 0.2, -0.98), (-0.62, -0.6, -0.5)], hold=0.6)
    add(dict(name='sanren_1', length=0.6, loop='once', legs=True, markers={'eye_flash': 0.26, 'contact': 0.37, 'cancel': 0.6}, keys=[
        k(0.0, IDLE), k(0.28, HIGH_R, up=(0, -26, -4), hips=(0, -0.08, 0), lhand=(-0.35, -0.3, -0.55)),
        *a1, k(0.37, up=(0, 30, 4), hips=(0, -0.15, 0), rfoot=(0.18, 0, -0.34, -8), lhand=(-0.55, -0.6, 0.2)),
        k(0.6, up=(0, 28, 4))]))
    a2 = arc(0.12, 0.22, [last(a1)['grip'], (0.05, -0.45, -0.95), (0.55, 0.35, -0.25)],
             [last(a1)['blade'], (0.05, 0.15, -0.99), (0.55, 0.75, 0.35)], hold=0.5)
    add(dict(name='sanren_2', length=0.5, loop='once', legs=True, markers={'contact': 0.17, 'cancel': 0.5}, keys=[
        k(0.0, last(a1), up=(0, 28, 4), hips=(0, -0.15, 0), rfoot=(0.18, 0, -0.34, -8), lmode='free', lhand=(-0.55, -0.6, 0.2)),
        *a2, k(0.17, up=(4, -30, 0), hips=(0, -0.06, 0), lhand=(-0.3, -0.4, -0.6)), k(0.5, up=(4, -28, 0))]))
    a3 = arc(0.27, 0.39, [BACK_R['grip'], (0.1, -0.45, -0.95), (-0.62, -0.45, 0.1)],
             [BACK_R['blade'], (0.0, -0.05, -1.0), (-0.85, -0.05, 0.52)], hold=0.6)
    add(dict(name='sanren_3', length=0.7, loop='once', legs=True, markers={'contact': 0.33, 'cancel': 0.65}, keys=[
        k(0.0, last(a2), up=(4, -28, 0), hips=(0, -0.06, 0), lmode='free', lhand=(-0.3, -0.4, -0.6)),
        k(0.22, BACK_R, up=(0, -45, 0), hips=(0, -0.1, 0), lhand=(-0.2, -0.4, -0.7)),
        *a3, k(0.33, up=(0, 50, 0), hips=(0, -0.13, 0), rfoot=(0.2, 0, -0.3, -20), lhand=(-0.62, -0.55, 0.32)),
        k(0.7, up=(0, 48, 0))]))

    # 斬り上げ：接在突き后面，刀落到右下，从下往上撩起
    up_cut = arc(0.24, 0.34, [(0.55, -0.95, -0.35), (0.1, -0.35, -1.0), (-0.2, 0.55, -0.4)],
                 [(0.45, -0.75, -0.48), (0.0, 0.3, -0.95), (-0.25, 0.85, 0.45)], hold=0.6)
    add(dict(name='kiriage', length=0.9, loop='once', legs=True, markers={'contact': 0.29, 'cancel': 0.65}, keys=[
        k(0.0, thrust, up=(0, 8, 0), hips=(0, -0.2, -0.04), rfoot=(0.15, 0, -0.5, -4), lmode='free', lhand=(-0.45, -0.6, 0.2)),
        k(0.2, grip=(0.55, -0.95, -0.35), blade=(0.45, -0.75, -0.48), edge=edge((0.45, -0.75, -0.48), (0, 0, -1)), up=(-4, -18, 0),
          hips=(0, -0.22, 0)),
        *up_cut,
        k(0.29, up=(8, 20, 0), hips=(0, -0.06, 0), lhand=(-0.5, -0.4, 0.1)),
        k(0.6, up=(8, 18, 0)),
        *recover(0.6, 0.9),
    ]))

    # ---- 移动
    # 縮地：身子前倾、刀拖在右后下方，一下贴到目标面前（服务端在这 0.45 秒里送她过去）
    add(dict(name='dash', length=0.45, loop='hold', legs=True, keys=[
        k(0.0, IDLE),
        k(0.12, TRAIL, up=(-26, -10, 0), hips=(0, -0.2, -0.05), lhand=(-0.45, -0.75, 0.25),
          rfoot=(0.14, 0.04, -0.42, -4), lfoot=(-0.18, 0.1, 0.38, 20)),
        k(0.45, TRAIL, up=(-24, -10, 0), hips=(0, -0.18, -0.05), lhand=(-0.45, -0.75, 0.25),
          rfoot=(0.14, 0, -0.36, -4), lfoot=(-0.18, 0.06, 0.32, 20)),
    ]))
    # 後跳び：一蹲，往后跃起，落地沉住（服务端在 0.12 秒给她往后上方的速度）
    guard = dict(grip=(0.3, -0.5, -0.55), blade=(-0.2, 0.25, -0.95), edge=edge((-0.2, 0.25, -0.95), (0, -1, 0)))
    add(dict(name='backstep', length=0.7, loop='once', legs=True, markers={'leap': 0.12, 'cancel': 0.6}, keys=[
        k(0.0, IDLE),
        k(0.1, guard, hips=(0, -0.2, 0), up=(-8, 0, 0), lhand=(-0.4, -0.5, -0.3)),
        k(0.22, guard, hips=(0, 0.05, 0), up=(6, 0, 0), rfoot=(0.14, 0.22, -0.14, -6), lfoot=(-0.18, 0.18, 0.12, 18)),
        k(0.45, guard, hips=(0, 0.02, 0), rfoot=(0.14, 0.12, -0.2, -6), lfoot=(-0.18, 0.08, 0.22, 18)),
        k(0.55, guard, hips=(0, -0.22, 0), up=(-6, 0, 0)),
        k(0.7, IDLE, hips=(0, -0.1, 0)),
    ]))

    # ---- 防御与破绽
    # 弹开弹射物：一下小幅度的上挑
    flick = arc(0.04, 0.12, [IDLE['grip'], (0.25, -0.4, -0.8), (-0.05, 0.1, -0.7)],
                [IDLE['blade'], (0.1, 0.3, -0.95), (-0.55, 0.65, -0.5)], hold=0.25, n=4)
    add(dict(name='deflect', length=0.4, loop='once', legs=False, markers={'contact': 0.08, 'cancel': 0.3}, keys=[
        k(0.0, IDLE), *flick, k(0.08, up=(2, 22, 0)), *recover(0.25, 0.4)]))
    # 被弹反：刀被弹向右上方、上身后仰退半步（高位招式）；或被拨到右下、身子拧过去（低位、横向招式）
    knocked_up = dict(grip=(0.6, 0.55, 0.2), blade=(0.2, 0.7, 0.68), edge=edge((0.2, 0.7, 0.68), (0, 1, 0)))
    add(dict(name='parried_high', length=1.0, loop='once', legs=True, keys=[
        k(0.0, last(t1)),
        k(0.08, knocked_up, up=(14, -10, 0), hips=(0, -0.06, 0.12), rfoot=(0.14, 0, 0.0, -6), lfoot=(-0.2, 0, 0.32, 22),
          lhand=(-0.5, -0.4, 0.3), head=(10, 0, 0)),
        hold_key(0.7, dict(knocked_up, up=(12, -10, 0), hips=(0, -0.08, 0.12), head=(6, 0, 0))),
        *recover(0.7, 1.0),
    ]))
    knocked_low = dict(grip=(0.75, -0.6, 0.2), blade=(0.85, -0.3, 0.4), edge=edge((0.85, -0.3, 0.4), (0, -1, 0)))
    add(dict(name='parried_low', length=1.0, loop='once', legs=True, keys=[
        k(0.0, IDLE),
        k(0.08, knocked_low, up=(4, -38, 0), hips=(0, -0.1, 0.06), rfoot=(0.22, 0, -0.1, -20), lhand=(-0.45, -0.3, -0.4)),
        hold_key(0.7, dict(knocked_low, up=(4, -36, 0), hips=(0, -0.1, 0.06))),
        *recover(0.7, 1.0),
    ]))

    # 失衡：蹲下喘息（刀尖撑地，左手按膝，肩膀起伏），站起来时快速一刀
    squat = dict(PLANT, grip=(0.38, -0.55, -0.5), blade=(0.15, -0.85, -0.5), edge=edge((0.15, -0.85, -0.5), (0, 0, -1)),
                 up=(-18, 0, 0), hips=(0, -0.5, 0.04), head=(-16, 0, 0), lmode='free', lhand=(-0.35, -1.0, -0.45),
                 rfoot=(0.2, 0, -0.15, -8), lfoot=(-0.22, 0, 0.05, 16))
    add(dict(name='pant_down', length=0.5, loop='hold', legs=True, keys=[k(0.0, IDLE), k(0.5, squat, blend=0.2)]))
    add(dict(name='pant', length=1.2, loop='loop', legs=True, keys=[
        k(0.0, squat), k(0.3, squat, up=(-12, 0, 0), head=(-10, 0, 0)), k(0.6, squat),
        k(0.9, squat, up=(-12, 0, 0), head=(-10, 0, 0)), k(1.2, squat)]))
    rise_cut = arc(0.15, 0.25, [(0.38, -0.55, -0.5), (0.1, -0.2, -1.0), (-0.45, 0.5, -0.3)],
                   [(0.15, -0.85, -0.5), (-0.05, 0.4, -0.92), (-0.5, 0.8, 0.3)], hold=0.45)
    add(dict(name='recover_slash', length=0.6, loop='once', legs=True, markers={'eye_flash': 0.08, 'contact': 0.2, 'cancel': 0.5}, keys=[
        k(0.0, squat), k(0.1, squat, up=(-20, -10, 0), hips=(0, -0.52, 0.04)),
        *rise_cut, k(0.2, up=(6, 26, 0), hips=(0, -0.08, 0), head=(0, 0, 0), lhand=(-0.5, -0.5, 0.2),
                     rfoot=(0.16, 0, -0.26, -6), lfoot=(-0.2, 0, 0.16, 20)),
        k(0.6, up=(6, 24, 0))]))

    # ---- 投技
    # 瞄准：沉腰前倾，刀收在右后下方，左手前伸指着目标；1.2 秒双眼闪红光
    aim = dict(TRAIL, up=(-14, -24, 0), hips=(0, -0.24, 0.02), head=(6, 18, 0), lmode='free', lhand=(-0.3, -0.25, -0.95),
               lhand_rot=(0, 0, -80), rfoot=(0.12, 0, -0.34, -8), lfoot=(-0.24, 0, 0.36, 28))
    add(dict(name='throw_aim', length=1.3, loop='hold', legs=True, markers={'eye_flash': 1.2}, keys=[
        k(0.0, IDLE), k(0.5, aim, blend=0.2), k(1.0, aim, hips=(0, -0.28, 0.04), up=(-16, -26, 0)),
        hold_key(1.3, dict(aim, hips=(0, -0.28, 0.04), up=(-16, -26, 0)))]))
    add(dict(name='throw_dash', length=0.4, loop='loop', legs=True, keys=[
        k(0.0, TRAIL, up=(-30, -12, 0), hips=(0, -0.26, -0.06), lmode='free', lhand=(-0.5, -0.8, 0.3),
          rfoot=(0.14, 0.06, -0.4, -4), lfoot=(-0.18, 0.12, 0.38, 20)),
        k(0.2, TRAIL, up=(-31, -12, 0), hips=(0, -0.27, -0.06), lmode='free', lhand=(-0.5, -0.8, 0.3),
          rfoot=(0.14, 0.1, -0.36, -4), lfoot=(-0.18, 0.06, 0.4, 20)),
        k(0.4, TRAIL, up=(-30, -12, 0), hips=(0, -0.26, -0.06), lmode='free', lhand=(-0.5, -0.8, 0.3),
          rfoot=(0.14, 0.06, -0.4, -4), lfoot=(-0.18, 0.12, 0.38, 20)),
    ]))
    launch = arc(0.04, 0.14, [TRAIL['grip'], (0.15, -0.3, -1.0), (-0.1, 0.75, -0.35)],
                 [TRAIL['blade'], (0.05, 0.35, -0.94), (-0.15, 0.9, 0.4)], hold=0.9)
    zanshin = dict(last(launch))
    add(dict(name='throw_kiriage', length=0.9, loop='hold', legs=True, markers={'contact': 0.08}, keys=[
        k(0.0, TRAIL, up=(-30, -12, 0), hips=(0, -0.26, -0.06), lmode='free', lhand=(-0.5, -0.8, 0.3),
          rfoot=(0.14, 0.06, -0.4, -4), lfoot=(-0.18, 0.12, 0.38, 20)),
        *launch,
        k(0.08, up=(14, 10, 0), hips=(0, 0.02, -0.02), head=(14, 0, 0), lhand=(-0.55, -0.45, 0.2),
          rfoot=(0.14, 0, -0.3, -6), lfoot=(-0.2, 0.04, 0.24, 20)),
        k(0.9, up=(10, 10, 0), hips=(0, -0.04, 0), head=(10, 0, 0)),
    ]))
    add(dict(name='throw_hold', length=1.2, loop='loop', legs=True, keys=[
        k(0.0, zanshin, up=(10, 10, 0), hips=(0, -0.04, 0), head=(10, 0, 0), lmode='free', lhand=(-0.55, -0.45, 0.2),
          rfoot=(0.14, 0, -0.3, -6), lfoot=(-0.2, 0, 0.24, 20)),
        k(0.6, zanshin, up=(8, 10, 0), hips=(0, -0.05, 0), head=(8, 0, 0), lmode='free', lhand=(-0.55, -0.47, 0.2),
          rfoot=(0.14, 0, -0.3, -6), lfoot=(-0.2, 0, 0.24, 20)),
        k(1.2, zanshin, up=(10, 10, 0), hips=(0, -0.04, 0), head=(10, 0, 0), lmode='free', lhand=(-0.55, -0.45, 0.2),
          rfoot=(0.14, 0, -0.3, -6), lfoot=(-0.2, 0, 0.24, 20)),
    ]))

    for clip in out:
        stance(clip)
    return out

# ---------------------------------------------------------------- 骨架与下摆


def skeleton(art):
    sk = bbmodel_skeleton(art / SOURCE, hand_grip=HAND_GRIP, weapon_scale=WEAPON_SCALE, carry=(np.zeros(3), np.eye(3)))
    sk.grips = GRIPS
    # 躯干方块（手臂的轴线不能进去），宽袍子留一点余量。
    sk.body = ((-6.0, 52.0, -4.2), (6.0, 70.0, 4.2))
    sk.tag = 'boss'
    return sk


def skirt(frame_eulers):
    """下摆八片跟着大腿摆：前片随大腿往前抬，后片在臀部压低时往后翻；下段往回折，大致垂着。返回 {骨骼: 欧拉角}。"""
    out = {}
    drop = -frame_eulers['AllBody'][0][1] / 51.0 if 'AllBody' in frame_eulers else 0
    for side in ('Right', 'Left'):
        e = frame_eulers.get(side + 'Leg')
        R = euler_matrix(e[1]) if e is not None else np.eye(3)
        d = R @ np.array([0, -1.0, 0])
        swing = math.degrees(math.atan2(-d[2], -d[1]))  # 0 垂着，+90 大腿水平向前，负值向后
        spread = math.degrees(math.atan2(d[0] * (1 if side == 'Right' else -1), -d[1]))
        for i, s, face in PANELS:
            if s != side:
                continue
            sign = 1 if side == 'Right' else -1
            if face == 'front':
                up = max(0.0, swing) * 0.92 + min(0.0, swing) * 0.3
            elif face == 'side_front':
                up = max(0.0, swing) * 0.6 + min(0.0, swing) * 0.4
            elif face == 'side_back':
                up = max(0.0, swing) * 0.25 + min(0.0, swing) * 0.7 - drop * 30
            else:
                up = min(0.0, swing) * 0.9 - drop * 55
            roll = sign * max(0.0, spread) * (0.6 if 'side' in face else 0.3) + sign * drop * (12 if 'side' in face else 4)
            low = -up * 0.85 if up > 0 else -up * 0.4
            out[f'robe_panel_{i}_upper'] = np.array([up, 0, roll])
            out[f'robe_panel_{i}_lower'] = np.array([low, 0, 0])
    return out


def export(art, all_clips, baked, sk):
    animations = {}
    for clip in all_clips:
        bones = UPPER + (LOWER if clip.get('legs') else [])
        data = bedrock([clip], {clip['name']: baked[clip['name']]}, sk, bones)['animations'][clip['name']]
        if clip.get('legs'):
            # 下摆：按每帧的大腿角度算，写成和其他骨骼一样的旋转轨道（Bedrock 符号：x、y 取反）
            tracks = {}
            for t, eulers in baked[clip['name']]:
                key = f'{t:.4f}'.rstrip('0').rstrip('.') if t else '0.0'
                for bone, e in skirt(eulers).items():
                    tracks.setdefault(bone, {})[key] = [round(-e[0], 3), round(-e[1], 3), round(e[2], 3)]
            from combat_author import compress
            for bone, track in tracks.items():
                data['bones'][bone] = {'rotation': compress(track)}
        animations[clip['name']] = data
    path = art / OUT
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({'format_version': '1.8.0', 'animations': animations}, ensure_ascii=False, separators=(',', ':')),
                    encoding='utf-8')
    return path

# ---------------------------------------------------------------- 预览


class BossGeo(Geo):
    """预览用：不画尾巴（一千多块，看动作用不着），其余照常。"""

    def __init__(self, geo_path, tex_path):
        super().__init__(geo_path, tex_path)
        keep = [b for b in self.bones if not b['name'].startswith('tail_')]
        self.bones = keep


def weapon_mesh(sk, W):
    s = sk.weapon_scale
    T = np.eye(4); T[:3, 3] = sk.weapon_pivot
    return W['Weapon'] @ T @ np.diag([s, s, s, 1.0])


def preview(art, out, names, all_clips, baked, sk):
    out.mkdir(parents=True, exist_ok=True)
    geo = BossGeo(art / MODEL / 'exports/revision_4/sealed_nine_tailed_fox.geo.json', art / MODEL / 'exports/revision_4/sealed_nine_tailed_fox.png')
    weapon = json.load(open(ROOT / 'src/main/resources/assets/logos_dimension/combat/weapons/ryuka.json', encoding='utf-8'))
    tex = np.asarray(Image.open(ROOT / 'src/main/resources/assets/logos_dimension/textures/item/ryuka_rig.png').convert('RGBA')).astype(np.float32)
    cams = [Camera(-40, 6, 2.6, (0, 52, -10), (300, 330)), Camera(70, 4, 2.6, (0, 52, -10), (300, 330))]
    for clip in all_clips:
        if names and clip['name'] not in names:
            continue
        frames = baked[clip['name']]
        times = [clip['length'] * i / 5 for i in range(6)]
        for name, value in (clip.get('markers') or {}).items():
            times += list(value) if isinstance(value, (list, tuple)) else [value]
        times = sorted(set(round(t, 3) for t in times))
        rows = []
        for v, cam in enumerate(cams):
            panels = []
            for t in times:
                i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
                eulers = frames[i][1]
                pose = {n: (p[0], euler_matrix(p[1])) for n, p in eulers.items()}
                if clip.get('legs'):
                    for bone, e in skirt(eulers).items():
                        pose[bone] = (np.zeros(3), euler_matrix(e))
                W = sk.world(pose)
                quads = geo.quads(pose)
                if clip['name'] != 'seiza' and not (clip['name'] == 'rise' and t < 1.4):
                    quads += weapon_quads(weapon, tex, weapon_mesh(sk, W), 'weapon')
                trail = []
                for j in range(max(0, i - 8), i + 1):
                    pj = {n: (p[0], euler_matrix(p[1])) for n, p in frames[j][1].items()}
                    trail.append((weapon_mesh(sk, sk.world(pj)) @ TIP)[:3])
                tag = ' '.join(n for n, mt in (clip.get('markers') or {}).items()
                               if any(abs(m - t) < 1e-3 for m in (mt if isinstance(mt, (list, tuple)) else [mt])))
                panels.append((f'{t:.2f}s {tag}', render(quads, cam, trail=trail if len(trail) > 1 else None)))
            rows.append(('左前' if v == 0 else '右侧', panels))
        sheet(rows, f"{clip['name']}  {clip['length']}s  标记 {clip.get('markers', {})}", out / f"{clip['name']}.png")
        print('rendered', clip['name'])


def main():
    args = [a for a in sys.argv[1:]]
    art = Path(args[0])
    sk = skeleton(art)
    all_clips = clips()
    baker = Baker(sk)
    baked = {clip['name']: baker.bake(clip, 30) for clip in all_clips}
    worst = {}
    for name, t, side, over in baker.warnings:
        lo, hi, m = worst.get((name, side), (t, t, 0))
        worst[(name, side)] = (min(lo, t), max(hi, t), max(m, over))
    for (name, side), (lo, hi, m) in sorted(worst.items()):
        print(f'{name} {side} 手够不着 {lo:.2f}–{hi:.2f}s，最多差 {m:.2f} 臂长')
    path = export(art, all_clips, baked, sk)
    print('wrote', path, len(all_clips), 'clips')
    if '--preview' in args:
        i = args.index('--preview')
        preview(art, Path(args[i + 1]), set(args[i + 2:]), all_clips, baked, sk)


if __name__ == '__main__':
    main()
