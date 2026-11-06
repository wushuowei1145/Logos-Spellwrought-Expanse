"""16 高塔：方块石塔占据主轴，一道折线闪电击中塔上部，金冠形塔顶被掀开、倾斜悬空，与塔身分离。
裂缝截断蓝青节点线路，两个窗口冒火，两位猫耳人物向相反两侧坠落；岩基与暗天把冲击集中在塔上。"""
from figures import *

# 墙面的灰缝不用近黑，砌块读得出来又不碎；亮面朝左上，暗面朝右
WALL = ['#5c5760', '#7b767d', '#8d888d', '#9e999c', '#b4afb0']
WALL_D = ['#454149', '#5a5560', '#6a656e', '#78737b', '#86818a']
EDGE = '#2e2a34'
CRAG = ['#201e2a', '#2f2d3a', '#403e4b', '#55535f', '#6c6a76']
CLOTH = ['#1c1e48', '#2b2f66', '#3d4382', '#515a9c', '#6a72ad']
FELT = ['#6e5e42', '#a08e6c', '#c6b58f', '#e4d6b2', '#f4ebce']
MOSS = ['#2d3a24', '#475a37', '#62784b', '#7f9562', '#a0b47f']
SASH = ['#34101f', '#551a30', '#7a2a44', '#9a4058']
BOOT = ['#2e1a14', '#4a2c1f', '#68412c', '#87593c']

# 张开的手：五指散开。默认手腕在左上，手指朝右下；flip 朝左下
HAND_SPREAD = [
    '..ll...l',
    '.lllll.l',
    '.llllll.',
    'l.lll.ll',
    'l.l.l...',
    '..l.l...',
]
# 上举的手：手腕在右下，手指朝左上散开
HAND_UP = [
    '.l..l..',
    'l..l..l',
    'l.l..l.',
    '.lll.l.',
    '.lllll.',
    '..llll.',
    '...ll..',
]
# 向右甩开的手
HAND_RIGHT = [
    '.....ll.',
    'll..ll..',
    'lllllll.',
    'lllll.ll',
    'lll.ll..',
    '.l..l...',
]


def ring_diamond(x, y, r):
    """空心菱形：线路端点那种节点框。"""
    d = np.abs(XX - x) + np.abs(YY - y)
    return (d <= r) & (d > r - 2)


def spark(c, x, y, a, b):
    """细长的四尖火星：竖长横短，比纯十字更像一粒飞溅的光。"""
    m = np.abs(XX - x) / (a + 0.3) + np.abs(YY - y) / (b + 0.3) <= 1
    c.glow(m, GOLD[3])
    c.glow(np.abs(XX - x) + np.abs(YY - y) <= 1, GOLD[4])


def with_rim(c, fn, color, clip=None):
    """画完一颗头后，沿外缘描一圈夜空亮一档的蓝，让暗发从夜空上分离。"""
    before = c.rgb.copy()
    out = fn()
    m = outer((c.rgb != before).any(axis=2))
    if clip is not None:
        m &= clip
    c.paint(m, color)
    return out


def draw():
    c = Card('16_tower')
    everything = rect(0, 0, W - 1, H - 1)

    # 深色夜空：一整块平涂，把冲击集中在塔上
    c.paint(everything, NAVY[1])

    # 方块乌云：右上和左下各一团，不抢塔的主轴
    cloud_a = rect(88, 0, 111, 6) | rect(82, 6, 111, 12) | rect(78, 10, 111, 14) | rect(92, 14, 111, 18)
    cloud_b = rect(0, 152, 8, 156) | rect(0, 156, 18, 162) | rect(0, 162, 32, 168) | rect(0, 168, 14, 174)
    for m in (cloud_a, cloud_b):
        c.paint(m, NAVY[2])
        c.paint(m & ~shift(m, 0, 2), NAVY[3])

    # 火星：少量四尖光粒
    for x, y, a, b in [(40, 11, 2, 5), (39, 33, 1, 3), (18, 50, 2, 4), (19, 66, 2, 4), (100, 52, 2, 5), (92, 64, 2, 5)]:
        spark(c, x, y, a, b)

    # ------------------------------ 塔身：分段的方块体，亮面在左、暗面在右
    parapet = poly([(41, 68), (41, 45), (45, 43), (45, 47), (49, 45), (49, 41), (54, 38), (54, 43), (57, 42), (57, 38), (60, 36), (63, 38), (63, 42), (66, 44), (66, 41), (70, 44), (70, 48), (72, 49), (72, 46), (78, 51), (78, 58), (73, 61), (73, 68)])
    step_l = poly([(30, 57), (34, 55), (34, 58), (36, 58), (36, 56), (41, 54), (41, 70), (34, 70), (30, 66)])
    body_top = rect(35, 68, 73, 85)
    body = poly([(37, 86), (71, 86), (71, 140), (38, 140)])
    ring_low = rect(32, 143, 77, 153) | rect(32, 139, 39, 143) | rect(46, 139, 53, 143) | rect(60, 139, 67, 143) | rect(72, 139, 77, 143)
    base = poly([(35, 154), (75, 154), (78, 180), (31, 180)])
    tower = parapet | step_l | body_top | body | ring_low | base
    corner_x = 60

    def block(mask, flat=False):
        """一段方块体：先向已画好的下段投影，再铺亮暗两面的砌块，最后描边。flat 时只平涂亮暗两面。"""
        under = shift(mask, 0, 3) & ~mask & c_tower[0]
        c.paint(under & (XX <= corner_x), WALL_D[1])
        c.paint(under & (XX > corner_x), WALL_D[0])
        if flat:
            c.paint(mask & (XX <= corner_x), WALL[3])
            c.paint(mask & (XX > corner_x), WALL_D[3])
        else:
            c.stone_bricks(mask & (XX <= corner_x), WALL, bw=16, bh=9, ox=3, oy=5, base=2, seed=61)
            c.stone_bricks(mask & (XX > corner_x), WALL_D, bw=16, bh=9, ox=9, oy=5, base=2, seed=62)
        c.paint(mask & (XX == corner_x), WALL_D[1])
        c.paint(edge(mask), EDGE)
        c_tower[0] = c_tower[0] | mask

    c_tower = [blank()]
    block(base)
    block(body)
    block(ring_low)
    block(body_top)
    block(parapet | step_l, flat=True)
    # 垛口迎光的上沿
    top_lit = shrink(parapet | step_l) & ~shift(shrink(parapet | step_l), 0, 1)
    c.paint(top_lit & (XX <= corner_x), WALL[4])
    # 塔顶和塔肩之间一道水平砌缝
    c.paint(rect(31, 62, corner_x, 62) & (parapet | step_l), WALL[1])
    c.paint(rect(corner_x + 1, 62, 72, 62) & parapet, WALL_D[1])
    # 垛口内侧：暗色走道，让塔顶有厚度
    c.paint(poly([(43, 50), (59, 43), (59, 45), (45, 52)]), WALL_D[1])
    c.paint(poly([(62, 48), (76, 56), (73, 58), (62, 51)]), WALL_D[0])

    # 窗：两个斜切的黑洞，火从里面向上窜，火焰发光
    win_a = poly([(62, 58), (71, 64), (71, 79), (62, 73)])
    c.paint(outer(win_a), EDGE)
    c.paint(win_a, CRAG[0])
    c.glow(poly([(63, 73), (63, 67), (65, 69), (67, 62), (68, 67), (70, 66), (70, 78)]), FIRE[2])
    c.glow(poly([(64, 73), (65, 71), (67, 67), (68, 71), (69, 72), (69, 77)]), FIRE[3])
    c.glow(poly([(66, 75), (67, 72), (68, 75), (68, 77)]), FIRE[4])

    win_b = poly([(41, 96), (50, 92), (50, 110), (41, 114)])
    c.paint(outer(win_b), EDGE)
    c.paint(win_b, CRAG[0])
    c.glow(poly([(42, 113), (42, 105), (44, 107), (46, 98), (47, 104), (49, 102), (49, 110)]), FIRE[2])
    c.glow(poly([(43, 112), (44, 109), (46, 104), (47, 108), (48, 108), (48, 110)]), FIRE[3])
    c.glow(poly([(45, 111), (46, 108), (47, 110)]), FIRE[4])

    # 蓝青节点线路：上段到裂缝止，中间碎片错位，下段从裂缝下重新开始
    c.glow(ring_diamond(52, 50, 4), TEAL[3])
    c.glow(rect(52, 55, 53, 66), TEAL[3])
    c.glow(line([(59, 75), (61, 73)]), TEAL[3])
    c.glow(line([(52, 87), (56, 81)]), TEAL[3])
    c.glow(rect(55, 90, 56, 99), TEAL[3])
    c.glow(ring_diamond(55, 104, 4), TEAL[3])

    # 裂缝：从击中点折向右下，越走越细，切断线路
    crack = poly([(39, 55), (47, 58), (46, 63), (53, 68), (54, 72), (60, 77), (64, 85), (58, 78), (52, 74), (51, 71), (44, 66), (43, 61), (38, 58)])
    c.paint(crack, CRAG[0])
    c.paint((shift(crack, 1, 1) & ~crack) & tower, WALL[4])

    # 岩基：方块阶梯的暗岩，几块硬边切面，不加颗粒
    rock = blocky(poly([(18, 192), (24, 184), (34, 176), (48, 172), (64, 168), (76, 176), (88, 184), (95, 192)]), 2)
    c.paint(rock, CRAG[2])
    for pts, tone in [([(34, 176), (48, 172), (52, 180), (40, 192), (24, 192)], CRAG[3]), ([(64, 168), (76, 176), (80, 192), (64, 192), (60, 180)], CRAG[1]), ([(88, 184), (95, 192), (82, 192)], CRAG[3])]:
        c.paint(blocky(poly(pts), 2) & rock, tone)
    c.paint(rock & ~shift(rock, 0, -1), CRAG[4])

    # ------------------------------ 冠顶：金色方块冠，从下方看到暗色的内腔，整体倾斜悬空
    under = poly([(52, 25), (70, 24), (88, 41), (88, 45), (70, 38), (58, 31)])
    face_r = poly([(70, 24), (76, 12), (79, 15), (77, 18), (81, 20), (83, 12), (86, 12), (90, 15), (86, 24), (90, 29), (93, 26), (95, 26), (97, 28), (90, 42), (88, 45), (88, 41)])
    face_l = poly([(52, 25), (65, 7), (67, 7), (63, 14), (71, 15), (73, 12), (76, 12), (70, 24)])
    c.paint(under, GOLD[1])
    c.paint(shrink(under), GOLD[0])
    c.paint(poly([(70, 27), (87, 43), (70, 37)]), GOLD[1])
    c.paint(face_r, GOLD[3])
    c.paint(face_r & ~shift(face_r, 1, -1), GOLD[2])
    c.paint(face_l, GOLD[4])
    c.paint(face_l & ~shift(face_l, 0, -1), GOLD[5])
    c.paint(line([(53, 23), (69, 22)]), GOLD[2])
    c.paint(line([(71, 22), (89, 38)]), GOLD[1])
    c.paint(edge(face_l | face_r | under), GOLD[1])

    # ------------------------------ 左侧坠落者：头朝下，四肢甩开
    tail(c, [(16, 106), (12, 100), (8, 93), (8, 87), (11, 85)], HAIR_BLACK, 4, 3, base=3)
    # 腿与靴：朝上。一条竖着蹬，一条横着甩
    pants_a = line([(18, 98), (20, 90)], 5)
    c.shape(pants_a, FELT, base=3, shadow=1, hi=0)
    boot_a = line([(21, 90), (23, 83)], 5) | poly([(20, 80), (22, 78), (27, 81), (26, 84), (22, 84)])
    c.shape(boot_a, BOOT, base=2, shadow=1, hi=1)
    pants_b = line([(22, 100), (27, 98)], 4)
    c.shape(pants_b, FELT, base=3, shadow=1, hi=0)
    boot_b = line([(28, 98), (31, 96)], 5) | poly([(30, 95), (31, 90), (34, 91), (34, 97), (31, 98)])
    c.shape(boot_b, BOOT, base=2, shadow=1, hi=1)
    # 外衣的三片衣摆：被风掀起，米色宽边
    flap_a = poly([(2, 107), (7, 98), (13, 100), (15, 108), (12, 112), (6, 110)])
    flap_b = poly([(12, 97), (20, 92), (23, 108), (15, 108)])
    flap_c = poly([(21, 110), (27, 103), (34, 104), (28, 110), (21, 115)])
    for f_ in (flap_a, flap_b, flap_c):
        c.shape(f_, CLOTH, base=2, shadow=1, hi=0)
    c.paint(line([(3, 107), (8, 98)]), FELT[3])
    c.paint(line([(12, 96), (20, 92), (23, 107)]), FELT[3])
    c.paint(line([(22, 110), (27, 103), (34, 104)]), FELT[3])
    # 躯干与腰带
    torso = poly([(13, 108), (22, 106), (24, 110), (21, 117), (20, 124), (15, 124), (14, 116)])
    c.shape(torso, CLOTH, base=2, shadow=1, hi=0)
    c.paint(line([(17, 111), (19, 120)]), FELT[3])
    belt_m = poly([(12, 108), (22, 107), (22, 110), (12, 111)])
    c.paint(belt_m, SASH[2])
    c.paint(edge(belt_m) & ~shift(belt_m, 0, -1), SASH[0])
    c.pxs([(14, 108), (15, 108), (14, 109), (15, 109)], TEAL[2])
    # 两只宽袖：向右下和左下张开，手指散开
    arm_a = poly([(19, 113), (26, 115), (33, 120), (31, 127), (25, 125), (20, 122)])
    c.shape(arm_a, CLOTH, base=2, shadow=1, hi=0)
    cuff_a = poly([(29, 117), (33, 120), (31, 127), (27, 125)])
    c.paint(cuff_a, FELT[3])
    c.paint(cuff_a & ~shift(cuff_a, -1, -1), FELT[2])
    c.sprite(29, 127, HAND_SPREAD, {'l': SKIN[3]})
    arm_b = poly([(9, 124), (15, 124), (14, 133), (12, 136), (5, 134)])
    c.shape(arm_b, CLOTH, base=2, shadow=1, hi=0)
    cuff_b = poly([(5, 133), (12, 130), (14, 134), (6, 137)])
    c.paint(cuff_b, FELT[3])
    c.paint(cuff_b & ~shift(cuff_b, -1, -1), FELT[2])
    c.sprite(2, 137, HAND_SPREAD, {'l': SKIN[3]}, flip=True)
    # 头：惊呼，黑发描一圈夜空亮色
    with_rim(c, lambda: head(c, 15, 113, '3q', 'M', HAIR_BLACK, BROWN_IRIS, mouth='open'), NAVY[3], rect(4, 110, 16, 126))

    # ------------------------------ 右侧坠落者：仰身向右后倒，长发和裙摆朝反方向飘
    tail(c, [(92, 106), (96, 112), (97, 117), (94, 119), (92, 118)], HAIR_SILVER, 3, 2, base=2)
    back_hair = poly([(99, 88), (104, 90), (106, 95), (110, 99), (111, 104), (107, 101), (106, 106), (103, 102), (101, 104), (100, 96)])
    c.paint(back_hair, HAIR_SILVER[2])
    c.paint(back_hair & ~shift(back_hair, -1, -1), HAIR_SILVER[3])
    c.paint(edge(back_hair), HAIR_SILVER[0])
    c.fold([(102, 92), (104, 97), (106, 101)], HAIR_SILVER[1], back_hair)
    # 长裙：外层灰绿、内层米色，朝左鼓起
    skirt = blob([(90, 102), (82, 98), (73, 99), (70, 103), (72, 110), (76, 116), (84, 119), (90, 119), (92, 110)])
    c.shape(skirt, MOSS, base=2, shadow=2, hi=0)
    panel = poly([(72, 100), (85, 101), (90, 104), (84, 119), (80, 109), (75, 110), (72, 104)])
    c.paint(panel, FELT[3])
    c.paint(panel & ~shift(panel, -1, -1), FELT[2])
    c.paint(poly([(74, 107), (86, 104), (88, 105), (80, 109), (75, 110)]), FELT[1])
    c.paint(line([(83, 109), (81, 114)]), FELT[1])
    # 小腿与长靴：从裙下伸向左下
    shin = poly([(77, 110), (80, 109), (79, 113), (76, 115)])
    c.volume(shin, SKIN, 3, shadow=1, hi=0)
    boot_w = poly([(75, 113), (79, 114), (76, 120), (75, 123), (69, 122), (69, 120), (72, 118)])
    c.shape(boot_w, BOOT, base=2, shadow=1, hi=1)
    # 躯干
    torso_w = poly([(91, 97), (98, 95), (99, 100), (95, 105), (90, 103)])
    c.shape(torso_w, MOSS, base=2, shadow=1, hi=0)
    # 红腰带与向左飞的飘带
    ribbon = poly([(90, 102), (83, 97), (76, 95), (78, 97), (77, 98), (82, 99), (88, 104)])
    c.paint(ribbon, SASH[2])
    c.paint(edge(ribbon) & ~shift(ribbon, 0, -1), SASH[1])
    sash = poly([(89, 101), (93, 99), (96, 103), (92, 106)])
    c.paint(sash, SASH[2])
    c.paint(edge(sash), SASH[1])
    c.pxs([(95, 98), (96, 98), (95, 99), (96, 99)], TEAL[2])
    # 两只宽袖：一只上举抓空，一只向右下甩开
    arm_c = poly([(87, 90), (92, 87), (95, 94), (92, 98), (88, 96)])
    c.shape(arm_c, MOSS, base=2, shadow=1, hi=0)
    cuff_c = poly([(85, 91), (90, 87), (92, 89), (87, 93)])
    c.paint(cuff_c, FELT[3])
    c.paint(line([(88, 87), (86, 84)]), SKIN[3])
    c.sprite(82, 78, HAND_UP, {'l': SKIN[3]})
    arm_d = poly([(98, 100), (102, 101), (107, 107), (103, 112), (99, 110)])
    c.shape(arm_d, MOSS, base=2, shadow=1, hi=0)
    cuff_d = poly([(99, 110), (106, 105), (107, 107), (103, 112)])
    c.paint(cuff_d, FELT[3])
    c.sprite(104, 109, HAND_RIGHT, {'l': SKIN[3]})
    # 头：朝上看，张口
    head(c, 97, 85, '3q', 'M', HAIR_SILVER, GREY_IRIS, eyes='up', mouth='open', flip=True)

    # ------------------------------ 闪电：从左上角折下，尖端撞在塔上部，炸开一朵金星
    bolt = poly([(0, 0), (17, 0), (31, 24), (20, 30), (39, 50), (35, 52), (8, 28), (21, 22), (0, 2)])
    c.glow(bolt, FIRE[4])
    c.glow(line([(17, 0), (31, 24)]) | line([(20, 30), (38, 50)]), FIRE[5])
    burst = poly([(27, 52), (36, 50), (35, 39), (40, 47), (43, 47), (43, 50), (47, 50), (43, 54), (44, 58), (39, 55), (36, 54)])
    c.glow(burst, FIRE[4])
    c.glow(poly([(36, 51), (41, 49), (42, 54), (38, 54)]), FIRE[5])

    return c
