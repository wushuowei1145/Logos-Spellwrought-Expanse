package dev.logos.dimension.client.music;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicPayloads;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.RollInfo;
import dev.logos.dimension.music.RollStore;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 乐器的客户端场景：方块外观（正面、侧面、两种朝向），演奏页（按住三个键弹和弦），纸带页（播放中），
 * 打孔编辑器（读入一卷纸带），MIDI 导入页（往 logos_music 放一个小文件，选中后预览）。
 * 声音本身只能人工试听，这里只核对状态与截图。
 */
public abstract class InstrumentViews implements UIScenario {
    protected static final int GROUND = 240;
    protected static final BlockPos ORIGIN = new BlockPos(120, GROUND, 0);
    protected static final BlockPos PIANO = ORIGIN.above();
    protected static final BlockPos HARP = ORIGIN.offset(4, 1, 0);
    protected static final BlockPos PIANO_EAST = ORIGIN.offset(-6, 1, 3);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("instrument").defaultTimeoutMs(180000).scenarioTimeoutMs(300000);
    }

    /** 一段 C 大调的小曲，两个声部（旋律 + 低音），给纸带页和编辑器用。 */
    static MusicRoll tune() {
        int[] melody = {72, 76, 79, 76, 77, 74, 71, 74, 72, 76, 79, 84};
        int[] bass = {48, 55, 53, 55, 48, 43};
        List<MusicRoll.Event> ev = new ArrayList<>();
        for (int i = 0; i < melody.length; i++) ev.add(MusicRoll.Event.note(i * 400, 0, melody[i], 96, 380));
        for (int i = 0; i < bass.length; i++) ev.add(MusicRoll.Event.note(i * 800, 1, bass[i], 80, 780));
        ev.add(MusicRoll.Event.pedal(0, 1, true));
        ev.add(MusicRoll.Event.pedal(4700, 1, false));
        return new MusicRoll("星辰花小步舞曲", "场景测试", 15000, 3, 4,
            List.of(new MusicRoll.Part("旋律", 0), new MusicRoll.Part("低音", 0)), ev);
    }

    private static BlockState place(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlockAndUpdate(pos, state);
        state.getBlock().setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
        return state;
    }

    protected ScenarioBuilder stage(ScenarioBuilder s, double eyeX, double eyeY, double eyeZ, float yaw, float pitch, boolean hideGui) {
        return s.setGameMode(GameType.CREATIVE)
            .runCommand("difficulty peaceful")
            .runCommand("time set day")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = hideGui;
                mc.options.renderDistance().set(8);
                mc.options.fov().set(70);
                mc.options.broadcastOptions();
            })
            .server("stage instruments", ctx -> {
                ServerLevel level = ctx.server().overworld();
                // 上一个场景摆的乐器：不掉落地拆掉（否则连带拆除会掉出一地乐器）
                for (int x = -10; x <= 10; x++) {
                    for (int z = -8; z <= 10; z++) {
                        for (int y = 1; y <= 6; y++) {
                            BlockPos p = ORIGIN.offset(x, y, z);
                            if (level.getBlockState(p).getBlock() instanceof InstrumentBlock) {
                                level.setBlock(p, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS
                                    | net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                            }
                        }
                    }
                }
                level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(ORIGIN).inflate(16)).forEach(net.minecraft.world.entity.Entity::discard);
                for (int x = -10; x <= 10; x++) {
                    for (int z = -8; z <= 10; z++) {
                        BlockPos p = ORIGIN.offset(x, 0, z);
                        level.setBlockAndUpdate(p, (x + z & 1) == 0 ? Blocks.POLISHED_DIORITE.defaultBlockState()
                            : Blocks.SMOOTH_QUARTZ.defaultBlockState());
                        for (int y = 1; y <= 6; y++) level.removeBlock(p.above(y), false);
                    }
                }
                place(level, PIANO, Instruments.GRAND_PIANO.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH));
                place(level, HARP, Instruments.CONCERT_HARP.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH));
                place(level, PIANO_EAST, Instruments.GRAND_PIANO.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.EAST));
                try {
                    MusicRoll roll = tune();
                    byte[] encoded = roll.encode();
                    UUID id = RollStore.save(encoded);
                    ItemStack punched = new ItemStack(Instruments.MUSIC_ROLL_ITEM.get());
                    punched.set(Instruments.MUSIC_ROLL.get(), RollInfo.of(id, roll, MusicRoll.hash(encoded)));
                    if (level.getBlockEntity(PIANO) instanceof InstrumentBlockEntity be) be.setRoll(punched);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                DimensionTravel.teleport(ctx.player(), level, new Vec3(ORIGIN.getX() + eyeX, GROUND + eyeY, ORIGIN.getZ() + eyeZ), yaw, pitch);
            })
            .waitUntil("stage arrives", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level != null && mc.level.getBlockState(HARP).is(Instruments.CONCERT_HARP.get())
                    && mc.level.getBlockEntity(PIANO) instanceof InstrumentBlockEntity be && !be.roll().isEmpty();
            });
    }

    @LDLRegisterClient(name = "instrument_views", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Looks extends InstrumentViews {
        @Override
        public void define(ScenarioBuilder s) {
            stage(s, 1.0, 2.8, 7.5, 180f, 14f, true)
                .ticks(60)
                .checkServer("piano covers four cells", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    int n = 0;
                    for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) {
                        if (level.getBlockState(PIANO.offset(x, 0, z)).is(Instruments.GRAND_PIANO.get())) n++;
                    }
                    return n == 4;
                })
                .screenshot("instrument_views")
                .server("side view", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(ORIGIN.getX() + 6.5, GROUND + 3.4, ORIGIN.getZ() + 5.5), 135f, 22f))
                .ticks(40)
                .screenshot("instrument_views_side")
                .server("close view of the harp", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(HARP.getX() + 0.5, GROUND + 2.2, HARP.getZ() + 3.2), 180f, 4f))
                .ticks(40)
                .screenshot("instrument_views_harp")
                .server("look down at the keys", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(PIANO.getX(), GROUND + 3.2, PIANO.getZ() + 1.6), 180f, 62f))
                .ticks(40)
                .screenshot("instrument_views_keys")
                .server("hotbar with the instruments", ctx -> {
                    var inv = ctx.player().getInventory();
                    inv.setItem(0, new ItemStack(Instruments.GRAND_PIANO_ITEM.get()));
                    inv.setItem(1, new ItemStack(Instruments.CONCERT_HARP_ITEM.get()));
                    inv.setItem(2, new ItemStack(Instruments.BLANK_ROLL.get()));
                    inv.setItem(3, new ItemStack(Instruments.MUSIC_ROLL_ITEM.get()));
                    inv.setSelectedSlot(0);
                    DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                        new Vec3(ORIGIN.getX() + 1.0, GROUND + 2.8, ORIGIN.getZ() + 7.5), 180f, 14f);
                })
                .step("show HUD", ctx -> Minecraft.getInstance().options.hideGui = false)
                .ticks(40)
                .screenshot("instrument_views_items")
                .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    @LDLRegisterClient(name = "instrument_play_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Play extends InstrumentViews {
        @Override
        public void define(ScenarioBuilder s) {
            stage(s, 0.0, 1.0, 3.5, 180f, 10f, false)
                .openScreen("open piano", ctx -> new InstrumentScreen(PIANO))
                .ticks(10)
                .waitUntilServer("player claims the piano", ctx -> ctx.server().overworld().getBlockEntity(PIANO) instanceof InstrumentBlockEntity be
                    && ctx.player().getUUID().equals(be.performer()))
                .keyDown('Z').keyDown('C').keyDown('B')
                .ticks(4)
                .check("chord keys are held", ctx -> Minecraft.getInstance().screen instanceof InstrumentScreen)
                .screenshot("instrument_play_ui")
                .keyUp('Z').keyUp('C').keyUp('B')
                .step("roll page and play", ctx -> {
                    if (Minecraft.getInstance().screen instanceof InstrumentScreen screen) screen.showRollPage();
                    MusicClient.control(PIANO, MusicPayloads.Control.PLAY, 0);
                })
                .waitUntilServer("roll plays on the server", ctx -> ctx.server().overworld().getBlockEntity(PIANO) instanceof InstrumentBlockEntity be
                    && be.isPlaying())
                .waitUntil("client follows the roll", ctx -> {
                    MusicClient.Track t = MusicClient.track(PIANO);
                    return t != null && t.playing && t.roll != null;
                })
                .ticks(30)
                .screenshot("instrument_roll_ui")
                .teardown("close", ctx -> Minecraft.getInstance().setScreen(null))
                .teardownServer("stop", ctx -> {
                    if (ctx.server().overworld().getBlockEntity(PIANO) instanceof InstrumentBlockEntity be) be.stopPlayback();
                });
        }
    }

    @LDLRegisterClient(name = "roll_editor_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Editor extends InstrumentViews {
        @Override
        public void define(ScenarioBuilder s) {
            stage(s, 0.0, 1.0, 3.5, 180f, 10f, false)
                .openScreen("open editor", ctx -> {
                    var be = (InstrumentBlockEntity) Minecraft.getInstance().level.getBlockEntity(PIANO);
                    return new RollEditorScreen(PIANO, new InstrumentScreen(PIANO), be.roll().get(Instruments.MUSIC_ROLL.get()));
                })
                .waitUntil("roll loaded into the editor", ctx -> Minecraft.getInstance().screen instanceof RollEditorScreen e
                    && e.loaded() && e.noteCount() == 18)
                .ticks(10)
                .screenshot("roll_editor_ui")
                .teardown("close", ctx -> Minecraft.getInstance().setScreen(null));
        }
    }

    @LDLRegisterClient(name = "midi_import_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Import extends InstrumentViews {
        private static final String FILE = "logos_scenario_waltz.mid";

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, 0.0, 1.0, 3.5, 180f, 10f, false)
                .step("write a small MIDI file", ctx -> {
                    try {
                        Files.write(MidiLibrary.ensureFolder().resolve(FILE), waltz());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .openScreen("open import", ctx -> new MidiImportScreen(PIANO, new InstrumentScreen(PIANO)))
                .step("pick the file", ctx -> {
                    if (Minecraft.getInstance().screen instanceof MidiImportScreen screen) screen.pick(FILE);
                })
                .waitUntil("file parsed", ctx -> Minecraft.getInstance().screen instanceof MidiImportScreen m && m.parsed())
                .ticks(10)
                .screenshot("midi_import_ui")
                .teardown("close and clean up", ctx -> {
                    Minecraft.getInstance().setScreen(null);
                    try {
                        Files.deleteIfExists(MidiLibrary.folder().resolve(FILE));
                    } catch (Exception ignored) {
                        // 测试文件删不掉也不影响结果
                    }
                });
        }

        /** 格式 1：速度轨 + 旋律轨 + 低音轨 + 一条打击乐（导入时应提示略过）。 */
        private static byte[] waltz() {
            ByteArrayOutputStream tempo = new ByteArrayOutputStream();
            tempo.writeBytes(new byte[]{0, (byte) 0xFF, 0x51, 3, 0x07, (byte) 0xA1, 0x20, 0, (byte) 0xFF, 0x58, 4, 3, 2, 24, 8,
                0, (byte) 0xFF, 0x2F, 0});
            ByteArrayOutputStream melody = new ByteArrayOutputStream();
            name(melody, "Starbloom Melody");
            int[] notes = {76, 79, 84, 83, 79, 76, 77, 81, 86, 84};
            for (int n : notes) note(melody, 0, n, 100, 240);
            end(melody);
            ByteArrayOutputStream bass = new ByteArrayOutputStream();
            name(bass, "Bass");
            for (int n : new int[]{48, 55, 53, 50, 48}) note(bass, 1, n, 80, 480);
            end(bass);
            ByteArrayOutputStream drums = new ByteArrayOutputStream();
            for (int i = 0; i < 4; i++) note(drums, 9, 36, 90, 120);
            end(drums);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes("MThd".getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(new byte[]{0, 0, 0, 6, 0, 1, 0, 4, 0, (byte) 0xF0});
            for (ByteArrayOutputStream t : List.of(tempo, melody, bass, drums)) {
                byte[] b = t.toByteArray();
                out.writeBytes("MTrk".getBytes(StandardCharsets.ISO_8859_1));
                out.writeBytes(new byte[]{(byte) (b.length >>> 24), (byte) (b.length >>> 16), (byte) (b.length >>> 8), (byte) b.length});
                out.writeBytes(b);
            }
            return out.toByteArray();
        }

        private static void name(ByteArrayOutputStream t, String name) {
            byte[] b = name.getBytes(StandardCharsets.UTF_8);
            t.writeBytes(new byte[]{0, (byte) 0xFF, 0x03, (byte) b.length});
            t.writeBytes(b);
        }

        private static void note(ByteArrayOutputStream t, int channel, int pitch, int vel, int ticks) {
            t.writeBytes(new byte[]{0, (byte) (0x90 | channel), (byte) pitch, (byte) vel});
            t.writeBytes(new byte[]{(byte) (0x80 | (ticks >> 7)), (byte) (ticks & 0x7F), (byte) (0x80 | channel), (byte) pitch, 0});
        }

        private static void end(ByteArrayOutputStream t) {
            t.writeBytes(new byte[]{0, (byte) 0xFF, 0x2F, 0});
        }
    }
}
