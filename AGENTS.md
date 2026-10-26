# LogosDimensionAddon

先阅读 README.md 和 VERIFICATION.md。本工程是 Logos 的独立附属 Mod；主模组源码不属于这个仓库的修改范围。

- 新改动在分支完成，一个 PR 只解决一个主题。提交标题用英文，说明用中文。
- PR 默认以 main 为目标；只有明确说明的依赖审查才指向功能分支，依赖功能分支上的更新必须另行整合到 main，不能把功能分支内合并称为主分支已更新。
- 美术素材使用代码绘制，不使用图像生成服务；代码注释与文档字符串使用中文。
- NPC 模型按 [docs/NPC_MODELS.md](docs/NPC_MODELS.md)：车万女仆 GeckoLib 骨架命名、2 倍制作、entityCutout（不剔除背面）加原版实体光照，发光部件用 `ysmGlow` 前缀骨骼；制作做法见 [docs/NPC_MODELING_GUIDE.md](docs/NPC_MODELING_GUIDE.md)；普通 NPC 的随机外观按 [docs/NPC_APPEARANCE.md](docs/NPC_APPEARANCE.md)，幼年、伴侣与繁殖按 [docs/NPC_POPULATION.md](docs/NPC_POPULATION.md)，施法按 [docs/NPC_MAGIC.md](docs/NPC_MAGIC.md)。
- 提交与 PR 不添加 AI 作者、共同作者或贡献者署名，使用维护者已有的 Git 身份。
- 复用现有 Logos 材料注册 ID，不复制注册污染碎片。
- 新增植被或树叶方块时加进 Logos 的 `logos:fragile_plants` 标签，否则衰退符文对它无效；见 [docs/LOGOS_TAGS.md](docs/LOGOS_TAGS.md)。
- 不使用原版新版本硫磺方块，不添加原版主世界矿物生成。
- 两个维度使用原版数据包生成，不恢复 Java 地形/种树/建筑填块生成器。咒械原为每釜独立的 20,000×20,000 格 UUID 世界，原版大群系替代同心环，以太占多数。挖掘保留绑定，实体损毁才销毁对应实例。
- 中心空岛顶面保持留空，禁止自动刷怪。Boss 和宫殿只在明确要求后制作。
- 岛下影雾由服务端 Boss 完成状态控制；每个炼金釜的独立世界分别记录。
- 测试必须明确区分单元、世界和实际客户端检查；没测试过的光影包不能声称已验证。
- 自定义几何写全 UV、颜色、光照、法线和 overlay。与当前 Logos 的 ShaderPackOverlay 共用收集器，并在其 begin 之后提交；不要在附属 Mod 中清空主模组的收集器。
- 不提交凭据、运行目录、存档、构建缓存、第三方模组 JAR 或光影包。
