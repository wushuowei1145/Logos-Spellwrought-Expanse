"""乐器新模型，按组分文件（keyboards、organ、strings、winds、percussion）。约定见 docs/INSTRUMENT_ART.md。

tools/instrument_art.py 生成完旧资源（方块状态、掉落、配方……）之后调用 build_all()，用这里的模型覆盖旧模型。
单独跑一组并出预览：

    uv run --no-project --with pillow --with numpy python tools/instrument_models/run.py keyboards --preview <目录>
"""
import importlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

GROUPS = ["keyboards", "organ", "strings", "winds", "percussion"]


def build_all(only=None):
    """按 GROUPS 的顺序跑各组的 build(out)，共用一个 Out，最后一起画贴图页、写文件。还没做的组跳过（沿用旧模型）。"""
    import instrument_kit as kit
    out = kit.Out()
    built = []
    for name in GROUPS:
        if only and name not in only:
            continue
        try:
            mod = importlib.import_module(f"instrument_models.{name}")
        except ModuleNotFoundError as e:
            if e.name == f"instrument_models.{name}":
                continue
            raise
        mod.build(out)
        built.append(mod)
    out.flush()
    return built
