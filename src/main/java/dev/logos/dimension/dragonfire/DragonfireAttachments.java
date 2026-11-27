package dev.logos.dimension.dragonfire;

import java.util.function.Supplier;

import com.mojang.serialization.Codec;
import dev.logos.dimension.LogosDimension;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** 龙炎挂在玩家身上的东西。龙炎缠身和缠绕借用狐火的（{@code FoxfireAttachments}，颜色是龙炎）。 */
public final class DragonfireAttachments {
    static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LogosDimension.ID);

    /** 学会了龙炎之言：自己写的术式里可以写 {@code dragonfire}。死了也记得，只同步给本人（编纂台画卡片用）。 */
    public static final Supplier<AttachmentType<Boolean>> KNOWN = TYPES.register("dragonfire_known",
            () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL.fieldOf("known")).copyOnDeath()
                    .sync((holder, to) -> holder == to, ByteBufCodecs.BOOL).build());

    private DragonfireAttachments() {}
}
