"""白樱落瓣的模型：只保留贴地的花瓣，没有花茎。

原版 flowerbed 模型底下带一段花茎贴图，我们这边缺那张图，游戏里会渲染成黑色块；
这里干脆自己做四个档位的模型——只有水平铺在地上的小花瓣，不引入任何茎元素。
"""
from pathlib import Path
import json, random
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/logos_dimension"
NAME = "journey_white_petals"


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def texture():
    """白粉色的小花瓣：纯代码画，和叶片同色系。"""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    q = ImageDraw.Draw(im)
    r = random.Random(NAME)
    for _ in range(9):
        cx, cy = r.randrange(1, 15), r.randrange(1, 15)
        w, h = r.randrange(1, 3), r.randrange(1, 2)
        q.ellipse((cx - w, cy - h, cx + w, cy + h), fill=(246, 226, 235, 255), outline=(232, 196, 213, 255))
        q.point((cx, cy), fill=(255, 246, 250, 255))
    im.save(A / "textures/block" / f"{NAME}.png")


def quad(x0, z0, size, y):
    return {
        "from": [round(x0, 2), y, round(z0, 2)],
        "to": [round(x0 + size, 2), round(y + 0.05, 3), round(z0 + size, 2)],
        "faces": {f: {"texture": "#flowerbed", "uv": [0, 0, 16, 16]} for f in ("up", "down")},
    }


def model(amount):
    r = random.Random(f"{NAME}{amount}")
    elements = []
    for _ in range(amount * 4):
        cx, cz = r.uniform(1, 11), r.uniform(1, 11)
        elements.append(quad(cx, cz, r.uniform(2.5, 4.0), 0.0))
    write(A / "models/block" / f"{NAME}_{amount}.json", {
        "parent": "minecraft:block/block",
        "render_type": "neoforge:cutout",
        "textures": {"flowerbed": f"logos_dimension:block/{NAME}", "particle": f"logos_dimension:block/{NAME}"},
        "elements": elements,
    })


def main():
    texture()
    for n in range(1, 5):
        model(n)

    # 方块状态：沿用原版花瓣的朝向与档位，只是模型换成自己的
    multipart = []
    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        entry = {"apply": {"model": f"logos_dimension:block/{NAME}_1"}}
        if y:
            entry["apply"]["y"] = y
        entry["when"] = {"facing": facing}
        multipart.append(entry)
    for n in range(2, 5):
        for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            entry = {"apply": {"model": f"logos_dimension:block/{NAME}_{n}"}}
            if y:
                entry["apply"]["y"] = y
            entry["when"] = {"facing": facing, "flower_amount": "|".join(str(k) for k in range(n, 5))}
            multipart.append(entry)
    write(A / "blockstates" / f"{NAME}.json", {"multipart": multipart})

    write(A / "models/item" / f"{NAME}.json", {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"logos_dimension:block/{NAME}"},
    })
    write(A / "items" / f"{NAME}.json", {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{NAME}"}})
    print("白樱落瓣模型重做完成：贴地花瓣，无花茎")


if __name__ == "__main__":
    main()
