"""白樱落瓣的模型（v2）：贴地花瓣，跨块拼接，按 facing × 档位出 16 种随机布局。

原版 flowerbed 模型底下有花茎元素，我们缺那张贴图，会渲染成黑块；
这里自绘模型，只有 y=0 的平面花瓣。每个 (朝向, 档位) 组合一套随机布局，
世界生成时随机给朝向，相邻方块的花瓣就不一样了。
"""
from pathlib import Path
import json, random
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/logos_dimension"
NAME = "journey_white_petals"
FACINGS = ("north", "east", "south", "west")


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def texture():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    q = ImageDraw.Draw(im)
    r = random.Random(NAME)
    for _ in range(9):
        cx, cy = r.randrange(1, 15), r.randrange(1, 15)
        w, h = r.randrange(1, 3), r.randrange(1, 2)
        q.ellipse((cx - w, cy - h, cx + w, cy + h), fill=(246, 226, 235, 255), outline=(232, 196, 213, 255))
        q.point((cx, cy), fill=(255, 246, 250, 255))
    im.save(A / "textures/block" / f"{NAME}.png")


def quad(x0, z0, size, y=0.0):
    return {
        "from": [round(x0, 2), y, round(z0, 2)],
        "to": [round(x0 + size, 2), round(y + 0.05, 3), round(z0 + size, 2)],
        "faces": {f: {"texture": "#flowerbed", "uv": [0, 0, 16, 16]} for f in ("up", "down")},
    }


def layout(amount, facing_index):
    """先按方块坐标系铺一遍，再按朝向整体旋转，四种朝向就不重样了。"""
    r = random.Random(f"{NAME}-{amount}-{facing_index}")
    quads = []
    for _ in range(amount * 4):
        cx, cz = r.uniform(0.5, 12.5), r.uniform(0.5, 12.5)
        quads.append((cx, cz, r.uniform(6.0, 9.0)))
    for _ in range(4):
        size = r.uniform(5.0, 8.0)
        along = r.uniform(-2.0, 12.0)
        edge = r.randrange(4)
        if edge == 0:
            quads.append((-2.5, along, size))
        elif edge == 1:
            quads.append((16 - size + 2.5, along, size))
        elif edge == 2:
            quads.append((along, -2.5, size))
        else:
            quads.append((along, 16 - size + 2.5, size))

    out = []
    for x, z, size in quads:
        # 绕方块中心旋转 90° × facing_index 次，坐标换算到 [0,16]
        for _ in range(facing_index):
            x, z = 16 - z - size, x
        out.append(quad(x, z, size))
    return out


def model(amount, facing_index):
    write(A / "models/block" / f"{NAME}_{amount}_{FACINGS[facing_index]}.json", {
        "parent": "minecraft:block/block",
        "render_type": "neoforge:cutout",
        "textures": {"flowerbed": f"logos_dimension:block/{NAME}", "particle": f"logos_dimension:block/{NAME}"},
        "elements": layout(amount, facing_index),
    })


def main():
    texture()
    for amount in range(1, 5):
        for i in range(4):
            model(amount, i)

    multipart = []
    for i, facing in enumerate(FACINGS):
        entry = {"apply": {"model": f"logos_dimension:block/{NAME}_1_{facing}"}, "when": {"facing": facing}}
        multipart.append(entry)
        for amount in range(2, 5):
            multipart.append({
                "apply": {"model": f"logos_dimension:block/{NAME}_{amount}_{facing}"},
                "when": {"facing": facing, "flower_amount": "|".join(str(k) for k in range(amount, 5))},
            })
    write(A / "blockstates" / f"{NAME}.json", {"multipart": multipart})
    write(A / "models/item" / f"{NAME}.json", {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"logos_dimension:block/{NAME}"},
    })
    write(A / "items" / f"{NAME}.json", {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{NAME}"}})

    # 旧的四个模型不再使用，删掉免得混淆
    for old in (A / "models/block").glob(f"{NAME}_[1-4].json"):
        old.unlink()
    print("16 种落瓣模型完成（4 档 × 4 朝向）")


if __name__ == "__main__":
    main()