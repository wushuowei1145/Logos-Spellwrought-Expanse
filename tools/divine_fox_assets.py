"""神狐的游戏资源：从仓库外的模型目录导入模型、贴图、零件表与动画，并画刷怪蛋。

    python tools/divine_fox_assets.py [--source <fox_model/divine_fox 目录>]

模型目录（默认 ../fox_model/divine_fox，维护者的部件库）里已合并、导出好的文件：

- `build/exports/divine_fox.geo.json`、`divine_fox.png`：完整部件库的模型与贴图（build_race.py 合并）；
- `build/parts.json`：零件表，原样写成 `data/logos_dimension/npc_races/divine_fox.json`；
- `build/exports/divine_fox.animation.json` 里的 `idle`：素体自带的待机；
- `Animation/side_lying_trial/divine_fox_death.animation.json`：侧倒后面朝地面的死亡动画（维护者选定的版本）；
- `Animation/ambient/divine_fox_ambient.animation.json`：常驻叠加层 `pre_parallel0` 眨眼、`pre_parallel1` 耳朵抖动、
  `pre_parallel2` 尾巴轻摆（create_ambient_loops.py）。

移动类动作借用本模组森林精灵动画文件里已经按 2 倍制作换算好的车万女仆（TLM）动画（CC BY-NC-SA 4.0，CREDITS.md 登记），
两者骨架相同，只保留神狐模型里有的骨骼。

死亡动画按 160 帧/秒逐帧烘焙，导入时去掉能由前后两帧线性插值还原（误差 < 0.001）的中间帧，动作不变、文件小得多。

叠加层在主状态之前播放，主状态动画用到同一骨骼时会盖过它（docs/NPC_ANIMATIONS.md）。死亡动画本身已经接管整条尾巴；
这里再给眨眼与抖耳两层用到的骨骼通道补上静止帧，倒下后不再眨眼、抖耳。
"""
import argparse
import json
import shutil
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension"
GECKO = ASSETS / "geckolib"
DEFAULT_SOURCE = ROOT.parent / "fox_model/divine_fox"
ELF_ANIMATION = GECKO / "animations/entity/forest_elf.animation.json"
# 借用的 TLM 动画（森林精灵文件里已换算好）；登记在 CREDITS.md
BORROWED = ["walk", "jump", "attacked", "ladder_up", "ladder_stillness", "ladder_down", "swim_stand", "swing_hand"]
LAYERS = ["pre_parallel0", "pre_parallel1", "pre_parallel2"]


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, content, indent=2):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, ensure_ascii=False, indent=indent) + "\n", encoding="utf-8")


def bones_of(geo):
    return {b["name"] for b in geo["minecraft:geometry"][0]["bones"]}


def only(anim, bones):
    out = {k: v for k, v in anim.items() if k != "bones"}
    out["bones"] = {n: c for n, c in anim["bones"].items() if n in bones}
    return out


def prune(anim, tolerance=1e-3):
    """去掉线性插值就能还原的中间关键帧（只处理纯数组的线性帧），保留首尾帧。"""
    for channels in anim["bones"].values():
        for ch, keys in channels.items():
            if not isinstance(keys, dict) or len(keys) < 3 or not all(isinstance(v, list) for v in keys.values()):
                continue
            times = sorted(keys, key=float)
            t = [float(k) for k in times]
            v = [keys[k] for k in times]

            def fits(a, b):
                # a、b 之间的每一帧都落在 a→b 的直线上
                return all(abs(v[m][i] - (v[a][i] + (v[b][i] - v[a][i]) * (t[m] - t[a]) / (t[b] - t[a]))) < tolerance
                           for m in range(a + 1, b) for i in range(3))

            kept = [0]
            for i in range(1, len(t) - 1):
                if not fits(kept[-1], i + 1):
                    kept.append(i)
            kept.append(len(t) - 1)
            channels[ch] = {times[i]: v[i] for i in kept}
    return anim


def animations(source, bones):
    exported = read(source / "build/exports/divine_fox.animation.json")["animations"]
    elf = read(ELF_ANIMATION)["animations"]
    death = read(source / "Animation/side_lying_trial/divine_fox_death.animation.json")["animations"]["death"]
    layers = read(source / "Animation/ambient/divine_fox_ambient.animation.json")["animations"]
    out = {"idle": only(exported["idle"], bones)}
    for name in BORROWED:
        out[name] = only(elf[name], bones)
    death = prune(only(death, bones))
    # 倒下后停止眨眼、高光抖动与抖耳：叠加层用到的每个骨骼通道，死亡动画没有的都补一个静止帧
    rest = {"rotation": [0, 0, 0], "position": [0, 0, 0], "scale": [1, 1, 1]}
    for layer in ["pre_parallel0", "pre_parallel1"]:
        for n, channels in layers[layer]["bones"].items():
            for ch in channels:
                death["bones"].setdefault(n, {}).setdefault(ch, {"0": rest[ch]})
    assert all(n in death["bones"] for n in layers["pre_parallel2"]["bones"]), "死亡动画须接管整条尾巴"
    out["death"] = death
    for name in LAYERS:
        out[name] = only(layers[name], bones)
        assert out[name]["bones"].keys() == layers[name]["bones"].keys(), name
    return {"format_version": "1.8.0", "animations": out}


def spawn_egg():
    """16×16 刷怪蛋：米白底、金色毛斑、朱红两点；描边一级深金，左上一点高光。"""
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
    colors = {"#": (166, 108, 43, 255), "a": (255, 248, 230, 255), "b": (234, 175, 77, 255),
              "c": (246, 205, 125, 255), "s": (201, 68, 53, 255)}
    img = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colors:
                img.putpixel((x, y), colors[ch])
    img.putpixel((6, 2), (255, 255, 255, 255))
    path = ASSETS / "textures/item/divine_fox_spawn_egg.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    write(ASSETS / "models/item/divine_fox_spawn_egg.json",
          {"parent": "minecraft:item/generated", "textures": {"layer0": "logos_dimension:item/divine_fox_spawn_egg"}})
    write(ASSETS / "items/divine_fox_spawn_egg.json",
          {"model": {"type": "minecraft:model", "model": "logos_dimension:item/divine_fox_spawn_egg"}})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", default=str(DEFAULT_SOURCE))
    source = Path(parser.parse_args().source)
    geo = read(source / "build/exports/divine_fox.geo.json")
    parts = read(source / "build/parts.json")
    bones = bones_of(geo)
    write(GECKO / "models/entity/divine_fox.geo.json", geo, indent=None)
    shutil.copyfile(source / "build/exports/divine_fox.png", ASSETS / "textures/entity/divine_fox.png")
    write(ROOT / "src/main/resources/data/logos_dimension/npc_races/divine_fox.json", parts, indent=1)
    write(GECKO / "animations/entity/divine_fox.animation.json", animations(source, bones), indent=1)
    spawn_egg()
    print("bones", len(bones), "parts", sum(len(s["parts"]) for s in parts["slots"].values()))


if __name__ == "__main__":
    main()
