package dev.logos.dimension.explosion;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 爆裂魔法留下的深坑。地面以上按半球削掉，地面以下是一只碗：中心一直挖到基岩（基岩本身挖不动，就成了坑底），往外渐浅，坑壁带一点起伏。
 * <p>
 * 几十个区块、上百万个方块，逐个 {@code setBlock} 会让服务器卡上好几秒，光照队列也会被塞满，所以这里绕过它：
 * <ul>
 *   <li>一个区块一个区块地挖，球面扫过整个区块之后才挖它（玩家看到的地形消失藏在白热的球体后面）；每刻有时间预算，超了留到下一刻；</li>
 *   <li>直接改区块的方块数据：不发邻居更新、不掉落、不触发方块自己的移除逻辑；方块实体直接拿掉，兴趣点（床、工作台等）一并注销；</li>
 *   <li>挖完一个区块重算高度图和天空光源，告诉光照引擎哪些子区块空了，再让它从新的天空光源往下补光（只会变亮，不用逐格检查）；
 *       挖掉的发光方块单独检查一次，免得留下没有来源的光；</li>
 *   <li>挖完马上把整个区块重发给看得到它的玩家，一个区块一个包，不按方块发，地形在白热的球体还在时就消失；
 *       光照在自己的线程上算完（几毫秒到几百毫秒）后再发一次，带上正确的光照。原版只给它看见变化的子区块补发光照，挖开的坑收不到。</li>
 * </ul>
 * 爆炸前给这一片加上传送门同款的加载票（15 秒自动过期），还没加载的区块在火柱和白热的那两秒里载进来；到时仍没加载的区块再等一会儿，超时就不挖了。
 */
final class Crater {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** 每刻最多花多少纳秒挖坑。 */
    private static final long BUDGET_NANOS = 6_000_000L;
    /** 区块一直没加载，最多等这么多刻。 */
    private static final int WAIT_TICKS = 200;
    private static final EnumSet<Heightmap.Types> HEIGHTMAPS = EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE);

    private record Column(ChunkPos pos, double far) {}

    private final ServerLevel level;
    private final Vec3 center;
    private final double radius, depth;
    private final int ground;
    private final @Nullable UUID caster;
    private final long seed;
    private final int ticketRadius;
    /** 按离中心最远的角排好的区块，还没挖的。 */
    private final List<Column> pending = new ArrayList<>();
    private int waited;
    /** 挖掉的方块数（世界测试和日志用）。 */
    long removed;
    int chunks;
    long nanos;
    /** 挖完到光照算完、重发出去最久等了多久，重发了几个区块（日志和客户端场景看）。 */
    long slowestResend;
    int resent, changed;

    Crater(ServerLevel level, Vec3 center, double radius, @Nullable UUID caster, long seed) {
        this.level = level;
        this.center = center;
        this.radius = radius;
        this.caster = caster;
        this.seed = seed;
        this.ground = Mth.floor(center.y);
        // 坑底停在最低一层之上：主世界的基岩就在那几层，挖不动的方块原样留着
        this.depth = Math.max(4, ground - level.getMinY());
        int r = Mth.ceil(radius) + 1;
        int x0 = SectionPos.blockToSectionCoord(Mth.floor(center.x) - r), x1 = SectionPos.blockToSectionCoord(Mth.floor(center.x) + r);
        int z0 = SectionPos.blockToSectionCoord(Mth.floor(center.z) - r), z1 = SectionPos.blockToSectionCoord(Mth.floor(center.z) + r);
        for (int cx = x0; cx <= x1; cx++)
            for (int cz = z0; cz <= z1; cz++) {
                double near = horizontal(cx, cz, false);
                if (near > radius) continue;
                pending.add(new Column(new ChunkPos(cx, cz), horizontal(cx, cz, true)));
            }
        pending.sort((a, b) -> Double.compare(a.far, b.far));
        this.ticketRadius = Math.max(1, Mth.ceil((radius + 8) / 16));
        level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos.containing(center)), ticketRadius);
    }

    /** 区块离中心最近（{@code far} 为 false）或最远的那一点的水平距离。 */
    private double horizontal(int cx, int cz, boolean far) {
        double minX = cx * 16, maxX = minX + 16, minZ = cz * 16, maxZ = minZ + 16;
        double dx, dz;
        if (far) {
            dx = Math.max(Math.abs(center.x - minX), Math.abs(center.x - maxX));
            dz = Math.max(Math.abs(center.z - minZ), Math.abs(center.z - maxZ));
        } else {
            dx = Math.max(0, Math.max(minX - center.x, center.x - maxX));
            dz = Math.max(0, Math.max(minZ - center.z, center.z - maxZ));
        }
        return Math.sqrt(dx * dx + dz * dz);
    }

    boolean done() {
        return pending.isEmpty();
    }

    /** 球面扫到了水平距离 {@code reach}：挖掉已经整个被扫过的区块；{@code all} 时不管扫没扫到，剩下的都挖。 */
    void advance(double reach, boolean all) {
        if (pending.isEmpty()) return;
        long start = Util.getNanos();
        boolean waiting = false;
        for (int i = 0; i < pending.size(); ) {
            Column c = pending.get(i);
            if (!all && c.far > reach) break;
            if (Util.getNanos() - start > BUDGET_NANOS) break;
            LevelChunk chunk = level.getChunkSource().getChunkNow(c.pos.x(), c.pos.z());
            if (chunk == null) {
                waiting = true;
                i++;
                continue;
            }
            carve(chunk);
            pending.remove(i);
        }
        nanos += Util.getNanos() - start;
        if (waiting && all && ++waited > WAIT_TICKS) pending.clear();
        if (pending.isEmpty()) {
            ExplosionMagic.lastCarved = removed;
            level.getChunkSource().removeTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos.containing(center)), ticketRadius);
            LOGGER.info("Explosion crater at {}: {} blocks in {} chunks, {} ms on the server thread",
                    BlockPos.containing(center), removed, chunks, nanos / 1_000_000);
        }
    }

    /** 坑底在 {@code (x, z)} 这一列有多深（从地面往下的格数），水平距离为 {@code d}。 */
    private double floorDepth(double d, int x, int z) {
        double k = 1 - (d / radius) * (d / radius);
        if (k <= 0) return 0;
        // 坑壁的起伏：两层粗糙的网格噪声
        double wobble = (noise(x >> 3, z >> 3) - .5) * 3 + (noise(x >> 1, z >> 1) - .5) * 1.2;
        // 中心不起伏：最深处总是挖到基岩
        return Math.max(0, depth * Math.pow(k, .8) + wobble * Math.min(1, k * 4) * Math.min(1, (1 - k) * 6));
    }

    private double noise(int x, int z) {
        long h = x * 341873128712L + z * 132897987541L + seed;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        return ((h ^ (h >>> 32)) & 0xFFFF) / 65535.0;
    }

    private void carve(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        LevelChunkSection[] sections = chunk.getSections();
        boolean[] wasEmpty = new boolean[sections.length];
        for (int i = 0; i < sections.length; i++) wasEmpty[i] = sections[i].hasOnlyAir();
        ServerPlayer owner = caster == null ? null : level.getServer().getPlayerList().getPlayer(caster);
        ThreadedLevelLightEngine light = level.getChunkSource().getLightEngine();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        int minY = level.getMinY(), maxY = level.getMaxY();
        long count = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = pos.getMinBlockX() + lx, z = pos.getMinBlockZ() + lz;
                double dx = x + .5 - center.x, dz = z + .5 - center.z;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius) continue;
                at.set(x, ground, z);
                // 出生点保护、世界边界：施法者自己都改不了的地方不挖
                if (!level.getWorldBorder().isWithinBounds(at) || owner != null && !level.mayInteract(owner, at)) continue;
                int top = Math.min(maxY, ground + (int) Math.floor(Math.sqrt(radius * radius - d * d)));
                int bottom = Math.max(minY, ground - (int) Math.round(floorDepth(d, x, z)));
                for (int y = bottom; y <= top; y++) {
                    int index = chunk.getSectionIndex(y);
                    LevelChunkSection section = sections[index];
                    if (section.hasOnlyAir()) {
                        // 整个子区块都是空的，直接跳到下一个子区块
                        y = (y & ~15) + 15;
                        continue;
                    }
                    int ly = y & 15;
                    BlockState state = section.getBlockState(lx, ly, lz);
                    if (state.isAir()) continue;
                    at.set(x, y, z);
                    if (state.getDestroySpeed(level, at) < 0) continue;
                    if (state.hasBlockEntity()) chunk.removeBlockEntity(at);
                    if (PoiTypes.forState(state).isPresent()) level.getPoiManager().remove(at.immutable());
                    section.setBlockState(lx, ly, lz, air, false);
                    if (state.getLightEmission() > 0) light.checkBlock(at.immutable());
                    count++;
                }
            }
        }
        chunks++;
        if (count == 0) return;
        removed += count;
        chunk.markUnsaved();
        Heightmap.primeHeightmaps(chunk, HEIGHTMAPS);
        chunk.getSkyLightSources().fillFrom(chunk);
        for (int i = 0; i < sections.length; i++) {
            boolean empty = sections[i].hasOnlyAir();
            if (empty == wasEmpty[i]) continue;
            int sectionY = level.getSectionYFromSectionIndex(i);
            light.updateSectionStatus(SectionPos.of(pos, sectionY), empty);
            level.getChunkSource().onSectionEmptinessChanged(pos.x(), sectionY, pos.z(), empty);
        }
        light.propagateLightSources(pos);
        changed++;
        long carvedAt = Util.getNanos();
        // 挖完马上把整个区块发出去：地形在白热的球体后面消失。这时的光照还是旧的（新挖开的地方全黑），
        // 光照在自己的线程上算完后再发一次：原版只给它看见变化的子区块补发光照，挖开的坑收不到，不再发一次会一直是黑的
        resend(pos);
        light.waitForPendingTasks(pos.x(), pos.z()).thenRunAsync(() -> {
            // 服务器正在关：任务就地跑在光照线程上，什么都不做
            if (!level.getServer().isSameThread()) return;
            resend(pos);
            slowestResend = Math.max(slowestResend, Util.getNanos() - carvedAt);
            if (++resent == changed && pending.isEmpty())
                LOGGER.info("Explosion crater at {}: all {} chunks relit and resent, slowest {} ms after carving", BlockPos.containing(center), resent,
                        slowestResend / 1_000_000);
        }, level.getServer());
    }

    private void resend(ChunkPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (chunk == null) return;
        var players = level.getChunkSource().chunkMap.getPlayers(pos, false);
        if (players.isEmpty()) return;
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getChunkSource().getLightEngine(), null, null);
        for (ServerPlayer p : players) p.connection.send(packet);
    }
}
