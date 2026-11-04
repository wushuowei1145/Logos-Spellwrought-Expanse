package dev.logos.dimension.cloudsea;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 远行大陆的水下植物：海草、高海草、萤光海草、海带，以及它们的水下生成特征。 */
public final class AquaticFlora {
    private static final DeferredRegister<Feature<?>> FEATURES =
        DeferredRegister.create(Registries.FEATURE, LogosDimension.ID);

    public static final DeferredBlock<Block> SEAGRASS = DimensionBlocks.BLOCKS.registerBlock(
        "journey_seagrass",
        JourneySeagrassBlock::new,
        p -> seagrass(p));
    public static final DeferredBlock<Block> TALL_SEAGRASS = DimensionBlocks.BLOCKS.registerBlock(
        "journey_tall_seagrass",
        JourneyTallSeagrassBlock::new,
        p -> seagrass(p).offsetType(BlockBehaviour.OffsetType.XZ));
    public static final DeferredBlock<Block> GLOW_SEAGRASS = DimensionBlocks.BLOCKS.registerBlock(
        "journey_glow_seagrass",
        JourneySeagrassBlock::new,
        p -> seagrass(p).lightLevel(s -> 4));
    public static final DeferredBlock<Block> KELP = DimensionBlocks.BLOCKS.registerBlock(
        "journey_kelp",
        JourneyKelpBlock::new,
        p -> kelp(p, true));
    public static final DeferredBlock<Block> KELP_PLANT = DimensionBlocks.BLOCKS.registerBlock(
        "journey_kelp_plant",
        JourneyKelpPlantBlock::new,
        p -> kelp(p, false));

    public static final DeferredItem<net.minecraft.world.item.BlockItem> SEAGRASS_ITEM =
        DimensionBlocks.ITEMS.registerSimpleBlockItem("journey_seagrass", SEAGRASS);
    public static final DeferredItem<net.minecraft.world.item.BlockItem> GLOW_SEAGRASS_ITEM =
        DimensionBlocks.ITEMS.registerSimpleBlockItem("journey_glow_seagrass", GLOW_SEAGRASS);
    public static final DeferredItem<net.minecraft.world.item.BlockItem> KELP_ITEM =
        DimensionBlocks.ITEMS.registerSimpleBlockItem("journey_kelp", KELP);

    public static final DeferredHolder<Feature<?>, JourneySeagrassFeature> SEAGRASS_FEATURE =
        FEATURES.register("journey_seagrass", JourneySeagrassFeature::new);
    public static final DeferredHolder<Feature<?>, JourneyGlowSeagrassFeature> GLOW_SEAGRASS_FEATURE =
        FEATURES.register("journey_glow_seagrass", JourneyGlowSeagrassFeature::new);
    public static final DeferredHolder<Feature<?>, JourneyKelpFeature> KELP_FEATURE =
        FEATURES.register("journey_kelp", JourneyKelpFeature::new);
    public static final DeferredHolder<Feature<?>, JourneyRockPileFeature> ROCK_PILE_FEATURE =
        FEATURES.register("journey_rock_pile", JourneyRockPileFeature::new);

    private static BlockBehaviour.Properties seagrass(BlockBehaviour.Properties p) {
        return p.mapColor(MapColor.WATER).replaceable().noCollision().instabreak()
            .sound(SoundType.WET_GRASS).pushReaction(PushReaction.DESTROY);
    }

    private static BlockBehaviour.Properties kelp(BlockBehaviour.Properties p, boolean randomTicks) {
        p = p.mapColor(MapColor.WATER).noCollision().instabreak()
            .sound(SoundType.WET_GRASS).pushReaction(PushReaction.DESTROY);
        return randomTicks ? p.randomTicks() : p;
    }

    public static void register(IEventBus bus) {
        FEATURES.register(bus);
    }

    private AquaticFlora() {
    }
}
