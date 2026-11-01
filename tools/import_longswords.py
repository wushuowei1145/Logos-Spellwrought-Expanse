"""将审定的 Blockbench 双手剑素材转换为战斗骨架数据（docs/COMBAT_SKELETON.md）。

输出三类文件：
- combat/skeletons/player_<视角>.json：玩家参考骨架（TLM 骨骼名）与按皮肤取 UV 的分段手臂；
- combat/weapons/<剑>.json：剑与剑鞘网格，坐标相对握柄原点、按原始美术尺寸（缩放 1）；
- combat/animations/longsword_<视角>.animation.json：Bedrock 1.8.0 动作，与 Blockbench 导出的符号一致。

展示人偶的头、躯干和腿只提供骨骼枢轴，不导出方块。
"""
import argparse
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
COMBAT = ASSETS / 'combat'

# 美术工程骨骼 → TLM 骨骼名；剑与剑鞘是战斗骨架的扩展骨骼。
RENAME = {
    'root': 'Root', 'body': 'UpBody', 'head': 'Head',
    'right_upper_arm': 'RightArm', 'right_forearm': 'RightForeArm', 'right_hand': 'RightHand',
    'left_upper_arm': 'LeftArm', 'left_forearm': 'LeftForeArm', 'left_hand': 'LeftHand',
    'right_leg': 'RightLeg', 'left_leg': 'LeftLeg',
    'sword_root': 'Weapon', 'scabbard_root': 'Sheath',
}
# 美术工程里没有、但标准骨架必需的骨骼：没有动作轨道，只补全层级（Blockbench 坐标，角色右侧为 +X）。
EXTRA = {
    'AllBody': ('Root', [0, 12, 0]), 'UpperBody': ('UpBody', [0, 21, 0]), 'AllHead': ('UpperBody', [0, 24, 0]),
    'Arm': ('UpperBody', [0, 23, 0]), 'DownBody': ('AllBody', [0, 12, 0]),
    'RightLowerLeg': ('RightLeg', None), 'RightFoot': ('RightLowerLeg', None),
    'LeftLowerLeg': ('LeftLeg', None), 'LeftFoot': ('LeftLowerLeg', None),
}
# 原工程的父级换成标准层级；只插入不带动作的中间骨骼，世界变换不变。
PARENT = {'UpBody': 'AllBody', 'Head': 'AllHead', 'RightArm': 'Arm', 'LeftArm': 'Arm',
          'RightLeg': 'DownBody', 'LeftLeg': 'DownBody', 'Weapon': 'UpBody', 'Sheath': 'UpBody'}
ORDER = ['Root', 'AllBody', 'UpBody', 'UpperBody', 'AllHead', 'Head', 'Arm',
         'RightArm', 'RightForeArm', 'RightHand', 'LeftArm', 'LeftForeArm', 'LeftHand',
         'DownBody', 'RightLeg', 'RightLowerLeg', 'RightFoot', 'LeftLeg', 'LeftLowerLeg', 'LeftFoot',
         'Weapon', 'Sheath']
GRIP = [0, 1, 0]  # 剑与剑鞘骨骼的枢轴，也是网格的原点
SCALE = {'tp': 0.8, 'fp': 0.58}  # 美术包里两个视角相对原始剑模型的缩放
LOOP = {'loop': True, 'once': False, 'hold': 'hold_on_last_frame'}


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')


def outline(model):
    groups = {g['uuid']: g for g in model['groups']}
    parents, owners = {}, {}

    def visit(nodes, parent=None):
        for node in nodes:
            if isinstance(node, str):
                owners[node] = parent
            else:
                name = groups[node['uuid']]['name']
                parents[name] = parent
                visit(node['children'], name)
    visit(model['outliner'])
    return parents, owners


def skeleton(model, view):
    parents, owners = outline(model)
    bones = {}
    for group in model['groups']:
        if group['name'] in RENAME:
            name = RENAME[group['name']]
            bones[name] = dict(parent=PARENT.get(name, RENAME.get(parents[group['name']])), pivot=group['origin'])
    for name, (parent, pivot) in EXTRA.items():
        if pivot is None:  # 膝与踝取原版 12 像素腿的中点与底部
            hip = bones['RightLeg' if name.startswith('Right') else 'LeftLeg']['pivot']
            pivot = [hip[0], 6 if 'Lower' in name else 1, hip[2]]
        bones[name] = dict(parent=parent, pivot=pivot)
    assert set(bones) == set(ORDER), set(bones) ^ set(ORDER)
    skin = []
    for cube in model['elements']:
        bone = owners[cube['uuid']]
        if bone.startswith(('right_', 'left_')) and 'leg' not in bone:
            skin.append(dict(name=cube['name'], bone=RENAME[bone], start=cube['from'], end=cube['to'],
                             pivot=cube['origin'], rotation=cube['rotation']))
    return dict(view=view, weapon_scale=SCALE[view],
                bones=[dict(name=n, parent=bones[n]['parent'], pivot=bones[n]['pivot']) for n in ORDER], skin=skin)


def weapon(model, variant):
    """剑网格取第三人称工程，换算到握柄原点、缩放 1。"""
    _, owners = outline(model)

    def local(p):
        return [(p[i] - GRIP[i]) / SCALE['tp'] for i in range(3)]
    cubes = []
    for cube in model['elements']:
        bone = owners[cube['uuid']]
        if not bone.startswith(('sword_', 'scabbard_')):
            continue
        cubes.append(dict(name=cube['name'], part='sheath' if bone.startswith('scabbard_') else 'weapon',
                          start=local(cube['from']), end=local(cube['to']), pivot=local(cube['origin']),
                          rotation=cube['rotation'], uv={face: data['uv'] for face, data in cube['faces'].items()}))
    return dict(texture=f'logos_dimension:textures/item/{variant}_rig.png',
                texture_size=[model['resolution']['width'], model['resolution']['height']], cubes=cubes)


def animations(model):
    """Blockbench 关键帧 → Bedrock：旋转 X、Y 取反，位移 X 取反（与 Blockbench 的 Bedrock 导出一致）。"""
    result = {}
    for animation in model['animations']:
        bones = {}
        for animator in animation['animators'].values():
            if animator.get('name') not in RENAME:
                continue
            channels = {}
            for frame in sorted(animator['keyframes'], key=lambda k: k['time']):
                p = frame['data_points'][0]
                x, y, z = (float(p[a]) for a in 'xyz')
                value = {'rotation': [-x, -y, z], 'position': [-x, y, z]}.get(frame['channel'], [x, y, z])
                channels.setdefault(frame['channel'], []).append((frame['time'], [v + 0.0 for v in value]))
            track = {}
            for channel, keys in channels.items():
                # 常量轨只保留端点；运动轨保留原始接触时刻和全部采样，避免更改动作。
                if all(k[1] == keys[0][1] for k in keys):
                    keys = [keys[0], keys[-1]]
                track[channel] = {repr(float(t)): value for t, value in keys}
            bones[RENAME[animator['name']]] = track
        result[animation['name'].rsplit('.', 1)[-1]] = dict(
            loop=LOOP.get(animation.get('loop', 'once'), False), animation_length=animation['length'], bones=bones)
    return dict(format_version='1.8.0', animations=result)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('art', type=Path, help='包含 dawnvow 和 blacksteel_longsword 的本地素材目录')
    args = parser.parse_args()
    (ASSETS / 'textures/item').mkdir(parents=True, exist_ok=True)
    for variant, source, static in [
        ('dawnvow', args.art / 'dawnvow/v04_preview_fix', args.art / 'dawnvow/v01'),
        ('blacksteel_longsword', args.art / 'blacksteel_longsword/v01', args.art / 'blacksteel_longsword/v01')
    ]:
        for view in ['fp', 'tp']:
            model = json.loads((source / f'{variant}_{view}.bbmodel').read_text(encoding='utf-8'))
            if variant == 'dawnvow':  # 两把剑共用曦誓工程的玩家骨架与动作
                write(COMBAT / f'skeletons/player_{view}.json', skeleton(model, view))
                write(COMBAT / f'animations/longsword_{view}.animation.json', animations(model))
            if view == 'tp':
                write(COMBAT / f'weapons/{variant}.json', weapon(model, variant))
                shutil.copyfile(source / f'{variant}_{view}.png', ASSETS / f'textures/item/{variant}_rig.png')
        item = json.loads((static / f'{variant}.json').read_text(encoding='utf-8'))
        item.pop('credit', None)
        item['textures'] = {k: v.replace('logos:', 'logos_dimension:') for k, v in item['textures'].items()}
        write(ASSETS / f'models/item/{variant}.json', item)
        write(ASSETS / f'items/{variant}.json', dict(model=dict(type='minecraft:model', model=f'logos_dimension:item/{variant}')))
        shutil.copyfile(static / f'{variant}.png', ASSETS / f'textures/item/{variant}.png')


if __name__ == '__main__':
    main()
