# -*- coding: utf-8 -*-
"""离线预览 GeckoLib 模型摆出某一刻的样子（docs/CHAIN_CUTIN.md「角色的姿势」）：不进游戏，一次看几十个候选。

按 GeckoLib 的规则摆骨骼（几何和动画里旋转的 x、y 和位移的 x 取反，绕枢轴按 Z·Y·X 转），每个方块平涂；
Weapon 骨骼和手部挂点画成一条线（刀剑、手里的东西大致朝哪），眼睛画成白点（看得到就是正面）。没有贴图，只看姿势。

用法：python tools/geo_pose_preview.py <模型名> <输出.png> <转角（度，180 是正面）> [标签=]动作@秒[+动作@秒[/只取这些骨骼的正则]]...
例如：python tools/geo_pose_preview.py lacerta_companion out.png 180 "拄剑=crimson_sheathe@0.8+act_wingspan@1.8/^Wing"
需要 numpy 和 Pillow。
"""
import json, math, re, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent / 'src/main/resources/assets/logos_dimension/geckolib'
sys.path.insert(0, str(Path(__file__).resolve().parent))
from chain_cutin_poses import value  # noqa

FACE = re.compile(r'(Eye|Lid|Mouth|Iris|Pupil|Lash|Blush|Mark|Brow|Tear|Sweat|Cheek|Ahoge|_ear$|^Ear$|Glow|Sigil|Coin|Flask)')


def rx(a):
    c, s = math.cos(a), math.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def ry(a):
    c, s = math.cos(a), math.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def rz(a):
    c, s = math.cos(a), math.sin(a); return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def M4(R=None, t=None):
    m = np.eye(4)
    if R is not None: m[:3, :3] = R
    if t is not None: m[:3, 3] = t
    return m


def rot(e):  # e = (x, y, z) radians, GeckoLib applies Z then Y then X (pose.mulPose Z, Y, X)
    return rz(e[2]) @ ry(e[1]) @ rx(e[0])


def load(asset):
    geo = json.load(open(ROOT / f'models/entity/{asset}.geo.json', encoding='utf-8'))['minecraft:geometry'][0]
    anims = json.load(open(ROOT / f'animations/entity/{asset}.animation.json', encoding='utf-8'))['animations']
    return geo, anims


def pose_from(anims, spec):
    """spec: list of (clip, t, only-regex) layered. Returns {bone: {'rotation':[..], 'position':[..], 'scale':[..]}} in file units."""
    bones = {}
    for clip, t, only in spec:
        for b, chs in anims[clip].get('bones', {}).items():
            if FACE.search(b) or (only and not re.search(only, b)):
                continue
            bones[b] = {ch: value(tr, t) for ch, tr in chs.items() if ch in ('rotation', 'position', 'scale')}
    return bones


def world_matrices(geo, anim):
    bones = {b['name']: b for b in geo['bones']}
    cache = {}

    def mat(name):
        if name in cache: return cache[name]
        b = bones[name]
        parent = mat(b['parent']) if b.get('parent') in bones else np.eye(4)
        p = np.array(b.get('pivot', [0, 0, 0]), float); p[0] = -p[0]
        r = np.array(b.get('rotation', [0, 0, 0]), float)
        a = anim.get(name, {})
        ar = np.array(a.get('rotation', [0, 0, 0]), float)
        ap = np.array(a.get('position', [0, 0, 0]), float)
        sc = np.array(a.get('scale', [1, 1, 1]), float)
        e = np.radians([-(r[0] + ar[0]), -(r[1] + ar[1]), r[2] + ar[2]])
        m = parent @ M4(t=[-ap[0], ap[1], ap[2]]) @ M4(t=p) @ M4(rot(e)) @ np.diag([*sc, 1]) @ M4(t=-p)
        cache[name] = m
        return m

    for n in bones: mat(n)
    return bones, cache


def quads(geo, anim):
    bones, mats = world_matrices(geo, anim)
    out = []
    hidden = set()
    for name, b in bones.items():
        q = name
        while q in bones:
            if re.search(r'SummonBud|Bud|Cocoon|Katashiro', q): hidden.add(name); break
            q = bones[q].get('parent')
    for name, b in bones.items():
        if name in hidden: continue
        m = mats[name]
        for c in b.get('cubes', []):
            o = np.array(c['origin'], float); s = np.array(c['size'], float); inf = c.get('inflate', 0)
            x0 = -(o[0] + s[0]) - inf; y0 = o[1] - inf; z0 = o[2] - inf
            x1 = x0 + s[0] + 2 * inf; y1 = y0 + s[1] + 2 * inf; z1 = z0 + s[2] + 2 * inf
            cm = np.eye(4)
            if 'rotation' in c:
                cp = np.array(c.get('pivot', [0, 0, 0]), float); cp[0] = -cp[0]
                cr = np.radians([-c['rotation'][0], -c['rotation'][1], c['rotation'][2]])
                cm = M4(t=cp) @ M4(rot(cr)) @ M4(t=-cp)
            T = m @ cm
            if np.allclose(T[:3, :3], 0): continue
            V = [np.array([x, y, z, 1.0]) for x in (x0, x1) for y in (y0, y1) for z in (z0, z1)]
            V = [(T @ v)[:3] for v in V]
            faces = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
            for f in faces:
                out.append((name, [V[i] for i in f]))
    return out, mats


def color(name):
    if re.search(r'Head|Hair|Braid|Ahoge|Ear', name): return (230, 200, 190)
    if re.search(r'Arm|Hand', name): return (120, 160, 230)
    if re.search(r'Leg|Foot', name): return (150, 120, 200)
    if re.search(r'Wing', name): return (220, 90, 90)
    if re.search(r'Tail|Plume', name): return (240, 220, 120)
    return (170, 200, 170)


def render(geo, anims, spec, yaw_deg, size=360, extent=62, weapon=None, label=''):
    anim = pose_from(anims, spec)
    qs, mats = quads(geo, anim)
    # front direction: eye bones' z relative to head pivot
    img = Image.new('RGB', (size, size), (24, 24, 34))
    d = ImageDraw.Draw(img)
    view = ry(math.radians(yaw_deg))
    sc = size / extent
    cy = extent * .45

    def proj(p):
        q = view @ p
        return (size / 2 + q[0] * sc, size - (q[1] + 4) * sc), q[2]

    polys = []
    light = np.array([.3, .8, .5]); light /= np.linalg.norm(light)
    for name, V in qs:
        P = [proj(v) for v in V]
        z = sum(p[1] for p in P) / 4
        n = np.cross(V[1] - V[0], V[2] - V[0]); nn = np.linalg.norm(n)
        shade = .55 + .45 * abs(float(np.dot(view @ n / nn, light))) if nn > 1e-9 else .7
        c = tuple(int(x * shade) for x in color(name))
        polys.append((z, [p[0] for p in P], c))
    polys.sort(key=lambda t: t[0])  # painter: far first (z small = far? camera looks along +z?)
    for z, pts, c in polys:
        d.polygon(pts, fill=c)
    # weapon bone: line along its +Y
    bones_by = {b['name']: b for b in geo['bones']}
    for bone, length in (weapon or {}).items():
        if bone in mats:
            m = mats[bone]
            pv = np.array(bones_by[bone].get('pivot', [0, 0, 0]), float); pv[0] = -pv[0]
            a = m @ np.array([*pv, 1.0]); bpt = m @ np.array([pv[0], pv[1] + length, pv[2], 1.0])
            (pa, _), (pb, _) = proj(a[:3]), proj(bpt[:3])
            d.line([pa, pb], fill=(255, 80, 40), width=4)
            d.ellipse([pa[0] - 4, pa[1] - 4, pa[0] + 4, pa[1] + 4], fill=(255, 255, 0))
    for b in geo['bones']:
        if re.search(r'EyeLook$', b['name']) and b['name'] in mats:
            pv = np.array(b.get('pivot', [0, 0, 0]), float); pv[0] = -pv[0]
            (pe, ze) = proj((mats[b['name']] @ np.array([*pv, 1.0]))[:3])
            d.ellipse([pe[0] - 3, pe[1] - 3, pe[0] + 3, pe[1] + 3], fill=(255, 255, 255))
    d.text((6, 6), label, fill=(255, 255, 255))
    return img


def parse(arg):
    label = arg
    spec = []
    if '=' in arg:
        label, arg = arg.split('=', 1)
    for part in arg.split('+'):
        only = None
        if '/' in part:
            part, only = part.split('/', 1)
        clip, t = part.rsplit('@', 1)
        spec.append((clip, float(t), only))
    return label, spec


def main():
    asset, out = sys.argv[1], sys.argv[2]
    yaw = float(sys.argv[3])
    cells = sys.argv[4:]
    geo, anims = load(asset)
    weapon = {'Weapon': 22, 'RightHandLocator': 14, 'LeftHandLocator': 8}
    imgs = []
    for c in cells:
        label, spec = parse(c)
        imgs.append(render(geo, anims, spec, yaw, weapon=weapon, label=label))
    cols = min(5, len(imgs)); rows = (len(imgs) + cols - 1) // cols
    W = imgs[0].width
    sheet = Image.new('RGB', (cols * W, rows * W), (0, 0, 0))
    for i, im in enumerate(imgs):
        sheet.paste(im, ((i % cols) * W, (i // cols) * W))
    sheet.save(out)


if __name__ == '__main__':
    main()
