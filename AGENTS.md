# LogosDimensionAddon

先阅读 README.md 和 VERIFICATION.md。本工程是 Logos 的独立附属 Mod；主模组源码不属于这个仓库的修改范围。

- 新改动在分支完成，一个 PR 只解决一个主题。提交标题用英文，说明用中文。
- PR 默认以 main 为目标；只有明确说明的依赖审查才指向功能分支，依赖功能分支上的更新必须另行整合到 main，不能把功能分支内合并称为主分支已更新。
- 美术素材使用代码绘制，不使用图像生成服务；代码注释与文档字符串使用中文。
- NPC 模型按 [docs/NPC_MODELS.md](docs/NPC_MODELS.md)：车万女仆 GeckoLib 骨架命名、2 倍制作、entityCutout（不剔除背面）加原版实体光照，发光部件用 `ysmGlow` 前缀骨骼；制作做法见 [docs/NPC_MODELING_GUIDE.md](docs/NPC_MODELING_GUIDE.md)；普通 NPC 的随机外观按 [docs/NPC_APPEARANCE.md](docs/NPC_APPEARANCE.md)，幼年、伴侣与繁殖按 [docs/NPC_POPULATION.md](docs/NPC_POPULATION.md)，施法按 [docs/NPC_MAGIC.md](docs/NPC_MAGIC.md)；借用车万女仆的动画按 [CREDITS.md](CREDITS.md) 署名并登记。
- 灵猫居民（猫耳猫尾的人形；毛色是同一套 UV 的另一张贴图，不进零件表；林隙村镇里由 `MysticalCatResidents` 补人）按 [docs/MYSTICAL_CAT.md](docs/MYSTICAL_CAT.md)。
- 战斗动作（玩家与 NPC 共用的连招）按 [docs/COMBAT_SKELETON.md](docs/COMBAT_SKELETON.md)：TLM 骨骼名、Bedrock 动作文件、`dev.logos.dimension.combat` 运行时；其中第 1 节的兼容性规则优先于新功能，只接管本附属自己的武器与生物，失败时退回原版。新增带动作的三维武器时按 [docs/COMBAT_WEAPON_WORKFLOW.md](docs/COMBAT_WEAPON_WORKFLOW.md) 的流程和清单做。
- 刀光按 [docs/WEAPON_TRAILS.md](docs/WEAPON_TRAILS.md)，尤其是其中“规范与踩坑”一节：
  - **接入**：刀光是刀身扫过的面，方向不另外指定。每个动作组一份 `combat/trails/<动作组>.json`，刀身范围写在这里，不写进导入工具会重写的 `combat/weapons/*.json`。
  - **出光**：先量刀尖速度，起手快的招式用 `windows`；改招式、加招式时同步改出光时段。
  - **样式**：按伤害类型选，新样式另建文件，带颜色的层 `occlude` 取中间值。
  - **光影**：顶点写全（含法线），第一人称走 `ShaderPackOverlay.item`，改了核心要在两个光影包下各跑一遍。
  - **外部素材**：参考 UE 等外部特效时只照搬逻辑，不拷素材包的贴图。
- 提交与 PR 不添加 AI 作者、共同作者或贡献者署名，使用维护者已有的 Git 身份。
- 复用现有 Logos 材料注册 ID，不复制注册污染碎片。
- 特效按 [docs/VFX.md](docs/VFX.md) 做：复用 Logos 的法术特效时在服务端调用 `dev.logos.runtime.Vfx`；附属自己的特效直接用 Photon 写，已有贴图用 `logos:` 路径引用。构建需要 `-PlogosJar`。
- 运镜（镜头暂时离开玩家再回来，方块、Boss 等共用）按 [docs/CAMERA_SHOTS.md](docs/CAMERA_SHOTS.md)：服务端调 `CameraShots.play`，不要用 `setCameraEntity` 做这件事。
- 全屏后期（黑白闪、电影蒙版）按 [docs/SCREEN_EFFECTS.md](docs/SCREEN_EFFECTS.md)：服务端调 `ScreenEffects.flash`，不要占原版 `setPostEffect` 的槽。改了后期要在文档列的 Sodium、Iris 和两种光影包下各跑一遍。
- 消散（模型逐渐消失、边缘发光）按 [docs/DISSOLVE.md](docs/DISSOLVE.md)：服务端调 `Dissolving`。让特效泛光用 `GlowBloom`，见 [docs/SCREEN_EFFECTS.md](docs/SCREEN_EFFECTS.md)，不要按亮度对整个画面取阈值。
- 要在画面变暗、调色之后仍然亮着的特效（或要在雾和光影包之外、几百格外都看得见的特效）交给 `PostVfx`，见 [docs/SCREEN_EFFECTS.md](docs/SCREEN_EFFECTS.md)；爆裂魔法按 [docs/EXPLOSION_MAGIC.md](docs/EXPLOSION_MAGIC.md)，大面积改方块照它的 `Crater` 直接写区块数据、按区块重发，不要逐个 `setBlock`。
- 腐败（核心、脉络、污秽、网络）按 [docs/CORRUPTION.md](docs/CORRUPTION.md)：蔓延照搬原版幽匿催发体，改蔓延规则时对照原版 `SculkSpreader` 等类；玩法数值在 `ServerConfig` 的 `[corruption]` 一节。
- 门后异画风按 [docs/WORLD_STYLES.md](docs/WORLD_STYLES.md)：服务端调 `StyleRealms.add`，只能换画风，不要用第二镜头显示别处。
- 乐器（钢琴、竖琴、打孔纸带、MIDI 导入）按 [docs/HEXENWALD_INSTRUMENTS.md](docs/HEXENWALD_INSTRUMENTS.md)，管风琴按 [docs/PIPE_ORGAN.md](docs/PIPE_ORGAN.md)：钢琴与竖琴的采样用 `tools/instrument_samples.py` 合成，管风琴的音色在客户端现场合成、经 `OrganBufferMixin` 交给声音引擎，都不放录音素材；服务端从不读 MIDI 或玩家给的路径，只收客户端解析好的纸带并重新校验。
- 新增植被或树叶方块时加进 Logos 的 `logos:fragile_plants` 标签，否则衰退符文对它无效；见 [docs/LOGOS_TAGS.md](docs/LOGOS_TAGS.md)。
- 不使用原版新版本硫磺方块，不添加原版主世界矿物生成。
- 两个维度使用原版数据包生成，不恢复 Java 地形/种树/建筑填块生成器。咒械原为每釜独立的 20,000×20,000 格 UUID 世界，原版大群系替代同心环，以太占多数。挖掘保留绑定，实体损毁才销毁对应实例。
- 例外是神狐聚落（`dev.logos.dimension.fox`，维护者于 2026-11-02 同意）：原版拼接无法按坡度铺山路、Beardifier 只能削不能填，所以由 Java 结构选址、整形山体、铺山路与溪流；建筑仍是代码生成的 NBT 模板。见 [docs/FOX_SANCTUARY.md](docs/FOX_SANCTUARY.md)。曦誓之洞（`dev.logos.dimension.dawnvow`，维护者于 2026-11-03 同意）同样由 Java 选址与开凿山洞，见 [docs/DAWNVOW_STONE.md](docs/DAWNVOW_STONE.md)。太玄灵境（`dev.logos.dimension.taixuan.TaixuanStructure`，维护者于 2026-11-11 同意）只用 Java 做选址检查：整座地标都要落在云岫峰林里、深渊口一圈的地面不能低于石壁顶；浮岛、深渊、镇渊柱和殿阁仍是原版拼接的 NBT 模板，见 [docs/TAIXUAN.md](docs/TAIXUAN.md)。其他结构不得照此扩展，需要时先问。
- 中心空岛顶面保持留空，禁止自动刷怪。Boss 和宫殿只在明确要求后制作。
- 岛下影雾由服务端 Boss 完成状态控制；每个炼金釜的独立世界分别记录。
- 测试必须明确区分单元、世界和实际客户端检查；没测试过的光影包不能声称已验证。
- 自定义几何写全 UV、颜色、光照、法线和 overlay。与当前 Logos 的 ShaderPackOverlay 共用收集器，并在其 begin 之后提交；不要在附属 Mod 中清空主模组的收集器。
- 不提交凭据、运行目录、存档、构建缓存、第三方模组 JAR 或光影包。
