# 远行大陆水下景观

给远行大陆的海面以下补上原版海洋那样的海床与水生植物，再加本附属的萤光海草和海底小石堆。水体本身由数据包生成（海平面 63、`default_fluid` 水、含水层开启），低地被灌成海/湖/湾；本功能只管水下的地面和水里的东西。

## 为什么海底原来是光秃秃的石头

远行大陆的群系地表规则不分水上水下，水下地面也铺成各群系的草皮；而深一些的海底在群系源里落进了 depth 群系 `cloudsea_root_caves`，它没有地表规则，于是直接露出默认方块石头，水下特征也没有接进去。

## 海床：照原版主世界的地表规则

在 `noise_settings/journey.json` 的基岩规则之后、各群系规则之前插一条水下海床规则（只作用于预备地表以上、被水淹没的地面，不碰干地和地下的含水洞穴），写法照原版 overworld：

| 位置 | 原版做法 | 这里 |
|---|---|---|
| 沙底群系（`cloudsea_pearl_basins`、`cloudsea_tide_ribbons`）水下 | 暖海 / 沙滩：沙，悬空顶面砂岩 | 同 |
| 其他群系离水面不远（`water -6/-1`）的浅水底 | 泥土 | 同 |
| 更深的海床表层 | 砾石，悬空处石头 | 同（深海底的 `root_caves` 也走这条） |

浅水的泥土上再由原版的 `minecraft:disk_sand` / `disk_clay` / `disk_gravel` 海床圆盘撒出沙、黏土、砾石斑块，和原版河湖一样。圆盘直接引用原版的 placed feature，不另做一份。

## 植物与石堆

| 方块 | 说明 |
|---|---|
| `journey_seagrass` | 海草，继承原版 `SeagrassBlock`：只在满水格里生长，骨粉长成本附属的高海草 |
| `journey_tall_seagrass` | 两格高海草，继承原版 `TallSeagrassBlock` |
| `journey_glow_seagrass` | 萤光海草（本附属特色），同海草，自发光 4 级，叶尖带亮点 |
| `journey_kelp` / `journey_kelp_plant` | 海带头与茎，继承原版 `KelpBlock` / `KelpPlantBlock` |
| 海底小石堆 | 本附属特色。原版 `block_pile` 只往空气里堆，这里的 `journey_rock_pile` 只往水格里堆：石灰岩为主、少量苔石，只出现在水下 |

方块类只覆盖原版里写死了原版方块的那一个方法；生长、存活、含水、剪刀行为都沿用原版。海草与高海草照原版只有剪刀才掉落（高海草掉两株），海带不挑工具。五种植物都在 `logos:fragile_plants` 里，衰退会毁掉它们。

分布照原版海洋 / 河流的特征表，数量与高海草比例沿用原版：

| 群系 | 相当于原版 | 特征 |
|---|---|---|
| `cloudsea_pearl_basins`、`cloudsea_tide_ribbons`（海湾与浅滩） | 暖海 | `journey_seagrass_warm`（80，高 0.3）、`journey_kelp_warm` |
| `cloudsea_glass_lagoons`、`cloudsea_dream_basins` | 河流 | `journey_seagrass_river`（48，高 0.4）、`journey_glow_seagrass` |
| `cloudsea_opal_dunes` | 河流 | `journey_seagrass_river` |
| `cloudsea_root_caves`（海面以下的深海底） | 深海 | `journey_seagrass_deep`（48，高 0.8）、`journey_glow_seagrass`、`journey_kelp_cold` |

以上六个群系都接了海床圆盘（`underground_ores` 阶段）和海底小石堆（`local_modifications` 阶段，与原版森林石堆同阶段，先于植物，水草可以长在石堆上）。珊瑚与鱼群留待下一阶段；河里不放珊瑚。

## 资源

```text
python tools/underwater_art.py       # 贴图、模型、方块状态、物品、掉落、衰退标签
python tools/underwater_worldgen.py  # configured/placed feature、群系接线、水下海床规则（幂等）
```

模型沿用原版 `minecraft:block/template_seagrass` 与 `minecraft:block/cross`，只替换贴图；贴图全部由代码绘制。高海草在 16×32 上画成一丛连贯的长叶再切成上下两半。

## 测试

```text
./gradlew runClient -PlogosJar=... -PldTest=underwater_views -PldTestHeadless -PldTestWindow=1280x720
```

`underwater_views` 在水箱里摆齐海草/高海草/萤光海草/海带/石堆并截图，断言：群系接线（含原版圆盘）、衰退标签、剪刀掉落；并在泻湖和海湾附近各找一片水，统计实际生成的海床表层方块（裸石头不超过 10%）、水下植物数，以及水下与岸上的石堆（岸上必须为 0）。
