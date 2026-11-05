"""神狐聚落的庭院与神社小品：逐像素绘制贴图并写出方块模型。

狐像、手水钵、竹出水口、踏步石、小石龛、暖帘（四色）、注连绳与纸垂、温泉石、温泉蒸汽粒子。
原版风格：16×16 贴图、每像素对应模型 1/16 格，色阶有限，用明暗块面而非平滑渐变。
"""
from pathlib import Path
import json
import random

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
A = ROOT / 'src/main/resources/assets/logos_dimension'
TEX = A / 'textures/block'
NS = 'logos_dimension'


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def canvas(w=16, h=16):
    return Image.new('RGBA', (w, h), (0, 0, 0, 0))


def ramp_pick(ramp, t):
    return ramp[max(0, min(len(ramp) - 1, int(t * len(ramp))))]


def fill_noise(img, ramp, seed, box=None, bias=0.0, grain=0.55):
    """块状噪声：2×2 的大斑块叠单像素颗粒，比纯随机更像原版石面。"""
    rnd = random.Random(seed)
    x0, y0, x1, y1 = box or (0, 0, img.width, img.height)
    blocks = {}
    for y in range(y0, y1):
        for x in range(x0, x1):
            key = (x // 2, y // 2)
            if key not in blocks:
                blocks[key] = rnd.random()
            t = blocks[key] * (1 - grain) + rnd.random() * grain + bias
            img.putpixel((x, y), (*ramp_pick(ramp, t), 255))


def px(img, x, y, c):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), (*c, 255) if len(c) == 3 else c)


def save(img, name):
    TEX.parent.mkdir(parents=True, exist_ok=True)
    img.save(TEX / f'{name}.png')


# ---------- 调色 ----------
STONE = [(86, 84, 80), (104, 101, 96), (122, 119, 113), (139, 135, 128), (156, 152, 144)]
STONE_DARK = [(62, 61, 60), (76, 74, 72), (92, 89, 86), (108, 105, 100)]
MOSS = [(72, 98, 46), (92, 120, 56), (114, 140, 66)]
RED_CLOTH = [(126, 26, 26), (156, 36, 32), (184, 50, 42), (204, 72, 58)]
BAMBOO = [(78, 116, 50), (96, 138, 60), (118, 160, 72), (140, 178, 88)]
STRAW = [(140, 114, 60), (164, 138, 74), (188, 162, 94), (208, 186, 116)]
WOOD = [(86, 60, 38), (104, 74, 46), (122, 88, 56), (140, 104, 66)]
BASALT = [(46, 43, 44), (56, 53, 53), (68, 64, 62), (80, 75, 72)]
SULFUR = [(168, 140, 58), (196, 170, 76)]
NOREN = {
    'indigo': ([(30, 40, 74), (38, 52, 94), (48, 66, 114), (60, 80, 132)], (226, 222, 206)),
    'red': ([(118, 28, 30), (146, 38, 36), (170, 52, 44), (192, 70, 56)], (238, 230, 214)),
    'white': ([(198, 192, 176), (214, 208, 192), (228, 223, 208), (240, 236, 224)], (44, 58, 104)),
    'brown': ([(58, 40, 30), (72, 50, 36), (88, 62, 44), (104, 76, 54)], (224, 210, 180)),
}


# ---------- 模型 ----------
def auto_uv(face, frm, to):
    x0, y0, z0 = frm
    x1, y1, z1 = to
    return {
        'north': [16 - x1, 16 - y1, 16 - x0, 16 - y0],
        'south': [x0, 16 - y1, x1, 16 - y0],
        'west': [z0, 16 - y1, z1, 16 - y0],
        'east': [16 - z1, 16 - y1, 16 - z0, 16 - y0],
        'up': [x0, z0, x1, z1],
        'down': [x0, 16 - z1, x1, 16 - z0],
    }[face]


def el(frm, to, tex, faces=None, uv=None, skip=(), rotation=None, tint=None):
    """一个模型块。faces 可给出每面的贴图键；uv 可覆盖指定面的 UV。"""
    out = {}
    for f in ('north', 'south', 'west', 'east', 'up', 'down'):
        if f in skip:
            continue
        t = (faces or {}).get(f, tex)
        u = (uv or {}).get(f, auto_uv(f, frm, to))
        u = [max(0, min(16, v)) for v in u]
        out[f] = {'uv': u, 'texture': '#' + t}
        if tint is not None:
            out[f]['tintindex'] = tint
    e = {'from': list(frm), 'to': list(to), 'faces': out}
    if rotation:
        e['rotation'] = rotation
    return e


def model(name, textures, elements, parent='minecraft:block/block', cutout=False, translucent=False):
    obj = {'parent': parent, 'textures': textures, 'elements': elements}
    if cutout:
        obj['render_type'] = 'minecraft:cutout'
    if translucent:
        obj['render_type'] = 'minecraft:translucent'
    write(A / f'models/block/{name}.json', obj)


def facing_states(name, model_name=None, extra=''):
    model_name = model_name or name
    variants = {}
    for f, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)):
        v = {'model': f'{NS}:block/{model_name}'}
        if y:
            v['y'] = y
        variants[f'facing={f}{extra}'] = v
    return variants


def item(name, parent=None, generated_tex=None):
    if generated_tex:
        write(A / f'models/item/{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': generated_tex}})
    else:
        write(A / f'models/item/{name}.json', {'parent': parent or f'{NS}:block/{name}'})
    write(A / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'{NS}:item/{name}'}})


# ==================== 狐像 ====================
def fox_statue():
    """石狐：照原版狐狸的坐姿比例，花岗岩粗面带少量青苔，胸前系红色围兜。"""
    t = canvas()
    fill_noise(t, STONE, 1)
    # 雕出的毛纹：竖向暗线
    rnd = random.Random(2)
    for _ in range(14):
        x, y = rnd.randrange(16), rnd.randrange(16)
        for k in range(rnd.randint(2, 3)):
            px(t, x, y + k, STONE[1])
    for _ in range(9):
        px(t, rnd.randrange(16), rnd.randrange(16), MOSS[rnd.randrange(3)])
    save(t, 'journey_fox_statue')
    # 脸：眼缝、鼻头（只在头部正面与吻部用）
    f = canvas()
    fill_noise(f, STONE, 3, bias=0.12)
    for x in (6, 9):
        px(f, x, 3, STONE_DARK[0])
        px(f, x + 1, 3, STONE_DARK[1])
    px(f, 7, 13, STONE_DARK[0])
    px(f, 8, 13, STONE_DARK[0])
    save(f, 'journey_fox_statue_face')
    b = canvas()
    fill_noise(b, RED_CLOTH, 4, grain=0.4)
    for x in range(16):
        px(b, x, 15, RED_CLOTH[0])
    save(b, 'journey_fox_statue_bib')
    base = canvas()
    fill_noise(base, STONE_DARK, 5)
    for x in range(16):
        px(base, x, 0, STONE[2])
    for _ in range(12):
        px(base, rnd.randrange(16), rnd.randrange(16), MOSS[rnd.randrange(3)])
    save(base, 'journey_fox_statue_base')

    face_uv = {'north': [5, 1, 11, 6]}
    els = [
        el((2, 0, 2), (14, 3, 14), 'base'),
        el((5, 3, 7), (11, 9, 13), 'stone'),                       # 后腿与腰
        el((5.5, 3, 4.5), (10.5, 11, 8), 'stone'),                 # 挺起的胸
        el((6, 3, 3.5), (7.5, 6, 5), 'stone'),                     # 前足
        el((8.5, 3, 3.5), (10, 6, 5), 'stone'),
        el((5.5, 7.5, 4.3), (10.5, 10, 4.6), 'bib', skip=('up', 'down')),  # 红围兜
        el((5, 10, 3.5), (11, 15, 8), 'stone', faces={'north': 'face'}, uv=face_uv),
        el((6.5, 10.5, 1.5), (9.5, 13, 3.5), 'stone', faces={'north': 'face'}, uv={'north': [6.5, 2, 9.5, 4.5]}),  # 吻部
        el((5, 15, 5), (7, 18, 7), 'stone'),                       # 耳
        el((9, 15, 5), (11, 18, 7), 'stone'),
        el((10.5, 3, 10), (13.5, 8, 14), 'stone'),                 # 尾根
        el((11, 8, 10.5), (13.5, 13, 13.5), 'stone'),              # 竖起的尾
        el((11.5, 13, 11), (13, 14.5, 12.5), 'face'),              # 尾尖
    ]
    tex = {f'{k}': f'{NS}:block/journey_fox_statue{("_" + k) if k != "stone" else ""}' for k in ('stone', 'face', 'bib', 'base')}
    tex['particle'] = f'{NS}:block/journey_fox_statue'
    model('journey_fox_statue', tex, els)
    write(A / 'blockstates/journey_fox_statue.json', {'variants': facing_states('journey_fox_statue')})
    item('journey_fox_statue')


# ==================== 手水钵 ====================
def stone_basin():
    """蹲踞：凿出的石钵盛水，钵沿横放一柄竹勺。"""
    t = canvas()
    fill_noise(t, STONE, 11)
    rnd = random.Random(12)
    for _ in range(18):  # 凿痕
        x, y = rnd.randrange(15), rnd.randrange(16)
        px(t, x, y, STONE[0])
        px(t, x + 1, y, STONE[1])
    for _ in range(10):
        px(t, rnd.randrange(16), rnd.randrange(8, 16), MOSS[rnd.randrange(3)])
    save(t, 'journey_stone_basin')
    w = canvas()
    water = [(40, 86, 130), (52, 104, 150), (66, 122, 168), (96, 150, 190)]
    fill_noise(w, water, 13, grain=0.3)
    for x, y in ((3, 4), (4, 4), (10, 9), (11, 9), (6, 12)):
        px(w, x, y, (170, 205, 225))
    save(w, 'journey_stone_basin_water')
    bam = bamboo_texture('journey_bamboo_spout')
    els = [
        el((2, 0, 2), (14, 8, 4), 'stone'),
        el((2, 0, 12), (14, 8, 14), 'stone'),
        el((2, 0, 4), (4, 8, 12), 'stone'),
        el((12, 0, 4), (14, 8, 12), 'stone'),
        el((4, 0, 4), (12, 5, 12), 'stone'),
        el((4, 6.5, 4), (12, 6.5, 12), 'water', skip=('north', 'south', 'west', 'east', 'down')),
        el((3, 8, 7.5), (13, 8.75, 8.25), 'bamboo'),               # 勺柄
        el((10.5, 8, 6.5), (12.5, 9.75, 9.25), 'bamboo'),          # 勺头
    ]
    model('journey_stone_basin', {'stone': f'{NS}:block/journey_stone_basin', 'water': f'{NS}:block/journey_stone_basin_water',
                                  'bamboo': f'{NS}:block/{bam}', 'particle': f'{NS}:block/journey_stone_basin'}, els)
    write(A / 'blockstates/journey_stone_basin.json', {'variants': {'': {'model': f'{NS}:block/journey_stone_basin'}}})
    item('journey_stone_basin')


def bamboo_texture(name):
    t = canvas()
    for y in range(16):
        for x in range(16):
            c = BAMBOO[2] if x % 4 in (1, 2) else BAMBOO[1]
            if x % 4 == 0:
                c = BAMBOO[0]
            px(t, x, y, c)
    for y in (4, 12):  # 竹节
        for x in range(16):
            px(t, x, y, BAMBOO[0] if x % 4 else (60, 92, 40))
            px(t, x, y + 1, BAMBOO[3] if x % 4 in (1, 2) else BAMBOO[2])
    save(t, name)
    e = canvas()
    fill_noise(e, [(170, 160, 110), (190, 180, 128), (206, 196, 146)], 21, grain=0.3)
    for x in range(16):
        for y in range(16):
            if x in (0, 15) or y in (0, 15):
                px(e, x, y, BAMBOO[0])
    for x in range(5, 11):
        for y in range(5, 11):
            px(e, x, y, (58, 70, 40))
    save(e, name + '_end')
    return name


# ==================== 竹出水口 ====================
def bamboo_spout():
    """筧：后部竹柱，斜削口的竹管向前伸出，水从管口落下（落水粒子在方块代码里）。"""
    bamboo_texture('journey_bamboo_spout')
    els = [
        el((6.5, 0, 11), (9.5, 12, 14), 'bamboo', faces={'up': 'end', 'down': 'end'}, uv={'up': [0, 0, 16, 16], 'down': [0, 0, 16, 16]}),
        el((7, 6, 1), (9, 8, 11), 'bamboo', faces={'north': 'end'}, uv={'north': [0, 0, 16, 16]}),
        el((7.5, 3, 11.5), (8.5, 6, 12.5), 'bamboo'),
        el((5.5, 0, 10), (10.5, 2, 15), 'stone'),
    ]
    model('journey_bamboo_spout', {'bamboo': f'{NS}:block/journey_bamboo_spout', 'end': f'{NS}:block/journey_bamboo_spout_end',
                                   'stone': f'{NS}:block/journey_stone_basin', 'particle': f'{NS}:block/journey_bamboo_spout'}, els)
    variants = {}
    for k, v in facing_states('journey_bamboo_spout').items():
        for wl in ('false', 'true'):
            variants[f'{k},waterlogged={wl}'] = v
    write(A / 'blockstates/journey_bamboo_spout.json', {'variants': variants})
    item('journey_bamboo_spout')


# ==================== 踏步石 ====================
def stepping_stone():
    """飞石：三种不规则的扁平石块，随机朝向，串成庭院小径。"""
    t = canvas()
    fill_noise(t, STONE, 31, bias=0.08)
    rnd = random.Random(32)
    for _ in range(10):
        px(t, rnd.randrange(16), rnd.randrange(16), STONE[0])
    for _ in range(6):
        px(t, rnd.randrange(16), rnd.randrange(16), (150, 160, 120))
    save(t, 'journey_stepping_stone')
    shapes = {
        'a': [((2, 0, 3), (13, 1.5, 12)), ((4, 0, 1), (11, 1.5, 3)), ((3, 0, 12), (10, 1.5, 15))],
        'b': [((1, 0, 4), (14, 1.5, 11)), ((3, 0, 2), (12, 1.5, 4)), ((5, 0, 11), (13, 1.5, 14))],
        'c': [((3, 0, 2), (12, 2, 13)), ((1, 0, 5), (3, 2, 10)), ((12, 0, 4), (15, 2, 12))],
    }
    for k, boxes in shapes.items():
        model(f'journey_stepping_stone_{k}', {'stone': f'{NS}:block/journey_stepping_stone', 'particle': f'{NS}:block/journey_stepping_stone'},
              [el(a, b, 'stone') for a, b in boxes])
    vs = [{'model': f'{NS}:block/journey_stepping_stone_{k}', 'y': y} for k in 'abc' for y in (0, 90, 180, 270)]
    write(A / 'blockstates/journey_stepping_stone.json', {'variants': {'waterlogged=false': vs, 'waterlogged=true': vs}})
    item('journey_stepping_stone', parent=f'{NS}:block/journey_stepping_stone_a')
    old = A / 'models/block/journey_stepping_stone.json'
    if old.exists():
        old.unlink()


# ==================== 小石龛 ====================
def shrine_hokora():
    """路边小祠：石台、木身、朱红格门、灰瓦人字顶，顶脊压一道深色木。"""
    w = canvas()
    fill_noise(w, WOOD, 41, grain=0.35)
    for x in range(16):
        if x % 5 == 0:
            for y in range(16):
                px(w, x, y, WOOD[0])
    save(w, 'journey_shrine_hokora')
    d = canvas()
    fill_noise(d, WOOD, 42, grain=0.3)
    for x in range(4, 12):
        for y in range(5, 14):
            px(d, x, y, RED_CLOTH[2] if (x in (4, 11) or y in (5, 13)) else (60, 30, 24) if (x + y) % 3 == 0 else RED_CLOTH[1])
    for x in (7, 8):
        for y in range(5, 14):
            px(d, x, y, RED_CLOTH[0])
    px(d, 7, 9, (214, 180, 80))
    px(d, 8, 9, (214, 180, 80))
    save(d, 'journey_shrine_hokora_door')
    els = [
        el((2, 0, 2), (14, 3, 14), 'stone'),
        el((4, 3, 4), (12, 10, 12), 'wood', faces={'north': 'door'}, uv={'north': [2, 3, 14, 13]}),
        el((3, 10, 3), (13, 11, 13), 'beam'),
        el((1.5, 11, 2), (8, 13, 14), 'roof', rotation={'origin': [8, 11, 8], 'axis': 'z', 'angle': 22.5}),
        el((8, 11, 2), (14.5, 13, 14), 'roof', rotation={'origin': [8, 11, 8], 'axis': 'z', 'angle': -22.5}),
        el((7, 13, 1.5), (9, 15, 14.5), 'beam'),
    ]
    model('journey_shrine_hokora', {'stone': f'{NS}:block/journey_fox_statue_base', 'wood': f'{NS}:block/journey_shrine_hokora',
                                    'door': f'{NS}:block/journey_shrine_hokora_door', 'beam': 'minecraft:block/stripped_dark_oak_log',
                                    'roof': f'{NS}:block/journey_kawara_roof', 'particle': f'{NS}:block/journey_shrine_hokora'}, els)
    # 激活的石龛（神社境内）：门里透出灯火
    lit = canvas()
    fill_noise(lit, WOOD, 42, grain=0.3)
    for x in range(4, 12):
        for y in range(5, 14):
            px(lit, x, y, RED_CLOTH[2] if (x in (4, 11) or y in (5, 13)) else (255, 214, 120) if (x + y) % 3 else (232, 150, 70))
    for x in (7, 8):
        for y in range(5, 14):
            px(lit, x, y, RED_CLOTH[0])
    save(lit, 'journey_shrine_hokora_door_lit')
    model('journey_shrine_hokora_lit', {'stone': f'{NS}:block/journey_fox_statue_base', 'wood': f'{NS}:block/journey_shrine_hokora',
                                        'door': f'{NS}:block/journey_shrine_hokora_door_lit', 'beam': 'minecraft:block/stripped_dark_oak_log',
                                        'roof': f'{NS}:block/journey_kawara_roof', 'particle': f'{NS}:block/journey_shrine_hokora'}, els)
    variants = facing_states('journey_shrine_hokora', extra=',active=false')
    variants.update(facing_states('journey_shrine_hokora', 'journey_shrine_hokora_lit', extra=',active=true'))
    write(A / 'blockstates/journey_shrine_hokora.json', {'variants': variants})
    item('journey_shrine_hokora')


# ==================== 暖帘 ====================
def noren():
    """暖帘：横杆下垂两幅布，中间开缝；下摆深一色，正中一枚圆形家纹跨在两幅上。白色款用藍纹。"""
    names = []
    for color, (ramp, crest) in NOREN.items():
        t = canvas()
        fill_noise(t, ramp, 51 + len(names), grain=0.18, bias=0.25)
        for x in range(16):
            px(t, x, 0, (78, 56, 38))            # 横杆
            px(t, x, 1, (96, 70, 48))
            px(t, x, 13, ramp[0])               # 下摆
        for y in range(2, 14):
            px(t, 7, y, (0, 0, 0, 0))           # 两幅之间的缝
            px(t, 8, y, (0, 0, 0, 0))
            px(t, 0, y, ramp[0])
            px(t, 15, y, ramp[0])
        for x in range(16):
            for y in range(14, 16):
                px(t, x, y, (0, 0, 0, 0))
        # 家纹：圆环，中心一点
        cx, cy = 7.5, 7
        for x in range(16):
            for y in range(2, 13):
                d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
                if 2.2 <= d <= 3.3 and x not in (7, 8):
                    px(t, x, y, crest)
        name = 'journey_noren' if color == 'indigo' else f'journey_noren_{color}'
        save(t, name)
        els = [
            el((0, 15, 7.25), (16, 16, 8.75), 'rod', uv={f: [0, 0, 16, 1] for f in ('north', 'south')}),
            el((0, 3, 8), (16, 15, 8), 'cloth', skip=('west', 'east', 'up', 'down'), uv={'north': [0, 2, 16, 14], 'south': [16, 2, 0, 14]}),
        ]
        els[1]['faces']['south']['uv'] = [16, 2, 0, 14]
        model(name, {'cloth': f'{NS}:block/{name}', 'rod': 'minecraft:block/stripped_dark_oak_log', 'particle': f'{NS}:block/{name}'}, els, cutout=True)
        write(A / f'blockstates/{name}.json', {'variants': facing_states(name)})
        item(name, generated_tex=f'{NS}:block/{name}')
        names.append(name)
    return names


# ==================== 注连绳 ====================
def shimenawa():
    """注连绳：麻与稻草搓成的粗绳横在上部，下垂两束稻穗与两条之字形纸垂。"""
    t = canvas()
    for y in range(16):
        for x in range(16):
            px(t, x, y, (0, 0, 0, 0))
    for y in range(0, 5):  # 绳：斜向搓纹
        for x in range(16):
            band = (x + y * 2) % 6
            px(t, x, y, STRAW[3] if band < 2 else STRAW[2] if band < 4 else STRAW[1])
        px(t, 0, y, STRAW[0])
    for x in range(16):
        px(t, x, 4, STRAW[0])
    # 纸垂：之字形白纸
    for base in (2, 11):
        path = [(0, 5), (0, 6), (1, 6), (1, 7), (1, 8), (2, 8), (2, 9), (2, 10), (1, 10), (1, 11), (1, 12), (2, 12), (2, 13)]
        for dx, y in path:
            px(t, base + dx, y, (244, 242, 236))
            px(t, base + dx + 1, y, (222, 218, 206))
    for x in (6, 9):  # 稻穗束
        for y in range(5, 10):
            px(t, x, y, STRAW[2] if y % 2 else STRAW[1])
    save(t, 'journey_shimenawa')
    els = [
        el((0, 11.5, 6.5), (16, 15.5, 9.5), 'rope', uv={'north': [0, 0, 16, 4], 'south': [0, 0, 16, 4], 'up': [0, 0, 16, 3], 'down': [0, 0, 16, 3],
                                                       'west': [0, 0, 3, 4], 'east': [0, 0, 3, 4]}),
        el((0, 2, 8), (16, 11.5, 8), 'rope', skip=('west', 'east', 'up', 'down'), uv={'north': [0, 5, 16, 14], 'south': [16, 5, 0, 14]}),
    ]
    els[1]['faces']['south']['uv'] = [16, 5, 0, 14]
    model('journey_shimenawa', {'rope': f'{NS}:block/journey_shimenawa', 'particle': f'{NS}:block/journey_shimenawa'}, els, cutout=True)
    write(A / 'blockstates/journey_shimenawa.json', {'variants': facing_states('journey_shimenawa')})
    item('journey_shimenawa', generated_tex=f'{NS}:block/journey_shimenawa')


# ==================== 温泉石 ====================
def hot_spring_stone():
    """温泉石：多孔的深色火山岩，孔隙边沉着硫黄与铁锈色。"""
    t = canvas()
    fill_noise(t, BASALT, 61)
    rnd = random.Random(62)
    for _ in range(11):
        x, y = rnd.randrange(16), rnd.randrange(16)
        px(t, x, y, (30, 28, 30))
        px(t, (x + 1) % 16, y, SULFUR[rnd.randrange(2)] if rnd.random() < 0.6 else (138, 84, 54))
    for _ in range(5):
        px(t, rnd.randrange(16), rnd.randrange(16), SULFUR[1])
    save(t, 'journey_hot_spring_stone')
    write(A / 'models/block/journey_hot_spring_stone.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'{NS}:block/journey_hot_spring_stone'}})
    write(A / 'blockstates/journey_hot_spring_stone.json', {'variants': {'': {'model': f'{NS}:block/journey_hot_spring_stone'}}})
    item('journey_hot_spring_stone')


# ==================== 蒸汽粒子 ====================
def steam_particles():
    """温泉蒸汽：四帧由聚到散的柔白团，边缘逐级透明。"""
    out = A / 'textures/particle'
    out.mkdir(parents=True, exist_ok=True)
    for i, (r, holes) in enumerate(((2.2, 0), (3.0, 2), (3.6, 5), (3.9, 9))):
        t = canvas(8, 8)
        rnd = random.Random(70 + i)
        for y in range(8):
            for x in range(8):
                d = ((x - 3.5) ** 2 + (y - 3.5) ** 2) ** 0.5
                if d <= r:
                    a = 200 if d < r - 1.4 else 130 if d < r - 0.6 else 70
                    px(t, x, y, (246, 248, 250, a))
        for _ in range(holes):
            x, y = rnd.randrange(8), rnd.randrange(8)
            px(t, x, y, (246, 248, 250, 40))
        t.save(out / f'hot_spring_steam_{i}.png')
    write(A / 'particles/hot_spring_steam.json', {'textures': [f'{NS}:hot_spring_steam_{i}' for i in range(4)]})


def lang(extra_noren):
    names = {'red': ('Crimson Noren', '茜色暖帘'), 'white': ('Unbleached Noren', '生成暖帘'), 'brown': ('Umber Noren', '焦茶暖帘')}
    for code, idx in (('en_us', 0), ('zh_cn', 1)):
        p = A / f'lang/{code}.json'
        data = json.loads(p.read_text(encoding='utf-8'))
        for color, pair in names.items():
            data[f'block.{NS}.journey_noren_{color}'] = pair[idx]
            data[f'item.{NS}.journey_noren_{color}'] = pair[idx]
        p.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def loot(names):
    for n in names:
        write(ROOT / f'src/main/resources/data/{NS}/loot_table/blocks/{n}.json', {
            'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{NS}:{n}'}],
                       'conditions': [{'condition': 'minecraft:survives_explosion'}]}],
            'random_sequence': f'{NS}:blocks/{n}',
        })


def main():
    fox_statue()
    stone_basin()
    bamboo_spout()
    stepping_stone()
    shrine_hokora()
    names = noren()
    shimenawa()
    hot_spring_stone()
    steam_particles()
    lang(names)
    loot([n for n in names if n != 'journey_noren'])
    print('props done')


if __name__ == '__main__':
    main()
