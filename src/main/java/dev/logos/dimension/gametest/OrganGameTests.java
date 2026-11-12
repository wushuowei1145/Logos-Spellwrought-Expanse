package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.organ.OrganConsoleBlock;
import dev.logos.dimension.music.organ.OrganPipeBlock;
import dev.logos.dimension.music.organ.OrganStop;
import dev.logos.dimension.music.organ.OrganStructure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 管风琴：演奏台 2×2 整体拆除；音管叠起来的形状；结构识别（风箱链、音管计数、不相连的不算）；
 * 没有音管时服务端只接受长笛 8′，搭上锡管后主音栓类才能开。
 */
public final class OrganGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        Map<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>();
        tests.put("organ_console_breaks_whole", OrganGameTests::consoleBreaksWhole);
        tests.put("organ_legacy_console", OrganGameTests::legacyConsole);
        tests.put("organ_pipe_shapes", OrganGameTests::pipeShapes);
        tests.put("organ_structure_scan", OrganGameTests::structureScan);
        tests.put("organ_stops_need_pipes", OrganGameTests::stopsNeedPipes);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("organ_default"), new TestEnvironmentDefinition.AllOf());
            for (var name : tests.keySet()) {
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 100, 0, true, Rotation.NONE, false, 1, 1, false, 12)));
            }
        });
    }

    private static BlockState console(ServerLevel level, BlockPos main, Direction facing) {
        BlockState state = Instruments.ORGAN_CONSOLE.get().placed(facing);
        level.setBlockAndUpdate(main, state);
        state.getBlock().setPlacedBy(level, main, state, null, ItemStack.EMPTY);
        return state;
    }

    /** 叠一根音管：逐格放，每格放完按上下邻格重新算形状（游戏里放置时由 getStateForPlacement 算）。 */
    private static void column(ServerLevel level, BlockPos bottom, Block pipe, int height) {
        for (int i = 0; i < height; i++) {
            BlockPos p = bottom.above(i);
            BlockState s = pipe.defaultBlockState();
            if (pipe instanceof OrganPipeBlock) s = s.setValue(OrganPipeBlock.FACING, Direction.SOUTH);
            level.setBlockAndUpdate(p, s);
            if (pipe instanceof OrganPipeBlock b) level.setBlockAndUpdate(p, b.withShape(level.getBlockState(p), level, p));
        }
    }

    private static void consoleBreaksWhole(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos main = h.absolutePos(new BlockPos(3, 1, 3));
        BlockState state = console(level, main, Direction.SOUTH);
        OrganConsoleBlock block = Instruments.ORGAN_CONSOLE.get();
        List<BlockPos> cells = new ArrayList<>();
        block.cells(state, main).forEach(cells::add);
        h.assertTrue(cells.size() == 6, "three wide, two tall: " + cells.size());
        for (BlockPos c : cells) {
            BlockState s = level.getBlockState(c);
            h.assertTrue(s.is(block) && s.getValue(OrganConsoleBlock.WIDE), "cell " + c);
            h.assertTrue(block.mainPos(s, c).equals(main), "cell " + c + " finds the main block");
        }
        // 键盘朝南：主块在下排正中，东西各一列
        h.assertTrue(cells.contains(main.east()) && cells.contains(main.west()) && cells.contains(main.above().east())
            && cells.contains(main.above().west()), "the main block is in the middle of the bottom row");
        h.assertTrue(InstrumentBlock.center(state, main).x == main.getX() + 0.5, "the sound comes from the middle");
        level.destroyBlock(main.above().east(), true);
        for (BlockPos c : cells) h.assertTrue(level.getBlockState(c).isAir(), "cell cleared " + c);
        int dropped = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(main).inflate(4))) {
            if (e.getItem().is(Instruments.ORGAN_CONSOLE_ITEM.get().asItem())) dropped += e.getItem().getCount();
        }
        h.assertTrue(dropped == 1, "drops one console, got " + dropped);
        h.succeed();
    }

    /** 旧存档里 2 宽的演奏台（wide=false）照旧：四格、旁边放东西不会把它拆掉、风箱照样识别。 */
    private static void legacyConsole(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos main = h.absolutePos(new BlockPos(3, 1, 3));
        OrganConsoleBlock block = Instruments.ORGAN_CONSOLE.get();
        BlockState state = block.defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH);
        h.assertTrue(!state.getValue(OrganConsoleBlock.WIDE), "old saves load as two wide (the default)");
        level.setBlockAndUpdate(main, state);
        block.setPlacedBy(level, main, state, null, ItemStack.EMPTY);
        List<BlockPos> cells = new ArrayList<>();
        block.cells(state, main).forEach(cells::add);
        h.assertTrue(cells.size() == 4 && cells.contains(main.west()) && !cells.contains(main.east()), "two wide: " + cells);
        // 逆时针那边（东）放一个方块：旧演奏台那边本来就没有格子，不该被拆
        level.setBlockAndUpdate(main.east(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(main.east(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        for (BlockPos c : cells) h.assertTrue(level.getBlockState(c).is(block), "still standing at " + c);
        level.setBlockAndUpdate(main.north(), Instruments.ORGAN_WIND_CHEST.get().defaultBlockState());
        column(level, main.north().above(), Instruments.ORGAN_TIN_PIPE.get(), 2);
        h.assertTrue(OrganStructure.scan(level, state, main).tin() == 2, "pipes are found from the old console");
        h.succeed();
    }

    private static void pipeShapes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos base = h.absolutePos(new BlockPos(2, 1, 2));
        column(level, base, Instruments.ORGAN_TIN_PIPE.get(), 3);
        h.assertTrue(level.getBlockState(base).getValue(OrganPipeBlock.SHAPE) == OrganPipeBlock.Shape.BOTTOM, "bottom");
        h.assertTrue(level.getBlockState(base.above()).getValue(OrganPipeBlock.SHAPE) == OrganPipeBlock.Shape.MIDDLE, "middle");
        h.assertTrue(level.getBlockState(base.above(2)).getValue(OrganPipeBlock.SHAPE) == OrganPipeBlock.Shape.TOP, "top");
        // 拆掉中间一格：上下各成一根短管
        level.destroyBlock(base.above(), false);
        h.assertTrue(level.getBlockState(base).getValue(OrganPipeBlock.SHAPE) == OrganPipeBlock.Shape.SINGLE, "lower becomes single");
        h.assertTrue(level.getBlockState(base.above(2)).getValue(OrganPipeBlock.SHAPE) == OrganPipeBlock.Shape.SINGLE, "upper becomes single");
        // 木管叠在锡管上不连成一根
        BlockPos other = base.east(2);
        column(level, other, Instruments.ORGAN_TIN_PIPE.get(), 1);
        column(level, other.above(), Instruments.ORGAN_WOOD_PIPE.get(), 1);
        h.assertTrue(level.getBlockState(other).getValue(OrganPipeBlock.SHAPE) == OrganPipeBlock.Shape.SINGLE, "tin under wood stays single");
        h.succeed();
    }

    private static void structureScan(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos main = h.absolutePos(new BlockPos(3, 1, 2));
        // 键盘朝南，风箱一排放在演奏台北面，向东延伸
        BlockState state = console(level, main, Direction.SOUTH);
        BlockPos chest0 = main.north();
        for (int i = 0; i < 4; i++) level.setBlockAndUpdate(chest0.west(i - 1), Instruments.ORGAN_WIND_CHEST.get().defaultBlockState());
        column(level, chest0.west(-1).above(), Instruments.ORGAN_TIN_PIPE.get(), 4);
        column(level, chest0.above(), Instruments.ORGAN_TIN_PIPE.get(), 3);
        column(level, chest0.west(1).above(), Instruments.ORGAN_WOOD_PIPE.get(), 2);
        column(level, chest0.west(2).above(), Instruments.ORGAN_TRUMPET_PIPE.get(), 1);
        // 风箱一级级往上接：北边一格、叠高一格、再往北一格，那里立着两格锡管（同音乐厅从演奏台升到管墙的风道）
        BlockPos riser = chest0.north();
        level.setBlockAndUpdate(riser, Instruments.ORGAN_WIND_CHEST.get().defaultBlockState());
        level.setBlockAndUpdate(riser.above(), Instruments.ORGAN_WIND_CHEST.get().defaultBlockState());
        level.setBlockAndUpdate(riser.north().above(), Instruments.ORGAN_WIND_CHEST.get().defaultBlockState());
        column(level, riser.north().above(2), Instruments.ORGAN_TIN_PIPE.get(), 2);
        // 不相连的风箱和音管不算
        BlockPos lonely = main.south(4);
        level.setBlockAndUpdate(lonely, Instruments.ORGAN_WIND_CHEST.get().defaultBlockState());
        column(level, lonely.above(), Instruments.ORGAN_TIN_PIPE.get(), 5);
        OrganStructure o = OrganStructure.scan(level, state, main);
        h.assertTrue(o.chests() == 7, "seven connected chests including the raised ones, got " + o.chests());
        h.assertTrue(o.tin() == 9 && o.wood() == 2 && o.reed() == 1, "pipes " + o);
        h.assertTrue(o.range() == 16 + 12, "range grows with pipes: " + o.range());
        h.assertTrue(o.availableStops() == OrganStop.ALL, "all families present");
        h.assertTrue(o.center().y > main.getY() + 1.5, "sound comes from the pipes: " + o.center());
        h.succeed();
    }

    private static void stopsNeedPipes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos main = h.absolutePos(new BlockPos(3, 1, 2));
        console(level, main, Direction.NORTH);
        if (!(level.getBlockEntity(main) instanceof InstrumentBlockEntity be)) throw new AssertionError("no console entity");
        be.setStops(OrganStop.ALL);
        h.assertTrue(be.stops() == OrganStop.FLUTE_8.bit(), "without pipes only the 8' flute: " + Integer.toBinaryString(be.stops()));
        BlockPos chest = main.south();
        level.setBlockAndUpdate(chest, Instruments.ORGAN_WIND_CHEST.get().defaultBlockState());
        column(level, chest.above(), Instruments.ORGAN_TIN_PIPE.get(), 2);
        be.rescanOrgan();
        be.setStops(OrganStop.Preset.PLENUM.mask());
        int expected = OrganStop.Preset.PLENUM.mask() & OrganStop.available(false, true, false);
        h.assertTrue(be.stops() == expected, "tin unlocks the principals but not the reeds or the bourdon: " + Integer.toBinaryString(be.stops()));
        h.assertTrue(!OrganStop.TRUMPET_8.in(be.stops()) && OrganStop.PRINCIPAL_8.in(be.stops()), "trumpet needs trumpet pipes");
        h.assertTrue(be.hearingRange() == 18, "range: " + be.hearingRange());
        h.succeed();
    }

    private OrganGameTests() {}
}
