"""17 星星：银发猫耳的女子跪在池边，一足入水，两只带菱印的金壶分别向水池和土地倾注。
天上一颗八芒主星加七颗小星；土地上两株新芽，右边一棵树、一只鸟；池水里的星光倒影只比水面略亮，不发光。"""
from figures import *

# 七颗小星：胖核加四条长臂、四条短臂，八芒轮廓在原尺寸也数得清
SMALL_STAR = [
    '......t......',
    '......t......',
    '......t......',
    '...t.ttt.t...',
    '....ttttt....',
    '...ttcccss...',
    'tttttcCcsssss',
    '...ttcccss...',
    '....sssss....',
    '...s.sss.s...',
    '......s......',
    '......s......',
    '......s......',
]
STAR_PAL = {'t': IVORY[4], 's': IVORY[3], 'c': WHITE[4], 'C': GOLD[5]}

# 新芽：两片对生的小叶
SPROUT = [
    'GGG.......ggg',
    '.GGGG...gggg.',
    '..GGGGsgggg..',
    '....GGsgg....',
    '......s......',
    '......s......',
    '......s......',
]
SPROUT_PAL = {'G': LEAF[4], 'g': GRASS[4], 's': LEAF[3]}

# 草丛：原版矮草那种竖直的尖刺
TUFT_L = [
    '...k...k..',
    '.k.k..kk..',
    '.kk.k.k.k.',
    'k.kkkkk.k.',
    '.kkkkkkkk.',
]
TUFT_S = [
    '.k..k.',
    '.kk.k.',
    'k.kkk.',
    '.kkkkk',
]

# 鸟：朝左落在枝上
BIRD = [
    '..oo......',
    '.owwo.....',
    'bowkwooo..',
    '.owwwwwoo.',
    '..owWWWWoo',
    '...oWWWo.o',
    '....oo....',
]


# 头：比公用的 L 头略大、向左下微倾，读作低头看水。每行 (起始 x, 像素串)，从 y=52 起。
# 0–4 银发色阶，W/w 耳背，q/r 内耳，l/s/d/x 肤色，k 眼线，e/p/h 虹膜、瞳孔、高光，b 眉，m 嘴。
HEAD_TOP = 52
HEAD_ROWS = [
    (61, 'WW'),
    (60, 'WWW'),
    (59, 'WWqW'),
    (50, '00000...WWqqW'),
    (47, '000333300.WWWqrqW'),
    (39, 'W......0233443320WWWqrrqW'),
    (39, 'WWWw..02334433220WWqqrrqW'),
    (39, 'WqqWWW023333322220WqqrrqW0'),
    (40, 'WqqqW02233322221WWqqrrqW0'),
    (40, 'WWqrqW022332122221WqrrqW10'),
    (41, 'WqrqW02232122233211qrqW120'),
    (41, 'WWqW0222211223332221qqW120'),
    (42, 'WqW022212112233222211W1220'),
    (43, 'WW0221221s122332222111220'),
    (45, '022121lls122322222122210'),
    (44, '0221221llls12232222122210'),
    (44, '022121llllls1222221122210'),
    (44, '021221lllllls112221s122210'),
    (44, '02121lllllllbbbl111ss12210'),
    (44, '02121llllllllllll1ssd12210'),
    (44, '02121lbblllkkkkllllsd12210'),
    (44, '0211llllllllhepllllsd12210'),
    (44, '0211kkkkllllepellllsd12210'),
    (45, '011lhepllllllllllsd112210'),
    (46, '01lepeldlllllllssd12210'),
    (47, '0slllldllllllssdx1210'),
    (48, 'slllllllllllsdx'),
    (49, 'sllllmllmlsdx'),
    (51, 'slllmmlsdx'),
    (53, 'dsssdx'),
]


def star_arms(cx, cy, rv, rh, rd, ri):
    """主星八条尖臂的三角片。每条臂返回两片：沿轴线切开的左半和右半。"""
    tips = [(0, -rv), (rd, -rd), (rh, 0), (rd, rd), (0, rv), (-rd, rd), (-rh, 0), (-rd, -rd)]
    arms = []
    for i, (tx, ty) in enumerate(tips):
        a0 = (i - 0.5) * np.pi / 4 - np.pi / 2
        a1 = (i + 0.5) * np.pi / 4 - np.pi / 2
        p0 = (cx + ri * np.cos(a0), cy + ri * np.sin(a0))
        p1 = (cx + ri * np.cos(a1), cy + ri * np.sin(a1))
        arms.append(((cx, cy), p0, p1, (cx + tx, cy + ty)))
    return arms


def draw_star(c, cx, cy):
    """主星：罗盘玫瑰式的两面明暗，迎光的半边更亮。"""
    arms = star_arms(cx, cy, 18, 20, 13, 6)
    whole = blank()
    for o, p0, p1, tip in arms:
        whole |= poly([o, p0, tip]) | poly([o, p1, tip])
    # 光的范围：贴着星铺一圈暗色抖动
    c.aura(whole, NAVY[2], n=2, t=0.5)
    c.glow(whole, GOLD[3])
    for o, p0, p1, tip in arms:
        pick = p0 if (p0[0] + p0[1]) < (p1[0] + p1[1]) else p1
        c.glow(poly([o, pick, tip]), GOLD[4])
    c.glow((np.abs(XX - cx) + np.abs(YY - cy)) <= 3, GOLD[4])
    c.glow((np.abs(XX - cx) + np.abs(YY - cy)) <= 2, GOLD[5])


# 金壶：圆肚、提环、向右下探出的壶嘴，壶腹一枚蓝青菱印。'k' 是壶嘴口
JUG = [
    '.....oooooo........',
    '....oGGGGGGo.......',
    '...oGoooooGGo......',
    '...oGo....oGo......',
    '...oGo.....oGo.....',
    '...oGo.....oGo.....',
    '..ooGoooooooGoo....',
    '.oYYYGGGGGGGGGoo...',
    'oYYYGGGGGGGGGDDGo..',
    'oYYGGGGGGGGGGDGGGo.',
    'oYGGGGGtGGGGGDGGGo.',
    'oGGGGGtTtGGGDDoGGGo',
    'oGGGGtTTTtGGDo.oGGo',
    'oGGGGGtTtGGGDo.oDGo',
    'oDGGGGGtGGGDDo.oDDo',
    '.oDGGGGGGGDDo..okko',
    '..oDDDDDDDDo.......',
    '...oooooooo........',
]
JUG_PAL = {'o': GOLD[1], 'G': GOLD[3], 'Y': GOLD[4], 'D': GOLD[2], 'k': GOLD[0], 't': TEAL[3], 'T': TEAL[4]}


def draw():
    c = Card('17_star')
    everything = rect(0, 0, W - 1, H - 1)

    # 夜空：只在地平线附近略亮一档，星群后面保持干净
    c.vgrad(everything, [NAVY[1], NAVY[2]], 78, 104)

    # 主星与七颗小星：一大七小，围成一圈，严格左右成对
    cx, cy = 56, 33
    draw_star(c, cx, cy)
    for sx, sy in [(56, 7), (32, 15), (80, 15), (21, 32), (91, 32), (32, 49), (80, 49)]:
        c.sprite(sx - 6, sy - 6, SMALL_STAR, STAR_PAL, glow='tscC')

    # 树：一株，树冠是平底叶团，呼应原版橡树
    trunk = taper([(104, 100), (103, 88), (101, 80), (99, 76)], 6, 3)
    limb_l = taper([(101, 80), (95, 77), (88, 76), (84, 74)], 3, 1)
    limb_r = taper([(102, 78), (106, 72), (109, 68)], 3, 1)
    limb_u = taper([(100, 77), (101, 68), (102, 60)], 3, 2)
    for m in (limb_l, limb_r, limb_u):
        c.paint(m, WOOD[2])
    c.bark(trunk, WOOD, seed=31)
    crown = (rect(96, 55, 111, 59) | rect(92, 58, 111, 62) | rect(100, 53, 110, 55)
             | rect(81, 64, 100, 68) | rect(84, 61, 96, 64) | rect(86, 68, 97, 70)
             | rect(80, 72, 89, 75) | rect(82, 70, 87, 72)
             | rect(105, 66, 111, 71))
    c.leaves(crown, LEAF, seed=32)
    c.paint(crown & ~shift(crown, 0, -1), LEAF[0])
    c.paint(crown & ~shift(crown, 0, 1), LEAF[3])

    # 鸟：落在左枝上
    c.sprite(90, 69, BIRD, {'o': SLATE[0], 'w': SKY[4], 'W': SKY[5], 'k': INK, 'b': GOLD[3]})

    # 远山：方块阶梯边
    far_l = blocky(poly([(0, 92), (9, 89), (20, 91), (30, 95), (46, 101), (46, 112), (0, 112)]), 2)
    far_r = blocky(poly([(66, 106), (85, 98), (100, 92), (112, 88), (112, 112), (66, 112)]), 2)
    for m in (far_l, far_r):
        c.paint(m, SAGE[2])
        c.paint(m & ~shift(m, 0, 2), SAGE[3])
    mid = blocky(poly([(0, 100), (10, 98), (24, 100), (46, 106), (70, 106), (90, 101), (112, 96), (112, 156), (0, 156)]), 2)
    c.paint(mid, SAGE[1])
    c.speckle(mid, [SAGE[0], SAGE[2]], 0.08, 33)

    # 前景岸地：草方块，夜里压暗一档
    land = blocky(poly([(0, 141), (14, 140), (26, 142), (40, 148), (70, 152), (84, 149), (96, 147), (104, 144), (112, 142), (112, 191), (0, 191)]), 2)
    c.paint(land, SAGE[2])
    c.speckle(land, [SAGE[3], SAGE[1], SAGE[3]], 0.26, 34)
    c.paint(land & ~shift(land, 0, 1), SAGE[4])

    # 土地：灌溉的那块泥土
    soil = blocky(poly([(44, 191), (52, 181), (62, 175), (72, 171), (82, 165), (92, 163), (102, 166), (112, 169), (112, 182), (100, 184), (90, 187), (72, 188), (58, 187)]), 2)
    c.soil(soil, DIRT, seed=35)
    c.paint(soil & ~shift(soil, 0, 1), DIRT[1])

    # 池水：上岸沿露一条泥土侧面，下面才是水
    pool = blocky(poly([(0, 152), (8, 151), (22, 152), (34, 154), (42, 158), (50, 163), (58, 168), (56, 172), (46, 176), (38, 181), (30, 186), (24, 191), (0, 191)]), 2)
    c.soil(pool, DIRT, seed=36, base=1)
    water = pool & shift(pool, 0, 3)
    c.water(water, SKY, seed=37, base=2)
    c.paint(water & ~shift(water, 0, 2), SKY[1])

    # 涟漪：以水流落点为心的三道同心弧，开几个缺口，像水面上散开的圈
    def arc(cx0, cy0, rx, ry, gaps):
        ang = np.degrees(np.arctan2((YY - cy0) * rx / ry, XX - cx0)) % 360
        m = ring(cx0, cy0, rx, ry) & water
        for g0, g1 in gaps:
            m &= ~((ang >= g0) & (ang <= g1))
        return m
    c.paint(arc(19, 172, 9, 2, [(150, 200)]), SKY[5])
    c.paint(arc(19, 172, 16, 4, [(100, 140), (280, 330)]), SKY[4])
    c.paint(arc(19, 172, 24, 7, [(60, 110), (190, 250), (300, 340)]), SKY[4])

    # 星光倒影：只比水面略亮，不写进发光层
    c.star4(26, 184, 3, color=SKY[3], core=SKY[4], glow=False)
    c.star4(43, 165, 2, color=SKY[3], core=SKY[4], glow=False)
    c.star4(7, 160, 2, color=SKY[3], core=SKY[4], glow=False)
    c.star4(9, 186, 2, color=SKY[3], core=SKY[4], glow=False)

    # 草丛
    tuft = {'k': SAGE[1]}
    c.sprite(4, 145, TUFT_L, tuft)
    c.sprite(15, 147, TUFT_L, tuft)
    c.sprite(90, 153, TUFT_L, tuft)
    c.sprite(99, 160, TUFT_S, tuft)
    c.sprite(62, 165, TUFT_S, tuft)

    # 猫尾：从裙后绕出，向右上甩
    tail(c, [(73, 130), (79, 129), (84, 122), (87, 113), (92, 105), (98, 102), (102, 105)], HAIR_SILVER, 5, 4, base=3)

    # 长发后片：贴着头，披过肩，发梢分几绺
    hair_back = blob([(46, 66), (56, 60), (66, 62), (70, 70), (73, 78), (77, 85), (79, 93), (78, 97), (82, 102), (83, 108), (78, 104), (74, 110), (50, 111), (43, 110), (41, 104), (43, 96), (41, 88), (42, 80), (41, 74)])
    hair_paint(c, hair_back, HAIR_SILVER, base=2, line_edge=True)
    c.fold([(72, 78), (76, 90), (79, 102)], HAIR_SILVER[1], hair_back)
    c.fold([(44, 84), (43, 96), (46, 106)], HAIR_SILVER[1], hair_back)

    # 后脚：跪着那条腿的脚掌从裙下露出
    foot_back = blob([(74, 145), (78, 143), (81, 148), (83, 153), (81, 155), (75, 155), (72, 152)])
    c.shape(foot_back, SKIN, base=3, shadow=1, hi=0, line_color=SKIN[0])

    # 裙子：后片（膝下垂下的那层，压在小腿之后）
    skirt_back = poly([(30, 118), (25, 128), (23, 136), (24, 146), (29, 153), (34, 158), (41, 158), (44, 140), (38, 124)])
    c.shape(skirt_back, IVORY, base=2, shadow=1, hi=0, line_color=IVORY[0])

    # 小腿与赤足：足尖朝左下，入水
    shin = poly([(30, 131), (36, 131), (38, 138), (37, 148), (36, 158), (37, 164), (31, 164), (31, 152), (30, 142)])
    c.shape(shin, SKIN, base=3, shadow=1, hi=0, line_color=SKIN[0])
    foot = poly([(31, 160), (37, 160), (38, 165), (35, 169), (25, 170), (24, 168), (29, 165)])
    c.paint(foot, SKIN[3])
    c.paint(edge(foot), SKIN[0])
    c.paint(rect(31, 158, 36, 164) & shrink(shin), SKIN[3])
    c.pxs([(35, 166), (36, 165), (34, 168)], SKIN[2])
    c.pxs([(26, 169), (28, 169), (30, 169), (32, 169)], SKIN[1])
    # 水没过足底：最后一行压回水色，涟漪绕着脚踝
    c.paint(rect(24, 170, 38, 170), SKY[3])

    # 裙子主体：盖过膝盖，裙摆铺在地上
    skirt = blob([(38, 108), (31, 112), (27, 120), (25, 130), (29, 132), (33, 129), (38, 134), (41, 145), (40, 158), (46, 162), (60, 162), (72, 158), (77, 148), (77, 132), (73, 118), (62, 112), (50, 109)])
    c.shape(skirt, IVORY, base=4, shadow=2, hi=0, line_color=IVORY[0])
    fold = IVORY[2]
    for pts, w0, w1 in [
        ([(30, 114), (28, 120), (27, 127)], 1, 2),
        ([(36, 113), (39, 119), (40, 124)], 1, 2),
        ([(43, 116), (47, 125), (51, 133)], 1, 4),
        ([(38, 126), (40, 136), (42, 146)], 1, 3),
        ([(54, 118), (60, 126), (64, 132)], 1, 3),
        ([(44, 152), (52, 150), (62, 146)], 3, 1),
        ([(52, 160), (62, 156), (72, 148)], 3, 1),
        ([(66, 118), (72, 130), (74, 140)], 1, 3),
    ]:
        c.paint(taper(pts, w0, w1) & shrink(skirt), fold)
    # 大腿与小腿的分界
    c.fold([(41, 158), (44, 150), (50, 143)], IVORY[1], skirt)

    # 上身
    torso = poly([(50, 84), (72, 84), (75, 100), (74, 112), (50, 110), (47, 96)])
    c.shape(torso, IVORY, base=4, shadow=2, hi=0, line_color=IVORY[0])
    c.paint(poly([(52, 104), (60, 96), (58, 104)]), IVORY[2])
    c.paint(poly([(62, 106), (70, 96), (68, 104)]), IVORY[2])

    # 领口与颈：方领开到锁骨下，一小道缺口
    neck = poly([(54, 78), (62, 77), (63, 82), (67, 83), (65, 88), (59, 90), (54, 85)])
    c.paint(neck, SKIN[3])
    c.paint(poly([(54, 78), (62, 77), (60, 82), (56, 84)]), SKIN[2])
    c.paint(line([(54, 85), (58, 90), (65, 88), (67, 83)]), IVORY[1])
    c.pxs([(59, 91), (59, 92), (60, 93)], IVORY[1])
    c.pxs([(58, 86), (59, 87), (63, 85), (64, 85)], SKIN[1])

    # 腰带：灰绿布，结在右腰，两条带尾垂下
    sash = poly([(48, 104), (60, 108), (73, 110), (74, 113), (60, 113), (48, 109)])
    c.shape(sash, SAGE, base=3, shadow=1, hi=0, line_color=SAGE[0])
    tail_a = poly([(62, 111), (66, 111), (67, 122), (63, 120)])
    tail_b = poly([(65, 111), (68, 111), (74, 134), (68, 139), (64, 122)])
    c.shape(tail_b, SAGE, base=3, shadow=1, hi=0, line_color=SAGE[0])
    c.shape(tail_a, SAGE, base=3, shadow=1, hi=0, line_color=SAGE[0])

    # 右臂（画面右）：袖子垂到腕，小臂伸向壶
    forearm_r = taper([(76, 114), (82, 121), (88, 127)], 5, 4)
    c.volume(forearm_r, SKIN, 3, shadow=1, hi=0)
    sleeve_r = poly([(70, 84), (75, 92), (78, 104), (82, 111), (82, 114), (75, 118), (72, 113), (68, 100)])
    c.shape(sleeve_r, IVORY, base=4, shadow=2, hi=0, line_color=IVORY[0])
    c.paint(line([(72, 112), (80, 110)]) & sleeve_r, IVORY[1])
    c.paint(poly([(73, 92), (76, 102), (73, 104)]) & sleeve_r, IVORY[2])

    # 左臂（画面左）：袖子向外撑开，小臂伸向壶
    forearm_l = taper([(30, 100), (24, 101), (18, 105)], 5, 4)
    c.volume(forearm_l, SKIN, 3, shadow=1, hi=0)
    sleeve_l = poly([(47, 83), (38, 85), (31, 91), (27, 94), (26, 100), (28, 105), (33, 105), (37, 99), (47, 93)])
    c.shape(sleeve_l, IVORY, base=4, shadow=2, hi=0, line_color=IVORY[0])
    c.paint(poly([(36, 92), (42, 90), (37, 96)]), IVORY[2])
    c.paint(line([(31, 93), (33, 104)]) & sleeve_l, IVORY[1])

    # 双壶与水流：两股水各有去向
    c.sprite(6, 108, JUG, JUG_PAL, glow='tT')
    c.sprite(83, 131, JUG, JUG_PAL, flip=True, glow='tT')

    # 左股：落入池中。逐段写中心线，台阶干净，不靠圆笔碰运气
    for y0, y1, xc in [(124, 129, 22), (130, 135, 21), (136, 143, 20), (144, 153, 19), (154, 171, 18)]:
        c.glow(rect(xc - 1, y0, xc + 1, y1), TEAL[3])
        c.glow(rect(xc - 1, y0, xc - 1, y1), TEAL[4])
    c.glow(rect(13, 171, 25, 172), TEAL[3])
    c.glow(rect(15, 171, 23, 171), TEAL[4])

    # 右股：落到土地上分成四支，灌溉新芽
    x0 = 85
    c.glow(rect(x0 - 1, 147, x0 + 1, 162), TEAL[3])
    c.glow(rect(x0 - 1, 147, x0 - 1, 162), TEAL[4])
    branches = [
        [(x0 - 1, 160), (79, 167), (71, 171), (65, 177)],
        [(x0 - 1, 162), (81, 169), (76, 176), (67, 186)],
        [(x0 + 1, 162), (88, 171), (90, 180), (87, 187)],
        [(x0 + 1, 160), (92, 167), (101, 172), (107, 184)],
    ]
    for pts in branches:
        c.glow(taper(pts, 1.6, 2.8), TEAL[3])

    # 新芽：亮绿，压在土上
    for sx, sy in [(57, 174), (94, 174)]:
        c.paint(outer(c.sprite_mask(sx, sy, SPROUT)) & (YY > sy + 1), DIRT[0])
        c.sprite(sx, sy, SPROUT, SPROUT_PAL)

    # 头：向左下微倾，低头看水，浅笑
    head_pal = {'0': HAIR_SILVER[0], '1': HAIR_SILVER[1], '2': HAIR_SILVER[2], '3': HAIR_SILVER[3], '4': HAIR_SILVER[4],
                'W': IVORY[4], 'w': IVORY[3], 'q': PINK[1], 'r': PINK[2],
                'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0],
                'k': SLATE[0], 'e': SLATE[2], 'p': SLATE[1], 'h': '#ffffff', 'b': HAIR_SILVER[1], 'm': SKIN[0]}
    for j, (x_start, row) in enumerate(HEAD_ROWS):
        c.sprite(x_start, HEAD_TOP + j, [row], head_pal)
    # 银发前绺垂到胸前：亮面加一条浅描边，别让细绺全变成暗线
    def soft_lock(pts, w0, w1):
        m = taper(pts, w0, w1)
        c.paint(m, HAIR_SILVER[3])
        c.paint(edge(m), HAIR_SILVER[1])
    soft_lock([(46, 76), (45, 84), (47, 92), (51, 98), (48, 106)], 5, 2)
    soft_lock([(66, 78), (65, 85), (69, 92), (65, 100)], 5, 2)
    soft_lock([(70, 78), (73, 86), (74, 96)], 3, 1.5)

    # 双手：握住提环，手指压在环上
    grip_l = [
        '...xxxxx.',
        '..xllllls',
        '.xlllllls',
        'xlllllll.',
        'xlllllss.',
        'xlldlldl.',
        'xlldlldl.',
        '.ss.ss.s.',
    ]
    hand_pal = {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0]}
    c.sprite(10, 103, grip_l, hand_pal)
    c.sprite(87, 126, grip_l, hand_pal, flip=True)

    return c
