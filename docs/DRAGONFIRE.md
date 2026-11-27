# 龙炎

龙族的火，伤及灵魂。龙炎是奥秘伤害的子类型，和 [狐火](FOXFIRE.md) 一样走 Logos 的奥秘伤害。它没有原版火的那些弱点，打中会烧掉魔力，缠身的火很难灭。整套都在附属里，Logos 本体不用改。

## 1. 要点

- **结算同奥秘**：走 Logos 的 `Effects`，所以奥秘强化、奥秘抗性、法术抗性都照常算。
- **伤害类型 `logos_dimension:dragonfire`**：
  - 带的标签：`bypasses_armor`、`bypasses_enchantments`、`bypasses_wolf_armor`、`panic_causes`，另加 `c:is_magic`。
  - **不带 `is_fire`**：天生不怕火的生物（烈焰人、下界生物）、抗火药水、火焰保护都挡不住它。
  - **不带 `witch_resistant_to`**：女巫和神狐对魔法的减免都不算。
  - 护甲和保护类附魔都不减。抗性提升效果照常减。
  - 死亡消息是「X 被 Y 的龙炎焚尽了」。
- **颜色**：深绯红的外焰、熔金的焰心，取拉塞塔的绯金龙炎。
- **命中**：不放 Logos 的奥秘命中特效，换成一团龙炎色的火，同狐火。

## 2. 谁放龙炎

现在游戏里还没有生物放龙炎，以后的拉塞塔用下面这些入口：

| 入口 | 说明 |
|---|---|
| `Dragonfire.deal(攻击者, 目标, 点数)` | 生物打出的一下龙炎，打中后缠身。`deal(…, false)` 不点缠身，照样掉魔力 |
| `Dragonfire.wrap(手, 每下附带, 秒)` | 给手缠上龙炎：Logos 的普通缠绕，每下附带的那一下是龙炎 |
| `Dragonfire.burn(目标, 点火的人, 刻)` | 让目标龙炎缠身（往上叠） |
| 术式里的 `dragonfire` | 见第 4 节 |

## 3. 打中之后

数值都在 `DragonfireRules` 里。

- **伤及灵魂**：
  - 每打进去一点龙炎，烧掉目标一点魔力。
  - 有魔力的是玩家的魔力池、魔力生命体、精灵、神狐，和腐败掉魔力用的是同一个入口。
- **龙炎缠身**：很难灭掉的火。
  - 每打中一下往上叠 5 秒，最多叠到 15 秒。狐火缠身是续上时间，龙炎是往上加。
  - 每秒烧 1.5 点龙炎，再烧掉 2 点魔力，记在点火的人名下。
  - 不是原版的火：水、雨、细雪、抗火、牛奶都没用，也不点燃方块。
  - 燃着龙炎时点不上狐火。身上同时只燃一种火，龙炎压过狐火。
  - 缠身每秒烧的那一下不再附带缠身，也不放命中特效。
- **龙族**：实体标签 `logos_dimension:dragonfire_kin`，现在只有末影龙。
  - 受到的龙炎只剩四分之一。
  - 不会缠身，也不掉魔力。
  - 拉塞塔做进游戏时把她加进这个标签。
  - 末影龙本来只认玩家打的伤害，附属的生物打不疼它。

## 4. 术式里的 `dragonfire`

- **怎么写**：在写 `arcane` 的地方写 `dragonfire`，例如 `(damage target 3 dragonfire)`。编译器把它当奥秘读，类型、耗魔、外观推断都同奥秘，并记下这是一条龙炎术式。
- **整条术式都算龙炎**：
  - 它的奥秘伤害都是龙炎。
  - 施放时脚下那一圈、符文自己放的那一团、魔力缠绕在手里东西上的样子、游脉，都换成龙炎。
  - 一条术式里 `foxfire` 和 `dragonfire` 都写了，按龙炎算。
- **藏着，能学会**：
  - 没学会龙炎之言时，玩家在编纂台、书与笔、术式发射器、书灵里写它，都和写错一个词一样报错。编译缓存里有调试时编过的同一条也不行。
  - 学会以后，和别的元素一样认得，编纂台的卡片库里也多一张「龙炎」。
  - 龙炎是奥秘的一支，开着符文解锁时还要学会奥秘才写得上。
  - 学会狐火之言不等于学会龙炎之言，两个词分开记。
- **解锁的接口**：`Dragonfire.teach(玩家)`、`Dragonfire.knows(玩家)`、`Dragonfire.forget(玩家)`。
  - 学会的记录存在玩家身上，死了也记得。
  - 书灵拿到的「学会了什么」里，学会的人多一个 `element:dragonfire`。
  - 现在还没有哪里教。以后放在拉塞塔的契印或别的剧情里，调一下 `teach` 就行。
- **用得了的地方**：调试（`/logos cast`、`/alchemy dragonfire page`）、附属自己的生物、学会了的玩家。

## 5. 样子

借用狐火的画法，换成龙炎的颜色。全部是自己画的特效，不用原版粒子：

| 地方 | 样子 |
|---|---|
| 命中、施放、符文自己放的一团 | 同狐火，龙炎色 |
| 魔力缠绕 | 手里的东西燃着深绯红的表面火焰。Logos 的表面火焰只会整体转色相，橙色转出来是淡粉，所以由 `DragonfireSurfaceAuraMixin` 把每个颜色往红收、提饱和度 |
| 游脉、缠身的火星 | 自己画的火舌（Logos 物品表面火焰的那张火焰动画，下面一层半透明焰体压住颜色，上面一层叠加的火）和熔金火星；游脉的地面碎屑换成从地里迸出的火星 |
| 缠身 | 身上一圈龙炎色的火舌；烧着的是自己时，第一人称画面底边泛起龙炎色 |

没有新加贴图，用的都是 Logos 现成的特效贴图。

## 6. 调试命令

管理员命令：

```
/alchemy dragonfire page <wrap|sweep>     一张龙炎术式页：龙炎缠绕，或单独一道龙炎横扫游脉
/alchemy dragonfire hit <目标> [点数]      以自己的名义放一下龙炎
/alchemy dragonfire wrap <目标> [秒]       给目标的手缠上龙炎，每下附带 3 点
/alchemy dragonfire burn <目标>            让目标龙炎缠身（叠一次）
/alchemy dragonfire teach <玩家>           教会龙炎之言
/alchemy dragonfire forget <玩家>          忘掉龙炎之言
```

## 7. 实现

龙炎没有自己再写一套注入。它是狐火颜色表里的第三种 `FoxfireFlame.DRAGON`：

- 凡是按颜色走的，颜色是龙炎时就是龙炎：结算上下文、缠身、缠绕、游脉、命中、施法圈。
- 伤害类型由颜色决定（`FoxfireFlame.damage()`）。
- 打中之后的事归 `DragonfireRules`。

| 部分 | 文件 |
|---|---|
| 入口、藏着的词、解锁 | `dragonfire/Dragonfire.java`、`DragonfireAttachments.java` |
| 掉魔力、缠身、龙族 | `dragonfire/DragonfireRules.java` |
| 命令 | `dragonfire/DragonfireCommands.java` |
| 藏词（狐火、龙炎共用） | `magic/HiddenWord.java`、`mixin/HiddenWord*Mixin.java` |
| 借用的狐火部分 | `foxfire/FoxfireFlame.java`（颜色）、`FoxfireRules.java`（缠身每秒那一下）、`mixin/FoxfireEffectsMixin.java`（换来源、打中之后） |
| 客户端 | `client/foxfire/FoxfireClient.java`（龙炎的火舌和火星 `ember`、表面火焰的换色 `dragonRed`）、`FoxfireVeinVisual.java`、`mixin/DragonfireSurfaceAuraMixin.java` |
| 数据 | `damage_type/dragonfire.json`；原版和 `c` 的伤害标签；`tags/entity_type/dragonfire_kin.json`；死亡消息、卡片名 |

注入都是 `require = 0`。Logos 改了被注入的方法时注入不上，龙炎退回普通的奥秘伤害，不会崩。

## 8. 测试

`-PdimensionTests=logos_dimension:dragonfire_*`，11 个，每个单独一批：

- 和奥秘掉血一样，只是伤害类型不同；打中后缠身。
- 穿着保护 IV 钻石甲、喝了抗火的僵尸全额受龙炎，烈焰人也全额。
- 女巫、神狐全额受龙炎。
- 一半奥秘抗性只受一半。
- 打进去 4 点掉 4 点魔力，奥秘不掉。
- 缠身往上叠、叠到上限为止，狐火盖不掉龙炎缠身。
- 缠身 5 秒烧掉 7.5 点后熄灭，烧的是龙炎。
- 末影龙受到的是同样奥秘的四分之一，不缠身。
- 藏着的词：调试能用，没学会写不了，编译缓存绕不过，只会狐火写不了龙炎。
- 书与笔、书灵：没学会拒绝，学会了照常。
- 龙炎术式、龙炎缠绕打出的都是龙炎。

实机场景 `dragonfire_views`（`-PldTest=dragonfire_views`）：地上的施法者朝空地横扫（施法圈、游脉），打中靶子（命中、缠身），玩家手上的剑缠着龙炎和附带的一下，玩家自己龙炎缠身（画面底边）。

改了狐火和腐败共用的入口，回归跑 `foxfire_*`、`elven_blight_corruption_heals_its_own`、`longsword_ryuka_*` 和场景 `foxfire_views`、`companion_seals`。
