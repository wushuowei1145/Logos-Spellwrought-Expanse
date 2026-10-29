"""灵狐樱野的随机地形装饰：巨石、碎石、山泉小池、白花片。

和村庄那种"随机撒在地上"的做法一样：每个特征各自随机出现，不保证位置，
落点由 `minecraft:biome` + `heightmap` 收在群系内地表。
全部数据驱动，运行时由原版 feature 生成，不写 Java 地形生成器。
"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parent.parent
D = ROOT / "src/main/resources/data/logos_dimension"
BIOME = D / "worldgen/biome/cloudsea_white_cherry.json"
STONE = "logos_dimension:journey_limestone"


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def state(name):
    return {"type": "minecraft:simple_state_provider", "state": {"Name": name}}


def ground_filters():
    return [
        {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING_NO_LEAVES"},
        {"type": "minecraft:block_predicate_filter",
         "predicate": {"type": "minecraft:matching_blocks", "blocks": ["minecraft:air"]}},
        {"type": "minecraft:biome"},
    ]


def configured(name, obj):
    write(D / "worldgen/configured_feature" / f"{name}.json", obj)


def placed(name, feature, placement):
    write(D / "worldgen/placed_feature" / f"{name}.json",
          {"feature": f"logos_dimension:{feature}", "placement": placement})


def main():
    # 1. 巨石：原版森林巨石，用我们的石灰岩，稀疏地冒出地面。
    configured("cherry_boulder", {"type": "minecraft:forest_rock", "config": {"state": {"Name": STONE}}})
    placed("cherry_boulder", "cherry_boulder",
           [{"type": "minecraft:rarity_filter", "chance": 10},
            {"type": "minecraft:in_square"},
            *ground_filters()])

    # 2. 碎石堆：更小更常见，像山路上的散石。
    configured("cherry_pebbles", {"type": "minecraft:block_pile", "config": {"state_provider": state(STONE)}})
    placed("cherry_pebbles", "cherry_pebbles",
           [{"type": "minecraft:rarity_filter", "chance": 4},
            {"type": "minecraft:in_square"},
            *ground_filters()])

    # 3. 山泉小池：原版湖，水 + 石灰岩边，落在山脚洼地处。
    configured("cherry_pool", {"type": "minecraft:lake", "config": {
        "fluid": {"type": "minecraft:simple_state_provider",
                  "state": {"Name": "minecraft:water", "Properties": {"level": "0"}}},
        "barrier": state(STONE),
    }})
    placed("cherry_pool", "cherry_pool",
           [{"type": "minecraft:rarity_filter", "chance": 45},
            {"type": "minecraft:in_square"},
            *ground_filters()])

    # 4. 白花片：雏菊成片，给樱花林的草地一点白。
    petal_state = {"Name": "minecraft:oxeye_daisy"}
    configured("cherry_white_flowers", {"type": "minecraft:random_patch", "config": {
        "tries": 24, "xz_spread": 5, "y_spread": 2,
        "feature": {
            "feature": {"type": "minecraft:simple_block", "config": {"to_place": petal_state}},
            "placement": [
                {"type": "minecraft:block_predicate_filter",
                 "predicate": {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"}},
                {"type": "minecraft:block_predicate_filter",
                 "predicate": {"type": "minecraft:would_survive", "state": petal_state}},
            ],
        },
    }})
    placed("cherry_white_flowers", "cherry_white_flowers",
           [{"type": "minecraft:count", "count": 2},
            {"type": "minecraft:in_square"},
            {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"},
            {"type": "minecraft:biome"}])

    # 追加到灵狐樱野的 step 9（重复执行安全），顺序固定为：巨石 → 碎石 → 泉 → 白花。
    data = json.loads(BIOME.read_text(encoding="utf-8"))
    step9 = data["features"][9]
    for name in ("cherry_boulder", "cherry_pebbles", "cherry_pool", "cherry_white_flowers"):
        placed_id = f"logos_dimension:{name}"
        if placed_id not in step9:
            step9.append(placed_id)
    write(BIOME, data)
    print("灵狐樱野随机地形装饰 4 件已写入")


if __name__ == "__main__":
    main()
