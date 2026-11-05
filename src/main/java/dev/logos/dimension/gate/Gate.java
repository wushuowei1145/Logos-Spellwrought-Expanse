package dev.logos.dimension.gate;

import dev.logos.dimension.LogosDimension;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 远行之门：主世界与远行大陆之间的传送门。
 *
 * <p>玩家给门框地面中心的启门石（{@link GateStoneBlock}）注入 1000 点魔力，门洞（{@link GateShape} 泛洪找出来）
 * 就会填满传送面（{@link GatePortalBlock}）。实体穿过后由 {@link GateTravel} 找到或生成另一侧的门。
 */
public final class Gate {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(LogosDimension.ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LogosDimension.ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LogosDimension.ID);
    private static final DeferredRegister<PoiType> POI = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, LogosDimension.ID);

    /** 各充能档的亮度：灌得越满越亮。 */
    private static final int[] STONE_LIGHT = {0, 3, 5, 7, 10};

    public static final DeferredBlock<GateStoneBlock> GATE_STONE = BLOCKS.registerBlock("gate_stone", GateStoneBlock::new,
            p -> p.mapColor(MapColor.STONE).strength(3f, 6f).requiresCorrectToolForDrops().sound(SoundType.DEEPSLATE_BRICKS)
                    .lightLevel(s -> STONE_LIGHT[s.getValue(GateStoneBlock.CHARGE)]));
    public static final DeferredBlock<GatePortalBlock> GATE_PORTAL = BLOCKS.registerBlock("gate_portal", GatePortalBlock::new,
            p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE).noCollision().noOcclusion().noLootTable().strength(-1f, 3600000f)
                    .sound(SoundType.GLASS).lightLevel(s -> 11).pushReaction(PushReaction.BLOCK));
    static {
        ITEMS.registerSimpleBlockItem("gate_stone", GATE_STONE);
    }
    public static final Supplier<BlockEntityType<GateStoneBlockEntity>> GATE_STONE_BE = BLOCK_ENTITIES.register("gate_stone",
            () -> new BlockEntityType<>(GateStoneBlockEntity::new, GATE_STONE.get()));
    /** 传送面的每一格都是兴趣点，和下界传送门一样靠它在另一侧找门。 */
    public static final DeferredHolder<PoiType, PoiType> GATE_POI = POI.register("gate_portal",
            () -> new PoiType(Set.copyOf(GATE_PORTAL.get().getStateDefinition().getPossibleStates()), 0, 1));

    private Gate() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        POI.register(bus);
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(GateIgnitionPayload.TYPE, GateIgnitionPayload.CODEC));
    }
}
