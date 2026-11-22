package dev.logos.dimension.companion;

/**
 * 琥珀的四套衣服（docs/KOHAKU_COMPANION.md 第 19 节）。同白梅的装束（{@link CompanionOutfit}）：四套都在一个模型里，组名是
 * {@code outfit_<id>_<骨骼>}，渲染时隐藏别的几套（连同子骨骼）。对话里「换身衣服吧」换，记在你身上（{@link KohakuDays#outfit}），
 * 收回再叫出来、换维度、读档都还是这一套。
 */
public enum KohakuOutfit {
    /** 开衫制服（默认）。 */
    CARDIGAN("cardigan"),
    /** 雏菊散步。 */
    DAISY("daisy"),
    /** 天鹅绒小夜曲。 */
    VELVET("velvet"),
    /** 泳装。 */
    SWIMSUIT("swimsuit");

    public final String id;

    KohakuOutfit(String id) {
        this.id = id;
    }

    /** 骨骼 {@code bone} 是不是别的几套的组（穿这一套时要隐藏）。 */
    public boolean hides(String bone) {
        return bone.startsWith(CompanionOutfit.PREFIX) && !bone.startsWith(CompanionOutfit.PREFIX + id + "_");
    }

    public static KohakuOutfit byId(String id) {
        for (KohakuOutfit o : values()) if (o.id.equals(id)) return o;
        return CARDIGAN;
    }
}
