package dev.logos.dimension.ultimate;

import java.util.function.Supplier;

/**
 * 一位同伴的奥义（docs/ULTIMATES.md）：写在她的档案 {@link dev.logos.dimension.companion.CompanionDef#ultimate} 里。
 * 名字是 {@code ultimate.logos_dimension.<同伴>.name}；发动、接力、缺魔时她说的话是
 * {@code line.logos_dimension.<同伴>.ultimate_cast}、{@code ultimate_relay}、{@code ultimate_no_mana}。
 * 画面（客户端那一半）写在她的 {@code CompanionStyle#ultimate}。
 *
 * <p>两个版本（docs/ULTIMATES.md「过场」）：单人奥义和连携里的第一位演完整版（最长 {@link #MAX_FULL} 刻）；
 * 连携里第二位起演缩短版（最长 {@link #MAX_CONDENSED} 刻），免得四人满连携看三十秒过场。两版的伤害和馈赠一样。
 *
 * @param cooldown  冷却（刻）。记在主人身上（{@link UltimateCooldowns}），收回再叫也不清
 * @param mana      基准耗魔（主人的 Logos 魔力）；连携里第几段打几折见 {@link UltimateChain#manaFactor}
 * @param script    完整版：每次发动新开一段
 * @param condensed 缩短版
 */
public record UltimateDef(int cooldown, int mana, Supplier<? extends UltimateScript> script, Supplier<? extends UltimateScript> condensed) {
    /** 完整版最长多少刻（5.5 秒）。玩家的最后一击不受这个限制。 */
    public static final int MAX_FULL = 110;
    /** 缩短版最长多少刻（约 2 秒）。 */
    public static final int MAX_CONDENSED = 44;

    /** 两版是同一段（占位的那一段）。 */
    public UltimateDef(int cooldown, int mana, Supplier<? extends UltimateScript> script) {
        this(cooldown, mana, script, script);
    }

    /** 第 {@code stage} 段演缩短版：连携里第二位起（玩家的最后一击总是完整的）。 */
    public static boolean condensed(UltimateChain.Stage stage) {
        return !stage.finisher() && stage.index() >= 1;
    }

    /** 新开一段第 {@code stage} 段该演的那一版。 */
    public UltimateScript script(UltimateChain.Stage stage) {
        return (condensed(stage) ? condensed : script).get();
    }
}
