"""远行大陆水下植物的美术资源：海草、高海草、萤光海草、海带的贴图与模型。

python tools/underwater_art.py

全部代码绘制（PIL）。模型沿用原版 template_seagrass / cross 模板，只换贴图，
渲染类型由原版模板自带（裁切）。
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
DATA = ROOT / 'src/main/resources/data/logos_dimension'


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def blade(d, x, y0, y1, color, dark, width=1):
    """一根略微弯曲的水草叶片。"""
    phase = (x * 7) % 3 - 1
    for y in range(y0, y1 + 1):
        xx = x + (phase if y > (y0 + y1) // 2 else 0)
        d.point((xx, y), fill=color)
        if width > 1:
            d.point((xx + 1, y), fill=dark)


def seagrass_texture(name, colors, tall=False):
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    light, dark = colors
    blades = [(3, 9, 15), (6, 4, 15), (9, 6, 15), (12, 10, 15)]
    if tall:
        blades = [(2, 2, 15), (5, 0, 15), (8, 3, 15), (11, 1, 15), (14, 5, 15)]
    for x, y0, y1 in blades:
        blade(d, x, y0, y1, light, dark)
    img.save(ASSETS / 'textures/block' / f'{name}.png')


def kelp_textures():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 顶叶：中轴 + 两侧叶
    for y in range(4, 16):
        d.point((8, y), fill=(86, 132, 70))
    for y in (5, 8, 11, 14):
        for dx in (1, 2):
            d.point((8 - dx, y), fill=(104, 158, 84))
            d.point((8 + dx, y), fill=(74, 120, 62))
    d.point((7, 3), fill=(132, 186, 104))
    d.point((9, 3), fill=(132, 186, 104))
    img.save(ASSETS / 'textures/block/journey_kelp.png')

    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(16):
        d.point((8, y), fill=(86, 132, 70))
        if y % 4 == 1:
            d.point((7, y), fill=(104, 158, 84))
            d.point((6, y + 1), fill=(74, 120, 62))
        if y % 4 == 3:
            d.point((9, y), fill=(104, 158, 84))
            d.point((10, y + 1), fill=(74, 120, 62))
    img.save(ASSETS / 'textures/block/journey_kelp_plant.png')


def models():
    write(ASSETS / 'models/block/journey_seagrass.json',
          {'parent': 'minecraft:block/template_seagrass',
           'textures': {'texture': 'logos_dimension:block/journey_seagrass'}})
    write(ASSETS / 'models/block/journey_tall_seagrass_top.json',
          {'parent': 'minecraft:block/template_seagrass',
           'textures': {'texture': 'logos_dimension:block/journey_tall_seagrass_top'}})
    write(ASSETS / 'models/block/journey_tall_seagrass_bottom.json',
          {'parent': 'minecraft:block/template_seagrass',
           'textures': {'texture': 'logos_dimension:block/journey_tall_seagrass_bottom'}})
    write(ASSETS / 'models/block/journey_glow_seagrass.json',
          {'parent': 'minecraft:block/template_seagrass',
           'textures': {'texture': 'logos_dimension:block/journey_glow_seagrass'}})
    write(ASSETS / 'models/block/journey_kelp.json',
          {'parent': 'minecraft:block/cross',
           'textures': {'cross': 'logos_dimension:block/journey_kelp'}})
    write(ASSETS / 'models/block/journey_kelp_plant.json',
          {'parent': 'minecraft:block/cross',
           'textures': {'cross': 'logos_dimension:block/journey_kelp_plant'}})


def states_and_items():
    write(ASSETS / 'blockstates/journey_seagrass.json',
          {'variants': {'': {'model': 'logos_dimension:block/journey_seagrass'}}})
    write(ASSETS / 'blockstates/journey_glow_seagrass.json',
          {'variants': {'': {'model': 'logos_dimension:block/journey_glow_seagrass'}}})
    write(ASSETS / 'blockstates/journey_tall_seagrass.json',
          {'variants': {
              'half=lower': {'model': 'logos_dimension:block/journey_tall_seagrass_bottom'},
              'half=upper': {'model': 'logos_dimension:block/journey_tall_seagrass_top'}}})
    write(ASSETS / 'blockstates/journey_kelp.json',
          {'variants': {'': {'model': 'logos_dimension:block/journey_kelp'}}})
    write(ASSETS / 'blockstates/journey_kelp_plant.json',
          {'variants': {'': {'model': 'logos_dimension:block/journey_kelp_plant'}}})
    for name, model in [('journey_seagrass', 'block/journey_seagrass'),
                        ('journey_glow_seagrass', 'block/journey_glow_seagrass'),
                        ('journey_kelp', 'block/journey_kelp')]:
        write(ASSETS / 'items' / f'{name}.json',
              {'model': {'type': 'minecraft:model', 'model': 'logos_dimension:' + model}})


def loot():
    def drop(name, item, count=1):
        write(DATA / 'loot_table/blocks' / f'{name}.json', {
            'type': 'minecraft:block',
            'pools': [{
                'rolls': 1,
                'conditions': [{'condition': 'minecraft:survives_explosion'}],
                'entries': [{'type': 'minecraft:item', 'name': item,
                             'functions': [{'function': 'minecraft:set_count', 'count': count}]}],
            }],
        })
    drop('journey_seagrass', 'logos_dimension:journey_seagrass')
    drop('journey_glow_seagrass', 'logos_dimension:journey_glow_seagrass')
    drop('journey_kelp', 'logos_dimension:journey_kelp')
    drop('journey_tall_seagrass', 'logos_dimension:journey_seagrass')
    drop('journey_kelp_plant', 'logos_dimension:journey_kelp')


def main():
    seagrass_texture('journey_seagrass', ((86, 148, 96), (52, 104, 66)))
    seagrass_texture('journey_tall_seagrass_top', ((96, 160, 104), (58, 112, 70)), tall=True)
    seagrass_texture('journey_tall_seagrass_bottom', ((78, 138, 88), (48, 96, 60)))
    seagrass_texture('journey_glow_seagrass', ((120, 236, 196), (64, 172, 152)))
    kelp_textures()
    models()
    states_and_items()
    loot()
    print('underwater art written')


if __name__ == '__main__':
    main()
