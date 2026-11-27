# 炽焰长剑（Crimson Flame Longsword）

以龙族少女骑士 **拉塞塔（Lacerta）** 为动作原型设计的二游动漫风烈焰大剑：傲岸拖剑待机、大幅前压蓄力扫斩、单手翼展亮相、腾空后仰蓄压后俯冲陨落重劈；并配备主动托脊横防、弹反追刺、突刺、旋风突进、扇形龙炎横扫技能与龙姬空降/十文字衍生技。

剑上烧的是 [龙炎](DRAGONFIRE.md)：这把剑打出的每一下都是龙炎，不是原版近战，也不是原版的火。

## 1. 美术交付检查

交付在仓库外 `art/crimson_flame_longsword/v01`（Blockbench 5.0 工程、80×96 贴图、`检查说明.md`），按 [工作流](COMBAT_WEAPON_WORKFLOW.md) 第 3 节查过：

| 查什么 | 结果 |
|---|---|
| 网格原点、刀尖方向、握点 | 原点在红皮握柄中心，`+Y` 指剑尖，`±X` 是双刃和护手翼 |
| 贴图尺寸 | 80×96，不是正方形；导入脚本按横纵分别归一化 UV |
| 物品模型 = 网格 + (8, 1, 8) | 导入脚本生成，全部顶点在原版 `[-16, 32]` 内；单元测试 `itemModelAndTexturesAlignWithCombatMesh` |
| 武器长度 | 全长约 34 单位（约 2.1 格），网格 `scale` 1.0 |
| 活动部件 | 没有，126 块都挂在 `Weapon` 下 |
| 缺什么 | 图标由导入脚本从网格渲染（32×32）；剑鞘没有，`withoutSheath()` |

## 2. 战斗数值与注册

- 物品：`logos_dimension:crimson_flame_longsword`（`CrimsonFlameLongswordItem` + `CrimsonFlameSkills`，战斗武器编号 `60`，`withCone(100).withoutSheath()`）。
- 属性：钻石级剑身，攻击力 `8`、攻速 `-2.5`（基准 `1.5` 次/秒）、交互距离 `+0.5` 格；自带无延迟的原版格挡组件（`BlocksAttacks`）。
- 创造模式物品栏放在「武器」的剑一组里，紧跟惊鸿；加进了 `minecraft:swords` 标签。

## 3. 伤害：龙炎

- **每一剑都是龙炎**：`strike` 不走原版 `player.attack`，换成 `Dragonfire.deal`，点数是原版近战会打的那么多（攻击力乘攻击蓄力），招式的 `damage` 倍数照常乘上（`LongswordCombat` 在受伤事件里乘）。所以护甲、保护附魔、抗火都挡不住，打进去多少点就烧掉目标多少魔力，龙族只受四分之一（见 [龙炎](DRAGONFIRE.md) 第 1、3 节）。
- **缠身**：连招前三式（`cleave_1`、`rend_2`、`ascend_3`）只打龙炎、不缠身，免得每一剑都把缠身叠满；带火的招式（四式、龙牙、突刺、旋风、十文字、横扫、坠星）打中点龙炎缠身（每下叠 5 秒，最多 15 秒）。
- **不是原版的火**：不点原版的火，不放原版的火焰、岩浆粒子；画面全是龙炎自己的（第 6 节）。
- **耐久**：照原版，每打中一个目标掉一点。
- **和原版近战不同的地方**（自己定的，试玩后可改）：没有暴击、横扫之锋、锋利、火焰附加这些附魔的效果，因为伤害不走原版近战。
- **战技的追加伤害**：横扫的每道游脉 10 点、坠星落点 12 点，都是龙炎并缠身；这两下不乘招式倍数。

## 4. 招式与战技表（`combat/movesets/crimson_flame_longsword.json`）

| 招式 | 触发 / 派生 | 时长 | 接触 | 取消 | 效果 / 说明 |
| --- | --- | --- | --- | --- | --- |
| `idle` | 默认待机 | 2.40s | — | — | **龙姬拖剑**：左手叉腰，右手自然垂落斜拖大剑于右后方 |
| `draw` | 切剑拔出 | 1.10s | — | 0.90s | **绯焰风车**：右肩后引出，体侧高速旋转剑花一圈后甩振落入待机 |
| `cleave_1` | 地面左键一式 | 0.84s | 0.28s | 0.60s | **一式 · 苍炎拖刀大横扫**：压低前倾深拉蓄力，绕腰扫出 215° 宽弧平斩（`cone=115`, `damage=1.0`） |
| `rend_2` | 二式 / 龙牙接续 | 0.84s | 0.30s | 0.60s | **二式 · 逆鳞回天斜撩**：顺腕翻刃自左下斜撩至高右上方，收势左手甩开单手定格亮相（`cone=115`, `damage=1.05`） |
| `ascend_3` | 三式 / 十字斩接续 | 0.88s | 0.34s | 0.64s | **三式 · 绯龙裂空升龙挑**：右膝外侧沉胯蓄力后爆发斜挑升空（`cone=95`, `damage=1.15`, `effect=launch`） |
| `cataclysm_4` | 四式 | 1.16s | 0.52s | 0.88s | **四式 · 天陨断界重劈**：双手举剑腾空滞空蓄压，随后猛然前压俯冲砸地重劈，龙炎缠身（`cone=75`, `damage=1.45`, `effect=slam`） |
| `guard` | 按住右键 | 2.00s | — | — | **托脊横防**：右腕持剑横斜护身、左掌实抵中段剑脊；开盾 `0–0.35s` 内挡击触发弹反；格挡中左键立即打出 `flame_sweep` |
| `guard_hit` | 格挡受击 | 0.34s | — | — | 常规格挡震颤卸力，回落 `guard` |
| `parry` | 开盾 `0–0.35s` 挡击 | 0.42s | — | 0.12s | **逆焰弹反**：右上斜崩弹开来袭（`effect=parry`），`0.12s` 起可左键接 `dragon_fang` |
| `dragon_fang` | 弹反后左键 | 0.66s | 0.18s | 0.44s | **破军龙牙**：弹反后的疾影低姿前倾单手弓步贯刺，龙炎缠身（`cone=55`, `damage=1.5`），可接回 `rend_2` |
| `crimson_thrust` | 地面潜行+左键 | 0.72s | 0.20s | 0.46s | **破晓炎枪 · 突刺**：第 2–5 刻高速前冲 `1.15` 格/刻，单手直刺，剑尖炸开一团龙炎并缠身（`cone=55`, `damage=1.25`），可接 `vortex_rush` |
| `vortex_rush` | 疾跑+左键 / 突刺接续 | 0.90s | 0.22s, 0.48s | 0.56s | **炽焰旋风斩 · 旋转突进**：第 2–10 刻持续前掠 `0.78` 格/刻，身边绕一圈龙炎，二段 360° 旋风大回环连斩（`cone=165`, `damage=0.85×2`），可接 `cross_rend` |
| `flame_sweep` | **格挡中按左键（技能）** | 0.96s | 0.36s | 0.68s | **红莲劫焰斩 · 技能横扫**：第 4–7 刻前踏并打出 240° 全弧（`cone=175`, `damage=1.35`），向前方 72° 扇区扫出 **五道贴地龙炎游脉**（射程 11 格，各 10 点龙炎并缠身，五道共享命中，一个目标只挨一道），可接 `cross_rend` |
| `skyfall_judgement` | 空中+左键（龙姬空战技） | 1.06s | 0.50s | 0.80s | **炽翼坠星破**：振翼跃升悬停倒持大剑后疾坠砸地（`cone=140`, `damage=1.55`），落点炸开一大团龙炎，半径 3.8 格内 12 点龙炎、缠身并挑飞；全程免除自身坠落伤害，可接 `cleave_1` |
| `cross_rend` | 旋风斩 / 横扫接续 | 0.94s | 0.20s, 0.44s | 0.68s | **逆鳞十文字裂炎斩**：右上→左下重袈裟接左下→右上逆十字二连爆斩（`cone=120`, `damage=0.95×2`），可接 `ascend_3` |
| `parried` / `parried_npc` | 攻击被弹反 | 0.50s / 0.80s | — | — | 被弹反后仰大破绽僵直 |
| `sheathe` | 收剑 | 1.30s | — | — | 右侧剑花 → 致敬设定图 2 的短暂驻剑致礼（`PLANT_SALUTE`）→ 下沉顺势收招 |

坠星原来带着 `effect=slam`：落点先把周围挑飞，同一刻的接触又按 `slam` 把刚挑起的目标砸回地面，挑飞从来留不住。设计写的是挑飞，所以去掉了 `slam`；要改成砸地再说。

### 状态图

```text
draw ──> idle ──左键──> cleave_1 ─> rend_2 ─> ascend_3 ─> cataclysm_4 ─> cleave_1 …
          │  ├─空中左键──> skyfall_judgement ──左键──> cleave_1
          │  ├─疾跑左键──> vortex_rush ──左键──> cross_rend ──左键──> ascend_3
          │  ├─潜行左键──> crimson_thrust ──左键──> vortex_rush
          │  └─右键────> guard ──左键──> flame_sweep ──左键──> cross_rend
          │               ├─挡下（0.35 秒内）──> parry ──左键──> dragon_fang ──左键──> rend_2
          │               └─挡下（之后）──> guard_hit ──> guard
          └─切槽──> sheathe
任何攻击被弹反 ──> parried（NPC：parried_npc）──> idle
```

各招按住右键都转格挡（`use_press`）。

## 5. 动作

- 编排 `tools/crimson_flame_longsword_moves.py`，烘焙出第三人称、第一人称两份动作（`combat/animations/crimson_flame_longsword_{tp,fp}.animation.json`），另出拉塞塔 NPC 版（仓库外 `art/crimson_flame_longsword/anim_v01/lacerta_crimson_flame_longsword.animation.json`，含龙翼、龙尾和裙褶避让）。NPC 版只烘焙了，拉塞塔还没进游戏。
- 预览在 `art/crimson_flame_longsword/anim_v01`：每招一张三视图、连招和技能的 GIF。

## 6. 样子

- **刀光**：`combat/trails/crimson_flame_longsword.json`（刀身 5.5–29.3，按速度和各招时段出光），样式 `logos_dimension:dragonfire`。这是龙炎这种伤害的刀光，所以任何武器缠上龙炎（`Dragonfire.wrap`）也换成它；之前龙炎缠绕找不到样式，退回了奥秘刀光。缠上别的元素时照常换成那种元素的刀光。
- **命中**：龙炎打中时 Logos 的命中特效换成一团龙炎色的火（龙炎自带）。
- **战技**：突刺的剑尖、旋风和十文字的身前、横扫身前的一道弧、坠星的落点，都放龙炎的一团（`FoxfireCue.burst`）；横扫的五道游脉借 Logos 的奥秘游脉，每刻先发 `FoxfireCue.vein`，客户端画成龙炎的火舌和熔金火星。
- **冲刺的火星**：`client/CrimsonFlameClient` 按同步来的招式和开始的刻自己画（突刺第 2–5 刻身后一串、旋风第 2–10 刻绕身一圈、坠星升空和下砸），时段同服务端的位移，不另发网络包。第一人称时自己身上的不画，只画旋风那一圈。
- 没有新加贴图：刀光的遮罩和噪声、龙炎的火舌和火星都是现成的。

## 7. 资源生成

- 网格、贴图、图标、物品模型（不手改生成的 JSON）：
  ```powershell
  uv run --offline --with numpy --with pillow python tools/import_crimson_flame_longsword.py
  ```
- 动作烘焙与三视图 + GIF 离线预览（不修改 `lacerta_14.bbmodel` 原文件）：
  ```powershell
  uv run --offline --with numpy --with pillow python tools/crimson_flame_longsword_moves.py
  ```

## 8. 测试

| 层 | 内容 |
|---|---|
| 单元 | `CrimsonFlameLongswordTest`（两个视角的动作和招式一致、刀不翻面、四式连招、弹反接龙牙、身法和格挡技能、物品模型对齐）、`WeaponTrailsTest`（刀光设置和动作一致、狐火每种颜色和龙炎都有刀光样式）、`MovesetTest`、`CombatWeaponTest`、`ItemModelDisplayTest`、`LangCoverageTest` |
| 世界 | `-PdimensionTests=logos_dimension:crimson_flame_*`，3 个：一式打穿满身钻石甲是整 8 点龙炎、掉一点耐久、不缠身、不点原版火；格挡中左键的横扫游脉打中 6 格外的靶子 10 点龙炎并缠身；坠星落点 12 点龙炎、缠身、挑飞 |
| 客户端 | `-PldTest=group:crimson_flame_longsword`：真实按键打一式、格挡、横扫、潜行突刺、疾跑旋风、空中坠星，第三和第一人称截图，检查伤害类型、缠身、游脉画成龙炎、冲刺火星、坠星不摔伤。`-PldTest=group:weapon_trails_crimson_flame`：四式在背后、正面、第一人称、俯视定格截图，检查出光、龙炎样式、缠冰霜换冰霜、缠龙炎仍是龙炎、待机散尽 |

本机跑单元测试时，Logos 和乐团的 jar 要放在纯英文路径下：路径里有中文时测试进程装不上它们，报「logos_dimension requires logos」。

## 9. 已知问题和没做的

- 动作、手感、数值都还没人试玩过。
- 拉塞塔还没进游戏：NPC 版动作只烘焙了，她用这把剑、把她加进 `dragonfire_kin` 都等她接入时做。
- 没有合成配方，同其他三维动作武器，只在创造模式物品栏里拿。
