"""用代码保留原腐败纹理结构，重配猩红色层次并导出净化方块资源。"""
from pathlib import Path
import json
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
DATA = ROOT / 'src/main/resources/data'

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def crimson(source, target, strength=1):
    image = Image.open(source).convert('RGBA')
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = image.getpixel((x, y))
            light = (r * .35 + g * .35 + b * .30) / 255
            red = (int(12 + 180 * light**1.15), int(6 + 42 * light**1.4), int(9 + 50 * light**1.3))
            image.putpixel((x, y), (*[int(v * strength + old * (1-strength)) for v, old in zip(red, (r,g,b))], a))
    image.save(target)

for source, target, mix in [('corruption','blight_flesh',1), ('blighted_heartwood','blight_bark',.75), ('blighted_canopy','blight_leaves',.9), ('blighted_loam','blight_loam',.85)]:
    crimson(ASSETS / f'textures/block/{source}.png', ASSETS / f'textures/block/{target}.png', mix)
for name in ('corruption_bud','corruption_spines','corruption_ribs'):
    # 三种增生共用原来的几何，只替换森林新增部分的贴图。
    model = json.loads((ASSETS / f'models/block/{name}.json').read_text(encoding='utf-8'))
    model['parent'] = 'minecraft:block/block'
    for key, texture in list(model.get('textures', {}).items()):
        if texture.endswith('corruption_growth'):
            model['textures'][key] = 'logos_dimension:block/blight_growth'
    write(ASSETS / f'models/block/{name}.json', model)
crimson(ASSETS / 'textures/block/corruption_growth.png', ASSETS / 'textures/block/blight_growth.png')
crimson(ASSETS / 'textures/entity/corruption_core.png', ASSETS / 'textures/entity/blight_node_palette.png')
variants = {}
for index, texture in enumerate(('blight_flesh','blight_bark','blight_leaves','blight_loam')):
    name = f'blight_memory_{index}'
    model = {'parent': 'minecraft:block/cube_all', 'textures': {'all': 'logos_dimension:block/' + texture}}
    if index == 2:
        model['render_type'] = 'minecraft:cutout'
    write(ASSETS / f'models/block/{name}.json', model)
    variants[f'appearance={index}'] = {'model': 'logos_dimension:block/' + name}
write(ASSETS / 'blockstates/blight_memory.json', {'variants': variants})
write(ASSETS / 'blockstates/blighted_loam.json', {'variants': variants})
write(ASSETS / 'blockstates/blight_node.json', {'variants': {'': {'model': 'logos_dimension:block/corruption_core'}}})
for name, model in [('blight_memory','blight_memory_0'),('blight_node','corruption_core')]:
    write(ASSETS / f'models/item/{name}.json', {'parent': 'logos_dimension:block/' + model})
    write(ASSETS / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': 'logos_dimension:item/' + name}})
for lang, names in [('zh_cn', ['猩红污秽','区域腐败核心']), ('en_us', ['Crimson Blight','Regional Corruption Core'])]:
    path = ASSETS / f'lang/{lang}.json'
    values = json.loads(path.read_text(encoding='utf-8'))
    for name, title in zip(('blight_memory','blight_node'), names):
        for prefix in ('block','item'):
            values[f'{prefix}.logos_dimension.{name}'] = title
    write(path, values)
for path, names in [('logos_dimension/tags/block/no_tree_roots.json',['blight_node']), ('minecraft/tags/block/mineable/hoe.json',['blight_memory','blighted_loam']), ('minecraft/tags/block/mineable/pickaxe.json',['blight_node']), ('logos/tags/block/fragile_plants.json',['blight_memory'])]:
    target = DATA / path
    value = json.loads(target.read_text(encoding='utf-8')) if target.exists() else {'values': []}
    for name in names:
        entry = 'logos_dimension:' + name
        if entry not in value['values']:
            value['values'].append(entry)
    write(target, value)
print('猩红污染、核心、分层材质与净化物品资源已导出。')
