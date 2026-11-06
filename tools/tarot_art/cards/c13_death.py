"""13 死神：银发猫耳的纯黑甲骑者骑完整白马缓行，黑旗一朵白花；空冠落地，枯茎旁发新芽，远处双塔之间日出。"""
from figures import *

# 暖奶油色的晨空、纯黑甲、白马、深色泥地：这张牌的黑白对照全靠这几条色阶
DAWN = ['#e4c48a', '#ecd199', '#f3dca8', '#f9e7ba', '#fdf1d2']
ARMOR = ['#0d0c15', '#181621', '#262432', '#393748', '#5b5970']
HORSE = ['#5a5245', '#9a9282', '#cdc5b1', '#eee6d2', '#fdf8ea']
HORSE_FAR = ['#4d463b', '#7a7366', '#9e9687', '#bdb5a3', '#d6cfbc']
EARTH = ['#1f161a', '#2b1e22', '#38282b', '#463433', '#57433e']
TOWER = ['#6b655e', '#8c867c', '#9c958b', '#aaa398', '#b8b1a5']
OLIVE = ['#3f4e30', '#536340', '#667750', '#7c8d60']


def petal(cx, cy, ang):
    """一片圆瓣，从花心向外。局部坐标 u 朝外、v 朝侧，手写后旋转到 ang 度。"""
    d = np.deg2rad(ang)
    ux, uy = np.cos(d), np.sin(d)
    nx, ny = -uy, ux
    local = [(2, 0), (5, -3.5), (9, -5.8), (13, -4.8), (16, 0), (13, 4.8), (9, 5.8), (5, 3.5)]
    return blob([(cx + ux * u + nx * v, cy + uy * u + ny * v) for u, v in local])


def draw():
    c = Card('13_death')
    everything = rect(0, 0, W - 1, H - 1)

    # 暖色晨空：上深下浅
    c.vgrad(everything, DAWN[:4], 0, 138)

    # 方块云：平底矩形堆叠
    for m in [rect(0, 5, 14, 9) | rect(0, 9, 26, 13) | rect(0, 13, 41, 17) | rect(6, 17, 30, 19),
              rect(0, 40, 7, 43) | rect(0, 43, 15, 47),
              rect(96, 47, 111, 50) | rect(84, 50, 111, 54),
              rect(92, 104, 111, 108) | rect(84, 108, 111, 113)]:
        c.paint(m, DAWN[4])
        c.paint(m & ~shift(m, 0, -2), DAWN[3])

    # 双塔之间的日出：盘面和光芒发光
    sun = ellipse(87.5, 146, 9)
    c.glow(sun, GOLD[4])
    c.glow(sun & ~shift(sun, 2, 2), GOLD[3])
    for a, b in [((87, 126), (87, 135)), ((81, 129), (84, 135)), ((94, 129), (91, 135)),
                 ((75, 134), (79, 138)), ((100, 133), (95, 138)), ((72, 140), (76, 141)), ((102, 139), (98, 141))]:
        c.glow(line([a, b]), GOLD[3])

    # 远塔两座：方块城垛，一座小一座大。灰度抬亮，读出空气的距离
    far = rect(64, 139, 70, 150) | rect(63, 135, 71, 139)
    far &= ~(rect(65, 135, 65, 136) | rect(69, 135, 69, 136) | rect(67, 135, 67, 136))
    c.stone_bricks(far, TOWER, bw=6, bh=4, base=2, seed=31)
    c.paint(edge(far), TOWER[0])
    c.paint(rect(66, 141, 68, 144), TOWER[0])
    big = poly([(102, 127), (108, 127), (110, 148), (100, 148)]) | rect(100, 123, 110, 127)
    big &= ~(rect(102, 123, 102, 124) | rect(108, 123, 108, 124) | rect(105, 123, 105, 124))
    c.stone_bricks(big, TOWER, bw=7, bh=4, base=2, seed=32)
    c.paint(edge(big), TOWER[0])
    c.paint(rect(104, 130, 106, 135), ARMOR[3])
    c.pxs([(104, 130), (106, 130)], TOWER[2])

    # 远丘：两层方块阶梯，只留极少颗粒
    hill_b = blocky(poly([(0, 146), (12, 144), (22, 146), (34, 144), (44, 145), (56, 148), (68, 148), (78, 147), (90, 149), (100, 146), (112, 144), (112, 170), (0, 170)]), 2)
    c.paint(hill_b, OLIVE[2])
    c.speckle(hill_b, [OLIVE[3], OLIVE[1]], 0.08, 33)
    c.paint(hill_b & ~shift(hill_b, 0, -1), OLIVE[3])
    hill_f = blocky(poly([(0, 153), (24, 151), (42, 153), (58, 157), (76, 158), (96, 161), (112, 163), (112, 172), (0, 172)]), 2)
    c.paint(hill_f, OLIVE[1])
    c.speckle(hill_f, [OLIVE[2], OLIVE[0]], 0.08, 34)
    c.paint(hill_f & ~shift(hill_f, 0, -1), OLIVE[2])

    # 河：一条折回的 S 形，由远处的日出流到近处
    spans = {147: (84, 87), 148: (84, 89), 149: (86, 94), 150: (88, 95), 151: (84, 93), 152: (76, 87), 153: (70, 80), 154: (66, 74), 155: (66, 76),
             156: (70, 82), 157: (74, 88), 158: (78, 94), 159: (72, 95), 160: (58, 92), 161: (54, 82), 162: (54, 72), 163: (56, 66)}
    river = blank()
    for y_, (x0, x1) in spans.items():
        river |= rect(x0, y_, x1, y_)
    c.paint(river, SKY[4])
    c.paint(river & ~shift(river, 0, 1), SKY[3])
    c.paint(river & ~shift(river, 0, -1), SKY[5])

    # 深色泥地
    ground = blocky(poly([(0, 158), (10, 157), (24, 158), (44, 161), (62, 164), (82, 165), (100, 168), (112, 170), (112, 192), (0, 192)]), 2)
    c.paint(ground, EARTH[2])
    c.speckle(ground, [EARTH[1], EARTH[3]], 0.16, 36)
    c.paint(ground & ~shift(ground, 0, -1), EARTH[4])

    # 黑旗：旗面保持黑色，两道大褶
    flag = poly([(58, 6), (64, 3), (70, 1), (78, 2), (86, 5), (95, 8), (104, 9), (110, 9), (108, 14), (106, 22), (107, 32), (109, 40), (111, 46),
                 (102, 46), (92, 45), (84, 42), (76, 38), (68, 36), (63, 39), (61, 41)])
    c.paint(flag, ARMOR[1])
    c.paint(flag & ~shift(flag, 0, -2), ARMOR[2])
    c.paint(edge(flag), ARMOR[0])
    c.fold([(66, 4), (66, 20), (64, 37)], ARMOR[0], flag)
    c.fold([(102, 10), (101, 24), (104, 44)], ARMOR[2], flag)

    # 花的三片叶：从花瓣之间伸出的尖叶，带一条叶脉
    fx, fy = 83, 22
    for ang in (-126, 162, 18):
        d = np.deg2rad(ang)
        ux, uy, nx, ny = np.cos(d), np.sin(d), -np.sin(d), np.cos(d)
        at = lambda u, v: (fx + ux * u + nx * v, fy + uy * u + ny * v)
        leaf = poly([at(9, 0), at(13, -3.4), at(17, -2.6), at(20.5, 0), at(17, 2.6), at(13, 3.4)])
        c.paint(leaf, OLIVE[2])
        c.paint(leaf & ~shift(leaf, -1, -1), OLIVE[3])
        c.paint(line([at(10, 0), at(19, 0)]), OLIVE[0])

    # 大朵白色五瓣花：旗面的识别点，瓣与瓣之间切一道黑缝
    for ang in (-90, -18, 54, 126, 198):
        p = petal(fx, fy, ang)
        c.paint(p, HORSE[4])
        c.paint(p & ~shift(p, -1, -1), HORSE[3])
        c.paint(edge(p), HORSE[2])
    for ang in (-54, 18, 90, 162, 234):
        d = np.deg2rad(ang)
        c.paint(line([(fx + 6 * np.cos(d), fy + 6 * np.sin(d)), (fx + 16 * np.cos(d), fy + 16 * np.sin(d))]), ARMOR[1])
    c.paint(ellipse(fx, fy, 5) , HORSE[3])
    for dx, dy in [(0, -5), (5, -2), (3, 4), (-3, 4), (-5, -2)]:
        c.px(fx + dx, fy + dy, GOLD[2])
        c.px(fx + round(dx * 0.7), fy + round(dy * 0.7), GOLD[2])
    c.paint(ellipse(fx, fy, 2), GOLD[3])
    c.px(fx - 1, fy - 1, GOLD[4])

    # 旗杆：金杆身、箍、木握把
    c.paint(line([(57, 7), (62, 42)]), GOLD[1])
    c.paint(line([(56, 7), (61, 42)]), GOLD[3])
    c.paint(line([(55, 7), (60, 42)]), GOLD[4])
    c.paint(rect(60, 42, 63, 45), GOLD[3])
    c.paint(rect(60, 45, 63, 45), GOLD[1])
    c.paint(rect(60, 42, 60, 45), GOLD[4])
    for off, tone in [(2, WOOD[1]), (1, WOOD[2]), (0, WOOD[3])]:
        c.paint(line([(61 + off, 46), (66 + off, 84)]), tone)
    gem(c, 55, 4, 3)

    # 马尾：向后垂的长束，尖端分叉
    tail_m = poly([(12, 100), (8, 103), (4, 110), (2, 120), (0, 130), (0, 140), (1, 147), (3, 142), (5, 148), (7, 141), (8, 134), (11, 126), (13, 116), (15, 104)])
    c.shape(tail_m, HORSE, base=3, shadow=2, hi=0, line_color=HORSE[0])
    c.fold([(10, 106), (6, 118), (4, 130), (3, 140)], HORSE[1], tail_m)
    c.fold([(12, 110), (10, 122), (7, 134)], HORSE[2], tail_m)

    # 远侧两条腿压暗，读出前后层次
    far_hind = poly([(24, 118), (35, 120), (35, 123), (30, 134), (29, 140), (32, 148), (35, 153), (37, 158), (38, 161), (33, 162), (30, 156), (27, 148), (22, 139), (23, 128)])
    far_front = poly([(56, 118), (65, 118), (65, 123), (60, 134), (56, 142), (53, 150), (54, 156), (55, 160), (49, 162), (48, 155), (51, 145), (52, 137), (55, 130)])
    c.shape(far_hind, HORSE_FAR, base=2, shadow=1, hi=0, line_color=HORSE[0])
    c.shape(far_front, HORSE_FAR, base=2, shadow=1, hi=0, line_color=HORSE[0])
    c.paint(shrink(far_hind) & poly([(24, 142), (32, 140), (38, 162), (30, 162)]), HORSE_FAR[4])
    c.paint(shrink(far_front) & poly([(46, 163), (58, 163), (58, 144), (51, 144)]), HORSE_FAR[4])
    hoof_fh = poly([(33, 161), (38, 160), (41, 164), (33, 165)])
    hoof_ff = poly([(49, 161), (55, 160), (58, 165), (50, 166)])
    for h_ in (hoof_fh, hoof_ff):
        c.paint(h_, HORSE[0])
        c.paint(edge(h_), EARTH[0])

    # 马身、近侧两条腿、颈、头一起是一个轮廓，描边才连贯
    body = blob([(60, 82), (44, 90), (28, 96), (15, 100), (10, 106), (10, 116), (14, 124), (28, 121), (45, 123), (60, 124), (70, 120), (76, 112), (78, 103), (76, 92)])
    near_hind = poly([(11, 112), (14, 122), (10, 133), (8, 137), (7, 144), (5, 152), (4, 156), (5, 161), (10, 161), (10, 151), (12, 142), (16, 135), (22, 128), (30, 118)])
    near_front = poly([(60, 116), (65, 125), (70, 134), (73, 145), (76, 155), (80, 162), (85, 161), (80, 150), (78, 140), (77, 133), (73, 122), (74, 112)])
    neck = poly([(58, 84), (66, 78), (74, 71), (82, 65), (90, 61), (95, 61), (92, 76), (88, 82), (84, 88), (80, 96), (77, 106), (70, 112), (58, 110)])
    muzzle = poly([(93, 62), (98, 61), (100, 64), (102, 72), (104, 80), (106, 86), (106, 90), (103, 92), (99, 92), (96, 90), (94, 85), (90, 82), (87, 79), (87, 74), (89, 68)])
    ear_a = poly([(93, 64), (98, 54), (99, 64)])
    ear_b = poly([(99, 62), (103, 57), (102, 65)])
    horse = body | near_hind | near_front | neck | muzzle | ear_a | ear_b
    c.volume(horse, HORSE, 3, shadow=2, hi=0)
    c.paint(edge(horse), HORSE[0])
    # 腹下和颈下的暗面
    c.paint(shrink(horse) & poly([(14, 124), (28, 120), (45, 122), (60, 123), (60, 120), (45, 119), (28, 117), (17, 119)]), HORSE[2])
    c.paint(shrink(horse) & poly([(84, 87), (80, 96), (78, 104), (76, 100), (79, 90), (82, 84)]), HORSE[2])
    c.paint(line([(14, 122), (20, 116), (22, 108)]) & horse, HORSE[2])
    c.paint(line([(60, 112), (65, 119)]) & horse, HORSE[2])
    # 蹄
    hoof_nh = poly([(5, 161), (10, 160), (14, 166), (4, 166)])
    hoof_nf = poly([(80, 162), (86, 161), (90, 167), (81, 167)])
    for h_ in (hoof_nh, hoof_nf):
        c.paint(h_, HORSE[0])
        c.paint(edge(h_), EARTH[0])
    # 距毛：蹄上方的小尖
    c.pxs([(4, 157), (77, 158), (48, 158), (33, 158)], HORSE[1])

    # 耳内
    c.paint(shrink(ear_a), HORSE[3])
    c.paint(line([(97, 58), (97, 62)]), HORSE[1])
    c.paint(shrink(ear_b), HORSE[3])
    c.px(101, 61, HORSE[1])

    # 鬃毛：一绺一绺从颈顶垂下，后画的压在先画的上面，每绺下侧压一道灰
    for pts in [[(64, 79), (62, 85), (61, 91)], [(70, 75), (68, 83), (64, 92)], [(76, 70), (75, 79), (70, 88)], [(82, 66), (82, 74), (78, 83)], [(88, 63), (88, 70), (85, 77)]]:
        lock = taper(pts, 8, 1)
        c.paint((shift(lock, 2, 1) | shift(lock, 1, 1)) & horse & ~lock, HORSE[2])
        c.paint(lock & horse, HORSE[4])
    # 伸出轮廓的飞毛
    for tip, a_, b_ in [((68, 74), (71, 71), (75, 72)), ((81, 64), (84, 63), (87, 63)), ((59, 80), (62, 79), (65, 78))]:
        fly = poly([tip, a_, b_])
        c.paint(fly, HORSE[4])
        c.paint(line([tip, b_]), HORSE[0])
        c.paint(line([tip, a_]), HORSE[0])
    # 额发：耳间垂下的两小绺
    for pts in [[(98, 62), (100, 67), (100, 73)], [(100, 62), (103, 67), (104, 73)]]:
        lock = taper(pts, 4, 1)
        c.paint(outer(lock) & (shift(lock, 1, 0) | shift(lock, 0, 1)) & horse, HORSE[1])
        c.paint(lock & horse, HORSE[4])
    # 鬃和额发盖掉的外轮廓补回来
    c.paint(edge(horse) & rect(58, 54, 108, 92), HORSE[0])

    # 马脸：眼、鼻孔、嘴线
    c.sprite(95, 73, ['kk.', 'kkk', '.kk'], {'k': INK})
    c.px(96, 73, '#ffffff')
    c.pxs([(103, 87), (103, 88), (102, 89)], HORSE[0])
    c.paint(line([(97, 90), (101, 91)]), HORSE[1])

    # 笼头与缰绳：暗红细带
    strap = RED[1]
    c.paint(line([(92, 63), (91, 70), (88, 75), (88, 79)]), strap)
    c.paint(line([(92, 67), (97, 65)]), strap)
    c.paint(line([(92, 71), (97, 83)]), strap)
    c.paint(line([(98, 83), (105, 81)]), strap)
    gem(c, 91, 69, 2)
    gem(c, 97, 83, 2)

    # 猫尾：从披风下探出
    tail(c, [(24, 78), (21, 68), (15, 62), (9, 62), (8, 66)], HAIR_SILVER, 4, 3, base=2)

    # 披风：从肩头向左后方大幅甩开，下摆有一个波折，盖住马臀一部分
    cape = poly([(42, 47), (32, 49), (26, 56), (21, 66), (13, 75), (5, 84), (0, 90), (0, 94), (5, 92), (4, 97), (9, 101), (12, 108), (16, 118), (24, 106), (32, 92), (38, 82), (60, 82), (58, 66), (54, 52)])
    c.shape(cape, HAIR_VIOLETBLACK, base=3, shadow=2, hi=0)
    c.paint(poly([(26, 62), (20, 72), (14, 88), (16, 106), (22, 96), (24, 80)]) & shrink(cape), HAIR_VIOLETBLACK[2])
    c.fold([(10, 84), (8, 92), (11, 100)], HAIR_VIOLETBLACK[2], cape)
    c.fold([(31, 68), (28, 80), (22, 98)], HAIR_VIOLETBLACK[2], cape)

    # 鞍褥：暗红长垂，金色内边
    cloth = poly([(32, 92), (40, 84), (58, 84), (58, 118), (50, 119), (52, 124), (42, 124), (24, 121)])
    c.shape(cloth, RED, base=2, shadow=2, hi=0)
    c.paint(poly([(36, 92), (44, 92), (40, 118), (30, 116)]) & shrink(cloth), RED[1])
    c.trim([(29, 102), (28, 117), (41, 119)], cloth, smooth=False)
    c.trim([(50, 116), (57, 115), (57, 94)], cloth, smooth=False)

    # 胸带：从鞍前斜下到马胸，正中一枚菱扣
    c.paint(line([(58, 91), (78, 107)], 3), RED[1])
    c.paint(line([(58, 90), (78, 106)]), RED[2])
    gem(c, 77, 106, 3)

    # 腿甲：大腿、膝甲、长靴、踩在金色马镫里
    thigh = poly([(34, 84), (50, 82), (53, 90), (52, 98), (44, 100), (33, 97)])
    c.shape(thigh, ARMOR, base=2, shadow=2, hi=0)
    boot = poly([(41, 103), (51, 103), (49, 122), (50, 126), (53, 130), (53, 133), (39, 133), (39, 127), (41, 122)])
    c.shape(boot, ARMOR, base=2, shadow=2, hi=0)
    c.paint(line([(43, 106), (43, 121)]), ARMOR[3])
    knee = blob([(47, 92), (52, 92), (54, 97), (52, 102), (47, 102), (45, 97)])
    c.shape(knee, ARMOR, base=3, shadow=1, hi=0)
    c.trim([(41, 103), (46, 100), (51, 103)], smooth=False)
    c.trim([(36, 92), (40, 87), (48, 87)], thigh)
    stirrup = line([(43, 133), (43, 127), (46, 125), (49, 127), (50, 133)])
    c.paint(stirrup, GOLD[3])
    c.paint(rect(39, 134, 53, 135), GOLD[3])
    c.paint(rect(39, 135, 53, 135), GOLD[1])
    c.paint(line([(50, 112), (47, 125)]), LEATHER[2])

    # 上身：纯黑胸甲，没有任何徽记，只有金色外缘
    torso = poly([(36, 48), (47, 46), (53, 50), (56, 60), (55, 72), (54, 82), (37, 84), (33, 72), (33, 60)])
    c.shape(torso, ARMOR, base=2, shadow=2, hi=0)
    plate = poly([(40, 58), (53, 58), (52, 69), (43, 69)])
    c.paint(plate, ARMOR[1])
    c.trim([(40, 58), (43, 69), (52, 69), (53, 58)], smooth=False)
    # 腰带
    belt = poly([(37, 71), (54, 70), (54, 74), (37, 75)])
    c.paint(belt, LEATHER[2])
    c.paint(edge(belt), LEATHER[0])

    # 披风前片的金边：过胸口，再沿下摆到底
    c.trim([(42, 53), (32, 62), (25, 71), (21, 80)])
    c.trim([(16, 115), (24, 104), (31, 94), (37, 84)])
    c.trim([(43, 53), (50, 56), (56, 60)])

    # 举旗的手臂
    far_arm = poly([(53, 60), (58, 57), (62, 54), (65, 56), (62, 62), (55, 66)])
    c.shape(far_arm, ARMOR, base=3, shadow=1, hi=0)
    c.trim([(57, 59), (61, 63)], far_arm, smooth=False)
    c.sprite(60, 47, ['.lll.', 'lllls', 'ldddd', 'lllls', 'ldddd', 'lllls', 'ldddd', '.sss.'], {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1]})

    # 握缰的手臂
    near_arm = poly([(31, 66), (37, 67), (48, 77), (44, 81), (32, 71)])
    c.shape(near_arm, ARMOR, base=3, shadow=1, hi=0)
    c.trim([(33, 67), (38, 69)], near_arm, smooth=False)
    c.trim([(36, 71), (40, 73)], near_arm, smooth=False)
    # 缰绳：从手里下垂到马颈再向前
    rein = curve([(50, 83), (62, 90), (72, 94), (82, 93), (94, 87)])
    c.paint(shift(rein, 0, 1), RED[0])
    c.paint(rein, RED[2])
    c.paint(curve([(94, 88), (85, 95), (79, 99)]), RED[0])
    hand(c, 45, 78, 'fist')
    c.pxs([(47, 82), (48, 83), (49, 83)], SKIN[1])

    # 长发后片：向左后方飘，三个尖
    back_hair = poly([(39, 30), (35, 34), (34, 39), (31, 42), (24, 45), (15, 47), (21, 50), (16, 54), (14, 60), (20, 57), (20, 63), (26, 56), (31, 52), (36, 49), (40, 46), (42, 38)])
    hair_paint(c, back_hair, HAIR_SILVER, base=3)
    c.fold([(36, 42), (28, 47), (20, 53)], HAIR_SILVER[1], back_hair)
    c.fold([(34, 46), (28, 51), (23, 56)], HAIR_SILVER[1], back_hair)

    # 立领
    coll = poly([(39, 45), (48, 44), (49, 49), (40, 50)])
    c.paint(coll, ARMOR[1])
    c.trim([(39, 45), (40, 50)], smooth=False)
    c.trim([(48, 44), (48, 49)], smooth=False)

    # 头
    head(c, 48, 24, '3q', 'L', HAIR_SILVER, GREY_IRIS, mouth='line')
    hair_lock(c, [(38, 35), (37, 41), (39, 46)], HAIR_SILVER, 3, 1, base=3)

    # 肩上的蓝青扣与腰扣
    gem(c, 42, 53, 3)
    gem(c, 48, 72, 3)

    # 落下的空冠：翻倒在地，露出内部的暗面
    c.paint(rect(8, 188, 40, 189), EARTH[0])
    crown = poly([(8, 180), (14, 181), (14, 175), (19, 177), (21, 171), (20, 166), (24, 170), (28, 175), (33, 173), (34, 168), (36, 172), (38, 175), (42, 180),
                  (41, 183), (33, 187), (23, 189), (14, 188)])
    c.shape(crown, GOLD, base=3, shadow=2, hi=1, line_color=GOLD[1])
    inside = poly([(16, 188), (22, 184), (30, 181), (37, 179), (41, 181), (33, 186), (24, 188)])
    c.paint(inside, EARTH[1])
    c.paint(poly([(24, 188), (33, 185), (40, 182), (33, 187)]), GOLD[1])
    c.trim([(14, 187), (22, 182), (31, 179), (40, 178)], crown, base=4)
    for gx, gy, r_ in [(16, 180, 1), (26, 175, 2), (37, 176, 1)]:
        m = (np.abs(XX - gx) + np.abs(YY - gy)) <= r_
        c.paint(outer(m) & crown, GOLD[1])
        c.paint(m, RED[2])
        c.px(gx, gy, RED[3])

    # 枯茎：弯垂的褐色细茎，三片枯叶
    c.paint(rect(60, 188, 78, 189), EARTH[0])
    stem = curve([(73, 188), (74, 181), (72, 175), (68, 171), (63, 171), (60, 173)])
    c.paint(stem, WOOD[4])
    for pts in [[(60, 173), (56, 175), (53, 182), (57, 181), (60, 177)], [(63, 172), (62, 177), (64, 181), (66, 177), (65, 172)], [(72, 187), (69, 184), (65, 184), (63, 187), (67, 186)]]:
        dead = poly(pts)
        c.paint(dead, WOOD[3])
        c.paint(dead & ~shift(dead, -1, -1), WOOD[4])

    # 新芽：鲜绿双叶，从小土堆里长出
    mound = poly([(68, 190), (74, 188), (80, 187), (86, 188), (92, 190)])
    c.paint(mound, WOOD[2])
    c.paint(mound & ~shift(mound, 0, -1), WOOD[3])
    c.paint(line([(79, 187), (79, 180)]), GRASS[3])
    for pts in [[(79, 180), (77, 176), (74, 175), (75, 178), (77, 181)], [(79, 180), (81, 176), (85, 174), (87, 175), (84, 178), (81, 180)]]:
        leaf = poly(pts)
        c.paint(leaf, GRASS[3])
        c.paint(leaf & ~shift(leaf, -1, -1), GRASS[4])

    return c
