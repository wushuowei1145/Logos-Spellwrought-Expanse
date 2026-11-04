# -*- coding: utf-8 -*-
"""曦誓自己的拔剑、收剑动作：编排、烘焙、导出与预览。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/dawnvow_moves.py [预览输出目录] [--gif]

- 拔剑 dawnvow_draw：双臂伸直到身前、掌心向上，剑横着在掌上凝成（光点见 client/vfx/DawnvowSummon）；
  右手握住剑柄，在身体右侧手腕转两圈剑花，左手合上，回到长剑的待机架势。
- 收剑 dawnvow_sheathe：双手把剑倒转举起，猛地插进身前的地面，松手，剑随后散成光点。

动作写进 assets/logos_dimension/combat/animations/dawnvow_{tp,fp}.animation.json，长剑骨架加载时叠在长剑动作上
（见 LongswordRig）。拔剑的终点从长剑现有的 idle 第 0 帧反算出来（剑柄位置、剑尖与刃的朝向、躯干转角、第一人称根骨），
接进待机不跳。编排坐标见 combat_author.py 开头。
"""
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

import combat_author
from combat_author import Baker, bedrock, euler_matrix, player_skeleton
from combat_render import Camera, mannequin_quads, render, sheet, weapon_quads

ROOT = Path(__file__).resolve().parent.parent
COMBAT = ROOT / 'src/main/resources/assets/logos_dimension/combat'
# 曦誓的握柄在网格里从 -3.2 到 3.2：右手握靠护手的一半，左手握靠剑首的一半。
combat_author.PRIMARY = 1.6
combat_author.SECONDARY = -1.6
TIP = np.array([0, 23.4, 0, 1])

HANG_R = (0.42, -0.98, 0.04)
HANG_L = (-0.42, -0.98, 0.04)
# 双臂伸直到身前、掌心向上（手指朝前）。
REACH_R = (0.3, -0.16, -0.92)
REACH_L = (-0.34, -0.16, -0.92)
PALM_UP = (-90, 0, 0)
# 剑横在掌上：剑柄在右掌上方，剑尖指向左边，刃略向前上，从正面和第一人称都看得到剑面。
FLOAT = dict(grip=(0.3, -0.08, -0.92), blade=(-1, 0, 0), edge=(0, 0.55, -0.85))


def k(t, base=None, **kw):
    key = dict(base or {})
    key.update(kw)
    key['t'] = t
    return key


def decode(track):
    """Bedrock 轨道第 0 帧换回编排用的欧拉角或位置（导出时 x、y 旋转与 x 位置取了反）。"""
    value = track[sorted(track, key=float)[0]]
    return np.array(value, float)


def idle_pose(view, sk):
    """长剑 idle 第 0 帧的剑与躯干，写成编排的一帧；第一人称另给出根骨。"""
    clip = json.load(open(COMBAT / f'animations/longsword_{view}.animation.json', encoding='utf-8'))['animations']['idle']['bones']
    r = decode(clip['UpBody']['rotation'])
    up = (-r[0], -r[1], r[2])
    r = decode(clip['Weapon']['rotation']); p = decode(clip['Weapon']['position'])
    R = euler_matrix((-r[0], -r[1], r[2]))
    origin = np.array([-p[0], p[1], p[2]]) + sk.weapon_pivot
    grip = origin + R @ np.array([0, combat_author.PRIMARY * sk.weapon_scale, 0])
    key = dict(grip=tuple((grip - sk.center - getattr(sk, 'reach', 0)) / sk.arm), blade=tuple(R[:, 1]), edge=tuple(R[:, 0]),
               up=up, lmode='two', rmode='sword')
    root = None
    if 'Root' in clip:
        r = decode(clip['Root']['rotation'])
        p = decode(clip['Root'].get('position', {'0.0': [0, 0, 0]}))
        root = ((-p[0], p[1], p[2]), (-r[0], -r[1], r[2]))
    return key, root


def spin_keys(t0, t1, turns, grip, tilt=12, fp_grip=None):
    """手腕转剑：剑在身体右侧的竖直面里绕握点转圈，剑尖 前 → 下 → 后 → 上。
    fp_grip 给出第一人称的握点（第一人称看不到身体右下侧，挪到画面右前方）。"""
    keys = []
    steps = int(turns * 8)
    axis_tilt = euler_matrix((0, 0, -tilt))
    for i in range(steps + 1):
        th = math.radians(360 * turns * i / steps)
        blade = axis_tilt @ np.array([0, -math.sin(th), -math.cos(th)])
        edge = axis_tilt @ np.array([0, -math.cos(th), math.sin(th)])
        wob = 0.04 * math.sin(th)
        key = dict(t=t0 + (t1 - t0) * i / steps, grip=(grip[0], grip[1] + wob, grip[2]),
                   blade=tuple(blade), edge=tuple(edge))
        if fp_grip is not None:
            key['fp'] = dict(grip=(fp_grip[0], fp_grip[1] + wob, fp_grip[2]))
        keys.append(key)
    return keys


# 动作时刻（秒），与 client/vfx/DawnvowSummon 对齐：剑在 FORM 时凝成；收剑 PLUNGE 时插进地面、RELEASE 时松手、VANISH 时散去。
DRAW_LENGTH, FORM, GRAB, SPIN_FROM, SPIN_TO = 1.8, 0.7, 0.84, 0.98, 1.52
# 第一人称看不到身体右下侧与脚下：剑花挪到画面右前方，插地的位置往前、往上挪，落在画面下方。
SPIN_FP = (0.28, -0.12, -0.8)
PLANT_FP = (0.1, -0.42, -1.0)
SHEATHE_LENGTH, PLUNGE, RELEASE, VANISH = 1.0, 0.3, 0.52, 0.62


def clips(idle):
    draw = [
        k(0.0, FLOAT, rmode='free', rhand=HANG_R, lmode='free', lhand=HANG_L, up=(0, 0, 0)),
        # 双臂抬起伸直到身前，掌心向上；剑在掌上方横着凝成
        k(0.3, FLOAT, rhand=REACH_R, lhand=REACH_L, rhand_rot=PALM_UP, lhand_rot=PALM_UP, ease='hold'),
        k(FORM, FLOAT, rhand=REACH_R, lhand=REACH_L, ease='hold'),
        # 右手握住剑柄，左手还托着剑身
        k(GRAB, FLOAT, rmode='sword', blend=0.12),
        # 剑转到身体右侧、剑尖朝前，左手放下
        k(SPIN_FROM, grip=(0.55, -0.3, -0.55), blade=(0, 0, -1), edge=(0, -1, 0), lmode='free',
          lhand=HANG_L, lhand_rot=(0, 0, 0), blend=0.12, fp=dict(grip=SPIN_FP)),
    ]
    draw += spin_keys(SPIN_FROM, SPIN_TO, 2, grip=(0.55, -0.3, -0.55), fp_grip=SPIN_FP)[1:]
    draw += [
        k(1.64, grip=(0.4, -0.32, -0.6), blade=(-0.1, 0.4, -0.9), edge=(0, -0.9, -0.4), lmode='two', blend=0.14),
        k(DRAW_LENGTH, idle),
    ]
    down = dict(blade=(0, -1, -0.12), edge=(0, 0, -1), lmode='two', rmode='sword')
    sheathe = [
        k(0.0, idle),
        # 双手把剑倒转举到胸前，剑尖朝下
        k(0.15, down, grip=(0.06, 0.08, -0.58), up=(0, 0, 0), fp=dict(grip=(0.1, 0.12, -0.85))),
        # 猛地插进身前的地面
        k(PLUNGE, down, grip=(0.06, -0.8, -0.64), blade=(0, -1, -0.04), fp=dict(grip=PLANT_FP), hips=(0, -0.12, 0), ease='lin'),
        k(RELEASE - 0.06, down, grip=(0.06, -0.8, -0.64), blade=(0, -1, -0.04), fp=dict(grip=PLANT_FP), hips=(0, -0.12, 0), ease='hold'),
        # 松手，双手放回身侧；剑留在地上，随后散成光点
        k(RELEASE + 0.08, down, grip=(0.06, -0.8, -0.64), blade=(0, -1, -0.04), fp=dict(grip=PLANT_FP), rmode='free', lmode='free', blend=0.1,
          rhand=(0.36, -0.78, -0.32), lhand=(-0.36, -0.78, -0.32), hips=(0, -0.06, 0)),
        k(0.85, down, grip=(0.06, -0.8, -0.64), blade=(0, -1, -0.04), fp=dict(grip=PLANT_FP), rmode='free', lmode='free',
          rhand=HANG_R, lhand=HANG_L, hips=(0, 0, 0)),
        k(SHEATHE_LENGTH, down, grip=(0.06, -0.8, -0.64), blade=(0, -1, -0.04), fp=dict(grip=PLANT_FP), rmode='free', lmode='free',
          rhand=HANG_R, lhand=HANG_L, hips=(0, 0, 0)),
    ]
    return [dict(name='dawnvow_draw', length=DRAW_LENGTH, loop='once', keys=draw),
            dict(name='dawnvow_sheathe', length=SHEATHE_LENGTH, loop='once', keys=sheathe)]


def skeletons():
    carry = (np.zeros(3), np.eye(3))
    tp = player_skeleton(COMBAT / 'skeletons/player_tp.json', weapon_scale=0.8, carry=carry)
    fp = player_skeleton(COMBAT / 'skeletons/player_fp.json', weapon_scale=0.58, carry=carry)
    tp.body = ((-4.4, 12.0, -2.5), (4.4, 21.0, 2.5))
    tp.tag, fp.tag = 'tp', 'fp'
    return tp, fp


def mesh(sk, W):
    s = sk.weapon_scale
    T = np.eye(4); T[:3, 3] = sk.weapon_pivot
    return W['Weapon'] @ T @ np.diag([s, s, s, 1.0])


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    preview = Path(args[0]) if args else None
    tp, fp = skeletons()
    baked = {}
    exported = {}
    for view, sk in (('tp', tp), ('fp', fp)):
        idle, root = idle_pose(view, sk)
        if root is not None:
            sk.root = root
        all_clips = clips(idle)
        baker = Baker(sk)
        for clip in all_clips:
            baked[(view, clip['name'])] = baker.bake(clip, 60)
        for clip_name, t, side, over in baker.warnings:
            print(f'{view} {clip_name} {side} {t:.2f}s 手够不着，差 {over:.2f} 臂长')
        bones = ['UpBody', 'UpperBody', 'Head', 'RightArm', 'RightForeArm', 'RightHand',
                 'LeftArm', 'LeftForeArm', 'LeftHand', 'Weapon', 'Sheath'] + (['Root'] if view == 'fp' else [])
        data = bedrock(all_clips, {c['name']: baked[(view, c['name'])] for c in all_clips}, sk, bones, markers=False)
        (COMBAT / f'animations/dawnvow_{view}.animation.json').write_text(
            json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
        exported[view] = all_clips
        print('wrote', f'dawnvow_{view}.animation.json')
    if preview is None:
        return
    preview.mkdir(parents=True, exist_ok=True)
    weapon = json.load(open(COMBAT / 'weapons/dawnvow.json', encoding='utf-8'))
    tex = np.asarray(Image.open(ROOT / 'src/main/resources/assets/logos_dimension/textures/item/dawnvow_rig.png').convert('RGBA')).astype(np.float32)
    cams = [Camera(-35, 8, 6.2, (0, 16, -2), (230, 270)), Camera(80, 6, 6.2, (0, 16, -2), (230, 270)),
            Camera(180, 10, 6.2, (0, 16, -2), (230, 270))]
    for clip in exported['tp']:
        frames = baked[('tp', clip['name'])]
        n = 10
        times = [clip['length'] * i / (n - 1) for i in range(n)]
        rows = []
        for v, cam in enumerate(cams):
            panels = []
            for t in times:
                i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
                pose = {name: (p, euler_matrix(e)) for name, (p, e) in frames[i][1].items()}
                W = tp.world(pose)
                quads = weapon_quads(weapon, tex, mesh(tp, W), 'weapon') + mannequin_quads(W)
                trail = []
                for j in range(max(0, i - 12), i + 1):
                    pj = {name: (p, euler_matrix(e)) for name, (p, e) in frames[j][1].items()}
                    trail.append((mesh(tp, tp.world(pj)) @ TIP)[:3])
                panels.append((f'{t:.2f}s', render(quads, cam, trail=trail if len(trail) > 1 else None)))
            rows.append((('左前', '右侧', '背后')[v], panels))
        sheet(rows, f"{clip['name']}  {clip['length']}s", preview / f"{clip['name']}.png")
        print('rendered', clip['name'])


if __name__ == '__main__':
    main()
