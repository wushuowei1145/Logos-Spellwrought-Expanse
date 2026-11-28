# -*- coding: utf-8 -*-
"""连携切入里每位同伴摆的姿势（docs/CHAIN_CUTIN.md「角色的姿势」）：写进她动画文件里的 `cutin` 循环动作。

姿势不从零摆，而是从她已有的动作里取某一刻（出刀、拉弓、施法、拔剑……那些都是按她的人设做的），
几段叠起来（后面的盖前面的同名骨骼），再按需要微调几根骨骼。表情、眼睛、嘴不写进来，交给表情动作（`face_*`）。

用法：python tools/chain_cutin_poses.py   （改完直接写回四个 animation.json）

文件里的数值和 Blockbench 导出的 Bedrock 动画一致（docs/COMBAT_SKELETON.md 第 3 节），这里只做取样和相加，不换坐标。
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ANIMS = ROOT / 'src/main/resources/assets/logos_dimension/geckolib/animations/entity'

# 表情归表情动作管：眼睛、眼皮、嘴、腮红、耳朵这些不写进姿势
FACE = re.compile(r'(Eye|Lid|Mouth|Iris|Pupil|Lash|Blush|Mark|Brow|Tear|Sweat|Cheek|Ahoge|_ear$|^Ear$|Glow|Sigil|Coin|Flask)')

# 每位：layers 依次叠（动作名、取第几秒、只取这些骨骼（None 是全部）[, 'mirror']），tweak 在取样结果上再加的角度（度，文件里的符号）。
# 带 'mirror' 的一层左右对调：Right* 的值给 Left*（反过来也一样），旋转的 y、z 和位移的 x 取反——把右手的手势搬到左手。
POSES = {
    # 白梅：流火逆袈裟，刀从左胸斜着划过身前到右腿外；左手压在胸前凝狐火。两只手都在胸口以下，不挡脸
    'shiraume_companion': dict(
        layers=[('ryuka_idle', 0.0, None),
                ('ryuka_gyaku', 0.28, None),
                ('cast_foxfire_l', 0.48, r'^Left(Arm|ForeArm|Hand)$')],
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


def build(clips, spec):
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
        clips['cutin'] = build(clips, spec)
        # 照原文件的写法写回（一行的紧凑写法，或缩进 1 格），别的动作一个字节不变
        if '\n' in text.strip():
            out = json.dumps(data, ensure_ascii=False, indent=1)
        else:
            out = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        path.write_text(out + ('\n' if text.endswith('\n') else ''), encoding='utf-8', newline='')
        print(asset, len(clips['cutin']['bones']), 'bones')


if __name__ == '__main__':
    main()
