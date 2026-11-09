# -*- coding: utf-8 -*-
"""九尾妖狐 白梅的游戏资源：从仓库外的模型目录导入人形与狐形两种形态的模型、贴图和动画。

    python tools/shiraume_assets.py [<Minecraft MOD 目录>]

模型目录（维护者的工程，不在本仓库）：
- 人形 `MinecraftLogosMod/fox_model/sealed_nine_tailed_fox/source/sealed_nine_tailed_fox_<N>.bbmodel`，取编号最大的
  （维护者最新保存的那一版）；招式动作是 tools/shiraume_moves.py 烘焙好的 `Animation/shiraume_moves.animation.json`。
- 狐形 `MinecraftLogosMod/fox_model/sealed_nine_tailed_fox_beast/source/sealed_nine_tailed_fox_beast.bbmodel`；
  战斗动作是那边 tools/beast_moves.py 写的 `Animation/beast_moves.animation.json`（没有时只导入模型自带的动作）。

写出（附属资源）：
- `geckolib/models/entity/shiraume.geo.json`、`textures/entity/shiraume.png`、`geckolib/animations/entity/shiraume.animation.json`
- `geckolib/models/entity/shiraume_beast.geo.json`、`textures/entity/shiraume_beast.png`、`geckolib/animations/entity/shiraume_beast.animation.json`

两种形态都加一根空的 `Weapon` 骨骼：流火不并进模型，由渲染器按流火自己的战斗网格画在这根骨骼上（同玩家，
所以凝聚、消散、狐火和自发光都和玩家手里的流火一样）。人形的 `Weapon` 在 UpBody 下、枢轴在原点，由招式动作驱动；
狐形的叼在嘴里：Head 下，枢轴在上下颌之间，刀身横过嘴巴指向右边。

动画名：
- 人形：`idle`（模型自带的待机去掉尾巴）、`walk`（借森林精灵文件里换算好的车万女仆行走，CREDITS.md 已登记）、
  `pre_parallel0`（模型自带待机里的尾巴，常驻叠加层，走路时尾巴也摆）、`seal_strain`（模型自带），
  招式加 `ryuka_` 前缀（例如 `ryuka_kesa`），由服务端的招式状态机经 combat 控制器播放。
- 狐形：`idle`、`walk`（模型自带），`pre_parallel0`（尾巴），战斗动作加 `fox_` 前缀（模型自带的 bite 也算进来）。
"""
import json
import re
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
GECKO = ASSETS / 'geckolib'
ELF_ANIMATION = GECKO / 'animations/entity/forest_elf.animation.json'
HUMAN = Path('MinecraftLogosMod/fox_model/sealed_nine_tailed_fox')
BEAST = Path('MinecraftLogosMod/fox_model/sealed_nine_tailed_fox_beast')
#: 狐形叼刀的位置（Blockbench 坐标，模型像素）：上下颌之间、犬齿后面。
BEAST_MOUTH = (0.0, 85.0, -84.0)
#: 刀横在嘴里：刀尖（网格 +Y）指向狐狸右边（+X），刃（网格 +X）朝前下方 30°。矩阵的三列是网格 X、Y、Z 转过去的方向，
#: 换成 Blockbench 欧拉角（Z·Y·X 顺序）是 (0, 60, -90)。刃不能正对前方：那样正好落在万向节锁上，欧拉角算不出来。
BEAST_WEAPON_MATRIX = np.array([[0.0, 1.0, 0.0], [-0.5, 0.0, -0.8660254], [-0.8660254, 0.0, 0.5]])
HUMAN_PREFIX, BEAST_PREFIX = 'ryuka_', 'fox_'


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def write(path, content, compact=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(content, ensure_ascii=False, separators=(',', ':')) if compact else json.dumps(content, ensure_ascii=False, indent=1)
    path.write_text(text + '\n', encoding='utf-8', newline='\n')


def latest(folder, stem):
    """维护者最新保存的一版：stem_<N>.bbmodel 里 N 最大的。"""
    best, path = -1, None
    for p in folder.glob(stem + '_*.bbmodel'):
        m = re.fullmatch(re.escape(stem) + r'_(\d+)\.bbmodel', p.name)
        if m and int(m.group(1)) > best:
            best, path = int(m.group(1)), p
    if path is None:
        raise SystemExit(f'{folder} 里没有 {stem}_<N>.bbmodel')
    return path


def bb_tools(art):
    sys.path.insert(0, str(art / HUMAN / 'tools'))
    import bbtool  # noqa: E402
    return bbtool


def matrix_euler(R):
    """R = Rz·Ry·Rx 的欧拉角（度）。"""
    sb = -max(-1.0, min(1.0, R[2, 0]))
    b = np.arcsin(sb)
    cb = np.cos(b)
    a = np.arctan2(R[2, 1] / cb, R[2, 2] / cb)
    c = np.arctan2(R[1, 0] / cb, R[0, 0] / cb)
    return [float(np.degrees(v)) for v in (a, b, c)]


def add_bone(geo, bone):
    bones = geo['minecraft:geometry'][0]['bones']
    names = {b['name'] for b in bones}
    assert bone['parent'] in names, bone
    bones[:] = [b for b in bones if b['name'] != bone['name']] + [bone]


def bone_names(geo):
    return {b['name'] for b in geo['minecraft:geometry'][0]['bones']}


def only(anim, bones):
    out = {k: v for k, v in anim.items() if k != 'bones'}
    out['bones'] = {n: c for n, c in anim.get('bones', {}).items() if n in bones}
    return out


def split_tails(anim):
    """模型自带的待机拆成两层：尾巴（常驻叠加层）与其余。"""
    tails = {n: c for n, c in anim['bones'].items() if n == 'Tail' or n.startswith('tail_')}
    rest = {n: c for n, c in anim['bones'].items() if n not in tails}
    body = {k: v for k, v in anim.items() if k != 'bones'}
    return {**body, 'bones': rest}, {**body, 'loop': True, 'bones': tails}


def model(bb, path, identifier):
    b = bb.BB(str(path))
    geo = b.export_geo(identifier)
    return b, geo


def human(art, bb):
    source = latest(art / HUMAN / 'source', 'sealed_nine_tailed_fox')
    b, geo = model(bb, source, 'geometry.shiraume')
    add_bone(geo, {'name': 'Weapon', 'parent': 'UpBody', 'pivot': [0, 0, 0]})
    names = bone_names(geo)
    own = b.export_animations()['animations']
    out = {}
    idle, tails = split_tails(own['idle'])
    out['idle'] = idle
    out['pre_parallel0'] = tails
    if 'seal_strain' in own:
        out['seal_strain'] = own['seal_strain']
    elf = read(ELF_ANIMATION)['animations']
    out['walk'] = only(elf['walk'], names)
    moves = read(art / HUMAN / 'Animation/shiraume_moves.animation.json')['animations']
    for name, anim in moves.items():
        out[HUMAN_PREFIX + name] = only(anim, names)
    write(GECKO / 'models/entity/shiraume.geo.json', geo)
    from PIL import Image
    Image.fromarray(b.texture()).save(ASSETS / 'textures/entity/shiraume.png', optimize=True)
    write(GECKO / 'animations/entity/shiraume.animation.json', {'format_version': '1.8.0', 'animations': out}, compact=True)
    return source.name, len(b.data['elements']), sorted(out)


def beast(art, bb):
    source = art / BEAST / 'source/sealed_nine_tailed_fox_beast.bbmodel'
    b, geo = model(bb, source, 'geometry.shiraume_beast')
    ex, ey, ez = matrix_euler(BEAST_WEAPON_MATRIX)
    x, y, z = BEAST_MOUTH
    # Bedrock 坐标：x 取反、绕 x/y 的转角取反（同 bbtool.export_geo）
    add_bone(geo, {'name': 'Weapon', 'parent': 'Head', 'pivot': [-x, y, z], 'rotation': [round(-ex, 4), round(-ey, 4), round(ez, 4)]})
    names = bone_names(geo)
    own = b.export_animations()['animations']
    out = {}
    idle, tails = split_tails(own['idle'])
    out['idle'] = idle
    out['pre_parallel0'] = tails
    out['walk'] = own['walk']
    out[BEAST_PREFIX + 'bite'] = own['bite']
    moves_path = art / BEAST / 'Animation/beast_moves.animation.json'
    if moves_path.exists():
        for name, anim in read(moves_path)['animations'].items():
            if name in ('idle', 'walk', 'run', 'death'):
                out[name] = only(anim, names)
            else:
                out[BEAST_PREFIX + name] = only(anim, names)
    write(GECKO / 'models/entity/shiraume_beast.geo.json', geo)
    from PIL import Image
    Image.fromarray(b.texture()).save(ASSETS / 'textures/entity/shiraume_beast.png', optimize=True)
    write(GECKO / 'animations/entity/shiraume_beast.animation.json', {'format_version': '1.8.0', 'animations': out}, compact=True)
    return source.name, len(b.data['elements']), sorted(out)


def main():
    art = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT.parent.parent
    bb = bb_tools(art)
    for label, fn in (('人形', human), ('狐形', beast)):
        name, cubes, anims = fn(art, bb)
        print(f'{label}: {name}，{cubes} 块，{len(anims)} 段动画')


if __name__ == '__main__':
    main()
