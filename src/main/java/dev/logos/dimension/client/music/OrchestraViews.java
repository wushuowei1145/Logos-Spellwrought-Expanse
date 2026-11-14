package dev.logos.dimension.client.music;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.InstrumentSeat;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicPayloads;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.MusicStandBlock;
import dev.logos.dimension.music.RollInfo;
import dev.logos.dimension.music.RollStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 中提琴、大提琴、低音提琴、双簧管、巴松管、定音鼓的客户端场景：三件方块乐器和三个乐谱架的样子；
 * 拿起中提琴、双簧管、巴松管演奏（第一人称和三个角度）；空手右键大提琴坐到琴凳上、右键低音提琴站到琴后，
 * 弹三个音核对声音、琴弓和双手，从四个角度各拍一张，起身后座位消失；定音鼓敲三下。
 * 最后装上「音乐厅音源」资源包（仓库里的 resourcepacks/concert_hall）：采样乐器都换成录音、界面写着音源，卸下又回到合成。
 */
public abstract class OrchestraViews implements UIScenario {
    protected static final int GROUND = 240;
    protected static final BlockPos ORIGIN = new BlockPos(210, GROUND, 100);
    protected static final BlockPos CELLO = ORIGIN.offset(-3, 1, 0);
    protected static final BlockPos BASS = ORIGIN.offset(0, 1, 0);
    protected static final BlockPos TIMPANI = ORIGIN.offset(3, 1, 0);
    private static int camera = -1;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("instrument").defaultTimeoutMs(180000).scenarioTimeoutMs(400000);
    }

    private static void place(ServerLevel level, BlockPos pos, Block block) {
        BlockState state = block.defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(pos, state);
        state.getBlock().setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
    }

    private static void stand(ServerLevel level, BlockPos pos, MusicStandBlock.Holds holds, Item item) {
        BlockState state = Instruments.MUSIC_STAND.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH)
            .setValue(MusicStandBlock.HOLDS, holds);
        level.setBlockAndUpdate(pos, state);
        if (level.getBlockEntity(pos) instanceof InstrumentBlockEntity be) be.setInstrument(new ItemStack(item));
        level.setBlockAndUpdate(pos, state);
    }

    protected ScenarioBuilder stage(ScenarioBuilder s, double eyeX, double eyeY, double eyeZ, float yaw, float pitch, boolean hideGui) {
        return s.setGameMode(GameType.CREATIVE)
            .runCommand("difficulty peaceful")
            .runCommand("time set noon")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = hideGui;
                mc.options.renderDistance().set(8);
                mc.options.fov().set(70);
                mc.options.broadcastOptions();
            })
            .server("stage the orchestra", ctx -> {
                ServerLevel level = ctx.server().overworld();
                for (int x = -9; x <= 9; x++) {
                    for (int z = -8; z <= 9; z++) {
                        BlockPos p = ORIGIN.offset(x, 0, z);
                        for (int y = 1; y <= 5; y++) {
                            level.setBlock(p.above(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                        }
                        level.setBlock(p, ((x + z & 1) == 0 ? Blocks.POLISHED_DIORITE : Blocks.SMOOTH_QUARTZ).defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
                level.getEntitiesOfClass(ItemEntity.class, new AABB(ORIGIN).inflate(16)).forEach(ItemEntity::discard);
                level.getEntitiesOfClass(InstrumentSeat.class, new AABB(ORIGIN).inflate(16)).forEach(InstrumentSeat::discard);
                place(level, CELLO, Instruments.CELLO.get());
                place(level, BASS, Instruments.DOUBLE_BASS.get());
                place(level, TIMPANI, Instruments.TIMPANI.get());
                stand(level, ORIGIN.offset(-2, 1, -5), MusicStandBlock.Holds.VIOLA, Instruments.VIOLA.get());
                stand(level, ORIGIN.offset(0, 1, -5), MusicStandBlock.Holds.OBOE, Instruments.OBOE.get());
                stand(level, ORIGIN.offset(2, 1, -5), MusicStandBlock.Holds.BASSOON, Instruments.BASSOON.get());
                var cam = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                cam.setInvisible(true);
                cam.setNoGravity(true);
                cam.setPos(ORIGIN.getX() + 0.5, GROUND + 1, ORIGIN.getZ() + 8);
                level.addFreshEntity(cam);
                camera = cam.getId();
                ctx.player().getInventory().clearContent();
                DimensionTravel.teleport(ctx.player(), level, new Vec3(ORIGIN.getX() + eyeX, GROUND + eyeY, ORIGIN.getZ() + eyeZ), yaw, pitch);
            })
            .waitUntil("instruments arrive", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level != null && mc.level.getBlockState(BASS.above()).is(Instruments.DOUBLE_BASS.get())
                    && mc.level.getBlockEntity(TIMPANI) instanceof InstrumentBlockEntity && mc.level.getEntity(camera) != null
                    // 谱架那格的区块可能比低音提琴、定音鼓晚到（客户端读到 void_air）：没到就接着等，不要 getValue 抛错
                    && mc.level.getBlockState(ORIGIN.offset(2, 1, -5)).getOptionalValue(MusicStandBlock.HOLDS).orElse(null) == MusicStandBlock.Holds.BASSOON;
            });
    }

    /** 从隐形盔甲架的眼睛里看玩家（offset 按世界坐标；演奏者都面朝南，+Z 是前方、+X 是他的左手边），看向他身上 lookY 高处。 */
    protected static ScenarioBuilder view(ScenarioBuilder s, String shot, double dx, double dy, double dz, double lookY) {
        return s.server("camera for " + shot, ctx -> {
                var cam = ctx.server().overworld().getEntity(camera);
                Vec3 me = ctx.player().position();
                Vec3 eye = me.add(dx, dy, dz);
                Vec3 look = me.add(0, lookY, 0).subtract(eye);
                float yaw = (float) (Mth.atan2(look.z, look.x) * Mth.RAD_TO_DEG) - 90;
                float pitch = (float) -(Mth.atan2(look.y, look.horizontalDistance()) * Mth.RAD_TO_DEG);
                cam.snapTo(eye.x, eye.y - cam.getEyeHeight(), eye.z, yaw, pitch);
                cam.setYHeadRot(yaw);
            })
            .ticks(5)
            .step("look from the camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.setCameraEntity(mc.level.getEntity(camera));
            })
            .ticks(2)
            .screenshot(shot)
            .step("back to my eyes", ctx -> {
                var mc = Minecraft.getInstance();
                mc.setCameraEntity(mc.player);
                mc.options.hideGui = false;
            });
    }

    private static ScenarioBuilder restoreEyes(ScenarioBuilder s) {
        return s.teardown("my own eyes again", ctx -> {
            var mc = Minecraft.getInstance();
            mc.setScreen(null);
            if (mc.player != null) mc.setCameraEntity(mc.player);
            mc.options.hideGui = false;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }

    @LDLRegisterClient(name = "orchestra_views", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Looks extends OrchestraViews {
        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = stage(s, 0.5, 2.6, 5.6, 180f, 16f, true)
                .ticks(60)
                .check("the stands know their instruments", ctx -> MusicClient.kindAt(ORIGIN.offset(0, 1, -5)) == InstrumentKind.OBOE)
                .screenshot("orchestra_views")
                .server("side view", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(ORIGIN.getX() - 5.0, GROUND + 2.8, ORIGIN.getZ() + 3.5), 225f, 20f))
                .ticks(40)
                .screenshot("orchestra_views_side")
                .server("behind the cello and bass", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(ORIGIN.getX() - 1.0, GROUND + 3.0, ORIGIN.getZ() - 2.6), 0f, 30f))
                .ticks(40)
                .screenshot("orchestra_views_back")
                .server("the stands", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(ORIGIN.getX() + 0.5, GROUND + 2.2, ORIGIN.getZ() - 1.6), 180f, 22f))
                .ticks(40)
                .screenshot("orchestra_views_stands")
                .step("hotbar", ctx -> Minecraft.getInstance().options.hideGui = false)
                .server("give the instruments", ctx -> {
                    var inv = ctx.player().getInventory();
                    Item[] items = {Instruments.VIOLA.get(), Instruments.OBOE.get(), Instruments.BASSOON.get(),
                        Instruments.CELLO_ITEM.get(), Instruments.DOUBLE_BASS_ITEM.get(), Instruments.TIMPANI_ITEM.get()};
                    for (int i = 0; i < items.length; i++) inv.setItem(i, new ItemStack(items[i]));
                    inv.setSelectedSlot(8);
                    DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                        new Vec3(ORIGIN.getX() + 0.5, GROUND + 2.6, ORIGIN.getZ() + 5.6), 180f, 16f);
                })
                .ticks(30)
                .screenshot("orchestra_views_items");
            restoreEyes(b);
        }
    }

    @LDLRegisterClient(name = "orchestra_handheld_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Held extends OrchestraViews {
        private static ScenarioBuilder hold(ScenarioBuilder s, String name, Item item) {
            s = s.server("hold the " + name, ctx -> {
                    ctx.player().getInventory().setItem(0, new ItemStack(item));
                    ctx.player().getInventory().setSelectedSlot(0);
                })
                .ticks(5)
                .step("raise the " + name, ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .waitUntil(name + " play bar opens", ctx -> Minecraft.getInstance().screen instanceof HandheldScreen)
                .waitUntilServer(name + " is in use on the server", ctx -> ctx.player().isUsingItem())
                .keyDown('Z').keyDown('C').keyDown('B')
                .ticks(6)
                .check(name + " sounds and follows the player", ctx -> {
                    var me = Minecraft.getInstance().player;
                    return VoiceBank.sounding(MusicClient.heldKey(me.getId())) >= 3 && MusicClient.recentlyPlayed(me.getId());
                })
                .screenshot("orchestra_play_" + name);
            s = view(s, "orchestra_play_" + name + "_left", 1.5, 1.8, 1.9, 1.3);
            s = view(s, "orchestra_play_" + name + "_right", -1.5, 1.8, 1.9, 1.3);
            s = view(s, "orchestra_play_" + name + "_side", 2.3, 1.8, 0.2, 1.3);
            return s.keyUp('Z').keyUp('C').keyUp('B')
                .step("put the " + name + " away", ctx -> Minecraft.getInstance().setScreen(null))
                .ticks(6)
                .check(name + " is put down", ctx -> !Minecraft.getInstance().player.isUsingItem());
        }

        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = stage(s, 0.5, 1.0, 4.0, 0f, 5f, false);
            b = hold(b, "viola", Instruments.VIOLA.get());
            b = hold(b, "oboe", Instruments.OBOE.get());
            b = hold(b, "bassoon", Instruments.BASSOON.get());
            restoreEyes(b);
        }
    }

    @LDLRegisterClient(name = "orchestra_seated_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Seated extends OrchestraViews {
        /** 空手右键琴：坐（站）到琴后，乐器界面打开；弹三个音，再关掉界面从四个角度看，最后起身。 */
        private static ScenarioBuilder sit(ScenarioBuilder s, String name, BlockPos pos, InstrumentKind kind, double lookY) {
            s = s.server("walk up to the " + name, ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(pos.getX() + 0.5, GROUND + 1, pos.getZ() + 2.2), 180f, 10f))
                .ticks(10)
                .step("right-click the " + name, ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0, 0.3), Direction.SOUTH, pos, false));
                })
                .waitUntil("seated at the " + name, ctx -> {
                    var mc = Minecraft.getInstance();
                    return mc.player.getVehicle() instanceof InstrumentSeat seat && seat.kind() == kind
                        && mc.screen instanceof InstrumentScreen;
                })
                .check(name + " seat faces the instrument", ctx -> {
                    var me = Minecraft.getInstance().player;
                    // 琴朝南放，人在琴的北面、面朝南（yaw 0）
                    return me.getZ() < pos.getZ() + 1 && Math.abs(Mth.wrapDegrees(me.yBodyRot)) < 1;
                })
                .keyDown('Z').keyDown('C').keyDown('B')
                .ticks(6)
                .check(name + " sounds", ctx -> VoiceBank.sounding(MusicClient.liveKey(pos)) >= 3 && VoiceBank.recentlyPlayed(pos))
                .screenshot("orchestra_seated_" + name + "_ui")
                .keyUp('Z').keyUp('C').keyUp('B')
                .step("close the " + name + " screen", ctx -> Minecraft.getInstance().setScreen(null))
                .ticks(4)
                .check("still seated at the " + name, ctx -> Minecraft.getInstance().player.getVehicle() instanceof InstrumentSeat);
            s = view(s, "orchestra_seated_" + name + "_front_left", 1.6, 1.5, 2.4, lookY);
            s = view(s, "orchestra_seated_" + name + "_front_right", -1.6, 1.5, 2.4, lookY);
            s = view(s, "orchestra_seated_" + name + "_side", 2.6, 1.2, 0.4, lookY);
            s = view(s, "orchestra_seated_" + name + "_back", -0.9, 1.9, -2.0, lookY);
            return s.server("stand up from the " + name, ctx -> ctx.player().stopRiding())
                .ticks(10)
                .check("the " + name + " seat is gone", ctx -> Minecraft.getInstance().player.getVehicle() == null
                    && Minecraft.getInstance().level.getEntitiesOfClass(InstrumentSeat.class, new AABB(pos).inflate(3)).isEmpty())
                .checkServer("the " + name + " seat is gone on the server", ctx ->
                    ctx.server().overworld().getEntitiesOfClass(InstrumentSeat.class, new AABB(pos).inflate(3)).isEmpty());
        }

        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = stage(s, 0.5, 1.0, 4.0, 180f, 10f, false);
            b = sit(b, "cello", CELLO, InstrumentKind.CELLO, 0.9);
            b = sit(b, "double_bass", BASS, InstrumentKind.DOUBLE_BASS, 1.2);
            b = b.server("walk up to the timpani", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(TIMPANI.getX() + 0.5, GROUND + 1, TIMPANI.getZ() - 1.2), 0f, 30f))
                .ticks(10)
                .openScreen("open the timpani", ctx -> new InstrumentScreen(TIMPANI))
                .waitUntilServer("player claims the timpani", ctx -> ctx.server().overworld().getBlockEntity(TIMPANI) instanceof InstrumentBlockEntity be
                    && ctx.player().getUUID().equals(be.performer()))
                .keyDown('X').keyDown('C').keyDown('B')
                .ticks(4)
                .check("three drum strokes", ctx -> VoiceBank.sounding(MusicClient.liveKey(TIMPANI)) >= 3)
                .screenshot("orchestra_timpani_ui")
                .keyUp('X').keyUp('C').keyUp('B')
                .ticks(20)
                .check("timpani ring on after the keys are let go", ctx -> VoiceBank.sounding(MusicClient.liveKey(TIMPANI)) >= 1)
                .step("close the timpani", ctx -> Minecraft.getInstance().setScreen(null));
            restoreEyes(b);
        }
    }

    /**
     * 合奏回归（#243 之后）：服务端每 5 秒发一次心跳报播放位置。以前客户端发现时钟差了 150 毫秒以上就重新定位，
     * 把这件乐器正在响的音全部掐掉（服务端卡顿时每件乐器各自在心跳时一件件哑掉）。这里让大提琴放一个 6 秒的长音，
     * 中途模拟一个偏了 0.4 秒的心跳，核对长音还在响；偏了 5 秒（服务端那边真的跳了位置）才重新定位。
     */
    @LDLRegisterClient(name = "ensemble_resync_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Resync extends OrchestraViews {
        private static String rollKey() {
            return "roll:" + CELLO.asLong();
        }

        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = stage(s, 0.5, 1.0, 4.0, 180f, 10f, false)
                .server("a long cello note on a roll", ctx -> {
                    try {
                        var ev = List.of(MusicRoll.Event.note(200, 0, 48, 100, 6000), MusicRoll.Event.note(6500, 0, 55, 100, 300));
                        MusicRoll roll = new MusicRoll("长音", "测试", 12000, 4, 4, List.of(new MusicRoll.Part("大提琴", 0)), ev);
                        byte[] encoded = roll.encode();
                        UUID id = RollStore.save(encoded);
                        ItemStack punched = new ItemStack(Instruments.MUSIC_ROLL_ITEM.get());
                        punched.set(Instruments.MUSIC_ROLL.get(), RollInfo.of(id, roll, MusicRoll.hash(encoded)));
                        if (ctx.server().overworld().getBlockEntity(CELLO) instanceof InstrumentBlockEntity be) {
                            be.setRoll(punched);
                            be.play();
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .waitUntil("the long note is sounding", ctx -> VoiceBank.sounding(rollKey()) >= 1)
                .ticks(20)
                .step("a heartbeat 0.4 s off", ctx -> {
                    var t = MusicClient.track(CELLO);
                    MusicClient.playback(new MusicPayloads.Playback(CELLO, t.rollId, 0, t.positionMs(System.nanoTime()) + 400, true, 0xFFFF));
                })
                .ticks(10)
                .check("the long note keeps sounding through the resync", ctx -> VoiceBank.sounding(rollKey()) >= 1)
                .step("a heartbeat 5 s off", ctx -> {
                    var t = MusicClient.track(CELLO);
                    MusicClient.playback(new MusicPayloads.Playback(CELLO, t.rollId, 0, t.positionMs(System.nanoTime()) - 5000, true, 0xFFFF));
                })
                .ticks(10)
                .check("a real jump repositions the roll", ctx -> MusicClient.track(CELLO).positionMs(System.nanoTime()) < 2000);
            b.teardownServer("stop the roll", ctx -> {
                if (ctx.server().overworld().getBlockEntity(CELLO) instanceof InstrumentBlockEntity be) be.stopPlayback();
            });
            restoreEyes(b);
        }
    }

    /** 仓库里的资源包目录：从游戏目录往上找 resourcepacks/concert_hall。 */
    static Path packSource() {
        for (Path p = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath(); p != null; p = p.getParent()) {
            Path pack = p.resolve("resourcepacks/concert_hall");
            if (Files.isRegularFile(pack.resolve("pack.mcmeta"))) return pack;
        }
        throw new IllegalStateException("resourcepacks/concert_hall not found above " + Minecraft.getInstance().gameDirectory);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Path target = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(target);
                else Files.copy(f, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static CompletableFuture<Void> reloading = CompletableFuture.completedFuture(null);

    private static void select(boolean on) {
        var mc = Minecraft.getInstance();
        var repo = mc.getResourcePackRepository();
        repo.reload();
        List<String> selected = new ArrayList<>(repo.getSelectedIds());
        selected.remove("file/concert_hall");
        if (on) selected.add("file/concert_hall");
        repo.setSelected(selected);
        reloading = mc.reloadResourcePacks();
    }

    private static boolean allRecorded() {
        for (InstrumentKind kind : InstrumentKind.values()) {
            if (kind.synthesized() || kind == InstrumentKind.CELESTA) continue;
            var map = SampleMaps.get(kind);
            if (map == null || !map.recorded()) return false;
        }
        return true;
    }

    private static boolean noneRecorded() {
        for (InstrumentKind kind : InstrumentKind.values()) {
            var map = SampleMaps.get(kind);
            if (map != null && map.recorded()) return false;
        }
        return true;
    }

    @LDLRegisterClient(name = "concert_hall_pack_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Pack extends OrchestraViews {
        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = stage(s, 0.5, 1.0, 4.0, 0f, 5f, false)
                .check("without the pack every instrument is synthesized", ctx -> noneRecorded())
                .step("install the concert hall pack", ctx -> {
                    try {
                        copyTree(packSource(), Minecraft.getInstance().getResourcePackDirectory().resolve("concert_hall"));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    select(true);
                })
                .waitUntil("the pack is loaded", ctx -> reloading.isDone() && Minecraft.getInstance().getOverlay() == null)
                .check("with the pack the sampled instruments play recordings", ctx -> allRecorded())
                .check("the celesta stays synthesized", ctx -> !SampleMaps.get(InstrumentKind.CELESTA).recorded())
                .server("hold the violin", ctx -> {
                    ctx.player().getInventory().setItem(0, new ItemStack(Instruments.VIOLIN.get()));
                    ctx.player().getInventory().setSelectedSlot(0);
                })
                .ticks(5)
                .step("raise the violin", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .waitUntil("violin play bar opens", ctx -> Minecraft.getInstance().screen instanceof HandheldScreen)
                .keyDown('C')
                .ticks(6)
                .check("the recorded violin sounds", ctx -> VoiceBank.sounding(MusicClient.heldKey(Minecraft.getInstance().player.getId())) >= 1)
                .screenshot("concert_hall_pack_violin")
                .keyUp('C')
                .step("put the violin away", ctx -> Minecraft.getInstance().setScreen(null))
                .ticks(4)
                .step("take the pack off", ctx -> select(false))
                .waitUntil("the pack is unloaded", ctx -> reloading.isDone() && Minecraft.getInstance().getOverlay() == null)
                .check("back to the synthesized sounds", ctx -> noneRecorded());
            b.teardown("pack off, own eyes", ctx -> {
                var mc = Minecraft.getInstance();
                mc.setScreen(null);
                if (mc.getResourcePackRepository().getSelectedIds().contains("file/concert_hall")) select(false);
            });
        }
    }
}
