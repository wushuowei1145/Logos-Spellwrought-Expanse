package dev.logos.dimension.instance;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The identity travels with the cauldron when it is mined and placed again. */
public final class CauldronBinding {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LogosDimension.ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> WORLD_ID = COMPONENTS.registerComponentType(
            "cauldron_world", builder -> builder.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BoundCauldronBlockEntity>> BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "alchemical_cauldron", () -> new BlockEntityType<>(BoundCauldronBlockEntity::new, DimensionBlocks.ALCHEMICAL_CAULDRON.get()));
    private record Expiry(ItemEntity item, UUID worldId) {}
    private static final List<Expiry> EXPIRING = new ArrayList<>();

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
        BLOCK_ENTITIES.register(bus);
        NeoForge.EVENT_BUS.addListener(CauldronBinding::expire);
        NeoForge.EVENT_BUS.addListener(CauldronBinding::afterTick);
    }

    private static void expire(ItemExpireEvent event) {
        var item = event.getEntity();
        UUID id = item.getItem().get(WORLD_ID.get());
        if (id != null && item.getItem().getItem() instanceof BoundCauldronItem) EXPIRING.add(new Expiry(item, id));
    }

    private static void afterTick(ServerTickEvent.Post event) {
        // Inspect the result after all expiry listeners; other mods may extend the lifespan.
        var iterator = EXPIRING.iterator();
        while (iterator.hasNext()) {
            var expiry = iterator.next();
            var item = expiry.item();
            if (item.level().getServer() != event.getServer()) continue;
            iterator.remove();
            if (item.getRemovalReason() == Entity.RemovalReason.DISCARDED
                    && expiry.worldId().equals(item.getItem().get(WORLD_ID.get()))
                    && item.getAge() >= item.lifespan) {
                CauldronWorlds.requestDestroy(event.getServer(), expiry.worldId());
            }
        }
    }

    private CauldronBinding() {}
}
