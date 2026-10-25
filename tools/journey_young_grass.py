"""幼年原野牧草的贴图与模型（代码绘制，不使用图像生成服务）。

形状直接照原版草（``minecraft:short_grass``）：16×16 的短草剪影，几片细叶从底部升起、
高矮不一，用原版 ``block/cross`` 交叉面片，不做底面草根片、不做自定义几何。
颜色仍是本维度的绿，不套原版草的颜色表（云海没有那张图）。

幼年体是独立方块 ``journey_meadow_grass_young``，带 ``age``（0–2）：
光照足够自己长，长满后换成成年原野牧草；撒骨粉催熟一档。

用法：python tools/journey_young_grass.py
"""
from pathlib import Path
import json
import random

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/logos_dimension"
D = ROOT / "src/main/resources/data/logos_dimension"
TAG = ROOT / "src/main/resources/data/logos/tags/block/fragile_plants.json"

NAME = "journey_meadow_grass_young"
PALETTE = ["#5c7a3f", "#6f8f4b", "#82a257", "#93b062"]


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def draw_grass():
    """照原版 short_grass 的形状画：底部一撮，5 片细叶，左右错开、高矮不一。"""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    rng = random.Random("young_meadow_grass")

    def put(x, y, color):
        if 0 <= x < 16 and 0 <= y < 16:
            px[x, y] = tuple(bytes.fromhex(color.lstrip("#"))) + (255,)

    # 叶根：底部两行铺一层杂乱草根，和原版草一样不悬空。
    for y in (14, 15):
        for x in range(16):
            if rng.random() < 0.78:
                put(x, y, rng.choice(PALETTE[1:]))

    # 叶片：从底部向上收窄，顶端偏移，模拟原版草那种自然弯曲。
    blades = [(5, 6, -1), (7, 3, 0), (9, 4, 1), (11, 7, 2), (13, 9, 1)]
    for x, top, bend in blades:
        for y in range(15, top - 1, -1):
            t = (15 - y) / max(1, 15 - top)
            dx = int(round(bend * t))
            put(x + dx, y, PALETTE[0] if (y + x) % 3 else PALETTE[2])
            if t < 0.6:
                put(x + dx - 1, y, PALETTE[1])
        # 叶尖提亮一像素。
        put(x + bend, top, PALETTE[3])
    return im


def main():
    tex = draw_grass()
    png = A / "textures/block" / f"{NAME}.png"
    png.parent.mkdir(parents=True, exist_ok=True)
    tex.save(png)

    # 模型直接抄原版 short_grass：block/cross 两片交叉面片。
    write(A / "models/block" / f"{NAME}.json",
          {"parent": "minecraft:block/cross", "render_type": "neoforge:cutout",
           "textures": {"cross": f"logos_dimension:block/{NAME}"}})
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
    # 旧的草根底片不再使用，删掉免得留孤儿贴图。
    orphan = A / "textures/block" / "journey_meadow_grass_young_mat.png"
    if orphan.exists():
        orphan.unlink()
    print("幼年原野牧草：原版草形状的 16×16 贴图 / block/cross 模型 / 方块状态 / 物品模型 / 掉落表 / 语言 / fragile_plants")


if __name__ == "__main__":
    main()
