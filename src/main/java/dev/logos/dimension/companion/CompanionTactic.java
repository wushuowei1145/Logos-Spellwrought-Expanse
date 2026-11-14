package dev.logos.dimension.companion;

/**
 * 同伴的行动策略（对话「作战策略」里改，存在主人身上的契里，收回再叫出来也不变）。跟上 / 留在原地另算（{@code staying}）。
 *
 * <ul>
 *   <li>{@link #ADAPTIVE} 随机应变：远处放狐火，敌人贴近凝刀（原来的做法，默认）；
 *   <li>{@link #RANGED} 优先远程：只要狐火打得着就不拔刀，敌人追上来先退开；被贴到跟前、退不开才凝刀，拉开了马上收刀；
 *   <li>{@link #MELEE} 优先近战：看见要打的就凝刀走过去砍，敌人退远了也追；够不着的（飞着的、走不过去的）才放狐火；
 *   <li>{@link #PASSIVE} 不参与攻击：不打、不处决，躲在主人背后；照样替主人截箭、缠火、挡刀（挡下不反击）。
 *       主人用 Logos 的 {@code sic} 点名的目标还是会打：点名就是命令。
 * </ul>
 * 神狐一族不怕狐火，三种会打的策略对它们都照旧凝刀守着。
 */
public enum CompanionTactic {
    ADAPTIVE("adaptive"), RANGED("ranged"), MELEE("melee"), PASSIVE("passive");

    public final String id;

    CompanionTactic(String id) {
        this.id = id;
    }

    public static CompanionTactic byId(String id) {
        for (CompanionTactic t : values()) if (t.id.equals(id)) return t;
        return ADAPTIVE;
    }
}
