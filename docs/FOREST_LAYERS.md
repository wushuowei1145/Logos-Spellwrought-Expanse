# 森林地带的四层结构

远行大陆的森林地带不再只是"换了树的群系"。现在按四个彼此独立的层来组织，每层都有自己的数据文件、
生成阶段与验收方式；只有四个群系额外有雾。所有内容都由原版数据包生成，**没有 Java 地形、种树或填块生成器**。

| 层 | 内容 | 生成阶段 | 覆盖范围 |
|---|---|---|---|
| 地被 | 每个群系自己的 `<biome>_floor` 植物组合 | 植被装饰（索引 9） | 16 个森林群系（13 林地 + 2 林隙 + 精灵森林） |
| 地物 | 倒木、石灰岩露头 | 植被装饰（索引 9） | 13 个林地 + 精灵森林 |
| 水边 | 原版甘蔗与睡莲 | 植被装饰（索引 9） | 13 个林地 + 精灵森林 |
| 地下 | 荧光苔 | 地下装饰（索引 7） | 13 个林地 + 精灵森林 |
| 雾 | 雾色与雾距（仅客户端） | — | 4 个群系：翳蕨密林、雾苔溪林、杉影深林、精灵森林 |

## 一、地被

每个森林群系一份 `<biome>_floor`：`configured_feature` 是加权方块表，`placed_feature` 带完整的放置修饰符
（`count → in_square → heightmap → environment_scan 向下找泥土 → 随机偏移 → 非岛石 → 空中 → biome`）。

- 林地每区块 **640** 次尝试，林隙 **360** 次，与原来的共享层一致，没有增加生成开销。
- 方块只来自已有的 19 种地生植物与原版 `moss_carpet` / `leaf_litter` / `oxeye_daisy`，**不新增方块**。
- 招牌植物占比 ≥ 15%，由单元测试守住。

数据入口：`tools/native_worldgen.py` 的 `FOREST_FLOORS` 表；精灵森林的群系文件由 `tools/elven_forest.py` 覆盖生成。

## 二、地物

### 倒木

13 个林地各一份 `fallen_tree_<biome>`：原版 `minecraft:fallen_tree`，树干取该群系自己的原木，长度 4–7，
保留树桩；`rarity_filter` 1/6。精灵森林沿用山毛榉的倒木。

### 岩石露头

13 个林地共用一份 `forest_rock_outcrop`：原版 `minecraft:block_pile`，用既有的
`journey_limestone` 堆成 5×2×5 范围内的一小簇，`rarity_filter` 1/8。原版特征自己检查支撑面，不会浮空。

## 三、水边

13 个林地与精灵森林在植被阶段直接引用原版 `minecraft:patch_sugar_cane`（甘蔗）与
`minecraft:patch_waterlily`（睡莲）。这两个原版 placed feature 自带"必须挨着水"的判定，干燥林地不会生成，
因此**没有新增任何数据文件**。

## 四、地下

13 个林地共用一份 `forest_cave_lichen`：原版 `minecraft:multiface_growth` + 原版 `glow_lichen`，
只贴在地表以下 13 格以下的岩壁与天花板（`surface_relative_threshold_filter` 负阈值），
可附着基底含本附属的 `journey_limestone` / `island_stone` / `strata_stone`，每区块 104–157 次。

> 密度教训：最初写 24–48 次/区块，在森林下方扫 80×80 都找不到一块荧光苔；改成原版档位后才稳定出现。

## 五、雾（只有四个群系）

| 群系 | 雾色 | 近/远平面 |
|---|---|---|
| 翳蕨密林 | `#9fb3aa` | 12 / 88 |
| 雾苔溪林 | `#c6d0c8` + 水雾 `#b8c6c2` | 6 / 56 |
| 杉影深林 | `#aab9c4` | 14 / 96 |
| 精灵森林 | `#a9cdb4` + 水雾 `#a2c4bb` | 10 / 82 |

数据侧只加 `fog_color` / `water_fog_color`，**不改 `sky_color`**；客户端 `ForestMist` 在进入这些群系时
把雾色与雾距平滑收拢，离开后恢复。其余群系完全没有这个效果。

## 生成与重跑

```text
python tools/native_worldgen.py
python tools/tall_trees.py
python tools/grape_resources.py
python tools/magic_flora.py
python tools/berry_resources.py
python tools/elven_forest.py
```

顺序不能颠倒：`native_worldgen` 会重写所有群系 JSON，后面几个脚本各自往植被阶段追加内容。
跑完用 `git add -A` 后看 `git diff --cached --stat`，应当只出现本次改动涉及的文件。

## 验证现状

| 层 | 结果 |
|---|---|
| 单元测试 | **41/41 通过**（含 5 个森林数据测试类，共 16 项断言） |
| 世界内 GameTest | **50/50 通过**（含新增的 `fallen_log_placement`，断言倒木真的倒下） |
| 客户端场景 | 雾：`group:elven_forest` 3/3 场景、15/15 检查通过 |
| 数据断言 | 各层都检查了引用、方块来源、稀疏度与放置阶段；非林地群系确认没有被顺带加上 |

## 没有验证的部分

- **自然林地的前后对照截图**：这些场景的取景由地形与区块随机数决定，加 feature 会改变随机数消耗，
  机位跟着变，像素差不能当作地表变化的证据。真正可靠的前后对照需要一个**固定机位**的森林场景。
- **洞穴取景**：试过在洞穴里扫描荧光苔并传送拍摄，扫描能命中，但玩家会被碰撞推到地表，截图是草原，未解决。
- **光影包 / 硬件帧率**：均未测。
- **旧区块**：所有内容只在新生成区块生效，仓库规则不重建旧区块。

## 给后续改动的边界

- 新增一个林地：在 `tools/native_worldgen.py` 的 `forests` 集合与 `FOREST_FLOORS` 表里加条目，
  然后同步更新对应的单元测试群系列表（`Forest*Test` 各自有一份）。
- 公共部分（`forests` 集合、`forest_floor()` / `fallen_tree()` 辅助函数、放置修饰符）属于协调改动，
  一次只动一处，别在单个群系的改动里顺手改。
- 不新增方块、不恢复 Java 生成器、不给非林地加森林内容。
