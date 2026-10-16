package dev.logos.dimension.cloudsea;

import java.util.Locale;

/** Stable registry IDs retained from the prototype; geography now chooses temperate ecosystems. */
public enum CloudseaBiome {
    SILK_FOLDS("长风草原",0x839769,0x5B774A,Form.FOLD,150,27,.28),
    JADE_WHIRLS("山毛榉林",0x788B60,0x577246,Form.PETAL,164,25,.64),
    PEARL_BASINS("浅海沙洲",0xC6C0A3,0x6A8058,Form.BASIN,140,17,.15),
    REVERSE_GARDENS("北境白桦林",0x87956E,0x7D9860,Form.HANGING,160,33,.63),
    STAR_RIBBONS("缓流河谷",0x7C9669,0x648250,Form.RIBBON,162,22,.22),
    BLUE_BELL_STEPS("高地草甸",0x82916E,0x60784D,Form.STEPS,150,38,.42),
    OPAL_DUNES("白崖海岸",0xC5C3AE,0x69815A,Form.DUNE,148,24,.13),
    HALO_SPIRES("裸岩山脊",0x96958B,0x536751,Form.SPIRE,153,68,.21),
    MOON_FOLDS("古河峡谷",0x8E9380,0x596F4B,Form.FOLD,156,32,.35),
    VERDANT_WHIRLS("橡木林地",0x7C905E,0x4E713D,Form.PETAL,172,32,.52),
    GLASS_LAGOONS("静水湖泊",0x8C9C7A,0x638153,Form.BASIN,138,22,.23),
    LANTERN_SHELVES("幽深阔叶林",0x71865C,0x496C42,Form.HANGING,158,28,.58),
    DAWN_RIBBONS("旷野丘陵",0x8C9B70,0x6D8658,Form.RIBBON,165,33,.17),
    LILAC_STEPS("北境冷杉林",0x7F8E79,0x3F6354,Form.STEPS,155,43,.37),
    ROSE_DUNES("野花草甸",0x8F9D70,0x6A8552,Form.DUNE,147,29,.19),
    FROST_SPIRES("冰川雪岭",0xD5DAD8,0x77918B,Form.SPIRE,166,59,.16),
    ECHO_FOLDS("高山河谷",0x919A8D,0x58765B,Form.FOLD,166,39,.20),
    AMBER_WHIRLS("牧草坡地",0x91A174,0x738B53,Form.PETAL,155,21,.48),
    DREAM_BASINS("芦苇湿地",0x7B8D67,0x617A49,Form.BASIN,143,26,.38),
    CLOUDROOT_SHELVES("山地松林",0x7F8D79,0x456853,Form.HANGING,170,40,.43),
    TIDE_RIBBONS("南方海湾",0xB6BDAD,0x718B67,Form.RIBBON,159,35,.25),
    IVORY_STEPS("银桦疏林",0x91A17C,0x8DA16B,Form.STEPS,166,27,.31),
    AURORA_DUNES("寒带荒原",0xA1AA9B,0x677F67,Form.DUNE,158,31,.11),
    PALE_SPIRES("积雪峰顶",0xD9DEDC,0x81918B,Form.SPIRE,170,72,.18);

    public enum Form { FOLD, PETAL, BASIN, HANGING, RIBBON, STEPS, DUNE, SPIRE }
    public final String title; public final int groundColor, leafColor, base, relief; public final Form form; public final double flora;
    CloudseaBiome(String title,int ground,int leaf,Form form,int base,int relief,double flora) {
        this.title=title;groundColor=ground;leafColor=leaf;this.form=form;this.base=base;this.relief=relief;this.flora=flora;
    }
    public boolean conifer(){return this==LILAC_STEPS||this==CLOUDROOT_SHELVES||this==AURORA_DUNES;}
    public boolean birch(){return this==REVERSE_GARDENS||this==IVORY_STEPS;}
    public boolean barren(){return this==HALO_SPIRES||this==FROST_SPIRES||this==PALE_SPIRES||this==TIDE_RIBBONS||this==PEARL_BASINS||this==OPAL_DUNES;}
    public double treeDensity(){
        if(barren())return 0;
        if(conifer())return .68;
        if(this==JADE_WHIRLS||this==VERDANT_WHIRLS||this==LANTERN_SHELVES||birch())return .70;
        return this==SILK_FOLDS||this==ROSE_DUNES||this==DAWN_RIBBONS||this==AMBER_WHIRLS?.035:.15;
    }
    public String id() { return "cloudsea_"+name().toLowerCase(Locale.ROOT); }
}
