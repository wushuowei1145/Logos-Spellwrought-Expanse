"""世界树之弓（精灵公主的弓）的游戏资源：从仓库外的美术交付生成模型、贴图与物品定义。

用法（需要 pillow）：
    uv run --with pillow python tools/import_worldtree_bow.py "<Minecraft MOD 目录>/新角色完成稿/精灵公主武器/方块版"

交付目录里的 worldtree_cubes_26.1.2.zip 是原版物品模型的资源包：静止的弓 + 三段拉弓（各 452 块，前 318 块是弯过的弓身，
后 134 块是搭在弦上的世界树之箭），贴图 32×32。维护者定：箭不用这套方块网格，用原版箭的网格，贴图照世界树之箭的样子重画。

- 弓：静止模型原样搬过来；三段拉弓去掉搭在弦上的箭（箭由 `logos_dimension:worldtree_arrows` 物品模型画：拉满时中间的实箭、
  每锁定一个就多一支的虚影箭），并按交付里那支箭量出每段的搭箭点与箭头方向，写进物品定义。
- 箭：原版箭实体贴图的布局（32×32：上面两行是 16×5 的箭身正反面，下面 5×5 的箭尾十字），一张实箭、一张虚影。
- 锁定的界面：藤蔓边框（四角卷须加叶片，边上是细藤）和锁定标记（藤圈加三片叶），都是低透明度的代码绘制。
"""
import json
import math
import sys
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
NAME = 'worldtree_bow'
BOW_CUBES = 318
# 第一人称：原版拉弓动画（ItemInHandRenderer 的 BOW）是按原版弓贴图的朝向写的，交付的第一人称展示（立着的大弓）套上去，
# 箭会横着指向屏幕右边，静止时弓也离镜头太近（维护者试玩：拿弓的姿势不对）。静止和三段拉弓都改用由原版弓的第一人称展示换算的变换：
# 箭（模型 +X）对准原版贴图里箭的方向、弓身（+Y）对准原版的弓梢连线，握把放在原版握把的位置，弓的大小与原版相同（推导见 docs/WORLDTREE_BOW.md）。
FP_PULL = {'rotation': [-180, 90, -160], 'translation': [1.13, 2.448, -1.643], 'scale': [0.422, 0.422, 0.422]}
FP_PULL_LEFT = {'rotation': [-180, -90, 160], 'translation': [1.13, 2.448, -1.643], 'scale': [0.422, 0.422, 0.422]}
# 左键横着拉弓：拉弓模型的展示变换再绕箭轴（模型 X，过搭箭点）转 90°，弓横过来、箭的方向不变。
SIDE_ROLL = -90
HAND_CONTEXTS = ('thirdperson_righthand', 'thirdperson_lefthand', 'firstperson_righthand', 'firstperson_lefthand')

# 原版弓按使用刻数换拉弓模型（交付的 items 定义：0.05 × 刻数 ≥ 0.65、≥ 0.9 换第二、三段）
STAGES = [(0.65, 1), (0.9, 2)]

# 交付贴图里的色块（8×8 一块）：木、深绿缠绳、叶绿、金、青绿、浅绿、弦、描边绿
PALETTE = {
    'wood': (124, 80, 48), 'wood_dark': (84, 52, 34), 'wood_light': (150, 102, 62),
    'wrap': (58, 96, 58), 'leaf': (104, 168, 72), 'leaf_dark': (66, 128, 52), 'leaf_light': (160, 206, 104),
    'gold': (236, 196, 124), 'teal': (44, 152, 112), 'teal_light': (104, 202, 150), 'teal_dark': (26, 104, 80),
    'string': (214, 220, 188), 'outline': (72, 150, 82),
}


def read_zip(folder):
    pack = Path(folder) / 'worldtree_cubes_26.1.2.zip'
    with zipfile.ZipFile(pack) as z:
        models = {n.rsplit('/', 1)[1][:-5]: json.loads(z.read(n)) for n in z.namelist() if n.startswith('assets/royalbranch/models/item/')}
        texture = Image.open(z.open('assets/royalbranch/textures/item/worldtree.png')).convert('RGBA')
    return models, texture


def write(path, data, indent=1):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=indent) + '\n', encoding='utf-8', newline='\n')


def retexture(model, extra=None):
    out = {k: v for k, v in model.items() if k not in ('textures', 'elements')}
    out['textures'] = {'atlas': f'logos_dimension:item/{NAME}', 'particle': f'logos_dimension:item/{NAME}'}
    out['elements'] = model['elements'] if extra is None else model['elements'][:extra]
    return out


def euler_xyz(m):
    """旋转矩阵 → 原版展示用的 XYZ 欧拉角（矩阵 = Rx·Ry·Rz）。"""
    b = math.asin(max(-1.0, min(1.0, m[0][2])))
    if abs(m[0][2]) < .9999:
        a = math.atan2(-m[1][2], m[2][2])
        c = math.atan2(-m[0][1], m[0][0])
    else:
        a, c = math.atan2(m[2][1], m[1][1]), 0.0
    return [round(math.degrees(v), 3) for v in (a, b, c)]


def matmul(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def rot_xyz(deg):
    x, y, z = (math.radians(v) for v in deg)
    rx = [[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]]
    ry = [[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]]
    rz = [[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]]
    return matmul(matmul(rx, ry), rz)


def rolled(entry, roll, pivot):
    """展示变换 t + R·s·(m − 8) 前面先让模型绕过 pivot 的 X 轴转 roll 度：R' = R·Q，t' = t + R·s·(I − Q)(pivot − 8)。缩放各向相同。"""
    r = rot_xyz(entry.get('rotation', [0, 0, 0]))
    q = rot_xyz([roll, 0, 0])
    s = entry.get('scale', [1, 1, 1])[0]
    d = [pivot[i] - 8 for i in range(3)]
    qd = [sum(q[i][k] * d[k] for k in range(3)) for i in range(3)]
    diff = [d[i] - qd[i] for i in range(3)]
    t = entry.get('translation', [0, 0, 0])
    t2 = [round(t[i] + s * sum(r[i][k] * diff[k] for k in range(3)), 4) for i in range(3)]
    return dict(entry, rotation=euler_xyz(matmul(r, q)), translation=t2)


def center(e):
    return [(e['from'][i] + e['to'][i]) / 2 for i in range(3)]


def teal(e):
    """箭头的方块用交付贴图左下那块青绿（u 0–8、v 8–16）。"""
    faces = [f for f in e['faces'].values() if 'uv' in f]
    return bool(faces) and all(f['uv'][0] < 8 and f['uv'][2] <= 8 and 8 <= f['uv'][1] < 16 for f in faces)


def arrow_line(elements):
    """交付那支箭的搭箭点（箭尾端）与箭头（物品模型像素坐标）：沿主方向取两端，箭头一端是青绿的方块那头。"""
    pts = [center(e) for e in elements]
    mean = [sum(p[i] for p in pts) / len(pts) for i in range(3)]
    # 主方向：离得最远的两点
    a = max(pts, key=lambda p: sum((p[i] - mean[i]) ** 2 for i in range(3)))
    b = max(pts, key=lambda p: sum((p[i] - a[i]) ** 2 for i in range(3)))
    d = [b[i] - a[i] for i in range(3)]
    n = math.sqrt(sum(v * v for v in d))
    d = [v / n for v in d]
    along = [sum((c[i] - mean[i]) * d[i] for i in range(3)) for c in (center(e) for e in elements)]
    heads = [s for s, e in zip(along, elements) if teal(e)]
    if heads and sum(heads) / len(heads) < 0:
        d = [-v for v in d]
        along = [-s for s in along]
    # 两端取所有角点在主方向上的极值（不是方块中心）
    corners = []
    for e in elements:
        for x in (e['from'][0], e['to'][0]):
            for y in (e['from'][1], e['to'][1]):
                for z in (e['from'][2], e['to'][2]):
                    corners.append(sum(([x, y, z][i] - mean[i]) * d[i] for i in range(3)))
    lo, hi = min(corners), max(corners)
    nock = [mean[i] + d[i] * lo for i in range(3)]
    tip = [mean[i] + d[i] * hi for i in range(3)]
    return [round(v, 3) for v in nock], [round(v, 3) for v in tip]


# ---------------------------------------------------------------- 箭的贴图（原版箭的布局）

def arrow_texture(phantom):
    """原版 entity/projectiles/arrow.png 的布局：第 0–4 行左右各 16 格是箭身正反面（箭头在两侧外端、箭羽在中间），第 5–9 行是 5×5 的箭尾十字。"""
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    p = PALETTE

    def tone(c, a=255):
        if not phantom:
            return c + (a,)
        # 虚影：提亮、偏青白、七成透明度（客户端用半透明自发光画）
        r, g, b = c
        return (int(r * .35 + 170), int(g * .35 + 165), int(b * .35 + 150), 180)

    def side(x0, flip):
        def put(x, y, c):
            img.putpixel((x0 + (15 - x if flip else x), y), tone(c))
        # 箭身：竹木杆，中段一道深色缠绳，尾端金色箍
        for x in range(3, 12):
            put(x, 2, p['wood_light'] if x % 4 else p['wood'])
        for x in (7, 8):
            put(x, 2, p['wood_dark'])
        put(3, 2, p['gold'])
        put(4, 2, p['gold'])
        # 箭尾：两片叶羽（上下各一），叶脉亮、叶缘深
        for x, ys in ((0, (0, 4)), (1, (0, 1, 3, 4)), (2, (1, 3))):
            for y in ys:
                put(x, y, p['leaf_dark'] if y in (0, 4) else p['leaf'])
        put(0, 2, p['gold'])
        put(1, 2, p['leaf_light'])
        put(2, 2, p['leaf_light'])
        put(2, 0, p['leaf'])
        put(2, 4, p['leaf'])
        put(3, 1, p['leaf_light'])
        put(3, 3, p['leaf_light'])
        # 箭头：青绿的叶形晶尖
        put(12, 2, p['teal_dark'])
        for y in (1, 2, 3):
            put(13, y, p['teal'] if y != 2 else p['teal_light'])
        for y in (1, 2, 3):
            put(14, y, p['teal_light'] if y == 2 else p['teal_dark'])
        put(15, 2, p['teal_light'])
        put(13, 0, p['teal_dark'])
        put(13, 4, p['teal_dark'])
    # 与原版相同：两半的箭头都在外侧（左半第 0 列、右半第 31 列），箭羽在中间
    side(0, True)
    side(16, False)
    # 箭尾（两个 5×5）：与原版一样是“十”字，叶色、中心一点金
    for ox in (0, 5):
        for i in range(5):
            img.putpixel((ox + 2, 5 + i), tone(PALETTE['leaf']))
            img.putpixel((ox + i, 7), tone(PALETTE['leaf_dark']))
        img.putpixel((ox + 2, 7), tone(PALETTE['gold']))
    return img


# ---------------------------------------------------------------- 锁定界面（低透明度的藤蔓）

VINE = (96, 176, 92)
VINE_DARK = (46, 112, 58)
LEAF = (134, 214, 112)
BUD = (232, 244, 196)


def leaf(d, cx, cy, angle, length, width, color):
    """一片叶：沿 angle 方向的尖椭圆。"""
    pts = []
    for i in range(16):
        t = i / 15
        r = math.sin(math.pi * t) * width
        x = cx + math.cos(angle) * length * t - math.sin(angle) * r
        y = cy + math.sin(angle) * length * t + math.cos(angle) * r
        pts.append((x, y))
    for i in range(15, -1, -1):
        t = i / 15
        r = -math.sin(math.pi * t) * width
        x = cx + math.cos(angle) * length * t - math.sin(angle) * r
        y = cy + math.sin(angle) * length * t + math.cos(angle) * r
        pts.append((x, y))
    d.polygon(pts, fill=color)
    d.line([(cx, cy), (cx + math.cos(angle) * length * .8, cy + math.sin(angle) * length * .8)], fill=VINE_DARK + (255,), width=1)


def frame_texture():
    """边框贴图 64×64：左上角 32×32 是一个角（两根藤蔓在角上缠成卷须、带三片叶），(32,0) 起 32×8 是横边，(0,32) 起 8×32 是竖边。
    其他三个角由客户端镜像。透明度在客户端给（整体约四成）。"""
    s = 4
    big = Image.new('RGBA', (64 * s, 64 * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    # 角：横藤与竖藤从角里长出，角上一个卷须
    w = 2 * s
    d.line([(3 * s, 4 * s), (32 * s, 4 * s)], fill=VINE + (255,), width=w)
    d.line([(4 * s, 3 * s), (4 * s, 32 * s)], fill=VINE + (255,), width=w)
    spiral = []
    for i in range(40):
        a = i / 39 * math.pi * 1.7
        r = (6 - 4.5 * i / 39) * s
        spiral.append((10 * s + math.cos(a + math.pi) * r, 10 * s + math.sin(a + math.pi) * r))
    d.line(spiral, fill=VINE + (255,), width=int(1.5 * s))
    leaf(d, 14 * s, 4 * s, math.radians(25), 9 * s, 2.6 * s, LEAF + (255,))
    leaf(d, 4 * s, 15 * s, math.radians(65), 9 * s, 2.6 * s, LEAF + (255,))
    leaf(d, 22 * s, 4 * s, math.radians(-30), 7 * s, 2.0 * s, LEAF + (255,))
    leaf(d, 4 * s, 23 * s, math.radians(120), 7 * s, 2.0 * s, LEAF + (255,))
    d.ellipse([5 * s, 5 * s, 8 * s, 8 * s], fill=BUD + (255,))
    # 横边：细藤上下轻轻起伏，一片小叶
    for x0, y0, w0, h0, horizontal in ((32, 0, 32, 8, True), (0, 32, 8, 32, False)):
        pts = []
        for i in range(33):
            t = i / 32
            wave = math.sin(t * math.pi * 2) * .8
            if horizontal:
                pts.append(((x0 + t * w0) * s, (4 + wave) * s))
            else:
                pts.append(((4 + wave) * s, (y0 + t * h0) * s))
        d.line(pts, fill=VINE + (255,), width=int(1.5 * s))
        if horizontal:
            leaf(d, (x0 + 12) * s, 4 * s, math.radians(-35), 6 * s, 1.6 * s, LEAF + (255,))
        else:
            leaf(d, 4 * s, (y0 + 12) * s, math.radians(125), 6 * s, 1.6 * s, LEAF + (255,))
    return big.resize((64, 64), Image.LANCZOS)


def lock_texture():
    """锁定标记 32×32：左 24×24 是藤圈（一圈细藤、三片叶、一颗芽），右上 8×8 是叠加锁定时绕圈添的小叶。"""
    s = 4
    big = Image.new('RGBA', (32 * s, 32 * s), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    c, r = 12 * s, 8.5 * s
    arc = [(c + math.cos(a) * r, c + math.sin(a) * r) for a in [i / 63 * math.pi * 2 for i in range(64)]]
    d.line(arc + [arc[0]], fill=VINE + (255,), width=int(1.6 * s))
    for k in range(3):
        a = math.radians(-90 + k * 120)
        leaf(d, c + math.cos(a) * r, c + math.sin(a) * r, a + math.radians(30), 6 * s, 1.8 * s, LEAF + (255,))
    d.ellipse([c - 1.6 * s, c - 1.6 * s, c + 1.6 * s, c + 1.6 * s], fill=BUD + (255,))
    leaf(d, 25 * s, 6 * s, math.radians(-60), 6 * s, 1.8 * s, LEAF + (255,))
    return big.resize((32, 32), Image.LANCZOS)


def main():
    folder = sys.argv[1]
    models, texture = read_zip(folder)
    texture.save(ASSETS / f'textures/item/{NAME}.png')
    still = retexture(models['worldtree_bow'])
    still['display'] = dict(still['display'], firstperson_righthand=FP_PULL, firstperson_lefthand=FP_PULL_LEFT)
    write(ASSETS / f'models/item/{NAME}.json', still)
    stages = []
    for i in (1, 2, 3):
        m = models[f'worldtree_draw_{i}']
        assert len(m['elements']) == BOW_CUBES + 134, len(m['elements'])
        pull = retexture(m, BOW_CUBES)
        pull['display'] = dict(pull['display'], firstperson_righthand=FP_PULL, firstperson_lefthand=FP_PULL_LEFT)
        write(ASSETS / f'models/item/{NAME}_pulling_{i - 1}.json', pull)
        nock, tip = arrow_line(m['elements'][BOW_CUBES:])
        side = dict(pull, display=dict(pull['display'], **{k: rolled(pull['display'][k], SIDE_ROLL, nock) for k in HAND_CONTEXTS}))
        write(ASSETS / f'models/item/{NAME}_side_pulling_{i - 1}.json', side)
        stages.append({'nock': nock, 'tip': tip})
        print(f'第 {i} 段：搭箭点 {nock}，箭头 {tip}')
    bow = f'logos_dimension:item/{NAME}'
    pulling = {
        'type': 'minecraft:range_dispatch', 'property': 'minecraft:use_duration', 'scale': 0.05,
        'fallback': {'type': 'minecraft:model', 'model': f'{bow}_pulling_0'},
        'entries': [{'threshold': t, 'model': {'type': 'minecraft:model', 'model': f'{bow}_pulling_{k}'}} for t, k in STAGES],
    }
    side_pulling = dict(pulling, fallback={'type': 'minecraft:model', 'model': f'{bow}_side_pulling_0'},
                        entries=[{'threshold': t, 'model': {'type': 'minecraft:model', 'model': f'{bow}_side_pulling_{k}'}} for t, k in STAGES])
    thresholds = [t for t, _ in STAGES]
    write(ASSETS / f'items/{NAME}.json', {'model': {
        # 左键横着拉弓时换横过来的拉弓模型
        'type': 'minecraft:condition', 'property': 'logos_dimension:worldtree_sideways',
        'on_true': {'type': 'minecraft:composite', 'models': [
            side_pulling,
            {'type': 'logos_dimension:worldtree_arrows', 'base': f'{bow}_side_pulling_0', 'thresholds': thresholds, 'stages': stages},
        ]},
        'on_false': {'type': 'minecraft:composite', 'models': [
            {'type': 'minecraft:condition', 'property': 'minecraft:using_item',
             'on_false': {'type': 'minecraft:model', 'model': bow}, 'on_true': pulling},
            # 拉满后中间的实箭与锁定的虚影箭；阶段门槛与上面相同
            {'type': 'logos_dimension:worldtree_arrows', 'base': f'{bow}_pulling_0', 'thresholds': thresholds, 'stages': stages},
        ]}}})
    arrow_texture(False).save(ASSETS / 'textures/entity/projectiles/worldtree_arrow.png')
    arrow_texture(True).save(ASSETS / 'textures/entity/projectiles/worldtree_arrow_phantom.png')
    (ASSETS / 'textures/gui/worldtree').mkdir(parents=True, exist_ok=True)
    frame_texture().save(ASSETS / 'textures/gui/worldtree/frame.png')
    lock_texture().save(ASSETS / 'textures/gui/worldtree/lock.png')
    print('done')


if __name__ == '__main__':
    main()
