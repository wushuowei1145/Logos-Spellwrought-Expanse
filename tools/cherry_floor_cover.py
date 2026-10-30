"""灵狐樱野的落瓣与落叶层：贴地、无碰撞、四档厚度。

方块 `journey_white_petals` / `journey_fallen_leaves`，各自 amount=1..4 的模型由
若干水平小面片组成，随机摆位做出"散落"的样子。纹理全部代码绘制。
"""
from pathlib import Path
import json, random
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/logos_dimension"
D = ROOT / "src/main/resources/data/logos_dimension"
LOGOS_TAGS = ROOT / "src/main/resources/data/logos/tags/block/fragile_plants.json"

COVERS = {
    # id: (中文名, 英文名, 主色, 高光色, 边缘色)
    "journey_white_petals": ("落樱瓣", "Fallen Cherry Petals", (246, 213, 224), (255, 243, 248), (226, 186, 203)),
    "journey_fallen_leaves": ("落樱叶", "Fallen Cherry Leaves", (238, 214, 226), (250, 240, 246), (214, 188, 203)),
}


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def texture(name, main, hi, edge):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    q = ImageDraw.Draw(im)
    r = random.Random(name)
    for _ in range(6):
        cx, cy = r.randrange(2, 14), r.randrange(2, 14)
        w, h = r.randrange(2, 5), r.randrange(1, 3)
        q.ellipse((cx - w, cy - h, cx + w, cy + h), fill=main + (255,), outline=edge + (255,))
        q.point((cx, cy - h), fill=hi + (255,))
    return im


def plane(x0, z0, x1, z1, y, angle):
    return {
        "from": [round(x0, 2), y, round(z0, 2)],
        "to": [round(x1, 2), round(y + 0.2, 2), round(z1, 2)],
        "faces": {f: {"texture": "#plant", "uv": [0, 0, 16, 16]} for f in ("up", "down")},
        "rotation": {"origin": [8, 0, 8], "axis": "y", "angle": angle},
    }


def model(name, amount):
    r = random.Random(f"{name}{amount}")
    elements = []
    for _ in range(amount * 3):
        cx = r.uniform(3, 13)
        cz = r.uniform(3, 13)
        w = r.uniform(1.2, 2.6)
        d = r.uniform(1.2, 2.6)
        elements.append(plane(cx - w, cz - d, cx + w, cz + d, 0.05 + r.random() * 0.1,
                              r.choice([0, 22.5, 45, 67.5])))
    write(A / "models/block" / f"{name}_{amount}.json", {
        "parent": "minecraft:block/block",
        "render_type": "neoforge:cutout",
        "textures": {"plant": f"logos_dimension:block/{name}", "particle": f"logos_dimension:block/{name}"},
        "elements": elements,
    })


def main():
    zh = json.loads((A / "lang/zh_cn.json").read_text(encoding="utf-8"))
    en = json.loads((A / "lang/en_us.json").read_text(encoding="utf-8"))
    tag = json.loads(LOGOS_TAGS.read_text(encoding="utf-8"))
    for name, (cn, eng, main, hi, edge) in COVERS.items():
        texture(name, main, hi, edge).save(A / f"textures/block/{name}.png")
        write(A / "blockstates" / f"{name}.json", {"variants": {
            f"amount={n}": {"model": f"logos_dimension:block/{name}_{n}"} for n in range(1, 5)}})
        for n in range(1, 5):
            model(name, n)
        write(A / "models/item" / f"{name}.json", {
            "parent": "minecraft:item/generated", "textures": {"layer0": f"logos_dimension:block/{name}"}})
        write(A / "items" / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{name}"}})
        zh[f"block.logos_dimension.{name}"] = cn
        en[f"block.logos_dimension.{name}"] = eng
        # 方块物品显示的是 item. 键，两份都要写
        zh[f"item.logos_dimension.{name}"] = cn
        en[f"item.logos_dimension.{name}"] = eng
        write(D / "loot_table/blocks" / f"{name}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": "logos_dimension:" + name}],
            "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
        if f"logos_dimension:{name}" not in tag["values"]:
            tag["values"].append(f"logos_dimension:{name}")
    write(A / "lang/zh_cn.json", zh)
    write(A / "lang/en_us.json", en)
    write(LOGOS_TAGS, tag)
    print("落瓣/落叶方块资源写好：", ", ".join(COVERS))


if __name__ == "__main__":
    main()
