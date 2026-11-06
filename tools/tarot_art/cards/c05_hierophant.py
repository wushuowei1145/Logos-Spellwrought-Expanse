"""5 教皇：银发猫耳的传授者端坐石柱之间，一手举双指、一手持三横杖；术式书摊在师生之间，交叉双钥匙在地，一人类一灵猫两名学徒背对画面仰听。"""
from figures import *

CX = 55.5  # 这张牌严格左右对称，中轴落在两列像素之间

# 本牌的石材：缝不用全黑，柱和墙才不会被砖缝切碎
WALL = ['#c4b692', '#ddd0aa', '#e4d8b5', '#e9dfbe', '#efe6c9']
PILLAR = ['#71747c', '#8d9096', '#9fa2a6', '#aeb0b3', '#c6c7c6']
SKIN_PAL = {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0]}


def mirror_f(flip):
    return (lambda pts: flip_pts(pts)) if flip else (lambda pts: pts)


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
    """菱形蒙版，cx 用半整数时严格对称。"""
    return (np.abs(XX - cx) + np.abs(YY - cy)) <= r


def node_even(c, cx, cy, r=3):
    """偶数宽的蓝青节点：亮芯、暗外缘都和 Card.node 一个语言。"""
    m = diamond(cx, cy, r + 0.5)
    c.paint(outer(m), TEAL[0])
    c.glow(m, TEAL[2])
    c.glow(diamond(cx, cy, r - 0.5), TEAL[3])
    c.glow(diamond(cx, cy, max(r - 1.5, 0.5)), TEAL[4])


# 圣带和披肩上的四芒星：竖长横短，中心开孔露出底色
STAR_L = [
    '....k....',
    '...kgk...',
    '...kgk...',
    '...kgk...',
    '..kgggk..',
    'kkggkggkk',
    'kgggkgggk',
    'kkggkggkk',
    '..kgggk..',
    '...kgk...',
    '...kgk...',
    '...kgk...',
    '....k....',
]
STAR_S = [
    '...k...',
    '..kgk..',
    '..kgk..',
    '.kgggk.',
    'kggkggk',
    '.kgggk.',
    '..kgk..',
    '..kgk..',
    '...k...',
]


def star_badge(c, x, y, rows, ramp=GOLD):
    width, height = len(rows[0]), len(rows)
    c.sprite(x - width // 2, y - height // 2, rows, {'k': ramp[1], 'g': ramp[3]})


def setting(c):
    # 墙：沙色大块砖，和原版的沙岩一样低对比
    c.stone_bricks(rect(0, 0, W - 1, H - 1), WALL, bw=18, bh=9, seed=31, base=2)

    # 深蓝拱龛：传授者的背光。拱边压一条金线
    niche = ellipse(CX, 30, 41, 36) | rect(15, 30, 96, 130)
    c.paint(grow(niche, 1), GOLD[2])
    c.paint(niche, NAVY[1])

    # 双柱：柱头、柱身都是石砖块，x 与 111-x 成对
    for flip in (False, True):
        fx = (lambda x: 111 - x) if flip else (lambda x: x)

        def box(a, y0, b, y1):
            lo, hi = sorted((fx(a), fx(b)))
            return rect(lo, y0, hi, y1)

        shaft = box(3, 14, 14, 96)
        c.stone_bricks(shaft, PILLAR, bw=12, bh=6, ox=3 if not flip else 97, seed=33 + flip, base=2)
        c.paint(box(3, 14, 3, 96), PILLAR[4])
        c.paint(box(14, 14, 14, 96), STONE[1])
        c.paint(box(15, 14, 15, 96), NAVY[0])
        for y0, y1, a, b in ((0, 3, 0, 18), (4, 7, 3, 15), (8, 13, 1, 17)):
            slab = box(a, y0, b, y1)
            c.paint(slab, PILLAR[3])
            c.paint(box(a, y0, b, y0), PILLAR[4])
            c.paint(box(a, y1, b, y1), STONE[1])
            c.paint(box(b, y0, b, y1), STONE[2])
            c.paint(box(a, y0, a, y1), PILLAR[4])


def robe_back(c):
    # 红斗篷的后摆：从袖下向两侧扫开
    for flip in (False, True):
        m = poly(mirror_f(flip)([(26, 84), (12, 96), (0, 113), (0, 130), (9, 124), (28, 104)]))
        c.shape(m, RED, base=2, shadow=2, hi=0)

    # 跪坐的长袍下半：铺满师生之间
    lower = poly([(26, 92), (85, 92), (92, 104), (108, 128), (108, 158), (3, 158), (3, 128), (19, 104)])
    c.paint(lower, IVORY[4])
    c.paint(edge(lower), IVORY[1])
    # 袍下摆的竖褶
    for pts in (((35, 138), (40, 138), (34, 158)), ((61, 138), (68, 138), (70, 158)), ((10, 128), (16, 112), (14, 130))):
        c.paint(poly(pts), IVORY[2])
        c.paint(poly(flip_pts(pts)), IVORY[2])
    # 膝头投影
    c.paint(poly([(46, 92), (65, 92), (55.5, 101)]), IVORY[2])

    # 膝上的红色下摆和金边
    for flip in (False, True):
        f = mirror_f(flip)
        m = poly(f([(27, 91), (40, 91), (37, 112), (24, 112)]))
        c.shape(m, RED, base=2, shadow=2, hi=0)
        c.paint(line(f([(41, 91), (37, 112)]), 3), GOLD[3])
        c.paint(line(f([(39, 91), (35, 112)])), GOLD[1])


def sleeves(c):
    for flip in (False, True):
        f = mirror_f(flip)
        out = poly(f([(19, 49), (33, 53), (36, 76), (31, 91), (13, 99), (9, 93), (2, 77), (9, 61)]))
        c.shape(out, IVORY, base=4, shadow=0, hi=0, line_color=IVORY[0])
        # 袖子里的大褶：只留两块硬边暗面
        c.paint(poly(f([(27, 80), (34, 84), (31, 91), (20, 95)])), IVORY[2])
        c.paint(poly(f([(28, 58), (35, 66), (35, 74), (29, 64)])), IVORY[2])
        c.paint(line(f([(27, 52), (27, 69), (20, 78), (14, 92)])), IVORY[1])
        # 袖缘的金带
        band = line(f([(14, 56), (11, 67), (10, 80), (13, 97)]), 3) & shrink(out)
        c.paint(band, GOLD[3])
        c.paint(line(f([(16, 57), (13, 67), (12, 80), (15, 96)])) & band, GOLD[2])


def torso(c):
    # 中央白色内袍
    tunic = poly([(47, 44), (64, 44), (65, 92), (46, 92)])
    c.shape(tunic, IVORY, base=4, shadow=0, hi=0, line_color=IVORY[1])
    c.paint(poly([(49, 64), (51, 64), (50, 78)]), IVORY[2])
    c.paint(poly([(60, 64), (62, 64), (61, 78)]), IVORY[2])
    c.paint(poly([(51, 86), (60, 86), (64, 92), (47, 92)]), IVORY[3])
    # 腰带：收成一道金色束结
    belt = poly([(48, 79), (63, 79), (59, 82), (63, 85), (48, 85), (52, 82)])
    c.paint(belt, GOLD[3])
    c.paint(belt & (YY >= 83), GOLD[2])
    c.paint(edge(belt), GOLD[1])
    c.paint(poly([(51, 76), (60, 76), (58, 78), (53, 78)]), IVORY[1])

    # 红色祭披左右两片
    for flip in (False, True):
        f = mirror_f(flip)
        panel = poly(f([(43, 44), (47, 44), (47, 88), (34, 92), (28, 92), (32, 72), (28, 58), (28, 52)]))
        c.shape(panel, RED, base=2, shadow=2, hi=0)
        c.paint(band([(46, 45), (46, 88)], flip), GOLD[3])
        c.paint(band([(33, 50), (35, 58), (36, 72), (33, 86), (30, 92)], flip), GOLD[3])
        c.paint(band([(35, 91), (46, 87)], flip), GOLD[3])
        star_badge(c, 111 - 41 if flip else 41, 68, STAR_L)


def collar(c):
    # 胸前金色领饰：V 形收到中线
    chest = poly([(47, 47), (64, 47), (64, 57), (55.5, 65), (47, 57)])
    c.paint(outer(chest), GOLD[1])
    c.paint(chest, GOLD[3])
    c.paint(line([(48, 58), (55, 64), (56, 64), (63, 58)]), GOLD[2])
    # 白色底领和立领金边
    for flip in (False, True):
        f = mirror_f(flip)
        under = poly(f([(46, 43), (55, 48), (53, 53), (46, 53), (43, 47)]))
        c.paint(under, IVORY[4])
        c.paint(edge(under), IVORY[1])
        c.paint(poly(f([(45, 48), (53, 53), (46, 53)])), IVORY[2])
        stand = poly(f([(47, 39), (55, 43), (55, 48), (47, 44)]))
        c.paint(stand, GOLD[3])
        c.paint(edge(stand), GOLD[1])
    c.paint(line([(55, 44), (55, 49)]) | line([(56, 44), (56, 49)]), GOLD[1])
    # 胸前菱扣
    node_even(c, CX, 56, 3)
    c.paint(outer(grow(diamond(CX, 56, 3.5))), GOLD[4])


# 左半张头（x=41..55），镜像得到右半。0–4 发色，l/s/d/x 肤色
HEAD_HALF = [
    '.......02233331',
    '......02233331l',
    '.....02233431ll',
    '....02233431lll',
    '...02233331slll',
    '..0223321slllll',
    '.00223210slllll',
    '0222232slllllll',
    '.002221slllllll',
    '..02210slllllll',
    '.022110slllllll',
    '0221100slllllll',
    '.021100slllllll',
    '.021100slllllll',
    '..02110slllllll',
    '..021100sllllll',
    '..021100sllllll',
    '...021100slllll',
    '...021100slllll',
    '....0110.xsllll',
    '.....00...xslll',
    '...........xsll',
    '............xxs',
]


def face(c):
    # 猫耳在冠两侧向外张开：外缘银发，内耳粉，耳根一撮白毛
    for flip in (False, True):
        f = mirror_f(flip)
        ear_m = poly(f([(38, 12), (48, 17), (49, 24), (44, 27)]))
        c.paint(outer(ear_m), HAIR_SILVER[0])
        c.paint(ear_m, PINK[2])
        c.paint(poly(f([(39, 13), (48, 17), (48, 19)])), HAIR_SILVER[2])
        c.paint(poly(f([(41, 17), (43, 18), (44, 25)])), PINK[1])
        c.paint(poly(f([(43, 20), (48, 20), (45, 26)])), WHITE[3])

    pal = {'0': HAIR_SILVER[0], '1': HAIR_SILVER[1], '2': HAIR_SILVER[2], '3': HAIR_SILVER[3], '4': HAIR_SILVER[4], **SKIN_PAL}
    shade = {'2': '1', '3': '2', '4': '3'}
    rows = [r + ''.join(shade.get(ch, ch) for ch in r[::-1]) for r in HEAD_HALF]
    c.sprite(41, 21, rows, pal)

    # 下颌右侧压一条暗面，光从左上来
    c.pxs([(62, 34), (62, 35), (61, 36), (61, 37), (60, 38), (60, 39), (59, 40), (58, 41)], SKIN[2])

    # 五官
    eye = {'k': '#2c2838', 'w': '#f3f2e8', 'e': '#6d66a8', 'p': '#2f2552', 'h': '#ffffff'}
    c.sprite(48, 29, ['.kkkkk', 'kwepew', '..eee.'], eye)
    c.sprite(58, 29, ['kkkkk.', 'wepewk', '.eee..'], eye)
    c.paint(line([(49, 27), (53, 26)]), HAIR_SILVER[1])
    c.paint(line([(58, 26), (62, 27)]), HAIR_SILVER[1])
    c.pxs([(55, 35), (56, 35)], SKIN[1])
    c.paint(rect(54, 38, 57, 38), SKIN[0])
    c.pxs([(53, 37), (58, 37)], SKIN[1])
    c.pxs([(55, 40), (56, 40)], SKIN[2])

    # 右肩后低束的马尾
    pony = taper([(68, 40), (70, 45), (72, 49), (71, 54)], 5, 1)
    c.volume(pony, HAIR_SILVER, 3, shadow=1, hi=0)
    c.paint(edge(pony), HAIR_SILVER[0])
    tie = rect(65, 38, 69, 40)
    c.paint(tie, GOLD[3])
    c.paint(edge(tie), GOLD[1])


# 三层冠：每一层都有尖角、金边和象牙带，向上收窄。左半（x=42..55），镜像得到右半
CROWN_HALF = [
    '.............h',
    '............hh',
    '.............h',
    '.......k....kg',
    '.......gk...kg',
    '.......ggkkkgg',
    '........gwwwgg',
    '........gwwcgg',
    '....k...kkkkkk',
    '....gk..kggggg',
    '....ggkkkggggg',
    '.....gwwwwwwgg',
    '.....gwwwwwcgg',
    '.k...kkkkkkkkk',
    '.gk..kgggggggg',
    '.ggkkkgggggggg',
    '..gwwwwwwwwggg',
    '..gwwwwwwwwggg',
    '..gcccccccgggg',
    '..dddddddddddd',
    '...kkkkkkkkkkk',
]


def crown(c):
    pal = {'k': GOLD[1], 'g': GOLD[3], 'h': GOLD[4], 'd': GOLD[2], 'w': IVORY[4], 'c': IVORY[2]}
    shade = {'g': 'g', 'h': 'g'}
    rows = [r + ''.join(shade.get(ch, ch) for ch in r[::-1]) for r in CROWN_HALF]
    c.sprite(42, 0, rows, pal)
    # 冠上菱印
    node_even(c, CX, 17, 2)


def staff(c):
    # 三横杖：杖身从书后穿出，三道横臂自上而下加宽，一眼能数清
    shaft = rect(87, 19, 89, 114)
    bars = [(24, 81, 95), (29, 79, 97), (34, 77, 99)]
    knob = rect(86, 18, 90, 20)
    collar_ring = rect(86, 40, 90, 42)
    whole = shaft | knob | collar_ring
    for y, a, b in bars:
        whole |= rect(a, y, b, y + 2)
    c.paint(outer(whole), NAVY[0])
    c.paint(whole, GOLD[3])
    c.paint(rect(87, 19, 87, 114), GOLD[4])
    c.paint(rect(89, 19, 89, 114), GOLD[2])
    for y, a, b in bars:
        c.paint(rect(a, y, b, y), GOLD[4])
        c.paint(rect(a, y + 2, b, y + 2), GOLD[2])
    c.paint(rect(86, 18, 90, 18), GOLD[4])
    c.paint(rect(86, 42, 90, 42), GOLD[2])
    c.paint(rect(86, 20, 90, 20), GOLD[2])


# 举起的右手：掌心向外，食指中指并立；无名指小指弯进掌心，拇指压在上面（x=20..31，y=26..48）
BLESS = [
    '.....xx.....',
    '....xllx....',
    '....xllxxx..',
    '....xllxlsx.',
    '....xllxlsx.',
    '....xllxlsx.',
    '....xllxlsx.',
    '....xllxlsx.',
    '....xllxlsx.',
    '...xxllxlsx.',
    '..xllxlxlsx.',
    '.xllllxxxxxx',
    'xlllllxllllx',
    'xlllxlxllllx',
    'xlllxlsxlllx',
    'xllxxsdxxlsx',
    'xlllllddllsx',
    'xlllllsdlssx',
    'xllllllsdssx',
    '.xlllllllssx',
    '.xllllllssx.',
    '.xlllllsssx.',
    '..xllllssx..',
]

# 握杖的左手（x=84..95，y=57..70）：拇指扣在杖左，四指从右横过杖前
GRIP = [
    '....xxxx....',
    '..xxllllxx..',
    '.xlxllllllx.',
    'xllxddddllsx',
    'xllxllllllsx',
    'xlsxddddllsx',
    '.xxlllllllsx',
    '..xddddddlsx',
    '..xlllllllsx',
    '..xlllllllsx',
    '..xxlllllssx',
    '....xxxxssx.',
]


def hands(c):
    # 右臂：袖口 V 形暗衬，小臂从里面伸出
    c.paint(poly([(19, 49), (27, 51), (25, 58), (20, 75)]), STONE[0])
    c.paint(poly([(20, 49), (26, 50), (25, 57), (21, 65), (20, 60)]), SKIN[1])
    c.paint(poly([(21, 48), (27, 49), (25, 55), (22, 57)]), SKIN[3])
    c.sprite(20, 26, BLESS, SKIN_PAL)

    # 左臂：暗衬和小臂在手下方
    c.paint(poly([(90, 68), (96, 68), (95, 80), (92, 88)]), STONE[0])
    c.paint(poly([(91, 68), (95, 68), (94, 75), (92, 78)]), SKIN[2])
    c.paint(poly([(91, 68), (93, 68), (93, 72), (92, 74)]), SKIN[3])
    c.sprite(84, 57, GRIP, SKIN_PAL)


def book(c):
    # 讲经台：木面、托、柱和台阶状的底座
    top = poly([(28, 132), (83, 132), (87, 136), (24, 136)])
    c.planks(top, WOOD, h=3, seed=41, base=3)
    c.paint(edge(top), WOOD[0])
    c.paint(poly([(39, 137), (72, 137), (68, 141), (43, 141)]), WOOD[1])
    stem = rect(52, 142, 59, 150)
    c.paint(stem, WOOD[2])
    c.paint(rect(52, 142, 53, 150), WOOD[3])
    c.paint(rect(58, 142, 59, 150), WOOD[1])
    c.paint(edge(stem) & rect(0, 142, W, 150) & ~rect(53, 142, 58, 150), WOOD[0])
    step_a, step_b = rect(49, 151, 62, 153), rect(45, 154, 66, 157)
    c.paint(step_a, WOOD[3])
    c.paint(step_b, WOOD[3])
    c.paint(rect(49, 153, 62, 153) | rect(45, 157, 66, 157), WOOD[1])
    c.paint(edge(step_a) | edge(step_b), WOOD[0])

    # 书：皮封、左右两页、向中缝微微下陷，页脚厚度压一道暗面
    cover = poly([(31, 106), (27, 133), (84, 133), (80, 106)])
    c.paint(outer(cover), WOOD[0])
    c.paint(cover, WOOD[3])
    half = [(33, 105), (45, 104), (55, 107), (55, 131), (45, 128), (28, 131)]
    c.paint(poly(half), IVORY[4])
    c.paint(poly(flip_pts(half)), IVORY[4])
    thick = [(28, 131), (45, 128), (55, 131), (55, 133), (45, 130), (28, 133)]
    c.paint(poly(thick), IVORY[2])
    c.paint(poly(flip_pts(thick)), IVORY[2])
    c.paint(line([(28, 133), (45, 130), (55, 133)]) | line([(83, 133), (66, 130), (56, 133)]), WOOD[1])
    c.paint(line([(33, 105), (45, 104), (55, 107)]) | line([(78, 105), (66, 104), (56, 107)]), IVORY[0])
    c.paint(line([(33, 105), (28, 131)]) | line([(78, 105), (83, 131)]), IVORY[1])
    c.paint(rect(55, 108, 56, 131), IVORY[2])
    # 字行
    for y, a, b in ((108, 37, 47), (111, 36, 47), (114, 36, 45), (117, 35, 43), (120, 34, 40), (123, 33, 38), (126, 33, 38)):
        c.glow(rect(a, y, b, y), TEAL[1])
        c.glow(rect(111 - b, y, 111 - a, y), TEAL[1])

    # 三节点与连线
    c.glow_line([(52, 115), (48, 121)], TEAL[3])
    c.glow_line([(59, 115), (63, 121)], TEAL[3])
    c.glow_line([(49, 124), (62, 124)], TEAL[3])
    node_even(c, CX, 112, 3)
    c.node(46, 124, 3)
    c.node(65, 124, 3)


def keys(c):
    # 深色石地，砖缝极淡，金钥匙才读得出来
    floor = rect(0, 158, W - 1, H - 1)
    c.paint(floor, NAVY[1])
    for y in (165, 172, 179, 186):
        c.paint(rect(0, y, W - 1, y), '#161c3e')
    c.paint(rect(0, 158, W - 1, 158), NAVY[0])

    # 环头、杆、两齿；两把在中线交叉
    for flip in (True, False):
        f = mirror_f(flip)
        shaft = line(f([(48, 168), (68, 186)]), 3)
        bx, by = f([(45.5, 164.5)])[0]
        bow = ring(bx, by, 6.5, 6.5, 2)
        neck = line(f([(50, 170), (52, 172)]), 5)
        teeth = line(f([(61, 181), (64, 178)]), 3) | line(f([(65, 185), (68, 182)]), 3)
        whole = shaft | bow | teeth | neck
        c.paint(outer(whole), GOLD[0])
        c.paint(whole, GOLD[3])
        c.paint(whole & ~shift(whole, 1, 1), GOLD[4])
        c.paint(whole & ~shift(whole, -1, -1), GOLD[2])


# 人类学徒的后脑（x=7..29，y=110..133）：短棕发，碎尖向外，发旋在头顶，右耳露一点
HUMAN_BACK = [
    '.........0..0..........',
    '......00020.02.0.......',
    '....0022233222200......',
    '..002223333222222000...',
    '.00222333322222222200..',
    '...0223332221222222200.',
    '..02223322211122222220.',
    '.0222332221111222222100',
    '02222322211012222222100',
    '.0022222210011222221200',
    '..0222222100112222212 0'.replace(' ', '0'),
    '.00222222100112222111 0'.replace(' ', '0'),
    '0.02222221011122221110.',
    '...0222211011122211110.',
    '...022221101112111110ss',
    '...0022111011111111 0ls'.replace(' ', '0'),
    '.....02111011111110.0ls',
    '.....00110101111100.0s0',
    '......0.01.011110.......',
    '.........0..0010.......',
    '.........xss0.0ssx.....',
    '.........xsssssssx.....',
]


def apprentice_human(c):
    # 人类学徒：背对画面跪坐，短棕发，靛蓝披肩
    robe = poly([(8, 165), (26, 165), (31, 176), (37, 186), (42, 191), (0, 191), (0, 178), (5, 170)])
    c.paint(robe, IVORY[4])
    c.paint(edge(robe), IVORY[1])
    c.paint(poly([(9, 167), (13, 167), (6, 180)]), IVORY[2])
    c.paint(poly([(21, 167), (25, 167), (26, 180)]), IVORY[2])
    c.paint(line([(16, 167), (15, 180)]), IVORY[2])
    # 袍摆铺在膝前
    hem = [(0, 183), (5, 180), (13, 184), (21, 180), (32, 183), (40, 191), (0, 191)]
    c.paint(poly(hem), IVORY[3])
    c.paint(line(hem[:5]), IVORY[1])
    # 跪坐时朝外的两只鞋底
    for x0 in (6, 15):
        sole = poly([(x0 + 1, 183), (x0 + 5, 183), (x0 + 6, 186), (x0 + 6, 191), (x0, 191), (x0, 186)])
        c.paint(sole, LEATHER[2])
        c.paint(rect(x0 + 2, 185, x0 + 3, 190), LEATHER[3])
        c.paint(edge(sole), LEATHER[0])
    # 手臂
    for pts in ([(4, 152), (9, 158), (9, 165), (6, 171), (2, 165)], [(30, 150), (33, 152), (34, 165), (30, 171), (26, 162), (27, 156)]):
        m = poly(pts)
        c.paint(m, IVORY[3])
        c.paint(edge(m), IVORY[1])
    belt = rect(8, 161, 25, 165)
    c.paint(belt, NAVY[2])
    c.paint(rect(8, 165, 25, 165) | rect(8, 161, 25, 161), NAVY[0])
    # 披肩：V 形下摆，金边只走一道
    cape = poly([(12, 134), (24, 134), (31, 138), (35, 152), (13, 162), (0, 152), (5, 138)])
    c.shape(cape, NAVY, base=2, shadow=2, hi=0)
    c.paint(bold([(5, 138), (13, 146), (30, 138)]) & cape, GOLD[3])
    c.paint(bold([(0, 152), (13, 161), (35, 152)]) & cape, GOLD[3])
    star_badge(c, 13, 152, STAR_S)
    # 白领
    coll = poly([(11, 133), (23, 133), (22, 137), (17, 139), (12, 137)])
    c.paint(coll, IVORY[4])
    c.paint(edge(coll), IVORY[1])
    # 后脑
    c.sprite(7, 111, HUMAN_BACK, {'0': HAIR_BROWN[0], '1': HAIR_BROWN[1], '2': HAIR_BROWN[2], '3': HAIR_BROWN[3], **SKIN_PAL})


def apprentice_cat(c):
    # 灵猫学徒：背对画面跪坐，浅金长发束起，鼠尾草绿披肩，猫耳与猫尾都在
    dress = poly([(87, 165), (104, 165), (108, 178), (111, 186), (111, 191), (78, 191), (79, 178)])
    c.shape(dress, SAGE, base=2, shadow=2, hi=0)
    c.paint(poly([(91, 167), (94, 167), (86, 182)]), SAGE[1])
    c.paint(poly([(102, 167), (105, 167), (108, 182)]), SAGE[1])
    hem = [(76, 191), (78, 184), (86, 180), (95, 184), (104, 183), (111, 180), (111, 191)]
    c.paint(poly(hem), IVORY[3])
    c.paint(line(hem[:6]), IVORY[1])
    for x0 in (89, 99):
        sole = poly([(x0 + 1, 184), (x0 + 5, 184), (x0 + 6, 187), (x0 + 6, 191), (x0, 191), (x0, 187)])
        c.paint(sole, LEATHER[2])
        c.paint(rect(x0 + 2, 186, x0 + 3, 190), LEATHER[3])
        c.paint(edge(sole), LEATHER[0])
    # 手臂
    for pts in ([(81, 152), (85, 156), (85, 166), (81, 171), (77, 162)], [(106, 152), (110, 154), (110, 165), (108, 170), (104, 162)]):
        m = poly(pts)
        c.paint(m, IVORY[3])
        c.paint(edge(m), IVORY[1])
    belt = rect(87, 161, 103, 165)
    c.paint(belt, SAGE[1])
    c.paint(rect(87, 165, 103, 165) | rect(87, 161, 103, 161), SAGE[0])
    # 披肩
    cape = poly([(88, 136), (102, 136), (107, 140), (111, 153), (97, 160), (78, 154), (82, 141)])
    c.shape(cape, SAGE, base=3, shadow=2, hi=0)
    c.paint(bold([(82, 140), (97, 149), (107, 140)]) & cape, GOLD[3])
    c.paint(bold([(78, 154), (97, 160), (111, 153)]) & cape, GOLD[3])
    star_badge(c, 97, 155, STAR_S)

    # 尾巴：从后腰绕到身旁，尾尖上翘
    tail(c, [(97, 186), (94, 178), (96, 170), (101, 165), (105, 166)], SAND, 6, 4, base=3)

    # 猫耳在发顶两侧：这是她和人类学徒的第一眼差别
    for flip in (False, True):
        f = (lambda pts: flip_pts(pts, 94.5)) if flip else (lambda pts: pts)
        ear_m = poly(f([(83, 109), (93, 116), (86, 124)]))
        c.paint(outer(ear_m), SAND[0])
        c.paint(ear_m, SAND[2])
        c.paint(ear_m & ~shift(ear_m, -1, 1), SAND[3])
    # 后脑
    back = blob([(94.5, 115), (101, 117), (104, 124), (103, 131), (99, 135), (90, 135), (86, 131), (85, 124), (88, 117)])
    c.paint(back, SAND[3])
    c.paint(back & ~shift(back, 2, 2), SAND[4])
    c.paint(edge(back), SAND[0])
    c.fold([(94, 116), (91, 122), (92, 130)], SAND[2], shrink(back))
    c.fold([(97, 117), (99, 124), (97, 131)], SAND[2], shrink(back))
    # 垂下的长发束和暗色蝴蝶结
    pony = taper([(94.5, 133), (94, 139), (96, 144), (98, 148)], 9, 2)
    c.paint(pony, SAND[3])
    c.paint(pony & ~shift(pony, 2, 0), SAND[4])
    c.paint(edge(pony), SAND[0])
    c.fold([(93, 137), (95, 146)], SAND[2], shrink(pony))
    c.sprite(90, 130, ['kk....kk', 'kgkkkkgk', 'kggkkggk', 'kgk..kgk', 'kk....kk'], {'k': SAGE[0], 'g': SAGE[2]})


def draw():
    c = Card('05_hierophant')
    setting(c)
    robe_back(c)
    sleeves(c)
    torso(c)
    collar(c)
    face(c)
    crown(c)
    staff(c)
    hands(c)
    keys(c)
    book(c)
    apprentice_human(c)
    apprentice_cat(c)
    return c
