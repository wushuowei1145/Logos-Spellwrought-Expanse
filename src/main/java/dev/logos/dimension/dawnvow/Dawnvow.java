package dev.logos.dimension.dawnvow;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;


/**
 * 石中剑：曦誓插在山洞中央的石座里。
 *
 * <p>插着剑的石座生存模式无法破坏、像火把一样发光，并有一道斜光从上方落在剑上；
 * 只有在破晓、那道光最亮的时候才拔得出来，每位玩家一生只能从石中拔出一次（把剑插回任何空石座即解除誓约）。
 * 山洞藏在远行大陆的山体里，由 {@link DawnvowGrottoStructure} 选址开凿。
 */
public final class Dawnvow {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LogosDimension.ID);
    private static final DeferredRegister<StructureType<?>> STRUCTURES = DeferredRegister.create(Registries.STRUCTURE_TYPE, LogosDimension.ID);
    private static final DeferredRegister<StructurePieceType> PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE, LogosDimension.ID);

    /** 插着曦誓的石座：硬度 -1 与基岩相同，任何按硬度判断的破坏方式（爆炸、活塞、法术）都拿它没办法。 */
    public static final DeferredBlock<SwordStoneBlock> STONE = DimensionBlocks.BLOCKS.registerBlock("dawnvow_stone",
        p -> new SwordStoneBlock(p, true), p -> p.mapColor(MapColor.STONE).sound(SoundType.STONE).strength(-1f, 3600000f)
            .noLootTable().noOcclusion().lightLevel(s -> 14).pushReaction(PushReaction.BLOCK));
    /** 剑被拔走后的空石座：普通石头的硬度，镐子挖下来可以搬走，插回一把曦誓又变回上面那种。 */
    public static final DeferredBlock<SwordStoneBlock> EMPTY_STONE = DimensionBlocks.BLOCKS.registerBlock("dawnvow_stone_empty",
        p -> new SwordStoneBlock(p, false), p -> p.mapColor(MapColor.STONE).sound(SoundType.STONE).strength(1.5f, 6f)
            .requiresCorrectToolForDrops().noOcclusion());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SwordStoneBlockEntity>> STONE_ENTITY =
        BLOCK_ENTITIES.register("dawnvow_stone", () -> new BlockEntityType<>(SwordStoneBlockEntity::new, STONE.get()));

    public static final DeferredHolder<StructureType<?>, StructureType<DawnvowGrottoStructure>> GROTTO =
        STRUCTURES.register("dawnvow_grotto", () -> () -> DawnvowGrottoStructure.CODEC);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> GROTTO_PIECE =
        PIECES.register("dawnvow_grotto", () -> (StructurePieceType.ContextlessType) GrottoPiece::new);


    static {
        DimensionBlocks.ITEMS.registerSimpleBlockItem("dawnvow_stone", STONE);
        DimensionBlocks.ITEMS.registerSimpleBlockItem("dawnvow_stone_empty", EMPTY_STONE);
    }

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
        STRUCTURES.register(bus);
        PIECES.register(bus);
    }

    private Dawnvow() {
    }
}
