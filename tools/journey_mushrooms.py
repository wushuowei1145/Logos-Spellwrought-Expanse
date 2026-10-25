"""画林地蘑菇的贴图与模型（代码绘制，不使用图像生成服务）。

六种蘑菇（三种食用、三种有毒）共用一套 64×64 的 billboard 画法：
菌盖按行做量化明暗（左上受光、右下背光），帽檐、菌褶、菌柄纤维分开画，
最后留足透明边距，让它只占方块六成高——草丛里是一小丛，不是一块牌子。

模型用原版 cross 交叉面片，物品走 block 模型，掉落表掉自己。

用法：python tools/journey_mushrooms.py
"""
from pathlib import Path
import json

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / "src/main/resources/assets/logos_dimension"
D = ROOT / "src/main/resources/data/logos_dimension"
TAG = ROOT / "src/main/resources/data/logos/tags/block/fragile_plants.json"

# (id, 中文名, 英文名, 帽色, 帽暗部, 柄色, 画法, 帽半径, 帽高, 柄高, 柄半宽)
SPECIES = [
    ("journey_field_mushroom", "白伞菇", "Field Mushroom",
     (240, 236, 222), (168, 160, 138), (224, 214, 192), "dome", 12, 11, 17, 3),
    ("journey_chanterelle", "鸡油菌", "Chanterelle",
     (232, 172, 60), (168, 108, 24), (230, 206, 152), "funnel", 12, 9, 16, 4),
    ("journey_bolete", "牛肝菌", "Bolete",
     (138, 90, 50), (92, 56, 28), (228, 214, 190), "bolete", 12, 11, 16, 4),
    ("journey_fly_agaric", "毒蝇伞", "Fly Agaric",
     (192, 50, 44), (132, 24, 22), (238, 230, 212), "amanita", 12, 11, 19, 3),
    ("journey_death_cap", "死帽菇", "Death Cap",
     (176, 188, 136), (114, 128, 80), (240, 236, 220), "deathcap", 11, 10, 21, 2),
    ("journey_false_chanterelle", "毒粉褶菌", "False Chanterelle",
     (206, 130, 52), (136, 78, 30), (228, 206, 158), "falsechanterelle", 12, 9, 18, 3),
]

SIZE = 64
GROUND = 59        # 蘑菇底部所在行
SHADE_LEVELS = 5   # 明暗量化档数，保持像素画质感


def shade(color, delta):
    return tuple(max(0, min(255, c + delta)) for c in color)


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def quantize(t):
    return round(max(0.0, min(1.0, t)) * SHADE_LEVELS) / SHADE_LEVELS


def in_cap(x, y, cx, cy, rx, ry):
    """菌盖区域：以 (cx, cy) 为中心、上半部略扁的椭圆，加上一点帽檐。"""
    nx = (x - cx) / rx
    ny = (y - cy) / ry
    return nx * nx + ny * ny <= 1.0 and y <= cy + ry * 0.24


def draw_cap(im, cx, cy, rx, ry, cap, cap_dark, spots=(), rim=True):
    """按行铺色：左上亮、右下暗，量化成几档，避免出现明显的内部接缝。"""
    q = ImageDraw.Draw(im)
    top, bottom = int(cy - ry), int(cy + ry * 0.24)
    for y in range(top, bottom + 1):
        for x in range(int(cx - rx), int(cx + rx) + 1):
            if not in_cap(x + 0.5, y + 0.5, cx, cy, rx, ry):
                continue
            nx = (x + 0.5 - cx) / rx
            ny = (y + 0.5 - top) / max(1.0, bottom - top)
            # 越靠下越暗，越靠右越暗；顶部再加一点高光。
            light = 0.92 - 0.42 * ny - 0.22 * max(0.0, nx) + 0.10 * max(0.0, -ny * 1.6)
            color = mix(cap_dark, cap, quantize(light))
            q.point((x, y), fill=color + (255,))
    # 帽檐：底部一条深色收边，把帽和柄分开。
    if rim:
        for x in range(int(cx - rx * 0.92), int(cx + rx * 0.92) + 1):
            if in_cap(x + 0.5, bottom - 0.5, cx, cy, rx, ry):
                q.point((x, bottom), fill=shade(cap_dark, -26) + (255,))
    # 斑点（毒蝇伞的白点、白伞菇的浅鳞片）。
    for ox, oy, r, color in spots:
        sx, sy = cx + rx * ox, cy - ry * 0.35 + ry * oy
        for dy in range(-r, r + 1):
            for dx in range(-r, r + 1):
                if dx * dx + dy * dy > r * r:
                    continue
                if in_cap(sx + dx, sy + dy, cx, cy, rx, ry):
                    q.point((int(sx + dx), int(sy + dy)), fill=color + (255,))


def draw_gills(q, cx, cy, rx, count=7, length=2, color=(120, 110, 92)):
    """菌褶：帽底朝下的一圈短线，短而疏，不要拖成裙子。"""
    for i in range(count):
        t = (i + 0.5) / count * 2 - 1
        x = cx + t * rx * 0.82
        for k in range(length):
            q.point((int(round(x + t * k * 0.35)), int(round(cy + 1 + k))), fill=color + (255,))


def draw_stem(q, cx, base_y, top_y, half_w, stem):
    """菌柄：下粗上细，左侧一像素亮边、右侧一像素暗边。"""
    for y in range(top_y, base_y + 1):
        t = (y - top_y) / max(1, base_y - top_y)
        w = half_w * (0.78 + 0.22 * t)
        left, right = int(round(cx - w)), int(round(cx + w))
        q.line((left, y, right, y), fill=stem + (255,))
        q.point((left, y), fill=shade(stem, 26) + (255,))
        q.point((right, y), fill=shade(stem, -34) + (255,))
    # 纵向纤维：三两条即可。
    for off in (-0.4, 0.15, 0.5):
        x = int(round(cx + half_w * off))
        q.line((x, top_y + 2, x, base_y - 2), fill=shade(stem, -20) + (255,))


def draw_mushroom(style, cap, cap_dark, stem, rx, ry, stem_h, stem_w):
    im = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    q = ImageDraw.Draw(im)
    cx = SIZE / 2
    base_y = GROUND
    cap_cy = base_y - stem_h
    top_y = cap_cy - ry
    if top_y < 2:
        cap_cy += 2 - top_y

    # 地面阴影与苔痕先画，免得压住菌柄。
    q.ellipse((cx - rx * 0.8, base_y, cx + rx * 0.8, base_y + 4), fill=(0, 0, 0, 60))
    for ox, w in ((-0.45, 4), (0.1, 3), (0.5, 3)):
        x = cx + rx * ox
        q.ellipse((x - w / 2, base_y - 1, x + w / 2, base_y + 2), fill=(92, 126, 74, 200))

    # 菌柄。
    draw_stem(q, cx, base_y, cap_cy, stem_w, stem)
    # 菌褶在帽檐下。
    draw_gills(q, cx, cap_cy + int(ry * 0.18), rx, color=shade(cap_dark, -30))

    if style == "dome":
        draw_cap(im, cx, cap_cy, rx, ry, cap, cap_dark,
                 spots=((-0.5, 0.32, 1, shade(cap, 26)), (0.02, 0.12, 2, shade(cap, 22)),
                        (0.5, 0.36, 1, shade(cap, 26)), (-0.15, 0.6, 1, shade(cap, 18))))
    elif style == "amanita":
        draw_cap(im, cx, cap_cy, rx, ry, cap, cap_dark,
                 spots=((-0.46, 0.26, 2, (244, 240, 228)), (0.04, 0.06, 2, (244, 240, 228)),
                        (0.46, 0.3, 1, (240, 236, 224)), (-0.1, 0.55, 1, (238, 234, 222))))
    elif style == "deathcap":
        draw_cap(im, cx, cap_cy, rx, ry, cap, cap_dark,
                 spots=((-0.4, 0.2, 1, shade(cap, 30)), (0.3, 0.35, 1, shade(cap, 24))))
    elif style == "funnel":
        # 鸡油菌：把菌盖压扁成漏斗，边缘往下翻一圈。
        draw_cap(im, cx, cap_cy, rx, ry, cap, cap_dark)
        for i in range(-4, 5):
            x = cx + i * (rx / 5.2)
            q.line((int(round(x * 0.78 + cx * 0.22)), int(cap_cy + ry * 0.22),
                    int(round(x * 0.6 + cx * 0.4)), int(cap_cy + ry * 0.62)),
                   fill=shade(cap_dark, -14) + (255,))
    elif style == "bolete":
        # 牛肝菌：帽下先铺一层浅色菌管，再压帽。
        q.rectangle((int(cx - rx * 0.84), int(cap_cy + ry * 0.2), int(cx + rx * 0.84), int(cap_cy + ry * 0.44)),
                    fill=(214, 200, 172, 255))
        for x in range(int(cx - rx * 0.78), int(cx + rx * 0.78), 3):
            q.line((x, int(cap_cy + ry * 0.24), x, int(cap_cy + ry * 0.42)), fill=(188, 172, 146, 255))
        draw_cap(im, cx, cap_cy, rx, ry, cap, cap_dark)
    elif style == "falsechanterelle":
        draw_cap(im, cx, cap_cy, rx, ry, cap, cap_dark)
        for i in range(-6, 7):
            x = cx + i * (rx / 6.5)
            q.line((int(round(x)), int(cap_cy + ry * 0.24), int(round(x + i * 0.08)), int(cap_cy + ry * 0.5)),
                   fill=shade(cap_dark, -22) + (255,))
    return im


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    zh = json.loads((A / "lang/zh_cn.json").read_text(encoding="utf-8"))
    en = json.loads((A / "lang/en_us.json").read_text(encoding="utf-8"))
    tag = json.loads(TAG.read_text(encoding="utf-8"))
    for slug, cn, eng, cap, cap_dark, stem, style, rx, ry, stem_h, stem_w in SPECIES:
        tex = draw_mushroom(style, cap, cap_dark, stem, rx, ry, stem_h, stem_w)
        png = A / "textures/block" / f"{slug}.png"
        png.parent.mkdir(parents=True, exist_ok=True)
        tex.save(png)
        write(A / "models/block" / f"{slug}.json",
              {"parent": "minecraft:block/cross", "render_type": "neoforge:cutout",
               "textures": {"cross": f"logos_dimension:block/{slug}"}})
        write(A / "blockstates" / f"{slug}.json", {"variants": {"": {"model": f"logos_dimension:block/{slug}"}}})
        # 物品图标：裁出蘑菇本体放大到 64×64，背包里才看得清。
        bbox = tex.getbbox()
        icon = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        if bbox:
            crop = tex.crop(bbox)
            scale = min(SIZE / crop.width, SIZE / crop.height) * 0.94
            crop = crop.resize((max(1, int(crop.width * scale)), max(1, int(crop.height * scale))), Image.NEAREST)
            icon.alpha_composite(crop, ((SIZE - crop.width) // 2, (SIZE - crop.height) // 2))
        icon_path = A / "textures/item" / f"{slug}.png"
        icon_path.parent.mkdir(parents=True, exist_ok=True)
        icon.save(icon_path)
        write(A / "models/item" / f"{slug}.json",
              {"parent": "minecraft:item/generated",
               "textures": {"layer0": f"logos_dimension:item/{slug}"}})
        write(A / "items" / f"{slug}.json",
              {"model": {"type": "minecraft:model", "model": f"logos_dimension:item/{slug}"}})
        write(D / "loot_table/blocks" / f"{slug}.json",
              {"type": "minecraft:block", "pools": [{"rolls": 1,
                "conditions": [{"condition": "minecraft:survives_explosion"}],
                "entries": [{"type": "minecraft:item", "name": f"logos_dimension:{slug}"}]}]})
        zh[f"block.logos_dimension.{slug}"] = cn
        en[f"block.logos_dimension.{slug}"] = eng
        if f"logos_dimension:{slug}" not in tag["values"]:
            tag["values"].append(f"logos_dimension:{slug}")
    (A / "lang/zh_cn.json").write_text(json.dumps(zh, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (A / "lang/en_us.json").write_text(json.dumps(en, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    TAG.write_text(json.dumps(tag, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"蘑菇资源：{len(SPECIES)} 种（64×64 贴图 / cross 模型 / 方块状态 / 物品模型 / 掉落表 / 语言 / 标签）")


if __name__ == "__main__":
    main()
