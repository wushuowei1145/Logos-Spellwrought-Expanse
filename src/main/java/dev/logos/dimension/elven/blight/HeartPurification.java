package dev.logos.dimension.elven.blight;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 公主净化腐败之心的进度和窗口（docs/CORRUPTED_QUEEN_REMAKE.md 第 5 节）。只是数，不碰世界，单元测试直接跑。
 *
 * <ul>
 * <li>公主在祭坛上引导时，进度按阶段的速度涨：阶段二 0 → 66% 约 50 秒，阶段三 66% → 100% 约 25 秒。
 * <li>涨到 33%、66%、100% 时开一次窗口（约 8 秒），进度停住。腐败之心的生命分三段，每个窗口最多打掉一段：
 * 每次命中按最大生命的百分比扣（生命伤害 {@link #LIFE_HIT}，其他 {@link #OTHER_HIT}），乘核心庇护剩下的那部分。
 * <li>窗口里打完这一段：窗口关、进度接着涨。没打完：进度退 15%，再涨到这个刻度时窗口重开；已经打掉的那部分不回。
 * <li>公主倒地时进度每秒退 1%；没人扶、她自己起来时再退 25%（{@link #setback}）。进度最低只退到上一个已经打通的刻度（{@link #floor}）。
 * </ul>
 */
public final class HeartPurification {
    /** 开窗口的进度。 */
    public static final float[] THRESHOLDS = {.33f, .66f, 1f};
    public static final int SEGMENTS = 3;
    /** 单人时每段生命；多人按 {@link #scale} 乘。 */
    public static final float SEGMENT = 300;
    public static final int WINDOW_TICKS = 160;
    public static final float LIFE_HIT = .06f, OTHER_HIT = .02f;
    public static final float FAIL_SETBACK = .15f, DOWNED_DECAY = .01f / 20, SELF_RISE_SETBACK = .25f;
    /** 阶段二、三各自涨完要的刻数。 */
    public static final int PHASE2_TICKS = 1000, PHASE3_TICKS = 500;

    public enum Event { NONE, OPENED, FAILED, CLEARED }

    private float progress, segment = SEGMENT, scale = 1;
    private int cleared, window;

    /** 新的一场：多人时生命按 {@code scale} 乘。 */
    public void reset(float scale) {
        this.scale = Math.max(1, scale);
        progress = 0;
        cleared = 0;
        window = 0;
        segment = segmentMax();
    }

    public float progress() { return progress; }
    /** 已经打掉几段。 */
    public int cleared() { return cleared; }
    /** 窗口还剩几刻，没开为 0。 */
    public int window() { return window; }
    public boolean open() { return window > 0; }
    public boolean done() { return cleared >= SEGMENTS; }
    public float scale() { return scale; }
    public float segmentMax() { return SEGMENT * scale; }
    public float maxHealth() { return segmentMax() * SEGMENTS; }
    /** 当前这一段还剩多少。 */
    public float segment() { return segment; }
    /** 腐败之心整体还剩几成（血条用）。 */
    public float health() { return done() ? 0 : (SEGMENTS - cleared - 1 + segment / segmentMax()) / SEGMENTS; }
    /** 公主身边净化圈的半径（格）。 */
    public static double circle(float progress) { return 3 + 9 * progress; }

    /** 进度最低退到哪：上一个已经打通的刻度（打碎的段不会因为倒地、失败再「没打过」）。 */
    public float floor() { return cleared == 0 ? 0 : THRESHOLDS[Math.min(cleared, SEGMENTS) - 1]; }

    /** 这一阶段每刻涨多少。 */
    public static float rate(int phase) {
        return phase >= 3 ? (THRESHOLDS[2] - THRESHOLDS[1]) / PHASE3_TICKS : THRESHOLDS[1] / PHASE2_TICKS;
    }

    /** 逐刻推进。{@code channeling}：公主正在祭坛上引导；{@code downed}：她倒地了。 */
    public Event tick(int phase, boolean channeling, boolean downed) {
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
        progress = Math.min(cap, progress + rate(phase));
        if (progress < cap - 1e-6f) return Event.NONE;
        progress = cap;
        window = WINDOW_TICKS;
        return Event.OPENED;
    }

    /** 窗口里命中一下：按最大生命的百分比扣，最多扣到这一段为止；{@code ward} 是核心庇护的减伤（0–1）。返回实际扣了多少。 */
    public float hit(boolean life, double ward) {
        if (!open() || done()) return 0;
        float amount = (float) (maxHealth() * (life ? LIFE_HIT : OTHER_HIT) * (1 - Math.clamp(ward, 0, 1)));
        amount = Math.min(amount, segment);
        segment -= amount;
        return amount;
    }

    /** 这一段打完了没有（{@link #hit} 之后看）；打完就关窗口、换下一段。 */
    public Event settle() {
        if (!open() || segment > 1e-4f) return Event.NONE;
        cleared++;
        window = 0;
        segment = done() ? 0 : segmentMax();
        return Event.CLEARED;
    }

    /** 测试和演示用：直接把进度调到 {@code value}（窗口开着时不动）。 */
    public void debugProgress(float value) {
        if (!open()) progress = Math.clamp(value, 0, THRESHOLDS[Math.min(cleared, SEGMENTS - 1)]);
    }

    public void setback(float amount) {
        if (!open()) progress = Math.max(floor(), progress - amount);
    }

    public void save(ValueOutput out) {
        out.putFloat("PurifyProgress", progress);
        out.putInt("PurifyCleared", cleared);
        out.putFloat("PurifySegment", segment);
        out.putInt("PurifyWindow", window);
        out.putFloat("PurifyScale", scale);
    }

    public void load(ValueInput in) {
        scale = Math.max(1, in.getFloatOr("PurifyScale", 1));
        progress = Math.clamp(in.getFloatOr("PurifyProgress", 0), 0, 1);
        cleared = Math.clamp(in.getIntOr("PurifyCleared", 0), 0, SEGMENTS);
        segment = Math.clamp(in.getFloatOr("PurifySegment", segmentMax()), 0, segmentMax());
        window = Math.clamp(in.getIntOr("PurifyWindow", 0), 0, WINDOW_TICKS);
    }

    /** 客户端用同步来的数还原（血条、净化圈）。 */
    public void sync(float progress, int cleared, float segmentFraction, int window) {
        this.progress = progress;
        this.cleared = cleared;
        this.segment = segmentFraction * segmentMax();
        this.window = window;
    }
}
