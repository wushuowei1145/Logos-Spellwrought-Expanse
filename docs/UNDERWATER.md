# 远行大陆水下景观

给远行大陆的海面以下补充水生植物与海底小石堆。水体本身由数据包生成（海平面 63、`default_fluid` 水、含水层开启），低地被灌成海/湖/湾；本功能只往水里加水下内容。

## 内容

| 方块 | 说明 |
|---|---|
| `journey_seagrass` | 海草，照原版 SeagrassBlock 改写：只在水里生长，水被移除即消失；骨粉长成高海草 |
| `journey_tall_seagrass` | 两格高海草，下半需要完整水格（照原版 TallSeagrassBlock） |
| `journey_glow_seagrass` | 萤光海草，同海草但自发光 4 级，集中在泻湖与湿地 |
| `journey_kelp` / `journey_kelp_plant` | 海带头与茎，照原版 KelpBlock/KelpPlantBlock：水内向上生长，可用骨粉助长 |
| 海底小石堆 | 照原版 BlockPileFeature 改写，允许往水格堆放；石灰岩为主、少量苔石 |

生成特征（configured/placed）与放置方式照原版 seagrass/kelp：`minecraft:heightmap OCEAN_FLOOR_WG` + 原生 biome 过滤，数量沿用原版手感。

## 分布

- 水面群系（`cloudsea_tide_ribbons`、`cloudsea_opal_dunes`、`cloudsea_glass_lagoons`、`cloudsea_pearl_basins`、`cloudsea_dream_basins`）：海草、海底石堆；泻湖与湿地加萤光海草，海湾与浅滩加海带。
- **海面以下的海底属于 depth 群系 `cloudsea_root_caves`**，同样的特征也接在这里，否则水下植物只会长在极浅处。
- 地表规则补了一条「水下面 → 沙/砾」：没有这条规则时海底露出裸石头，水草会长在石头上；现在水下表层为沙（3 格内）与砾石（更深），与原版海床一致。
- 珊瑚与鱼群留待下一阶段；河里不放珊瑚。

## 资源

```text
python tools/underwater_art.py       # 海草/高海草/萤光海草/海带贴图、模型、方块状态、物品与掉落
python tools/underwater_worldgen.py  # configured/placed feature 与群系接线（幂等）
```

模型沿用原版 `minecraft:block/template_seagrass` 与 `minecraft:block/cross`，只替换贴图；贴图全部由代码绘制。

## 测试

```text
./gradlew runClient -PlogosJar=... -PldTest=underwater_views -PldTestHeadless -PldTestWindow=1280x720
```

`underwater_views` 会在水箱里摆齐海草/高海草/萤光海草/海带/石堆并截图，断言：群系接入了 placed feature、远行大陆实际生成的水下植物数量大于 0、水箱内方块存活。
