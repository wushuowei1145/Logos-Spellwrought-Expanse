# 首版验证记录

目标环境：Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25；Logos 0.5.1，主模组源码基线 `f196960`。当前组合安装包版本为 0.2.0。原炼金维度不包含建筑或 Boss；新增天门云域包含中世纪聚落，不包含 Boss。

## 0.2.0 温带旅途大陆

2026-10-16：21/21 单元测试、18/18 GameTest、普通客户端 8/8 场景与 16/16 检查通过。实际检查北方山带/南方海岸、水系连续、至少 20 种生态覆盖、原版洞窟与峡谷雕刻器接入、无矿物、投影结构加载与真实区块楼板、四季换叶/冬季降雪/温度作用域隔离，并保留原咒械原与流体检查。

最后一次普通客户端包含单纯形地貌、2–4 层住宅、原版动物和村民装饰、果园、改进树冠及植物选择框，截图位于 `docs/screenshots/journey/`。客户端测试时版本元数据为 0.1.4，随后仅升级元数据到 0.2.0；实现相同。八个场景为平原、雪山、海岸、乡村、城镇、植物画廊、秋林和冬林。画廊为专门摆放的模型展示，其他为实际生成地貌，季节场景主动推进时钟并刷新周边材质便于截图。

此前一轮 Complementary Reimagined r5.9.3 实际开启，8/8 场景、16/16 检查通过；这一轮早于最后的单纯形轮廓、楼层变体、植物支撑与村民补充，不当作最终所有改动的光影验证。光影包会使用自己的云高和雾色。Photon 本轮没有重跑。没有声称测得 FPS 提升或完成城市素材的最终美术。

新增植物模型的纹理引用、坐标范围与资源脚本重复生成字节一致通过。法阵放大另以 `group:glass_array` 检查，1/1 场景、3/3 检查通过；流体世界检查覆盖移动、上浮、源头/流动格填方块、汞中毒/虚弱、熔融玻璃点燃伤害与原有流动/装桶。

## 0.1.4 法阵连续过渡

20/20 单元测试通过；普通连续录制 1/1 场景、3/3 检查，237 张真实渲染帧。Complementary r5.9.3 与 Photon v1.3b 各以实际启用的光影运行法阵组，分别 1/1 场景、3/3 检查通过。本次没有声称再次验证全维度或 GameTest；此次修改仅涉及客户端与开发录制场景。录制负载与普通游戏性能分开，细节见 `docs/SMOOTH_ARRAY.md`。

## 0.1.3 移除云层、完整树木与玻璃法阵

2026-10-15：18/18 单元测试、14/14 GameTest 通过。最终普通渲染、实际启用的 Complementary Reimagined r5.9.3 和 Photon Shader v1.3b，各通过 10/10 场景、15/15 检查，每套保留 26 张实机截图。最终小修为针叶冠收尖、叶片镂空和测试相机视野，随后重跑单元与三套客户端；世界检查覆盖的是同一套注册、方向、碰撞与生成规则。

检查展示完整圆冠/针叶/伞冠/垂冠树木、24 种叶块、材料画廊、16 帧连续玻璃法阵、旧云层上方和内部空域，以及实际云域地貌。没有云网格工作线程，发行 JAR 不包含旧云渲染器或密度场。原云层截图呈纯天空颜色为预期结果。没有本附属模型烘焙、缺失纹理、顶点属性或玻璃着色器编译异常。

资源审计覆盖 95 个模型的纹理引用和 UV 范围，生成脚本重复运行字节一致。24 种叶块模型合计面数 912→144，24 种地表 387→144；这些是几何计数，不代表已测得的 FPS 提升比例。没有运行同场景的硬件帧率对照，不声称所有卡顿已消除。新树形仅在新生成区块出现，旧区块更新材质，不自动拆改建筑。

玻璃使用解析菲涅耳、镜面带与边缘色散，不读取场景颜色或复制帧缓存；光影可改变背景和玻璃颜色。截图在 `docs/screenshots/revision-0.1.3/`，组合检查组为 `group:visual_revision`，组合安装包与源码分支为 `release/refinement-0.1.3`。独立主题继续更新原 PR #4、#5、#6。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:visual_revision -PldTestHeadless -PldTestWindow=1600x900
```

## 0.1.2 法阵变化、独立模型与立体云海

2026-10-15 完成三项改动的组合验证。JUnit **18/18**、GameTest **13/13** 通过。最终几何修正后，普通渲染、实际启用的 Complementary Reimagined r5.9.3 和 Photon Shader v1.3b，各通过 **8/8 场景、12/12 检查**，各保留 41 张实机截图。GameTest 后的修正仅涉及客户端云轮廓透明度与地表模型浅刻面的自遮挡；最终三套客户端均包含这两项修正。

客户端场景覆盖三组共 24 种悬叶、材料画廊、32 帧连续法阵变化、云上、穿入/离开云体、剑开天门与魔鲸。没有本附属的模型烘焙失败、缺失纹理或顶点属性异常。80 种方块的几何布局均不同（四阶段材料共输出 95 份模型），资源脚本重复生成一致。云密度场另外检查实际厚度、缺口、负坐标、相邻网格边界与法线；法阵检查短时间可见变化及连续插值。

实机截图见 `docs/screenshots/refinement/`，不是概念图。云采用三维密度场生成的有厚度网格，玩家可穿过；它不是全屏体积光线步进。光影包可覆盖局部雾色、天空和远云，没有对所有硬件帧率或未测试光影作承诺。未加入 Boss、云端平台、矿物或新的实体生物。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:refinement -PldTestHeadless -PldTestWindow=1600x900
```

三个独立 PR 分支分别提供 `group:arcane_animation`、`group:individual_models`、`group:cloud_volume`。`group:refinement` 位于组合版本分支 `release/refinement-0.1.2`，引用三项实现；组合安装包对应此分支。

## 方块模型、天空法阵与放大炼金釜

2026-10-15 完成本轮美术与交互范围检查。JUnit 13/13、GameTest 13/13 通过；炼金釜检查同步验证放大后的釜沿碰撞高度、内部中空、碰外壁/站釜沿不触发、延后传送及冷却。普通客户端、实际启用的 Complementary Reimagined r5.9.3 和 Photon Shader v1.3b 下，`group:model_polish` 各通过 **4/4 场景、6/6 检查**。

四个实机场景包括材料画廊、立体植物画廊、法阵四阶段画面及炼金釜实际重力落入。分别检查模型加载日志，没有本附属的模型烘焙失败、缺失纹理或顶点属性异常；首轮发现的发光壳 UV 越界已修复后重新检查。主模组自带魔杖的粒子纹理警告与本轮无关。截图在 `docs/screenshots/model-polish/`，其中 `astrolabe-pattern.png` 是纹样合成预览，其他为普通渲染实机截图。

两种光影会使用自己的云层与天空色，法阵背景明暗可以不同。没有对本轮未运行的其他客户端场景声称重新验证，也没有在本轮加入新群系、生物、Boss 或入口。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:model_polish -PldTestHeadless -PldTestWindow=1600x900
```

## 天门云域验证

2026-10-14 完成第二维度的独立检查。下列客户端场景只覆盖天门云域，不能当作原炼金维度的再次检查结果。

| 检查 | 结果 | 覆盖内容 |
| --- | --- | --- |
| JUnit | 共 13/13 通过，其中云域新增 5 项 | 24 群系覆盖、负坐标、种子稳定性、高度与悬层边界、洞窟、八个形态家族和独立配色；保留原有 8 项检查 |
| GameTest | 共 13/13 通过，其中云域新增 3 项 | 26 群系注册、空刷怪表、没有入口物品、实际生成区块无矿物、洞窟植物、地心层与聚落门口/地板/屋顶；保留原有 10 项检查 |
| 普通客户端 | 6/6 场景、12/12 检查通过 | 卷曲岩层、悬浮长带、天门与魔鲸、中世纪聚落、倒生根窟、地心环境 |
| Complementary Reimagined r5.9.3 | 6/6 场景、12/12 检查通过 | Iris 1.11.4 与 Sodium 0.9.2 实际启用；六个场景分别留存截图 |
| Photon Shader v1.3b | 6/6 场景、12/12 检查通过 | Iris 1.11.4 与 Sodium 0.9.2 实际启用；六个场景分别留存截图 |

云域实机截图位于 `docs/screenshots/cloudsea/`，概念图另存 `docs/concepts/cloudsea-direction.png`。当前云团、天空鲸、程序式聚落与植物均为首版美术；它们没有达到概念图的细节水平。魔鲸是天空几何现象，不是生物实体；阴影为地面近似投影。光影包自己的云层和色调可能改变天门背景，没有声称所有光影包都具有相同观感。没有新增矿物、生物实体、Boss、战利品或维度入口；聚落暂不提供 `/locate structure`。

复现云域客户端检查：

```text
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:cloudsea -PldTestHeadless -PldTestWindow=1280x720
```

## 已完成

| 检查 | 结果 | 覆盖内容 |
| --- | --- | --- |
| JUnit | 8/8 通过 | 20,000 格重复布局、区域比例和顺序、负坐标、种子变化、空岛平台、周期、雾区边界 |
| GameTest | 10/10 通过 | 注册兼容、平台留空、禁刷怪、自有材料、独立时间、往返传送、天然材料换相、流体流动与装桶、各岛 Boss 状态持久化、炼金釜碰撞/返回/冷却 |
| 普通客户端 | 8/8 场景、19/19 检查通过 | 四区、中心空岛、倒悬蒸馏瓶、四阶段天空、岛下影雾及解除 |
| Complementary Reimagined r5.9.3 | 8/8 场景、19/19 检查通过 | 实际启用 Iris 1.11.4、Sodium 0.9.2；天空星盘和影子可见 |
| Photon Shader v1.3b | 8/8 场景、19/19 检查通过 | 实际启用 Iris 1.11.4、Sodium 0.9.2；天空与影雾场景完成 |

客户端场景使用 LDLib2 的实际开发客户端；截图不是概念图。首版截图位于 `docs/screenshots/`。完整报告由检查生成在 `build/ldlib2-uitest/report.json`，世界检查报告在 `build/gametest-results.xml`。

新增的 `crucible_cauldron` 场景另外在普通渲染、Complementary Reimagined r5.9.3、Photon Shader v1.3b 下各通过 1/1 场景、2/2 检查：先实际站在釜沿，再让生存模式玩家从釜口上方自由落入，确认客户端与服务端都抵达新维度。两种光影实际启用，均保留模型、液面与传送；GameTest 还检查了液面与釜沿的碰撞差异、延后传送、安全返回及冷却期间禁止重复触发。

## 光影限制

Complementary r5.9.3 会输出来自光影包的 `BIOME_SULFUR_CAVES` / `inSulfurCaves` 警告，当前 26.1.2 中没有对应原版生物群系；测试期间光影保持开启。附属 Mod 没有为此引入新版本原版硫磺内容。

光影包可能覆盖游戏雾色，因此岛下的背景雾明暗与普通渲染不同。自定义影子、雾层和天空使用 Logos 的 `ShaderPackOverlay`，完整提交 UV、颜色、光照、法线与 overlay；未验证的光影包不保证相同观感。

## 后续接入

尚无实际 Boss；通过玩家管理员命令 `/alchemy boss defeated` 验证解雾，`/alchemy boss reset` 恢复。实际 Boss 接入点为 `LOGOS_DIMENSION_ISLAND_BOSS_HOOK` 和 `logos_dimension:island_bosses` 标签。镜面汞采用动画贴图，尚无实时反射；元素风暴、完整转化玩法、探索奖励后续制作。

## 复现

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:logos_dimension -PldTestHeadless -PldTestWindow=1280x720
```

光影测试使用 `-PldTestSeedDir=/absolute/path/seed`；seed 放入所需 `mods/`、`shaderpacks/` 和 `config/iris.properties`。它只复制到每次清空的 `runs/uiTest` 开发目录，依赖和光影包不提交到源码仓库。
