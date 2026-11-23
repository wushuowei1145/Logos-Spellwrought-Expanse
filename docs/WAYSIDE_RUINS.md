# 旅途小遗迹

远行大陆新增九类小型探索点。风格取自花藤爬满旧石、断裂构件与悬挂微光，轮廓围绕各自地形设计；没有搬用原版沉船、雪屋、沙漠神殿或地牢模板。

| 名称 | 适用环境 | 主要轮廓 |
|---|---|---|
| 花缠路碑 | 草原、野花草甸、林隙 | 高低错落的断碑、星花藤和偏置的悬灯 |
| 蕨影书龛 | 翳蕨密林、雾苔溪林、幽深阔叶林 | 缺了一角的书壁、凹龛、残廊与讲读台 |
| 古根摇篮 | 古冠山毛榉林、橡木林、桦林等 | 粗根围合的中空环座、垂灯与苔痕石圈 |
| 霜脊谐铃台 | 雪岭、雪峰、寒地针叶林 | 三根不同高度的叉柱、冰色晶簇与悬铃 |
| 长风残盘 | 长风草原、旷野丘陵、牧草坡地、高地草甸 | 断开的圆盘、四向嵌纹和不等高的方位石 |
| 望潮透镜座 | 白崖海岸、南方海湾、浅海沙洲 | 开口透镜框、氧化色拱冠与潮痕石面 |
| 芦汀汲月台 | 芦苇湿地、缓流河谷 | 开敞的汲月架、浅水钵与苔石台脚 |
| 断崖星纱门 | 裸岩山脊、高山河谷、峡谷、山地松林 | 偏置高低门肋、参差垂链与星纹吊坠 |
| 沉月镜庭 | 静水湖泊 | 湖底残环、嵌纹石面与低矮镜座 |

每类两套残损布局，共 18 份 NBT 模板，宽度大多 8–14 格，高度大多 6–10 格。第二套包含不同侵蚀、局部缺损或散落构件。全部模板的实体列表为空，不生成村民、动物或刷怪笼。

## 材料与光源

新增风蚀刻纹石、苔痕旧石、月纹嵌石、青玉遗灯、缠石星花藤。贴图均由 `tools/wayside_ruins.py` 直接代码绘制，分辨率为 16×16。石块保持封闭外表面，裂纹和刻纹画在贴图中，避免石面透空；小灯使用分件立体模型、原版方块照明，没有方块实体或每帧扫描。

星花藤采用原版藤蔓的附着与攀爬规则，无随机扩散；支撑面消失后按原版规则脱落。添加到 Logos 的脆弱植物标签，衰退法术可以正常作用于它。

## 生成与接地

生产生成全部使用原版 `minecraft:jigsaw`、模板池、`minecraft:random_spread` 与 `block_column`。九类共用一个结构集，网格间距 26 区块、最小分离 14 区块，避免九种独立网格叠加造成密集刷建筑。实际数量还取决于候选点群系、地形与原版结构有效性检查，不保证每 416 格必有一座。

起点采用 `OCEAN_FLOOR_WG` 高度图，不被树叶抬高；湖底类型落在水底。`terrain_adaptation=none`，不复制土台、不削坡、不抬高整块地形。承重石脚通过原版拼接挂接向下的方块柱，遇实地停止；地形较陡时可能出现较高石脚。

外部未写入模板的空位保留原地形。实际铺石上方的两格净空使用显式空气，保证通行与藏匣可见；沉月镜庭对应位置使用水，避免湖底出现空气泡。处理器仅忽略结构空位，不忽略空气。

## 探索与战利品

每座遗迹有 1–2 个原版奖励箱。花缠路碑、霜脊谐铃台、望潮透镜座、芦汀汲月台各一箱；蕨影书龛、古根摇篮、长风残盘、断崖星纱门、沉月镜庭各两箱。两种残损布局都保留奖励，18 份模板合计 28 个箱子；箱子不相邻合并，每个都有接地石脚和箱盖净空。湖底箱使用含水状态，可在水下直接开启。

每类使用独立的原版战利品表，首次开启生成内容，不能反复开启刷物资：

- 保底补给抽取 2–4 次：面包 2–5、烤马铃薯 3–7、火把 4–10、箭 4–10、纸 2–6 或书 1–2；这是每次抽中的数量范围，同一物品可能抽中多次。
- 每箱保底帝国铜分 4–16 枚。
- 地形小物件独立抽取 1–2 次，可能为空：森林偏荧光浆果、雪地偏紫水晶碎片、海岸偏海晶碎片、湿地偏玻璃瓶、湖底偏鹦鹉螺壳等。
- 稀有栏独立抽取一次：15% 获得前置 `logos:mana_shard` 1–2 个，5% 获得一颗原版金苹果，80% 为空。

没有重复注册前置材料，也不放高阶武器或大额货币。

新增内容只在新区块生成，不重写旧存档。可直接定位最近的一类：

```mcfunction
/execute in logos_dimension:cloudsea run locate structure #logos_dimension:wayside_ruins
/execute in logos_dimension:cloudsea run locate structure logos_dimension:wayside_bloom_waystone
```

其余结构 ID 为 `wayside_fern_scriptorium`、`wayside_root_cradle`、`wayside_frost_resonator`、`wayside_wind_compass`、`wayside_tide_lens`、`wayside_reed_siphon`、`wayside_ravine_thread`、`wayside_rain_memory`。

## 生成与验证工具

```text
python tools/wayside_ruins.py
python tools/audit_wayside_ruins.py
./gradlew test build -PlogosJar=/absolute/path/logos.jar -PorchestraJar=/absolute/path/orchestra.jar
./gradlew runGameTestServer -PlogosJar=... -PorchestraJar=... -PdimensionTests=logos_dimension:wayside_*
./gradlew runClient -PlogosJar=... -PorchestraJar=... -PldTest=group:wayside_ruins -PldTestHeadless -PldTestWindow=1280x720
```

模板对照镜头在独立测试台摆放，不能当作自然生成效果；`wayside_natural` 场景用实际定位、正常区块生成寻找一处真实遗迹。验证结果记录于根目录 `VERIFICATION.md`。
