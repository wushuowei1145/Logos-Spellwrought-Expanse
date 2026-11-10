"""魔女之森乐器的采样：星辰花三角钢琴与灵猫竖琴，全部用代码合成（不用录音素材）。

    uv run --no-project --with numpy python tools/instrument_samples.py --ffmpeg <ffmpeg 路径> [--demo <试听目录>]

做法：
- 每隔小三度（3 个半音）合成一个采样，游戏里取最近的采样变调，变调不超过 ±1 个半音。
- 两种乐器都用「模态合成」：一个音 = 若干个分音（正弦）各自按指数衰减，再加上起音的噪声、
  共鸣箱的频响和很短的房间混响。
  - 钢琴：每个音 1–3 根弦（略微失谐，产生钢琴特有的拍音）；分音带非谐性（高分音偏高）；
    双段衰减（先快后慢）；琴槌击弦位置决定分音的强弱；起音有一点琴槌的木头声。
  - 竖琴：几乎谐和的分音；拨弦位置靠近中间，音色圆润；衰减比钢琴快，高音更短；起音有指腹拨弦的轻响。
- 输出单声道 44.1 kHz OGG（ffmpeg 的 libvorbis），写到 assets/logos_dimension/sounds/instrument/<乐器>/s<音高>.ogg，
  并在 sounds.json 里写 instrument.<乐器>.s<音高> 声音事件（只改 instrument.* 这些键，其他声音不动）。
- 随机数种子固定，重复运行得到相同的波形。

--demo 会把每种乐器的音阶和几个和弦另存成 ogg，方便不进游戏先听听音色。
"""
import argparse
import json
import subprocess
import sys
import tempfile
import wave
from pathlib import Path

import numpy as np

RATE = 44100
ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension"

# 乐器：采样音高范围（含两端，步长 3）、可闻距离、采样时长
INSTRUMENTS = {
    "grand_piano": {"low": 21, "high": 108, "range": 32},
    "concert_harp": {"low": 24, "high": 102, "range": 24},
}


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


def piano(midi, rng):
    f0 = freq(midi)
    pos = (midi - 21) / 87.0
    duration = lerp(6.5, 2.2, pos ** 0.8)
    n = int(RATE * duration)
    t = np.arange(n) / RATE
    strings = 1 if midi < 32 else 2 if midi < 44 else 3
    detune_cents = [0.0, 0.9, -0.7][:strings]
    inharm = 10 ** (-3.9 + 2.0 * pos)          # 非谐性系数 B：低音 ~1e-4，高音 ~1e-2
    strike = 1.0 / 7.6                          # 击弦点在弦长 1/7.6 处
    t60 = lerp(16.0, 1.4, pos ** 0.75)          # 基音 60 dB 衰减时间
    out = np.zeros(n)
    max_partials = 70
    for k in range(1, max_partials + 1):
        fk = k * f0 * np.sqrt(1.0 + inharm * k * k)
        if fk > RATE * 0.45:
            break
        amp = abs(np.sin(np.pi * k * strike)) / k ** 0.85
        amp /= 1.0 + (fk / 3800.0) ** 2           # 琴槌毛毡吃掉的高频
        if k == 1 and midi < 40:
            amp *= 0.7                           # 低音的基音本来就弱，泛音撑起音高
        tk = t60 / (1.0 + (fk / 900.0) ** 1.3)   # 高分音衰减快
        fast, slow = tk * 0.22, tk
        env = 0.68 * np.exp(-6.9 * t / fast) + 0.32 * np.exp(-6.9 * t / slow)
        for d in detune_cents:
            phase = rng.uniform(0, 2 * np.pi)
            out += amp * env * np.sin(2 * np.pi * fk * 2 ** (d / 1200.0) * t + phase) / strings
    # 琴槌的木头声与键盘的轻响：按弦振动的响度定比例，高音的弦声小，击键声也跟着小
    out /= head_rms(out)
    knock = noise_burst(rng, int(RATE * 0.08), 300.0, 3500.0, 0.010)
    out[: len(knock)] += knock / (np.max(np.abs(knock)) + 1e-9) * lerp(0.35, 0.15, pos)
    thump_t = t[: int(RATE * 0.12)]
    out[: len(thump_t)] += lerp(0.30, 0.0, pos * 1.4) * np.sin(2 * np.pi * 70.0 * thump_t) * np.exp(-thump_t / 0.025)
    # 音板与琴箱：中低频温暖、2–4 kHz 略亮
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
        # 三角形初始形变的傅里叶系数（~1/k²），手指的软肉再滤掉一点高频
        amp = abs(np.sin(np.pi * k * pluck)) / (k ** 1.7)
        amp /= 1.0 + (fk / 2600.0) ** 2
        tk = t60 / (1.0 + (fk / 650.0) ** 1.5)
        env = np.exp(-6.9 * t / tk)
        # 极轻微的音高下滑（拨弦后张力回落），让声音更自然
        glide = 1.0 + 0.0009 * np.exp(-t / 0.08)
        phase = rng.uniform(0, 2 * np.pi)
        out += amp * env * np.sin(2 * np.pi * fk * glide * t + phase)
    # 起音：手指离弦的轻响
    out /= head_rms(out)
    pick = noise_burst(rng, int(RATE * 0.03), 1200.0, 6000.0, 0.004)
    out[: len(pick)] += pick / (np.max(np.abs(pick)) + 1e-9) * 0.12
    # 云杉共鸣箱：低频鼓起、中频一个木头的共振、高频收得早
    out = spectral_shape(out, lambda f: peak(f, 140.0, 1.0, 3.0) * peak(f, 480.0, 2.0, 2.0)
                         * peak(f, 1500.0, 1.8, -1.5) * (1.0 / (1.0 + (f / 7000.0) ** 4)))
    out = room(out, rng, length=0.7, wet=0.14, damp=4000.0)
    return finish(out, fade_s=min(0.5, duration * 0.2))


SYNTHS = {"grand_piano": piano, "concert_harp": harp}


def normalize(signal, midi, low, high):
    """按起音后 0.4 秒的 RMS 统一响度，高音稍微降一点（人耳对 2–4 kHz 敏感）。"""
    head = signal[: int(RATE * 0.4)]
    rms = np.sqrt(np.mean(head ** 2)) + 1e-9
    tilt = lerp(1.0, 0.8, (midi - low) / max(1, high - low))
    signal = signal * (0.16 / rms) * tilt
    pk = np.max(np.abs(signal))
    if pk > 0.95:
        signal *= 0.95 / pk
    return signal


def write_wav(path, signal):
    data = np.clip(signal, -1.0, 1.0)
    pcm = (data * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.tobytes())


def to_ogg(ffmpeg, wav, ogg):
    subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", str(wav), "-ac", "1", "-c:a", "libvorbis",
                    "-q:a", "5", str(ogg)], check=True)


def sample_notes(spec):
    return list(range(spec["low"], spec["high"] + 1, 3))


def nearest_sample(midi, notes):
    return min(notes, key=lambda s: abs(s - midi))


def resample(signal, ratio):
    """试听用的线性插值变调（游戏里由 OpenAL 变调）。"""
    idx = np.arange(0, len(signal) - 1, ratio)
    i0 = idx.astype(int)
    frac = idx - i0
    return signal[i0] * (1 - frac) + signal[i0 + 1] * frac


def demo(name, samples, notes, out_dir, ffmpeg, tmp):
    """音阶（C 大调两个八度）+ 三个和弦，按游戏里的取样规则拼出来。"""
    events = []
    scale = [0, 2, 4, 5, 7, 9, 11]
    start = 48 if name == "grand_piano" else 55
    time = 0.0
    for octave in range(2):
        for s in scale:
            events.append((time, start + 12 * octave + s, 0.35))
            time += 0.28
    events.append((time, start + 24, 1.2))
    time += 1.0
    for chord in ([48, 52, 55, 60, 64], [45, 52, 57, 60, 64], [41, 48, 53, 57, 60, 65]):
        for i, m in enumerate(chord):
            # 竖琴的和弦从低到高轻轻琶音
            offset = i * 0.06 if name == "concert_harp" else 0.0
            events.append((time + offset, m + (12 if name == "concert_harp" else 0), 1.8))
        time += 1.9
    total = int(RATE * (time + 3.0))
    mix = np.zeros(total)
    for at, m, hold in events:
        s = nearest_sample(m, notes)
        wave_ = resample(samples[s], 2 ** ((m - s) / 12.0))
        keep = min(len(wave_), int(RATE * (hold + 0.12)))
        piece = wave_[:keep].copy()
        rel = min(int(RATE * 0.12), keep)
        piece[-rel:] *= np.linspace(1.0, 0.0, rel)
        a = int(RATE * at)
        end = min(total, a + len(piece))
        mix[a:end] += piece[: end - a]
    mix *= 0.9 / (np.max(np.abs(mix)) + 1e-9)
    wav = Path(tmp) / f"demo_{name}.wav"
    write_wav(wav, mix)
    out_dir.mkdir(parents=True, exist_ok=True)
    to_ogg(ffmpeg, wav, out_dir / f"demo_{name}.ogg")


def update_sounds_json(events):
    path = ASSETS / "sounds.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data = {k: v for k, v in data.items() if not k.startswith("instrument.")}
    data.update(events)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", required=True, help="带 libvorbis 的 ffmpeg")
    parser.add_argument("--demo", type=Path, help="把试听用的音阶与和弦另存到这个目录")
    parser.add_argument("--only", choices=list(INSTRUMENTS), help="只生成一种乐器")
    args = parser.parse_args()
    events = {}
    with tempfile.TemporaryDirectory() as tmp:
        for name, spec in INSTRUMENTS.items():
            notes = sample_notes(spec)
            out_dir = ASSETS / "sounds/instrument" / name
            out_dir.mkdir(parents=True, exist_ok=True)
            samples = {}
            for m in notes:
                if args.only is None or args.only == name:
                    rng = np.random.default_rng(1000 * len(name) + m)
                    sig = normalize(SYNTHS[name](m, rng), m, spec["low"], spec["high"])
                    samples[m] = sig
                    wav = Path(tmp) / f"{name}_{m}.wav"
                    write_wav(wav, sig)
                    to_ogg(args.ffmpeg, wav, out_dir / f"s{m}.ogg")
                    print(f"{name} s{m} ({freq(m):.1f} Hz, {len(sig) / RATE:.1f} s)")
                events[f"instrument.{name}.s{m}"] = {
                    "subtitle": f"subtitles.logos_dimension.instrument.{name}",
                    "sounds": [{"name": f"logos_dimension:instrument/{name}/s{m}",
                                "attenuation_distance": spec["range"], "preload": True}],
                }
            if args.demo and samples:
                demo(name, samples, notes, args.demo, args.ffmpeg, tmp)
    update_sounds_json(events)
    print(f"sounds.json: {len(events)} instrument events")


if __name__ == "__main__":
    sys.exit(main())
