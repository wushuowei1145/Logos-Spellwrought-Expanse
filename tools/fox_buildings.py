"""神狐聚落的建筑设计：街边商铺（町家）、住宅、仓与小屋。

每个函数返回一个局部画布：x 向东、z 向南、正面朝北（z=0 临街），y=0 为地面层。
同一类地块的变体共用占地尺寸，由结构随机挑选。
"""
from fox_kit import *  # noqa: F401,F403

SHOP_W, SHOP_D = 9, 13


def _floor(c, x0, x1, z0, z1, y, state):
    c.box(x0, y, z0, x1, y, z1, state)


def machiya(c, noren_color='indigo', two_storey=True, goods=None, bench=False, lattice=True, sign=None):
    """临街町家：一层店面带格子与暖帘，二层低矮、退后一格，屋脊平行于街道。

    占地 9×13：z=0 为檐下石板走廊，z=1..10 为房屋，z=11..12 为后院。"""
    W = SHOP_W - 1
    front, back = 1, 10
    # 地基与地面：前半为土间，后半抬高铺榻榻米
    _floor(c, 0, W, 0, 0, 0, PAVING)
    c.box(0, 0, front, W, 0, back, STONE_BASE)
    _floor(c, 1, W - 1, front + 1, 5, 0, B('packed_mud'))
    _floor(c, 1, W - 1, 6, back - 1, 0, FLOOR)
    interior_air(c, 1, W - 1, front + 1, back - 1, 1, 4)
    # 后半是半格高的小上がり（不设门，进出只跨半格）
    for x in range(1, W):
        for z in range(6, back):
            c.set(x, 1, z, L('journey_tatami_slab', type='bottom', waterlogged=False))
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
    # 后院：碎石地与小柴堆
    _floor(c, 0, W, 11, 12, 0, B('coarse_dirt'))
    c.set(1, 1, 12, B('barrel', facing='up', open=False))
    c.set(W - 1, 1, 12, B('spruce_log', axis='x'))
    c.set(W - 2, 1, 12, B('spruce_log', axis='x'))
    return c


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
    _counter(c, 1, 7, 4)
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


def minka(variant):
    """住宅：13×13 地块，正面朝北。a 单层主屋与菜园；b 两层町屋带土藏；c 稻草顶农家。"""
    c = Canvas()
    c.box(0, 0, 0, 12, 0, 12, B('grass_block', snowy=False))
    for x in range(4, 7):
        for z in range(0, 3):
            c.set(x, 0, z, L('journey_stepping_stone') if False else B('dirt_path'))
    if variant == 'a':
        levels, _ = wa_building(c, 1, 3, 10, 8, 0, floors=1, floor_h=4, roof='hip', eave=1, bays=3)
        door(c, 5, 1, 3, 'north')
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
    else:
        # 稻草顶农家：干草块作茅草，灰瓦压脊
        levels, _ = wa_building(c, 1, 2, 11, 8, 0, floors=1, floor_h=4, roof='hip', eave=1, bays=4)
        for (x, y, z), st in list(c.cells.items()):
            if st['Name'].endswith('journey_kawara_stairs') or st['Name'].endswith('journey_kawara_roof'):
                c.set(x, y, z, B('hay_block', axis='y'))
        door(c, 6, 1, 2, 'north')
        _veg_plot(c, 1, 10, 8, 12, crop='beetroots')
        _woodshed(c, 10, 10)
        c.set(12, 1, 1, B('composter', level=4))
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


HOUSE_VARIANTS = {f'house_{k}': (lambda k=k: minka(k)) for k in ('a', 'b', 'c')}
STALL_VARIANTS = {f'stall_{k}': (lambda k=k: yatai(k)) for k in ('dango', 'yakitori', 'tea', 'mask')}
