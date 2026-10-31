# 特效：复用 Logos 与附属自有 Photon 特效

附属的特效分两类：**跟 Logos 一模一样的法术特效**直接调 Logos，**附属自己的新特效**直接用 Photon 写。两者都是编译期依赖，都不打包进附属 jar。

## 一、依赖

| 依赖 | Gradle | `neoforge.mods.toml` | 说明 |
|---|---|---|---|
| Logos | `implementation files(logosJar)` | `logos` required | 只为编译和开发运行；安装包不含主模组 |
| Photon | `implementation`，`transitive = false` | `photon` required | 版本与 Logos 的 `gradle.properties` 相同 |
| LDLib2 / KilaGraph | 同上 | 由 Logos 声明 | Photon 的前置，跟 Logos 同版本 |

构建必须给 Logos 的 jar：

```text
./gradlew build -PlogosJar=/absolute/path/logos-0.6.0.jar
```

也可以在 `~/.gradle/gradle.properties` 写一次 `logosJar=/absolute/path/logos-0.6.0.jar`，之后就不用每次传参数。缺少这个参数时，Gradle 在配置阶段直接报错。

Logos 升级 Photon / LDLib2 时，同步改这里的 `photon_version`、`ldlib2_version` 和 mods.toml 里 `photon` 的 `versionRange`。不能比 Logos 用的版本低。

## 二、复用 Logos 特效：调 `dev.logos.runtime.Vfx`

在**服务端**调用 `Vfx` 的公开静态方法。Logos 负责发包，并在客户端用 Photon 画出来，多人同步不用附属处理。常用的入口：

| 入口 | 画什么 |
|---|---|
| `burst(level, entity, from, element, Vfx.Burst)` | 落在目标身上的效果：伤害、治疗、护盾、力、药水 |
| `burst(level, pos, element, n)` | 某个位置的冲击 |
| `ray(level, from, to, element, caster, Vfx.RayEnd)` | 一次射线，带命中样式 |
| `line(level, from, to, element, caster)` | 两个物体之间的连线（汲取、处决） |
| `projectileImpact(level, id, pos, outward, element, size)` | 弹体撞击 |
| `cast` / `mark` / `execute` | 施法点、标记、处决提示 |
| `sound(element)` | 元素对应的音效 |

规则：

- 只调用 Logos 的 **public** 类。`dev.logos.client.*Visual` 是主模组的内部实现，大多是包级私有，不要用反射调用，也不要把代码复制过来。缺少入口时，到主模组提 PR 加一个公开方法，再把附属的 `logos` 依赖下限抬到包含它的版本。
- **持续型特效**（`trail`、`beam`、`field`）用一个 id 标识，客户端按 id 更新。这个 id 和 Logos 法术物体的计数器（`SpellObject`）在同一个空间里，附属自己挑的 id 会和玩家法术撞车。第一次需要持续型特效时，先在主模组加一个公开的 id 分配方法，不要硬编码 id。
- 元素用 `dev.logos.lang.Element`，配色、形状、拖尾都由 Logos 按元素生成，附属不要另外定义一套颜色。

## 三、附属新特效：Photon 或自定义几何

- 客户端代码放 `dev.logos.dimension.client.vfx`。需要由服务端触发时，附属自己定义 payload，不要借用 Logos 的 `FxPayload`。
- 两种画法都可以，跟 Logos 一样按层选：
  - 粒子类的层用 Photon，材质用 Photon 自带的（`TextureMaterial`、`CustomShaderMaterial`），不要返回自己构造的 `RenderType`，否则开光影时看不见。
  - 光柱、地面光环、冲击波这类成形的几何，用 `VfxParts` 画（照 Logos 蓄力核心的 `ChargeParts` 做法）。它的材质直接读 `logos:textures/vfx/...`，几何每个顶点写全；绘制时经 `dev.logos.client.ShaderPackOverlay.collector(...)` 提交，并在 `SubmitCustomGeometryEvent` 里用 `EventPriority.LOWEST` 排在 Logos 之后（Logos 在这个事件里先 `begin()`）。
- Logos 已有的贴图和 shader 直接用 `logos:` 路径引用，例如 `logos:textures/vfx/fire/...`，不要复制一份。新素材放 `assets/logos_dimension/textures/vfx/`，仍然用代码绘制。
- 风格对齐主模组仓库里的特效设计文档（`CHARGE_VFX.md`、`FIELD_VFX.md`、`RAY_VFX.md` 等）。
- 自定义几何的要求不变：写全 UV、颜色、光照、法线和 overlay，并与 ShaderPackOverlay 共用收集器（见 AGENTS.md）。
- **不同材质之间谁先画是不确定的。** 原版把同一批自定义几何按材质放进 `HashMap`，先后取决于对象哈希，每次启动都可能不同。几层全是加法发光时无所谓，但只要有半透明的暗层（比如光柱的暗斑块）要盖在亮层上，就必须固定顺序，否则这一次暗块很浓，下一次被亮层冲掉。用 `VfxParts.layered(raw, out)`：不开光影时每层放进各自的 `order(n)`（原版按序号分批画），开光影时交给 ShaderPackOverlay（它本来就按提交顺序画）。光柱就是这样做的；验证时连跑两次，截图应当完全一致。

## 四、和主模组同步

有了编译期依赖之后，Logos 改了 `Vfx` 的签名，附属会**直接编译失败**。这时按 [UPSTREAM_SYNC.md](UPSTREAM_SYNC.md) 跟进。编译通过仍然不能证明运行期兼容，世界内测试照常要跑。

## 五、已有的附属特效

### 金色光柱爆发（`PillarBurst`）

为圣剑蓄力劈下后的爆发准备。服务端调用 `dev.logos.dimension.vfx.PillarBurst.play(level, 位置, 缩放)`，160 格内的玩家会收到 `PillarBurstPayload`。客户端由 `PillarBursts` 管理，最多同时 8 个；画面在 `PillarBurstVisual`。只有画面，没有伤害，也不破坏方块。

参考 Unreal `NiagaeaBigVFXPack/Effects/Niagara/FlamePillar/NS_FlamePillar` 和 `NS_FlamePillar_Stone`。原版是循环的火柱，这里改成一次性爆发、金色。拆解（发射器、材质、解出的参数）在仓库外 `art/pillar/`。

| 时间（刻） | 内容 |
|---|---|
| 0–6 | 同心光柱从地面冲上 64 格；开头一闪，柱子先略粗再收回 |
| 0–30 | 两道贴地冲击波：地面亮环（`heal/ring`）加一圈低矮风墙（`physical_ray/wind`），分别扩散到约 18、24 格 |
| 2–48 | 光柱立着期间，脚下每 4 刻荡开一圈白色气浪，扩散到约 10 格，光柱离地后不再新生。边缘起伏、有断有续，往里拖着气流纹（`pillar_burst/ripple_0`–`2` 三种形状），每圈随机挑一种、随机转向、横纵各自略压扁 |
| 0–58 | 柱脚甩出的两圈火舌（参考《碧蓝幻想 Relink》夏洛特奥义光柱根部的火焰气浪）：上圈 16 条，向外倾斜 35–55°，像王冠；下圈 12 条，倾斜 70–82°，几乎贴地往外喷。4 刻内甩到全长，保持到第 10 刻，之后慢慢变淡、变细，同时往外飘。贴图 `pillar_burst/flame` 有 4 帧，每 2 刻换一帧，火苗会跳动；先画半透明深金底色，再叠金色发光和白芯 |
| 0–40 | 柱内上冲的光痕（`force/streak`），四周慢慢升起的光点（`heal/glow`） |
| 0–60 | 被掀起的尘土（`physical_ray/smoke`，按脚下方块的地图色往浅灰偏）；约 35° 锥形抛起的碎石，用碎石所在位置那块方块自己的贴图，落地后停一会儿再淡掉 |
| 全程 | 脚下的地面光晕（`heal/glow`）、两圈反向旋转的光环（`heal/ring`）和旋涡（`heal/swirl`） |
| 42–74 | 光柱底部始终贴着地面，柱身越收越细，第 66 刻只剩一条线，随后熄灭 |

- 光柱的层次照 UE 的 `M_FlamePillar_main`。那个材质是 Masked（不透明加镂空），最暗的一层（`MI_FlamePillar_main1`）基色是暗红褐，暗斑块是材质自己的颜色，不是背景；它挡住后面的亮层，配合快速上滚，显得速度快、能量大。这里的层（半径由内到外）：白芯（`pillar_burst/veins` 与 `charge/pillar`）、金色亮面（`veins`）、半透明的暗褐斑块（`pillar_burst/cells`）、半透明金色罩层（白天的亮天空会把加法发光冲成白色，靠它染回金色）、外层金色亮面和淡淡的外晕。绘制顺序是暗斑块最先，其余按半径由内到外：里层亮面叠在暗斑块上，把它冲淡成零星的琥珀色暗块。维护者认可的是这个样子；暗斑块若最后才画，会又黑又密，很难看。
- 贴图按世界尺寸铺：亮面一格横向 3.2 格、纵向再乘 1.35，暗斑块一格横向 4.5 格、纵向乘 1.8；绕柱一圈取整数格。各层每刻上冲 0.8–2.6 格。
- 暗斑块那层越往上纵向拉得越长：离地占满高 `f` 处一格贴图拉长 `1 + 40·f³` 倍，连续变化（竖向切 24 段算 UV）。底部三成和原来几乎一样，到顶约拉长 41 倍，近乎一条线；同样的滚动速度在上面也就冲得更快。
- `pillar_burst/` 下的贴图（暗斑块、亮面、三种气浪环、火舌四帧）是代码画的，生成脚本在仓库外 `art/pillar/tools/PillarTextures.java`。加新贴图时只往后追加，别改动前面几张用到的随机数顺序，否则已经调好的贴图会变。
- 绘制顺序（`order`）：光柱 7 层是 1–7，火舌的底色和发光是 8、9；其余部分（地面、冲击波、气浪、尘土、碎石）在 0。
- 缩放 1 时柱半径 1.6 格。所有尺寸和速度都乘以缩放。
- 镜头进到光柱里时，各层按到柱轴的水平距离淡出；贴近镜头的碎石、尘土和光点也会淡出，不会糊满视野。
- 预览：`/alchemy pillar [缩放]` 在准星指着的地面爆发一次（需要管理员权限）。
- 客户端场景：`./gradlew runClient -PlogosJar=<jar> -PldTest=group:pillar_burst -PldTestHeadless -PldTestWindow=1280x720`。`pillar_burst` 是远景的五个阶段；`pillar_burst_close` 是近景、经服务端发包、夜晚和站在光柱里。
- 动态预览：`-PldTest=group:pillar_preview`，去掉 `-PldTestHeadless` 就会开窗口播放。白天三个机位（远看、近看、侧面两倍大小），夜里三个机位（远看、站在旁边、第三人称背后），每个机位放一次完整的光柱，不截图。本地的 `预览金色光柱.cmd`（不进仓库）就是跑这一组。
- 没有做的：音效、照亮周围方块的动态光源、Unreal 原版的 HDR/Bloom、随时间被侵蚀的镂空（原版 OpacityMask 的溶解）。
