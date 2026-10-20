# 对应树叶的原木与叶心填充

0.7.0 为 32 种既有树叶各增加对应原木。白桦纸白树皮、山毛榉平滑灰皮与愈合疤、松杉深色鳞裂、橡木纵裂、湿地银灰苔痕、海岸淡色风化等采用独立纹理。每种含三套 64×64 树皮、独立年轮和三份模型，轴向支持 X/Y/Z，正常使用原版原木与斧标签。

原木主体完整封闭，浅层树皮浮起细节不挖空主体，每份模型 16 面。128 份四季叶模型补入中央三向叶簇，每份 14 面；叶簇内部贴图覆盖率约 73%–98%，保留少量自然透空。相邻同类叶块仍剔除内部面，没有方块实体、逐树渲染或新的后台线程。

原版数据包中 26 种有树群系的 trunk_provider 改用对应原木，foliage_provider 保持对应树叶。新生成区块使用新树干；已经生成的原版橡木/白桦/云杉不会被扫描替换。既有季节叶直接更新外观，旧 cloudsea_stem 更新为山毛榉外观以兼容旧存档。

| 既有树叶名称 | 原木 ID（命名空间 logos_dimension） |
| --- | --- |
| 牧草坡地树叶 | `cloudsea_amber_whirls_log` |
| 古冠山毛榉林树叶 | `cloudsea_ancient_beech_log` |
| 寒带荒原树叶 | `cloudsea_aurora_dunes_log` |
| 高地草甸树叶 | `cloudsea_blue_bell_steps_log` |
| 山地松林树叶 | `cloudsea_cloudroot_shelves_log` |
| 铜叶林丘树叶 | `cloudsea_copper_copse_log` |
| 旷野丘陵树叶 | `cloudsea_dawn_ribbons_log` |
| 芦苇湿地树叶 | `cloudsea_dream_basins_log` |
| 高山河谷树叶 | `cloudsea_echo_folds_log` |
| 翳蕨密林树叶 | `cloudsea_fernwood_log` |
| 杉影深林树叶 | `cloudsea_fir_heart_log` |
| 冰川雪岭树叶 | `cloudsea_frost_spires_log` |
| 静水湖泊树叶 | `cloudsea_glass_lagoons_log` |
| 裸岩山脊树叶 | `cloudsea_halo_spires_log` |
| 银桦疏林树叶 | `cloudsea_ivory_steps_log` |
| 山毛榉林树叶 | `cloudsea_jade_whirls_log` |
| 幽深阔叶林树叶 | `cloudsea_lantern_shelves_log` |
| 北境冷杉林树叶 | `cloudsea_lilac_steps_log` |
| 月露白桦林树叶 | `cloudsea_moon_birch_log` |
| 古河峡谷树叶 | `cloudsea_moon_folds_log` |
| 雾苔溪林树叶 | `cloudsea_moss_vale_log` |
| 白崖海岸树叶 | `cloudsea_opal_dunes_log` |
| 积雪峰顶树叶 | `cloudsea_pale_spires_log` |
| 浅海沙洲树叶 | `cloudsea_pearl_basins_log` |
| 北境白桦林树叶 | `cloudsea_reverse_gardens_log` |
| 野花草甸树叶 | `cloudsea_rose_dunes_log` |
| 长风草原树叶 | `cloudsea_silk_folds_log` |
| 缓流河谷树叶 | `cloudsea_star_ribbons_log` |
| 星铃花甸树叶 | `cloudsea_starflower_glade_log` |
| 曦光林隙树叶 | `cloudsea_sunlit_glade_log` |
| 南方海湾树叶 | `cloudsea_tide_ribbons_log` |
| 橡木林地树叶 | `cloudsea_verdant_whirls_log` |

验证：12 项单元测试、31 项 GameTest 通过。新增世界检查覆盖 32 种原木的注册、标签、三轴和对对应叶块的原版距离支撑；实际生成森林检查新原木和对应叶块。实际客户端 tree_families 为 5 场景、16 检查通过，前四个是摆放的纹理/模型画廊，最后一个为自然生成森林。截图见 screenshots/tree-families/。未验证第三方光影或硬件 FPS。
