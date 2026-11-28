"""拉塞塔的效果图标（18×18，与 Logos 的 mob_effect 同尺寸，画法同 luck_icons.py）。

绯翼余温：一只展开的珊瑚红龙翼（三根翼骨、下缘扇形），右下角叠一簇金色的小火——她守了你一夜，翅膀底下的余温。
"""
from PIL import Image

from luck_icons import A, draw

WING = (0xE0566B, [
    '##          ',
    '#+##        ',
    '#++.##      ',
    '#+++..###   ',
    '#++++...+## ',
    '#+++++.+++.#',
    '#++.++++.++#',
    '#+. #+. #+.#',
    '#.   #.  #. ',
    ' #    #   # ',
    '            ',
    '            '])

FLAME = (0xFFA640, [
    '            ',
    '            ',
    '            ',
    '            ',
    '            ',
    '            ',
    '            ',
    '         +  ',
    '        +#  ',
    '       +##. ',
    '       +#+. ',
    '        ..  '])


def main():
    out = A / 'textures/mob_effect'
    out.mkdir(parents=True, exist_ok=True)
    icon = draw(WING[1], WING[0], False)
    flame = draw(FLAME[1], FLAME[0], False)
    icon.alpha_composite(flame)
    icon.save(out / 'lacerta_warmth.png')


if __name__ == '__main__':
    main()
