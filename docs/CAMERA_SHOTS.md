# 运镜（Camera Shot）

> 几个系统怎么选、共同的规范（通道顺序、深度、兼容、光敏、测试）见总览 [CINEMATICS.md](CINEMATICS.md)。

镜头暂时离开玩家，到某个位置看向某处，再平滑回到玩家并交还控制。没有黑屏，也不换相机实体：玩家的实体、物理和位置同步全程照常，只有这个玩家的画面在动。

## 怎么用

在服务端组一段镜头发给玩家：

```java
// 方块：固定机位，看方块的位置
CameraShots.play(player, CameraShot.of(
        CameraPoint.world(CameraShots.above(player, 10)),
        CameraPoint.world(pos.getCenter())));

// Boss：机位和视线都跟着实体走
CameraShots.play(player, CameraShot.of(
        CameraPoint.on(boss, new Vec3(0, 3, -7)),
        CameraPoint.eyes(boss))
        .timing(20, 80, 20));
```

默认过渡各 1.2 秒，停留 3 秒，锁操作，隐藏界面，没有遮幅和蒙版。

| 写法 | 作用 |
|---|---|
| `timing(进场, 停留, 回程)` | 三段时长，按刻；进场或回程为 0 就是硬切 |
| `keepControl()` | 不锁操作，玩家能边看边动，回程终点跟着玩家走 |
| `keepHud()` | 镜头离开时不隐藏界面 |
| `letterbox()` | 信箱模式：上下各加一条黑色遮幅，跟着进场滑入、跟着回程滑出；进场或回程为 0 时遮幅也是直接出现或消失 |
| `cinema()` | 电影蒙版：压饱和度、提对比、暗部偏冷、暗角，跟着镜头的过渡淡入淡出，见 [SCREEN_EFFECTS.md](SCREEN_EFFECTS.md) |
| `CameraShots.reach(level, from, to)` | 从 `from` 往 `to` 走，被方块挡住就停在前面 |
| `CameraShots.above(entity, height)` | 实体头顶的机位，被上方的方块挡住就矮一些 |

要让多个玩家一起看，就对每个玩家各调一次 `play`。上一段没播完又来一段时，新的一段从当时的画面接过去。玩家死亡时镜头提前往回走；退出世界或换维度时立刻结束。

`CameraTestBlock` 是方块接运镜的最小样例：用镐挖掉就播一段，潜行时挖掉则加上黑白闪、遮幅和蒙版。

## 限制

- **距离**：区块和实体都按玩家的位置下发。机位和目标要在玩家视距内，实体还要在它的追踪范围内，否则看到的是空区块、实体消失（跟着的实体不见了时，镜头停在最后的位置）。往远离玩家的方向看时，远景会比平时更早到头，回看玩家或侧向取景更稳。
- **穿墙**：镜头路线是直线，没有碰撞。`reach` 只保证机位本身不嵌进方块，从室内飞到室外仍会穿过墙，要靠机位和触发位置避开。
- **世界不暂停**：镜头期间怪物照常行动，锁操作时玩家也会挨打。战斗中的镜头要么由服务端保护玩家，要么用 `keepControl()`。
- **提示框**：成就、配方这类右上角提示不归界面层管，镜头期间仍会显示。
- **声音**：听者跟着镜头走，机位离得远时玩家身边的声音会变小。

## 实现

- `CameraShotMixin` 在 `Camera.alignWithEntity` 末尾换掉位姿，这是唯一改镜头的地方。之后的视锥、投影、地形剔除、雾和声音都按新位姿算。
- `CameraDirector` 每帧把镜头放在「玩家这一帧的真实视角」和机位之间，比例由 `CameraShot.weight` 给。比例为 0 时就是原版视角，所以进出都接得上。
- 镜头离头部超过 0.6 格时临时切到第三人称，手、身体、屏幕效果和各处 `isFirstPerson()` 判断都会按镜头在外面处理；回到这个距离内就切回去并交还控制。玩家的视角设置在结束时恢复。
- 遮幅在 `RenderGuiEvent.Pre` 里画，先于所有界面层：隐藏界面（包括 F1）时它照样在，`keepHud()` 时物品栏压在它上面。新的一段打断旧的时，遮幅从当时的位置接着走。
- 锁操作和打开界面时一样：每刻开头 `KeyMapping.releaseAll()`，结束时 `setAll()` 并恢复切换式的潜行疾跑。滚轮和鼠标转向不走按键映射，单独拦。
- 客户端还可以 `CameraDirector.play(shot, rig)`：停留期间的机位、视线和画面倾斜由 `rig` 逐帧给（可以硬切、抖动），进出的过渡和遮幅仍按 `shot` 走。[处决特写](EXECUTION_SHOTS.md)就是这样切镜的。

## 测试

```text
./gradlew test --tests dev.logos.dimension.CameraShotTest -PlogosJar=/absolute/path/logos.jar
./gradlew runGameTestServer -PdimensionTests=logos_dimension:camera_* -PlogosJar=/absolute/path/logos.jar
./gradlew runClient -PldTest=group:camera_shot -PldTestHeadless -PldTestWindow=1280x720 -PlogosJar=/absolute/path/logos.jar
```

客户端场景走真实的挖掘流程，并逐帧记录镜头位姿，按缓动曲线的最大速度检查有没有跳变。无窗口运行没有真实鼠标，鼠标锁只检查了转向公式归零，真实手感要进游戏看。
