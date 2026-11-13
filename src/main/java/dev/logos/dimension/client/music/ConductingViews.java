package dev.logos.dimension.client.music;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentSeat;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.MusicStandBlock;
import dev.logos.dimension.music.RollInfo;
import dev.logos.dimension.music.RollStore;
import dev.logos.dimension.music.orchestra.BeatMap;
import dev.logos.dimension.music.orchestra.Orchestra;
import dev.logos.dimension.music.orchestra.PodiumBlock;
import dev.logos.dimension.music.orchestra.PodiumBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
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
import org.jspecify.annotations.Nullable;

/**
 * 乐团的客户端场景（docs/ORCHESTRA.md）：十二件乐器围成半圆，前面一座指挥台（指挥面朝北）。一份十二个声部的总谱
 * （长笛第 5 小节、小号第 9 小节才进来，有渐强）。召集附近、自动分声部、指挥台界面、选中声部的描边（看得见的、被墙挡住的）、
 * 指挥棒开始演奏、预备拍、十二件一起响、客户端和服务端的时钟一致（含 /tick rate 10 卡顿）、左手提示、收束；
 * 四种拍子的手势按拍点、高点各拍一张；坐在大提琴后面的人跟着乐团拉弓。
 */
public abstract class ConductingViews implements UIScenario {
    protected static final int GROUND = 240;
    protected static final BlockPos ORIGIN = new BlockPos(270, GROUND, 100);
    /** 指挥台主块：指挥面朝北（−z），侧块在东边（指挥的右手边）。 */
    protected static final BlockPos PODIUM = ORIGIN.offset(0, 1, 6);
    protected static final BlockPos CELLO = ORIGIN.offset(4, 1, 3);
    protected static final BlockPos TIMPANI = ORIGIN.offset(2, 1, -3);
    private static int camera = -1;
    /** 服务端记下的乐团位置与那一刻（同一个 JVM，nanoTime 可以直接比）。 */
    private static int serverPosition;
    private static long serverNanos;
    private static int serverTempo = 100;
    private static long outlineBefore;
    private static int victim = -1;
    private static boolean attackedHealthy;

    /** 一个座位：方块乐器，或放了手持乐器的乐谱架；part 是它该分到的声部。 */
    record Seat(BlockPos pos, Block block, MusicStandBlock.@Nullable Holds holds, @Nullable Item held, int part) {}

    private static Seat stand(int x, int z, MusicStandBlock.Holds holds, Item held, int part) {
        return new Seat(ORIGIN.offset(x, 1, z), Instruments.MUSIC_STAND.get(), holds, held, part);
    }

    private static Seat block(int x, int z, Block block, int part) {
        return new Seat(ORIGIN.offset(x, 1, z), block, null, null, part);
    }

    static List<Seat> seats() {
        return List.of(
            stand(-3, 3, MusicStandBlock.Holds.VIOLIN, Instruments.VIOLIN.get(), 0),
            stand(-1, 3, MusicStandBlock.Holds.VIOLIN, Instruments.VIOLIN.get(), 1),
            stand(2, 3, MusicStandBlock.Holds.VIOLA, Instruments.VIOLA.get(), 2),
            block(4, 3, Instruments.CELLO.get(), 3),
            stand(-2, 0, MusicStandBlock.Holds.FLUTE, Instruments.FLUTE.get(), 4),
            stand(0, 0, MusicStandBlock.Holds.OBOE, Instruments.OBOE.get(), 5),
            stand(2, 0, MusicStandBlock.Holds.BASSOON, Instruments.BASSOON.get(), 6),
            block(5, 0, Instruments.DOUBLE_BASS.get(), 7),
            stand(-1, -3, MusicStandBlock.Holds.TRUMPET, Instruments.TRUMPET.get(), 8),
            block(2, -3, Instruments.TIMPANI.get(), 9),
            block(-4, -2, Instruments.CONCERT_HARP.get(), 10),
            block(4, -3, Instruments.GLOCKENSPIEL.get(), 11));
    }

    static final int BPM_X100 = 11200, BARS = 12;

    static int quarterMs() {
        return 60000 * 100 / BPM_X100;
    }

    /** 十二个声部、十二小节的 4/4：C F G C Am F G C C F G C，渐强；长笛第 5 小节、小号第 9 小节进来。 */
    static MusicRoll score() {
        int q = quarterMs();
        int[][] chords = {{60, 64, 67}, {65, 69, 72}, {67, 71, 74}, {60, 64, 67}, {57, 60, 64}, {65, 69, 72},
            {67, 71, 74}, {60, 64, 67}, {60, 64, 67}, {65, 69, 72}, {67, 71, 74}, {60, 64, 67}};
        int[] timpani = {48, 41, 43, 48, 45, 41, 43, 48, 48, 41, 43, 48};
        List<MusicRoll.Event> ev = new ArrayList<>();
        for (int bar = 0; bar < BARS; bar++) {
            int[] c = chords[bar];
            int vel = bar >= 10 ? 120 : 52 + bar * 6;
            int t0 = bar * 4 * q;
            for (int beat = 0; beat < 4; beat++) {
                int t = t0 + beat * q;
                ev.add(MusicRoll.Event.note(t, 0, c[beat % 3] + 12, vel, q - 20));
                ev.add(MusicRoll.Event.note(t, 1, c[(beat + 1) % 3], vel - 10, q - 20));
                if (beat % 2 == 0) {
                    ev.add(MusicRoll.Event.note(t, 2, c[beat / 2], vel - 10, 2 * q - 20));
                    if (bar >= 4) ev.add(MusicRoll.Event.note(t, 4, c[beat] + 12, vel, 2 * q - 20));
                    ev.add(MusicRoll.Event.note(t, 5, c[2 - beat / 2], vel - 15, 2 * q - 20));
                    ev.add(MusicRoll.Event.note(t, 6, c[0] - 12, vel - 5, q - 20));
                    ev.add(MusicRoll.Event.note(t, 9, timpani[bar], vel, q));
                }
                for (int half = 0; half < 2; half++) {
                    int[] arp = {c[0], c[1], c[2], c[0] + 12, c[2], c[1], c[0], c[1]};
                    ev.add(MusicRoll.Event.note(t + half * q / 2, 10, arp[beat * 2 + half], vel - 20, q / 2));
                }
            }
            ev.add(MusicRoll.Event.note(t0, 3, c[0] - 12, vel, 4 * q - 30));
            ev.add(MusicRoll.Event.note(t0, 7, c[0] - 24, vel, 4 * q - 30));
            if (bar >= 8) {
                ev.add(MusicRoll.Event.note(t0, 8, c[0], vel, q - 20));
                ev.add(MusicRoll.Event.note(t0 + 2 * q, 8, c[2], vel, 2 * q - 20));
            }
            if (bar % 2 == 0) ev.add(MusicRoll.Event.note(t0, 11, c[2] + 24, vel, q));
        }
        List<MusicRoll.Part> parts = List.of(new MusicRoll.Part("第一小提琴", 40), new MusicRoll.Part("第二小提琴", 40),
            new MusicRoll.Part("中提琴", 41), new MusicRoll.Part("大提琴", 42), new MusicRoll.Part("长笛", 73),
            new MusicRoll.Part("双簧管", 68), new MusicRoll.Part("巴松管", 70), new MusicRoll.Part("低音提琴", 43),
            new MusicRoll.Part("小号", 56), new MusicRoll.Part("定音鼓", 47), new MusicRoll.Part("竖琴", 46), new MusicRoll.Part("钟琴", 9));
        return new MusicRoll("魔女之森进行曲", "测试", BPM_X100, 4, 4, parts, ev);
    }

    static ItemStack punched(MusicRoll roll) {
        try {
            byte[] encoded = roll.encode();
            UUID id = RollStore.save(encoded);
            ItemStack stack = new ItemStack(Instruments.MUSIC_ROLL_ITEM.get());
            stack.set(Instruments.MUSIC_ROLL.get(), RollInfo.of(id, roll, MusicRoll.hash(encoded)));
            return stack;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("instrument").defaultTimeoutMs(180000).scenarioTimeoutMs(500000);
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlockAndUpdate(pos, state);
        state.getBlock().setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
    }

    static @Nullable PodiumBlockEntity serverPodium(ServerLevel level) {
        return level.getBlockEntity(PODIUM) instanceof PodiumBlockEntity be ? be : null;
    }

    static @Nullable PodiumBlockEntity clientPodium() {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(PODIUM) instanceof PodiumBlockEntity be ? be : null;
    }

    /** 指挥站的位置：两格交界处、台面上。 */
    static Vec3 standAt() {
        return new Vec3(PODIUM.getX() + 1.0, PODIUM.getY() + 0.5, PODIUM.getZ() + 0.5);
    }

    /** 搭台：地板、十二件乐器、指挥台、隐形的摄像机；玩家站上指挥台、面朝乐团。{@code baton} 时手里一根绑好的指挥棒。 */
    protected ScenarioBuilder stage(ScenarioBuilder s, boolean baton, boolean hideGui) {
        return s.setGameMode(GameType.CREATIVE)
            .runCommand("difficulty peaceful")
            .runCommand("time set noon")
            .runCommand("tick rate 20")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = hideGui;
                mc.options.renderDistance().set(8);
                mc.options.fov().set(70);
                mc.options.broadcastOptions();
                Conducting.testBeat = null;
                Conducting.testAmplitude = 1.0;
            })
            .server("stage the orchestra", ctx -> {
                ServerLevel level = ctx.server().overworld();
                PodiumBlockEntity old = serverPodium(level);
                if (old != null) old.stop();
                for (int x = -8; x <= 8; x++) {
                    for (int z = -7; z <= 11; z++) {
                        BlockPos p = ORIGIN.offset(x, 0, z);
                        for (int y = 1; y <= 5; y++) {
                            level.setBlock(p.above(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                        }
                        level.setBlock(p, ((x + z & 1) == 0 ? Blocks.POLISHED_DIORITE : Blocks.SMOOTH_QUARTZ).defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
                level.getEntitiesOfClass(ItemEntity.class, new AABB(ORIGIN).inflate(16)).forEach(ItemEntity::discard);
                level.getEntitiesOfClass(InstrumentSeat.class, new AABB(ORIGIN).inflate(16)).forEach(InstrumentSeat::discard);
                for (Seat seat : seats()) {
                    if (seat.holds() != null) {
                        BlockState state = Instruments.MUSIC_STAND.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH)
                            .setValue(MusicStandBlock.HOLDS, seat.holds());
                        level.setBlockAndUpdate(seat.pos(), state);
                        if (level.getBlockEntity(seat.pos()) instanceof InstrumentBlockEntity be) be.setInstrument(new ItemStack(seat.held()));
                        level.setBlockAndUpdate(seat.pos(), state);
                    } else {
                        place(level, seat.pos(), seat.block().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH));
                    }
                }
                place(level, PODIUM, Orchestra.PODIUM.get().defaultBlockState().setValue(PodiumBlock.FACING, Direction.NORTH));
                var cam = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                cam.setInvisible(true);
                cam.setNoGravity(true);
                cam.setPos(ORIGIN.getX() + 0.5, GROUND + 1, ORIGIN.getZ() - 6);
                level.addFreshEntity(cam);
                camera = cam.getId();
                var inv = ctx.player().getInventory();
                inv.clearContent();
                if (baton) {
                    ItemStack stick = new ItemStack(Orchestra.BATON.get());
                    stick.set(Orchestra.BATON_PODIUM.get(), GlobalPos.of(level.dimension(), PODIUM));
                    inv.setItem(0, stick);
                }
                inv.setSelectedSlot(0);
                Vec3 at = standAt();
                DimensionTravel.teleport(ctx.player(), level, at, 180f, 12f);
            })
            .waitUntil("the orchestra arrives", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level != null && clientPodium() != null && mc.level.getEntity(camera) != null
                    && mc.level.getBlockState(ORIGIN.offset(-1, 1, -3)).getValue(MusicStandBlock.HOLDS) == MusicStandBlock.Holds.TRUMPET
                    && mc.level.getBlockState(CELLO.above()).is(Instruments.CELLO.get());
            });
    }

    /** 从隐形盔甲架的眼睛里看玩家：offset 按世界坐标（指挥面朝北：−z 是他前方、+x 是他的右手边），看向他身上 lookY 高处。 */
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
            .ticks(4)
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

    protected static ScenarioBuilder restore(ScenarioBuilder s) {
        s.teardownServer("stop the orchestra, normal ticks", ctx -> {
            PodiumBlockEntity be = serverPodium(ctx.server().overworld());
            if (be != null) be.stop();
            ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(), "tick rate 20");
        });
        return s.teardown("my own eyes again", ctx -> {
            var mc = Minecraft.getInstance();
            mc.setScreen(null);
            if (mc.player != null) mc.setCameraEntity(mc.player);
            mc.options.hideGui = false;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            Conducting.testBeat = null;
            Conducting.testAmplitude = 1.0;
            Conducting.select(PODIUM, -1);
        });
    }

    /** 在服务端记下乐团此刻的位置。 */
    static ScenarioBuilder recordServerClock(ScenarioBuilder s) {
        return s.server("read the podium clock", ctx -> {
            PodiumBlockEntity be = serverPodium(ctx.server().overworld());
            serverPosition = be == null ? Integer.MIN_VALUE : be.positionMs();
            serverTempo = be == null ? 100 : be.tempo();
            serverNanos = System.nanoTime();
        });
    }

    /** 客户端的乐团时钟和服务端的差几毫秒（按服务端记下的那一刻往后推）。 */
    static int clockDrift() {
        Integer client = Conducting.position(PODIUM);
        if (client == null || serverPosition == Integer.MIN_VALUE) return Integer.MAX_VALUE;
        double expected = serverPosition + (System.nanoTime() - serverNanos) / 1_000_000.0 * serverTempo / 100.0;
        return (int) Math.abs(client - expected);
    }

    /** 此刻在响的成员件数。 */
    static int soundingMembers() {
        int n = 0;
        for (Seat seat : seats()) if (Conducting.sounding(PODIUM, seat.pos()) > 0) n++;
        return n;
    }

    private static net.minecraft.core.BlockPos restedMember;

    private static boolean partsAsExpected() {
        PodiumBlockEntity be = clientPodium();
        if (be == null) return false;
        for (Seat seat : seats()) if (be.mask(seat.pos()) != 1 << seat.part()) return false;
        return true;
    }

    /** 把手势定在某一拍，从乐团那边（前右方）和正侧面各拍一张。 */
    private static ScenarioBuilder pose(ScenarioBuilder s, String name, int beats, int unit, double beatMs, int beat, double phase) {
        s = s.step("pose " + name, ctx -> Conducting.testBeat = new BeatMap.Beat(0, beat, phase, beats, unit, beatMs));
        s = view(s, "orchestra_pose_" + name, 1.3, 0.55, -3.1, 1.45);
        return view(s, "orchestra_pose_" + name + "_side", 3.2, 0.35, -0.2, 1.4);
    }

    @LDLRegisterClient(name = "orchestra_conduct_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Conduct extends ConductingViews {
        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = stage(s, true, false)
                .server("score on the stand, gather nearby", ctx -> {
                    PodiumBlockEntity be = serverPodium(ctx.server().overworld());
                    be.setScore(punched(score()));
                    be.gather();
                })
                .waitUntil("the podium has twelve members on the client", ctx -> clientPodium() != null && clientPodium().members().size() == 12)
                .check("the stand shows the score", ctx -> Minecraft.getInstance().level.getBlockState(PODIUM).getValue(PodiumBlock.SCORE))
                .check("every instrument plays its own part (first violins on the left)", ctx -> partsAsExpected())
                .screenshot("orchestra_conduct_stage");
            b = view(b, "orchestra_podium_back", 0.4, 1.4, 4.2, 0.4);
            b = view(b, "orchestra_podium_front", -2.2, 0.9, -3.4, 0.2);
            // 从指挥的右后上方看谱架上摊开的总谱
            b = view(b, "orchestra_podium_score", 1.1, 2.3, 0.9, 0.6);
            b = b.openScreen("open the podium", ctx -> new PodiumScreen(PODIUM))
                .ticks(10)
                .screenshot("orchestra_podium_screen")
                // 声部列表最后一行「休息」：选中它再点乐器，那件乐器休息（锁定）；右键乐器解锁，重新分到声部
                .step("pick Rest, click the first member", ctx -> {
                    PodiumScreen screen = (PodiumScreen) Minecraft.getInstance().screen;
                    PodiumBlockEntity be = clientPodium();
                    screen.clickRow(false, ConductingViews.score().parts().size(), 0);
                    screen.clickRow(true, 0, 0);
                    restedMember = be.members().get(0);
                })
                .waitUntil("the first member rests, locked", ctx -> clientPodium().mask(restedMember) == 0 && clientPodium().locked(restedMember))
                .screenshot("orchestra_podium_screen_rest")
                .step("right-click it to unlock", ctx -> ((PodiumScreen) Minecraft.getInstance().screen).clickRow(true, 0, 1))
                .waitUntil("it plays its part again", ctx -> !clientPodium().locked(restedMember) && partsAsExpected())
                .step("select the flutes", ctx -> {
                    outlineBefore = PartOutline.passes();
                    Conducting.select(PODIUM, 4);
                })
                .ticks(6)
                .check("the outline pass runs while a part is selected", ctx -> PartOutline.passes() > outlineBefore)
                .screenshot("orchestra_podium_screen_flutes")
                .step("close the screen, no selection", ctx -> {
                    Minecraft.getInstance().setScreen(null);
                    Conducting.select(PODIUM, -1);
                })
                .ticks(2)
                // 右键空中（不潜行）换声部
                .step("look up and right-click with the baton", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.player.setXRot(-60f);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .check("the baton selects the first part", ctx -> Conducting.selectedPart(PODIUM) == 0)
                .step("right-click again", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .check("then the second", ctx -> Conducting.selectedPart(PODIUM) == 1)
                .step("no selection, look at the orchestra", ctx -> {
                    Conducting.select(PODIUM, -1);
                    Minecraft.getInstance().player.setXRot(12f);
                })
                // 指挥状态下左键：对着乐谱架的方块、对着站在台前的生物都不起作用
                .server("a villager in front of the podium", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    var v = EntityType.VILLAGER.create(level, EntitySpawnReason.COMMAND);
                    v.setNoAi(true);
                    v.setPos(PODIUM.getX() + 1.0, GROUND + 1, PODIUM.getZ() - 1.5);
                    level.addFreshEntity(v);
                    victim = v.getId();
                })
                .ticks(5)
                .step("left-click the villager", ctx -> {
                    var mc = Minecraft.getInstance();
                    var v = mc.level.getEntity(victim);
                    mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, v.position().add(0, 1.2, 0));
                    mc.options.keyAttack.setDown(true);
                    net.minecraft.client.KeyMapping.click(mc.options.keyAttack.getKey());
                })
                .ticks(3)
                .check("the arm does not even swing", ctx -> !Minecraft.getInstance().player.swinging)
                .step("let go", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .checkServer("the villager was not hit", ctx -> {
                    var v = ctx.server().overworld().getEntity(victim);
                    return v instanceof net.minecraft.world.entity.LivingEntity l && l.getHealth() == l.getMaxHealth() && l.hurtTime == 0;
                })
                .server("the villager leaves, attack a stand on the server directly", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    var v = level.getEntity(victim);
                    if (v != null) {
                        ctx.player().attack(v);
                        attackedHealthy = v instanceof net.minecraft.world.entity.LivingEntity l && l.getHealth() == l.getMaxHealth();
                        v.discard();
                    }
                    BlockPos stand = ORIGIN.offset(-1, 1, 3);
                    ctx.player().gameMode.handleBlockBreakAction(stand,
                        net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.SOUTH,
                        level.getMaxY(), 0);
                })
                .ticks(3)
                .checkServer("the server refuses the attack too", ctx -> attackedHealthy)
                .checkServer("and the music stand is still there", ctx ->
                    ctx.server().overworld().getBlockState(ORIGIN.offset(-1, 1, 3)).is(Instruments.MUSIC_STAND.get()))
                // 开始：指挥棒右键指挥台
                .server("start with the baton", ctx -> Orchestra.batonOnPodium(ctx.player().getMainHandItem(), ctx.server().overworld(), PODIUM, ctx.player()))
                .waitUntil("the ensemble starts on the client", ctx -> Conducting.position(PODIUM) != null)
                .check("the conductor raises the baton for the upbeat", ctx -> {
                    Integer pos = Conducting.position(PODIUM);
                    return pos != null && pos < 0 && Conducting.poseOf(Minecraft.getInstance().player) != null;
                })
                .ticks(50)
                .check("the orchestra plays together", ctx -> soundingMembers() >= 7)
                .screenshot("orchestra_conduct_first_person");
            b = recordServerClock(b)
                .check("client and server clocks agree", ctx -> clockDrift() < 80);
            b = view(b, "orchestra_conduct_front", 1.3, 0.55, -3.1, 1.45);
            b = b.waitUntil("the left hand cues the flutes before bar 5", ctx -> {
                    var pose = Conducting.poseOf(Minecraft.getInstance().player);
                    return pose != null && pose.cue() != null;
                })
                .check("the cue points at the flute stand", ctx -> {
                    var pose = Conducting.poseOf(Minecraft.getInstance().player);
                    return pose != null && pose.cue() != null && pose.cue().distanceTo(Vec3.atCenterOf(ORIGIN.offset(-2, 1, 0))) < 1.0;
                })
                .step("third person for the cue", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(1)
                .screenshot("orchestra_conduct_cue")
                .step("first person again", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
                // 选中声部的描边：看得见的定音鼓、被墙挡住的定音鼓、第一小提琴
                .step("select the timpani", ctx -> Conducting.select(PODIUM, 9))
                .ticks(3)
                .screenshot("orchestra_outline_timpani")
                .server("a wall in front of the timpani", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    for (int x = 1; x <= 3; x++) for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(ORIGIN.offset(x, y, -2), Blocks.STONE_BRICKS.defaultBlockState());
                })
                .ticks(6)
                .screenshot("orchestra_outline_timpani_hidden")
                .step("select the first violins", ctx -> Conducting.select(PODIUM, 0))
                .ticks(3)
                .screenshot("orchestra_outline_violins")
                .step("deselect", ctx -> Conducting.select(PODIUM, -1))
                .ticks(2)
                .step("count outline passes", ctx -> outlineBefore = PartOutline.passes())
                .ticks(10)
                .check("no outline pass without a selection", ctx -> PartOutline.passes() == outlineBefore)
                .server("take the wall down", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    for (int x = 1; x <= 3; x++) for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(ORIGIN.offset(x, y, -2), Blocks.AIR.defaultBlockState());
                })
                // 服务端卡到每秒 10 刻：时钟按真实时间走，客户端不跟着慢
                .runCommand("tick rate 10")
                .ticks(50);
            b = recordServerClock(b)
                .check("the clocks still agree while the server lags", ctx -> clockDrift() < 80)
                .check("and the orchestra still plays together", ctx -> soundingMembers() >= 7)
                .runCommand("tick rate 20")
                .waitUntil("the trumpet comes in at bar 9", ctx -> Conducting.sounding(PODIUM, ORIGIN.offset(-1, 1, -3)) > 0)
                .check("loud passages: the left hand mirrors the baton", ctx -> {
                    var pose = Conducting.poseOf(Minecraft.getInstance().player);
                    return pose != null && pose.amplitude() > 1.0;
                })
                .screenshot("orchestra_conduct_tutti")
                .waitUntil("the last note ends", ctx -> {
                    Integer pos = Conducting.position(PODIUM);
                    return pos != null && pos > BARS * 4 * quarterMs() + 100;
                })
                .check("the conductor closes with a cutoff", ctx -> Conducting.poseOf(Minecraft.getInstance().player) != null);
            b = view(b, "orchestra_conduct_cutoff", 1.3, 0.55, -3.1, 1.45);
            b = b.waitUntilServer("the podium stops by itself", ctx -> {
                    PodiumBlockEntity be = serverPodium(ctx.server().overworld());
                    return be != null && !be.isPlaying();
                })
                .ticks(5)
                .check("the arms come down", ctx -> Conducting.poseOf(Minecraft.getInstance().player) == null)
                .check("the members are released", ctx -> soundingMembers() == 0);
            // 四种拍子的手势：拍点（相位 0）与第一拍弹起的高点
            double q = 535;
            b = pose(b, "4_4_beat1", 4, 4, q, 0, 0);
            b = pose(b, "4_4_beat1_rebound", 4, 4, q, 0, 0.4);
            b = pose(b, "4_4_beat2", 4, 4, q, 1, 0);
            b = pose(b, "4_4_beat3", 4, 4, q, 2, 0);
            b = pose(b, "4_4_beat4", 4, 4, q, 3, 0);
            b = pose(b, "4_4_beat4_rebound", 4, 4, q, 3, 0.4);
            b = pose(b, "3_4_beat2", 3, 4, q, 1, 0);
            b = pose(b, "3_4_beat3", 3, 4, q, 2, 0);
            b = pose(b, "2_4_beat2", 2, 4, q, 1, 0);
            b = pose(b, "6_8_beat2", 6, 8, 250, 3, 0);
            b = b.step("loud", ctx -> Conducting.testAmplitude = 1.3);
            b = pose(b, "4_4_beat3_loud", 4, 4, q, 2, 0);
            b = b.step("first person, beat 3 rebound", ctx -> {
                    Conducting.testAmplitude = 1.0;
                    Conducting.testBeat = new BeatMap.Beat(0, 2, 0.4, 4, 4, q);
                })
                .ticks(2)
                .screenshot("orchestra_pose_first_person_rebound")
                .step("first person, downbeat", ctx -> Conducting.testBeat = new BeatMap.Beat(0, 0, 0, 4, 4, q))
                .ticks(2)
                .screenshot("orchestra_pose_first_person_downbeat");
            restore(b);
        }
    }

    /** 坐在大提琴后面的人（选项 A）：乐团演奏时琴按总谱响，演奏者的弓跟着拉。 */
    @LDLRegisterClient(name = "orchestra_seated_member_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class SeatedMember extends ConductingViews {
        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = stage(s, false, false)
                .server("score on the stand, gather nearby", ctx -> {
                    PodiumBlockEntity be = serverPodium(ctx.server().overworld());
                    be.setScore(punched(score()));
                    be.gather();
                })
                .waitUntil("twelve members", ctx -> clientPodium() != null && clientPodium().members().size() == 12)
                .server("walk up to the cello", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(CELLO.getX() + 0.5, GROUND + 1, CELLO.getZ() + 2.2), 180f, 10f))
                .ticks(10)
                .step("right-click the cello", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(CELLO).add(0, 0, 0.3), Direction.SOUTH, CELLO, false));
                })
                .waitUntil("seated at the cello", ctx -> Minecraft.getInstance().player.getVehicle() instanceof InstrumentSeat)
                .step("close the cello screen", ctx -> Minecraft.getInstance().setScreen(null))
                .server("the podium starts (redstone)", ctx -> ctx.server().overworld().setBlockAndUpdate(PODIUM.south(), Blocks.REDSTONE_BLOCK.defaultBlockState()))
                .waitUntil("the ensemble plays", ctx -> Conducting.position(PODIUM) != null && Conducting.position(PODIUM) > 300)
                .ticks(10)
                .check("the cello sounds its part", ctx -> Conducting.sounding(PODIUM, CELLO) > 0)
                .check("the seated player's bow follows", ctx -> VoiceBank.recentlyPlayed(CELLO));
            b = view(b, "orchestra_seated_member_side", 2.4, 1.0, 0.6, 0.9);
            b = view(b, "orchestra_seated_member_front", -1.2, 1.3, 2.6, 0.9);
            b = b.server("stand up, power off", ctx -> {
                ctx.player().stopRiding();
                ctx.server().overworld().setBlockAndUpdate(PODIUM.south(), Blocks.AIR.defaultBlockState());
            });
            restore(b);
        }
    }
}
