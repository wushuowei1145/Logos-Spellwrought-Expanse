package dev.logos.dimension.firearm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.item.PageTier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 刻在一发弹药上的术式（物品组件 {@code logos_dimension:etched_spell}，docs/FIREARMS.md 第 9 节）：
 * 图纸上的术式原文、蚀刻的档位（1–7，对应 Logos 载页的基础到贤者）和每发注入的魔力。
 * 同一张图纸、同一档刻出来的弹组件完全一样，所以能叠在一起、装进同一个转轮。
 */
public record EtchedSpell(String source, int tier, double mana) {
    public static final Codec<EtchedSpell> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("source").forGetter(EtchedSpell::source),
            Codec.intRange(1, RoundEtchRecipe.TIERS).fieldOf("tier").forGetter(EtchedSpell::tier),
            Codec.doubleRange(0, 1e6).fieldOf("mana").forGetter(EtchedSpell::mana)
    ).apply(i, EtchedSpell::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, EtchedSpell> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, EtchedSpell::source,
            ByteBufCodecs.VAR_INT, EtchedSpell::tier,
            ByteBufCodecs.DOUBLE, EtchedSpell::mana,
            EtchedSpell::new);

    /** 这一档对应的 Logos 载页品级：术式被掐灭时剩下的魔力按它的回收比例放出来。 */
    public PageTier pageTier() {
        return PageTier.values()[Math.clamp(tier - 1, 0, PageTier.values().length - 1)];
    }
}
