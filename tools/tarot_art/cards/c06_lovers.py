"""6 恋人：黑发男与银发女相向而立，手伸向中央的蓝青节点却不接触；两段金线对等，上方翼形星标压在太阳前；左果树缠蛇、右火焰树，两条路在脚下合流。"""
from figures import *

CX = 55.5
SKIN_PAL = {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[1]}
CLOTH = HAIR_BLACK  # 男方的深紫黑外袍，恶魔牌会呼应这身
SNAKE = HAIR_BLACK
# 树叶：比 leaves 默认色阶反差小，树冠读成整团而不是噪点
CROWN_LIGHT = [SAGE[2], SAGE[3], SAGE[3], SAGE[3], SAGE[4]]
CROWN_MID = [SAGE[1], SAGE[2], SAGE[2], SAGE[2], SAGE[3]]
CROWN_DARK = [LEAF[0], LEAF[1], LEAF[1], LEAF[1], LEAF[2]]

# 手心向上、手指朝右（画右边那只时 flip）
HAND_UP = [
    '........xx..',
    '......xxlx..',
    '....xxllx...',
    'xxxxllllx.xx',
    'xlllllllxxlx',
    'xllllllllllx',
    'xxllllllssx.',
    '..xxsssxxx..',
]
APPLE = [
    '...gg.g.',
    '....gg..',
    '.rrrgrr.',
    'rRRrrrrr',
    'rRrrrrrd',
    'rrrrrrrd',
    '.rrrrrd.',
    '..rd.d..',
]
FLAME = [
    '...f....',
    '...ff...',
    '..fff...',
    '..ffff.f',
    '.ffyfff.',
    '.fyyyff.',
    'ffyyyyff',
    '.fyYYyf.',
    '.ffYyf..',
    '..fff...',
]


def bold(pts):
    """两像素宽的线。这里叠一条平移的：陡的横移，缓的下移。"""
    m = line(pts)
    dx, dy = abs(pts[-1][0] - pts[0][0]), abs(pts[-1][1] - pts[0][1])
    return m | (shift(m, 1, 0) if dy > dx * 2 else shift(m, 0, 1))


def band(pts, flip):
    """对称牌用：先在左半画，再整条镜像，右半才和左半严格成对。"""
    m = bold(pts)
    return mirror(m) if flip else m


def diamond(cx, cy, r):
    return (np.abs(XX - cx) + np.abs(YY - cy)) <= r


def sky(c):
    c.vgrad(rect(0, 0, W - 1, 142), [SKY[1], SKY[2], SKY[3]], 0, 140, soft=0.1)


def sun_and_star(c):
    # 太阳在后，星标压在前面
    sun = ellipse(CX, 13, 9.5)
    c.glow(sun, GOLD[4])
    c.glow(sun & ~shift(sun, -2, -2), GOLD[3])
    for a, b in [((55, 0), (55, 2)), ((56, 0), (56, 2)), ((47, 0), (50, 4)), ((41, 6), (46, 9)), ((38, 13), (44, 13)), ((41, 21), (46, 18))]:
        c.glow(bold([a, b]), GOLD[4])
    for a, b in [((47, 0), (50, 4)), ((41, 6), (46, 9)), ((38, 13), (44, 13)), ((41, 21), (46, 18))]:
        c.glow(band([a, b], True), GOLD[4])

    # 翼属于星标：外缘一条弧，下沿三层羽尖；上缘一条细亮线，夜里读得出翼形
    wing = [(12, 5), (15, 11), (21, 15), (29, 18), (36, 21), (40, 25), (41, 30), (45, 33), (37, 35), (47, 38), (41, 43), (42, 39), (35, 43), (37, 38), (31, 39), (26, 37), (31, 34), (25, 34), (20, 29), (27, 29), (21, 26), (14, 19), (24, 23), (17, 18)]
    for flip in (False, True):
        pts = flip_pts(wing) if flip else wing
        m = poly(pts)
        c.paint(shift(m, 0, 1) & ~m, SKY[0])
        c.paint(m, IVORY[4])
        c.paint(m & ~shift(m, 0, -1), IVORY[3])
        for seg in ([(24, 23), (32, 26)], [(27, 29), (35, 31)], [(31, 34), (38, 35)]):
            c.paint(line(flip_pts(seg) if flip else seg), IVORY[2])
        arc = [(12, 5), (15, 11), (21, 15), (29, 18), (36, 21), (40, 25)]
        c.glow(line(flip_pts(arc) if flip else arc), IVORY[4])

    # 八尖星：长竖臂、长横臂、四枚短斜臂
    star = poly([(CX, 12), (58, 31), (76, 35), (58, 39), (CX, 59), (53, 39), (35, 35), (53, 31)])
    diag = blank()
    for tip, a, b in (((46, 24), (51, 34), (54, 30)), ((65, 24), (60, 34), (57, 30)), ((46, 46), (51, 36), (54, 40)), ((65, 46), (60, 36), (57, 40))):
        diag |= poly([tip, a, b])
    c.paint(outer(star | diag), SKY[0])
    c.glow(diag, GOLD[3])
    # 明暗按四象限切开：左上亮、右下暗，星才有棱
    c.glow(star & (XX < CX) & (YY < 35), GOLD[5])
    c.glow(star & (XX > CX) & (YY < 35), GOLD[4])
    c.glow(star & (XX < CX) & (YY >= 35), GOLD[4])
    c.glow(star & (XX > CX) & (YY >= 35), GOLD[3])


def landscape(c):
    # 远山只留一个大三角，山顶积雪；阶梯边
    peak = blocky(poly([(CX, 116), (74, 132), (84, 142), (27, 142), (37, 132)]), 2)
    c.paint(peak, SLATE[3])
    c.paint(peak & (XX > 56), SLATE[2])
    snow = peak & blocky(poly([(CX, 114), (65, 123), (61, 122), (59, 126), (55, 121), (52, 126), (49, 122), (46, 123)]), 2)
    c.paint(snow, IVORY[4])
    c.paint(snow & (XX > 56), IVORY[2])
    # 山脚的远丘
    hill = blocky(poly([(0, 134), (22, 132), (42, 138), (56, 136), (80, 137), (111, 132), (111, 146), (0, 146)]), 2)
    c.paint(hill, SAGE[1])
    c.paint(hill & ~shift(hill, 0, -2), SAGE[2])

    # 草地
    ground = rect(0, 142, W - 1, H - 1)
    c.turf(ground, GRASS, seed=61, base=2)
    # 两条路在远处细、近处宽，在脚下合流成一条
    road = poly([(36, 142), (40, 142), (50, 147), (CX, 151), (61, 147), (71, 142), (75, 142), (65, 149), (60, 154), (60, 162), (66, 176), (84, 191), (27, 191), (45, 176), (51, 162), (51, 154), (46, 149)])
    road = blocky(road, 2)
    c.paint(road, SAND[3])
    c.speckle(road, [SAND[2], SAND[4]], 0.1, 62)
    c.paint(outer(road) & ground, GRASS[1])

    # 方块云
    for m in (rect(0, 90, 8, 94) | rect(0, 86, 4, 90) | rect(0, 94, 12, 96), rect(0, 112, 6, 116) | rect(0, 107, 2, 112) | rect(0, 116, 10, 118), rect(103, 92, 111, 96) | rect(107, 88, 111, 92), rect(102, 116, 111, 120) | rect(106, 112, 111, 116)):
        c.paint(m, IVORY[4])
        c.paint(m & ~shift(m, 0, -2), IVORY[3])


def clumps(c, boxes, ramp, seed):
    """在树冠上压几块方边叶簇，每块上沿提一道亮线。"""
    for i, pts in enumerate(boxes):
        m = blocky(blob(pts), 2)
        c.leaves(m, ramp, seed=seed + i, base=2)
        c.paint(m & ~shift(m, 0, -1), ramp[4])


def left_tree(c):
    # 主干和两根分枝
    trunk = poly([(3, 60), (10, 60), (11, 108), (2, 108)])
    c.bark(trunk, WOOD, seed=63, base=2)
    c.paint(line([(9, 66), (16, 60)], 3), WOOD[1])
    c.paint(line([(3, 66), (0, 62)], 3), WOOD[1])
    c.paint(rect(11, 60, 11, 108) & trunk, WOOD[0])
    c.paint(edge(trunk) & (XX > 9), WOOD[0])

    canopy = blocky(blob([(0, 26), (8, 26), (13, 32), (20, 33), (27, 42), (34, 46), (36, 56), (28, 62), (16, 61), (8, 63), (0, 63)]), 2)
    c.paint(outer(canopy), SKY[0])
    c.leaves(canopy, CROWN_MID, seed=64, base=2)
    clumps(c, [
        [(0, 26), (8, 26), (12, 32), (8, 38), (0, 38)],
        [(11, 33), (20, 34), (25, 41), (17, 45), (11, 40)],
        [(21, 44), (30, 44), (35, 50), (34, 58), (26, 54)],
        [(0, 42), (8, 42), (9, 48), (0, 50)],
    ], CROWN_LIGHT, 80)
    # 三枚果
    pal = {'r': RED[3], 'R': RED[4], 'd': RED[2], 'g': LEAF[0]}
    for x, y in ((9, 38), (1, 49), (16, 49)):
        c.sprite(x, y, APPLE, pal)


def right_tree(c):
    trunk = poly([(103, 60), (109, 60), (110, 116), (102, 116)])
    c.bark(trunk, WOOD, seed=66, base=1)
    for pts, w in (([(104, 82), (94, 72), (86, 66)], 3), ([(103, 76), (98, 68)], 2), ([(109, 86), (111, 84)], 2)):
        c.paint(line(pts, w), WOOD[1])

    canopy = blocky(blob([(111, 27), (102, 30), (92, 38), (84, 42), (78, 52), (75, 58), (80, 67), (92, 69), (104, 67), (111, 64)]), 2)
    c.paint(outer(canopy), SKY[0])
    c.leaves(canopy, CROWN_DARK, seed=67, base=2)
    clumps(c, [
        [(111, 26), (102, 29), (96, 36), (104, 40), (111, 38)],
        [(94, 38), (86, 42), (82, 50), (90, 50), (96, 44)],
        [(82, 52), (76, 56), (77, 62), (84, 62), (86, 56)],
    ], CROWN_MID, 90)
    # 三处火焰
    pal = {'f': FIRE[3], 'y': FIRE[4], 'Y': FIRE[5]}
    for x, y in ((98, 37), (90, 47), (103, 49)):
        c.sprite(x, y, FLAME, pal)


def snake(c):
    # 蛇缠在果树干上，头探向天空，不碰到男方
    body = taper([(3, 96), (9, 90), (11, 84), (6, 78), (2, 73), (4, 68), (9, 66), (13, 67)], 3, 4)
    c.paint(outer(body), SNAKE[0])
    c.paint(body, SNAKE[2])
    c.paint(body & ~shift(body, 1, 1), SNAKE[3])
    head_m = poly([(10, 64), (15, 65), (18, 67), (15, 69), (10, 69)])
    c.paint(outer(head_m), SNAKE[0])
    c.paint(head_m, SNAKE[2])
    c.paint(head_m & ~shift(head_m, 1, 1), SNAKE[3])
    c.pxs([(14, 66), (15, 66)], GOLD[4])


def man(c):
    # 猫尾从背后绕出
    tail(c, [(12, 120), (5, 124), (2, 131), (4, 138), (9, 140)], HAIR_BLACK, 5, 3)

    # 背后的长披风
    back = poly([(11, 98), (9, 118), (4, 156), (8, 160), (22, 152), (24, 104)])
    c.shape(back, CLOTH, base=2, shadow=2, hi=0)
    c.paint(bold([(5, 156), (18, 151)]) & back, GOLD[3])

    # 两条腿
    leg_b = poly([(11, 150), (22, 150), (23, 165), (10, 167)])
    c.shape(leg_b, IVORY, base=3, shadow=2, hi=0)
    leg_f = poly([(22, 150), (35, 150), (35, 166), (27, 166)])
    c.shape(leg_f, CLOTH, base=2, shadow=2, hi=0)
    boot_b = poly([(11, 166), (17, 166), (17, 176), (16, 181), (6, 181), (6, 179), (11, 175)])
    c.shape(boot_b, LEATHER, base=2, shadow=1, hi=1)
    c.paint(rect(11, 167, 17, 169), LEATHER[1])
    boot_f = poly([(28, 166), (34, 166), (35, 173), (42, 176), (42, 180), (28, 180)])
    c.shape(boot_f, LEATHER, base=2, shadow=1, hi=1)
    c.paint(rect(28, 167, 34, 169), LEATHER[1])

    # 白色长外袍
    coat = poly([(22, 108), (36, 108), (38, 125), (40, 152), (30, 155), (22, 152), (10, 158), (14, 130), (18, 118)])
    c.shape(coat, IVORY, base=4, shadow=0, hi=0, line_color=IVORY[1])
    c.paint(poly([(35, 112), (37, 112), (39, 151), (36, 152)]), IVORY[3])
    c.paint(poly([(20, 118), (23, 118), (17, 152), (13, 154)]), IVORY[3])
    c.paint(poly([(23, 146), (26, 146), (24, 152), (16, 155)]), IVORY[2])

    # 前襟的长条：深色带金边，下端成尖，中间一枚四芒星
    apron = poly([(26, 110), (35, 110), (35, 145), (30.5, 150), (26, 145)])
    c.paint(outer(apron), CLOTH[0])
    c.paint(apron, CLOTH[2])
    c.paint(edge(apron), GOLD[3])
    c.sprite(28, 133, ['..g..', '..g..', '.ggg.', 'ggggg', '.ggg.', '..g..', '..g..'], {'g': GOLD[4]})

    # 上身
    shirt = poly([(23, 93), (35, 93), (37, 107), (22, 107)])
    c.paint(shirt, IVORY[4])
    c.paint(poly([(29, 95), (32, 95), (30.5, 103)]), IVORY[2])
    # 腰带
    belt = rect(22, 106, 37, 110)
    c.paint(belt, CLOTH[2])
    c.paint(rect(22, 106, 37, 106) | rect(22, 110, 37, 110), CLOTH[0])
    c.paint(rect(22, 108, 37, 108), GOLD[3])
    buckle = diamond(30, 108, 2)
    c.paint(outer(buckle), GOLD[1])
    c.paint(buckle, GOLD[4])

    # 垂下的右臂和手
    sleeve_b = poly([(11, 106), (20, 104), (21, 120), (12, 123)])
    c.shape(sleeve_b, IVORY, base=4, shadow=1, hi=0, line_color=IVORY[1])
    c.paint(line([(12, 121), (21, 118)]), GOLD[3])
    c.sprite(14, 123, ['.xlllx.', 'xllllsx', 'xllllsx', 'xlxlxsx', 'xlxlxsx', '.x.x.x.'], SKIN_PAL)

    # 肩披：大块深色，金边各走一道
    cape = poly([(22, 86), (14, 90), (10, 98), (8, 112), (14, 112), (24, 104), (31, 93), (30, 89)])
    c.shape(cape, CLOTH, base=2, shadow=2, hi=0)
    c.paint(bold([(9, 111), (24, 104), (31, 94)]) & cape, GOLD[3])
    c.paint(bold([(17, 88), (29, 92)]) & cape, GOLD[3])
    cape_r = poly([(32, 89), (35, 92), (38, 101), (33, 97), (31, 93)])
    c.shape(cape_r, CLOTH, base=2, shadow=1, hi=0)

    # 前伸的手臂：宽袖向右张开，手停在节点之下，留出和女方之间的空
    sleeve = poly([(35, 100), (42, 97), (41, 113), (33, 112), (33, 105)])
    c.shape(sleeve, IVORY, base=4, shadow=1, hi=0, line_color=IVORY[1])
    c.paint(line([(40, 98), (39, 112)]) & sleeve, GOLD[3])
    c.paint(line([(36, 105), (37, 109)]), IVORY[2])
    forearm = poly([(42, 98), (45, 97), (45, 101), (42, 105)])
    c.volume(forearm, SKIN, 3, shadow=1, hi=0)
    c.paint(line([(42, 105), (45, 102)]), SKIN[0])
    c.sprite(42, 94, HAND_UP, SKIN_PAL)

    # 颈和立领
    c.paint(rect(26, 85, 31, 89), SKIN[2])
    stand = rect(24, 87, 32, 91)
    c.paint(stand, CLOTH[2])
    c.paint(rect(24, 87, 32, 87), GOLD[3])
    gem = diamond(30, 93, 2)
    c.paint(outer(gem), GOLD[1])
    c.paint(gem, GOLD[4])

    # 头：黑发，看向中央；几绺碎发向外翘，只在脑后和额前
    for pts, w0 in (([(20, 76), (18, 74)], 3), ([(19, 81), (17, 83)], 3), ([(22, 85), (21, 88)], 3)):
        hair_lock(c, pts, HAIR_BLACK, w0, 1)
    head(c, 33, 67, '3q', 'L', HAIR_BLACK, GREY_IRIS, eyes='open', mouth='none')
    # 头顶翘起的几撮
    c.pxs([(29, 69), (32, 69)], HAIR_BLACK[0])
    # 含笑的嘴
    c.pxs([(31, 84), (34, 84)], SKIN[1])
    c.pxs([(32, 85), (33, 85)], SKIN[0])


def silver_lock(c, m):
    """银发：外缘用浅紫灰，不用最暗一档，蓝天上才轻。"""
    c.paint(outer(m), HAIR_SILVER[1])
    c.paint(m, HAIR_SILVER[3])
    c.paint(m & ~shift(m, 1, 0), HAIR_SILVER[4])
    c.paint(m & ~shift(m, -1, 0), HAIR_SILVER[2])


def woman(c):
    # 白色猫尾在裙后勾起
    tail(c, [(102, 140), (108, 137), (110, 130), (107, 124)], IVORY, 5, 3, base=4)

    # 长发后片：银发垂到腰下，末端分绺
    silver_lock(c, taper([(103, 94), (108, 106), (109, 116), (105, 125)], 5, 1))
    silver_lock(c, taper([(80, 88), (77, 98), (75, 108), (76, 118), (79, 127)], 6, 1))
    mass = blob([(88, 76), (97, 78), (100, 86), (103, 96), (106, 108), (106, 118), (102, 126), (99, 112), (96, 100), (84, 96), (80, 92), (82, 84)])
    silver_lock(c, mass)
    c.paint(line([(99, 92), (103, 108), (102, 120)]) & mass, HAIR_SILVER[2])

    # 靴子：细跟，靴筒收窄
    for pts in ([(83, 169), (89, 169), (89, 177), (90, 180), (80, 180), (80, 178), (83, 176)], [(95, 169), (101, 169), (101, 176), (105, 179), (105, 180), (95, 180)]):
        boot = poly(pts)
        c.shape(boot, LEATHER, base=2, shadow=1, hi=1)

    # 裙：象牙内裙打底，下摆成弧；外面两片绿色开襟
    skirt = poly([(87, 110), (98, 110), (106, 150), (109, 166), (100, 170), (88, 172), (76, 171), (68, 167), (73, 150)])
    c.paint(skirt, IVORY[4])
    c.paint(edge(skirt), IVORY[1])
    c.paint(line([(68, 164), (76, 168), (88, 169), (100, 167), (109, 163)]) & skirt, IVORY[2])
    left_panel = poly([(86, 110), (90, 110), (88, 140), (88, 166), (78, 165), (70, 161), (76, 138)])
    right_panel = poly([(96, 110), (100, 110), (104, 138), (108, 161), (101, 164), (99, 138)])
    c.shape(left_panel, SAGE, base=2, shadow=2, hi=0)
    c.shape(right_panel, SAGE, base=2, shadow=2, hi=0)
    c.fold([(82, 120), (78, 150)], SAGE[1], shrink(left_panel))
    c.paint(bold([(71, 161), (78, 164), (87, 165)]) & left_panel, GOLD[3])
    c.paint(bold([(102, 163), (107, 161)]) & right_panel, GOLD[3])
    c.sprite(80, 148, ['..g..', '..g..', '.ggg.', 'ggggg', '.ggg.', '..g..', '..g..'], {'g': GOLD[4]})

    # 垂下的袖子和手
    sleeve_b = poly([(96, 98), (102, 98), (105, 120), (101, 126), (96, 118)])
    c.shape(sleeve_b, IVORY, base=4, shadow=1, hi=0, line_color=IVORY[1])
    c.sprite(99, 125, ['.xlllx.', 'xslllsx', 'xllllsx', 'xsxlxlx', '.x.x.x.'], SKIN_PAL)

    # 上身
    bodice = poly([(84, 97), (97, 97), (97, 108), (84, 108)])
    c.shape(bodice, SAGE, base=2, shadow=1, hi=0)
    c.paint(poly([(87, 98), (94, 98), (90.5, 105)]), IVORY[4])
    belt = rect(84, 107, 98, 110)
    c.paint(belt, SAGE[0])
    c.paint(rect(84, 109, 98, 109), GOLD[3])
    # 系带：从腰前垂下的象牙带子和金结
    sash = poly([(89, 110), (93, 110), (95, 130), (91, 134), (88, 130)])
    c.paint(outer(sash), IVORY[1])
    c.paint(sash, IVORY[4])
    c.paint(poly([(88, 111), (85, 118), (87, 119), (90, 112)]), GOLD[3])
    c.paint(poly([(94, 111), (97, 118), (95, 119), (92, 112)]), GOLD[3])
    knot = rect(89, 108, 93, 111)
    c.paint(knot, GOLD[4])
    c.paint(edge(knot), GOLD[1])
    c.paint(poly([(90, 130), (93, 130), (91, 134)]), SAGE[2])

    # 前伸的手臂：泡袖收在腕口
    sleeve = poly([(84, 100), (78, 99), (72, 102), (69, 108), (74, 112), (84, 106)])
    c.shape(sleeve, IVORY, base=4, shadow=1, hi=0, line_color=IVORY[1])
    c.fold([(75, 103), (74, 108)], IVORY[2], sleeve)
    cuff = poly([(70, 99), (72, 97), (76, 102), (73, 105)])
    c.paint(cuff, IVORY[3])
    c.paint(edge(cuff), IVORY[1])
    forearm = poly([(66, 97), (70, 97), (72, 100), (69, 103)])
    c.volume(forearm, SKIN, 3, shadow=1, hi=0)
    c.paint(line([(66, 101), (69, 103)]), SKIN[0])
    c.sprite(58, 94, HAND_UP, SKIN_PAL, flip=True)

    # 绿色小披肩，金边一道
    cape_l = poly([(90, 96), (83, 93), (76, 101), (90, 98)])
    cape_r = poly([(91, 96), (99, 94), (102, 104), (91, 98)])
    c.shape(cape_l, SAGE, base=1, shadow=1, hi=0)
    c.shape(cape_r, SAGE, base=1, shadow=1, hi=0)
    c.paint(bold([(76, 101), (90, 98)]), GOLD[3])
    c.paint(bold([(91, 98), (102, 104)]), GOLD[3])

    # 颈、肩和立领
    c.paint(rect(86, 86, 90, 92), SKIN[2])
    c.paint(poly([(83, 93), (90, 92), (99, 94), (91, 96), (90, 96)]), SKIN[3])
    collar = rect(85, 90, 91, 93)
    c.paint(collar, SAGE[2])
    c.paint(rect(85, 90, 91, 90), GOLD[3])
    gem = diamond(90, 97, 2)
    c.paint(outer(gem), GOLD[1])
    c.paint(gem, GOLD[4])

    # 头后的墨绿发带：一只结，两条飘带
    for pts in ([(97, 84), (101, 88), (104, 94)], [(97, 85), (99, 92), (100, 99)]):
        c.paint(taper(pts, 3, 2), SAGE[0])
    # 头：银发，看向中央
    head(c, 84, 70, '3q', 'L', HAIR_SILVER, GOLD_IRIS, eyes='open', mouth='none', flip=True, lash='#4a4560')
    c.pxs([(83, 87), (86, 87)], SKIN[1])
    c.pxs([(84, 88), (85, 88)], SKIN[0])
    # 脸侧垂下的一绺前发
    silver_lock(c, taper([(80, 80), (78, 87), (79, 94)], 4, 1))


def center_node(c):
    # 两段金线对等，端头各一枚小菱环
    cy = 85
    for flip in (False, True):
        f = (lambda pts: flip_pts(pts)) if flip else (lambda pts: pts)
        c.glow(line(f([(45, cy), (49, cy)])), GOLD[4])
        ring_x = f([(42, cy)])[0][0]
        c.glow(diamond(ring_x, cy, 3), GOLD[4])
        c.paint(diamond(ring_x, cy, 1), SKY[3])
    # 节点：竖长菱形，亮边青芯
    m = np.abs(XX - CX) / 6 + np.abs(YY - cy) / 8 <= 1
    inner = np.abs(XX - CX) / 4.5 + np.abs(YY - cy) / 6 <= 1
    c.paint(outer(m), TEAL[0])
    c.glow(m, TEAL[4])
    c.glow(inner, TEAL[3])
    c.glow(inner & (XX > CX) & (YY > cy), TEAL[2])
    c.glow_px([(54, 83), (55, 83), (54, 84)], TEAL[4])
    # 上、斜和下方的短光芒
    c.glow(rect(55, 72, 56, 75), GOLD[4])
    c.glow(rect(55, 95, 56, 98), GOLD[4])
    for flip in (False, True):
        pts = [(50, 75), (52, 78)]
        c.glow(line(flip_pts(pts) if flip else pts), GOLD[4])


def tufts(c):
    # 前景草丛
    for x, y, w in ((4, 188, 7), (14, 190, 3), (100, 188, 7), (92, 190, 3), (3, 178, 3), (108, 178, 3)):
        for k in range(-w // 2, w // 2 + 1):
            c.paint(line([(x + k * 2, 191), (x + k * 3, y + abs(k))]), GRASS[0])
    # 脚下投影
    for x0, x1, y in ((6, 42, 181), (80, 105, 181)):
        c.paint(rect(x0, y, x1, y + 1), GRASS[1])


def draw():
    c = Card('06_lovers')
    sky(c)
    landscape(c)
    sun_and_star(c)
    right_tree(c)
    left_tree(c)
    snake(c)
    tufts(c)
    man(c)
    woman(c)
    center_node(c)
    return c
