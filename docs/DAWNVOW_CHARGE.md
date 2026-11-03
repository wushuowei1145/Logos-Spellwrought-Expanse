# 曦誓的蓄力彩蛋

致敬《Fate》系列的誓约胜利之剑。只有曦誓（`logos_dimension:dawnvow`）有，黑钢长剑照旧。长剑原有的动作不变：按住右键竖剑蓄力，松开举剑下劈。

![蓄满：剑身喷出金色流火，长长的金光，身上的金色蓄力光](screenshots/dawnvow-charge/full_front.png)

## 蓄力斩的剑气

蓄力后松手劈下（剑刃接触的一刻），从脚前一格的地面放出一道剑气：Logos 物理游脉的样子，放大到宽 2.4、高 2，每刻 1.2 格贴着地面往前走（上得了一格台阶、跟着地面下到四格）。撞墙或走满 16 格为止。沿途碰到的生物各受 15 点物理伤害，剑气穿过它们继续走。地上留下发光的切痕，两侧翻起地面方块，过一会儿沉回去。不扣魔力。

![蓄力斩的剑气留下的切痕](screenshots/dawnvow-charge/small_wave_side.png)

## 大型蓄力

| 时刻 | 发生什么 |
| --- | --- |
| 7 秒 | 进入大型蓄力。站定不能移动、不能跳。四周 32 格内的地上不断升起金色光点，各自随机游走着上升、拖着细细的光痕，越接近蓄满越密。身上出现金色的蓄力光（没有法阵）：脚下一片柔光、两层缠着身体往上流的光、从脚边往上散开闪烁的细光线、绕身体盘旋上升的火星、从四周飞进身体的光点。从这时起开始扣魔力 |
| 12 秒 | 剑身发出长长的金光，伸到剑尖外很远，看上去剑长了一倍多，一边呼吸一边微微伸缩，剑尖一颗四芒星 |
| 15 秒 | 蓄满。胸前一团爆闪和放射的光芒，脚下一圈光向外扩开。剑身喷出金色的流动火焰（Logos 物品表面火焰，转成金色），金光更长更亮，身上的光更盛 |

![11 秒：四周升起的金色光点](screenshots/dawnvow-charge/grand_11s_wide.png)

![12 秒：剑身金光](screenshots/dawnvow-charge/blade_13s.png)

| 第一人称 | 背后 |
| --- | --- |
| ![第一人称蓄满](screenshots/dawnvow-charge/full_first_person.png) | ![背后蓄满](screenshots/dawnvow-charge/full_back.png) |

**魔力**：从 7 秒到蓄满共 1000 魔力，按刻平摊（每刻 6.25）。中途不够时提示，大型蓄力中断：解除站定、身上的光收掉，回到普通蓄力，松手只放普通剑气。已经付的魔力不退。没蓄满就松手同样只放普通剑气，魔力也不退。

## 巨型剑气

蓄满后松手劈下，向前（水平方向）放出一道巨大的金色剑气：一面向前鼓起的弧形光墙，宽约 10 格、高约 9 格，每刻 4 格，射程 64 格。身后留下一条宽阔的光流，一秒多淡去。剑气不理会地形，也不破坏方块。

- 生命值 ≤ 50 的生物：直接击杀。
- 生命值 50–100：受 50 点伤害，剑气穿过。
- 碰到生命值 > 100 的生物，或走满 64 格：十字爆闪，4 刻后 Logos 的金色光柱冲起（缩放 10，柱半径 16 格），半径 16 格内的生物受 200 点伤害。

伤害都是物理（按近战算护甲），不伤挥剑的人、同乘的和自己的宠物。生命值按剑气碰到时的当前生命算。剑气停在哪里在出手时就定下（前方第一个生命值超过 100 的生物处），客户端按同一个长度画。

![巨型剑气冲出去](screenshots/dawnvow-charge/wave.png)

![到头的十字爆闪](screenshots/dawnvow-charge/wave_late.png)

![光柱](screenshots/dawnvow-charge/pillar_wide.png)

## 实现

- 服务端 `dawnvow/DawnvowCharge`：招式状态机每刻报告手持曦誓的玩家的招式名，按 `charge_start`、`charge_hold`、`charge_release` 计时。站定是移动速度与跳跃力度上的 −100% 临时属性修饰。蓄满时把剑原来的 `logos:surface_aura` 记下来，结束后还原。服务器在蓄满时关掉、剑上留着金色火焰的，玩家下次登录时去掉。
- 服务端 `dawnvow/SwordWave`：两种剑气逐刻推进与结算。普通剑气用 Logos 公开的 `Vfx.vein` / `Vfx.veinEnd` 画，贴地方式与 Logos 游脉相同。
- 客户端 `client/vfx/`：`DawnvowClient` 收 `DawnvowPayload`；身上的光与四周光点在 `DawnvowAura`；剑身金光在 `DawnvowBlade`，挂在长剑骨骼上，第一、第三人称都跟着动作走；巨型剑气在 `ExcaliburWave`。
- 身上的光照 Logos 虚空巨型蓄力光环的层次重画，Logos 那套是包内私有的类和自定义着色器，没有复制过来。全部用 `VfxParts` 和 Logos 现有贴图，按 `logos:` 路径引用：`fire_beam/dot`、`laser`、`glow`，`surface/trail`，`force/streak`，`fire_impact/flash`，`lightning_beam/rays`，`physical_ray/wind`，`arcane_ray/impact_cross`。没有新增贴图。自定义几何都经 `ShaderPackOverlay` 提交。
- 普通剑气的游脉 id 从 −1,000,000 往下数。Logos 法术物体的 id 从 1 往上数，两边不会撞。主模组还没有给附属分配持续型特效 id 的公开方法，见 [VFX.md](VFX.md)。

## 复查

```text
./gradlew test runGameTestServer build -PdimensionTests=logos_dimension:dawnvow* -PlogosJar=/absolute/path/logos-0.6.0.jar
./gradlew runClient -PlogosJar=/absolute/path/logos-0.6.0.jar -PldTest=group:dawnvow_charge -PldTestHeadless -PldTestWindow=1280x720
```

世界测试 `dawnvow_charge_*` 三项：

- 蓄力斩剑气打中八格外的铁傀儡，受 15 点，不扣魔力。
- 按满 15 秒：7 秒起站定、按刻扣魔力，蓄满时剑上冒金色火焰、共扣 1000；放出后铁傀儡受 50、尸壳被击杀、监守者吃到光柱 200，随后解除站定、收掉火焰。
- 魔力只有 500 时大型蓄力中断，不会蓄满。

客户端场景 `dawnvow_charge` 在黄昏的石台上完整按一次 15 秒，拍各阶段与剑气、光柱，并检查三种生物的伤害。截图在 `screenshots/dawnvow-charge/`。

没测：光影包、联机多客户端、坡地与洞穴里的剑气。
