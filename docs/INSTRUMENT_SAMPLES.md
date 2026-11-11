# 乐器采样

魔女之森的乐器（钢琴、竖琴、钟琴、钢片琴、管钟、大键琴、小提琴、中提琴、大提琴、低音提琴、长笛、双簧管、巴松管、小号、定音鼓）
有两层音源：

- **模组本体：合成音源。** `tools/instrument_samples.py` 为每件采样乐器合成一套采样（全部 15 件共 3.4 MB），
  写在 `src/main/resources/assets/logos_dimension/` 的 `sounds/instrument/<乐器>/` 和采样表 `instruments/<乐器>.json`
  （`"quality": "synth"`）。不装资源包也能弹。
- **「音乐厅音源」资源包：录音。** `tools/instrument_sources.py` 把公开采样库的录音做成同样格式的采样和采样表
  （`"quality": "recorded"`），放在仓库的 `resourcepacks/concert_hall/`，单独发布（`./gradlew concertHallPack`
  打成 `build/libs/<mod_id>-concert_hall-<版本>.zip`，约 22 MB）。装上并启用后，除钢片琴外的 14 件乐器都换成录音；不装不影响。

模组 jar 因此不随乐器变多而变大：录音全在资源包里，本体每件乐器只多 0.1–0.6 MB 的合成音。
管风琴不在这里，它在客户端按音栓现场合成（[管风琴](PIPE_ORGAN.md)）；钢片琴两个库都没有，只有合成的一层。

## 1. 两层怎么叠

- 两层的采样表格式相同（`music/SampleMap`），资源包里的同名表盖在模组本体的表上面。`client/music/SampleMaps` 按资源包顺序
  从上往下找第一张读得懂的表：表里的 `format` 比模组认得的（`SampleMap.FORMAT`）新时跳过、记一行日志、退回下一层。
  旧模组遇到新资源包、新模组遇到旧资源包都不会坏，最多是用合成的音。
- 乐器界面写着这件乐器现在用哪一层：方块乐器的演奏页在标题下面，手持乐器的演奏条在右上角，「音源：音乐厅录音」或「音源：合成」。
- 换资源包、按 F3+T 重新加载时，采样表和解码好的声音一起清空、重读。

**以后加乐器做两层**：先在 `tools/instrument_samples.py` 的 `INSTRUMENTS`（和敲击类的 `PERCUSSIVE` 或持续音的 `SUSTAINED`）
加合成的一层，进模组本体；再在 `tools/instrument_sources.py` 的 `SPECS` 加录音的一层，进资源包，同时更新资源包里的
`NOTICE.txt` 和 `CREDITS.md`。两层的音域、松键时间、电平、可闻距离保持一致。采样表的结构变了，就把两个脚本的 `MAP_FORMAT`
和 `SampleMap.FORMAT` 一起加一。

## 2. 来源与许可（资源包）

| 采样库 | 用在 | 协议 | 要求 |
|---|---|---|---|
| [Salamander Grand Piano V3](https://github.com/sfzinstruments/SalamanderGrandPiano)（Alexander Holm；SFZ / FLAC 版 kinwie；重新调律 Markus Fiedler），提交 `3382bf94` | 钢琴 | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) | **署名**、附协议链接、说明做了修改。资源包根目录有 `NOTICE.txt`，源码里有 `CREDITS.md`。sfzinstruments 另请求注明「取自这个 SFZ 版本」，也写了 |
| [VS Chamber Orchestra: Community Edition](https://github.com/sgossner/VSCO-2-CE)（Versilian Studios），提交 `44030090` | 小提琴、中提琴、大提琴、低音提琴、长笛、双簧管、巴松管、小号、定音鼓 | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) | 无（公有领域）；仍在 NOTICE 和 CREDITS 里致谢 |
| [Versilian Community Sample Library](https://github.com/sgossner/VCSL)（Versilian Studios 及贡献者），提交 `c1ea7bcc` | 竖琴、钟琴、管钟、大键琴 | CC0 1.0 | 同上 |

VCSL 是 VSCO 2 CE 的扩展版，收了 VSCO 2 CE 的大部分采样（有的重新整理过）。同一件乐器两边都有时用 VCSL；
管弦乐器多半只有 VSCO 2 CE 有。模组本体只有自己合成的音，没有第三方素材。

## 3. 每件乐器用了什么（资源包）

| 乐器 | 采样（库里的文件夹） | 音与力度层 | 说明 |
|---|---|---|---|
| 星辰花三角钢琴 | Salamander `Samples/` | A0–C8 每隔小三度 30 个音 × 16 层力度里的 5 层（第 2、6、10、13、16 层，对应力度 1–36、37–56、57–80、81–104、105–127） | Yamaha C5。音准用 Markus Fiedler 的重新调律值（`Data/tune_ret.txt`）。没有用松键的琴槌声、踏板声、弦共鸣 |
| 灵猫竖琴 | VCSL `Concert Harp` | E1–F7 每隔三度 23 个音 × mf / f 两层（个别音只有 mp、p，就用它们） | 测出整体偏低约 20 音分，按测量值校正 |
| 钟琴 | VCSL `Glockenspiel` | G4–C7（文件名）× 弱 / 中 / 强三层 | **文件名比实际发声低一个八度**，实际 G5–C8，正好是钟琴的音域 |
| 管钟 | VCSL `Tubular Bells 2`（即 VSCO 2 的管钟） | C4–F5 全音阶 11 个音 × 两层 | 音高按文件名（钟的分音不谐和，没法按谐波量）。音域 B3–G5，两头变调不超过 2 个半音 |
| 大键琴 | VCSL `Harpsichord, Unk`（Harpsi4） | 每个半音一个，共 61 个 + 61 个松键声 | **文件名比实际发声低一个八度**，实际 F1–F6；松键时放拨子落回的那一下 |
| 小提琴（手持） | VSCO 2 CE `Strings/Solo Violin/Arco Vib` | G3–C7 15 个音 × p / f 两层 | 独奏小提琴揉弦长弓；加了循环点 |
| 中提琴（手持） | VSCO 2 CE `Strings/Viola Section/susvib` | C3–D6 13 个音 × v1 / v2 两层（力度 1–62、63–127） | 只有声部（齐奏）录音，没有独奏中提琴；**文件名比实际低一个八度**；加了循环点 |
| 大提琴 | VSCO 2 CE `Strings/Cello Section/susvib` | C2–F5 13 个音 × v1 / v3 两层 | 声部录音；**文件名比实际低一个八度**；加了循环点 |
| 低音提琴 | VSCO 2 CE `Strings/Solo Contrabass/SusVib` | F♯1–B3 13 个音 × v1 / v3 两层 | 独奏；**文件名比实际低一个八度**（最低四个音的基音太弱，测量时落在低一个八度上，八度按 SFZ、只取音分）；加了循环点 |
| 长笛（手持） | VSCO 2 CE `Woodwinds/Flute/susvib` | C4–C7 10 个音，一层 | 带揉音的长音；**文件名比实际低一个八度**；加了循环点 |
| 双簧管（手持） | VSCO 2 CE `Woodwinds/Oboe/Vib` | A♯3–F6 9 个音 × v1 / v3 两层 | 带揉音的长音；**文件名比实际低一个八度**；加了循环点 |
| 巴松管（手持） | VSCO 2 CE `Woodwinds/Bassoon/sus` | A♯1–D♯5 13 个音（弱的一层少一个）× v1 / v2 两层 | 直音长音；**文件名比实际低一个八度**；加了循环点 |
| 王室小号（手持） | VSCO 2 CE `Brass/Trumpet/sus` | F3–C6 10 个音 × 弱 / 强两层 | 直音长音；**文件名比实际低一个八度**；加了循环点 |
| 定音鼓 | VSCO 2 CE `Percussion/Timpani`（Hit） | 五面鼓 × v1 / v3 / v4 三层（第一面鼓没录 v4，最强一层用它的 v3） | 文件名不写音名，音高按 VSCO 的 SFZ：F♯2、A♯2、C♯3、E3、F♯3；音域 D2–A3 |

各库文件名的八度写法不一样（有的把中央 C 写成 C3），八度以库自带的 SFZ 为准；每个采样再量一遍音高：按谐波定出它实际在
哪个八度、差多少音分，和预期不符时报出来，只取测出的音分。

## 4. 合成的一层（模组本体）

`tools/instrument_samples.py`，每件乐器每隔小三度一个采样，游戏里取最近的采样变调（不超过 ±1.5 个半音）：

- 敲击、拨弦（钢琴、竖琴、钟琴、钢片琴、管钟、大键琴、定音鼓）用模态合成：若干个分音各自按指数衰减，加起音的轻响、
  共鸣箱的频响和很短的房间混响。定音鼓的分音比是鼓膜的 1 : 1.50 : 1.74 : 2.00 …，击鼓后音高略微下滑，另有一下低沉的鼓腔声。
- 持续音（小提琴、中提琴、大提琴、低音提琴、长笛、双簧管、巴松管、小号）合成一段起音和一段 1.2 秒的无缝循环：载波、
  5 Hz 的揉弦（6 个周期）、一个周期的慢摆动都取整数个周期，弓噪、气声做成以循环长度为周期的噪声，循环终点正好接上起点。
  起音只是音量和亮度慢慢推起来，没有噪声冲击。
- 响度、力度曲线与录音同一套标准（K 计权，力度 127 时约 −16 dB），装不装资源包，同一件乐器差不多一样响。
- 体积：钢琴 0.58 MB、大键琴 0.48、竖琴 0.38，其余每件 0.1–0.26 MB，共 3.4 MB。

## 5. 录音怎么做成游戏里的采样

`tools/instrument_sources.py`，每个采样：

1. **下载**：只下用得到的文件，钉在上表的提交上（`raw.githubusercontent.com/<库>/<提交>/<路径>`），存在 `--cache` 目录，下次不再下。
2. **解码**：ffmpeg 解码，soxr 高精度重采样到 44.1 kHz，左右声道平均成单声道（游戏里的声音要能定位，必须是单声道），去掉直流。
3. **测音高**：取起音后一段稳定的声音，算谐波处的能量减去谐波之间的能量，在预期音的上下八度里挑得分最高的，再用前几根谐波的峰值量出准确的音分。钢琴用调律值，管钟、定音鼓按文件名（SFZ），钟琴取最强的峰（钢条的基音最强）。
4. **切头**：切掉起音前的静音（钢琴、竖琴的文件开头有 8–21 毫秒空白），按下就响。弓弦、管乐慢慢推起来的弱奏，从快到稳定音量 −18 dB 前 30 毫秒开始放，轻弹也不慢半拍。
5. **截尾**（会自然衰减的乐器）：衰减到 −60 dB 或到上限时长（钢琴低音 10 秒、中音 7 秒、高音 5 秒、最高 3.5 秒；竖琴 4–9 秒；大键琴 2.5–4.5 秒；定音鼓 5 秒）为止，最后一段淡出。
6. **循环**（弓弦、管乐）：在起音之后 0.5–4.5 秒的稳定段里找循环点。有揉弦、揉音时，循环长度取揉弦周期的整数倍，起点和终点的音量、音高、音高变化方向都要接近，再把终点在一个周期内挪动，让两边的波形相位对上。终点前 150 毫秒和起点前同样长的一段交叉淡化（按两段的相关度补偿音量，不会在接缝处变响或变轻）。文件只到循环终点为止。
7. **响度**：用 K 计权（近似 ITU-R BS.1770）量响度。同一层里各音拉到这一层的平滑曲线上（去掉录音里个别音的忽大忽小；音区之间的自然起伏只保留一半、最多 ±6 dB，长笛录音里低音比高音轻 25 dB，游戏里太悬殊）。
8. **力度**：各层在自己力度上限处的响度落在同一条曲线 (力度 / 127) ^ curve 上，所以换层时只换音色、不跳音量；最响一层、音区中间的音在力度 127 时约 −16 dB，再按乐器加减几 dB 平衡。
9. **编码**：峰值放大到 −1 dBFS 再编码（libvorbis q5），播放音量写进采样表。用固定的流序号和 bitexact，重跑脚本字节不变。

## 6. 游戏里怎么放

- **采样表**（`music/SampleMap`）：`format`（格式版本）、`quality`（synth / recorded）、来源说明、可闻距离和一串区。每个区是一个文件，
  管一段音高、一段力度，记着它的实际音高（带音分）、增益、力度曲线、采样点数、循环点、松键时间和松键声。
- **不进 sounds.json**：`VoiceBank` 按表里的文件名直接放（`DirectSound`），第一次用到才解码，不会启动时把几百个采样全读进内存。
  打开乐器界面时先解码当前力度那一层（钢琴一层 30 个），手持乐器打开演奏条或附近有人拿着时先解码整件。
- **音量**：区的增益 × (力度 / 这一层的力度上限) ^ curve；大键琴不分力度。变调倍率 2^((音高 − 实际音高) / 12)，最多 4 个半音。
- **循环**：OpenAL Soft 的 `AL_SOFT_loop_points`。读出采样的缓冲时记下循环点（`OrganBufferMixin`），缓冲上传到 OpenAL 之后、
  挂到声源之前设上（`SoundBufferLoopMixin`）；之后由 OpenAL 逐采样点在起点和终点之间循环，没有接缝，按住多久响多久。
  OpenAL 不支持时只打一行日志，长音到采样结束为止。
- **松键**：会止音的乐器按这个区的松键时间淡出到 −40 dB（钢琴低音 0.65 秒、高音 0.25 秒；大键琴 0.08 秒；小提琴、中提琴 0.2，大提琴 0.25，
  低音提琴 0.3，长笛、巴松管 0.15，双簧管、小号 0.12 秒），大键琴另放一下拨子落回的声音（踩着延音踏板时不止音，放开踏板时再止）。
  竖琴、钟琴、管钟、定音鼓不止音，自然衰减。

## 7. 重新生成

```text
uv run --no-project --with numpy --with scipy python tools/instrument_samples.py --ffmpeg <带 libvorbis 的 ffmpeg>
    [--only violin cello ...] [--demo <试听目录>]
uv run --no-project --with numpy --with scipy python tools/instrument_sources.py --cache <下载目录> --ffmpeg <带 libvorbis 的 ffmpeg>
    [--only grand_piano concert_harp ...] [--report] [--demo <试听目录>]
./gradlew concertHallPack
uv run --no-project --with numpy --with scipy python tools/instrument_audit.py --ffmpeg <带 libvorbis 的 ffmpeg>
```

- 第一条写模组本体的合成音源，第二条写 `resourcepacks/concert_hall/` 里的录音，第三条把资源包打成 zip，
  第四条核对两层的音高（见第 9 节），重新生成之后跑一遍。
- `--report` 只分析、打印每个采样的测量结果（音高、切头、循环点、响度、增益），不写文件。
- `--demo` 另把每件乐器的音阶（轻、重各一遍）与和弦渲染成 ogg：取区、变调、循环、松键淡出都和游戏里一样。
- 录音第一次运行要下载约 550 个文件（只下用得到的），以后不再下。资源包里的 ogg 共 22.5 MB：钢琴 8.3、大键琴 2.4、竖琴 2.4、
  管钟 1.9、小提琴 1.2、中提琴 1.1、大提琴 1.0、低音提琴 0.9、巴松管 0.8、双簧管 0.8、小号 0.8、钟琴 0.7、定音鼓 0.5、长笛 0.4。
- 调参数（层的划分、时长上限、松键时间、乐器之间的平衡）改两个脚本里的 `INSTRUMENTS` / `SPECS`。

## 8. 以后备用的乐器

之前列的管弦乐团里还没做的乐器，都能在这两个库里找到（文件名里的音按库里写的记，木管、铜管的文件名多半比实际低一个八度，
做之前用 `--report` 量一遍、对照库里的 SFZ）。「层」是录音的力度层。`tools/instrument_sources.py --reserve <目录>` 把
`RESERVES` 里的几件做到那个目录（不进资源包），列出体积并渲染试听。

| 乐器 | 库与文件夹 | 奏法：音数（文件名音域），力度层 | 备注 |
|---|---|---|---|
| 小提琴声部 | VSCO 2 CE `Strings/Violin Section` | 揉弦长音 11 音（43–74），2 层；拨弦、跳弓、震音 | 可做「弦乐声部」方块 |
| 独奏小提琴其他奏法 | VSCO 2 CE `Strings/Solo Violin` | 拨弦 13 音 p / f；跳弓 15 音、震音 14 音，2 层 | 可加到现在的小提琴上（比如潜行弹 = 拨弦） |
| 中提琴、大提琴、低音提琴其他奏法 | VSCO 2 CE 同上各文件夹 | 拨弦、跳弓、震音各 12–14 音，2 层；低音提琴另有不揉弦 14 音 | |
| 短笛 | VSCO 2 CE `Woodwinds/Piccolo` | 长音、断奏各 5 音（67–91），1 层 | 采样稀，变调会大 |
| 单簧管 | VSCO 2 CE `Woodwinds/Clarinet` | 长音 11 音（38–78），3 层；断奏 3 层 | |
| 双簧管、巴松管其他奏法 | VSCO 2 CE `Woodwinds/Oboe`、`Woodwinds/Bassoon` | 双簧管直音 9 音、断奏 3 层；巴松管揉音 10 音、断奏 | |
| 长笛其他奏法 | VSCO 2 CE `Woodwinds/Flute` | 直音 10 音，2–3 层；表情揉音 10 音；断奏 8 音，3 层 | 可给长笛加力度层 |
| 圆号 | VSCO 2 CE `Brass/F Horn` | 长音 11 音（21–65），最多 4 层；断奏 3 层；弱音器 9 音 | |
| 长号 | VSCO 2 CE `Brass/Tenor Trombone`、`Brass/OldTrombone` | 长音 11 音（22–53），3 层；揉音；断奏 4 层；老长号长音 16 音 5 层 | 老长号层多但旧录音，做之前先听 |
| 大号 | VSCO 2 CE `Brass/Tuba` | 长音 9 音（17–50），3 层；断奏 2 层 | |
| 小号其他奏法 | VSCO 2 CE `Brass/Trumpet` | 揉音 11 音、断奏 3 层、直弱音器 / 哈蒙弱音器各 8 音 | 可加到现在的小号上 |
| 定音鼓滚奏、另两套定音鼓 | VSCO 2 CE `Percussion/Timpani`（Roll）；VCSL `Timpani 1`、`Timpani 2` | 五面鼓滚奏 2 层；Timpani 2 有 204 个文件，3 层 | 滚奏可做成「按住 = 滚奏」 |
| 小军鼓 | VCSL `Snare Drum, Modern 1–3`、`Rope Tension` | 每种 20–30 个文件，最多 9 层 | 无音高 |
| 大鼓 | VCSL `Bass Drum 1`、`Bass Drum 2` | Bass Drum 2 有 pp–ff 六档 | 无音高 |
| 镲 | VCSL `Clash Cymbals 1/2`（对镲）、`Suspended Cymbal 1/2`（吊镲） | 对镲 5–10 个，吊镲 23–25 个 | 无音高 |
| 三角铁 | VCSL `Triangles` | 37 个文件，2 层 | 无音高 |
| 录音的管风琴 | VCSL `Pipe Organ`（Loud / Quiet / 两种踏板）、`Renaissance Organ`（8′ / 4′ / Full） | 21–27 音，每种一个固定的音栓组合 | 不能自由开关音栓，所以现在的管风琴仍合成；可另做一台「文艺复兴小管风琴」 |
| 其他大键琴 | VCSL `Harpsichord, French / Flemish / English（Normal、Lute 两种音栓）/ Italian` | 各 27–32 音 | 现在用的 Unk 是每个半音都有的；English 的 Lute 音栓可做成换音色 |
| 马林巴、木琴、颤音琴 | VCSL `Marimba`（3 层）、`Xylophone`（pp / ff）、`Vibraphone`（软硬槌） | 8–11 音 | 不在之前的表里，顺带记下 |
| 越南筝（Đàn tranh） | VCSL `Chordophones/Zithers/Dan Tranh` | mf / ff 两层 | 两个库里唯一的东亚弹拨乐器，可做筝的底子 |

两个库都没有钢片琴、竖笛以外的古乐器（鲁特琴等）。

## 9. 验证

- 单元：`SampleMapTest` 两层各核对一遍——模组本体每件采样乐器都有合成的表（`quality` 为 synth），资源包里至少 14 件是录音
  （`quality` 为 recorded，没有钢片琴）、`pack.mcmeta` 的格式是 26.1 的 84、带 `NOTICE.txt`。每张表：音域内每个音、每个力度都有
  正好覆盖它的区，变调不超过 4 个半音，音量在 (0, 1]，同一区里力度越大越响；文件、循环点、松键声都在；只有弓弦、管乐循环；
  大键琴每个音都有松键声。
- 生成脚本自己核对：每个采样实测的八度和预期一致（不一致会打印出来）；换层处的响度差（钢琴 0.34 dB，竖琴、巴松管各一个音约 1.9–2.0 dB，
  其余不到 1.2 dB）；`--demo` 渲染的音阶可人工试听。
- 音高（`tools/instrument_audit.py`，#243）：两层音源每件乐器，音域内每个音、每个力度都按游戏里同样的办法取区（变调 ≤ 4 个半音），
  每个采样的实际音高与表里记的差多少音分（持续音在循环段里量，揉弦不带偏；八度由合成的频率、库的 SFZ 定，不在这里判）。
  2026-11-11：29 项全部干净，最大 20 音分（小提琴揉弦、钢琴伸张调律）。管钟、定音鼓的分音不谐和，只核对取区。
- 循环接缝：解码写好的 ogg，绕回处那一步不大于两边 2400 个点里第 99 百分位的一步的 1.16 倍（没有咔嗒声）。
- 实际客户端：`concert_hall_pack_ui` 把仓库里的资源包装进测试客户端、启用：14 件采样乐器都读到录音、钢片琴仍合成，小提琴演奏条
  写着「音源：音乐厅录音」；卸下后全部回到合成。`handheld_play_ui` 按住长笛的一个音 7 秒，靠循环点一直响。
- 记录见 `VERIFICATION.md` 的「音乐厅音源资源包与第六轮乐器」一节。
