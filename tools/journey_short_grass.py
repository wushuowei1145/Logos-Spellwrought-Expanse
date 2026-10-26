"""长风草原的矮草：只留一层很薄的草垫（1/16 格）。

实机试玩反馈：方块模型里每格一簇的竖直叶片看起来像"一排韭菜/箭头"。所以基础草
只负责地面质感，会摆动的草叶全部由渲染层的叠加几何提供（见 client/WindGrass）。

复用既有贴图 `journey_meadow_mat`（草垫），另画一张 `journey_meadow_grass_short`
用于物品图标/粒子。
"""
from pathlib import Path
import json
import random

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/logos_dimension"
D = ROOT / "src/main/resources/data/logos_dimension"
TAG = ROOT / "src/main/resources/data/logos/tags/block/fragile_plants.json"
NAME = "journey_meadow_grass_short"
MAT = "logos_dimension:block/journey_meadow_grass_short_mat"


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def draw_short_grass():
    """物品图标/粒子用的一丛短草（32×32）。"""
    im = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    q = ImageDraw.Draw(im)
    rng = random.Random("short_meadow_grass")
    root, mid, tip = (96, 128, 76), (128, 162, 100), (168, 198, 128)
    for x in range(2, 30):
        if rng.random() < 0.75:
            q.point((x, 30), fill=root + (255,))
            q.point((x, 29), fill=mid + (255,))
    for i in range(9):
        x = 3 + i * 3 + rng.randint(-1, 1)
        top = rng.randint(10, 20)
        bend = rng.choice([-2, -1, 0, 1, 2])
        for y in range(30, top - 1, -1):
            t = (30 - y) / max(1, 30 - top)
            px = x + int(round(bend * t))
            color = tip if t > 0.7 else mid if t > 0.3 else root
            if 0 <= px < 32:
                q.point((px, y), fill=color + (255,))
            if t < 0.85 and 0 <= px - 1 < 32:
                q.point((px - 1, y), fill=tuple(max(0, c - 14) for c in color) + (255,))
    return im


def draw_short_grass_mat():
    """草垫贴图：低对比的绿色噪点 + 稀疏深色斑，四边用环绕采样保证可平铺。"""
    import random
    im = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    q = ImageDraw.Draw(im)
    rng = random.Random("short_meadow_mat")
    base = (126, 156, 96)
    for y in range(32):
        for x in range(32):
            d = rng.randint(-7, 7)
            q.point((x, y), fill=(base[0] + d, base[1] + d, base[2] + d, 255))
    # 稀疏的深/浅斑点，幅度压小，远看是细腻的绿而不是花布
    for _ in range(140):
        x, y = rng.randrange(32), rng.randrange(32)
        shade = rng.choice([-14, -10, 8, 12])
        q.point((x, y), fill=(base[0] + shade, base[1] + shade, base[2] + shade, 255))
    # 几根极浅的草丝，增加方向感
    for _ in range(26):
        x, y = rng.randrange(32), rng.randrange(32)
        if y > 2:
            q.line((x, y, x, y - 2), fill=(base[0] + 16, base[1] + 18, base[2] + 12, 255))
    return im


def main():
    mat_tex = draw_short_grass_mat()
    mat_path = A / "textures/block" / f"{NAME}_mat.png"
    mat_path.parent.mkdir(parents=True, exist_ok=True)
    mat_tex.save(mat_path)
    icon = draw_short_grass()
    icon_path = A / "textures/block" / f"{NAME}.png"
    icon_path.parent.mkdir(parents=True, exist_ok=True)
    icon.save(icon_path)

    def face():
        return {"texture": "#mat", "uv": [0, 0, 16, 16]}

    # 只保留上/下两个面：1/16 厚草垫的侧面会在方块边界形成一圈深色竖线，
    # 去掉侧面之后相邻草垫在视觉上是连续的一层草皮。
    elements = [{
        "from": [0, 0, 0], "to": [16, 1, 16],
        "faces": {"up": face(), "down": face()},
    }]
    write(A / "models/block" / f"{NAME}.json",
          {"parent": "minecraft:block/block", "ambientocclusion": False,
           "render_type": "neoforge:cutout",
           "textures": {"particle": f"logos_dimension:block/{NAME}",
                        "plant": f"logos_dimension:block/{NAME}",
                        "mat": MAT},
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
    print("矮草：1/16 草垫模型 / 图标 / 方块状态 / 物品 / 掉落 / 语言 / fragile_plants")


if __name__ == "__main__":
    main()
