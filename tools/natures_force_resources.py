"""从用户提供的代码绘制素材包导入自然之力，生成四段蓄力物品定义。"""
import argparse
import json
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension"


def write(path, content):
    """以统一格式输出资源 JSON。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def model(stage):
    """每个阶段使用同一握柄位置和原版弓显示变换。"""
    return {"type": "minecraft:model", "model": f"logos_dimension:item/natures_force_{stage}"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bow-pack", type=Path, required=True)
    parser.add_argument("--arrow", type=Path, required=True)
    args = parser.parse_args()
    with ZipFile(args.bow_pack) as pack:
        for stage in range(5):
            image = ASSETS / f"textures/item/natures_force_{stage}.png"
            image.parent.mkdir(parents=True, exist_ok=True)
            image.write_bytes(pack.read(f"natures_force_32/natures_force_{stage}.png"))
            write(ASSETS / f"models/item/natures_force_{stage}.json", {
                "parent": "minecraft:item/bow", "textures": {"layer0": f"logos_dimension:item/natures_force_{stage}"}
            })
    arrow = ASSETS / "textures/entity/projectiles/natures_force_arrow.png"
    arrow.parent.mkdir(parents=True, exist_ok=True)
    arrow.write_bytes(args.arrow.read_bytes())
    write(ASSETS / "items/natures_force.json", {"model": {
        "type": "minecraft:condition", "property": "minecraft:using_item",
        "on_false": model(0), "on_true": {
            "type": "minecraft:range_dispatch", "property": "logos_dimension:nature_charge", "scale": 1,
            "fallback": model(1), "entries": [
                {"threshold": 2, "model": model(2)},
                {"threshold": 3, "model": model(3)},
                {"threshold": 4, "model": model(4)}
            ]
        }
    }})
    for namespace, name in [("minecraft", "enchantable/bow"), ("minecraft", "enchantable/durability"), ("minecraft", "skeleton_preferred_weapons"),
                            ("neoforge", "skeleton_usable_bows")]:
        write(ROOT / f"src/main/resources/data/{namespace}/tags/item/{name}.json",
              {"replace": False, "values": ["logos_dimension:natures_force"]})
    for lang, title, message in [
        ("zh_cn", "自然之力", "魔力不足（需要 %s），改为第二阶普通射击"),
        ("en_us", "Nature's Force", "Not enough mana (requires %s); firing a stage II arrow")
    ]:
        path = ASSETS / f"lang/{lang}.json"
        text = json.loads(path.read_text(encoding="utf-8"))
        text["item.logos_dimension.natures_force"] = title
        text["message.logos_dimension.natures_force.no_mana"] = message
        write(path, text)


if __name__ == "__main__":
    main()
