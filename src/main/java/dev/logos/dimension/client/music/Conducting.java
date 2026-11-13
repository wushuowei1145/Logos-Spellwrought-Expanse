package dev.logos.dimension.client.music;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.orchestra.BeatMap;
import dev.logos.dimension.music.orchestra.ConductorPattern;
import dev.logos.dimension.music.orchestra.EnsemblePayloads;
import dev.logos.dimension.music.orchestra.Orchestra;
import dev.logos.dimension.music.orchestra.PodiumBlock;
import dev.logos.dimension.music.orchestra.PodiumBlockEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 客户端的乐团（docs/ORCHESTRA.md 第 6–8 节）：收服务端的乐团状态，让每件成员乐器的纸带播放（{@link MusicClient.Track}）
 * 共用指挥台的时钟、只放分给它的声部、按声部的响度 / 静音 / 独奏；算指挥此刻的手势（拍子、强弱、提示、预备拍、收束）；
 * 记着指挥选中的声部（画面里描边，只有自己看得到）。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class Conducting {
    /** 16 个声部的颜色：魔女之森的配色，互不相同（界面的色块、选中时的描边同色）。 */
    public static final int[] PART_COLORS = {
        0xFF3B5BDB, 0xFFE8C15A, 0xFFB46FE0, 0xFFE0567A, 0xFF3FA66B, 0xFF4CC3E0, 0xFFF08C3A, 0xFFF2EAD0,
        0xFF8F7BD8, 0xFFFF8A80, 0xFF2BB3A3, 0xFFB0283F, 0xFF9BAA3C, 0xFF7FA3C9, 0xFFF5B79A, 0xFF8EE0B5,
    };
    private static final long RESYNC_NS = 150_000_000L, JUMP_MS = 3000;
    /** 晚于最后一个音这么久之内画收束。 */
    static final int CUTOFF_MS = 1200;

    private Conducting() {}

    /** 一个乐团（按指挥台）。 */
    static final class Ensemble {
        final BlockPos podium;
        UUID rollId;
        @Nullable MusicRoll roll;
        @Nullable BeatMap beats;
        float @Nullable [] envelope;
        final List<List<int[]>> entries = new ArrayList<>();
        /** 各声部每个音开始的时刻（排好序；描边跟着亮一下）。 */
        int @Nullable [][] onsets;
        final MusicClient.Clock clock = new MusicClient.Clock();
        int durationMs;
        int tempo = 100;
        final Map<BlockPos, Integer> masks = new HashMap<>();
        final Map<BlockPos, MusicClient.Track> tracks = new HashMap<>();
        final float[] gains = new float[MusicRoll.MAX_PARTS];
        int muteMask, soloMask;

        Ensemble(BlockPos podium) {
            this.podium = podium;
        }

        String key(BlockPos member) {
            return "ens:" + podium.asLong() + ":" + member.asLong();
        }

        int effective(int mask) {
            int m = mask & ~muteMask;
            return soloMask != 0 ? m & soloMask : m;
        }
    }

    private static final Map<BlockPos, Ensemble> ENSEMBLES = new HashMap<>();
    private static @Nullable BlockPos selectedPodium;
    private static int selectedPart = -1;

    // —— 网络 ——

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(EnsemblePayloads.EnsembleState.TYPE, (p, c) -> c.enqueueWork(() -> state(p)));
    }

    static void state(EnsemblePayloads.EnsembleState p) {
        BlockPos podium = p.podium().immutable();
        Ensemble e = ENSEMBLES.get(podium);
        if (!p.playing()) {
            if (e != null) stop(e);
            ENSEMBLES.remove(podium);
            return;
        }
        long now = System.nanoTime();
        if (e == null || !p.roll().equals(e.rollId)) {
            if (e != null) stop(e);
            e = new Ensemble(podium);
            e.rollId = p.roll();
            e.clock.set(p.positionMs(), p.tempo() / 100.0, now);
            ENSEMBLES.put(podium, e);
        } else {
            int drift = p.positionMs() - e.clock.position(now);
            boolean jump = Math.abs(drift) > JUMP_MS;
            if (p.tempo() != e.tempo || Math.abs(drift) * 1_000_000L > RESYNC_NS) e.clock.set(p.positionMs(), p.tempo() / 100.0, now);
            if (jump) for (MusicClient.Track t : e.tracks.values()) t.needSeek = true;
        }
        e.tempo = p.tempo();
        e.durationMs = p.durationMs();
        for (int i = 0; i < e.gains.length; i++) e.gains[i] = i < p.gains().length ? (float) Math.pow(10, p.gains()[i] / 20.0) : 1f;
        e.muteMask = p.muteMask();
        e.soloMask = p.soloMask();
        e.masks.clear();
        for (EnsemblePayloads.MemberPart m : p.members()) e.masks.put(m.pos().immutable(), m.mask());
        // 成员：没有的建一路播放，退出的止音
        for (BlockPos gone : new ArrayList<>(e.tracks.keySet())) {
            if (!e.masks.containsKey(gone)) VoiceBank.allOff(e.tracks.remove(gone).key);
        }
        for (var m : e.masks.entrySet()) {
            MusicClient.Track t = e.tracks.get(m.getKey());
            if (t == null) {
                t = new MusicClient.Track(e.key(m.getKey()), m.getKey());
                t.rollId = e.rollId;
                t.ensemble = true;
                t.shared = e.clock;
                t.playing = true;
                e.tracks.put(m.getKey(), t);
            }
            int mask = e.effective(m.getValue());
            if (mask != t.partMask) {
                t.partMask = mask;
                t.shiftsFor = null;   // 声部变了：重新预读
            }
            t.gains = e.gains;
        }
    }

    private static void stop(Ensemble e) {
        for (MusicClient.Track t : e.tracks.values()) VoiceBank.allOff(t.key);
        e.tracks.clear();
    }

    @SubscribeEvent
    static void frame(RenderFrameEvent.Pre event) {
        if (ENSEMBLES.isEmpty()) return;
        long now = System.nanoTime();
        for (Ensemble e : new ArrayList<>(ENSEMBLES.values())) {
            if (e.roll == null) {
                MusicRoll raw = MusicClient.roll(e.rollId);
                // 合奏用的总谱（钢琴曲另有拆出的旋律、和声、低音），声部编号同服务端分派的
                e.roll = raw == null ? null : dev.logos.dimension.music.orchestra.OrchestraScore.of(raw).roll();
                if (e.roll != null) analyse(e);
            }
            for (MusicClient.Track t : e.tracks.values()) t.advance(now);
            // 放完了收不到停止包（走远了）：自己收掉
            if (e.clock.position(now) > e.durationMs + 5000) {
                stop(e);
                ENSEMBLES.remove(e.podium);
            }
        }
    }

    /** 纸带到了：拍子表、响度包络（指挥的幅度）、各声部休息后再进来的时刻（左手提示）。 */
    private static void analyse(Ensemble e) {
        MusicRoll roll = e.roll;
        e.beats = BeatMap.of(roll);
        int slots = Math.max(1, roll.durationMs() / 100 + 2);
        float[] env = new float[slots];
        List<List<MusicRoll.Event>> byPart = new ArrayList<>();
        for (int i = 0; i < MusicRoll.MAX_PARTS; i++) byPart.add(new ArrayList<>());
        for (MusicRoll.Event ev : roll.events()) {
            if (ev.type() != MusicRoll.NOTE) continue;
            byPart.get(ev.part()).add(ev);
            int from = Math.max(0, ev.timeMs() / 100), to = Math.min(slots - 1, ev.endMs() / 100);
            for (int s = from; s <= to; s++) env[s] += ev.velocity() / 127f * (s == from ? 1.5f : 1f);
        }
        float[] sorted = env.clone();
        java.util.Arrays.sort(sorted);
        float top = Math.max(1f, sorted[(int) (sorted.length * 0.95)]);
        for (int s = 0; s < slots; s++) env[s] = Math.min(1.2f, env[s] / top);
        e.envelope = env;
        e.onsets = new int[MusicRoll.MAX_PARTS][];
        for (int part = 0; part < MusicRoll.MAX_PARTS; part++) {
            List<MusicRoll.Event> notes = byPart.get(part);
            int[] on = new int[notes.size()];
            for (int i = 0; i < on.length; i++) on[i] = notes.get(i).timeMs();
            java.util.Arrays.sort(on);
            e.onsets[part] = on;
        }
        e.entries.clear();
        for (List<MusicRoll.Event> part : byPart) {
            List<int[]> list = new ArrayList<>();
            // 开头就有的音不算「进来」：要休息两小节以上（含从头休息到这里）
            int lastEnd = 0;
            for (MusicRoll.Event ev : part) {
                BeatMap.Beat b = e.beats.at(ev.timeMs());
                double bar = b.beatMs() * b.beatsPerBar();
                if (ev.timeMs() - lastEnd >= 2 * bar - 1) list.add(new int[]{ev.timeMs()});
                lastEnd = Math.max(lastEnd, ev.endMs());
            }
            e.entries.add(list);
        }
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ENSEMBLES.clear();
        selectedPodium = null;
        selectedPart = -1;
    }

    /** 声音引擎重启：成员的采样重新预读。 */
    static void engineReloaded() {
        for (Ensemble e : ENSEMBLES.values()) for (MusicClient.Track t : e.tracks.values()) t.shiftsFor = null;
    }

    // —— 界面、选中的声部 ——

    public static void openPodium(BlockPos main) {
        Minecraft.getInstance().setScreen(new PodiumScreen(main));
    }

    static void select(BlockPos podium, int part) {
        selectedPodium = podium.immutable();
        selectedPart = part;
    }

    static int selectedPart(BlockPos podium) {
        return podium.equals(selectedPodium) ? selectedPart : -1;
    }

    /** 选中的声部与它的颜色、指挥台（描边用）；没选中时为 null。 */
    static @Nullable Selection selection() {
        if (selectedPodium == null || selectedPart < 0) return null;
        var level = Minecraft.getInstance().level;
        if (level == null || !(level.getBlockEntity(selectedPodium) instanceof PodiumBlockEntity be)) return null;
        List<BlockPos> members = new ArrayList<>();
        for (BlockPos m : be.members()) if ((be.mask(m) & (1 << selectedPart)) != 0) members.add(m);
        Ensemble e = ENSEMBLES.get(selectedPodium);
        return new Selection(selectedPodium, selectedPart, PART_COLORS[selectedPart], members, e);
    }

    record Selection(BlockPos podium, int part, int color, List<BlockPos> members, @Nullable Ensemble ensemble) {}

    /** 选中的声部刚有音进来时为 1，随后很快暗下去；没在演奏、静音时为 0。 */
    static float pulse(Selection s) {
        Ensemble e = s.ensemble();
        if (e == null || e.onsets == null || e.effective(1 << s.part()) == 0) return 0;
        int pos = e.clock.position(System.nanoTime());
        int[] on = e.onsets[s.part()];
        int i = java.util.Arrays.binarySearch(on, pos);
        if (i < 0) i = -i - 2;
        if (i < 0) return 0;
        return (float) Math.exp(-(pos - on[i]) / 160.0);
    }

    /** 指挥棒右键空中（或右键不是乐器、指挥台的方块）：换到下一个有人演奏的声部（再往后是不选）。 */
    public static void cycleSelection() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        var bound = mc.player.getMainHandItem().get(Orchestra.BATON_PODIUM.get());
        if (bound == null) bound = mc.player.getOffhandItem().get(Orchestra.BATON_PODIUM.get());
        if (bound == null || !(mc.level.getBlockEntity(bound.pos()) instanceof PodiumBlockEntity be)) return;
        int used = 0;
        for (BlockPos m : be.members()) used |= be.mask(m);
        int cur = selectedPart(bound.pos());
        for (int step = 1; step <= MusicRoll.MAX_PARTS + 1; step++) {
            int next = cur + step;
            if (next >= MusicRoll.MAX_PARTS) {
                select(bound.pos(), -1);
                mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.translatable("message.logos_dimension.orchestra.selected_none"), false);
                return;
            }
            if ((used & (1 << next)) != 0) {
                select(bound.pos(), next);
                mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.translatable("message.logos_dimension.orchestra.selected",
                    PodiumScreen.partName(be, next)), false);
                return;
            }
        }
    }

    // —— 指挥的手势 ——

    /**
     * 指挥此刻的样子。tip 是棒尖在拍子平面上的位置（{@link ConductorPattern}），amplitude 是幅度（按响度），
     * cue 不为空时是左手要指向的方向（世界坐标的水平方向），blend 是从站姿过渡到指挥的程度（预备拍开头、收束之后为 0–1）。
     */
    public record Pose(double[] tip, double amplitude, @Nullable Vec3 cue, double blend, boolean mirrorLeft) {}

    /** 测试用：站在指挥台上拿着指挥棒的人的手势定在这一拍（不管乐团在不在演奏、时钟走到哪），幅度 testAmplitude；为 null 时照常。 */
    static BeatMap.@Nullable Beat testBeat;
    static double testAmplitude = 1.0;

    /** 站在指挥台上、拿着指挥棒、乐团在演奏的玩家：此刻的手势；不是在指挥时为 null。 */
    public static @Nullable Pose poseOf(Entity entity) {
        if (!(entity instanceof Player player)) return null;
        if (!player.getMainHandItem().is(Orchestra.BATON.get())) return null;
        BlockPos podium = PodiumBlock.under(player);
        if (podium == null) return null;
        if (testBeat != null) {
            return new Pose(ConductorPattern.tip(ConductorPattern.conduct(testBeat)), testAmplitude, null, 1, testAmplitude > 1.05);
        }
        Ensemble e = ENSEMBLES.get(podium);
        if (e == null || e.beats == null) return null;
        long now = System.nanoTime();
        int pos = e.clock.position(now);
        if (pos > e.durationMs + CUTOFF_MS + 1500) return null;
        if (pos > e.durationMs) {
            double t = (pos - e.durationMs) / (double) CUTOFF_MS;
            double blend = t <= 1 ? 1 : Math.max(0, 1 - (t - 1) * CUTOFF_MS / 1500.0);
            return new Pose(ConductorPattern.cutoff(Math.min(1, t)), 0.9, null, blend, false);
        }
        BeatMap.Beat b = e.beats.at(pos);
        ConductorPattern.Conducted c = ConductorPattern.conduct(b);
        double[] tip = ConductorPattern.tip(c);
        // 预备拍：从站姿抬起来
        double blend = pos >= 0 ? 1 : Math.min(1, Math.max(0, 1 + pos / Math.max(1, c.beatMs())) / 0.4);
        double amp = 0.75;
        if (e.envelope != null) {
            int slot = Math.max(0, Math.min(e.envelope.length - 1, (int) ((pos + 150) / 100)));
            amp = 0.55 + 0.7 * e.envelope[slot];
        }
        return new Pose(tip, amp, cue(e, pos, c.beatMs()), blend, amp > 1.05);
    }

    /** 接下来一拍之内有声部休息了两小节以上要进来：指向演奏它的乐器的中心。 */
    private static @Nullable Vec3 cue(Ensemble e, int pos, double beatMs) {
        int best = -1, bestTime = Integer.MAX_VALUE;
        for (int part = 0; part < e.entries.size(); part++) {
            if ((e.effective(0xFFFF) & (1 << part)) == 0) continue;
            for (int[] entry : e.entries.get(part)) {
                if (entry[0] > pos && entry[0] - pos <= beatMs && entry[0] < bestTime) {
                    best = part;
                    bestTime = entry[0];
                }
            }
        }
        if (best < 0) return null;
        Vec3 sum = Vec3.ZERO;
        int n = 0;
        var level = Minecraft.getInstance().level;
        for (var m : e.masks.entrySet()) {
            if ((m.getValue() & (1 << best)) == 0) continue;
            BlockState state = level == null ? null : level.getBlockState(m.getKey());
            sum = sum.add(state != null && state.getBlock() instanceof dev.logos.dimension.music.InstrumentBlock
                    ? dev.logos.dimension.music.InstrumentBlock.center(state, m.getKey()) : Vec3.atCenterOf(m.getKey()));
            n++;
        }
        return n == 0 ? null : sum.scale(1.0 / n);
    }

    /** 指挥状态下左键什么都不做：不挥手、不攻击、不挖方块（服务端 {@link Orchestra} 也拦一道）。 */
    @SubscribeEvent
    static void input(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        if (event.isAttack() && mc.player != null && PodiumBlock.conducting(mc.player)) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** 测试用：这座指挥台的乐团此刻的位置；没在演奏时为 null。 */
    static @Nullable Integer position(BlockPos podium) {
        Ensemble e = ENSEMBLES.get(podium);
        return e == null ? null : e.clock.position(System.nanoTime());
    }

    /** 测试用：成员乐器在这个乐团里此刻在响的音数。 */
    static int sounding(BlockPos podium, BlockPos member) {
        Ensemble e = ENSEMBLES.get(podium);
        return e == null ? 0 : VoiceBank.sounding(e.key(member));
    }

    /** 测试、界面用：成员的乐器种类。 */
    static @Nullable InstrumentKind kindOf(BlockPos member) {
        return MusicClient.kindAt(member);
    }
}
