package dev.logos.dimension.elven.blight;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 公主净化腐败之心的进度和降下（docs/CORRUPTED_QUEEN_REMAKE.md 第 5 节）。只是数，不碰世界，单元测试直接跑。
 *
 * <ul>
 * <li>公主在祭坛上引导时进度涨，每 20% 约 20 秒。涨到 20%、40%、60%、80%、100% 时腐败之心被压下来一次（约 8 秒），进度停住。
 * <li>降下来时任何一个玩家砍中它一下（{@link #cut}）就斩掉一段：固定是最大生命的 20%，五刀砍完。
 * <li>没人砍到：它升回去，进度退 15%，再涨到这个刻度时重新降下。
 * <li>公主倒地时进度每秒退 1%；没人扶、她自己起来时再退 25%（{@link #setback}）。进度最低只退到上一个已经斩掉的刻度（{@link #floor}）。
 * </ul>
 * 这些都不写在屏幕上：玩家看腐败之心周围那圈净化之光合拢、看它降下来，公主也会喊出来（{@link PrincessLines}）。
 */
public final class HeartPurification {
    /** 降下来的进度。 */
    public static final float[] THRESHOLDS = {.2f, .4f, .6f, .8f, 1f};
    public static final int SEGMENTS = THRESHOLDS.length;
    /** 单人时每段生命（只给血条上的数用）；多人按 {@link #scale} 乘。 */
    public static final float SEGMENT = 200;
    public static final int WINDOW_TICKS = 160;
    /** 从一个刻度涨到下一个要多少刻。 */
    public static final int SEGMENT_TICKS = 400;
    public static final float FAIL_SETBACK = .15f, DOWNED_DECAY = .01f / 20, SELF_RISE_SETBACK = .25f;

    public enum Event { NONE, OPENED, FAILED, CLEARED }

    private float progress, scale = 1;
    private int cleared, window;

    /** 新的一场：多人时生命按 {@code scale} 乘。 */
    public void reset(float scale) {
        this.scale = Math.max(1, scale);
        progress = 0;
        cleared = 0;
        window = 0;
    }

    public float progress() { return progress; }
    /** 已经斩掉几段。 */
    public int cleared() { return cleared; }
    /** 降下来还剩几刻，没降为 0。 */
    public int window() { return window; }
    public boolean open() { return window > 0; }
    public boolean done() { return cleared >= SEGMENTS; }
    public float scale() { return scale; }
    public float maxHealth() { return SEGMENT * scale * SEGMENTS; }
    /** 腐败之心整体还剩几成（血条用）。 */
    public float health() { return (SEGMENTS - Math.min(cleared, SEGMENTS)) / (float) SEGMENTS; }
    /** 公主身边净化圈的半径（格）。 */
    public static double circle(float progress) { return 3 + 9 * progress; }

    /** 进度最低退到哪：上一个已经斩掉的刻度（斩掉的段不会因为倒地、没砍到再「没斩过」）。 */
    public float floor() { return cleared == 0 ? 0 : THRESHOLDS[Math.min(cleared, SEGMENTS) - 1]; }

    /** 离下一次降下还差几成：0 刚斩完，1 正要降下（腐败之心周围那圈光按它合拢）。 */
    public float toNext() {
        if (done()) return 1;
        if (open()) return 1;
        float lo = floor(), hi = THRESHOLDS[cleared];
        return Math.clamp((progress - lo) / Math.max(1e-6f, hi - lo), 0, 1);
    }

    /** 每刻涨多少。 */
    public static float rate() {
        return THRESHOLDS[0] / SEGMENT_TICKS;
    }

    /** 逐刻推进。{@code channeling}：公主正在祭坛上引导；{@code downed}：她倒地了。 */
    public Event tick(boolean channeling, boolean downed) {
        if (done()) return Event.NONE;
        if (window > 0) {
            if (--window > 0) return Event.NONE;
            progress = Math.max(floor(), progress - FAIL_SETBACK);
            return Event.FAILED;
        }
        if (downed) {
            progress = Math.max(floor(), progress - DOWNED_DECAY);
            return Event.NONE;
        }
        if (!channeling) return Event.NONE;
        float cap = THRESHOLDS[cleared];
        progress = Math.min(cap, progress + rate());
        if (progress < cap - 1e-6f) return Event.NONE;
        progress = cap;
        window = WINDOW_TICKS;
        return Event.OPENED;
    }

    /** 降下来时砍中一刀：斩掉这一段，它升回去。没降下来时什么都不做。 */
    public Event cut() {
        if (!open() || done()) return Event.NONE;
        cleared++;
        window = 0;
        return Event.CLEARED;
    }

    /** 测试和演示用：直接把进度调到 {@code value}（降下来时不动）。 */
    public void debugProgress(float value) {
        if (!open() && !done()) progress = Math.clamp(value, floor(), THRESHOLDS[cleared]);
    }

    public void setback(float amount) {
        if (!open()) progress = Math.max(floor(), progress - amount);
    }

    public void save(ValueOutput out) {
        out.putFloat("PurifyProgress", progress);
        out.putInt("PurifyCleared", cleared);
        out.putInt("PurifyWindow", window);
        out.putFloat("PurifyScale", scale);
        out.putInt("PurifySegments", SEGMENTS);
    }

    public void load(ValueInput in) {
        scale = Math.max(1, in.getFloatOr("PurifyScale", 1));
        // 三段的旧存档（第 4 步初版）从头净化。
        if (in.getIntOr("PurifySegments", 3) != SEGMENTS) { reset(scale); return; }
        progress = Math.clamp(in.getFloatOr("PurifyProgress", 0), 0, 1);
        cleared = Math.clamp(in.getIntOr("PurifyCleared", 0), 0, SEGMENTS);
        window = Math.clamp(in.getIntOr("PurifyWindow", 0), 0, WINDOW_TICKS);
    }

    /** 客户端用同步来的数还原（血条、净化之光）。 */
    public void sync(float progress, int cleared, int window) {
        this.progress = progress;
        this.cleared = cleared;
        this.window = window;
    }
}
