package dev.logos.dimension;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.gametest.DimensionGameTests;
import dev.logos.dimension.world.LegacyWorldgenCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
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
    static {
        GENERATORS.register("crucible", () -> LegacyWorldgenCodecs.generator("crucible"));
        BIOME_SOURCES.register("alchemical", () -> LegacyWorldgenCodecs.ALCHEMICAL);
        GENERATORS.register("cloudsea", () -> LegacyWorldgenCodecs.generator("journey"));
        BIOME_SOURCES.register("cloudsea", () -> LegacyWorldgenCodecs.CLOUDSEA);
    }
    public LogosDimension(IEventBus bus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, ServerConfig.SPEC);
        DimensionFluids.init();
        dev.logos.dimension.item.NaturesForceItem.init();
        dev.logos.dimension.item.ElfSpearItem.init();
        dev.logos.dimension.item.LongswordItem.init();
        dev.logos.dimension.item.LingxiaoSwordItem.init();
        dev.logos.dimension.item.JinghongSwordItem.init();
        dev.logos.dimension.lingxiao.LingxiaoFlight.register(bus);
        dev.logos.dimension.longsword.ManaGuard.init();
        dev.logos.dimension.item.UchigatanaItem.init();
        dev.logos.dimension.item.OathSpearItem.init();
        dev.logos.dimension.item.YumiItem.init();
        dev.logos.dimension.item.RyukaItem.init();
        dev.logos.dimension.ryuka.Ryuka.register(bus);
        dev.logos.dimension.item.AncientEmbraceItem.register(bus);
        dev.logos.dimension.item.NatureAttachments.TYPES.register(bus);
        dev.logos.dimension.instance.CauldronBinding.register(bus);
        dev.logos.dimension.cloudsea.CloudseaBlocks.init();
        dev.logos.dimension.elven.ElvenBlocks.init();
        dev.logos.dimension.gate.Gate.register(bus);
        dev.logos.dimension.dawnvow.Dawnvow.register(bus);
        dev.logos.dimension.dawnvow.DawnvowCharge.register(bus);
        dev.logos.dimension.explosion.ExplosionStaffItem.init();
        dev.logos.dimension.explosion.ExplosionMagic.register(bus);
        dev.logos.dimension.camera.CameraShots.register(bus);
        dev.logos.dimension.screenfx.ScreenEffects.register(bus);
        dev.logos.dimension.execution.ExecutionShots.register(bus);
        dev.logos.dimension.dissolve.Dissolving.register(bus);
        dev.logos.dimension.worldstyle.StyleRealms.register(bus);
        dev.logos.dimension.camera.CameraTestBlock.init();
        dev.logos.dimension.corruption.Corruption.init();
        dev.logos.dimension.corruption.Corruption.register(bus);
        dev.logos.dimension.corruption.mob.CorruptionMobs.register(bus);
        DimensionBlocks.BLOCKS.register(bus); DimensionBlocks.ITEMS.register(bus);
        DimensionFluids.TYPES.register(bus); DimensionFluids.FLUIDS.register(bus);
        DimensionParticles.register(bus);
        dev.logos.dimension.fox.FoxSanctuary.register(bus);
        dev.logos.dimension.cloudsea.AquaticFlora.register(bus);
        dev.logos.dimension.cloudsea.EmaItem.register(bus);
        dev.logos.dimension.cloudsea.Omikuji.register(bus);
        dev.logos.dimension.luck.Luck.register(bus);
        dev.logos.dimension.economy.Economy.register(bus);
        dev.logos.dimension.tarot.TarotCards.register(bus);
        dev.logos.dimension.paint.PaintSystem.register(bus);
        dev.logos.dimension.music.Instruments.register(bus);
        dev.logos.dimension.npc.NpcEntities.register(bus);
        dev.logos.dimension.shiraume.ShiraumeEntities.register(bus);
        dev.logos.dimension.companion.Companions.register(bus);
        dev.logos.dimension.foxfire.Foxfire.register(bus);
        dev.logos.dimension.reifu.Reifu.register(bus);
        GENERATORS.register(bus); BIOME_SOURCES.register(bus); AddonTabs.register(bus);
        DimensionGameTests.register(bus);
        dev.logos.dimension.gametest.CloudseaGameTests.register(bus);
        dev.logos.dimension.gametest.CreativeTabGameTests.register(bus);
        dev.logos.dimension.gametest.InstrumentGameTests.register(bus);
        dev.logos.dimension.gametest.FoxfireGameTests.register(bus);
        dev.logos.dimension.gametest.ShiraumeGameTests.register(bus);
        dev.logos.dimension.gametest.ReifuGameTests.register(bus);
        dev.logos.dimension.gametest.CauldronInstanceGameTests.register(bus);
        dev.logos.dimension.gametest.GrapeGameTests.register(bus);
        dev.logos.dimension.gametest.PlantSupportGameTests.register(bus);
        dev.logos.dimension.gametest.BerryGameTests.register(bus);
        dev.logos.dimension.gametest.TallTreeGameTests.register(bus);
        dev.logos.dimension.gametest.SettlementGameTests.register(bus);
        dev.logos.dimension.gametest.ElvenForestGameTests.register(bus);
        dev.logos.dimension.gametest.FallenLogGameTests.register(bus);
        dev.logos.dimension.gametest.MushroomGameTests.register(bus);
        dev.logos.dimension.gametest.TurfGameTests.register(bus);
        dev.logos.dimension.gametest.YoungGrassGameTests.register(bus);
        dev.logos.dimension.gametest.NaturesForceGameTests.register(bus);
        dev.logos.dimension.gametest.ElfSpearGameTests.register(bus);
        dev.logos.dimension.gametest.AncientEmbraceGameTests.register(bus);
        dev.logos.dimension.gametest.LongswordGameTests.register(bus);
        dev.logos.dimension.gametest.LingxiaoGameTests.register(bus);
        dev.logos.dimension.gametest.JinghongGameTests.register(bus);
        dev.logos.dimension.gametest.DawnvowGameTests.register(bus);
        dev.logos.dimension.gametest.OmikujiGameTests.register(bus);
        dev.logos.dimension.gametest.DawnvowChargeGameTests.register(bus);
        dev.logos.dimension.gametest.ForestElfGameTests.register(bus);
        dev.logos.dimension.gametest.EconomyGameTests.register(bus);
        dev.logos.dimension.gametest.RailgunGameTests.register(bus);
        dev.logos.dimension.gametest.ExplosionGameTests.register(bus);
        dev.logos.dimension.gametest.GateGameTests.register(bus);
        dev.logos.dimension.gametest.CameraShotGameTests.register(bus);
        dev.logos.dimension.gametest.DissolveGameTests.register(bus);
        dev.logos.dimension.gametest.CorruptionGameTests.register(bus);
        bus.addListener(DimensionSpawns::register);
        bus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) ->
                event.registrar("1").playToClient(dev.logos.dimension.net.HauntPayload.TYPE, dev.logos.dimension.net.HauntPayload.CODEC));
        bus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) ->
                event.registrar("1").playToServer(
                        dev.logos.dimension.net.PaintColorPayload.TYPE,
                        dev.logos.dimension.net.PaintColorPayload.CODEC,
                        (payload, context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                                net.minecraft.world.InteractionHand hand = payload.hand() == 0
                                        ? net.minecraft.world.InteractionHand.MAIN_HAND
                                        : net.minecraft.world.InteractionHand.OFF_HAND;
                                net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
                                if (stack.getItem() instanceof dev.logos.dimension.paint.PaletteItem) {
                                    stack.set(dev.logos.dimension.paint.PaintSystem.PAINT_COLOR.get(), payload.color());
                                }
                            }
                        })));
        NeoForge.EVENT_BUS.addListener(DimensionCommands::register);
        NeoForge.EVENT_BUS.addListener(dev.logos.dimension.wind.WindCommands::register);
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
