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
    # 注意：26.1 没有 `minecraft:forest_rock` 这个特征类型，原版森林巨石用的是 block_blob；
    # 类型名写错会让整个数据包加载失败，表现为"一直在生成世界"。
    configured("cherry_boulder", {"type": "minecraft:block_blob", "config": {
        "state": {"Name": STONE},
        "can_place_on": {"type": "minecraft:matching_block_tag", "tag": "logos_dimension:turf"},
    }})
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

    # 4. 白花片：26.1 没有 random_patch 这个特征类型，原版花片是 simple_block +
    #    placed_feature 里做多次 count / random_offset，这里照原版改。
    flower_entries = [
        {"data": {"Name": "minecraft:oxeye_daisy"}, "weight": 6},
        {"data": {"Name": "logos_dimension:journey_silver_anemone", "Properties": {"season": "0"}}, "weight": 3},
        {"data": {"Name": "minecraft:short_grass"}, "weight": 4},
    ]
    configured("cherry_white_flowers", {"type": "minecraft:simple_block", "config": {
        "to_place": {"type": "minecraft:weighted_state_provider", "entries": flower_entries}}})
    placed("cherry_white_flowers", "cherry_white_flowers", [
        {"type": "minecraft:count", "count": 24},
        {"type": "minecraft:random_offset", "xz_spread": 5, "y_spread": 2},
        {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"},
        {"type": "minecraft:block_predicate_filter",
         "predicate": {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"}},
        {"type": "minecraft:biome"},
    ])

    # 5. 神社遗迹：散落的旧石板，不铺成方形；只嵌进草皮表层，像被苔草吃掉一半的参道。
    paving = {"type": "minecraft:weighted_state_provider", "entries": [
        {"data": {"Name": "minecraft:stone_bricks"}, "weight": 6},
        {"data": {"Name": "minecraft:mossy_stone_bricks"}, "weight": 3},
        {"data": {"Name": "minecraft:cracked_stone_bricks"}, "weight": 2},
    ]}
    configured("cherry_paving", {"type": "minecraft:simple_block", "config": {"to_place": paving}})
    placed("cherry_paving", "cherry_paving", [
        {"type": "minecraft:count", "count": 5},
        {"type": "minecraft:in_square"},
        {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING_NO_LEAVES"},
        {"type": "minecraft:random_offset", "xz_spread": 0, "y_spread": -1},
        {"type": "minecraft:block_predicate_filter",
         "predicate": {"type": "minecraft:matching_block_tag", "tag": "logos_dimension:turf"}},
        {"type": "minecraft:biome"},
    ])


    # 6. 落瓣堆：成片的落樱，amount 1-4 随机，只在樱野地表。
    drift = {"type": "minecraft:weighted_state_provider", "entries": [
        {"data": {"Name": "logos_dimension:journey_white_petals", "Properties": {"amount": "1"}}, "weight": 5},
        {"data": {"Name": "logos_dimension:journey_white_petals", "Properties": {"amount": "2"}}, "weight": 4},
        {"data": {"Name": "logos_dimension:journey_white_petals", "Properties": {"amount": "3"}}, "weight": 2},
        {"data": {"Name": "logos_dimension:journey_fallen_leaves", "Properties": {"amount": "2"}}, "weight": 2},
    ]}
    configured("cherry_petal_drift", {"type": "minecraft:simple_block", "config": {"to_place": drift}})
    placed("cherry_petal_drift", "cherry_petal_drift", [
        {"type": "minecraft:count", "count": 6},
        {"type": "minecraft:random_offset", "xz_spread": 7, "y_spread": 1},
        {"type": "minecraft:heightmap", "heightmap": "MOTION_BLOCKING"},
        {"type": "minecraft:block_predicate_filter",
         "predicate": {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"}},
        {"type": "minecraft:biome"},
    ])

    # 追加到灵狐樱野的 step 9（重复执行安全），顺序固定为：巨石 → 碎石 → 泉 → 白花。
    data = json.loads(BIOME.read_text(encoding="utf-8"))
    step9 = data["features"][9]
    for name in ("cherry_boulder", "cherry_pebbles", "cherry_pool", "cherry_white_flowers", "cherry_paving", "cherry_petal_drift"):
        placed_id = f"logos_dimension:{name}"
        if placed_id not in step9:
            step9.append(placed_id)
    write(BIOME, data)
    print("灵狐樱野随机地形装饰 4 件已写入")


if __name__ == "__main__":
    main()
