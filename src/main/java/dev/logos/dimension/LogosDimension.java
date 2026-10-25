package dev.logos.dimension;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.gametest.DimensionGameTests;
import dev.logos.dimension.world.LegacyWorldgenCodecs;
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
    public static final ResourceKey<Level> CLOUDSEA = ResourceKey.create(Registries.DIMENSION, id("cloudsea"));
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS = DeferredRegister.create(Registries.CHUNK_GENERATOR, ID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES = DeferredRegister.create(Registries.BIOME_SOURCE, ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
    static {
        GENERATORS.register("crucible", () -> LegacyWorldgenCodecs.generator("crucible"));
        BIOME_SOURCES.register("alchemical", () -> LegacyWorldgenCodecs.ALCHEMICAL);
        GENERATORS.register("cloudsea", () -> LegacyWorldgenCodecs.generator("journey"));
        BIOME_SOURCES.register("cloudsea", () -> LegacyWorldgenCodecs.CLOUDSEA);
        TABS.register("cloudsea", () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.logos_dimension.cloudsea"))
                .icon(() -> dev.logos.dimension.cloudsea.CloudseaBlocks.PORCELAIN.get().asItem().getDefaultInstance())
                .displayItems((p,out) -> {
                    dev.logos.dimension.cloudsea.CloudseaBlocks.PALETTE.forEach(b -> out.accept(b.get()));
                    out.accept(dev.logos.dimension.cloudsea.CloudseaBlocks.GRAPES.get());out.accept(dev.logos.dimension.cloudsea.CloudseaBlocks.GRAPE_LEAF.get());
                }).build());
        TABS.register("crucible", () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.logos_dimension"))
                .icon(() -> DimensionBlocks.SALT_CRYSTAL.get().asItem().getDefaultInstance())
                .displayItems((p, out) -> {
                    DimensionBlocks.PALETTE.forEach(b -> out.accept(b.get()));
                    out.accept(DimensionFluids.MERCURY_BUCKET.get()); out.accept(DimensionFluids.GLASS_BUCKET.get());
                }).build());
    }
    public LogosDimension(IEventBus bus) {
        DimensionFluids.init();
        dev.logos.dimension.instance.CauldronBinding.register(bus);
        dev.logos.dimension.cloudsea.CloudseaBlocks.init();
        dev.logos.dimension.elven.ElvenBlocks.init();
        DimensionBlocks.BLOCKS.register(bus); DimensionBlocks.ITEMS.register(bus);
        DimensionFluids.TYPES.register(bus); DimensionFluids.FLUIDS.register(bus);
        DimensionParticles.register(bus);
        GENERATORS.register(bus); BIOME_SOURCES.register(bus); TABS.register(bus);
        DimensionGameTests.register(bus);
        dev.logos.dimension.gametest.CloudseaGameTests.register(bus);
        dev.logos.dimension.gametest.CauldronInstanceGameTests.register(bus);
        dev.logos.dimension.gametest.GrapeGameTests.register(bus);
        dev.logos.dimension.gametest.PlantSupportGameTests.register(bus);
        dev.logos.dimension.gametest.BerryGameTests.register(bus);
        dev.logos.dimension.gametest.TallTreeGameTests.register(bus);
        dev.logos.dimension.gametest.SettlementGameTests.register(bus);
        dev.logos.dimension.gametest.ElvenForestGameTests.register(bus);
        dev.logos.dimension.gametest.FallenLogGameTests.register(bus);
        dev.logos.dimension.gametest.MushroomGameTests.register(bus);
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
    public static boolean isCrucible(ResourceKey<Level> dimension) {
        var id=dimension.identifier();if(!id.getNamespace().equals(ID))return false;
        String path=id.getPath();if(path.length()!=36)return false;
        for(int i=0;i<36;i++){
            char c=path.charAt(i);
            if(i==8||i==13||i==18||i==23){if(c!='-')return false;}
            else if(!(c>='0'&&c<='9'||c>='a'&&c<='f'))return false;
        }
        return true;
    }
}
