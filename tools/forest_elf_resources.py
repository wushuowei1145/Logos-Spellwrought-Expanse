"""森林精灵的资源：刷怪蛋（代码绘制）与语音（按素材标签归类、转成 ogg、写 sounds.json）。

    python tools/forest_elf_resources.py [--voices <素材目录>] [--ffmpeg <ffmpeg 路径>]

语音素材文件名里方括号或冒号后的标签说明情绪，例如「女[痛苦地]呃啊！」。Minecraft 只认 ogg：
素材目录下有 forest_elf_ogg/（维护者已转好的 ogg）就直接复制；否则把 mp3 转成 ogg，
转换需要 ffmpeg（或 Python 的 soundfile 模块），两者都没有时只画刷怪蛋，不动声音。
注意：Minecraft 只对单声道声音按距离衰减，立体声的素材会在哪里听都一样响，脚本会列出来。
"""
import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension"
DEFAULT_VOICES = ROOT.parent.parent / "声音素材" / "森林精灵"

# 标签 → 事件（对标村民：闲时、搭话、肯定、不满、警觉、发力、施法、受伤、死亡）。
# 女声用于现在的成年精灵；中性嗓音先登记在 sounds.json，留给以后的幼年与中性外观。
LABELS = [
    ("死亡", "death"), ("痛苦", "hurt"), ("警觉", "alert"), ("发力", "attack"), ("不满", "no"),
    ("肯定", "yes"), ("语气友好", "greet"), ("热情", "greet"), ("高兴", "greet"),
    ("心情愉快", "ambient"), ("语气愉快", "ambient"), ("思索", "ambient"), ("疑惑", "ambient"),
]
# 施法时的语音借用「语气友好」与「高兴」两类。
CAST_FROM = ("greet",)


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def spawn_egg():
    """16×16 刷怪蛋：浅黄绿底、深绿叶斑、两点肤色；描边一级深色，左上一点高光。"""
    rows = [
        "......####......",
        ".....#aaaa#.....",
        "....#aaaaaa#....",
        "...#aabaaaaa#...",
        "...#aaaccaaa#...",
        "..#aaaaccaaaa#..",
        "..#aaaaaaaabb#..",
        "..#abbaaaaabba#.",
        ".#aabbaaaaaaaa#.",
        ".#aaaaaaasaaaa#.",
        ".#aaaccaaaaaaa#.",
        ".#aaaccaaaabba#.",
        "..#aaaaaaabba#..",
        "..#aaaasaaaaa#..",
        "...##aaaaaa##...",
        ".....######.....",
    ]
    colors = {"#": (51, 93, 47, 255), "a": (204, 227, 138, 255), "b": (111, 162, 77, 255),
              "c": (77, 127, 60, 255), "s": (246, 203, 187, 255)}
    img = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colors:
                img.putpixel((x, y), colors[ch])
    img.putpixel((6, 2), (240, 248, 214, 255))
    path = ASSETS / "textures/item/forest_elf_spawn_egg.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    write(ASSETS / "models/item/forest_elf_spawn_egg.json",
          {"parent": "minecraft:item/generated", "textures": {"layer0": "logos_dimension:item/forest_elf_spawn_egg"}})
    write(ASSETS / "items/forest_elf_spawn_egg.json",
          {"model": {"type": "minecraft:model", "model": "logos_dimension:item/forest_elf_spawn_egg"}})


def classify(name):
    voice = "female" if name.startswith("女") else "neutral" if name.startswith("中性") else None
    for label, event in LABELS:
        if label in name:
            return voice, event
    return voice, None


def converter(ffmpeg):
    if ffmpeg and Path(ffmpeg).exists():
        return lambda src, dst: subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", str(src), "-ac", "1", "-c:a", "libvorbis",
                                                "-q:a", "4", str(dst)], check=True)
    found = shutil.which("ffmpeg")
    if found:
        return converter(found)
    try:
        import soundfile
    except ImportError:
        return None

    def convert(src, dst):
        data, rate = soundfile.read(str(src), always_2d=True)
        soundfile.write(str(dst), data.mean(axis=1), rate, format="OGG", subtype="VORBIS")
    return convert


def channels(path):
    """ogg 的声道数（Vorbis 标识头第 11 字节）。"""
    data = Path(path).read_bytes()[:64]
    i = data.find(b"vorbis")
    return data[i + 11] if i >= 0 else 0


def voices(folder, ffmpeg):
    ready = Path(folder) / "forest_elf_ogg"
    if ready.is_dir() and any(ready.glob("*.ogg")):
        files = sorted(ready.glob("*.ogg"))
        convert = lambda src, dst: shutil.copyfile(src, dst)
    else:
        files = sorted(Path(folder).glob("*.mp3"))
        convert = converter(ffmpeg)
    if not files or convert is None:
        print(f"语音：{'没有素材' if not files else '没有 ffmpeg 或 soundfile，跳过转换'}（{folder}）")
        return
    out = ASSETS / "sounds/entity/forest_elf"
    out.mkdir(parents=True, exist_ok=True)
    events = {}
    skipped, stereo = [], []
    for src in files:
        voice, event = classify(src.stem)
        if voice is None or event is None:
            skipped.append(src.name)
            continue
        # 文件名用素材名的哈希，避免中文与符号；同一素材重跑得到同一个名字
        name = f"{voice}_{event}_{hashlib.sha1(src.name.encode('utf-8')).hexdigest()[:8]}"
        convert(src, out / f"{name}.ogg")
        if channels(out / f"{name}.ogg") > 1:
            stereo.append(src.name)
        events.setdefault((voice, event), []).append(f"logos_dimension:entity/forest_elf/{name}")
    sounds = {}
    for (voice, event), files_ in sorted(events.items()):
        key = f"entity.forest_elf.{event}" if voice == "female" else f"entity.forest_elf.neutral.{event}"
        sounds[key] = {"subtitle": f"subtitles.logos_dimension.forest_elf.{event}", "sounds": files_}
    female = {k: v for k, v in sounds.items() if ".neutral." not in k}
    cast = [s for e in CAST_FROM for s in female.get(f"entity.forest_elf.{e}", {}).get("sounds", [])]
    sounds["entity.forest_elf.cast"] = {"subtitle": "subtitles.logos_dimension.forest_elf.cast", "sounds": cast}
    write(ASSETS / "sounds.json", sounds)
    print(f"语音：{sum(len(v) for v in events.values())} 个文件，{len(sounds)} 个事件；未归类 {skipped}；立体声（不随距离衰减）{stereo}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--voices", default=str(DEFAULT_VOICES))
    parser.add_argument("--ffmpeg")
    args = parser.parse_args()
    spawn_egg()
    voices(args.voices, args.ffmpeg)


if __name__ == "__main__":
    main()
