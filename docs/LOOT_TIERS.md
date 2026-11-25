# 附属箱子的分级战利品

Logos 的符文书和古代魔力药剂只靠 NeoForge 战利品修改器加进**原版**箱子表，条件是精确的表 id，附属自己的箱子表（`logos_dimension:chests/...`）不会被匹配。所以附属维护三张手写的分级表，专门往附属的箱子里放 Logos 和附属的东西，并且按开箱玩家的签运变化：

| 表 | 对照的 Logos 档 | 用在哪里 |
|---|---|---|
| `logos_dimension:chests/tiers/common` | 地牢、废弃矿井、沉船补给、废弃传送门等 | 路边小遗迹（`chests/wayside/*`） |
| `logos_dimension:chests/tiers/uncommon` | 沙漠/丛林神殿、埋藏的宝藏、要塞走廊、下界要塞、堡垒等 | 暂无 |
| `logos_dimension:chests/tiers/rare` | 要塞图书馆、林地府邸、远古城市、末地城、堡垒宝藏室 | 暂无 |

每档分两张文件：`tiers/<档>.json` 放钱币，并抽一次 `tiers/logos/<档>.json`；后者放 Logos 的东西（魔力碎片、符文书、古代药剂）。

## 签运怎么起作用

箱子的内容在第一次被打开时才生成，原版会把开箱玩家和他的原版幸运传进战利品表（`RandomizableContainer.unpackLootTable`）。附属加了一个数值类型 `logos_dimension:luck`（`dev.logos.dimension.luck.LuckValue`），按等级取值，`values` 依次是 −2、−1、0、+1、+2 级：

```json
{ "type": "logos_dimension:luck", "values": [0.07, 0.11, 0.15, 0.19, 0.22] }
{ "type": "logos_dimension:luck", "kind": "wealth", "values": [2, 3, 4, 6, 8] }
```

- **不写 `kind`**：读原版幸运，也就是总签运（大吉幸运 II、中吉到末吉幸运 I、凶霉运 I、大凶霉运 II；赛钱箱的保佑、幸运药水也算），四舍五入后截在 ±2。
- **写 `kind`**：读开箱玩家这一项运势的等级（`Luck.level`），没抽到这一项就是 0 级。
- **没有玩家**（箱子没开就被破坏、被漏斗抽走）按 0 级，和不抽签一样。
- 可以放在任何接受数的地方：`random_chance` 的概率、`set_count` 的数量、池子的 `rolls`。
- 内容只生成一次：按第一个打开的人、打开那一刻的运势算，开过就不变。

| 签运 | 管什么 | 怎么变 |
|---|---|---|
| 总签运（原版幸运） | Logos 物品的整体出率 | 各池概率 ×0.5、×0.75、×1、×1.25、×1.5 |
| 总签运（原版幸运） | 小遗迹自己的池子 | 用原版的 `quality`：金苹果 0%、2.6%、5%、7.3%、9.5%；地形小物件每次抽中的机会 27%、30%、33%、38%、43% |
| 财运 `wealth` | 钱币 | 数量 ×0.5、×0.75、×1、×1.5、×2；吉时普通档另有银元；**大利**才可能出金冠 |
| 法运 `spell` | Logos 物品（`tiers/logos/*`） | 不利时整份有 30%（大不利 60%）落空；吉时有 50%（大利必定）再抽一份 |

其他七项运势暂时不影响箱子。

## 每档内容

下面是 0 级（平运）时的数值，括号里是 −2 到 +2 级。

| | 普通 | 较稀有 | 稀有 |
|---|---|---|---|
| 保底货币 | 铜分 4–16（2–8 … 8–32） | 铜分 12–40（6–20 … 24–80） | 银元 1–3（1–2 … 2–6） |
| 银元 | 吉 5%、大利 10% | 20%（10% … 40%） | — |
| 金冠 | 大利 2% | 大利 5% | 吉 3%、大利 10% |
| 魔力碎片 | 15%，1–2 | 25%，2–4 | 40%，3–6 |
| 基础符文书（瞄准/变化/法则/数值/运动，权重 29/52/10/23/8） | 15% | 30% | 50% |
| 本源之书 | — | — | 5% |
| 古代药剂 | 普通 25%、水晶 20% | 水晶 20%、炼化 15%、白垩 12% | 回响 25%、苍穹 12%、贤者 5% |

Logos 物品的平运概率照 Logos 给对应原版箱子的数值。元素书（火、霜、雷、虚空、奥术、形态）在 Logos 里跟地点走（下界出火、雪屋出霜），不放进通用档；某个结构想给，就写在它自己的表里。生命永远不进箱子（Logos 规定）。

## 怎么用

- 结构的箱子仍然绑定自己的表（放这个结构的补给和地形物品），在里面加一个池子引用分级表：

  ```json
  { "rolls": 1, "entries": [ { "type": "minecraft:loot_table", "value": "logos_dimension:chests/tiers/common" } ] }
  ```

- Logos 或附属有了新东西要进箱子，改分级表，不要改结构或各结构的表。钱币写进 `tiers/<档>.json`，Logos 的东西写进 `tiers/logos/<档>.json`（这样法运才管得到）。概率用 `logos_dimension:luck` 写五档，跟着上面的倍数。
- 分级表里只放 `logos:` 和 `logos_dimension:` 的物品，原版物品写在结构自己的表里。
- Logos 改了原版箱子的概率时，对照 `MinecraftLogosMod/src/main/resources/data/logos/loot_table/chests/` 同步这里。

## 测试

`LootTierGameTests`（环境 `logos_dimension:loot_tiers`）：

- `loot_tiers_roll`：不带玩家，每档抽 1000 箱，每箱都有保底货币，上表每样都抽得到（金冠除外，平运不出），五本基础符文书齐全，只出 Logos 与附属的物品。
- `loot_tiers_in_wayside`：九张小遗迹表各抽 300 箱，每箱都有铜分（说明确实引用了普通档），出得了 Logos 的药剂和符文书，不出银元。
- `loot_tiers_follow_fortune`：真的放箱子、让抽过签的玩家打开，第一次开才生成内容。大吉、不抽签、大凶各开 400 箱普通档，Logos 物品依次减少；小遗迹的金苹果大吉有、大凶没有。
- `loot_tiers_follow_kinds`：铜分随财运依次减少；财运大利 600 箱出得了金冠，平运和只有法运的不出；Logos 物品随法运依次减少。

```
./gradlew runGameTestServer -PdimensionTests='logos_dimension:loot_tiers_*'
```
