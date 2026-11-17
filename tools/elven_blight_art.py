"""代码绘制腐败林地材质，并从现有精灵零件制作女王独立模型和动作。"""
import copy
import json
import math
import random
import uuid
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'
ART = RES / 'assets/logos_dimension'
DATA = RES / 'data'
NS = 'logos_dimension:'


def load(p):
    return json.loads(p.read_text(encoding='utf-8'))


def write(p, doc):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def texture(name, mode):
    if name == 'blighted_lumen':
        from elven_queen_floor import export
        export()
        return
    rng = random.Random(name)
    im = Image.new('RGBA', (32, 32))
    for y in range(32):
        for x in range(32):
            if mode == 'leaves':
                leaf = (x // 3 + y // 4) % 4
                c = [(69, 66, 70), (91, 78, 82), (112, 96, 100), (55, 58, 58)][leaf]
                alpha = 0 if rng.random() < .18 else 255
            elif mode == 'wood':
                groove = (x + int(2 * math.sin(y * .23))) % 9
                c = (57, 45, 53) if groove < 2 else (104, 94, 94) if groove == 2 else (81, 75, 79)
                alpha = 255
            elif mode == 'end':
                r = math.hypot(x - 15.5, y - 15.5)
                c = (71, 47, 59) if int(r) % 5 == 0 else (126, 108, 109)
                alpha = 255
            elif mode == 'obsidian':
                line = abs((x + y * 2 + int(3 * math.sin(y / 5))) % 25 - 12) < 1
                c = (54, 15, 46) if line else (17, 13, 25)
                alpha = 255
            elif mode == 'loam':
                c = (84, 75, 80) if (x * 7 + y * 13) % 17 < 4 else (103, 94, 97)
                alpha = 255
            else:
                ridge = (x + int(2 * math.sin(y / 4))) % 10
                c = (56, 29, 46) if ridge < 2 else (122, 64, 84) if ridge < 4 else (84, 43, 65)
                alpha = 255
            n = rng.randint(-5, 5)
            im.putpixel((x, y), tuple(max(0, min(255, v + n)) for v in c) + (alpha,))
    d = ImageDraw.Draw(im)
    if mode in ('wood', 'flesh'):
        for i in range(3):
            x, y = rng.randrange(32), rng.randrange(32)
            d.line([(x, y), (x - 2, y + 3), (x, y + 7), (x - 3, y + 11)], fill=(166, 79, 99), width=1)
            d.point((x - 1, y + 2), fill=(224, 142, 150))
    path = ART / f'textures/block/{name}.png'
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path)


def cube(name, start, end, texture_name, rotation=None):
    e = {'name': name, 'from': start, 'to': end, 'faces': {face: {'uv': [0, 0, 16, 16], 'texture': '#' + texture_name} for face in ('north', 'south', 'east', 'west', 'up', 'down')}}
    if rotation:
        e['rotation'] = rotation
    return e


def block_assets():
    for name, mode in [('blighted_heartwood', 'wood'), ('blighted_heartwood_top', 'end'), ('blighted_canopy', 'leaves'), ('blighted_loam', 'loam'), ('blighted_lumen', 'obsidian'), ('corruption_growth', 'flesh')]:
        texture(name, mode)
    palette = [(13, 3, 13), (35, 7, 21), (57, 10, 26), (85, 16, 33), (110, 22, 39), (137, 31, 47), (98, 13, 38), (142, 19, 45), (184, 34, 55), (229, 65, 79), (255, 126, 120), (231, 195, 183), (205, 58, 73), (143, 19, 40), (19, 4, 15), (0, 0, 0)]
    image = Image.new('RGBA', (16, 16))
    for x, color in enumerate(palette):
        for y in range(16):
            image.putpixel((x, y), color + (255,))
    image.save(ART / 'textures/entity/queen_heart_palette.png')
    for name in ('blighted_heartwood', 'blighted_canopy', 'blighted_loam', 'blighted_lumen', 'queen_heart', 'corruption_bud', 'corruption_spines', 'corruption_ribs'):
        model = {'parent': 'minecraft:block/cube_all', 'textures': {'all': NS + 'block/' + name}}
        state = {'variants': {'': {'model': NS + 'block/' + name}}}
        if name == 'blighted_heartwood':
            model = {'parent': 'minecraft:block/cube_column', 'textures': {'side': NS + 'block/' + name, 'end': NS + 'block/' + name + '_top'}}
            state = {'variants': {axis: {'model': NS + 'block/' + name, **rot} for axis, rot in [('axis=y', {}), ('axis=x', {'x': 90, 'y': 90}), ('axis=z', {'x': 90})]}}
        if name == 'blighted_canopy':
            model['render_type'] = 'minecraft:cutout'
        if name == 'blighted_lumen':
            from elven_queen_floor import floor_model
            model = floor_model()
        if name == 'queen_heart':
            model = {'parent': NS + 'block/corruption_core'}
        if name.startswith('corruption_'):
            shapes = []
            if name == 'corruption_bud':
                shapes = [cube('侵蚀基盘', [2, 0, 2], [14, 3, 14], 'flesh'), cube('脓芽', [4, 3, 4], [12, 12, 12], 'flesh'), cube('裂口', [6, 5, 2.8], [10, 10, 4], 'glow')]
            elif name == 'corruption_spines':
                for i, (x, z) in enumerate([(4, 4), (10, 7), (6, 12)]):
                    shapes.append(cube('骨刺' + str(i), [x, 0, z], [x + 2, 10 + i * 2, z + 2], 'flesh', {'origin': [x + 1, 1, z + 1], 'axis': 'z', 'angle': -22.5 if i % 2 else 22.5}))
            else:
                shapes.append(cube('脊髓', [7, 0, 1], [9, 3, 15], 'flesh'))
                for i in range(3):
                    for s in (-1, 1):
                        shapes.append(cube('肋骨' + str(i) + str(s), [7 if s > 0 else 1, 1, 2 + i * 4], [15 if s > 0 else 9, 3, 4 + i * 4], 'flesh', {'origin': [8, 1, 3 + i * 4], 'axis': 'z', 'angle': s * 22.5}))
            model = {'render_type': 'minecraft:cutout', 'textures': {'particle': NS + 'block/corruption_growth', 'flesh': NS + 'block/corruption_growth', 'glow': NS + 'block/corruption'}, 'elements': shapes}
        write(ART / f'models/block/{name}.json', model)
        write(ART / f'blockstates/{name}.json', state)
        write(ART / f'models/item/{name}.json', {'parent': NS + 'block/' + name})
        write(ART / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': NS + 'item/' + name}})
        if name != 'queen_heart':
            write(DATA / f'logos_dimension/loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': NS + name}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    tags = {'minecraft:logs': ['blighted_heartwood'], 'minecraft:logs_that_burn': ['blighted_heartwood'], 'minecraft:leaves': ['blighted_canopy'], 'minecraft:dirt': ['blighted_loam'], 'minecraft:mineable/axe': ['blighted_heartwood'], 'minecraft:mineable/shovel': ['blighted_loam'], 'minecraft:mineable/hoe': ['blighted_canopy', 'corruption_bud', 'corruption_spines', 'corruption_ribs'], 'logos:fragile_plants': ['blighted_canopy', 'corruption_bud', 'corruption_spines', 'corruption_ribs']}
    for tag, names in tags.items():
        namespace, path = tag.split(':')
        target = DATA / namespace / f'tags/block/{path}.json'
        doc = load(target) if target.exists() else {'replace': False, 'values': []}
        doc['values'] = list(dict.fromkeys(doc['values'] + [NS + x for x in names]))
        write(target, doc)
    target = DATA / 'logos_dimension/tags/block/no_tree_roots.json'
    doc = load(target)
    doc['values'] = list(dict.fromkeys(doc['values'] + [NS + 'blighted_lumen', NS + 'queen_heart']))
    write(target, doc)
    for lang, names in [('zh_cn', ['腐败古木', '枯败树冠', '腐败林土', '女王的腐败核心', '腐败脓芽', '腐败骨刺', '腐败肋丛']), ('en_us', ['Blighted Heartwood', 'Blighted Canopy', 'Blighted Loam', "Queen's Corruption Heart", 'Corruption Bud', 'Corruption Spines', 'Corruption Ribs'])]:
        path = ART / f'lang/{lang}.json'
        doc = load(path)
        for key, value in zip(('blighted_heartwood', 'blighted_canopy', 'blighted_loam', 'queen_heart', 'corruption_bud', 'corruption_spines', 'corruption_ribs'), names):
            doc['block.logos_dimension.' + key] = value
            doc['item.logos_dimension.' + key] = value
        doc['block.logos_dimension.blighted_lumen'] = doc['item.logos_dimension.blighted_lumen'] = '腐败脉光' if lang == 'zh_cn' else 'Blighted Veinlight'
        doc['biome.logos_dimension.blighted_elven_forest'] = '腐败精灵森林' if lang == 'zh_cn' else 'Blighted Elven Forest'
        doc['entity.logos_dimension.corrupted_elf_queen'] = '腐败精灵女王' if lang == 'zh_cn' else 'Corrupted Elf Queen'
        write(path, doc)
    path = DATA / 'logos_dimension/tags/entity_type/corrupted.json'
    doc = load(path)
    doc['values'] = list(dict.fromkeys(doc['values'] + [NS + 'corrupted_elf_queen']))
    write(path, doc)


def queen_model():
    geo = load(ART / 'geckolib/models/entity/forest_elf.geo.json')
    body = geo['minecraft:geometry'][0]
    parts = load(DATA / 'logos_dimension/npc_races/forest_elf.json')
    chosen = {'hair_priestess_braids', 'headwear_twig_crown', 'ear_long', 'eyes_square', 'mouth_smile', 'outfit_floral_gown'}
    drop = set()
    for slot in parts['slots'].values():
        for part in slot['parts']:
            if part['id'] not in chosen:
                drop.update(part['groups'])
    for part in parts['expressions']:
        drop.update(part['groups'])
    for b in body['bones']:
        if b.get('parent') in drop:
            drop.add(b['name'])
    body['bones'] = [b for b in body['bones'] if b['name'] not in drop]
    factor = 1.52
    for bone in body['bones']:
        bone['pivot'] = [round(v * factor, 5) for v in bone.get('pivot', [0, 0, 0])]
        for c in bone.get('cubes', []):
            for key in ('origin', 'size', 'pivot'):
                if key in c:
                    c[key] = [round(v * factor, 5) for v in c[key]]
    body['description'].update(identifier='geometry.corrupted_elf_queen', texture_height=416, visible_bounds_width=4, visible_bounds_height=5, visible_bounds_offset=[0, 2.2, 0])
    tex = Image.new('RGBA', (128, 416))
    original = Image.open(ART / 'textures/entity/forest_elf.png').convert('RGBA')
    # 保留皮肤和眼白，灰绿发色、骨白衣料、病红脉络保持大块层次，不画固定方向光。
    for y in range(original.height):
        for x in range(original.width):
            r, g, b, a = original.getpixel((x, y))
            if g > r * 1.07 and g > b * 1.05:
                light = .25 * r + .6 * g + .15 * b
                rgb = (int(light * .72 + 18), int(light * .69 + 13), int(light * .78 + 18))
            elif r > g * 1.2 and r > b * 1.1 and r > 170:
                rgb = (int(r * .94), int(g * .98), min(255, int(b * 1.06)))
            else:
                rgb = (r, g, b)
            tex.putpixel((x, y), rgb + (a,))
    colors = [(61, 35, 48), (113, 66, 83), (168, 102, 116), (238, 166, 155), (143, 129, 131), (218, 204, 190), (83, 77, 80), (31, 23, 32)]
    d = ImageDraw.Draw(tex)
    for i, color in enumerate(colors):
        d.rectangle((i * 16, 368, i * 16 + 15, 383), fill=color + (255,))
        for yy in range(369, 384, 4):
            d.line((i * 16 + 1, yy, i * 16 + 14, yy + 1), fill=tuple(max(0, c - 8) for c in color) + (255,))
    # 病红虹膜保留清晰的深瞳与眼白，不把脸整张涂黑。
    d.rectangle((0, 384, 23, 411), fill=(68, 21, 44, 255))
    for y in range(386, 410):
        d.line((2, y, 21, y), fill=(min(240, 125 + (y - 386) * 4), 65 + y % 5, 91 + (y - 386) * 2, 255))
    d.rectangle((9, 386, 13, 405), fill=(39, 19, 35, 255))
    d.rectangle((3, 387, 7, 392), fill=(255, 230, 208, 255))
    for bone in body['bones']:
        if bone['name'].endswith('_pupil'):
            for c in bone.get('cubes', []):
                if 'north' in c['uv']:
                    c['uv']['north'] = {'uv': [0, 384], 'uv_size': [24, 28]}
    def add(name, parent, pivot, pos, size, color, rotation=None):
        bone = {'name': name, 'parent': parent, 'pivot': pivot, 'cubes': [{'origin': pos, 'size': size, 'uv': {f: {'uv': [color * 16 + 1, 369], 'uv_size': [12, 12]} for f in ('north', 'south', 'east', 'west', 'up', 'down')}}]}
        if rotation:
            bone['rotation'] = rotation
        body['bones'].append(bone)
    # 枯枝王冠偏向一侧；分段根须与裙摆使用独立骨骼。
    for side in (-1, 1):
        base = [side * 4, 73, 0]
        add(f'queen_crown_{side}', 'Head', base, [side * 4 - 1, 71, -1], [2, 10, 2], 4, [0, 0, side * -22])
        add(f'queen_crown_fork_{side}', f'queen_crown_{side}', [side * 4, 78, 0], [side * 4 - 1, 77, -1], [2, 7 if side < 0 else 5, 2], 0, [0, 0, side * 42])
        add(f'ysmGlowQueenCrown{side}', f'queen_crown_fork_{side}', [side * 4, 80, -1], [side * 4 - .9, 79.5, -1.6], [1.8, 2.4, 1.8], 3)
    for i in range(7):
        a = math.radians(20 + i * 22)
        x, z = math.cos(a) * 7, math.sin(a) * 4 + 2
        parent = 'UpperBody'
        for j in range(3):
            name = f'queen_root_{i}_{j}'
            add(name, parent, [x, 31 - j * 10, z], [x - 1.5 + j * .3, 20 - j * 10, z], [3 - j * .6, 12, 2.4 - j * .4], 0 if j % 2 == 0 else 1, [-8 + j * 7, 0, (i - 3) * 4])
            parent = name
        add(f'ysmGlowQueenRoot{i}', parent, [x, 0, z], [x - .5, -1, z -.3], [1.2, 2, 1.2], 2)
    add('queen_corrupt_shoulder', 'RightArm', [-7, 49, 0], [-13, 46, -3], [7, 6, 6], 0, [0, 0, 18])
    add('queen_shoulder_thorn', 'queen_corrupt_shoulder', [-11, 51, 0], [-12, 49, -1], [2, 8, 2], 4, [0, 0, 35])
    add('ysmGlowQueenHeart', 'UpperBody', [0, 47, -4], [-1.3, 44, -4.5], [2.6, 3.6, 1.3], 3)
    write(ART / 'geckolib/models/entity/corrupted_elf_queen.geo.json', geo)
    tex.save(ART / 'textures/entity/corrupted_elf_queen.png')
    return geo, factor


def animations(geo, factor):
    source = load(ART / 'geckolib/animations/entity/forest_elf.animation.json')
    valid = {b['name'] for b in geo['minecraft:geometry'][0]['bones']}
    doc = {'format_version': '1.8.0', 'animations': {k: copy.deepcopy(source['animations'][k]) for k in ('idle', 'walk', 'jump', 'attacked', 'death')}}
    for clip in doc['animations'].values():
        clip['bones'] = {k: v for k, v in clip['bones'].items() if k in valid}
    def frames(rows):
        return {str(t): {'post': value, 'lerp_mode': 'catmullrom'} for t, value in rows}
    def clip(name, length, bones):
        doc['animations'][name] = {'animation_length': length, 'bones': {key: {kind: frames(value) for kind, value in channels.items()} for key, channels in bones.items()}}
    clip('queen_emerge', 5, {
        'AllBody': {'position': [(0, [0, -17, 90]), (.7, [0, -15, 70]), (2.1, [0, -15, 35]), (3, [0, -9, 12]), (4.4, [0, 0, 0]), (5, [0, 0, 0])]},
        'UpperBody': {'rotation': [(0, [70, 0, -8]), (1.5, [62, -8, 6]), (3, [42, 0, 0]), (4.5, [-8, 0, 0]), (5, [0, 0, 0])]},
        'Head': {'rotation': [(0, [-35, 0, 10]), (1.3, [-48, 12, -7]), (3.4, [-25, -8, 0]), (5, [0, 0, 0])]},
        'LeftArm': {'rotation': [(0, [-120, -20, -35]), (1, [-80, 0, -50]), (2, [-100, 5, -15]), (3.2, [-25, 0, -50]), (5, [0, 0, 0])]},
        'RightArm': {'rotation': [(0, [-65, 5, 30]), (1.4, [-120, 15, 30]), (2.5, [-65, 0, 55]), (3.4, [-25, 0, 60]), (5, [0, 0, 0])]},
        'LeftForeArm': {'rotation': [(0, [-50, 0, 0]), (2, [-15, 0, 0]), (3.5, [-55, 0, 0]), (5, [0, 0, 0])]},
        'RightForeArm': {'rotation': [(0, [-20, 0, 0]), (1.5, [-60, 0, 0]), (3.5, [-35, 0, 0]), (5, [0, 0, 0])]},
        'LeftLeg': {'rotation': [(0, [-55, 0, -12]), (2, [-78, 0, -15]), (3.7, [-35, 0, 0]), (5, [0, 0, 0])]},
        'RightLeg': {'rotation': [(0, [-65, 0, 15]), (2.5, [-40, 0, 0]), (4, [-20, 0, 0]), (5, [0, 0, 0])]}})
    clip('queen_reap', 2.4, {
        'UpperBody': {'rotation': [(0, [0, 0, 0]), (.85, [8, -45, -7]), (1.1, [10, -48, -10]), (1.25, [7, 60, 8]), (1.65, [0, 48, 0]), (2.4, [0, 0, 0])]},
        'RightArm': {'rotation': [(0, [0, 0, 0]), (.85, [-75, -35, 90]), (1.1, [-80, -40, 100]), (1.25, [-70, 80, -35]), (1.65, [-60, 60, -30]), (2.4, [0, 0, 0])]},
        'LeftArm': {'rotation': [(0, [0, 0, 0]), (1, [10, -20, -30]), (1.25, [-20, -30, -55]), (2.4, [0, 0, 0])]}})
    clip('queen_roots', 3.8, {
        'AllBody': {'position': [(0, [0, 0, 0]), (1.3, [0, 4, 0]), (1.55, [0, -5, 0]), (2.7, [0, -5, 0]), (3.8, [0, 0, 0])]},
        'UpperBody': {'rotation': [(0, [0, 0, 0]), (1.3, [-15, 0, 0]), (1.55, [22, 0, 0]), (2.6, [15, 0, 0]), (3.8, [0, 0, 0])]},
        'RightArm': {'rotation': [(0, [0, 0, 0]), (1.3, [-170, 0, 28]), (1.55, [-45, 0, 35]), (2.6, [-40, 0, 35]), (3.8, [0, 0, 0])]},
        'LeftArm': {'rotation': [(0, [0, 0, 0]), (1.3, [-170, 0, -28]), (1.55, [-45, 0, -35]), (2.6, [-40, 0, -35]), (3.8, [0, 0, 0])]}})
    clip('queen_thorns', 3.1, {
        'UpperBody': {'rotation': [(0, [0, 0, 0]), (1.4, [-8, -22, 0]), (1.7, [16, 18, 0]), (2.2, [12, 15, 0]), (3.1, [0, 0, 0])]},
        'RightArm': {'rotation': [(0, [0, 0, 0]), (1.4, [-50, -60, 40]), (1.7, [-100, 12, -15]), (2.3, [-95, 12, -12]), (3.1, [0, 0, 0])]},
        'RightForeArm': {'rotation': [(0, [0, 0, 0]), (1.4, [-100, 0, 0]), (1.7, [-8, 0, 0]), (2.3, [-10, 0, 0]), (3.1, [0, 0, 0])]}})
    for i in range(7):
        doc['animations']['idle']['bones'][f'queen_root_{i}_0'] = {'rotation': frames([(0, [-4, 0, -3]), (2, [4, 0, 3]), (4, [-4, 0, -3])])}
        doc['animations']['queen_emerge']['bones'][f'queen_root_{i}_0'] = {'rotation': frames([(0, [-60, 0, (i - 3) * 8]), (2.6, [-30, 0, (i - 3) * 4]), (4, [15, 0, 0]), (5, [0, 0, 0])])}
    write(ART / 'geckolib/animations/entity/corrupted_elf_queen.animation.json', doc)


def guard_animations():
    """按当前骨架计算坐姿与裙摆收拢，保持坐下时的黑色地面落点。"""
    path = ART / 'geckolib/animations/entity/corrupted_elf_queen.animation.json'
    doc = load(path)
    model = load(ART / 'geckolib/models/entity/corrupted_elf_queen.geo.json')['minecraft:geometry'][0]
    rig = {b['name']: b for b in model['bones']}
    unit = rig['LeftLeg']['pivot'][1] / 22.60097
    drop = rig['LeftLeg']['pivot'][1] - 7 * unit
    skirt_lift = drop - rig['Skirt']['pivot'][1] * .65 + .5 * unit
    pose = {'AllBody': ('position', [0, -drop, 0]), 'UpperBody': ('rotation', [5, 0, 0]),
            'LeftLeg': ('rotation', [-75, 8, 6]), 'RightLeg': ('rotation', [-75, -8, -6]),
            'LeftLowerLeg': ('rotation', [140, 0, 0]), 'RightLowerLeg': ('rotation', [140, 0, 0]),
            'LeftFoot': ('rotation', [-50, 0, 0]), 'RightFoot': ('rotation', [-50, 0, 0]),
            'LeftArm': ('rotation', [-25, 0, -8]), 'RightArm': ('rotation', [-25, 0, 8]),
            'LeftForeArm': ('rotation', [-60, 0, -18]), 'RightForeArm': ('rotation', [-60, 0, 18])}
    doc['animations']['queen_guard'] = {'loop': True, 'animation_length': 4, 'bones': {
        name: {kind: value} for name, (kind, value) in pose.items()}}
    doc['animations']['queen_guard']['bones']['Skirt'] = {'position':[0,skirt_lift,0], 'scale':[1.45,.35,1.4]}
    doc['animations']['queen_rise'] = {'animation_length': 2, 'bones': {
        name: {kind: {'0': {'post': value, 'lerp_mode': 'catmullrom'}, '.6': {'post': [v * .85 for v in value], 'lerp_mode': 'catmullrom'},
                     '1.65': {'post': [0, 0, 0], 'lerp_mode': 'catmullrom'}, '2': {'post': [0, 0, 0], 'lerp_mode': 'catmullrom'}}}
        for name, (kind, value) in pose.items()}}
    doc['animations']['queen_rise']['bones']['Skirt'] = {'position':{'0':[0,skirt_lift,0],'1.65':[0,0,0]},'scale':{'0':[1.45,.35,1.4],'1.65':[1,1,1]}}
    doc['animations'].pop('queen_emerge', None)
    for clip in doc['animations'].values():
        clip['bones'] = {name: value for name, value in clip.get('bones', {}).items() if name in rig}
    write(path, doc)


if __name__ == '__main__':
    block_assets()
    guard_animations()
    # 华服模型由 elven_queen_regalia.py 维护，这里只更新方块和守护动作。
    print('腐败林地与悬吊核心素材已导出；不覆盖已确认的华服模型。')
