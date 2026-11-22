# 野花草甸

保留群系 ID `logos_dimension:cloudsea_rose_dunes`。在湿润、侵蚀较高的平地补入连续气候范围，地形继续使用原版噪声，不另写地形生成器。花带以奶白雏菊、蓝紫铃花为主体，少量银星莲花、霞穗柳兰与星露草点缀；短草、幼草填入剩余空隙。

## 花簇与贴图

- `journey_meadow_daisies`：原野雏菊花簇，浅暖白花瓣、淡金花心、深浅绿叶。
- `journey_meadow_bells`：原野铃花花簇，蓝紫垂铃与淡色花口，植株稍高。
- 每格两株，株高约 9–13 模型单位，两种株型、四向随机旋转。侧面交叉片表现茎叶，花顶另加水平花瓣片，俯看也有体积。春夏每格 12 个面，秋冬不画花顶，为 8 个面。
- 新增贴图全部 **16×16**，由 `tools/wildflower_meadow.py` 使用代码直接绘制。春夏开花，秋季变为淡褐种头，冬季保留带霜的茎叶；沿用现有四季状态与随机刻更新。
- 模型与贴图位于 `assets/logos_dimension/models/block/wildflower/`、`textures/block/wildflower/`，不改其他群系原有花卉。

## 原版生成

主花带使用 `minecraft:noise_provider`：同一片区域选择同类花，白花与蓝花按噪声过渡。每区块尝试 360 次；随后独立的 `wildflower_understory` 尝试 220 次，用草和少量点缀填空。两层只放在空位，沿已有地表找土壤，不削坡、不抬地、不覆盖石头或建筑。

连续气候主体为温度 `[-0.1,0.55]`、湿度 `[0.28,0.8]`、内陆度 `[0,1]`、侵蚀 `[0.45,1.2]`，覆盖整段山脊噪声。与长风草原较干的气候主体错开。保留原先的气候点和其他群系；世界测试预设同步。修改只影响新生成区块。

花簇继承原有植物的土壤支撑，挖去土壤即脱落，不可种在石头上。破坏掉落自身，进入创造模式的已有远行大陆方块目录；添加到 `logos:fragile_plants`、原版小型花与树木可替换植物标签，复用已有微光与萤火虫环境机制，没有新增每帧扫描。

定位：

```mcfunction
/execute in logos_dimension:cloudsea run locate biome logos_dimension:cloudsea_rose_dunes
/give @s logos_dimension:journey_meadow_daisies
/give @s logos_dimension:journey_meadow_bells
```

## 验证入口

```text
python tools/wildflower_meadow.py
./gradlew test build -PlogosJar=/absolute/path/logos.jar -PorchestraJar=/absolute/path/orchestra.jar
./gradlew runClient -PlogosJar=... -PorchestraJar=... -PldTest=group:wildflower_meadow -PldTestHeadless -PldTestWindow=1280x720
./gradlew runGameTestServer -PlogosJar=... -PorchestraJar=... -PdimensionTests=logos_dimension:plant_*
```

客户端分为真实原版放置特征的平地样本，以及未修改方块的自然花甸镜头。前者检查花草比例、空隙和土壤支撑；后者检查实际群系与原版噪声生成器。结果写入根目录 `VERIFICATION.md`，测试台截图不能当作自然景观。
