package dev.logos.dimension.fox;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 神狐聚落结构：候选区块位于灵狐樱野且群系覆盖足够时，采样周围自然地形，
 * 定出主街标高与山脚，再在八种朝向里挑山路最贴合地形的一种。
 */
public class FoxSanctuaryStructure extends Structure {
    public static final MapCodec<FoxSanctuaryStructure> CODEC = simpleCodec(FoxSanctuaryStructure::new);
    /** 自然地形采样：33 格网格（9×9），覆盖核心与外圈山坡。 */
    private static final int STEP = 33, HALF = 132;
    /** 地形部件的水平半径：核心半宽 + 外圈山坡，正好落在起始区块周围 8 个区块的引用范围内。 */
    private static final int REACH = 128;
    /** 与 worldgen/structure_set/fox_sanctuary.json 保持一致（由 tools/fox_sanctuary.py 生成）。 */
    private static final int SPACING = 8, SEPARATION = 4, SALT = 20261102;
    private static final RandomSpreadStructurePlacement PLACEMENT = new RandomSpreadStructurePlacement(SPACING, SEPARATION, RandomSpreadType.LINEAR, SALT);
    /** 两处聚落中心的最小间距：大于整个聚落的宽度。 */
    private static final double EXCLUSION = 300;
    /** 5×5 采样里至少这么多点落在樱野。 */
    private static final int MIN_COVERAGE = 15;

    public FoxSanctuaryStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    public StructureType<?> type() {
        return FoxSanctuary.TYPE.get();
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        ChunkPos chunk = ctx.chunkPos();
        int cx = chunk.getMiddleBlockX(), cz = chunk.getMiddleBlockZ();
        var sampler = ctx.randomState().sampler();
        // 先看中心一点，绝大多数候选在这里就被排除。群系用结构自己的集合判断：/place 传入的谓词恒为真
        if (!biomes().contains(ctx.biomeSource().getNoiseBiome(QuartPos.fromBlock(cx), QuartPos.fromBlock(96), QuartPos.fromBlock(cz), sampler))) {
            return Optional.empty();
        }
        // 候选很密（每 8 个区块一个），只留下 300 格内樱野覆盖最好的那个：每片樱野山大约一处聚落，彼此不重叠
        int score = coverage(ctx, cx, cz, biomes());
        if (score == 0) return Optional.empty();
        int cell = Math.floorDiv(chunk.x(), SPACING), cellZ = Math.floorDiv(chunk.z(), SPACING);
        for (int i = -3; i <= 3; i++) {
            for (int j = -3; j <= 3; j++) {
                if (i == 0 && j == 0) continue;
                ChunkPos other = PLACEMENT.getPotentialStructureChunk(ctx.seed(), (cell + i) * SPACING, (cellZ + j) * SPACING);
                int ox = other.getMiddleBlockX(), oz = other.getMiddleBlockZ();
                if (Math.hypot(ox - cx, oz - cz) > EXCLUSION) continue;
                int s = coverage(ctx, ox, oz, biomes());
                if (s > score || s == score && (ox < cx || ox == cx && oz < cz)) return Optional.empty();
            }
        }

        int n = HALF * 2 / STEP + 1;
        // 只有每片樱野胜出的那个候选才走到这里，按真实地表取 9×9 个点（樱野在陡峭山峰上，噪声路由的初步估算会低好几十格）
        double[] grid = new double[n * n];
        int minNat = Integer.MAX_VALUE;
        List<Integer> ring = new ArrayList<>(), core = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int dx = -HALF + i * STEP, dz = -HALF + j * STEP;
                int h = ctx.chunkGenerator().getBaseHeight(cx + dx, cz + dz, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState()) - 1;
                grid[i * n + j] = h;
                minNat = Math.min(minNat, h);
                int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                if (cheb >= 99) ring.add(h);
                if (cheb <= 66) core.add(h);
            }
        }
        int foot = percentile(ring, 0.5);
        int coreHigh = percentile(core, 0.9);
        int p = Math.max(coreHigh + 3, foot + 34);
        if (p - foot > 64 || p + 64 > ctx.heightAccessor().getMaxY()) return Optional.empty();

        long seed = ctx.random().nextLong();
        java.util.function.DoubleBinaryOperator natural = (x, z) -> {
            double fx = Mth.clamp((x - cx + HALF) / STEP, 0, n - 1.001), fz = Mth.clamp((z - cz + HALF) / STEP, 0, n - 1.001);
            int i = (int) fx, j = (int) fz;
            double a = fx - i, b = fz - j;
            return Mth.lerp2(a, b, grid[i * n + j], grid[(i + 1) * n + j], grid[i * n + j + 1], grid[(i + 1) * n + j + 1]);
        };
        SanctuaryFrame best = null;
        SanctuaryTrail bestTrail = null;
        for (Mirror mirror : new Mirror[]{Mirror.NONE, Mirror.FRONT_BACK}) {
            for (Rotation rotation : Rotation.values()) {
                int[] origin = SanctuaryFrame.originFor(cx, cz, mirror, rotation);
                SanctuaryFrame frame = new SanctuaryFrame(origin[0], origin[1], mirror, rotation, p, foot, seed);
                SanctuaryTrail trail = SanctuaryTrail.plan(frame, natural);
                if (bestTrail == null || trail.cost < bestTrail.cost) {
                    best = frame;
                    bestTrail = trail;
                }
            }
        }
        SanctuaryFrame frame = best;
        SanctuaryTrail trail = bestTrail;
        int low = minNat;
        for (int y : trail.y) low = Math.min(low, y);
        BoundingBox box = new BoundingBox(cx - REACH, low - 10, cz - REACH, cx + REACH, p + 48, cz + REACH);

        return Optional.of(new GenerationStub(new BlockPos(cx, p, cz), builder -> {
            builder.addPiece(new SanctuaryGroundsPiece(frame, trail, box));
            SanctuaryLayout layout = frame.layout;
            for (SanctuaryLayout.Tile tile : layout.tiles) {
                builder.addPiece(new SanctuaryTemplatePiece(ctx.structureTemplateManager(), "logos_dimension:fox_sanctuary/" + tile.template(),
                    frame.world(tile.u(), p + layout.tileMinY, tile.v()), frame.mirror, frame.rotation));
            }
            for (SanctuaryLayout.Lot lot : layout.lots) {
                List<String> options = layout.variants.get(lot.kind());
                if (options == null || options.isEmpty()) continue;
                String template = options.get(ctx.random().nextInt(options.size()));
                builder.addPiece(new SanctuaryTemplatePiece(ctx.structureTemplateManager(), "logos_dimension:fox_sanctuary/" + template,
                    frame.world(lot.u(), p + lot.y(), lot.v()), frame.mirror, lotRotation(frame, lot.quarterTurns())));
            }
        }));
    }

    /** 地块模板先在设计坐标里顺时针转 turns 次，再套聚落整体的镜像与旋转；镜像会让局部旋转反向。 */
    private static Rotation lotRotation(SanctuaryFrame frame, int turns) {
        Rotation local = Rotation.values()[Math.floorMod(turns, 4)];
        if (frame.mirror != Mirror.NONE) local = Rotation.values()[Math.floorMod(-turns, 4)];
        return frame.rotation.getRotated(local);
    }

    /** 某区块所在网格的候选区块（开发预览用）。 */
    public static ChunkPos candidate(long seed, int chunkX, int chunkZ) {
        return PLACEMENT.getPotentialStructureChunk(seed, chunkX, chunkZ);
    }

    public static int spacing() {
        return SPACING;
    }

    /** 以 (x, z) 为中心 ±88 格内 5×5 采样点落在樱野的个数（0–25）；中心不在樱野或覆盖不足时为 0，与自身的取舍规则一致。 */
    private static int coverage(GenerationContext ctx, int x, int z, net.minecraft.core.HolderSet<net.minecraft.world.level.biome.Biome> allowed) {
        var sampler = ctx.randomState().sampler();
        if (!allowed.contains(ctx.biomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(96), QuartPos.fromBlock(z), sampler))) return 0;
        int hits = 0;
        for (int dx = -88; dx <= 88; dx += 44) {
            for (int dz = -88; dz <= 88; dz += 44) {
                if (allowed.contains(ctx.biomeSource().getNoiseBiome(QuartPos.fromBlock(x + dx), QuartPos.fromBlock(96), QuartPos.fromBlock(z + dz), sampler))) hits++;
            }
        }
        return hits >= MIN_COVERAGE ? hits : 0;
    }

    private static int percentile(List<Integer> values, double q) {
        int[] a = values.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(a);
        return a[Math.min(a.length - 1, (int) (q * a.length))];
    }
}
