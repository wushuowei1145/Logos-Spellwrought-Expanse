"""3 皇后：蜂蜜金发绿眼的猫耳女性略侧坐，一手持花头短杖、一手搭膝，头顶十二星；
脚边心形盾刻圆圈加十字，三簇麦穗，三枚苹果，带节点的魔力流水从简化玻璃温室流出。"""
from figures import *

# 座椅的暖灰石头、奶色猫尾：参考图里偏粉的灰和奶油色，公共色阶里没有
WARM_STONE = ['#4f4744', '#7d716c', '#a79a93', '#c9bdb4', '#e6dcd2']
TAIL = ['#94785a', '#d7bd93', '#efdcb5', '#faf0d4', '#fff8e6']

# 十二星的星心：头顶弧线，逐颗手排，数一下正好十二颗
STARS = [(28, 45), (27, 38), (29, 32), (33, 27), (38, 23), (44, 21), (50, 21), (56, 22), (61, 26), (65, 31), (67, 37), (66, 44)]
STAR_SPRITE = ['..#..', '..#..', '#####', '.###.', '.#.#.']


WHEAT = [
    '...h...',
    '..hGd..',
    '.hhGdd.',
    '..hGd..',
    '.hhGdd.',
    '..hGd..',
    '.hhGdd.',
    '..hGd..',
    '.hhGdd.',
    '..hGd..',
    '...G...',
]


def wheat(c, root_x, top, lean=0.0):
    """一支麦穗：人字纹的细穗加一根秆。root_x 是秆在画面底边的位置，top 是穗尖的行，lean 是每行的横向偏移。"""
    pal = {'d': GOLD[2], 'G': GOLD[3], 'h': GOLD[4]}
    n = len(WHEAT)

    def x_at(y):
        return round(root_x + (191 - y) * lean)

    c.paint(line([(x_at(top + n), top + n), (root_x, 191)]), GOLD[2])
    shape_mask = blank()
    for j, row in enumerate(WHEAT):
        shape_mask |= c.sprite_mask(x_at(top + j) - 3, top + j, [row])
    c.paint(outer(shape_mask), GOLD[1])
    for j, row in enumerate(WHEAT):
        c.sprite(x_at(top + j) - 3, top + j, [row], pal)


def thick(m):
    """2 像素宽的线：叠一条平移的，走向更整齐。"""
    return m | shift(m, 1, 0) | shift(m, 0, 1)

def blade(c, x0, y0, x1, y1, ramp=LEAF, tone=2):
    """一片细长草叶。"""
    c.paint(taper([(x0, y0), ((x0 + x1) / 2 + 1, (y0 + y1) / 2), (x1, y1)], 3, 1), ramp[tone])


def draw():
    c = Card('03_empress')
    everything = rect(0, 0, W - 1, H - 1)

    # 天空与方块云
    c.vgrad(everything, [DAY[1], DAY[2]], 10, 65, soft=0.05)
    cloud_a = rect(74, 8, 111, 11) | rect(84, 4, 111, 8) | rect(93, 1, 111, 4)
    c.paint(cloud_a, IVORY[4])
    c.paint(rect(74, 10, 111, 11) & cloud_a, IVORY[2])
    cloud_b = rect(65, 20, 95, 23) | rect(73, 17, 88, 20)
    c.paint(cloud_b, IVORY[4])
    c.paint(rect(65, 23, 95, 23) & cloud_b, IVORY[2])

    # 远处灌木：方块阶梯边
    bush_l = blocky(poly([(0, 64), (6, 60), (14, 62), (24, 58), (34, 62), (40, 70), (0, 70)]), 2)
    bush_r = blocky(poly([(62, 72), (68, 58), (76, 54), (84, 50), (96, 44), (104, 38), (111, 38), (111, 72)]), 2)
    c.paint(bush_l | bush_r, SAGE[2])
    c.paint((bush_l | bush_r) & ~shift(bush_l | bush_r, 0, 2), SAGE[3])

    # 草地
    ground = blocky(poly([(0, 74), (20, 70), (50, 70), (72, 66), (111, 68), (111, 191), (0, 191)]), 2)
    # 地面大，颗粒比 turf 稀一半，麦穗和裙摆才不被吃掉
    c.paint(ground, GRASS[2])
    c.speckle(ground, [GRASS[1], GRASS[3]], 0.16, seed=51)
    c.paint(ground & ~shift(ground, 0, 1), GRASS[4])

    # 树干与枝
    trunk = poly([(0, 76), (7, 76), (5, 66), (7, 52), (11, 38), (18, 26), (28, 14), (24, 12), (14, 22), (8, 30), (0, 42)])
    c.bark(trunk, WOOD, seed=52)
    branch = line([(18, 24), (26, 14), (38, 7), (50, 5)]) | line([(10, 34), (14, 28), (20, 26)])

    # 树冠：沿画面上缘铺开，下缘留给十二星
    crown = blocky(poly([(0, 0), (74, 0), (70, 4), (60, 6), (52, 10), (44, 13), (37, 16), (30, 20), (24, 25), (22, 31), (12, 33), (5, 30), (0, 32)]), 2)
    c.leaves(crown, LEAF, seed=53)
    c.paint(branch & crown, WOOD[1])
    # 三枚苹果
    for x, y in ((17, 25), (31, 16), (46, 8)):
        apple = ellipse(x, y, 4)
        c.paint(outer(apple), RED[0])
        c.paint(apple, RED[3])
        c.paint(apple & ~shift(apple, -1, -1), RED[2])
        c.pxs([(x - 2, y - 2), (x - 1, y - 2), (x - 2, y - 1)], RED[4])
        c.paint(rect(x, y - 6, x, y - 4), WOOD[1])

    # 简化玻璃温室：木框 + 几格玻璃 + 里面一团绿
    glass = rect(82, 56, 94, 66) | rect(97, 56, 109, 66)
    gable = poly([(82, 55), (88, 46), (94, 55)])
    slope = poly([(90, 46), (104, 47), (110, 55), (97, 55)])
    frame_all = rect(80, 55, 110, 68) | poly([(80, 55), (88, 44), (105, 45), (111, 55)])
    c.paint(frame_all, WOOD[2])
    c.paint(edge(frame_all), WOOD[1])
    c.paint(glass | gable | slope, SKY[5])
    c.paint(rect(95, 55, 96, 68), WOOD[3])
    c.paint(rect(80, 55, 110, 55), WOOD[3])
    c.paint(line([(88, 44), (96, 55)]), WOOD[3])
    c.paint(line([(99, 46), (104, 55)]), WOOD[2])
    for g in (rect(84, 60, 92, 66), rect(99, 60, 107, 66)):
        c.paint(g, SAGE[3])
        c.paint(g & rect(0, 0, W, 61), SKY[5])
    c.paint(ellipse(88, 61, 3, 2) | ellipse(103, 61, 3, 2), SAGE[3])
    c.paint(line([(84, 58), (86, 56)]) | line([(100, 58), (102, 56)]), DAY[4])

    # 魔力流水：从温室脚下弯到石座后面，由细变宽
    stream = taper([(88, 69), (88, 71), (95, 73), (105, 77), (109, 84), (105, 92), (100, 100), (98, 108)], 2, 5)
    c.paint(outer(stream) & ground, TEAL[1])
    c.glow(stream, TEAL[3])
    c.glow(curve([(90, 72), (99, 75), (106, 80), (106, 89), (101, 98), (99, 107)]) & stream, TEAL[4])
    c.node(93, 72, 2)
    c.node(106, 85, 3)

    # 双尾：奶色，从座后立起，一高一低
    tail(c, [(84, 110), (82, 98), (83, 88), (82, 80), (86, 76), (90, 77)], TAIL, 6, 5, base=2)
    tail(c, [(89, 110), (90, 100), (91, 91), (94, 86), (98, 85)], TAIL, 6, 5, base=2)

    # 座椅：红靠背、红坐垫、石立柱
    back = poly([(6, 83), (8, 81), (22, 81), (22, 110), (6, 110)])
    c.shape(back, RED, base=2, shadow=2, hi=1)
    c.paint(poly([(10, 84), (17, 84), (21, 90), (21, 109), (14, 109)]), RED[1])
    cushion_l = poly([(20, 118), (32, 118), (44, 126), (43, 134), (34, 165), (20, 165)])
    c.shape(cushion_l, RED, base=3, shadow=2, hi=0)
    c.paint(poly([(36, 130), (42, 132), (33, 160), (28, 165), (24, 165)]), RED[1])
    cushion_r = poly([(74, 112), (92, 113), (97, 118), (98, 150), (84, 150)])
    c.shape(cushion_r, RED, base=2, shadow=2, hi=1)
    # 石柱：石砖只留一层缝
    post_l = rect(5, 117, 18, 160)
    c.stone_bricks(post_l, WARM_STONE, bw=30, bh=11, ox=5, oy=7, seed=54, base=3)
    c.paint(rect(16, 117, 18, 160), WARM_STONE[1])
    c.paint(rect(4, 117, 4, 160) | rect(19, 117, 19, 160), WARM_STONE[0])
    cap_l = rect(3, 109, 20, 116)
    c.shape(cap_l, WARM_STONE, base=3, shadow=2, hi=1)
    foot_l = rect(3, 161, 20, 167)
    c.shape(foot_l, WARM_STONE, base=3, shadow=2, hi=1)
    post_r = rect(93, 115, 106, 158)
    c.stone_bricks(post_r, WARM_STONE, bw=30, bh=11, ox=93, oy=6, seed=55, base=3)
    c.paint(rect(104, 115, 106, 158), WARM_STONE[1])
    c.paint(rect(92, 115, 92, 158) | rect(107, 115, 107, 158), WARM_STONE[0])
    cap_r = rect(84, 107, 108, 114)
    c.shape(cap_r, WARM_STONE, base=3, shadow=2, hi=1)

    # 后发：两大片波浪，外缘一收一放；暗面在颈后，再走几条 S 形暗线分绺
    hair_back_r = blob([(52, 38), (60, 41), (64, 48), (65, 55), (63, 60), (69, 66), (72, 71), (71, 76), (77, 80), (80, 87), (79, 92), (75, 100), (74, 92), (71, 84), (66, 78), (60, 70), (55, 62), (52, 50)])
    hair_back_l = blob([(42, 42), (37, 47), (34, 54), (36, 59), (33, 63), (30, 67), (27, 71), (31, 72), (36, 68), (44, 60)])
    for m in (hair_back_r, hair_back_l):
        c.paint(m, HAIR_HONEY[2])
        c.paint(m & ~shift(m, 2, 2), HAIR_HONEY[3])
        c.paint(edge(m), HAIR_HONEY[0])
    c.paint(blob([(53, 56), (58, 57), (63, 66), (66, 77), (61, 76), (55, 66)]), HAIR_HONEY[1])
    c.paint(blob([(38, 58), (42, 56), (46, 62), (42, 68), (37, 66)]), HAIR_HONEY[1])
    for pts in ([(62, 50), (63, 58), (67, 66), (71, 73), (75, 82), (77, 92)], [(66, 68), (70, 78), (73, 88), (74, 94)]):
        c.paint(curve(pts) & shrink(hair_back_r), HAIR_HONEY[1])
    c.paint(curve([(38, 50), (36, 56), (37, 61), (32, 68)]) & shrink(hair_back_l), HAIR_HONEY[1])
    # 头后暗发：填掉下颌和后发之间露天的洞
    c.paint(blob([(42, 42), (52, 39), (58, 46), (58, 60), (50, 64), (42, 60)]), HAIR_HONEY[1])

    # 裙：大片象牙色，膝头撑开，硬边褶子只走几道
    skirt = poly([(44, 92), (66, 92), (72, 104), (80, 113), (83, 125), (86, 145), (96, 158), (100, 166), (92, 169), (24, 169), (30, 160), (37, 148), (43, 132), (42, 126), (33, 122), (32, 114), (38, 104)])
    c.paint(skirt, IVORY[4])
    c.paint(edge(skirt), IVORY[0])
    for wedge in ([(34, 120), (46, 122), (66, 112), (72, 110), (56, 122), (44, 128)],
                  [(50, 126), (58, 148), (52, 150), (34, 168), (42, 168), (60, 152)],
                  [(66, 122), (74, 142), (70, 150), (58, 168), (66, 168), (78, 148)],
                  [(80, 132), (86, 150), (94, 162), (86, 168), (82, 150)],
                  [(42, 108), (52, 105), (44, 114)]):
        c.paint(poly(wedge) & shrink(skirt), IVORY[2])

    # 胸衣与腰带
    bodice = poly([(45, 72), (63, 72), (65, 90), (44, 90)])
    c.paint(bodice, IVORY[4])
    c.paint(bodice & (XX >= 61), IVORY[2])
    c.fold([(56, 76), (54, 88)], IVORY[1], bodice)
    belt = poly([(43, 88), (66, 88), (67, 95), (43, 96)])
    c.shape(belt, SAGE, base=2, shadow=1, hi=0)
    c.paint(thick(line([(43, 94), (67, 93)])), GOLD[3])

    # 左大袖：象牙内衬 + 绿外层 + 金边
    lining = poly([(20, 84), (24, 82), (24, 118), (15, 108), (15, 92)])
    c.paint(lining, IVORY[4])
    c.paint(edge(lining), IVORY[0])
    c.paint(poly([(21, 96), (23, 96), (23, 114), (19, 108)]), IVORY[2])
    mantle_l = poly([(33, 76), (56, 73), (47, 92), (38, 108), (28, 118), (20, 128), (18, 127), (24, 118), (24, 100), (24, 80)])
    c.paint(mantle_l, SAGE[2])
    c.paint(edge(mantle_l), SAGE[0])
    c.paint(poly([(26, 92), (38, 84), (34, 100), (26, 112)]), SAGE[1])
    c.paint(thick(line([(25, 79), (24, 100), (25, 116)])), GOLD[3])
    c.paint(thick(curve([(54, 75), (46, 93), (37, 108), (27, 118), (20, 128)])), GOLD[3])

    # 右侧披挂：肩头一小片，腰下一条长摆拖到地
    mantle_r = poly([(64, 72), (67, 78), (66, 88), (60, 76)])
    c.paint(mantle_r, SAGE[2])
    drape = poly([(63, 98), (74, 100), (82, 114), (86, 132), (96, 148), (111, 161), (111, 165), (100, 163), (90, 152), (80, 134), (76, 118)])
    c.paint(drape, SAGE[2])
    c.paint(edge(drape), SAGE[0])
    c.paint(thick(curve([(74, 110), (80, 130), (90, 150), (100, 160), (111, 164)])), GOLD[3])
    sleeve_r = poly([(62, 96), (74, 98), (80, 112), (76, 116), (68, 106), (60, 104)])
    c.paint(sleeve_r, SAGE[3])
    c.paint(edge(sleeve_r), SAGE[0])
    c.paint(thick(curve([(61, 99), (70, 98), (77, 106), (80, 114)])), GOLD[3])

    # 搭膝的手：手背朝上，指尖朝左下
    c.sprite(47, 99, [
        '..........xxxxxx',
        '.......xxxlllllx',
        '....xxxllllllllx',
        '...xlllllllllssx',
        '..xllllllllssxx.',
        '.xllxlxlssxx....',
        '.xlxlxlxx.......',
        '..x.x.x.........',
    ], {'l': SKIN[3], 's': SKIN[2], 'x': SKIN[0]})

    # 肩与颈
    shoulder = blob([(35, 75), (36, 69), (40, 66), (45, 67), (47, 75)])
    c.paint(shoulder, SKIN[3])
    c.paint(outer(shoulder) & (YY < 75), SKIN[0])
    chest = poly([(46, 56), (51, 56), (52, 63), (61, 69), (63, 74), (45, 74), (45, 67), (47, 62)])
    c.paint(chest, SKIN[3])
    c.paint(outer(chest) & (YY < 70) & (XX >= 51), SKIN[0])
    c.paint(poly([(46, 56), (51, 56), (52, 61), (47, 61)]), SKIN[2])
    c.paint(line([(47, 66), (48, 70)]), SKIN[2])
    # 领口金边与菱扣：一条宽带从肩头走到胸前
    band = curve([(36, 77), (44, 75), (55, 73), (64, 74)], 3)
    c.paint(outer(band) & (YY < 76), GOLD[1])
    c.paint(band, GOLD[3])
    gem(c, 56, 73, 3)

    # 头：四分之三侧朝左；嘴只留浅浅一笑
    h = head(c, 45, 36, '3q', 'L', HAIR_HONEY, GREEN_IRIS, eyes='open', mouth='none', flip=True, ears=False)
    c.pxs([(44, 53), (47, 53)], SKIN[1])
    c.pxs([(45, 54), (46, 54)], SKIN[0])
    # 大猫耳：一远一近，和星弧隔开
    far = poly([(38, 34), (45, 39), (40, 44)])
    near = poly([(52, 31), (58, 39), (47, 39)])
    far_in = poly([(39.5, 37), (43, 40), (40.5, 42)])
    near_in = poly([(52, 34), (55.5, 39), (49.5, 39)])
    for m, inside in ((far, far_in), (near, near_in)):
        c.paint(outer(m), HAIR_HONEY[0])
        c.paint(m, HAIR_HONEY[2])
        c.paint(inside, PINK[1])
    c.pxs([(51, 38), (52, 37), (53, 38), (52, 38)], IVORY[4])
    # 发冠：盖住耳根，向右扫
    crown_hair = poly([(41, 44), (43, 40), (48, 38), (56, 39), (61, 44), (64, 50), (60, 50), (56, 46), (51, 45), (48, 45), (46, 46), (43, 46)])
    c.paint(crown_hair, HAIR_HONEY[3])
    c.paint(crown_hair & rect(0, 40, W, 41), HAIR_HONEY[4])
    c.paint(edge(crown_hair) & (YY < 44), HAIR_HONEY[0])
    c.fold([(49, 40), (56, 44), (62, 49)], HAIR_HONEY[1], crown_hair)
    # 脸前的前绺
    hair_lock(c, [(41, 46), (38, 52), (40, 58), (43, 63), (42, 69), (45, 74)], HAIR_HONEY, 5, 1, base=3)
    hair_lock(c, [(56, 49), (58, 56), (57, 64)], HAIR_HONEY, 4, 1, base=3)

    # 十二星
    for x, y in STARS:
        c.sprite(x - 2, y - 2, STAR_SPRITE, {'#': GOLD[4]}, glow='#')
        c.glow_px([(x, y)], GOLD[5])

    # 短杖：花头、菱框、金杆；手握在中段
    sleeve_arm = poly([(14, 72), (21, 72), (24, 84), (17, 90)])
    c.shape(sleeve_arm, SKIN, base=3, shadow=1, hi=0)
    shaft = rect(16, 52, 18, 84)
    c.paint(outer(shaft), GOLD[0])
    c.paint(shaft, GOLD[3])
    c.paint(rect(16, 52, 16, 84), GOLD[4])
    knob = ellipse(17, 84, 2)
    c.paint(outer(knob), GOLD[0])
    c.paint(knob, GOLD[3])
    frame = (np.abs(XX - 17) + np.abs(YY - 54)) <= 5
    c.paint(outer(frame), GOLD[0])
    c.glow(frame, GOLD[3])
    c.glow((np.abs(XX - 17) + np.abs(YY - 54)) <= 3, TEAL[3])
    c.glow_px([(16, 53), (17, 53), (16, 54), (17, 54)], TEAL[4])
    white_flower(c, 17, 46, 4, outline=STONE[0])
    c.sprite(12, 63, [
        '..xxxxxx..',
        '.xllllllx.',
        'xlllllllsx',
        'xlllxxxxxx',
        'xllllllllx',
        'xlllxxxxxx',
        'xllllllllx',
        'xlllxxxxxx',
        '.xllllllx.',
        '.xlllssx..',
    ], {'l': SKIN[3], 's': SKIN[2], 'x': SKIN[0]})

    # 心形盾：金边红底，内部圆圈加十字
    heart = blob([(20, 131), (26, 127), (33, 128), (37, 135), (36, 144), (30, 155), (20, 168), (10, 155), (4, 144), (3, 135), (7, 128), (14, 127)])
    c.paint(outer(heart), GOLD[0])
    c.paint(heart, GOLD[3])
    c.paint(heart & ~shift(heart, 1, 1), GOLD[4])
    inner_h = shrink(heart, 3)
    c.paint(inner_h, RED[2])
    c.paint(inner_h & ~shift(inner_h, 1, 1), RED[1])
    c.paint(ring(20, 141, 6, 6, 2), GOLD[3])
    c.paint(rect(19, 147, 21, 159), GOLD[3])
    c.paint(rect(15, 151, 25, 153), GOLD[3])

    # 鞋：从裙摆下探出来
    shoe = ['..oo........', '.oWWo.......', '.oWWWo......', '.oWWWWo.....', '.oWWWWWo....', 'oWoWWWWWo...', 'oWooWWWWWoo.', 'oWo.oWWsssWo', 'ooo..oooooo.']
    shoe_pal = {'o': SAND[0], 'W': SAND[4], 's': SAND[3]}
    # 裙摆在脚前掀起一角，里面压暗，鞋才跳得出来
    under = poly([(56, 169), (60, 159), (70, 155), (78, 160), (85, 169)])
    c.paint(under, IVORY[1])
    c.paint(outer(under) & (YY < 169), IVORY[0])
    c.paint(rect(69, 156, 73, 163) | rect(60, 159, 64, 165), SKIN[2])
    c.sprite(67, 161, shoe, shoe_pal)
    c.sprite(57, 163, shoe, shoe_pal)
    c.paint(line([(26, 170), (56, 170)]) | line([(70, 170), (92, 170)]), GRASS[0])

    # 麦穗三簇与草叶
    for base in (0, 36, 72):
        blade(c, base + 12, 191, base + 3, 178, LEAF, 1)
        blade(c, base + 14, 191, base + 34, 182, LEAF, 1)
    # 三簇：每簇三支从同一点散开，中间一支最高
    for x in (17, 56, 95):
        wheat(c, x, 172, -0.42)
        wheat(c, x, 172, 0.42)
        wheat(c, x, 168, 0.0)

    return c
