"""把开发客户端抓取的真实渲染帧按游戏刻合成 GIF，不插帧、不生成替代画面。"""
import argparse
from pathlib import Path
from PIL import Image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('frames', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for prefix, stride in [('queen_chain_break', 100), ('queen_core_fall', 100), ('ending', 150)]:
        files = sorted(args.frames.glob(prefix + '_*.png'))
        if not files:
            continue
        indices = [int(p.stem.rsplit('_', 1)[1]) for p in files]
        frames = []
        for p in files:
            with Image.open(p) as image:
                frames.append(image.convert('RGB').quantize(colors=256, method=Image.Quantize.MEDIANCUT))
        duration = [(b - a) * stride for a, b in zip(indices, indices[1:])] + [400]
        frames[0].save(args.output / (prefix + '.gif'), save_all=True, append_images=frames[1:], duration=duration, loop=0, optimize=False, disposal=2)
        print(prefix, len(files), 'frames', frames[0].size)


if __name__ == '__main__':
    main()
