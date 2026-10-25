"""画林地蘑菇的贴图与模型（代码绘制，不使用图像生成服务）。

三种无毒蘑菇：白伞菇、鸡油菌、牛肝菌。都是 32×32 的 billboard 贴图，
用原版 cross 模型，物品走 item/generated，掉自己。

后续有毒蘑菇在设计上复用同一套画法，只要在 SPECIES 里加条目并重跑本脚本。

用法：python tools/journey_mushrooms.py
"""
from pathlib import Path
import json

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/logos_dimension"
D = ROOT / "src/main/resources/data/logos_dimension"
TAG = ROOT / "src/main/resources/data/logos/tags/block/fragile_plants.json"

# (id, 中文名, 英文名, 主色, 次色, 菌柄色, 画法)
SPECIES = [
    ("journey_field_mushroom", "白伞菇", "Field Mushroom", (236, 231, 214), (206, 199, 178), (214, 203, 181), "cap"),
    ("journey_chanterelle", "鸡油菌", "Chanterelle", (232, 176, 66), (196, 138, 42), (226, 200, 142), "funnel"),
    ("journey_bolete", "牛肝菌", "Bolete", (146, 96, 54), (110, 70, 38), (226, 210, 184), "bolete"),
]


def shade(color, delta):
    return tuple(max(0, min(255, c + delta)) for c in color)


def draw_mushroom(style, cap, cap_dark, stem):
    im = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    q = ImageDraw.Draw(im)
    # 菌柄：不论哪种都从底部中间往上，底部略宽。
    q.polygon([(14, 30), (18, 30), (17, 19), (15, 19)], fill=stem + (255,))
    q.line((15, 30, 16, 20), fill=shade(stem, -24) + (255,))
    if style == "cap":
        # 圆顶伞：半椭圆帽 + 几片浅色鳞片。
        q.pieslice((5, 8, 27, 26), 180, 360, fill=cap + (255,))
        q.rectangle((5, 17, 27, 19), fill=cap + (255,))
        q.arc((5, 8, 27, 26), 180, 360, fill=shade(cap_dark, -18) + (255,))
        for x, y, r in [(11, 14, 2), (17, 11, 2), (22, 15, 2), (15, 16, 1)]:
            q.ellipse((x - r, y - r, x + r, y + r), fill=shade(cap, 18) + (255,))
        q.line((6, 18, 26, 18), fill=cap_dark + (255,))
    elif style == "funnel":
        # 漏斗形：上宽下窄，边缘翻卷，纵向纤维纹。
        q.polygon([(4, 9), (28, 9), (23, 20), (9, 20)], fill=cap + (255,))
        q.polygon([(9, 20), (23, 20), (19, 24), (13, 24)], fill=cap_dark + (255,))
        q.arc((4, 4, 28, 16), 200, 340, fill=shade(cap, -30) + (255,))
        for x in range(8, 26, 4):
            q.line((x, 10, x + 1, 19), fill=shade(cap_dark, -10) + (255,))
    else:
        # 牛肝菌：厚帽 + 粗柄，帽下有一圈浅色菌管。
        q.rectangle((11, 19, 21, 30), fill=stem + (255,))
        q.pieslice((4, 7, 28, 25), 180, 360, fill=cap + (255,))
        q.rectangle((4, 16, 28, 20), fill=shade(cap, -16) + (255,))
        q.rectangle((6, 20, 26, 22), fill=(228, 214, 186, 255))
        q.arc((4, 7, 28, 25), 180, 360, fill=shade(cap_dark, -22) + (255,))
        for y in range(21, 22):
            for x in range(7, 26, 2):
                q.point((x, y), fill=(206, 190, 160, 255))
    # 地面阴影，让它在草丛里"坐得住"。
    q.ellipse((8, 27, 24, 31), fill=(0, 0, 0, 56))
    return im


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    zh = json.loads((A / "lang/zh_cn.json").read_text(encoding="utf-8"))
    en = json.loads((A / "lang/en_us.json").read_text(encoding="utf-8"))
    tag = json.loads(TAG.read_text(encoding="utf-8"))
    for slug, cn, eng, cap, cap_dark, stem, style in SPECIES:
        name = slug
        tex = draw_mushroom(style, cap, cap_dark, stem)
        png = A / "textures/block" / f"{name}.png"
        png.parent.mkdir(parents=True, exist_ok=True)
        tex.save(png)
        # 原版 cross 模型：两片交叉面片，贴图整张映射。
        write(A / "models/block" / f"{name}.json",
              {"parent": "minecraft:block/cross", "render_type": "neoforge:cutout",
               "textures": {"cross": f"logos_dimension:block/{name}"}})
        write(A / "blockstates" / f"{name}.json", {"variants": {"": {"model": f"logos_dimension:block/{name}"}}})
        write(A / "items" / f"{name}.json",
              {"model": {"type": "minecraft:model", "model": f"logos_dimension:block/{name}"}})
        write(D / "loot_table/blocks" / f"{name}.json",
              {"type": "minecraft:block", "pools": [{"rolls": 1,
                "conditions": [{"condition": "minecraft:survives_explosion"}],
                "entries": [{"type": "minecraft:item", "name": f"logos_dimension:{name}"}]}]})
        zh[f"block.logos_dimension.{name}"] = cn
        en[f"block.logos_dimension.{name}"] = eng
        if f"logos_dimension:{name}" not in tag["values"]:
            tag["values"].append(f"logos_dimension:{name}")
    (A / "lang/zh_cn.json").write_text(json.dumps(zh, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (A / "lang/en_us.json").write_text(json.dumps(en, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    TAG.write_text(json.dumps(tag, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"蘑菇资源：{len(SPECIES)} 种（贴图 / 模型 / 方块状态 / 物品模型 / 掉落表 / 语言 / fragile_plants 标签）")


if __name__ == "__main__":
    main()
