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

远行大陆有 35 个地表群系和一个地下群系，不再按 Z 坐标固定南北地理。森林由气候连续分布，林隙独立成群系。

## 群系大小

远行大陆的群系由 t×h×c×e×w = 5×5×4×3×5 的格点表决定（`dimension/cloudsea.json`）。格点是完整的直角网格，每处在各轴上分别取最近的值，所以一个群系占的就是它那些格点对应的气候盒子。盒子在好几个轴上都窄时，群系会被各个噪声的边界切成碎块。

云岫峰林要能整块放下太玄灵境（完整地标 252×252 格），前后扩了三次。下表是 32768×32768 格的普查（每 64 格一点）：

| 版本 | 格点 | 占地图 | 一片的中位数 | 最大一片 | 九成面积在不在能放下 320 格见方的大片里 | 能当 256 格见方中心的地方 |
|---|---|---|---|---|---|---|
| 第一版 | 16 | 约 0.5% | 约 87 格（8192 格普查） | — | 石柱长满的核心只占一成，`/locate` 找到的多是碎片 | — |
| 第二版 | 45 | 0.88% | 约 220 格 | 约 1340 格 | 82% | 地图的 0.16% |
| 现在 | 100 | 1.69% | 约 300 格 | 约 1950 格 | 93.5% | 地图的 0.35% |

现在的格点是两个盒子的并集（见 `tools/cloud_peaks.py` 的 `in_climate`）：高温 t=0.8、h ≥ -0.4，加上「中等侵蚀 e=0.15、c ≥ -0.08」或「近海 c=-0.08、三档侵蚀」。多拿的格点来自白崖海岸、静水湖泊、铜叶林丘（面积约少三分之一）和曦光林隙，没有群系被挤掉。内陆的低侵蚀那一档底下是大山，石柱会立在山顶上，所以不要；近海的低侵蚀、高侵蚀只是海边丘陵和平地，普查量到的地表高度和中等侵蚀那片差不多（四分之一分位约 72–79，中位数约 82–94）。地形门是两个盒子取较大的那个，两个盒子在群系内部相接的地方不会凹下去一道缝。

以后在这个群系里放太玄灵境这样的大结构，要放在够大的那几片里：只看起点的群系不够，起点要离群系边缘 128 格以上。

量群系大小用普查场景：

```text
./gradlew runClient -PldTest=cloudsea_biome_census -PldTestHeadless -PlogosJar=/absolute/path/logos.jar
```

它不生成区块，直接用群系源和气候采样器取点，写两份：8192×8192 格每 16 格一点，和 32768×32768 格每 64 格一点（文件名带 `_wide`）。每份有群系编号（`cloudsea_biomes*.csv`）、五个气候值（`cloudsea_climate*.csv`）、编号对照（`cloudsea_legend*.txt`），云岫峰林里每四个点还按噪声算一次地表高度（`cloudsea_census_heights*.csv`）。因为是直角网格，拿气候值就能离线精确重算「把哪些格点划给谁」之后的群系分布，不用每改一次都进游戏。

## 旧模板和绑定

删除 dimension/crucible.json，取消可指令进入的固定 logos_dimension:crucible 空世界。cauldron/crucible.json 不属于维度注册目录。旧存档若仍加载固定世界，启动时撤离玩家、保存、卸载，保留旧目录。

数据组件 logos_dimension:cauldron_world 保存 UUID。绑定物品名显示 UUID，提示与釜右键显示完整维度名。回程漩涡在外围抵达点旁自动生成。

## 资源与验证

tools/native_worldgen.py 先生成模型纹理，再输出最终原版数据与 NBT。维护脚本需要 Pillow 与本地 Minecraft 源码资料；结果已提交，正常构建不需要脚本或联网素材。

GameTestServer 的 WorldOptions 禁用结构生成，因此 GameTest 检查结构类型，真实服务器 fixture 另检查实际空岛和聚落放置。客户端区分自然森林/林隙与专门摆放的植物画廊；季节截图主动调整时钟与附近材质。验证范围见 [VERIFICATION.md](../VERIFICATION.md)。
