"""追加女王法术、阶段结束与净化剧情动作，保留既有动画和贴图。"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources/assets/logos_dimension'


def track(keys):
    """四元组是时刻与三个旋转角，全部使用平滑插值。"""
    return {str(t): {'post': [x, y, z], 'lerp_mode': 'catmullrom'} for t, x, y, z in keys}


def main():
    path = RES / 'geckolib/animations/entity/corrupted_elf_queen.animation.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    clips = data['animations']
    clips['queen_resist'] = {'animation_length': 6, 'bones': {
        'UpperBody': {'rotation': track([(0, 0, 0, 0), (.4, -10, -15, 12), (1.1, 12, 18, -9), (2, 8, 8, 5), (4.8, 6, 5, 3), (5.6, -6, -10, 8), (6, 0, 0, 0)])},
        'Head': {'rotation': track([(0, 0, 0, 0), (.5, -22, -18, 12), (1.2, 15, 20, -8), (2.5, 8, 2, 0), (4.8, 10, -6, -4), (6, 0, 0, 0)])},
        'RightArm': {'rotation': track([(0, 0, 0, 0), (.6, -100, -15, 65), (1.2, -55, -35, 30), (2, -40, -10, 25), (5.2, -38, -8, 23), (6, 0, 0, 0)])},
        'RightForeArm': {'rotation': track([(0, 0, 0, 0), (.8, -55, 0, 10), (2, -75, 0, 15), (5.2, -72, 0, 14), (6, 0, 0, 0)])},
        'LeftArm': {'rotation': track([(0, 0, 0, 0), (.7, -75, 15, -55), (1.4, -55, -18, -30), (2.1, -48, -30, -20), (5.3, -50, -28, -22), (6, 0, 0, 0)])},
        'LeftForeArm': {'rotation': track([(0, 0, 0, 0), (.8, -95, 0, -18), (2, -115, -15, -30), (5.3, -112, -15, -28), (6, 0, 0, 0)])},
    }}
    clips['queen_aid'] = {'animation_length': 6, 'bones': {
        'UpperBody': {'rotation': track([(0, 0, 0, 0), (.65, 12, -10, -5), (2, 9, -5, -3), (4.8, 10, -7, -4), (6, 0, 0, 0)])},
        'Head': {'rotation': track([(0, 0, 0, 0), (.6, 12, 15, 0), (2, 4, 10, 0), (5, 6, 12, 0), (6, 0, 0, 0)])},
        'RightArm': {'rotation': track([(0, 0, 0, 0), (.7, -35, -20, 30), (2, -32, -15, 26), (5, -33, -15, 28), (6, 0, 0, 0)])},
        'RightForeArm': {'rotation': [-25, 0, 0]},
        'LeftArm': {'rotation': track([(0, 0, 0, 0), (.75, -135, 0, -48), (2, -130, 0, -42), (4.8, -132, 0, -44), (6, 0, 0, 0)])},
        'LeftForeArm': {'rotation': [-35, 0, -10]},
    }}
    clips['queen_bind'] = {'loop': True, 'animation_length': 4, 'bones': {
        'UpperBody': {'rotation': track([(0, 14, 0, 0), (2, 17, 0, 1), (4, 14, 0, 0)])},
        'Head': {'rotation': track([(0, -18, 0, 0), (2, -15, 0, -2), (4, -18, 0, 0)])},
        'RightArm': {'rotation': [-38, -10, 30]}, 'RightForeArm': {'rotation': [-38, 0, 8]},
        'LeftArm': {'rotation': [-145, 0, -35]}, 'LeftForeArm': {'rotation': [-45, 0, -10]},
        'LeftLeg': {'rotation': [-8, 0, -6]}, 'RightLeg': {'rotation': [7, 0, 6]},
    }}
    clips['queen_sacrifice'] = {'animation_length': 4, 'bones': {
        'UpperBody': {'rotation': track([(0, 14, 0, 0), (1, 8, 0, 0), (2.7, 18, 0, 0), (4, 10, 0, 0)])},
        'Head': {'rotation': track([(0, -18, 0, 0), (1, 5, 0, 0), (2.7, 18, 0, 0), (4, 12, 0, 0)])},
        'RightArm': {'rotation': track([(0, -38, -10, 30), (1, -75, -10, 38), (2, -82, -25, 15), (4, -85, -25, 14)])},
        'RightForeArm': {'rotation': track([(0, -38, 0, 8), (1, -55, 0, 10), (2, -105, 0, 20), (4, -105, 0, 20)])},
        'LeftArm': {'rotation': track([(0, -145, 0, -35), (1, -80, 10, -38), (2, -82, 25, -15), (4, -85, 25, -14)])},
        'LeftForeArm': {'rotation': track([(0, -45, 0, -10), (1, -55, 0, -10), (2, -105, 0, -20), (4, -105, 0, -20)])},
        'queen_weapon_branch_scepter': {'scale': track([(0, 1, 1, 1), (.75, 1, 1, 1), (1, 0, 0, 0), (4, 0, 0, 0)])},
    }}
    # 第一条血结束：失去支撑、跪撑挣扎，再被血管拽起；第二条血结束：双臂扯断控制，迎住坠落核心。
    clips['queen_chain_break'] = {'animation_length': 3, 'bones': {
        'AllBody': {'position': track([(0, 0, 0, 0), (.7, 0, -12, 0), (1.5, 0, -15, 0), (2.6, 0, -4, 0), (3, 0, 0, 0)])},
        'UpperBody': {'rotation': track([(0, 0, 0, 0), (.45, -18, -12, 8), (1, 34, 9, -12), (1.8, 28, -8, 6), (2.5, -12, 6, 3), (3, 0, 0, 0)])},
        'Head': {'rotation': track([(0, 0, 0, 0), (.5, -30, -15, 10), (1.1, 28, 12, -15), (1.8, 20, -15, 5), (2.6, -18, 0, -5), (3, 0, 0, 0)])},
        'RightArm': {'rotation': track([(0, 0, 0, 0), (.5, -135, -20, 55), (1.1, -30, -35, 22), (1.9, -45, -15, 35), (2.6, -95, -25, 58), (3, 0, 0, 0)])},
        'RightForeArm': {'rotation': track([(0, 0, 0, 0), (.55, -65, 0, 10), (1.2, -105, -10, 22), (2.4, -80, 0, 10), (3, 0, 0, 0)])},
        'LeftArm': {'rotation': track([(0, 0, 0, 0), (.5, -125, 20, -60), (1.1, -40, 25, -30), (1.9, -65, 15, -45), (2.6, -115, 15, -60), (3, 0, 0, 0)])},
        'LeftForeArm': {'rotation': track([(0, 0, 0, 0), (.6, -65, 0, -10), (1.2, -120, 0, -15), (2.4, -75, 0, -10), (3, 0, 0, 0)])},
        'RightLeg': {'rotation': track([(0, 0, 0, 0), (.8, -65, 0, 8), (1.8, -70, 0, 8), (3, 0, 0, 0)])},
        'LeftLeg': {'rotation': track([(0, 0, 0, 0), (.8, -25, 0, -12), (1.8, -30, 0, -10), (3, 0, 0, 0)])},
    }}
    clips['queen_core_fall'] = {'animation_length': 3.2, 'bones': {
        'UpperBody': {'rotation': track([(0, 0, 0, 0), (.6, -20, -18, 8), (1.15, -8, 22, -10), (1.7, 22, 0, 0), (2.6, 16, 0, 0), (3.2, 14, 0, 0)])},
        'Head': {'rotation': track([(0, 0, 0, 0), (.6, -38, 15, 0), (1.4, -28, -15, 0), (2.1, -20, 0, 0), (3.2, -18, 0, 0)])},
        'RightArm': {'rotation': track([(0, 0, 0, 0), (.6, -145, -15, 28), (1.1, -125, -20, 75), (1.5, -65, -15, 85), (2.3, -42, -10, 35), (3.2, -38, -10, 30)])},
        'RightForeArm': {'rotation': track([(0, 0, 0, 0), (.6, -75, 0, 15), (1.5, -12, 0, 5), (2.3, -40, 0, 10), (3.2, -38, 0, 8)])},
        'LeftArm': {'rotation': track([(0, 0, 0, 0), (.6, -155, 20, -28), (1.1, -140, 25, -80), (1.5, -70, 15, -90), (2.3, -148, 0, -40), (3.2, -145, 0, -35)])},
        'LeftForeArm': {'rotation': track([(0, 0, 0, 0), (.6, -75, 0, -15), (1.5, -10, 0, -5), (2.3, -48, 0, -10), (3.2, -45, 0, -10)])},
        'LeftLeg': {'rotation': [-8, 0, -6]}, 'RightLeg': {'rotation': [7, 0, 6]},
    }}
    # 抬手、持杖蓄势、释放、收势四段；剑雨持续举杖，不在发射期间回待机。
    for name, length, release in [('queen_life_fan', 3, 1.3), ('queen_life_ray', 3.4, 1.5), ('queen_sword_rain', 4.5, 1.8)]:
        last = 3.45 if name == 'queen_sword_rain' else release + .25
        clips[name] = {'animation_length': length, 'bones': {
            'UpperBody': {'rotation': track([(0, 0, 0, 0), (.8, -6, -10, 4), (release, -4, -8, 2), (last, 6, 5, -3), (length, 0, 0, 0)])},
            'Head': {'rotation': track([(0, 0, 0, 0), (.8, -12, 5, 0), (last, -8, 0, 0), (length, 0, 0, 0)])},
            'RightArm': {'rotation': track([(0, 0, 0, 0), (.75, -105, -12, 28), (release, -102, -10, 26), (last, -82, 8, 20), (length, 0, 0, 0)])},
            'RightForeArm': {'rotation': track([(0, 0, 0, 0), (.8, -40, 0, 8), (last, -30, 0, 5), (length, 0, 0, 0)])},
            'LeftArm': {'rotation': track([(0, 0, 0, 0), (.8, -88, 18, -35), (release, -100, 15, -30), (last, -112, 10, -20), (length, 0, 0, 0)])},
            'LeftForeArm': {'rotation': track([(0, 0, 0, 0), (.8, -35, 0, -10), (last, -20, 0, -8), (length, 0, 0, 0)])},
        }}
    # 命中标记与服务端招式来自同一份时间表，避免动画和伤害各自改时间。
    contacts = {'queen_reap': [1.2], 'queen_roots': [t / 20 for t in range(30, 59, 2)], 'queen_thorns': [1.7],
                'queen_life_fan': [1.3], 'queen_life_ray': [1.5], 'queen_sword_rain': [round(1.8 + i * .15, 2) for i in range(12)]}
    moves = {}
    lengths = {'queen_guard': 4, 'queen_idle': 2, 'queen_rise': 2, 'queen_entrance': 5, 'queen_reap': 2.4,
               'queen_roots': 3.8, 'queen_thorns': 3.1, 'queen_resist': 6, 'queen_aid': 6, 'queen_bind': 4, 'queen_sacrifice': 4,
               'queen_life_fan': 3, 'queen_life_ray': 3.4, 'queen_sword_rain': 4.5, 'queen_chain_break': 3, 'queen_core_fall': 3.2}
    for name, length in lengths.items():
        move = {'clip': 'idle' if name == 'queen_idle' else name, 'length': length}
        if name in ('queen_guard', 'queen_idle', 'queen_bind'):
            move['playback'] = 'loop'
        else:
            move['next'] = 'queen_idle'
        if name == 'queen_chain_break': move['next'] = 'queen_aid'
        if name == 'queen_core_fall': move['next'] = 'queen_bind'
        if name in contacts:
            move['contact'] = contacts[name]
            move['damage'] = {'queen_reap': 8, 'queen_roots': 7, 'queen_thorns': 10}.get(name, 1)
            clips[name]['timeline'] = {str(t): 'contact' for t in contacts[name]}
        moves[name] = move
    folder = ROOT / 'src/main/resources/data/logos_dimension/combat/movesets'
    (folder / 'corrupted_queen.json').write_text(json.dumps({'start': 'queen_guard', 'moves': moves}, indent=2) + '\n', encoding='utf-8')
    (folder / 'queen_core.json').write_text(json.dumps({'start': 'core_thorns', 'moves': {
        'core_thorns': {'length': 2.25, 'contact': [1.6], 'damage': 9, 'effect': 'line', 'next': 'core_wave'},
        'core_wave': {'length': 1.8, 'contact': [t / 20 for t in range(0, 36, 3)], 'damage': 7, 'effect': 'ring', 'next': 'core_thorns'},
    }}, indent=2) + '\n', encoding='utf-8')
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    names = {
        'zh_cn': {
            'entity.logos_dimension.queen_tether': '核心血管',
            'gui.logos_dimension.queen.roots': '断链 %s/3',
            'boss.logos_dimension.queen.phase1': '腐化女王 · 牵丝之冠',
            'boss.logos_dimension.queen.phase2': '腐化女王 · 逆流之心',
            'boss.logos_dimension.queen.phase3': '腐化女王 · 共殒',
            'message.logos_dimension.queen.resist': '她在反抗——斩断暴露的血管根结！',
            'message.logos_dimension.queen.phase2': '核心外壳破裂。她正在争夺自己的身体。',
            'message.logos_dimension.queen.phase3': '她困住了核心！切断三条供养血管，再击碎核心。',
            'message.logos_dimension.queen.root_cut': '供养血管断裂。',
            'message.logos_dimension.queen.last_words': '「我的森林……还给我。」',
        },
        'en_us': {
            'entity.logos_dimension.queen_tether': 'Heart Vessel',
            'gui.logos_dimension.queen.roots': 'Severed %s/3',
            'boss.logos_dimension.queen.phase1': 'The Corrupted Queen · Puppet Crown',
            'boss.logos_dimension.queen.phase2': 'The Corrupted Queen · Defiant Heart',
            'boss.logos_dimension.queen.phase3': 'The Corrupted Queen · Shared Ruin',
            'message.logos_dimension.queen.resist': 'She is resisting. Sever the exposed root vessels!',
            'message.logos_dimension.queen.phase2': 'The heart cracks. She fights for control.',
            'message.logos_dimension.queen.phase3': 'She binds the heart! Sever its three vessels, then shatter it.',
            'message.logos_dimension.queen.root_cut': 'A feeding vessel is severed.',
            'message.logos_dimension.queen.last_words': '"Give me back... my forest."',
        },
    }
    for language, additions in names.items():
        path = RES / 'lang' / (language + '.json')
        data = json.loads(path.read_text(encoding='utf-8'))
        data.update(additions)
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
