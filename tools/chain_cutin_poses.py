# -*- coding: utf-8 -*-
"""连携切入里每位同伴摆的姿势（docs/CHAIN_CUTIN.md「角色的姿势」）：写进她动画文件里的 `cutin` 循环动作。

姿势多半不从零摆，而是从她已有的动作里取某一刻（出刀、拉弓、施法、拔剑……那些都是按她的人设做的），
几段叠起来（后面的盖前面的同名骨骼），再按需要微调几根骨骼。表情、眼睛、嘴不写进来，交给表情动作（`face_*`）。
已有动作里找不到合适的一刻时（刀的位置不对），用 `sword` 写刀的编排——握点、刀向、刃向、另一只手、脚——
由战斗动作的解算器（tools/combat_author.py 的 Baker）在她自己的骨架上解出手臂和刀，手一定握在刀柄上。

用法：uv run --with numpy --with pillow python tools/chain_cutin_poses.py   （改完直接写回四个 animation.json）

文件里的数值和 Blockbench 导出的 Bedrock 动画一致（docs/COMBAT_SKELETON.md 第 3 节），叠层只做取样和相加，不换坐标。
"""
import json
import re
from pathlib import Path

import numpy as np

from combat_author import CORE, Baker, Skeleton, bedrock
from ryuka_moves import GRIPS

ROOT = Path(__file__).resolve().parent.parent
GECKO = ROOT / 'src/main/resources/assets/logos_dimension/geckolib'
ANIMS = GECKO / 'animations/entity'

# 刀挂在谁的骨架上：拳心相对手部枢轴（模型像素）、刀的网格单位 → 模型像素、躯干方块（手臂不能穿进去）。
# 少女白梅的数值同她的流火招式（维护者模型目录 nine_tailed_fox_companion/tools/companion_ryuka.py）。
SHIRAUME_RIG = dict(hand_grip=(0, -1.0, -0.15), weapon_scale=0.93, body=((-2.6, 18.2, -2.2), (2.6, 26.4, 2.1)))

# 表情归表情动作管：眼睛、眼皮、嘴、腮红、耳朵这些不写进姿势
FACE = re.compile(r'(Eye|Lid|Mouth|Iris|Pupil|Lash|Blush|Mark|Brow|Tear|Sweat|Cheek|Ahoge|_ear$|^Ear$|Glow|Sigil|Coin|Flask)')

# 每位：layers 依次叠（动作名、取第几秒、只取这些骨骼（None 是全部）[, 'mirror']），tweak 在取样结果上再加的角度（度，文件里的符号）。
# 带 'mirror' 的一层左右对调：Right* 的值给 Left*（反过来也一样），旋转的 y、z 和位移的 x 取反——把右手的手势搬到左手。
# sword（可选）是刀的编排，解出来的骨骼盖在 layers 上面：rig 是上面的骨架数值，其余字段同 tools/shiraume_moves.py 的姿势——
# 刀、手在躯干坐标（两肩中点为原点、臂长为单位；前 -Z、上 +Y、她的右手 +X），edge 写刃大致朝哪边（解算时投到刀身的垂面上），
# up / head 是躯干、头的角度，脚以髋高为单位。
POSES = {
    # 白梅：流火竖在右肩外（八相），刀面朝前、刀尖过头，左手叉腰——赢定了的那只骄傲的九尾。
    # 握刀的手在胸口高度、肩膀外侧，不挡脸；刀身在身体轮廓外面，晶刃和狐火衬在暗底上看得见。
    # （刀横在身前时晶刃和白衣叠在一起看不出来，只剩握刀的手停在小腹前，像把手伸进了衣服里；
    # 扛在肩上往后伸，从正面看刀身藏在肩后，只有刀柄朝着镜头戳出来。）
    'shiraume_companion': dict(
        layers=[],
        sword=dict(rig=SHIRAUME_RIG,
                   grip=(0.5, -0.18, -0.32), blade=(0.2, 0.97, 0.1), edge=(1, 0, -0.4),
                   lmode='free', lhand=(-0.46, -0.66, 0.04), lpole=(-1, 0.1, 0.6),
                   up=(4, -8, -4), head=(-4, 8, 7),
                   rfoot=(0.16, 0, -0.18, -6), lfoot=(-0.22, 0, 0.16, 20)),
        tweak={},
    ),
    # 精灵公主：世界树之弓拉满，朝前瞄（身子侧多少在 CompanionStyle 里给）
    'elf_princess_companion': dict(
        layers=[('use_mainhand:bow', 0.5, None)],
        tweak={},
    ),
    # 琥珀：右手照常拄着长杖，左手抬到下巴前竖起一指——想到解法的那一下（她自己的 act_formula 是右手，镜像到左手）
    'kohaku_companion': dict(
        layers=[('act_formula', 0.7, r'^Left(Arm|ForeArm|Hand)$', 'mirror')],
        # 手收到下巴前：五人时格子只有肩宽，举在脸外侧会被斜边切掉
        tweak={'LeftArm': {'rotation': [-20, 45, -45]}},
    ),
    # 拉塞塔：炽焰长剑剑尖朝下竖在身前、手按剑柄（收剑那一刻，骑士拄剑），双翼全展
    'lacerta_companion': dict(
        layers=[('crimson_sheathe', 0.8, None),
                ('act_wingspan', 1.8, r'^Wing')],
        tweak={},
    ),
}

# 整个人的位移（跳起、冲刺）不要：特写的镜头对着她站着的样子
NO_POSITION = ('Root', 'AllBody')


def value(channel, t):
    """一条轨道在第 t 秒的值（线性插值；分段值取 post）。"""
    if isinstance(channel, list):
        return [float(v) for v in channel]
    keys = sorted((float(k), v) for k, v in channel.items())

    def vec(v):
        if isinstance(v, dict):
            v = v.get('post', v.get('pre'))
        return [float(x) for x in v]

    if t <= keys[0][0]:
        return vec(keys[0][1])
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t0 <= t <= t1:
            k = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            a, b = vec(v0), vec(v1)
            return [x + (y - x) * k for x, y in zip(a, b)]
    return vec(keys[-1][1])


def mirrored(bone):
    if bone.startswith('Right'):
        return 'Left' + bone[5:]
    if bone.startswith('Left'):
        return 'Right' + bone[4:]
    return bone


#: 解刀的编排时写出的骨骼：躯干、头、双臂、刀、双腿（Root、AllHead、Arm 解算器不动）。
SOLVED = [n for n in CORE if n not in ('Root', 'AllHead', 'Arm')] + ['Weapon']


def skeleton(asset, rig):
    """她的骨架从 geo.json 读（Bedrock 几何的枢轴 x、静止旋转 x/y 取反，换回 Blockbench 空间），刀挂在 UpBody 下。"""
    geo = json.loads((GECKO / f'models/entity/{asset}.geo.json').read_text(encoding='utf-8'))['minecraft:geometry'][0]
    parents = {b['name']: b.get('parent') for b in geo['bones']}
    bones = []
    for b in geo['bones']:
        if b['name'] not in CORE:
            continue
        parent = parents[b['name']]
        while parent and parent not in CORE:
            parent = parents[parent]
        p, r = b.get('pivot', [0, 0, 0]), b.get('rotation', [0, 0, 0])
        bones.append(dict(name=b['name'], parent=parent, pivot=np.array([-p[0], p[1], p[2]], float),
                          rest=np.array([-r[0], -r[1], r[2]], float)))
    for name in ('Weapon', 'Sheath'):
        bones.append(dict(name=name, parent='UpBody', pivot=np.zeros(3), rest=np.zeros(3)))
    sk = Skeleton(bones, rig['hand_grip'], rig['weapon_scale'], (np.zeros(3), np.eye(3)), weapon_pivot=(0, 0, 0))
    sk.grips, sk.body = GRIPS, rig['body']
    return sk


def solve(asset, sword):
    """按刀的编排解出这一刻的骨骼，数值同 bedrock() 导出的动作。"""
    sk = skeleton(asset, sword['rig'])
    key = {k: v for k, v in sword.items() if k != 'rig'}
    clip = dict(name='cutin', length=1.0, keys=[dict(key, t=0)], stance={'rfoot': key['rfoot'], 'lfoot': key['lfoot']})
    frames = Baker(sk).bake(clip, 1)[:1]
    tracks = bedrock([clip], {'cutin': frames}, sk, SOLVED, markers=False)['animations']['cutin']['bones']
    return {bone: {ch: [round(v, 4) for v in value(track, 0)] for ch, track in channels.items()} for bone, channels in tracks.items()}


def build(asset, clips, spec):
    bones = {}
    for layer in spec['layers']:
        name, t, only = layer[:3]
        mirror = len(layer) > 3 and layer[3] == 'mirror'
        source = clips[name].get('bones', {})
        for bone, channels in source.items():
            target = mirrored(bone) if mirror else bone
            if FACE.search(target) or (only and not re.search(only, target)):
                continue
            values = {ch: [round(v, 4) for v in value(track, t)] for ch, track in channels.items()
                      if ch in ('rotation', 'scale') or (ch == 'position' and target not in NO_POSITION)}
            if mirror:
                if 'rotation' in values:
                    x, y, z = values['rotation']
                    values['rotation'] = [x, round(-y, 4), round(-z, 4)]
                if 'position' in values:
                    x, y, z = values['position']
                    values['position'] = [round(-x, 4), y, z]
            bones[target] = values
    if 'sword' in spec:
        for bone, values in solve(asset, spec['sword']).items():
            bones[bone] = {ch: v for ch, v in values.items() if not (ch == 'position' and bone in NO_POSITION)}
    for bone, delta in spec['tweak'].items():
        for ch, d in delta.items():
            base = bones.setdefault(bone, {}).get(ch, [1, 1, 1] if ch == 'scale' else [0, 0, 0])
            bones[bone][ch] = [round(a + b, 4) for a, b in zip(base, d)]
    return {'loop': True, 'animation_length': 1.0, 'bones': bones}


def main():
    for asset, spec in POSES.items():
        path = ANIMS / f'{asset}.animation.json'
        text = path.read_text(encoding='utf-8')
        data = json.loads(text)
        clips = data['animations']
        clips['cutin'] = build(asset, clips, spec)
        # 照原文件的写法写回（一行的紧凑写法，或缩进 1 格），别的动作一个字节不变
        if '\n' in text.strip():
            out = json.dumps(data, ensure_ascii=False, indent=1)
        else:
            out = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        path.write_text(out + ('\n' if text.endswith('\n') else ''), encoding='utf-8', newline='')
        print(asset, len(clips['cutin']['bones']), 'bones')


if __name__ == '__main__':
    main()
