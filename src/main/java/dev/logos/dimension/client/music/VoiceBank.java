package dev.logos.dimension.client.music;

import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.SampleMap;
import dev.logos.dimension.music.organ.OrganSynth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 乐器的发声：
 * <ul>
 *   <li>采样乐器（钢琴、竖琴、钟琴、钢片琴、管钟、大键琴、手持乐器）：按采样表（{@link SampleMaps}）取这个音高、
 *       这个力度的那个采样变调播放，音量随力度；持续音的采样带循环点，按住多久响多久。会止音的乐器松键时
 *       按这个采样的松键时间淡出（钢琴低音长、高音短），大键琴另放一下拨子落回的声音；钢琴踩下延音踏板时不止音，
 *       竖琴、钟琴、管钟的声音拨响或敲响后自然衰减。</li>
 *   <li>管风琴：按当前音栓组合与房间的采样（{@link OrganMixer}）：起音和混响都已合成在采样里，直接全音量放，之后在循环段里
 *       按住多久响多久；松键很快淡出，房间有混响时接上这一套的尾音（按得越短、混响长得越少，尾音越轻）；
 *       按着音时换了音栓或房间，已按住的音交叉淡入新组合的循环段（{@link #tickOrgans}）。</li>
 * </ul>
 * 复音有上限，满了先停「已松开、最旧」的音（尾音也算）；所有乐器加起来满了，从发声最多的那件乐器里停，
 * 不去掐别的乐器仅有的一个长音。
 *
 * <p>音高：每个音记着调用方给的「原音」（键盘上的那个键、纸带上的那个音）和实际发声的音高。实时演奏（键盘、电子琴、
 * 别人的演奏）不在音域里的音不出声、也不折叠；纸带由 {@link MusicClient} 先整个声部移八度再给实际音高。
 * 松键、同一个音再弹都按原音找，两个不同的原音即使发同一个音高也互不影响。
 *
 * <p>「声源」是一件乐器上的一路演奏（实时演奏一路、纸带播放一路、编辑器试听一路），各自记踏板和发声的音。
 */
final class VoiceBank {
    private static final Map<Identifier, SoundEvent> EVENTS = new HashMap<>();
    private static final Map<String, Source> SOURCES = new HashMap<>();
    private static final int MAX_TAILS = 32;

    private VoiceBank() {}

    private static final class Voice {
        final NoteSound sound;
        /** 调用方给的原音（松键、重弹按它找）。 */
        final int input;
        /** 实际发声的音高。 */
        final int pitch;
        final int part;
        final long started;
        /** 管风琴：用的哪一代组合（0 表示不是管风琴）。 */
        final int gen;
        final boolean tail;
        /** 采样乐器：用的哪个采样区（松键时间、松键声、力度对应的音量）；管风琴为 null。 */
        final SampleMap.@Nullable Zone zone;
        final int velocity;
        /** 乐团里这个声部的响度（倍数）：管风琴的余响、换音栓时的接续音也按它。 */
        float gain = 1f;
        /** 管风琴：这个音最初弹下的时刻（换音栓接续时不变），松键时按它算混响长到了几成。 */
        long noteStart;
        boolean held = true;
        boolean damped;

        Voice(NoteSound sound, int input, int pitch, int part, long started, int gen, boolean tail) {
            this(sound, input, pitch, part, started, gen, tail, null, 0);
        }

        Voice(NoteSound sound, int input, int pitch, int part, long started, int gen, boolean tail, SampleMap.@Nullable Zone zone, int velocity) {
            this.sound = sound;
            this.input = input;
            this.pitch = pitch;
            this.part = part;
            this.started = started;
            this.gen = gen;
            this.tail = tail;
            this.held = !tail;
            this.zone = zone;
            this.velocity = velocity;
            this.noteStart = started;
        }

        /** 采样自然放完时声音引擎直接把它移走，不再调用 tick，所以还要问一下声音引擎它是否还在。 */
        boolean alive() {
            if (sound.isStopped() || sound.finished) return false;
            return System.nanoTime() - started < 250_000_000L || Minecraft.getInstance().getSoundManager().isActive(sound);
        }
    }

    private static final class Source {
        final InstrumentKind kind;
        final BlockPos pos;
        Vec3 at;
        final List<Voice> voices = new ArrayList<>();
        int pedalMask;
        /** 手持乐器：声音跟着这个实体走（-1 表示不跟）。 */
        int follow = -1;

        Source(InstrumentKind kind, BlockPos pos, Vec3 at) {
            this.kind = kind;
            this.pos = pos;
            this.at = at;
        }

        boolean pedal(int part) {
            return (pedalMask & (1 << part)) != 0;
        }
    }

    /** 一个音的声音：音量可以在每 tick 里逐渐推上来、降下来。 */
    static class NoteSound extends AbstractTickableSoundInstance {
        private boolean releasing;
        private float fade = 0.45f;
        private final float target;
        private final int attackTicks;
        private int age;
        boolean finished;
        /** 跟着走的实体（拿在手上的乐器），-1 表示固定在原处。 */
        int follow = -1;

        NoteSound(SoundEvent event, float pitch, float volume, Vec3 at, int attackTicks, boolean loop) {
            super(event, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.pitch = pitch;
            this.target = volume;
            this.attackTicks = attackTicks;
            this.volume = attackTicks > 0 ? volume / (attackTicks + 1) : volume;
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
            this.attenuation = Attenuation.LINEAR;
            this.looping = loop;
            this.delay = 0;
        }

        void release(float perTick) {
            releasing = true;
            fade = Math.min(fade, perTick);
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            age++;
            if (follow >= 0 && Minecraft.getInstance().level != null) {
                var e = Minecraft.getInstance().level.getEntity(follow);
                if (e != null) {
                    // 乐器在胸口到嘴边的高度
                    this.x = e.getX();
                    this.y = e.getY() + e.getBbHeight() * 0.8;
                    this.z = e.getZ();
                }
            }
            if (releasing) {
                volume *= fade;
                if (volume < 0.01f) {
                    finished = true;
                    stop();
                }
            } else if (age <= attackTicks) {
                volume = target * (age + 1f) / (attackTicks + 1f);
            }
            // 不循环的采样放完时声音引擎自己会结束（钢琴低音最长约 12 秒），这里只是保险；循环的不限时
            if (!looping && age > 20 * 40) {
                finished = true;
                stop();
            }
        }
    }

    /**
     * 不经 sounds.json、直接指向一个声音文件的声音：采样表里的 ogg，或管风琴在内存里现做的循环段、尾音
     * （{@link OrganMixer}）。可闻距离和字幕在这里给。
     */
    static final class DirectSound extends NoteSound {
        private final Identifier buffer;
        private final int range;

        DirectSound(Identifier buffer, float pitch, float volume, Vec3 at, int range, int attackTicks, boolean loop) {
            super(event(buffer), pitch, volume, at, attackTicks, loop);
            this.buffer = buffer;
            this.range = range;
        }

        private String subtitle = "subtitles.logos_dimension.instrument.pipe_organ";

        DirectSound subtitle(String key) {
            this.subtitle = key;
            return this;
        }

        @Override
        public @Nullable WeighedSoundEvents resolve(SoundManager manager) {
            this.sound = new Sound(buffer, ConstantFloat.of(1f), ConstantFloat.of(1f), 1, Sound.Type.FILE, false, false, range);
            return new WeighedSoundEvents(buffer, subtitle);
        }
    }

    private static SoundEvent event(Identifier id) {
        return EVENTS.computeIfAbsent(id, SoundEvent::createVariableRangeEvent);
    }

    private static Source source(String key, InstrumentKind kind, BlockPos pos, Vec3 at) {
        Source s = SOURCES.get(key);
        if (s == null || s.kind != kind || !s.pos.equals(pos)) {
            if (s != null) allOff(key);
            s = new Source(kind, pos.immutable(), at);
            SOURCES.put(key, s);
        }
        s.at = at;
        return s;
    }

    private static int totalAlive() {
        int n = 0;
        for (Source s : SOURCES.values()) {
            s.voices.removeIf(v -> !v.alive());
            n += s.voices.size();
        }
        return n;
    }

    /** 挑一个可以停掉的音：先挑尾音和已松开的、最旧的，没有就挑最旧的。 */
    private static void steal(List<Voice> pool) {
        Voice pick = null;
        for (Voice v : pool) {
            if (!v.alive()) continue;
            if (pick == null || (!v.held && pick.held) || (v.held == pick.held && v.started < pick.started)) pick = v;
        }
        if (pick != null) {
            pick.sound.finished = true;
            Minecraft.getInstance().getSoundManager().stop(pick.sound);
            pool.remove(pick);
        }
    }

    private static void makeRoom(Source s) {
        s.voices.removeIf(v -> !v.alive());
        int cap = ClientConfig.INSTRUMENT_VOICES.get();
        if (s.kind == InstrumentKind.PIPE_ORGAN) cap = Math.max(cap, 48);
        while (s.voices.size() >= cap) steal(s.voices);
        if (totalAlive() >= ClientConfig.INSTRUMENT_TOTAL_VOICES.get()) {
            // 合奏时所有乐器加起来满了：先找有已松开（尾音）的乐器，其中发声最多的那件让出一个；
            // 都按着时也从发声最多的那件里停最旧的，不去掐只剩一两个音的乐器
            Source pick = null;
            for (Source o : SOURCES.values()) {
                if (o.voices.isEmpty()) continue;
                boolean released = hasReleased(o);
                if (pick == null) {
                    pick = o;
                    continue;
                }
                boolean pickReleased = hasReleased(pick);
                if (released != pickReleased ? released : o.voices.size() > pick.voices.size()) pick = o;
            }
            if (pick != null) steal(pick.voices);
        }
    }

    private static boolean hasReleased(Source s) {
        for (Voice v : s.voices) if (!v.held) return true;
        return false;
    }

    private static @Nullable InstrumentBlockEntity organAt(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getBlockEntity(pos) instanceof InstrumentBlockEntity be ? be : null;
    }

    /** 拿在手上的乐器：声源跟着实体走。 */
    static void noteOnHeld(String key, InstrumentKind kind, int entity, Vec3 at, int pitch, int velocity) {
        Source s = source(key, kind, BlockPos.ZERO, at);
        s.follow = entity;
        noteOn(key, kind, BlockPos.ZERO, at, pitch, velocity, 0);
    }

    /** 实时演奏的一个音（键盘、电子琴、别人的演奏）：原音就是发声的音高，不在音域里不出声。 */
    static void noteOn(String key, InstrumentKind kind, BlockPos pos, Vec3 at, int pitch, int velocity, int part) {
        noteOn(key, kind, pos, at, pitch, pitch, velocity, part);
    }

    /**
     * @param input 原音：松键、同一个音再弹都按它找
     * @param pitch 实际发声的音高；不在这件乐器的音域里就不出声
     */
    static void noteOn(String key, InstrumentKind kind, BlockPos pos, Vec3 at, int input, int pitch, int velocity, int part) {
        noteOn(key, kind, pos, at, input, pitch, velocity, part, 1f);
    }

    /** @param gain 乐团里这个声部的响度（倍数） */
    static void noteOn(String key, InstrumentKind kind, BlockPos pos, Vec3 at, int input, int pitch, int velocity, int part, float gain) {
        if (pitch < kind.low() || pitch > kind.high() || gain <= 0) return;
        Source s = source(key, kind, pos, at);
        // 同一个音再次弹下：先止住还在响的那一个（琴槌或手指重新碰到弦；管风琴同一根管不会响两次）
        for (Voice v : s.voices) {
            if (v.input == input && v.part == part && v.alive() && !v.tail) {
                v.held = false;
                v.sound.release(0.3f);
            }
        }
        makeRoom(s);
        NoteSound sound;
        int gen = 0;
        SampleMap.Zone zone = null;
        int vel = velocity;
        if (kind == InstrumentKind.PIPE_ORGAN) {
            InstrumentBlockEntity be = organAt(pos);
            if (be == null) return;
            OrganMixer.Mix mix = OrganMixer.now(be);
            gen = mix.gen;
            // 起音已经合成在采样里（从静音柔和地长出来），直接全音量放
            sound = organSound(mix, be, pitch, mix.note(pitch), 1f, 0, true, gain);
        } else {
            // 大键琴不分力度：一律按中强
            vel = kind.velocitySensitive() ? velocity : 100;
            SampleMap map = SampleMaps.get(kind);
            zone = map == null ? null : map.pick(pitch, vel);
            if (zone == null) return;
            // 手持乐器跟着人走，可闻距离按转发范围；方块乐器按采样表
            int range = kind.handheld() ? Math.min(48, kind.broadcastRange() - 8) : map.range();
            DirectSound note = new DirectSound(SampleMaps.sound(zone.file()), zone.ratio(pitch), Math.min(1f, zone.volume(vel) * gain), at, range, 0, zone.loops())
                .subtitle("subtitles.logos_dimension.instrument." + kind.id());
            note.follow = s.follow;
            sound = note;
        }
        Minecraft.getInstance().getSoundManager().play(sound);
        Voice voice = new Voice(sound, input, pitch, part, System.nanoTime(), gen, false, zone, vel);
        voice.gain = gain;
        s.voices.add(voice);
    }

    /** 管风琴松键后每刻音量乘的倍数：约 0.2 秒降到 −40 dB（真管子停得快，余下的交给尾音）。 */
    private static final float ORGAN_RELEASE = 0.35f;

    /**
     * 管风琴一个音的声音。buffer 是整个采样、只有循环段，或尾音；volume 是相对这套组合基准音量的倍数。
     * 基准音量 = 组合的响度（{@link OrganMixer.Mix#loudness}）× 0.9，没有音管时减半，再乘乐团里这个声部的响度。
     */
    private static DirectSound organSound(OrganMixer.Mix mix, InstrumentBlockEntity be, int pitch, Identifier buffer, float volume, int attack, boolean loop, float gain) {
        var o = be.organ();
        int range = o == null ? 16 : o.range();
        float base = Math.min(1f, mix.loudness * (o != null && o.pipes() == 0 ? 0.5f : 1f) * gain) * 0.9f;
        return new DirectSound(buffer, OrganSynth.ratio(mix.sample(pitch), pitch), base * volume, be.voiceCenter(), range, attack, loop);
    }

    /**
     * 松开一个管风琴的音：很快淡出；这一套组合有房间时接上它的尾音（同一个音的旧尾音先止住）。尾音的音量按这个音按了多久：
     * 按得很短时房间里的混响还没长起来，尾音也轻（{@link OrganSynth.Sample#bloomAt}）。
     */
    private static void releaseOrgan(Source s, Voice v) {
        v.held = false;
        v.sound.release(ORGAN_RELEASE);
        InstrumentBlockEntity be = organAt(s.pos);
        if (be == null) return;
        OrganMixer.Mix mix = OrganMixer.byGen(v.gen);
        if (mix == null) return;
        Identifier tailBuffer = mix.tail(v.pitch);
        if (tailBuffer == null) return;
        int tails = 0;
        for (Voice t : s.voices) {
            if (!t.tail || !t.alive()) continue;
            if (t.pitch == v.pitch) t.sound.release(0.5f);
            else tails++;
        }
        if (tails >= MAX_TAILS) return;
        makeRoom(s);
        int ticks = (int) Math.min(1000, (System.nanoTime() - v.noteStart) / 50_000_000L);
        DirectSound tail = organSound(mix, be, v.pitch, tailBuffer, mix.sample(v.pitch).bloomAt(ticks), 1, false, v.gain);
        Minecraft.getInstance().getSoundManager().play(tail);
        Voice t = new Voice(tail, v.input, v.pitch, v.part, System.nanoTime(), mix.gen, true);
        t.gain = v.gain;
        s.voices.add(t);
    }

    /** 松开一个原音（与 noteOn 的 input 相同）。 */
    static void noteOff(String key, int input, int part) {
        Source s = SOURCES.get(key);
        if (s == null) return;
        for (Voice v : new ArrayList<>(s.voices)) {
            if (v.input != input || v.part != part || !v.held) continue;
            if (s.kind == InstrumentKind.PIPE_ORGAN) {
                releaseOrgan(s, v);
                continue;
            }
            v.held = false;
            if (s.kind.dampsOnRelease() && !s.pedal(part)) damp(s, v);
        }
    }

    /** 每 tick 乘的倍数：seconds 秒后降到 -40 dB。 */
    private static float perTick(float seconds) {
        return (float) Math.pow(10.0, -2.0 / (Math.max(0.05f, seconds) * 20.0));
    }

    /** 止音：按这个采样的松键时间淡出；大键琴另放一下拨子落回的声音（拨弦后多久松键都会有）。 */
    private static void damp(Source s, Voice v) {
        if (v.damped) return;
        v.damped = true;
        float seconds = v.zone != null && v.zone.release() > 0 ? v.zone.release() : 0.15f;
        v.sound.release(perTick(seconds));
        SampleMap.Zone z = v.zone;
        if (z == null || z.releaseSound() == null) return;
        SampleMap map = SampleMaps.get(s.kind);
        float touch = z.gain() > 0 ? z.volume(v.velocity) / z.gain() : 1f;
        DirectSound release = new DirectSound(SampleMaps.sound(z.releaseSound()), z.ratio(v.pitch), Math.min(1f, z.releaseGain() * touch),
            s.at, map == null ? 24 : map.range(), 0, false).subtitle("subtitles.logos_dimension.instrument." + s.kind.id());
        release.follow = s.follow;
        Minecraft.getInstance().getSoundManager().play(release);
    }

    static void pedal(String key, InstrumentKind kind, BlockPos pos, Vec3 at, int part, boolean down) {
        // 管风琴、大键琴、钟琴等没有延音踏板（竖琴、管钟本来就不止音）
        if (!kind.usesPedal()) return;
        Source s = source(key, kind, pos, at);
        if (down) {
            s.pedalMask |= 1 << part;
            return;
        }
        s.pedalMask &= ~(1 << part);
        if (!s.kind.dampsOnRelease()) return;
        for (Voice v : s.voices) {
            if (v.part == part && !v.held && !v.tail) damp(s, v);
        }
    }

    /**
     * 每 tick：管风琴按着的音如果用的不是当前的组合（玩家刚换了音栓、房间的混响变了，或第一个音先借用了上一套），
     * 新组合合成好之后交叉淡入它的循环段（不再起一次音）；同时预先合成当前组合，免得下一个音要等。
     */
    static void tickOrgans() {
        for (Source s : SOURCES.values()) {
            if (s.kind != InstrumentKind.PIPE_ORGAN) continue;
            InstrumentBlockEntity be = organAt(s.pos);
            if (be == null) continue;
            OrganMixer.Mix mix = OrganMixer.ready(be);
            if (mix == null) continue;
            for (Voice v : new ArrayList<>(s.voices)) {
                if (!v.held || v.tail || v.gen == mix.gen || !v.alive()) continue;
                v.held = false;
                v.sound.release(0.6f);
                DirectSound fresh = organSound(mix, be, v.pitch, mix.steady(v.pitch), 1f, 2, true, v.gain);
                Minecraft.getInstance().getSoundManager().play(fresh);
                Voice f = new Voice(fresh, v.input, v.pitch, v.part, System.nanoTime(), mix.gen, false);
                f.gain = v.gain;
                f.noteStart = v.noteStart;
                s.voices.add(f);
            }
        }
    }

    /** 有没有还在响的声音用着这一代组合（{@link OrganMixer} 清缓存前问）。 */
    static boolean uses(int gen) {
        for (Source s : SOURCES.values()) {
            for (Voice v : s.voices) if (v.gen == gen && v.alive()) return true;
        }
        return false;
    }

    /** 全部松开（演奏者离开、纸带停止、跳到别处）。 */
    static void allOff(String key) {
        Source s = SOURCES.remove(key);
        if (s == null) return;
        for (Voice v : s.voices) v.sound.release(0.35f);
    }

    static void clear() {
        for (String key : new ArrayList<>(SOURCES.keySet())) allOff(key);
        SOURCES.clear();
    }

    /** 声音引擎重新启动了（换资源包、F3+T、换了音频设备）：原来的声音都已停掉，记录一并丢掉。 */
    static void engineReloaded() {
        SOURCES.clear();
    }

    /** 这一路有没有按着的音（手持乐器的演奏姿势用：按住长音时弓一直来回拉）。 */
    static boolean holding(String key) {
        Source s = SOURCES.get(key);
        if (s == null) return false;
        for (Voice v : s.voices) if (v.held && v.alive()) return true;
        return false;
    }

    /** 这件方块乐器（大提琴、低音提琴）有没有按着的音，或者刚停 0.3 秒以内：坐在后面的演奏者的弓跟着拉。 */
    static boolean recentlyPlayed(BlockPos pos) {
        for (Source s : SOURCES.values()) {
            if (!s.pos.equals(pos)) continue;
            for (Voice v : s.voices) {
                if (v.alive() && (v.held || System.nanoTime() - v.started < 300_000_000L)) return true;
            }
        }
        return false;
    }

    /**
     * 这件乐器（按位置，不分是谁在弹：纸带、乐团、现场演奏都算）此刻按着的音和刚弹下的音：每项 {音高, 弹下多少毫秒}。
     * 坐在琴凳上的键盘手、竖琴手按它摆手（{@link MusicianPoses}）。
     */
    static List<int[]> notesAt(BlockPos pos) {
        List<int[]> out = new ArrayList<>();
        long now = System.nanoTime();
        for (Source s : SOURCES.values()) {
            if (!s.pos.equals(pos)) continue;
            for (Voice v : s.voices) {
                if (v.tail || !v.alive()) continue;
                long age = (now - v.started) / 1_000_000L;
                if (v.held || age < 250) out.add(new int[]{v.pitch, (int) Math.min(Integer.MAX_VALUE, age)});
            }
        }
        return out;
    }

    /** 测试与界面用：这一路还在响的音数（含尾音）。 */
    static int sounding(String key) {
        Source s = SOURCES.get(key);
        if (s == null) return 0;
        int n = 0;
        for (Voice v : s.voices) if (v.alive()) n++;
        return n;
    }
}
