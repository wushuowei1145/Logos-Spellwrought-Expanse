package dev.logos.dimension.ultimate;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 玩家的档案里的最后一击（docs/ULTIMATES.md「玩家的最后一击」）：只在全员连携放完、切入换场以后才放得出来。
 * 每种武器大类（{@link FinisherTemplate}）一段脚本；没写的那一类落到空手。画面（客户端）在 {@code HeroCutin#finishers}。
 *
 * @param finishers 每种武器大类的脚本，必须有 {@link FinisherTemplate#UNARMED}
 */
public record HeroUltimate(Map<FinisherTemplate, Supplier<? extends UltimateScript>> finishers) {
    /** 现在只有空手一类：「星穹誓约 · 羁绊焚天炮」（{@link HeroFinisher}）。 */
    public static final HeroUltimate PLAYER = new HeroUltimate(Map.of(FinisherTemplate.UNARMED, HeroFinisher::new));

    public HeroUltimate {
        if (!finishers.containsKey(FinisherTemplate.UNARMED)) throw new IllegalArgumentException("最后一击必须有空手那一类");
        finishers = Map.copyOf(finishers);
    }

    /** 拿着 {@code template} 这一类时实际演哪一类：档案里有就是它，没有就是空手。 */
    public FinisherTemplate route(FinisherTemplate template) {
        return finishers.containsKey(template) ? template : FinisherTemplate.UNARMED;
    }

    /** 新开一段 {@code template}（已经 {@link #route} 过）的脚本。 */
    public UltimateScript script(FinisherTemplate template) {
        return finishers.get(route(template)).get();
    }
}
