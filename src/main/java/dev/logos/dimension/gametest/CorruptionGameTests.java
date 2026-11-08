package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.Corruption;
import dev.logos.dimension.corruption.CorruptionCoreBlock;
import dev.logos.dimension.corruption.CorruptionNetworks;
import dev.logos.runtime.PlayerMagic;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 腐败的服务端部分：核心开网络并往外长，碰到会受伤和掉魔力（亡灵不会），杀了东西网络加电荷，
 * 两个网络挨上时小的并进大的，拆核心不掉东西并从核心开始一圈圈消散。
 */
public final class CorruptionGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    private CorruptionGameTests() {}

    public static void register(IEventBus bus) {
        var tests = new LinkedHashMap<String, Consumer<GameTestHelper>>();
        tests.put("corruption_core_spreads_over_ground", CorruptionGameTests::spreads);
        tests.put("corruption_hurts_the_living_not_the_undead", CorruptionGameTests::contact);
        tests.put("corruption_drains_player_mana", CorruptionGameTests::mana);
        tests.put("corruption_kill_feeds_the_network", CorruptionGameTests::feeds);
        tests.put("corruption_touching_networks_stay_apart", CorruptionGameTests::apart);
        tests.put("corruption_full_network_splits", CorruptionGameTests::splits);
        tests.put("corruption_full_lone_network_banks_charge", CorruptionGameTests::banks);
        tests.put("corruption_core_break_fades_outward", CorruptionGameTests::fade);
        tests.put("corruption_player_breaks_core", CorruptionGameTests::playerBreak);
        tests.put("corruption_large_network_restores", CorruptionGameTests::large);
        tests.put("corruption_core_grows_with_network", CorruptionGameTests::grows);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            for (var name : tests.keySet()) {
                var env = event.registerEnvironment(LogosDimension.id(name), new TestEnvironmentDefinition.AllOf());
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 600, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
            }
        });
    }

    private static void check(GameTestHelper h, boolean ok, String message) {
        h.assertTrue(ok, Component.literal(message));
    }

    private static CorruptionNetworks data(GameTestHelper h) {
        return CorruptionNetworks.get(h.getLevel());
    }

    /** 一片 {@code size}×{@code size} 的地面，左下角在 {@code (0, y, 0)}，上面清出 4 格空气（测试原点常在超平坦地面以下）。 */
    private static void floor(GameTestHelper h, int y, int size, Block block) {
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) h.setBlock(new BlockPos(x, y, z), block);
        clear(h, new BlockPos(0, y + 1, 0), new BlockPos(size - 1, y + 4, size - 1));
    }

    private static void clear(GameTestHelper h, BlockPos from, BlockPos to) {
        for (BlockPos p : BlockPos.betweenClosed(from, to)) h.setBlock(p, Blocks.AIR);
    }

    /**
     * 放在泥土地上的核心：初始电荷长出脉络，再把地面变成腐败污秽，都记在这个网络名下，并记着原来是泥土。
     * 拆掉核心后污秽变回泥土，脉络消失。
     */
    private static void spreads(GameTestHelper h) {
        floor(h, 0, 11, Blocks.DIRT);
        BlockPos core = new BlockPos(5, 1, 5);
        h.setBlock(core, Corruption.CORE.get());
        check(h, data(h).ofCore(h.absolutePos(core)) != null, "放下核心应当开一个网络");
        h.startSequence()
                .thenWaitUntil(() -> {
                    var network = data(h).ofCore(h.absolutePos(core));
                    check(h, network != null && network.size() > 4, "网络应当长到 4 格以上");
                    int filth = 0, veins = 0;
                    for (int x = 0; x < 11; x++) {
                        for (int z = 0; z < 11; z++) {
                            BlockPos ground = new BlockPos(x, 0, z);
                            if (h.getBlockState(ground).is(Corruption.BLOCK.get())) {
                                filth++;
                                check(h, data(h).at(h.absolutePos(ground)) == network, "长出来的污秽应当记在这个核心名下");
                                check(h, network.original(h.absolutePos(ground)) == Blocks.DIRT.defaultBlockState(), "污秽应当记着原来是泥土");
                            }
                            if (h.getBlockState(new BlockPos(x, 1, z)).is(Corruption.VEIN.get())) veins++;
                        }
                    }
                    check(h, filth > 0, "地面应当有被变成腐败污秽的");
                    check(h, veins > 0, "应当有脉络");
                })
                .thenExecute(() -> h.getLevel().destroyBlock(h.absolutePos(core), false))
                .thenWaitUntil(() -> {
                    for (int x = 0; x < 11; x++) {
                        for (int z = 0; z < 11; z++) {
                            h.assertBlockPresent(Blocks.DIRT, new BlockPos(x, 0, z));
                            h.assertBlockNotPresent(Corruption.VEIN.get(), new BlockPos(x, 1, z));
                        }
                    }
                })
                .thenSucceed();
    }

    /**
     * 大网络：61×61 的污秽地面（3720 格，原来是石头和泥土相间）上一半铺着脉络。拆核心后全部变回去、脉络全没，
     * 消散最慢的一刻要在 20 毫秒以内（一刻 50 毫秒）；存档编码存了再读回来，原方块一个不少。
     */
    private static void large(GameTestHelper h) {
        int r = 30;
        BlockPos below = new BlockPos(r, 0, r), core = below.above();
        // 这片地跨好几个区块，测试框架不会替它们保持加载；没加载的方块消散会一直等着
        BlockPos min = h.absolutePos(BlockPos.ZERO), max = h.absolutePos(new BlockPos(2 * r, 0, 2 * r));
        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = Math.min(min.getX(), max.getX()) >> 4; cx <= Math.max(min.getX(), max.getX()) >> 4; cx++) {
            for (int cz = Math.min(min.getZ(), max.getZ()) >> 4; cz <= Math.max(min.getZ(), max.getZ()) >> 4; cz++) {
                chunks.add(new ChunkPos(cx, cz));
                h.getLevel().setChunkForced(cx, cz, true);
            }
        }
        clear(h, new BlockPos(0, 1, 0), new BlockPos(2 * r, 3, 2 * r));
        var data = data(h);
        h.setBlock(core, Corruption.CORE.get());
        var network = data.ofCore(h.absolutePos(core));
        List<BlockPos> floor = new ArrayList<>();
        for (int x = 0; x <= 2 * r; x++) for (int z = 0; z <= 2 * r; z++) floor.add(new BlockPos(x, 0, z));
        // 由近到远放，每一格都挨着已经在网络里的格子
        floor.sort(Comparator.comparingInt(p -> p.distManhattan(below)));
        for (BlockPos p : floor) {
            h.setBlock(p, Corruption.BLOCK.get());
            data.remember(h.absolutePos(p), original(p));
        }
        var vein = Corruption.VEIN.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        for (BlockPos p : floor) if ((p.getX() + p.getZ()) % 2 == 0 && !p.equals(below)) h.setBlock(p.above(), vein);
        check(h, network.size() > 5000, "网络应当记下全部污秽和脉络，实际 " + network.size());
        int converted = network.size();
        var saved = CorruptionNetworks.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        var loaded = CorruptionNetworks.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        var reloaded = loaded.ofCore(h.absolutePos(core));
        check(h, reloaded != null && reloaded.size() == converted, "存了再读回来网络大小不变");
        for (BlockPos p : floor) {
            if (reloaded.original(h.absolutePos(p)) != original(p)) {
                check(h, false, "存了再读回来 " + p + " 的原方块应当是 " + original(p));
            }
        }
        h.getLevel().destroyBlock(h.absolutePos(core), false);
        h.succeedWhen(() -> {
            for (BlockPos p : floor) {
                h.assertBlockPresent(original(p).getBlock(), p);
                h.assertBlockNotPresent(Corruption.VEIN.get(), p.above());
            }
            long worst = data.worstFadeNanos();
            LogUtils.getLogger().info("[corruption] restored {} blocks, slowest fade tick {} ms", floor.size(), worst / 1e6);
            check(h, worst < 20_000_000L, "消散最慢的一刻用了 " + worst / 1e6 + " 毫秒");
            for (ChunkPos c : chunks) h.getLevel().setChunkForced(c.x(), c.z(), false);
        });
    }

    private static BlockState original(BlockPos p) {
        return ((p.getX() / 3 + p.getZ() / 3) % 2 == 0 ? Blocks.STONE : Blocks.DIRT).defaultBlockState();
    }

    /**
     * 核心随网络长大：0 级碰撞箱只有半格；网络到 100、300、600 块（上限的 1/6、1/2、满）时方块状态的阶段变成 1、2、3，
     * 碰撞箱变成整格，亮度跟着变亮。污秽由近到远一批批放，每批等服务端同步（每秒一次）。
     */
    private static void grows(GameTestHelper h) {
        var chunks = forceArea(h, new BlockPos(0, 0, 0), new BlockPos(24, 0, 24));
        BlockPos below = new BlockPos(12, 0, 12), core = below.above();
        clear(h, new BlockPos(0, 1, 0), new BlockPos(24, 4, 24));
        h.setBlock(core, Corruption.CORE.get());
        List<BlockPos> floor = new ArrayList<>();
        for (int x = 0; x < 25; x++) for (int z = 0; z < 25; z++) floor.add(new BlockPos(x, 0, z));
        floor.sort(Comparator.comparingInt(p -> p.distManhattan(below)));
        int[] placed = {0};
        java.util.function.IntConsumer fill = upTo -> {
            for (; placed[0] < upTo; placed[0]++) h.setBlock(floor.get(placed[0]), Corruption.BLOCK.get());
        };
        java.util.function.IntFunction<Runnable> stageIs = want -> () -> {
            int stage = h.getBlockState(core).getValue(CorruptionCoreBlock.STAGE);
            check(h, stage == want, "网络 " + data(h).ofCore(h.absolutePos(core)).size() + " 块时阶段应当是 " + want + "，实际 " + stage);
        };
        var level = h.getLevel();
        h.startSequence()
                .thenExecute(() -> {
                    var shape = h.getBlockState(core).getShape(level, h.absolutePos(core));
                    check(h, shape.max(Direction.Axis.Y) <= .5 + 1e-6, "0 级碰撞箱应当只有半格高");
                })
                .thenExecute(() -> fill.accept(99))
                .thenExecuteAfter(25, stageIs.apply(0))
                .thenExecute(() -> fill.accept(100))
                .thenWaitUntil(stageIs.apply(1))
                .thenExecute(() -> {
                    var shape = h.getBlockState(core).getShape(level, h.absolutePos(core));
                    check(h, shape.max(Direction.Axis.Y) >= 1 - 1e-6, "1 级以后碰撞箱应当是整格");
                    check(h, h.getBlockState(core).getLightEmission() > Corruption.CORE.get().defaultBlockState().getLightEmission(), "长大以后应当更亮");
                })
                .thenExecute(() -> fill.accept(300))
                .thenWaitUntil(stageIs.apply(2))
                .thenExecute(() -> fill.accept(600))
                .thenWaitUntil(stageIs.apply(3))
                .thenExecute(() -> release(h, chunks))
                .thenSucceed();
    }

    /** 站在腐败污秽上：猪掉血，戴头盔的僵尸（亡灵）不掉。 */
    private static void contact(GameTestHelper h) {
        floor(h, 0, 5, Corruption.BLOCK.get());
        Pig[] pig = {null};
        Zombie[] zombie = {null};
        h.startSequence()
                .thenWaitUntil(entitiesTicking(h, new BlockPos(1, 1, 1), new BlockPos(3, 1, 3)))
                .thenExecute(() -> {
                    pig[0] = h.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(1, 1, 1));
                    zombie[0] = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(3, 1, 3));
                    zombie[0].setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                })
                .thenWaitUntil(RealTimeWait.paced(() -> check(h, pig[0].getHealth() < pig[0].getMaxHealth(), "猪站在腐败上应当掉血")))
                .thenExecuteAfter(20, () -> {
                    var source = pig[0].getLastDamageSource();
                    check(h, source != null && source.is(Corruption.DAMAGE), "伤害应当来自腐败，实际 " + (source == null ? "无" : source.getMsgId()));
                    check(h, zombie[0].tickCount > 20, "僵尸应当在走刻");
                    check(h, zombie[0].getHealth() == zombie[0].getMaxHealth(), "僵尸是亡灵，不应当受伤");
                    unforce(h, new BlockPos(1, 1, 1), new BlockPos(3, 1, 3));
                })
                .thenSucceed();
    }

    /**
     * 生物所在的区块强制加载、进入实体走刻之后再放生物。测试框架只强制加载测试结构所在的区块，
     * 旁边区块里的生物不走刻；刚加载的区块里放早了还会被按刻算的自动存档存盘卸载（见 #199）。
     */
    private static Runnable entitiesTicking(GameTestHelper h, BlockPos... at) {
        var level = h.getLevel();
        var chunks = java.util.Arrays.stream(at).map(p -> net.minecraft.world.level.ChunkPos.containing(h.absolutePos(p))).distinct().toList();
        for (var c : chunks) level.setChunkForced(c.x(), c.z(), true);
        return RealTimeWait.paced(() -> {
            for (var c : chunks) check(h, level.areEntitiesActuallyLoadedAndTicking(c), "区块 " + c + " 应当进入实体走刻");
        });
    }

    private static void unforce(GameTestHelper h, BlockPos... at) {
        for (BlockPos p : at) {
            var c = net.minecraft.world.level.ChunkPos.containing(h.absolutePos(p));
            h.getLevel().setChunkForced(c.x(), c.z(), false);
        }
    }

    /** 生存模式的玩家站在脉络上：掉魔力。 */
    private static void mana(GameTestHelper h) {
        floor(h, 0, 3, Blocks.STONE);
        h.setBlock(new BlockPos(1, 1, 1), Corruption.VEIN.get().defaultBlockState().setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(net.minecraft.core.Direction.DOWN), true));
        BlockPos at = h.absolutePos(new BlockPos(1, 1, 1));
        ServerPlayer player = player(h, at);
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = magic.mana = 1000;
        h.succeedWhen(() -> {
            check(h, magic.mana < 1000, "碰到脉络应当掉魔力：魔力 " + magic.mana + "，模式 " + player.gameMode());
            check(h, player.getHealth() < player.getMaxHealth(), "碰到脉络应当受伤");
            h.getLevel().getServer().getPlayerList().remove(player);
        });
    }

    /** 生存模式的玩家拿镐拆核心：拆得掉、不掉东西、网络散了。 */
    private static void playerBreak(GameTestHelper h) {
        BlockPos core = new BlockPos(1, 2, 1);
        h.setBlock(core, Corruption.CORE.get());
        h.setBlock(new BlockPos(2, 2, 1), Corruption.BLOCK.get());
        BlockPos at = h.absolutePos(core);
        ServerPlayer player = player(h, at.offset(-1, -1, -1));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
        check(h, player.gameMode.destroyBlock(at), "玩家应当拆得掉核心");
        h.assertBlockNotPresent(Corruption.CORE.get(), core);
        check(h, data(h).ofCore(at) == null, "核心没了网络就散了");
        h.runAfterDelay(2, () -> {
            check(h, h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3)).isEmpty(), "核心不应当掉落");
            h.getLevel().getServer().getPlayerList().remove(player);
            h.succeed();
        });
    }

    /** 和 {@code CameraShotGameTests} 一样的真玩家：{@code setGameMode} 对它真的生效（测试框架的假玩家一直是创造模式）。 */
    private static ServerPlayer player(GameTestHelper h, BlockPos at) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "corruption-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setGameMode(GameType.SURVIVAL);
        p.setPos(at.getBottomCenter());
        return p;
    }

    /** 被腐败杀死的猪（最大生命 10）给网络加 10 点电荷（默认每点最大生命 1 点）。 */
    private static void feeds(GameTestHelper h) {
        BlockPos core = new BlockPos(1, 2, 1);
        h.setBlock(core, Corruption.CORE.get());
        // 悬空的一排污秽挨着核心，名下 4 格
        for (int x = 2; x <= 5; x++) h.setBlock(new BlockPos(x, 2, 1), Corruption.BLOCK.get());
        var network = data(h).ofCore(h.absolutePos(core));
        check(h, network != null && network.size() == 4, "挨着核心放的污秽应当记在它名下");
        BlockPos spot = new BlockPos(4, 3, 1);
        clear(h, new BlockPos(2, 3, 0), new BlockPos(6, 5, 2));
        Pig[] pig = {null};
        h.startSequence()
                .thenWaitUntil(entitiesTicking(h, spot))
                .thenExecute(() -> {
                    pig[0] = h.spawnWithNoFreeWill(EntityType.PIG, spot);
                    pig[0].setHealth(1);
                })
                .thenWaitUntil(RealTimeWait.paced(() -> check(h, !pig[0].isAlive(), "猪应当被腐败杀死，生命 " + pig[0].getHealth() + "，走了 " + pig[0].tickCount + " 刻")))
                .thenExecute(() -> {
                    var source = pig[0].getLastDamageSource();
                    check(h, source != null && source.is(Corruption.DAMAGE), "猪应当死于腐败，实际 " + (source == null ? "无" : source.getMsgId()));
                    check(h, network.fed() == 10, "网络应当从猪身上得到 10 点电荷，实际 " + network.fed());
                    check(h, network.charge() > 0, "电荷应当变成蔓延的光标");
                    unforce(h, spot);
                })
                .thenSucceed();
    }

    /** 两个网络挨上：不合并，两个核心都在，各自的方块还是各自的。 */
    private static void apart(GameTestHelper h) {
        BlockPos leftCore = new BlockPos(1, 2, 1), rightCore = new BlockPos(12, 2, 1);
        h.setBlock(leftCore, Corruption.CORE.get());
        h.setBlock(rightCore, Corruption.CORE.get());
        for (int x = 2; x <= 9; x++) h.setBlock(new BlockPos(x, 2, 1), Corruption.BLOCK.get());
        h.setBlock(new BlockPos(11, 2, 1), Corruption.BLOCK.get());
        var left = data(h).ofCore(h.absolutePos(leftCore));
        var right = data(h).ofCore(h.absolutePos(rightCore));
        // 补上中间这一格，两个网络就挨上了（它跟着先找到的邻居，也就是左边）
        h.setBlock(new BlockPos(10, 2, 1), Corruption.BLOCK.get());
        h.runAfterDelay(20, () -> {
            h.assertBlockPresent(Corruption.CORE.get(), leftCore);
            h.assertBlockPresent(Corruption.CORE.get(), rightCore);
            check(h, data(h).ofCore(h.absolutePos(leftCore)) == left && data(h).ofCore(h.absolutePos(rightCore)) == right, "两个网络都还在");
            for (int x = 2; x <= 10; x++) check(h, data(h).at(h.absolutePos(new BlockPos(x, 2, 1))) == left, "第 " + x + " 格应当还归左边");
            check(h, data(h).at(h.absolutePos(new BlockPos(11, 2, 1))) == right, "第 11 格应当还归右边");
            h.succeed();
        });
    }

    /** 强制加载这片地跨过的区块（测试框架只替测试结构所在的区块保持加载），返回它们，测试结束时放开。 */
    private static List<ChunkPos> forceArea(GameTestHelper h, BlockPos from, BlockPos to) {
        BlockPos a = h.absolutePos(from), b = h.absolutePos(to);
        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                chunks.add(new ChunkPos(cx, cz));
                h.getLevel().setChunkForced(cx, cz, true);
            }
        }
        return chunks;
    }

    private static void release(GameTestHelper h, List<ChunkPos> chunks) {
        for (ChunkPos c : chunks) h.getLevel().setChunkForced(c.x(), c.z(), false);
    }

    /**
     * 一块 {@code size}×{@code size} 的污秽地（记着原来是泥土）、中间上方一个核心，由近到远放，全归这个核心。
     * {@code veins} 时污秽上再全铺脉络。返回核心的相对位置。
     */
    private static BlockPos filthField(GameTestHelper h, int size, boolean veins) {
        int r = size / 2;
        BlockPos below = new BlockPos(r, 0, r), core = below.above();
        clear(h, new BlockPos(0, 1, 0), new BlockPos(size - 1, 4, size - 1));
        h.setBlock(core, Corruption.CORE.get());
        List<BlockPos> floor = new ArrayList<>();
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) floor.add(new BlockPos(x, 0, z));
        floor.sort(Comparator.comparingInt(p -> p.distManhattan(below)));
        for (BlockPos p : floor) {
            h.setBlock(p, Corruption.BLOCK.get());
            data(h).remember(h.absolutePos(p), Blocks.DIRT.defaultBlockState());
        }
        if (veins) {
            var vein = Corruption.VEIN.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
            for (BlockPos p : floor) if (!p.equals(below)) h.setBlock(p.above(), vein);
        }
        return core;
    }

    /**
     * 网络满了（25×25 = 625 格，上限 600）：杀生的电荷每来一次就种一个子核心，一共 3 个，彼此和母核心都隔开至少 16 格，
     * 记着母核心是谁；第 4 次种满了，电荷交给最近的子核心。拆掉母核心后，子核心还在，变成独立的。
     */
    private static void splits(GameTestHelper h) {
        var chunks = forceArea(h, new BlockPos(0, 0, 0), new BlockPos(24, 0, 24));
        BlockPos core = filthField(h, 25, false);
        var data = data(h);
        var root = data.ofCore(h.absolutePos(core));
        check(h, root.size() == 625, "母网络应当有 625 格，实际 " + root.size());
        BlockPos killer = h.absolutePos(new BlockPos(12, 0, 13));
        List<BlockPos> cores = new ArrayList<>(List.of(h.absolutePos(core)));
        for (int i = 1; i <= 3; i++) {
            check(h, data.feed(h.getLevel(), killer, killer.above(), 40), "第 " + i + " 次杀生应当记到网络上");
            check(h, data.satellites(root) == i, "第 " + i + " 次杀生后应当有 " + i + " 个子核心，实际 " + data.satellites(root));
        }
        List<CorruptionNetworks.Network> children = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(0, 1, 0)), h.absolutePos(new BlockPos(24, 1, 24)))) {
            var n = data.ofCore(p);
            if (n != null && n != root) {
                children.add(n);
                cores.add(p.immutable());
            }
        }
        check(h, children.size() == 3, "地上应当有 3 个子核心，实际 " + children.size());
        for (var child : children) {
            check(h, child.parent() == root.id(), "子核心应当记着母核心");
            check(h, child.charge() > 0, "子核心应当接到电荷");
        }
        for (int i = 0; i < cores.size(); i++) for (int j = i + 1; j < cores.size(); j++) {
            check(h, cores.get(i).distSqr(cores.get(j)) >= 16 * 16, "核心之间应当至少隔 16 格：" + cores.get(i) + " 和 " + cores.get(j));
        }
        int before = 0;
        for (var child : children) before += child.charge();
        data.feed(h.getLevel(), killer, killer.above(), 40);
        int after = 0;
        for (var child : children) after += child.charge();
        check(h, data.satellites(root) == 3, "子核心种满 3 个就不再种");
        check(h, after >= before + 40, "种满后杀生的电荷应当交给子核心：之前 " + before + "，之后 " + after);
        check(h, root.reserve() == 0, "同族还有没满的网络，电荷不进储备");
        h.getLevel().destroyBlock(h.absolutePos(core), false);
        for (int i = 1; i < cores.size(); i++) {
            var child = data.ofCore(cores.get(i));
            check(h, child != null && child.parent() == 0, "母核心拆掉后子核心应当变成独立的");
            check(h, h.getLevel().getBlockState(cores.get(i)).is(Corruption.CORE.get()), "子核心应当还在");
        }
        release(h, chunks);
        h.succeed();
    }

    /** 满了但没地方种子核心（20×20 的污秽上铺满脉络，799 格，离核心最远也不到 16 格），又没有同族：杀生的电荷存进储备。 */
    private static void banks(GameTestHelper h) {
        var chunks = forceArea(h, new BlockPos(0, 0, 0), new BlockPos(19, 0, 19));
        BlockPos core = filthField(h, 20, true);
        var data = data(h);
        var root = data.ofCore(h.absolutePos(core));
        check(h, root.size() >= 600, "网络应当是满的，实际 " + root.size());
        BlockPos killer = h.absolutePos(new BlockPos(10, 0, 11));
        data.feed(h.getLevel(), killer, killer.above(), 30);
        check(h, data.satellites(root) == 0, "没有地方种子核心");
        check(h, root.reserve() == 30, "电荷应当存进储备，实际 " + root.reserve());
        release(h, chunks);
        h.succeed();
    }

    /** 拆掉核心：不掉东西，近的先消散、远的后消散，最后全没了。 */
    private static void fade(GameTestHelper h) {
        BlockPos core = new BlockPos(1, 2, 1);
        h.setBlock(core, Corruption.CORE.get());
        for (int x = 2; x <= 14; x++) h.setBlock(new BlockPos(x, 2, 1), Corruption.BLOCK.get());
        check(h, data(h).ofCore(h.absolutePos(core)).size() == 13, "网络应当是 13 格");
        h.getLevel().destroyBlock(h.absolutePos(core), true);
        check(h, data(h).ofCore(h.absolutePos(core)) == null, "核心没了网络就散了");
        h.runAfterDelay(1, () -> check(h, h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(core)).inflate(3)).isEmpty(), "核心不应当掉落"));
        // 拆后 12 刻才开始散，每刻推 0.25 格：第 20 刻推到 2 格
        h.runAfterDelay(20, () -> {
            h.assertBlockNotPresent(Corruption.BLOCK.get(), new BlockPos(2, 2, 1));
            h.assertBlockPresent(Corruption.BLOCK.get(), new BlockPos(14, 2, 1));
        });
        h.succeedWhen(() -> {
            // 这排污秽是直接放的，没有原方块，消散成空气
            for (int x = 2; x <= 14; x++) h.assertBlockPresent(Blocks.AIR, new BlockPos(x, 2, 1));
        });
    }
}
