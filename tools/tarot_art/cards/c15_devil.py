"""15 恶魔：高台上的猫耳者戴金属角冠，蝠翼张开，头顶倒五芒星，一手上举、一手持向下的火炬。
台下黑发男与银发女服色呼应恋人牌；两条链从台前环松松垂到两人颈上的宽环，双手自由。"""
from figures import *

WING = ['#1f1330', '#2e1f44', '#3d2d57', '#4e3d6b', '#625283']
PLINTH = ['#211731', '#271c38', '#2d2240', '#332847', '#4d4064']
WINE = ['#280c1c', '#471327', '#6b1e34', '#8a2d42', '#a84d5a']
HAIR_D = ['#14111c', '#231e2e', '#352f42', '#4b4559', '#6f6a80']
FLOOR = ['#2a2030', '#42364c', '#54475e', '#64566e', '#786a80']
ROBE_D = ['#0f0a1c', '#1a1330', '#261d42', '#352b56', '#4a3f6e']
CREAM = ['#6e5c44', '#a18d6e', '#c9b591', '#e6d5b2', '#f6ebd0']
MOSS = ['#2c3a28', '#46583a', '#5f744c', '#7a8f60', '#99ad7c']
BOOT = ['#1c1418', '#2e2228', '#443439', '#5b4a4e']


# 大链环：空心的长圆，h 迎光、g 本色、k 背光。四种朝向逐格摆，中间透出底下的砌面
LINK_STEEP = ['....hhg', '...h..g', '..h..g.', '.h..g..', 'h..g...', 'hgk....']
LINK_SHALLOW = ['.....hhg', '..hhh..g', 'hh...gg.', 'h..gg...', 'hgg.....']
LINK_FLAT = ['.hhhhhh.', 'h......g', 'g......k', '.ggggkk.']
LINK_RISE = ['hhg....', 'h..g...', '.h..g..', '..h..g.', '...h..g', '....gkk']
LINK_PAL = {'h': GOLD[4], 'g': GOLD[3], 'k': GOLD[2]}


def chain_side(c, flip):
    """一条链：从台前环垂下成弧，再抬起接到人物颈上的宽环。flip 为真时镜像到右边。"""
    for x, y, rows in [(45, 121, LINK_STEEP), (38, 126, LINK_SHALLOW), (31, 130, LINK_FLAT), (26, 124, LINK_RISE)]:
        w_ = len(rows[0])
        c.sprite(111 - x - w_ + 1 if flip else x, y, rows, LINK_PAL, flip=flip)


def with_rim(c, fn, color, clip=None):
    """画完一颗头后，沿它外缘描一圈略亮的暗紫，让黑发从暗背景上分离出来。"""
    before = c.rgb.copy()
    out = fn()
    m = outer((c.rgb != before).any(axis=2))
    if clip is not None:
        m &= clip
    c.paint(m, color)
    return out


def neck_ring(c, cx, cy, rx, ry, front):
    """颈上的宽环：画成两半，后半在脖子后，前半压在胸前。"""
    r_ = ring(cx, cy, rx, ry, 2)
    half = r_ & (YY >= cy) if front else r_ & (YY < cy)
    c.paint(half, GOLD[3])
    c.paint(half & ~shift(half, 0, 1), GOLD[1])
    c.paint(half & ~shift(half, 0, -1), GOLD[4])


def draw():
    c = Card('15_devil')
    everything = rect(0, 0, W - 1, H - 1)

    # 暗背景：大面深紫，上下只差一档
    c.vgrad(everything, [PLUM[1], PLUM[1], PLUM[0]], 0, 190)

    # 蝠翼：右翼手写，左翼镜像
    wing_r = [(73, 9), (86, 12), (100, 19), (108, 28), (111, 36), (111, 46), (104, 58), (102, 50), (96, 44), (91, 42), (88, 48), (85, 58), (82, 50), (78, 48), (73, 54), (68, 56), (62, 44), (68, 34), (73, 22)]
    pane_a = [(74, 16), (72, 28), (66, 42), (70, 52), (78, 44), (84, 50), (82, 32)]
    pane_b = [(77, 14), (85, 34), (87, 44), (92, 38), (100, 44), (104, 52), (104, 38), (92, 22)]
    for flip_it in (False, True):
        pts = (lambda q: flip_pts(q)) if flip_it else (lambda q: q)
        w_ = poly(pts(wing_r))
        c.paint(w_, WING[3])
        c.paint(edge(w_), WING[1])
        c.paint(poly(pts(pane_a)) & shrink(w_), WING[1])
        c.paint(poly(pts(pane_b)) & shrink(w_), WING[1])

    # 倒五芒星：一尖朝下，发光。顶点取整数，左半手写再镜像，严格对称
    star = line([(55, 17), (49, 0)]) | line([(49, 0), (64, 10)])
    star |= mirror(star, 55) | line([(46, 10), (64, 10)])
    c.glow(star, GOLD[4])

    # 猫尾：暗处的长尾
    c.paint(taper([(21, 83), (14, 78), (11, 72), (12, 66), (18, 62)], 3, 2), WING[2])

    # 地面与高台：低对比的方块砌面
    floor = rect(0, 181, W - 1, H - 1)
    c.stone_bricks(floor, FLOOR, bw=18, bh=6, oy=181, base=2, seed=51)
    c.paint(rect(0, 181, W - 1, 181), FLOOR[0])
    plinth = rect(30, 108, 81, 181)
    c.stone_bricks(plinth, PLINTH, bw=13, bh=9, ox=4, oy=110, base=2, seed=52)
    c.paint(edge(plinth), PLINTH[0])
    top_slab = rect(29, 105, 82, 109)
    c.paint(top_slab, PLINTH[4])
    c.paint(rect(29, 109, 82, 109), PLINTH[0])
    c.paint(rect(29, 105, 82, 105), PLINTH[0])

    # 台面两条竖金线
    c.paint(rect(50, 124, 50, 180), GOLD[2])
    c.paint(rect(61, 124, 61, 180), GOLD[2])

    # 大红披风：铺在身后，从肩头分垂两侧
    cape = poly([(44, 40), (34, 45), (31, 48), (30, 58), (24, 64), (21, 76), (17, 88), (15, 95), (21, 98), (25, 109), (30, 101), (40, 92), (56, 70), (70, 92), (80, 103), (85, 110), (89, 102), (85, 88), (85, 70), (84, 60), (77, 56), (73, 49), (65, 42), (56, 40)])
    c.shape(cape, WINE, base=2, shadow=2, hi=0)
    for pts in [[(34, 52), (28, 66), (24, 86), (30, 74)], [(40, 66), (30, 80), (22, 94), (32, 88)], [(74, 64), (78, 80), (82, 100), (84, 80)], [(70, 74), (74, 92), (80, 100), (78, 84)]]:
        c.paint(poly(pts) & shrink(cape), WINE[1])

    # 上举手的宽袖：竖直，金边
    sleeve_l = poly([(25, 44), (30, 43), (33, 50), (36, 60), (36, 70), (26, 80), (22, 76), (24, 60)])
    c.shape(sleeve_l, ROBE_D, base=2, shadow=2, hi=0)
    c.trim([(30, 43), (30, 49), (27, 61), (26, 79)], smooth=False)
    forearm = poly([(27, 37), (31, 37), (30, 46), (28, 50), (26, 46)])
    c.volume(forearm, SKIN, 3, shadow=1, hi=0)
    # 上举的手：食中二指向上分明，拇指外张，无名指小指弯在掌心
    c.sprite(25, 26, [
        '...ll.ll...',
        '...ll.ll...',
        '...lldll...',
        '...lldll...',
        '...lldlls..',
        '.d.lldlls.l',
        'lldlldllsll',
        'llllllllsl.',
        'lddddlllll.',
        'lllllllll..',
        '.llllllss..',
        '..lllllss..',
        '..lllsss...',
    ], {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1]})

    # 持火炬的宽袖
    sleeve_r = poly([(74, 60), (83, 59), (90, 62), (86, 70), (85, 83), (80, 80), (75, 70)])
    c.shape(sleeve_r, ROBE_D, base=2, shadow=2, hi=0)
    c.trim([(89, 62), (85, 70), (85, 82)], smooth=False)

    # 向下的火炬：把手、金箍、火焰朝下；火焰发光
    c.paint(line([(93, 71), (96, 82)], 3), WOOD[1])
    c.paint(line([(93, 71), (96, 82)]), WOOD[3])
    c.paint(poly([(93, 82), (98, 81), (99, 84), (95, 85)]), GOLD[3])
    c.paint(line([(94, 85), (99, 84)]), GOLD[1])
    flame = poly([(96, 85), (98, 85), (100, 88), (101, 92), (100, 94), (98, 97), (96, 95), (93, 94), (94, 90), (95, 88)])
    c.aura(flame, RED[1], 1, 0.5)
    c.glow(flame, FIRE[3])
    c.glow(edge(flame), FIRE[2])
    c.glow(poly([(96, 88), (98, 90), (98, 93), (97, 95), (95, 92)]), FIRE[4])

    # 持炬的手：手腕出袖，四指向下扣住把手
    c.sprite(87, 63, [
        'll........',
        'llll......',
        '.lllll....',
        '..lllllll.',
        '..llllllls',
        '...lllllss',
        '....lddlsd',
        '....ld.lsd',
        '....ld.ls.',
        '.......ls.',
    ], {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1]})

    # 胸腹与膝头：深色长袍
    torso = poly([(49, 42), (62, 42), (64, 48), (64, 62), (47, 62), (47, 48)])
    c.shape(torso, ROBE_D, base=2, shadow=2, hi=0)
    # 右腿小腿和靴
    leg_r = poly([(60, 62), (76, 64), (72, 78), (68, 92), (62, 92), (58, 82)])
    c.shape(leg_r, ROBE_D, base=1, shadow=1, hi=1)
    leg_l = poly([(52, 80), (62, 78), (62, 97), (53, 97)])
    c.shape(leg_l, ROBE_D, base=1, shadow=1, hi=1)
    boot_r = poly([(64, 90), (70, 89), (72, 96), (80, 99), (80, 103), (66, 103)])
    c.shape(boot_r, BOOT, base=2, shadow=1, hi=1)
    boot_l = poly([(53, 96), (61, 96), (62, 104), (63, 108), (51, 108), (52, 104)])
    c.shape(boot_l, BOOT, base=2, shadow=1, hi=1)
    c.paint(rect(52, 103, 62, 103), BOOT[0])

    # 高膝长袍的主体：从膝头垂到台面
    robe = poly([(46, 58), (54, 57), (60, 60), (60, 70), (63, 78), (57, 82), (47, 94), (48, 99), (37, 104), (30, 109), (32, 98), (36, 85), (39, 72)])
    c.shape(robe, ROBE_D, base=3, shadow=2, hi=0)
    c.fold([(46, 60), (42, 80), (38, 100)], ROBE_D[1], robe)
    c.fold([(52, 60), (51, 76), (46, 92)], ROBE_D[1], robe)
    c.trim([(30, 108), (37, 103), (47, 99), (46, 94), (57, 81), (63, 78), (67, 81)], smooth=False)

    # 腰间的红布搭过右腿
    drape = poly([(60, 60), (67, 62), (76, 66), (82, 74), (78, 78), (70, 72), (62, 68)])
    c.shape(drape, WINE, base=2, shadow=1, hi=0)

    # 披风上部两片：搭在肩头、盖住胸侧，金线收在领扣
    lobe_l = poly([(50, 42), (43, 39), (35, 44), (32, 47), (37, 55), (36, 58), (39, 63), (50, 46)])
    lobe_r = poly([(61, 42), (64, 39), (66, 41), (73, 49), (77, 56), (83, 60), (76, 68), (70, 72), (66, 52)])
    c.shape(lobe_l, WINE, base=3, shadow=2, hi=0)
    c.shape(lobe_r, WINE, base=3, shadow=2, hi=0)
    c.paint(poly([(36, 47), (44, 46), (40, 53)]), WINE[1])
    c.paint(poly([(66, 46), (72, 54), (68, 56)]), WINE[1])
    c.trim([(50, 45), (40, 62)], smooth=False)
    c.trim([(61, 45), (66, 52)], smooth=False)

    # 胸前金线与两枚菱纹：菱纹只是织物，不发光
    c.paint(rect(50, 44, 50, 58), GOLD[3])
    c.paint(rect(60, 44, 60, 60), GOLD[3])
    c.paint(rect(55, 42, 55, 60), GOLD[3])
    for gy, r_ in [(47, 3), (53, 2)]:
        m = (np.abs(XX - 55) + np.abs(YY - gy)) <= r_
        c.paint(m, GOLD[3])
        c.paint((np.abs(XX - 55) + np.abs(YY - gy)) <= r_ - 1, TEAL[1])

    # 立领与颈
    c.paint(rect(53, 36, 57, 40), SKIN[2])
    coll = rect(50, 38, 60, 42)
    c.paint(coll, ROBE_D[1])
    c.paint(rect(50, 38, 60, 38) & ~rect(54, 38, 56, 38), GOLD[3])
    c.paint(rect(55, 38, 55, 42), GOLD[3])
    c.paint(rect(49, 43, 62, 43), GOLD[3])
    c.pxs([(49, 43), (55, 43), (62, 43)], GOLD[4])

    # 头：黑发红眼，人脸不兽化；两侧碎发向外翘
    for pts in [[(47, 26), (44, 29), (43, 33)], [(47, 30), (45, 33), (46, 36)], [(63, 26), (66, 29), (67, 33)], [(63, 30), (65, 33), (64, 36)]]:
        hair_lock(c, pts, HAIR_D, 3, 1, base=2)
    with_rim(c, lambda: head(c, 55, 16, 'front', 'L', HAIR_D, RED_IRIS, mouth='none'), WING[2], rect(40, 10, 70, 30))
    # 微微上扬的嘴角
    c.pxs([(53, 34), (57, 34)], SKIN[1])
    c.pxs([(54, 35), (55, 35), (56, 35)], SKIN[0])

    # 金属角形冠：额上一道细金箍，中央小菱，两支细角从猫耳内侧上升、向外勾
    horn = line([(51, 22), (51, 18), (50, 15)]) | line([(50, 15), (47, 14), (45, 14)])
    horn |= mirror(horn, 55)
    c.paint(horn, GOLD[3])
    c.paint(horn & ~shift(horn, 1, 0), GOLD[4])
    band = rect(50, 22, 60, 23)
    c.paint(band, GOLD[3])
    c.paint(rect(50, 23, 60, 23), GOLD[1])
    gem_m = (np.abs(XX - 55) + np.abs(YY - 21)) <= 2
    c.paint(gem_m, GOLD[4])
    c.px(55, 21, TEAL[2])

    # 台前环：金托架、一只大环；环下三枚菱形节点（发光）
    c.paint(rect(54, 110, 57, 113), GOLD[2])
    c.paint(rect(54, 110, 54, 113), GOLD[3])
    ring_big = ring(55.5, 117, 5, 5, 2)
    c.paint(ring_big, GOLD[3])
    c.paint(ring_big & ~shift(ring_big, -1, -1), GOLD[2])
    c.paint(ring_big & ~shift(ring_big, 1, 1), GOLD[4])

    # 菱形节点：上小下大
    c.node(55, 127, 1, ramp=[TEAL[0], TEAL[1], TEAL[2], TEAL[2], TEAL[3]])
    big = (np.abs(XX - 55.5) / 4.5 + np.abs(YY - 134) / 6.5) <= 1
    c.glow(big, TEAL[3])
    c.glow(edge(big), TEAL[2])
    c.glow((np.abs(XX - 55.5) / 2 + np.abs(YY - 134) / 3) <= 1, TEAL[4])
    c.node(55, 143, 2)

    # 黑发男 ------------------------------------------------------------
    c.paint(taper([(8, 147), (4, 152), (3, 158), (5, 163)], 3, 2), WING[2])
    neck_ring(c, 20, 120, 9, 4, False)
    # 宽裤与靴
    for r_ in [rect(9, 174, 17, 187), rect(21, 174, 28, 187)]:
        c.paint(r_, ROBE_D[1])
    c.paint(rect(8, 186, 15, 191), BOOT[1])
    c.paint(rect(21, 186, 29, 191), BOOT[1])
    c.paint(rect(9, 186, 15, 186), BOOT[3])
    c.paint(rect(21, 186, 29, 186), BOOT[3])
    # 下摆
    skirt = poly([(13, 139), (27, 139), (29, 150), (32, 174), (20, 170), (6, 175), (9, 155)])
    c.shape(skirt, CREAM, base=3, shadow=1, hi=0)
    panel_l = poly([(14, 139), (18, 139), (15, 170), (7, 172), (10, 155)])
    panel_r = poly([(24, 139), (27, 139), (29, 150), (31, 172), (27, 170)])
    panel_m = poly([(18, 139), (24, 139), (26, 165), (17, 165)])
    for p_ in (panel_l, panel_r, panel_m):
        c.paint(p_, ROBE_D[2])
    c.trim([(18, 139), (15, 170), (7, 172)], smooth=False)
    c.trim([(24, 139), (27, 170), (31, 172)], smooth=False)
    c.trim([(17, 165), (26, 165)], smooth=False)
    m = (np.abs(XX - 22) + np.abs(YY - 160)) <= 2
    c.paint(m, GOLD[3])
    c.px(22, 160, TEAL[2])
    # 上身：米色内衣、深色背心、米色长袖
    sleeve_rm = poly([(26, 122), (30, 122), (33, 136), (34, 148), (29, 149), (27, 136)])
    c.shape(sleeve_rm, CREAM, base=3, shadow=1, hi=0)
    c.paint(poly([(29, 145), (34, 144), (34, 148), (29, 149)]), ROBE_D[2])
    c.trim([(29, 145), (34, 144)], smooth=False)
    hand(c, 29, 149, 'mitt')
    c.paint(rect(31, 153, 32, 155), SKIN[3])
    vest = poly([(13, 120), (27, 120), (27, 135), (14, 135)])
    c.shape(vest, ROBE_D, base=2, shadow=1, hi=0)
    c.paint(rect(18, 122, 18, 135), GOLD[3])
    c.paint(rect(25, 122, 25, 135), GOLD[3])
    for gy, r_ in [(127, 2), (132, 1)]:
        m = (np.abs(XX - 21) + np.abs(YY - gy)) <= r_
        c.paint(m, GOLD[3])
        c.px(21, gy, TEAL[2])
    belt = rect(13, 135, 27, 139)
    c.paint(belt, ROBE_D[1])
    c.paint(rect(13, 135, 27, 135), GOLD[3])
    # 领口
    c.paint(rect(19, 112, 23, 116), SKIN[2])
    collar_m = poly([(17, 115), (19, 114), (21, 117), (23, 114), (24, 115), (24, 121), (17, 121)])
    c.paint(collar_m, CREAM[3])
    c.paint(line([(21, 117), (21, 121)]), CREAM[1])
    neck_ring(c, 20, 120, 9, 4, True)
    # 抬起的手臂：肘向外，一片宽袖，金色袖口；手自由地搭在环上
    arm = poly([(11, 120), (13, 122), (13, 127), (12, 132), (9, 136), (7, 137), (4, 132), (6, 126)])
    c.shape(arm, CREAM, base=3, shadow=1, hi=0)
    c.fold([(7, 129), (8, 133), (9, 135)], CREAM[1], arm)
    c.paint(poly([(11, 126), (15, 122), (17, 124), (13, 129)]), SKIN[2])
    c.paint(line([(9, 129), (12, 125)]), ROBE_D[2])
    c.paint(line([(10, 130), (13, 127)]), GOLD[3])
    c.sprite(13, 118, ['.lllll.', 'lllllll', 'lllllls', 'ldldlds', 'ldldld.'], {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1]})
    # 头
    hair_lock(c, [(15, 105), (11, 109), (13, 114)], HAIR_D, 3, 1, base=3)
    with_rim(c, lambda: head(c, 22, 101, '3q', 'M', HAIR_D, BROWN_IRIS, eyes='up', mouth='none'), WING[2], rect(6, 96, 30, 111))
    c.pxs([(24, 110), (25, 110)], SKIN[0])

    # 银发女 ------------------------------------------------------------
    c.paint(taper([(103, 150), (108, 155), (109, 162), (105, 168)], 3, 2), WING[2])
    # 长发后片：垂到胸下
    back = poly([(95, 107), (100, 108), (103, 112), (105, 120), (108, 126), (109, 133), (108, 138), (110, 144), (106, 148), (103, 143), (100, 146), (99, 136), (97, 126), (95, 115)])
    c.paint(back, HAIR_SILVER[2])
    c.paint(back & ~shift(back, 2, 0), HAIR_SILVER[3])
    c.paint(edge(back), HAIR_SILVER[0])
    c.fold([(100, 113), (102, 124), (104, 134), (103, 142)], HAIR_SILVER[1], back)
    neck_ring(c, 92, 122, 9, 4, False)
    # 脚
    c.paint(rect(87, 186, 92, 191), BOOT[1])
    c.paint(rect(95, 186, 100, 191), BOOT[1])
    # 长裙
    dress = poly([(88, 136), (98, 136), (103, 155), (108, 184), (95, 188), (76, 186), (83, 155)])
    c.shape(dress, MOSS, base=2, shadow=2, hi=0)
    c.fold([(86, 142), (82, 165), (80, 184)], MOSS[1], dress)
    c.fold([(101, 150), (104, 170), (105, 184)], MOSS[1], dress)
    mid = poly([(92, 139), (96, 139), (99, 187), (90, 187)])
    c.paint(mid, CREAM[3])
    c.paint(line([(92, 139), (90, 187)]), GOLD[3])
    c.paint(line([(96, 139), (99, 187)]), GOLD[3])
    hem = poly([(76, 184), (90, 186), (108, 182), (108, 184), (95, 188), (76, 186)])
    c.paint(hem, GOLD[3])
    # 上身与袖
    bodice = poly([(87, 125), (98, 125), (98, 136), (88, 136)])
    c.shape(bodice, MOSS, base=2, shadow=1, hi=0)
    c.paint(rect(88, 135, 98, 138), ROBE_D[1])
    c.paint(rect(88, 135, 98, 135), GOLD[3])
    c.paint(rect(88, 138, 98, 138), GOLD[2])
    m = (np.abs(XX - 94) + np.abs(YY - 130)) <= 2
    c.paint(m, GOLD[3])
    c.px(94, 130, TEAL[2])
    sleeve_a = poly([(84, 128), (88, 126), (88, 136), (86, 148), (79, 147), (81, 136)])
    c.shape(sleeve_a, MOSS, base=2, shadow=1, hi=0)
    c.paint(poly([(79, 145), (86, 146), (86, 149), (79, 148)]), CREAM[3])
    c.paint(line([(79, 149), (86, 149)]), GOLD[3])
    hand(c, 80, 150, 'mitt', flip=True)
    c.paint(rect(81, 154, 82, 156), SKIN[3])
    # 米色肩披
    shawl = poly([(84, 125), (90, 122), (100, 122), (102, 130), (97, 136), (88, 134), (82, 133)])
    c.shape(shawl, CREAM, base=3, shadow=1, hi=0)
    c.paint(line([(82, 133), (88, 134)]), GOLD[3])
    # 领口
    c.paint(rect(89, 116, 93, 121), SKIN[2])
    c.paint(rect(88, 119, 95, 122), MOSS[2])
    c.paint(rect(88, 119, 95, 119), GOLD[3])
    neck_ring(c, 92, 122, 9, 4, True)
    # 抬起的手搭在环上
    fore_b = poly([(100, 130), (104, 131), (104, 137), (99, 140), (97, 134)])
    c.shape(fore_b, MOSS, base=2, shadow=1, hi=0)
    c.sprite(95, 120, ['.lllll.', 'lllllll', 'slllllll', 'sdldldl', '.dldldl'], {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1]})
    # 头
    head(c, 90, 106, '3q', 'M', HAIR_SILVER, VIOLET_IRIS, eyes='up', mouth='none', flip=True)
    c.pxs([(87, 115), (88, 115)], SKIN[0])
    hair_lock(c, [(96, 111), (97, 116), (96, 120)], HAIR_SILVER, 3, 1, base=3)

    # 两条松弛的链：向下垂成弧，压在袖子上，接到两人的宽环
    chain_side(c, False)
    chain_side(c, True)

    return c
