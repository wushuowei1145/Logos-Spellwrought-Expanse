"""神狐聚落主街中央的一排屋台（照福冈中洲的路边屋台）。

每个屋台是一块 3×2 的可替换地块（kind 'yatai'），正面朝北（z=0 为柜台、z=1 为灶台），模板原点为建筑坐标 (-2, -2, -2)：
- y=1：柜台（上半砖，下面留出伸腿的空当）与灶台；柜台前 z=-1 是长凳或高脚凳；
- y=2：四角细柱、檐下暖帘或纸灯笼；
- y=3：屋顶，平顶到 2.5 格，人字小坡顶的屋脊到 3 格；行人从屋檐下走过不碰头。
两端各占一格：西端 x=-1 只挂吊牌（无碰撞，留作横穿通道），东端 x=3 挂灯笼、堆杂物。
同一组屋台朝向相同、间隔 2 格，东端杂物与下一个的西端吊牌错开，组内也能横穿主街。
"""
from fox_kit import *  # noqa: F401,F403
from blueprint_nbt import write_structure

YATAI_W, YATAI_D = 3, 2

# 主街上的屋台组：(首个屋台的 u, 个数, 朝向)。组间空出横穿主街的口子，
# 对准两侧巷口（u=55、103），食摊广场门前（65–83）整段留空。
GROUPS = [(31, 3, 0), (47, 2, 2), (57, 2, 0), (84, 3, 2), (105, 3, 0)]
STRIDE = YATAI_W + 2

FULL_SUFFIX = ('_planks', '_log', '_wood', 'barrel', 'smoker', 'furnace', '_block', 'bricks', 'mosaic', 'kawara_roof')


def _full(state):
    if state is None:
        return False
    n = state['Name']
    return n.endswith(FULL_SUFFIX) and not n.endswith(('_slab', '_stairs'))


def _fence_links(c):
    """按原版规则补全栅栏连接：相邻栅栏或完整方块才连。"""
    for (x, y, z), st in list(c.cells.items()):
        if not st['Name'].endswith('_fence'):
            continue
        sides = {}
        for d, (dx, dz) in zip(DIRS, ((0, -1), (1, 0), (0, 1), (-1, 0))):
            nb = c.get(x + dx, y, z + dz)
            sides[d] = nb is not None and (nb['Name'].endswith('_fence') or _full(nb))
        c.set(x, y, z, B(st['Name'], **sides, waterlogged=False))


def _slab(wood, kind='bottom'):
    return B(f'{wood}_slab', type=kind, waterlogged=False)


def _stairs(wood, facing, half='bottom'):
    return B(f'{wood}_stairs', facing=facing, half=half, shape='straight', waterlogged=False)


def _hanging_sign(c, nbt, x, y, z, wood, text, rotation=4):
    """檐下吊牌：留白不写字（维护者要求），玩家可以自己写上店名；text 只作变体说明。"""
    c.set(x, y, z, B(f'{wood}_hanging_sign', rotation=rotation, attached=False, waterlogged=False))


# ---------- 屋顶 ----------
def roof_board(c, wood):
    """板葺平顶：顶面 2.5 格，前沿一道立起的檐板挡住下面的吊件。"""
    for x in range(-1, 4):
        c.set(x, 3, -1, _stairs(wood, 'north'))
        for z in (0, 1):
            c.set(x, 3, z, _slab(wood))


def roof_gable(c, kind):
    """人字小坡顶：屋脊顺着主街，前后檐各一排楼梯，脊高 3 格。"""
    if kind == 'kawara':
        stairs, ridge = tile_stairs, TILE
    else:
        stairs, ridge = lacquer_stairs, LACQUER_PLANKS
    for x in range(-1, 4):
        c.set(x, 3, -1, stairs('south'))
        c.set(x, 3, 0, ridge)
        c.set(x, 3, 1, stairs('north'))


def roof_canvas(c, wood='birch'):
    """布篷：一层关着的活板门当篷布，向前多伸一格遮住长凳。"""
    for x in range(-1, 4):
        for z in range(-2, 2):
            c.set(x, 3, z, trapdoor('north', half='bottom', open_=False, wood=wood))


# ---------- 屋台 ----------
def yatai(name, wood, roof, front, kitchen, seats='bench', counter=None, on_counter=None, back_top=None,
          east=None, sign_wood='spruce'):
    """拼装一个屋台。front：檐下 z=-1 一排的方块（暖帘或灯笼），kitchen：灶台 z=1 三格，east：东端 (y, z) → 方块。"""
    c = Canvas()
    nbt = {}
    top = counter or _slab(wood, 'top')
    for x in range(3):
        c.set(x, 1, 0, top)
        c.set(x, 1, 1, kitchen[x])
    # 四角细柱撑起屋顶
    for x in (0, 2):
        for z in (0, 1):
            c.set(x, 2, z, fence(wood))
    if on_counter:
        c.set(1, 2, 0, on_counter)
    if back_top:
        c.set(1, 2, 1, back_top)
    {'board': lambda: roof_board(c, wood), 'kawara': lambda: roof_gable(c, 'kawara'),
     'lacquer': lambda: roof_gable(c, 'lacquer'), 'canvas': lambda: roof_canvas(c)}[roof]()
    for x, st in enumerate(front):
        c.set(x, 2, -1, st)
    # 座位：长凳是一条下半砖；高脚凳是栅栏顶一块压力板
    if seats == 'bench':
        for x in range(3):
            c.set(x, 1, -1, _slab(wood))
    elif seats == 'stools':
        for x in (0, 2):
            c.set(x, 1, -1, _slab(wood))
    elif seats == 'high':
        for x in (0, 2):
            c.set(x, 1, -1, fence(wood))
            c.set(x, 2, -1, B(f'{wood}_pressure_plate', powered=False))
    _hanging_sign(c, nbt, -1, 2, 0, sign_wood, name)
    for (y, z), st in (east or {}).items():
        c.set(3, y, z, st)
    _fence_links(c)
    return c, nbt


def lantern():
    return paper_lantern(True)


def _variants():
    sm = B('smoker', facing='south', lit=True)
    fu = B('furnace', facing='south', lit=True)
    barrel = B('barrel', facing='up', open=False)
    cask = B('barrel', facing='east', open=False)
    broth = B('water_cauldron', level=3)
    crate = B('stripped_bamboo_block', axis='y')
    pot = B('decorated_pot', facing='north', cracked=False, waterlogged=False)
    return {
        # 拉面：板葺平顶，赤暖帘，汤锅冒着热气
        'yatai_ramen': lambda: yatai('ラーメン', 'spruce', 'board', [noren('north', 'red')] * 3,
                                     [sm, broth, barrel], seats='bench',
                                     east={(2, 0): lantern(), (1, 0): barrel, (1, 1): crate}),
        # 烤串：灰瓦小坡顶，正中一幅焦茶暖帘，炭火上冒烟，两张高脚凳
        'yatai_yakitori': lambda: yatai('焼鳥', 'dark_oak', 'kawara', [AIR, noren('north', 'brown'), AIR],
                                        [barrel, B('campfire', facing='north', lit=True, signal_fire=False, waterlogged=False), sm],
                                        seats='high', sign_wood='dark_oak',
                                        east={(2, -1): lantern(), (2, 0): lantern(), (2, 1): lantern(), (1, 1): crate}),
        # 关东煮：布篷，前檐一排纸灯笼，方锅放在正中
        'yatai_oden': lambda: yatai('おでん', 'spruce', 'canvas', [lantern()] * 3,
                                    [fu, broth, barrel], seats='bench', sign_wood='birch',
                                    east={(2, 0): noren('east', 'white'), (1, 0): pot}),
        # 甘味：樱木顶，柜台上摆一只蛋糕，白暖帘
        'yatai_sweets': lambda: yatai('甘味', 'cherry', 'board', [noren('north', 'white'), lantern(), noren('north', 'white')],
                                      [barrel, fu, crate], seats='bench', on_counter=B('cake', bites=0),
                                      back_top=B('potted_cherry_sapling'), sign_wood='cherry',
                                      east={(2, 0): lantern(), (1, 0): pot}),
        # 酒：朱漆小坡顶，背后叠两层酒桶，檐角挂杉玉，立饮的高脚凳
        'yatai_sake': lambda: yatai('酒', 'dark_oak', 'lacquer', [AIR, lantern(), AIR],
                                    [cask, cask, cask], seats='high', back_top=cask, sign_wood='dark_oak',
                                    east={(2, 0): B('spruce_leaves', distance=1, persistent=True, waterlogged=False),
                                          (1, 0): cask, (1, 1): cask}),
        # 章鱼烧：板葺平顶，藍暖帘，两座烤炉
        'yatai_takoyaki': lambda: yatai('たこ焼', 'bamboo', 'board', [noren('north'), noren('north'), lantern()],
                                        [sm, sm, crate], seats='stools', sign_wood='bamboo',
                                        east={(2, 0): lantern(), (1, 0): crate, (1, 1): barrel}),
    }


YATAI_VARIANTS = _variants()


def street_row(s, street_v):
    """在主街中线上登记屋台地块：柜台、灶台与长凳三排以中线为中心，两侧各留至少 2 格通行。"""
    for u0, n, turns in GROUPS:
        for i in range(n):
            u = u0 + i * STRIDE
            vc = int(round(street_v(u + 1)))
            # 朝北时长凳在 v0-1；朝南时长凳在 v0+2。两种都让三排落在 vc-1..vc+1
            s.lot('yatai', u, vc if turns == 0 else vc - 1, YATAI_W, YATAI_D, turns, y=0)
    s.variants['yatai'] = sorted(YATAI_VARIANTS)


def write_templates(out, data_version):
    """模板与其他地块一致：外扩 2 格，原点为建筑坐标 (-2, -2, -2)；吊牌带方块实体数据。"""
    size = [YATAI_W + 4, 8, YATAI_D + 4]
    for name, make in YATAI_VARIANTS.items():
        c, nbt = make()
        cells, bes = {}, {}
        for (x, y, z), st in c.cells.items():
            k = (x + 2, y + 2, z + 2)
            if all(0 <= k[i] < size[i] for i in range(3)):
                cells[k] = st
                if (x, y, z) in nbt:
                    bes[k] = nbt[(x, y, z)]
        write_structure(out / f'{name}.nbt', size, cells, block_entities=bes, data_version=data_version)
