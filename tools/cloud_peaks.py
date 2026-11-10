"""云岫峰林：仙侠风的张家界峰林群系。

全部是原版数据：在远行大陆的气候表里划出一块高温、湿润、内陆的山地，让它落在新群系上；
地形在原版最终密度外面再包一层 max，只在这块气候里按二维噪声立起一根根直上直下的石柱。
脚本在已提交的数据上做定点修改，可以重复运行；不重跑整条 native_worldgen 管线。
"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
D = ROOT / 'src/main/resources/data/logos_dimension'
A = ROOT / 'src/main/resources/assets/logos_dimension'
NS = 'logos_dimension:'
ID = 'cloudsea_cloud_peaks'
BIOME = NS + ID


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', 'utf-8', newline='\n')


def read(path):
    return json.loads(path.read_text('utf-8'))


# ---------------------------------------------------------------- 气候
# 气候表是 t×h×c×e×w = 5×5×4×3×5 的规则格点，每处在各轴上分别取最近的值，所以一块格点对应一个气候盒子。
# 云岫峰林取两个盒子的并集，w 五档全要：
#   A：高温 t=0.8、h∈{-0.4,0,0.4,0.8}、近海到内陆 c∈{-0.08,0.3,0.75}、中等侵蚀 e=0.15（丘陵与河谷做底，
#      石柱从谷底拔起，落差才大；内陆的低侵蚀那一档底下本身就是大山，柱子会立在山顶上，所以不要）；
#   B：同样的 t、h，近海 c=-0.08 的三档侵蚀全要（近海的低侵蚀只是海边丘陵，没有大山）。
# 共 100 个格点。要能整块放下太玄灵境（完整地标 252×252 格），所以一路扩大：
#   第一版 16 个格点：地图 0.5%，碎块中位数约 87 格见方，石柱长满的核心只占一成，/locate 找到的多是碎片；
#   45 个格点：约占 0.9%（32768 格见方的普查），一片中位数约 220 格；
#   现在：约占 1.7%，一片中位数约 300 格、最大约 1950 格，九成多的面积在放得下 320 格见方的大片里，
#   能当太玄中心的地方是 45 个格点时的 2.2 倍。
# 多拿的格点来自白崖海岸 25（共 225）、静水湖泊 20（共 150）、铜叶林丘 8（共 34，面积约少三分之一）、曦光林隙 2，没有群系被挤掉。
# 普查场景 cloudsea_biome_census，见 docs/NATIVE_WORLDGEN.md。
T, H = (.8,), (-.4, 0, .4, .8)
W = (-.8, -.35, 0, .35, .8)


def in_climate(p):
    if p['temperature'] not in T or p['humidity'] not in H or p['weirdness'] not in W:
        return False
    a = p['continentalness'] in (-.08, .3, .75) and p['erosion'] == .15
    b = p['continentalness'] == -.08
    return a or b


# 与上面格点对应的气候盒子（相邻格点值的中点），密度门控用它：
# t > 0.6、h > -0.6、c > -0.34 两个盒子共用；A 还要 e 落在 (-0.25, 0.4)，B 还要 c < 0.11
BOX = dict(t=.6, h=-.6, c=-.34, e=(-.25, .4), coast=.11)
# 气候门斜坡宽度（气候值单位）
GW = .13


def climate():
    path = D / 'dimension/cloudsea.json'
    dim = read(path)
    taken = 0
    for row in dim['generator']['biome_source']['biomes']:
        p = row['parameters']
        if isinstance(p['temperature'], list):
            continue
        if in_climate(p):
            row['biome'] = BIOME
            taken += 1
    assert taken == 100, taken
    write(path, dim)
    fixture = ROOT / 'gametest-packs/crucible/data/minecraft/worldgen/world_preset/flat.json'
    preset = read(fixture)
    preset['dimensions'][NS + 'cloudsea'] = dim
    write(fixture, preset)
    return taken


# ---------------------------------------------------------------- 密度
def add(a, b): return {'type': 'minecraft:add', 'argument1': a, 'argument2': b}
def mul(a, b): return {'type': 'minecraft:mul', 'argument1': a, 'argument2': b}
def mn(a, b): return {'type': 'minecraft:min', 'argument1': a, 'argument2': b}
def mx(a, b): return {'type': 'minecraft:max', 'argument1': a, 'argument2': b}
def clamp(x, lo, hi): return {'type': 'minecraft:clamp', 'input': x, 'min': lo, 'max': hi}
def cache2d(x): return {'type': 'minecraft:cache_2d', 'argument': x}
def ramp(x, lo, hi): return clamp(mul(add(x, -lo), 1.0 / (hi - lo)), 0.0, 1.0)
def smooth(x, lo, hi):
    t = ramp(x, lo, hi)
    return mul(mul(t, t), add(3.0, mul(t, -2.0)))
def noise(name, xz=1.0, y=0.0): return {'type': 'minecraft:noise', 'noise': name, 'xz_scale': xz, 'y_scale': y}
def shifted(name): return {'type': 'minecraft:shifted_noise', 'noise': name, 'shift_x': 'minecraft:shift_x', 'shift_y': 0.0,
                           'shift_z': 'minecraft:shift_z', 'xz_scale': 0.25, 'y_scale': 0.0}

NEG_Y = {'type': 'minecraft:y_clamped_gradient', 'from_y': -64, 'to_y': 448, 'from_value': 64.0, 'to_value': -448.0}
PILLARS = NS + 'cloud_peaks/pillars'
# 石柱平面：多八度噪声高过阈值的地方
TAU = .02
LAND = NS + 'cloud_peaks/land'
CARVE = NS + 'cloud_peaks/carve'
NOISES = {
    # 岩块：成片的砂岩台地，约一半面积
    'cloud_peaks_pillar': {'firstOctave': -6, 'amplitudes': [1.0, 0.5, 0.2]},
    # 两组竖直裂隙：零值线把岩块切成一根根石柱
    'cloud_peaks_fissure_a': {'firstOctave': -5, 'amplitudes': [1.0, 0.3]},
    'cloud_peaks_fissure_b': {'firstOctave': -5, 'amplitudes': [1.0, 0.35]},
    'cloud_peaks_tier': {'firstOctave': -5, 'amplitudes': [1.0]},
    'cloud_peaks_height': {'firstOctave': -7, 'amplitudes': [1.0, 0.5]},
    # 溪流：低频噪声的零值线，蜿蜒穿过峰林
    'cloud_peaks_river': {'firstOctave': -7, 'amplitudes': [1.0, 0.4]},
    # 台地与林坡的细碎起伏
    'cloud_peaks_ground': {'firstOctave': -6, 'amplitudes': [1.0, 0.5, 0.25]},
}
# 台地高度：群系核心整体抬到 y≈92，边缘随气候门平缓落回原地形
PLATEAU = (64.0, 28.0)
# 溪流：|噪声| 小于 RIVER_CORE 的一线是溪床（y=55，海平面 63 以下自然灌水），往外 RIVER_BANK 内是峡谷谷壁
RIVER_CORE, RIVER_BANK, RIVER_BED = .035, .085, 55.0


def density():
    for name, spec in NOISES.items():
        write(D / 'worldgen/noise' / f'{name}.json', spec)
    # 气候门：每条边界一段 0→1 的斜坡，相乘；整片只在气候盒子里面为 1，边上渐弱。
    # 斜坡放宽（GW），从群系边缘往里有一段较长的过渡带，石柱在里面逐渐变矮、变成山包，最后消失。
    # 两个盒子取较大的那个：A 的侵蚀带和 B 的近海带在群系内部相接，取大值那里不会凹下去一道缝
    erosion_band = mul(ramp('minecraft:overworld/erosion', BOX['e'][0], BOX['e'][0] + GW),
                       ramp(mul('minecraft:overworld/erosion', -1.0), -BOX['e'][1], -BOX['e'][1] + GW))
    coastal = ramp(mul('minecraft:overworld/continents', -1.0), -BOX['coast'], -BOX['coast'] + GW)
    gate = cache2d(mul(ramp(shifted('minecraft:temperature'), BOX['t'], BOX['t'] + GW),
               mul(ramp(shifted('minecraft:vegetation'), BOX['h'], BOX['h'] + GW),
               mul(ramp('minecraft:overworld/continents', BOX['c'], BOX['c'] + GW),
                   mx(erosion_band, coastal)))))
    write(D / 'worldgen/density_function/cloud_peaks/gate.json', gate)
    gate = NS + 'cloud_peaks/gate'
    pillar = cache2d(noise(NS + 'cloud_peaks_pillar'))
    write(D / 'worldgen/density_function/cloud_peaks/pillar.json', pillar)
    pillar = NS + 'cloud_peaks/pillar'
    # 过渡：离群系核心越远（气候门越小），柱子越矮（height），崖壁越缓（steep）。
    # 门值 0.75 以上是全高、直上直下的石柱；往外逐渐变矮、变成缓坡的山包；门值 0.08 以下就只剩台地
    height = cache2d(smooth(gate, .08, .75))
    write(D / 'worldgen/density_function/cloud_peaks/height.json', height)
    height = NS + 'cloud_peaks/height'
    steep = cache2d(add(.03, mul(smooth(gate, .3, .8), .97)))
    write(D / 'worldgen/density_function/cloud_peaks/steep.json', steep)
    steep = NS + 'cloud_peaks/steep'
    # 柱顶高度：大尺度起伏 + 柱心更高（顶面略呈穹形）
    q = clamp(add(mul(noise(NS + 'cloud_peaks_height'), .5), .5), 0.0, 1.0)
    # 核心区柱顶 y≈130～260：大尺度起伏占大头，柱心只比边缘高十来格，顶面平缓圆润，不是尖塔
    # 再叠一层与柱子同尺度的噪声，相邻柱子高低错落，不是整片一样高；
    # 外圈的山包没有陡壁，顶上的穹形放大一些（1-steep），看上去是圆的
    tier = mul(noise(NS + 'cloud_peaks_tier'), 32.0)
    dome = mul(clamp(add(pillar, -TAU), 0.0, .3), add(40.0, mul(add(1.0, mul(steep, -1.0)), 50.0)))
    rise = add(35.0, add(add(mul(q, 90.0), tier), dome))
    top = cache2d(add(add(PLATEAU[0], mul(gate, PLATEAU[1])), mul(height, rise)))
    write(D / 'worldgen/density_function/cloud_peaks/top.json', top)
    # 溪流：riverness 在溪床为 1，出了峡谷为 0；只在气候门里
    river = cache2d({'type': 'minecraft:abs', 'argument': noise(NS + 'cloud_peaks_river')})
    write(D / 'worldgen/density_function/cloud_peaks/river.json', river)
    river = NS + 'cloud_peaks/river'
    # 溪流在门值过半时就切到底，不受过渡带拖累；只在群系最外一圈逐渐变浅
    flow = smooth(gate, .05, .5)
    riverness = cache2d(mul(flow, clamp(add(1.0, mul(add(river, -RIVER_CORE), -1.0 / (RIVER_BANK + .03))), 0.0, 1.0)))
    write(D / 'worldgen/density_function/cloud_peaks/riverness.json', riverness)
    riverness = NS + 'cloud_peaks/riverness'
    # 裂隙：两张噪声零值线附近为 1，宽几格；张家界的石柱就是砂岩台地被纵横的竖直裂隙切开留下的
    def crack(name):
        return clamp(add(1.0, mul({'type': 'minecraft:abs', 'argument': noise(NS + name)}, -1 / .075)), 0.0, 1.0)
    cracks = cache2d(mx(crack('cloud_peaks_fissure_a'), crack('cloud_peaks_fissure_b')))
    write(D / 'worldgen/density_function/cloud_peaks/cracks.json', cracks)
    # 平面掩码：岩块内为正，减去裂隙；溪流两岸不长柱子，水从柱林之间穿过。
    # 不再在门值不满时整体压掉（那样柱子还很高就突然没了），边缘交给 height 与 steep 渐变
    mask = cache2d(add(add(mul(add(pillar, -TAU), 12.0), mul(riverness, -14.0)), mul(NS + 'cloud_peaks/cracks', -9.0)))
    write(D / 'worldgen/density_function/cloud_peaks/mask.json', mask)
    # 台地 + 柱脚林坡：柱子周围一圈向上隆起的陡坡，越靠近柱脚越高，柱子从坡顶拔起；溪流两岸不起坡
    ground = noise(NS + 'cloud_peaks_ground')
    plateau = add(PLATEAU[0], mul(gate, add(PLATEAU[1], mul(ground, 7.0))))
    # 林坡比柱子宽一大圈、坡度放缓，不再是一级级的台阶
    skirt = clamp(mul(add(pillar, -(TAU - .30)), 1 / .30), 0.0, 1.0)
    skirt_h = mul(mul(skirt, skirt), add(30.0, mul(ground, 9.0)))
    land_top = cache2d(add(plateau, mul(mul(gate, skirt_h), add(1.0, mul(riverness, -1.0)))))
    write(D / 'worldgen/density_function/cloud_peaks/land_top.json', land_top)
    land = {'type': 'minecraft:range_choice', 'input': gate, 'min_inclusive': .001, 'max_exclusive': 2.0,
            'when_in_range': mul(add(NS + 'cloud_peaks/land_top', NEG_Y), .125), 'when_out_of_range': -1000.0}
    write(D / 'worldgen/density_function/cloud_peaks/land.json', land)
    # 峡谷：溪床在 RIVER_BED，往外沿谷壁陡升；气候门外不切
    floor = cache2d(add(add(RIVER_BED, mul(clamp(mul(add(river, -RIVER_CORE), 1 / RIVER_BANK), 0.0, 1.0), 180.0)),
                        mul(add(1.0, mul(smooth(gate, .05, .5), -1.0)), 400.0)))
    write(D / 'worldgen/density_function/cloud_peaks/river_floor.json', floor)
    carve = {'type': 'minecraft:range_choice', 'input': gate, 'min_inclusive': .001, 'max_exclusive': 2.0,
             'when_in_range': mul(add(NS + 'cloud_peaks/river_floor', NEG_Y), .17), 'when_out_of_range': 1000.0}
    write(D / 'worldgen/density_function/cloud_peaks/carve.json', carve)
    # 柱身由二维掩码决定、上下贯通（三维凹槽会把细柱子拦腰切断，留下悬空碎块）。
    # 再加一点锥度：离柱顶越远掩码越宽，每一层都包住上一层，柱脚自然放宽、柱身上出现可以长草木的小台阶
    depth = clamp(add(NS + 'cloud_peaks/top', NEG_Y), 0.0, 160.0)
    # 掩码乘 steep：核心区掩码陡，崖壁直上直下；外圈掩码平，锥度占上风，侧面成了缓坡，裂隙也变浅
    body = mn(mul(add(NS + 'cloud_peaks/top', NEG_Y), .125), add(mul(NS + 'cloud_peaks/mask', steep), mul(depth, .018)))
    pillars = {'type': 'minecraft:range_choice', 'input': gate, 'min_inclusive': .001, 'max_exclusive': 2.0,
               'when_in_range': body, 'when_out_of_range': -1.0}
    write(D / 'worldgen/density_function/cloud_peaks/pillars.json', pillars)
    # 包住原版最终密度：max(min(max(原版, 台地与林坡), 峡谷), 石柱)。原版只出现一次，不会多算一遍插值噪声；
    # 气候门外台地给 -1000、峡谷给 1000、石柱给 -1，结果就是原版。重复运行时先拆掉旧的几层。
    path = D / 'worldgen/noise_settings/journey.json'
    settings = read(path)
    final = settings['noise_router']['final_density']
    if final.get('type') == 'minecraft:max' and final.get('argument2') == PILLARS:
        final = final['argument1']
    if final.get('type') == 'minecraft:min' and final.get('argument2') == CARVE:
        final = final['argument1']
    if final.get('type') == 'minecraft:max' and final.get('argument2') == LAND:
        final = final['argument1']
    settings['noise_router']['final_density'] = mx(mn(mx(final, LAND), CARVE), PILLARS)
    write(path, settings)


# ---------------------------------------------------------------- 地表
def surface_rule(ground):
    """柱顶草皮，下面几格土；崖壁按二维噪声分出竖向的灰白、浅黄与暗灰条纹（张家界石英砂岩的竖向节理）。"""
    def block(name): return {'type': 'minecraft:block', 'result_state': {'Name': name}}
    def cond(c, then): return {'type': 'minecraft:condition', 'if_true': c, 'then_run': then}
    def band(lo, hi): return {'type': 'minecraft:noise_threshold', 'noise': NS + 'cloud_peaks_streak', 'min_threshold': lo, 'max_threshold': hi}
    floor0 = {'type': 'minecraft:stone_depth', 'offset': 0, 'add_surface_depth': False, 'secondary_depth_range': 0, 'surface_type': 'floor'}
    floor3 = {'type': 'minecraft:stone_depth', 'offset': 3, 'add_surface_depth': False, 'secondary_depth_range': 0, 'surface_type': 'floor'}
    above = {'type': 'minecraft:y_above', 'anchor': {'absolute': 58}, 'surface_depth_multiplier': 0, 'add_stone_depth': False}
    high = {'type': 'minecraft:y_above', 'anchor': {'absolute': 150}, 'surface_depth_multiplier': 0, 'add_stone_depth': False}
    # 水平层理：每隔十来格一道断续的暗色岩层（另一张二维噪声把它切成一段一段），配合竖向条纹形成块状节理
    bedding = {'type': 'minecraft:noise_threshold', 'noise': NS + 'cloud_peaks_bedding', 'min_threshold': .12, 'max_threshold': 9.0}
    layers = []
    y = 66
    while y < 250:
        layers.append(cond(bedding, cond({'type': 'minecraft:y_above', 'anchor': {'absolute': y}, 'surface_depth_multiplier': 0, 'add_stone_depth': False},
            cond({'type': 'minecraft:not', 'invert': {'type': 'minecraft:y_above', 'anchor': {'absolute': y + 1},
                  'surface_depth_multiplier': 0, 'add_stone_depth': False}}, block('minecraft:tuff')))))
        y += 9 + (y * 7) % 5
    cliffs = cond(above, {'type': 'minecraft:sequence', 'sequence': layers + [
        cond(band(.40, 9.0), block('minecraft:smooth_sandstone')),
        cond(band(-9.0, -.50), block('minecraft:andesite')),
        cond(band(-.50, -.42), block('minecraft:tuff')),
        cond(band(-.14, -.08), block('minecraft:calcite')),
        cond(band(.10, .16), block('minecraft:mossy_cobblestone')),
        cond(high, cond(band(.16, .22), block('minecraft:smooth_sandstone'))),
    ]})
    # 平处草皮下垫几格土；陡处（柱顶边缘、崖壁上的小台阶）只铺一层草皮，下面直接是岩石，崖壁不会出现一道道土层
    flat = {'type': 'minecraft:not', 'invert': {'type': 'minecraft:steep'}}
    valley = {'type': 'minecraft:not', 'invert': {'type': 'minecraft:y_above', 'anchor': {'absolute': 112},
              'surface_depth_multiplier': 0, 'add_stone_depth': False}}
    # 溪床：海平面以下的地面铺砾石，夹沙与苔石
    bed = {'type': 'minecraft:not', 'invert': {'type': 'minecraft:y_above', 'anchor': {'absolute': 61},
           'surface_depth_multiplier': 0, 'add_stone_depth': False}}
    riverbed = cond(bed, cond(floor3, {'type': 'minecraft:sequence', 'sequence': [
        cond(band(.25, 9.0), block('minecraft:sand')), cond(band(-9.0, -.35), block('minecraft:mossy_cobblestone')),
        block('minecraft:gravel')]}))
    return cond({'type': 'minecraft:biome', 'biome_is': [BIOME]}, {'type': 'minecraft:sequence', 'sequence': [
        riverbed, cond(flat, cond(floor0, block(ground))), cond({'type': 'minecraft:steep'}, cond(floor0, block('minecraft:mossy_cobblestone'))), cond(valley, cond(flat, cond(floor3, block('minecraft:dirt')))), cliffs]})


def surface(ground):
    write(D / 'worldgen/noise/cloud_peaks_streak.json', {'firstOctave': -4, 'amplitudes': [1.0, 0.0, 0.6]})
    write(D / 'worldgen/noise/cloud_peaks_bedding.json', {'firstOctave': -3, 'amplitudes': [1.0, 0.5]})
    path = D / 'worldgen/noise_settings/journey.json'
    settings = read(path)
    seq = settings['surface_rule']['sequence']
    seq[:] = [r for r in seq if BIOME not in json.dumps(r.get('if_true', {}))]
    seq.insert(1, surface_rule(ground))
    write(path, settings)


# ---------------------------------------------------------------- 植被
def state(name, **props):
    return {'Name': name, 'Properties': {k: str(v).lower() for k, v in props.items()}} if props else {'Name': name}


def simple(name, **props):
    return {'type': 'minecraft:simple_state_provider', 'state': state(name, **props)}


def below_dirt():
    return {'type': 'minecraft:rule_based_state_provider', 'rules': [{'if_true': {'type': 'minecraft:not', 'predicate': {
        'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:cannot_replace_below_tree_trunk'}},
        'then': simple('minecraft:dirt')}]}


def uniform(lo, hi): return {'type': 'minecraft:uniform', 'min_inclusive': lo, 'max_inclusive': hi}


LOG = NS + ID + '_log'
LEAF = NS + ID + '_leaf'


def pine(trunk, foliage, size_limit=1, root=None):
    """黄山松：原版树的拼装。树干用原版的斜干或带横枝的干，树冠用金合欢那种扁平两层的冠。"""
    below = below_dirt()
    if root:
        below['rules'].insert(0, {'if_true': {'type': 'minecraft:matching_blocks', 'blocks': ['minecraft:air']}, 'then': simple(root)})
    return {'type': 'minecraft:tree', 'config': {
        'below_trunk_provider': below, 'decorators': [], 'foliage_placer': foliage,
        'foliage_provider': simple(LEAF, distance=7, persistent=False, waterlogged=False, season=0),
        'ignore_vines': True,
        'minimum_size': {'type': 'minecraft:two_layers_feature_size', 'limit': size_limit, 'lower_size': 0, 'upper_size': 1},
        'trunk_placer': trunk, 'trunk_provider': simple(LOG, axis='y')}}


def pines():
    return {
        # 迎客式：主干带两三根横伸的枝，每根枝头托一层扁平的松针，层层叠叠
        'cloud_peaks_pine_tiered': pine({'type': 'minecraft:cherry_trunk_placer', 'base_height': 8, 'height_rand_a': 3, 'height_rand_b': 0,
            'branch_count': {'type': 'minecraft:weighted_list', 'distribution': [{'data': 2, 'weight': 2}, {'data': 3, 'weight': 1}]},
            'branch_horizontal_length': uniform(3, 5), 'branch_start_offset_from_top': {'min_inclusive': -5, 'max_inclusive': -2},
            'branch_end_offset_from_top': uniform(-1, 0)},
            {'type': 'minecraft:acacia_foliage_placer', 'radius': 2, 'offset': 0}),
        # 斜干式：主干斜着长出去，顶上一大片平展的冠
        'cloud_peaks_pine_leaning': pine({'type': 'minecraft:forking_trunk_placer', 'base_height': 6, 'height_rand_a': 2, 'height_rand_b': 2},
            {'type': 'minecraft:acacia_foliage_placer', 'radius': 3, 'offset': 0}),
        # 崖边小松：矮小、斜出，贴着崖缘
        'cloud_peaks_pine_cliff': pine({'type': 'minecraft:forking_trunk_placer', 'base_height': 2, 'height_rand_a': 1, 'height_rand_b': 1},
            {'type': 'minecraft:acacia_foliage_placer', 'radius': 2, 'offset': 0}, size_limit=0),
        # 崖壁松：从半空的岩缝里斜着长出来，树根处是一团苔石（破石而出）
        'cloud_peaks_pine_wall': pine({'type': 'minecraft:forking_trunk_placer', 'base_height': 2, 'height_rand_a': 2, 'height_rand_b': 1},
            {'type': 'minecraft:acacia_foliage_placer', 'radius': 2, 'offset': 0}, size_limit=0, root='minecraft:mossy_cobblestone'),
    }


def at(dx, dy, dz): return [dx, dy, dz]


def placed(name, feature, mods):
    write(D / 'worldgen/placed_feature' / f'{name}.json', {'feature': feature, 'placement': mods})


def vegetation():
    for name, spec in pines().items():
        write(D / 'worldgen/configured_feature' / f'{name}.json', spec)
    # 每个远行大陆群系都有一份 trees_<群系> 的原版树，用自家原木与树叶（NativeDataPolicyTest 检查）
    write(D / 'worldgen/configured_feature' / f'trees_{ID}.json', pines()['cloud_peaks_pine_leaning'])
    write(D / 'worldgen/configured_feature/cloud_peaks_pines.json', {'type': 'minecraft:simple_random_selector', 'config': {
        'features': [{'feature': NS + n, 'placement': []} for n in ('cloud_peaks_pine_tiered', 'cloud_peaks_pine_leaning', 'cloud_peaks_pine_tiered')]}})
    write(D / 'worldgen/configured_feature/cloud_peaks_cliff_pines.json', {'type': 'minecraft:simple_random_selector', 'config': {
        'features': [{'feature': NS + n, 'placement': []} for n in ('cloud_peaks_pine_cliff', 'cloud_peaks_pine_leaning')]}})
    soil = {'type': 'minecraft:matching_block_tag', 'offset': at(0, -1, 0), 'tag': 'minecraft:dirt'}
    # 柱顶与崖上：草皮下面直接是岩石（地表规则只在谷底垫土），以此区分柱顶和谷底
    rock_below = {'type': 'minecraft:not', 'predicate': {'type': 'minecraft:matching_blocks', 'offset': at(0, -2, 0), 'blocks': ['minecraft:dirt']}}
    dirt_below = {'type': 'minecraft:matching_blocks', 'offset': at(0, -2, 0), 'blocks': ['minecraft:dirt']}
    # 崖边：三格以内有至少三格深的落差
    edge = {'type': 'minecraft:any_of', 'predicates': [
        {'type': 'minecraft:matching_blocks', 'offset': at(dx, -3, dz), 'blocks': ['minecraft:air']}
        for dx, dz in ((3, 0), (-3, 0), (0, 3), (0, -3), (2, 2), (-2, 2), (2, -2), (-2, -2))]}
    def tree_mods(count, *preds):
        return [{'type': 'minecraft:count', 'count': count}, {'type': 'minecraft:in_square'},
                {'type': 'minecraft:surface_water_depth_filter', 'max_water_depth': 0},
                {'type': 'minecraft:heightmap', 'heightmap': 'OCEAN_FLOOR'},
                {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:all_of', 'predicates': [soil, *preds]}},
                {'type': 'minecraft:biome'}]
    placed('cloud_peaks_summit_pines', NS + 'cloud_peaks_pines', tree_mods(20, rock_below))
    placed('cloud_peaks_edge_pines', NS + 'cloud_peaks_cliff_pines', tree_mods(14, rock_below, edge))
    # 谷底：成片的阔叶林
    placed('cloud_peaks_valley_trees', NS + 'trees_cloudsea_jade_whirls', tree_mods(10, dirt_below))
    # 崖壁：藤蔓与贴壁的灌丛
    placed('cloud_peaks_cliff_vines', 'minecraft:vines', [{'type': 'minecraft:count', 'count': 420}, {'type': 'minecraft:in_square'},
        {'type': 'minecraft:height_range', 'height': {'type': 'minecraft:uniform', 'min_inclusive': {'absolute': 70}, 'max_inclusive': {'absolute': 235}}},
        {'type': 'minecraft:biome'}])
    wall = {'type': 'minecraft:any_of', 'predicates': [
        {'type': 'minecraft:solid', 'offset': at(dx, 0, dz)} for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))]}
    # 26.1 已去掉 random_patch：成丛的东西在放置规则里做，先定一个点，再在周围随机撒若干次
    write(D / 'worldgen/configured_feature/cloud_peaks_cliff_shrub.json', {'type': 'minecraft:simple_block', 'config': {
        'to_place': simple(LEAF, distance=7, persistent=True, waterlogged=False, season=0)}})
    trap = lambda r: {'type': 'minecraft:trapezoid', 'min': -r, 'max': r, 'plateau': 0}
    placed('cloud_peaks_cliff_shrubs', NS + 'cloud_peaks_cliff_shrub', [{'type': 'minecraft:count', 'count': 70}, {'type': 'minecraft:in_square'},
        {'type': 'minecraft:height_range', 'height': {'type': 'minecraft:uniform', 'min_inclusive': {'absolute': 80}, 'max_inclusive': {'absolute': 230}}},
        {'type': 'minecraft:biome'}, {'type': 'minecraft:count', 'count': 20},
        {'type': 'minecraft:random_offset', 'xz_spread': trap(2), 'y_spread': trap(2)},
        {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:all_of', 'predicates': [
            {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:air'}, wall]}}])
    # 崖壁松：半空中贴着岩壁、脚下悬空的位置
    placed('cloud_peaks_wall_pines', NS + 'cloud_peaks_pine_wall', [{'type': 'minecraft:count', 'count': 40}, {'type': 'minecraft:in_square'},
        {'type': 'minecraft:height_range', 'height': {'type': 'minecraft:uniform', 'min_inclusive': {'absolute': 95}, 'max_inclusive': {'absolute': 225}}},
        {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:all_of', 'predicates': [
            {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:air'},
            {'type': 'minecraft:matching_block_tag', 'offset': at(0, -1, 0), 'tag': 'minecraft:air'}, wall]}},
        {'type': 'minecraft:biome'}])
    return ['cloud_peaks_edge_pines', 'cloud_peaks_summit_pines', 'cloud_peaks_wall_pines', 'cloud_peaks_valley_trees', 'cloud_peaks_cliff_shrubs',
            'cloud_peaks_cliff_vines', 'cloudsea_fernwood_floor', 'minecraft:patch_sugar_cane', 'minecraft:patch_waterlily']


# ---------------------------------------------------------------- 群系
def biome(ground, features=()):
    base = read(D / 'worldgen/biome/cloudsea_cloudroot_shelves.json')
    base['temperature'] = .9
    base['downfall'] = .85
    base['effects'] = {'water_color': '#5e8f97', 'grass_color': '#7d9a6b', 'foliage_color': '#4f6f4d', 'fog_color': '#c8d4d2'}
    base['attributes'] = {'minecraft:visual/fog_start_distance': 64.0, 'minecraft:visual/fog_end_distance': 420.0}
    base['features'] = [[] for _ in range(11)]
    base['features'][9] = [f if ':' in f else NS + f for f in features]
    write(D / 'worldgen/biome' / f'{ID}.json', base)


NAMES = {
    'zh_cn': {'': '云岫峰林', '_ground': '云岫草皮', '_leaf': '云岫松针', '_log': '云岫松木'},
    'en_us': {'': 'Cloudveil Peaks', '_ground': 'Cloudveil Turf', '_leaf': 'Cloudveil Pine Needles', '_log': 'Cloudveil Pine Log'},
}


def lang():
    for code, names in NAMES.items():
        path = A / 'lang' / f'{code}.json'
        obj = read(path)
        obj['biome.logos_dimension.' + ID] = names['']
        for part in ('_ground', '_leaf', '_log'):
            obj['block.logos_dimension.' + ID + part] = names[part]
            obj['item.logos_dimension.' + ID + part] = names[part]
        write(path, obj)


# ---------------------------------------------------------------- 方块素材
SOURCE = 'cloudsea_cloudroot_shelves'


def recolor(path, out, part):
    """从山地松林的贴图改色：松针更深更绿（黄山松的墨绿），树皮更灰更暗，草皮略偏青。"""
    import numpy as np
    from PIL import Image
    im = Image.open(path).convert('RGBA')
    a = np.asarray(im).astype(np.float32) / 255
    rgb, alpha = a[..., :3], a[..., 3:]
    hsv = np.asarray(Image.fromarray((rgb * 255).astype(np.uint8)).convert('HSV')).astype(np.float32) / 255
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    if part == '_leaf':
        h = h * .4 + (100 / 360) * .6; s = np.clip(s * 1.2, 0, 1); v = v * .8
    elif part == '_log':
        s = s * .6; v = v * .82
    else:
        h = h * .7 + (95 / 360) * .3; s = np.clip(s * 1.05, 0, 1)
    out_rgb = np.asarray(Image.fromarray((np.stack([h, s, v], -1) * 255).astype(np.uint8), 'HSV').convert('RGB')).astype(np.float32) / 255
    res = np.concatenate([out_rgb, alpha], -1)
    Image.fromarray((res * 255).round().astype(np.uint8), 'RGBA').save(out)


def blocks():
    """复制山地松林的三种方块素材（方块状态、模型、物品、掉落、贴图）并改名、改色；同时加进它所在的方块标签。"""
    copied = 0
    folders = [A / 'blockstates', A / 'items', A / 'models/block', A / 'textures/block', D / 'loot_table/blocks']
    for folder in folders:
        for path in sorted(folder.glob(SOURCE + '_*')):
            name = path.name.replace(SOURCE, ID)
            target = folder / name
            if path.suffix == '.png':
                part = '_leaf' if '_leaf' in path.name else '_log' if '_log' in path.name else '_ground'
                recolor(path, target, part)
            else:
                text = path.read_text('utf-8').replace(SOURCE + '_', ID + '_')
                target.write_text(text, 'utf-8', newline='\n')
            copied += 1
    tagged = 0
    for root in (ROOT / 'src/main/resources/data',):
        for path in root.rglob('tags/block/**/*.json'):
            tag = read(path)
            values = tag.get('values', [])
            changed = False
            for part in ('_ground', '_leaf', '_log'):
                src, dst = NS + SOURCE + part, NS + ID + part
                if src in values and dst not in values:
                    values.insert(values.index(src) + 1, dst)
                    changed = True
            if changed:
                write(path, tag)
                tagged += 1
    return copied, tagged


def main():
    ground = NS + ID + '_ground'
    print('assets copied, tags touched', blocks())
    print('climate points', climate())
    density()
    surface(ground)
    biome(ground, vegetation())
    lang()


if __name__ == '__main__':
    main()
