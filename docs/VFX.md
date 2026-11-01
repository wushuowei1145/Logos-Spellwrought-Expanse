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

不分元素的形类魔法特效也在 Logos（做法见主模组的 `FORM_VFX.md`），同样在服务端调用：

| 入口 | 画什么 |
|---|---|
| `PillarBurst.play(level, 位置, 缩放)` | 金色光柱爆发（圣剑蓄力劈下后用） |
| `ManaTornado.play(level, 施法者或位置, 刻数, 缩放, 转色刻数)` / `stop(level, 施法者)` | 魔力龙卷，跟着施法者走；刻数为 0 或负数时一直卷到 `stop` |
| `SurfaceAura.set(物品, 样式, 色相)` / `of(物品)` | 物品表面的冒火或缠着魔力，写在物品的数据组件 `logos:surface_aura` 上；样式为 null 时去掉 |

这三种原本是附属自己做的，后来移进 Logos，附属改为调用。

规则：

- 只调用 Logos 的 **public** 类。`dev.logos.client.*Visual` 是主模组的内部实现，大多是包级私有，不要用反射调用，也不要把代码复制过来。缺少入口时，到主模组提 PR 加一个公开方法，再把附属的 `logos` 依赖下限抬到包含它的版本。
- **持续型特效**（`trail`、`beam`、`field`）用一个 id 标识，客户端按 id 更新。这个 id 和 Logos 法术物体的计数器（`SpellObject`）在同一个空间里，附属自己挑的 id 会和玩家法术撞车。第一次需要持续型特效时，先在主模组加一个公开的 id 分配方法，不要硬编码 id。
- 元素用 `dev.logos.lang.Element`，配色、形状、拖尾都由 Logos 按元素生成，附属不要另外定义一套颜色。

## 三、附属新特效：Photon 或自定义几何

- 客户端代码放 `dev.logos.dimension.client.vfx`。需要由服务端触发时，附属自己定义 payload，不要借用 Logos 的 `FxPayload`。
- 两种画法都可以，跟 Logos 一样按层选：
  - 粒子类的层用 Photon，材质用 Photon 自带的（`TextureMaterial`、`CustomShaderMaterial`），不要返回自己构造的 `RenderType`，否则开光影时看不见。
  - 光柱、地面光环、冲击波这类成形的几何，用 Logos 公开的 `dev.logos.client.VfxParts` 画（形类魔法特效就是用它画的）。材质传字符串时读 `logos:textures/vfx/...`，用附属自己的贴图时传完整的 `Identifier`；几何每个顶点写全。绘制时经 `dev.logos.client.ShaderPackOverlay.collector(...)` 提交，并在 `SubmitCustomGeometryEvent` 里用 `EventPriority.LOWEST` 排在 Logos 之后（Logos 在这个事件里先 `begin()`）。
- Logos 已有的贴图和 shader 直接用 `logos:` 路径引用，例如 `logos:textures/vfx/fire/...`，不要复制一份。新素材放 `assets/logos_dimension/textures/vfx/`，仍然用代码绘制。
- 风格对齐主模组仓库里的特效设计文档（`CHARGE_VFX.md`、`FIELD_VFX.md`、`RAY_VFX.md` 等）。
- 自定义几何的要求不变：写全 UV、颜色、光照、法线和 overlay，并与 ShaderPackOverlay 共用收集器（见 AGENTS.md）。
- **不同材质之间谁先画是不确定的。** 原版把同一批自定义几何按材质放进 `HashMap`，先后取决于对象哈希，每次启动都可能不同。几层全是加法发光时无所谓，但只要有半透明的暗层（比如光柱的暗斑块）要盖在亮层上，就必须固定顺序，否则这一次暗块很浓，下一次被亮层冲掉。用 `VfxParts.layered(raw, out)`：不开光影时每层放进各自的 `order(n)`（原版按序号分批画），开光影时交给 ShaderPackOverlay（它本来就按提交顺序画）。光柱就是这样做的；验证时连跑两次，截图应当完全一致。

## 四、和主模组同步

有了编译期依赖之后，Logos 改了 `Vfx` 的签名，附属会**直接编译失败**。这时按 [UPSTREAM_SYNC.md](UPSTREAM_SYNC.md) 跟进。编译通过仍然不能证明运行期兼容，世界内测试照常要跑。

## 五、已移到 Logos 的特效

金色光柱爆发、魔力龙卷、物品表面特效原本是附属做的，现在在 Logos（入口见上面第二节，做法、时间轴、贴图和客户端场景见主模组的 `FORM_VFX.md`）。附属只保留预览指令，都需要管理员权限：

- `/alchemy pillar [缩放]`：在准星指着的地面爆发一次光柱。
- `/alchemy tornado [秒数] [缩放] [转色秒数]`：在自己身边卷起龙卷，默认 8 秒、缩放 1、12 秒转一圈；秒数为 0 表示一直卷，`/alchemy tornado stop` 收掉。
- `/alchemy aura fire|mana [色相]`：给手里的物品加上表面特效，`/alchemy aura clear` 去掉。
