package dev.logos.dimension.companion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 拉塞塔和你的日子（存在主人身上，跟着存档走，死了也带着）：哪天夸过她、哪天让她看过武器——每天头一次加羁绊，一天的事一天算，
 * 收回再叫出来也不清零。都按游戏日记（{@code getOverworldClockTime() / 24000}）。以后的阶段（巨龙形态的记忆……）往这里加字段。
 *
 * @param praiseDay   最近一次在对话里夸她、加了羁绊的那一天
 * @param critiqueDay 最近一次让她看武器、加了羁绊的那一天
 */
public record LacertaDays(long praiseDay, long critiqueDay) {
    public static final LacertaDays NONE = new LacertaDays(-1, -1);
    static final Codec<LacertaDays> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.optionalFieldOf("praise_day", -1L).forGetter(LacertaDays::praiseDay),
            Codec.LONG.optionalFieldOf("critique_day", -1L).forGetter(LacertaDays::critiqueDay)).apply(i, LacertaDays::new));

    LacertaDays withPraise(long day) {
        return new LacertaDays(day, critiqueDay);
    }

    LacertaDays withCritique(long day) {
        return new LacertaDays(praiseDay, day);
    }
}
