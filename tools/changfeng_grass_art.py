"""代码绘制长风平原的宽叶草簇；三种株型共用原版双面交叉片，保持面数。"""
from pathlib import Path
import json
import math
import random
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent / 'src/main/resources/assets/logos_dimension'


def texture(variant, tall):
    rng = random.Random(22031 + variant * 53 + int(tall) * 991)
    height = 32 if tall else 16
    image = Image.new('RGBA', (16, height))
    draw = ImageDraw.Draw(image)
    # 每叶是二至四像素宽的弯曲带；短叶铺开根部，长叶撑起上层，留出斜向缺口。
    for n in range(9 if tall else 7):
        root = rng.choice([3, 6, 8, 10, 13]) + rng.uniform(-1, 1)
        length = rng.uniform(21, 27) if tall and n < 3 else rng.uniform(9, 20) if tall else rng.uniform(7, 14)
        reach = (1 if n % 2 else -1) * rng.uniform(3, 7)
        width = rng.uniform(2.5, 3.7)
        shade = rng.randrange(144, 214)
        centers, left, right = [], [], []
        for step in range(17):
            t = step / 16
            x = root + reach * t ** 1.5
            y = height - 1 - length * t + abs(reach) * t ** 3 * .3
            half = width * .5 * math.sin(math.pi * t) ** .7 + .65 * (1 - t)
            centers.append((round(x), round(y)))
            left.append((round(x - half), round(y)))
            right.append((round(x + half), round(y)))
        draw.polygon(left + right[::-1], fill=(round(shade * .73), round(shade * .94), round(shade * .42), 255))
        # 只添一条低对比叶脉；避免把草叶画成发白的细杆。
        vein = (round(shade * .84), min(238, round(shade * 1.03)), round(shade * .51), 255)
        draw.line(centers[2:14], fill=vein, width=1)
    return image


def model(texture_id):
    uv = [0, 0, 16, 16]
    # 两张双面卡片共四面；双层高草共八面，与原版高草保持一致。
    elements = []
    for angle in [-45, 45]:
        elements.append({
            'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False,
            'rotation': {'origin': [8, 0, 8], 'axis': 'y', 'angle': angle},
            'faces': {face: {'uv': uv, 'texture': '#plant', 'tintindex': 0} for face in ['north', 'south']}
        })
    return {'parent': 'minecraft:block/block', 'ambientocclusion': False,
            'render_type': 'minecraft:cutout', 'textures': {'plant': texture_id, 'particle': texture_id}, 'elements': elements}


def main():
    textures = ROOT / 'textures/block/changfeng'
    models = ROOT / 'models/block/changfeng'
    textures.mkdir(parents=True, exist_ok=True)
    models.mkdir(parents=True, exist_ok=True)
    for variant in range(3):
        for tall in [False, True]:
            name = ('tall' if tall else 'short') + f'_{variant}'
            image = texture(variant, tall)
            parts = [('lower', False), ('upper', True)] if tall else [('', None)]
            for suffix, upper in parts:
                part = name + ('_' + suffix if suffix else '')
                pixels = image if upper is None else image.crop((0, 0 if upper else 16, 16, 16 if upper else 32))
                pixels.save(textures / f'{part}.png')
                path = models / f'{part}.json'
                path.write_text(json.dumps(model(f'logos_dimension:block/changfeng/{part}'), indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    main()
