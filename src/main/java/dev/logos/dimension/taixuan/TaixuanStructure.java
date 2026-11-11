package dev.logos.dimension.taixuan;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

/**
 * 太玄灵境：浮空宗门、深渊、镇渊柱与链台。建筑全部是原版模板拼接（{@code layout} 里那一份原版拼接结构），
 * 这里只管选址（维护者 2026-11-11 同意）：原版只看起点一个点的群系，整座 252 格见方的地标常常一半伸出群系；
 * 深渊的石壁封水封到 y=76，所以深渊口一圈的地面要高过它，不然石壁会冒出谷底。
 *
 * <p>只读噪声：群系源和噪声高度图，不看已生成的方块。条件都满足才交给原版拼接去放。
 * 群系用生成上下文里的判断（自然生成时就是本结构的群系列表），世界测试可以像原版一样传入“全部接受”。
 *
 * <p>一大片峰林里常有好几个候选点都能放，彼此只隔三四百格。{@value #CROWD} 格以内都能放的话只留一处：
 * 深处那一圈（半径 {@value #DEEP_RADIUS} 格）落在群系里的点最多的那处，再比缺的点少，最后按区块坐标定。
 * 每处都按同样的规则看邻居，结果只由种子和噪声决定，不管区块按什么顺序生成。
 */
public class TaixuanStructure extends Structure {
    public static final MapCodec<TaixuanStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        settingsCodec(i),
        JigsawStructure.CODEC.codec().fieldOf("layout").forGetter(s -> s.layout),
        Codec.intRange(16, 128).fieldOf("biome_radius").forGetter(s -> s.biomeRadius),
        Codec.intRange(8, 128).fieldOf("rim_radius").forGetter(s -> s.rimRadius),
        Codec.INT.fieldOf("rim_min_height").forGetter(s -> s.rimMinHeight),
        Codec.intRange(0, 8).optionalFieldOf("outer_misses", 0).forGetter(s -> s.outerMisses),
        Codec.intRange(0, 8).optionalFieldOf("rim_misses", 0).forGetter(s -> s.rimMisses)
    ).apply(i, TaixuanStructure::new));
    /** 群系检查取两圈：半径上 16 个方向，半径一半处 8 个方向。 */
    private static final int OUTER_POINTS = 16, INNER_POINTS = 8;
    /** 量群系时的高度：高过云岫峰林的台地，不会落进地下的根洞群系。 */
    private static final int BIOME_Y = 103;
    /** 两处之间至少隔这么远（格）；地标本身约 224 格宽。 */
    private static final int CROWD = 600;
    /** 比谁更靠近峰林中心时，量这一圈有多少点在群系里。 */
    private static final int DEEP_RADIUS = 176;

    private final JigsawStructure layout;
    private final int biomeRadius, rimRadius, rimMinHeight, outerMisses, rimMisses;
    /** 本结构所在结构组的候选点分布，从注册表里找一次（世界加载后不变）。 */
    private volatile RandomSpreadStructurePlacement spread;
    private volatile boolean spreadResolved;

    /**
     * @param outerMisses 外圈 16 个群系采样点里最多几个可以不在群系里（地标最外一圈只有空地边上的原地形）
     * @param rimMisses 深渊口一圈 16 个点里最多几个可以低于 rimMinHeight（峰林里的溪流常常横穿一两处）
     */
    public TaixuanStructure(StructureSettings settings, JigsawStructure layout, int biomeRadius, int rimRadius, int rimMinHeight,
            int outerMisses, int rimMisses) {
        super(settings);
        this.layout = layout;
        this.biomeRadius = biomeRadius;
        this.rimRadius = rimRadius;
        this.rimMinHeight = rimMinHeight;
        this.outerMisses = outerMisses;
        this.rimMisses = rimMisses;
    }

    /** 一处候选点的采样结果：内圈、外圈不在群系里的点数，深渊口一圈低于给定高度的点数。 */
    public record Measure(int inner, int outer, int rimLow) {}

    @Override
    public StructureType<?> type() {
        return Taixuan.STRUCTURE.get();
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        return wins(ctx) ? layout.findGenerationPoint(ctx) : Optional.empty();
    }

    /** 选址检查本身，不拼接：区块中心、两圈群系采样点、深渊口一圈的地面高度。勘察和世界测试也用它。 */
    public boolean siteFits(GenerationContext ctx) {
        return passes(measure(ctx, biomeRadius, rimRadius, rimMinHeight));
    }

    private boolean passes(Measure m) {
        return m != null && m.inner() == 0 && m.outer() <= outerMisses && m.rimLow() <= rimMisses;
    }

    /** 自然生成用的判断：自己通过选址，而且 {@value #CROWD} 格以内没有更好的候选点也通过。 */
    public boolean wins(GenerationContext ctx) {
        var self = measure(ctx, biomeRadius, rimRadius, rimMinHeight);
        if (!passes(self)) return false;
        var placement = spread(ctx.registryAccess());
        if (placement == null) return true;
        int spacing = placement.spacing(), reach = CROWD / (spacing * 16) + 1;
        ChunkPos chunk = ctx.chunkPos();
        int gx = Math.floorDiv(chunk.x(), spacing), gz = Math.floorDiv(chunk.z(), spacing);
        int deep = -1;
        for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
            if (dx == 0 && dz == 0) continue;
            ChunkPos other = placement.getPotentialStructureChunk(ctx.seed(), (gx + dx) * spacing, (gz + dz) * spacing);
            if (Math.hypot(other.getMiddleBlockX() - chunk.getMiddleBlockX(), other.getMiddleBlockZ() - chunk.getMiddleBlockZ()) > CROWD) continue;
            var near = new GenerationContext(ctx.registryAccess(), ctx.chunkGenerator(), ctx.biomeSource(), ctx.randomState(),
                ctx.structureTemplateManager(), ctx.seed(), other, ctx.heightAccessor(), ctx.validBiome());
            var m = measure(near, biomeRadius, rimRadius, rimMinHeight);
            if (!passes(m)) continue;
            if (deep < 0) deep = deep(ctx, chunk);
            int theirs = deep(near, other);
            if (theirs != deep) {
                if (theirs > deep) return false;
                continue;
            }
            int a = self.outer() + self.rimLow(), b = m.outer() + m.rimLow();
            if (b != a) {
                if (b < a) return false;
                continue;
            }
            if (other.x() < chunk.x() || other.x() == chunk.x() && other.z() < chunk.z()) return false;
        }
        return true;
    }

    /** 深处那一圈 16 个点里有几个在群系里：越多越靠近峰林中心。 */
    private static int deep(GenerationContext ctx, ChunkPos chunk) {
        int cx = chunk.getMiddleBlockX(), cz = chunk.getMiddleBlockZ(), n = 0;
        for (int k = 0; k < OUTER_POINTS; k++) {
            double a = (k + .5) * Mth.TWO_PI / OUTER_POINTS;
            if (inBiome(ctx, cx + (int) Math.round(Math.cos(a) * DEEP_RADIUS), cz + (int) Math.round(Math.sin(a) * DEEP_RADIUS))) n++;
        }
        return n;
    }

    /** 本结构所在结构组的随机散布参数；不在任何随机散布的结构组里（比如单独用 /place）时返回 null，不去重。 */
    private RandomSpreadStructurePlacement spread(RegistryAccess access) {
        if (!spreadResolved) {
            RandomSpreadStructurePlacement found = null;
            for (var set : access.lookupOrThrow(Registries.STRUCTURE_SET).listElements().toList())
                for (var entry : set.value().structures())
                    if (entry.structure().value() == this && set.value().placement() instanceof RandomSpreadStructurePlacement r) found = r;
            spread = found;
            spreadResolved = true;
        }
        return spread;
    }

    /**
     * 按给定半径和高度采样一处候选点；区块中心不在群系里时返回 null（自然生成时绝大多数候选点在这一步就结束，很便宜）。
     * 勘察场景用不同的参数比较放宽前后能通过多少处。
     */
    public Measure measure(GenerationContext ctx, int biomeRadius, int rimRadius, int rimMinHeight) {
        ChunkPos chunk = ctx.chunkPos();
        int cx = chunk.getMiddleBlockX(), cz = chunk.getMiddleBlockZ();
        if (!inBiome(ctx, cx, cz)) return null;
        int inner = 0, outer = 0, low = 0;
        for (int k = 0; k < INNER_POINTS; k++) {
            double a = (k + .5) * Mth.TWO_PI / INNER_POINTS;
            if (!inBiome(ctx, cx + (int) Math.round(Math.cos(a) * biomeRadius / 2), cz + (int) Math.round(Math.sin(a) * biomeRadius / 2))) inner++;
        }
        for (int k = 0; k < OUTER_POINTS; k++) {
            double a = k * Mth.TWO_PI / OUTER_POINTS;
            if (!inBiome(ctx, cx + (int) Math.round(Math.cos(a) * biomeRadius), cz + (int) Math.round(Math.sin(a) * biomeRadius))) outer++;
        }
        // 深渊口一圈：地面低过石壁顶的点（河谷、湖边、溪流）
        for (int k = 0; k < OUTER_POINTS; k++) {
            double a = k * Mth.TWO_PI / OUTER_POINTS;
            int x = cx + (int) Math.round(Math.cos(a) * rimRadius), z = cz + (int) Math.round(Math.sin(a) * rimRadius);
            int top = ctx.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, ctx.heightAccessor(), ctx.randomState());
            if (top < rimMinHeight) low++;
        }
        return new Measure(inner, outer, low);
    }

    /** 里面那一份原版拼接结构；世界测试用它跳过选址、只看拼接。 */
    public JigsawStructure layout() {
        return layout;
    }

    public int biomeRadius() {
        return biomeRadius;
    }

    public int rimRadius() {
        return rimRadius;
    }

    public int rimMinHeight() {
        return rimMinHeight;
    }

    public int outerMisses() {
        return outerMisses;
    }

    public int rimMisses() {
        return rimMisses;
    }

    private static boolean inBiome(GenerationContext ctx, int x, int z) {
        var biome = ctx.biomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(BIOME_Y), QuartPos.fromBlock(z),
            ctx.randomState().sampler());
        return ctx.validBiome().test(biome);
    }
}
