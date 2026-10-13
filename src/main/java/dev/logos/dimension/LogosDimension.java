package dev.logos.dimension;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.gametest.DimensionGameTests;
import dev.logos.dimension.world.AlchemicalBiomeSource;
import dev.logos.dimension.world.AlchemicalChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Standalone Logos addon; it registers no items in the parent mod's namespace. */
@Mod(LogosDimension.ID)
public final class LogosDimension {
    public static final String ID = "logos_dimension";
    public static final ResourceKey<Level> CRUCIBLE = ResourceKey.create(Registries.DIMENSION, id("crucible"));
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS = DeferredRegister.create(Registries.CHUNK_GENERATOR, ID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES = DeferredRegister.create(Registries.BIOME_SOURCE, ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    static {
        GENERATORS.register("crucible", () -> AlchemicalChunkGenerator.CODEC);
        BIOME_SOURCES.register("alchemical", () -> AlchemicalBiomeSource.CODEC);
        TABS.register("crucible", () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.logos_dimension"))
                .icon(() -> DimensionBlocks.SALT_CRYSTAL.get().asItem().getDefaultInstance())
                .displayItems((p, out) -> {
                    DimensionBlocks.PALETTE.forEach(b -> out.accept(b.get()));
                    out.accept(DimensionFluids.MERCURY_BUCKET.get()); out.accept(DimensionFluids.GLASS_BUCKET.get());
                }).build());
    }
    public LogosDimension(IEventBus bus) {
        DimensionFluids.init();
        DimensionBlocks.BLOCKS.register(bus); DimensionBlocks.ITEMS.register(bus);
        DimensionFluids.TYPES.register(bus); DimensionFluids.FLUIDS.register(bus);
        GENERATORS.register(bus); BIOME_SOURCES.register(bus); TABS.register(bus);
        DimensionGameTests.register(bus);
        bus.addListener(DimensionSpawns::register);
        bus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) ->
                event.registrar("1").playToClient(dev.logos.dimension.net.HauntPayload.TYPE, dev.logos.dimension.net.HauntPayload.CODEC));
        NeoForge.EVENT_BUS.addListener(DimensionCommands::register);
        NeoForge.EVENT_BUS.addListener(IslandSanctum::placement);
        NeoForge.EVENT_BUS.addListener(IslandSanctum::position);
        NeoForge.EVENT_BUS.addListener(UnderIslandHaunt::tick);
        NeoForge.EVENT_BUS.addListener(UnderIslandHaunt::death);
    }
    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(ID, path); }
}
