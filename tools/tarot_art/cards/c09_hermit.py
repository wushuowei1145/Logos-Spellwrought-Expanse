"""9 隐士：银发猫耳的男人放下兜帽立在雪岩上，低头侧望，左手高举星灯，右手拄杖；一道浅色楔形灯照落向山下的路。"""
from figures import *

# 远山和岩石的蓝灰：比 CLOAK 冷、比 SKY 灰
RIDGE = ['#252b4c', '#363e61', '#4b5578', '#6e7a9a', '#99a6bf']
# 灯照到的岩石：把上面那条蓝灰拉到暖灰，光才像是落在石头上
LITROCK = ['#5f5b63', '#7b7671', '#9a927f', '#b9ad90', '#d5c8a2']
# 披巾的灰绿：比 SAGE 再灰一点，不和冷夜抢色
MOSS = ['#2d3a30', '#485744', '#68795c', '#858f72', '#a5ad8e']
# 披风：比 CLOAK 略带紫，和灰袍、灰岩分开
CLOAK = ['#211f38', '#373355', '#4a456c', '#625d85', '#8a86a6']

# 六芒星：两个空心三角叠起来，尖上留出小亮三角，和终稿一样是线画的星
STAR = [
    '......o......',
    '.....o.o.....',
    '.....o.o.....',
    'ooooooooooooo',
    '.o..o...o..o.',
    '.o.o.....o.o.',
    '..oo.....oo..',
    '..o.......o..',
    '..oo.....oo..',
    '.o.o.....o.o.',
    '.o..o...o..o.',
    'ooooooooooooo',
    '.....o.o.....',
    '.....o.o.....',
    '......o......',
]


def lantern(c, x):
    """星灯：x 是灯的中线。提环、灯顶、蓝青菱印、发光灯窗和灯座，每块都不互相挡。"""
    # 提环两根立杆，上端接进手里
    c.paint(rect(x - 4, 36, x - 4, 41) | rect(x + 4, 36, x + 4, 41), GOLD[2])
    c.paint(rect(x - 5, 36, x - 5, 41) | rect(x + 5, 36, x + 5, 41), GOLD[1])
    # 灯顶
    cap = rect(x - 4, 40, x + 4, 41)
    c.paint(cap, GOLD[3])
    c.paint(rect(x - 4, 40, x + 4, 40), GOLD[4])
    roof = poly([(x - 10, 47), (x - 4, 42), (x + 4, 42), (x + 10, 47)])
    c.paint(roof, GOLD[3])
    c.paint(roof & (XX + YY < x + 42), GOLD[4])
    c.paint(roof & (XX > x + 4), GOLD[2])
    c.paint(rect(x - 10, 47, x + 10, 47), GOLD[3])
    c.paint(rect(x - 10, 48, x + 10, 48), GOLD[1])
    # 灯窗：窗面和星都发光；星用深金压在亮面上
    frame = rect(x - 8, 49, x + 8, 66)
    c.paint(frame, GOLD[3])
    c.paint(rect(x + 7, 49, x + 8, 66), GOLD[2])
    c.paint(rect(x - 8, 49, x - 8, 66) | rect(x + 8, 49, x + 8, 66), GOLD[1])
    pane = rect(x - 6, 49, x + 6, 65)
    c.glow(pane, GOLD[5])
    c.glow(c.sprite_mask(x - 6, 50, STAR), GOLD[1])
    # 灯座
    c.paint(rect(x - 9, 66, x + 9, 68), GOLD[2])
    c.paint(rect(x - 9, 66, x + 9, 66), GOLD[3])
    c.paint(rect(x - 9, 68, x + 9, 68), GOLD[1])
    c.paint(rect(x - 7, 69, x + 7, 69), GOLD[1])
    # 灯顶菱印：比宽略高，比灯顶小一圈
    gem_rows = [
        '...t...',
        '..tWt..',
        '.tWWTt.',
        'tWWTTTt',
        '.tTTTt.',
        '..tTt..',
        '...t...',
    ]
    c.paint(outer(c.sprite_mask(x - 3, 41, gem_rows)), TEAL[0])
    c.sprite(x - 3, 41, gem_rows, {'t': TEAL[2], 'W': TEAL[4], 'T': TEAL[3]}, glow='tWT')


def staff(c, pts):
    """手杖逐行摆：三列木色，每隔几行错一下，读成一根天然的木杆，而不是断开的线段。"""
    xs, ys = zip(*pts)
    for y in range(ys[0], ys[-1] + 1):
        x = int(round(np.interp(y, ys, xs)))
        c.px(x - 1, y, WOOD[3])
        c.px(x, y, WOOD[2])
        c.px(x + 1, y, WOOD[1])


def draw():
    c = Card('09_hermit')
    everything = rect(0, 0, W - 1, H - 1)

    # 夜空：只有一道过渡，藏在人身后的高度，背景要安静
    c.vgrad(everything, [NAVY[1], NAVY[2]], 50, 130, soft=0.1)

    # 两块远山：方块阶梯边
    far_r = blocky(poly([(82, 104), (104, 80), (111, 85), (111, 170), (82, 170)]), 2)
    c.paint(far_r, RIDGE[2])
    c.paint(far_r & (YY > 130), RIDGE[1])
    cap_r = far_r & blocky(poly([(92, 91), (104, 79), (112, 85), (112, 90), (107, 87), (101, 92), (103, 86)]), 2)
    c.paint(cap_r, RIDGE[4])
    far_l = blocky(poly([(0, 103), (8, 97), (24, 112), (40, 128), (48, 150), (48, 172), (0, 172)]), 2)
    c.paint(far_l, RIDGE[2])
    c.paint(far_l & (XX + YY > 150), RIDGE[1])
    cap_l = far_l & blocky(poly([(0, 103), (8, 96), (22, 109), (13, 106), (9, 111), (6, 105), (0, 109)]), 2)
    c.paint(cap_l, RIDGE[4])

    # 灯照：一块浅色楔形。天空部分最亮，山体部分压成暖灰，读出"光落在山上"
    wedge = poly([(15, 52), (0, 80), (0, 165), (17, 152), (29, 69)])
    c.rgrad(wedge & ~far_l, 23, 60, 72, [DUSK[4], DUSK[3], DUSK[2], DUSK[1]], soft=0.12)
    # 灯口附近的两档光也写进发光层：夜里灯下有一截短短的光锥，其余部分照常变暗
    for tone in (DUSK[4], DUSK[3]):
        c.glow(wedge & ~far_l & np.all(c.rgb == np.array(rgb(tone)), axis=2), tone)
    c.rgrad(wedge & far_l, 23, 60, 110, [LITROCK[3], LITROCK[3], LITROCK[2], LITROCK[1], LITROCK[0]], soft=0.12)
    c.paint(wedge & cap_l, IVORY[4])
    c.paint(wedge & cap_l & (XX > 13), IVORY[3])

    # 左下的岩坡
    cliff = blocky(poly([(0, 132), (12, 138), (20, 145), (31, 151), (40, 166), (34, 180), (28, 192), (0, 192)]), 2)
    c.paint(cliff, LITROCK[1])
    c.paint(cliff & (XX > 30 - (YY - 150) * 0.1), LITROCK[0])
    facet = blocky(poly([(32, 168), (44, 160), (42, 176), (24, 192), (20, 192)]), 2)
    c.paint(facet, RIDGE[2])

    # 被照亮的之字路：踏面亮、立面是受光的暖灰，从左下一路盘到山后
    road = poly([(0, 186), (2, 180), (11, 173), (17, 167), (8, 163), (0, 158), (5, 152), (12, 146), (6, 141), (0, 134), (6, 135), (16, 140), (24, 146), (30, 150),
                 (22, 154), (17, 158), (27, 164), (30, 169), (28, 172), (24, 180), (15, 186), (12, 191), (0, 191)])
    c.paint(road, LITROCK[3])
    treads = [
        [(0, 187), (15, 187), (12, 191), (0, 191)],
        [(3, 180), (24, 179), (21, 183), (1, 184)],
        [(12, 173), (28, 171), (26, 175), (11, 176)],
        [(17, 166), (29, 165), (30, 168), (18, 169)],
        [(8, 161), (20, 160), (27, 162), (15, 164)],
        [(0, 156), (16, 154), (20, 157), (2, 158)],
        [(9, 150), (29, 149), (22, 152), (5, 154)],
        [(12, 145), (24, 145), (26, 147), (16, 147)],
        [(6, 140), (16, 139), (16, 142), (8, 143)],
        [(0, 133), (6, 134), (10, 138), (0, 136)],
    ]
    for t in treads:
        c.paint(poly(t), DUSK[4])

    # 脚下的雪岩：岩体在下，雪只盖上半，几道雪舌顺坡流下
    rock = blocky(poly([(24, 192), (30, 172), (36, 166), (46, 162), (60, 160), (80, 158), (96, 157), (103, 156), (111, 158), (111, 192)]), 2)
    c.paint(rock, RIDGE[2])
    for f in ([(84, 178), (100, 170), (111, 168), (111, 192), (96, 192)], [(30, 192), (38, 176), (50, 182), (44, 192)]):
        c.paint(blocky(poly(f), 2) & rock, RIDGE[1])
    cap = poly([(30, 171), (36, 166), (46, 162), (60, 160), (66, 159), (80, 158), (88, 160), (96, 157), (103, 156), (111, 158), (111, 164), (106, 165), (97, 171), (88, 175), (75, 172), (65, 175), (55, 173), (46, 172), (38, 175), (33, 174)])
    snow = cap | poly([(46, 172), (55, 173), (47, 182), (39, 185), (44, 177)])
    snow |= poly([(75, 172), (88, 175), (97, 171), (100, 180), (106, 189), (96, 180), (85, 178), (78, 177), (72, 179)])
    snow = blocky(snow, 2)
    c.paint(snow, WHITE[2])
    c.paint(snow & ~shift(snow, 0, -3), WHITE[3])
    c.paint(snow & ~shift(snow, 0, 2), WHITE[1])
    # 人影落在雪上：蓝灰一片，顺着楔形光的反方向
    shade = blocky(poly([(60, 164), (66, 162), (78, 163), (98, 165), (88, 169), (74, 170), (64, 166)]), 2) & snow
    c.paint(shade, RIDGE[4])

    # 尾巴：从披风后探出，先细后粗
    tl = taper([(90, 104), (93, 112), (100, 120), (104, 127), (103, 132), (99, 135)], 3, 5)
    c.paint(outer(tl) & (XX > 90), RIDGE[0])
    c.paint(tl, HAIR_SILVER[3])
    c.paint(tl & ~shift(tl, 1, 1), HAIR_SILVER[4])
    c.paint(tl & ~shift(tl, -1, -1), HAIR_SILVER[2])

    # 披风整体：左臂带出的大袖 + 身体
    cloak = poly([(33, 37), (37, 42), (41, 43), (47, 41), (53, 38), (58, 35), (62, 33), (76, 33), (87, 33), (88, 38), (90, 40), (96, 66), (96, 72), (92, 90), (90, 105), (94, 130), (99, 155), (88, 160), (72, 158), (60, 158), (42, 150), (37, 139), (39, 128), (42, 111), (36, 100), (31, 88), (31, 60), (31, 46)])
    c.shape(cloak, CLOAK, base=1, shadow=1, hi=0)
    # 受灯光的一条亮边
    c.paint(edge(cloak) & (XX < 44) & (YY > 60), CLOAK[2])
    # 大袖下的长暗褶，尖端朝下
    c.paint(poly([(33, 60), (38, 62), (39, 75), (45, 88), (51, 74), (46, 100), (42, 111), (36, 88)]) & cloak, CLOAK[0])
    # 披肩下的暗楔
    c.paint(poly([(47, 50), (54, 57), (58, 52), (51, 68), (48, 58)]), CLOAK[0])
    # 下摆内衬
    c.paint(poly([(37, 139), (48, 130), (48, 146), (60, 148), (60, 151), (75, 152), (86, 149), (99, 155), (88, 160), (72, 158), (60, 158), (42, 150)]) & cloak, CLOAK[0])
    # 右侧两道长暗褶
    c.paint(poly([(80, 96), (90, 97), (90, 105), (92, 125), (85, 104)]) & cloak, CLOAK[0])
    c.paint(poly([(80, 110), (82, 140), (86, 149), (78, 149)]) & cloak, CLOAK[0])
    c.paint(poly([(72, 62), (75, 60), (76, 100), (75, 148), (72, 139), (72, 100)]) & cloak, CLOAK[0])

    # 大袖的灰衬里：朝向灯的那一面
    lining = poly([(32, 37), (37, 42), (38, 61), (33, 57), (31, 46)])
    c.paint(lining, STONE[3])
    c.paint(lining & (XX >= 35) & (YY > 44), STONE[4])
    c.paint(line([(32, 38), (37, 42), (38, 61)]), STONE[2])
    c.paint(line([(33, 46), (34, 56)]), STONE[1])

    # 内袍
    robe = poly([(58, 74), (62, 74), (62, 100), (66, 110), (67, 136), (72, 139), (73, 145), (85, 150), (72, 151), (60, 149), (48, 146), (48, 130), (50, 127), (52, 100)])
    c.paint(robe, STONE[3])
    c.paint(robe & (XX + YY * 0.2 > 86) & (YY > 100), STONE[2])
    c.fold([(56, 96), (57, 120), (59, 148)], STONE[1], robe)
    c.paint(edge(robe) & ((YY > 140) | (XX < 54)), STONE[1])

    # 靴子
    boot_l = poly([(52, 147), (60, 147), (60, 162), (52, 163), (44, 164), (44, 160), (49, 158), (52, 155)])
    boot_r = poly([(70, 151), (76, 151), (76, 166), (67, 166), (68, 162), (70, 158)])
    c.shape(boot_l, LEATHER, base=2, shadow=1, hi=0)
    c.shape(boot_r, LEATHER, base=2, shadow=1, hi=0)
    c.paint(rect(44, 163, 60, 164) & grow(boot_l), LEATHER[0])
    c.paint(rect(67, 166, 76, 166), LEATHER[0])
    c.paint(line([(52, 156), (59, 156)]), LEATHER[1])
    c.paint(line([(70, 158), (75, 158)]), LEATHER[1])

    # 兜帽放下，堆在肩后：右大、左小，夹着脖子
    hood_r = poly([(76, 33), (87, 33), (88, 38), (90, 40), (82, 42), (74, 46), (70, 46)])
    c.paint(hood_r, CLOAK[2])
    c.paint(hood_r & ~shift(hood_r, 0, -1), CLOAK[3])
    c.paint(line([(72, 45), (80, 41), (90, 40)]), CLOAK[0])
    hood_l = poly([(41, 43), (47, 41), (53, 38), (58, 35), (63, 45), (58, 50), (53, 53), (47, 47)])
    c.paint(hood_l, CLOAK[2])
    c.paint(hood_l & ~shift(hood_l, 0, -1), CLOAK[3])
    c.paint(line([(42, 44), (47, 47), (53, 54), (60, 50)]), CLOAK[0])
    c.paint(poly([(58, 35), (62, 33), (66, 42), (64, 46), (60, 42)]), CLOAK[0])

    # 脖子
    neck = poly([(65, 31), (71, 29), (73, 33), (68, 43), (65, 37)])
    c.paint(neck, SKIN[2])
    c.paint(neck & (XX + YY * 0.6 < 85), SKIN[1])

    # 绿色披巾：左侧一片小领，右侧一片斜跨到腰，再垂下一段
    lapel = poly([(61, 37), (63, 36), (67, 43), (65, 46)])
    c.paint(lapel, MOSS[2])
    c.paint(edge(lapel), MOSS[0])
    # 垂下的长片压在腰带后
    stole2 = poly([(67, 58), (71, 46), (72, 105), (66, 111), (65, 101), (60, 97), (62, 75)])
    c.shape(stole2, MOSS, base=3, shadow=1, hi=0)
    c.paint(line([(68, 103), (71, 106)]), MOSS[1])
    belt = poly([(58, 68), (67, 68), (66, 74), (58, 74)])
    c.shape(belt, MOSS, base=3, shadow=0, hi=0)
    c.paint(line([(59, 71), (65, 70)]), MOSS[1])
    stole = poly([(73, 30), (76, 33), (72, 46), (67, 58), (58, 68), (60, 56), (64, 46), (67, 42)])
    c.shape(stole, MOSS, base=3, shadow=1, hi=0)
    c.paint(line([(70, 44), (66, 54), (63, 60)]), MOSS[1])

    # 右手袖：袖子拱起一条暗弧，灰袖口垂下
    c.paint(line([(81, 68), (86, 62), (91, 64), (95, 72)]) & cloak, CLOAK[0])
    c.paint(poly([(80, 76), (85, 76), (83, 84), (87, 96), (81, 96)]), CLOAK[0])
    cuff = poly([(85, 71), (88, 72), (90, 76), (90, 87), (87, 95), (83, 84), (85, 78)])
    c.paint(cuff, STONE[3])
    c.paint(line([(87, 76), (87, 88)]), STONE[1])
    c.paint(edge(cuff) & (XX > 88), STONE[2])

    # 手杖：从手心到岩面，一根连贯的木杆
    staff(c, [(80, 56), (79, 62), (80, 70), (79, 84), (79, 100), (78, 108), (78, 120), (79, 128), (78, 140), (77, 152), (76, 167)])

    # 握杖手：四指横过杖身，拇指压在上面
    grip = [
        '..lllllll..',
        '.llllllllls',
        'lxxxxxxllls',
        '.llllllllss',
        'lxxxxxxllss',
        '.llllllllss',
        'lxxxxxxlssd',
        '.lllllllsd.',
        '...ssssdd..',
    ]
    c.sprite(76, 68, grip, {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0]})

    # 举灯的小臂、手和星灯
    forearm = poly([(27, 37), (31, 36), (33, 41), (33, 46), (29, 42)])
    c.paint(forearm, SKIN[3])
    c.paint(forearm & (XX + YY > 73), SKIN[2])
    lantern(c, 23)
    lift = [
        '....llll.....',
        '..llllllll...',
        '.lllllllllll.',
        '.lllllxxllll.',
        'lllllxlllllll',
        'lxlxlxlllllss',
        'lxlxlxxxslsss',
        '.l.l.l...ssss',
    ]
    c.sprite(18, 30, lift, {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0]})

    # 头：四分之三侧朝左，低头垂目。银发在深夜空前面不需要深色外线，只在贴脸处压一条浅线
    hs = HAIR_SILVER
    soft = [hs[1], hs[1], hs[2], hs[3], hs[4]]
    # 后发一束，从脑后结到肩上
    pony = taper([(74, 26), (79, 28), (84, 34)], 4, 1) | taper([(75, 28), (78, 31), (81, 37)], 3, 1)
    c.paint(pony, hs[2])
    c.paint(pony & ~shift(pony, 0, -1), hs[3])
    c.paint(pony & ~shift(pony, 0, 1), hs[1])
    # 脑后的发块，顺着往后收
    back = blob([(58, 15), (63, 13), (70, 13), (75, 17), (77, 22), (76, 27), (72, 30), (68, 28), (58, 22)])
    c.paint(back, hs[2])
    c.paint(back & ~shift(back, -1, -1), hs[1])
    head(c, 61, 13, '3q', 'L', soft, GREY_IRIS, eyes='down', mouth='line', flip=True, ears=False, brow=hs[2], lash='#3d3a55')
    # 精灵不画耳时留下两根暗刺，抹回夜空；真耳朵后面再压
    c.paint(rect(70, 12, 73, 15) & ~back, NAVY[1])
    c.paint(rect(56, 12, 60, 15) & ~back, NAVY[1])
    # 一点笑意：嘴角上提一格
    c.px(60, 29, SKIN[0])
    # 额前的发：从头顶向左下扫，只盖到眉上；左边落下一绺，右边鬓角一绺
    fringe = poly([(56, 18), (60, 15), (68, 15), (72, 17), (73, 21), (70, 22), (64, 21), (60, 22), (58, 22), (57, 27), (55, 31), (54, 26)])
    side = poly([(68, 21), (71, 22), (71, 27), (69, 31), (68, 26)])
    tuft = poly([(56, 21), (58, 22), (56, 28), (54, 30), (54, 25)])
    front = fringe | side | tuft
    c.paint(front, hs[2])
    c.paint(curve([(57, 19), (62, 17), (69, 18)]) & fringe, hs[3])
    c.paint(curve([(60, 18), (64, 17), (67, 17)]) & fringe, hs[4])
    c.paint(edge(front) & (YY > 21) & (XX < 72), hs[1])
    # 两只猫耳压在发上：后耳高而窄，前耳向左上挑；内耳只占耳朵的一半，不抢脸
    ear_b = poly([(63, 16), (68, 6), (72, 15)])
    c.paint(ear_b, hs[3])
    c.paint(ear_b & (XX >= 69), hs[2])
    c.paint(poly([(65, 15), (68, 10), (70, 15)]), PINK[2])
    c.paint(poly([(67, 15), (68, 12), (69, 15)]), PINK[1])
    ear_f = poly([(54, 17), (54, 7), (61, 15)])
    c.paint(ear_f, hs[3])
    c.paint(poly([(56, 15), (56, 11), (59, 15)]), PINK[2])
    # 束发结
    c.paint(rect(74, 27, 75, 28), hs[0])

    return c
