"""腐化女王的宫廷枝叶、刺绣与独立树枝权杖，沿用既有精灵面容和骨架。"""
import math
from PIL import Image, ImageDraw


def enrich(model, atlas, rig, group, box):
    # 披肩分成两层叶片，外缘浅金、内层墨绿，留出脖颈和脸的轮廓。
    for side, label in ((1, 'Left'), (-1, 'Right')):
        root = 'queen_leaf_mantle_' + label.lower()
        group(root, label + 'Arm', [side * 4.8, 34.5, .2])
        for j in range(3):
            p = [side * (4.7 + j * .65), 34.3 - j * .8, .4]
            box(root + '_edge_' + str(j), root, p,
                [p[0] - 1.1, p[1] - 2.2, -.8], [2.2, 3.2, .55], 4,
                [-12, side * 12, side * (24 + j * 13)])
            box(root + '_leaf_' + str(j), root + '_edge_' + str(j), p,
                [p[0] - .85, p[1] - 2, -1.04], [1.7, 2.7, .35], 10)
        # 袖口的垂叶独立于手腕，挥杖时不会把权杖拖进衣料。
        x, y, z = rig[label + 'ForeArm']['pivot']
        for j in range(3):
            p = [x + (j - 1) * 1.2, y - 6.1, -2.15]
            box(f'queen_cuff_petal_{label}_{j}', 'queen_bell_sleeve_' + label.lower(), p,
                [p[0] - .5, p[1] - 2.5, p[2]], [1, 3, .32], 4 if j == 1 else 10,
                [8, 0, (j - 1) * 12])

    group('queen_bodice_filigree', 'UpperBody', [0, 30, -2.8])
    for side in (-1, 1):
        for j in range(3):
            x, y = side * (1.1 + j * .6), 28.6 + j * 1.45
            box(f'queen_bodice_vine_{side}_{j}', 'queen_bodice_filigree', [x, y, -3.05],
                [x - .13, y - .2, -3.15], [.26, 2.2, .25], 4, [0, 0, side * 24])
            box(f'queen_bodice_leaf_{side}_{j}', 'queen_bodice_filigree', [x, y + .4, -3.3],
                [x - .45, y + .1, -3.4], [.9, 1.2, .18], 11, [0, 0, side * 42])
        # 宽腰封两侧加成串的小金叶，长度错开，避免整齐的金属栅格。
        root = 'queen_waist_chain_' + str(side)
        group(root, 'DownBody', [side * 3.1, 25, -3])
        for j in range(4):
            x, y = side * (1 + j * .85), 25.2 - math.sin(j * .65) * 1.3
            box(root + '_' + str(j), root, [x, y, -3.6],
                [x - .38, y - .55, -3.8], [.76, 1.1, .26], 5, [0, 0, side * (32 - j * 9)])

    # 从腰部延伸的外层花瓣与原有长裙错层，胸衣、长裙、拖尾仍是不同配件。
    for side in (-1, 1):
        for j in range(3):
            root = f'queen_overpetal_{side}_{j}'
            p = [side * (3.5 + j * .65), 24 - j * 1.9, .5 + j * 1.6]
            group(root, 'Skirt', p)
            box(root + '_silk', root, p, [p[0] - 1.55, 4 + j * 1.2, p[2]],
                [3.1, p[1] - 3.1 - j * 1.2, .5], 10, [0, side * (24 + j * 13), side * 7])
            box(root + '_border', root + '_silk', p, [p[0] - 1.5, 4 + j * 1.2, p[2] - .14],
                [.22, p[1] - 3.1 - j * 1.2, .2], 5)
            box(root + '_tip', root, [p[0], 5 + j * 1.2, p[2]],
                [p[0] - .8, 2.8 + j * 1.2, p[2] - .08], [1.6, 3.4, .38], 11,
                [0, side * (24 + j * 13), side * 25])
        root = 'queen_hair_jewels_' + str(side)
        group(root, 'Head', [side * 4.5, 45, 2.2])
        for j in range(3):
            x, y, z = side * (4.8 + j * .35), 45 - j * 1.4, 2.4 + j * .5
            box(root + '_leaf_' + str(j), root, [x, y, z], [x - .65, y - .9, z],
                [1.3, 1.8, .3], 4 if j == 0 else 11, [0, side * 12, side * (30 + j * 15)])
        box(root + '_drop', root, [side * 5.6, 40.4, 3.3], [side * 5.6 - .4, 39.8, 3.1],
            [.8, 1.2, .4], 7, [0, 0, 45])

    # 后脑增加一圈分叉花萼，不封成实心高帽；冠尖仍由原来的枝冠决定。
    for side in (-1, 1):
        for j in range(3):
            p = [side * (2.2 + j * 1.05), 49.2, 2.2]
            box(f'queen_rear_crown_{side}_{j}', 'queen_branch_diadem', p,
                [p[0] - .2, p[1], p[2] - .2], [.4, 2.4 + j * .15, .4], 4,
                [14, 0, side * (12 + j * 15)])

    # 权杖整棵挂在手持挂点下；换武器只需隐藏该根，不必动衣服或手臂。
    hand = rig['RightHandLocator']['pivot'][:]
    socket = group('queen_scepter_socket', 'RightHandLocator', hand)
    socket['rotation'] = [0, 0, -18.17591]
    group('queen_weapon_branch_scepter', 'queen_scepter_socket', hand)
    root = 'queen_weapon_branch_scepter'
    x, y, z = hand
    z -= .5
    points = [(x + .8, .7, z + .4), (x + .15, 10, z), (x, y, z),
              (x - .2, 33, z + .2), (x + .45, 48, z), (x + 1.2, 53, z + .15)]
    for i, (a, b) in enumerate(zip(points, points[1:])):
        branch(box, root + '_shaft_' + str(i), root, a, b, 1.1 - i * .09, 10)
        branch(box, root + '_ridge_' + str(i), root,
               (a[0] + .28, a[1], a[2] - .47), (b[0] + .28, b[1], b[2] - .47), .16, 11)
    # 非闭合的枝冠将小红种子托在中间，分叉处留出透空轮廓。
    group('queen_scepter_crown', root, [x, 50, z])
    for side in (-1, 1):
        route = [(x + .45, 48, z), (x + side * 2.8, 51.5, z - .2),
                 (x + side * 3.7, 56, z), (x + side * 2.3, 59.6 + side * .7, z + .2),
                 (x + side * 1.3, 60.5 + side * .7, z)]
        for i, (a, b) in enumerate(zip(route, route[1:])):
            branch(box, f'queen_scepter_fork_{side}_{i}', 'queen_scepter_crown', a, b, .9 - i * .17, 10)
        for j in range(3):
            p = [x + side * (2.5 + (j % 2) * .8), 51.5 + j * 2.5, z]
            box(f'queen_scepter_leaf_{side}_{j}', 'queen_scepter_crown', p,
                [p[0] - .55, p[1] - 1, p[2] - .25], [1.1, 2.3, .5], 11,
                [10, side * 15, side * (40 + j * 8)])
    box('queen_scepter_seed_case', 'queen_scepter_crown', [x, 55.2, z],
        [x - .95, 54.1, z - .75], [1.9, 2.6, 1.5], 7, [0, 0, 30])
    box('ysmGlowQueenScepterSeed', 'queen_scepter_crown', [x, 55.2, z],
        [x - .55, 54.6, z - .88], [1.1, 1.6, .28], 8, [0, 0, 30])
    for j in range(4):
        yy = y - 2 + j * 1.15
        box('queen_scepter_grip_' + str(j), root, [x, yy, z],
            [x - .65, yy - .22, z - .63], [1.3, .44, 1.26], 3, [0, j * 17, 0])
    group('queen_weapon_stow_locator', 'UpperBody', [0, 32, 3.5])
    group('queen_scepter_tip_locator', 'queen_scepter_crown', [x, 56, z])

    # 保持扩大后的模型纹素密度；下方空区专用于绣花衣料和树皮。
    atlas = atlas.resize((768, 1024), Image.Resampling.NEAREST)
    for bone in model['bones']:
        for cube in bone.get('cubes', []):
            for face in cube['uv'].values():
                face['uv'] = [v * 2 for v in face['uv']]
                face['uv_size'] = [v * 2 for v in face['uv_size']]
    draw = ImageDraw.Draw(atlas)
    cloth(draw, (0, 864), (48, 32, 61), (173, 139, 84))
    cloth(draw, (128, 864), (22, 58, 46), (154, 146, 85))
    cloth(draw, (256, 864), (220, 207, 177), (157, 137, 91))
    bark(draw, (384, 864))
    for bone in model['bones']:
        name = bone['name']
        for cube in bone.get('cubes', []):
            if '_silk_' in name and name.startswith('queen_embroidered'):
                cube['uv']['north'] = {'uv': [0, 864], 'uv_size': [30, 62]}
            elif name.endswith('_silk') and name.startswith('queen_overpetal'):
                cube['uv']['north'] = {'uv': [128, 864], 'uv_size': [30, 120]}
                cube['uv']['south'] = {'uv': [256, 864], 'uv_size': [30, 120]}
            elif name.startswith(root + '_shaft_'):
                for face in ('north', 'south', 'east', 'west'):
                    cube['uv'][face] = {'uv': [384, 864], 'uv_size': [4, 64]}
    return atlas


def branch(box, name, parent, a, b, width, color):
    dx, dy, dz = (b[i] - a[i] for i in range(3))
    length = math.sqrt(dx * dx + dy * dy + dz * dz)
    return box(name, parent, list(a), [a[0] - width / 2, a[1], a[2] - width / 2],
               [width, length, width], color,
               [-math.degrees(math.atan2(dz, math.hypot(dx, dy))), 0,
                math.degrees(math.atan2(dx, dy))])


def cloth(draw, at, base, gold):
    x, y = at
    draw.rectangle((x, y, x + 127, y + 127), fill=base + (255,))
    # 宽缓褶线与少量藤叶刺绣，避免全身被高频噪点盖住。
    for xx in range(6, 128, 16):
        draw.line((x + xx, y, x + xx, y + 127), fill=tuple(max(0, c - 6) for c in base) + (255,))
    for yy in range(5, 126, 20):
        draw.line((x + 7, y + yy, x + 18, y + yy + 12, x + 7, y + yy + 20), fill=gold + (255,), width=1)
        draw.polygon([(x + 14, y + yy + 7), (x + 22, y + yy + 4), (x + 18, y + yy + 11)], fill=gold + (255,))
    draw.line((x + 1, y, x + 1, y + 127), fill=gold + (255,), width=2)
    draw.line((x + 27, y, x + 27, y + 127), fill=gold + (255,), width=1)


def bark(draw, at):
    x, y = at
    draw.rectangle((x, y, x + 63, y + 127), fill=(18, 53, 40, 255))
    for j in range(16):
        xx = x + j * 4
        draw.line([(xx, y), (xx + (j % 3) - 1, y + 29), (xx, y + 73), (xx + 1, y + 127)],
                  fill=(29 + j % 3 * 4, 70 + j % 4 * 3, 48 + j % 3 * 3, 255), width=1)


def support_scepter_poses(model, animations):
    """跪坐时将长杖横向靠在身体外侧，起身时再竖起；枢轴始终留在手掌。"""
    import numpy as np
    from combat_author import euler_matrix, matrix_euler, rot_z
    rig = {b['name']: b for b in model['bones']}
    chain, at = [], 'RightHandLocator'
    while at in rig:
        chain.insert(0, at)
        at = rig[at].get('parent')

    def value(channel, t):
        if not isinstance(channel, dict):
            return np.array(channel, dtype=float)
        keys = sorted((float(k), v) for k, v in channel.items())
        def vector(v):
            return np.array(v.get('post', v.get('pre')) if isinstance(v, dict) else v, dtype=float)
        if t <= keys[0][0]:
            return vector(keys[0][1])
        for (ta, a), (tb, b) in zip(keys, keys[1:]):
            if t <= tb:
                return vector(a) + (vector(b) - vector(a)) * ((t - ta) / (tb - ta))
        return vector(keys[-1][1])

    def hand_rotation(clip, t):
        matrix = np.eye(3)
        for name in chain:
            angles = np.array(rig[name].get('rotation', [0, 0, 0]), dtype=float)
            channels = clip.get('bones', {}).get(name, {})
            if 'rotation' in channels:
                angles += value(channels['rotation'], t)
            matrix = matrix @ euler_matrix(angles * [-1, -1, 1])
        return matrix

    base = np.array(rig['queen_scepter_socket'].get('rotation', [0, 0, 0])) * [-1, -1, 1]
    upright = hand_rotation({}, 0) @ euler_matrix(base)
    for name in ('queen_guard', 'queen_rise'):
        clip = animations['animations'][name]
        track, near = {}, None
        times = [0] if name == 'queen_guard' else [i / 20 for i in range(41)]
        for t in times:
            u = max(0, min(1, t / 1.65)) if name == 'queen_rise' else 0
            u = u * u * (3 - 2 * u)
            desired = rot_z(-80 * (1 - u)) @ upright
            local = matrix_euler(hand_rotation(clip, t).T @ desired, near)
            near = local
            delta = (local - base) * [-1, -1, 1]
            track[str(t)] = {'post': [round(float(v), 5) for v in delta], 'lerp_mode': 'linear'}
        clip['bones']['queen_scepter_socket'] = {'rotation': track if name == 'queen_rise' else track['0']['post']}
