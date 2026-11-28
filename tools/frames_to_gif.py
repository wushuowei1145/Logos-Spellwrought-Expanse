# -*- coding: utf-8 -*-
"""把客户端场景逐刻截的图拼成动图（docs/ULTIMATES.md「过场」的测试）：一张真实速度、一张慢放。

用法：uv run --with pillow python tools/frames_to_gif.py <截图目录> <输出目录> [宽度=640] [每张多少毫秒=50] [慢放倍数=4]

截图按文件名里最后一个数排序（例如 12_ultimate_film_frame_007.png 按 7）。每张是一刻时用 50 毫秒（真实速度），
半刻一张时用 25。所有帧共用一张调色板，颜色不会一帧一跳。
"""
import glob
import os
import re
import sys

from PIL import Image


def main():
    src, out_dir = sys.argv[1], sys.argv[2]
    width = int(sys.argv[3]) if len(sys.argv) > 3 else 640
    ms = int(sys.argv[4]) if len(sys.argv) > 4 else 50
    slow = int(sys.argv[5]) if len(sys.argv) > 5 else 4
    files = sorted(glob.glob(os.path.join(src, '*.png')), key=lambda f: int(re.findall(r'(\d+)', os.path.basename(f))[-1]))
    files = [f for f in files if 'error' not in os.path.basename(f)]
    if not files:
        sys.exit('没有截图：' + src)
    frames = []
    for f in files:
        im = Image.open(f).convert('RGB')
        frames.append(im.resize((width, round(im.height * width / im.width)), Image.LANCZOS))
    picks = [frames[round(i * (len(frames) - 1) / 3)] for i in range(4)]
    sheet = Image.new('RGB', (width, frames[0].height * len(picks)))
    for k, im in enumerate(picks):
        sheet.paste(im, (0, frames[0].height * k))
    palette = sheet.quantize(colors=255, method=Image.Quantize.MEDIANCUT)
    quantized = [fr.quantize(palette=palette, dither=Image.Dither.FLOYDSTEINBERG) for fr in frames]
    os.makedirs(out_dir, exist_ok=True)
    name = os.path.basename(os.path.normpath(src))
    for suffix, each in (('realtime', ms), (f'slow{slow}x', ms * slow)):
        path = os.path.join(out_dir, f'{name}_{suffix}.gif')
        durations = [each] * len(quantized)
        durations[-1] = 700
        quantized[0].save(path, save_all=True, append_images=quantized[1:], duration=durations, loop=0, optimize=False)
        print(path, os.path.getsize(path) // 1024, 'KB', len(quantized), 'frames')


if __name__ == '__main__':
    main()
