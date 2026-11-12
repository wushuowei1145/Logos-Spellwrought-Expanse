package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.ConcertHarpBlock;
import dev.logos.dimension.music.GrandPianoBlock;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.InstrumentSeat;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.MusicStandBlock;
import dev.logos.dimension.music.RollCopyRecipe;
import dev.logos.dimension.music.RollInfo;
import dev.logos.dimension.music.RollStore;
import dev.logos.dimension.music.SeatedInstrumentBlock;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 魔女之森乐器：钢琴 2×2、竖琴两格高，拆任意一块整体拆掉、只掉一件乐器和槽里的纸带；
 * 纸带复制配方；纸带内容存档读写与临时纸带；红石上升沿开始播放、「信号停止时停止」。
 * 大提琴、低音提琴：空手右键坐（站）到琴后的座位上，座位不重复、随琴消失；乐谱架认得中提琴、双簧管、巴松管。
 */
public final class InstrumentGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        Map<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>();
        tests.put("instrument_piano_breaks_whole", InstrumentGameTests::pianoBreaksWhole);
        tests.put("instrument_harp_breaks_whole", InstrumentGameTests::harpBreaksWhole);
        tests.put("instrument_roll_copy", InstrumentGameTests::rollCopy);
        tests.put("instrument_roll_store", InstrumentGameTests::rollStore);
        tests.put("instrument_redstone_starts", InstrumentGameTests::redstoneStarts);
        tests.put("instrument_new_shapes_break_whole", InstrumentGameTests::newShapesBreakWhole);
        tests.put("instrument_music_stand", InstrumentGameTests::musicStand);
        tests.put("instrument_seated_strings", InstrumentGameTests::seatedStrings);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("instrument_default"), new TestEnvironmentDefinition.AllOf());
            for (var name : tests.keySet()) {
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 100, 0, true, Rotation.NONE, false, 1, 1, false, 6)));
            }
        });
    }

    private static BlockState place(ServerLevel level, BlockPos main, BlockState state) {
        level.setBlockAndUpdate(main, state);
        state.getBlock().setPlacedBy(level, main, state, null, ItemStack.EMPTY);
        return state;
    }

    private static int dropped(ServerLevel level, BlockPos around, Item item) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(4))) {
            if (e.getItem().is(item)) n += e.getItem().getCount();
        }
        return n;
    }

    private static ItemStack punched(byte[] encoded, MusicRoll roll) throws Exception {
        UUID id = RollStore.save(encoded);
        ItemStack stack = new ItemStack(Instruments.MUSIC_ROLL_ITEM.get());
        stack.set(Instruments.MUSIC_ROLL.get(), RollInfo.of(id, roll, MusicRoll.hash(encoded)));
        return stack;
    }

    private static MusicRoll scale() {
        List<MusicRoll.Event> ev = new ArrayList<>();
        for (int i = 0; i < 8; i++) ev.add(MusicRoll.Event.note(i * 250, 0, 60 + i, 90, 240));
        return new MusicRoll("音阶", "测试", 12000, 4, 4, List.of(new MusicRoll.Part("钢琴", 0)), ev);
    }

    private static void pianoBreaksWhole(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos main = h.absolutePos(new BlockPos(2, 1, 2));
        BlockState state = place(level, main, Instruments.GRAND_PIANO.get().defaultBlockState()
            .setValue(InstrumentBlock.FACING, Direction.EAST));
        GrandPianoBlock block = Instruments.GRAND_PIANO.get();
        List<BlockPos> cells = new ArrayList<>();
        block.cells(state, main).forEach(cells::add);
        h.assertTrue(cells.size() == 4, "piano covers four cells");
        for (int part = 0; part < 4; part++) {
            BlockState s = level.getBlockState(cells.get(part));
            h.assertTrue(s.is(block) && s.getValue(GrandPianoBlock.PART) == part, "part " + part + " at " + cells.get(part));
            h.assertTrue(block.mainPos(s, cells.get(part)).equals(main), "part " + part + " finds the main block");
            h.assertTrue((level.getBlockEntity(cells.get(part)) != null) == (part == 0), "only the main block has an entity");
        }
        // 槽里放一卷空白纸带，拆琴尾那一块：整架钢琴消失，掉一架钢琴和那卷纸带
        if (level.getBlockEntity(main) instanceof InstrumentBlockEntity be) be.setRoll(new ItemStack(Instruments.BLANK_ROLL.get()));
        level.destroyBlock(cells.get(3), true);
        for (BlockPos c : cells) h.assertTrue(level.getBlockState(c).isAir(), "cell cleared " + c);
        h.assertTrue(dropped(level, main, Instruments.GRAND_PIANO_ITEM.get().asItem()) == 1, "drops one piano");
        h.assertTrue(dropped(level, main, Instruments.BLANK_ROLL.get()) == 1, "drops the roll in the slot");
        h.succeed();
    }

    private static void harpBreaksWhole(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos main = h.absolutePos(new BlockPos(2, 1, 2));
        place(level, main, Instruments.CONCERT_HARP.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        BlockState upper = level.getBlockState(main.above());
        h.assertTrue(upper.is(Instruments.CONCERT_HARP.get())
            && upper.getValue(ConcertHarpBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER, "upper half");
        level.destroyBlock(main.above(), true);
        h.assertTrue(level.getBlockState(main).isAir(), "lower half goes with the upper");
        h.assertTrue(dropped(level, main, Instruments.CONCERT_HARP_ITEM.get().asItem()) == 1, "drops one harp");
        h.succeed();
    }

    private static void rollCopy(GameTestHelper h) {
        ItemStack source;
        try {
            MusicRoll roll = scale();
            source = punched(roll.encode(), roll);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ItemStack blank = new ItemStack(Instruments.BLANK_ROLL.get());
        CraftingInput input = CraftingInput.of(3, 1, List.of(source, blank, blank.copy()));
        RollCopyRecipe recipe = RollCopyRecipe.INSTANCE;
        h.assertTrue(recipe.matches(input, h.getLevel()), "roll + blanks matches");
        ItemStack out = recipe.assemble(input);
        h.assertTrue(out.getCount() == 2, "one copy per blank roll, got " + out.getCount());
        h.assertTrue(source.get(Instruments.MUSIC_ROLL.get()).equals(out.get(Instruments.MUSIC_ROLL.get())), "copies point to the same contents");
        var rest = recipe.getRemainingItems(input);
        h.assertTrue(rest.get(0).is(Instruments.MUSIC_ROLL_ITEM.get()), "the original roll stays in the grid");
        h.assertTrue(!recipe.matches(CraftingInput.of(2, 1, List.of(blank, blank.copy())), h.getLevel()), "blanks alone do not match");
        h.assertTrue(!recipe.matches(CraftingInput.of(2, 1, List.of(source, source.copy())), h.getLevel()), "two sources do not match");
        h.succeed();
    }

    private static void rollStore(GameTestHelper h) {
        try {
            MusicRoll roll = scale();
            byte[] encoded = roll.encode();
            UUID id = RollStore.save(encoded);
            h.assertTrue(java.util.Arrays.equals(encoded, RollStore.load(id)), "saved roll reads back");
            UUID temp = RollStore.saveTemporary(encoded);
            h.assertTrue(RollStore.isTemporary(temp) && RollStore.load(temp) != null, "temporary roll is there");
            RollStore.dropTemporary(temp);
            h.assertTrue(RollStore.load(temp) == null, "temporary roll is gone after dropping");
            h.assertTrue(RollStore.load(UUID.randomUUID()) == null, "unknown roll is null");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        h.succeed();
    }

    private static void redstoneStarts(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos main = h.absolutePos(new BlockPos(2, 1, 2));
        place(level, main, Instruments.CONCERT_HARP.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH));
        if (!(level.getBlockEntity(main) instanceof InstrumentBlockEntity be)) throw new AssertionError("no instrument entity");
        try {
            MusicRoll roll = scale();
            be.setRoll(punched(roll.encode(), roll));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        BlockPos power = main.west();
        level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        h.assertTrue(be.isPlaying(), "redstone starts the roll");
        level.setBlockAndUpdate(power, Blocks.AIR.defaultBlockState());
        h.assertTrue(be.isPlaying(), "losing the signal does not stop it by default");
        be.setStopWithSignal(true);
        level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(power, Blocks.AIR.defaultBlockState());
        h.assertTrue(!be.isPlaying(), "stop-with-signal stops when the signal ends");
        // 上半格旁边的信号也算
        level.setBlockAndUpdate(main.above().east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        h.assertTrue(be.isPlaying(), "a signal next to the upper half starts it too");
        h.succeed();
    }

    /** 第二批：钟琴、钢片琴一格；管钟两格高；大键琴一宽两深，拆琴尾整架拆掉、只掉一架。 */
    private static void newShapesBreakWhole(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos a = h.absolutePos(new BlockPos(1, 1, 1));
        place(level, a, Instruments.GLOCKENSPIEL.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH));
        h.assertTrue(level.getBlockEntity(a) instanceof InstrumentBlockEntity be && be.kind() == dev.logos.dimension.music.InstrumentKind.GLOCKENSPIEL,
            "glockenspiel has its entity");
        BlockPos b = h.absolutePos(new BlockPos(3, 1, 1));
        place(level, b, Instruments.TUBULAR_BELLS.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH));
        h.assertTrue(level.getBlockState(b.above()).is(Instruments.TUBULAR_BELLS.get()), "tubular bells are two tall");
        level.destroyBlock(b.above(), true);
        h.assertTrue(level.getBlockState(b).isAir(), "bells go as a whole");
        BlockPos c = h.absolutePos(new BlockPos(5, 1, 1));
        BlockState harpsichord = place(level, c, Instruments.HARPSICHORD.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        BlockPos tail = c.south();
        h.assertTrue(level.getBlockState(tail).is(Instruments.HARPSICHORD.get())
            && level.getBlockState(tail).getValue(dev.logos.dimension.music.LongInstrumentBlock.TAIL), "harpsichord tail behind the keyboard");
        h.assertTrue(Instruments.HARPSICHORD.get().mainPos(level.getBlockState(tail), tail).equals(c), "tail finds the main block");
        h.assertTrue(level.getBlockEntity(tail) == null, "only the main block has an entity");
        level.destroyBlock(tail, true);
        h.assertTrue(level.getBlockState(c).isAir(), "harpsichord goes as a whole");
        h.assertTrue(dropped(level, b, Instruments.TUBULAR_BELLS_ITEM.get().asItem()) == 1, "one set of bells drops");
        h.assertTrue(dropped(level, c, Instruments.HARPSICHORD_ITEM.get().asItem()) == 1, "one harpsichord drops");
        h.succeed();
    }

    /**
     * 乐谱架：空着时不能演奏；放上小提琴后方块状态显示小提琴、成为小提琴；装纸带、红石启动；
     * 拆掉时乐器、纸带、架子各掉一件。
     */
    private static void musicStand(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos pos = h.absolutePos(new BlockPos(2, 1, 2));
        place(level, pos, Instruments.MUSIC_STAND.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH));
        if (!(level.getBlockEntity(pos) instanceof InstrumentBlockEntity be)) throw new AssertionError("no stand entity");
        h.assertTrue(!be.playable() && !be.play(), "an empty stand cannot play");
        h.assertTrue(Instruments.heldKind(new ItemStack(Instruments.VIOLIN.get())) == dev.logos.dimension.music.InstrumentKind.VIOLIN, "violin item kind");
        h.assertTrue(Instruments.heldKind(new ItemStack(Instruments.BLANK_ROLL.get())) == null, "a roll is not an instrument");
        for (Item item : List.of(Instruments.VIOLA.get(), Instruments.OBOE.get(), Instruments.BASSOON.get())) {
            InstrumentKind kind = Instruments.heldKind(new ItemStack(item));
            h.assertTrue(kind != null && MusicStandBlock.Holds.of(kind).kind() == kind, item + " has its own look on the stand");
        }
        // 与拿着小提琴右键架子做的事相同
        be.setInstrument(new ItemStack(Instruments.VIOLIN.get()));
        level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(dev.logos.dimension.music.MusicStandBlock.HOLDS,
            dev.logos.dimension.music.MusicStandBlock.Holds.VIOLIN));
        h.assertTrue(be.playable() && be.kind() == dev.logos.dimension.music.InstrumentKind.VIOLIN, "the stand is now a violin");
        try {
            MusicRoll roll = scale();
            be.setRoll(punched(roll.encode(), roll));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        level.setBlockAndUpdate(pos.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        h.assertTrue(be.isPlaying(), "redstone starts the stand like any instrument");
        level.setBlockAndUpdate(pos.west(), Blocks.AIR.defaultBlockState());
        level.destroyBlock(pos, true);
        h.assertTrue(dropped(level, pos, Instruments.VIOLIN.get()) == 1, "the violin drops");
        h.assertTrue(dropped(level, pos, Instruments.MUSIC_ROLL_ITEM.get()) == 1, "the roll drops");
        h.assertTrue(dropped(level, pos, Instruments.MUSIC_STAND_ITEM.get().asItem()) == 1, "the stand drops");
        h.succeed();
    }

    /**
     * 大提琴两格高：空手右键（上半格也行）坐到琴后的座位上，双脚正落在座位处，身体朝着琴；有人坐着时别人再右键不会多出座位。
     * 低音提琴的人站在地上；定音鼓一格。拆掉大提琴：整把拆掉、只掉一件，座位随后消失，人站起来。
     */
    private static void seatedStrings(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos cello = h.absolutePos(new BlockPos(2, 1, 2));
        place(level, cello, Instruments.CELLO.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH));
        h.assertTrue(level.getBlockState(cello.above()).is(Instruments.CELLO.get()), "the cello is two tall");
        Player first = h.makeMockPlayer(GameType.SURVIVAL);
        first.snapTo(cello.getX() + 0.5, cello.getY(), cello.getZ() + 2.5);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(cello), Direction.SOUTH, cello, false);
        level.getBlockState(cello.above()).useWithoutItem(level, first, hit.withPosition(cello.above()));
        if (!(first.getVehicle() instanceof InstrumentSeat seat)) throw new AssertionError("right-clicking the cello seats the player");
        h.assertTrue(seat.instrument().equals(cello) && seat.kind() == InstrumentKind.CELLO, "the seat belongs to the cello");
        Vec3 at = SeatedInstrumentBlock.seatPosition(cello, Direction.SOUTH, InstrumentKind.CELLO);
        seat.positionRider(first);
        h.assertTrue(first.position().distanceTo(at) < 1e-4, "the player's feet are on the seat: " + first.position() + " vs " + at);
        h.assertTrue(Math.abs(Mth.wrapDegrees(seat.getYRot() - Direction.SOUTH.toYRot())) < 1e-3, "the seat faces the way the cello faces");
        h.assertTrue(at.z < cello.getZ() + 0.5, "the player sits behind a south-facing cello");
        Player second = h.makeMockPlayer(GameType.SURVIVAL);
        level.getBlockState(cello).useWithoutItem(level, second, hit);
        h.assertTrue(second.getVehicle() == null && level.getEntitiesOfClass(InstrumentSeat.class, new AABB(cello).inflate(3)).size() == 1,
            "a taken seat is not doubled");
        // 起身：站在座位后面的地面上，不陷进地里（座位要等原版问完「人放哪」才消失）；再坐回去
        first.stopRiding();
        h.assertTrue(Math.abs(first.getY() - cello.getY()) < 1e-6 && first.getZ() < at.z - 0.5,
            "standing up puts the player on the floor behind the seat: " + first.position());
        level.getBlockState(cello.above()).useWithoutItem(level, first, hit.withPosition(cello.above()));
        h.assertTrue(first.getVehicle() instanceof InstrumentSeat, "the player can sit down again right away");
        BlockPos bass = h.absolutePos(new BlockPos(5, 1, 2));
        place(level, bass, Instruments.DOUBLE_BASS.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.EAST));
        h.assertTrue(level.getBlockState(bass.above()).is(Instruments.DOUBLE_BASS.get()), "the double bass is two tall");
        h.assertTrue(SeatedInstrumentBlock.feet(InstrumentKind.DOUBLE_BASS).y == 0, "the bass player stands on the floor");
        BlockPos timpani = h.absolutePos(new BlockPos(2, 1, 5));
        place(level, timpani, Instruments.TIMPANI.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH));
        h.assertTrue(level.getBlockEntity(timpani) instanceof InstrumentBlockEntity be && be.kind() == InstrumentKind.TIMPANI, "the timpani has its entity");
        level.destroyBlock(cello.above(), true);
        h.assertTrue(level.getBlockState(cello).isAir(), "the cello goes as a whole");
        h.assertTrue(dropped(level, cello, Instruments.CELLO_ITEM.get().asItem()) == 1, "one cello drops");
        // 座位在自己下一次 tick 时发现琴没了（测试区的区块刚强制加载，实体开始 tick 可能要晚几刻）
        h.succeedWhen(() -> {
            h.assertTrue(level.getEntitiesOfClass(InstrumentSeat.class, new AABB(cello).inflate(3)).isEmpty(), "the seat goes with the cello");
            h.assertTrue(first.getVehicle() == null, "the player stands up");
        });
    }

    private InstrumentGameTests() {}
}
