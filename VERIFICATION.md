# 首版验证记录

## 合奏哑音、管风琴不出声与音高错乱（#243）

2026-11-11：同在分支 `feat/concert-hall`（未提交），接着上一节。原因和改法见 [docs/HEXENWALD_INSTRUMENTS.md](docs/HEXENWALD_INSTRUMENTS.md) 第 17 节。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全量 **360/361**，唯一失败的仍是 main 上就缺的 zh_cn `item.logos_dimension.fuda_desk`。新增 `MusicRollTest.wholePartsMoveByOctavesToFitTheInstrument`、`KeyLayoutTest` 2/2、`OrganSynthTest.octavePartialStaysBalancedAcrossKeys`（把相位换回随机时失败：晨祷相邻采样间差 23 dB；现在 < 3 dB） |
| 音高 | `tools/instrument_audit.py`：两层 29 项全部干净（取区都在 4 个半音以内，采样音准最大 20 音分） |
| 世界测试 | `instrument_*` **8/8**（服务端播放时钟改成真实时间后重跑） |
| 实际客户端 | **10/10、49/49 检查**：新增 `ensemble_resync_ui`（偏 0.4 秒的心跳不再掐掉大提琴的长音，偏 5 秒才重新定位）、`organ_reload_ui`（重启声音引擎后同一个管风琴键仍在响；把重启时丢缓冲那一行注释掉再跑，这一项失败——就是试玩时的现象），回归 `orchestra_seated_ui`、`orchestra_handheld_ui`、`concert_hall_pack_ui`、`handheld_play_ui`、`instrument_play_ui`、`mallet_instrument_play`、`midi_keyboard_ui`、`organ_play_ui` |
| 未验证 | 服务端真的卡顿、真的多人合奏没有试；心跳偏差和声音引擎重启是在测试里模拟的。音色仍需人工试听 |

## 音乐厅音源资源包与第六轮乐器

2026-11-11：分支 `feat/concert-hall`，基于 main `4a1cbccd`（#242），Logos jar `builds/shiraume-pact-r2-2026-11-10/logos-0.6.0-lasting-companion.jar`。说明见 [docs/INSTRUMENT_SAMPLES.md](docs/INSTRUMENT_SAMPLES.md)、[docs/HEXENWALD_INSTRUMENTS.md](docs/HEXENWALD_INSTRUMENTS.md) 第 16 节。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全量 **356/357**，唯一失败的仍是 main 上就缺的 zh_cn `item.logos_dimension.fuda_desk`；新物品、方块、实体的名字都齐。`SampleMapTest` 4/4 改成两层：模组本体 15 件采样乐器都有合成的表（synth），资源包 14 件录音（recorded，没有钢片琴）、`pack.mcmeta` 格式 84、带 `NOTICE.txt`；音域内每个音、每个力度都有正好覆盖的区，变调 ≤ 4 个半音，音量在 (0, 1]，只有弓弦、管乐循环 |
| 生成脚本 | 录音：14 件全部重做（表里加了 `format`、`quality`），新六件八度按 VSCO 的 SFZ，只取实测音分；低音提琴最低四个音基音太弱、量成低八度（按 SFZ 纠正，音分照用）；换层响度差钢琴 0.34 dB、竖琴 1.89、巴松管 1.97、其余 ≤ 1.13 dB；资源包 ogg 共 22.5 MB。合成：15 件共 3.43 MB，钢琴为不超满幅整体降 1.3 dB |
| 世界测试 | `instrument_*` **8/8**（新增 `instrument_seated_strings`：大提琴两格高、右键坐上、双脚正落在座位处、座位朝琴、有人坐着不多出座位、低音提琴两格高、定音鼓有方块实体、拆琴后座位消失人站起来；`instrument_music_stand` 加了三件新手持乐器），`creative_tabs_cover_items` **1/1** |
| 实际客户端 | 最后一轮新场景 `orchestra_views`、`orchestra_handheld_ui`、`orchestra_seated_ui`、`concert_hall_pack_ui` 加回归 `handheld_play_ui`：**5/5、34/34 检查**；回归 `music_stand_views`、`mallet_instrument_play`、`instrument_play_ui` 在前一轮（6/6、26/26 检查）通过。资源包场景把仓库里的 `resourcepacks/concert_hall` 装进测试客户端、启用：14 件读到录音、钢片琴仍合成，小提琴演奏条写「Sound: recorded」，卸下后全部回到合成。截图人工看过：坐姿、弓、左手、琴颈在头旁、乐谱架三种摆法、物品栏图标 |
| 改过的问题 | 玩家骑乘时自带 0.6 格挂点，座位起初会把人按进地里 0.6 格（改成座位位置 = 双脚）；英文的「Sound: concert hall recordings」在演奏条上和音域文字重叠（缩短，并在挤不下时挪到提示行）；第一人称巴松管的弯管朝外（改成同第三人称的摆法）；`SampleMapTest` 在测试 JVM 里找不到资源包目录（由 build.gradle 传路径） |
| 未验证 | 音色只能人工试听（试听在仓库外 `builds/concert-hall-2026-11-11/demo/recorded`、`demo/synth`）；双簧管第一人称大半被演奏条挡住；多人、专用服务器、光影包没测；座位不存盘 |

```text
uv run --no-project --with numpy --with scipy python tools/instrument_samples.py --ffmpeg <ffmpeg> --demo <试听目录>/synth
uv run --no-project --with numpy --with scipy python tools/instrument_sources.py --cache <下载目录> --ffmpeg <ffmpeg> --demo <试听目录>/recorded
uv run --no-project --with pillow python tools/instrument_art.py
./gradlew test -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:instrument_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=orchestra_views,orchestra_handheld_ui,orchestra_seated_ui,concert_hall_pack_ui,handheld_play_ui" -PldTestHeadless -PldTestWindow=1280x720
./gradlew concertHallPack
```
## 乐器换用录音采样

2026-11-11：分支 `feat/recorded-samples`，基于 main `7a98e0e0`（#241），Logos jar `builds/shiraume-pact-r2-2026-11-10/logos-0.6.0-lasting-companion.jar`。说明见 [docs/INSTRUMENT_SAMPLES.md](docs/INSTRUMENT_SAMPLES.md)、[docs/HEXENWALD_INSTRUMENTS.md](docs/HEXENWALD_INSTRUMENTS.md) 第 15 节。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全量 **350/351**，唯一失败的仍是 main 上就缺的 `item.logos_dimension.fuda_desk`。新增 `SampleMapTest` 4/4（音域内每个音、每个力度都有正好覆盖的区，变调 ≤ 4 个半音，音量在 (0, 1]；文件、循环点、松键声都在；只有手持乐器循环；区的选取与音量公式）；删去 `HeldSynthTest`（合成器已去掉） |
| 生成脚本 | 每个采样实测八度与预期一致（钢琴最低几层的 C1 基音太弱、量成低八度，钢琴本来就用调律表，不受影响）；钢琴 C8 实测比调律表高约 60 音分，高音区改用实测；换层响度差钢琴 ≤ 0.34 dB、竖琴一个音 1.9 dB、其余 < 1 dB；同样的输入重跑，ogg 与采样表字节不变 |
| 循环接缝 | 60 个循环区解码后检查：绕回那一步 ≤ 两边第 99 百分位一步的 1.16 倍；解码长度都盖过循环终点 |
| 试听渲染 | 九件乐器的音阶实测音准 ±5 音分内（音头揉弦处最多 14 音分），峰值、响度正常 |
| 世界测试 | `instrument_*` **7/7**、`organ_*` 4/4、`creative_tabs_cover_items` 1/1 |
| 实际客户端 | 乐器相关的 11 个场景 **11/11、27/27 检查**；`handheld_play_ui` 新增：按住长笛一个音 7 秒（比采样长）仍在响、OpenAL 上设了循环点，松开后停下 |
| 未验证 | 音色只能人工试听（试听在仓库外 `builds/recorded-samples-2026-11-11/demo`）；多人、专用服务器、光影包没测；OpenAL 不支持循环点的声卡驱动上只会打日志、长音到采样结束为止（没有这样的环境可测） |

```text
uv run --no-project --with numpy --with scipy python tools/instrument_sources.py --cache <下载目录> --ffmpeg <ffmpeg> --demo <试听目录>
./gradlew test -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=instrument_play_ui,mallet_instrument_play,handheld_play_ui,organ_play_ui" -PldTestHeadless -PldTestWindow=1280x720
```
## 太玄灵境重做：删去城池，浮空宗门、深渊、链台与镇渊柱

2026-11-11：分支 `feat/taixuan-sanctuary`（#218），先在 `feat/cloud-peaks` `f69f90f7`（#233）上做，#233 以 `0e9ec038` 压缩合并以后 rebase 到 main `7a98e0e0`（#241）。rebase 前 Logos 用 page-variants 的 jar（Logos #422）；rebase 后 main 的白梅同伴要 Logos #423，改用 `builds/shiraume-pact-r2-2026-11-10/logos-0.6.0-lasting-companion.jar`（含 #422、#423）。Java 25。改动和原因见 [太玄灵境](docs/TAIXUAN.md)。

| 层级 | 结果 |
| --- | --- |
| 模板审计 | `python tools/audit_taixuan.py` 通过。见下文「模板审计」 |
| 单元 | 全量 **315/316**，rebase 后 **358/359**，唯一失败都是 main 上本来就有的 `LangCoverageTest`（`fuda_desk` 缺中文物品名），新方块的名字都齐。rebase 后重新跑 `tools/taixuan.py`，生成结果和提交里的一模一样。`TaixuanDataTest` **6/6**（重写）：拼接与模板文件齐全；选址参数盖住整座地标；雾底、雾层、链台、深渊口高度由低到高、画布在世界高度内；路线、瀑布、镇渊柱都记进 manifest；树的放置规则都带 `no_tree_roots`，标签里不再有 `minecraft:chain`；崖壁松、崖壁灌丛、崖壁藤蔓带 `built_surfaces` 一道，而且天然崖壁的材料不在这个标签里；测试预设与正式维度一致 |
| 世界测试 | `taixuan_*` **4/4**，见下文「世界测试」。相关的 `creative_tabs_*` **1/1**（新方块都在创造栏里）、`cloudsea_*` **4/4**。rebase 后三组重跑：**4/4、1/1、4/4**。按测试范围的约定，没有跑全部世界测试 |
| 实际客户端 | `group:taixuan` **8/8**（选址放宽后重跑一次，仍是 8/8）：宗门全景、深渊口、链台、雾层、镇渊柱与铁链、雾底场地、栈道、浮岛底下。1600×900，不装 Sodium、Iris，截图已人工查看。前两轮看出来、已经改掉的问题，见下文「实际客户端」 |
| 自然生成勘察 | `taixuan_survey_0`、`_1`：全新世界、打开结构生成，只把玩家送过去，区块按正常流程生成。两处都自然生成了，1357 个部件齐全；瀑布没断；深渊里没有残留地形；结构上没有长出外来的树。路线：第二处全部能走；第一处有 **2 格**树叶挡路（镇渊柱楼梯井一格、柱顶桥连山门峰那一头一格），两次重跑位置相同，是区块交界处装饰先于隔壁结构的问题，记为已知问题。第一次勘察时还有十几格：崖壁松和崖壁灌丛长进楼梯井、贴着桥长出来；藤蔓爬满镇渊柱外墙和铁链。加了 `built_surfaces` 一道之后降到这 2 格。256 个区块生成约 84 秒，同大小对照区 27～43 秒 |
| 选址统计 | `taixuan_site_stats`（31104×31104 格、6561 个候选点，只读噪声）：中心落在云岫峰林的 97 处。按第一版的检查只有 4 处，最近两处中位约 14400 格；现在的参数加「一片只留一处」是 **11** 处，最近两处 p10 约 700、中位约 1150 格，600 格以内 0 对。只放宽、不去重时同样范围 16～32 处，其中 10～27 处挨着另一处 |
| 未验证 | Sodium、Iris 与光影包（渊雾按全亮画和半透明在光影下的样子、雾远近调整有没有被光影包的雾盖掉）；独立多人服务器；真人试玩；Boss（只做了场地）；低配机器上的生成耗时；已经生成过的旧区块；全部世界测试（只跑了相关的） |

### 模板审计

- **规模**：891 个 28³ 部件，890 条唯一拼接连接，466 个接地石基入口；模板方块 7,467,801 格，其中显式空气 6,456,583 格。`structure/taixuan` 约 20 MB（上一版 12 MB），涨的部分是深渊口外的空地。
- **路线**：18 条共 1,060 格都能走：脚下有碰撞，头顶两格是空气、渊雾或垂藤；相邻两格高差不超过一格。
- **水和雾**：两道瀑布从泉眼、横流、落水柱到接水池都封住。渊雾 176,368 格，都在雾层高度和深渊半径以内。
- **依附与地面**：花草都长在土上，钟乳石上面有支撑，吊灯、立灯和香炉都有依托；空地地面的记号方块只在口沿下一格那一层。
- **前几轮查出来、已经改掉的问题**：
  - 折桥拐角一段的栏板压在另一段的桥面上；
  - 栈道内沿栏杆随螺旋半径起伏，落到了走道中间；
  - 门改朝深渊以后，镇渊柱顶的香炉堵住了楼梯口；
  - 雾底断柱顶缺块，魂灯悬空；
  - 吊桥扶手和 `no_tree_roots` 标签用了 26.1 没有的 `minecraft:chain`。

### 世界测试

- **`taixuan_native_assembly`**：种子 0 和 81 都是 891 个模板加 466 个接地特征，范围 y −24～283，锚在设计高度 y=120。第一次锚在 119：原版把起点部件按地面差往下挪一格，起始高度已改成 121。
  - 新加的自适应石阶最初有 10 个石基入口落在模板顶层，接地子件会落进上面那块模板，被原版拼接扔掉（456/466）。现在这种入口往下挪一格，原来那格补石头。
- **`taixuan_template_blocks`**：904 个模板文件里的方块 ID 都在 26.1 方块注册表里。
- **`taixuan_natural_habitat`**：真实候选点（锚 96, 120, −2304，转 90°）。
  - 半径 112 格（深渊口外的空地）内每 8 格采样，云岫峰林 **613/613**；放宽前按半径 126 量也是 774/774。
  - 600 格以内没有第二处会生成。
  - 半径 72 格一圈的原地面比口沿：最低 −7，中位 +22，最高 +102。最高的那些是峰林的石柱，已经被空地清掉。
- **`taixuan_abyss_and_sect`**：在同一处实际放下以后，以下都通过：
  - 所有路线、两道瀑布、雾层、链台的太极和雾底的玄铁心；
  - 房间、台基、23 棵崖松和一处水潭；
  - 深渊竖井里没有残留地形。

### 实际客户端

前两轮截图看出来、已经改掉的问题：

- 镇渊柱埋在天然石柱里，只露出柱顶的亭子；东殿峰底下顶着一根石柱；深渊口外比口沿高几十格，深渊口机位落在石头里。改成清出空地，空地里削平的那一圈换成草坪。
- 渊雾在深渊里看着是一片灰石头。改成按全亮画，并且更透明。
- 铁链和链台在浮岛底下一片漆黑。玄铁调亮，并且微微发光。

## 手持乐器与乐谱架：小提琴、长笛、王室小号

2026-11-11：分支 `feat/handheld-instruments`，基于 main `a8540e68`（#240），Logos jar `builds/shiraume-pact-r2-2026-11-10/logos-0.6.0-lasting-companion.jar`。说明见 [docs/HEXENWALD_INSTRUMENTS.md](docs/HEXENWALD_INSTRUMENTS.md) 第 14 节。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全量 **352/353**，唯一失败的仍是 main 上就缺的 `item.logos_dimension.fuda_desk`。新增 `HeldSynthTest`：循环接缝连续、音准误差 < 0.1 音分、长笛音高、响的一层高次谐波更多、起音淡入、三件都是手持且运行时合成 |
| 世界测试 | `instrument_*` **7/7**（新增 `instrument_music_stand`：空架子不能演奏；放上小提琴后方块状态与乐器种类都是小提琴；装纸带、红石启动；拆掉时小提琴、纸带、架子各掉一件）；`creative_tabs_cover_items` 1/1 |
| 实际客户端 | 新增 `music_stand_views`、`handheld_play_ui`，连同钢琴、管风琴、第二批方块乐器、电子琴共 **7/7、21/21 检查**：三件乐器各举起来按三个键，本地 ≥3 路在响、跟着自己，关掉演奏条后放下；模拟另一人手上小提琴的一批音在那人位置响 ≥2 路，收起时全部停下 |
| 截图人工看过 | 四个乐谱架正面、侧面、小提琴近景；三件乐器第一人称、斜前方左右、侧面。改过：小号架子起初是缺模型的方块（旗子贴图在物品图集，方块模型用不了，移到方块贴图）；架子上的小提琴太大（缩到 0.72）；演奏时只抬了一只手、乐器飘在头上（改为玩家层按 `HeldRig` 把乐器画在下巴、嘴边，双臂指向乐器上的手位）；演奏条的字压在框上、条太高挡住人（框改细、条改矮）；第一人称乐器被演奏条挡住、小提琴琴头朝错方向（抬高、重算转角，弓横搭在弦上） |
| 未验证 | 音色只能人工试听（`builds/handheld-instruments-2026-11-11/demo`）；两个真实客户端、专用服务器、光影包没测 |

```text
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:instrument_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=music_stand_views,handheld_play_ui" -PldTestHeadless -PldTestWindow=1280x720
```

## 第二批方块乐器：钟琴、钢片琴、管钟、大键琴

2026-11-11：分支 `feat/mallet-instruments`，基于 main `6a1642be`（#239），Logos jar `builds/shiraume-pact-r2-2026-11-10/logos-0.6.0-lasting-companion.jar`。说明见 [docs/HEXENWALD_INSTRUMENTS.md](docs/HEXENWALD_INSTRUMENTS.md) 第 13 节。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全量 **347/348**，唯一失败的仍是 main 上就缺的 `item.logos_dimension.fuda_desk`。新增 `MusicRollTest.everySampledKindHasItsSamples`：六种用采样的乐器，音域内每个音取到的采样都在 ±1 半音内，每个采样的 ogg 与 `sounds.json` 条目都在；大键琴不感力度、钢片琴用踏板、钟琴不用 |
| 采样 | 量过频谱：钟琴 F♯5 最强 740 Hz、C8 4186 Hz；钢片琴 C4 261 Hz；管钟 F♯3 基音 185 Hz 最强（起初基音拆成两支互相抵消，三次模态反而最强，已改为基音保持一支）、A4 440 Hz；大键琴 B2 247 Hz，F4 的二次谐波最强、基音也在（8′+4′ 的大键琴本来如此） |
| 世界测试 | `instrument_*` **6/6**（新增 `instrument_new_shapes_break_whole`：钟琴一格有方块实体；管钟两格、拆上半整体消失；大键琴琴尾在键盘后面、只有主块有方块实体、拆琴尾整体消失；各只掉一件）；`creative_tabs_cover_items` 1/1 |
| 实际客户端 | 新增 `mallet_instrument_views`、`mallet_instrument_play`，连同钢琴、管风琴的三组共 **5/5、7/7 检查**：大键琴演奏页按三个键有三路在响、松开后制音落下全部停止 |
| 截图人工看过 | 一排正面、侧面、钟琴与钢片琴近景、物品栏；改过钢片琴顶面（全金太重，改为胡桃木顶 + 细金边）、正面镶嵌板（被截掉一半，改为方形整张）、管钟铜管（太密像帘子，改细、留缝） |
| 未验证 | 音色只能人工试听（试听音阶在仓库外 `builds/mallet-instruments-2026-11-11/demo`）；多人、专用服务器、光影包没测 |

```text
uv run --no-project --with numpy python tools/instrument_samples.py --ffmpeg <ffmpeg> --only glockenspiel
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:instrument_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=mallet_instrument_views,mallet_instrument_play" -PldTestHeadless -PldTestWindow=1280x720
```

## 电子琴（MIDI 键盘）输入

2026-11-11：分支 `feat/midi-keyboard`，基于 main `9001a6a0`（#238），Logos jar `builds/shiraume-pact-r2-2026-11-10/logos-0.6.0-lasting-companion.jar`。说明见 [docs/HEXENWALD_INSTRUMENTS.md](docs/HEXENWALD_INSTRUMENTS.md) 第 12 节。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全量 **346/347**，唯一失败的仍是 main 上就缺的 `item.logos_dimension.fuda_desk`。新增 `MidiInputTest` 5/5：按下与力度 0、松开、任意通道；踏板阈值、CC120/123、忽略音量旋钮、Program Change；忽略时钟、主动感知、SysEx、触后、弯音和数据字节；通道过滤、移调越界丢弃；三种力度曲线的大小顺序与边界 |
| 世界测试 | 没有服务端改动；`instrument_*` 5/5、`organ_*` 4/4、`creative_tabs_cover_items` 1/1 回归 |
| 实际客户端 | 新增 `midi_keyboard_ui`，连同乐器的六组 **7/7、13/13 检查**：消息直接塞进接收器；没打开乐器时被丢掉；踏板 + 三个音让琴键亮起、声音引擎里 ≥3 路在响；力度 0 的按下、松开都能松开；录下的纸带力度 100/80/60、两次踏板；「轻」曲线下 40 → 63；设置页显示「没有找到 MIDI 输入设备」和收到的消息 |
| 截图人工看过 | 演奏页（C4、E4、G4 亮着、延音●、录制中、MIDI 键盘按钮）、设置页 |
| 未验证 | **没有真的电子琴**：开发机只有 Windows 自带的输出合成器（Gervill、MIDI Mapper、GS Wavetable）；真实设备的连接、拔插、macOS/Linux 都没试 |

```text
./gradlew test --tests "dev.logos.dimension.music.MidiInputTest" -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=midi_keyboard_ui" -PldTestHeadless -PldTestWindow=1280x720
```

## 修复：时好时坏的世界测试（腐败两项、狐火缠身、精灵世界树光照）

2026-11-11：分支 `fix/flaky-gametests`，基于 main `b70ec0f5`（#235），Logos jar `builds/shiraume-pact-r2-2026-11-10/logos-0.6.0-lasting-companion.jar`。只改了测试代码，玩法代码没动；`build.gradle` 加了一个查偶发失败用的开关 `-PdimensionTestRepeat`。

原来猜的「相邻的腐败测试互相蔓延」不成立：26.1 按测试环境分批，这几项各有自己的环境，所以各是一批，一批跑完才跑下一批；每个测试在格子上占一个新位置（不清空、不复用），起点和批次顺序每次随机，所以测试场地和区块边界的相对位置每次都不一样。几项失败都出在测试自己身上：

| 测试与失败消息 | 原因 | 改法 |
| --- | --- | --- |
| `corruption_core_grows_with_network`：「网络 107 块时阶段应当是 0，实际 1」 | 核心自己撒的 24 点初始电荷。光标在放好的污秽上游走，走到边上时，脉络照样爬上铺的黑曜石、绕到黑曜石底下（黑曜石只是转化不了），脉络也算网络的方块。加日志跑到一次失败：前 10 刻正好 99 块，之后 105、107、112……多出来的全是污秽外圈黑曜石上面和底下的脉络，光标钻到了地面底下 | 不铺黑曜石，污秽悬空放，上下四周各留一格空气。脉络不贴污秽和核心，周围没有别的面可贴，电荷只能在污秽上耗光，网络大小正好是放的块数 |
| `corruption_full_network_splits`：「种满后杀生的电荷应当交给子核心：之前 120，之后 120」 | 测试在同一刻里强制加载场地、铺地、喂电荷。`setChunkForced` 对已经加载的区块要到下一刻处理票时才生效，离测试原点两个区块的场地角落在这一刻还不走方块刻。子核心种在角上，同族分电荷只挑在走刻的网络，三个子核心都落在这种区块里时，第 4 次的电荷就进了储备（原点 x、z 除以 16 的余数都不小于 8，且没有子核心种在原点那个角，约 1/16） | 先等这片区块都走方块刻，再在同一刻里铺地、喂电荷。不能隔一刻，否则满了的母网络会先拿初始电荷自己种一个子核心 |
| `foxfire_burns_then_goes_out`：「三秒缠身应当烧掉 3 点，实际 0.0」 | 缠身在被烧的生物走实体刻时才烧。测试框架只强制加载一格测试结构所在的区块，隔壁区块只走方块刻、不走实体刻。铁傀儡站的 (2, 1, 3) 在原点 x 余数不小于 14 或 z 余数不小于 13 时落在隔壁区块（约 29%），一刻也不走 | 先强制加载尸壳和铁傀儡所在的区块，等它们进入实体走刻（`RealTimeWait`）再放生物、点火。这一项的时限从 200 刻改成 600 刻 |
| `elven_living_world_tree`：等光照超时 | 6000 刻的时限按刻算，全套测试时一两秒就空跑完了，光照还在后台线程排队 | 等光照那一步包上 `RealTimeWait.paced`，和 #199 的做法一样（以前的草稿分支 `fix/gametest-tick-budget` 也这样改过） |

| 层级 | 结果 |
| --- | --- |
| 改前改后对照 | 用 `-PdimensionTestRepeat=50` 把一项复制 50 份放进同一批，散在格子的不同位置一起跑。`grows`：改前 **14/50** 失败，改后 **0/50**。`splits`：50 份的子核心彼此隔不开 16 格，第 3 个种不下，所以只为这次对照临时把腐败测试的留白从 16 格加到 32 格，改前 200 份失败 **13 份**（6.5%，消息都是「之前 120，之后 120」），改后 **0/200**。狐火缠身：改前 **6/50**，改后 **0/50**。另外改前单跑狐火缠身 10 次失败 1 次，正好是 10 次里唯一一次铁傀儡落在隔壁区块的那次 |
| 验收 | `corruption_*` 连跑 10 次 **10/10**（每次 16/16）。狐火缠身单跑 10 次 **10/10**。全部世界测试跑一次 **249/249**（3.7 分钟；`elven_living_world_tree` 通过，连放树带等光照用了 25 秒，树心最暗的一格亮度 9） |
| 测试的坑 | 超平坦测试世界的地面在 y = -61，测试场地都悬在空中；黑曜石、石头这类转化不了的方块，脉络照样能贴上去。测试框架只强制加载测试结构那一格所在的区块：隔壁区块只走方块刻、不走实体刻，隔两个的只是加载着。自己 `setChunkForced` 的区块，要是本来就加载着，下一刻才开始走刻。`-PdimensionTestRepeat` 的复本在同一批里一起跑：会看全维度所有核心的（子核心的间距）、会扫到隔壁的（狐火横扫）测试不适合这样跑 |
| 未验证 | 失败率是在这台机器上数的，CI 上没有跑；全部世界测试只跑了一次；世界树光照超时没法按需复现，没有改前改后对照；单元测试没有重跑（只改了世界测试和 `runGameTestServer` 的参数） |

```text
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:corruption_*"
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:foxfire_burns_then_goes_out"
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:corruption_core_grows_with_network" -PdimensionTestRepeat=50
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar
```
## 教堂管风琴（第一版）

2026-11-11：分支 `feat/pipe-organ`，基于 main `b70ec0f5`（#235），Logos jar `builds/shiraume-pact-r2-2026-11-10/logos-0.6.0-lasting-companion.jar`。设计与实现说明见 [docs/PIPE_ORGAN.md](docs/PIPE_ORGAN.md)。演奏台（2×2）、风箱、锡音管、木音管、号角管；十个音栓（预设 + 自由开关，全开也只占一路声音）；音色在客户端合成，不放 ogg；混响按房间自动判断，另有关 / 小厅 / 教堂三档。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全量 **338/339**，唯一失败的仍是 main 上就缺的 `item.logos_dimension.fuda_desk`。新增 `OrganSynthTest` 6/6：四种组合的 25 个循环段首尾都接得上、约 1 秒；C1–C7 每个音校正后误差小于 0.1 音分、变调在 ±1 半音内；长笛 8′ 中央 C 的最强频率就是 261.6 Hz；尾音 2 秒衰减约 60 dB、开头淡入；材质与音栓、预设；露天没有混响、小屋 < 大厅 < 教堂、羊毛房间短而暗、「自动」遇到 Sound Physics 让开、手动档照常 |
| 世界测试 | 新增 `organ_*` **4/4**：演奏台四格（第三格在主块正上方）、拆右上一块整体消失、只掉一个演奏台；锡管叠三格成下 / 中 / 上，拆中间后两头变短管，木管叠在锡管上不连；四个相连的风箱、锡 7 木 2 号角 1 根，可闻 26 格，不相连的风箱和音管不算；没有音管时只接受长笛 8′，搭上锡管后全奏只留锡管的四个音栓、可闻 18 格。`instrument_*` 5/5、`creative_tabs_cover_items` 1/1 回归通过 |
| 实际客户端 | 新增 `organ_views`、`organ_play_ui`，连同钢琴竖琴的四组共 **6/6、7/7 检查**：客户端识别到锡 69、木 26、号角 2；石砌玻璃顶大厅里自动混响约 1.4 秒，掀掉屋顶和墙后量出露天、没有混响；音栓页切全奏后客户端合成出这套组合；按住四个键 1.5 秒后声音引擎里仍有 4 路在响（循环段确实交给了 OpenAL 并在循环），松开后有尾音 |
| 截图人工看过 | 大厅全景、演奏台近景（两层手键盘、音栓拉钮、脚键盘、镶嵌板）、侧面、物品栏、音栓页、演奏页。截图在仓库外 `builds/pipe-organ-2026-11-11` |
| 未验证 | 音色与混响只能人工试听；多人同步没用两个客户端连同一服务器试过；专用服务器、光影包、Sound Physics Remastered 实装都没测 |

```text
./gradlew test --tests "dev.logos.dimension.music.organ.*" -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:organ_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=organ_views,organ_play_ui" -PldTestHeadless -PldTestWindow=1280x720
```
## 白梅装束：浴衣「梅灯夜渡」

2026-11-11：分支 `feat/shiraume-outfits`，基于 main `b70ec0f5`（#235）。Logos jar 同「白梅同伴（第二版）」。模型是仓库外 `fox_model/nine_tailed_fox_companion` 的 `_9`（维护者画的浴衣 `skins/umeakari` 按规范整理，见那里 README「第 9 版」）；设计见 [白梅同伴](docs/SHIRAUME_COMPANION.md) 第 8.10 节。试玩包 `builds/shiraume-outfits-2026-11-11`。

| 层级 | 结果 |
| --- | --- |
| 模型 | `tools/validate_companion.py` **18/18**（贴图 1 纹素/单位、方块名唯一、动画骨骼都在、导出一致等）。原稿三处不合规范：两套衣服同名骨骼、贴图约 8–16 纹素/单位、49 个方块重名；整理后 1027 块、273 根骨骼、55 段动画，贴图 128×141 |
| 单元 | 新增 `CompanionOutfitTest` **3/3**（两套装束的组挂在同一组衣服骨骼下、隐藏别的装束不碰共用骨骼、梅灯梅花簪在浴衣组里且有摆动）；`CompanionPactTest` 12/12、`CompanionTest` 6/6、`NpcAnimationsTest` 5/5（立契的刻数和新动画文件的标记仍然一致） |
| 实际客户端 | `shiraume_companion` **15/15 场景、44/44 检查**；新增 `companion_outfit`（平时巫女服；泡温泉换上浴衣，客户端也是；出浴后还穿着；尸壳冲她来就换回巫女服；命令能换）。打两只神狐这一轮 2/3 |
| 问题与修正 | 立契过场机位二又被挡：测试里主人手是空的，献上的流火直接塞到手上，发光的刀横在镜头前。献上的东西改放背包里手上以外的空格，重跑 `companion_pact`、`companion_pact_again`、`companion_outfit` 3/3（17/17 检查），机位二只拍到她和形代 |
| 截图人工看过 | 浴衣站姿（藏青底白梅点、青绿腰带红腰绳、梅灯、木屐红鼻绪、梅花簪）、泡温泉换装的花瓣、巫女服预览与第 8 版一致；离线预览含原稿对照 |
| 没做 | 花纹按 1 纹素/单位重画的样子待维护者确认；夏夜祭典没有；多人、光影包没测 |

## 魔女之森乐器（第一版）

2026-11-10：分支 `feat/hexenwald-instruments`，在 main `64d4481f`（#232）上做完后 rebase 到 main `f3ea2b44`（#234），下表是 rebase 后重跑的结果，Logos jar `builds/shiraume-pact-r2-2026-11-10/logos-0.6.0-lasting-companion.jar`（main 合了 #234，要用这个构建）。设计与实现说明见 [docs/HEXENWALD_INSTRUMENTS.md](docs/HEXENWALD_INSTRUMENTS.md)。星辰花三角钢琴（2×2）、灵猫竖琴（两格高）、空白纸带与打孔纸带；采样全部由 `tools/instrument_samples.py` 合成（钢琴 30 个、竖琴 27 个，约 1.6 MB）。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全量 **332/333**，唯一失败的仍是 main 上就缺的 `item.logos_dimension.fuda_desk`。新增 `MusicRollTest` 6/6（编码往返、排序、上限、逐字节截断与伪造长度、乐器音域与取样不超过 ±1 半音）、`MidiReaderTest` 5/5（速度表变化、运行状态、力度 0 当松开、踏板、打击乐提示、出错报出轨道与事件、伪造长度与格式 2、事件上限截断、SMPTE 时间码） |
| 世界测试 | 新增 `instrument_*` **5/5**：钢琴四格的 part 与主块、只有主块有方块实体，拆琴尾一块整架消失、掉一架钢琴和槽里的纸带；竖琴拆上半整个消失；复制配方（一卷 + 两张空白 → 两卷同内容，原卷留下）；纸带存档读写与临时纸带；红石上升沿开始、默认失去信号不停、「信号停止时停止」、上半格旁的信号也算。`creative_tabs_cover_items` 1/1 |
| 实际客户端 | 新增 `instrument_views`、`instrument_play_ui`、`roll_editor_ui`、`midi_import_ui` **4/4**：认领演奏者、按住 Z C B 弹 C–E–G、切纸带页播放后服务端在放且客户端跟上纸带；编辑器读入 18 个音；往 `logos_music/` 写一个四轨小 MIDI，选中后预览两个声部并提示打击乐没导入 |
| 截图人工看过 | 正面、侧面（两种朝向）、竖琴近景、俯视琴键（黑键 2+3 分组）、物品栏图标、演奏页、纸带页、编辑器、导入页。截图和试听音阶在仓库外 `builds/instruments-2026-11-10` |
| 未验证 | 音色只能人工试听；多人同步没用两个客户端连同一服务器试过；专用服务器、光影包没测 |

```text
./gradlew test --tests "dev.logos.dimension.music.*" -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:instrument_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=instrument_views,instrument_play_ui,roll_editor_ui,midi_import_ui" -PldTestHeadless -PldTestWindow=1280x720
```

## 白梅同伴（第二版）试玩后：出刀、打神狐、立契过场、深温泉、狐火灯

2026-11-10：分支 `feat/shiraume-pact`。提 PR 时第一版（#229）和 Logos 的长期同伴接口（MagicFoxDemon/MinecraftLogosMod#423）都已合并，改成直接基于 main `0e9ec038`（#233）重建；形代按 #232 的分类放进「道具」页。重建后全量单元测试 321/322（唯一失败同前，main 上的 `fuda_desk`），`creative_tabs_cover_items` 通过，`shiraume_companion` 14/14 场景、40/40 检查，打两只神狐三轮全赢。Logos jar 同下一节。按试玩反馈改：突刺一直刺、沿用 Boss 出招太慢打不过两只神狐、机位二被自己挡住、台词生硬（改成谢罪、愿做式神，加发抖）、一格半深的露天风吕里被淹、狐火灯改成飘在身边的淡粉狐火。设计见 [白梅同伴](docs/SHIRAUME_COMPANION.md) 8.1、8.1.1、8.4、8.6、8.9 节。试玩包 `builds/shiraume-pact-r2-2026-11-10`。

| 层级 | 结果 |
| --- | --- |
| 单元 | `CompanionPactTest` **12/12**，新增：立契两个机位看她胸口、头、手的视线不穿过主人（主人在 1.6–4.5 格，按连胳膊宽 1 格算；旧机位二在 1.5 格处被挡住）、突刺冲的那一段同 Boss 的 lunge、每一招平均每砍中一下不超过半秒、基础一刀等于玩家流火的一刀（9）。`CompanionTest` 6/6、`MovesetTest` 14/14、`ParryTest` 8/8、`RyukaTest` 5/5、`NpcAnimationsTest` 5/5 |
| 实际客户端 | `shiraume_companion` **14/14 场景、40/40 检查**（最后的代码完整跑一遍）。改动的场景：`companion_melee`（60 血尸壳，约 6 秒砍倒，突刺 1 记、别的招式 3–4 串）；`companion_vs_foxes`（新：两只打刀铁甲的寻常神狐一起上，连打三轮至少赢两轮）；`companion_pact`（主人站两格外不挪；等你时在发抖，递形代时不抖了）；`companion_pact_again`（贴着她说话先退开到 2 格）；`companion_soak`（含水半砖上一格水的深池：脚底比池底高半格、水面在肩头，泡过呛水的 15 秒还在泡、没起身、没呛水；主人换到浅池，她起身过去坐在池底泡）；`companion_lantern`（屋里飘起狐火，客户端亮度到 0.9 以上） |
| 打两只神狐 | 主人没穿装备，她只有基础数值（46 血）。只改出刀速度时一轮都没赢（刀伤 8.7，打一只铁甲神狐只剩一半多，一半被弹开还挨反刺；血少时转身放狐火，神狐不怕狐火、放的时候又挡不了）。加上弹开就反击、对弹开过她的少出刀多守、先打伤重的、对神狐不放狐火、血少时凝刀守着、一刀改成 9 × 狐火倍率（13）之后，三次各三轮：**3/3、2/3、3/3**（剩 11–46 血；输的那一轮也砍倒了一只） |
| 问题与修正 | 测试场地复原用 `removeBlock`，水源会留下：泡温泉场景的池水漫过了下一个场景（第一次打神狐是在水里打的，作废），改成换空气。站在地上的主人脚底陷进地面 5e-9，挪开前的碰撞检查总是不过，盒子收一点再量。机位二第一次改到侧面 60° 时只拍到头发和耳朵，再改到侧前方 34°。狐火灯第一版太白，缩小、白芯调淡 |
| 截图人工看过 | 机位一献刀（主人在画面右边缘，没挡她）、机位二递形代和字幕（四分之三正面、脸和形代都在）、深池里水面在肩头、尾巴浮在水面、狐火灯绕到身侧和身后、两只神狐对打 |
| 没做 | 多人、光影包没测；`group:logos` 和 Boss 战长场景没跑；模型没动（还是 `_8`） |

## 白梅同伴（第二版）：立契、流火、日常

2026-11-10：分支 `feat/shiraume-pact`（叠在第一版 #229 上，main `34c49450`）。Logos jar 用 `builds/shiraume-pact-2026-11-10/logos-0.6.0-lasting-companion.jar`（Logos PR #423，基于 Logos main `ed2b403`，含 #422）。设计见 [白梅同伴](docs/SHIRAUME_COMPANION.md) 第 8 节；模型是仓库外 `fox_model/nine_tailed_fox_companion` 的 `_8`。

| 层级 | 结果 |
| --- | --- |
| 单元 | `CompanionTest` **6/6**、`CompanionPactTest` **8/8**（好感与契印、截弹射物的判断、招式文件与动画、立契和吃东西的刻数与动画标记）；`NpcAnimationsTest` 5/5、`MovesetTest` 14/14、`ParryTest` 8/8、`RyukaTest` 5/5。`LangCoverageTest` 1/2：只缺 `item.logos_dimension.fuda_desk` 的中文，main 上就是这样（#220），新加的物品、台词中英文都有 |
| 实际客户端 | `shiraume_companion` **13/13 场景、31/31 检查**（最后一轮完整跑）：第一版四个照旧通过；新增 `companion_boss_reward`（命令召出的狐形白梅被打倒后，花瓣聚回少女、跪坐着等这位玩家，场地中央不再掉流火）、`companion_pact`（只同步给本人的跪坐分霊；说话后遮幅过场、字幕；流火和写着名字的形代到手；立契后记在契上，Logos `companionOf` 是她且没有时限）、`companion_pact_again`（已立契的人得到空白形代、那位化作花瓣带魂火回去；空白形代盖一枚契印）、`companion_melee`（尸壳贴近凝刀、挥刀、砍倒后收刀，客户端刀也散了）、`companion_guard`（不动的骷髅朝主人射一箭，她闪过去弹开，把射手定为目标并打中）、`companion_execute`（主人的流火印在劫掠兽身上爆开，她上挑接处决，用掉这次爆开）、`companion_feed`（喂甜浆果、拒吃腐肉、饿了自己摘浆果不被扎、多的进包袱）、`companion_soak`（主人泡在地热石上方的水里，她下到旁边一格深的温泉里泡）、`companion_lantern`（露天不点灯，罩进石屋点灯，潜行右键开包袱，收回后灯拆掉）。中间几轮暴露并修好的问题：截下箭后读射手要在弹开之前（弹开后箭算她的）；后撤时不还手会和尸壳僵住（改为边退边放狐火，刀速 1.25 → 1.5 倍）；泡温泉时主人被传送那一刻不算在水里（加 2 秒缓冲）；拾荒只捡主人身边 10 格内的；狐火灯露天判断改看高度图 |
| 截图人工看过 | 跪坐等你、遮幅过场里横托流火献刀、递形代、拿流火砍尸壳、处决时目标被抛到半空、双手捧着浆果吃 |
| Logos | `./gradlew test` 通过；`runGameTestServer` **1254/1254**，新增 `companion_one_per_master`、`companion_keeps_its_own_mind`；临时改坏一条断言确认新测试在跑 |
| 没做 | 多人（分霊只给本人看）没测；世界测试没写；光影包没测。Boss 战长场景 `shiraume_duel`（最后一步已改成检查她跪坐等你）这次在开打后不久的「玩家挨了一刀」一步超时，没跑到奖励，和这次改动无关；奖励这一段由 `companion_boss_reward` 覆盖 |

## 云岫峰林：张家界式峰林群系、群系大小与国画后期

2026-11-10：分支 `feat/cloud-peaks`，基于 main `70071ef2`，提交前 rebase 到 main `64d4481f`（#232 创造栏之后）。Logos 用 page-variants 的 jar（Logos #422）。地形、植被、群系数据都由 `tools/cloud_peaks.py` 生成。说明见 [群系大小](docs/NATIVE_WORLDGEN.md) 和 [国画](docs/WORLD_STYLES.md)。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | rebase 前全量 **290/291**，rebase 后 **309/310**，唯一失败都是 main 上本来就有的 `LangCoverageTest`（`fuda_desk` 少中文名）；新增 `StyleRealmTest` 两项（国画的着色器编号、群系画风标签里有云岫峰林） |
| 世界测试 | 全部 232 个跑了两次：第一次 229/232，第二次 231/232。`cloudsea_registry` 原来要求 33 个群系，加了云岫峰林改成 34，之后通过。剩下的都是腐败的偶发失败（`corruption_core_grows_with_network`、`corruption_full_network_splits`，第一次还有 `shiraume_rage_ring_jumpable`）：每次失败的不一样；单独跑都过；测试顺序每次打乱；干净的 main 上只跑腐败那 16 个，8 次里也失败 1 次（同样这两项）；这个分支把群系格点改回 main 的样子，5 次里失败 2 次。判断是相邻的腐败测试互相蔓延，和这次改动无关，没有修。rebase 后 243 个跑一次 **241/243**：`foxfire_burns_then_goes_out`（干净的 main 上单跑 3 次也失败 1 次，同样的消息）和 `shiraume_beast_pounce_lands_on_mark`（单跑 2 次都过）；干净的 main 跑全部 243 个也失败 1 个（`elven_living_world_tree` 光照超时）。再 rebase 到 `64d4481f` 后 244 个 **243/244**，只有腐败那个偶发；#232 的 `creative_tabs_default` 通过，三种新方块按群系循环自动进了自然栏 |
| 群系普查 | `cloudsea_biome_census`（32768×32768 格，每 64 格一点）：云岫峰林占地图 **1.69%**（45 个格点时 0.88%），34 片，一片的中位数约 300 格、最大约 1950 格，**93.5%** 的面积在能放下 320 格见方的大片里（太玄灵境完整地标 252×252 格），能当 256 格见方中心的地方占地图 0.35%（原来 0.16%）。近海那一盒的地表高度和中等侵蚀那片差不多（四分之一分位约 72–79、中位数约 82–94），没有大山垫在石柱底下 |
| 实际客户端 | 不装 Sodium 和 Iris：`group:cloud_peaks` **7/7**（每个机位原画、国画各一张，柱顶加一张夜景），`group:world_style` **5/5、20/20 检查**，`group:screen_fx` **5/5、11/11 检查**。rebase 后重跑 `group:world_style` **5/5、20/20 检查**，`cloud_peaks_pine`、`cloud_peaks_overview` **2/2** |
| 光影包与 Sodium | 只装 Sodium 0.9.2、Sodium + Iris 1.11.4 光影关闭：`group:world_style` **5/5**，`cloud_peaks_pine`、`cloud_peaks_summit` **2/2**。Iris + photon v1.3b、Iris + Complementary Reimagined r5.9.3：`group:world_style` **4/5**（没过的是 `world_style_fabulous`，只在不开光影时跑，同上一节），两个峰林机位 **2/2**，日志确认光影包已加载 |
| 截图人工看过 | 原画和国画对比（七个机位）、64–128 格遮罩的过渡、夜里墨比纸深、四种环境下的柱顶黄山松；新格点下的 384 格俯视高度图 |
| 耗时 | 不是在游戏里量的：用 moderngl 在这台机器的 RTX 5080 上跑同一个着色器（合成深度、镜头在云带里）。不带遮罩的最费情况 3440×1369 约 1.1 毫秒、1920×1080 约 0.5 毫秒（同样条件下童话风约 0.3 毫秒）；群系画风带遮罩、约一半画面在 64 格外时约 0.6 毫秒 |
| 未验证 | 最终这一版（100 个格点）没有真人试玩，维护者试玩过的是之前的版本；中低端显卡的耗时；其他光影包；Distant Horizons（它画的远景不在原版深度里，国画可能把远处的 LOD 地形当成天空）；专用服务器；已经探索过的区块保留旧群系，新旧交界的接缝没有看过 |

## 创造模式物品栏分类

2026-11-10：分支 `feat/addon-creative-tabs`，基于 main `a9323e08`（#229），Logos jar `builds/shiraume-pact-r2-2026-11-10/logos-0.6.0-lasting-companion.jar`。原「远行大陆」一页拆成建材、装饰、自然、功能方块、道具、武器六页，补上原来不在任何一页的 33 件灵狐樱野方块和签纸；货币、塔罗、炼金釜三页内容不变，九页统一在 `AddonTabs` 注册、按顺序挨在一起。运镜测试方块从炼金釜页挪到功能方块页末尾。

| 层级 | 结果 |
| --- | --- |
| 单元 | `LangCoverageTest` 1/2：失败的仍是 main 上就缺的 `item.logos_dimension.fuda_desk`，这次没动；新页名中英文都有 |
| 世界测试 | 新增 `creative_tabs_cover_items` **1/1**：附属的每件物品都在某一页（刷怪蛋在原版刷怪蛋页；只为旧存档保留的 `journey_rice` 和写好的霊符故意不放）；建材、装饰、功能方块页只有方块，武器页没有方块 |
| 实际客户端 | 新增 `creative_tab_views` 九页依次打开截图，人工看过建材、装饰、功能方块、武器四页；`ema_views` 照旧通过（检查改为绘马在道具页、绘马挂在功能方块页）。共 2/2 场景、14/14 检查 |
| 未验证 | 和其他模组一起装时页签的前后位置；自然页较长，截图只看到第一屏 |

```text
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:creative_tabs_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=creative_tab_views,ema_views" -PldTestHeadless -PldTestWindow=1280x720
```

## 白梅同伴（第一版）

2026-11-10：分支 `feat/shiraume-companion`，提 PR 前 rebase 到 main `34c49450`（#228）并重跑下表的单元测试和客户端场景，结果相同。Logos jar 用 `builds/shiraume-tuning-2026-11-10/logos-0.6.0-page-variants.jar`（main 合了 #220 霊符，要用 page-variants）。设计见 [白梅同伴](docs/SHIRAUME_COMPANION.md)；模型是仓库外 `fox_model/nine_tailed_fox_companion` 的 `_7`。

| 层级 | 结果 |
| --- | --- |
| 单元 | `CompanionTest` **6/6**：数值继承照原版算法、剔掉状态效果给的加成、近战与法强取高、阶数与上限、玩家模型的判断；`NpcAnimationsTest` 5/5。`LangCoverageTest` 1/2：符札书案缺中文物品名 `item.logos_dimension.fuda_desk`，main 上就是这样（#220），这里没动 |
| 实际客户端 | `shiraume_companion` **4/4 场景、8/8 检查**：召唤（入场播 `summon`，播完换回）、跟随（主人到 18 格外，她跑过来跟上）、战斗（两只尸壳冲着站在石台上的主人来，她放狐火弹打倒；主人打不动的劫掠兽时，她给主人缠上淡粉狐火）、睡觉（主人睡下，她在床边蜷起来睡；客户端看到的也是蜷着）、离场（致命一击后跪下、消散，放下形代，只飞出一缕魂火；之后要休息，休息时叫不出来） |
| 截图人工看过 | 入场弹出与合手行礼、跑过来、放狐火弹（淡粉、带泛光）、夜里床边蜷睡、跪着含泪浅笑、消散时形代飘落与魂火、形代烧起幽蓝狐火 |
| 没做 | 世界测试（GameTest）没写，服务端行为由实际客户端场景覆盖；光影包、多人没测 |

## 修复：钱庄和兑换台的时间不跟 /time set

2026-11-10：分支 `fix/fx-clock`，基于 main `a55b287e`。原因：26.1 起每个维度有自己的时钟，远行大陆用 `logos_dimension:cloudsea`，在那里执行 `/time set` 只改它；市场和钱庄却读主世界时钟。改为读远行大陆的时钟，柜台在别的维度时面板注明「远行大陆」。另外原来时钟往回拨时只重新对齐，待揭晓事件、消息、K 线、挂单和通知的时刻还留在未来，传闻要等很多天才会揭晓；现在往回拨时把这些时间一起挪。顺带把兑换台「持有」一行下移，不再被市价按钮压住。

| 层级 | 结果 |
| --- | --- |
| 单元 | 经济全部通过（`FxMarketTest` 新增：拨回第 0 天后消息和 K 线都不在未来、K 线按小时挪、原本五小时后揭晓的战事照常揭晓、往前走不挪） |
| 世界测试 | 经济全部 **9/9**，连跑 4 次都通过（新增 `economy_clock_set_back`：市场读远行大陆时钟、拨回 60 天后消息不在未来、挂单时刻同步挪 60 天、原本六小时后揭晓的议会照常揭晓） |
| 实际客户端 | `bank_views` 6/6、`economy_views` **5/5**（新增：`/time of logos_dimension:cloudsea set 1000` 后打开兑换台，面板显示「远行大陆 第 0 天 07:00」，消息和 K 线都不在未来） |

main 现在要用较新的 Logos 构建编译（2026-11-10 的 `lasting-companion` 构建可以；11-05 的 `logos-spell-ward` 缺霊符需要的接口）。

```text
./gradlew test --tests "dev.logos.dimension.economy.*" -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:economy_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=bank_views,economy_views" -PldTestHeadless -PldTestWindow=1280x720
```

## 惊鸿：新剑与剑阵（追击、横剑格挡与弹剑、掷剑、万剑归宗、剑身刻度）

2026-11-10：分支 `feat/new-sword`，基于 main `34c49450`（#228），Logos jar `builds/reifu-2026-11-09/logos-0.6.0-page-variants.jar`。设计见 [docs/JINGHONG.md](docs/JINGHONG.md)。共用代码只加了两个钩子（`CombatWeapon.Holder.hit`、`blocked`），所以加跑了 `longsword_*`。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | 全量 **301/302**。唯一失败的 `LangCoverageTest.everyBlockAndItemHasAName`（符札书案 `fuda_desk` 没有 `item.` 名字）是 main 上就有的，这次没动。`JinghongTest` 7/7：骨骼、握点、剑长与凌霄一致；精度；物品模型等于网格平移；连招走不到御剑；格挡的弹剑窗口 0.3 秒；掷剑出手等于接触时刻，飞剑回手对上 `catch`；剑阵时间轴；刻度贴图逐段多亮。时间轴测试抓到过一次虚影剑没落完就贯下，已改 |
| 世界测试 | `jinghong_*` **7/7**：追击（概率定为 1 时五把错开出手，伤害正好砍中 6 + 五把各 3，五把都激活）；格挡（弹剑 2–5 把，普通格挡概率 1 时一把、0 时不飞）；真实弹剑（右键架剑后下一刻挨一拳，转入弹剑、不掉血、有剑反击）；掷剑时挡不住且掷完回到格挡；两把协同（伤害够，飞完激活清零）；万剑归宗（贯下前正好是掷剑加九下虚影剑，之后清零）；没有目标时激活保留。`longsword_*` 回归 **22/22** |
| 实际客户端 | `group:jinghong` **1/1、12/12 检查**：刻度 0、2、5 把；身后五把都画了；第一人称不画自己身后那一扇；左键追击飞出、五把激活、伤到靶子；横剑格挡；弹剑反击（靶子一拳落在弹剑窗口里，不掉血）；两把协同掷剑后激活清零；万剑归宗结环、剑雨、贯下，之后清零；收剑后身后的剑也收了；掉在地上的立体模型 |
| 截图人工看过 | 仓库外 `art/jinghong/ingame_v06`：背面和左前的剑阵、激活发光、追击、横剑（第三人称、第一人称）、弹剑反击、掷剑协同、万剑归宗三张、第一人称刻度与星芒。离线预览在 `art/jinghong/model_v01`、`anim_v02` |
| 测试的坑 | 场景里要 `difficulty easy`：和平难度下生物攻击伤害是 0，根本走不到格挡。测试概率的开关要按玩家记，世界测试并行时会互相覆盖。机位不是玩家时要第一人称，第三人称会把镜头拉到机位后面四格。第一人称的星芒要在手里那把剑的坐标里画、两面都画 |
| 未验证 | 光影包下；多人下旁观者看到的剑阵（单机只能本机演）；手感、数值、音效都没试玩过 |

## 流火：狐火印、平一文字の構え、突进上挑、可交互处决

2026-11-10：分支 `feat/ryuka-execution`，提 PR 前 rebase 到 main `70071ef2`（#227）并重跑下面所有测试，全部通过（单元 64/64、`longsword_*` 22/22、`foxfire_*` 10/10、`shiraume_*` 13/13、`group:ryuka,group:execution_shot` 7/7 场景 88/88 检查）。下表里提到的失败是之前在 `d950cf17` 上跑的，那时 main 本身有这几处失败或时好时坏；Logos jar `builds/shiraume-tuning-2026-11-10/logos-0.6.0-page-variants.jar`。设计见 [docs/RYUKA.md](docs/RYUKA.md) 3.2–3.4 节，通用的可交互处决见 [docs/EXECUTION_SHOTS.md](docs/EXECUTION_SHOTS.md)「可交互的处决」。

| 层级 | 结果 |
| --- | --- |
| 单元 | 相关 68/69：新增 `RyukaTest.stanceChargesThenDashesIntoTheRisingCut`（构え、蓄不到 0.3 秒回格挡、突进接上挑、构え的格挡后松开立即突进）、`ExecutionTimelineTest` 两项（可交互：刀一刀刀加、两端一样、重击和整段时长与刀数无关、节拍每秒不超过三次、编解码不变；不可交互的不能加刀）、`PaleFireTest` 两项。唯一失败的 `ShiraumeTrailsTest.everyHumanAttackLeavesATrail`（白梅的 `turn_sweep` 没有刀光）在干净的 main 上同样失败，与本改动无关 |
| 世界测试 | `longsword_*` **22/22**，新增：`longsword_ryuka_marks`（平时的刀照常缠身但不能处决、四刀四枚印、第五刀爆开多掉 30、缠身 8 秒、8 秒内能处决）、`longsword_ryuka_dash`（格挡中左键进构え、0.3 秒内松开回格挡、构え的格挡、松开突进、撞上木桩停下上挑挑飞、扣 20 + 10）、`longsword_ryuka_execution`（只缠身没爆开过的只挑飞；爆开过的被上挑时处决、用掉这次爆开：定住、窗口前点的不算、同一刻点两下只算一刀、9 刀 99、窗口关了不收、重击 11、落地 25 摔落、结束后放开）。改成「爆开后 8 秒内可处决、平时照常缠身」之后重跑：单元 49/49、`longsword_*` 22/22、`foxfire_*` **10/10**、`group:ryuka` 3/3（33/33 检查）。之前 `foxfire_burns_then_goes_out` 在本分支和干净的 main 上都失败过，这次通过，是时好时坏。`shiraume_*`（白梅的处决也走 `ExecutionShots.play`）本分支跑三次：13/13 一次，另两次各错一项（`ichimonji_turns_when_behind`、`ichimonji_wave`）；干净的 main 上跑一次也错两项（`ichimonji_wave`、`thrust_purges_two`），是 main 上本来就不稳 |
| 实际客户端 | `group:ryuka` 三个场景、`group:execution_shot` 四个场景：`ryuka`（原有，弹刀后再按左键接突刺）、`ryuka_stance`（刀身狐火变淡、构え正面/背面/第一人称、构え的格挡、突进 1.5 格以上撞上尸壳挑飞、四枚印客户端看得见、爆开、客户端看得出能处决）、`ryuka_execution`（狐火印爆开过的尸壳被突进上挑后进处决特写，一刻一下点到窗口关上，两端刀数一样，伤害正好是刀数 × 11 + 11 + 25，重击变红、落地白一拍、镜头回来；本机演一段旁观的处决，世界里画了刀光）。处决特写原有的四个场景照旧通过 |
| 刀翻面 | rebase 到 main `70071ef2` 后用 #225 的翻面检查重新烘焙：新的 `dash`、`rise`、`execution` 原来各有一两处刀一帧转过 90° 以上，补了翻腕的中间帧（`ryuka_moves.py` 的 `wrist`），现在都不报；刀走的弧线没变。`slash_1`–`slash_5` 的翻面是之前就有的，这次没动 |
| 截图 | 仓库外 `art/liuhuo/execution_v01`：刀身狐火前后对比、构え、挑飞、狐火印、处决的黑白/红/落地、旁观的刀光 |
| 未验证 | 真机手感（突进距离、点击节奏）、多人下旁观者真的收到刀光（单机只能本机演）、光影包下的刀光和狐火印 |

## 白梅：狐形消散卡顿、大跳蓄力与破盾、狂暴狐火冲击、一字斩剑气与转身

2026-11-10：同一分支 `feat/shiraume-tuning`，试玩后的第三轮。Logos jar 同上（page-variants）。提交前 rebase 到 main `9eddb7fc`（#221 刀光，只在两份文档上有冲突，两边都留）。最后按试玩把狐形血量从 300 调到 500，这一下没有重跑测试。

| 层级 | 结果 |
| --- | --- |
| 消散卡顿 | 原因：消散按贴图像素切小格（每像素四格、每面最多 64×64），v3 狐形约四万个面配 2048 贴图，第一帧切上亿格。`shiraume_dissolve` 量最长的一帧：改前 **14124 ms**、**9854 ms**（两次），改后第一次消散 **145 ms**（其中读 2048 贴图 34 ms，其余是第一次用到的预热），第二次 **13 ms**，平均 9 ms。一个模型一次最多切 10 万格，普通生物到不了 |
| 单元与构建 | 全量 **273/274**（唯一失败同上一节，main 上的符札书案） |
| 世界测试 | `shiraume_*` **13/13**，连跑两次：新加狐形大跳落地破盾、狂暴狐火冲击（贴地的人挨狐火、半空的人躲过、扩散到头散掉）、一字斩剑气打中九格外的人并散掉、举刀时玩家在背后变成转身横扫；破盾那一项加查一字斩被挡下时没有剑气 |
| 实际客户端 | `shiraume_round3` **1/1、1/1 检查**（剑气、举刀转身、加长蓄力、狐火冲击三个阶段）；`shiraume_dissolve` **1/1**；`shiraume_tuning` 回归 **1/1、14/14 检查** |
| 截图人工看过 | 剑气（白芯淡粉的竖弧、三道残影）；举刀时绕到背后被横扫；狐火冲击慢的一圈、快的一大圈（先只叠加时在白瓷地上一片白，加了混合的深粉垫底后看得出是粉的）；狐形消散 |
| 测试的坑 | 测试结构周围有一圈屏障，剑气飞过玩家后撞上就散了：数剑气要看最多有几道，不能挑某一刻数 |
| 未验证 | 光影包下的剑气与狐火冲击；真人跳过狐火冲击、侧闪剑气的手感 |

## 白梅 Boss 战改进：破盾、清增益、转身横扫、读指令、斩法、速度线、v3 狐形

2026-11-09：分支 `feat/shiraume-tuning`，基于 main `4755289b`（#220）。main 上的霊符（#220）要 Logos 的 `writtenForm` 钩子，所以 Logos jar 用 `builds/reifu-2026-11-09/logos-0.6.0-page-variants.jar`（spell-ward 那个编译不过）。设计见 [九尾妖狐 白梅](docs/SHIRAUME_BOSS.md) 第 3、4.3、4.8–4.10、6、9 节。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | 全量 **273/274**：唯一失败的 `LangCoverageTest.everyBlockAndItemHasAName` 是 main 上就有的（#220 的符札书案只有 `block.` 名字，没有 `item.` 名字），这次没动 |
| 世界测试 | `shiraume_*` **9/9**（连跑两次）：一字斩挡下来破盾、流火挡不破、突き清掉两个增益（坏的留着）、两种形态都拴不住、术式打中五次盯上 → 长剑蓄力时挨斩法（伤害类型对、至少 8、蓄力被打断）、斩法死亡消息的参数（37.5 / 40）、狐形大跳落在标记上（水平差 < 0.75）、狐形咬击被打刀弹开仰头一晃、狐形前爪破盾。`longsword_*` 回归 **19/19** |
| 实际客户端 | `shiraume_tuning` **1/1、14/14 检查**：开打一条血条 → 走远（区块卸载）收起 → 回来还是一条；缩地时收到速度提示、全屏通道跑了；破盾；清两个增益；转身横扫；读指令后金苹果照样吃完；术式五下盯上；变身后狐形还盯着；大跳落在玩家脚下；狐形斩法、死亡消息；复活回来最多一条血条。无 Sodium、Iris 或光影包 |
| 截图人工看过 | 缩地（第一人称）速度线朝她汇聚、四周拖影、她本人清楚；破盾的金白闪光；清增益的彩色光圈；转身横扫；读指令那一刀；被盯上的人头顶淡粉狐火；v3 狐形叼着发光的流火；大跳腾空、落地砸中；跳闪；狐形斩法扑击（速度线、狐火碎屑）；死亡画面的消息「Dev was cut down mid-spell by Shiraume (Nine-Tailed Fox), 40 mana still unspent」 |
| 离线预览 | 人形 `turn_sweep` 两个机位；v3 狐形 21 段动作（嘴里的刀的位置、咬击、前爪、后跃、尾扫、大跳、跳闪、被弹开、踉跄、休息、死亡）用附属的几何与动画离线渲染看过 |
| 测试的坑 | 测试结构只有一格，测试框架只替那一小块区块跑实体刻：她冲出去、跳出去就停在半空（大跳停在弧线的三分之一、三分之二处）。要强制加载场地的区块，旁边还要站一个玩家。招式文件的 `cone` 最大 180，转身横扫的一整圈写在代码里。吃东西按住右键会一个接一个吃，读指令要按手上这一口的剩余刻数算 |
| 未验证 | Sodium、Iris 和光影包（速度线、拖影、新的闪光都没在光影包下看过）；独立多人服务器；真人操作的弹反与破盾手感；v3 狐形（6976 块）对帧率的影响没量；死后在离场地很远处复活的完整流程只测了走远再回来与原地复活 |

## 刀光

2026-11-09：分支 `feat/weapon-trails`，基于 main `bcae9d1d`（#219）。先做刀光核心（[docs/WEAPON_TRAILS.md](docs/WEAPON_TRAILS.md)），再按武器分头接入：长剑、长枪、凌霄、流火与白梅、神狐 NPC 的打刀各一个分支，合并回来；最后合进 main `4755289b`（#220 霊符），之后 Logos jar 要用 `builds/reifu-2026-11-09/logos-0.6.0-page-variants.jar`（#220 用到 Logos 的 writtenForm 钩子）。下面的结果是合进 main 之后重跑的。

| 层级 | 结果 |
| --- | --- |
| 单元 | 相关的 **75/75**：`WeaponTrailsTest`（样式与设置文件读得通、引用的贴图与动作都在、各网格自己的动作也认、出光时段不超出动作、七种元素加狐火都有样式；扫过的面按段记点、散尽后再送点另起一段、帧间补点贴着圆弧），各武器的 `*TrailsTest` 与原有的长剑、凌霄、流火、白梅、神狐、打刀、长枪测试 |
| 实际客户端 | 8 个场景全部通过，**193/193 检查**：`weapon_trails`（打刀与七种元素缠绕）、`weapon_trails_dawnvow`、`weapon_trails_blacksteel`、`weapon_trails_spear`、`weapon_trails_lingxiao`、`ryuka_trails`、`shiraume_trails`、`weapon_trails_fox`。每一击都在画刀光、蓄势与待机时没有、回到待机后散尽、样式对，缠绕时换成对应元素，狐火缠绕换成狐火 |
| 截图人工看过 | 背后、正面、侧面与第一人称：刀光都顺着挥刀的方向，白天亮天空前也看得出颜色；背后看竖劈、长枪横扫有刃线的朝镜头带子补上 |
| 照 UE 重做之后 | 着色器与样式照 StylizedSlashesV2 的 `M_ComplexSlash_01` 重做（越老越从柄侧收窄、尾部被条纹侵蚀、颜色噪声流动、弯钩刃线）。8 个场景重跑 **193/193**，相关单元 **76/76**；打刀场景改成命中后约 0.1 秒定格（/tick freeze）再截，加了俯视机位；神狐场景结束时带走神狐与靶子，不再站到后面场景的镜头前。人工对照 UE 截图：刀光是头宽尾尖的月牙、尾部碎成条纹，流火回旋不再是一样宽的圈 |

## 太玄灵境接手：自然生成、落地高度、城心细化与岩层

2026-11-09：分支 `feat/taixuan-sanctuary`（#218），rebase 到 main `4755289b`（#220）。#220 的霊符要 Logos 的 writtenForm 钩子（Logos PR #422），CI 用的 `LOGOS_REF` 还编译不过 main，所以本地用 `MinecraftLogosMod/worktrees/page-variants`（`94f8403`）构建的 Logos 0.6.0。Java 25.0.3。改动和原因见 [太玄灵境](docs/TAIXUAN.md)。

| 层级 | 结果 |
| --- | --- |
| 模板审计 | `python tools/audit_taixuan.py` 通过：729 个 28³ 部件；接地石基入口 4349 → **1807**（外缘逐格、内部 8 格一根），整座地标部件 5078 → **2536**；模板方块 3,087,486 → **2,984,860**（清空高度以上的 103,144 格天空空气不导出）。新增检查：吊灯上方、立灯与门下方、梯子背后都有方块（第一版殿阁柱廊有 76 盏吊灯悬空，已补短枋）；水圳与井的底和四周封住；落水柱从泉眼到接水池连续、离东城墙 ≥15 格。模板里的原版方块 ID 与 26.1 方块表逐个核对，`chain` 已改为 `iron_chain` |
| 单元 | 全量 **280** 项，279 通过。失败的 1 项是 `LangCoverageTest`：#220 的 `fuda_desk` 缺 `item.` 名字，main 上同样存在，与本分支无关。`TaixuanDataTest` 6/6，新增两项：城池地面压在中心原地表上（`start_height` = -4）；远行大陆所有群系引用的树、高树、倒木、林地石堆都带 `no_tree_roots` 过滤、不再用 `OCEAN_FLOOR_WG` |
| 世界测试 | `taixuan_*` **3/3**。种子 0 与 81 原版拼装均为 729 个模板 + 1807 个接地特征。新增检查：鼓楼十字券洞、牌坊中间开间通行，东西主街北侧水圳有水，樟树接到树池，落水柱完整 |
| 实际客户端 | `group:taixuan` **7/7**（宗门、城池、园林、街巷、鼓楼与牌坊、南市、水圳），1600×900，截图已人工查看 |
| 自然生成勘察 | `group:taixuan_survey` **4/4**：全新世界里四处真实候选点（两处未旋转、两处转 90°），只把玩家送过去，区块按正常流程生成。地标都自然生成，2536 个部件；落水柱完整；城池清空范围内外来树干 **0**。修复前同样四处：城内外来树干 3489 / 18231 / 10570 / 2380 块，两处落水柱被树叶截断。256 个区块的服务端生成耗时 9.4 / 9.6 / 6.8 / 9.0 秒，同大小对照区 8.2 / 8.8 / 8.8 / 8.0 秒 |
| 地形统计 | `taixuan_terrain_stats`：60 处候选点，城内地表比中心高出 p50 0 / p90 15 / p99 40 / 最高 94 格；约三分之一的候选点会留下高出 44 格的悬空山头；城门外地面 p10 -9 / p50 +1 / p90 +20 |
| 未验证 | Sodium、Iris 与光影包；独立多人服务器；长时间自然探索；低配机器上的生成耗时。悬空山头与临水选址的问题没有解决（见 [选址的已知问题](docs/TAIXUAN.md)） |

## 太玄灵境、浮峰宗门与扩大后的太玄城

2026-11-09：分支 `feat/taixuan-sanctuary`，基于 main `a0bd54c`（含 #217 的严格 GLSL 编译修复）。本次只修改附属，Logos 使用本机 0.6.0 测试副本，未修改前置或其版本。实现和定位见 [太玄灵境](docs/TAIXUAN.md)。建筑按仓库已有 Python 局部画布工作流制作，贴图由 Pillow 代码绘制；游戏内使用原版噪声、NBT/jigsaw 与接地 `block_column`。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | 全量 **278/278**，零失败、零跳过；`test build runGameTestServer runClient` 完整命令成功。最后只补实马头墙瓦饰的半格空隙，又执行 `build runClient`，单元与构建再次成功 |
| 模板审计 | `python tools/audit_taixuan.py` 通过：729 个 28³ 部件、728 条唯一拼接连接、76 份局部模块、46 处民居院落、70 处房间检查点、4349 个接地石基入口；8 棵崖松、2 个封底封岸水池。检查街道低处与树冠高度的净空、角楼落地塔身及殿阁挡土石基。结构实体数为 0，没有沙砾或沙子道路 |
| 世界测试 | `taixuan_*` **3/3**。种子 0 与 81 的原版拼装均为 729 个模板 + 4349 个接地特征，无碰撞或深度截断；真实气候候选点通过群系过滤。实际放置后读取房间、楼板、池底、池岸、树干土壤、街道净空、角楼塔身和台基，均通过；花窗的四向旋转与含水属性通过。世界测试后仅有瓦饰外观调整，没有修改选址、接线、室内或基础 |
| 实际客户端 | 瓦饰调整后的 `group:taixuan` **4/4 场景、16/16 检查**，1600×900。宗门、城池、园林、徽宅天井的原始实景 PNG 均已人工查看；确认花窗没有缺材质、天井和回廊正常、池水封闭、殿阁台基已接实，机位不在方块内 |
| 测试环境 | Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25.0.4.1、Logos 0.6.0；GeckoLib 5.5.2、LDLib2 26.1.2.42、Photon 26.1.2.3。未安装 Sodium、Iris 或光影包。客户端场景通过真实原版地形候选点调用原版放置流程，属于开发预览，不称为正常探索时完整的自然分区块生成测试 |
| 性能与未验证 | 最后一轮整座一次性测试摆放记录约 **28 秒**服务端加载峰值，首场景约 95 秒；后续三个机位约 17–18 秒（包含固定等待和截图）。这些不是稳态 FPS 或自然区块生成耗时。未做自然探索性能、独立多人服务器、其他种子长时间探索或光影包验证。陡坡、临水选址可能露出较高石基 |

本地报告和测试包保存在仓库外 `outputs/taixuan-implementation/delivery/`；原始完整检查日志另存 `outputs/taixuan-implementation/full-check/`，末次外观构建日志为 `outputs/taixuan-implementation/final-visual.log`。测试包内的 manifest、民居 NBT、花窗模型与砖雕贴图已按字节核对当前资源。[实景拼图](docs/taixuan/preview.png) 随源码提供，供 PR 审查。预览包不是正式发布；旧存档需要新区块，不会自动重建已有地标。
## 九尾妖狐 白梅 Boss 战与狐火

2026-11-09：分支 `feat/shiraume-boss`，基于 main `cd9ed21c`（#216），推送前 rebase 到 `a0bd54cc`（#217 只改爆裂魔法着色器）。Logos jar 用 `build-latest-20261105/logos-spell-ward`（`ba471315`，与 CI 的 `LOGOS_REF` 相同；试玩包里的 `logos-0.6.0-spell-ward.jar`）。设计见 [九尾妖狐 白梅](docs/SHIRAUME_BOSS.md)、[狐火](docs/FOXFIRE.md)。试玩包在仓库外的 `builds/shiraume-boss-2026-11-09`。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | 全量 **274/274**，零失败、零跳过；`jar` 构建成功 |
| 世界测试 | `foxfire_*` **10/10**，每个单独一批（狐火横扫扫得到隔壁测试的生物）：狐火和奥秘掉血相同、只有伤害类型不同；同族（神狐）只受四分之一再叠魔法减免，不缠身；缠身三秒烧 3 点后熄灭；`foxfire` 调试能用、玩家写不了（编译缓存里有也不行），编纂台打开再存回去变成奥秘；书与笔、书灵真的拒绝它；狐火术式打出狐火；横扫游脉被认出来、扫到的是狐火；普通缠绕附带淡粉狐火；惑。`longsword_*` 回归 **19/19**（流火改走狐火，魔力护体认狐火） |
| 实际客户端 | `shiraume_awakening` **1/1**（真实封印之间：九根封印杭、色散、红光、两次震动、炸开、挪人、拆除、跪坐、起身凝刀、标题、开打）；`shiraume_duel` **1/1、4/4 检查**（出招、投技与处决、变身、狐形、狂暴放狐火、击倒与流火）；`foxfire_views` **1/1、10/10 检查**；`npc_divine_fox_fight` **1/1、3/3 检查**（神狐的狐火缠绕改成狐火后的真实 AI 对战）。无 Sodium、Iris 或光影包 |
| 截图人工看过 | 白梅：开场各段、标题、对决各段，狂暴时的狐火飞弹是淡粉的。狐火：施法圈、刀身幽蓝狐火、命中、缠身、朝空地的横扫游脉、玩家手上淡粉的缠绕与附带的一下、自己被打中时画面边缘与底边、被惑的怪物头上的狐火 |
| 场景的坑 | 投技后玩家只剩 1 点血，她接着出招会把人砍死，后面的截图全成了死亡画面：确认 1 点血后马上回满。狐火的世界测试要铺地面，不然生物往下掉，缠身算不到点火的人身上。靶子站在横扫的弧上时，Logos 的游脉第一步就打中、整条不画，所以场景朝空地另扫一道看颜色。uitest 每次运行前会清空所有场景的截图目录 |
| 未验证 | Sodium、Iris 和光影包（白梅的全屏后期、光源变红、狐火泛光都没在光影包下看过）；独立多人服务器；真实鼠标的弹反手感；被腐化的同族更怕狐火（现在还没有被腐化的狐狸） |


## 凌霄接手：规范检查、仙侠四式、剑尖朝向、提速、持续扣魔力

2026-11-08：分支 `feat/lingxiao-sword-flight`（PR #212，接手同伴的 `cbf33eb`），工作树 `worktrees/lingxiao`，基于 main `2822f04`。Logos jar 用 `builds/explosion-staff-v2-2026-11-08/logos-0.6.0-spell-ward.jar`。改了什么、为什么改，见 [LINGXIAO.md](docs/LINGXIAO.md) 第 1 节。离线预览在仓库外的 `art/lingxiao/anim_v01`（四机位总览、慢放 GIF、第一人称透视预览），游戏截图在 `art/lingxiao/ingame_v02`。维护者用试玩包 `builds/lingxiao-2026-11-08` 试玩后认可。推送前合入 main `9b0eb1e`（#213–#215），只有本文件顶部冲突，两边都保留；合并后重跑：单元 **274/274**、`test build` 成功，`lingxiao_*` 11/11、`longsword_*` 19/19，`group:lingxiao,group:longsword` 5/5 场景、46/46 检查。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | 全量 **260/260**，`test build` 成功。`LingxiaoTest` 12 项：带自然回复时二十刻正好少十点、剑尖顺着飞行方向（倒飞不掉头、悬停跟视线）、俯仰限幅、四式连段间隔不短于冷却、交接后骨架里不画剑、两视角动作与招式（contact、cancel）一致、没有 `first_person_scale` |
| 世界测试 | `lingxiao_*` **11/11**，新增 `lingxiao_heading`：起飞剑尖朝身体方向、侧飞转向北、前进转回东。`mana` 改为不锁魔力上限，自然回复照常时一秒实际少十点；`creative`、`exhaustion` 相应改成“没被扣”“不扣成负数”。`longsword_*` 回归 **19/19** |
| 实际客户端 | `group:lingxiao` **1/1 场景、11/11 检查**：四式连招第一、第三人称各一遍，每式定住时截图；起飞交接前载具不画剑、交接后画剑；侧飞剑尖转向和前进转回（数值）；第一人称低头看见脚下的剑；每秒实际少十点；下剑、归鞘。`group:longsword` 4/4 场景、37/37 检查（共用渲染层有改动） |
| 截图人工看过 | 第一人称四式挥到头时剑都在画面里；第三人称正面看得到每式定住的架势和凌霄亮相；侧飞时剑尖指向飞行方向、骑手侧身；第一人称低头看见脚下剑尖朝前；动作栏显示 `☿ … 189/200 御剑中 · 每秒 -10` |
| 场景的坑 | 正面机位夹在人和靶子之间，第三人称那一遍先移走靶子；靶子被前一遍打死时清理步骤要判空。下剑后照常下落，截图里人因摔落受伤变红，属正常 |
| 未验证 | 独立多人服务器（其他玩家看到的剑尖朝向只在单人客户端里看过）；Sodium、Iris 和光影包 |

## 凌霄仙剑与御剑飞行

2026-11-08：分支 `feat/lingxiao-sword-flight`，已执行 `git rebase origin/main` 到 `2822f04`（#211；包含 #208 的崩溃修复），保留 main 的流火与自然之力反射桥崩溃修复。Logos 使用本机 `111/mods/logos-0.6.0.jar`，没有改其版本号。创建 PR 时 main 又推进到 #211；已重新 rebase，保留爆裂魔法、神狐地下结构和全部语言条目，重新运行全量单元、构建及两组世界测试。客户端场景的记录来自 #208 基线，本次冲突只涉及文档与语言条目。设计与操作见 [LINGXIAO.md](docs/LINGXIAO.md)。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | 全量 **257/257**，零失败、零跳过，`test build` 成功。凌霄新增 9 项，检查菱形边缘与大目标、垂直瞄准、每刻 0.5 魔力、移动速度上限、双视角动作和招式一致、单斜块剑尖及物品坐标 |
| 世界测试 | `lingxiao_*` **10/10**：属性伤害 6、攻速 2.4、命中前摇和普通铁剑伤害、菱形边缘和后方排除、墙体遮挡、真实载具扣费、魔力耗尽、潜行和换槽清理、碰撞、创造能力保留；`longsword_*` 回归 **19/19**，包含 main 的流火魔力格挡 |
| 实际客户端（#208 基线） | `group:lingxiao,group:longsword` **5/5 场景、42/42 检查**。凌霄 7/7，实际左键攻击、右键骑乘同步、站姿、原版输入包前进与上升、Shift 下剑、切槽收剑剑花均通过。1280×720，无 Sodium、Iris 或光影包；第一人称、御剑、上升和剑花截图已查看，结果保存在仓库外美术目录 `ingame-verification` |
| 模型及重建 | 剑尖从六块简化为一块绕 Z 旋转 45° 的方块，总构件 **63 → 58**，13 骨骼、16 动作保留。当前仓库动作编排脚本重建 TP/FP 逐字节一致，导入脚本重建 9 个游戏资源逐字节一致；Three.js 实际解析三份 GLB，各 16 动作、305 个网格姿势，误差小于 0.000002 格 |
| 测试隔离 | 嵌入玩家连接没有区块跟踪包，因此世界测试显式加载测试区并计数释放；测试中将魔力容量锁到当前余额以排除前置自然恢复。生产代码仍保留 Logos 自然恢复，扣费每秒 10、面板净减少量可能更低。Minecraft 26.1 禁止骑乘不可序列化类型，所以类型可序列化、临时载具实例拒绝保存 |
| 未验证 | 独立多人服务器、Sodium、Iris 和光影包，以及玩家实玩手感；测试截图与离线预览分别标注，不把离线渲染当作游戏截图 |
## 曦誓大型蓄力与巨型剑气的后期：周围变暗、光柱冲起之前的几何黑白闪

2026-11-08：分支 `feat/dawnvow-burst-fx`，基于 main `07a2f86c`（rebase 后重跑了构建、`dawnvow*` 世界测试和原版下的 `dawnvow_charge`）。说明见 [曦誓的蓄力彩蛋](docs/DAWNVOW_CHARGE.md)「光柱冲起之前」。按维护者意见用附属的后期系统：

- 大型蓄力时周围暗下去、褪色，蓄力的人和身边一圈亮起来；四周的光点、身上的光、剑气改在调暗之后画（`PostVfx`），在暗处格外亮。
- 剑气到头后不再十字爆闪，改成一串画面再接光柱：地上一团光鼓成穹顶，黑色的尖刺炸满画面、外围的亮光向内收紧，几何黑白闪（反色两拍、白底黑星、黑底白星），穹顶扑满画面，极亮。光柱冲起从到头后 4 刻挪到 33 刻，服务端、客户端共用 `DawnvowBurst` 的时间轴。
- 黑白闪是几何的（尖刺、反色、星），不用爆裂魔法那种取画面剪影的写实黑白闪；节奏参考爆裂魔法的时间轴。
- 维护者试过第一版：蓄满时剑上的金色流火也跟着暗了。火焰是实心的世界画完之后才画的、不写深度，按深度它背后是远处的天空。改为世界画到几个阶段时各留一份画面，变亮了的像素（加上去的光）保留原来的颜色；开着光影包时在 Logos 画特效之前留一份。第三人称时玩家的身体也被调暗过（皮肤是半透明画的，不在半透明之前的深度里），改用半透明特效画完时的深度。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全部通过 |
| 世界测试 | `logos_dimension:dawnvow*` 12/12（光柱的结算时刻按 `SwordWave.PILLAR_DELAY` 算，跟着挪后） |
| 实际客户端 | `dawnvow_charge` 1/1 场景、16/16 检查：蓄力各阶段周围暗下去；调暗时分层留到了画面（第一人称看着墙和抬头看天时剑上的火焰都是原来的亮度）；剑气扎进地面、光柱冲起时镜头抖动；全屏后期在画；实时放的几何黑白闪六拍都放到了（每拍至少一帧）；只在本机重放一道剑气，停住时钟拍了光柱冲起之前的 13 个时刻。五种环境都跑过：不装 Sodium 和 Iris、只装 Sodium 0.9.2、Iris 1.11.4 关光影、Iris + photon v1.4（日志有 `Using shaderpack: photon_v1.4.zip`）、Iris + Complementary Reimagined r5.9.3。改了全屏后期的调用顺序（爆裂魔法的通道不再自己画 `PostVfx`），`explosion_staff` 重跑 1/1 场景、6/6 检查 |
| 未测 | 联机多客户端；「隐藏闪电的闪烁」打开时的减弱只看过代码。已知限制：开着光影包时第一人称的手按它背后的东西判断，背后暗时手也暗 |

## 爆裂魔法第二轮：条带、球体纹理、蓄满的气浪、放完趴下

2026-11-08：同一分支。维护者看过第一版后要的四处改动，说明见 [爆裂魔法](docs/EXPLOSION_MAGIC.md)：

- 条带弯得太多，变长之后绕着人转圈：改成每条只弯 20–40 度。顺带修了一个错：条带两端转动的差按客户端开机以来的总刻数算，玩得越久卷得越厉害，现在按吟唱开始后的时间算。
- 球体加纹理（参考 Unreal）：壳变成边缘白热、中间半透明，透出橙红的火云和正中一颗极亮的白球（从哪个方向看都在中间）；三道锯齿形、带暗影的亮纹从顶上往下流。
- 蓄满后只有声音：加了蓄满那一下的气浪（杖头一闪、球形冲击和别人看到的空气折射、地面风墙、冲出去的光痕、镜头一震、风爆声）和之后持续的涌出（更高更快的光柱、上升光痕、往外冲的光点、更勤的风圈，自己第一人称时画面四周往上流的蓝光）。
- 放完之后施法者强制趴下 30 秒（原版的趴下姿势，不提醒），从球体散尽开始；趴着时挡掉跳和疾跑。

| 层级 | 结果 |
| --- | --- |
| 实际客户端 | `explosion_staff` 1/1 场景、6/6 检查（新增：球体散尽后施法者在本机和服务端都是趴着），五种环境都过：不装 Sodium 和 Iris、只装 Sodium、Iris 关光影、photon v1.4、Complementary r5.9.3。看过新加的蓄满一刻、蓄满之后（侧面、第一人称、远处）、球体各时刻和趴下的截图，五种环境都看了球体和蓄满 |
| 帧时间 | `explosion_bench` 不限帧率：原版平时 0.75 毫秒，吟唱（含蓄满之后）1.36，爆炸各段 0.87–1.15；photon 下平时 2.8，各段 2.2–3.0。新的球体看不出额外开销。慢帧大多跑过垃圾回收，吟唱第一帧 13–15 毫秒 |
| 世界测试 | `explosion_*` 3/3（服务端改了放出去之后的流程，重跑过） |
| 场景里的假象 | 场景从盔甲架机位看时，原版客户端不把自己的位置发给服务端：施法者被气浪掀到石垫外的地上，服务端还以为人在石垫上。趴下的截图按客户端的位置放机位；正常玩时镜头就是玩家自己，没有这个问题 |
| 未验证 | 多人联机里别人看到的趴下和蓄满时空气的折射；趴着时换维度、重新登录（代码里会重新设上，没实测）；Distant Horizons；比这台慢的显卡；没进游戏手动玩过 |

## 爆裂魔杖与爆裂魔法

2026-11-08：分支 `feat/explosion-staff`，基于 main `9234727e`。维护者要求：参考惠惠的法杖做模型；右键只能放爆裂魔法，吟唱约 20 秒、注入 6400 魔力，蓄力特效挂在杖头红珠上，参考 Logos 巨型蓄力改成动画里的深蓝，条带从更远处汇来，目的地上空逐渐出现大小不一、竖直一串的红色法阵，周围的人画面变暗，后期出现各色十字闪；松手后火柱、空气冲击、红热到白热、转蓝、十字闪、黑白闪，黑白闪结束前换成参考 Unreal 爆炸的白热球体扩大并掀飞经过的地方，最后留下气浪、烟和彩色光点；爆炸几十个区块大，强制击杀范围内一切，配置可关破坏地形，开着时坑一直深到基岩，要兼顾性能。说明见 [爆裂魔法](docs/EXPLOSION_MAGIC.md)。

| 层级 | 结果 |
| --- | --- |
| 世界测试 | `explosion_*` 3/3（挖坑改成挖完马上重发、光照算完再发一次之后重跑过）：魔力不够、副手不能吟唱；400 刻注满 6400、每刻 16；中断后注入的魔力散成游离魔力；半径内戴下界合金头盔的僵尸、铁傀儡都死，原有掉落物清掉，半径外的牛被掀开活着；坑中心挖到世界底部、半径外不动、离中心 12 格挖不到底；关掉破坏地形时一块不动 |
| 实际客户端 | `explosion_staff` 1/1 场景、5/5 检查，五种环境都过：不装 Sodium 和 Iris；只装 Sodium 0.9.2；Sodium + Iris 1.11.4 光影关；photon v1.4；Complementary Reimagined r5.9.3（改完十字闪和第一人称姿势后五种又各跑了一遍）。真按住右键吟唱到蓄满再松手；石台上 9 个生物和盔甲架全没了、挖出约 11 万方块的坑、坑底到基岩。每种环境都看过吟唱各进度（侧面、第一人称、远处）和爆炸时间轴各时刻的截图，挑出的放在 [docs/screenshots/explosion](docs/screenshots/explosion) |
| 帧时间 | `explosion_bench`，1280×720、视距 16、不限帧率：原版平时 0.84 毫秒一帧，吟唱和爆炸各段平均 0.9–1.4 毫秒、99 分位不到 2.5 毫秒；photon 下平时 3.3 毫秒，各段 2.2–3.4 毫秒、99 分位不到 5.5 毫秒。挖坑、重发区块在客户端看不出额外开销。偶尔有 10–25 毫秒的单帧（平时也有，最慢约 10 毫秒），约一半那一帧里跑过垃圾回收，吟唱第一帧约 14 毫秒，其余没找到固定原因（那一刻区块网格没有排队）。有一次在烟的阶段连着一秒出现 10–41 毫秒的帧，当时慢帧是在帧里当场写日志的；改成最后一起打印后再跑没有出现 |
| 服务端 | 挖 64 个区块、约 11.4 万方块合计 19–27 毫秒（每刻上限 6 毫秒），光照在挖完后几到几十毫秒内算完再补发一次；爆炸那几秒每刻平均 1.6–1.9 毫秒 |
| 未验证 | 多人联机（别人看到的吟唱、远处的人收到的重发区块）；真实地形（山、洞穴、水、熔岩，下界和末地）里的坑和光照；Distant Horizons 下远处的 LOD 会不会跟着变；比这台慢的显卡；没进游戏手动玩过 |

## 流火：魔力凝成的打刀

2026-11-08：分支 `feat/ryuka`（原 `feat/liuhuo`），基于 main `1e05c246`，提 PR 前合入 main `9234727e`（#205）后复跑了下面“试玩反馈第二轮”那一行的测试；Logos jar `build-latest-20261104/logos-main`。设计见 [docs/RYUKA.md](docs/RYUKA.md)。

| 层级 | 结果 |
| --- | --- |
| 精简（试玩后） | 维护者试玩一拿出来就严重掉帧。原因：刀柄、刀镡的小方块每个面贴整块材质区域，Logos 表面火焰和凝聚、消散按贴图格子每格画一片，每帧约 114 万片。网格精简到 124 块（打刀 98 块），贴图格子按方块大小取：表面火焰约 1800 片、凝聚消散约 9400 片（`docs/RYUKA.md` 1.1 节）。同一场景里临时测帧（无光影、1280×720、不限帧，测完已删）：旧网格拿着第一人称每帧 **111 ms**、第三人称 109 ms，空手 0.69 ms；新网格第一人称 **0.81 ms**、第三人称 0.78 ms，空手 0.75 ms，拔刀凝聚平均 1.12 ms。`RyukaTest` 改为检查块数不超过 127、两种切片数的上限；`tools/import_ryuka.py` 重跑生成的资源逐字节一致。之后 `group:ryuka,group:longsword` **5/5 场景、56/56 检查**，`RyukaTest`、`LongswordMotionTest`、`DissolveShapeTest` 13/13，截图看过：待机狐火、凝聚花瓣、只剩刀柄都正常 |
| 试玩反馈第二轮 | 维护者试玩：放出精灵后拿出流火，客户端崩溃（`run/crash-reports/crash-2026-11-08_16.50.18-server.txt`）。与流火无关：维护者用的是 `编译成品/20261108-0028-最新版` 的 Logos，`Spell` 多了第 11 个字段 `element`，附属的 `LogosNatureBridge` 按位置传 10 个参数，第一支箭（精灵射的）飞行时初始化失败。main 上同样存在。改为 `LogosRecords.make` 按记录字段名构造（新字段填 `LIFE`、其余默认值），新增 `LogosRecordsTest` 2 项。另：第四刀回旋斩换成逆胴横扫（不转身），英文名改为 Ryūka，随后物品 id、资源、类名、场景组、世界测试名也从 liuhuo 改成 ryuka（分支改名 `feat/ryuka`；本条以上记录里的名字已一并替换）。用这份新 Logos 跑：单元 15/15（流火、LogosRecords、长剑动作、消散）、世界 `nature_*` 10/10、`ancient_embrace_*` 12/12、`longsword_*` 19/19、`group:ryuka,group:longsword` 5/5 场景 56/56 检查。注意：Logos jar 放在中文路径下时单元测试的模组加载认不出 Logos，测试时复制到英文路径 |
| 单元 | 相关 47/47：新增 `RyukaTest` 4 项（两个视角的动作与招式一致、五段连斩与终式时间轴、0.5 秒弹刀窗口的逐刻边界、网格 496 块 / 刀身 288 块标 fade / 物品模型 = 网格 + (8, 1, 8)；精简后为块数 ≤ 127、刀身 60 块标 fade、切片数上限），其余长剑、打刀、战斗运行时、消散形状、神狐资源照旧通过 |
| 实际客户端 | `group:ryuka` **1/1 场景、21/21 检查**：凝聚、待机狐火、五段连斩（第一、第三人称）造成的是魔法伤害不是近战、五刀加刀气扣了魔力、按下当刻已在格挡、刚架刀挨打弹刀并接突刺、超过 0.5 秒只格挡、举盾僵尸挡不住流火（无形之刃）、斩杀后客户端在放花散、魔力耗尽只剩刀柄且左键不出刀不伤人、魔力回来刀身重新凝聚、切走散去，流火命中时 Logos 的奥秘冲击特效被换成了樱花；魔力护盾：弹开拿斧头的木桩不破防、不花魔力，过了 0.5 秒硬挡多付 20（简单难度下 6 点伤害落到 4 点，共扣 25）、不破防，魔法伤害挡得住，魔力不够时被斧头砍中才破防。截图在仓库外 `art/liuhuo/ingame_v01`（换特效前）、`ingame_v02`（之后，`compare_burst.png` 是对照） |
| 回归 | 加魔力护盾后重跑：`group:longsword` 4/4（35/35 检查）、相关单元测试（流火、长剑、打刀、战斗）25/25。此前：`group:longsword` 4/4、`group:dissolve` 4/4、`group:yumi` 单独连跑两次 1/1（和 `group:uchigatana` 同一次跑时，打刀中途出错后它跟着出错，单独跑都通过）。`group:uchigatana` 在不含本改动的干净 main（`9234727e`，单独的工作树）上同样失败于第 82 步“普通格挡照常消耗耐久”，本分支上那次是第 72 步木桩被突刺打死，都是已知问题 |
| 世界测试 | `logos_dimension:longsword_*` **19/19**：新增 `longsword_ryuka_guard`（拿斧头的木桩刚架刀就砍 → 弹刀，魔力一点不扣、不破防；过了 0.5 秒再砍 → 挡下，扣 7 + 20、不破防；魔法 4 点挡下扣 5；虚空穿透；魔力只剩 8 时被斧头砍 → 挡下扣 7，破防 5 秒）。黑钢长剑的 `longsword_guard`、`longsword_mana_guard` 改成走 `Holder#manaGuard` 后照旧通过 |
| 未验证 | 手感、动作好不好看、樱花刀气的样子、狐火颜色（是否够“淡”）都要维护者试玩；光影包下的透明晶刃和消散；多人下别人看到的凝聚与只剩刀柄 |
## 污秽衍生物：腐败上的刷怪、腐败的样子、精灵神狐的敌人

2026-11-08：分支 `feat/corruption-derivatives`，基于 main `9234727e`。按维护者定的顺序做的第四项：腐败区域昼夜按原版规则刷“污秽衍生物”（僵尸、骷髅、蠹虫、史莱姆的变体），攻击带腐败伤害，站在腐败上白天不燃烧；森林精灵和神狐主动打它们；满了的核心用储备就地生衍生物；被腐败的生物有一套通用的样子。说明见 [腐败](docs/CORRUPTION.md) 的“污秽衍生物”。补伤害 3、储备 20 生一只、30 秒一次、附近 6 只封顶，是我定的，都在配置里。

| 层级 | 结果 |
| --- | --- |
| 世界测试 | `corruption_*` 16 项，连跑 5 次都是 **16/16**。新增 5 项：腐败上怪物候选全换成四种衍生物、草地和别的类不变、自然生成只在腐败上；污秽僵尸打猪多补一次腐败伤害；四种衍生物站在污秽上不受伤，僵尸和骷髅在腐败上看到的亮度是 0；满了的核心用储备生一只并扣掉 20；精灵和神狐都把四种当敌人。顺手修了上一项 `corruption_core_grows_with_network` 的偶发失败（核心的初始电荷把测试场地外的地面也转化了，网络比放的多），场地改铺黑曜石 |
| 实际客户端 | 不装 Sodium 和 Iris：`group:corruption` 4/4 场景、16/16 检查。新场景 `corruption_derivatives`：正午、普通难度，40×40 的污秽上原版刷怪刷出了衍生物，上面没有别的怪，站在污秽上的没有烧起来。看过白天、夜里的并排截图（第一版压暗不够、僵尸身上斑太少，调深了一档、斑和脉加多后再看） |
| 单元 | `./gradlew test` 通过 |
| 未验证 | 光影包下腐败的样子（叠加的发光脉用 `eyes` 渲染类型）；GeckoLib 和别的 mod 用自定义几何画的生物（这一项只管模型画的，下一项再做）；真实生存里刷怪的密度；多人服务器；没进游戏手动玩过 |

## 腐败核心的样子：会长大的肉瘤心脏

2026-11-08：分支 `feat/corruption-core-model`，基于 main `1e05c246`。维护者从三种草图里选了 A 肉瘤心脏，要求下面的根再长一些。核心不再画方块，画体素肉瘤，随网络大小分四级，3 级裂口里的眼珠盯着最近的玩家。说明见 [腐败](docs/CORRUPTION.md) 的“核心的样子”。

| 层级 | 结果 |
| --- | --- |
| 世界测试 | `corruption_*` 11 项全过。新增 `corruption_core_grows_with_network`：网络 99 块时还是 0 级，100、300、600 块时变成 1、2、3 级；0 级碰撞箱半格，之后整格，长大后更亮 |
| 实际客户端 | 不装 Sodium 和 Iris：`group:corruption` 3/3 场景、14/14 检查。新场景 `corruption_core_stages` 两次检查眼珠朝向和玩家方向差不到 12 度（正面、绕到侧面后）。3 级在眼前时平均每帧 8.4 毫秒、最慢 9.9 毫秒。看过白天、夜里、正面、侧面的截图 |
| 面数 | 0–3 级近处 327、1294、3985、9727 个面，粗一档 119、406、1195、2866 |
| 未验证 | 光影包下（按实体画，没实测）；几十个 3 级核心同时在视野里的开销；地面不平时根的样子；没进游戏手动玩过 |

## 腐败：网络上限与子核心

2026-11-08：分支 `feat/corruption-split`，基于 main `b73ed21d`。维护者要求：网络不再合并成一个，而是满了（约 600 块）就在周围分出子核心，可以无限扩张；拆母核心时子核心变成独立的。说明见 [腐败](docs/CORRUPTION.md) 的“分出子核心”。子核心数量（每个 3 个）、间距（16 格）、满员后电荷的去向（种子核心 → 同族最近没满的 → 储备；被动电荷不分给同族）是我定的，都在配置里。

| 层级 | 结果 |
| --- | --- |
| 世界测试 | `corruption_*` 10 项，连跑 5 次都是 **10/10**。新增：挨上的网络不合并；625 格的满网络连种 3 个子核心，彼此和母核心至少隔 16 格、记着母核心、接到电荷，第 4 次杀生的电荷交给子核心，拆母核心后子核心独立；满了又种不了的孤立网络把电荷存进储备 |
| 实际客户端 | `group:corruption` 的 `corruption_core_break` 9/9 检查（网络不到 600 块，走的路和以前一样） |
| 未验证 | 真实地形里长到几千块、几十个核心之后的样子和开销；被动扩张的速度（每个满了的核心每分钟 4 点电荷，只够种子核心）；没进游戏手动玩过 |
## 腐败网络消散时周围客户端卡死

2026-11-08：分支 `fix/corruption-fade-freeze`，基于 main `b73ed21d`。维护者试玩时，上千块的腐败网络拆核心后周围的人都卡死了。原因在客户端：消散按每次发来的块数选精度，而消散波每刻按区块发很多小组，每组都按最细切（每块 6144 格）。现在按客户端上正在消散的方块总数选精度，加了最粗一档（每块 96 格），所有方块影子合计不超过 10 万格，超出的不画影子只冒火星。见 [消散](docs/DISSOLVE.md)。

| 层级 | 结果 |
| --- | --- |
| 实际客户端 | 新场景 `corruption_large_fade`：29×29 污秽上全铺脉络，1681 块，玩家在边上看着拆核心。下表；`group:corruption` 2/2 场景 12/12 检查、`group:dissolve` 4/4 场景（只截图），看过截图 |
| 世界测试、单元 | 没跑：只改了客户端画法和客户端场景 |
| 未验证 | 光影包下（阴影通道还要再画一遍，格数上限同样管用，没实测）；多人服务器上几个玩家同时看着 |

| `corruption_large_fade`，不装 Sodium 和 Iris，1280×720 | 同时的影子 | 一帧最多交出的格数 | 平均每帧 | 最慢一帧 |
| --- | --- | --- | --- | --- |
| 修改前 | 844 块 | 1374465 | 20.7 毫秒 | 793 毫秒 |
| 修改后（各 2 次） | 900 块 | 81192–85740 | 8.9 毫秒 | 72–75 毫秒 |
| 完全不画影子（对照，各 2 次） | 0 | 0 | 8.8 毫秒 | 71–78 毫秒 |

修改后最慢的那两三帧和完全不画影子时一样，是原版在 1681 个方块被换掉时重建区块网格。

`dissolve_blocks` 场景同时散 24 块（墙 15、单块 4、柱子 5），超过 16 块，后到的柱子会比以前粗一档；这是文档里“同时消散的越多格子越粗”本来的规则，以前按组算才没体现出来。

## 腐败：核心、脉络、污秽与网络

2026-11-07～08：分支 `feat/corruption`，基于 main `6d9b4084`，先把消散 #198 重新挑到 main 上（#198 当时合进的是 `feat/glow-bloom`，没有进 main），Logos 用 `builds/flash-stages-2026-11-07` 的 `logos-0.6.0-spell-ward.jar`。说明见 [腐败](docs/CORRUPTION.md)。第二轮按维护者要求：污秽记下原方块，拆核心后变回去，脉络直接消散；为此给消散加了 `Dissolving.replace`。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | `./gradlew test` 通过（没有新增单元测试，网络逻辑都靠世界测试） |
| 世界测试 | 新增 8 项 `corruption_*`，连跑 5 次都是 **8/8**：核心在泥土地上长出脉络和污秽并记在名下、记着原来是泥土，拆核心后变回泥土、脉络消失；猪受伤、戴头盔的僵尸不受伤；生存玩家站在脉络上掉魔力和血；被腐败杀死的猪给网络正好 10 点电荷；两个网络挨上时小的连核心并掉、方块全归大的；拆核心不掉落、近的先散远的后散、直接放的污秽散成空气；生存玩家拿镐拆得掉核心；3721 块的大网络全部还原、存档编码存了再读回来原方块一个不少。`dissolve_*` 4/4。没跑全部世界测试 |
| 耗时 | 大网络那项量消散最慢的一刻：还原原方块 2.2–2.9 毫秒（5 次里另有一次 10 毫秒，像是 JIT 没热），同一网络临时改成只放空气 2.1–2.7 毫秒。这台机器，测试服务器 |
| 实际客户端 | 不装 Sodium 和 Iris：`group:corruption` 的 `corruption_core_break` **1/1 场景、9/9 检查**：铁镐真的挖掉核心，镜头升到头顶 10 格看着核心，第 28 刻核心旁边已经变回草方块、最外圈还是污秽，第 70 刻污秽全部变回草方块、脉络全没，镜头回到玩家，核心没掉东西。看过各阶段截图和放大图：污秽剥落处露出草地，没有深度打架的条纹，影子没有发黑。`group:dissolve` 4/4 场景（只截图，没有检查项），确认原来的方块消散这条路没受影响 |
| 未验证 | 没有进游戏手动玩过；Sodium、Iris 和光影包下没跑（`replace` 的影子外推和邻格光照在光影下没看过）；专用服务器；关服时正在消散的网络重开后接着散（编码测了，关服重开没测） |

消散顺带修了一处：方块消散原来用 `sendToPlayersTrackingChunk`，测试里没注册通道的假玩家看得到那个区块时，服务器直接崩（`Payload logos_dimension:dissolve may not be sent to the client`）。现在和运镜一样先看 `hasChannel`。

世界测试的两个坑：测试原点常在超平坦地面以下，要先清出空气，不然猪会被闷伤；测试框架只强制加载测试结构所在的区块，旁边区块里放的生物不走刻，要自己强制加载并等它进入实体走刻。

## 世界测试：三个与改动无关的偶发失败

2026-11-07：分支 `fix/world-test-flakes`，基于 main `a64b5522`，只改测试代码。`journey_luminous_plants`、`berry_native_growth`、`gate_crossing` 在 CI 上时不时失败，和被测的 PR 无关。共同的背景：`GameTestServer` 每刻之间只把积压的任务跑完、不等下一刻，CI 上空闲时每秒约 5000 刻（`gate_crossing` 的 2 万刻约 4 秒用完）；光照传播和区块生成却在后台线程池里排队，和别的测试生成远行大陆区块的任务抢线程。按刻算的时限在 CI 上只有几毫秒到一秒多。

- `journey_luminous_plants`：放下星露和荧蕨后写死等 15 刻（CI 上只有几毫秒）再读光照。它和 `cloudsea_default` 批里的地形、森林普查同批，后台线程池里排满了远行大陆的区块生成。改成轮询到亮了为止，断言不变。
- `berry_native_growth`：CI 上两次失败（run 41995323855、41998069304）时时限还是 400 刻，#164 改成 6000 刻后 CI 上没再失败，但 6000 刻在 CI 上也只有一秒多。区块是测试结构所在、已经加载的区块，光照引擎在跑，只是光照任务没轮到。两处等光照都改成按真实时间等，原来那句 400 刻的注释改成实际原因。
- `gate_crossing`：盔甲架放得太早，被自动存档卸载了。强制加载让区块直接升到实体走刻，原版 `ChunkHolder` 会取消中间的「可访问」通知，实体管理器在周围 5×5 区块都生成完之前一直把它当成不可见。原测试第 1 刻就放盔甲架，它进了不可见的区段，不走刻；自动存档也按刻算（每 6000 刻一次），第一次读这个区块的实体存档，第二次就把盔甲架存盘卸载（`UNLOADED_TO_CHUNK`），之后等多久都等不到它穿门。CI 上生成这片区块要一两秒，正好跑过两次存档。现在先等门所在的区块进入实体走刻再放盔甲架，失败信息里带上移除原因。真实游戏里自动存档是真实时间 5 分钟一次，所以没改游戏代码。
- 新增 `RealTimeWait.paced`：包住等待的断言，没通过的那一刻多睡 10 毫秒再重试。时限因此至少有「刻数 × 10 毫秒」的真实时间（1600 刻至少 16 秒，6000 刻至少 1 分钟，2 万刻至少 3 分多钟），等到之后不再睡。断言和时限的刻数都没改。

| 层级 | 结果 |
| --- | --- |
| 复现对比 | 见下表。每轮单独起一次测试服务器，只跑这一个测试，JVM 限 4 个处理器（`-XX:ActiveProcessorCount=4`，同 GitHub 的 ubuntu-latest）。「卡后台」用的是不提交的临时代码：放好方块后往 `Util.backgroundExecutor()` 塞 4 个各睡 3 秒的任务，模拟 CI 上后台线程池被别的测试占满；`berry_native_growth` 放萤石后再卡一次 |
| 世界测试 | 去掉临时代码后三个测试各再跑 2 次，**6/6 通过**。没跑全部世界测试 |
| 单元、实际客户端 | 没跑：只改了世界测试代码，游戏代码没动 |
| 未验证 | GitHub CI 上的表现，要合入后多跑几次才看得出 |

| 测试 | 修改前，卡后台 | 修改后，卡后台 | 修改后，不卡 |
| --- | --- | --- | --- |
| `gate_crossing` | **2/2 失败**，`盔甲架应穿过门 on tick 20002`，约 2.2 秒跑完 2 万刻 | 3/3 通过：等了 191–206 刻（约 3.0–3.3 秒）区块进入实体走刻，下一刻放盔甲架，再下一刻穿门 | 3/3 通过，第 4 刻区块就进入实体走刻 |
| `journey_luminous_plants` | **2/2 失败**，`Stardew does not illuminate nearby ground on tick 15` | 3/3 通过，约 3.3 秒 | 3/3 通过 |
| `berry_native_growth` | **2/2 失败**，`Waiting for dark fixture light propagation on tick 6002`，约 0.5 秒跑完 6000 刻 | 3/3 通过，约 6.5 秒（两处各卡 3 秒） | 3/3 通过 |

`gate_crossing` 修改前卡后台的两次，临时诊断输出和上面说的过程一致：开测约 0.95 秒时第 6000 刻自动存档，之后实体存档读进来；约 1.5 秒时第 12000 刻自动存档，盔甲架以 `UNLOADED_TO_CHUNK` 被卸载，这时区块还没进入实体走刻；跑满 2 万刻失败，报错和 CI 上一字不差。修改前不卡后台跑过一次，本机约 0.15 秒（116 刻）区块就进入实体走刻，赶在第一次存档之前，所以本机不卡后台复现不出来。

```text
./gradlew --offline runGameTestServer "-PlogosJar=/absolute/path/logos-0.6.0.jar" "-PdimensionTests=logos_dimension:gate_crossing"
```

## 消散和泛光

2026-11-07：分支 `feat/dissolve`，基于 main `dff12855`，Logos 用 `builds/world-style-2026-11-07` 的 `logos-0.6.0-spell-ward.jar`。说明见 [消散](docs/DISSOLVE.md) 和 [全屏后期的泛光一节](docs/SCREEN_EFFECTS.md)。这部分最初写在 Logos 的工作树里（未提交），在那边用同样的场景调好效果和看过截图，再按维护者要求搬到附属；没有改 Logos。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | `./gradlew test build` 通过；新增 `DissolveShapeTest` 4 项 |
| 世界测试 | 全部 **176/176**。新增 4 项 `dissolve_*`：实体放完才拿走且站住不动，方块立刻离开世界，手上的东西放完才清（人和另一只手不受影响），真的执行 `/alchemy dissolve blocks`（形状名不对、范围超过 4096 时什么都不动） |
| 实际客户端 | 不装 Sodium 和 Iris、只装 Sodium 0.9.2、Sodium + Iris 1.11.4 光影关闭：`group:dissolve,screen_fx,world_style` **14/14 场景、31/31 检查** |
| 光影包 | Iris + photon v1.4、Iris + Complementary Reimagined r5.9.3：**13/14 场景、30/31 检查**，日志确认光影包已加载。没过的是 `world_style_fabulous` 的“高画质透明开着”：开光影时这个选项本来开不了，[WORLD_STYLES.md](docs/WORLD_STYLES.md) 写明这个场景只在不开光影时跑 |
| 泛光自查 | `dissolve_bloom` 确认全部消散结束后通道不再跑。看过关开泛光的对比截图：白天、夜里、方块、第一人称手上的物品 |
| 耗时 | Logos 工作树里量的，代码相同，1280×720，这台机器。泛光 GPU 约 0.3–0.4 毫秒一帧（photon 下，前后 glFinish）。CPU 增加：原版 8 块每像素四格的方块约 2.3 毫秒，5 个村民约 3.5 毫秒；photon 下 16 块约 5.5 毫秒，5 个村民约 10 毫秒（这之后才把阴影通道切粗一档，没有重量） |
| 未验证 | 没有进游戏手动玩过这一版（搬过来之前的版本维护者试玩过，觉得效果不错，细分和泛光是之后加的）；专用服务器（世界测试是服务端环境，但不是真实联机）；其他光影包；和最新 Logos 一起跑；黑白闪和消散同时出现时的画面（代码顺序是泛光在前） |

## 被弹反的僵直

2026-11-07：分支 `feat/parry-stagger`，基于 main `0c745061`，Logos 用 `ba47131`（`feat/spell-ward`）。下表是合入 #193 之前跑的；合入 main `738208be` 后复跑单元 **220/220**（多出的是 #193 的测试）、世界 `longsword_*` **18/18**、`group:parried` **4/4**，其余回归没有重跑。说明见 [COMBAT_SKELETON.md](docs/COMBAT_SKELETON.md) 第 5 节「被弹反」。打刀弹刀、神狐格开近战时，亲手近战的攻击方进入僵直：玩家 0.5 秒（`parried`），NPC 0.8 秒（`parried_npc`，写在招式文件里，用同一把武器的 NPC 都一样）。这段时间不能出招、不能格挡，这一刀作废。曦誓、黑钢长剑、打刀、长枪两段动作都有，神狐目前接的是打刀；和弓、原版和其他模组的生物没有反应。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | `./gradlew test build` 通过，单元测试 **213/213**。新增 `ParryTest` 8 项：五份招式文件的 `parried` 0.5 秒、`parried_npc` 0.8 秒，都无转移、无接触、清连招、回待机；和弓都没有；NPC 优先用 `parried_npc`，没有时退回 `parried`，都没有时不反应；NPC 僵直 16 刻内不接受输入；动作在两个视角与神狐身上都有；连刺第一下被弹开后其余接触不结算；缓冲的下一刀不会在僵直后接上 |
| 动作烘焙 | 改过的编排重新烘焙后逐段对比：长剑、打刀、长枪两个视角只多了 `parried` 和 `parried_npc`，其余动作不变；`divine_fox.animation.json` 只多了 `katana_parried` 和 `katana_parried_npc`。加 `parried_npc` 时玩家的 `parried` 逐字节没变 |
| 世界测试 | `logos_dimension:longsword_*` **18/18**。新增 5 项：玩家弹开玩家（攻击方僵直、左键作废、身后木桩没挨刀、对方不掉血不耗耐久、之后从第一刀重新开始）；上挑被弹开不挑飞；神狐被弹开后进入 `parried_npc`，24 下近战全部命中（平时一半被格开）；神狐格开玩家时玩家僵直，箭被挡时射手不受影响；拿和弓的玩家、原版生物没有反应 |
| 实际客户端 | `group:parried` **1/1 场景、4/4 检查**：四把武器在第一人称、第三人称正面与背后各被弹反一次并截图；真实按键架刀弹开神狐（神狐服务端僵直、客户端播 `katana_parried_npc`），破绽里突刺命中；按住左键砍到神狐格开，玩家僵直；走 AI 的神狐僵直时站住（位移 < 0.2 格），服务端量到 16–19 刻（轮询可能晚一两刻），结束后接着追目标。0.5 秒那版的截图在仓库外 `art/parried_v01/ingame`，`parried_npc` 的离线预览在 `art/parried_v02`，0.8 秒动作没有另外截实机图 |
| 反向对照 | 临时去掉僵直行为跑同一场景，“僵直时站住不动”失败（2/3）；这项检查还抓到一个真问题：行为选择器隔一刻才切换，追击行为多跑的那一下可能触发箭步，神狐滑出约 0.7 格。`StaggerGoal.interrupt` 当刻打断后位移为 0 |
| 回归 | `group:longsword` 4/4 场景 35/35、`group:oath_spear` 8/8、`npc_divine_fox_defense` 3/3、`npc_divine_fox_fight` 3/3、`npc_divine_fox_katana` 8/8。`group:yumi` 12/12 是加 `parried_npc` 之前跑的，和弓的招式文件和动作这一轮没动。`group:uchigatana` 见下一行 |
| 打刀场景不稳定 | `group:uchigatana` 本身不稳定，**这个分支直接跑多数失败**（“普通格挡挡下伤害”），未改动的 main（`0c745061`）四次也失败两次。原因：铁傀儡木桩只有 100 血，两轮连招加火焰缠绕后约剩 12 血，突刺把它打死；之后那一下的伤害没有来源位置，原版无法判定格挡。临时把木桩血量加到 1000（未提交）后这个分支连续 **6/6、每次 8/8**。main 上还见过一次中途等不到突刺超时、一次木桩为空的报错，这里只确认了木桩血量这个原因；前者的原因没有查实 |
| 未验证 | 手感（NPC 0.8 秒是否合适、神狐一半几率弹开玩家是否太频繁）需要试玩；联机两名真实玩家对打（世界测试用的是模拟玩家）；光影包；专用服务器 |

## NPC 渲染：预先算好网格，只画看得见的面，批量写顶点，兼顾光影

2026-11-07：分支 `fix/npc-render-cache`，对应 issue #173。下面的数字都在 main `0c74506` 上测得，之后同步到较新的 main 只重跑了相关场景。维护者反馈神狐多了连 RTX 5080 都卡。JFR 显示渲染线程 82% 的时间花在 GeckoLib 逐方块绘制上（写顶点 46%，每个方块重算旋转 19%，修正法线 12%），`glowBones` 和隐藏集合加起来只占约一成，每帧还分配约 5 MB。

第一轮 `NpcMesh`：方块的旋转提前算进顶点，每帧只乘一次骨骼矩阵；隐藏零件整棵跳过，也不算动画；封闭且不透明的方块跳过背对镜头的面。维护者实机试玩（RTX 5080，平时 100 帧）：20 只 60 帧，开光影 30 帧，50 只不足 20 帧，“好一点但还是卡”。

第二轮：窄边投影不足 0.75 像素的面不画；普通实体格式批量写顶点。JFR 显示这时写顶点占约 75%，其中约一半是 LWJGL 3.4 每次原生写入的 `reinterpret` 权限检查。没有改模型和动画。维护者实机：“还是卡，感觉差不多”，看向神狐的方向就卡，把自己埋了也一样，抬头看天不卡。

第三轮：前两轮只在没有其他模组的 720p 客户端上测过，而维护者实际用 Sodium + Iris + Photon。用 `-PldTestSeedDir` 把实例里的模组和光影搬进开发环境，按实际窗口 3440×1369 复现后发现：Iris 换了顶点格式，第二轮的批量写入根本用不上，渲染线程约七成时间卡在 `addVertex`；神狐还要画两遍（主通道和阴影）。第二轮对背面的判断在阴影通道里也不对。这轮改了：光影下经 Sodium 的 `VertexBufferWriter.push` 批量写；阴影通道按光线方向跳过背面、按离玩家的距离放宽窄边；窄边阈值升到 2 像素，但方块最大的一对面按长边算（第二轮的规则会让长条拼的裙子在约 41 格外整片消失）；被实心方块完全挡住的 NPC 主通道不画；`NpcAnimations.available` 不再每次新建 `Identifier`。说明见 [NPC 模型规范第 5 节](docs/NPC_MODELS.md)。

| 项目 | 结果 |
|---|---|
| 第三轮实机 | 维护者在自己的实例里试玩（RTX 5080，Sodium + Iris + Photon，3440×1369）：“这下感觉流畅多了”，神狐很多的地方也有 60 帧。没有逐项记录当时的 DH 配置和具体只数 |
| 第三轮帧时间 | `npc_fox_render_bench`：外观固定，不限帧率，3440×1369，RTX 5080，Ryzen 9 9950X，Photon 1.4，平地。平均帧时间（50 只的 p99）：只装 Sodium + Iris，第二轮代码 空场地 4.58 / 20 只开 AI 11.11 / 50 只 21.69（32.63）ms，第三轮 4.81 / 8.68 / 13.79（17.77）ms。50 只全被墙挡住 7.87 ms，开一格窗 9.19 ms。关光影 1.06 / 2.21 / 4.35（7.35）ms，全被挡住 1.12 ms。这些运行全程用 JFR 的 `jdk.CPULoad` 确认其他进程只占约 11–15%；期间维护者开着游戏（其他进程 60% 以上）的几轮作废，没有计入。场景没有地形和战斗，真实聚落的数字没有测过 |
| 其他模组 | 把实例里其余模组全搬进来（`create-adapted` 除外，它要官方 Create 的 jar，开发环境加载不了），Distant Horizons 也去掉：50 只 13.08（18.64）ms，和只装 Sodium + Iris 差不多。**加上 Distant Horizons、用维护者现在的配置（16 个线程，`threadRunTimeRatio` 1.0）：空场地 9.52，50 只 24.04（54.95）ms**，10 秒内 GC 24 次、单次停顿最长 28 ms，分配约 4 GB/s，全部来自 DH 的世界生成线程，这就是维护者 F3 上 2861 MiB/s 的来源。改成 4 个线程、比例 0.5：空场地 6.85，50 只 16.68（23.28）ms，GC 5 次、最长 8.6 ms。开发环境是新世界，LOD 一直在生成；已经生成完的区域负载会低一些，没有测过。这是维护者自己的配置，这里没有改动 |
| 第二轮帧时间 | 1280×720，无光影，无其他模组。**和没有任何渲染改动的 main 同一时段交替各跑三轮**（其间机器上还有其他会话的客户端，所以波动大），平均帧时间：20 只站着 19.43 / 19.96 / 12.43 → 3.85 / 4.19 / 2.32 ms；20 只开 AI 18.75 / 20.17 / 11.79 → 4.13 / 2.01 / 1.74 ms；50 只开 AI 46.78 / 46.04 / 25.71 → 7.23 / 4.67 / 4.56 ms；50 只站着 37.94 / 44.59 / 24.78 → 5.37 / 4.51 / 4.56 ms。同期空场地 1.0–2.7 ms |
| 第三轮面数 | 50 只，约 25 格：主通道约 49000 面（每只约 980），阴影约 16000 面（每只约 320）。场景核对：被墙挡住时主通道 0 面、阴影不受影响；开一格窗后主通道约 15000–20000 面 |
| 第三轮画面 | Photon 下人工对比：跳过小面的开和关，中远距离（约 25、40 格）身体和影子看不出差别，长条拼的裙子没有消失；阴影的简化版和保留全部朝向太阳的面的版本，近处耳尖还在，尾巴末端略圆，全景看不出差别。一格窗能看到窗后的神狐。只用了 Photon，其他光影包没有测过 |
| 服务端 | 同一场景 50 只开 AI 闲逛，服务端线程约 9% 的时间在忙，这里的卡顿在渲染线程。没有战斗 |
| 内存分配 | JFR 统计的渲染线程分配，第一轮：每帧约 5.0 MB → 约 0.5 MB |
| 画面一致 | 场景核对神狐和森林精灵每根骨骼的顶点、贴图坐标、法线都和 GeckoLib 一致（带旋转和不等比缩放的姿势，不跳过任何面时比较）；批量写入和逐顶点写的字节一致（每个顶点的填充字节不比）；实际渲染确实走批量写入。背面跳过的开和关各拍一组近景、裙底、背后截图做像素对比：身体没有差异，差异只在云的位置和尾巴摆动相位造成的边缘，没有破洞。窄面跳过的开和关人工放大对比近景和远景，差别是亚像素的细线，看不出缺面 |
| 实际客户端 | **同步到 main `dff1285` 后：`group:npc_preview` 23/23 场景、92/92 检查；装 Sodium + Iris + Photon 跑 `npc_fox_render_bench` 4/4 检查。** 在这之前，第三轮整组跑了两次都没有全过（13/23、12/23），原因见下一行。第二轮整组 23/23、92/92。人工查看拔刀、狐火、拉弓、表情、死亡、短装、精灵的截图（第二轮）和第三轮的精灵队列 |
| 整组失败的原因 | 和这次改动无关的竞态，**没有修**：神狐刚放到位还没落地时，如果第一刻就锁定了目标，`FoxBowGoal.wantsBow` 的 `createPath` 因离地返回 null，`reachable` 留在默认的 true，于是 `FoxKatanaGoal` 先拔刀。站在悬空原木上时就会一个箭步冲下去摔死（调试输出：第 2 刻 `move=katana`，第 3 刻已离开原木，之后 `alive=false`），`wrap_bow` 等不到箭而超时；中断后 `FoxWeaponViews.observe` 还在清怪，后面的场景全部连带失败。**干净的 main（`0c74506`）上也出现了同样的竞态**：`wrap_bow` 单独连跑第 5 轮，第 1 刻手里已经是刀，这次没冲下去，但 `!katanaSeen` 检查失败。交替跑 `weapon_commit` + `wrap_bow`：这个分支 4 轮败 1 轮，main 4 轮全过。把所有带 `wrap_bow` 的运行都算上，分支失败 4/9，main 失败 1/10。分支看着偏高，但次数太少，不能断定有差别；这个分支没有改 `FoxBowGoal`、`FoxKatanaGoal` |
| 单元测试 | `NpcPartsTest`、`DivineFoxAssetsTest` 通过，未跑全部 |
| 未验证 | 战斗中的帧率；Photon 以外的光影包；遮挡判断在快速转过拐角时会不会晚一拍（场景只测了静止的墙和窗）；AMD 或核显；物品栏、Jade 这类界面预览（靠缩放判断不跳过任何面，没有实际看过）；没有跑世界测试（这次没有改服务端逻辑） |

## 灵猫塔罗：二十二张大阿尔卡那

2026-11-07：分支 `feat/mystical-cat-tarot`，基于 main `7864d25`。说明见 [灵猫塔罗](docs/TAROT.md)。

牌面按用户的终稿和《要素说明》用代码重绘，牌面模块不读取任何图片。每张牌都和终稿并排看过，要素清单逐张核对。

| 层级 | 结果 |
| --- | --- |
| 单元 | `TarotAssetsTest`、`LangCoverageTest`、`ItemModelDisplayTest` 通过。没有跑全部单元测试 |
| 世界 | 没跑。这一轮只有不带行为的物品，没有服务端逻辑 |
| 实际客户端 | `tarot_views` 1/1 场景、1/1 检查。日志里没有塔罗牌的模型烘焙失败或缺失贴图。看过截图：牌墙（白天与午夜）、第一人称双手、第三人称前后、掉落物、物品栏 |

截图在 `docs/screenshots/tarot/`，都是普通渲染下的实机画面。夜里发光层按满亮度显示，牌面其余部分随环境变暗。

没有测过光影包，发光效果在光影下不作保证。其他玩家手持时的观感只从第三人称自看，没有开双人。
## 黑钢长剑：施法时手臂伸直

2026-11-07：分支 `fix/blacksteel-straight-arm`，基于 main `7864d25`。按维护者意见，第三人称的施法前指改成手臂完全伸直、剑顺着手臂的延长线，由 `tools/blacksteel_cast_moves.py` 重新烘焙。`cast_aim` 蓄力和持续光柱共用，所以两者都变；`cast_thrust` 的手臂已经伸直，改靠转身和前倾把肩送出去。第一人称动作文件逐字节不变，视线俯仰跟随的代码没动。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | `./gradlew test build` 通过；`LongswordMotionTest` 新增一项：`cast_aim` 手肘弯曲小于 2°，剑与手臂夹角小于 1°，手臂水平朝前 |
| 世界测试 | `logos_dimension:longsword_*` **13/13** |
| 实际客户端 | `blacksteel_cast` **1/1 场景、2/2 检查**；截图看过斜侧平视与低头、施放、背后与光柱 |
| 未验证 | 光影包、联机 |

## 神狐：不在刀和弓之间来回换，苦力怕怕神狐

2026-11-05：分支 `fix/fox-weapon-dither`，基于 main `8fe3fb9`。说明见 [神狐](docs/NPC_RACE_DIVINE_FOX.md) 第 6 节。维护者试玩时发现：约 6 格时背弓的神狐在刀和弓之间快速切换、打不出伤害；苦力怕也不怕神狐。

- **换武器**：离地时原版不算路径（`createPath` 返回 null），原来被当成走不到，一跳就可能换弓；现在只信站在地上查到的结果，换目标时重查。换了武器至少拿 3 秒，够放完一箭；走得到的目标贴到 6 格内时例外，立刻换刀。
- **太近**：原来 6 格内每刻都强制回到执弓，动作每刻重播，也不放箭；现在能退就退，退不开就站着射。
- **施法**：弓模式原来在施法开始前就被清掉，背弓的神狐会先拔一下刀；现在弓留在手上。战斗结束后弓也留在手上，等警戒期（5 秒）过了才收。
- **苦力怕**：原来与躲猫一样只在 6 格内躲开，被箭射中还会把神狐当目标冲过来。现在看到 12 格内的神狐就跑，也不把神狐当目标。

| 场景（实际客户端） | 修之前（同一场景跑旧代码） | 修之后 |
| --- | --- | --- |
| `npc_divine_fox_weapon_commit`：台阶坡上打 20 秒 | 换手 7 次，砍中 9 下 | 换手 0 次，砍中 21–24 下 |
| `npc_divine_fox_close_shot`：悬空原木上退不开，敌人隔 5 格，站 20 秒 | 0 箭，动作重播 396 次 | 中 9 箭（共 9 箭），动作切换 40 次 |
| `npc_divine_fox_wrap_bow`：拉弓时敌人变强，放狐火缠绕 | 拔刀，换手 2 次 | 不拔刀，换手 0 次 |
| `npc_divine_fox_creeper_fear`：9 格外的苦力怕，没弓的神狐 | 最近走到 6.73 格，最远 9 格 | 最近 9 格，跑到 15.8–19.7 格 |
| `npc_divine_fox_creeper_fear`：14 格外的苦力怕被背弓神狐射中 | 把神狐当目标，最近 8.53 格 | 不当目标，最近 14 格 |

旧代码上这四个场景 0/4 通过（检查 2/11），修复后 4/4（11/11）。

| 层级 | 结果 |
| --- | --- |
| 单元 | `./gradlew --offline test`：201/201 通过 |
| 世界 | 合并 main `4d88702` 后 `./gradlew --offline runGameTestServer` 跑了两次：第一次 164/165，`elven_living_world_tree` 等光照超时（6002 刻）；代码不变的第二次 165/165 |
| 实际客户端 | 上面四个场景，加 `npc_divine_fox_katana`、`npc_divine_fox_fight`、`npc_divine_fox_defense`、`npc_divine_fox_bow`、`npc_divine_fox_yumi`、`npc_forest_elf_guard`：10/10，检查 41/41 |

CI 上 `bddf0a7` 也有一次偶发失败：`gate_crossing` 超时（20002 刻，只花了 2.27 秒）；main 上这个测试通过时用了 1380 和 5820 刻，都是约 2 秒。两个偶发失败的测试都没有被这次改动碰到：测试服务器全速空转，按刻算的等待时限可能两秒就用完，等不到区块升级、光照这类要花真实时间的结果。

这些都是读数，没有逐帧看画面。维护者截图那样的悬空布置，旧代码在探针里只复现了不放箭和动作重播，没有复现换手；那里实际地形能否走到对面没有确认。没有测光影、联机。

## 远行之门：另一侧按种群生成不同样式的门

穿门后自动生成的小号门多了鸟居（神狐）、英式尖拱（灵猫）、凯旋门（群狼）、城门楼（人类）四种，加上原来的树门（精灵）共五种。远行大陆一侧随机，主世界一侧仍是树门；主世界自然生成的大号门没动。做法见 [远行之门](docs/JOURNEY_GATE.md)。

| 项目 | 结果 |
|---|---|
| 单元测试 | 全部 **201/201** 通过 |
| 世界测试 | `./gradlew runGameTestServer` 全部 **165/165**。`gate_small_opening_edges` 改为五种样式、两个朝向都查：格数对、朝向对、启门石是满的，门框上每个半格只补一次；新增 `gate_style_pick`：主世界一律树门，远行大陆五种都出现 |
| 实际客户端 | `gate_views` **1/1 场景、8/8 检查**。人工查看每种样式的正面、斜面、背面、转到 Z 向和夜里的截图；玩家真走进门到远行大陆（这次随机到凯旋门）再走回来 |
| 未验证 | 五种门在真实地形上的样子（坡地、林间）：客户端里只有一扇是真实生成的远行大陆，其余放在超平坦上；专用服务器；联机旁观者；光影包 |

## 运势：九种空壳效果与完整的抽签流程

2026-11-05：分支 `feat/omikuji-luck`，基于 main `a99a65d`。说明见 [运势](docs/LUCK.md)。抽签除原版幸运、霉运外再随机带几项运势，都管到下一次日出；运势记在玩家身上，效果只是图标，牛奶和死亡洗不掉；只有今天的凶签才解得掉今天的凶运。九种运都还没有实际效果，只提供 `Luck.level` 供玩法读取。签文每种签运 8 句、每个签项每档 8 句，中英文各 328 句，抽到时随机挑。文字由十个子代理分别起草，维护者同意后统稿改了 15 处：狐仙捂脸的句子太多、一句叫人向神社添谷子（赛钱箱不收谷子）、一句重复了项目名，以及跑题、撞句和英文拼法。

| 层级 | 结果 |
| --- | --- |
| 单元 | `FoxShrineGoodsTest`、`LangCoverageTest` 通过（未跑全部单元测试）：每个效果都有图标和中英文名字，每支签的项数都挑得够，每一句签文的键中英文都在 |
| 世界测试 | `logos_dimension:omikuji_*` **6/6**（未跑全部世界测试）。新增三项：大吉带两项二级运势、幸运 II 正好管到日出、身上已有低级福运也盖得上去、睡到天亮后运势读不到且图标消失、末吉不带运势、大凶不会抽到失物运；牛奶和死亡后记录还在、图标补回来、今天抽过不重置；昨天的凶签、吉签、老存档的签都解不了，今天的凶签解得掉且不再补回来，绳上满了还能系 |
| 实际客户端 | `omikuji_clockless`、`omikuji_fortune_views`、`omikuji_fortune_views_zh`、`fox_interior_views` **4/4 场景**。人工查看截图：物品栏列出运势名字与等级，签纸显示签文和签项，中英文都放得下，图标没有紫黑缺图块。英文提示框原来宽到折行，评语改成 excellent/good/poor/dire、待人改成 Guests 后放下了。328 句里只在截图中实际看到抽中的几句，其余只核对了长度与键。截图里同时有幸运 II 和霉运 II，是场景同一天连抽两次造成的 |
| 未测 | 真实喝牛奶与真实死亡（测试调用同样的清除与重生接口）、真实睡进床里、联机多玩家。截图里右上角的提示框挡住了部分图标，全部图标是否进图集靠单元测试查文件，只目视确认了露出来的那部分 |

截图：[大吉的签纸与效果](docs/screenshots/luck/daikichi.png) · [大凶的签纸与效果](docs/screenshots/luck/daikyo.png) · [全部图标](docs/screenshots/luck/icons.png)

## 远行之门：主世界与远行大陆之间的传送门

做法见 [远行之门](docs/JOURNEY_GATE.md)。Logos 用 `build-latest-20261105/logos-spell-ward` 的 0.6.0 jar。

| 项目 | 结果 |
|---|---|
| 单元测试 | 全部 **199/199** 通过（含语言文件覆盖） |
| 世界测试 | `./gradlew runGameTestServer` 全部 **158/158**，其中新增 7 个 `gate_*`：大号门放正和转 90° 都判定出 57 格；小号门 21 格，四个楼梯各补一次；各种魔力物品的数值，被污染的碎片不收，别的物品照常使用；走 `ManaVessel` 灌满开门、门洞逐步填满、打掉门框连锁消失且魔力还在、修好后重开、挖走启门石门消失且掉落；玩家自搭门框缺一块开不了，合拢后 9 格；盔甲架真过门，另一侧小号门和传送票，再过一次到同一扇，回来落在原来的门；结构、结构集、模板池、群系标签 |
| 实际客户端 | `gate_views` **1/1 场景、8/8 检查**：经服务端发包的点亮特效各阶段；楼梯和活板门补缝的近景；两个朝向的小号门；`place structure` 放出的大号门没有点亮；玩家真走进门到远行大陆再走回原来那扇；盔甲架过门后，远行大陆没有玩家也在走刻，传送票过期后停止。人工查看截图 |
| 未验证 | 专用服务器；联机旁观者；光影包；真实地形上自然生成的大号门（`gate_views` 用 `place structure` 放在超平坦上，世界测试只查数据接线）；传送面和树叶、洞穴藤蔓在光影下的排序 |

```text
./gradlew test runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:gate_*"
./gradlew runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=gate_views" -PldTestHeadless -PldTestWindow=1280x720
```
## 抽签：一天一抽改按维度时钟

2026-11-05：分支 `fix/omikuji-day`，基于 main `7b84a14`。原来用游戏总刻数除 24000 算天：睡觉和 `/time set` 只拨维度时钟、不动总刻数，所以睡醒还是抽不到，睡过之后换天的时刻也和日出错开。现在按玩家所在维度的时钟算，刻数 0 是日出，日出换天；下界这类没有时钟的维度跟主世界。每个时钟分开记（附件 `omikuji_days`，旧的 `omikuji_day` 不再读取，老玩家最多多抽一次），来回换维度不能多抽。

| 层级 | 结果 |
| --- | --- |
| 单元 | `FoxShrineGoodsTest` 通过（未跑全部单元测试） |
| 世界测试 | `logos_dimension:omikuji_*` **3/3**：同一天点不出第二支、日出前最后一刻仍是同一天、日出后能再抽；晚上抽过，睡醒时间点之后能再抽且总刻数没动；主世界与远行大陆各抽一次后来回传送都不能再抽。三项在旧代码上都失败 |
| 实际客户端 | `omikuji_clockless`、`fox_interior_views` **2/2 场景、4/4 检查**：下界没有时钟，主世界过一天后下界也换天；实际点签箱第二次不再给签，系签解霉运不变 |
| 未测 | 真实睡进床里（测试直接把时钟拨到原版睡醒用的时间点）、联机多玩家、完整的世界测试与单元测试 |

## 石中剑：去掉一人一把的限制

2026-11-05：分支 `fix/dawnvow-no-vow-limit`，基于 main `b108dac`。按维护者意见：“已立誓”的限制容易出问题，玩家可能永远拔不了下一把（剑丢了、毁了、掉进岩浆，就再也没有剑可以插回石座解除）。

- 去掉一人一誓：拔剑不再记录，手上有没有曦誓都能拔。破晓与按住 3 秒的规矩不变。
- 手持曦誓右键空石座仍能插回去，保留那把剑本身；不再有“誓约解除”的提示。
- 删掉玩家附加数据 `logos_dimension:dawnvow_sworn` 和两条提示。旧存档里立过誓的玩家，读档时 NeoForge 会报一行未知附加数据并跳过，下次保存后就没有了。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全部通过（语言文件覆盖检查含在内） |
| 世界测试 | `logos_dimension:dawnvow*` 12/12：`dawnvow_one_vow` 换成 `dawnvow_draw_again`，背包里已有一把时破晓还能拔出第二把、石座变空 |
| 未测 | 实际客户端没有另拍：只删了服务端的判定和提示，画面不变；旧存档读档的那行报错没有实测，依据是 NeoForge 跳过未知附加数据的行为 |

## 神狐和弓：神狐用和弓、拉弓跟随视线俯仰、右臂、其他生物的拿法

- 神狐背的弓换成随机一把和弓，用与玩家相同的招式和射法，箭也是和弓的箭（箭速 4.5、约 11 点）。旧存档里背的原版弓读档时换掉。
- 第三人称拉弓时箭跟着视线俯仰：玩家的上半身绕腰转一半（最多 25°），双臂连同弓绕两肩中点转其余的；神狐的上半身绕腰转（最多 50°）。
- 会时右臂对照弓道照片改了：上臂从肩向后伸，手肘在身后、与肩差不多高，前臂折回来差不多与箭同线。之前手肘抬到头顶高度，前臂与箭差约 72°，现在约 16°。
- 骷髅等生物手里的和弓按原版弓的持物变换换算：弓弭到弓弭 16.5 像素、弓背朝前、握把在拳心。之前弓面转了 90°，长度约为原版的 1.5 倍。

| 项目 | 结果 |
|---|---|
| 单元测试 | 全部 **195/195** 通过；`YumiTest` 新增右前臂与箭同线、上臂朝身后；`DivineFoxAssetsTest` 新增和弓骨骼挂在 `Yumi` 下、四套配色的方块组与 `DivineFox` 隐藏用的一致、动作时长与招式一致、动作不碰刀的 `Weapon`、执弓不带下半身 |
| 玩家 | `group:yumi` **1/1 场景、12/12 检查**。新增按画出来的箭核对三维夹角（余弦 > 0.97）：抬头 40°、低头 30°、左手惯用抬头 30°。人工查看胸前一侧的平视、抬头、低头截图 |
| 神狐 | 新增 `npc_divine_fox_yumi`（四只各拿一套配色，执弓、打起し、大三、会、抬头、低头、残心）。`npc_divine_fox_bow`：背和弓的神狐射倒苦力怕，射出的箭带和弓标记、箭速超过 3。`npc_divine_fox_katana`：拉和弓时刀佩在腰间，弓上有狐火。人工查看截图：四套配色都对，会时左臂笔直，抬头时箭朝上 |
| 骷髅 | 人工对照原版弓、和弓、左手惯用的和弓（正面与侧面，截图 `skeleton_compare_*`）：长度、倾斜方向、握的位置一致，左手惯用是镜像。骷髅仍用原版 AI 射原版箭 |
| 回归 | `npc_divine_fox_fight`、`npc_divine_fox_defense` 通过（5 个神狐场景合计 22/22 检查）；`fox_sanctuary_residents` 通过；`group:longsword` 4/4 场景、35/35 检查。`fox_sanctuary_residents` 要单独跑：它结束后玩家留在别处，排在它后面的 `npc_divine_fox_*` 场景的白瓷台区块加载不了，会超时 |
| 未验证 | 俯仰时弓的魔力缠绕；联机旁观者；专用服务器；第三方镜头 mod |

## 修复全新克隆上失败的 GameTest

2026-11-05：Logos 用 MinecraftLogosMod `ba47131`（`feat/spell-ward`）构建。五个问题都出在测试上，没有改产品代码。

| 测试 | 原因 | 修复 |
| --- | --- | --- |
| `journey_log_families`、`cloudsea_registry` | #93 加灵狐樱野后是 33 种，测试还写着 32 | 原木族改为与群系数相同，群系数改为 33；客户端检查 `TreeFamilyViews` 同样改 |
| `cloudsea_tree_blocks` | `locateBiome` 搜不到内部点时退回边界点，样方跨进相邻的山毛榉林；这些横向原木来自邻近群系的树。`ancient_beech` 的特征只用自己的原木 | 改找四周 32 格都是目标群系的样地，找不到就失败；“只有古冠山毛榉原木”的断言不变，样方内 1237 根原木全部符合 |
| `settlement_native_assembly` | 固定采样点 (1536, 1536) 在海面上，#120 的水面检查把起点放弃了，这是正确行为 | 改用曦光林隙里的实地起点；新增断言：水面起点不生成 |
| `economy_bank_liquidates_and_stops` | 不是残留状态：`gametestserver` 世界每次启动都会重建。测试顺序每次运行都可能不同（`Map.of` 的迭代顺序每个 JVM 不同），钱庄若已结到较早的一天，刚开的仓位会白拿跨过的夜的利息 | 与 `economy_bank_manage_position` 一样，开仓前先结算。确定性复现：开仓前让钱庄先结到第 4 天，修复前余额 119909（断言要求低于 100000），修复后通过 |

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | 全新克隆上 `./gradlew test build` 通过，单元测试全部通过 |
| 世界测试 | 全新克隆上 `./gradlew runGameTestServer` **151/151**，合并最新 main 前后都通过 |
| 未验证 | 实际客户端（`TreeFamilyViews` 的检查只编译过）；钱庄结到第 4 天的状态在完整运行里怎么产生，没有继续追，只用上面的确定性复现验证了机制 |

## 神狐和弓：放大弓身、会时左臂伸直

弓放大到玩家身高的约 1.3 倍（`scale` 1.25 → 1.625，第三人称弓高 32 → 41.6 像素）；神狐 2 → 2.3 倍（弓高 73.6，约身高 54 的 1.36 倍）。会与残心时左臂完全伸直，之前第三人称左肘弯约 92°、第一人称约 61°、神狐约 97°。拉弦的右手不变。

| 项目 | 结果 |
|---|---|
| 单元测试 | 全部 **193/193** 通过；`YumiTest` 新增 1 项：第一、第三人称会与残心时左肘弯曲 < 2°（打起し > 15°，确认量的确实是弯曲） |
| 动作烘焙 | 三副骨架会时左肘弯 0.5° 以内。第三人称会的 `RightArm`、`RightForeArm` 旋转与放大前的动作文件逐值相同。弓的下弭在玩家、神狐所有动作里都不入地（最低离地 0.06 像素） |
| 实际客户端 | `group:yumi` **1/1 场景、9/9 检查**。人工查看截图：胸前一侧（新增 `kai_side`）左臂笔直、弓高过头顶、下弓身弯向身后，足踏み的双腿张开；第一人称前臂朝前、箭从右下指向握把，准星未被挡住；执弓时下弭离地 |
| 回归 | 只改了和弓的数据与测试场景，没有改其他武器共用的代码，没有再跑长剑等场景 |
| 未验证 | 神狐使用和弓（神狐背的还是原版弓，和弓的动作只有离线预览）；骷髅手里的尺寸没有改 |

## 神狐和弓：第三人称转身方向修正

试玩发现第三人称拉弓时整个人朝反方向（箭指着视线的反面）。原版身体朝向应加 `-turn`（Root 转身 -90° 时 `bodyRot` +90°），之前加的是 `+turn`；同时转满时身体以视线为准，不再跟着原版落后视线的身体。

| 项目 | 结果 |
|---|---|
| 单元测试 | 全部 **192/192** 通过（基于 main `225d39c`） |
| 编译依赖 | main 的魔力格挡要用 `SpellWard`：Logos 用仓库外 `build-latest-20261105/logos-spell-ward`（`ba47131`）的 0.6.0 构建 |
| 实际客户端 | `group:yumi` **1/1 场景、9/9 检查**。新增 4 项按第三人称实际画出的箭（`BowArrow` 骨骼经这一层的世界朝向）核对箭与视线的水平夹角（余弦 > 0.97）：会时、会中视线左转 70° 后、转回木桩后、左手惯用时。人工查看正上方俯视截图：右手、左手惯用都是持弓手伸向视线正前方，箭沿视线、从脸前经过 |
| 反向对照 | 把符号改回 `+turn` 跑同一场景，新增的 4 项检查全部失败（5/9），确认检查能抓到这个问题 |
| 回归 | `group:longsword` 4/4 场景、35/35 检查通过（同一渲染层） |

## 神狐：行为、打刀、狐火缠绕与聚落居民

2026-11-05：分支 `feat/divine-fox-ai`，基于 main `c3b83d3`。说明见 [神狐](docs/NPC_RACE_DIVINE_FOX.md) 第 3、5–7 节与 [神狐聚落](docs/FOX_SANCTUARY.md)。

- **战斗**：神狐主动攻击附近的敌对怪物，对玩家中立；被打时附近的神狐一起反击。
- **打刀**：每只一把随机刀装，与玩家共用打刀招式状态机。决定攻击后突进，然后连砍。
- **弓**：约四分之一背原版弓，打苦力怕、够不着的目标或远处目标时用弓；苦力怕像躲猫一样躲神狐。
- **狐火缠绕**：遇上生命超过 50 的敌人时，在安全间隙放维护者的 Logos 术式，外观换成幽蓝的物品表面火焰，画在刀身和弓上。缠绕结束而战斗未完时再放。
- **防御**：格挡弹射物 80%、近战 50%，近战被格开后突刺反击。铁甲不显示、不掉落。原版魔法减免 40%。
- **逃跑**：重伤时逃跑。
- **表情与语音**：表情随行为切换；语音九个事件。
- **刀的显示**：刀与鞘平时收起，警戒时出现在左腰，神乐礼装总是佩刀。
- **聚落居民**：每处至多 20 只，死去后每 2 分钟补回一只。
- **外观**：狐纹出现几率改为一成。

| 层级 | 结果 |
| --- | --- |
| 单元 | `./gradlew --offline test`：187/187 通过（`DivineFoxAssetsTest` 13/13：打刀骨骼与招式时长一致、佩刀层、持弓动作、狐纹一成、九个语音事件；`SanctuaryResidentsTest`：每套方案落脚点 12–20 个、都在街上、彼此隔开、同一处固定） |
| 实际客户端 | `npc_divine_fox_katana`（12 项）：佩刀/收起、拔刀、持刀待机、第一刀、缠上狐火后刀身画出狐火、换弓拉弦时弓上狐火、收刀后只剩神乐礼装佩刀 |
| 实际客户端 | `npc_divine_fox_fight`：真实 AI 对 80 血尸壳，盯上、缠狐火、突进（最快 0.71 格/刻）、连砍、砍倒 |
| 实际客户端 | `npc_divine_fox_defense`：格挡弹射物 41–52/60、近战 29–34/60（三次运行），10 点魔法伤害只掉 6 点，重伤后逃跑拉开距离 |
| 实际客户端 | `npc_divine_fox_bow`：背弓神狐拉弓射倒苦力怕，苦力怕最近 5.9–6.8 格（躲神狐），拉弓时刀佩在腰间 |
| 实际客户端 | `fox_sanctuary_residents`：生成的聚落落脚点 20 个、加载到 20 只，打倒三只居民数减三，补回一只加一 |
| 实际客户端（回归） | 神狐原有六个场景 27/27，森林精灵 `npc_forest_elf_guard` 8/8 |

用的是 Logos main（`7e56851`）的构建：狐火缠绕要用到魔力缠绕与游脉形态，更旧的 Logos 里这项法术编译不过（神狐只是不缠狐火）。横扫游脉的特效仍是 Logos 默认的样子。没有测光影、联机。
## 神狐和弓：弓道射法、分节弓身、和弓的箭

| 项目 | 结果 |
|---|---|
| 美术交付 | `art/fox_yumi/v01` 四套配色几何、UV 相同，GeckoLib 解析通过；弓身已分 17 节、两段弦与搭箭点，交付没有动作和箭（见 docs/YUMI.md 第 1 节） |
| 单元测试 | 全部 **188/188** 通过；新增 `YumiTest` 5 项：两个视角的动作与招式一致（`lower` 倒放 `uchiokoshi`）、物品的举弓与引分け刻数与招式时长一致、举弓中松手倒放回执弓、0.45 + 0.6 秒拉到会并一直持满、残心 0.75 秒内不能再拉、四套配色共用弓的骨骼（都挂在 `Weapon` 下，每个方块都绑在存在的骨骼上，箭附件存在）、执弓无箭、会时箭在弦上且第三人称两段弦保持原长（±3%）、离れ后箭立刻不见、第三人称会时 Root 转身 -90° |
| 动作烘焙 | 打刀用改过的 `combat_author.py` 重新烘焙，与提交的动作文件逐字节相同 |
| 实际客户端 | `group:yumi` **1/1 场景、5/5 检查**：举弓中松手不放箭、箭不减；满弓一箭命中 14 格外的铁傀儡、伤害 ≥ 9（原版满弓 6）；用掉一支箭；射出的箭带和弓标记；骷髅拿着和弓用原版 AI 射出原版箭（不带标记）。人工查看截图：第三人称侧身对靶、打起し举过头顶、大三、会、离れ与残心、骷髅持弓；第一人称执弓、打起し在左上方、会时箭从右下指向弓、命中粒子 |
| 回归 | `group:longsword` + `group:oath_spear` 5/5 场景通过；`group:uchigatana` 停在“突刺命中”（木桩已被打死），与群誓长枪一轮记下的已有问题相同 |
| 编译依赖 | Logos 用仓库外 `build-latest-20261104/logos-main` 的 0.6.0 构建（main 需要 `CastPose`、`wrap_cost` 等） |
| 未验证 | 足踏み的双腿张开：原版第三人称镜头总在视线上，侧身后只看得到侧面，没有截到正面；联机旁观者；光影包；专用服务器；神狐使用和弓（动作只有离线预览） |
## 黑钢长剑：魔力格挡

2026-11-05：分支 `feat/mana-guard`，基于 main `c3b83d3`。按维护者意见，黑钢长剑的竖剑格挡改成魔力格挡：

- 原版攻击：原版盾牌该掉多少耐久就扣多少魔力（伤害不到 3 不扣，否则 1 + 伤害取整），剑不掉耐久；魔力不够就挡不住。
- 魔法伤害（原版 `magic`、`indirect_magic`）也挡。
- 正面飞来的敌方魔弹、光柱、游脉停在剑前：借用 Logos 炼成体拦截法术的逻辑，拦截费与炼成体相同，再加命中伤害折成的魔力，从持剑者的魔力里付。
- 虚空属性一律穿透。
- 格挡声换成紫水晶块敲击声，破防声换成紫水晶簇碎裂声（与 Logos 炼成体相同）。

需要 Logos 的 `SpellWard`、`Constructs.addWards` 与 `Effects.dealing()`（MagicFoxDemon/MinecraftLogosMod#375，分支 `feat/spell-ward`）。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | `./gradlew test build` 通过（按 Logos `feat/spell-ward` 编译） |
| 世界测试 | `logos_dimension:longsword_*` **13/13**。`longsword_guard` 加查挡下 6 点伤害扣 7 魔力；新增 `longsword_mana_guard`：魔法伤害挡住并扣 5 魔力，虚空伤害穿透，魔力不够（3）时 6 点伤害挡不住也不扣，正前方敌人伤害 5 的魔弹停在剑前、扣 6.5 魔力，虚空魔弹穿过打中，不许作用于玩家的术式穿过不扣魔力 |
| 未验证 | 实际客户端（新声音、法术停在剑前的样子）；光影包；联机；光柱、游脉、巨像炮顶在护盾上（只测了魔弹） |

## 群誓·狼牙长枪

2026-11-04：分支 `feat/oath-spear`，基于 main `86fc980`，之后合入 main `b845d68`（#150 黑钢长剑施法）。设计见 [群誓·狼牙长枪](docs/OATH_SPEAR.md)。新增长枪（战斗武器编号 6）：从背后转两圈拔枪、转一圈背回；左键直刺、横扫、过肩刺、上挑四段连击，上挑把目标挑上半空（体型越大浮空越短，非玩家期间不能行动）；右键按住一秒八刺、落点各不相同，刺浮空目标伤害翻倍并维持浮空，枪尖放出往前冲的残影；连刺三秒后转枪下砸，结束浮空并结算原版摔落伤害。招式文件新增多段 `contact`、`damage`、`cone`、`knockback`、`full_strength`、`effect`；动作标记新增 `tip_fx`，`contact` 可多次出现。

| 层级 | 结果 |
| --- | --- |
| 编译 | `./gradlew compileJava -PlogosJar=…`（Logos 0.6.0，本机 11-03 构建）通过 |
| 单元测试 | 全部 **183/183** 通过；新增 `OathSpearTest` 7 项：两个视角的动作与招式一致（时长、每次 contact、tip_fx、cancel），四段连击在取消点衔接、第四击是挑飞，按住右键三段共 24 刺（相邻两刺 2.5 刻）后下砸、下砸后仍按着会重新连刺，松开立即停，连击命中后按右键直接转连刺，浮空时长按体型（玩家 32 刻、铁傀儡 16、劫掠兽 14、鸡 48），物品模型 = 网格 + (8, 1, 8) |
| 动作烘焙 | 打刀用改过的 `combat_author.py` 重新烘焙，与提交的动作文件逐字节相同 |
| 实际客户端 | `oath_spear` **1/1 场景、8/8 检查**：魔力缠绕产生表面；关着 AI 的木桩被挑飞后放回原高度；连击造成伤害；上挑后木桩浮空且升高 0.8 格以上；连刺时枪尖残影出现；挑飞的 1.6 秒过后连刺仍维持着浮空；下砸结束浮空、木桩回到地面；下砸受到摔落伤害 10 点（摔落伤害类型）。截图在仓库外 `art/pack_oath_spear/ingame_v01`（拔枪转枪、待机、第一人称、上挑、连刺侧面与残影、下砸、收枪） |
| 合并 main 之后 | 按 Logos `feat/wrap-focus`（`350e839`，带 `wrap_cost`、`wrap_power`，#150 起 main 需要它）重新编译；单元测试 183/183；`group:oath_spear` + `group:longsword` **5/5 场景、43/43 检查**（含 #150 新增的 `blacksteel_cast`） |
| 回归 | 合并前 `group:longsword` 3/3 场景通过；`group:uchigatana` 在本分支停在“突刺命中”（木桩已被打死），在不含本改动的 main 上连跑两次停在第 82 步（普通格挡耗耐久），都是已有问题 |
| 未验证 | 被挑飞的是玩家（联机 PvP）；光影包；专用服务器启动；NPC 使用长枪 |

## 黑钢长剑：格挡与施法

2026-11-04：分支 `feat/blacksteel-cast`，基于 main `ed4527f`。按维护者意见，黑钢长剑可以像法杖一样施法：

- 潜行右键打开 6 格载页栏位，R 打开术式轮盘，蓄力速度为 Logos 第 3 档法杖（流量 70、加速 130）。
- 右键竖剑格挡（原版格挡组件，0.3 秒前摇，不损耗耐久），前方浮现一圈奥秘符文；格挡中左键开始蓄力，蓄满后松开右键施放。
- 蓄力时单手剑尖指向前，施放后按 Logos #373 的 `CastPose.gesture` 判定：光柱开头一直前指到光柱结束，其他向前一送再收回。
- 物品模型加 `wand_tip` 挂点（剑尖），按剑实际画出的位置上报给 Logos 的 `CastPoints`。
- 用它施放的魔力缠绕及其引发的符文花费 −30%、伤害 +30%（Logos 新增的 `wrap_cost`、`wrap_power` 属性，见 MagicFoxDemon/MinecraftLogosMod#374）。
- 施法和魔力缠绕时剑身铭文按元素色发光。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | 全部通过（编译依赖带 `wrap_cost`、`wrap_power` 的 Logos 分支 `feat/wrap-focus`） |
| 世界测试 | `logos_dimension:longsword_*` 12/12：新增 `longsword_guard`、`longsword_cast`、`longsword_cast_beam`；`longsword_charge`、`longsword_cancel` 改用曦誓（黑钢长剑没有蓄力下劈了，曦誓松手另有剑气，下劈伤害改为不少于 10.5）；`longsword_contact` 照旧通过，说明补回的剑伤害与攻速没变 |
| 实际客户端 | `group:longsword` 4/4 场景、35/35 检查。新场景 `blacksteel_cast`：格挡第一、第三人称与符文圈，蓄力第一人称、正面、斜侧（平视、抬头、低头）、背后，施放向前一送，光柱期间前指与第一人称光柱；日志 `[cast point]` 在两种视角都是剑尖坐标（第一人称约在眼前 1.6 格，第三人称约 1.6 格、偏右 0.1 格） |
| 未测 | 光影包、联机多客户端、言出法随与连咏坠挂在剑上的情况；施法动作是程序编排的临时动作，第一人称送剑时右手有约 0.08 臂长够不着 |

## 远行大陆水下景观

2026-11-04：分支 `feat/underwater-flora`。设计见 [水下景观](docs/UNDERWATER.md)。新增海草、高海草、萤光海草、海带（头/茎）与海底小石堆，方块类照原版 SeagrassBlock / TallSeagrassBlock / KelpBlock / KelpPlantBlock / BlockPileFeature 改写（石堆允许放在水里）；生成特征接在水面群系与海底的 `cloudsea_root_caves`。

| 层级 | 结果 |
| --- | --- |
| 编译 | `./gradlew compileJava -PlogosJar=…` 通过 |
| 世界测试 | 未新增 GameTest |
| 专用服务器 | `./gradlew --offline runServer -PlogosJar=…` 启动成功（`Done (0.296s)!`），水下植物的方块与 configured/placed feature 正常加载，无未知 feature 或数据包错误 |
| 实际客户端 | `underwater_views` **1/1 场景、4/4 检查**：群系接入 placed feature、远行大陆实生水下植物计数 > 0（本次 1713）、水箱内海草/高海草/海带/石堆存活；统计显示水草支撑方块为沙 460 / 砾 4（补地表规则前是石头 463）；截图 `docs/screenshots/underwater/tank.png` |
| 未验证 | 珊瑚、鱼群（后续批次）；光影包；多人同步 |

第二轮（同分支）：海床改照原版主世界地表规则，只作用于被水淹没的地面（沙底群系水下为沙，其他群系浅水泥土、深水砾石），并接入原版沙/黏土/砾石海床圆盘；海草与海带的数量和高海草比例改用原版暖海/河流/深海的数值；海底小石堆只往水格里堆；方块类改为继承原版类，只覆盖写死原版方块的方法；萤光海草改用原版 `simple_block`，删除重复的生成特征；三张只差颜色的海草贴图重画为各自不同的形状；海草/高海草改为剪刀才掉落；五种水下植物加入 `logos:fragile_plants`。

| 层级 | 结果 |
| --- | --- |
| 编译 | `./gradlew jar -PlogosJar=…`（Logos 0.6.0）通过 |
| 专用服务器 | 扫描构建出的 jar：除 `client` 包与 mixins.json 里 `client` 段的 mixin 外，没有类引用 `net.minecraft.client`；通用类指向 `client` 包的只有只传通用类型的静态调用。未在真专用服务器上启动 |
| 世界测试 | 未新增 GameTest |
| 实际客户端 | `underwater_views` **1/1 场景、8/8 检查**：群系接线（含原版 `disk_clay`）、衰退标签、海草空手不掉/剪刀才掉、海带空手可掉；泻湖与海湾附近各生成 7×7 区块统计——泻湖海床 9409 列：砾 8796、石 613（其中 552 列在洞穴/峡谷挖开处 4 格内，海床裸石 61 列 0.6%）；海湾 9409 列：砾 8200、沙 568、泥土 305、黏土 18、石 318（挖开处 227，海床裸石 91 列 0.97%）；水下植物 8183 / 3683；水下石堆 42 / 29 块，岸上石堆 0。截图 `docs/screenshots/underwater/tank.png`（水箱）、`docs/screenshots/underwater/seabed.png`（实际生成的海湾海床） |
| 未验证 | 衰退法术的实际施放（只核对了标签）；珊瑚、鱼群；光影包；多人同步 |

## 命中锥形张角按武器设定

2026-11-04：分支 `feat/weapon-cone-param`，基于 main `ed4527f`。按维护者意见，锥形命中判定（#142）写进文档，并让以后新增的武器能给自己的参数：

- `CombatWeapon` 注册时链式调用 `withCone(度数)` 给出张角，不给就是 `CombatWeapon.DEFAULT_CONE`（60°）；张角须在 (0, 180] 内，越界在注册时报错。长度仍是攻击距离属性。两把长剑和打刀都用默认值，手感不变。
- 曦誓金光取武器自己的张角与 90° 中较大的那个。
- [战斗骨架规范](docs/COMBAT_SKELETON.md) 第 4 节新增「命中锥形」：注册示例、参数表、所有武器共用的规则。
- README 补回合并 #143 时被覆盖掉的锥形判定那句话。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全部通过（新增 `CombatWeaponTest`：不给张角时为 60°，给了用自己的，越界报错） |
| 世界测试 | `logos_dimension:longsword*` 9/9；`logos_dimension:dawnvow*` 12/12 |

## 修复：大额杠杆单靠自己的冲击凭空赚钱

2026-11-04：分支 `fix/fx-impact`，基于 main（含 #139）。玩家在创造模式拿大量钱 25 倍做空 5 万金冠狐钱，冲击把狐钱砸到接近零，浮盈显示一百多万金冠。原因有三：冲击按成交时的价值算，砸低价格后原数买回几乎不推价；穿仓亏损封顶在保证金，用自己的平仓冲击把另一笔仓位推到穿仓就能让钱庄替自己赔；成交价取冲击中点，大单拆开换顺序时开平不完全抵消。改为冲击按数量、以锚定价折算，成交价对冲击路径积分，单笔冲击超过 0.3（对数）拒绝、累计冲击封顶 ±1.5，穿仓亏损记成欠款，旧的超大仓位作废并退回保证金。

| 层级 | 结果 |
| --- | --- |
| 单元 | `MarginTest` **18/18**（新增 4：玩家报告的单子被拒、三种货币多空各以最大允许单子立刻开平都不赚钱且价格回到原处、连砸八笔做空再全平不赚钱、累计冲击封顶不归零不无穷；穿仓测试改为欠款）；`FxMarketTest` **7/7**（新增：三种货币最大允许现货单原路换回不赚钱且价格回到原处）；模拟结果见设计文档，25 倍乱炒因可欠款变为平均 −108% |
| 世界测试 | 经济全部 **8/8**，连跑 3 次都通过（钱庄管理测试加上玩家报告的情形：6 万金冠存款下 25 倍做空 5 万金冠狐钱的市价单和挂单都被拒、价格不动；强平测试改为穿仓扣进余额） |
| 实际客户端 | `bank_views` 6/6、`economy_views` 4/4，照旧 |

```text
./gradlew test --tests "dev.logos.dimension.economy.*" -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:economy_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=bank_views,economy_views" -PldTestHeadless -PldTestWindow=1280x720
```

## 曦誓拔剑与收剑的动作：托剑凝成、剑花、插地

2026-11-04：分支 `feat/dawnvow-flourish`，基于 main `ba681e1`。按维护者意见：

- 拔剑改为双臂前伸、剑横着在掌上凝成，右手握住转两圈剑花再双手持剑。
- 收剑改为把剑插进地面后散去，比拔剑快。

动作由新的 `tools/dawnvow_moves.py` 用 `combat_author` 编排生成，叠在长剑动作上。

| 层级 | 结果 |
| --- | --- |
| 离线预览 | 第三人称左前、右侧、背后三个机位各十帧，检查托剑、转剑、回到待机，以及倒转、插地、松手 |
| 单元 | 全部通过（`MovesetTest` 的曦誓继承检查改为新的片段名与 1.8 秒拔剑） |
| 世界测试 | `logos_dimension:longsword*` 8/8；`logos_dimension:dawnvow*` 11/11（蓄力测试的开始时刻挪到拔剑 1.8 秒之后） |
| 实际客户端 | `dawnvow_summon` 1/1 场景、6/6 检查：第三人称正面与第一人称各按动作的五个时刻拍拔剑、四个时刻拍收剑；凝成之前和散去之后都不画剑。`group:longsword` 3/3 场景、33/33 检查 |
| 未测 | 光影包、联机多客户端；第一人称插地时右手有约 0.08 臂长够不着，画面上看不出 |
## 近战锥形判定与准星暴击；曦誓金光放宽

2026-11-04：分支 `feat/combat-cone`，基于 main `ba681e1`。按维护者意见：剑不用准星精确指着目标也能打中。

- 本附属所有近战武器（长剑、打刀和以后的同类）改为准星前方的锥形判定：默认张角 60°，长度为原版攻击距离。锥形里看得见的生物都挨一刀，准星正对的暴击。
- 每个目标都按出刀时的攻击蓄力结算（`AttackStrengthAccess` 读写原版的攻击蓄力计数）；这一刀不再另触发原版横扫。
- 曦誓放出巨型剑气后的 60 秒内：锥形放宽到 90°，长度按强化后的 6 格；蓄力斩剑气走两倍远（32 格）。
- 顺带修好 `longsword_combo` 的偶发失败：连招放过自己造成的受击冷却时，原先按游戏刻推算目标剩余冷却，目标没被 tick 时冷却不递减，被误判成“别人刷新过”，后两刀被挡。现在改看目标最后一次被谁、在哪一刻打中。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全部通过 |
| 世界测试 | `logos_dimension:longsword*` 9/9，修复后连跑六次全过：新增 `longsword_cone`，检查正前方铁傀儡暴击 10.5、斜前方 35° 受 7、70° 外和攻击距离外不受伤；原有命中断言改为准星正对的暴击值。`logos_dimension:dawnvow*` 12/12：新增 `dawnvow_charge_blessed_wave`，金光期间锥形为 90° |
| 未测 | 实际客户端没有另拍：判定都在服务端，画面不变。打刀没有世界测试，与长剑共用同一段判定代码 |
## 神狐：尾巴自然弯曲与更灵动的摆尾

2026-11-04：分支 `feat/divine-fox-curved-tails`，基于 main `dc9f831`。说明见 [神狐](docs/NPC_RACE_DIVINE_FOX.md)。三种尾巴不再笔直伸出：静止时尾根向后下方伸出，中段下垂，尾尖上翘并略偏向一侧；三尾向两侧散开。常驻摆尾改为各节依次延迟、摆幅向尾尖渐大的柔和波浪，外半段摆到两端时微微上扬。侧倒死亡动画按新尾巴重新生成。

| 层级 | 结果 |
| --- | --- |
| 单元 | `./gradlew --offline test`：170/170 通过（`DivineFoxAssetsTest` 10/10，新增尾巴静止时下垂、尾尖上翘的检查） |
| 离线 | 模型目录：3 种尾巴 × 14 套衣装与 5 种发型，23 个姿势的穿模检查不比笔直尾巴差（最深 0.55，笔直时 0.58），最低点离地 2.5；两版死亡动画落地不穿地；GeckoLib 解析与烘焙通过 |
| 实际客户端 | `npc_divine_fox_lineup`、`npc_divine_fox_back`、`npc_divine_fox_death`：3/3 场景、14/14 检查通过；背面截图里三种尾巴的弯曲都正常 |

没有测光影。

## 炒汇：挂单、持仓管理、预警与随身行情

2026-11-04：分支 `feat/fx-orders`，基于 main（含 #119 钱庄与 #128/#131 超电磁炮）。设计见 [异世界货币与外汇](docs/ECONOMY.md)。钱庄改为账户、杠杆、持仓三页并加账户总览；杠杆可挂单、设移动止损，预览约可承受反向波动；持仓可追加保证金、加仓、部分平仓、一键全平、改止损；兑换台可挂现货单；爆仓预警、价格提醒、离线通知上线补发；钱囊加行情、持仓两页；屏幕角落行情条。

| 层级 | 结果 |
| --- | --- |
| 单元 | `MarginTest` **14/14**（新增 6：追加保证金推远爆仓线、加仓按均价合并、分两半平与一次平一致、移动止损只进不退且多空方向正确、挂单与提醒的触发方向、账户连同挂单提醒存读档与总览）；`FxMarketTest` 6/6；模拟结果不变 |
| 世界测试 | 经济全部 **8/8**，连跑 4 次都通过（新增 2：现货买卖挂单冻结与成交入钱庄、杠杆挂单夜里不撮合、撤单退款、夜里提醒照样触发；追加保证金、预警不强平、移动止损从现价起算、平一半、加仓、一键全平） |
| 实际客户端 | `bank_views` **1/1 场景、6/6 检查** 与 `economy_views` 4/4（中文）：经网络挂单、追加保证金、设移动止损，持仓页选中管理，钱囊行情页设提醒、持仓页，屏幕角落行情条收到数据 |

测试里发现：各测试来回拨世界时钟时，钱庄会补算跨过的夜的利息，世界测试先结一次再开仓。这是正常行为（睡觉跳过夜晚也会计息）。没测：多人同时挂单撮合、光影。

```text
./gradlew test --tests "dev.logos.dimension.economy.*" -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:economy_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=bank_views,economy_views" -PldTestHeadless -PldTestWindow=1280x720
```

## 曦誓：去掉剑鞘，拔剑与收剑改为光点聚散

2026-11-04：分支 `feat/dawnvow-summon`，基于 main `ae3f1aa`。按维护者意见：

- 曦誓不挂剑鞘：武器注册上标明，剑骨架加载时跳过剑鞘部件。
- 拔剑改为金色光点旋着聚拢、凝成剑的样子；收剑改为剑散成光点飘散。
- 曦誓的招式继承长剑，只换拔剑和收剑两段，双手直接持剑。招式文件新增 `extends` 继承。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全部通过，新增 `MovesetTest.dawnvowInheritsLongswordWithOwnDrawAndSheathe`：合并后能解析，拔剑与收剑换成 `idle`，其余八段与长剑完全相同，一秒后进入待机 |
| 世界测试 | `logos_dimension:longsword*` 8/8，`logos_dimension:dawnvow*` 9/9 |
| 实际客户端 | `dawnvow_summon` 1/1 场景、2/2 检查：凝成之前不画剑、散去之后不画剑；第三人称正面与第一人称各拍拔剑、凝成、收剑；待机时腰侧没有剑鞘 |
| 未测 | 光影包、联机多客户端；黑钢长剑与打刀的拔剑没有重拍（未改动它们的招式与网格，世界测试照旧通过） |
## 曦誓蓄力彩蛋第二轮：贴地劈开、镜头抖动、剑身金光保留

2026-11-04：分支 `feat/dawnvow-charge-2`，基于 main `ae3f1aa`。按维护者实测后的意见：

- 巨型剑气改为沿地面走：迈上一格高的坎，劈开两格以上的墙；剑气高度以上、左右各 3.5 格内硬度不超过 3 的方块削掉，不碰脚下；正前方劈不开的方块处停下爆开。
- 修复中途松手后身上的光不消失：中途松手也会劈出普通剑气，之前收尾只在没劈出剑气时才通知客户端收光。现在只要进入过大型蓄力就通知；四周已升起的光点也一起在一秒内淡完。
- 爆闪与光柱冲起时镜头抖动。
- 放出巨型剑气后剑身长金光保留 60 秒，期间手持曦誓攻击距离 +3、每击 +15 物理伤害。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全部 161/161 |
| 世界测试 | `logos_dimension:dawnvow*` 11/11（新增 `dawnvow_charge_carves`、`dawnvow_charge_blessing`）；`logos_dimension:longsword*` 8/8，但 `longsword_combo` 偶发失败（三刀只记到一刀）：在未改动的 main `701a997` 上连跑三次同样一次失败，与本分支无关，没有修 |
| 实际客户端 | `dawnvow_charge` 1/1 场景、11/11 检查：中途取消后收干净、三种生物伤害、土石墙削开而台阶与地面还在、爆发时镜头抖动、放出后剑身金光保留与攻击距离 6 格 |
| 未测 | 光影包、联机多客户端、真实山地与洞穴里的长距离剑气 |
## 神狐：四款短装

2026-11-04：分支 `feat/divine-fox-short-outfits`，基于 main `701a997`。说明见 [神狐](docs/NPC_RACE_DIVINE_FOX.md)。零件表新增八套可随机抽到的衣装：朱白巫女短和服、紫绛金边短和服、夜紫玫瑰短浴衣、米白樱花短浴衣，每款有短袜、过膝袜两版，袜子与木屐随衣装一起换。侧倒死亡动画按 37 个零件重新生成，新衣装的裙片、垂袖与飘带倒地时都不穿进地面。导入时去掉线性插值就能还原的死亡中间帧（误差 < 0.001），动画文件从 4.4 MB 降到 1.35 MB。

| 层级 | 结果 |
| --- | --- |
| 单元 | `./gradlew --offline test`：162/162 通过（`DivineFoxAssetsTest` 9/9，新增短装可抽到、过膝袜挂点、死亡动画覆盖垂袖与裙片） |
| 离线 | 模型目录：短装、长装、表情、两版死亡动画检查全部通过；GeckoLib 解析与烘焙死亡动画通过，共 785 根骨骼 |
| 实际客户端 | `npc_divine_fox_short_outfits`、`npc_divine_fox_death`：2/2 场景、10/10 检查通过；截图里八套短装和死亡三阶段都正常 |

没有测光影；走路时短裙不随腿摆动（与长装相同，没有布料物理）。

## 曦誓的蓄力彩蛋

2026-11-03：分支 `feat/dawnvow-charge`，基于 main `8e9dc79`。说明见 [曦誓的蓄力彩蛋](docs/DAWNVOW_CHARGE.md)。

- 蓄力斩带出放大的物理游脉剑气：16 格，15 点物理伤害。
- 按住 7 秒进入大型蓄力：站定，四周升起金色光点，身上出现金色蓄力光，开始扣魔力，共 1000。12 秒剑身金光，15 秒蓄满冒金色流火。
- 蓄满后放出巨型剑气：64 格，生命 ≤50 击杀、≤100 受 50 穿过，>100 处或射程尽头十字爆闪后接光柱（半径 16 格，200 点）。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全部 161/161 |
| 世界测试 | `logos_dimension:dawnvow*` 9/9（石座 6 项和蓄力 3 项）。`logos_dimension:longsword*` 8/8（改了 `LongswordCombat` 的逐刻与接触挂钩，原有连招、蓄力、取消、换槽、丢弃、遮挡与距离照旧） |
| 实际客户端 | `dawnvow_charge` 1/1 场景、6/6 检查：真实按住右键 15 秒，各阶段截图；服务端在实机里确认尸壳被击杀、铁傀儡剩 50、监守者剩 300 |
| 未测 | 光影包、联机多客户端、坡地与洞穴里的剑气 |

我自己定的、需求里没写的：15 秒蓄满；魔力从 7 秒到蓄满按刻平摊；没蓄满或魔力不足时退回普通剑气、已付魔力不退；生命值按当前生命算；巨型剑气不理会地形、不破坏方块；普通剑气穿过生物不停；没有冷却。
## 绘马：书写页与绘马挂的挂、看、取

2026-11-03：分支 `feat/ema-writing`，基于 main `8e9dc79`。说明见 [神狐聚落](docs/FOX_SANCTUARY.md) 的绘马与绘马挂两行。`journey_ema` 改为可书写的绘马：右键打开照实物用代码画的绘马页（LDLib2，五角屋形板、白编绳、竖印「願意」），文字存在 `logos_dimension:ema_wish` 组件里，合上即存、可改写；只有写好的绘马能挂，挂到指着的那一格，右键翻看，潜行空手右键取下指着的那块。

| 层级 | 结果 |
| --- | --- |
| 编译 | `./gradlew --offline compileJava -PlogosJar=…`（Logos 0.6.0）通过 |
| 单元 / 世界测试 | 未新增，未运行 |
| 实际客户端 | `ema_views` **1/1 场景、13/13 检查**：`./gradlew --offline runClient -PlogosJar=… -PldTest=ema_views -PldTestHeadless -PldTestWindow=1280x720`。书写区打开即获得焦点；一行打到板边后不再收字；合上后服务端从三块一叠里分出一块写好的、手上剩两块空白；空白绘马挂不上；写好的挂到点中的右格并被用掉；翻看从点中的那块开始、翻页顺序正确、页码显示「2 / 3」；潜行取下中间那块，其他两块留着，取下的仍带愿望与署名。人工查看截图 |
| 未测 | 输入法实际打中文（依赖 Logos 的 LdlibIme，对所有 LDLib 界面生效）、「写好了」按钮的真实点击（测试器的松开动作落在已关闭的页面上，改用 Esc 路径合上）、联机多客户端、专用服务器加载 |

截图：[书写页](docs/screenshots/ema/write.png) · [翻看页](docs/screenshots/ema/view.png) · [挂了三块的绘马挂](docs/screenshots/ema/rack.png)

## 石中剑：曦誓之洞

2026-11-03：分支 `feat/dawnvow-stone`，基于 main `5c5efc4`。说明见 [石中剑：曦誓之洞](docs/DAWNVOW_STONE.md)。新增插着曦誓的石座（不可破坏、亮度 14、斜光）与空石座，破晓按住右键拔剑、一人一誓、插回解除；远行大陆山体里的隐蔽山洞由 Java 结构 `logos_dimension:dawnvow_grotto` 选址开凿（维护者同意，AGENTS.md 已登记）。

| 层级 | 结果 |
| --- | --- |
| 单元 | 全部 **161/161**（含 `LangCoverageTest`：两块石座都有 `block.` 与 `item.` 中英文名字） |
| 世界测试 | `logos_dimension:dawnvow*` **6/6**：不可破坏/不怕爆炸/亮度 14、破晓拔满 3 秒拔出并立誓、正午拔不动、已立誓拔不动、中途松手不累计、插回解除誓约且保留那把剑（带名字）。各测试拨同一个世界时钟，所以每个测试单独一个环境、分批依次跑 |
| 实际客户端 | `dawnvow_grotto_views` **1/1 场景**：开着结构生成定位最近的山洞（种子世界里离出生点约 1000 格、第 25 个候选），确认中央是插着剑的石座、洞内只有预期的方块（植物、藤、地衣、孢子花、小池），拍洞口、窄洞尽头、洞内、正午与破晓的斜光；破晓连续拔 19 次后石座变空、金色光柱出现 |
| 未测 | 光影包、联机多客户端、多处山洞的分布统计以外的世界 |

选址统计（开发时临时计数，已移除）：841 个候选里 57 个中心够高、23 个通过全部条件，约 2.7%；候选间隔 20 个区块，平均约每 2000×2000 格一处。斜光按维护者意见改成丁达尔效应式：顶端窄、往下变宽并渐隐，整体很淡；光身里几缕直的细光线。第一版分段各自朝向镜头，段与段之间边缘错开，看起来弯弯曲曲；改为每个分段点只算一次侧向、相邻段共用，光路是直的。
## 油漆：漆面、画刷与调色盘

2026-11-03：分支 `feat/paint-system`，基于 main `e5a9206`。设计见 [油漆](docs/PAINT.md)。新增漆面方块（薄层、无碰撞、颜色存方块实体、整面/半砖两种轮廓）、画刷（原版刷子动作，按住刷）、调色盘（可放置，右键打开 256 色调配页），配方、创造栏与掉落表一并补齐。楼梯、栅栏等非整面方块不支持刷漆。

| 层级 | 结果 |
| --- | --- |
| 编译 | `./gradlew build -PlogosJar=…` 编译与资源处理通过；本机 `:test` 测试执行器无法启动（Gradle 报 `version is null`），**单元测试未运行，不计为已验证** |
| 世界测试 | 未新增 GameTest |
| 实际客户端 | **3/3 场景、7/7 检查**：`./gradlew --offline runClient -PlogosJar=… -PldTest=paint_tops,paint_views,paint_palette_ui -PldTestHeadless -PldTestWindow=1280x720`。漆面颜色写入方块实体、半砖 `cover=half_bottom`、顶面整面/半砖变体、支撑方块移除后漆面自动消失均断言通过；人工查看截图确认染色后木纹清晰、顶面贴平 |
| 未验证 | 光影包、多人颜色同步、生存获取（配方与掉落已加，未实测） |

截图：[调配页](docs/screenshots/paint/palette.png) · [漆面与木纹](docs/screenshots/paint/surface-on-wood.png) · [顶面](docs/screenshots/paint/tops.png)

## 神狐：第一版生物、侧倒死亡与常驻叠加层

2026-11-03：分支 `feat/divine-fox`，基于 main `8625daf`。说明见 [神狐](docs/NPC_RACE_DIVINE_FOX.md)。新增神狐实体与刷怪蛋（随机外观、闲逛看人、受伤逃开），模型、贴图、零件表与动画由 `tools/divine_fox_assets.py` 从仓库外模型目录导入：待机、借用的车万女仆移动动作、维护者选定的侧倒死亡，以及眨眼（睫毛随眼皮移动、睁开后高光抖一下）、耳朵抖动、尾巴轻摆三个常驻叠加层。

| 层级 | 结果 |
| --- | --- |
| 单元 | `DivineFoxAssetsTest` **8/8**（含表情登记）：零件组都在模型里、随机外观往返、应有的动作齐全、动画骨骼都在模型里、没有 `ysm.*`、死亡停在末帧、叠加层首尾一致；叠加层不被待机与移动类动作盖过、死亡时全部被接管。`NpcPartsTest`、`ForestElfAnimationFileTest` 照旧。全部单元 159/160：唯一失败的 `LangCoverageTest` 是 main 上兑换台与钱庄柜台缺中文名，与本分支无关 |
| 世界测试 | 没跑：这一版只有原版的闲逛与逃跑，没有需要世界测试的玩法逻辑 |
| 实际客户端 | `npc_divine_fox_lineup`、`npc_divine_fox_back`、`npc_divine_fox_face`、`npc_divine_fox_death` **4/4 场景、18/18 检查**：五种外观同步与正确显示，三个叠加层控制器都在播放，死亡时三只按倒地、侧倒中、受击的顺序。另做了一次诊断（临时把眨眼层定在闭眼那一帧）确认游戏里眼球与瞳孔收拢、睫毛不变形只下移；正常眨眼只闭约 0.1 秒，截图步骤太慢，拍不到。维护者调整了全部狐耳角度、下移脸颊纹、给垂眼加睫毛方块后重新导入，4/4、18/18 照旧；加入五种表情后另加 `npc_divine_fox_expressions`，5/5 场景、23/23 检查 |

没测：多人、光影、真实玩家打死的完整流程（场景里用一击致命）。注意 main 上 `placed_feature/settlement_wheat.json` 的 `xz_spread` 超过原版上限 ±16，任何世界都加载不了；客户端测试时临时改成 ±16，没有提交，修复另开分支。

```text
python tools/divine_fox_assets.py
./gradlew --offline test --tests dev.logos.dimension.DivineFoxAssetsTest -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=npc_divine_fox_lineup,npc_divine_fox_back,npc_divine_fox_face,npc_divine_fox_death" -PldTestHeadless -PldTestWindow=1280x720
```

## 钱庄：存款、借款与杠杆交易

2026-11-03：分支 `feat/fx-margin`，基于 main `9cb4b7d`。设计见 [异世界货币与外汇 · 钱庄](docs/ECONOMY.md#钱庄bankblockbankmargin)。新增钱庄方块（账户与杠杆两页）、按玩家记账的存款（死亡不掉）、帝国币借款当保证金（只显示负数，授信 100 金冠）、做多做空、玩家 1–25 倍（数据与计算兼容到 500 倍）、强平、止损止盈、隔夜利息。兑换台的 K 线抽成共用的 `CandleChart`。

| 层级 | 结果 |
| --- | --- |
| 单元 | `MarginTest` **8/8**：一倍开平只亏点差、25 倍约跌 3% 强平且亏损不超过保证金、做空下跌赚钱、止损止盈方向、套息正负、500 倍可算且 501 倍拒绝、授信额度与借款不能变现及存读档、杠杆策略模拟；`FxMarketTest` 6/6 照旧 |
| 世界测试 | 经济全部 **6/6**（新增 2）：存入全部、超玩家倍数拒绝、借钱开仓使帝国币为负、欠钱时不能取光存款、超授信拒绝、手动平仓；砸盘后 25 倍多单强平、带止损的空单止损，通知与余额正确 |
| 实际客户端 | `bank_views` **1/1 场景、3/3 检查**（中文）：经网络存款、借钱开三笔、杠杆页 K 线画出开仓与爆仓线、砸盘后强平并出现通知；`economy_views` 4/4 照旧 |

没测：多人同时开仓、离线期间跨多夜的利息累计（单元层面按夜数相乘）、光影。钱庄方块贴图是代码画的初版。

```text
./gradlew test --tests "dev.logos.dimension.economy.*" -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:economy_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=bank_views,economy_views" -PldTestHeadless -PldTestWindow=1280x720
```

## 异世界货币与炒外汇（第一版：原型与现货）

2026-11-03：分支 `feat/fx-economy`，基于 main `b983fb0`。设计见 [异世界货币与外汇](docs/ECONOMY.md)。新增帝国、神狐、灵猫、群狼四种货币共十三枚钱币，全服共享的动态汇率市场（随机事件、传闻、玩家冲击），兑换台方块（K 线、下单、告示与传闻），万国钱囊（无限存放、取一枚或一组、锁定、捡钱入囊），以及给以后商人用的自动换汇结账。顺带修好 main 上 `zh_cn.json`、`en_us.json` 打刀格挡提示后缺的逗号（两份语言文件原本都不是合法 JSON）。

| 层级 | 结果 |
| --- | --- |
| 单元 | `FxMarketTest` **6/6**：面额拆分、任意两步三步兑换都不赚钱（无三角套利）、砸盘压低汇率且几天内恢复、存读档后汇率消息 K 线一致且随机数接着走、四百天不越界、策略模拟（100 金冠起步、200 次 × 30 天：乱炒平均 −39%，买入不动 +3%，看可靠传闻 +15%） |
| 世界测试 | 新增 **4/4**：钱囊存入取出、散币付款找零、结账自动卖帝国币且锁定后不卖、兑换台夜里不营业白天成交离远不能交易 |
| 实际客户端 | 新增 `economy_views` **1/1 场景、4/4 检查**（中文界面）：五天行情与传闻送达客户端、兑换台截图、经网络下单一百金冠换狼币、钱囊截图、一键存入散币 |

没测：多人同时交易、光影、和商人 NPC 的结账（商人还没有）。钱币和钱囊图标、兑换台方块贴图是代码画的初版。

```text
./gradlew test --tests dev.logos.dimension.economy.FxMarketTest -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:economy_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=economy_views -PldTestHeadless -PldTestWindow=1280x720
```

## 神狐打刀：四套刀装、连招、零延迟格挡与弹刀

2026-11-02：分支 `feat/fox-uchigatana`，基于 main `1b51501`。规划与做法见 [神狐打刀](docs/UCHIGATANA.md)。新增四件打刀物品（墨漆米白柄、墨漆朱绳柄、靛漆靛青柄、拭漆木褐绳柄），左键四段连招，右键按住立即格挡（原版格挡组件，延迟 0），格挡开始 0.3 秒内被击中弹刀（不耗耐久），弹刀后左键突刺，切走物品时转刀两圈收刀。长剑与打刀改为共用运行时（`CombatWeapon`），招式文件新增 `block` 输入。玩家的手改为原版那样的一整块（长剑同样生效）。动作按刀路编排，用两段式逆运动学烘焙到第三、第一人称骨架，没有腕关节。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **135/135** 通过，`test build` 成功；新增 `UchigatanaTest` 5 项：两个视角的动作与招式文件时长和动作标记一致、四段连招与出刀中立即格挡、只有 0.3 秒内的格挡才弹刀并接突刺、弹刀后回到格挡、四套刀装共用网格且立体物品模型就是刀身网格平移 (8, 1, 8) |
| 世界测试 | 长剑 8 项跑 3 次：1 次全过，2 次只有 `longsword_combo` 失败，报错都是“只有第一下掉血”，与 main 上已知的测试环境问题相同；其余 7 项每次通过 |
| 实际客户端 | 新增 `group:uchigatana` **1/1 场景、8/8 检查**：实际按键拔刀、四段连招造成伤害、出刀中按右键服务端当刻即在格挡、刚架刀被击中弹刀且不掉血不耗耐久、按住左键接突刺并命中、超过 0.3 秒只格挡且照常耗耐久、第一与第三人称魔力缠绕都贴在刀上、切槽收刀；`group:longsword` **3/3 场景、33/33 检查**（换成整块手之后重跑） |

维护者在实际客户端试玩过第一、第三人称，按反馈去掉了腕关节、修正了前臂自转造成的肘部破面和第三人称手肘外翻，并让魔力缠绕对齐刀身。物品栏图标是按刀装配色生成的占位图。没有测光影、联机延迟；弹开后禁用对方武器与神狐 NPC 还没做。

```text
./gradlew --offline test build -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew --offline runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:longsword_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=group:uchigatana -PldTestHeadless -PldTestWindow=1280x720
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=group:longsword -PldTestHeadless -PldTestWindow=1280x720
## 神狐聚落：山顶聚落、上山小路与和风小品

2026-11-02：分支 `feat/fox-sanctuary`，基于 main `1b51501`。重做灵狐樱野的聚落生成（取代未合并的 `f7f8435` 与 main 上的 `cherry_road`），详见 [神狐聚落](docs/FOX_SANCTUARY.md)。维护者同意此结构用 Java 结构类型，AGENTS.md 记有例外。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **134/134** 通过，`test build` 成功；新增 `FoxSanctuaryDataTest`：设计图尺寸与入口、全部分块与地块变体模板存在、结构集间距与 Java 常量一致、樱野的树与地被只认自然草皮；`CherryTerrainTest` 跟着改认 `fox_wild_*` |
| 世界测试 | 未跑 GameTest：测试服务器关闭结构生成 |
| 实际客户端 | `fox_sanctuary_views` 与 `fox_props_views` **2/2 场景通过**。开发场景用反射打开结构生成，聚落随区块自然生成（候选 63 个后命中，主街 Y=100，山路 174 格）；逐张查看山脚、山腰、石板桥、鸟居段、入口石阶、登顶主街、食摊、街尾、千本鸟居、神社、旅馆门前、中庭、露天风吕、田地、住宅与小品近景 |

试玩反馈修正（同日）：路边削挖后同步 `*_WG` 高度图（山脚别的群系的树不再悬空）；屋面从墙顶横梁起出檐；室内改整块榻榻米，2 格门洞不用潜行；旅馆每层一跑楼梯；障子拉门打开时滑进墙；和纸灯笼换新模型；手水舍改为石砖托手水钵、四周含水楼梯；新增绘马挂（1–4 层、挂三块玩家绘马并显示铁砧名）、绘马、赛钱箱、鈴緒。单元 **134/134**；客户端 `fox_props_views`、`fox_sanctuary_views` **2/2** 通过，查看了小品陈列、旅馆楼梯、住宅屋顶、神社与手水舍截图。

合入屋台分支后（同日）：主街中线 13 个屋台、六种变体；重新生成数据后单元 **134/134**，客户端 `fox_props_views`、`fox_sanctuary_views` **2/2** 通过，查看了登顶主街、屋台近景与小品陈列截图。

第四版（同日）：灰瓦/朱漆楼梯方块状态改为与原版一致（转角不再错位）；障子门打开时略内收防闪烁、重画物品图标；绘马挂可连成 3×2 大架子；神社石阶与赛钱箱修正；屋台吊牌留白；重做旅馆中庭与露天风吕，街尾加茶庭，街道、巷口、住宅区与草坪加满布置。模板方块 ID 全部对照 26.1 原版与本附属方块状态核对（发现并改正 `chain` → `iron_chain`）。单元 **134/134**；客户端 `fox_props_views`、`fox_sanctuary_views` **2/2** 通过，查看了中庭、茶庭、住宅、登顶主街截图。

第五版（2026-11-03，分支 `fix/fox-sanctuary-round5`）：温泉地热石改为加热正上方整段水柱（水或含水方块，最多 8 格），生物每秒检查一次得生命恢复，池底可铺含水半砖；露天风吕池底铺含水石半砖；手水舍楼梯改为高侧朝外、四角内角封水，四边加一级上台石阶；参道、狐像、本殿与绘马挂对齐拝殿中线，神木老樱移出拝殿；旅馆两侧后院（枯山水、菜园柴棚水井）、门前野点与西墙外绿篱；樱野的浮萍改用逐格判定自然水底的副本，温泉不再长浮萍。`FoxSanctuaryDataTest`、`CherryTerrainTest` 通过（全量单元里 `LangCoverageTest` 因 main 上 `bank_counter`、`exchange_counter` 缺中文名失败，与本分支无关）；客户端 `fox_sanctuary_views` **1/1** 通过，新增检查：受伤的牛放进露天风吕三秒后有生命恢复；冬季温度下露天风吕水面不结冰、中庭池（碎石底）会结冰（温泉水柱由 `HotSpringFreezeMixin` 拦下 `Biome.shouldFreeze`）；查看了手水舍、拝殿正面、露天风吕、两侧后院、野点与中庭截图。

第六版（2026-11-03，分支 `fix/fox-sanctuary-round6`）：灯笼串两端立朱漆栅栏柱；拱桥量池宽两端落岸、桥面 4 宽、扶手立在桥面上只连同高；神社平台四周草坡每格至多降一格，剩下的陡坎砌石垣。随机化：三套布局方案（a 原布局；b 神社东南、旅馆东北、九十九折、五重塔、茶园、两层旅馆枯山水中庭；c 大弯主街、三段石阶、竹林小径、果园、两排住宅、旅馆两翼对调加木桶汤），三种山路样式，园景地块（樱两种、松、花坛、竹丛、灌木石灯、水井、只有草），放置时的小替换（暖帘与幟颜色、花草盆栽、作物、石砖风化、酒桶干草堆肥桶）。顺带修正原布局里一间北侧商铺伸进食摊广场两格。`FoxSanctuaryDataTest`（三套方案）、`CherryTerrainTest` 通过；客户端 `fox_sanctuary_views` 逐套各 **1/1** 通过（a 配石段参道、b 配竹林道、c 配山野小径），每套的回血与冬季不结冰检查都通过，查看了俯瞰、参道、拝殿、旅馆门前与中庭、露天风吕、街尾空地、山路截图。

第七版（2026-11-05，分支 `feat/fox-sanctuary-round7`）：布局方案加到六套（新增 d、e、f，旅馆格局 onsen、lodge，街尾空地莲池与足汤，满地水田）；旅馆客房隔间、半砖嵌床、楼梯与二层走廊、私汤与两池内汤；居酒屋三种、两层大宅、住家与商铺内饰和楼上卧室；田地与路基切面砌石垣；主街水渠换含水石台阶；绘马挂正面朝路；隔板补到天花板；外缘树只长进空格子。新方块：签箱、结签架、签纸、菜单条；石龛 `active` 属性（境内发 10 级光）；赛钱箱收异世界钱币。合入最新 main 后单元 **199/199**、GameTest **151/151**（新增 `FoxShrineGoodsTest`）；以下客户端检查在合入前的版本上做，合入的改动不涉及聚落；客户端 `fox_sanctuary_views` 六套方案各 **1/1** 通过（d 配竹林道、b 配石段参道、a 与 e 配山野小径、c 配竹林道、f 配石段参道），每套都检查了回血、冬季（温泉与水渠不结冰、灌溉沟结冰）、神木下石龛激活且亮度 10、境内外判定；`fox_interior_views` **1/1**，实际操作：两枚小判后福运 II 且时长累加到 71640 刻并带生命恢复与抗性、第二次抽签不再给签、系签后霉运解除且架上白签加一档。逐张看了室内、抽签处、绘马挂、田地切面、街尾空地与旅馆截图。

第八版（2026-11-08，分支 `feat/fox-sanctuary-vault`，基于 main `50ab377`）：居酒屋每处至多 2–3 家；新增豆腐屋、陶器屋、药屋、刀锻冶、提灯屋；约三分之一的聚落带地下封印场地（封じの堂、回旋石阶、旧地下通道、大鸟居之间与楼门、封印之间），新方块封印杭与御札，九尾狐首领战未做。单元 **248/248**（新增 `SanctuaryUndercroftTest`：每套方案 300 个种子居酒屋都不超过 3 家、每套方案至少 2 条地下路线且换掉的都是住宅、岩洞顶离地面至少 12 格、九根封印杭都在封印之间里）；GameTest **192/193**，没过的 `corruption_core_grows_with_network` 在 main 的 CI 上同样失败（#206、#208 两次推送），与本分支无关。客户端 `fox_vault_views`（强制带地下场地）方案 0 与方案 3 各 **1/1**：九根封印杭都在，实际以生存模式逐根拆掉后一根不剩；逐张看了封じの堂、井口、井底出口、通道两段、大厅平台与全景、大鸟居、楼门、门洞、封印之间入口与俯瞰、倒挂町屋、杀生石与封印杭截图（第一轮地下太暗，补了发光地衣、圆台一圈高石灯笼、倒挂町屋下的狐火与大厅北岸石灯笼后再拍）。`fox_interior_views` **1/1**，新增五种商铺与两个新方块的截图。未测：多个种子与位置、光影包、联机；首领战。

只在一个随机种子的一处聚落上看过；其他地形下的山路走向、陡坡露岩与开挖量没有逐一检查。没测光影包、联机与帧率。

```text
python tools/fox_props_art.py
python tools/fox_sanctuary.py
./gradlew --offline test build -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=fox_sanctuary_views,fox_props_views" -PldTestHeadless -PldTestWindow=1280x720
```

## 长剑连招手感：取消点、输入缓冲与过渡混合

2026-11-02：分支 `feat/combat-feel`，基于 main `de46d32`（已合并 PR #110）。招式转移新增 `at`（取消点），招式新增 `blend_in`（进入时的过渡时长），格式见 [战斗骨架与兼容性规范](docs/COMBAT_SKELETON.md) 第 5 节。长剑出刀 0.1 秒后的左键都会缓冲，在美术标注的收势起点（0.36 / 0.36 / 0.37 秒）接下一段；客户端从上一招在切换那一刻的姿势平滑过渡（攻击 0.08 秒、回待机 0.12 秒、蓄力 0.1 秒、下劈 0.05 秒、收剑 0.1 秒）。同步包加入过渡时长，通道版本升为 3。

伤害仍走原版：提前接刀的两下按原版攻击冷却减伤（三连段实测 7 + 约 3.7 + 约 3.7），总输出与改动前接近，数值按维护者要求暂不调整。为不让连招被自己上一下的 10 刻受击冷却挡掉，同一目标的冷却若正是这套连招上一下造成的，下一下结算前清零；冷却来自其他来源时照原版处理。客户端单击只发一次攻击包，避免同一次点击被缓冲成两刀。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **130/130** 通过，`test build` 成功；`MovesetTest` 改为新时间轴：0.1 秒内重复输入不缓冲、缓冲在 37.2 刻（7.2 刻取消点）接上、取消点后输入立即接、第三段 0.37 秒取消回到第一段、取消点早于接触或 `at` 不带 `queue` 时报错 |
| 世界测试 | 长剑 8 项各跑 6 次：1 次全过，其余 5 次只有 `longsword_combo` 失败，且都是“只有第一下掉血”，与 main 上已知的测试环境问题相同（main 同样 6 次 1 次全过）；三下都命中的运行里伤害和冷却处理符合预期。其余 7 项每次通过 |
| 实际客户端 | `group:longsword` **3/3 场景、33/33 检查**；查看三把剑的连招截图，三段姿势与双手握剑正常 |

没有测光影、联机延迟或 NPC；手感本身需要维护者在游戏里实际试。

```text
./gradlew --offline test build -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew --offline runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:longsword_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=group:longsword -PldTestHeadless -PldTestWindow=1280x720
```

## 招式状态机与长剑迁移

2026-11-02：分支 `feat/combat-movesets`，基于 main `71e3816`（已合并 PR #109）。新增数据驱动的招式状态机（`Moveset`、`MoveRunner`、`Movesets`，格式见 [战斗骨架与兼容性规范](docs/COMBAT_SKELETON.md) 第 5 节），长剑的出招、接触、排队接段、连招记忆、蓄力、取消和收剑改由 `data/logos_dimension/combat/movesets/longsword.json` 描述，删除写死的 `LongswordPhase`。同步包改为招式快照（动作名、开始刻、速度、播放方式），通道版本由 1 升为 2。时序与手感不变；新增兼容行为：招式数据缺失或损坏时记日志，长剑按原版物品处理，客户端在服务端回报持剑动作前不拦截左键。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **129/129** 通过，`test build` 成功；新增 `MovesetTest` 12 项，按迁移前的时间轴逐刻对照拔剑、三连段（接触刻、0.425 秒起排队、在 42.5/55 刻对齐接段）、连招记忆 30 刻边界、攻速 2 倍、短按倒放取消、满蓄力保持与释放、边界刻松手、蓄力清空连招、收剑取消待结算攻击，以及动作时长与动作文件一致、校验报错 |
| 世界测试 | 长剑 8 项各跑 6 次：本分支 3 次全过、3 次只有 `longsword_combo` 失败；同机 main 上 6 次中 5 次同样只有它失败，报错相同（第二、三段射线命中目标但没有掉血）。临时日志确认本分支三段都在预期刻命中，属于已有的测试环境问题，本轮未修；其余 7 项每次都通过 |
| 实际客户端 | `group:longsword` **3/3 场景、33/33 检查**；与上一轮 main 截图放大对照，第二段攻击、下劈等同帧姿势一致 |

唯一的时序差别：下一段的输入窗口旧版固定为收势最后 4 游戏刻，现在是动作时间 0.425 秒起；原版剑攻速 1.6 下相同，攻速被改变时窗口随动作缩放。普通渲染 1280×720；Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25、Logos 0.6.0。没有改手感（取消窗口、过渡混合），没有做 NPC、光影或联机测试。

```text
./gradlew --offline test build -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew --offline runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:longsword_*"
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=group:longsword -PldTestHeadless -PldTestWindow=1280x720
```

## 战斗骨架标准与长剑迁移

2026-11-02：分支 `feat/combat-skeleton`，基于 main `d46b591`。新增 [战斗骨架与兼容性规范](docs/COMBAT_SKELETON.md) 与共用运行时 `dev.logos.dimension.combat`；两把长剑的动作改为 TLM 骨骼名的 Bedrock 动作文件，玩家骨架、剑网格、动作拆成三类文件。外观与玩法不变；新增一项兼容行为：骨架、网格或动作资源缺失、损坏时记日志并退回原版持物渲染，不再抛异常。

| 层级 | 结果 |
| --- | --- |
| 数据转换 | 仓库外校验脚本用迁移前的算法和旧数据、迁移后的算法和新数据，对两把剑、两个视角、九段动作按 60 帧每秒错开关键帧取样，比较全部方块顶点：最大误差 **7×10⁻¹⁵ 像素**；剑与剑鞘的 UV、贴图尺寸逐项一致 |
| 单元与构建 | **118/118** 通过，`test build` 成功；新增运行时 7 项（Bedrock 符号、三种插值与 pre/post、Molang 与坏动作不抛异常、缺父骨骼、缺骨忽略、混合端点与球面插值、左右镜像）和长剑 2 项，其中一项用迁移前算法生成的 12 组参考骨骼矩阵逐项对照 |
| 实际客户端 | `group:longsword` **3/3 场景、33/33 检查**，与同机在 main 上的结果相同 |
| 截图对照 | main 与本分支各 54 张，没有逐像素相同的：魔力缠绕光层、粒子、受击飘心和云边每次运行都不同。人工放大核对第一人称待机、第三人称蓄力等截图，剑、护手、双手与手臂位置重合，缠绕光层仍贴在剑身上 |

普通渲染 1280×720；Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25、Logos 0.6.0。日志里的 `Sheets loaded too early` 在 main 上同样出现两次，不是本轮引入。没有做 NPC 接入、光影包或联机测试；第 1 节第 10 条（与其他玩家模型模组共存）尚未实现。

```text
python tools/import_longswords.py ../art
./gradlew --offline test build -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=group:longsword -PldTestHeadless -PldTestWindow=1280x720
```

## 双手剑潜行、丢弃与魔力缠绕修复

2026-11-02：分支 `fix/longsword-crouch-and-drop`，基于 main `5b9da25`（已合并 PR #107）。修复持剑姿势覆盖潜行弯腰而导致腰腿错位；剑、双臂、剑鞘和胸甲共同跟随潜行。修复 Q 丢弃后空掉的原物品对象仍被视为装备中的剑，确保不切槽也清理动作并取消未命中的攻击。

自定义剑模型接回 Logos 公开的 `SurfaceAuras.submitItem`，使用原版烘焙物品表面并转换到实际剑骨骼；抵消 `ItemTransform.NO_TRANSFORM` 内置的半格居中，避免光层漂到剑旁。复用原元素光层、拖尾及 ShaderPackOverlay，不改主模组源码或符文伤害。Logos 依赖下限提高至 0.6.0，该接口在 0.5.1 中不存在。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **109/109** 通过，包含两项潜行姿态回归；`test build` 成功 |
| 世界测试 | 长剑 **8/8**，新增实际丢弃同槽位后清理状态与取消接触伤害 |
| 实际客户端 | **3/3 场景、33/33 检查**，54 张截图；两把剑、左右手、潜行待机与连招、穿甲、站立恢复、Q 丢弃、奥秘与火焰缠绕、解除后停止发射 |

普通渲染 1280×720，Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25、Logos 0.6.0。人工核对潜行斜侧面及第一、第三人称缠绕贴附位置，截图选集在 `docs/screenshots/longsword-fixes/`。未运行光影包或双客户端联机延迟测试；未重跑其他主题的世界测试。

## 双手长剑物品与连招试玩

2026-11-01：分支 `feat/two-handed-longswords`，基于 main `bc128cf`。接入用户审定的曦誓 v04 与黑钢长剑 v01，黑钢保留原版附魔台粒子符文。两把物品共用九段动作与服务端连招时间轴，第一人称待机靠右，第三人称替换玩家双臂并适配原版胸甲。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **107/107** 通过，`test build` 成功；其中三项长剑资源、靠右根骨与动作时序检查 |
| 世界测试 | 长剑专用 **7/7** 通过：接触前摇、三连段、蓄力保持与释放、短按取消、换槽取消、墙体遮挡、原版距离 |
| 实际客户端 | `group:longsword` **3/3 场景、15/15 检查**，39 张截图；曦誓、黑钢右手、黑钢左手 |

客户端使用实际按键状态与物品使用包、服务端动作回包和生命变化；另查出鞘期间提前按住右键、胸甲分段跟随、收剑后原版头部和双臂恢复。普通渲染，1280×720；Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25、Logos 0.6.0。截图选集在 `docs/screenshots/longswords/`，获取、操作和复现命令见 [双手长剑试玩](docs/LONGSWORDS.md)。

没有重跑全仓库世界场景，没有进行双客户端联机延迟或光影包验证。现有白樱瓣 stem 纹理与 Logos 魔杖粒子引用警告仍存在，本轮没有新增长剑的缺失纹理或 Mixin 报错。安装包沿用内部开发版本 0.11.0，未修改前置依赖或 Logos 本体，也不包含第三方 JAR。

## 森林精灵第三批随机外观

2026-10-31：分支 `feat/forest-elf-batch-03`，基于 main `c5051b9`。加入维护者确认的连续裙甲卫士服、白内绿外祭服、浅翠粉花礼裙及前双麻花辫厚长发，共 341 块、98 根新骨骼。现有 349 根骨骼和 128×240 旧贴图像素完全保留，22 段动画逐字节不变；总模型 1343 块、447 根骨骼，图集扩展为 128×352。重复导出字节一致。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **104/104** 通过，`test build` 成功；新增随机池、保存回读、隐藏与独立搭配检查 |
| GeckoLib | 实际依赖解析和烘焙通过：**447 根骨骼、22 段动画** |
| 原始客户端 | main 已有 `wild_mandrake` / `wild_starlight_moss` 引用不存在的 `minecraft:random_patch` Feature，实际复现注册表加载失败 |
| 隔离植物配置后的客户端 | `group:npc_batch_03_preview` **3/3 场景、6/6 检查**；查看新部件正、斜侧和背面截图 |

隔离仅使用不提交的本地 init 脚本，将 `build/resources/main` 下两个失效 Feature 临时换为 `minecraft:no_op`。源配置与安装包未改，结束后已恢复构建输出；不将此结果表述为原始配置可以正常进入世界。没有运行本轮世界 GameTest、完整战斗/死亡动作、多人与光影检查。详情及截图见 [第三批外观](docs/NPC_FOREST_ELF_BATCH_03.md)。

## 森林精灵最新体型与全套服装同步

2026-10-31：分支 `feat/forest-elf-body-fit`，基于 `main` 的 `8d6ddf5`。读取维护者最新标准 `.bbmodel`，同步 40 块素体、五套配套服装的胸腰形体、凉鞋和腰包局部修订，以及守林人披肩角度。源文件未改，游戏模型实际更新 11 根骨骼的几何或局部静止姿势，合计 1002 块、349 根骨骼。

新增 `tools/forest_elf_appearance.py`，复用现有坐标和 UV 旋转导出规则，在原图集分区中局部同步整个外观库。22 段完整运行时动画、挂泪表情骨骼及像素、39 根共用骨架、全部外观 ID 与搭配规则保持不变；本轮贴图像素无需修改。连续两次导出字节一致。新旧精灵继续按原部件 ID 使用更新后的形体，不重新随机外观。

| 层级 | 结果 |
| --- | --- |
| 源数据与导出 | 十六个源文件保持维护者内容；十五个部件的共用骨架和 40 块灰色参照对齐，UV 无越界、名称唯一、方块有正确父级；非本轮修改骨骼与完整动画逐项保留 |
| 单元与构建 | **103/103** 单元通过，`test build` 成功；保留骨盆封口、90° UV、随机外观及完整动画回归检查 |
| 原始实际客户端 | **未进入预览**：主分支已有 `wild_mandrake`、`wild_starlight_moss` 使用不存在的 `minecraft:random_patch` Feature 类型，世界注册表加载失败；这两个资源未由本 PR 修改 |
| 隔离植物配置后的实际客户端 | 素体 `group:npc_body_preview` **2/2 场景、4/4 检查**；外观与动作 `group:npc_preview` **8/8 场景、32/32 检查**，合计 **10/10 场景、36/36 检查** |

客户端隔离方式：仅在本地 `build/preview-registry-compat.init.gradle` 的 `runClient.doFirst` 中，将 `build/resources/main/` 下上述两个植物 Feature 临时换为 `{"type":"minecraft:no_op","config":{}}`，以让模型预览世界进入游戏。它不修改 `src/`，不纳入提交；测试后已恢复构建输出，并核对安装包内两个配置与原源文件字节一致。**上述客户端通过只代表在该隔离条件下的外观与动作检查，不能称作主分支原始配置已能正常进入世界。** 原始启动错误保留于本地 `build/body-fit-original-client-registry-error.log`。

运行环境：Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25.0.3、GeckoLib 5.5.2、本地 Logos 0.6.0 开发 JAR。人工查看素体、全套服装的正面与斜侧面，以及拉弓侧面、施法和死亡侧面截图。新增截图在 `docs/screenshots/npc-body-fit/`：

- [素体与配套服装正面](docs/screenshots/npc-body-fit/forest-elf-body-fit-front.png)
- [素体与配套服装斜侧面](docs/screenshots/npc-body-fit/forest-elf-body-fit-angle.png)
- [五套服装正面](docs/screenshots/npc-body-fit/forest-elf-outfits-fit-front.png)
- [五套服装斜侧面](docs/screenshots/npc-body-fit/forest-elf-outfits-fit-angle.png)

本轮未重跑世界 GameTest，没有验证多人、光影或全部极端动作穿插。没有修改生态注册、NPC 行为、战斗逻辑或动画。

```text
python tools/forest_elf_appearance.py
./gradlew --offline test build -PlogosJar=/absolute/path/logos-0.6.0.jar
# 以下两次客户端检查使用上文说明的本地临时 init 脚本。
./gradlew --offline -I build/preview-registry-compat.init.gradle runClient -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=group:npc_body_preview -PldTestHeadless -PldTestWindow=1600x900
./gradlew --offline -I build/preview-registry-compat.init.gradle runClient -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=group:npc_preview -PldTestHeadless -PldTestWindow=1600x900
```

## 精灵自然之力箭穿过同族

2026-10-29：分支 `fix/elf-nature-arrow-passthrough`，基于 `origin/main` 的 `738b725`。在原版箭的 `canHitEntity` 目标筛选处，跳过「自然之力标记 + 发射者为森林精灵 + 目标为森林精灵」的碰撞；箭继续检查同一段路径后面的敌人和方块。

- 单元：**96/96** 通过，`test build` 成功。
- 世界：使用本地 Logos **0.6.0**，精灵 GameTest **23/23** 通过。新增四项，覆盖普通、药水、光灵箭连续穿过两名精灵后在同一刻伤到后方僵尸，同伴无伤害、药效与击退，后方石墙仍挡箭；普通弓的精灵箭、骷髅与玩家的自然之力箭仍能伤到精灵。普通箭走实际 `shootMob` 发射入口；生物默认不提供特效弹药，因此药水与光灵箭用对应原版物品创建并附上同样的自然之力状态。
- 实际客户端：没有运行；没有验证多人或光影。

自然之力原有世界测试 `logos_dimension:nature*` **10/10** 通过，同样使用 Logos 0.6.0；覆盖资源消耗、弹道、寿命、药效与生命伤害、骷髅发射及 AI、普通箭保持原样、存档和未加载区块清理。两组世界测试合计 **33/33**。

复现：`./gradlew test build runGameTestServer -PlogosJar=/path/to/logos-0.6.0.jar '-PdimensionTests=logos_dimension:forest_elf*'`。

## 未举盾横持绕手翻转到身体外侧

2026-10-28：按维护者反馈，未举盾的物品挂点绕掌心手臂轴增加 180°，将横持盾从身体内侧翻到外侧。仅更新两个 `hold_*:shield` 动画，逐项确认已接受的举盾姿势及其它动画保持一致。

单元 **96/96**，`test build` 成功；实际客户端 **1/1 场景、8/8 检查**通过。人工查看正面与侧面横持截图，确认盾位于身体外侧。更新 `docs/screenshots/npc-guard/forest-elf-guard-alert-front.png`、`forest-elf-guard-alert-side.png`。本次没有重跑世界 GameTest。

## 未举盾时在身体侧面横持

2026-10-28：未格挡时恢复玩家放松持盾的方向，盾牌长边沿身体前后横放在身体侧面；只更新 `hold_mainhand:shield`、`hold_offhand:shield`，逐项确认其余动画（包括已确认的举盾姿势）完全保留。

单元 **96/96**，`test build` 成功；实际客户端 **1/1 场景、8/8 检查**通过。人工查看未举盾的正面和侧面截图，确认盾不再竖在脚前；增加 `docs/screenshots/npc-guard/forest-elf-guard-alert-side.png`。本次没有重跑世界 GameTest。

## 举盾保留在左臂前方

2026-10-28：取消举盾时手臂向身体中线内收的 30° 偏航，盾保持在同侧肩膀和手掌前方；重新计算物品挂点补偿，保留竖直朝前的盾面，让出另一只手的长矛戳刺空间。主手盾姿势同步作镜像调整。

增加正面近景戳刺截图，人工核对原版矛盾和精灵矛盾的持握及戳刺姿势。单元 **96/96**，`test build` 成功；实际客户端 **1/1 场景、8/8 检查**通过。截图更新在 `docs/screenshots/npc-guard/`，新增 `forest-elf-guard-front-close-stab.png`。本次只调整举盾姿势，没有重跑世界 GameTest。

## 森林精灵盾牌直立姿势

2026-10-28：按维护者的玩家正面举盾参考，继续修正盾牌虽然朝外、但仍明显倾斜的问题。

- 整臂按玩家式前伸，抵消精灵肩膀的 A 字初始角，前臂和手腕保持自然。物品挂点按肩部、GeckoLib 手持层及原版盾模型的合成矩阵求逆，让盾牌在身前直立；警戒期未格挡时，盾也保持竖直。
- 增加水平正面近景和警戒期截图，核对整块盾面、上下边缘和侧面倾角；不再仅以盾面朝外作为通过依据。原版盾与古木之拥均检查。
- 临时矩阵诊断已移除。保留当前工作区的世界树扩建数据，居民总数检查与生成清单对齐，并保留原居民及守卫数量下限。

验证：单元 **96/96**，`test build` 成功；实际客户端 `group:npc_guard` **1/1 场景、8/8 检查**通过，人工查看举盾正面近景、侧面、戳刺和警戒期持盾截图。本次为姿势修正，没有重跑世界 GameTest。

新截图：`docs/screenshots/npc-guard/forest-elf-guard-front-close.png`、`forest-elf-guard-alert-front.png`，并更新原有正面、侧面及戳刺截图。本节取代此前对盾姿已到位的判断。

## 森林精灵矛盾朝向与整套装备收起修正

2026-10-28：继续在 `feat/forest-elf-spear-shield` 修正试玩反馈。上一轮预览没有正确识别矛尖和盾牌正反面，本节结果及新截图取代此前的朝向判断。

- 长矛手持挂点翻转为矛尖朝前；盾牌挂点翻转为盾面朝外、握把朝向手臂。只调整 NPC 动画，保留物品本身及玩家的显示与操作。
- 弓、长矛、主手盾及副手盾统一在战斗、五秒警戒期间拿出，平静时收起。收起的副手盾以 `Shield` 保存；装备移动不复制堆，耐久和自定义组件保留。顶楼守卫出生时也空手，仍持有内部的默认矛盾装备。
- 外部换装覆盖同槽收起的旧装备；旧存档同时存在手持长矛与隐藏默认弓时，以手持装备为准，平静后能正常收起。收起状态遇袭仍立即拿出盾尝试格挡。
- 修复同刻取出装备并举盾时，客户端先收到使用标记、后收到装备包导致使用堆为空的问题；装备到齐后恢复客户端使用堆，不延迟服务端格挡。

验证：`test build` 成功，单元 **96/96**；世界 `logos_dimension:forest_elf*` **19/19**，新增原版和自定义矛盾的收起、遇袭取盾、警戒、再收起、数据存档和旧格式迁移；实际客户端 `group:npc_guard` **1/1 场景、7/7 检查**通过。

人工查看正面、侧面、戳刺、平静及警戒结束截图，确认矛尖朝前、盾面朝外，双臂动作互不覆盖，平静后双手无物品。截图在 `docs/screenshots/npc-guard/`：`forest-elf-guard-front.png`、`forest-elf-guard-side.png`、`forest-elf-guard-stab.png`、`forest-elf-guard-idle.png`、`forest-elf-guard-stowed.png`。没有新增多人或光影包验证。

## 森林精灵长矛、盾牌与世界树顶楼守卫

2026-10-28：分支 `feat/forest-elf-spear-shield`，已拉取并基于最新主分支 `8cc0410`。

- 新增按原版 `piercing_weapon` / `attack_range` 识别的长矛 AI：读取生物有效射程与近身盲区，趋向射程外沿，贴身后撤；末段平地微调处理原版寻路允许提前一格到达造成的原地停住。
- 盾按 `blocks_attacks` 识别，受攻击时尝试举起，持矛战斗时持续防御并可同时刺击。保留物品前摇、方向、绕盾伤害和斧头禁盾；弓手短暂防御后恢复原有射击，盾的使用时间不再被当作拉弓时间。玩家限定的矛盾双持规则及专属武器法术没有扩展或改动。
- 原有十三位世界树居民保留，最高回廊（相对树基 Y=81，站立 Y=82）增加两位精灵长矛与古木之拥守卫，仅改变 `tile_3_2_3.nbt` 的实体列表与清单。默认普通精灵仍用弓，`ElfHealGoal` 未改动。
- 新增持矛、戳刺、持盾与举盾动画，左右臂分开驱动；手持挂点校正矛与盾的方向。测试场地显式清空头部空间、增加间隔，避免测试中的亡灵互相选敌影响移动和脱战。测试补推进改为比较实体实际 tick 数，修复区块已标为运算中但新实体尚未进入运算列表造成的偶发不动。
- 测试僵尸标为常驻，避免远离模拟玩家时被原版清除。弓手防御恢复检查真实箭实体已经射出，并检查举盾期间没有提前出箭，避免把远处区块投射物暂未运算误判为弓 AI 不射击。

验证环境：Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25、Logos 0.6.0。

| 层级 | 结果 |
| --- | --- |
| 单元 | **96/96**；包含动画骨骼与双臂叠加检查，世界树居民总数更新为十五位 |
| 世界（`logos_dimension:forest_elf*`） | **17/17**；覆盖原版与自定义矛盾同时格挡刺击、贴身后撤、独立长矛、首击反应、原版盾前摇、背后攻击、斧头禁盾、弓手防御后恢复射击、读取打包守卫模板并初始化和装备存档重载，以及原有弓箭、治疗、阵营、绳梯、死亡行为 |
| 实际客户端（`group:npc_guard`） | **1/1 场景、5/5 检查**；人工查看持矛/举盾与戳刺截图，确认盾不覆盖右臂刺击、物品朝向正常 |

截图：`docs/screenshots/npc-guard/forest-elf-guard-hold.png`、`forest-elf-guard-stab.png`。客户端场景为定姿动作预览；没有把它称作实际客户端实战、多人或光影验证。旧区块及已有世界树不会自动补刷守卫。

## 统一矛盾正反手操作（当前方案）

2026-10-28：在 `codex/ancient-embrace` 的 `9e78f9a` 之上调整。正反手均为右键举盾、左键戳刺，持续举盾也能戳刺，保留原版举盾减速。删除反手的动能并行结算、接触表与冲刺反馈接入及双使用姿态；双持期间不会因移动碰撞消耗印记。收起古木盾后仍可单独使用长矛的原版冲刺与生命追加。下方双持记录描述此前方案，已由本节取代。

验证环境：Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25.0.3、Logos 0.6.0（`14c3260`）。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **95/95** 通过，`test build` 成功 |
| 世界：精灵长矛 | **16/16** 通过；反手举盾戳刺仍造成四点物理伤害并种印，不放下盾、不刷新举盾时间、不扣魔力或触发生命追加，保留冷却及临时属性清理；两种握持均无动能碰撞并保留原版减速；单独冲刺及副手损毁回归通过 |
| 世界：古木之拥 | **12/12** 通过，保留五叶蓄生、极限格挡、收盾治疗和主手矛举盾戳刺行为 |
| 实际客户端（`group:spear_shield`） | **4/4 场景、28/28 检查**通过：两种主副手排列 × 两种惯用手，普通戳刺、两键同刻戳刺并举盾、持续举盾戳刺、保持实际格挡及叶片充能、松开右键同刻恢复戳刺；人工检查第一/第三人称截图 |

更新 `docs/screenshots/spear-shield/` 为统一操作后的实际截图。安装包检查确认移除的双使用姿态类和 Mixin 均未残留。未验证远程多人或光影包，没有重跑自然之力世界套件与完整世界生成套件。

## 古木之拥与精灵长矛的双持操作

2026-10-28：在 `codex/ancient-embrace` 的 `a209503` 之上追加，仍属于古木之拥功能分支，尚未合入 `main`。环境为 Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25.0.3、Logos 0.6.0（`14c3260`）。

主手矛、副手盾：右键举盾，期间可左键戳刺；反手搭配：未按右键时左键用副手矛戳刺，右键同时举盾与启用原版动能冲刺，期间屏蔽左键戳刺。松开右键结束两种使用姿态，立即恢复戳刺。副手矛按本武器的四点伤害、十三刻间隔结算，不为持盾常驻添加攻击属性。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **95/95** 通过，`test build` 成功 |
| 世界：古木之拥 | **12/12** 通过；新增主手矛让出右键、普通盾搭配保持原逻辑、原版 STAB 包在举盾中正常命中/种印、不收盾/治疗或刷新极限窗口、连续点击保留冷却 |
| 世界：精灵长矛 | **17/17** 通过；新增副手矛正常戳刺与属性清理、反手右键同时格挡与冲刺、右键期间拒绝左键戳刺、十二刻起手、固定生命追加与易伤、原版接触表及收盾停止冲刺、主矛副盾不冲刺、副手矛损毁不误删主手盾 |
| 世界：自然之力回归 | **10/10** 通过 |
| 实际客户端（`group:spear_shield`） | **4/4 场景、24/24 检查**通过，8 张截图；覆盖两种主副手排列 × 两种惯用手，实际左右键输入、右键使用手、举盾左键差异、保持物理格挡、收盾后恢复戳刺；反手两键同刻按下时右键优先，松开右键同刻左键不会吞掉戳刺 |

动能伤害数值与速度使用世界测试的受控已知移动速度验证；客户端检查实际按键、普通戳刺、格挡及第一/第三人称双手姿态，没有将其称为实际客户端移动冲撞验证。普通戳刺、动能命中、附魔、射程和方块阻挡继续调用原版路径，反手使用实际副手槽位损耗与损毁。命中反馈复用原版时序与副手矛音效。

截图见 `docs/screenshots/spear-shield/hand-combinations.png` 及同目录原始截图。未验证远程多人和光影包，本次没有重跑完整世界生成套件。

## 古木之拥：即时格挡、五叶充能与回生

2026-10-28：分支 `codex/ancient-embrace`，基于 `main` 的 `e0760ad`，独立于已合入主分支的精灵长矛 PR。环境：Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25.0.3、Logos 0.6.0（`14c3260`）。

调查当前原版源码：`Items.SHIELD` 的格挡组件指定 0.25 秒，`BlocksAttacks.blockDelayTicks()` 换算为五刻，`LivingEntity.getItemBlockingWith()` 在达到该时间后才返回盾。古木之拥单独将此前摇设为零；原来的五刻改作双叶奖励窗口，普通格挡充一叶，五叶收盾消耗五魔力回复四点生命，冷却八秒。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **95/95** 通过，`test build` 成功 |
| 世界（`logos_dimension:ancient_embrace*`） | **10/10** 通过：原版盾仍有五刻前摇、古木之拥当刻格挡、0/4/5/10 刻奖励边界、五叶封顶、原版 336 耐久与格挡损耗、收盾治疗、满血/不足与恰好魔力、换盾冷却、保存及到期、背后与绕盾伤害、强制禁盾不误治、创造免魔力 |
| 世界回归 | 精灵长矛 **13/13**、自然之力 **10/10** 通过，检查新增格挡入口与共用 Logos 边界未破坏既有武器 |
| 实际客户端（`group:ancient_embrace`） | **2/2 场景、16/16 检查**通过，18 张截图：左右惯用手原版举盾/收盾输入、五次实际格挡逐片同步发光、治疗四点与恰好五点扣费、技能冷却；人工检查夜间 0–5 叶进度、两手姿势和 Logos 生命治疗特效 |
| 资源与安装包 | 原图 64×64、逐字节相同；完整 29 个方块的几何/UV/旋转保留；发光图仅包含叶片 UV；上游 ID/标签审计通过，JAR 无内嵌第三方 JAR |

客户端检查专门关闭测试玩家的 Logos 自动回魔，并清空两个场景间的技能冷却，避免它们干扰单次扣费与充能断言；没有修改正式游戏的回魔或冷却规则。普通与发光叶片互斥提交到原版模型收集器，保留实体光照、overlay 与附魔闪光，不清空 Logos 收集器。

截图见 `docs/screenshots/ancient-embrace/leaf-progress.png`、`left-blocking.png`、`right-blocking.png` 和 `heal.png`。没有验证远程多人、光影包或完整世界生成套件；前五刻窗口按服务端接受举盾后的游戏刻计算。

## 精灵长矛：萌芽印记与生命易伤

2026-10-28：分支 `codex/elf-spear`，基于 `main` 的 `e46eaaf`。验证环境：Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25.0.3；Logos 0.6.0，源码基线 `14c3260fc3ad930ab120eb518e21a2b1641a4869`。本次单独构建该上游快照作运行依赖，没有修改或打包主模组源码。

新增精灵长矛：钻石矛戳刺伤害、木矛攻速、铁矛动能组件。左键成功物理命中种六秒印记，同玩家右键冲撞消耗印记与五点魔力，追加六点 Logos 生命基础伤害，然后施加原生生命易伤 I 四秒。GUI 保留提供的图标，手持专用贴图由代码镜像为原版长矛方向。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **95/95** 通过，`test build` 成功 |
| 世界（`logos_dimension:elf_spear*`） | **13/13** 通过：原版属性与冷却、普通戳刺、不同速度下固定生命追加、恰好/不足魔力、无印记、归属与到期、已有生命易伤 I/II、生命抗性、距离与方块阻挡、无敌与盾牌完全格挡、印记保存重载、多目标分别扣费、副手与起手时间 |
| 世界回归（`logos_dimension:nature_*`） | **10/10** 通过，确认共用生命伤害接口保留自然之力行为 |
| 实际客户端（`group:elf_spear`） | **2/2 场景、10/10 检查**通过：左右惯用手、真实左键戳刺与前进冲刺、印记网络同步及叶片生成、生命易伤；人工查看第一人称架矛、第三人称与生命绽放截图 |
| 上游与安装包 | Logos ID/标签审计通过；GUI PNG 与提供文件逐字节相同，手持 PNG 为水平镜像；JAR 无内嵌第三方 JAR |

世界测试用受控的服务端已知速度调用原版射线/动能组件，客户端测试另外使用实际按键与移动，不将两类测试混称。截图见 `docs/screenshots/elf-spear/`。没有验证远程多人、光影包或完整世界生成套件。完整易伤机制要求当前 Logos 已注册 `logos:life_vulnerability`，早期同版本开发包缺少时保留物理伤害、不扣资源并提示更新。

## 修复 #73 / #84 后服务端世界生成注册表加载失败

2026-10-27：分支 `fix/rose-dunes-server-startup`，基于 `main` 的 `82b63dd`。

#73 删除草原矮草注册后漏掉了野花草甸的引用；#84 将它改成不存在的 `logos_dimension:short_grass`，因此仍然启动失败。已实际复现 `Unknown registry key in ResourceKey[minecraft:root / minecraft:block]: logos_dimension:short_grass`。改为 `minecraft:short_grass`，保留原有权重；恢复生成脚本里误删的野花草甸配置。平原测试按相同 ID 累加权重，新增三个平原群系的原版短草 ID 与总权重检查，并清理允许旧矮草 ID 的逻辑。

验证环境：Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25、Logos 0.6.0。

- 回归：修复前新测试失败（7 项中 1 项失败），修复后全部通过。
- 单元与构建：`test build` 成功，95/95 单元测试通过。
- 世界：`runGameTestServer -PdimensionTests=logos_dimension:cloudsea_registry`，1/1 通过，世界生成数据成功加载。
- 普通独立服务端：`runServer` 在全新测试存档进入 `Done (1.851s)!`，成功加载主世界、下界、末地和 `logos_dimension:cloudsea`。
- 未运行实际客户端或使用维护者原存档测试；没有运行完整资源生成脚本（其依赖仓库外的主模组构建产物）。


目标环境：Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25；Logos 0.5.1，主模组源码基线 `f196960`。当前开发安装包为 0.11.0。原炼金维度不包含建筑或 Boss；远行大陆包含中世纪聚落、精灵森林与世界树，不包含 Boss。

## 森林精灵兜帽与披风搭配规则

2026-10-27：分支 `fix/forest-elf-hood-rule`，基于主分支 `5fe80a1`。

新增数据规则 `requires: {"hair_hooded": {"outfit": "outfit_ranger"}}`：兜帽必带守林人披风，披风可以搭配其他发型；不改变其他组合及兜帽与枝冠互斥。按槽位顺序处理单向依赖，既用于出生随机，也用于旧存档和手动外观的校正；不合法的依赖目标或反向槽位配置在读取时报告错误。仓库外 `race.json`、合并脚本和 `build/parts.json` 同步保存该规则，避免下次导出丢失。

验证：`NpcPartsTest` **12/12** 通过，包含 2000 次随机验证（兜帽总是搭配披风，同时确实能抽出无兜帽的披风）、手动组合与旧外观校正，以及其他发型与每套服装保持原组合。未运行完整单元套件、世界 GameTest 或实际客户端。

## 森林精灵素体与 UV 修订

2026-10-27：分支 `fix/forest-elf-body-uv`，已同步主分支 `a07dc76`。

- 同步维护者增量保存的成年女性素体：保留肩部拆分、腰胯形体和贴图修改，删除的六块腰部曲面不再导出，共用素体由 42 块改为 38 块。
- `pelvis` 顶面封口，使用独立素体贴图右侧肤色纹素中心 `(14.5, 3.5)`（`#FFE1D5`）；`chest_top` 向前（制作坐标 −Z）精确加深 0.1，深度为 4.1。导出保留骨盆正面 90° UV 旋转。
- 新增局部导入工具 `tools/forest_elf_body.py`，只替换共用素体方块、总贴图 `(96, 200)` 的 16×16 素体区域及方块统计。逐项比较确认其余部件、挂泪表情、骨架层级和全部游戏动画保持主分支内容；重复运行输出不变。
- 新增开发预览组 `npc_body_preview`：无遮发素体、高马尾素体、休闲、花匠、学者，从正面和斜侧面观察。

后续追加：左右 `upper_arm`、`forearm`、`hand` 统一为上臂原有的 2.4×2.9 截面，长度、中心、关节与 UV 保留；重新导出时同步标准源文件已有的 4 个素体纹素调整。按维护者要求，这次追加未运行测试或构建，下表为此前素体修订的验证结果。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **90/90** 通过，`test build` 成功；新增骨盆封口肤色、90° UV 与胸顶 4.1 深度回归检查 |
| 世界（GameTest） | 本次未重跑 |
| 实际客户端（`group:npc_body_preview`） | **2/2 场景、4/4 检查**通过；人工查看正面及斜侧面截图 |

截图：`docs/screenshots/npc-body-uv/forest-elf-body-lineup.png`、`forest-elf-body-angle.png`。没有验证战斗动作中的服装穿插、多人或光影包。

## NPC 死亡后锁住朝向

2026-10-27：分支 `fix/npc-death-facing`。原版死亡时朝向不冻结（身体转向击退滑动的方向、被头的朝向拉着走），森林精灵趴着躺 3 秒时会慢慢转过一定角度。新增 `NpcDeathPose.hold`：死后每刻把身体、头和实体的偏航（连同上一刻的值）固定为死亡时的朝向，服务端与客户端都调用；`ForestElf#tick` 使用它。`docs/NPC_ANIMATIONS.md` 写明之后加入的 NPC 都用同一个方法。

| 层级 | 结果 |
| --- | --- |
| 单元 | **89/89** 通过 |
| 世界（GameTest `logos_dimension:forest_elf*`） | **9/9** 通过，连续三次；`forest_elf_death_lingers` 新增：死后每刻给尸体击退速度、把头转 30°，从第二刻起身体与实体朝向不变 |
| 实际客户端 | 没有运行 |

顺带修正测试：手动推进生物的辅助方法原来跳过已死亡的生物，测试区块不运算实体时尸体不会消失；`forest_elf_death_lingers` 的「不能消失太早」放宽一刻（测试检查与精灵这一刻的先后不固定）。

## 森林精灵拉弓姿势修正

2026-10-27：分支 `fix/forest-elf-bow-pose`，按实际试玩反馈参照玩家拉弓修改。

- **左手**：原来左臂向外甩、小臂向上折，看起来是意义不明地举起；现在左臂向内约 34°（含上身 12° 侧转）、小臂微弯，左手伸到弓的位置。动画里左臂 y 为正是向内。
- **侧身**：原来视线偏向一侧时只有双臂跟着转（最多约 75°，加上动画侧转接近 90°）；现在瞄准时整个人转向视线方向，上身侧转只来自动画（12°，远小于 45°）。施法伸手（抚生之触）同样适用。

验证：单元 89/89；实际客户端 `npc_forest_elf_bow`、`npc_forest_elf_bow_side` 2/2 场景、8/8 检查，人工查看 3/4、侧面截图，并临时把镜头放到正上方确认两手在弓的位置会合（这张俯视图没有保存进仓库）。更新 `forest-elf-bow.png`、`forest-elf-bow-side.png`。没有在实战中查看。


2026-10-27：分支 `feat/forest-elf-death`。

- **动画**：维护者在 Blockbench 里做的 `death`（仓库外 `forest-elf-model/forest_elf/Animation/forest_elf_death.bbmodel`，1.55 秒，跪下再向前倒地）由 `tools/forest_elf_animations.py` 换算进动画文件；与维护者导出的 `forest_elf_death.animation.json` 逐关键帧比对，差为 0。
- **衔接**：主控制器在死亡时先播一遍 `attacked`（最后一击的受击），再播 `death` 并停在最后一帧。
- **表情**：`expression_dazed_tears`（仓库外 `expressions/`）由 `tools/forest_elf_expressions.py` 并进模型（8 根骨骼、25 个方块，挂在 `Head` 下）与贴图（放在原贴图未使用的 (96, 104)），登记在零件表 `expressions`。死亡动画开始的同时（第 9 刻）显示它、隐藏常态眼眉嘴。重复运行结果不变。
- **原版效果**：有死亡动画的 NPC 不再染红、不向侧面倒下、不叠加视线；死亡时停止手部与剧情动作。精灵 60 刻（动画播完后在地上停约 1 秒）才消失。倒下的精灵不再拿出弓。
- **粒子**：新粒子 `forest_elf_spirit`（代码绘制的 8×8 白色圆点，染成叶绿色，全亮、半透明，向上飘、轻轻摆动、淡出）。死亡动画开始后每刻从身上飘出一两个，出生点随身体从站立移到倒地；消失时不放原版白烟，沿倒地的身体散出 100 个。都在客户端生成，不走网络。

| 层级 | 结果 |
| --- | --- |
| 单元 | **89/89** 通过；新增：表情平时隐藏、给出时替换常态眼嘴且不参与抽取，表情挂在 `Head` 下；死亡动画停在最后一帧 |
| 世界（GameTest `logos_dimension:forest_elf*`） | **9/9** 通过；新增 `forest_elf_death_lingers`：一击致命后第 9 刻起才换表情，满 60 刻才消失 |
| 实际客户端（`group:npc_preview`） | **8/8 场景、32/32 检查**通过，连续两次；新增两个死亡场景：三个精灵错开受到致命一击，检查死亡动画播完前不消失、客户端按死亡刻数换表情、常态眼嘴被隐藏、倒下时飘出光点、消失时散出大量光点；人工查看截图 |

截图 `docs/screenshots/npc-preview/forest-elf-death.png`（3/4）与 `forest-elf-death-side.png`（侧面）：从左到右是倒地停住（身上飘出光点）、跪下中（挂泪表情）、受击中；都没有染红或侧倒。`forest-elf-death-burst.png` 与 `forest-elf-death-burst-side.png`：左边的精灵刚消失，化作一团绿色光点，没有白烟。

注意：以后从 `forest-elf-model` 重新合并并复制模型、贴图后，要再跑 `tools/forest_elf_expressions.py` 与 `tools/forest_elf_animations.py`，否则表情和死亡动画会丢。

没有验证的部分：实战中被玩家或怪物打死的观感（只用了无来源的伤害）、多人、光影；TLM 中的播放。

## 森林精灵平时收起武器

2026-10-27：分支 `feat/forest-elf-holster`（建在 `feat/forest-elf-animations` 之上）。精灵的弓平时收着（存档 `Weapon`），有目标时拿出来；目标没了或受伤后警戒 100 刻（5 秒），期间不收，正在拉弓时也不收。旧存档里一直拿着弓的精灵，在第一次不警戒时收起来。

| 层级 | 结果 |
| --- | --- |
| 单元 | **86/86** 通过（没有新增） |
| 世界（GameTest `logos_dimension:forest_elf*`） | **8/8** 通过，连续两次；新增 `forest_elf_weapon_only_in_combat`：平时空手 → 看到僵尸拿出弓并真正开始拉弓 → 僵尸消失后警戒期内仍拿着 → 警戒结束收起；结构生成的精灵空手、弓收着 |
| 实际客户端（`group:npc_preview`） | **6/6 场景、18/18 检查**通过；施法截图 `forest-elf-spells.png` 更新为空手（不在战斗中） |

没有验证的部分：实际游戏中进出战斗的观感（没有在战斗中截图）、多人。


2026-10-27：分支 `feat/forest-elf-animations`，清单见 [NPC 动画清单](docs/NPC_ANIMATION_LIST.md)。

- **借用车万女仆**（CC BY-NC-SA 4.0，[CREDITS.md](CREDITS.md) 登记）：`walk`、`jump`、`attacked`、`ladder_up` / `ladder_stillness` / `ladder_down`、`swim_stand`、`swing_hand`；位移按 2 倍制作放大。
- **自绘**（`tools/forest_elf_animations.py`）：`hold_mainhand:bow`、`use_mainhand:bow`（1.2 秒拉满停住）、`heal_touch`、`heal_ring`、`greet`、`refuse`。
- **瞄准**：拉弓、放抚生之触时，渲染器把视线俯仰与偏航叠加到 `Arm`，弓和施法的手指向目标；自然之力的拉弓贴图按精灵 24 刻满弦的比例推进。
- **剧情动作**：精灵用同步字段给出动作名（治疗、右键行礼 / 拒绝），播完自动清掉。
- **修正**：NPC 头部俯仰方向原来是反的（抬头时低头），现在和视线一致；站着不动的 NPC 在客户端不再误播跳跃（客户端的「在地面上」只在移动时更新）。

| 层级 | 结果 |
| --- | --- |
| 单元 | **86/86** 通过；新增 `ForestElfAnimationFileTest` 4 项：现有行为的动作齐全、动画骨骼都在模型里、没有 TLM 专用 Molang、自绘循环首尾一致 |
| 世界（GameTest `logos_dimension:forest_elf*`） | **7/7** 通过 |
| 实际客户端（`group:npc_preview`） | **6/6 场景、18/18 检查**通过，新增三个动作场景：拉弓的精灵在客户端处于瞄准、剧情动作同步、站着不动的精灵不播跳跃；人工查看截图 |

截图在 `docs/screenshots/npc-preview/`：

- `forest-elf-bow.png`：持弓、平射拉弓、抬头 30° 并向左偏 25° 拉弓，弓与头一起朝向视线；
- `forest-elf-bow-side.png`：正侧面，平射时弓臂水平，抬头 30° 时弓随之抬起；
- `forest-elf-spells.png`：抚生之触、生息之环、行礼。

没有验证的部分：实际战斗与寻路中的连贯性（走路、爬绳、受伤只确认了动画存在且骨骼匹配，没有逐帧截图）、`refuse` 的画面、多人、光影。
## 森林精灵第二批随机外观部件

2026-10-27：分支 `feat/forest-elf-appearance-parts`，基于 `main` 的 `17c2dbd`。同步维护者最新手改并修正 UV 后的合并几何、128×240 贴图与零件表，新增高马尾、花匠工作服、休闲短装和白大衣学者服。动画与主分支字节一致，不引入新动画或借用素材。

新生成的森林精灵从四种发型、五套服装中随机选择。外观记录继续存部件 ID，已有精灵不重新随机；默认组合、兜帽与枝冠互斥保持不变。没有修改出生逻辑或渲染器。

| 层级 | 结果 |
| --- | --- |
| 单元与构建 | **83/83** 单元通过，`build` 通过；包含新部件存在、2000 次随机抽取覆盖、外观编码往返、未选中服装隐藏及全部零件顶层组登记检查 |
| 资源核对 | 四个运行时资源与最新源导出字节一致；其中动画无差异。原有 263 根骨骼的几何与父子结构、3430 个面的纹理像素及 UV 尺寸保留 |
| 普通实际客户端 | `group:npc_preview` **3/3 场景、6/6 检查**通过；1600×900 实际渲染与客户端外观同步，并人工查看三张截图 |
| 世界（GameTest） | 本轮未重跑，行为与结构生成没有改动 |

客户端环境：Minecraft 26.1.2、NeoForge 26.1.2.112、Java 25.0.3、GeckoLib 5.5.2、本地 Logos 0.6.0 开发 JAR（未提交）。预览五位精灵依次为默认居民、高马尾花匠、高马尾休闲装（垂眼与猫猫嘴）、高马尾学者、兜帽巡林弓手；精灵关掉 AI 站定，待机动画正常播放。截图位于 `docs/screenshots/npc-parts/`：

- [正面组合](docs/screenshots/npc-parts/forest-elf-parts-lineup.png)
- [3/4 组合](docs/screenshots/npc-parts/forest-elf-parts-angle.png)
- [高马尾与休闲装近景](docs/screenshots/npc-parts/forest-elf-parts-faces.png)

本轮没有验证多人、第三方光影、极端动作穿插或大量居民的帧率。单元检查证明新部件进入随机池，客户端截图使用指定外观以完整展示三套新服装；没有将指定预览说成自然生成截图。

```text
./gradlew --offline test build -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=group:npc_preview -PldTestHeadless -PldTestWindow=1600x900
```

## 森林精灵居民：生成、战斗、治疗、绳梯与语音

2026-10-26：分支 `feat/forest-elf-residents`，行为见 [森林精灵](docs/NPC_RACE_FOREST_ELF.md) 第 8 节，施法接入见 [NPC 魔法](docs/NPC_MAGIC.md)。

- **零件表数据化**：`data/logos_dimension/npc_races/forest_elf.json`（模型合并脚本导出），新增兜帽发型、巡林弓手服装，以及兜帽与枝冠的互斥规则。
- **生成**：居民写进结构模板，世界树 13 位、5 座带屋巨木各 2 位，结构放置时按 `STRUCTURE` 初始化；另有刷怪蛋（代码绘制）。
- **行为**：自然之力远射（快速满蓄力、保持距离、朝同伴聚集），抚生之触 / 生息之环治疗，200 魔力、每秒恢复 5，使魔时视玩家为友方；与僵尸、灾厄村民的敌对照抄村民；寻路把绳梯当路径点上下。
- **语音**：维护者转好的 32 个 ogg 按标签归入 9 个事件，带字幕。

运行环境：**Logos 0.6.0，上游 main `112bc52` 的源码快照构建**（包含 `npc-heal` 与 `npc-group-heal` 示例），JAR 只在仓库外的临时目录，没有纳入提交。

| 层级 | 结果 |
| --- | --- |
| 单元 | **82/82** 通过；`NpcPartsTest` 新增：零件表来自数据且含新零件、2000 次抽取不出现互斥组合、模板里的居民数量 |
| 世界（GameTest，`logos_dimension:forest_elf*`） | **7/7** 通过，连续两次：结构初始化、绳梯向上、绳梯向下（不摔伤）、单体治疗、群体治疗、使魔治疗玩家、僵尸与精灵互为目标 |
| 实际客户端 | **没有运行** |

世界测试使用真实的 Logos 运行时施法，魔力从精灵自己的池子扣除。测试服务器把测试放在很远的随机坐标，区块有时不进入实体运算，所以测试里的生物在那样的刻由测试手动推进一刻。

没有验证的部分：新生成区块里的实际居民（只检查了模板里的实体数据）、客户端外观与动画、兜帽与弓手服装的截图、语音的实际听感、多人、光影。其中 5 个受伤 / 死亡语音是立体声，Minecraft 不会让它们随距离衰减，需要重新导出为单声道。

```text
./gradlew test -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar "-PdimensionTests=logos_dimension:forest_elf*"
```

## 森林精灵预览原型（零件切换）

2026-10-26：随机外观的最小原型，先让森林精灵在游戏里能按零件切换外观，并用客户端截图场景检查。

- **实体**：`logos_dimension:forest_elf`（`ForestElf`），碰撞箱 0.6×1.5，眼高 1.26；简单闲逛和看玩家；用 `NpcAnimations` 驱动，模型里目前只有 `idle`，其余状态按驱动规则顺延。
- **外观记录**：「槽位 → 零件 ID」，生成时随机抽一次，存档存 ID（NBT `Appearance`），同步用一个字符串字段；未知 ID 回退默认；可选槽位（发型、头饰、服装）可以选 `none`，素体就是这三项都为 `none`。
- **零件表**：`NpcParts.FOREST_ELF`，六个槽位：发型（长直发、短发）、头饰（枝冠）、耳型（长耳）、眼睛（方眼、垂眼）、嘴（微笑、猫猫嘴）、服装（居民）。
- **渲染**：`NpcRenderer` 对实现 `NpcPartsHolder` 的 NPC 整组跳过没选中零件的骨骼组（含子骨骼）；发光骨骼在隐藏组里时也不补画。
- **资源**：模型、动画、贴图由仓库外的 Blockbench 总模型 `forest_elf_race_1.bbmodel` 导出（2 倍制作，206 根骨骼，贴图 112×128）。

验证：**74/74 单元测试**通过，其中新增 `NpcPartsTest` 4 项：编码往返、未知 ID 与 `none` 的回退规则、隐藏组计算，以及零件表与模型骨骼组一一对应。普通实际客户端 `group:npc_preview`：**3/3 场景、6/6 检查**通过，检查五个精灵的外观都同步到了客户端，并人工查看了截图：

- `forest-elf-lineup.png`：从左到右依次是默认外观、短发不戴头饰、短发配垂眼和猫猫嘴、长直发配垂眼和猫猫嘴不戴头饰、素体；
- `forest-elf-angle.png`：3/4 视角；
- `forest-elf-faces.png`：短发配垂眼和猫猫嘴的脸部近景。

截图在 `docs/screenshots/npc-preview/`。精灵在场景里关掉 AI 站定，待机动画照常播放。

没有验证的部分：自然生成（还没加进任何结构或群系）、存档重进后外观保留（只有代码与单元层面的编码检查）、多人同步、光影、硬件帧率；GameTest 没有新增也没有重跑。

```text
./gradlew test build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:npc_preview -PldTestHeadless -PldTestWindow=1600x900
## 0.11.0 自然之力四段蓄力弓

### 2026-10-26 手持朝向、弓臂与箭矢外观修订

按实际试玩反馈，将五张弓贴图的弓臂纵向延长约 25%，统一转到原版弓的左上射向并输出 64×64，保持五帧握柄一致；沿用原版显示变换，修正第一人称箭身侧向的问题。前三阶恢复普通箭、药水箭、光灵箭各自的原版纹理；只有第四阶使用自然之力纹理。第四阶屏蔽原版暴击飞行粒子，保留暴击伤害和 Logos 生命弹体拖尾。

同一 Logos 0.6.0 快照（`a86bb4d`）下复验：**69/69 单元、10/10 定向世界检查通过，构建通过；普通实际客户端左右手 2/2 场景、16/16 检查通过**。客户端实际观察一次箭刻产生的原版粒子数量：保持暴击标记时第三阶生成四颗，第四阶零颗；另外确认生命拖尾正常接收。普通/药水/光灵箭三种实体各检查四个阶段的最终纹理选择。人工查看左右手第一人称满弓及第三人称持弓截图，更新 `docs/screenshots/natures-force/`。此次仍未运行第三方光影。

### 首次实现验证

2026-10-26：在独立分支 `codex/natures-force-bow` 添加 `logos_dimension:natures_force`，玩法见 [自然之力](docs/NATURES_FORCE.md)。前两阶免魔力，第三阶消耗 5 点并等同原版满弓，第四阶合计消耗 10 点，8 格/tick 直线飞行、额外 10 点 Logos 生命属性基础伤害。魔力不足降至第二阶，不扣残余魔力。

最终运行环境为 **Logos 0.6.0，上游 main `a86bb4d1ed7aadbe0c9beb16f8fc1935cae982c2` 的源码快照构建**。快照仅下载、编译到本附属被忽略的 `build/`，没有修改本地主模组仓库，也没有将其源码或 JAR 纳入提交。早期使用本地 0.5.1 检查基本射击与客户端；下表为最终 0.6.0 结果。

| 层级 | 结果 | 覆盖 |
| --- | --- | --- |
| 单元 | 69/69 通过 | 含新增三项蓄力边界、速度和魔力费用检查；保留现有单元测试 |
| 定向世界检查 | 10/10 通过 | 生存箭耗与各阶魔力、缺魔力降阶、无箭拒绝、各阶物理/生命伤害、药水及光灵效果、无重力无阻力、80 tick 到期、保存/重载到期时间、未加载区块清除、无限且不可拾取的生物箭、原版骷髅 AI 等待 40 tick、普通箭不受影响 |
| 普通实际客户端 | 1/1 场景、6/6 检查通过 | 四阶物品模型、箭阶段同步、最终渲染状态选择指定纹理、Logos 生命拖尾、1280×720 视口、80 tick 后自然移除 |
| 构建与资源 | 通过 | 原版箭模型、用户指定 PNG 原样导入、五张弓贴图、中英文名称、弓/耐久附魔与骷髅武器标签；无内嵌前置 JAR |

实际客户端截图位于 `docs/screenshots/natures-force/`，已人工查看待机、满蓄力和飞行箭近景。用于拍摄的近景箭以较慢速度横移，便于辨认 UV；实际发射的 8 格/tick 速度由世界测试单独断言。没有将近景拍摄速度当作战斗射速。

过程中修复了两项真实兼容问题：NeoForge 开发环境禁止给原版箭追加同步数据编号，改用同步附件；NeoForge 会在实体原版提取后清空扩展渲染数据，改用正式渲染状态回调写入标记，补充最终纹理选择断言并重新拍摄，确认箭身实际使用指定的翠绿/浅金纹理。世界测试的无玩家区块不保证持续模拟，80 tick 测试明确在指定时刻触发到期检查；实际客户端另外验证了自然移除。

本次未跑全套世界生成 GameTest，也未运行第三方光影、多人远程联机或硬件帧率检查。没有新增配方或生存掉落来源；通过创造标签页或 `/give @s logos_dimension:natures_force` 获取。没有将工作分支内容称为 main 已更新。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.6.0.jar -PdimensionTests=logos_dimension:nature_*
./gradlew runClient build -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=group:natures_force -PldTestHeadless -PldTestWindow=1280x720
```

## 0.11.0 林地蘑菇、幼年牧草与草皮骨粉

2026-10-25：森林侧收尾的一批小内容，全部数据包 + 少量方块类，无新美术服务、无内嵌前置。

- **露灯菌可食用**：`journey_glowcap` 的方块物品带食用属性，营养 2（一格饱食度），饱和 0.15；对空气右键吃、对地面右键仍是放置。
- **六种林地蘑菇**：白伞菇 / 鸡油菌 / 牛肝菌（营养 2）+ 毒蝇伞 / 死帽菇 / 毒粉褶菌（营养 1）。毒菌吃下挂中毒、反胃、饥饿，死帽菇最重（中毒 II）。贴图与模型由 `tools/journey_mushrooms.py` 代码绘制，64×64，单朵约占方块 0.48 高，物品另有 64×64 满格图标。
- **蘑菇自然生成**：六种按群系分配，食用三种常见（1/24、1/32）、毒菌稀有（1/96–1/128）；非林地不加。
- **幼年原野牧草**：`journey_meadow_grass_young`，形状照原版 `short_grass`（16×16 + `block/cross`），带 `age` 0–2；光照 ≥ 9 自己长，长满换成成年牧草，骨粉催熟一档；进入 `meadow_floor` 与两个林隙的地表组合。
- **草皮骨粉修复**：`SeasonalTurfBlock` 实现 `BonemealableBlock`，候选来自群系的骨粉特征（`minecraft:can_spawn_from_bone_meal` 标签），所以撒骨粉长的是本群系自己的植物；新增该标签的追加文件（`replace:false`），覆盖 16 个林地、精灵森林与共享草地表；群系无候选时不消耗骨粉。
- **上游同步工具**：`tools/check_logos_upstream.py` + [与主模组同步的检查清单](docs/UPSTREAM_SYNC.md)。

验证：main 上 **53/53 单元测试**通过（强制重跑，Logos 0.6.0 的 jar）；定向 GameTest 全部通过——`glowcap_food`、`woodland_mushroom_food`、`toxic_mushroom_food`（各回饱食度 / 挂三种负面效果）、`young_grass_grows`（骨粉 0→1→2→成年牧草）、`turf_bonemeal`（放草皮撒 4 次骨粉后上方长出方块）。幼年牧草与草皮骨粉两处在 Logos **0.6.0** 的 jar 上重新跑过，不只是 0.5.1。六个蘑菇 / 幼草 / 草皮方块都进了 `logos:fragile_plants` 标签，衰退符文有效。

已知问题：全套 GameTest 的 `elven_living_world_tree` 仍会失败（`树心中央方块光照不足：5`，等待光照传播超时）。该项在**未改动的 main 上同样复现**，属既有环境/时序问题，没有靠重跑掩盖，也未计入"通过"。

没有验证的部分：蘑菇与幼草的实际观感（只有贴图/模型文件级核对，没有客户端截图）、自然生成密度手感、光影、硬件帧率。所有世界生成内容只在新区块生效。

```text
python tools/journey_mushrooms.py
python tools/journey_young_grass.py
python tools/check_logos_upstream.py
./gradlew test build -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runGameTestServer -PlogosJar=/absolute/path/logos-0.6.0.jar -PdimensionTests=logos_dimension:young_grass_grows
```

## 0.11.0 森林地带的四层补充（地被 / 地物 / 水边 / 地下 / 雾）

2026-10-25：森林地带从"只换树"改成四层结构，另加一层只覆盖四个群系的雾。全部数据包实现，无 Java 地形、种树或填块生成器，无新增方块。结构与边界见 [森林地带的四层结构](docs/FOREST_LAYERS.md)。

- **地被**：16 个森林群系各有自己的 `<biome>_floor`，林地每区块 640 次、林隙 360 次，与共享层同预算；只用既有 19 种地生植物与原版苔毯/落叶/雏菊。
- **倒木**：13 个林地各一份原版 `fallen_tree`，树干取本群系原木，长度 4–7、稀疏 1/6。
- **岩石露头**：13 个林地共用原版 `block_pile` + `journey_limestone`，稀疏 1/8。
- **水边**：13 个林地与精灵森林引用原版 `patch_sugar_cane` / `patch_waterlily`，零新增文件。
- **地下**：13 个林地共用原版 `multiface_growth` + `glow_lichen`，地表以下 13 格以下，每区块 104–157 次。
- **雾**：仅翳蕨密林、雾苔溪林、杉影深林、精灵森林；只改 `fog_color` / `water_fog_color`，**不动天空**，客户端 `ForestMist` 平滑过渡。

验证：最终 **41/41 单元**通过（新增 5 个森林数据测试类、共 16 项断言）；**50/50 GameTest** 通过（新增 `fallen_log_placement`，断言倒木真的倒下）。雾的客户端场景 `group:elven_forest` 为 3/3 场景、15/15 检查通过，并人工查看了精灵森林与翳蕨密林的雾色截图。

过程中的两个真实问题记录在案：`fallen_tree` 的 `log_length` 一开始写成 `{"type":"uniform","value":{...}}`，注册表直接报 `Not a number`，改成扁平写法才对；荧光苔最初密度 24–48 次/区块，扫描 80×80 找不到一块，改成原版档位后才稳定出现。

没有验证的部分：自然林地的前后对照截图（取景随区块随机数变化，像素差不能当作地表差异的证据）、洞穴取景（传送进洞会被碰撞推到地表）、第三方光影、硬件帧率。所有内容只在新生成区块生效，旧区块不重建。

```text
python tools/native_worldgen.py
python tools/tall_trees.py
python tools/grape_resources.py
python tools/magic_flora.py
python tools/berry_resources.py
python tools/elven_forest.py
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:elven_forest -PldTestHeadless -PldTestWindow=1600x900
```

## 0.11.0 精灵树屋、旋转回廊与呼吸光

2026-10-24：五种巨木增加带屋变体，带屋与无屋权重为 2:5，平均每 3.5 棵巨木一间屋；世界树外围固定两间，针阔叶采用不同木材与屋顶。新增悬挂绳梯、枝间叶辉、五层回廊、三格宽旋转楼梯和有限范围的同步呼吸光。全部新增贴图、模型和软件预览由代码制作。与远程 main `72b8ba7` 整合，保留其 NPC 渲染与发光骨骼更新；没有改动 Logos 本体或混入之前未提交的前置对齐改动。

初轮 20/20 单元、49/49 全套 GameTest 及构建通过。之后增加真实玩家步行测试，发现半砖高度交替错误和斜转角净空不足；修正后最终八项森林 GameTest 全部通过，玩家沿实际碰撞面无跳跃走过全部 160 次台阶过渡，验证顶面每级升高半格、楼梯净空、五层楼板与贯通光井。原版拼接仍为 343 块树体、1,670 个根部子件，初始悬空支点为零，底层九点夜间最低光照为 9。没有将修正后的八项定向复验记为再次重跑全部 49 项。

绳梯另外使用玩家实际运动检查上攀、潜行停留、缓降；拆除锚点后整链移除。针阔叶两种树屋通过原版模板放置检查连续绳梯、锚点、阳台入口与灯具。叶辉不能单独种在石头上。静态审计为 353 个 NBT 模板、343 个连通树体部件、3,007 个接口、五层楼板、161 个中心踏步和七组模板绳梯，无土台、模板实体或沙砾铺装。

整合最新 main 后，20/20 单元、构建及普通实际客户端 4/4 场景、17/17 检查通过。最终八张截图均为 1600×900；人工检查阔叶屋、修剪门前低枝后的冷杉屋、旋转楼梯和森林呼吸光，见 `docs/screenshots/elven-habitation/`。首轮无窗口截图被工具缩成一像素，不能用于美术验收；开发场景显式恢复离屏渲染尺寸并增加尺寸断言后重新复验。未运行第三方光影或硬件 FPS 对比。

呼吸周期为 120 tick，所有光点使用同一游戏时钟而不是独立随机相位。世界坐标哈希在负坐标下稳定，周期和平滑插值单元测试通过。光点只探测已加载近景，普通/减少/最少粒子设置的上限为 160/64/0，每刻最多十八个噪声格；光晕一次批量提交，实际方块照明保持恒定，不反复重算世界光照。

最终 JAR 审计确认含 353 个森林 NBT、新光源/绳梯/光晕类、五层回廊清单以及最新 main 的 NpcRenderer；无嵌入前置 JAR，元数据只有一份且版本为 0.11.0。SHA-256：`7db0a4385f212fd68af72bee7962c8b7f65c7f8638b535bc9cd7b0e2b8183761`。新树屋与楼梯只在新生成区块出现；旧森林可显示呼吸光，不扫描或重建玩家已有建筑。

```text
python tools/elven_forest.py
python tools/audit_elven_forest.py
python tools/preview_elven_habitation.py
./gradlew test runGameTestServer build -PdimensionTests=logos_dimension:elven* -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:elven_habitation -PldTestHeadless -PldTestWindow=1600x900
```

## 0.10.0 精灵森林与中空世界树

### 主分支、安装包与原版指令补查

2026-10-23：针对“两条定位都找不到”的反馈，检查 PR #26 已合并到 main，合并提交 92bf56f 与实现提交 742fa60 的文件树没有差异。将远程 main 导出到独立目录重新构建，18/18 单元及构建通过；与实际安装的 0.10.0 包逐项比较，3,978 项资源内容一致，4,233 个 JAR 条目在规范化文本换行后没有差异，类文件与二进制素材字节一致。本轮没有据此声称用户执行指令的服务器已经更新。

补充显式启用的正常专用服务器夹具 `-PjourneyLocateStage=fresh|upgrade|seed`，各自使用 runs 下的隔离目录，不使用 GameTest 的关闭结构开关。完整执行 `execute in logos_dimension:cloudsea run locate biome logos_dimension:elven_forest` 和对应的世界树 structure 指令，通过命令回调检查成功，不改用更远的自定义采样代替原版定位。

新种子 0、旧开发存档副本及反馈种子，在原点的 Y=96/320 各执行两条指令，十二次均成功。随后只使用正常区块加载检查反馈种子的世界树，没有手动放置结构，2,017 个子件及发光地板、双格书架生成正确。

这些结果排除了所核对主分支和安装包的漏交，尚不能证明反馈发生的具体存档、坐标或联机服务器配置正确。本轮没有改世界生成规则，也没有将尚未复现的问题标记为已修复。

同日补查 GitHub 实际下载渠道，发现仓库没有任何 Release，之前的安装 JAR 仅在本地交付，源码合并不能代替公开附件发布。已为 main 92bf56f 补发 v0.10.0 正式 Release，包含可直接安装的 JAR 和 SHA256SUMS.txt。随后通过公开下载链接重新取回 JAR，SHA-256 与上传候选完全一致；再次确认含 80 条森林气候条目、348 个精灵树模板、群系/世界树结构定义及 ElvenBlocks 注册类。README 补充安装包直链及联机两端更新说明。这修复的是公开下载渠道的缺口，不等于已验证具体联机服务器完成更新。

公开安装包 SHA-256：cd267acb1eadb96ace45b889917e10a663a7527b9fd67e3c71052f0b2bc6df73。

2026-10-21：在远行大陆内增加独立群系 elven_forest，地表群系由 32 增至 33。使用原版噪声/多噪声气候、NBT/jigsaw、单点原版树根 Feature。世界树由 343 个部件与 1,674 个根部子件组成，另带八棵外围巨木；五种普通巨木也通过原版结构集生成。树冠移除不可见的内部填充，树心加入少量叶背椅、阅览桌椅、双格藏书架、花架和实际方块光源。

18/18 单元、47/47 全套 GameTest 及构建通过，覆盖原有炼金釜、回程、流体、植物、村镇和六项新增森林测试。两组旋转样本均完整拼接 343 个部件，实际根部数量与模板逐接口统计完全一致。根部初始支点不实数量为零；向下树根另有 4/32 格水隙夹具逐格验证。书架物品成对放置、同向、失去支撑后成对移除通过；中央主通道无碰撞，外部预置箱子保留。

原版候选公式在真实气候采样中找到精灵森林，并在候选区块 [-127,-59] 通过结构自身的真实群系过滤，完整生成 343 个树体部件。该项验证原版选址和拼接数据，不是普通新存档自动装饰全过程；GameTest 的自然结构生成开关默认关闭。

无人值守异维度最初因区块卸载读到零光照，测试夹具改为在光照传播期间保留树心区块，未改游戏光照算法。最终中央九处采样最低方块光照为 9。原版根部 Feature 的连接口采用 rollable，修正固定方向导致支点全部被拒绝的问题；树体之间仍使用 aligned 保持对齐。

普通实际客户端最终 3/3 场景、18/18 检查通过，人工查看了全树、夜间内厅及阅览角，截图位于 docs/screenshots/elven-forest/。最终将开发场景移至真实群系候选坐标后，再次 6/6 森林 GameTest、3/3 客户端、18/18 客户端检查及构建通过。照片仍是已生成地形上的原版拼接预览，不声称测试过第三方光影或硬件 FPS。高树首次生成明显重于普通树，陡地根柱可能外露。

随后仅给新精灵叶块的叶心及边缘面补上相邻叶块剔除，减少不可见的内部重复绘制；单独全树客户端再次 1/1 场景、6/6 检查和构建通过，最后外景截图已更新。没有将这次模型复验记为全部世界测试重跑，也未测量 FPS。

静态审计通过：348 个 NBT 模板、343 个树体部件连通、最大拼接深度 12、2,685 个合法接口，十格宽中央通道净空、家具模型 UV/纹理引用、无土台及实体。完整资源脚本重跑，3,978 个资源的字节或规范化 JSON 内容全部一致；原聚落审计继续通过。上方高度扩至 512，原噪声地形保持 384，高处新增空间为空气。新区块生效，不扫描重建旧树或建筑。

## 0.9.3 主分支整合核对

2026-10-20：发现 PR #24 合并到 feat/journey-tall-trees，而默认分支 main 仍为 0.3.1，导致首页下载与已交付的 0.9.3 不一致。新整合分支从远程 main 建立，将功能分支已完成的 0.9.3 整理为一个提交；合入前的暂存树与远程 feat/journey-tall-trees 完全一致，main 的两个独有提交均为合并记录，没有额外代码内容被覆盖。

本轮仅新增默认 PR 目标规则、源码下载版本说明与本记录，运行源码和资源沿用已验证的 0.9.3。构建、17/17 单元测试及聚落静态审计通过；将新构建与此前交付 JAR 的全部 3,456 个条目逐项对照，1,064 个 JSON 仅有换行符差异，其余条目字节相同，类文件和贴图无差异。原安装 JAR 的 SHA-256 为 40c4ab357bbde644d1df3e96d8d5941eb03a9155d591946a3bb1fa486d80bfb9。下方世界和客户端记录属于此前实测，不将此次整合声称为全部世界测试重跑。精灵森林仍未实现。

## 0.9.3 石基与支墩接地

2026-10-20：给建筑实际底部的外轮廓设置连续石基，内部为四格支柱网格；广场、井和摊位补基础，道路每四格一排支墩。使用原版单点 feature_pool_element 与向下 block_column，从承重点下方填石砖，遇到第一个实心方块即停，不挖穿土层或改邻列。检查偏移 +1 对齐原版柱形 Feature 的下一格检测，避免柱脚余下一格空隙。只在生成时运行，没有 Java 填地器或逐刻更新，不恢复矩形土台或地形适配。

17/17 单元和首轮 5/5 定向 GameTest 通过。新增完整深度测试覆盖 0、1、4、12、35、90 格空气/水间隙，逐格确认 142 格石基填实，原土层、上方建筑和相邻箱子保留；井、室内空气/外部结构空位、小麦与拼接回归通过。之后将冗余内部支点从 5,153 个减至 1,933 个，保留外墙与支柱网格，最终并未重复宣称跑过全部世界回归。

优化后逐端口核对发现道路的地形投影使部分子件超出父件的名义碰撞盒，原版拒绝了 70 个支点。道路补入 141 个备用端口，与各自原端口投影到同一位置，分别使用内部/外部自由空间，只接入一个支墩。修正后单独 settlement_native_assembly 通过：八组固定样本保持 16/24 栋、32 个小麦 Feature，并断言所有预期支点与实际接入数完全一致。最终静态审计为 18 模板、10 池、2,136 个接口，其中 1,933 个支点位置、141 个备用端口和 28 个种植锚点。

实际客户端 grounded_settlements 为 2/2 场景、12/12 检查通过。小镇/村落分别有 2,924/1,883 个支撑子件，逐点检查起始格均为实心且不是树叶，不实位置为零；这项检查与上面的完整深度测试联合使用，不单独声称逐列扫描到了地心。人工查看了井与陡岸基础，陡岸样本石柱深 54 格，保留在 docs/screenshots/settlements/shore-foundation.png；陡坡仍可能出现较高的砌石基座。

随后只调整开发相机，补拍普通 8 格石基的房屋/河床近景，grounded_foundation 单场复验 1/1、6/6 检查及构建通过，见 grounded-foundation.png。井的最终截图为 grounded-well.png。没有把单场补拍说成全部客户端场景重跑。场景仍是在已生成地形上调用原版拼接器，未验证普通新存档自动选址、第三方光影或硬件 FPS；不承诺解决所有陡坡嵌入问题。

最终再运行聚落资源脚本，全部 3,214 个资源文件的 SHA-256 保持不变，静态审计通过。

新区块中的新聚落生效，不扫描或重建旧聚落。村民、动物、旧土台和玩家改建仍不自动删除。

## 0.9.2 蓝图土层、净空、石路与井

2026-10-19：删除七份源蓝图入口以下的 2,311 个地表/支撑作物方块（每种源图统计一次），同时取消生成脚本追加的整块泥土与草坪底板。房屋基础、楼板与高处花槽保留。室内封闭空腔、有楼板的有顶空位和门口使用空气，外部及原土层位置使用结构空位；原版处理器只忽略结构空位，不忽略空气，池覆盖 ignore_waterlogging。石路替代沙砾，井改为石砌密封井筒、下沉水面、四柱人字木顶、辘轳与链灯。模板实体列表全为空，远行大陆动物生成表全为空，聚落 creature 生成覆盖也为空。

17/17 单元、4/4 定向 GameTest 与构建通过。七种房屋各四个旋转，896 处预填石头/水的空气位置被清空，896 处外部结构空位中的泥土/水原样保留，原版放置未生成任何 Mob。井的封闭底部、四格水、四根柱、屋顶与辘轳通过原版池放置验证。八组村镇拼接继续为 16/24 栋、32 个单点小麦 Feature；七层坡地 135 株小麦及 7,938 格保护快照回归通过。本次没有重跑全部其他世界回归。

首次加载检查发现 block_ignore 的 blocks 列表需要方块状态对象，改正后注册表及上述世界测试全部通过。完整资源重新生成，3,211 个文件字节一致。静态审计覆盖 18 个模板、9 个池、62 个接口及 28 个农舍种植锚点，确认无低层土台、沙砾/沙子/混凝土粉末铺装或模板实体。

普通实际客户端首轮 blueprint_settlements 为 4/4 场景、16/16 检查通过，井与房屋地表近景已人工查看，见 docs/screenshots/settlements/covered-well.png 与 foundation-free-houses.png。两张远景镜头偏离聚落，修正开发相机为等待实际井顶方块到达客户端后重新瞄准，增加朝向检查；随后单独 blueprint_town 为 1/1 场景、5/5 检查通过，最终远景已人工查看，见 stone-road-town.png。相机修正后没有声称重跑全部四场。场景使用已生成地形上的原版拼接，未验证正常新存档的自动选址、第三方光影或硬件 FPS。

已有区块中的土台、村民、动物和旧水田不自动删除，避免破坏玩家修改；新的模板在新生成聚落生效。动物自然生成表修改不会删除已有实体，也不拦截手动生成或其他模组的额外规则。结构空位不保证净空；开放房间仍可能后来进水，水下建筑需要密封外壳。刚性建筑在极端坡地/河谷仍可能悬空或嵌入，不声称这次已解决所有选址贴合问题。

## 0.9.1 原版小麦沿地势种植

2026-10-19：撤掉独立水田模板、灌渠、粮棚和额外的 40 格农舍田区，生成资产缩为 18 个模板、9 个池。原版 feature_pool_element 仅提供单点种植入口，原版高度图、条件过滤和 block_column 负责种植原版耕地/小麦。取消聚落 Beardifier 自动地形适配。旧水稻 ID 只用于旧存档兼容，从创造栏隐藏，不再生成。

16/16 单元测试、2/2 定向 GameTest 通过；本轮没有声称重跑全部 38 项世界回归。坡地测试在七个不同高度种出 135 株小麦，对照 7,938 个方块快照，只有原土壤转耕地、原空气转小麦两类变化；石头、木板、道路、箱子、水和已有植物不变，也没有填底、清空或改写其他高度。八组固定结构拼接仍为 16/24 栋、32 个单点种植 Feature，没有旧水田部件；实际结构 terrain_adaptation 为 none。

普通实际客户端 blueprint_settlements：最终 3/3 场景、12/12 检查通过。预览明确排除蓝图原有小麦的位置，三个样本分别读到 680、983、852 株新小麦；这不是自然世界的固定产量。新近景见 docs/screenshots/settlements/terrain-wheat.png。场景是在已生成地形上调用原版拼接器，未测试普通新存档的自动选址、硬件 FPS 或第三方光影。

静态审计确认旧水田和旧田区池已移除、62 个接口完整、28 个蓝图农舍种植锚点（每类农舍四个）引用合法，种植只包含一格原版耕地和一格原版小麦。完整资源生成再次保持字节一致。

0.9.0 已生成的田块保持原样，不自动删玩家修改，也不臆造被覆盖前的原地形；需在新生成区块体验修改。下方 0.9.0 的水田说明与截图为历史验证记录。

## 0.9.0 蓝图村镇与大稻田

2026-10-19：改造用户提供的七份蓝图，输出 27 个标准 NBT 部件与 11 个原版模板池；保留原作者元数据与原文件 SHA-256。源实体、物品栏和方块实体未导入，移除控制方块并替换矿石/贵重资源方块，村民为明确编写的原版居民。旧单块村镇模板和起始池移除，locate ID 保留。生成仅使用原版 jigsaw/random_spread，中心群系限定为四类开放地带。

16/16 单元、38/38 GameTest 全套通过，覆盖旧版植物、葡萄、蓝莓、流体、炼金釜及回程。新增世界测试验证浅水泥巴种稻、成熟骨粉保持源水、2–4 份成熟收割、石头支撑脱落、干地拒绝。八组原版拼接样本（两种聚落，各四个固定种子）全部生成村落 16 栋/小镇 24 栋，并连接 3–4 片 52×56 格稻田。后续只调整模板净空、NBT 目标版本与稻秆高度：最终模板再次通过拼接测试，最终模型再次通过客户端与构建；没有把这些复验说成再次跑过全部世界测试。

普通实际客户端 blueprint_settlements：3/3 场景、12/12 检查通过；最终稻秆增高后单独 blueprint_paddy 再次为 1/1 场景、4/4 检查通过。近景田块实际存在 1,523 株水稻，成熟穗露出水面。截图保存于 docs/screenshots/settlements/rice-paddy.png，属于在已生成地形上调用原版拼接器的开发预览。

客户端测试世界默认关闭自然结构，第一次自然查找因此超时；改为明确的拼接预览，未把该失败当成自然生成通过。预览也不重新运行地形生成阶段的 Beardifier，所以部分模板地基与周围地形衔接不代表新世界的地形适配效果。本轮没有验证正常新存档的自动选址、光影包或硬件 FPS。自然聚落实际部件数仍可能因高度和碰撞减少，旧区块不会重建。

静态审计通过：27 个部件边界合法、50 个连接口完整、3,046 株模板水稻均有泥巴支撑，池与模板引用齐全。完整资源脚本再生成的 3,218 个资源字节一致。无新增 Java 地形/建筑生成器、水稻方块实体或逐株扫描。

## 0.8.1 林冠高大树木

2026-10-18：在七种现有林地使用原版 tree/placed feature 稀疏混入高冠阔叶树和 2×2 树干的高塔冷杉，配置高度分别为 16–25、22–35 格，每个适用区块 1/3 概率一次候选，可能因空间或土壤失败。地形、建筑、普通树和已有树叶模型不变，新区块生效。

15/15 单元、36/36 GameTest 全部通过。新世界检查用原版 Feature 实际生成树形，样本树干最高点相对根部 22/25 格、原木 78/101 个、树叶 848/411 个；真实自然冷杉林 96×96 格样本找到 33 格高树干。原有植被、光照、葡萄、蓝莓和炼金釜回归通过。自然采样显式加载固定区域后读取高度；旧蓝莓测试改为等待实际光照传播条件，消除固定 10 刻在区块生成负载下不稳定的夹具，不改变生产光照要求。

普通实际客户端 tall_trees：2/2 场景、8/8 检查通过，第一场为原版 Feature 的隔离画廊，第二场为自然生成森林，截图见 docs/screenshots/tall-trees/。最终 0.8.1 的旧环境实机回归 journey_ambience 亦为 4/4 场景、21/21 检查通过，覆盖昼夜、冬季、粒子预算、关闭/恢复和退出维度清理。完整资源再次生成，3,162 个文件字节一致。运行时仅原版数据生成大树，没有新的 Java 种树器、逐树更新或地图扫描。没有硬件 FPS 或第三方光影验证。

## 0.8.0 魔法生态细节与发光植物

2026-10-18：新增星露草和萤灯蕨的模型、主体与小范围发光遮罩，实际照明为 2/3 级，原版数据包稀疏装饰林地。增加植物靠近回应、菌蕨孢子、草间浮光、贴地小符纹、静水银环与避让游光，以及有冷却的轻微泛音。没有改地形、建筑或新增实体。

14/14 单元、34/34 GameTest 通过。新检查覆盖粒子种类共享 80/28 总预算、连续运动、水面/贴地效果保持平面；世界检查确认两种植物真实照亮相邻格、自然森林样本有 30 株发光植物，21 种地生植物的土壤和旧状态清理回归保留。普通实际客户端 magic_details 为 3/3 场景、12/12 检查通过：正常靠近触发花/菌、符纹和银环，单株站立冷却、声音限频、最少粒子关闭。截图为专门摆放的近景，见 docs/screenshots/magic-details/，没有强制添加粒子。

完整资源连续两次生成，3,148 个文件字节一致；最终截图调整后重新构建和运行全部三个近景。新增纯客户端局部探查每 4/8 刻最多检查 8 个已加载位置，最多记录 96 处触发点。全部环境效果共用既有数量上限；自身明亮的粒子不会提供动态照明。没有光影包或硬件 FPS 检查。

## 0.7.1 大型蓝莓灌木

2026-10-18：林莓灌丛保留原有方块/物品 ID，改为大型蓝莓灌木。16 张独立 64×64 贴图覆盖四阶段与四季；几何采用原版甜浆果的对角交叉结构并放大，成熟约 1.25 格宽、1.375 格高，4 个面。原物品变为可食用、可种植的蓝莓；自然林下原版 Feature 生成成熟灌木。

12/12 单元、33/33 GameTest 通过；新增测试用真实光照传播与区块随机刻检查黑暗和明亮条件，覆盖两种采摘产量、回退生长期、防止重复采摘、食用、果实种植、骨粉与土壤丢失。第一次黑暗夹具检查早于异步光照完成，增加等待后通过，没有改宽生产光照条件。最终资源只调整交叉几何后重新构建和跑实际客户端，没有重复运行机制测试。

普通实际客户端 blue_berries：1/1 场景、4/4 检查通过，包含实际发送右键采摘与两次骨粉再生；截图是摆放的模型对照场景，见 docs/screenshots/blueberries/。新灌木生长沿用原版甜浆果类与 NeoForge 钩子；旧存档缺少 age 的灌木从幼苗继续生长，不扫描重写旧区块。

最终 0.7.1 再运行普通客户端 journey_ambience：4/4 场景、21/21 检查通过，确认新树木资源下的夏夜萤火虫、白天/冬季微光、粒子预算与维度退出清理。场景为摆放的树林池塘，正常环境发射粒子，截图见 docs/screenshots/combined-ambience/。完整脚本再次生成，3,128 个资源字节一致，全部与最终 JAR 内资源逐字节匹配；96 个原木模型和石灰岩均保留封闭六面主体。

## 0.7.0 独立原木与叶心

2026-10-18：32 种树叶对应 32 种独立原木，96 份模型、128 张 64×64 树皮/年轮贴图。所有原木使用完整封闭主体与浅树皮浮雕，原版三轴、日志标签、斧与叶距离更新保持。128 份季节叶模型补入三向中央叶簇，模型 14 面并指定剔除，内部贴图覆盖率约 73%–98%。26 个有树的原版 Feature 改用对应原木，没有新增 Java 世界生成器。

12/12 单元测试、31/31 GameTest 通过。新增原木测试覆盖注册、三轴、标签与正常邻居更新后的原版叶距离；固定自然森林确认对应新树干和树叶。普通实际客户端 tree_families：5/5 场景、16/16 检查通过，纹理与叶心画廊是摆放场景，最后一场是自然森林。截图见 docs/screenshots/tree-families/。新树干仅用于新区块，旧原版树干不自动替换；旧 cloudsea_stem 保持兼容并更新外观。本轮没有光影或硬件 FPS 检查。

## 0.6.1 石灰岩拼接封口

2026-10-17：移除与完整方块遮挡形状不一致的侧面内凹几何，石灰岩六个外表面连续封闭，层理与裂隙由原有不透明贴图表现。每个外表面按 32×32 采样验证完整覆盖，6,144 个样本无开口。

构建及 11/11 现有单元测试通过；普通实际客户端 group:limestone_fix 为 1/1 场景、3/3 检查通过，另保存前、后、侧面、顶部四张实际拼接画面，位于 docs/screenshots/limestone-seams/。本轮只改静态石灰岩模型与开发相机，没有重复运行世界机制、光影或硬件 FPS 检查。

## 0.6.0 萤火虫与环境微光

2026-10-17：新增三类原版粒子类型：带双帧翅膀、独立闪烁与弧线飞行的萤火虫，随季节换色的浮光，以及植物附近的小型四角星芒。全部为客户端局部粒子，不生成实体或动态光源；自身明亮但不照亮周围方块。黄昏/黎明连续淡入淡出，冬季和雨天没有萤火虫。

最终 11/11 单元、30/30 GameTest 通过；单元新增生态时段、昼夜连续性、飞行范围与淡入淡出检查，世界检查确认专用服务器注册正常且既有植物支撑、葡萄、流体和炼金釜回归保留。普通实际客户端 group:journey_ambience 为 4/4 场景、21/21 检查通过，覆盖夏夜、白天、冬季、全部/减少/最少粒子设置、关闭后恢复、原版粒子池清空后恢复和离开维度清理。

四个场景的树林池塘为专门摆放，粒子全部由正常环境发射逻辑产生，没有直接强制添加。实机截图与八张连续飞行帧见 docs/screenshots/journey-ambience/。本轮没有光影包或硬件 FPS 对照，没有声称粒子能投射动态光照。

全部粒子设置下同时最多 80 个本附属环境粒子，减少设置最多 28 个，最少设置关闭。每次最多采样四个已加载近邻列，单列最多检查 12 个候选高度；没有远区块加载、全维度扫描或新的后台线程。粒子运动范围受限，离开 24 格或进入实体方块/流体时移除；原版粒子池清空后过期引用会在后续客户端刻释放。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:journey_ambience -PldTestHeadless -PldTestWindow=1600x900
```

## 0.5.2 植物土壤支撑修复

2026-10-17：先新增回归测试，复现石头上可种植、土壤替换为石头后植物不脱落、基础植物不参加旧状态清理三项失败。原因是 JourneyPlantBlock 只检查坚固顶面，另外四种旧地生植物仍注册为普通 Block。现在 19 种地生植物统一继承原版 VegetationBlock，使用 `supports_vegetation` 与 NeoForge 土壤支撑决定。32 种自定义草皮加入原版标签；葡萄藤和以太植物仍保留原有附着/专用土壤规则。

最终构建、8/8 单元及 30/30 GameTest 通过。新增三项世界测试包括：19 种植物在 42 种合法基底和 12 种非法基底上的 1,026 次实际 BlockItem 放置，拒绝时不扣物品；季节状态不改变合法支撑；土壤换成石头或移除后立即脱落；以跳过邻居校验的方式构造旧错误状态，然后通过 ServerLevel.tickChunk 的真实随机采样清理全部 19 种植物。后者模拟旧区块状态，不是服务器重启升级测试。自然森林样本中逐株检查植物 canSurvive，原生植被覆盖与葡萄、流体、炼金釜回归仍通过。

普通实际客户端 `group:plant_support`：1/1 场景、3/3 检查通过。实际发送右键交互，确认石头上不能种蕨且物品保留，泥土上成功并消耗一个物品，随后把泥土换成石头，客户端和服务端均确认植物脱落。截图见 `docs/screenshots/plant-support/`。本轮没有重跑全部美术场景、光影或服务器重启；没有新增地形、模型或植被扫描线程。

完整资源脚本再运行后 2,638 个资源文件字节一致，土壤标签不会被生成脚本覆盖回旧值。旧存档中的非法植物仅在已加载区块随随机刻逐步脱落；关闭随机刻时仍由邻居支撑更新清理，不扫描整张地图。有效植物与玩家建筑保留。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:plant_support -PldTestHeadless -PldTestWindow=1280x720
```

## 0.5.1 草皮整体重做

2026-10-17：32 种草皮、四季共 384 个模型，每季三套纹理并使用四向原版随机旋转。地表区分草原、落叶林、湿苔、松针、高山、寒地、海岸、积雪与湿地，冬季霜色柔和混合，侧面采用不规则草根与土层。保留每块六个剔除面、完整碰撞与已有方块 ID。

最终 0.5.1 普通实际客户端 `group:journey_turf`：5/5 场景、16/16 检查通过，包括 32 种草皮对照、连续草原、三类林地地表、同屏四季和真实自然生成森林。前四个是专门摆放的画廊；画廊临时暂停随机刻，拍完恢复，避免季节状态在对照中变化。截图见 `docs/screenshots/journey-turf/`。没有本附属模型烘焙或纹理缺失错误；本轮没有光影包或硬件 FPS 对照。

构建完成，8/8 现有单元测试通过。资源审计确认 32 个方块均有四季状态，每季 12 个原版变体选项，引用三份合法六面模型；共 2,304 个草皮 UV 面与全部 32×32 不透明贴图合法。完整资源脚本再次生成，2,638 个资源文件字节一致，已有叶块、植物、葡萄和群系数据未改变。本轮只改草皮美术及开发拍摄场景，没有重复运行 GameTest、葡萄交互或服务器重启检查；上轮机制验证范围保留在下方 0.5.0 记录。

```text
./gradlew build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:journey_turf -PldTestHeadless -PldTestWindow=1600x900
```

## 0.5.0 方块美术与贴墙葡萄藤

2026-10-17：覆盖远行大陆全部 96 种方块的模型或材质。石灰岩为灰白岩层与浅凹接缝；树叶增加镂空叶簇和边缘层，牧草覆盖完整 16×16 地面范围，蕨、灌木、花穗、芦苇和菌盖分别细化。保持静态模型，没有逐株方块实体或新渲染线程。

8/8 单元、27/27 GameTest 通过，新增四项葡萄测试覆盖实际 BlockItem 五面附着、移除支撑后脱落、攀爬标签、无实体碰撞、采果/摘叶、未成熟与裸藤不重复掉落、剪刀耐久、骨粉重新长叶结果，以及真实玩家食用回复 2 点饥饿值。原版生成的固定森林 64×64 格样本找到 48 株葡萄藤，逐株检查有依附面；该数量不代表所有种子或森林密度。

普通实际客户端 `group:journey_art`：7/7 场景、24/24 检查通过。包含真实发送右键采葡萄并持续使用进食，服务端确认饥饿值 10→12、果实消耗、藤上果实归零。六个模型画廊为专门摆放；森林场景为原版自然生成。截图见 `docs/screenshots/journey-art/`。本轮没有重跑光影包、重启持久化或硬件 FPS 对照。

资源审计：404 个模型、3,615 个面 UV 与本地纹理引用合法，96 个方块状态均引用存在的模型；128 份叶模型每份 10 面并指定剔除，草毯纹理覆盖 835/1024 像素。37 个配置 Feature 全部为原版类型，没有矿物 Feature。完整脚本连续生成两次，1,861 个资源文件逐字节一致。最终接缝封口调整仅涉及两种实心材料的静态模型，随后重新构建并重跑全部七个客户端场景；机制与自然生成数据沿用上述 GameTest 覆盖。

旧物品 ID 与已有区块保留；旧版无附着面的葡萄藤在随机刻时脱落。JAR 不内嵌主模组或第三方依赖。版本元数据与 Gradle 均为 0.5.0。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:journey_art -PldTestHeadless -PldTestWindow=1600x900
```

## 0.4.1 缺陷复查

2026-10-17：针对 0.4.0 复查，新增回归断言先复现两项失败，再修复：

- 原版 LeavesBlock 仅让腐烂条件下的叶块参与随机刻，健康树叶和玩家放置的树叶因此无法换季。现在允许季节叶参与随机刻；测试使用 distance=1、persistent=true 的健康状态，通过 ServerLevel.tickChunk 的真实随机采样更新到冬季，没有直接调用叶块 randomTick 或强制改 season。常绿树仍保留原有不换色逻辑。
- 首个安全落点周围没有可放出口的空间时，原先直接返回失败，使炼金釜世界无法进入。现在把出口空间一起纳入落点选择，继续检查其他外围以太落点；回归测试用黑曜石封住首个落点的全部出口候选，确认另选落点且原建筑全部保留。

最终 8/8 单元、23/23 GameTest 通过。普通实际客户端 1/1 场景、7/7 检查，验证重力跳釜、动态 UUID 同步、右键自动回程、重入与不同釜隔离。0.4.1 没有重跑七个全大陆美术场景、光影或双进程重启；这些沿用下方 0.4.0 的明确范围。绑定、销毁、旧固定维度不可访问、流体与无矿物回归包含在本次 GameTest 中。

本次没有确认其他可复现故障，不代表穷举了所有种子、第三方模组或用户建造情况。

## 0.4.0 原版数据包生成与连续森林

2026-10-17：两个维度的原版 noise / multi_noise 数据加载通过。8/8 单元、22/22 GameTest 通过；删除的旧 Java 地理/树形测试由原版数据策略与真实区块检查替代，不能与旧版 23 项直接比较。实际样本 32×32 格密林有 1,282 个原木、9,133 个叶块，899/1,024 根地表列存在蕨、灌木、苔毯或落叶；这是固定测试种子的样本，不代表所有森林都具有同一比例。

普通实际客户端：远行大陆 7/7 场景、28/28 检查，炼金釜 1/1 场景、7/7 检查。覆盖原版生成森林/林隙、春夏植物画廊、秋冬林地、局部魔法粒子、实际重力跳釜、动态 UUID 世界同步与渲染、真实右键自动出口返回。截图见 docs/screenshots/native-worldgen/。植物画廊为专门摆放，季节场景主动调整时钟和附近季节状态；没有重跑光影或做 FPS 对照。密林树荫较深，林隙提供开阔区域。

真实服务器另用启用原版结构的专用存档检查：中心 NBT 空岛实际存在，121 个平台采样点顶面均为岛石、上方为空；原版 jigsaw 聚落实有石灰岩建筑，非仅检查注册。最终增加的空岛装饰过滤由 GameTest 和实际服务器检查覆盖，客户端画面检查早于该过滤；普通地面植被规则相同。

最后两次独立服务器进程完成创建和重启检查，UUID、种子、区块改动、放置釜绑定、Boss 状态、20,000 格边界、删除记录与已删除目录共 8 项保留正常。测试存档来自旧版实例验证，启动后固定 logos_dimension:crucible 不再加载。Photon/LDLib2 专用服务器依赖扫描仍有既有客户端类缺失日志，但服务器完成检查并正常保存退出。

资源审计：397 个模型的本地贴图引用与 1,180 个面 UV 合法，33 个配置 Feature 均为原版类型，没有矿物装饰或自定义 ChunkGenerator 子类；资源脚本再次运行字节一致。版本元数据与 Gradle 均为 0.4.0。

布局改变与限制见 docs/NATIVE_WORLDGEN.md：自然大群系替代同心环，中心岛约 126 格直径，旧区块保留、可能与新地形接缝；没有声称旧区块已重绘、所有种子安全落点均已穷举、第三方光影或强制断电全部通过。

## 0.3.1 自动回程入口

2026-10-16：23/23 单元与 22/22 GameTest 通过；新增断言覆盖落点旁自动出口、重入复用、出口损坏不删除维度及修复。普通实际客户端 1/1 场景、7/7 检查通过，改为真实发送右键交互到自动生成的漩涡，返回原釜旁，无需开发场景预放绑定釜或携带材料。入口坐标按世界 SavedData 保存，旧实例内登录也会补出口并告知坐标。此次没有重跑光影和服务器双进程重启检查。

## 0.3.0 炼金釜 UUID 实例

2026-10-16：23/23 单元测试、22/22 GameTest 通过。世界检查新增四项，覆盖首次进入懒创建、不同 UUID/种子与 Boss 状态隔离、20,000 格边界和外围以太落点、已有区块保留、生存/创造实际挖掘掉落与拾取再放置、岩浆伤害销毁物品后撤离在线玩家、只删除对应 UUID 磁盘目录，以及返回栈/旧存储格式往返。

普通实际客户端 `group:cauldron_instances`：1/1 场景、7/7 检查通过；以真实重力落入两口釜，验证动态 UUID 世界同步/显示、绑定出口返回、同一釜重入同种子、不同釜独立实例。截图见 `docs/screenshots/cauldron-instances/`，是普通渲染下的实际到达画面。客户端运行时 Java/Gradle 项目版本为 0.3.0、模组展示元数据仍为 0.2.0，之后仅同步展示元数据。最终路径保护另由同一套 GameTest 重跑覆盖。本轮没有重跑光影或全大陆客户端检查。

另在专用存档启动两个独立的真实服务器进程，先创建并保存，然后完全退出后重新启动；8/8 检查通过：UUID 自动恢复、种子、修改过的区块、放置釜绑定、Boss 状态、边界、删除记录与已删除目录均保持正确。结果文件为 `runs/cauldronPersistence/world/cauldron-persistence.properties`，复现命令见 [独立世界说明](docs/CAULDRON_WORLDS.md)。第二进程检查早于最后的删除路径实路径限制与写盘顺序补强，后两项由最终世界测试覆盖。

专用服务器启动中，Photon/LDLib2 依赖扫描有既存的客户端类缺失日志；两进程均完成启动、检查和正常保存退出。没有本附属实例创建/卸载/读写异常。没有声称通过强制断电或全部第三方删物品行为测试。

## 0.2.0 温带旅途大陆

2026-10-16：21/21 单元测试、18/18 GameTest、普通客户端 8/8 场景与 16/16 检查通过。实际检查北方山带/南方海岸、水系连续、至少 20 种生态覆盖、原版洞窟与峡谷雕刻器接入、无矿物、投影结构加载与真实区块楼板、四季换叶/冬季降雪/温度作用域隔离，并保留原咒械原与流体检查。

最后一次普通客户端包含单纯形地貌、2–4 层住宅、原版动物和村民装饰、果园、改进树冠及植物选择框，截图位于 `docs/screenshots/journey/`。客户端测试时版本元数据为 0.1.4，随后仅升级元数据到 0.2.0；实现相同。八个场景为平原、雪山、海岸、乡村、城镇、植物画廊、秋林和冬林。画廊为专门摆放的模型展示，其他为实际生成地貌，季节场景主动推进时钟并刷新周边材质便于截图。

此前一轮 Complementary Reimagined r5.9.3 实际开启，8/8 场景、16/16 检查通过；这一轮早于最后的单纯形轮廓、楼层变体、植物支撑与村民补充，不当作最终所有改动的光影验证。光影包会使用自己的云高和雾色。Photon 本轮没有重跑。没有声称测得 FPS 提升或完成城市素材的最终美术。

新增植物模型的纹理引用、坐标范围与资源脚本重复生成字节一致通过。法阵放大另以 `group:glass_array` 检查，1/1 场景、3/3 检查通过；流体世界检查覆盖移动、上浮、源头/流动格填方块、汞中毒/虚弱、熔融玻璃点燃伤害与原有流动/装桶。

## 0.1.4 法阵连续过渡

20/20 单元测试通过；普通连续录制 1/1 场景、3/3 检查，237 张真实渲染帧。Complementary r5.9.3 与 Photon v1.3b 各以实际启用的光影运行法阵组，分别 1/1 场景、3/3 检查通过。本次没有声称再次验证全维度或 GameTest；此次修改仅涉及客户端与开发录制场景。录制负载与普通游戏性能分开，细节见 `docs/SMOOTH_ARRAY.md`。

## 0.1.3 移除云层、完整树木与玻璃法阵

2026-10-15：18/18 单元测试、14/14 GameTest 通过。最终普通渲染、实际启用的 Complementary Reimagined r5.9.3 和 Photon Shader v1.3b，各通过 10/10 场景、15/15 检查，每套保留 26 张实机截图。最终小修为针叶冠收尖、叶片镂空和测试相机视野，随后重跑单元与三套客户端；世界检查覆盖的是同一套注册、方向、碰撞与生成规则。

检查展示完整圆冠/针叶/伞冠/垂冠树木、24 种叶块、材料画廊、16 帧连续玻璃法阵、旧云层上方和内部空域，以及实际云域地貌。没有云网格工作线程，发行 JAR 不包含旧云渲染器或密度场。原云层截图呈纯天空颜色为预期结果。没有本附属模型烘焙、缺失纹理、顶点属性或玻璃着色器编译异常。

资源审计覆盖 95 个模型的纹理引用和 UV 范围，生成脚本重复运行字节一致。24 种叶块模型合计面数 912→144，24 种地表 387→144；这些是几何计数，不代表已测得的 FPS 提升比例。没有运行同场景的硬件帧率对照，不声称所有卡顿已消除。新树形仅在新生成区块出现，旧区块更新材质，不自动拆改建筑。

玻璃使用解析菲涅耳、镜面带与边缘色散，不读取场景颜色或复制帧缓存；光影可改变背景和玻璃颜色。截图在 `docs/screenshots/revision-0.1.3/`，组合检查组为 `group:visual_revision`，组合安装包与源码分支为 `release/refinement-0.1.3`。独立主题继续更新原 PR #4、#5、#6。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:visual_revision -PldTestHeadless -PldTestWindow=1600x900
```

## 0.1.2 法阵变化、独立模型与立体云海

2026-10-15 完成三项改动的组合验证。JUnit **18/18**、GameTest **13/13** 通过。最终几何修正后，普通渲染、实际启用的 Complementary Reimagined r5.9.3 和 Photon Shader v1.3b，各通过 **8/8 场景、12/12 检查**，各保留 41 张实机截图。GameTest 后的修正仅涉及客户端云轮廓透明度与地表模型浅刻面的自遮挡；最终三套客户端均包含这两项修正。

客户端场景覆盖三组共 24 种悬叶、材料画廊、32 帧连续法阵变化、云上、穿入/离开云体、剑开天门与魔鲸。没有本附属的模型烘焙失败、缺失纹理或顶点属性异常。80 种方块的几何布局均不同（四阶段材料共输出 95 份模型），资源脚本重复生成一致。云密度场另外检查实际厚度、缺口、负坐标、相邻网格边界与法线；法阵检查短时间可见变化及连续插值。

实机截图见 `docs/screenshots/refinement/`，不是概念图。云采用三维密度场生成的有厚度网格，玩家可穿过；它不是全屏体积光线步进。光影包可覆盖局部雾色、天空和远云，没有对所有硬件帧率或未测试光影作承诺。未加入 Boss、云端平台、矿物或新的实体生物。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:refinement -PldTestHeadless -PldTestWindow=1600x900
```

三个独立 PR 分支分别提供 `group:arcane_animation`、`group:individual_models`、`group:cloud_volume`。`group:refinement` 位于组合版本分支 `release/refinement-0.1.2`，引用三项实现；组合安装包对应此分支。

## 方块模型、天空法阵与放大炼金釜

2026-10-15 完成本轮美术与交互范围检查。JUnit 13/13、GameTest 13/13 通过；炼金釜检查同步验证放大后的釜沿碰撞高度、内部中空、碰外壁/站釜沿不触发、延后传送及冷却。普通客户端、实际启用的 Complementary Reimagined r5.9.3 和 Photon Shader v1.3b 下，`group:model_polish` 各通过 **4/4 场景、6/6 检查**。

四个实机场景包括材料画廊、立体植物画廊、法阵四阶段画面及炼金釜实际重力落入。分别检查模型加载日志，没有本附属的模型烘焙失败、缺失纹理或顶点属性异常；首轮发现的发光壳 UV 越界已修复后重新检查。主模组自带魔杖的粒子纹理警告与本轮无关。截图在 `docs/screenshots/model-polish/`，其中 `astrolabe-pattern.png` 是纹样合成预览，其他为普通渲染实机截图。

两种光影会使用自己的云层与天空色，法阵背景明暗可以不同。没有对本轮未运行的其他客户端场景声称重新验证，也没有在本轮加入新群系、生物、Boss 或入口。

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos-0.5.1.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:model_polish -PldTestHeadless -PldTestWindow=1600x900
```

## 天门云域验证

2026-10-14 完成第二维度的独立检查。下列客户端场景只覆盖天门云域，不能当作原炼金维度的再次检查结果。

| 检查 | 结果 | 覆盖内容 |
| --- | --- | --- |
| JUnit | 共 13/13 通过，其中云域新增 5 项 | 24 群系覆盖、负坐标、种子稳定性、高度与悬层边界、洞窟、八个形态家族和独立配色；保留原有 8 项检查 |
| GameTest | 共 13/13 通过，其中云域新增 3 项 | 26 群系注册、空刷怪表、没有入口物品、实际生成区块无矿物、洞窟植物、地心层与聚落门口/地板/屋顶；保留原有 10 项检查 |
| 普通客户端 | 6/6 场景、12/12 检查通过 | 卷曲岩层、悬浮长带、天门与魔鲸、中世纪聚落、倒生根窟、地心环境 |
| Complementary Reimagined r5.9.3 | 6/6 场景、12/12 检查通过 | Iris 1.11.4 与 Sodium 0.9.2 实际启用；六个场景分别留存截图 |
| Photon Shader v1.3b | 6/6 场景、12/12 检查通过 | Iris 1.11.4 与 Sodium 0.9.2 实际启用；六个场景分别留存截图 |

云域实机截图位于 `docs/screenshots/cloudsea/`，概念图另存 `docs/concepts/cloudsea-direction.png`。当前云团、天空鲸、程序式聚落与植物均为首版美术；它们没有达到概念图的细节水平。魔鲸是天空几何现象，不是生物实体；阴影为地面近似投影。光影包自己的云层和色调可能改变天门背景，没有声称所有光影包都具有相同观感。没有新增矿物、生物实体、Boss、战利品或维度入口；聚落暂不提供 `/locate structure`。

复现云域客户端检查：

```text
./gradlew runClient -PlogosJar=/absolute/path/logos-0.5.1.jar -PldTest=group:cloudsea -PldTestHeadless -PldTestWindow=1280x720
```

## 已完成

| 检查 | 结果 | 覆盖内容 |
| --- | --- | --- |
| JUnit | 8/8 通过 | 20,000 格重复布局、区域比例和顺序、负坐标、种子变化、空岛平台、周期、雾区边界 |
| GameTest | 10/10 通过 | 注册兼容、平台留空、禁刷怪、自有材料、独立时间、往返传送、天然材料换相、流体流动与装桶、各岛 Boss 状态持久化、炼金釜碰撞/返回/冷却 |
| 普通客户端 | 8/8 场景、19/19 检查通过 | 四区、中心空岛、倒悬蒸馏瓶、四阶段天空、岛下影雾及解除 |
| Complementary Reimagined r5.9.3 | 8/8 场景、19/19 检查通过 | 实际启用 Iris 1.11.4、Sodium 0.9.2；天空星盘和影子可见 |
| Photon Shader v1.3b | 8/8 场景、19/19 检查通过 | 实际启用 Iris 1.11.4、Sodium 0.9.2；天空与影雾场景完成 |

客户端场景使用 LDLib2 的实际开发客户端；截图不是概念图。首版截图位于 `docs/screenshots/`。完整报告由检查生成在 `build/ldlib2-uitest/report.json`，世界检查报告在 `build/gametest-results.xml`。

新增的 `crucible_cauldron` 场景另外在普通渲染、Complementary Reimagined r5.9.3、Photon Shader v1.3b 下各通过 1/1 场景、2/2 检查：先实际站在釜沿，再让生存模式玩家从釜口上方自由落入，确认客户端与服务端都抵达新维度。两种光影实际启用，均保留模型、液面与传送；GameTest 还检查了液面与釜沿的碰撞差异、延后传送、安全返回及冷却期间禁止重复触发。

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
