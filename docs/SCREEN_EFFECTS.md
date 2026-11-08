# 全屏后期（Screen Effects）

世界画完之后、界面之前的一个全屏通道，目前有两种效果：动漫式的黑白闪（冲击帧）和电影蒙版。只影响收到效果的那个玩家的画面。门后异画风见 [WORLD_STYLES.md](WORLD_STYLES.md)，同一处还有特效的泛光，见下文「泛光」，以及在后期里画的特效，见下文「后期里画的特效」。爆裂魔法自己的全屏通道（变暗、红热、转蓝、十字闪、白热的球体）排在这些之前，见 [EXPLOSION_MAGIC.md](EXPLOSION_MAGIC.md)；曦誓的全屏通道（大型蓄力时周围变暗、光柱冲起之前的几何黑白闪）紧跟在它后面，见 [DAWNVOW_CHARGE.md](DAWNVOW_CHARGE.md)；超电磁炮发射时的压暗（`RailgunScreen`）又排在爆裂魔法之前，见 [RAILGUN.md](RAILGUN.md)。处决特写的平涂画面（`ExecutionRenderer`）排在画风之后、黑白闪之前，见 [EXECUTION_SHOTS.md](EXECUTION_SHOTS.md)。

## 怎么用

```java
// 技能命中：在命中点闪一次，默认四个阶段，共五拍约 0.2 秒
ScreenEffects.flash(player, ScreenFlash.at(CameraPoint.world(hitPos)));

// 自己排拍子：发光、剪影、原画、翻转
ScreenEffects.flash(player, ScreenFlash.at(CameraPoint.on(boss, new Vec3(0, 2, 0)))
        .frames(Frame.GLOW, Frame.SILHOUETTE, Frame.NORMAL, Frame.SWAP));

// 运镜时加电影蒙版和遮幅，跟着镜头的过渡淡入淡出
CameraShots.play(player, CameraShot.of(from, lookAt).cinema().letterbox());
```

试效果：`/alchemy flash` 在准星指的地方按默认四个阶段闪一次。`/alchemy flash ggsex` 自己排拍子，一个字母一拍：`g` 发光，`s` 剪影，`e` 吞没，`x` 翻转，`w` 白底黑，`b` 黑底白，其余是原画。测试方块潜行时挖掉，会看到黑白闪、遮幅和蒙版一起出现。

## 黑白闪怎么做的

冲击点就是光源，所有线都指向它。明暗、剪影和轮廓都取自当时的画面和深度，不需要美术素材，打在什么上面都能用。

默认四个阶段：

| 阶段 | 拍数 | 画面 |
|---|---|---|
| 发光 `GLOW` | 2 | 还是彩色：画面整体变亮，光源一团白光加放射的光芒，靠近光源的东西被吞没。第二拍比第一拍亮 |
| 剪影 `SILHOUETTE` | 1 | 突然变成黑白：光源是白的，背光面是黑色剪影，剪影背离光源拉出影子线，轮廓带刺 |
| 吞没 `ENGULF` | 1 | 光源的白色扩大，线更长，形状更抽象 |
| 翻转 `SWAP` | 1 | 光再扩大，上一拍黑的被光吞成白的；光以外整个反过来，上一拍亮的暗下去 |

- **背光面**：比光源离镜头近，并且表面背对光源。表面朝向用深度算。只看远近的话，光源前面的整片地面都会变黑；地面朝上、迎着光，应该是亮的，上面才落得下影子线。
- **轮廓**：深度突变的地方。透视下平面的深度在屏幕上是线性的，二阶差分为零，所以只有轮廓和转折才算。
- **影子线**：从每个像素朝光源方向找一段，碰到背光面或轮廓，就说明这里落在它背离光源拉出的线里。每根线长度不同，而且断断续续。
- **带刺**：采样点沿放射方向随机错开，越往后错得越开，轮廓碎成一根根尖刺，形状越来越抽象。
- **剪影里的白**：朝光的一侧留一道白边，身体里零星几道顺着光的长白缝。
- **画面本来就暗的地方**：迎光区域里的影子、洞口，按暗的程度落一部分放射线，成了排线。分界跟着整幅画面的平均明度走，夜里也成立。
- **深度**：闪的时候在半透明之前留一份，玻璃和水不会变成剪影。不开光影时手在最后的深度里，算成离镜头最近，是剪影；开光影时手按它背后的东西判断。
- **时长**：按动画的 24fps 算，一拍 1/24 秒，和游戏帧率无关。帧率再低，每一拍也至少显示一帧。
- **收尾**：闪的时候画面放大到 105%，闪完 3 拍内收回，同时带一点色差。
- **界面**：闪的那几拍不画界面。成就、配方这类右上角提示不归界面层管，仍会显示。

旧的两拍 `WHITE`（白底黑）和 `BLACK`（黑底白）还在，只看颜色的明暗，不用深度，可以自己排进拍子里。

## 和 Sodium、光影的关系

通道只读主画面最终的颜色和半透明之前的深度，不碰光影包的缓冲，也不在世界渲染过程中画任何东西，所以不需要走 `ShaderPackOverlay`。

| 环境 | 结果 |
|---|---|
| 不装 Sodium 和 Iris | 通过 |
| 只装 Sodium 0.9.2 | 通过 |
| Sodium + Iris 1.11.4，光影关闭 | 通过 |
| Sodium + Iris + photon v1.4（延迟光照） | 通过 |
| Sodium + Iris + Complementary Reimagined r5.9.3 | 通过 |

这些都是无窗口客户端的结果，其他光影包没有测过。光影包把画面调得越有层次，黑白闪的细节越多（比如 photon 的云层会变成排线），这是效果本身读的就是最终画面，不是冲突。

不用原版的 `GameRenderer.setPostEffect`：它只有一个槽，会和附身时的苦力怕视角互相覆盖；原版后期链的参数也是加载时写死的，做不了逐帧变化。

## 光敏安全

满屏黑白交替有诱发光敏反应的风险，所以有三道限制：

- 原版选项「隐藏闪电的闪烁」打开时不闪。
- 两次闪之间至少隔 0.7 秒，这期间后来的直接跳过。
- 一次最多 8 拍。

连续命中时不要指望每一下都闪，只给最重的那一下用。

## 泛光（`GlowBloom`）

只让发光的东西泛光，不碰画面其他部分。特效把发光的几何交给 `GlowBloom`，世界和手画完之后，把它们画到一张空白的小图上，模糊，再加回屏幕。目前只有[消散](DISSOLVE.md)在用。

```java
GlowBloom.sprite(x, y, z, size, argb);          // 一个朝向镜头的小方块，坐标相对镜头
GlowBloom.quads(data, size, firstPerson);       // 一批四边形，格式见方法说明，比如 Dissolve.Glow
```

光影包的阴影通道里不要交，那时的姿势是太阳的。

- **为什么不按亮度取阈值**：雪、天空、白墙都会跟着泛光，整个游戏的观感都变了，光影包自己也可能已有泛光。
- **顺序**：排在画风和黑白闪前面，它们处理的是已经带泛光的画面。
- **遮挡**：小图比屏幕小，没法直接用深度测试，由着色器和留下的深度比较：挡在前面的方块和手会挡住光，玻璃和水不会。第一人称手上的东西永远在最前面。深度只在这一帧有东西发光时才留。
- **开销**：这一帧没有东西交，就一个通道都不跑。模糊是 6 张逐级减半的小图，每张几次采样。
- **强度**：`config/logos_dimension-client.toml` 的 `bloom`，默认 1，0 关闭。

场景 `dissolve_bloom`：同一次消散在白天和夜里，关和开各拍一张。

## 后期里画的特效（`PostVfx`）

有的特效要在画面调完色之后才画：爆裂魔法吟唱时周围变暗，施法者身上和天上的法阵却要格外亮；曦誓大型蓄力时也一样，四周升起的光点要在暗处发亮。在世界里画的话，它们会跟着一起暗下去。`PostVfx` 收这些发光的几何，世界和手都画完、爆裂魔法和曦誓的全屏通道跑完之后，在泛光之前把它们画上去。`PostVfx.collecting()` 说这一帧收不收：不收时照旧在世界里画（曦誓的 `PostLayer` 就是这样切换的）。

```java
// 在 SubmitCustomGeometryEvent 里，和 VfxParts 平常的用法一样先 translate(-camera)
VfxParts.sprite(pose, PostVfx.add(PostVfx.logos("heal/glow"), false), view, at, size, 0, argb);   // 加法发光
VfxParts.flat(pose, PostVfx.blend(texture, false), center, x, z, r, turn, argb);                  // 半透明
```

- **投影**：按世界渲染同一个投影矩阵（含视角晃动）在显卡上投到屏幕，和半透明之前的深度做硬件深度测试，被方块挡住的不画；玻璃、水挡不住。
- **手**：手挡在前面的不画。`front` 为 true 的一批画在手前面，用于第一人称杖头上的光。
- **顺序**：先画半透明的批，再画加法的批，最后画在手前面的；同一类按这一帧第一次交上来的顺序。
- **雾和光影包**：都不影响它们，几百格外的东西照样看得见；也因此不随光影包的光照变化。
- **开销**：这一帧没人交东西就一个通道都不跑；每张贴图一批，一帧几千个四边形没有问题。
- 半透明之前的深度由 `PostVfx` 留一份（`PostVfx.depth()`），爆裂魔法的全屏通道也拿它还原位置。

## 限制

- 蒙版目前只能跟着运镜走。技能要单独用蒙版时，需要给它加一个独立的入口。
- 要点里的顿帧和冲击瞬间的声音抽离还没有做；镜头震动可以用现成的 `DawnvowShake`。

## 测试

```text
./gradlew test --tests dev.logos.dimension.ScreenFlashTest -PlogosJar=/absolute/path/logos.jar
./gradlew runClient -PldTest=group:screen_fx -PldTestHeadless -PldTestWindow=1280x720 -PlogosJar=/absolute/path/logos.jar
```

测兼容时加 `-PldTestSeedDir=/absolute/path/seed`，seed 里放 `mods/`、`shaderpacks/` 和 `config/iris.properties`。黑白闪只有两拍，截图用 `ScreenEffectRenderer.hold` 停在某一拍上；时长另外按帧记录来查。改了后期就要在上表的环境里各跑一遍。
