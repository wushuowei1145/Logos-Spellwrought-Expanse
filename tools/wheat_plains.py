"""用原版群系、作物与地表拼接生成极稀有的队徽麦田；不注册新物品。"""
from collections import deque
from pathlib import Path
import json
import math
import zipfile
from PIL import Image, ImageEnhance
from blueprint_nbt import write_structure

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'src/main/resources'
DATA = RES / 'data/logos_dimension'
ART = ROOT / 'art/wheat_plains'
BIOME = 'logos_dimension:wheat_plains'
SIZE = 128
RARITY_THRESHOLD = .2


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def state(name, **props):
    return {'Name': 'minecraft:' + name, **({'Properties': {k: str(v) for k, v in props.items()}} if props else {})}


def climate():
    # 只在已压低起伏的温带内陆平原取稀疏大块区域，不再复用蘑菇岛的海洋位置。
    entry = {'biome': BIOME, 'parameters': {'temperature': [.35, .55], 'humidity': [-.6, .2],
             'continentalness': [.05, 1.0], 'erosion': [.45, 1.2], 'weirdness': 2.0, 'depth': 0, 'offset': 0}}
    for path in [DATA / 'dimension/cloudsea.json', ROOT / 'gametest-packs/crucible/data/minecraft/worldgen/world_preset/flat.json']:
        obj = json.loads(path.read_text(encoding='utf-8'))
        generator = obj['generator'] if 'generator' in obj else obj['dimensions']['logos_dimension:cloudsea']['generator']
        points = generator['biome_source']['biomes']
        # 同时移除首版误加的两个海洋保护带，恢复原有海洋群系分布。
        points[:] = [e for e in points if e['biome'] != BIOME and e['parameters']['continentalness'] != [-1.05, -.455]]
        # 地下群系接受地表标记，仍按原来的深度接管洞穴，避免麦田沿整根区块柱侵入地下。
        for existing in points:
            if existing['biome'] == 'logos_dimension:cloudsea_root_caves':
                existing['parameters']['weirdness'] = [-2.0, 2.0]
        points.append(entry)
        write(path, obj)
    path = DATA / 'worldgen/noise_settings/journey.json'
    obj = json.loads(path.read_text(encoding='utf-8'))
    functions = DATA / 'worldgen/density_function/wheat'
    router = obj['noise_router']
    if router['ridges'] != 'logos_dimension:wheat/biome_marker':
        write(functions / 'original_ridges.json', {'type': 'minecraft:add', 'argument1': router['ridges'], 'argument2': 0})
    write(DATA / 'worldgen/noise/wheat/rarity.json', {'firstOctave': -11, 'amplitudes': [1.0]})
    # 群系采样的专用标记，不参与地形密度；常态仍取原来的山脊值，平原高程完全不变。
    original = {'type': 'minecraft:clamp', 'input': {'type': 'minecraft:add', 'argument1': 'logos_dimension:wheat/original_ridges', 'argument2': 0}, 'min': -1.2, 'max': 1.2}
    marker = {'type': 'minecraft:range_choice', 'input': {'type': 'minecraft:noise', 'noise': 'logos_dimension:wheat/rarity', 'xz_scale': 1, 'y_scale': 0},
              'min_inclusive': RARITY_THRESHOLD, 'max_exclusive': 100, 'when_in_range': 2.0, 'when_out_of_range': original}
    for coordinate, low, high in [(router['temperature'], .35, .55), (router['vegetation'], -.6, .2),
                                   (router['continents'], .05, 1.0), (router['erosion'], .45, 1.2)]:
        marker = {'type': 'minecraft:range_choice', 'input': coordinate, 'min_inclusive': low,
                  'max_exclusive': high, 'when_in_range': marker, 'when_out_of_range': original}
    write(functions / 'biome_marker.json', {'type': 'minecraft:cache_2d', 'argument': marker})
    router['ridges'] = 'logos_dimension:wheat/biome_marker'
    rule = {'type': 'minecraft:condition', 'if_true': {'type': 'minecraft:biome', 'biome_is': [BIOME]},
            'then_run': {'type': 'minecraft:sequence', 'sequence': [
                {'type': 'minecraft:condition', 'if_true': {'type': 'minecraft:stone_depth', 'offset': 0,
                    'add_surface_depth': False, 'secondary_depth_range': 0, 'surface_type': 'floor'},
                 'then_run': {'type': 'minecraft:block', 'result_state': state('grass_block', snowy='false')}},
                {'type': 'minecraft:condition', 'if_true': {'type': 'minecraft:stone_depth', 'offset': 4,
                    'add_surface_depth': False, 'secondary_depth_range': 0, 'surface_type': 'floor'},
                 'then_run': {'type': 'minecraft:block', 'result_state': state('dirt')}}]}}
    rules = obj['surface_rule']['sequence']
    if rule not in rules:
        rules.insert(1, rule)
    write(path, obj)


def crops():
    # 原版高度图读取地面而非麦穗；每格平均 16 次候选，地表覆盖率的期望大于 99.999%。
    write(DATA / 'worldgen/configured_feature/wheat_plains_cover.json', {
        'type': 'minecraft:block_column', 'config': {'direction': 'up', 'prioritize_tip': False,
            'allowed_placement': {'type': 'minecraft:matching_blocks', 'blocks': ['minecraft:air']},
            'layers': [{'height': 1, 'provider': {'type': 'minecraft:simple_state_provider', 'state': s}}
                       for s in [state('farmland', moisture=7), state('wheat', age=7)]]}})
    write(DATA / 'worldgen/placed_feature/wheat_plains_cover.json', {
        'feature': 'logos_dimension:wheat_plains_cover', 'placement': [
            {'type': 'minecraft:count', 'count': 4096}, {'type': 'minecraft:in_square'},
            {'type': 'minecraft:heightmap', 'heightmap': 'OCEAN_FLOOR_WG'},
            {'type': 'minecraft:random_offset', 'xz_spread': 0, 'y_spread': -1},
            {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:all_of', 'predicates': [
                {'type': 'minecraft:matching_blocks', 'blocks': ['minecraft:grass_block', 'minecraft:dirt']},
                {'type': 'minecraft:matching_blocks', 'offset': [0, 1, 0], 'blocks': ['minecraft:air']},
                {'type': 'minecraft:matching_blocks', 'offset': [0, 2, 0], 'blocks': ['minecraft:air']}]}},
            {'type': 'minecraft:biome'}]})
    write(DATA / 'worldgen/biome/wheat_plains.json', {
        'has_precipitation': True, 'temperature': .75, 'downfall': .45,
        'effects': {'water_color': '#607d85', 'grass_color': '#b6ad68', 'foliage_color': '#a4a065'},
        'carvers': ['minecraft:cave'], 'features': [[] for _ in range(9)] + [['logos_dimension:wheat_plains_cover'], []],
        'spawners': {}, 'spawn_costs': {}})


def logo_mask():
    """只去除与图像边界相连的灰底；保留白发、耳朵和衣服，按原图明暗转成麦秆三色。"""
    image = Image.open(ART / 'team_logo.png').convert('RGB')
    w, h = image.size
    bg = image.getpixel((0, h - 1))
    removed, queue = set(), deque()
    for x in range(w):
        queue.extend([(x, 0), (x, h - 1)])
    for y in range(h):
        queue.extend([(0, y), (w - 1, y)])
    while queue:
        x, y = queue.popleft()
        if (x, y) in removed or not (0 <= x < w and 0 <= y < h):
            continue
        pixel = image.getpixel((x, y))
        if sum((pixel[i] - bg[i]) ** 2 for i in range(3)) > 38 ** 2:
            continue
        removed.add((x, y))
        queue.extend([(x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)])
    alpha = Image.new('L', image.size, 255)
    for pos in removed:
        alpha.putpixel(pos, 0)
    gray = ImageEnhance.Contrast(image.convert('L')).enhance(1.25).resize((100, 106), Image.Resampling.LANCZOS)
    alpha = alpha.resize(gray.size, Image.Resampling.LANCZOS)
    mask = Image.new('L', (SIZE, SIZE))
    for z in range(SIZE):
        for x in range(SIZE):
            radius = math.hypot(x - 63.5, z - 63.5)
            if 59.5 <= radius <= 62.5:
                mask.putpixel((x, z), 1 if radius > 60.5 else 3)
            elif 55.8 <= radius <= 56.8:
                mask.putpixel((x, z), 1)
    for y in range(gray.height):
        for x in range(gray.width):
            if alpha.getpixel((x, y)) >= 128:
                value = gray.getpixel((x, y))
                edge = any(not (0 <= x + dx < gray.width and 0 <= y + dy < gray.height)
                           or alpha.getpixel((x + dx, y + dy)) < 128 for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)])
                mask.putpixel((x + 14, y + 10), 1 if edge or value < 108 else 2 if value < 182 else 3)
    return mask


def circle():
    mask = logo_mask()
    palette = {1: state('mud'), 2: state('dirt_path'), 3: state('hay_block', axis='y')}
    cells = {(x, 0, z): palette[mask.getpixel((x, z))] for z in range(SIZE) for x in range(SIZE) if mask.getpixel((x, z))}
    anchor = (64, 0, 64)
    final = palette.get(mask.getpixel((64, 64)), state('hay_block', axis='y'))
    cells[anchor] = state('jigsaw', orientation='up_north')
    final_string = final['Name'] + ('[' + ','.join(f'{k}={v}' for k, v in final.get('Properties', {}).items()) + ']' if final.get('Properties') else '')
    nbt = {anchor: {'id': 'minecraft:jigsaw', 'name': 'logos_dimension:crop_circle_center',
                   'target': 'minecraft:empty', 'pool': 'minecraft:empty', 'joint': 'rollable',
                   'final_state': final_string, 'selection_priority': 0, 'placement_priority': 0}}
    with zipfile.ZipFile(ROOT / 'build/moddev/artifacts/minecraft-patched-26.1.2.112-sources.jar') as archive:
        version = json.loads(archive.read('version.json'))['world_version']
    write_structure(DATA / 'structure/wheat/team_crop_circle.nbt', [SIZE, 1, SIZE], cells, nbt, data_version=version)
    # 用地表高度而非海底高度；保护水、岩石、别的群系草皮，避免图案跨岸后悬浮或铺进海中。
    protected = ['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:water', 'minecraft:lava',
                 '#minecraft:base_stone_overworld', '#minecraft:sand', 'minecraft:gravel', 'minecraft:bedrock',
                 'minecraft:snow_block', 'minecraft:ice', 'minecraft:packed_ice', 'minecraft:blue_ice',
                 'logos_dimension:journey_limestone']
    protected += ['logos_dimension:' + p.stem.removesuffix('_ground') + '_ground'
                  for p in (RES / 'assets/logos_dimension/blockstates').glob('cloudsea_*_ground.json')]
    write(DATA / 'tags/block/crop_circle_protected.json', {'replace': False, 'values': protected})
    write(DATA / 'worldgen/processor_list/crop_circle_ground.json', {'processors': [
        {'processor_type': 'minecraft:gravity', 'heightmap': 'WORLD_SURFACE_WG', 'offset': -1},
        {'processor_type': 'minecraft:protected_blocks', 'value': '#logos_dimension:crop_circle_protected'}]})
    write(DATA / 'worldgen/template_pool/wheat/crop_circle.json', {
        'name': 'logos_dimension:wheat/crop_circle', 'fallback': 'minecraft:empty', 'elements': [{'weight': 1, 'element': {
            'element_type': 'minecraft:single_pool_element', 'location': 'logos_dimension:wheat/team_crop_circle',
            'processors': 'logos_dimension:crop_circle_ground', 'projection': 'rigid'}}]})
    write(DATA / 'worldgen/structure/wheat_crop_circle.json', {
        'type': 'minecraft:jigsaw', 'biomes': [BIOME], 'step': 'surface_structures', 'spawn_overrides': {},
        'terrain_adaptation': 'none', 'start_pool': 'logos_dimension:wheat/crop_circle',
        'start_jigsaw_name': 'logos_dimension:crop_circle_center', 'size': 1,
        'start_height': {'absolute': -1}, 'project_start_to_heightmap': 'WORLD_SURFACE_WG',
        'max_distance_from_center': 128, 'use_expansion_hack': False})
    write(DATA / 'worldgen/structure_set/wheat_crop_circles.json', {
        'structures': [{'structure': 'logos_dimension:wheat_crop_circle', 'weight': 1}],
        'placement': {'type': 'minecraft:random_spread', 'spacing': 12, 'separation': 10,
                      'salt': 72311917, 'spread_type': 'linear'}})
    write(DATA / 'tags/worldgen/structure/wheat_crop_circles.json', {'values': ['logos_dimension:wheat_crop_circle']})
    colors = ['#d4c382', '#423c40', '#aa8b52', '#d4b94d']
    preview = Image.new('RGB', mask.size)
    for z in range(SIZE):
        for x in range(SIZE):
            preview.putpixel((x, z), tuple(bytes.fromhex(colors[mask.getpixel((x, z))][1:])))
    preview.resize((1024, 1024), Image.Resampling.NEAREST).save(ART / 'crop_circle_plan.png')
    write(DATA / 'wheat_plains_manifest.json', {'biome': BIOME, 'size': SIZE,
          'logo_reference': 'art/wheat_plains/team_logo.png', 'materials': list(palette.values()),
          'pattern_blocks': len(cells), 'full_wheat_attempts_per_chunk': 4096,
          'placement': 'rare_inland_temperate_plains', 'rarity_noise_threshold': RARITY_THRESHOLD, 'new_registered_items': 0})


def advancement():
    write(DATA / 'advancement/adventure/crop_circle.json', {
        'parent': 'minecraft:adventure/root',
        'display': {'icon': {'id': 'minecraft:wheat'}, 'frame': 'challenge', 'show_toast': True,
                    'announce_to_chat': True, 'hidden': True,
                    'title': {'translate': 'advancements.logos_dimension.crop_circle.title'},
                    'description': {'translate': 'advancements.logos_dimension.crop_circle.description'}},
        'criteria': {'first_encounter': {'trigger': 'minecraft:location', 'conditions': {'player': [{
            'condition': 'minecraft:entity_properties', 'entity': 'this',
            'predicate': {'location': {'dimension': 'logos_dimension:cloudsea', 'biomes': BIOME}}}]}}},
        'requirements': [['first_encounter']]})
    for lang, title, description, biome in [
        ('zh_cn', '麦田怪圈', '风吹过麦穗，未解之事也有了形状。', '麦田平原'),
        ('en_us', 'Crop Circles', 'As wind passes through wheat, even the unknown takes shape.', 'Wheat Plains')]:
        path = RES / f'assets/logos_dimension/lang/{lang}.json'
        text = path.read_text(encoding='utf-8')
        entries = {f'advancements.logos_dimension.crop_circle.title': title,
                   f'advancements.logos_dimension.crop_circle.description': description,
                   'biome.logos_dimension.wheat_plains': biome}
        existing = json.loads(text)
        missing = {k: v for k, v in entries.items() if existing.get(k) != v}
        if missing:
            # 保留原有语言表的排序和其他分支追加内容。
            suffix = json.dumps(missing, ensure_ascii=False, indent=2)[1:-1].strip()
            path.write_text(text.rstrip().removesuffix('}').rstrip() + ',\n  ' + suffix + '\n}\n', encoding='utf-8', newline='\n')


def main():
    climate()
    crops()
    circle()
    advancement()


if __name__ == '__main__':
    main()
