"""导入审定仙剑美术包：网格、贴图、物品模型和物品定义。

用法：uv run python tools/import_lingxiao.py <美术目录>
动作由 tools/lingxiao_moves.py 编排、烘焙后直接写进仓库；招式文件 combat/movesets/lingxiao.json 手写维护，
时长和接触点要与动作一致（LingxiaoTest 检查）。御剑的位移由服务端载具完成，动作里没有根位移。
"""
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'


def main():
    source = Path(sys.argv[1])
    resources = source / 'resources/assets/logos_dimension'
    for folder in ('combat/weapons', 'textures/item', 'models/item', 'items'):
        for file in (resources / folder).glob('lingxiao*'):
            target = ASSETS / folder / file.name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(file, target)
    print('已导入仙剑网格、贴图与物品模型；动作请运行 tools/lingxiao_moves.py。')


if __name__ == '__main__':
    main()
