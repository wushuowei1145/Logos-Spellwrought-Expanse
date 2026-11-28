package dev.logos.dimension.ultimate;

import org.jspecify.annotations.Nullable;

/**
 * 一段奥义在服务端怎么演（docs/ULTIMATES.md「脚本」）：每次发动新开一个，框架每刻调一次 {@link #tick}，到 {@link #length} 刻结束。
 * 同伴的奥义和玩家的最后一击都是它。
 *
 * <p>演的这段时间里同伴的心智停着（不会自己跑开、换目标、出别的招），动作、位置、特效、镜头、伤害都由脚本管；
 * 伤害走 {@link UltimateCast#hit}，连携倍率和前面的人留下的接力馈赠才算得上。画面里只在客户端做的部分（后期、贴图特效）
 * 写在客户端的 {@code UltimateShow}，开演时框架把这一次的信息发给看得见的玩家。
 */
public interface UltimateScript {
    /** 一共演多少刻。 */
    int length();

    /** 第几刻出手：接力窗口从这一刻起算（不是按键的那一刻，免得前摇和跑位吃掉窗口），馈赠也在这一刻交出去。 */
    int impact();

    /** 开演（第 0 刻，在 {@code tick(0)} 之前）：换表情、拿武器、看向目标。 */
    default void start(UltimateCast cast) {}

    /** 第 {@code t} 刻（0 到 {@code length - 1}）。出手那一刻（{@link #impact}）框架先交出馈赠、刷新窗口，再调这里。 */
    void tick(UltimateCast cast, int t);

    /**
     * 演完（{@code interrupted} 为假）或被打断（她倒下、被收回，主人下线、死亡、换世界）。
     * 收尾的东西放这里：撤掉临时属性、把她摆回能交给心智的样子。
     */
    default void end(UltimateCast cast, boolean interrupted) {}

    /** 出手那一刻交给后面的接力馈赠（规范第四节）；没有就是 null。 */
    default @Nullable RelayGift gift(UltimateCast cast) {
        return null;
    }
}
