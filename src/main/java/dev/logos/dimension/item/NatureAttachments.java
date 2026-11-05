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
    public static final Supplier<AttachmentType<SproutMark>> SPROUT_MARK = TYPES.register("sprout_mark",
            () -> AttachmentType.builder(() -> SproutMark.EMPTY)
                    .serialize(SproutMark.CODEC, mark -> mark.expiresAt() > 0)
                    .sync(SproutMark.STREAM_CODEC).build());
    /** 和弓射出的箭（YumiItem）：客户端画成和弓的矢；存档时保留，插在地上的箭重进世界后仍是矢。 */
    public static final Supplier<AttachmentType<Boolean>> YUMI_ARROW = TYPES.register("yumi_arrow",
            () -> AttachmentType.builder(() -> false).serialize(com.mojang.serialization.Codec.BOOL.fieldOf("marked"), marked -> marked)
                    .sync(ByteBufCodecs.BOOL).build());
    private NatureAttachments() {}
}
