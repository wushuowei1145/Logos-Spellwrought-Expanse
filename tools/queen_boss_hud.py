"""用代码绘制女王血条：暗金枝藤、猩红液面与三瓣冠；不烘焙文字。"""
import math
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/assets/logos_dimension/textures/gui'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    frame = Image.new('RGBA', (352, 60))
    d = ImageDraw.Draw(frame)
    # 细框围住液槽，枝藤留出标题与阶段标记的净空。
    d.rounded_rectangle((28, 23, 324, 34), radius=3, fill=(17, 9, 17, 235), outline=(63, 41, 42, 255))
    d.line([(32, 22), (320, 22)], fill=(151, 118, 79, 255))
    d.line([(34, 35), (318, 35)], fill=(104, 77, 56, 255))
    d.line([(36, 24), (316, 24)], fill=(226, 195, 136, 190))
    for side in (-1, 1):
        def point(x, y): return (176 + side * x, y)
        vine = [point(161 - i * 1.7, 29 + math.sin(i * .34) * (7 - i * .10)) for i in range(53)]
        d.line(vine, fill=(110, 80, 57, 255), width=2)
        d.line([(x, y - 1) for x, y in vine], fill=(192, 157, 103, 230))
        for i in range(2, 43, 6):
            x, y = vine[i]; sign = -1 if i % 12 == 2 else 1
            d.polygon([(x, y), (x + side * 3, y + sign * 6), (x + side * 9, y + sign * 8), (x + side * 7, y + sign * 2)], fill=(83, 79, 56, 255), outline=(155, 131, 85, 255))
        cx = 176 + side * 153
        d.polygon([(cx, 20), (cx + 6, 28), (cx, 37), (cx - 6, 28)], fill=(57, 19, 32, 255), outline=(179, 141, 91, 255))
        d.polygon([(cx, 24), (cx + 2, 28), (cx, 32), (cx - 2, 28)], fill=(226, 82, 102, 255))
        d.line([point(51, 43), point(84, 43), point(92, 46)], fill=(113, 89, 65, 220))
    # 顶端的小冠与下面的细根不碰正文。
    for x in (-7, 0, 7):
        cx = 176 + x
        d.polygon([(cx, 0 if x == 0 else 2), (cx + 2, 5), (cx, 8), (cx - 2, 5)], fill=(212, 180, 117, 255))
    frame.save(OUT / 'queen_boss_frame.png')
    fill = Image.new('RGBA', (352, 60))
    f = ImageDraw.Draw(fill)
    colors = [(232, 105, 120), (210, 66, 91), (178, 35, 65), (142, 23, 52), (116, 20, 45), (84, 16, 36), (58, 14, 29)]
    for row, color in enumerate(colors):
        f.line((33, 25 + row, 319, 25 + row), fill=(*color, 255))
    for x in range(37, 317, 19):
        f.line([(x, 28), (x + 4, 27), (x + 8, 29), (x + 14, 28)], fill=(244, 133, 136, 80))
    fill.save(OUT / 'queen_boss_fill.png')


if __name__ == '__main__':
    main()
