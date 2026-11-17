package dev.logos.dimension.worldtree;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 世界树之弓的注册：拉弓状态组件、箭实体、锁定的音。物品本身在 {@link dev.logos.dimension.item.WorldtreeBowItem}。 */
public final class Worldtree {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, LogosDimension.ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<WorldtreeDraw>> DRAW = COMPONENTS.registerComponentType(
            "worldtree_draw", b -> b.networkSynchronized(WorldtreeDraw.STREAM_CODEC));
    public static final Supplier<EntityType<WorldtreeArrow>> ARROW = TYPES.register("worldtree_arrow", () ->
            EntityType.Builder.<WorldtreeArrow>of(WorldtreeArrow::new, MobCategory.MISC).noLootTable().sized(.5f, .5f).eyeHeight(.13f)
                    .clientTrackingRange(4).updateInterval(2)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("worldtree_arrow"))));
    /** 第 n 次锁定的音（tools/worldtree_chime.py 合成，C4 起按大调往上）。 */
    public static final List<Supplier<SoundEvent>> LOCK_SOUNDS = lockSounds();

    private static List<Supplier<SoundEvent>> lockSounds() {
        List<Supplier<SoundEvent>> out = new ArrayList<>();
        for (int i = 1; i <= WorldtreeAim.MAX_LOCKS; i++) {
            String path = "item.worldtree_bow.lock_" + (i < 10 ? "0" : "") + i;
            out.add(SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(LogosDimension.id(path))));
        }
        return List.copyOf(out);
    }

    /** 客户端按下左键（手里是世界树之弓）：开始横着拉弓。 */
    public record Sideways() implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        public static final Type<Sideways> TYPE = new Type<>(LogosDimension.id("worldtree_sideways"));
        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Sideways> CODEC =
                net.minecraft.network.codec.StreamCodec.unit(new Sideways());
        @Override public Type<Sideways> type() { return TYPE; }
    }

    public static void register(IEventBus bus) {
        bus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) -> event.registrar("1")
                .playToServer(Sideways.TYPE, Sideways.CODEC, (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer player)
                        dev.logos.dimension.item.WorldtreeBowItem.beginSideways(player);
                })));
        COMPONENTS.register(bus);
        TYPES.register(bus);
        SOUNDS.register(bus);
    }

    private Worldtree() {}
}
