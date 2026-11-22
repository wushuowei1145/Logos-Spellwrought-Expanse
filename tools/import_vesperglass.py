# -*- coding: utf-8 -*-
"""凝辉（Vesperglass）的导入（docs/KOHAKU_COMPANION.md 第 6 节）：琥珀被贴脸时凝出来横扫一圈的魔力大剑。

读仓库外的美术稿 art/vesperglass/vesperglass_greatsword.bbmodel，写战斗网格
src/main/resources/assets/logos_dimension/combat/weapons/vesperglass_greatsword.json 和贴图（textures/item/ 下两张，原样拷贝）。
网格 = 美术稿 - (8, 1, 8)：原点在主握点（同别的战斗武器）。凝辉不是物品，没有物品模型和图标。

美术稿的每一段轮廓（剑身、花瓣护手、萼托、剑柄、露滴剑首）都是一层层很薄的切片叠出来的，一共 210 块，
剑身一片只高 0.35–0.4，比游戏里看得出的一个像素细得多。导入时把相邻、能接成一块的切片合并：
- 竖着叠的（剑身、萼托、剑柄、剑首）：上下相接、在同一条中线上、宽和厚差不到 TOL_SIZE 的并成一块，宽取按高度加权的平均；
- 花瓣：角度相同、首尾相接、在一条线上、高差不到 PETAL_SIZE 的一串并成一块斜放的长条（花瓣是弯的、一路收窄，容差严一点）。
每个面的贴图坐标换成合并后的范围：沿合并方向取并集，另一个方向按宽度加权平均（剑身正面就是从那一长条画好的剑里取同样宽的一段）。
贴图不动。

uv run --with numpy --with pillow python tools/import_vesperglass.py [--art <美术目录>] [--preview]
--preview 另外画一张合并前后的对比图（tools/combat_render.py 的离线光栅化，不是游戏截图），写到美术目录的 slim_compare.png。
"""
import argparse
import json
import math
import shutil
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
NAME = 'vesperglass_greatsword'
OFFSET = np.array([8.0, 1.0, 8.0])
# 合并的容差（美术稿单位，1 = 物品模型的 1 像素）：尺寸差、偏离同一条线、首尾的缝。
# 花瓣是弯的、一路收窄，放宽了会变成几段折线、接缝处有缺口；露滴剑首是圆的，放宽了台阶明显：这两处用严一点的（PETAL_*）。
TOL_SIZE, TOL_LINE, TOL_GAP = 0.12, 0.06, 0.05
PETAL_SIZE, PETAL_LINE = 0.05, 0.03
FACE_AXES = {  # 每个面的 (u, v) 对应的本地轴：0 = x，1 = y，2 = z
    'north': (0, 1), 'south': (0, 1), 'east': (2, 1), 'west': (2, 1), 'up': (0, 2), 'down': (0, 2),
}


def find_art():
    for p in [ROOT, *ROOT.parents]:
        if (p / 'art' / 'vesperglass').is_dir():
            return p / 'art' / 'vesperglass'
    sys.exit('找不到 art/vesperglass（用 --art 指定）')


class Piece:
    """一块（只绕 z 转，同美术稿）：中心、半尺寸、角度、各面贴图坐标。"""

    def __init__(self, name, center, half, theta, uv):
        self.name, self.center, self.half, self.theta, self.uv = name, np.asarray(center, float), np.asarray(half, float), theta, uv

    @staticmethod
    def of(e):
        rot = e.get('rotation') or [0, 0, 0]
        assert abs(rot[0]) < 1e-6 and abs(rot[1]) < 1e-6, e['name'] + ' 不只绕 z 转'
        lo, hi = np.array(e['from'], float), np.array(e['to'], float)
        c = (lo + hi) / 2
        o = np.array(e.get('origin', c), float)
        th = math.radians(rot[2])
        R = rot_z(th)
        uv = {f: list(d['uv']) for f, d in e['faces'].items() if d and d.get('uv') is not None and d.get('texture') is not None}
        return Piece(e['name'], o + R @ (c - o), (hi - lo) / 2, th, uv)

    def axis(self, k):
        return rot_z(self.theta)[:, k]


def rot_z(th):
    c, s = math.cos(th), math.sin(th)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1.0]])


def joins(run, p, k):
    """{@code p} 能不能沿本地轴 {@code k} 接在这一串后面。"""
    a, o = run[-1], 1 - k
    petal = 'petal' in a.name or a.name.startswith('dew')
    tol_size, tol_line = (PETAL_SIZE, PETAL_LINE) if petal else (TOL_SIZE, TOL_LINE)
    if abs(a.theta - p.theta) > 1e-6 or abs(a.center[2] - p.center[2]) > 1e-3 or abs(a.half[2] - p.half[2]) > 1e-3:
        return False
    ax, ao = a.axis(k), a.axis(o)
    along = (p.center - a.center) @ ax
    if abs(abs(along) - (a.half[k] + p.half[k])) > TOL_GAP:
        return False
    if abs((p.center - run[0].center) @ ao) > tol_line:
        return False
    if len(run) > 1 and np.sign(along) != np.sign((run[-1].center - run[-2].center) @ ax):
        return False
    sizes = [q.half[o] * 2 for q in run + [p]]
    return max(sizes) - min(sizes) <= tol_size


def span(rects, i0, i1, mirrored):
    """几块的贴图坐标在一个方向上的并集，保留原来的朝向（u0 > u1 是镜像的）。"""
    lo = min(min(r[i0], r[i1]) for r in rects)
    hi = max(max(r[i0], r[i1]) for r in rects)
    return (hi, lo) if mirrored else (lo, hi)


def average(rects, weights, i0, i1):
    """几块的贴图坐标在一个方向上按宽度加权的平均：中心和带符号的半宽各自平均。"""
    w = np.asarray(weights, float) / sum(weights)
    c = sum(wi * (r[i0] + r[i1]) / 2 for wi, r in zip(w, rects))
    h = sum(wi * (r[i1] - r[i0]) / 2 for wi, r in zip(w, rects))
    return c - h, c + h


def merge(run, k):
    if len(run) == 1:
        return run[0]
    o = 1 - k
    ax, ao = run[0].axis(k), run[0].axis(o)
    run = sorted(run, key=lambda p: p.center @ ax)
    lens = [p.half[k] * 2 for p in run]
    start = run[0].center @ ax - run[0].half[k]
    end = run[-1].center @ ax + run[-1].half[k]
    perp = sum(l * (p.center @ ao) for l, p in zip(lens, run)) / sum(lens)
    size_o = sum(l * p.half[o] * 2 for l, p in zip(lens, run)) / sum(lens)
    z = run[0].center[2]
    center = ax * (start + end) / 2 + ao * perp
    center[2] = z
    half = np.zeros(3)
    half[k], half[o], half[2] = (end - start) / 2, size_o / 2, run[0].half[2]
    uv = {}
    for face, (ua, va) in FACE_AXES.items():
        rects = [p.uv[face] for p in run if face in p.uv]
        if not rects:
            continue
        weights = [lens[i] for i, p in enumerate(run) if face in p.uv]
        if face in ('up', 'down') and k == 1:
            uv[face] = list((run[-1] if face == 'up' else run[0]).uv.get(face, rects[0]))
            continue
        if face in ('east', 'west') and k == 0:
            uv[face] = list((run[-1] if face == 'east' else run[0]).uv.get(face, rects[0]))
            continue
        r = [0.0] * 4
        for axis_of_uv, (i0, i1) in ((ua, (0, 2)), (va, (1, 3))):
            mirrored = rects[0][i0] > rects[0][i1]
            if axis_of_uv == k:
                r[i0], r[i1] = span(rects, i0, i1, mirrored)
            elif axis_of_uv == o:
                r[i0], r[i1] = average(rects, weights, i0, i1)
            else:  # 厚度方向（z）：大家一样
                r[i0], r[i1] = rects[0][i0], rects[0][i1]
        uv[face] = r
    name = run[0].name + '..' + run[-1].name.rsplit('_', 1)[-1]
    return Piece(name, center, half, run[0].theta, uv)


def slim(pieces):
    out, run, axis = [], [pieces[0]], None
    for p in pieces[1:]:
        k = axis if axis is not None else (1 if joins(run, p, 1) else 0 if joins(run, p, 0) else None)
        if k is not None and joins(run, p, k):
            run.append(p)
            axis = k
            continue
        out.append(merge(run, axis if axis is not None else 1))
        run, axis = [p], None
    out.append(merge(run, axis if axis is not None else 1))
    return out


def groups(b):
    els = {e['uuid']: e for e in b['elements']}
    out = []

    def walk(nodes):
        for n in nodes:
            if isinstance(n, dict):
                kids = [els[c] for c in n.get('children', []) if isinstance(c, str) and c in els]
                if kids:
                    out.append((n['name'], kids))
                walk(n.get('children', []))
    walk(b['outliner'])
    return out


def cube_json(p):
    lo, hi = p.center - p.half - OFFSET, p.center + p.half - OFFSET
    r = lambda v: [round(float(x), 5) for x in v]
    return {'name': p.name, 'part': 'weapon', 'start': r(lo), 'end': r(hi), 'pivot': r(p.center - OFFSET),
            'rotation': [0, 0, round(math.degrees(p.theta), 4)], 'uv': {f: [round(float(x), 5) for x in v] for f, v in p.uv.items()}}


def preview(art, before, after, tex):
    sys.path.insert(0, str(ROOT / 'tools'))
    from PIL import Image
    import combat_render as CR
    t = np.asarray(Image.open(tex).convert('RGBA')).astype(np.float32)
    rows = []
    for label, mesh in (('合并前 %d 块' % len(before['cubes']), before), ('合并后 %d 块' % len(after['cubes']), after)):
        panels = []
        for cap, cam in (('正面', CR.Camera(0, 0, 13, (0, 12, 0), (260, 520))), ('侧面', CR.Camera(90, 0, 13, (0, 12, 0), (260, 520))),
                         ('斜看', CR.Camera(-35, 18, 13, (0, 12, 0), (260, 520))), ('护手近看', CR.Camera(-20, 10, 60, (0, 4, 0), (260, 520)))):
            img = CR.render(CR.weapon_quads(mesh, t, np.eye(4), 'weapon'), cam, bg=(38, 40, 58), ground=False)
            panels.append((cap, img))
        rows.append((label, panels))
    CR.sheet(rows, '凝辉：切片合并前后（离线光栅化，不是游戏截图）', art / 'slim_compare.png')
    print('对比图', art / 'slim_compare.png')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--art', type=Path)
    ap.add_argument('--preview', action='store_true')
    args = ap.parse_args()
    art = args.art or find_art()
    b = json.loads((art / f'{NAME}.bbmodel').read_bytes().decode('utf-8'))
    size = [b['resolution']['width'], b['resolution']['height']]
    before = {'cubes': [], 'texture_size': size}
    pieces, report = [], []
    for gname, els in groups(b):
        ps = [Piece.of(e) for e in els]
        before['cubes'] += [cube_json(p) for p in ps]
        slimmed = slim(ps)
        pieces += slimmed
        report.append((gname, len(ps), len(slimmed)))
    mesh = {
        '_comment': '凝辉（琥珀被贴脸时凝出来的魔力大剑，docs/KOHAKU_COMPANION.md 第 6 节）。tools/import_vesperglass.py 从仓库外的美术稿生成，不要手改。',
        'texture': f'logos_dimension:textures/item/{NAME}.png', 'glow': f'logos_dimension:textures/item/{NAME}_glow.png',
        'texture_size': size, 'cubes': [cube_json(p) for p in pieces]}
    out = ASSETS / 'combat/weapons' / f'{NAME}.json'
    out.write_bytes((json.dumps(mesh, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))
    for suffix in ('', '_glow'):
        src = art / 'resources/assets/logos_dimension/textures/item' / f'{NAME}{suffix}.png'
        shutil.copyfile(src, ASSETS / 'textures/item' / f'{NAME}{suffix}.png')
    for g, n0, n1 in report:
        print(f'{g}: {n0} -> {n1}')
    print('一共', sum(r[1] for r in report), '->', len(pieces), '块；写了', out)
    if args.preview:
        preview(art, before, mesh, ASSETS / 'textures/item' / f'{NAME}.png')


if __name__ == '__main__':
    main()
