package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.LogosMod;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.gate.Gate;
import dev.logos.dimension.gate.GatePortalBlock;
import dev.logos.dimension.gate.GateShape;
import dev.logos.dimension.gate.GateStoneBlockEntity;
import dev.logos.dimension.gate.GateTravel;
import dev.logos.runtime.ManaInfusion;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 远行之门的世界测试。每个测试用自己一块远离原点的地方：门靠兴趣点互相找，靠太近会串。
 * 门不在测试结构里，测试里自己搭。
 */
public final class GateGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static final BlockPos BIG_PIVOT = new BlockPos(9, 0, 3), SMALL_PIVOT = new BlockPos(4, 0, 2);

    public static void register(IEventBus bus) {
        var tests = new LinkedHashMap<String, Consumer<GateGameTestsTest>>();
        tests.put("gate_big_opening", t -> bigOpening(t.h));
        tests.put("gate_small_opening_edges", t -> smallOpeningEdges(t.h));
        tests.put("gate_mana_items", t -> manaItems(t.h));
        tests.put("gate_ignite_collapse_relight", t -> igniteCollapseRelight(t.h));
        tests.put("gate_player_frame", t -> playerFrame(t.h));
        tests.put("gate_crossing", t -> crossing(t.h));
        tests.put("gate_worldgen_data", t -> worldgenData(t.h));
        tests.forEach((name, test) -> {
            Consumer<GameTestHelper> function = h -> test.accept(new GateGameTestsTest(h));
            FUNCTIONS.register(name, () -> function);
        });
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            for (var name : tests.keySet()) {
                var env = event.registerEnvironment(LogosDimension.id(name), new TestEnvironmentDefinition.AllOf());
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 20000, 0, true, Rotation.NONE, false, 1, 1, false, 8)));
            }
        });
    }

    private record GateGameTestsTest(GameTestHelper h) {}

    private static void check(GameTestHelper h, boolean ok, String message) {
        h.assertTrue(ok, net.minecraft.network.chat.Component.literal(message));
    }

    private static ServerPlayer player(GameTestHelper h, BlockPos at) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "gate-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setGameMode(GameType.SURVIVAL);
        p.setPos(at.getBottomCenter());
        return p;
    }

    private static void placeTemplate(ServerLevel level, String name, BlockPos stone, BlockPos pivot, Rotation rotation) {
        BlockPos origin = stone.subtract(pivot);
        level.getStructureManager().get(LogosDimension.id(name)).orElseThrow().placeInWorld(level, origin, origin,
                new StructurePlaceSettings().setRotation(rotation).setRotationPivot(pivot).setIgnoreEntities(true), level.getRandom(), 2);
    }

    /** 测试结束时释放强制加载的区块，别占着服务器影响后面的测试。 */
    private static void release(ServerLevel level, BlockPos pos) {
        level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, false);
    }

    private static long portals(Level level, List<BlockPos> cells) {
        return cells.stream().filter(c -> level.getBlockState(c).is(Gate.GATE_PORTAL.get())).count();
    }

    /** 蓝图转出来的大号门：放正和转 90° 都判定出同样的 57 格，朝向跟着变。 */
    private static void bigOpening(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos stone = new BlockPos(30000, 120, 30000);
        placeTemplate(level, "gate/journey_gate", stone, BIG_PIVOT, Rotation.NONE);
        check(h, level.getBlockState(stone).is(Gate.GATE_STONE.get()), "大号门地面中心应是启门石");
        var straight = GateShape.find(level, stone);
        check(h, straight != null && straight.cells().size() == 57 && straight.axis() == Direction.Axis.X, "放正的大号门应判定出沿 X 的 57 格：" + straight);
        check(h, !level.getBlockState(stone.above()).is(Gate.GATE_PORTAL.get()), "自然生成的门不应点亮");
        BlockPos turned = stone.offset(0, 0, 64);
        placeTemplate(level, "gate/journey_gate", turned, BIG_PIVOT, Rotation.CLOCKWISE_90);
        var rotated = GateShape.find(level, turned);
        check(h, rotated != null && rotated.cells().size() == 57 && rotated.axis() == Direction.Axis.Z, "转 90° 的大号门应判定出沿 Z 的 57 格：" + rotated);
        h.succeed();
    }

    /** 小号门当场点亮 21 格；四个楼梯的空缺各由一格传送面补，不多不少。两个朝向都查。 */
    private static void smallOpeningEdges(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        check(h, GateTravel.linked(Level.OVERWORLD) && GateTravel.linked(LogosDimension.CLOUDSEA) && !GateTravel.linked(Level.NETHER), "门只连主世界和远行大陆");
        for (Direction.Axis axis : List.of(Direction.Axis.X, Direction.Axis.Z)) {
            BlockPos near = new BlockPos(31000, 0, axis == Direction.Axis.X ? 31000 : 31400);
            BlockPos cell = GateTravel.createSmallGate(level, near, axis);
            check(h, cell != null, "小号门应当生成");
            var cells = GateShape.connected(level, cell);
            check(h, cells.size() == 21, axis + " 小号门应是 21 格：" + cells.size());
            check(h, level.getBlockState(cell).getValue(GatePortalBlock.AXIS) == axis, "小号门朝向应跟来时的门相同");
            check(h, level.getBlockState(cell.below()).is(Gate.GATE_STONE.get()), "门洞正下方应是启门石");
            check(h, level.getBlockEntity(cell.below()) instanceof GateStoneBlockEntity s && s.mana() == GateStoneBlockEntity.MAX_MANA, "小号门的启门石应当是满的");
            int stairs = 0;
            for (BlockPos c : cells) for (Direction d : GateShape.plane(axis)) {
                BlockPos n = c.relative(d);
                if (GateShape.partial(level.getBlockState(n)) && level.getBlockState(c).getValue(GatePortalBlock.edge(d))) stairs++;
            }
            check(h, stairs == 4, axis + " 四个楼梯应各补一次，实际 " + stairs);
        }
        h.succeed();
    }

    /** 任何纯净魔力物品都能放，每次吃一个；被污染的碎片不收，也不被吃掉；别的东西照常使用。 */
    private static void manaItems(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos stone = new BlockPos(32000, 120, 32000);
        level.setChunkForced(stone.getX() >> 4, stone.getZ() >> 4, true); // 没有玩家在附近，方块实体和实体要靠强制加载才走刻
        placeTemplate(level, "gate/small_gate", stone, SMALL_PIVOT, Rotation.NONE);
        var be = (GateStoneBlockEntity) level.getBlockEntity(stone);
        ServerPlayer player = player(h, stone.above());
        BlockHitResult hit = new BlockHitResult(stone.getCenter(), Direction.UP, stone, false);
        record Use(ItemStack stack, double expect) {}
        double total = 0;
        for (Use use : List.of(new Use(new ItemStack(Items.AMETHYST_SHARD, 2), 25), new Use(new ItemStack(Items.LAPIS_LAZULI, 2), 50), new Use(new ItemStack(Items.ECHO_SHARD, 2), 400),
                new Use(new ItemStack(LogosMod.MANA_SHARD.get(), 2), 500))) {
            int before = use.stack.getCount();
            level.getBlockState(stone).useItemOn(use.stack, level, player, InteractionHand.MAIN_HAND, hit);
            total += use.expect;
            check(h, Math.abs(be.mana() - total) < 1e-6, use.stack.getItem() + " 之后应有 " + total + "，实际 " + be.mana());
            check(h, use.stack.getCount() == before - 1, "每次只吃一个");
        }
        ItemStack tainted = new ItemStack(LogosMod.TAINTED_MANA_SHARD.get(), 2);
        level.getBlockState(stone).useItemOn(tainted, level, player, InteractionHand.MAIN_HAND, hit);
        check(h, Math.abs(be.mana() - total) < 1e-6 && tainted.getCount() == 2, "被污染的魔力碎片不应被接受");
        InteractionResult dirt = level.getBlockState(stone).useItemOn(new ItemStack(Items.DIRT), level, player, InteractionHand.MAIN_HAND, hit);
        check(h, dirt == InteractionResult.PASS, "别的物品应当照常使用：" + dirt);
        // 还差 25：一块晶石也只算灌满，整块吃掉，溢出的不存
        ItemStack gem = new ItemStack(LogosMod.MANA_GEMSTONE.get(), 2);
        level.getBlockState(stone).useItemOn(gem, level, player, InteractionHand.MAIN_HAND, hit);
        check(h, be.mana() == GateStoneBlockEntity.MAX_MANA && gem.getCount() == 1, "晶石应灌满并只吃一块");
        check(h, level.getBlockState(stone).getValue(dev.logos.dimension.gate.GateStoneBlock.CHARGE) == 4, "灌满后外观应是最亮一档");
        h.succeedWhen(() -> {
            check(h, level.getBlockState(stone.above()).is(Gate.GATE_PORTAL.get()), "灌满应自动开门");
            level.getBlockState(stone).useItemOn(gem, level, player, InteractionHand.MAIN_HAND, hit);
            check(h, gem.getCount() == 1, "已经满了不应再吃物品");
            level.getServer().getPlayerList().remove(player);
            release(level, stone);
        });
    }

    /** 走魔力容器接口（术式、存储罐、收集器都走这里）灌满开门；打掉门框连锁消失、魔力还在；修好后空手重开；挖走启门石门就没了。 */
    private static void igniteCollapseRelight(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos stone = new BlockPos(33000, 120, 33000);
        level.setChunkForced(stone.getX() >> 4, stone.getZ() >> 4, true); // 没有玩家在附近，方块实体和实体要靠强制加载才走刻
        placeTemplate(level, "gate/small_gate", stone, SMALL_PIVOT, Rotation.NONE);
        var opening = GateShape.find(level, stone).cells();
        ServerPlayer player = player(h, stone.above());
        BlockPos stair = stone.offset(-2, 1, 0);
        var stairState = level.getBlockState(stair);
        check(h, GateShape.partial(stairState), "(−2,1,0) 应是楼梯");
        var vessel = ManaInfusion.vesselAt(level, stone);
        check(h, vessel != null && vessel.maxMana() == GateStoneBlockEntity.MAX_MANA, "启门石应是魔力容器，容量 1000");
        check(h, vessel.receiveMana(400) == 400, "应收下 400");
        check(h, portals(level, opening) == 0 && level.getBlockState(stone).getValue(dev.logos.dimension.gate.GateStoneBlock.CHARGE) == 1, "没满不开门");
        check(h, vessel.receiveMana(5000) == 600, "只收缺的部分");
        int[] stage = {0};
        boolean[] gradual = {false};
        // 区块进入走刻状态要几刻，这里每刻查一次，到了就走下一步；没到的步骤靠断言失败等下一刻重试
        h.succeedWhen(() -> {
            long lit = portals(level, opening);
            if (stage[0] == 0) {
                if (lit > 0 && lit < 21) gradual[0] = true;
                check(h, lit == 21, "应填满 21 格：" + lit);
                check(h, gradual[0], "门洞应一点点填满");
                level.destroyBlock(stair, false);
                stage[0] = 1;
                check(h, false, "下一步");
            }
            if (stage[0] == 1) {
                check(h, lit == 0, "打掉门框应当连锁消失，还剩 " + lit);
                check(h, vessel.mana() == GateStoneBlockEntity.MAX_MANA, "魔力应当还在");
                level.setBlockAndUpdate(stair, stairState);
                level.getBlockState(stone).useWithoutItem(level, player, new BlockHitResult(stone.getCenter(), Direction.UP, stone, false));
                stage[0] = 2;
                check(h, false, "下一步");
            }
            if (stage[0] == 2) {
                check(h, lit == 21, "修好后空手右键应重新开门：" + lit);
                level.destroyBlock(stone, true);
                stage[0] = 3;
                check(h, false, "下一步");
            }
            check(h, lit == 0, "挖走启门石后门应消失，还剩 " + lit);
            var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(stone).inflate(3), e -> e.getItem().is(Gate.GATE_STONE.get().asItem()));
            check(h, !drops.isEmpty(), "启门石应当掉落");
            level.getServer().getPlayerList().remove(player);
            release(level, stone);
        });
    }

    /** 玩家自己搭的方形门框也能开；门框缺一块就开不了，魔力留着。 */
    private static void playerFrame(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos stone = new BlockPos(34000, 120, 34000);
        level.setChunkForced(stone.getX() >> 4, stone.getZ() >> 4, true); // 没有玩家在附近，方块实体和实体要靠强制加载才走刻
        // 宽 3 高 3 的门框，沿 Z；上边先空一格
        for (int z = -2; z <= 2; z++) for (int y = 0; y <= 4; y++) {
            boolean edge = z == -2 || z == 2 || y == 0 || y == 4;
            level.setBlockAndUpdate(stone.offset(0, y, z), edge ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(stone, Gate.GATE_STONE.get().defaultBlockState());
        level.setBlockAndUpdate(stone.offset(0, 4, 1), Blocks.AIR.defaultBlockState());
        var be = (GateStoneBlockEntity) level.getBlockEntity(stone);
        be.receiveMana(1000);
        check(h, !be.igniting() && be.mana() == 1000, "门框没合拢不应开门，魔力留着");
        level.setBlockAndUpdate(stone.offset(0, 4, 1), Blocks.COBBLESTONE.defaultBlockState());
        be.tryIgnite(null);
        h.succeedWhen(() -> {
            var cell = stone.above();
            check(h, level.getBlockState(cell).is(Gate.GATE_PORTAL.get()), "合拢后应开门");
            check(h, level.getBlockState(cell).getValue(GatePortalBlock.AXIS) == Direction.Axis.Z, "门面应沿 Z");
            check(h, GateShape.connected(level, cell).size() == 9, "应是 9 格");
            release(level, stone);
        });
    }

    /**
     * 真让一个实体穿过去：另一侧生成点亮的小号门，到达的区块有传送票保持加载；再过一次不会多生成一扇；从另一侧回来落在原来这扇门上。
     *
     * <p>测试服务器是加速跑的，区块要花几秒才能升到走刻状态，这段时间传送票（300 刻）已经过期，所以这里只查传送票本身；
     * 实体到达后真的在走刻由 {@code GateView} 在实际客户端里检查（真实时间下玩家走一趟）。
     */
    private static void crossing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerLevel cloudsea = level.getServer().getLevel(LogosDimension.CLOUDSEA);
        check(h, cloudsea != null, "测试世界应有远行大陆");
        BlockPos stone = new BlockPos(1200, 120, 1200);
        level.setChunkForced(stone.getX() >> 4, stone.getZ() >> 4, true); // 没有玩家在附近，方块实体和实体要靠强制加载才走刻
        placeTemplate(level, "gate/small_gate", stone, SMALL_PIVOT, Rotation.NONE);
        ((GateStoneBlockEntity) level.getBlockEntity(stone)).igniteNow();
        BlockPos cell = stone.above();
        ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        stand.snapTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
        h.runAfterDelay(1, () -> level.addFreshEntity(stand));
        h.succeedWhen(() -> {
            check(h, stand.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION, "盔甲架应穿过门");
            // 兴趣点要下一刻才登记，没查到就等下一刻
            BlockPos exit = GateTravel.findExit(cloudsea, stone).orElse(null);
            check(h, exit != null, "远行大陆应有一扇门");
            var door = GateShape.connected(cloudsea, exit);
            check(h, door.size() == 21, "另一侧应是小号门：" + door.size());
            BlockPos arrival = GateTravel.arrivalCell(cloudsea, exit);
            check(h, cloudsea.getBlockState(arrival.below()).is(Gate.GATE_STONE.get()), "到达位置应在门洞中央、启门石上方");
            check(h, cloudsea.getBlockState(arrival.below()).getValue(dev.logos.dimension.gate.GateStoneBlock.CHARGE) == 4, "另一侧的启门石应当是满的");
            var tickets = cloudsea.getDataStorage().computeIfAbsent(net.minecraft.world.level.TicketStorage.TYPE)
                    .getTickets(net.minecraft.world.level.ChunkPos.containing(exit).pack());
            check(h, tickets.stream().anyMatch(t -> t.getType() == net.minecraft.server.level.TicketType.PORTAL), "到达的区块应有传送票：" + tickets);
            ArmorStand probe = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            probe.snapTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5);
            var again = GateTravel.destination(level, probe, cell);
            check(h, again != null && again.newLevel() == cloudsea && door.contains(BlockPos.containing(again.position())), "再过一次应到同一扇门：" + (again == null ? null : again.position()));
            ArmorStand back = EntityType.ARMOR_STAND.create(cloudsea, EntitySpawnReason.COMMAND);
            back.snapTo(arrival.getX() + .5, arrival.getY(), arrival.getZ() + .5);
            var home = GateTravel.destination(cloudsea, back, arrival);
            check(h, home != null && home.newLevel() == level, "从远行大陆应回主世界");
            check(h, GateShape.connected(level, cell).contains(BlockPos.containing(home.position())), "应落回原来这扇门：" + home.position());
            release(level, stone);
        });
    }

    /** 世界生成的数据都接上了：结构、结构集、模板池、群系标签。 */
    private static void worldgenData(GameTestHelper h) {
        var registries = h.getLevel().registryAccess();
        check(h, registries.lookupOrThrow(Registries.STRUCTURE).containsKey(LogosDimension.id("journey_gate")), "结构");
        check(h, registries.lookupOrThrow(Registries.STRUCTURE_SET).containsKey(LogosDimension.id("journey_gate")), "结构集");
        check(h, registries.lookupOrThrow(Registries.TEMPLATE_POOL).containsKey(LogosDimension.id("gate/journey_gate")), "模板池");
        TagKey<Biome> tag = TagKey.create(Registries.BIOME, LogosDimension.id("has_structure/journey_gate"));
        var biomes = registries.lookupOrThrow(Registries.BIOME);
        check(h, biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).is(tag) && biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.FOREST).is(tag), "平原和森林应能生成");
        check(h, !biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.OCEAN).is(tag) && !biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.DESERT).is(tag), "海洋和沙漠不生成");
        check(h, biomes.getOrThrow(net.minecraft.world.level.biome.Biomes.FOREST).is(BiomeTags.IS_FOREST), "原版森林标签");
        check(h, !biomes.get(ResourceKey.create(Registries.BIOME, LogosDimension.id("cloudsea_sunlit_glade"))).orElseThrow().is(tag), "远行大陆的群系不生成大号门");
        h.succeed();
    }
}
