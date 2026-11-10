package dev.logos.dimension.client.music;

import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.music.InstrumentKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 乐器的发声：每个音从最近的采样变调播放（{@link InstrumentKind#sampleFor}），松键时在约 150 ms 内淡出；
 * 钢琴踩下延音踏板时松键不止音，竖琴的弦拨响后自然衰减。复音有上限，满了先停「已松开、最旧」的音。
 *
 * <p>「声源」是一件乐器上的一路演奏（实时演奏一路、纸带播放一路、编辑器试听一路），各自记踏板和发声的音。
 */
final class VoiceBank {
    private static final Map<Identifier, SoundEvent> EVENTS = new HashMap<>();
    private static final Map<String, Source> SOURCES = new HashMap<>();

    private VoiceBank() {}

    private static final class Voice {
        final NoteSound sound;
        final int pitch;
        final int part;
        final long started;
        boolean held = true;

        Voice(NoteSound sound, int pitch, int part, long started) {
            this.sound = sound;
            this.pitch = pitch;
            this.part = part;
            this.started = started;
        }

        /** 采样自然放完时声音引擎直接把它移走，不再调用 tick，所以还要问一下声音引擎它是否还在。 */
        boolean alive() {
            if (sound.isStopped() || sound.finished) return false;
            return System.nanoTime() - started < 250_000_000L || Minecraft.getInstance().getSoundManager().isActive(sound);
        }
    }

    private static final class Source {
        final InstrumentKind kind;
        Vec3 at;
        final List<Voice> voices = new ArrayList<>();
        int pedalMask;

        Source(InstrumentKind kind, Vec3 at) {
            this.kind = kind;
            this.at = at;
        }

        boolean pedal(int part) {
            return (pedalMask & (1 << part)) != 0;
        }
    }

    /** 一个音的声音：音量可以在每 tick 里逐渐降下来。 */
    static final class NoteSound extends AbstractTickableSoundInstance {
        private boolean releasing;
        private float fade = 0.45f;
        private int age;
        boolean finished;

        NoteSound(SoundEvent event, float pitch, float volume, Vec3 at) {
            super(event, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.pitch = pitch;
            this.volume = volume;
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
            this.attenuation = Attenuation.LINEAR;
            this.looping = false;
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
            if (releasing) {
                volume *= fade;
                if (volume < 0.01f) {
                    finished = true;
                    stop();
                }
            }
            // 采样最长约 6.5 秒，到点后声音引擎自己会结束，这里只是保险
            if (age > 20 * 8) {
                finished = true;
                stop();
            }
        }
    }

    private static SoundEvent event(Identifier id) {
        return EVENTS.computeIfAbsent(id, SoundEvent::createVariableRangeEvent);
    }

    private static Source source(String key, InstrumentKind kind, Vec3 at) {
        Source s = SOURCES.get(key);
        if (s == null || s.kind != kind) {
            if (s != null) allOff(key);
            s = new Source(kind, at);
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

    /** 挑一个可以停掉的音：先挑已松开的最旧的，没有就挑最旧的。 */
    private static void steal(List<Voice> pool) {
        Voice pick = null;
        for (Voice v : pool) {
            if (!v.alive()) continue;
            if (pick == null || (!v.held && pick.held) || (v.held == pick.held && v.started < pick.started)) pick = v;
        }
        if (pick != null) {
            pick.sound.release(0.1f);
            pick.sound.finished = true;
            Minecraft.getInstance().getSoundManager().stop(pick.sound);
            pool.remove(pick);
        }
    }

    static void noteOn(String key, InstrumentKind kind, Vec3 at, int pitch, int velocity, int part) {
        Source s = source(key, kind, at);
        pitch = kind.fold(pitch);
        // 同一个音再次弹下：先止住还在响的那一个（琴槌或手指重新碰到弦）
        for (Voice v : s.voices) {
            if (v.pitch == pitch && v.alive()) {
                v.held = false;
                v.sound.release(0.3f);
            }
        }
        s.voices.removeIf(v -> !v.alive());
        int cap = ClientConfig.INSTRUMENT_VOICES.get();
        while (s.voices.size() >= cap) steal(s.voices);
        if (totalAlive() >= ClientConfig.INSTRUMENT_TOTAL_VOICES.get()) {
            List<Voice> all = new ArrayList<>();
            for (Source o : SOURCES.values()) all.addAll(o.voices);
            steal(all);
            for (Source o : SOURCES.values()) o.voices.retainAll(all);
        }
        float volume = (float) Math.pow(Math.max(1, Math.min(127, velocity)) / 127.0, 1.6);
        NoteSound sound = new NoteSound(event(kind.soundId(kind.sampleFor(pitch))), kind.pitchRatio(pitch), volume, at);
        Minecraft.getInstance().getSoundManager().play(sound);
        s.voices.add(new Voice(sound, pitch, part, System.nanoTime()));
    }

    static void noteOff(String key, int pitch, int part) {
        Source s = SOURCES.get(key);
        if (s == null) return;
        pitch = s.kind.fold(pitch);
        for (Voice v : s.voices) {
            if (v.pitch != pitch || v.part != part || !v.held) continue;
            v.held = false;
            if (s.kind.dampsOnRelease() && !s.pedal(part)) v.sound.release(0.45f);
        }
    }

    static void pedal(String key, InstrumentKind kind, Vec3 at, int part, boolean down) {
        Source s = source(key, kind, at);
        if (down) {
            s.pedalMask |= 1 << part;
            return;
        }
        s.pedalMask &= ~(1 << part);
        if (!s.kind.dampsOnRelease()) return;
        for (Voice v : s.voices) {
            if (v.part == part && !v.held) v.sound.release(0.45f);
        }
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

    /** 测试与界面用：这一路还在响的音数。 */
    static int sounding(String key) {
        Source s = SOURCES.get(key);
        if (s == null) return 0;
        int n = 0;
        for (Iterator<Voice> it = s.voices.iterator(); it.hasNext(); ) {
            if (it.next().alive()) n++;
        }
        return n;
    }
}
