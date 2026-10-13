# 首版验证记录

目标环境：Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25；Logos 0.5.1，主模组源码基线 `f196960`。这是可运行的 0.1.0 原型，不包含建筑或 Boss。

## 已完成

| 检查 | 结果 | 覆盖内容 |
| --- | --- | --- |
| JUnit | 8/8 通过 | 20,000 格重复布局、区域比例和顺序、负坐标、种子变化、空岛平台、周期、雾区边界 |
| GameTest | 9/9 通过 | 注册兼容、平台留空、禁刷怪、自有材料、独立时间、往返传送、天然材料换相、流体流动与装桶、各岛 Boss 状态持久化 |
| 普通客户端 | 8/8 场景、19/19 检查通过 | 四区、中心空岛、倒悬蒸馏瓶、四阶段天空、岛下影雾及解除 |
| Complementary Reimagined r5.9.3 | 8/8 场景、19/19 检查通过 | 实际启用 Iris 1.11.4、Sodium 0.9.2；天空星盘和影子可见 |
| Photon Shader v1.3b | 8/8 场景、19/19 检查通过 | 实际启用 Iris 1.11.4、Sodium 0.9.2；天空与影雾场景完成 |

客户端场景使用 LDLib2 的实际开发客户端；截图不是概念图。首版截图位于 `docs/screenshots/`。完整报告由检查生成在 `build/ldlib2-uitest/report.json`，世界检查报告在 `build/gametest-results.xml`。

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
