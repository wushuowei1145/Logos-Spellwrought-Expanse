package dev.logos.dimension.firearm;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 铸弹台和它的压制配方（docs/FIREARMS.md 第 6 节）：方块、物品、方块实体、配方类型，以及漏斗、管道接口。
 * 以后刻术式弹也在这张台上（docs 里的第二步），那时它再加上存魔力。
 */
public final class AmmoBench {
    private AmmoBench() {}

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LogosDimension.ID);
    private static final DeferredRegister<RecipeType<?>> RECIPE_TYPES = DeferredRegister.create(Registries.RECIPE_TYPE, LogosDimension.ID);
    private static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(Registries.RECIPE_SERIALIZER, LogosDimension.ID);
    private static final DeferredRegister<RecipeBookCategory> BOOK_CATEGORIES = DeferredRegister.create(Registries.RECIPE_BOOK_CATEGORY, LogosDimension.ID);

    public static final DeferredBlock<AmmoBenchBlock> BENCH = DimensionBlocks.BLOCKS.registerBlock("ammo_bench", AmmoBenchBlock::new,
            p -> p.mapColor(MapColor.WOOD).sound(SoundType.WOOD).strength(2.5f).noOcclusion());
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AmmoBenchBlockEntity>> BENCH_ENTITY =
            BLOCK_ENTITIES.register("ammo_bench", () -> new BlockEntityType<>(AmmoBenchBlockEntity::new, BENCH.get()));
    public static final DeferredHolder<RecipeType<?>, RecipeType<AmmoPressRecipe>> PRESS_RECIPE =
            RECIPE_TYPES.register("ammo_pressing", RecipeType::simple);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<AmmoPressRecipe>> PRESS_SERIALIZER =
            RECIPE_SERIALIZERS.register("ammo_pressing", () -> AmmoPressRecipe.SERIALIZER);
    public static final DeferredHolder<RecipeBookCategory, RecipeBookCategory> BOOK_CATEGORY =
            BOOK_CATEGORIES.register("ammo_pressing", RecipeBookCategory::new);
    /** 蚀刻术式弹的档位（{@link RoundEtchRecipe}）。 */
    public static final DeferredHolder<RecipeType<?>, RecipeType<RoundEtchRecipe>> ETCH_RECIPE =
            RECIPE_TYPES.register("round_etching", RecipeType::simple);
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<RoundEtchRecipe>> ETCH_SERIALIZER =
            RECIPE_SERIALIZERS.register("round_etching", () -> RoundEtchRecipe.SERIALIZER);

    static {
        DimensionBlocks.ITEMS.registerSimpleBlockItem("ammo_bench", BENCH);
    }

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
        RECIPE_TYPES.register(bus);
        RECIPE_SERIALIZERS.register(bus);
        BOOK_CATEGORIES.register(bus);
        // 漏斗、管道：材料进各自的格子，弹药从两个出货格取。
        bus.addListener((RegisterCapabilitiesEvent e) -> e.registerBlockEntity(Capabilities.Item.BLOCK, BENCH_ENTITY.get(),
                (bench, side) -> bench.automation()));
    }
}
