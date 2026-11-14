"""单独生成一组（或几组）乐器模型，可选顺便出离线预览：

    uv run --no-project --with pillow --with numpy python tools/instrument_models/run.py keyboards [organ ...] [--preview <目录>]

预览的模型列表取各组模块里的 PREVIEW。
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import instrument_models  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("groups", nargs="*", default=instrument_models.GROUPS)
    ap.add_argument("--preview")
    args = ap.parse_args()
    mods = instrument_models.build_all(args.groups)
    if args.preview:
        import model_preview as mp
        out = Path(args.preview)
        for mod in mods:
            for ref in getattr(mod, "PREVIEW", []):
                path, model = mp.sheet_for(ref, out)
                print(mp.report(ref, model))
                print(f"  -> {path}")


if __name__ == "__main__":
    main()
