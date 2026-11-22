"""剑祟第三阶段「坠渊」外面那一层的贴图（docs/JIAN_SUI_BOSS.md 第 10.2 节）。

场景本身不动，四周几层圆筒一直往上滚，人就觉得自己在往下掉。圆筒的贴图在这里画，都能上下、左右无缝平铺：
  - jian_sui_fall_rock.png：最外面那层不透明的岩壁。和上面深渊的石壁同一套层理（石头、安山岩、方解石、闪长岩、凝灰岩），
    但粗、不规则：一层 3～8 格厚，带斜的裂缝和零星发白的矿脉。细密的重复条纹看不出速度、还晃眼，所以不要。
  - jian_sui_fall_mist.png：中间那层半透明的雾，横着摊开的云絮（白色，靠透明度）；
  - jian_sui_fall_streak.png：最里面那层稀疏的风线；
  - jian_sui_fall_glow.png：一个柔和的圆斑，画往上飞的尘土、火星，顶上发亮的雾和底下的冷光都用它。
贴图在世界里的大小由渲染器定（岩壁一张约 64 格高），这里只管样子。

运行：uv run --python 3.12 --with numpy --with pillow python tools/jian_sui_fall_art.py
"""
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'logos_dimension', 'textures', 'environment')


def periodic_noise(w, h, cells_x, cells_y, seed):
    """可平铺的值噪声：格点在两个方向上首尾相接，三次平滑插值。"""
    r = np.random.default_rng(seed)
    grid = r.random((cells_y, cells_x))
    x = np.arange(w) * cells_x / w
    y = np.arange(h) * cells_y / h
    xi, yi = np.floor(x).astype(int), np.floor(y).astype(int)
    xf, yf = x - xi, y - yi
    xf = xf * xf * (3 - 2 * xf);yf = yf * yf * (3 - 2 * yf)
    x0, x1 = xi % cells_x, (xi + 1) % cells_x
    y0, y1 = yi % cells_y, (yi + 1) % cells_y
    a = grid[y0][:, x0];b = grid[y0][:, x1];c = grid[y1][:, x0];d = grid[y1][:, x1]
    top = a * (1 - xf) + b * xf
    bottom = c * (1 - xf) + d * xf
    return top * (1 - yf[:, None]) + bottom * yf[:, None]


def fbm(w, h, cx, cy, seed, octaves=4):
    out = np.zeros((h, w));amp = 1.0;total = 0.0
    for o in range(octaves):
        out += amp * periodic_noise(w, h, cx * 2 ** o, cy * 2 ** o, seed + o)
        total += amp;amp *= .5
    return out / total


# 和上面深渊石壁同一套层理（taixuan_abyss.STRATA），颜色取原版方块贴图的平均色
STRATA = [(125, 125, 125), (136, 136, 137), (222, 222, 218), (125, 125, 125), (188, 188, 188), (108, 109, 102), (136, 136, 137), (118, 118, 116)]


def rock():
    w, h = 128, 256  # 一张约 44 格宽、64 格高：横向约 3 像素一格，纵向 4 像素一格
    warp = (fbm(w, h, 4, 2, 11) - .5) * 26 + (fbm(w, h, 16, 8, 12) - .5) * 5
    y = np.arange(h)[:, None] + warp
    # 层厚 3～8 格（12～32 像素），用一维噪声把每层的厚度拉开；层号在一张图里首尾相接（256 像素正好走完 STRATA 一轮）
    band = np.floor((y % h) / h * len(STRATA)).astype(int) % len(STRATA)
    base = np.array(STRATA, float)[band]
    # 同一层里有大块的明暗和小的颗粒
    shade = .72 + .42 * fbm(w, h, 8, 8, 21) + .10 * (periodic_noise(w, h, 64, 128, 22) - .5)
    img = base * shade[..., None]
    # 层与层之间压一道暗线
    edge = np.abs(((y % h) / h * len(STRATA)) % 1 - .5)
    img *= (.55 + .45 * np.clip((.5 - edge) * 9, 0, 1))[..., None]
    # 每层顶上一道亮边（受光的岩檐），一层层的横线最显往上滚的速度
    lip = np.clip(1 - np.abs(((y % h) / h * len(STRATA)) % 1 - .06) * 22, 0, 1)
    img = img * (1 + .35 * lip[..., None])
    # 斜的裂缝：几条噪声的等值线，细而暗
    crack = np.abs(fbm(w, h, 6, 3, 31) - .5)
    img *= (1 - .35 * np.clip(1 - crack * 90, 0, 1))[..., None]
    # 零星发白的矿脉（淡青白，和胸口光缝一个颜色）
    vein = fbm(w, h, 12, 6, 41)
    mask = np.clip((vein - .76) * 14, 0, 1) * np.clip(1 - crack * 20, 0, 1)
    img = img * (1 - mask[..., None] * .6) + np.array([206, 236, 240]) * mask[..., None] * .6
    # 整体压暗：这是远处、雾里的岩壁
    img *= .62
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')


def mist():
    """雾：一团团横着摊开的云絮（一张约 25 格宽、24 格高，云团约 6 格宽、3 格高）。往上滚时横着的边最显速度，
    竖条纹顺着运动方向，看不出在动，所以不用。"""
    w, h = 256, 256
    big = fbm(w, h, 4, 8, 51, octaves=4)
    soft = fbm(w, h, 8, 16, 52, octaves=2)
    a = np.clip((big - .48) * 2.8, 0, 1) * (.55 + .45 * soft)
    a = a ** 1.3
    rgba = np.zeros((h, w, 4), np.uint8)
    rgba[..., :3] = 255
    rgba[..., 3] = np.clip(a * 255, 0, 255).astype(np.uint8)
    return Image.fromarray(rgba, 'RGBA')


def streak():
    """最里面那层的风线：稀疏、细、很长的竖线，两头淡，像高速掠过的气流（只在近处这一层用）。"""
    w, h = 128, 256
    lines = periodic_noise(w, h, 64, 2, 61)
    length = fbm(w, h, 64, 4, 62, octaves=2)
    a = np.clip((lines - .84) * 7, 0, 1) * np.clip((length - .42) * 3, 0, 1)
    rgba = np.zeros((h, w, 4), np.uint8)
    rgba[..., :3] = 255
    rgba[..., 3] = np.clip(a * 255, 0, 255).astype(np.uint8)
    return Image.fromarray(rgba, 'RGBA')


def glow():
    n = 32
    y, x = np.mgrid[0:n, 0:n] + .5
    d = np.hypot(x - n / 2, y - n / 2) / (n / 2)
    a = np.clip(1 - d, 0, 1) ** 2.2
    rgba = np.zeros((n, n, 4), np.uint8)
    rgba[..., :3] = 255
    rgba[..., 3] = (a * 255).astype(np.uint8)
    return Image.fromarray(rgba, 'RGBA')


def main():
    os.makedirs(OUT, exist_ok=True)
    rock().save(os.path.join(OUT, 'jian_sui_fall_rock.png'))
    mist().save(os.path.join(OUT, 'jian_sui_fall_mist.png'))
    streak().save(os.path.join(OUT, 'jian_sui_fall_streak.png'))
    glow().save(os.path.join(OUT, 'jian_sui_fall_glow.png'))
    print('jian_sui_fall_rock.png jian_sui_fall_mist.png jian_sui_fall_streak.png jian_sui_fall_glow.png')


if __name__ == '__main__':
    main()
