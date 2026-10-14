package dev.logos.dimension.cloudsea;

import java.util.Locale;

/** Shared, explicit biome identities: data, terrain, flora and visual tests use this catalogue. */
public enum CloudseaBiome {
    SILK_FOLDS("绸岩折原",0xBCCCCC,0x74AAA0,Form.FOLD,150,27,.28),
    JADE_WHIRLS("青玉旋庭",0x83B4AA,0xC2DCC0,Form.PETAL,164,25,.64),
    PEARL_BASINS("珠光浅湾",0xCED8CE,0x8DAAC0,Form.BASIN,140,17,.15),
    REVERSE_GARDENS("倒生花园",0xAAC6B1,0xB6DCCB,Form.HANGING,160,33,.63),
    STAR_RIBBONS("星流长带",0xA0B8CC,0x76B8C3,Form.RIBBON,162,22,.22),
    BLUE_BELL_STEPS("蓝铃阶地",0x9DAEC4,0xB1C3DF,Form.STEPS,150,38,.42),
    OPAL_DUNES("欧泊潮丘",0xDDCBC1,0xB9C7DC,Form.DUNE,148,24,.13),
    HALO_SPIRES("光冠峰林",0xC8D1C1,0xE1D6AA,Form.SPIRE,153,68,.21),
    MOON_FOLDS("月纱褶谷",0xB3BBCB,0xCBC4DE,Form.FOLD,156,32,.35),
    VERDANT_WHIRLS("翠羽盘原",0x7EAA97,0xA3C99B,Form.PETAL,172,32,.52),
    GLASS_LAGOONS("静镜水庭",0x91BBC7,0xBAD9D7,Form.BASIN,138,22,.23),
    LANTERN_SHELVES("灯蕊悬台",0xC5C7A4,0xD7C998,Form.HANGING,158,28,.58),
    DAWN_RIBBONS("曦光绸径",0xCFC3AD,0xD8C69D,Form.RIBBON,165,33,.17),
    LILAC_STEPS("丁香叠界",0xB8AFC3,0xC8B6D4,Form.STEPS,155,43,.37),
    ROSE_DUNES("玫辉波原",0xCAAEB0,0xD3B8AD,Form.DUNE,147,29,.19),
    FROST_SPIRES("霜羽高脊",0xC1D5D6,0xA8C9D5,Form.SPIRE,166,59,.16),
    ECHO_FOLDS("回音悬谷",0xA6B5B6,0x9AC9C0,Form.FOLD,166,39,.20),
    AMBER_WHIRLS("蜜光花盘",0xCEC49F,0xDECE9E,Form.PETAL,155,21,.48),
    DREAM_BASINS("梦蓝浮泽",0x96ABCB,0xAABBDD,Form.BASIN,143,26,.38),
    CLOUDROOT_SHELVES("云根垂野",0xACBCAF,0x82B49E,Form.HANGING,170,40,.43),
    TIDE_RIBBONS("听潮浮径",0x8EAFC0,0xA1C8D0,Form.RIBBON,159,35,.25),
    IVORY_STEPS("象牙层园",0xD8D4C4,0xBCCCA3,Form.STEPS,166,27,.31),
    AURORA_DUNES("极光砂幕",0xB6C8CD,0xAFD1CA,Form.DUNE,158,31,.11),
    PALE_SPIRES("白羽天柱",0xCFD4D0,0xB6D2C4,Form.SPIRE,170,72,.18);

    public enum Form { FOLD, PETAL, BASIN, HANGING, RIBBON, STEPS, DUNE, SPIRE }
    public final String title; public final int groundColor, leafColor, base, relief; public final Form form; public final double flora;
    CloudseaBiome(String title,int ground,int leaf,Form form,int base,int relief,double flora) {
        this.title=title;groundColor=ground;leafColor=leaf;this.form=form;this.base=base;this.relief=relief;this.flora=flora;
    }
    public String id() { return "cloudsea_"+name().toLowerCase(Locale.ROOT); }
}
