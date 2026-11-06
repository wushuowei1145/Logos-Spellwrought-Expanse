# -*- coding: utf-8 -*-
"""黑钢长剑的施法动作（单手持剑、剑尖指向前方）：编排、烘焙、导出。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/blacksteel_cast_moves.py

美术包里没有施法动作，这里用 combat_author 按刀路编排（编排坐标见 combat_author.py 开头），交给美术之前先用它：
- cast_aim：蓄力与持续光柱时保持的单手前指，第三人称手臂从肩到拳完全伸直，剑顺着手臂的延长线指向前方，右肩略向前送，左手自然垂下（循环，静止）；
- cast_thrust：施放：向前一送（第三人称手臂已经伸直，靠转身前倾把肩送出去），再收回到长剑待机（一次）；
- cast_lower：光柱结束：从前指收回到待机（一次）；
- guard_lower：竖剑格挡放下：共用的 charge_start（举剑竖起）倒过来（一次）。格挡循环比举剑长，
  不能像 charge_cancel 那样按招式倒放（倒放起点取上一招的播放时刻）。

剑尖朝向按髋部写（space='hips'），躯干转动不带偏剑尖。第一人称骨架整体有长剑待机的根骨侧倾，
剑尖与刃的方向先在视图里给出、再换回骨架里，剑尖斜着指向画面中央。收回的终点是长剑 idle 第 0 帧，接进待机不跳。
剑鞘不动，沿用 idle 的剑鞘轨道。动作写进 combat/animations/blacksteel_longsword_{tp,fp}.animation.json，
长剑骨架加载时叠在长剑动作上（见 LongswordRig）。
"""
import json

import numpy as np

import combat_author
from combat_author import ARM_POLE, Baker, bedrock, euler_matrix, unit
from dawnvow_moves import COMBAT, idle_pose, k, skeletons

# 黑钢长剑的握柄与曦誓一样从 -3.2 到 3.2：右手握靠护手的一半。
combat_author.PRIMARY = 1.6
combat_author.SECONDARY = -1.6

AIM_LENGTH, THRUST_LENGTH, LOWER_LENGTH = 2.0, 0.6, 0.4
# 施放的时刻（秒）：送到最前、回到前指。
THRUST_OUT, THRUST_BACK = 0.08, 0.24
HANG_L = (-0.44, -0.96, 0.06)
BACK_L = (-0.44, -0.9, 0.22)
# 第三人称：剑尖笔直向前（略向内收），刃竖直（像握手那样握剑）。躯干的转身与前倾：施放时转得更多、前倾，把伸直的手臂送出去。
TP_BLADE, TP_EDGE = (-0.08, 0, -1), (0, -1, 0)
TP_AIM_UP, TP_THRUST_UP = (0, 15, 0), (-8, 32, 0)
# 手肘朝下：手臂伸直时方块袖子不绕长轴歪着。
STRAIGHT_POLE = (0, -1, 0)
# 第一人称（视图方向）：剑柄在画面右下，剑尖斜着指向画面中央。
FP_BLADE, FP_EDGE = (-0.26, 0.17, -1), (0, -1, 0)
FP_REACH, FP_THRUST_REACH = (-0.12, 0.1, -0.22), (-0.14, 0.12, -0.4)


def view_to_skeleton(root, v):
    """第一人称：视图里的方向换到骨架躯干坐标（根骨的旋转倒回去）。"""
    return tuple(euler_matrix(root[1]).T @ np.asarray(v, float))


def straight_arm(sk, up):
    """第三人称：肩到拳心正好是整条手臂的长度，手臂和剑在同一条线上（按髋部写）。护手正好露在拳头外面。"""
    p = sk.pivot('UpBody')
    shoulder = p + euler_matrix(up) @ (sk.pivot('RightArm') - p)
    length = np.linalg.norm(sk.pivot('RightForeArm') - sk.pivot('RightArm')) \
        + np.linalg.norm(sk.pivot('RightHand') - sk.pivot('RightForeArm')) + abs(sk.hand_grip[1])
    grip = shoulder + unit(TP_BLADE) * length
    return dict(space='hips', up=up, grip=tuple((grip - sk.center) / sk.arm), blade=TP_BLADE, edge=TP_EDGE, rpole=STRAIGHT_POLE)


def keys(sk, view, idle, root):
    if view == 'tp':
        aim, thrust = straight_arm(sk, TP_AIM_UP), straight_arm(sk, TP_THRUST_UP)
    else:
        blade, edge = view_to_skeleton(root, FP_BLADE), view_to_skeleton(root, FP_EDGE)
        base = np.asarray(idle['grip'])
        aim = dict(up=(0, 0, 0), grip=tuple(base + FP_REACH), blade=blade, edge=edge)
        thrust = dict(aim, grip=tuple(base + FP_THRUST_REACH))
    one_hand = dict(rmode='sword', lmode='free', lhand=HANG_L, lhand_rot=(0, 0, 0))
    return [
        dict(name='cast_aim', length=AIM_LENGTH, loop='loop',
             keys=[k(0.0, aim, **one_hand), k(AIM_LENGTH, aim, **one_hand)]),
        dict(name='cast_thrust', length=THRUST_LENGTH, loop='once', keys=[
            k(0.0, aim, **one_hand),
            # 送到最前、回到前指都停稳一下（切线为 0），剑尖不会被收回待机的那一段带着往上翘。
            k(THRUST_OUT, thrust, ease='hold', **dict(one_hand, lhand=BACK_L)),
            k(THRUST_BACK, aim, ease='hold', **one_hand),
            # 左手回到剑柄上，双手握回待机。
            k(THRUST_LENGTH, idle, lmode='two', blend=0.25, rpole=ARM_POLE['Right'])]),
        dict(name='cast_lower', length=LOWER_LENGTH, loop='once', keys=[
            k(0.0, aim, **one_hand),
            k(LOWER_LENGTH, idle, lmode='two', blend=0.3, rpole=ARM_POLE['Right'])]),
    ]


def reverse(clip):
    """把一段一次性动作倒过来：每个关键帧的时间换成 时长 − 时间。"""
    length = clip['animation_length']
    bones = {}
    for bone, channels in clip['bones'].items():
        bones[bone] = {channel: {f'{round(length - float(t), 7):g}' if float(t) < length else '0.0': v
                                 for t, v in sorted(frames.items(), key=lambda kv: -float(kv[0]))}
                       for channel, frames in channels.items()}
    return {'loop': False, 'animation_length': length, 'bones': bones}


def main():
    tp, fp = skeletons()
    for view, sk in (('tp', tp), ('fp', fp)):
        idle, root = idle_pose(view, sk)
        if root is not None:
            sk.root = root
        clips = keys(sk, view, idle, root)
        baker = Baker(sk)
        baked = {clip['name']: baker.bake(clip, 60) for clip in clips}
        for name, t, side, over in baker.warnings:
            print(f'{view} {name} {side} {t:.2f}s 手够不着，差 {over:.2f} 臂长')
        bones = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
                 'LeftArm', 'LeftForeArm', 'LeftHand', 'Weapon'] + (['Root'] if view == 'fp' else [])
        data = bedrock(clips, baked, sk, bones, markers=False)
        shared = json.load(open(COMBAT / f'animations/longsword_{view}.animation.json', encoding='utf-8'))['animations']
        for clip in data['animations'].values():
            clip['bones']['Sheath'] = shared['idle']['bones']['Sheath']
        data['animations']['guard_lower'] = reverse(shared['charge_start'])
        (COMBAT / f'animations/blacksteel_longsword_{view}.animation.json').write_text(
            json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
        print('wrote', f'blacksteel_longsword_{view}.animation.json')


if __name__ == '__main__':
    main()
