package dev.logos.dimension.npc;

import java.util.function.Supplier;

import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 附属模组的 NPC 实体。 */
public final class NpcEntities {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);

    /** 显示高度：发顶约 1.51 格、眼睛约 1.26 格（2 倍制作、渲染缩放 0.5）。 */
    public static final Supplier<EntityType<ForestElf>> FOREST_ELF = TYPES.register("forest_elf", () ->
            EntityType.Builder.of(ForestElf::new, MobCategory.CREATURE).sized(0.6f, 1.5f).eyeHeight(1.26f).clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("forest_elf"))));

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        bus.addListener(NpcEntities::attributes);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(FOREST_ELF.get(), ForestElf.createAttributes().build());
    }

    private NpcEntities() {}
}
