package dev.logos.dimension.dawnvow;

import dev.logos.dimension.cloudsea.CloudseaBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import static dev.logos.dimension.dawnvow.DawnvowGrottoStructure.HEIGHT;
import static dev.logos.dimension.dawnvow.DawnvowGrottoStructure.RADIUS;

/**
 * 曦誓之洞的开凿：一间圆顶山洞（洞壁按角度起伏），中央一座矮石丘托着石座，石丘一侧有一汪小池，
 * 一条约三格宽、四格高的窄洞平缓地通到山坡上。洞底铺苔，种着远行大陆的蕨、铃兰、露灯菌与微光的植物，
 * 洞顶零星的发光地衣与垂根，几串发光浆果；洞口两侧爬着藤蔓。
 *
 * <p>洞外一圈与洞顶上方的空隙（天然洞穴、含水层）先用石头补上，山洞只从那条窄洞进出。
 * 每个区块只改自己范围内的方块；所有形状都是坐标与种子的纯函数，与区块生成顺序无关。
 */
public class GrottoPiece extends StructurePiece {
    /** 中央石丘的半径与高度。 */
    private static final double MOUND_R = 6, MOUND_H = 3.2;
    private final int cx, floor, cz, exitR, exitY;
    private final double angle, ux, uz;
    private final long seed;

    GrottoPiece(int cx, int floor, int cz, double angle, int exitR, int exitY, long seed, BoundingBox box) {
        super(Dawnvow.GROTTO_PIECE.get(), 0, box);
        this.cx = cx;
        this.floor = floor;
        this.cz = cz;
        this.angle = angle;
        this.exitR = exitR;
        this.exitY = exitY;
        this.seed = seed;
        this.ux = Math.cos(angle);
        this.uz = Math.sin(angle);
    }

    public GrottoPiece(CompoundTag tag) {
        super(Dawnvow.GROTTO_PIECE.get(), tag);
        this.cx = tag.getIntOr("CX", 0);
        this.floor = tag.getIntOr("Floor", 80);
        this.cz = tag.getIntOr("CZ", 0);
        this.angle = tag.getDoubleOr("Angle", 0);
        this.exitR = tag.getIntOr("ExitR", 24);
        this.exitY = tag.getIntOr("ExitY", 80);
        this.seed = tag.getLongOr("Seed", 0);
        this.ux = Math.cos(angle);
        this.uz = Math.sin(angle);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("CX", cx);
        tag.putInt("Floor", floor);
        tag.putInt("CZ", cz);
        tag.putDouble("Angle", angle);
        tag.putInt("ExitR", exitR);
        tag.putInt("ExitY", exitY);
        tag.putLong("Seed", seed);
    }

    /** 石座所在的位置。 */
    public BlockPos stone() {
        return new BlockPos(cx, floorAt(cx, cz) + 1, cz);
    }

    /** 洞口离洞中心多远（开发预览用）。 */
    public int exitR() {
        return exitR;
    }

    /** 隧道里离洞中心 r 格处、地面上方一格（开发预览用）。 */
    public Vec3 tunnelPoint(double r) {
        return new Vec3(cx + 0.5 + ux * r, tunnelFloor(r) + 1, cz + 0.5 + uz * r);
    }

    /** 洞底离中心 r 格、朝 turn（弧度，0 为洞口方向）处地面上方一格（开发预览用）。 */
    public Vec3 floorPoint(double r, double turn) {
        double a = angle + turn;
        int x = Mth.floor(cx + 0.5 + Math.cos(a) * r), z = Mth.floor(cz + 0.5 + Math.sin(a) * r);
        return new Vec3(x + 0.5, floorAt(x, z) + 1, z + 0.5);
    }

    // ---- 形状：坐标与种子的纯函数 ------------------------------------------------------------

    private double hash(int x, int y, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ (salt * 0x27D4EB2F165667C5L);
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
        return ((h ^ (h >>> 32)) >>> 11) * 0x1.0p-53;
    }

    /** 平滑的二维值噪声（0–1）。 */
    private double noise(double x, double z, double scale, int salt) {
        double fx = x / scale, fz = z / scale;
        int ix = Mth.floor(fx), iz = Mth.floor(fz);
        double a = Mth.smoothstep(fx - ix), b = Mth.smoothstep(fz - iz);
        return Mth.lerp2(a, b, hash(ix, 0, iz, salt), hash(ix + 1, 0, iz, salt), hash(ix, 0, iz + 1, salt), hash(ix + 1, 0, iz + 1, salt));
    }

    /** 这一列离洞壁多远：0 在中心，1 在洞壁；洞壁半径随角度起伏。 */
    private double q(int x, int z) {
        double dx = x - cx, dz = z - cz;
        double theta = Math.atan2(dz, dx);
        double p1 = hash(1, 0, 0, 7) * Mth.TWO_PI, p2 = hash(2, 0, 0, 7) * Mth.TWO_PI;
        double wall = RADIUS * (1 + 0.13 * (0.6 * Math.sin(3 * theta + p1) + 0.4 * Math.sin(5 * theta + p2)) + 0.08 * (noise(x, z, 5, 3) - 0.5));
        return Math.sqrt(dx * dx + dz * dz) / wall;
    }

    private double mound(int x, int z) {
        double d = Math.hypot(x - cx, z - cz) / MOUND_R;
        if (d >= 1) return 0;
        return MOUND_H * (1 - d * d) + 0.6 * (noise(x, z, 3, 11) - 0.5);
    }

    /** 小池：石丘后侧偏一边的椭圆，返回 0 在池心、1 在池沿。 */
    private double pond(int x, int z) {
        double a = angle + Math.PI * 0.8, px = cx + Math.cos(a) * 8, pz = cz + Math.sin(a) * 8;
        double along = (x - px) * Math.cos(a) + (z - pz) * Math.sin(a), across = -(x - px) * Math.sin(a) + (z - pz) * Math.cos(a);
        return Math.sqrt(along * along / (2.4 * 2.4) + across * across / (3.6 * 3.6));
    }

    /** 洞底这一列最高的实心方块；不在洞内时返回 {@link Integer#MIN_VALUE}。 */
    private int floorAt(int x, int z) {
        double q = q(x, z);
        if (q >= 1) return Integer.MIN_VALUE;
        double m = mound(x, z);
        if (m >= 0.5) return floor + (int) Math.round(m);
        if (pond(x, z) < 1) return floor - (pond(x, z) < 0.55 ? 2 : 1);
        return floor + (int) Math.floor(1.8 * q * q * q * q + 0.9 * noise(x, z, 4, 5));
    }

    private int ceilingAt(int x, int z) {
        double q = q(x, z);
        if (q >= 1) return Integer.MIN_VALUE;
        return floor + (int) Math.round(HEIGHT * Math.pow(1 - q * q, 0.6) + 2.2 * (noise(x, z, 4, 9) - 0.5));
    }

    /** 隧道离洞中心 t 格处的地面高度：从洞底平缓地升（或降）到洞口。 */
    private int tunnelFloor(double t) {
        double k = Mth.clamp((t - (RADIUS - 3)) / (exitR - (RADIUS - 3)), 0, 1);
        return (int) Math.round(Mth.lerp(k, floor, exitY));
    }

    // ---- 逐列开凿 --------------------------------------------------------------------------

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
                            BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos) {
        int minX = Math.max(chunkBB.minX(), boundingBox.minX()), maxX = Math.min(chunkBB.maxX(), boundingBox.maxX());
        int minZ = Math.max(chunkBB.minZ(), boundingBox.minZ()), maxZ = Math.min(chunkBB.maxZ(), boundingBox.maxZ());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                carve(level, pos, x, z);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                decorate(level, pos, x, z);
            }
        }
        BlockPos stone = stone();
        if (chunkBB.isInside(stone)) {
            Direction facing = Direction.getApproximateNearest(ux, 0, uz);
            level.setBlock(stone, Dawnvow.STONE.get().defaultBlockState().setValue(SwordStoneBlock.FACING, facing), Block.UPDATE_CLIENTS);
        }
    }

    private void carve(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
        int surface = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        double q = q(x, z);
        double dx = x - cx, dz = z - cz, t = dx * ux + dz * uz, s = Math.abs(-dx * uz + dz * ux);
        boolean tunnel = t >= RADIUS - 4 && t <= exitR + 3 && s <= 1.6 + 0.5 * noise(x, z, 3, 13);
        int tunnelY = tunnelFloor(t);
        // 1. 补洞：洞外一圈与洞顶上方的天然空隙、积水填成石头，免得别的洞穴、水或岩浆漏进来
        if (q < 1.3) {
            int fl = q < 1 ? floorAt(x, z) : floor;
            for (int y = Math.min(floor, fl) - 3; y <= floor + HEIGHT + 3; y++) {
                if (y >= surface - 2) break;
                if (tunnel && y > tunnelY - 2 && y <= tunnelY + 5) continue;
                pos.set(x, y, z);
                BlockState here = level.getBlockState(pos);
                if (here.isAir() || !here.getFluidState().isEmpty()) set(level, pos, wall(x, y, z));
            }
        }
        // 2. 山洞本身
        if (q < 1) {
            int fl = floorAt(x, z), ce = Math.max(ceilingAt(x, z), fl + 1);
            if (ce > fl + 1 || q < 0.95) {
                for (int y = fl + 1; y <= ce; y++) {
                    pos.set(x, y, z);
                    set(level, pos, y <= floor && pond(x, z) < 1 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
                boolean onMound = mound(x, z) >= 0.5;
                for (int y = floor - 3; y <= fl; y++) {
                    pos.set(x, y, z);
                    BlockState here = level.getBlockState(pos);
                    if (y == fl) set(level, pos, onMound ? moundTop(x, z) : pond(x, z) < 1 ? pondBed(x, y, z) : ground(x, z));
                    else if (onMound && y > floor - 1) set(level, pos, moundStone(x, y, z));
                    else if (here.isAir() || !here.getFluidState().isEmpty()) set(level, pos, wall(x, y, z));
                }
            }
        }
        // 3. 窄洞：只挖在山体里，洞口外不往空中搭路
        if (tunnel && surface > tunnelY) {
            int head = s < 1.0 ? 4 : 3;
            boolean inCave = q < 1;
            for (int y = tunnelY + 1; y <= tunnelY + head; y++) {
                if (inCave && y <= floorAt(x, z)) continue;
                pos.set(x, y, z);
                set(level, pos, Blocks.AIR.defaultBlockState());
            }
            if (!inCave) {
                pos.set(x, tunnelY, z);
                set(level, pos, hash(x, tunnelY, z, 21) < 0.4 ? Blocks.GRAVEL.defaultBlockState() : hash(x, tunnelY, z, 22) < 0.5
                    ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState());
                for (int y = tunnelY - 1; y >= tunnelY - 3; y--) {
                    pos.set(x, y, z);
                    BlockState here = level.getBlockState(pos);
                    if (here.isAir() || !here.getFluidState().isEmpty()) set(level, pos, wall(x, y, z));
                }
            }
        }
    }

    private void decorate(WorldGenLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
        double q = q(x, z);
        if (q < 1) {
            int fl = floorAt(x, z), ce = ceilingAt(x, z);
            if (ce <= fl + 1) return;
            boolean nearStone = Math.abs(x - cx) <= 1 && Math.abs(z - cz) <= 1;
            // 洞底：苔上种植物，石丘顶上石座周围留空，小池里漂几片睡莲
            pos.set(x, fl + 1, z);
            if (pond(x, z) < 1) {
                if (pond(x, z) < 0.85 && hash(x, 0, z, 31) < 0.16) set(level, pos.set(x, floor + 1, z), Blocks.LILY_PAD.defaultBlockState());
            } else if (!nearStone && level.getBlockState(pos.below()).is(Blocks.MOSS_BLOCK) && level.getBlockState(pos).isAir()) {
                BlockState plant = plant(x, z);
                if (plant != null) set(level, pos, plant);
            }
            // 洞顶：发光地衣、垂根，零星几串发光浆果，正中一朵孢子花
            pos.set(x, ce, z);
            if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isFaceSturdy(level, pos.above(), Direction.DOWN)) return;
            double h = hash(x, ce, z, 41);
            if (h < 0.004 && q > 0.25) {
                set(level, pos, Blocks.SPORE_BLOSSOM.defaultBlockState());
            } else if (h < 0.022 && q > 0.3) {
                int length = 1 + (int) (hash(x, ce, z, 42) * 4);
                for (int i = 0; i < length && ce - i > fl + 2; i++) {
                    boolean berries = hash(x, ce - i, z, 43) < 0.35;
                    boolean last = i == length - 1 || ce - i - 1 <= fl + 2;
                    BlockState vine = last
                        ? Blocks.CAVE_VINES.defaultBlockState().setValue(GrowingPlantHeadBlock.AGE, GrowingPlantHeadBlock.MAX_AGE).setValue(CaveVines.BERRIES, berries)
                        : Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(CaveVines.BERRIES, berries);
                    set(level, pos.set(x, ce - i, z), vine);
                }
            } else if (h < 0.035) {
                set(level, pos, Blocks.HANGING_ROOTS.defaultBlockState());
            } else if (h < 0.17) {
                set(level, pos, Blocks.GLOW_LICHEN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.UP), true));
            }
            return;
        }
        // 洞口：最外几格的两侧洞壁爬着藤蔓
        double dx = x - cx, dz = z - cz, t = dx * ux + dz * uz, s = Math.abs(-dx * uz + dz * ux);
        if (t < exitR - 5 || t > exitR + 3 || s > 2.6) return;
        int y0 = tunnelFloor(t);
        for (int y = y0 + 1; y <= y0 + 4; y++) {
            pos.set(x, y, z);
            if (!level.getBlockState(pos).isAir()) continue;
            BlockState vine = Blocks.VINE.defaultBlockState();
            boolean attached = false;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(side);
                if (level.getBlockState(next).isFaceSturdy(level, next, side.getOpposite()) && hash(x, y, z, 50 + side.ordinal()) < 0.55) {
                    vine = vine.setValue(VineBlock.getPropertyForFace(side), true);
                    attached = true;
                }
            }
            BlockPos up = pos.above();
            if (level.getBlockState(up).isFaceSturdy(level, up, Direction.DOWN) && hash(x, y, z, 49) < 0.6) {
                vine = vine.setValue(VineBlock.UP, true);
                attached = true;
            }
            if (attached) set(level, pos, vine);
        }
    }

    // ---- 材料 ---------------------------------------------------------------------------

    private BlockState wall(int x, int y, int z) {
        double h = hash(x, y, z, 61);
        return h < 0.7 ? Blocks.STONE.defaultBlockState() : h < 0.9 ? Blocks.ANDESITE.defaultBlockState() : Blocks.TUFF.defaultBlockState();
    }

    private BlockState ground(int x, int z) {
        double n = noise(x, z, 3.5, 71), h = hash(x, 0, z, 72);
        if (n < 0.62) return Blocks.MOSS_BLOCK.defaultBlockState();
        return h < 0.35 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : h < 0.6 ? Blocks.STONE.defaultBlockState()
            : h < 0.8 ? Blocks.ANDESITE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
    }

    private BlockState pondBed(int x, int y, int z) {
        return hash(x, y, z, 81) < 0.5 ? Blocks.GRAVEL.defaultBlockState() : Blocks.CLAY.defaultBlockState();
    }

    private BlockState moundTop(int x, int z) {
        double d = Math.hypot(x - cx, z - cz);
        if (d < 1.6) return Blocks.STONE.defaultBlockState();
        return noise(x, z, 2.5, 91) < 0.55 ? Blocks.MOSS_BLOCK.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
    }

    private BlockState moundStone(int x, int y, int z) {
        double h = hash(x, y, z, 92);
        return h < 0.4 ? Blocks.STONE.defaultBlockState() : h < 0.65 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
            : h < 0.85 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.ANDESITE.defaultBlockState();
    }

    /** 苔地上的一株植物，约三成的格子有；远行大陆的蕨与花为主，几株自己会发微光。 */
    private BlockState plant(int x, int z) {
        double h = hash(x, 1, z, 101);
        if (h > 0.42) return null;
        double k = hash(x, 2, z, 102);
        if (k < 0.22) return CloudseaBlocks.FERN.get().defaultBlockState();
        if (k < 0.36) return CloudseaBlocks.LUMEN_FERN.get().defaultBlockState();
        if (k < 0.46) return CloudseaBlocks.STARDEW.get().defaultBlockState();
        if (k < 0.58) return CloudseaBlocks.MOONBELL.get().defaultBlockState();
        if (k < 0.66) return CloudseaBlocks.GLOWCAP.get().defaultBlockState();
        if (k < 0.76) return CloudseaBlocks.ANEMONE.get().defaultBlockState();
        if (k < 0.82) return CloudseaBlocks.HELLEBORE.get().defaultBlockState();
        return Blocks.MOSS_CARPET.defaultBlockState();
    }

    private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        if (state.is(Blocks.WATER)) level.scheduleTick(pos.immutable(), Fluids.WATER, 2);
    }
}
