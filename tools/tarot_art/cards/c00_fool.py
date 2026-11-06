"""0 愚者：黑发金眼的猫耳旅人一脚立崖、一脚迈空，举花仰望；白犬抬头提醒，崖外三枚分散的蓝青踏点。"""
from figures import *


def draw():
    c = Card('00_fool')
    everything = rect(0, 0, W - 1, H - 1)

    # 夜蓝天空：上深下浅，色带之间只留一窄条抖动
    c.vgrad(everything, [SKY[1], SKY[2], SKY[3]], 10, 175)

    # 太阳：大而圆的金盘，八道尖光。盘面发光，是牌面夜里的主光之一
    sun_x, sun_y = 97, 15
    for tip, spread in [((60, 15), 2.5), ((70, 33), 2.5), ((97, 50), 2.5), ((68, -3), 2.5), ((83, -8), 2.5), ((111, 37), 2.5), ((112, -6), 2.5), ((125, 15), 2.5)]:
        ang = np.arctan2(tip[1] - sun_y, tip[0] - sun_x)
        nx, ny = -np.sin(ang), np.cos(ang)
        base = (sun_x + 14 * np.cos(ang), sun_y + 14 * np.sin(ang))
        c.paint(poly([(base[0] + nx * spread, base[1] + ny * spread), tip, (base[0] - nx * spread, base[1] - ny * spread)]), GOLD[3])
    sun = ellipse(sun_x, sun_y, 12)
    c.paint(grow(sun, 2, diag=True), SKY[1])
    c.glow(sun, GOLD[4])
    c.glow(edge(sun) | (sun & ~shift(sun, 2, 2)), GOLD[3])

    # 方块云：原版那种平底矩形堆叠
    cloud_a = rect(0, 13, 44, 17) | rect(6, 9, 30, 13) | rect(16, 4, 30, 9) | rect(33, 11, 42, 13) | rect(8, 17, 50, 19)
    c.paint(cloud_a, IVORY[4])
    c.paint(rect(0, 17, 50, 19) & cloud_a, IVORY[3])
    c.paint(rect(8, 19, 50, 19) & cloud_a, IVORY[2])
    cloud_b = rect(82, 58, 111, 62) | rect(90, 54, 111, 58) | rect(97, 51, 111, 54)
    c.paint(cloud_b, IVORY[4])
    c.paint(rect(82, 61, 111, 62) & cloud_b, IVORY[2])

    # 远山：方块阶梯边
    far = blocky(poly([(70, 100), (84, 90), (88, 92), (100, 83), (112, 92), (112, 112), (70, 112)]), 2)
    c.paint(far, SKY[4])
    c.paint(far & ~shift(far, 0, 3), SKY[5])
    near = blocky(poly([(48, 136), (64, 124), (74, 130), (90, 126), (112, 136), (112, 142), (48, 142)]), 2)
    c.paint(near, SKY[1])
    c.paint(near & ~shift(near, 0, 2), SKY[2])
    # 山脚的雾带，把远山压回去
    c.dither(rect(60, 106, 111, 110) & far, SKY[4], SKY[3], 0.5)
    c.paint(rect(60, 111, 111, 113) & far, SKY[3])

    # 对岸小岛与秘径门
    isle = blocky(poly([(86, 139), (96, 136), (111, 136), (111, 142), (100, 141)]), 2)
    c.paint(isle, GRASS[1])
    c.paint(isle & ~shift(isle, 0, 1), GRASS[2])
    arch = rect(97, 125, 107, 136) & ~rect(100, 129, 104, 136)
    arch |= poly([(96, 126), (102, 122), (108, 126)])
    c.paint(outer(arch) & rect(90, 118, 111, 136), SKY[1])
    c.paint(arch, STONE[3])
    c.paint(rect(97, 133, 107, 136) & arch, STONE[2])
    c.pxs([(102, 122), (101, 123), (103, 123)], STONE[4])
    c.glow(rect(100, 129, 104, 136) & ~poly([(100, 129), (100, 130), (101, 129)]) & ~poly([(104, 129), (104, 130), (103, 129)]), TEAL[3])
    c.glow(rect(101, 131, 103, 135), TEAL[4])

    # 三枚蓝青踏点：分散、互不相连，不是一座桥
    c.node_h(68, 148, 7, 3)
    c.node_h(81, 156, 8, 3)
    c.node_h(95, 164, 9, 4)

    # 崖体：草方块的层次——草皮、锯齿垂边、泥土，右侧切面压暗
    cliff = poly([(0, 133), (20, 134), (40, 136), (65, 137), (62, 143), (56, 150), (50, 158), (46, 170), (38, 183), (33, 191), (0, 191)])
    cliff = blocky(cliff, 2)
    c.soil(cliff, DIRT, seed=21)
    facet = cliff & ~blocky(poly([(0, 133), (40, 140), (44, 150), (34, 170), (20, 191), (0, 191)]), 2)
    c.soil(facet, [DIRT[0], DIRT[0], DIRT[1], DIRT[2], DIRT[3]], seed=22)
    top = cliff & blocky(poly([(0, 130), (66, 134), (62, 142), (40, 142), (24, 146), (14, 152), (0, 163)]), 2)
    c.turf(top, GRASS, seed=23)
    c.grass_lip(top, GRASS, seed=24, length=6)
    c.paint(top & ~shift(top, 0, 1), GRASS[4])

    # 双尾：从披风后探出
    tail(c, [(21, 78), (17, 69), (11, 65), (6, 65), (4, 68)], HAIR_BLACK, 4, 3)
    tail(c, [(19, 82), (15, 75), (9, 73), (4, 75), (3, 78)], HAIR_BLACK, 4, 3)

    # 披风后片：从肩头大幅向左下甩开
    cape = blob([(42, 49), (32, 57), (24, 66), (19, 76), (11, 86), (5, 95), (9, 101), (21, 106), (35, 108), (50, 107), (60, 103), (64, 92), (68, 74), (70, 60), (62, 51)])
    c.shape(cape, NAVY, base=2, shadow=2, hi=0, deep=0)
    lining = blob([(23, 74), (27, 66), (37, 60), (44, 62), (36, 73), (27, 83), (20, 84)])
    c.paint(lining, NAVY[1])
    c.fold([(24, 88), (32, 96), (36, 106)], NAVY[1], cape)
    c.fold([(44, 92), (48, 100), (50, 106)], NAVY[1], cape)
    c.fold([(14, 92), (22, 98), (26, 104)], NAVY[3], cape)
    c.trim([(5, 95), (17, 90), (30, 87), (42, 75), (53, 60)], cape)
    c.trim([(21, 76), (25, 67), (35, 60)], cape, base=3)
    c.trim([(69, 62), (66, 76), (63, 90)], cape)

    # 后腿：站在崖上那条
    leg_back = blob([(46, 88), (54, 92), (50, 102), (46, 110), (40, 114), (35, 110), (35, 103), (39, 96)])
    c.shape(leg_back, LEATHER, base=2, shadow=2, hi=1)
    c.fold([(44, 95), (40, 103), (39, 110)], LEATHER[1], leg_back)
    cuff_b = poly([(35, 110), (43, 112), (42, 116), (35, 113)])
    c.shape(cuff_b, LEATHER, base=2, shadow=1, hi=0)
    shin_b = poly([(36, 114), (42, 116), (40, 122), (35, 121)])
    c.volume(shin_b, SKIN, 3, shadow=1, hi=0)
    boot_b = poly([(31, 120), (39, 120), (39, 129), (44, 132), (46, 136), (46, 138), (29, 138), (30, 130)])
    c.shape(boot_b, LEATHER, base=2, shadow=1, hi=1)
    c.paint(rect(29, 137, 46, 138) & boot_b, GOLD[3])
    c.paint(rect(29, 138, 46, 138) & boot_b, GOLD[1])
    c.paint(rect(31, 123, 39, 124) & boot_b, LEATHER[0])
    c.pxs([(35, 123), (36, 123), (35, 124), (36, 124)], GOLD[3])
    c.paint(line([(36, 131), (42, 133)]) & boot_b, GOLD[2])

    # 前腿：迈向空处，靴尖抬起
    leg_front = blob([(52, 88), (62, 85), (70, 89), (75, 95), (75, 101), (70, 104), (63, 101), (55, 97)])
    c.shape(leg_front, LEATHER, base=2, shadow=2, hi=1)
    c.fold([(58, 92), (64, 96), (69, 102)], LEATHER[1], leg_front)
    cuff_f = poly([(67, 102), (75, 99), (76, 103), (70, 106)])
    c.shape(cuff_f, LEATHER, base=2, shadow=1, hi=0)
    shin_f = poly([(69, 105), (75, 103), (78, 109), (72, 111)])
    c.volume(shin_f, SKIN, 3, shadow=1, hi=0)
    boot_f = poly([(71, 110), (77, 106), (80, 110), (87, 109), (90, 111), (89, 115), (84, 120), (80, 124), (76, 121), (74, 116)])
    c.shape(boot_f, LEATHER, base=2, shadow=1, hi=1)
    c.paint(line([(80, 124), (84, 120), (90, 113)]), GOLD[3])
    c.paint(line([(74, 109), (80, 112)]) & boot_f, LEATHER[0])
    c.pxs([(76, 110), (77, 110), (77, 111)], GOLD[3])

    # 上衣下摆：两片象牙色衣襟，开衩处露出裤子
    skirt_l = poly([(46, 75), (51, 75), (49, 92), (36, 99), (33, 96), (40, 86)])
    skirt_r = poly([(51, 75), (60, 73), (70, 87), (50, 94)])
    c.shape(skirt_l, IVORY, base=3, shadow=2, hi=0)
    c.shape(skirt_r, IVORY, base=3, shadow=2, hi=0)
    c.trim([(35, 96), (47, 90)], skirt_l)
    c.trim([(52, 91), (67, 86)], skirt_r)
    c.fold([(54, 77), (56, 88)], IVORY[1], skirt_r)
    c.trim([(48, 77), (47, 88)], skirt_l)

    # 上身
    torso = poly([(47, 56), (55, 57), (63, 55), (60, 73), (46, 75)])
    c.shape(torso, IVORY, base=3, shadow=2, hi=0)
    c.fold([(55, 62), (54, 72)], IVORY[1], torso)
    # 腰带与方扣
    belt = poly([(45, 73), (60, 71), (60, 74), (45, 76)])
    c.paint(belt, LEATHER[1])
    c.paint(edge(belt), LEATHER[0])
    c.paint(rect(52, 71, 55, 75) & grow(belt, 1), GOLD[3])
    c.paint(rect(53, 72, 54, 74), LEATHER[1])

    # 肩部 V 形披风领，交在胸前菱扣
    collar_l = poly([(42, 49), (50, 47), (55, 56), (52, 60), (46, 56)])
    collar_r = poly([(58, 48), (64, 47), (71, 47), (68, 53), (61, 57), (56, 60), (54, 56)])
    c.shape(collar_l, NAVY, base=3, shadow=1, hi=0)
    c.shape(collar_r, NAVY, base=3, shadow=1, hi=0)
    c.trim([(50, 48), (55, 56)], collar_l)
    c.trim([(59, 49), (56, 56)], collar_r)
    c.trim([(56, 59), (62, 55), (69, 50)], collar_r)
    # 领口露肤
    c.paint(poly([(51, 46), (58, 46), (55, 53)]), IVORY[4])
    c.paint(poly([(53, 46), (57, 46), (55, 50)]), SKIN[2])

    # 举花的手臂：宽袖向右上挥开
    sleeve_r = poly([(62, 50), (71, 47), (77, 47), (78, 52), (73, 64), (68, 61), (63, 58)])
    forearm_r = taper([(75, 49), (79, 46), (82, 44)], 4, 3)
    c.volume(forearm_r, SKIN, 3, shadow=1, hi=0)
    c.shape(sleeve_r, IVORY, base=3, shadow=2, hi=0)
    c.trim([(75, 48), (71, 62)], sleeve_r)
    # 花与手
    c.paint(line([(85, 35), (83, 43), (82, 47)]), LEAF[2])
    leaf_l = poly([(77, 37), (81, 36), (84, 39), (80, 40)])
    leaf_r = poly([(85, 40), (88, 37), (91, 40), (87, 42)])
    c.shape(leaf_l, LEAF, base=3, shadow=1, hi=0)
    c.shape(leaf_r, LEAF, base=3, shadow=1, hi=0)
    white_flower(c, 85, 32, 4)
    hand(c, 79, 41, 'fist')
    c.pxs([(79, 42), (80, 44)], SKIN[1])

    # 肩上的杖与包袱
    c.paint(line([(5, 42), (48, 58)], 2), WOOD[1])
    c.paint(line([(5, 41), (48, 57)], 2), WOOD[3])
    c.paint(line([(5, 40), (47, 56)]), WOOD[4])
    sack = blob([(14, 46), (19, 48), (21, 54), (19, 59), (12, 61), (6, 58), (6, 52), (10, 47)])
    tie_a = poly([(13, 44), (11, 36), (17, 42)])
    tie_b = poly([(16, 44), (22, 38), (21, 44)])
    c.shape(tie_a, NAVY, base=2, shadow=1, hi=0)
    c.shape(tie_b, NAVY, base=2, shadow=1, hi=0)
    c.shape(sack, NAVY, base=2, shadow=2, hi=1)
    c.fold([(11, 48), (9, 54)], NAVY[1], sack)
    c.fold([(15, 49), (16, 55)], NAVY[1], sack)
    c.paint(rect(12, 42, 17, 47) & ellipse(14.5, 44.5, 3), NAVY[1])
    c.paint(edge(ellipse(14.5, 44.5, 3)), NAVY[0])
    # 杖压在包袱结上，再画一段
    c.paint(line([(13, 44), (23, 48)], 2), WOOD[3])

    # 握杖手臂：袖口朝上张开，小臂伸到杖下
    forearm_l = taper([(39, 62), (42, 58), (44, 56)], 4, 3)
    c.volume(forearm_l, SKIN, 3, shadow=1, hi=0)
    sleeve_l = poly([(37, 58), (43, 61), (47, 58), (45, 68), (41, 74), (33, 71), (31, 65)])
    c.shape(sleeve_l, IVORY, base=3, shadow=2, hi=0)
    c.trim([(36, 59), (42, 62)], sleeve_l)
    hand(c, 41, 51, 'fist')
    c.pxs([(42, 54), (43, 55), (44, 55)], SKIN[1])

    # 头：四分之三侧，仰望；黑发里夹一绺蓝尾
    h = head(c, 53, 24, '3q', 'L', HAIR_BLACK, GOLD_IRIS, eyes='up', mouth='open')
    hair_lock(c, [(37, 34), (35, 39), (37, 43)], HAIR_BLACK, 4, 1)
    hair_lock(c, [(40, 37), (38, 43), (41, 47)], HAIR_BLACK, 4, 1)
    blue = hair_lock(c, [(43, 41), (43, 45), (45, 49)], [HAIR_BLUEBLACK[1], HAIR_BLUEBLACK[2], SKY[3], SKY[4], SKY[5]], 3, 1)

    # 胸前菱扣最后压上
    gem(c, 55, 57, 3)

    # 白犬：完整的普通犬身，抬头吠叫。逐格摆，四条腿才分得开
    dog = [
        '...............oo.........',
        '..............opwoooo.....',
        '..............oppwwwwoo...',
        '.............owwwwkwwwwko.',
        '.ooo..........owwwwwwwwoo.',
        'owwwo.........owwwwwmmmm..',
        'owoowo.........owwwwwwo...',
        'owo.oo.........owwwwoo....',
        'owwo...........occcwo.....',
        '.owwo.........owwwcco.....',
        '..owwoooooooooowwwwwo.....',
        '...owwwwwwwwwwwwwwwwo.....',
        '...owwwwwwwwwwwwwwwwwo....',
        '...owwwwwwwwwwwwwwwwwo....',
        '...owwWWWWWWWWWWWwwwwo....',
        '...ogowwWWoooooWWwwwgo....',
        '..ogoowwo.....oWWwoogo....',
        '..ogo.owo.....owwo.ogo....',
        '..ogo.owo.....owwo.ogo....',
        '.ogo..owo.....owwo..ogo...',
        '.ogo..owo.....owo...ogo...',
        '.oooo.owoo....owo...oooo..',
        '......owwwo...owwwo.......',
        '......ooooo...ooooo.......',
    ]
    c.paint(rect(4, 136, 28, 137) & top, GRASS[1])
    c.sprite(1, 113, dog, {'o': IVORY[1], 'w': IVORY[4], 'W': IVORY[3], 'g': IVORY[2], 'k': INK, 'p': PINK[2], 'm': '#7a3540', 'c': LEATHER[2]})
    # 小菱牌挂在项圈上
    c.node(20, 124, 1)
    # 提醒的短线
    for a, b in [((27, 106), (25, 102)), ((30, 107), (31, 103)), ((31, 110), (34, 108))]:
        c.paint(line([a, b]), GOLD[4])

    return c
