package dev.logos.dimension.elven.blight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 腐化女王和腐败之心招式文件里的 Boss 专用字段（同 {@code ShiraumeMoves.Extra} 的做法）：命中形状、法术与耗魔、留下的污秽、出招顺序。
 * 代码里只留形状的种类（{@link Shape#hits}），数都在 {@code combat/movesets/corrupted_queen.json} 和 {@code queen_core.json} 里。
 */
public final class QueenMoves {
    /**
     * 一招的命中形状。{@code kind}：{@code cone}（扇形：半径 {@code reach}、半角 {@code cone} 度）、{@code ring}（从中心往外扩的环：
     * 每秒扩 {@code speed} 格、环宽 {@code band}，最远 {@code reach}，朝向两侧 {@code gap} 度内是缺口）、{@code line}（直线：长 {@code reach}、半宽 {@code width}）、
     * {@code spell}（交给 Logos 载页 {@code spell}，耗魔 {@code mana}）。{@code filth}：打完在地上留几块腐败污秽。
     */
    public record Shape(String kind, double reach, double cone, double width, double speed, double band, double gap, double height,
                        @Nullable String spell, double mana, int filth) {
        public static final Shape NONE = new Shape("", 0, 0, 0, 0, 0, 0, 2, null, 0, 0);

        /** 环形招式在 {@code seconds} 秒时的半径。 */
        public double ring(double seconds) {
            return Math.min(reach, seconds * speed + 1);
        }

        /**
         * {@code target} 的脚在不在这一下的范围里。{@code origin} 是出招时的位置，{@code aim} 是水平单位朝向，
         * {@code seconds} 是从第一次接触算起过了几秒（环形用）。
         */
        public boolean hits(Vec3 origin, Vec3 aim, Vec3 target, double seconds) {
            Vec3 d = target.subtract(origin);
            if (d.y < -1 || d.y > height) return false;
            double flat = Math.hypot(d.x, d.z);
            return switch (kind) {
                case "cone" -> flat <= reach && (flat < .5 || (d.x * aim.x + d.z * aim.z) / flat >= Math.cos(Math.toRadians(cone)));
                case "ring" -> Math.abs(flat - ring(seconds)) < band && (gap <= 0 || flat < .5
                        || (d.x * aim.x + d.z * aim.z) / flat < Math.cos(Math.toRadians(gap)));
                case "line" -> {
                    double along = d.x * aim.x + d.z * aim.z, across = Math.abs(d.x * aim.z - d.z * aim.x);
                    yield along > 0 && along < reach && across < width;
                }
                default -> false;
            };
        }
    }

    /** 出招顺序：贴身时每隔 {@code closeEvery} 次出 {@code close}，平时按 {@code rotation} 轮换，魔力不够时出 {@code fallback}。 */
    public record Plan(List<String> rotation, @Nullable String close, double closeRange, int closeEvery, @Nullable String fallback) {
        public static final Plan NONE = new Plan(List.of(), null, 0, 1, null);

        public String pick(int sequence, double distance) {
            if (close != null && distance < closeRange && sequence % Math.max(1, closeEvery) == 0) return close;
            return rotation.isEmpty() ? (close == null ? "" : close) : rotation.get(Math.floorMod(sequence, rotation.size()));
        }
    }

    public record Set(Map<String, Shape> shapes, Plan plan) {
        public Shape shape(String move) { return shapes.getOrDefault(move, Shape.NONE); }
    }

    private static final Set EMPTY = new Set(Map.of(), Plan.NONE);
    private static ResourceManager cachedFor;
    private static final Map<Identifier, Optional<Set>> CACHE = new HashMap<>();

    private QueenMoves() {}

    public static synchronized Set of(MinecraftServer server, Identifier moveset) {
        var manager = server.getResourceManager();
        if (manager != cachedFor) { CACHE.clear(); cachedFor = manager; }
        return CACHE.computeIfAbsent(moveset, id -> {
            try (var reader = manager.getResourceOrThrow(id.withPath(p -> "combat/movesets/" + p + ".json")).openAsReader()) {
                return Optional.of(parse(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception e) {
                LogUtils.getLogger().error("Boss 招式 {} 读取失败", id, e);
                return Optional.empty();
            }
        }).orElse(EMPTY);
    }

    public static Set parse(JsonObject json) {
        Map<String, Shape> shapes = new HashMap<>();
        for (var entry : json.getAsJsonObject("moves").entrySet()) {
            JsonObject m = entry.getValue().getAsJsonObject();
            if (!m.has("shape")) continue;
            shapes.put(entry.getKey(), new Shape(m.get("shape").getAsString(), number(m, "reach", 0), number(m, "cone", 60), number(m, "width", 1),
                    number(m, "speed", 0), number(m, "band", .65), number(m, "gap", 0), number(m, "height", 2),
                    m.has("spell") ? m.get("spell").getAsString() : null, number(m, "mana", 0), (int) number(m, "filth", 0)));
        }
        Plan plan = Plan.NONE;
        if (json.has("boss")) {
            JsonObject b = json.getAsJsonObject("boss");
            List<String> rotation = new ArrayList<>();
            if (b.has("rotation")) for (var v : b.getAsJsonArray("rotation")) rotation.add(v.getAsString());
            plan = new Plan(List.copyOf(rotation), b.has("close") ? b.get("close").getAsString() : null, number(b, "close_range", 0),
                    (int) number(b, "close_every", 1), b.has("fallback") ? b.get("fallback").getAsString() : null);
        }
        return new Set(Map.copyOf(shapes), plan);
    }

    private static double number(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }
}
