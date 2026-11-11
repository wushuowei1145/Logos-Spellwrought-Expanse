package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.MusicStandBlock;
import dev.logos.dimension.music.RollInfo;
import dev.logos.dimension.music.RollStore;
import dev.logos.dimension.music.orchestra.Orchestra;
import dev.logos.dimension.music.orchestra.PodiumBlock;
import dev.logos.dimension.music.orchestra.PodiumBlockEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 乐团（docs/ORCHESTRA.md）：指挥台两格、拆任一格整座拆掉，只掉一座和谱架上的总谱；指挥棒先绑定指挥台、再点选乐器加入 / 退出；
 * 一件乐器只属于一个乐团；距离、件数的限制；成员、分派、速度、响度的存档读写（播放状态不存）；
 * 乐器被拆、乐谱架上的乐器被拿走时退出；跟着乐团演奏时乐器不放自己的纸带，红石也不行，停下后恢复。
 */
public final class OrchestraGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    private OrchestraGameTests() {}

    public static void register(IEventBus bus) {
        Map<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>();
        tests.put("orchestra_podium_breaks_whole", OrchestraGameTests::podiumBreaksWhole);
        tests.put("orchestra_baton_links", OrchestraGameTests::batonLinks);
        tests.put("orchestra_one_podium_per_instrument", OrchestraGameTests::onePodiumPerInstrument);
        tests.put("orchestra_limits", OrchestraGameTests::limits);
        tests.put("orchestra_saves", OrchestraGameTests::saves);
        tests.put("orchestra_members_leave", OrchestraGameTests::membersLeave);
        tests.put("orchestra_conducted_instruments_wait", OrchestraGameTests::conductedInstrumentsWait);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("orchestra_default"), new TestEnvironmentDefinition.AllOf());
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

    private static int dropped(ServerLevel level, BlockPos around, Item item) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(4))) {
            if (e.getItem().is(item)) n += e.getItem().getCount();
        }
        return n;
    }

    /** 两个小提琴声部的总谱。 */
    private static MusicRoll duet() {
        List<MusicRoll.Event> ev = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ev.add(MusicRoll.Event.note(i * 250, 0, 76 + i % 3, 90, 240));
            ev.add(MusicRoll.Event.note(i * 250, 1, 64 + i % 3, 80, 240));
        }
        return new MusicRoll("二重奏", "测试", 12000, 4, 4,
            List.of(new MusicRoll.Part("第一小提琴", 40), new MusicRoll.Part("第二小提琴", 40)), ev);
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

    private static PodiumBlockEntity podium(GameTestHelper h, BlockPos rel, Direction facing) {
        ServerLevel level = h.getLevel();
        BlockPos main = h.absolutePos(rel);
        place(level, main, Orchestra.PODIUM.get().defaultBlockState().setValue(PodiumBlock.FACING, facing));
        if (!(level.getBlockEntity(main) instanceof PodiumBlockEntity be)) throw new AssertionError("no podium entity at " + main);
        return be;
    }

    private static BlockPos timpani(GameTestHelper h, BlockPos rel) {
        BlockPos pos = h.absolutePos(rel);
        place(h.getLevel(), pos, Instruments.TIMPANI.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        return pos;
    }

    /** 放了一把小提琴的乐谱架（同拿着小提琴右键架子）。 */
    private static BlockPos violinStand(GameTestHelper h, BlockPos rel) {
        ServerLevel level = h.getLevel();
        BlockPos pos = h.absolutePos(rel);
        place(level, pos, Instruments.MUSIC_STAND.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.NORTH));
        if (!(level.getBlockEntity(pos) instanceof InstrumentBlockEntity be)) throw new AssertionError("no stand entity");
        be.setInstrument(new ItemStack(Instruments.VIOLIN.get()));
        level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(MusicStandBlock.HOLDS, MusicStandBlock.Holds.VIOLIN));
        return pos;
    }

    private static InstrumentBlockEntity entity(GameTestHelper h, BlockPos pos) {
        InstrumentBlockEntity be = InstrumentBlock.entityAt(h.getLevel(), pos);
        if (be == null) throw new AssertionError("no instrument at " + pos);
        return be;
    }

    private static void podiumBreaksWhole(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        PodiumBlockEntity be = podium(h, new BlockPos(2, 1, 2), Direction.NORTH);
        BlockPos main = be.getBlockPos(), side = main.east();
        BlockState s = level.getBlockState(side);
        h.assertTrue(s.is(Orchestra.PODIUM.get()) && s.getValue(PodiumBlock.SIDE), "the side cell is on the conductor's right");
        h.assertTrue(level.getBlockEntity(side) == null, "only the main cell has an entity");
        h.assertTrue(PodiumBlock.entityAt(level, side) == be, "the side cell finds the podium");
        h.assertTrue(be.standAt().equals(Vec3.atBottomCenterOf(main).add(0.5, 0.5, 0)), "the conductor stands on the seam, half a block up");
        be.setScore(punched(duet()));
        h.assertTrue(level.getBlockState(main).getValue(PodiumBlock.SCORE), "the stand shows the score");
        level.destroyBlock(side, true);
        h.assertTrue(level.getBlockState(main).isAir() && level.getBlockState(side).isAir(), "both cells go");
        h.assertTrue(dropped(level, main, Orchestra.PODIUM_ITEM.get().asItem()) == 1, "drops one podium");
        h.assertTrue(dropped(level, main, Instruments.MUSIC_ROLL_ITEM.get()) == 1, "drops the score");
        h.succeed();
    }

    private static void batonLinks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        PodiumBlockEntity be = podium(h, new BlockPos(2, 1, 1), Direction.SOUTH);
        BlockPos drum = timpani(h, new BlockPos(2, 1, 5));
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        ItemStack baton = new ItemStack(Orchestra.BATON.get());
        Orchestra.batonOnInstrument(baton, level, drum, player);
        h.assertTrue(be.members().isEmpty(), "an unbound baton adds nothing");
        Orchestra.batonOnPodium(baton, level, be.getBlockPos(), player);
        h.assertTrue(GlobalPos.of(level.dimension(), be.getBlockPos()).equals(baton.get(Orchestra.BATON_PODIUM.get())), "the baton remembers the podium");
        h.assertTrue(!be.isPlaying(), "binding does not start the orchestra");
        Orchestra.batonOnInstrument(baton, level, drum, player);
        h.assertTrue(be.members().equals(List.of(drum)), "the timpani joined");
        h.assertTrue(be.getBlockPos().equals(entity(h, drum).orchestra()), "the timpani knows its orchestra");
        Orchestra.batonOnInstrument(baton, level, drum, player);
        h.assertTrue(be.members().isEmpty() && entity(h, drum).orchestra() == null, "clicking it again leaves");
        h.succeed();
    }

    private static void onePodiumPerInstrument(GameTestHelper h) {
        PodiumBlockEntity a = podium(h, new BlockPos(1, 1, 1), Direction.SOUTH);
        PodiumBlockEntity b = podium(h, new BlockPos(5, 1, 1), Direction.SOUTH);
        BlockPos drum = timpani(h, new BlockPos(3, 1, 5));
        h.assertTrue("joined".equals(a.toggle(drum)), "joins the first orchestra");
        h.assertTrue("joined".equals(b.toggle(drum)), "joins the second orchestra");
        h.assertTrue(a.members().isEmpty() && b.members().equals(List.of(drum)), "it moved to the second orchestra");
        h.assertTrue(b.getBlockPos().equals(entity(h, drum).orchestra()), "and remembers the second podium");
        h.succeed();
    }

    private static void limits(GameTestHelper h) {
        PodiumBlockEntity be = podium(h, new BlockPos(2, 1, 0), Direction.SOUTH);
        h.assertTrue("not_instrument".equals(be.toggle(h.absolutePos(new BlockPos(6, 1, 6)))), "air is not an instrument");
        BlockPos high = timpani(h, new BlockPos(2, 1 + (int) PodiumBlockEntity.MAX_DISTANCE + 2, 0));
        h.assertTrue("too_far".equals(be.toggle(high)), "instruments farther than 32 blocks cannot join");
        int joined = 0;
        for (int x = 0; x < 8; x++) {
            for (int z = 2; z < 6; z++) {
                if ("joined".equals(be.toggle(timpani(h, new BlockPos(x, 1, z))))) joined++;
            }
        }
        h.assertTrue(joined == PodiumBlockEntity.MAX_MEMBERS, "32 instruments join, got " + joined);
        h.assertTrue("full".equals(be.toggle(timpani(h, new BlockPos(3, 1, 7)))), "the 33rd is refused");
        h.succeed();
    }

    private static void saves(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        PodiumBlockEntity be = podium(h, new BlockPos(3, 1, 1), Direction.SOUTH);
        BlockPos left = violinStand(h, new BlockPos(5, 1, 4)), right = violinStand(h, new BlockPos(1, 1, 4));
        be.setScore(punched(duet()));
        be.toggle(left);
        be.toggle(right);
        // 面朝南的指挥台：指挥的左手边是东（x 大）
        h.assertTrue(be.mask(left) == 1 && be.mask(right) == 1 << 1, "the left violin plays the first part: " + be.mask(left) + " " + be.mask(right));
        be.setTempo(80);
        be.setGain(1, -6);
        be.toggleMute(0);
        be.assign(be.members().indexOf(right), 1);
        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
        BlockEntity loaded = BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(), tag, level.registryAccess());
        if (!(loaded instanceof PodiumBlockEntity copy)) throw new AssertionError("podium did not load");
        h.assertTrue(copy.members().equals(be.members()), "members saved");
        h.assertTrue(copy.mask(right) == 1 && copy.locked(right) && !copy.locked(left), "manual assignment and lock saved");
        h.assertTrue(copy.tempo() == 80 && copy.gain(1) == -6 && copy.muteMask() == 1, "tempo, gain and mute saved");
        h.assertTrue(copy.score().has(Instruments.MUSIC_ROLL.get()), "the score saved");
        h.assertTrue(!copy.isPlaying(), "playback is not saved");
        h.succeed();
    }

    private static void membersLeave(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        PodiumBlockEntity be = podium(h, new BlockPos(3, 1, 1), Direction.SOUTH);
        BlockPos left = violinStand(h, new BlockPos(5, 1, 4)), right = violinStand(h, new BlockPos(1, 1, 4));
        BlockPos drum = timpani(h, new BlockPos(3, 1, 5));
        be.setScore(punched(duet()));
        for (BlockPos p : List.of(left, right, drum)) be.toggle(p);
        h.assertTrue(be.members().size() == 3, "three members");
        // 潜行空手右键架子，把小提琴拿下来
        Player player = h.makeMockPlayer(GameType.SURVIVAL);
        player.setShiftKeyDown(true);
        level.getBlockState(left).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(left), Direction.SOUTH, left, false));
        h.assertTrue(level.getBlockState(left).getValue(MusicStandBlock.HOLDS) == MusicStandBlock.Holds.NONE, "the stand is empty");
        h.assertTrue(!be.members().contains(left), "taking the violin off the stand leaves the orchestra");
        h.assertTrue(be.mask(right) == 1, "the other violin now plays the first part");
        level.destroyBlock(drum, false);
        h.assertTrue(!be.members().contains(drum), "a broken instrument leaves");
        level.destroyBlock(be.getBlockPos(), false);
        h.assertTrue(entity(h, right).orchestra() == null, "breaking the podium frees its instruments");
        h.succeed();
    }

    private static void conductedInstrumentsWait(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        PodiumBlockEntity be = podium(h, new BlockPos(3, 1, 1), Direction.SOUTH);
        BlockPos left = violinStand(h, new BlockPos(5, 1, 4)), right = violinStand(h, new BlockPos(1, 1, 4));
        be.setScore(punched(duet()));
        be.toggle(left);
        be.toggle(right);
        InstrumentBlockEntity violin = entity(h, left);
        violin.setRoll(punched(duet()));
        // 红石上升沿让指挥台开始
        BlockPos power = be.getBlockPos().north();
        level.setBlockAndUpdate(power, Blocks.REDSTONE_BLOCK.defaultBlockState());
        h.assertTrue(be.isPlaying(), "redstone starts the orchestra");
        h.assertTrue(be.positionMs() < 0, "it begins with the conductor's upbeat");
        h.assertTrue(be.getBlockPos().equals(violin.conductedBy()), "members follow the podium");
        h.assertTrue(!violin.play(), "a conducted instrument does not play its own roll");
        level.setBlockAndUpdate(left.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        h.assertTrue(!violin.isPlaying(), "nor does redstone start it");
        level.setBlockAndUpdate(left.west(), Blocks.AIR.defaultBlockState());
        be.stop();
        h.assertTrue(!be.isPlaying() && violin.conductedBy() == null, "stopping releases the members");
        h.assertTrue(violin.play(), "afterwards it plays its own roll again");
        violin.stopPlayback();
        level.setBlockAndUpdate(power, Blocks.AIR.defaultBlockState());
        h.succeed();
    }
}
