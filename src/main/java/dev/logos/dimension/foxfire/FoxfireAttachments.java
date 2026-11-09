package dev.logos.dimension.foxfire;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jspecify.annotations.Nullable;

/** 狐火挂在生物身上的两样东西，各端都看得到（客户端按它画）。 */
public final class FoxfireAttachments {
    static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LogosDimension.ID);

    static final Codec<FoxfireFlame> FLAME = Codec.STRING.xmap(
            s -> {
                for (FoxfireFlame f : FoxfireFlame.values()) if (f.name().toLowerCase(Locale.ROOT).equals(s)) return f;
                return FoxfireFlame.KITSUNE;
            },
            f -> f.name().toLowerCase(Locale.ROOT));
    static final StreamCodec<ByteBuf, FoxfireFlame> FLAME_STREAM = ByteBufCodecs.VAR_INT.map(FoxfireFlame::byId, Enum::ordinal);

    /**
     * 手上的魔力缠绕是狐火：颜色、和 Logos 那条缠绕同一个结束时刻（对不上就是后来换了别的缠绕）、
     * 是不是附属给的普通缠绕（{@link FoxfireWraps#wrap}，每下附带固定的伤害）。存档：Logos 的普通缠绕也存档。
     */
    public record Wrap(FoxfireFlame flame, long until, boolean plain) {
        public static final Wrap NONE = new Wrap(FoxfireFlame.KITSUNE, 0, false);
        static final MapCodec<Wrap> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                FLAME.fieldOf("flame").forGetter(Wrap::flame),
                Codec.LONG.fieldOf("until").forGetter(Wrap::until),
                Codec.BOOL.fieldOf("plain").forGetter(Wrap::plain)).apply(i, Wrap::new));
        static final StreamCodec<ByteBuf, Wrap> STREAM = StreamCodec.composite(
                FLAME_STREAM, Wrap::flame, ByteBufCodecs.VAR_LONG, Wrap::until, ByteBufCodecs.BOOL, Wrap::plain, Wrap::new);
    }

    /** 狐火缠身：颜色、烧到哪一刻。谁点的火只在服务端记（客户端拿到的是 null）；不存档，几秒就灭。 */
    public record Burn(FoxfireFlame flame, long until, @Nullable UUID burner) {
        public static final Burn NONE = new Burn(FoxfireFlame.KITSUNE, 0, null);
        static final StreamCodec<ByteBuf, Burn> STREAM = StreamCodec.composite(
                FLAME_STREAM, Burn::flame, ByteBufCodecs.VAR_LONG, Burn::until, (flame, until) -> new Burn(flame, until, null));
    }

    public static final Supplier<AttachmentType<Wrap>> WRAP = TYPES.register("foxfire_wrap",
            () -> AttachmentType.builder(() -> Wrap.NONE).serialize(Wrap.CODEC).sync(Wrap.STREAM).build());
    public static final Supplier<AttachmentType<Burn>> BURN = TYPES.register("foxfire_burn",
            () -> AttachmentType.builder(() -> Burn.NONE).sync(Burn.STREAM).build());

    private FoxfireAttachments() {}
}
