"""精灵树屋、攀爬绳、环形分层与林间光芽的离线模板构件。"""
import json
import math
from pathlib import Path
from PIL import Image, ImageDraw

NS = 'logos_dimension:'


def state(name, **props):
    return {'Name': name if ':' in name else NS + name, **({'Properties': {k: str(v).lower() for k, v in props.items()}} if props else {})}


def tree_house(cells, conifer=False, variant=0, offset=(0, 0, 0)):
    """树干旁有斜撑的木屋；入口绳梯通到原模板根颈，不追加地台。"""
    ox, oy, oz = offset
    floor = 23 + variant % 3 * 3
    wood = 'spruce' if conifer else 'birch'
    frame = 'cloudsea_fir_heart_log' if conifer else 'elven_heartwood'
    plank = 'minecraft:spruce_planks' if conifer else 'elven_pale_planks'
    roof = 'dark_oak' if conifer else 'spruce'
    def put(x, y, z, name, **p): cells[ox+x, oy+y, oz+z] = state(name, **p)
    if conifer:
        # 冷杉低枝较密，只修剪门前叶片，保留树干与承重分枝。
        for x in range(-12,13):
            for z in range(4,25):
                for y in range(floor,floor+14):
                    at=(ox+x,oy+y,oz+z)
                    if 'leaf' in cells.get(at,{}).get('Name','') or cells.get(at,{}).get('Name','').endswith('elven_canopy'):
                        cells[at]=state('minecraft:air')
    # 阳台与屋内先留显式空气，避免生成的枝叶堵住房间。
    for x in range(-5, 6):
        for z in range(4, 14):
            for y in range(floor+1, floor+9): put(x, y, z, 'minecraft:air')
            put(x, floor, z, plank)
    for x in [-4, 4]:
        for z in [5, 10]:
            for y in range(floor+1, floor+5): put(x, y, z, frame, axis='y')
    for y in range(floor+1, floor+5):
        for x in range(-3, 4):
            for z in [5, 10]: put(x, y, z, plank)
        for z in range(6, 10):
            for x in [-4, 4]: put(x, y, z, plank)
    # 入口朝阳台；两侧落地窗配木格和花槽，屋顶随针阔叶树改变轮廓。
    for x in [-1, 0]:
        for y in [floor+1, floor+2, floor+3]: put(x, y, 10, 'minecraft:air')
    for x in [-4, 4]:
        for z in [7, 8]:
            for y in [floor+2, floor+3]: put(x, y, z, 'minecraft:glass_pane', north=True, south=True, east=False, west=False, waterlogged=False)
        put(x, floor+1, 8, 'elven_flower_planter', facing='north')
    for x in [-5, 5]:
        for z in range(4, 14): put(x, floor+1, z, f'minecraft:{wood}_fence', north=True, south=True, east=False, west=False, waterlogged=False)
    for x in range(-4, 5):
        if x != 0: put(x, floor+1, 13, f'minecraft:{wood}_fence', east=True, west=True, north=False, south=False, waterlogged=False)
    for x in range(-5, 6):
        rise = (5-abs(x)) if conifer else (5-abs(x))//2
        y = floor+5+rise
        for z in range(4, 12):
            put(x, y, z, f'minecraft:{roof}_stairs', facing='east' if x<0 else 'west', half='bottom', shape='straight', waterlogged=False)
            if abs(x) < 5 and y > floor+5:
                put(x, y-1, z, plank)
            # 封住山墙，避免从屋内看见穿透的屋顶边缘。
            for wall_y in range(floor+5, y):
                for edge in [5, 10]: put(x, wall_y, edge, plank)
        if x in [-5, 5]:
            for z in [4, 11]: put(x, y+1, z, 'cloudsea_fir_heart_leaf' if conifer else 'elven_canopy', distance=1, persistent=True, waterlogged=False, season=0)
    # 梁与折线托臂穿入原木，不让木屋仅靠绳子悬浮。
    for x in [-4, 4]:
        for z in range(0, 13): put(x, floor-1, z, frame, axis='z')
        for k in range(8):
            put(x, floor-8+k, 2+k, frame, axis='y')
            put(x, floor-8+k, 3+k, frame, axis='z')
    put(3, floor+1, 7, 'elven_reading_table', facing='west')
    put(2, floor+1, 7, 'elven_leaf_seat', facing='east')
    for y, half in [(floor+1, 'lower'), (floor+2, 'upper')]: put(-3, y, 6, 'elven_bookshelf', facing='south', half=half)
    for x, z in [(-3, 12), (3, 12), (0, 7)]:
        put(x, floor+3, z, 'elven_leaf_lamp', facing='north')
        for y in range(floor+4, floor+6): put(x, y, z, 'elven_lamp_vine', facing='north')
        # 阳台灯的吊枝从墙柱接出。
        for zz in range(10, z+1): put(x, floor+6, zz, frame, axis='z')
    # 绳口两侧有栏杆，整条绳可攀爬；下端在根颈旁一格。
    put(0, floor+3, 13, frame, axis='z')
    for y in range(2, floor+3): put(0, y, 13, 'elven_climbing_rope')
    put(0, floor, 13, 'elven_climbing_rope')
    return {'kind': 'fir' if conifer else 'broadleaf', 'floor': oy+floor, 'rope': [ox, oy+2, oz+13], 'rope_top': oy+floor+2}


RESIDENT = NS + 'forest_elf'


def resident(x, y, z, guard=False):
    """结构放置时按 STRUCTURE 初始化；普通居民拿弓，顶楼守卫标记让实体初始化矛盾。"""
    return {'pos': [x + .5, float(y), z + .5], 'blockPos': [x, y, z],
            'nbt': {'id': RESIDENT, **({'WorldTreeGuard': 1} if guard else {})}}


def house_residents(offset, floor):
    """树屋里两位：屋内书架与桌椅之间的空地，脚下是屋板。"""
    ox, oy, oz = offset
    return [resident(ox + x, oy + floor + 1, oz + 8) for x in (-2, 1)]


def standable(cells, x, y, z):
    below = cells.get((x, y - 1, z), {'Name': 'minecraft:air'})['Name']
    if below == 'minecraft:air' or 'fence' in below or 'rope' in below:
        return False
    return all(cells.get((x, yy, z), {'Name': 'minecraft:air'})['Name'] == 'minecraft:air' for yy in (y, y + 1))


def world_residents(cells, levels):
    """世界树：大厅三位、三层回廊各两位、顶楼两位守卫；逐个检查脚下有地、头上两格为空气。"""
    out = []
    hall = [(3, 1), (-3, -2), (1, -4), (-4, 3), (4, -3)]
    for x, z in hall:
        for y in range(1, 10):
            if standable(cells, x, y, z):
                out.append(resident(x, y, z))
                break
        if len(out) == 3:
            break
    ring = [(0, 8), (8, 0), (0, -8), (-8, 0), (6, 6), (-6, -6), (6, -6), (-6, 6)]
    for floor in levels['floors'][:3]:
        placed = 0
        for x, z in ring:
            if standable(cells, x, floor + 1, z):
                out.append(resident(x, floor + 1, z))
                placed += 1
            if placed == 2:
                break
    # 顶楼单独增加两位矛盾守卫，保留原来的居民与装备。
    placed = 0
    for x, z in ring:
        y = levels['floors'][-1] + 1
        if standable(cells, x, y, z):
            out.append(resident(x, y, z, guard=True))
            placed += 1
        if placed == 2:
            break
    assert placed == 2, '世界树顶楼没有足够的守卫站位'
    return out


def tree_lights(cells, offset=(0, 0, 0), seed=0):
    """以固定空间哈希疏采树皮和枝条；不改变地形，也不生成实体。"""
    candidates = list(cells.items())
    count = 0
    for (x, y, z), s in candidates:
        if s['Name'] not in [NS+'elven_heartwood', NS+'cloudsea_fir_heart_log'] or y < 3:
            continue
        h = ((x*73856093) ^ (y*19349663) ^ (z*83492791) ^ (seed*9719)) & 0xffffffff
        if h % 113: continue
        dx, dz = [(1, 0), (-1, 0), (0, 1), (0, -1)][(h >> 10) % 4]
        p = (x+dx, y, z+dz)
        if p in cells and cells[p]['Name'] != 'minecraft:air': continue
        cells[p] = state('elven_bough_light')
        count += 1
    return count


def spiral_path():
    # 圆角八边形每圈七十二步，宽三格；半格一级可直接步行。
    vertices = [(-8, -10), (8, -10), (10, -8), (10, 8), (8, 10), (-8, 10), (-10, 8), (-10, -8)]
    path = []
    for a, b in zip(vertices, vertices[1:]+vertices[:1]):
        n = max(abs(b[0]-a[0]), abs(b[1]-a[1]))
        path += [(round(a[0]+(b[0]-a[0])*i/n), round(a[1]+(b[1]-a[1])*i/n)) for i in range(n)]
    return path


def world_floors(cells):
    floors = [17, 33, 49, 65, 81]
    def put(x, y, z, n, **p): cells[x, y, z] = state(n, **p)
    for floor in floors:
        for x in range(-13, 14):
            for z in range(-13, 14):
                r = math.hypot(x, z)
                if 5.3 <= r < 13:
                    put(x, floor, z, 'elven_luminous_inlay' if (x*7+z*11)%29==0 else 'elven_pale_planks')
                    # 完整分层，树壳作外围墙，中央光井贯通所有楼层。
                    for y in range(floor+1, floor+5): put(x, y, z, 'minecraft:air')
                if 5.3 <= r <= 6.42:
                    put(x, floor+1, z, 'minecraft:birch_fence', north=True, south=True, east=True, west=True, waterlogged=False)
        for x, z in [(-7, 0), (7, 0), (0, -7), (0, 7)]:
            put(x, floor+3, z, 'elven_leaf_lamp', facing='north')
            for y in [floor+4, floor+5]: put(x, y, z, 'elven_lamp_vine', facing='north')
        for x in [-7, -6]:
            for y, half in [(floor+1, 'lower'), (floor+2, 'upper')]: put(x, y, 7, 'elven_bookshelf', facing='north', half=half)
        put(7, floor+1, -6, 'elven_reading_table', facing='south')
        put(7, floor+1, -4, 'elven_leaf_seat', facing='north')
    path = spiral_path(); steps = []
    # 清出连续的三格高头部空间，先处理所有台阶再补外侧护栏。
    for i in range(161):
        x, z = path[i % len(path)]
        y = 1+(i+1)//2
        half = 'top' if i%2==0 else 'bottom'
        dx, dz = (1, 0) if abs(x)>=abs(z) else (0, 1)
        # 斜向转角需要完整的三乘三头部净空，避免玩家宽度撞到旧树壳。
        for xx in range(x-1,x+2):
            for zz in range(z-1,z+2):
                for yy in range(y+1,y+4):put(xx,yy,zz,'minecraft:air')
        for w in [-1, 0, 1]:
            xx, zz = x+dx*w, z+dz*w
            for yy in range(y+1, y+4): put(xx, yy, zz, 'minecraft:air')
            put(xx, y, zz, 'minecraft:birch_slab', type=half, waterlogged=False)
        steps.append([x, y, z, half])
        if i % 8 == 0:
            sx, sz = x+dx*2*(1 if x>=0 else -1), z+dz*2*(1 if z>=0 else -1)
            put(sx, y+1, sz, 'elven_bough_light')
    rail_posts = []
    for x, y, z, half in steps:
        dx, dz = (1 if x>=0 else -1, 0) if abs(x)>=abs(z) else (0, 1 if z>=0 else -1)
        fx, fy, fz = x+dx*2, y+1, z+dz*2
        # 外侧连续扶手位于行走宽度之外，不堵中央采光井或楼梯。
        put(fx, fy, fz, 'minecraft:birch_fence', north=False, south=False, east=False, west=False, waterlogged=False)
        rail_posts.append((fx, fy, fz))
    for (fx0, fy0, fz0), (fx1, fy1, fz1) in zip(rail_posts, rail_posts[1:]):
        if fy1 == fy0 + 1 and abs(fx1 - fx0) + abs(fz1 - fz0) == 1:
            put(fx0, fy1, fz0, 'minecraft:birch_fence', north=False, south=False, east=False, west=False, waterlogged=False)
    return {'floors': floors, 'stairs': steps, 'width': 3, 'central_lightwell_radius': 5.3}


def _can_connect_horizontal(neighbor_name, is_pane=False):
    if not neighbor_name or neighbor_name == 'minecraft:air':
        return False
    if is_pane:
        if 'glass_pane' in neighbor_name or 'distillation_glass' in neighbor_name or neighbor_name.endswith(':glass'):
            return True
    else:
        if '_fence' in neighbor_name:
            return True
    return any(k in neighbor_name for k in (
        'planks', 'heartwood', '_log', 'plaster', 'wool', 'moss_block', 'luminous_inlay'
    ))


def fix_connectable_blockstates(cells):
    """根据四邻格实际方块自动修正全部栅栏与玻璃板的连接属性（north/south/east/west），消除十字错乱状态。"""
    dirs = (
        ('north', 0, -1),
        ('south', 0, 1),
        ('east', 1, 0),
        ('west', -1, 0),
    )
    for (x, y, z), st in list(cells.items()):
        name = st.get('Name', '')
        is_fence = '_fence' in name
        is_pane = 'glass_pane' in name
        if not (is_fence or is_pane):
            continue
        props = dict(st.get('Properties', {}))
        for side, dx, dz in dirs:
            nb = cells.get((x + dx, y, z + dz), {}).get('Name', 'minecraft:air')
            props[side] = 'true' if _can_connect_horizontal(nb, is_pane=is_pane) else 'false'
        props['waterlogged'] = 'false'
        cells[x, y, z] = {'Name': name, 'Properties': props}


def assets(root):
    """直接绘制绳索纤维、叶芽与光晕；不使用生成式图像。"""
    a = Path(root)/'assets/logos_dimension'
    d = Path(root)/'data/logos_dimension'
    def write(p, o):
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(json.dumps(o, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    im = Image.new('RGBA', (16, 16), (164, 140, 97, 255)); q = ImageDraw.Draw(im)
    for y in range(16):
        for x in range(16):
            v = int(16*math.sin((x+y*.7)*1.4)+5*math.cos(y*1.6))
            q.point((x, y), fill=(164+v, 140+v, 97+v, 255))
    im.save(a/'textures/block/elven_rope.png')
    def cube(lo, hi, tex, light=False):
        return {'from': list(lo), 'to': list(hi), **({'shade': False, 'light_emission': 15} if light else {}), 'faces': {s: {'texture': '#'+tex, 'uv': [0, 0, 16, 16]} for s in ['up', 'down', 'north', 'south', 'east', 'west']}}
    rope = [cube((x, 0, 7), (x+1.4, 16, 8.4), 'rope') for x in [4, 10.6]]
    for y in [2, 7.3, 12.6]: rope.append(cube((3.3, y, 6.8), (12.7, y+1.2, 8.6), 'rope'))
    bud = [cube((7.3, 0, 7.3), (8.7, 6, 8.7), 'stem')]
    for y, w in [(4, 2.1), (6, 3.1), (8, 3.5), (10, 2.8), (12, 1.5)]:
        bud += [cube((8-w, y, 7.1), (8+w, y+2, 8.9), 'leaf'), cube((7.1, y, 8-w), (8.9, y+2, 8+w), 'leaf')]
    bud.append(cube((6.8, 5.5, 6.8), (9.2, 12, 9.2), 'light', True))
    for n, elements, textures in [('elven_climbing_rope', rope, {'rope': NS+'block/elven_rope'}), ('elven_bough_light', bud, {'stem': NS+'block/elven_dark', 'leaf': NS+'block/elven_green', 'light': NS+'block/elven_pearl'})]:
        textures['particle'] = next(iter(textures.values()))
        write(a/'models/block'/f'{n}.json', {'parent': 'minecraft:block/block', 'render_type': 'neoforge:cutout', 'textures': textures, 'elements': elements})
        write(a/'blockstates'/f'{n}.json', {'variants': {'': {'model': NS+'block/'+n}}})
        write(a/'items'/f'{n}.json', {'model': {'type': 'minecraft:model', 'model': NS+'block/'+n}})
        write(d/'loot_table/blocks'/f'{n}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'conditions': [{'condition': 'minecraft:survives_explosion'}], 'entries': [{'type': 'minecraft:item', 'name': NS+n}]}]})
    im = Image.new('RGBA', (64, 64))
    for y in range(64):
        for x in range(64):
            r = math.hypot(x-31.5, y-31.5)/31.5
            alpha = int(255*max(0, 1-r)**3)
            im.putpixel((x, y), (244, 255, 213, alpha))
    (a/'textures/environment').mkdir(parents=True, exist_ok=True)
    im.save(a/'textures/environment/elven_breath.png')
    for lang, titles in [('zh_cn', ['精灵攀爬绳梯', '枝间叶辉']), ('en_us', ['Elven Climbing Rope', 'Bough Light'])]:
        p = a/'lang'/f'{lang}.json'; obj = json.loads(p.read_text(encoding='utf-8'))
        obj.update({f'block.logos_dimension.{name}': title for name, title in zip(['elven_climbing_rope', 'elven_bough_light'], titles)})
        write(p, obj)
    p = Path(root)/'data/minecraft/tags/block/climbable.json'
    obj = json.loads(p.read_text()) if p.exists() else {'replace': False, 'values': []}
    obj['values'] = list(dict.fromkeys(obj['values']+[NS+'elven_climbing_rope'])); write(p, obj)
