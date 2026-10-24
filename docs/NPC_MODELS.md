# NPC 模型美术规范

适用于附属模组中所有人形 NPC（剧情角色、商人、同伴等）。总原则继承 Logos `CONTRIBUTING.md` 第 6 节「美术资产规范」；本页只写 NPC 的补充和例外。两边冲突时，NPC 以本页为准。

NPC 采用车万女仆（Touhou Little Maid，下称 TLM）的 GeckoLib 女仆模型规范，目标有两个：

1. 在附属模组里用与 TLM / YSM 相同的渲染风格显示 NPC；
2. 同一份几何和贴图不改动就能放进 TLM 的自定义模型包，作为女仆模型使用。

## 1. 格式

- 源文件：Blockbench **Bedrock 实体**工程（`model_format: bedrock`，不需要插件），每面独立 UV（不用 box UV）。`.bbmodel` 不进仓库。
- 几何：Bedrock geometry `format_version 1.12.0`，`geometry.<asset>`。附属模组用 GeckoLib 读取；TLM 自定义包设置 `"is_gecko": true` 后读取同一个文件。
- 动画：GeckoLib / Bedrock 动画 JSON（`format_version 1.8.0`）。
- 贴图：单张 PNG，宽高为 2 的幂。只用完全不透明或完全透明的像素（alpha 0 / 255），发尾、领口等镂空靠 alpha 裁切，不做半透明。

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

挂点（空骨骼，只读枢轴）：

| 骨骼 | 父骨骼 | 用途 |
|---|---|---|
| `LeftHandLocator` / `RightHandLocator` | 对应手 | 手持物品位置 |
| `BackpackLocator` | `UpperBody` | 背包 / 背后道具 |
| `cast_point` | `UpperBody` | Logos 施法点，见主模组规范 6.3 |

## 3. 尺寸与比例

- 按常规女仆模型的 **2 倍**制作：脚底 Y=0，正面朝 -Z。约 3 头身（不含兽耳，以带头发的头部高度计），制作高度约 50–60 单位。
- 游戏内整体缩放 **0.5**。附属模组在渲染器里缩放；TLM 包写 `"render_entity_scale": 0.5`。TLM 限制在 0.2–2.0，所以制作尺寸最多放大到 5 倍，不能更大。
- 缩放只改显示。碰撞箱、眼高、持物位置在接入时另外校准。

## 4. 贴图

- 2 倍制作下用 **2 纹素 / 模型单位**，相当于常规尺寸的 4 纹素 / 单位，接近 TLM / YSM 常见密度，保持 MC 像素颗粒感。全身纹素密度保持一致；脸部也不单独加密。
- 每面独立 UV，岛之间留 1 像素并外扩边缘颜色，避免采样串色。
- 明暗画在贴图里：发束分缕、深浅交替、发根到发梢渐暗、关节处略深。不要把某个方向的光照画死，游戏会再加实体光照。
- 表情只画在 `Head` 正面（或之后的表情骨骼上）。

## 5. 渲染风格

「车万女仆 / YSM 风格」在附属模组里指下面这套组合，不是自定义着色器：

- 渲染类型 `entityCutoutNoCull`：alpha 裁切，不剔除背面，薄发片、裙摆内侧两面都能看到；
- 原版实体光照：两盏固定方向光加环境光，正面亮、侧面暗，不另加描边或卡通着色；
- 使用世界光照（方块光与天空光），没有全亮；
- 每帧按 GeckoLib 动画姿势绘制完整模型。

需要发光（眼睛、魔法饰品）时另做发光贴图层，并在说明里写明。只把颜色画亮不等于运行时发光。渲染器属于代码改动，按主模组规范另开 PR，不和美术 PR 混在一起。

## 6. 表情与动画

- 先交付「常态」表情并通过模型验收，再添加其他表情。
- 其他表情、眨眼按 TLM 的结构在 `Head` 下加 `Eyes` / `Eyelid` / `Mouth` 子骨骼，用显示/隐藏切换。
- 附属模组的动画名用 `animation.<asset>.<action>`，例如 `animation.elodie.idle`。
- 放进 TLM 包时，TLM 会把它自带的默认女仆动画（`idle`、`walk`、`run`、`sit`、`sleep`、`swim`、`attacked`、`use_mainhand` 等）合并到模型里，只补模型自己没定义的名字。所以 TLM 包可以不带动画；要覆盖哪个动作，就用 TLM 的动画名单独写。

## 7. 授权边界

TLM 的代码为 MIT，模型、贴图、动画等美术资源为 CC BY-NC-SA 4.0。本规范只参照它公开的骨骼命名和包结构，**不复制** TLM 的任何模型、贴图或动画文件进本仓库。NPC 的美术全部自行绘制（附属模组要求：代码绘制，不使用图像生成服务）。

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
