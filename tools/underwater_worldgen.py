"""远行大陆水下景观的 worldgen 数据：海床、海草、海带、萤光海草、海底小石堆。

python tools/underwater_worldgen.py

只写本功能自己的 configured/placed feature，把它们与原版海床圆盘接进指定水群系，
并在地表规则里补一条照原版写的水下海床规则；不改动其它群系或既有特征。幂等。
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
D = ROOT / 'src/main/resources/data/logos_dimension'


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def state(name, **props):
    return {'Name': name, **({'Properties': {k: str(v).lower() for k, v in props.items()}} if props else {})}


# 照原版海洋/河流的群系特征表：海草、海带的数量与高海草比例沿用原版，换成本附属的方块。
#   海湾与浅滩（pearl/tide，大陆度 -0.6，沙底）≈ 原版暖海：seagrass_warm + kelp_warm
#   泻湖、沙丘水域、内陆盆地 ≈ 原版河流：seagrass_river
#   海面以下的深海底属于 depth 群系 root_caves ≈ 原版深海：seagrass_deep + kelp_cold
# 萤光海草与海底小石堆是本附属的特色；沙/黏土/砾石海床圆盘直接引用原版的 placed feature。
VEGETATION = {
    'cloudsea_pearl_basins': ['journey_seagrass_warm', 'journey_kelp_warm'],
    'cloudsea_tide_ribbons': ['journey_seagrass_warm', 'journey_kelp_warm'],
    'cloudsea_glass_lagoons': ['journey_seagrass_river', 'journey_glow_seagrass'],
    'cloudsea_opal_dunes': ['journey_seagrass_river'],
    'cloudsea_dream_basins': ['journey_seagrass_river', 'journey_glow_seagrass'],
    'cloudsea_root_caves': ['journey_seagrass_deep', 'journey_glow_seagrass', 'journey_kelp_cold'],
}
DISKS = ['minecraft:disk_sand', 'minecraft:disk_clay', 'minecraft:disk_gravel']
ROCK_PILE = 'logos_dimension:underwater_rock_pile'
# 植被一律按这个顺序排，保证各群系之间同一阶段的特征顺序一致
ORDER = ['journey_seagrass_warm', 'journey_seagrass_river', 'journey_seagrass_deep',
         'journey_glow_seagrass', 'journey_kelp_warm', 'journey_kelp_cold']
# 第一版写过、现在不再使用的 feature：configured 的 journey_kelp 仍在用，只退役它的 placed
RETIRED_CONFIGURED = ['journey_seagrass']
RETIRED_PLACED = ['journey_seagrass', 'journey_kelp']

# 原版生成阶段：石堆与原版 forest_rock 同在 local_modifications，圆盘在 underground_ores，植被在 vegetal_decoration
LOCAL_MODIFICATIONS, UNDERGROUND_ORES, VEGETAL_DECORATION = 4, 6, 9
FLOOR = [{'type': 'minecraft:in_square'},
         {'type': 'minecraft:heightmap', 'heightmap': 'OCEAN_FLOOR_WG'}]


def configured():
    # 原版 seagrass_short / slightly_less_short / tall：probability 是长成高海草的比例
    for name, probability in [('journey_seagrass_short', 0.3),
                              ('journey_seagrass_slightly_less_short', 0.4),
                              ('journey_seagrass_tall', 0.8)]:
        write(D / 'worldgen/configured_feature' / f'{name}.json',
              {'type': 'logos_dimension:journey_seagrass', 'config': {'probability': probability}})
    # 萤光海草只有单格，用原版 simple_block 放（它会先检查能否存活）
    write(D / 'worldgen/configured_feature/journey_glow_seagrass.json', {
        'type': 'minecraft:simple_block',
        'config': {'to_place': {'type': 'minecraft:simple_state_provider',
                                'state': state('logos_dimension:journey_glow_seagrass')}},
    })
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
    def seagrass(name, feature, count):
        # 原版 seagrass_*：in_square → OCEAN_FLOOR_WG → count → biome
        write(D / 'worldgen/placed_feature' / f'{name}.json', {
            'feature': 'logos_dimension:' + feature,
            'placement': FLOOR + [{'type': 'minecraft:count', 'count': count}, {'type': 'minecraft:biome'}],
        })

    seagrass('journey_seagrass_warm', 'journey_seagrass_short', 80)
    seagrass('journey_seagrass_river', 'journey_seagrass_slightly_less_short', 48)
    seagrass('journey_seagrass_deep', 'journey_seagrass_tall', 48)

    write(D / 'worldgen/placed_feature/journey_glow_seagrass.json', {
        'feature': 'logos_dimension:journey_glow_seagrass',
        'placement': [{'type': 'minecraft:count', 'count': 16}] + FLOOR + [
            {'type': 'minecraft:block_predicate_filter',
             'predicate': {'type': 'minecraft:matching_blocks', 'blocks': 'minecraft:water'}},
            {'type': 'minecraft:biome'}],
    })

    def kelp(name, ratio):
        # 原版 kelp_cold（120）/ kelp_warm（80）
        write(D / 'worldgen/placed_feature' / f'{name}.json', {
            'feature': 'logos_dimension:journey_kelp',
            'placement': [{'type': 'minecraft:noise_based_count', 'noise_factor': 80.0,
                           'noise_offset': 0.0, 'noise_to_count_ratio': ratio}] + FLOOR + [
                {'type': 'minecraft:biome'}],
        })

    kelp('journey_kelp_warm', 80)
    kelp('journey_kelp_cold', 120)

    # 原版圆盘同款的水格过滤：原点不在水里（陆地）就直接跳过
    write(D / 'worldgen/placed_feature/underwater_rock_pile.json', {
        'feature': ROCK_PILE,
        'placement': [{'type': 'minecraft:rarity_filter', 'chance': 6}] + FLOOR + [
            {'type': 'minecraft:block_predicate_filter',
             'predicate': {'type': 'minecraft:matching_fluids', 'fluids': 'minecraft:water'}},
            {'type': 'minecraft:biome'}],
    })

    for name in RETIRED_CONFIGURED:
        (D / 'worldgen/configured_feature' / f'{name}.json').unlink(missing_ok=True)
    for name in RETIRED_PLACED:
        (D / 'worldgen/placed_feature' / f'{name}.json').unlink(missing_ok=True)


def wire_biomes():
    ours = {'logos_dimension:' + i for i in ORDER + RETIRED_PLACED} | {ROCK_PILE} | set(DISKS)
    for path in sorted((D / 'worldgen/biome').glob('*.json')):
        biome = path.stem
        data = json.loads(path.read_text(encoding='utf-8'))
        features = data.get('features') or []
        while len(features) < 11:
            features.append([])
        before = json.dumps(features)
        for stage in features:
            stage[:] = [entry for entry in stage if entry not in ours]
        if biome in VEGETATION:
            features[LOCAL_MODIFICATIONS].append(ROCK_PILE)
            features[UNDERGROUND_ORES].extend(DISKS)
            features[VEGETAL_DECORATION].extend(
                'logos_dimension:' + i for i in ORDER if i in VEGETATION[biome])
        if json.dumps(features) != before:
            data['features'] = features
            write(path, data)


def water(offset, multiplier=0, stone=False):
    return {'type': 'minecraft:water', 'offset': offset, 'surface_depth_multiplier': multiplier,
            'add_stone_depth': stone}


def depth(offset=0, add_surface=False, kind='floor'):
    return {'type': 'minecraft:stone_depth', 'offset': offset, 'add_surface_depth': add_surface,
            'secondary_depth_range': 0, 'surface_type': kind}


def when(condition, then):
    return {'type': 'minecraft:condition', 'if_true': condition, 'then_run': then}


def block(name):
    return {'type': 'minecraft:block', 'result_state': state(name)}


def seq(*rules):
    return {'type': 'minecraft:sequence', 'sequence': list(rules)}


SANDY = ['logos_dimension:cloudsea_pearl_basins', 'logos_dimension:cloudsea_tide_ribbons']


def underwater_rule():
    """水下海床，照原版主世界 overworld 的地表规则：
    - 沙底群系（海湾与浅滩）照原版暖海/沙滩：水下一律是沙，悬空的顶面是砂岩；
    - 其他群系照原版：离水面不远（water -6/-1）的浅水底是泥土，更深的海床表层是砾石，悬空处是石头；
    - 原版的沙/黏土/砾石海床圆盘只认泥土，所以浅水的泥土上会出现沙、黏土、砾石斑块，深水是砾石。
    只作用于预备地表以上、被水淹没的地面，不碰干地和地下的含水洞穴；水面以上仍由各群系自己的规则决定。"""
    submerged = {'type': 'minecraft:not', 'invert': water(0)}
    shallow = water(-6, -1, True)
    return when({'type': 'minecraft:above_preliminary_surface'}, when(submerged, seq(
        when({'type': 'minecraft:biome', 'biome_is': SANDY}, when(depth(add_surface=True), seq(
            when(depth(kind='ceiling'), block('minecraft:sandstone')),
            block('minecraft:sand')))),
        when(depth(), seq(
            when(shallow, block('minecraft:dirt')),
            when(depth(kind='ceiling'), block('minecraft:stone')),
            block('minecraft:gravel'))),
        when(shallow, when(depth(add_surface=True), block('minecraft:dirt'))),
    )))


# 本功能第一版插过的规则：整块大陆水下一律沙/砾。重新生成时换掉它。
OLD_RULE = when({'type': 'minecraft:not', 'invert': {
    'type': 'minecraft:water', 'add_stone_depth': False, 'offset': 0, 'surface_depth_multiplier': 0}},
    seq(when(depth(), block('minecraft:sand')), when(depth(offset=4), block('minecraft:gravel'))))


def underwater_surface_rule():
    """插在基岩规则之后、各群系规则之前：群系规则不分水上水下，排在它们后面就会把海床铺成草皮。"""
    path = D / 'worldgen/noise_settings/journey.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    rule = underwater_rule()
    sequence = data['surface_rule']['sequence']

    def canon(r):
        return json.dumps(r, sort_keys=True)

    sequence[:] = [r for r in sequence if canon(r) not in (canon(rule), canon(OLD_RULE))]
    sequence.insert(1, rule)
    write(path, data)


def main():
    configured()
    placed()
    wire_biomes()
    underwater_surface_rule()
    print('underwater worldgen written:', ', '.join(VEGETATION))


if __name__ == '__main__':
    main()
