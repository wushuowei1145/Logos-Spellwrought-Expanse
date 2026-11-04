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
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


def blades(img, specs, base, tip, shade):
    """画一丛水草叶片。specs 为 (根部 x, 叶尖 y, 摆向, 是否两像素宽)；颜色从根部 base 渐变到叶尖 tip，
    两像素宽的叶片右侧一列用 shade 压暗，叶片在上半段按摆向弯出一格。"""
    h = img.height
    for x, top, sway, wide in specs:
        length = h - 1 - top
        for y in range(top, h):
            t = (h - 1 - y) / max(1, length)
            xx = x + (sway if t > 0.55 else 0) + (sway if t > 0.85 and length > 12 else 0)
            img.putpixel((xx, y), mix(base, tip, t))
            if wide and t < 0.8:
                img.putpixel((xx + 1, y), mix(shade, base, t))


SEAGRASS = ((46, 98, 58), (128, 190, 116), (34, 76, 46))


def seagrass_textures():
    # 矮海草：一丛高低不齐的叶片
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    blades(img, [(2, 9, 0, False), (4, 4, 1, True), (7, 6, -1, False), (9, 2, 1, True), (13, 8, -1, True)],
           *SEAGRASS)
    img.save(ASSETS / 'textures/block/journey_seagrass.png')

    # 高海草：在 16×32 上画一丛连贯的长叶，再切成上下两半，上下两格接缝处叶片连续
    tall = Image.new('RGBA', (16, 32), (0, 0, 0, 0))
    blades(tall, [(1, 12, 1, False), (3, 3, 1, True), (6, 8, -1, False), (8, 0, 1, True),
                  (11, 5, -1, True), (14, 14, -1, False)], *SEAGRASS)
    tall.crop((0, 0, 16, 16)).save(ASSETS / 'textures/block/journey_tall_seagrass_top.png')
    tall.crop((0, 16, 16, 32)).save(ASSETS / 'textures/block/journey_tall_seagrass_bottom.png')

    # 萤光海草：更细的淡青叶片，叶尖挂着发光的小球
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    specs = [(3, 6, 1, False), (7, 3, -1, False), (10, 5, 1, False), (13, 9, -1, False)]
    blades(img, specs, (40, 112, 108), (110, 206, 186), (30, 86, 84))
    for x, top, sway, _ in specs:
        tx = x + sway
        for px, py, c in [(tx, top - 1, (236, 255, 228)), (tx - 1, top, (172, 250, 214)),
                          (tx + 1, top, (172, 250, 214)), (tx, top, (214, 255, 236))]:
            if 0 <= px < 16 and 0 <= py < 16:
                img.putpixel((px, py), c + (255,))
    img.save(ASSETS / 'textures/block/journey_glow_seagrass.png')


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
    """掉落照原版：海草类只有用剪刀才掉（高海草掉两株海草），海带不挑工具、经得住爆炸才掉。"""
    def drop(name, item, condition, count=None):
        entry = {'type': 'minecraft:item', 'name': item}
        if count:
            entry['functions'] = [{'function': 'minecraft:set_count', 'count': count, 'add': False}]
        write(DATA / 'loot_table/blocks' / f'{name}.json', {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'bonus_rolls': 0, 'conditions': [condition], 'entries': [entry]}],
            'random_sequence': f'logos_dimension:blocks/{name}',
        })
    shears = {'condition': 'minecraft:match_tool', 'predicate': {'items': 'minecraft:shears'}}
    explosion = {'condition': 'minecraft:survives_explosion'}
    drop('journey_seagrass', 'logos_dimension:journey_seagrass', shears)
    drop('journey_glow_seagrass', 'logos_dimension:journey_glow_seagrass', shears)
    drop('journey_tall_seagrass', 'logos_dimension:journey_seagrass', shears, count=2)
    drop('journey_kelp', 'logos_dimension:journey_kelp', explosion)
    drop('journey_kelp_plant', 'logos_dimension:journey_kelp', explosion)


FLORA = ['journey_seagrass', 'journey_tall_seagrass', 'journey_glow_seagrass', 'journey_kelp', 'journey_kelp_plant']


def fragile_tag():
    """水下植物都加进 Logos 的 logos:fragile_plants，衰退才会毁掉它们（见 docs/LOGOS_TAGS.md）。"""
    path = ROOT / 'src/main/resources/data/logos/tags/block/fragile_plants.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    for name in FLORA:
        if 'logos_dimension:' + name not in data['values']:
            data['values'].append('logos_dimension:' + name)
    write(path, data)


def main():
    seagrass_textures()
    kelp_textures()
    models()
    states_and_items()
    loot()
    fragile_tag()
    print('underwater art written')


if __name__ == '__main__':
    main()
