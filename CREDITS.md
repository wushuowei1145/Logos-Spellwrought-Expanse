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
| （暂无） | | | |

借用新动画时在这里加一行；只参照节奏和姿势、自己从头制作的动画不用登记。

## 本模组使用借用素材的限制

- **非商业**：本模组不能收费、付费抢先体验，或以借用素材牟利。需要商用时，先取得车万女仆作者的许可，或换成自制动画。
- **署名不能去掉**：转载、整合包、二次修改时保留本文件和 `licenses/touhou_little_maid/NOTICE.txt`。
