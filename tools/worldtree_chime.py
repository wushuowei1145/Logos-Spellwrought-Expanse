"""世界树之弓锁定时的“叮”：代码合成的清脆短音，第 n 次锁定用第 n 个音（C4 起按大调音阶往上，第 16 个是 D6）。

用法（需要 numpy、soundfile）：
    uv run --with numpy --with soundfile python tools/worldtree_chime.py

写 `sounds/item/worldtree_bow/lock_01.ogg` … `lock_16.ogg`（单声道，游戏里随距离衰减）。

音色是很轻的玻璃叮（维护者试玩：原来太响，要非常淡而清脆的一声）：基音短，二、三泛音相对亮一些、衰减更快，
第四条略偏离整数倍给一点晶莹感；整体 0.45 秒就收尾，文件峰值 -14 dBFS，游戏里再按 0.5 的音量播。
起音是 3 毫秒的升余弦，没有噪声、没有咔嗒；每条泛音同时起音。
"""
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'src/main/resources/assets/logos_dimension/sounds/item/worldtree_bow'
RATE = 44100
LENGTH = .45
COUNT = 16
# 大调音阶的半音步：C D E F G A B C …
MAJOR = [0, 2, 4, 5, 7, 9, 11]
# (频率倍数, 相对幅度, 衰减时间常数 秒)
PARTIALS = [(1.0, 1.0, .16), (2.0, .55, .09), (3.0, .3, .06), (4.07, .14, .04), (5.98, .05, .025)]


def frequency(n):
    """第 n 个音（从 0 起）：C4 往上按大调音阶。"""
    octave, step = divmod(n, len(MAJOR))
    return 261.63 * 2 ** ((12 * octave + MAJOR[step]) / 12)


def chime(f):
    t = np.arange(int(RATE * LENGTH)) / RATE
    out = np.zeros_like(t)
    for ratio, amp, tau in PARTIALS:
        hz = f * ratio
        if hz > RATE * .45:
            continue
        # 高音的泛音更短，音高越高整体越短一点
        decay = tau * (261.63 / f) ** .25
        out += amp * np.exp(-t / decay) * np.sin(2 * np.pi * hz * t)
    attack = np.clip(t / .003, 0, 1)
    out *= .5 - .5 * np.cos(np.pi * attack)
    # 尾巴 60 毫秒淡出到零，不留截断
    tail = np.clip((LENGTH - t) / .06, 0, 1)
    out *= tail
    return out / np.max(np.abs(out)) * 10 ** (-14 / 20)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for n in range(COUNT):
        f = frequency(n)
        sf.write(OUT / f'lock_{n + 1:02d}.ogg', chime(f).astype(np.float32), RATE, format='OGG', subtype='VORBIS')
        print(f'lock_{n + 1:02d}  {f:7.1f} Hz')


if __name__ == '__main__':
    main()
