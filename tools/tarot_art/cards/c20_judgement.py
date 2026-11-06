"""20 审判：银发猫耳使者立于方云之上吹金号角，号角旗红十字嵌蓝青菱印，三道声光落向下方；三只石匣里两侧灵猫人形、中间背向观众的学徒仰望举手回应。"""
from figures import *

# 石匣用的浅石色阶：比公共 STONE 提亮一档，缝不至于黑成一条条
BOX = [STONE[1], STONE[2], STONE[3], STONE[4], '#c6c7c8']
SKIN_PAL = {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0]}

# 举起的张开手：拇指在外，四指分开，指尖朝上
HAND_RAISED = [
    '.l.l.l.',
    '.l.l.l.l',
    '.l.l.l.l',
    'xlllll.l',
    'xllllll.',
    '.lllls..',
    '..lsd...',
]
# 向侧边摊开的手，掌心朝上，指尖朝左
HAND_SIDE = [
    '....l..',
    'lllll..',
    'dllllls',
    'lllllls',
    '..lllsd',
]
# 握在号角上的手：四指横过管身，指缝沿 / 方向
HAND_GRIP = [
    '...ll.',
    '.lllls',
    'lllsls',
    'llslls',
    'lslllsd',
    '.lllsd',
    '..sd..',
]
# 使者的大猫耳：近耳和远耳，耳根埋进头发
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
# 配角的小猫耳：比头部精灵自带的耳朵大一号，读得出是猫耳
EAR_M_NEAR = [
    '.0...',
    '.00..',
    '.0p0.',
    '0pq20',
    '0pq22',
]
EAR_M_FAR = [
    '...0',
    '..00',
    '.0q0',
    '0qp0',
]
# 学徒的后脑：短发，两撮小翘发，没有耳朵
HAIR_BACK = [
    '.....0...0....',
    '....02002200..',
    '...0222222220.',
    '..02233222220.',
    '.0223332222210',
    '.0223322222210',
    '00222222222210',
    '.0222212222110',
    '.0222212222110',
    '.0222212222110',
    '.0222122221100',
    '..022122221110',
    '..02212222110.',
    '...021222110..',
    '....0222110...',
    '.....01110....',
    '......000.....',
]
# 胸前的长菱纹
CHEST_DIAMOND = [
    '...g...',
    '..ggg..',
    '..gbg..',
    '.gbbbg.',
    '.gbbbg.',
    'gbbcbbg',
    '.gbbbg.',
    '.gbbbg.',
    '..gbg..',
    '..ggg..',
    '...g...',
]


def rot_ellipse(cx, cy, a, b, deg):
    """旋转过的实心椭圆，号角喇叭口用。"""
    t = np.radians(deg)
    u = (XX - cx) * np.cos(t) + (YY - cy) * np.sin(t)
    v = -(XX - cx) * np.sin(t) + (YY - cy) * np.cos(t)
    return (u / (a + 0.25)) ** 2 + (v / (b + 0.25)) ** 2 <= 1


def box_back(c, bx0, bx1, by, fx0, fx1, fy):
    """石匣的上表面和匣内暗口。人物画在它之后，匣子前壁再压回来。"""
    top = poly([(bx0, by), (bx1, by), (fx1, fy), (fx0, fy)])
    c.paint(top, BOX[3])
    c.paint(top & rect(0, by, W, by), BOX[4])
    c.paint(edge(top) & ~rect(0, by, W, by), BOX[2])
    inner = poly([(bx0 + 3, by + 2), (bx1 - 3, by + 2), (fx1 - 3, fy), (fx0 + 3, fy)])
    c.paint(inner, SLATE[1])
    c.paint(inner & rect(0, by + 2, W, by + 3), SLATE[0])
    return top


def box_front(c, fx0, fx1, fy, y_bottom, side=None, seed=3):
    """石匣的前壁：一道受光的匣沿，下面是错缝石砖。side 是透视露出的侧面。"""
    if side is not None:
        c.paint(side, BOX[1])
        c.paint(edge(side), BOX[0])
    face = rect(fx0, fy + 2, fx1, y_bottom)
    c.stone_bricks(face, BOX, bw=9, bh=4, oy=fy + 2, seed=seed, base=2)
    c.paint(rect(fx0, fy, fx1, fy + 1), BOX[4])
    c.paint(rect(fx0, fy + 1, fx1, fy + 1), BOX[3])
    c.paint(rect(fx0, y_bottom, fx1, y_bottom), BOX[0])


def slim_tail(c, pts, ramp, w0=4, w1=3, base=2):
    """细尾巴：暗边包一条平涂的芯，不做体积，免得碎成噪点。"""
    m = taper(pts, w0, w1)
    c.paint(m, ramp[0])
    c.paint(shrink(m), ramp[base])
    return m


def wing_mask(feathers, body):
    """翅膀：几支圆头长羽并在一起，再用一块翼肩把前缘补齐。"""
    m = blob(body)
    for pts, w0, w1 in feathers:
        m |= taper(pts, w0, w1)
    return m


def draw():
    c = Card('20_judgement')

    # 上层深靛场域，下层蓝天与静水
    c.paint(rect(0, 0, W - 1, 90), NAVY[1])
    c.vgrad(rect(0, 88, W - 1, 128), [SKY[1], SKY[2]], 88, 128)
    c.vgrad(rect(0, 128, W - 1, 191), [SKY[2], SKY[3]], 128, 191)

    # 远山：方块阶梯边
    hill_l = blocky(poly([(0, 127), (0, 124), (8, 122), (16, 118), (24, 122), (36, 126), (47, 127)]), 2)
    hill_r = blocky(poly([(64, 127), (80, 124), (96, 117), (104, 121), (111, 125), (111, 127)]), 2)
    for hill in (hill_l, hill_r):
        c.paint(hill, SKY[4])
        c.paint(hill & ~shift(hill, 0, 2), SKY[5])
    # 静水的横向亮条
    for x0, x1, y in [(29, 36, 156), (28, 37, 161), (37, 44, 171), (75, 80, 160), (71, 80, 164), (2, 9, 166), (102, 108, 165), (64, 70, 168)]:
        c.paint(rect(x0, y, x1, y), SKY[4])

    # 双翼：圆头长羽，只留外形和几道羽线
    wing_l = wing_mask([
        ([(30, 20), (20, 7), (11, 2)], 15, 7),
        ([(29, 24), (16, 12), (6, 8)], 14, 7),
        ([(28, 28), (14, 20), (4, 19)], 14, 7),
        ([(28, 33), (16, 29), (6, 31)], 12, 7),
        ([(28, 36), (20, 35), (12, 39)], 10, 6),
        ([(28, 38), (24, 42), (21, 47)], 9, 5),
    ], [(14, 0), (22, 5), (29, 12), (32, 20), (33, 32), (28, 40), (22, 30), (16, 15)])
    wing_r = wing_mask([
        ([(74, 18), (90, 8), (105, 2)], 15, 7),
        ([(74, 22), (92, 13), (107, 9)], 14, 7),
        ([(72, 28), (92, 22), (107, 21)], 14, 7),
        ([(70, 33), (88, 32), (102, 34)], 12, 7),
        ([(68, 37), (82, 40), (92, 45)], 10, 6),
        ([(66, 40), (74, 44), (80, 50)], 9, 5),
    ], [(100, 0), (90, 6), (76, 13), (71, 22), (68, 30), (64, 36), (72, 38), (84, 22)])
    for wing in (wing_l, wing_r):
        c.paint(wing, IVORY[4])
        c.paint(wing & ~shift(wing, -1, -2), IVORY[2])
    for pts in [[(21, 14), (15, 10)], [(20, 21), (13, 19)], [(21, 29), (15, 29)]]:
        c.fold(pts, IVORY[3], wing_l)
    for pts in [[(86, 16), (94, 12)], [(87, 24), (95, 22)], [(84, 32), (91, 32)]]:
        c.fold(pts, IVORY[3], wing_r)

    # 尾巴：从袍后绕到左侧
    tail(c, [(15, 72), (8, 66), (5, 57), (8, 51), (14, 49)], HAIR_SILVER, 5, 3, base=3)

    # 后发和向左吹开的发绺并成一块，只上一次色，内部几条发丝
    hair_mass = blob([(42, 12), (52, 10), (60, 13), (63, 20), (64, 28), (61, 32), (52, 32), (44, 31), (40, 28), (38, 20)])
    hair_mass |= poly([(42, 16), (36, 19), (30, 20), (35, 22), (41, 24)])
    hair_mass |= poly([(41, 22), (34, 26), (28, 31), (36, 30), (42, 28)])
    hair_mass |= poly([(42, 26), (38, 31), (34, 38), (41, 34), (46, 30)])
    hair_mass |= poly([(62, 24), (65, 29), (63, 36), (60, 31)])
    hair_paint(c, hair_mass, HAIR_SILVER, base=2, line_edge=False)
    c.paint(edge(hair_mass), HAIR_SILVER[1])
    c.fold([(40, 24), (34, 28)], HAIR_SILVER[1], hair_mass)
    c.fold([(42, 29), (38, 33)], HAIR_SILVER[1], hair_mass)

    # 使者身躯：靛袍
    robe = poly([(30, 33), (38, 30), (46, 29), (58, 33), (64, 36), (72, 52), (72, 64), (66, 72), (66, 86), (24, 86), (24, 46), (27, 38)])
    c.shape(robe, NAVY, base=3, shadow=2, hi=0)
    c.fold([(34, 37), (44, 44)], NAVY[1], robe)

    # 中央袍片：两道金边夹靛面，左边露一窄条象牙内袍
    c.paint(rect(46, 49, 49, 86), IVORY[4])
    c.paint(rect(46, 49, 46, 86), IVORY[1])
    c.paint(rect(50, 44, 51, 86), GOLD[3])
    c.paint(rect(52, 44, 58, 86), NAVY[3])
    c.paint(rect(59, 38, 60, 86), GOLD[3])
    c.sprite(52, 46, CHEST_DIAMOND, {'g': GOLD[3], 'b': SKY[3], 'c': SKY[4]})
    # 右侧露出的内袍
    under = poly([(62, 54), (68, 50), (67, 62), (64, 76), (61, 78)])
    c.paint(under, IVORY[4])
    c.paint(edge(under), IVORY[1])
    c.trim([(61, 56), (66, 49)])

    # 左侧宽袖：象牙色大幅下垂，金边包在上沿和内沿
    sleeve = poly([(22, 47), (30, 43), (36, 42), (45, 45), (43, 52), (36, 64), (27, 80), (23, 86), (9, 86), (13, 76), (19, 62)])
    c.shape(sleeve, IVORY, base=4, shadow=2, hi=0)
    c.paint(edge(sleeve) & (YY < 48), IVORY[4])
    c.paint(taper([(33, 47), (26, 62), (18, 82)], 1, 3) & sleeve, IVORY[2])
    c.paint(taper([(40, 50), (34, 61), (27, 76)], 1, 2) & sleeve, IVORY[3])
    c.trim([(22, 47), (30, 42), (36, 41), (45, 44)])
    c.trim([(45, 44), (43, 52), (36, 64), (27, 80), (23, 86)])
    # 肩上的金带
    c.trim([(36, 31), (39, 31), (50, 41)])

    # 方块云：平底矩形堆叠，压住袍摆
    cloud = rect(0, 84, 111, 92) | rect(0, 70, 7, 84) | rect(7, 74, 14, 84) | rect(14, 78, 22, 84) | rect(22, 81, 36, 84) | rect(36, 78, 50, 84) | rect(50, 75, 64, 84) | rect(64, 79, 88, 84) | rect(88, 76, 94, 84) | rect(94, 71, 103, 84) | rect(103, 68, 111, 84)
    cloud |= rect(8, 92, 66, 97) | rect(14, 97, 50, 99) | rect(88, 92, 111, 95)
    c.paint(cloud, IVORY[4])
    c.paint(cloud & rect(0, 88, W - 1, 92), IVORY[3])
    c.paint(cloud & rect(0, 93, W - 1, 99), IVORY[3])
    c.paint(cloud & ~shift(cloud, -2, 0) & rect(0, 0, W - 1, 88), IVORY[3])
    c.paint(cloud & ~shift(cloud, 0, -2), IVORY[2])

    # 握号角下方的手臂：靛袖压在翅膀上，读得出来
    sleeve_r = poly([(75, 49), (79, 47), (86, 57), (81, 60), (76, 55)])
    c.shape(sleeve_r, NAVY, base=3, shadow=1, hi=0)
    c.paint(line([(75, 49), (79, 47)]), GOLD[3])
    fore_r = taper([(72, 46), (77, 49)], 4, 4)
    c.paint(outer(fore_r) & ~sleeve_r, SKIN[0])
    c.paint(fore_r, SKIN[3])

    # 号角：喇叭口朝下，管身向右下放宽
    body = poly([(60, 36), (63, 35), (67, 40), (71, 46), (78, 53), (84, 58), (88, 62), (65, 69), (67, 60), (68, 55), (67, 48), (64, 42)])
    c.shape(body, GOLD, base=3, shadow=2, hi=1, line_color=GOLD[1])
    bell = rot_ellipse(76, 64, 13, 5.5, -18)
    c.paint(outer(bell), GOLD[1])
    c.glow(bell, GOLD[4])
    inner = rot_ellipse(76, 64, 11, 3.5, -18)
    c.paint(inner, GOLD[1])
    c.paint(inner & ~shift(inner, 0, -2), GOLD[2])

    # 号角旗：挂在喇叭口下
    flag = poly([(69, 71), (85, 67), (88, 101), (79, 95), (69, 103)])
    c.paint(outer(flag), GOLD[1])
    c.paint(flag, GOLD[3])
    flag_in = shrink(flag, 2)
    c.paint(flag_in, IVORY[4])
    c.paint(flag_in & ~shift(flag_in, -1, 0), IVORY[2])
    c.paint(rect(70, 66, 71, 72), IVORY[3])
    c.paint(rect(83, 63, 84, 68), IVORY[3])
    # 红十字与蓝青菱印
    cross = rect(75, 72, 79, 92) | rect(71, 76, 84, 80)
    c.paint(cross, RED[3])
    c.paint(cross & ~shift(cross, -1, -1), RED[2])
    c.node(77, 78, 3)

    # 颈与立领：两道细金边夹出 V 形领口
    c.paint(poly([(48, 29), (55, 30), (55, 33), (53, 38), (52, 38)]), SKIN[3])
    c.paint(rect(50, 30, 55, 31), SKIN[2])
    c.paint(line([(47, 30), (52, 38), (52, 40)]), GOLD[3])
    c.paint(line([(56, 31), (54, 38)]), GOLD[3])

    # 头：四分之三侧，闭目吹奏。闭眼是两条细长睫线
    head(c, 55, 8, '3q', 'L', HAIR_SILVER, BLUE_IRIS, eyes='closed', mouth='none')
    c.sprite(44, 6, EAR_NEAR, {'0': HAIR_SILVER[1], '3': HAIR_SILVER[3], 'p': PINK[1], 'q': PINK[2]})
    c.sprite(58, 8, EAR_FAR, {'0': HAIR_SILVER[1], '2': HAIR_SILVER[2], 'p': PINK[1], 'q': PINK[2]})
    c.paint(rect(50, 20, 58, 23), SKIN[3])
    c.pxs([(49, 21), (50, 21), (51, 21), (52, 22), (53, 22)], HAIR_SILVER[0])
    c.pxs([(56, 22), (57, 22), (58, 21)], HAIR_SILVER[0])
    c.px(55, 24, SKIN[1])

    # 号角吹口：贴着嘴，到第一只手之间露出一段细管
    tube = line([(56, 26), (62, 34)])
    c.paint(outer(tube) & (XX > 56), GOLD[1])
    c.paint(tube, GOLD[3])
    c.paint(line([(56, 25), (61, 32)]), GOLD[4])
    c.paint(rect(54, 25, 56, 27) & ~rect(54, 25, 54, 25) & ~rect(54, 27, 54, 27), GOLD[4])
    c.paint(rect(55, 27, 56, 27), GOLD[2])

    # 小臂与两只握号角的手，外圈描暗线，压在翅膀和头发上也分得开
    fore_l = taper([(50, 47), (54, 43), (58, 38)], 6, 4)
    c.volume(fore_l, SKIN, 3, shadow=1, hi=0)
    for x, y in [(58, 33), (67, 42)]:
        c.paint(outer(c.sprite_mask(x, y, HAND_GRIP)), SKIN[0])
        c.sprite(x, y, HAND_GRIP, SKIN_PAL)

    # 三道声光：从云底落下，上窄下宽
    rays = poly([(55, 100), (56, 100), (60, 125), (51, 125)])
    left = poly([(49, 100), (50, 101), (38, 120), (32, 117)])
    rays |= left | mirror(left)
    c.aura(rays, SKY[3], 1, 0.5, clip=~grow(cloud) & ~grow(flag) & rect(0, 96, W - 1, 130))
    c.glow(rays, GOLD[4])
    c.glow(edge(rays) & (XX > 55.5) & ~shift(rays, -1, 0), GOLD[3])

    # 左：黑发灵猫人形，靛背心，一手举起一手向侧张开
    box_back(c, 10, 33, 169, 1, 29, 178)
    tail(c, [(9, 173), (5, 168), (3, 162), (5, 157)], HAIR_BLACK, 5, 4)
    skirt = poly([(11, 156), (24, 156), (27, 178), (8, 178)])
    c.shape(skirt, NAVY, base=3, shadow=1, hi=0)
    apron = poly([(15, 160), (21, 160), (24, 178), (13, 178)])
    c.paint(apron, IVORY[4])
    c.paint(edge(apron), IVORY[1])
    c.fold([(18, 164), (17, 177)], IVORY[2], apron)
    torso = poly([(10, 141), (24, 141), (25, 157), (11, 157)])
    c.shape(torso, NAVY, base=3, shadow=1, hi=0)
    chest = poly([(17, 141), (20, 141), (22, 156), (17, 156)])
    c.paint(chest, IVORY[4])
    c.paint(line([(17, 141), (17, 156)]), IVORY[1])
    sash = rect(11, 155, 25, 158)
    c.paint(sash, NAVY[2])
    c.paint(edge(sash), NAVY[0])
    c.paint(poly([(19, 158), (22, 158), (23, 172), (20, 170)]), NAVY[2])
    c.paint(line([(22, 158), (23, 172)]), NAVY[0])
    # 举起的手臂
    arm_r = poly([(22, 141), (27, 139), (31, 135), (32, 146), (26, 151)])
    c.shape(arm_r, IVORY, base=4, shadow=1, hi=0)
    c.paint(line([(30, 136), (31, 146)]), NAVY[2])
    c.paint(taper([(32, 140), (34, 135)], 3, 3), SKIN[3])
    c.sprite(31, 128, HAND_RAISED, SKIN_PAL)
    # 侧伸的手臂
    arm_l = poly([(10, 143), (13, 145), (14, 158), (11, 160), (7, 151)])
    c.shape(arm_l, IVORY, base=4, shadow=1, hi=0)
    c.paint(line([(8, 150), (11, 160)]), NAVY[2])
    c.paint(rect(5, 151, 8, 153), SKIN[3])
    c.sprite(1, 149, HAND_SIDE, SKIN_PAL)
    c.paint(rect(13, 138, 16, 141), SKIN[2])
    head(c, 16, 127, '3q', 'M', HAIR_BLACK, GOLD_IRIS, eyes='up', mouth='soft')
    c.sprite(9, 124, EAR_M_NEAR, {'0': HAIR_BLACK[0], '2': HAIR_BLACK[2], 'p': PINK[1], 'q': PINK[2]})
    c.sprite(19, 125, EAR_M_FAR, {'0': HAIR_BLACK[0], 'p': PINK[1], 'q': PINK[2]})
    box_front(c, 1, 29, 178, 190, poly([(29, 178), (33, 169), (33, 178), (30, 190), (29, 190)]), seed=3)

    # 中：背向观众的人类学徒，双手高举，左右对称
    box_back(c, 44, 67, 175, 37, 74, 183)
    gown = poly([(48, 172), (63, 172), (67, 183), (44, 183)])
    c.shape(gown, IVORY, base=4, shadow=1, hi=0)
    c.fold([(52, 174), (50, 182)], IVORY[2], gown)
    c.fold([(59, 174), (61, 182)], IVORY[2], gown)
    arm_cl = poly([(41, 147), (45, 144), (49, 150), (53, 156), (49, 169), (45, 160)])
    torso_c = poly([(48, 156), (63, 156), (64, 171), (47, 171)])
    robe_c = torso_c | arm_cl | mirror(arm_cl)
    c.shape(robe_c, IVORY, base=4, shadow=1, hi=0)
    c.fold([(49, 158), (49, 168)], IVORY[1], robe_c)
    c.fold([(62, 158), (62, 168)], IVORY[1], robe_c)
    sash_c = rect(47, 170, 64, 173)
    c.paint(sash_c, IVORY[3])
    c.paint(edge(sash_c), IVORY[1])
    fore_c = taper([(44, 146), (42, 141)], 3, 3)
    c.paint(fore_c | mirror(fore_c), SKIN[3])
    c.sprite(37, 134, HAND_RAISED, SKIN_PAL)
    c.sprite(67, 134, HAND_RAISED, SKIN_PAL, flip=True)
    # 后脑：人类学徒，没有猫耳，只有短发
    c.paint(rect(53, 154, 58, 157), SKIN[2])
    c.sprite(49, 139, HAIR_BACK, {'0': HAIR_BROWN[0], '1': HAIR_BROWN[1], '2': HAIR_BROWN[2], '3': HAIR_BROWN[3]})
    box_front(c, 37, 74, 183, 191, None, seed=5)

    # 右：银发灵猫人形，绿袍象牙披肩
    box_back(c, 78, 101, 169, 82, 110, 178)
    tail(c, [(102, 173), (105, 168), (106, 162), (109, 159)], HAIR_SILVER, 5, 4, base=3)
    long_hair = blob([(99, 134), (105, 139), (108, 146), (109, 153), (106, 151), (106, 156), (102, 152), (99, 146)])
    hair_paint(c, long_hair, HAIR_SILVER, base=2)
    skirt_r = poly([(87, 158), (101, 158), (103, 178), (85, 178)])
    c.shape(skirt_r, SAGE, base=2, shadow=1, hi=0)
    apron_r = poly([(96, 160), (101, 160), (103, 178), (98, 178)])
    c.paint(apron_r, IVORY[4])
    c.paint(edge(apron_r), IVORY[1])
    bodice = poly([(90, 144), (103, 145), (103, 158), (88, 158)])
    c.shape(bodice, SAGE, base=2, shadow=1, hi=0)
    mantle = poly([(92, 142), (100, 141), (103, 146), (104, 157), (96, 150)])
    c.shape(mantle, IVORY, base=4, shadow=1, hi=0)
    c.trim([(94, 147), (103, 157)])
    sash_r = rect(88, 156, 98, 159)
    c.paint(sash_r, IVORY[4])
    c.paint(edge(sash_r), IVORY[1])
    arm_rr = poly([(82, 138), (88, 142), (94, 146), (90, 160), (86, 161), (82, 155)])
    c.shape(arm_rr, SAGE, base=2, shadow=1, hi=0)
    c.paint(line([(82, 139), (83, 156)]), IVORY[4])
    c.trim([(89, 142), (90, 150)])
    c.paint(taper([(84, 140), (81, 135)], 3, 3), SKIN[3])
    c.sprite(74, 128, HAND_RAISED, SKIN_PAL, flip=True)
    c.paint(rect(95, 141, 98, 143), SKIN[2])
    head(c, 95, 130, '3q', 'M', HAIR_SILVER, GREY_IRIS, eyes='up', mouth='soft', flip=True)
    c.sprite(98, 127, EAR_M_NEAR, {'0': HAIR_SILVER[0], '2': HAIR_SILVER[2], 'p': PINK[1], 'q': PINK[2]}, flip=True)
    c.sprite(90, 128, EAR_M_FAR, {'0': HAIR_SILVER[0], 'p': PINK[1], 'q': PINK[2]}, flip=True)
    box_front(c, 82, 110, 178, 190, poly([(82, 178), (78, 169), (78, 178), (81, 190), (82, 190)]), seed=7)

    return c
