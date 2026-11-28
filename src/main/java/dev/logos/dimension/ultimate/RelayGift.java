package dev.logos.dimension.ultimate;

import net.minecraft.server.level.ServerLevel;

/**
 * 接力馈赠（docs/ULTIMATES.md「接力馈赠」）：一段奥义出手时交给后面的人。
 * 交出时调 {@link #handed}；之后的每一段（同伴的奥义、玩家的最后一击）算耗魔、算威力、打中目标时都问它一遍；
 * 每段演完问 {@link #keep} 还留不留。连携结束（放完最后一击、窗口超时、主人下线）时调 {@link #cleared} 并全部清掉。
 *
 * <p>馈赠自己记着是哪一段交出来的（建它的时候从 {@link UltimateCast#stage()} 拿），只想管紧接着的下一段时就比一下段号。
 */
public interface RelayGift {
    /** 交出去的那一刻（炼金回流：这时返还魔力）。 */
    default void handed(UltimateCast from) {}

    /** 第 {@code stage} 段要花的魔力乘多少（森之加护：0.75）。只在下令时问。 */
    default double manaFactor(UltimateChain.Stage stage) {
        return 1;
    }

    /** 第 {@code stage} 段的威力乘多少（森之加护：1.3）。 */
    default double power(UltimateChain.Stage stage) {
        return 1;
    }

    /** 某一段打中一下：改伤害、加穿甲，顺手放特效（狐火蚀骨 +25% 并二次引爆、龙炎碎甲穿 40% 护甲）。 */
    default void hit(UltimateCast cast, UltimateHit hit) {}

    /** 第 {@code done} 段演完以后还留不留。默认只管交出它之后的那一段。 */
    default boolean keep(UltimateChain.Stage done) {
        return false;
    }

    /** 连携结束、清掉的时候（撤掉目标身上的印记）。 */
    default void cleared(ServerLevel level) {}
}
