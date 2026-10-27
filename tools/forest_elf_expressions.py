"""把动画专用表情并进森林精灵的游戏模型：几何挂到 Head 下，贴图放进总贴图里没被用到的一块。

    python tools/forest_elf_expressions.py

表情是仓库外 forest-elf-model/forest_elf/expressions/ 里的独立部件（维护者在 Blockbench 里制作），
这里读它导出的 geo.json 与贴图，只取表情自己的子树（名字以表情 id 开头），不取灰色素体参照。
表情平时由渲染器隐藏，NPC 给出表情时显示它、隐藏常态眼嘴（ForestElf#npcExpression）。

可以重复运行：先去掉模型里已有的同名表情骨骼，再在「没有任何其他方块的 UV 用到」的区域里找第一块放得下的地方
（从上到下、从左到右），所以重跑结果不变；放不下时把贴图加高 16 的倍数。
登记到零件表 data/logos_dimension/npc_races/forest_elf.json 的 `expressions`。
"""
import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension"
MODEL = ASSETS / "geckolib/models/entity/forest_elf.geo.json"
TEXTURE = ASSETS / "textures/entity/forest_elf.png"
PARTS = ROOT / "src/main/resources/data/logos_dimension/npc_races/forest_elf.json"
SOURCE = ROOT.parent / "forest-elf-model/forest_elf/expressions"
# 表情 id → 显示它时要隐藏的常态五官骨骼（Eyes 含常态眼型与瞳孔；眉毛、眼皮是 Head 下的兄弟骨骼）
EXPRESSIONS = {"expression_dazed_tears": ["Eyes", "Eyelid", "EyeBrow", "Mouth"]}


def face_rects(bones):
    """所有方块各个面在贴图上占的矩形（像素，左上与右下）。"""
    for b in bones:
        for c in b.get("cubes", []):
            for face in c.get("uv", {}).values():
                (u, v), (w, h) = face["uv"], face["uv_size"]
                yield min(u, u + w), min(v, v + h), max(u, u + w), max(v, v + h)


def find_spot(used, width, height, w, h):
    """在 used（True 为已被其他方块用到）里找第一块 w×h 的空地，8 像素对齐。"""
    for y in range(0, height - h + 1, 8):
        for x in range(0, width - w + 1, 8):
            if not used[y:y + h, x:x + w].any():
                return x, y
    return None


def main():
    geo = json.loads(MODEL.read_text(encoding="utf-8"))
    g = geo["minecraft:geometry"][0]
    atlas = np.asarray(Image.open(TEXTURE).convert("RGBA")).copy()
    parts = json.loads(PARTS.read_text(encoding="utf-8"))
    registered = []
    for pid, hides in EXPRESSIONS.items():
        src = json.loads((SOURCE / f"{pid}.geo.json").read_text(encoding="utf-8"))["minecraft:geometry"][0]
        image = np.asarray(Image.open(SOURCE / f"{pid}.png").convert("RGBA"))
        sub = [b for b in src["bones"] if b["name"] == pid or b["name"].startswith(pid + "_")]
        head = next(b for b in src["bones"] if b["name"] == "Head")
        ours = next(b for b in g["bones"] if b["name"] == "Head")
        assert head["pivot"] == ours["pivot"], ("表情文件的骨架与游戏模型不一致", head["pivot"], ours["pivot"])
        # 去掉旧的，再找贴图位置
        g["bones"] = [b for b in g["bones"] if not (b["name"] == pid or b["name"].startswith(pid + "_"))]
        H, W = atlas.shape[:2]
        used = np.zeros((H, W), bool)
        for x1, y1, x2, y2 in face_rects(g["bones"]):
            used[int(y1):int(np.ceil(y2)), int(x1):int(np.ceil(x2))] = True
        h, w = image.shape[:2]
        spot = find_spot(used, W, H, w, h)
        if spot is None:
            grow = -(-h // 16) * 16
            atlas = np.vstack([atlas, np.zeros((grow, W, 4), np.uint8)])
            spot = (0, H)
            g["description"]["texture_height"] = H + grow
        x, y = spot
        atlas[y:y + h, x:x + w] = image
        for b in sub:
            b = json.loads(json.dumps(b))
            for c in b.get("cubes", []):
                for face in c.get("uv", {}).values():
                    face["uv"] = [face["uv"][0] + x, face["uv"][1] + y]
            # 插在 Head 的子骨骼之后，保持父骨骼在前
            g["bones"].append(b)
        registered.append({"id": pid, "groups": [pid], "hides": hides})
        print(f"{pid}：{len(sub)} 根骨骼、{sum(len(b.get('cubes', [])) for b in sub)} 个方块，贴图放在 ({x}, {y})")
    MODEL.write_text(json.dumps(geo, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    Image.fromarray(atlas, "RGBA").save(TEXTURE, optimize=True)
    parts["expressions"] = registered
    PARTS.write_text(json.dumps(parts, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
