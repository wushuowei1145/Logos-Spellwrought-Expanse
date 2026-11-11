"""乐器音高核对（#243）：两层音源（模组本体的合成、「音乐厅音源」资源包的录音），每件乐器：

1. 取区：音域内每个音、每个力度层都按游戏里同样的办法（tools/instrument_sources.py 的 pick，与 SampleMap 一致）取到一个区，
   变调不超过 4 个半音；
2. 音准：每个区的采样实际音高与表里记的音高（key）差多少音分。持续音在循环段里量（循环段是整数个揉弦周期，
   不会被揉弦带偏），敲击、拨弦量起音后的头一段。八度不在这里判：合成的一层按 key 的频率生成，录音的一层按库自带的
   SFZ 与文件名定八度；谐波法判八度在很低的音（基音弱）和接近纯音的钢片琴上不可靠，生成脚本自己会报。

    uv run --no-project --with numpy --with scipy python tools/instrument_audit.py --ffmpeg <带 libvorbis 的 ffmpeg>
        [--only violin cello ...] [--layer mod|pack] [--cents 25]

管钟、定音鼓的分音不谐和（耳朵听到的音高不是谐波的基音），只核对取区。管风琴不用采样表（客户端按音栓现场合成），
由 OrganSynthTest 核对。纸带移调、键盘起点在 Java 的单元测试里核对（MusicRollTest、KeyLayoutTest）。
"""
import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import instrument_sources as rec  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
LAYERS = {
    "mod": ROOT / "src/main/resources/assets/logos_dimension",
    "pack": ROOT / "resourcepacks/concert_hall/assets/logos_dimension",
}
INHARMONIC = {"tubular_bells", "timpani"}


def cents_off(seg: np.ndarray, key: float) -> float | None:
    """在 key 所在的八度里量音准：前几根谐波各自的峰按强弱加权，折算成基音，返回比 key 高多少音分。"""
    X, df = rec.spectrum(seg)
    f0 = rec.hz(key)
    num = den = 0.0
    for k in range(1, 7):
        f = k * f0
        if f > rec.SR * 0.45:
            break
        lo, hi = max(1, int(f * 2 ** (-0.6 / 12) / df)), int(f * 2 ** (0.6 / 12) / df) + 1
        band = X[lo:hi]
        if band.size == 0:
            continue
        i = int(np.argmax(band))
        peak = (lo + i) * df
        # 抛物线插值求峰的准确位置
        if 0 < i < band.size - 1:
            a, b, c = np.log(band[i - 1] + 1e-15), np.log(band[i] + 1e-15), np.log(band[i + 1] + 1e-15)
            d = a - 2 * b + c
            if d < 0:
                peak = (lo + i + 0.5 * (a - c) / d) * df
        w = float(band[i])
        num += w * math.log2(peak / k / f0)
        den += w
    return None if den <= 0 else 1200 * num / den


def segment(audio: np.ndarray, z: dict) -> np.ndarray:
    if "loop" in z:
        s, e = z["loop"]
        return audio[s:e]
    f = rec.hz(z["key"])
    n = int(min(0.6, max(0.25, 16 / f)) * rec.SR)
    start = int(0.06 * rec.SR)
    return audio[start:start + n]


def audit(layer: str, assets: Path, only: set[str] | None, limit: float) -> list[tuple]:
    rows = []
    for map_path in sorted((assets / "instruments").glob("*.json")):
        kind = map_path.stem
        if only and kind not in only:
            continue
        m = json.loads(map_path.read_text(encoding="utf-8"))
        zones = m["zones"]
        low, high = min(z["lo"] for z in zones), max(z["hi"] for z in zones)
        problems = []
        for vlo, vhi in sorted({(z["vlo"], z["vhi"]) for z in zones}):
            for v in range(vlo, vhi + 1):
                for p in range(low, high + 1):
                    z = rec.pick(zones, p, v)
                    if not (z["lo"] <= p <= z["hi"] and z["vlo"] <= v <= z["vhi"]):
                        problems.append(f"{rec.name_of(p)} v{v}: no zone covers it")
                    elif abs(p - z["key"]) > 4.01:
                        problems.append(f"{rec.name_of(p)} v{v}: zone {z['file']} is {abs(p - z['key']):.2f} semitones away")
        worst = 0.0
        if kind not in INHARMONIC:
            for z in zones:
                audio = rec.decode_ogg(assets / "sounds" / f"{z['file']}.ogg")
                seg = segment(audio, z)
                if len(seg) < 2048 or float(np.abs(seg).max()) < 1e-6:
                    problems.append(f"{z['file']}: silent or too short")
                    continue
                c = cents_off(seg, z["key"])
                if c is None:
                    problems.append(f"{z['file']}: no harmonics near key {z['key']}")
                    continue
                worst = max(worst, abs(c))
                if abs(c) > limit:
                    problems.append(f"{z['file']}: {c:+.0f} cents from its key {z['key']}")
        rows.append((layer, kind, f"{rec.name_of(low)}-{rec.name_of(high)}", len(zones), worst, problems))
    return rows


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ffmpeg", default="ffmpeg")
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--layer", choices=list(LAYERS))
    ap.add_argument("--cents", type=float, default=25.0)
    args = ap.parse_args()
    rec.FFMPEG = args.ffmpeg
    only = set(args.only) if args.only else None
    rows = []
    for layer, assets in LAYERS.items():
        if args.layer and layer != args.layer:
            continue
        if not (assets / "instruments").exists():
            print(f"(no {layer} layer at {assets})")
            continue
        rows += audit(layer, assets, only, args.cents)
    failed = 0
    for layer, kind, rng, zones, worst, problems in rows:
        flag = "OK " if not problems else "BAD"
        note = " (inharmonic: zones only)" if kind in INHARMONIC else f", worst {worst:4.1f} cents"
        print(f"{flag} {layer:4s} {kind:14s} {rng:9s} {zones:4d} zones{note}")
        for line in problems[:12]:
            print(f"      {line}")
        if len(problems) > 12:
            print(f"      ... {len(problems) - 12} more")
        failed += bool(problems)
    print(f"{len(rows) - failed}/{len(rows)} instrument layers clean")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
