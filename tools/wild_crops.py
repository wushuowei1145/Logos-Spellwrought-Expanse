"""让 Logos 的罐栽植物能种到云海草皮上（地面种植的配套数据）。

Logos 从 #328 起给每种罐栽植物一个独立基底标签
`logos:plantable_on/<植物方块路径>`，默认空；附属可以往里面加地面基底。
这里做三件事：

1. 新建 `logos_dimension:turf`，列出所有会当作"草皮"的地面方块；
2. 把 13 种植物的 `logos:plantable_on/<crop>` 指向这个标签；
3. 给精灵森林加两种稀疏野生作物（曼德拉草、星辉苔）。

种下/收获/掉落由 Java 侧 `WildCrops` 负责（Logos 的植物本身没有物品和掉落）。
"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "src/main/resources/data"
DIM = DATA / "logos_dimension"
LOGOS = DATA / "logos"

CROPS = {
    "mandrake_crop": ("logos:mandrake_seed", [("logos:mandrake_root", 2)]),
    "starlight_moss_crop": ("logos:starlight_moss_spore", [("logos:starlight_moss", 2)]),
    "mercury_mushroom_crop": ("logos:mercury_spore", [("logos:mercury_mushroom", 2)]),
    "niter_sprout_crop": ("logos:niter_sprout", [("logos:saltpeter", 2), ("logos:niter_sprout", 2)]),
    "copper_pod_crop": ("logos:copper_seed", [("logos:copper_pod", 1)]),
    "iron_pod_crop": ("logos:iron_seed", [("logos:iron_pod", 1)]),
    "gold_pod_crop": ("logos:gold_seed", [("logos:gold_pod", 1)]),
    "netherite_pod_crop": ("logos:netherite_seed", [("logos:netherite_pod", 1)]),
    "candleflame_crop": ("logos:candleflame", [("logos:candleflame_stamen", 2)]),
    "frostbell_crop": ("logos:frostbell", [("logos:frostbell_petal", 2)]),
    "thunder_thistle_crop": ("logos:thunder_thistle", [("logos:thunder_thistle_down", 2)]),
    "riftshade_crop": ("logos:riftshade", [("logos:riftshade_pollen", 2)]),
    "redvein_root_crop": ("logos:redvein_root", [("logos:redvein_root", 2)]),
}


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def turf_blocks():
    """所有 *_ground 与以太地表：作为草皮基底。"""
    dirt = json.loads((DATA / "minecraft/tags/block/dirt.json").read_text(encoding="utf-8"))
    return [v for v in dirt["values"] if v.startswith("logos_dimension:")]


def main():
    turf = turf_blocks()
    write(DIM / "tags/block/turf.json", {"replace": False, "values": turf})

    for crop in CROPS:
        write(LOGOS / "tags/block/plantable_on" / f"{crop}.json",
              {"replace": False, "values": ["#logos_dimension:turf"]})

    # 精灵森林里的两种野生作物：低概率、成小片。
    for crop, name in (("mandrake_crop", "wild_mandrake"), ("starlight_moss_crop", "wild_starlight_moss")):
        state = {"Name": f"logos:{crop}", "Properties": {"age": "0"}}
        write(DIM / "worldgen/configured_feature" / f"{name}.json", {
            "type": "minecraft:random_patch",
            "config": {
                "tries": 2, "xz_spread": 3, "y_spread": 1,
                "feature": {
                    "feature": {"type": "minecraft:simple_block", "config": {"to_place": state}},
                    "placement": [
                        {"type": "minecraft:block_predicate_filter",
                         "predicate": {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"}},
                        {"type": "minecraft:block_predicate_filter",
                         "predicate": {"type": "minecraft:would_survive", "state": state}},
                    ],
                },
            },
        })
        write(DIM / "worldgen/placed_feature" / f"{name}.json", {
            "feature": f"logos_dimension:{name}",
            "placement": [
                {"type": "minecraft:count", "count": 2},
                {"type": "minecraft:in_square"},
                {"type": "minecraft:heightmap", "heightmap": "WORLD_SURFACE_WG"},
                {"type": "minecraft:biome"},
            ],
        })

    # 精灵森林第 9 阶段追加这两个特征（重复执行安全）。
    path = DIM / "worldgen/biome/elven_forest.json"
    biome = json.loads(path.read_text(encoding="utf-8"))
    step9 = biome["features"][9]
    for name in ("wild_mandrake", "wild_starlight_moss"):
        placed = f"logos_dimension:{name}"
        if placed not in step9:
            step9.append(placed)
    write(path, biome)
    print(f"草皮基底 {len(turf)} 种；13 种作物可种；精灵森林加 2 种野生作物")


if __name__ == "__main__":
    main()
