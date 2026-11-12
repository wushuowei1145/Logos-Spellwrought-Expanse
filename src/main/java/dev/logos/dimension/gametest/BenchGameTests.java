package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.BenchSeat;
import dev.logos.dimension.music.Benches;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.MusicStandBlock;
import dev.logos.dimension.music.RollInfo;
import dev.logos.dimension.music.RollStore;
import dev.logos.dimension.music.StarbloomBenchBlock;
import dev.logos.dimension.music.orchestra.Orchestra;
import dev.logos.dimension.music.orchestra.PodiumBlock;
import dev.logos.dimension.music.orchestra.PodiumBlockEntity;
import dev.logos.dimension.npc.ForestElf;
import dev.logos.dimension.npc.NpcEntities;
import java.util.ArrayList;
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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 星辰花琴凳（docs/ORCHESTRA.md 第 14 节）：在键盘的琴键一面、竖琴的背后才和乐器关联，方向不对、位置不对都不关联；
 * 关联的琴凳就是那件乐器（指挥棒点它加入的是乐器，召集时跳过它）；拴绳牵着精灵右键琴凳它就坐下、拴绳还回来；
 * 拿着小提琴右键坐着的精灵交给它（原来的弓替它收着），空手右键拿回来、弓还给它；潜行空手右键让它站起来；
 * 坐着生物的座位存盘、坐着玩家的不存；没关联的琴凳空着也留在乐团里休息，坐上拿着小提琴的精灵就分到小提琴声部。
 */
public final class BenchGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    private BenchGameTests() {}

    public static void register(IEventBus bus) {
        Map<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>();
        tests.put("bench_links_to_instruments", BenchGameTests::links);
        tests.put("bench_seats_led_mobs", BenchGameTests::seats);
        tests.put("bench_joins_the_orchestra", BenchGameTests::joins);
        tests.put("bench_orchestra_moves_with_the_hall", BenchGameTests::moves);
        tests.put("bench_seat_moves_up_to_the_keys", BenchGameTests::movesUp);
        tests.put("bench_duet_sits_in_the_middle", BenchGameTests::duet);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("bench_default"), new TestEnvironmentDefinition.AllOf());
            for (var name : tests.keySet()) {
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 100, 0, true, Rotation.NONE, false, 1, 1, false, 6)));
            }
        });
    }

    private static BlockState place(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlockAndUpdate(pos, state);
        state.getBlock().setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
        return state;
    }

    private static BlockPos bench(GameTestHelper h, BlockPos rel, Direction facing) {
        BlockPos pos = h.absolutePos(rel);
        place(h.getLevel(), pos, Benches.BENCH.get().defaultBlockState().setValue(InstrumentBlock.FACING, facing));
        return pos;
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    private static ForestElf elf(GameTestHelper h, BlockPos rel) {
        ForestElf elf = NpcEntities.FOREST_ELF.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
        if (elf == null) throw new AssertionError("no forest elf");
        BlockPos at = h.absolutePos(rel);
        elf.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        elf.setNoAi(true);
        h.getLevel().addFreshEntity(elf);
        return elf;
    }

    private static void links(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // 钢琴琴键朝北：前排是主块和它东边那格；琴凳在前排北面、面朝南就关联
        BlockPos piano = h.absolutePos(new BlockPos(2, 1, 3));
        place(level, piano, Instruments.GRAND_PIANO.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        BlockPos front = bench(h, new BlockPos(2, 1, 2), Direction.SOUTH);
        BlockPos frontSide = bench(h, new BlockPos(3, 1, 2), Direction.SOUTH);
        BlockPos sideways = bench(h, new BlockPos(1, 1, 3), Direction.EAST);
        BlockPos behind = bench(h, new BlockPos(2, 1, 5), Direction.NORTH);
        h.assertTrue(piano.equals(StarbloomBenchBlock.linked(level, front)), "the bench at the keys links to the piano");
        h.assertTrue(piano.equals(StarbloomBenchBlock.linked(level, frontSide)), "either front cell of the piano works");
        h.assertTrue(StarbloomBenchBlock.linked(level, sideways) == null, "a bench at the piano's side does not link");
        h.assertTrue(StarbloomBenchBlock.linked(level, behind) == null, "a bench behind the piano does not link");
        h.assertTrue(StarbloomBenchBlock.linkedKind(level, front) == InstrumentKind.GRAND_PIANO, "linked kind is the piano");
        // 竖琴朝北：弹的人坐在它南面、和它朝同一个方向
        BlockPos harp = h.absolutePos(new BlockPos(6, 1, 3));
        place(level, harp, Instruments.CONCERT_HARP.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        BlockPos harpist = bench(h, new BlockPos(6, 1, 4), Direction.NORTH);
        BlockPos facingHarp = bench(h, new BlockPos(6, 1, 2), Direction.SOUTH);
        h.assertTrue(harp.equals(StarbloomBenchBlock.linked(level, harpist)), "the harpist's bench behind the harp links");
        h.assertTrue(StarbloomBenchBlock.linked(level, facingHarp) == null, "a bench facing the harp's front does not link");
        // 钟琴站着敲：不关联
        BlockPos glock = h.absolutePos(new BlockPos(9, 1, 3));
        place(level, glock, Instruments.GLOCKENSPIEL.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        h.assertTrue(StarbloomBenchBlock.linked(level, bench(h, new BlockPos(9, 1, 2), Direction.SOUTH)) == null, "mallet instruments do not link");
        // 拆掉钢琴就不再关联
        level.destroyBlock(piano, false);
        h.assertTrue(StarbloomBenchBlock.linked(level, front) == null, "no piano, no link");
        h.succeed();
    }

    /**
     * 坐着的精灵：前面放上钢琴就挪到琴键前（{@link StarbloomBenchBlock#LINKED_FORWARD}），拆掉就挪回凳子中间。
     * 座位是在方块更新里当场挪的；坐的人由座位当场摆好再查，不等实体刻（测试格子所在的区块不一定已经在跑实体）。
     */
    private static void movesUp(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos seat = bench(h, new BlockPos(2, 1, 2), Direction.SOUTH);
        ForestElf elf = elf(h, new BlockPos(2, 1, 5));
        h.assertTrue(StarbloomBenchBlock.sit(level, seat, elf) && elf.getVehicle() instanceof BenchSeat, "the elf sits down");
        BenchSeat ride = (BenchSeat) elf.getVehicle();
        double middle = seat.getZ() + StarbloomBenchBlock.FEET.z / 16;
        double keys = middle + StarbloomBenchBlock.LINKED_FORWARD / 16;
        ride.positionRider(elf);
        h.assertTrue(Math.abs(elf.getZ() - middle) < 1.0e-3, "an unlinked bench seats the elf in its middle, z=" + elf.getZ() + ", expected " + middle);
        BlockPos piano = h.absolutePos(new BlockPos(2, 1, 3));
        place(level, piano, Instruments.GRAND_PIANO.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        ride.positionRider(elf);
        h.assertTrue(Math.abs(ride.getZ() - keys) < 1.0e-3 && Math.abs(elf.getZ() - keys) < 1.0e-3,
            "with a piano in front the elf moves up to the keys, z=" + elf.getZ() + ", expected " + keys);
        level.destroyBlock(piano, false);
        ride.positionRider(elf);
        h.assertTrue(Math.abs(elf.getZ() - middle) < 1.0e-3, "without the piano the elf moves back, z=" + elf.getZ() + ", expected " + middle);
        h.succeed();
    }

    /** 双人琴凳：两格、另一格在坐的人右手边；两格宽的钢琴前任一格都关联；从哪一格坐都坐在正中、往琴键挪；拆一格整张拆掉、只掉一张。 */
    private static void duet(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos piano = h.absolutePos(new BlockPos(2, 1, 3));
        place(level, piano, Instruments.GRAND_PIANO.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        BlockPos main = h.absolutePos(new BlockPos(3, 1, 2));
        place(level, main, Benches.DUET.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH));
        BlockPos side = main.west();
        BlockState sideState = level.getBlockState(side);
        h.assertTrue(sideState.is(Benches.DUET.get()) && sideState.getValue(dev.logos.dimension.music.StarbloomDuetBenchBlock.SIDE),
            "two wide: the second cell is on the sitter's right");
        h.assertTrue(piano.equals(StarbloomBenchBlock.linked(level, main)) && piano.equals(StarbloomBenchBlock.linked(level, side)),
            "either cell links to the two-wide piano");
        ForestElf elf = elf(h, new BlockPos(2, 1, 6));
        h.assertTrue(StarbloomBenchBlock.sit(level, side, elf) && elf.getVehicle() instanceof BenchSeat, "sitting down from either cell");
        BenchSeat ride = (BenchSeat) elf.getVehicle();
        ride.positionRider(elf);
        h.assertTrue(Math.abs(elf.getX() - main.getX()) < 1.0e-3, "the elf sits where the two cells meet: x=" + elf.getX() + " vs " + main.getX());
        h.assertTrue(Math.abs(elf.getZ() - (main.getZ() + 0.5 + StarbloomBenchBlock.LINKED_FORWARD / 16)) < 1.0e-3,
            "and moves up to the keys: z=" + elf.getZ());
        h.assertTrue(ride.bench().equals(main) && StarbloomBenchBlock.occupant(level, side) == elf, "the seat belongs to the main cell");
        elf.stopRiding();
        level.destroyBlock(side, true);
        h.assertTrue(level.getBlockState(main).isAir() && level.getBlockState(side).isAir(), "breaking one cell takes the whole bench");
        int dropped = 0;
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new AABB(main).inflate(3))) {
            if (e.getItem().is(Benches.DUET_ITEM.get().asItem())) dropped += e.getItem().getCount();
        }
        h.assertTrue(dropped == 1, "one duet bench drops, got " + dropped);
        h.succeed();
    }

    private static void seats(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos seat = bench(h, new BlockPos(2, 1, 2), Direction.SOUTH);
        ForestElf elf = elf(h, new BlockPos(2, 1, 5));
        elf.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        player.snapTo(seat.getX() + 0.5, seat.getY(), seat.getZ() + 2.5, 0, 0);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LEAD));
        elf.setLeashedTo(player, true);
        h.assertTrue(elf.isLeashed(), "the elf is on a lead");
        // 牵着它右键琴凳
        level.getBlockState(seat).useItemOn(player.getMainHandItem(), level, player, InteractionHand.MAIN_HAND, hit(seat));
        h.assertTrue(elf.getVehicle() instanceof BenchSeat s && s.bench().equals(seat), "the led elf sits on the bench");
        h.assertTrue(!elf.isLeashed(), "and is let off the lead");
        h.assertTrue(player.getInventory().countItem(Items.LEAD) == 2, "the lead comes back");
        h.assertTrue(elf.isPersistenceRequired(), "a seated mob does not despawn");
        h.assertTrue(StarbloomBenchBlock.occupant(level, seat) == elf, "the bench knows who sits on it");
        BenchSeat benchSeat = (BenchSeat) elf.getVehicle();
        h.assertTrue(benchSeat.shouldBeSaved(), "a seat with a mob is saved with it");
        // 拿着小提琴右键它：交给它，原来的弓替它收着
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Instruments.VIOLIN.get()));
        player.interactOn(elf, InteractionHand.MAIN_HAND, elf.position().add(0, 1, 0));
        h.assertTrue(elf.getMainHandItem().is(Instruments.VIOLIN.get()), "the elf holds the violin");
        h.assertTrue(player.getMainHandItem().isEmpty(), "the player's violin went to the elf");
        h.assertTrue(elf.getData(Benches.STOWED).is(Items.BOW), "the bow is kept for the elf");
        h.assertTrue(level.getBlockState(seat).getValue(StarbloomBenchBlock.PLAYS) == MusicStandBlock.Holds.VIOLIN, "the bench now plays the violin");
        // 空手右键：拿回小提琴，弓还给它
        player.interactOn(elf, InteractionHand.MAIN_HAND, elf.position().add(0, 1, 0));
        h.assertTrue(elf.getMainHandItem().is(Items.BOW), "the bow is back in the elf's hand");
        h.assertTrue(player.getInventory().countItem(Instruments.VIOLIN.get()) == 1, "the violin is back with the player");
        h.assertTrue(level.getBlockState(seat).getValue(StarbloomBenchBlock.PLAYS) == MusicStandBlock.Holds.NONE, "the bench rests again");
        // 潜行空手右键琴凳：站起来，座位消失
        player.setShiftKeyDown(true);
        level.getBlockState(seat).useWithoutItem(level, player, hit(seat));
        h.assertTrue(elf.getVehicle() == null, "the elf stands up");
        h.assertTrue(StarbloomBenchBlock.isFree(level, seat), "the bench is free again (the empty seat goes on its next tick)");
        // 玩家自己坐：座位不存盘
        player.setShiftKeyDown(false);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        level.getBlockState(seat).useWithoutItem(level, player, hit(seat));
        h.assertTrue(player.getVehicle() instanceof BenchSeat s && !s.shouldBeSaved(), "a player's seat is not saved");
        // 起身：站在凳子后面的地面上，不陷进地里
        player.stopRiding();
        h.assertTrue(Math.abs(player.getY() - seat.getY()) < 1e-6 && player.getZ() < seat.getZ() + 0.5 - 0.5,
            "standing up puts the player on the floor behind the bench: " + player.position());
        h.succeed();
    }

    /** 整座乐团被结构方块、蓝图搬到别处：读回来时成员和乐器记着的指挥台跟着平移（存盘时记着 origin）。 */
    private static void moves(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos main = h.absolutePos(new BlockPos(3, 1, 1));
        place(level, main, Orchestra.PODIUM.get().defaultBlockState().setValue(PodiumBlock.FACING, Direction.SOUTH));
        if (!(level.getBlockEntity(main) instanceof PodiumBlockEntity podium)) throw new AssertionError("no podium");
        BlockPos drum = h.absolutePos(new BlockPos(3, 1, 5));
        place(level, drum, Instruments.TIMPANI.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        h.assertTrue("joined".equals(podium.toggle(drum)), "the timpani joins");
        BlockPos away = new BlockPos(40, 3, -25);
        var podiumTag = podium.saveWithFullMetadata(level.registryAccess());
        if (!(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(main.offset(away), podium.getBlockState(), podiumTag,
                level.registryAccess()) instanceof PodiumBlockEntity moved)) throw new AssertionError("podium did not load");
        h.assertTrue(moved.members().equals(List.of(drum.offset(away))), "members move with the podium: " + moved.members());
        var drumEntity = InstrumentBlock.entityAt(level, drum);
        var drumTag = drumEntity.saveWithFullMetadata(level.registryAccess());
        if (!(net.minecraft.world.level.block.entity.BlockEntity.loadStatic(drum.offset(away), drumEntity.getBlockState(), drumTag,
                level.registryAccess()) instanceof dev.logos.dimension.music.InstrumentBlockEntity movedDrum)) throw new AssertionError("timpani did not load");
        h.assertTrue(main.offset(away).equals(movedDrum.orchestra()), "the instrument remembers the moved podium: " + movedDrum.orchestra());
        // 原地读回不动
        var same = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(main, podium.getBlockState(), podiumTag, level.registryAccess());
        h.assertTrue(same instanceof PodiumBlockEntity p && p.members().equals(List.of(drum)), "loading in place keeps the members");
        // 乐器记着的指挥台不在了（例如蓝图转着放下，位置只平移不旋转）：召集附近照样收进来
        h.assertTrue("left".equals(podium.toggle(drum)), "the timpani leaves");
        var stale = InstrumentBlock.entityAt(level, drum);
        stale.setOrchestra(h.absolutePos(new BlockPos(7, 1, 1)));
        h.assertTrue(podium.gather() == 1 && main.equals(stale.orchestra()), "gathering takes an instrument whose podium is gone: " + stale.orchestra());
        h.succeed();
    }

    private static MusicRoll duet() {
        List<MusicRoll.Event> ev = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ev.add(MusicRoll.Event.note(i * 250, 0, 76 + i % 3, 90, 240));
            ev.add(MusicRoll.Event.note(i * 250, 1, 48 + i % 3, 80, 240));
        }
        return new MusicRoll("二重奏", "测试", 12000, 4, 4,
            List.of(new MusicRoll.Part("小提琴", 40), new MusicRoll.Part("钢琴", 0)), ev);
    }

    private static ItemStack punched(MusicRoll roll) {
        try {
            byte[] encoded = roll.encode();
            UUID id = RollStore.save(encoded);
            ItemStack stack = new ItemStack(Instruments.MUSIC_ROLL_ITEM.get());
            stack.set(Instruments.MUSIC_ROLL.get(), RollInfo.of(id, roll, MusicRoll.hash(encoded)));
            return stack;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void joins(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos main = h.absolutePos(new BlockPos(3, 1, 1));
        place(level, main, Orchestra.PODIUM.get().defaultBlockState().setValue(PodiumBlock.FACING, Direction.SOUTH));
        if (!(level.getBlockEntity(main) instanceof PodiumBlockEntity podium)) throw new AssertionError("no podium");
        podium.setScore(punched(duet()));
        // 钢琴前的琴凳：点它加入的是钢琴
        BlockPos piano = h.absolutePos(new BlockPos(1, 1, 6));
        place(level, piano, Instruments.GRAND_PIANO.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        BlockPos pianist = bench(h, new BlockPos(1, 1, 5), Direction.SOUTH);
        h.assertTrue("joined".equals(podium.toggle(pianist)), "clicking the linked bench joins");
        h.assertTrue(podium.members().contains(piano) && !podium.members().contains(pianist), "the piano is the member, not its bench");
        // 没关联的琴凳：自己就是成员，空着时休息，开始演奏也不会被去掉
        BlockPos seat = bench(h, new BlockPos(5, 1, 5), Direction.NORTH);
        h.assertTrue("joined".equals(podium.toggle(seat)), "a free-standing bench joins");
        h.assertTrue(podium.mask(seat) == 0, "an empty bench rests");
        h.assertTrue(podium.start(0), "the orchestra starts");
        h.assertTrue(podium.members().contains(seat), "the empty bench stays in the orchestra");
        podium.stop();
        // 坐上拿着小提琴的精灵：分到小提琴声部
        ForestElf elf = elf(h, new BlockPos(5, 1, 7));
        h.assertTrue(StarbloomBenchBlock.sit(level, seat, elf), "the elf sits down");
        Benches.give(elf, new ItemStack(Instruments.VIOLIN.get()), h.makeMockPlayer(GameType.CREATIVE));
        StarbloomBenchBlock.refresh(level, seat);
        h.assertTrue(podium.mask(seat) == 1, "the elf's bench plays the violin part, got " + podium.mask(seat));
        h.assertTrue(podium.mask(piano) == 1 << 1, "the piano plays the piano part, got " + podium.mask(piano));
        // 召集附近时关联的琴凳不算
        podium.toggle(piano);
        podium.toggle(seat);
        h.assertTrue(podium.members().isEmpty(), "everyone left");
        podium.gather();
        h.assertTrue(podium.members().contains(piano) && podium.members().contains(seat) && !podium.members().contains(pianist),
            "gathering takes the piano and the free bench, not the piano's bench: " + podium.members());
        h.succeed();
    }
}
