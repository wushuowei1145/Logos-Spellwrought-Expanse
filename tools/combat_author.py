# -*- coding: utf-8 -*-
"""按刀路制作战斗动作。

动作不直接写骨骼角度，而是写“编排”：刀柄在哪、刀身和刃朝哪、身体怎么转、脚踩在哪。
双臂、双腿用两段式逆运动学解出，手腕按握法对齐刀身，再按指定骨架逐帧烘焙。
同一份编排可以分别烘焙到玩家参考骨架和 NPC 自己的骨架，比例不同也不会脱手。

坐标系同 docs/COMBAT_SKELETON.md 第 3 节：Blockbench 空间，Y 向上、正面 -Z、角色右侧 +X，
欧拉角按 Z·Y·X 顺序（度）。导出 Bedrock 动画时旋转 x、y 取反，位置 x 取反。

编排坐标：
- 刀、刀鞘、手的目标都在躯干（UpBody）坐标里，原点是两肩中点，长度以臂长为单位。
- 髋部位移、脚的位置以髋高（AllBody 枢轴离地高度）为单位。
"""
import json
import math
import uuid

import numpy as np

# ---------------------------------------------------------------- 数学


def rot_x(a):
    a = math.radians(a); c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    a = math.radians(a); c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rot_z(a):
    a = math.radians(a); c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def euler_matrix(e):
    return rot_z(e[2]) @ rot_y(e[1]) @ rot_x(e[0])


def matrix_euler(R, near=None):
    """R = Rz·Ry·Rx 的欧拉角（度）；给出 near 时取两组解里离它最近、并按 360° 展开的那组。"""
    sb = -max(-1.0, min(1.0, R[2, 0]))
    candidates = []
    for b in (math.asin(sb), math.pi - math.asin(sb)):
        cb = math.cos(b)
        if abs(cb) < 1e-6:
            a = math.radians(near[0]) if near is not None else 0.0
            # 万向节锁：固定 x，按 R 求 z。
            if sb > 0:
                c = a - math.atan2(R[0, 1], R[1, 1])
            else:
                c = -a + math.atan2(-R[0, 1], R[1, 1])
        else:
            a = math.atan2(R[2, 1] / cb, R[2, 2] / cb)
            c = math.atan2(R[1, 0] / cb, R[0, 0] / cb)
        candidates.append(np.degrees([a, b, c]))
    if near is None:
        return candidates[0]
    best = None
    for e in candidates:
        e = np.array([v + 360 * round((n - v) / 360) for v, n in zip(e, near)])
        if best is None or np.abs(e - near).sum() < np.abs(best - near).sum():
            best = e
    return best


def unit(v):
    v = np.asarray(v, float)
    n = np.linalg.norm(v)
    return v / n if n > 1e-9 else v


def frame(blade, edge):
    """刀的朝向：+Y 指刀尖，+X 指刃，返回列为 X、Y、Z 的旋转矩阵。"""
    y = unit(blade)
    x = unit(np.asarray(edge, float) - y * np.dot(edge, y))
    return np.column_stack([x, y, np.cross(x, y)])


def quat(R):
    w = math.sqrt(max(0.0, 1 + R[0, 0] + R[1, 1] + R[2, 2])) / 2
    x = math.copysign(math.sqrt(max(0.0, 1 + R[0, 0] - R[1, 1] - R[2, 2])) / 2, R[2, 1] - R[1, 2])
    y = math.copysign(math.sqrt(max(0.0, 1 - R[0, 0] + R[1, 1] - R[2, 2])) / 2, R[0, 2] - R[2, 0])
    z = math.copysign(math.sqrt(max(0.0, 1 - R[0, 0] - R[1, 1] + R[2, 2])) / 2, R[1, 0] - R[0, 1])
    return np.array([w, x, y, z])


def quat_matrix(q):
    w, x, y, z = q / np.linalg.norm(q)
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def slerp(A, B, w):
    a, b = quat(A), quat(B)
    if np.dot(a, b) < 0:
        b = -b
    d = min(1.0, float(np.dot(a, b)))
    if d > 0.9995:
        return quat_matrix(a + (b - a) * w)
    th = math.acos(d)
    return quat_matrix((math.sin((1 - w) * th) * a + math.sin(w * th) * b) / math.sin(th))


def affine(R, t):
    M = np.eye(4); M[:3, :3] = R; M[:3, 3] = t
    return M


def translate(t):
    return affine(np.eye(3), t)


# 握法：手臂自然下垂时刀尖朝前、刃朝下。列为刀的 X（刃）、Y（刀尖）、Z 在手骨骼坐标里的方向。
GRIP = np.column_stack([(0, -1, 0), (0, 0, -1), (1, 0, 0)]).astype(float)
# 左掌托刀背：列为手的 X、Y、Z 在刀坐标里的方向（掌心 +X 朝刃，手指 -Y 沿刀的 -Z）。
MUNE = np.column_stack([(1, 0, 0), (0, 0, 1), (0, -1, 0)]).astype(float)

# ---------------------------------------------------------------- 骨架

CORE = ['Root', 'AllBody', 'UpBody', 'UpperBody', 'AllHead', 'Head', 'Arm',
        'RightArm', 'RightForeArm', 'RightHand', 'LeftArm', 'LeftForeArm', 'LeftHand',
        'DownBody', 'RightLeg', 'RightLowerLeg', 'RightFoot', 'LeftLeg', 'LeftLowerLeg', 'LeftFoot']


class Skeleton:
    """骨骼名、父级、枢轴与静止旋转。world() 按 T(p+pos)·R·T(-p) 逐级相乘。"""

    def __init__(self, bones, hand_grip, weapon_scale, carry, weapon_pivot=(0, 1, 0)):
        self.bones = {b['name']: b for b in bones}
        self.order = []
        seen = set()

        def visit(name):
            if name in seen:
                return
            parent = self.bones[name]['parent']
            if parent:
                visit(parent)
            seen.add(name)
            self.order.append(name)
        for name in self.bones:
            visit(name)
        self.hand_grip = np.asarray(hand_grip, float)
        self.weapon_scale = weapon_scale
        self.weapon_pivot = np.asarray(weapon_pivot, float)
        p = lambda n: self.bones[n]['pivot']
        self.center = (p('RightArm') + p('LeftArm')) / 2
        self.arm = np.linalg.norm(p('RightForeArm') - p('RightArm')) + np.linalg.norm(p('RightHand') - p('RightForeArm'))
        self.hip = p('AllBody')[1]
        # 佩刀：刀鞘网格原点与朝向，躯干坐标。
        self.carry = (np.asarray(carry[0], float), np.asarray(carry[1], float))

    def pivot(self, name):
        return self.bones[name]['pivot']

    def rest(self, name):
        return euler_matrix(self.bones[name]['rest'])

    def world(self, pose):
        W = {}
        for name in self.order:
            b = self.bones[name]
            entry = pose.get(name, (np.zeros(3), self.rest(name)))
            pos, R = entry[0], entry[1]
            # 第三项是缩放（弓弦随拉弓伸长）；与 GeckoLib、CombatPose 相同，先缩放再旋转。
            S = np.diag(list(entry[2]) + [1.0]) if len(entry) > 2 else np.eye(4)
            local = translate(b['pivot'] + pos) @ affine(R, np.zeros(3)) @ S @ translate(-b['pivot'])
            W[name] = W[b['parent']] @ local if b['parent'] else local
        return W

    def model(self, v):
        """编排坐标（以两肩中点为原点、臂长为单位）换成模型坐标；reach 是该骨架统一的手部偏移（第一人称把刀举进画面）。"""
        return self.center + self.arm * np.asarray(v, float) + getattr(self, 'reach', 0)


def player_skeleton(path, weapon_scale, carry):
    data = json.load(open(path, encoding='utf-8'))
    bones = [dict(name=b['name'], parent=b['parent'], pivot=np.array(b['pivot'], float), rest=np.zeros(3))
             for b in data['bones']]
    return Skeleton(bones, hand_grip=(0, -0.35, 0), weapon_scale=weapon_scale, carry=carry,
                    weapon_pivot=next(b['pivot'] for b in bones if b['name'] == 'Weapon'))


def bbmodel_skeleton(path, hand_grip, weapon_scale, carry):
    """从 Blockbench 工程读核心骨骼（枢轴、静止旋转），另加挂在 UpBody 下的 Weapon / Sheath。"""
    data = json.load(open(path, encoding='utf-8'))
    groups = {g['uuid']: g for g in data.get('groups', [])}
    bones = []

    def walk(node, parent):
        if isinstance(node, str):
            return
        g = groups.get(node.get('uuid'), node)
        name = g['name']
        if name in CORE:
            bones.append(dict(name=name, parent=parent, pivot=np.array(g['origin'], float),
                              rest=np.array(g.get('rotation', [0, 0, 0]), float)))
            parent = name
        for child in node.get('children', []):
            walk(child, parent)
    for node in data['outliner']:
        walk(node, None)
    for name in ('Weapon', 'Sheath'):
        bones.append(dict(name=name, parent='UpBody', pivot=np.zeros(3), rest=np.zeros(3)))
    return Skeleton(bones, hand_grip, weapon_scale, carry, weapon_pivot=(0, 0, 0))

# ---------------------------------------------------------------- 插值


def hermite(keys, t, loop_length=None):
    """keys: [(时间, 值, 标记)]，标记 'hold' 表示在该帧停稳（切线为 0），'lin' 表示到该帧走直线。"""
    if len(keys) == 1:
        return np.asarray(keys[0][1], float)
    times = [k[0] for k in keys]
    if t <= times[0]:
        return np.asarray(keys[0][1], float)
    if t >= times[-1]:
        return np.asarray(keys[-1][1], float)
    i = max(j for j in range(len(times) - 1) if times[j] <= t)
    t0, t1 = times[i], times[i + 1]
    p0, p1 = np.asarray(keys[i][1], float), np.asarray(keys[i + 1][1], float)
    s = (t - t0) / (t1 - t0)
    if 'lin' in keys[i + 1][2]:
        return p0 + (p1 - p0) * s

    def tangent(j):
        if 'hold' in keys[j][2]:
            return np.zeros_like(p0)
        if loop_length and j in (0, len(keys) - 1):
            prev = np.asarray(keys[-2][1], float); nxt = np.asarray(keys[1][1], float)
            span = (times[-1] - times[-2]) + (times[1] - times[0])
            return (nxt - prev) / span
        if j == 0 or j == len(keys) - 1:
            other = keys[1] if j == 0 else keys[-2]
            return (np.asarray(keys[j][1], float) - np.asarray(other[1], float)) / (times[j] - other[0]) * 0.5
        return (np.asarray(keys[j + 1][1], float) - np.asarray(keys[j - 1][1], float)) / (times[j + 1] - times[j - 1])
    m0, m1 = tangent(i) * (t1 - t0), tangent(i + 1) * (t1 - t0)
    h00 = 2 * s ** 3 - 3 * s ** 2 + 1; h10 = s ** 3 - 2 * s ** 2 + s
    h01 = -2 * s ** 3 + 3 * s ** 2; h11 = s ** 3 - s ** 2
    return h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1


def channel(clip, field, default=None):
    keys = [(k['t'], k[field], k.get('ease', '')) for k in clip['keys'] if field in k]
    if not keys and default is not None:
        keys = [(0.0, default, '')]
    return keys

# ---------------------------------------------------------------- 逆运动学


def two_bone(S, W, a, b, pole, sign):
    """肩（髋）S 到腕（踝）W，上段长 a、下段长 b，pole 为肘（膝）朝向。
    返回上段世界旋转（静止时骨骼沿 -Y、绕 X 弯曲）与弯曲角；sign=+1 时下段向前弯（手臂），-1 向后弯（腿）。"""
    d_vec = W - S
    d = np.linalg.norm(d_vec)
    reach = d
    d = min(max(d, abs(a - b) + 1e-4), a + b - 1e-4)
    dhat = unit(d_vec)
    p = np.asarray(pole, float) - dhat * np.dot(pole, dhat)
    phat = unit(p) if np.linalg.norm(p) > 1e-6 else unit(np.cross(dhat, (1, 0, 0)))
    cos_a = (a * a + d * d - b * b) / (2 * a * d)
    alpha = math.acos(max(-1, min(1, cos_a)))
    E = S + a * (math.cos(alpha) * dhat + math.sin(alpha) * phat)
    u = unit(E - S)
    f = unit(S + dhat * d - E)
    n = np.cross(u, f)
    n = sign * unit(n) if np.linalg.norm(n) > 1e-6 else sign * unit(np.cross(phat, dhat))
    y = -u
    F = np.column_stack([n, y, np.cross(n, y)])
    bend = math.degrees(math.acos(max(-1, min(1, np.dot(u, f)))))
    return F, sign * bend, reach - (a + b)

def rigid_arm(S, G, a, b, pref, target, previous, twist=120.0, previous_tau=None, up=(0, 1, 0), body=None, axial=False,
              lift_weight=1.0):
    """没有腕关节的手臂：手与前臂一体，只用肩（三向）和肘（弯曲加前臂自转）把拳心送到握点 G。

    b 是肘到拳心的长度（前臂加手）。到了握点后还剩手肘绕“肩—握点”连线的转角，逐个试，
    前臂自转按闭式让拳心正对 target（期望的手部朝向）给出的刀身方向；剩下对不上的就是刀柄穿过拳心的偏角。
    pref 是偏好的手肘朝向，previous 是上一帧的手肘位置（保持连续，不翻面）；
    body 给出世界坐标点陷进躯干的深度，手臂穿进身体重罚。
    axial 为真时是转枪：杆在拳中绕前臂转，方块拳头看不出这个转动，只算杆偏离“垂直于前臂”的角度，前臂不自转。
    lift_weight 是手肘抬过肩的扣分倍数（弓道拉满时右肘高抬在后，给 0）。
    返回 (上臂世界旋转, 弯曲角, 前臂自转角, 手肘位置, 刀在拳中的残差角, 够不着的长度)。"""
    dhat = unit(G - S)
    u1 = np.asarray(pref, float) - dhat * np.dot(pref, dhat)
    u1 = unit(u1) if np.linalg.norm(u1) > 1e-6 else unit(np.cross(dhat, (1, 0, 0)))
    u2 = np.cross(dhat, u1)

    blade = target @ np.array([0, 0, -1.0])  # 期望的刀身方向（握法里刀尖是手的 -Z）

    def evaluate(phi):
        pole = math.cos(phi) * u1 + math.sin(phi) * u2
        F, bend, over = two_bone(S, G, a, b, pole, +1)
        local = (F @ rot_x(bend)).T @ blade
        elbow = S + F @ np.array([0, -a, 0])
        inside = 0.0
        if body is not None:
            fore = unit(G - elbow)
            for p in (S + (elbow - S) * 0.6, elbow, elbow + fore * b * 0.35, elbow + fore * b * 0.7, G):
                inside += body(p)
        # 方块拳头只看得出刀柄穿过拳心的方向：前臂自转让拳心正对刀身，刀绕自身轴的转动看不出来。
        # 袖子截面是正方形，twist 为 None 时只转 90° 的整数倍，肘部棱角保持对齐；换档有代价，避免来回跳。
        if axial:
            options = (previous_tau if previous_tau is not None else 0.0,)
        elif twist is None:
            options = (-90.0, 0.0, 90.0, 180.0)
        else:
            options = (max(-twist, min(twist, math.degrees(math.atan2(-local[0], -local[2])))),)
        best = None
        for tau in options:
            through = rot_y(tau) @ np.array([0, 0, -1.0])
            if axial:
                residual = math.asin(min(1.0, abs(float(local[1]))))
            else:
                residual = math.acos(max(-1.0, min(1.0, float(np.dot(through, local)))))
            # 手肘偏离默认朝向（向下、略向后外）和抬过肩下方 1/4 上臂处都扣分，第三人称姿势才自然。
            lift = max(0.0, float(np.dot(elbow - S, up)) / a + 0.25)
            cost = residual + ELBOW_PREF * (1 - math.cos(phi)) + 2.5 * lift_weight * lift + 6.0 * inside / a + (0.05 if tau else 0)
            if previous is not None:
                cost += 2.0 * np.linalg.norm(elbow - previous) / a
            if previous_tau is not None and tau != previous_tau:
                cost += 0.6
            if best is None or cost < best[0]:
                best = (cost, (F, bend, tau, elbow, math.degrees(residual), over))
        return best
    best = min((evaluate(math.radians(d)) for d in range(0, 360, 6)), key=lambda r: r[0])
    phi0 = math.atan2(np.dot(best[1][3] - S - dhat * np.dot(best[1][3] - S, dhat), u2),
                      np.dot(best[1][3] - S - dhat * np.dot(best[1][3] - S, dhat), u1))
    for d in range(-5, 6):
        candidate = evaluate(phi0 + math.radians(d))
        if candidate[0] < best[0]:
            best = candidate
    return best[1]

# ---------------------------------------------------------------- 烘焙

ARM_POLE = {'Right': (0.6, -0.8, 0.9), 'Left': (-0.6, -0.8, 0.9)}


class Baker:
    def __init__(self, skeleton):
        self.sk = skeleton
        self.warnings = []
        # 刀在拳中的残差角（度），以及上一帧的手肘位置（保持手肘连续）。
        self.residuals = []
        self.elbows = {}
        self.taus = {}

    def sheath(self, clip, t):
        """刀鞘网格变换（躯干坐标）：佩刀姿势绕鞘口旋转 saya 角度，再平移 saya_off。"""
        origin, R = self.sk.carry
        s = self.sk.weapon_scale
        mouth = origin + R @ np.array([0, 2.3 * s, 0])
        delta = euler_matrix(hermite(channel(clip, 'saya', (0, 0, 0)), t))
        off = hermite(channel(clip, 'saya_off', (0, 0, 0)), t) * self.sk.arm
        Rs = delta @ R
        return mouth + delta @ (origin - mouth) + off, Rs

    def sword_key(self, clip, key):
        """把一帧刀的编排换成 (主手握点, 刀尖方向, 刃方向)，都在躯干坐标。"""
        s = self.sk.weapon_scale
        if 'sheathed' in key:
            origin, Rs = self.sheath(clip, key['t'])
            o = origin + Rs @ np.array([0, -key['sheathed'] * s, 0])
            return o + Rs @ np.array([0, PRIMARY * s, 0]), Rs[:, 1], Rs[:, 0]
        grip, blade, edge = self.sk.model(key['grip']), unit(key['blade']), unit(key['edge'])
        if key.get('space') == 'hips':
            # 按髋部朝向写的关键帧（例如笔直向前的突刺），换回躯干坐标。
            R = euler_matrix(hermite(channel(clip, 'up', (0, 0, 0)), key['t'])).T
            p = self.sk.pivot('UpBody')
            grip, blade, edge = p + R @ (grip - p), R @ blade, R @ edge
        return grip, blade, edge

    def shaft(self, clip, t, side):
        """手握在武器 Y 轴上的位置（武器网格单位）：默认取骨架的 grips（打刀为 PRIMARY / SECONDARY），
        关键帧的 rpos / lpos 让手沿枪杆滑动。grip 关键帧给的始终是右手的位置。"""
        default = getattr(self.sk, 'grips', (PRIMARY, SECONDARY))[0 if side == 'Right' else 1]
        field = 'rpos' if side == 'Right' else 'lpos'
        if not any(field in k for k in clip['keys']):
            return default
        return float(np.atleast_1d(hermite(channel(clip, field, default), t))[0])

    def sword(self, clip, t):
        keys = [k for k in clip['keys'] if 'grip' in k or 'sheathed' in k]
        s = self.sk.weapon_scale
        # 两个入鞘关键帧之间：刀沿鞘身滑动，跟着鞘走。
        for a, b in zip(keys, keys[1:]):
            if a['t'] <= t <= b['t'] and 'sheathed' in a and 'sheathed' in b:
                u = (t - a['t']) / (b['t'] - a['t']) if b['t'] > a['t'] else 1
                if 'lin' not in b.get('ease', ''):
                    u = u * u * (3 - 2 * u)
                depth = a['sheathed'] + (b['sheathed'] - a['sheathed']) * u
                origin, Rs = self.sheath(clip, t)
                return origin + Rs @ np.array([0, -depth * s, 0]), Rs
        converted = [(k['t'], self.sword_key(clip, k), k.get('ease', '')) for k in keys]
        loop = clip.get('loop') == 'loop' and clip['length']
        grip = hermite([(t0, v[0], e) for t0, v, e in converted], t, loop)
        blade = hermite([(t0, v[1], e) for t0, v, e in converted], t, loop)
        edge = hermite([(t0, v[2], e) for t0, v, e in converted], t, loop)
        R = frame(blade, edge)
        return grip - R @ np.array([0, self.shaft(clip, t, 'Right') * s, 0]), R

    def pose(self, clip, t, prev=None):
        sk = self.sk
        pose = {}
        H = sk.hip
        hips = hermite(channel(clip, 'hips', (0, 0, 0)), t) * H
        hip_rot = euler_matrix(hermite(channel(clip, 'hip_rot', (0, 0, 0)), t))
        up = euler_matrix(hermite(channel(clip, 'up', (0, 0, 0)), t))
        chest = euler_matrix(hermite(channel(clip, 'chest', (0, 0, 0)), t))
        pose['AllBody'] = (hips, hip_rot)
        pose['UpBody'] = (np.zeros(3), up)
        pose['UpperBody'] = (np.zeros(3), chest)
        for name in ('DownBody', 'Arm', 'AllHead'):
            pose[name] = (np.zeros(3), np.eye(3))
        # 第一人称骨架整体靠右下摆放（与长剑第一人称相同），由 root 给出。
        root = getattr(sk, 'root', None)
        # turn：整个人绕脚下竖轴转身（度，绕 +Y）。弓道侧身对靶时为 -90，靶在左手一侧。
        # 玩家第三人称由客户端把原版身体整个转过去（头仍看准星），所以动作里的 Root 只带这一个转角。
        turn = rot_y(float(np.atleast_1d(hermite(channel(clip, 'turn', 0.0), t))[0]))
        pose['Root'] = (np.zeros(3), turn) if root is None else (np.asarray(root[0], float), euler_matrix(root[1]) @ turn)
        W = sk.world(pose)
        torso = W['UpBody']
        Rt = torso[:3, :3]
        # 头：默认看向髋部正前方，再叠加 head 角度。
        look = euler_matrix(hermite(channel(clip, 'head', (0, 0, 0)), t))
        pose['Head'] = (np.zeros(3), W['AllHead'][:3, :3].T @ W['AllBody'][:3, :3] @ look)
        # 刀与鞘（躯干坐标 → 世界）。
        o_s, R_s = self.sheath(clip, t)
        o_w, R_w = self.sword(clip, t)
        sheath_world = torso @ affine(R_s, o_s)
        weapon_world = torso @ affine(R_w, o_w)
        s = sk.weapon_scale

        def target(side, mode):
            if mode in ('sword', 'two'):
                y = self.shaft(clip, t, side)
                return (weapon_world @ np.array([0, y * s, 0, 1]))[:3], weapon_world[:3, :3] @ GRIP.T
            if mode == 'slide':
                # 前手不动、杆从手里滑过（长枪连刺）：手的目标点投到杆上取最近处，夹在骨架给的可握范围里。
                # 够不着最近处时，改取够得着的那段杆上离目标最近的点；整段都够不着就取离肩最近的点。
                p = (torso @ np.append(sk.model(hermite(channel(clip, side[0].lower() + 'hand'), t)), 1))[:3]
                origin, axis = weapon_world[:3, 3], weapon_world[:3, 1]
                lo, hi = getattr(sk, 'slide', (-30.0, 30.0))
                shoulder = (W['Arm'] @ np.append(sk.pivot(side + 'Arm'), 1))[:3]
                reach = 0.97 * (np.linalg.norm(sk.pivot(side + 'ForeArm') - sk.pivot(side + 'Arm'))
                                + np.linalg.norm(sk.pivot(side + 'Hand') - sk.pivot(side + 'ForeArm')) + abs(sk.hand_grip[1]))
                ys = np.arange(lo, hi + 1e-9, 0.25)
                points = origin[None, :] + np.outer(ys * s, axis)
                to_shoulder = np.linalg.norm(points - shoulder, axis=1)
                ok = to_shoulder <= reach
                if ok.any():
                    y = ys[ok][np.argmin(np.linalg.norm(points[ok] - p, axis=1))]
                else:
                    y = ys[np.argmin(to_shoulder)]
                return (weapon_world @ np.array([0, y * s, 0, 1]))[:3], weapon_world[:3, :3] @ GRIP.T
            if mode == 'weapon':
                # 手按武器网格坐标摆（rweapon / lweapon，网格单位）：搭箭、举弓时右手扣在弦上，跟着弓走。
                local = np.asarray(hermite(channel(clip, side[0].lower() + 'weapon', (0, 0, 0)), t), float)
                return (weapon_world @ np.append(local * s, 1))[:3], weapon_world[:3, :3] @ GRIP.T
            if mode == 'saya':
                return (sheath_world @ np.array([0, 3.1 * s, 0, 1]))[:3], sheath_world[:3, :3] @ GRIP.T
            if mode == 'mune':
                # 左掌托住刀背：掌心朝刃，手指横过刀面。mune 给出托点在刀身上的高度（刀的单位）。
                y = float(np.atleast_1d(hermite(channel(clip, 'mune', 8.0), t))[0])
                return (weapon_world @ np.array([-1.0 * s, y * s, 0, 1]))[:3], weapon_world[:3, :3] @ MUNE
            letter = side[0].lower()
            p = torso @ np.append(sk.model(hermite(channel(clip, letter + 'hand'), t)), 1)
            return p[:3], Rt @ euler_matrix(hermite(channel(clip, letter + 'hand_rot', (0, 0, 0)), t))
        hands = {}
        for side, field, default in (('Right', 'rmode', 'sword'), ('Left', 'lmode', 'two')):
            mode, previous, since, blend = self.mode(clip, t, field, default)
            pos, R = target(side, mode)
            if previous is not None and since < blend:
                # 换握法时从旧位置平滑移过去，不瞬移。
                w = since / blend
                w = w * w * (3 - 2 * w)
                old_pos, old_R = target(side, previous)
                pos, R = old_pos + (pos - old_pos) * w, slerp(old_R, R, w)
            hands[side] = pos, R
        body = None
        if getattr(sk, 'body', None) is not None:
            # 躯干方块（静止坐标）；点换回躯干坐标后，陷进去的深度就是到最近一面的距离。
            lo, hi = (np.asarray(v, float) for v in sk.body)
            inverse = np.linalg.inv(torso)

            def body(p):
                q = (inverse @ np.append(p, 1))[:3]
                return max(0.0, float(min(np.min(q - lo), np.min(hi - q))))
        for side in ('Right', 'Left'):
            grip, Rh = hands[side]
            arm, fore, hand = side + 'Arm', side + 'ForeArm', side + 'Hand'
            S = (W['Arm'] @ np.append(sk.pivot(arm), 1))[:3]
            a = np.linalg.norm(sk.pivot(fore) - sk.pivot(arm))
            b = np.linalg.norm(sk.pivot(hand) - sk.pivot(fore))
            pole = Rt @ unit(hermite(channel(clip, side[0].lower() + 'pole', ARM_POLE[side]), t))
            # 玩家模型没有手腕：手与前臂一体，拳心在前臂延长线上（握点偏移只取沿前臂的分量）。
            axial = bool(self.mode(clip, t, side[0].lower() + 'axial', False)[0])
            lift = float(np.atleast_1d(hermite(channel(clip, side[0].lower() + 'lift', 1.0), t))[0])
            F, bend, tau, elbow, residual, over = rigid_arm(S, grip, a, b + abs(sk.hand_grip[1]), pole, Rh,
                                                            self.elbows.get(side), getattr(sk, 'twist', TWIST),
                                                            self.taus.get(side), Rt[:, 1], body, axial, lift)
            self.elbows[side] = elbow
            self.taus[side] = tau
            self.residuals.append((clip['name'], t, side, residual))
            if over > 0.05 * sk.arm:
                self.warnings.append((clip['name'], t, side, over / sk.arm))
            Rparent = W['Arm'][:3, :3]
            pose[arm] = (np.zeros(3), Rparent.T @ F)
            pose[fore] = (np.zeros(3), rot_x(bend) @ rot_y(tau))
            pose[hand] = (np.zeros(3), np.eye(3))
        # 腿：脚踩在指定位置，膝盖朝前。
        for side, sign in (('Right', 1), ('Left', -1)):
            leg, lower, foot = side + 'Leg', side + 'LowerLeg', side + 'Foot'
            rest_foot = sk.pivot(foot)
            # 没写脚步的动作沿用 stance（默认是静止站姿）。
            stance = clip.get('stance', {}).get(side[0].lower() + 'foot', (rest_foot[0] / H, 0, rest_foot[2] / H, 0))
            key = hermite(channel(clip, side[0].lower() + 'foot', stance), t)
            # 脚步写在转身前的朝向里，跟着 turn 一起转（turn 为 0 时不变）。
            target = turn @ np.array([key[0] * H, rest_foot[1] + key[1] * H, key[2] * H])
            S = (W['DownBody'] @ np.append(sk.pivot(leg), 1))[:3]
            a = np.linalg.norm(sk.pivot(lower) - sk.pivot(leg))
            b = np.linalg.norm(rest_foot - sk.pivot(lower))
            knee = turn @ rot_y(key[3]) @ np.array([0.15 * sign, 0, -1])
            F, bend, over = two_bone(S, target, a, b, knee, -1)
            Rparent = W['DownBody'][:3, :3]
            pose[leg] = (np.zeros(3), Rparent.T @ F)
            pose[lower] = (np.zeros(3), rot_x(bend))
            pose[foot] = (np.zeros(3), (F @ rot_x(bend)).T @ turn @ rot_y(key[3]))
        # 刀、鞘骨骼：父级 UpBody，记录网格原点相对枢轴的位移与旋转。
        for name, (o, R) in (('Weapon', (o_w, R_w)), ('Sheath', (o_s, R_s))):
            pose[name] = (o - sk.weapon_pivot, R)
        return pose

    @staticmethod
    def mode(clip, t, field, default):
        """当前握法、换之前的握法、换了多久、过渡时长（关键帧的 blend，默认 0.1 秒）。"""
        value, previous, since, blend = default, None, math.inf, 0.1
        for k in clip['keys']:
            if field in k and k['t'] <= t + 1e-9 and k[field] != value:
                # 第 0 帧的握法是起始握法，不从默认值过渡。
                first = k['t'] <= 1e-9
                value, previous, since, blend = k[field], None if first else value, t - k['t'], k.get('blend', 0.1)
        return value, previous, since, blend

    def bake(self, clip, fps):
        # 关键帧里以骨架标签命名的字段（例如 fox={...}）只对该骨架生效，覆盖同名字段。
        tag = getattr(self.sk, 'tag', None)
        clip = dict(clip, keys=[dict(k, **k.get(tag, {})) for k in clip['keys']])
        frames = []
        self.elbows, self.taus = {}, {}
        n = max(1, round(clip['length'] * fps))
        prev = None
        for i in range(n + 1):
            t = min(clip['length'], i / fps)
            pose = self.pose(clip, t, prev)
            eulers = {}
            for name, entry in pose.items():
                pos, R = entry[0], entry[1]
                e = matrix_euler(R, None if prev is None or name not in prev else prev[name][1])
                eulers[name] = (pos, e) if len(entry) < 3 else (pos, e, np.asarray(entry[2], float))
            frames.append((t, eulers))
            prev = eulers
        return frames


# 前臂绕自身长轴的转动：None 表示只转 90° 的整数倍（袖子截面是正方形，肘部棱角保持对齐）；
# 给数字则连续转动、以此为上限（度）。连续转动会让方块袖子在肘部错开、棱角穿插。
TWIST = None
# 手肘朝向默认方向（ARM_POLE）的权重，与刀柄偏角（弧度）相比。
ELBOW_PREF = 1.4
PRIMARY = 0.65
SECONDARY = -1.1

# ---------------------------------------------------------------- 导出


def bedrock(clips, baked, skeleton, bones, markers=True):
    """Bedrock 1.8.0 动画文件。GeckoLib 把动画旋转加在静止旋转上，所以这里写差值。"""
    out = {}
    for clip in clips:
        frames = baked[clip['name']]
        anim = {'animation_length': round(clip['length'], 4)}
        if clip.get('loop') == 'loop':
            anim['loop'] = True
        elif clip.get('loop') == 'hold':
            anim['loop'] = 'hold_on_last_frame'
        tracks = {}
        for name in bones:
            rest = skeleton.bones[name]['rest']
            rot, pos, scale = {}, {}, {}
            for t, eulers in frames:
                entry = eulers[name]
                p, e = entry[0], entry[1] - rest
                key = f'{t:.4f}'.rstrip('0').rstrip('.') if t else '0.0'
                rot[key] = [round(-e[0], 3), round(-e[1], 3), round(e[2], 3)]
                pos[key] = [round(-p[0], 4), round(p[1], 4), round(p[2], 4)]
                sc = entry[2] if len(entry) > 2 else (1.0, 1.0, 1.0)
                scale[key] = [round(float(v), 4) for v in sc]
            track = {'rotation': compress(rot)}
            if any(any(abs(v) > 1e-4 for v in value) for value in pos.values()):
                track['position'] = compress(pos)
            if any(any(abs(v - 1) > 1e-4 for v in value) for value in scale.values()):
                track['scale'] = compress(scale)
            tracks[name] = track
        anim['bones'] = tracks
        if markers and clip.get('markers'):
            anim['timeline'] = timeline(clip['markers'])
        out[clip['name']] = anim
    return {'format_version': '1.8.0', 'animations': out}


def marker_list(markers):
    """标记表 {名: 时刻或时刻列表} 展开成按时间排序的 (时刻, 名)；连刺这类多段命中的 contact 可以有多个。"""
    out = []
    for name, value in (markers or {}).items():
        for t in (value if isinstance(value, (list, tuple)) else [value]):
            out.append((float(t), name))
    return sorted(out)


def timeline(markers):
    """Bedrock timeline：同一时刻只有一个标记时写字符串，有几个时写数组。"""
    grouped = {}
    for t, name in marker_list(markers):
        grouped.setdefault(f'{t:g}', []).append(name)
    return {t: names[0] if len(names) == 1 else names for t, names in grouped.items()}


def compress(track):
    """去掉能由前后保留帧线性插值还原的中间帧；被删的每一帧误差都不超过 0.05。"""
    items = list(track.items())
    if all(v == items[0][1] for _, v in items):
        return {items[0][0]: items[0][1]}
    times = [float(t) for t, _ in items]

    def fits(a, b):
        for i in range(a + 1, b):
            u = (times[i] - times[a]) / (times[b] - times[a])
            if any(abs(x + (z - x) * u - y) > 0.05 for x, y, z in zip(items[a][1], items[i][1], items[b][1])):
                return False
        return True
    keep, start = [0], 0
    for j in range(2, len(items)):
        if not fits(start, j):
            start = j - 1
            keep.append(start)
    keep.append(len(items) - 1)
    return {items[i][0]: items[i][1] for i in keep}


def new_uuid():
    return str(uuid.uuid4())
