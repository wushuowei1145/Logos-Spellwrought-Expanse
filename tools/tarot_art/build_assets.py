"""生成塔罗牌的牌号牌名层、物品模型、物品定义和语言条目。牌画本身由 render.py 写。

用法（仓库根目录）：uv run --quiet --with pillow --with numpy python tools/tarot_art/build_assets.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from registry import CARDS  # noqa: E402

ASSETS = Path(__file__).resolve().parents[2] / 'src/main/resources/assets/logos_dimension'

# 5 像素高的位图字。牌框的牌号区 [54,74)×[6,12)，牌名区 [22,106)×[213,220)
FONT = {
    'A': ['.#.', '#.#', '###', '#.#', '#.#'],
    'B': ['##.', '#.#', '##.', '#.#', '##.'],
    'C': ['.##', '#..', '#..', '#..', '.##'],
    'D': ['##.', '#.#', '#.#', '#.#', '##.'],
    'E': ['###', '#..', '##.', '#..', '###'],
    'F': ['###', '#..', '##.', '#..', '#..'],
    'G': ['.##', '#..', '#.#', '#.#', '.##'],
    'H': ['#.#', '#.#', '###', '#.#', '#.#'],
    'I': ['###', '.#.', '.#.', '.#.', '###'],
    'J': ['..#', '..#', '..#', '#.#', '.#.'],
    'K': ['#.#', '#.#', '##.', '#.#', '#.#'],
    'L': ['#..', '#..', '#..', '#..', '###'],
    'M': ['#...#', '##.##', '#.#.#', '#...#', '#...#'],
    'N': ['#..#', '##.#', '#.##', '#..#', '#..#'],
    'O': ['.#.', '#.#', '#.#', '#.#', '.#.'],
    'P': ['##.', '#.#', '##.', '#..', '#..'],
    'Q': ['.#.', '#.#', '#.#', '##.', '.##'],
    'R': ['##.', '#.#', '##.', '#.#', '#.#'],
    'S': ['.##', '#..', '.#.', '..#', '##.'],
    'T': ['###', '.#.', '.#.', '.#.', '.#.'],
    'U': ['#.#', '#.#', '#.#', '#.#', '###'],
    'V': ['#.#', '#.#', '#.#', '#.#', '.#.'],
    'W': ['#...#', '#...#', '#.#.#', '##.##', '#...#'],
    'X': ['#.#', '#.#', '.#.', '#.#', '#.#'],
    'Y': ['#.#', '#.#', '.#.', '.#.', '.#.'],
    'Z': ['###', '..#', '.#.', '#..', '###'],
    '0': ['###', '#.#', '#.#', '#.#', '###'],
    ' ': ['..', '..', '..', '..', '..'],
}
LIGHT = (229, 199, 132, 255)
SHADE = (93, 64, 44, 255)


def text_width(text):
    return sum(len(FONT[ch][0]) + 1 for ch in text) - 1


def put_text(im, text, cx, top):
    """在 cx 处居中写字；下方压一行暗色，和牌框烫金的做法一致。"""
    px = im.load()
    x = cx - text_width(text) // 2
    for ch in text:
        glyph = FONT[ch]
        for j, row in enumerate(glyph):
            for i, v in enumerate(row):
                if v == '#':
                    if px[x + i, top + j + 1][3] == 0:
                        px[x + i, top + j + 1] = SHADE
        for j, row in enumerate(glyph):
            for i, v in enumerate(row):
                if v == '#':
                    px[x + i, top + j] = LIGHT
        x += len(glyph[0]) + 1


def labels_image(numeral, title):
    im = Image.new('RGBA', (128, 224), (0, 0, 0, 0))
    put_text(im, numeral, 64, 6)
    put_text(im, title, 64, 214)
    return im


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def card_model(card_id):
    tex = f'logos_dimension:item/tarot/major/{card_id}'
    return {'parent': 'logos_dimension:item/tarot/card_blank',
            'textures': {'artwork': tex, 'glow': f'{tex}_glow', 'labels': f'{tex}_labels'}}


def merge_lang(locale, entries):
    """把牌名补进语言文件，已有的键原位更新，其余条目的顺序不动。"""
    path = ASSETS / 'lang' / f'{locale}.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    data.update(entries)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def main():
    zh = {'itemGroup.logos_dimension.tarot': '灵猫塔罗'}
    en = {'itemGroup.logos_dimension.tarot': 'Mystical Cat Tarot'}
    for card_id, (numeral, title, zh_name, _) in CARDS.items():
        (ASSETS / 'textures/item/tarot/major').mkdir(parents=True, exist_ok=True)
        labels_image(numeral, title).save(ASSETS / f'textures/item/tarot/major/{card_id}_labels.png', optimize=True)
        write_json(ASSETS / f'models/item/tarot/major/{card_id}.json', card_model(card_id))
        write_json(ASSETS / f'items/tarot_{card_id}.json',
                   {'model': {'type': 'minecraft:model', 'model': f'logos_dimension:item/tarot/major/{card_id}'}})
        zh[f'item.logos_dimension.tarot_{card_id}'] = f'塔罗牌 · {zh_name}'
        en_name = title.title().replace(' Of ', ' of ')
        en[f'item.logos_dimension.tarot_{card_id}'] = f'Tarot: {en_name}'
    merge_lang('zh_cn', zh)
    merge_lang('en_us', en)
    print(f'写入 {len(CARDS)} 张牌的牌名层、模型、物品定义和语言条目')


if __name__ == '__main__':
    main()
