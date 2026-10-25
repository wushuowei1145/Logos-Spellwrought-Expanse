"""长风草原的矮草：复用原野牧草的贴图，模型只到 0.45 格高。

风摆动由渲染层的草尖叠加负责，所以基础草必须矮——否则草尖会被原版模型挡住，
看不到摆动（见 docs/WIND_GRASS.md）。
"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/logos_dimension"
D = ROOT / "src/main/resources/data/logos_dimension"
TAG = ROOT / "src/main/resources/data/logos/tags/block/fragile_plants.json"
NAME = "journey_meadow_grass_short"


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    # 模型：三片矮叶交叉，最高 7/16 格；贴图沿用原野牧草。
    elements = [
        {"from": [3, 0, 8], "to": [13, 7, 8],
         "faces": {"north": {"texture": "#plant", "uv": [0, 0, 16, 16]},
                   "south": {"texture": "#plant", "uv": [0, 0, 16, 16]}}},
        {"from": [8, 0, 3], "to": [8, 5, 13],
         "faces": {"east": {"texture": "#plant", "uv": [0, 0, 16, 16]},
                   "west": {"texture": "#plant", "uv": [0, 0, 16, 16]}}},
        {"from": [4, 0, 4], "to": [12, 6, 12], "rotation": {"axis": "y", "angle": 45, "origin": [8, 0, 8]},
         "faces": {"north": {"texture": "#plant", "uv": [0, 0, 16, 16]},
                   "south": {"texture": "#plant", "uv": [0, 0, 16, 16]}}},
    ]
    write(A / "models/block" / f"{NAME}.json",
          {"parent": "minecraft:block/block", "ambientocclusion": False,
           "render_type": "neoforge:cutout",
           "textures": {"particle": "logos_dimension:block/journey_meadow_grass",
                        "plant": "logos_dimension:block/journey_meadow_grass"},
           "elements": elements})
    write(A / "blockstates" / f"{NAME}.json", {"variants": {"": {"model": f"logos_dimension:block/{NAME}"}}})
    write(A / "models/item" / f"{NAME}.json", {"parent": f"logos_dimension:block/{NAME}"})
    write(A / "items" / f"{NAME}.json", {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{NAME}"}})
    write(D / "loot_table/blocks" / f"{NAME}.json",
          {"type": "minecraft:block", "pools": [{"rolls": 1,
            "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [{"type": "minecraft:item", "name": f"logos_dimension:{NAME}"}]}]})
    zh = json.loads((A / "lang/zh_cn.json").read_text(encoding="utf-8"))
    en = json.loads((A / "lang/en_us.json").read_text(encoding="utf-8"))
    zh[f"block.logos_dimension.{NAME}"] = "草原矮草"
    en[f"block.logos_dimension.{NAME}"] = "Short Meadow Grass"
    (A / "lang/zh_cn.json").write_text(json.dumps(zh, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (A / "lang/en_us.json").write_text(json.dumps(en, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tag = json.loads(TAG.read_text(encoding="utf-8"))
    if f"logos_dimension:{NAME}" not in tag["values"]:
        tag["values"].append(f"logos_dimension:{NAME}")
    TAG.write_text(json.dumps(tag, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("矮草资源：模型 / 方块状态 / 物品 / 掉落 / 语言 / fragile_plants")


if __name__ == "__main__":
    main()
