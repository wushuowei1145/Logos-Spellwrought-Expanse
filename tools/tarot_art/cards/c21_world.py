"""21 世界：闭合月桂环上下各系一枚红色无限结，环内蓝青术式环连接四枚菱印；银发猫耳女子持双短杖起舞，一腿轻交于另一腿；四角翼书左上火、右上水、左下叶、右下晶。"""
from figures import *

CX = 55.5
RING_Y, RING_RX, RING_RY = 92, 41, 66      # 术式环
WREATH_RX, WREATH_RY = 48, 76              # 月桂茎线

SKIN_PAL = {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0]}

# 握短杖的手：四指横裹杖身，只留两道指缝
HAND_ROD = [
    '.lll.',
    'lllls',
    'dddls',
    'lllls',
    'dddls',
    'lllsd',
    '.ss..',
]
EAR_NEAR = [
    '.0.......',
    '.00......',
    '.030.....',
    '.0p30....',
    '0ppq30...',
    '0ppqq30..',
    '0ppqqq30.',
    '0ppqqq330',
]
EAR_FAR = [
    '.....0',
    '....00',
    '...0q0',
    '..0qp0',
    '.0qqp0',
    '022qp0',
]


def leaf(x, y, deg, length, width):
    """一片尖叶的蒙版和亮面：从叶柄 (x, y) 朝 deg 方向伸出。亮面取朝左上的半边。"""
    a = np.radians(deg)
    ux, uy = np.cos(a), np.sin(a)
    nx, ny = -uy, ux
    profile = [(0.0, 0.0), (0.25, 0.8), (0.5, 1.0), (0.75, 0.7), (1.0, 0.0)]
    left = [(x + ux * length * t + nx * width / 2 * k, y + uy * length * t + ny * width / 2 * k) for t, k in profile]
    right = [(x + ux * length * t - nx * width / 2 * k, y + uy * length * t - ny * width / 2 * k) for t, k in reversed(profile[1:-1])]
    m = poly(left + right)
    side = (XX - x) * nx + (YY - y) * ny
    lit_sign = -1 if (nx + ny) > 0 else 1
    return m, m & (side * lit_sign >= 0)


def wreath_point(theta):
    """茎线上的点。theta 从底部中心 0 度沿左侧到顶部 180 度。返回坐标和前进方向（度）。"""
    t = np.radians(theta)
    x = CX - WREATH_RX * np.sin(t)
    y = RING_Y + WREATH_RY * np.cos(t)
    dx, dy = -WREATH_RX * np.cos(t), -WREATH_RY * np.sin(t)
    return x, y, np.degrees(np.arctan2(dy, dx))


def draw_wreath(c):
    """月桂环：一条闭合的茎，左右两侧各一串成对的叶，叶尖都朝顶部的红结。内侧叶压暗，退到外侧叶后面。"""
    c.paint(ring(CX, RING_Y, WREATH_RX, WREATH_RY), SAGE[2])
    for theta in [18, 31, 43, 54, 64, 73.5, 82.5, 91.5, 100.5, 110, 120, 131, 143, 156]:
        x, y, heading = wreath_point(theta)
        for side in (-1, 1):
            # side=-1 是左半环；右半环用镜像的角度和坐标
            px = x if side < 0 else 2 * CX - x
            hd = heading if side < 0 else 180 - heading
            spread = 30 if side < 0 else -30
            in_m, in_lit = leaf(px, y, hd + spread * 0.9, 10, 5)
            c.paint(outer(in_m), NIGHT[0])
            c.paint(in_m, LEAF[1])
            c.paint(in_lit, LEAF[2])
            out_m, out_lit = leaf(px, y, hd - spread, 15, 7)
            c.paint(outer(out_m), NIGHT[0])
            c.paint(out_m, SAGE[2])
            c.paint(out_lit, SAGE[3])


def draw_knot(c, cy):
    """红色无限结：粗带平涂，右下一线暗，外圈深红描边，两个环孔露出深蓝底。"""
    m = c.infinity(CX, cy, 17, 6, 3)
    c.paint(ellipse(CX, cy, 17, 6), NIGHT[1])
    c.paint(outer(m), RED[0])
    c.paint(m, RED[3])
    c.paint(m & ~shift(m, -1, -1), RED[2])


def even_node(c, cy, r=3):
    """落在中线 55.5 上的菱印：顶尖两像素宽，严格对称。"""
    d = np.abs(XX - CX) + np.abs(YY - cy)
    m = d <= r + 0.5
    c.paint(outer(m), TEAL[0])
    c.glow(m, TEAL[2])
    c.glow(d <= r - 0.5, TEAL[3])
    c.glow(d <= 0.5, TEAL[4])


def wand(c, a, b):
    """短杖：两像素宽的金杆，上端三格杖尖发光。两根同粗同长。"""
    rod = line([a, b])
    c.paint(outer(rod), GOLD[1])
    c.paint(rod, GOLD[3])
    c.paint(line([a, b]), GOLD[4])
    c.glow(rod & rect(b[0] - 3, b[1] - 2, b[0] + 3, b[1] + 2), GOLD[5])


def draw():
    c = Card('21_world')

    # 深蓝空面；术式环内略提亮一档
    c.paint(rect(0, 0, W - 1, H - 1), NIGHT[1])
    inside = ellipse(CX, RING_Y, RING_RX, RING_RY)
    c.paint(inside, NIGHT[2])

    draw_wreath(c)
    draw_knot(c, 16)
    draw_knot(c, 168)

    # 术式环：一像素发光线，四枚菱印
    circuit = ring(CX, RING_Y, RING_RX, RING_RY)
    c.aura(circuit, NIGHT[3], 1, 0.4, clip=inside)
    c.glow(circuit, TEAL[3])
    even_node(c, RING_Y - RING_RY)
    even_node(c, RING_Y + RING_RY)
    c.node(15, RING_Y, 3)
    c.node(96, RING_Y, 3)
    c.glow(circuit & (c.gl[..., 3] != 255), TEAL[3])

    # 四角翼书：和命运之轮同一个函数、同一方位
    winged_book(c, 18, 12, 'fire')
    winged_book(c, 93, 12, 'water')
    winged_book(c, 18, 180, 'leaf')
    winged_book(c, 93, 180, 'crystal')

    # 猫尾：从裙后绕到右侧
    tail(c, [(64, 103), (75, 105), (83, 100), (86, 92), (81, 86)], HAIR_SILVER, 5, 4, base=3)

    # 长发：右侧贴着脸垂下，到肩外翻卷；左侧三大绺向左下甩开，发尾收尖
    hair_mass = blob([(62, 42), (65, 44), (66, 48), (66, 52), (69, 55), (72, 58), (73, 62), (71, 65), (68, 63), (65, 60), (58, 58), (52, 60), (46, 60), (44, 52), (47, 44), (55, 42)])
    hair_mass |= taper([(46, 53), (40, 54), (36, 56), (34, 60)], 7, 1)
    hair_mass |= taper([(48, 64), (38, 68), (28, 70), (23, 75), (23, 82)], 10, 1)
    hair_mass |= taper([(48, 66), (40, 72), (33, 77), (31, 83), (35, 88)], 10, 1)
    hair_mass |= taper([(49, 68), (44, 75), (42, 81), (46, 86)], 9, 1)
    c.volume(hair_mass, HAIR_SILVER, 2, shadow=1, hi=1)
    c.paint(edge(hair_mass), HAIR_SILVER[1])
    c.fold([(44, 67), (34, 71), (27, 74)], HAIR_SILVER[1], hair_mass)
    c.fold([(44, 72), (37, 78), (35, 83)], HAIR_SILVER[1], hair_mass)
    c.fold([(68, 56), (70, 60), (70, 63)], HAIR_SILVER[1], hair_mass)

    c.fold([(65, 46), (65, 51)], HAIR_SILVER[3], hair_mass)
    c.fold([(46, 54), (42, 55)], HAIR_SILVER[3], hair_mass)
    c.fold([(42, 66), (34, 69)], HAIR_SILVER[3], hair_mass)

    # 红腰带的两条长飘带：先画，裙摆压住它们的尾端
    ribbon_l = taper([(54, 80), (44, 84), (33, 88), (25, 93), (21, 99), (23, 104), (27, 108)], 4, 7)
    ribbon_r = taper([(65, 80), (68, 90), (73, 100), (80, 107), (83, 113), (79, 118), (72, 122)], 3, 6)
    for rb in (ribbon_l, ribbon_r):
        c.paint(rb, RED[3])
        c.paint(rb & ~shift(rb, -1, -1), RED[2])
        c.paint(edge(rb), RED[1])

    # 裙内暗面：两道深褶把下摆内侧分成几幅
    under = blob([(24, 111), (25, 116), (29, 120), (33, 123), (38, 124), (43, 127), (48, 128), (58, 130), (63, 128), (58, 116), (40, 108)])
    c.paint(under, IVORY[1])
    c.paint(under & ~shift(under, 0, -1), IVORY[0])
    c.fold([(32, 113), (34, 122)], IVORY[0], under)
    c.fold([(42, 116), (44, 126)], IVORY[0], under)
    # 站立腿：小腿收细，踮脚，脚尖两道趾缝
    leg_stand = poly([(51, 120), (58, 120), (58, 128), (57, 134), (57, 140), (56, 147), (53, 147), (52, 140), (52, 134), (51, 128)])
    foot_stand = poly([(53, 146), (56, 146), (57, 148), (59, 150), (62, 151), (62, 152), (56, 152), (53, 150), (52, 148)])
    c.volume(leg_stand | foot_stand, SKIN, 3, shadow=1, hi=0)
    c.pxs([(59, 152), (61, 152)], SKIN[1])
    # 交叉腿：屈膝从站立腿前向左下伸，脚跟翘起，脚尖绷直
    leg_bent = poly([(57, 116), (62, 120), (53, 127), (46, 132), (43, 133), (42, 130), (48, 125)])
    foot_bent = poly([(44, 129), (41, 130), (38, 130), (36, 132), (36, 134), (35, 137), (33, 140), (34, 142), (36, 142), (38, 139), (41, 135), (45, 133)])
    bent = leg_bent | foot_bent
    c.paint(outer(bent) & (leg_stand | under), SKIN[1])
    c.volume(bent, SKIN, 3, shadow=1, hi=0)
    c.pxs([(34, 142), (33, 141), (35, 141)], SKIN[1])

    # 裙：向左旋开的大钟形，几道楔形长褶
    skirt = poly([(53, 80), (49, 85), (44, 89), (38, 94), (32, 98), (27, 102), (23, 106), (21, 110), (23, 112), (28, 112), (34, 114), (40, 116), (45, 119), (48, 121), (52, 118), (57, 119), (60, 124), (60, 129), (58, 131), (60, 133), (65, 133), (70, 131), (74, 128), (76, 125), (72, 119), (69, 112), (67, 104), (66, 94), (65, 85), (65, 79)])
    c.shape(skirt, IVORY, base=4, shadow=2, hi=0)
    c.paint(taper([(57, 86), (49, 96), (40, 104), (31, 111)], 1, 3) & skirt, IVORY[2])
    c.paint(taper([(63, 94), (59, 104), (53, 113), (50, 119)], 1, 3) & skirt, IVORY[2])
    c.paint(taper([(66, 108), (63, 118), (62, 131)], 1, 2) & skirt, IVORY[2])
    c.paint(taper([(45, 92), (38, 99), (33, 103)], 1, 2) & skirt, IVORY[3])

    # 上身与红腰带
    bodice = poly([(52, 64), (65, 66), (65, 75), (53, 76)])
    c.shape(bodice, IVORY, base=4, shadow=1, hi=0)
    sash = poly([(52, 74), (65, 73), (66, 79), (53, 81)])
    c.paint(sash, RED[3])
    c.paint(sash & ~shift(sash, 0, -2), RED[2])
    c.paint(edge(sash), RED[1])
    c.paint(line([(64, 74), (63, 80)]), RED[2])

    # 左臂：举杖。杖在手后，手在杖前；手和杖尖都和术式环留出余量
    wand(c, (28, 60), (36, 43))
    c.volume(taper([(39, 62), (36, 57), (33, 54)], 4, 3), SKIN, 3, shadow=1, hi=0)
    c.sprite(30, 49, HAND_ROD, SKIN_PAL)
    sleeve_l = poly([(47, 59), (40, 60), (37, 58), (35, 62), (35, 68), (38, 71), (43, 68), (48, 65)])
    c.shape(sleeve_l, IVORY, base=4, shadow=1, hi=0)
    c.paint(line([(37, 59), (35, 63), (36, 68)]) & sleeve_l, IVORY[2])

    # 右臂：平展持杖
    wand(c, (82, 76), (86, 58))
    c.volume(taper([(76, 72), (80, 70), (83, 68)], 4, 3), SKIN, 3, shadow=1, hi=0)
    c.sprite(82, 64, HAND_ROD, SKIN_PAL)
    sleeve_r = poly([(65, 68), (73, 67), (77, 69), (78, 75), (73, 81), (68, 78)])
    c.shape(sleeve_r, IVORY, base=4, shadow=2, hi=0)

    # 靛披肩：交在胸前，领口一个小 V
    c.paint(poly([(54, 58), (63, 58), (60, 68)]), SKIN[3])
    shawl = poly([(37, 58), (46, 56), (54, 56), (58, 62), (60, 67), (63, 59), (67, 58), (71, 63), (75, 67), (71, 70), (64, 69), (58, 71), (50, 66), (44, 62)])
    c.shape(shawl, NAVY, base=3, shadow=1, hi=0)
    c.fold([(44, 59), (52, 62), (58, 68)], NAVY[1], shawl)
    c.fold([(66, 62), (70, 67)], NAVY[1], shawl)
    c.paint(rect(56, 58, 59, 60), SKIN[2])

    # 头：四分之三侧，笑眼。笑弧比精灵自带的宽一格，读得出是在笑
    x0, top = 44, 37
    head(c, 59, top, '3q', 'L', HAIR_SILVER, GREY_IRIS, eyes='happy', mouth='none', lash=SLATE[1])
    c.sprite(x0 + 5, 35, EAR_NEAR, {'0': HAIR_SILVER[1], '3': HAIR_SILVER[3], 'p': PINK[1], 'q': PINK[2]})
    c.sprite(x0 + 14, 36, EAR_FAR, {'0': HAIR_SILVER[1], '2': HAIR_SILVER[2], 'p': PINK[1], 'q': PINK[2]})
    c.paint(rect(x0 + 9, top + 12, x0 + 18, top + 14), SKIN[3])
    c.sprite(x0 + 9, top + 12, ['.kk.', 'k..k'], {'k': SLATE[1]})
    c.sprite(x0 + 15, top + 12, ['.kk.', 'k..k'], {'k': SLATE[1]})
    c.pxs([(x0 + 10, top + 15), (x0 + 11, top + 15), (x0 + 16, top + 15), (x0 + 17, top + 15)], PINK[2])
    # 微笑开口
    c.sprite(x0 + 13, top + 17, ['mmmm', '.rr.'], {'m': '#7a3540', 'r': '#c8605c'})
    # 精灵右缘的鼻尖格在长发衬底上显得突兀，压回头发色
    c.px(x0 + 19, top + 11, HAIR_SILVER[2])

    return c
