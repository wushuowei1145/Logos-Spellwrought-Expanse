package dev.logos.dimension.elven.blight;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 腐败精灵森林的材料、树心和女王。污染碎片仍使用 Logos 的已有物品。 */
public final class ElvenBlight {
    private ElvenBlight() {}
    public static final DeferredBlock<BlightMemoryBlock> MEMORY = DimensionBlocks.BLOCKS.registerBlock("blight_memory", BlightMemoryBlock::new,
            p -> p.mapColor(MapColor.COLOR_RED).strength(.8f).sound(SoundType.SCULK));
    public static final DeferredBlock<BlightNodeBlock> NODE = DimensionBlocks.BLOCKS.registerBlock("blight_node", BlightNodeBlock::new,
            p -> p.mapColor(MapColor.COLOR_RED).strength(4f, 1200f).sound(SoundType.SCULK_CATALYST).noOcclusion().lightLevel(s -> 6));
    public static final DeferredBlock<RotatedPillarBlock> WOOD = DimensionBlocks.BLOCKS.registerBlock("blighted_heartwood", RotatedPillarBlock::new,
            p -> p.mapColor(MapColor.COLOR_GRAY).strength(2.4f).sound(SoundType.WOOD));
    public static final DeferredBlock<Block> LEAVES = DimensionBlocks.BLOCKS.registerSimpleBlock("blighted_canopy",
            p -> p.mapColor(MapColor.COLOR_PURPLE).strength(.2f).noOcclusion().sound(SoundType.AZALEA_LEAVES));
    public static final DeferredBlock<BlightMemoryBlock> LOAM = DimensionBlocks.BLOCKS.registerBlock("blighted_loam", p -> new BlightMemoryBlock(p, 3, 1),
            p -> p.mapColor(MapColor.COLOR_GRAY).strength(.7f).sound(SoundType.ROOTED_DIRT));
    public static final DeferredBlock<Block> LUMEN = DimensionBlocks.BLOCKS.registerSimpleBlock("blighted_lumen",
            p -> p.mapColor(MapColor.COLOR_PURPLE).strength(.6f).sound(SoundType.SCULK).lightLevel(s -> 12));
    public static final DeferredBlock<CorruptionGrowthBlock> BUD = growth("corruption_bud");
    public static final DeferredBlock<CorruptionGrowthBlock> SPINES = growth("corruption_spines");
    public static final DeferredBlock<CorruptionGrowthBlock> RIBS = growth("corruption_ribs");
    public static final DeferredBlock<QueenHeartBlock> HEART = DimensionBlocks.BLOCKS.registerBlock("queen_heart", QueenHeartBlock::new,
            p -> p.mapColor(MapColor.COLOR_PURPLE).strength(-1, 3600000).noOcclusion().lightLevel(s -> s.getValue(QueenHeartBlock.DEFEATED) ? 2 : 8).sound(SoundType.SCULK_CATALYST));
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LogosDimension.ID);
    public static final Supplier<BlockEntityType<QueenHeart>> HEART_ENTITY = BLOCK_ENTITIES.register("queen_heart", () -> new BlockEntityType<>(QueenHeart::new, HEART.get()));
    public static final Supplier<BlockEntityType<BlightNode>> NODE_ENTITY = BLOCK_ENTITIES.register("blight_node", () -> new BlockEntityType<>(BlightNode::new, NODE.get()));
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);
    public static final Supplier<EntityType<CorruptedElfQueen>> QUEEN = ENTITIES.register("corrupted_elf_queen", () ->
            EntityType.Builder.of(CorruptedElfQueen::new, MobCategory.MONSTER).sized(1.45f, 4f).eyeHeight(3.3f).clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("corrupted_elf_queen"))));
    public static final Supplier<EntityType<QueenTether>> TETHER = ENTITIES.register("queen_tether", () ->
            EntityType.Builder.of(QueenTether::new, MobCategory.MISC).sized(1.4f, 1.6f).clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("queen_tether"))));

    private static DeferredBlock<CorruptionGrowthBlock> growth(String id) {
        return DimensionBlocks.BLOCKS.registerBlock(id, CorruptionGrowthBlock::new,
                p -> p.mapColor(MapColor.COLOR_PURPLE).strength(.4f).noOcclusion().noCollision().sound(SoundType.SCULK).lightLevel(s -> 2));
    }

    public static void register(IEventBus bus) {
        for (var block : new DeferredBlock<?>[] {WOOD, LEAVES, LOAM, LUMEN, BUD, SPINES, RIBS, HEART, MEMORY, NODE}) {
            DimensionBlocks.ITEMS.registerSimpleBlockItem(block.getId().getPath(), block);
        }
        QueenEntrances.register(bus);
        QueenEndings.register(bus);
        QueenSpellEffects.register(bus);
        BLOCK_ENTITIES.register(bus);
        ENTITIES.register(bus);
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(QUEEN.get(), CorruptedElfQueen.attributes().build()));
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(TETHER.get(), QueenTether.attributes().build()));
    }
}
