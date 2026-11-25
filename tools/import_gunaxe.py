# -*- coding: utf-8 -*-
"""铳刃：从维护者手改过的 Blockbench 工程导入网格、贴图、物品模型和图标。

用法：uv run --with numpy --with pillow python tools/import_gunaxe.py <gunaxe.bbmodel> [预览目录]

工程最初由 tools/gunaxe.py --bbmodel 生成（Bedrock 格式、坐标即网格坐标、骨骼是同名的组、贴图内嵌）。
手改之后以工程为准，不再跑 gunaxe.py 的程序化生成，否则会覆盖手改。
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gunaxe  # noqa: E402


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    bb = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    m, tex_img = gunaxe.from_bbmodel(bb)
    gunaxe.write_assets(m, tex_img, sys.argv[2] if len(sys.argv) > 2 else None)


if __name__ == '__main__':
    main()
