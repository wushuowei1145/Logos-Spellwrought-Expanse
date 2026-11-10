package dev.logos.dimension.companion;

/**
 * 白梅的装束（docs/SHIRAUME_COMPANION.md 第 8.10 节）。做法同神狐：一个模型里放所有装束的方块组，组名是
 * {@code outfit_<id>_<衣服骨骼>}（挂在原来的衣服骨骼下，动画照旧作用在那根骨骼上），渲染时隐藏别的装束的组（连同子骨骼）。
 * 模型 fox_model/nine_tailed_fox_companion 的 {@code _9}，浴衣是用户画的「梅灯夜渡」。
 */
public enum CompanionOutfit {
    /** 巫女服：平时、冒险。 */
    MIKO("miko"),
    /** 梅灯夜渡（浴衣）：泡温泉时换上，出浴后穿一阵（{@link #LINGER} 刻），一打起来就换回巫女服。 */
    UMEAKARI("umeakari");

    /** 出浴后浴衣穿多久（刻）。 */
    public static final int LINGER = 6000;
    public static final String PREFIX = "outfit_";

    public final String id;

    CompanionOutfit(String id) {
        this.id = id;
    }

    /** 骨骼 {@code bone} 是不是别的装束的组（这套装束下要隐藏）。 */
    public boolean hides(String bone) {
        return bone.startsWith(PREFIX) && !bone.startsWith(PREFIX + id + "_");
    }

    public static CompanionOutfit byId(String id) {
        for (CompanionOutfit o : values()) if (o.id.equals(id)) return o;
        return MIKO;
    }
}
