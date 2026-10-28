"""导入用户指定的精灵长矛图标，沿用原版长矛模型模板，并代码绘制萌芽印记叶片。"""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension"


def write(path, content):
    """输出资源 JSON。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--icon", required=True, type=Path)
    args = parser.parse_args()
    texture = ASSETS / "textures/item/elf_spear.png"
    texture.parent.mkdir(parents=True, exist_ok=True)
    texture.write_bytes(args.icon.read_bytes())
    # 原版长矛图标指向右上，手持专用贴图指向左上；分开保存以匹配原版握持与冲刺姿态。
    ImageOps.mirror(Image.open(args.icon).convert("RGBA")).save(ASSETS / "textures/item/elf_spear_in_hand.png")
    for suffix, parent in [("", "minecraft:item/generated"), ("_in_hand", "minecraft:item/spear_in_hand")]:
        write(ASSETS / f"models/item/elf_spear{suffix}.json", {
            "parent": parent, "textures": {"layer0": f"logos_dimension:item/elf_spear{suffix}"}})
    write(ASSETS / "items/elf_spear.json", {"model": {
        "type": "minecraft:select", "property": "minecraft:display_context",
        "cases": [{"when": ["gui", "ground", "fixed", "on_shelf"],
                   "model": {"type": "minecraft:model", "model": "logos_dimension:item/elf_spear"}}],
        "fallback": {"type": "minecraft:model", "model": "logos_dimension:item/elf_spear_in_hand"}},
        "swap_animation_scale": 1.95})
    write(ROOT / "src/main/resources/data/minecraft/tags/item/spears.json", {
        "replace": False, "values": ["logos_dimension:elf_spear"]})
    # 耐久附魔标签不是通过 spears 间接包含的版本也显式追加，不覆盖已有自然之力。
    tag = ROOT / "src/main/resources/data/minecraft/tags/item/enchantable/durability.json"
    contents = json.loads(tag.read_text(encoding="utf-8"))
    if "logos_dimension:elf_spear" not in contents["values"]:
        contents["values"].append("logos_dimension:elf_spear")
    write(tag, contents)
    for lang, title, warning in [
        ("zh_cn", "精灵长矛", "当前 Logos 没有生命易伤效果，精灵长矛仅造成物理伤害"),
        ("en_us", "Elven Spear", "This Logos version lacks Life Vulnerability; the spear deals physical damage only")]:
        path = ASSETS / f"lang/{lang}.json"
        contents = json.loads(path.read_text(encoding="utf-8"))
        contents["item.logos_dimension.elf_spear"] = title
        contents["message.logos_dimension.elf_spear.old_logos"] = warning
        write(path, contents)
    image = Image.new("RGBA", (16, 16))
    draw = ImageDraw.Draw(image)
    draw.line([(8, 14), (8, 7)], fill="#708c38", width=2)
    draw.polygon([(8, 9), (3, 8), (1, 3), (6, 4), (9, 8)], fill="#7eae43")
    draw.polygon([(8, 8), (10, 2), (15, 1), (14, 6), (9, 10)], fill="#b9d878")
    draw.line([(3, 4), (8, 9), (13, 3)], fill="#e3edb1", width=1)
    path = ASSETS / "textures/particle/sprout_mark.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)
    write(ASSETS / "particles/sprout_mark.json", {"textures": ["logos_dimension:sprout_mark"]})


if __name__ == "__main__":
    main()
