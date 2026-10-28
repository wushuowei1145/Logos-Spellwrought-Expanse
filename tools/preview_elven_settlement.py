"""绘制世界树立体精灵聚落（盘旋外廊、树干依附屋、高空悬吊屋、树根集市、花房与跨树栈桥网）的多角度软件预览图。"""
from pathlib import Path
import math
import numpy as np
from PIL import Image, ImageDraw
from elven_tree_study import Volume, world_tree, broadleaf, fir, render, COLORS, text, panel, PAPER, INK, MUTED
from elven_forest import trimmed, base_state, furniture
from elven_habitation import world_floors, tree_lights
from elven_settlement import build_world_tree_settlement

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs/concepts/elven-habitation'
OUT.mkdir(parents=True, exist_ok=True)

# 扩展软件渲染调色板：奶油白灰泥墙、深木屋顶、绿白遮阳篷、温润木栈桥、通透玻璃、花卉与暖光灯
COLORS.update({
    30: (96, 132, 92),     # 树叶与藤蔓
    31: (214, 194, 148),   # 浅色精灵木板 / 白桦半砖 / 栈桥栏杆
    32: (98, 68, 44),      # 深色橡木 / 云杉斜坡屋顶
    33: (162, 128, 86),    # 云杉木板 / 平台边框
    34: (242, 236, 220),   # 奶油白灰泥墙 (journey_lime_plaster)
    35: (208, 182, 118),   # 悬挂攀爬长索
    36: (76, 134, 72),     # 遮阳篷绿条纹 (moss_block)
    37: (248, 246, 238),   # 遮阳篷白条纹 (white_wool)
    38: (184, 224, 232),   # 温室玻璃 (distillation_glass / glass_pane)
    39: (232, 164, 184),   # 窗台花槽与繁花点缀
})


def state_to_color(name):
    if name == 'minecraft:air':
        return 0
    if 'lamp_vine' in name:
        return 30
    if 'lamp' in name or 'bough_light' in name or 'inlay' in name:
        return 17
    if 'rope' in name:
        return 35
    if 'moss_block' in name:
        return 36
    if 'white_wool' in name:
        return 37
    if 'glass' in name:
        return 38
    if 'planter' in name:
        return 39
    if 'plaster' in name:
        return 34
    if 'dark_oak' in name or 'spruce_stairs' in name:
        return 32
    if 'spruce_planks' in name:
        return 33
    if 'canopy' in name or 'leaf' in name:
        return 2
    if 'heartwood' in name or 'log' in name:
        return 1
    return 31


def build_full_settlement_volume():
    v = world_tree()
    data = trimmed(v)
    homes_info = []
    for i, a in enumerate(np.linspace(0, math.tau, 8, endpoint=False)):
        t = fir(80 + i % 2 * 6) if i % 3 == 0 else broadleaf(66 + i % 3 * 4, 4.5, 11 + i)
        td = trimmed(t)
        idx = np.argwhere(td)
        gx, gz = round(math.cos(a) * 74), round(math.sin(a) * 74)
        xyz = idx + t.origin + np.array([gx, 0, gz])
        values = td[tuple(idx.T)].copy()
        if i % 3 == 0:
            values[values == 1] = 25
        dst = xyz - v.origin
        inside = np.all((dst >= 0) & (dst < np.array(data.shape)), axis=1)
        dst = dst[inside]
        values = values[inside]
        empty = data[tuple(dst.T)] == 0
        data[tuple(dst[empty].T)] = values[empty]
        homes_info.append((i, gx, gz))

    cells = {}
    for p in np.argwhere(data):
        x, y, z = (p + v.origin).tolist()
        cells[x, y, z] = base_state(int(data[tuple(p)]), y)

    for y in range(2, 96):
        radius = 13 if y < 82 else 13 - (y - 82) * .22
        for x in range(-13, 14):
            for z in range(-27, 14):
                if x * x + z * z < radius * radius or (y < 35 and z < 0 and (x / 9) ** 2 + (max(0, y - 19) / 16) ** 2 < 1):
                    cells[x, y, z] = {'Name': 'minecraft:air'}

    furniture(cells)
    levels = world_floors(cells)
    settlement_meta = build_world_tree_settlement(cells, homes_info, levels)
    tree_lights(cells, seed=37)

    for z, y in [(-29, 0), (-28, 1)]:
        for x in range(-4, 5):
            cells[x, y, z] = {'Name': 'minecraft:oak_stairs'}

    vol = Volume(112, 226)
    vol.ground(95)
    for (x, y, z), s in cells.items():
        pos = np.array([x, y, z]) - vol.origin
        if np.all(pos >= 0) and np.all(pos < np.array(vol.data.shape)):
            vol.data[tuple(pos)] = state_to_color(s['Name'])

    return vol, settlement_meta, homes_info


def main():
    vol, meta, homes_info = build_full_settlement_volume()
    xx, yy_grid, zz = np.ogrid[-112:113, -4:230, -112:113]

    # 1. 全景总览与平面布局图 (settlement-overview-preview.png)
    # 仅揭开正前方一棵外围冷杉 (0, -74) 的上层浓密树冠，让底层集市与树干聚落一目了然，其余7棵巨木与全桥网完整保留
    cut_overview = vol.data.copy()
    front_canopy = (xx ** 2 + (zz + 74) ** 2 < 22 ** 2) & (yy_grid > 30) & ((cut_overview == 2) | (cut_overview == 1))
    cut_overview[front_canopy] = 0

    board = Image.new('RGB', (2400, 1600), PAPER)
    d = ImageDraw.Draw(board)
    text(d, (68, 38), '世界树精灵聚落 / 全景与立体路网总览', 52, bold=True)
    text(d, (72, 108), '双螺旋树干外廊 · 18座依附木屋 · 12座高空主枝悬吊屋 · 8棵外围巨木树屋 · 树根集市与花房', 25, MUTED)
    panel(d, (45, 170, 1620, 1510))
    panel(d, (1650, 170, 2355, 1510))

    hero = render(vol, (1530, 1290), yaw=16, elevation=16, crop=cut_overview)
    board.paste(hero, (65, 195), hero)

    # 右侧绘制聚落平面拓扑图
    text(d, (1690, 205), '立体聚落拓扑平面图', 32, bold=True)
    cx, cy, scale = 2002, 575, 3.3
    for idx, (_, gx, gz) in enumerate(homes_info):
        _, nx, nz = homes_info[(idx + 1) % 8]
        d.line((cx + gx * scale, cy - gz * scale, cx + nx * scale, cy - nz * scale), fill='#b89e6c', width=4)
        d.line((cx + gx * 0.34 * scale, cy - gz * 0.34 * scale, cx + gx * scale, cy - gz * scale), fill='#c9ad78', width=5)
    inner_susp = [meta['suspended_houses'][idx] for idx in range(len(meta['suspended_houses'])) if idx % 3 != 1]
    for i in range(8):
        s0 = inner_susp[i]['center']
        s1 = inner_susp[(i + 1) % 8]['center']
        d.line((cx + s0[0] * scale, cy - s0[2] * scale, cx + s1[0] * scale, cy - s1[2] * scale), fill='#8fa882', width=3)
    d.ellipse((cx - 25 * scale, cy - 25 * scale, cx + 25 * scale, cy + 25 * scale), fill='#7b684f', outline='#5c4d3a', width=3)
    d.ellipse((cx - 13 * scale, cy - 13 * scale, cx + 13 * scale, cy + 13 * scale), fill='#dccb9f', outline='#a69268', width=2)
    for idx, (_, gx, gz) in enumerate(homes_info):
        px, py = cx + gx * scale, cy - gz * scale
        d.ellipse((px - 14, py - 14, px + 14, py + 14), fill='#5b7d57', outline='#e8dec0', width=3)
    for sh in meta['suspended_houses']:
        sx, _, sz = sh['center']
        px, py = cx + sx * scale, cy - sz * scale
        d.rectangle((px - 7, py - 7, px + 7, py + 7), fill='#e8dfc9', outline='#6b4c30', width=2)
    for th in meta['trunk_houses']:
        tx, _, tz = th['center']
        px, py = cx + tx * scale, cy - tz * scale
        d.ellipse((px - 6, py - 6, px + 6, py + 6), fill='#f2ead4', outline='#855c3a', width=2)

    items = [
        ('1. 双螺旋树干外廊与 18 座依附屋', '4格宽带斜撑木栈道盘升 (Y=5~84)，5座双层遮阳篷主商屋 + 13座白墙木架屋，10扇拱门连通树心'),
        ('2. 高空主枝 12 座悬吊木屋群', '沿8条粗壮主枝正下方悬吊内环8座+外环4座吊篮木屋，四角长索直锚主枝十字横梁'),
        ('3. 外围 8 巨木与空中廊桥网', '8棵近卫巨木全配环抱式树屋（4棵双层），8座带落地高木桥墩放射长桥 + 8座外环桥'),
        ('4. 树根林间集市与双玻璃花房', '6座绿白条纹遮阳篷集市摊位 + 2座挑台拱顶通透玻璃花房温室'),
    ]
    yy = 920
    for title, desc in items:
        text(d, (1685, yy), title, 25, bold=True)
        text(d, (1685, yy + 36), desc[:25], 20, MUTED)
        text(d, (1685, yy + 64), desc[25:], 20, MUTED)
        yy += 128

    text(d, (68, 1535), '代码绘制的完整体素聚落预览（与将写入 NBT 的方块坐标完全一致）。', 23, MUTED)
    board.save(OUT / 'settlement-overview-preview.png')

    # 2. 树干盘旋聚落、树根集市、玻璃花房与高木桥墩近景 (settlement-trunk-market-preview.png)
    # 隐去正南方挡住镜头的1棵外围树冠，完整保留左右两侧外围巨木、带高木桥墩的跨树长桥、中低层树干屋群与底部集市
    cut_trunk = vol.data.copy()
    front_block = ((xx - 0) ** 2 + (zz + 74) ** 2 < 26 ** 2) & (yy_grid > 2)
    back_trim = (yy_grid > 82) | (zz > 28)
    cut_trunk[front_block | back_trim] = 0

    board2 = Image.new('RGB', (2400, 1500), PAPER)
    d2 = ImageDraw.Draw(board2)
    text(d2, (68, 38), '树干盘旋聚落 · 树根集市 · 玻璃花房 · 跨树高架栈桥（近景）', 48, bold=True)
    text(d2, (72, 106), '对应概念图 1 & 2：绿白遮阳篷集市、双层主商屋、挑台玻璃温室、双螺旋木阶与带高木桥墩的跨树长桥', 25, MUTED)
    panel(d2, (45, 165, 2355, 1415))
    img_trunk = render(vol, (2260, 1210), yaw=8, elevation=15, crop=cut_trunk)
    board2.paste(img_trunk, (70, 185), img_trunk)
    text(d2, (68, 1435), '中低层正视体素裁切渲染 (Y = -4..82) · 两侧保留外围巨木与高木桥墩跨树栈桥，中央展示树干立体街区', 23, MUTED)
    board2.save(OUT / 'settlement-trunk-market-preview.png')

    # 3. 高空主枝悬吊木屋群与云端栈桥近景 (settlement-hanging-houses-preview.png)
    # 隐去底部外围低矮巨木与顶层前侧浓密叶盖，聚焦 Y = 45..160 的8条主枝与12座四索悬吊木屋
    cut_high = vol.data.copy()
    outer_low = (xx ** 2 + zz ** 2 > 54 ** 2) & (yy_grid < 115)
    top_front_leaves = (yy_grid > 118) & (zz < 10) & (cut_high == 2)
    cut_high[(yy_grid < 45) | (yy_grid > 162) | outer_low | top_front_leaves] = 0

    board3 = Image.new('RGB', (2400, 1500), PAPER)
    d3 = ImageDraw.Draw(board3)
    text(d3, (68, 38), '高空主枝悬吊木屋群 · 四索直锚主枝 · 云端连通栈桥（近景）', 48, bold=True)
    text(d3, (72, 106), '对应概念图 3：12座高低错落的吊篮式木屋悬垂于世界树8条粗壮主枝正下方，四角长索直入主枝木心', 25, MUTED)
    panel(d3, (45, 165, 2355, 1415))
    img_high = render(vol, (2260, 1210), yaw=18, elevation=11, crop=cut_high)
    board3.paste(img_high, (70, 185), img_high)
    text(d3, (68, 1435), '高层树冠下部体素特写渲染 (Y = 45..162) · 内外双环共 12 座吊屋由云端木栈桥串联并连回树干顶层外廊', 23, MUTED)
    board3.save(OUT / 'settlement-hanging-houses-preview.png')

    print('SETTLEMENT_PREVIEWS_READY', OUT, flush=True)


if __name__ == '__main__':
    main()
