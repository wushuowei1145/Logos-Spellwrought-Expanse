# 炼金釜的独立世界

0.3.0 在 0.2.0 温带大陆版本上加入。本功能作用于咒械原，远行大陆仍是原来的独立维度。

- 放置未使用的釜不建世界。玩家第一次真正跳入液面时分配 UUID，以 `logos_dimension:<UUID>` 作为维度名，UUID 同时写入釜的方块实体。
- 每个实例只有一个 20,000×20,000 格的地貌，边界位于 X/Z=-10,000 与 10,000；普通玩家受原版边界限制，管理员越界仍可能生成区块，不再重复空岛。默认落在外围以太区，约距中心 9,300 格。
- 不同釜使用各自的种子、区块和 Boss 状态。返回、重新进入和服务器正常保存重启都保留已有内容。
- 挖掘时把 `logos_dimension:cauldron_world` 数据组件复制到掉落物，再放置会恢复绑定。釜不可堆叠。创造模式挖掉已绑定釜也生成一件绑定掉落物。
- 方块被爆炸或直接替换、釜的物品实体被伤害摧毁（含岩浆/火焰）、掉入虚空或自然消失时，标记对应 UUID 已销毁。普通拾取、运输、区块卸载以及其他模组延长物品寿命不触发删除。
- 删除排在服务端 tick 末尾：撤离在线玩家，保存并关闭对应世界，随后只删除其 UUID 目录。若撤离或文件关闭失败会保留待办重试。保留已销毁记录，旧绑定不能重新生成同一个世界。

0.3.1 起，进入时在落点旁自动生成回程漩涡，右键返回各自来时的原釜旁，无需携带材料。回程位置按世界保存，重入复用；出口被挖掉不会销毁实例，再次进入会重建，玩家建造占住旧位置时另选空位。登录旧实例也会补出口并提示坐标。绑定当前 UUID 的釜也可作为出口。未绑定的新釜即使放在其他实例中，也会创建新世界；返回记录按嵌套顺序保存。归途所指实例已经销毁时跳过该点，必要时退回原版安全出生点。管理员可用 `/alchemy return` 返回。

区块位于存档的 `dimensions/logos_dimension/<UUID>/`。总索引为全局 SavedData `logos_dimension:cauldron_worlds`，返回点在主世界 SavedData。清理代码只接受索引中已登记、已销毁的规范 UUID 路径，拒绝目标或命名空间目录为符号链接的路径。

0.4.0 取消固定 `logos_dimension:crucible` 管理/旧漩涡测试维度，模板移到 `cauldron/crucible.json`，不能再用指令进入空模板。旧存档若仍加载固定维度则保存并卸载，保留旧目录。已绑定物品名称显示 UUID，悬浮提示和对釜右键显示完整维度名。两个维度均改为原版数据包生成，旧区块不会重绘，新建釜使用原版大群系布局。建议在新存档体验大陆地形。创造复制或指令复制带相同 UUID 的釜是同一个绑定，任何一份损毁都会销毁该世界。

第三方模组无伤害直接 `discard()` 物品的特殊行为暂不视为损毁，避免把拾取误判为删除。实例只在实际访问时生成区块；没有对整张 4 亿格地图预生成。

## 复现检查

```text
./gradlew test runGameTestServer build -PlogosJar=/absolute/path/logos.jar
./gradlew runClient -PlogosJar=/absolute/path/logos.jar -PldTest=group:cauldron_instances -PldTestHeadless -PldTestWindow=1280x720
```

真实服务器重启检查使用专用 `runs/cauldronPersistence`，先在其中准备 Minecraft 的 EULA 与测试服务器配置，再分别运行两个独立进程：

```text
./gradlew runServer -PlogosJar=/absolute/path/logos.jar -PcauldronPersistenceStage=create
./gradlew runServer -PlogosJar=/absolute/path/logos.jar -PcauldronPersistenceStage=verify
```

该钩子仅在开发环境且显式设置测试阶段时启用，不参与普通游戏。验证文件为该测试存档中的 `cauldron-persistence.properties`。
