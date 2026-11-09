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

**渲染：** 照 StylizedSlashesV2（UE5 素材包）刀光材质 `M_ComplexSlash_01` 的做法，在我们的“扫过的面”上重做。UE 的刀光是一个 270° 的环形网格，套三层材质：身子、刃线、各元素的高光。我们的带子横向就是年龄（头是刚扫过的地方），纵向从柄到刀尖外缘，对应 UE 网格的 U、V。
- 每种样式由几层带子组成，一层一条管线，着色器是 `shaders/core/weapon_trail.{vsh,fsh}`，层的参数写成 #define。
- **形状**：形状遮罩^对比度 × 强度，被扭曲噪声推开。遮罩画的是 UE 那种刀光：头部贴着外缘一大块最亮，往后拖成长短不一的条纹，越靠外缘拖得越长（`slash_streak`）；或一整块身子、尾端撕成豁口（`slash_blob`）；刃线是外缘一道往头部变粗的线，头部顺着刀身弯下一道钩（`slash_hook`）。
- **越远越窄**：越老的点，从柄那一侧往外缘收掉越多（`narrow`），几何上真的裁掉，着色器里给软边。离刀越远，刀光越窄、只剩贴着外缘的一条。
- **越远越透**：侵蚀 `smoothstep(e − f, e + f, 噪声)`，e 随年龄变大，噪声是沿刀光拉长的条纹，所以尾部被一条条吃掉。
- **颜色**：两色按流动的颜色噪声插值（往尾部流），乘强度（可以大于 1，按 1 − exp(−c) 压回，过亮时变白），形状最浓处偏向白热的芯；另可乘一张往尾部流的不透明噪声，做出流动的亮斑（UE 高光层的做法）。
- **往外飘**：刀光在散尽前顺着刀身往外飘出一点（`expand`），对应 UE 刀光网格随年龄放大。
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

**样式按什么顺序选：** 招式指定 → `meshes` → 动作组默认；拿刀的手缠着魔力、且样式不 `fixed` 时，换成 `logos_dimension:<元素>`。狐火缠绕在 Logos 看来是奥秘，但刀上烧的是狐火，所以先认狐火：白梅的淡粉换成 `foxfire`，神狐一族的幽蓝换成 `foxfire_kitsune`（`TrailStyles.resolve`）。

**挑招式：**
- 攻击类招式都要有刀光：连招、重击、突刺、弹刀后的反击。
- 待机、格挡、拔刀、收刀一般不要，转刀这类纯展示的动作看效果决定。
- 自动出光省事。如果出光拖得太长（蓄势也亮）或太短（只亮一下），就改用 `windows`，以动作文件 `timeline` 里的 `contact` 为中心往前后取。

## 现有武器

| 武器 | 设置 | 样式 | 出光 | 场景 |
| --- | --- | --- | --- | --- |
| 神狐打刀（四套刀装） | `trails/uchigatana.json` | `physical` | 四段连砍、弹刀、突刺按速度 | `weapon_trails` |
| 曦誓、黑钢长剑 | `trails/longsword.json`（`meshes` 分样式） | `dawn`、`blacksteel` | 三段连击与曦誓的蓄力斩按时段 | `weapon_trails_longsword` |
| 群誓·狼牙长枪 | `trails/oath_spear.json` | `oath_spear` | 直刺、横扫、上挑、连刺各刺按时段，砸地按速度 | `weapon_trails_spear` |
| 凌霄 | `trails/lingxiao.json` | `lingxiao`；御剑飞行的尾迹 `lingxiao_flight` | 四式按时段，收剑的剑花也出光 | `weapon_trails_lingxiao` |
| 流火（玩家与白梅） | `trails/ryuka.json`（`tip_x` 弯刀） | `ryuka`（`fixed`） | 五段连斩、弹刀、突刺按时段；白梅按 GeckoLib 动画名记时段 | `weapon_trails_ryuka` |
| 神狐 NPC 的打刀 | 读玩家的 `trails/uchigatana.json` | `physical`，狐火缠绕时 `foxfire_kitsune` | 只在 `contact` 前后出光，够快就亮到这段结束 | `weapon_trails_fox` |

和弓不出刀光。

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

一种样式一般三层，照 UE：**身子**（`slash_blob` 或 `slash_streak`，带颜色、盖住一些背景、尾部被条纹吃掉）、**高光**（`slash_streak` 或元素自己的形状：火舌 `slash_flame`、电弧 `slash_bolt`、冰晶 `slash_shards`、柔丝 `slash_wisp`，亮色、乘一张往尾部流的不透明噪声）、**刃线**（`slash_hook`，细而亮，带 `facing`）。

```json
{
  "life": 0.38,
  "expand": 0.22,
  "layers": [
    {"mask": "logos_dimension:textures/vfx/trail/slash_blob.png",
     "detail_noise": "logos:textures/vfx/charge_aura/noise_smoke.png",
     "span": [0, 1], "colors": ["#FF7A1A", "#8A1400", "#FFD27A"],
     "occlude": 0.7, "color_strength": 1.3, "color_tile": [0.4, 1], "color_pan": 0.4,
     "main_contrast": 1.6, "opacity": 0.85, "distort": 0.06,
     "erosion": {"amount": 0.6, "start": 0.35, "pow": 1.6, "feather": 0.4, "tile": [0.3, 0.12]},
     "narrow": 0.78, "narrow_pow": 1.3, "narrow_soft": 0.18,
     "fade": {"in": 0.1, "out": 0.02, "tail": 0.25}},
    {"mask": "logos_dimension:textures/vfx/trail/slash_flame.png",
     "detail_noise": "logos:textures/vfx/charge_aura/noise_fire04.png",
     "span": [0.1, 1.12], "colors": ["#FF4A08", "#FFB040", "#FFF2B0"], "occlude": 0.25, "color_strength": 4.5,
     "opacity_noise": {"strength": 1.3, "contrast": 3, "tile": [0.6, 1], "pan": 0.8}},
    {"mask": "logos_dimension:textures/vfx/trail/slash_hook.png",
     "span": [0.15, 1.03], "colors": ["#FFF2B0", "#FF8A2A", "#FFFFFF"], "occlude": 0.25, "color_strength": 3.5,
     "narrow": 0, "facing": 0.06}
  ],
  "sparks": {"texture": "logos:textures/vfx/fire_impact/ember.png", "per_block": 8, "life": 0.6, "size": 0.045,
             "colors": ["#FFF2B0", "#FF4A00"], "gravity": -1.6, "blend": "blend"},
  "glow": {"alpha": 0.5, "color": "#FF6A10", "width": 0.3}
}
```

**整体：**
- `life`：一点从扫过到散尽的秒数。
- `expand`：散尽前顺着刀身往外飘出多少格。

**每一层**（没写的取默认值，见 `TrailStyle`）：
- 贴图：`mask` 形状遮罩；`erosion_noise` 侵蚀噪声（默认沿刀光拉长的条纹 `noise_aniso`）；`detail_noise` 颜色与不透明噪声（默认 Logos 的云雾噪声）；`distort_noise` 扭曲噪声。
- `span`：横跨刀身的位置，0 是 `blade.from`，1 是刀尖，大于 1 就伸出刀尖之外。
- `colors`：`[颜色一, 颜色二, 白热芯]`，前两色按颜色噪声插值。
- `color_strength`、`color_contrast`：颜色的强度（可大于 1）与对比度；`color_tile`（沿刀光每格、横跨）、`color_pan`（往尾部流的速度）。
- `hot_from`、`hot_gain`：形状多浓开始偏向白热芯；`emissive_mask`：形状浓淡乘进亮度的比例。
- `main_contrast`、`main_strength`：形状遮罩的对比度与强度。
- `opacity`、`occlude`：不透明度；0 发光，1 盖色。
- `distort`、`distort_tile`、`distort_pan`：扭曲的强弱、密度与流速。
- `opacity_noise`：`{strength, contrast, tile, pan}`，乘一张往尾部流的不透明噪声做流动亮斑；`strength` 为 0 不用。
- `erosion`：`{amount, start, pow, feather, contrast, strength, tile, pan}`。侵蚀从年龄 `start` 起，按 `pow` 变大到 `amount`；`feather` 是边缘软硬；`tile` 是噪声沿刀光每格的密度与横跨的密度（横跨取小值，条纹才是一长条）。
- `narrow`、`narrow_pow`、`narrow_soft`：年龄 a 处从柄那一侧收掉 `narrow × a^narrow_pow`，软边宽 `narrow_soft`。刃线层取 0。
- `fade`：`{in, out, tail}`，柄侧、外缘与尾端的淡出宽度。
- `rim`、`rim_width`：外缘再加一道细亮线（可选）。
- `facing`：沿刀尖路径再画一条朝向镜头的带子，单位是半宽多少格，0 不画。刀扫过的面被侧着看时会细成一条线，比如从背后看竖劈，或者直刺几乎扫不出面。这条带子按侧着的程度淡入，正对着看时不画。一般只给刃线层，取 0.06。

**粒子：**
- `per_block`：刀尖每走一格甩出几颗。
- `frames`：贴图横排几帧。
- `colors`：随年龄渐变。
- `inherit`：带上刀尖速度的比例。
- `blend`：`blend` 是照常盖色，白天看得出颜色；`add` 是发光。

**贴图：**
- 形状遮罩在 `textures/vfx/trail/`，由 `tools/trail_textures.py` 生成：条纹身子、实心身子、弯钩刃线、火舌、电弧、冰晶、墨痕、柔丝，以及旧的身子、刃线、速度线。遮罩的坐标是横向从刀光头（左）到尾（右），纵向从刀尖外缘（上）到柄（下）。形状参考了 StylizedSlashesV2 的刀光贴图，但都是自己生成的，没有拷它的图。
- 侵蚀用的条纹噪声 `noise_aniso` 也在这里生成（可平铺）；其余噪声和粒子贴图直接用 Logos 已有的 `logos:textures/vfx/...`，不复制。
- 新形状加到 `trail_textures.py` 里生成，不要手画后丢进来没有来源。

## 检查

- 单元测试 `WeaponTrailsTest`：样式和设置文件读得通、引用的贴图与动作都在、出光时段不超出动作、每种伤害类型都有样式；扫过的面按段记点，帧间补点贴着圆弧。
- 实际客户端 `group:weapon_trails`：打刀四段连招在第三人称背后、正面与第一人称截图；依次缠上七种元素各砍一刀，检查样式切换并截图；最后用俯视机位（隐身盔甲架）看物理、火焰、虚空的整道刀光形状。检查数值时可用 `WeaponTrails.ribbons()`、`WeaponTrails.sparks()`、`LongswordClient.trailStyle(entity, firstPerson)`。
