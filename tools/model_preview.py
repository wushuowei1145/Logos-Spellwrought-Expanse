"""方块 / 物品模型的离线预览（软件光栅化），给乐器美术自查用。

    uv run --no-project --with pillow --with numpy python tools/model_preview.py block/starbloom_grand_piano item/hexen_violin --out <目录>

每个模型出一张图：四个角度的等轴视图（原版方块的面明暗：顶 1.0、南北 0.8、东西 0.6、底 0.5，剔除背面、alpha 裁切）、
物品栏图标（按模型的 display.gui 在 32 和 64 像素的格子里画，就是 GUI 缩放 2 和 4 时看到的大小），
并打印检查结果（元素数、边界、共面闪烁）和各个显示位置的实际大小（和原版方块比）。
这不是游戏截图：没有环境光遮蔽、没有 mipmap、光照是近似的，最后以客户端截图为准。
"""
from __future__ import annotations

import argparse
import io
import json
import math
import sys
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import instrument_kit as kit  # noqa: E402

CLIENT = Path.home() / ".gradle/caches/neoformruntime/artifacts/minecraft_26.1.2_client.jar"
_zip = None
_tex_cache = {}

FACE_NORMAL = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0), "down": (0, -1, 0), "up": (0, 1, 0)}
# 原版方块（block/block）的显示变换：拿来比大小
VANILLA_BLOCK = {
    "ground": {"rotation": [0, 0, 0], "scale": [0.25] * 3},
    "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.4] * 3},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "scale": [0.375] * 3},
    "fixed": {"rotation": [0, 0, 0], "scale": [0.5] * 3},
    "gui": {"rotation": [30, 225, 0], "scale": [0.625] * 3},
}
_CUBE = np.array([[x, y, z] for x in (-8, 8) for y in (-8, 8) for z in (-8, 8)], float)


def _split(ref):
    ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
    return ns, path


def read_asset(ns, path):
    if ns == kit.NS:
        p = kit.RES / "assets" / ns / path
        return p.read_bytes() if p.exists() else None
    global _zip
    if _zip is None:
        _zip = zipfile.ZipFile(CLIENT)
    try:
        return _zip.read(f"assets/{ns}/{path}")
    except KeyError:
        return None


def texture(ref):
    if ref in _tex_cache:
        return _tex_cache[ref]
    ns, path = _split(ref)
    data = read_asset(ns, f"textures/{path}.png")
    if data is None:
        img = np.zeros((16, 16, 4), np.uint8)
        img[..., 0] = 255
        img[..., 2] = 255
        img[..., 3] = 255
        img[::2, ::2, :3] = 0  # 缺贴图：紫黑格
    else:
        im = Image.open(io.BytesIO(data)).convert("RGBA")
        if im.height > im.width:  # 动画贴图只取第一帧
            im = im.crop((0, 0, im.width, im.width))
        img = np.array(im)
    _tex_cache[ref] = img
    return img


def load_model(ref):
    """解析 parent 链，合并贴图、元素、显示变换。ref 形如 "block/x"、"item/x"、"minecraft:block/y"。"""
    ns, path = _split(ref if ":" in ref else f"{kit.NS}:{ref}")
    data = read_asset(ns, f"models/{path}.json")
    if data is None:
        raise FileNotFoundError(ref)
    m = json.loads(data)
    textures, elements, disp = {}, None, {}
    if "parent" in m and not m["parent"].endswith("builtin/generated"):
        parent = m["parent"] if ":" in m["parent"] else "minecraft:" + m["parent"]
        p = load_model(parent)
        textures, elements, disp = dict(p["textures"]), p["elements"], dict(p["display"])
    textures.update(m.get("textures", {}))
    if "elements" in m:
        elements = m["elements"]
    disp.update(m.get("display", {}))
    if not elements and "layer0" in textures:
        # 平面物品（item/generated）：画成一张两面的薄片（原版会按像素挤出厚度，这里不做）
        elements = [{"from": [0, 0, 7.5], "to": [16, 16, 8.5], "faces": {
            "north": {"texture": "#layer0", "uv": [16, 0, 0, 16]}, "south": {"texture": "#layer0", "uv": [0, 0, 16, 16]}}}]
    return {"textures": textures, "elements": elements or [], "display": disp, "ref": ref}


def resolve(textures, key):
    seen = set()
    while key.startswith("#"):
        if key in seen:
            return "missing"
        seen.add(key)
        key = textures.get(key[1:], "missing")
    return key


# —— 几何 ——

def _rot_matrix(r):
    if "axis" in r:
        return np.array(kit.euler(**{r["axis"]: r["angle"]}))
    return np.array(kit.euler(r.get("x", 0), r.get("y", 0), r.get("z", 0)))


def quads(model):
    """模型的所有面：(四个角的模型坐标, 四个角的 uv（贴图像素比例 0–1）, 贴图数组, 法线, shade)。"""
    out = []
    for e in model["elements"]:
        f, t = np.array(e["from"], float), np.array(e["to"], float)
        size = t - f
        rot = e.get("rotation")
        m = _rot_matrix(rot) if rot else None
        o = np.array(rot["origin"], float) if rot else None
        for face, spec in e["faces"].items():
            (ua, us), (va, vs), na = kit.FACE_AXES[face]
            iu, iv, inn = kit.AXIS[ua], kit.AXIS[va], kit.AXIS[na]
            fixed = t[inn] if FACE_NORMAL[face][inn] > 0 else f[inn]
            corners, fr = [], []
            for fu, fv in ((0, 0), (1, 0), (1, 1), (0, 1)):
                p = np.zeros(3)
                p[inn] = fixed
                p[iu] = f[iu] + size[iu] * (fu if us > 0 else 1 - fu)
                p[iv] = f[iv] + size[iv] * (fv if vs > 0 else 1 - fv)
                corners.append(p)
                fr.append((fu, fv))
            uv = spec.get("uv")
            if uv is None:  # 原版自动 uv：按坐标
                lo = [f[iu], f[iv]]
                hi = [t[iu], t[iv]]
                u0 = lo[0] if us > 0 else 16 - hi[0]
                u1 = hi[0] if us > 0 else 16 - lo[0]
                v0 = lo[1] if vs > 0 else 16 - hi[1]
                v1 = hi[1] if vs > 0 else 16 - lo[1]
                uv = [u0, v0, u1, v1]
            rotn = (spec.get("rotation", 0) // 90) % 4
            uvs = []
            for fu, fv in fr:
                su, sv = fu, fv
                for _ in range(rotn):
                    su, sv = sv, 1 - su
                uvs.append(((uv[0] + (uv[2] - uv[0]) * su) / 16, (uv[1] + (uv[3] - uv[1]) * sv) / 16))
            corners = np.array(corners)
            n = np.array(FACE_NORMAL[face], float)
            if m is not None:
                corners = (corners - o) @ m.T + o
                n = m @ n
            tex = texture(resolve(model["textures"], spec["texture"]))
            out.append((corners, np.array(uvs), tex, n, e.get("shade", True)))
    return out


def block_shade(n):
    n = n / np.linalg.norm(n)
    return n[0] ** 2 * 0.6 + n[2] ** 2 * 0.8 + n[1] ** 2 * (1.0 if n[1] > 0 else 0.5)


def render(qs, basis, scale, size, center, shade=block_shade, bg=(28, 32, 40, 255)):
    """正交投影。basis = (右, 上, 朝相机)；center 是画面中心对应的模型点；scale 是每模型像素多少屏幕像素。"""
    W, H = size
    img = np.zeros((H, W, 4), np.float32)
    img[:] = bg
    zbuf = np.full((H, W), -1e9, np.float32)
    r, u, d = (np.asarray(b, float) for b in basis)
    for corners, uvs, tex, n, sh in qs:
        if n @ d <= 1e-6:
            continue  # 背面剔除
        rel = corners - center
        sx = rel @ r * scale + W / 2
        sy = -(rel @ u) * scale + H / 2
        sz = rel @ d
        k = shade(n) if sh else 1.0
        th, tw = tex.shape[:2]
        for tri in ((0, 1, 2), (0, 2, 3)):
            x, y, z = sx[list(tri)], sy[list(tri)], sz[list(tri)]
            uv = uvs[list(tri)]
            x0, x1 = int(max(0, math.floor(x.min()))), int(min(W - 1, math.ceil(x.max())))
            y0, y1 = int(max(0, math.floor(y.min()))), int(min(H - 1, math.ceil(y.max())))
            if x1 < x0 or y1 < y0:
                continue
            den = (y[1] - y[2]) * (x[0] - x[2]) + (x[2] - x[1]) * (y[0] - y[2])
            if abs(den) < 1e-9:
                continue
            gy, gx = np.mgrid[y0:y1 + 1, x0:x1 + 1] + 0.5
            w0 = ((y[1] - y[2]) * (gx - x[2]) + (x[2] - x[1]) * (gy - y[2])) / den
            w1 = ((y[2] - y[0]) * (gx - x[2]) + (x[0] - x[2]) * (gy - y[2])) / den
            w2 = 1 - w0 - w1
            inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
            if not inside.any():
                continue
            zz = w0 * z[0] + w1 * z[1] + w2 * z[2]
            sub = zbuf[y0:y1 + 1, x0:x1 + 1]
            front = inside & (zz > sub + 1e-5)
            if not front.any():
                continue
            tu = w0 * uv[0, 0] + w1 * uv[1, 0] + w2 * uv[2, 0]
            tv = w0 * uv[0, 1] + w1 * uv[1, 1] + w2 * uv[2, 1]
            px = np.clip((tu * tw).astype(int), 0, tw - 1)
            py = np.clip((tv * th).astype(int), 0, th - 1)
            col = tex[py, px].astype(np.float32)
            ok = front & (col[..., 3] > 25)
            if not ok.any():
                continue
            sub[ok] = zz[ok]
            region = img[y0:y1 + 1, x0:x1 + 1]
            region[ok, :3] = col[ok, :3] * k
            region[ok, 3] = 255
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA")


def camera(yaw, pitch):
    """相机所在方向：yaw 0 在北边（-Z）看向南，正值往东转；pitch 向下俯视的角度。"""
    a, p = math.radians(yaw), math.radians(pitch)
    d = np.array([math.sin(a) * math.cos(p), math.sin(p), -math.cos(a) * math.cos(p)])
    f = -d
    r = np.cross(f, [0, 1, 0])
    r /= np.linalg.norm(r)
    u = np.cross(r, f)
    return r, u, d


def view(model, yaw, pitch, px=360):
    qs = quads(model)
    if not qs:
        return Image.new("RGBA", (px, px), (28, 32, 40, 255))
    pts = np.concatenate([q[0] for q in qs])
    r, u, d = camera(yaw, pitch)
    c = (pts.min(axis=0) + pts.max(axis=0)) / 2
    rel = pts - c
    span = max(np.ptp(rel @ r), np.ptp(rel @ u), 1e-3)
    return render(qs, (r, u, d), px * 0.88 / span, (px, px), c)


def display_matrix(t):
    rot = np.array(kit._display_matrix(t.get("rotation", [0, 0, 0])))
    s = np.array(t.get("scale", [1, 1, 1]), float)
    tr = np.array(t.get("translation", [0, 0, 0]), float)
    return rot, s, tr


def gui_icon(model, slot_px):
    """按 display.gui 在 slot_px 大小的格子里画物品（原版：模型先平移 −8，再缩放、转动、平移，格子是 16 像素）。"""
    t = model["display"].get("gui")
    qs = quads(model)
    if t is None:
        t = {}
    rot, s, tr = display_matrix(t)
    out = []
    for corners, uvs, tex, n, sh in qs:
        p = ((corners - 8.0) * s) @ rot.T + tr
        out.append((p, uvs, tex, rot @ n, sh))
    k = slot_px / 16.0
    # 物品栏的光：从左上前方来的两盏灯（近似）
    l0 = np.array([-0.3, 0.8, 0.6]); l0 /= np.linalg.norm(l0)

    def gui_shade(n):
        n = n / np.linalg.norm(n)
        return min(1.0, 0.45 + 0.65 * max(0.0, float(n @ l0)))
    img = render(out, ((1, 0, 0), (0, 1, 0), (0, 0, 1)), k, (slot_px, slot_px), np.zeros(3), gui_shade, bg=(139, 139, 139, 255))
    return img


def sizes(model):
    """各显示位置下模型的最长边（像素，16 = 一格），和原版方块同位置的大小比。"""
    qs = quads(model)
    if not qs:
        return {}
    pts = np.concatenate([q[0] for q in qs]) - 8.0
    res = {}

    def span(p, t, key):
        rot, s, tr = display_matrix(t)
        q = (p * s) @ rot.T
        return float(max(np.ptp(q[:, 0]), np.ptp(q[:, 1]), np.ptp(q[:, 2]) if key == "ground" else 0))
    for key, vt in VANILLA_BLOCK.items():
        t = model["display"].get(key)
        if t is None:
            res[key] = ("missing", None)
            continue
        a = span(pts, t, key)
        res[key] = (round(a, 2), round(a / span(_CUBE, vt, key), 2))
    return res


def sheet_for(ref, out_dir, px=360):
    model = load_model(ref)
    views = [view(model, -35, 28, px), view(model, 35, 28, px), view(model, 155, 24, px), view(model, 0, 70, px)]
    icon32 = gui_icon(model, 32).resize((128, 128), Image.NEAREST)
    icon64 = gui_icon(model, 64).resize((128, 128), Image.NEAREST)
    W = px * 4 + 150
    img = Image.new("RGBA", (W, px + 30), (20, 23, 30, 255))
    for i, v in enumerate(views):
        img.paste(v, (i * px, 30))
    img.paste(icon32, (px * 4 + 11, 40))
    img.paste(icon64, (px * 4 + 11, 40 + 140))
    dr = ImageDraw.Draw(img)
    els = model["elements"]
    dr.text((8, 8), f"{ref}   {len(els)} elements", fill=(230, 230, 230, 255))
    dr.text((px * 4 + 11, 26), "GUI x2 / x4", fill=(200, 200, 200, 255))
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / (ref.replace(":", "_").replace("/", "__") + ".png")
    img.convert("RGB").save(path)
    return path, model


def report(ref, model, strict_budget=None):
    els = model["elements"]
    lines = [f"{ref}: {len(els)} elements"]
    if els:
        lo, hi = kit.bounds(els)
        lines.append(f"  bounds {np.round(lo, 2).tolist()} .. {np.round(hi, 2).tolist()}")
        probs = kit.lint(ref, els, strict_budget or 10 ** 6, strict=False)
        lines += [f"  ! {p}" for p in probs] or ["  no z-fights, in range"]
    for key, (span, ratio) in sizes(model).items():
        lines.append(f"  {key}: {span} px" + (f" = {ratio}x a vanilla block" if ratio is not None else " (no transform: drawn full size!)"))
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="+")
    ap.add_argument("--out", default=str(Path.home() / "instrument_previews"))
    ap.add_argument("--px", type=int, default=360)
    args = ap.parse_args()
    out = Path(args.out)
    for ref in args.models:
        path, model = sheet_for(ref, out, args.px)
        print(report(ref, model))
        print(f"  -> {path}")


if __name__ == "__main__":
    main()
