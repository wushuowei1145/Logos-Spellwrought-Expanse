package dev.logos.dimension.elven.blight;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.*;
import dev.logos.dimension.dissolve.*;
import it.unimi.dsi.fastutil.longs.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** 只登记原版生成结果并推动净化，不生成地形、不种树、不强加载区块。 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class BlightReclamation extends SavedData {
    public record Cell(BlockPos core, boolean active) {
        static final Codec<Cell> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("core").forGetter(Cell::core), Codec.BOOL.fieldOf("active").forGetter(Cell::active)).apply(i, Cell::new));
    }
    public record Source(BlockPos pos, boolean defeated) {
        static final Codec<Source> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Source::pos), Codec.BOOL.fieldOf("defeated").forGetter(Source::defeated)).apply(i, Source::new));
    }
    public static final Codec<BlightReclamation> CODEC = RecordCodecBuilder.create(i -> i.group(
            Cell.CODEC.listOf().fieldOf("cells").forGetter(d -> List.copyOf(d.cells.values())),
            Source.CODEC.listOf().fieldOf("sources").forGetter(d -> List.copyOf(d.sources.values()))).apply(i, BlightReclamation::new));
    private static final SavedDataType<BlightReclamation> TYPE = new SavedDataType<>(LogosDimension.id("blight_reclamation"), BlightReclamation::new, CODEC);
    /** 每个服务端刻每维度合计最多检查两个含污染的区段；不按核心数放大开销。 */
    private static final int SCAN_BUDGET = 8192;
    public static final int SOURCE_RANGE = 1024;
    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Source> sources = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<Scan> scans = new ArrayDeque<>();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private static final class Scan {
        final ChunkPos chunk;
        int section, index;
        Scan(ChunkPos chunk) { this.chunk = chunk; }
    }
    private BlightReclamation() {}
    private BlightReclamation(List<Cell> cells, List<Source> sources) {
        for (Cell cell : cells) this.cells.put(ChunkPos.containing(cell.core()).pack(), cell);
        for (Source source : sources) this.sources.put(source.pos().asLong(), source);
    }
    public static BlightReclamation get(ServerLevel level) { return level.getDataStorage().computeIfAbsent(TYPE); }
    public Cell cell(ChunkPos pos) { return cells.get(pos.pack()); }
    public boolean pending() { return !scans.isEmpty(); }
    public void register(ServerLevel level, BlockPos core) {
        long key = ChunkPos.containing(core).pack();
        Cell old = cells.get(key);
        if (old != null && !old.core().equals(core)) return;
        if (old == null) { cells.put(key, new Cell(core.immutable(), true)); setDirty(); }
        if (cells.get(key).active()) CorruptionNetworks.get(level).prepareGenerated(level, core);
        supply(level, cells.get(key));
        enqueue(ChunkPos.containing(core));
    }
    /** 核心先消失也不会漏掉还在排队登记的污染；清理进度永久存档。 */
    public void cleared(ServerLevel level, BlockPos core) {
        long key = ChunkPos.containing(core).pack();
        Cell cell = cells.get(key);
        if (cell != null && !cell.core().equals(core)) return;
        cells.put(key, new Cell(core.immutable(), false));
        setDirty();
        enqueue(ChunkPos.containing(core));
    }
    public void changed(BlockPos pos) {
        if (cells.containsKey(ChunkPos.containing(pos).pack())) enqueue(ChunkPos.containing(pos));
    }
    private void enqueue(ChunkPos pos) {
        if (queued.add(pos.pack())) scans.addLast(new Scan(pos));
    }
    public void source(ServerLevel level, BlockPos pos, boolean defeated) {
        Source old = sources.get(pos.asLong());
        if (old != null && old.defeated() == defeated) return;
        sources.put(pos.asLong(), new Source(pos.immutable(), defeated));
        setDirty();
        for (Cell cell : cells.values()) supply(level, cell);
    }
    public boolean supplied(BlockPos core) {
        Source nearest = null;
        double distance = (double) SOURCE_RANGE * SOURCE_RANGE;
        for (Source source : sources.values()) {
            double dx = core.getX() - source.pos().getX(), dz = core.getZ() - source.pos().getZ();
            double d = dx * dx + dz * dz;
            if (d < distance) { distance = d; nearest = source; }
        }
        return nearest == null || !nearest.defeated();
    }
    private void supply(ServerLevel level, Cell cell) {
        if (cell.active()) CorruptionNetworks.get(level).passiveSupply(cell.core(), supplied(cell.core()));
    }
    private static boolean generated(BlockState state) {
        return state.getBlock() instanceof BlightMemoryBlock || state.getBlock() instanceof CorruptionGrowthBlock || state.is(Corruption.VEIN.get());
    }
    private void tick(ServerLevel level) {
        int budget = SCAN_BUDGET;
        while (budget > 0 && !scans.isEmpty()) {
            Scan scan = scans.peekFirst();
            var chunk = level.getChunkSource().getChunkNow(scan.chunk.x(), scan.chunk.z());
            Cell cell = cells.get(scan.chunk.pack());
            if (chunk == null || cell == null) { finish(scan); continue; }
            var sections = chunk.getSections();
            var positions = new LongArrayList();
            var originals = new Long2ObjectOpenHashMap<BlockState>();
            while (budget > 0 && scan.section < sections.length) {
                var section = sections[scan.section];
                if (scan.index == 0 && (section.hasOnlyAir() || !section.getStates().maybeHas(BlightReclamation::generated))) { scan.section++; continue; }
                int i = scan.index++, x = i & 15, z = i >> 4 & 15, y = i >> 8;
                BlockState state = section.getBlockState(x, y, z);
                budget--;
                if (generated(state)) {
                    BlockPos pos = new BlockPos(scan.chunk.getMinBlockX() + x, (level.getSectionYFromSectionIndex(scan.section) << 4) + y, scan.chunk.getMinBlockZ() + z);
                    BlockState original = state.getBlock() instanceof BlightMemoryBlock ? BlightMemoryBlock.original(state) : null;
                    if (cell.active()) CorruptionNetworks.get(level).adoptGenerated(level, cell.core(), pos, original);
                    else {
                        positions.add(pos.asLong());
                        if (original != null) originals.put(pos.asLong(), original);
                    }
                }
                if (scan.index == 4096) { scan.index = 0; scan.section++; }
            }
            if (!positions.isEmpty()) CorruptionNetworks.get(level).fadeGenerated(level, cell.core(), positions, originals);
            if (scan.section >= sections.length) finish(scan);
        }
    }
    private void finish(Scan scan) { scans.removeFirst(); queued.remove(scan.chunk.pack()); }
    @SubscribeEvent static void loaded(ChunkEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel level) {
            ChunkPos at = e.getChunk().getPos();
            level.getServer().execute(() -> { var data = get(level); if (data.cells.containsKey(at.pack())) data.enqueue(at); });
        }
    }
    @SubscribeEvent static void tick(LevelTickEvent.Post e) { if (e.getLevel() instanceof ServerLevel level) get(level).tick(level); }
    /** 边缘没有核心的小片残污仍能逐块清理；锄剥去污染而保留地面或树木。 */
    @SubscribeEvent static void hoe(PlayerInteractEvent.RightClickBlock e) {
        if (!e.getItemStack().is(ItemTags.HOES)) return;
        BlockState state = e.getLevel().getBlockState(e.getPos());
        if (!(state.getBlock() instanceof BlightMemoryBlock)) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (e.getLevel() instanceof ServerLevel level) {
            Dissolving.replace(level, List.of(e.getPos()), List.of(BlightMemoryBlock.original(state)), DissolveShape.noise(), 24, 0xA91F32);
            e.getItemStack().hurtAndBreak(1, e.getEntity(), e.getHand() == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        }
    }
}
