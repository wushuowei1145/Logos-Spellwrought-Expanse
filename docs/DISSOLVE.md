# 消散（Dissolve）

让东西一点点消失：消失的边缘发光、放出火星。参考虚幻 `NiagaraDissolveEdge`。服务端调用，结束后把东西拿走；目前还没有技能在用，也没有符文触发。

```java
Dissolving.entity(entity, DissolveShape.noise(), 60, Dissolving.EMBER);      // 生物、掉落物：连同盔甲和手持物，结束后消失（玩家不会）
Dissolving.held(holder, InteractionHand.MAIN_HAND, DissolveShape.parse("hilt"), 60, Dissolving.EMBER); // 只消散手上的东西，结束后清空这只手
Dissolving.blocks(level, positions, DissolveShape.sphere(.5f, .5f, .5f), 60, Dissolving.EMBER);        // 一组方块：立刻从世界移除，消散的是留下的影子
Dissolving.replace(level, positions, replacements, DissolveShape.noise(), 24, color);                  // 一组方块换成别的方块：新方块立刻放好，旧方块的影子罩在外面剥落（腐败污秽变回原方块用的）
```

方块消散只发给看得到那个区块、并且装了本附属的玩家（和 `CameraShots.play` 一样先看 `hasChannel`，没有这个通道的连接收到会报错）。

`replace` 的影子每面往外推 1/64 格，免得和原位的新方块抢深度；光照取六个邻格里最亮的，因为实心方块里面的光是 0。

试玩命令（需要管理员权限，这些命令真的会删东西）：

```text
/alchemy dissolve entity <目标> <形状> [秒]
/alchemy dissolve hand <形状> [秒]
/alchemy dissolve blocks <起点> <终点> <形状> [秒]
```

## 怎么做的

- **原理**（和虚幻的材质、粒子脚本一样）：模型的每一小格有一个值，阈值随时间上升；值低于阈值的格子消失，刚好高于阈值的一窄条（`Dissolve.WIDTH`）发光并放粒子。
- **形状**（`DissolveShape`，决定谁先消失）：
  - `noise` 噪波，到处开洞。
  - `sphere(x,y,z)` 从目标范围内的一点（0–1）球形外扩；`point` 从世界里的一点（比如命中处）。
  - `sweep(dx,dy,dz)` 一道波前朝这个方向扫过。
  - `.reversed()` 倒过来（球形向内收缩）；`.rough(k)` 是波前被噪波打乱的程度，0 是整齐的线。
  - 命令里的名字：`noise sphere implode feet down up north south east west tip hilt`。`tip`、`hilt` 用于物品，沿剑身朝尖或朝柄。
- **没有自定义着色器**：只把还留着的格子交给原版画，所以贴图、染色、附魔光效、光影包的光照都还在。代价是洞的边缘还是一小格一小格的，不像虚幻那样平滑。
- **三条路**都切成每个贴图像素四格：
  - 物品、方块模型是烘焙好的面：`DissolveMesh` 切一次并缓存，每帧把留下的格子交给 `submitItem`。
  - 生物模型、GeckoLib 模型、三叉戟和盾牌是逐顶点写的：`DissolveCollector` 替换它们提交用的收集器，模型先写进 `DissolveCapture`，切完再当自定义几何画。盔甲、手持物、渲染层都走这里，不用单独处理。透明的贴图像素（外层皮肤）读 `DissolveSkin` 跳过。
  - 噪波：物品和方块按空间位置取，洞能前后穿透、跨方块连续；生物按贴图位置取，模型动起来洞也跟着皮肤走。
- **方块**同时消散的越多格子越粗，按的是**客户端上正在消散的方块总数**（不是一次发来几块）：16 个以内每像素四格（整块 6144 格），64 个以内每像素一格（1536 格），160 个以内每 2 像素一格（384 格），再多每 4 像素一格（96 格）。切好的模型只在有东西消散时留着，全部结束就释放。
- **格数上限**：所有方块影子加起来最多 10 万格，正好放得下 16 块最细的方块。新来的方块这一档放不下就再粗一档，最粗也放不下就不画影子（方块照样换掉），只在那里冒两三颗火星。
  - 以前按每次发来的块数选精度。腐败的消散波每刻按区块发很多小组，每组都按最细切。1681 块的网络一起散时，客户端同时有 844 块影子，一帧交出 137 万格，最慢一帧 0.8 秒，光影下更糟。
  - 现在同一场景最多约 86000 格，平均每帧 8.9 毫秒，最慢 72–75 毫秒。完全不画影子时也是 71–78 毫秒，剩下的是原版重建区块网格的开销（场景 `corruption_large_fade`）。
- **发光**分三层：格子上的一层加法光（`Dissolve.Glow`），贴在边缘的短命柔光和飘走的火星（`Dissolves.emit`），以及真正的泛光（`GlowBloom`，见 [SCREEN_EFFECTS.md](SCREEN_EFFECTS.md)）。

## 和 Logos 的关系

只用 Logos 已公开的 `VfxParts`、`ShaderPackOverlay`，不需要 Logos 改代码。需要的 Mixin 都在附属里。三叉戟这类物品的切割、第一人称的做法参考了 Logos 的魔力缠绕，物品固定缓冲要提前画的原因见 `DissolveCollector.flushItems`。贴图用的是 Logos 已有的 `fire_impact/glow`、`fire_impact/ember`。

Iris 是可选的，附属不直接依赖它，阴影通道的判断用反射（`Dissolves.Iris`）。

## 光影

- 留下的格子走原版，吃光影的光照；发光和粒子经 `ShaderPackOverlay` 在光影合成之后画上去。
- 阴影通道里生物和物品切粗一档（每像素一格），影子看不出差别，能省下大部分开销。

## 没有做的

- 玩家自己第一人称的手臂（只有手上的物品会消散）。
- 方块实体（箱子、告示牌）：它们没有烘焙模型，看不到影子。
- 发光描边、破坏裂纹：消散中的生物不画。
- 消散中的方块和原版地形的明暗略有差别（没有环境光遮蔽）。
- 技能接入、符文触发、按元素变色（接口可以传颜色，但火星和柔光的贴图是火的）。

## 测试

```text
./gradlew test --tests dev.logos.dimension.DissolveShapeTest -PlogosJar=/absolute/path/logos.jar
./gradlew runGameTestServer -PdimensionTests="logos_dimension:dissolve_*" -PlogosJar=/absolute/path/logos.jar
./gradlew runClient -PldTest=group:dissolve -PldTestHeadless -PldTestWindow=1280x720 -PlogosJar=/absolute/path/logos.jar
```

场景：`dissolve_mobs`、`dissolve_items`、`dissolve_blocks`、`dissolve_bloom`。僵尸在太阳下会着火，场景里要给它戴头盔。
