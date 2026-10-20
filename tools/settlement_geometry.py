"""Build-time template masks. No runtime terrain editing or custom world generation."""
from collections import deque

AIR = {'Name': 'minecraft:air'}
VOID = {'Name': 'minecraft:structure_void'}
SOIL = {'dirt', 'grass_block', 'coarse_dirt', 'rooted_dirt', 'podzol',
        'mycelium', 'farmland', 'dirt_path', 'mud', 'clay', 'sand', 'red_sand', 'gravel'}
GROUND_PLANTS = {'short_grass', 'grass', 'tall_grass', 'fern', 'large_fern',
                 'dandelion', 'poppy', 'azure_bluet', 'oxeye_daisy', 'cornflower',
                 'wheat', 'carrots', 'potatoes', 'beetroots', 'sweet_berry_bush'}


def without_terrain(cells, ground_y):
    """Remove copied terrain, retaining raised, built-in planter beds above the entrance."""
    removed = {p for p, s in cells.items()
               if p[1] <= ground_y and s['Name'].split(':')[-1] in SOIL | {'water', 'lava'}}
    result = {p: s for p, s in cells.items() if p not in removed}
    # Imported crops cannot float over removed soil. Native wheat handles the real surface.
    for p, s in list(result.items()):
        if s['Name'].split(':')[-1] in GROUND_PLANTS:
            x, y, z = p
            if (x, y - 1, z) in removed or (x, y - 2, z) in removed:
                removed.add(p)
                del result[p]
    return result, removed


def room_air(cells, size):
    """Clear enclosed rooms and open, roofed floor columns, never whole plot-sized boxes."""
    sx, sy, sz = size
    solid = {p for p, s in cells.items() if s['Name'] not in
             {'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:structure_void'}}
    outside = set()
    queue = deque()
    for x in range(sx):
        for y in range(sy):
            for z in range(sz):
                if x in (0, sx - 1) or y in (0, sy - 1) or z in (0, sz - 1):
                    p = x, y, z
                    if p not in solid:
                        outside.add(p)
                        queue.append(p)
    while queue:
        x, y, z = queue.popleft()
        for p in ((x+1,y,z),(x-1,y,z),(x,y+1,z),(x,y-1,z),(x,y,z+1),(x,y,z-1)):
            if 0 <= p[0] < sx and 0 <= p[1] < sy and 0 <= p[2] < sz and p not in solid and p not in outside:
                outside.add(p)
                queue.append(p)
    air = {(x, y, z) for x in range(sx) for y in range(sy) for z in range(sz)
           if (x, y, z) not in solid and (x, y, z) not in outside}
    columns = {}
    floor_columns = {}
    for x,y,z in solid:
        columns.setdefault((x,z), []).append(y)
    for (x,y,z),s in cells.items():
        if s['Name'].endswith('_planks') or s['Name'] in {
                'logos_dimension:journey_limestone', 'minecraft:stone_bricks',
                'minecraft:deepslate_bricks', 'minecraft:tuff_bricks',
                'minecraft:polished_deepslate', 'minecraft:smooth_stone'}:
            floor_columns.setdefault((x,z), []).append(y)
    for x in range(sx):
        for z in range(sz):
            # Floor-to-roof clearance also covers the reference's open workshop/veranda.
            floors = floor_columns.get((x,z), [])
            tops = columns.get((x,z), [])
            if floors and tops:
                for y in range(min(floors)+1, max(tops)):
                    if (x,y,z) not in solid:
                        air.add((x,y,z))
    return air
