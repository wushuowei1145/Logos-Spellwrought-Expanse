"""人物与常用器物的像素零件。五官、头发和手都一格一格摆，不靠几何体碰运气。

尺寸档：L 是主角（整头宽约 19，脸宽 13），M 是配角（整头宽约 13），S 是远景小人（整头宽约 9）。
朝向：front 正面，3q 四分之三侧，profile 纯侧。精灵默认朝右，flip=True 朝左；vflip=True 上下倒置（倒吊人）。
"""
from __future__ import annotations

from pixelkit import *  # noqa: F403


# ---------------------------------------------------------------- 头部精灵
# 字符：0–4 是发色阶（0 最暗），l/s/d/x 是肤色四档，p/q 是内耳。
# 正面精灵只写左半，右半镜像后再压暗一档（光从左上来）。
def _mirror_rows(half):
    rows = []
    swap = {'2': '1', '3': '2', '4': '3', 'l': 's'}
    for r in half:
        right = r[-2::-1]
        shaded = ''.join(swap.get(ch, ch) if i >= len(right) - 5 else ch for i, ch in enumerate(right))
        rows.append(r + shaded)
    return rows


HEADS = {
    ('L', 'front'): {
        'rows': _mirror_rows([
            '.0........',
            '.00.......',
            '.0p0......',
            '.0pq0.0000',
            '.0pqq00222',
            '.0pqq22232',
            '..02223342',
            '..02222332',
            '.022222122',
            '.02221l122',
            '.0221ll1l1',
            '.021llllll',
            '.021llllll',
            '.021llllll',
            '.021llllll',
            '.01sllllll',
            '..01llllll',
            '..0sllllll',
            '....slllll',
            '.....sllll',
            '......slll',
            '.......xxx',
        ]),
        'eyes': [(5, 12, 3), (11, 12, 3)], 'nose': (9, 16), 'mouth': (9, 18), 'chin': (9, 21), 'lock': [(2, 11), (16, 11)],
    },
    ('L', '3q'): {
        'rows': [
            '.....0..............',
            '.....00..........0..',
            '.....0p0.........00.',
            '.....0pq0.000000.0q0',
            '....0pqq0022222200q0',
            '...00pqq222223222q0.',
            '..02220222233422220.',
            '.022222222222222210.',
            '.022222222212222110.',
            '02222222212l21l2l10.',
            '0222222221ll1ll1ll0.',
            '022222221lllllllllls',
            '02222221sllllllllll.',
            '0222221slllllllllll.',
            '.022221sllllllllll..',
            '.0221..dslllllllll..',
            '..021...dsllllllll..',
            '..01.....dslllllll..',
            '...0......dsllllll..',
            '...........dslllll..',
            '............ddllx...',
            '..............xxx...',
        ],
        'eyes': [(10, 12, 3), (15, 12, 2)], 'nose': (16, 15), 'mouth': (15, 17), 'chin': (15, 21), 'lock': [(3, 13)],
    },
    ('L', 'profile'): {
        'rows': [
            '.....0.............',
            '.....00............',
            '.....0p0...........',
            '.....0pq0.000000...',
            '....0pqq003333330..',
            '...00pqq2234443320.',
            '..0222022233333320.',
            '.02222222222222210.',
            '.02222222221222110.',
            '02222222212l21l10..',
            '0222222221ll1lll0..',
            '022222221llllllll..',
            '02222221sllllllll..',
            '0222221sllllllll...',
            '.022221slllllllll..',
            '.0221..dsllllllllll',
            '..021...dslllllll..',
            '..01.....dslllll...',
            '...0......dsllll...',
            '...........dsll....',
            '............ddx....',
        ],
        'eyes': [(12, 12, 3)], 'nose': None, 'mouth': (15, 17), 'chin': (14, 20), 'lock': [(3, 13)],
    },
    ('M', 'front'): {
        'rows': _mirror_rows([
            '.0.....',
            '.00.000',
            '0pq0222',
            '0p22232',
            '.022342',
            '.022222',
            '.0212l2',
            '.01llll',
            '.01llll',
            '.01llll',
            '..0llll',
            '...slll',
            '....xss',
        ]),
        'eyes': [(3, 8, 2), (8, 8, 2)], 'nose': None, 'mouth': (6, 10), 'chin': (6, 12), 'lock': [(1, 7), (11, 7)],
    },
    ('M', '3q'): {
        'rows': [
            '....0.........',
            '...00.000..0..',
            '...0p0222200q0',
            '.002p2222322q0',
            '02222223342220',
            '022222212l2l10',
            '022221lllllll.',
            '02221sllllll..',
            '.021.sllllll..',
            '.01..dslllll..',
            '..0...dslll...',
            '.......dxx....',
        ],
        'eyes': [(7, 7, 2), (10, 7, 2)], 'nose': None, 'mouth': (9, 10), 'chin': (8, 11), 'lock': [(1, 8)],
    },
    ('S', 'front'): {
        'rows': _mirror_rows([
            '0....',
            '0p.00',
            '0p033',
            '.0222',
            '.0l2l',
            '.0lll',
            '..sll',
            '...xs',
        ]),
        'eyes': [(2, 5, 1), (6, 5, 1)], 'nose': None, 'mouth': None, 'chin': (4, 7), 'lock': [],
    },
    ('S', '3q'): {
        'rows': [
            '..0......',
            '..0p.00.0',
            '.00p0332q',
            '022223320',
            '02221l2l.',
            '0221sllll',
            '.01.dslll',
            '.....dsx.',
        ],
        'eyes': [(5, 5, 1), (7, 5, 1)], 'nose': None, 'mouth': None, 'chin': (6, 7), 'lock': [],
    },
}

EYE_SPRITES = {
    # k 眼线 e 虹膜 h 高光 p 瞳孔；3 宽给主角，2 宽给配角或主角的远眼，1 宽只是一个点。
    ('open', 3): ['kkk', 'hep', 'eep'],
    ('open', 2): ['kk', 'he'],
    ('open', 1): ['k'],
    ('up', 3): ['kkk', 'hpe', 'eep'],
    ('up', 2): ['kk', 'hp'],
    ('up', 1): ['k'],
    ('down', 3): ['...', 'kkk', 'ehp'],
    ('down', 2): ['..', 'kk'],
    ('down', 1): ['k'],
    ('closed', 3): ['...', 'k.k', '.k.'],
    ('closed', 2): ['..', 'kk'],
    ('closed', 1): ['k'],
    ('happy', 3): ['.k.', 'k.k', '...'],
    ('happy', 2): ['kk', '..'],
    ('happy', 1): ['k'],
}
MOUTH_SPRITES = {
    'smile': ['m..m', '.mm.'],
    'soft': ['mm'],
    'line': ['nn'],
    'open': ['mmm', '.r.'],
    'grin': ['mmm', '.r.'],
    'none': [],
}


def head(c, cx, top, view='front', size='L', hair=HAIR_BLACK, iris=BLUE_IRIS, eyes='open', mouth='soft', flip=False, vflip=False, skin=SKIN,
         inner=PINK, blush=False, brow=None, lash=None, ears=True):
    """贴一整颗头：头发冠、猫耳、脸和五官都在里面。长发的后片在身体之前由调用方自己画。

    cx 是下巴所在的横坐标，top 是精灵上沿（耳尖）。view 取 front/3q/profile。
    眼睛 eyes 取 open/up/down/closed/happy；嘴 mouth 取 smile/soft/line/open/grin/none。
    ears=False 只把内耳改成发色，耳朵的轮廓还在；要彻底去掉耳朵，得在调用后自己盖掉。
    返回 dict：chin 下巴点，x0/x1 精灵左右界，top/bottom 上下界，lock 鬓角点（长发的前绺从这里接出去）。
    """
    spec = HEADS[(size, view)]
    rows = spec['rows']
    width = max(len(r) for r in rows)
    height = len(rows)
    chin_x, chin_y = spec['chin']
    x = cx - chin_x if not flip else cx - (width - 1 - chin_x)
    pal = {'0': hair[0], '1': hair[1], '2': hair[2], '3': hair[3], '4': hair[4], 'l': skin[3], 's': skin[2], 'd': skin[1], 'x': skin[0], 'p': inner[1], 'q': inner[2]}
    if not ears:
        rows = [''.join('2' if ch in 'pq' else ch for ch in r) for r in rows]
    c.sprite(x, top, rows, pal, flip=flip, vflip=vflip)

    def place(px, py, w, h):
        xx = x + (width - px - w if flip else px)
        yy = top + (height - py - h if vflip else py)
        return xx, yy

    lash = lash or hair[0]
    eye_pal = {'k': lash, 'e': iris, 'h': '#ffffff', 'p': dim(iris, 0.42)}
    for ex, ey, ew in spec['eyes']:
        spr = EYE_SPRITES[(eyes, ew)]
        xx, yy = place(ex, ey, ew, len(spr))
        c.sprite(xx, yy, spr, eye_pal, vflip=vflip)
        if brow is not None and ew >= 2:
            bx, by = place(ex, ey - 2, ew, 1)
            for i in range(ew):
                c.px(bx + i, by, brow)
    if spec.get('nose'):
        xx, yy = place(spec['nose'][0], spec['nose'][1], 1, 1)
        c.px(xx, yy, skin[1])
    if spec.get('mouth') and MOUTH_SPRITES.get(mouth):
        spr = MOUTH_SPRITES[mouth]
        mw = max(len(r) for r in spr)
        xx, yy = place(spec['mouth'][0] - mw // 2, spec['mouth'][1], mw, len(spr))
        c.sprite(xx, yy, spr, {'m': '#7a3540', 'n': skin[0], 'r': '#c8605c'}, vflip=vflip)
    if blush:
        for ex, ey, ew in spec['eyes']:
            xx, yy = place(ex, ey + 3, ew, 1)
            for i in range(ew):
                c.px(xx + i, yy, PINK[2])
    chin = place(chin_x, chin_y, 1, 1)
    locks = [place(lx, ly, 1, 1) for lx, ly in spec.get('lock', [])]
    return {'chin': chin, 'x0': x, 'x1': x + width - 1, 'top': top, 'bottom': top + height - 1, 'lock': locks}


def ear(c, x, y, h=7, w=None, lean=0, ramp=HAIR_BLACK, inner=PINK):
    """单独加一只猫耳（完整猫形态、大特写用）：(x, y) 是耳根中心，耳尖在 (x+lean, y-h)。"""
    w = w or max(h - 1, 3)
    half = w / 2
    outer_tri = poly([(x - half, y), (x + half, y), (x + lean, y - h)])
    c.paint(outer_tri, ramp[2])
    c.paint(edge(outer_tri), ramp[0])
    if h >= 5:
        inner_tri = poly([(x - half + 2, y), (x + half - 2, y), (x + lean * 0.8, y - h + 3)])
        c.paint(inner_tri & shrink(outer_tri), inner[1])
        c.paint(inner_tri & shrink(outer_tri) & (XX <= x + lean * 0.5), inner[2])
    return outer_tri


def hair_paint(c, mask, ramp, base=2, ring_y=None, line_edge=True, light=(-1, -1)):
    """长发后片的上色：硬边体积 + 可选的一条高光环。ring_y 给高光环所在的行。"""
    c.volume(mask, ramp, base, light=light, shadow=2, hi=1)
    if ring_y is not None:
        band = mask & rect(0, ring_y, W, ring_y + 1) & shrink(mask)
        band &= (BAYER < 0.75)
        c.paint(band, ramp[min(base + 2, len(ramp) - 1)])
    if line_edge:
        c.paint(edge(mask), ramp[0])


def hair_lock(c, pts, ramp, w0=3, w1=1, base=2):
    """一绺头发：从粗到细，自带暗边。发绺末端要细，才不像一根棍子。"""
    m = taper(pts, w0, w1)
    c.volume(m, ramp, base, shadow=1, hi=0)
    c.paint(edge(m), ramp[0])
    return m


def tail(c, pts, ramp=HAIR_BLACK, w0=4, w1=2, base=2):
    """猫尾：一条渐细的软管，尾尖略圆。"""
    m = taper(pts, w0, w1)
    c.volume(m, ramp, base, shadow=1, hi=1)
    c.paint(edge(m), ramp[0])
    return m


# ---------------------------------------------------------------- 手
# 字符：l 亮 s 肤 d 暗 x 线。默认手腕在下，手指朝上；flip、vflip 翻转。
HANDS = {
    # 张开的手掌（太阳女孩、审判起身者）
    'open': [
        '.l.l.l..',
        '.l.l.l..',
        'xl.l.l.l',
        'xl.l.l.l',
        'xlllllls',
        '.lllllls',
        '.lllllsd',
        '..lllsd.',
    ],
    # 食指指天或指地（魔术师；vflip 即朝下）
    'point': [
        '..l..',
        '..l..',
        '..l..',
        '..l..',
        '.lls.',
        'lllls',
        'lllls',
        'dllsd',
        '.lsd.',
    ],
    # 双指祝福（教皇）
    'bless': [
        '..l.l.',
        '..l.l.',
        '..l.l.',
        '.lllls',
        '.lllls',
        'xlllls',
        'xllssd',
        '.lssd.',
    ],
    # 握竖直杆：杆从手后穿过，四指横在杆前
    'grip': [
        '.lll.',
        'lllls',
        'xxxxs',
        'lllls',
        'xxxxs',
        'lllls',
        '.dsd.',
    ],
    # 握拳/托物
    'fist': [
        '.lll.',
        'lllls',
        'lllls',
        'lllsd',
        '.ssd.',
    ],
    # 手心向上托（恋人、女祭司）
    'cup': [
        'l.......',
        'll...ll.',
        'llllllls',
        '.llllssd',
        '..sssd..',
    ],
    # 小号手：配角用
    'open_s': [
        'l.l.l',
        'l.l.l',
        'lllls',
        '.llsd',
        '.lsd.',
    ],
    'mitt': [
        '.ll.',
        'llls',
        'llls',
        '.sd.',
    ],
    'dot': [
        'll',
        'ls',
    ],
}


def hand(c, x, y, kind='fist', skin=SKIN, flip=False, vflip=False):
    """贴一只手，x、y 是精灵左上角。"""
    c.sprite(x, y, HANDS[kind], {'l': skin[3], 's': skin[2], 'd': skin[1], 'x': skin[0]}, flip=flip, vflip=vflip)


# ---------------------------------------------------------------- 全套反复出现的符号
FLOWERS = {
    4: ['....www....', '...wWWWw...', '.www.W.www.', 'wWWWwYwWWWw', '.wWWYOYWWw.', '..wwwYwww..', '..wWWwWWw..', '.wWWw.wWWw.', '.wWw...wWw.', '..w.....w..'],
    3: ['...W...', '..WWW..', 'WWWYWWW', '.WYOYW.', '..WYW..', '.WW.WW.', '.W...W.'],
    2: ['..W..', 'WWYWW', '.WOW.', '.W.W.'],
}


def white_flower(c, x, y, r=3, ramp=WHITE, center=GOLD[3], outline=None):
    """五瓣白花，(x, y) 是花心。r 取 2、3、4；浅色背景上传 outline 描一圈暗线。"""
    rows = FLOWERS[r]
    width = len(rows[0])
    x0, y0 = x - width // 2, y - {4: 4, 3: 3, 2: 2}[r]
    if outline:
        c.paint(outer(c.sprite_mask(x0, y0, rows)), outline)
    c.sprite(x0, y0, rows, {'w': ramp[2], 'W': ramp[4], 'Y': center, 'O': GOLD[4]})


def rose(c, x, y, r=2, ramp=RED):
    m = ellipse(x, y, r)
    c.paint(outer(m), ramp[0])
    c.paint(m, ramp[3])
    c.pxs([(x, y), (x - 1, y - 1)], ramp[4])
    c.pxs([(x + 1, y), (x, y + 1)], ramp[2])


def gem(c, x, y, r=3):
    """胸前的金边蓝青菱扣：Logos 视觉语言在人物身上的落点。"""
    m = (np.abs(XX - x) + np.abs(YY - y)) <= r
    c.paint(outer(m), GOLD[1])
    c.paint(m, GOLD[3])
    c.node(x, y, max(r - 1, 1))
    c.paint(outer((np.abs(XX - x) + np.abs(YY - y)) <= max(r - 1, 1)) & m, GOLD[3])


ELEMENT_ICONS = {
    # 书页上的四元素符号，左上火、右上水、左下叶、右下晶（命运之轮与世界共用）
    'fire': (['....r....', '...rr....', '...rrr.r.', '..rrrrrr.', '.rrrorrr.', '.rroooorr', 'rrooyoorr', 'rrooyyorr', '.rroyyor.', '..rrrrr..'],
             {'r': '#c8452a', 'o': '#ef8a2c', 'y': '#f9d55e'}),
    'water': (['....b....', '....b....', '...bbb...', '...bbb...', '..bbbbb..', '.bbcbbbb.', '.bcbbbbb.', '.bcbbbbb.', '..bbbbb..', '...bbb...'],
              {'b': '#3f8fc0', 'c': '#8fd5e5'}),
    'leaf': (['......ggg', '....ggggg', '...gggglg', '..gggglgg', '.ggglgggg', '.gglggggg', '.glgggg..', '.lggg....', 'l........', 'l........'],
             {'g': '#4d8a3e', 'l': '#8cc266'}),
    'crystal': (['....T....', '...tTT...', '..ttTTT..', '..ttTTT..', '.t.tTT.T.', 'tt.tTT.TT', 'tt.tTT.TT', 'tt.tTT.TT', '.t.tTT.T.', '...tTT...'],
                {'t': '#35b7c0', 'T': '#9df2ee'}),
}


def winged_book(c, x, y, element, glow_icon=True):
    """四角术式书：打开的书页 + 一对小翼 + 一枚元素符号。(x, y) 是书的中心，总宽约 37、总高约 21。"""
    for s in (-1, 1):
        wing = poly([(x + s * 9, y - 4), (x + s * 14, y - 7), (x + s * 18, y - 12), (x + s * 18, y - 7), (x + s * 16, y - 5),
                     (x + s * 17, y - 3), (x + s * 14, y - 2), (x + s * 15, y), (x + s * 12, y + 1), (x + s * 9, y + 3)])
        c.paint(outer(wing), WHITE[0])
        c.paint(wing, WHITE[3])
        c.paint(wing & (YY > y - 2), WHITE[2])
        c.paint(line([(x + s * 11, y - 5), (x + s * 15, y - 8)]), WHITE[1])
        c.paint(line([(x + s * 11, y - 2), (x + s * 14, y - 4)]), WHITE[1])
    book = poly([(x - 11, y - 6), (x - 6, y - 7), (x, y - 5), (x + 6, y - 7), (x + 11, y - 6), (x + 11, y + 6), (x + 6, y + 5), (x, y + 7), (x - 6, y + 5), (x - 11, y + 6)])
    c.paint(outer(book), GOLD[1])
    c.paint(book, IVORY[4])
    c.paint(book & ((XX < x - 8) | (XX > x + 8)), IVORY[3])
    c.paint(book & (XX == x), IVORY[1])
    c.paint(line([(x - 11, y + 7), (x - 6, y + 6), (x, y + 8), (x + 6, y + 6), (x + 11, y + 7)]), GOLD[2])
    spr, pal = ELEMENT_ICONS[element]
    glow = ''.join(pal.keys()) if glow_icon else ''
    c.sprite(x - 4, y - 5, spr, pal, glow=glow)
