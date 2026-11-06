"""14 节制：银发中性猫耳人形正面而立，高杯向低杯连续倾注，水流里三枚蓝青节点；一足踏岸石，一足入水。
身后一对大翼，胸前方中三角，远路通向山上的冠状光点，岸边两株鸢尾。"""
from figures import *

# 暖白的天、灰绿的草甸和披帛、米白的长袍
AIR = ['#e7d3a8', '#eddcb9', '#f3e6c9']
MIST = ['#3f5c60', '#54747a', '#6d8d8e']
MEADOW = ['#5d7346', '#6f8653', '#829962', '#96ab74']
ROCK = ['#4f4636', '#7a6c55', '#a2927a', '#c1b299', '#d9ccb3']
FEATHER = ['#8b7f64', '#b7aa8c', '#dcd2b8', '#f2ebd8', '#fdfaf0']
ROBE = ['#6d6148', '#a39678', '#cfc3a6', '#ece3cc', '#fbf6e8']
SILVER = ['#77738e', '#9c98b0', '#bdbacb', '#dcdae6', '#f6f5f9']


IRIS_L = [
    '.......P.......',
    '......PLP......',
    '...p..PLP..p...',
    '..PPp.PLP.pPP..',
    '.PLLPpPYPpPLLP.',
    'PLLLPYYYYYPLLLP',
    'PLLPp.YYY.pPLLP',
    '.PP..pPPPp..PP.',
    '.....PLLLP.....',
    '......PLP......',
    '.......P.......',
]
IRIS_S = [
    '.....P.....',
    '....PLP....',
    '.p..PLP..p.',
    'PPp.PLP.pPP',
    'PLLPYYYPLLP',
    'PLPpYYYpPLP',
    '.p.pPPPp.p.',
    '....PLP....',
    '.....P.....',
]


def draw():
    c = Card('14_temperance')
    everything = rect(0, 0, W - 1, H - 1)

    # 暖白天空
    c.vgrad(everything, AIR, 0, 128)

    # 头后的金色日盘：被牌顶切掉一部分
    halo = ellipse(55, 4, 10)
    c.glow(halo, GOLD[3])
    c.glow(halo & ~shift(halo, -1, -1), GOLD[2])

    # 远山与山顶的冠状光点
    mount = blocky(poly([(78, 130), (88, 122), (99, 114), (104, 116), (112, 122), (112, 132), (78, 132)]), 2)
    c.paint(mount, SLATE[3])
    c.paint(mount & (XX + YY * 0.6 > 170), SLATE[2])
    c.sprite(96, 106, ['G..G..G', 'GG.G.GG', 'GGGGGGG', '.ggggg.'], {'g': GOLD[3], 'G': GOLD[4]}, glow='gG')
    hills_r = blocky(poly([(70, 132), (80, 126), (88, 129), (96, 127), (112, 124), (112, 134), (70, 134)]), 2)
    c.paint(hills_r, MIST[1])
    hills_l = blocky(poly([(0, 126), (8, 124), (18, 127), (30, 130), (40, 134), (0, 134)]), 2)
    c.paint(hills_l, MIST[1])
    c.paint(hills_l & ~shift(hills_l, 0, -1), MIST[2])

    # 草甸
    meadow = rect(0, 132, W - 1, 180)
    c.paint(meadow, MEADOW[2])
    c.speckle(meadow, [MEADOW[1], MEADOW[3]], 0.08, 41)

    # 远路：从山脚蜿蜒到近处
    road = poly([(102, 127), (91, 129), (99, 133), (92, 136), (78, 138), (78, 140), (92, 143), (97, 147), (87, 152), (79, 157), (80, 164), (92, 159), (105, 154), (111, 149),
                 (107, 146), (98, 142), (87, 139), (100, 136), (105, 133), (100, 131), (94, 129)])
    c.paint(road, SAND[3])
    c.paint(road & ~shift(road, 0, -1), SAND[4])

    # 水面
    pond = blocky(poly([(112, 165), (92, 167), (70, 169), (52, 172), (40, 178), (34, 184), (28, 192), (112, 192)]), 2)
    c.water(pond, SKY, seed=42, base=3)
    c.paint(outer(pond) & ~shift(pond, 0, 1) & rect(0, 0, W, H), MEADOW[0])

    # 翼：左翼手写，右翼镜像，大轮廓把人物包在中间
    wing_pts = [(35, 22), (33, 16), (29, 14), (23, 16), (15, 23), (8, 34), (3, 46), (1, 56), (1, 65), (4, 60), (3, 72), (3, 86), (7, 78), (7, 92), (12, 112), (15, 96), (18, 80), (20, 66), (24, 58), (32, 50), (40, 46), (42, 38), (37, 32)]
    feather_lines = [[(30, 20), (17, 36), (6, 58)], [(26, 32), (14, 50), (5, 70)], [(23, 44), (14, 60), (8, 80)], [(21, 56), (16, 72), (13, 96)]]
    for flip_it in (False, True):
        pts = flip_pts(wing_pts, 55) if flip_it else wing_pts
        if flip_it:
            pts = [(96, 100) if p_ == (98.0, 112) else p_ for p_ in pts]
        wing = poly(pts)
        c.paint(wing, FEATHER[4])
        c.paint(edge(wing), FEATHER[0])
        for ln in feather_lines:
            c.fold(flip_pts(ln, 55) if flip_it else ln, FEATHER[1], shrink(wing))

    # 长发后片
    back_l = poly([(47, 30), (46, 35), (47, 41), (49, 46), (49, 38), (49, 32)])
    back_r = poly([(63, 30), (64, 35), (63, 41), (61, 46), (61, 38), (61, 32)])
    c.paint(back_l, SILVER[2])
    c.paint(back_r, SILVER[1])
    c.paint(edge(back_l), SILVER[0])
    c.paint(edge(back_r), SILVER[0])

    # 猫尾：从袍后绕到右侧竖起
    tail(c, [(76, 118), (83, 119), (88, 114), (90, 106), (90, 98), (92, 92), (97, 91)], SILVER, 4, 3, base=3)

    # 袍身
    robe = poly([(40, 46), (70, 46), (72, 64), (73, 84), (76, 110), (80, 140), (84, 166), (74, 172), (57, 175), (40, 170), (24, 161), (28, 140), (33, 115), (38, 86), (38, 64)])
    c.shape(robe, ROBE, base=4, shadow=2, hi=0)
    # 大块硬边衣褶
    for pts in [[(36, 118), (34, 136), (28, 156), (36, 150)], [(47, 100), (44, 130), (41, 168), (46, 150)], [(66, 124), (70, 150), (72, 171), (76, 170), (72, 146)], [(76, 130), (80, 150), (83, 165), (78, 160)],
                [(58, 134), (60, 152), (59, 172), (63, 170), (62, 150)], [(40, 88), (36, 110), (38, 122), (42, 104)]]:
        c.paint(poly(pts) & shrink(robe), ROBE[2])

    # 下摆金边
    hem = poly([(24, 158), (40, 167), (57, 172), (74, 169), (83, 163), (84, 166), (74, 172), (57, 175), (40, 170), (24, 161)])
    c.paint(hem, GOLD[3])
    c.paint(hem & ~shift(hem, 0, 1), GOLD[2])
    c.paint(edge(robe) & rect(20, 158, 90, 178), GOLD[1])

    # 脚：一足踏石、一足入水
    rock_b = blocky(poly([(0, 176), (10, 174), (16, 178), (6, 186), (0, 188)]), 2)
    c.paint(rock_b, ROCK[2])
    c.paint(rock_b & ~shift(rock_b, 0, -2), ROCK[3])
    rock = blocky(poly([(8, 180), (20, 177), (36, 178), (42, 180), (46, 188), (36, 192), (4, 192)]), 2)
    c.paint(rock, ROCK[2])
    top_rock = rock & poly([(4, 192), (8, 180), (20, 177), (36, 178), (42, 180), (34, 184), (22, 186), (8, 187)])
    c.paint(top_rock, ROCK[3])
    c.paint(top_rock & ~shift(top_rock, 0, -1), ROCK[4])
    c.paint(rock & poly([(34, 185), (44, 182), (46, 188), (36, 192)]), ROCK[1])
    c.paint(outer(rock) & ~pond, ROCK[0])

    ankle_l = poly([(28, 160), (34, 162), (32, 172), (32, 177), (20, 181), (19, 179), (26, 172)])
    c.volume(ankle_l, SKIN, 3, shadow=1, hi=0)
    c.paint(edge(ankle_l), SKIN[0])
    c.pxs([(21, 181), (23, 182), (25, 182)], SKIN[1])
    c.paint(rect(28, 161, 33, 162), SKIN[1])

    ankle_r = poly([(70, 172), (76, 171), (77, 177), (79, 183), (71, 183), (72, 178)])
    c.volume(ankle_r, SKIN, 3, shadow=1, hi=0)
    c.paint(edge(ankle_r), SKIN[0])
    c.paint(rect(70, 172, 76, 173), SKIN[1])

    # 涟漪两道半：围着入水的脚
    for rx, ry in [(23, 5), (14, 3)]:
        c.paint(ring(76, 184, rx, ry) & ~ankle_r, SKY[5])
    c.paint(ring(75, 183, 6, 1) & ~rect(71, 180, 79, 183), SKY[5])
    c.paint(rect(72, 184, 79, 184), SKY[1])
    c.paint(rect(73, 186, 78, 186), SKY[1])

    # 鸢尾两株：剑形叶加两朵紫花
    for pts, tone in [([(10, 178), (8, 165), (2, 150)], LEAF[1]), ([(16, 178), (19, 168), (24, 158)], LEAF[1]), ([(8, 178), (4, 168), (0, 160)], LEAF[2]), ([(17, 178), (21, 172), (25, 169)], LEAF[2]), ([(12, 178), (13, 168), (12, 160)], LEAF[2])]:
        c.paint(taper(pts, 4, 1), tone)
    c.paint(line([(10, 144), (10, 176)]), LEAF[1])
    c.paint(line([(16, 158), (15, 176)]), LEAF[1])
    pal = {'p': PURPLE[2], 'P': PURPLE[3], 'L': PURPLE[4], 'Y': GOLD[4]}
    c.sprite(3, 134, IRIS_L, pal)
    c.sprite(11, 149, IRIS_S, pal)

    # 披帛：从肩头垂到腰的两条灰绿，金色外缘
    stole_l = poly([(40, 46), (46, 44), (50, 56), (53, 74), (45, 76), (41, 60)])
    stole_r = poly([(64, 44), (70, 46), (73, 50), (68, 64), (63, 76), (58, 74), (62, 60)])
    c.shape(stole_l, SAGE, base=3, shadow=2, hi=0)
    c.shape(stole_r, SAGE, base=3, shadow=2, hi=0)
    c.trim([(40, 48), (43, 62), (46, 76)], smooth=False)
    c.trim([(72, 49), (69, 62), (64, 75)], smooth=False)

    # 腰带与垂下的长带
    belt = poly([(43, 75), (52, 74), (62, 75), (64, 78), (62, 81), (52, 80), (44, 83), (42, 79)])
    c.shape(belt, SAGE, base=3, shadow=1, hi=0)
    c.fold([(44, 79), (52, 77), (60, 78)], SAGE[1], belt)
    sash = poly([(52, 80), (62, 78), (66, 118), (57, 131), (56, 145), (50, 139), (49, 134), (51, 108)])
    c.shape(sash, SAGE, base=2, shadow=2, hi=0)
    c.trim([(62, 79), (66, 118), (57, 130), (49, 134)], smooth=False)
    c.trim([(49, 136), (56, 144)], smooth=False)
    c.fold([(55, 84), (54, 110), (52, 130)], SAGE[1], sash)

    # 胸前方中三角：方框不发光，三角发光
    c.paint(rect(51, 45, 60, 55), ROBE[4])
    c.paint(edge(rect(51, 45, 60, 55)), GOLD[2])
    c.glow_px([(55, 48), (56, 48), (54, 49), (57, 49), (53, 50), (58, 50), (52, 51), (59, 51)] + [(x_, 52) for x_ in range(52, 60)], GOLD[3])

    # 立领
    c.paint(rect(53, 35, 57, 40), SKIN[2])
    collar = poly([(50, 39), (54, 41), (56, 41), (60, 39), (62, 44), (48, 44)])
    c.paint(collar, ROBE[3])
    c.paint(line([(50, 39), (54, 41)]) | line([(56, 41), (60, 39)]), GOLD[3])
    c.paint(line([(48, 44), (62, 44)]), GOLD[3])
    c.paint(line([(55, 41), (55, 44)]), GOLD[1])

    # 右袖（画面右）：托低杯
    sleeve_r = poly([(69, 46), (74, 49), (82, 74), (84, 91), (79, 118), (76, 117), (73, 97), (70, 90), (66, 74)])
    c.shape(sleeve_r, ROBE, base=4, shadow=2, hi=0)
    c.paint(poly([(73, 97), (76, 87), (81, 90), (78, 118)]), ROBE[1])
    c.trim([(76, 85), (81, 89), (78, 118)], smooth=False)

    # 低杯：金杯接水
    bowl = poly([(63, 80), (65, 86), (68, 88), (72, 88), (75, 86), (77, 80)])
    c.shape(bowl, GOLD, base=3, shadow=2, hi=1, line_color=GOLD[1])
    c.paint(rect(69, 88, 71, 94), GOLD[2])
    c.paint(rect(69, 88, 69, 94), GOLD[3])
    c.paint(rect(65, 95, 75, 96), GOLD[3])
    c.paint(rect(65, 96, 75, 96), GOLD[1])
    c.paint(rect(67, 94, 73, 94), GOLD[2])
    rim = ellipse(70, 79, 7, 1)
    c.paint(rim, GOLD[3])
    c.paint(ellipse(70, 79, 5, 0), TEAL[3])

    # 托杯的手：四指从前面绕过杯脚，手腕藏进右袖
    c.sprite(66, 86, ['.lllllllllls.', 'lddddlllllss.', 'lllllllllsss.', 'ldddddlllsss.', 'lllllllllss..', '.dddddllsss..', '..lllll.sd...'],
             {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1]})

    # 左袖（画面左）：大袖口垂下，手臂向上伸出
    sleeve_l = poly([(15, 49), (24, 46), (41, 47), (42, 62), (36, 72), (25, 88), (20, 100), (14, 88), (10, 67)])
    c.shape(sleeve_l, ROBE, base=4, shadow=2, hi=0)
    c.paint(poly([(13, 66), (16, 53), (19, 90)]), ROBE[1])
    c.trim([(16, 50), (12, 67), (15, 88), (20, 99)], smooth=False)
    c.paint(poly([(41, 60), (35, 70), (26, 84), (22, 96), (30, 72)]) & shrink(sleeve_l), ROBE[2])
    forearm = poly([(17, 51), (20, 45), (25, 43), (27, 47), (22, 52)])
    c.volume(forearm, SKIN, 3, shadow=1, hi=0)

    # 高杯：杯口朝右上张开，水从下唇倒出；杯脚是一块朝左下张开的楔形
    foot_h = poly([(31, 45), (24, 46), (30, 53)])
    c.paint(foot_h, GOLD[3])
    c.paint(foot_h & ~shift(foot_h, -1, -1), GOLD[2])
    c.paint(edge(foot_h) & ~shift(foot_h, 1, 1), GOLD[1])
    bowl_h = poly([(35, 34), (32, 37), (31, 41), (32, 44), (35, 46), (39, 47), (44, 45)])
    c.shape(bowl_h, GOLD, base=3, shadow=2, hi=1, line_color=GOLD[1])
    inner = poly([(35, 34), (39, 34), (43, 38), (45, 43), (44, 45)])
    c.paint(inner, GOLD[2])
    c.paint(edge(inner) & ~line([(35, 34), (44, 45)]), GOLD[1])
    c.paint(line([(35, 34), (44, 45)]), GOLD[5])

    # 握杯的手：拇指搭在杯口上方，三指扣住杯腹
    c.sprite(21, 37, ['.....lll.....', '....lllllll..', '...lllllllll.', '..llllldddd..', '.llllll.llls.', '.lllllldddd..', 'lllllll.llls.', 'lllllssdddd..', 'llllss..lls..', 'lllss...dd...', 'lss..........'],
             {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1]})

    # 头
    head(c, 55, 14, 'front', 'L', SILVER, BROWN_IRIS, mouth='soft')

    # 水流：从高杯口连续流进低杯，三枚节点串在水流上
    stream = [(43, 44), (45, 48), (47, 53), (52, 58), (57, 62), (62, 67), (66, 71), (68, 78)]
    c.glow(curve(stream, 5), TEAL[4])
    c.glow(curve(stream, 3), TEAL[3])
    c.glow(curve([(p[0] - 1, p[1]) for p in stream]), TEAL[4])
    c.glow(rect(66, 76, 69, 79), TEAL[3])
    for nx_, ny_ in [(47, 53), (57, 62), (65, 70)]:
        c.node(nx_, ny_, 3)

    return c
