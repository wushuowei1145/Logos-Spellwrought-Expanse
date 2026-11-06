"""把一张或全部牌画出来，输出审稿预览：4 倍牌画、夜间发光模拟、装进牌框的整卡、参考图对照。

用法：python tools/tarot_art/render.py 00_fool [01_magician ...]    只画指定牌
      python tools/tarot_art/render.py all                            全部
      python tools/tarot_art/render.py --sheet                        再拼一张总览
预览写到 art/mystical_cat_tarot/v09_pixel_major/preview，找不到 art 目录时写到 build/tarot_preview，可用 TAROT_PREVIEW 环境变量改。
"""
from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from pixelkit import H, W  # noqa: E402
from registry import ART_ROOT, CARDS, reference_image  # noqa: E402

REPO = HERE.parent.parent
PREVIEW = Path(os.environ.get('TAROT_PREVIEW', (ART_ROOT / 'v09_pixel_major/preview') if ART_ROOT else REPO / 'build/tarot_preview'))
TEXTURES = REPO / 'src/main/resources/assets/logos_dimension/textures/item/tarot'


def load_card(card_id):
    """每张牌一个模块 cards/c<id>.py，提供 draw() -> Card。"""
    path = HERE / 'cards' / f'c{card_id}.py'
    spec = importlib.util.spec_from_file_location(f'c{card_id}', path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.draw()


def night_view(card):
    """夜间模拟：牌面压到 22% 亮度，发光层保持原色。只是预览，不是游戏截图。"""
    base = (card.rgb.astype(float) * 0.22).astype(np.uint8)
    mask = card.gl[..., 3] == 255
    base[mask] = card.gl[..., :3][mask]
    return Image.fromarray(base, 'RGB')


def framed(card_id, art, glow=None):
    """装进牌框的整卡（128×224），有牌号牌名层时一起叠上。"""
    frame = Image.open(TEXTURES / 'frame.png').convert('RGBA')
    out = Image.new('RGBA', frame.size, (0, 0, 0, 0))
    out.paste(art.convert('RGBA'), (8, 16))
    out.alpha_composite(frame)
    labels = TEXTURES / 'major' / f'{card_id}_labels.png'
    if labels.exists():
        out.alpha_composite(Image.open(labels).convert('RGBA'))
    return out


def preview_card(card_id, card):
    PREVIEW.mkdir(parents=True, exist_ok=True)
    art = card.art_image()
    k = 4
    big = art.resize((W * k, H * k), Image.NEAREST)
    night = night_view(card).resize((W * k, H * k), Image.NEAREST)
    gap = 16
    sheet = Image.new('RGB', (W * k * 3 + gap * 4, H * k + gap * 2), (24, 32, 44))
    sheet.paste(big, (gap, gap))
    sheet.paste(night, (W * k + gap * 2, gap))
    ref = reference_image(card_id)
    if ref is not None:
        sheet.paste(ref.resize((W * k, H * k), Image.LANCZOS), (W * k * 2 + gap * 3, gap))
    sheet.save(PREVIEW / f'{card_id}_review.png')
    # 原尺寸、2 倍和整卡：看低分辨率下的可读性
    try:
        full = framed(card_id, art)
        strip = Image.new('RGB', (128 * 3 + 112 + 64 + gap * 4, 224 * 3 + gap * 2), (24, 32, 44))
        strip.paste(full.convert('RGB').resize((128 * 3, 224 * 3), Image.NEAREST), (gap, gap))
        strip.paste(art.convert('RGB'), (128 * 3 + gap * 2, gap))
        small = full.convert('RGB').resize((64, 112), Image.BOX)
        strip.paste(small, (128 * 3 + 112 + gap * 3, gap))
        strip.save(PREVIEW / f'{card_id}_framed.png')
    except FileNotFoundError:
        pass


def write_textures(card_id, card):
    out = TEXTURES / 'major'
    out.mkdir(parents=True, exist_ok=True)
    card.art_image().save(out / f'{card_id}.png', optimize=True)
    card.glow_image().save(out / f'{card_id}_glow.png', optimize=True)


def lint(card_id, card):
    """硬性检查：发光层只有全透明或全不透明；发光量和用色数在范围内。"""
    problems = []
    alpha = card.glow_image().getchannel('A')
    values = set(np.unique(np.array(alpha)).tolist())
    if not values <= {0, 255}:
        problems.append(f'发光层有半透明像素: {sorted(values)}')
    st = card.stats()
    if st['glow_pixels'] == 0:
        problems.append('没有发光像素')
    if st['glow_ratio'] > 0.12:
        problems.append(f"发光比例 {st['glow_ratio']:.1%} 过高（上限 12%）")
    if st['colors'] > 72:
        problems.append(f"用色 {st['colors']} 种，超过上限 72")
    return st, problems


def contact_sheet():
    cols = 6
    k = 2
    gap = 10
    rows = (len(CARDS) + cols - 1) // cols
    sheet = Image.new('RGB', (cols * (W * k + gap) + gap, rows * (H * k + gap) + gap), (24, 32, 44))
    for i, cid in enumerate(CARDS):
        p = TEXTURES / 'major' / f'{cid}.png'
        if not p.exists():
            continue
        im = Image.open(p).convert('RGB').resize((W * k, H * k), Image.NEAREST)
        sheet.paste(im, (gap + (i % cols) * (W * k + gap), gap + (i // cols) * (H * k + gap)))
    PREVIEW.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW / 'all_major.png')


def main(argv):
    ids = [a for a in argv if not a.startswith('--')]
    if ids == ['all']:
        ids = [c for c in CARDS if (HERE / 'cards' / f'c{c}.py').exists()]
    failed = False
    for cid in ids:
        card = load_card(cid)
        write_textures(cid, card)
        preview_card(cid, card)
        st, problems = lint(cid, card)
        status = 'OK' if not problems else 'LINT: ' + '; '.join(problems)
        print(f"{cid}: {st['colors']} 色, 发光 {st['glow_pixels']} 像素 ({st['glow_ratio']:.1%}) {status}")
        failed |= bool(problems)
    if '--sheet' in argv:
        contact_sheet()
    print(f'预览目录: {PREVIEW}')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
