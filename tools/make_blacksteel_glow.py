"""从黑钢长剑的骨架贴图取出剑身铭文（LOGOS 五枚原版附魔字形）的像素，生成发光层贴图。

铭文在 blacksteel_blade_00 的前后两面，颜色固定为 (147, 169, 174)，透明度随字形。输出同尺寸贴图：
铭文像素白色不透明，周围一圈很淡的光晕（只在剑身那一面的 UV 范围内）；其余透明。运行时按元素颜色叠加。

用法：uv run --with pillow python tools/make_blacksteel_glow.py
"""
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
RUNE = (147, 169, 174)
CUBE = 'blacksteel_blade_00'

weapon = json.loads((ASSETS / 'combat/weapons/blacksteel_longsword.json').read_text(encoding='utf-8'))
source = Image.open(ASSETS / 'textures/item/blacksteel_longsword_rig.png').convert('RGBA')
out = Image.new('RGBA', source.size, (0, 0, 0, 0))
cube = next(c for c in weapon['cubes'] if c['name'] == CUBE)
count = 0
for face in ('north', 'south'):
    x0, y0, x1, y1 = (int(v) for v in cube['uv'][face])
    runes = {(x, y) for x in range(x0, x1) for y in range(y0, y1)
             if source.getpixel((x, y))[:3] == RUNE and source.getpixel((x, y))[3] > 0}
    count += len(runes)
    for x in range(x0, x1):
        for y in range(y0, y1):
            if (x, y) in runes:
                out.putpixel((x, y), (255, 255, 255, source.getpixel((x, y))[3]))
            elif any((x + dx, y + dy) in runes for dx in (-1, 0, 1) for dy in (-1, 0, 1)):
                out.putpixel((x, y), (255, 255, 255, 70))
assert count > 0, '没找到铭文像素'
path = ASSETS / 'textures/item/blacksteel_longsword_rig_glow.png'
out.save(path)
print(f'铭文像素 {count}，写入 {path.relative_to(ROOT).as_posix()}')
