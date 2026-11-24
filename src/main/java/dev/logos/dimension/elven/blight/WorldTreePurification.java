package dev.logos.dimension.elven.blight;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jspecify.annotations.Nullable;

/** 战后只还原有记忆的污染，按区块写回和重发；进度存档，未加载区域以后接着做。 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class WorldTreePurification extends SavedData {
    public record Region(BlockPos center, int radius, LongOpenHashSet done) {
        static final Codec<Region> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("center").forGetter(Region::center), Codec.intRange(16, 256).fieldOf("radius").forGetter(Region::radius),
                Codec.LONG.listOf().fieldOf("done").forGetter(r -> Arrays.stream(r.done.toLongArray()).boxed().toList()))
                .apply(i, (p, r, d) -> new Region(p, r, new LongOpenHashSet(d))));
        boolean contains(ChunkPos p) {
            double dx = Math.max(0, Math.abs(p.getMinBlockX() + 8 - center.getX()) - 8);
            double dz = Math.max(0, Math.abs(p.getMinBlockZ() + 8 - center.getZ()) - 8);
            return dx * dx + dz * dz <= (double) radius * radius;
        }
    }
    public static final Codec<WorldTreePurification> CODEC = Region.CODEC.listOf().xmap(WorldTreePurification::new, d -> d.regions);
    private static final SavedDataType<WorldTreePurification> TYPE = new SavedDataType<>(LogosDimension.id("queen_purification"), () -> new WorldTreePurification(List.of()), CODEC);
    private static final long BUDGET = 6_000_000;
    private final List<Region> regions;
    private final ArrayDeque<ChunkPos> pending = new ArrayDeque<>();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private long restored, worstNanos;
    private WorldTreePurification(List<Region> regions) { this.regions = new ArrayList<>(regions); }
    public static WorldTreePurification get(ServerLevel level) { return level.getDataStorage().computeIfAbsent(TYPE); }
    public long restored() { return restored; }
    public long worstNanos() { return worstNanos; }
    public boolean pending() { return !pending.isEmpty(); }
    public boolean started(BlockPos center) { return regions.stream().anyMatch(r -> r.center.equals(center)); }
    public void start(ServerLevel world, BlockPos center, int radius) {
        if (started(center)) return;
        Region region = new Region(center.immutable(), Math.clamp(radius, 16, 256), new LongOpenHashSet());
        regions.add(region); setDirty();
        int r = (region.radius + 15) / 16;
        var origin = ChunkPos.containing(center);
        List<ChunkPos> loaded = new ArrayList<>();
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            var at = new ChunkPos(origin.x() + x, origin.z() + z);
            if (region.contains(at) && world.getChunkSource().getChunkNow(at.x(), at.z()) != null) loaded.add(at);
        }
        loaded.sort(Comparator.comparingDouble(p -> { double x = p.getMinBlockX() + 8 - center.getX(), z = p.getMinBlockZ() + 8 - center.getZ(); return x*x + z*z; }));
        loaded.forEach(this::enqueue);
    }
    private void enqueue(ChunkPos pos) { if (queued.add(pos.pack())) pending.addLast(pos); }
    private void loaded(ChunkPos pos) { if (regions.stream().anyMatch(r -> r.contains(pos) && !r.done.contains(pos.pack()))) enqueue(pos); }
    private static boolean candidate(BlockState s) { return Corruption.isNetworkBlock(s) || s.is(ElvenBlight.LUMEN.get()) || s.is(ElvenBlight.WOOD.get()) || s.is(ElvenBlight.LEAVES.get()) || s.is(ElvenBlight.NODE.get()); }
    public static @Nullable BlockState original(ServerLevel world, BlockPos at, BlockState state) {
        if (state.getBlock() instanceof BlightMemoryBlock) return BlightMemoryBlock.original(state);
        if (state.is(ElvenBlight.LUMEN.get())) return BuiltInRegistries.BLOCK.getValue(LogosDimension.id("elven_luminous_inlay")).defaultBlockState();
        if (state.is(ElvenBlight.WOOD.get())) return BuiltInRegistries.BLOCK.getValue(LogosDimension.id("elven_heartwood")).defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS));
        if (state.is(ElvenBlight.LEAVES.get())) return BuiltInRegistries.BLOCK.getValue(LogosDimension.id("elven_canopy")).defaultBlockState();
        if (state.getBlock() instanceof CorruptionGrowthBlock || state.is(Corruption.VEIN.get()) || state.is(ElvenBlight.NODE.get())) return state.getFluidState().createLegacyBlock();
        var network = CorruptionNetworks.get(world).at(at);
        // 没有原材质的旧污秽不能猜成土或空气，玩家改建也不会进入候选。
        return state.getBlock() instanceof CorruptionBlock && network != null ? network.original(at) : null;
    }
    private void tick(ServerLevel world) {
        long began = System.nanoTime();
        while (!pending.isEmpty()) {
            ChunkPos pos = pending.removeFirst(); queued.remove(pos.pack());
            var chunk = world.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk != null) for (Region region : regions) if (region.contains(pos) && !region.done.contains(pos.pack())) {
                restore(world, chunk, region); region.done.add(pos.pack()); setDirty();
            }
            if (System.nanoTime() - began >= BUDGET) break;
        }
        worstNanos = Math.max(worstNanos, System.nanoTime() - began);
    }
    private void restore(ServerLevel world, LevelChunk chunk, Region region) {
        var sections = chunk.getSections(); var pos = chunk.getPos();
        var light = world.getChunkSource().getLightEngine();
        var networks = CorruptionNetworks.get(world);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int changed = 0;
        boolean[] biomesChanged = {false};
        var healthy = world.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(ResourceKey.create(Registries.BIOME, LogosDimension.id("elven_forest")));
        var corrupted = ResourceKey.create(Registries.BIOME, LogosDimension.id("blighted_elven_forest"));
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            var section = sections[sectionIndex];
            if (section.getBiomes().maybeHas(b -> b.is(corrupted))) {
                section.fillBiomesFromNoise((qx, qy, qz, sampler) -> {
                    var old = section.getNoiseBiome(qx & 3, qy & 3, qz & 3);
                    if (old.is(corrupted) && new BlockPos(QuartPos.toBlock(qx) + 2, QuartPos.toBlock(qy) + 2, QuartPos.toBlock(qz) + 2).distSqr(region.center) <= (double) region.radius * region.radius) {
                        biomesChanged[0] = true; return healthy;
                    }
                    return old;
                }, world.getChunkSource().randomState().sampler(), QuartPos.fromBlock(pos.getMinBlockX()), QuartPos.fromBlock(world.getSectionYFromSectionIndex(sectionIndex) << 4), QuartPos.fromBlock(pos.getMinBlockZ()));
            }
            if (section.hasOnlyAir() || !section.getStates().maybeHas(WorldTreePurification::candidate)) continue;
            boolean emptyBefore = section.hasOnlyAir();
            for (int i = 0; i < 4096; i++) {
                int x = i & 15, z = i >> 4 & 15, y = i >> 8;
                BlockState state = section.getBlockState(x, y, z);
                if (!candidate(state)) continue;
                at.set(pos.getMinBlockX() + x, (world.getSectionYFromSectionIndex(sectionIndex) << 4) + y, pos.getMinBlockZ() + z);
                if (at.distSqr(region.center) > (double) region.radius * region.radius) continue;
                BlockState target = original(world, at, state);
                if (target == null || target == state || target.hasBlockEntity()) continue;
                BlockPos fixed = at.immutable();
                // 先实例化待加载的方块实体，消耗其延迟 NBT；否则批量换成空气后仍会尝试加载旧核心。
                if (state.hasBlockEntity()) chunk.getBlockEntity(fixed);
                if (state.is(ElvenBlight.NODE.get())) BlightReclamation.get(world).cleared(world, fixed);
                networks.purified(fixed);
                if (state.hasBlockEntity()) chunk.removeBlockEntity(fixed);
                section.setBlockState(x, y, z, target, false);
                if (state.getLightEmission() != target.getLightEmission()) light.checkBlock(fixed);
                changed++;
            }
            if (emptyBefore != section.hasOnlyAir()) {
                int sy = world.getSectionYFromSectionIndex(sectionIndex);
                light.updateSectionStatus(SectionPos.of(pos, sy), section.hasOnlyAir());
                world.getChunkSource().onSectionEmptinessChanged(pos.x(), sy, pos.z(), section.hasOnlyAir());
            }
        }
        if (changed == 0 && !biomesChanged[0]) return;
        restored += changed; chunk.markUnsaved();
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
        chunk.getSkyLightSources().fillFrom(chunk); light.propagateLightSources(pos); resend(world, pos);
        light.waitForPendingTasks(pos.x(), pos.z()).thenRunAsync(() -> { if (world.getServer().isSameThread()) resend(world, pos); }, world.getServer());
    }
    private static void resend(ServerLevel world, ChunkPos pos) {
        var chunk = world.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (chunk == null) return;
        var players = world.getChunkSource().chunkMap.getPlayers(pos, false);
        if (players.isEmpty()) return;
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, world.getChunkSource().getLightEngine(), null, null);
        for (ServerPlayer player : players) player.connection.send(packet);
    }
    @SubscribeEvent static void tick(LevelTickEvent.Post e) { if (e.getLevel() instanceof ServerLevel world) get(world).tick(world); }
    @SubscribeEvent static void load(ChunkEvent.Load e) { if (e.getLevel() instanceof ServerLevel world) { var pos = e.getChunk().getPos(); world.getServer().execute(() -> get(world).loaded(pos)); } }
}
