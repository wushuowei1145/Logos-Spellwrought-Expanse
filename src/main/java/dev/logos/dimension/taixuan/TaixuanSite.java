package dev.logos.dimension.taixuan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.LogosDimension;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 已经生成好的一处太玄灵境：结构起点、起点锚在世界里的位置、整座地标的旋转，以及画布坐标（tools/taixuan.py 的设计坐标，
 * 写在 taixuan_manifest.json 里）到世界坐标的换算。原版拼接把整座地标随机转 0°/90°/180°/270°，所以场地下面空洞里的碎块、
 * 剑祟的位置都要经这里换算（同世界测试的 {@code TaixuanGameTests.Site}）。只读，不改地形。
 *
 * <p>画布上格子 (x, z) 的中心转到世界里也是格子的中心：连续坐标绕起点锚那一格的中心转。
 */
public record TaixuanSite(BlockPos anchor, Rotation rotation, ChunkPos start) {
    /** 深渊（雾底场地、空洞）的竖轴在画布上的那一列。 */
    public static final int AXIS = 126;
    private static @Nullable JsonObject manifest;

    /** taixuan_manifest.json（在模组 jar 里，两端都读得到）。 */
    public static synchronized JsonObject manifest() {
        if (manifest == null) {
            var input = Objects.requireNonNull(TaixuanSite.class.getResourceAsStream("/data/logos_dimension/taixuan_manifest.json"));
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                manifest = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        }
        return manifest;
    }

    /** 场地下面空洞的布局（tools/taixuan_cavity.py）。 */
    public static JsonObject cavity() {
        return manifest().getAsJsonObject("abyss").getAsJsonObject("cavity");
    }

    private static int anchorCanvas(int axis) {
        return manifest().getAsJsonArray("anchor_canvas").get(axis).getAsInt();
    }

    /** {@code pos} 落在哪一处太玄灵境的某个部件里；不在任何部件里时返回 null。 */
    public static @Nullable TaixuanSite at(ServerLevel level, BlockPos pos) {
        var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getOptional(ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("taixuan_sanctuary"))).orElse(null);
        if (structure == null) return null;
        return of(level.structureManager().getStructureWithPieceAt(pos, structure));
    }

    /** 由结构起点得到这一处地标；起点无效时返回 null。 */
    public static @Nullable TaixuanSite of(@Nullable StructureStart start) {
        if (start == null || !start.isValid() || start.getPieces().isEmpty()) return null;
        if (!(start.getPieces().getFirst() instanceof PoolElementStructurePiece root)) return null;
        JsonArray local = manifest().getAsJsonArray("anchor");
        var anchor = root.getPosition().offset(new BlockPos(local.get(0).getAsInt(), local.get(1).getAsInt(), local.get(2).getAsInt()).rotate(root.getRotation()));
        return new TaixuanSite(anchor, root.getRotation(), start.getChunkPos());
    }

    /** 画布上的格子 → 世界里的格子。 */
    public BlockPos pos(int x, int y, int z) {
        return anchor.offset(new BlockPos(x - anchorCanvas(0), y - anchorCanvas(1), z - anchorCanvas(2)).rotate(rotation));
    }

    public BlockPos pos(JsonArray xyz) {
        return pos(xyz.get(0).getAsInt(), xyz.get(1).getAsInt(), xyz.get(2).getAsInt());
    }

    /** 画布上的连续坐标 → 世界坐标（格子 (x, z) 的中心是画布上的 (x + 0.5, z + 0.5)）。 */
    public Vec3 point(double x, double y, double z) {
        double dx = x - (anchorCanvas(0) + .5), dz = z - (anchorCanvas(2) + .5);
        double[] r = turn(dx, dz);
        return new Vec3(anchor.getX() + .5 + r[0], anchor.getY() + (y - anchorCanvas(1)), anchor.getZ() + .5 + r[1]);
    }

    /** 画布上的方向（x, z）转到世界里。 */
    public double[] turn(double dx, double dz) {
        return switch (rotation) {
            case NONE -> new double[] {dx, dz};
            case CLOCKWISE_90 -> new double[] {-dz, dx};
            case CLOCKWISE_180 -> new double[] {-dx, -dz};
            case COUNTERCLOCKWISE_90 -> new double[] {dz, -dx};
        };
    }

    /** 绕竖轴转了多少度（画布 +x 转到世界里，顺时针为正，同 {@link Rotation}）。 */
    public float degrees() {
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
        };
    }

    /** 深渊竖轴上世界高度 {@code worldY} 的那一点（格子中心那条线）。 */
    public Vec3 axis(double worldY) {
        return point(AXIS + .5, 0, AXIS + .5).with(net.minecraft.core.Direction.Axis.Y, worldY);
    }

    /** 画布 y → 世界 y。 */
    public int worldY(int canvasY) {
        return anchor.getY() + canvasY - anchorCanvas(1);
    }

    /** 结构本身（给只认 {@link Structure} 的地方用）。 */
    public static @Nullable Structure structure(ServerLevel level) {
        return level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getOptional(ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("taixuan_sanctuary"))).orElse(null);
    }
}
