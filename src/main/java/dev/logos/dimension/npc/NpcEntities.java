package dev.logos.dimension.npc;

import java.util.function.Supplier;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 附属模组的 NPC 实体、刷怪蛋与相关事件。 */
public final class NpcEntities {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);

    /** 显示高度：发顶约 1.51 格、眼睛约 1.26 格（2 倍制作、渲染缩放 0.5）。 */
    public static final Supplier<EntityType<ForestElf>> FOREST_ELF = TYPES.register("forest_elf", () ->
            EntityType.Builder.of(ForestElf::new, MobCategory.CREATURE).sized(0.6f, 1.5f).eyeHeight(1.26f).clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("forest_elf"))));

    /** 刷怪蛋；物品注册在实体之后，属性里直接取实体类型。 */
    public static final DeferredItem<SpawnEggItem> FOREST_ELF_SPAWN_EGG = DimensionBlocks.ITEMS.registerItem("forest_elf_spawn_egg",
            SpawnEggItem::new, p -> p.spawnEgg(FOREST_ELF.get()));

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        ElfSounds.register(bus);
        bus.addListener(NpcEntities::attributes);
        bus.addListener(NpcEntities::creativeTab);
        NeoForge.EVENT_BUS.addListener(ElfFactions::onJoin);
    }

    private static void attributes(EntityAttributeCreationEvent event) {
        event.put(FOREST_ELF.get(), ForestElf.createAttributes().build());
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) event.accept(FOREST_ELF_SPAWN_EGG.get());
    }

    private NpcEntities() {}
}
