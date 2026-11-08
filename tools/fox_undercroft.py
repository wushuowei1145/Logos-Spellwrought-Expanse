"""神狐聚落的地下封印场地：约三分之一的聚落里，有一栋民居换成「封じの堂」，从堂里一路往下，走到封着九尾狐的地方。

一路下去：
- 封じの堂：替换一块普通住宅地块。小小的御堂，门口一座鸟居、一道注连绳，门板与心柱上贴满御札；堂里没有地板，
  只有绕着心柱往下的回旋石阶。
- 回旋石阶：两格宽，绕 3×3 的心柱每圈下降 12 格，一直下到约 30 格深；越往下墙越旧，灯从油灯换成狐火（灵魂灯）。
- 旧地下通道：白瓷砖墙、朱红腰线、顶上一排海晶灯，像城里废弃的地下通路；每隔几格一座嵌在墙里的小鸟居。
  越往里瓷砖越碎，灯越少，墙上的御札越多，最后一道注连绳拦在出口。
- 大鸟居之间：通道从岩洞高处的平台进来，平台下是两段沿北壁的大石阶，从东西两头一直下到底，在正中会合；
  正中往南一条参道穿过朱漆的大鸟居，两旁是黑水与石灯笼，尽头是嵌在南壁里的楼门。
- 楼门：两层瓦檐，朱柱白壁，一对狐像；巨大的门扇已经向里推开，门扇上的封条早被撕开。
- 封印之间：大约是地表聚落的一半大，黑水上一座圆台。圆心的石台上是一块缠着注连绳、从中间裂开的杀生石，
  裂缝里烧着幽蓝的狐火；九根封印杭围成一圈（九尾各一道），破坏它们就会解开封印（首领战之后再做）。
  四周一排巨柱撑着岩顶，柱间拉着注连绳；抬头看，岩顶上倒挂着一整条町屋街和一座鸟居，灯笼亮着暖光。

坐标：设计坐标 (u, v)，y 相对主街标高。大鸟居之间与封印之间在每套方案里都在同一处（主街与南侧地下）；
封じの堂与通道随所选的住宅不同，每套方案预先算好几条路线（route），结构生成时挑一条。
"""
import math
import random

from fox_kit import *  # noqa: F401,F403
import fox_buildings as fb
import fox_landmarks as fl

#: 通道地面那一层；回旋石阶从住宅地面一直下到这里
CORRIDOR_Y = -30
#: 大鸟居之间与封印之间的地面层
FLOOR_Y = -58
#: 两处岩洞的空气范围（u0, v0, u1, v1）与最上面一层空气
HALL = (52, 70, 124, 95)
HALL_TOP = -22
ARENA = (44, 100, 132, 159)
ARENA_TOP = -26
#: 楼门门洞：穿过大厅与封印之间之间四格厚的岩壁
GATE = (85, 96, 91, 99)
GATE_TOP = -46
SHELL = 3
CU = 88
#: 封印之间圆台的圆心、半径，与九根封印杭所在的圆
ARENA_CV = 131
PLATFORM_R = 23
SEAL_R = 13
#: 大厅北壁的台阶带（v 范围）与两头平台
TERRACE_V = (70, 74)
PARAPET_V = 75
BALCONY_W = (52, 56)
BALCONY_E = (120, 124)
#: 通道从大厅两头进来的那一行（台阶带中线），以及沿大厅外侧南下的 u
ENTRY_V = 72
LANE_W, LANE_E = 46, 130
#: 每套方案最多备几条路线
ROUTES_PER_PLAN = 5

WATER = B('water', level=0)
DARK_BOTTOM = (B('blackstone'), B('deepslate'), B('blackstone'), B('polished_blackstone'))


def ofuda(facing, kind=0):
    return L('journey_ofuda', facing=facing, kind=kind)


def seal_stake(half):
    return L('journey_seal_stake', half=half)


def _h(*xs):
    """确定的小哈希：同一位置每次生成结果一样。"""
    h = 2166136261
    for x in xs:
        h = (h ^ (x & 0xFFFFFFFF)) * 16777619 & 0xFFFFFFFF
    return h


# ==================== 上下翻转（倒挂的町屋街）====================
_DROP_PARTS = ('flower', 'tulip', 'sapling', 'potted', 'flower_pot', 'carpet', 'banner', 'noren', 'menu_strips', 'shimenawa',
               'suzu_rope', 'wheat', 'carrots', 'potatoes', 'beetroots', 'farmland', 'water', 'short_grass', 'fern', 'poppy',
               'dandelion', 'daisy', 'bluet', 'cornflower', 'lily', 'allium', 'stone_lantern_tall', 'brewing_stand', 'cake',
               'hanging_roots', 'spore_blossom', 'bed')


def flip_y(state):
    """上下翻转一个方块状态：楼梯、活板门上下半互换，台阶上下互换，门上下扇互换，灯笼吊挂与立放互换，朝上朝下互换。
    花草、盆栽、地毯、旗幡、暖帘这些翻过来会悬在半空的东西返回 None（不放）。"""
    name = state['Name']
    if any(part in name for part in _DROP_PARTS):
        return None
    if 'Properties' not in state:
        return state
    p = dict(state['Properties'])
    if 'half' in p:
        p['half'] = {'top': 'bottom', 'bottom': 'top', 'upper': 'lower', 'lower': 'upper'}.get(p['half'], p['half'])
    if name.endswith('_slab') and p.get('type') in ('top', 'bottom'):
        p['type'] = 'bottom' if p['type'] == 'top' else 'top'
    if 'hanging' in p:
        p['hanging'] = 'false' if p['hanging'] == 'true' else 'true'
    if p.get('facing') in ('up', 'down'):
        p['facing'] = 'down' if p['facing'] == 'up' else 'up'
    for key in ('attachment', 'face'):
        if p.get(key) in ('floor', 'ceiling'):
            p[key] = 'ceiling' if p[key] == 'floor' else 'floor'
    if 'up' in p and 'down' in p:
        p['up'], p['down'] = p['down'], p['up']
    return {'Name': name, 'Properties': p}


def paste_inverted(dst, src, ox, top, oz, turns=0):
    """把 src（正着建好的建筑，y=0 为地面）旋转 turns 次后上下翻转，地面那层贴在 top，往下垂；旋转后左上角对齐 (ox, oz)。"""
    tmp = Canvas()
    tmp.paste(src, 0, 0, 0, turns)
    for (x, y, z), st in tmp.cells.items():
        out = flip_y(st)
        if out is not None:
            dst.set(ox + x, top - y, oz + z, out)


# ==================== 大鸟居 ====================
def giant_torii(c, cx, y0, z, post_h=22, half=9):
    """明神鸟居：3×3 粗柱（削去四角，近似圆柱），柱脚黑色根卷与一圈石座；两格厚的贯穿过柱子、两头出挑，
    额束上一块黑匾；朱漆岛木上压黑色笠木，两端翘起。横梁沿 x，柱心在 cx ± half，y0 为柱脚那一层。"""
    top = y0 + post_h - 1
    for s in (-half, half):
        px_ = cx + s
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx and dz:
                    continue
                for y in range(y0, top + 1):
                    c.set(px_ + dx, y, z + dz, B('polished_blackstone') if y < y0 + 2 else LACQUER_LOG)
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                if max(abs(dx), abs(dz)) == 2 and abs(dx) + abs(dz) < 4:
                    c.set(px_ + dx, y0, z + dz, B('stone_brick_slab', type='bottom', waterlogged=False))
    nuki = top - 6
    for x in range(cx - half - 4, cx + half + 5):
        for y in (nuki, nuki + 1):
            c.set(x, y, z, LACQUER_PLANKS)
    for y in range(nuki + 2, top + 1):
        c.set(cx, y, z, LACQUER_PLANKS)
    for dx in (-1, 0, 1):
        for y in (nuki + 3, nuki + 4):
            for dz in (-1, 1):
                c.set(cx + dx, y, z + dz, B('polished_blackstone_bricks'))
    for x in range(cx - half + 2, cx + half - 1):
        c.set(x, nuki - 1, z, L('journey_shimenawa', facing='north'))
    for x in range(cx - half - 3, cx + half + 4):
        for dz in (-1, 0, 1):
            c.set(x, top + 1, z + dz, LACQUER_PLANKS)
    for x in range(cx - half - 5, cx + half + 6):
        for dz in (-1, 0, 1):
            c.set(x, top + 2, z + dz, B('polished_blackstone'))
    for x in range(cx - half - 6, cx + half + 7):
        end = abs(x - cx) == half + 6
        for dz in (-1, 0, 1):
            c.set(x, top + 3, z + dz, B('polished_blackstone') if end else B('polished_blackstone_slab', type='bottom', waterlogged=False))
    for s, f in ((-1, 'west'), (1, 'east')):
        for dz in (-1, 0, 1):
            c.set(cx + s * (half + 7), top + 3, z + dz, B('polished_blackstone_stairs', facing=f, half='bottom', shape='straight', waterlogged=False))
            c.set(cx + s * (half + 6), top + 4, z + dz, B('polished_blackstone_slab', type='bottom', waterlogged=False))


# ==================== 封じの堂 ====================
#: 堂里的井：内部 x、z 3..9，心柱 5..7，墙在 2 与 10（建筑坐标，正面朝北）
WELL = (3, 9)
PILLAR = (5, 7)


def _wall_line(c, pts, y, block='stone_brick_wall'):
    """一排矮墙：按左右邻居连上，两头与转角立柱。"""
    s = set(pts)
    for (x, z) in pts:
        sides = {d: ('low' if (x + dx, z + dz) in s else 'none')
                 for d, (dx, dz) in (('north', (0, -1)), ('south', (0, 1)), ('west', (-1, 0)), ('east', (1, 0)))}
        straight = (sides['north'] != 'none' and sides['south'] != 'none' and sides['east'] == sides['west'] == 'none') or \
                   (sides['east'] != 'none' and sides['west'] != 'none' and sides['north'] == sides['south'] == 'none')
        c.set(x, y, z, B(block, up=not straight, waterlogged=False, **sides))


def gate_house():
    """封じの堂：13×13 地块，正面朝北。9×9 的小堂，白壁黑柱、四坡瓦顶，门口一座小鸟居、两盏石灯笼与一圈石玉垣；
    门洞三格宽，上挂注连绳，两旁与心柱上贴满御札。堂里没有地板：进门就是一条窄平台，绕着心柱往下的回旋石阶
    （地下部分在路线模板里）。地面以下、井的范围里这里一格也不放。"""
    c = Canvas()
    for x in range(13):
        for z in range(13):
            inside = 2 <= x <= 10 and 2 <= z <= 10
            if not inside:
                c.set(x, 0, z, B('moss_block') if _h(x, z, 3) % 5 == 0 else B('coarse_dirt'))
    for z in (0, 1):
        for x in (5, 6, 7):
            c.set(x, 0, z, B('stone_bricks') if (x + z) % 2 else B('mossy_stone_bricks'))
    # 墙：黑柱白壁，腰下一道木腰墙；门洞 5..7
    for y in range(1, 6):
        for x in range(2, 11):
            for z in range(2, 11):
                if not (x in (2, 10) or z in (2, 10)):
                    continue
                corner = x in (2, 10) and z in (2, 10)
                post = corner or (x in (2, 10) and z == 6) or (z in (2, 10) and x in (4, 8))
                if z == 2 and 5 <= x <= 7 and y <= 3:
                    continue
                if post or y == 5:
                    c.set(x, y, z, B('stripped_dark_oak_log', axis='y') if post else BEAM_X if z in (2, 10) else BEAM_Z)
                else:
                    c.set(x, y, z, WAINSCOT if y == 1 else PLASTER)
    for x in (5, 6, 7):
        c.set(x, 4, 2, BEAM_X)
    c.set(3, 3, 10, shoji_window('south'))
    c.set(9, 3, 10, shoji_window('south'))
    c.set(10, 3, 4, shoji_window('east'))
    c.set(2, 3, 8, shoji_window('west'))
    # 堂内：井口以上全是空的；心柱一直通到屋顶，缠一道注连绳
    c.clear(3, 1, 3, 9, 5, 9)
    c.box(5, 1, 5, 7, 6, 7, B('stripped_dark_oak_log', axis='y'))
    for (x, z, f) in ((6, 4, 'north'), (8, 6, 'east'), (6, 8, 'south'), (4, 6, 'west')):
        c.set(x, 3, z, L('journey_shimenawa', facing=f))
        c.set(x, 2, z, ofuda(f, kind=(x + z) % 3))
    c.set(5, 2, 4, ofuda('north', 1))
    c.set(7, 1, 4, ofuda('north', 2))
    c.set(8, 2, 5, ofuda('east', 0))
    # 进门另一侧的井口那一角立木栅（随回旋方向，放在路线模板里）
    for (x, z) in ((3, 9), (9, 9)):
        c.set(x, 5, z, paper_lantern(True))
    c.box(2, 6, 2, 10, 6, 10, DARK_PLANKS)
    c.box(5, 6, 5, 7, 6, 7, B('stripped_dark_oak_log', axis='y'))
    hip_roof(c, 2, 10, 2, 10, 6, eave=1)
    # 门洞：上挂注连绳，两旁贴御札
    for x in (5, 6, 7):
        c.set(x, 3, 2, L('journey_shimenawa', facing='north'))
    for (x, y) in ((4, 2), (4, 3), (8, 2), (8, 3), (3, 2), (9, 3)):
        c.set(x, y, 1, ofuda('north', kind=(x * 3 + y) % 3))
    # 门前：小鸟居、石灯笼、赛钱箱，一圈石玉垣
    fl.torii(c, 6, 1, 0, axis='x')
    for x in (1, 11):
        c.set(x, 1, 1, stone_lantern())
    pts = [(x, z) for x in (0, 12) for z in range(0, 13)] + [(x, 12) for x in range(1, 12)] + [(x, 0) for x in (1, 2, 10, 11)]
    _wall_line(c, [p for p in pts if c.get(p[0], 1, p[1]) is None], 1)
    return c


# ==================== 回旋石阶 ====================
_CORNERS = {'NE': [(8, 3), (9, 3), (8, 4), (9, 4)], 'SE': [(8, 8), (9, 8), (8, 9), (9, 9)],
            'SW': [(3, 8), (4, 8), (3, 9), (4, 9)], 'NW': [(3, 3), (4, 3), (3, 4), (4, 4)]}
#: 每条边：按行走方向排好的三级（每级两格）与上坡朝向
_SIDES = {
    True: [('E', [[(8, 5), (9, 5)], [(8, 6), (9, 6)], [(8, 7), (9, 7)]], 'north', 'SE'),
           ('S', [[(7, 8), (7, 9)], [(6, 8), (6, 9)], [(5, 8), (5, 9)]], 'east', 'SW'),
           ('W', [[(3, 7), (4, 7)], [(3, 6), (4, 6)], [(3, 5), (4, 5)]], 'south', 'NW'),
           ('N', [[(5, 3), (5, 4)], [(6, 3), (6, 4)], [(7, 3), (7, 4)]], 'west', 'NE')],
    False: [('W', [[(3, 5), (4, 5)], [(3, 6), (4, 6)], [(3, 7), (4, 7)]], 'north', 'SW'),
            ('S', [[(5, 8), (5, 9)], [(6, 8), (6, 9)], [(7, 8), (7, 9)]], 'west', 'SE'),
            ('E', [[(8, 7), (9, 7)], [(8, 6), (9, 6)], [(8, 5), (9, 5)]], 'south', 'NE'),
            ('N', [[(7, 3), (7, 4)], [(6, 3), (6, 4)], [(5, 3), (5, 4)]], 'east', 'NW')],
}
#: 井壁上出口的三格（建筑坐标的墙格）与朝外方向
_EXITS = {'E': ([(10, 5), (10, 6), (10, 7)], 'east', [(8, z) for z in (5, 6, 7)] + [(9, z) for z in (5, 6, 7)]),
          'W': ([(2, 5), (2, 6), (2, 7)], 'west', [(3, z) for z in (5, 6, 7)] + [(4, z) for z in (5, 6, 7)]),
          'N': ([(5, 2), (6, 2), (7, 2)], 'north', [(x, z) for x in (5, 6, 7) for z in (3, 4)]),
          'S': ([(5, 10), (6, 10), (7, 10)], 'south', [(x, z) for x in (5, 6, 7) for z in (8, 9)])}


def _ring():
    return [(x, z) for x in range(3, 10) for z in range(3, 10) if not (5 <= x <= 7 and 5 <= z <= 7)]


def shaft(depth, cw=True):
    """封じの堂下的回旋石阶（建筑坐标，y=0 为地面那层，井底在 -depth）。顺时针时进门右转往东边下，逆时针往西边下；
    入口平台在北边。返回画布。"""
    c = Canvas()
    bottom = -depth
    ring = _ring()
    for y in range(bottom, 1):
        deep = y < bottom + 13
        for x in range(2, 11):
            for z in range(2, 11):
                if x in (2, 10) or z in (2, 10):
                    k = _h(x, y, z, 11) % 10
                    if deep:
                        st = B('deepslate_bricks') if k < 5 else B('tuff_bricks') if k < 8 else B('cracked_deepslate_bricks')
                    else:
                        st = B('stone_bricks') if k < 6 else B('mossy_stone_bricks') if k < 9 else B('cracked_stone_bricks')
                    c.set(x, y, z, st)
        for x in range(5, 8):
            for z in range(5, 8):
                c.set(x, y, z, B('chiseled_stone_bricks') if (y - bottom) % 6 == 3 and (x, z) != (6, 6) else B('stone_bricks'))
        for (x, z) in ring:
            c.set(x, y, z, AIR)
    for (x, z) in ring:
        c.set(x, bottom, z, B('polished_andesite') if (x + z) % 2 else B('stone_bricks'))
    # 入口平台：北边一条加进门那头的角
    start = 'NE' if cw else 'NW'
    landing = [(x, z) for x in (5, 6, 7) for z in (3, 4)] + _CORNERS[start]
    for (x, z) in landing:
        c.set(x, 0, z, B('stone_bricks'))
        c.set(x, -1, z, B('stone_bricks'))
    # 平台另一头的那一角已经是井口：地面上一圈木栅挡着
    for (x, z) in _CORNERS['NW' if cw else 'NE']:
        c.set(x, 1, z, fence('dark_oak', north=z == 4, south=z == 3, west=x in (4, 9), east=x in (3, 8)))
    layer = 0
    i = 0
    done = False
    lamp = 0
    while not done:
        name, steps, up, corner = _SIDES[cw][i % 4]
        for step in steps:
            if layer <= bottom:
                done = True
                break
            for (x, z) in step:
                c.set(x, layer, z, B('stone_brick_stairs', facing=up, half='bottom', shape='straight', waterlogged=False))
                if layer - 1 > bottom:
                    c.set(x, layer - 1, z, B('stone_bricks'))
            layer -= 1
        if done or layer <= bottom:
            break
        for (x, z) in _CORNERS[corner]:
            c.set(x, layer, z, B('stone_bricks'))
            if layer - 1 > bottom:
                c.set(x, layer - 1, z, B('stone_bricks'))
        # 拐角平台最外一格放一盏灯：上面是油灯，越往下越冷，换成狐火
        ox, oz = {'NE': (9, 3), 'SE': (9, 9), 'SW': (3, 9), 'NW': (3, 3)}[corner]
        lamp += 1
        c.set(ox, layer + 1, oz, B('lantern' if layer > -14 else 'soul_lantern', hanging=False, waterlogged=False))
        i += 1
    # 井底四个外角各一盏狐火（不挡台阶）
    for (x, z) in ((3, 3), (9, 3), (3, 9), (9, 9)):
        if c.get(x, bottom + 1, z) == AIR:
            c.set(x, bottom + 1, z, B('soul_lantern', hanging=False, waterlogged=False))
    # 心柱上贴御札：只贴在空着的格子里（注连绳下、台阶之间）
    faces = [((6, 4), 'north'), ((8, 6), 'east'), ((6, 8), 'south'), ((4, 6), 'west'),
             ((5, 4), 'north'), ((7, 4), 'north'), ((8, 5), 'east'), ((8, 7), 'east'),
             ((5, 8), 'south'), ((7, 8), 'south'), ((4, 5), 'west'), ((4, 7), 'west')]
    for y in range(bottom + 2, 0):
        for (x, z), f in faces:
            if _h(x, y, z, 5) % 9 == 0 and c.get(x, y, z) == AIR and c.get(x, y - 1, z) == AIR:
                c.set(x, y, z, ofuda(f, kind=_h(x, y, z) % 3))
    return c


def exit_clear(c, depth, side, height=4):
    """井底往 side 一侧开口，开口前的那两排格子在通道高度里是不是空的（没有被最后几级台阶挡住）。"""
    bottom = -depth
    _, _, front = _EXITS[side]
    def passable(st):
        return st == AIR or st is not None and st['Name'].endswith('journey_ofuda')
    return all(passable(c.get(x, y, z)) for (x, z) in front for y in range(bottom + 1, bottom + 1 + height))


def open_exit(c, depth, side, height=4):
    bottom = -depth
    wall, _, _ = _EXITS[side]
    for (x, z) in wall:
        for y in range(bottom + 1, bottom + 1 + height):
            c.set(x, y, z, AIR)
        c.set(x, bottom, z, B('polished_andesite'))


# ==================== 旧地下通道 ====================
def corridor(c, path, forbid, y=CORRIDOR_Y):
    """沿一串轴向折线（中线格）铺一条 3 宽 4 高的地下通道：瓷砖墙、朱红腰线、平顶上一排海晶灯，每隔 9 格一座嵌墙的小鸟居。
    走得越深（离井越远）越破：瓷砖碎落、灯换成吊着的狐火、墙上贴满御札、角落结着蛛网。forbid 里的格子（井与大厅内部）不碰。"""
    cells = []          # (u, v, t, axis)：中线格，t 为走过的比例
    pts = []
    for (a, b) in zip(path, path[1:]):
        (u0, v0), (u1, v1) = a, b
        assert u0 == u1 or v0 == v1
        n = abs(u1 - u0) + abs(v1 - v0)
        du, dv = (u1 > u0) - (u1 < u0), (v1 > v0) - (v1 < v0)
        for k in range(n):
            pts.append((u0 + du * k, v0 + dv * k, 'u' if du else 'v'))
    pts.append((path[-1][0], path[-1][1], pts[-1][2]))
    total = len(pts)
    interior = {}
    for i, (u, v, axis) in enumerate(pts):
        t = i / max(1, total - 1)
        for s in (-1, 0, 1):
            cu_, cv_ = (u, v + s) if axis == 'u' else (u + s, v)
            if (cu_, cv_) not in interior:
                interior[(cu_, cv_)] = t
        cells.append((u, v, t, axis))
    walls = {}
    for (u, v), t in interior.items():
        for du in (-1, 0, 1):
            for dv in (-1, 0, 1):
                k = (u + du, v + dv)
                if k not in interior and k not in forbid:
                    walls[k] = min(walls.get(k, 1.0), t)
    for (u, v), t in interior.items():
        if (u, v) in forbid:
            continue
        r = _h(u, v, 7) % 100
        c.set(u, y, v, B('smooth_stone') if (u + v) % 2 else B('polished_andesite'))
        if t > 0.6 and r < 12:
            c.set(u, y, v, B('cracked_stone_bricks'))
        for k in range(1, 5):
            c.set(u, y + k, v, AIR)
        c.set(u, y + 5, v, B('smooth_stone'))
    for (u, v), t in walls.items():
        for k in range(0, 6):
            r = _h(u, k, v, 13) % 100
            if k in (0, 5):
                st = B('stone_bricks')
            elif k == 1:
                st = B('polished_blackstone_bricks') if r > t * 40 else B('cracked_polished_blackstone_bricks')
            elif k == 3:
                st = B('red_terracotta') if r > t * 55 else B('tuff')
            else:
                # 瓷砖：越往里碎得越多，露出后面的岩石
                st = B('quartz_bricks') if r > t * 70 else (B('cobbled_deepslate') if r % 3 else B('mossy_cobblestone'))
            c.set(u, y + k, v, st)
    # 灯、鸟居、御札、蛛网
    for i, (u, v, t, axis) in enumerate(cells):
        if (u, v) in forbid:
            continue
        straight = 0 < i < len(cells) - 1 and cells[i - 1][3] == axis == cells[i + 1][3]
        if i % 6 == 3:
            if t < 0.65:
                c.set(u, y + 5, v, B('sea_lantern'))
            elif i % 12 == 3:
                c.set(u, y + 4, v, B('soul_lantern', hanging=True, waterlogged=False))
        if straight and i % 9 == 5 and 4 < i < len(cells) - 5:
            for s in (-2, 2):
                pu, pv = (u, v + s) if axis == 'u' else (u + s, v)
                if (pu, pv) in walls:
                    for k in range(1, 5):
                        c.set(pu, y + k, pv, LACQUER_LOG)
                    c.set(pu, y + 5, pv, LACQUER_PLANKS)
            for s in (-1, 0, 1):
                pu, pv = (u, v + s) if axis == 'u' else (u + s, v)
                c.set(pu, y + 4, pv, lacquer_slab('top'))
                c.set(pu, y + 5, pv, LACQUER_PLANKS)
        if straight and t > 0.35:
            for s, f in ((-1, None), (1, None)):
                pu, pv = (u, v + s) if axis == 'u' else (u + s, v)
                wu, wv = (u, v + 2 * s) if axis == 'u' else (u + 2 * s, v)
                if (wu, wv) not in walls:
                    continue
                face = {(0, -1): 'south', (0, 1): 'north', (-1, 0): 'east', (1, 0): 'west'}[(wu - pu, wv - pv)]
                for k in (2, 3):
                    if _h(u, v, k, s) % 100 < (t - 0.35) * 90 and c.get(pu, y + k, pv) == AIR:
                        c.set(pu, y + k, pv, ofuda(face, kind=_h(u, v, k) % 3))
                if t > 0.7 and _h(u, v, s, 99) % 100 < 12 and c.get(pu, y + 4, pv) == AIR:
                    c.set(pu, y + 4, pv, B('cobweb'))
    # 出口拦一道注连绳
    u, v, t, axis = cells[-2]
    for s in (-1, 0, 1):
        pu, pv = (u, v + s) if axis == 'u' else (u + s, v)
        c.set(pu, y + 4, pv, L('journey_shimenawa', facing='north' if axis == 'v' else 'east'))
    return total


def _lanes(us):
    """井口在 u=us 时，沿大厅外侧南下的那条通道可以走哪几条：(lane, 进大厅那一格的 u)。
    西边的不超过 u=46、东边的不小于 130（离大厅岩壳两格），也不能穿过井本身（离井心至少 7 格，或者就从井的正南出去）。"""
    out = []
    west = us if us <= LANE_W else (LANE_W if us - LANE_W >= 7 else us - 8)
    east = us if us >= LANE_E else (LANE_E if LANE_E - us >= 7 else us + 8)
    out.append((west, HALL[0] - 1))
    out.append((east, HALL[2] + 1))
    return out


def route(lot_box, turns, y_lot):
    """一条路线：lot_box 为住宅地块的占地 (u0, v0, u1, v1)，turns 为地块旋转，y_lot 为地块地面高度（设计坐标）。
    返回 (画布, 信息)；走不通返回 None。"""
    u0, v0, u1, v1 = lot_box
    assert u1 - u0 == 12 and v1 - v0 == 12
    corners = [rotate_pos(x, z, turns) for x, z in ((0, 0), (12, 0), (0, 12), (12, 12))]
    mx, mz = min(p[0] for p in corners), min(p[1] for p in corners)

    def to_design(x, z):
        rx, rz = rotate_pos(x, z, turns)
        return u0 + rx - mx, v0 + rz - mz

    us, vs = to_design(6, 6)
    depth = y_lot - CORRIDOR_Y
    step = {'east': (1, 0), 'west': (-1, 0), 'north': (0, -1), 'south': (0, 1)}
    best = None
    for lane, entry in _lanes(us):
        want = 'south' if lane == us else 'east' if lane > us else 'west'
        for side in ('E', 'W', 'N', 'S'):
            d = rotate_dir(_EXITS[side][1], turns)
            if d == 'south' and want != 'south':
                continue  # 往南就是大厅：只有井正南的那条直路能往南出
            if want == 'south' and d != 'south':
                continue
            for cw in (True, False):
                sh = shaft(depth, cw)
                if not exit_clear(sh, depth, side):
                    continue
                wall_mid = to_design(*_EXITS[side][0][1])
                p1 = (wall_mid[0] + step[d][0] * 3, wall_mid[1] + step[d][1] * 3)
                path = [wall_mid, p1]
                if d != want and d != 'north':
                    path.append((p1[0], vs - 8))   # 朝反方向出来：绕到井北边再横着走
                path += [(lane, path[-1][1]), (lane, ENTRY_V), (entry, ENTRY_V)]
                clean = [path[0]]
                for p in path[1:]:
                    if p != clean[-1]:
                        clean.append(p)
                # 拐回头的折线（往东走又往西）不要
                ok = all(not (a[0] == b[0] == c_[0] and (b[1] - a[1]) * (c_[1] - b[1]) < 0) and
                         not (a[1] == b[1] == c_[1] and (b[0] - a[0]) * (c_[0] - b[0]) < 0)
                         for a, b, c_ in zip(clean, clean[1:], clean[2:]))
                if not ok:
                    continue
                length = sum(abs(a[0] - b[0]) + abs(a[1] - b[1]) for a, b in zip(clean, clean[1:]))
                if best is None or length < best[0]:
                    best = (length, side, cw, sh, d, clean)
    if best is None:
        return None
    _, side, cw, sh, design_dir, path = best
    open_exit(sh, depth, side)
    c = Canvas()
    shaft_cells = set()
    for (x, y, z), st in sh.cells.items():
        u, v = to_design(x, z)
        c.set(u, y_lot + y, v, rotate_state(st, turns))
        if 3 <= x <= 9 and 3 <= z <= 9:
            shaft_cells.add((u, v))
    hall_cells = {(u, v) for u in range(HALL[0], HALL[2] + 1) for v in range(HALL[1], HALL[3] + 1)}
    length = corridor(c, path, shaft_cells | hall_cells)
    info = {'shaft': [us, vs], 'exit': design_dir, 'cw': cw, 'path': [list(p) for p in path], 'length': length, 'depth': depth}
    return c, info


def _open(u, y, v):
    """这一格是不是挖空的（岩洞、门洞，或通道进大厅的口子）。"""
    for (a0, b0, c0, a1, b1, c1) in carve_boxes():
        if a0 <= u <= a1 and c0 <= v <= c1 and b0 <= y <= b1:
            return True
    return u in (HALL[0] - 1, HALL[2] + 1) and abs(v - ENTRY_V) <= 1 and CORRIDOR_Y < y <= CORRIDOR_Y + 4


def cave_glow(c, box, top, rnd, patches):
    """岩洞四壁与岩顶零星长着发光地衣（7 级光）：一小片两到五格，贴在岩面上；格子已经有东西就跳过。"""
    u0, v0, u1, v1 = box
    faces = []
    for y in range(FLOOR_Y + 2, top + 1):
        for u in range(u0, u1 + 1):
            faces.append((u, y, v0, 'north'))
            faces.append((u, y, v1, 'south'))
        for v in range(v0, v1 + 1):
            faces.append((u0, y, v, 'west'))
            faces.append((u1, y, v, 'east'))
    for u in range(u0, u1 + 1):
        for v in range(v0, v1 + 1):
            faces.append((u, top, v, 'up'))
    for _ in range(patches):
        u, y, v, f = rnd.choice(faces)
        for _k in range(rnd.randint(2, 5)):
            du, dy, dv = rnd.randint(-1, 1), rnd.randint(-1, 1), rnd.randint(-1, 1)
            if f in ('north', 'south'):
                dv = 0
            elif f in ('west', 'east'):
                du = 0
            else:
                dy = 0
            pu, py, pv = u + du, y + dy, v + dv
            if not (u0 <= pu <= u1 and v0 <= pv <= v1 and FLOOR_Y + 2 <= py <= top) or c.get(pu, py, pv) is not None:
                continue
            bu, by, bv = pu + {'west': -1, 'east': 1}.get(f, 0), py + (1 if f == 'up' else 0), pv + {'north': -1, 'south': 1}.get(f, 0)
            if _open(bu, by, bv):
                continue  # 背后是门洞或通道口，没有岩面可贴
            props = {d: d == f for d in ('north', 'south', 'east', 'west', 'up', 'down')}
            c.set(pu, py, pv, B('glow_lichen', waterlogged=False, **props))


# ==================== 大鸟居之间 ====================
def hall(c, rnd):
    y = FLOOR_Y
    u0, v0, u1, v1 = HALL
    tv0, tv1 = TERRACE_V
    # 黑水：参道两旁，四角收圆一点
    for u in range(u0 + 1, u1):
        for v in range(PARAPET_V + 2, v1):
            if 85 <= u <= 91:
                continue
            if v >= 88 and 76 <= u <= 100:
                continue  # 楼门前的石板广场
            edge = min(u - u0, u1 - u, v - PARAPET_V - 1, v1 - v)
            if edge <= 1 and _h(u, v, 1) % 3 == 0:
                continue
            c.set(u, y, v, WATER)
            c.set(u, y - 1, v, DARK_BOTTOM[_h(u, v) % 4])
    # 参道与正中平台、楼门前广场
    for u in range(85, 92):
        for v in range(tv0, v1 + 1):
            c.set(u, y, v, B('stone_bricks') if 86 <= u <= 90 else B('polished_andesite'))
    for u in range(76, 101):
        for v in range(88, v1 + 1):
            if not 85 <= u <= 91:
                c.set(u, y, v, B('polished_andesite') if (u + v) % 3 else B('stone_bricks'))
    # 北壁的台阶带：两头平台，大石阶从两头往正中下；下面整个砌实，前沿一道随台阶升降的石栏
    def column(u, top):
        for v in range(tv0, PARAPET_V + 1):
            for yy in range(y, top):
                k = _h(u, yy, v, 21) % 10
                c.set(u, yy, v, B('stone_bricks') if k < 7 else B('mossy_stone_bricks') if k < 9 else B('cracked_stone_bricks'))
    for (b0, b1) in (BALCONY_W, BALCONY_E):
        for u in range(b0, b1 + 1):
            column(u, CORRIDOR_Y)
            for v in range(tv0, tv1 + 1):
                c.set(u, CORRIDOR_Y, v, B('polished_andesite') if (u + v) % 2 else B('stone_bricks'))
            c.set(u, CORRIDOR_Y, PARAPET_V, B('stone_bricks'))
            c.set(u, CORRIDOR_Y + 1, PARAPET_V, B('chiseled_stone_bricks') if u in (b0, b1) else B('stone_bricks'))
        c.set(b0 + 2, CORRIDOR_Y + 2, PARAPET_V, stone_lantern())
    n = BALCONY_E[0] - 1 - CU - 3   # 每边石阶级数
    for k in range(n + 1):
        layer = CORRIDOR_Y - k
        if layer <= y:
            break
        for u, f in ((BALCONY_W[1] + 1 + k, 'west'), (BALCONY_E[0] - 1 - k, 'east')):
            column(u, layer)
            for v in range(tv0, tv1 + 1):
                c.set(u, layer, v, B('stone_brick_stairs', facing=f, half='bottom', shape='straight', waterlogged=False))
            c.set(u, layer, PARAPET_V, B('stone_bricks'))
            c.set(u, layer + 1, PARAPET_V, B('chiseled_stone_bricks') if k % 7 == 3 else B('stone_bricks'))
            if k % 7 == 3:
                c.set(u, layer + 2, PARAPET_V, stone_lantern())
    for u in range(85, 92):
        for v in range(tv0, PARAPET_V + 1):
            c.set(u, y, v, B('polished_andesite') if (u + v) % 2 else B('stone_bricks'))
    # 平台进口两侧的岩壁上贴御札
    for (u, f) in ((u0, 'east'), (u1, 'west')):
        for v in (tv0, tv1):
            for k in (1, 2, 3):
                c.set(u, CORRIDOR_Y + k, v, ofuda(f, kind=(k + v) % 3))
    # 大鸟居：柱子立在黑水里，柱下垫石
    tz = 85
    for s in (-9, 9):
        for du in range(-2, 3):
            for dv in range(-2, 3):
                c.set(CU + s + du, y, tz + dv, B('stone_bricks'))
                c.set(CU + s + du, y - 1, tz + dv, B('stone_bricks'))
    giant_torii(c, CU, y + 1, tz)
    # 参道两旁的高石灯笼
    for v in (79, 83, 89, 93):
        for u in (84, 92):
            c.set(u, y, v, B('stone_bricks'))
            c.set(u, y + 1, v, tall_lantern('lower'))
            c.set(u, y + 2, v, tall_lantern('upper'))
    gate(c)
    # 岩顶垂下的钟乳石与几盏吊着的狐火
    for i in range(46):
        u = rnd.randint(u0 + 2, u1 - 2)
        v = rnd.randint(v0 + 6, v1 - 2)
        if 70 <= u <= 106 and 82 <= v <= 89:
            continue  # 别挡大鸟居
        n_ = rnd.choice((1, 2, 2, 3, 3, 4))
        top = HALL_TOP
        c.set(u, top, v, B('dripstone_block'))
        seq = {1: ['tip'], 2: ['frustum', 'tip'], 3: ['base', 'frustum', 'tip'], 4: ['base', 'middle', 'frustum', 'tip']}[n_]
        for k, th in enumerate(seq):
            c.set(u, top - 1 - k, v, B('pointed_dripstone', thickness=th, vertical_direction='down', waterlogged=False))
    for (u, v) in ((64, 82), (112, 82), (70, 92), (106, 92), (60, 90), (116, 90), (58, 80), (118, 80), (74, 79), (102, 79),
                   (66, 93), (110, 93)):
        drop = 6 + (u * 7 + v) % 5
        for yy in range(HALL_TOP - drop, HALL_TOP + 1):
            c.set(u, yy, v, B('chain', axis='y', waterlogged=False))
        c.set(u, HALL_TOP - drop - 1, v, B('soul_lantern', hanging=True, waterlogged=False))
    # 台阶脚下、黑水北岸一排石灯笼
    for u in list(range(56, 84, 6)) + list(range(96, 122, 6)):
        c.set(u, y + 1, PARAPET_V + 1, stone_lantern())
    cave_glow(c, HALL, HALL_TOP, rnd, 110)


def gate(c):
    """楼门：贴在大厅南壁上，两层瓦檐，朱柱白壁；门洞 7 宽 12 高，门扇向里推开贴着门洞两壁，扇上的封条已经撕开。"""
    y = FLOOR_Y
    fv, bv = 92, HALL[3]   # 门脸与贴墙的一排
    posts = (76, 80, 84, 92, 96, 100)
    top1 = y + 16
    for u in range(76, 101):
        for v in range(fv, bv + 1):
            for yy in range(y + 1, top1 + 1):
                if 85 <= u <= 91 and yy <= y + 12:
                    c.set(u, yy, v, AIR)
                elif v not in (fv, bv) and u not in (76, 100):
                    c.set(u, yy, v, DARK_PLANKS if yy == top1 else AIR)
                elif u in posts:
                    c.set(u, yy, v, LACQUER_LOG if yy < top1 else LACQUER_PLANKS)
                elif yy in (top1, y + 13):
                    c.set(u, yy, v, LACQUER_PLANKS)
                elif yy <= y + 2:
                    c.set(u, yy, v, WAINSCOT)
                else:
                    c.set(u, yy, v, PLASTER)
    # 门上的黑匾
    for u in range(86, 91):
        for yy in (y + 14, y + 15):
            c.set(u, yy, fv, B('polished_blackstone_bricks'))

    def pent(u0, u1, base, rows):
        """贴着岩壁的单坡檐：从南壁（base 层）往北一排排下降，下面空着的地方填暗色木板。"""
        for k in range(rows):
            v = bv - k
            layer = base - (k + 1) // 2
            for u in range(u0, u1 + 1):
                c.set(u, layer, v, tile_stairs('south') if k % 2 == 0 else tile_slab('top'))
        return base - rows // 2

    # 一层檐：从门脸顶上往北出挑三格
    pent(74, 102, top1 + 4, 7)
    for u in range(76, 101):
        for v in range(fv, bv + 1):
            for yy in range(top1 + 1, top1 + 4):
                if c.get(u, yy, v) is None:
                    c.set(u, yy, v, DARK_PLANKS)
    # 二层：退到贴墙的两排，窄一些
    top2 = top1 + 11
    for u in range(80, 97):
        for v in (bv - 1, bv):
            for yy in range(top1 + 4, top2 + 1):
                if c.get(u, yy, v) not in (None, AIR):
                    continue
                if u in (80, 84, 88, 92, 96):
                    c.set(u, yy, v, LACQUER_LOG if yy < top2 else LACQUER_PLANKS)
                elif yy == top2:
                    c.set(u, yy, v, LACQUER_PLANKS)
                elif yy in (top1 + 7, top1 + 8) and v == bv - 1:
                    c.set(u, yy, v, shoji_window('north'))
                else:
                    c.set(u, yy, v, PLASTER)
    pent(78, 98, top2 + 3, 6)
    for u in range(80, 97):
        for v in (bv - 1, bv):
            for yy in range(top2 + 1, top2 + 3):
                if c.get(u, yy, v) is None:
                    c.set(u, yy, v, DARK_PLANKS)
    # 檐下两盏大纸灯笼，门前一对狐像
    for u in (81, 95):
        c.set(u, top1 + 1, fv - 1, paper_lantern(True))
    fl.fox_pair(c, CU, y + 1, 89, axis='x', gap=12)
    # 门洞穿过岩壁：门扇推开贴在两壁，扇上铁条与撕开的封条
    for v in range(GATE[1], GATE[3] + 1):
        for u in range(GATE[0], GATE[2] + 1):
            c.set(u, y, v, B('polished_blackstone_bricks'))
        for u, f in ((GATE[0], 'east'), (GATE[2], 'west')):
            for yy in range(y + 1, y + 13):
                c.set(u, yy, v, B('polished_blackstone') if yy in (y + 2, y + 7, y + 11) else B('dark_oak_planks'))
            iu = u + (1 if f == 'east' else -1)
            for yy in (y + 3, y + 4, y + 9):
                if (v + yy) % 2 == 0:
                    c.set(iu, yy, v, ofuda(f, kind=(v + yy) % 3))
    # 封印之间那头：一道朱漆门框，框上的注连绳；门框脚下铺一块石板地
    av = ARENA[1]
    for u in range(GATE[0] - 3, GATE[2] + 4):
        for v in range(av, av + 3):
            c.set(u, y, v, B('polished_blackstone_bricks') if GATE[0] <= u <= GATE[2] else B('polished_blackstone'))
    for u in (GATE[0] - 1, GATE[2] + 1):
        for yy in range(y + 1, y + 14):
            c.set(u, yy, av, LACQUER_LOG)
    for u in range(GATE[0] - 2, GATE[2] + 3):
        c.set(u, y + 14, av, LACQUER_PLANKS)
    for u in range(GATE[0], GATE[2] + 1):
        c.set(u, y + 13, av, L('journey_shimenawa', facing='north'))


# ==================== 封印之间 ====================
def seal_positions():
    """九根封印杭的位置（u, v），自正北（楼门一侧）起每 40 度一根。"""
    out = []
    for k in range(9):
        a = math.radians(40 * k)
        out.append((CU + round(SEAL_R * math.sin(a)), ARENA_CV - round(SEAL_R * math.cos(a))))
    return out


PILLARS = [(u, v) for u in (46, 127) for v in (104, 120, 136, 152)]


def arena(c, rnd):
    y = FLOOR_Y
    u0, v0, u1, v1 = ARENA
    seals = seal_positions()
    ray = set()
    for (su, sv) in seals:
        for r in range(7, SEAL_R - 1):
            f = r / SEAL_R
            ray.add((round(CU + (su - CU) * f), round(ARENA_CV + (sv - ARENA_CV) * f)))
    for u in range(u0, u1 + 1):
        for v in range(v0, v1 + 1):
            d = math.hypot(u - CU, v - ARENA_CV)
            pillar = any(pu - 1 <= u <= pu + 4 and pv - 1 <= v <= pv + 4 for (pu, pv) in PILLARS)
            causeway = 85 <= u <= 91 and v < ARENA_CV - PLATFORM_R + 2
            if d <= PLATFORM_R:
                if d > PLATFORM_R - 1.2:
                    st = B('polished_blackstone_bricks')
                elif (u, v) in ray:
                    st = B('chiseled_deepslate')
                elif abs(d - SEAL_R) < 0.8:
                    st = B('deepslate_tiles')
                elif d < 12:
                    st = B('deepslate_tiles') if (u + v) % 2 else B('polished_deepslate')
                else:
                    st = B('polished_deepslate') if _h(u, v, 4) % 7 else B('deepslate_bricks')
                c.set(u, y, v, st)
            elif causeway:
                c.set(u, y, v, B('polished_blackstone_bricks') if 86 <= u <= 90 else B('polished_blackstone'))
            elif pillar:
                c.set(u, y, v, B('polished_andesite'))
            else:
                c.set(u, y, v, WATER)
                c.set(u, y - 1, v, DARK_BOTTOM[_h(u, v, 2) % 4])
    # 封印杭：三格见方的黑石台，杭立正中，两角各一盏狐火
    for i, (su, sv) in enumerate(seals):
        for du in (-1, 0, 1):
            for dv in (-1, 0, 1):
                c.set(su + du, y + 1, sv + dv, B('chiseled_polished_blackstone') if du == dv == 0 else B('polished_blackstone_bricks'))
        c.set(su, y + 2, sv, seal_stake('lower'))
        c.set(su, y + 3, sv, seal_stake('upper'))
        a = math.atan2(su - CU, -(sv - ARENA_CV))
        # 两盏灯放在杭的左右（沿圆周方向）
        tu, tv = round(math.cos(a)), round(math.sin(a))
        for s in (-1, 1):
            c.set(su + s * tu, y + 2, sv + s * tv, B('soul_lantern', hanging=False, waterlogged=False))
    # 正中的石台：两级
    for u in range(CU - 7, CU + 8):
        for v in range(ARENA_CV - 7, ARENA_CV + 8):
            d = math.hypot(u - CU, v - ARENA_CV)
            if d <= 5:
                c.set(u, y + 1, v, B('polished_blackstone'))
            elif d <= 6.2:
                c.set(u, y + 1, v, B('polished_blackstone_slab', type='bottom', waterlogged=False))
            if d <= 3.6:
                c.set(u, y + 2, v, B('polished_blackstone'))
            elif d <= 4.8:
                c.set(u, y + 2, v, B('polished_blackstone_slab', type='bottom', waterlogged=False))
    sessho_seki(c, CU, y + 3, ARENA_CV)
    # 圆台边上一圈高石灯笼（楼门那头的参道口空着）
    for k in range(12):
        a = math.radians(15 + 30 * k)
        lu, lv = CU + round((PLATFORM_R - 2) * math.sin(a)), ARENA_CV - round((PLATFORM_R - 2) * math.cos(a))
        if abs(lu - CU) <= 4 and lv < ARENA_CV:
            continue
        c.set(lu, y + 1, lv, tall_lantern('lower'))
        c.set(lu, y + 2, lv, tall_lantern('upper'))
    # 巨柱：4×4，柱脚与两道雕纹腰带，朝里那面两盏吊着的狐火，柱脚贴满御札
    for (pu, pv) in PILLARS:
        for u in range(pu - 1, pu + 5):
            for v in range(pv - 1, pv + 5):
                c.set(u, y + 1, v, B('polished_andesite'))
        for u in range(pu, pu + 4):
            for v in range(pv, pv + 4):
                for yy in range(y + 1, ARENA_TOP + 1):
                    band = yy in (y + 2, y + 10, ARENA_TOP - 4)
                    c.set(u, yy, v, B('chiseled_stone_bricks') if band else B('stone_bricks'))
        inward = 'east' if pu < CU else 'west'
        fu = pu + 4 if pu < CU else pu - 1
        for v in (pv + 1, pv + 2):
            c.set(fu, y + 14, v, B('stone_brick_slab', type='top', waterlogged=False))
            c.set(fu, y + 13, v, B('soul_lantern', hanging=True, waterlogged=False))
        for fv in (pv - 1, pv + 4):
            c.set(pu + 1, y + 14, fv, B('stone_brick_slab', type='top', waterlogged=False))
            c.set(pu + 1, y + 13, fv, B('soul_lantern', hanging=True, waterlogged=False))
        for v in range(pv, pv + 4):
            for yy in range(y + 3, y + 7):
                if _h(pu, v, yy) % 3 and c.get(fu, yy, v) is None:
                    c.set(fu, yy, v, ofuda(inward, kind=_h(v, yy) % 3))
    # 柱间拉注连绳（沿 v），挂在两柱之间
    for pu in (46, 127):
        u = pu + 2
        for (a, b) in ((107, 120), (123, 136), (139, 152)):
            for v in range(a + 1, b):
                c.set(u, y + 18, v, L('journey_shimenawa', facing='east'))
    # 岩顶倒挂的町屋街：东西两排，正面朝向圆台；还有一座倒挂的鸟居在南头
    makers = [m for k, m in sorted(fb.SHOP_VARIANTS.items()) if 'izakaya' not in k]
    for row, (ou, turns) in enumerate(((51, 1), (113, 3))):
        for i, v in enumerate(range(102, 150, 11)):
            house = makers[(i * 3 + row * 5) % len(makers)]()
            before = set(c.cells)
            paste_inverted(c, house, ou, ARENA_TOP, v, turns)
            # 倒挂的屋脊下吊一盏狐火
            mine = [k for k in c.cells if k not in before]
            if mine:
                low = min(k[1] for k in mine)
                cu_ = sum(k[0] for k in mine) // len(mine)
                cv_ = v + 4
                col = [k[1] for k in mine if k[0] == cu_ and k[2] == cv_]
                bottom = min(col) if col else low
                for yy in (bottom - 1, bottom - 2):
                    c.set(cu_, yy, cv_, B('chain', axis='y', waterlogged=False))
                c.set(cu_, bottom - 3, cv_, B('soul_lantern', hanging=True, waterlogged=False))
    cave_glow(c, ARENA, ARENA_TOP, rnd, 170)
    t = Canvas()
    giant_torii(t, CU, 0, 0, post_h=14, half=7)
    for (x, yy, z), st in t.cells.items():
        out = flip_y(st)
        if out is not None:
            c.set(x, ARENA_TOP - yy, 156 + z, out)


def sessho_seki(c, cx, y0, cz):
    """杀生石：一块歪斜的黑色巨岩，约 8×7 见方、11 格高，自上而下从正中裂开（裂缝沿 v），缝底是灵魂土上的狐火；
    半腰缠一道干草捆搓成的粗注连绳，绳下挂着纸垂。"""
    rnd = random.Random(9)
    stone = {}
    for k in range(11):
        t = k / 10
        rx = 4.2 * (1 - 0.55 * t ** 1.6)
        rz = 3.6 * (1 - 0.5 * t ** 1.5)
        lean = 0.9 * t
        for u in range(cx - 6, cx + 7):
            for v in range(cz - 6, cz + 7):
                du, dv = (u - cx - lean) / rx, (v - cz) / rz
                wob = 0.12 * math.sin(u * 1.7 + k * 0.9) + 0.1 * math.cos(v * 1.3 - k)
                if du * du + dv * dv <= 1 + wob:
                    stone[(u, y0 + k, v)] = True
    mats = [B('blackstone'), B('basalt', axis='y'), B('tuff'), B('deepslate'), B('smooth_basalt'), B('blackstone')]
    for (u, y, v) in stone:
        crack = u == cx and y >= y0 and abs(v - cz) <= 3
        if crack and y > y0:
            c.set(u, y, v, AIR)
            continue
        c.set(u, y, v, mats[_h(u, y, v, 31) % len(mats)])
    for v in range(cz - 3, cz + 4):
        if (cx, y0, v) in stone:
            c.set(cx, y0, v, B('soul_soil'))
            c.set(cx, y0 + 1, v, B('soul_fire'))
    # 注连绳：半腰一圈紧贴石面的干草捆
    ry = y0 + 5
    ring = []
    for u in range(cx - 7, cx + 8):
        for v in range(cz - 7, cz + 8):
            if (u, ry, v) in stone:
                continue
            if any((u + du, ry, v + dv) in stone for du, dv in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                ring.append((u, v))
    for (u, v) in ring:
        axis = 'x' if any((u, ry, v + dv) in stone for dv in (1, -1)) else 'z'
        c.set(u, ry, v, B('hay_block', axis=axis))
    for (u, v) in ring:
        if (u + v) % 3:
            continue
        for f, (du, dv) in (('north', (0, 1)), ('south', (0, -1)), ('west', (1, 0)), ('east', (-1, 0))):
            if (u + du, ry, v + dv) in stone:
                c.set(u, ry - 1, v, L('journey_shimenawa', facing='north' if f in ('north', 'south') else 'east'))
                break


# ==================== 输出 ====================
def vault_canvas():
    """大鸟居之间与封印之间（每套方案共用）。"""
    c = Canvas()
    rnd = random.Random(1145)
    hall(c, rnd)
    arena(c, rnd)
    return c


def carve_boxes():
    """要挖空的范围（u0, y0, v0, u1, y1, v1，含两端），Java 的岩洞部件按它挖、外面包一层岩壳。"""
    return [
        [HALL[0], FLOOR_Y + 1, HALL[1], HALL[2], HALL_TOP, HALL[3]],
        [ARENA[0], FLOOR_Y + 1, ARENA[1], ARENA[2], ARENA_TOP, ARENA[3]],
        [GATE[0], FLOOR_Y + 1, GATE[1], GATE[2], GATE_TOP, GATE[3]],
    ]
