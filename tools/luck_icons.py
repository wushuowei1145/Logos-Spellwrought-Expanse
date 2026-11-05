"""运势图标（18×18，与 Logos 的 mob_effect 同尺寸）：每种运一个小图案。

吉的一面用这种运自己的颜色，下面压一道暖光；凶的一面同一个图案褪成灰紫，斜压一道裂痕。
失物运没有凶的一面。
"""
from PIL import Image

from fox_props_art import A

# 12×12 图案：# 主色，+ 亮面，. 暗面，空格透明
ICONS = {
    'wealth': (0xE8B53A, [  # 方孔钱
        '    ####    ',
        '  ##++++##  ',
        ' #++++++++# ',
        ' #++.  .+.# ',
        '#+++.  .+..#',
        '#+++    +..#',
        '#+++    +..#',
        '#+++.  .+..#',
        ' #++.  .+.# ',
        ' #+......#  ',
        '  ##....##  ',
        '    ####    ']),
    'market': (0x5FB6C9, [  # 上扬的折线与箭头
        '        ####',
        '         ++#',
        '        +#+#',
        '       +# .#',
        '      +#    ',
        ' +   +#     ',
        '+#+ +#      ',
        '#.#+#       ',
        '# .#.       ',
        '#           ',
        '#.          ',
        '.           ']),
    'war': (0xD8513E, [  # 斜举的刀
        '          ++',
        '         +##',
        '        +##.',
        '       +##. ',
        '      +##.  ',
        '     +##.   ',
        '    +##.    ',
        ' #.+##.     ',
        ' .###.      ',
        '  .#.       ',
        ' ##.#.      ',
        '##   .      ']),
    'spell': (0x9A6BE0, [  # 四芒星与小星
        '     +      ',
        '     #      ',
        '    +#.     ',
        '    +#.     ',
        ' ++++##...  ',
        '    +#.     ',
        '    +#.  +  ',
        '     #  +#. ',
        '     .   .  ',
        '  +         ',
        ' +#.        ',
        '  .         ']),
    'harvest': (0x8DBB45, [  # 一穗谷子
        '      +     ',
        '     +#.    ',
        '    +#.+.   ',
        '     .+#.   ',
        '    +#.#.   ',
        '     .+#.   ',
        '    +#.#.   ',
        '     .+#.   ',
        '      #.    ',
        '      #     ',
        '      #     ',
        '      .     ']),
    'alchemy': (0x46B59A, [  # 长颈烧瓶
        '    ####    ',
        '     +.     ',
        '     +.     ',
        '     +.     ',
        '    ++..    ',
        '    +#.     ',
        '   ++#..    ',
        '  ++###..   ',
        ' ++#####..  ',
        ' +#######.  ',
        ' +#######.  ',
        '  .......   ']),
    'travel': (0xC79A5B, [  # 草鞋
        '   ++       ',
        '  +##.      ',
        '  +##.      ',
        '  +##.      ',
        '  +##.      ',
        '  +###..    ',
        '  +#####..  ',
        '  +#######. ',
        '  +#######. ',
        '  .......   ',
        '            ',
        '            ']),
    'lost': (0xD9C7A0, [  # 扎口的小钱袋
        '    #  #    ',
        '    ####    ',
        '     ..     ',
        '    ####    ',
        '   +####.   ',
        '  +#####..  ',
        ' +#######.. ',
        ' +#######.. ',
        ' +#######.. ',
        ' +#######.. ',
        '  +#####..  ',
        '   ......   ']),
    'meeting': (0xE58FB0, [  # 狐脸
        '#.        .#',
        '##.      .##',
        '#+#.    .#.#',
        '#++######..#',
        ' #++++....# ',
        ' #+ ++..  # ',
        ' #++++..... ',
        '  #++++...# ',
        '   #++...#  ',
        '    #+..#   ',
        '     ##     ',
        '            ']),
}

DULL = (0x4A, 0x3A, 0x52)


def rgb(color):
    return (color >> 16) & 255, (color >> 8) & 255, color & 255


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def draw(rows, color, bad):
    """把图案放到 18×18 中央（留 3 格边），描一圈深边，让它压在亮背景上也看得清。"""
    base = mix(rgb(color), DULL, 0.72) if bad else rgb(color)
    light = mix(base, (255, 255, 255), 0.25 if bad else 0.45)
    dark = mix(base, (0, 0, 0), 0.35)
    shades = {'#': base, '+': light, '.': dark}
    icon = Image.new('RGBA', (18, 18), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row.ljust(12)):
            if ch in shades:
                icon.putpixel((x + 3, y + 3), (*shades[ch], 255))
    outline = mix(base, (0, 0, 0), 0.75)
    filled = {(x, y) for x in range(18) for y in range(18) if icon.getpixel((x, y))[3]}
    for x, y in filled:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            at = (x + dx, y + dy)
            if at not in filled and 0 <= at[0] < 18 and 0 <= at[1] < 18:
                icon.putpixel(at, (*outline, 255))
    if bad:
        # 从右上到左下一道裂痕
        crack = (214, 72, 66, 255)
        for i in range(12):
            x, y = 14 - i, 3 + i
            if (i // 2) % 2 == 0 or i % 2 == 0:
                icon.putpixel((x, y), crack)
    return icon


def main():
    out = A / 'textures/mob_effect'
    out.mkdir(parents=True, exist_ok=True)
    for kind, (color, rows) in ICONS.items():
        assert len(rows) == 12 and all(len(r) <= 12 for r in rows), kind
        draw(rows, color, False).save(out / f'{kind}_luck.png')
        if kind != 'lost':
            draw(rows, color, True).save(out / f'{kind}_misfortune.png')


if __name__ == '__main__':
    main()
