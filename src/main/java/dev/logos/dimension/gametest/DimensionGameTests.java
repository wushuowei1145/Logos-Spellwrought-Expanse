package dev.logos.dimension.gametest;

import dev.logos.dimension.*;
import dev.logos.dimension.world.*;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Tests load the real addon's dimension and use actual registry data, chunks and spawn events. */
public final class DimensionGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static final List<String> NAMES = List.of("registry_compatibility", "empty_island", "island_spawn_guard", "custom_materials", "phase_clock", "travel_round_trip", "reactive_matter", "fluids_flow", "island_boss_progress");
    public static void register(IEventBus bus) {
        FUNCTIONS.register("registry_compatibility", () -> DimensionGameTests::registry);
        FUNCTIONS.register("empty_island", () -> DimensionGameTests::island);
        FUNCTIONS.register("island_spawn_guard", () -> DimensionGameTests::spawnGuard);
        FUNCTIONS.register("custom_materials", () -> DimensionGameTests::materials);
        FUNCTIONS.register("phase_clock", () -> DimensionGameTests::clock);
        FUNCTIONS.register("travel_round_trip", () -> DimensionGameTests::travel);
        FUNCTIONS.register("reactive_matter", () -> DimensionGameTests::reaction);
        FUNCTIONS.register("fluids_flow", () -> DimensionGameTests::flow);
        FUNCTIONS.register("island_boss_progress", () -> DimensionGameTests::progress);
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(LogosDimension.id("default"), new TestEnvironmentDefinition.AllOf());
            for (String name : NAMES) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)),
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 400, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }
    private static ServerLevel dimension(GameTestHelper h) {
        var level = h.getLevel().getServer().getLevel(LogosDimension.CRUCIBLE);
        h.assertTrue(level != null, "Crucible dimension did not load"); return level;
    }
    private static void registry(GameTestHelper h) {
        var level = dimension(h);
        h.assertTrue(level.getChunkSource().getGenerator() instanceof AlchemicalChunkGenerator, "Wrong generator");
        h.assertTrue(BuiltInRegistries.ITEM.containsKey(Identifier.parse("logos:tainted_mana_shard")), "Parent material missing");
        h.assertTrue(!BuiltInRegistries.ITEM.containsKey(LogosDimension.id("tainted_mana_shard")), "Parent material was duplicated");
        h.assertTrue(((AlchemicalChunkGenerator) level.getChunkSource().getGenerator()).terrain().layout().spacing() == 20_000, "Pattern spacing changed");
        h.succeed();
    }
    private static void island(GameTestHelper h) {
        var level = dimension(h);
        var gen = (AlchemicalChunkGenerator) level.getChunkSource().getGenerator();
        var c = gen.terrain().column(0, 0);
        level.getChunk(0, 0);
        for (int x = 1; x <= 12; x += 3) for (int z = 1; z <= 12; z += 3) {
            var p = new BlockPos(x, c.islandTop(), z);
            h.assertTrue(level.getBlockState(p).is(DimensionBlocks.ISLAND_STONE.get()), "Island top not reserved stone");
            for (int y = 1; y <= 30; y++) h.assertTrue(level.isEmptyBlock(p.above(y)), "Island decorated or obstructed");
            h.assertTrue(level.getBiome(p.above()).is(LogosDimension.id("sanctum")), "Island is not the sanctum biome");
        }
        h.succeed();
    }
    private static void spawnGuard(GameTestHelper h) {
        var level = dimension(h);
        var c = ((AlchemicalChunkGenerator) level.getChunkSource().getGenerator()).terrain().column(0, 0);
        var pos = new BlockPos(0, c.islandTop() + 1, 0);
        var natural = new MobSpawnEvent.SpawnPlacementCheck(EntityType.ZOMBIE, level, EntitySpawnReason.NATURAL, pos, level.getRandom(), true);
        NeoForge.EVENT_BUS.post(natural);
        h.assertTrue(!natural.getPlacementCheckResult(), "Automatic zombie spawning was allowed on the island");
        var spawner = new MobSpawnEvent.SpawnPlacementCheck(EntityType.SPIDER, level, EntitySpawnReason.SPAWNER, pos, level.getRandom(), true);
        NeoForge.EVENT_BUS.post(spawner);
        h.assertTrue(!spawner.getPlacementCheckResult(), "Spawner bypassed sanctum guard");
        var scripted = new MobSpawnEvent.SpawnPlacementCheck(EntityType.WITHER, level, EntitySpawnReason.TRIGGERED, pos, level.getRandom(), true);
        NeoForge.EVENT_BUS.post(scripted);
        h.assertTrue(scripted.getPlacementCheckResult(), "Scripted boss hook was blocked");
        h.assertTrue(!IslandSanctum.contains(level, new BlockPos(0, c.ground() + 1, 0)), "Ground beneath island incorrectly protected");
        h.succeed();
    }
    private static void materials(GameTestHelper h) {
        var level = dimension(h);
        var gen = (AlchemicalChunkGenerator) level.getChunkSource().getGenerator();
        for (var region : AlchemicalLayout.Region.values()) {
            var point = DimensionTravel.findLanding(level, gen.terrain().layout().center(0, 0), region);
            h.assertTrue(point != null, "No safe landing in " + region);
            var pos = BlockPos.containing(point);
            for (int y = level.getMinY() + 4; y < pos.getY(); y++) {
                var block = level.getBlockState(new BlockPos(pos.getX(), y, pos.getZ())).getBlock();
                String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                h.assertTrue(!id.startsWith("minecraft:") || id.equals("minecraft:air") || id.equals("minecraft:water"), "Vanilla mineral generated: " + id);
            }
        }
        h.assertTrue(BuiltInRegistries.BLOCK.getKey(DimensionBlocks.SULFUR_DEPOSIT.get()).getNamespace().equals(LogosDimension.ID), "Sulfur uses vanilla block");
        h.assertTrue(DimensionFluids.MERCURY.get().getFluidType() == DimensionFluids.MERCURY_TYPE.get(), "Mercury fluid type mismatch");
        h.succeed();
    }
    private static void clock(GameTestHelper h) {
        var level = dimension(h);
        var type = level.dimensionType();
        h.assertTrue(type.defaultClock().orElseThrow().is(OpusPhase.CLOCK), "Dimension uses ordinary day clock");
        h.assertTrue(type.timelines().stream().anyMatch(t -> t.is(LogosDimension.id("opus"))), "Alchemical timeline missing");
        h.assertTrue(OpusPhase.at(24_000) == OpusPhase.ALBEDO && OpusPhase.at(96_000) == OpusPhase.NIGREDO, "Cycle ordering wrong");
        h.succeed();
    }
    private static void travel(GameTestHelper h) {
        var player = h.makeMockServerPlayerInLevel();
        player.connection.markClientLoaded();
        var before = player.position();
        var original = player.level().dimension();
        h.assertTrue(DimensionTravel.enter(player), "Entrance failed");
        h.assertTrue(player.level().dimension().equals(LogosDimension.CRUCIBLE), "Entrance used wrong dimension");
        h.assertTrue(DimensionTravel.leave(player), "Return failed");
        h.assertTrue(player.level().dimension().equals(original) && player.position().distanceToSqr(before) < .01, "Return point changed");
        h.succeed();
    }
    private static void reaction(GameTestHelper h) {
        var level = dimension(h);
        var natural = new BlockPos(9500, 230, 1000);
        var placed = natural.east(3);
        level.getChunk(natural);
        var block = DimensionBlocks.CINNABAR.get();
        var originalNatural = level.getBlockState(natural);
        var originalPlaced = level.getBlockState(placed);
        var clock = level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(OpusPhase.CLOCK);
        var manager = level.getServer().clockManager();
        long previousTime = manager.getTotalTicks(clock);
        try {
            manager.setTotalTicks(clock, OpusPhase.PHASE_TICKS);
            level.setBlockAndUpdate(natural, block.defaultBlockState().setValue(dev.logos.dimension.block.ReactiveMatterBlock.NATURAL, true));
            level.setBlockAndUpdate(placed, block.defaultBlockState());
            level.getBlockState(natural).randomTick(level, natural, net.minecraft.util.RandomSource.create(1));
            level.getBlockState(placed).randomTick(level, placed, net.minecraft.util.RandomSource.create(1));
            h.assertTrue(level.getBlockState(natural).getValue(dev.logos.dimension.block.ReactiveMatterBlock.PHASE) == 1, "Natural matter did not change with its own clock");
            h.assertTrue(level.getBlockState(placed).getValue(dev.logos.dimension.block.ReactiveMatterBlock.PHASE) == 0, "Player placed material was changed");
        } finally {
            manager.setTotalTicks(clock, previousTime);
            level.setBlockAndUpdate(natural, originalNatural); level.setBlockAndUpdate(placed, originalPlaced);
        }
        h.succeed();
    }
    private static void flow(GameTestHelper h) {
        // GameTest's structure chunks are guaranteed to tick; a mock player's remote chunk is not.
        var level = h.getLevel();
        var mercury = h.absolutePos(new BlockPos(3, 2, 3));
        var glass = mercury.east(8);
        for (var p : List.of(mercury, glass)) for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
            level.setBlockAndUpdate(p.offset(x, -1, z), DimensionBlocks.STRATA_STONE.get().defaultBlockState());
        level.setBlockAndUpdate(mercury, DimensionFluids.MERCURY_BLOCK.get().defaultBlockState());
        level.setBlockAndUpdate(glass, DimensionFluids.GLASS_BLOCK.get().defaultBlockState());
        h.runAfterDelay(100, () -> {
            h.assertTrue(level.getFluidState(mercury.east()).getFluidType() == DimensionFluids.MERCURY_TYPE.get(), "Mercury did not flow");
            h.assertTrue(level.getFluidState(glass.east()).getFluidType() == DimensionFluids.GLASS_TYPE.get(), "Molten glass did not flow");
            h.assertTrue(level.getFluidState(mercury.east()).getAmount() < 8, "Flow created an infinite mercury source");
            var bucket = DimensionFluids.MERCURY_BLOCK.get().pickupBlock(null, level, mercury, level.getBlockState(mercury));
            h.assertTrue(bucket.is(DimensionFluids.MERCURY_BUCKET.get()), "Source cannot be collected into the custom bucket");
            h.succeed();
        });
    }
    private static void progress(GameTestHelper h) {
        var level = dimension(h);
        var terrain = ((AlchemicalChunkGenerator) level.getChunkSource().getGenerator()).terrain();
        var center = terrain.layout().center(0,0); var next = terrain.layout().center(1,0);
        var col = terrain.column(center.x(),center.z());
        var other = terrain.column(next.x(),next.z());
        var data = IslandProgress.of(level);
        boolean wasDefeated = data.defeated(center);
        try {
            data.setDefeated(center, false);
            h.assertTrue(UnderIslandHaunt.state(level,center.x(),col.ground()+2,center.z()).density() > .9, "Undefeated island has no haunted fog");
            h.assertTrue(UnderIslandHaunt.state(level,center.x(),col.islandTop()+1,center.z()).density() == 0, "Plateau is haunted");
            data.setDefeated(center, true);
            h.assertTrue(!UnderIslandHaunt.state(level,center.x(),col.ground()+2,center.z()).veiled(), "Boss victory left island haunted");
            h.assertTrue(UnderIslandHaunt.state(level,next.x(),other.ground()+2,next.z()).veiled(), "Victory cleared a different repeated island");
            var encoded = IslandProgress.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,data).getOrThrow();
            var restored = IslandProgress.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,encoded).getOrThrow();
            h.assertTrue(restored.defeated(center) && !restored.defeated(next), "Boss progress failed to serialize");
        } finally { data.setDefeated(center,wasDefeated); }
        h.succeed();
    }
    private DimensionGameTests() {}
}
