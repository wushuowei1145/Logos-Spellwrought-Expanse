package dev.logos.dimension.fox;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 地下封印场地的岩洞：按设计数据挖空大鸟居之间、封印之间与楼门门洞，外面包一层几格厚的岩壳，
 * 把天然洞穴与含水层隔在外面。洞里的建筑、黑水与布置是另外的模板，排在这个部件之后放。
 *
 * <p>另外记下封印杭的位置与封印之间的范围，供破坏封印杭时清点剩下几道（以后的九尾狐首领战也从这里找场地）。
 */
public class SanctuaryVaultPiece extends StructurePiece {
    private final SanctuaryFrame frame;
    /** 挖空范围，每 6 个一组：u0, y0, v0, u1, y1, v1（设计坐标，y 相对主街标高，含两端）。 */
    private final int[] boxes;
    private final int shell;
    /** 封印杭下半格的设计坐标，每 3 个一组。 */
    private final int[] seals;
    /** 封印之间 [u0, v0, u1, v1, 地面层, 顶层] 与杀生石脚下的设计点。 */
    private final int[] arena, center;
    /** 开发预览用：井心 (u, v)、堂的地面高度（相对主街）与通道中线折点。 */
    private final int[] route;
    /** 可拆的三组范围，见 {@link Removable}；每组 3 个数（杀生石与石台：半径、最低层、最高层；底座：半宽、最低层、最高层）。 */
    private final int[] removable;
    /** 先前试玩存档里的岩洞没有记这一项，按当时的设计补上。 */
    private static final int[] REMOVABLE_V1 = {7, -55, -42, 7, -57, -56, 1, -57, -55};

    /** 首领战开始时可以整块拆掉的三组东西。 */
    public enum Removable {
        /** 杀生石，连同半腰的注连绳、纸垂与裂缝里的灵魂土和狐火。 */
        STONE,
        /** 杀生石脚下的两级黑石台。 */
        ALTAR,
        /** 九个封印杭底座，连同还没拆的封印杭与两旁的狐火。 */
        PEDESTALS
    }

    SanctuaryVaultPiece(SanctuaryFrame frame, SanctuaryUndercroft data, SanctuaryLayout.Route route) {
        super(FoxSanctuary.VAULT.get(), 0, bounds(frame, data.boxes, data.shell));
        this.frame = frame;
        this.boxes = data.boxes.clone();
        this.shell = data.shell;
        this.seals = data.seals.clone();
        this.arena = data.arena.clone();
        this.center = data.center.clone();
        int[] r = new int[3 + route.path().length];
        r[0] = route.shaftU();
        r[1] = route.shaftV();
        r[2] = route.groundY();
        System.arraycopy(route.path(), 0, r, 3, route.path().length);
        this.route = r;
        this.removable = data.removable.clone();
    }

    public SanctuaryVaultPiece(CompoundTag tag) {
        super(FoxSanctuary.VAULT.get(), tag);
        this.frame = new SanctuaryFrame(tag.getIntOr("Plan", 0), tag.getIntOr("OX", 0), tag.getIntOr("OZ", 0),
            Mirror.valueOf(tag.getStringOr("Mirror", "NONE")), Rotation.valueOf(tag.getStringOr("Rot", "NONE")),
            tag.getIntOr("P", 100), tag.getIntOr("Foot", 70), tag.getLongOr("Seed", 0));
        int[] none = new int[0];
        this.boxes = tag.getIntArray("Boxes").orElse(none);
        this.shell = tag.getIntOr("Shell", 3);
        this.seals = tag.getIntArray("Seals").orElse(none);
        this.arena = tag.getIntArray("Arena").orElse(new int[6]);
        this.center = tag.getIntArray("Center").orElse(new int[3]);
        this.route = tag.getIntArray("Route").orElse(new int[3]);
        this.removable = tag.getIntArray("Removable").orElse(REMOVABLE_V1.clone());
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
        tag.putIntArray("Boxes", boxes);
        tag.putInt("Shell", shell);
        tag.putIntArray("Seals", seals);
        tag.putIntArray("Arena", arena);
        tag.putIntArray("Center", center);
        tag.putIntArray("Route", route);
        tag.putIntArray("Removable", removable);
    }

    private static BoundingBox bounds(SanctuaryFrame frame, int[] boxes, int shell) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int i = 0; i + 5 < boxes.length; i += 6) {
            for (int u : new int[]{boxes[i] - shell, boxes[i + 3] + shell}) {
                for (int v : new int[]{boxes[i + 2] - shell, boxes[i + 5] + shell}) {
                    BlockPos w = frame.world(u, 0, v);
                    minX = Math.min(minX, w.getX());
                    maxX = Math.max(maxX, w.getX());
                    minZ = Math.min(minZ, w.getZ());
                    maxZ = Math.max(maxZ, w.getZ());
                }
            }
            minY = Math.min(minY, frame.p + boxes[i + 1] - shell);
            maxY = Math.max(maxY, frame.p + boxes[i + 4] + shell);
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
                            BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos) {
        int minX = Math.max(chunkBB.minX(), boundingBox.minX()), maxX = Math.min(chunkBB.maxX(), boundingBox.maxX());
        int minZ = Math.max(chunkBB.minZ(), boundingBox.minZ()), maxZ = Math.min(chunkBB.maxZ(), boundingBox.maxZ());
        if (minX > maxX || minZ > maxZ) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int[] l = frame.local(x, z);
                int u = l[0], v = l[1];
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int i = 0; i + 5 < boxes.length; i += 6) {
                    if (u >= boxes[i] - shell && u <= boxes[i + 3] + shell && v >= boxes[i + 2] - shell && v <= boxes[i + 5] + shell) {
                        lo = Math.min(lo, boxes[i + 1] - shell);
                        hi = Math.max(hi, boxes[i + 4] + shell);
                    }
                }
                if (lo > hi) continue;
                for (int y = lo; y <= hi; y++) {
                    boolean inside = false, wall = false;
                    for (int i = 0; i + 5 < boxes.length; i += 6) {
                        if (u >= boxes[i] && u <= boxes[i + 3] && v >= boxes[i + 2] && v <= boxes[i + 5] && y >= boxes[i + 1] && y <= boxes[i + 4]) {
                            inside = true;
                            break;
                        }
                        if (u >= boxes[i] - shell && u <= boxes[i + 3] + shell && v >= boxes[i + 2] - shell && v <= boxes[i + 5] + shell
                            && y >= boxes[i + 1] - shell && y <= boxes[i + 4] + shell) {
                            wall = true;
                        }
                    }
                    pos.set(x, frame.p + y, z);
                    if (inside) level.setBlock(pos, air, 2);
                    else if (wall) level.setBlock(pos, rock(x, frame.p + y, z), 2);
                }
            }
        }
    }

    /** 岩壳：深板岩为主，掺凝灰岩、圆石深板岩、安山岩与一点玄武岩，看着是天然的洞壁。 */
    private BlockState rock(int x, int y, int z) {
        long h = frame.seed ^ (x * 341873128712L) ^ (z * 132897987541L) ^ (y * 0x9E3779B97F4A7C15L);
        h ^= h >>> 31;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 29;
        int r = (int) ((h & Long.MAX_VALUE) % 100);
        Block b = r < 38 ? Blocks.DEEPSLATE : r < 52 ? Blocks.COBBLED_DEEPSLATE : r < 68 ? Blocks.TUFF : r < 78 ? Blocks.STONE
            : r < 86 ? Blocks.ANDESITE : r < 93 ? Blocks.SMOOTH_BASALT : Blocks.BLACKSTONE;
        return b.defaultBlockState();
    }

    // ---------------------------------------------------------------- 封印

    /** 封印杭下半格的世界坐标。 */
    public List<BlockPos> seals() {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i + 2 < seals.length; i += 3) out.add(frame.world(seals[i], frame.p + seals[i + 1], seals[i + 2]));
        return out;
    }

    /** 这个位置在不在封印之间里（空气范围内）。 */
    public boolean inArena(BlockPos pos) {
        int[] l = frame.local(pos.getX(), pos.getZ());
        int y = pos.getY() - frame.p;
        return l[0] >= arena[0] && l[0] <= arena[2] && l[1] >= arena[1] && l[1] <= arena[3] && y >= arena[4] && y <= arena[5];
    }

    /** 杀生石脚下（石台顶面上一格）的世界坐标：以后九尾狐从这里出来。 */
    public BlockPos sealStone() {
        return frame.world(center[0], frame.p + center[1], center[2]);
    }

    /** 这一组占的全部世界坐标（含已经是空气的格子），自上而下排好：拆的时候先拆上面，灯笼与狐火不会掉成物品。 */
    public List<BlockPos> blocks(Removable group) {
        List<BlockPos> out = new ArrayList<>();
        int k = group.ordinal() * 3, size = removable[k], y0 = removable[k + 1], y1 = removable[k + 2];
        for (int y = y1; y >= y0; y--) {
            if (group == Removable.PEDESTALS) {
                for (int i = 0; i + 2 < seals.length; i += 3) {
                    for (int du = -size; du <= size; du++) {
                        for (int dv = -size; dv <= size; dv++) out.add(frame.world(seals[i] + du, frame.p + y, seals[i + 2] + dv));
                    }
                }
            } else {
                for (int du = -size; du <= size; du++) {
                    for (int dv = -size; dv <= size; dv++) {
                        if (du * du + dv * dv <= size * size) out.add(frame.world(center[0] + du, frame.p + y, center[2] + dv));
                    }
                }
            }
        }
        return out;
    }

    /** 把这一组拆成空气（不掉落任何东西），返回拆掉的方块数。下面露出的是圆台原本的地面。 */
    public int clear(net.minecraft.server.level.ServerLevel level, Removable group) {
        int n = 0;
        for (BlockPos pos : blocks(group)) {
            if (level.getBlockState(pos).isAir()) continue;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- 开发预览

    /** 设计坐标 (u, v)、相对主街 dy 处的世界位置。 */
    public Vec3 designPoint(double u, double dy, double v) {
        double[] w = frame.worldCont(u, v);
        return new Vec3(w[0], frame.p + dy, w[1]);
    }

    /** 井心的设计坐标 (u, v) 与封じの堂地面那一层的高度（相对主街）。 */
    public int[] shaft() {
        return new int[]{route[0], route[1], route[2]};
    }

    /** 通道中线的折点（设计坐标），每 2 个一组。 */
    public int[] path() {
        int[] out = new int[route.length - 3];
        System.arraycopy(route, 3, out, 0, out.length);
        return out;
    }

    public int streetLevel() {
        return frame.p;
    }
}
