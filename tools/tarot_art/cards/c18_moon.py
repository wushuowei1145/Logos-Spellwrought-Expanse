"""18 月亮：月盘里的闭眼人面占满上方，八道简化光芒；三枚蓝青碎片悬在路径上方。
双塔之间一条曲折陆路消失在远山；短毛家猫安静、长毛猫警觉，都是完整的猫，都朝月仰望；
前景才是水池，螯虾从深水朝岸爬出。"""
from figures import *

# 月夜里偏灰的草色，从暗到亮
MOSS = ['#25393a', '#37544f', '#4d6a58', '#6e8066', '#8c9a70']


# 短毛家猫：坐姿、脖子拉长抬头。o 轮廓，w/W/g/G 毛色四档，p 内耳，e/k 眼，n 鼻
WHITE_CAT = [
    '...................o.......',
    '.............o.....oo......',
    '.............oo....ogo.....',
    '.............owo...oggo....',
    '.............opwo..ogggo...',
    '.............oppwooggoooo..',
    '..............oppwwwwwwwwo.',
    '..............opwwwooowwwno',
    '...............owwwekewwwwo',
    '...............owwwwwwwwwgo',
    '...............owwwwwwwwggo',
    '................owwwwwwwwo.',
    '................ogwwwwwwo..',
    '................ogwwwwwwo..',
    '................ogwwwwwwo..',
    '................ogwwwwwwo..',
    '...............oWwwwwwwwo..',
    '.............ooWwwwwwwwwo..',
    '...........ooWWwwwwwwwwwwo.',
    '..........oWWWwwwwwwwwwwwo.',
    '.........oWWwwwwwwwwwwwwwo.',
    '........oWWwwwwwwwwwwwwwwo.',
    '.......oWWwwwwwwwwwwwwwwwo.',
    '......oWWwwwwwwwwwwwwwwwo..',
    '......oWwwwwwwwgwwwwgwwwo..',
    '.....oWwwwwwwwwgwwwwgwwGo..',
    '.....oWwwggggwwgwwwwgwGGo..',
    '....oWwwwwwwwgwgwwwwgGGo...',
    '....oWwwwwwwwwgGowwwoGGo...',
    '....oWwwwwwwwwgGowwwoGGo...',
    '....oWwwwwwwwwgGowwwoGGo...',
    '....oWwwwwwwwwgGGowwoGGo...',
    '...oWwwwwwwwwwgGGowwoGGo...',
    '...oWwwwwwwwwwgGGowwoGGo...',
    '...oWwwwwwwwwwgGGowwoGGo...',
    '..oWwwwwwwwwwwgGGowwoGGo...',
    '.oWwwgGGGGGGwwwgGowwwowwo..',
    '.owwogGGGGGGwwwwowwwwowwo..',
    'oWwwoooooooooooowgwgoggo...',
    'oWwwwo..........ooooooooo..',
    '.owwwwoooooooooo...........',
    '.oWWwwwwwwwwwwwwwoo........',
    '..owwwwwwwwwwwwwwwwo.......',
    '...ooggggggggggggggo.......',
    '.....oooooooooooooo........',
]


def sym(m):
    """把只画了左半的蒙版镜像成严格左右对称（x 与 111-x）。"""
    return m | mirror(m)


def draw_moon(c, cx, cy, r):
    disc = ellipse(cx, cy, r)
    # 八道光芒：四正是三角，四斜是方条，和盘面留一道暗缝
    rays = blank()
    rays |= sym(poly([(52, 4), (55, 4), (55, 0)]))
    rays |= sym(poly([(52, 68), (55, 68), (55, 75)]))
    rays |= sym(poly([(9, cy), (23, cy - 4), (23, cy + 4)]))
    rays |= sym(poly([(29, 16), (32, 13), (22, 3), (19, 6)]))
    rays |= sym(poly([(29, 56), (32, 59), (22, 69), (19, 66)]))
    c.glow(rays, DUSK[3])
    # 迎光的亮边：只提上沿和左沿一条
    c.glow(rays & ~shift(rays, 1, 1), DUSK[4])

    c.paint(disc, DUSK[3])
    # 人面：只留头发大块和几条曲线，脸朝左、闭眼
    y = 1  # 月盘比参考图低一格
    def P(pts):
        return [(px, py + y) for px, py in pts]
    # 头发主块
    hair = blob(P([(60, 10), (65, 8), (75, 12), (81, 17), (85, 29), (85, 35), (84, 45), (78, 53), (68, 61), (57, 63), (50, 62), (60, 59), (66, 52), (70, 45), (71, 36), (70, 30), (63, 20), (59, 15)]))
    # 颈侧阴影
    neck = poly(P([(60, 48), (62, 47), (65, 54), (60, 59), (50, 61), (45, 61), (54, 58), (58, 53)]))
    # 前刘海两片
    bang1 = blob(P([(65, 8), (54, 12), (49, 18), (48, 24), (48, 28), (50, 21), (54, 16), (59, 12)]))
    bang2 = blob(P([(60, 11), (54, 17), (52, 23), (52, 28), (54, 31), (55, 28), (55, 25), (57, 19), (59, 15)]))
    # 鬓角：贴着脸颊的月牙
    side = blob(P([(69, 29), (71, 32), (70, 38), (66, 42), (62, 44), (66, 39), (68, 34)]))
    tone = DUSK[1]
    for m in (hair, neck, bang1, bang2, side):
        c.paint(m & disc, tone)
    # 发丝间的亮线
    for pts in [P([(64, 10), (68, 18), (75, 23), (85, 30)]),
                P([(71, 33), (77, 43), (77, 49), (72, 58)]),
                P([(52, 62), (63, 57), (70, 46)])]:
        c.paint(curve(pts) & disc, DUSK[3])
    # 侧脸轮廓：额、鼻、唇、下巴、下颌，一格一格摆
    line_tone = DUSK[0]
    c.pxs([(54, 32), (53, 33), (52, 34), (51, 35), (50, 35), (49, 36),
           (50, 37), (50, 38), (51, 39),
           (50, 40), (50, 41), (51, 42), (52, 42), (53, 42),
           (51, 43), (51, 44), (52, 45), (52, 46), (52, 47), (53, 48), (54, 49),
           (55, 49), (56, 49), (57, 49), (58, 49), (59, 48), (60, 48), (61, 48)], line_tone)
    # 闭着的眼：一道下弯的弧，尾端两格睫毛
    c.pxs([(57, 31), (58, 32), (59, 32), (60, 32), (61, 32), (62, 31), (63, 31)], line_tone)
    # 眉
    c.pxs([(57, 27), (58, 26), (59, 26), (60, 26), (61, 26), (62, 27), (63, 27)], DUSK[1])

    # 月盘外缘发光：两层，夜里只亮这一圈
    rim = disc & ~shrink(disc, 2)
    c.glow(rim, DUSK[4])
    return disc


def fog(c, pts, tone):
    """雾带：方块阶梯边的平带，上沿提亮一道。"""
    m = blocky(poly(pts), 2)
    c.paint(m, tone)
    c.paint(m & ~shift(m, 0, 1), SLATE[4])


def draw():
    c = Card('18_moon')
    everything = rect(0, 0, W - 1, H - 1)
    c.paint(everything, NAVY[1])

    # 一层：月盘
    draw_moon(c, 55.5, 36, 30)

    # 三枚蓝青碎片：互不相连，自上而下渐小
    c.node(55, 81, 3)
    c.node(56, 89, 2)
    c.node(55, 95, 1)

    # 雾带：上层左右成对，下层错开高度，不能连成一道墙
    upper = [(0, 68), (12, 68), (17, 70), (12, 73), (5, 74), (8, 76), (22, 77), (42, 80), (24, 80), (6, 79), (0, 77)]
    fog(c, upper, SLATE[3])
    fog(c, flip_pts(upper), SLATE[3])
    fog(c, [(0, 98), (12, 98), (12, 100), (0, 101)], SLATE[3])
    fog(c, [(30, 98), (40, 96), (47, 98), (53, 101), (30, 101)], SLATE[3])
    fog(c, [(58, 96), (66, 93), (76, 92), (80, 95), (66, 96)], SLATE[3])
    fog(c, [(98, 93), (111, 92), (111, 96), (98, 96)], SLATE[3])

    # 远山两块，方块阶梯边
    peak_b = blocky(poly([(50, 113), (65, 101), (80, 113), (80, 122), (50, 122)]), 2)
    c.paint(peak_b, SKY[3])
    c.paint(peak_b & ~shift(peak_b, 0, 2), SKY[4])
    peak_a = blocky(poly([(32, 116), (48, 105), (64, 116), (64, 124), (32, 124)]), 2)
    c.paint(peak_a, SKY[2])
    c.paint(peak_a & ~shift(peak_a, 0, 2), SKY[3])

    # 二层：双塔，方块感。先画左塔，再按 111-x 复制到右边
    tower_ramp = ['#8c7661', '#ad967a', '#c7b092', '#dac6a7', '#e8d9bd']
    side_ramp = ['#2f2c44', '#45415c', '#5a5670', '#716d85', '#8a869c']
    face = rect(11, 91, 27, 120)
    side = rect(28, 91, 33, 120)
    merlons = rect(9, 83, 13, 87) | rect(16, 83, 20, 87) | rect(23, 83, 27, 87) | rect(29, 83, 33, 87)
    crown = rect(9, 87, 33, 91) | merlons
    c.stone_bricks(face, tower_ramp, bw=7, bh=4, seed=41, base=2)
    c.stone_bricks(side, side_ramp, bw=5, bh=4, seed=42, base=2)
    c.paint(crown & (XX <= 27), tower_ramp[3])
    c.paint(crown & (XX > 27), side_ramp[2])
    c.paint(crown & ~shift(crown, 0, 1) & (XX <= 27), tower_ramp[4])
    c.paint(crown & ~shift(crown, 0, 1) & (XX > 27), side_ramp[3])
    c.paint(rect(9, 91, 27, 91), tower_ramp[1])
    c.paint(rect(28, 91, 33, 91), side_ramp[0])
    window = rect(17, 97, 20, 103)
    c.paint(outer(window), tower_ramp[1])
    c.paint(window, NAVY[0])
    c.pxs([(17, 97), (20, 97)], tower_ramp[1])
    tower = face | side | crown | outer(window) | window
    for yy, xx in zip(*np.nonzero(tower)):
        c.rgb[yy, 111 - xx] = c.rgb[yy, xx]

    # 地：最远的暗绿坡，陆路的尽头藏在它和远山之间
    back = blocky(poly([(33, 114), (45, 116), (52, 118), (60, 115), (70, 112), (79, 110), (79, 126), (33, 126)]), 2)
    c.paint(back, MOSS[1])
    # 左右两片中景草坡
    hill_l = blocky(poly([(0, 112), (10, 116), (30, 118), (46, 122), (60, 128), (60, 150), (0, 150)]), 2)
    hill_r = blocky(poly([(112, 114), (100, 113), (90, 115), (78, 118), (66, 122), (50, 128), (50, 150), (112, 150)]), 2)
    for m, seed in ((hill_l, 43), (hill_r, 44)):
        c.paint(m, MOSS[3])
        c.speckle(m, [MOSS[2], MOSS[4]], 0.14, seed)
    # 猫身后压暗的一块，把白猫托出来
    dark_l = blocky(poly([(0, 120), (12, 124), (24, 130), (36, 138), (40, 146), (34, 150), (0, 150)]), 2)
    c.paint(dark_l, MOSS[2])
    c.speckle(dark_l, [MOSS[1], MOSS[3]], 0.12, 45)

    # 曲折陆路：从池边一路折到双塔之间，收成一线消失在远山脚下
    road = poly([(43, 153), (38, 147), (38, 143), (34, 138), (31, 135), (33, 132), (40, 130), (50, 128), (56, 128), (57, 126), (52, 124), (45, 123), (44, 121), (50, 120), (57, 119), (60, 118),
                 (60, 119), (52, 121), (49, 122), (54, 123), (60, 124), (65, 126), (66, 128), (63, 130), (55, 132), (50, 134), (51, 136), (56, 139), (64, 142), (75, 146), (66, 147), (62, 150), (61, 153)])
    c.paint(road, SAND[3])
    c.speckle(road, [SAND[2], SAND[4]], 0.10, 46)
    c.paint(road & ~shift(road, 0, 1) & (YY > 124), SAND[4])

    # 三层往前：水池。岸是草方块：草面、垂边、一条泥土侧面，水在最下面
    pond = rect(0, 152, W - 1, H - 1)
    c.paint(pond, NAVY[2])
    c.water(pond, NAVY, seed=47, base=2)
    facet = blocky(poly([(8, 174), (28, 168), (36, 172), (18, 178)]) | poly([(0, 184), (18, 180), (28, 186), (12, 190)])
                   | poly([(103, 174), (83, 168), (75, 172), (93, 178)]) | poly([(111, 184), (93, 180), (83, 186), (99, 190)]), 2)
    c.paint(facet, NAVY[1])
    bank_l = blocky(poly([(0, 150), (12, 147), (24, 146), (34, 148), (43, 153), (36, 155), (28, 156), (24, 159), (12, 160), (0, 165)]), 2)
    bank_r = blocky(poly([(112, 150), (98, 148), (84, 146), (72, 147), (61, 153), (68, 155), (80, 156), (84, 159), (98, 160), (112, 165)]), 2)
    for m, seed in ((bank_l, 48), (bank_r, 49)):
        cliff = (shift(m, 0, 3) | shift(m, 0, 2) | shift(m, 0, 1)) & ~m & (YY > 150)
        c.soil(cliff, DIRT, seed=seed + 10, base=1)
        c.paint(m, MOSS[3])
        c.speckle(m, [MOSS[4]], 0.30, seed)
        c.paint(m & ~shift(m, 0, 1), MOSS[4])
        c.grass_lip(m, [MOSS[1], MOSS[2], MOSS[2], MOSS[3], MOSS[4]], seed=seed + 20, length=2)

    # 月光倒影：两片收尖的亮斑，夜里也亮。两边不等长，不要像两根棍子
    lens_a = rect(46, 154, 61, 154) | rect(38, 155, 69, 155) | rect(44, 156, 63, 156)
    lens_b = rect(48, 159, 70, 159) | rect(33, 160, 79, 160) | rect(38, 161, 68, 161) | rect(47, 162, 58, 162)
    c.glow(lens_a | lens_b, DUSK[3])
    c.glow(rect(49, 154, 58, 154) | rect(52, 159, 66, 159), DUSK[4])

    # 猫的落影
    c.paint(ellipse(16, 150, 11, 1) & bank_l, MOSS[2])
    c.paint(ellipse(85, 150, 18, 1) & bank_r, MOSS[2])

    # 短毛家猫：安静地坐着，抬头望月
    cat_line = NAVY[0]
    c.sprite(5, 111, WHITE_CAT, {'o': cat_line, 'w': IVORY[4], 'W': IVORY[3], 'g': IVORY[2], 'G': IVORY[1], 'p': PINK[2], 'e': GOLD[3], 'k': INK, 'n': PINK[1]})

    # 长毛猫：警觉地站着，同样抬头望月。躯干用多边形，颈毛和尾巴的尖刺都是手写的顶点
    far_a = poly([(72, 134), (76, 139), (73, 147), (72, 148), (68, 148), (68, 146), (71, 145)])
    far_b = poly([(85, 136), (90, 137), (91, 141), (88, 148), (85, 148), (85, 146), (87, 143), (87, 140)])
    body = poly([(76, 122), (81, 120), (83, 123), (86, 125), (90, 125), (96, 127), (98, 131), (98, 135), (100, 138), (102, 141), (103, 148), (103, 150), (99, 150), (99, 148), (98, 143), (92, 141), (90, 138), (91, 136), (88, 139), (87, 136), (85, 139), (83, 137), (81, 139), (80, 147), (80, 150), (76, 150), (76, 148), (78, 146), (78, 139), (74, 137), (71, 135), (69, 130), (70, 124)])
    ruff = poly([(69, 122), (70, 125), (67, 126), (70, 127), (67, 130), (70, 130), (68, 133), (71, 132), (71, 136), (73, 134), (75, 138), (76, 135), (77, 137), (78, 133), (80, 129), (80, 126), (78, 124), (76, 122)])
    mane = poly([(77, 119), (81, 120), (80, 121), (84, 123), (82, 124), (87, 125), (84, 127), (88, 131), (82, 129), (79, 127)])
    tail_all = poly([(97, 130), (98, 134), (100, 137), (102, 135), (103, 137), (105, 135), (107, 136), (107, 133), (110, 131), (108, 128), (110, 126), (108, 123), (108, 120), (105, 119), (104, 118), (101, 118), (97, 118), (95, 120), (97, 123), (100, 121), (103, 124), (103, 129), (100, 131)])
    tail_core = taper([(97, 131), (101, 133), (105, 130), (106, 125), (103, 121), (96, 120)], 4, 2) & tail_all
    head_spr = [
        '.........o.....o.',
        '.........oo...oo.',
        '........odo..oao.',
        '........oddooaao.',
        '.......odddoaaao.',
        '...oooooodboaaao.',
        '..obbbbbbbbbbaao.',
        'onbbDDDbbbbbbbao.',
        'obbbbekebbbbbbo..',
        'obbbbbbbbbbbbbo..',
        'obDDbbbbbbbbbbbo.',
        '.oddbbbbbbbbbbbb.',
        '..ooddbbbbbbbbbb.',
        '....dddbbbbbbbbb.',
    ]
    head_mask = c.sprite_mask(66, 111, [r.replace('o', '.') for r in head_spr])
    union = far_a | far_b | body | ruff | mane | tail_all | head_mask
    c.paint(outer(union), cat_line)
    for m in (far_a, far_b):
        c.paint(m, SLATE[1])
    c.paint(tail_all, SLATE[1])
    c.paint(tail_core, SLATE[2])
    c.paint(body, SLATE[2])
    c.paint(mane, SLATE[1])
    c.paint(ruff, SLATE[3])
    c.paint(ruff & ~shift(ruff, 0, 1), SLATE[4])
    c.paint(ruff & ~shift(ruff, 0, -1), SLATE[2])
    # 颈毛里两道暗线，腹下几撮
    for pts in [[(70, 128), (73, 130)], [(73, 133), (75, 130)], [(77, 128), (75, 126)], [(83, 133), (85, 137)], [(87, 132), (88, 137)], [(92, 134), (93, 139)]]:
        c.paint(line(pts), SLATE[1])
    c.sprite(66, 111, head_spr, {'o': cat_line, 'b': SLATE[3], 'd': SLATE[2], 'D': SLATE[0], 'a': DUSK[3], 'e': GOLD[4], 'k': INK, 'n': PINK[1]})
    # 爪趾
    c.pxs([(77, 149), (78, 149), (100, 149), (101, 149), (69, 147), (70, 147), (86, 147), (87, 147)], SLATE[0])

    # 四层：螯虾，从深水朝岸爬，左右对称
    c.paint(ellipse(55.5, 190, 24, 3) & pond, NAVY[1])
    legs = blank()
    for pts in ([(51, 180), (44, 178), (37, 182)], [(51, 183), (44, 181), (35, 186)], [(51, 186), (45, 185), (38, 190)]):
        legs |= taper(pts, 2.4, 1)
    legs = sym(legs)
    c.paint(legs, RED[3])
    ant = sym(curve([(54, 170), (52, 166), (47, 161), (41, 157)]))
    c.paint(ant, RED[2])
    # 大螯：外指粗、内指细，中间留缝
    claw = ['.....R....',
            '....RR.r..',
            '...RRR.rr.',
            '..RRRR.rr.',
            '.RRRRr.rrr',
            '.RRRRr.rrr',
            'RRRRRRrrrr',
            'RRRRRRRrrr',
            'DRRRRRRRr.',
            '.DRRRRRR..',
            '..DDRRRD..',
            '...DDDD...']
    pal = {'R': RED[4], 'r': RED[3], 'D': RED[2]}
    arm = sym(line([(45, 175), (47, 178), (50, 176)], 3))
    c.paint(arm, RED[2])
    c.sprite(40, 164, claw, pal)
    c.sprite(62, 164, claw, pal, flip=True)
    # 头胸甲
    carapace = sym(poly([(55, 169), (53, 170), (51, 174), (50, 179), (51, 181), (55, 182)]))
    c.paint(outer(carapace) & (YY > 168), RED[0])
    c.paint(carapace, RED[3])
    c.paint(carapace & (XX < 54) & (YY < 178), RED[4])
    c.paint(rect(55, 171, 56, 177), RED[2])
    # 三节腹：每节一道暗缝，一节比一节窄
    for yy, half in [(183, 5), (185, 4), (187, 3)]:
        c.paint(sym(rect(55 - half, yy, 55, yy + 1)), RED[3])
        c.paint(sym(rect(55 - half, yy + 1, 55, yy + 1)), RED[1])
        c.paint(sym(rect(55 - half, yy, 55 - half + 1, yy)), RED[4])
    # 尾扇：中叶加两对侧叶，读得出来
    fan = sym(poly([(55, 189), (52, 189), (47, 191), (55, 191)]))
    c.paint(fan, RED[3])
    c.paint(sym(line([(53, 190), (52, 191)])) | sym(line([(50, 190), (49, 191)])), RED[1])
    # 眼
    c.pxs([(52, 171), (59, 171)], INK)

    return c
