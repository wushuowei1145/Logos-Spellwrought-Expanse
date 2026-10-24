#!/usr/bin/env python3
"""无窗口客户端场景截图工具。

为什么需要它：LDLib2 的 UI 场景跑完只把截图留在 build/ldlib2-uitest/screenshots/，
而且无窗口模式偶尔会把整张图压成 1×1（VERIFICATION.md 记录过同类问题），
脚本返回码仍然是 0。人工核对时既不好找图，也不容易发现坏图。

本工具做三件事：

  capture  跑一个场景/分组，校验截图尺寸，坏图自动重跑，然后把图整理进
           带 HTML 画廊的输出目录，方便直接打开查看。
  compare  对比两次 capture 的输出目录，给出逐图差异率并生成并排对照页。

用法：

  python tools/uitest_shots.py capture --scenario group:journey
  python tools/uitest_shots.py capture --scenario logos_elven_forest --retries 3
  python tools/uitest_shots.py compare build/uitest-shots/a build/uitest-shots/b

依赖：Python 3.10+；compare 需要 Pillow（仓库资源脚本已经在用）。
截图与日志只写入 build/ 或指定目录，不进版本库。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UITEST_DIR = ROOT / "build" / "ldlib2-uitest"
DEFAULT_OUT = ROOT / "build" / "uitest-shots"
MIN_SCREENSHOT_BYTES = 20_000

# Windows 控制台默认不是 UTF-8，中文提示会变成乱码；能改就改成 UTF-8。
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


def log(message: str) -> None:
    print(message, flush=True)


def find_java_home() -> str | None:
    """优先用环境变量，其次找本机常见目录里版本号最高的 JDK 25。"""
    from_env = os.environ.get("JAVA_HOME")
    if from_env and Path(from_env, "bin", "java.exe").exists() or (from_env and Path(from_env, "bin", "java").exists()):
        return from_env
    candidates: list[Path] = []
    for base in (
        Path(os.environ.get("LOCALAPPDATA", ""), "Programs", "Eclipse Adoptium"),
        Path("C:/Program Files/Eclipse Adoptium"),
        Path("C:/Program Files/Java"),
    ):
        if base.is_dir():
            candidates.extend(p for p in base.iterdir() if p.is_dir() and re.search(r"(jdk|jre)-?25", p.name, re.I))
    if not candidates:
        return None
    return str(sorted(candidates)[-1])


def find_logos_jar(explicit: str | None) -> Path:
    """定位主模组 JAR：显式参数 → 环境变量 LOGOS_JAR → 相邻检出目录的产物。"""
    if explicit:
        path = Path(explicit).expanduser().resolve()
        if not path.is_file():
            sys.exit(f"找不到 logos JAR: {path}")
        return path
    from_env = os.environ.get("LOGOS_JAR")
    if from_env:
        path = Path(from_env).expanduser().resolve()
        if path.is_file():
            return path
    patterns = [
        "LogosParent-current/build/libs/logos-*.jar",
        "../LogosParent-current/build/libs/logos-*.jar",
        "../minecraft-logos/build/libs/logos-*.jar",
    ]
    for pattern in patterns:
        for candidate in sorted(ROOT.glob(pattern)):
            if candidate.name.endswith("-sources.jar"):
                continue
            return candidate.resolve()
    sys.exit("找不到主模组 JAR：用 --logos-jar 指定，或设置 LOGOS_JAR，或让主模组检出位于 ../LogosParent-current")


def gradle_env(java_home: str) -> dict[str, str]:
    """把 JDK 与代理设置塞进 Gradle 环境：Java 不读 HTTPS_PROXY 环境变量，必须显式传系统属性。"""
    env = dict(os.environ)
    env["JAVA_HOME"] = java_home
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or os.environ.get("HTTP_PROXY")
    if proxy:
        match = re.match(r"https?://([^:/]+):?(\d+)?", proxy)
        if match:
            host, port = match.group(1), match.group(2) or "80"
            env["GRADLE_OPTS"] = (
                f"-Dhttps.proxyHost={host} -Dhttps.proxyPort={port} "
                f"-Dhttp.proxyHost={host} -Dhttp.proxyPort={port}"
            )
    return env


def scenario_slug(selection: str) -> str:
    return re.sub(r"[^0-9A-Za-z._-]+", "-", selection).strip("-") or "all"


def collect_screenshots() -> list[Path]:
    if not UITEST_DIR.is_dir():
        return []
    return sorted(p for p in UITEST_DIR.rglob("*.png") if p.is_file())


def screenshot_size(path: Path) -> tuple[int, int]:
    """只用 Pillow 读尺寸；没有 Pillow 时退化为「字节数够大就算好图」。"""
    try:
        from PIL import Image
    except ImportError:
        return (0, 0)
    try:
        with Image.open(path) as image:
            return image.size
    except Exception:
        return (0, 0)


def audit_screenshots() -> tuple[bool, list[str]]:
    """判断这一轮截图能不能用：尺寸太小或文件过小的都算坏图。"""
    shots = collect_screenshots()
    if not shots:
        return False, ["这一轮没有产生任何截图"]
    problems: list[str] = []
    for shot in shots:
        size = shot.stat().st_size
        width, height = screenshot_size(shot)
        if size < MIN_SCREENSHOT_BYTES:
            problems.append(f"{shot.name}: 文件只有 {size} 字节")
        elif width and height and (width < 100 or height < 100):
            problems.append(f"{shot.name}: 尺寸只有 {width}x{height}")
    return (not problems), problems


def read_report() -> dict:
    path = UITEST_DIR / "report.json"
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def run_capture_once(selection: str, window: str, jar: Path, java_home: str, log_path: Path) -> int:
    """跑一次 runClient；输出同时写日志文件与屏幕。"""
    gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [
        str(gradlew), "--console=plain", "runClient",
        f"-PlogosJar={jar}",
        f"-PldTest={selection}",
        "-PldTestHeadless",
        f"-PldTestWindow={window}",
    ]
    log(f"$ {' '.join(command)}")
    with log_path.open("w", encoding="utf-8", errors="replace") as log_file:
        process = subprocess.Popen(
            command, cwd=ROOT, env=gradle_env(java_home),
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        assert process.stdout is not None
        for line in process.stdout:
            log_file.write(line)
            if "[uitest]" in line or "BUILD " in line:
                log(line.rstrip())
        return process.wait()


def build_gallery(out_dir: Path, title: str, report: dict, meta: dict) -> Path:
    """生成离线 HTML 画廊：每张图一张卡片，显示场景、尺寸与字节数。"""
    cards: list[str] = []
    shots_root = out_dir / "screenshots"
    if shots_root.is_dir():
        for shot in sorted(shots_root.rglob("*.png")):
            width, height = screenshot_size(shot)
            relative = shot.relative_to(out_dir).as_posix()
            scenario = shot.parent.name
            cards.append(
                f'<figure><a href="{relative}"><img src="{relative}" loading="lazy" alt="{shot.name}"></a>'
                f"<figcaption><b>{scenario}</b> · {shot.name}<br>"
                f"{width}×{height} · {shot.stat().st_size // 1024} KB</figcaption></figure>"
            )
    environment = report.get("environment", {})
    rows = "".join(
        f"<tr><td>{key}</td><td>{value}</td></tr>" for key, value in {
            "状态": report.get("status", "?"),
            "选择": report.get("selection", meta.get("selection", "?")),
            "耗时": f"{report.get('durationMs', 0) / 1000:.1f} s",
            "窗口": f"{environment.get('windowWidth', '?')}×{environment.get('windowHeight', '?')}",
            "帧缓冲": f"{environment.get('framebufferWidth', '?')}×{environment.get('framebufferHeight', '?')}",
            "尝试次数": meta.get("attempts", "?"),
            "抓取时间": meta.get("captured_at", "?"),
        }.items()
    )
    html = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>{title}</title>
<style>
body{{background:#14161a;color:#e6e6e6;font:14px/1.6 system-ui,"Microsoft YaHei",sans-serif;margin:24px}}
h1{{font-size:20px;margin:0 0 12px}}
table{{border-collapse:collapse;margin-bottom:20px}}
td{{border:1px solid #2c3038;padding:4px 10px}}
.gallery{{display:grid;grid-template-columns:repeat(auto-fill,minmax(360px,1fr));gap:16px}}
figure{{margin:0;background:#1c1f25;border:1px solid #2c3038;border-radius:8px;overflow:hidden}}
figure img{{width:100%;display:block}}
figcaption{{padding:8px 10px;color:#a9b1c0;font-size:12px}}
</style></head><body>
<h1>{title}</h1>
<table>{rows}</table>
<div class="gallery">{''.join(cards)}</div>
</body></html>
"""
    index = out_dir / "index.html"
    index.write_text(html, encoding="utf-8")
    return index


def command_capture(args: argparse.Namespace) -> int:
    started = time.time()
    java_home = find_java_home()
    if not java_home:
        sys.exit("找不到 JDK 25：用 JAVA_HOME 指定")
    jar = find_logos_jar(args.logos_jar)
    log(f"JDK       : {java_home}")
    log(f"logos JAR : {jar}")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    slug = scenario_slug(args.scenario)
    out_dir = (Path(args.out).expanduser() if args.out else DEFAULT_OUT) / f"{stamp}_{slug}"
    logs_dir = out_dir / "logs"
    logs_dir.mkdir(parents=True, exist_ok=True)

    attempts = 0
    problems: list[str] = ["未开始"]
    report: dict = {}
    while attempts < max(1, args.retries):
        attempts += 1
        # 每轮先清掉上一轮产物，避免旧图混进这一轮的判定。
        if UITEST_DIR.is_dir():
            for stale in UITEST_DIR.rglob("*.png"):
                stale.unlink(missing_ok=True)
        log(f"--- 第 {attempts}/{args.retries} 次：{args.scenario} ---")
        code = run_capture_once(args.scenario, args.window, jar, java_home, logs_dir / f"attempt-{attempts}.log")
        report = read_report()
        ok, problems = audit_screenshots()
        if ok:
            log(f"截图校验通过（{len(collect_screenshots())} 张）")
            break
        log(f"截图校验未通过：{'; '.join(problems[:3])}")
        if code != 0:
            log(f"runClient 返回码 {code}，看 {logs_dir / f'attempt-{attempts}.log'}")
        if attempts < args.retries:
            log("清理后重跑……")

    if report:
        (out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    copied = 0
    for shot in collect_screenshots():
        relative = shot.relative_to(UITEST_DIR / "screenshots")
        target = out_dir / "screenshots" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(shot, target)
        copied += 1
    (out_dir / "screenshots").mkdir(parents=True, exist_ok=True)

    meta = {
        "selection": args.scenario,
        "attempts": attempts,
        "captured_at": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ"),
        "duration_seconds": round(time.time() - started, 1),
        "screenshots": copied,
        "problems": problems if copied == 0 else [],
    }
    (out_dir / "summary.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    gallery = build_gallery(out_dir, f"UI 场景截图 · {args.scenario}", report, meta)

    log("")
    log(f"状态      : {'OK' if copied else 'FAILED'}（尝试 {attempts} 次，{meta['duration_seconds']} s）")
    log(f"截图      : {copied} 张")
    log(f"输出目录  : {out_dir}")
    log(f"画廊      : {gallery}")
    if copied == 0:
        for problem in problems[:5]:
            log(f"  - {problem}")
        return 1
    if not args.no_open:
        log("用浏览器打开上面的 index.html 即可逐张查看。")
    return 0


def command_compare(args: argparse.Namespace) -> int:
    """对比两次 capture：逐图差异率 + 并排对照页，用于判断改动是否真的看得见。"""
    try:
        from PIL import Image, ImageChops, ImageEnhance
    except ImportError:
        sys.exit("compare 需要 Pillow：pip install pillow")

    left, right = Path(args.left), Path(args.right)
    for path in (left, right):
        if not (path / "screenshots").is_dir():
            sys.exit(f"{path} 不像 capture 输出目录（缺少 screenshots/）")
    out_dir = Path(args.out) if args.out else left.parent / f"compare_{left.name}_vs_{right.name}"
    pairs_dir = out_dir / "pairs"
    pairs_dir.mkdir(parents=True, exist_ok=True)

    rows: list[str] = []
    cards: list[str] = []
    for left_shot in sorted((left / "screenshots").rglob("*.png")):
        relative = left_shot.relative_to(left / "screenshots")
        right_shot = right / "screenshots" / relative
        if not right_shot.is_file():
            rows.append(f"<tr><td>{relative.as_posix()}</td><td colspan='3'>右侧缺失</td></tr>")
            continue
        a = Image.open(left_shot).convert("RGB")
        b = Image.open(right_shot).convert("RGB")
        if a.size != b.size:
            rows.append(f"<tr><td>{relative.as_posix()}</td><td colspan='3'>尺寸不同 {a.size} vs {b.size}</td></tr>")
            continue
        diff = ImageChops.difference(a, b)
        histogram = diff.convert("L").histogram()
        total = a.size[0] * a.size[1]
        changed = 100 * (total - histogram[0]) / total
        mean = sum(index * count for index, count in enumerate(histogram)) / total
        stem = re.sub(r"[^0-9A-Za-z._-]+", "-", relative.as_posix().replace("/", "_"))
        stack = Image.new("RGB", (a.size[0], a.size[1] * 2 + 8), (24, 26, 30))
        stack.paste(a, (0, 0))
        stack.paste(b, (0, a.size[1] + 8))
        stack.save(pairs_dir / f"{stem}_stack.png")
        ImageEnhance.Brightness(diff).enhance(6.0).save(pairs_dir / f"{stem}_diff.png")
        rows.append(
            f"<tr><td>{relative.as_posix()}</td><td>{changed:.2f}%</td>"
            f"<td>{mean:.2f}</td><td><a href='pairs/{stem}_stack.png'>并排</a> · "
            f"<a href='pairs/{stem}_diff.png'>差异</a></td></tr>"
        )
        cards.append(
            f"<figure><img src='pairs/{stem}_stack.png' loading='lazy'>"
            f"<figcaption>{relative.as_posix()} · 差异 {changed:.2f}% · 平均 {mean:.2f}</figcaption></figure>"
        )

    html = f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>UI 场景对比</title>
<style>
body{{background:#14161a;color:#e6e6e6;font:14px/1.6 system-ui,"Microsoft YaHei",sans-serif;margin:24px}}
table{{border-collapse:collapse;margin-bottom:20px}}td,th{{border:1px solid #2c3038;padding:4px 10px}}
.gallery{{display:grid;grid-template-columns:repeat(auto-fill,minmax(520px,1fr));gap:16px}}
figure{{margin:0;background:#1c1f25;border:1px solid #2c3038;border-radius:8px;overflow:hidden}}
figure img{{width:100%;display:block}}figcaption{{padding:8px 10px;color:#a9b1c0;font-size:12px}}
</style></head><body>
<h1>UI 场景对比</h1>
<p>左：{left}<br>右：{right}</p>
<p>提示：差异率包含取景/时间等噪声，先看同一构建连跑两次的噪声底，再看改动带来的增量。</p>
<table><tr><th>截图</th><th>差异像素</th><th>平均差异</th><th>附件</th></tr>{''.join(rows)}</table>
<div class="gallery">{''.join(cards)}</div>
</body></html>
"""
    index = out_dir / "index.html"
    index.write_text(html, encoding="utf-8")
    log(f"对比完成：{len(rows)} 张")
    log(f"画廊    : {index}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="无窗口客户端场景截图与对比工具")
    sub = parser.add_subparsers(dest="command", required=True)

    capture = sub.add_parser("capture", help="跑场景并整理截图")
    capture.add_argument("--scenario", default="group:journey", help="ldTest 选择：场景名、组名或 all")
    capture.add_argument("--window", default="1600x900", help="离屏渲染尺寸，默认 1600x900")
    capture.add_argument("--logos-jar", help="主模组 JAR 路径")
    capture.add_argument("--out", help="输出根目录，默认 build/uitest-shots")
    capture.add_argument("--retries", type=int, default=3, help="截图坏掉时最多尝试几次，默认 3")
    capture.add_argument("--no-open", action="store_true", help="只打印路径，不提示打开浏览器")
    capture.set_defaults(func=command_capture)

    compare = sub.add_parser("compare", help="对比两次 capture 输出")
    compare.add_argument("left")
    compare.add_argument("right")
    compare.add_argument("--out", help="对比输出目录")
    compare.set_defaults(func=command_compare)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
