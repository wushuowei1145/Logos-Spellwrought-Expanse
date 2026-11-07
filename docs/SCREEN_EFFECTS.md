# 全屏后期（Screen Effects）

世界画完之后、界面之前的一个全屏通道，目前有两种效果：动漫式的黑白闪（冲击帧）和电影蒙版。只影响收到效果的那个玩家的画面。门后异画风见 [WORLD_STYLES.md](WORLD_STYLES.md)。

## 怎么用

```java
// 技能命中：在命中点闪一次，默认白底黑接黑底白，共两拍
ScreenEffects.flash(player, ScreenFlash.at(CameraPoint.world(hitPos)));

// 自己排几拍：白、黑、原画、白
ScreenEffects.flash(player, ScreenFlash.at(CameraPoint.on(boss, new Vec3(0, 2, 0)))
        .frames(Frame.WHITE, Frame.BLACK, Frame.NORMAL, Frame.WHITE));

// 运镜时加电影蒙版和遮幅，跟着镜头的过渡淡入淡出
CameraShots.play(player, CameraShot.of(from, lookAt).cinema().letterbox());
```

试效果：`/alchemy flash` 在准星指的地方闪一次；`/alchemy flash wbw` 自己排拍子，一个字母一拍，`w` 白底黑，`b` 黑底白，其余是原画。测试方块潜行时挖掉，会依次看到黑白闪、遮幅和蒙版。

## 黑白闪怎么做的

全部由当时的画面算出来，不需要美术素材，打在什么上面都能用。

- **时长**：按动画的 24fps 算，一拍 1/24 秒，和游戏帧率无关。帧率再低，每一拍也至少显示一帧。
- **明暗**：先算整幅画面的平均明度，分界压在它以下。所以大部分画面留白，只有真正暗的东西变成墨，夜晚和山洞也不会一片全黑。
- **轮廓**：同一圈采样顺便算出明度梯度，浅色物体站在浅色背景上也有轮廓。
- **放射线**：线条从冲击点放射，每根线随机错开采样点，轮廓是撕裂的尖刺。半明半暗处只有一部分线落墨，就成了排线。每一拍换一次随机种子，线条会抖。
- **黑底白**：负片之外再加几根贯穿全屏、两头收尖的白线。
- **收尾**：闪的时候画面放大到 105%，闪完 3 拍内收回，同时带一点色差。
- **界面**：闪的那几拍不画界面。成就、配方这类右上角提示不归界面层管，仍会显示。

## 和 Sodium、光影的关系

通道只读主画面最终的颜色，不碰深度和光影包的缓冲，也不在世界渲染过程中画任何东西，所以不需要走 `ShaderPackOverlay`。

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
- 一次最多 6 拍。

连续命中时不要指望每一下都闪，只给最重的那一下用。

## 限制

- 蒙版目前只能跟着运镜走。技能要单独用蒙版时，需要给它加一个独立的入口。
- 要点里的顿帧和冲击瞬间的声音抽离还没有做；镜头震动可以用现成的 `DawnvowShake`。

## 测试

```text
./gradlew test --tests dev.logos.dimension.ScreenFlashTest -PlogosJar=/absolute/path/logos.jar
./gradlew runClient -PldTest=group:screen_fx -PldTestHeadless -PldTestWindow=1280x720 -PlogosJar=/absolute/path/logos.jar
```

测兼容时加 `-PldTestSeedDir=/absolute/path/seed`，seed 里放 `mods/`、`shaderpacks/` 和 `config/iris.properties`。黑白闪只有两拍，截图用 `ScreenEffectRenderer.hold` 停在某一拍上；时长另外按帧记录来查。改了后期就要在上表的环境里各跑一遍。
