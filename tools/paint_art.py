"""油漆系统的美术资源：画刷/调色盘物品图标、调色盘方块贴图与模型。

python tools/paint_art.py

全部代码绘制（PIL），16×16 像素画；调色盘方块是 3D 木板 + 可染色的颜料池，
物品图标单独出图，避免方块模型直接当物品图标。
"""
import json
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def brush_sprite():
    """斜放画刷：木柄、金属箍、刷毛，刷尖带一点漆。"""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 刷毛（右上），逐行收窄
    d.rectangle([12, 2, 14, 2], fill=(58, 46, 38, 255))
    d.rectangle([11, 3, 15, 4], fill=(74, 58, 46, 255))
    d.rectangle([11, 5, 14, 5], fill=(58, 46, 38, 255))
    d.rectangle([11, 6, 13, 6], fill=(74, 58, 46, 255))
    d.point((12, 3), fill=(96, 78, 62, 255))
    d.point((14, 3), fill=(96, 78, 62, 255))
    # 刷尖的漆
    d.rectangle([13, 1, 14, 1], fill=(196, 72, 60, 255))
    d.point((15, 2), fill=(196, 72, 60, 255))
    # 金属箍
    d.rectangle([8, 4, 11, 7], fill=(138, 138, 146, 255))
    d.rectangle([8, 4, 8, 7], fill=(188, 188, 196, 255))
    d.rectangle([8, 7, 11, 7], fill=(96, 96, 104, 255))
    # 木柄（左下），描边 + 亮面 + 高光
    d.line([(2, 14), (9, 7)], fill=(74, 46, 26, 255), width=4)
    d.line([(3, 13), (8, 8)], fill=(158, 110, 62, 255), width=2)
    d.point((4, 12), fill=(196, 148, 92, 255))
    d.point((5, 11), fill=(196, 148, 92, 255))
    d.point((6, 10), fill=(196, 148, 92, 255))
    return img


def palette_sprite():
    """木调色板：椭圆板身、拇指孔、几团颜料。"""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([0, 3, 15, 14], fill=(104, 70, 40, 255))
    d.ellipse([1, 4, 14, 13], fill=(198, 160, 104, 255))
    d.ellipse([2, 5, 13, 12], fill=(214, 178, 120, 255))
    # 拇指孔（透明 = 孔）
    d.ellipse([3, 8, 5, 11], fill=(0, 0, 0, 0))
    d.arc([3, 8, 5, 11], 180, 360, fill=(150, 112, 68, 255))
    # 颜料团
    for (x, y, c) in [(8, 6, (205, 60, 52)), (10, 6, (60, 96, 200)),
                      (9, 8, (86, 160, 74)), (11, 9, (222, 176, 58)),
                      (8, 10, (150, 70, 168))]:
        d.ellipse([x - 1, y - 1, x + 1, y + 1], fill=c + (255,))
        d.point((x, y - 1), fill=tuple(min(255, v + 60) for v in c) + (255,))
    return img


def board_texture():
    """调色盘木板面：浅木色 + 细木纹，四角略深。"""
    img = Image.new('RGBA', (16, 16))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], fill=(205, 168, 110))
    for y in (2, 5, 8, 11, 14):
        d.line([(0, y), (15, y)], fill=(188, 150, 96, 255))
    for x in (4, 9, 13):
        d.line([(x, 0), (x, 15)], fill=(198, 158, 102, 255))
    d.rectangle([0, 0, 15, 15], outline=(168, 132, 82, 255))
    return img


def block_model():
    board = {f: {'texture': '#board'} for f in ('north', 'south', 'east', 'west', 'up', 'down')}
    paint = {f: {'texture': '#paint', 'tintindex': 0} for f in ('north', 'south', 'east', 'west', 'up', 'down')}
    return {
        'parent': 'minecraft:block/block',
        'textures': {
            'board': 'logos_dimension:block/paint_palette',
            'paint': 'logos_dimension:block/paint_surface',
            'particle': 'logos_dimension:block/paint_palette',
        },
        'elements': [
            # 板身与两端圆角
            {'from': [1, 0, 2], 'to': [15, 1, 13], 'faces': dict(board)},
            {'from': [0, 0, 4], 'to': [1, 1, 11], 'faces': dict(board)},
            {'from': [15, 0, 4], 'to': [16, 1, 11], 'faces': dict(board)},
            # 可染色的颜料池
            {'from': [4, 1, 4], 'to': [12, 1.2, 10], 'faces': dict(paint)},
        ],
    }


def main():
    brush_sprite().save(ASSETS / 'textures/item/paint_brush.png')
    palette_sprite().save(ASSETS / 'textures/item/paint_palette.png')
    board_texture().save(ASSETS / 'textures/block/paint_palette.png')
    write(ASSETS / 'models/item/paint_brush.json', {
        'parent': 'minecraft:item/handheld',
        'textures': {'layer0': 'logos_dimension:item/paint_brush'},
    })
    write(ASSETS / 'models/item/paint_palette.json', {
        'parent': 'minecraft:item/generated',
        'textures': {'layer0': 'logos_dimension:item/paint_palette'},
    })
    write(ASSETS / 'items/paint_palette.json', {
        'model': {'type': 'minecraft:model', 'model': 'logos_dimension:item/paint_palette'},
    })
    write(ASSETS / 'models/block/paint_palette.json', block_model())
    print('paint art written')


if __name__ == '__main__':
    main()
