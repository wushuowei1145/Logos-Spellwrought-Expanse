"""将维护者最新的素体与十五个外观部件局部同步进游戏资源。

python tools/forest_elf_appearance.py

使用现有图集分区，保留全部运行时动画、动画表情、共用骨架与搭配规则。
只读取仓库外的当前 .bbmodel，不重跑旧制作脚本，不修改源文件。
"""
import base64
import copy
import io
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

from forest_elf_body import export_cube

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT.parent / 'forest-elf-model/forest_elf'
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
MODEL = ASSETS / 'geckolib/models/entity/forest_elf.geo.json'
TEXTURE = ASSETS / 'textures/entity/forest_elf.png'
ANIMATION = ASSETS / 'geckolib/animations/entity/forest_elf.animation.json'
PARTS = ROOT / 'src/main/resources/data/logos_dimension/npc_races/forest_elf.json'
# 现有分区；若源贴图布局改变，应先核对图集，而不是覆盖挂泪表情所在的空白区。
UV_ORIGINS = {
    'body': (96, 200),
    'hair_high_ponytail': (40, 200), 'hair_hooded': (48, 160),
    'hair_short_bob': (0, 136), 'hair_straight_long': (0, 88),
    'headwear_twig_crown': (112, 200), 'ear_long': (112, 208),
    'eyes_droopy': (64, 0), 'eyes_square': (0, 0),
    'mouth_cat': (16, 216), 'mouth_smile': (0, 216),
    'outfit_casual': (72, 200), 'outfit_gardener': (88, 160),
    'outfit_ranger': (64, 56), 'outfit_scholar': (0, 176),
    'outfit_villager': (48, 112),
}


def face_region(face):
    """普通区域和单色纹素都转成实际采样的整数像素区域。"""
    (u, v), (w, h) = face['uv'], face['uv_size']
    x0, x1 = sorted((u, u + w)); y0, y1 = sorted((v, v + h))
    return (math.floor(x0), math.floor(y0),
            max(math.floor(x0) + 1, math.ceil(x1)),
            max(math.floor(y0) + 1, math.ceil(y1)))


def read_source(path, pid, game):
    """从大纲读取部件子树，忽略锁定素体参照；共用骨架只从素体读取。"""
    data = json.loads(path.read_text(encoding='utf-8'))
    assert data['meta']['model_format'] == 'bedrock' and len(data['textures']) == 1
    definitions = {g['uuid']: g for g in data['groups']}
    assert len(definitions) == len(data['groups'])
    assert len({g['name'] for g in data['groups']}) == len(definitions)
    elements = {e['uuid']: e for e in data['elements']}
    assert len(elements) == len(data['elements'])
    assert len({e['name'] for e in data['elements']}) == len(elements)
    image = np.asarray(Image.open(io.BytesIO(base64.b64decode(
        data['textures'][0]['source'].split(',', 1)[1]))).convert('RGBA'))
    h, w = image.shape[:2]
    assert data['resolution'] == {'width': w, 'height': h}
    assert set(np.unique(image[:, :, 3])) <= {0, 255}
    used = np.zeros((h, w), bool)
    bones, tops, element_ids = {}, [], set()

    def visit(node, parent=None, included=False):
        if isinstance(node, str):
            if not included:
                assert elements[node].get('locked'), (pid, '部件方块不在自身子树', elements[node]['name'])
                return
            e = elements[node]
            assert parent is not None and e.get('type', 'cube') == 'cube'
            assert not e.get('locked')
            assert all(math.isfinite(n) for k in ['from', 'to', 'origin'] for n in e[k])
            assert all(e['to'][i] >= e['from'][i] for i in range(3))
            if not e.get('export', True):
                return
            for face in e['faces'].values():
                assert face.get('texture') in (None, 0)
            cube = export_cube(e, (0, 0))
            for face in cube['uv'].values():
                x0, y0, x1, y1 = face_region(face)
                assert 0 <= x0 < x1 <= w and 0 <= y0 < y1 <= h, (pid, e['name'], face)
                used[y0:y1, x0:x1] = True
            bones[parent].setdefault('cubes', []).append(export_cube(e, UV_ORIGINS[pid]))
            element_ids.add(node)
            return
        g = definitions[node['uuid']]
        name = g['name']
        own_root = name == pid or name.startswith(pid + '_')
        chosen = pid == 'body' or included or own_root
        if chosen:
            assert name in game, ('需要先登记新骨骼', name)
            assert game[name].get('parent') == parent, ('骨骼重新挂接需单独核对', name)
            origin = [round(float(v), 5) + 0.0 for v in g['origin']]
            rotation = [round(float(v), 5) + 0.0 for v in (g.get('rotation') or [0, 0, 0])]
            assert set(game[name]) <= {'name', 'parent', 'pivot', 'rotation', 'cubes'}, ('未识别的运行时骨骼字段', name)
            bone = {'name': name, 'pivot': [-origin[0], origin[1], origin[2]]}
            if parent:
                bone['parent'] = parent
            if any(rotation):
                bone['rotation'] = [-rotation[0], -rotation[1], rotation[2]]
            if pid == 'body':
                for key in ['parent', 'pivot', 'rotation']:
                    assert bone.get(key) == game[name].get(key), ('共用骨架变动需先同步动画', name)
            bones[name] = bone
            if not included and pid != 'body':
                tops.append(name)
        for child in node.get('children', []):
            visit(child, name, chosen)

    for node in data['outliner']:
        visit(node)
    assert len(element_ids) == sum(e.get('export', True) and not e.get('locked') for e in elements.values())
    return {'bones': bones, 'tops': tops, 'image': image, 'used': used,
            'count': len(element_ids), 'file': str(path.relative_to(SOURCE))}


def main():
    geo = json.loads(MODEL.read_text(encoding='utf-8'))
    model = geo['minecraft:geometry'][0]
    game = {b['name']: b for b in model['bones']}
    assert len(game) == len(model['bones'])
    parts = json.loads(PARTS.read_text(encoding='utf-8'))
    before_parts = copy.deepcopy(parts)
    before_model = copy.deepcopy(model)
    before_animation = ANIMATION.read_bytes()
    config = json.loads((SOURCE / 'race.json').read_text(encoding='utf-8'))
    assert config['conflicts'] == parts['conflicts'] and config.get('requires', {}) == parts.get('requires', {})
    sources = {'body': read_source(SOURCE / config['body'], 'body', game)}
    for slot in config['slots']:
        table = parts['slots'][slot['name']]
        assert table['optional'] == slot['optional']
        assert next(p['id'] for p in table['parts'] if p['default']) == slot['default']
        assert {p.stem for p in (SOURCE / slot['name']).glob('*.bbmodel')} == {p['id'] for p in table['parts']}
        for entry in table['parts']:
            pid = entry['id']
            src = read_source(SOURCE / slot['name'] / (pid + '.bbmodel'), pid, game)
            assert src['tops'] == entry['groups'], ('零件顶层登记不一致', pid)
            sources[pid] = src
            entry['cubes'] = src['count']
    parts['body_cubes'] = sources['body']['count']
    selected = {name for s in sources.values() for name in s['bones']}
    assert len(selected) == sum(len(s['bones']) for s in sources.values()), '重复导出骨骼'
    atlas = np.asarray(Image.open(TEXTURE).convert('RGBA')).copy()
    old_atlas = atlas.copy()
    protected = np.zeros(atlas.shape[:2], bool)
    for name, bone in game.items():
        if name in selected:
            continue
        for cube in bone.get('cubes', []):
            for face in cube['uv'].values():
                x0, y0, x1, y1 = face_region(face)
                protected[y0:y1, x0:x1] = True
    occupied = np.zeros_like(protected)
    report = {'parts': {}, 'changed_bones': [], 'animation_count': len(json.loads(before_animation)['animations'])}
    for pid, src in sources.items():
        x, y = UV_ORIGINS[pid]; h, w = src['image'].shape[:2]
        assert x + w <= atlas.shape[1] and y + h <= atlas.shape[0], (pid, '源贴图超出现有图集')
        used = src['used']
        assert not (occupied[y:y+h, x:x+w] & used).any(), (pid, '部件 UV 分区冲突')
        assert not (protected[y:y+h, x:x+w] & used).any(), (pid, '新 UV 占用了动画表情')
        occupied[y:y+h, x:x+w] |= used
        # 只写当前模型实际采样的像素，保留空白分区中的表情图块。
        atlas[y:y+h, x:x+w][used] = src['image'][used]
        for name, bone in src['bones'].items():
            old = game[name]
            if old != bone:
                report['changed_bones'].append(name)
            old.clear(); old.update(bone)
        report['parts'][pid] = {'source': src['file'], 'cubes': src['count'], 'uv_origin': [x, y]}
    for old, new in zip(before_model['bones'], model['bones']):
        if old['name'] not in selected:
            assert old == new
    assert np.array_equal(old_atlas[protected], atlas[protected]), '动画表情像素被覆盖'
    assert np.array_equal(old_atlas[~occupied], atlas[~occupied]), '写入了部件范围以外的贴图'
    assert ANIMATION.read_bytes() == before_animation
    invariant = lambda p: {k: v for k, v in p.items() if k not in ['body_cubes', 'slots']}
    assert invariant(parts) == invariant(before_parts)
    for slot, table in parts['slots'].items():
        assert table['optional'] == before_parts['slots'][slot]['optional']
        for old, new in zip(before_parts['slots'][slot]['parts'], table['parts']):
            assert {k:v for k,v in old.items() if k != 'cubes'} == {k:v for k,v in new.items() if k != 'cubes'}
    MODEL.write_text(json.dumps(geo, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    Image.fromarray(atlas).save(TEXTURE, optimize=True)
    PARTS.write_text(json.dumps(parts, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    report.update(bones=len(model['bones']), cubes=sum(len(b.get('cubes', [])) for b in model['bones']),
                  animations_preserved=True, expressions_preserved=True, rules_preserved=True)
    report_path = ROOT / 'build/forest-elf-appearance-sync.json'
    report_path.parent.mkdir(exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
