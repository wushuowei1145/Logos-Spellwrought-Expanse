# 致谢与第三方素材

本模组的代码和自制美术以「保留所有权利」发布（`neoforge.mods.toml` 的 `license`）。下面列出的第三方素材按各自的协议使用，**不适用**本模组的协议。

## 车万女仆（Touhou Little Maid）

- **作者**：TartaricAcid, Snownee, Succinum, Pajinyi, Zhi_Ban, CrystalizedSun, Foky, ZeniCrow, Paulzzh, Yuriscat, Lappland162 及车万女仆的其他贡献者
- **来源**：<https://github.com/TartaricAcid/TouhouLittleMaid>（1.21 分支）
- **协议**：代码为 MIT；模型、贴图、动画等美术资源为 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)（署名—非商业性使用—相同方式共享 4.0 国际）
- **免责**：素材按原样提供，原作者不提供任何担保，也不为本模组背书。

### 参照（不借用素材）

NPC 的骨骼命名、动画名、动画控制器结构与优先级、手部条件动画的命名规则，参照车万女仆的 GeckoLib 女仆（见 [NPC 模型美术规范](docs/NPC_MODELS.md)、[NPC 动画制作规范](docs/NPC_ANIMATIONS.md)）。`NpcAnimations` 按这些规则自行编写，没有复制车万女仆的代码。

### 借用的动画

来源文件：`src/main/resources/assets/touhou_little_maid/animation/maid.animation.json`（车万女仆默认女仆动画）。

含有下表任何一个动画的动画文件，**整个文件**以 CC BY-NC-SA 4.0 发布（相同方式共享），模组发布包内的协议说明见 `licenses/touhou_little_maid/NOTICE.txt`。

| 本模组的动画文件 | 动画名 | 来源动画 | 修改 |
|---|---|---|---|
| `forest_elf.animation.json` | `walk` | `walk` | 位移 ×2（本模组 2 倍制作） |
| `forest_elf.animation.json` | `jump` | `jump` | 无 |
| `forest_elf.animation.json` | `attacked` | `attacked` | 位移 ×2 |
| `forest_elf.animation.json` | `ladder_up` | `ladder_up` | 位移 ×2；去掉精灵模型没有的 `LongHair` |
| `forest_elf.animation.json` | `ladder_stillness` | `ladder_stillness` | 位移 ×2；去掉 `LongHair` |
| `forest_elf.animation.json` | `ladder_down` | `ladder_down` | 位移 ×2；去掉 `LongHair` |
| `forest_elf.animation.json` | `swim_stand` | `swim_stand` | 位移 ×2 |
| `forest_elf.animation.json` | `swing_hand` | `swing_hand` | 无 |
| `divine_fox.animation.json` | `walk`、`jump`、`attacked`、`ladder_up`、`ladder_stillness`、`ladder_down`、`swim_stand`、`swing_hand` | 同名 | 取自 `forest_elf.animation.json` 里已换算好的版本（修改同上），神狐与精灵骨架相同 |

来源固定在车万女仆 1.21 分支的提交 `207647c85740b1d0971de6040d6dd57cea62528c`，由 `tools/forest_elf_animations.py` 读取、修改后写入。因此 `forest_elf.animation.json` 整个文件（包括同一文件里自绘的持弓、拉弓、治疗、行礼、拒绝动画）以 CC BY-NC-SA 4.0 发布。

`divine_fox.animation.json` 由 `tools/divine_fox_assets.py` 从精灵文件复制这些动画，整个文件（包括神狐自己的待机、死亡、常驻叠加层、打刀动作，以及从精灵文件复制的自绘持弓、拉弓、行礼、拒绝）同样以 CC BY-NC-SA 4.0 发布。

借用新动画时在这里加一行；只参照节奏和姿势、自己从头制作的动画不用登记。

## Minecraft 原版字形与双手剑

黑钢长剑的剑身符文使用 Minecraft 26.1.2 原版附魔台粒子 `sga_l/o/g/o/s`，来源为本地客户端资源；这些字形属于 Mojang/Microsoft。两把长剑及其剑鞘和动作使用用户审定的自制素材，没有借用车万女仆动作。详见 [双手长剑](docs/LONGSWORDS.md)。

## 乐器采样

`assets/logos_dimension/sounds/instrument/` 下的录音采样（钢片琴除外，钢片琴是自己合成的）取自下面三个采样库，
由 `tools/instrument_sources.py` 从固定提交下载、改编（混成单声道、重采样、切头截尾、加循环点、调整音量、重新编码）。
发布包内的协议说明见 `licenses/instrument_samples/NOTICE.txt`，做法见 [乐器采样](docs/INSTRUMENT_SAMPLES.md)。

| 乐器 | 来源 | 作者 | 协议 |
|---|---|---|---|
| 星辰花三角钢琴 | [Salamander Grand Piano V3](https://github.com/sfzinstruments/SalamanderGrandPiano)（提交 `3382bf94`，原始发布 <https://archive.org/details/SalamanderGrandPianoV3>） | Alexander Holm；SFZ / FLAC 版 kinwie（sfzinstruments）；重新调律 Markus Fiedler | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/)，**须署名**；本模组的钢琴采样是它的改编 |
| 小提琴、长笛、小号 | [VS Chamber Orchestra: Community Edition](https://github.com/sgossner/VSCO-2-CE)（提交 `44030090`） | Versilian Studios（Sam Gossner、Simon Dalzell 录音，Elan Hickler 切采样） | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)，不要求署名，这里致谢 |
| 灵猫竖琴、钟琴、管钟、大键琴 | [Versilian Community Sample Library](https://github.com/sgossner/VCSL)（提交 `c1ea7bcc`） | Versilian Studios 及贡献者 | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)，不要求署名，这里致谢 |

转载、整合包、二次修改时保留 Salamander 的署名与协议链接（`licenses/instrument_samples/NOTICE.txt`）。

## 本模组使用借用素材的限制

- **非商业**：本模组不能收费、付费抢先体验，或以借用素材牟利。需要商用时，先取得车万女仆作者的许可，或换成自制动画。
- **署名不能去掉**：转载、整合包、二次修改时保留本文件和 `licenses/touhou_little_maid/NOTICE.txt`。
