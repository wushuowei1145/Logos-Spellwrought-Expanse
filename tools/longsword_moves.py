# -*- coding: utf-8 -*-
"""长剑共用的被弹反动作 parried 和 parried_npc：编排、烘焙，写进 combat/animations/longsword_{tp,fp}.animation.json。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/longsword_moves.py [预览输出目录]

美术包里没有这段动作，所以用 combat_author 按刀路编排（编排坐标见 combat_author.py 开头）。其余长剑动作由
tools/import_longswords.py 从美术工程生成；它写完动作文件后会调用这里，把这两段补回去。

剑被猛地磕向右后上方，左手脱开，身子后仰、胸口敞开，顶住一下露出破绽，再双手握回待机（长剑 idle 第 0 帧，接进待机不跳）。
"""
import json
import sys
from pathlib import Path

import numpy as np

from combat_author import Baker, bedrock, npc_parried
from dawnvow_moves import COMBAT, idle_pose, k, mesh, skeletons

LENGTH = 0.5
# 相对待机握点的位移：出招的起手、磕飞的顶点（第三人称、第一人称各一份，第一人称要留在画面里）。
START, PEAK, SAG = {'tp': (0.0, 0.24, -0.1), 'fp': (-0.08, 0.1, -0.2)}, \
    {'tp': (0.5, 0.42, 0.34), 'fp': (-0.04, 0.2, -0.2)}, \
    {'tp': (0.47, 0.32, 0.36), 'fp': (-0.05, 0.13, -0.2)}
BLADE = {'tp': (0.68, 0.68, 0.28), 'fp': (0.2, 0.92, -0.34)}
EDGE = {'tp': (0.72, -0.62, -0.3), 'fp': (0.8, -0.5, 0.3)}
UP = {'tp': (14, -24, 0), 'fp': (6, -10, 0)}
CHEST = {'tp': (4, -10, 0), 'fp': (0, 0, 0)}
LHAND = (-0.68, -0.52, 0.16)


def clip(view, idle):
    base = np.asarray(idle['grip'])
    start = tuple(base + START[view])
    peak = dict(grip=tuple(base + PEAK[view]), blade=BLADE[view], edge=EDGE[view], up=UP[view], chest=CHEST[view],
                lhand=LHAND, hips=(0, -0.05, 0.08))
    return dict(name='parried', length=LENGTH, loop='once', keys=[
        k(0.0, grip=start, blade=(0.28, 0.6, -0.75), edge=(0.92, -0.2, 0.34), up=(0, -8, 0), rmode='sword', lmode='free',
          lhand=(-0.42, -0.5, -0.32)),
        k(0.07, peak, ease='hold'),
        k(0.32, peak, grip=tuple(base + SAG[view]), ease='hold'),
        # 左手在动作结束前回到剑柄（握法切换从这一帧开始过渡）。
        k(0.36, lmode='two', blend=0.14),
        k(LENGTH, idle, hips=(0, 0, 0), chest=(0, 0, 0)),
    ])


def baked_views():
    """两个视角各烘焙一份：{视角: (骨架, [(编排, 逐帧姿势), ...])}，parried 在前。"""
    tp, fp = skeletons()
    out = {}
    for view, sk in (('tp', tp), ('fp', fp)):
        idle, root = idle_pose(view, sk)
        if root is not None:
            sk.root = root
        c = clip(view, idle)
        baker = Baker(sk)
        out[view] = (sk, [(each, baker.bake(each, 60)) for each in (c, npc_parried(c))])
        for name, t, side, over in baker.warnings:
            print(f'{view} {name} {side} {t:.2f}s 手够不着，差 {over:.2f} 臂长')
    return out


def write():
    for view, (sk, baked) in baked_views().items():
        bones = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
                 'LeftArm', 'LeftForeArm', 'LeftHand', 'Weapon'] + (['Root'] if view == 'fp' else [])
        path = COMBAT / f'animations/longsword_{view}.animation.json'
        shared = json.load(open(path, encoding='utf-8'))
        for c, frames in baked:
            data = bedrock([c], {c['name']: frames}, sk, bones, markers=False)['animations'][c['name']]
            # 剑鞘不动，沿用 idle 的剑鞘轨道。
            data['bones']['Sheath'] = shared['animations']['idle']['bones']['Sheath']
            shared['animations'][c['name']] = data
        path.write_text(json.dumps(shared, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8', newline='\n')
        print('wrote parried clips into', path.name)


def preview(out):
    from PIL import Image
    from combat_author import euler_matrix
    from combat_render import Camera, mannequin_quads, render, sheet, weapon_quads
    out.mkdir(parents=True, exist_ok=True)
    sk, baked = baked_views()['tp']
    frames = baked[0][1]
    weapon = json.load(open(COMBAT / 'weapons/blacksteel_longsword.json', encoding='utf-8'))
    tex = np.asarray(Image.open(Path(__file__).resolve().parent.parent
                                / 'src/main/resources/assets/logos_dimension/textures/item/blacksteel_longsword_rig.png').convert('RGBA')).astype(np.float32)
    cams = [Camera(-35, 8, 6.2, (0, 16, -2), (230, 270)), Camera(80, 6, 6.2, (0, 16, -2), (230, 270)),
            Camera(180, 10, 6.2, (0, 16, -2), (230, 270))]
    rows = []
    for v, cam in enumerate(cams):
        panels = []
        for t in (0, 0.03, 0.07, 0.15, 0.25, 0.32, 0.4, 0.5):
            i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
            pose = {name: (p, euler_matrix(e)) for name, (p, e) in frames[i][1].items()}
            W = sk.world(pose)
            panels.append((f'{t:.2f}s', render(weapon_quads(weapon, tex, mesh(sk, W), 'weapon') + mannequin_quads(W), cam)))
        rows.append((('左前', '右侧', '背后')[v], panels))
    sheet(rows, 'parried 0.5s', out / 'parried.png')


if __name__ == '__main__':
    write()
    if len(sys.argv) > 1:
        preview(Path(sys.argv[1]))
