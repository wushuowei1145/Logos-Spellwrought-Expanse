# -*- coding: utf-8 -*-
"""惊鸿自己的动作：横剑格挡、弹剑、掷剑。其余动作（拔剑、四式连招、被弹反、归鞘）共用凌霄的。

用法（需要 numpy、pillow）：
    uv run --with numpy --with pillow python tools/jinghong_moves.py <预览输出目录> [动作名…] [--fp]

烘焙结果写进 combat/animations/jinghong_sword_<视角>.animation.json，按 LongswordRig 的规则盖在凌霄的动作上
（同名的以惊鸿的为准）。编排坐标、关键帧字段同 tools/lingxiao_moves.py 和 combat_author.py。

- 格挡（guard）：参考植物大战僵尸 2 里横剑的那种架势：剑横在胸前、剑尖指向左方，剑面朝前，
  左手两指搭在剑身中段托住。原版格挡组件，开头 0.3 秒弹剑。
- 弹剑（parry）：剑尖往左前上方一磕，再落回格挡。
- 掷剑（throw）：格挡中按左键。剑收到右肩后、剑尖朝前，一甩掷出（release），右手捏剑指指着目标；
  剑飞回来时张手接住（catch）。release 到 catch 之间骨架里的剑缩成 0，由客户端在世界里画飞出去的剑。
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lingxiao_moves as lx  # noqa: E402
from combat_author import Baker, bedrock  # noqa: E402
from combat_render import Camera, mannequin_quads, render, sheet, weapon_quads  # noqa: E402

ASSETS = lx.ASSETS
MESH = json.loads((ASSETS / 'combat/weapons/jinghong_sword.json').read_text(encoding='utf-8'))
# 骨架挂上惊鸿自己的骨骼（与凌霄同名，剑穗枢轴不同）。
lx.WEAPON = MESH
TIP = np.array([0, MESH['tip'][1], 0, 1])

# 掷剑：剑离手、回到手里的时刻（秒）。服务端在 release 结算掷出，客户端在两者之间画飞剑（SwordFlight.THROW_*）。
RELEASE = 0.14
CATCH = 0.62

k = lx.k
IDLE = lx.IDLE

# 横剑：握点在右胸前，剑尖指向左方略朝前，刃朝上、剑面朝前；左手两指搭在剑身中段（剑面后侧）。
GUARD = dict(grip=(0.40, -0.18, -0.66), blade=(-0.96, 0.06, -0.24), edge=(0.02, 1, 0.06),
             rmode='sword', lmode='weapon', lweapon=(0, 12.5, 1.1), up=(6, -6, 0), chest=(-2, 0, 0), hips=(0, -0.05, 0),
             rfoot=(0.22, 0, -0.18, -14), lfoot=(-0.24, 0, 0.16, 20),
             # 第一人称：剑横在准星下方，剑柄在右下、剑尖指向画面左边；左手在画面外。
             # 第一人称剑面转向镜头（刃朝前上），剑身上的激活刻度才看得见。
             fp=dict(grip=(0.22, -0.40, -0.62), blade=(-0.95, 0.06, -0.3), edge=(0.02, 0.45, -0.89), up=(2, -2, 0),
                     lmode='free', lhand=(-0.6, -0.8, 0.1)))


def clips():
    out = []
    out.append(dict(name='guard', length=1.6, loop='loop', keys=[
        k(0.0, GUARD),
        k(0.8, GUARD, grip=(0.40, -0.16, -0.67), up=(7, -5, 0), chest=(-3, 0, 0)),
        k(1.6, GUARD),
    ]))
    # 弹剑：剑尖往左前上方一磕（把来剑拨开），顿一下再落回横剑。
    flick = dict(grip=(0.46, 0.02, -0.74), blade=(-0.62, 0.48, -0.62), edge=(0.25, 0.62, -0.74), up=(2, 14, 0),
                 lweapon=(0, 8, 1.1),
                 fp=dict(grip=(0.26, -0.28, -0.66), blade=(-0.7, 0.45, -0.55), edge=(0.25, 0.7, -0.66), up=(1, 5, 0),
                         lmode='free', lhand=(-0.6, -0.8, 0.1)))
    out.append(dict(name='parry', length=0.36, loop='once', keys=[
        k(0.0, GUARD),
        k(0.05, GUARD, **flick, ease='hold'),
        k(0.18, GUARD, **flick, ease='hold'),
        k(0.36, GUARD),
    ]))
    # 掷剑：收到右肩后（剑尖朝前），甩手掷出；右手顺势捏剑指指着目标，定住；剑飞回时张手接住，收回待机。
    cock = dict(grip=(0.56, 0.30, 0.06), blade=(0.02, 0.10, -1), edge=(1, 0, 0), up=(-2, -30, 0), chest=(-4, 0, 0),
                lmode='free', lhand=(-0.24, 0.02, -0.86), hips=(0, -0.04, 0.04),
                rfoot=(0.2, 0, 0.04, -12), lfoot=(-0.24, 0, 0.22, 22),
                fp=dict(grip=(0.46, -0.06, -0.34), blade=(0.0, 0.08, -1), edge=(1, 0, 0), up=(-1, -8, 0)))
    release = dict(grip=(0.40, 0.14, -0.86), blade=(0.0, 0.04, -1), edge=(1, 0, 0), up=(6, 18, 0),
                   lhand=(-0.54, -0.30, 0.22), hips=(0, -0.08, -0.12),
                   rfoot=(0.2, 0, -0.34, -10), lfoot=(-0.24, 0, 0.26, 22),
                   fp=dict(grip=(0.28, -0.18, -0.66), blade=(0.0, 0.05, -1), up=(2, 5, 0)))
    point = dict(rmode='free', rhand=(0.26, 0.06, -0.96), rhand_rot=(-90, 0, 0), blend=0.04,
                 fp=dict(rhand=(0.30, -0.25, -0.62)))
    catch_at = dict(grip=(0.42, -0.06, -0.74), blade=(0.06, 0.55, -0.83), edge=(1, 0, 0), rmode='sword', blend=0.05,
                    up=(2, 6, 0), lhand=(-0.3, -0.6, 0.1), hips=(0, -0.04, -0.04),
                    fp=dict(grip=(0.34, -0.30, -0.62), blade=(-0.05, 0.6, -0.8)))
    out.append(dict(name='throw', length=0.9, loop='once', markers={'contact': RELEASE, 'release': RELEASE, 'catch': CATCH}, keys=[
        k(0.0, GUARD),
        # 左手先松开剑身，不然剑往右肩收时左手追不上
        k(0.03, lmode='free', lhand=(-0.22, -0.12, -0.72), blend=0.05),
        k(0.10, cock, lmode='free', ease='hold'),
        k(RELEASE, cock, **release, ease='lin'),
        k(0.22, release, **point, up=(8, 22, 0)),
        k(0.50, release, **point, up=(7, 20, 0), ease='hold'),
        k(0.56, release, rmode='free', rhand=(0.36, -0.02, -0.80), rhand_rot=(-60, 0, 0), up=(4, 10, 0),
          fp=dict(rhand=(0.32, -0.30, -0.58))),
        k(CATCH, catch_at),
        k(0.9, IDLE),
    ]))
    for clip in out:
        clip.setdefault('stance', {'rfoot': IDLE['rfoot'], 'lfoot': IDLE['lfoot']})
        for key in clip['keys']:
            if 'lhand' in key or key.get('lmode') == 'free':
                key['fp'] = dict(key.get('fp', {}), lhand=(-0.6, -0.8, 0.1))
    return out


def weapon_scale(clip, t):
    """掷剑时 release 到 catch 之间骨架里的剑缩成 0（飞出去的剑由客户端画）。"""
    if clip['name'] == 'throw' and RELEASE - 1e-6 <= t < CATCH - 1e-6:
        return 0.0
    return 1.0


lx.weapon_scale = weapon_scale


def bake(all_clips, fps=60):
    player, fp = lx.skeletons()
    baked = {}
    for who, sk in (('player', player), ('fp', fp)):
        baker = Baker(sk)
        for clip in all_clips:
            baked[(who, clip['name'])] = lx.post(clip, baker.bake(clip, fps), sk)
        worst = {}
        for clip_name, t, side, over in baker.warnings:
            key = (clip_name, side)
            lo, hi, m = worst.get(key, (t, t, 0))
            worst[key] = (min(lo, t), max(hi, t), max(m, over))
        for (clip_name, side), (lo, hi, m) in sorted(worst.items()):
            print(f'{who} {clip_name} {side} 手够不着 {lo:.2f}–{hi:.2f}s，最多差 {m:.2f} 臂长')
        if who == 'player':
            by = {}
            for clip_name, t, side, r in baker.residuals:
                by.setdefault((clip_name, side), []).append(r)
            for (clip_name, side), rs in sorted(by.items()):
                if side == 'Right':
                    print(f'{who} {clip_name} 剑柄偏角 平均 {sum(rs) / len(rs):.0f}° 最大 {max(rs):.0f}°')
    return player, fp, baked


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    out = Path(args[0])
    only = set(args[1:])
    out.mkdir(parents=True, exist_ok=True)
    all_clips = clips()
    player, fp, baked = bake(all_clips)
    anim = ASSETS / 'combat/animations'
    for who, sk, view in (('player', player, 'tp'), ('fp', fp, 'fp')):
        data = bedrock(all_clips, {c['name']: baked[(who, c['name'])] for c in all_clips}, sk, lx.BONES)
        text = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
        (anim / f'jinghong_sword_{view}.animation.json').write_text(text, encoding='utf-8', newline='\n')
    tex = np.asarray(Image.open(ASSETS / 'textures/item/jinghong_sword_rig.png').convert('RGBA')).astype(np.float32)
    cams = [Camera(-35, 8, 5.4, (0, 15, -6), (300, 300)), Camera(80, 6, 5.4, (0, 15, -6), (300, 300)),
            Camera(145, 14, 5.4, (0, 15, -6), (300, 300)), Camera(0, 4, 5.4, (0, 15, -6), (300, 300))]
    names = ('左前', '右侧', '右后', '正面')

    def draw(clip, t, cam):
        frames = baked[('player', clip['name'])]
        poses = lx.poses_of(frames)
        i = min(range(len(frames)), key=lambda j: abs(frames[j][0] - t))
        W = player.world(poses[i])
        quads = mannequin_quads(W) + weapon_quads(MESH, tex, lx.mesh(player, W, 'Sheath'), 'sheath')
        if weapon_scale(clip, frames[i][0]) > 0:
            quads += weapon_quads(MESH, tex, lx.mesh(player, W), 'weapon')
        trail = []
        for j in range(max(0, i - 12), i + 1):
            if weapon_scale(clip, frames[j][0]) > 0:
                trail.append((lx.mesh(player, player.world(poses[j])) @ TIP)[:3])
        return render(quads, cam, trail=trail if len(trail) > 1 else None)

    for clip in all_clips:
        if only and clip['name'] not in only:
            continue
        times = lx.frames_for(clip, 7)
        for name, mt in (clip.get('markers') or {}).items():
            times.append(mt)
        times = sorted(set(round(t, 3) for t in times))
        rows = []
        for v, cam in enumerate(cams):
            panels = []
            for t in times:
                tag = next((n for n, mt in (clip.get('markers') or {}).items() if abs(mt - t) < 1e-3 and n != 'contact'), '')
                panels.append((f'{t:.2f}s {tag}', draw(clip, t, cam)))
            rows.append((names[v], panels))
        sheet(rows, f"惊鸿 {clip['name']}  {clip['length']}s  标记 {clip.get('markers', {})}", out / f"{clip['name']}.png")
        if '--fp' in sys.argv:
            panels = []
            for t in times:
                panels.append((f'{t:.2f}s', lx.fp_frame(fp, baked[('fp', clip['name'])], clip, t, tex)))
            rows = [(r, panels[i * 5:(i + 1) * 5]) for i, r in enumerate(('第一人称', '')) if panels[i * 5:(i + 1) * 5]]
            sheet(rows, f"惊鸿 第一人称 {clip['name']}", out / f"fp_{clip['name']}.png")
        print('rendered', clip['name'])


if __name__ == '__main__':
    main()
