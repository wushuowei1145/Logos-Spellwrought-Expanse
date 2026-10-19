# 林隙村镇与大片稻田

0.9.0 根据用户提供的七份木石中世纪蓝图改造建筑，保留尖顶、挑檐、阳台、木骨架与院落，使用本附属的石灰岩与灰泥统一墙面。素材来源和原作者元数据在 [蓝图记录](../references/user_blueprints/README.md)。新聚落替代旧版单块大模板，保留 `journey_hamlet` / `journey_town` 的 locate ID。

## 布局

- 村落设 16 个住宅地块，小镇设 24 个；中央水井、市场、灯柱和四条街道连接住宅与庭院。
- 每个方向最多接一片 **52×56 格**大田，目标最多四片，总田区占地约 11,648 平方格。每片分六个水田区块，含田埂、浅水灌渠、入口桥、晾晒架和小粮棚；道路与后院留出通行空间。
- 宅院前门与街道连接，后院田门向外，房屋内部写入空气，避免原地形填满房间。水田为刚性浅水盆地，有封边；道路用原版 terrain_matching 随地形投影。
- 中心有四名原版村民；狭长农舍有原版农民。没有注册新的 NPC 类型，也没有定时补刷人口。

房屋、田区都是原版 jigsaw 部件。原版会根据包围盒、地形高差与高度限制拒绝部分部件，所以不能保证每个自然聚落都生成满额住宅和四片田；适合在平缓空地探索。生成中心限制为曦光林隙、星铃花甸、长风草原、野花草甸四个开放群系。聚落之间保持稀疏，random_spread 间距为村落 128 区块、小镇 192 区块，这是候选网格间距，不是固定相邻距离。

## 水稻

`logos_dimension:journey_rice` 是实际作物，具有八个生长阶段和独立贴图。稻秆、叶片和下垂穗分别绘制，使用四组双面植株面片。稻株自带源水状态，像原版海草一样扎根浅水；玩家需要点击水下泥巴或黏土种植，石头与干地不能种。

生长、光照要求、骨粉和破坏收割复用原版 CropBlock。成熟破坏掉 2–4 份稻种，未成熟掉 1 份，可再次种植。没有增加水稻食品或配方。稻株不需要方块实体，不逐株执行自定义扫描。

## 原版数据与迁移

生产生成仅包含 `minecraft:jigsaw`、`random_spread`、template_pool 与标准结构 NBT。带地形适配时最大生成半径 112 格，另留原版地形适配余量。没有恢复 Java 建筑填充器，也没有改变大陆地形。

```text
/locate structure logos_dimension:journey_hamlet
/locate structure logos_dimension:journey_town
```

已有区块、旧聚落保持原样，需探索新区块。旧单块模板和旧起始池已移除，现有 locate ID 指向新结构。构建不需要原始蓝图；归一化几何和确定性资源脚本已随源码保存。

```text
python tools/settlements.py
python tools/audit_settlements.py
./gradlew runGameTestServer -PdimensionTests=logos_dimension:settlement_native_assembly
./gradlew runClient -PldTest=group:blueprint_settlements -PldTestHeadless -PldTestWindow=1600x900
```

实际截图为在生成地形上测试调用原版拼接器放置的村镇，验证结果见 [VERIFICATION.md](../VERIFICATION.md)。客户端测试世界默认关闭自然结构，因此不把这类预览称为自然生成。
