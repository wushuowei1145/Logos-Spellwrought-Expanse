"""森林精灵的动画文件：借用车万女仆（TLM）的移动类默认动画，加上代码绘制的弓与施法动作。

    python tools/forest_elf_animations.py [--tlm <maid.animation.json 路径或 URL>]

- 待机 `idle` 保留动画文件里现有的（模型合并脚本导出的那一份）。
- 借用 TLM 默认女仆动画（CC BY-NC-SA 4.0，CREDITS.md 登记）：位移按本模组的 2 倍制作放大，
  去掉精灵模型没有的骨骼。来源固定在 TLM 1.21 分支的一个提交，重跑结果不变。
- 自绘：持弓、拉弓、持矛、戳刺、举盾、抚生之触、生息之环、行礼、拒绝。手臂静止姿势外张约 18°（A 字），
  向前举时用 z 抵消。弓和施法的手指向目标由渲染器按视线俯仰叠加在 `Arm` 骨骼上，这里只做水平的姿势。
- 角度沿用 Bedrock 动画的写法：手臂 x 为负是向前举，身体 x 为正是前倾。
- 死亡 `death`：维护者在 Blockbench 里做的原创动画（仓库外 forest-elf-model/forest_elf/Animation/forest_elf_death.bbmodel），
  按 Blockbench 导出 Bedrock 的规则换算：旋转 X、Y 与位移 X 取反，`hold` 循环写成 `hold_on_last_frame`。
  源文件不在时保留动画文件里现有的 `death`。
"""
import argparse
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/logos_dimension/geckolib"
ANIMATION = ASSETS / "animations/entity/forest_elf.animation.json"
MODEL = ASSETS / "models/entity/forest_elf.geo.json"
DEATH_SOURCE = ROOT.parent / "forest-elf-model/forest_elf/Animation/forest_elf_death.bbmodel"
TLM_COMMIT = "207647c85740b1d0971de6040d6dd57cea62528c"
TLM_URL = ("https://raw.githubusercontent.com/TartaricAcid/TouhouLittleMaid/" + TLM_COMMIT
           + "/src/main/resources/assets/touhou_little_maid/animation/maid.animation.json")
# 借用的 TLM 动画（名字不变）；登记在 CREDITS.md
BORROWED = ["walk", "jump", "attacked", "ladder_up", "ladder_stillness", "ladder_down", "swim_stand", "swing_hand"]
# TLM 女仆模型约 24 单位高，精灵按 2 倍制作
POSITION_SCALE = 2.0


def load_tlm(source):
    if source.startswith("http"):
        with urllib.request.urlopen(source) as r:
            return json.loads(r.read().decode("utf-8"))
    return json.loads(Path(source).read_text(encoding="utf-8"))


def scale_positions(channel):
    """位移关键帧乘以 POSITION_SCALE；关键帧可以是数组、带 pre/post 的对象，也可以是单个数组。"""
    def scale(v):
        return [x * POSITION_SCALE if isinstance(x, (int, float)) else f"({x})*{POSITION_SCALE:g}" for x in v]
    if isinstance(channel, list):
        return scale(channel)
    out = {}
    for t, frame in channel.items():
        if isinstance(frame, list):
            out[t] = scale(frame)
        else:
            out[t] = {k: (scale(v) if k in ("pre", "post") else v) for k, v in frame.items()}
    return out


def adapt(anim, bones):
    out = {k: v for k, v in anim.items() if k != "bones"}
    out["bones"] = {}
    for name, channels in anim.get("bones", {}).items():
        if name not in bones:
            continue
        b = dict(channels)
        if "position" in b:
            b["position"] = scale_positions(b["position"])
        out["bones"][name] = b
    return out


# Blockbench 关键帧换算到 Bedrock：旋转 X、Y 与位移 X 取反
BB_SIGN = {"rotation": (-1, -1, 1), "position": (-1, 1, 1), "scale": (1, 1, 1)}
BB_LOOP = {"hold": "hold_on_last_frame", "loop": True, "once": False}


def from_bbmodel(path, name, bones):
    """从 .bbmodel 里取出一个动画，换算成 Bedrock / GeckoLib 的写法；只保留游戏模型里有的骨骼。"""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    src = next(a for a in data["animations"] if a["name"] == name)
    out = {"loop": BB_LOOP.get(src["loop"], False), "animation_length": src["length"], "bones": {}}
    for animator in src["animators"].values():
        if animator.get("type", "bone") != "bone" or animator["name"] not in bones:
            continue
        channels = {}
        for k in sorted(animator["keyframes"], key=lambda k: k["time"]):
            sign = BB_SIGN[k["channel"]]
            point = k["data_points"][0]
            value = [float(point[a]) * s + 0.0 for a, s in zip("xyz", sign)]
            frame = {"post": value, "lerp_mode": "catmullrom" if k["interpolation"] == "catmullrom" else "linear"}
            channels.setdefault(k["channel"], {})[f"{round(k['time'], 4):g}"] = frame
        if channels:
            out["bones"][animator["name"]] = channels
    return out


def keys(frames, smooth=True):
    """{时间: [x, y, z]} → Bedrock 关键帧，平滑插值。"""
    return {f"{t:g}": ({"post": v, "lerp_mode": "catmullrom"} if smooth else v) for t, v in sorted(frames.items())}


def pose(length, bones, loop=None):
    anim = {"animation_length": length, "bones": {b: {"rotation": keys(f)} for b, f in bones.items()}}
    if loop is not None:
        anim["loop"] = loop
    return anim


# 手臂抵消 A 字外张、垂直向前（再加上渲染器的视线俯仰）
R_FWD, L_FWD = -18, 18


def drawn():
    """代码绘制的动作。"""
    a = {}
    # 矛的持握与戳刺只写右臂，盾的使用只写左臂，双持时两个控制器可以叠加。
    a["hold_mainhand:spear"] = pose(2, {
        "RightArm": {0: [-64, -8, R_FWD], 2: [-64, -8, R_FWD]},
        "RightForeArm": {0: [-22, 0, 0], 2: [-22, 0, 0]},
        "RightHandLocator": {0: [90, 0, 0], 2: [90, 0, 0]},
    }, loop=True)
    a["swing:spear"] = pose(.3, {
        "RightArm": {0: [-64, -8, R_FWD], .08: [-48, -12, R_FWD], .16: [-90, 0, R_FWD], .3: [-64, -8, R_FWD]},
        "RightForeArm": {0: [-22, 0, 0], .08: [-45, 0, 0], .16: [0, 0, 0], .3: [-22, 0, 0]},
    }, loop=False)
    for side, sign, hand in [("Left", 1, "offhand"), ("Right", -1, "mainhand")]:
        # 整臂在同侧肩膀前方举盾，抵消 A 字初始角，不向身体中线内收；前臂和手腕保持自然。
        # 挂点角度由肩部、GeckoLib 手持层和原版盾模型的合成矩阵求逆，只校正物品，让盾面直立朝前。
        shoulder = [.18355, sign * 3.0808, sign * 18.17591]
        # 未格挡时绕掌心的手臂轴翻转半圈，让横持的盾从身体内侧转到外侧。
        locator = [0, 180, 0 if side == "Left" else 180]
        a["hold_" + hand + ":shield"] = pose(2, {
            side + "Arm": {0: shoulder, 2: shoulder},
            side + "ForeArm": {0: [0, 0, 0], 2: [0, 0, 0]},
            side + "HandLocator": {0: locator, 2: locator},
        }, loop=True)
        block = [shoulder[0] - 54, shoulder[1], shoulder[2]]
        block_locator = [101.636249, -14.383372, -20.669024] if side == "Left" else [-101.636249, -14.383372, -159.330976]
        a["use_" + hand + ":shield"] = pose(2, {
            side + "Arm": {0: block, 2: block},
            side + "ForeArm": {0: [0, 0, 0], 2: [0, 0, 0]},
            side + "HandLocator": {0: block_locator, 2: block_locator},
        }, loop=True)
    # 持弓：弓臂（右手）微微前提、肘部弯曲，弓斜握在身前侧
    a["hold_mainhand:bow"] = pose(2, {
        "RightArm": {0: [-14, 0, -6], 2: [-14, 0, -6]},
        "RightForeArm": {0: [-28, 0, 0], 2: [-28, 0, 0]},
    }, loop=True)
    # 拉弓：0.35 秒举弓搭箭，1.2 秒拉满（精灵 24 刻满弦），停在最后一帧。参照玩家拉弓：
    # 弓臂（右手）笔直向前，左手向内伸到弓的位置、小臂稍弯拉弦；上身只微微侧转（远小于 45°），
    # 头和弓臂反向转回同样的角度，视线与弓仍朝正前。转向目标由渲染器把整个人转过去。
    a["use_mainhand:bow"] = pose(1.2, {
        "UpBody": {0: [0, 0, 0], 0.35: [0, 8, 0], 1.2: [0, 12, 0]},
        "Head": {0: [0, 0, 0], 0.35: [0, -8, 0], 1.2: [0, -12, 0]},
        "RightArm": {0: [-20, 0, -8], 0.35: [-100, -8, R_FWD], 1.2: [-100, -12, R_FWD]},
        "RightForeArm": {0: [-20, 0, 0], 0.35: [0, 0, 0], 1.2: [0, 0, 0]},
        # 两肩相距约 8 单位、整条手臂约 15 单位：左手要向内约 34° 才够到弓，上身侧转已经转了 12°。
        # 左臂 y 为正是向内（为负会向外甩开）
        "LeftArm": {0: [-20, 0, 8], 0.35: [-98, 18, L_FWD], 1.2: [-97, 22, L_FWD]},
        "LeftForeArm": {0: [-30, 0, 0], 0.35: [-6, 0, 0], 1.2: [-12, 0, 0]},
    }, loop="hold_on_last_frame")
    # 抚生之触：左手（右手拿弓）掌心朝前伸出，停一下再收回
    a["heal_touch"] = pose(0.8, {
        "LeftArm": {0: [0, 0, 0], 0.18: [-86, 0, L_FWD], 0.55: [-86, 0, L_FWD], 0.8: [0, 0, 0]},
        "LeftForeArm": {0: [0, 0, 0], 0.18: [-8, 0, 0], 0.55: [-8, 0, 0], 0.8: [0, 0, 0]},
        "LeftHand": {0: [0, 0, 0], 0.18: [30, 0, 0], 0.55: [30, 0, 0], 0.8: [0, 0, 0]},
        "UpBody": {0: [0, 0, 0], 0.18: [0, -10, 0], 0.55: [0, -10, 0], 0.8: [0, 0, 0]},
    }, loop=False)
    # 生息之环：双手在胸前合拢，再向两侧展开画一个圈，身体微微后仰
    a["heal_ring"] = pose(1.3, {
        "RightArm": {0: [0, 0, 0], 0.3: [-70, 25, -16], 0.75: [-35, 0, 62], 1.05: [-20, 0, 50], 1.3: [0, 0, 0]},
        "LeftArm": {0: [0, 0, 0], 0.3: [-70, -25, 16], 0.75: [-35, 0, -62], 1.05: [-20, 0, -50], 1.3: [0, 0, 0]},
        "RightForeArm": {0: [0, 0, 0], 0.3: [-40, 0, 0], 0.75: [-10, 0, 0], 1.3: [0, 0, 0]},
        "LeftForeArm": {0: [0, 0, 0], 0.3: [-40, 0, 0], 0.75: [-10, 0, 0], 1.3: [0, 0, 0]},
        "UpBody": {0: [0, 0, 0], 0.3: [4, 0, 0], 0.75: [-6, 0, 0], 1.3: [0, 0, 0]},
    }, loop=False)
    # 行礼（林间礼）：右手抚胸、上身欠身
    a["greet"] = pose(1.6, {
        "UpBody": {0: [0, 0, 0], 0.35: [4, 0, 0], 0.7: [16, 0, 0], 1.1: [16, 0, 0], 1.6: [0, 0, 0]},
        "RightArm": {0: [0, 0, 0], 0.35: [-38, 34, -28], 1.1: [-38, 34, -28], 1.6: [0, 0, 0]},
        "RightForeArm": {0: [0, 0, 0], 0.35: [-105, 0, 0], 1.1: [-105, 0, 0], 1.6: [0, 0, 0]},
    }, loop=False)
    # 拒绝：摇头，左手在身前摆一下
    a["refuse"] = pose(0.9, {
        "Head": {0: [0, 0, 0], 0.15: [0, 16, 0], 0.35: [0, -16, 0], 0.55: [0, 12, 0], 0.75: [0, -6, 0], 0.9: [0, 0, 0]},
        "LeftArm": {0: [0, 0, 0], 0.2: [-50, 0, 8], 0.7: [-50, 0, 8], 0.9: [0, 0, 0]},
        "LeftForeArm": {0: [0, 0, 0], 0.2: [-60, 0, 0], 0.35: [-60, 30, 0], 0.55: [-60, -30, 0], 0.7: [-60, 0, 0], 0.9: [0, 0, 0]},
    }, loop=False)
    return a


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--tlm", default=TLM_URL)
    parser.add_argument("--death", default=str(DEATH_SOURCE))
    args = parser.parse_args()
    bones = {b["name"] for b in json.loads(MODEL.read_text(encoding="utf-8"))["minecraft:geometry"][0]["bones"]}
    current = json.loads(ANIMATION.read_text(encoding="utf-8"))
    tlm = load_tlm(args.tlm)["animations"]
    animations = {"idle": current["animations"]["idle"]}
    for name in BORROWED:
        animations[name] = adapt(tlm[name], bones)
    for name, anim in drawn().items():
        missing = set(anim["bones"]) - bones
        assert not missing, (name, missing)
        animations[name] = anim
    if Path(args.death).is_file():
        animations["death"] = from_bbmodel(args.death, "death", bones)
    elif "death" in current["animations"]:
        animations["death"] = current["animations"]["death"]
    out = {"format_version": current.get("format_version", "1.8.0"), "animations": animations}
    ANIMATION.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(animations)} 个动画：{', '.join(animations)}")


if __name__ == "__main__":
    main()
