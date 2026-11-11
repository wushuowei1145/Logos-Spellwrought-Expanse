#!/usr/bin/env python3
"""
魔女之森乐器的真实采样：从公开采样库（钉在固定提交上）下载需要的文件，做成游戏用的单声道 ogg，
并写出每件乐器的采样表 assets/logos_dimension/instruments/<乐器>.json（{@code SampleMap} 读它）。

来源与许可（另见 docs/INSTRUMENT_SAMPLES.md、CREDITS.md）：
- 钢琴：Salamander Grand Piano V3（Alexander Holm，CC BY 3.0），sfzinstruments 的 FLAC 版，音准用 Markus Fiedler 的重新调律值
- 竖琴、钟琴、管钟、大键琴：Versilian Community Sample Library（VCSL，CC0）
- 小提琴、长笛、小号：VS Chamber Orchestra: Community Edition（VSCO 2 CE，CC0）
两个库都没有钢片琴，钢片琴仍由 tools/instrument_samples.py 合成；管风琴仍在客户端按音栓现场合成。

每个采样的处理：
  解码 → 混成单声道 → soxr 重采样到 44.1 kHz → 去直流 → 按谐波测音高（定八度、量出音分偏差）→
  切掉起音前的静音 → 衰减类截到 -60 dB 或上限时长并淡出；持续音（弓弦、管乐）在稳定段里找循环点
  （有揉弦时循环长度取揉弦周期的整数倍，波形相位对齐），做按相关度补偿的交叉淡化，文件到循环终点为止 →
  按 K 计权响度把同一层各音拉平（保留音区间的自然起伏），算出每个音每层的增益与层内力度曲线，
  让相邻两层在交界处一样响 → libvorbis 编码（固定流序号、bitexact，重跑字节不变）。

用法：
  uv run --no-project --with numpy --with scipy python tools/instrument_sources.py --cache <下载目录> [--only 乐器 ...]
      [--ffmpeg <ffmpeg>] [--report] [--demo <目录>]
  --report  只分析、打印每个采样的测量结果，不写文件
  --demo    另把每件乐器的音阶与和弦渲染成 ogg，方便不进游戏先听
  --reserve 只下载并分析「以后备用」的乐器（见 RESERVES），渲染试听到 --demo 目录，不写进模组
"""
from __future__ import annotations

import argparse
import json
import math
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np
from scipy import signal

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension"
SR = 44100
NAMES = "C C# D D# E F F# G G# A A# B".split()
FFMPEG = "ffmpeg"


def midi_of(name: str) -> int:
    """'D#1' → 27，'A0' → 21（C4 = 60）。"""
    m = re.fullmatch(r"([A-G])(#|b|s)?(-?\d)", name)
    if not m:
        raise ValueError(name)
    n = NAMES.index(m.group(1)) + {"#": 1, "s": 1, "b": -1, None: 0}[m.group(2)]
    return 12 * (int(m.group(3)) + 1) + n


def name_of(midi: int) -> str:
    return f"{NAMES[midi % 12]}{midi // 12 - 1}"


def hz(m: float) -> float:
    return 440.0 * 2 ** ((m - 69) / 12)


# ---------------------------------------------------------------- 来源


@dataclass(frozen=True)
class Repo:
    slug: str
    commit: str
    title: str
    license: str
    url: str
    credit: str


REPOS = {
    "salamander": Repo("sfzinstruments/SalamanderGrandPiano", "3382bf9496bba2486f5ab0de55a264d1dfc38404",
                       "Salamander Grand Piano V3", "CC BY 3.0", "https://creativecommons.org/licenses/by/3.0/",
                       "Alexander Holm (recording); kinwie / sfzinstruments (SFZ, FLAC); Markus Fiedler (retuning)"),
    "vsco": Repo("sgossner/VSCO-2-CE", "440300901dfe9275fd84e0b7763af1f8443ae62e",
                 "VS Chamber Orchestra: Community Edition", "CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/",
                 "Versilian Studios (Sam Gossner, Simon Dalzell; sample cutting by Elan Hickler)"),
    "vcsl": Repo("sgossner/VCSL", "c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e",
                 "Versilian Community Sample Library", "CC0 1.0", "https://creativecommons.org/publicdomain/zero/1.0/",
                 "Versilian Studios and contributors"),
    "local": Repo("", "", "synthesized by tools/instrument_samples.py", "same as this mod", "", "Logos: Spellwrought Expanse"),
}


class Cache:
    """下载目录：<cache>/<owner>_<repo>/<仓库内路径>，另存一份文件树。只下载用得到的文件。"""

    def __init__(self, root: Path):
        self.root = root
        self.trees: dict[str, list[str]] = {}

    def tree(self, key: str) -> list[str]:
        if key not in self.trees:
            repo = REPOS[key]
            path = self.root / f"tree-{repo.slug.replace('/', '_')}-{repo.commit[:12]}.json"
            if not path.exists():
                url = f"https://api.github.com/repos/{repo.slug}/git/trees/{repo.commit}?recursive=1"
                req = urllib.request.Request(url, headers={"User-Agent": "instrument-sources", "Accept": "application/vnd.github+json"})
                with urllib.request.urlopen(req, timeout=120) as r:
                    data = json.load(r)
                if data.get("truncated"):
                    raise RuntimeError(f"tree of {repo.slug} is truncated")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps([e["path"] for e in data["tree"] if e["type"] == "blob"]), encoding="utf-8")
            self.trees[key] = json.loads(path.read_text(encoding="utf-8"))
        return self.trees[key]

    def file(self, key: str, rel: str) -> Path:
        if key == "local":
            return ASSETS / "sounds" / rel
        repo = REPOS[key]
        dest = self.root / repo.slug.replace("/", "_") / Path(*rel.split("/"))
        if dest.exists() and dest.stat().st_size > 0:
            return dest
        dest.parent.mkdir(parents=True, exist_ok=True)
        url = f"https://raw.githubusercontent.com/{repo.slug}/{repo.commit}/" + urllib.parse.quote(rel)
        req = urllib.request.Request(url, headers={"User-Agent": "instrument-sources"})
        part = dest.with_suffix(dest.suffix + ".part")
        with urllib.request.urlopen(req, timeout=300) as r, open(part, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        part.replace(dest)
        print(f"  downloaded {rel} ({dest.stat().st_size // 1024} KB)", flush=True)
        return dest


# ---------------------------------------------------------------- 乐器


@dataclass
class Src:
    path: str            # 仓库内路径
    label: int           # 文件名里写的音（MIDI）
    layer: int           # Spec.layers 的下标
    tune: float | None = None  # 播放时要加的音分（钢琴的重新调律）；None 表示自己量


@dataclass
class Spec:
    kind: str
    repo: str
    low: int
    high: int
    layers: list[tuple[int, int]]
    sources: Callable[["Cache"], list[Src]]
    shift: int = 0                 # 文件名的音 + shift = 实际发声的音（各库的八度写法不同，会再量一遍核对）
    pitch: str = "harmonic"        # harmonic：按谐波量；peak：最强的峰（钟琴）；label：按文件名（管钟）
    sustain: bool = False          # 持续音：找循环点
    max_seconds: Callable[[int], float] = lambda m: 8.0
    release: Callable[[int], float] = lambda m: 0.0   # 松键后淡出的秒数；0 = 不止音（竖琴、钟琴、管钟）
    releases: Callable[["Cache"], dict[int, str]] | None = None  # 松键声（大键琴的拨子落回）
    release_level: float = 0.0     # 松键声相对录音时的电平（dB）
    level: float = 0.0             # 乐器之间的平衡（dB）
    curve: float = 1.4             # 力度曲线：响度 ∝ (力度 / 127) ^ curve，各层都落在这条曲线上，层只换音色
    shape: float = 0.5             # 音区间自然的响度起伏保留多少（录音里长笛低音比高音轻 25 dB，游戏里太悬殊）
    velocity: bool = True
    quality: float = 5.0           # libvorbis -q
    range: int = 32                # 方块乐器的可闻距离（格）
    keep_files: bool = False       # 文件已在资源里（合成的钢片琴）：只量响度、写采样表，不重新编码
    note: str = ""


def files_in(cache: Cache, key: str, folder: str) -> list[str]:
    prefix = folder.rstrip("/") + "/"
    return [p for p in cache.tree(key) if p.startswith(prefix)]


def piano_sources(cache: Cache) -> list[Src]:
    notes = ["A0"] + [f"{n}{o}" for o in range(1, 8) for n in ("C", "D#", "F#", "A")] + ["C8"]
    tune_file = cache.file("salamander", "Data/tune_ret.txt").read_text(encoding="utf-8")
    retune = [int(v) for v in re.findall(r"\$TUNE\d+\s+(-?\d+)", tune_file)]
    assert len(notes) == 30 == len(retune), (len(notes), len(retune))
    layers = [2, 6, 10, 13, 16]   # Salamander 16 层里取 pp / mp / mf / f / ff
    return [Src(f"Samples/{n}v{v}.flac", midi_of(n), li, tune=retune[i]) for i, n in enumerate(notes) for li, v in enumerate(layers)]


def harp_sources(cache: Cache) -> list[Src]:
    folder = "Chordophones/Composite Chordophones/Concert Harp"
    by_note: dict[str, dict[str, str]] = {}
    for p in files_in(cache, "vcsl", folder):
        m = re.search(r"KSHarp_([A-G]#?\d)_(p|mp|mf|f)(\d)\.wav$", p)
        if m:
            by_note.setdefault(m.group(1), {}).setdefault(m.group(2), p)
    out = []
    for note, dyn in by_note.items():
        soft = dyn.get("mf") or dyn.get("mp") or dyn.get("p")
        loud = dyn.get("f")
        if soft:
            out.append(Src(soft, midi_of(note), 0))
        if loud:
            out.append(Src(loud, midi_of(note), 1))
        if not soft and loud:
            out.append(Src(loud, midi_of(note), 0))
    return out


def glock_sources(cache: Cache) -> list[Src]:
    out = []
    seen = set()
    for p in sorted(files_in(cache, "vcsl", "Idiophones/Struck Idiophones/Glockenspiel")):
        m = re.search(r"glock_(soft|medium|loud)_([A-G]#?\d)_\d+\.wav$", p)
        if not m or (m.group(1), m.group(2)) in seen:
            continue
        seen.add((m.group(1), m.group(2)))
        out.append(Src(p, midi_of(m.group(2)), ["soft", "medium", "loud"].index(m.group(1))))
    return out


def bells_sources(cache: Cache) -> list[Src]:
    out = []
    seen = set()
    for p in sorted(files_in(cache, "vcsl", "Idiophones/Struck Idiophones/Tubular Bells 2")):
        m = re.search(r"TB_hit_([A-G]#?\d)_v(\d)_\d+\.wav$", p)
        if not m or (m.group(1), m.group(2)) in seen:
            continue
        seen.add((m.group(1), m.group(2)))
        out.append(Src(p, midi_of(m.group(1)), 0 if m.group(2) == "2" else 1))
    return out


def harpsichord_sources(cache: Cache) -> list[Src]:
    out = []
    for p in files_in(cache, "vcsl", "Chordophones/Zithers/Harpsichord, Unk/Sustains"):
        m = re.search(r"Harpsi4_Sus_Main_([A-G]#?-?\d)_rr1\.wav$", p)
        if m:
            out.append(Src(p, midi_of(m.group(1)), 0))
    return out


def harpsichord_releases(cache: Cache) -> dict[int, str]:
    out = {}
    for p in files_in(cache, "vcsl", "Chordophones/Zithers/Harpsichord, Unk/Releases"):
        m = re.search(r"Harpsi4_Rel_Main_([A-G]#?-?\d)_rr1\.wav$", p)
        if m:
            out[midi_of(m.group(1)) + 12] = p
    return out


def celesta_sources(cache: Cache) -> list[Src]:
    return [Src(f"instrument/celesta/s{m}.ogg", m, 0) for m in range(60, 109, 3)]


def violin_sources(cache: Cache) -> list[Src]:
    out = []
    for p in files_in(cache, "vsco", "Strings/Solo Violin/Arco Vib"):
        m = re.search(r"LLVln_ArcoVib_([A-G]#?\d)_(p|f)\.wav$", p)
        if m:
            out.append(Src(p, midi_of(m.group(1)), 0 if m.group(2) == "p" else 1))
    return out


def flute_sources(cache: Cache) -> list[Src]:
    out = []
    for p in files_in(cache, "vsco", "Woodwinds/Flute/susvib"):
        m = re.search(r"LDFlute_susvib_([A-G]#?\d)_v1_1\.wav$", p)
        if m:
            out.append(Src(p, midi_of(m.group(1)), 0))
    return out


def trumpet_sources(cache: Cache) -> list[Src]:
    out = []
    for p in files_in(cache, "vsco", "Brass/Trumpet/sus"):
        m = re.search(r"Sum_SHTrumpet_sus_([A-G]#?\d)_v(1|3)_rr1\.wav$", p)
        if m:
            out.append(Src(p, midi_of(m.group(1)), 0 if m.group(2) == "1" else 1))
    return out


SPECS: dict[str, Spec] = {
    "grand_piano": Spec(
        "grand_piano", "salamander", 21, 108, [(1, 36), (37, 56), (57, 80), (81, 104), (105, 127)], piano_sources,
        max_seconds=lambda m: 10.0 if m < 48 else 7.0 if m < 72 else 5.0 if m < 84 else 3.5,
        release=lambda m: 0.65 - 0.4 * (m - 21) / 87, level=0.0, curve=1.4, range=32,
        note="Yamaha C5，每隔小三度一个音，16 层力度里取 5 层"),
    "concert_harp": Spec(
        "concert_harp", "vcsl", 24, 103, [(1, 90), (91, 127)], harp_sources,
        max_seconds=lambda m: 9.0 if m < 48 else 6.0 if m < 72 else 4.0, level=-1.0, range=24,
        note="Concert Harp（VSCO 的竖琴加强奏一层），每隔三度一个音"),
    "glockenspiel": Spec(
        "glockenspiel", "vcsl", 79, 108, [(1, 50), (51, 95), (96, 127)], glock_sources, shift=12, pitch="peak",
        max_seconds=lambda m: 4.0, level=-5.0, range=24,
        note="Glockenspiel，弱 / 中 / 强三层，文件名比实际发声低一个八度"),
    "tubular_bells": Spec(
        "tubular_bells", "vcsl", 59, 79, [(1, 80), (81, 127)], bells_sources, pitch="label",
        max_seconds=lambda m: 10.0, level=-3.0, range=48,
        note="Tubular Bells 2（VSCO 2 的管钟），C4–F5 全音阶，两层"),
    "harpsichord": Spec(
        "harpsichord", "vcsl", 29, 89, [(1, 127)], harpsichord_sources, shift=12,
        max_seconds=lambda m: 4.5 if m < 53 else 3.0 if m < 72 else 2.5, release=lambda m: 0.08, releases=harpsichord_releases,
        release_level=0.0, level=-2.0, velocity=False, range=28,
        note="Harpsichord, Unk（Harpsi4），每个半音一个、带松键声；文件名比实际发声低一个八度"),
    "celesta": Spec(
        "celesta", "local", 60, 108, [(1, 127)], celesta_sources, pitch="label", keep_files=True,
        release=lambda m: 0.3, level=-4.0, curve=1.6, range=24,
        note="两个采样库都没有钢片琴：仍用 tools/instrument_samples.py 合成的采样，这里只量响度、写采样表"),
    "violin": Spec(
        "violin", "vsco", 55, 100, [(1, 79), (80, 127)], violin_sources, sustain=True,
        max_seconds=lambda m: 7.0, release=lambda m: 0.2, level=-1.0,
        note="Solo Violin Arco Vib（独奏小提琴，揉弦长弓），p / f 两层"),
    "flute": Spec(
        "flute", "vsco", 60, 96, [(1, 127)], flute_sources, shift=12, sustain=True,
        max_seconds=lambda m: 7.0, release=lambda m: 0.15, level=-2.0, curve=1.2,
        note="Flute susvib（带揉音的长音），文件名比实际发声低一个八度"),
    "trumpet": Spec(
        "trumpet", "vsco", 54, 84, [(1, 84), (85, 127)], trumpet_sources, shift=12, sustain=True,
        max_seconds=lambda m: 7.0, release=lambda m: 0.12, level=0.0,
        note="Trumpet sus（直音长音），弱 / 强两层，文件名比实际发声低一个八度"),
}


# ---------------------------------------------------------------- 信号处理


def decode(path: Path) -> np.ndarray:
    """解码成 44.1 kHz 单声道 float64（soxr 高精度重采样）。"""
    raw = subprocess.run([FFMPEG, "-v", "error", "-i", str(path), "-af", "aresample=resampler=soxr:precision=28",
                          "-ar", str(SR), "-ac", "2", "-f", "f32le", "-"], capture_output=True, check=True).stdout
    x = np.frombuffer(raw, dtype="<f4").reshape(-1, 2).astype(np.float64)
    mono = 0.5 * (x[:, 0] + x[:, 1])
    sos = signal.butter(2, 12, btype="highpass", fs=SR, output="sos")
    return signal.sosfiltfilt(sos, mono)


def rms_track(x: np.ndarray, win: float) -> np.ndarray:
    """以每个点为中心、win 秒窗口的均方根（累加和，O(N)）。"""
    w = max(1, int(win * SR))
    c = np.concatenate(([0.0], np.cumsum(x * x)))
    i = np.arange(len(x))
    lo = np.clip(i - w // 2, 0, len(x))
    hi = np.clip(i - w // 2 + w, 0, len(x))
    return np.sqrt(np.maximum(c[hi] - c[lo], 0) / w + 1e-20)


def find_onset(x: np.ndarray) -> int:
    peak = np.abs(x).max()
    i = int(np.argmax(np.abs(x) > peak * 10 ** (-42 / 20)))
    return max(0, i - int(0.0015 * SR))


def spectrum(seg: np.ndarray):
    n = len(seg)
    size = 1 << int(math.ceil(math.log2(n * 8)))
    X = np.abs(np.fft.rfft((seg - seg.mean()) * np.hanning(n), size))
    return X, SR / size


def peak_freq(X: np.ndarray, df: float, f: float, tol: float):
    lo = max(1, int(f * (1 - tol) / df))
    hi = min(len(X) - 2, int(f * (1 + tol) / df) + 1)
    if hi <= lo:
        return None
    i = lo + int(np.argmax(X[lo:hi]))
    a, b, c = (math.log(X[j] + 1e-15) for j in (i - 1, i, i + 1))
    den = a - 2 * b + c
    d = 0.5 * (a - c) / den if den != 0 else 0.0
    return X[i], (i + d) * df


def measure_harmonic(seg: np.ndarray, expected: int) -> tuple[float, int, float]:
    """按谐波定八度（谐波处的能量减去半谐波处的），再用前几根谐波的峰值量出准确音高。返回（MIDI 小数、所选八度的音、可信度）。"""
    X, df = spectrum(seg)
    noise = float(np.median(X)) + 1e-15

    def mag(f: float) -> float:
        if f > SR * 0.45:
            return noise
        lo, hi = max(1, int(f * 0.988 / df)), int(f * 1.012 / df) + 1
        return float(X[lo:hi].max())

    scores = []
    for octave in (-12, 0, 12):
        m0 = expected + octave
        best = None
        for c in range(-70, 71, 5):
            f0 = hz(m0 + c / 100)
            k_max = int(min(80, min(6000, SR * 0.45) // f0))
            if k_max < 1:
                continue
            h = np.mean([math.log1p(mag(k * f0) / noise) for k in range(1, k_max + 1)])
            s = np.mean([math.log1p(mag((k - 0.5) * f0) / noise) for k in range(1, k_max + 1)])
            score = h - 0.8 * s
            if best is None or score > best[0]:
                best = (score, m0, c)
        if best:
            scores.append(best)
    scores.sort(reverse=True)
    score, m0, c = scores[0]
    margin = score - (scores[1][0] if len(scores) > 1 else 0)
    f0 = hz(m0 + c / 100)
    est = []
    for k in (1, 2, 3, 4):
        if k * f0 > SR * 0.45:
            break
        p = peak_freq(X, df, k * f0, 0.02)
        if p:
            est.append((p[0] / math.sqrt(k), p[1] / k))
    w = np.array([e[0] for e in est])
    f = float((w * np.array([e[1] for e in est])).sum() / w.sum())
    return 69 + 12 * math.log2(f / 440), m0, margin


def measure_peak(seg: np.ndarray, expected: int) -> tuple[float, int, float]:
    """钢条、钟片：基音最强。在各个八度附近找最强的峰。"""
    X, df = spectrum(seg)
    best = None
    for octave in (-12, 0, 12):
        f = hz(expected + octave)
        if f > SR * 0.45:
            continue
        p = peak_freq(X, df, f, 0.05)
        if p and (best is None or p[0] > best[0]):
            best = (p[0], p[1], expected + octave)
    return 69 + 12 * math.log2(best[1] / 440), best[2], 0.0


def kweight(x: np.ndarray) -> np.ndarray:
    """近似 ITU-R BS.1770 的 K 计权：1.5 kHz 以上 +4 dB 的高架，再滤掉 40 Hz 以下。"""
    def biquad_shelf(f0, gain_db):
        A = 10 ** (gain_db / 40)
        w = 2 * math.pi * f0 / SR
        alpha = math.sin(w) / 2 * math.sqrt(2)
        cw = math.cos(w)
        b = [A * ((A + 1) + (A - 1) * cw + 2 * math.sqrt(A) * alpha), -2 * A * ((A - 1) + (A + 1) * cw),
             A * ((A + 1) + (A - 1) * cw - 2 * math.sqrt(A) * alpha)]
        a = [(A + 1) - (A - 1) * cw + 2 * math.sqrt(A) * alpha, 2 * ((A - 1) - (A + 1) * cw),
             (A + 1) - (A - 1) * cw - 2 * math.sqrt(A) * alpha]
        return np.array(b) / a[0], np.array(a) / a[0]
    b, a = biquad_shelf(1500, 4.0)
    y = signal.lfilter(b, a, x)
    sos = signal.butter(2, 40, btype="highpass", fs=SR, output="sos")
    return signal.sosfilt(sos, y)


def loudness_db(x: np.ndarray, start: int, end: int, peak_window: float | None) -> float:
    y = kweight(x)[start:end]
    if len(y) == 0:
        return -120.0
    if peak_window:
        r = rms_track(y, peak_window)
        return 20 * math.log10(float(r.max()) + 1e-12)
    return 10 * math.log10(float(np.mean(y * y)) + 1e-20)


def decay_end(x: np.ndarray, start: int, max_s: float) -> int:
    r = rms_track(x, 0.05)
    top_i = start + int(np.argmax(r[start:start + SR]))
    below = np.where(r[top_i:] < r[top_i] * 10 ** (-60 / 20))[0]
    end = top_i + int(below[0]) if len(below) else len(x)
    return min(end, start + int(max_s * SR), len(x))


def fade_out(y: np.ndarray, length: int) -> np.ndarray:
    length = min(length, len(y))
    if length > 0:
        t = np.linspace(0, 1, length, endpoint=True)
        y[-length:] *= np.cos(t * math.pi / 2) ** 2
    return y


# ---------------------------------------------------------------- 循环点


def pitch_track(x: np.ndarray, f0: float, hop: int):
    """每 hop 个点一帧：期望周期 ±8% 内的归一化自相关峰 → 音分；同时给出每帧的响度（dB）。"""
    period = SR / f0
    n = int(max(0.03 * SR, 4 * period))
    lags = np.arange(int(period * 0.92), int(period * 1.08) + 2)
    cents, level = [], []
    for start in range(0, len(x) - n - lags[-1], hop):
        a = x[start:start + n]
        energy = float(a @ a) + 1e-20
        ac = np.array([a @ x[start + l:start + l + n] for l in lags]) / energy
        i = int(np.argmax(ac))
        if 0 < i < len(ac) - 1:
            den = ac[i - 1] - 2 * ac[i] + ac[i + 1]
            d = 0.5 * (ac[i - 1] - ac[i + 1]) / den if den != 0 else 0.0
        else:
            d = 0.0
        lag = lags[i] + d
        cents.append(1200 * math.log2(period / lag))
        level.append(10 * math.log10(energy / n))
    return np.array(cents), np.array(level)


@dataclass
class Loop:
    start: int
    end: int
    crossfade: int
    cost: float
    vibrato: float | None


def find_loop(x: np.ndarray, f0: float, limit_s: float) -> Loop:
    hop = int(0.01 * SR)
    x = x[:int(limit_s * SR) + int(0.3 * SR)]
    cents, level = pitch_track(x, f0, hop)
    frames = len(cents)
    a = int(0.5 * SR / hop)
    b = min(frames - 1, int((len(x) / SR - 0.25) * SR / hop))
    if b - a < int(1.2 * SR / hop):
        raise RuntimeError("sample too short to loop")
    region = cents[a:b] - np.median(cents[a:b])
    # 揉弦周期：音分曲线自相关在 0.1–0.35 秒内的峰
    vib = None
    detr = region - signal.savgol_filter(region, min(len(region) // 2 * 2 - 1, 101), 2)
    depth = float(np.std(detr))
    if depth > 5:
        ac = np.correlate(detr, detr, mode="full")[len(detr) - 1:]
        ac /= ac[0] + 1e-12
        lo, hi = int(0.1 * SR / hop), int(0.35 * SR / hop)
        k = lo + int(np.argmax(ac[lo:hi]))
        if ac[k] > 0.3:
            vib = k * hop / SR
    slope = np.gradient(cents)
    if vib:
        lengths = [n * vib for n in range(1, 40) if 1.2 <= n * vib <= 3.2]
    else:
        lengths = list(np.arange(1.2, 3.2, 0.05))
    cands = []
    for L in lengths:
        span = int(round(L * SR / hop))
        for s in range(a, b - span, 2):
            e = s + span
            c = (s * hop / SR * 0.08 + abs(level[s] - level[e]) * 1.5 + abs(cents[s] - cents[e]) * 0.08 + abs(slope[s] - slope[e]) * 0.15
                 + max(0.0, level[a:b].max() - level[s] - 6) * 0.5)
            cands.append((c, s * hop, e * hop))
    cands.sort()
    period = SR / f0
    w = int(max(2 * period, 0.004 * SR))
    best = None
    for c, s, e in cands[:60]:
        ref = x[s - w:s + w]
        lo, hi = e - int(period), e + int(period) + 1
        best_e, best_r = e, -2.0
        for e2 in range(lo, hi):
            seg = x[e2 - w:e2 + w]
            if len(seg) != len(ref) or e2 >= len(x):
                continue
            r = float(ref @ seg) / (math.sqrt(float(ref @ ref) * float(seg @ seg)) + 1e-20)
            if r > best_r:
                best_r, best_e = r, e2
        total = c + (1 - best_r) * 25
        if best is None or total < best[0]:
            best = (total, s, best_e)
    total, s, e = best
    xf = int(min(0.15 * SR, 0.3 * (e - s)))
    return Loop(s, e, xf, total, vib)


def apply_loop(x: np.ndarray, loop: Loop) -> np.ndarray:
    """循环终点前 crossfade 个点与循环起点前的同样长度交叉淡化：放到终点时波形正好接上起点。"""
    s, e, n = loop.start, loop.end, loop.crossfade
    y = x[:e].copy()
    a_seg, b_seg = x[e - n:e], x[s - n:s]
    rho = float(a_seg @ b_seg) / (math.sqrt(float(a_seg @ a_seg) * float(b_seg @ b_seg)) + 1e-20)
    t = (np.arange(n) + 0.5) / n
    ga, gb = np.cos(t * math.pi / 2), np.sin(t * math.pi / 2)
    norm = np.sqrt(ga * ga + gb * gb + 2 * max(0.0, rho) * ga * gb)
    y[e - n:e] = (ga * a_seg + gb * b_seg) / norm
    return y


# ---------------------------------------------------------------- 一件乐器


@dataclass
class Sample:
    src: Src
    audio: np.ndarray
    key: float            # 实际音高（MIDI 小数）
    measured: float
    loudness: float       # 原始电平下的 K 计权响度（dB）
    peak: float
    loop: Loop | None = None
    store: float = 1.0    # 写进文件时乘的倍数
    gain: float = 1.0
    curve: float = 1.0
    file: str = ""
    lo: int = 0
    hi: int = 0
    release_file: str | None = None
    release_gain: float = 0.0
    release_seconds: float = 0.0


def process(spec: Spec, cache: Cache, src: Src, log) -> Sample:
    x = decode(cache.file(spec.repo, src.path))
    start = find_onset(x)
    expected = src.label + spec.shift
    f_exp = hz(expected)
    if spec.sustain:
        seg = x[start + int(0.6 * SR):start + int(1.1 * SR)]
    else:
        seg_len = int(min(0.6, max(0.25, 16 / f_exp)) * SR)
        seg = x[start + int(0.06 * SR):start + int(0.06 * SR) + seg_len]
    if spec.pitch == "harmonic":
        measured, octave_note, margin = measure_harmonic(seg, expected)
    elif spec.pitch == "peak":
        measured, octave_note, margin = measure_peak(seg, expected)
    else:
        measured, octave_note, margin = float(expected), expected, 0.0
    if octave_note != expected:
        log(f"    ! {src.path}: expected {name_of(expected)}, sounds like {name_of(octave_note)} ({measured:.2f})")
    if src.tune is not None:
        key = expected - src.tune / 100
        if abs(measured - key) > 0.3 and expected >= 84 and octave_note == expected:
            log(f"    ! {src.path}: retuned key {key:.2f} but measured {measured:.2f}; using the measurement (treble)")
            key = measured
        elif abs(measured - key) > 0.3:
            log(f"    ! {src.path}: retuned key {key:.2f} but measured {measured:.2f}; keeping the retuning (bass)")
    elif spec.pitch == "label":
        key = float(expected)
    else:
        key = measured - (octave_note - expected)
    if spec.keep_files:
        start = 0
    elif spec.sustain:
        r = rms_track(x, 0.02)
        steady = float(np.median(r[start + int(0.6 * SR):start + int(2.0 * SR)]))
        speak = start + int(np.argmax(r[start:] >= steady * 10 ** (-18 / 20)))
        trimmed = max(start, speak - int(0.03 * SR))
        if trimmed - start > int(0.02 * SR):
            log(f"    > {src.path}: starts {1000 * (trimmed - start) / SR:.0f} ms later (slow attack)")
        start = trimmed
    y = x[start:]
    if spec.keep_files:
        return Sample(src, y, key, measured, loudness_db(y, 0, min(len(y), int(0.8 * SR)), 0.4), float(np.abs(y).max()))
    if spec.sustain:
        loop = find_loop(y, hz(key), min(4.5, spec.max_seconds(expected)))
        y = apply_loop(y, loop)
        loud = loudness_db(y, loop.start, loop.end, None)
    else:
        loop = None
        end = decay_end(y, 0, spec.max_seconds(expected))
        y = y[:end].copy()
        y = fade_out(y, int(min(0.8 * SR, 0.3 * len(y))))
        loud = loudness_db(y, 0, min(len(y), int(0.8 * SR)), 0.4)
    fade_in = int((0.008 if spec.sustain else 0.001) * SR)
    y[:fade_in] *= np.linspace(0, 1, fade_in)
    return Sample(src, y, key, measured, loud, float(np.abs(y).max()), loop)


def smooth(notes: list[float], values: list[float], width: float = 7.0) -> list[float]:
    out = []
    for n in notes:
        near = [v for m, v in zip(notes, values) if abs(m - n) <= width]
        out.append(float(np.median(near)))
    return out


REF = -16.0  # 每件乐器在力度 127、音区平均处的目标响度（K 计权 dB），再加 Spec.level


def balance(spec: Spec, samples: list[Sample], log) -> None:
    """
    同一层各音拉平到这一层的平滑曲线（保留音区间的自然起伏，去掉录音里个别音的忽大忽小）；
    各层在自己力度上限处的响度落在 (力度 / 127) ^ curve 这条曲线上，所以换层时只换音色、不跳音量。
    """
    for s in samples:
        s.store = 1.0 if spec.keep_files else 0.891 / max(s.peak, 1e-9)
    targets: dict[int, float] = {}
    by_layer: dict[int, list[Sample]] = {}
    for s in samples:
        by_layer.setdefault(s.src.layer, []).append(s)
    # 音区起伏：每个采样比它那一层的中位数响多少，所有层放在一起按音高平滑——各层共用一条，换层时音量才接得上
    offsets: dict[int, float] = {}
    for layer, group in by_layer.items():
        mid = float(np.median([s.loudness for s in group]))
        for s in group:
            offsets[id(s)] = s.loudness - mid
    keys = [s.key for s in samples]
    pooled = smooth(keys, [offsets[id(s)] for s in samples])
    shape_at = {id(s): float(np.clip(v * spec.shape, -6, 6)) for s, v in zip(samples, pooled)}
    for layer, group in by_layer.items():
        vhi = spec.layers[layer][1]
        at_top = 20 * math.log10((vhi / 127) ** spec.curve) if spec.velocity else 0.0
        mid = float(np.median([s.loudness for s in group]))
        for s in group:
            targets[id(s)] = REF + spec.level + shape_at[id(s)] + at_top
            natural = mid + shape_at[id(s)] / max(spec.shape, 1e-6)
            if abs(natural - s.loudness) > 4:
                log(f"    ~ {s.src.path}: evened by {natural - s.loudness:+.1f} dB")
    for s in samples:
        stored = s.loudness + 20 * math.log10(s.store)
        s.gain = 10 ** ((targets[id(s)] - stored) / 20)
    over = float(np.percentile([s.gain for s in samples], 95))
    if over > 1:
        log(f"    ! {spec.kind}: needs {20 * math.log10(over):.1f} dB more than full scale; whole instrument lowered")
        for s in samples:
            s.gain /= over
    for s in samples:
        if s.gain > 1:
            log(f"    ~ {s.src.path}: {20 * math.log10(s.gain):.1f} dB short of its level (already at full scale)")
            s.gain = 1.0
    for s in samples:
        s.gain = round(s.gain, 4)


def zones(spec: Spec, samples: list[Sample]) -> None:
    """每层里每个音取最近的采样（一样近时取高的，往下变调）；相邻层交界处一样响。"""
    for layer in range(len(spec.layers)):
        group = sorted([s for s in samples if s.src.layer == layer], key=lambda s: s.key)
        for s in group:
            s.lo, s.hi = 999, -1
        for p in range(spec.low, spec.high + 1):
            best = min(group, key=lambda s: (abs(s.key - p), -s.key))
            best.lo, best.hi = min(best.lo, p), max(best.hi, p)
        for s in group:
            if s.hi < 0:
                s.lo = s.hi = -1   # 没用上（被别的采样覆盖），不写进表
    for s in samples:
        s.curve = spec.curve if spec.velocity else 0.0


def report_layers(spec: Spec, used: list[Sample], log) -> None:
    """换层处的响度差：每个音在下一层力度下限处与上一层力度上限处的响度（K 计权，dB）。"""
    if not spec.velocity or len(spec.layers) < 2:
        return
    worst = (0.0, None)
    for p in range(spec.low, spec.high + 1):
        for layer in range(1, len(spec.layers)):
            lo_zone = next((s for s in used if s.src.layer == layer - 1 and s.lo <= p <= s.hi), None)
            hi_zone = next((s for s in used if s.src.layer == layer and s.lo <= p <= s.hi), None)
            if not lo_zone or not hi_zone:
                continue
            vhi_prev = spec.layers[layer - 1][1]
            vlo, vhi = spec.layers[layer]

            def loud(z: Sample, v: int, top: int) -> float:
                return z.loudness + 20 * math.log10(z.store * z.gain * (v / top) ** z.curve)
            d = loud(hi_zone, vlo, vhi) - loud(lo_zone, vhi_prev, vhi_prev)
            if abs(d) > abs(worst[0]):
                worst = (d, f"{name_of(p)} layer {layer - 1}->{layer}")
    log(f"   layer switch: largest loudness step {worst[0]:+.2f} dB at {worst[1]}")


def encode(y: np.ndarray, path: Path, quality: float) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([FFMPEG, "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "-",
                    "-c:a", "libvorbis", "-q:a", str(quality), "-map_metadata", "-1", "-fflags", "+bitexact",
                    "-flags:a", "+bitexact", "-serial_offset", "1", str(path)],
                   input=np.clip(y, -1, 1).astype("<f4").tobytes(), check=True)


def decode_ogg(path: Path) -> np.ndarray:
    raw = subprocess.run([FFMPEG, "-v", "error", "-i", str(path), "-f", "f32le", "-ac", "1", "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def build(spec: Spec, cache: Cache, write: bool, log) -> list[Sample]:
    srcs = spec.sources(cache)
    log(f"== {spec.kind}: {len(srcs)} samples from {REPOS[spec.repo].title}")
    samples = []
    for src in sorted(srcs, key=lambda s: (s.layer, s.label)):
        samples.append(process(spec, cache, src, log))
    balance(spec, samples, log)
    zones(spec, samples)
    used = [s for s in samples if s.hi >= 0]
    for s in samples:
        if s.hi < 0:
            log(f"    - unused {s.src.path}")
    report_layers(spec, used, log)
    rels = spec.releases(cache) if spec.releases else {}
    out_dir = ASSETS / "sounds/instrument" / spec.kind
    total_seconds = 0.0
    for s in used:
        s.file = s.src.path.removesuffix(".ogg") if spec.keep_files else f"instrument/{spec.kind}/{round(s.key)}_{s.src.layer}"
        total_seconds += len(s.audio) / SR
        s.release_seconds = round(spec.release(round(s.key)), 3)
    rel_samples: dict[int, tuple[str, float, np.ndarray]] = {}
    for s in used:
        note = round(s.key)
        if note in rels and note not in rel_samples:
            r = decode(cache.file(spec.repo, rels[note]))
            r = r[find_onset(r):]
            r = fade_out(r[:int(0.9 * SR)].copy(), int(0.2 * SR))
            factor = s.gain * s.store * 10 ** (spec.release_level / 20)   # 和这个音同样的放大倍数
            store = min(0.891 / max(float(np.abs(r).max()), 1e-9), factor)
            rel_samples[note] = (f"instrument/{spec.kind}/rel_{note}", round(min(1.0, factor / store), 4), r * store)
            total_seconds += len(r) / SR
        if note in rel_samples:
            s.release_file, s.release_gain = rel_samples[note][:2]
    for s in sorted(used, key=lambda s: (s.src.layer, s.key)):
        loop = f" loop {s.loop.start / SR:.2f}-{s.loop.end / SR:.2f}s xf {s.loop.crossfade / SR * 1000:.0f}ms vib {s.loop.vibrato and round(s.loop.vibrato, 3)} cost {s.loop.cost:.1f}" if s.loop else ""
        log(f"   {s.src.layer} {name_of(round(s.key)):4s} key {s.key:7.2f} (measured {s.measured:7.2f}) {s.lo:3d}-{s.hi:3d} len {len(s.audio) / SR:5.2f}s "
            f"loud {s.loudness:6.1f} gain {s.gain:.3f} curve {s.curve:.2f}{loop}")
    log(f"   total {total_seconds:.0f} s of audio in {len(used)} samples + {len(rel_samples)} release")
    if write and spec.keep_files:
        write_map(spec, used)
    elif write:
        if out_dir.exists():
            for old in out_dir.glob("*.ogg"):
                old.unlink()
        for s in used:
            encode(s.audio * s.store, ASSETS / "sounds" / f"{s.file}.ogg", spec.quality)
        for file, _, audio in rel_samples.values():
            encode(audio, ASSETS / "sounds" / f"{file}.ogg", spec.quality)
        write_map(spec, used)
    return used


def write_map(spec: Spec, used: list[Sample]) -> None:
    zs = []
    for s in sorted(used, key=lambda s: (s.src.layer, s.key)):
        vlo, vhi = spec.layers[s.src.layer]
        z = {"file": s.file, "key": round(s.key, 3), "lo": s.lo, "hi": s.hi, "vlo": vlo, "vhi": vhi,
             "gain": s.gain, "curve": s.curve, "frames": len(s.audio), "release": s.release_seconds}
        if s.loop:
            z["loop"] = [s.loop.start, s.loop.end]
        if s.release_file:
            z["releaseSound"] = s.release_file
            z["releaseGain"] = s.release_gain
        zs.append(z)
    repo = REPOS[spec.repo]
    source = f"{repo.title} ({repo.license}) {repo.slug}@{repo.commit[:12]}" if repo.slug else repo.title
    data = {"source": source, "range": spec.range, "zones": zs}
    path = ASSETS / "instruments" / f"{spec.kind}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")


def pick(zones: list[dict], pitch: int, velocity: int) -> dict:
    """同 SampleMap.pick。"""
    best, cost = None, None
    for z in zones:
        if z["lo"] <= pitch <= z["hi"] and z["vlo"] <= velocity <= z["vhi"]:
            return z
        dv = z["vlo"] - velocity if velocity < z["vlo"] else max(0, velocity - z["vhi"])
        dp = z["lo"] - pitch if pitch < z["lo"] else max(0, pitch - z["hi"])
        c = dv * 1000 + dp
        if cost is None or c < cost:
            best, cost = z, c
    return best


def render_note(audio: np.ndarray, z: dict, pitch: int, velocity: int, hold: float, damps: bool) -> np.ndarray:
    """同游戏里的播放：变调（线性插值）、按住时在循环点之间循环、松键后按采样的松键时间淡出。"""
    ratio = 2 ** ((pitch - z["key"]) / 12)
    hold_n = int(hold * SR)
    tail = z["release"] if damps and z.get("release") else None
    total = hold_n + (int(tail * SR) if tail else int(len(audio) / ratio))
    pos = np.arange(total) * ratio
    if "loop" in z:
        s, e = z["loop"]
        over = pos >= e
        pos[over] = s + np.mod(pos[over] - s, e - s)
    else:
        pos = pos[pos < len(audio) - 1]
    i = pos.astype(int)
    frac = pos - i
    y = audio[i] * (1 - frac) + audio[np.minimum(i + 1, len(audio) - 1)] * frac
    vol = min(1.0, z["gain"] * (max(1, min(z["vhi"], velocity)) / z["vhi"]) ** z["curve"])
    y = y * vol
    if tail and len(y) > hold_n:
        n = len(y) - hold_n
        y[hold_n:] *= 10 ** (-2 * np.arange(n) / (tail * SR))
    return y


def demo(spec: Spec, out: Path) -> None:
    """一段试听：轻、重各弹一遍两个八度的音阶，再来几个和弦（钢琴另加最低、最高几个音）。"""
    data = json.loads((ASSETS / "instruments" / f"{spec.kind}.json").read_text(encoding="utf-8"))
    zones = data["zones"]
    cache: dict[str, np.ndarray] = {}
    damps = spec.release(60) > 0
    events = []   # (起始秒, 音高, 力度, 按住秒)
    t = 0.3
    root = max(spec.low, min(spec.high - 24, 60 if spec.low <= 60 else spec.low))
    scale = [0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24]
    step = 0.42 if spec.sustain else 0.28
    for velocity in ((60, 112) if spec.velocity else (100,)):
        for d in scale:
            p = root + d
            if p <= spec.high:
                events.append((t, p, velocity, step * 0.9))
                t += step
        t += 0.4
    for chord in ((0, 4, 7, 12), (5, 9, 12, 17), (7, 11, 14, 19), (0, 4, 7, 12)):
        for d in chord:
            p = root + d
            if p <= spec.high:
                events.append((t, p, 96, 1.6))
        t += 1.8
    if spec.kind == "grand_piano":
        for i, p in enumerate((21, 28, 33, 40, 96, 100, 103, 108)):
            events.append((t + i * 0.5, p, 90, 1.2))
        t += 4.5
    mix = np.zeros(int((t + 12) * SR))
    for start, p, v, hold in events:
        z = pick(zones, p, v)
        if z["file"] not in cache:
            cache[z["file"]] = decode_ogg(ASSETS / "sounds" / f"{z['file']}.ogg")
        y = render_note(cache[z["file"]], z, p, v, hold, damps)
        i = int(start * SR)
        n = min(len(y), len(mix) - i)
        mix[i:i + n] += y[:n]
    nz = np.nonzero(np.abs(mix) > 1e-4)[0]
    mix = mix[:nz[-1] + SR // 2] if len(nz) else mix
    peak = float(np.abs(mix).max())
    if peak > 0.98:
        mix *= 0.98 / peak
    out.mkdir(parents=True, exist_ok=True)
    encode(mix, out / f"demo_{spec.kind}.ogg", 6)


def main() -> None:
    global FFMPEG
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", required=True, type=Path)
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--ffmpeg", default="ffmpeg")
    ap.add_argument("--report", action="store_true")
    ap.add_argument("--demo", type=Path)
    args = ap.parse_args()
    FFMPEG = args.ffmpeg
    cache = Cache(args.cache)
    kinds = args.only or list(SPECS)

    def log(msg: str) -> None:
        print(msg, flush=True)

    for kind in kinds:
        build(SPECS[kind], cache, not args.report, log)
        if args.demo and not args.report:
            demo(SPECS[kind], args.demo)
    if not args.report:
        size = sum(f.stat().st_size for f in (ASSETS / "sounds/instrument").rglob("*.ogg"))
        log(f"instrument sounds now {size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
