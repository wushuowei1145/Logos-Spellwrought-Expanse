package dev.logos.dimension.ultimate;

import dev.logos.dimension.cutin.ChainTimeline;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 一位玩家这一轮奥义连携走到哪了（docs/ULTIMATES.md「连携」）。纯逻辑：谁在场、此刻是哪一刻都由调用的人给，好测。
 *
 * <pre>
 * IDLE ──在场只有一位──▶ SINGLE ──演完──▶ IDLE
 * IDLE ──在场两位以上，第一位下令──▶ WINDOW ──窗口里下一位──▶ WINDOW
 * WINDOW ──超时（最后一次出手后 WINDOW 刻、没人在演）──▶ IDLE
 * WINDOW ──在场的全放过了、都演完了──▶ CUTIN（连携切入）──SWAP──▶ CONVERGED（汇流）──按键或超时──▶ FINISHER ──演完──▶ IDLE
 * </pre>
 */
public final class UltimateChain {
    public enum Phase { IDLE, SINGLE, WINDOW, CUTIN, CONVERGED, FINISHER }

    /** {@link #tick} 告诉调用的人这一刻该做什么。 */
    public enum Event {
        NONE,
        /** 窗口过了、没凑齐：调 {@link #reset}。 */
        TIMEOUT,
        /** 在场的全放完了：放连携切入。 */
        CUTIN,
        /** 切入换场了：进汇流，锁住主目标。 */
        CONVERGE,
        /** 汇流时间到了玩家还没按：替他放最后一击（{@link #finish}）。 */
        AUTO_FINISH
    }

    /**
     * 连携里的一段。
     *
     * @param index    第几段（0 起）；玩家的最后一击排在所有同伴后面
     * @param finisher 是不是玩家的最后一击
     * @param members  这一轮在场几位同伴（下令时）
     */
    public record Stage(int index, boolean finisher, int members) {}

    /** 接力窗口（刻）：从上一位出手那一刻算起。 */
    public static final int WINDOW = 140;
    /** 汇流：切入换场后玩家有这么多刻按键放最后一击，过了就替他放。 */
    public static final int CONVERGE = 100;
    /** 切入开始后第几刻进汇流：{@link ChainTimeline#SWAP} 那一拍（光幕盖满、角色甩出）。 */
    public static final int SWAP = (int) Math.ceil(ChainTimeline.SWAP);
    /** 第 1、2、3、4 段的威力。 */
    private static final double[] POWER = {1, 1.25, 1.55, 1.9};
    /** 第 1、2、3、4 段花基准耗魔的几成（逐段打折，免得第三位时断蓝）。 */
    private static final double[] MANA = {1, .85, .7, .55};

    private record Held(RelayGift gift, int from) {}

    private Phase phase = Phase.IDLE;
    private final List<String> released = new ArrayList<>();
    private final List<Held> gifts = new ArrayList<>();
    /** 已经下令、还没演完的段数（同伴的）。 */
    private int pending;
    private long windowEnd, cutinAt, convergedEnd;
    private int members;

    /** 第 {@code index} 段的威力。 */
    public static double power(int index) {
        return POWER[Math.min(index, POWER.length - 1)];
    }

    /** 第 {@code index} 段花基准耗魔的几成。 */
    public static double manaFactor(int index) {
        return MANA[Math.min(index, MANA.length - 1)];
    }

    /** 玩家最后一击的威力：两位同伴 1 倍，三位 1.6 倍（满格），四位 2 倍。 */
    public static double finisherPower(int members) {
        return members >= 4 ? 2 : members == 3 ? 1.6 : 1;
    }

    public Phase phase() {
        return phase;
    }

    /** 这一轮按顺序放过的同伴。 */
    public List<String> released() {
        return List.copyOf(released);
    }

    /** 进切入时在场几位同伴。 */
    public int members() {
        return members;
    }

    /** 此刻能不能给 {@code id} 下令：常态或接力窗口里，这一轮还没放过。 */
    public boolean open(String id) {
        return (phase == Phase.IDLE || phase == Phase.WINDOW) && !released.contains(id);
    }

    /** 此刻下令会是哪一段（{@code present} 是在场几位同伴）。 */
    public Stage next(int present) {
        return new Stage(phase == Phase.WINDOW ? released.size() : 0, false, present);
    }

    /** 给 {@code id} 下令。在场只有一位时是单人奥义：不开窗口、不进连携。 */
    public Stage order(String id, int present, long now) {
        if (!open(id)) throw new IllegalStateException("此刻不能给 " + id + " 下令：" + phase);
        Stage stage = next(present);
        if (present < 2) {
            phase = Phase.SINGLE;
        } else {
            phase = Phase.WINDOW;
            released.add(id);
            windowEnd = Math.max(windowEnd, now + WINDOW);
        }
        pending++;
        return stage;
    }

    /** 某一段出手了：窗口从这一刻重新算。 */
    public void impact(long now) {
        if (phase == Phase.WINDOW) windowEnd = now + WINDOW;
    }

    /** 能不能交出馈赠：只有连携里交（单人奥义没有下一段）。 */
    public boolean relaying() {
        return phase == Phase.WINDOW;
    }

    /** 第 {@code from} 段出手时交出馈赠。 */
    public void give(RelayGift gift, Stage from) {
        gifts.add(new Held(gift, from.index()));
    }

    /** 管得着第 {@code stage} 段的馈赠：前面的段交出来的。 */
    public List<RelayGift> gifts(Stage stage) {
        List<RelayGift> out = new ArrayList<>();
        for (Held h : gifts) if (h.from < stage.index()) out.add(h.gift);
        return out;
    }

    /** 某一段演完（或被打断）：前面交出来的、不再留的馈赠收走；单人奥义演完回到常态。最后一击演完由调用的人 {@link #reset}。 */
    public void ended(Stage stage) {
        if (stage.finisher()) return;
        pending = Math.max(0, pending - 1);
        gifts.removeIf(h -> h.from < stage.index() && !h.gift.keep(stage));
        if (phase == Phase.SINGLE && pending == 0) phase = Phase.IDLE;
    }

    /** 每刻：{@code present} 是此刻在场的同伴。 */
    public Event tick(Collection<String> present, long now) {
        switch (phase) {
            case WINDOW -> {
                if (pending > 0) return Event.NONE;
                if (present.size() >= 2 && released.containsAll(present)) {
                    phase = Phase.CUTIN;
                    cutinAt = now;
                    members = present.size();
                    return Event.CUTIN;
                }
                if (now > windowEnd) return Event.TIMEOUT;
            }
            case CUTIN -> {
                if (now >= cutinAt + SWAP) {
                    phase = Phase.CONVERGED;
                    convergedEnd = now + CONVERGE;
                    return Event.CONVERGE;
                }
            }
            case CONVERGED -> {
                if (now >= convergedEnd) return Event.AUTO_FINISH;
            }
            default -> {}
        }
        return Event.NONE;
    }

    /** 汇流里放最后一击：排在所有同伴后面的那一段。 */
    public Stage finish() {
        if (phase != Phase.CONVERGED) throw new IllegalStateException("没在汇流：" + phase);
        phase = Phase.FINISHER;
        return new Stage(released.size(), true, members);
    }

    /** 回到常态，返回清掉的馈赠（调用的人要对它们调 {@link RelayGift#cleared}）。 */
    public List<RelayGift> reset() {
        List<RelayGift> dropped = new ArrayList<>();
        for (Held h : gifts) dropped.add(h.gift);
        gifts.clear();
        released.clear();
        phase = Phase.IDLE;
        pending = 0;
        members = 0;
        return dropped;
    }

    /** 接力窗口还剩多少刻（有人在演时算满的）。 */
    public int windowLeft(long now) {
        if (phase != Phase.WINDOW) return 0;
        return pending > 0 ? WINDOW : (int) Math.clamp(windowEnd - now, 0, WINDOW);
    }

    /** 汇流还剩多少刻。 */
    public int convergedLeft(long now) {
        return phase == Phase.CONVERGED ? (int) Math.clamp(convergedEnd - now, 0, CONVERGE) : 0;
    }
}
