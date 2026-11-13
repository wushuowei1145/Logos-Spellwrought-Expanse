package dev.logos.dimension.client.music;

import dev.logos.dimension.music.orchestra.BeatMap;
import dev.logos.dimension.music.orchestra.ConductorPattern;
import dev.logos.dimension.music.orchestra.Judgement;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

/**
 * 指挥台的节拍器（docs/ORCHESTRA.md 第 18 节）：只有站在台上指挥的人听得到，每一拍（实际打的拍）一声咔哒，第一拍重一些。
 * <ul>
 * <li>没人打拍子时它和乐团一起走（就是乐团此刻的拍子）。</li>
 * <li>自己在打拍子时它按设定的速度从刚才和乐团对齐的地方接着走，是打拍子的标准：打在它上面就是 PERFECT。</li>
 * <li>延长时它也停住，接着走时重新和乐团对齐。</li>
 * </ul>
 */
final class Metronome {
    private static @Nullable BlockPos podium;
    private static @Nullable UUID roll;
    private static double anchorPos;
    private static long anchorNanos;
    private static double rate = 1;
    private static double lastTick = Double.NaN;
    /** 测试用：响过几声。 */
    static int ticks;

    private Metronome() {}

    /** 节拍器此刻在纸带上的位置。 */
    static double position(long now) {
        return anchorPos + (now - anchorNanos) / 1_000_000.0 * rate;
    }

    static double rate() {
        return rate;
    }

    /** 每一帧：对齐到乐团（没人打拍子、延长、刚开始时），过了拍子就响一声。leading 是自己在打拍子。 */
    static void update(BlockPos at, Conducting.Ensemble e, boolean leading, boolean sound, long now) {
        if (e.beats == null) return;
        boolean fresh = !at.equals(podium) || !e.rollId.equals(roll);
        if (fresh) {
            podium = at;
            roll = e.rollId;
            lastTick = Double.NaN;
        }
        if (fresh || !leading || e.rateMilli == 0) {
            // 跟乐团一起：位置就是乐团的；自己打拍子时速度用设定的（标准），否则用乐团此刻的
            anchorPos = e.clock.position(now);
            anchorNanos = now;
            rate = e.rateMilli == 0 ? 0 : leading ? e.tempo / 100.0 : e.rateMilli / 1000.0;
            if (e.rateMilli == 0) return;   // 延长：不响
        }
        double pos = position(now);
        ConductorPattern.Conducted c = ConductorPattern.conduct(e.beats.at(pos));
        double start = pos - c.phase() * c.beatMs();
        if (Double.isNaN(lastTick)) {
            lastTick = start;   // 刚开始：这一拍已经过了，不补响
            return;
        }
        if (start > lastTick + c.beatMs() * 0.5) {
            lastTick = start;
            ticks++;
            if (sound) click(c.beat() == 0);
        }
    }

    /** 自己打拍子之后：节拍器从这一刻、乐团此刻的位置起按设定的速度走（只在刚接手、刚从延长接着走时调用）。 */
    static void lead(Conducting.Ensemble e, long now) {
        anchorPos = e.clock.position(now);
        anchorNanos = now;
        rate = e.tempo / 100.0;
    }

    /** 这一刻离节拍器最近的一拍差多少真实毫秒（早为负）。 */
    static double offsetMs(BeatMap map, long now) {
        return Judgement.offsetMs(map, position(now), rate);
    }

    /** 测试用：节拍器下一拍的时刻（纳秒）。 */
    static long nextBeatNanos(BeatMap map, long now) {
        double pos = position(now);
        ConductorPattern.Conducted c = ConductorPattern.conduct(map.at(pos));
        double next = pos - c.phase() * c.beatMs() + c.beatMs();
        return now + (long) ((next - pos) / Math.max(1e-3, rate) * 1_000_000);
    }

    static void reset() {
        podium = null;
        roll = null;
        lastTick = Double.NaN;
    }

    private static void click(boolean accent) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), accent ? 1.8f : 1.35f, accent ? 0.9f : 0.6f));
    }
}
