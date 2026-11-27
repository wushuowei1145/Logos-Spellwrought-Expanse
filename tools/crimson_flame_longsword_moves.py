# -*- coding: utf-8 -*-
"""炽焰长剑（Crimson Flame Longsword）二游动漫风大剑动作编排、烘焙与离线预览。

用法（需要 numpy、pillow）：
    uv run --offline --with numpy --with pillow python tools/crimson_flame_longsword_moves.py [预览目录] [动作名…] [--gif]

坐标与符号约定（combat_author.py / docs/COMBAT_SKELETON.md）：
- 角色正面朝 -Z、头顶 +Y、角色右手 +X；绕 +X 旋转为 rot_x(a)：
  - rx < 0（负值）= 上身/胸部前倾下压（扑击、下劈、突刺）；
  - rx > 0（正值）= 上身挺胸后仰（高举蓄势、升龙仰身）。
- head 的旋转基准是 AllBody（不是 UpperBody），前倾时需给 head 匹配负俯仰角，避免头部相对倾斜躯干前探僵硬。
- 主动格挡（guard）采用左掌抵住剑脊斜横护身的二游大剑架势；收剑末段仍保留致敬设定图 2 的竖直驻剑致礼。
"""
import copy
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
WORK_ART = ROOT.parents[1] / 'art' / 'crimson_flame_longsword'
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
LACERTA_DIR = ROOT.parents[1] / 'MinecraftLogosMod' / 'lacerta_dragon_model'
LACERTA_BB = LACERTA_DIR / 'source' / 'lacerta_14.bbmodel'

sys.path.insert(0, str(ROOT / 'tools'))
from combat_author import (  # noqa: E402
    Baker,
    bbmodel_skeleton,
    bedrock,
    euler_matrix,
    npc_parried,
    player_skeleton,
    timeline,
    unit,
)
from combat_render import Camera, mannequin_quads, render, sheet, weapon_quads  # noqa: E402

WEAPON_PATH = ASSETS / 'combat/weapons/crimson_flame_longsword.json'
TEX_PATH = ASSETS / 'textures/item/crimson_flame_longsword_rig.png'
TIP = np.array([0.0, 29.38, 0.0, 1.0])
POMMEL = np.array([0.0, -4.69, 0.0, 1.0])


# ----------------------------------------------------------------------------
# 数学与编排工具
# ----------------------------------------------------------------------------

def k(t, base=None, **kw):
    key = dict(base or {})
    key.update(kw)
    key['t'] = t
    return key


def slerp(a, b, u):
    a, b = unit(a), unit(b)
    d = float(np.clip(a @ b, -1.0, 1.0))
    w = math.acos(d)
    if w < 1e-5:
        return a
    return (math.sin((1 - u) * w) * a + math.sin(u * w) * b) / math.sin(w)


def edge_perp(blade, hint):
    """计算垂直于 blade 且最接近 hint 的单位刃向。"""
    b = unit(blade)
    h = np.asarray(hint, float)
    e = h - (h @ b) * b
    if np.linalg.norm(e) < 1e-5:
        e = np.cross(b, (0, 1, 0) if abs(b[1]) < 0.9 else (1, 0, 0))
    return tuple(np.round(unit(e), 4))


def wrist(blade, edge):
    """刃绕剑身轴转 90° 的过渡向，防止连续反手招式插值翻面。"""
    return tuple(np.round(unit(np.cross(unit(blade), unit(edge))), 4))


def arc(t0, t1, grips, blades, n=6, hold=None, edge_hint=None, **extra):
    """宽弧斩击：剑尖沿球面经 blades[1] 扫向 blades[2]，刃始终顺着挥砍方向。"""
    g0, g1, g2 = (np.asarray(g, float) for g in grips)
    c = 2 * g1 - (g0 + g2) / 2

    def blade_at(u):
        return slerp(blades[0], blades[1], u * 2) if u <= 0.5 else slerp(blades[1], blades[2], u * 2 - 1)

    keys = []
    for i in range(n + 1):
        s = i / n
        u = s * s * (3 - 2 * s)
        du = 1e-3
        b = blade_at(u)
        travel = blade_at(min(1.0, u + du)) - blade_at(max(0.0, u - du))
        e = travel - (travel @ b) * b
        if np.linalg.norm(e) < 1e-5:
            e = np.cross(b, edge_hint or (0, 1, 0))
        grip = (1 - u) ** 2 * g0 + 2 * u * (1 - u) * c + u * u * g2
        keys.append(k(t0 + (t1 - t0) * s, grip=tuple(np.round(grip, 4)), blade=tuple(np.round(b, 4)), edge=tuple(np.round(unit(e), 4)), **extra))
    if hold is not None:
        keys.append(dict(keys[-1], t=hold, ease='hold'))
    return keys


def first_person(keys, alt):
    """将第一人称独立取景刀路写入对应关键帧。"""
    for i, key in enumerate(keys):
        b = alt[min(i, len(alt) - 1)]
        fp_d = dict(key.get('fp', {}))
        fp_d.update(grip=b['grip'], blade=b['blade'], edge=b['edge'])
        key['fp'] = fp_d
    return keys


def last_pose(keys):
    """提取定帧终点的核心握持姿势（含 fp）。"""
    key = keys[-1]
    out = dict(grip=key['grip'], blade=key['blade'], edge=key['edge'])
    if 'fp' in key:
        out['fp'] = {n: key['fp'][n] for n in ('grip', 'blade', 'edge') if n in key['fp']}
    return out


def flower(t0, t1, grip, turns, tilt=24, **extra):
    """右侧身外立面挥转剑花（raxial 绕前臂轴转）。"""
    keys = []
    steps = max(4, int(round(turns * 8)))
    lean = euler_matrix((0, 0, -tilt))
    for i in range(steps + 1):
        th = math.radians(360 * turns * i / steps)
        b = lean @ np.array([0.0, -math.sin(th), -math.cos(th)])
        e = lean @ np.array([0.0, -math.cos(th), math.sin(th)])
        keys.append(k(t0 + (t1 - t0) * i / steps, grip=grip, blade=tuple(np.round(b, 4)), edge=tuple(np.round(e, 4)), raxial=True, **extra))
    return keys


# ----------------------------------------------------------------------------
# 常用姿势（二游龙姬大剑）
# ----------------------------------------------------------------------------

HANG_L = (-0.42, -0.96, 0.04)
HANG_R = (0.42, -0.96, 0.04)
AKIMBO_L = (-0.36, -0.62, -0.02)

# 待机（二游招牌傲岸侧姿）：单手垂胯，烈焰大剑斜拖在右后下方，左手叉腰，身姿微侧 3/4 蓄势感
_IDLE_BLADE = (0.60, -0.36, 0.71)
IDLE = dict(
    grip=(0.48, -0.72, 0.06),
    blade=_IDLE_BLADE,
    edge=edge_perp(_IDLE_BLADE, (-0.56, -0.78, 0.28)),
    rmode='sword',
    lmode='free',
    lhand=AKIMBO_L,
    up=(-2, -14, -2),
    chest=(2, -4, 0),
    head=(0, -6, -1),
    hips=(0, -0.03, 0),
    rfoot=(0.18, 0, -0.10, -8),
    lfoot=(-0.18, 0, 0.14, 16),
    lacerta=dict(lhand=(-0.38, -0.62, -0.02), rfoot=(0.18, 0, -0.12, -10), lfoot=(-0.18, 0, 0.12, 12)),
    fp=dict(grip=(0.36, -0.52, -0.60), blade=(-0.10, 0.36, -0.93), edge=(0.24, -0.92, -0.32), up=(0, 0, 0), chest=(0, 0, 0), head=(0, 0, 0)),
)

# 驻剑致礼（致敬设定图 2）：双手搭在剑首/下握柄上，大剑竖直指地立于身前中线（保留于收剑礼毕定格）
PLANT_SALUTE = dict(
    grip=(0.06, 0.08, -0.62),
    blade=(-0.06, -0.80, -0.60),
    edge=(1.0, 0.0, -0.08),
    rpos=-2.1,
    lpos=-3.3,
    rmode='sword',
    lmode='two',
    up=(-2, 0, 0),
    chest=(0, 0, 0),
    head=(-2, 0, 0),
    hips=(0, -0.04, 0),
    rfoot=(0.18, 0, -0.06, -6),
    lfoot=(-0.18, 0, 0.06, 6),
    lacerta=dict(
        grip=(0.06, -0.24, -0.56),
        blade=(-0.04, -0.94, -0.34),
        edge=(1.0, 0.0, -0.08),
        rpos=-2.5,
        lpos=-3.6,
        up=(-2, 0, 0),
        head=(-2, 0, 0),
        hips=(0, -0.02, 0),
    ),
    fp=dict(
        grip=(0.12, -0.32, -0.68),
        blade=(-0.22, 0.95, -0.22),
        edge=(0.10, 0.24, -0.96),
        rpos=1.15,
        lpos=-1.45,
        up=(0, 0, 0),
        head=(0, 0, 0),
    ),
)

# 主动格挡（二游动漫大剑托脊横防）：右腕持剑横斜立于身前，左掌实抵中段剑脊借力卸冲，左足前踏压低重心
_GUARD_BLADE = (-0.44, 0.86, -0.26)
_GUARD_FP_BLADE = (-0.56, 0.80, -0.22)
GUARD = dict(
    grip=(0.24, -0.26, -0.68),
    blade=_GUARD_BLADE,
    edge=edge_perp(_GUARD_BLADE, (0.24, 0.14, -0.96)),
    rmode='sword',
    lmode='mune',
    mune=11.0,
    up=(-8, -16, -2),
    chest=(-2, -6, 0),
    head=(-5, -6, -1),
    hips=(0, -0.07, 0),
    rfoot=(0.20, 0, 0.18, -10),
    lfoot=(-0.22, 0, -0.20, 22),
    lacerta=dict(
        grip=(0.24, -0.24, -0.60),
        mune=10.5,
        up=(-7, -14, -2),
        chest=(-2, -5, 0),
        head=(-5, -5, -1),
        hips=(0, -0.05, 0),
    ),
    fp=dict(
        grip=(0.22, -0.34, -0.64),
        blade=_GUARD_FP_BLADE,
        edge=edge_perp(_GUARD_FP_BLADE, (0.20, 0.16, -0.96)),
        mune=9.5,
        up=(0, 0, 0),
        chest=(0, 0, 0),
        head=(0, 0, 0),
    ),
)


# ----------------------------------------------------------------------------
# 动作编排（全套 18 段）
# ----------------------------------------------------------------------------

def clips():
    out = []

    # 0. 待机（2.4s 循环）
    out.append(dict(name='idle', length=2.4, loop='loop', keys=[
        k(0.0, IDLE),
        k(1.2, IDLE, grip=(0.48, -0.70, 0.05), blade=(0.60, -0.33, 0.73), up=(-1, -12, -2), chest=(3, -4, 0), head=(1, -5, -1), hips=(0, -0.04, 0),
          fp=dict(IDLE['fp'], grip=(0.36, -0.49, -0.62))),
        k(2.4, IDLE),
    ]))

    # 1. 拔剑（draw, 1.10s, cancel=0.90）：右肩后引出 → 体侧高速风车剑花一圈 → 向右外甩振落入身后拖剑待机
    fl_draw = flower(0.24, 0.66, grip=(0.52, 0.22, -0.36), turns=1.0, tilt=28, rmode='sword', lmode='free', lhand=AKIMBO_L,
                     up=(-4, -18, 0), head=(-2, -6, 0))
    first_person(fl_draw, flower(0.24, 0.66, grip=(0.42, -0.25, -0.58), turns=1.0, tilt=12))
    out.append(dict(name='draw', length=1.1, loop='once', markers={'cancel': 0.90}, keys=[
        k(0.0, rmode='free', rhand=HANG_R, lmode='free', lhand=HANG_L, up=(0, -8, 0), head=(0, -4, 0), hips=(0, -0.02, 0),
          grip=(0.48, 0.28, 0.14), blade=(0.36, 0.64, 0.68), edge=edge_perp((0.36, 0.64, 0.68), (0.8, 0.1, -0.5)),
          fp=dict(grip=(0.42, -0.62, -0.30), blade=(0.20, -0.40, -0.89), edge=(0.2, -0.9, 0.3))),
        k(0.14, rmode='sword', lmode='free', lhand=AKIMBO_L, blend=0.14,
          grip=(0.52, 0.36, 0.04), blade=(0.44, 0.72, 0.54), edge=edge_perp((0.44, 0.72, 0.54), (0.8, -0.2, -0.5)),
          up=(4, -18, 0), head=(2, -6, 0),
          fp=dict(grip=(0.42, -0.25, -0.58), blade=(0.25, 0.45, -0.86), edge=(0.1, -0.9, -0.4))),
        k(0.19, rmode='sword', lmode='free', lhand=AKIMBO_L,
          grip=(0.52, 0.28, -0.20), blade=(0.40, 0.48, -0.78), edge=edge_perp((0.40, 0.48, -0.78), (-0.4, -0.88, -0.2)),
          up=(0, -18, 0), head=(0, -6, 0),
          fp=dict(grip=(0.42, -0.25, -0.58), blade=(0.0, 0.0, -1.0), edge=(-0.21, -0.98, 0.0))),
        *fl_draw,
        k(0.76, rmode='sword', lmode='free', lhand=AKIMBO_L,
          grip=(0.56, -0.42, -0.16), blade=(0.94, -0.30, -0.16), edge=edge_perp((0.94, -0.30, -0.16), (-0.4, -0.9, 0.1)),
          up=(-4, -22, -3), head=(-2, -8, -1),
          fp=dict(grip=(0.38, -0.48, -0.60), blade=(-0.10, 0.30, -0.95), edge=(0.24, -0.92, -0.32))),
        k(0.88, IDLE, grip=(0.52, -0.74, 0.12), blade=(0.64, -0.40, 0.65),
          edge=edge_perp((0.64, -0.40, 0.65), (-0.56, -0.78, 0.28)),
          up=(-5, -20, -3), head=(-2, -7, -2), ease='hold'),
        k(1.10, IDLE),
    ]))

    # 2. 一式 · 苍炎拖刀大横扫（cleave_1, 0.84s, contact=0.28, cancel=0.60）
    # 压低前倾深拉蓄力 → 贴腰扫出 215° 超大弧平斩，左脚蹬地、上身前压随刀向左拧转定帧
    cock1 = dict(
        grip=(0.58, -0.26, 0.16),
        blade=(0.76, -0.10, 0.64),
        edge=(0.64, 0.10, -0.76),
        fp=dict(grip=(0.46, -0.26, -0.34), blade=(0.88, 0.26, -0.39), edge=(-0.38, 0.12, -0.92)),
    )
    s1 = arc(
        0.16, 0.34,
        [cock1['grip'], (0.12, -0.20, -0.88), (-0.44, -0.08, -0.38)],
        [cock1['blade'], (0.0, 0.04, -1.0), (-0.86, 0.16, 0.48)],
        n=6, hold=0.60,
    )
    s1_fp = arc(
        0.16, 0.34,
        [cock1['fp']['grip'], (0.12, -0.26, -0.72), (-0.36, -0.18, -0.54)],
        [cock1['fp']['blade'], (-0.08, 0.12, -0.99), (-0.88, 0.28, -0.38)],
        n=6, hold=0.60,
    )
    cock1['edge'] = s1[0]['edge']
    cock1['fp']['edge'] = s1_fp[0]['edge']
    first_person(s1, s1_fp)
    for idx, key in enumerate(s1):
        u = idx / max(1, len(s1) - 1)
        f = min(1.0, u * 1.15)
        key.update(
            rmode='sword', lmode='two',
            up=(round(-15 + 3 * u, 1), round(-48 + 92 * f, 1), round(-5 + 9 * u, 1)),
            chest=(round(-6 + 1 * u, 1), round(-16 + 30 * f, 1), 0),
            head=(round(-8 + 1 * u, 1), round(-16 + 30 * f, 1), 0),
            hips=(0, round(-0.14 + 0.02 * u, 3), round(-0.04 - 0.08 * f, 3)),
            rfoot=(0.20, 0, round(0.16 - 0.54 * f, 3), -8),
            lfoot=(-0.22, 0, round(-0.14 + 0.42 * f, 3), 24),
        )
    end1 = last_pose(s1)
    out.append(dict(name='cleave_1', length=0.84, loop='once', markers={'input_open': 0.08, 'contact': 0.28, 'cancel': 0.60}, keys=[
        k(0.0, IDLE),
        k(0.08, grip=(0.56, -0.46, 0.10), blade=(0.76, -0.20, 0.62), edge=s1[0]['edge'],
          up=(-10, -32, -4), chest=(-4, -10, 0), head=(-5, -10, 0), hips=(0, -0.09, -0.02), lmode='two', blend=0.08,
          fp=dict(grip=(0.42, -0.40, -0.44), blade=(0.76, 0.24, -0.60), edge=s1_fp[0]['edge'])),
        k(0.15, cock1, lmode='two', up=(-15, -48, -5), chest=(-6, -16, 0), head=(-8, -16, 0), hips=(0, -0.14, -0.04), ease='hold',
          rfoot=(0.22, 0, 0.18, -18), lfoot=(-0.20, 0, -0.16, 16)),
        *s1,
        # 两段右侧弧形收剑，避免穿过身前翻面回拖剑
        k(0.69, grip=(-0.02, -0.48, -0.66), blade=(-0.38, -0.26, -0.89),
          edge=edge_perp((-0.38, -0.26, -0.89), (0.18, -0.96, 0.20)),
          up=(-6, 12, 0), chest=(-2, 4, 0), head=(-3, 4, 0), hips=(0, -0.07, -0.04),
          lmode='free', lhand=AKIMBO_L,
          fp=dict(grip=(-0.06, -0.36, -0.64), blade=(-0.44, 0.12, -0.89), edge=(0.18, -0.94, -0.28))),
        k(0.77, grip=(0.48, -0.58, -0.22), blade=(0.82, -0.32, -0.47),
          edge=edge_perp((0.82, -0.32, -0.47), (-0.42, -0.88, 0.22)),
          up=(-4, -10, -2), chest=(0, -2, 0), head=(-1, -4, -1), hips=(0, -0.04, 0),
          lmode='free', lhand=AKIMBO_L,
          fp=dict(grip=(0.26, -0.48, -0.62), blade=(0.12, 0.30, -0.95), edge=(0.24, -0.92, -0.32))),
        k(0.84, IDLE, blend=0.12),
    ]))

    # 3. 二式 · 逆鳞回天斜撩（rend_2, 0.84s, contact=0.30, cancel=0.60）
    # 承接一式左侧低蹲 → 顺腕翻刃自左下狠狠斜撩向高右上方 → 收尾左手帅气甩开单手亮相，挺胸昂首不前探！
    cock2 = dict(
        grip=(-0.38, -0.46, -0.30),
        blade=(-0.82, -0.40, 0.41),
        edge=(0.50, 0.16, 0.85),
        fp=dict(grip=(-0.34, -0.42, -0.48), blade=(-0.80, -0.30, -0.52), edge=(0.54, 0.22, -0.81)),
    )
    s2 = arc(
        0.16, 0.35,
        [cock2['grip'], (0.12, -0.18, -0.86), (0.58, 0.28, -0.12)],
        [cock2['blade'], (0.06, 0.10, -0.99), (0.76, 0.54, 0.36)],
        n=6, hold=0.60,
    )
    s2_fp = arc(
        0.16, 0.35,
        [cock2['fp']['grip'], (0.10, -0.22, -0.72), (0.48, 0.20, -0.40)],
        [cock2['fp']['blade'], (0.04, 0.12, -0.99), (0.72, 0.58, -0.38)],
        n=6, hold=0.60,
    )
    cock2['edge'] = s2[0]['edge']
    cock2['fp']['edge'] = s2_fp[0]['edge']
    first_person(s2, s2_fp)
    for idx, key in enumerate(s2):
        u = idx / max(1, len(s2) - 1)
        f = min(1.0, u * 1.12)
        key.update(
            rmode='sword',
            lmode='free' if idx >= len(s2) - 2 else 'two',
            up=(round(-15 + 21 * u, 1), round(40 - 78 * f, 1), round(5 - 9 * u, 1)),
            chest=(round(-6 + 9 * u, 1), round(12 - 22 * f, 1), 0),
            head=(round(-9 + 12 * u, 1), round(12 - 26 * f, 1), round(-2 * u, 1)),
            hips=(0, round(-0.16 + 0.10 * u, 3), round(-0.06 + 0.02 * u, 3)),
            rfoot=(0.22, 0, -0.26, -12), lfoot=(-0.20, 0, 0.26, 22),
        )
        if idx >= len(s2) - 2:
            key.update(lhand=(-0.58, -0.24, -0.30), blend=0.08)
    end2 = last_pose(s2)
    out.append(dict(name='rend_2', length=0.84, loop='once', markers={'input_open': 0.08, 'contact': 0.30, 'cancel': 0.60}, keys=[
        k(0.0, end1, lmode='two', up=(-12, 44, 4), chest=(-5, 14, 0), head=(-7, 14, 0), hips=(0, -0.12, -0.12)),
        k(0.08, grip=(-0.40, -0.32, -0.36), blade=(-0.86, -0.14, 0.49),
          edge=wrist(end1['blade'], end1['edge']),
          fp=dict(grip=(-0.36, -0.32, -0.52), blade=(-0.86, 0.0, -0.50),
                  edge=wrist(end1['fp']['blade'], end1['fp']['edge'])),
          up=(-14, 42, 5), chest=(-5, 13, 0), head=(-8, 14, 0), lmode='two'),
        k(0.15, cock2, up=(-15, 40, 5), chest=(-6, 12, 0), head=(-9, 12, 0), hips=(0, -0.16, -0.06), lmode='two', ease='hold',
          rfoot=(0.16, 0, -0.14, -8), lfoot=(-0.22, 0, 0.14, 24)),
        *s2,
        k(0.73, grip=(0.64, -0.38, -0.06), blade=(0.86, -0.12, 0.50),
          edge=edge_perp((0.86, -0.12, 0.50), (-0.36, -0.88, 0.30)),
          up=(0, -24, -2), chest=(2, -6, 0), head=(1, -9, -1), hips=(0, -0.04, 0),
          lmode='free', lhand=AKIMBO_L,
          fp=dict(grip=(0.40, -0.26, -0.52), blade=(0.24, 0.46, -0.85), edge=(0.30, -0.92, -0.24))),
        k(0.84, IDLE, lmode='free', lhand=AKIMBO_L, blend=0.12),
    ]))

    # 4. 三式 · 绯龙裂空升龙挑（ascend_3, 0.88s, contact=0.34, cancel=0.64, effect=launch）
    # 右膝外侧深蹲拧腰蓄力 → 爆发升龙斜挑将敌击飞半空，腰身顺势腾跃拔高、大剑高举在左肩外不挡脸
    cock3 = dict(
        grip=(0.50, -0.42, -0.06),
        blade=(0.64, -0.24, -0.73),
        edge=(-0.44, 0.63, -0.64),
        fp=dict(grip=(0.36, -0.54, -0.46), blade=(0.36, -0.42, -0.83), edge=(-0.36, 0.76, -0.54)),
    )
    s3 = arc(
        0.18, 0.40,
        [cock3['grip'], (0.10, -0.22, -0.86), (-0.34, 0.48, -0.28)],
        [cock3['blade'], (0.04, 0.06, -0.997), (-0.52, 0.76, 0.39)],
        n=6, hold=0.64,
    )
    s3_fp = arc(
        0.18, 0.40,
        [cock3['fp']['grip'], (0.12, -0.26, -0.74), (-0.16, 0.42, -0.50)],
        [cock3['fp']['blade'], (0.06, 0.14, -0.988), (-0.30, 0.86, -0.41)],
        n=6, hold=0.64,
    )
    cock3['edge'] = s3[0]['edge']
    cock3['fp']['edge'] = s3_fp[0]['edge']
    first_person(s3, s3_fp)
    for idx, key in enumerate(s3):
        u = idx / max(1, len(s3) - 1)
        f = min(1.0, u * 1.1)
        key.update(
            rmode='sword', lmode='two',
            up=(round(-14 + 20 * u, 1), round(-36 + 70 * f, 1), round(-4 + 8 * u, 1)),
            chest=(round(-5 + 9 * u, 1), round(-12 + 22 * f, 1), 0),
            head=(round(-9 + 14 * u, 1), round(-12 + 22 * f, 1), 0),
            hips=(0, round(-0.14 + 0.24 * u, 3), round(-0.06 - 0.06 * u, 3)),
            rfoot=(0.20, 0, round(-0.12 - 0.22 * u, 3), -10),
            lfoot=(-0.22, 0, 0.22, 20),
        )
    end3 = last_pose(s3)
    out.append(dict(name='ascend_3', length=0.88, loop='once', markers={'input_open': 0.08, 'contact': 0.34, 'cancel': 0.64}, keys=[
        k(0.0, end2, lmode='free', lhand=(-0.58, -0.24, -0.30), up=(6, -38, -4), chest=(3, -10, 0), head=(3, -14, -2), hips=(0, -0.06, -0.04)),
        k(0.09, grip=(0.52, -0.14, -0.34), blade=(0.64, 0.04, -0.77), edge=(-0.72, 0.68, -0.14),
          fp=dict(grip=(0.42, -0.22, -0.52), blade=(0.52, 0.10, -0.85), edge=(-0.20, 0.88, 0.42)),
          up=(-8, -32, -3), chest=(-3, -10, 0), head=(-5, -10, 0), hips=(0, -0.10, -0.05), lmode='two'),
        k(0.17, cock3, up=(-14, -36, -4), chest=(-5, -12, 0), head=(-9, -12, 0), hips=(0, -0.14, -0.06), lmode='two', ease='hold',
          rfoot=(0.22, 0, -0.10, -14), lfoot=(-0.20, 0, 0.24, 20)),
        *s3,
        k(0.76, grip=(0.28, -0.16, -0.44), blade=(0.28, 0.56, -0.78), edge=(0.94, -0.24, 0.22),
          fp=dict(grip=(0.20, -0.14, -0.58), blade=(-0.16, 0.68, -0.72), edge=(0.98, 0.18, -0.04)),
          up=(-2, 8, 0), chest=(0, 2, 0), head=(-1, 2, 0), hips=(0, -0.02, 0), lmode='free', lhand=AKIMBO_L),
        k(0.88, IDLE, blend=0.14),
    ]))

    # 5. 四式 · 天陨断界重劈（cataclysm_4, 1.16s, contact=0.52, cancel=0.88, effect=slam）
    # 二游大剑灵魂：腾空后仰举剑滞空蓄力（0.21-0.36s）→ 全身猛然前压俯冲砸地重劈（up=-22, chest=-10，绝不再后仰！）
    apex4 = dict(
        grip=(0.18, 0.64, 0.04),
        blade=(0.14, 0.52, 0.84),
        edge=(0.0, 0.85, -0.53),
        fp=dict(grip=(0.08, 0.44, -0.44), blade=(-0.04, 0.82, -0.57), edge=(0.0, -0.57, -0.82)),
    )
    s4 = arc(
        0.38, 0.55,
        [apex4['grip'], (0.10, 0.06, -0.86), (-0.16, -0.26, -0.74)],
        [apex4['blade'], (0.0, 0.42, -0.91), (-0.26, -0.08, -0.96)],
        n=6, hold=0.88, edge_hint=(1, 0, 0),
    )
    s4_fp = arc(
        0.38, 0.55,
        [apex4['fp']['grip'], (0.08, 0.02, -0.74), (-0.08, -0.50, -0.66)],
        [apex4['fp']['blade'], (-0.04, 0.42, -0.91), (-0.18, -0.46, -0.87)],
        n=6, hold=0.88, edge_hint=(1, 0, 0),
    )
    apex4['edge'] = s4[0]['edge']
    apex4['fp']['edge'] = s4_fp[0]['edge']
    first_person(s4, s4_fp)
    for idx, key in enumerate(s4):
        u = idx / max(1, len(s4) - 1)
        f = min(1.0, u * 1.15)
        key.update(
            rmode='sword',
            lmode='free' if idx >= len(s4) - 2 else 'two',
            up=(round(12 - 34 * f, 1), round(-20 + 48 * f, 1), round(-2 + 6 * u, 1)),
            chest=(round(6 - 16 * f, 1), round(-8 + 16 * f, 1), 0),
            head=(round(6 - 18 * f, 1), round(-6 + 14 * f, 1), 0),
            hips=(0, round(0.28 - 0.46 * f, 3), round(0.02 - 0.26 * f, 3)),
            rfoot=(0.20, round(0.22 * (1 - f), 3), round(-0.12 - 0.48 * f, 3), -6),
            lfoot=(-0.22, round(0.15 * (1 - f), 3), round(0.22 + 0.14 * f, 3), 26),
        )
        if idx >= len(s4) - 2:
            key.update(lhand=(-0.58, 0.16, 0.42), blend=0.09)
    out.append(dict(name='cataclysm_4', length=1.16, loop='once', markers={'input_open': 0.10, 'contact': 0.52, 'cancel': 0.88}, keys=[
        k(0.0, end3, lmode='two', up=(6, 34, 4), chest=(4, 10, 0), head=(5, 10, 0), hips=(0, 0.10, -0.12)),
        k(0.11, grip=(0.06, 0.62, -0.24), blade=(0.0, 0.94, 0.34), edge=(0.96, 0.08, -0.26),
          fp=dict(grip=(-0.02, 0.44, -0.48), blade=(-0.12, 0.90, -0.42), edge=(0.98, 0.10, -0.14)),
          up=(8, 0, 0), chest=(4, 0, 0), head=(6, 0, 0), hips=(0, 0.22, -0.04), lmode='two'),
        k(0.21, apex4, up=(12, -20, -2), chest=(6, -8, 0), head=(6, -6, 0), hips=(0, 0.28, 0.02), lmode='two',
          rfoot=(0.18, 0.22, -0.12, -8), lfoot=(-0.20, 0.15, 0.22, 18)),
        k(0.36, apex4, up=(12, -20, -2), chest=(6, -8, 0), head=(6, -6, 0), hips=(0, 0.28, 0.02), lmode='two', ease='hold',
          rfoot=(0.18, 0.22, -0.12, -8), lfoot=(-0.20, 0.15, 0.22, 18)),
        *s4,
        k(1.02, grip=(0.34, -0.54, -0.34), blade=(0.48, -0.26, -0.84), edge=edge_perp((0.48, -0.26, -0.84), (0.14, -0.96, -0.10)),
          up=(-8, -6, -1), chest=(0, -2, 0), head=(-4, -4, 0), hips=(0, -0.08, -0.08), lmode='free', lhand=AKIMBO_L, blend=0.14,
          fp=dict(grip=(0.24, -0.50, -0.62), blade=(-0.08, 0.16, -0.98), edge=(0.20, -0.96, -0.18))),
        k(1.16, IDLE),
    ]))

    # 6. 主动格挡 · 左掌托脊（guard, 2.0s loop, 二游大剑横防）
    out.append(dict(name='guard', length=2.0, loop='loop', keys=[
        k(0.0, GUARD),
        k(1.0, GUARD, grip=(0.24, -0.28, -0.67), up=(-7, -15, -2), hips=(0, -0.08, 0),
          lacerta=dict(GUARD['lacerta'], grip=(0.24, -0.26, -0.59), up=(-6, -13, -2), hips=(0, -0.06, 0)),
          fp=dict(GUARD['fp'], grip=(0.22, -0.36, -0.63))),
        k(2.0, GUARD),
    ]))

    # 7. 受击震颤（guard_hit, 0.34s）
    _gh_b = (-0.36, 0.88, -0.30)
    _gh_b2 = (-0.40, 0.87, -0.28)
    _gh_fp = (-0.48, 0.84, -0.24)
    _gh_fp2 = (-0.52, 0.82, -0.23)
    out.append(dict(name='guard_hit', length=0.34, loop='once', keys=[
        k(0.0, GUARD),
        k(0.06, GUARD, grip=(0.20, -0.30, -0.54), blade=_gh_b, edge=edge_perp(_gh_b, (0.24, 0.14, -0.96)),
          up=(-2, -10, 0), head=(-1, -4, 0), hips=(0, -0.10, 0.05),
          lacerta=dict(GUARD['lacerta'], grip=(0.20, -0.28, -0.48), up=(-2, -9, 0), head=(-1, -4, 0), hips=(0, -0.08, 0.04)),
          fp=dict(GUARD['fp'], grip=(0.20, -0.38, -0.52), blade=_gh_fp, edge=edge_perp(_gh_fp, (0.20, 0.16, -0.96))), ease='hold'),
        k(0.18, GUARD, grip=(0.22, -0.28, -0.60), blade=_gh_b2, edge=edge_perp(_gh_b2, (0.24, 0.14, -0.96)),
          up=(-5, -13, -1), head=(-3, -5, 0), hips=(0, -0.08, 0.02),
          lacerta=dict(GUARD['lacerta'], grip=(0.22, -0.26, -0.54), up=(-4, -12, -1), hips=(0, -0.06, 0.02)),
          fp=dict(GUARD['fp'], grip=(0.21, -0.36, -0.58), blade=_gh_fp2, edge=edge_perp(_gh_fp2, (0.20, 0.16, -0.96))), ease='hold'),
        k(0.34, GUARD),
    ]))

    # 8. 逆焰弹反（parry, 0.42s, cancel=0.12, effect=parry）
    out.append(dict(name='parry', length=0.42, loop='once', markers={'cancel': 0.12}, keys=[
        k(0.0, GUARD),
        k(0.02, GUARD, lmode='free', lhand=(-0.06, 0.26, -0.68), blend=0.05),
        k(0.06, lmode='free', lhand=(-0.30, -0.06, -0.52),
          grip=(0.30, -0.16, -0.62), blade=(-0.06, 0.88, -0.47), edge=edge_perp((-0.06, 0.88, -0.47), (0.52, 0.24, -0.82)),
          up=(-2, -14, -1), head=(-1, -6, 0),
          fp=dict(grip=(0.28, -0.24, -0.60), blade=(-0.04, 0.88, -0.47), edge=edge_perp((-0.04, 0.88, -0.47), (0.48, 0.22, -0.85)))),
        k(0.11, rmode='sword', lmode='free', lhand=(-0.52, -0.34, 0.08),
          grip=(0.48, 0.12, -0.36), blade=(0.64, 0.72, -0.26), edge=(0.76, -0.60, 0.24),
          up=(6, -24, 0), chest=(2, -7, 0), head=(3, -8, 0), ease='hold',
          fp=dict(grip=(0.44, -0.10, -0.52), blade=(0.46, 0.84, -0.28), edge=(0.86, -0.48, 0.16))),
        k(0.24, rmode='sword', lmode='free', lhand=(-0.50, -0.38, 0.04),
          grip=(0.44, 0.02, -0.46), blade=(0.52, 0.74, -0.42), edge=edge_perp((0.52, 0.74, -0.42), (0.82, -0.52, 0.22)),
          up=(2, -18, 0), head=(1, -6, 0), ease='hold',
          fp=dict(grip=(0.40, -0.18, -0.56), blade=(0.36, 0.82, -0.44), edge=edge_perp((0.36, 0.82, -0.44), (0.84, -0.46, 0.24)))),
        k(0.34, lmode='free', lhand=(-0.32, -0.20, -0.62), blend=0.06,
          grip=(0.30, -0.18, -0.58), blade=(-0.22, 0.90, -0.38), edge=edge_perp((-0.22, 0.90, -0.38), (0.32, 0.20, -0.92)),
          up=(-4, -14, -1), head=(-3, -5, 0),
          fp=dict(grip=(0.28, -0.26, -0.62), blade=(-0.26, 0.86, -0.44), edge=edge_perp((-0.26, 0.86, -0.44), (0.30, 0.18, -0.94)))),
        k(0.42, GUARD, blend=0.10),
    ]))

    # 9. 破军龙牙（dragon_fang, 0.66s, contact=0.18, cancel=0.44）：弹反后的疾影低姿突刺（前倾 up=-18）！
    out.append(dict(name='dragon_fang', length=0.66, loop='once', markers={'contact': 0.18, 'cancel': 0.44}, keys=[
        k(0.0, rmode='sword', lmode='free', lhand=(-0.52, -0.34, 0.08),
          grip=(0.48, 0.12, -0.36), blade=(0.64, 0.72, -0.26), edge=(0.76, -0.60, 0.24), up=(6, -24, 0), chest=(2, -7, 0), head=(3, -8, 0)),
        k(0.07, grip=(0.54, -0.34, 0.12), blade=(0.12, 0.04, -0.99), edge=(0.99, 0.0, 0.12),
          up=(2, -38, 0), chest=(2, -10, 0), head=(2, -12, 0), lmode='free', lhand=(-0.24, -0.18, -0.76), hips=(0, -0.10, 0.04), ease='hold', space='hips',
          rfoot=(0.20, 0, 0.10, -14), lfoot=(-0.20, 0, -0.20, 22),
          fp=dict(grip=(0.44, -0.36, -0.22), blade=(0.06, 0.06, -0.996), edge=(0.996, 0.0, 0.06))),
        k(0.18, grip=(0.10, -0.20, -0.98), blade=(0.0, 0.02, -1.0), edge=(1.0, 0.0, 0.0),
          up=(-18, 28, 3), chest=(-6, 10, 0), head=(-10, 10, 0), lhand=(-0.62, 0.28, 0.44), hips=(0, -0.16, -0.28), space='hips',
          rfoot=(0.18, 0, -0.72, -6), lfoot=(-0.22, 0, 0.24, 26),
          fp=dict(grip=(0.06, -0.22, -0.96), blade=(-0.02, 0.04, -0.999), edge=(1.0, 0.0, -0.02))),
        k(0.44, grip=(0.10, -0.20, -0.96), blade=(0.0, 0.03, -0.999), edge=(1.0, 0.0, 0.0),
          up=(-16, 26, 2), chest=(-5, 8, 0), head=(-9, 9, 0), lhand=(-0.60, 0.24, 0.42), hips=(0, -0.15, -0.25), space='hips', ease='hold',
          fp=dict(grip=(0.06, -0.22, -0.94), blade=(-0.02, 0.04, -0.999), edge=(1.0, 0.0, -0.02))),
        k(0.55, grip=(0.46, -0.52, -0.32), blade=(0.64, -0.28, -0.71), edge=edge_perp((0.64, -0.28, -0.71), (0.48, -0.82, -0.30)),
          up=(-8, -4, -1), chest=(-1, -2, 0), head=(-4, -3, 0), hips=(0, -0.08, -0.10), lhand=AKIMBO_L,
          fp=dict(grip=(0.24, -0.38, -0.76), blade=(0.14, 0.24, -0.96), edge=(0.40, -0.88, -0.24))),
        k(0.66, IDLE, blend=0.14),
    ]))

    # 10 & 11. 被弹反硬直（parried 0.50s / parried_npc 0.80s）
    parried_peak = dict(
        grip=(0.64, 0.12, -0.16),
        blade=(0.56, 0.74, 0.36),
        edge=(0.74, -0.60, -0.30),
        up=(14, -26, 0),
        chest=(4, -10, 0),
        head=(10, -10, 0),
        lmode='free',
        lhand=(-0.66, -0.52, 0.16),
        hips=(0, -0.06, 0.08),
        fp=dict(grip=(0.48, 0.0, -0.46), blade=(0.50, 0.80, -0.30), edge=(0.80, -0.56, 0.20), up=(6, -10, 0), chest=(0, 0, 0), head=(0, 0, 0)),
    )
    out.append(dict(name='parried', length=0.50, loop='once', keys=[
        k(0.0, grip=(0.34, -0.18, -0.66), blade=(0.28, 0.56, -0.78), edge=(0.90, -0.22, 0.38), up=(0, -8, 0), head=(0, -4, 0),
          lmode='free', lhand=(-0.44, -0.50, -0.30)),
        k(0.07, parried_peak, ease='hold', rfoot=(0.18, 0, 0.0, -10), lfoot=(-0.22, 0, 0.28, 24)),
        k(0.34, parried_peak, grip=(0.60, 0.04, -0.20), blade=(0.50, 0.70, 0.50), up=(10, -22, 0), head=(6, -8, 0), ease='hold',
          fp=dict(parried_peak['fp'], grip=(0.46, -0.08, -0.46))),
        k(0.42, grip=(0.58, -0.36, -0.04), blade=(0.74, 0.08, 0.67), edge=edge_perp((0.74, 0.08, 0.67), (-0.42, -0.74, 0.52)),
          up=(2, -16, -1), chest=(2, -5, 0), head=(2, -6, 0), hips=(0, -0.04, 0.03), lhand=AKIMBO_L,
          fp=dict(grip=(0.40, -0.32, -0.54), blade=(0.24, 0.58, -0.78), edge=edge_perp((0.24, 0.58, -0.78), (0.56, -0.76, -0.32)))),
        k(0.50, IDLE, blend=0.14),
    ]))
    out.append(npc_parried(out[-1]))

    # 12. 收剑（sheathe, 1.30s hold）：体侧甩前 → 剑花收势 → 短暂驻剑致礼（PLANT_SALUTE）→ 右侧下沉交回原版
    fl_sheathe = flower(0.14, 0.56, grip=(0.50, 0.26, -0.42), turns=1.0, tilt=26, rmode='sword', lmode='free', lhand=(-0.48, -0.72, 0.04),
                        up=(0, -8, 0), head=(0, -4, 0))
    first_person(fl_sheathe, flower(0.14, 0.56, grip=(0.38, -0.26, -0.56), turns=1.0, tilt=10))
    out.append(dict(name='sheathe', length=1.30, loop='hold', keys=[
        k(0.0, IDLE),
        k(0.07, rmode='sword', lmode='free', lhand=(-0.44, -0.68, 0.0),
          grip=(0.56, -0.30, -0.28), blade=(0.82, -0.24, -0.52), edge=edge_perp((0.82, -0.24, -0.52), (-0.48, -0.80, -0.36)),
          up=(-2, -12, -1), head=(0, -5, 0),
          fp=dict(grip=(0.38, -0.38, -0.58), blade=(-0.05, 0.16, -0.98), edge=(0.12, -0.96, -0.24))),
        *fl_sheathe,
        k(0.68, PLANT_SALUTE, rmode='sword', lmode='free', lhand=(-0.34, -0.56, -0.26),
          fp=dict(grip=(0.24, -0.38, -0.62), blade=(-0.12, 0.88, -0.46), edge=(0.10, 0.44, -0.89))),
        k(0.92, PLANT_SALUTE, rmode='sword', lmode='free', lhand=(-0.38, -0.68, -0.10), ease='hold',
          fp=dict(grip=(0.24, -0.44, -0.58), blade=(-0.08, 0.80, -0.59), edge=(0.12, 0.56, -0.82))),
        k(1.06, rmode='sword', lmode='free', lhand=HANG_L,
          grip=(0.36, -0.34, -0.36), blade=(0.62, -0.58, -0.52), edge=(0.70, 0.46, 0.54), up=(0, -6, 0), head=(0, -2, 0),
          fp=dict(grip=(0.36, -0.56, -0.48), blade=(0.14, 0.36, -0.92), edge=(0.20, -0.90, -0.38))),
        k(1.18, rmode='sword', lmode='free', lhand=HANG_L,
          grip=(0.46, -0.52, -0.10), blade=(0.58, -0.52, 0.62), edge=(0.76, 0.44, -0.48), up=(0, -10, 0), head=(0, -2, 0),
          fp=dict(grip=(0.44, -0.72, -0.28), blade=(0.22, -0.42, -0.88), edge=(0.22, -0.88, 0.42))),
        k(1.30, rmode='free', rhand=HANG_R, lmode='free', lhand=HANG_L, up=(0, 0, 0), chest=(0, 0, 0), head=(0, 0, 0), hips=(0, 0, 0), blend=0.18),
    ]))

    # 13. 破晓炎枪 · 突刺（crimson_thrust, 0.72s, input_open=0.08, contact=0.20, cancel=0.46）
    # 蹲伏潜行/先手蓄刺：右侧挥弧转前 → 低姿斜压右腰蓄势 → 疾影暴冲单手直刺（前倾 up=-20, head=-14）→ 帅气外撇收剑
    out.append(dict(name='crimson_thrust', length=0.72, loop='once', markers={'input_open': 0.08, 'contact': 0.20, 'cancel': 0.46}, keys=[
        k(0.0, IDLE),
        k(0.045, grip=(0.54, -0.46, -0.22), blade=(0.88, -0.18, -0.44),
          edge=edge_perp((0.88, -0.18, -0.44), (0.42, -0.10, 0.90)),
          up=(-4, -24, -2), chest=(-1, -7, 0), head=(-2, -9, 0), lmode='free', lhand=(-0.28, -0.42, -0.34),
          hips=(0, -0.09, 0.04),
          fp=dict(grip=(0.40, -0.42, -0.42), blade=(0.54, 0.14, -0.83), edge=edge_perp((0.54, 0.14, -0.83), (0.64, -0.16, 0.75)))),
        k(0.09, grip=(0.46, -0.30, 0.08), blade=(0.06, 0.04, -0.997), edge=(0.997, 0.0, 0.06),
          up=(-6, -34, -2), chest=(-2, -10, 0), head=(-4, -12, 0), lmode='free', lhand=(-0.18, -0.28, -0.64),
          hips=(0, -0.14, 0.08), space='hips', rfoot=(0.20, 0, 0.14, -14), lfoot=(-0.22, 0, -0.22, 22),
          fp=dict(grip=(0.42, -0.34, -0.22), blade=(0.04, 0.05, -0.998), edge=(0.998, 0.0, 0.04))),
        k(0.12, grip=(0.46, -0.30, 0.08), blade=(0.06, 0.04, -0.997), edge=(0.997, 0.0, 0.06),
          up=(-8, -36, -2), chest=(-3, -10, 0), head=(-5, -12, 0), lmode='free', lhand=(-0.16, -0.26, -0.66),
          hips=(0, -0.15, 0.10), space='hips', ease='hold',
          fp=dict(grip=(0.42, -0.34, -0.20), blade=(0.04, 0.05, -0.998), edge=(0.998, 0.0, 0.04))),
        k(0.20, grip=(0.08, -0.20, -0.98), blade=(0.0, 0.02, -1.0), edge=(1.0, 0.0, 0.0),
          up=(-20, 26, 3), chest=(-8, 10, 0), head=(-14, 10, 0), lmode='free', lhand=(-0.62, 0.30, 0.46),
          hips=(0, -0.18, -0.32), space='hips', rfoot=(0.18, 0, -0.76, -8), lfoot=(-0.22, 0, 0.28, 28),
          fp=dict(grip=(0.06, -0.20, -0.96), blade=(-0.02, 0.03, -0.999), edge=(1.0, 0.0, -0.02))),
        k(0.46, grip=(0.08, -0.20, -0.95), blade=(0.0, 0.03, -0.999), edge=(1.0, 0.0, 0.0),
          up=(-17, 22, 2), chest=(-6, 8, 0), head=(-11, 8, 0), lmode='free', lhand=(-0.58, 0.24, 0.40),
          hips=(0, -0.15, -0.26), space='hips', ease='hold',
          fp=dict(grip=(0.06, -0.20, -0.94), blade=(-0.02, 0.03, -0.999), edge=(1.0, 0.0, -0.02))),
        k(0.58, grip=(0.48, -0.50, -0.30), blade=(0.64, -0.28, -0.71), edge=edge_perp((0.64, -0.28, -0.71), (0.48, -0.82, -0.30)),
          up=(-7, -6, -1), chest=(-1, -2, 0), head=(-4, -3, 0), hips=(0, -0.08, -0.10), lhand=AKIMBO_L,
          fp=dict(grip=(0.24, -0.38, -0.74), blade=(0.14, 0.24, -0.96), edge=(0.40, -0.88, -0.24))),
        k(0.72, IDLE, blend=0.14),
    ]))

    # 14. 炽焰旋风斩 · 旋转突进（vortex_rush, 0.90s, input_open=0.12, contact=[0.22, 0.48], cancel=0.56）
    # 疾跑突进连续二段 360° 大剑旋风斩：第一段贴腰平扫半周 → 绕背过顶大回环换肩 → 第二段前冲反撩爆砍定帧
    vr_a = arc(
        0.11, 0.24,
        [(0.50, -0.32, 0.04), (0.10, -0.20, -0.88), (-0.34, -0.12, -0.44)],
        [(0.78, -0.14, 0.61), (0.0, 0.02, -1.0), (-0.74, 0.14, 0.66)],
        n=4, edge_hint=(0, 1, 0),
    )
    vr_a_fp = arc(
        0.11, 0.24,
        [(0.44, -0.30, -0.34), (0.08, -0.24, -0.76), (-0.34, -0.16, -0.52)],
        [(0.84, -0.10, -0.53), (0.0, 0.04, -0.999), (-0.84, 0.14, -0.52)],
        n=4, edge_hint=(0, 1, 0),
    )
    first_person(vr_a, vr_a_fp)
    for idx, key in enumerate(vr_a):
        u = idx / max(1, len(vr_a) - 1)
        key.update(
            rmode='sword', lmode='two',
            up=(round(-14 + 2 * u, 1), round(-46 + 94 * u, 1), round(-4 + 8 * u, 1)),
            chest=(round(-6 + 1 * u, 1), round(-16 + 32 * u, 1), 0),
            head=(round(-9 + 2 * u, 1), round(-14 + 28 * u, 1), 0),
            hips=(0, round(-0.14 + 0.04 * u, 3), round(0.04 - 0.14 * u, 3)),
            rfoot=(0.20, 0, round(0.14 - 0.56 * u, 3), -8),
            lfoot=(-0.22, 0, round(-0.12 + 0.36 * u, 3), 24),
        )

    vr_b = arc(
        0.26, 0.36,
        [(-0.34, -0.12, -0.44), (0.10, 0.56, -0.18), (0.48, 0.02, -0.28)],
        [(-0.74, 0.14, 0.66), (0.0, 0.86, 0.51), (0.76, 0.22, 0.61)],
        n=4, edge_hint=(0, 0, 1),
    )
    vr_b_fp = arc(
        0.26, 0.36,
        [(-0.34, -0.16, -0.52), (0.08, 0.38, -0.42), (0.44, -0.10, -0.42)],
        [(-0.84, 0.14, -0.52), (0.0, 0.90, -0.44), (0.82, 0.20, -0.54)],
        n=4, edge_hint=(0, 0, 1),
    )
    first_person(vr_b, vr_b_fp)
    for idx, key in enumerate(vr_b):
        u = idx / max(1, len(vr_b) - 1)
        key.update(
            rmode='sword', lmode='two',
            up=(round(-12 + 10 * u, 1), round(48 - 94 * u, 1), round(4 - 8 * u, 1)),
            chest=(round(-5 + 4 * u, 1), round(16 - 32 * u, 1), 0),
            head=(round(-7 + 5 * u, 1), round(14 - 28 * u, 1), 0),
            hips=(0, round(-0.10 + 0.06 * math.sin(u * math.pi), 3), round(-0.10 - 0.06 * u, 3)),
        )

    vr_c = arc(
        0.41, 0.52,
        [(0.48, 0.02, -0.28), (0.06, -0.20, -0.88), (-0.40, -0.22, -0.42)],
        [(0.76, 0.22, 0.61), (0.04, -0.06, -0.997), (-0.68, -0.28, -0.68)],
        n=4, hold=0.58, edge_hint=(0, 1, 0),
    )
    vr_c_fp = arc(
        0.41, 0.52,
        [(0.44, -0.10, -0.42), (0.04, -0.24, -0.78), (-0.34, -0.26, -0.54)],
        [(0.82, 0.20, -0.54), (0.04, -0.06, -0.997), (-0.76, -0.28, -0.58)],
        n=4, hold=0.58, edge_hint=(0, 1, 0),
    )
    first_person(vr_c, vr_c_fp)
    for idx, key in enumerate(vr_c):
        u = idx / max(1, len(vr_c) - 1)
        f = min(1.0, u * 1.15)
        key.update(
            rmode='sword',
            lmode='free' if idx >= len(vr_c) - 2 else 'two',
            up=(round(-2 - 14 * f, 1), round(-46 + 94 * f, 1), round(-4 + 9 * u, 1)),
            chest=(round(-1 - 5 * f, 1), round(-16 + 32 * f, 1), 0),
            head=(round(-2 - 8 * f, 1), round(-14 + 28 * f, 1), 0),
            hips=(0, round(-0.10 - 0.05 * f, 3), round(-0.16 - 0.10 * f, 3)),
            rfoot=(0.20, 0, round(0.08 - 0.62 * f, 3), -8),
            lfoot=(-0.22, 0, round(-0.08 + 0.34 * f, 3), 26),
        )
        if idx >= len(vr_c) - 2:
            key.update(lhand=(-0.60, 0.10, 0.36), blend=0.08)

    vr_mid_edge = edge_perp((0.76, 0.22, 0.61), tuple(unit(np.asarray(vr_b[-1]['edge']) + np.asarray(vr_c[0]['edge']))))
    vr_mid_fp_edge = edge_perp((0.82, 0.20, -0.54), tuple(unit(np.asarray(vr_b_fp[-1]['edge']) + np.asarray(vr_c_fp[0]['edge']))))
    out.append(dict(name='vortex_rush', length=0.90, loop='once', markers={'input_open': 0.12, 'contact': [0.22, 0.48], 'cancel': 0.56}, keys=[
        k(0.0, IDLE),
        k(0.06, grip=(0.54, -0.42, 0.04), blade=(0.76, -0.22, 0.61), edge=vr_a[0]['edge'],
          up=(-8, -28, -3), chest=(-3, -10, 0), head=(-5, -8, 0), hips=(0, -0.09, 0.02), lmode='two',
          fp=dict(grip=(0.42, -0.40, -0.44), blade=(0.74, -0.14, -0.66), edge=vr_a_fp[0]['edge'])),
        *vr_a,
        *vr_b,
        k(0.385, grip=(0.48, 0.02, -0.28), blade=(0.76, 0.22, 0.61), edge=vr_mid_edge,
          up=(-2, -46, -4), chest=(-1, -16, 0), head=(-2, -14, 0), hips=(0, -0.10, -0.16), lmode='two',
          fp=dict(grip=(0.44, -0.10, -0.42), blade=(0.82, 0.20, -0.54), edge=vr_mid_fp_edge)),
        *vr_c,
        k(0.71, grip=(0.02, -0.44, -0.64), blade=(-0.34, -0.26, -0.90),
          edge=edge_perp((-0.34, -0.26, -0.90), (0.18, -0.96, 0.20)),
          up=(-6, 10, 0), chest=(-2, 4, 0), head=(-3, 4, 0), hips=(0, -0.07, -0.06), lmode='free', lhand=AKIMBO_L,
          fp=dict(grip=(-0.06, -0.36, -0.64), blade=(-0.44, 0.12, -0.89), edge=(0.18, -0.94, -0.28))),
        k(0.81, grip=(0.48, -0.58, -0.20), blade=(0.82, -0.32, -0.47),
          edge=edge_perp((0.82, -0.32, -0.47), (-0.42, -0.88, 0.22)),
          up=(-4, -10, -2), chest=(0, -2, 0), head=(-1, -4, -1), hips=(0, -0.04, 0), lmode='free', lhand=AKIMBO_L,
          fp=dict(grip=(0.26, -0.48, -0.62), blade=(0.12, 0.30, -0.95), edge=(0.24, -0.92, -0.32))),
        k(0.90, IDLE, blend=0.12),
    ]))

    # 15. 红莲劫焰斩 · 技能横扫（flame_sweep, 0.96s, input_open=0.14, contact=0.36, cancel=0.68）
    # 技能招式（可自格挡直接派生破防横扫）：右后沉身蓄出 240° 烈焰全弧 → 横贯身前掠地清场，末段单手脱力定帧
    cock_fs = dict(
        grip=(0.48, -0.18, 0.10),
        blade=(0.64, 0.04, 0.77),
        edge=(0.76, 0.04, -0.65),
        fp=dict(grip=(0.46, -0.22, -0.34), blade=(0.86, 0.16, -0.48), edge=(-0.46, 0.10, -0.88)),
    )
    fs = arc(
        0.22, 0.42,
        [cock_fs['grip'], (0.12, -0.18, -0.90), (-0.40, -0.14, -0.34)],
        [cock_fs['blade'], (0.0, -0.02, -1.0), (-0.78, -0.08, 0.62)],
        n=6, hold=0.68, edge_hint=(0, 1, 0),
    )
    fs_fp = arc(
        0.22, 0.42,
        [cock_fs['fp']['grip'], (0.10, -0.22, -0.74), (-0.36, -0.20, -0.50)],
        [cock_fs['fp']['blade'], (-0.04, 0.04, -0.998), (-0.86, 0.14, -0.49)],
        n=6, hold=0.68, edge_hint=(0, 1, 0),
    )
    cock_fs['edge'] = fs[0]['edge']
    cock_fs['fp']['edge'] = fs_fp[0]['edge']
    first_person(fs, fs_fp)
    for idx, key in enumerate(fs):
        u = idx / max(1, len(fs) - 1)
        f = min(1.0, u * 1.12)
        key.update(
            rmode='sword',
            lmode='free' if idx >= len(fs) - 2 else 'two',
            up=(round(-14 - 4 * u, 1), round(-46 + 102 * f, 1), round(-5 + 10 * u, 1)),
            chest=(round(-6 - 1 * u, 1), round(-16 + 34 * f, 1), 0),
            head=(round(-9 - 3 * u, 1), round(-15 + 33 * f, 1), 0),
            hips=(0, round(-0.15 - 0.01 * u, 3), round(0.08 - 0.30 * f, 3)),
            rfoot=(0.20, 0, round(0.18 - 0.74 * f, 3), -10),
            lfoot=(-0.24, 0, round(-0.16 + 0.46 * f, 3), 28),
        )
        if idx >= len(fs) - 2:
            key.update(lhand=(-0.54, 0.10, 0.24), blend=0.09)

    out.append(dict(name='flame_sweep', length=0.96, loop='once', markers={'input_open': 0.14, 'contact': 0.36, 'cancel': 0.68}, keys=[
        k(0.0, GUARD),
        k(0.02, GUARD, lmode='free', lhand=(-0.06, 0.26, -0.68), blend=0.06),
        k(0.07, grip=(0.36, -0.22, -0.54), blade=(0.22, 0.56, -0.80), edge=edge_perp((0.22, 0.56, -0.80), (0.64, 0.14, -0.76)),
          lmode='free', lhand=(-0.22, -0.04, -0.46), up=(-8, -28, -2), chest=(-3, -10, 0), head=(-5, -9, 0), hips=(0, -0.10, 0.02),
          fp=dict(grip=(0.30, -0.30, -0.56), blade=(0.20, 0.48, -0.85), edge=edge_perp((0.20, 0.48, -0.85), (0.60, 0.12, -0.79)))),
        k(0.12, grip=(0.46, -0.20, -0.16), blade=(0.88, 0.28, -0.38), edge=edge_perp((0.88, 0.28, -0.38), (0.42, 0.08, -0.90)),
          lmode='free', lhand=(-0.18, -0.10, -0.42), up=(-11, -38, -4), chest=(-4, -13, 0), head=(-7, -12, 0), hips=(0, -0.13, 0.05),
          fp=dict(grip=(0.40, -0.26, -0.44), blade=(0.64, 0.32, -0.70), edge=edge_perp((0.64, 0.32, -0.70), (0.12, 0.10, -0.98)))),
        k(0.18, cock_fs, lmode='two', up=(-14, -46, -5), chest=(-6, -16, 0), head=(-9, -15, 0), hips=(0, -0.15, 0.08),
          rfoot=(0.22, 0, 0.20, -16), lfoot=(-0.22, 0, -0.18, 18)),
        k(0.21, cock_fs, lmode='two', up=(-14, -46, -5), chest=(-6, -16, 0), head=(-9, -15, 0), hips=(0, -0.15, 0.08), ease='hold',
          rfoot=(0.22, 0, 0.20, -16), lfoot=(-0.22, 0, -0.18, 18)),
        *fs,
        k(0.78, grip=(0.02, -0.44, -0.64), blade=(-0.34, -0.26, -0.90),
          edge=edge_perp((-0.34, -0.26, -0.90), (0.18, -0.96, 0.20)),
          up=(-6, 12, 0), chest=(-2, 4, 0), head=(-3, 4, 0), hips=(0, -0.07, -0.06), lmode='free', lhand=AKIMBO_L,
          fp=dict(grip=(-0.06, -0.36, -0.64), blade=(-0.44, 0.12, -0.89), edge=(0.18, -0.94, -0.28))),
        k(0.87, grip=(0.48, -0.58, -0.20), blade=(0.78, -0.28, -0.56),
          edge=edge_perp((0.78, -0.28, -0.56), (-0.42, -0.88, 0.22)),
          up=(-4, -10, -2), chest=(0, -2, 0), head=(-1, -4, -1), hips=(0, -0.04, 0), lmode='free', lhand=AKIMBO_L,
          fp=dict(grip=(0.26, -0.48, -0.62), blade=(0.12, 0.30, -0.95), edge=(0.24, -0.92, -0.32))),
        k(0.96, IDLE, blend=0.12),
    ]))

    # 16. 炽翼坠星破 · 龙姬空降坠刺（skyfall_judgement, 1.06s, input_open=0.10, contact=0.50, cancel=0.80）
    # 龙姬专属空战技：振翼高跃悬停翻转大剑下指 → 双手压柄疾坠贯地引发爆炎火柱
    out.append(dict(name='skyfall_judgement', length=1.06, loop='once', markers={'input_open': 0.10, 'contact': 0.50, 'cancel': 0.80}, keys=[
        k(0.0, IDLE),
        k(0.06, grip=(0.50, -0.18, -0.24), blade=(0.74, 0.34, -0.58), edge=edge_perp((0.74, 0.34, -0.58), (0.62, -0.36, 0.70)),
          up=(1, -14, -1), chest=(2, -4, 0), head=(1, -6, 0), hips=(0, -0.10, 0.02), lmode='free', lhand=(-0.26, -0.34, -0.32),
          fp=dict(grip=(0.34, -0.24, -0.56), blade=(0.26, 0.58, -0.77), edge=edge_perp((0.26, 0.58, -0.77), (0.60, -0.40, 0.69)))),
        k(0.11, grip=(0.32, 0.18, -0.42), blade=(0.30, 0.82, 0.49), edge=edge_perp((0.30, 0.82, 0.49), (0.8, -0.2, -0.5)),
          up=(4, -14, 0), chest=(2, -4, 0), head=(3, -5, 0), hips=(0, -0.16, 0.04), lmode='two',
          fp=dict(grip=(0.24, 0.04, -0.54), blade=(0.14, 0.78, -0.61), edge=edge_perp((0.14, 0.78, -0.61), (0.8, -0.2, 0.3)))),
        k(0.17, grip=(0.20, 0.46, -0.44), blade=(0.14, 0.08, -0.98), edge=edge_perp((0.14, 0.08, -0.98), (0.98, 0.0, 0.14)),
          up=(6, -6, 0), chest=(3, -2, 0), head=(4, -2, 0), hips=(0, 0.12, 0.02), lmode='two',
          fp=dict(grip=(0.14, 0.24, -0.52), blade=(0.06, 0.04, -0.997), edge=edge_perp((0.06, 0.04, -0.997), (0.98, 0.0, 0.14)))),
        k(0.22, grip=(0.12, 0.64, -0.28), blade=(0.0, -0.88, -0.47), edge=edge_perp((0.0, -0.88, -0.47), (0.98, 0.0, -0.20)),
          up=(8, 0, 0), chest=(3, 0, 0), head=(5, 0, 0), hips=(0, 0.34, 0.0), lmode='two',
          rfoot=(0.18, 0.22, -0.12, -10), lfoot=(-0.20, 0.16, 0.20, 18),
          fp=dict(grip=(0.06, 0.42, -0.46), blade=(0.0, -0.84, -0.54), edge=edge_perp((0.0, -0.84, -0.54), (0.98, 0.0, -0.20)))),
        k(0.35, grip=(0.12, 0.64, -0.28), blade=(0.0, -0.88, -0.47), edge=edge_perp((0.0, -0.88, -0.47), (0.98, 0.0, -0.20)),
          up=(8, 0, 0), chest=(3, 0, 0), head=(5, 0, 0), hips=(0, 0.36, 0.0), lmode='two', ease='hold',
          rfoot=(0.18, 0.22, -0.12, -10), lfoot=(-0.20, 0.16, 0.20, 18),
          fp=dict(grip=(0.06, 0.42, -0.46), blade=(0.0, -0.84, -0.54), edge=edge_perp((0.0, -0.84, -0.54), (0.98, 0.0, -0.20)))),
        k(0.50, grip=(0.08, 0.16, -0.72), blade=(0.0, -0.56, -0.83), edge=edge_perp((0.0, -0.56, -0.83), (0.98, 0.0, -0.20)),
          up=(-18, 0, 0), chest=(-6, 0, 0), head=(-11, 0, 0), hips=(0, -0.06, -0.20), lmode='two',
          rfoot=(0.18, 0, -0.58, -8), lfoot=(-0.22, 0, 0.26, 26),
          fp=dict(grip=(0.04, -0.10, -0.68), blade=(0.0, -0.64, -0.77), edge=edge_perp((0.0, -0.64, -0.77), (0.98, 0.0, -0.20)))),
        k(0.80, grip=(0.08, 0.16, -0.70), blade=(0.0, -0.56, -0.83), edge=edge_perp((0.0, -0.56, -0.83), (0.98, 0.0, -0.20)),
          up=(-16, 0, 0), chest=(-5, 0, 0), head=(-10, 0, 0), hips=(0, -0.05, -0.18), lmode='two', ease='hold',
          fp=dict(grip=(0.04, -0.10, -0.66), blade=(0.0, -0.64, -0.77), edge=edge_perp((0.0, -0.64, -0.77), (0.98, 0.0, -0.20)))),
        k(0.93, grip=(0.44, -0.34, -0.38), blade=(0.56, -0.36, -0.75), edge=edge_perp((0.56, -0.36, -0.75), (0.48, -0.82, -0.30)),
          up=(-6, -8, -1), chest=(-1, -2, 0), head=(-3, -4, 0), hips=(0, -0.06, -0.08), lmode='free', lhand=AKIMBO_L,
          fp=dict(grip=(0.24, -0.40, -0.68), blade=(0.14, 0.22, -0.96), edge=(0.40, -0.88, -0.24))),
        k(1.06, IDLE, blend=0.14),
    ]))

    # 17. 逆鳞十文字裂炎斩 · 双连十字斩（cross_rend, 0.94s, input_open=0.10, contact=[0.20, 0.44], cancel=0.68）
    # 二段派生/高速 X 型交叉连斩：右上→左下重袈裟 → 左下翻腕顺势逆撩右上，收势左手后摆单手亮相
    xr1 = arc(
        0.10, 0.23,
        [(0.50, 0.22, -0.24), (0.10, -0.04, -0.86), (-0.32, -0.24, -0.52)],
        [(0.56, 0.72, 0.41), (0.0, 0.08, -0.997), (-0.68, -0.40, -0.61)],
        n=4,
    )
    xr1_fp = arc(
        0.10, 0.23,
        [(0.44, 0.10, -0.44), (0.08, -0.14, -0.76), (-0.32, -0.36, -0.50)],
        [(0.64, 0.68, -0.36), (0.0, 0.08, -0.997), (-0.68, -0.48, -0.55)],
        n=4,
    )
    first_person(xr1, xr1_fp)
    for idx, key in enumerate(xr1):
        u = idx / max(1, len(xr1) - 1)
        key.update(
            rmode='sword', lmode='two',
            up=(round(4 - 16 * u, 1), round(-30 + 64 * u, 1), round(-2 + 5 * u, 1)),
            chest=(round(2 - 7 * u, 1), round(-10 + 22 * u, 1), 0),
            head=(round(3 - 10 * u, 1), round(-10 + 22 * u, 1), 0),
            hips=(0, round(-0.08 - 0.02 * u, 3), round(0.02 - 0.14 * u, 3)),
            rfoot=(0.20, 0, round(0.08 - 0.44 * u, 3), -8),
            lfoot=(-0.22, 0, round(-0.08 + 0.30 * u, 3), 24),
        )
    end_xr1 = last_pose(xr1)

    xr2 = arc(
        0.30, 0.47,
        [(-0.32, -0.24, -0.52), (0.10, -0.08, -0.88), (0.56, 0.26, -0.18)],
        [(-0.68, -0.40, -0.61), (0.04, 0.08, -0.996), (0.66, 0.54, 0.52)],
        n=5, hold=0.68,
    )
    xr2_fp = arc(
        0.30, 0.47,
        [(-0.32, -0.36, -0.50), (0.08, -0.14, -0.74), (0.46, 0.18, -0.42)],
        [(-0.68, -0.48, -0.55), (0.04, 0.10, -0.994), (0.70, 0.56, -0.44)],
        n=5, hold=0.68,
    )
    first_person(xr2, xr2_fp)
    for idx, key in enumerate(xr2):
        u = idx / max(1, len(xr2) - 1)
        f = min(1.0, u * 1.12)
        key.update(
            rmode='sword',
            lmode='free' if idx >= len(xr2) - 2 else 'two',
            up=(round(-12 + 16 * f, 1), round(34 - 70 * f, 1), round(3 - 6 * u, 1)),
            chest=(round(-5 + 7 * f, 1), round(12 - 22 * f, 1), 0),
            head=(round(-7 + 10 * f, 1), round(12 - 22 * f, 1), 0),
            hips=(0, round(-0.10 + 0.02 * f, 3), round(-0.12 - 0.06 * f, 3)),
            rfoot=(0.20, 0, round(-0.24 - 0.12 * f, 3), -10),
            lfoot=(-0.22, 0, 0.24, 24),
        )
        if idx >= len(xr2) - 2:
            key.update(lhand=(-0.58, -0.32, -0.26), blend=0.08)

    out.append(dict(name='cross_rend', length=0.94, loop='once', markers={'input_open': 0.10, 'contact': [0.20, 0.44], 'cancel': 0.68}, keys=[
        k(0.0, IDLE),
        k(0.04, grip=(0.54, -0.26, -0.14), blade=(0.88, 0.24, -0.42),
          edge=edge_perp((0.88, 0.24, -0.42), (0.15, -0.30, -0.94)),
          up=(1, -22, -1), chest=(1, -7, 0), head=(1, -8, 0), hips=(0, -0.06, 0.01),
          lmode='free', lhand=(-0.28, -0.40, -0.34),
          fp=dict(grip=(0.40, -0.24, -0.54), blade=(0.46, 0.48, -0.75), edge=edge_perp((0.46, 0.48, -0.75), (0.30, -0.40, -0.86)))),
        k(0.08, grip=(0.50, 0.22, -0.24), blade=(0.56, 0.72, 0.41), edge=xr1[0]['edge'],
          up=(4, -30, 0), chest=(2, -10, 0), head=(3, -10, 0), hips=(0, -0.08, 0.02), lmode='two',
          fp=dict(grip=(0.44, 0.10, -0.44), blade=(0.64, 0.68, -0.36), edge=xr1_fp[0]['edge'])),
        *xr1,
        k(0.27, grip=(-0.32, -0.24, -0.52), blade=(-0.68, -0.40, -0.61),
          edge=wrist(end_xr1['blade'], end_xr1['edge']),
          up=(-12, 34, 3), chest=(-5, 12, 0), head=(-7, 12, 0), hips=(0, -0.10, -0.12), lmode='two',
          fp=dict(grip=(-0.32, -0.36, -0.50), blade=(-0.68, -0.48, -0.55),
                  edge=wrist(end_xr1['fp']['blade'], end_xr1['fp']['edge']))),
        *xr2,
        k(0.81, grip=(0.64, -0.40, -0.06), blade=(0.86, -0.14, 0.49),
          edge=edge_perp((0.86, -0.14, 0.49), (-0.36, -0.88, 0.30)),
          up=(0, -20, -2), chest=(1, -5, 0), head=(0, -8, -1), hips=(0, -0.04, 0), lmode='free', lhand=AKIMBO_L,
          fp=dict(grip=(0.40, -0.26, -0.52), blade=(0.24, 0.46, -0.85), edge=(0.30, -0.92, -0.24))),
        k(0.94, IDLE, blend=0.14),
    ]))

    for clip in out:
        clip.setdefault('stance', {'rfoot': IDLE['rfoot'], 'lfoot': IDLE['lfoot']})
    return out


# ----------------------------------------------------------------------------
# 骨架配置
# ----------------------------------------------------------------------------

def skeletons():
    player = player_skeleton(ASSETS / 'combat/skeletons/player_tp.json', weapon_scale=0.80, carry=((0, 0, 0), np.eye(3)))
    player.grips = (1.15, -1.45)
    player.body = ((-4.4, 12.0, -2.5), (4.4, 21.0, 2.5))
    player.tag = 'player'

    fp = player_skeleton(ASSETS / 'combat/skeletons/player_fp.json', weapon_scale=0.58, carry=((0, 0, 0), np.eye(3)))
    fp.grips = (1.15, -1.45)
    fp.root = ((15.13, -1.82, 0.0), (0.0, 0.0, 12.0))
    fp.reach = np.array([-3.0, 3.0, -5.0])
    fp.tag = 'fp'

    lacerta = bbmodel_skeleton(LACERTA_BB, hand_grip=(0.0, -2.20, -0.35), weapon_scale=0.85, carry=((0, 0, 0), np.eye(3)))
    lacerta.grips = (1.15, -1.45)
    lacerta.body = ((-3.5, 20.8, -2.5), (3.5, 30.5, 2.5))
    lacerta.tag = 'lacerta'
    return player, fp, lacerta


# ----------------------------------------------------------------------------
# 拉塞塔全套动作导出（含龙翼、龙尾律动与裙褶自动防穿插）
# ----------------------------------------------------------------------------

def add_lacerta_dragon_flair(clip_name: str, length: float, frames: list, sk):
    """给拉塞塔 NPC 的动作附加龙翼与龙尾动态，以及下装裙褶退让，提升二游龙姬爆发力。"""
    def sample_flair(t: float):
        u = t / max(length, 1e-6)
        if clip_name == 'cleave_1':
            burst = math.sin(min(1.0, max(0.0, (t - 0.12) / 0.45)) * math.pi)
            return (-8 - 22 * burst, 10 + 18 * burst, 16 * math.sin(u * math.pi), -14 * math.cos(u * 2 * math.pi))
        if clip_name == 'rend_2':
            # 单手高挑亮相：双翼猛张展开、龙尾上翘平衡
            flare = math.sin(min(1.0, max(0.0, (t - 0.14) / 0.50)) * math.pi)
            return (-10 - 26 * flare, 14 + 22 * flare, -12 * flare, 18 * flare)
        if clip_name == 'ascend_3':
            lift = math.sin(min(1.0, max(0.0, (t - 0.14) / 0.48)) * math.pi)
            return (-14 - 26 * lift, 16 + 24 * lift, -18 * lift, 12 * lift)
        if clip_name == 'cataclysm_4':
            # 腾空蓄压：翼展最大化；陨落重劈：双翼前扣俯冲裹风
            if t < 0.36:
                f = min(1.0, t / 0.28)
                return (-12 - 26 * f, 14 + 28 * f, -16 * f, 8 * f)
            f = min(1.0, (t - 0.36) / 0.22)
            return (-38 + 48 * f, 42 - 30 * f, -16 + 32 * f, 8 - 18 * f)
        if clip_name in ('guard', 'guard_hit'):
            breath = math.sin(u * 2 * math.pi)
            return (-6 + 2 * breath, 10 + 2 * breath, -6, 6 * breath)
        if clip_name in ('parry', 'dragon_fang', 'crimson_thrust'):
            burst = math.sin(min(1.0, t / max(length * 0.65, 1e-4)) * math.pi)
            return (-14 - 28 * burst, 18 + 26 * burst, -16 * burst, 14 * burst)
        if clip_name in ('vortex_rush', 'flame_sweep'):
            surge = math.sin(min(1.0, max(0.0, (t - 0.08) / max(length * 0.68, 1e-4))) * math.pi)
            whip = math.sin(u * 3 * math.pi) if clip_name == 'vortex_rush' else math.sin(u * 2 * math.pi)
            return (-12 - 32 * surge, 16 + 28 * surge, -14 * surge, 22 * whip)
        if clip_name == 'skyfall_judgement':
            if t < 0.35:
                f = min(1.0, t / 0.24)
                return (-14 - 30 * f, 16 + 30 * f, -18 * f, 6 * f)
            f = min(1.0, (t - 0.35) / 0.20)
            return (-44 + 54 * f, 46 - 32 * f, -18 + 34 * f, 6 - 16 * f)
        if clip_name == 'cross_rend':
            double_pulse = abs(math.sin(min(1.0, max(0.0, (t - 0.06) / 0.52)) * 2 * math.pi))
            return (-10 - 24 * double_pulse, 14 + 22 * double_pulse, -14 * double_pulse, 18 * math.cos(u * 2 * math.pi))
        breath = math.sin(u * 2 * math.pi)
        return (-2 + 2 * breath, 8 + 3 * breath, 2 * breath, 6 * breath)

    extra = {}
    for t, _ in frames:
        wy, wx, tail_x, tail_y = sample_flair(t)
        key = f'{t:.4f}'.rstrip('0').rstrip('.') if t else '0.0'
        extra.setdefault('WingRootL', {})[key] = [round(-wx * 0.4, 2), round(-wy, 2), 0.0]
        extra.setdefault('WingRootR', {})[key] = [round(-wx * 0.4, 2), round(wy, 2), 0.0]
        extra.setdefault('WingArmL', {})[key] = [0.0, round(-wy * 0.45, 2), round(-wx * 0.3, 2)]
        extra.setdefault('WingArmR', {})[key] = [0.0, round(wy * 0.45, 2), round(wx * 0.3, 2)]
        extra.setdefault('DragonTail0', {})[key] = [round(tail_x, 2), round(tail_y, 2), 0.0]
        extra.setdefault('DragonTail1', {})[key] = [round(tail_x * 0.6, 2), round(tail_y * 0.8, 2), 0.0]
        extra.setdefault('DragonTail2', {})[key] = [0.0, round(tail_y * 0.6, 2), 0.0]
    return extra


def export_lacerta_animations_and_stage(all_clips, baked_lacerta, lacerta_sk):
    """在内存中组装拉塞塔战斗动画并计算裙褶防穿插（不修改磁盘上的 lacerta_14.bbmodel）。"""
    sys.path.insert(0, str(LACERTA_DIR / 'tools'))
    import bbtool as lac_bbtool  # noqa: E402
    import preview_anim as lac_pa  # noqa: E402
    import skirt as lac_skirt  # noqa: E402

    b = lac_bbtool.BB(str(LACERTA_BB))
    lac_bones = [
        'AllBody', 'UpBody', 'UpperBody', 'AllHead', 'Head', 'Arm',
        'RightArm', 'RightForeArm', 'RightHand',
        'LeftArm', 'LeftForeArm', 'LeftHand',
        'DownBody', 'RightLeg', 'RightLowerLeg', 'RightFoot',
        'LeftLeg', 'LeftLowerLeg', 'LeftFoot',
        'Weapon',
    ]
    combat_data = bedrock(
        all_clips,
        {c['name']: baked_lacerta[c['name']] for c in all_clips},
        lacerta_sk,
        lac_bones,
    )
    groups_by_name = {g['name']: g for g in b.groups.values()}

    anim_entries = []
    for clip in all_clips:
        cname = clip['name']
        anim_obj = combat_data['animations'][cname]
        animators = {}
        extra_tracks = add_lacerta_dragon_flair(cname, clip['length'], baked_lacerta[cname], lacerta_sk)

        for bname, tr in anim_obj['bones'].items():
            if bname not in groups_by_name:
                continue
            guuid = groups_by_name[bname]['uuid']
            kfs = []
            for ch in ('rotation', 'position'):
                for ts, val in tr.get(ch, {}).items():
                    t_val = float(ts)
                    if ch == 'rotation':
                        x, y, z = -val[0], -val[1], val[2]
                    else:
                        x, y, z = -val[0], val[1], val[2]
                    kfs.append({
                        'channel': ch,
                        'data_points': [{'x': str(x), 'y': str(y), 'z': str(z)}],
                        'uuid': str(lac_bbtool.uuidlib.uuid4()),
                        'time': t_val,
                        'color': -1,
                        'interpolation': 'linear',
                    })
            animators[guuid] = {
                'name': bname,
                'type': 'bone',
                'rotation_global': False,
                'quaternion_interpolation': False,
                'keyframes': kfs,
            }

        for bname, rmap in extra_tracks.items():
            if bname not in groups_by_name:
                continue
            guuid = groups_by_name[bname]['uuid']
            kfs = [
                {
                    'channel': 'rotation',
                    'data_points': [{'x': str(v[0]), 'y': str(v[1]), 'z': str(v[2])}],
                    'uuid': str(lac_bbtool.uuidlib.uuid4()),
                    'time': float(ts),
                    'color': -1,
                    'interpolation': 'linear',
                }
                for ts, v in rmap.items()
            ]
            animators[guuid] = {
                'name': bname,
                'type': 'bone',
                'rotation_global': False,
                'quaternion_interpolation': False,
                'keyframes': kfs,
            }

        anim_entries.append({
            'uuid': str(lac_bbtool.uuidlib.uuid4()),
            'name': f'crimson_sword_{cname}',
            'loop': 'loop' if clip.get('loop') == 'loop' else ('hold' if clip.get('loop') == 'hold' else 'once'),
            'override': False,
            'length': clip['length'],
            'snapping': 60,
            'selected': False,
            'animators': animators,
        })

    base_keep = [a for a in b.data['animations'] if a['name'] in ('pre_parallel0', 'pre_parallel1', 'pre_parallel2', 'idle', 'face_battle')]
    b.data['animations'] = base_keep + anim_entries
    skirt_report = lac_skirt.bake(b)
    print('Lacerta in-memory skirt bake:', skirt_report)

    full_exp = b.export_animations()
    lac_export = {
        'format_version': '1.8.0',
        'animations': {
            name.replace('crimson_sword_', ''): v
            for name, v in full_exp['animations'].items()
            if name.startswith('crimson_sword_')
        },
    }
    for clip in all_clips:
        if clip.get('markers'):
            lac_export['animations'][clip['name']]['timeline'] = timeline(clip['markers'])

    stage = lac_pa.Stage(b)
    return lac_export, stage, b


# ----------------------------------------------------------------------------
# 主流程：烘焙、导出与渲染预览
# ----------------------------------------------------------------------------

def mesh_matrix(sk, W):
    s = sk.weapon_scale
    M = W['Weapon']
    T = np.eye(4); T[:3, 3] = sk.weapon_pivot
    S = np.diag([s, s, s, 1.0])
    return M @ T @ S


def check_floor_clearance(all_clips, baked, sk, label):
    min_tip = math.inf
    min_pommel = math.inf
    worst = None
    for clip in all_clips:
        for t, eulers in baked[(label, clip['name'])]:
            pose = {n: (p, euler_matrix(e)) for n, (p, e) in eulers.items()}
            M = mesh_matrix(sk, sk.world(pose))
            tip_y = float((M @ TIP)[1])
            pom_y = float((M @ POMMEL)[1])
            if tip_y < min_tip:
                min_tip = tip_y
                worst = (clip['name'], t, 'tip', tip_y)
            if pom_y < min_pommel:
                min_pommel = pom_y
                if worst is None or pom_y < worst[3]:
                    worst = (clip['name'], t, 'pommel', pom_y)
    print(f'[{label}] Floor clearance: min_tip_y={min_tip:.2f}, min_pommel_y={min_pommel:.2f}, worst={worst}')
    assert min_tip >= 0.25 and min_pommel >= 0.25, f'{label} clips floor! {worst}'


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    out_dir = Path(args[0]) if args else (WORK_ART / 'anim_v01')
    only = set(args[1:])
    want_gif = '--gif' in sys.argv or not args
    out_dir.mkdir(parents=True, exist_ok=True)

    player, fp, lacerta = skeletons()
    all_clips = clips()
    baked = {}

    for who, sk in (('player', player), ('fp', fp), ('lacerta', lacerta)):
        baker = Baker(sk, strict=True)
        for clip in all_clips:
            baked[(who, clip['name'])] = baker.bake(clip, 60)
        worst = {}
        for clip_name, t, side, over in baker.warnings:
            key = (clip_name, side)
            lo, hi, m = worst.get(key, (t, t, 0.0))
            worst[key] = (min(lo, t), max(hi, t), max(m, over))
        for (clip_name, side), (lo, hi, m) in sorted(worst.items()):
            print(f'WARNING {who} {clip_name} {side} out-of-reach {lo:.2f}-{hi:.2f}s max={m:.3f} arm')
        if who in ('player', 'lacerta'):
            check_floor_clearance(all_clips, baked, sk, who)

    # 1. 导出玩家 TP / FP 动作至工作树
    tp_bones = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
                'LeftArm', 'LeftForeArm', 'LeftHand', 'Weapon']
    fp_bones = ['Root'] + tp_bones
    anim_dir = ASSETS / 'combat/animations'
    anim_dir.mkdir(parents=True, exist_ok=True)
    for who, sk, view, blist in (('player', player, 'tp', tp_bones), ('fp', fp, 'fp', fp_bones)):
        data = bedrock(all_clips, {c['name']: baked[(who, c['name'])] for c in all_clips}, sk, blist)
        text = json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n'
        (anim_dir / f'crimson_flame_longsword_{view}.animation.json').write_text(text, encoding='utf-8', newline='\n')

    # 2. 生成拉塞塔全套动作（含翼/尾动态 + 裙褶避让）并单独交付到 art/crimson_flame_longsword/anim_v01/
    lac_export, lac_stage, lac_bb = export_lacerta_animations_and_stage(
        all_clips,
        {c['name']: baked[('lacerta', c['name'])] for c in all_clips},
        lacerta,
    )
    (out_dir / 'lacerta_crimson_flame_longsword.animation.json').write_text(
        json.dumps(lac_export, ensure_ascii=False, separators=(',', ':')) + '\n',
        encoding='utf-8',
        newline='\n',
    )

    # 3. 离线渲染：拉塞塔（3/4 视图与右侧）+ 玩家第三人称 + 剑尖轨迹红线
    weapon = json.loads(WEAPON_PATH.read_text(encoding='utf-8'))
    tex = np.asarray(Image.open(TEX_PATH).convert('RGBA')).astype(np.float32)

    sys.path.insert(0, str(LACERTA_DIR / 'tools'))
    import preview_anim as lac_pa  # noqa: E402
    import render_review as lac_R  # noqa: E402

    def draw_lacerta(clip_name: str, t: float, cam: Camera, trail_from: int):
        anim_key = f'crimson_sword_{clip_name}'
        pose_lac = lac_stage.pose(anim_key, t, main='idle', extra=None)
        lac_pa.sample(lac_stage.anims['face_battle'], t, pose_lac)
        lac_pa.sample(lac_stage.anims[anim_key], t, pose_lac)

        q, ltex = lac_R.load_geo(lac_stage.geo, lac_stage.tex, pose_lac)
        ltex_f32 = np.asarray(ltex, dtype=np.float32)
        if ltex_f32.max() <= 1.01:
            ltex_f32 = ltex_f32 * 255.0
        frames = baked[('lacerta', clip_name)]
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
        pose_c = {n: (p, euler_matrix(e)) for n, (p, e) in frames[i][1].items()}
        W = lacerta.world(pose_c)
        M_wep = mesh_matrix(lacerta, W)
        quads = weapon_quads(weapon, tex, M_wep, 'weapon')
        for Ow, Uw, Vw, Nw, uv in q:
            quads.append((Ow, Uw, Vw, Nw, uv, ltex_f32, None))
        trail = []
        for j in range(max(0, i - trail_from), i + 1):
            pj = {n: (p, euler_matrix(e)) for n, (p, e) in frames[j][1].items()}
            trail.append((mesh_matrix(lacerta, lacerta.world(pj)) @ TIP)[:3])
        return render(quads, cam, trail=trail if len(trail) > 1 else None)

    def draw_player(clip_name: str, t: float, cam: Camera, trail_from: int):
        frames = baked[('player', clip_name)]
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
        pose = {n: (p, euler_matrix(e)) for n, (p, e) in frames[i][1].items()}
        W = player.world(pose)
        quads = weapon_quads(weapon, tex, mesh_matrix(player, W), 'weapon') + mannequin_quads(W)
        trail = []
        for j in range(max(0, i - trail_from), i + 1):
            pj = {n: (p, euler_matrix(e)) for n, (p, e) in frames[j][1].items()}
            trail.append((mesh_matrix(player, player.world(pj)) @ TIP)[:3])
        return render(quads, cam, trail=trail if len(trail) > 1 else None)

    cam_lac_front = Camera(-28, 10, 3.6, (0, 24, -2), (260, 300))
    cam_lac_side = Camera(78, 8, 3.6, (0, 24, -2), (260, 300))
    cam_ply_front = Camera(-32, 10, 5.2, (0, 18, -3), (260, 300))

    for clip in all_clips:
        cname = clip['name']
        if only and cname not in only:
            continue
        L = clip['length']
        marker_times = []
        for v in (clip.get('markers') or {}).values():
            if isinstance(v, (int, float)):
                marker_times.append(round(float(v), 3))
            elif isinstance(v, (list, tuple)):
                marker_times.extend(round(float(x), 3) for x in v if isinstance(x, (int, float)))
        times = sorted({round(L * i / 6, 3) for i in range(7)} | set(marker_times))
        rows = [
            ('拉塞塔 · 斜前', [(f'{t:.2f}s', draw_lacerta(cname, t, cam_lac_front, 14)) for t in times]),
            ('拉塞塔 · 右侧', [(f'{t:.2f}s', draw_lacerta(cname, t, cam_lac_side, 14)) for t in times]),
            ('玩家 · 第三人称', [(f'{t:.2f}s', draw_player(cname, t, cam_ply_front, 14)) for t in times]),
        ]
        sheet(rows, f'{cname}  ({L:.2f}s)  markers={clip.get("markers", {})}', out_dir / f'{cname}.png')
        print('Rendered sheet:', cname)

    highlights = [
        ('待机 · 龙姬拖剑', 'idle', 0.0),
        ('格挡 · 左掌托脊', 'guard', 0.0),
        ('一式 · 苍炎大横扫', 'cleave_1', 0.36),
        ('二式 · 逆鳞单手定格', 'rend_2', 0.40),
        ('三式 · 绯龙升龙挑', 'ascend_3', 0.44),
        ('四式 · 腾空蓄压', 'cataclysm_4', 0.32),
        ('四式 · 天陨陨落重劈', 'cataclysm_4', 0.56),
        ('反击 · 破军龙牙', 'dragon_fang', 0.22),
    ]
    sheet(
        [
            ('拉塞塔招牌定格（上）', [(lbl, draw_lacerta(cn, t, cam_lac_front, 16)) for lbl, cn, t in highlights[:4]]),
            ('拉塞塔招牌定格（下）', [(lbl, draw_lacerta(cn, t, cam_lac_front, 16)) for lbl, cn, t in highlights[4:]]),
        ],
        '炽焰长剑 × 拉塞塔 · 二游动漫大剑动作总览',
        out_dir / 'overview.png',
    )

    skill_highlights = [
        ('主动格挡 · 托脊横防', 'guard', 0.0),
        ('破晓炎枪 · 突刺', 'crimson_thrust', 0.22),
        ('炽焰旋风 · 一转前掠', 'vortex_rush', 0.20),
        ('炽焰旋风 · 二斩爆发', 'vortex_rush', 0.48),
        ('红莲劫焰 · 技能横扫', 'flame_sweep', 0.36),
        ('炽翼坠星 · 悬停倒持', 'skyfall_judgement', 0.30),
        ('炽翼坠星 · 贯地破阵', 'skyfall_judgement', 0.54),
        ('逆鳞十文字 · 双连终结', 'cross_rend', 0.52),
    ]
    sheet(
        [
            ('战技与衍生动作（上）', [(lbl, draw_lacerta(cn, t, cam_lac_front, 16)) for lbl, cn, t in skill_highlights[:4]]),
            ('战技与衍生动作（下）', [(lbl, draw_lacerta(cn, t, cam_lac_front, 16)) for lbl, cn, t in skill_highlights[4:]]),
        ],
        '炽焰长剑 × 拉塞塔 · 防御与火焰战技全览',
        out_dir / 'skills_overview.png',
    )

    if want_gif:
        def make_seq_gif(gif_name: str, segments: list[tuple[str, float]]):
            imgs = []
            for cname, dur in segments:
                n = max(2, int(round(dur * 24)))
                for i in range(n):
                    t = min(dur, i / 24.0)
                    a = draw_lacerta(cname, t, cam_lac_front, 10)
                    b = draw_player(cname, t, cam_ply_front, 10)
                    im = Image.new('RGB', (a.width + b.width, a.height))
                    im.paste(a, (0, 0))
                    im.paste(b, (a.width, 0))
                    imgs.append(im.convert('P', palette=Image.ADAPTIVE, colors=255))
            imgs[0].save(out_dir / gif_name, save_all=True, append_images=imgs[1:], duration=42, loop=0)
            print('Rendered gif:', gif_name)

        make_seq_gif('combo_4hit.gif', [('cleave_1', 0.60), ('rend_2', 0.60), ('ascend_3', 0.64), ('cataclysm_4', 1.16)])
        make_seq_gif('guard_parry_fang.gif', [('guard', 0.60), ('parry', 0.12), ('dragon_fang', 0.66)])
        make_seq_gif('draw_idle_sheathe.gif', [('draw', 1.10), ('idle', 1.20), ('sheathe', 1.30)])
        make_seq_gif('skills_showcase.gif', [
            ('guard', 0.50),
            ('flame_sweep', 0.96),
            ('crimson_thrust', 0.72),
            ('vortex_rush', 0.90),
            ('cross_rend', 0.94),
            ('skyfall_judgement', 1.06),
        ])

    print('All animations baked & rendered successfully to', out_dir)


if __name__ == '__main__':
    main()
