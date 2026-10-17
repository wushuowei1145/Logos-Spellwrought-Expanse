# 维度-咒械原

**Logos: Spellwrought Expanse**，Logos 的独立维度附属 Mod，Mod ID：`logos_dimension`。当前版本 **0.5.0**。目标为 Minecraft **26.1.2**、NeoForge **26.1.2.112**、Java **25**，开发验证搭配 Logos **0.5.1**。

0.5.0 重做远行大陆全部 96 种方块的模型或材质：灰白层理石灰岩、带叶簇边缘的树叶、铺满方块面的牧草，以及分枝、花穗和蕨叶。葡萄藤使用原版藤蔓的表面附着与攀爬规则，结出可采摘的葡萄和葡萄叶，详见 [方块与葡萄藤](docs/JOURNEY_BLOCK_ART.md)。保留 0.4.1 的换季和安全出口修复。

成熟葡萄藤空手右键采葡萄，每颗回复一格饥饿值（2 点）；潜行空手右键或使用剪刀摘叶。摘后会重新生长，需要亮度至少 7，也可用骨粉催熟。藤体本身不自动蔓延。

## 原版数据包生成

两个维度都使用原版 `minecraft:noise` 地形、`minecraft:multi_noise` 群系选择、原版树木与植物 Feature、标准 NBT/jigsaw 结构。删除手写 Java 地形、种树与建筑填块生成器。数据随 Mod 内置，正常安装不需要额外下载数据包，存档数据包可以覆盖同名资源。见 [生成数据与迁移](docs/NATIVE_WORLDGEN.md)。

- **咒械原**：每口炼金釜首次进入创建一个 `logos_dimension:<UUID>` 世界，20,000×20,000 格边界，以太为主要环境，硫、汞、盐为大尺度自然群系。
- **远行大陆**：保留 `logos_dimension:cloudsea` ID，32 个地表群系和独立地下群系。湿润气候形成连续密林，林隙与花甸独立成群系；林下有蕨、苔毯、落叶、灌木和季节植物。正常天空、昼夜与天气，四季各 8 个游戏日。
- **无原版矿物**：关闭原版矿脉，移除矿物装饰 Feature，保留原版洞穴、含水层与废弃矿井。炼金硫沉积是本附属自有方块，不引用 26.1 原版硫磺。
- **稀疏中世纪聚落**：使用原版 jigsaw/random_spread，可用 `/locate structure logos_dimension:journey_hamlet` 或 `journey_town` 寻找。素材与许可见 [第三方说明](third_party/millenaire/SOURCE.md)。

原版生成重设计了布局：不再保证硫→汞→盐的精确同心顺序，也不固定雪山/海岸坐标。中心空岛现为约 **126 格直径、顶面 Y=320** 的原版 NBT 预留平台，原 2,400 格手写岛与玻璃巨构不再生成。岛面不放建筑或植被，拦截自然及刷怪笼生成，宫殿和 Boss 尚未制作。

## 安装与进出

将本附属与 Logos 及其依赖一起放入 `mods`，本包不内嵌主模组或第三方 JAR。

8 块玻璃围住 `logos:tainted_mana_shard` 合成炼金漩涡，再与原版炼药锅合成炼金釜。**跳入釜内液面**进入独立世界，默认寻找外围以太区安全地面；首次到达在旁边自动生成回程漩涡，右键返回原釜旁，重入复用出口。

已绑定釜的物品名称显示 UUID，悬浮提示和对放置的釜右键显示完整维度名。挖掘、拾取、再放置保留绑定；真正损毁釜或其掉落物才撤离玩家并销毁对应 UUID 世界。见 [炼金釜与回程](docs/CAULDRON_WORLDS.md)。

**取消旧固定 `logos_dimension:crucible` 的注册与入口**，不能再用 `/execute in` 进入空模板。升级存档若仍加载它，启动时撤离玩家、保存并卸载；保留旧目录，避免删除旧建筑。UUID 实例继续使用。

管理员命令：

```text
/alchemy enter
/alchemy return
/alchemy island 0 0
/alchemy region aether|sulfur|mercury|salt
/alchemy phase nigredo|albedo|citrinitas|rubedo
/alchemy boss defeated
/alchemy boss reset
```

`enter` 需要附近有实体炼金釜，不再打开公共模板。`region` 查找原版群系中的安全位置，有限边界和特定种子内可能找不到目标。远行大陆仍无生存入口，管理员可先切创造模式，再用：

```text
/execute in logos_dimension:cloudsea run tp @s 0 250 0
```

岛下影雾保留，每个 UUID 世界独立记录 Boss 状态。搜索接口 `LOGOS_DIMENSION_ISLAND_BOSS_HOOK`，当前标签为空。动态玻璃法阵和流体行为沿用上一版。

## 升级和检查

旧区块保留，新区块使用原版生成，边缘可能出现接缝。旧生成器 ID 仅负责存档解码迁移，解码结果直接是原版生成器。推荐新存档或新炼金釜体验完整布局。

20,000 格限制由原版世界边界实现；管理员越界命令仍可能生成边界外区块，不承诺边界外为空。区块按需生成，不预生成整张地图。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos.jar
./gradlew runClient -PlogosJar=/absolute/path/logos.jar -PldTest=group:journey -PldTestHeadless -PldTestWindow=1280x720
./gradlew runClient -PlogosJar=/absolute/path/logos.jar -PldTest=group:cauldron_instances -PldTestHeadless -PldTestWindow=1280x720
```

验证结果见 [VERIFICATION.md](VERIFICATION.md)，生态方向见 [远行大陆](docs/TEMPERATE_JOURNEY.md)。`tools/native_worldgen.py` 输出的 JSON/NBT/PNG 已提交，普通构建不需要运行资源脚本。
