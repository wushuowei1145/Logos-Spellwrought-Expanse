"""神狐聚落的建筑设计：街边商铺（町家）、住宅、仓与小屋。

每个函数返回一个局部画布：x 向东、z 向南、正面朝北（z=0 临街），y=0 为地面层。
同一类地块的变体共用占地尺寸，由结构随机挑选。
"""
from fox_kit import *  # noqa: F401,F403

SHOP_W, SHOP_D = 9, 13


def _floor(c, x0, x1, z0, z1, y, state):
    c.box(x0, y, z0, x1, y, z1, state)


def machiya(c, noren_color='indigo', two_storey=True, goods=None, bench=False, lattice=True, sign=None, agari=True):
    """临街町家：一层店面带格子与暖帘，二层低矮、退后一格，屋脊平行于街道。两层的店靠东墙有楼梯，楼上是店主的卧室。

    占地 9×13：z=0 为檐下石板走廊，z=1..10 为房屋，z=11..12 为后院。agari=False 时整层都是土间（居酒屋）。"""
    W = SHOP_W - 1
    front, back = 1, 10
    # 地基与地面：前半为土间，后半抬高铺榻榻米
    _floor(c, 0, W, 0, 0, 0, PAVING)
    c.box(0, 0, front, W, 0, back, STONE_BASE)
    _floor(c, 1, W - 1, front + 1, 5, 0, B('packed_mud'))
    _floor(c, 1, W - 1, 6, back - 1, 0, FLOOR if agari else B('packed_mud'))
    interior_air(c, 1, W - 1, front + 1, back - 1, 1, 4)
    if agari:
        # 后半是半格高的小上がり（不设门，进出只跨半格）
        for x in range(1, W):
            for z in range(6, back):
                c.set(x, 1, z, TATAMI_SLAB)
    # 两侧与后墙：真壁造
    timber_wall(c, 0, 0, front, back, 1, 4, posts_every=3)
    timber_wall(c, W, W, front, back, 1, 4, posts_every=3)
    timber_wall(c, 0, W, back, back, 1, 4, posts_every=4)
    c.set(3, 3, back, shoji_window('south'))
    c.set(5, 3, back, shoji_window('south'))
    # 店面：柱、门洞、格子
    for x in range(0, W + 1):
        c.set(x, 4, front, BEAM_X)
    for x in (0, 2, 6, W):
        c.box(x, 1, front, x, 3, front, POST)
    door = range(3, 6)
    for x in range(0, W + 1):
        if x in (0, 2, 6, W) or x in door:
            continue
        if lattice:
            c.set(x, 1, front, WAINSCOT)
            c.set(x, 2, front, trapdoor('north', wood='spruce'))
            c.set(x, 3, front, trapdoor('north', wood='spruce'))
        else:
            c.set(x, 1, front, WAINSCOT)
    for x in door:
        c.set(x, 3, front, noren('north', noren_color))
    if two_storey:
        # 檐下纸灯笼，挂在一层披檐下
        c.set(1, 4, 0, paper_lantern(True))
        c.set(W - 1, 4, 0, paper_lantern(True))
        for x in range(0, W + 1):
            c.set(x, 5, 0, tile_stairs('south'))
            c.set(x, 5, front, tile_slab('bottom'))
        # 二层：退后一格，较矮，虫笼窗
        z2 = front + 1
        _floor(c, 0, W, front, back, 5, DARK_PLANKS)
        for x in range(0, W + 1):
            c.set(x, 5, 0, tile_stairs('south'))
        timber_wall(c, 0, W, z2, z2, 6, 8, posts_every=4, wainscot=0)
        timber_wall(c, 0, 0, z2, back, 6, 8, posts_every=4, wainscot=0)
        timber_wall(c, W, W, z2, back, 6, 8, posts_every=4, wainscot=0)
        timber_wall(c, 0, W, back, back, 6, 8, posts_every=4, wainscot=0)
        for x in (2, 3, 5, 6):
            c.set(x, 7, z2, shoji_window('north'))
        interior_air(c, 1, W - 1, z2 + 1, back - 1, 6, 8)
        gable_roof(c, 0, W, z2, back, 8, ridge_axis='x', eave=1)
    else:
        # 平屋：屋面直接从墙顶出檐，灯笼挂在檐下
        _floor(c, 1, W - 1, front + 1, back - 1, 5, DARK_PLANKS)
        hip_roof(c, 0, W, front, back, 4, eave=1)
        c.set(1, 3, 0, paper_lantern(True))
        c.set(W - 1, 3, 0, paper_lantern(True))
    if bench:
        # 檐下长凳：云杉台阶铺红毡
        for x in (1, 7):
            c.set(x, 1, 0, spruce_slab('bottom'))
    if goods:
        goods(c)
    if two_storey:
        _shop_upstairs(c, W, back)
    # 檐下两角：一边立一面幟（nobori），一边摆盆栽或酒桶，各家不同
    import random as _random
    rnd = _random.Random(sum(map(ord, noren_color + (goods.__name__ if goods else ''))) + two_storey * 7)
    banner = rnd.choice(('red_banner', 'white_banner', 'blue_banner', 'orange_banner'))
    c.set(0, 1, 0, B(banner, rotation=8))
    if rnd.random() < 0.5:
        c.set(W, 1, 0, B(rnd.choice(('potted_azalea_bush', 'potted_bamboo', 'potted_fern', 'potted_cherry_sapling'))))
    else:
        c.set(W, 1, 0, B('barrel', facing='up', open=False))
        c.set(W, 2, 0, B(rnd.choice(('potted_azalea_bush', 'potted_flowering_azalea_bush', 'flower_pot'))))
    # 后院：碎石地与小柴堆
    _floor(c, 0, W, 11, 12, 0, B('coarse_dirt'))
    c.set(1, 1, 12, B('barrel', facing='up', open=False))
    c.set(W - 1, 1, 12, B('spruce_log', axis='x'))
    c.set(W - 2, 1, 12, B('spruce_log', axis='x'))
    return c


def _shop_upstairs(c, W, back):
    """靠东墙一跑楼梯（自后向前上），楼上东边留走道，其余铺榻榻米半砖，嵌一张床，矮桌、柜与纸灯笼。"""
    x = W - 1
    for k in range(5):
        z = back - 1 - k
        c.clear(x, 1 + k, z, x, 4 + k, z)
        c.set(x, 1 + k, z, B('spruce_stairs', facing='north', half='bottom', shape='straight', waterlogged=False))
    fence_line(c, [(x - 1, z) for z in range(back - 4, back)], 6, wood='spruce')
    bed(c, 2, 6, back - 2, 'west')
    kotatsu(c, 3, 6, 5)
    c.set(1, 6, back - 1, B('chest', facing='east', type='single', waterlogged=False))
    c.set(x - 1, 6, 3, paper_lantern(False))
    zashiki(c, 1, 3, x - 2, back - 1, 6)


def _counter(c, x0, x1, z, top=None):
    for x in range(x0, x1 + 1):
        c.set(x, 1, z, B('spruce_slab', type='top', waterlogged=False) if top is None else top)


def goods_tea(c):
    _counter(c, 2, 6, 5)
    c.set(2, 2, 5, B('flower_pot'))
    c.set(6, 2, 5, B('decorated_pot', facing='north', cracked=False, waterlogged=False))
    c.set(1, 1, 2, B('barrel', facing='up', open=False))
    c.set(7, 1, 2, B('spruce_stairs', facing='west', half='bottom', shape='straight', waterlogged=False))


def goods_noodle(c):
    _counter(c, 1, 6, 4)  # 东边留一格，往后走得到楼梯
    c.set(1, 1, 8, B('smoker', facing='north', lit=True))
    c.set(2, 1, 8, B('cauldron'))
    c.set(7, 2, 4, B('flower_pot'))
    for x in (2, 4, 6):
        c.set(x, 1, 2, B('spruce_stairs', facing='south', half='bottom', shape='straight', waterlogged=False))


def goods_sweets(c):
    _counter(c, 2, 6, 4, B('dark_oak_slab', type='top', waterlogged=False))
    for x in (2, 4, 6):
        c.set(x, 2, 4, B('cake', bites=0) if x == 4 else B('flower_pot'))
    c.set(1, 1, 8, B('barrel', facing='up', open=False))


def goods_sake(c):
    for x in (1, 2):
        for z in (2, 3):
            c.set(x, 1, z, B('barrel', facing='west', open=False))
            c.set(x, 2, z, B('barrel', facing='west', open=False))
    _counter(c, 4, 7, 5)
    # 杉玉：檐下挂一团深绿
    c.set(4, 4, 0, B('spruce_leaves', distance=1, persistent=True, waterlogged=False))


def goods_general(c):
    for x in range(1, 8):
        c.set(x, 1, 9 if x % 2 else 9, B('chiseled_bookshelf', facing='north') if x % 3 == 0 else B('barrel', facing='north', open=False))
    _counter(c, 2, 6, 5)
    c.set(3, 2, 5, B('flower_pot'))
    c.set(1, 1, 2, B('composter', level=0))


def goods_cloth(c):
    colors = ['red_wool', 'light_blue_wool', 'white_wool', 'pink_wool', 'blue_wool', 'yellow_wool']
    for i, x in enumerate(range(1, 8)):
        c.set(x, 1, 9, B(colors[i % len(colors)]))
        c.set(x, 2, 9, B(colors[(i + 2) % len(colors)]))
    _counter(c, 2, 6, 5)


COUNTER_TOP = B('dark_oak_slab', type='top', waterlogged=False)
STOOL = B('spruce_slab', type='bottom', waterlogged=False)


def _kitchen(c, cells):
    """灶台一排：烟熏炉、炼药锅、木桶、高炉、箱子依次排开。"""
    for (x, z, f), st in zip(cells, (B('smoker', facing='{}', lit=True), B('cauldron'), B('barrel', facing='up', open=False),
                                     B('blast_furnace', facing='{}', lit=False), B('chest', facing='{}', type='single', waterlogged=False))):
        st = dict(st)
        if 'Properties' in st and st['Properties'].get('facing') == '{}':
            st['Properties'] = dict(st['Properties'], facing=f)
        c.set(x, 1, z, st)


def goods_izakaya_u(c):
    """居酒屋（平屋）：コ字形吧台围着厨师，灶台靠后墙，客人坐在吧台外的圆凳上；吧台上方垂菜单条，头顶一串纸灯笼。"""
    for x in range(2, 7):
        c.set(x, 1, 4, COUNTER_TOP)
    for z in range(5, 9):
        c.set(2, 1, z, COUNTER_TOP)
        if z < 8:  # 东侧短一格，厨师从这里进吧台
            c.set(6, 1, z, COUNTER_TOP)
    _kitchen(c, [(3, 9, 'north'), (4, 9, 'north'), (5, 9, 'north'), (1, 9, 'north'), (7, 9, 'north')])
    for x in (3, 5):
        c.set(x, 1, 3, STOOL)
    for z in (5, 7):
        c.set(1, 1, z, STOOL)
        c.set(7, 1, z, STOOL)
    for x in range(2, 7):
        menu_strips(c, x, 4, 4, 'north', paper=x % 3)
    for z in range(5, 9):
        menu_strips(c, 2, 4, z, 'west', paper=z % 3)
        if z < 8:
            menu_strips(c, 6, 4, z, 'east', paper=(z + 1) % 3)
    for (x, z) in ((4, 2), (1, 3), (7, 3), (4, 6), (4, 8)):
        c.set(x, 4, z, paper_lantern(True))
    c.set(2, 2, 4, B('flower_pot'))
    c.set(6, 2, 6, B('decorated_pot', facing='north', cracked=False, waterlogged=False))
    c.set(4, 2, 4, B('cake', bites=1))


def goods_izakaya_long(c):
    """居酒屋（两层）：西边一条长吧台与厨房，东边半格高的座敷摆矮桌；吧台上方菜单条，东墙靠楼梯。"""
    for z in range(3, 9):
        c.set(3, 1, z, COUNTER_TOP)
    _kitchen(c, [(1, 4, 'east'), (1, 5, 'east'), (1, 6, 'east'), (1, 7, 'east'), (1, 8, 'east')])
    for z in (3, 5, 7):
        c.set(4, 1, z, STOOL)
    for z in range(3, 9):
        menu_strips(c, 3, 4, z, 'west', paper=z % 3)
    kotatsu(c, 5, 1, 8, axis='z', color='blue')
    c.set(6, 1, 4, B('barrel', facing='up', open=False))
    c.set(6, 2, 4, B('flower_pot'))
    zashiki(c, 5, 6, 6, 9, 1)
    for (x, z) in ((4, 2), (2, 6), (5, 5)):
        c.set(x, 4, z, paper_lantern(True))
    c.set(3, 2, 3, B('decorated_pot', facing='north', cracked=False, waterlogged=False))
    c.set(3, 2, 7, B('flower_pot'))


def kura_style(c):
    """土藏造的店：腰墙换成深色石，格子封成白壁，整栋偏白，与木格子的町家错开。"""
    for k, st in list(c.cells.items()):
        name = st['Name']
        if name == WAINSCOT['Name']:
            c.cells[k] = B('polished_blackstone_bricks')
        elif name == 'minecraft:spruce_trapdoor':
            c.cells[k] = PLASTER
    return c


SHOP_VARIANTS = {
    'shop_tea': lambda: machiya(Canvas(), 'red', two_storey=False, goods=goods_tea, bench=True),
    'shop_noodle': lambda: machiya(Canvas(), 'indigo', goods=goods_noodle),
    'shop_sweets': lambda: machiya(Canvas(), 'white', goods=goods_sweets),
    'shop_sake': lambda: kura_style(machiya(Canvas(), 'brown', goods=goods_sake)),
    'shop_general': lambda: machiya(Canvas(), 'indigo', two_storey=False, goods=goods_general, lattice=False),
    'shop_cloth': lambda: kura_style(machiya(Canvas(), 'red', goods=goods_cloth)),
    'shop_izakaya_u': lambda: machiya(Canvas(), 'red', two_storey=False, goods=goods_izakaya_u, agari=False),
    'shop_izakaya_long': lambda: machiya(Canvas(), 'brown', goods=goods_izakaya_long, agari=False),
}


# ==================== 住宅、屋台、小屋 ====================
def _veg_plot(c, x0, z0, x1, z1, crop='carrots'):
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if (x - x0) % 3 == 2:
                c.set(x, 0, z, B('water', level=0))
            else:
                c.set(x, 0, z, B('farmland', moisture=7))
                c.set(x, 1, z, B(crop, age=7 if crop != 'beetroots' else 3))


def _woodshed(c, x0, z0):
    """柴棚：单坡小屋顶下码放的原木。"""
    for x in (x0, x0 + 2):
        c.box(x, 1, z0, x, 3, z0, fence('spruce'))
    c.box(x0, 1, z0 + 1, x0 + 2, 2, z0 + 1, B('spruce_log', axis='x'))
    c.box(x0, 4, z0, x0 + 2, 4, z0 + 1, B('spruce_slab', type='bottom', waterlogged=False))


def _living(c, x0, z0, x1, z1, door_x, y=1, beds=1, kitchen=True):
    """一层住家的内饰：门内一排土间（灶台在这一排），往里铺榻榻米半砖，床嵌在半砖里，矮桌与坐垫，柜子与纸灯笼。
    门在 z0-1 那面墙的 door_x 处；半砖不铺到门前，进门不用先跨台阶。"""
    c.box(x0, y - 1, z0, x1, y - 1, z0, B('packed_mud'))
    if kitchen:
        c.set(x1, y, z0, B('smoker', facing='south', lit=True))
        c.set(x1 - 1, y, z0, B('cauldron'))
        c.set(x0, y, z0, B('barrel', facing='up', open=False))
    for i in range(beds):
        bed(c, x0 + 1, y, z1 - i * 2, 'west')
    kotatsu(c, (x0 + x1) // 2, y, (z0 + z1 + 1) // 2)
    c.set(x1, y, z1, B('chest', facing='west', type='single', waterlogged=False))
    zashiki(c, x0, z0 + 1, x1, z1, y)


def minka(variant):
    """住宅：13×13 地块，正面朝北。a 单层主屋与菜园；b 两层町屋带土藏；c 稻草顶农家；
    d 居酒屋（コ字吧台）；e 两层大宅（楼下两间，楼上卧室）。"""
    c = Canvas()
    c.box(0, 0, 0, 12, 0, 12, B('grass_block', snowy=False))
    for x in range(4, 7):
        for z in range(0, 3):
            c.set(x, 0, z, L('journey_stepping_stone') if False else B('dirt_path'))
    if variant == 'a':
        levels, _ = wa_building(c, 1, 3, 10, 8, 0, floors=1, floor_h=4, roof='hip', eave=1, bays=3)
        door(c, 5, 1, 3, 'north')
        _living(c, 2, 4, 9, 7, 5, beds=2)
        # 缘侧：南面的木廊
        for x in range(1, 11):
            c.set(x, 1, 9, spruce_slab('bottom'))
        _veg_plot(c, 1, 10, 6, 12)
        _woodshed(c, 9, 10)
        c.set(11, 1, 1, stone_lantern())
    elif variant == 'b':
        levels, _ = wa_building(c, 1, 2, 8, 9, 0, floors=2, floor_h=4, roof='gable', ridge_axis='z', eave=1, bays=3)
        door(c, 4, 1, 2, 'north')
        c.set(5, 3, 2, noren('north', 'white'))
        _inner_stairs(c, 7, 8, 5)
        _living(c, 2, 3, 6, 8, 4, beds=1)
        _upstairs_room(c, 2, 3, 6, 8, 6)
        # 土藏：白壁黑腰，小窗
        c.box(9, 0, 6, 12, 0, 11, B('stone_bricks'))
        for x in range(9, 13):
            for z in range(6, 12):
                edge = x in (9, 12) or z in (6, 11)
                if edge:
                    c.set(x, 1, z, B('polished_blackstone_bricks'))
                    c.box(x, 2, z, x, 5, z, PLASTER)
        c.clear(10, 1, 7, 11, 5, 10)
        c.set(9, 4, 8, B('iron_bars', north=True, south=True, east=False, west=False, waterlogged=False))
        c.set(10, 1, 6, B('spruce_door', facing='north', half='lower', hinge='left', open=False, powered=False))
        c.set(10, 2, 6, B('spruce_door', facing='north', half='upper', hinge='left', open=False, powered=False))
        gable_roof(c, 9, 12, 6, 11, 5, ridge_axis='z', eave=1)
        _veg_plot(c, 1, 11, 7, 12, crop='potatoes')
        fl_shrub(c, 11, 1, 2)
    elif variant == 'd':
        _izakaya_house(c)
    elif variant == 'e':
        levels, _ = wa_building(c, 1, 2, 11, 9, 0, floors=2, floor_h=4, roof='hip', eave=1, bays=3)
        door(c, 6, 1, 2, 'north')
        c.set(5, 3, 2, noren('north', 'indigo'))
        c.set(7, 3, 2, noren('north', 'indigo'))
        # 楼下用隔板分成两间，隔板上留门
        c.box(6, 1, 4, 6, 4, 8, B('spruce_planks'))
        door(c, 6, 1, 6, 'west')
        _inner_stairs(c, 10, 8, 5)
        _living(c, 2, 3, 5, 8, 4, beds=1)
        bed(c, 8, 1, 7, 'south')
        kotatsu(c, 8, 1, 5, axis='z')
        zashiki(c, 7, 4, 9, 8, 1)
        c.set(7, 1, 3, B('barrel', facing='up', open=False))
        _upstairs_room(c, 2, 3, 9, 8, 6, beds=2)
        _veg_plot(c, 1, 11, 7, 12, crop='carrots')
    else:
        # 稻草顶农家：干草块作茅草，灰瓦压脊
        levels, _ = wa_building(c, 1, 2, 11, 8, 0, floors=1, floor_h=4, roof='hip', eave=1, bays=4)
        for (x, y, z), st in list(c.cells.items()):
            if st['Name'].endswith('journey_kawara_stairs') or st['Name'].endswith('journey_kawara_roof'):
                c.set(x, y, z, B('hay_block', axis='y'))
        door(c, 6, 1, 2, 'north')
        _living(c, 2, 3, 10, 7, 6, beds=2)
        _veg_plot(c, 1, 10, 8, 12, crop='beetroots')
        _woodshed(c, 10, 10)
        c.set(12, 1, 1, B('composter', level=4))
    _yard(c, variant)
    return c


def _inner_stairs(c, x, z_foot, y_top):
    """住宅里的一跑楼梯：自 z_foot 向北上到二层（地板在 y_top），上方开洞。"""
    for k in range(y_top):
        z = z_foot - k
        c.clear(x, 1 + k, z, x, 4 + k, z)
        c.set(x, 1 + k, z, B('spruce_stairs', facing='north', half='bottom', shape='straight', waterlogged=False))


def _upstairs_room(c, x0, z0, x1, z1, y, beds=1):
    """二层卧室：半砖铺地、床嵌在里面；楼梯口一侧留一列木地板走道。"""
    for i in range(beds):
        bed(c, x0 + 1, y, z1 - i * 2, 'west')
    kotatsu(c, (x0 + x1) // 2, y, z0 + 1)
    c.set(x1 - 1, y, z1, B('chest', facing='north', type='single', waterlogged=False))
    zashiki(c, x0, z0, x1 - 1, z1, y)
    fence_line(c, [(x1, z) for z in range(z0 + 2, z1 + 1)], y, wood='spruce')


def _izakaya_house(c):
    """地块上的大居酒屋：一层高堂，开放式厨房居中，コ字吧台三面围坐，门口两张矮桌，头顶菜单条与灯笼。"""
    levels, _ = wa_building(c, 1, 1, 11, 10, 0, floors=1, floor_h=5, roof='hip', eave=1, bays=3, tatami=False, lit=False)
    c.box(2, 0, 2, 10, 0, 9, B('packed_mud'))
    door(c, 6, 1, 1, 'north')
    c.set(5, 3, 1, noren('north', 'red'))
    c.set(7, 3, 1, noren('north', 'red'))
    for x in range(4, 9):
        c.set(x, 1, 5, COUNTER_TOP)
    for z in range(6, 10):
        c.set(4, 1, z, COUNTER_TOP)
        if z < 9:  # 东侧短一格，厨师从这里进吧台
            c.set(8, 1, z, COUNTER_TOP)
    _kitchen(c, [(5, 9, 'north'), (6, 9, 'north'), (7, 9, 'north'), (5, 7, 'east'), (5, 6, 'east')])
    for x in (5, 7):
        c.set(x, 1, 4, STOOL)
    for z in (6, 8):
        c.set(3, 1, z, STOOL)
        c.set(9, 1, z, STOOL)
    for x in range(4, 9):
        menu_strips(c, x, 5, 5, 'north', paper=x % 3)
    for z in range(6, 10):
        menu_strips(c, 4, 5, z, 'west', paper=z % 3)
        if z < 9:
            menu_strips(c, 8, 5, z, 'east', paper=(z + 2) % 3)
    for (x, z) in ((6, 3), (2, 3), (10, 3), (6, 7), (2, 8), (10, 8)):
        c.set(x, 5, z, paper_lantern(True))
    for x in (2, 10):
        c.set(x, 1, 3, B('barrel', facing='up', open=False))
    c.set(6, 2, 5, B('decorated_pot', facing='north', cracked=False, waterlogged=False))
    c.set(4, 2, 5, B('flower_pot'))
    c.set(8, 2, 5, B('cake', bites=2))


def _yard(c, variant):
    """住宅小院：一圈矮篱（正面留门口），院角一棵小樱或松，几丛花与盆栽。"""
    import fox_landmarks as fl
    pts = [(x, z) for x in (0, 12) for z in range(1, 13)] + [(x, 12) for x in range(1, 12)] + [(x, 0) for x in list(range(0, 4)) + list(range(7, 13))]
    pts = [(x, z) for (x, z) in pts if c.get(x, 1, z) is None]
    fence_line(c, pts, 1, wood='bamboo' if variant == 'b' else 'spruce')
    corner = {'a': (1, 1), 'b': (2, 10), 'c': (11, 4), 'd': (1, 12), 'e': (12, 11)}[variant]
    # 树只长进空格子，枝叶不穿进房子
    tree = Canvas()
    if variant == 'b':
        fl.pine(tree, corner[0], 1, corner[1], seed=3)
    else:
        fl.cherry_tree(tree, corner[0], 1, corner[1], height=5, radius=3, seed=ord(variant))
    for k, st in tree.cells.items():
        if k not in c.cells or c.cells[k] == AIR:
            c.cells[k] = st
    for (x, z, f) in ((3, 1, 'poppy'), (8, 1, 'oxeye_daisy'), (9, 2, 'azure_bluet'), (2, 2, 'cornflower')):
        if c.get(x, 1, z) is None:
            c.set(x, 1, z, B(f))
    if c.get(7, 1, 1) is None:
        c.set(7, 1, 1, B('potted_azalea_bush'))
    return c


def fl_shrub(c, x, y, z):
    leaf = B('azalea_leaves', distance=1, persistent=True, waterlogged=False)
    c.box(x - 1, y, z, x + 1, y, z, leaf)
    c.set(x, y + 1, z, B('flowering_azalea_leaves', distance=1, persistent=True, waterlogged=False))


def yatai(kind):
    """屋台：5×5，木台面、小坡顶、纸灯笼与暖帘，正面朝北。"""
    c = Canvas()
    c.box(0, 0, 0, 4, 0, 4, B('coarse_dirt'))
    for x in (0, 4):
        for z in (1, 3):
            c.box(x, 1, z, x, 3, z, fence('spruce'))
    for x in range(0, 5):
        c.set(x, 1, 1, B('spruce_planks') if x in (1, 2, 3) else fence('spruce'))
        c.set(x, 2, 1, B('spruce_slab', type='bottom', waterlogged=False) if x in (1, 2, 3) else fence('spruce'))
    for x in range(-1, 6):
        c.set(x, 4, 0, B('spruce_stairs', facing='south', half='bottom', shape='straight', waterlogged=False))
        c.set(x, 4, 1, B('spruce_planks'))
        c.set(x, 4, 2, B('spruce_planks'))
        c.set(x, 4, 3, B('spruce_planks'))
        c.set(x, 4, 4, B('spruce_stairs', facing='north', half='bottom', shape='straight', waterlogged=False))
    color = {'dango': 'red', 'yakitori': 'brown', 'tea': 'white', 'mask': 'indigo'}[kind]
    for x in (1, 2, 3):
        c.set(x, 3, 1, noren('north', color))
    c.set(0, 3, 0, paper_lantern(True))
    c.set(4, 3, 0, paper_lantern(True))
    if kind == 'yakitori':
        c.set(2, 1, 3, B('campfire', facing='north', lit=True, signal_fire=False, waterlogged=False))
    elif kind == 'dango':
        c.set(1, 3, 1, B('flower_pot'))
        c.set(2, 1, 3, B('barrel', facing='up', open=False))
    elif kind == 'tea':
        c.set(2, 1, 3, B('cauldron'))
        c.set(1, 0, -1, B('spruce_slab', type='top', waterlogged=False))
    else:
        for x in (1, 2, 3):
            c.set(x, 3, 3, B('skeleton_skull', rotation=8) if x == 2 else B('carved_pumpkin', facing='north'))
    return c


def tool_shed(c):
    """田边农具棚：三面板墙、单坡瓦顶，里面放耙、桶与干草。"""
    c.box(0, 0, 0, 6, 0, 4, B('coarse_dirt'))
    for x in range(0, 7):
        c.box(x, 1, 4, x, 3, 4, B('spruce_planks'))
    for z in range(0, 5):
        c.box(0, 1, z, 0, 3, z, B('spruce_planks'))
        c.box(6, 1, z, 6, 3, z, B('spruce_planks'))
    for x in (2, 4):
        c.box(x, 1, 0, x, 3, 0, POST)
    for x in range(-1, 8):
        c.set(x, 4, 4, tile_stairs('north'))
        for z in range(0, 4):
            c.set(x, 4, z, tile_slab('bottom'))
        c.set(x, 3, -1, tile_stairs('south', half='bottom'))
    c.set(1, 1, 3, B('hay_block', axis='y'))
    c.set(2, 1, 3, B('hay_block', axis='x'))
    c.set(5, 1, 3, B('barrel', facing='up', open=False))
    c.set(4, 1, 3, B('composter', level=2))
    c.set(1, 1, 1, B('grindstone', face='floor', facing='north'))


HOUSE_VARIANTS = {f'house_{k}': (lambda k=k: minka(k)) for k in ('a', 'b', 'c', 'd', 'e')}
STALL_VARIANTS = {f'stall_{k}': (lambda k=k: yatai(k)) for k in ('dango', 'yakitori', 'tea', 'mask')}
