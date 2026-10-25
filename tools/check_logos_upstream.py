"""开 PR 前检查主模组（Logos）有没有更新、是否兼容、有没有重复内容。

只读：不会改 Logos 仓库，也不会提交任何东西。发现需要处理的问题时以非零码退出，
方便在开 PR 之前当一道闸门用。

检查四件事：
1. Logos 本地检出的提交是否落后 origin/main（有更新就列出来）。
2. Logos 当前版本 vs 附属声明的依赖范围（neoforge.mods.toml 的 versionRange）。
3. 重复内容：把附属注册的方块/物品 ID 名拿到 Logos 源码里搜，看有没有同名同义实现。
4. 标签：附属往 Logos / 原版标签里追加的条目，主模组那边是否也有同名标签、是否用了 replace。

用法：
    python tools/check_logos_upstream.py
    python tools/check_logos_upstream.py --logos ../minecraft-logos --no-fetch
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODS_TOML = ROOT / "src/main/resources/META-INF/neoforge.mods.toml"


def run(*args, cwd=None):
    r = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def logos_version(logos: Path) -> str:
    text = (logos / "gradle.properties").read_text(encoding="utf-8", errors="replace")
    m = re.search(r"^mod_version\s*=\s*(\S+)", text, re.M)
    return m.group(1) if m else "?"


def declaration():
    """返回 (本附属 modId, Logos 依赖的版本范围)。只取 modId="logos" 那一节。"""
    text = MODS_TOML.read_text(encoding="utf-8", errors="replace")
    mod_id = re.search(r'modId\s*=\s*"([^"]+)"', text)
    logos_range = "?"
    for section in text.split("[[dependencies.")[1:]:
        if re.search(r'modId\s*=\s*"logos"', section):
            m = re.search(r'versionRange\s*=\s*"([^"]+)"', section)
            if m:
                logos_range = m.group(1)
            break
    return (mod_id.group(1) if mod_id else "?"), logos_range


def registered_ids() -> list[str]:
    """从 blockstates 与物品模型文件名里收集本附属注册的 ID。"""
    ids = set()
    for base, pattern in ((ROOT / "src/main/resources/assets/logos_dimension/blockstates", "*.json"),
                          (ROOT / "src/main/resources/assets/logos_dimension/items", "*.json")):
        if base.is_dir():
            for path in base.glob(pattern):
                ids.add(path.stem)
    return sorted(ids)


def tag_appends() -> list[Path]:
    out = []
    for ns in ("logos", "minecraft", "neoforge"):
        base = ROOT / f"src/main/resources/data/{ns}/tags"
        if base.is_dir():
            out.extend(sorted(base.rglob("*.json")))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--logos", default=str(ROOT.parent / "minecraft-logos"), help="Logos 仓库路径")
    ap.add_argument("--no-fetch", action="store_true", help="不执行 git fetch，只用本地已有引用")
    args = ap.parse_args()

    logos = Path(args.logos).expanduser().resolve()
    problems: list[str] = []
    if not (logos / ".git").exists():
        print(f"[错误] 不是 git 仓库：{logos}")
        return 2

    print(f"=== Logos 上游检查：{logos} ===")
    if not args.no_fetch:
        run("git", "fetch", "--all", "--prune", cwd=logos)

    _, head, _ = run("git", "log", "-1", "--format=%h %ad %s", "--date=short", cwd=logos)
    print(f"本地 HEAD：{head}")
    code, behind, _ = run("git", "rev-list", "--count", "HEAD..origin/main", cwd=logos)
    if code == 0 and behind.isdigit() and int(behind) > 0:
        problems.append(f"Logos 落后 origin/main {behind} 个提交")
        print(f"[更新] 落后 origin/main {behind} 个提交：")
        _, names, _ = run("git", "log", "--oneline", "HEAD..origin/main", cwd=logos)
        for line in names.splitlines()[:15]:
            print("   ", line)
    else:
        print("[更新] 已是最新的 origin/main")

    version = logos_version(logos)
    mod_id, logos_range = declaration()
    print(f"Logos 版本：{version}；附属 {mod_id} 对 logos 的依赖范围：{logos_range}")

    def semver_ok(v: str, rng: str) -> bool:
        def parts(x):
            return [int(n) for n in re.findall(r"\d+", x)[:3]]
        try:
            vp = parts(v)
            m = re.match(r"\[?\s*([0-9.]+)\s*,?\s*([0-9.]+)?\s*[\])]?$", rng.strip())
            if not m:
                return True
            lo = parts(m.group(1)) if m.group(1) else [0, 0, 0]
            hi = parts(m.group(2)) if m.group(2) else None
            return vp >= lo and (hi is None or vp <= hi)
        except Exception:
            return True

    if version != "?" and logos_range != "?" and not semver_ok(version, logos_range):
        problems.append(f"Logos {version} 不在依赖范围 {logos_range} 内")
        print(f"[兼容] 依赖范围 {logos_range} 覆盖不到 Logos {version}")
    elif version != "?" and logos_range != "?":
        print(f"[兼容] Logos {version} 落在依赖范围 {logos_range} 内（仍需用该版本的 jar 跑一次构建）")

    ids = registered_ids()
    print(f"本附属注册 ID：{len(ids)} 个（方块状态 + 物品模型）")
    args_grep = ["git", "grep", "-n", "-F", "origin/main", "--", "src/main/java", "src/main/resources/data/logos"]
    for i in ids:
        args_grep.extend(["-e", f'"{i}"'])
    code, hits, _ = run(*args_grep, cwd=logos)
    if code == 0 and hits:
        # 只保留"整串 ID 被当作字符串出现"的行，去掉只是子串命中的噪音。
        real = []
        for line in hits.splitlines():
            for i in ids:
                if f'"{i}"' in line:
                    real.append((i, line))
                    break
        if real:
            print(f"[重复] Logos 源码里出现同名 ID 字符串（{len(real)} 处）：")
            for i, line in real[:20]:
                print(f"    {i} -> {line.split(':')[0]}:{line.split(':')[1]}")
            problems.append("Logos 源码里出现与附属同名的 ID，需要确认是否重复实现/可以复用")
        else:
            print("[重复] 没有整串同名的 ID")
    else:
        print("[重复] 没有整串同名的 ID")

    for tag in tag_appends():
        rel = tag.relative_to(ROOT / "src/main/resources").as_posix()
        ours = json.loads(tag.read_text(encoding="utf-8"))
        replace = ours.get("replace", None)
        if replace is False:
            print(f"[标签] 追加 {rel}（replace:false，正确）")
        else:
            problems.append(f"{rel} 没有写 replace:false")
            print(f"[标签] 警告：{rel} 没有写 replace:false，会覆盖主模组条目")
        if rel.startswith("data/logos/"):
            code, upstream, _ = run("git", "show", f"origin/main:src/main/resources/{rel}", cwd=logos)
            if code == 0 and upstream.strip():
                print(f"[标签] 主模组也有 {rel}，确认追加式合并")
            else:
                problems.append(f"主模组没有 {rel}，可能是新标签，需要确认主模组是否认这个路径")
                print(f"[标签] 注意：主模组 origin/main 里还没有 {rel}")

    print()
    if problems:
        print("需要处理：")
        for p in problems:
            print(" -", p)
        return 1
    print("没有发现需要处理的问题。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
