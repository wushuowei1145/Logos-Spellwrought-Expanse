# 过场、后期与特写：总览和规范

运镜、全屏后期、门后画风、处决特写、泛光、在后期里画的特效，是同一套东西的几个部分：服务端决定「给谁看什么」，客户端在世界画完之后、界面之前，把画面换掉或叠上东西。这篇讲怎么选、共同的规矩和踩过的坑；每个系统的细节看它自己的文档。

| 系统 | 文档 | 一句话 |
|---|---|---|
| 运镜 | [CAMERA_SHOTS.md](CAMERA_SHOTS.md) | 镜头暂时离开玩家，看完平滑回来，可带遮幅和电影蒙版 |
| 全屏后期 | [SCREEN_EFFECTS.md](SCREEN_EFFECTS.md) | 黑白闪（冲击帧）、电影蒙版、泛光 `GlowBloom`、后期里画的特效 `PostVfx` |
| 区域画风 | [WORLD_STYLES.md](WORLD_STYLES.md) | 门后、群系里的世界换一种画风（黑白、童话、国画），走进去时球形切换 |
| 处决特写 | [EXECUTION_SHOTS.md](EXECUTION_SHOTS.md) | 投技、处决的两秒特写：抛起、黑白、刀光、红色重击、拍下；可交互版本 |
| 各功能自己的通道 | [EXPLOSION_MAGIC.md](EXPLOSION_MAGIC.md)、[DAWNVOW_CHARGE.md](DAWNVOW_CHARGE.md)、[RAILGUN.md](RAILGUN.md)、[SHIRAUME_BOSS.md](SHIRAUME_BOSS.md)、[ORCHESTRA.md](ORCHESTRA.md) | 爆裂魔法、曦誓蓄力、超电磁炮、白梅战、乐团描边各有一个全屏通道 |

## 一、先选对工具

| 想要 | 用 | 不要 |
|---|---|---|
| 镜头去别处看一眼再回来（方块、Boss 登场、机关） | 服务端 `CameraShots.play(player, CameraShot.of(from, lookAt))` | `setCameraEntity`、生成看不见的实体当相机 |
| 镜头在停留期间切机位、抖动、歪画面 | 客户端 `CameraDirector.play(shot, rig)`，`Rig` 逐帧给机位 | 自己写 Mixin 改 `Camera` |
| 运镜时上下黑边、电影感调色 | `CameraShot.letterbox()`、`CameraShot.cinema()` | 自己画黑条 |
| 一下冲击：黑白闪 | 服务端 `ScreenEffects.flash(player, ScreenFlash.at(CameraPoint.world(pos)))` | 每次命中都闪 |
| 某块区域的世界换画风 | 服务端 `StyleRealms.add(level, realm)` | 第二镜头、渲染到贴图再贴到门上 |
| 投技、处决特写 | `ExecutionShots.play`；玩家逐刀点击的用 `InteractiveExecutions.start` | 自己拼运镜加闪 |
| 让发光的特效泛光 | `GlowBloom` | 按亮度挑像素来泛光（会把天空、雪地一起糊掉） |
| 画面被调暗、调色后仍然亮着的特效；雾和光影包外也要看得见 | `PostVfx.add` / `PostVfx.blend` | 在世界里画了再想办法提亮 |
| 一种新的全屏效果 | 照第四节加一个通道 | 占原版 `GameRenderer.setPostEffect` 的槽 |

原版只有一个后期槽：附身苦力怕的视角就在用它，参数也只在加载时读一次。这里所有效果都不碰它。

## 二、共同的结构

### 服务端决定，客户端画

- 服务端只发「给谁看什么」：一个包，带上时刻、种子、跟随哪个实体。画面、镜头、声音都在客户端按同一张时间表算出来，不逐帧同步。
- 共用的类（`camera`、`screenfx`、`worldstyle`、`execution` 这几个包）不能引用客户端的类，否则专用服务器一加载就崩。客户端的放在 `client` 下面。
- 发包前查 `player.connection.hasChannel(TYPE)`；注册用 `event.registrar("1").playToClient(...)`。

### 一个插入点，固定的顺序

所有全屏通道都由 `ScreenEffectMixin` 调：插在 `GameRenderer.render` 里 `FogRenderer.endFrame()` 之前。这时世界、手、光影包的合成都已经画完，主画面就是最终结果，界面还没画。顺序（先画的在下面）：

1. `RailgunScreen`：超电磁炮发射时压暗
2. `ExplosionScreen`：爆裂魔法的调暗、红热、球体
3. `DawnvowScreen`：曦誓大型蓄力的调暗和穹顶
4. `PostVfx`：在调暗之后画的发光几何
5. `GlowBloom`：泛光
6. `PartOutline`：乐团选中声部的描边
7. `ShiraumeScreen`：白梅战的色散和红光
8. `WorldStyleRenderer`：区域画风
9. `ExecutionRenderer`：处决特写，整个画面换成平涂的颜色
10. `ScreenEffectRenderer`：黑白闪和电影蒙版，最后画

新通道按「要处理谁的结果」决定放哪：

- 调暗、调色类放在 `PostVfx` 之前，这样发光的特效不会被一起调暗。
- 风格化、整画面替换类放在泛光之后，这样处理的是已经带泛光的画面。

同一位置的另一个注入（`renderLevel` 里 `setProjectionMatrix` 之前）记下世界渲染真正用的投影矩阵 `WorldStyleRenderer.PROJECTION`，并开始收这一帧的 `GlowBloom`、`PostVfx` 几何。

界面层：

- 运镜的遮幅画在 `RenderGuiEvent.Pre`，先于所有界面。
- 要整屏替换画面的效果，在 `RenderGuiEvent.Pre` 里用 `EventPriority.LOWEST` 取消界面，排在最后，遮幅才已经画上去。
- 成就、配方这类右上角提示不归界面层管，取消不掉。

### 只读画面和深度

通道只读两样东西：主画面的颜色，以及自己在某一步复制的深度。光影包换掉了整条管线，但这两样在原版、Sodium、Iris 下都在，所以走的是同一条路。

深度在哪一步取，决定了里面有什么：

| 在哪取 | 里面有 | 没有 | 谁在用 |
|---|---|---|---|
| `RenderLevelStageEvent.AfterOpaqueFeatures` | 地形、不透明的实体 | 玻璃和水，以及**半透明的实体：玩家的模型是 `entityTranslucent`，不在里面** | 画风、黑白闪的剪影、`PostVfx`、`GlowBloom` |
| `RenderLevelStageEvent.AfterTranslucentFeatures` | 上面这些加上玩家等半透明实体 | 半透明的地形（玻璃、水） | 处决特写的剪影 |
| 画的时候主画面的深度 | 最后的结果 | 开光影时不一定有手 | 黑白闪判断手 |

规矩：

- 每个通道自己留一份：一个和主画面同尺寸的 `TextureTarget`，尺寸变了就 `resize`，`useStencil` 和主画面不一样时重建。
- 只在效果开着的帧复制，平常一帧不花。
- 要和 `PostVfx` 共用那一份，就在它的 `begin(active)` 里把自己算进去，并用 `PostVfx.depth()` 取。

### 用深度还原位置

- 逆矩阵用 `new Matrix4f(WorldStyleRenderer.PROJECTION).mul(camera.getViewRotationMatrix(...)).invert()`。这个投影含视角晃动和受伤倾斜，自己重算的投影会和画面对不上。
- 深度是 0 到 1 还是 -1 到 1，看 `RenderSystem.getDevice().isZZeroToOne()`，作为参数传给着色器。
- 还原出的是相对镜头的坐标。要判断「这个像素是不是某个东西」，就把它换到那个东西自己的坐标里比较：
  - 门框：看视线是否穿过门框。
  - 处决特写：看是否落在跟着身体转的盒子里。
  - 这样不需要模板缓冲，光影包下也成立。

### 时间

- **跟玩法对齐的**（运镜、处决特写）：按游戏刻加帧内插值走，暂停时停，和服务端安排的伤害、声音对得上。
- **纯画面的冲击帧**（黑白闪）：按真实时间，一拍 1/24 秒，一帧最多往前走一拍，帧率再低每一拍也看得到。
- **动画感**：画面和动作按 24 帧一拍地跳（`ExecutionTimeline.FRAME`），镜头的移动和抖动按帧连续地走。
- **声音**：在 `RenderFrameEvent.Pre` 里按帧放。两下之间不到三刻时，按刻放会忽快忽慢。

## 三、规范

### 兼容

- 不碰 Iris、Sodium 的类和光影包的缓冲，只读最终画面和自己复制的深度。
- 改了任何通道或着色器，都要在下面五种环境各跑一遍相关的客户端场景：原版、只装 Sodium、Iris 关光影、Iris + photon、Iris + Complementary。
- 光影包缺失时 Iris 会静默退回原版，场景照样通过。所以要确认日志里有 `Using shaderpack`，并看截图里真有光影。
- 测试用的模组和光影包在 `art/fire-beam-20261013/test-dependencies`，用 `-PldTestSeedDir` 传。不要从别的工作树的 `runs/` 拿，那里会被清空。

### 光敏

- 全屏的明暗交替任何一秒里不超过 3 次（常见的安全线）。连续命中时后来的就不闪：黑白闪两次之间至少隔 0.7 秒。处决特写默认只在节拍上对调，两次至少隔 0.35 秒。
- 原版选项「隐藏闪电的闪烁」打开时，不做全屏闪烁和黑白对调，只保留不闪的部分。
- 饱和的红色只用作一次转变（处决特写的重击），不要红色闪烁。
- 想要「每下都有感觉」时，用不闪的手段：
  - 局部的受击火花、刀痕、身体错位。
  - 镜头抖动、推镜、顿帧。
  - 节拍上才闪一次。

  处决特写第一版「每刀对调」实测一秒 10 次以上，试玩时太闪，保留为 `Swaps.EVERY` 只作对照。

### 不要贴在屏幕上的纹理

后期里不能有固定在画面上的纹理或噪声：纸纹、笔触噪声、逐像素抖动、固定的叠图。转镜头时世界在动、这些不动，会让人头晕（国画画风试玩时的教训）。

- 需要纹理时，按深度还原的世界坐标取（天空按视线方向）。
- 坐标每 4096 格折一次，频率用 2 的幂，折返处才接得上。
- 只做邻域平均的滤镜（模糊、Kuwahara）没问题。
- 集中线、刀光这类本来就是画面上的图形可以，但要以画面上的目标为中心，跟着目标走。

### 着色器

- 不要用 GLSL 内置函数的名字给自己的函数命名：`noise1` 到 `noise4`、`texture*` 等。开发机的驱动会放过，严格的驱动（AMD、Intel）编译失败，NeoForge 预编译会连带整个着色器重载失败，玩家直接进不了游戏（`explosion.fsh` 的 `noise3` 就出过这事）。
- 管线在 `RegisterRenderPipelinesEvent` 里注册，启动时就编译，第一次用时不卡。
- 着色器里写清每个参数的含义，和 Java 里的 `Std140Builder` 顺序一一对应；数组要对齐到 `vec4`。

### 镜头

- 运镜只用 `CameraShots.play` 或 `CameraDirector.play(shot, rig)`，不要换相机实体：换了之后区块、声音、玩家的物理都跟着出问题。
- 机位用 `CameraShots.reach` 或 `CameraShots.above`，避免嵌进方块。镜头路线本身不做碰撞，从室内飞到室外会穿墙，要靠机位避开。
- 视角在 `ViewportEvent.ComputeFov` 里改，只改 `usedConfiguredFov()` 为真的那次（世界的视角），不改手的。
- 锁操作、藏界面是默认值：镜头期间怪物照常行动，锁着的玩家照样挨打。战斗中的镜头要么由服务端保护玩家，要么用 `keepControl()`。

### 性能

- 效果没开时，通道在复制任何东西之前就返回。
- 量 GPU 耗时要在通道前后 `glFinish`；只看帧间隔，量到的是 CPU 提交时间。

## 四、加一个新的全屏通道

1. **服务端入口**：一个描述效果的 `record`（带 `StreamCodec`），一个包，一个 `XxxEffects.play(player, ...)`。
2. **客户端状态**：收到包后记下开始的时刻，按时间表算这一帧的参数。玩家退出世界、换维度、跟随的实体消失时立刻结束。
3. **管线**：参照 `ScreenEffectRenderer` 或 `ExecutionRenderer`。
   - `RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)`，加上采样器和一个 `UNIFORM_BUFFER`，在 `RegisterRenderPipelinesEvent` 里注册。
   - 每帧把参数写进 `MappableRingBuffer`（`Std140Builder`）。
   - 把主画面颜色 `copyTextureToTexture` 到自己的副本，再以副本为输入画回主画面。全屏三角形 `pass.draw(0, 3)`。
4. **深度**：按第二节的表选一步复制，只在开着时复制。
5. **顺序**：在 `ScreenEffectMixin.screenEffectDraw` 里按第二节的原则插入，写一行注释说明为什么排在这里。要整屏替换就在 `RenderGuiEvent.Pre` 以 `LOWEST` 取消界面。
6. **光敏和纹理**：对照第三节。
7. **文档**：写自己的 `docs/XXX.md`（怎么用、怎么做的、和 Sodium/光影的关系、限制、测试），在本文的表格和第二节的顺序里加一行，在 `AGENTS.md` 里加一行指向它。
8. **测试**：见第五节。

## 五、测试

- **单元测试**放时间表、编解码这类纯逻辑：时长、顺序、每秒闪几次、同一个种子在两端算出一样的结果。
- **客户端场景**（LDLib2 uitest，`@LDLRegisterClient`，`DEV_ONLY`）放画面：
  - 截图要停在某一拍上。已有的停住钩子：`ScreenEffectRenderer.hold`、`CameraDirector.hold`、`ExecutionClient.hold`。新效果也留一个。
  - 时长、顺序另外真放一次，在 `RenderFrameEvent.Post` 里逐帧记录来查。
  - 运镜按缓动曲线的最大速度逐帧查有没有跳变（`CameraShotViews.FrameLog`）。
- **测试框架的坑**：
  - `runCommand` 以控制台身份执行；需要玩家身份的命令用 `Minecraft.getInstance().player.connection.sendCommand(...)`。
  - 框架会替换 `Util` 的时钟，时间差可能是负数，要容忍。
  - 量真实时长用 `System.nanoTime()`。
  - F2 截图拍得到这些效果，因为直接抓主画面。
- **只跑相关的组**，不要跑整个 `group:logos`：`group:camera_shot`、`group:screen_fx`、`group:world_style`、`group:execution_shot`，以及你那个功能自己的组。

```text
./gradlew runClient -PldTest=group:screen_fx -PldTestHeadless -PldTestWindow=1280x720 -PlogosJar=/absolute/path/logos.jar -PldTestSeedDir=/path/to/seed
```

`seed` 目录里放 `mods/`（Sodium、Iris 的 jar）、`shaderpacks/` 和 `config/iris.properties`（`enableShaders`、`shaderPack`），五种环境各准备一个目录。
