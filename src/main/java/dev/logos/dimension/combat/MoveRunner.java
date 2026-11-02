package dev.logos.dimension.combat;

/**
 * 一名角色身上运行的招式状态机，只依赖游戏刻与攻速，玩家与 NPC 共用。
 * 服务端逐刻调用 {@link #tick}，收到输入时调用 {@link #input}；换招、接触与结束通过 {@link Actor} 回调。
 */
public final class MoveRunner {
    /** 使用招式的角色：提供攻速，接收换招与接触。 */
    public interface Actor {
        double attackSpeed();
        void moveStarted(MoveRunner runner);
        void contact(MoveRunner runner);
    }
    /** 刻数比较的容差，避免 0.625×20 之类的小数误差错过边界。 */
    private static final double EPSILON = 1e-6;
    private final Moveset set;
    private final Actor actor;
    private Moveset.Move move;
    private double start, rate = 1, offset;
    private boolean hit, cleared;
    private Moveset.Transition queued;
    private String comboMove;
    private double comboTime;

    public MoveRunner(Moveset set, Actor actor, double now) {
        this.set = set; this.actor = actor;
        begin(set.move(set.start()), now);
    }

    public Moveset moveset() { return set; }
    public Moveset.Move move() { return move; }
    public double start() { return start; }
    public double rate() { return rate; }
    public double offset() { return offset; }
    public boolean cleared() { return cleared; }
    public double age(double now) { return now - start; }
    /** 当前招式已经播放的动作时间（秒），不取模、不截断。 */
    private double elapsed(double now) { return Math.max(0, now - start) / 20 * rate; }

    /** 处理一次输入；接受（立即切换或排队）时返回 true。 */
    public boolean input(Moveset.Input input, double now) {
        if (cleared) return false;
        double t = elapsed(now);
        for (var transition : move.on()) {
            if (transition.input() != input || t + EPSILON < transition.from() || t - EPSILON > transition.until()) continue;
            if (transition.queue()) queued = transition;
            else follow(transition, now);
            return true;
        }
        return false;
    }

    /** 逐刻推进：先结算接触，再处理播完后的排队转移或默认去向。 */
    public void tick(double now) {
        if (cleared) return;
        double age = now - start;
        if (move.hasContact() && !hit && age + EPSILON >= move.contact() * 20 / rate) {
            hit = true;
            actor.contact(this);
        }
        double duration = move.durationTicks(rate, offset);
        if (age + EPSILON < duration) return;
        // 排队的输入在原招式结束的那一刻接上，不等到下一刻，连段节奏不因刻边界漂移。
        if (queued != null) follow(queued, start + duration);
        else go(move.next(), false, now);
    }

    /** 不经转移规则直接切换，例如换下武器时收剑。 */
    public void force(String name, double now) {
        if (!cleared && set.move(name) != null) begin(set.move(name), now);
    }

    private void follow(Moveset.Transition transition, double time) { go(transition.to(), transition.combo(), time); }

    private void go(String target, boolean combo, double time) {
        if (Moveset.CLEAR.equals(target)) { cleared = true; actor.moveStarted(this); return; }
        if (combo && comboMove != null && time - comboTime <= set.comboMemory() * 20 + EPSILON) {
            var next = set.move(comboMove).comboNext();
            if (next != null) target = next;
        }
        begin(set.move(target), time);
    }

    private void begin(Moveset.Move next, double time) {
        // 倒放招式从上一招式已播放的位置退回，例如蓄力未满时松手。
        double from = move == null ? 0 : move.seconds(time - start, rate, offset);
        move = next; start = time; hit = false; queued = null;
        rate = next.scalesWithAttackSpeed() ? Math.clamp(actor.attackSpeed() / next.speedBase(), .2, 4) : 1;
        offset = next.playback() == Moveset.Playback.REVERSE ? from : 0;
        if (next.resetCombo()) comboMove = null;
        if (next.comboNext() != null) { comboMove = next.name(); comboTime = time; }
        actor.moveStarted(this);
    }
}
