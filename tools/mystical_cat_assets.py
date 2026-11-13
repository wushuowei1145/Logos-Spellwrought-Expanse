"""灵猫的游戏资源：从仓库外的模型目录导入模型、各毛色贴图、零件表与动画，并画刷怪蛋。

    python tools/mystical_cat_assets.py [--source <cat_model/mystical_cat 目录>] [--fox <fox_model/divine_fox 目录>]

模型目录（默认 ../cat_model/mystical_cat，维护者的部件库）里已合并、导出好的文件：

- `build/exports/mystical_cat.geo.json`、`mystical_cat.png`：完整部件库的模型与贴图（build_race.py 合并）；
- `build/parts.json`：零件表，原样写成 `data/logos_dimension/npc_races/mystical_cat.json`；
- `build/exports/mystical_cat.animation.json` 里的 `idle`：素体自带的待机；
- `tools/coats.py`：毛色表。贴图里头发、耳朵、尾巴与瞳孔用的是占位颜色，这里按每种毛色整体替换，
  写成 `textures/entity/mystical_cat/<毛色>.png`（同一套 UV），默认贴图 `textures/entity/mystical_cat.png` 是第一种。

动画：
- 移动类动作借用本模组森林精灵动画文件里已经按 2 倍制作换算好的车万女仆（TLM）动画（CC BY-NC-SA 4.0，CREDITS.md 登记），
  打招呼、拒绝借森林精灵自制的剧情动作；三者骨架相同，只保留灵猫模型里有的骨骼。
- `sit`：神狐死亡动画（`fox_model/divine_fox/Animation/divine_fox_death.animation.json`）0.45 秒处的鸭子坐，
  上身扶正、头稍歪，做成坐在地上的循环姿势。
- 常驻叠加层：`pre_parallel0` 眨眼、`pre_parallel1` 抖耳沿用神狐的曲线（骨骼名换成灵猫的），
  `pre_parallel2` 摆尾在这里生成：尾根左右轻摆，每节比上一节晚一点、幅度大一点，像波浪从尾根传到尾尖。
- `stretch` 伸懒腰、`wash` 舔爪洗脸、`purr` 被摸头时眯眼歪头：这里写的剧情动作。
"""
import argparse
import importlib.util
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension"
GECKO = ASSETS / "geckolib"
DEFAULT_SOURCE = ROOT.parent / "cat_model/mystical_cat"
FOX = ROOT.parent / "fox_model/divine_fox"
ELF_ANIMATION = GECKO / "animations/entity/forest_elf.animation.json"
BORROWED = ["walk", "jump", "attacked", "ladder_up", "ladder_stillness", "ladder_down", "swim_stand", "swing_hand"]
ELF_ACTIONS = ["greet", "refuse"]
SIT_TIME = "0.45"
# 鸭子坐用到的骨骼：下半身与整体位置照搬，上身扶正
SIT_BONES = ["AllBody", "DownBody", "Skirt", "LeftLeg", "LeftLowerLeg", "LeftFoot", "RightLeg", "RightLowerLeg", "RightFoot"]


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, content, indent=2):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, ensure_ascii=False, indent=indent) + "\n", encoding="utf-8", newline="\n")


def bones_of(geo):
    return {b["name"] for b in geo["minecraft:geometry"][0]["bones"]}


def only(anim, bones):
    out = {k: v for k, v in anim.items() if k != "bones"}
    out["bones"] = {n: c for n, c in anim["bones"].items() if n in bones}
    return out


def r(v):
    return [round(float(x), 4) + 0.0 for x in v]


def bed(rot):
    """作者写的是 Blockbench 里看到的角度；动画文件里 X、Y 取反（GeckoLib 读的时候再取反回来）。

    方向（对照森林精灵的行礼动画核对过）：X 为正时朝下的骨骼（手臂）末端向前抬，朝上的骨骼（上身、头）向后仰；
    左臂 Y 为负、右臂 Y 为正时手往身前收；垂着的左臂 Z 为负往外张，举过头顶后反过来，Z 为正往外张。
    """
    return r([-rot[0], -rot[1], rot[2]])


def bed_pos(pos):
    return r([-pos[0], pos[1], pos[2]])


def track(times, values, kind="rotation"):
    conv = bed if kind == "rotation" else bed_pos if kind == "position" else r
    return {f"{t:g}" if t else "0.0": conv(v) for t, v in zip(times, values)}


# ---------------------------------------------------------------- 常驻叠加层

def blink_and_ears(bones):
    layers = read(FOX / "Animation/ambient/divine_fox_ambient.animation.json")["animations"]
    blink = layers["pre_parallel0"]
    out0 = {"loop": True, "animation_length": blink["animation_length"], "bones": {}}
    for name, channels in blink["bones"].items():
        if name.startswith("eyes_amber_round_"):
            out0["bones"]["eyes_cat_round_" + name[len("eyes_amber_round_"):]] = channels
    ears = layers["pre_parallel1"]
    out1 = {"loop": True, "animation_length": ears["animation_length"], "bones": {}}
    for side in ("left", "right"):
        curve = ears["bones"][f"ear_fox_upright_{side}"]
        for style in ("upright", "folded", "tufted"):
            out1["bones"][f"ear_cat_{style}_{side}"] = curve
    for layer in (out0, out1):
        missing = [n for n in layer["bones"] if n not in bones]
        assert not missing, missing
    return out0, out1


def tail_sway(bones, length=3.2, steps=32):
    """尾根左右摆 ±5°，每节晚 0.11 秒、幅度从 1.4° 加到 3.6°；尾尖几节再带一点上下的卷动。"""
    chains = {}
    for n in bones:
        if n.startswith("tail_cat_") and "_seg" in n:
            chains.setdefault(n.rsplit("_seg", 1)[0], []).append(n)
    times = [length * i / steps for i in range(steps + 1)]
    out = {"Tail": {"rotation": track(times, [[0, 5 * math.sin(2 * math.pi * t / length), 0] for t in times])}}
    for prefix, segs in chains.items():
        segs.sort()
        phase = .5 if prefix.endswith("chain2") else 0      # 猫又的两条尾巴错开半拍
        for i, n in enumerate(segs):
            amp = 1.4 + 2.2 * i / max(1, len(segs) - 1)
            lag = .11 * i
            curl = 2.5 if i >= len(segs) - 3 else 0
            vals = []
            for t in times:
                w = 2 * math.pi * ((t - lag) / length + phase)
                vals.append([curl * math.sin(2 * w), amp * math.sin(w), 0])
            out[n] = {"rotation": track(times, vals)}
    return {"loop": True, "animation_length": length, "bones": out}


# ---------------------------------------------------------------- 姿势与剧情动作

def sit(bones):
    death = read(FOX / "Animation/divine_fox_death.animation.json")["animations"]["death"]["bones"]
    out = {}
    for n in SIT_BONES:
        channels = {}
        for ch, keys in death.get(n, {}).items():
            if isinstance(keys, dict) and SIT_TIME in keys:
                v = keys[SIT_TIME]
                channels[ch] = {"0.0": v["post"] if isinstance(v, dict) else v}
        if channels:
            out[n] = channels
    # 上身扶正一点，头歪向一侧；双手搭在身前的地上
    out["UpBody"] = {"rotation": {"0.0": bed([3, 0, 0])}}
    out["Head"] = {"rotation": track([0, 2, 4], [[4, 0, 6], [6, 3, 9], [4, 0, 6]])}
    out["LeftArm"] = {"rotation": {"0.0": bed([24, -6, 6])}}
    out["RightArm"] = {"rotation": {"0.0": bed([24, 6, -6])}}
    out["LeftForeArm"] = {"rotation": {"0.0": bed([18, 0, 0])}}
    out["RightForeArm"] = {"rotation": {"0.0": bed([18, 0, 0])}}
    return {"loop": True, "animation_length": 4.0, "bones": {k: v for k, v in out.items() if k in bones}}


def stretch():
    """伸懒腰：双臂举过头顶、上身后仰、踮脚，停一下再放松。2.4 秒，播一次。"""
    t = [0, .5, 1.3, 1.7, 2.4]
    up = [[0, 0, 0], [150, 0, 0], [165, 0, 0], [150, 0, 0], [0, 0, 0]]
    return {"loop": False, "animation_length": 2.4, "bones": {
        # 举过头顶以后，左臂 Z 为正才是往外张开（垂着时相反）
        "LeftArm": {"rotation": track(t, [[a[0], 0, a[0] * .15] for a in up])},
        "RightArm": {"rotation": track(t, [[a[0], 0, -a[0] * .15] for a in up])},
        "LeftForeArm": {"rotation": track(t, [[0, 0, 0], [10, 0, 0], [6, 0, 0], [10, 0, 0], [0, 0, 0]])},
        "RightForeArm": {"rotation": track(t, [[0, 0, 0], [10, 0, 0], [6, 0, 0], [10, 0, 0], [0, 0, 0]])},
        "UpBody": {"rotation": track(t, [[0, 0, 0], [6, 0, 0], [11, 0, 0], [6, 0, 0], [0, 0, 0]])},
        "Head": {"rotation": track(t, [[0, 0, 0], [10, 0, 0], [16, 0, 4], [8, 0, 0], [0, 0, 0]])},
        "AllBody": {"position": track(t, [[0, 0, 0], [0, .6, 0], [0, .9, 0], [0, .4, 0], [0, 0, 0]], "position")},
    }}


def wash():
    """舔爪洗脸：右手收到脸前，手背在脸颊上来回抹三下，头跟着轻点。2.0 秒，播一次。"""
    t = [0, .35, .6, .85, 1.1, 1.35, 1.6, 2.0]
    return {"loop": False, "animation_length": 2.0, "bones": {
        "RightArm": {"rotation": track(t, [[0, 0, 0], [62, -18, -6], [70, -18, -6], [62, -18, -6], [70, -18, -6], [62, -18, -6], [66, -18, -6], [0, 0, 0]])},
        "RightForeArm": {"rotation": track(t, [[0, 0, 0], [96, 0, 0], [104, 0, 0], [96, 0, 0], [104, 0, 0], [96, 0, 0], [100, 0, 0], [0, 0, 0]])},
        "RightHand": {"rotation": track(t, [[0, 0, 0], [20, 0, 0], [30, 0, 0], [20, 0, 0], [30, 0, 0], [20, 0, 0], [25, 0, 0], [0, 0, 0]])},
        "Head": {"rotation": track(t, [[0, 0, 0], [-8, -6, -8], [-12, -6, -10], [-8, -6, -8], [-12, -6, -10], [-8, -6, -8], [-10, -6, -9], [0, 0, 0]])},
    }}


def purr():
    """被摸头：缩一缩脖子、歪头蹭两下、双手收在胸前。1.6 秒，播一次（表情由实体换成开心）。"""
    t = [0, .25, .6, .95, 1.3, 1.6]
    return {"loop": False, "animation_length": 1.6, "bones": {
        "Head": {"rotation": track(t, [[0, 0, 0], [-10, 0, 8], [-10, 4, -8], [-10, -4, 9], [-8, 0, 4], [0, 0, 0]])},
        "UpBody": {"rotation": track(t, [[0, 0, 0], [-4, 0, 0], [-4, 0, 0], [-4, 0, 0], [-3, 0, 0], [0, 0, 0]])},
        "LeftArm": {"rotation": track(t, [[0, 0, 0], [38, -26, 4], [38, -26, 4], [38, -26, 4], [32, -22, 4], [0, 0, 0]])},
        "RightArm": {"rotation": track(t, [[0, 0, 0], [38, 26, -4], [38, 26, -4], [38, 26, -4], [32, 22, -4], [0, 0, 0]])},
        "LeftForeArm": {"rotation": track(t, [[0, 0, 0], [70, 0, 0], [70, 0, 0], [70, 0, 0], [60, 0, 0], [0, 0, 0]])},
        "RightForeArm": {"rotation": track(t, [[0, 0, 0], [70, 0, 0], [70, 0, 0], [70, 0, 0], [60, 0, 0], [0, 0, 0]])},
    }}


def animations(source, bones):
    exported = read(source / "build/exports/mystical_cat.animation.json")["animations"]
    elf = read(ELF_ANIMATION)["animations"]
    out = {"idle": only(exported["idle"], bones)}
    for name in BORROWED + ELF_ACTIONS:
        out[name] = only(elf[name], bones)
    out["sit"] = sit(bones)
    out["stretch"] = stretch()
    out["wash"] = wash()
    out["purr"] = purr()
    out["pre_parallel0"], out["pre_parallel1"] = blink_and_ears(bones)
    out["pre_parallel2"] = tail_sway(bones)
    for name, anim in out.items():
        missing = [n for n in anim["bones"] if n not in bones]
        assert not missing, (name, missing[:5])
    return {"format_version": "1.8.0", "animations": out}


# ---------------------------------------------------------------- 贴图与刷怪蛋

def load_coats(source):
    spec = importlib.util.spec_from_file_location("coats", source / "tools/coats.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def textures(source):
    coats = load_coats(source)
    atlas = np.asarray(Image.open(source / "build/exports/mystical_cat.png").convert("RGBA"))
    folder = ASSETS / "textures/entity/mystical_cat"
    folder.mkdir(parents=True, exist_ok=True)
    names = list(coats.COATS)
    for name in names:
        Image.fromarray(coats.remap(atlas, coats.COATS[name])).save(folder / f"{name}.png", optimize=True)
    Image.fromarray(coats.remap(atlas, coats.COATS[names[0]])).save(ASSETS / "textures/entity/mystical_cat.png", optimize=True)
    return names


def spawn_egg():
    """16×16 刷怪蛋：藏青底（学院披肩）、橘色猫耳轮廓、青色菱形一点。"""
    rows = [
        "......####......",
        ".....#aaaa#.....",
        "....#abaaba#....",
        "...#abbaabba#...",
        "...#aaaaaaaa#...",
        "..#aaaaaaaaaa#..",
        "..#aaaaccaaaa#..",
        "..#aaacddcaaa#..",
        ".#aaaaaccaaaaa#.",
        ".#aaaaaaaaaaaa#.",
        ".#aeeaaaaaaeea#.",
        ".#aaeeeeeeeeaa#.",
        "..#aaaaaaaaaa#..",
        "..#aaaaaaaaaa#..",
        "...##aaaaaa##...",
        ".....######.....",
    ]
    colors = {"#": (35, 43, 75, 255), "a": (74, 88, 136, 255), "b": (242, 160, 79, 255), "c": (47, 178, 191, 255),
              "d": (230, 255, 255, 255), "e": (221, 178, 84, 255)}
    img = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colors:
                img.putpixel((x, y), colors[ch])
    img.putpixel((6, 2), (255, 255, 255, 255))
    path = ASSETS / "textures/item/mystical_cat_spawn_egg.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    write(ASSETS / "models/item/mystical_cat_spawn_egg.json",
          {"parent": "minecraft:item/generated", "textures": {"layer0": "logos_dimension:item/mystical_cat_spawn_egg"}})
    write(ASSETS / "items/mystical_cat_spawn_egg.json",
          {"model": {"type": "minecraft:model", "model": "logos_dimension:item/mystical_cat_spawn_egg"}})


def main():
    global FOX
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", default=str(DEFAULT_SOURCE))
    parser.add_argument("--fox", default=str(FOX), help="神狐部件库（借眨眼、抖耳曲线与鸭子坐）")
    args = parser.parse_args()
    FOX = Path(args.fox)
    source = Path(args.source)
    geo = read(source / "build/exports/mystical_cat.geo.json")
    parts = read(source / "build/parts.json")
    bones = bones_of(geo)
    write(GECKO / "models/entity/mystical_cat.geo.json", geo, indent=None)
    coats = textures(source)
    write(ROOT / "src/main/resources/data/logos_dimension/npc_races/mystical_cat.json", parts, indent=1)
    write(GECKO / "animations/entity/mystical_cat.animation.json", animations(source, bones), indent=1)
    spawn_egg()
    print("bones", len(bones), "parts", sum(len(s["parts"]) for s in parts["slots"].values()), "coats", coats)


if __name__ == "__main__":
    main()
