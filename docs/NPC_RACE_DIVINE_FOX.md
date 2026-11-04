# 神狐

[灵狐樱野](FOX_SANCTUARY.md) 的居民。通用规则见 [NPC 随机外观方案](NPC_APPEARANCE.md)、[NPC 动画制作规范](NPC_ANIMATIONS.md)，本页只写这个种族自己的内容。

模型的素体与部件在仓库外的 `fox_model/divine_fox/`（与森林精灵同一套骨架与素体，膝关节按维护者的比例下移到 y 11.41），由那里的合并脚本合并、导出，再由 `tools/divine_fox_assets.py` 导入本模组。

## 1. 第一版的范围

- **外观**：生成时按零件表随机抽一次（`data/logos_dimension/npc_races/divine_fox.json`，即模型目录 `build/parts.json`），存进存档。共 37 个零件：五种发型、三种头饰、五种狐耳、三种尾巴（静止时就自然弯曲：尾根向后下方伸出，中段下垂，尾尖上翘并略偏向一侧；三尾向两侧散开）、两种眉毛、两种眼睛、两种嘴、狐纹，以及十四套衣装：巫女、日常小袖、樱花浴衣、弓道、振袖、神乐礼装六套长装，和朱白巫女短和服、紫绛金边短和服、夜紫玫瑰短浴衣、米白樱花短浴衣四款短装（每款短袜、过膝袜各一版，袜子与木屐随衣装一起换）。
- **行为**：闲逛、看人、四处张望，受伤时逃开；不主动攻击。
- **还没有**：战斗（神狐打刀，见 [COMBAT_SKELETON.md](COMBAT_SKELETON.md)）、语音、剧情动作、聚落里的生成。刷怪蛋可以召唤。

## 2. 动画

动画文件 `geckolib/animations/entity/divine_fox.animation.json` 由 `tools/divine_fox_assets.py` 拼成：

| 动画 | 来源 |
| --- | --- |
| `idle` | 素体自带的待机（上身、头、手臂、耳朵的小幅呼吸） |
| `walk`、`jump`、`attacked`、`ladder_*`、`swim_stand`、`swing_hand` | 借用森林精灵文件里已换算好的车万女仆动画（[CREDITS.md](../CREDITS.md) 登记），骨架相同 |
| `death` | 维护者选定的「侧倒后面朝地面、尾巴先僵直后松垂」版本，1.55 秒，停在末帧。按全部零件生成，每套衣装的裙片、垂袖与飘带倒地时都不穿进地面；导入时去掉线性插值就能还原的中间帧（误差 < 0.001） |
| `pre_parallel0` | 眨眼：每种眼睛零件自己的骨骼。眼球与瞳孔向下眼缘上方 0.4 处收拢，主要是上眼皮往下合；睫毛不缩放，上睫毛随上眼皮下移、下睫毛随下眼皮上移；睁开后高光左右抖一下。5.3 秒循环里一次单眨、一次连眨 |
| `pre_parallel1` | 耳朵抖动：每种狐耳零件自己的根骨骼（`Left_ear` / `Right_ear` 的子骨骼）短促抖一下，7 秒循环里左右耳错开 |
| `pre_parallel2` | 尾巴轻摆：`Tail` 左右 ±4°，各节尾巴依次延迟 0.12 秒、再各加从尾根 ±1.2° 到尾尖 ±3° 的摆动，一道柔和的波顺着尾巴传到尾尖；外半段在左右摆到头时微微上扬。2.8 秒一个来回 |

三个叠加层有就一直循环（`NpcAnimations` 的 `pre_parallel_*` 控制器），在主状态之前播放，主状态动画用到同一骨骼时会盖过它们：

- 待机、走路、跳跃、受击、游泳、挥手都不碰眼睛零件、耳朵零件根骨骼和尾巴，所以这些状态下三层一直有效；待机自己的耳朵呼吸挂在父骨骼上，和抖动叠在一起。
- 死亡动画接管整条尾巴，导入时再给眨眼与抖耳两层用到的每个骨骼通道补一个静止帧，倒下后不再眨眼、抖耳、摇尾巴。

`DivineFoxAssetsTest` 核对这两条，以及叠加层首尾一致、每根动画骨骼都在模型里。

叠加层的制作脚本是模型目录的 `tools/create_ambient_loops.py`（Blockbench 工程与预览在 `Animation/ambient/`）。

## 3. 表情

五种表情：`expression_teasing` 调侃、`expression_kitsune` 狐狸眯眼、`expression_happy` 开心、`expression_surprised` 惊讶、`expression_angry` 愤怒。每种是一组眼睛加一张嘴，由模型目录 `race.json` 的 `expressions` 登记，合并时挂到 `Head` 下以表情 id 命名的骨骼里，零件表 `expressions` 写明它替换 `Eyelid`（常态眼、瞳孔与睫毛）和 `Mouth`，眉毛保留。

表情不参与随机外观：神狐不会生来就是一张生气或惊讶的脸。`DivineFox#setExpression` 临时换上一个（不存档），渲染器显示表情骨骼、隐藏被替换的常态五官；这一版还没有动作或剧情去调用它。表情眼睛的睫毛是方块，没有眨眼骨骼，表情期间不眨眼。

## 4. 死亡

与森林精灵相同的节奏：最后一击先播受击，再播死亡动画，共 60 刻后消失（原版是 20 刻）；倒下后朝向固定（`NpcDeathPose`）。不染红，不按原版侧倒。消失时用原版白烟，以后可以换成狐火之类的专属效果。

## 5. 更新资源

模型目录重新合并或改了动画之后：

```text
python tools/divine_fox_assets.py [--source <fox_model/divine_fox 目录>]
./gradlew test --tests dev.logos.dimension.DivineFoxAssetsTest -PlogosJar=/absolute/path/logos-0.6.0.jar
```

实际客户端预览（白瓷台上五只不同外观的神狐，正面、背面、脸部近景、死亡与五种表情；另有八只穿四款短装的神狐，左四只短袜、右四只过膝袜）：

```text
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=npc_divine_fox_lineup,npc_divine_fox_back,npc_divine_fox_face,npc_divine_fox_death,npc_divine_fox_expressions,npc_divine_fox_short_outfits" -PldTestHeadless -PldTestWindow=1280x720
```
