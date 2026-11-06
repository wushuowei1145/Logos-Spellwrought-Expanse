"""8 力量：棕发猫耳的女子一手抚鬃、一手托下颌，闭目的狮子平静地仰头；头顶横向无限符号，花环三朵花绕过女子和狮子，开阔草地上只有一座远山。"""
from figures import *

SKIN_PAL = {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0]}
HAIR = HAIR_BROWN
# 狮子只用金色色阶，躯干亮、鬃毛暗，和人物的棕发分得开
BODY, MANE, MANE_DARK, LINE = GOLD[3], GOLD[2], GOLD[1], GOLD[0]

# 托下颌的手：手心向上，拇指贴在颌边，四指从颌下伸出（x=38..58，y=89..97）
HAND_CHIN = [
    '.xxxxxx..............',
    'xllllllxxxxxx........',
    'xlllllllllllsx.......',
    'xllllllllxxxx..xxx...',
    'xllllllllllxxxxllsxx.',
    '.xllllllllllllllllllx',
    '..xxllllllllsssssxxx.',
    '....xxxxxxxxxxxx.....',
]
# 抚鬃的手：手背向上，手指顺着鬃毛垂下（x=59..78，y=67..75）
HAND_MANE = [
    '........xxxxx.......',
    '.....xxxlllllxxx....',
    '..xxxlllllllllllxx..',
    'xxlllllllllllllllsx.',
    'xllllllllllxslxlsxx.',
    'xllllsssxx.xlsxxsxx.',
    '.xxxxx.....xx..xx.x.',
]


def bold(pts):
    m = line(pts)
    return m | shift(m, 0, 1)


def setting(c):
    # 奶油色的天：上浅下暖，只在色带之间有一窄条抖动
    c.vgrad(rect(0, 0, W - 1, 82), [DUSK[4], DUSK[4], DUSK[3]], 0, 80, soft=0.12)
    # 一座远山：方块阶梯边
    peak = blocky(poly([(57, 80), (72, 64), (84, 60), (93, 53), (102, 59), (111, 63), (111, 82), (57, 82)]), 2)
    c.paint(peak, SKY[3])
    c.paint(peak & ~shift(peak, 0, -2), SKY[4])
    slope = blocky(poly([(93, 53), (111, 63), (111, 82), (84, 82)]), 2)
    c.paint(peak & slope, SKY[2])
    # 草地：草方块的语言
    ground = blocky(poly([(0, 75), (20, 77), (58, 79), (111, 79), (111, 192), (0, 192)]), 2)
    c.turf(ground, GRASS, seed=81, base=2)
    c.paint(ground & ~shift(ground, 0, -1), GRASS[4])
    # 脚下的两块投影
    for m in (ellipse(28, 188, 26, 3), ellipse(82, 186, 30, 4)):
        c.paint(m & ground, GRASS[1])
    # 远处几簇草
    for x, y in ((3, 182), (47, 186), (104, 188), (56, 172)):
        c.sprite(x, y, ['.g.g', 'g.g.', '.gg.'], {'g': GRASS[0]})


def infinity(c):
    # 无限符号：横向双环，字面对称轴在 x=40，正好落在头顶
    sign = c.infinity(40, 9, 27, 8, 4)
    c.paint(outer(sign), GOLD[1])
    c.glow(sign, GOLD[3])
    c.glow(sign & ~shift(sign, 1, 1), GOLD[4])


def lion_far_legs(c):
    # 远侧前腿和后腿：压暗一档，退到后面
    for pts in ([(52, 130), (64, 134), (65, 178), (54, 178)], [(82, 148), (94, 146), (92, 176), (82, 176)]):
        m = poly(pts)
        c.paint(outer(m), LINE)
        c.paint(m, MANE)
        c.paint(m & ~shift(m, 1, 0), BODY)
    for x, y, rx in ((59, 181, 7), (85, 177, 7)):
        paw(c, x, y, rx, MANE)


def paw(c, x, y, rx, col):
    m = ellipse(x, y, rx, 3.5)
    c.paint(outer(m), LINE)
    c.paint(m, col)
    for k in (-3, 0, 3):
        c.pxs([(x + k, y + 1), (x + k, y + 2), (x + k, y + 3)], LINE)


def lion_body(c):
    # 尾巴：从臀后沿外侧垂下，尾端一撮鬃毛
    t = taper([(105, 122), (109, 134), (110, 148), (107, 158)], 4, 3)
    c.paint(outer(t), LINE)
    c.paint(t, BODY)

    # 躯干：背线从鬃后斜到臀，腹线收上去
    trunk = poly([(92, 104), (100, 110), (105, 120), (107, 134), (105, 150), (97, 156), (80, 150), (68, 152), (60, 140), (70, 112)])
    c.paint(outer(trunk), LINE)
    c.paint(trunk, BODY)
    c.paint(trunk & ~shift(trunk, 2, 2), GOLD[4])
    # 后腿根部的暗面
    c.paint(poly([(90, 150), (100, 128), (97, 150), (92, 154)]), MANE)

    # 近侧后腿：大腿粗、踝细
    hind = poly([(92, 138), (105, 136), (105, 150), (102, 164), (102, 178), (94, 178), (95, 164), (91, 152)])
    c.paint(outer(hind) & ~trunk, LINE)
    c.paint(hind, BODY)
    c.paint(hind & ~shift(hind, 1, 0), GOLD[4])
    c.paint(line([(91, 152), (95, 164), (94, 178)]) | line([(105, 150), (102, 164), (102, 178)]), LINE)
    paw(c, 98, 180, 6, BODY)
    tuft = blob([(108, 155), (111, 160), (111, 167), (107, 173), (103, 167), (104, 160)])
    c.paint(outer(tuft), LINE)
    c.paint(tuft, MANE)
    c.paint(line([(107, 160), (106, 170)]), MANE_DARK)

    # 近侧前腿
    front = poly([(64, 136), (78, 138), (78, 156), (76, 170), (76, 182), (67, 182), (68, 168), (66, 154)])
    c.paint(front, BODY)
    c.paint(front & ~shift(front, 1, 0), GOLD[4])
    c.paint(line([(66, 150), (68, 168), (67, 182)]) | line([(78, 150), (76, 170), (76, 182)]), LINE)
    paw(c, 72, 185, 7, BODY)


def lion_mane(c):
    # 鬃毛：大块外形 + 边上的几个尖，不画细毛
    outline_pts = [(57, 73), (68, 71), (78, 72), (86, 75), (92, 79), (96, 86), (100, 89), (103, 97), (100, 98), (102, 106), (100, 116), (96, 124), (92, 120), (93, 128), (84, 134), (82, 128), (78, 138), (72, 148), (68, 138), (62, 142), (58, 132), (53, 134), (50, 124), (46, 118), (45, 106), (49, 97), (52, 92)]
    mane = poly(outline_pts)
    spikes = poly([(84, 74), (92, 79), (88, 79)]) | poly([(95, 84), (103, 92), (98, 92)]) | poly([(100, 95), (106, 97), (101, 99)])
    whole = mane | spikes
    c.paint(outer(whole), LINE)
    c.paint(whole, MANE)
    # 成绺的暗面：硬边，顺着鬃毛向下
    for pts, w0 in (
        (((70, 104), (68, 116), (60, 128)), 5),
        (((78, 110), (76, 122), (70, 136)), 5),
        (((88, 104), (90, 114), (85, 128)), 6),
        (((94, 96), (96, 106), (93, 118)), 4),
        (((56, 104), (52, 114), (52, 124)), 4),
        (((82, 92), (86, 96), (86, 104)), 4),
        (((64, 112), (60, 118), (56, 128)), 3),
    ):
        c.paint(taper(pts, w0, 1) & whole, MANE_DARK)
    # 亮绺
    for pts in (((58, 100), (55, 108)), ((74, 96), (72, 106)), ((84, 110), (82, 120)), ((62, 118), (64, 128))):
        c.paint(taper(pts, 3, 1) & whole, BODY)


def lion_head(c):
    # 耳朵：圆，内侧暗
    e = blob([(75, 82), (80, 81), (83, 85), (81, 90), (77, 91), (75, 87)])
    c.paint(outer(e), LINE)
    c.paint(e, BODY)
    c.paint(poly([(77, 85), (80, 84), (79, 88), (77, 89)]), MANE_DARK)

    # 脸：微微仰起的侧面，整块平涂
    head_m = poly([(46, 79), (52, 78), (60, 77), (67, 76), (72, 79), (74, 84), (73, 90), (70, 96), (66, 101), (63, 97), (60, 103), (57, 97), (52, 97), (47, 95), (45, 90), (45, 85)])
    c.paint(outer(head_m), LINE)
    c.paint(head_m, BODY)
    # 口鼻亮一档
    c.paint(poly([(47, 84), (53, 82), (58, 84), (58, 88), (53, 88), (48, 86)]), GOLD[4])
    # 鼻：深色小块，只属于狮子
    c.paint(poly([(46, 78), (52, 78), (53, 80), (50, 83), (47, 83), (45, 81)]), DIRT[1])
    c.pxs([(47, 79), (48, 79)], DIRT[3])
    # 嘴：含笑的一条长线
    c.paint(line([(47, 84), (47, 86), (50, 87), (54, 89), (58, 89)]), LINE)
    c.px(59, 88, LINE)
    # 闭着的眼：一道向上的弧
    c.paint(line([(60, 83), (62, 81), (64, 81), (66, 83)]), LINE)
    # 额头压下来的鬃毛
    brow = poly([(58, 75), (68, 72), (76, 74), (76, 81), (71, 79), (66, 77), (60, 77)])
    c.paint(outer(brow) & head_m, LINE)
    c.paint(brow, MANE)
    c.paint(line([(66, 75), (72, 77)]), MANE_DARK)


def hair_back(c):
    # 猫尾：棕色，从裙后绕到身侧
    tail(c, [(10, 118), (4, 110), (2, 100), (4, 93), (9, 92)], HAIR, 5, 4)

    # 长发：从头后向左下甩开，外缘有两三个波浪
    back = blob([(34, 22), (42, 20), (50, 24), (54, 32), (55, 42), (54, 52), (51, 60), (46, 56), (38, 52), (28, 56), (24, 68), (19, 78), (12, 84), (7, 78), (10, 70), (8, 62), (12, 52), (15, 44), (20, 37), (27, 27)])
    c.paint(back, HAIR[2])
    c.paint(back & ~shift(back, 2, 2), HAIR[3])
    c.paint(edge(back), HAIR[0])
    # 暗绺和亮绺顺着发流走
    c.paint(taper([(28, 34), (20, 48), (17, 64), (15, 76)], 4, 1) & back, HAIR[1])
    c.paint(taper([(24, 36), (15, 52), (12, 70)], 2, 1) & back, HAIR[3])
    hair_lock(c, [(13, 76), (8, 84), (5, 90)], HAIR, 5, 1)
    hair_lock(c, [(19, 76), (17, 84), (13, 90)], HAIR, 5, 1)
    # 下颌后面的头发压暗，脸才从发里分出来
    c.paint(poly([(38, 46), (52, 40), (54, 52), (47, 56), (38, 52)]), HAIR[1])


def dress(c):
    # 长裙：象牙底，硬边竖褶
    skirt = poly([(25, 74), (41, 74), (44, 100), (47, 130), (54, 174), (32, 178), (1, 172), (9, 135), (18, 100)])
    c.shape(skirt, IVORY, base=4, shadow=0, hi=0, line_color=IVORY[0])
    for pts in (((30, 112), (26, 172), (15, 174)), ((38, 120), (42, 174), (34, 176)), ((14, 130), (8, 170), (4, 171)), ((43, 100), (47, 174), (52, 174), (46, 130))):
        c.paint(poly(pts) & skirt, IVORY[2])
    # 靴子
    for pts in ([(13, 174), (26, 174), (27, 184), (28, 189), (20, 190), (13, 187)], [(28, 174), (38, 174), (39, 180), (45, 184), (45, 187), (30, 187)]):
        boot = poly(pts)
        c.shape(boot, LEATHER, base=1, shadow=1, hi=1)
    # 裙摆盖住靴筒，再画一次下沿
    c.paint(skirt & (YY > 170), IVORY[3])
    c.paint(edge(skirt) & (YY > 165), IVORY[0])
    # 绿色系带：腰上一圈，垂下两片
    for pts in ([(23, 80), (28, 80), (24, 106), (17, 112)], [(28, 80), (31, 80), (28, 102), (25, 104)]):
        c.shape(poly(pts), SAGE, base=2, shadow=1, hi=0)
    waist = poly([(23, 72), (42, 73), (41, 80), (24, 81)])
    c.shape(waist, SAGE, base=2, shadow=1, hi=0)


def upper_body(c):
    # 上身
    bust = poly([(26, 56), (42, 56), (43, 74), (25, 74)])
    c.paint(bust, IVORY[4])
    c.paint(poly([(36, 62), (42, 66), (42, 74), (36, 74)]), IVORY[2])

    # 抚鬃的手臂：袖子向右上伸，袖口收紧
    sleeve_up = poly([(38, 60), (56, 69), (58, 76), (46, 77), (36, 72)])
    c.shape(sleeve_up, IVORY, base=4, shadow=1, hi=0, line_color=IVORY[0])
    c.paint(line([(54, 69), (53, 76)]), IVORY[0])
    forearm = poly([(56, 69), (62, 69), (63, 73), (58, 76)])
    c.volume(forearm, SKIN, 3, shadow=1, hi=0)
    c.paint(line([(58, 76), (63, 74)]), SKIN[0])

    # 托颌的手臂：袖子斜向右下
    sleeve_low = poly([(18, 72), (27, 70), (43, 88), (38, 96), (22, 86)])
    c.shape(sleeve_low, IVORY, base=4, shadow=1, hi=0, line_color=IVORY[0])
    c.paint(line([(30, 77), (28, 86)]), IVORY[2])

    # 绿色小披肩：两片在胸前的菱扣处收拢
    left = poly([(20, 47), (29, 50), (36, 56), (37, 62), (27, 66), (17, 76), (10, 68), (15, 56)])
    right = poly([(36, 56), (46, 52), (50, 60), (55, 69), (47, 78), (40, 70), (37, 62)])
    c.shape(left, SAGE, base=2, shadow=2, hi=0)
    c.shape(right, SAGE, base=2, shadow=2, hi=0)
    c.fold([(30, 54), (24, 58), (20, 66)], SAGE[1], left)
    c.fold([(42, 60), (48, 70)], SAGE[1], right)

    # 颈和白领
    neck = poly([(35, 43), (40, 46), (38, 52), (33, 50)])
    c.paint(neck, SKIN[2])
    coll = poly([(30, 45), (35, 50), (37, 56), (32, 54), (28, 49)])
    c.paint(coll, IVORY[4])
    c.paint(edge(coll), IVORY[1])
    # 胸扣：单枚蓝青
    gem(c, 36, 57, 3)


def face(c):
    # 猫耳
    for pts, inner_pts in (([(35, 13), (41, 22), (31, 27)], [(35, 17), (38, 22), (33, 25)]), ([(58, 21), (53, 33), (47, 24)], [(56, 24), (52, 30), (50, 25)])):
        e = poly(pts)
        c.paint(outer(e), HAIR[0])
        c.paint(e, HAIR[2])
        c.paint(poly(inner_pts), PINK[2])

    # 脸：向右下微倾，近眼高、远眼低
    face_m = poly([(36, 30), (44, 28), (51, 29), (52, 38), (50, 42), (44, 46), (40, 47), (37, 45), (35, 40)])
    c.paint(face_m, SKIN[3])
    c.paint(line([(36, 44), (38, 46), (40, 47), (43, 46)]), SKIN[2])
    c.pxs([(51, 37), (51, 38), (50, 39), (50, 40), (49, 41)], SKIN[2])

    # 发冠和刘海：发尖停在眼睛上方，只有一绺落到两眼之间
    crown = poly([(32, 27), (36, 22), (42, 20), (48, 22), (53, 27), (55, 33), (55, 40), (53, 44), (52, 37), (51, 32), (49, 34), (47, 31), (45, 36), (43, 31), (40, 34), (38, 31), (36, 35), (34, 31)])
    c.paint(crown, HAIR[2])
    c.paint(crown & ~shift(crown, 2, 2), HAIR[3])
    c.paint(edge(crown), HAIR[0])
    c.paint(curve([(35, 27), (40, 25), (47, 27)]), HAIR[1])
    c.paint(curve([(38, 22), (43, 23), (48, 25)]), HAIR[4])

    # 脸两侧的长发绺
    hair_lock(c, [(33, 30), (32, 40), (33, 50), (35, 56)], HAIR, 5, 2)
    hair_lock(c, [(54, 36), (55, 44), (52, 51)], HAIR, 4, 1)

    # 五官：低垂含笑，只留眼、鼻、嘴三处
    eye = {'k': HAIR[0], 'w': '#f3f2e8', 'e': BROWN_IRIS, 'p': '#3a2010'}
    c.sprite(37, 35, ['.kkkkk', 'kkwppw', '..wepe', '...ee.'], eye)
    c.sprite(47, 37, ['kkkkk', 'wppwk', 'epew.', '.ee..'], eye)
    c.px(45, 41, SKIN[1])
    c.pxs([(41, 43), (45, 43)], SKIN[1])
    c.paint(rect(42, 44, 44, 44), SKIN[0])
    c.pxs([(38, 41), (39, 41), (48, 42), (49, 42)], PINK[2])


def hands(c):
    c.sprite(38, 89, HAND_CHIN, SKIN_PAL)
    c.sprite(59, 67, HAND_MANE, SKIN_PAL)


def garland(c):
    # 花环：一根藤穿过裙前和鬃毛，三朵白花
    vine = curve([(29, 108), (34, 114), (42, 120), (52, 121), (60, 118), (68, 112), (73, 106)])
    c.paint(shift(vine, 0, 1), SAGE[1])
    c.paint(vine, SAGE[3])
    for pts in ([(24, 111), (27, 112), (26, 117), (23, 114)], [(34, 106), (39, 108), (36, 110)], [(39, 125), (42, 126), (40, 129), (37, 128)], [(47, 124), (50, 126), (49, 130), (46, 128)], [(60, 123), (62, 125), (60, 130), (58, 127)]):
        m = poly(pts)
        c.paint(outer(m), LEAF[0])
        c.paint(m, LEAF[2])
        c.paint(m & ~shift(m, 1, 1), LEAF[3])
    for x, y in ((27, 105), (43, 120), (63, 116)):
        white_flower(c, x, y, 4, outline=IVORY[0])


def draw():
    c = Card('08_strength')
    setting(c)
    infinity(c)
    hair_back(c)
    lion_far_legs(c)
    lion_body(c)
    lion_mane(c)
    lion_head(c)
    dress(c)
    upper_body(c)
    face(c)
    hands(c)
    garland(c)
    return c
