# -*- coding: utf-8 -*-
"""战斗动作的离线预览：软件光栅化，画出烘焙后的骨骼、刀与刀尖轨迹。

不是游戏截图：光照只模拟原版实体的两盏方向光，alpha 裁切、不剔除背面。
"""
import json
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from combat_author import affine, euler_matrix, translate

L0 = np.array([0.2, 1.0, -0.7]); L0 /= np.linalg.norm(L0)
L1 = np.array([-0.2, 1.0, 0.7]); L1 /= np.linalg.norm(L1)

FACES = {  # 与 LongswordRig.cube 相同的角点顺序
    'north': ((1, 0, 0), (0, 0, 0), (0, 1, 0), (1, 1, 0)),
    'south': ((0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)),
    'west': ((0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)),
    'east': ((1, 0, 1), (1, 0, 0), (1, 1, 0), (1, 1, 1)),
    'up': ((0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0)),
    'down': ((0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)),
}


def cube_local(pivot, rotation):
    p = np.asarray(pivot, float)
    return translate(p) @ affine(euler_matrix(rotation), np.zeros(3)) @ translate(-p)


def box(M, start, end, pivot=(0, 0, 0), rotation=(0, 0, 0), color=None, uv=None, tex=None):
    """一个方块的六个面。uv 为各面 [u0,v0,u1,v1]（像素）；没有贴图时用纯色。"""
    T = M @ cube_local(pivot, rotation)
    lo, hi = np.asarray(start, float), np.asarray(end, float)
    quads = []
    for name, corners in FACES.items():
        pts = [(T @ np.append(lo + (hi - lo) * np.array(c), 1))[:3] for c in corners]
        normal = np.cross(pts[1] - pts[0], pts[2] - pts[0])
        if uv is not None:
            u0, v0, u1, v1 = uv[name]
            quads.append((pts[2], pts[3] - pts[2], pts[1] - pts[2], normal, (u0, v0, u1 - u0, v1 - v0), tex, None))
        else:
            quads.append((pts[2], pts[3] - pts[2], pts[1] - pts[2], normal, None, None, color))
    return quads


def weapon_quads(weapon, tex, M, part):
    out = []
    for c in weapon['cubes']:
        if c['part'] == part:
            out += box(M, c['start'], c['end'], c['pivot'], c['rotation'], uv=c['uv'], tex=tex)
    return out


SKIN, SHIRT, PANTS, HAIR = (222, 184, 160), (92, 128, 176), (60, 64, 92), (96, 72, 52)
MANNEQUIN = {  # 玩家参考骨架上的简易假人（Blockbench 空间）
    'Head': [((-4, 24, -4), (4, 32, 4), SKIN), ((-4.2, 29, -4.2), (4.2, 32.4, 4.2), HAIR)],
    'UpBody': [((-4, 12, -2), (4, 24, 2), SHIRT)],
    'RightArm': [((3.2, 17.1, -1.8), (6.8, 23.45, 1.8), SHIRT)],
    'RightForeArm': [((3.5, 11.7, -1.5), (6.5, 17.1, 1.5), SHIRT)],
    'RightHand': [((4.2, 9.4, -1.2), (6.3, 11.7, 1.2), SKIN)],
    'LeftArm': [((-6.8, 17.1, -1.8), (-3.2, 23.45, 1.8), SHIRT)],
    'LeftForeArm': [((-6.5, 11.7, -1.5), (-3.5, 17.1, 1.5), SHIRT)],
    'LeftHand': [((-6.3, 9.4, -1.2), (-4.2, 11.7, 1.2), SKIN)],
    'RightLeg': [((0, 6, -2), (4, 12, 2), PANTS)],
    'RightLowerLeg': [((0, 1, -2), (4, 6, 2), PANTS)],
    'RightFoot': [((0, 0, -2.6), (4, 1, 2), (40, 40, 48))],
    'LeftLeg': [((-4, 6, -2), (0, 12, 2), PANTS)],
    'LeftLowerLeg': [((-4, 1, -2), (0, 6, 2), PANTS)],
    'LeftFoot': [((-4, 0, -2.6), (0, 1, 2), (40, 40, 48))],
}


def mannequin_quads(W):
    out = []
    for bone, boxes in MANNEQUIN.items():
        for start, end, color in boxes:
            out += box(W[bone], start, end, color=color)
    return out


class Geo:
    """读取 Bedrock 几何（换回 Blockbench 空间），按给定骨骼局部变换算出整模型的面。"""

    def __init__(self, geo_path, tex_path, skip=()):
        g = json.load(open(geo_path, encoding='utf-8'))['minecraft:geometry'][0]
        self.tex = np.asarray(Image.open(tex_path).convert('RGBA')).astype(np.float32)
        self.bones = []
        for b in g['bones']:
            if b['name'] in skip:
                continue
            r = b.get('rotation', [0, 0, 0])
            self.bones.append(dict(name=b['name'], parent=b.get('parent'),
                                   pivot=np.array([-b['pivot'][0], b['pivot'][1], b['pivot'][2]], float),
                                   rest=np.array([-r[0], -r[1], r[2]], float), cubes=b.get('cubes', [])))
        names = {b['name'] for b in self.bones}
        for b in self.bones:
            if b['parent'] not in names:
                b['parent'] = None

    def quads(self, pose):
        W = {}
        pending = list(self.bones)
        while pending:
            rest = []
            for b in pending:
                if b['parent'] and b['parent'] not in W:
                    rest.append(b)
                    continue
                pos, R = pose.get(b['name'], (np.zeros(3), euler_matrix(b['rest'])))
                local = translate(b['pivot'] + pos) @ affine(R, np.zeros(3)) @ translate(-b['pivot'])
                W[b['name']] = W[b['parent']] @ local if b['parent'] else local
            pending = rest
        out = []
        for b in self.bones:
            M = W[b['name']]
            for c in b['cubes']:
                Mc = M
                if 'rotation' in c:
                    cp = np.array([-c['pivot'][0], c['pivot'][1], c['pivot'][2]], float)
                    cr = c['rotation']
                    Mc = M @ cube_local(cp, (-cr[0], -cr[1], cr[2]))
                size = np.array(c['size'], float)
                o = c['origin']
                x0, y0, z0 = -(o[0] + size[0]), o[1], o[2]
                x1, y1, z1 = x0 + size[0], y0 + size[1], z0 + size[2]
                dx, dy, dz = size
                defs = {
                    'north': ((x1, y1, z0), (-dx, 0, 0), (0, -dy, 0), (0, 0, -1)),
                    'south': ((x0, y1, z1), (dx, 0, 0), (0, -dy, 0), (0, 0, 1)),
                    'east': ((x1, y1, z1), (0, 0, -dz), (0, -dy, 0), (1, 0, 0)),
                    'west': ((x0, y1, z0), (0, 0, dz), (0, -dy, 0), (-1, 0, 0)),
                    'up': ((x0, y1, z0), (dx, 0, 0), (0, 0, dz), (0, 1, 0)),
                    'down': ((x0, y0, z1), (dx, 0, 0), (0, 0, -dz), (0, -1, 0)),
                }
                for f, (O, U, V, N) in defs.items():
                    uvd = c['uv'].get(f)
                    if not uvd:
                        continue
                    u, v = uvd['uv']; w, h = uvd['uv_size']
                    if f in ('up', 'down'):
                        u, v, w, h = u + w, v + h, -w, -h
                    turn = int(uvd.get('uv_rotation', 0)) % 360
                    oo, uu, vv = np.array(O, float), np.array(U, float), np.array(V, float)
                    if turn == 90:
                        oo, uu, vv = oo + uu, vv, -uu
                    elif turn == 180:
                        oo, uu, vv = oo + uu + vv, -uu, -vv
                    elif turn == 270:
                        oo, uu, vv = oo + vv, -vv, uu
                    out.append(((Mc @ np.append(oo, 1))[:3], Mc[:3, :3] @ uu, Mc[:3, :3] @ vv,
                                Mc[:3, :3] @ np.array(N, float), (u, v, w, h), self.tex, None))
        return out


class Camera:
    def __init__(self, yaw, pitch, scale, center, size):
        self.W, self.H = size
        cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
        cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
        # yaw=0 时从模型正面（-Z）看向 +Z。
        self.fwd = np.array([-sy * cp, -sp, cy * cp])
        self.right = np.array([-cy, 0, -sy])
        up = np.cross(self.right, self.fwd)
        self.up = -up if up[1] < 0 else up
        self.scale = scale
        self.center = np.asarray(center, float)

    def project(self, p):
        d = np.asarray(p, float) - self.center
        return np.array([self.W / 2 + self.scale * d.dot(self.right), self.H / 2 - self.scale * d.dot(self.up), d.dot(self.fwd)])


def render(quads, cam, bg=(244, 242, 236), trail=None, ground=True):
    W, H = cam.W, cam.H
    img = np.zeros((H, W, 3), np.float32); img[:] = bg
    zb = np.full((H, W), np.inf)
    if ground:
        # 脚下的格子，看站位与位移。
        g = Image.new('RGB', (W, H), bg); d = ImageDraw.Draw(g)
        for i in range(-3, 4):
            for a, b in (((i * 16, 0, -48), (i * 16, 0, 48)), ((-48, 0, i * 16), (48, 0, i * 16))):
                pa, pb = cam.project(a), cam.project(b)
                d.line([tuple(pa[:2]), tuple(pb[:2])], fill=(222, 218, 208), width=1)
        img[:] = np.asarray(g, np.float32)
    for O, U, V, N, uv, tex, color in quads:
        P0 = cam.project(O)
        PU = np.array([cam.scale * U.dot(cam.right), -cam.scale * U.dot(cam.up), U.dot(cam.fwd)])
        PV = np.array([cam.scale * V.dot(cam.right), -cam.scale * V.dot(cam.up), V.dot(cam.fwd)])
        A = np.array([[PU[0], PV[0]], [PU[1], PV[1]]])
        det = np.linalg.det(A)
        if abs(det) < 1e-6:
            continue
        corners = np.array([P0[:2], P0[:2] + PU[:2], P0[:2] + PV[:2], P0[:2] + PU[:2] + PV[:2]])
        xa, ya = np.floor(corners.min(0)).astype(int)
        xb, yb = np.ceil(corners.max(0)).astype(int)
        xa, ya = max(xa, 0), max(ya, 0)
        xb, yb = min(xb, W - 1), min(yb, H - 1)
        if xa > xb or ya > yb:
            continue
        xs, ys = np.meshgrid(np.arange(xa, xb + 1) + 0.5, np.arange(ya, yb + 1) + 0.5)
        Ai = np.linalg.inv(A)
        dx, dy = xs - P0[0], ys - P0[1]
        s = Ai[0, 0] * dx + Ai[0, 1] * dy
        t = Ai[1, 0] * dx + Ai[1, 1] * dy
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        if not inside.any():
            continue
        z = P0[2] + s * PU[2] + t * PV[2]
        if tex is not None:
            u, v, w, h = uv
            tu = np.clip(np.floor(u + s * w).astype(int), 0, tex.shape[1] - 1)
            tv = np.clip(np.floor(v + t * h).astype(int), 0, tex.shape[0] - 1)
            if w < 0:
                tu = np.clip(np.floor(u + s * w - 1e-6).astype(int), 0, tex.shape[1] - 1)
            if h < 0:
                tv = np.clip(np.floor(v + t * h - 1e-6).astype(int), 0, tex.shape[0] - 1)
            col = tex[tv, tu]
            ok = inside & (col[..., 3] >= 128)
            rgb = col[..., :3]
        else:
            ok = inside
            rgb = np.broadcast_to(np.array(color, np.float32), s.shape + (3,))
        ok = ok & (z < zb[ya:yb + 1, xa:xb + 1] - 1e-4)
        if not ok.any():
            continue
        n = N / (np.linalg.norm(N) or 1)
        light = min(1.0, 0.4 + 0.6 * (max(0, n.dot(L0)) + max(0, n.dot(L1))))
        region = img[ya:yb + 1, xa:xb + 1]
        region[ok] = rgb[ok] * light
        zb[ya:yb + 1, xa:xb + 1][ok] = z[ok]
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    if trail:
        d = ImageDraw.Draw(out, 'RGBA')
        pts = [cam.project(p) for p in trail]
        for i in range(1, len(pts)):
            a = int(60 + 180 * i / len(pts))
            d.line([tuple(pts[i - 1][:2]), tuple(pts[i][:2])], fill=(214, 72, 52, a), width=2)
    return out


def font(size):
    for p in (r'C:\Windows\Fonts\msyh.ttc', r'C:\Windows\Fonts\segoeui.ttf'):
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            pass
    return ImageFont.load_default()


def sheet(rows, title, out):
    """rows: [(行标题, [(格标题, 图)])]，拼成一张总览图。"""
    pw, ph = rows[0][1][0][1].size
    cols = max(len(r[1]) for r in rows)
    W = 130 + cols * (pw + 8) + 12
    S = Image.new('RGB', (W, 56 + len(rows) * (ph + 34)), (250, 249, 245))
    d = ImageDraw.Draw(S)
    d.text((14, 12), title, fill=(70, 66, 80), font=font(22))
    for r, (label, panels) in enumerate(rows):
        y = 56 + r * (ph + 34)
        d.text((14, y + ph // 2), label, fill=(90, 86, 100), font=font(18), anchor='lm')
        for i, (cap, im) in enumerate(panels):
            x = 130 + i * (pw + 8)
            S.paste(im, (x, y))
            d.text((x + pw // 2, y + ph + 6), cap, fill=(120, 116, 130), font=font(14), anchor='mt')
    S.save(out)
