"""白樱落瓣的模型与贴图生成器：
1. 四象限 16x16 贴图，涵盖真实缺口樱花瓣、交叠花瓣、散落小瓣、微卷花瓣。
2. 核心模型 core_1..4 (a/b 变体)，提供 1~4 层不同密度与厚度。
3. 边缘连接模型 edge (a/b 变体)，邻近有落樱瓣时无缝相连消除接缝。
4. 多向旋转与随机组合消除重复感。
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


def generate_texture():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    # 象限1 (0..7, 0..7): 经典缺口樱花瓣
    # 浅粉白底色 (255, 244, 248), 边缘淡粉 (246, 212, 226), 浅阴影 (228, 186, 204)
    body = (255, 244, 248, 255)
    rim = (246, 212, 226, 255)
    shadow = (228, 186, 204, 255)

    # 绘制单瓣
    d.polygon([(2, 1), (3, 2), (2, 4), (3, 6), (5, 5), (6, 3), (5, 1), (4, 2)], fill=body, outline=rim)
    d.point((4, 2), fill=shadow)
    d.point((3, 3), fill=body)
    d.point((4, 3), fill=(255, 250, 252, 255))

    # 象限2 (8..15, 0..7): 双瓣交叠
    d.polygon([(9, 2), (10, 1), (13, 1), (14, 3), (13, 5), (10, 4)], fill=body, outline=rim)
    d.polygon([(11, 3), (12, 2), (15, 3), (14, 6), (12, 5)], fill=(250, 235, 242, 255), outline=rim)
    d.point((12, 4), fill=shadow)

    # 象限3 (0..7, 8..15): 散落三小瓣
    d.polygon([(1, 9), (3, 8), (3, 10), (1, 10)], fill=body, outline=rim)
    d.polygon([(4, 11), (6, 10), (6, 12), (4, 12)], fill=body, outline=rim)
    d.polygon([(1, 13), (3, 12), (3, 14), (1, 14)], fill=body, outline=rim)

    # 象限4 (8..15, 8..15): 弧形微卷花瓣
    d.polygon([(9, 10), (11, 8), (14, 9), (13, 12), (10, 12)], fill=body, outline=rim)
    d.point((10, 11), fill=shadow)
    d.point((11, 10), fill=(255, 252, 254, 255))

    im.save(A / "textures/block" / f"{NAME}.png")


UV_QUADRANTS = [
    [0, 0, 8, 8],
    [8, 0, 16, 8],
    [0, 8, 8, 16],
    [8, 8, 16, 16],
]


def make_petal_quad(x, z, size_x, size_z, y_base, angle, uv_box):
    return {
        "from": [round(x, 2), round(y_base, 3), round(z, 2)],
        "to": [round(x + size_x, 2), round(y_base + 0.05, 3), round(z + size_z, 2)],
        "faces": {
            "up": {"uv": uv_box, "texture": "#flowerbed"},
            "down": {"uv": uv_box, "texture": "#flowerbed"},
        },
        "rotation": {
            "origin": [round(x + size_x / 2, 2), round(y_base, 3), round(z + size_z / 2, 2)],
            "axis": "y",
            "angle": angle,
        } if angle != 0 else None,
    }


def clean_element(elem):
    if elem.get("rotation") is None:
        elem.pop("rotation", None)
    return elem


def generate_core_models():
    for amount in range(1, 5):
        for variant in ("a", "b"):
            rng = random.Random(f"{NAME}_core_{amount}_{variant}")
            count = amount * 4
            elements = []
            for i in range(count):
                sx = rng.uniform(4.0, 5.2)
                sz = rng.uniform(4.0, 5.2)
                cx = rng.uniform(2.5, 13.5 - sx)
                cz = rng.uniform(2.5, 13.5 - sz)
                y = 0.02 + i * 0.015
                angle = rng.choice([-45, -22.5, 0, 22.5, 45])
                uv = rng.choice(UV_QUADRANTS)
                elements.append(clean_element(make_petal_quad(cx, cz, sx, sz, y, angle, uv)))

            write(A / "models/block" / f"{NAME}_core_{amount}_{variant}.json", {
                "parent": "minecraft:block/block",
                "render_type": "neoforge:cutout",
                "textures": {
                    "flowerbed": f"logos_dimension:block/{NAME}",
                    "particle": f"logos_dimension:block/{NAME}"
                },
                "elements": elements,
            })


def generate_edge_models():
    for variant in ("a", "b"):
        rng = random.Random(f"{NAME}_edge_{variant}")
        elements = []
        for i in range(4):
            sx = rng.uniform(4.2, 5.0)
            sz = rng.uniform(4.2, 5.0)
            cx = 1.0 + i * 3.2 + rng.uniform(-0.5, 0.5)
            cz = rng.uniform(-0.5, 1.5)
            y = 0.025 + (i % 3) * 0.015
            angle = rng.choice([-45, -22.5, 0, 22.5, 45])
            uv = rng.choice(UV_QUADRANTS)
            elements.append(clean_element(make_petal_quad(cx, cz, sx, sz, y, angle, uv)))

        write(A / "models/block" / f"{NAME}_edge_{variant}.json", {
            "parent": "minecraft:block/block",
            "render_type": "neoforge:cutout",
            "textures": {
                "flowerbed": f"logos_dimension:block/{NAME}",
                "particle": f"logos_dimension:block/{NAME}"
            },
            "elements": elements,
        })


def main():
    generate_texture()
    generate_core_models()
    generate_edge_models()
    print("落樱瓣资源生成完成！")


if __name__ == "__main__":
    main()
