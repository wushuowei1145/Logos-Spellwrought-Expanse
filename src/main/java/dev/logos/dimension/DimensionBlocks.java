package dev.logos.dimension;

import dev.logos.dimension.block.AetherPlantBlock;
import dev.logos.dimension.block.AlchemicalCauldronBlock;
import dev.logos.dimension.block.ReactiveMatterBlock;
import dev.logos.dimension.block.VortexBlock;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Natural materials use the addon's namespace; Logos ingredients are referenced, never registered again. */
public final class DimensionBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(LogosDimension.ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LogosDimension.ID);
    public static final List<DeferredBlock<? extends Block>> PALETTE = new ArrayList<>();
    public static final DeferredBlock<Block> STRATA_STONE = solid("strata_stone", MapColor.COLOR_LIGHT_GRAY, 1.8f, SoundType.STONE);
    public static final DeferredBlock<Block> AETHER_SOIL = solid("aether_soil", MapColor.DIRT, .7f, SoundType.GRAVEL);
    public static final DeferredBlock<Block> AETHER_TURF = solid("aether_turf", MapColor.COLOR_CYAN, .8f, SoundType.GRASS);
    public static final DeferredBlock<Block> ISLAND_STONE = solid("island_stone", MapColor.QUARTZ, 2.2f, SoundType.STONE);
    public static final DeferredBlock<Block> CHARRED_STONE = solid("charred_stone", MapColor.COLOR_BLACK, 2.5f, SoundType.BASALT);
    public static final DeferredBlock<ReactiveMatterBlock> CINNABAR = reactive("cinnabar", MapColor.COLOR_RED, SoundType.STONE);
    public static final DeferredBlock<ReactiveMatterBlock> SULFUR_DEPOSIT = reactive("sulfur_deposit", MapColor.COLOR_YELLOW, SoundType.STONE);
    public static final DeferredBlock<Block> MERCURY_METAL = solid("mercury_metal", MapColor.METAL, 3f, SoundType.METAL);
    public static final DeferredBlock<ReactiveMatterBlock> MERCURY_CRUST = reactive("mercury_crust", MapColor.METAL, SoundType.METAL);
    public static final DeferredBlock<ReactiveMatterBlock> SALT_CRUST = reactive("salt_crust", MapColor.QUARTZ, SoundType.CALCITE);
    public static final DeferredBlock<ReactiveMatterBlock> SALT_CRYSTAL = reactive("salt_crystal", MapColor.QUARTZ, SoundType.AMETHYST);
    public static final DeferredBlock<Block> FAN_TRUNK = item("fan_trunk", BLOCKS.<Block>registerBlock("fan_trunk", RotatedPillarBlock::new,
            p -> p.mapColor(MapColor.COLOR_BROWN).strength(2f).sound(SoundType.WOOD)));
    public static final DeferredBlock<Block> FAN_LEAVES = item("fan_leaves", BLOCKS.<Block>registerBlock("fan_leaves", TransparentBlock::new,
            p -> p.mapColor(MapColor.COLOR_CYAN).strength(.25f).sound(SoundType.GRASS).noOcclusion()));
    public static final DeferredBlock<Block> GLASS_LEAVES = item("glass_leaves", BLOCKS.registerBlock("glass_leaves", TransparentBlock::new,
            p -> p.mapColor(MapColor.COLOR_LIGHT_BLUE).strength(.4f).sound(SoundType.GLASS).noOcclusion().noCollision()));
    public static final DeferredBlock<TransparentBlock> DISTILLATION_GLASS = item("distillation_glass", BLOCKS.registerBlock("distillation_glass", TransparentBlock::new,
            p -> p.mapColor(MapColor.NONE).strength(2f).sound(SoundType.GLASS).noOcclusion()));
    public static final DeferredBlock<Block> AMBER_SEEDS = item("amber_seeds", BLOCKS.registerSimpleBlock("amber_seeds",
            p -> p.mapColor(MapColor.COLOR_ORANGE).strength(.3f).sound(SoundType.GRASS).noOcclusion().noCollision().lightLevel(s -> 3)));
    public static final DeferredBlock<AetherPlantBlock> STAR_BLOSSOM = item("star_blossom", BLOCKS.registerBlock("star_blossom", AetherPlantBlock::new,
            p -> p.mapColor(MapColor.QUARTZ).instabreak().noCollision().noOcclusion().sound(SoundType.GRASS).lightLevel(s -> 2)));
    public static final DeferredBlock<AetherPlantBlock> AETHER_FERN = item("aether_fern", BLOCKS.registerBlock("aether_fern", AetherPlantBlock::new,
            p -> p.mapColor(MapColor.COLOR_CYAN).instabreak().noCollision().noOcclusion().sound(SoundType.GRASS)));
    public static final DeferredBlock<VortexBlock> VORTEX = item("vortex", BLOCKS.registerBlock("vortex", VortexBlock::new,
            p -> p.mapColor(MapColor.GOLD).strength(2f).sound(SoundType.AMETHYST).noOcclusion().lightLevel(s -> 8)));
    public static final DeferredBlock<AlchemicalCauldronBlock> ALCHEMICAL_CAULDRON = item("alchemical_cauldron", BLOCKS.registerBlock("alchemical_cauldron", AlchemicalCauldronBlock::new,
            p -> p.mapColor(MapColor.COLOR_BLACK).strength(3f).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 10)));

    private static DeferredBlock<Block> solid(String name, MapColor color, float strength, SoundType sound) {
        return item(name, BLOCKS.registerSimpleBlock(name, p -> p.mapColor(color).strength(strength).sound(sound)));
    }
    private static DeferredBlock<ReactiveMatterBlock> reactive(String name, MapColor color, SoundType sound) {
        return item(name, BLOCKS.registerBlock(name, ReactiveMatterBlock::new,
                p -> p.mapColor(color).strength(1.4f).sound(sound).randomTicks()
                        .lightLevel(s -> s.getValue(ReactiveMatterBlock.PHASE) == 1 ? 4 : s.getValue(ReactiveMatterBlock.PHASE) == 3 ? 2 : 0)));
    }
    private static <B extends Block> DeferredBlock<B> item(String name, DeferredBlock<B> block) {
        ITEMS.registerSimpleBlockItem(name, block);
        PALETTE.add(block);
        return block;
    }
    private DimensionBlocks() {}
}
