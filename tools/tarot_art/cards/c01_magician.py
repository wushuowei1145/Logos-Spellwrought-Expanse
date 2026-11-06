"""1 魔术师：黑紫发蓝青眼的猫耳男性施法者，右手高举晶杖指天、左手食指指地，头顶无限符号；
桌上杖、剑、杯、币四器与术式载页，三枚节点把上下两手连成一线；红披风、两角白花与玫瑰。"""
from figures import *

# 桌面：比 WOOD 少一点跳动，四件金器压在上面才分得开
TABLE = ['#3f2717', '#5c3c20', '#6e4a28', '#7d5730', '#8f6838']
HAND_PAL = {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0]}


def thick(m):
    """2 像素宽的线：叠一条平移的，走向更整齐。"""
    return m | shift(m, 1, 0) | shift(m, 0, 1)

def leaf(c, x0, y0, x1, y1, w=2):
    """一片两头尖的叶子，半边亮半边暗，中脉是分界。"""
    mx, my = (x0 + x1) / 2, (y0 + y1) / 2
    dx, dy = x1 - x0, y1 - y0
    n = max(np.hypot(dx, dy), 1)
    nx, ny = -dy / n * w, dx / n * w
    whole = poly([(x0, y0), (mx + nx, my + ny), (x1, y1), (mx - nx, my - ny)])
    half = poly([(x0, y0), (mx - abs(nx), my - abs(ny)), (x1, y1)])
    c.paint(outer(whole), LEAF[0])
    c.paint(whole, LEAF[2])
    c.paint(half & whole, LEAF[3])


def draw():
    c = Card('01_magician')
    everything = rect(0, 0, W - 1, H - 1)

    # 米色素底：简洁背景，上下只差一档
    c.vgrad(everything, [IVORY[4], DUSK[4], IVORY[3]], 0, 125, soft=0.12)

    # 草坡：方块阶梯边，草面颗粒只在环境上
    hill = blocky(poly([(0, 119), (8, 116), (22, 116), (40, 122), (70, 122), (92, 119), (102, 116), (111, 113), (111, 191), (0, 191)]), 2)
    c.turf(hill, GRASS, seed=31)
    c.paint(hill & ~shift(hill, 0, 1), GRASS[4])
    c.paint(rect(26, 187, 84, 191), GRASS[1])

    # 双尾：一左一右从披风后绕出来
    tail(c, [(11, 121), (5, 114), (2, 106), (4, 99), (9, 96)], HAIR_VIOLETBLACK, 5, 4)
    tail(c, [(90, 129), (98, 123), (105, 115), (107, 106), (105, 99), (100, 97)], HAIR_VIOLETBLACK, 6, 4)

    # 披风后身：大块红色轮廓，暗红是里层
    cape = poly([(26, 40), (31, 46), (36, 50), (42, 50), (47, 52), (52, 55), (60, 55), (65, 54), (70, 57), (75, 62), (78, 70), (84, 81), (88, 86),
                 (91, 104), (93, 125), (94, 160), (95, 177), (86, 186), (26, 186), (15, 177), (12, 150), (8, 128), (10, 100), (8, 76), (15, 52), (19, 40)])
    c.paint(cape, RED[2])
    c.paint(edge(cape), RED[0])
    # 硬边暗褶：一大片压在金边底下，读出披风是翻开的
    for wedge in ([(21, 90), (43, 74), (45, 82), (42, 100), (36, 128), (30, 128), (21, 112)],
                  [(8, 128), (12, 104), (17, 96), (15, 128)],
                  [(66, 68), (84, 83), (78, 96), (76, 118), (74, 128), (70, 96)],
                  [(84, 126), (91, 112), (93, 128)],
                  [(26, 158), (34, 158), (31, 183), (24, 183), (18, 172)],
                  [(76, 158), (88, 158), (92, 174), (84, 184), (79, 180)]):
        c.paint(poly(wedge) & shrink(cape), RED[1])
    for lit in ([(21, 112), (30, 128), (21, 128)], [(17, 100), (20, 96), (20, 128), (18, 128)], [(86, 160), (91, 160), (93, 176), (89, 180)]):
        c.paint(poly(lit) & shrink(cape), RED[3])

    # 鞋：两只鞋尖略朝外，压金边
    for shoe in (poly([(36, 183), (45, 183), (45, 188), (46, 191), (35, 191), (35, 188)]), poly([(60, 183), (68, 183), (72, 188), (73, 191), (59, 191)])):
        c.shape(shoe, SLATE, base=2, shadow=1, hi=0)
    c.paint(rect(35, 190, 46, 190), GOLD[3])
    c.paint(rect(59, 190, 73, 190), GOLD[3])

    # 素白长袍：平涂 + 少量硬边褶
    robe = poly([(50, 62), (63, 62), (67, 78), (68, 90), (72, 120), (75, 157), (78, 179), (76, 184), (32, 184), (30, 179), (33, 157), (36, 128), (42, 98), (45, 88), (45, 78)])
    c.paint(robe, IVORY[3])
    c.paint(robe & (XX >= 69), IVORY[2])
    c.paint(edge(robe), IVORY[0])
    for a, b in (((47, 92), (40, 128)), ((53, 92), (50, 128)), ((47, 158), (44, 178)), ((70, 158), (72, 178))):
        c.fold([a, b], IVORY[1], shrink(robe))
    # 下摆金边
    c.paint(robe & (YY >= 179), GOLD[3])
    c.paint(robe & (YY == 179), GOLD[4])
    c.paint(robe & (YY >= 183), GOLD[1])

    # 垂带：深色长带穿过袍身，金色单边
    drape = poly([(56, 88), (65, 87), (70, 117), (61, 116), (56, 112)])
    c.shape(drape, SLATE, base=1, shadow=0, hi=0)
    c.trim([(62, 89), (65, 116)], drape)
    drape2 = poly([(55, 156), (66, 156), (66, 170), (55, 177)])
    c.shape(drape2, SLATE, base=1, shadow=0, hi=0)
    c.paint(line([(54, 156), (54, 177)]), GOLD[3])
    c.paint(line([(54, 177), (66, 171)]), GOLD[3])

    # 上身与腰带
    chest = poly([(49, 60), (64, 60), (67, 80), (45, 80)])
    c.paint(chest, IVORY[4])
    c.paint(chest & (XX >= 62), IVORY[2])
    c.fold([(56, 64), (53, 80)], IVORY[1], chest)
    c.fold([(58, 66), (62, 79)], IVORY[1], chest)
    sash = poly([(45, 80), (66, 80), (67, 87), (45, 88)])
    c.shape(sash, SLATE, base=1, shadow=1, hi=0)
    c.paint(thick(line([(43, 98), (56, 88)])), GOLD[3])
    knot = poly([(54, 84), (60, 81), (67, 83), (63, 88), (59, 91), (56, 87)])
    c.shape(knot, SLATE, base=1, shadow=0, hi=1)

    # 披风肩幔：比里层亮一档，边缘走一道宽金边
    mantle = poly([(26, 40), (31, 46), (36, 50), (42, 50), (47, 52), (52, 55), (60, 55), (65, 54), (70, 57), (75, 62), (78, 70), (84, 81), (70, 72), (58, 62), (21, 90), (26, 60)])
    c.paint(mantle, RED[3])
    c.paint(edge(mantle) & (YY < 62) & ~rect(21, 40, 27, 62), RED[1])
    for wedge in ([(30, 48), (33, 49), (43, 61), (39, 62)], [(38, 51), (42, 51), (54, 60), (50, 62)], [(32, 66), (54, 59), (56, 61), (40, 72)], [(62, 62), (67, 60), (80, 76), (72, 70)]):
        c.paint(poly(wedge) & mantle, RED[1])
    c.paint(thick(line([(58, 62), (21, 90)])), GOLD[3])
    c.paint(thick(line([(58, 62), (84, 81)])), GOLD[3])

    # 举杖大袖：象牙内衬、两道金边
    c.paint(poly([(14, 62), (21, 70), (21, 89), (8, 76)]), RED[3])
    lining = poly([(19, 40), (25, 39), (25, 50), (24, 60), (21, 70), (14, 62), (15, 52), (17, 45)])
    c.paint(lining, IVORY[4])
    c.paint(edge(lining), IVORY[0])
    c.paint(poly([(21, 47), (18, 54), (17, 62), (22, 55)]), IVORY[1])
    c.paint(thick(line([(26, 40), (25, 56), (21, 89)])), GOLD[3])
    c.paint(thick(line([(15, 52), (9, 76)])), GOLD[3])

    # 指地大袖：金边压外缘，手腕伸出的地方压成棕褐，手才跳得出来
    sleeve = poly([(84, 81), (88, 86), (93, 96), (88, 128), (72, 118), (76, 92)])
    c.paint(sleeve & (XX < 92), RED[2])
    lining_r = poly([(84, 88), (88, 89), (93, 95), (87, 97), (88, 104), (83, 121), (78, 115), (79, 100), (80, 92)])
    c.paint(lining_r, IVORY[4])
    c.paint(edge(lining_r), IVORY[0])
    c.paint(poly([(87, 95), (88, 104), (84, 112), (83, 120), (80, 113), (82, 102)]), IVORY[1])
    c.paint(thick(line([(87, 85), (82, 87), (78, 94), (76, 113)])), GOLD[3])

    # 桌：平面 + 一条厚边 + 桌裙 + 两条腿
    for x0, x1 in ((8, 15), (96, 103)):
        leg = rect(x0, 156, x1, 186)
        c.shape(leg, WOOD, base=2, shadow=2, hi=1)
    apron = rect(5, 154, 106, 158)
    c.paint(apron, WOOD[1])
    c.paint(rect(5, 154, 106, 155), WOOD[0])
    top = poly([(13, 128), (99, 128), (110, 148), (1, 148)])
    c.planks(top, TABLE, h=5, seed=34, base=2)
    c.paint(edge(top), WOOD[0])
    front = rect(1, 149, 110, 153)
    c.paint(front, WOOD[3])
    c.paint(rect(1, 153, 110, 153), WOOD[0])
    c.paint(rect(1, 149, 110, 149), WOOD[4])

    # 载页：两页摊开的羊皮纸
    page = poly([(25, 135), (77, 135), (83, 148), (18, 148)])
    c.paint(page, IVORY[4])
    c.paint(edge(page), IVORY[0])
    c.paint(line([(19, 148), (82, 148)]), IVORY[1])
    c.paint(line([(51, 137), (51, 148)]), IVORY[2])
    c.paint(line([(25, 144), (36, 144)]), SLATE[2])
    c.paint(line([(24, 146), (33, 146)]), SLATE[2])
    c.paint(line([(66, 144), (76, 144)]), SLATE[2])
    c.paint(line([(69, 146), (79, 146)]), SLATE[2])

    # 杖：细长金杖，两端圆头，整根都在桌面的暗棕上
    wand = line([(11, 138), (38, 127)], 3)
    c.paint(outer(wand), GOLD[0])
    c.paint(wand, GOLD[3])
    c.paint(line([(11, 137), (37, 126)]), GOLD[4])
    for x, y, r in ((10, 138, 2), (39, 126, 2)):
        m = ellipse(x, y, r)
        c.paint(outer(m), GOLD[0])
        c.paint(m, GOLD[3])
        c.px(x - 1, y - 1, GOLD[5])

    # 剑：长刃斜插向载页，护手和柄都比刃粗
    blade = poly([(47, 118), (52, 123), (37, 140), (34, 141), (35, 136)])
    c.paint(outer(blade), STONE[0])
    c.paint(blade, WHITE[2])
    c.paint(poly([(47, 118), (37, 139), (34, 141), (35, 136)]), WHITE[4])
    grip = line([(52, 118), (55, 115)], 3)
    c.paint(outer(grip), GOLD[0])
    c.paint(grip, GOLD[2])
    pommel = ellipse(56, 113, 2)
    c.paint(outer(pommel), GOLD[0])
    c.paint(pommel, GOLD[3])
    c.px(55, 112, GOLD[5])
    guard = line([(44, 116), (56, 124)], 3)
    c.paint(outer(guard), GOLD[0])
    c.paint(guard, GOLD[3])
    c.paint(line([(44, 115), (56, 123)]), GOLD[4])

    # 杯：高脚金杯，一格格摆，杯口、杯腰、杯脚才分明
    cup = [
        '..oooooooooo..',
        '.oDDDDDDDDDDo.',
        '.ohGGGGGGGGmo.',
        '.ohGGGGGGGGmo.',
        '.ohGGGGGGGGmo.',
        '..ohGGGGGGmo..',
        '..ohGGGGGmmo..',
        '...ohGGGmmo...',
        '....ooGmoo....',
        '.....oGmo.....',
        '.....oGmo.....',
        '....oohmoo....',
        '...ohGGGGmo...',
        '..ohGGGGGGmo..',
        '..oooooooooo..',
    ]
    c.sprite(65, 118, cup, {'o': GOLD[0], 'D': GOLD[1], 'h': GOLD[4], 'G': GOLD[3], 'm': GOLD[2]})

    # 币：大圆盘加实心五角星
    c.paint(ellipse(95, 142, 9, 1), TABLE[0])
    coin = ellipse(91.5, 132.5, 9.5)
    c.paint(outer(coin), GOLD[0])
    c.paint(coin, GOLD[3])
    c.paint(coin & ~shift(coin, 1, 1), GOLD[4])
    c.paint(coin & ~shift(coin, -1, -1), GOLD[2])
    star = []
    for i in range(10):
        a = -np.pi / 2 + i * np.pi / 5
        r = 7.5 if i % 2 == 0 else 3
        star.append((91.5 + r * np.cos(a), 133 + r * np.sin(a)))
    c.paint(poly(star), GOLD[1])

    # 手臂、杖、手：先手臂，再杖，再握拳，杖才穿在手里
    arm = poly([(19, 33), (25, 33), (25, 38), (24, 44), (21, 50), (20, 42)])
    c.shape(arm, SKIN, base=3, shadow=1, hi=0)
    staff = line([(27, 17), (23, 36)], 3)
    c.paint(outer(staff), GOLD[0])
    c.paint(staff, GOLD[3])
    c.paint(line([(26, 17), (22, 36)]), GOLD[4])
    collar = rect(24, 15, 29, 17)
    c.paint(outer(collar), GOLD[0])
    c.paint(collar, GOLD[3])
    c.paint(rect(24, 15, 29, 15), GOLD[4])
    crystal = poly([(26.5, 6), (30, 11), (26.5, 15), (23, 11)])
    c.paint(outer(crystal), TEAL[0])
    c.glow(crystal, TEAL[3])
    c.glow(poly([(26, 7), (24, 11), (26, 14)]), TEAL[4])
    c.sprite(17, 23, [
        '..xxxxxx...',
        '.xlllllllx.',
        'xlllllllssx',
        'xllllxlllsx',
        'xlllxxxxxxx',
        'xlllllllssx',
        'xlllxxxxxxx',
        'xlllllllssx',
        'xlllxxxxxxx',
        '.xlllllssx.',
        '.xllllsxx..',
        '..xxlls....',
    ], HAND_PAL)

    # 指地的手：手背朝外，三指蜷在下面，食指单独伸直
    c.sprite(85, 92, [
        '...xxxx.....',
        '..xlllsxx...',
        '.xlllllssx..',
        'xlllllllssx.',
        'xllllllllsx.',
        'xllllllllssx',
        'xllllllllssx',
        'xdlllllllssx',
        'xsxdllllllsx',
        'xdsxdlllllsx',
        '.xdsxdllllsx',
        '.xsdsxxllssx',
        '..xxxx.xllsx',
        '.......xllsx',
        '.......xllsx',
        '.......xllsx',
        '.......xllsx',
        '.......xllsx',
        '.......xllsx',
        '........xlx.',
        '.........x..',
    ], HAND_PAL)

    # 脖子与立领：象牙领心，金边
    c.paint(poly([(52, 46), (60, 46), (60, 52), (57, 59), (55, 59), (52, 52)]), SKIN[2])
    c.paint(rect(52, 49, 60, 49), SKIN[1])
    collar_l = poly([(49, 51), (55, 53), (58, 60), (54, 60), (49, 56)])
    collar_r = poly([(62, 51), (62, 56), (59, 60), (58, 58), (58, 54)])
    c.paint(collar_l, GOLD[3])
    c.paint(collar_r, GOLD[3])
    c.paint(poly([(51, 54), (54, 55), (55, 58), (51, 56)]), IVORY[4])
    c.paint(outer(collar_l) & (YY > 50) & (XX < 54), GOLD[1])

    # 后发：一圈外翘的尖绺，不是一顶圆帽
    back = poly([(49, 31), (46, 34), (44, 37), (45, 38), (41, 39), (44, 41), (40, 45), (44, 45), (43, 49), (46, 47), (46, 51), (49, 48), (50, 50),
                 (62, 50), (63, 48), (66, 51), (66, 47), (69, 49), (68, 45), (72, 45), (68, 41), (71, 39), (67, 38), (68, 37), (66, 34), (63, 31)])
    c.volume(back, HAIR_VIOLETBLACK, 2, shadow=1, hi=0)
    c.paint(edge(back), HAIR_VIOLETBLACK[0])
    for pts in ([(46, 37), (43, 41)], [(46, 42), (45, 47)], [(66, 37), (69, 41)], [(66, 42), (67, 47)]):
        c.fold(pts, HAIR_VIOLETBLACK[1], shrink(back))

    # 头：大猫耳单独画，发冠压住耳根
    head(c, 56, 27, 'front', 'L', HAIR_VIOLETBLACK, BLUE_IRIS, eyes='open', mouth='smile', ears=False)
    ear(c, 49, 36, 11, 9, -3, HAIR_VIOLETBLACK)
    ear(c, 63, 36, 11, 9, 3, HAIR_VIOLETBLACK)
    c.pxs([(47, 34), (48, 33), (48, 35), (49, 34)], IVORY[3])
    c.pxs([(65, 34), (64, 33), (64, 35), (63, 34)], IVORY[3])
    cap = poly([(49, 37), (50, 32), (53, 29), (56, 28), (59, 29), (62, 32), (63, 37), (61, 35), (58, 37), (56, 35), (53, 37), (51, 35)])
    c.volume(cap, HAIR_VIOLETBLACK, 2, shadow=1, hi=0)
    c.paint(edge(cap) & (YY < 34), HAIR_VIOLETBLACK[0])
    c.pxs([(53, 31), (54, 31), (55, 31), (54, 32), (55, 32), (58, 32), (59, 32)], HAIR_VIOLETBLACK[3])
    c.pxs([(54, 30), (55, 30)], HAIR_VIOLETBLACK[4])
    # 刘海一绺垂到两眼之间：只走暗线，不加粗描边，发冠才不会多出一个黑方块
    c.paint(line([(57, 34), (56, 37), (55, 40)]), HAIR_VIOLETBLACK[1])
    c.pxs([(56, 38), (56, 39)], HAIR_VIOLETBLACK[2])
    gem(c, 58, 60, 4)

    # 术式线路：杖—节点—节点—载页三节点—指地节点，把上下两手连起来
    c.glow_line([(29, 138), (29, 41)], TEAL[3])
    c.glow_line([(29, 36), (29, 28), (34, 23)], TEAL[3])
    c.glow_line([(33, 19), (30, 16)], TEAL[3])
    c.glow_line([(74, 138), (92, 118)], TEAL[3])
    c.glow_line([(31, 140), (70, 140)], GOLD[3])
    c.node(29, 38, 2)
    c.node(34, 21, 2)
    c.node(29, 140, 2)
    c.node(51, 140, 2)
    c.node(72, 140, 2)
    c.node(94, 116, 2)

    # 无限符号与放射短线
    inf = c.infinity(56, 15, 19, 7, w=3)
    c.paint(outer(inf), GOLD[1])
    c.glow(inf, GOLD[3])
    c.glow(c.infinity(56, 15, 19, 7, w=1), GOLD[4])
    for a, b in (((56, 0), (56, 4)), ((45, 2), (48, 5)), ((67, 2), (64, 5)), ((33, 15), (35, 15)), ((77, 15), (79, 15)), ((39, 26), (42, 23)), ((73, 26), (70, 23))):
        c.glow(line([a, b]), GOLD[3])

    # 两角：白花与玫瑰，只占角落
    leaf(c, 8, 186, 1, 178, 2)
    leaf(c, 17, 180, 21, 170, 2)
    leaf(c, 3, 190, 12, 188, 2)
    leaf(c, 103, 186, 110, 178, 2)
    leaf(c, 94, 180, 90, 170, 2)
    leaf(c, 108, 190, 99, 188, 2)
    white_flower(c, 11, 180, 4, outline=IVORY[0])
    white_flower(c, 100, 180, 4, outline=IVORY[0])
    rose(c, 18, 188, 3)
    rose(c, 93, 188, 3)

    return c
