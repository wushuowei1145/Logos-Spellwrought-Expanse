# 灵猫塔罗

二十二张大阿尔卡那，注册 ID 是 `logos_dimension:tarot_00_fool` 到 `logos_dimension:tarot_21_world`，放在创造模式的「灵猫塔罗」页签。

目前它们只是能拿在手里、挂进物品展示框的道具：没有配方，没有占卜玩法，每张牌单独占一格。占卜怎么做还没定，定下来再加。

## 模型

所有牌共用 `models/item/tarot/card_blank.json`，每张牌只覆盖三个贴图键。牌面朝 -Z，从里到外：

| 层 | Z | 贴图 | 尺寸 |
| --- | --- | --- | --- |
| 牌背与纸芯 | 7.92–8.08 | `tarot/back.png`、`tarot/edge.png` | 128×224、16×16 |
| 牌画 | 7.915 | `tarot/major/<id>.png` | 112×192，完全不透明 |
| 发光层 | 7.905 | `tarot/major/<id>_glow.png` | 112×192，只有全透明和全不透明两种像素 |
| 牌框 | 7.9 | `tarot/frame.png` | 128×224，画窗透明 |
| 牌号牌名 | 7.89 | `tarot/major/<id>_labels.png` | 128×224 |

发光层是单独的模型元素，带 `light_emission: 15`，所以夜里这些像素按满亮度显示，牌面其余部分跟随环境变暗。发光像素同时也画在牌画里，不认这个属性的渲染环境不会露洞。

## 重新生成

牌面全部由代码绘制，规范和工具见 [tools/tarot_art/README.md](../tools/tarot_art/README.md)。

```bash
uv run --quiet --with pillow --with numpy python tools/tarot_art/render.py all --sheet
```

```bash
uv run --quiet --with pillow --with numpy python tools/tarot_art/build_assets.py
```

前者写牌画和发光层，后者写牌号牌名层、物品模型、物品定义和语言条目。

高像素终稿和《要素说明》不在仓库里，放在 `art/mystical_cat_tarot/灵猫大阿尔卡那_终稿与要素说明/`。它们只用来审稿对照，牌面模块不读取任何图片。

## 实机检查

```bash
./gradlew runClient -PlogosJar=/absolute/path/logos.jar -PldTest=tarot_views -PldTestHeadless -PldTestWindow=1280x720
```

`TarotViews` 把二十二张牌挂满一面墙，依次截第一人称、第三人称前后、掉落物、物品栏，再到午夜截一次，用来校手持位置和发光层。
