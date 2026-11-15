"""从当前精灵森林成品派生腐败群系；保留原版噪声与拼接，不覆盖原始模型。"""
import copy
import json
from pathlib import Path
from blueprint_nbt import read_nbt, write_structure

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'src/main/resources/data/logos_dimension'
NS = 'logos_dimension:'
BIOME = NS + 'blighted_elven_forest'
PALETTE_PATH = DATA / 'blight_restoration.json'
PALETTE = json.loads(PALETTE_PATH.read_text(encoding='utf-8')) if PALETTE_PATH.exists() else [
    {'Name': NS + 'cloudsea_moss_vale_ground'}, {'Name': 'minecraft:dirt'}]


def memory(original, appearance=0):
    """稳定追加健康状态及其旋转闭包；已发布编号不重排。"""
    def add(state):
        if state not in PALETTE:
            PALETTE.append(copy.deepcopy(state))
        return PALETTE.index(state)
    index = add(original)
    props = original.get('Properties', {})
    if 'axis' in props:
        for axis in ('x', 'y', 'z'):
            state = copy.deepcopy(original)
            state['Properties']['axis'] = axis
            add(state)
    if 'facing' in props:
        for facing in ('north', 'east', 'south', 'west'):
            state = copy.deepcopy(original)
            state['Properties']['facing'] = facing
            add(state)
    if all(face in props for face in ('north', 'east', 'south', 'west')):
        for mask in range(16):
            state = copy.deepcopy(original)
            for bit, face in enumerate(('north', 'east', 'south', 'west')):
                state['Properties'][face] = str(bool(mask & (1 << bit))).lower()
            add(state)
    assert len(PALETTE) <= 256
    return {'Name': NS + 'blight_memory', 'Properties': {'memory': str(index), 'appearance': str(appearance)}}


FLESH = memory(PALETTE[0])
VEIN = NS + 'corruption_vein'
FACES = ((1, 0, 0, 'west'), (-1, 0, 0, 'east'), (0, 1, 0, 'down'), (0, -1, 0, 'up'), (0, 0, 1, 'north'), (0, 0, -1, 'south'))
HEART = (0, 9, 0)


def protected_hall(x, y, z):
    """保护树心净空、内侧壁和楼层；树冠及外露树皮不在这个范围。"""
    if not -4 <= y < 96:
        return False
    radius = 16 if y < 82 else 16 - (y - 82) * .22
    return x * x + z * z <= radius * radius


def cell_hash(x, y, z):
    value = (x * 374761393 + y * 668265263 + z * 2147483647) & 0xffffffff
    value = ((value ^ (value >> 13)) * 1274126177) & 0xffffffff
    return ((value ^ (value >> 16)) & 0xffffff) / 0xffffff


def infestation(x, y, z):
    """大块侵蚀叠加较小的分叉，按整棵树坐标采样，拼接边缘不会重新随机。"""
    return .75 * cell_hash(x // 9, y // 11, z // 9) + .25 * cell_hash((x + 3) // 4, (y + 5) // 7, (z - 2) // 4)


def encrust(state, at):
    name = state['Name']
    threshold = .22 if name == NS + 'blighted_heartwood' else .30 if name == NS + 'blighted_canopy' else .35
    if name in (NS + 'blighted_loam', NS + 'blighted_lumen'):
        return FLESH
    if name in (NS + 'blighted_heartwood', NS + 'blighted_canopy', NS + 'elven_pale_planks') or any(name.endswith(suffix) for suffix in ('_planks', '_stairs', '_slab', '_fence')):
        if infestation(*at) >= threshold:
            return FLESH
    return state


def coat_surfaces(cells, size, offset, world_tree):
    """把原有脉络贴到残留树皮和叶片的外露面，不侵占树心或堵住拼接口。"""
    supports = [p for p, state in cells.items() if state['Name'] in (NS + 'blighted_heartwood', NS + 'blighted_canopy') or state['Name'] == NS + 'blight_memory' and state.get('Properties', {}).get('appearance') in ('1', '2')]
    for pos in supports:
        at = tuple(pos[i] + offset[i] for i in range(3))
        if world_tree and protected_hall(*at) or cell_hash(*at) < .5:
            continue
        for dx, dy, dz, face in FACES:
            target = (pos[0] + dx, pos[1] + dy, pos[2] + dz)
            if not all(0 <= target[i] < size[i] for i in range(3)):
                continue
            absolute = tuple(target[i] + offset[i] for i in range(3))
            if world_tree and protected_hall(*absolute):
                continue
            existing = cells.get(target, {'Name': 'minecraft:air'})
            if existing['Name'] not in ('minecraft:air', VEIN):
                continue
            props = {f: 'false' for f in ('down', 'east', 'north', 'south', 'up', 'west', 'waterlogged')}
            if existing['Name'] == VEIN:
                props.update(existing['Properties'])
            props[face] = 'true'
            cells[target] = {'Name': VEIN, 'Properties': props}


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def rename(value):
    if isinstance(value, str):
        return value.replace(NS + 'elven/', NS + 'blighted_elven/')
    if isinstance(value, dict):
        return {k: rename(v) for k, v in value.items()}
    if isinstance(value, list):
        return [rename(v) for v in value]
    return value


def corrupted(state):
    name = state['Name']
    if name in (NS + 'elven_heartwood', NS + 'cloudsea_fir_heart_log'):
        return {'Name': NS + 'blighted_heartwood', 'Properties': {'axis': state.get('Properties', {}).get('axis', 'y')}}
    if name in (NS + 'elven_canopy', NS + 'cloudsea_fir_heart_leaf'):
        return {'Name': NS + 'blighted_canopy'}
    if name == 'minecraft:moss_block':
        return {'Name': NS + 'blighted_loam'}
    if name == NS + 'elven_luminous_inlay':
        return {'Name': NS + 'blighted_lumen'}
    # 植物、培养罐、精灵家具和灯都撤掉，保留残破的楼板与回廊。
    if name.startswith(NS + 'elven_') and name not in (NS + 'elven_pale_planks', NS + 'elven_climbing_rope'):
        return {'Name': 'minecraft:air'}
    if name == 'logos:cultivation_jar' or name in ('minecraft:white_wool', 'minecraft:glass_pane'):
        return {'Name': 'minecraft:air'}
    return copy.deepcopy(state)


def templates():
    sources = sorted((DATA / 'structure/elven').glob('tile_*.nbt'))
    sources += [DATA / f'structure/elven/giant_{i}.nbt' for i in range(5)]
    for path in sources:
        doc = read_nbt(path)
        world_tree = path.stem.startswith('tile_')
        offset = tuple(int(n) * 32 - (4 if i == 1 else 112) for i, n in enumerate(path.stem.split('_')[1:])) if world_tree else (-doc['size'][0] // 2, 0, -doc['size'][2] // 2)
        cells, tags = {}, {}
        for entry in doc['blocks']:
            pos = tuple(entry['pos'])
            original = doc['palette'][entry['state']]
            state = corrupted(original)
            at = tuple(pos[i] + offset[i] for i in range(3))
            if world_tree and protected_hall(*at):
                # 只留下天然树壁，家具、楼板、楼梯和围栏全部拆空。
                if at[1] > 1 and state['Name'] not in ('minecraft:air', 'minecraft:jigsaw', NS + 'blighted_heartwood'):
                    state = {'Name': 'minecraft:air'}
            else:
                state = encrust(state, at)
                appearance = {NS + 'blighted_heartwood': 1, NS + 'blighted_canopy': 2, NS + 'blighted_loam': 3}.get(state['Name'], 0)
                if state['Name'] in (NS + 'blight_memory', NS + 'blighted_heartwood', NS + 'blighted_canopy', NS + 'blighted_loam'):
                    state = memory(original, appearance)
            cells[pos] = state
            if 'nbt' in entry and state['Name'] != 'minecraft:air':
                tags[pos] = rename(entry['nbt'])
        coat_surfaces(cells, doc['size'], offset, world_tree)
        if world_tree:
            for x in range(doc['size'][0]):
                for z in range(doc['size'][2]):
                    ax, az = x + offset[0], z + offset[2]
                    if ax * ax + az * az > 13 * 13:
                        continue
                    for y in range(doc['size'][1]):
                        ay = y + offset[1]
                        if ay == 1:
                            cells[x, y, z] = {'Name': NS + 'blighted_lumen'}
                        elif 2 <= ay < 96 and ax * ax + az * az < (13 if ay < 82 else 13 - (ay - 82) * .22) ** 2:
                            cells[x, y, z] = {'Name': 'minecraft:air'}
                            tags.pop((x, y, z), None)
        if path.stem == 'tile_3_0_3':
            # 核心悬在黑色大厅上方，血管由同一个核心渲染器连接到树壁。
            local = (HEART[0] - offset[0], HEART[1] - offset[1], HEART[2] - offset[2])
            cells[local] = {'Name': NS + 'queen_heart', 'Properties': {'awakened': 'false', 'defeated': 'false'}}
            tags[local] = {'id': NS + 'queen_heart', 'GuardianDrop': 7}
        write_structure(DATA / 'structure/blighted_elven' / path.name, doc['size'], cells, tags, doc.get('DataVersion', 0))
    for path in sorted((DATA / 'worldgen/template_pool/elven').glob('*.json')):
        doc = rename(load(path))
        if path.stem == 'giants':
            doc['elements'] = [e for e in doc['elements'] if not e['element'].get('location', '').endswith('_home')]
        if path.stem == 'roots':
            doc['elements'][0]['element']['feature'] = NS + 'blighted_elven_roots'
        write(DATA / 'worldgen/template_pool/blighted_elven' / path.name, doc)
    roots = load(DATA / 'worldgen/configured_feature/elven_roots.json')
    roots['config']['layers'][0]['provider']['state'] = memory({'Name': NS + 'elven_heartwood', 'Properties': {'axis': 'y'}})
    write(DATA / 'worldgen/configured_feature/blighted_elven_roots.json', roots)
    placed_roots = load(DATA / 'worldgen/placed_feature/elven_roots.json')
    placed_roots['feature'] = NS + 'blighted_elven_roots'
    write(DATA / 'worldgen/placed_feature/blighted_elven_roots.json', placed_roots)


def biome():
    forest = load(DATA / 'worldgen/biome/elven_forest.json')
    forest['effects'].update(water_color='#54404e', grass_color='#665d63', foliage_color='#514650', fog_color='#a194a1', water_fog_color='#584659')
    forest['features'][7] = []
    forest['features'][9] = [NS + 'blighted_elven_trees', NS + 'blighted_elven_growth', NS + 'blighted_elven_outcrop', NS + 'blight_nodes']
    write(DATA / 'worldgen/biome/blighted_elven_forest.json', forest)
    tree = load(DATA / 'worldgen/configured_feature/trees_cloudsea_ancient_beech.json')
    healthy_trunk = copy.deepcopy(tree['config']['trunk_provider']['state'])
    healthy_leaves = copy.deepcopy(tree['config']['foliage_provider']['state'])
    healthy_leaves.setdefault('Properties', {})['persistent'] = 'true'
    tree['config']['trunk_provider'] = {'type': 'minecraft:weighted_state_provider', 'entries': [
        {'data': memory(healthy_trunk), 'weight': 8}, {'data': memory(healthy_trunk, 1), 'weight': 2}]}
    tree['config']['foliage_provider'] = {'type': 'minecraft:weighted_state_provider', 'entries': [
        {'data': memory(healthy_leaves), 'weight': 7}, {'data': memory(healthy_leaves, 2), 'weight': 3}]}
    write(DATA / 'worldgen/configured_feature/blighted_elven_trees.json', tree)
    placed = load(DATA / 'worldgen/placed_feature/trees_cloudsea_ancient_beech.json')
    placed['feature'] = NS + 'blighted_elven_trees'
    # 地表下方须有本群系的林土，避免把同材质的巨木树冠误当成地面再种树。
    placed['placement'].insert(-1, {'type': 'minecraft:block_predicate_filter', 'predicate': {
        'type': 'minecraft:matching_blocks', 'offset': [0, -2, 0], 'blocks': [NS + 'blighted_loam']}})
    write(DATA / 'worldgen/placed_feature/blighted_elven_trees.json', placed)
    choices = [{'data': {'Name': NS + name}, 'weight': w} for name, w in [('corruption_bud', 3), ('corruption_spines', 3), ('corruption_ribs', 2), ('corruption', 2)]]
    choices.append({'data': {'Name': VEIN, 'Properties': {'down': 'true', 'up': 'false', 'north': 'false', 'south': 'false', 'east': 'false', 'west': 'false', 'waterlogged': 'false'}}, 'weight': 6})
    write(DATA / 'worldgen/configured_feature/blighted_elven_growth.json', {'type': 'minecraft:simple_block', 'config': {
        'to_place': {'type': 'minecraft:weighted_state_provider', 'entries': choices}}})
    write(DATA / 'worldgen/placed_feature/blighted_elven_growth.json', {'feature': NS + 'blighted_elven_growth', 'placement': [
        {'type': 'minecraft:count', 'count': 144}, {'type': 'minecraft:in_square'}, {'type': 'minecraft:heightmap', 'heightmap': 'MOTION_BLOCKING_NO_LEAVES'},
        {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:all_of', 'predicates': [
            {'type': 'minecraft:matching_blocks', 'blocks': ['minecraft:air']},
            {'type': 'minecraft:matching_blocks', 'offset': [0, -1, 0], 'blocks': [NS + 'blighted_loam', 'minecraft:dirt', NS + 'corruption']}] }},
        {'type': 'minecraft:biome'}]})
    outcrop = load(DATA / 'worldgen/configured_feature/forest_rock_outcrop.json')
    outcrop['config']['state_provider']['state'] = memory(copy.deepcopy(outcrop['config']['state_provider']['state']))
    write(DATA / 'worldgen/configured_feature/blighted_elven_outcrop.json', outcrop)
    placed_outcrop = load(DATA / 'worldgen/placed_feature/forest_rock_outcrop.json')
    placed_outcrop['feature'] = NS + 'blighted_elven_outcrop'
    write(DATA / 'worldgen/placed_feature/blighted_elven_outcrop.json', placed_outcrop)
    # 原版单方块 Feature 在污染地表放区域核心，不在安全内厅放置。
    write(DATA / 'worldgen/configured_feature/blight_nodes.json', {'type': 'minecraft:simple_block', 'config': {
        'to_place': {'type': 'minecraft:simple_state_provider', 'state': {'Name': NS + 'blight_node', 'Properties': {'stage': '0'}}}}})
    write(DATA / 'worldgen/placed_feature/blight_nodes.json', {'feature': NS + 'blight_nodes', 'placement': [
        {'type': 'minecraft:in_square'}, {'type': 'minecraft:heightmap', 'heightmap': 'MOTION_BLOCKING'},
        {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:matching_blocks', 'offset': [0, -1, 0],
          'blocks': [NS + 'blight_memory', NS + 'blighted_loam', NS + 'corruption', NS + 'corruption_bud', NS + 'corruption_spines', NS + 'corruption_ribs']}},
        {'type': 'minecraft:biome'}]})
    for name in ('elven_world_tree', 'elven_giant_tree'):
        doc = rename(load(DATA / f'worldgen/structure/{name}.json'))
        doc['biomes'] = [BIOME]
        write(DATA / f'worldgen/structure/blighted_{name}.json', doc)
    for name, structure in [('elven_world_trees', 'elven_world_tree'), ('elven_giant_trees', 'elven_giant_tree')]:
        path = DATA / f'worldgen/structure_set/{name}.json'
        doc = load(path)
        doc['structures'] = [{'structure': NS + structure, 'weight': 1}, {'structure': NS + 'blighted_' + structure, 'weight': 1}]
        write(path, doc)


def climate():
    paths = [DATA / 'dimension/cloudsea.json', ROOT / 'gametest-packs/crucible/data/minecraft/worldgen/world_preset/flat.json']
    def replace(node):
        if isinstance(node, dict):
            if node.get('biome') in (NS + 'elven_forest', BIOME):
                node['biome'] = BIOME if node['parameters']['weirdness'] == -0.35 else NS + 'elven_forest'
            for value in node.values():
                replace(value)
        elif isinstance(node, list):
            for value in node:
                replace(value)
    for path in paths:
        doc = load(path)
        replace(doc)
        write(path, doc)
    path = DATA / 'worldgen/noise_settings/journey.json'
    doc = load(path)
    def surface(node):
        if isinstance(node, dict):
            for value in node.values():
                surface(value)
        elif isinstance(node, list):
            for item in list(node):
                if isinstance(item, dict) and item.get('if_true', {}).get('biome_is') == [BIOME]:
                    item['then_run']['sequence'][0]['then_run']['result_state'] = FLESH
                    item['then_run']['sequence'][1]['then_run']['result_state'] = {'Name': NS + 'blighted_loam'}
                if isinstance(item, dict) and item.get('if_true', {}).get('biome_is') == [NS + 'elven_forest']:
                    if any(isinstance(s, dict) and s.get('if_true', {}).get('biome_is') == [BIOME] for s in node):
                        continue
                    extra = copy.deepcopy(item)
                    extra['if_true']['biome_is'] = [BIOME]
                    extra['then_run']['sequence'][0]['then_run']['result_state'] = FLESH
                    extra['then_run']['sequence'][1]['then_run']['result_state'] = {'Name': NS + 'blighted_loam'}
                    node.insert(node.index(item), extra)
                surface(item)
    surface(doc)
    write(path, doc)


if __name__ == '__main__':
    templates()
    biome()
    climate()
    write(PALETTE_PATH, PALETTE)
    print('腐败精灵森林：348 个无实体模板，五组精灵气候分支中的一组。')
