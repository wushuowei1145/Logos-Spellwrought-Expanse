"""二十二张大阿尔卡那的编号、资源 ID 与牌名。Java 注册、语言文件和贴图文件名都以这里为准。"""
from __future__ import annotations

import glob
import os
from pathlib import Path

from PIL import Image

# id -> (罗马数字, 英文牌名, 中文牌名, 终稿文件夹)
CARDS = {
    '00_fool': ('0', 'THE FOOL', '愚者', '00_愚者'),
    '01_magician': ('I', 'THE MAGICIAN', '魔术师', '01_魔术师'),
    '02_high_priestess': ('II', 'THE HIGH PRIESTESS', '女祭司', '02_女祭司'),
    '03_empress': ('III', 'THE EMPRESS', '皇后', '03_皇后'),
    '04_emperor': ('IV', 'THE EMPEROR', '皇帝', '04_皇帝'),
    '05_hierophant': ('V', 'THE HIEROPHANT', '教皇', '05_教皇'),
    '06_lovers': ('VI', 'THE LOVERS', '恋人', '06_恋人'),
    '07_chariot': ('VII', 'THE CHARIOT', '战车', '07_战车'),
    '08_strength': ('VIII', 'STRENGTH', '力量', '08_力量'),
    '09_hermit': ('IX', 'THE HERMIT', '隐士', '09_隐士'),
    '10_wheel_of_fortune': ('X', 'WHEEL OF FORTUNE', '命运之轮', '10_命运之轮'),
    '11_justice': ('XI', 'JUSTICE', '正义', '11_正义'),
    '12_hanged_man': ('XII', 'THE HANGED MAN', '倒吊人', '12_倒吊人'),
    '13_death': ('XIII', 'DEATH', '死神', '13_死神'),
    '14_temperance': ('XIV', 'TEMPERANCE', '节制', '14_节制'),
    '15_devil': ('XV', 'THE DEVIL', '恶魔', '15_恶魔'),
    '16_tower': ('XVI', 'THE TOWER', '高塔', '16_高塔'),
    '17_star': ('XVII', 'THE STAR', '星星', '17_星星'),
    '18_moon': ('XVIII', 'THE MOON', '月亮', '18_月亮'),
    '19_sun': ('XIX', 'THE SUN', '太阳', '19_太阳'),
    '20_judgement': ('XX', 'JUDGEMENT', '审判', '20_审判'),
    '21_world': ('XXI', 'THE WORLD', '世界', '21_世界'),
}


def _find_art_root():
    """art 目录在仓库外。主仓库和工作树离它的层数不同，所以往上逐级找。"""
    for parent in Path(__file__).resolve().parents:
        candidate = parent / 'art/mystical_cat_tarot'
        if candidate.is_dir():
            return candidate
    return None


ART_ROOT = _find_art_root()
# 终稿不进仓库，只用于审稿对照；找不到时跳过参考图
REFERENCE_ROOT = Path(os.environ['TAROT_REFERENCE']) if 'TAROT_REFERENCE' in os.environ else (
    ART_ROOT / '灵猫大阿尔卡那_终稿与要素说明/01_逐牌终稿' if ART_ROOT else None)


def reference_path(card_id):
    if REFERENCE_ROOT is None:
        return None
    folder = REFERENCE_ROOT / CARDS[card_id][3]
    found = glob.glob(str(folder / '*.png'))
    return Path(found[0]) if found else None


def reference_image(card_id):
    """按 7:12 居中裁切后的终稿。只拿来和重绘结果并排看，绝不进入贴图。"""
    p = reference_path(card_id)
    if p is None:
        return None
    im = Image.open(p).convert('RGB')
    w, h = im.size
    target_h = round(w * 12 / 7)
    top = max((h - target_h) // 2, 0)
    return im.crop((0, top, w, top + target_h))
