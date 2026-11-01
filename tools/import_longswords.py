"""将审定的 Blockbench 双手剑素材转换为运行时数据；不把展示人偶放进玩家模型。"""
import argparse
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')


def convert(source, variant, view):
    model = json.loads((source / f'{variant}_{view}.bbmodel').read_text(encoding='utf-8'))
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
    bones = [dict(name=g['name'], parent=parents[g['name']], pivot=g['origin']) for g in model['groups']]
    cubes = []
    for cube in model['elements']:
        bone = owners[cube['uuid']]
        if not (bone.startswith(('sword_', 'scabbard_', 'right_', 'left_')) and 'leg' not in bone):
            continue
        skin = bone.startswith(('right_', 'left_'))
        cubes.append(dict(name=cube['name'], bone=bone, skin=skin, start=cube['from'], end=cube['to'],
                          pivot=cube['origin'], rotation=cube['rotation'],
                          uv={face: data['uv'] for face, data in cube['faces'].items()}))
    write(ASSETS / f'longsword/{variant}_{view}.json', dict(bones=bones, cubes=cubes))
    shutil.copyfile(source / f'{variant}_{view}.png', ASSETS / f'textures/item/{variant}_rig.png')
    if variant != 'dawnvow':
        return
    clips = {}
    for animation in model['animations']:
        tracks = {}
        for animator in animation['animators'].values():
            channels = {}
            for frame in animator['keyframes']:
                point = frame['data_points'][0]
                channels.setdefault(frame['channel'], []).append([frame['time'], *[float(point[a]) for a in 'xyz']])
            for channel, keys in channels.items():
                # 常量轨只保留端点；运动轨保留原始接触时刻和全部采样，避免更改动作。
                if all(k[1:] == keys[0][1:] for k in keys):
                    channels[channel] = [keys[0], keys[-1]]
            tracks[animator['name']] = channels
        clips[animation['name'].rsplit('.', 1)[-1]] = dict(length=animation['length'], tracks=tracks)
    write(ASSETS / f'longsword/motion_{view}.json', clips)


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
            convert(source, variant, view)
        item = json.loads((static / f'{variant}.json').read_text(encoding='utf-8'))
        item.pop('credit', None)
        item['textures'] = {k: v.replace('logos:', 'logos_dimension:') for k, v in item['textures'].items()}
        write(ASSETS / f'models/item/{variant}.json', item)
        write(ASSETS / f'items/{variant}.json', dict(model=dict(type='minecraft:model', model=f'logos_dimension:item/{variant}')))
        shutil.copyfile(static / f'{variant}.png', ASSETS / f'textures/item/{variant}.png')


if __name__ == '__main__':
    main()
