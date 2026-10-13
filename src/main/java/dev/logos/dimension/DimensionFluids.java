package dev.logos.dimension;

import java.util.function.Supplier;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.core.registries.Registries;

/** The lakes and glass rivers are actual flowing, bucketable fluids, with no infinite source conversion. */
public final class DimensionFluids {
    public static final DeferredRegister<FluidType> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, LogosDimension.ID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, LogosDimension.ID);
    public static final DeferredHolder<FluidType, FluidType> MERCURY_TYPE = TYPES.register("mercury", () -> new FluidType(
            FluidType.Properties.create().density(13500).viscosity(4000).canConvertToSource(false).supportsBoating(true).canHydrate(false)));
    public static final DeferredHolder<FluidType, FluidType> GLASS_TYPE = TYPES.register("molten_glass", () -> new FluidType(
            FluidType.Properties.create().density(2500).viscosity(8000).temperature(1300).lightLevel(9).canConvertToSource(false).canHydrate(false)));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> MERCURY = FLUIDS.register("mercury", () -> new BaseFlowingFluid.Source(mercuryProperties()));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_MERCURY = FLUIDS.register("flowing_mercury", () -> new BaseFlowingFluid.Flowing(mercuryProperties()));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> MOLTEN_GLASS = FLUIDS.register("molten_glass", () -> new BaseFlowingFluid.Source(glassProperties()));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_GLASS = FLUIDS.register("flowing_molten_glass", () -> new BaseFlowingFluid.Flowing(glassProperties()));
    public static final Supplier<LiquidBlock> MERCURY_BLOCK = DimensionBlocks.BLOCKS.registerBlock("mercury", p -> new LiquidBlock(MERCURY.get(), p),
            p -> p.noCollision().noOcclusion().strength(100).liquid());
    public static final Supplier<LiquidBlock> GLASS_BLOCK = DimensionBlocks.BLOCKS.registerBlock("molten_glass", p -> new LiquidBlock(MOLTEN_GLASS.get(), p),
            p -> p.noCollision().noOcclusion().strength(100).liquid().lightLevel(s -> 9));
    public static final Supplier<BucketItem> MERCURY_BUCKET = DimensionBlocks.ITEMS.registerItem("mercury_bucket", p -> new BucketItem(MERCURY.get(), p),
            p -> p.stacksTo(1).craftRemainder(Items.BUCKET));
    public static final Supplier<BucketItem> GLASS_BUCKET = DimensionBlocks.ITEMS.registerItem("molten_glass_bucket", p -> new BucketItem(MOLTEN_GLASS.get(), p),
            p -> p.stacksTo(1).craftRemainder(Items.BUCKET));

    private static BaseFlowingFluid.Properties mercuryProperties() { return new BaseFlowingFluid.Properties(MERCURY_TYPE, MERCURY, FLOWING_MERCURY).block(MERCURY_BLOCK).bucket(MERCURY_BUCKET).tickRate(15).levelDecreasePerBlock(2); }
    private static BaseFlowingFluid.Properties glassProperties() { return new BaseFlowingFluid.Properties(GLASS_TYPE, MOLTEN_GLASS, FLOWING_GLASS).block(GLASS_BLOCK).bucket(GLASS_BUCKET).tickRate(30).levelDecreasePerBlock(2); }
    public static void init() {}
    private DimensionFluids() {}
}
