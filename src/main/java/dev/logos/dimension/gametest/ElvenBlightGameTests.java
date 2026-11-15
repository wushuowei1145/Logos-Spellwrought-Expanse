package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.Corruption;
import dev.logos.dimension.elven.blight.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 实际服务端检查：核心单次触发、存档锁、登场保护和击败标记。 */
public final class ElvenBlightGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private ElvenBlightGameTests() {}
    public static void register(IEventBus bus) {
        var tests = new LinkedHashMap<>(Map.<String, Consumer<GameTestHelper>>of(
                "elven_blight_heart_once_and_saved", ElvenBlightGameTests::once,
                "elven_blight_emergence_and_defeat", ElvenBlightGameTests::defeat,
                "elven_blight_growth_uses_corruption", ElvenBlightGameTests::growth,
                "elven_blight_native_assembly", ElvenBlightGameTests::assembly,
                "elven_blight_natural_surface", ElvenBlightGameTests::surface,
                "elven_blight_melee_hits_once", ElvenBlightGameTests::melee,
                "elven_blight_natural_trigger", ElvenBlightGameTests::trigger,
                "elven_blight_cleanup_is_local_and_restores", BlightReclamationTests::cleanup,
                "elven_blight_reclamation_saved_and_rotated", BlightReclamationTests::saved,
                "elven_blight_natural_nodes", BlightReclamationTests::natural));
        tests.put("elven_blight_three_stage_battle", QueenBattleTests::battle);
        tests.put("elven_blight_queen_purification", QueenBattleTests::restoration);
        tests.put("elven_blight_queen_logos_spells", QueenBattleTests::spells);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent e) -> {
            var env = e.registerEnvironment(LogosDimension.id("elven_blight"), new TestEnvironmentDefinition.AllOf());
            for (String name : tests.keySet()) e.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                    Identifier.withDefaultNamespace("empty"), 1800, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }
    public static ElvenForestGameTests.Tree assemble(net.minecraft.server.level.ServerLevel world, BlockPos at, long seed) {
        var holder = world.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("blighted_elven_world_tree")));
        var source = world.getChunkSource();
        var gen = source.getGenerator();
        var start = holder.value().generate(holder, world.dimension(), world.registryAccess(), gen, gen.getBiomeSource(), source.randomState(),
                world.getStructureManager(), seed, new ChunkPos(at.getX() >> 4, at.getZ() >> 4), 0, world, b -> true);
        if (!start.isValid()) throw new IllegalStateException("腐败世界树未能进行原版拼接");
        var root = (net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece) start.getPieces().getFirst();
        return new ElvenForestGameTests.Tree(start, root.getPosition().offset(new BlockPos(16, 4, 16).rotate(root.getRotation())), root.getRotation());
    }
    public static ElvenForestGameTests.Tree placeStudy(net.minecraft.server.level.ServerLevel world) {
        var tree = assemble(world, dev.logos.dimension.NativeWorlds.locateBiome(world, "blighted_elven_forest"), 81);
        SettlementGameTests.place(world, tree.start());
        return tree;
    }
    private static void surface(GameTestHelper h) {
        var world = ElvenForestGameTests.level(h);
        var at = dev.logos.dimension.NativeWorlds.locateBiome(world, "blighted_elven_forest");
        var source = world.getChunkSource();
        int eligible = 0, covered = 0;
        var biome = ResourceKey.create(Registries.BIOME, LogosDimension.id("blighted_elven_forest"));
        for (int x = -16; x <= 16; x += 4) for (int z = -16; z <= 16; z += 4) {
            int ax = at.getX() + x, az = at.getZ() + z;
            int y = source.getGenerator().getBaseHeight(ax, az, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, world, source.randomState()) - 1;
            var pos = new BlockPos(ax, y, az);
            if (!world.getBiome(pos).is(biome) || y <= 63) continue;
            eligible++;
            if (world.getBlockState(pos).getBlock() instanceof BlightMemoryBlock) covered++;
        }
        System.out.println("ELVEN_BLIGHT_SURFACE at=" + at + " covered=" + covered + "/" + eligible);
        h.assertTrue(eligible >= 30 && covered >= eligible * .7, "实际自然地表缺少腐败覆盖：" + covered + "/" + eligible);
        h.succeed();
    }
    private static void assembly(GameTestHelper h) {
        var world = ElvenForestGameTests.level(h);
        world.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, LogosDimension.id("blighted_elven_forest")));
        var tree = assemble(world, new BlockPos(12320, 80, -20320), 81);
        long tiles = tree.start().getPieces().stream().filter(p -> p instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece q && q.getElement() instanceof SinglePoolElement).count();
        h.assertTrue(tiles == 343, "腐败世界树拼接缺块：" + tiles);
        var root = (net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece) tree.start().getPieces().getFirst();
        var element = (SinglePoolElement) root.getElement();
        var template = world.getStructureManager().getOrCreate(element.getTemplateLocation());
        var markers = template.filterBlocks(root.getPosition(), new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setRotation(root.getRotation()), ElvenBlight.HEART.get());
        h.assertTrue(markers.size() == 1 && markers.getFirst().pos().equals(tree.pos(0, 9, 0)), "核心未悬在树心");
        h.assertTrue(ElvenForestGameTests.roots(tree) >= 1500, "腐败树根缺少接地子件");
        h.succeed();
    }
    record Fixture(QueenHeart heart, ServerPlayer player, BlockPos pos) {
        void close(GameTestHelper h) {
            var world = h.getLevel();
            if (heart.queen() != null && world.getEntity(heart.queen()) != null) world.getEntity(heart.queen()).discard();
            world.getServer().getPlayerList().remove(player);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.setChunkForced((pos.getX() >> 4) + x, (pos.getZ() >> 4) + z, false);
        }
    }
    static Fixture fixture(GameTestHelper h) {
        return fixture(h, BlockPos.ZERO);
    }
    static Fixture fixture(GameTestHelper h, BlockPos offset) {
        var world = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(8, 9, 8)).offset(offset);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.setChunkForced((at.getX() >> 4) + x, (at.getZ() >> 4) + z, true);
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            world.setBlockAndUpdate(at.offset(x, -8, z), Blocks.STONE.defaultBlockState());
            for (int y = -7; y < 15; y++) world.setBlockAndUpdate(at.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
        world.setBlockAndUpdate(at, ElvenBlight.HEART.get().defaultBlockState());
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "blight-test"), false);
        var player = new ServerPlayer(world.getServer(), world, cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        world.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.connection.markClientLoaded();
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(at.getBottomCenter().add(0, -7, 7));
        return new Fixture((QueenHeart) world.getBlockEntity(at), player, at);
    }
    private static void once(GameTestHelper h) {
        var f = fixture(h);
        try {
            var q = f.heart.awaken(f.player);
            h.assertTrue(q != null && q == f.heart.awaken(f.player), "重复触发生成了第二个女王");
            h.assertTrue(Math.abs(q.getBbHeight() - 4) < .001 && Math.abs(q.getEyeHeight() - 3.3) < .001, "四格模型与实体尺寸不一致");
            var tag = f.heart.saveWithFullMetadata(h.getLevel().registryAccess());
            var loaded = (QueenHeart) BlockEntity.loadStatic(f.pos, f.heart.getBlockState(), tag, h.getLevel().registryAccess());
            h.assertTrue(loaded != null && q.getUUID().equals(loaded.queen()), "重载丢失战斗 UUID");
            h.assertTrue(q.introPlayed(), "首次入场未记录");
            loaded.setLevel(h.getLevel());
            h.assertTrue(loaded.awaken(f.player) == q, "重载生成了第二个女王");
            q.guard(f.pos);
            loaded.awaken(f.player);
            h.assertTrue(q.action().equals("queen_rise"), "重置守护后重复播放长入场");
            q.discard();
            h.assertTrue(loaded.awaken(f.player) == null, "未加载或不存在的实体被当成可重复召唤");
            h.succeed();
        } finally { f.close(h); }
    }
    private static void defeat(GameTestHelper h) {
        var f = fixture(h);
        var queen = Objects.requireNonNull(f.heart.awaken(f.player));
        float hp = queen.getHealth();
        queen.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(f.player), 20);
        h.assertTrue(queen.getHealth() == hp, "起身期间受到普通伤害");
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(queen.tickCount >= QueenEntrances.TICKS + 5, "等待实体所在区块开始走刻");
            try {
                h.assertTrue(!queen.entering(), "起身后未进入战斗");
                queen.invulnerableTime = 0;
                h.assertTrue(queen.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(f.player), 4), "登场结束仍不可攻击");
                queen.die(h.getLevel().damageSources().playerAttack(f.player));
                h.assertTrue(f.heart.getBlockState().getValue(QueenHeartBlock.DEFEATED), "击败未标记核心");
                h.assertTrue(f.heart.awaken(f.player) == null, "击败后重复触发");
                h.assertTrue(!BlightReclamation.get(h.getLevel()).supplied(f.pos), "女王死后仍在供养污染");
                h.succeed();
            } finally { f.close(h); }
        }));
    }
    private static void growth(GameTestHelper h) {
        h.assertTrue(!Corruption.isNetworkBlock(ElvenBlight.LUMEN.get().defaultBlockState())
                && ElvenBlight.LUMEN.get().defaultBlockState().getLightEmission() >= 10, "场地脉光应照明并提供安全落脚点");
        BlockPos at = h.absolutePos(new BlockPos(3, 2, 3));
        for (var block : List.of(ElvenBlight.BUD.get(), ElvenBlight.SPINES.get(), ElvenBlight.RIBS.get())) {
            h.assertTrue(Corruption.isNetworkBlock(block.defaultBlockState()), "增生未接入腐败接触与网络");
            h.getLevel().setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
            h.assertTrue(!block.defaultBlockState().canSurvive(h.getLevel(), at), "腐败植物可种在任意石头上");
            h.getLevel().setBlockAndUpdate(at.below(), ElvenBlight.LOAM.get().defaultBlockState());
            h.assertTrue(block.defaultBlockState().canSurvive(h.getLevel(), at), "增生不能在腐败林土上生成");
        }
        h.succeed();
    }
    private static void melee(GameTestHelper h) {
        var f = fixture(h);
        var queen = Objects.requireNonNull(f.heart.awaken(f.player));
        h.assertTrue(dev.logos.dimension.corruption.CorruptionContact.immune(queen), "女王未加入腐败生物标签");
        final float[] before = {0};
        h.onEachTick(() -> {
            if (queen.tickCount >= QueenEntrances.TICKS + 5 && before[0] == 0) {
                f.player.setPos(queen.position().add(0, 0, 2.8));
                before[0] = f.player.getHealth();
            }
        });
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(queen.tickCount >= QueenEntrances.TICKS + 75, "等待横扫完成");
            try {
                float lost = before[0] - f.player.getHealth();
                h.assertTrue(lost > 0 && lost <= 11, "近身横扫未命中或重复结算：" + lost);
                h.succeed();
            } finally { f.close(h); }
        }));
    }
    private static void trigger(GameTestHelper h) {
        var f = fixture(h);
        f.player.setGameMode(GameType.CREATIVE);
        h.runAfterDelay(20, () -> {
            h.assertTrue(f.heart.queen() != null && h.getLevel().getEntity(f.heart.queen()) instanceof CorruptedElfQueen q && q.action().equals("queen_guard"), "女王应坐在核心下，创造模式不自动开战");
            f.player.setGameMode(GameType.SURVIVAL);
        });
        h.runAfterDelay(45, () -> {
            try {
                h.assertTrue(f.heart.queen() != null && h.getLevel().getEntity(f.heart.queen()) instanceof CorruptedElfQueen q && !q.action().equals("queen_guard"), "生存靠近未唤醒守护者");
                h.succeed();
            } finally { f.close(h); }
        });
    }
}
