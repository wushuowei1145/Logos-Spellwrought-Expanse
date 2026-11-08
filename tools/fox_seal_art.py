"""神狐聚落地下封印场地的方块美术：封印杭（两格高）与御札（贴墙的纸符，三种纸）。

沿用 fox_props_art 的像素工具与模型写法；御札贴图 32×32（比方块别的贴图细一倍，墨迹才像毛笔字），
墨迹用 fox_shrine_art 的鬼画符，看着像符咒，其实读不出来。
"""
import json
import random

from PIL import Image, ImageDraw

from fox_props_art import A, NS, ROOT, STRAW, canvas, el, fill_noise, item, loot, model, px, save, write
from fox_shrine_art import brush_glyph

CHARRED = [(22, 18, 17), (30, 24, 22), (38, 31, 28), (48, 39, 34), (58, 47, 40)]
#: 三种御札：纸色、墨色、朱印色
OFUDA = [
    ((240, 236, 222), (30, 24, 22), (190, 40, 34)),
    ((226, 196, 120), (176, 38, 30), (150, 30, 26)),
    ((246, 242, 232), (184, 44, 36), (40, 30, 28)),
]


def ofuda_texture(i):
    paper, ink, stamp = OFUDA[i]
    rnd = random.Random(1730 + i)
    t = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    for y in range(2, 30):
        for x in range(8, 24):
            k = rnd.randint(-6, 6)
            t.putpixel((x, y), tuple(max(0, min(255, c + k)) for c in paper) + (255,))
    d = ImageDraw.Draw(t)
    # 细边框，旧纸边缘发暗一点
    d.rectangle([9, 3, 22, 28], outline=tuple(int(c * 0.82) for c in paper))
    # 顶上一方朱印，印里留几道白
    d.rectangle([13, 5, 18, 10], fill=stamp)
    for (x, y) in ((14, 6), (15, 8), (17, 7), (16, 9), (14, 9)):
        t.putpixel((x, y), paper + (255,))
    # 一竖列鬼画符
    y = 12
    while y + 7 <= 28:
        brush_glyph(d, 12.5 + rnd.uniform(-0.5, 0.5), y, 7, rnd, ink)
        y += 8
    # 下沿撕得不太齐
    for x in range(8, 24):
        if rnd.random() < 0.3:
            t.putpixel((x, 29), (0, 0, 0, 0))
    return t


def stake_textures():
    side = canvas()
    fill_noise(side, CHARRED, 1801, grain=0.35)
    rnd = random.Random(1802)
    for x in (2, 5, 9, 12, 14):
        for y in range(16):
            if rnd.random() < 0.8:
                px(side, x, y, CHARRED[0])
    # 炭化的裂纹里透出一点幽蓝
    for (x, y) in ((6, 3), (6, 4), (7, 5), (11, 10), (11, 11), (10, 12)):
        px(side, x, y, (70, 150, 160))
    save(side, 'journey_seal_stake_side')
    top = canvas()
    fill_noise(top, CHARRED[1:], 1803, grain=0.3)
    for r in (2, 4, 6):
        for a in range(0, 360, 12):
            import math
            x = round(7.5 + r * math.cos(math.radians(a)))
            y = round(7.5 + r * math.sin(math.radians(a)))
            px(top, x, y, CHARRED[3])
    save(top, 'journey_seal_stake_top')
    rope = canvas()
    for y in range(16):
        for x in range(16):
            band = (x + y * 2) % 6
            px(rope, x, y, STRAW[3] if band < 2 else STRAW[2] if band < 4 else STRAW[1])
    save(rope, 'journey_seal_stake_rope')


def seal_stake():
    """封印杭：下半截黑石箍住的黑木桩，半腰一道草绳，前后各贴一张御札；上半截再贴两张，顶上一道铁箍。"""
    tex = {'wood': f'{NS}:block/journey_seal_stake_side', 'top': f'{NS}:block/journey_seal_stake_top',
           'rope': f'{NS}:block/journey_seal_stake_rope', 'base': 'minecraft:block/polished_blackstone',
           'band': 'minecraft:block/polished_blackstone_bricks', 'ofuda': f'{NS}:block/journey_ofuda_0',
           'ofuda2': f'{NS}:block/journey_ofuda_1', 'particle': f'{NS}:block/journey_seal_stake_side'}
    paper = {'north': [4, 1, 12, 15], 'south': [4, 1, 12, 15], 'west': [4, 1, 12, 15], 'east': [4, 1, 12, 15]}
    lower = [
        el((4, 0, 4), (12, 1.5, 12), 'base'),
        el((5, 0, 5), (11, 16, 11), 'wood', faces={'up': 'top', 'down': 'top'}),
        el((4.6, 10, 4.6), (11.4, 12, 11.4), 'rope'),
        el((6, 2, 4.85), (10, 9.5, 5), 'ofuda', skip=('up', 'down', 'west', 'east'), uv=paper),
        el((6, 2, 11), (10, 9.5, 11.15), 'ofuda2', skip=('up', 'down', 'west', 'east'), uv=paper),
    ]
    upper = [
        el((5, 0, 5), (11, 9, 11), 'wood', faces={'up': 'top', 'down': 'top'}),
        el((4.6, 5.5, 4.6), (11.4, 7, 11.4), 'band'),
        el((5.5, 9, 5.5), (10.5, 10, 10.5), 'top'),
        el((4.85, 0.5, 6), (5, 5.5, 10), 'ofuda2', skip=('up', 'down', 'north', 'south'), uv=paper),
        el((11, 0.5, 6), (11.15, 5.5, 10), 'ofuda', skip=('up', 'down', 'north', 'south'), uv=paper),
    ]
    model('journey_seal_stake_lower', tex, lower, cutout=True)
    model('journey_seal_stake_upper', tex, upper, cutout=True)
    write(A / 'blockstates/journey_seal_stake.json', {'variants': {
        'half=lower': {'model': f'{NS}:block/journey_seal_stake_lower'},
        'half=upper': {'model': f'{NS}:block/journey_seal_stake_upper'}}})
    item('journey_seal_stake', parent=f'{NS}:block/journey_seal_stake_lower')


def ofuda():
    variants = {}
    for i in range(3):
        save(ofuda_texture(i), f'journey_ofuda_{i}')
        name = f'journey_ofuda_{i}'
        tex = {'paper': f'{NS}:block/{name}', 'particle': f'{NS}:block/{name}'}
        els = [el((4, 1, 15.7), (12, 15, 15.9), 'paper', skip=('up', 'down', 'west', 'east'),
                  uv={'north': [4, 1, 12, 15], 'south': [12, 1, 4, 15]})]
        model(name, tex, els, cutout=True)
        for f, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)):
            v = {'model': f'{NS}:block/{name}'}
            if y:
                v['y'] = y
            variants[f'facing={f},kind={i}'] = v
    write(A / 'blockstates/journey_ofuda.json', {'variants': variants})
    item('journey_ofuda', generated_tex=f'{NS}:block/journey_ofuda_0')


def tags():
    p = ROOT / 'src/main/resources/data/minecraft/tags/block/mineable/axe.json'
    data = json.loads(p.read_text(encoding='utf-8'))
    k = f'{NS}:journey_seal_stake'
    if k not in data['values']:
        data['values'].append(k)
    p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def lang():
    names = {
        'journey_seal_stake': ('Sealing Stake', '封印杭'),
        'journey_ofuda': ('Ofuda', '御札'),
    }
    msgs = {
        'journey_seal_stake.left': ('A seal breaks. %s remain.', '一道封印断了，还剩 %s 道。'),
        'journey_seal_stake.released': ('The last seal breaks. Something stirs inside the killing stone...',
                                        '最后一道封印断了。杀生石里，有什么东西动了一下……'),
    }
    for code, idx in (('en_us', 0), ('zh_cn', 1)):
        p = A / f'lang/{code}.json'
        data = json.loads(p.read_text(encoding='utf-8'))
        for k, pair in names.items():
            data[f'block.{NS}.{k}'] = pair[idx]
            data[f'item.{NS}.{k}'] = pair[idx]
        for k, pair in msgs.items():
            data[f'block.{NS}.{k}'] = pair[idx]
        p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    stake_textures()
    ofuda()
    seal_stake()
    tags()
    lang()
    loot(['journey_ofuda'])
    print('seal art done')


if __name__ == '__main__':
    main()
