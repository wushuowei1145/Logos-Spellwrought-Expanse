"""10 命运之轮：圆而完整的金轮、八辐与稳定的菱形轮心；轮顶白猫爪旁横剑，左蛇右猫，两枚方向箭头；四角翼书。"""
from figures import *

# 赤猫的毛色：比皮肤深、比红衣暖，放在深靛底上要一眼跳出来
TERRA = ['#4a1a14', '#83301e', '#b84e2e', '#d6704a', '#ec9a72']

CX, CY = 55.5, 95.5   # 轮心落在半整数上，四向都严格对称
R_OUT, R_IN = 42, 35


def polar(a, r):
    """角度制：0° 朝右，90° 朝下。"""
    t = np.deg2rad(a)
    return CX + r * np.cos(t), CY + r * np.sin(t)


def diamond(c, x, y, r):
    """轮缘小菱：半整数中心也能用。暗外缘 + 发光本体 + 左上一道亮面。"""
    d = np.abs(XX - x) + np.abs(YY - y)
    m = d <= r
    c.paint(outer(m), TEAL[0])
    c.glow(m, TEAL[3])
    c.glow(m & (XX < x) & (YY < y), TEAL[4])
    c.glow(m & (XX > x) & (YY > y), TEAL[2])
    return m


def core(c, r):
    """轮心大菱：四个刻面分明，一眼读成稳定的宝石，而不是一个光斑。"""
    dx, dy = XX - CX, YY - CY
    m = np.abs(dx) + np.abs(dy) <= r
    c.paint(outer(m), TEAL[0])
    c.glow(m & (dx < 0) & (dy < 0), TEAL[4])
    c.glow(m & (dx > 0) & (dy < 0), TEAL[3])
    c.glow(m & (dx < 0) & (dy > 0), TEAL[3])
    c.glow(m & (dx > 0) & (dy > 0), TEAL[2])
    table = np.abs(dx) + np.abs(dy) <= r * 0.42
    c.glow(table, TEAL[3])
    c.glow(table & (dx < 0) & (dy < 0), TEAL[4])


def arrow(c, a0, a1, tip, r=48):
    """沿轮外缘的弧形箭头：顺时针走，从细到粗，末端一枚三角箭头。"""
    step = 1 if a1 > a0 else -1
    pts = [polar(a, r) for a in np.arange(a0, a1 + step, 4 * step)]
    c.paint(taper(pts, 1, 3), GOLD[3])
    t = polar(tip, r)
    b = polar(a1, r)
    p, q = polar(a1, r + 5), polar(a1, r - 5)
    c.paint(poly([p, t, q, b]), GOLD[3])
    c.paint(line([p, t]), GOLD[4])


def cloud(c, x, y, flip=False):
    """翼书下面压一片平底方云。"""
    parts = [(0, 6, 22, 9), (4, 3, 18, 6), (18, 8, 30, 11), (12, 9, 24, 12)]
    m = blank()
    for x0, y0, x1, y1 in parts:
        if flip:
            x0, x1 = 34 - x1, 34 - x0
        m |= rect(x + x0, y + y0, x + x1, y + y1)
    c.paint(m, IVORY[3])
    c.paint(m & ~shift(m, 0, -2), IVORY[2])
    c.paint(m & ~shift(m, 0, 1), IVORY[4])


def white_cat(c):
    """轮顶白猫：完整的猫，正面端坐，前爪落在剑身上。左半手写，右半镜像，中线落在 55.5。"""
    half = [
        '...o.......',
        '...oo......',
        '...opo.....',
        '...oppo....',
        '...opppo...',
        '...oppppo..',
        '...oppwwooo',
        '..owwwwwwww',
        '..owwwwwwww',
        '..owwwwwwww',
        '..owwooowww',
        '..owwgkgwww',
        '..owwgkgwww',
        '..owwwgwwww',
        '..owwwwwwwn',
        '...owwwmwwm',
        '...owwwwmmw',
        '....owwwwww',
        '.....ooWWWW',
        '.....owwwww',
        '.....owwwww',
        '....owwwwww',
        '....owwwwww',
        '....owwwwWw',
        '...owowwwwW',
        '..owwowwwwW',
        '.owwwowwwwW',
        '.owwwowwwoW',
        'owwwwwowwoW',
        'owwwwwowwoW',
        'owwwwwowwoW',
        'owWwwwowwoW',
        '.oWWwwowwoW',
        '..oooowwwwo',
        '.....owowow',
        '.....oooooo',
    ]
    rows = [r + r[::-1] for r in half]
    pal = {'o': WHITE[0], 'w': WHITE[3], 'W': WHITE[2], 'p': PINK[2], 'k': INK, 'g': GOLD_IRIS, 'n': PINK[1], 'm': WHITE[0]}
    x0, y0 = 45, 16
    c.sprite(x0, y0, rows, pal)
    # 迎光一侧提一道亮，背光一侧压一道影，猫才不是一张纸
    body = c.sprite_mask(x0, y0, rows)
    inner = shrink(body)
    c.paint(inner & ~shift(inner, 2, 0) & (YY > y0 + 7) & (XX < 50), WHITE[4])
    c.paint(inner & ~shift(inner, -1, 0) & (YY > y0 + 23) & (XX > 60), WHITE[2])
    # 胡须：三根，从脸颊向外散开
    for y, dy in [(y0 + 13, -1), (y0 + 15, 0), (y0 + 16, 2)]:
        c.paint(line([(42, y + dy), (47, y)]), WHITE[1])
        c.paint(line([(69, y + dy), (64, y)]), WHITE[1])


def red_cat(c):
    """攀轮赤猫：完整的猫，抬头向上爬，前爪、后爪都搭在轮缘上，尾巴垂在身后。整只猫共用一条外轮廓。"""
    tail_m = taper([(103, 112), (102, 120), (96, 125), (92, 129), (91, 133)], 5, 3)
    body = blob([(100, 87), (106, 87), (107, 93), (109, 100), (108, 107), (105, 112), (100, 113), (98, 106), (100, 100), (99, 94)])
    thigh = blob([(95, 107), (100, 103), (105, 105), (105, 111), (100, 115), (95, 113)])
    shank = taper([(98, 113), (94, 114), (90, 112)], 5, 3)
    arm = taper([(101, 92), (96, 86), (93, 82)], 5, 3)
    cat = tail_m | body | thigh | shank | arm
    c.paint(outer(cat), TERRA[0])
    c.paint(cat, TERRA[3])
    c.paint(cat & ~shift(cat, -2, -1), TERRA[2])
    c.paint(cat & ~shift(cat, 1, 1), TERRA[4])
    # 只在关节处补两条内线，读出肩和胯
    c.paint(curve([(99, 90), (103, 90), (106, 92)]), TERRA[1])
    c.paint(curve([(97, 104), (101, 103), (105, 106)]), TERRA[1])
    c.paint(line([(96, 112), (98, 109)]), TERRA[1])
    # 爪：比腿亮一点，脚趾两道缝
    for x, y in ((90, 80), (88, 110)):
        c.sprite(x, y, ['.oooo', 'oTTTo', 'oTTTo', '.oooo'], {'o': TERRA[0], 'T': TERRA[3]})
        c.px(x + 1, y + 1, TERRA[1])
        c.px(x + 1, y + 2, TERRA[1])
    # 头：四分之三侧，向左上看
    rows = [
        '...........o....',
        '..........oTo...',
        '.....oooooTTo...',
        '....oTTTTTTTo..o',
        '...oTTTTTTTToopo',
        '..oTToooTTTTpppo',
        '.oLTTkggTTTTppo.',
        'oLLTTTTTTTTTpo..',
        'onLLTTTTTTTTo...',
        '.oLLLTTTTTTso...',
        '..oooLLTTTso....',
        '.....oTTTso.....',
    ]
    c.sprite(96, 76, rows, {'o': TERRA[0], 'T': TERRA[3], 's': TERRA[2], 'L': TERRA[4], 'k': INK, 'g': GOLD_IRIS, 'p': PINK[2], 'n': PINK[0]})


def snake(c):
    """蓝蛇：头在轮外左上，身子一路 S 形垂到轮下，贴着轮缘擦过。深底上不需要外线。"""
    pts = [(21, 56), (15, 55), (10, 60), (9, 68), (14, 74), (16, 79), (10, 85), (4, 91), (4, 98), (8, 106), (10, 116), (8, 126), (10, 137)]
    m = taper(pts, 7, 1)
    c.paint(m, SKY[2])
    c.paint(m & ~shift(m, 1, 1), SKY[3])
    c.paint(m & ~shift(m, -1, -1), SKY[1])
    rows = [
        '...oooo.....',
        '..obbbboo...',
        '.obbkkbbboo.',
        'obbbgkbbbbbo',
        'obbbbbbbbbo.',
        '.obbbbbboo..',
        '..oooooo....',
    ]
    c.sprite(17, 52, rows, {'o': SKY[1], 'b': SKY[3], 'k': INK, 'g': GOLD_IRIS})
    # 分叉的舌头
    c.paint(line([(27, 57), (29, 59)]), RED[4])
    c.pxs([(30, 59), (29, 60), (30, 61)], RED[4])


def draw():
    c = Card('10_wheel_of_fortune')
    everything = rect(0, 0, W - 1, H - 1)

    # 深靛底：平涂。轮内再沉一档，金轮更跳
    c.paint(everything, NIGHT[1])

    # 四角的方云：先画，翼书压在上面
    cloud(c, 0, 19)
    cloud(c, 77, 19, flip=True)
    cloud(c, 0, 169)
    cloud(c, 77, 169, flip=True)

    # 轮缘、辐条。全部用半整数圆心和精确 45° 的带子，确保真的圆、真的匀
    rim = ring(CX, CY, R_OUT, R_OUT, R_OUT - R_IN)
    well = ellipse(CX, CY, R_IN)
    dx, dy = XX - CX, YY - CY
    spokes = (np.abs(dx) <= 1.5) | (np.abs(dy) <= 1.5) | (np.abs(dx - dy) <= 2.5) | (np.abs(dx + dy) <= 2.5)
    spokes &= well
    c.paint(well, NIGHT[0])
    c.volume(spokes, GOLD, 3, shadow=1, hi=0)
    c.volume(rim, GOLD, 3, shadow=1, hi=1)
    # 轮缘内外各压一道暗线，圆才收得住
    c.paint(edge(ellipse(CX, CY, R_OUT)) & (dx + dy > 0), GOLD[2])
    c.paint(outer(well) & (dx + dy < 0), GOLD[2])

    # 轮心：金盘托一枚大菱
    hub = (np.abs(dx) + np.abs(dy)) <= 11.5
    c.paint(outer(hub), GOLD[1])
    c.paint(hub, GOLD[3])
    c.paint(hub & (dx + dy > 0), GOLD[2])
    c.paint(hub & (dx + dy < -8), GOLD[4])
    core(c, 8.5)

    # 轮缘八菱：45° 一枚，对角也落在同一半径
    for a in range(0, 360, 45):
        x, y = polar(a, (R_OUT + R_IN) / 2 - 0.5)
        diamond(c, round(x * 2) / 2, round(y * 2) / 2, 3)

    # 方向箭头：都顺时针
    arrow(c, -62, -38, -28)
    arrow(c, 114, 134, 144)

    snake(c)
    red_cat(c)

    # 白猫的尾巴：从后腿旁绕起，尾尖回勾
    tl = taper([(64, 49), (69, 47), (72, 43), (71, 39), (72, 37), (75, 37)], 4, 3)
    c.paint(outer(tl), WHITE[0])
    c.paint(tl, WHITE[3])
    c.paint(tl & ~shift(tl, -1, -1), WHITE[2])

    # 横剑：剑柄朝左，剑尖朝右，贴在轮顶上
    blade = poly([(44, 49), (72, 49), (76, 51), (72, 52), (44, 52)])
    c.paint(blade, WHITE[3])
    c.paint(blade & (YY == 49), WHITE[4])
    c.paint(blade & (YY >= 51), WHITE[1])
    c.paint(rect(37, 50, 42, 52), LEATHER[2])
    c.paint(rect(37, 52, 42, 52), LEATHER[1])
    guard = poly([(42, 47), (43, 46), (44, 47), (44, 55), (43, 56), (42, 55)])
    c.paint(guard, GOLD[3])
    c.paint(rect(44, 47, 44, 55), GOLD[1])
    pommel = ellipse(35, 51, 2.5)
    c.paint(pommel, GOLD[3])
    c.paint(pommel & (XX + YY > 87), GOLD[2])
    c.px(34, 50, GOLD[5])

    white_cat(c)

    # 四角术式书：左上火、右上水、左下叶、右下晶
    winged_book(c, 19, 15, 'fire')
    winged_book(c, 92, 15, 'water')
    winged_book(c, 19, 165, 'leaf')
    winged_book(c, 92, 165, 'crystal')

    return c
