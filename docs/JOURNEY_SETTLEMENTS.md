# 林隙村镇与随地势小麦

0.9.1 保留用户提供的七份中世纪蓝图改造建筑、16/24 个住宅地块、井台、市场与原版居民。来源见 [蓝图记录](../references/user_blueprints/README.md)。本次修正撤掉旧版 52×56 水田、田埂、灌渠、桥、粮棚及额外加长的农舍田区，不再把农业地块作为大模板摆放。

## 直接沿原地表种植

农舍周围由原版 `feature_pool_element` 调用 `block_column` Feature。每次候选独立读取 `MOTION_BLOCKING_NO_LEAVES` 高度，检查当前地表是原版草方块、泥土、砂土或本附属对应草皮，且上方完全为空。

通过检查后仅做两步：将**原高度**上的这一块土转成原版耕地，在它上方的空位种原版小麦。石头、道路、木材、箱子、建筑、水、已有植物和其他农作物都跳过。没有清空空气、搬移方块、抹平山坡、垫泥层或生成灌溉水。

候选点围绕农舍边缘散布，接受的麦株多少取决于现有地形和可种空位，不再限定方形大田。小麦的生长、干湿耕地、骨粉、收割和补种全部使用原版规则，没有新增作物行为。192 次候选限制在锚点周围 7 格，避免写入远处生成区块。

## 避免抬地与掩埋

种植的结构部件只有一个点，没有大型田块包围盒；聚落的 `terrain_adaptation` 改成 `none`，取消原版 Beardifier 自动填高或削低地表。房屋本体保留建造所需的模板基础和内部空间，道路仍按原版高度投影放置。种植本身只改合法土壤及其上方原本的空气。

生产生成继续仅使用原版数据包格式，没有 Java 填地、种植或地形生成器。聚落中心仍限定为曦光林隙、星铃花甸、长风草原、野花草甸，间距继续稀疏，locate ID 保留：

```text
/locate structure logos_dimension:journey_hamlet
/locate structure logos_dimension:journey_town
```

## 已有存档

新生成聚落采用新布局。0.9.0 已生成的水田不会自动拆除或恢复原地形，避免误删玩家修改；原始地形也没有可供自动回填的快照。旧水稻的注册 ID 和资源仅为旧存档保留，已从创造物品栏隐藏，不再由聚落生成。

```text
python tools/settlements.py
python tools/audit_settlements.py
./gradlew test runGameTestServer -PdimensionTests=logos_dimension:settlement_*
./gradlew runClient -PldTest=group:blueprint_settlements -PldTestHeadless -PldTestWindow=1600x900
```

世界测试逐格对照高低坡地及受保护物件，验证种植前后只有合法的耕地/小麦变化。客户端截图为已生成地形上的原版拼接预览，结果见 [VERIFICATION.md](../VERIFICATION.md)。
