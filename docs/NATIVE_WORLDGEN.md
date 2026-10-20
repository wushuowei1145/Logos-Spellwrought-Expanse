# 原版生成数据与迁移

0.4.0 按用户选择，两个维度都使用原版数据包生成。内置数据位于 `src/main/resources/data/logos_dimension/`，存档数据包可覆盖同名资源。

| 内容 | 数据路径 | 原版机制 |
| --- | --- | --- |
| 远行大陆 | dimension/cloudsea.json | minecraft:noise + minecraft:multi_noise |
| 炼金釜模板 | cauldron/crucible.json | 原版 LevelStem 格式，按 UUID 加载 |
| 地形与表面 | worldgen/noise_settings/ | overworld / large_biomes 与 surface_rule |
| 群系 | worldgen/biome/ | 气候映射、刷怪和装饰步骤 |
| 树木、植被、液体湖 | configured_feature/、placed_feature/ | 原版 tree、simple_block、lake |
| 空岛、聚落 | structure/*.nbt、structure/、template_pool/、structure_set/ | 原版 NBT、jigsaw、结构分布 |

Java 只保留 UUID 生命周期、进出、安全落点、季节、粒子与玩法保护。删除旧 ChunkGenerator、自定义 BiomeSource、Java 地形/种树/建筑填块代码。LegacyWorldgenCodecs 仅把旧存档 ID 解码成原版生成器和群系源。

## 布局变化

咒械原用原版大群系气候选择，以太气候样本最多，硫/汞/盐自然成片。取消精确同心环和重复巨构。20,000×20,000 世界边界保留；原版边界不禁止管理员越界生成区块。

中心空岛为一个 128×65×128 NBT 模板，圆形顶面约 126 格直径、Y=320，原点附近单次生成，下部收拢。模板不放建筑、树或 Boss。原 2,400 格手写岛不再生成；未来可拆分原版结构池扩大场地。

远行大陆有 32 个地表群系和一个地下群系，不再按 Z 坐标固定南北地理。森林由气候连续分布，林隙独立成群系。

## 旧模板和绑定

删除 dimension/crucible.json，取消可指令进入的固定 logos_dimension:crucible 空世界。cauldron/crucible.json 不属于维度注册目录。旧存档若仍加载固定世界，启动时撤离玩家、保存、卸载，保留旧目录。

数据组件 logos_dimension:cauldron_world 保存 UUID。绑定物品名显示 UUID，提示与釜右键显示完整维度名。回程漩涡在外围抵达点旁自动生成。

## 资源与验证

tools/native_worldgen.py 先生成模型纹理，再输出最终原版数据与 NBT。维护脚本需要 Pillow 与本地 Minecraft 源码资料；结果已提交，正常构建不需要脚本或联网素材。

GameTestServer 的 WorldOptions 禁用结构生成，因此 GameTest 检查结构类型，真实服务器 fixture 另检查实际空岛和聚落放置。客户端区分自然森林/林隙与专门摆放的植物画廊；季节截图主动调整时钟与附近材质。验证范围见 [VERIFICATION.md](../VERIFICATION.md)。
