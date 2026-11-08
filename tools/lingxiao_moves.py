# -*- coding: utf-8 -*-
"""凌霄（单手直剑）的动作编排，以及烘焙、导出与预览。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/lingxiao_moves.py <预览输出目录> [动作名… | combo] [--gif]

网格直接读仓库里的 combat/weapons/lingxiao_sword.json（原点在剑柄里，主手握点 0.65，剑尖 +Y，刃宽沿 X）。
编排坐标见 combat_author.py 开头：剑与手在躯干坐标里，以两肩中点为原点、臂长为单位；前 -Z、上 +Y、角色右手 +X。

仙侠剑的路数：剑走轻灵，一招一条线，挥到头定住一下再接下一招；左手不握剑，平时负在身后，出招时捏剑指前引或后扬。
玩家方块手臂没有手腕：剑花、云剑这类“剑在手里转”的动作用 raxial（剑在拳中绕前臂转，方块拳头看不出来）。
御剑时脚下的剑由载具自己画（FlyingSwordRenderer，剑尖顺着飞行方向）；起飞把剑抛到脚下、交接后骨架里的剑缩成 0。
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

from combat_author import Baker, bedrock, euler_matrix, frame, matrix_euler, npc_parried, player_skeleton
from combat_render import Camera, mannequin_quads, render, sheet, weapon_quads

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
WEAPON = json.loads((ASSETS / 'combat/weapons/lingxiao_sword.json').read_text(encoding='utf-8'))
# 剑尖（武器网格单位），预览画轨迹、查入地用。
TIP = np.array([0, 21.8, 0, 1])
# 脚踩的位置：网格里两个踏脚挂点（RideRear / RideFront）的中点。载具渲染按同一点放剑。
FOOT_ON_BLADE = 6.7
# 御剑时剑放大的倍数（载具渲染用同一个数，见 FlyingSwordRenderer.SCALE）。
FLIGHT_SCALE = 1.8
# 起飞时骨架里的剑交给载具的时刻（秒）：之后骨架里的剑缩成 0，载具开始画（见 FlyingSwordRenderer.HANDOFF）。
HANDOFF = 0.56
# 剑鞘佩在左腰（躯干坐标，像素）：鞘口在腰前左侧，鞘身斜向后下。
MOUTH = np.array([-4.6, 13.6, -2.6])
CARRY = frame((-0.25, -0.45, 0.86), (0, 0.88, 0.46))
# 拔剑、归鞘时左手把鞘横托到腹前左侧（鞘口在左手处，鞘身朝左），右手沿鞘向右拔出：
# 剑比手臂长，鞘挂在腰侧时右手够不到拔出的位置，横托过来两臂一展正好。
PRESENT_MOUTH = np.array([-6.0, 15.5, -6.5])
PRESENT_GRIP = np.array([11.0, 20.0, -10.0])
DRAWN = 20.8  # 拔到剑尖刚出鞘口（网格单位）

# ---------------------------------------------------------------- 常用姿势

HANG_L = (-0.42, -0.98, 0.04)
HANG_R = (0.42, -0.98, 0.04)
# 负手：左手背在腰后正中。
BEHIND = (-0.04, -0.74, 0.40)
# 待机：右手垂在身侧握剑，剑尖接近水平指向前下；左手负在身后。
IDLE = dict(grip=(0.44, -0.86, -0.18), blade=(0.35, 0.08, -0.93), edge=(0.05, -0.95, 0.1),
            rmode='sword', lmode='free', lhand=BEHIND, up=(0, 6, 0), chest=(0, 0, 0), hips=(0, -0.02, 0),
            rfoot=(0.16, 0, -0.08, -8), lfoot=(-0.18, 0, 0.1, 14),
            # 第一人称看不到腰下：剑在右前方，剑尖斜指前上。
            fp=dict(grip=(0.34, -0.5, -0.62), blade=(-0.12, 0.3, -0.95), edge=(0.2, -0.95, -0.3)))
# 御剑站姿：右手捏剑诀立在胸前，左手负后。剑在脚下（骨架里缩成 0，载具画）。
FLIGHT = dict(rmode='free', rhand=(0.14, -0.16, -0.46), rhand_rot=(-90, 0, 0), lmode='free', lhand=BEHIND,
              up=(0, 0, 0), chest=(-4, 0, 0), hips=(0, 0, 0),
              fp=dict(rhand=(0.32, -0.3, -0.5)))


def present():
    """左手横托剑鞘的 saya（鞘绕鞘口转的欧拉角）与 saya_off（鞘口平移，臂长）。"""
    axis = unit(PRESENT_MOUTH - PRESENT_GRIP)
    delta = rotation_between(CARRY[:, 1], axis)
    return dict(saya=tuple(np.round(matrix_euler(delta), 3)), saya_off=tuple(np.round((PRESENT_MOUTH - MOUTH) / 12.5, 4)))


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
    """一剑：剑尖方向 blades[0] → blades[1] → blades[2]（沿球面转过去），握点沿过 grips[1] 的弧线走，
    刃始终朝着剑走的方向。t0 到 t1 中间快、两头慢；hold 给出时在终点定住到这一刻。extra 加在每一帧上。"""
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
    """一剑定住时的握点、剑尖、刃（下一招从这里起手），连同第一人称的那一套。"""
    key = keys[-1]
    out = dict(grip=key['grip'], blade=key['blade'], edge=key['edge'])
    if 'fp' in key:
        out['fp'] = {n: key['fp'][n] for n in ('grip', 'blade', 'edge') if n in key['fp']}
    return out


def first_person(keys, alt):
    """给一串刀路关键帧（arc、circle 生成）配上第一人称的另一条刀路：同样的时刻，按顺序一一对应；
    第一人称要单独取景（docs/COMBAT_WEAPON_WORKFLOW.md 第 5 节），挥到头时剑要留在画面里。"""
    for i, key in enumerate(keys):
        b = alt[min(i, len(alt) - 1)]
        key['fp'] = dict(key.get('fp', {}), grip=b['grip'], blade=b['blade'], edge=b['edge'])
    return keys


def circle(t0, t1, grip, start, turns, up=0.05, radius=0.06, **extra):
    """云剑：剑平着在头顶绕竖直轴转圈（俯视逆时针：右 → 前 → 左 → 后），剑在拳中绕前臂转（raxial）。
    start 是起始剑尖的方位角（度，0 指右、-90 指前）；握点跟着画一个小圈，看上去是手腕在领剑。"""
    keys = []
    steps = max(2, int(round(turns * 8)))
    for i in range(steps + 1):
        a = math.radians(start - 360 * turns * i / steps)
        blade = (math.cos(a), up, math.sin(a))
        g = (grip[0] - radius * math.cos(a), grip[1], grip[2] - radius * math.sin(a))
        keys.append(k(t0 + (t1 - t0) * i / steps, grip=g, blade=blade, edge=(-math.sin(a), 0, math.cos(a)), raxial=True, **extra))
    return keys


def flower(t0, t1, grip, turns, tilt=10, **extra):
    """剑花：剑在身体右侧的竖直面里绕握点转圈（剑尖 前 → 下 → 后 → 上），剑在拳中绕前臂转（raxial）。"""
    keys = []
    steps = int(round(turns * 8))
    lean = euler_matrix((0, 0, -tilt))  # 转圈的面略向外倾，避开大腿
    for i in range(steps + 1):
        th = math.radians(360 * turns * i / steps)
        blade = lean @ np.array([0, -math.sin(th), -math.cos(th)])
        edge = lean @ np.array([0, -math.cos(th), math.sin(th)])
        keys.append(k(t0 + (t1 - t0) * i / steps, grip=grip, blade=tuple(blade), edge=tuple(edge), raxial=True, **extra))
    return keys


def under_feet(scale, blade=(0, 0, -1), edge=(1, 0, 0)):
    """剑平放在脚下（剑面朝上、剑尖朝前）时的主手握点：网格里踏脚那一点落在两脚中间、剑面顶住鞋底。
    剑按 scale 放大（绕网格原点）；只对第三人称骨架算（两肩中点 (0, 23, 0)，臂长 12.5）。"""
    s = 0.8
    R = frame(blade, edge)
    feet = np.array([0, -0.25, 0])
    grip = feet - R @ np.array([0, (FOOT_ON_BLADE * scale - 0.65) * s, 0])
    return tuple((grip - np.array([0, 23, 0])) / 12.5)

# ---------------------------------------------------------------- 动作


def clips():
    out = []

    out.append(dict(name='idle', length=2.4, loop='loop', keys=[
        k(0.0, IDLE),
        k(1.2, IDLE, grip=(0.44, -0.81, -0.28), up=(2, 8, 0), chest=(-2, 0, 0), lhand=(-0.04, -0.72, 0.41)),
        k(2.4, IDLE),
    ]))

    # 出鞘：左手从左腰把剑鞘横托到腹前，右手越过身前握住剑柄，沿鞘向右一展把剑拔出；
    # 出鞘后剑尖向上划一个立圆收到右前方，左手送鞘回腰、负到身后。
    hold = present()
    out.append(dict(name='draw', length=1.0, loop='once', keys=[
        k(0.0, rmode='free', rhand=HANG_R, lmode='free', lhand=HANG_L, sheathed=0, saya=(0, 0, 0), saya_off=(0, 0, 0), up=(0, 0, 0)),
        k(0.14, rmode='sword', lmode='saya', blend=0.14, sheathed=0, up=(0, 16, 0), hips=(0, -0.03, 0)),
        k(0.30, sheathed=0, up=(0, 12, 0), **hold),
        k(0.50, sheathed=DRAWN, up=(0, -12, 0), **hold),
        k(0.66, grip=(0.46, 0.14, -0.50), blade=(0.30, 0.92, 0.0), edge=(1, 0, 0), up=(0, -8, 0),
          lmode='free', lhand=(-0.3, -0.62, 0.1), blend=0.2, **hold),
        k(0.84, grip=(0.52, -0.58, -0.30), blade=(0.5, -0.2, -0.84), edge=(0.2, -0.9, 0.3), up=(0, 2, 0), lhand=BEHIND,
          saya=(0, 0, 0), saya_off=(0, 0, 0)),
        k(1.0, IDLE),
    ]))

    # 一·清风：剑举到右上方（剑尖指向右后上），从右上斜劈到左前下，腰往左拧；左手剑指先前引，劈下时甩向左后。
    # 剑劈到左前下方就收住，剑尖指着对手的膝前（不顺势甩过身体）：剑走轻灵，也让方块拳头握得正。
    cock1 = dict(grip=(0.54, 0.20, -0.16), blade=(0.38, 0.5, 0.78), edge=(-0.4, 0.62, -0.68))
    s1 = arc(0.08, 0.17, [cock1['grip'], (0.16, -0.12, -0.84), (-0.22, -0.56, -0.62)],
             [cock1['blade'], (0.05, 0.55, -0.83), (-0.22, -0.42, -0.88)], hold=0.36)
    # 第一人称：剑竖在右上方起手，劈到画面中下偏左收住。
    cock1['fp'] = dict(grip=(0.5, 0.1, -0.6), blade=(0.35, 0.9, 0.25), edge=(-0.4, 0.2, -0.9))
    first_person(s1, arc(0.08, 0.17, [cock1['fp']['grip'], (0.3, -0.1, -0.6), (0.1, -0.3, -0.5)],
                         [cock1['fp']['blade'], (0.05, 0.6, -0.8), (-0.5, -0.25, -0.83)], hold=0.36))
    out.append(dict(name='attack_1', length=0.5, loop='once', markers={'contact': 0.13, 'cancel': 0.4}, keys=[
        k(0.0, IDLE),
        k(0.08, cock1, up=(-4, -26, 0), lhand=(-0.30, -0.18, -0.62), ease='hold'),
        *s1,
        k(0.17, up=(6, 30, 0), lhand=(-0.74, -0.34, 0.24), hips=(0, -0.08, -0.04),
          rfoot=(0.16, 0, -0.32, -8), lfoot=(-0.2, 0, 0.18, 18)),
        k(0.36, up=(6, 28, 0), lhand=(-0.72, -0.36, 0.22), hips=(0, -0.08, -0.04), ease='hold'),
        k(0.5, IDLE),
    ]))

    # 二·拨云：剑在左下略一沉，反手从左下往右上撩起，腰往右拧，剑斜举过右肩；左手剑指收回胸前。
    dip = dict(grip=(-0.36, -0.62, -0.40), blade=(-0.6, -0.65, -0.45))
    s2 = arc(0.05, 0.15, [dip['grip'], (0.10, -0.30, -0.88), (0.54, 0.28, -0.36)],
             [dip['blade'], (0.06, 0.32, -0.95), (0.55, 0.66, 0.50)], hold=0.38)
    dip['fp'] = dict(grip=(0.05, -0.45, -0.5), blade=(-0.6, -0.6, -0.5))
    first_person(s2, arc(0.05, 0.15, [dip['fp']['grip'], (0.25, -0.2, -0.6), (0.45, 0.0, -0.65)],
                         [dip['fp']['blade'], (0.05, 0.35, -0.94), (0.3, 0.9, -0.3)], hold=0.38))
    out.append(dict(name='attack_2', length=0.52, loop='once', markers={'contact': 0.12, 'cancel': 0.4}, keys=[
        k(0.0, last(s1), up=(6, 28, 0), lmode='free', lhand=(-0.72, -0.36, 0.22), hips=(0, -0.08, -0.04)),
        k(0.05, dip, edge=(0.5, -0.3, -0.8), up=(8, 32, 0), lhand=(-0.6, -0.4, 0.1)),
        *s2,
        k(0.15, up=(-6, -30, 0), lhand=(-0.26, -0.22, -0.6), hips=(0, -0.03, 0),
          rfoot=(0.16, 0, -0.1, -8), lfoot=(-0.2, 0, 0.24, 18)),
        k(0.38, up=(-6, -28, 0), lhand=(-0.24, -0.24, -0.58), hips=(0, -0.03, 0), ease='hold'),
        k(0.52, IDLE),
    ]))

    # 三·云月：剑举到头顶平着绕一圈（云剑），再落到肩高从右往左平斩；左手剑指在胸前领着，斩出时甩开。
    over = (0.14, 0.95, -0.14)
    cloud = circle(0.07, 0.27, over, start=10, turns=1.0, up=0.06)
    s3 = arc(0.28, 0.38, [(0.62, 0.06, -0.30), (0.06, 0.0, -0.92), (-0.56, 0.02, -0.40)],
             [(0.98, 0.06, -0.18), (0.0, 0.05, -1), (-0.55, 0.05, 0.83)], hold=0.52)
    # 第一人称：头顶看不到，云剑放到眼前上方转；平斩从右扫到画面左边收住。
    over_fp = (0.3, 0.2, -0.4)
    first_person(cloud, circle(0.07, 0.27, over_fp, start=10, turns=1.0, up=0.06))
    first_person(s3, arc(0.28, 0.38, [(0.35, 0.0, -0.45), (0.2, -0.15, -0.5), (0.1, -0.25, -0.5)],
                         [(0.98, 0.06, -0.18), (0.0, 0.05, -1), (-0.75, 0.05, -0.65)], hold=0.52))
    out.append(dict(name='attack_3', length=0.7, loop='once', markers={'contact': 0.33, 'cancel': 0.56}, keys=[
        k(0.0, last(s2), up=(-6, -28, 0), lmode='free', lhand=(-0.24, -0.24, -0.58)),
        k(0.07, grip=over, blade=(0.98, 0.06, 0.17), edge=(-0.17, 0, 0.98), raxial=True, up=(-10, -6, 0), lhand=(-0.16, -0.26, -0.56),
          fp=dict(grip=over_fp)),
        *cloud,
        k(0.27, up=(-10, 6, 0), lhand=(-0.18, -0.28, -0.56), raxial=False),
        *s3,
        k(0.28, up=(0, -32, 0), lhand=(-0.2, -0.3, -0.55)),
        k(0.38, up=(4, 34, 0), lhand=(-0.76, -0.3, 0.26), hips=(0, -0.06, 0),
          rfoot=(0.16, 0, -0.2, -8), lfoot=(-0.2, 0, 0.2, 18)),
        k(0.52, up=(4, 32, 0), lhand=(-0.74, -0.32, 0.24), hips=(0, -0.06, 0), ease='hold'),
        k(0.7, IDLE),
    ]))

    # 四·凌霄：收剑到右腰、剑尖朝前，左手剑指直指前方瞄着；弓步一记平剑直刺，左手剑指甩到身后头顶，
    # 定住亮相，再收回待机。刺击的剑按髋部朝向写（space='hips'），躯干怎么拧都笔直朝前。
    cock4 = dict(grip=(0.46, -0.56, 0.12), blade=(0.3, -0.05, -0.95), edge=(1, 0, 0),
                 fp=dict(grip=(0.38, -0.5, -0.28), blade=(0.05, 0.05, -1)))
    # 第一人称的刺击从右下朝准星刺出（笔直朝前会缩成一个点，看不出刺）。
    thrust = dict(grip=(0.26, 0.02, -0.92), blade=(0, 0.02, -1), edge=(1, 0, 0), space='hips',
                  fp=dict(grip=(0.32, -0.32, -0.62), blade=(-0.25, 0.2, -0.95)))
    out.append(dict(name='attack_4', length=1.0, loop='once', markers={'contact': 0.2, 'cancel': 0.7}, keys=[
        k(0.0, last(s3), up=(4, 32, 0), lmode='free', lhand=(-0.74, -0.32, 0.24), hips=(0, -0.06, 0)),
        k(0.14, cock4, up=(-6, -30, 0), lhand=(-0.12, -0.14, -0.96), hips=(0, -0.02, 0.06), ease='hold',
          rfoot=(0.16, 0, 0.04, -8), lfoot=(-0.2, 0, 0.26, 18)),
        k(0.2, thrust, up=(8, 26, 0), lhand=(-0.6, 0.5, 0.34), hips=(0, -0.13, -0.26),
          rfoot=(0.16, 0, -0.7, -6), lfoot=(-0.2, 0, 0.3, 24), ease='lin'),
        k(0.24, thrust, grip=(0.26, 0.03, -0.93), up=(8, 26, 0), lhand=(-0.58, 0.54, 0.36), hips=(0, -0.13, -0.26)),
        k(0.62, thrust, grip=(0.26, 0.04, -0.91), up=(7, 25, 0), lhand=(-0.56, 0.56, 0.36), hips=(0, -0.13, -0.25), ease='hold',
          fp=dict(grip=(0.32, -0.31, -0.61))),
        k(1.0, IDLE),
    ]))

    # 被弹反：剑被磕向右后上方，左手甩开，身子后仰、胸口敞开；顶住一下露出破绽，再收回待机。
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

    # 归鞘：右侧挽两个剑花；左手把鞘横托到腹前，剑尖找到鞘口、向左推回去；鞘送回左腰，双手放下。
    flower_grip = (0.80, -0.10, -0.20)
    FLOWER_FP = (0.4, -0.2, -0.45)
    out.append(dict(name='sheathe', length=1.8, loop='hold', keys=[
        k(0.0, IDLE, saya=(0, 0, 0), saya_off=(0, 0, 0)),
        k(0.10, grip=flower_grip, blade=(0, 0, -1), edge=(0, -1, 0), raxial=True, up=(0, -8, 0), lhand=BEHIND,
          fp=dict(grip=FLOWER_FP, up=(0, 0, 0))),
        # 第一人称：剑花挽在画面右前方。
        *first_person(flower(0.10, 0.56, flower_grip, turns=2), flower(0.10, 0.56, FLOWER_FP, turns=2)),
        k(0.56, up=(0, -6, 0)),
        k(0.64, raxial=False, lmode='saya', blend=0.18),
        k(0.70, grip=(0.86, -0.12, -0.74), blade=(-0.9, -0.2, 0.3), edge=(0, -1, 0)),
        k(0.86, sheathed=DRAWN, up=(0, -10, 0), ease='hold', **hold),
        k(1.16, sheathed=0, up=(0, 12, 0), **hold),
        k(1.36, sheathed=0, up=(0, 14, 0), saya=(0, 0, 0), saya_off=(0, 0, 0)),
        k(1.46, sheathed=0, rmode='free', rhand=HANG_R, lmode='free', lhand=HANG_L, blend=0.22),
        k(1.8, sheathed=0, up=(0, 0, 0), hips=(0, 0, 0)),
    ]))

    # 起飞：右手把剑竖在面前、左手剑指贴住剑身（御剑诀），一推把剑送出去；剑往前上方飞出、翻平，
    # 绕回来放大落到脚下（交接给载具），双手收成御剑站姿。第一人称剑往前飞出画面即可。
    feet = under_feet(FLIGHT_SCALE)
    out.append(dict(name='flight_takeoff', length=0.8, loop='hold', markers={'handoff': HANDOFF}, keys=[
        k(0.0, IDLE),
        k(0.14, grip=(0.22, -0.04, -0.56), blade=(0, 1, -0.08), edge=(1, 0, 0), up=(0, 0, 0), chest=(0, 0, 0),
          lhand=(-0.06, -0.06, -0.56), hips=(0, -0.03, 0), ease='hold'),
        k(0.24, grip=(0.22, 0.10, -0.86), blade=(0, 0.9, -0.44), edge=(1, 0, 0), rmode='free', rhand=(0.24, -0.08, -0.66),
          rhand_rot=(-90, 0, 0), blend=0.06, fp=dict(grip=(0.22, 0.2, -1.1), rhand=(0.32, -0.2, -0.5))),
        k(0.34, grip=(0.1, 0.0, -1.6), blade=(0, 0.2, -0.98), edge=(1, 0, 0), lhand=(-0.3, -0.5, 0.1),
          fp=dict(grip=(0.1, 0.4, -2.4))),
        k(0.46, grip=(0.0, -1.5, -1.3), blade=(0, -0.2, -0.98), edge=(1, 0, 0), fp=dict(grip=(0.0, 0.5, -4.0))),
        k(0.56, grip=feet, blade=(0, 0, -1), edge=(1, 0, 0), ease='hold', fp=dict(grip=(0.0, 0.5, -5.0))),
        k(0.62, rhand=(0.18, -0.12, -0.5), lhand=(-0.1, -0.7, 0.36), fp=dict(rhand=(0.32, -0.28, -0.5))),
        k(0.8, FLIGHT),
    ]))
    out.append(dict(name='flight_idle', length=2.4, loop='loop', keys=[
        k(0.0, FLIGHT, grip=feet, blade=(0, 0, -1), edge=(1, 0, 0)),
        k(1.2, FLIGHT, grip=feet, blade=(0, 0, -1), edge=(1, 0, 0), chest=(-5, 0, 0), rhand=(0.14, -0.14, -0.47)),
        k(2.4, FLIGHT, grip=feet, blade=(0, 0, -1), edge=(1, 0, 0)),
    ]))
    # 落地：脚下的剑翻起来飞回右手，顺势收到待机。
    out.append(dict(name='flight_land', length=0.6, loop='hold', keys=[
        k(0.0, FLIGHT, grip=feet, blade=(0, 0, -1), edge=(1, 0, 0)),
        k(0.16, grip=(0.1, -1.0, -1.1), blade=(0, 0.5, -0.86), edge=(1, 0, 0), fp=dict(grip=(0.2, -0.3, -1.2))),
        k(0.30, grip=(0.32, -0.36, -0.62), blade=(0.08, 0.7, -0.7), edge=(1, 0, 0), rmode='sword', blend=0.08,
          up=(0, -6, 0), lhand=(-0.1, -0.66, 0.38)),
        k(0.6, IDLE),
    ]))
    for clip in out:
        clip.setdefault('stance', {'rfoot': IDLE['rfoot'], 'lfoot': IDLE['lfoot']})
        if clip['name'].startswith('attack_') or clip['name'] == 'flight_takeoff':
            # 第一人称的镜头不跟腰转：腰拧多了，手和剑会整个甩出画面，只留三分之一。
            # 左手剑指在第一人称里会挡在镜头前（后扬时整条胳膊从脸前扫过），放在左下画面外。
            for key in clip['keys']:
                if 'up' in key and 'up' not in key.get('fp', {}):
                    key['fp'] = dict(key.get('fp', {}), up=tuple(round(v * 0.35, 3) for v in key['up']))
                if 'lhand' in key:
                    key['fp'] = dict(key.get('fp', {}), lhand=(-0.6, -0.8, 0.1))
    return out


def weapon_scale(clip, t):
    """骨架里剑的放大：起飞时剑飞出后放大，交接后缩成 0（载具画）；落地时从脚下的大小缩回。"""
    name = clip['name']
    if name == 'flight_takeoff':
        if t >= HANDOFF - 1e-6:
            return 0.0
        u = min(1.0, max(0.0, (t - 0.24) / (0.5 - 0.24)))
        return 1 + (FLIGHT_SCALE - 1) * u * u * (3 - 2 * u)
    if name == 'flight_idle':
        return 0.0
    if name == 'flight_land':
        u = min(1.0, t / 0.3)
        return FLIGHT_SCALE + (1 - FLIGHT_SCALE) * u * u * (3 - 2 * u)
    return 1.0

# ---------------------------------------------------------------- 骨架


def skeletons():
    R, mouth = CARRY, MOUTH
    s_tp, s_fp = 0.8, 0.58
    player = player_skeleton(ASSETS / 'combat/skeletons/player_tp.json', weapon_scale=s_tp,
                             carry=(mouth - R @ np.array([0, 2.3 * s_tp, 0]), R))
    shoulder = np.array([0, 4, -3.5])
    fp = player_skeleton(ASSETS / 'combat/skeletons/player_fp.json', weapon_scale=s_fp,
                         carry=(mouth + shoulder - R @ np.array([0, 2.3 * s_fp, 0]), R))
    # 第一人称取景与打刀、流火相同：根骨同长剑第一人称，手的目标前移、抬高，剑留在画面里。
    fp.root = ((15.13, -1.82, 0), (0, 0, 12))
    fp.reach = np.array([-3.0, 3.0, -5.0])
    player.body = ((-4.4, 12.0, -2.5), (4.4, 21.0, 2.5))
    for sk, tag in ((player, 'player'), (fp, 'fp')):
        sk.tag = tag
        # 武器自己的骨骼（剑穗三节、踏脚挂点、剑镡宝石）挂在 Weapon 下，枢轴按武器缩放放进骨架。
        for b in WEAPON['bones']:
            sk.bones[b['name']] = dict(name=b['name'], parent=b['parent'],
                                       pivot=sk.weapon_pivot + np.array(b['pivot'], float) * sk.weapon_scale, rest=np.zeros(3))
            sk.order.append(b['name'])
    return player, fp

# ---------------------------------------------------------------- 剑穗


TASSELS = (('TasselLeft0', 'TasselLeft1', 'TasselLeft2'), ('TasselRight0', 'TasselRight1', 'TasselRight2'))
GRAVITY = 160.0  # 像素/秒²，约每秒 10 格
LAG = (0.045, 0.07, 0.095)  # 每节跟上一节（第一节跟视在重力）的时间常数（秒），越往下越慢
MAX_SWING = 115.0  # 剑穗离开剑柄延长线的最大角度，再大就甩进剑柄里了


def rotation_between(a, b):
    a, b = unit(a), unit(b)
    v = np.cross(a, b)
    c = float(a @ b)
    if np.linalg.norm(v) < 1e-9:
        return np.eye(3) if c > 0 else euler_matrix((180, 0, 0))
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx * (1 / (1 + c))


def clamp_cone(d, axis, limit):
    """把方向 d 限制在离 axis 不超过 limit 度的锥里。"""
    d, axis = unit(d), unit(axis)
    ang = math.degrees(math.acos(float(np.clip(d @ axis, -1, 1))))
    if ang <= limit:
        return d
    side = d - axis * float(d @ axis)
    side = unit(side) if np.linalg.norm(side) > 1e-9 else unit(np.cross(axis, (1, 0, 0)))
    r = math.radians(limit)
    return math.cos(r) * axis + math.sin(r) * side


def poses_of(frames):
    out = []
    for _, eul in frames:
        pose = {}
        for n, v in eul.items():
            pose[n] = (np.asarray(v[0], float), euler_matrix(v[1])) + ((np.asarray(v[2], float),) if len(v) > 2 else ())
        out.append(pose)
    return out


def tassels(clip, frames, sk):
    """剑穗：每节朝“视在重力”（重力减去剑柄尾的加速度）甩，一节跟一节、越往下越慢。只改剑穗骨骼。"""
    worlds = [sk.world(p) for p in poses_of(frames)]
    n = len(frames)
    dt = frames[1][0] - frames[0][0]
    loop = clip.get('loop') == 'loop'
    rest = np.array([0, -1.0, 0])
    for chain in TASSELS:
        pivot = np.append(sk.bones[chain[0]]['pivot'], 1)
        P = np.array([(w['Weapon'] @ pivot)[:3] for w in worlds])
        acc = np.zeros_like(P)
        for i in range(n):
            if loop:
                a, b = P[(i - 1) % (n - 1)], P[(i + 1) % (n - 1)]
            elif 0 < i < n - 1:
                a, b = P[i - 1], P[i + 1]
            else:
                continue
            acc[i] = (b - 2 * P[i] + a) / (dt * dt)
        # 加速度略微平滑，免得逐帧抖。
        smooth = acc.copy()
        for i in range(n):
            smooth[i] = acc[max(0, i - 2):min(n, i + 3)].mean(axis=0)
        dirs = None
        result = [None] * n
        for _ in range(2 if loop else 1):
            for i in range(n):
                M = worlds[i]['Weapon'][:3, :3]
                scale = np.linalg.norm(M[:, 1])
                if scale < 1e-6:
                    result[i] = [np.eye(3)] * 3
                    continue
                Rw = M / scale
                g = Rw.T @ (np.array([0, -GRAVITY, 0]) - smooth[i])
                target = clamp_cone(g, rest, MAX_SWING)
                if dirs is None:
                    dirs = [target.copy(), target.copy(), target.copy()]
                prev = target
                for j in range(3):
                    w = 1 - math.exp(-dt / LAG[j])
                    dirs[j] = clamp_cone(unit(dirs[j] + (prev - dirs[j]) * w), rest, MAX_SWING)
                    prev = dirs[j]
                result[i] = [rotation_between(rest, d) for d in dirs]
        for i, (t, eul) in enumerate(frames):
            parent = np.eye(3)
            for j, name in enumerate(chain):
                local = parent.T @ result[i][j]
                near = frames[i - 1][1][name][1] if i > 0 and name in frames[i - 1][1] else None
                eul[name] = (np.zeros(3), matrix_euler(local, near))
                parent = result[i][j]
    return frames


def post(clip, frames, sk):
    for t, eul in frames:
        s = weapon_scale(clip, t)
        pos, e = eul['Weapon'][0], eul['Weapon'][1]
        eul['Weapon'] = (pos, e, np.array([s, s, s]))
        for b in WEAPON['bones']:
            if not b['name'].startswith('Tassel'):
                eul[b['name']] = (np.zeros(3), np.zeros(3))
    frames = tassels(clip, frames, sk)
    if clip.get('loop') == 'loop':
        frames[-1] = (frames[-1][0], frames[0][1])
    return frames

# ---------------------------------------------------------------- 预览


def mesh(skeleton, W, bone='Weapon'):
    s = skeleton.weapon_scale
    T = np.eye(4); T[:3, 3] = skeleton.weapon_pivot
    return W[bone] @ T @ np.diag([s, s, s, 1.0])


def ridden():
    """载具画的剑（模型坐标）：剑面朝上、剑尖朝前，踏脚点在两脚中间、剑面顶住鞋底。"""
    s = 0.8 * FLIGHT_SCALE
    R = frame((0, 0, -1), (1, 0, 0))
    origin = np.array([0, -0.25, 0]) - R @ np.array([0, FOOT_ON_BLADE * s, 0])
    M = np.eye(4); M[:3, :3] = R * s; M[:3, 3] = origin
    return M


def frames_for(clip, n):
    times = [clip['length'] * i / (n - 1) for i in range(n)]
    for name, t in (clip.get('markers') or {}).items():
        if name in ('contact', 'cancel', 'handoff'):
            times.append(t)
    return sorted(set(round(t, 3) for t in times))


BONES = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
         'LeftArm', 'LeftForeArm', 'LeftHand', 'Weapon', 'Sheath', 'Root'] + [b['name'] for b in WEAPON['bones']]


def bake_all(all_clips, fps):
    player, fp = skeletons()
    baked = {}
    for who, sk in (('player', player), ('fp', fp)):
        baker = Baker(sk)
        for clip in all_clips:
            baked[(who, clip['name'])] = post(clip, baker.bake(clip, fps), sk)
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
                    print(f'{who} {clip_name} 剑柄偏角 平均 {sum(rs) / len(rs):.0f}° 最大 {max(rs):.0f}°')
    return player, fp, baked


def lowest_tip(sk, frames, clip):
    low = math.inf
    for (t, _), pose in zip(frames, poses_of(frames)):
        if weapon_scale(clip, t) == 0 or clip['name'].startswith('flight'):
            continue
        low = min(low, (mesh(sk, sk.world(pose)) @ TIP)[1])
    return low


ARM_BOXES = {b: v for b, v in __import__('combat_render').MANNEQUIN.items() if b.endswith(('Arm', 'Hand'))}


def fp_frame(sk, frames, clip, t, tex, size=(480, 270), fov=70):
    """第一人称预览：按 LongswordClient.hand 的取景（缩放 1/16、平移 (0,-29,0.6)、俯 2°）放进镜头坐标，透视投影。
    只画手臂和剑（第一人称看不到别的）。光线逐像素与方块所在平面求交，准确但慢，只用来看取景。"""
    from combat_render import box, L0, L1
    i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
    W = sk.world(poses_of([frames[i]])[0])
    C = np.eye(4); C[:3, :3] = euler_matrix((2, 0, 0))
    C = C @ np.diag([1 / 16, 1 / 16, 1 / 16, 1]) @ np.array([[1, 0, 0, 0], [0, 1, 0, -29], [0, 0, 1, 0.6], [0, 0, 0, 1.0]])
    quads = []
    for bone, boxes in ARM_BOXES.items():
        for start, end, color in boxes:
            quads += box(C @ W[bone], start, end, color=color)
    if weapon_scale(clip, frames[i][0]) > 0:
        quads += weapon_quads(WEAPON, tex, C @ mesh(sk, W), 'weapon')
    w, h = size
    f = (h / 2) / math.tan(math.radians(fov) / 2)
    img = np.zeros((h, w, 3), np.float32); img[:] = (214, 226, 240)
    img[h // 2:] = (150, 150, 150)
    zb = np.full((h, w), np.inf)
    xs, ys = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    rays = np.stack([(xs - w / 2) / f, -(ys - h / 2) / f, -np.ones_like(xs)], -1)
    for O, U, V, N, uv, qtex, color in quads:
        corners = np.array([O, O + U, O + V, O + U + V])
        if (corners[:, 2] > -0.02).any():
            continue
        px = w / 2 + f * corners[:, 0] / -corners[:, 2]
        py = h / 2 - f * corners[:, 1] / -corners[:, 2]
        xa, xb = max(0, int(px.min())), min(w - 1, int(px.max()) + 1)
        ya, yb = max(0, int(py.min())), min(h - 1, int(py.max()) + 1)
        if xa > xb or ya > yb:
            continue
        n = np.cross(U, V)
        if np.linalg.norm(n) < 1e-12:
            continue
        r = rays[ya:yb + 1, xa:xb + 1]
        denom = r @ n
        with np.errstate(divide='ignore', invalid='ignore'):
            tr = (O @ n) / denom
        X = r * tr[..., None] - O
        G = np.array([[U @ U, U @ V], [U @ V, V @ V]])
        if abs(np.linalg.det(G)) < 1e-14:
            continue
        Gi = np.linalg.inv(G)
        bu, bv = X @ U, X @ V
        s = Gi[0, 0] * bu + Gi[0, 1] * bv
        q = Gi[1, 0] * bu + Gi[1, 1] * bv
        ok = (tr > 0) & (s >= 0) & (s < 1) & (q >= 0) & (q < 1) & np.isfinite(tr)
        if qtex is not None:
            u0, v0, uw, vh = uv
            tu = np.clip(np.floor(u0 + np.nan_to_num(s) * uw - (1e-6 if uw < 0 else 0)).astype(int), 0, qtex.shape[1] - 1)
            tv = np.clip(np.floor(v0 + np.nan_to_num(q) * vh - (1e-6 if vh < 0 else 0)).astype(int), 0, qtex.shape[0] - 1)
            col = qtex[tv, tu]
            ok &= col[..., 3] >= 128
            rgb = col[..., :3]
        else:
            rgb = np.broadcast_to(np.array(color, np.float32), s.shape + (3,))
        ok &= tr < zb[ya:yb + 1, xa:xb + 1]
        if not ok.any():
            continue
        nn = N / (np.linalg.norm(N) or 1)
        light = min(1.0, 0.4 + 0.6 * (max(0, nn @ L0) + max(0, nn @ L1)))
        region = img[ya:yb + 1, xa:xb + 1]
        region[ok] = rgb[ok] * light
        zb[ya:yb + 1, xa:xb + 1][ok] = tr[ok]
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    from PIL import ImageDraw
    d = ImageDraw.Draw(out)
    d.line([(w / 2 - 5, h / 2), (w / 2 + 5, h / 2)], fill=(255, 255, 255)); d.line([(w / 2, h / 2 - 5), (w / 2, h / 2 + 5)], fill=(255, 255, 255))
    return out


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    out = Path(args[0])
    only = set(args[1:])
    gif = '--gif' in sys.argv
    out.mkdir(parents=True, exist_ok=True)
    all_clips = clips()
    by_name = {c['name']: c for c in all_clips}
    player, fp, baked = bake_all(all_clips, 60)
    for clip in all_clips:
        low = lowest_tip(player, baked[('player', clip['name'])], clip)
        if low < 2:
            print(f"注意 {clip['name']} 剑尖最低离地 {low:.1f} 像素")
    anim = ASSETS / 'combat/animations'
    for who, sk, view in (('player', player, 'tp'), ('fp', fp, 'fp')):
        data = bedrock(all_clips, {c['name']: baked[(who, c['name'])] for c in all_clips}, sk, BONES)
        text = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        (anim / f'lingxiao_{view}.animation.json').write_text(text, encoding='utf-8', newline='\n')
    tex = np.asarray(Image.open(ASSETS / 'textures/item/lingxiao_sword_rig.png').convert('RGBA')).astype(np.float32)
    cams = [Camera(-35, 8, 5.4, (0, 15, -6), (300, 300)), Camera(80, 6, 5.4, (0, 15, -6), (300, 300)),
            Camera(145, 14, 5.4, (0, 15, -6), (300, 300)), Camera(180, 70, 4.6, (0, 14, -4), (300, 300))]
    names = ('左前', '右侧', '右后', '俯视')

    def draw(name, t, cam, trail_from):
        clip = by_name[name]
        frames = baked[('player', name)]
        poses = poses_of(frames)
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
        W = player.world(poses[i])
        quads = mannequin_quads(W) + weapon_quads(WEAPON, tex, mesh(player, W, 'Sheath'), 'sheath')
        if weapon_scale(clip, frames[i][0]) > 0:
            quads += weapon_quads(WEAPON, tex, mesh(player, W), 'weapon')
        elif name.startswith('flight'):
            # 交接之后游戏里由载具画脚下的剑（剑尖顺着飞行方向）；预览按静止朝前画出来，看衔接。
            quads += weapon_quads(WEAPON, tex, ridden(), 'weapon')
        trail = []
        for j in range(max(0, i - trail_from), i + 1):
            if weapon_scale(clip, frames[j][0]) > 0:
                trail.append((mesh(player, player.world(poses[j])) @ TIP)[:3])
        return render(quads, cam, trail=trail if len(trail) > 1 else None)

    def pair(name, t):
        a, b = draw(name, t, cams[0], 10), draw(name, t, cams[2], 10)
        im = Image.new('RGB', (a.width * 2, a.height), (255, 255, 255))
        im.paste(a, (0, 0)); im.paste(b, (a.width, 0))
        return im

    for clip in all_clips:
        if only and clip['name'] not in only:
            continue
        rows = []
        times = frames_for(clip, 8)
        for v, cam in enumerate(cams):
            panels = []
            for t in times:
                tag = next((n for n, mt in (clip.get('markers') or {}).items() if abs(mt - t) < 1e-3), '')
                panels.append((f'{t:.2f}s {tag}', draw(clip['name'], t, cam, 14)))
            rows.append((names[v], panels))
        sheet(rows, f"凌霄 {clip['name']}  {clip['length']}s  标记 {clip.get('markers', {})}", out / f"{clip['name']}.png")
        if gif:
            images = [pair(clip['name'], i / 40) for i in range(int(clip['length'] * 40) + 1)]
            # 放慢一倍看：每帧 1/40 秒的动作，播 50 毫秒
            images[0].save(out / f"{clip['name']}.gif", save_all=True, append_images=images[1:], duration=50, loop=0)
        print('rendered', clip['name'])
    if '--fp' in sys.argv:
        for clip in all_clips:
            if only and clip['name'] not in only:
                continue
            times = frames_for(clip, 10)
            panels = []
            for t in times:
                tag = next((n for n, mt in (clip.get('markers') or {}).items() if abs(mt - t) < 1e-3), '')
                panels.append((f'{t:.2f}s {tag}', fp_frame(fp, baked[('fp', clip['name'])], clip, t, tex)))
            rows = [(r, panels[i * 5:(i + 1) * 5]) for i, r in enumerate(('第一人称', '')) if panels[i * 5:(i + 1) * 5]]
            sheet(rows, f"凌霄 第一人称 {clip['name']}", out / f"fp_{clip['name']}.png")
            print('rendered fp', clip['name'])
    if (gif and not only) or 'combo' in only:
        # 整套连招：每招播到取消点就接下一招（和游戏里按住左键一样），最后一招播完。
        seq = []
        for name in ('attack_1', 'attack_2', 'attack_3', 'attack_4'):
            end = by_name[name]['length'] if name == 'attack_4' else by_name[name]['markers']['cancel']
            seq += [(name, i / 40) for i in range(int(end * 40) + 1)]
        images = [pair(name, t) for name, t in seq]
        images[0].save(out / 'combo.gif', save_all=True, append_images=images[1:], duration=50, loop=0)
        print('rendered combo')
        if '--fp' in sys.argv:
            images = [fp_frame(fp, baked[('fp', name)], by_name[name], t, tex) for name, t in seq]
            images[0].save(out / 'fp_combo.gif', save_all=True, append_images=images[1:], duration=50, loop=0)
            print('rendered fp combo')


if __name__ == '__main__':
    main()
