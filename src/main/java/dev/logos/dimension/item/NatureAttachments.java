package dev.logos.dimension.item;

import dev.logos.dimension.LogosDimension;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import java.util.function.Supplier;

/** 使用 NeoForge 的同步附件，避免向原版实体占用新的同步数据编号。 */
public final class NatureAttachments {
    public static final DeferredRegister<AttachmentType<?>> TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, LogosDimension.ID);
    public static final Supplier<AttachmentType<Integer>> ARROW_STAGE = TYPES.register("nature_arrow_stage",
            () -> AttachmentType.builder(() -> 0).sync(ByteBufCodecs.VAR_INT).build());
    public static final Supplier<AttachmentType<Integer>> CHARGE_LIMIT = TYPES.register("nature_charge_limit",
            () -> AttachmentType.builder(() -> 2).sync(ByteBufCodecs.VAR_INT).build());
    private NatureAttachments() {}
}
