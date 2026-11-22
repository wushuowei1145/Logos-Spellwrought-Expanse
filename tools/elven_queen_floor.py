"""代码绘制腐化女王战场地板：黑石底、连贯枝藤、暗金叶脉与四瓣花冠。"""
from pathlib import Path
import json
import math
import random
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'src/main/resources/assets/logos_dimension'


def floor_model():
    return {'parent': 'minecraft:block/cube_bottom_top', 'textures': {
        'top': 'logos_dimension:block/blighted_lumen',
        'bottom': 'logos_dimension:block/blighted_lumen_side',
        'side': 'logos_dimension:block/blighted_lumen_side'}}


def draw_floor():
    rng = random.Random(94017)
    tile = Image.new('RGBA', (64, 64))
    for y in range(64):
        for x in range(64):
            grain = rng.choice((-2, -1, 0, 0, 0, 1, 2))
            stone = math.sin(x * .14 + math.sin(y * .11)) * 1.5
            tile.putpixel((x, y), (int(18 + grain + stone), int(14 + grain + stone), int(23 + grain + stone), 255))
    d = ImageDraw.Draw(tile)
    def turn(points, quarter):
        out = []
        for x, y in points:
            x, y = x - 32, y - 32
            for _ in range(quarter):
                x, y = -y, x
            out.append((x + 32, y + 32))
        return out

    vine = [(-3, 32), (7, 32), (14, 27), (16, 23), (13, 18), (17, 13), (23, 16), (27, 14), (32, 7), (32, -3)]
    for side in range(4):
        # 线条沿边界正中衔接；不用砖缝包边，连续铺设时不会变成小格子。
        path = turn(vine, side)
        d.line(path, fill=(9, 7, 14, 255), width=5)
        d.line(path, fill=(55, 19, 34, 255), width=3)
        d.line(path, fill=(112, 36, 51, 255), width=1)
        leaf = turn([(15, 22), (10, 20), (7, 13), (12, 15), (16, 19)], side)
        d.polygon(leaf, fill=(33, 24, 29, 255))
        d.line(leaf + [leaf[0]], fill=(90, 67, 47, 255), width=1)
        d.line(turn([(9, 15), (14, 20), (18, 22)], side), fill=(115, 85, 51, 255), width=1)
        # 花瓣边界采用轻微缺口，保留腐化、磨蚀后的古老感。
        petal = turn([(32, 13), (27, 20), (29, 25), (32, 29), (35, 25), (37, 20), (32, 13)], side)
        d.line(petal, fill=(65, 25, 40, 255), width=3)
        d.line(petal, fill=(128, 42, 57, 255), width=1)
        d.line(turn([(32, 17), (32, 25)], side), fill=(75, 48, 46, 255), width=1)
        d.point(turn([(30, 17)], side)[0], fill=(19, 14, 23, 255))
        d.line(turn([(18, 31), (22, 31), (24, 29)], side), fill=(62, 46, 40, 255), width=1)
    d.polygon([(32, 28), (36, 32), (32, 36), (28, 32)], fill=(65, 23, 39, 255))
    d.line([(32, 29), (35, 32), (32, 35), (29, 32), (32, 29)], fill=(125, 91, 56, 255))
    d.point((32, 32), fill=(163, 54, 67, 255))
    side = Image.new('RGBA', (32, 32))
    for y in range(32):
        for x in range(32):
            n = rng.randrange(-2, 3)
            seam = abs((x + int(2 * math.sin(y * .2))) % 31 - 15) < 1
            side.putpixel((x, y), ((38 + n, 17 + n, 31 + n, 255) if seam else (17 + n, 13 + n, 22 + n, 255)))
    return tile, side


def export():
    tile, side = draw_floor()
    folder = ART / 'textures/block'
    tile.save(folder / 'blighted_lumen.png')
    side.save(folder / 'blighted_lumen_side.png')
    (ART / 'models/block/blighted_lumen.json').write_text(json.dumps(floor_model(), indent=2) + '\n', encoding='utf-8')
    return tile


if __name__ == '__main__':
    tile = export()
    out = ROOT.parents[1] / 'outputs/elven-boss/floor-v1'
    out.mkdir(parents=True, exist_ok=True)
    preview = Image.new('RGBA', (512, 512))
    for y in range(8):
        for x in range(8):
            preview.paste(tile, (x * 64, y * 64))
    preview.resize((1024, 1024), Image.Resampling.NEAREST).convert('RGB').save(out / 'floor-texture-preview.png')
    print(out / 'floor-texture-preview.png')
