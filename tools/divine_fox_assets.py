"""神狐的游戏资源：从仓库外的模型目录导入模型、贴图、零件表与动画，并画刷怪蛋。

    python tools/divine_fox_assets.py [--source <fox_model/divine_fox 目录>] [--voices <ogg 目录>]

模型目录（默认 ../fox_model/divine_fox，维护者的部件库）里已合并、导出好的文件：

- `build/exports/divine_fox.geo.json`、`divine_fox.png`：完整部件库的模型与贴图（build_race.py 合并）；
- `build/parts.json`：零件表，原样写成 `data/logos_dimension/npc_races/divine_fox.json`；
- `build/exports/divine_fox.animation.json` 里的 `idle`：素体自带的待机；
- `Animation/side_lying_trial/divine_fox_death.animation.json`：侧倒后面朝地面的死亡动画（维护者选定的版本）；
- `Animation/ambient/divine_fox_ambient.animation.json`：常驻叠加层 `pre_parallel0` 眨眼、`pre_parallel1` 耳朵抖动、
  `pre_parallel2` 尾巴轻摆（create_ambient_loops.py）；
- `Animation/katana/divine_fox_katana_v2.animation.json`：打刀动作在神狐骨架上的烘焙（本模组 tools/uchigatana_moves.py 写出，
  文件名是那里的 `FOX_KATANA`），动画名加 `katana_` 前缀并入，例如 `katana_slash_1`；
- `Animation/yumi/divine_fox_yumi.animation.json`：和弓的射法在神狐骨架上的烘焙（本模组 tools/yumi_moves.py 写出），
  动画名加 `yumi_` 前缀并入，例如 `yumi_kai`。

打刀：四套刀装的网格（`combat/weapons/fox_uchigatana_*.json`）按神狐的 1.6 倍挂到新加的 `Weapon` / `Sheath` 骨骼上
（父骨骼 `UpBody`、枢轴在原点，与烘焙用的骨架一致），每套刀装一组刀身 `katana_<配色>_blade`、一组刀鞘 `katana_<配色>_saya`，
贴图并到总贴图底部。平时由常驻叠加层 `pre_parallel3`（佩刀）把刀与鞘停在左腰的佩刀位置，打刀动作播放时由它接管。

和弓：弓的骨骼按神狐的 2.3 倍挂到新加的 `Yumi` 骨骼下（父骨骼 `UpBody`、枢轴在原点；动作文件里的 `Weapon` 轨道改名成 `Yumi`，
所以拉弓时腰间的刀不动）。四套配色共用弓的骨骼，方块放在每根骨骼下的 `yumi_<配色>_<骨骼>` 子骨骼里，只显示手里那把的配色；
箭的方块直接挂在 `BowArrow` 上。贴图并到总贴图底部。

移动类动作借用本模组森林精灵动画文件里已经按 2 倍制作换算好的车万女仆（TLM）动画（CC BY-NC-SA 4.0，CREDITS.md 登记），
两者骨架相同，只保留神狐模型里有的骨骼。

死亡动画按 160 帧/秒逐帧烘焙，导入时去掉能由前后两帧线性插值还原（误差 < 0.001）的中间帧，动作不变、文件小得多。

语音：维护者转好的 ogg（默认 `声音素材/神狐女/shenhu_female_ogg`），按文件名方括号里的标签归到各个事件，
复制为 `sounds/entity/divine_fox/<事件>_<哈希>.ogg`，只改写 sounds.json 里 `entity.divine_fox.*` 这几项。

叠加层在主状态之前播放，主状态动画用到同一骨骼时会盖过它（docs/NPC_ANIMATIONS.md）。死亡动画本身已经接管整条尾巴；
这里再给眨眼与抖耳两层用到的骨骼通道补上静止帧，倒下后不再眨眼、抖耳。
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension"
GECKO = ASSETS / "geckolib"
DEFAULT_SOURCE = ROOT.parent / "fox_model/divine_fox"
DEFAULT_VOICES = ROOT.parent.parent / "声音素材" / "神狐女" / "shenhu_female_ogg"
# 标签 → 事件（对标村民与森林精灵）。一个素材可以归到几个事件：疑惑与肯定当闲时，不满既是警觉也是拒绝。
VOICE_LABELS = {
    "ambient": ["疑惑", "肯定"],
    "greet": ["高兴", "肯定", "接受"],
    "yes": ["肯定"],
    "no": ["不满"],
    "alert": ["不满"],
    "attack": ["发力"],
    "cast": ["接受"],
    "hurt": ["到攻击"],   # 素材里写作「到攻击」与「收到攻击」
    "death": ["死亡"],
}
ELF_ANIMATION = GECKO / "animations/entity/forest_elf.animation.json"
# 借用的 TLM 动画（森林精灵文件里已换算好）；登记在 CREDITS.md
BORROWED = ["walk", "jump", "attacked", "ladder_up", "ladder_stillness", "ladder_down", "swim_stand", "swing_hand",
            "hold_mainhand:bow", "use_mainhand:bow"]
# 森林精灵自制的剧情动作（不是车万女仆的），同一套骨架，神狐打招呼与拒绝时借用
ELF_ACTIONS = ["greet", "refuse"]
LAYERS = ["pre_parallel0", "pre_parallel1", "pre_parallel2"]
# 打刀：四套刀装（与 UchigatanaItem 同名）、神狐挂刀的倍数（tools/uchigatana_moves.py 的 fox_scale）
KATANAS = ["ink", "vermilion", "indigo", "wood"]
KATANA_SCALE = 1.6
KATANA_PREFIX = "katana_"
# 模型目录 Animation/katana/ 下的烘焙文件（同 tools/uchigatana_moves.py 的 FOX_KATANA）
KATANA_SOURCE = "divine_fox_katana_v2.animation.json"
CARRY = "pre_parallel3"
# 和弓：四套配色（与 YumiItem 同名）、神狐挂弓的倍数（tools/yumi_moves.py 的 FOX_SCALE）
YUMIS = ["bamboo", "vermilion", "indigo", "chestnut"]
YUMI_SCALE = 2.3
YUMI_PREFIX = "yumi_"
YUMI_BONE = "Yumi"
# 执弓不带下半身与转身：拿着弓走路时腿交给行走动作。
YUMI_UPPER_ONLY = {"idle", "ready"}
YUMI_LOWER = {"Root", "AllBody", "RightLeg", "RightLowerLeg", "RightFoot", "LeftLeg", "LeftLowerLeg", "LeftFoot"}
FACES = ["north", "east", "south", "west", "up", "down"]


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, content, indent=2):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, ensure_ascii=False, indent=indent) + "\n", encoding="utf-8", newline="\n")


def bones_of(geo):
    return {b["name"] for b in geo["minecraft:geometry"][0]["bones"]}


def only(anim, bones):
    out = {k: v for k, v in anim.items() if k != "bones"}
    out["bones"] = {n: c for n, c in anim["bones"].items() if n in bones}
    return out


def prune(anim, tolerance=1e-3):
    """去掉线性插值就能还原的中间关键帧（只处理纯数组的线性帧），保留首尾帧。"""
    for channels in anim["bones"].values():
        for ch, keys in channels.items():
            if not isinstance(keys, dict) or len(keys) < 3 or not all(isinstance(v, list) for v in keys.values()):
                continue
            times = sorted(keys, key=float)
            t = [float(k) for k in times]
            v = [keys[k] for k in times]

            def fits(a, b):
                # a、b 之间的每一帧都落在 a→b 的直线上
                return all(abs(v[m][i] - (v[a][i] + (v[b][i] - v[a][i]) * (t[m] - t[a]) / (t[b] - t[a]))) < tolerance
                           for m in range(a + 1, b) for i in range(3))

            kept = [0]
            for i in range(1, len(t) - 1):
                if not fits(kept[-1], i + 1):
                    kept.append(i)
            kept.append(len(t) - 1)
            channels[ch] = {times[i]: v[i] for i in kept}
    return anim


def animations(source, bones):
    exported = read(source / "build/exports/divine_fox.animation.json")["animations"]
    elf = read(ELF_ANIMATION)["animations"]
    death = read(source / "Animation/side_lying_trial/divine_fox_death.animation.json")["animations"]["death"]
    layers = read(source / "Animation/ambient/divine_fox_ambient.animation.json")["animations"]
    katana = read(source / "Animation/katana" / KATANA_SOURCE)["animations"]
    yumi = read(source / "Animation/yumi/divine_fox_yumi.animation.json")["animations"]
    out = {"idle": only(exported["idle"], bones)}
    for name in BORROWED + ELF_ACTIONS:
        out[name] = only(elf[name], bones)
    for name, anim in katana.items():
        out[KATANA_PREFIX + name] = only(anim, bones)
    out[CARRY] = carry(katana["sheathe"])
    for name, anim in yumi.items():
        tracks = {YUMI_BONE if n == "Weapon" else n: c for n, c in anim["bones"].items()
                  if name not in YUMI_UPPER_ONLY or n not in YUMI_LOWER}
        out[YUMI_PREFIX + name] = prune(only({**anim, "bones": tracks}, bones))
    death = prune(only(death, bones))
    # 倒下后停止眨眼、高光抖动与抖耳：叠加层用到的每个骨骼通道，死亡动画没有的都补一个静止帧
    rest = {"rotation": [0, 0, 0], "position": [0, 0, 0], "scale": [1, 1, 1]}
    for layer in ["pre_parallel0", "pre_parallel1"]:
        for n, channels in layers[layer]["bones"].items():
            for ch in channels:
                death["bones"].setdefault(n, {}).setdefault(ch, {"0": rest[ch]})
    assert all(n in death["bones"] for n in layers["pre_parallel2"]["bones"]), "死亡动画须接管整条尾巴"
    out["death"] = death
    for name in LAYERS:
        out[name] = only(layers[name], bones)
        assert out[name]["bones"].keys() == layers[name]["bones"].keys(), name
    return {"format_version": "1.8.0", "animations": out}


def carry(sheathe):
    """佩刀：收刀动作的最后一帧（刀入鞘、鞘在左腰），刀与鞘停在那里的常驻叠加层；角度化到 ±180°。"""
    out = {}
    for bone in ("Weapon", "Sheath"):
        channels = {}
        for ch, keys in sheathe["bones"][bone].items():
            last = keys[max(keys, key=float)] if isinstance(keys, dict) else keys
            value = [((v + 180) % 360) - 180 for v in last] if ch == "rotation" else last
            channels[ch] = {"0.0": [round(v, 4) for v in value]}
        out[bone] = channels
    return {"loop": True, "animation_length": 1.0, "bones": out}


def bedrock_cube(c, s, u0, v0, shift=(0, 0, 0)):
    """网格是 Blockbench 坐标，按 bbtool.export_geo 的规则换成 Bedrock：x 取反、绕 x/y 的转角取反。s 是缩放，(u0, v0) 是贴图在总贴图里的位置，shift 是缩放前的平移。"""
    a = [(c["start"][i] + shift[i]) * s for i in range(3)]
    b = [(c["end"][i] + shift[i]) * s for i in range(3)]
    size = [b[i] - a[i] for i in range(3)]
    cube = {"origin": [round(-(a[0] + size[0]), 5), round(a[1], 5), round(a[2], 5)], "size": [round(v, 5) for v in size]}
    r = c.get("rotation") or [0, 0, 0]
    if any(abs(v) > 1e-6 for v in r):
        pv = [(c["pivot"][i] + shift[i]) * s for i in range(3)]
        cube["pivot"] = [round(-pv[0], 5), round(pv[1], 5), round(pv[2], 5)]
        cube["rotation"] = [round(-r[0], 5), round(-r[1], 5), round(r[2], 5)]
    uv = {}
    for f in FACES:
        if f not in c["uv"]:
            continue
        u1, v1, u2, v2 = c["uv"][f]
        u1, u2, v1, v2 = u1 + u0, u2 + u0, v1 + v0, v2 + v0
        uv[f] = {"uv": [u2, v2], "uv_size": [u1 - u2, v1 - v2]} if f in ("up", "down") else {"uv": [u1, v1], "uv_size": [u2 - u1, v2 - v1]}
    cube["uv"] = uv
    return cube


def katana_geometry(geo, atlas):
    """把四套刀装挂进模型：Weapon / Sheath 骨骼（UpBody 下、枢轴在原点），每套一组刀身、一组刀鞘，贴图并到总贴图底部。"""
    desc = geo["minecraft:geometry"][0]["description"]
    width, height = desc["texture_width"], desc["texture_height"]
    rigs = [Image.open(ASSETS / f"textures/item/fox_uchigatana_{v}_rig.png").convert("RGBA") for v in KATANAS]
    band = max(r.height for r in rigs)
    assert sum(r.width for r in rigs) <= width, "刀装贴图并排放不下"
    out = Image.new("RGBA", (width, height + band))
    out.paste(atlas, (0, 0))
    bones = geo["minecraft:geometry"][0]["bones"]
    bones += [{"name": "Weapon", "parent": "UpBody", "pivot": [0, 0, 0]}, {"name": "Sheath", "parent": "UpBody", "pivot": [0, 0, 0]}]
    s = KATANA_SCALE
    x0 = 0
    for variant, rig in zip(KATANAS, rigs):
        out.paste(rig, (x0, height))
        mesh = read(ASSETS / f"combat/weapons/fox_uchigatana_{variant}.json")
        for part, parent, group in (("weapon", "Weapon", "blade"), ("sheath", "Sheath", "saya")):
            # 刀尖 +Y、刃 +X
            cubes = [bedrock_cube(c, s, x0, height) for c in mesh["cubes"] if c["part"] == part]
            bones.append({"name": f"katana_{variant}_{group}", "parent": parent, "pivot": [0, 0, 0], "cubes": cubes})
        x0 += rig.width
    desc["texture_height"] = height + band
    return out


def yumi_geometry(geo, atlas):
    """把和弓挂进模型：Yumi 骨骼（UpBody 下、枢轴在原点）下是弓的骨骼，四套配色的方块各在子骨骼里，箭挂在 BowArrow 上；贴图并到总贴图底部。"""
    desc = geo["minecraft:geometry"][0]["description"]
    width, height = desc["texture_width"], desc["texture_height"]
    rigs = [Image.open(ASSETS / f"textures/item/fox_yumi_{v}_rig.png").convert("RGBA") for v in YUMIS]
    arrow_tex = Image.open(ASSETS / "textures/entity/projectiles/yumi_arrow.png").convert("RGBA")
    band = max(r.height for r in [*rigs, arrow_tex])
    assert sum(r.width for r in [*rigs, arrow_tex]) <= width, "和弓贴图并排放不下"
    out = Image.new("RGBA", (width, height + band))
    out.paste(atlas, (0, 0))
    bones = geo["minecraft:geometry"][0]["bones"]
    bones.append({"name": YUMI_BONE, "parent": "UpBody", "pivot": [0, 0, 0]})
    s = YUMI_SCALE
    mesh = read(ASSETS / f"combat/weapons/fox_yumi_{YUMIS[0]}.json")
    pivots = {b["name"]: [round(-b["pivot"][0] * s, 5), round(b["pivot"][1] * s, 5), round(b["pivot"][2] * s, 5)] for b in mesh["bones"]}
    rig_bones = {}
    for b in mesh["bones"]:
        rig_bones[b["name"]] = {"name": b["name"], "parent": YUMI_BONE if b["parent"] == "Weapon" else b["parent"], "pivot": pivots[b["name"]]}
        bones.append(rig_bones[b["name"]])
    x0 = 0
    for finish, rig in zip(YUMIS, rigs):
        out.paste(rig, (x0, height))
        cubes = read(ASSETS / f"combat/weapons/fox_yumi_{finish}.json")["cubes"]
        for name, pivot in pivots.items():
            own = [bedrock_cube(c, s, x0, height) for c in cubes if c["bone"] == name]
            if own:
                bones.append({"name": f"yumi_{finish}_{name}", "parent": name, "pivot": pivot, "cubes": own})
        x0 += rig.width
    out.paste(arrow_tex, (x0, height))
    # 箭的网格原点在箭筈：平移到搭箭点（BowArrow 的枢轴）。
    nock = mesh["attachments"][0]["origin"]
    rig_bones["BowArrow"]["cubes"] = [bedrock_cube(c, s, x0, height, nock) for c in read(ASSETS / "combat/weapons/yumi_arrow.json")["cubes"]]
    desc["texture_height"] = height + band
    return out


def spawn_egg():
    """16×16 刷怪蛋：米白底、金色毛斑、朱红两点；描边一级深金，左上一点高光。"""
    rows = [
        "......####......",
        ".....#aaaa#.....",
        "....#aaaaaa#....",
        "...#aabaaaaa#...",
        "...#aaaccaaa#...",
        "..#aaaaccaaaa#..",
        "..#aaaaaaaabb#..",
        "..#abbaaaaabba#.",
        ".#aabbaaaaaaaa#.",
        ".#aaaaaaasaaaa#.",
        ".#aaaccaaaaaaa#.",
        ".#aaaccaaaabba#.",
        "..#aaaaaaabba#..",
        "..#aaaasaaaaa#..",
        "...##aaaaaa##...",
        ".....######.....",
    ]
    colors = {"#": (166, 108, 43, 255), "a": (255, 248, 230, 255), "b": (234, 175, 77, 255),
              "c": (246, 205, 125, 255), "s": (201, 68, 53, 255)}
    img = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in colors:
                img.putpixel((x, y), colors[ch])
    img.putpixel((6, 2), (255, 255, 255, 255))
    path = ASSETS / "textures/item/divine_fox_spawn_egg.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    write(ASSETS / "models/item/divine_fox_spawn_egg.json",
          {"parent": "minecraft:item/generated", "textures": {"layer0": "logos_dimension:item/divine_fox_spawn_egg"}})
    write(ASSETS / "items/divine_fox_spawn_egg.json",
          {"model": {"type": "minecraft:model", "model": "logos_dimension:item/divine_fox_spawn_egg"}})


def channels(path):
    """ogg 的声道数（Vorbis 标识头第 11 字节）；立体声在游戏里不随距离衰减。"""
    data = path.read_bytes()
    i = data.find(b"vorbis")
    return data[i + 11] if i >= 0 else 0


def voices(folder):
    files = sorted(Path(folder).glob("*.ogg"))
    if not files:
        print(f"语音：没有素材（{folder}），sounds.json 不变")
        return
    out = ASSETS / "sounds/entity/divine_fox"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    events, used, stereo = {e: [] for e in VOICE_LABELS}, set(), []
    for src in files:
        hit = [e for e, labels in VOICE_LABELS.items() if any(l in src.stem for l in labels)]
        if not hit:
            continue
        # 文件名用素材名的哈希，避免中文与符号；同一素材重跑得到同一个名字
        name = f"{hit[0]}_{hashlib.sha1(src.name.encode('utf-8')).hexdigest()[:8]}"
        shutil.copyfile(src, out / f"{name}.ogg")
        used.add(src.name)
        if channels(src) > 1:
            stereo.append(src.name)
        for e in hit:
            events[e].append(f"logos_dimension:entity/divine_fox/{name}")
    path = ASSETS / "sounds.json"
    sounds = {k: v for k, v in read(path).items() if not k.startswith("entity.divine_fox.")}
    for e, files_ in events.items():
        assert files_, f"语音事件 {e} 没有素材"
        sounds[f"entity.divine_fox.{e}"] = {"subtitle": f"subtitles.logos_dimension.divine_fox.{e}", "sounds": sorted(files_)}
    write(path, sounds)
    print(f"语音：{len(used)} 个文件，{len(events)} 个事件；未归类 {[f.name for f in files if f.name not in used]}；立体声 {stereo}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", default=str(DEFAULT_SOURCE))
    parser.add_argument("--voices", default=str(DEFAULT_VOICES))
    args = parser.parse_args()
    source = Path(args.source)
    geo = read(source / "build/exports/divine_fox.geo.json")
    parts = read(source / "build/parts.json")
    atlas = yumi_geometry(geo, katana_geometry(geo, Image.open(source / "build/exports/divine_fox.png").convert("RGBA")))
    bones = bones_of(geo)
    write(GECKO / "models/entity/divine_fox.geo.json", geo, indent=None)
    atlas.save(ASSETS / "textures/entity/divine_fox.png")
    write(ROOT / "src/main/resources/data/logos_dimension/npc_races/divine_fox.json", parts, indent=1)
    write(GECKO / "animations/entity/divine_fox.animation.json", animations(source, bones), indent=1)
    spawn_egg()
    voices(args.voices)
    print("bones", len(bones), "parts", sum(len(s["parts"]) for s in parts["slots"].values()))


if __name__ == "__main__":
    main()
