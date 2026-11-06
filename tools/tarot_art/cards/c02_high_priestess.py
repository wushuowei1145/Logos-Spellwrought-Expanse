"""2 女祭司：银紫长发的猫耳女性正面静坐，左暗右亮双柱之间；头顶月冠，膝上半遮的卷轴三节点，
脚边大月牙；幕后三枚大星与层叠的魔力井。整张基本对称（x 与 111-x 成对）。"""
from figures import *

# 暗柱：比帷幕亮半档但仍是暗紫；明柱用象牙色阶
DARK_PILLAR = ['#1d1846', '#2f2768', '#443a8c', '#574ca6', '#7268bd']
LIGHT_PILLAR = ['#857656', '#b8aa86', '#e9dfbe', '#f1e8cc', '#faf4dc']
# 法袍：比帷幕更灰更暗，人和帷幕才分得开
ROBE = ['#15112c', '#231d42', '#352d5c', '#463d74', '#5e5590']
# 银紫发：在 HAIR_LILAC 和 HAIR_SILVER 之间，比丁香更灰一点
HAIR = ['#544870', '#8578a2', '#b3a7c9', '#d3cade', '#f0ebf5']


def thick(m):
    """2 像素宽的线：叠一条平移的，走向更整齐。"""
    return m | shift(m, 1, 0) | shift(m, 0, 1)

def sym(pts):
    """左半轮廓加它的镜像。"""
    m = poly(pts)
    return m | mirror(m)


def big_star(c, cx, cy, rx, ry, k=2):
    """帷幕大星：四芒内收，发光。cx 可以是半整数。"""
    m = poly([(cx, cy - ry), (cx + k, cy - k), (cx + rx, cy), (cx + k, cy + k), (cx, cy + ry), (cx - k, cy + k), (cx - rx, cy), (cx - k, cy - k)])
    c.glow(m, GOLD[3])
    c.glow(shrink(m), GOLD[4])


def pillar(c, shaft, ring, base, low, ramp):
    """一根柱子：柱头喇叭口、颈环、柱身、柱础、下段。参数都是 (x0, x1)；光从左上来，暗面都在右侧。"""
    cap_x0 = shaft[0] - 3 if shaft[0] < 56 else shaft[0] - 5
    cap_x1 = cap_x0 + 21
    # 柱身：石砖只留水平缝，读得出是一节节垒的
    body = rect(shaft[0], 14, shaft[1], 88)
    c.stone_bricks(body, ramp, bw=30, bh=10, ox=shaft[0], oy=4, seed=41, base=3)
    c.paint(rect(shaft[1] - 3, 14, shaft[1], 88), ramp[1])
    c.paint(rect(shaft[1], 14, shaft[1], 88), ramp[0])
    c.paint(rect(shaft[0], 14, shaft[0], 88), ramp[0])
    # 下段
    lower = rect(low[0], 98, low[1], 170)
    c.stone_bricks(lower, ramp, bw=30, bh=10, ox=low[0], oy=8, seed=42, base=2)
    c.paint(rect(low[1] - 3, 98, low[1], 170) & lower, ramp[1])
    # 柱头
    flare = poly([(cap_x0 + 1, 3), (cap_x1 - 1, 3), (cap_x1 - 4, 9), (cap_x0 + 4, 9)])
    c.volume(flare, ramp, 3, shadow=2, hi=0)
    c.paint(outer(flare) | edge(flare) & (YY > 3), ramp[0])
    c.shape(rect(cap_x0, 0, cap_x1, 2), ramp, base=3, shadow=1, hi=0)
    c.shape(rect(cap_x0 + 2, 10, cap_x1 - 2, 13), ramp, base=3, shadow=1, hi=1)
    # 柱础
    c.shape(rect(ring[0], 89, ring[1], 92), ramp, base=3, shadow=1, hi=1)
    c.shape(rect(base[0], 93, base[1], 97), ramp, base=3, shadow=1, hi=1)


def draw():
    c = Card('02_high_priestess')

    # 帷幕后的深夜蓝
    c.paint(rect(0, 0, W - 1, H - 1), NIGHT[1])
    gap = sym([(55.5, 8), (18, 97), (18, 132), (55.5, 132)])

    # 魔力井：三层水面、晶体。只留轮廓，亮度留给水面和晶体
    plate3 = sym([(34, 48), (55.5, 48), (55.5, 52), (34, 52)])
    body3 = sym([(36, 52), (55.5, 52), (55.5, 66), (39, 66)])
    plate2 = sym([(42, 37), (55.5, 37), (55.5, 40), (42, 40)])
    body2 = sym([(44, 40), (55.5, 40), (55.5, 48), (44, 48)])
    plate1 = sym([(48, 29), (55.5, 29), (55.5, 31), (48, 31)])
    stem = sym([(50, 31), (55.5, 31), (55.5, 37), (50, 37)])
    c.vgrad(body3, [TEAL[1], TEAL[0], NIGHT[2]], 52, 66)
    c.paint(body2, TEAL[1])
    c.paint(stem, TEAL[1])
    for plate in (plate3, plate2, plate1):
        c.paint(plate, TEAL[1])
        c.paint(plate & ~shift(plate, 0, -1), TEAL[0])
    c.glow(rect(38, 48, 73, 48), TEAL[3])
    c.glow(rect(46, 37, 65, 37), TEAL[3])
    c.glow(rect(50, 29, 61, 29), TEAL[3])
    c.glow(sym([(53, 31), (55.5, 31), (55.5, 37), (53, 37)]), TEAL[3])
    c.glow(rect(55, 31, 56, 37), TEAL[4])
    crystal = sym([(55.5, 20), (53, 23), (53, 28), (55.5, 28)])
    c.paint(outer(crystal), TEAL[0])
    c.glow(crystal, TEAL[3])
    c.glow(poly([(55, 21), (53, 23), (53, 28), (55, 28)]), TEAL[4])

    # 帷幕：两片从中心顶点向两侧垂下，顶上压一道暗弧，褶子是由粗到细的硬边
    curtain = rect(18, 0, 93, 97) & ~gap
    c.paint(curtain, PURPLE[2])
    swag = sym([(18, 0), (55.5, 0), (55.5, 5), (46, 10), (30, 13), (18, 13)])
    c.paint(swag & curtain, PURPLE[1])
    for fold in ([(53, 9), (46, 19), (34, 25)], [(36, 32), (29, 50), (20, 66)], [(47, 26), (40, 44), (31, 62)]):
        m = taper(fold, 3, 1) & curtain
        c.paint(m | mirror(m), PURPLE[1])
    c.paint((edge(gap) & curtain) | (outer(gap) & curtain), PURPLE[1])

    # 三枚大星
    big_star(c, 27, 26, 6, 9)
    big_star(c, 84, 26, 6, 9)
    big_star(c, 55.5, 4, 6, 8)

    # 双柱：左暗右亮，明度差是这张牌的骨架
    pillar(c, (3, 16), (1, 18), (0, 19), (0, 17), DARK_PILLAR)
    pillar(c, (95, 108), (93, 110), (92, 111), (94, 111), LIGHT_PILLAR)

    # 石座与台阶
    floor = rect(0, 168, 111, 191)
    c.stone_bricks(floor, STONE, bw=16, bh=8, ox=4, oy=0, seed=43, base=2)
    c.paint(rect(0, 168, 111, 168), STONE[4])
    c.stone_bricks(rect(6, 137, 105, 167), STONE, bw=16, bh=8, ox=6, oy=1, seed=44, base=2)
    c.paint(rect(105, 137, 105, 167) | rect(6, 137, 6, 167), STONE[0])
    seat = rect(4, 130, 107, 136)
    c.shape(seat, STONE, base=3, shadow=2, hi=1)

    # 石座两侧卷起的双尾
    tail(c, [(20, 166), (11, 159), (8, 151), (11, 144), (18, 141)], HAIR, 5, 4, base=3)
    tail(c, [(91, 166), (100, 159), (103, 151), (100, 144), (93, 141)], HAIR, 5, 4, base=3)

    # 猫耳向外斜张，尖端离开月冠；纱边压住耳根，耳和月冠之间隔着一道象牙色
    ear_l = poly([(42, 49), (51, 55), (46, 62)])
    inner_l = poly([(44, 52), (48, 56), (46, 60)])
    tuft_l = poly([(45, 56), (48, 57), (46, 60)])
    for mask, inner_m, tuft in ((ear_l, inner_l, tuft_l), (mirror(ear_l), mirror(inner_l), mirror(tuft_l))):
        c.paint(outer(mask), HAIR[0])
        c.paint(mask, HAIR[2])
        c.paint(inner_m, PINK[1])
        c.paint(tuft, IVORY[4])

    # 头纱：象牙色大三角，从发顶披到座上，在头发外面始终露出一条
    veil = sym([(55.5, 53), (49, 55), (46, 60), (42, 70), (34, 80), (26, 96), (18, 112), (10, 130), (55.5, 130)])
    c.paint(veil, IVORY[3])
    c.paint(edge(veil), IVORY[0])
    c.paint(veil & (YY < 58) & ~shift(veil, 0, 1), IVORY[4])
    inner_veil = sym([(42, 70), (39, 76), (37, 84), (42, 84), (46, 70)])
    c.paint(inner_veil & shrink(veil), IVORY[1])
    low_veil = sym([(21, 112), (13, 128), (24, 130)])
    c.paint(low_veil & shrink(veil), IVORY[1])

    # 后发：波浪外缘一收一放，里面再走两条 S 形暗线，分出几绺
    wave = blob([(47, 62), (44, 70), (42, 78), (37, 85), (35, 91), (29, 97), (31, 104), (24, 110), (26, 116), (17, 122), (15, 127), (19, 131), (26, 131),
                 (32, 124), (40, 108), (47, 90), (50, 74)])
    for m in (wave, mirror(wave)):
        c.paint(m, HAIR[2])
        c.paint(m & ~shift(m, 2, 2), HAIR[3])
        c.paint(edge(m), HAIR[0])
    for pts in ([(46, 70), (42, 82), (39, 90), (34, 96), (36, 103), (29, 110), (31, 116), (23, 122), (21, 129)], [(47, 80), (43, 90), (39, 98), (40, 104), (34, 112), (35, 117), (29, 124)]):
        m = curve(pts) & shrink(wave)
        c.paint(m, HAIR[1])
        c.paint(mirror(m), HAIR[1])
    # 颈后暗发：挡住脖子两侧露出来的纱
    c.paint(rect(47, 68, 64, 81), HAIR[1])

    # 法袍：一整块深色，膝处撑开，裙摆铺到台阶
    robe = sym([(55.5, 79), (51, 80), (45, 84), (41, 90), (37, 98), (34, 108), (29, 118), (24, 128), (22, 134), (21, 148), (16, 165), (8, 178), (0, 183), (0, 185), (36, 185), (55.5, 185)])
    c.paint(robe, ROBE[2])
    c.paint(edge(robe), ROBE[0])
    # 大袖外侧比袍身亮一档，手臂的轮廓才出得来
    sleeve = poly([(41, 91), (46, 100), (44, 110), (36, 124), (29, 142), (24, 128), (29, 118), (34, 108), (37, 98)])
    c.paint((sleeve | mirror(sleeve)) & shrink(robe), ROBE[3])
    # 裙上的硬边暗褶：中缝、两腿外侧
    c.paint(sym([(55.5, 132), (53, 150), (49, 176), (55.5, 176)]), ROBE[1])
    for wedge in ([(28, 142), (21, 165), (19, 165), (26, 142)], [(41, 134), (33, 170), (38, 170)]):
        m = poly(wedge)
        c.paint(m | mirror(m), ROBE[1])
    # 胸下收口的暗面
    c.paint(sym([(45, 100), (48, 100), (46, 112), (44, 110)]), ROBE[1])
    c.paint(sym([(48, 104), (55.5, 104), (55.5, 112), (46, 112)]), ROBE[1])

    # 金边：两道长线从袖口一路走到裙摆
    band = curve([(44, 107), (41, 112), (35, 124), (28, 142), (20, 160), (12, 176)], 3)
    c.paint(band | mirror(band), GOLD[3])

    # 前绺：S 形垂在胸前，末端收细
    for pts in ([(50, 76), (50, 84), (47, 90), (47, 96), (49, 100)], [(61, 76), (61, 84), (64, 90), (64, 96), (62, 100)]):
        m = taper(pts, 6, 3)
        c.paint(m, HAIR[2])
        c.paint(m & ~shift(m, 1, 1), HAIR[3])
        c.paint(edge(m), HAIR[1])

    # 卷轴：纸面先画，再让右袖和两只手压上去，读出半遮
    page = poly([(44, 113), (67, 113), (59, 126), (37, 126)])
    c.paint(page, DUSK[3])
    c.paint(edge(page), DUSK[0])
    c.paint(line([(44, 114), (66, 114)]), DUSK[1])
    roll = rect(35, 126, 54, 131)
    c.paint(roll, DUSK[3])
    c.paint(rect(36, 127, 53, 127), DUSK[4])
    c.paint(rect(35, 130, 54, 131), DUSK[2])
    c.paint(edge(roll), DUSK[0])
    c.paint(ellipse(53, 128.5, 2, 3), DUSK[1])
    c.paint(ring(53, 128.5, 2, 3), DUSK[0])
    c.px(53, 128, DUSK[3])
    # 三节点
    c.paint(line([(44, 119), (51, 122), (58, 118)]), WOOD[2])
    c.node(44, 119, 2)
    c.node(51, 122, 2)
    c.node(58, 118, 2)

    # 右袖压住卷轴右上：一片叶形袖口，两道金边合在袖尖
    flap = blob([(63, 118), (66, 112), (71, 111), (74, 120), (77, 132), (71, 125)])
    c.paint(flap, ROBE[3])
    c.paint(edge(flap), ROBE[0])
    c.paint(thick(curve([(66, 111), (71, 111), (74, 120), (77, 132)])), GOLD[3])

    pal = {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0]}
    # 上手：横搭卷轴上沿，四指朝右下垂
    c.sprite(43, 108, [
        '...xxxxxxx....',
        '.xxllllllllxx.',
        'xlllllllllllsx',
        'xllllllllllssx',
        'xllsslllxlxlxx',
        '.xxxxxxxlxlxl.',
        '........x.x.x.',
    ], pal)
    # 下手：从右袖伸来，指尖落在卷筒端头
    c.sprite(55, 119, [
        '.........xxxxx',
        '.......xxlllls',
        '.....xxllllllx',
        '...xxllllllssx',
        '..xllxlllllsx.',
        '.xllxlllllsx..',
        'xlxlxlllssx...',
        'xlxlxlsxxx....',
        '.x.x.xx.......',
    ], pal)
    # 袖口金边压在手腕上，手才是从袖子里伸出来的
    c.paint(thick(curve([(62, 118), (68, 118), (72, 124), (77, 132)])), GOLD[3])

    # 脖子、立领与菱扣
    c.paint(rect(53, 74, 58, 78), SKIN[2])
    c.paint(rect(53, 76, 58, 76), SKIN[1])
    c.paint(sym([(49, 79), (55.5, 79), (55.5, 84), (49, 84)]), ROBE[2])
    collar = thick(line([(49, 78), (51, 78), (55, 81)]))
    c.paint(collar | mirror(collar), GOLD[3])
    yoke = thick(curve([(43, 92), (47, 88), (52, 86)]))
    c.paint(yoke | mirror(yoke), GOLD[3])
    diamond = (np.abs(XX - 55.5) + np.abs(YY - 86)) <= 3.5
    c.paint(outer(diamond), GOLD[1])
    c.paint(diamond, GOLD[3])
    c.glow((np.abs(XX - 55.5) + np.abs(YY - 86)) <= 2.5, TEAL[3])
    c.glow((np.abs(XX - 55.5) + np.abs(YY - 85.5)) <= 1, TEAL[4])

    # 头：五官用头部精灵，头发在上面重铺一层
    head(c, 56, 54, 'front', 'L', HAIR, VIOLET_IRIS, eyes='open', mouth='none', ears=False)
    # 浅浅的笑：嘴角只比中间高一格，不用深色
    c.pxs([(54, 72), (57, 72)], SKIN[2])
    c.pxs([(55, 73), (56, 73)], SKIN[0])
    # 下巴线收窄变浅，女子的下颌不要一道深色横杠
    c.paint(rect(53, 75, 59, 75), SKIN[2])
    c.paint(rect(55, 75, 57, 75), SKIN[1])
    c.paint(rect(53, 76, 58, 76), SKIN[1])
    # 两侧长刘海：中分，从发旋扫向两颊，不压脸
    bang_l = poly([(56, 56), (50, 57), (46, 61), (45, 68), (46, 74), (48, 78), (49, 71), (49, 66), (51, 63), (55, 60)])
    bang_r = poly([(56, 56), (62, 57), (66, 61), (67, 68), (66, 74), (64, 78), (63, 71), (63, 66), (61, 63), (57, 60)])
    for m in (bang_l, bang_r):
        c.paint(m, HAIR[2])
        c.paint(m & (YY <= 64), HAIR[3])
        c.paint(m & rect(0, 58, W, 59) & shrink(m), HAIR[4])
        c.paint(edge(m), HAIR[1])
    c.paint(line([(56, 56), (56, 60)]), HAIR[1])
    # 鬓发里两道暗线，免得成一块平板
    for pts in ([(48, 61), (47, 67), (47, 73)], [(64, 61), (65, 67), (65, 73)]):
        c.paint(line(pts), HAIR[1])

    # 月冠：新月托一枚圆珠，落在纱上，和猫耳之间留开
    moon = ellipse(55.5, 44.5, 9, 8.5) & ~ellipse(55.5, 42, 7, 7.5)
    c.paint(outer(moon), GOLD[0])
    c.glow(moon, GOLD[3])
    c.glow(moon & ~shift(moon, 1, 1), GOLD[4])
    orb = ellipse(55.5, 45, 2.5)
    c.paint(outer(orb), GOLD[0])
    c.glow(orb, GOLD[3])
    c.glow_px([(54, 44), (55, 44), (54, 45)], GOLD[5])

    # 脚边大月牙：先画内圈里的暗面、脚和地，再压月牙
    disk = ellipse(55.5, 174, 13.5) & (YY > 170)
    c.paint(disk, ROBE[0])
    c.paint(disk & (YY > 184), STONE[3])
    for x0 in (49, 57):
        c.paint(rect(x0, 175, x0 + 5, 178), SKIN[2])
        c.paint(rect(x0, 175, x0 + 5, 175), SKIN[1])
        shoe = poly([(x0 - 1, 177), (x0 + 2, 180), (x0 + 3, 180), (x0 + 6, 177), (x0 + 7, 183), (x0 + 5, 186), (x0, 186), (x0 - 1, 183)])
        c.paint(shoe, ROBE[3])
        c.paint(edge(shoe) & (YY > 178), ROBE[0])
        c.paint(line([(x0 - 1, 177), (x0 + 2, 180), (x0 + 3, 180), (x0 + 6, 177)]), GOLD[3])
    feet_moon = ellipse(55.5, 181, 18.5) & ~ellipse(55.5, 174, 13.5)
    c.paint(outer(feet_moon), GOLD[0])
    c.paint(feet_moon, GOLD[3])
    c.paint(feet_moon & ~shift(feet_moon, 1, 1), GOLD[4])
    c.paint(feet_moon & ~shift(feet_moon, -1, -1), GOLD[2])

    # 两朵白花
    for x in (13, 98):
        c.paint(poly([(x - 9, 191), (x - 7, 187), (x - 2, 187), (x, 191)]), LEAF[2])
        c.paint(poly([(x + 9, 191), (x + 7, 187), (x + 2, 187), (x, 191)]), LEAF[3])
        white_flower(c, x, 184, 4, outline=STONE[0])

    return c
