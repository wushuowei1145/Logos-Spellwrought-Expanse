# 乐器

乐器从 0.12.0 起拆成独立的模组 **[星辰花乐团（Starbloom Orchestra，`starbloom_orchestra`）](https://github.com/MagicFoxDemon/StarbloomOrchestra)**，附属依赖它。
星辰花乐团不依赖 Logos，单独装也能用（原版生物、玩家、车万女仆演奏）；以后新的乐器和玩法都加在它那里。

## 附属这边还有什么

| 位置 | 内容 |
|---|---|
| `client/music/NpcMusician` | 附属的 NPC（精灵、神狐、灵猫，GeckoLib 骨架）坐上琴凳、站上工位时的演奏姿势：把星辰花乐团给的手位（`MusicianPoses`、`HeldRig`）换算到 NPC 的骨骼上 |
| `client/NpcRenderer` | 调用 `NpcMusician` 的两处 |
| `tools/concert_hall.py` | 灵猫大音乐厅的蓝图（大厅的装饰用附属的太玄黄铜、精灵家具、星辰花；乐器写成 `starbloom_orchestra:*`） |
| `client/music/HallViews`、`MaidHallViews` | 音乐厅里的客户端场景：精灵、神狐和原版生物、车万女仆一起合奏（`orchestra_hall_ui`、`orchestra_player_mallets_ui`、`orchestra_percussion_ui`、`orchestra_stand_player_ui`、`orchestra_maids_ui`） |

乐器、纸带、MIDI、乐团与指挥、工位、女仆的演奏姿势、采样和美术工具、「音乐厅音源」资源包、乐器的世界测试和大部分客户端场景都在
星辰花乐团里，说明在它的 `docs/ORCHESTRA.md`（这个仓库以前的 `docs/ORCHESTRA.md` 原样搬了过去，节号不变）。

## 老存档

方块、物品、方块实体、实体、物品上的数据组件的 ID 从 `logos_dimension:<名字>` 换成 `starbloom_orchestra:<名字>`，名字不变。
星辰花乐团给每一项注册了旧 ID 的别名（NeoForge 注册表别名，旧 ID 没人注册时才起作用），读档时自动换过去：放下的乐器、
琴凳上坐着的生物、纸带和指挥棒都在。不搬的：配方书里解锁过的乐器配方（再合成一次就解锁）、乐器的客户端设置
（`logos_dimension-client.toml` 里的 `instrumentVoices`、`midiDevice` 等，现在在 `starbloom_orchestra-client.toml`）、
服务端设置（`allowRollUpload`、`uploadsPerMinute`，现在在 `starbloom_orchestra-server.toml`）。

## 开发

附属编译、测试都要星辰花乐团的 jar：`-PorchestraJar=/absolute/path/starbloom_orchestra-x.y.z.jar`（和 `-PlogosJar` 一样，
也可以写进 `~/.gradle/gradle.properties`）。CI 按 `.github/workflows/build.yml` 里的 `ORCHESTRA_REF` 拉它的固定提交现场构建；
附属用到了乐器那边新的接口时，先在星辰花乐团提交，再改这一行。
