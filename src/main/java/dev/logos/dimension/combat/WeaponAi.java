package dev.logos.dimension.combat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 招式文件里给 AI 看的那一份（docs/WEAPON_AI.md 第 3 节）：顶层的 {@code ai}（哪一族、动作前缀、惯用距离、偏好）和每一招的
 * {@code ai}（用途、距离、条件、分数）。和 {@link Moveset} 读自同一份文件（{@code extends} 展开以后），没写 {@code ai} 的招式文件
 * 就是 {@link #NONE}：AI 不会用这把武器。
 *
 * @param moves 有用法的招式 → 几种用法（取分数最高的那一种）
 * @param off   标了 {@code "ai": false} 的招式：AI 不用（玩家专用）
 */
public record WeaponAi(@Nullable String family, @Nullable String clips, double keepMin, double keepMax, Map<String, Double> prefer,
                       Map<String, List<Use>> moves, Set<String> off) {
    public static final WeaponAi NONE = new WeaponAi(null, null, Double.NaN, Double.NaN, Map.of(), Map.of(), Set.of());
    /** 用途（{@code role}）的词表。 */
    public static final Set<String> ROLES = Set.of("opener", "combo", "finisher", "gap_close", "aoe", "launcher", "juggle", "ranged", "guard",
            "counter", "reload", "buff");
    /** 目标条件（{@code target}、{@code likes}）的词表；{@code likes} 另有 {@code crowd}。 */
    public static final Set<String> CONDITIONS = Set.of("airborne", "grounded", "strong", "fleeing", "above", "staggered");
    /** {@code via} 里能按的。 */
    public static final Set<String> PRESSES = Set.of("attack", "attack_release", "use_press", "use_release", "reload", "jump");
    /** 不写 {@code weight} 时的底分。 */
    public static final double WEIGHT = .5;

    /** 至少几个敌人：{@code radius} 格内、正前方 {@code arc} 度里。 */
    public record Crowd(int count, double radius, double arc) {}

    /** {@code via} 的一步：按一下（{@code press}），或者按着现在按着的等几秒（{@code hold}）。 */
    public record Step(@Nullable String press, double hold) {}

    /**
     * 一招的一种用法。
     *
     * @param min      距离下限（格，她脚下到目标脚下）
     * @param max      距离上限；NaN 表示「够得着」（她的 {@link Wielder#reach} 加目标半宽）
     * @param cooldown 软冷却（秒）
     * @param via      代码里硬切、转移图里找不到的按法；空表示从转移图找
     */
    public record Use(Set<String> roles, double min, double max, @Nullable Crowd crowd, Set<String> target, Map<String, Double> likes,
                      double weight, Map<String, Double> after, double cooldown, List<Step> via) {}

    public boolean usable() {
        return !moves.isEmpty();
    }

    /** 和招式对一下：写了用法、标了不用的招都要在招式文件里，{@code after} 指向的招也是。返回自己。 */
    public WeaponAi check(Moveset set) {
        for (var e : moves.entrySet()) {
            if (set.move(e.getKey()) == null) throw new IllegalArgumentException("ai 写在不存在的招式 " + e.getKey() + " 上");
            for (Use u : e.getValue()) for (String a : u.after().keySet())
                if (set.move(a) == null) throw new IllegalArgumentException(e.getKey() + " 的 ai.after 指向不存在的招式 " + a);
        }
        for (String m : off) if (set.move(m) == null) throw new IllegalArgumentException("ai: false 写在不存在的招式 " + m + " 上");
        return this;
    }

    public List<Use> uses(String move) {
        return off.contains(move) ? List.of() : moves.getOrDefault(move, List.of());
    }

    public double prefer(Set<String> roles) {
        double p = 0;
        for (String r : roles) p += prefer.getOrDefault(r, 0.0);
        return p;
    }

    /** 读（{@code json} 是 {@code extends} 展开以后的整份招式文件）；格式错误抛出带原因的 IllegalArgumentException。 */
    public static WeaponAi parse(JsonObject json) {
        String family = null, clips = null;
        double keepMin = Double.NaN, keepMax = Double.NaN;
        Map<String, Double> prefer = Map.of();
        if (json.has("ai")) {
            JsonObject top = json.getAsJsonObject("ai");
            family = string(top, "family");
            clips = string(top, "clips");
            if (top.has("keep")) {
                double[] k = pair(top.get("keep"), "顶层 ai.keep");
                keepMin = k[0];
                keepMax = k[1];
            }
            prefer = numbers(top, "prefer", "顶层 ai.prefer", ROLES);
        }
        Map<String, List<Use>> moves = new LinkedHashMap<>();
        Set<String> off = new java.util.HashSet<>();
        JsonObject movesJson = json.getAsJsonObject("moves");
        if (movesJson != null) for (var entry : movesJson.entrySet()) {
            JsonObject m = entry.getValue().getAsJsonObject();
            if (!m.has("ai")) continue;
            String name = entry.getKey();
            JsonElement ai = m.get("ai");
            if (ai.isJsonPrimitive() && ai.getAsJsonPrimitive().isBoolean()) {
                if (!ai.getAsBoolean()) off.add(name);
                else throw new IllegalArgumentException(name + " 的 ai 只能写 false、一种用法或几种用法");
                continue;
            }
            List<Use> uses = new ArrayList<>();
            if (ai.isJsonArray()) for (JsonElement u : ai.getAsJsonArray()) uses.add(use(name, u.getAsJsonObject()));
            else uses.add(use(name, ai.getAsJsonObject()));
            if (uses.isEmpty()) throw new IllegalArgumentException(name + " 的 ai 是空的");
            moves.put(name, List.copyOf(uses));
        }
        return new WeaponAi(family, clips, keepMin, keepMax, Map.copyOf(prefer), Map.copyOf(moves), Set.copyOf(off));
    }

    private static Use use(String move, JsonObject u) {
        String where = move + " 的 ai";
        Set<String> roles = words(u.get("role"), where + ".role", ROLES);
        if (roles.isEmpty()) throw new IllegalArgumentException(where + " 缺少 role");
        double min = 0, max = Double.NaN;
        if (u.has("range")) {
            double[] r = pair(u.get("range"), where + ".range");
            min = r[0];
            max = r[1];
        }
        Crowd crowd = null;
        if (u.has("crowd")) {
            JsonObject c = u.getAsJsonObject("crowd");
            int count = c.get("count").getAsInt();
            double radius = c.has("radius") ? c.get("radius").getAsDouble() : Double.isNaN(max) ? 4 : max;
            double arc = c.has("arc") ? c.get("arc").getAsDouble() : 360;
            if (count < 1 || radius <= 0 || arc <= 0 || arc > 360) throw new IllegalArgumentException(where + ".crowd 不对");
            crowd = new Crowd(count, radius, arc);
        }
        Set<String> target = u.has("target") ? words(u.get("target"), where + ".target", CONDITIONS) : Set.of();
        Set<String> likeKeys = new java.util.HashSet<>(CONDITIONS);
        likeKeys.add("crowd");
        Map<String, Double> likes = numbers(u, "likes", where + ".likes", likeKeys);
        double weight = u.has("weight") ? u.get("weight").getAsDouble() : WEIGHT;
        Map<String, Double> after = numbers(u, "after", where + ".after", null);
        double cooldown = u.has("cooldown") ? u.get("cooldown").getAsDouble() : 0;
        if (cooldown < 0) throw new IllegalArgumentException(where + ".cooldown 不能为负");
        List<Step> via = new ArrayList<>();
        if (u.has("via")) for (JsonElement s : u.getAsJsonArray("via")) {
            if (s.isJsonPrimitive()) {
                String press = s.getAsString().toLowerCase(Locale.ROOT);
                if (!PRESSES.contains(press)) throw new IllegalArgumentException(where + ".via 不认识 " + press);
                via.add(new Step(press, 0));
            } else {
                double hold = s.getAsJsonObject().get("hold").getAsDouble();
                if (hold <= 0) throw new IllegalArgumentException(where + ".via 的 hold 要大于 0");
                via.add(new Step(null, hold));
            }
        }
        return new Use(roles, min, max, crowd, target, likes, weight, after, cooldown, List.copyOf(via));
    }

    private static @Nullable String string(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static double[] pair(JsonElement e, String where) {
        var a = e.getAsJsonArray();
        if (a.size() != 2) throw new IllegalArgumentException(where + " 要写 [近, 远]");
        double lo = a.get(0).getAsDouble(), hi = a.get(1).getAsDouble();
        if (lo < 0 || hi < lo) throw new IllegalArgumentException(where + " 要 0 ≤ 近 ≤ 远");
        return new double[]{lo, hi};
    }

    private static Set<String> words(@Nullable JsonElement e, String where, Set<String> allowed) {
        if (e == null) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        if (e.isJsonArray()) for (JsonElement w : e.getAsJsonArray()) out.add(w.getAsString());
        else out.add(e.getAsString());
        for (String w : out) if (!allowed.contains(w)) throw new IllegalArgumentException(where + " 不认识 " + w);
        return Set.copyOf(out);
    }

    private static Map<String, Double> numbers(JsonObject o, String key, String where, @Nullable Set<String> allowed) {
        if (!o.has(key)) return Map.of();
        Map<String, Double> out = new LinkedHashMap<>();
        for (var entry : o.getAsJsonObject(key).entrySet()) {
            if (allowed != null && !allowed.contains(entry.getKey())) throw new IllegalArgumentException(where + " 不认识 " + entry.getKey());
            out.put(entry.getKey(), entry.getValue().getAsDouble());
        }
        return Map.copyOf(out);
    }
}
