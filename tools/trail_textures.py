"""生成刀光的形状遮罩（docs/WEAPON_TRAILS.md）。

输出到 src/main/resources/assets/logos_dimension/textures/vfx/trail/，每张 256×128 灰度：
横向从刀光头（左，刚扫过）到尾（右，将散），纵向从刀尖外缘（上）到柄（下）。
噪声不在这里做：直接用 Logos 已有的 textures/vfx 噪声图。

运行：uv run --python 3.12 --with numpy --with pillow python tools/trail_textures.py
"""
import math
import os

import numpy as np
from PIL import Image

W, H = 256, 128
OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'logos_dimension', 'textures', 'vfx', 'trail')
rng = np.random.default_rng(20261109)
u = np.linspace(0, 1, W)[None, :].repeat(H, 0)   # 0 头 → 1 尾
v = np.linspace(0, 1, H)[:, None].repeat(W, 1)   # 0 外缘 → 1 柄


def smooth(a, b, x):
    k = np.clip((x - a) / (b - a), 0, 1)
    return k * k * (3 - 2 * k)


def value_noise_1d(n, octaves=4, seed=0):
    """沿纵向的一维噪声：每一行一个值，用来给条纹不同的长度与亮度。"""
    r = np.random.default_rng(seed)
    out = np.zeros(n)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = 4 * 2 ** o
        pts = r.random(cells + 2)
        x = np.linspace(0, cells, n)
        i = np.floor(x).astype(int)
        f = x - i
        f = f * f * (3 - 2 * f)
        out += amp * (pts[i] * (1 - f) + pts[i + 1] * f)
        total += amp
        amp *= .55
    return out / total


def fbm2(shape, cells=(8, 4), octaves=5, seed=0):
    """二维值噪声（不要求平铺：遮罩夹边取样）。"""
    r = np.random.default_rng(seed)
    h, w = shape
    out = np.zeros(shape)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cx, cy = cells[0] * 2 ** o, cells[1] * 2 ** o
        grid = r.random((cy + 2, cx + 2))
        y = np.linspace(0, cy, h)[:, None]
        x = np.linspace(0, cx, w)[None, :]
        iy, ix = np.floor(y).astype(int), np.floor(x).astype(int)
        fy, fx = y - iy, x - ix
        fy, fx = fy * fy * (3 - 2 * fy), fx * fx * (3 - 2 * fx)
        a = grid[iy, ix] * (1 - fx) + grid[iy, ix + 1] * fx
        b = grid[iy + 1, ix] * (1 - fx) + grid[iy + 1, ix + 1] * fx
        out += amp * (a * (1 - fy) + b * fy)
        total += amp
        amp *= .5
    return out / total


def save(name, a):
    a = np.clip(a, 0, 1)
    img = Image.fromarray((a * 255).astype(np.uint8), 'L').convert('RGB')
    img.save(os.path.join(OUT, name + '.png'))
    print(name)


def body():
    """主体：贴着外缘最亮、往柄渐淡；尾部被长短不一的条纹拖散，像刀风扯开的绸。"""
    streak = value_noise_1d(H, 5, 1)[:, None]
    fine = value_noise_1d(H, 3, 2)[:, None]
    length = .55 + .45 * streak
    tail = smooth(length, length - .5, u)
    across = (1 - v) ** .8 * .8 + .2 * np.exp(-((v - .1) / .2) ** 2)
    stripes = .65 + .35 * (fine * 1.4 - .2)
    inner = smooth(1.0, .6, v)
    return across * tail * stripes * inner * smooth(0, .03, u + .02)


def edge():
    """刃线：外缘一道细亮线，越往尾越细越淡，偶有细小锯齿。"""
    width = .05 + .07 * (1 - u) ** .8
    jag = (value_noise_1d(W, 4, 3)[None, :] - .5) * .03 * u
    line = np.exp(-((v - .02 - jag) / width) ** 2)
    second = .35 * np.exp(-((v - .16 - jag * 2) / (width * .6)) ** 2) * smooth(.75, .2, u)
    return (line + second) * smooth(1, .25, u) ** .7


def streaks():
    """速度线：许多细条，从头部附近起、长短不一，越靠外缘越密越亮。"""
    a = np.zeros((H, W))
    for _ in range(140):
        y = rng.random() ** 1.7 * .95
        start = rng.random() * .25
        length = .15 + rng.random() * .75
        bright = .4 + .6 * rng.random()
        thick = .004 + rng.random() * .012
        row = np.exp(-((v - y) / thick) ** 2)
        along = smooth(start, start + .05, u) * smooth(start + length, start + length * .5, u)
        a = np.maximum(a, row * along * bright * (1 - y * .5))
    return a


def flame():
    """火舌：一条条火苗从刀身往外缘、往尾部卷起，越往尾越细、被噪声撕碎。"""
    warp = fbm2((H, W), (8, 4), 5, 4)
    tear = fbm2((H, W), (16, 8), 4, 5)
    a = np.zeros((H, W))
    for k in range(9):
        y0 = .08 + k * .085 + rng.random() * .03
        rise = .18 + rng.random() * .25          # 越往尾越往外缘飘
        amp, freq, ph = .03 + rng.random() * .05, 6 + rng.random() * 8, rng.random() * 6
        length = .5 + rng.random() * .45
        y = y0 - rise * u ** 1.3 + amp * np.sin(u * freq + ph + warp * 3) * u
        th = (.05 + .03 * rng.random()) * (1 - u / length).clip(0, 1) ** .7 + .004
        tongue = np.exp(-((v - y) / th) ** 2) * smooth(length, length * .6, u) * (1 - k * .06)
        a = np.maximum(a, tongue)
    a *= smooth(.25, .6, tear + (1 - u) * .45)
    core = (1 - v) ** 1.5 * smooth(.45, .1, u) * .6
    return np.clip(np.maximum(a, core) * smooth(0, .03, u + .02) * 1.1, 0, 1)


def bolt():
    """电弧：几道折线沿刀光跳动、带细分叉，外缘最密。"""
    img = Image.new('L', (W, H), 0)
    from PIL import ImageDraw, ImageFilter
    d = ImageDraw.Draw(img)
    for k in range(5):
        y = 6 + k * 14 + rng.integers(-4, 5)
        x, pts = 0, []
        while x < W * (.55 + .4 * rng.random()):
            pts.append((x, y))
            x += rng.integers(8, 22)
            y = int(np.clip(y + rng.integers(-11, 12), 2, H * .75))
        width = 3 if k < 2 else 2
        d.line(pts, fill=255 - k * 25, width=width)
        for p in pts[1::3]:
            bx, by = p
            q = [(bx, by)]
            for _ in range(3):
                bx += rng.integers(6, 14)
                by += rng.integers(-9, 10)
                q.append((bx, by))
            d.line(q, fill=150, width=1)
    sharp = np.asarray(img) / 255.0
    soft = np.asarray(img.filter(ImageFilter.GaussianBlur(4))) / 255.0
    return np.clip(sharp + soft * 1.6, 0, 1) * smooth(1, .4, u) ** .6


def shards():
    """冰晶：沿外缘排开、尖朝尾部的三角碎片，面上明暗不一。"""
    img = Image.new('L', (W, H), 0)
    from PIL import ImageDraw, ImageFilter
    d = ImageDraw.Draw(img)
    x = 2
    while x < W * .92:
        w = rng.integers(14, 34)
        h = int(H * (.15 + .5 * rng.random()) * (1 - x / W * .6))
        top = rng.integers(0, 10)
        shade = int(255 * (.45 + .55 * rng.random()) * (1 - x / W * .5))
        d.polygon([(x, top), (x + w * 1.6, top + h * .35), (x + w * .3, top + h)], fill=shade)
        if rng.random() < .6:
            d.polygon([(x + w * .2, top + h * .25), (x + w * 1.1, top + h * .45), (x + w * .4, top + h * .8)], fill=min(255, shade + 50))
        x += int(w * (.55 + .5 * rng.random()))
    a = np.asarray(img) / 255.0
    glow = np.asarray(img.filter(ImageFilter.GaussianBlur(3))) / 255.0
    return np.clip(a * .85 + glow * .5, 0, 1) * smooth(1, .5, u) ** .5


def ink():
    """墨痕：粗重的墨团从外缘泼向尾部，边上溅出点点墨滴。"""
    n = fbm2((H, W), (7, 4), 6, 7)
    fine = fbm2((H, W), (24, 12), 3, 8)
    reach = .55 + .4 * value_noise_1d(H, 4, 9)[:, None]
    base = (1 - v) ** .9 * smooth(reach, reach - .5, u)
    blot = smooth(.42, .52, base * .8 + n * .55 - .1)
    drops = smooth(.8, .84, fine) * smooth(.95, .4, u) * smooth(.9, .3, v) * (u > .25)
    return np.clip(blot + drops, 0, 1)


def wisp():
    """柔丝：两三条交缠的光带，给奥秘、生命一类的柔和刀光做底。"""
    a = np.zeros((H, W))
    for k, (y0, amp, freq, ph) in enumerate([(.12, .07, 9, 0), (.28, .1, 6, 1.3), (.45, .12, 4.5, 2.1)]):
        y = y0 + amp * np.sin(u * freq + ph) * u
        th = .03 + .05 * u
        a = np.maximum(a, np.exp(-((v - y) / th) ** 2) * (1 - k * .2))
    return a * smooth(1, .3, u) ** .8 * smooth(0, .05, u + .02)


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for name, fn in [('slash_body', body), ('slash_edge', edge), ('slash_streaks', streaks), ('slash_flame', flame),
                     ('slash_bolt', bolt), ('slash_shards', shards), ('slash_ink', ink), ('slash_wisp', wisp)]:
        save(name, fn())
