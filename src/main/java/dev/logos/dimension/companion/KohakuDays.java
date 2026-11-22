package dev.logos.dimension.companion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.GlobalPos;

/**
 * 琥珀和你的日子（存在主人身上，跟着存档走，死了也带着）：她的专属地毯在哪、哪天在那里睡着过、今天塞给你几次吃的、
 * 哪天帮你看过载页、哪天收过你的零花钱。都按游戏日记（{@code getOverworldClockTime() / 24000}），一天的事一天算，重新叫她出来也不清零。
 *
 * @param rug          她认下的那块地毯（第一次坐上去时记下）；地毯被拿走了就忘掉
 * @param rugNapDay    最近一次在地毯上睡着、加了羁绊的那一天
 * @param foodDay      最近一次塞给你吃的那一天；{@code foods} 是那天塞了几次
 * @param critiqueDay  最近一次帮你看载页、加了羁绊的那一天
 * @param allowanceDay 最近一次收你零花钱的那一天（第二天早上的礼物里稀有的那一档翻倍，第五步）
 */
public record KohakuDays(Optional<GlobalPos> rug, long rugNapDay, long foodDay, int foods, long critiqueDay, long allowanceDay) {
    public static final KohakuDays NONE = new KohakuDays(Optional.empty(), -1, -1, 0, -1, -1);
    static final Codec<KohakuDays> CODEC = RecordCodecBuilder.create(i -> i.group(
            GlobalPos.CODEC.optionalFieldOf("rug").forGetter(KohakuDays::rug),
            Codec.LONG.optionalFieldOf("rug_nap_day", -1L).forGetter(KohakuDays::rugNapDay),
            Codec.LONG.optionalFieldOf("food_day", -1L).forGetter(KohakuDays::foodDay),
            Codec.INT.optionalFieldOf("foods", 0).forGetter(KohakuDays::foods),
            Codec.LONG.optionalFieldOf("critique_day", -1L).forGetter(KohakuDays::critiqueDay),
            Codec.LONG.optionalFieldOf("allowance_day", -1L).forGetter(KohakuDays::allowanceDay)).apply(i, KohakuDays::new));

    KohakuDays withRug(Optional<GlobalPos> at) {
        return new KohakuDays(at, rugNapDay, foodDay, foods, critiqueDay, allowanceDay);
    }

    KohakuDays withRugNap(long day) {
        return new KohakuDays(rug, day, foodDay, foods, critiqueDay, allowanceDay);
    }

    /** 今天塞过几次吃的（不是今天的记录就是 0）。 */
    int foodsOn(long day) {
        return foodDay == day ? foods : 0;
    }

    KohakuDays withFood(long day) {
        return new KohakuDays(rug, rugNapDay, day, foodsOn(day) + 1, critiqueDay, allowanceDay);
    }

    KohakuDays withCritique(long day) {
        return new KohakuDays(rug, rugNapDay, foodDay, foods, day, allowanceDay);
    }

    KohakuDays withAllowance(long day) {
        return new KohakuDays(rug, rugNapDay, foodDay, foods, critiqueDay, day);
    }
}
