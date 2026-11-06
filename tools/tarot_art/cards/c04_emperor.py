"""4 皇帝：赤褐发猫耳男性正面端坐石王座，左手 Ankh 权杖、右手托蓝色宝球；王座四处羊角浮雕，
红披风加铠甲，落地双靴踩在台阶上，身后荒山。方正、低重心，除持物和尾巴外基本对称。"""
from figures import *

# 王座暖灰、铠甲亮灰：两层石头和金属要分得开
THRONE = ['#35302f', '#544d4b', '#736b68', '#8d8481', '#ada49f']
BRICK = [THRONE[1], THRONE[2], THRONE[3], THRONE[3], THRONE[4]]
ARMOR = ['#45434a', '#74707a', '#a29ea2', '#c9c5c4', '#efece6']
RAM_PAL = {'o': THRONE[0], 'W': IVORY[2], 'm': THRONE[3], 'd': THRONE[1]}
RAM = [
    '..oooooo...oooooo..',
    '.oWWWWWWoooWWWWWWo.',
    'oWWWWWWWWWWWWWWWWWo',
    'oWWWWmmWWWWWmmWWWWo',
    'oWWWmddmWWWmddmWWWo',
    'oWWmdddmWWWmdddmWWo',
    'oWWmdWWdmWmdWWdmWWo',
    'oWWWWWWomWmoWWWWWWo',
    '.oWWWWo.mWm.oWWWWo.',
    '..oooo..mWm..oooo..',
    '.......oWWWo.......',
    '........ooo........',
]


def both(pts):
    """一片形状和它的镜像，x 与 111-x 成对。"""
    m = poly(pts)
    return m | mirror(m)


def plate(c, pts, base=3, shadow=2, hi=1, ramp=ARMOR):
    """一块硬甲或石面，左右各画一次，光都从左上来。"""
    m = poly(pts)
    for side in (m, mirror(m)):
        c.shape(side, ramp, base=base, shadow=shadow, hi=hi)
    return m


def thick(m):
    """2 像素宽的线：叠一条平移的，走向更整齐。"""
    return m | shift(m, 1, 0) | shift(m, 0, 1)


def stud(c, x, y, r=3):
    """披风扣：金菱，发光。"""
    m = (np.abs(XX - x) + np.abs(YY - y)) <= r
    c.paint(outer(m), GOLD[0])
    c.glow(m, GOLD[3])
    c.glow(m & (XX <= x) & (YY <= y), GOLD[4])


def draw():
    c = Card('04_emperor')
    everything = rect(0, 0, W - 1, H - 1)

    # 天空：暖金平涂，只留一条抖动
    c.vgrad(everything, [DUSK[3], DUSK[3], DUSK[2]], 0, 135, soft=0.08)

    # 荒山：方块阶梯边，上深下浅
    range_far = blocky(poly([(0, 34), (20, 17), (30, 25), (42, 12), (56, 0), (70, 12), (82, 26), (94, 17), (111, 32), (111, 135), (0, 135)]), 2)
    c.vgrad(range_far, [DUSK[1], DUSK[1], DUSK[2], DUSK[3]], 20, 135, soft=0.08)
    c.paint(range_far & ~shift(range_far, 0, 2) & (YY < 40), DUSK[2])
    range_near = blocky(poly([(0, 60), (17, 46), (30, 56), (30, 135), (0, 135)]), 2) | blocky(poly([(84, 62), (98, 53), (111, 68), (111, 135), (84, 135)]), 2)
    c.vgrad(range_near, [DUSK[0], DUSK[1], DUSK[2]], 50, 135, soft=0.08)

    # 地面与河
    ground = blocky(poly([(0, 134), (111, 134), (111, 191), (0, 191)]), 2)
    c.turf(ground, SAGE, seed=61)
    c.paint(ground & ~shift(ground, 0, 1), SAGE[4])
    c.water(blocky(poly([(0, 141), (9, 140), (6, 146), (0, 148)]), 2), SKY, seed=62, base=4)
    c.water(blocky(poly([(103, 141), (111, 138), (111, 144), (105, 144)]), 2), SKY, seed=63, base=4)

    # 尾巴：从宝球后翘向右上
    tail(c, [(91, 84), (93, 77), (98, 73), (104, 72), (108, 74)], HAIR_AUBURN, 6, 4)

    # 王座靠背：阶梯形轮廓，两根立柱是石砖，中间内凹一块平面，头才压得住
    back = both([(55.5, 22), (44, 26), (44, 31), (32, 35), (32, 40), (21, 40), (21, 138), (55.5, 138)])
    c.stone_bricks(back, BRICK, bw=11, bh=8, ox=21, oy=4, seed=64, base=2)
    c.paint(edge(back), THRONE[0])
    panel = both([(55.5, 34), (48, 34), (48, 38), (33, 41), (33, 138), (55.5, 138)])
    c.paint(panel, THRONE[2])
    c.paint(outer(panel) | edge(panel) & (YY < 60), THRONE[1])
    # 立柱顶的羊角一对
    c.sprite(17, 29, RAM, RAM_PAL)
    c.sprite(76, 29, RAM, RAM_PAL)
    # 靠背菱印：蓝青发光
    seal = (np.abs(XX - 55.5) + np.abs(YY - 29)) <= 4.5
    c.paint(outer(seal), THRONE[0])
    c.glow(seal, TEAL[3])
    c.glow((np.abs(XX - 55.5) + np.abs(YY - 28)) <= 1.5, TEAL[4])

    # 台阶：两级，石砖
    step_low = rect(3, 184, 108, 191)
    c.stone_bricks(step_low, BRICK, bw=17, bh=8, ox=3, oy=0, seed=65, base=2)
    c.paint(rect(3, 184, 108, 184), THRONE[4])
    step_up = rect(8, 174, 103, 183)
    c.stone_bricks(step_up, BRICK, bw=17, bh=10, ox=8, oy=4, seed=66, base=2)
    c.paint(rect(8, 174, 103, 174), THRONE[4])
    c.paint(rect(8, 183, 103, 183), THRONE[1])

    # 红披风：一整块大轮廓，向两边铺到台阶
    cape = poly([(38, 65), (31, 67), (24, 76), (21, 84), (13, 88), (8, 100), (8, 106), (27, 108), (25, 116), (17, 134), (10, 155), (0, 173), (0, 191), (14, 179), (26, 176),
                 (85, 176), (97, 179), (111, 191), (111, 173), (101, 155), (94, 134), (86, 116), (84, 108), (97, 106), (97, 98), (86, 92), (87, 84), (80, 68), (73, 65)])
    c.paint(cape, RED[3])
    c.paint(edge(cape), RED[0])
    # 两腿之间的暗面和两块亮褶，座面从披风下摆露出来
    c.paint(poly([(44, 118), (68, 118), (72, 165), (40, 165)]), RED[1])
    c.paint(both([(44, 136), (50, 158), (43, 163)]), RED[2])
    seat = both([(55.5, 166), (50, 164), (45, 166), (45, 175), (55.5, 175)])
    c.paint(seat, THRONE[2])
    c.paint(seat & (YY <= 167), THRONE[1])
    # 胸甲两旁的暗面：披风从肩上翻开，露出里层
    c.paint(both([(46, 82), (46, 100), (42, 108), (28, 108), (26, 92), (38, 89)]), RED[1])
    # 外摆暗褶
    for wedge in ([(29, 132), (24, 152), (19, 172), (26, 174), (30, 150)],
                  [(14, 150), (6, 172), (10, 172), (18, 150)]):
        m = poly(wedge) & shrink(cape)
        c.paint(m | mirror(m), RED[1])

    # 扶手石柱与羊角：石块压在披风外缘上
    for x0, x1 in ((9, 21), (90, 102)):
        col = rect(x0, 122, x1, 172)
        c.stone_bricks(col, BRICK, bw=20, bh=9, ox=x0, oy=2, seed=67, base=2)
        c.paint(edge(col), THRONE[0])
    for wedge in ([(22, 118), (17, 134), (10, 155), (0, 173), (0, 191), (14, 179), (22, 176), (27, 150)],):
        m = poly(wedge) & cape
        c.paint(m | mirror(m), RED[3])
        c.paint(edge(cape) & (m | mirror(m)), RED[0])
    for wedge in ([(16, 150), (6, 172), (10, 172), (20, 150)],):
        m = poly(wedge) & shrink(cape)
        c.paint(m | mirror(m), RED[2])
    block_l = rect(5, 104, 27, 122)
    block_r = rect(84, 104, 106, 122)
    for block in (block_l, block_r):
        c.shape(block, THRONE, base=3, shadow=2, hi=1)
    c.sprite(5, 107, RAM, RAM_PAL)
    c.sprite(88, 107, RAM, RAM_PAL)

    # 大腿：深色衬裤
    plate(c, [(35, 118), (50, 112), (52, 131), (46, 133), (36, 122)], base=1, shadow=1, hi=0, ramp=SLATE)

    # 圣甲下摆：中间一片长甲，靠金边压住
    tasset = poly([(50, 109), (62, 109), (63, 140), (53, 137), (50, 120)])
    c.shape(tasset, ARMOR, base=2, shadow=2, hi=1)
    c.paint(line([(50, 118), (56, 123), (62, 118)]), ARMOR[0])
    c.paint(line([(50, 119), (56, 124), (62, 119)]), ARMOR[3])

    # 腿甲：膝甲、胫甲、踝甲、铁靴，左右成对。中间一档打底，亮棱只走一条
    plate(c, [(30, 138), (42, 138), (40, 155), (32, 155)], base=2, shadow=2, hi=1)
    ridge = line([(36, 139), (36, 154)])
    c.paint(ridge | mirror(ridge), ARMOR[4])
    plate(c, [(35, 120), (46, 131), (42, 138), (35, 140), (30, 134)], base=2, shadow=2, hi=1)
    knee_line = line([(35, 121), (36, 136)])
    c.paint(knee_line | mirror(knee_line), ARMOR[4])
    knee_low = line([(30, 134), (35, 139), (42, 136)])
    c.paint(knee_low | mirror(knee_low), ARMOR[0])
    plate(c, [(26, 174), (29, 168), (33, 164), (38, 166), (42, 170), (43, 174), (43, 176), (25, 176)], base=2, shadow=2, hi=1)
    toe = line([(27, 172), (34, 168), (42, 172)])
    c.paint(toe | mirror(toe), ARMOR[0])
    toe_hi = line([(34, 164), (33, 168), (28, 172)])
    c.paint(toe_hi | mirror(toe_hi), ARMOR[4])
    plate(c, [(29, 156), (35, 152), (42, 155), (42, 162), (35, 160), (29, 163)], base=2, shadow=1, hi=1)
    cuff = line([(35, 153), (35, 160)])
    c.paint(cuff | mirror(cuff), ARMOR[4])
    sole = rect(25, 175, 43, 176)
    c.paint(sole | mirror(sole), ARMOR[0])

    # 披风内缘的宽金边：左边一路拖到台阶，右边收在膝外
    stripe_l = line([(50, 114), (41, 110), (35, 113), (30, 130), (24, 150), (20, 172)], 3)
    stripe_r = line([(62, 114), (66, 115), (72, 112), (80, 111), (83, 115), (87, 131)], 3)
    c.paint(stripe_l | stripe_r, GOLD[4])
    c.paint(thick(line([(51, 112), (53, 137)])), GOLD[4])

    # 腰带与方扣
    belt = poly([(43, 100), (69, 100), (70, 107), (42, 107)])
    c.paint(belt, LEATHER[2])
    c.paint(edge(belt), LEATHER[0])
    c.paint(thick(line([(44, 102), (51, 100)])), GOLD[3])
    c.paint(thick(line([(61, 100), (67, 102)])), GOLD[3])

    # 胸甲：上宽下收，两段折线分出层次
    chest = poly([(46, 69), (66, 69), (68, 74), (66, 90), (64, 100), (48, 100), (46, 90), (44, 74)])
    c.shape(chest, ARMOR, base=2, shadow=2, hi=1)
    c.paint(poly([(47, 71), (55, 71), (55, 79), (47, 75)]), ARMOR[3])
    c.paint(poly([(47, 79), (55, 83), (55, 92), (47, 87)]), ARMOR[3])
    c.paint(rect(55, 70, 55, 99), ARMOR[4])
    c.paint(rect(56, 70, 56, 99), ARMOR[1])
    c.paint(line([(46, 88), (55, 93), (56, 93), (66, 88)]), ARMOR[0])
    c.paint(line([(46, 89), (55, 94), (56, 94), (66, 89)]), ARMOR[3])
    buckle = both([(55.5, 99), (52, 99), (50, 101), (50, 106), (52, 108), (55.5, 108)])
    c.paint(outer(buckle), GOLD[0])
    c.paint(buckle, GOLD[3])
    c.paint(rect(53, 102, 58, 105), LEATHER[1])
    c.paint(rect(52, 100, 59, 100), GOLD[4])
    # 胸前金折线：把两枚菱扣连起来，一起发光
    c.glow(thick(line([(47, 76), (55, 80), (56, 80), (64, 76)])), GOLD[3])

    # 肩幔：两块红压在胸甲两侧；暗褶从菱扣向外放射
    mantle_l = poly([(38, 65), (31, 67), (24, 76), (21, 84), (26, 92), (40, 90), (46, 80), (47, 69)])
    mantle_r = mirror(mantle_l)
    for m in (mantle_l, mantle_r):
        c.paint(m, RED[3])
        c.paint(edge(m), RED[0])
    for wedge in ([(44, 78), (28, 83), (25, 89), (35, 88)], [(43, 71), (32, 71), (27, 76), (37, 76)], [(45, 67), (40, 67), (41, 71)]):
        m = poly(wedge)
        c.paint(m | mirror(m), RED[1])
    c.paint(line([(21, 91), (36, 89), (45, 78)], 3), GOLD[4])
    c.paint(line([(87, 90), (76, 107)], 3), GOLD[4])

    # 袖甲：两只前臂压在披风上，金属环分段
    gaunt_l = poly([(12, 92), (17, 87), (25, 87), (30, 92), (28, 100), (24, 104), (13, 104)])
    gaunt_r = poly([(80, 100), (84, 95), (97, 95), (100, 99), (97, 104), (84, 106)])
    c.shape(gaunt_l, ARMOR, base=2, shadow=2, hi=1)
    c.shape(gaunt_r, ARMOR, base=2, shadow=2, hi=1)
    c.paint(curve([(13, 98), (20, 101), (28, 96)]), ARMOR[4])
    c.paint(curve([(82, 100), (90, 103), (98, 100)]), ARMOR[4])
    c.paint(rect(14, 88, 24, 90), SLATE[1])
    c.paint(curve([(14, 91), (19, 93), (24, 91)]), ARMOR[3])

    # 蓝色宝球：整颗发光，金菱在中央。比披风暗、比天冷，圆形一眼读出
    orb = ellipse(90.5, 85.5, 8.5)
    c.paint(outer(orb), SKY[0])
    c.glow(orb, SKY[1])
    c.glow(orb & ((XX - 90.5) + (YY - 85.5) < 5), SKY[2])
    c.glow(orb & ((XX - 90.5) + (YY - 85.5) < -6), SKY[3])
    c.glow_px([(85, 81), (86, 80), (87, 80), (88, 79), (89, 79)], SKY[5])
    jewel = (np.abs(XX - 90.5) + np.abs(YY - 85)) <= 4.5
    c.paint(outer(jewel), GOLD[1])
    c.glow(jewel, GOLD[3])
    c.glow(jewel & (XX <= 90) & (YY <= 85), GOLD[4])
    # 托球的手：三指竖在球前，拇指在外侧翘起，掌心在下
    c.sprite(84, 89, [
        '...........xx....',
        '......xx..xllx.xx',
        '.xx..xllx.xllxxlx',
        'xllx.xllx.xllxxlx',
        'xllx.xllx.xllxxlx',
        'xllx.xllxxxllxlx.',
        'xlllxxllllllsxlx.',
        'xllllllllllllslx.',
        '.xllllllllllllsx.',
        '..xxlllllllsssx..',
        '....xxxxxxxxxx...',
    ], {'l': SKIN[3], 's': SKIN[2], 'x': SKIN[0]})

    # Ankh 权杖：上环、横臂、结、长杆
    shaft = rect(16, 67, 19, 104)
    c.paint(outer(shaft), GOLD[0])
    c.paint(shaft, GOLD[3])
    c.paint(rect(16, 67, 16, 104), GOLD[4])
    c.paint(rect(19, 67, 19, 104), GOLD[2])
    loop = ellipse(17.5, 57, 6.5, 7.5) & ~poly([(17.5, 52), (20.5, 55), (20.5, 58), (17.5, 62.5), (14.5, 58), (14.5, 55)])
    c.paint(outer(loop), GOLD[0])
    c.paint(loop, GOLD[3])
    c.paint(loop & ~shift(loop, 1, 1), GOLD[4])
    arm = poly([(8, 64), (27, 64), (27, 67), (8, 67)])
    c.paint(outer(arm), GOLD[0])
    c.paint(arm, GOLD[3])
    c.paint(rect(8, 64, 27, 64), GOLD[4])
    c.paint(rect(8, 67, 27, 67), GOLD[2])
    c.paint(rect(16, 63, 19, 67), GOLD[3])
    knot = poly([(14, 72), (17, 69), (18, 69), (21, 72), (18, 75), (17, 75)])
    c.paint(outer(knot), GOLD[0])
    c.glow(knot, GOLD[3])
    c.glow(knot & (YY <= 71), GOLD[4])

    # 握杖的手：四指横在杆前，拇指压在外侧
    c.sprite(12, 76, [
        '...xxxxxxx..',
        '.xxlllllllxx',
        'xlllllllllxl',
        'xlllxxxxxxxl',
        'xllllllllsxl',
        'xlllxxxxxxxx',
        'xllllllllsx.',
        'xlllxxxxxxx.',
        'xllllllllsx.',
        '.xlllllssxx.',
        '.xlllllsx...',
        '..xxxxxx....',
    ], {'l': SKIN[3], 's': SKIN[2], 'x': SKIN[0]})

    # 披风扣：胸甲两侧一对金菱
    stud(c, 45, 75)
    stud(c, 66, 75)

    # 脖子与立领
    c.paint(rect(51, 61, 60, 65), SKIN[2])
    c.paint(rect(51, 63, 60, 63), SKIN[1])
    collar = both([(49, 64), (55.5, 64), (55.5, 70), (49, 70)])
    c.paint(collar, SLATE[1])
    c.paint(edge(collar) & (YY > 66), SLATE[0])
    band = both([(49, 64), (54.5, 64), (55.5, 70), (54, 70), (53, 66), (49, 66)])
    c.paint(band, GOLD[3])
    c.paint(poly([(54, 64), (57, 64), (55.5, 69)]), SKIN[2])

    # 短发后片：只在鬓边露几绺外翘的尖，不做成蓬松一团
    back_hair = both([(55.5, 44), (47, 46), (45, 50), (43, 52), (46, 53), (45, 57), (48, 56), (48, 61), (50, 58), (55.5, 58)])
    c.paint(back_hair, HAIR_AUBURN[2])
    c.paint(edge(back_hair), HAIR_AUBURN[0])

    # 头：用头部精灵打底，再把下颌方起来、眼睛压窄，换成成年男子的沉稳神情
    head(c, 56, 41, 'front', 'L', HAIR_AUBURN, BROWN_IRIS, eyes='open', mouth='none', ears=False)
    face = poly([(50, 50), (62, 50), (62, 57), (60, 60), (58, 62), (54, 62), (52, 60), (50, 57)])
    c.paint(face, SKIN[3])
    c.paint(face & (XX >= 61), SKIN[2])
    c.paint(line([(50, 57), (52, 60), (54, 62)]) | line([(62, 57), (60, 60), (58, 62)]), SKIN[0])
    c.paint(rect(55, 62, 57, 62), SKIN[0])
    c.paint(rect(53, 63, 59, 63), SKIN[1])
    # 眼：上睑一条直线压到底，只露半截虹膜
    c.paint(rect(52, 54, 55, 54) | rect(57, 54, 60, 54), HAIR_AUBURN[0])
    c.pxs([(52, 55), (60, 55)], '#ffffff')
    c.pxs([(53, 55), (59, 55)], BROWN_IRIS)
    c.pxs([(54, 55), (58, 55)], dim(BROWN_IRIS, 0.42))
    c.pxs([(52, 56), (53, 56), (54, 56), (58, 56), (59, 56), (60, 56)], SKIN[2])
    # 鼻梁与嘴：嘴和下颌线之间留两行皮肤，读不成两张嘴
    c.paint(rect(56, 54, 56, 56), SKIN[2])
    c.pxs([(55, 57), (56, 57)], SKIN[1])
    c.paint(rect(55, 59, 57, 59), SKIN[0])
    c.pxs([(54, 59), (58, 59)], SKIN[1])
    # 大猫耳：耳根压在王冠后
    for tip, a, b, in_a, in_b, in_c in (((45, 36), (52, 42), (46, 47), (46.5, 39), (50, 43), (46.5, 45)), ((67, 36), (60, 42), (66, 47), (65.5, 39), (62, 43), (65.5, 45))):
        m = poly([tip, a, b])
        c.paint(outer(m), HAIR_AUBURN[0])
        c.paint(m, HAIR_AUBURN[2])
        c.paint(poly([in_a, in_b, in_c]), PINK[1])
    # 额发：逐行摆，两鬓和左右各垂一尖，额心留缝
    c.sprite(47, 46, [
        '3333333333333333333',
        '2233322223333222233',
        '2222222222222222222',
        '2222222201222222222',
        '2222222211122222222',
        '221.2221...1222.122',
        '21...21.....12...12',
        '1.................1',
    ], {'3': HAIR_AUBURN[3], '2': HAIR_AUBURN[2], '1': HAIR_AUBURN[1], '0': HAIR_AUBURN[0]})
    # 双眉斜压：压在发尖上面，眉峰朝外
    c.pxs([(50, 51), (51, 51), (52, 51), (53, 52), (54, 52), (55, 52), (62, 51), (61, 51), (60, 51), (59, 52), (58, 52), (57, 52)], HAIR_AUBURN[0])
    # 王冠：五尖，金箍窄窄一条弯在额发上
    crown = poly([(45, 42), (47.5, 45), (50, 40), (52.5, 44), (54, 42.5), (56, 36), (58, 42.5), (59.5, 44), (62, 40), (64.5, 45), (67, 42), (65.5, 48), (61, 46), (56, 45), (51, 46), (46.5, 48)])
    c.paint(outer(crown), GOLD[0])
    c.paint(crown, GOLD[3])
    c.paint(crown & (YY <= 43) & (XX <= 56), GOLD[4])
    c.paint(crown & ~shift(crown, 0, -1), GOLD[2])
    return c
