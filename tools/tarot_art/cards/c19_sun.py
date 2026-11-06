"""19 太阳：大而温暖的微笑日面，直光和尖角光交替。棕发猫耳女孩骑完整的白马，闭眼微笑、微微仰头，
双臂向两侧张开、手掌打开、双手空空。身后四株向日葵和一道矮石墙，腰间一枚蓝青菱印。没有旗。"""
from figures import *

# 矮墙的暖灰石，和奶油色的天不打架
WALL = ['#8d7963', '#ad977c', '#c7b197', '#dbc9b0', '#ecdfca']
# 晒足太阳的草地，比 GRASS 更偏橄榄
MEADOW = ['#4b5a2a', '#68783a', '#84934a', '#9dab5a', '#b7c172']
# 马蹄：偏暖的灰褐
HOOF = ['#3d332f', '#5d5048', '#7b6d62', '#978a7d', '#b3a698']

# 头：比公用的 L 头略大，五官整体上提，读作微微仰头。每行 (起始 x, 像素串)，从 y=72 起。
# 0–4 棕发，p/q 内耳，G/Y 金发箍，l/s/d/x 肤色，k 闭眼的弧，b 腮红，m/t/r 嘴、牙、舌
HEAD_TOP = 72
HEAD_ROWS = [
    (58, '0'),
    (58, '00'),
    (44, '0............0p0'),
    (44, '00.......00000pq0'),
    (44, '0pq0..00223320pqq0'),
    (44, '0pqq002233GYGGpqq0'),
    (45, '0pqq23GGG2222GGq0'),
    (46, '023GG212212212G0'),
    (45, '02GG21l1ll1ll1l2210'),
    (44, '022221llllllllkkls210'),
    (43, '0232221lkkllllkllkls210'),
    (42, '0222221skllkllllllllls1210'),
    (41, '02322211sbblllldlllbbls12210'),
    (41, '02212211sllmtttttmllls1210'),
    (41, '01221211sllmmmmmmmllls1210'),
    (42, '0122110slllmrrrmlllsd120'),
    (43, '012110dslllmmmlllsdx120'),
    (43, '0112101dsllllllssdx1210'),
    (44, '0111011dsllllssdx1120'),
    (45, '0011111dsllssdx1110'),
    (47, '01111.dssdx.1110'),
    (48, '0110.......110'),
    (49, '00.........0'),
]

# 张开的手掌：五指分开，拇指朝上。手腕在右，flip 后朝另一边。逐格摆
OPEN_PALM = [
    '........xx.....',
    '.......xllx....',
    '........xllx...',
    '.xxx....xllx...',
    'xlllxx...xllx..',
    '.xxxllxxxxlllx.',
    '.xddddlllllllx.',
    'xllllllllllllsl',
    '.xdddddlllllllS',
    '.xlllllllllllsl',
    '..xdddddllllllS',
    '...xllllllllssl',
    '....xxxlllllssS',
    '.......xxxxxxxx',
]
PALM_PAL = {'l': SKIN[3], 's': SKIN[2], 'S': SKIN[3], 'd': SKIN[1], 'x': SKIN[0]}

# 马头：一格格摆。o 轮廓，w/W 白毛两档，g/G 阴影，k/e/h 眼线、眼珠、高光，n 鼻孔。金笼头最后压上去
HORSE_HEAD = [
    '.............o........',
    '....o........oo.......',
    '....oo......ogo.......',
    '....ogo.....oggo......',
    '....oggo....oggo......',
    '....oGgo.ooo.oGgo.....',
    '....oGggoWWWooGGgo....',
    '...ooGgWWWWWWWGGgo....',
    '..oWWWWWWWWWWWWggo....',
    '.oWWWWWWWWgWWWWWwo....',
    'oWWWgWWWWgWWWWWWwwo...',
    '.ooWWWgWWWgWWWWwwwo...',
    '...oWWgWWWWWgWwwwwwo..',
    '..oWWgoWwWWWwwwwwwwo..',
    '.oWWoowwwoWwkkkwwwwo..',
    'oWWo.okwwwwwehewwwwo..',
    '.oo..okwwwwweeewwgwo..',
    '.....owwwwwwwwwwwgwo..',
    '.....owwwwwwwwwwwggo..',
    '.....owwwwwwwwwwwggo..',
    '.....owwwwwwwwwwwggo..',
    '.....owwwwwwwwwwwggo..',
    '....owwwwwwwwwwwwggo..',
    '....owwwwwwwwwwwggo...',
    '....oWwwwwwwwwwwggo...',
    '...oWWWWWWWWWWwggo....',
    '...oWnWWWnnWWWwgo.....',
    '...oWWWWWWnWWWWgo.....',
    '...oWWWWWWWWWWgo......',
    '....oWWGGGGGWWgo......',
    '....oWWWWWWWgoo.......',
    '.....ooooooo..........',
]


def sunflower(c, x, y, r):
    """向日葵：十二瓣，一明一暗相间，花盘是深棕大圆，亮点偏左上。"""
    all_petals = blank()
    petals = []
    for i in range(12):
        a = i * np.pi / 6 + 0.1
        tip = (x + r * np.cos(a), y + r * np.sin(a))
        b0 = (x + r * 0.42 * np.cos(a - 0.34), y + r * 0.42 * np.sin(a - 0.34))
        b1 = (x + r * 0.42 * np.cos(a + 0.34), y + r * 0.42 * np.sin(a + 0.34))
        w0 = (x + r * 0.72 * np.cos(a - 0.26), y + r * 0.72 * np.sin(a - 0.26))
        w1 = (x + r * 0.72 * np.cos(a + 0.26), y + r * 0.72 * np.sin(a + 0.26))
        m = poly([b0, w0, tip, w1, b1])
        petals.append(m)
        all_petals |= m
    c.paint(outer(all_petals), GOLD[2])
    for i, m in enumerate(petals):
        if i % 2:
            c.paint(m, GOLD[4])
    for i, m in enumerate(petals):
        if not i % 2:
            c.paint(m, GOLD[3])
    disc_r = max(r * 0.42, 2)
    disc = ellipse(x, y, disc_r)
    c.paint(outer(disc), GOLD[2])
    c.paint(disc, WOOD[2])
    c.paint(disc & ~shift(disc, -1, -1), WOOD[1])
    c.paint(disc & ~shift(disc, 1, 1), WOOD[3])


def stem_leaves(c, x, y0, y1, flip=False):
    """茎和一对叶，只长在墙头以上。"""
    c.paint(rect(x, y0, x + 1, y1), LEAF[2])
    c.paint(rect(x, y0, x, y1), LEAF[3])
    s = -1 if flip else 1
    leaf_a = poly([(x, y1 - 2), (x - s * 3, y1 - 7), (x - s * 8, y1 - 8), (x - s * 6, y1 - 4)])
    leaf_b = poly([(x + 1, y1 - 2), (x + 1 + s * 3, y1 - 7), (x + 1 + s * 8, y1 - 9), (x + 1 + s * 6, y1 - 4)])
    for m in (leaf_a, leaf_b):
        c.paint(outer(m), SAGE[1])
        c.paint(m, SAGE[3])
        c.paint(m & ~shift(m, 0, -1), SAGE[2])


def draw():
    c = Card('19_sun')
    everything = rect(0, 0, W - 1, H - 1)

    # 奶油色的晴空，平涂，不要色带
    c.paint(everything, IVORY[4])

    # 日面：八直八尖交替，和盘面留一圈空
    cx, cy = 55.5, 26
    # 尖光：底宽头尖，整片发光
    wedges = blank()
    for tip, b0, b1 in [((36, 0), (41, 8), (46, 4)), ((19, 15), (33, 15), (31, 23)), ((19, 38), (32, 30), (34, 38)), ((36, 56), (42, 43), (47, 48))]:
        wedge = poly([tip, b0, b1])
        wedges |= wedge | mirror(wedge)
    # 直光：外形三格宽，只有中间一到两格发光，白天粗、夜里细
    bars = rect(54, 0, 57, 2) | rect(54, 49, 57, 59) | rect(8, 24, 31, 27) | rect(80, 24, 103, 27)
    bar_core = rect(55, 0, 56, 2) | rect(55, 49, 56, 59) | rect(8, 25, 31, 26) | rect(80, 25, 103, 26)
    for tip, base in [((19, 0), (36, 13)), ((19, 52), (37, 38))]:
        bars |= line([tip, base], 3) | mirror(line([tip, base], 3))
        bar_core |= line([tip, base], 1) | mirror(line([tip, base], 1))
    c.paint(bars, GOLD[3])
    c.paint(wedges, GOLD[3])
    c.paint(wedges & ~shift(wedges, 1, 1), GOLD[4])
    c.glow(bar_core | shrink(wedges), GOLD[3])
    # 盘面：外圈一道不发光的金边，里面整片发光
    rim = ellipse(cx, cy, 21)
    disc = ellipse(cx, cy, 19)
    c.paint(rim, GOLD[3])
    c.glow(disc, ORANGE[4])
    c.glow(disc & ~shift(disc, 2, 2), GOLD[4])
    c.glow(disc & ~shift(disc, -2, -2), GOLD[3])
    # 五官写进发光层，夜里才不会变成黑洞：弯眉、弯眼、细长的鼻、大大的笑
    brow = GOLD[2]
    arc = GOLD[1]
    for side in (0, 1):
        def pts(lst):
            return [(111 - x if side else x, y) for x, y in lst]
        c.glow_px(pts([(41, 19), (42, 18), (43, 17), (44, 17), (45, 17), (46, 17), (47, 17), (48, 18), (49, 18)]), brow)
        c.glow_px(pts([(41, 25), (42, 24), (43, 23), (44, 23), (45, 22), (46, 22), (47, 23), (48, 23), (49, 24), (50, 25),
                       (42, 25), (43, 24), (44, 24), (48, 24), (49, 25)]), arc)
    c.glow(poly([(54, 21), (57, 21), (56, 31), (55, 31)]), GOLD[3])
    c.glow(poly([(56, 21), (57, 21), (56, 31)]), GOLD[2])
    mouth = [(45, 34), (46, 35), (47, 35), (48, 36), (49, 36), (50, 37), (51, 37), (52, 37), (53, 38), (54, 38), (55, 38)]
    c.glow_px(mouth + [(111 - x, y) for x, y in mouth], arc)

    # 向日葵：四株，低位，不碰手臂
    stem_leaves(c, 8, 124, 136)
    stem_leaves(c, 19, 130, 136)
    stem_leaves(c, 83, 124, 136, flip=True)
    stem_leaves(c, 101, 124, 136, flip=True)
    sunflower(c, 9, 117, 8)
    sunflower(c, 19, 127, 6)
    sunflower(c, 83, 117, 8)
    sunflower(c, 101, 117, 8)

    # 矮石墙与草地
    wall = rect(0, 136, W - 1, 158)
    c.stone_bricks(wall, WALL, bw=13, bh=7, ox=2, oy=136, seed=61, base=2)
    c.paint(rect(0, 136, W - 1, 136), WALL[4])
    ground = rect(0, 158, W - 1, H - 1)
    c.paint(ground, MEADOW[2])
    c.speckle(ground, [MEADOW[3], MEADOW[1], MEADOW[3]], 0.2, 62)
    c.paint(rect(0, 158, W - 1, 158), MEADOW[3])
    # 草边的小齿
    for x in range(1, W, 5):
        c.paint(rect(x, 157, x + 1, 157), MEADOW[3])
    # 马的落影
    c.paint(poly([(36, 191), (60, 186), (90, 185), (76, 188), (50, 191)]), MEADOW[1])

    line_c = LEATHER[2]

    # 马：后两条腿先画，压一档暗
    far_fore = poly([(30, 146), (34, 150), (30, 153), (25, 156), (24, 160), (24, 172), (25, 175), (20, 178), (18, 172), (18, 160), (19, 155), (24, 151)])
    far_hind = poly([(50, 154), (60, 154), (57, 165), (56, 172), (54, 180), (47, 181), (49, 175), (52, 168), (53, 163)])
    hoof_ff = poly([(20, 177), (25, 174), (27, 178), (26, 182), (23, 183), (21, 181)])
    hoof_fh = poly([(47, 180), (54, 180), (54, 184), (46, 184)])
    # 另一只靴子：在马腹那一侧，压在前腿后面
    boot_far = poly([(32, 152), (38, 154), (37, 160), (35, 163), (31, 162), (30, 157)])
    c.paint(outer(boot_far), LEATHER[0])
    c.paint(boot_far, LEATHER[2])
    for m in (far_hind,):
        c.paint(outer(m), line_c)
        c.paint(m, IVORY[2])
    c.paint(outer(far_fore), line_c)
    c.paint(far_fore, WHITE[3])
    c.paint(far_fore & ~shift(far_fore, -2, 0), WHITE[2])
    for m in (hoof_ff, hoof_fh):
        c.paint(outer(m), line_c)
        c.paint(m, HOOF[2])
        c.paint(m & ~shift(m, 0, 1), HOOF[3])

    # 马尾：一大束从尾根甩出，末端分三条发梢
    mass = blob([(72, 131), (79, 131), (86, 137), (92, 146), (94, 154), (90, 158), (84, 156), (82, 148), (78, 141), (73, 138)])
    wisps = [taper([(90, 150), (93, 158), (91, 165)], 5, 1),
             taper([(87, 152), (88, 160), (84, 167)], 5, 1),
             taper([(83, 150), (83, 158), (79, 164)], 4, 1)]
    tail_h = mass | wisps[0] | wisps[1] | wisps[2]
    c.paint(outer(tail_h), line_c)
    c.paint(tail_h, WHITE[4])
    c.paint(tail_h & ~shift(tail_h, -2, -1), IVORY[3])
    c.fold([(76, 134), (83, 141), (87, 150), (86, 160)], IVORY[2], tail_h)
    c.fold([(80, 133), (88, 141), (92, 151), (91, 160)], IVORY[2], tail_h)

    # 马身、前腿、后腿
    torso_h = blob([(27, 135), (34, 128), (46, 128), (60, 128), (68, 130), (73, 135), (75, 143), (75, 152), (70, 155), (60, 155), (48, 156), (38, 157), (30, 152), (26, 146), (25, 140)])
    near_fore = poly([(36, 150), (44, 152), (43, 160), (42, 165), (39, 170), (38, 176), (36, 180), (37, 188), (30, 187), (32, 181), (31, 178), (34, 171), (35, 164)])
    near_hind = poly([(64, 150), (75, 148), (76, 156), (80, 161), (82, 165), (81, 172), (83, 178), (84, 184), (77, 184), (78, 179), (76, 172), (71, 165), (66, 158)])
    neck_h = poly([(28, 122), (34, 114), (35, 103), (40, 106), (46, 118), (50, 130), (46, 134), (27, 136), (28, 128)])
    horse = torso_h | near_fore | near_hind | neck_h
    c.paint(outer(horse), line_c)
    c.paint(horse, WHITE[3])
    c.paint(horse & ~shift(horse, -2, -2), WHITE[2])
    c.paint(horse & ~shift(horse, 2, 1), WHITE[4])
    # 腿和身子的分界
    c.fold([(44, 152), (42, 150), (38, 150)], IVORY[1], horse)
    c.fold([(64, 150), (70, 147), (75, 146)], IVORY[2], horse)
    c.paint(poly([(31, 124), (35, 118), (36, 126), (33, 133)]) & horse, WHITE[2])
    c.fold([(30, 150), (36, 153), (44, 152)], IVORY[2], horse)
    # 蹄
    hoof_nf = poly([(30, 186), (37, 186), (37, 191), (27, 191)])
    hoof_nh = poly([(77, 183), (84, 183), (85, 187), (76, 187)])
    for m in (hoof_nf, hoof_nh):
        c.paint(outer(m) & (YY <= 191), line_c)
        c.paint(m, HOOF[2])
        c.paint(m & ~shift(m, 0, 1), HOOF[3])

    # 胸带：一道金色的 V，缰绳从笼头接过来
    collar = poly([(26, 134), (32, 141), (48, 129), (50, 131), (32, 144), (25, 137)])
    c.paint(outer(collar) & horse, GOLD[1])
    c.paint(collar, GOLD[3])
    c.paint(collar & ~shift(collar, 0, -1), GOLD[2])
    girth = poly([(44, 155), (52, 146), (53, 148), (46, 156)])
    c.paint(girth, GOLD[3])
    c.paint(outer(girth) & horse, GOLD[1])

    # 女孩的尾巴：从腰后甩出
    tail(c, [(60, 120), (66, 118), (69, 111), (71, 106), (75, 105)], HAIR_BROWN, 4, 3)

    # 裤子与靴：一条腿垂在马身这一侧
    boot = poly([(53, 150), (60, 149), (61, 158), (62, 164), (61, 167), (58, 168), (54, 168), (52, 165), (53, 160)])
    c.shape(boot, LEATHER, base=3, shadow=2, hi=1, line_color=LEATHER[0])
    c.paint(line([(53, 161), (61, 160)]) & boot, LEATHER[1])
    c.paint(rect(53, 150, 57, 152) & shrink(boot), LEATHER[4])
    trouser = poly([(46, 123), (58, 120), (62, 126), (60, 131), (58, 135), (60, 141), (60, 146), (59, 150), (51, 150), (49, 146), (48, 140), (46, 130)])
    c.shape(trouser, SAGE, base=3, shadow=2, hi=1, line_color=SAGE[0])
    c.fold([(50, 128), (54, 132), (58, 134)], SAGE[1], trouser)
    c.fold([(51, 136), (53, 141), (56, 144)], SAGE[1], trouser)
    c.paint(rect(50, 147, 60, 147) & shrink(trouser), SAGE[1])

    # 上衣下摆
    hem = poly([(47, 117), (61, 117), (64, 124), (58, 128), (48, 127), (44, 123)])
    c.shape(hem, IVORY, base=3, shadow=2, hi=1, line_color=IVORY[0])
    c.trim([(44, 123), (48, 127), (58, 128), (64, 124)], hem, smooth=False)

    # 上身
    torso = poly([(47, 99), (50, 95), (60, 95), (63, 99), (62, 105), (60, 111), (47, 111), (46, 105)])
    c.shape(torso, IVORY, base=3, shadow=2, hi=1, line_color=IVORY[0])
    c.paint(poly([(50, 104), (58, 105), (54, 107)]), IVORY[1])

    # 马鬃：压在上衣下摆之前，顺着脖子披下去
    mane = poly([(33, 99), (40, 104), (37, 105), (43, 110), (40, 111), (46, 118), (43, 118), (48, 126), (45, 127), (50, 132), (45, 131), (41, 122), (37, 113), (34, 105)])
    c.paint(outer(mane), line_c)
    c.paint(mane, WHITE[4])

    # 马头：逐格精灵，再压金笼头
    c.sprite(17, 94, HORSE_HEAD, {'o': line_c, 'w': WHITE[3], 'W': WHITE[4], 'g': IVORY[2], 'G': IVORY[1], 'k': INK, 'e': LEATHER[1], 'h': '#ffffff', 'n': IVORY[0]})
    # 笼头：额带、鼻带、颊带、圆环，缰绳接到胸带
    c.paint(line([(33, 102), (34, 106), (34, 110), (32, 114), (30, 118)]), GOLD[3])
    c.paint(line([(22, 115), (24, 114), (26, 115), (28, 116), (30, 118)]), GOLD[3])
    c.pxs([(29, 118), (30, 119), (31, 119), (30, 120), (31, 118)], GOLD[2])
    c.paint(curve([(32, 120), (37, 126), (44, 130), (50, 130)]) & ~collar, GOLD[3])

    # 腰带：金布一束，右侧垂下一长条，压在马背上
    sash_tail = poly([(56, 117), (61, 116), (70, 128), (74, 136), (66, 143), (62, 138), (58, 126)])
    c.shape(sash_tail, GOLD, base=3, shadow=2, hi=0, line_color=GOLD[1])
    c.fold([(60, 118), (65, 128), (68, 138)], GOLD[2], sash_tail)
    sash = poly([(46, 111), (61, 110), (62, 116), (46, 118)])
    c.shape(sash, GOLD, base=3, shadow=1, hi=1, line_color=GOLD[1])
    c.fold([(47, 114), (60, 113)], GOLD[2], sash)
    # 腰间单枚蓝青菱印
    gem(c, 59, 114, 4)

    # 双臂：向两侧大大张开，上臂从短袖里伸出，手掌张开，什么也没拿
    arm_l = poly([(24, 83), (41, 91), (40, 97), (24, 87)])
    arm_r = poly([(87, 86), (70, 92), (69, 99), (88, 90)])
    for m in (arm_l, arm_r):
        c.paint(outer(m), SKIN[0])
        c.paint(m, SKIN[3])
        c.paint(m & ~shift(m, 0, -1), SKIN[2])
    c.sprite(11, 75, OPEN_PALM, PALM_PAL)
    c.sprite(86, 78, OPEN_PALM, PALM_PAL, flip=True)
    # 短袖：金边袖口
    sleeve_l = poly([(41, 90), (50, 95), (48, 101), (45, 100), (40, 98)])
    sleeve_r = poly([(61, 95), (70, 91), (70, 100), (62, 101)])
    for m in (sleeve_l, sleeve_r):
        c.shape(m, IVORY, base=3, shadow=1, hi=1, line_color=IVORY[0])
    c.paint(line([(41, 90), (40, 98)]), GOLD[3])
    c.paint(line([(70, 91), (70, 100)]), GOLD[3])

    # 颈与领口：立领金边，V 形开口
    neck = poly([(52, 90), (58, 90), (58, 94), (55, 99), (52, 94)])
    c.paint(neck, SKIN[3])
    c.paint(rect(52, 90, 58, 91), SKIN[2])
    c.paint(line([(51, 94), (55, 100), (59, 94)]), GOLD[3])
    c.paint(rect(51, 93, 52, 94), GOLD[3])
    c.paint(rect(58, 93, 59, 94), GOLD[3])

    # 头
    head_pal = {'0': HAIR_BROWN[0], '1': HAIR_BROWN[1], '2': HAIR_BROWN[2], '3': HAIR_BROWN[3], '4': HAIR_BROWN[4],
                'p': PINK[1], 'q': PINK[2], 'G': GOLD[3], 'Y': GOLD[4],
                'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0],
                'k': HAIR_BROWN[0], 'b': PINK[2], 'm': '#7a3540', 't': '#ffffff', 'r': '#c8605c'}
    for j, (x_start, row) in enumerate(HEAD_ROWS):
        c.sprite(x_start, HEAD_TOP + j, [row], head_pal)
    # 发梢的小卷
    c.pxs([(40, 85), (40, 86), (39, 87), (40, 88)], HAIR_BROWN[1])
    c.pxs([(67, 83), (68, 83), (69, 82)], HAIR_BROWN[1])

    return c
