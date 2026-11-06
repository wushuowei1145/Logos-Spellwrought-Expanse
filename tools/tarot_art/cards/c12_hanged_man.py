"""12 倒吊人：黑蓝发猫耳男子只由一只脚踝悬在活木横梁下，另一腿弯成三角，手臂在身后；头下脚上，神情安宁，头部光环下端一枚蓝青节点。"""
from figures import *

# 浅色天空的四档：只差一点点，读得出层次又不抢人物
CREAM = ['#e9dfbf', '#f0e7cb', '#f6efd9', '#fcf7e8']
# 上衣的深靛：比 NAVY 灰一点，和发色分得开
JACKET = ['#15162c', '#23244a', '#323463', '#46497f', '#666aa0']
# 带子：亮的旧布色，浅背景上要靠暗线分开
CLOTH = ['#8a7a58', '#b3a47e', '#d8cba6', '#efe7cb', '#fbf7e6']
# 草地：比 SAGE 再提一档
MEADOW = [SAGE[1], SAGE[2], SAGE[3], SAGE[3], SAGE[4]]


def leaf(c, x0, y0, x1, y1, wide=3):
    """一片叶：两头尖的橄榄形，叶脉一条，迎光半边亮。"""
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
    dx, dy = x1 - x0, y1 - y0
    n = max(np.hypot(dx, dy), 1)
    nx, ny = -dy / n * wide, dx / n * wide
    m = poly([(x0, y0), (mx + nx, my + ny), (x1, y1), (mx - nx, my - ny)])
    c.paint(outer(m), LEAF[0])
    c.paint(m, GRASS[3])
    lit = poly([(x0, y0), (mx + nx, my + ny), (x1, y1)]) if nx + ny < 0 else poly([(x0, y0), (mx - nx, my - ny), (x1, y1)])
    c.paint(m & ~lit, GRASS[2])
    c.paint(line([(x0, y0), (x1, y1)]) & m, GRASS[1])
    return m


def tuft(c, x, y, h=7, n=3):
    """一丛草：几片尖叶从地面向上散开。"""
    for i in range(n):
        lean = (i - (n - 1) / 2) * 3
        blade = taper([(x + lean * 0.5, y), (x + lean, y - h + abs(lean) * 0.5)], 2, 1)
        c.paint(blade, LEAF[2] if i % 2 else LEAF[1])


def draw():
    c = Card('12_hanged_man')
    everything = rect(0, 0, W - 1, H - 1)

    # 浅色背景：上暗下亮的两道极淡过渡，再加两朵只比底色亮一档的方云，安静又有层次
    c.vgrad(everything, [CREAM[1], CREAM[2], CREAM[3]], 0, 176, soft=0.12)
    for parts, col in (([(72, 60, 104, 65), (78, 56, 98, 60), (84, 52, 92, 56)], CREAM[3]),
                       ([(16, 118, 34, 122), (20, 115, 30, 118)], CREAM[3])):
        m = blank()
        for q in parts:
            m |= rect(*q)
        c.paint(m, col)

    # 远山与草地：只用少数色块
    hills = blocky(poly([(0, 184), (10, 176), (22, 173), (36, 177), (58, 181), (78, 177), (98, 172), (111, 175), (111, 186), (0, 186)]), 2)
    c.paint(hills, SKY[3])
    c.paint(hills & ~shift(hills, 0, -2), SKY[4])
    meadow = rect(0, 182, 111, 191)
    c.turf(meadow, MEADOW, seed=41, base=2)
    c.paint(rect(0, 182, 111, 182), SAGE[4])

    # 光环：先画，身子压在它上面。金环发光，浅底上压一圈暗金才不散
    hx, hy, hr = 54, 154, 18
    halo = ring(hx, hy, hr, hr, 2)
    c.paint(outer(halo) & ~ellipse(hx, hy, hr - 2), CREAM[0])
    c.glow(halo, GOLD[3])
    c.glow(ring(hx, hy, hr, hr, 1) & (XX + YY < hx + hy), GOLD[4])
    c.glow(shrink(ellipse(hx, hy, hr), 1) & ~shrink(ellipse(hx, hy, hr), 2) & (XX + YY > hx + hy), GOLD[2])

    # 活木：树干和横梁连成一体，树皮用灰褐，大面上不跳色
    trunk = poly([(3, 0), (10, 0), (10, 7), (13, 13), (12, 30), (13, 42), (10, 60), (8, 100), (9, 130), (11, 160), (14, 175), (24, 191), (0, 191), (0, 50), (1, 45), (2, 20)])
    beam = taper([(8, 16), (18, 10), (34, 8), (56, 8), (78, 8), (96, 10)], 10, 2)
    twigs = [taper([(18, 9), (22, 5), (27, 1)], 3, 1), taper([(78, 8), (88, 4), (93, 0)], 3, 1),
             taper([(85, 8), (95, 10), (100, 9)], 2, 1), taper([(88, 9), (92, 12), (94, 15)], 2, 1)]
    for m in twigs:
        c.paint(m, DIRT[1])
    wood = trunk | beam
    c.bark(wood, DIRT, 42, base=3)
    c.paint(wood & (XX >= 7) & (YY > 20) & ~shift(wood, 2, 0), DIRT[1])
    c.paint(beam & ~shift(beam, 0, -2), DIRT[1])
    c.paint(beam & ~shift(beam, 0, 1), DIRT[4])
    c.paint(edge(wood), DIRT[0])

    # 两簇叶：左上小、右上大
    for a in [(14, 3, 20, 0), (20, 3, 25, 7), (25, 1, 33, 6), (27, 0, 35, 0)]:
        leaf(c, *a, wide=2)
    for a in [(93, 3, 100, 8), (101, 7, 110, 5), (100, 9, 110, 13), (100, 11, 104, 21), (94, 13, 96, 24), (87, 14, 92, 20), (95, 0, 104, 1)]:
        leaf(c, *a, wide=3)

    # 尾巴：从腰后垂下，末端略圆
    tail(c, [(66, 94), (72, 104), (75, 118), (76, 130), (75, 142), (76, 147)], JACKET, 3, 4, base=2)

    # 弯着的那条腿：大腿从胯到膝，小腿折回到直腿后面，围出一个三角空隙
    boot_b = poly([(46, 55), (53, 52), (56, 59), (49, 62)])
    c.shape(boot_b, LEATHER, base=2, shadow=1, hi=0)
    c.paint(line([(47, 54), (49, 62)]), LEATHER[3])
    shin = poly([(25, 62), (28, 59), (46, 54), (48, 61), (34, 68), (31, 70)])
    thigh = poly([(25, 63), (31, 69), (40, 74), (54, 80), (56, 90), (41, 92), (30, 76)])
    bent = shin | thigh
    c.paint(bent, RED[2])
    c.paint(bent & ~shift(bent, 1, 1), RED[3])
    c.paint(bent & ~shift(bent, -2, -2), RED[1])
    c.paint(line([(32, 70), (38, 66)]), RED[1])
    c.fold([(34, 76), (42, 84), (50, 88)], RED[1], thigh)
    c.paint(edge(bent), RED[0])

    # 手臂在身后：肘向两侧顶出，和身体之间留一条小缝
    arm_l = poly([(44, 100), (36, 107), (31, 113), (33, 123), (35, 128), (42, 133), (49, 137), (45, 118), (41, 112), (45, 107)])
    arm_r = poly([(65, 100), (72, 105), (78, 112), (76, 120), (73, 128), (70, 132), (60, 137), (64, 118), (68, 112), (65, 107)])
    c.shape(arm_l, JACKET, base=2, shadow=1, hi=1)
    c.shape(arm_r, JACKET, base=2, shadow=2, hi=0)
    for pts in ([(33, 113), (37, 112), (42, 113)], [(36, 118), (38, 124), (42, 128)], [(76, 113), (72, 112), (67, 113)], [(73, 118), (71, 124), (68, 128)]):
        c.paint(curve(pts), JACKET[0])

    # 上衣：倒着，下摆朝上，肩朝下
    torso = poly([(41, 92), (60, 89), (66, 88), (67, 92), (65, 97), (66, 105), (67, 118), (69, 132), (60, 137), (49, 137), (41, 133), (40, 128), (42, 117), (45, 107), (45, 100)])
    c.shape(torso, JACKET, base=2, shadow=1, hi=1)
    c.paint(line([(59, 90), (55, 94), (55, 122), (51, 127), (51, 137)]) & torso, GOLD[3])

    # 肘窝的小缝：两边都留，手臂才读得出是背在身后
    c.paint(poly([(41, 113), (43, 113), (42, 118)]), CREAM[2])
    c.paint(poly([(67, 113), (69, 113), (68, 118)]), CREAM[2])

    # 直腿：从脚踝一路到胯，只这一条连着悬带
    leg = poly([(54, 33), (62, 33), (63, 50), (66, 75), (67, 90), (52, 92), (54, 80), (52, 70), (52, 58), (53, 48)])
    c.paint(leg, RED[2])
    c.paint(leg & ~shift(leg, 2, 0), RED[3])
    c.paint(leg & ~shift(leg, -3, 0), RED[1])
    c.fold([(58, 44), (59, 66), (61, 86)], RED[1], leg)
    c.paint(edge(leg), RED[0])

    # 衣摆压在裤上
    hem = poly([(41, 92), (60, 89), (66, 88), (67, 92), (65, 97), (45, 100)])
    c.shape(hem, JACKET, base=2, shadow=1, hi=1)
    c.paint(line([(59, 90), (55, 94), (55, 100)]), GOLD[3])

    # 腰带与垂下的带尾：衣物朝下垂，带尾指向头的方向
    sash_end = poly([(60, 103), (65, 100), (67, 112), (69, 128), (62, 124), (59, 120)])
    c.shape(sash_end, RED, base=2, shadow=1, hi=1)
    c.fold([(63, 106), (64, 122)], RED[1], sash_end)
    sash = poly([(43, 97), (65, 96), (65, 102), (60, 104), (44, 105)])
    c.shape(sash, RED, base=2, shadow=1, hi=1)
    c.fold([(46, 100), (54, 99), (63, 99)], RED[1], sash)

    # 靴筒、靴子：鞋底朝上。悬带从靴子中间穿过，再绕脚踝一圈
    shaft = poly([(54, 30), (62, 30), (63, 35), (53, 35)])
    c.shape(shaft, LEATHER, base=3, shadow=1, hi=0)
    heel = poly([(49, 23), (54, 17), (58, 19), (58, 27), (54, 28), (51, 26)])
    toe = poly([(59, 14), (66, 12), (69, 14), (65, 20), (63, 26), (58, 26)])
    for m in (heel, toe):
        c.shape(m, LEATHER, base=2, shadow=1, hi=1)
    c.paint(line([(49, 23), (54, 17)]), LEATHER[0])
    c.paint(line([(59, 14), (66, 12), (69, 14)]), LEATHER[0])

    # 悬带：两股从横梁上垂下，向下收拢，脚踝处绕两圈。脖子和手上都没有绳
    band_l = poly([(55, 5), (58, 5), (58, 24), (57, 24)])
    band_r = poly([(58, 5), (60, 5), (59, 24), (58, 24)])
    band = band_l | band_r
    c.paint(outer(band), CLOTH[0])
    c.paint(band, CLOTH[4])
    c.paint(band_r, CLOTH[3])
    c.paint(rect(55, 4, 60, 4), CLOTH[0])
    wrap = poly([(53, 26), (62, 24), (63, 29), (61, 31), (54, 31)])
    c.paint(outer(wrap), CLOTH[0])
    c.paint(wrap, CLOTH[4])
    c.paint(line([(54, 29), (58, 27), (62, 28)]), CLOTH[1])
    c.paint(wrap & (YY >= 30), CLOTH[2])

    # 发块：头发朝地面垂，外缘全是向下、向外的尖。深靛提两档，和上衣分开，也不糊成一团黑
    hb = HAIR_BLUEBLACK
    bright = [hb[1], hb[2], hb[3], hb[3], hb[4]]
    hair = poly([(47, 144), (45, 147), (42, 150), (46, 151), (43, 155), (47, 155), (47, 160), (47, 164), (50, 159), (51, 167), (53, 160), (54, 168), (55, 160), (57, 167), (58, 159), (61, 164), (61, 160), (61, 155), (65, 155), (62, 151), (66, 150), (63, 147), (61, 144)])
    c.paint(outer(hair), hb[0])
    c.paint(hair, hb[3])
    c.paint(hair & ~shift(hair, 1, 1), hb[4])
    c.paint(hair & ~shift(hair, -1, -1), hb[2])

    # 脖子和领口
    c.paint(rect(50, 137, 58, 142), SKIN[2])
    c.paint(rect(50, 137, 58, 139), SKIN[1])
    collar = poly([(47, 134), (53, 134), (54, 137), (55, 134), (61, 134), (60, 139), (54, 137), (48, 139)])
    c.shape(collar, JACKET, base=2, shadow=0, hi=0)
    c.paint(line([(48, 139), (54, 137), (60, 139)]), GOLD[3])
    c.paint(line([(54, 134), (54, 137)]), GOLD[3])

    # 头：倒置，下巴朝上，发尾朝下。垂目，抿着一点点笑
    head(c, 54, 141, 'front', 'L', bright, BLUE_IRIS, eyes='down', mouth='line', vflip=True, ears=False, lash=hb[0])
    # 下巴那行线会读成第二张嘴，压淡
    c.paint(rect(50, 141, 58, 141), SKIN[2])
    # 头顶以下的发尾盖回来，发丝只留三道中间调
    low = hair & (YY >= 155)
    c.paint(low, hb[3])
    c.paint(low & ~shift(hair, 1, 1), hb[4])
    c.paint(low & ~shift(hair, -1, -1), hb[2])
    for pts in ([(50, 156), (50, 158)], [(58, 156), (58, 158)]):
        c.paint(line(pts), hb[2])
    # 猫耳：耳尖朝下、朝外，贴在发块两侧；内耳是一枚收尖的小三角
    for sx in (-1, 1):
        ear_m = poly([(54 + sx * 7, 154), (54 + sx * 11, 155), (54 + sx * 11, 163)])
        c.paint(outer(ear_m), hb[0])
        c.paint(ear_m, hb[3])
        c.paint(poly([(54 + sx * 9, 156), (54 + sx * 10, 156), (54 + sx * 10, 160)]), PINK[2])

    # 光环下端节点
    c.node(hx, hy + hr, 3)

    # 地面的几丛草
    tuft(c, 6, 191, 6, 3)
    tuft(c, 26, 191, 8, 4)
    tuft(c, 102, 191, 8, 4)

    return c
