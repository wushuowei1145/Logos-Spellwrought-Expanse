# 移植到 1.21.1 的笔记

Logos 本体已经有 1.21.1 + NeoForge 21.1 的版本（主模组仓库的 `port/1.21.1` 分支）。这份文档记下那次移植的做法、踩过的坑和验证方法，
并按本附属 `main`（`3ce82584`）粗查了一遍会受影响的地方，给以后移植附属用。

本体分支里的 [`docs/PORT_1.21.1.md`](https://github.com/MagicFoxDemon/MinecraftLogosMod/blob/port/1.21.1/docs/PORT_1.21.1.md)
是那边的约定和行为差异清单，以它为准；这里不重复它的每一条，只写对附属有用的部分。

## 依赖对照

| | 26.1（现在） | 1.21.1 |
|---|---|---|
| Minecraft / NeoForge | 26.1.2 / 26.1.2.112 | 1.21.1 / 21.1.267 |
| Java | 25 | 21 |
| Logos | 0.7.x | `port/1.21.1` 打出的 jar（同版本号，文件名带 `mc1.21.1`） |
| LDLib2 / Photon / KilaGraph | 26.1.2.42 / 26.1.2.3 / 26.1.0.16 | 2.2.46.a / 2.2.11 / 21.1.0.16 |
| GeckoLib | 5.5.2（包名 `com.geckolib`） | 4.9.3（包名 `software.bernie.geckolib`） |
| 车万女仆 | 2.0.0 | 1.5.3（`neoforge.mods.toml` 的 `versionRange` 要改成 `[1.5.3,)`，否则装着 1.5.3 也会被判成版本不符） |
| Starbloom Orchestra | 0.1.0 | **还没有 1.21.1 版**，附属要先等它移植，或者在 1.21.1 上把它改成可选依赖 |

## 本体是怎么做的

1. **先量一遍再动手。** 搭一个 1.21.1 的空工程，把源码原样放进去编译。javac 加上
   `-Xmaxerrs 100000 -XDshould-stop.ifError=FLOW`，一次报出全部错误；Gradle 要 `-Dorg.gradle.jvmargs=-Xmx4G`，1G 会内存不够。
   本体第一次是 6079 个错误、530 个文件，按类别归档后估工作量。这个数只是下限：javac 认不出一个类型后，就不再检查对它的方法调用。
   mixin 注入点和 JSON 格式问题编译器完全看不到，要另外查。
2. **用 `--release 21` 再编译一遍。** 错误数没变，说明没用到 Java 22+ 的语法和 API。附属也粗查过，没有 `_` 这类未命名变量。
3. **替身层，不改玩法代码。** 1.21.1 缺的 26.1 API，在本体的 `dev.logos.port`（两端）和 `dev.logos.client.port`（渲染）里各写一个同名的替身。
   玩法代码只换 import，`main` 的改动搬过来时冲突最少。替身只实现了 Logos 用到的方法。
4. **资源文件和 `main` 保持同一份，差异放到构建时改写。** 物品定义（`items/*.json`）、模型、GeckoLib 资源、战利品修改器都不分叉，
   由 Gradle 任务在 `processResources` 里转成 1.21.1 的格式。这样以后从 `main` 搬资源改动，直接复制就行。
5. **跟进 `main` 用 `git cherry-pick -x`。** 一个 PR 一个提交，提交里留着来源，方便追。冲突里被移植改写过的代码先取移植版，再手工补上 `main` 的意图。

### 附属可以直接用本体的替身和构建任务

附属在 1.21.1 上对着本体的移植版 jar 编译，所以 `dev.logos.port.*`、`dev.logos.client.port.*` 本来就在类路径上：

- `SubmitNodeCollector`、`RenderPipeline`、`RenderSetup`、`RenderTypes`、`GuiGraphicsExtractor`、`ValueInput`/`ValueOutput`、`SavedDataType`
  等可以直接 import，附属不用再写一份。替身缺方法时，到本体的 `port/1.21.1` 里补上，不要在附属里另写一套。
- 这样附属就绑定了替身的签名，以后本体改替身，附属可能编译不过。开工前先和本体维护者说好，把这两个包当作对附属公开的接口。
- 构建改写可以照搬：本体 `build.gradle` 里的 `generateItemModels`、`generateGeckoLibAssets`、`generateLootModifierList`，
  以及 `gradle/port-models.gradle`（旋转、渲染层、发光、特殊物品四种模型改写）。命名空间换成 `logos_dimension`。

## 踩过的坑

按「看到的现象 → 原因 → 修法」写。前几条都是只在游戏里才会发现的，编译和服务端测试查不出来。

### 注册与事件

- **监听器静默不生效。** 1.21.1 的 `@EventBusSubscriber` 默认挂在游戏总线上，26.1 会按事件类型自动分。
  监听模组总线事件（注册、`FMLClientSetupEvent`、`RegisterGuiLayersEvent`、`EntityRenderersEvent` 等）要写 `bus = EventBusSubscriber.Bus.MOD`。
  一个类里两种总线的方法都有时，把模组总线的方法挪进嵌套类。漏写不会报错。附属有 82 个文件用了 `@EventBusSubscriber`，要逐个看。
- `neoforge.mods.toml` 开头要有 `modLoader` 和 `loaderVersion`（26.1 不需要，1.21.1 缺了加载不了）。

### 方块与模型

- **方块放下后整块看不见，物品栏里却正常。** 1.21.1 的 `BaseEntityBlock.getRenderShape` 默认返回 `INVISIBLE`，26.1 默认画模型。
  继承它的方块要覆写成 `RenderShape.MODEL`。本体的培养罐和魔力储存罐就漏了，玩家放下后才发现。附属有 1 个方块继承它（只实现 `EntityBlock` 的方块不受影响，`Block` 默认画模型）。
- **手持物品的 3D 模型消失，只剩扁平贴图或什么都没有。** 1.21.1 中，父模型链最终是 `builtin/generated` 的物品模型，
  只会按 `layer0` 生成扁平图标，`elements` 被丢掉。带元素又以 `item/handheld`、`item/generated` 为父的模型，要换一个不带 generated 根的父模型，
  显示参数照抄（本体是 `models/item/port/handheld_elements.json`）。
- **整个模型加载失败，显示紫黑方块。** 26.1 的元素旋转可以多轴（`"rotation": {"x": …, "y": …, "z": …}`）或任意角度，
  1.21.1 只认单轴且角度为 0、±22.5、±45。本体的转换器把旋转拆成「90° 的倍数」和「剩下的合法单轴旋转」两部分：前一部分直接烘进方块的坐标和各面，
  再按两个版本的烘焙逐顶点比较位置和 uv，对不上就让构建失败。附属有 23 个模型用了多轴旋转。
- **贴图透明的地方露出一圈底色。** 26.1 按每个面盖住的贴图像素自动选渲染层（有半透明像素就走半透明，有全透明像素就走镂空），
  1.21.1 只看模型的 `render_type`。不写就按实心画，透明像素显示的是贴图里存着的颜色。本体在构建时按 26.1 的规则算好 `render_type` 写进去。
  1.21.1 的 `render_type` 只能整个模型统一设，所以混合的模型取最透的那一层；重叠的半透明物体偶尔会排序不对。
- **发光部件不亮。** 1.21.1 不读元素的 `light_emission`，要改写成 NeoForge 的 `"neoforge_data": {"block_light": n}`，效果相同。
  **附属有 332 个模型用了 `light_emission`**，这一条影响最大。
- 26.1 的贴图对象 `{"sprite": …, "force_translucent": true}` 1.21.1 不认，要写回贴图名，半透明交给上一条的渲染层。附属目前没用到。
- 渲染类型改名了：26.1 的 `entityCutout` 是 1.21.1 的 `entityCutoutNoCull`（两面都画），26.1 的 `entityCutoutCull` 是 1.21.1 的 `entityCutout`。
  NPC 模型约定写的 entityCutout（不剔除背面），在 1.21.1 上要用 `entityCutoutNoCull`。

### 物品

- **物品完全透明。** 1.21.1 的物品着色是 ARGB，会读 alpha。26.1 的常量着色是 `0xRRGGBB`，没有 alpha 字节，搬过去就变成完全透明。
  本体在读着色时一律补成不透明（`FastColor.ARGB32.opaque`）。
- **用特殊渲染器画的物品（GeckoLib 物品）显示成扁平图标或什么都没有。** 1.21.1 只有父模型链以 `builtin/entity` 结尾的模型才交给自定义渲染器，
  `items/*.json` 里 `"minecraft:special"` 的 base 模型要改用这个父模型。附属有 1 个。
- `items/*.json`（附属 336 个）是 1.21.4 才有的。本体用 `generateItemModels` 在构建时生成 1.21.1 的 `models/item/<id>.json`，
  着色在运行时读同一份文件。

### GeckoLib

- **创造物品栏一打开就崩溃。** GeckoLib 4 从 `geo/`、`animations/` 读文件，GeckoLib 5 从 `geckolib/models/`、`geckolib/animations/` 读。
  找不到模型时，物品渲染器在绘制物品栏图标时抛异常。本体构建时用 `generateGeckoLibAssets` 复制一份到旧路径，源文件不动。
- GeckoLib 5 是渲染状态式的（`GeoRenderState`、`DataTicket`），4 直接在渲染器里读实体。附属有 19 个文件用 GeckoLib、7 个用 `DataTicket`。
  改骨骼放在渲染器的 `renderRecursively` 或模型的 `setCustomAnimations`（动画之后）。
- GeckoLib 4 的烘焙模型在同一资源的所有实例之间共享，骨骼状态会留下来。改了哪块骨骼，每次画之前都要重新设，否则这只 NPC 会带着上一只的姿势。
- **不要凭印象加补偿变换。** 本体曾以为 GeckoLib 4 和 5 的变换顺序不同，多做了一次位移，结果炼金术桌物品歪了。实际上两边顺序一样，
  姿势照搬 26.1 就对了。拿不准时，在两个版本上截同一个场景对比。

### 数据包

- 配方原料要写成 `{"item": …}` / `{"tag": …}`，26.1 的字符串写法 1.21.1 不认。
- 标签里有 1.21.1 不存在的原版 ID 时，整个标签都读不出来。写成 `{"id": …, "required": false}`。
- 1.21.1 没有数据驱动的村民交易（`villager_trade`、`trade_set`），本体用 `DataTrades` 在代码里读同一份文件。
- 战利品修改器要有 `neoforge:global_loot_modifiers` 名单，本体在构建时生成。

### 世界内测试

- GameTest 框架在 1.21.5 重做过：1.21.1 没有 `Registries.TEST_FUNCTION`、`TestEnvironmentDefinition`，要改成 `@GameTestGenerator` 返回测试列表，
  每个测试必须给一个结构（本体用一个 32×16×40 的空气结构，开着 sky access，否则屏障顶盖住整个结构，雨、阳光、落雷都没有）。附属有 36 个文件涉及测试注册。
- 测试结构四周有屏障墙：相对坐标不能小于 0（26.1 每个测试四周留 16 格）。
- 原版的 `makeMockServerPlayerInLevel` 没有网络频道，同步附件时会抛异常；1.21.1 的玩家进服后有 3 秒无敌。本体用 `MockPlayers` 处理这两点。
- 模拟玩家出生在世界出生点，那里可能飘着别的测试留下的游离魔力，会把魔力池补满。测魔力数值时先把玩家挪到测试结构中间。

### 构建与工具

- **改了 `processResources` 里的改写逻辑，输出却没变。** Gradle 看不到闭包内部的变化，会判定任务是最新的。
  本体用 `inputs.property("portModelRewrites", N)` 当版本号，改了逻辑就把 N 加一。临时可以 `--rerun-tasks`。
- 单元测试读编译好的类时用 ASM：Java 21 没有 `java.lang.classfile`。
- `org.jspecify` 的 `@Nullable` 是 TYPE_USE，换成 `javax.annotation.Nullable` 后，`X.@Nullable Y` 要写成 `@Nullable X.Y`。附属有 118 个文件用 jspecify。
- 批量替换不要扫进替身包。本体有一次批量替换扫进了 `dev.logos.port`，替身自己的说明（写着它替的是 26.1 的哪个类）被改坏了。
- mixin：编译器只能看出目标类不存在，看不出注入的方法改了名或签名。要逐个对照 1.21.1 的反编译源码（`build/moddev/artifacts/neoforge-21.1.267-sources.jar`）。
  玩法 mixin 注入失败会让游戏启动不了，画面 mixin 只是效果消失，后者更容易漏。

## 附属要额外注意的地方

本体没有自定义维度，下面这些是附属才有的，移植时工作量可能最大。都在附属 `main`（`3ce82584`）上用 grep 粗查，数量是文件数。

### 维度与世界生成

- **维度类型**（`dimension_type/` 2 个）：26.1 用 `attributes`（雾色、天空色、云高、音乐、床的规则……）、`timelines`、`default_clock`。
  1.21.1 没有这些字段，反而必须有 `ultrawarm`、`natural`、`bed_works`、`piglin_safe`、`has_raids`，`effects` 指向一个维度特效 ID。
  雾色、天空色、云高这些要改用 `DimensionSpecialEffects` 或渲染事件在代码里给。
- **世界时钟与时间线**（`world_clock/`、`timeline/` 各 2 个，Java 里 7 个文件用 `WorldClock` 一类、25 个提到 timeline）：1.21.1 没有。
  本体的时间加速改成每刻直接推进日内时间并发给这个维度的玩家。附属的炼金四相（黑化、白化、黄化、赤化）、按时间变的天空颜色要改成代码驱动。
- **群系**（42 个）：1.21.1 的 `effects` 里 `fog_color`、`water_color`、`water_fog_color`、`sky_color` 都是必填，**而且是整数，不是 `"#rrggbb"` 字符串**。
  附属的群系只写了 `water_color` 等几个，还是字符串，在 1.21.1 上全部读不出来。可以照本体的做法在构建时改写，缺的颜色从维度类型的 `attributes` 取。
  有 2 个群系带 `attributes`，同样要转。
- **1.21.1 没有的原版方块**：世界生成里用到了 `minecraft:leaf_litter`（1.21.5 才有，14 处）。读不到方块会让引用它的地物、进而整个群系加载失败。
  换成别的方块，或者在 1.21.1 上去掉。
- **结构 NBT**（1454 个）：文件按 26.1 的数据版本保存。1.21.1 读更新版本的结构不会降级转换，结构里 1.21.1 没有的方块会丢失。
  移植前写个小程序扫一遍所有结构的调色板，列出 1.21.1 不存在的方块 ID。
- 世界生成的其余部分（`noise_settings`、`density_function`、`configured_feature`、`template_pool`……共 1581 个文件）格式大多相同，
  但没有逐个核对过，要在 1.21.1 上真的进一次维度看。

### 代码

| 26.1 的东西 | 文件数 | 本体的处理 |
|---|---:|---|
| `SubmitNodeCollector` / `submitCustomGeometry` | 61 | 用替身 `client.port.SubmitNodeCollector`，底层画进 1.21.1 的 buffer source |
| `*RenderState`（含 `EntityRenderState` 21 个、`BlockEntityRenderState` 4 个） | 59 | 实体和方块实体渲染器改回 1.21.1 的 `render(...)` 直接画；人形姿势有替身 `HumanoidRenderState` |
| `RenderPipeline` / `RenderTypes` | 16 / 10 | 替身编成 1.21.1 的 `RenderType` + `ShaderInstance` |
| shader 用 `dynamictransforms.glsl` 等 UBO 头文件 | 4 | 本体的 `ShaderPrograms` 为 Logos 自己的程序生成这些头文件，shader 文件不用改。附属的程序要接进同一套，或者自己改头部 |
| `GuiGraphicsExtractor` / `setTooltipForNextFrame` | 16 | 替身；悬停提示改用 `renderTooltip` |
| `ValueInput` / `ValueOutput` | 20 | 替身，`saveAdditional(CompoundTag, Provider)` 里包一层 |
| `SavedDataType` | 11 | 替身 |
| `Identifier` | 98 | 换成 `ResourceLocation`，可以脚本批量做 |
| `@EventBusSubscriber` | 82 | 见上文「监听器静默不生效」 |
| mixin（46 个） | — | 目标里有 26.1 才有的类：`ItemModelResolver`、`ItemStackRenderState`、`RenderSetup`（本体改成挂在 `ItemRenderer.render`）；其余的也要逐个对照注入方法 |
| `META-INF/enumextensions.json` | 1 | 本体的 `HumanoidModel$ArmPose` 构造器在 1.21.1 少一个 boolean，附属的条目也要核对签名 |
| 车万女仆 / Starbloom Orchestra | 1 / 3 | 女仆在 1.5.3 上只差几个原版 API（见本体文档的依赖表）；乐团要等它自己移植 |
| `Consumable` 组件 | 4 | 本体用食物属性加 `DrinkItem` 替身 |

## 怎么验证

本体每一层分开做、分开报：

1. **编译 + 单元测试**：`./gradlew test`。
2. **世界内测试**：`./gradlew runGameTestServer`，装女仆再跑一遍（本体 1.21.1 上不带女仆 481 个，带女仆 498 个）。区块和实体 tick 时序的偶发失败在两个版本上都有，
   但要确认是同一个测试在重跑后通过，不能靠多跑几次蒙过去。
3. **独立核对构建改写**：另找一个人（或另一个 agent）不看转换器代码，自己写核对程序，逐顶点比较两个版本烘出来的模型（本体 269 个模型、4521 个元素，0 处不一致），
   再故意改坏几处，确认核对程序抓得到。
4. **两个版本截同一个场景对比**：同一个无窗口客户端场景在 26.1 和 1.21.1 上各跑一次，逐张对比位置、姿势、贴图方向、透明边缘。
   创造物品栏场景能一次查出模型加载失败和物品栏崩溃。
5. **进游戏手动看**。前几层都过了以后，这一层仍然查出过问题：方块要**放进世界**看，不能只看物品栏（培养罐、储存罐就是物品栏正常、放下看不见）；
   发光要在暗处看；半透明的东西要重叠摆着看。

本体的五处显示问题（法杖消失、创造栏崩溃、编纂台加载失败、贤者模板透明、方块放下看不见）都是第 5 层才发现的，前四层是事后补上去防止再犯的。
附属移植时，建议一开始就把第 4 层的对比场景准备好。

## 建议顺序

1. 等本体的移植版稳定，和本体维护者确认替身包可以给附属用；确认 Starbloom Orchestra 的打算。
2. 换依赖，脚本做机械替换（`Identifier`、`Nullable`、包路径），用本体的替身让逻辑层先编译通过。
3. 修 `@EventBusSubscriber` 总线、`BaseEntityBlock` 渲染形状这类编译查不出的问题。
4. 维度和世界生成：维度类型、时钟与时间线、群系颜色、`leaf_litter`、结构扫描。先让两个维度能进去。
5. GameTest 注册，跑通服务端测试。
6. 渲染：实体渲染器（GeckoLib 4）、特效、mixin、构建时模型改写（照搬本体的任务）。
7. 两个版本对比截图，再进游戏手动看。
