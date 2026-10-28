# 古木之拥

物品 ID 为 `logos_dimension:ancient_embrace`，可在远行大陆创造标签页获取，或使用：

```text
/give @s logos_dimension:ancient_embrace
```

使用维护者提供的 `ancient_embrace_v1_pack.zip` 中的完整盾牌模型和 64×64 原图，包括盾面、握把、藤蔓、五组叶片及金色束环，共 29 个方块部件。原包导出到 1.21.1，本附属只适配当前 26.1.2 的模型提交接口，保留尺寸、位置、UV 和旋转。首版不添加配方、掉落来源或 NPC 装备变更。

## 原版操作与即时格挡

沿用原版盾牌的右键举盾、松开收盾、副手使用、移动限制、方向判定、耐久损耗、修复材料及禁盾行为。耐久 **336 点**，与原版盾一致；普通格挡不消耗魔力。固定树纹不叠加旗帜纹样。

当前原版 `Items.SHIELD` 的 `BlocksAttacks` 组件为 `blockDelaySeconds = 0.25`，`blockDelayTicks()` 换算为 **5 tick**。生物需要使用盾牌达到这段时间，`getItemBlockingWith()` 才返回有效盾牌。古木之拥仅将自己的前摇改成 **0**；原版盾不变。

即时生效指服务端接收并处理举盾操作后立即具备格挡能力，仍遵循网络传输与服务端运行刻。攻击方向、穿透箭、绕盾伤害和禁盾照常判定。

## 五叶蓄生与极限格挡

- 每次真正挡下正数伤害，点亮 **一组叶片**。
- 举盾开始后的前 **5 tick（0.25 秒）**内成功格挡，点亮 **两组叶片**；对应 `0 ≤ 已举盾刻数 < 5`，第 5 tick 起恢复普通充能。
- 最多点亮五组；三次极限格挡可达到满充。奖励不反射伤害，不改变攻击者的原版击退或禁盾判定。
- 部分充能在最后一次增加叶片后保留八秒。满充生机从蓄满起保留八秒，继续格挡不会延长满充期限，也不储存第二次治疗。

叶片按上侧外叶、顶叶、上侧内叶、下侧外叶、下侧内叶的顺序点亮，每组的叶梗、叶身和叶尖一起切换。点亮部件使用独立的生命绿色发光纹理；未点亮的叶片、盾面、藤蔓和握把继续使用原图与实际实体光照。局部发光不会产生方块光源，是否出现泛光取决于玩家的渲染配置。

## 收盾回生

满充且生命值不足时，正常松开右键收盾，消耗五片叶的生机与 **5 点 Logos 魔力**，恢复 **4 点生命（两颗心）**，然后进入 **八秒技能冷却**。治疗显示 Logos 现成的生命治疗绽放。生命不足四点时恢复至上限，仍消耗一次完整生机；治疗可正常受到其它模组的治疗事件影响。

满血或魔力不足时不消耗生机和魔力，仍可在原有到期时间内再次举盾、收盾尝试治疗。治疗被取消时也不扣费。创造模式免魔力。强制禁盾、盾牌损毁或单纯切换使用状态不会自动算作玩家正常收盾治疗。

技能冷却保存于玩家，换盾不能绕过；冷却期间仍可正常举盾格挡，但不能积蓄生机。盾牌的叶片数量与绝对到期时间使用物品组件保存、同步，卸载或保存重读不会刷新期限。非玩家生物保留即时物理格挡，首版不新增其魔力池或回生法术。

## 资源与检查

普通 PNG 与提供的原图逐字节一致，发光 PNG 由代码在叶片原 UV 区域绘制。普通与发光部件互斥绘制，没有重叠面闪烁；UV、颜色、光照、法线与 overlay 交由原版 `ModelPart` 和模型收集器完整提交，不清空 Logos 的收集器。

```text
python tools/ancient_embrace_resources.py --pack /absolute/path/ancient_embrace_v1_pack.zip
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos.jar -PdimensionTests=logos_dimension:ancient_embrace*
./gradlew runClient -PlogosJar=/absolute/path/logos.jar -PldTest=group:ancient_embrace -PldTestHeadless -PldTestWindow=1280x720
```

分层验证结果见 [VERIFICATION.md](../VERIFICATION.md)。
