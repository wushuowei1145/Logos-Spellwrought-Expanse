"""审计剑祟导出资源；这里只验证文件和数据，不代替游戏内测试。"""
import json
import math
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def require(value, message):
    if not value:
        raise ValueError(message)


def vector(value):
    require(isinstance(value, list) and len(value) == 3, '通道必须是三维向量')
    require(all(isinstance(v, (int, float)) and math.isfinite(v) for v in value), '向量含非有限数值')


def audit():
    document = load(ASSETS / 'geckolib/models/entity/jian_sui.geo.json')
    require(document['format_version'] == '1.12.0', '几何格式不符')
    geo, = document['minecraft:geometry']
    require(geo['description']['identifier'] == 'geometry.jian_sui', '模型标识不符')
    bones = {b['name']: b for b in geo['bones']}
    require(len(bones) == len(geo['bones']) == 152, '骨骼名称重复或数量不符')
    required = {'Root', 'AllBody', 'UpBody', 'UpperBody', 'AllHead', 'Head', 'Arm', 'DownBody'}
    required.update(side + part for side in ('Left', 'Right') for part in ('Arm', 'ForeArm', 'Hand', 'Leg', 'LowerLeg', 'Foot'))
    require(required <= bones.keys(), '缺少标准骨骼')
    for name, bone in bones.items():
        vector(bone['pivot'])
        seen = {name}
        while bone.get('parent'):
            parent = bone['parent']
            require(parent in bones and parent not in seen, '骨骼父级缺失或存在循环')
            seen.add(parent)
            bone = bones[parent]
    require(not bones['Weapon'].get('cubes'), '武器挂点不应包含持剑几何')
    require(bones['Skirt'].get('cubes') and all(f'WaistBridge{i}' in bones for i in range(8)), '腰部零件缺失')

    image = Image.open(ASSETS / 'textures/entity/jian_sui.png').convert('RGBA')
    require(image.size == (512, 224), '贴图尺寸不符')
    require(set(image.getchannel('A').tobytes()) <= {0, 255}, '贴图含半透明像素')
    width, height = image.size
    require((geo['description']['texture_width'], geo['description']['texture_height']) == image.size, 'UV 页尺寸不符')
    cubes = [c for b in bones.values() for c in b.get('cubes', [])]
    require(len(cubes) == 865 and len(cubes) < 1000, '方块预算超限')
    faces = 0
    for cube in cubes:
        vector(cube['origin'])
        vector(cube['size'])
        require(min(cube['size']) > 0, '方块尺寸无效')
        if 'rotation' in cube:
            vector(cube['rotation'])
        require(isinstance(cube['uv'], dict), '必须使用每面 UV')
        for face in cube['uv'].values():
            faces += 1
            u, v = face['uv']
            du, dv = face['uv_size']
            require(all(math.isfinite(x) for x in (u, v, du, dv)), 'UV 含非有限数值')
            require(0 <= min(u, u + du) <= max(u, u + du) <= width, 'UV 横向越界')
            require(0 <= min(v, v + dv) <= max(v, v + dv) <= height, 'UV 纵向越界')
    require(faces == 3724 and faces < 4000, '有效面数预算超限')

    animations = load(ASSETS / 'geckolib/animations/entity/jian_sui.animation.json')
    require(animations['format_version'] == '1.8.0', '动画格式不符')
    expected = {'collapse': 2, 'idle': 2, 'attack_1': 1.5, 'attack_2': 1.8, 'attack_3': 1.8}
    require(set(animations['animations']) == set(expected), '动画清单不符')
    events = load(ROOT / 'docs/jian_sui/animation_events.json')
    for name, animation in animations['animations'].items():
        length = animation['animation_length']
        require(length == expected[name], '动画时长不符')
        for bone, channels in animation['bones'].items():
            require(bone in bones, '动画引用了不存在的骨骼')
            for channel, keys in channels.items():
                require(channel in ('rotation', 'position', 'scale'), '未知动画通道')
                if isinstance(keys, list):
                    vector(keys)
                    continue
                ordered = sorted((float(t), value) for t, value in keys.items())
                require(all(0 <= t <= length for t, _ in ordered), '关键帧超出动画时长')
                for _, value in ordered:
                    vector(value)
                if animation.get('loop') is True:
                    require(ordered[0][1] == ordered[-1][1], '循环动画首尾不一致')
        for time in animation.get('timeline', {}):
            require(0 <= float(time) <= length, '事件超出动画时长')
        if name in ('idle', 'collapse') or name.startswith('attack_'):
            require('Weapon' in animation['bones'], '剑没有跟手：动画缺 Weapon 轨道')
        if name == 'collapse':
            require({'Skirt', *(f'WaistBridge{i}' for i in range(8))} <= animation['bones'].keys(), '散架漏了腰部零件')
        if name.startswith('attack_'):
            actual = {time: [s.strip() for s in script.rstrip(';').split(';') if s.strip()] for time, script in animation['timeline'].items()}
            require(actual == events[name]['timeline'], '事件记录与正式动画不一致')
            markers = {marker: float(t) for t, markers in actual.items() for marker in markers}
            require(markers['input_open'] <= markers['contact'] <= markers['cancel'] <= length, '攻击事件顺序不符')
            require(markers['trail_start'] < markers['trail_end'], '刀光窗口顺序不符')
    for path in ('attacks.mp4', 'attack_1.jpg', 'attack_2.jpg', 'attack_3.jpg'):
        require((ROOT / 'docs/jian_sui' / path).is_file(), '缺少审查预览')
    return {'骨骼': len(bones), '方块': len(cubes), '有效四边面': faces, '动画': len(expected), '结论': '资源数据审计通过；未进行游戏实测'}


if __name__ == '__main__':
    print(json.dumps(audit(), ensure_ascii=False))
