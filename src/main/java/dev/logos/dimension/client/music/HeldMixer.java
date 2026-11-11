package dev.logos.dimension.client.music;

import com.mojang.blaze3d.audio.SoundBuffer;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.held.HeldSynth;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 手持乐器的声音：每种乐器两层（轻、重）各一套循环段（{@link HeldSynth}），外加一段起音。
 * 第一次用到或拿起乐器时在后台合成（每层约 0.2 秒），之后一直留着（三种乐器两层加起来约 5 MB）。
 * 声音引擎经 {@code OrganBufferMixin} 按名字取：{@code held_runtime/<乐器>_<层>/l<序号>}、{@code held_runtime/<乐器>/attack}。
 */
public final class HeldMixer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PREFIX = "sounds/held_runtime/";
    private static final AudioFormat FORMAT = new AudioFormat(HeldSynth.RATE, 16, 1, true,
        ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN);

    private static final Map<String, HeldSynth.Sample[]> READY = new HashMap<>();
    private static final Map<String, CompletableFuture<HeldSynth.Sample[]>> PENDING = new HashMap<>();
    private static final Map<String, SoundBuffer> BUFFERS = new HashMap<>();

    private HeldMixer() {}

    private static String key(InstrumentKind kind, int layer) {
        return kind.id() + "_" + layer;
    }

    /** 后台开始合成这件乐器的两层（已经有或正在合成时什么都不做）。 */
    public static void prewarm(InstrumentKind kind) {
        if (!kind.handheld()) return;
        for (int layer = 0; layer < 2; layer++) {
            String k = key(kind, layer);
            if (READY.containsKey(k) || PENDING.containsKey(k)) continue;
            int l = layer;
            PENDING.put(k, CompletableFuture.supplyAsync(() -> HeldSynth.render(kind, l), Util.backgroundExecutor()));
        }
    }

    /** 立即要用：没合成好就等它（或当场合成），只在第一次、而且没来得及预先合成时会卡一下。 */
    static HeldSynth.Sample[] now(InstrumentKind kind, int layer) {
        String k = key(kind, layer);
        HeldSynth.Sample[] s = READY.get(k);
        if (s != null) return s;
        CompletableFuture<HeldSynth.Sample[]> f = PENDING.remove(k);
        s = f != null ? f.join() : HeldSynth.render(kind, layer);
        READY.put(k, s);
        return s;
    }

    static boolean ready(InstrumentKind kind) {
        for (int layer = 0; layer < 2; layer++) {
            String k = key(kind, layer);
            if (READY.containsKey(k)) continue;
            CompletableFuture<HeldSynth.Sample[]> f = PENDING.get(k);
            if (f == null || !f.isDone()) return false;
            now(kind, layer);
        }
        return true;
    }

    static Identifier loop(InstrumentKind kind, int layer, int pitch) {
        return LogosDimension.id("held_runtime/" + key(kind, layer) + "/l" + HeldSynth.index(kind, pitch));
    }

    static Identifier attack(InstrumentKind kind) {
        return LogosDimension.id("held_runtime/" + kind.id() + "/attack");
    }

    public static boolean owns(Identifier location) {
        return location.getNamespace().equals(LogosDimension.ID) && location.getPath().startsWith(PREFIX);
    }

    public static CompletableFuture<SoundBuffer> buffer(Identifier location) {
        String rest = location.getPath().substring(PREFIX.length()).replace(".ogg", "");
        try {
            SoundBuffer b = BUFFERS.get(rest);
            if (b == null) {
                int slash = rest.indexOf('/');
                String head = rest.substring(0, slash), name = rest.substring(slash + 1);
                short[] pcm;
                if (name.equals("attack")) {
                    pcm = HeldSynth.attack(kind(head));
                } else {
                    int us = head.lastIndexOf('_');
                    InstrumentKind kind = kind(head.substring(0, us));
                    int layer = Integer.parseInt(head.substring(us + 1));
                    pcm = now(kind, layer)[Integer.parseInt(name.substring(1))].pcm();
                }
                b = new SoundBuffer(pcm(pcm), FORMAT);
                BUFFERS.put(rest, b);
            }
            return CompletableFuture.completedFuture(b);
        } catch (RuntimeException e) {
            LOGGER.warn("Bad handheld instrument sound {}", location, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private static InstrumentKind kind(String id) {
        for (InstrumentKind k : InstrumentKind.values()) if (k.id().equals(id) && k.handheld()) return k;
        throw new IllegalArgumentException("unknown handheld instrument " + id);
    }

    private static ByteBuffer pcm(short[] samples) {
        ByteBuffer buf = ByteBuffer.allocateDirect(samples.length * 2).order(ByteOrder.nativeOrder());
        for (short s : samples) buf.putShort(s);
        buf.flip();
        return buf;
    }

    static void clear() {
        for (SoundBuffer b : BUFFERS.values()) b.discardAlBuffer();
        BUFFERS.clear();
        READY.clear();
        PENDING.clear();
    }

    /** 测试用：这件乐器的样本（没合成时为 null）。 */
    static HeldSynth.Sample @Nullable [] samples(InstrumentKind kind, int layer) {
        return READY.get(key(kind, layer));
    }
}
