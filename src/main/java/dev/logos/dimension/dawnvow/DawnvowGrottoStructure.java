package dev.logos.dimension.dawnvow;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

import java.util.Optional;

/**
 * 曦誓之洞：藏在山体里的一间山洞，中央石丘上是插着曦誓的石座，一条窄洞从山坡通进来。
 *
 * <p>和神狐聚落一样用 Java 选址（维护者 2026-11-03 同意）：洞要整个埋在山里、顶上留足岩层，
 * 洞口要正好开在某一面山坡上，这得按真实地表高度去量，原版拼接与 Beardifier 做不到。
 * 选址只读噪声地形，不看已生成的方块；开凿见 {@link GrottoPiece}。
 */
public class DawnvowGrottoStructure extends Structure {
    public static final MapCodec<DawnvowGrottoStructure> CODEC = simpleCodec(DawnvowGrottoStructure::new);
    /** 与 worldgen/structure_set/dawnvow_grotto.json 保持一致。 */
    private static final int SPACING = 20, SEPARATION = 7, SALT = 20261103;
    private static final RandomSpreadStructurePlacement PLACEMENT = new RandomSpreadStructurePlacement(SPACING, SEPARATION, RandomSpreadType.LINEAR, SALT);
    /** 洞的半径与洞顶高度（相对洞底）。 */
    static final int RADIUS = 12, HEIGHT = 11;
    /** 洞顶之上至少留这么厚的岩层，山洞从外面看不出来。 */
    private static final int COVER = 8;
    /** 洞底比海平面至少高这么多，避开含水层。 */
    private static final int ABOVE_SEA = 4;
    /** 山要多高：洞正上方的地表至少高出海平面这么多，洞口所在山坡到洞顶地表至少这么大的落差。 */
    private static final int MIN_HEIGHT = 30, MIN_RELIEF = 20;
    /** 洞口方向与长度的搜索：16 个方向，洞口离洞中心 20–84 格。 */
    private static final int DIRECTIONS = 16, EXIT_MIN = 20, EXIT_MAX = 84, EXIT_STEP = 4;

    public DawnvowGrottoStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    public StructureType<?> type() {
        return Dawnvow.GROTTO.get();
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        ChunkPos chunk = ctx.chunkPos();
        int cx = chunk.getMiddleBlockX(), cz = chunk.getMiddleBlockZ();
        int sea = ctx.chunkGenerator().getSeaLevel();
        // 先看中心一点：要是高山，且地表群系在允许的集合里（樱野的山留给神狐聚落）
        int top = top(ctx, cx, cz);
        if (top < sea + MIN_HEIGHT) return Optional.empty();
        var sampler = ctx.randomState().sampler();
        if (!biomes().contains(ctx.biomeSource().getNoiseBiome(QuartPos.fromBlock(cx), QuartPos.fromBlock(top), QuartPos.fromBlock(cz), sampler))) {
            return Optional.empty();
        }
        // 洞的正上方：5×5 个点里最低的地表决定洞底最高能放多高
        int cover = Integer.MAX_VALUE;
        for (int dx = -RADIUS - 4; dx <= RADIUS + 4; dx += (RADIUS + 4) / 2) {
            for (int dz = -RADIUS - 4; dz <= RADIUS + 4; dz += (RADIUS + 4) / 2) {
                cover = Math.min(cover, top(ctx, cx + dx, cz + dz));
            }
        }
        int floorMax = cover - HEIGHT - COVER;
        if (floorMax < sea + ABOVE_SEA) return Optional.empty();

        // 找最近的一面山坡开洞口：沿各方向往外量，第一处地表低到隧道能通出去、且隧道一路都埋在山里的地方
        long seed = ctx.random().nextLong();
        int turn = (int) Math.floorMod(seed, DIRECTIONS);
        int bestR = Integer.MAX_VALUE, bestDir = -1, bestExitY = 0, bestFloor = 0;
        for (int k = 0; k < DIRECTIONS; k++) {
            int dir = (k + turn) % DIRECTIONS;
            double a = dir * Mth.TWO_PI / DIRECTIONS, ux = Math.cos(a), uz = Math.sin(a);
            int[] h = new int[(EXIT_MAX - EXIT_MIN) / EXIT_STEP + 1];
            for (int i = 0; i < h.length; i++) {
                int r = EXIT_MIN + i * EXIT_STEP;
                h[i] = top(ctx, cx + (int) Math.round(ux * r), cz + (int) Math.round(uz * r));
            }
            for (int i = 0; i < h.length; i++) {
                int r = EXIT_MIN + i * EXIT_STEP;
                if (r >= bestR) break;
                int exitY = h[i];
                // 隧道坡度至多 1:2：洞底在洞口高度上下半个隧道长度以内；尽量与洞口齐平
                int length = r - (RADIUS - 3);
                int floor = Math.min(floorMax, exitY);
                if (floor < exitY - length / 2 || floor < sea + ABOVE_SEA || exitY < sea + 2) continue;
                if (cover - exitY < MIN_RELIEF) continue;
                if (!waterless(ctx, cx + (int) Math.round(ux * r), cz + (int) Math.round(uz * r))) continue;
                boolean buried = true;
                for (int j = 0; j < i - 1 && buried; j++) {
                    int rj = EXIT_MIN + j * EXIT_STEP;
                    double t = (rj - (RADIUS - 3)) / (double) length;
                    int tunnelTop = (int) Math.round(Mth.lerp(t, floor, exitY)) + 5;
                    buried = h[j] >= tunnelTop + 3;
                }
                if (!buried) continue;
                bestR = r;
                bestDir = dir;
                bestExitY = exitY;
                bestFloor = floor;
                break;
            }
        }
        if (bestDir < 0) return Optional.empty();

        double angle = bestDir * Mth.TWO_PI / DIRECTIONS;
        int floor = bestFloor, exitR = bestR, exitY = bestExitY;
        int reach = Math.max(RADIUS + 6, exitR + 4);
        BoundingBox box = new BoundingBox(cx - reach, Math.min(floor, exitY) - 6, cz - reach, cx + reach, Math.max(floor + HEIGHT, exitY + 5) + 6, cz + reach);
        return Optional.of(new GenerationStub(new BlockPos(cx, floor, cz),
            builder -> builder.addPiece(new GrottoPiece(cx, floor, cz, angle, exitR, exitY, seed, box))));
    }

    /** 地表最高的实心方块（不含水面）。 */
    private static int top(GenerationContext ctx, int x, int z) {
        return ctx.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState()) - 1;
    }

    /** 这一列地表上没有水（洞口不能开在湖底或河里）。 */
    private static boolean waterless(GenerationContext ctx, int x, int z) {
        int dry = ctx.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState());
        int wet = ctx.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, ctx.heightAccessor(), ctx.randomState());
        return dry == wet;
    }

    /** 某区块所在网格的候选区块（开发预览用）。 */
    public static ChunkPos candidate(long seed, int chunkX, int chunkZ) {
        return PLACEMENT.getPotentialStructureChunk(seed, chunkX, chunkZ);
    }

    public static int spacing() {
        return SPACING;
    }
}
