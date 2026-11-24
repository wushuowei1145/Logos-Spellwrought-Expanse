# 附属箱子的分级战利品

Logos 的符文书和古代魔力药剂只靠 NeoForge 战利品修改器加进**原版**箱子表，条件是精确的表 id，附属自己的箱子表（`logos_dimension:chests/...`）不会被匹配。所以附属维护三张手写的分级表，专门往附属的箱子里放 Logos 和附属的东西：

| 表 | 对照的 Logos 档 | 用在哪里 |
|---|---|---|
| `logos_dimension:chests/tiers/common` | 地牢、废弃矿井、沉船补给、废弃传送门等 | 路边小遗迹（`chests/wayside/*`） |
| `logos_dimension:chests/tiers/uncommon` | 沙漠/丛林神殿、埋藏的宝藏、要塞走廊、下界要塞、堡垒等 | 暂无 |
| `logos_dimension:chests/tiers/rare` | 要塞图书馆、林地府邸、远古城市、末地城、堡垒宝藏室 | 暂无 |

## 每档内容

各池独立抽取，概率照 Logos 给对应原版箱子的数值。

| | 普通 | 较稀有 | 稀有 |
|---|---|---|---|
| 保底货币 | 铜分 4–16 | 铜分 12–40，另 20% 一枚银元 | 银元 1–3 |
| 魔力碎片 | 15%，1–2 | 25%，2–4 | 40%，3–6 |
| 基础符文书（瞄准/变化/法则/数值/运动，权重 29/52/10/23/8） | 15% | 30% | 50% |
| 本源之书 | — | — | 5% |
| 古代药剂 | 普通 25%、水晶 20% | 水晶 20%、炼化 15%、白垩 12% | 回响 25%、苍穹 12%、贤者 5% |

元素书（火、霜、雷、虚空、奥术、形态）在 Logos 里跟地点走（下界出火、雪屋出霜），不放进通用档；某个结构想给，就写在它自己的表里。生命永远不进箱子（Logos 规定）。

## 怎么用

- 结构的箱子仍然绑定自己的表（放这个结构的补给和地形物品），在里面加一个池子引用分级表：

  ```json
  { "rolls": 1, "entries": [ { "type": "minecraft:loot_table", "value": "logos_dimension:chests/tiers/common" } ] }
  ```

- Logos 或附属有了新东西要进箱子，改这三张表，不要改结构或各结构的表。
- 分级表里只放 `logos:` 和 `logos_dimension:` 的物品，原版物品写在结构自己的表里。
- Logos 改了原版箱子的概率时，对照 `MinecraftLogosMod/src/main/resources/data/logos/loot_table/chests/` 同步这里。

## 测试

`LootTierGameTests`（环境 `logos_dimension:loot_tiers`）：

- `loot_tiers_roll`：每档抽 1000 箱，每箱都有保底货币，上表每样都抽得到，五本基础符文书齐全，只出 Logos 与附属的物品。
- `loot_tiers_in_wayside`：九张小遗迹表各抽 300 箱，每箱都有铜分（说明确实引用了普通档），出得了 Logos 的药剂和符文书，不出银元。

```
./gradlew runGameTestServer -PdimensionTests='logos_dimension:loot_tiers_*'
```
