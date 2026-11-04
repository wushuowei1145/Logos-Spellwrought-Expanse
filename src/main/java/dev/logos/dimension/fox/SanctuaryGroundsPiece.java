package dev.logos.dimension.fox;

import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import dev.logos.dimension.cloudsea.TallStoneLanternBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚落的地形部件：整形山体（核心按设计高度削填，外圈堆出山坡），铺上山小路、溪流与石板桥，
 * 再放沿路的鸟居、石灯笼与竹林。每个区块只改自己范围内的方块，结果只取决于保存的规划数据与该列原地形。
 */
public class SanctuaryGroundsPiece extends StructurePiece {
    private final SanctuaryFrame frame;
    private final SanctuaryTrail trail;
    private final double centerX, centerZ;

    SanctuaryGroundsPiece(SanctuaryFrame frame, SanctuaryTrail trail, BoundingBox box) {
        super(FoxSanctuary.GROUNDS.get(), 0, box);
        this.frame = frame;
        this.trail = trail;
        double[] c = frame.worldCont(frame.layout.size / 2.0, frame.layout.size / 2.0);
        this.centerX = c[0];
        this.centerZ = c[1];
    }

    public SanctuaryGroundsPiece(CompoundTag tag) {
        super(FoxSanctuary.GROUNDS.get(), tag);
        // 早期存档没有 Plan，按方案 0
        this.frame = new SanctuaryFrame(tag.getIntOr("Plan", 0), tag.getIntOr("OX", 0), tag.getIntOr("OZ", 0),
            Mirror.valueOf(tag.getStringOr("Mirror", "NONE")), Rotation.valueOf(tag.getStringOr("Rot", "NONE")),
            tag.getIntOr("P", 100), tag.getIntOr("Foot", 70), tag.getLongOr("Seed", 0));
        this.trail = SanctuaryTrail.load(tag);
        double[] c = frame.worldCont(frame.layout.size / 2.0, frame.layout.size / 2.0);
        this.centerX = c[0];
        this.centerZ = c[1];
    }

    /** 布局方案（开发预览用）。 */
    public int plan() {
        return frame.layout.plan;
    }

    /** 主街标高（开发预览用）。 */
    public int streetLevel() {
        return frame.p;
    }

    public int trailSize() {
        return trail.size();
    }

    /** 路面第 i 个采样点上方一格（开发预览用）。 */
    public net.minecraft.world.phys.Vec3 trailPoint(int i) {
        i = Mth.clamp(i, 0, trail.size() - 1);
        return new net.minecraft.world.phys.Vec3(trail.x(i), trail.y[i] + 1, trail.z(i));
    }

    /** 设计坐标 (u, v) 在主街标高上的世界位置（开发预览用）。 */
    public net.minecraft.world.phys.Vec3 designPoint(double u, double dy, double v) {
        double[] w = frame.worldCont(u, v);
        return new net.minecraft.world.phys.Vec3(w[0], frame.p + dy, w[1]);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("Plan", frame.layout.plan);
        tag.putInt("OX", frame.originX);
        tag.putInt("OZ", frame.originZ);
        tag.putString("Mirror", frame.mirror.name());
        tag.putString("Rot", frame.rotation.name());
        tag.putInt("P", frame.p);
        tag.putInt("Foot", frame.foot);
        tag.putLong("Seed", frame.seed);
        trail.save(tag);
    }

    /** 一列的整形结果。 */
    private static final class Col {
        int natTop, surfTop, target, ground;
        boolean soft, path, stream;
        int ti = -1, sj = -1;
        double dt = 1e9, ds = 1e9;
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
                            BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos) {
        int minX = Math.max(chunkBB.minX(), boundingBox.minX()), maxX = Math.min(chunkBB.maxX(), boundingBox.maxX());
        int minZ = Math.max(chunkBB.minZ(), boundingBox.minZ()), maxZ = Math.min(chunkBB.maxZ(), boundingBox.maxZ());
        if (minX > maxX || minZ > maxZ) return;
        int[] near = nearby(trail.x8, trail.z8, minX - 11, maxX + 11, minZ - 11, maxZ + 11);
        int[] nearS = nearby(trail.sx8, trail.sz8, minX - 5, maxX + 5, minZ - 5, maxZ + 5);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Col c = plan(level, x, z, near, nearS);
                if (c == null) continue;
                apply(level, pos, x, z, c);
                syncWorldgenHeightmaps(level, x, z);
                bamboo(level, pos, x, z, c);
            }
        }
        for (int i : trail.torii) torii(level, chunkBB, i);
        for (int e : trail.lanterns) lantern(level, chunkBB, e >> 1, (e & 1) == 1, near, nearS);
    }

    private static int[] nearby(int[] x8, int[] z8, int minX, int maxX, int minZ, int maxZ) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < x8.length; i++) {
            double x = x8[i] / 8.0, z = z8[i] / 8.0;
            if (x >= minX && x <= maxX + 1 && z >= minZ && z <= maxZ + 1) out.add(i);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 该列真正的地面：从最高方块往下跳过空气、树叶、树干、植物与液体。邻区块先长出的树冠可能已伸进本区块。 */
    private static int groundTop(WorldGenLevel level, int x, int z, int from) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, from, z);
        int min = level.getMinY();
        while (pos.getY() > min) {
            BlockState s = level.getBlockState(pos);
            if (!(s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.canBeReplaced() || !s.getFluidState().isEmpty()
                || s.is(Blocks.BAMBOO) || s.is(BlockTags.FLOWERS))) {
                return pos.getY();
            }
            pos.move(0, -1, 0);
        }
        return min;
    }

    private static final Heightmap.Types[] WORLDGEN_MAPS = {Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG};

    /**
     * 结构整形发生在特征阶段，此时区块只更新最终高度图，*_WG 还停在整形前；
     * 别的群系的树多按 *_WG 落点，不同步的话会悬在削掉的路面上或埋进填出的坡里。
     */
    private static void syncWorldgenHeightmaps(WorldGenLevel level, int x, int z) {
        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
        int lx = x & 15, lz = z & 15;
        int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Heightmap.Types type : WORLDGEN_MAPS) {
            Heightmap map = chunk.getOrCreateHeightmapUnprimed(type);
            // 原最高块被削掉：在该处“移除”一次，让高度图向下重找
            int top = map.getFirstAvailable(lx, lz) - 1;
            map.update(lx, top, lz, chunk.getBlockState(pos.set(x, top, z)));
            // 填高：从现在的最高块往下找第一个算数的方块
            for (int y = surface; y >= map.getFirstAvailable(lx, lz); y--) {
                BlockState s = chunk.getBlockState(pos.set(x, y, z));
                if (type.isOpaque().test(s)) {
                    map.update(lx, y, lz, s);
                    break;
                }
            }
        }
    }

    /** 整形之后该列的地表。 */
    private static int finalTop(Col c) {
        if (c.stream) return c.target;
        return c.soft && c.target <= c.natTop ? c.natTop : c.target;
    }

    private Col plan(WorldGenLevel level, int x, int z, int[] near, int[] nearS) {
        Col c = new Col();
        double px = x + 0.5, pz = z + 0.5;
        for (int k : near) {
            double d = sq(trail.x(k) - px) + sq(trail.z(k) - pz);
            if (d < c.dt) {
                c.dt = d;
                c.ti = k;
            }
        }
        c.dt = Math.sqrt(c.dt);
        for (int k : nearS) {
            double d = sq(trail.sx8[k] / 8.0 - px) + sq(trail.sz8[k] / 8.0 - pz);
            if (d < c.ds) {
                c.ds = d;
                c.sj = k;
            }
        }
        c.ds = Math.sqrt(c.ds);
        c.surfTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        c.natTop = groundTop(level, x, z, c.surfTop);
        SanctuaryFrame.Column fc = frame.column(x, z, c.natTop);
        if (fc == null && c.dt > 9 && c.ds > 3) return null;
        c.target = fc != null ? fc.target() : c.natTop;
        c.ground = fc != null ? fc.ground() : SanctuaryLayout.SOFT_TURF;
        c.soft = fc == null || fc.fillOnly();
        if (c.ti >= 0 && c.dt <= 9) {
            int ty = trail.y[c.ti];
            if (c.dt <= SanctuaryTrail.HALF_WIDTH) {
                c.path = true;
                c.target = ty;
                c.soft = false;
            } else {
                double k = c.dt - SanctuaryTrail.HALF_WIDTH;
                int lo = (int) Math.ceil(ty - k * 1.05 - 0.3);
                int hi = (int) Math.floor(ty + 0.4 + k * 1.3);
                int t = Mth.clamp(c.target, lo, hi);
                if (t != c.target) c.soft = false;
                c.target = t;
            }
        }
        if (c.sj >= 0) {
            int wy = trail.sy[c.sj];
            boolean end = c.sj == 0 || c.sj == trail.sy.length - 1;
            double r = end ? 2.3 : 1.05;
            if (c.ds <= r) {
                c.stream = true;
                c.soft = false;
            } else if (c.ds <= r + 1.6 && c.target < wy + 1) {
                c.target = wy + 1;
                c.soft = false;
            }
        }
        return c;
    }

    private void apply(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, Col c) {
        long h = hash(x, z);
        if (c.stream) {
            int wy = trail.sy[c.sj];
            shape(level, pos, x, z, c, wy - 1, streamBed(h));
            set(level, pos.set(x, wy, z), Blocks.WATER.defaultBlockState());
            level.scheduleTick(pos.immutable(), Fluids.WATER, 2);
            if (c.path) {
                // 石板桥：溪水从路下穿过，桥面与路面齐平
                int ty = trail.y[c.ti];
                for (int y = wy + 1; y < ty; y++) set(level, pos.set(x, y, z), Blocks.AIR.defaultBlockState());
                set(level, pos.set(x, ty, z), (h & 3) == 0 ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState());
                for (int y = ty + 1; y <= Math.max(c.surfTop, ty + 3); y++) set(level, pos.set(x, y, z), Blocks.AIR.defaultBlockState());
            } else {
                for (int y = wy + 1; y <= Math.max(c.surfTop, wy + 2); y++) set(level, pos.set(x, y, z), Blocks.AIR.defaultBlockState());
            }
            return;
        }
        if (c.path) {
            shape(level, pos, x, z, c, c.target, pathBlock(c, h, x, z));
            for (int y = c.target + 1; y <= c.target + 4; y++) set(level, pos.set(x, y, z), Blocks.AIR.defaultBlockState());
            return;
        }
        if (c.soft && c.target <= c.natTop) return;
        shape(level, pos, x, z, c, c.target, c.ground == SanctuaryFrame.ROCKY ? rock(h) : topBlock(c.ground));
    }

    /** 把这一列整成以 top 为地表：低了就填（表层土、下层石），高了就削。 */
    private void shape(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, Col c, int top, BlockState surface) {
        BlockState dirt = Blocks.DIRT.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
        if (top > c.natTop) {
            for (int y = c.natTop; y < top; y++) {
                if (y == c.natTop && !level.getBlockState(pos.set(x, y, z)).is(turf().getBlock())) continue;
                set(level, pos.set(x, y, z), top - y <= 3 ? dirt : stone);
            }
        } else {
            for (int y = top + 1; y <= c.surfTop; y++) set(level, pos.set(x, y, z), Blocks.AIR.defaultBlockState());
            for (int y = top - 3; y < top; y++) {
                BlockState old = level.getBlockState(pos.set(x, y, z));
                if (!old.isAir() && old.getFluidState().isEmpty()) set(level, pos, dirt);
            }
        }
        set(level, pos.set(x, top, z), surface);
    }

    private BlockState topBlock(int ground) {
        return switch (ground) {
            case SanctuaryLayout.LAWN -> Blocks.GRASS_BLOCK.defaultBlockState();
            case SanctuaryLayout.MOSS -> Blocks.MOSS_BLOCK.defaultBlockState();
            case SanctuaryLayout.COARSE -> Blocks.COARSE_DIRT.defaultBlockState();
            case SanctuaryLayout.PATH -> Blocks.DIRT_PATH.defaultBlockState();
            case SanctuaryLayout.GRAVEL -> Blocks.GRAVEL.defaultBlockState();
            case SanctuaryLayout.STONE -> Blocks.STONE.defaultBlockState();
            default -> turf();
        };
    }

    private static BlockState turfState;

    private static BlockState turf() {
        if (turfState == null) {
            turfState = BuiltInRegistries.BLOCK.getValue(Identifier.parse("logos_dimension:cloudsea_white_cherry_ground")).defaultBlockState();
        }
        return turfState;
    }

    private static BlockState rock(long h) {
        int r = (int) (h % 20);
        return (r < 7 ? Blocks.STONE : r < 11 ? Blocks.ANDESITE : r < 14 ? Blocks.COBBLESTONE : r < 17 ? Blocks.MOSSY_COBBLESTONE : Blocks.TUFF).defaultBlockState();
    }

    private static BlockState streamBed(long h) {
        int r = (int) (h % 20);
        return (r < 8 ? Blocks.GRAVEL : r < 13 ? Blocks.COBBLESTONE : r < 17 ? Blocks.MOSSY_COBBLESTONE : Blocks.SAND).defaultBlockState();
    }

    /** 路面：山脚土路配木阶，山腰乱石配石阶，近山顶为石板路。台阶放在较高一格并朝上坡。 */
    private BlockState pathBlock(Col c, long h, int x, int z) {
        int zone = trail.zone[c.ti];
        int i = c.ti;
        boolean step = i + 1 < trail.size() && trail.y[i + 1] < trail.y[i];
        if (step && i > 2) {
            double[] t = trail.tangent(i);
            Direction up = horizontal(-t[0], -t[1]);
            Block stair = switch (zone) {
                case 2 -> (h & 3) == 0 ? Blocks.MOSSY_STONE_BRICK_STAIRS : Blocks.STONE_BRICK_STAIRS;
                case 1 -> (h & 1) == 0 ? Blocks.MOSSY_COBBLESTONE_STAIRS : Blocks.COBBLESTONE_STAIRS;
                default -> Blocks.SPRUCE_STAIRS;
            };
            return stair.defaultBlockState().setValue(StairBlock.FACING, up).setValue(StairBlock.HALF, Half.BOTTOM);
        }
        int r = (int) (h % 100);
        boolean edge = c.dt > 1.0;
        return switch (zone) {
            case 2 -> edge
                ? (r < 45 ? Blocks.MOSSY_COBBLESTONE : r < 75 ? Blocks.MOSSY_STONE_BRICKS : Blocks.MOSS_BLOCK).defaultBlockState()
                : (r < 55 ? Blocks.STONE_BRICKS : r < 80 ? Blocks.MOSSY_STONE_BRICKS : r < 90 ? Blocks.CRACKED_STONE_BRICKS : Blocks.POLISHED_ANDESITE).defaultBlockState();
            case 1 -> edge
                ? (r < 40 ? Blocks.COARSE_DIRT : r < 70 ? Blocks.MOSS_BLOCK : Blocks.DIRT_PATH).defaultBlockState()
                : (r < 30 ? Blocks.COBBLESTONE : r < 58 ? Blocks.MOSSY_COBBLESTONE : r < 88 ? Blocks.DIRT_PATH : Blocks.ANDESITE).defaultBlockState();
            default -> edge
                ? (r < 50 ? Blocks.COARSE_DIRT : r < 80 ? Blocks.DIRT_PATH : turf().getBlock()).defaultBlockState()
                : (r < 72 ? Blocks.DIRT_PATH : r < 90 ? Blocks.COARSE_DIRT : Blocks.GRAVEL).defaultBlockState();
        };
    }

    private void bamboo(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z, Col c) {
        if (c.path || c.stream || c.ti < 0 || c.dt < 2.7 || c.dt > 8.5 || c.ds < 2.8) return;
        int[] l = frame.local(x, z);
        if (frame.layout.inside(l[0], l[1]) || frame.ringDistance(l[0], l[1]) > SanctuaryFrame.RING - 1) return;
        int side = outwardSign(c.ti, x + 0.5, z + 0.5);
        boolean inZone = false;
        for (int k = 0; k + 2 < trail.bamboo.length; k += 3) {
            if (c.ti >= trail.bamboo[k] && c.ti <= trail.bamboo[k + 1] && side == trail.bamboo[k + 2]) inZone = true;
        }
        long h = hash(x * 31 + 7, z * 17 - 3);
        if (!inZone || (h % 100) > 46) return;
        int top = finalTop(c);
        set(level, pos.set(x, top, z), Blocks.PODZOL.defaultBlockState());
        int height = 8 + (int) ((h >> 8) % 7);
        for (int k = 1; k <= height; k++) {
            BambooLeaves leaves = k >= height - 1 ? BambooLeaves.LARGE : k == height - 2 ? BambooLeaves.SMALL : BambooLeaves.NONE;
            if (!level.getBlockState(pos.set(x, top + k, z)).isAir()) return;
            set(level, pos, Blocks.BAMBOO.defaultBlockState().setValue(BambooStalkBlock.AGE, 1).setValue(BambooStalkBlock.LEAVES, leaves));
        }
    }

    /** 采样点处路的外侧（背离聚落中心）方向。 */
    private double[] outward(int i) {
        double[] t = trail.tangent(i);
        double nx = -t[1], nz = t[0];
        if (nx * (trail.x(i) - centerX) + nz * (trail.z(i) - centerZ) < 0) {
            nx = -nx;
            nz = -nz;
        }
        return new double[]{nx, nz};
    }

    private int outwardSign(int i, double px, double pz) {
        double[] o = outward(i);
        return o[0] * (px - trail.x(i)) + o[1] * (pz - trail.z(i)) >= 0 ? 1 : -1;
    }

    /** 红色鸟居：两根朱漆柱，半格厚的贯，细额束，朱漆岛木上压一道出挑的灰瓦笠木；门洞 3 宽 4 高。 */
    private void torii(WorldGenLevel level, BoundingBox chunkBB, int i) {
        double[] t = trail.tangent(i);
        int cx = Mth.floor(trail.x(i)), cz = Mth.floor(trail.z(i)), y = trail.y[i];
        boolean spanZ = Math.abs(t[0]) > Math.abs(t[1]);
        BlockState post = FoxWildsBlocks.LACQUER_LOG.get().defaultBlockState().trySetValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        BlockState nuki = FoxWildsBlocks.LACQUER_SLAB.get().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
        BlockState strut = FoxWildsBlocks.LACQUER_FENCE.get().defaultBlockState();
        BlockState beam = FoxWildsBlocks.LACQUER_PLANKS.get().defaultBlockState();
        BlockState cap = FoxWildsBlocks.KAWARA_SLAB.get().defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        for (int s = -4; s <= 4; s++) {
            int bx = spanZ ? cx : cx + s, bz = spanZ ? cz + s : cz;
            int a = Math.abs(s);
            if (a == 2) for (int k = -2; k <= 6; k++) put(level, chunkBB, bx, y + k, bz, post);
            if (a <= 3 && a != 2) put(level, chunkBB, bx, y + 5, bz, nuki);
            if (s == 0) put(level, chunkBB, bx, y + 6, bz, strut);
            if (a <= 3) put(level, chunkBB, bx, y + 7, bz, beam);
            put(level, chunkBB, bx, y + 8, bz, cap);
        }
        // 柱脚外侧略填，免得柱子悬空
        for (int s : new int[]{-2, 2}) {
            int bx = spanZ ? cx : cx + s, bz = spanZ ? cz + s : cz;
            for (int k = -5; k < -2; k++) {
                if (chunkBB.isInside(bx, y + k, bz) && level.getBlockState(new BlockPos(bx, y + k, bz)).isAir()) put(level, chunkBB, bx, y + k, bz, post);
            }
        }
    }

    private void lantern(WorldGenLevel level, BoundingBox chunkBB, int i, boolean outer, int[] near, int[] nearS) {
        if (i < 0 || i >= trail.size()) return;
        double[] o = outward(i);
        double sign = outer ? 1 : -1;
        int lx = Mth.floor(trail.x(i) + o[0] * 3.2 * sign), lz = Mth.floor(trail.z(i) + o[1] * 3.2 * sign);
        if (!chunkBB.isInside(lx, chunkBB.minY() + 1, lz) || !boundingBox.isInside(lx, boundingBox.minY() + 1, lz)) return;
        Col c = plan(level, lx, lz, near, nearS);
        if (c == null || c.path || c.stream) return;
        // 这一列已在本区块整形完毕，直接取整形后的地面
        int ground = groundTop(level, lx, lz, level.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) - 1) + 1;
        boolean foot = i >= trail.size() - 4;
        if (foot) {
            BlockState tall = FoxWildsBlocks.STONE_LANTERN_TALL.get().defaultBlockState();
            set(level, new BlockPos(lx, ground, lz), tall.setValue(TallStoneLanternBlock.HALF, DoubleBlockHalf.LOWER));
            set(level, new BlockPos(lx, ground + 1, lz), tall.setValue(TallStoneLanternBlock.HALF, DoubleBlockHalf.UPPER));
        } else {
            set(level, new BlockPos(lx, ground, lz), FoxWildsBlocks.STONE_LANTERN.get().defaultBlockState());
        }
    }

    private static Direction horizontal(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void put(WorldGenLevel level, BoundingBox chunkBB, int x, int y, int z, BlockState state) {
        if (chunkBB.isInside(x, y, z)) level.setBlock(new BlockPos(x, y, z), state, 2);
    }

    private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 2);
    }

    private long hash(int x, int z) {
        long h = frame.seed ^ (x * 341873128712L) ^ (z * 132897987541L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h & Long.MAX_VALUE;
    }

    private static double sq(double v) {
        return v * v;
    }
}
