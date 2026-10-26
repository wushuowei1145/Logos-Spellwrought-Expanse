"""风里草叶的专用贴图（代码绘制）。

叠加层不再从牧草贴图里抠一条竖线（那样看起来像麦秆），
而是用一张专门的窄叶：根部深、叶尖浅、一侧有高光，边缘带一点羽化。
尺寸 16×64，竖着贴在一个四边形上，配合顶点色与片元渐变使用。
"""
from pathlib import Path
import json

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/logos_dimension"
W, H = 16, 64


def main():
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    px = im.load()
    for y in range(H):
        t = y / (H - 1)                     # 0 = 叶尖, 1 = 叶根
        # 叶宽：根部宽、叶尖收成一点
        half = max(0.8, (2.6 - 2.0 * t))
        # 叶身随高度轻微弯曲
        bend = (t - 0.5) ** 2 * 3.2 - 0.8
        cx = W / 2 + bend
        for x in range(W):
            d = abs(x - cx)
            if d > half:
                continue
            edge = 1.0 - (d / half) ** 3       # 边缘羽化
            # 颜色：根部深绿 -> 叶尖黄绿；中间是叶身
            r = int(58 + 74 * t)
            g = int(96 + 68 * t)
            b = int(38 + 34 * t)
            # 左侧一道高光
            if x < cx:
                r, g, b = int(r * 1.16), int(g * 1.12), int(b * 1.05)
            alpha = int(255 * min(1.0, edge * 1.25))
            # 叶尖一像素更亮
            if t < 0.06:
                r, g, b = min(255, r + 26), min(255, g + 24), min(255, b + 18)
            px[x, y] = (min(255, r), min(255, g), min(255, b), alpha)
    out = A / "textures/block" / "wind_blade.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    im.save(out)
    print("草叶贴图：", out.name, im.size)


if __name__ == "__main__":
    main()
