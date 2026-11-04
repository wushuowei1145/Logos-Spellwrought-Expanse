"""远行大陆水下景观的 worldgen 数据：海草、海带、萤光海草、海底小石堆。

python tools/underwater_worldgen.py

只写本功能自己的 configured/placed feature，并把它们加入指定水群系的植被阶段；
不改动其它群系或既有特征。
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
D = ROOT / 'src/main/resources/data/logos_dimension'
OURS = ['journey_seagrass', 'journey_glow_seagrass', 'journey_kelp', 'underwater_rock_pile']


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def state(name, **props):
    return {'Name': name, **({'Properties': {k: str(v).lower() for k, v in props.items()}} if props else {})}


# 每个水群系放什么：珊瑚留给后续批次，这里只有海草类与海底石堆。
MARINE = {
    'cloudsea_glass_lagoons': ['journey_seagrass', 'journey_glow_seagrass', 'underwater_rock_pile'],
    'cloudsea_pearl_basins': ['journey_seagrass', 'journey_kelp', 'underwater_rock_pile'],
    'cloudsea_tide_ribbons': ['journey_seagrass', 'journey_kelp', 'underwater_rock_pile'],
    'cloudsea_opal_dunes': ['journey_seagrass', 'underwater_rock_pile'],
    'cloudsea_dream_basins': ['journey_seagrass', 'journey_glow_seagrass', 'underwater_rock_pile'],
    # 海面以下的海底属于 depth 群系 root_caves，水下植物要接在这里才会长在海底
    'cloudsea_root_caves': ['journey_seagrass', 'journey_glow_seagrass', 'journey_kelp', 'underwater_rock_pile'],
}


def configured():
    write(D / 'worldgen/configured_feature/journey_seagrass.json',
          {'type': 'logos_dimension:journey_seagrass', 'config': {'probability': 0.6}})
    write(D / 'worldgen/configured_feature/journey_glow_seagrass.json',
          {'type': 'logos_dimension:journey_glow_seagrass', 'config': {'probability': 0.0}})
    write(D / 'worldgen/configured_feature/journey_kelp.json',
          {'type': 'logos_dimension:journey_kelp', 'config': {}})
    write(D / 'worldgen/configured_feature/underwater_rock_pile.json', {
        'type': 'logos_dimension:journey_rock_pile',
        'config': {'state_provider': {'type': 'minecraft:weighted_state_provider', 'entries': [
            {'data': state('logos_dimension:journey_limestone'), 'weight': 3},
            {'data': state('minecraft:mossy_cobblestone'), 'weight': 1},
        ]}},
    })


def placed():
    floor = [{'type': 'minecraft:in_square'},
             {'type': 'minecraft:heightmap', 'heightmap': 'OCEAN_FLOOR_WG'}]

    def simple(name, feature, count):
        write(D / 'worldgen/placed_feature' / f'{name}.json', {
            'feature': 'logos_dimension:' + feature,
            'placement': floor[:1] + floor[1:] + [
                {'type': 'minecraft:count', 'count': count},
                {'type': 'minecraft:biome'},
            ],
        })

    simple('journey_seagrass', 'journey_seagrass', 40)
    simple('journey_glow_seagrass', 'journey_glow_seagrass', 16)

    write(D / 'worldgen/placed_feature/journey_kelp.json', {
        'feature': 'logos_dimension:journey_kelp',
        'placement': [
            {'type': 'minecraft:noise_based_count', 'noise_factor': 80.0,
             'noise_offset': 0.0, 'noise_to_count_ratio': 80},
            {'type': 'minecraft:in_square'},
            {'type': 'minecraft:heightmap', 'heightmap': 'OCEAN_FLOOR_WG'},
            {'type': 'minecraft:biome'},
        ],
    })
    write(D / 'worldgen/placed_feature/underwater_rock_pile.json', {
        'feature': 'logos_dimension:underwater_rock_pile',
        'placement': [
            {'type': 'minecraft:rarity_filter', 'chance': 6},
            {'type': 'minecraft:in_square'},
            {'type': 'minecraft:heightmap', 'heightmap': 'OCEAN_FLOOR_WG'},
            {'type': 'minecraft:biome'},
        ],
    })


def wire_biomes():
    for biome, ids in MARINE.items():
        path = D / 'worldgen/biome' / f'{biome}.json'
        data = json.loads(path.read_text(encoding='utf-8'))
        features = data.get('features') or [[] for _ in range(11)]
        while len(features) < 11:
            features.append([])
        entries = ['logos_dimension:' + i for i in OURS]
        for stage in features:
            for entry in list(stage):
                if entry in entries and entry not in ['logos_dimension:' + i for i in ids]:
                    stage.remove(entry)
        for i in ids:
            ref = 'logos_dimension:' + i
            if ref not in features[9]:
                features[9].append(ref)
        data['features'] = features
        write(path, data)


def underwater_surface_rule():
    """水下的地表换成沙/砾，照原版主世界的写法；海面以下的 root_caves 没有自己的地表规则，
    否则海底会露出裸石头。插在基岩规则之后、群系规则之前，只影响水下的面。"""
    path = D / 'worldgen/noise_settings/journey.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    rule = {
        'type': 'minecraft:condition',
        # 原版 water 条件为真表示「在水面之上」；取反才是水下
        'if_true': {'type': 'minecraft:not', 'invert': {
            'type': 'minecraft:water', 'add_stone_depth': False, 'offset': 0,
            'surface_depth_multiplier': 0}},
        'then_run': {'type': 'minecraft:sequence', 'sequence': [
            {'type': 'minecraft:condition',
             'if_true': {'type': 'minecraft:stone_depth', 'add_surface_depth': False, 'offset': 0,
                         'secondary_depth_range': 0, 'surface_type': 'floor'},
             'then_run': {'type': 'minecraft:block', 'result_state': state('minecraft:sand')}},
            {'type': 'minecraft:condition',
             'if_true': {'type': 'minecraft:stone_depth', 'add_surface_depth': False, 'offset': 4,
                         'secondary_depth_range': 0, 'surface_type': 'floor'},
             'then_run': {'type': 'minecraft:block', 'result_state': state('minecraft:gravel')}},
        ]},
    }
    seq = data['surface_rule']['sequence']
    if seq and seq[1] == rule:
        return
    seq.insert(1, rule)
    write(path, data)


def main():
    configured()
    placed()
    wire_biomes()
    underwater_surface_rule()
    print('underwater worldgen written:', ', '.join(MARINE))


if __name__ == '__main__':
    main()
