"""魔女之森乐器的合成音源（模组本体自带，体积很小）：每件采样乐器都在这里合成一套，写成单声道 ogg 和采样表
assets/logos_dimension/instruments/<乐器>.json（quality = synth）。装了「音乐厅音源」资源包时，包里同名的表换成录音
（tools/instrument_sources.py）；不装就用这里的合成音源。管风琴不在这里，它在客户端按音栓现场合成。

    uv run --no-project --with numpy --with scipy python tools/instrument_samples.py --ffmpeg <ffmpeg 路径> [--only 乐器 ...] [--demo <试听目录>]

做法：
- 每隔小三度（3 个半音）合成一个采样，游戏里取最近的采样变调，变调不超过 ±1.5 个半音。
- 敲击、拨弦（钢琴、竖琴、钟琴、钢片琴、管钟、大键琴、定音鼓）用模态合成：一个音 = 若干个分音各自按指数衰减，
  加上起音的轻响、共鸣箱的频响和很短的房间混响。
- 持续音（小提琴、中提琴、大提琴、低音提琴、长笛、双簧管、巴松、小号）合成一段起音和一段 1.2 秒的无缝循环：
  载波、5 Hz 的揉弦（6 个周期）、一个周期的慢摆动都取整数个周期，弓噪、气声用逆 FFT 做成以循环长度为周期的噪声，
  所以循环终点正好接上起点。起音只是音量和亮度慢慢推起来，没有噪声冲击（第一版手持乐器像鼓的那一下就是它）。
- 每个采样放大到峰值 -1 dBFS 编码；播放音量按 K 计权响度写进采样表（与录音音源同一套标准，力度 127 时约 -16 dB）。
- 随机数种子固定、ogg 用固定流序号编码，重复运行字节不变。
"""
import argparse
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import instrument_sources as rec  # noqa: E402  共用编码、响度、采样表格式

RATE = rec.SR
ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension"


def freq(midi):
    return 440.0 * 2.0 ** ((midi - 69) / 12.0)


def lerp(a, b, t):
    return a + (b - a) * min(1.0, max(0.0, t))


def spectral_shape(signal, gains):
    """频域乘一条频响曲线（gains(freqs) -> 线性增益），用来模拟共鸣箱。"""
    n = len(signal)
    size = 1 << int(np.ceil(np.log2(n * 2)))
    spec = np.fft.rfft(signal, size)
    f = np.fft.rfftfreq(size, 1.0 / RATE)
    spec *= gains(f)
    return np.fft.irfft(spec, size)[:n]


def peak(f, center, q, gain_db):
    """一个共振峰（对数频率上的高斯形状）。"""
    x = np.log2(np.maximum(f, 1.0) / center)
    return 10 ** (gain_db / 20.0 * np.exp(-(x * q) ** 2))


def room(signal, rng, length=0.45, wet=0.10, damp=4500.0):
    """很短的房间混响：指数衰减的低通噪声做冲激响应，FFT 卷积。"""
    n_ir = int(RATE * length)
    t = np.arange(n_ir) / RATE
    ir = rng.standard_normal(n_ir) * np.exp(-t * 6.9 / length)
    ir = spectral_shape(ir, lambda f: 1.0 / (1.0 + (f / damp) ** 2))
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
    size = 1 << int(np.ceil(np.log2(len(signal) + n_ir)))
    tail = np.fft.irfft(np.fft.rfft(signal, size) * np.fft.rfft(ir, size), size)[:len(signal)]
    return signal * (1.0 - wet) + tail * wet * 2.0


def noise_burst(rng, n, band_lo, band_hi, decay_s):
    t = np.arange(n) / RATE
    burst = rng.standard_normal(n) * np.exp(-t / decay_s)
    return spectral_shape(burst, lambda f: ((f > band_lo) & (f < band_hi)).astype(float))


def head_rms(signal):
    return np.sqrt(np.mean(signal[: int(RATE * 0.4)] ** 2)) + 1e-9


def finish(signal, fade_s=0.25):
    """首尾淡入淡出，避免爆音。"""
    fade_in = int(RATE * 0.0015)
    signal[:fade_in] *= np.linspace(0.0, 1.0, fade_in)
    fade = int(RATE * fade_s)
    signal[-fade:] *= np.cos(np.linspace(0.0, np.pi / 2, fade)) ** 2
    return signal


# ---------------------------------------------------------------- 敲击、拨弦（第一版的合成，#240）


def piano(midi, rng):
    f0 = freq(midi)
    pos = (midi - 21) / 87.0
    duration = lerp(6.5, 2.2, pos ** 0.8)
    n = int(RATE * duration)
    t = np.arange(n) / RATE
    strings = 1 if midi < 32 else 2 if midi < 44 else 3
    detune_cents = [0.0, 0.9, -0.7][:strings]
    inharm = 10 ** (-3.9 + 2.0 * pos)          # 非谐性系数 B：低音 ~1e-4，高音 ~1e-2
    strike_pos = 1.0 / 7.6                      # 击弦点在弦长 1/7.6 处
    t60 = lerp(16.0, 1.4, pos ** 0.75)          # 基音 60 dB 衰减时间
    out = np.zeros(n)
    for k in range(1, 71):
        fk = k * f0 * np.sqrt(1.0 + inharm * k * k)
        if fk > RATE * 0.45:
            break
        amp = abs(np.sin(np.pi * k * strike_pos)) / k ** 0.85
        amp /= 1.0 + (fk / 3800.0) ** 2           # 琴槌毛毡吃掉的高频
        if k == 1 and midi < 40:
            amp *= 0.7                           # 低音的基音本来就弱，泛音撑起音高
        tk = t60 / (1.0 + (fk / 900.0) ** 1.3)   # 高分音衰减快
        fast, slow = tk * 0.22, tk
        env = 0.68 * np.exp(-6.9 * t / fast) + 0.32 * np.exp(-6.9 * t / slow)
        for d in detune_cents:
            phase = rng.uniform(0, 2 * np.pi)
            out += amp * env * np.sin(2 * np.pi * fk * 2 ** (d / 1200.0) * t + phase) / strings
    out /= head_rms(out)
    knock = noise_burst(rng, int(RATE * 0.08), 300.0, 3500.0, 0.010)
    out[: len(knock)] += knock / (np.max(np.abs(knock)) + 1e-9) * lerp(0.35, 0.15, pos)
    thump_t = t[: int(RATE * 0.12)]
    out[: len(thump_t)] += lerp(0.30, 0.0, pos * 1.4) * np.sin(2 * np.pi * 70.0 * thump_t) * np.exp(-thump_t / 0.025)
    out = spectral_shape(out, lambda f: peak(f, 180.0, 1.2, 2.0) * peak(f, 2800.0, 1.5, 1.5)
                         * (1.0 / (1.0 + (f / 9000.0) ** 4)))
    out = room(out, rng, length=0.6, wet=0.12)
    return finish(out, fade_s=min(0.6, duration * 0.2))


def harp(midi, rng):
    f0 = freq(midi)
    pos = (midi - 24) / 78.0
    duration = lerp(5.0, 1.6, pos ** 0.8)
    n = int(RATE * duration)
    t = np.arange(n) / RATE
    pluck = lerp(0.42, 0.30, pos)              # 拨弦点靠近弦中央，音色圆
    t60 = lerp(7.5, 1.0, pos ** 0.7)
    inharm = 2e-6 + 3e-5 * pos
    out = np.zeros(n)
    for k in range(1, 40):
        fk = k * f0 * np.sqrt(1.0 + inharm * k * k)
        if fk > RATE * 0.42:
            break
        amp = abs(np.sin(np.pi * k * pluck)) / (k ** 1.7)
        amp /= 1.0 + (fk / 2600.0) ** 2
        tk = t60 / (1.0 + (fk / 650.0) ** 1.5)
        env = np.exp(-6.9 * t / tk)
        glide = 1.0 + 0.0009 * np.exp(-t / 0.08)
        phase = rng.uniform(0, 2 * np.pi)
        out += amp * env * np.sin(2 * np.pi * fk * glide * t + phase)
    out /= head_rms(out)
    pick = noise_burst(rng, int(RATE * 0.03), 1200.0, 6000.0, 0.004)
    out[: len(pick)] += pick / (np.max(np.abs(pick)) + 1e-9) * 0.12
    out = spectral_shape(out, lambda f: peak(f, 140.0, 1.0, 3.0) * peak(f, 480.0, 2.0, 2.0)
                         * peak(f, 1500.0, 1.8, -1.5) * (1.0 / (1.0 + (f / 7000.0) ** 4)))
    out = room(out, rng, length=0.7, wet=0.14, damp=4000.0)
    return finish(out, fade_s=min(0.5, duration * 0.2))


def modal(midi, rng, modes, t60, duration, detune_hz=0.0):
    """敲击类乐器的模态合成：modes 是 (频率倍数, 强弱, 衰减倍数)；detune_hz 不为 0 时上方模态拆成两支，拍出微微的颤动。"""
    f0 = freq(midi)
    n = int(RATE * duration)
    t = np.arange(n) / RATE
    out = np.zeros(n)
    for ratio, amp, decay in modes:
        f = f0 * ratio
        if f > RATE * 0.45:
            continue
        env = np.exp(-6.9 * t / (t60 * decay))
        if detune_hz and ratio > 1.5:
            for d in (-detune_hz / 2, detune_hz / 2):
                out += amp * 0.5 * env * np.sin(2 * np.pi * (f + d) * t + rng.uniform(0, 2 * np.pi))
        else:
            out += amp * env * np.sin(2 * np.pi * f * t + rng.uniform(0, 2 * np.pi))
    return out, t


def strike(rng, lo, hi, decay_s, level, length=0.06):
    burst = noise_burst(rng, int(RATE * length), lo, hi, decay_s)
    return burst / (np.max(np.abs(burst)) + 1e-9) * level


def glockenspiel(midi, rng):
    """钟琴：钢条的自由振动模态（1 : 2.756 : 5.404 : 8.933），硬槌敲出很亮的一下，余音清脆。"""
    pos = (midi - 78) / 30.0
    duration = lerp(4.0, 2.4, pos)
    out, t = modal(midi, rng, [(1.0, 1.0, 1.0), (2.756, 0.28, 0.35), (5.404, 0.12, 0.18), (8.933, 0.05, 0.1)],
                   lerp(3.6, 2.0, pos), duration)
    out /= head_rms(out)
    out[: int(RATE * 0.06)] += strike(rng, 2000.0, 9000.0, 0.003, 0.35)
    out = room(out, rng, length=0.5, wet=0.10, damp=7000.0)
    return finish(out, fade_s=0.4)


def celesta(midi, rng):
    """钢片琴：毛毡琴槌敲钢片，下面的木共鸣箱让基音特别饱满，上方模态很快消失，声音甜而轻。"""
    pos = (midi - 60) / 48.0
    duration = lerp(3.6, 1.8, pos)
    out, t = modal(midi, rng, [(1.0, 1.0, 1.0), (2.0, 0.08, 0.5), (2.76, 0.07, 0.25), (4.0, 0.16, 0.3), (5.4, 0.05, 0.15)],
                   lerp(3.0, 1.3, pos), duration)
    out *= np.minimum(1.0, t / 0.003)
    out /= head_rms(out)
    out[: int(RATE * 0.06)] += strike(rng, 500.0, 3000.0, 0.006, 0.14)
    out = spectral_shape(out, lambda f: peak(f, 320.0, 1.2, 2.5) * (1.0 / (1.0 + (f / 9000.0) ** 4)))
    out = room(out, rng, length=0.5, wet=0.12)
    return finish(out, fade_s=0.4)


def tubular_bells(midi, rng):
    """管钟：铜管被皮槌敲响，近似谐和的高次模态让音高听得清，余音很长、带一点拍频的颤动。"""
    pos = (midi - 54) / 30.0
    duration = lerp(8.0, 6.0, pos)
    out, t = modal(midi, rng, [(1.0, 1.0, 1.0), (2.0, 0.55, 0.8), (3.0, 0.35, 0.6), (4.17, 0.25, 0.5),
                               (5.43, 0.18, 0.4), (6.8, 0.10, 0.3), (8.4, 0.05, 0.2)],
                   lerp(9.0, 6.0, pos), duration, detune_hz=0.35)
    out /= head_rms(out)
    out[: int(RATE * 0.06)] += strike(rng, 1000.0, 6000.0, 0.005, 0.30)
    out = room(out, rng, length=0.9, wet=0.16, damp=5000.0)
    return finish(out, fade_s=0.8)


def harpsichord(midi, rng):
    """大键琴：羽管在靠近弦端的地方拨弦，泛音很多、音色明亮带一点金属感；8′ 与 4′ 两组弦叠在一起。"""
    f0 = freq(midi)
    pos = (midi - 29) / 60.0
    duration = lerp(6.0, 2.2, pos)
    n = int(RATE * duration)
    t = np.arange(n) / RATE
    t60 = lerp(9.0, 2.2, pos)
    inharm = 10 ** (-5 + 1.2 * pos)
    out = np.zeros(n)
    for octave, level, pluck in ((1.0, 1.0, 0.11), (2.0, 0.25, 0.13)):
        for k in range(1, 70):
            fk = k * f0 * octave * np.sqrt(1 + inharm * k * k)
            if fk > RATE * 0.45:
                break
            amp = abs(np.sin(np.pi * k * pluck)) / k ** 0.95
            if k == 1 and octave == 1.0:
                amp *= 1.4
            amp /= 1.0 + (fk / 8000.0) ** 2
            tk = t60 / (1.0 + (fk / 1500.0) ** 1.2)
            env = np.exp(-6.9 * t / tk)
            detune = 2 ** (0.6 / 1200) if octave > 1 else 1.0
            out += level * amp * env * np.sin(2 * np.pi * fk * detune * t + rng.uniform(0, 2 * np.pi))
    out /= head_rms(out)
    out[: int(RATE * 0.06)] += strike(rng, 2000.0, 7000.0, 0.004, 0.22)
    out = spectral_shape(out, lambda f: peak(f, 220.0, 1.2, 2.0) * peak(f, 1200.0, 1.5, 1.5)
                         * (1.0 / (1.0 + (f / 10000.0) ** 4)))
    out = room(out, rng, length=0.5, wet=0.10)
    return finish(out, fade_s=min(0.5, duration * 0.2))


def timpani(midi, rng):
    """定音鼓：鼓皮的 (1,1) 主模态定音高，上方模态约 1.5、2、2.5 倍（空气负载让它们近似谐和）；
    皮槌敲下去先有一下低沉的 (0,1) 闷响，音高随张力回落略往下走。"""
    f1 = freq(midi)
    duration = 4.5
    n = int(RATE * duration)
    t = np.arange(n) / RATE
    glide = 1.0 + 0.008 * np.exp(-t / 0.12)
    out = np.zeros(n)
    for ratio, amp, t60 in ((1.0, 1.0, 3.4), (1.504, 0.50, 2.4), (1.742, 0.26, 1.7), (2.0, 0.34, 2.0),
                            (2.245, 0.16, 1.4), (2.494, 0.18, 1.5), (2.8, 0.09, 1.0), (3.0, 0.07, 0.9)):
        f = f1 * ratio
        if f > RATE * 0.45:
            continue
        phase = 2 * np.pi * np.cumsum(f * glide) / RATE + rng.uniform(0, 2 * np.pi)
        out += amp * np.exp(-6.9 * t / t60) * np.sin(phase)
    out += 0.7 * np.sin(2 * np.pi * 0.62 * f1 * t + rng.uniform(0, 2 * np.pi)) * np.exp(-6.9 * t / 0.28)
    out /= head_rms(out)
    out[: int(RATE * 0.04)] += strike(rng, 120.0, 1400.0, 0.008, 0.30, length=0.04)
    out = spectral_shape(out, lambda f: peak(f, 180.0, 1.0, 2.0) * (1.0 / (1.0 + (f / 5000.0) ** 3)))
    out = room(out, rng, length=0.9, wet=0.16, damp=3500.0)
    return finish(out, fade_s=0.8)


# ---------------------------------------------------------------- 持续音：起音 + 无缝循环

LOOP_S = 1.2
LOOP_N = int(round(LOOP_S * RATE))
VIBRATO_HZ = 5.0       # 1.2 秒里正好 6 个周期
WOBBLE_HZ = 1.0 / LOOP_S

# vib：揉弦深度（音分）；trem：音量起伏；attack：起音秒数；noise：弓噪 / 气声相对乐音的大小；band：噪声频带；
# shape：谐波的基本形状；body：共鸣峰（中心 Hz, 宽窄, 增益 dB）；roll：高频滚降；boost：起音时亮度多推多少（铜管的「冲」）
SUSTAINED = {
    "violin": dict(vib=20, trem=0.03, attack=0.12, noise=0.020, band=(2500, 9000), shape="bowed",
                   body=[(280, 1.5, 6), (500, 2.0, 3), (1000, 1.2, 2), (2800, 1.0, 6)], roll=7000, boost=0.0),
    "viola": dict(vib=18, trem=0.03, attack=0.13, noise=0.020, band=(2000, 8000), shape="bowed",
                  body=[(230, 1.5, 6), (420, 2.0, 3), (850, 1.2, 2), (2300, 1.0, 5)], roll=6000, boost=0.0),
    "cello": dict(vib=14, trem=0.03, attack=0.15, noise=0.020, band=(1500, 6000), shape="bowed",
                  body=[(110, 1.5, 6), (200, 1.8, 4), (600, 1.5, 3), (1600, 1.2, 4)], roll=5000, boost=0.0),
    "double_bass": dict(vib=8, trem=0.02, attack=0.18, noise=0.025, band=(800, 4000), shape="bowed",
                        body=[(60, 1.5, 6), (110, 1.8, 4), (400, 1.5, 3), (1000, 1.2, 2)], roll=3000, boost=0.0),
    "flute": dict(vib=10, trem=0.08, attack=0.08, noise=0.060, band=(1500, 10000), shape="flute", body=[], roll=9000, boost=0.0),
    "oboe": dict(vib=8, trem=0.04, attack=0.05, noise=0.012, band=(2000, 8000), shape="oboe",
                 body=[(1100, 2.5, 10), (2900, 2.5, 6)], roll=8000, boost=0.0),
    "bassoon": dict(vib=3, trem=0.02, attack=0.06, noise=0.015, band=(800, 5000), shape="bassoon",
                    body=[(450, 2.0, 10), (1150, 2.5, 6)], roll=4000, boost=0.0),
    "trumpet": dict(vib=0, trem=0.0, attack=0.04, noise=0.004, band=(2000, 8000), shape="brass",
                    body=[(1300, 1.2, 8)], roll=5000, boost=0.5),
}


def partial_amp(spec, k, fk, f0):
    shape = spec["shape"]
    if shape == "bowed":
        a = 1.0 / k                                   # 弓弦的锯齿波（Helmholtz 运动）
    elif shape == "flute":
        a = 0.38 ** (k - 1) * (1.6 if k == 2 and f0 < 500 else 1.0)
    elif shape == "oboe":
        a = 1.0 / k ** 0.45 * (0.5 if k == 1 else 1.0)  # 双簧管基音弱，鼻音靠共振峰
    elif shape == "bassoon":
        a = 1.0 / k ** 0.7
    else:
        a = k ** -0.5                                 # 铜管：谐波多，向共振峰推高
    for center, q, gain in spec["body"]:
        a *= peak(fk, center, q, gain)
    return a / (1.0 + (fk / spec["roll"]) ** 3)


def sustained(kind, midi, rng):
    """返回 (信号, 实际音高, 循环点)。信号 = 起音段 + 一整个循环段，循环段以 LOOP_N 为周期。"""
    spec = SUSTAINED[kind]
    cycles = max(1, round(freq(midi) * LOOP_S))
    f0 = cycles / LOOP_S                               # 载波取整数个周期，实际音高略有偏差，写进采样表
    attack = int(round(spec["attack"] * RATE))
    n = attack + LOOP_N
    t = np.arange(n) / RATE
    dv = f0 * (2 ** (spec["vib"] / 1200.0) - 1)
    dw = f0 * (2 ** (3 / 1200.0) - 1)
    phi_v, phi_w = rng.uniform(0, 2 * np.pi, 2)
    theta = (2 * np.pi * f0 * t + dv / VIBRATO_HZ * np.sin(2 * np.pi * VIBRATO_HZ * t + phi_v)
             + dw / WOBBLE_HZ * np.sin(2 * np.pi * WOBBLE_HZ * t + phi_w))
    x = np.clip(t / max(spec["attack"], 1e-3), 0, 1)
    env = x * x * (3 - 2 * x)
    if spec["shape"] == "bowed":
        env = env ** 1.4                               # 弓慢慢咬住弦
    tonal = np.zeros(n)
    top = f0 + dv + dw
    for k in range(1, 200):
        fk = k * f0
        if k * top > min(9500.0, RATE * 0.45):
            break
        a = partial_amp(spec, k, fk, f0)
        bright = 1.0 + spec["boost"] * min(k, 8) / 8.0 * (1.0 - env)
        tonal += a * bright * np.sin(k * theta + rng.uniform(0, 2 * np.pi))
    tonal *= 1.0 + spec["trem"] * np.sin(2 * np.pi * VIBRATO_HZ * t + phi_v + 0.6)
    # 以循环长度为周期的带通噪声（弓毛擦弦、吹气）
    bins = np.fft.rfftfreq(LOOP_N, 1.0 / RATE)
    lo, hi = spec["band"]
    mag = np.where((bins > lo) & (bins < hi), 1.0, 0.0) / (1.0 + (bins / hi) ** 2)
    noise = np.fft.irfft(mag * np.exp(1j * rng.uniform(0, 2 * np.pi, len(bins))), LOOP_N)
    noise /= np.sqrt(np.mean(noise ** 2)) + 1e-12
    noise = noise[np.arange(n) % LOOP_N] * spec["noise"] * np.sqrt(np.mean(tonal[attack:] ** 2))
    out = (tonal + noise) * env
    key = 69 + 12 * math.log2(f0 / 440.0)
    return out, key, (attack, attack + LOOP_N)


# ---------------------------------------------------------------- 乐器表

PERCUSSIVE = {"grand_piano": piano, "concert_harp": harp, "glockenspiel": glockenspiel, "celesta": celesta,
              "tubular_bells": tubular_bells, "harpsichord": harpsichord, "timpani": timpani}

# 音域与 InstrumentKind 一致；range：可闻距离；release：松键淡出秒数（0 = 不止音）；level：乐器间的平衡（dB）；curve：力度曲线（0 = 不分力度）
INSTRUMENTS = {
    "grand_piano": dict(low=21, high=108, range=32, release=lambda m: 0.65 - 0.4 * (m - 21) / 87, level=0.0, curve=1.4),
    "concert_harp": dict(low=24, high=103, range=24, release=lambda m: 0.0, level=-1.0, curve=1.4),
    "glockenspiel": dict(low=79, high=108, range=24, release=lambda m: 0.0, level=-5.0, curve=1.4),
    "celesta": dict(low=60, high=108, range=24, release=lambda m: 0.3, level=-4.0, curve=1.6),
    "tubular_bells": dict(low=59, high=79, range=48, release=lambda m: 0.0, level=-3.0, curve=1.4),
    "harpsichord": dict(low=29, high=89, range=28, release=lambda m: 0.08, level=-2.0, curve=0.0),
    "timpani": dict(low=38, high=57, range=48, release=lambda m: 0.0, level=-2.0, curve=1.4),
    "violin": dict(low=55, high=100, range=32, release=lambda m: 0.2, level=-1.0, curve=1.4),
    "viola": dict(low=48, high=88, range=32, release=lambda m: 0.2, level=-1.0, curve=1.4),
    "cello": dict(low=36, high=81, range=32, release=lambda m: 0.25, level=0.0, curve=1.4),
    "double_bass": dict(low=28, high=62, range=32, release=lambda m: 0.3, level=0.0, curve=1.4),
    "flute": dict(low=60, high=96, range=32, release=lambda m: 0.15, level=-2.0, curve=1.2),
    "oboe": dict(low=58, high=92, range=32, release=lambda m: 0.12, level=-2.0, curve=1.2),
    "bassoon": dict(low=34, high=76, range=32, release=lambda m: 0.15, level=-1.0, curve=1.2),
    "trumpet": dict(low=54, high=84, range=32, release=lambda m: 0.12, level=0.0, curve=1.4),
}


def sample_notes(spec):
    notes = list(range(spec["low"], spec["high"] + 1, 3))
    if spec["high"] - notes[-1] > 1:
        notes.append(notes[-1] + 3)
    return notes


def build(name, demo_dir):
    spec = INSTRUMENTS[name]
    out_dir = ASSETS / "sounds/instrument" / name
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.ogg"):
        old.unlink()
    samples = []
    for m in sample_notes(spec):
        rng = np.random.default_rng(1000 * len(name) + m)
        if name in SUSTAINED:
            sig, key, loop = sustained(name, m, rng)
            loud = rec.loudness_db(sig, loop[0], loop[1], None)
        else:
            sig, key, loop = PERCUSSIVE[name](m, rng), float(m), None
            loud = rec.loudness_db(sig, 0, min(len(sig), int(0.8 * RATE)), 0.4)
        store = 0.891 / (np.max(np.abs(sig)) + 1e-12)
        stored = loud + 20 * math.log10(store)
        gain = 10 ** ((rec.REF + spec["level"] - stored) / 20)
        rec.encode(sig * store, out_dir / f"s{m}.ogg", 5.0)
        samples.append(dict(m=m, key=key, loop=loop, frames=len(sig), gain=gain))
        print(f"{name} s{m} key {key:.2f} {len(sig) / RATE:.2f} s gain {gain:.3f}" + (f" loop {loop}" if loop else ""), flush=True)
    # 个别峰值很尖的音不拖累整件乐器：按第 90 百分位整体降低，剩下几个超过满幅的停在满幅
    over = float(np.percentile([s["gain"] for s in samples], 90))
    if over > 1:
        print(f"  {name}: lowered by {20 * math.log10(over):.1f} dB to stay under full scale")
        for s in samples:
            s["gain"] /= over
    for s in samples:
        s["gain"] = min(1.0, s["gain"])
    for s in samples:
        s["lo"], s["hi"] = 999, -1
    for p in range(spec["low"], spec["high"] + 1):
        best = min(samples, key=lambda s: (abs(s["key"] - p), -s["key"]))
        best["lo"], best["hi"] = min(best["lo"], p), max(best["hi"], p)
    zones = []
    for s in samples:
        if s["hi"] < 0:
            continue
        z = {"file": f"instrument/{name}/s{s['m']}", "key": round(s["key"], 3), "lo": s["lo"], "hi": s["hi"], "vlo": 1, "vhi": 127,
             "gain": round(s["gain"], 4), "curve": spec["curve"], "frames": s["frames"], "release": round(spec["release"](s["m"]), 3)}
        if s["loop"]:
            z["loop"] = list(s["loop"])
        zones.append(z)
    rec.write_map_json(ASSETS / "instruments" / f"{name}.json", "synthesized by tools/instrument_samples.py", spec["range"], "synth", zones)
    if demo_dir:
        rec.ASSETS = ASSETS
        demo_spec = rec.Spec(name, "vsco", spec["low"], spec["high"], [(1, 127)], lambda c: [], sustain=name in SUSTAINED,
                             release=spec["release"], velocity=spec["curve"] > 0)
        rec.demo(demo_spec, demo_dir)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", required=True, help="带 libvorbis 的 ffmpeg")
    parser.add_argument("--demo", type=Path, help="把试听用的音阶与和弦另存到这个目录")
    parser.add_argument("--only", nargs="*", choices=list(INSTRUMENTS), help="只生成这几种乐器")
    args = parser.parse_args()
    rec.FFMPEG = args.ffmpeg
    for name in args.only or list(INSTRUMENTS):
        build(name, args.demo)
    size = sum(f.stat().st_size for f in (ASSETS / "sounds/instrument").rglob("*.ogg"))
    print(f"synthesized instrument sounds in the mod: {size / 1e6:.2f} MB")


if __name__ == "__main__":
    sys.exit(main())
