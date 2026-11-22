# -*- coding: utf-8 -*-
"""剑祟的脚本动作（docs/JIAN_SUI_BOSS.md 第 16.1 节；维护者 2026-11-22 定：新动作都用脚本做）。

PR #280 的三招剑招（attack_1–3）、待势（idle）和散架（collapse）原样保留，这里只往 jian_sui.animation.json 里加新动作：
  parried        被弹反：剑被磕飞到身后上方，整个人仰身，僵住一会儿再慢慢收回（1.8 秒）
  stagger        失衡：单膝跪地，剑尖朝下插在身前撑住（0.5 秒，接 stagger_hold）
  stagger_hold   跪着喘（循环 2 秒）
  stagger_rise   站起来时快速一剑横扫（0.7 秒，0.42 秒出手）
  deflect        空着时手中剑一拨，拨开飞来的东西（0.4 秒）
  backstep       往后滑开（0.6 秒）：身子微俯，衣摆往前拖

做法：每个动作写几个关键姿势（相对待势第一帧的偏移，Blockbench 空间、欧拉角 Z·Y·X、度；位置是模型单位），
关键姿势之间按缓入缓出插值，按每秒 30 帧烘焙。没写到的骨骼不出轨道：NPC 的底层控制器一直在放待势，
衣摆、发束照常飘。手中古剑（Weapon 骨骼）每帧按「右手的变换 × 持剑关系」跟着右手，持剑关系取待势第一帧。

用法：uv run --python 3.12 --with numpy --with pillow python tools/jian_sui_moves.py [--preview 输出目录]
"""
import argparse
import json
import math
import pathlib
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from combat_author import affine, euler_matrix, matrix_euler, translate  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources/assets/logos_dimension'
GEO = RES / 'geckolib/models/entity/jian_sui.geo.json'
ANIM = RES / 'geckolib/animations/entity/jian_sui.animation.json'
FPS = 30
KEEP = ('idle', 'attack_1', 'attack_2', 'attack_3', 'collapse')


# ---------------------------------------------------------------- 骨架
class Rig:
    """Blockbench 空间的骨架：枢轴、父级、静止旋转（Bedrock 几何的 X 取反）。"""

    def __init__(self, geo):
        g = geo['minecraft:geometry'][0]
        self.bones = {}
        self.order = []
        for b in g['bones']:
            r = b.get('rotation', [0, 0, 0])
            self.bones[b['name']] = dict(parent=b.get('parent'), pivot=np.array([-b['pivot'][0], b['pivot'][1], b['pivot'][2]], float),
                                         rest=np.array([-r[0], -r[1], r[2]], float))
        seen = set()

        def visit(n):
            if n in seen:
                return
            p = self.bones[n]['parent']
            if p:
                visit(p)
            seen.add(n)
            self.order.append(n)
        for n in self.bones:
            visit(n)

    def world(self, pose):
        """pose[name] = (位置, 欧拉角)（动画的值，叠在静止旋转上）。"""
        W = {}
        for n in self.order:
            b = self.bones[n]
            pos, e = pose.get(n, (np.zeros(3), np.zeros(3)))
            local = translate(b['pivot'] + pos) @ affine(euler_matrix(b['rest'] + e), np.zeros(3)) @ translate(-b['pivot'])
            W[n] = W[b['parent']] @ local if b['parent'] else local
        return W


# ---------------------------------------------------------------- 读写动画
def sample(keys, t):
    if isinstance(keys, list):
        return np.array(keys, float)
    ks = sorted((float(k), np.array(v if isinstance(v, list) else v.get('post', v.get('vector')), float)) for k, v in keys.items())
    if t <= ks[0][0]:
        return ks[0][1]
    for (t0, v0), (t1, v1) in zip(ks, ks[1:]):
        if t <= t1:
            return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
    return ks[-1][1]


def clip_pose(clip, t):
    """Bedrock 动画 → Blockbench 空间（旋转 x、y 取反，位置 x 取反）。"""
    out = {}
    for b, ch in clip['bones'].items():
        r = sample(ch['rotation'], t) if 'rotation' in ch else np.zeros(3)
        p = sample(ch['position'], t) if 'position' in ch else np.zeros(3)
        out[b] = (np.array([-p[0], p[1], p[2]]), np.array([-r[0], -r[1], r[2]]))
    return out


def key(t):
    return f'{round(t, 4):g}' if t else '0.0'


def compress(track, eps=.02):
    """去掉落在前后两帧连线上的帧（同 tools/combat_author.compress 的做法）。"""
    items = sorted(track.items(), key=lambda kv: float(kv[0]))
    if len(items) <= 2:
        return dict(items)
    keep = [items[0]]
    for i in range(1, len(items) - 1):
        t0, v0 = float(keep[-1][0]), np.array(keep[-1][1])
        t1, v1 = float(items[i][0]), np.array(items[i][1])
        t2, v2 = float(items[i + 1][0]), np.array(items[i + 1][1])
        guess = v0 + (v2 - v0) * (t1 - t0) / (t2 - t0)
        if np.max(np.abs(guess - v1)) > eps:
            keep.append(items[i])
    keep.append(items[-1])
    return dict(keep)


# ---------------------------------------------------------------- 编排
def ease(k):
    k = min(1.0, max(0.0, k))
    return k * k * (3 - 2 * k)


def snap(k):
    """一下甩出去：前快后慢。"""
    k = min(1.0, max(0.0, k))
    return 1 - (1 - k) ** 3


class Clip:
    """几个关键姿势：[(时刻, {骨骼: (位置偏移, 角度偏移)}, 缓动)]。姿势之间按缓动插值，第一个姿势之前是待势。"""

    def __init__(self, name, length, keys, loop=False, hold=False, markers=None):
        self.name, self.length, self.keys, self.loop, self.hold = name, length, keys, loop, hold
        self.markers = markers or {}

    def offsets(self, t):
        keys = self.keys
        if t <= keys[0][0]:
            return keys[0][1]
        for (t0, a, _), (t1, b, curve) in zip(keys, keys[1:]):
            if t <= t1:
                k = (curve or ease)((t - t0) / (t1 - t0))
                out = {}
                for bone in set(a) | set(b):
                    pa, ra = a.get(bone, (np.zeros(3), np.zeros(3)))
                    pb, rb = b.get(bone, (np.zeros(3), np.zeros(3)))
                    out[bone] = (np.asarray(pa) + (np.asarray(pb) - np.asarray(pa)) * k, np.asarray(ra) + (np.asarray(rb) - np.asarray(ra)) * k)
                return out
        return keys[-1][1]


def P(**bones):
    """姿势：骨骼名 = (角度) 或 (角度, 位置)。角度、位置都是相对待势第一帧的偏移。"""
    out = {}
    for name, v in bones.items():
        if len(v) == 2 and hasattr(v[0], '__len__'):
            rot, pos = v
        else:
            rot, pos = v, (0, 0, 0)
        out[name] = (np.array(pos, float), np.array(rot, float))
    return out


REST = P()

# 角度的方向（Blockbench 空间，绕 X）：垂着的手脚 +X 往前摆、-X 往后摆；朝上的躯干、头 +X 往后仰、-X 往前俯；
# 小腿、前臂 -X 是弯（往后折）。下面的数都是相对待势第一帧的偏移。

# 被弹反：剑被磕飞到身后上方，仰身，右臂整条甩到头顶后面，左手往外摊开保持平衡；身子往后退半步、膝微屈
RECOIL = P(AllBody=((0, 0, 0), (0, -5, 4)), UpperBody=(22, 0, -4), AllHead=(18, 0, 0),
           RightArm=(-160, 0, 16), RightForeArm=(-28, 0, 0), RightHand=(-20, 0, 0),
           LeftArm=(30, 0, -40), LeftForeArm=(-25, 0, 0),
           RightLeg=(-8, 0, 0), RightLowerLeg=(-14, 0, 0), LeftLeg=(-14, 0, 0), LeftLowerLeg=(-18, 0, 0))
RECOIL_SWAY = P(AllBody=((0, 0, 0), (0, -6, 4.6)), UpperBody=(25, 3, -6), AllHead=(21, 4, 0),
                RightArm=(-166, 0, 20), RightForeArm=(-24, 0, 0), RightHand=(-16, 0, 0),
                LeftArm=(33, 0, -44), LeftForeArm=(-22, 0, 0),
                RightLeg=(-9, 0, 0), RightLowerLeg=(-16, 0, 0), LeftLeg=(-15, 0, 0), LeftLowerLeg=(-20, 0, 0))

# 失衡：单膝跪地（右膝着地、左脚在前踩住），上身前俯，右手握剑剑尖朝下插在身旁撑住，左手按在左膝上。
# 髋放低 24：右大腿往后 20°、小腿平贴在身后，膝盖正好着地；左大腿几乎水平往前、小腿竖直，脚踩在地上
KNEEL = P(AllBody=((0, 0, 0), (0, -24, 0)), UpperBody=(-20, 0, 0), AllHead=(-14, 0, 0),
          RightLeg=(-13, 0, 2), RightLowerLeg=(-44, 0, 0), RightFoot=(-20, 0, 0),
          LeftLeg=(57.5, 0, -4), LeftLowerLeg=(-55, 0, 0),
          RightArm=(30, 0, 6), RightForeArm=(-6, 0, 0),
          LeftArm=(48, 0, 10), LeftForeArm=(-38, 0, 0))
KNEEL_BREATH = P(AllBody=((0, 0, 0), (0, -25, 0)), UpperBody=(-23, 0, 0), AllHead=(-17, 0, 0),
                 RightLeg=(-13, 0, 2), RightLowerLeg=(-44, 0, 0), RightFoot=(-20, 0, 0),
                 LeftLeg=(57.5, 0, -4), LeftLowerLeg=(-55, 0, 0),
                 RightArm=(32, 0, 6), RightForeArm=(-7, 0, 0),
                 LeftArm=(50, 0, 10), LeftForeArm=(-40, 0, 0))
# 起身反斩：先拧身把剑拖到右后方，再一记横扫到左前方（手臂从身后平抡到身前）
RISE_WIND = P(AllBody=((0, 0, 0), (0, -12, 0)), UpperBody=(-6, 40, 0), AllHead=(-4, -22, 0),
              RightArm=(-50, 0, 70), RightForeArm=(-10, 0, 0),
              LeftArm=(20, 0, -20), LeftForeArm=(-30, 0, 0),
              RightLeg=(30, 0, 0), RightLowerLeg=(-30, 0, 0), LeftLeg=(-10, 0, 0), LeftLowerLeg=(-20, 0, 0))
RISE_CUT = P(AllBody=((0, 0, 0), (0, -6, -2)), UpperBody=(-10, -48, 0), AllHead=(-4, 24, 0),
             RightArm=(80, -20, -10), RightForeArm=(-6, 0, 0),
             LeftArm=(-10, 0, -40), LeftForeArm=(-24, 0, 0),
             RightLeg=(24, 0, 0), RightLowerLeg=(-18, 0, 0), LeftLeg=(-14, 0, 0), LeftLowerLeg=(-12, 0, 0))
# 拨开：右臂一抬、手腕一翻，剑竖着往外拨
FLICK = P(UpperBody=(0, -14, 0), RightArm=(70, 0, -18), RightForeArm=(-24, 0, 0), RightHand=(0, -30, 20))
# 后撤：身子微俯、重心压低，衣摆往前拖（身子往后滑时布料落在后面，看上去就是往前飘）
BACK_LEAN = P(AllBody=((0, 0, 0), (0, -6, 0)), UpperBody=(-10, 0, 0), AllHead=(-6, 0, 0),
              RightArm=(14, 0, 8), LeftArm=(10, 0, -10),
              RobeLower0=(-26, 0, 0), RobeLower1=(-22, 0, 0), RobeLower11=(-22, 0, 0), RobeFrontLower=(-24, 0, 0),
              RobeLower2=(-14, 0, 0), RobeLower10=(-14, 0, 0))

# 插剑（裂地）：双手把剑举过头顶，1.5 秒时整个人压下去，剑顺着手臂往前下方插进地板
PLUNGE_RAISE = P(AllBody=((0, 0, 0), (0, 2, 2)), UpperBody=(10, 0, 0), AllHead=(8, 0, 0),
                 RightArm=(168, 0, 10), RightForeArm=(-22, 0, 0),
                 LeftArm=(162, 0, -14), LeftForeArm=(-24, 0, 0))
PLUNGE_DOWN = P(AllBody=((0, 0, 0), (0, -16, -3)), UpperBody=(-34, 0, 0), AllHead=(-20, 0, 0),
                RightArm=(52, 0, 6), RightForeArm=(-10, 0, 0),
                LeftArm=(50, 0, -8), LeftForeArm=(-12, 0, 0),
                RightLeg=(30, 0, 0), RightLowerLeg=(-42, 0, 0), LeftLeg=(-18, 0, 0), LeftLowerLeg=(-34, 0, 0))
PLUNGE_BREATH = P(AllBody=((0, 0, 0), (0, -17, -3)), UpperBody=(-36, 0, 0), AllHead=(-22, 0, 0),
                  RightArm=(53, 0, 6), RightForeArm=(-10, 0, 0),
                  LeftArm=(51, 0, -8), LeftForeArm=(-13, 0, 0),
                  RightLeg=(30, 0, 0), RightLowerLeg=(-42, 0, 0), LeftLeg=(-18, 0, 0), LeftLowerLeg=(-34, 0, 0))
# 指向下面（第三阶段射巨剑）：左臂抬起指着那个人，右手的剑垂在身侧
POINT = P(UpperBody=(-6, 12, 0), AllHead=(-8, 0, 0), LeftArm=(100, 0, -6), LeftForeArm=(-4, 0, 0), LeftHand=(0, 0, 0))
# 被撬：胸口一下下往外顶，两手抽搐着抓向胸前的武器
PRIED_A = P(UpperBody=(14, 0, 0), AllHead=(16, 0, 0), RightArm=(70, 0, -30), RightForeArm=(-70, 0, 0),
            LeftArm=(70, 0, 30), LeftForeArm=(-70, 0, 0))
PRIED_B = P(UpperBody=(24, -6, 4), AllHead=(28, 6, 0), RightArm=(86, 0, -42), RightForeArm=(-86, 0, 0),
            LeftArm=(82, 0, 38), LeftForeArm=(-84, 0, 0))

CLIPS = [
    Clip('parried', 1.8, [(0, REST, None), (.12, RECOIL, snap), (.75, RECOIL_SWAY, ease), (1.2, RECOIL, ease), (1.8, REST, ease)]),
    Clip('stagger', .5, [(0, REST, None), (.18, P(AllBody=((0, 0, 0), (0, -8, 0)), UpperBody=(-8, 0, 0)), snap), (.5, KNEEL, ease)], hold=True),
    Clip('stagger_hold', 2.0, [(0, KNEEL, None), (1.0, KNEEL_BREATH, ease), (2.0, KNEEL, ease)], loop=True),
    Clip('stagger_rise', .7, [(0, KNEEL, None), (.28, RISE_WIND, ease), (.42, RISE_CUT, snap), (.7, REST, ease)],
         markers={'contact': .42}),
    Clip('deflect', .4, [(0, REST, None), (.1, FLICK, snap), (.4, REST, ease)]),
    Clip('backstep', .6, [(0, REST, None), (.12, BACK_LEAN, snap), (.4, BACK_LEAN, None), (.6, REST, ease)]),
    Clip('plunge', 1.8, [(0, REST, None), (.9, PLUNGE_RAISE, ease), (1.2, PLUNGE_RAISE, None), (1.5, PLUNGE_DOWN, snap), (1.8, PLUNGE_DOWN, None)],
         hold=True, markers={'impact': 1.5}),
    Clip('plunge_hold', 2.0, [(0, PLUNGE_DOWN, None), (1.0, PLUNGE_BREATH, ease), (2.0, PLUNGE_DOWN, ease)], loop=True),
    Clip('point', .6, [(0, REST, None), (.25, POINT, snap), (.6, POINT, None)], hold=True),
    Clip('pried', 2.6, [(0, PRIED_A, None), (.5, PRIED_B, snap), (.75, PRIED_A, ease), (1.2, PRIED_B, snap), (1.45, PRIED_A, ease),
                        (1.9, PRIED_B, snap), (2.6, PRIED_B, None)], hold=True),
]


# ---------------------------------------------------------------- 烘焙
def bake(rig, idle, clip, hold):
    """逐帧：待势第一帧 + 偏移；Weapon 跟着右手。返回 Bedrock 动画。"""
    base = clip_pose(idle, 0)
    n = max(1, int(round(clip.length * FPS)))
    tracks = {}
    prev = None
    for i in range(n + 1):
        t = min(clip.length, i / FPS)
        off = clip.offsets(t)
        pose = dict(base)
        for bone, (dp, dr) in off.items():
            p0, r0 = base.get(bone, (np.zeros(3), np.zeros(3)))
            pose[bone] = (p0 + dp, r0 + dr)
        W = rig.world(pose)
        Lw = np.linalg.inv(W['UpBody']) @ W['RightHand'] @ hold
        ew = matrix_euler(Lw[:3, :3], prev)
        prev = ew
        k = key(t)
        for bone in set(off) | {'Weapon'}:
            if bone == 'Weapon':
                pos, e = Lw[:3, 3], ew
            else:
                pos, e = pose[bone]
            tr = tracks.setdefault(bone, ({}, {}))
            tr[0][k] = [round(-float(e[0]), 3), round(-float(e[1]), 3), round(float(e[2]), 3)]
            tr[1][k] = [round(-float(pos[0]), 4), round(float(pos[1]), 4), round(float(pos[2]), 4)]
    bones = {}
    for bone, (rot, pos) in sorted(tracks.items()):
        ch = {'rotation': compress(rot)}
        if any(abs(v) > 1e-3 for vec in pos.values() for v in vec) or bone in ('Weapon', 'AllBody'):
            ch['position'] = compress(pos, .01)
        bones[bone] = ch
    out = {'animation_length': round(clip.length, 4), 'bones': bones}
    if clip.loop:
        out['loop'] = True
    elif clip.hold:
        out['loop'] = 'hold_on_last_frame'
    if clip.markers:
        out['timeline'] = {key(t): f'{m};' for m, t in clip.markers.items()}
    return out


def still(idle, length):
    """待势第一帧的所有骨骼，一段时长 length、每根骨骼一个关键帧：散架、倒放、残骸把 152 根骨骼写全（第 16.1 节），底下的待势就动不了它们。"""
    out = {}
    for b, ch in idle['bones'].items():
        out[b] = {k: {'0.0': [round(float(x), 4) for x in sample(v, 0)]} for k, v in ch.items() if k in ('rotation', 'position', 'scale')}
    return out


def derived(anim):
    """由散架 collapse 算的几段：残骸（最后一帧停住）、倒放（拼回去，3 秒）、去掉贯穿古剑的散架。"""
    clips = anim['animations']
    col, idle = clips['collapse'], clips['idle']
    L = col['animation_length']
    full = still(idle, L)
    # 残骸：每根骨骼取散架最后一帧
    remains = {b: dict(ch) for b, ch in full.items()}
    for b, ch in col['bones'].items():
        remains[b] = {k: {'0.0': [round(float(x), 4) for x in sample(v, L)]} for k, v in ch.items()}
    # 倒放：时间倒过来、拉长到 3 秒；发光的眼与光缝最后才亮起
    stretch = 1.5
    back = {b: dict(ch) for b, ch in full.items()}
    for b, ch in col['bones'].items():
        tr = {}
        for k, v in ch.items():
            if isinstance(v, list):
                tr[k] = v
                continue
            keys = sorted((float(t), val) for t, val in v.items())
            tr[k] = {key(round((L - t) * stretch, 4)): (val if isinstance(val, list) else val) for t, val in keys}
        back[b] = tr
    for g in ('ysmGlowEyeLeft', 'ysmGlowEyeRight', 'ysmGlowCoreSeam'):
        back[g] = {'scale': {'0.0': [0, 0, 0], key(L * stretch - .3): [0, 0, 0], key(L * stretch): [1, 1, 1]}}
    # 去掉贯穿古剑的散架：剑已经崩飞出去了
    no_axial = {b: dict(ch) for b, ch in col['bones'].items()}
    no_axial['AxialSword'] = {'scale': {'0.0': [0, 0, 0]}}
    return {
        'remains': {'loop': 'hold_on_last_frame', 'animation_length': .05, 'bones': remains},
        'reassemble': {'loop': 'hold_on_last_frame', 'animation_length': round(L * stretch, 4), 'bones': back},
        'collapse_noaxial': {'loop': 'hold_on_last_frame', 'animation_length': L, 'bones': no_axial},
    }


def hold_relation(rig, idle):
    """持剑关系：待势第一帧里 Weapon 相对右手的变换。"""
    pose = clip_pose(idle, 0)
    W = rig.world(pose)
    pos, e = pose['Weapon']
    Wweapon = W['UpBody'] @ affine(euler_matrix(e), pos)
    return np.linalg.inv(W['RightHand']) @ Wweapon


def preview(out_dir, anim, clips):
    """每个新动作几个时刻、两个机位的离线预览（不是游戏截图）。"""
    import combat_render as cr
    from PIL import Image
    geo = cr.Geo(GEO, RES / 'textures/entity/jian_sui.png')
    sword = RES / 'jian_sui/swords/jian_sui_sword.json'
    if not sword.exists():
        sword = ROOT.parents[1] / 'art/jian-sui-model-v6/game_assets/assets/logos_dimension/combat/weapons/jian_sui_sword.json'
    weapon = json.load(open(sword, encoding='utf-8'))
    weapon['texture_size'] = [512, 224]
    out_dir.mkdir(parents=True, exist_ok=True)
    rig = Rig(json.load(open(GEO, encoding='utf-8')))
    for c in clips:
        a = anim['animations'][c.name]
        idle = anim['animations']['idle']
        rows = []
        times = [round(c.length * f, 3) for f in (0, .15, .3, .5, .75, 1.0)]
        for yaw, pitch, label in ((30, 10, '前 3/4'), (110, 8, '侧')):
            row = []
            for t in times:
                pose = clip_pose(idle, t % idle['animation_length'])
                pose.update(clip_pose(a, t))
                full = {}
                for b in geo.bones:
                    p, e = pose.get(b['name'], (np.zeros(3), np.zeros(3)))
                    full[b['name']] = (p, euler_matrix(b['rest'] + e))
                quads = geo.quads(full)
                Wm = rig.world(pose)
                pw, ew = pose['Weapon']
                M = Wm['UpBody'] @ affine(euler_matrix(ew), pw)
                quads += cr.weapon_quads(weapon, geo.tex, M, 'weapon')
                row.append((f'{t:.2f}s', cr.render(quads, cr.Camera(yaw, pitch, 2.4, (0, 60, 0), (300, 330)))))
            rows.append((label, row))
        cr.sheet(rows, f'剑祟 {c.name}（{c.length}s）', out_dir / f'{c.name}.png')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--preview', type=pathlib.Path)
    ap.add_argument('--out', type=pathlib.Path, default=ANIM)
    args = ap.parse_args()
    geo = json.load(open(GEO, encoding='utf-8'))
    anim = json.load(open(ANIM, encoding='utf-8'))
    rig = Rig(geo)
    idle = anim['animations']['idle']
    hold = hold_relation(rig, idle)
    anims = anim['animations']
    for name in list(anims):
        if name not in KEEP:
            del anims[name]
    for c in CLIPS:
        anims[c.name] = bake(rig, idle, c, hold)
    anims.update(derived(anim))
    args.out.write_bytes(json.dumps(anim, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
    print('动作', list(anims))
    if args.preview:
        preview(args.preview, anim, CLIPS)
        print('预览', args.preview)


if __name__ == '__main__':
    main()
