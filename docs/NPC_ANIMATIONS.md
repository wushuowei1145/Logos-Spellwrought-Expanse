# NPC 动画制作规范

适用于附属模组的人形 NPC。模型部分见 [NPC 模型美术规范](NPC_MODELS.md)。

NPC 的动画驱动 `dev.logos.dimension.npc.NpcAnimations` 沿用车万女仆（TLM）GeckoLib 女仆的控制器结构、动画名和优先级。同一份动画文件在附属模组里由 NPC 驱动播放，放进 TLM 模型包后由 TLM 驱动，效果一致。

对照的 TLM 源码（1.21 分支）是 `client/animation/gecko/AnimationManager`、`AnimationRegister`、`condition/*` 与 `client/entity/GeckoMaidEntity`。本仓库只参照它的命名和规则，不复制 TLM 的代码或动画文件（TLM 美术为 CC BY-NC-SA 4.0）。

## 1. 文件与命名

- 每个 NPC 一个动画文件：`assets/logos_dimension/geckolib/animations/entity/<名>.animation.json`，`format_version` 为 `1.8.0`，与模型同名。
- **动画名直接用 TLM 的名字**（`idle`、`walk` 等），不加 `animation.<asset>.` 前缀。这是 Logos「`animation.<asset>.<action>`」命名规则的例外，原因同骨骼命名：换了名字，TLM 就认不出来。
- Blockbench 里用「动画」模式制作，动画名就填上面的名字。
- 实体实现 `NpcAnimated` 接口，在 `registerControllers` 里调用 `NpcAnimations.registerControllers(this, controllers)`；渲染用 `NpcRenderer`。

## 2. 控制器

按注册顺序排列；同一根骨骼上，后注册的控制器覆盖先注册的。

| 控制器 | 播放什么 | 过渡 |
|---|---|---|
| `pre_parallel_0..7_controller` | 动画 `pre_parallel0`–`pre_parallel7`，有就一直循环 | 0 |
| `main` | 主状态（第 3 节） | 2 tick |
| `hold_offhand` / `hold_mainhand` | 持物（第 4 节） | 0 |
| `swing` | 挥手，每次挥手从头播一遍 | 2 tick |
| `use` | 使用物品（吃、拉弓、举盾……） | 2 tick |
| `misc` | 剧情动作，由 NPC 自己给出名字（`NpcAnimated.npcMiscAnimation()`） | 2 tick |
| `parallel_0..7_controller` | 动画 `parallel0`–`parallel7`，有就一直循环 | 0 |

- **只播放动画文件里有的名字。** GeckoLib 遇到缺失的动画会每帧报错，所以驱动先查动画文件。主状态缺失时顺延到下一个成立的状态：比如没有 `run`，奔跑时播 `walk`；没有 `jump`，就落到 `walk` 或 `idle`。
- **不包含的部分**：TLM 女仆专属的棋盘、椅子游戏、乞求、拾雪球、魔法、枪械、盔甲、乘客控制器不做。TLM / YSM 的专用 Molang 变量（如 `is_begging`、`head_yaw`）没有提供，只能用 GeckoLib 自带的 Molang 查询。

## 3. 主状态（`main`）

按优先级从上到下检查，第一个条件成立、且动画文件里存在的胜出。

| 优先级 | 动画名 | 条件 | 循环 |
|---|---|---|---|
| 最高 | `death` | 死亡中 | 播完停在最后一帧 |
| 最高 | `sleep` | 睡觉姿势 | 循环 |
| 最高 | `swim` | 游泳姿势（水中冲刺） | 循环 |
| 最高 | `ladder_up` / `ladder_stillness` / `ladder_down` | 在梯子上，向上 / 不动 / 向下 | 循环 |
| 高 | `boat` | 坐船 | 循环 |
| 高 | `chair` | 骑乘其他实体 | 循环 |
| 高 | `sit` | 坐下（`NpcAnimated.isNpcSitting()`） | 循环 |
| 普通 | `swim_stand` | 站在水里 | 循环 |
| 普通 | `attacked` | 受伤（受击计时 > 0） | 播一次 |
| 普通 | `jump` | 不在地面，也不在水里 | 循环 |
| 低 | `run` | 在地面上冲刺 | 循环 |
| 低 | `walk` | 在地面上，肢体摆动速度 > 0.05 | 循环 |
| 最低 | `idle` | 总是成立 | 循环 |

同级按表中顺序：水中受伤显示 `swim_stand`，与 TLM 相同。

**至少要有 `idle` 和 `walk`**；`attacked`、`run`、`jump`、`sit` 建议都做；`death`、`sleep`、`swim`、梯子、船按 NPC 实际会遇到的情况做。

## 4. 手部条件动画

持物、挥手、使用物品都可以按手上的物品换动画。名字写法与 TLM 一致，按下面的顺序匹配，第一个存在的胜出：

| 写法 | 例子 | 匹配 |
|---|---|---|
| `前缀$物品ID` | `hold_mainhand$minecraft:iron_sword` | 精确物品 |
| `前缀#物品标签` | `hold_mainhand#minecraft:swords` | 物品标签 |
| `前缀:大类` | `hold_mainhand:sword` | 大类：`sword`、`axe`、`pickaxe`、`shovel`、`hoe`、`shield`、`throwable_potion` |
| `前缀:使用动画类型` | `hold_mainhand:bow` | 物品的使用动画：`eat`、`drink`、`block`、`bow`、`trident`、`crossbow`、`spyglass`、`toot_horn`、`brush`、`bundle`、`spear` |

- **前缀**：持物 `hold_mainhand` / `hold_offhand`，挥手 `swing` / `swing_offhand`，使用 `use_mainhand` / `use_offhand`。
- **默认动画**：没有条件名匹配时，挥手播 `swing_hand` / `swing_offhand`，使用播 `use_mainhand` / `use_offhand`，持物不播。
- **特殊名**：空手持物用 `hold_mainhand:empty`；上了弦的弩优先用 `hold_mainhand:charged_crossbow`。
- **常见错误**：物品 ID 要用 `$`。`hold_mainhand:minecraft:bow` 不会生效，`:` 后面只能接大类或使用动画类型。
- **`use_*` 的条件写法**：照持物的规则做的；TLM 的对应代码（`ConditionalUse`）这次没有核对。

## 5. 常驻叠加层

- **用途**：`pre_parallel0`–`7` 和 `parallel0`–`7` 有就一直循环，适合与状态无关的小动作，例如尾巴轻摆、耳朵偶尔抖动、呆毛晃动、发结飘带摆动、呼吸。
- **叠加顺序**：`pre_parallel*` 在主状态之前，会被主状态覆盖同一骨骼；`parallel*` 在最后，会覆盖主状态。
- **用哪一组**：只想加点小动作、又不想抢走主状态对同一骨骼的控制时，用 `pre_parallel*`；需要压过主状态的，用 `parallel*`。
- **避免冲突**：同一根骨骼不要同时在叠加层和主状态里做大幅动作。

## 6. 怎样做得好看

- **静止姿势要先正确**：模型的静止姿势本身必须好看、正确，比如百褶裙的褶角、头发的弯曲。动画只在上面做变化，不要用动画去「摆正」模型。
- **视线由渲染器负责**：头部跟随视线由 `NpcRenderer` 在动画之上叠加，`idle` 里不要让 `Head` 大幅转动，否则会和视线打架。
- **循环首尾一致**：循环动画的首尾关键帧必须完全相同，插值用平滑曲线（catmullrom），避免接缝处抽一下。
- **时长与节奏**：
  - `idle` 3–4 秒，呼吸的起伏 1–2°；
  - `walk` 一步约 0.5 秒（一个循环约 1 秒），要和实际移动速度对得上，看起来不打滑；
  - `run` 比 `walk` 快、身体前倾，手臂摆幅更大；
  - `attacked` 0.3–0.5 秒，快速后仰再恢复。
- **重心与跟随**：
  - 走、跑时 `AllBody` 或 `UpBody` 上下起伏，落脚那一帧略低；
  - 头发、裙褶、尾巴、发饰比身体晚 1–3 帧跟随（次级运动），幅度逐级放大；
  - 可爱的角色动作略夸张、有弹性，但不要抖。
- **少动根骨骼**：`Root` 不要做大位移，实际移动交给实体，否则碰撞箱和画面会对不上。
- **先验证方向**：Bedrock 导出时 X、Y 旋转取反。先用一根骨骼做一个关键帧，确认方向，再批量做。
- **分骨骼的部件**：头发每缕、褶片每片都是独立骨骼，可以只给一部分加摆动，不必全部都动。以后的物理效果也在这些骨骼上叠加。
- **表情**：用 `Eyes` / `Eyelid` / `Mouth` 下的骨骼显示、隐藏切换（如眨眼），可以放在常驻叠加层里循环。
- **光照**：发光部件用 `ysmGlow` 前缀骨骼（规范第 5 节）；动画里缩放或隐藏它们，发光也跟着变。

## 7. 交付与验证

- 动画文件与模型同目录结构交付：`geckolib/animations/entity/<名>.animation.json`。
- **检查**：
  - 动画名都在第 3、4、5 节的表里，或者是 NPC 自己用于 `misc` 的名字；
  - 循环动画首尾一致；
  - 用附属模组实际依赖的 GeckoLib 解析、烘焙通过。
- **游戏内**：附属模组里看主状态切换（站、走、跑、跳、受伤、坐）和手部动作；放进 TLM 包里再看一次，确认效果一致。没测过的项目，在说明里写明未验证。
