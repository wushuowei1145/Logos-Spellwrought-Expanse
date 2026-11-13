package dev.logos.dimension.client.music;

import com.mojang.blaze3d.audio.SoundBuffer;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.organ.OrganStop;
import dev.logos.dimension.music.organ.OrganSynth;
import dev.logos.dimension.music.organ.RoomAcoustics;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 管风琴的混音：一套音栓组合 + 一种房间 → 25 个采样（{@link OrganSynth}：起音段 + 无缝循环段，混响已在里面，另有松键尾音）。
 * 25 个音分别交给后台线程并行合成，全好之后统一缩放（{@link OrganSynth#finish}）。
 *
 * <p>声音引擎经 {@code OrganBufferMixin} 按名字取数据：{@code organ_runtime/g<代>/l<序号>} 是整个采样（循环点设在循环段上，
 * 起音放一遍、之后在循环段里一直响）；{@code c<序号>} 只有循环段（换音栓、换房间时已按住的音交叉淡入用，不再起一次音）；
 * {@code t<序号>} 是松键尾音。缓冲第一次用到时才做。
 *
 * <p>房间（混响档位或自动估计，按 {@link RoomAcoustics#bucket} 分档）是组合的一部分：房间变了也合成一套新的。
 * 最多留 {@link #KEEP} 套；正在发声的组合不清掉。
 */
public final class OrganMixer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "sounds/organ_runtime/";
    private static final int KEEP = 4;
    private static final AudioFormat FORMAT = new AudioFormat(OrganSynth.RATE, 16, 1, true,
        ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);

    private OrganMixer() {}

    /** 组合的身份：音栓掩码 + 房间档（0 为没有混响）。 */
    record Key(int mask, int room) {
        RoomAcoustics.Reverb reverb() {
            return RoomAcoustics.fromBucket(room);
        }
    }

    /** 这台管风琴此刻该用的组合。 */
    static Key key(InstrumentBlockEntity be) {
        return new Key(be.effectiveStops(), RoomAcoustics.bucket(OrganRoom.reverb(be.getBlockPos(), be)));
    }

    /** 一套合成好的组合。 */
    static final class Mix {
        final Key key;
        final int gen;
        final OrganSynth.Sample[] samples;
        final float loudness;
        final Map<String, SoundBuffer> buffers = new HashMap<>();

        Mix(Key key, int gen, OrganSynth.Sample[] samples) {
            this.key = key;
            this.gen = gen;
            this.samples = samples;
            this.loudness = (float) OrganStop.loudness(key.mask());
        }

        OrganSynth.Sample sample(int pitch) {
            return samples[OrganSynth.index(pitch)];
        }

        /** 整个采样：起音 + 循环。 */
        Identifier note(int pitch) {
            return id("l", pitch);
        }

        /** 只有循环段：交叉淡入用。 */
        Identifier steady(int pitch) {
            return id("c", pitch);
        }

        /** 松键尾音；这套没有房间时为 null。 */
        @Nullable Identifier tail(int pitch) {
            return sample(pitch).hasTail() ? id("t", pitch) : null;
        }

        private Identifier id(String kind, int pitch) {
            return LogosDimension.id("organ_runtime/g" + gen + "/" + kind + OrganSynth.index(pitch));
        }
    }

    private static int nextGen = 1;
    private static final Map<Key, Mix> READY = new LinkedHashMap<>(8, 0.75f, true);
    private static final Map<Integer, Mix> BY_GEN = new HashMap<>();
    private static final Map<Key, CompletableFuture<OrganSynth.Sample[]>> PENDING = new HashMap<>();
    private static @Nullable Mix last;

    /** 后台开始合成这台管风琴当前的组合（已经有或正在合成时什么都不做）。 */
    public static void prewarm(InstrumentBlockEntity be) {
        prewarm(key(be));
    }

    private static void prewarm(Key key) {
        if (READY.containsKey(key) || PENDING.containsKey(key)) return;
        RoomAcoustics.Reverb room = key.reverb();
        List<CompletableFuture<OrganSynth.Raw>> notes = new ArrayList<>();
        for (int i = 0; i < OrganSynth.COUNT; i++) {
            int note = OrganSynth.note(i);
            notes.add(CompletableFuture.supplyAsync(() -> OrganSynth.raw(note, key.mask(), room), Util.backgroundExecutor()));
        }
        PENDING.put(key, CompletableFuture.allOf(notes.toArray(CompletableFuture[]::new))
            .thenApply(v -> OrganSynth.finish(notes.stream().map(CompletableFuture::join).toArray(OrganSynth.Raw[]::new))));
    }

    /** 这台管风琴当前的组合，已经合成好才返回（还没好时开始合成并返回 null）。 */
    static @Nullable Mix ready(InstrumentBlockEntity be) {
        return ready(key(be));
    }

    private static @Nullable Mix ready(Key key) {
        Mix m = READY.get(key);
        if (m != null) return m;
        CompletableFuture<OrganSynth.Sample[]> f = PENDING.get(key);
        if (f == null) {
            prewarm(key);
            return null;
        }
        if (!f.isDone()) return null;
        PENDING.remove(key);
        try {
            return install(key, f.join());
        } catch (RuntimeException e) {
            LOGGER.warn("Organ mix {} (room {}) failed", Integer.toBinaryString(key.mask()), key.room(), e);
            return null;
        }
    }

    /**
     * 立即要发声：当前组合好了就用它；还在合成时先用上一次用过的组合（好了之后 {@link VoiceBank#tickOrgans} 把按着的音交叉淡入）；
     * 进存档后第一次、什么都还没有时才当场等它合成好（25 个音在后台并行，约 0.1–0.4 秒）。
     */
    static Mix now(InstrumentBlockEntity be) {
        Key key = key(be);
        Mix m = ready(key);
        if (m != null) return used(m);
        if (last != null && BY_GEN.containsKey(last.gen)) return last;
        CompletableFuture<OrganSynth.Sample[]> f = PENDING.remove(key);
        OrganSynth.Sample[] samples = f != null ? f.join() : OrganSynth.render(key.mask(), key.reverb());
        return used(install(key, samples));
    }

    private static Mix used(Mix m) {
        last = m;
        return m;
    }

    static @Nullable Mix byGen(int gen) {
        return BY_GEN.get(gen);
    }

    private static Mix install(Key key, OrganSynth.Sample[] samples) {
        Mix m = new Mix(key, nextGen++, samples);
        READY.put(key, m);
        BY_GEN.put(m.gen, m);
        evict();
        return m;
    }

    private static void evict() {
        if (READY.size() <= KEEP) return;
        List<Mix> drop = new ArrayList<>();
        for (Mix m : READY.values()) {
            if (READY.size() - drop.size() <= KEEP) break;
            if (!VoiceBank.uses(m.gen) && m != last) drop.add(m);
        }
        for (Mix m : drop) {
            READY.remove(m.key);
            BY_GEN.remove(m.gen);
            for (SoundBuffer b : m.buffers.values()) b.discardAlBuffer();
        }
    }

    // —— 交给声音引擎 ——

    public static boolean owns(Identifier location) {
        return location.getNamespace().equals(LogosDimension.ID) && location.getPath().startsWith(PREFIX);
    }

    public static CompletableFuture<SoundBuffer> buffer(Identifier location) {
        String rest = location.getPath().substring(PREFIX.length()).replace(".ogg", "");
        // rest = g<代>/l<序号>、g<代>/c<序号> 或 g<代>/t<序号>
        try {
            int slash = rest.indexOf('/');
            int gen = Integer.parseInt(rest.substring(1, slash));
            String name = rest.substring(slash + 1);
            Mix m = BY_GEN.get(gen);
            if (m == null) return CompletableFuture.failedFuture(new IllegalStateException("organ mix gone: " + location));
            SoundBuffer b = m.buffers.get(name);
            if (b == null) {
                OrganSynth.Sample s = m.samples[Integer.parseInt(name.substring(1))];
                b = switch (name.charAt(0)) {
                    case 'l' -> looped(s.pcm(), s.loopStart(), s.loopEnd());
                    case 'c' -> steadyBuffer(s);
                    case 't' -> new SoundBuffer(pcm(s.tail()), FORMAT);
                    default -> throw new IllegalArgumentException(location.toString());
                };
                m.buffers.put(name, b);
            }
            return CompletableFuture.completedFuture(b);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 只有循环段的缓冲，前面垫 20 ms 淡入：取循环段末尾那 20 ms（它本来就首尾相接地接着循环起点）乘上淡入，
     * 于是声音引擎从中途的振幅开始播放也不会「咔」一下；循环点设在垫音之后。
     */
    private static SoundBuffer steadyBuffer(OrganSynth.Sample s) {
        int len = s.loopEnd() - s.loopStart(), lead = OrganSynth.RATE / 50;
        short[] out = new short[lead + len];
        for (int n = 0; n < lead; n++) out[n] = (short) Math.round(s.pcm()[s.loopEnd() - lead + n] * (0.5 - 0.5 * Math.cos(Math.PI * n / lead)));
        System.arraycopy(s.pcm(), s.loopStart(), out, lead, len);
        return looped(out, lead, lead + len);
    }

    /** 带循环点的缓冲：上传到 OpenAL 之后设上（{@link LoopedSoundBuffer}）。 */
    private static SoundBuffer looped(short[] samples, int start, int end) {
        SoundBuffer b = new SoundBuffer(pcm(samples), FORMAT);
        ((LoopedSoundBuffer) (Object) b).logos_dimension$loop(start, end);
        return b;
    }

    private static ByteBuffer pcm(short[] samples) {
        ByteBuffer buf = ByteBuffer.allocateDirect(samples.length * 2).order(ByteOrder.nativeOrder());
        for (short s : samples) buf.putShort(s);
        buf.flip();
        return buf;
    }

    /**
     * 声音引擎重新启动了：OpenAL 上下文是新的，这里留着的缓冲号都属于已经关掉的旧上下文——不能再交给引擎
     * （按过的键会不出声或响成别的声音），也不能删（新上下文里同一个号可能是别的缓冲）。只把引用丢掉，下次用到时重做。
     */
    static void engineReloaded() {
        for (Mix m : READY.values()) m.buffers.clear();
    }

    /** 资源重载时原版会清掉自己的缓存；这里的数据自己管，退出存档时清掉。 */
    static void clear() {
        for (Mix m : READY.values()) for (SoundBuffer b : m.buffers.values()) b.discardAlBuffer();
        READY.clear();
        BY_GEN.clear();
        PENDING.clear();
        last = null;
    }
}
