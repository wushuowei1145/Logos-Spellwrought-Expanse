"""11 正义：银发猫耳的女裁判正面端坐、睁眼直视；画面左侧一柄竖直双刃剑，右侧水平天平两盘等高、各一枚蓝青菱印。"""
from figures import *

# 帷幕的酒红：比红衣暗、偏紫，和衣服分得开
WINE = ['#2c1024', '#471a34', '#622744', '#7c3b58', '#9c5a76']
# 石柱用的低对比石砖：缝只比砖面暗一档，柱子才不抢人物
PALE_STONE = [STONE[2], STONE[3], STONE[3], STONE[3], STONE[4]]

AX = 55  # 人物的镜像轴。L 号正面头宽 19，中线落在整数列上


def both(m):
    """人物左右成对的形状，只写左半。"""
    return m | mirror(m, AX)


def chain(c, x0, y0, x1, y1):
    """吊链：一格亮一格暗，读成链节，不是实线。"""
    n = max(abs(x1 - x0), abs(y1 - y0))
    for i in range(n + 1):
        x, y = round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n)
        c.px(x, y, GOLD[4] if i % 2 == 0 else GOLD[2])


def diamond(c, x, y, rx, ry):
    """竖长的金菱：护手、配重、横梁中枢都用它。"""
    m = np.abs(XX - x) / (rx + 0.5) + np.abs(YY - y) / (ry + 0.5) <= 1
    c.paint(outer(m), GOLD[1])
    c.paint(m, GOLD[3])
    c.paint(m & (XX > x), GOLD[2])
    c.paint(m & (XX < x) & (YY < y), GOLD[4])
    return m


def scale(c, cx=88, y=66):
    """水平天平：提环、横梁、两股吊链和盘底各自分开，两盘严格等高。"""
    # 提环与短柄
    r = ring(cx, y - 7, 3, 3)
    c.paint(outer(r), GOLD[1])
    c.paint(r, GOLD[3])
    c.paint(r & (XX + YY < cx + y - 7), GOLD[4])
    c.paint(rect(cx, y - 4, cx, y - 3), GOLD[3])
    for sx in (cx - 12, cx + 12):
        # 吊链：从横梁两端各分两股
        chain(c, sx - 1, y + 3, sx - 6, y + 18)
        chain(c, sx + 1, y + 3, sx + 6, y + 18)
        # 盘底：一只浅碗。盘沿比吊链低一格，吊链和盘底之间读得出分界
        pan = poly([(sx - 7, y + 19), (sx + 7, y + 19), (sx + 5, y + 21), (sx + 3, y + 22), (sx - 3, y + 22), (sx - 5, y + 21)])
        c.paint(outer(pan), GOLD[1])
        c.paint(pan, GOLD[3])
        c.paint(pan & (YY == y + 19), GOLD[4])
        c.paint(pan & (YY == y + 22), GOLD[2])
        # 每盘一枚相同的蓝青菱印，悬在两股吊链之间
        c.node(sx, y + 14, 3)
    # 横梁
    beam = rect(cx - 12, y - 1, cx + 12, y)
    c.paint(outer(beam), GOLD[1])
    c.paint(beam, GOLD[3])
    c.paint(rect(cx - 12, y - 1, cx + 12, y - 1), GOLD[4])
    for sx in (cx - 12, cx + 12):
        ball = ellipse(sx, y, 2)
        c.paint(outer(ball), GOLD[1])
        c.paint(ball, GOLD[3])
        c.px(sx - 1, y - 1, GOLD[5])
    diamond(c, cx, y, 2, 4)


def sword(c, x=20):
    """竖直双刃剑：剑身笔直，中脊一线，护手两端带小菱。"""
    blade = poly([(x, 9), (x + 2, 14), (x + 2, 64), (x - 2, 64), (x - 2, 14)])
    c.paint(outer(blade), WINE[0])
    c.paint(blade, WHITE[3])
    c.paint(blade & (XX < x), WHITE[4])
    c.paint(blade & (XX > x), WHITE[1])
    # 剑脊一线发光：夜里只看得见这道笔直的裁断
    c.glow(line([(x, 11), (x, 64)]), GOLD[5])
    # 护手
    guard = rect(x - 8, 66, x + 8, 67)
    c.paint(outer(guard), GOLD[1])
    c.paint(guard, GOLD[3])
    c.paint(rect(x - 8, 66, x + 8, 66), GOLD[4])
    diamond(c, x - 8, 67, 2, 2)
    diamond(c, x + 8, 67, 2, 2)
    diamond(c, x, 67, 3, 4)
    # 剑柄与配重
    c.paint(rect(x - 1, 72, x + 1, 83), LEATHER[1])
    c.paint(rect(x + 1, 72, x + 1, 83), LEATHER[0])
    diamond(c, x, 85, 3, 3)


def draw():
    c = Card('11_justice')
    everything = rect(0, 0, W - 1, H - 1)

    # 帷幕铺满，上方挽起露出一弧天空
    c.paint(everything, WINE[2])
    sky = ellipse(55.5, 0, 38, 9)
    c.paint(sky, DAY[2])
    c.paint(sky & (YY <= 3), DAY[1])
    c.paint(outer(sky), WINE[1])
    # 帷幕大褶：从柱头向中央挽起的大块硬边，左右成对
    for pts, col in (([(15, 8), (30, 16), (48, 20), (32, 24), (15, 17)], WINE[1]),
                     ([(15, 28), (28, 44), (38, 64), (28, 64), (15, 44)], WINE[1]),
                     ([(15, 58), (28, 72), (15, 76)], WINE[1]),
                     ([(15, 4), (30, 13), (40, 15), (28, 16)], WINE[3])):
        m = poly(pts)
        c.paint(m | mirror(m), col)

    # 双柱：低对比石砖 + 一条受光面、一条背光面。左右成对
    for x0 in (1, 98):
        col = rect(x0, 0, x0 + 12, 175)
        c.stone_bricks(col, PALE_STONE, bw=13, bh=10, ox=x0, oy=9, base=2)
        c.paint(rect(x0 + 10, 9, x0 + 11, 175), STONE[2])
        c.paint(rect(x0 + 12, 9, x0 + 12, 175), STONE[1])
        c.paint(rect(x0, 9, x0, 175), STONE[4])
        cap = rect(x0 - 1, 3, x0 + 13, 8)
        c.paint(cap, STONE[3])
        c.paint(rect(x0 - 1, 3, x0 + 13, 4), STONE[4])
        c.paint(rect(x0 - 1, 8, x0 + 13, 8), STONE[1])
        c.paint(rect(x0, 0, x0 + 12, 2), STONE[2])

    # 披风后片：左侧垂到王座外，右侧收在袖后
    cape = poly([(55, 58), (40, 60), (32, 64), (28, 72), (26, 82), (14, 100), (8, 112), (5, 140), (12, 162), (20, 130), (30, 122), (80, 122), (92, 122), (90, 100), (88, 76), (84, 70), (78, 64), (70, 60)])
    c.paint(cape, NAVY[2])
    c.paint(edge(cape), NAVY[0])
    c.paint(poly([(24, 112), (33, 100), (40, 85), (45, 90), (40, 104), (32, 122), (20, 122)]) & cape, NAVY[1])
    c.paint(poly([(76, 100), (84, 104), (90, 122), (74, 122)]) & cape, NAVY[1])

    # 石王座扶手：方块语言，顶面亮、正面中、立柱暗
    for flip_side in (False, True):
        arm = rect(9, 121, 30, 131)
        leg = rect(12, 132, 27, 174)
        top = rect(9, 121, 30, 124)
        lip = rect(9, 131, 30, 132)
        rim = rect(9, 121, 30, 121)
        if flip_side:
            arm, leg, top, lip, rim = mirror(arm), mirror(leg), mirror(top), mirror(lip), mirror(rim)
        c.paint(leg, STONE[2])
        c.paint(leg & (YY <= 135), STONE[1])
        c.paint(arm, STONE[2])
        c.paint(top, STONE[3])
        c.paint(rim, STONE[4])
        c.paint(lip, STONE[0])

    # 台阶：顶面亮，立面石砖
    c.paint(rect(4, 175, 107, 180), STONE[3])
    c.paint(rect(4, 175, 107, 175), STONE[4])
    c.stone_bricks(rect(3, 181, 108, 187), PALE_STONE, bw=17, bh=7, oy=181, base=1)
    c.paint(rect(3, 181, 108, 181), STONE[1])
    c.paint(rect(0, 188, 111, 191), STONE[3])
    c.paint(rect(0, 188, 111, 188), STONE[4])

    # 尾巴：从披风后探到右侧柱前
    tl = taper([(90, 112), (97, 116), (102, 125), (102, 134), (98, 141), (96, 146)], 3, 5)
    c.paint(outer(tl), WINE[0])
    c.paint(tl, HAIR_SILVER[3])
    c.paint(tl & ~shift(tl, -1, -1), HAIR_SILVER[2])
    c.paint(tl & ~shift(tl, 1, 1), HAIR_SILVER[4])

    # 红裙：大钟形，膝头微鼓，裙摆落到台阶上
    robe = both(poly([(55, 104), (42, 102), (34, 108), (28, 116), (27, 128), (29, 136), (24, 156), (19, 168), (14, 177), (55, 179)]))
    c.shape(robe, RED, base=2, shadow=1, hi=0)
    c.paint(robe & ~shift(robe, 1, 1), RED[3])
    # 硬边大褶：从膝头垂到裙摆的长暗楔，每边两道
    for pts in ([(40, 112), (34, 118), (36, 122)],
                [(38, 124), (34, 150), (28, 177), (37, 177), (39, 150)],
                [(30, 140), (24, 165), (18, 177), (26, 177), (29, 160)]):
        c.paint(both(poly(pts)) & robe, RED[1])
    c.paint(both(line([(30, 115), (36, 108), (41, 106)])) & robe, RED[3])

    # 脚
    for flip_side in (False, True):
        foot = rect(45, 169, 53, 175)
        shoe = poly([(44, 175), (53, 175), (53, 180), (43, 180)])
        if flip_side:
            foot, shoe = mirror(foot, AX), mirror(shoe, AX)
        c.paint(foot, SKIN[3])
        c.paint(foot & (YY <= 172), SKIN[1])
        c.paint(shoe, NAVY[1])
        c.paint(shoe & (YY == 175), NAVY[3])
        c.paint(shoe & (YY == 180), NAVY[0])
    c.paint(rect(54, 169, 56, 180), RED[0])

    # 中央象牙衣摆：拱顶、三指宽金边、V 形暗褶
    panel = both(poly([(55, 104), (49, 104), (45, 107), (43, 112), (42, 170), (55, 171)]))
    c.paint(panel, IVORY[3])
    inner = both(poly([(55, 107), (49, 107), (47, 109), (46, 114), (45, 169), (55, 170)]))
    gold_band = panel & ~inner
    c.paint(gold_band, GOLD[3])
    c.paint(gold_band & (XX + YY * 0 < 44), GOLD[2])
    c.paint(mirror(gold_band & (XX < 44), AX), GOLD[2])
    c.paint(rect(42, 170, 68, 171), IVORY[2])
    c.paint(both(poly([(48, 118), (49, 118), (55, 146), (55, 152)])), IVORY[2])

    # 上身：红衣 + 中央象牙条，上下两段都夹着金边
    bodice = both(poly([(55, 58), (46, 60), (44, 74), (44, 88), (42, 104), (55, 106)]))
    c.shape(bodice, RED, base=2, shadow=1, hi=0)
    strip_top = both(poly([(55, 66), (50, 88), (55, 88)]))
    c.paint(strip_top, IVORY[3])
    c.paint(both(line([(54, 66), (50, 88)])), GOLD[3])
    strip_low = both(poly([(55, 92), (50, 92), (46, 105), (55, 106)]))
    c.paint(strip_low, IVORY[3])
    c.paint(both(line([(50, 92), (46, 105)])), GOLD[3])
    # 拱顶上的一条衣影
    c.paint(both(curve([(46, 104), (50, 106), (55, 106)])), STONE[3])

    # 腰带
    belt = rect(43, 88, 67, 92)
    c.paint(belt, GOLD[3])
    c.paint(rect(43, 88, 67, 88), GOLD[4])
    c.paint(rect(43, 92, 67, 92), GOLD[1])
    c.paint(rect(43, 88, 43, 92) | rect(67, 88, 67, 92), GOLD[1])

    # 持剑的宽袖：红面向外，象牙衬里只翻出一道窄边，内面压暗，免得读成剑身的延长
    sleeve = poly([(17, 80), (26, 78), (34, 83), (39, 91), (36, 95), (29, 105), (25, 112), (21, 116), (17, 110), (14, 97)])
    c.shape(sleeve, RED, base=2, shadow=1, hi=0)
    c.paint(sleeve & ~shift(sleeve, 1, 1), RED[3])
    c.paint(poly([(33, 84), (38, 91), (36, 94), (33, 90)]), RED[1])
    c.paint(poly([(29, 98), (33, 92), (30, 104)]), RED[1])
    cuff = poly([(25, 79), (28, 85), (28, 95), (26, 106), (21, 116), (19, 106), (19, 90), (20, 84)])
    c.paint(cuff, IVORY[4])
    c.paint(cuff & (XX < 21), IVORY[3])
    c.paint(poly([(22, 88), (25, 88), (24, 96), (22, 106), (22, 96)]), RED[1])
    wrist = poly([(21, 78), (25, 78), (26, 84), (23, 87), (21, 85)])
    c.paint(wrist, SKIN[2])
    c.paint(wrist & (XX >= 25), SKIN[1])

    # 披肩前片：V 字压在肩上，金边一道收到胸口。右片包住上臂，下摆折成一个尖角
    cape_l = poly([(51, 62), (44, 59), (34, 63), (29, 72), (26, 82), (32, 84), (39, 81), (46, 73)])
    cape_r = poly([(59, 62), (66, 59), (76, 63), (84, 70), (87, 75), (85, 97), (83, 104), (72, 92), (65, 74)])
    c.paint(cape_l, NAVY[2])
    c.paint(cape_r, NAVY[2])
    c.paint(poly([(30, 76), (38, 72), (44, 70), (38, 80), (30, 83)]), NAVY[1])
    c.paint(poly([(70, 78), (80, 84), (83, 96), (76, 92)]), NAVY[1])
    c.paint(line([(52, 65), (45, 74), (38, 82), (28, 84)], 3), GOLD[3])
    c.paint(line([(58, 65), (65, 75), (72, 92), (82, 103)], 3), GOLD[3])
    c.paint(line([(83, 103), (85, 96), (87, 74)], 3), GOLD[3])
    # 肩头两片金饰
    c.paint(both(poly([(42, 59), (46, 57), (47, 60), (44, 61)])), GOLD[3])

    # 提秤的前臂：从披肩的尖角里伸出，上窄下收，给提环留出空位
    forearm = poly([(83, 56), (86, 56), (86, 72), (84, 80), (83, 72)])
    c.paint(forearm, SKIN[3])
    c.paint(forearm & (XX >= 85), SKIN[2])
    c.paint(outer(forearm) & cape_r, NAVY[0])
    c.paint(outer(forearm) & ~cape_r & (YY < 66), WINE[0])

    # 长发两片落在肩前，末端都收成尖，不粘成板
    hs = HAIR_SILVER
    lock_a = taper([(44, 38), (40, 48), (37, 60), (33, 70), (29, 79)], 7, 1)
    lock_b = taper([(46, 42), (45, 54), (42, 64), (43, 70), (47, 77)], 8, 1)
    hair_l = lock_a | lock_b | poly([(47, 34), (41, 42), (39, 52), (47, 52)])
    hair_all = both(hair_l)
    c.paint(hair_all, hs[3])
    c.paint(hair_all & ~shift(hair_all, 1, 1), hs[4])
    c.paint(hair_all & ~shift(hair_all, -1, -1), hs[2])
    # 几道发丝：只用中间调，不用深色
    for pts in ([(42, 46), (39, 58), (34, 70)], [(45, 48), (43, 60), (43, 68)]):
        m = curve(pts)
        c.paint(both(m) & hair_all, hs[2])

    # 脖子、高领、金项圈
    c.paint(rect(52, 49, 58, 56), SKIN[2])
    c.paint(rect(52, 49, 58, 52), SKIN[1])
    collar = both(poly([(55, 55), (50, 55), (48, 57), (47, 62), (55, 64)]))
    c.paint(collar, RED[2])
    c.paint(both(rect(50, 55, 55, 56)), IVORY[3])
    c.paint(rect(54, 57, 56, 62), IVORY[3])
    c.paint(both(curve([(46, 60), (50, 63), (55, 64)])), GOLD[3])

    # 头：正面、睁眼、抿嘴。发色的 2 档提亮，和两侧长发同一明度
    soft = [hs[1], hs[2], hs[3], hs[3], hs[4]]
    head(c, AX, 30, 'front', 'L', soft, GREY_IRIS, eyes='open', mouth='line', ears=False, brow=hs[2], lash='#3a3850')
    c.paint(rect(52, 51, 58, 51), SKIN[1])
    # 脸两侧的短绺贴着外缘，把脸框住又不吃脸
    for pts in ([(46, 37), (44, 44), (44, 52)], [(64, 37), (66, 44), (66, 52)]):
        m = taper(pts, 3, 1)
        c.paint(m, hs[3])
        c.paint(m & ~shift(m, 0, -1), hs[4])

    # 大猫耳从冠后伸出：耳尖朝外，内耳粉色偏外侧
    ear_l = poly([(40, 21), (42, 33), (49, 27)])
    c.paint(outer(both(ear_l)), WINE[0])
    c.paint(both(ear_l), hs[3])
    c.paint(ear_l & (XX + YY * 0 > 44) & (YY < 30), hs[4])
    c.paint(both(poly([(41, 24), (43, 32), (46, 29)])), PINK[2])
    c.paint(both(poly([(42, 28), (43, 32), (45, 30)])), PINK[1])

    # 低冠：一道略垂两端的金圈，四个城垛，中央一枚竖菱
    crown_half = [
        '.....GGG...G',
        '.....GGG..GG',
        'GGG..GGG.GGG',
        'GGGGGGGGGGGG',
        'GGGGGGGGGGGG',
        'GGGGGGGGGGGG',
        'dGGGGGGddddd',
        '.ddddddd....',
    ]
    rows = [r + r[-2::-1] for r in crown_half]
    x0, y0 = AX - 11, 28
    crown = c.sprite_mask(x0, y0, rows)
    c.paint(outer(crown), GOLD[1])
    c.sprite(x0, y0, rows, {'G': GOLD[3], 'd': GOLD[1]})
    c.paint(crown & (YY == y0 + 3), GOLD[4])
    c.paint(rect(x0 + 5, y0, x0 + 7, y0) | rect(x0 + 15, y0, x0 + 17, y0), GOLD[4])
    c.paint(crown & (XX == x0 + 22) & (YY >= y0 + 2), GOLD[2])
    gem_rows = ['..t..', '.tWt.', 'tWWTt', 'tWTTt', '.tTt.', '..t..']
    c.paint(outer(c.sprite_mask(AX - 2, y0, gem_rows)), TEAL[0])
    c.sprite(AX - 2, y0, gem_rows, {'t': TEAL[2], 'W': TEAL[4], 'T': TEAL[3]}, glow='tWT')

    # 胸前菱扣
    gem(c, AX, 65, 3)

    # 双刃剑和握剑手：四指横过剑柄，拇指压在上面
    sword(c, 20)
    grip = [
        '..llllll.',
        '.lllllllss',
        'lxxxxxxlss',
        'lllllllls',
        'lxxxxxxls',
        'lllllllls',
        'lxxxxxxss',
        '.lllllsd.',
        '..sssdd..',
    ]
    c.sprite(16, 71, grip, {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[1]})

    # 天平和提秤的手：手在环上方，指尖捏住提环
    scale(c)
    pinch = [
        '....lllll.',
        '..lllllll.',
        '.llllllllx',
        '.lllxlxlxs',
        '.lllxlxlxs',
        '.lllxlxlxs',
        '.slllslxsd',
        '..ss.ll.d.',
        '..ss.ll...',
    ]
    c.paint(outer(c.sprite_mask(82, 49, pinch)) & (YY < 58), WINE[0])
    c.sprite(82, 49, pinch, {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[1]})

    return c
