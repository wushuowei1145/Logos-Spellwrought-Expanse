package dev.logos.dimension.client.music;

import com.mojang.blaze3d.audio.SoundBuffer;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.LogosDimension;
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
 * 管风琴的混音：一套音栓组合 → 25 个无缝循环段（{@link OrganSynth}），在后台线程合成。
 * 声音引擎经 {@code OrganBufferMixin} 按名字取数据：循环段 {@code organ_runtime/g<代>/l<序号>}，
 * 混响尾音 {@code organ_runtime/g<代>/t<序号>_<档>}（第一次用到时才生成）。
 * 最多留 4 套组合；正在发声的组合不清掉。
 */
public final class OrganMixer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "sounds/organ_runtime/";
    private static final int KEEP = 4;
    private static final AudioFormat FORMAT = new AudioFormat(OrganSynth.RATE, 16, 1, true,
        ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);

    private OrganMixer() {}

    /** 一套合成好的组合。 */
    static final class Mix {
        final int mask;
        final int gen;
        final OrganSynth.Sample[] samples;
        final float loudness;
        final Map<String, SoundBuffer> buffers = new HashMap<>();

        Mix(int mask, int gen, OrganSynth.Sample[] samples) {
            this.mask = mask;
            this.gen = gen;
            this.samples = samples;
            this.loudness = (float) OrganStop.loudness(mask);
        }

        OrganSynth.Sample sample(int pitch) {
            return samples[OrganSynth.index(pitch)];
        }

        Identifier loop(int pitch) {
            return LogosDimension.id("organ_runtime/g" + gen + "/l" + OrganSynth.index(pitch));
        }

        Identifier tail(int pitch, RoomAcoustics.Reverb reverb) {
            return LogosDimension.id("organ_runtime/g" + gen + "/t" + OrganSynth.index(pitch) + "_" + RoomAcoustics.bucket(reverb));
        }
    }

    private static int nextGen = 1;
    private static final Map<Integer, Mix> READY = new LinkedHashMap<>(8, 0.75f, true);
    private static final Map<Integer, Mix> BY_GEN = new HashMap<>();
    private static final Map<Integer, CompletableFuture<OrganSynth.Sample[]>> PENDING = new HashMap<>();

    /** 后台开始合成这套组合（已经有或正在合成时什么都不做）。 */
    public static void prewarm(int mask) {
        if (READY.containsKey(mask) || PENDING.containsKey(mask)) return;
        PENDING.put(mask, CompletableFuture.supplyAsync(() -> OrganSynth.render(mask), Util.backgroundExecutor()));
    }

    /** 已经合成好的组合；还没好时返回 null（并开始合成）。 */
    static @Nullable Mix ready(int mask) {
        Mix m = READY.get(mask);
        if (m != null) return m;
        CompletableFuture<OrganSynth.Sample[]> f = PENDING.get(mask);
        if (f == null) {
            prewarm(mask);
            return null;
        }
        if (!f.isDone()) return null;
        PENDING.remove(mask);
        try {
            return install(mask, f.join());
        } catch (RuntimeException e) {
            LOGGER.warn("Organ mix {} failed", Integer.toBinaryString(mask), e);
            return null;
        }
    }

    /** 立即要用：没有就当场合成（约 0.1 秒，只在第一次、而且没来得及预先合成时发生）。 */
    static Mix now(int mask) {
        Mix m = ready(mask);
        if (m != null) return m;
        CompletableFuture<OrganSynth.Sample[]> f = PENDING.remove(mask);
        OrganSynth.Sample[] samples = f != null ? f.join() : OrganSynth.render(mask);
        return install(mask, samples);
    }

    private static Mix install(int mask, OrganSynth.Sample[] samples) {
        Mix m = new Mix(mask, nextGen++, samples);
        READY.put(mask, m);
        BY_GEN.put(m.gen, m);
        evict();
        return m;
    }

    private static void evict() {
        if (READY.size() <= KEEP) return;
        List<Mix> drop = new ArrayList<>();
        for (Mix m : READY.values()) {
            if (READY.size() - drop.size() <= KEEP) break;
            if (!VoiceBank.uses(m.gen)) drop.add(m);
        }
        for (Mix m : drop) {
            READY.remove(m.mask);
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
        // rest = g<代>/l<序号> 或 g<代>/t<序号>_<档>
        try {
            int slash = rest.indexOf('/');
            int gen = Integer.parseInt(rest.substring(1, slash));
            String name = rest.substring(slash + 1);
            Mix m = BY_GEN.get(gen);
            if (m == null) return CompletableFuture.failedFuture(new IllegalStateException("organ mix gone: " + location));
            SoundBuffer b = m.buffers.get(name);
            if (b == null) {
                short[] pcm;
                if (name.startsWith("l")) {
                    pcm = m.samples[Integer.parseInt(name.substring(1))].pcm();
                } else {
                    int us = name.indexOf('_');
                    int index = Integer.parseInt(name.substring(1, us));
                    RoomAcoustics.Reverb r = RoomAcoustics.fromBucket(Integer.parseInt(name.substring(us + 1)));
                    pcm = OrganSynth.tail(m.samples[index].pcm(), Math.max(0.2, r.seconds()), r.brightness());
                }
                b = new SoundBuffer(pcm(pcm), FORMAT);
                m.buffers.put(name, b);
            }
            return CompletableFuture.completedFuture(b);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static ByteBuffer pcm(short[] samples) {
        ByteBuffer buf = ByteBuffer.allocateDirect(samples.length * 2).order(ByteOrder.nativeOrder());
        for (short s : samples) buf.putShort(s);
        buf.flip();
        return buf;
    }

    /** 资源重载时原版会清掉自己的缓存；这里的数据自己管，退出存档时清掉。 */
    static void clear() {
        for (Mix m : READY.values()) for (SoundBuffer b : m.buffers.values()) b.discardAlBuffer();
        READY.clear();
        BY_GEN.clear();
        PENDING.clear();
    }
}
