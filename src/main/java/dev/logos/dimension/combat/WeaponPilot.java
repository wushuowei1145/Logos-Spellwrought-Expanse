package dev.logos.dimension.combat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;

/**
 * 驾驶员（docs/WEAPON_AI.md 第 2.3 节）：照招式文件里的 {@code ai} 挑一招（{@link #pick}），再沿转移图一下一下按过去（{@link #go}、
 * {@link #tick}）。同伴的心智决定打不打、站哪里、打谁、一串打几招；驾驶员只管这一招打哪个、怎么按。
 */
public final class WeaponPilot {
    /** 接在上一招后面（上一招有缓冲的左键转移到它）加多少分。 */
    public static final double SUCCESSOR = .5;
    /** 刚放过的那一招罚多少（往前每一招减半）；记几招。 */
    public static final double REPEAT = .45;
    public static final int RECENT = 4;
    /** 抖动的幅度（±一半）；低于多少分就不打。 */
    public static final double JITTER = .1, THRESHOLD = .2;
    /** 策略的偏好：优先近战的突进、优先远程的远程各加多少。 */
    public static final double TACTIC_MELEE = .15, TACTIC_RANGED = .2;
    /** 强敌：最大生命这么多以上。 */
    public static final double STRONG = 40;
    /** {@code likes.crowd}：身边这么多格里有这么多个敌人。 */
    public static final double CROWD_RADIUS = 3.5;
    public static final int CROWD_COUNT = 3;
    /** 转移图最多找几步；按过去最多等几刻（过了就算了，重新挑）。 */
    static final int DEPTH = 3, PATIENCE = 30;

    /** 同伴心智告诉驾驶员的处境。 */
    public interface Situation {
        /** {@code radius} 格内、正前方 {@code arc} 度里冲着她或你来的敌人有几个。 */
        int crowd(double radius, double arc);

        /** 角色自己的条件（例如拉塞塔的坠星要第四印）：这一招现在能不能用。 */
        default boolean allows(String move) {
            return true;
        }

        /** 角色和策略的偏好：这些用途另加多少分。 */
        default double prefer(Set<String> roles) {
            return 0;
        }
    }

    /** 转移图上的一步：按一下 {@code input}（带前提 {@code require}），或者等这一招播完（{@code input} 为 null）。 */
    public record Edge(Moveset.@Nullable Input input, @Nullable String require, String to) {}

    private final NpcWeapon hands;
    private final Deque<String> recent = new ArrayDeque<>();
    private final Map<String, Long> ready = new HashMap<>();
    private @Nullable String goal;
    private long goalUntil;
    private List<WeaponAi.Step> via = List.of();
    private int viaAt;
    private long viaWait = -1;

    public WeaponPilot(NpcWeapon hands) {
        this.hands = hands;
        hands.onMove(this::started);
    }

    public @Nullable String goal() {
        return goal;
    }

    /** 最近放过的几招，最近的在前。 */
    public Iterable<String> recent() {
        return recent;
    }

    public void clear() {
        goal = null;
        via = List.of();
        viaWait = -1;
    }

    // ---------------------------------------------------------------- 挑

    /**
     * 挑一招：这一刻走得到的每一招，用法的条件都满足、没在软冷却里的，打分（第 2.3 节的式子），取最高的；低于 {@link #THRESHOLD} 返回 null。
     * {@code d} 是她到目标的距离（脚下到脚下）。
     */
    public @Nullable String pick(LivingEntity target, double d, Situation s, RandomSource random, double tactic) {
        MoveRunner runner = hands.runner();
        if (runner == null) return null;
        WeaponAi ai = hands.ai();
        long now = hands.wielder().entity().level().getGameTime();
        String prev = recent.peekFirst();
        String best = null;
        double top = THRESHOLD;
        for (var e : ai.moves().entrySet()) {
            String m = e.getKey();
            if (ai.off().contains(m) || !s.allows(m) || now < ready.getOrDefault(m, 0L)) continue;
            double score = Double.NEGATIVE_INFINITY;
            for (WeaponAi.Use u : e.getValue()) score = Math.max(score, score(u, m, target, d, s, prev, tactic));
            if (score == Double.NEGATIVE_INFINITY) continue;
            boolean hasVia = e.getValue().stream().anyMatch(u -> !u.via().isEmpty());
            if (!hasVia && !m.equals(runner.move().name()) && route(runner, m, now, this::reachable) == null) continue;
            score += (random.nextDouble() - .5) * JITTER;
            if (score > top) {
                top = score;
                best = m;
            }
        }
        return best;
    }

    /** 一种用法的分数；条件不满足为负无穷。{@code tactic}：优先近战为正、优先远程为负、随机应变为 0。 */
    double score(WeaponAi.Use u, String move, LivingEntity target, double d, Situation s, @Nullable String prev, double tactic) {
        LivingEntity self = hands.wielder().entity();
        double max = Double.isNaN(u.max()) ? hands.wielder().reach() + target.getBbWidth() * .5 : u.max();
        if (d < u.min() || d > max) return Double.NEGATIVE_INFINITY;
        if (u.crowd() != null && s.crowd(u.crowd().radius(), u.crowd().arc()) < u.crowd().count()) return Double.NEGATIVE_INFINITY;
        for (String c : u.target()) if (!holds(c, self, target)) return Double.NEGATIVE_INFINITY;
        double score = u.weight() + hands.ai().prefer(u.roles()) + s.prefer(u.roles());
        for (var like : u.likes().entrySet()) {
            boolean yes = "crowd".equals(like.getKey()) ? s.crowd(CROWD_RADIUS, 360) >= CROWD_COUNT : holds(like.getKey(), self, target);
            if (yes) score += like.getValue();
        }
        if (tactic > 0 && u.roles().contains("gap_close")) score += TACTIC_MELEE;
        if (tactic < 0 && u.roles().contains("ranged")) score += TACTIC_RANGED;
        if (prev != null) {
            MoveRunner runner = hands.runner();
            if (runner != null && successor(runner.moveset(), prev, move)) score += SUCCESSOR;
            score += u.after().getOrDefault(prev, 0.0);
        }
        return score - repeat(recent, move);
    }

    /** 目标条件（{@link WeaponAi#CONDITIONS}）。 */
    boolean holds(String condition, LivingEntity self, LivingEntity target) {
        double rise = target.getY() - self.getY();
        return switch (condition) {
            case "airborne" -> Airborne.is(target) || !target.onGround() && !target.isInWater() && !target.onClimbable();
            case "grounded" -> target.onGround() && Math.abs(rise) < 2;
            case "strong" -> target.getMaxHealth() >= STRONG;
            case "fleeing" -> target.getDeltaMovement().horizontalDistanceSqr() > .004
                    && target.getDeltaMovement().dot(target.position().subtract(self.position())) > 0;
            case "above" -> rise >= 2 && rise <= 5;
            case "staggered" -> hands.parriedFoe() == target;
            default -> false;
        };
    }

    /** 上一招有缓冲的左键转移到这一招（连段的下一式、突刺接旋风……）。 */
    public static boolean successor(Moveset set, String prev, String move) {
        Moveset.Move p = set.move(prev);
        if (p == null) return false;
        for (var t : p.on()) if (t.input() == Moveset.Input.ATTACK && t.queue() && t.to().equals(move)) return true;
        return false;
    }

    /** 刚放过的罚分：{@code recent} 从最近一招往前，最近一招 {@link #REPEAT}，往前每一招减半。 */
    public static double repeat(Iterable<String> recent, String move) {
        double p = 0, k = REPEAT;
        for (String m : recent) {
            if (m.equals(move)) p += k;
            k /= 2;
        }
        return p;
    }

    // ---------------------------------------------------------------- 按

    /** 去放 {@code move}：转移图里走得到（或写了 {@code via}）时记下来、返回真，之后每刻 {@link #tick} 按过去。 */
    public boolean go(String move) {
        MoveRunner runner = hands.runner();
        if (runner == null || runner.moveset().move(move) == null) return false;
        long now = hands.wielder().entity().level().getGameTime();
        List<WeaponAi.Step> steps = List.of();
        for (WeaponAi.Use u : hands.ai().uses(move)) if (!u.via().isEmpty()) steps = u.via();
        if (steps.isEmpty() && route(runner, move, now, this::reachable) == null) return false;
        goal = move;
        goalUntil = now + PATIENCE;
        via = steps;
        viaAt = 0;
        viaWait = -1;
        tick();
        return true;
    }

    /** 每刻：照着去 {@link #goal} 的路按下一下（等到时间窗、先打开身法开关、要空中先跳）。 */
    public void tick() {
        MoveRunner runner = hands.runner();
        if (goal == null) return;
        long now = hands.wielder().entity().level().getGameTime();
        if (runner == null || now > goalUntil) {
            clear();
            return;
        }
        if (!via.isEmpty()) {
            followVia(now);
            return;
        }
        List<Edge> path = route(runner, goal, now, this::reachable);
        if (path == null) {
            clear();
            return;
        }
        if (path.isEmpty()) return;
        Edge first = path.getFirst();
        if (first.input() == null) return;
        press(runner, first, now);
    }

    private void press(MoveRunner runner, Edge e, long now) {
        LivingEntity self = hands.wielder().entity();
        if (e.require() != null) switch (e.require()) {
            case "airborne" -> {
                if (!hands.wielder().airborne()) {
                    if (self.onGround() && self instanceof Mob mob) mob.getJumpControl().jump();
                    return;
                }
            }
            case "sneak", "sprint" -> hands.intend(e.require());
            default -> {}
        }
        switch (e.input()) {
            case USE_PRESS -> hands.hold(true);
            case USE_RELEASE -> hands.hold(false);
            default -> hands.press(e.input());
        }
    }

    private void followVia(long now) {
        if (viaAt >= via.size()) return;
        WeaponAi.Step step = via.get(viaAt);
        if (step.press() == null) {
            if (viaWait < 0) viaWait = now + Math.round(step.hold() * 20);
            if (now < viaWait) return;
            viaWait = -1;
            viaAt++;
            return;
        }
        switch (step.press()) {
            case "use_press" -> hands.hold(true);
            case "use_release" -> hands.hold(false);
            case "jump" -> {
                if (hands.wielder().entity() instanceof Mob mob) mob.getJumpControl().jump();
            }
            case "attack" -> hands.press(Moveset.Input.ATTACK);
            case "attack_release" -> hands.press(Moveset.Input.ATTACK_RELEASE);
            case "reload" -> hands.press(Moveset.Input.RELOAD);
            default -> {}
        }
        viaAt++;
    }

    /** 一招开始了：到了要去的那一招就算放出来了（记下、软冷却）；不需要按着右键的招，到了就松开。 */
    private void started(Moveset.Move m) {
        long now = hands.wielder().entity().level().getGameTime();
        if (!hands.ai().uses(m.name()).isEmpty()) {
            recent.addFirst(m.name());
            while (recent.size() > RECENT) recent.removeLast();
            double cd = 0;
            for (WeaponAi.Use u : hands.ai().uses(m.name())) cd = Math.max(cd, u.cooldown());
            if (cd > 0) ready.put(m.name(), now + Math.round(cd * 20));
        }
        if (m.name().equals(goal)) {
            clear();
            if (!m.accepts(Moveset.Input.USE_RELEASE)) hands.hold(false);
        }
    }

    /** 这一招有没有在软冷却里。 */
    public boolean cooling(String move) {
        return hands.wielder().entity().level().getGameTime() < ready.getOrDefault(move, 0L);
    }

    /**
     * 从当前这一招沿转移图走到 {@code to}（最多 {@link #DEPTH} 步）：每一步是按一下或等这一招播完走 {@code next}。到不了返回 null，
     * 已经在 {@code to} 返回空表。第一步看现在的时间窗（过了的不算）和连招记忆；走得到的前提：空中（先跳）、潜行、疾跑，其余看现在满不满足。
     */
    public static @Nullable List<Edge> route(MoveRunner runner, String to, double now, Predicate<String> reachable) {
        String from = runner.move().name();
        if (from.equals(to)) return List.of();
        Moveset set = runner.moveset();
        double t = runner.time(now);
        record Node(String move, List<Edge> path) {}
        Deque<Node> open = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        open.add(new Node(from, List.of()));
        seen.add(from);
        while (!open.isEmpty()) {
            Node n = open.poll();
            if (n.path.size() >= DEPTH) continue;
            boolean here = n.path.isEmpty();
            Moveset.Move m = set.move(n.move);
            List<Edge> out = new ArrayList<>();
            for (var tr : m.on()) {
                if (tr.input() == Moveset.Input.BLOCK || Moveset.CLEAR.equals(tr.to())) continue;
                if (here && t - 1e-6 > tr.until()) continue;
                if (tr.require() != null && !reachable.test(tr.require())) continue;
                out.add(new Edge(tr.input(), tr.require(), here ? runner.destination(tr, now) : tr.to()));
            }
            if (m.playback() != Moveset.Playback.LOOP && m.next() != null && !Moveset.CLEAR.equals(m.next()))
                out.add(new Edge(null, null, m.next()));
            for (Edge e : out) {
                if (set.move(e.to()) == null) continue;
                List<Edge> path = new ArrayList<>(n.path);
                path.add(e);
                if (e.to().equals(to)) return path;
                if (seen.add(e.to())) open.add(new Node(e.to(), path));
            }
        }
        return null;
    }

    private boolean reachable(String require) {
        return switch (require) {
            case "airborne" -> hands.wielder().airborne() || hands.wielder().entity().onGround();
            case "sneak", "sprint" -> true;
            default -> hands.allows(require);
        };
    }
}
