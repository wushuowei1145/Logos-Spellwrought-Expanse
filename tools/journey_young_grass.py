"""原野牧草的幼年体贴图与模型（代码绘制，不使用图像生成服务）。

幼年体是独立方块 ``journey_meadow_grass_young``：叶片更少更矮、颜色更嫩，
带 ``age`` 属性（0–2），光照足够时自己长大，长到 2 之后变成成年原野牧草；
撒骨粉会直接催熟一档。所以衰退符文按 age 逐级倒退，不会一刀切掉。

用法：python tools/journey_young_grass.py
"""
from pathlib import Path
import json
import random

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/logos_dimension"
D = ROOT / "src/main/resources/data/logos_dimension"
TAG = ROOT / "src/main/resources/data/logos/tags/block/fragile_plants.json"

NAME = "journey_meadow_grass_young"
MAT_NAME = "journey_meadow_grass_young_mat"


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def draw_young():
    """幼年牧草：三五根短叶，颜色比成年体嫩，根部一点土色。"""
    im = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    q = ImageDraw.Draw(im)
    rng = random.Random("young_meadow_grass")
    greens = ["#7d9a58", "#8fae66", "#6d8a4c"]
    # 先铺一小片根部，让草看起来是成丛的。
    for n in range(90):
        x = rng.randrange(6, 27)
        y = rng.randrange(26, 32)
        q.point((x, y), fill=rng.choice(["#5f7a42", "#6f8a4d", "#7d9a58"]))
    # 五片短叶：中间最高，两侧渐矮，叶尖向外偏。
    blades = [(11, 20, -2), (15, 16, -1), (18, 14, 1), (22, 19, 2), (13, 23, -3)]
    for x, top, bend in blades:
        q.line((x, 31, x, top), fill=greens[0], width=2)
        q.line((x, 31, x + bend, top + 1), fill=greens[1], width=1)
        q.point((x + bend, top), fill=greens[2])
    return im


def draw_mat():
    """脚下的小片草根，铺在方块底面，避免幼草看起来悬空。"""
    im = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    q = ImageDraw.Draw(im)
    rng = random.Random("young_meadow_mat")
    for n in range(230):
        x = rng.randrange(32)
        y = rng.randrange(32)
        q.line((x, y, x + 1, y - 2), fill=rng.choice(["#6d8a4c", "#7d9a58", "#8fae66"]), width=1)
    return im


def main():
    tex = draw_young()
    png = A / "textures/block" / f"{NAME}.png"
    png.parent.mkdir(parents=True, exist_ok=True)
    tex.save(png)
    mat = A / "textures/block" / f"{MAT_NAME}.png"
    draw_mat().save(mat)

    # 模型：三片短叶交叉 + 底面草根，高度只有成年牧草的一半左右。
    elements = [
        {"from": [6, 0, 8], "to": [26, 7, 8],
         "faces": {"north": {"texture": "#plant", "uv": [0, 0, 16, 16]},
                   "south": {"texture": "#plant", "uv": [0, 0, 16, 16]}}},
        {"from": [8, 0, 6], "to": [8, 9, 26],
         "faces": {"east": {"texture": "#plant", "uv": [0, 0, 16, 16]},
                   "west": {"texture": "#plant", "uv": [0, 0, 16, 16]}}},
        {"from": [4, 0, 4], "to": [28, 6, 28], "rotation": {"axis": "y", "angle": 45, "origin": [16, 0, 16]},
         "faces": {"north": {"texture": "#plant", "uv": [0, 0, 16, 16]},
                   "south": {"texture": "#plant", "uv": [0, 0, 16, 16]}}},
        {"from": [0, 0.15, 0], "to": [16, 0.15, 16],
         "faces": {"up": {"texture": "#mat", "uv": [0, 0, 16, 16]},
                   "down": {"texture": "#mat", "uv": [0, 0, 16, 16]}}},
    ]
    write(A / "models/block" / f"{NAME}.json",
          {"parent": "minecraft:block/block", "ambientocclusion": False,
           "render_type": "neoforge:cutout",
           "textures": {"particle": f"logos_dimension:block/{NAME}",
                        "plant": f"logos_dimension:block/{NAME}",
                        "mat": f"logos_dimension:block/{MAT_NAME}"},
           "elements": elements})
    # 三个生长阶段共用同一模型；年龄只影响行为，观感靠成年体区分。
    write(A / "blockstates" / f"{NAME}.json",
          {"variants": {f"age={age}": {"model": f"logos_dimension:block/{NAME}"} for age in range(3)}})
    write(A / "models/item" / f"{NAME}.json",
          {"parent": f"logos_dimension:block/{NAME}"})
    write(A / "items" / f"{NAME}.json",
          {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{NAME}"}})
    write(D / "loot_table/blocks" / f"{NAME}.json",
          {"type": "minecraft:block", "pools": [{"rolls": 1,
            "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [{"type": "minecraft:item", "name": f"logos_dimension:{NAME}"}]}]})

    zh = json.loads((A / "lang/zh_cn.json").read_text(encoding="utf-8"))
    en = json.loads((A / "lang/en_us.json").read_text(encoding="utf-8"))
    zh[f"block.logos_dimension.{NAME}"] = "幼年原野牧草"
    en[f"block.logos_dimension.{NAME}"] = "Young Meadow Grass"
    (A / "lang/zh_cn.json").write_text(json.dumps(zh, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (A / "lang/en_us.json").write_text(json.dumps(en, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    tag = json.loads(TAG.read_text(encoding="utf-8"))
    if f"logos_dimension:{NAME}" not in tag["values"]:
        tag["values"].append(f"logos_dimension:{NAME}")
    TAG.write_text(json.dumps(tag, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("幼年原野牧草：贴图 / 草根 / 模型 / 三个年龄的方块状态 / 物品模型 / 掉落表 / 语言 / fragile_plants")


if __name__ == "__main__":
    main()
