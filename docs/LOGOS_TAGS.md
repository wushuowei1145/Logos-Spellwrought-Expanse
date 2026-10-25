# Logos 本体标签

Logos 的部分符文按方块标签判断作用对象。附属 Mod 新增同类方块时，要同时加进对应标签，否则本体法术对它们无效。标签文件放在 `src/main/resources/data/logos/tags/block/`，写 `"replace": false` 追加，不要覆盖本体条目。

## `logos:fragile_plants`：会被衰退直接毁掉的植被

文件：[fragile_plants.json](../src/main/resources/data/logos/tags/block/fragile_plants.json)

衰退（`decay`）在作用范围内逐个检查方块，按以下顺序处理：

1. 有名为 `age` 的整数属性且未到最小值：`age` 减 1，方块保留。
2. 继承 `LeavesBlock` 的树叶：像自然凋落一样掉落并移除。
3. 在 `logos:fragile_plants` 里：按正常破坏掉落并移除。

本体标签默认包含 `#minecraft:flowers`、`#minecraft:saplings` 和原版几种草、蕨、灌木。已经进了 `#minecraft:flowers` 或 `#minecraft:saplings` 的方块不必重复添加。

**新增以下方块时加进这个标签：**

- 花、草、蕨、芦苇、菌类花草等不可碰撞的小型植被；
- 灌木、藤蔓这类会生长的植物：有 `age` 时先逐级倒退，退到最小值后再毁掉；
- 所有树叶。继承 `LeavesBlock` 的树叶即使不在标签里也会凋落，加进标签是为了把"这是植被"写清楚，以后不继承 `LeavesBlock` 的树叶也能生效。

**不要加的：**

- 农作物（如 `journey_rice`）：与原版小麦一致，只倒退生长阶段，不会被毁掉；
- 玻璃叶（`glass_leaves`）这类名字里有"叶"、实际是玻璃或矿物的方块；
- 家具和装饰件（精灵花架、吊灯细藤、叶形吊灯等），即使外观上有植物。

新增方块后，在客户端或世界测试里对它施放 `(decay here 1)` 确认会被移除。
