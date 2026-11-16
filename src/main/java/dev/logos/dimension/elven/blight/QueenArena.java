package dev.logos.dimension.elven.blight;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;

/**
 * 树心战场的标记点，读自 {@code data/logos_dimension/queen_arena.json}（由 tools/blighted_world_tree_arena.py 和模板一起生成）。
 * 坐标相对腐败之心方块的底面中心、模板朝向 south；{@link #at} 按方块的 {@code facing} 转到世界里，y 是站立高度。
 */
public record QueenArena(double hallRadius, Vec3 altar, List<Vec3> tunnels, Vec3 entrance, Vec3 gate, List<Vec3> exitWalk,
                         double galleryStand, double galleryInner, double galleryOuter) {
    /** 文件缺失时的后备，同 2026-11 生成的模板。 */
    public static final QueenArena DEFAULT = new QueenArena(24, new Vec3(0, -4, 18.5),
            List.of(new Vec3(19.09, -7, 19.09), new Vec3(-27, -7, 0), new Vec3(19.09, -7, -19.09)),
            new Vec3(0, -7, -23), new Vec3(0, -8, -39), List.of(new Vec3(0, -7, -8), new Vec3(0, -7, -21)), 2, 20.8, 24);

    private static ResourceManager cachedFor;
    private static QueenArena cached = DEFAULT;

    public static synchronized QueenArena get(MinecraftServer server) {
        var manager = server.getResourceManager();
        if (manager != cachedFor) {
            cachedFor = manager;
            try (var reader = manager.getResourceOrThrow(LogosDimension.id("queen_arena.json")).openAsReader()) {
                cached = parse(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (Exception e) {
                LogUtils.getLogger().error("树心战场标记读取失败，用内置的后备", e);
                cached = DEFAULT;
            }
        }
        return cached;
    }

    public static QueenArena parse(JsonObject json) {
        List<Vec3> tunnels = new ArrayList<>();
        for (var t : json.getAsJsonArray("tunnels")) tunnels.add(vec(t.getAsJsonArray()));
        List<Vec3> walk = new ArrayList<>();
        for (var t : json.getAsJsonArray("exit_walk")) walk.add(vec(t.getAsJsonArray()));
        JsonObject gallery = json.getAsJsonObject("gallery");
        return new QueenArena(json.get("hall_radius").getAsDouble(), vec(json.getAsJsonArray("altar")), List.copyOf(tunnels),
                vec(json.getAsJsonArray("entrance")), vec(json.getAsJsonArray("gate")), List.copyOf(walk),
                gallery.get("stand").getAsDouble(), gallery.get("inner").getAsDouble(), gallery.get("outer").getAsDouble());
    }

    private static Vec3 vec(JsonArray a) {
        return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }

    /** 模板坐标 {@code marker} 在世界里的位置：绕腐败之心转到它的朝向（同 QueenHeartRenderer 的血管接头）。 */
    public static Vec3 at(BlockPos heart, Direction facing, Vec3 marker) {
        double angle = Math.toRadians(facing.toYRot()), c = Math.cos(angle), s = Math.sin(angle);
        return new Vec3(heart.getX() + .5 + c * marker.x - s * marker.z, heart.getY() + marker.y, heart.getZ() + .5 + s * marker.x + c * marker.z);
    }

    /** 模板里的方向转到世界里。 */
    public static Vec3 direction(Direction facing, Vec3 v) {
        double angle = Math.toRadians(facing.toYRot()), c = Math.cos(angle), s = Math.sin(angle);
        return new Vec3(c * v.x - s * v.z, v.y, s * v.x + c * v.z);
    }
}
