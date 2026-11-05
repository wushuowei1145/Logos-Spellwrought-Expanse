# 神狐

[灵狐樱野](FOX_SANCTUARY.md) 的居民。通用规则见 [NPC 随机外观方案](NPC_APPEARANCE.md)、[NPC 动画制作规范](NPC_ANIMATIONS.md)，本页只写这个种族自己的内容。

模型的素体与部件在仓库外的 `fox_model/divine_fox/`（与森林精灵同一套骨架与素体，膝关节按维护者的比例下移到 y 11.41），由那里的合并脚本合并、导出，再由 `tools/divine_fox_assets.py` 导入本模组。

## 1. 范围

- **外观**：生成时按零件表随机抽一次（`data/logos_dimension/npc_races/divine_fox.json`，即模型目录 `build/parts.json`），存进存档。共 37 个零件：五种发型、三种头饰、五种狐耳、三种尾巴（静止时就自然弯曲：尾根向后下方伸出，中段下垂，尾尖上翘并略偏向一侧；三尾向两侧散开）、两种眉毛、两种眼睛、两种嘴、狐纹（只有一成神狐有：零件表槽位的 `chance` 为 0.1），以及十四套衣装：巫女、日常小袖、樱花浴衣、弓道、振袖、神乐礼装六套长装，和朱白巫女短和服、紫绛金边短和服、夜紫玫瑰短浴衣、米白樱花短浴衣四款短装（每款短袜、过膝袜各一版，袜子与木屐随衣装一起换）。
- **行为**：闲逛、看人、四处张望；主动攻击附近的敌对怪物，对玩家中立（第 6 节）。重伤时逃跑。右键打招呼。
- **生成**：在神狐聚落 `logos_dimension:fox_sanctuary` 的街上，每处至多 20 只，死去后慢慢补回（[FOX_SANCTUARY.md](FOX_SANCTUARY.md)）；刷怪蛋也可以召唤。
- **还没有**：神狐打刀的 NPC 加招（居合、回旋斩等，[UCHIGATANA.md](UCHIGATANA.md) 第 4.2 节）、横扫游脉的狐火特效、剧情。

## 2. 动画

动画文件 `geckolib/animations/entity/divine_fox.animation.json` 由 `tools/divine_fox_assets.py` 拼成：

| 动画 | 来源 |
| --- | --- |
| `idle` | 素体自带的待机（上身、头、手臂、耳朵的小幅呼吸） |
| `walk`、`jump`、`attacked`、`ladder_*`、`swim_stand`、`swing_hand` | 借用森林精灵文件里已换算好的车万女仆动画（[CREDITS.md](../CREDITS.md) 登记），骨架相同 |
| `hold_mainhand:bow`、`use_mainhand:bow`、`greet`、`refuse` | 借用森林精灵自绘的持弓、拉弓、行礼、拒绝 |
| `katana_draw` … `katana_sheathe` | 神狐打刀的十个动作（拔刀、待机、四段斩、格挡、弹刀、突刺、收刀），与玩家共用同一份编排，由 `tools/uchigatana_moves.py` 烘焙到神狐骨架（刀按 1.6 倍），写在模型目录 `Animation/katana/` |
| `pre_parallel3` | 佩刀：刀与鞘停在左腰（收刀动作的最后一帧）。打刀动作播放时由它接管 |
| `death` | 维护者选定的「侧倒后面朝地面、尾巴先僵直后松垂」版本，1.55 秒，停在末帧。按全部零件生成，每套衣装的裙片、垂袖与飘带倒地时都不穿进地面；导入时去掉线性插值就能还原的中间帧（误差 < 0.001） |
| `pre_parallel0` | 眨眼：每种眼睛零件自己的骨骼。眼球与瞳孔向下眼缘上方 0.4 处收拢，主要是上眼皮往下合；睫毛不缩放，上睫毛随上眼皮下移、下睫毛随下眼皮上移；睁开后高光左右抖一下。5.3 秒循环里一次单眨、一次连眨 |
| `pre_parallel1` | 耳朵抖动：每种狐耳零件自己的根骨骼（`Left_ear` / `Right_ear` 的子骨骼）短促抖一下，7 秒循环里左右耳错开 |
| `pre_parallel2` | 尾巴轻摆：`Tail` 左右 ±4°，各节尾巴依次延迟 0.12 秒、再各加从尾根 ±1.2° 到尾尖 ±3° 的摆动，一道柔和的波顺着尾巴传到尾尖；外半段在左右摆到头时微微上扬。2.8 秒一个来回 |

前三个叠加层有就一直循环（`NpcAnimations` 的 `pre_parallel_*` 控制器），在主状态之前播放，主状态动画用到同一骨骼时会盖过它们：

- 待机、走路、跳跃、受击、游泳、挥手都不碰眼睛零件、耳朵零件根骨骼和尾巴，所以这些状态下三层一直有效；待机自己的耳朵呼吸挂在父骨骼上，和抖动叠在一起。
- 死亡动画接管整条尾巴，导入时再给眨眼与抖耳两层用到的每个骨骼通道补一个静止帧，倒下后不再眨眼、抖耳、摇尾巴。

`DivineFoxAssetsTest` 核对这两条，以及叠加层首尾一致、每根动画骨骼都在模型里。

叠加层的制作脚本是模型目录的 `tools/create_ambient_loops.py`（Blockbench 工程与预览在 `Animation/ambient/`）。

## 3. 表情

五种表情：`expression_teasing` 调侃、`expression_kitsune` 狐狸眯眼、`expression_happy` 开心、`expression_surprised` 惊讶、`expression_angry` 愤怒。每种是一组眼睛加一张嘴，由模型目录 `race.json` 的 `expressions` 登记，合并时挂到 `Head` 下以表情 id 命名的骨骼里，零件表 `expressions` 写明它替换 `Eyelid`（常态眼、瞳孔与睫毛）和 `Mouth`，眉毛保留。

表情不参与随机外观：神狐不会生来就是一张生气或惊讶的脸。行为换表情（`DivineFox#npcExpression`）：

| 情况 | 表情 |
| --- | --- |
| 有战斗目标 | 愤怒 |
| 重伤逃跑 | 惊讶 |
| 缠上狐火的那两秒 | 狐狸眯眼 |
| 亲手砍倒目标后三秒 | 调侃 |
| 被玩家搭话后两秒 | 开心 |

剧情用 `DivineFox#setExpression` 指定的表情优先于行为，传空交还给行为（不存档）。渲染器显示表情骨骼、隐藏被替换的常态五官。表情眼睛的睫毛是方块，没有眨眼骨骼，表情期间不眨眼。

## 4. 死亡

与森林精灵相同的节奏：最后一击先播受击，再播死亡动画，共 60 刻后消失（原版是 20 刻）；倒下后朝向固定（`NpcDeathPose`）。不染红，不按原版侧倒。消失时用原版白烟，以后可以换成狐火之类的专属效果。死亡不掉落刀、弓和铁甲。

## 5. 装备与防御

- **打刀**：每只神狐都有一把，刀装随机（墨漆、朱绳、靛青、拭漆四套，即 `UchigatanaItem` 四件物品）。刀与鞘是模型里的 `Weapon` / `Sheath` 骨骼，由打刀动作驱动，手里的刀不再按物品画一遍。平时收起不显示；战斗、受伤与之后五秒的警戒期才连刀带鞘出现在左腰。穿神乐礼装（`outfit_kagura`）的神狐总是佩着刀。
- **弓**：约四分之一的神狐还背着一张原版弓（无限箭），平时看不见，用的时候拿出来，刀回到腰间。
- **铁甲**：一整套，模型不画盔甲所以看不见，死亡不掉落。生命值 20。
- **魔法减免**：同女巫，对原版魔法与间接魔法伤害减免 40%（`getDamageAfterMagicAbsorb`）。Logos 的奥秘伤害走的就是间接魔法；寒霜、雷霆、虚空也是，所以同样减免。
- **格挡**：弹射物 80%、近战 50% 的几率用刀格开，不掉血（绕过无敌的伤害、魔法、火焰、爆炸不能格挡）。格开弹射物时架刀；格开近战时弹刀，紧接一记突刺反击。

## 6. 战斗

- **目标**：附近 20 格内的敌对怪物；不惹中立生物（末影人、僵尸猪灵等），不挑战 Boss 与监守者，不打森林精灵、同伴和玩家的 Logos 使魔。对玩家中立：玩家打了神狐，这只和附近的神狐一起反击。僵尸和灾厄村民也像打村民一样打神狐（`ElfFactions`）。
- **打刀**（`FoxKatanaGoal`）：决定攻击后先拔刀；目标在 3.5–10 格、看得见时一个箭步冲过去（水平速度按距离给，最快约 0.7 格/刻），够得着就连砍。出招、连段、接触时刻全由与玩家共用的招式状态机（`MoveRunner` + `combat/movesets/uchigatana.json`）决定，客户端的 `combat` 控制器播放对应的 `katana_*` 动作。每刀砍身前 75° 扇形 3 格内的目标与敌人。战斗结束、警戒期过后播收刀动作。
- **弓**（`FoxBowGoal`）：背弓的神狐在目标是苦力怕、走路到不了（在天上、隔着水或悬崖）或远于 16 格时换弓，保持 6–12 格拉满再放；目标重新走得到又不远时收弓拔刀。没有弓的神狐不去追苦力怕。苦力怕像躲猫一样躲神狐（6 格内逃开）。
- **狐火缠绕**（`FoxFireWrapGoal`）：遇上生命值超过 50 的敌人时，在安全的间隙（离目标 4 格以上、一秒内没挨打）停一下，用自己的魔力（200 点，每秒恢复 2 点）放 Logos 术式「狐火缠绕」（100 魔力，`LogosMagicBridge.FOX_FIRE_WRAP`）：60 秒内命中附加 3 点奥秘伤害并点燃 1 秒，挥刀（生物的挥砍以命中计）放出 120° 的横扫游脉，再加 5 点奥秘伤害。缠绕结束时这一仗还没打完，就在下一个安全间隙再缠一次。用弓时一样，命中的箭也带着狐火。
  - 外观：不用 Logos 按奥秘元素画的缠绕，改成 Logos 的物品表面火焰（`/alchemy aura fire`）把色相转半圈，成为幽蓝的鬼火，画在拔出的刀身和手里的弓上（`DivineFoxRenderer`）。横扫游脉的特效还没做，暂时是 Logos 默认的样子，以后回来换。
- **逃跑**（`FoxFleeGoal`）：生命不超过三成时背离打伤自己的敌人跑开，直到回到六成、拉开 20 格或跑满 10 秒；逃跑时不出刀不放箭，仍会格挡。脱离战斗五秒后每两秒回一点生命。

## 7. 语音

维护者转好的 ogg（`声音素材/神狐女/shenhu_female_ogg`），按文件名方括号里的标签归类：

| 事件 | 素材标签 | 什么时候 |
| --- | --- | --- |
| `ambient` | 疑惑、肯定 | 闲时 |
| `greet` | 高兴、肯定、接受 | 被玩家搭话 |
| `yes` | 肯定 | （留给剧情） |
| `no` | 不满 | 战斗中被搭话 |
| `alert` | 不满 | 发现敌人，一声「哼」 |
| `attack` | 发力 | 突进、放箭 |
| `cast` | 接受 | 缠上狐火 |
| `hurt` | 受到攻击 | 受伤 |
| `death` | 死亡 | 死亡 |

## 8. 更新资源

模型目录重新合并或改了动画之后：

```text
uv run --with numpy --with pillow python tools/uchigatana_moves.py "<Minecraft MOD 目录>" "<输出目录>" none   # 改了打刀编排时：重新烘焙神狐的打刀动作
python tools/divine_fox_assets.py [--source <fox_model/divine_fox 目录>] [--voices <ogg 目录>]
./gradlew test --tests dev.logos.dimension.DivineFoxAssetsTest -PlogosJar=/absolute/path/logos-0.6.0.jar
```

狐火缠绕用到 Logos 的魔力缠绕（`imbue`）与游脉（`vein`）形态：需要包含它们的 Logos 版本（2026-11-04 之后的 main），旧版本里这项法术编译不过，神狐只是不缠狐火。

实际客户端预览（白瓷台上五只不同外观的神狐，正面、背面、脸部近景、死亡与五种表情；另有八只穿四款短装的神狐，左四只短袜、右四只过膝袜）：

```text
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=npc_divine_fox_lineup,npc_divine_fox_back,npc_divine_fox_face,npc_divine_fox_death,npc_divine_fox_expressions,npc_divine_fox_short_outfits" -PldTestHeadless -PldTestWindow=1280x720
```

战斗与聚落居民（`FoxCombatViews`、`FoxResidentsView`）：佩刀与收刀、拔刀与连招、刀身与弓上的狐火（`npc_divine_fox_katana`）；真实 AI 对 80 血的尸壳（`npc_divine_fox_fight`）；格挡几率、魔法减免、逃跑（`npc_divine_fox_defense`）；背弓射苦力怕（`npc_divine_fox_bow`）；聚落居民数与补回（`fox_sanctuary_residents`，要生成聚落，耗时几分钟）：

```text
./gradlew --offline runClient -PlogosJar=/absolute/path/logos-0.6.0.jar "-PldTest=npc_divine_fox_katana,npc_divine_fox_fight,npc_divine_fox_defense,npc_divine_fox_bow" -PldTestHeadless -PldTestWindow=1280x720
```
