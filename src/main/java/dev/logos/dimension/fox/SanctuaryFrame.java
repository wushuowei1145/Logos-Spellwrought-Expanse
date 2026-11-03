package dev.logos.dimension.fox;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * 设计坐标与世界坐标的换算，以及聚落山体的目标地表高度。
 *
 * <p>核心区按设计高度图；核心外一圈 {@link #RING} 格是人工山坡，从核心边缘高度平滑降到山脚，
 * 再与自然地形取平滑最大值，所以原本更高的自然山脊会保留下来。
 */
final class SanctuaryFrame {
    /** 核心外山坡的宽度。核心半宽加上它不能超过结构引用范围（起始区块周围 8 个区块）。 */
    static final int RING = 40;

    final SanctuaryLayout layout;
    final int originX, originZ;
    final Mirror mirror;
    final Rotation rotation;
    /** 主街标高与山脚高度。 */
    final int p, foot;
    final long seed;
    private final SimplexNoise noise;

    SanctuaryFrame(int plan, int originX, int originZ, Mirror mirror, Rotation rotation, int p, int foot, long seed) {
        this.layout = SanctuaryLayout.get(plan);
        this.originX = originX;
        this.originZ = originZ;
        this.mirror = mirror;
        this.rotation = rotation;
        this.p = p;
        this.foot = foot;
        this.seed = seed;
        RandomSource random = new LegacyRandomSource(seed);
        this.noise = new SimplexNoise(random);
    }

    /** 让核心中心落在 (centerX, centerZ) 的原点。 */
    static int[] originFor(int centerX, int centerZ, Mirror mirror, Rotation rotation) {
        int half = SanctuaryLayout.get(0).size / 2;  // 各方案核心区一样大
        BlockPos c = StructureTemplate.transform(new BlockPos(half, 0, half), mirror, rotation, BlockPos.ZERO);
        return new int[]{centerX - c.getX(), centerZ - c.getZ()};
    }

    BlockPos world(int u, int y, int v) {
        BlockPos t = StructureTemplate.transform(new BlockPos(u, y, v), mirror, rotation, BlockPos.ZERO);
        return new BlockPos(originX + t.getX(), y, originZ + t.getZ());
    }

    /** 世界方块列 → 设计坐标（整数，精确可逆）。 */
    int[] local(int x, int z) {
        int dx = x - originX, dz = z - originZ;
        // 先逆旋转，再镜像（镜像自身可逆，原版是先镜像后旋转）
        int u, v;
        switch (rotation) {
            case CLOCKWISE_90 -> { u = dz; v = -dx; }
            case CLOCKWISE_180 -> { u = -dx; v = -dz; }
            case COUNTERCLOCKWISE_90 -> { u = -dz; v = dx; }
            default -> { u = dx; v = dz; }
        }
        if (mirror == Mirror.FRONT_BACK) u = -u;
        else if (mirror == Mirror.LEFT_RIGHT) v = -v;
        return new int[]{u, v};
    }

    /** 设计坐标中的连续点 → 世界连续坐标（方块中心对应方块中心）。 */
    double[] worldCont(double u, double v) {
        double a = u - 0.5, b = v - 0.5;
        if (mirror == Mirror.FRONT_BACK) a = -a;
        else if (mirror == Mirror.LEFT_RIGHT) b = -b;
        double x, z;
        switch (rotation) {
            case CLOCKWISE_90 -> { x = -b; z = a; }
            case CLOCKWISE_180 -> { x = -a; z = -b; }
            case COUNTERCLOCKWISE_90 -> { x = b; z = -a; }
            default -> { x = a; z = b; }
        }
        return new double[]{originX + 0.5 + x, originZ + 0.5 + z};
    }

    /** 外圈陡坡露岩的地表类型（只在 Java 里用，不出现在设计数据中）。 */
    static final int ROCKY = 100;

    /** 设计坐标到核心正方形的距离；在核心内为 0。 */
    double ringDistance(int u, int v) {
        int s = layout.size - 1;
        int cu = Mth.clamp(u, 0, s), cv = Mth.clamp(v, 0, s);
        return Math.sqrt((double) (u - cu) * (u - cu) + (double) (v - cv) * (v - cv));
    }

    /** 某列的设计结果：目标地表高度、地表类型，以及是否只允许填高。 */
    record Column(int target, int ground, boolean fillOnly) {
    }

    /**
     * @param natural 该列现有地表（最高实心方块）的 y
     * @return null 表示不在聚落山体范围内，保持原样
     */
    Column column(int x, int z, int natural) {
        int[] l = local(x, z);
        int u = l[0], v = l[1];
        if (layout.inside(u, v)) {
            int design = p + layout.height(u, v);
            int g = layout.ground(u, v);
            if (g == SanctuaryLayout.SOFT_TURF) return new Column((int) Math.round(smax(natural, design, 3)), g, true);
            return new Column(design, g, false);
        }
        double d = ringDistance(u, v);
        if (d > RING) return null;
        double slope = slope(x, z, u, v, d);
        // 陡处露出岩石：取设计坡面的梯度，与自然地形无关，相邻区块结果一致
        int[] ex = local(x + 1, z), wx = local(x - 1, z), sz = local(x, z + 1), nz = local(x, z - 1);
        double gx = (slope(x + 1, z, ex[0], ex[1], ringDistance(ex[0], ex[1])) - slope(x - 1, z, wx[0], wx[1], ringDistance(wx[0], wx[1]))) / 2;
        double gz = (slope(x, z + 1, sz[0], sz[1], ringDistance(sz[0], sz[1])) - slope(x, z - 1, nz[0], nz[1], ringDistance(nz[0], nz[1]))) / 2;
        boolean rocky = Math.hypot(gx, gz) > 1.05 && noise.getValue(x / 7.0 - 90, z / 7.0) > -0.15;
        return new Column((int) Math.round(smax(natural, slope, 4)), rocky ? ROCKY : SanctuaryLayout.SOFT_TURF, true);
    }

    /** 核心外人工山坡的高度：从最近的核心边缘高度降到山脚，坡面带一点起伏。 */
    double slope(int x, int z, int u, int v, double d) {
        int s = layout.size - 1;
        int edge = p + layout.height(Mth.clamp(u, 0, s), Mth.clamp(v, 0, s));
        double drop = Math.max(0, edge - foot + 2);
        double t = Math.min(1, d / RING);
        double g = 1 - Math.pow(1 - t, 1.4);
        // 低频起伏让等高线弯曲，免得山坡像一圈圈台阶
        double wobble = noise.getValue(x / 37.0, z / 37.0) * 4.5 + noise.getValue(x / 13.0 + 40, z / 13.0) * 1.6;
        return edge - drop * g + wobble * Math.sin(Math.PI * t);
    }

    /** 带自然地形采样的规划用地表：核心与山坡取设计值，外侧取自然。 */
    double planned(int x, int z, double natural) {
        Column c = column(x, z, (int) Math.round(natural));
        return c == null ? natural : c.target();
    }

    static double smax(double a, double b, double k) {
        double h = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.max(a, b) + h * h * k * 0.25;
    }
}
