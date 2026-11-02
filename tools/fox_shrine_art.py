"""神狐聚落第二批方块美术：神社用具（绘马挂、绘马、赛钱箱、鈴緒）、重画的和纸灯笼、障子门窗与朱漆木。

沿用 fox_props_art 的像素工具与模型写法；原版风格 16×16，每像素对应 1/16 格。
"""
import json
import random

from fox_props_art import (A, NS, ROOT, STONE, WOOD, RED_CLOTH, STRAW, canvas, el, facing_states, fill_noise,
                           item, loot, model, px, save, write)

DARK_WOOD = [(46, 32, 22), (58, 41, 28), (70, 50, 34), (84, 61, 42)]
LIGHT_WOOD = [(176, 140, 92), (196, 160, 108), (214, 180, 126), (228, 198, 146)]
LACQUER = [(150, 34, 28), (172, 42, 32), (190, 52, 38), (206, 66, 46)]
PAPER = [(232, 206, 150), (244, 222, 170), (252, 236, 190), (255, 246, 214)]
GOLD = (222, 182, 78)


# ==================== 绘马挂与绘马 ====================
EMA_MARKS = [  # 每块小绘马上的墨迹与红绳位置
    [(1, 1), (1, 2)], [(0, 1), (2, 2)], [(1, 1)], [(0, 2), (1, 1), (2, 2)], [(2, 1)],
]


def ema_textures():
    # 方块图集：上半深木（框、梁），下面一排 3×3 小绘马
    t = canvas()
    fill_noise(t, DARK_WOOD, 101, box=(0, 0, 16, 7), grain=0.35)
    for x in range(16):
        px(t, x, 3, DARK_WOOD[0])
    for k, marks in enumerate(EMA_MARKS):
        u = k * 3
        for x in range(3):
            for y in range(3):
                px(t, u + x, 8 + y, LIGHT_WOOD[(x + y + k) % 4])
        px(t, u + 1, 8, RED_CLOTH[2])                   # 顶部红绳
        for (x, y) in marks:
            px(t, u + x, 8 + y, (60, 40, 30))           # 墨迹
    fill_noise(t, LIGHT_WOOD, 102, box=(0, 12, 16, 16), grain=0.3)
    save(t, 'journey_ema_rack')
    # 物品：五角形木牌，红绳，白狐面
    e = canvas()
    for y in range(3, 15):
        inset = max(0, 6 - y) if y < 6 else 0
        for x in range(2 + inset, 14 - inset):
            px(e, x, y, LIGHT_WOOD[(x * 3 + y) % 4 if (x + y) % 5 else 1])
    for y in range(3, 15):
        inset = max(0, 6 - y) if y < 6 else 0
        px(e, 2 + inset, y, LIGHT_WOOD[0])
        px(e, 13 - inset, y, LIGHT_WOOD[0])
    for x in range(2, 14):
        px(e, x, 14, LIGHT_WOOD[0])
    for x, y in ((7, 1), (8, 1), (6, 2), (9, 2), (7, 4), (8, 4)):
        px(e, x, y, RED_CLOTH[2])
    # 白狐面：三角脸、耳朵、红眉
    for x, y in ((5, 7), (10, 7), (5, 8), (6, 8), (9, 8), (10, 8), (6, 9), (7, 9), (8, 9), (9, 9), (6, 10), (7, 10), (8, 10), (9, 10), (7, 11), (8, 11)):
        px(e, x, y, (246, 244, 236))
    for x, y in ((6, 9), (9, 9)):
        px(e, x, y, RED_CLOTH[1])
    px(e, 7, 11, (40, 30, 26))
    item_dir = A / 'textures/item'
    item_dir.mkdir(parents=True, exist_ok=True)
    e.save(item_dir / 'journey_ema.png')


def ema_rack():
    """深木框与小瓦顶，梁下与中横木各挂一排旧绘马；叠层越多挂得越密，第 4 层在前面再错开挂一排。
    左右、上下相连时只在两端立柱、只在最上一排盖顶，拼成一个大架子。"""
    ema_textures()
    tex = {'wood': f'{NS}:block/journey_ema_rack', 'roof': f'{NS}:block/journey_kawara_roof', 'particle': f'{NS}:block/journey_ema_rack'}
    side_uv = lambda u: {f: [u, 0, u + 2, 7] for f in ('north', 'south', 'west', 'east')}
    beam_uv = {'north': [0, 1, 16, 3], 'south': [0, 1, 16, 3], 'up': [0, 0, 16, 2], 'down': [0, 4, 16, 6]}
    # 拆成几块，由方块状态按左右、上方是否相连组合：只在两端立柱，只在最上一排盖顶
    parts = {
        'post_left': [el((0.5, 0, 7), (2, 14, 9), 'wood', uv=side_uv(0))],
        'post_left_tall': [el((0.5, 0, 7), (2, 16, 9), 'wood', uv=side_uv(0))],
        'post_right': [el((14, 0, 7), (15.5, 14, 9), 'wood', uv=side_uv(2))],
        'post_right_tall': [el((14, 0, 7), (15.5, 16, 9), 'wood', uv=side_uv(2))],
        'top': [el((0, 13, 7), (16, 15, 9), 'wood', uv=beam_uv), el((0, 15, 5.5), (16, 16, 10.5), 'roof', skip=('west', 'east'))],
        'cap_left': [el((-0.5, 15, 5.5), (0, 16, 10.5), 'roof')],
        'cap_right': [el((16, 15, 5.5), (16.5, 16, 10.5), 'roof')],
        'hang_bar': [el((0, 13.5, 7.5), (16, 14.5, 8.5), 'wood', uv={'north': [0, 4, 16, 5], 'south': [0, 4, 16, 5]}, skip=('west', 'east'))],
        'bar': [el((0, 7.5, 7.5), (16, 8.5, 8.5), 'wood', uv={'north': [0, 4, 16, 5], 'south': [0, 4, 16, 5]}, skip=('west', 'east'))],
    }
    for name, els in parts.items():
        model(f'journey_ema_rack_{name}', tex, els, cutout=True)
    old_frame = A / 'models/block/journey_ema_rack_frame.json'
    if old_frame.exists():
        old_frame.unlink()

    def plaques(row_y, xs, z, seed):
        rnd = random.Random(seed)
        out = []
        for i, x in enumerate(xs):
            k = rnd.randrange(len(EMA_MARKS))
            y = row_y - rnd.choice((0, 0.5, 1))
            face = [k * 3, 8, k * 3 + 3, 11]
            out.append(el((x, y, z), (x + 3, y + 3, z + 0.5), 'wood',
                          uv={'north': face, 'south': face, 'up': [0, 12, 3, 12.5], 'down': [0, 12, 3, 12.5],
                              'west': [0, 12, 0.5, 15], 'east': [0, 12, 0.5, 15]}))
        return out

    layers = [
        plaques(10, [2.5, 6.5, 10.5], 6.5, 1),
        plaques(10, [4.5, 8.5, 12], 6.0, 2) + plaques(4.5, [3, 9], 7.0, 3),
        plaques(4.5, [6, 11.5], 6.5, 4) + plaques(10.5, [1.5, 7, 11], 5.5, 5),
        plaques(4, [2, 5, 8.5, 12], 5.5, 6) + plaques(9.5, [3.5, 9.5], 5.0, 7),
    ]
    for i, els in enumerate(layers, start=1):
        model(f'journey_ema_rack_layer{i}', tex, els, cutout=True)
    rules = [
        ('post_left', {'left': 'false', 'up': 'false'}), ('post_left_tall', {'left': 'false', 'up': 'true'}),
        ('post_right', {'right': 'false', 'up': 'false'}), ('post_right_tall', {'right': 'false', 'up': 'true'}),
        ('top', {'up': 'false'}), ('cap_left', {'up': 'false', 'left': 'false'}), ('cap_right', {'up': 'false', 'right': 'false'}),
        ('hang_bar', {'up': 'true'}), ('bar', {}),
    ]
    parts = []
    for f, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)):
        for name, cond in rules:
            parts.append({'when': {'facing': f, **cond}, 'apply': {'model': f'{NS}:block/journey_ema_rack_{name}', 'y': y}})
        for i in range(1, 5):
            parts.append({'when': {'facing': f, 'layers': '|'.join(str(n) for n in range(i, 5))},
                          'apply': {'model': f'{NS}:block/journey_ema_rack_layer{i}', 'y': y}})
    write(A / 'blockstates/journey_ema_rack.json', {'multipart': parts})
    # 物品模型：框加两层旧绘马
    write(A / 'models/item/journey_ema_rack.json', {'parent': f'{NS}:block/journey_ema_rack_top'})
    write(A / 'items/journey_ema_rack.json', {'model': {'type': 'minecraft:composite', 'models': [
        *[{'type': 'minecraft:model', 'model': f'{NS}:block/journey_ema_rack_{n}'} for n in ('post_left', 'post_right', 'top', 'cap_left', 'cap_right', 'bar')],
        {'type': 'minecraft:model', 'model': f'{NS}:block/journey_ema_rack_layer1'},
        {'type': 'minecraft:model', 'model': f'{NS}:block/journey_ema_rack_layer2'}]}})
    write(A / 'models/item/journey_ema.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/journey_ema'}})
    write(A / 'items/journey_ema.json', {'model': {'type': 'minecraft:model', 'model': f'{NS}:item/journey_ema'}})
    # 叠了几层就掉几个
    write(ROOT / f'src/main/resources/data/{NS}/loot_table/blocks/journey_ema_rack.json', {
        'type': 'minecraft:block',
        'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:journey_ema_rack', 'functions': [
            *[{'function': 'minecraft:set_count', 'add': True, 'count': 1,
               'conditions': [{'condition': 'minecraft:block_state_property', 'block': f'{NS}:journey_ema_rack', 'properties': {'layers': str(n)}}]}
              for n in (2, 3, 4)]]}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}],
        'random_sequence': f'{NS}:blocks/journey_ema_rack',
    })


# ==================== 赛钱箱 ====================
def offering_box():
    """赛钱箱：深色木箱，正面镶边与金色圆纹，顶面一排格栅。"""
    t = canvas()
    fill_noise(t, DARK_WOOD, 111, grain=0.3)
    for x in range(16):
        for y in (0, 9):
            px(t, x, y, DARK_WOOD[3])
    for y in range(16):
        for x in (0, 15):
            px(t, x, y, DARK_WOOD[3])
    # 正面金色圆纹（丸に宝珠）：圆环里一颗宝珠
    for x in range(16):
        for y in range(1, 9):
            d = ((x - 7.5) ** 2 + (y - 4.5) ** 2) ** 0.5
            if 2.4 <= d <= 3.3:
                px(t, x, y, GOLD)
    for x, y in ((7, 4), (8, 4), (7, 5), (8, 5), (7, 3)):
        px(t, x, y, GOLD)
    for x in range(16):  # 下半：顶面格栅
        for y in range(10, 16):
            px(t, x, y, DARK_WOOD[0] if (x + y) % 3 == 0 else DARK_WOOD[2])
    save(t, 'journey_offering_box')
    tex = {'wood': f'{NS}:block/journey_offering_box', 'particle': f'{NS}:block/journey_offering_box'}
    side = [1, 0, 15, 8]
    els = [el((1, 0, 3), (15, 7, 13), 'wood', uv={'north': [1, 1, 15, 8], 'south': side, 'west': [3, 1, 13, 8], 'east': [3, 1, 13, 8],
                                                   'down': [1, 10, 15, 15]}, skip=('up',)),
           el((1, 7, 3), (15, 8, 13), 'wood', uv={'up': [1, 10, 15, 15], 'north': [1, 0, 15, 1], 'south': [1, 0, 15, 1],
                                                    'west': [3, 0, 13, 1], 'east': [3, 0, 13, 1]})]
    for k in range(5):  # 格栅
        z = 4 + k * 2
        els.append(el((1.5, 8, z), (14.5, 9.5, z + 0.75), 'wood', uv={'up': [0, 9, 13, 10], 'north': [0, 9, 13, 10], 'south': [0, 9, 13, 10]}))
    model('journey_offering_box', tex, els)
    write(A / 'blockstates/journey_offering_box.json', {'variants': facing_states('journey_offering_box')})
    item('journey_offering_box')


# ==================== 鈴緒 ====================
def suzu_rope():
    """鈴緒：红白紫三色布条绞成的粗绳，最下一格挂穗。"""
    t = canvas()
    colors = [RED_CLOTH[2], (240, 236, 226), (118, 66, 140)]
    for y in range(16):
        for x in range(8):
            px(t, x, y, colors[((x + y) // 2) % 3])
    for y in range(16):  # 右半：麻穗
        for x in range(8, 16):
            px(t, x, y, STRAW[(x * 7 + y * 3) % 4] if y > 2 else RED_CLOTH[1])
    save(t, 'journey_suzu_rope')
    tex = {'rope': f'{NS}:block/journey_suzu_rope', 'particle': f'{NS}:block/journey_suzu_rope'}
    rope = [0, 0, 3, 16]
    mid = [el((6.5, 0, 6.5), (9.5, 16, 9.5), 'rope', uv={f: rope for f in ('north', 'south', 'west', 'east')} | {'up': [0, 0, 3, 3], 'down': [0, 0, 3, 3]})]
    bottom = [el((6.5, 5, 6.5), (9.5, 16, 9.5), 'rope', uv={f: [0, 0, 3, 11] for f in ('north', 'south', 'west', 'east')} | {'up': [0, 0, 3, 3], 'down': [0, 0, 3, 3]}),
              el((6, 4, 6), (10, 5, 10), 'rope', uv={f: [8, 0, 12, 1] for f in ('north', 'south', 'west', 'east', 'up', 'down')}),
              el((5.5, 0, 5.5), (10.5, 4, 10.5), 'rope', uv={f: [8, 3, 13, 7] for f in ('north', 'south', 'west', 'east')} | {'down': [8, 8, 13, 13], 'up': [8, 8, 13, 13]})]
    model('journey_suzu_rope', tex, mid, cutout=True)
    model('journey_suzu_rope_bottom', tex, bottom, cutout=True)
    write(A / 'blockstates/journey_suzu_rope.json', {'variants': {'bottom=false': {'model': f'{NS}:block/journey_suzu_rope'},
                                                                   'bottom=true': {'model': f'{NS}:block/journey_suzu_rope_bottom'}}})
    item('journey_suzu_rope', parent=f'{NS}:block/journey_suzu_rope_bottom')


# ==================== 和纸灯笼 ====================
def paper_lantern():
    """提灯：上下深木圈，中间鼓起的和纸灯身带竹骨横纹，挂着时上面一段绳。"""
    t = canvas()
    for y in range(11):  # 灯身：0..10 行
        for x in range(10):
            c = PAPER[2] if (x + y) % 5 else PAPER[3]
            if y % 3 == 0:
                c = PAPER[0]                               # 竹骨
            px(t, x, y, c)
    for x in range(10):  # 红色腰带
        px(t, x, 5, RED_CLOTH[2])
        px(t, x, 6, RED_CLOTH[1])
    fill_noise(t, DARK_WOOD, 121, box=(10, 0, 16, 6), grain=0.25)   # 木圈
    for y in range(6, 16):                                            # 绳
        px(t, 10, y, (62, 46, 34))
    fill_noise(t, PAPER, 122, box=(0, 11, 10, 16), grain=0.2)         # 灯身顶底面（亮）
    save(t, 'journey_paper_lantern')

    def body(y0):
        side = {f: [0, 0, 8, 9] for f in ('north', 'south', 'west', 'east')}
        bulge = {f: [1, 1, 9, 8] for f in ('north', 'south', 'west', 'east')}
        ring = {f: [10, 0, 16, 1] for f in ('north', 'south', 'west', 'east')} | {'up': [10, 0, 16, 6], 'down': [10, 0, 16, 6]}
        return [
            el((5, y0, 5), (11, y0 + 1, 11), 'lantern', uv=ring),
            el((4, y0 + 1, 4), (12, y0 + 10, 12), 'lantern', uv=side | {'up': [0, 11, 8, 16], 'down': [0, 11, 8, 16]}),
            el((3.5, y0 + 2, 5), (12.5, y0 + 9, 11), 'lantern', uv={'west': [1, 1, 7, 8], 'east': [1, 1, 7, 8], 'up': [0, 11, 9, 16], 'down': [0, 11, 9, 16]}),
            el((5, y0 + 2, 3.5), (11, y0 + 9, 12.5), 'lantern', uv={'north': [1, 1, 7, 8], 'south': [1, 1, 7, 8], 'up': [0, 11, 6, 16], 'down': [0, 11, 6, 16]}),
            el((5, y0 + 10, 5), (11, y0 + 11, 11), 'lantern', uv=ring),
        ]
    tex = {'lantern': f'{NS}:block/journey_paper_lantern', 'particle': f'{NS}:block/journey_paper_lantern'}
    model('journey_paper_lantern', tex, body(0), cutout=True)
    hanging = body(2) + [el((7.5, 13, 7.5), (8.5, 16, 8.5), 'lantern', uv={f: [10, 6, 11, 9] for f in ('north', 'south', 'west', 'east')})]
    model('journey_paper_lantern_hanging', tex, hanging, cutout=True)
    write(A / 'blockstates/journey_paper_lantern.json', {'variants': {
        f'hanging={h},waterlogged={w}': {'model': f'{NS}:block/journey_paper_lantern' + ('_hanging' if h == 'true' else '')}
        for h in ('false', 'true') for w in ('false', 'true')}})
    item('journey_paper_lantern', parent=f'{NS}:block/journey_paper_lantern')


# ==================== 障子 ====================
def kumiko(img, x0, y0, x1, y1, step_x=3, step_y=4):
    """木格：外框深木，内部细格浅木，格间和纸。"""
    for y in range(y0, y1):
        for x in range(x0, x1):
            frame = x in (x0, x1 - 1) or y in (y0, y1 - 1)
            grid = (x - x0) % step_x == 0 or (y - y0) % step_y == 0
            if frame:
                px(img, x, y, DARK_WOOD[2])
            elif grid:
                px(img, x, y, LIGHT_WOOD[1])
            else:
                px(img, x, y, (240, 236, 222) if (x + y) % 7 else (232, 227, 210))


def shoji():
    """障子：细木格与和纸；门的下半有木腰板。拉门打开时整扇滑进旁边的墙，不像普通门那样转开。"""
    top = canvas()
    kumiko(top, 0, 0, 16, 16)
    save(top, 'journey_shoji_door_top')
    bottom = canvas()
    kumiko(bottom, 0, 0, 16, 11)
    fill_noise(bottom, WOOD, 131, box=(1, 11, 15, 15), grain=0.3)
    for x in range(16):
        px(bottom, x, 15, DARK_WOOD[1])
        px(bottom, x, 10, DARK_WOOD[2])
    for y in range(10, 16):
        px(bottom, 0, y, DARK_WOOD[2])
        px(bottom, 15, y, DARK_WOOD[2])
    save(bottom, 'journey_shoji_door_bottom')
    win = canvas()
    kumiko(win, 0, 0, 16, 16, step_x=3, step_y=3)
    save(win, 'journey_shoji_window')
    item_dir = A / 'textures/item'
    # 物品图标：原版门图标式的竖长一扇，深木框、细格和纸、下方木腰板
    door_item = canvas()
    for y in range(16):
        for x in range(3, 13):
            frame = x in (3, 12) or y in (0, 15)
            if frame:
                c = DARK_WOOD[2]
            elif y >= 11:
                c = DARK_WOOD[1] if y in (11, 14) else WOOD[(x + y) % 2 + 1]
            elif (x - 3) % 3 == 0 or y % 3 == 0:
                c = LIGHT_WOOD[1]
            else:
                c = (240, 236, 222)
            px(door_item, x, y, c)
    px(door_item, 10, 8, DARK_WOOD[0])  # 拉手
    door_item.save(item_dir / 'journey_shoji_door.png')

    def panel(half, slide):
        tex = 'top' if half == 'top' else 'bottom'
        z0, z1 = slide, 16 + slide
        # 打开时整扇略往门洞内收、上下各缩一点，滑进墙里的部分不与墙面共面，避免闪烁
        x0, x1 = (0.4, 2.6) if slide else (0, 3)
        y0, y1 = (0.05, 15.95) if slide else (0, 16)
        return el((x0, y0, z0), (x1, y1, z1), tex, uv={'west': [0, 0, 16, 16], 'east': [16, 0, 0, 16], 'north': [3, 0, 0, 16],
                                                    'south': [0, 0, 3, 16], 'up': [0, 0, 3, 16], 'down': [0, 0, 3, 16]})
    tex = {'top': f'{NS}:block/journey_shoji_door_top', 'bottom': f'{NS}:block/journey_shoji_door_bottom',
           'particle': f'{NS}:block/journey_shoji_door_bottom'}
    for half in ('top', 'bottom'):
        for hinge, slide in (('left', -13), ('right', 13)):
            model(f'journey_shoji_door_{half}_{hinge}', tex, [panel(half, 0)], cutout=True)
            model(f'journey_shoji_door_{half}_{hinge}_open', tex, [panel(half, slide)], cutout=True)
    variants = {}
    rot = {'east': 0, 'south': 90, 'west': 180, 'north': 270}
    for f, y in rot.items():
        for half, h in (('lower', 'bottom'), ('upper', 'top')):
            for hinge in ('left', 'right'):
                for opened in ('false', 'true'):
                    v = {'model': f'{NS}:block/journey_shoji_door_{h}_{hinge}' + ('_open' if opened == 'true' else '')}
                    if y:
                        v['y'] = y
                    variants[f'facing={f},half={half},hinge={hinge},open={opened}'] = v
    write(A / 'blockstates/journey_shoji_door.json', {'variants': variants})


# ==================== 朱漆木 ====================
def lacquer_wood():
    """朱漆：原木侧面竖向木纹与少许磨出的暗处；端面年轮；木板为原版木板式的错缝横板。"""
    side = canvas()
    rnd = random.Random(141)
    for x in range(16):
        base = 1 + (x * 5 + 3) % 3 // 2
        for y in range(16):
            v = base + (1 if rnd.random() < 0.18 else 0) - (1 if rnd.random() < 0.08 else 0)
            px(side, x, y, LACQUER[max(0, min(3, v))])
    for x in (3, 9, 13):
        for y in range(16):
            if rnd.random() < 0.7:
                px(side, x, y, LACQUER[0])
    save(side, 'journey_lacquer_log')
    end = canvas()
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if d > 7.2 or x in (0, 15) or y in (0, 15):
                px(end, x, y, LACQUER[1])
            else:
                ring = int(d) % 3
                px(end, x, y, [(170, 120, 74), (150, 102, 60), (186, 136, 88)][ring])
    save(end, 'journey_lacquer_log_top')
    planks = canvas()
    for y in range(16):
        row = y // 4
        for x in range(16):
            seam = (x + row * 6) % 16 == 0
            v = 2 if (x * 3 + y) % 7 else 3
            if y % 4 == 3:
                v = 0
            if seam:
                v = 0
            px(planks, x, y, LACQUER[v])
    save(planks, 'journey_lacquer_planks')


def lang():
    names = {
        'journey_ema_rack': ('Ema Rack', '绘马挂'),
        'journey_offering_box': ('Offering Box', '赛钱箱'),
        'journey_suzu_rope': ('Bell Rope', '鈴緒'),
    }
    for code, idx in (('en_us', 0), ('zh_cn', 1)):
        p = A / f'lang/{code}.json'
        data = json.loads(p.read_text(encoding='utf-8'))
        for k, pair in names.items():
            data[f'block.{NS}.{k}'] = pair[idx]
            data[f'item.{NS}.{k}'] = pair[idx]
        data[f'item.{NS}.journey_ema'] = ('Ema (rename on an anvil to write a wish)', '绘马（在铁砧上改名写下愿望）')[idx]
        p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    ema_rack()
    offering_box()
    suzu_rope()
    paper_lantern()
    shoji()
    lacquer_wood()
    lang()
    loot(['journey_offering_box', 'journey_suzu_rope'])
    print('shrine art done')


if __name__ == '__main__':
    main()
