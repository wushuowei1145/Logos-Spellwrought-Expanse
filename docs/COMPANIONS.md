# 同伴：有哪些系统，做下一位照着做

白梅是第一位同伴，精灵公主是第二位。这份文档把同伴的系统一项项列出来，并说明每项现在是不是公共的，下一位同伴照着做。
白梅自己的设定、数值和每印的机制见 [白梅同伴](SHIRAUME_COMPANION.md)，精灵公主的见 [精灵公主](ELF_PRINCESS_COMPANION.md)，琥珀的见 [琥珀](KOHAKU_COMPANION.md)，拉塞塔的见 [拉塞塔](LACERTA_COMPANION.md)；方向见 [二游式角色](SHIRAUME_COMPANION.md#1-核心做角色不做定制)：性格鲜明、羁绊随时间长、有自己的本事，不做女仆式的定制。

## 1. 现状（2026-11-18，精灵公主加入时）

做精灵公主之前把第 4 节的抽取做了：

- **公共**：召唤收回休息、契（每位一份）、羁绊和契印的规矩、数值成长和继承、饱腹和吃东西、包袱、心智的框架和通用行为、表情和小动作的播放、
  台词字幕、对话、角色档案、对话界面和角色面板、接 Logos、防困。代码按「同伴」写：基类 `CompanionEntity`、每位一份定义 `CompanionDef`。
- **每位自己写**：信物、加入方式、打法、生活、挑哪个表情和小动作（`ShiraumeMood` / `PrincessMood`）、心智里她自己的行为（`ShiraumeMind` / `PrincessMind`）、
  对话的内容（`ShiraumeTalk` / `PrincessTalk`）、面板样式。
- **白梅独有**：流火、狐火、狐火灯、温泉、摘浆果、怕狗、截箭、处决、立契过场、两套装束、形代替身。
- **精灵公主独有**：世界树之弓的锁定和散射、生命涌动（治疗、护盾、缠绕、荆棘、万分之一）、撤退（代替倒下）、袋装世界树。
- **琥珀独有**（[琥珀](KOHAKU_COMPANION.md)，分五步做，第一步已进游戏）：长杖和换杖（`KohakuStaff`）、魔力（她是 Logos 的 `ManaVessel`，回复由附属结算）、
  生命单独的继承比例和不继承护甲（`CompanionDef.Growth` 的 `healthRatio`、`inheritsArmor`）、琥珀护膜、她自己施放的载页（`KohakuSpells`）、哈气和幻翼；
  之后是按意图挑页（含你给她的页）、凝辉、猫步、凑过来坐下、雨伞、睡姿和醒来的礼物、琥珀相伴。
- **拉塞塔独有**（[拉塞塔](LACERTA_COMPANION.md) 第 17 节，接入方案分五个阶段，阶段 A 人形核心已进游戏）：誓约逆鳞（`DragonScaleItem`）、
  不封顶的分段继承和超出原版上限的护甲、魔咒折成的减伤（`LacertaStats`）、炽焰长剑（`LacertaBlade`）和按处境挑招、防复读（`LacertaMind`）、
  赤龙威压（嘲讽）、掌心赤焰（龙炎弹）、龙眠、收展翅、展翼滑翔、四档守夜（`LacertaRest`）。
- 角色面板的数值页按档案给的顺序画（`CompanionSheet.stats`）：白梅生命、攻击、护甲、韧性；精灵公主把攻击换成法术强度；琥珀生命、法术强度、魔力、护甲；
  拉塞塔同白梅（攻击含剑的 8 点）。

还没做的：命令只有白梅那一组是老写法（精灵公主的在 `princess` 下面）。

## 2. 系统一览

状态：**公共** = 代码已经通用；**待抽** = 设计通用，代码只有白梅；**独有** = 每位同伴自己做。

| # | 系统 | 做什么 | 状态 | 代码 |
|---|---|---|---|---|
| 1 | 召唤、收回、休息 | 用信物召唤、收回；倒下后歇一段时间才能再叫；主人下线、死亡、换维度时悄悄收起，回来后自动叫回 | **公共** | `Companions`（`summon`、`dismiss`、`stow`、`fell`，都按 `CompanionDef`）；歇多久在定义里 |
| 2 | 信物 | 写着主人名字的（绑定）召唤用；空白的右键她盖一枚契印，还没立契时右键用它就写上名字、立契、叫她出来；丢了找她补 | 独有（每位一种，用法照这个） | 白梅 `KatashiroItem`（丢了拿纸）；精灵公主 `BaggedWorldTreeItem`（丢了拿树苗）；拉塞塔 `DragonScaleItem`（丢了在对话里说，或拿她最爱吃的给她） |
| 3 | 加入方式 | 怎么成为同伴 | 独有 | 白梅：打倒狐形白梅后在封印之间立契，`ShiraumePact` |
| 4 | 契 | 存在主人身上：立契没有、在外面的是谁、休息到哪、要不要自动叫回、契印、好感、饱腹、每天的次数、行动策略、攻击玩家 | **公共**（每位一份附件 `<同伴>_bond`，白梅的还是 `shiraume_bond`） | `Companions.Bond`、`CompanionDef.bond` |
| 5 | 羁绊 | 五段：生疏 → 熟悉 → 亲近 → 信赖 → 羁绊。一起吃饭、摸头、泡温泉、挡死都加好感，同一件事一天里头几次加得多、之后很少，不能刷 | 规矩公共（纯函数），数是白梅的 | `CompanionBond` |
| 6 | 契印 | 重复获得：每多一枚，继承多一点、她自己长一点，第 n 枚解锁第 n 个机制。每位同伴给它起名字（白梅叫「尾之契」） | 计数和公式公共，机制独有 | `CompanionBond.unlocked`、`CompanionStats.ratio` |
| 7 | 数值 | 本身（基础 + 契印成长）+ 羁绊 + 继承。只继承长期属性（装备、基础属性、盔甲上的保护类魔咒，不算药水）；攻击取近战和法强折算后高的那个；用法术打的同伴另有法术强度；在场时 5 秒刷新一次，面板开着时每秒刷新 | **公共**（数在每位的 `CompanionDef.Growth`；要另算的同伴覆盖 `CompanionEntity.statParts`、`protectionRatio`、`reduceDamage`，拉塞塔就是这样） | `CompanionStats`；保护魔咒在 `Companions.onProtect` |
| 8 | 饱腹、吃东西 | 饱腹随时间降，饿了先吃包袱里的；喂她吃的效果同玩家吃，回的血 = 饱食度 + 饱和度；有喜欢的、不吃的 | **公共**（口味标签 `<同伴>_favorite_food` / `_refused_food`） | `CompanionFood`；吃的动作在 `CompanionEntity` |
| 9 | 包袱 | 她替你背一个小箱子，饿了从里面拿吃的，捡的东西也放进去；从对话里打开 | **公共**（每位一份附件 `<同伴>_pouch`） | `CompanionPouch`、`CompanionDef.pouch` |
| 10 | 心智 | 效用 AI：每刻先过反射层（掉队闪回、掉岩浆、防困，加她自己的），每 10 刻给各种行为打分，挑最高的做（第 2.1 节） | **公共**框架和通用行为；她自己的在子类 | `CompanionMind`（`ShiraumeMind`、`PrincessMind`）、`OwnerWatch` |
| 11 | 行动策略、攻击玩家 | 随机应变、优先远程、优先近战、不参与攻击；攻击玩家开关（第 3.4 节） | 菜单和存储公共，「怎么打」每位自己写 | `CompanionTactic`；`Bond.tactic`、`Bond.pvp`；`CompanionMind` |
| 12 | 护主 | 截下射向主人的弹射物，再去收拾射手；第一印替主人挡死（眼前转出信物，同不死图腾） | 弹道判断公共，挡死用的是形代 | `CompanionGuard` |
| 13 | 防困 | 连着五次闪不回主人身边（矿车、船、座位）就回信物里 | **公共** | `CompanionMind.stuckInSeat` |
| 14 | 表情、小动作 | 每半秒按处境挑一种表情（`face_*`），遇事插一个小动作（`act_*`） | 播放公共，挑哪一个每位自己写；主控制器换状态的过渡刻数每位可以自己定（`NpcAnimated.npcMainTransition`，默认 2，拉塞塔 5） | `CompanionMood`（`ShiraumeMood`、`PrincessMood`） |
| 15 | 台词字幕 | 她说话：名字 + 一句，屏幕下方字幕（名字取 `talk.logos_dimension.<同伴>.name`，颜色取她的主色） | **公共** | `CompanionLines.say(她, 主人, 键)`、`client/companion/CompanionLinesHud` |
| 16 | 对话 | 潜行右键打开；一人同时只和一位说话；走开八格、来了敌人就断；看包袱时先放下，关上回来 | **公共** | `CompanionTalk`（`Talker`）、`TalkMenu`；白梅的在 `ShiraumeTalk` |
| 17 | 角色档案 | 对话和面板显示的一切：数值、能力、契印、羁绊、饱腹、策略、往事 | **公共** | `CompanionSheet` |
| 18 | 对话界面、角色面板 | 打字机文字、右边一列选项；面板三页（属性、契印 · 能力、往事），左边她本人可拖动转身 | **公共**（每位注册一份样式） | `client/companion/CompanionTalkScreen`、`CompanionPanelScreen`、`CompanionUi`、`CompanionStyle` |
| 19 | 接 Logos | 她是 Logos 的长期同伴：主人用 `sic` 点名、`goto` 派差事、`dismiss` 收回 | **公共** | `LogosCompanionBridge` |
| 20 | 战斗 | 怎么打 | 独有 | 白梅：狐火弹（`shiraume/FoxfireBolt`）、流火（`CompanionBlade`，招式文件 `shiraume_companion`）、缠火、处决；精灵公主：世界树之弓（`PrincessBow`）、生命涌动（`PrincessMagic`）；拉塞塔：炽焰长剑（`LacertaBlade`，招式文件 `lacerta_companion`，由 `LacertaMind` 挑招）、赤龙威压、掌心赤焰（`LacertaFlame`） |
| 21 | 生活 | 日常小事 | 独有 | 白梅：狐火灯（`CompanionLight`）、温泉、摘浆果、怕狗、装束（`CompanionOutfit`）；琥珀：四套衣服，对话里换、记在你身上（`KohakuOutfit`）；拉塞塔：四档守夜和坐下（`LacertaRest`）、收展翅（翅膀控制器排在 NPC 控制器之后） |
| 22 | 命令 | `/alchemy companion …`：召唤、收回、看数、清休息、盖契印、设饱腹、换装 | 白梅的在最上一层，精灵公主的在 `princess` 下面，拉塞塔的在 `lacerta` 下面 | `CompanionCommands` |
| 23 | 友人帐 | 按键（默认 K）翻开：所有同伴一人一页，立过契的画本人，没立契的是黑窗；在里面叫出来、收回（第 7 节） | **公共**（`Companions.ALL` 里的都在） | `CompanionBook`、`client/companion/CompanionBookClient`、`CompanionBookScreen` |

### 2.1 心智里的行为

| 行为 | 做什么 | 通用还是白梅的 |
|---|---|---|
| FOLLOW 跟随 | 离主人远了跟上，很远就跑 | 通用 |
| STAY 留在原地 | 主人让她等 | 通用 |
| TALK 说话 | 站住，转过来看着主人 | 通用 |
| REST 休息 | 主人睡觉、挂机时陪着打盹 | 通用（蜷着睡的姿势是她的） |
| IDLE 闲着 | 看着主人，偶尔四下看看 | 通用 |
| ERRAND 差事 | Logos `goto` 派她去的地方 | 通用 |
| SCAVENGE 拾荒 | 主人身边刚掉下来的东西（敌人掉的）叼回包袱；主人刚死在附近时不碰 | 通用 |
| ENGAGE 迎战 | 按行动策略打要对付的那个 | 结构通用，怎么打是她的 |
| COVER 护主 | 主人血少时站到主人和敌人之间 | 通用 |
| RETREAT 后撤 | 她血少了退到主人身后；被贴着先守，再闪开 | 通用 |
| EXECUTE 处决 | 狐火印爆开后接处决 | 白梅 |
| KINDLE 缠火 | 给主人的手缠上狐火 | 白梅 |
| FORAGE 觅食 | 饿了吃包袱里的、去摘浆果 | 吃包袱里的通用，摘浆果是她的 |
| SOAK 泡温泉 | 主人泡温泉她也泡 | 白梅 |
| HIDE 躲狗 | 狐狸怕狗 | 白梅 |

要对付谁（`pickFoe`）是通用的：`sic` 点名的最先，再是朝主人放冷箭的射手、打主人的、主人在打的、打她的，最后是冲着主人或她来的敌对生物。

## 3. 公共部分怎么用

### 3.1 对话

1. 同伴实体实现 `CompanionTalk.Talker`：`talkId()`（同伴 ID，例如 `shiraume`）、`canTalk`、`talkSheet`、`talkGreeting`、`talkPick`、`talkStarted`、`talkEnded`，可选 `talkInterrupted`、`talkBack`。
2. 登记选项树：`TalkMenu.register(id, TalkMenu.standard(话题, 有没有包袱))`。通用的顶层是：聊天（她自己的话题，后面自动加「返回」）、包袱、作战策略（跟上 / 留在原地 / 修改行动策略 / 攻击玩家）、详细数据、一会再聊。
3. 在 `mobInteract` 里潜行右键调 `CompanionTalk.begin(player, this)`。
4. 选项的处理写在她自己的类里（白梅的是 `ShiraumeTalk.pick`）：跟上、留在原地、策略、攻击玩家这几个通用选项每位都要处理，回话用自己的台词。

选项名先找 `talk.logos_dimension.<id>.option.<选项>`（她自己的说法），没有再用 `talk.logos_dimension.option.<选项>`（通用的）。

### 3.2 角色档案

`CompanionSheet` 由 `talkSheet` 给，说着话时每秒重发：

- `stats`：每项 `Stat(id, value, base, bond, current)`。`base` 是她自己的，`bond` 是羁绊给的，其余算继承；`current` 不小于 0 时显示成「current / value」（生命）。面板写成「本身 ＋羁绊 ＋继承」。
- `skills`：每项 `Skill(id, kind, seal, unlocked, args)`。`kind` 分普通攻击、特殊、被动；`seal` 是第几枚契印解锁（0 是本来就会）；没解锁的显示「？？？」和第几枚。说明里的数从 `args` 填，以 `#` 开头的参数本身也是语言键。
- 契印、羁绊段和进度、饱腹、留在原地、行动策略、攻击玩家、往事（想起几段、一共几段）、继承比例。

### 3.3 样式

`CompanionStyle.register(id, new CompanionStyle(…))`：

- 九个颜色：主色、深主色、金线、冷色、正文、次要文字、面板底色上下、卡片底。
- 纹（`Crest`）：名字旁一个小的、面板里身后一个大的。白梅是梅鉢，用 `CompanionUi` 的圆一格一格填出来，不用贴图。
- 展示（`Showcase`）：造一个不放进世界的她、摆好姿势（每次打开面板造一次），身子侧几度、头转回来几成、镜头俯仰、占展示框几成高、往上挪多少。

`CompanionUi` 有通用的画法：圆、圆环、椭圆、光晕、菱形、渐隐的线、进度条、能力图标，还有中日文逐字断行（标点不落到行首）。

### 3.4 行动策略和攻击玩家

存在契里，收回再叫出来也不变。每位同伴都要按这个意思实现：

| 策略 | 意思 |
|---|---|
| 随机应变（默认） | 她自己最拿手的打法 |
| 优先远程 | 能远就远，贴到跟前才近战，砍完拉开 |
| 优先近战 | 远远就走过去近战，够不着的才用远程 |
| 不参与攻击 | 不打，躲到主人背后；护主（截箭、挡死）照常；`sic` 点名的还是打 |

攻击玩家（开关，默认关）：开着时只打打主人的、主人在打的、打她的玩家，不会去找路过的人；主人自己、创造和旁观的不打；服务器关了 PvP、和主人同队不许互伤时也不打；不处决玩家。判断写在 `canHarm` 里。

### 3.5 数值的规矩

| | 规矩 | 白梅的数 |
|---|---|---|
| 继承比例 | 起点 + 每枚契印一步，最多全部 | 三成起，每枚 +8%，第九枚全部 |
| 她自己的 | 基础 + 每枚契印成长，到满契为止 | 生命 40 / 护甲 4 / 韧性 0 起，每枚 +5 / +1 / +0.5 |
| 羁绊 | 每升一段加生命和攻击 | +4 生命、+0.5 攻击 |
| 只继承长期属性 | 装备、基础属性、保护类魔咒；药水、信标不算 | |
| 攻击 | 近战攻击和法强折算后取高的那个（法强 1 折 6 点） | |
| 吃东西 | 效果同玩家，回的血 = 饱食度 + 饱和度 | |

## 4. 抽出来的公共部分（精灵公主加入时做的）

1. **同伴基类** `CompanionEntity`：主人、契、数值、表情和台词的接口、对话、吃东西、摸头、盖契印、信物补做、召唤和收回、闪回和防困。
   子类给定义（`def()`）、表情（`createMood`）、心智（`createMind`）、入场长度、现身和散去的样子、信物，按需改钩子
   （`tickSpecial`、`tickActions`、`interactSpecial`、`immune`、`blocked`、`statsApplied`……）。
2. **每位同伴一份定义** `CompanionDef`：ID、实体、契和包袱的附件、数值成长（`Growth`）、歇多久、契印几枚。`Companions.ALL` 列着所有同伴。
3. **契按同伴分开**：没有用「同伴 ID → 契」的表，而是每位一份附件 `<同伴>_bond`、`<同伴>_pouch`——白梅的就是原来的 `shiraume_bond`、
   `shiraume_pouch`，旧存档不用迁移。一个玩家可以和几位分别立契。
4. **同时在外面几位**：同一位同伴一人一个，不同的可以一起在外面，最多 4 位（Logos 的使魔上限 `maxFamiliars`，多叫一位时最早出来的那位回去）。
   这条规矩在 Logos 的 `Familiars.bindCompanion`（MagicFoxDemon/MinecraftLogosMod#467；旧的 Logos 是一人一位，叫出另一位时前一位被送回去）。
5. **心智拆开**：通用的在 `CompanionMind`，白梅的在 `ShiraumeMind`，精灵公主的在 `PrincessMind`；行为种类 `Kind` 一张表列全，各自只打自己的分。
6. **台词前缀、口味、包袱标题** 按同伴 ID 走；`TalkMenu` 同一个 ID 只能登记一份（后登记的会报错，免得悄悄盖掉）。

## 5. 新同伴清单

1. **角色**：性格、来历（照世界设定）、加入方式、信物、契印的名字和每印的机制、往事。
2. **模型和动画**：GeckoLib；表情 `face_*`、小动作 `act_*`、入场、倒下、睡觉；战斗动作走共用的战斗骨架（`docs/COMBAT_WEAPON_WORKFLOW.md`），NPC 战斗数值放招式文件。
3. **实体**：接第 4 节的基类；给定义（数值、成长）；实现 `Talker`；心智里加她自己的战斗和生活行为；四种行动策略各是什么意思。
4. **文字**：台词 `line.logos_dimension.<id>.*`，对话和面板 `talk.logos_dimension.<id>.*`（`name`、`epithet`、`kana`、`seal_name`、`option.*`、`skill.<能力>.name/.icon/.desc`、`memory.*`），中英文都要有。
5. **样式**：`CompanionStyle` 的颜色、纹、展示姿势。
6. **测试**：单元测试查语言文件齐全、档案在网络上来回不走样；客户端场景至少有召唤、对话、面板、一种策略、她的独有能力；同一组 tag。
7. **文档**：她自己一份（结构照 `SHIRAUME_COMPANION.md`），这份文档第 2 节加一行她独有的东西。

## 6. 做同伴时要守的规矩

- **不显示状态文字**（维护者 2026-11-17 定的全项目规矩）：不用 actionbar、聊天栏、HUD 报状态或给提示，要么画面上看得出，要么她说出来（字幕带名字）。现在同伴里还有几处没改：
  - 跟上 / 留在原地（`ShiraumeCompanion.setStaying`）、给你缠上狐火、盖了第几枚契印、倒下后歇多久、困住回形代、学会狐火之言；
  - 形代的「她不认得这张形代」「不是你的」、召唤失败的原因（`KatashiroItem`）。
- **羁绊不靠刷**：同一件事一天里头几次加得多，之后很少。
- **只继承长期属性**，继承最多全部，不让同伴比主人强到主人只需要站着（第 3.5 节）。
- **不做定制**：不像车万女仆那样换皮肤、改性格；每位同伴是一个固定的角色。
- **主人永远打不到**：主人打她不疼（会闹别扭）；她也从不打主人和主人的宠物、使魔。

## 7. 友人帐

信物丢了以后只能用命令叫同伴，所以加了友人帐（维护者 2026-11-23 提的）。

- **翻开**：按键「友人帐」（「同伴」一栏，默认 K，可以改）。客户端问服务端要每一页（`CompanionBook.Ask`），回来了就翻开（`CompanionBook.State`）。
- **每一页**按 `Companions.ALL` 的顺序排成一排（放不下就换行）：
  - 立过契：画她本人的半身（同角色面板的展示 `CompanionStyle.Showcase`，放大到窗里只看得到头和肩；穿着她现在那一套），配色和纹照她的对话和面板；下面是名字、
    羁绊段（在外面时写「在你身边」），最底下一个按钮：召唤、收回，或者「歇着 · N 秒」（倒下、撤退后，按了不算）。
  - 没立契：一扇黑窗，名字是「？？？」。
- **召唤、收回**走的是信物同一套（`Companions.summon` / `dismiss`）：在外面就收回，不然叫出来；成了就合上友人帐，好看她出来。只在这里叫、不立契（立契还是靠各自的加入方式）。
- **新同伴**：加进 `Companions.ALL`、注册了 `CompanionStyle` 就自动有一页；画她时穿哪一套在 `CompanionBook.outfit` 里加一行。
