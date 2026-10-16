# 维度-咒械原

**Logos: Spellwrought Expanse** — 由咒术塑成的辽阔原野。

Logos 的独立新维度附属 Mod。工程名 `LogosDimensionAddon`，Mod ID `logos_dimension`。当前组合测试版本为可运行的 **0.3.1 原型**，适合逐步调整世界生成和美术。

第二维度 `logos_dimension:cloudsea` 已改为 **远行大陆**：开阔的温带草原、橡木与山毛榉林、白桦林、北方针叶林和雪山，南方是河湾与石灰岩海岸。取消原有的悬浮岩带、魔鲸、魔法粒子与魔气地心，以长途旅行和稀疏的中世纪聚落为重点。保留原维度与群系 ID；旧区块不自动重建，建议在新存档或新生成区块体验。

四季每季 8 个游戏日，树叶与草皮逐步换季，冬季低地降雪；使用普通昼夜、蓝天、薄云和原版天气。地下接入原版洞穴、峡谷与废弃矿井，不运行任何矿物生成。城镇与村落靠近河流，麦田、葡萄架和果园集中在乡村周围。完整说明见 [远行大陆](docs/TEMPERATE_JOURNEY.md)。

本轮重做树木与材料：完整原木、连通分枝、成团树冠，清晰的像素叶簇与专用材料，减少地表重复浮雕和整片发光。咒械原法阵加入透明玻璃承载面、视角高光、边缘色散、清亮刻线与向外传播的施法波，保留展开重组动画。详见 [树木与材料](docs/NATURAL_TREES.md)、[逐种模型目录](docs/INDIVIDUAL_BLOCK_MODELS.md)、[玻璃法阵](docs/GLASS_ARCANE_ARRAY.md) 和 [云层移除说明](docs/CLOUD_VOLUME.md)。三项更新保持独立 PR，当前分支用于组合安装包验证。

![空岛下方的影雾](docs/screenshots/shadow-fog.png)

0.1.4 进一步平滑法阵连线切换、光点循环和玻璃高光，减少逐帧数组分配；见 [连续过渡与实机视频](docs/SMOOTH_ARRAY.md)。

## 世界方向

世界是一座仍在工作的巨大炼金釜。**每口炼金釜首次进入时生成一个独立 UUID 世界，大小固定 20,000 × 20,000 格**，坐标范围为 X/Z ∈ [-10,000, 10,000)，边缘设置世界边界，外侧不再重复生成地貌。世界只有一座直径约 2,400 格的中心空岛。地面由内向外依次是硫、汞、盐三条宽广区域，以太荒野占多数并穿插连通各区。默认抵达距中心约 9,300 格的外围以太区，区块按需生成，不一次性预生成全图。

- 以太：灰绿地层、扇冠树、琥珀种穗、星形花与河谷。
- 硫：焦化岩、朱砂、附属 Mod 自有的炼金硫沉积、深裂谷与熔融玻璃。
- 汞：凝汞地壳、真实可流动的液态汞、少量悬浮金属岩台。
- 盐：盐壳台地、透明盐晶、白化时的天然盐晶生长。
- 环境巨物：倒悬玻璃蒸馏瓶生态岛、巨型透明液体导管、缓慢旋转的天空星盘。
- 时间：独立的黑化、白化、黄化、红化周期，每阶段 24,000 tick。天空、雾、天然材料随周期变化。

![倒悬蒸馏瓶原型](docs/screenshots/glass-retort.png)

**中心空岛不生成建筑、植被或小怪，平整顶面预留给后续 Boss 宫殿。** 专属 `sanctum` 生物群系没有自然刷怪表，额外拦截自动刷怪和刷怪笼。脚本与命令召唤保留给后续 Boss。岛下面的地面仍可有正常生态。

## 空岛下方的影雾

击败对应空岛 Boss 前，岛下开放空间被灰黑雾笼罩。进入会看到边缘浮现的细长影子、短暂苍白眼点，并听到低沉回声；靠近或近距离直视时影子消散。影子属于视觉幻象，不攻击、不碰撞、没有掉落，也不会在存档中堆积。离开雾区后清除。

每口炼金釜的独立世界分别持久保存中心空岛的 Boss 完成状态。以后把 Boss 的实体 ID 加入 `logos_dimension:island_bosses` 实体类型标签，其在对应岛范围内死亡时便会解除该岛的雾与幻象。

搜索占位入口：**`LOGOS_DIMENSION_ISLAND_BOSS_HOOK`**。目前标签为空，宫殿及 Boss 尚未制作。管理员可用下面的命令验证完成与重置状态。

## 安装与进入

当前构建目标：Minecraft **26.1.2**、NeoForge **26.1.2.112**、Java **25**。与独立安装的 Logos 0.5.0+ 一起放入 `mods`；开发验证使用 Logos **0.5.1**，主仓库基线 `f196960`。Logos 自身所需的 LDLib2、Photon、KilaGraph、GeckoLib 也需按主模组要求安装。本附属不内嵌这些模组。

生存入口：8 块玻璃围住一个现有 `logos:tainted_mana_shard`，合成「炼金漩涡」；再与原版炼药锅合成「炼金釜」。釜内的金银漩涡缓慢旋转并发光，跳入釜内液面即可传送到咒械原。釜壁与釜沿有实际碰撞，碰外壁或站釜沿不会触发。返回位置保存在釜旁的安全地面，狭窄位置则落在实心釜沿；传送后有 4 秒冷却。**挖掘炼金釜掉落带 UUID 的釜，重新放置仍进入原世界；创造模式挖掘已绑定釜也保留掉落。** 釜或其掉落物被真正摧毁（爆炸、岩浆/火焰、虚空、自然消失等）时，先撤离其中在线玩家，再卸载并删除该 UUID 的世界数据，其他世界不受影响。拾取和区块卸载不视为损毁。进入时会在外围落点旁自动生成「回程漩涡」，右键即可沿原路返回，无需自带材料；在某世界内放置绑定同一 UUID 的釜也可返回，新造的未绑定釜则会开启新世界。旧炼金漩涡在外界仍保留兼容入口。详见 [炼金釜独立世界](docs/CAULDRON_WORLDS.md)。朱砂与盐晶的材料掉落同样复用这个 Logos 注册 ID，没有重复注册污染碎片。

管理员命令：

![炼金釜实机截图](docs/screenshots/alchemical-cauldron.png)

```text
/alchemy enter
/alchemy return
/alchemy island [grid_x grid_z]
/alchemy region aether|sulfur|mercury|salt
/alchemy phase nigredo|albedo|citrinitas|rubedo
/alchemy boss defeated
/alchemy boss reset
```

`boss` 命令只修改玩家所在 UUID 世界的状态。`island` 只接受原点（0, 0），不再存在其他重复空岛。命令与旧漩涡仍可进入固定的管理测试维度，实体炼金釜始终创建自己的实例。普通玩家不能执行管理员命令。首次远距离传送需生成目的地区块。

天门云域暂未制作生存入口。管理员可先切创造模式，再传送到中央天门附近：

```text
/gamemode creative
/execute in logos_dimension:cloudsea run tp @s 0 400 0
```

原云海、魔鲸与天空缺口已取消，改用普通天空。管理员仍可用上述命令先进入空域，再飞向地面。南方海岸大约在 Z=6,800 一带，北方雪山在 Z=-6,600 一带，具体随种子与 X 坐标变化。没有添加本维度的生存入口。

**不引用 26.1 原版硫磺方块，不添加任何原版主世界矿物生成。** `logos_dimension:sulfur_deposit` 是独立方块，方便以后移植资源与地形逻辑；当前 Java API 和构建仍针对上述 Minecraft 版本，跨版本仍需适配。

## 开发和检查

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos.jar
./gradlew runClient -PlogosJar=/absolute/path/logos.jar -PldTest=group:logos_dimension -PldTestHeadless -PldTestWindow=1280x720
```

GameTest 的专用数据包仅在测试时加载，因为 26.1 的 GameTestServer 默认剔除数据包维度。它不进入发行 JAR。源码内的客户端场景由 LDLib2 的 DEV_ONLY 注册，仅在开发环境运行。

咒械原地理比例、单一中心与边界、种子变化、平台留空、禁刷怪、流体流动与收集、天然材料换相、维度往返、各岛 Boss 状态序列化均有检查。客户端输出在 `build/ldlib2-uitest/`。

天空和影雾的自定义几何写全顶点属性。当前 Logos 的 `ShaderPackOverlay` 可用时，共用其光影合成后收集器；早期 Logos 没有该 API 时使用普通渲染。光影包兼容结果请见 `VERIFICATION.md`。

## 原型边界

镜面汞目前采用动画金属贴图，没有真正的实时镜面反射。大型地貌、美术、流体和影雾已是可运行底层；移动元素风暴、完整物质转化链、区域独有生物、探索奖励、建筑、宫殿及 Boss 留待后续逐项制作。当前没有原版主世界矿物，不会把主模组本体改成新维度模组。

## 0.2.0 同步修复

咒械原玻璃法阵以自身高度为中心等比放大 15%。熔融玻璃和汞实现原版岩浆式移动、上浮与越岸，源头和流动格均可用方块填平。熔融玻璃采用原版岩浆接触点燃和伤害；汞采用中毒 I（残留 4 秒）与虚弱 I（残留 8 秒），接触持续刷新，不附加直接伤害。遵循原版药效免疫和创造/旁观规则。

中世纪建筑使用 [Millénaire 公开诺曼投影](https://github.com/Zoythrus/Content/tree/1.x.x/cultures/norman/buildings/core) 改作，MIT 原许可、固定版本和修改说明保存在 `third_party/millenaire/`；发行 JAR 包含相应许可。新素材脚本为 `tools/journey_resources.py`，需要 Pillow 与本地 Minecraft 源码资料。生成文件已提交，正常构建不联网取图，也不需要运行资源脚本。
