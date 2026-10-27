# NPC 模型美术规范

适用于附属模组中所有人形 NPC（剧情角色、商人、同伴等）。总原则继承 Logos `CONTRIBUTING.md` 第 6 节「美术资产规范」；本页只写 NPC 的补充和例外。两边冲突时，NPC 以本页为准。

NPC 采用车万女仆（Touhou Little Maid，下称 TLM）的 GeckoLib 女仆模型规范，目标有两个：

1. 在附属模组里用与 TLM 相同的渲染方式显示 NPC（外观接近 YSM，但 YSM 不开源，渲染细节无法核对）；
2. 同一份几何和贴图不改动就能放进 TLM 的自定义模型包，作为女仆模型使用。

怎样把角色做得好看（比例、脸、头发、服装、UV 的具体做法）见 [NPC 模型制作指南](NPC_MODELING_GUIDE.md)。在世界里大量生成的普通 NPC 的随机外观（发型、配色、五官、服装、身材）见 [NPC 随机外观方案](NPC_APPEARANCE.md)。

## 1. 格式

- 源文件：Blockbench **Bedrock 实体**工程（`model_format: bedrock`，不需要插件），每面独立 UV（不用 box UV）。`.bbmodel` 不进仓库。
- 几何：Bedrock geometry `format_version 1.12.0`，`geometry.<asset>`。附属模组用 GeckoLib 读取；TLM 自定义包设置 `"is_gecko": true` 后读取同一个文件。
- 动画：GeckoLib / Bedrock 动画 JSON（`format_version 1.8.0`）。
- 贴图：单张 PNG，宽高不要求是 2 的幂（Java 版实体贴图不要求）。只用完全不透明或完全透明的像素（alpha 0 / 255），发尾、领口、眼眶等镂空靠 alpha 裁切，不做半透明。

## 2. 骨骼命名（Logos 命名规则的例外）

骨骼名使用 TLM GeckoLib 女仆骨架的 **PascalCase 名称**，不改成 `snake_case`。TLM 的默认动画（待机、行走、坐下、睡觉、游泳、挥手、受击等）按这些名字驱动骨骼；改名就失去兼容。方块名仍按 Logos 规则用 `snake_case`。

必需层级（括号内为父骨骼）：

| 骨骼 | 父骨骼 | 枢轴位置 / 用途 |
|---|---|---|
| `Root` | — | 原点，整体位移 |
| `AllBody` | `Root` | 髋部，全身转向 |
| `UpBody` | `AllBody` | 腰部，上半身 |
| `UpperBody` | `UpBody` | 胸部，躯干方块挂在这里 |
| `AllHead` | `UpperBody` | 颈根，含脖子方块 |
| `Head` | `AllHead` | 下颌，头部转动与视线 |
| `Arm` | `UpperBody` | 双臂的公共父级 |
| `LeftArm` / `RightArm` | `Arm` | 肩关节 |
| `LeftForeArm` / `RightForeArm` | 对应上臂 | 肘关节 |
| `LeftHand` / `RightHand` | 对应前臂 | 腕关节 |
| `DownBody` | `AllBody` | 髋部，下半身 |
| `LeftLeg` / `RightLeg` | `DownBody` | 髋关节 |
| `LeftLowerLeg` / `RightLowerLeg` | 对应大腿 | 膝关节 |
| `LeftFoot` / `RightFoot` | 对应小腿 | 踝关节 |

左右指**角色自身**的左右。Blockbench 里正面朝 -Z，角色左侧在 -X；导出后 Bedrock 文件中 X 取反，左侧骨骼的枢轴 X 为正（与 TLM 内置模型一致）。

按角色需要添加的可选骨骼（用到时就用这些名字）：

- 头发：`Hair`（总父级）、`BaseHair`、`Bangs`、`LeftSideHair` / `RightSideHair`、`LongHair`、`LongLeftHair` / `LongRightHair`。
- 兽耳与尾巴：`Ear` → `Left_ear` / `Right_ear`（注意下划线写法沿用 TLM）；`Tail` → `Tail2` → `Tail3` …，挂在 `UpBody`。
- 裙摆：`Skirt`，挂在 `DownBody`。
- 表情：`Eyes`、`Eyelid`、`Mouth` 及其子骨骼，用显示/隐藏切换（见第 6 节）。
- 发光部件：骨骼名以 `ysmGlow` 开头（区分大小写），例如 `ysmGlowEyes`、`ysmGlowRune`，见第 5 节。

挂点（空骨骼，只读枢轴）：

| 骨骼 | 父骨骼 | 用途 |
|---|---|---|
| `LeftHandLocator` / `RightHandLocator` | 对应手 | 手持物品位置 |
| `BackpackLocator` | `UpperBody` | 背包 / 背后道具 |
| `cast_point` | `UpperBody` | Logos 施法点，见主模组规范 6.3 |

## 3. 尺寸与比例

- 先定游戏内显示高度，再按 **2 倍**制作。附属模组渲染器固定缩放 **0.5**；TLM 包写 `"render_entity_scale": 0.5`（TLM 限制在 0.2–2.0）。
  - 例：艾洛蒂显示时发顶约 1.7 格、耳尖约 1.9 格，制作时发顶约 55 单位、耳尖约 60 单位。
- 脚底 Y=0，正面朝 -Z。约 3.5–4 头身，以下巴到发顶为一头。腿约占全身的 45%。
- 不要把已有的原尺寸（头 7×7×7）模型直接放大 2 倍，那样游戏内会有 2.7 格以上。
- 缩放只改显示。碰撞箱、眼高、持物位置在接入时另外校准。

## 4. 贴图与 UV

- 每（2 倍）模型单位 **1 纹素**，显示时相当于每单位 2 纹素，保持 MC 像素颗粒感。细节（高光、睫毛、嘴、扣子等）用小方块表现，不靠提高贴图分辨率。
- **唯一例外是眼睛**：每只眼一块平面，单独分配更高的像素（如 25×30），用来画描边、虹膜和高光。
- 单色面同色共用 1 个纹素，UV 取纹素中心、尺寸为 0；像素完全相同的面共用同一块；看不见的面不分配 UV（texture 为空，也不渲染）。其余区域紧密排布。
- 不同的面之间不能有意外的 UV 重叠。有意共用的纹素必须只出现在看不到的地方，或者颜色相同。
- 明暗画在贴图里：发束分缕、深浅交替、发根到发梢渐暗、关节处略深。不要把某个方向的光照画死，游戏会再加实体光照。
- 表情只画在 `Head` 正面（或之后的表情骨骼上）。

## 5. 渲染风格

附属模组的 `NpcRenderer` 按 TLM 的 GeckoLib 女仆渲染方式实现（对照 TLM 1.21 分支源码 `GeckoEntityMaidRenderer`、`geckolib3/geo/IGeoRenderer`、`GeoBone`）：

- 渲染类型：TLM 用 `RenderType.entityCutoutNoCull`；26.1 中同一种渲染类型叫 `RenderTypes.entityCutout`（`entityCutoutCull` 才剔除背面）。alpha 裁切，不剔除背面，薄发片、裙摆内侧两面都能看到；
- 原版实体光照：两盏固定方向光加环境光，正面亮、侧面暗，没有自定义着色器，不另加描边或卡通着色；
- 使用世界光照（方块光与天空光）；
- 发光骨骼：名字以 `ysmGlow` 开头的骨骼（YSM 的约定，TLM 沿用），自身方块按满亮度（方块光、天空光均为 15）绘制，夜里也是亮的；子骨骼不受影响，被动画隐藏时仍然隐藏。颜色来自贴图，不叠加辉光或泛光。

需要发光的部件（眼睛、魔法饰品）单独拆成 `ysmGlow…` 骨骼，并在说明里写明。只把颜色画亮、不放进发光骨骼，不会在暗处发光。

YSM 不开源、模型文件加密，「YSM 风格」只指外观相近；它的渲染类型和光照没有核对过。渲染器属于代码改动，按主模组规范另开 PR，不和美术 PR 混在一起。

## 6. 表情与动画

- 先交付「常态」表情并通过模型验收，再添加其他表情。
- 其他表情、眨眼按 TLM 的结构在 `Head` 下加 `Eyes` / `Eyelid` / `Mouth` 子骨骼，用显示/隐藏切换。
- 动画由 `NpcAnimations` 驱动，沿用 TLM 的控制器、动画名（`idle`、`walk`、`run`、`attacked`、`sit` 等，不加 `animation.<asset>.` 前缀）、优先级和手部条件名。制作要求见 [NPC 动画制作规范](NPC_ANIMATIONS.md)。
- 放进 TLM 包时，TLM 会把自带的默认女仆动画合并进来，只补模型自己没定义的名字，所以 TLM 包可以不带动画；附属模组里则只播放 NPC 动画文件里有的名字。

## 7. 授权边界

TLM 的代码为 MIT，模型、贴图、动画等美术资源为 CC BY-NC-SA 4.0（TLM 1.21 分支 `gradle.properties` 的 `mod_license`）。

- **模型与贴图**：NPC 的模型和贴图全部自行制作（附属模组要求：代码绘制，不使用图像生成服务），不使用 TLM 的模型或贴图。骨骼命名和包结构只是参照，不构成借用。
- **动画**：可以借用 TLM 的默认动画（`assets/touhou_little_maid/animation/maid.animation.json`），原样或修改后放进 NPC 的动画文件，按 [致谢与第三方素材](../CREDITS.md) 的要求署名，并遵守：
  - **相同方式共享**：含有借用动画（原样或修改）的动画文件，整个文件以 CC BY-NC-SA 4.0 发布，不适用本模组的「保留所有权利」；
  - **非商业**：本模组不能收费、付费抢先或以借用素材牟利；要改变这一点，先取得 TLM 作者的许可；
  - **登记**：每借用一个动画，在 CREDITS.md 的借物表里记一行（来源动画名、用在哪个文件的哪个动画名、做了什么修改）。
- **只看不借**：参照 TLM 动画的节奏和姿势、自己从头制作的动画不算借用，不需要登记。

## 8. 交付与验证

每个 NPC 交付：

- 仓库外：`<asset>.bbmodel` 源文件、TLM 测试包（`assets/logos_dimension/maid_model.json`、`models/entity/<asset>.json`、`textures/entity/<asset>.png`）。
- 仓库内（接入时）：`assets/logos_dimension/geckolib/models/entity/<asset>.geo.json`、`geckolib/animations/entity/<asset>.animation.json`、`textures/entity/<asset>.png`。
- 中文检查说明：骨骼表、制作尺寸与缩放、哪些检查做过、哪些没做。

检查分层写清楚，不混为一谈：

1. 数据检查：源文件内嵌贴图与导出 PNG 一致，UV 不越界、不重叠，骨骼名唯一且包含第 2 节的必需骨骼；
2. GeckoLib 解析与烘焙：用附属模组实际依赖的 GeckoLib 版本；
3. 离线预览：正、侧、背、3/4 与头部特写；
4. 游戏内：附属模组渲染器、TLM 女仆各自实测。没做过的不能写成已验证。
