"""7 战车：蓝发猫耳的驾驭者站在车内，一手举杖、一手按住车沿；一黑一白两只真猫坐在车前，蓝青线路从两侧汇到中心节点；五星篷、翼形菱印、石砖路和两座远城。"""
from figures import *

CX = 55.5
SKIN_PAL = {'l': SKIN[3], 's': SKIN[2], 'd': SKIN[1], 'x': SKIN[0]}
# 黑猫比车箱暗，白猫比路亮，两只猫都从背景里跳出来
CAT_BLACK = ['#100e1a', '#1d1b28', '#2b2937', '#3d3b4b', '#5a586a']
CAT_WHITE = ['#30323c', IVORY[1], IVORY[2], IVORY[3], IVORY[4]]
ARMOR = [STONE[1], STONE[3], STONE[4], '#c9cacb', WHITE[3]]

CRESCENT = [
    '..ggg.',
    '.ggh..',
    'gghh..',
    'gghh..',
    'gghh..',
    '.gghh.',
    '..gggg',
]

# 猫头：朝右的四分之三面。k 眼眶 e 虹膜 p 瞳孔 n 鼻 m 嘴 h 口鼻浅色
CAT_HEAD = [
    '...o............o.....',
    '...oo..........oo.....',
    '..owo..........owo....',
    '..owpo........opwo....',
    '..owppo......oppwo....',
    '.owPppo.....oppPwo....',
    '.owPpppo...oppPPwo....',
    '.owwwppooooopppwwwo...',
    'owwwwwwwwwwwwwwwwwo...',
    'owwwwwwwwwwwwwwwwwwo..',
    'owwwwwwwwwwwwwwwwwwwo.',
    'owwwwwwkkkkwwwwkkkwwo.',
    'owwwwwkeeeukwwkeukwwo.',
    'owwwwwwkeeuwwwwkuwwwo.',
    '.owwwwwwwwwwwwwwwwwwwo',
    '.owwwwwwwwwwwwwhnnhhho',
    '..owwwwwwwwwwwhhhmhhho',
    '..owwwwwwwwwwwhhmhmho.',
    '...owwwwwwwwwwwhhhho..',
    '....owwwwwwwwwwwwoo...',
]

# 驾驭者的左半张头（x=43..55），镜像得到右半
HEAD_HALF = [
    '.........0023',
    '.......002233',
    '......0022333',
    '.....00223333',
    '....002233333',
    '....022233333',
    '...0222233333',
    '...0222333332',
    '..02222333322',
    '..02222233221',
    '.002222212222',
    '..02222211222',
    '.002221lll122',
    '..0221sllll12',
    '.0021slllllll',
    '..021slllllll',
    '.0021slllllll',
    '..011slllllll',
    '...01xsllllll',
    '...00.xslllll',
    '......xslllll',
    '.......xsllll',
    '........xslll',
    '.........xsll',
    '..........xxs',
]


def diamond(cx, cy, r):
    return (np.abs(XX - cx) + np.abs(YY - cy)) <= r


def bold(pts):
    """两像素宽的折线。这里叠一条下移的。"""
    m = line(pts)
    return m | shift(m, 0, 1)


def star_fat(c, x, y, even=False):
    """篷顶的四芒星：有体积，尖角收细。even 时占两列，落在中轴上。"""
    rows = ['....g....', '....g....', '...ghg...', '..gghgg..', 'gggghgggg', '..ggggg..', '...ggg...', '....g....', '....g....']
    if even:
        rows = [r[:5] + r[4:] for r in rows]
    x0 = x - 4
    m = c.sprite_mask(x0, y - 4, rows)
    c.paint(outer(m), NAVY[0])
    c.sprite(x0, y - 4, rows, {'g': GOLD[3], 'h': GOLD[5]}, glow='gh')


def node_even(c, cx, cy, r=3):
    m = diamond(cx, cy, r + 0.5)
    c.paint(outer(m), TEAL[0])
    c.glow(m, TEAL[4])
    c.glow(diamond(cx, cy, r - 0.5), TEAL[3])
    c.glow(diamond(cx, cy, max(r - 2, 0.5)), TEAL[4])


def backdrop(c):
    # 夜蓝天空
    c.vgrad(rect(0, 0, W - 1, 96), [SKY[0], SKY[1], SKY[1]], 10, 90, soft=0.1)

    # 方块云：两侧的大团和两片薄云
    clouds = [rect(0, 52, 8, 56) | rect(0, 56, 17, 66) | rect(3, 66, 17, 80), rect(92, 48, 111, 56) | rect(98, 42, 111, 48) | rect(92, 56, 100, 62) | rect(92, 62, 111, 80), rect(0, 31, 6, 33) | rect(0, 33, 14, 34), rect(78, 39, 92, 40) | rect(84, 37, 92, 39)]
    for m in clouds:
        c.paint(m, IVORY[4])
        c.paint(m & ~shift(m, 0, -2), IVORY[3])

    # 两座远城：城墙只留轮廓
    for flip in (False, True):
        fx = (lambda x: 111 - x) if flip else (lambda x: x)

        def box(a, y0, b, y1):
            lo, hi = sorted((fx(a), fx(b)))
            return rect(lo, y0, hi, y1)

        castle = box(0, 80, 17, 94) | box(4, 70, 12, 94)
        for a in (4, 7, 10):
            castle |= box(a, 68, a + 1, 70)
        for a in (0, 14):
            castle |= box(a, 78, a + 1, 80)
        c.paint(castle, SLATE[3])
        c.paint(box(13, 80, 17, 94) | box(11, 70, 12, 80), SLATE[2])
        c.paint(box(7, 64, 7, 68), SLATE[1])
        c.paint(poly([(fx(8), 64), (fx(13), 66), (fx(8), 67)]), SLATE[2])

    # 草地和远丘
    ground = rect(0, 92, W - 1, H - 1)
    c.turf(ground, GRASS, seed=71, base=1)
    road_pts = [(45, 141), (66, 141), (95, 158), (111, 172), (111, 191), (0, 191), (0, 172), (16, 158)]
    road = poly(road_pts)
    c.paint(outer(road) & ground, GRASS[0])
    stone_road(c, road)


def stone_road(c, road):
    # 石砖路：横缝越往近处越宽，竖缝从远处的消失点放射出来，砖块有原版圆石砖的小明暗差
    rows = [141, 144, 148, 153, 159, 166, 174, 183, 192]
    rng = np.random.default_rng(72)
    tone = rng.integers(0, 3, (16, 32))
    ramp = [STONE[3], STONE[4], ARMOR[3]]
    for r in range(len(rows) - 1):
        for y in range(rows[r], rows[r + 1]):
            k = (y - 118) / 3.2
            for x in range(W):
                if not road[y, x]:
                    continue
                u = (x - CX) / k + (0.5 if r % 2 else 0)
                u2 = (x + 1 - CX) / k + (0.5 if r % 2 else 0)
                col = int(np.floor(u))
                if y == rows[r + 1] - 1 or int(np.floor(u2)) != col:
                    c.px(x, y, STONE[2])
                elif y == rows[r]:
                    c.px(x, y, ramp[min(tone[r, col % 32] + 1, 2)])
                else:
                    c.px(x, y, ramp[tone[r, col % 32]])


def wheel(c, flip):
    cx = 101 if flip else 10
    cy = 131
    side = -1 if flip else 1
    out = ellipse(cx, cy, 9, 33)
    inner = ellipse(cx + side, cy, 6, 27)
    c.paint(outer(out), GOLD[0])
    c.paint(out, GOLD[3])
    c.paint(out & ~shift(out, side, 0), GOLD[4])
    c.paint(out & (YY > 150), GOLD[2])
    c.paint(out & (np.abs(YY - 112) <= 1), GOLD[4])
    c.paint(inner, NAVY[0])
    c.paint(outer(inner) & out, GOLD[1])
    # 八根辐条从轮心放射
    hx = cx + side
    for dx, dy in ((0, -27), (0, 27), (5, -17), (-5, -17), (5, 17), (-5, 17), (6, 0), (-6, 0)):
        c.paint(line([(hx, cy), (hx + dx, cy + dy)]) & inner, GOLD[3])
    hub = ellipse(hx, cy, 2.5, 5)
    c.paint(outer(hub), GOLD[0])
    c.paint(hub, GOLD[3])
    c.paint(hub & ~shift(hub, 1, 1), GOLD[4])
    # 车轴接到车箱
    ax0, ax1 = sorted((hx, 94 if flip else 17))
    axle = rect(ax0, cy - 2, ax1, cy + 2)
    c.paint(axle & ~hub, GOLD[2])
    c.paint(rect(ax0, cy - 2, ax1, cy - 2) & ~hub, GOLD[4])
    c.paint(rect(ax0, cy + 2, ax1, cy + 2) & ~hub, GOLD[1])


def canopy(c):
    # 两根金柱从车沿直顶到篷下
    for x0, x1 in ((18, 21), (90, 93)):
        post = rect(x0, 14, x1, 96)
        c.paint(outer(post), GOLD[0])
        c.paint(post, GOLD[3])
        c.paint(rect(x0, 14, x0, 96), GOLD[4])
        c.paint(rect(x1, 14, x1, 96), GOLD[2])
        foot = rect(x0 - 1, 93, x1 + 1, 96)
        c.paint(foot, GOLD[3])
        c.paint(edge(foot), GOLD[1])

    # 篷底的阴影
    c.paint(poly([(18, 14), (93, 14), (93, 19), (84, 19), (80, 21), (31, 21), (27, 19), (18, 19)]), NIGHT[1])

    # 篷面和扇形垂边：垂边金线只走一道
    top = poly([(13, 0), (98, 0), (102, 12), (9, 12)])
    c.paint(top, NAVY[2])
    c.paint(top & (XX > 92), NAVY[1])
    c.paint(top & (XX < 19), NAVY[1])
    scallop = [(9, 12), (15, 17), (23, 13), (32, 13), (38, 16), (50, 16), (55, 13), (56, 13), (61, 16), (73, 16), (79, 13), (88, 13), (96, 17), (102, 12)]
    fill = poly(scallop + [(102, 10), (9, 10)])
    c.paint(fill, NAVY[1])
    c.paint(bold(scallop), GOLD[3])
    c.paint(line(scallop), GOLD[4])

    # 篷顶五星
    for x in (24, 40, 71, 87):
        star_fat(c, x, 6)
    star_fat(c, 55, 6, even=True)


def driver_back(c):
    # 尾巴从身后绕到右肩上方
    tail(c, [(82, 74), (80, 62), (81, 54), (84, 49), (87, 50)], HAIR_BLUEBLACK, 6, 5, base=3)

    # 披风：暗衬打底，左右两片亮面向下展开
    cape = poly([(36, 60), (76, 60), (84, 70), (91, 96), (21, 96), (28, 70)])
    c.paint(outer(cape), NAVY[0])
    c.paint(cape, NAVY[1])
    for flip in (False, True):
        f = (lambda pts: flip_pts(pts)) if flip else (lambda pts: pts)
        c.paint(poly(f([(36, 60), (44, 62), (41, 72), (32, 96), (23, 96), (29, 72)])), NAVY[3])
        c.paint(poly(f([(36, 64), (39, 66), (32, 84), (28, 96), (25, 96), (30, 76)])), NAVY[2])


def driver_body(c):
    # 下身：深蓝裙甲和四片银色甲叶
    skirt = poly([(42, 86), (69, 86), (72, 96), (39, 96)])
    c.paint(skirt, NAVY[2])
    c.paint(edge(skirt), NAVY[0])
    for flip in (False, True):
        f = (lambda pts: flip_pts(pts)) if flip else (lambda pts: pts)
        for pts in ([(45, 87), (50, 87), (46, 96), (41, 96)], [(51, 87), (55, 87), (54, 96), (49, 96)]):
            plate = poly(f(pts))
            c.paint(plate, ARMOR[2])
            c.paint(plate & ~shift(plate, 1, 0), ARMOR[3])
            c.paint(edge(plate) & (YY > 88), ARMOR[0])

    # 胸甲：上下两段，中线分明暗
    plate_low = poly([(46, 72), (65, 72), (67, 80), (67, 82), (44, 82), (44, 80)])
    c.paint(plate_low, ARMOR[1])
    c.paint(edge(plate_low), ARMOR[0])
    plate_up = poly([(45, 60), (66, 60), (68, 68), (63, 75), (55.5, 79), (48, 75), (43, 68)])
    c.paint(plate_up, ARMOR[3])
    c.paint(plate_up & (XX > CX), ARMOR[2])
    c.paint(plate_up & ~shift(plate_up, 0, 2), ARMOR[4])
    c.paint(edge(plate_up), ARMOR[0])
    c.paint(line([(45, 67), (50, 70), (61, 70), (66, 67)]), ARMOR[1])
    # 胸前金菱
    gem = np.abs(XX - CX) / 3.5 + np.abs(YY - 67) / 6 <= 1
    c.paint(outer(gem), GOLD[1])
    c.paint(gem, GOLD[3])
    c.paint(gem & (XX < CX), GOLD[4])

    # 腰带和方菱扣
    belt = rect(44, 81, 67, 86)
    c.paint(belt, CAT_BLACK[2])
    c.paint(rect(44, 81, 67, 81), CAT_BLACK[0])
    c.paint(rect(44, 86, 67, 86), CAT_BLACK[0])
    buckle = diamond(CX, 84, 4.5)
    c.paint(outer(buckle), GOLD[1])
    c.paint(buckle, GOLD[3])
    c.paint(diamond(CX, 84, 2.5), NAVY[1])

    # 领甲：立领、蓝垫和项圈
    c.paint(poly([(46, 53), (65, 53), (67, 61), (44, 61)]), NAVY[3])
    for flip in (False, True):
        f = (lambda pts: flip_pts(pts)) if flip else (lambda pts: pts)
        stand = poly(f([(48, 49), (55, 49), (55, 54), (49, 57)]))
        c.paint(stand, ARMOR[3])
        c.paint(edge(stand), ARMOR[0])
    c.paint(rect(55, 49, 56, 55), NAVY[0])
    c.paint(bold([(48, 58), (53, 60), (58, 60), (63, 58)]), GOLD[3])
    for x in (47, 64):
        d = diamond(x, 57, 2)
        c.paint(outer(d), GOLD[1])
        c.paint(d, GOLD[4])


def driver_arms(c):
    # 举杖的手臂：深色上臂、银色护腕和握拳
    upper = poly([(30, 60), (38, 62), (38, 69), (31, 69)])
    c.paint(upper, NAVY[1])
    c.paint(edge(upper), NAVY[0])
    fore = poly([(25, 51), (31, 51), (33, 60), (32, 66), (24, 70), (21, 63)])
    c.paint(fore, ARMOR[3])
    c.paint(fore & ~shift(fore, 1, 0), ARMOR[4])
    c.paint(fore & (XX > 29), ARMOR[2])
    c.paint(edge(fore), ARMOR[0])
    c.paint(line([(22, 63), (26, 66), (32, 62)]), ARMOR[0])

    # 杖身和杖头：菱晶和八道短光
    staff = bold([(31, 52), (31, 42), (32, 32)]) | shift(line([(31, 52), (31, 42), (32, 32)]), 1, 0)
    c.paint(outer(staff), GOLD[0])
    c.paint(staff, GOLD[3])
    hx, hy = 32, 26
    head_m = np.abs(XX - hx) / 4 + np.abs(YY - hy) / 6 <= 1
    c.paint(outer(head_m), GOLD[0])
    c.glow(head_m, GOLD[4])
    c.glow(np.abs(XX - hx) / 2.5 + np.abs(YY - hy) / 4 <= 1, TEAL[3])
    c.glow_px([(31, 25), (32, 24)], TEAL[4])
    for a, b in (((32, 15), (32, 18)), ((24, 26), (26, 26)), ((38, 26), (40, 26)), ((26, 20), (28, 22)), ((38, 20), (36, 22)), ((26, 32), (28, 30)), ((38, 32), (36, 30))):
        c.glow(line([a, b]), TEAL[3])

    # 握杖的拳
    c.sprite(26, 41, [
        '..xxxxx.',
        '.xlllllx',
        'xllllllx',
        'xlddddlx',
        'xllllllx',
        'xlddddlx',
        'xllllllx',
        'xlddddlx',
        'xlllllsx',
        '.xllssx.',
        '..xxxx..',
    ], SKIN_PAL)
    c.paint(rect(30, 52, 32, 54), WOOD[1])

    # 按车沿的手臂
    upper_r = poly([(71, 66), (79, 68), (80, 77), (73, 79)])
    c.paint(upper_r, NAVY[1])
    c.paint(edge(upper_r), NAVY[0])
    fore_r = poly([(74, 79), (80, 76), (85, 92), (78, 95)])
    c.paint(fore_r, ARMOR[3])
    c.paint(fore_r & ~shift(fore_r, 1, 0), ARMOR[4])
    c.paint(fore_r & (XX + YY * 0.35 > 112), ARMOR[2])
    c.paint(edge(fore_r), ARMOR[0])
    c.paint(line([(76, 91), (84, 89)]), ARMOR[0])

    # 肩甲：有棱角的两片，上亮下暗；月牙在正中
    for flip in (False, True):
        f = (lambda pts: flip_pts(pts)) if flip else (lambda pts: pts)
        pad = poly(f([(42, 50), (46, 53), (45, 60), (38, 64), (30, 62), (31, 57), (35, 52)]))
        c.paint(outer(pad), ARMOR[0])
        c.paint(pad, ARMOR[3])
        c.paint(pad & ~shift(pad, 1, 2), ARMOR[4])
        c.paint(pad & ~shift(pad, 0, -3), ARMOR[1])
    c.sprite(35, 53, CRESCENT, {'g': GOLD[3], 'h': GOLD[4]})
    c.sprite(70, 53, CRESCENT, {'g': GOLD[3], 'h': GOLD[4]}, flip=True)


def driver_head(c):
    # 猫耳：外缘蓝黑，内耳粉
    for flip in (False, True):
        f = (lambda pts: flip_pts(pts)) if flip else (lambda pts: pts)
        e = poly(f([(43, 21), (53, 28), (45, 36)]))
        c.paint(outer(e), HAIR_BLUEBLACK[0])
        c.paint(e, HAIR_BLUEBLACK[2])
        c.paint(poly(f([(45, 24), (50, 28), (46, 33)])), PINK[2])
        c.paint(poly(f([(46, 27), (49, 28), (46, 31)])), PINK[3])

    pal = {'0': HAIR_BLUEBLACK[0], '1': HAIR_BLUEBLACK[1], '2': HAIR_BLUEBLACK[3], '3': HAIR_BLUEBLACK[4], '4': HAIR_BLUEBLACK[4], **SKIN_PAL}
    shade = {'3': '2', '2': '1'}
    rows = [r + ''.join(shade.get(ch, ch) for ch in r[::-1]) for r in HEAD_HALF]
    c.sprite(43, 24, rows, pal)
    # 下颌右侧的暗面
    c.pxs([(62, 41), (62, 42), (61, 43), (61, 44), (60, 45), (59, 46)], SKIN[2])

    # 眼：坚定，眉压低
    eye = {'k': '#1a1a2c', 'w': '#f3f2e8', 'e': '#5a7fb8', 'p': '#20305c'}
    c.sprite(49, 39, ['kkkkk', '.wepe', '..ee.'], eye)
    c.sprite(58, 39, ['kkkkk', 'epew.', '.ee..'], eye)
    c.pxs([(50, 36), (51, 36), (52, 37), (53, 37)], HAIR_BLUEBLACK[0])
    c.pxs([(58, 37), (59, 37), (60, 36), (61, 36)], HAIR_BLUEBLACK[0])
    c.pxs([(55, 43), (56, 43)], SKIN[1])
    c.paint(rect(54, 46, 57, 46), SKIN[1])

    # 金额环和星
    c.paint(bold([(46, 34), (50, 32), (55, 31), (56, 31), (61, 32), (65, 34)]), GOLD[3])
    c.paint(line([(46, 34), (50, 32), (55, 31), (56, 31), (61, 32), (65, 34)]), GOLD[4])
    st = rect(55, 25, 56, 33) | rect(52, 28, 59, 29) | rect(54, 27, 57, 30)
    c.paint(outer(st), GOLD[1])
    c.paint(st, GOLD[4])
    c.paint(rect(55, 28, 56, 29), GOLD[5])


def box(c):
    # 车箱：金框深面，四边的金条彼此分开，一眼读成方箱
    frame = rect(17, 96, 94, 157)
    c.paint(outer(frame), GOLD[0])
    c.paint(frame, GOLD[3])
    panel = rect(19, 100, 92, 157)
    c.paint(panel, NAVY[1])
    inner = rect(26, 100, 85, 134)
    c.paint(inner, NAVY[3])
    c.paint(rect(23, 100, 25, 157) | rect(86, 100, 88, 157), GOLD[3])
    c.paint(rect(23, 100, 23, 157) | rect(86, 100, 86, 157), GOLD[4])
    c.paint(rect(17, 96, 94, 96), GOLD[4])
    c.paint(rect(17, 99, 94, 99), GOLD[2])

    # 车底两道金条
    for y0, y1 in ((134, 137), (139, 142)):
        bar = rect(26, y0, 85, y1)
        c.paint(bar, GOLD[3])
        c.paint(rect(26, y0, 85, y0), GOLD[4])
        c.paint(rect(26, y1, 85, y1), GOLD[1])
    c.paint(rect(26, 138, 85, 138), NAVY[0])
    # 车底暗面，道路从下面穿过
    c.paint(rect(26, 143, 85, 157), NAVY[0])
    stone_road(c, poly([(45, 141), (66, 141), (95, 158), (16, 158)]) & rect(26, 143, 85, 157))

    # 翼形菱印：金色发光，中间一枚竖菱，左右三层羽
    for flip in (False, True):
        f = (lambda pts: flip_pts(pts)) if flip else (lambda pts: pts)
        for pts, col in (([(30, 102), (47, 108), (47, 112), (35, 110)], GOLD[4]), ([(35, 112), (47, 113), (47, 116), (40, 115)], GOLD[3]), ([(41, 117), (47, 117), (50, 120), (44, 119)], GOLD[3])):
            c.glow(poly(f(pts)), col)
    big = np.abs(XX - CX) / 8.5 + np.abs(YY - 114) / 14.5 <= 1
    mid = np.abs(XX - CX) / 5.5 + np.abs(YY - 114) / 10 <= 1
    c.paint(outer(big), GOLD[0])
    c.glow(big, GOLD[3])
    c.glow(mid, GOLD[4])
    c.glow(mid & (XX < CX) & (YY < 114), GOLD[5])

    # 车沿上按住的手
    c.sprite(76, 92, [
        '..xxxxxx...',
        '.xllllllxx.',
        'xlllllllllx',
        'xlxllxllxlx',
        'xlxllxllxsx',
        'xlxlsxlsxsx',
        '.x.xx.xx.x.',
    ], SKIN_PAL)


def conduits(c):
    # 蓝青线路从两侧收束到中心节点
    for pts in ([(26, 112), (53, 132)], [(85, 112), (58, 132)]):
        c.glow(bold(pts), TEAL[3])
        c.glow(line(pts), TEAL[4])
    c.node(25, 111, 4)
    c.node(86, 111, 4)
    node_even(c, CX, 134, 4)


def cat_black(c):
    r = CAT_BLACK
    # 尾巴从身后绕到身前
    t = taper([(49, 168), (56, 175), (53, 183), (43, 186), (35, 186)], 6, 4)
    c.paint(outer(t), r[0])
    c.paint(t, r[2])
    c.paint(t & ~shift(t, 0, -1), r[3])
    # 身体：弓起的背、坐下的后腿、并拢的前腿
    body = poly([(20, 142), (36, 140), (42, 147), (49, 156), (53, 167), (52, 178), (48, 181), (19, 181), (18, 172), (20, 160), (21, 150)])
    c.paint(outer(body), r[0])
    c.paint(body, r[2])
    c.paint(body & ~shift(body, 2, 2), r[3])
    # 前腿之间和腿后的暗缝
    c.paint(poly([(30, 158), (33, 158), (33, 178), (31, 178)]), r[1])
    c.paint(poly([(24, 160), (26, 160), (25, 178), (24, 178)]), r[1])
    c.paint(poly([(19, 160), (22, 158), (22, 178), (19, 178)]), r[1])
    # 后腿的大弧
    c.fold([(44, 158), (37, 161), (34, 168), (36, 176), (44, 178)], r[4], shrink(body))
    # 胸前的一撮毛
    c.paint(poly([(25, 144), (31, 144), (28, 148)]), r[1])
    for x, rr in ((21, 3), (28, 3), (38, 4)):
        m = ellipse(x, 179, rr, 2.5)
        c.paint(outer(m), r[0])
        c.paint(m, r[3])
        for k in (-1, 1):
            c.pxs([(x + k, 180), (x + k, 181)], r[0])
    # 头：朝左，略微仰起，瞳孔靠上；金色眼
    pal = {'o': r[0], 'w': r[2], 'h': r[3], 'p': PINK[0], 'P': RED[2], 'k': r[0], 'e': GOLD[3], 'u': r[0], 'n': PINK[0], 'm': r[0]}
    c.sprite(15, 121, CAT_HEAD, pal, flip=True)
    # 胡须
    for pts in (((16, 137), (11, 136)), ((16, 139), (11, 140)), ((31, 137), (37, 136)), ((31, 139), (37, 140))):
        c.paint(line(pts), r[4])


def cat_white(c):
    r = CAT_WHITE
    t = taper([(62, 168), (56, 175), (60, 183), (69, 186), (77, 187)], 6, 4)
    c.paint(outer(t), r[0])
    c.paint(t, r[4])
    c.paint(t & ~shift(t, 0, -1), r[3])
    body = poly([(91, 142), (75, 140), (69, 147), (62, 156), (58, 167), (59, 178), (63, 181), (92, 181), (93, 172), (91, 160), (90, 150)])
    c.paint(outer(body), r[0])
    c.paint(body, r[4])
    c.paint(body & ~shift(body, -2, -2), r[2])
    c.paint(poly([(78, 158), (81, 158), (80, 178), (78, 178)]), r[2])
    c.paint(poly([(85, 160), (87, 160), (87, 178), (86, 178)]), r[2])
    c.paint(poly([(89, 158), (92, 160), (92, 178), (89, 178)]), r[3])
    c.fold([(67, 158), (74, 161), (77, 168), (75, 176), (67, 178)], r[1], shrink(body))
    c.paint(poly([(80, 144), (86, 144), (83, 148)]), r[3])
    for x, rr in ((90, 3), (83, 3), (73, 4)):
        m = ellipse(x, 179, rr, 2.5)
        c.paint(outer(m), r[0])
        c.paint(m, r[4])
        for k in (-1, 1):
            c.pxs([(x + k, 180), (x + k, 181)], r[1])
    # 头：朝右，平视；蓝眼
    pal = {'o': r[0], 'w': r[4], 'h': WHITE[4], 'p': PINK[2], 'P': PINK[1], 'k': r[0], 'e': BLUE_IRIS, 'u': '#20305c', 'n': PINK[1], 'm': STONE[3]}
    c.sprite(74, 122, CAT_HEAD, pal)
    for pts in (((95, 138), (100, 137)), ((95, 140), (100, 141)), ((79, 138), (73, 137)), ((79, 140), (73, 141))):
        c.paint(line(pts), r[1])


def draw():
    c = Card('07_chariot')
    backdrop(c)
    wheel(c, False)
    wheel(c, True)
    canopy(c)
    driver_back(c)
    driver_body(c)
    driver_arms(c)
    driver_head(c)
    box(c)
    conduits(c)
    cat_black(c)
    cat_white(c)
    return c
