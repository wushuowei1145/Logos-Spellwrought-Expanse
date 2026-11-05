package dev.logos.dimension.fox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.npc.DivineFox;
import dev.logos.dimension.npc.NpcEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 神狐聚落的居民：聚落生成时在街道与广场上放下至多 {@link #MAX} 只神狐（{@link SanctuaryResidentsPiece}），
 * 之后有玩家在聚落里、居民不足 {@link #MAX} 只时，每 {@link #REFILL_TICKS} 刻在离玩家较远的街上补回一只。
 * 每处聚落的居民数 = 生成时的只数 − 死去或被清除的 + 补回的，记在维度存档里（以结构起点区分）。
 */
public final class SanctuaryResidents {
    public static final int MAX = 20;
    /** 补回一只的间隔（2 分钟）。 */
    public static final int REFILL_TICKS = 2400;
    /** 补回的神狐离玩家至少这么远（不在眼前凭空出现）。 */
    public static final double REFILL_AWAY = 24;
    /** 街上落脚点之间的最小间距。 */
    private static final int SPACING = 9;
    private static final ResourceKey<Structure> STRUCTURE = ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("fox_sanctuary"));

    private SanctuaryResidents() {}

    // ---------------------------------------------------------------- 落脚点

    /**
     * 居民的落脚点：核心区里铺着小路、碎石或石板的格子（街道与广场，不在屋里），按聚落种子打乱后取彼此相隔
     * {@link #SPACING} 格以上的至多 {@link #MAX} 个；返回世界坐标，高度是铺装面上一格。
     */
    static List<BlockPos> spots(SanctuaryFrame frame) {
        SanctuaryLayout layout = frame.layout;
        List<int[]> cells = new ArrayList<>();
        for (int u = 2; u < layout.size - 2; u += 2) {
            for (int v = 2; v < layout.size - 2; v += 2) {
                int g = layout.ground(u, v);
                if (g == SanctuaryLayout.PATH || g == SanctuaryLayout.GRAVEL || g == SanctuaryLayout.STONE) cells.add(new int[] {u, v});
            }
        }
        Collections.shuffle(cells, new Random(frame.seed ^ 0x5EEDF0C5L));
        List<int[]> picked = new ArrayList<>();
        for (int[] c : cells) {
            if (picked.size() >= MAX) break;
            boolean far = true;
            for (int[] p : picked) if (Math.abs(p[0] - c[0]) < SPACING && Math.abs(p[1] - c[1]) < SPACING) far = false;
            if (far) picked.add(c);
        }
        List<BlockPos> out = new ArrayList<>();
        for (int[] c : picked) out.add(frame.world(c[0], frame.p + layout.height(c[0], c[1]) + 1, c[1]));
        return out;
    }

    /** 在 at 放一只神狐（结构居民）；脚下或头顶被挡住就往上找三格，找不到放弃。 */
    static boolean spawn(ServerLevelAccessor level, BlockPos at, long sanctuary) {
        BlockPos pos = at;
        for (int i = 0; i < 4 && !(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()); i++) pos = pos.above();
        if (!(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir())) return false;
        DivineFox fox = NpcEntities.DIVINE_FOX.get().create(level.getLevel(), EntitySpawnReason.STRUCTURE);
        if (fox == null) return false;
        fox.snapTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, level.getRandom().nextFloat() * 360, 0);
        fox.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.STRUCTURE, null);
        fox.setSanctuary(sanctuary);
        level.addFreshEntityWithPassengers(fox);
        return true;
    }

    // ---------------------------------------------------------------- 居民数

    /** 每处聚落死去（或被清除）与补回的只数。 */
    public static final class Census extends SavedData {
        record Entry(int lost, int refilled) {
            static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.fieldOf("lost").forGetter(Entry::lost),
                    Codec.INT.fieldOf("refilled").forGetter(Entry::refilled)).apply(i, Entry::new));
        }

        public static final Codec<Census> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.unboundedMap(Codec.STRING, Entry.CODEC).fieldOf("sanctuaries").forGetter((Census c) -> c.entries)
        ).apply(i, Census::new));
        static final SavedDataType<Census> TYPE = new SavedDataType<>(LogosDimension.id("fox_sanctuary_residents"), Census::new, CODEC);
        private final Map<String, Entry> entries;

        public Census() {
            this(Map.of());
        }

        private Census(Map<String, Entry> entries) {
            this.entries = new HashMap<>(entries);
        }

        Entry get(long key) {
            return entries.getOrDefault(Long.toString(key), new Entry(0, 0));
        }

        void put(long key, Entry entry) {
            entries.put(Long.toString(key), entry);
            setDirty();
        }

        /** 生成时放下 planned 只的聚落现在有多少居民。 */
        int alive(long key, int planned) {
            Entry e = get(key);
            return planned - e.lost + e.refilled;
        }
    }

    private static Census census(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Census.TYPE);
    }

    /** 一只居民死去或被清除。 */
    public static void lost(ServerLevel level, long sanctuary) {
        Census c = census(level);
        Census.Entry e = c.get(sanctuary);
        c.put(sanctuary, new Census.Entry(e.lost + 1, e.refilled));
    }

    /** 这处聚落现在有多少居民（生成时放下 planned 只）。 */
    public static int alive(ServerLevel level, long sanctuary, int planned) {
        return census(level).alive(sanctuary, planned);
    }

    // ---------------------------------------------------------------- 补回

    /** 每 {@link #REFILL_TICKS} 刻看一次：有玩家所在的聚落居民不足时补回一只。 */
    public static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % REFILL_TICKS != 0 || level.players().isEmpty()) return;
        var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(STRUCTURE);
        if (structure == null) return;
        List<Long> done = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            StructureStart start = level.structureManager().getStructureAt(player.blockPosition(), structure);
            if (!start.isValid()) continue;
            SanctuaryResidentsPiece piece = start.getPieces().stream().filter(p -> p instanceof SanctuaryResidentsPiece)
                    .map(p -> (SanctuaryResidentsPiece) p).findFirst().orElse(null);
            if (piece == null || done.contains(piece.sanctuary())) continue;
            done.add(piece.sanctuary());
            refill(level, piece);
        }
    }

    /** 居民不足时，在随机一个离所有玩家都够远、已加载的落脚点补一只（客户端检查也直接调用）。 */
    public static boolean refill(ServerLevel level, SanctuaryResidentsPiece piece) {
        Census c = census(level);
        if (c.alive(piece.sanctuary(), piece.planned()) >= MAX) return false;
        List<BlockPos> spots = new ArrayList<>(piece.spots());
        Collections.shuffle(spots, new Random(level.getGameTime()));
        for (BlockPos spot : spots) {
            if (!level.isLoaded(spot)) continue;
            if (level.getNearestPlayer(spot.getX() + .5, spot.getY(), spot.getZ() + .5, REFILL_AWAY, false) != null) continue;
            if (!spawn(level, spot, piece.sanctuary())) continue;
            Census.Entry e = c.get(piece.sanctuary());
            c.put(piece.sanctuary(), new Census.Entry(e.lost, e.refilled + 1));
            return true;
        }
        return false;
    }
}
