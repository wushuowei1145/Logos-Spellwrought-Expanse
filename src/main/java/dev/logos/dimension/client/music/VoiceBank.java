package dev.logos.dimension.client.music;

import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.SampleMap;
import dev.logos.dimension.music.organ.OrganSynth;
import dev.logos.dimension.music.organ.RoomAcoustics;
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
 *   <li>管风琴：按当前音栓组合的循环段（{@link OrganMixer}）按住多久响多久，起音 3 tick 推上来，松键淡出，
 *       室内时补一段混响尾音；按着音时换了音栓，已按住的音交叉淡入新组合（{@link #tickOrgans}）。</li>
 * </ul>
 * 复音有上限，满了先停「已松开、最旧」的音（尾音也算）。
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
        final int pitch;
        final int part;
        final long started;
        /** 管风琴：用的哪一代组合（0 表示不是管风琴）。 */
        final int gen;
        final boolean tail;
        /** 采样乐器：用的哪个采样区（松键时间、松键声、力度对应的音量）；管风琴为 null。 */
        final SampleMap.@Nullable Zone zone;
        final int velocity;
        boolean held = true;
        boolean damped;

        Voice(NoteSound sound, int pitch, int part, long started, int gen, boolean tail) {
            this(sound, pitch, part, started, gen, tail, null, 0);
        }

        Voice(NoteSound sound, int pitch, int part, long started, int gen, boolean tail, SampleMap.@Nullable Zone zone, int velocity) {
            this.sound = sound;
            this.pitch = pitch;
            this.part = part;
            this.started = started;
            this.gen = gen;
            this.tail = tail;
            this.held = !tail;
            this.zone = zone;
            this.velocity = velocity;
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
            List<Voice> all = new ArrayList<>();
            for (Source o : SOURCES.values()) all.addAll(o.voices);
            steal(all);
            for (Source o : SOURCES.values()) o.voices.retainAll(all);
        }
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

    static void noteOn(String key, InstrumentKind kind, BlockPos pos, Vec3 at, int pitch, int velocity, int part) {
        Source s = source(key, kind, pos, at);
        pitch = kind.fold(pitch);
        // 同一个音再次弹下：先止住还在响的那一个（琴槌或手指重新碰到弦；管风琴同一根管不会响两次）
        for (Voice v : s.voices) {
            if (v.pitch == pitch && v.alive() && !v.tail) {
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
            OrganMixer.Mix mix = OrganMixer.now(be.effectiveStops());
            gen = mix.gen;
            sound = organSound(mix, be, pitch, false, 3);
        } else {
            // 大键琴不分力度：一律按中强
            vel = kind.velocitySensitive() ? velocity : 100;
            SampleMap map = SampleMaps.get(kind);
            zone = map == null ? null : map.pick(pitch, vel);
            if (zone == null) return;
            // 手持乐器跟着人走，可闻距离按转发范围；方块乐器按采样表
            int range = kind.handheld() ? Math.min(48, kind.broadcastRange() - 8) : map.range();
            DirectSound note = new DirectSound(SampleMaps.sound(zone.file()), zone.ratio(pitch), zone.volume(vel), at, range, 0, zone.loops())
                .subtitle("subtitles.logos_dimension.instrument." + kind.id());
            note.follow = s.follow;
            sound = note;
        }
        Minecraft.getInstance().getSoundManager().play(sound);
        s.voices.add(new Voice(sound, pitch, part, System.nanoTime(), gen, false, zone, vel));
    }

    private static DirectSound organSound(OrganMixer.Mix mix, InstrumentBlockEntity be, int pitch, boolean tail, int attack) {
        OrganSynth.Sample sample = mix.sample(pitch);
        var o = be.organ();
        int range = o == null ? 16 : o.range();
        float base = mix.loudness * (o != null && o.pipes() == 0 ? 0.5f : 1f);
        Vec3 at = be.voiceCenter();
        if (tail) {
            RoomAcoustics.Reverb r = OrganRoom.reverb(be.getBlockPos(), be);
            float wet = (float) (0.3 + 0.15 * Math.min(1, r.seconds() / 3));
            return new DirectSound(mix.tail(pitch, r), OrganSynth.ratio(sample, pitch), base * wet, at, range, 0, false);
        }
        return new DirectSound(mix.loop(pitch), OrganSynth.ratio(sample, pitch), base * 0.9f, at, range, attack, true);
    }

    /** 松开一个管风琴的音：淡出，室内时补尾音（同一个音的旧尾音先止住）。 */
    private static void releaseOrgan(Source s, Voice v) {
        v.held = false;
        v.sound.release(0.5f);
        InstrumentBlockEntity be = organAt(s.pos);
        if (be == null) return;
        if (!OrganRoom.reverb(s.pos, be).on()) return;
        OrganMixer.Mix mix = OrganMixer.ready(be.effectiveStops());
        if (mix == null || mix.gen != v.gen) return;
        int tails = 0;
        for (Voice t : s.voices) {
            if (!t.tail || !t.alive()) continue;
            if (t.pitch == v.pitch) t.sound.release(0.5f);
            else tails++;
        }
        if (tails >= MAX_TAILS) return;
        makeRoom(s);
        DirectSound tail = organSound(mix, be, v.pitch, true, 0);
        Minecraft.getInstance().getSoundManager().play(tail);
        s.voices.add(new Voice(tail, v.pitch, v.part, System.nanoTime(), mix.gen, true));
    }

    static void noteOff(String key, int pitch, int part) {
        Source s = SOURCES.get(key);
        if (s == null) return;
        pitch = s.kind.fold(pitch);
        for (Voice v : new ArrayList<>(s.voices)) {
            if (v.pitch != pitch || v.part != part || !v.held) continue;
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
     * 每 tick：管风琴按着的音如果用的不是当前音栓的组合（玩家刚换了音栓），新组合合成好之后交叉淡入；
     * 同时预先合成当前组合，免得下一个音要当场合成。
     */
    static void tickOrgans() {
        for (Source s : SOURCES.values()) {
            if (s.kind != InstrumentKind.PIPE_ORGAN) continue;
            InstrumentBlockEntity be = organAt(s.pos);
            if (be == null) continue;
            OrganMixer.Mix mix = OrganMixer.ready(be.effectiveStops());
            if (mix == null) continue;
            for (Voice v : new ArrayList<>(s.voices)) {
                if (!v.held || v.tail || v.gen == mix.gen || !v.alive()) continue;
                v.held = false;
                v.sound.release(0.6f);
                DirectSound fresh = organSound(mix, be, v.pitch, false, 2);
                Minecraft.getInstance().getSoundManager().play(fresh);
                s.voices.add(new Voice(fresh, v.pitch, v.part, System.nanoTime(), mix.gen, false));
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

    /** 测试与界面用：这一路还在响的音数（含尾音）。 */
    static int sounding(String key) {
        Source s = SOURCES.get(key);
        if (s == null) return 0;
        int n = 0;
        for (Voice v : s.voices) if (v.alive()) n++;
        return n;
    }
}
