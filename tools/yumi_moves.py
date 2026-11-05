# -*- coding: utf-8 -*-
"""神狐和弓的动作编排（弓道的射法），以及烘焙、导出与预览。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/yumi_moves.py <Minecraft MOD 目录> <输出目录> [动作名…] [--gif]

编排坐标同 combat_author.py：弓与手在躯干坐标里，以两肩中点为原点、臂长为单位；前 -Z、上 +Y、角色右手 +X。
弓的朝向用 up（上弭方向）和 aim（射向）给出；左手握弓（握点就是弓网格原点），右手扣弦。

弓的骨骼不在编排里直接写，由烘焙时算出：
- 搭箭点（弦的中点）：string 为 1 时跟着右手拳心走，为 0 时回到弦的静止位置。
- 弓身：按搭箭点被拉开的距离（占满弓的比例）逐节弯曲；shiver 是放箭后的回弹颤动，叠在比例上。
- 上下两段弦：从搭箭点指向弯过之后的两弭，沿长度方向伸缩。
- 箭（BowArrow）：箭筈在搭箭点，箭杆搭在握把上方的箭台；arrow 为 0 时缩成 0（还没取箭或已射出）。
整个人的转身用 turn（度）：拉弓时侧身 -90°，靶在左手一侧。
左手握弓的手臂在会与残心时完全伸直：straight 给出握点的高度和前后（躯干坐标），左右按该骨架的臂长算出（straight_grip）。
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from combat_author import (Baker, bbmodel_skeleton, bedrock, channel, euler_matrix, hermite, player_skeleton,
                           rot_x, rot_y, translate, unit)
from combat_render import FACES, L0, L1, Camera, Geo, box, mannequin_quads, render, sheet

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
WEAPON = ASSETS / 'combat/weapons/fox_yumi_bamboo.json'
ARROW = ASSETS / 'combat/weapons/yumi_arrow.json'

NOCK = np.array([0.0, 0.85, 3.08965625])
UPPER_LOOP = np.array([0.0, 21.57, 3.18])
LOWER_LOOP = np.array([0.0, -10.57, 3.04])
# 弓身各节的弯曲分配：中段弯得多、靠握把与两弭的地方硬。满弓时上、下段总弯角（度）。
UPPER_WEIGHTS = [0.15, 0.55, 0.9, 1.0, 1.0, 1.0, 0.9, 0.7, 0.5, 0.3]
LOWER_WEIGHTS = [0.3, 0.8, 1.0, 1.0, 0.8, 0.5, 0.3]
UPPER_BEND = 34.0
LOWER_BEND = 40.0
# 箭搭在握把右侧（弓网格 +X 一侧）、左拳上方；箭头露出弓外的长度（弓网格单位）。
ARROW_SIDE = 0.42
OVERHANG = 3.2

# ---------------------------------------------------------------- 弓的骨骼


def between(a, b):
    """把方向 a 转到 b 的最小旋转。"""
    a, b = unit(a), unit(b)
    v = np.cross(a, b)
    c = float(np.dot(a, b))
    if np.linalg.norm(v) < 1e-9:
        return np.eye(3) if c > 0 else rot_x(180)
    vx = np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])
    return np.eye(3) + vx + vx @ vx * (1 / (1 + c))


def add_bow(sk, weapon):
    """把弓的骨骼（网格单位的枢轴）按武器缩放挂到骨架的 Weapon 下。"""
    s = sk.weapon_scale
    for b in weapon['bones']:
        sk.bones[b['name']] = dict(name=b['name'], parent=b['parent'],
                                   pivot=sk.weapon_pivot + s * np.asarray(b['pivot'], float), rest=np.zeros(3))
    order, seen = [], set()

    def visit(name):
        if name in seen:
            return
        parent = sk.bones[name]['parent']
        if parent:
            visit(parent)
        seen.add(name)
        order.append(name)
    for name in sk.bones:
        visit(name)
    sk.order = order
    sk.grips = (0.0, 0.0)  # 握点就是弓网格原点（左手），右手不按刀柄握
    sk.full_draw = 1.0
    sk.arrow_len = 21.0
    sk.bow_bones = [b['name'] for b in weapon['bones']]


def straight_grip(sk, y, z):
    """左臂完全伸直时的握点（编排坐标）：高度 y、前后 z 给定，朝靶的一侧（-X）伸到手臂全长，肘部不弯。"""
    S = sk.pivot('LeftArm')
    reach = (np.linalg.norm(sk.pivot('LeftForeArm') - S) + np.linalg.norm(sk.pivot('LeftHand') - sk.pivot('LeftForeArm'))
             + abs(sk.hand_grip[1]))
    dy, dz = sk.center[1] + y * sk.arm - S[1], sk.center[2] + z * sk.arm - S[2]
    x = S[0] - math.sqrt(reach * reach - dy * dy - dz * dz)
    return ((x - sk.center[0]) / sk.arm, y, z)


class YumiBaker(Baker):
    def sword_key(self, clip, key):
        if key.get('straight') is not None:
            key = dict(key, grip=straight_grip(self.sk, *key['straight']))
        return super().sword_key(clip, key)

    def scalar(self, clip, t, field, default):
        return float(np.atleast_1d(hermite(channel(clip, field, default), t))[0])

    def mesh_to_world(self, W):
        sk = self.sk
        return W['Weapon'] @ translate(sk.weapon_pivot) @ np.diag([sk.weapon_scale] * 3 + [1.0])

    def nock(self, clip, t, W):
        """搭箭点（弓网格坐标）与拉开的距离。"""
        sk = self.sk
        fist = (W['RightHand'] @ np.append(sk.pivot('RightHand') + sk.hand_grip, 1))[:3]
        local = (np.linalg.inv(self.mesh_to_world(W)) @ np.append(fist, 1))[:3]
        w = min(1.0, max(0.0, self.scalar(clip, t, 'string', 0.0)))
        # 搭箭点钉在弦上：只跟着拳心前后拉开；上下、左右只跟一小部分，箭始终近乎垂直于弓。
        fx, fy = getattr(sk, 'nock_follow', (0.3, 0.2))
        pulled = np.array([NOCK[0] + fx * (local[0] - NOCK[0]), NOCK[1] + fy * (local[1] - NOCK[1]), local[2]])
        nock = NOCK + w * (pulled - NOCK)
        return nock, max(0.0, nock[2] - NOCK[2])

    def pose(self, clip, t, prev=None):
        pose = super().pose(clip, t, prev)
        sk = self.sk
        s = sk.weapon_scale
        W = sk.world(pose)
        nock, drawn = self.nock(clip, t, W)
        d = drawn / sk.full_draw + self.scalar(clip, t, 'shiver', 0.0)
        pose['BowGrip'] = (np.zeros(3), np.eye(3))
        upper, lower = getattr(sk, 'bend', (UPPER_BEND, LOWER_BEND))
        for i, w in enumerate(UPPER_WEIGHTS):
            pose[f'BowUpper{i + 1}'] = (np.zeros(3), rot_x(d * upper * w / sum(UPPER_WEIGHTS)))
        for i, w in enumerate(LOWER_WEIGHTS):
            pose[f'BowLower{i + 1}'] = (np.zeros(3), rot_x(-d * lower * w / sum(LOWER_WEIGHTS)))
        W = sk.world(pose)
        inverse = np.linalg.inv(self.mesh_to_world(W))
        offset = s * (nock - NOCK)
        for bone, end, tip in (('BowStringUpper', 'BowUpper10', UPPER_LOOP), ('BowStringLower', 'BowLower7', LOWER_LOOP)):
            world = (W[end] @ np.append(sk.weapon_pivot + s * tip, 1))[:3]
            target = (inverse @ np.append(world, 1))[:3] - nock
            rest = tip - NOCK
            k = np.linalg.norm(target) / np.linalg.norm(rest)
            stretched = rest * np.array([1, k, 1])
            pose[bone] = (offset, between(stretched, target), (1.0, k, 1.0))
        pose['BowNock'] = (offset, np.eye(3))
        # 箭：-Z 指向箭台，箭羽保持竖直（贴着弓身的 +Y）。
        shelf = np.array([ARROW_SIDE, getattr(sk, 'arrow_rest', 1.7), 0.0])
        z = -unit(shelf - nock)
        y = unit(np.array([0, 1.0, 0]) - z * z[1])
        R = np.column_stack([np.cross(y, z), y, z])
        shown = self.scalar(clip, t, 'arrow', 0.0) >= 0.5
        length = sk.arrow_len / 21.0
        pose['BowArrow'] = (offset, R, (1.0, 1.0, length) if shown else (0.0, 0.0, 0.0))
        # rhide：第一人称拉弦的右前臂贴着镜头时整段缩成 0（与第一人称射击游戏一样只看到弓、左手和箭）。
        if self.scalar(clip, t, 'rhide', 0.0) >= 0.5:
            pose['RightForeArm'] = pose['RightForeArm'][:2] + ((0.0, 0.0, 0.0),)
        return pose

# ---------------------------------------------------------------- 编排


def k(t, base=None, **kw):
    key = dict(base or {})
    key.update(kw)
    key['t'] = t
    return key


def bow(up, aim):
    """弓的朝向：blade 是弓网格 +Y（上弭），edge 是 +X = Y × Z，Z（弦侧）与射向相反。"""
    y = unit(up)
    z = -unit(np.asarray(aim, float) - y * np.dot(aim, y))
    return dict(blade=tuple(y), edge=tuple(np.cross(y, z)))


def reach_out(y, z):
    """关键帧字段：左臂伸直握弓（见 straight_grip）。grip 只是占位，烘焙时按骨架换掉。"""
    return dict(grip=(-1.3, y, z), straight=(y, z))


def spun(up, aim, degrees):
    """弓绕自身竖轴（上弭方向）转 degrees：返弦（放箭后弓在掌中翻转）。"""
    y = unit(up)
    a = math.radians(degrees)
    aim = np.asarray(aim, float)
    turned = aim * math.cos(a) + np.cross(y, aim) * math.sin(a) + y * np.dot(y, aim) * (1 - math.cos(a))
    return bow(up, turned)


# 弓道的“执弓”：左手握弓垂在左腰，上弭略向前；右手放在右腰。下弭离地一点点（弓比人高，手不能再低）。
HOLD_R = (0.42, -0.86, -0.05)
IDLE = dict(grip=(-0.44, -0.76, -0.16), **bow((0.04, 0.96, -0.3), (1, 0, 0)), rmode='free', rhand=HOLD_R,
            lmode='sword', string=0.0, arrow=0.0, turn=0.0)
# 侧身对靶时的站姿：足踏み，两脚沿靶的方向分开。
FEET = dict(rfoot=(0.55, 0, 0.0, 0), lfoot=(-0.55, 0, 0.0, 0), hips=(0, -0.04, 0))
# 弓构え：弓竖在身前左侧，箭指靶（-X），右手扣弦。
AIM = (-1, 0, 0)
YUGAMAE = dict(grip=(-0.36, -0.36, -0.62), **bow((0.06, 1, 0.04), AIM), rmode='weapon', rweapon=(0, 0.85, 4.6),
               string=1.0, arrow=1.0, turn=-90.0, head=(0, 90, 0), **FEET)
# 打起し：双手把弓举过头顶，箭保持水平。
UCHIOKOSHI = dict(YUGAMAE, grip=(-0.4, 0.92, -0.4), **bow((0.1, 1, 0.08), AIM))
# 大三：左手推向靶的斜上方，右手拉到额前上方。
DAISAN = dict(UCHIOKOSHI, grip=(-0.8, 0.62, -0.38), **bow((0.08, 1, 0.04), AIM), rmode='free', rhand=(0.12, 0.74, -0.36),
              rhand_rot=(90, 0, 0), rpole=(0.5, 0.4, 1.0), rlift=0.0)
# 会：左臂完全伸直推弓，略高于肩，箭在口高、贴着脸前过，右手在右颊旁。右上臂从肩向后伸，手肘在身后、与肩差不多高，
# 右前臂折回来，差不多与箭在一条线上（肘朝身后的 rpole）。
# 握点比肩靠前（-Z）才能让箭从脸前过，所以左臂朝内收约 19°，从靶的方向看不是正对着肩。
KAI_ARM = (0.06, -0.34)
KAI = dict(DAISAN, **reach_out(*KAI_ARM), **bow((0.05, 1, 0.02), AIM), rhand=(0.28, 0.2, -0.33),
           rpole=(1.0, 0.1, -0.25), lpole=(-0.2, -0.8, 0.4))
# 残心：放箭后双臂一字展开，弓在掌中翻转（返弦）。
ZANSHIN_R = (1.0, 0.2, -0.08)

# 第一人称：眼睛在骨架坐标 (0, 29, -0.6)；手的目标按镜头坐标写，换成第一人称骨架转身后的躯干坐标。
# 第一人称骨架的肩比眼睛只低 2 像素、靠前 3.5 像素（为刀剑取景），转身后左肩会挡在镜头正前方；
# 和弓的第一人称把整个身体压低 4 像素、左移 3.5 像素，左肩落在眼睛正下方。
FP_CENTER, FP_ARM = np.array([0.0, 27.0, -3.5]), 12.5
FP_ROOT = np.array([-3.5, -4.0, 0.5])


def fp_arm(x, y):
    """第一人称左臂伸直：握点在镜头坐标里的左右 x、高低 y（像素），深度按臂长算出。身体转了 -90°。"""
    return reach_out(round((y - FP_ROOT[1] - FP_CENTER[1]) / FP_ARM, 4), round((FP_ROOT[0] - x - FP_CENTER[2]) / FP_ARM, 4))


def fpv(x, y, z, turn=-90.0):
    """镜头坐标（像素，+X 右、+Y 上、-Z 前）里的一点 → 第一人称躯干坐标（身体转了 turn 度时）。"""
    body = rot_y(turn).T @ (np.array([x, y, z], float) - FP_ROOT)
    return tuple(np.round((body - FP_CENTER) / FP_ARM, 4))


IDLE['fp'] = dict(grip=fpv(-5.5, 19.5, -12, 0), **bow((0.2, 1, 0.15), (1, 0, 0.3)), rhand=fpv(6, 13, -3, 0), rhide=0.0)
YUGAMAE['fp'] = dict(grip=fpv(-1.0, 20.5, -12), rhide=0.0)
# 第一人称不把弓举过头顶：举到左上方、手臂伸远，前臂离镜头远，不挡住准星一带。
UCHIOKOSHI['fp'] = dict(grip=fpv(-6.5, 32.5, -12.5), rhide=1.0)
DAISAN['fp'] = dict(grip=fpv(-4.5, 31.0, -12.5), rhand=fpv(3.0, 30.0, 0.8), rhide=1.0)
KAI['fp'] = dict(**fp_arm(-1.35, 26.05), rhand=fpv(3.6, 26.6, 0.8), rhide=1.0)
FP_ZANSHIN = dict(**fp_arm(-1.7, 25.6), rhand=fpv(9.0, 27.0, 2.0), rhide=1.0)


def clips():
    out = []
    out.append(dict(name='idle', length=2.4, loop='loop', keys=[
        k(0.0, IDLE),
        k(1.2, IDLE, grip=(-0.44, -0.74, -0.18), hips=(0, -0.012, 0),
          fp=dict(IDLE['fp'], grip=fpv(-5.5, 19.8, -12, 0))),
        k(2.4, IDLE),
    ]))
    # 取弓：从身侧把弓提到执弓的位置。
    out.append(dict(name='ready', length=0.5, loop='once', keys=[
        k(0.0, IDLE, grip=(-0.42, -0.74, 0.05), **bow((0.02, 0.97, -0.22), (1, 0, 0))),
        k(0.5, IDLE),
    ]))
    # 搭箭、打起し：转身侧对靶、两脚分开，弓提到身前取箭搭弦，再双手举过头顶。松开右键时倒放回执弓（lower）。
    out.append(dict(name='uchiokoshi', length=0.45, loop='once', keys=[
        k(0.0, IDLE),
        k(0.08, arrow=1.0),
        k(0.16, YUGAMAE, blend=0.12, ease='hold', fox=dict(rweapon=(0, 0.85, 4.0))),
        k(0.45, UCHIOKOSHI, ease='hold'),
    ]))
    # 引分け：弓从头顶推向靶、弦拉开，经大三落到会。
    out.append(dict(name='hikiwake', length=0.6, loop='once', keys=[
        k(0.0, UCHIOKOSHI),
        k(0.26, DAISAN, blend=0.16),
        k(0.6, KAI, ease='hold'),
    ]))
    # 会：拉满后的持满，极轻的伸合（左臂已经伸到头，只有右手再拉一点）。
    out.append(dict(name='kai', length=1.6, loop='loop', keys=[
        k(0.0, KAI),
        k(0.8, KAI, rhand=(0.3, 0.2, -0.33), fp=dict(KAI['fp'], rhand=fpv(3.6, 26.6, 1.0))),
        k(1.6, KAI),
    ]))
    # 離れ：右手向后一字甩开，弦回弹、弓身颤动，弓在左掌中翻转（返弦）；残心停住，再倒弓回执弓。
    spin = [k(0.03 + 0.03 * i, **spun((0.05, 1, 0.02), AIM, 50 * i), ease='lin') for i in range(1, 5)]
    out.append(dict(name='hanare', length=1.4, loop='once', markers={'release': 0.02}, keys=[
        k(0.0, KAI, shiver=0.0),
        k(0.02, string=0.0, arrow=0.0, blend=0.02, rhand=(0.62, 0.22, -0.22), rhand_rot=(0, 0, 0), shiver=0.0),
        k(0.06, shiver=-0.22),
        *spin,
        k(0.1, shiver=0.1),
        k(0.13, **reach_out(0.03, KAI_ARM[1]), **spun((0.05, 1, 0.02), AIM, 200), rhand=ZANSHIN_R, rpole=(0.6, -0.4, 0.6), shiver=-0.05,
          fp=FP_ZANSHIN),
        k(0.2, shiver=0.0),
        k(0.75, **reach_out(0.01, KAI_ARM[1]), **spun((0.05, 1, 0.02), AIM, 200), rhand=ZANSHIN_R, ease='hold', fp=FP_ZANSHIN),
        # 弓倒し：双手放下、弓转回，身体转回正面。
        k(1.0, grip=(-0.6, -0.5, -0.34), rhand=(0.5, -0.6, -0.1), **spun((0.05, 1, -0.1), AIM, 100), turn=-60.0,
          rlift=1.0, fp=dict(grip=fpv(-3.5, 18.5, -12, -60), rhand=fpv(7, 13, -2, -60), rhide=0.0)),
        k(1.4, IDLE, rmode='free', blend=0.2, rfoot=(0.167, 0, 0, 0), lfoot=(-0.167, 0, 0, 0), hips=(0, 0, 0),
          head=(0, 0, 0)),
    ]))
    for clip in out:
        clip.setdefault('stance', {'rfoot': (0.167, 0, 0, 0), 'lfoot': (-0.167, 0, 0, 0)})
    return out

# ---------------------------------------------------------------- 骨架


# 弓与身高的比例和玩家差不多：神狐连耳朵高 54，2.3 倍时弓高 73.6，约身高的 1.36 倍。
FOX_SCALE = 2.3


def skeletons(art, weapon):
    # 武器网格的 scale 乘在骨架的武器缩放上（第三人称 0.8、第一人称 0.58），与 LongswordRig 相同。
    tp_scale, fp_scale = 0.8 * weapon['scale'], 0.58 * weapon['scale']
    player = player_skeleton(ROOT / 'src/main/resources/assets/logos_dimension/combat/skeletons/player_tp.json',
                             weapon_scale=tp_scale, carry=((0, 0, 0), np.eye(3)))
    fp = player_skeleton(ROOT / 'src/main/resources/assets/logos_dimension/combat/skeletons/player_fp.json',
                         weapon_scale=fp_scale, carry=((0, 0, 0), np.eye(3)))
    # 第一人称：身体不靠右（转身后左臂本来就伸在视线前方），弓在画面中偏右。
    fp.root = (tuple(FP_ROOT), (0, 0, 0))
    # 第一人称的搭箭点完全跟着右手：箭从右颊下方斜着射向弓，看得见。
    fp.nock_follow = (1.0, 0.6)
    fox = bbmodel_skeleton(art / 'MinecraftLogosMod/fox_model/divine_fox/body/divine_fox_body.bbmodel',
                           hand_grip=(0, -2.29, -0.3), weapon_scale=FOX_SCALE, carry=((0, 0, 0), np.eye(3)))
    player.body = ((-4.4, 12.0, -2.5), (4.4, 21.0, 2.5))
    fox.body = ((-3.4, 24.0, -2.6), (3.4, 32.5, 2.6))
    player.tag, fp.tag, fox.tag = 'player', 'fp', 'fox'
    # 箭台高度（弓网格单位）：左拳沿弓身方向的半宽之上一点。
    player.arrow_rest = 1.5 / tp_scale + 0.2
    fp.arrow_rest = 1.15 / fp_scale + 0.2
    fox.arrow_rest = 1.8 / FOX_SCALE + 0.1
    for sk in (player, fp, fox):
        add_bow(sk, weapon)
    return player, fp, fox


def calibrate(sk, all_clips, floor=None):
    """满弓距离与箭长：取“会”第一帧的搭箭点；弓身的弯曲量让两段弦在满弓时保持原长（弦不伸缩）。
    floor 是弯曲量的下限（神狐手臂短，拉开的距离只有弓长的六分之一，纯按弦长算弓几乎不弯）。"""
    kai = next(c for c in all_clips if c['name'] == 'kai')
    baker = YumiBaker(sk)
    tag = getattr(sk, 'tag', None)
    clip = dict(kai, keys=[dict(key, **key.get(tag, {})) for key in kai['keys']])
    pose = Baker.pose(baker, clip, 0.0)
    nock, drawn = baker.nock(clip, 0.0, sk.world(pose))
    sk.full_draw = drawn
    shelf = np.array([ARROW_SIDE, getattr(sk, 'arrow_rest', 1.7), 0.0])
    sk.arrow_len = float(np.linalg.norm(shelf - nock)) + OVERHANG

    def stretch(bone, end, tip, total):
        # 只弯这一段，看满弓时这段弦相对原长的伸缩。
        sk.bend = (total, 0.0) if bone == 'BowStringUpper' else (0.0, total)
        pose = baker.pose(clip, 0.0)
        return pose[bone][2][1]
    bend = []
    for bone, total in (('BowStringUpper', UPPER_BEND), ('BowStringLower', LOWER_BEND)):
        lo, hi = 0.0, 120.0
        for _ in range(40):
            mid = (lo + hi) / 2
            if stretch(bone, None, None, mid) > 1.0:
                lo = mid
            else:
                hi = mid
        bend.append((lo + hi) / 2)
    if floor is not None:
        bend = [max(b, f) for b, f in zip(bend, floor)]
    sk.bend = tuple(bend)
    return drawn

# ---------------------------------------------------------------- 预览


def weapon_quads(sk, W, weapon, tex, arrow, arrow_tex):
    s = sk.weapon_scale
    base = translate(sk.weapon_pivot) @ np.diag([s, s, s, 1.0])
    quads = []
    for c in weapon['cubes']:
        # 骨骼矩阵作用在骨架坐标，网格点先按 Weapon 枢轴与缩放放进骨架坐标。
        quads += box(W[c['bone']] @ base, c['start'], c['end'], c['pivot'], c['rotation'], uv=c['uv'], tex=tex)
    M = W['BowArrow'] @ base @ translate(NOCK)
    for c in arrow['cubes']:
        quads += box(M, c['start'], c['end'], c['pivot'], c['rotation'], uv=c['uv'], tex=arrow_tex)
    return quads


class FirstPerson:
    """第一人称预览的透视相机：眼睛在骨架坐标 (0, 29, -0.6)，看向 -Z，竖直视角 70°。"""

    def __init__(self, size=(427, 240), fov=70):
        self.W, self.H = size
        self.f = self.H / 2 / math.tan(math.radians(fov / 2))
        self.eye = np.array([0, 29, -0.6])

    def project(self, p):
        d = np.asarray(p, float) - self.eye
        depth = -d[2]
        if depth < 0.3:
            return None
        return np.array([self.W / 2 + self.f * d[0] / depth, self.H / 2 - self.f * d[1] / depth, depth])


def render_fp(quads, cam, bg=(176, 204, 236)):
    img = np.zeros((cam.H, cam.W, 3), np.float32); img[:] = bg
    zb = np.full((cam.H, cam.W), np.inf)
    pieces = []
    for q in quads:
        O, U, V, N, uv, tex, color = q
        if all(cam.project(p) is not None for p in (O, O + U, O + V, O + U + V)):
            pieces.append(q)
            continue
        # 跨过镜头平面：沿长边切成 16 段，只留镜头前面的段。
        along_u = np.linalg.norm(U) >= np.linalg.norm(V)
        for i in range(16):
            a, b = i / 16, (i + 1) / 16
            if along_u:
                o, u, v = O + U * a, U * (b - a), V
                sub = None if uv is None else (uv[0] + uv[2] * a, uv[1], uv[2] * (b - a), uv[3])
            else:
                o, u, v = O + V * a, U, V * (b - a)
                sub = None if uv is None else (uv[0], uv[1] + uv[3] * a, uv[2], uv[3] * (b - a))
            if all(cam.project(p) is not None for p in (o, o + u, o + v, o + u + v)):
                pieces.append((o, u, v, N, sub, tex, color))
    for O, U, V, N, uv, tex, color in pieces:
        P = [cam.project(O), cam.project(O + U), cam.project(O + V), cam.project(O + U + V)]
        # 小方块近似为屏幕上的平行四边形（预览用，透视误差可以忽略）。
        P0, PU, PV = P[0], P[1] - P[0], P[2] - P[0]
        A = np.array([[PU[0], PV[0]], [PU[1], PV[1]]])
        if abs(np.linalg.det(A)) < 1e-6:
            continue
        corners = np.array([p[:2] for p in P])
        xa, ya = np.floor(corners.min(0)).astype(int)
        xb, yb = np.ceil(corners.max(0)).astype(int)
        xa, ya, xb, yb = max(xa, 0), max(ya, 0), min(xb, cam.W - 1), min(yb, cam.H - 1)
        if xa > xb or ya > yb:
            continue
        xs, ys = np.meshgrid(np.arange(xa, xb + 1) + 0.5, np.arange(ya, yb + 1) + 0.5)
        Ai = np.linalg.inv(A)
        dx, dy = xs - P0[0], ys - P0[1]
        s = Ai[0, 0] * dx + Ai[0, 1] * dy
        t = Ai[1, 0] * dx + Ai[1, 1] * dy
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        if not inside.any():
            continue
        z = P0[2] + s * PU[2] + t * PV[2]
        if tex is not None:
            u, v, w, h = uv
            tu = np.clip(np.floor(u + s * w - (1e-6 if w < 0 else 0)).astype(int), 0, tex.shape[1] - 1)
            tv = np.clip(np.floor(v + t * h - (1e-6 if h < 0 else 0)).astype(int), 0, tex.shape[0] - 1)
            col = tex[tv, tu]
            ok = inside & (col[..., 3] >= 128)
            rgb = col[..., :3]
        else:
            ok = inside
            rgb = np.broadcast_to(np.array(color, np.float32), s.shape + (3,))
        ok = ok & (z < zb[ya:yb + 1, xa:xb + 1] - 1e-4)
        if not ok.any():
            continue
        n = N / (np.linalg.norm(N) or 1)
        light = min(1.0, 0.4 + 0.6 * (max(0, n.dot(L0)) + max(0, n.dot(L1))))
        img[ya:yb + 1, xa:xb + 1][ok] = rgb[ok] * light
        zb[ya:yb + 1, xa:xb + 1][ok] = z[ok]
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(out)
    cx, cy = cam.W // 2, cam.H // 2
    d.line([(cx - 4, cy), (cx + 4, cy)], fill=(255, 255, 255)); d.line([(cx, cy - 4), (cx, cy + 4)], fill=(255, 255, 255))
    return out


FP_ARMS = {  # 第一人称骨架只画前臂与手（与 player_fp.json 的 skin 相同）
    'RightForeArm': [((2.85, 15.7, -4.65), (5.15, 21.1, -2.35), (92, 128, 176))],
    'RightHand': [((2.85, 13.7, -4.65), (5.15, 15.7, -2.35), (222, 184, 160))],
    'LeftForeArm': [((-5.15, 15.7, -4.65), (-2.85, 21.1, -2.35), (92, 128, 176))],
    'LeftHand': [((-5.15, 13.7, -4.65), (-2.85, 15.7, -2.35), (222, 184, 160))],
}


def frames_for(clip, n):
    times = [clip['length'] * i / (n - 1) for i in range(n)]
    for name, t in (clip.get('markers') or {}).items():
        times.append(t)
    return sorted(set(round(t, 3) for t in times))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    art, out = Path(args[0]), Path(args[1])
    only = set(args[2:])
    gif = '--gif' in sys.argv
    out.mkdir(parents=True, exist_ok=True)
    weapon = json.load(open(WEAPON, encoding='utf-8'))
    arrow = json.load(open(ARROW, encoding='utf-8'))
    tex = np.asarray(Image.open(ASSETS / 'textures/item/fox_yumi_bamboo_rig.png').convert('RGBA')).astype(np.float32)
    arrow_tex = np.asarray(Image.open(ASSETS / 'textures/entity/projectiles/yumi_arrow.png').convert('RGBA')).astype(np.float32)
    player, fp, fox = skeletons(art, weapon)
    all_clips = clips()
    for who, sk in (('player', player), ('fp', fp), ('fox', fox)):
        floor = tuple(0.6 * b for b in player.bend) if who == 'fox' else None
        drawn = calibrate(sk, all_clips, floor)
        if who == 'fp':
            # 第一人称按镜头取景，拉开的距离（弓网格单位）比下段弦还长，弦不可能保持原长；
            # 弓身弯得与第三人称满弓一样，下段弦多半在画面外，允许它伸长。
            sk.bend = player.bend
        print(f'{who} 满弓拉开 {drawn:.1f}（弓网格单位，弓高 32），箭长 {sk.arrow_len:.1f}，'
              f'弓身弯 上 {sk.bend[0]:.0f}° 下 {sk.bend[1]:.0f}°')
    review = art / 'art/fox_yumi/v01/review/fox_holding_kyudo.geo.json'
    fox_geo = Geo(review, str(review).replace('.geo.json', '.png'), skip=('Weapon', 'Sheath', *fox.bow_bones))
    baked = {}
    for who, sk in (('player', player), ('fp', fp), ('fox', fox)):
        baker = YumiBaker(sk)
        for clip in all_clips:
            baked[(who, clip['name'])] = baker.bake(clip, 60)
        worst = {}
        for clip_name, t, side, over in baker.warnings:
            lo, hi, m = worst.get((clip_name, side), (t, t, 0))
            worst[(clip_name, side)] = (min(lo, t), max(hi, t), max(m, over))
        for (clip_name, side), (lo, hi, m) in sorted(worst.items()):
            print(f'{who} {clip_name} {side} 手够不着 {lo:.2f}–{hi:.2f}s，最多差 {m:.2f} 臂长')
    # 玩家两份动作写进附属资源：第三人称带 Root（只有转身）与双腿（原版腿只取张开的角度）。
    bones = ['Root', 'UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
             'LeftArm', 'LeftForeArm', 'LeftHand', 'RightLeg', 'LeftLeg', 'Weapon', *player.bow_bones]
    for who, sk, view in (('player', player, 'tp'), ('fp', fp, 'fp')):
        data = bedrock(all_clips, {c['name']: baked[(who, c['name'])] for c in all_clips}, sk, bones)
        text = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        (ASSETS / f'combat/animations/yumi_{view}.animation.json').write_text(text, encoding='utf-8')
    # 神狐的动作写进模型目录，由 tools/divine_fox_assets.py 并入神狐的动画文件（与打刀相同）。
    fox_bones = ['Root', 'AllBody', 'UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand', 'LeftArm', 'LeftForeArm', 'LeftHand',
                 'RightLeg', 'RightLowerLeg', 'RightFoot', 'LeftLeg', 'LeftLowerLeg', 'LeftFoot', 'Weapon', *fox.bow_bones]
    data = bedrock(all_clips, {c['name']: baked[('fox', c['name'])] for c in all_clips}, fox, fox_bones)
    fox_out = art / 'MinecraftLogosMod/fox_model/divine_fox/Animation/yumi'
    fox_out.mkdir(parents=True, exist_ok=True)
    (fox_out / 'divine_fox_yumi.animation.json').write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')

    cams = {'player': [Camera(-35, 8, 4.6, (0, 22, -2), (250, 300)), Camera(60, 6, 4.6, (0, 22, -2), (250, 300))],
            'fox': [Camera(-35, 8, 2.5, (0, 36, -3), (250, 300)), Camera(60, 6, 2.5, (0, 36, -3), (250, 300))]}
    fp_cam = FirstPerson()

    def posed(who, clip, t):
        frames = baked[(who, clip['name'])]
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
        return {n: (v[0], euler_matrix(v[1])) + ((v[2],) if len(v) > 2 else ()) for n, v in frames[i][1].items()}

    def draw(who, sk, clip, t, cam):
        pose = posed(who, clip, t)
        W = sk.world(pose)
        quads = weapon_quads(sk, W, weapon, tex, arrow, arrow_tex)
        quads += mannequin_quads(W) if who == 'player' else fox_geo.quads({n: v[:2] for n, v in pose.items()})
        return render(quads, cam)

    def draw_fp(clip, t):
        pose = posed('fp', clip, t)
        W = fp.world(pose)
        quads = weapon_quads(fp, W, weapon, tex, arrow, arrow_tex)
        for bone, boxes in FP_ARMS.items():
            for start, end, color in boxes:
                quads += box(W[bone], start, end, color=color)
        return render_fp(quads, fp_cam)

    for clip in all_clips:
        if only and clip['name'] not in only:
            continue
        times = frames_for(clip, 7)
        rows = []
        for who, sk in (('player', player), ('fox', fox)):
            for v, cam in enumerate(cams[who]):
                rows.append((('玩家' if who == 'player' else '神狐') + ('·左前' if v == 0 else '·右前'),
                             [(f'{t:.2f}s', draw(who, sk, clip, t, cam)) for t in times]))
        sheet(rows, f"{clip['name']}  {clip['length']}s", out / f"{clip['name']}.png")
        fp_rows = [('第一人称', [(f'{t:.2f}s', draw_fp(clip, t)) for t in times])]
        sheet(fp_rows, f"{clip['name']} 第一人称", out / f"{clip['name']}_fp.png")
        if gif:
            images = []
            for i in range(int(clip['length'] * 20) + 1):
                t = i / 20
                a = draw('player', player, clip, t, cams['player'][0])
                b = draw('fox', fox, clip, t, cams['fox'][0])
                im = Image.new('RGB', (a.width * 2, a.height)); im.paste(a, (0, 0)); im.paste(b, (a.width, 0))
                images.append(im)
            images[0].save(out / f"{clip['name']}.gif", save_all=True, append_images=images[1:], duration=50, loop=0)
        print('rendered', clip['name'])


if __name__ == '__main__':
    main()
