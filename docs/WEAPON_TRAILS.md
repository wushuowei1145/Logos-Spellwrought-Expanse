# 刀光

有骨架动作的 3D 武器挥动时，刀身扫过的地方留下一道刀光。刀光顺着刀的真实运动方向，按武器造成的伤害类型换纹理和颜色；拿刀的手缠着魔力时，刀光换成那种元素的。

## 做法

刀光就是刀身扫过的那片面。每一帧记下刀身两端的位置：柄端叫 base，尖端叫 tip。相邻两次之间连成四边形，所以刀光一定顺着刀的运动方向，不需要另外指定方向。

**两种送点方式：**

| 谁 | 入口 | 帧间 |
| --- | --- | --- |
| 玩家（战斗骨架，`LongswordRig`） | `RigTrails`（`LongswordClient` 里已接好） | 按动作在上一帧到这一帧之间补满取样，约 1/240 秒一点，每点都是那一刻的真实姿势 |
| NPC（GeckoLib 模型里的刀骨骼） | `BoneTrails.feed`，在那根骨骼的渲染回调里调 | 只拿得到这一帧，用 Catmull-Rom 在帧间补成圆弧 |

**两种坐标：**
- 第三人称和 NPC 的刀光记在世界坐标里，在世界的自定义几何阶段画。人走开时，刀光留在原地慢慢散。
- 第一人称的刀光记在手部渲染的坐标里，在画手时画，跟着镜头走。

**渲染：**
- 每种样式由几层带子组成，一层一条管线，着色器是 `shaders/core/weapon_trail.{vsh,fsh}`。
- 每层是形状遮罩 × 流动噪声。刀光随年龄从尾部被噪声侵蚀，外缘有一道热亮的刃线，颜色按"热度"在三色之间取。
- 混合用预乘 alpha：`occlude` 为 0 是纯发光，为 1 是照常盖上去。带颜色的身子要取中间值，否则白天会被亮天空洗成白色。
- 另有刀尖甩出的粒子，以及沿刀尖的一层柔光（`SoftGlow`）。
- 开着光影包时，刀光经 `ShaderPackOverlay` 在合成后画；阴影通道里送来的点直接丢掉。

代码在 `client/trail/`：
- `WeaponTrails`：总入口、画、粒子；
- `TrailRibbon`：扫过的面；
- `TrailStyle`、`TrailStyles`：样式；
- `TrailMaterials`：管线；
- `TrailConfigs`：哪把武器、哪一招出光；
- `BoneTrails`：NPC 入口。

玩家的接入在 `client/longsword/RigTrails`。

## 给玩家武器加刀光

每个动作组写一个 `assets/logos_dimension/combat/trails/<动作组>.json`。动作组就是 `CombatWeapon.register` 的第三个参数。没有这个文件的动作组不出刀光，弓就是这样。

```json
{
  "blade": {"from": 3.5, "to": 15.5},
  "meshes": {"某个网格": {"from": 2, "to": 20, "style": "logos_dimension:fire"}},
  "style": "logos_dimension:physical",
  "fixed": false,
  "speed": 5.5,
  "auto": false,
  "clips": {
    "slash_1": {},
    "slash_4": {"windows": [[0.26, 0.52]]},
    "riposte": {"style": "logos_dimension:lightning"},
    "sheathe": "none"
  }
}
```

**字段：**
- `blade`：刀身在武器网格里沿 +Y 从哪到哪，单位是网格单位，原点在握柄，刀尖朝 +Y。从护手或刀镡之上量起，到刀尖为止。刀身不写在 `combat/weapons/*.json` 里，因为那些文件由导入工具整份重写。
- `tip_x`：可选，写在 `blade` 或 `meshes` 的条目里。弯刀的刀尖不在 Y 轴上时，填刀尖的 X（网格单位），刀身按直线从柄端斜到刀尖；流火是 -2.25。
- `meshes`：共用动作组的不同网格各自的刀身和样式，可选。
- `style`：默认样式，也就是这把武器造成的伤害类型。
- `fixed`：为 true 时魔力缠绕不改样式，用于武器自带属性的情况，比如流火的狐火。
- `speed`：自动出光的刀尖速度，单位格/秒，在第三人称骨架里量，不含走路、转身。默认 7。
- `auto`：为 false 时只有 `clips` 里列出的招式出光；为 true 时没列出的招式也按速度自动出光。
- `clips` 里每一招有三种写法：
  - `{}`：按速度自动出光；
  - `"windows"`：按招式里的秒指定出光时段；
  - `"none"`：不出光。
  - 每一招都可以再用 `"style"` 覆盖样式。

**样式按什么顺序选：** 招式指定 → `meshes` → 动作组默认；拿刀的手缠着魔力、且样式不 `fixed` 时，换成 `logos_dimension:<元素>`。

**挑招式：**
- 攻击类招式都要有刀光：连招、重击、突刺、弹刀后的反击。
- 待机、格挡、拔刀、收刀一般不要，转刀这类纯展示的动作看效果决定。
- 自动出光省事。如果出光拖得太长（蓄势也亮）或太短（只亮一下），就改用 `windows`，以动作文件 `timeline` 里的 `contact` 为中心往前后取。

## 给 NPC 的刀加刀光

在刀骨骼的渲染回调里调：

```java
BoneTrails.feed(key, holder, poseStack.last().pose(), base, tip,
        LogosDimension.id("physical"), false, null, 6f);
```

**参数：**
- `key`：每把刀一个，比如 `"fox_katana:" + entity.getId()`。
- `base`、`tip`：刀身两端在这个姿势里的坐标，要乘好模型缩放。
- 第 6、7 个参数：武器默认样式，以及是否 `fixed`。
- `emit`：传 `null` 时按刀尖相对持刀者的速度自动出光，阈值是最后的 `speed`；也可以按当前动作名和时刻传 true 或 false。

光影包阴影通道里的调用会被自动忽略，同一帧画两次也只记一次。被剔除后又回来、两次送点隔得太久时，会自动断开，不会跨着空档连一条直的。

**按动作出光要用动画自己的时刻。** 用服务端同步来的招式开始刻推算会晚 1–2 刻，而一刀往往只挥 0.1 秒，时段会落在挥刀前的停顿里。要从 GeckoLib 控制器的 `getCurrentAnimationPoint()` 取当前动作名和 `animTime()`，`ShiraumeTrails` 就是这样做的。

## 样式

样式文件是 `assets/<ns>/combat/trail_styles/<名字>.json`。每种伤害类型一种：`physical`、`fire`、`frost`、`lightning`、`void`、`arcane`、`life`，再加附属的 `foxfire`。武器可以有自己的样式，比如某把剑独有的光。

```json
{
  "life": 0.34,
  "layers": [
    {"mask": "logos_dimension:textures/vfx/trail/slash_body.png",
     "noise": "logos:textures/vfx/charge_aura/noise_07.png",
     "span": [0.08, 1.0], "colors": ["#FFFFFF", "#B4C6E2", "#4A6488"],
     "occlude": 0.5, "intensity": 1.0, "opacity": 0.55,
     "noise_scale": 0.9, "noise_stretch": 1, "scroll": 1.2, "distort": 0.06, "detail": 0.55,
     "erosion": 0.96, "soft": 0.3, "heat": 1.2, "rim": 0, "rim_width": 14}
  ],
  "sparks": {"texture": "logos:textures/vfx/physical/star.png", "frames": 1, "per_block": 3, "life": 0.32,
             "size": 0.05, "colors": ["#FFFFFF", "#8890A0"], "gravity": 2, "drag": 3, "spread": 1.0,
             "inherit": 0.15, "spin": 4, "blend": "add"},
  "glow": {"alpha": 0.3, "color": "#DCE4F5", "width": 0.16}
}
```

**整体：**
- `life`：一点从扫过到散尽的秒数。

**每一层：**
- `span`：横跨刀身的位置，0 是 `blade.from`，1 是刀尖，大于 1 就伸出刀尖之外。
- `colors`：三色，依次是最亮、过渡、将散。
- `occlude`：0 发光，1 盖色。
- `intensity`、`opacity`：亮度与不透明度。
- `noise_scale`：噪声沿刀光的密度，单位是每格多少个噪声单元。
- `noise_stretch`：噪声横跨刀身的密度。
- `scroll`：噪声往尾部流动的速度。
- `distort`：噪声把遮罩扭开多少。
- `detail`：噪声在形状里占多少。
- `erosion`：随年龄侵蚀的快慢。
- `soft`：侵蚀边缘的软硬。
- `heat`：多亮算"最亮"。
- `rim`、`rim_width`：外缘刃线的强弱与粗细。
- `facing`：沿刀尖路径再画一条朝向镜头的带子，单位是半宽多少格，0 不画。刀扫过的面被侧着看时会细成一条线，比如从背后看竖劈，或者直刺几乎扫不出面。这条带子按侧着的程度淡入，正对着看时不画。一般只给刃线层，取 0.06。

**粒子：**
- `per_block`：刀尖每走一格甩出几颗。
- `frames`：贴图横排几帧。
- `colors`：随年龄渐变。
- `inherit`：带上刀尖速度的比例。
- `blend`：`blend` 是照常盖色，白天看得出颜色；`add` 是发光。

**贴图：**
- 形状遮罩在 `textures/vfx/trail/`，由 `tools/trail_textures.py` 生成，有身子、刃线、速度线、火舌、电弧、冰晶、墨痕、柔丝。遮罩的坐标是横向从刀光头（左）到尾（右），纵向从刀尖外缘（上）到柄（下）。
- 噪声和粒子贴图直接用 Logos 已有的 `logos:textures/vfx/...`，不复制。
- 新形状加到 `trail_textures.py` 里生成，不要手画后丢进来没有来源。

## 检查

- 单元测试 `WeaponTrailsTest`：样式和设置文件读得通、引用的贴图与动作都在、出光时段不超出动作、每种伤害类型都有样式；扫过的面按段记点，帧间补点贴着圆弧。
- 实际客户端 `group:weapon_trails`：打刀四段连招在第三人称背后、正面与第一人称截图；依次缠上七种元素各砍一刀，检查样式切换并截图。检查数值时可用 `WeaponTrails.ribbons()`、`WeaponTrails.sparks()`、`LongswordClient.trailStyle(entity, firstPerson)`。
