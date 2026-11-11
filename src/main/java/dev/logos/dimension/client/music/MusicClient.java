package dev.logos.dimension.client.music;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.MusicPayloads;
import dev.logos.dimension.music.MusicRoll;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 乐器的客户端：
 * <ul>
 *   <li>实时演奏：自己弹的音立刻在本地发声，同时攒成每 tick 一批发给服务端；别人弹的音统一延后 100 ms，
 *       按批内偏移排期播放，节奏和演奏者一致。</li>
 *   <li>纸带播放：服务端只告诉「哪卷纸带、此刻放到哪里」，这里按本地时钟逐个放音；纸带内容按 ID 缓存。</li>
 *   <li>编辑器试听、录制、上传纸带。</li>
 * </ul>
 * 每帧（不是每 tick）检查一次到点的音，时间精度跟帧率走。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class MusicClient {
    /** 听众端的抖动缓冲。 */
    private static final long LISTEN_DELAY_NS = 100_000_000L;
    /** 心跳和本地时钟差多少才修正。 */
    private static final long RESYNC_NS = 150_000_000L;
    /** 离乐器多远就不放声音（只推进进度）。 */
    private static final double AUDIBLE = 72.0;

    private MusicClient() {}

    // —— 入口 ——

    public static void openInstrument(BlockPos main) {
        Minecraft.getInstance().setScreen(new InstrumentScreen(main));
    }

    static @Nullable InstrumentKind kindAt(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof InstrumentBlock b ? b.kind() : null;
    }

    static Vec3 centerAt(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        if (level == null) return Vec3.atCenterOf(pos);
        // 管风琴的声音从音管墙的中心发出
        if (level.getBlockEntity(pos) instanceof dev.logos.dimension.music.InstrumentBlockEntity be) return be.voiceCenter();
        return InstrumentBlock.center(level.getBlockState(pos), pos);
    }

    /** 管风琴：预先合成当前音栓的组合（打开界面、开始放纸带时调用）。 */
    static void prewarmOrgan(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        if (level != null && level.getBlockEntity(pos) instanceof dev.logos.dimension.music.InstrumentBlockEntity be
                && be.kind() == InstrumentKind.PIPE_ORGAN) {
            OrganMixer.prewarm(be.effectiveStops());
        }
    }

    private static boolean audible(BlockPos pos) {
        var p = Minecraft.getInstance().player;
        return p != null && p.distanceToSqr(Vec3.atCenterOf(pos)) < AUDIBLE * AUDIBLE;
    }

    static String liveKey(BlockPos pos) {
        return "live:" + pos.asLong();
    }

    // —— 网络 ——

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(MusicPayloads.LiveNotesOut.TYPE, (p, c) -> c.enqueueWork(() -> heard(p)));
        event.register(MusicPayloads.Playback.TYPE, (p, c) -> c.enqueueWork(() -> playback(p)));
        event.register(MusicPayloads.RollData.TYPE, (p, c) -> c.enqueueWork(() -> rollData(p)));
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TRACKS.clear();
        SCHEDULE.clear();
        ROLLS.clear();
        REQUESTED.clear();
        MISSING.clear();
        preview = null;
        recorder = null;
        BATCH.clear();
        VoiceBank.clear();
        OrganMixer.clear();
        OrganRoom.clear();
    }

    // —— 定时 ——

    private record Timed(long at, long seq, Runnable run) {}

    private static long seq;
    private static final PriorityQueue<Timed> SCHEDULE = new PriorityQueue<>((a, b) ->
        a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.seq, b.seq));

    private static void at(long nanos, Runnable run) {
        SCHEDULE.add(new Timed(nanos, seq++, run));
    }

    @SubscribeEvent
    static void frame(RenderFrameEvent.Pre event) {
        MidiKeyboard.drain();
        long now = System.nanoTime();
        while (!SCHEDULE.isEmpty() && SCHEDULE.peek().at <= now) SCHEDULE.poll().run.run();
        for (Track t : new ArrayList<>(TRACKS.values())) {
            t.advance(now);
            // 走远时收不到停止包：放完之后自己收掉，走回来时服务端会补发状态
            if (t.playing && t.roll != null && t.positionMs(now) > t.roll.durationMs() + 3000) {
                VoiceBank.allOff(t.key);
                TRACKS.remove(t.pos);
            }
        }
        if (preview != null) preview.advance(now);
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        flushLive();
        VoiceBank.tickOrgans();
    }

    // —— 实时演奏：发送 ——

    private static @Nullable BlockPos batchPos;
    private static long batchStart;
    private static final List<MusicPayloads.LiveNote> BATCH = new ArrayList<>();

    private static void queueLive(BlockPos pos, int pitch, int velocity, long now) {
        if (batchPos != null && !batchPos.equals(pos)) flushLive();
        if (BATCH.isEmpty()) {
            batchPos = pos;
            batchStart = now;
        }
        BATCH.add(new MusicPayloads.LiveNote((int) Math.max(0, Math.min(1000, (now - batchStart) / 1_000_000L)), pitch, velocity));
        if (BATCH.size() >= MusicPayloads.MAX_BATCH) flushLive();
    }

    private static void flushLive() {
        if (BATCH.isEmpty() || batchPos == null) return;
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPacketDistributor.sendToServer(new MusicPayloads.LiveNotes(batchPos, List.copyOf(BATCH)));
        }
        BATCH.clear();
    }

    /** 演奏界面里弹下（velocity &gt; 0）或松开（velocity 0）一个音：本地立刻发声并转给服务端。 */
    static void localNote(BlockPos pos, int pitch, int velocity) {
        localNote(pos, pitch, velocity, System.nanoTime());
    }

    /** nanos 是这个音真正按下的时刻（电子琴的消息比这一帧早到一点），录制和批内偏移按它算。 */
    static void localNote(BlockPos pos, int pitch, int velocity, long nanos) {
        InstrumentKind kind = kindAt(pos);
        if (kind == null) return;
        if (velocity > 0) VoiceBank.noteOn(liveKey(pos), kind, pos, centerAt(pos), pitch, velocity, 0);
        else VoiceBank.noteOff(liveKey(pos), pitch, 0);
        queueLive(pos, pitch, velocity, nanos);
        if (recorder != null) recorder.note(pitch, velocity, nanos);
    }

    static void localPedal(BlockPos pos, boolean down) {
        localPedal(pos, down, System.nanoTime());
    }

    static void localPedal(BlockPos pos, boolean down, long nanos) {
        InstrumentKind kind = kindAt(pos);
        if (kind == null) return;
        VoiceBank.pedal(liveKey(pos), kind, pos, centerAt(pos), 0, down);
        queueLive(pos, MusicPayloads.PEDAL, down ? 127 : 0, nanos);
        if (recorder != null) recorder.pedal(down, nanos);
    }

    // —— 实时演奏：收听 ——

    private static void heard(MusicPayloads.LiveNotesOut p) {
        String key = liveKey(p.pos());
        if (p.allOff()) {
            VoiceBank.allOff(key);
            return;
        }
        InstrumentKind kind = kindAt(p.pos());
        if (kind == null || !audible(p.pos())) return;
        Vec3 at = centerAt(p.pos());
        BlockPos where = p.pos().immutable();
        long base = System.nanoTime() + LISTEN_DELAY_NS;
        for (MusicPayloads.LiveNote n : p.notes()) {
            long when = base + n.offsetMs() * 1_000_000L;
            if (n.pitch() == MusicPayloads.PEDAL) {
                at(when, () -> VoiceBank.pedal(key, kind, where, at, 0, n.velocity() > 0));
            } else if (n.velocity() > 0) {
                at(when, () -> VoiceBank.noteOn(key, kind, where, at, n.pitch(), n.velocity(), 0));
            } else {
                at(when, () -> VoiceBank.noteOff(key, n.pitch(), 0));
            }
        }
    }

    // —— 纸带缓存 ——

    private static final Map<UUID, MusicRoll> ROLLS = new HashMap<>();
    private static final Set<UUID> REQUESTED = new HashSet<>();
    private static final Set<UUID> MISSING = new HashSet<>();

    /** 取纸带内容；还没有就向服务端要，返回 null。 */
    static @Nullable MusicRoll roll(UUID id) {
        MusicRoll r = ROLLS.get(id);
        if (r == null && !MISSING.contains(id) && REQUESTED.add(id)) {
            ClientPacketDistributor.sendToServer(new MusicPayloads.RollRequest(id));
        }
        return r;
    }

    static boolean missing(UUID id) {
        return MISSING.contains(id);
    }

    private static void rollData(MusicPayloads.RollData p) {
        REQUESTED.remove(p.id());
        if (p.data().length == 0) {
            MISSING.add(p.id());
            return;
        }
        try {
            ROLLS.put(p.id(), MusicRoll.decode(p.data()));
        } catch (MusicRoll.FormatException e) {
            MISSING.add(p.id());
        }
    }

    // —— 纸带播放 ——

    /** 一路按本地时钟播放的纸带（服务端同步的播放，或编辑器的本地试听）。 */
    static final class Track {
        final String key;
        final BlockPos pos;
        @Nullable UUID rollId;
        @Nullable MusicRoll roll;
        boolean playing;
        long anchor;
        int pausedAt;
        int partMask = 0xFFFF;
        int cursor;
        boolean needSeek = true;
        final PriorityQueue<long[]> offs = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));

        Track(String key, BlockPos pos) {
            this.key = key;
            this.pos = pos;
        }

        int positionMs(long now) {
            return playing ? (int) Math.max(0, (now - anchor) / 1_000_000L) : pausedAt;
        }

        void seek(int ms) {
            VoiceBank.allOff(key);
            offs.clear();
            cursor = 0;
            if (roll == null) return;
            var ev = roll.events();
            int lo = 0, hi = ev.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ev.get(mid).timeMs() < ms - 20) lo = mid + 1; else hi = mid;
            }
            cursor = lo;
            needSeek = false;
        }

        void advance(long now) {
            while (!offs.isEmpty() && offs.peek()[0] <= now) {
                long[] o = offs.poll();
                VoiceBank.noteOff(key, (int) o[2], (int) o[1]);
            }
            if (!playing) return;
            if (roll == null) {
                if (rollId != null) roll = MusicClient.roll(rollId);
                if (roll == null) return;
            }
            int pos = positionMs(now);
            if (needSeek) seek(pos);
            InstrumentKind kind = kindAt(this.pos);
            if (kind == null) return;
            boolean hear = audible(this.pos);
            Vec3 at = centerAt(this.pos);
            var ev = roll.events();
            while (cursor < ev.size() && ev.get(cursor).timeMs() <= pos) {
                MusicRoll.Event e = ev.get(cursor++);
                if ((partMask & (1 << e.part())) == 0 || !hear) continue;
                if (e.type() == MusicRoll.NOTE) {
                    VoiceBank.noteOn(key, kind, this.pos, at, e.pitch(), e.velocity(), e.part());
                    offs.add(new long[]{anchor + e.endMs() * 1_000_000L, e.part(), e.pitch()});
                } else {
                    VoiceBank.pedal(key, kind, this.pos, at, e.part(), e.type() == MusicRoll.PEDAL_DOWN);
                }
            }
        }
    }

    private static final Map<BlockPos, Track> TRACKS = new HashMap<>();
    private static @Nullable Track preview;

    private static void playback(MusicPayloads.Playback p) {
        BlockPos pos = p.pos().immutable();
        if (p.roll() == null) {
            Track old = TRACKS.remove(pos);
            if (old != null) VoiceBank.allOff(old.key);
            return;
        }
        long now = System.nanoTime();
        Track t = TRACKS.get(pos);
        if (t == null || !p.roll().equals(t.rollId)) {
            if (t != null) VoiceBank.allOff(t.key);
            t = new Track("roll:" + pos.asLong(), pos);
            t.rollId = p.roll();
            t.roll = roll(p.roll());
            TRACKS.put(pos, t);
            prewarmOrgan(pos);
            t.playing = false;
        }
        t.partMask = p.partMask();
        if (p.playing()) {
            long expected = now - p.positionMs() * 1_000_000L;
            if (!t.playing || Math.abs(expected - t.anchor) > RESYNC_NS) {
                t.anchor = expected;
                t.playing = true;
                t.needSeek = true;
            }
        } else {
            t.playing = false;
            t.pausedAt = p.positionMs();
            VoiceBank.allOff(t.key);
            t.offs.clear();
            t.needSeek = true;
        }
    }

    /** 界面显示用：这件乐器的纸带播放状态（没有在放时为 null）。 */
    static @Nullable Track track(BlockPos pos) {
        return TRACKS.get(pos);
    }

    // —— 编辑器试听 ——

    static void preview(BlockPos pos, MusicRoll roll, int fromMs) {
        stopPreview();
        Track t = new Track("preview", pos);
        t.roll = roll;
        t.playing = true;
        t.anchor = System.nanoTime() - fromMs * 1_000_000L;
        preview = t;
    }

    static void stopPreview() {
        if (preview != null) VoiceBank.allOff(preview.key);
        preview = null;
    }

    /** 试听的当前位置（毫秒），没有试听时 -1。 */
    static int previewPosition() {
        Track t = preview;
        if (t == null || t.roll == null) return -1;
        int pos = t.positionMs(System.nanoTime());
        if (pos > t.roll.durationMs() + 1500) {
            stopPreview();
            return -1;
        }
        return pos;
    }

    /** 编辑器左侧键盘点一下试听一个音。 */
    static void audition(BlockPos pos, int pitch) {
        InstrumentKind kind = kindAt(pos);
        if (kind == null) return;
        VoiceBank.noteOn("audition", kind, pos, centerAt(pos), pitch, 90, 0);
        at(System.nanoTime() + 400_000_000L, () -> VoiceBank.noteOff("audition", pitch, 0));
    }

    // —— 录制 ——

    static @Nullable Recorder recorder;

    /** 在演奏者自己的客户端上按本地毫秒时间戳录下弹的音，停止时整理成一卷纸带。 */
    static final class Recorder {
        private final long start = System.nanoTime();
        private final Map<Integer, int[]> open = new HashMap<>();
        private final List<MusicRoll.Event> events = new ArrayList<>();
        private boolean pedal;

        private int now() {
            return at(System.nanoTime());
        }

        private int at(long nanos) {
            return (int) Math.max(0, Math.min(MusicRoll.MAX_DURATION_MS, (nanos - start) / 1_000_000L));
        }

        int elapsedMs() {
            return now();
        }

        int count() {
            return events.size() + open.size();
        }

        void note(int pitch, int velocity) {
            note(pitch, velocity, System.nanoTime());
        }

        void note(int pitch, int velocity, long nanos) {
            int t = at(nanos);
            int[] o = open.remove(pitch);
            if (o != null) events.add(MusicRoll.Event.note(o[0], 0, pitch, o[1], Math.max(1, Math.min(MusicRoll.MAX_NOTE_MS, t - o[0]))));
            if (velocity > 0 && events.size() < MusicRoll.MAX_EVENTS) open.put(pitch, new int[]{t, velocity});
        }

        void pedal(boolean down) {
            pedal(down, System.nanoTime());
        }

        void pedal(boolean down, long nanos) {
            if (down == pedal || events.size() >= MusicRoll.MAX_EVENTS) return;
            pedal = down;
            events.add(MusicRoll.Event.pedal(at(nanos), 0, down));
        }

        /** 测试用：录下的事件（按开始时间排好）。 */
        List<MusicRoll.Event> events() {
            return new MusicRoll("", "", 12000, 4, 4, List.of(), events).events();
        }

        MusicRoll finish(String partName) {
            for (Integer p : new ArrayList<>(open.keySet())) note(p, 0);
            if (pedal) {
                pedal = false;
                events.add(MusicRoll.Event.pedal(now(), 0, false));
            }
            // 去掉开头的空白：从第一个事件开始
            int first = events.stream().mapToInt(MusicRoll.Event::timeMs).min().orElse(0);
            List<MusicRoll.Event> shifted = new ArrayList<>(events.size());
            for (MusicRoll.Event e : events) {
                shifted.add(new MusicRoll.Event(e.timeMs() - first, e.type(), e.part(), e.pitch(), e.velocity(), e.durationMs()));
            }
            return new MusicRoll("", "", 12000, 4, 4, List.of(new MusicRoll.Part(partName, 0)), shifted);
        }
    }

    // —— 上传 ——

    /** 把编码好的纸带分块发给服务端。purpose 见 {@link MusicPayloads.Upload}。 */
    static void upload(BlockPos pos, int purpose, byte[] encoded) {
        int id = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        int chunk = MusicPayloads.UPLOAD_CHUNK;
        int total = Math.max(1, (encoded.length + chunk - 1) / chunk);
        for (int i = 0; i < total; i++) {
            int from = i * chunk;
            int to = Math.min(encoded.length, from + chunk);
            byte[] part = java.util.Arrays.copyOfRange(encoded, from, to);
            ClientPacketDistributor.sendToServer(new MusicPayloads.Upload(id, i, total, purpose, pos, part));
        }
    }

    static void control(BlockPos pos, int action, int arg) {
        ClientPacketDistributor.sendToServer(new MusicPayloads.Control(pos, action, arg));
    }
}
