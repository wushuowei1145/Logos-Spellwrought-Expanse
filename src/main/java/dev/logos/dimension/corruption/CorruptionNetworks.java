package dev.logos.dimension.corruption;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.ServerConfig;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * 一个维度里的所有腐败网络。一个核心是一个网络；它的光标放下的脉络和腐败污秽记在它名下，网络大小就是名下的方块数。
 * 网络长到 {@code maxBlocks} 格就不再自己长，而是在自己的污秽上离所有核心都远的地方种一个子核心，把蔓延的电荷交给它；
 * 子核心也会再分，没有代数上限。不同网络挨上不合并，各管各的。核心被拆掉时，只有它名下的方块从核心开始一圈圈往外消散，
 * 它的子核心变成独立的核心：
 * 蔓延时由别的方块变来的污秽变回原来的方块，脉络和没有原方块的污秽直接消散。
 *
 * <p>方块进出网络靠方块自己的放置和移除回调（{@link #claim}、{@link #release}）。只设置不通知邻居的改动
 * （比如 {@code /setblock} 的 {@code strict}）收不到移除回调，所以每个网络隔一阵子查一遍名下的方块，把不是腐败的除名。
 */
public final class CorruptionNetworks extends SavedData {
    /** 消散的颜色：发紫的病红。 */
    public static final int FADE_COLOR = 0xB4286E;
    /** 消散波每刻往外推多少格；拆核心后等多少刻才开始推（镜头升到一半），让拆的人从高处看到一圈圈散开；每块方块消散多久。 */
    static final double WAVE_SPEED = .25;
    static final int WAVE_DELAY = 12, FADE_TICKS = 24;
    /** 储备最多存多少电荷（以后用来生产污秽衍生物）。 */
    static final int RESERVE_CAP = 10_000;
    private static final int AUDIT_TICKS = 100;

    private static final Codec<LongOpenHashSet> LONG_SET = Codec.LONG_STREAM.xmap(s -> new LongOpenHashSet(s.toArray()), set -> Arrays.stream(set.toLongArray()));

    /** 位置 → 原方块，存成一张调色板（泥土、石头、草方块……通常只有几种）加每个位置在调色板里的序号。 */
    private record Packed(List<BlockState> palette, long[] positions, int[] states) {
        static final Codec<Packed> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.listOf().fieldOf("palette").forGetter(Packed::palette),
                Codec.LONG_STREAM.xmap(LongStream::toArray, LongStream::of).fieldOf("positions").forGetter(Packed::positions),
                Codec.INT_STREAM.xmap(IntStream::toArray, IntStream::of).fieldOf("states").forGetter(Packed::states)
        ).apply(i, Packed::new));

        static Packed of(Long2ObjectOpenHashMap<BlockState> map) {
            List<BlockState> palette = new ArrayList<>();
            Object2IntOpenHashMap<BlockState> index = new Object2IntOpenHashMap<>();
            long[] positions = new long[map.size()];
            int[] states = new int[map.size()];
            int n = 0;
            for (var e : map.long2ObjectEntrySet()) {
                positions[n] = e.getLongKey();
                states[n++] = index.computeIfAbsent(e.getValue(), s -> {
                    palette.add(e.getValue());
                    return palette.size() - 1;
                });
            }
            return new Packed(palette, positions, states);
        }

        Long2ObjectOpenHashMap<BlockState> unpack() {
            var map = new Long2ObjectOpenHashMap<BlockState>(positions.length);
            for (int i = 0; i < positions.length && i < states.length; i++) {
                if (states[i] >= 0 && states[i] < palette.size()) map.put(positions[i], palette.get(states[i]));
            }
            return map;
        }
    }

    private static final Codec<Long2ObjectOpenHashMap<BlockState>> ORIGINALS = Packed.CODEC.xmap(Packed::unpack, Packed::of);

    /** 存档编码；世界测试用它检查存了再读回来原方块还在。 */
    public static final Codec<CorruptionNetworks> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("next_id").forGetter(d -> d.nextId),
            Network.CODEC.listOf().fieldOf("networks").forGetter(d -> List.copyOf(d.networks.values())),
            Fade.CODEC.listOf().fieldOf("fades").forGetter(d -> d.fades)
    ).apply(i, CorruptionNetworks::new));

    private static final SavedDataType<CorruptionNetworks> TYPE = new SavedDataType<>(LogosDimension.id("corruption"), CorruptionNetworks::new, CODEC);

    private int nextId = 1;
    private final Int2ObjectMap<Network> networks = new Int2ObjectLinkedOpenHashMap<>();
    /** 方块位置 → 所属网络。 */
    private final Long2IntOpenHashMap owner = new Long2IntOpenHashMap();
    /** 核心位置 → 网络。 */
    private final Long2IntOpenHashMap cores = new Long2IntOpenHashMap();
    private final List<Fade> fades = new ArrayList<>();
    /** 正在走光标的网络：这期间放下的腐败方块记在它名下。 */
    private int placing;
    /** 正在种子核心的网络：这期间放下的核心是它的子核心。 */
    private int planting;
    /** 消散最慢的一刻用了多少纳秒，只给测试和排查看，不存档。 */
    private long worstFadeNanos;

    private CorruptionNetworks() {
        owner.defaultReturnValue(0);
    }

    private CorruptionNetworks(int nextId, List<Network> networks, List<Fade> fades) {
        this();
        this.nextId = nextId;
        for (Network n : networks) {
            this.networks.put(n.id, n);
            cores.put(n.core.asLong(), n.id);
            n.members.forEach(pos -> owner.put(pos, n.id));
        }
        this.fades.addAll(fades);
    }

    public static CorruptionNetworks get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ---- 查询 -----------------------------------------------------------------------------------

    /** {@code pos} 这一格属于哪个网络，不属于任何网络时是 null。 */
    public @Nullable Network at(BlockPos pos) {
        int id = owner.get(pos.asLong());
        return id == 0 ? null : networks.get(id);
    }

    /** 以 {@code core} 为核心的网络。 */
    public @Nullable Network ofCore(BlockPos core) {
        int id = cores.get(core.asLong());
        return id == 0 ? null : networks.get(id);
    }

    public int count() {
        return networks.size();
    }

    /** {@code n} 现在有几个子核心（拆掉的不算）。 */
    public int satellites(Network n) {
        int count = 0;
        for (Network other : networks.values()) if (other.parent == n.id) count++;
        return count;
    }

    public boolean fading() {
        return !fades.isEmpty();
    }

    public long worstFadeNanos() {
        return worstFadeNanos;
    }

    // ---- 方块回调 -------------------------------------------------------------------------------

    /** 放下核心：开一个新网络。玩家放的往核心四周撒初始电荷；分出来的子核心不撒，电荷由母网络交过来。 */
    void open(ServerLevel level, BlockPos core) {
        if (ofCore(core) != null) return;
        Network n = new Network(nextId++, core.immutable(), new LongOpenHashSet(), List.of(), level.getGameTime() + idleTicks(), new Long2ObjectOpenHashMap<>(), planting, 0);
        networks.put(n.id, n);
        cores.put(n.core.asLong(), n.id);
        int seed = planting != 0 ? 0 : ServerConfig.CORRUPTION_SEED_CHARGE.getAsInt();
        if (seed > 0) {
            for (Direction side : Direction.Plane.HORIZONTAL) n.spreader.addCursors(core.relative(side), Math.max(1, seed / 4));
        }
        setDirty();
    }

    /**
     * 有腐败方块放下了：记到正在蔓延的那个网络名下；不是蔓延放的（玩家、命令）就跟着挨着的网络或核心；都没有就不属于任何网络。
     * 挨着别的网络也不合并。
     */
    void claim(ServerLevel level, BlockPos pos) {
        long key = pos.asLong();
        int id = placing != 0 && networks.containsKey(placing) ? placing : neighbourNetwork(pos);
        if (id == 0) return;
        int old = owner.put(key, id);
        if (old != id) {
            Network was = networks.get(old);
            if (was != null) was.members.remove(key);
            networks.get(id).members.add(key);
            setDirty();
        }
    }

    /** 这一格是哪个网络的方块或核心。 */
    private int idAt(long key) {
        int id = owner.get(key);
        return id != 0 ? id : cores.get(key);
    }

    /** 腐败方块没了（被挖、被消散、被别的方块顶替）。 */
    void release(BlockPos pos) {
        int id = owner.remove(pos.asLong());
        if (id == 0) return;
        Network n = networks.get(id);
        if (n != null) {
            n.members.remove(pos.asLong());
            n.originals.remove(pos.asLong());
        }
        setDirty();
    }

    /** 蔓延把 {@code original} 换成了 {@code pos} 上的污秽：记下来，核心没了以后变回去。不属于任何网络的位置不记。 */
    public void remember(BlockPos pos, BlockState original) {
        Network n = at(pos);
        if (n == null) return;
        n.originals.put(pos.asLong(), original);
        setDirty();
    }

    /** 核心没了：网络解散，名下的方块从核心开始往外消散；它的子核心变成独立的核心（各自带着自己的子孙）。 */
    void collapse(ServerLevel level, BlockPos core) {
        Network n = ofCore(core);
        if (n == null) return;
        networks.remove(n.id);
        cores.remove(core.asLong());
        n.members.forEach(owner::remove);
        for (Network child : networks.values()) if (child.parent == n.id) child.parent = 0;
        fades.add(new Fade(core, new LongArrayList(n.members.toLongArray()), n.originals, level.getGameTime() + WAVE_DELAY, FADE_TICKS));
        setDirty();
    }

    /**
     * 腐败杀了东西：在死的地方给这个网络加电荷。网络满了就先种子核心；种满了交给同一族里最近的没满的网络；
     * 全族都满了存进这个网络的储备。
     */
    public boolean feed(ServerLevel level, BlockPos killer, BlockPos at, int charge) {
        Network n = at(killer);
        if (n == null || charge <= 0) return false;
        n.fed += charge;
        if (!full(n)) n.spreader.addCursors(at, charge);
        else overflow(level, n, at, charge, true);
        setDirty();
        return true;
    }

    static boolean full(Network n) {
        return n.members.size() >= ServerConfig.CORRUPTION_MAX_BLOCKS.getAsInt();
    }

    /** 满了的网络多出来的电荷：种子核心，不行就（{@code share} 时）交给同族最近的没满的网络，再不行存进储备。 */
    private void overflow(ServerLevel level, Network n, BlockPos at, int charge, boolean share) {
        if (plant(level, n, charge)) return;
        if (share) {
            Network kin = nearestKin(level, n, at);
            if (kin != null) {
                spread(kin, charge);
                return;
            }
        }
        n.reserve = Math.min(RESERVE_CAP, n.reserve + charge);
    }

    /** 从核心四周出发蔓延 {@code charge} 点电荷。 */
    private static void spread(Network n, int charge) {
        int each = Math.max(1, charge / 4);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (charge <= 0) break;
            int part = Math.min(each, charge);
            n.spreader.addCursors(n.core.relative(side), part);
            charge -= part;
        }
        if (charge > 0) n.spreader.addCursors(n.core.north(), charge);
    }

    /** {@code n} 这一族的祖先（顺着母核心一直往上找）。 */
    private int root(Network n) {
        Network at = n;
        for (int guard = 0; at.parent != 0 && guard < 10_000; guard++) {
            Network up = networks.get(at.parent);
            if (up == null) break;
            at = up;
        }
        return at.id;
    }

    /** 同一族里核心离 {@code at} 最近、没满、所在区块在走刻的网络。 */
    private @Nullable Network nearestKin(ServerLevel level, Network n, BlockPos at) {
        int family = root(n);
        Network best = null;
        double bestDist = Double.MAX_VALUE;
        for (Network other : networks.values()) {
            if (other == n || full(other) || !level.shouldTickBlocksAt(other.core) || root(other) != family) continue;
            double d = other.core.distSqr(at);
            if (d < bestDist) {
                bestDist = d;
                best = other;
            }
        }
        return best;
    }

    /**
     * 满了的网络种一个子核心：在自己名下、上面空着的污秽里挑离所有核心最远的一格（至少 {@code satelliteSpacing} 格），
     * 核心放在它上面，{@code charge} 点电荷从子核心四周出发。子核心已经种满或者没有合适的地方时返回 false。
     */
    private boolean plant(ServerLevel level, Network n, int charge) {
        if (satellites(n) >= ServerConfig.CORRUPTION_MAX_SATELLITES.getAsInt()) return false;
        double spacing = ServerConfig.CORRUPTION_SATELLITE_SPACING.getAsInt();
        BlockPos best = null;
        double bestDist = spacing * spacing - 1e-6;
        for (long key : n.members) {
            BlockPos ground = BlockPos.of(key), spot = ground.above();
            if (!level.isLoaded(spot) || !level.getBlockState(ground).is(Corruption.BLOCK.get())) continue;
            BlockState above = level.getBlockState(spot);
            if (!above.isAir() && !above.is(Corruption.VEIN.get())) continue;
            double nearest = Double.MAX_VALUE;
            for (Network other : networks.values()) nearest = Math.min(nearest, other.core.distSqr(spot));
            if (nearest > bestDist) {
                bestDist = nearest;
                best = spot;
            }
        }
        if (best == null) return false;
        planting = n.id;
        try {
            level.setBlock(best, Corruption.CORE.get().defaultBlockState(), Block.UPDATE_ALL);
        } finally {
            planting = 0;
        }
        Network child = ofCore(best);
        if (child == null) return false;
        spread(child, charge);
        setDirty();
        return true;
    }

    private int neighbourNetwork(BlockPos pos) {
        for (Direction d : Direction.values()) {
            int id = idAt(pos.relative(d).asLong());
            if (id != 0) return id;
        }
        return 0;
    }

    // ---- 每刻 -----------------------------------------------------------------------------------

    void tick(ServerLevel level) {
        long now = level.getGameTime();
        RandomSource random = level.getRandom();
        for (Network n : List.copyOf(networks.values())) {
            if (!networks.containsKey(n.id)) continue;
            idle(level, n, now, random);
            if (!n.spreader.cursors().isEmpty() && full(n)) {
                // 满了：手上的电荷不再自己用，交出去（种子核心、给同族、存储备）
                int charge = n.spreader.charge();
                n.spreader.cursors().clear();
                overflow(level, n, n.core, charge, true);
                setDirty();
            } else if (!n.spreader.cursors().isEmpty()) {
                placing = n.id;
                try {
                    n.spreader.update(level, n.core, random);
                } finally {
                    placing = 0;
                }
                setDirty();
            }
            if ((now + n.id) % AUDIT_TICKS == 0) audit(level, n);
        }
        if (!fades.isEmpty()) {
            long t0 = System.nanoTime();
            fades.removeIf(f -> f.step(level, now));
            worstFadeNanos = Math.max(worstFadeNanos, System.nanoTime() - t0);
            setDirty();
        }
    }

    private static int idleTicks() {
        return ServerConfig.CORRUPTION_IDLE_SECONDS.getAsInt() * 20;
    }

    /**
     * 核心自己隔一阵子添一点电荷，从网络里随便一格出发，网络就算没有杀到东西也会慢慢长。满了的网络拿它种子核心，
     * 种满了就存进储备，不分给同族：不然核心越多大家长得越快，被动的扩张会越滚越大。
     */
    private void idle(ServerLevel level, Network n, long now, RandomSource random) {
        if (now < n.idleAt) return;
        n.idleAt = now + idleTicks();
        int charge = ServerConfig.CORRUPTION_IDLE_CHARGE.getAsInt();
        if (charge <= 0 || !level.shouldTickBlocksAt(n.core)) return;
        if (full(n)) {
            overflow(level, n, n.core, charge, false);
            setDirty();
            return;
        }
        BlockPos from = n.core.relative(Direction.Plane.HORIZONTAL.getRandomDirection(random));
        if (!n.members.isEmpty()) {
            long[] all = n.members.toLongArray();
            from = BlockPos.of(all[random.nextInt(all.length)]);
        }
        n.spreader.addCursors(from, charge);
        setDirty();
    }

    /** 把名下已经不是腐败方块的位置除名（只看已加载的）。 */
    private void audit(ServerLevel level, Network n) {
        for (LongIterator it = n.members.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            BlockPos pos = BlockPos.of(key);
            if (!level.isLoaded(pos) || Corruption.isNetworkBlock(level.getBlockState(pos))) continue;
            it.remove();
            owner.remove(key);
            n.originals.remove(key);
            setDirty();
        }
    }

    // ---- 数据 -----------------------------------------------------------------------------------

    /** 一个网络：核心位置、名下的方块、蔓延光标、母核心和储备。 */
    public static final class Network {
        static final Codec<Network> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(n -> n.id),
                BlockPos.CODEC.fieldOf("core").forGetter(n -> n.core),
                LONG_SET.fieldOf("members").forGetter(n -> n.members),
                CorruptionSpreader.CURSORS.fieldOf("cursors").forGetter(n -> n.spreader.cursors()),
                Codec.LONG.fieldOf("idle_at").forGetter(n -> n.idleAt),
                ORIGINALS.optionalFieldOf("originals", new Long2ObjectOpenHashMap<>()).forGetter(n -> n.originals),
                Codec.INT.optionalFieldOf("parent", 0).forGetter(n -> n.parent),
                Codec.INT.optionalFieldOf("reserve", 0).forGetter(n -> n.reserve)
        ).apply(i, Network::new));

        final int id;
        final BlockPos core;
        final LongOpenHashSet members;
        /** 名下由别的方块变来的污秽原来是什么。玩家放的污秽和脉络不在这里。 */
        final Long2ObjectOpenHashMap<BlockState> originals;
        final CorruptionSpreader spreader = new CorruptionSpreader();
        long idleAt;
        /** 母核心的网络；玩家放的核心、母核心被拆掉的子核心是 0。 */
        int parent;
        /** 满了以后存下的电荷，以后用来生产污秽衍生物。 */
        int reserve;
        /** 这次开服以来靠杀生得到的电荷，只给测试和排查看，不存档。 */
        int fed;

        Network(int id, BlockPos core, LongOpenHashSet members, List<CorruptionSpreader.Cursor> cursors, long idleAt, Long2ObjectOpenHashMap<BlockState> originals,
                int parent, int reserve) {
            this.parent = parent;
            this.reserve = reserve;
            this.id = id;
            this.core = core;
            this.members = members;
            this.originals = originals;
            this.idleAt = idleAt;
            spreader.load(cursors);
        }

        public int id() { return id; }
        public BlockPos core() { return core; }
        /** 网络大小：名下的脉络和腐败污秽有多少格（不算核心）。 */
        public int size() { return members.size(); }
        public int charge() { return spreader.charge(); }
        public int fed() { return fed; }
        public int parent() { return parent; }
        public int reserve() { return reserve; }
        /** {@code pos} 原来是什么方块；不是蔓延变来的就是 null。 */
        public @Nullable BlockState original(BlockPos pos) { return originals.get(pos.asLong()); }
    }

    /**
     * 一次消散：方块按离 {@code origin} 的远近排好，消散波每刻往外推 {@link #WAVE_SPEED} 格，扫到的方块交给 {@link Dissolving}：
     * 记着原方块的污秽用 {@link Dissolving#replace} 变回去，别的（脉络、玩家放的污秽、核心）用 {@link Dissolving#blocks} 消失，泡在水里的脉络留下水。
     * 同一刻扫到的按区块分组，每组是一次消散，只发给看得到那个区块的玩家。没加载的方块先留着，加载了再消散。
     */
    static final class Fade {
        static final Codec<Fade> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("origin").forGetter(f -> f.origin),
                Codec.LONG_STREAM.xmap(s -> new LongArrayList(s.toArray()), l -> LongStream.of(l.toLongArray())).fieldOf("left").forGetter(Fade::left),
                ORIGINALS.optionalFieldOf("restore", new Long2ObjectOpenHashMap<>()).forGetter(f -> f.restore),
                Codec.LONG.fieldOf("start").forGetter(f -> f.start),
                Codec.INT.optionalFieldOf("ticks", FADE_TICKS).forGetter(f -> f.ticks)
        ).apply(i, Fade::new));

        final BlockPos origin;
        /** 还没扫到的，按距离从近到远。 */
        final LongArrayList pending;
        /** 扫到了但区块没加载的。 */
        final LongArrayList waiting = new LongArrayList();
        /** 要变回去的污秽原来是什么。 */
        final Long2ObjectOpenHashMap<BlockState> restore;
        final int ticks;
        long start;
        int next;

        Fade(BlockPos origin, LongArrayList positions, Long2ObjectOpenHashMap<BlockState> restore, long start, int ticks) {
            this.origin = origin;
            this.restore = new Long2ObjectOpenHashMap<>(restore);
            this.start = start;
            this.ticks = ticks;
            long[] sorted = positions.toLongArray();
            // 按距离排：先装进 (距离, 位置) 再排，避免比较器里反复算
            Long[] boxed = Arrays.stream(sorted).boxed().toArray(Long[]::new);
            Arrays.sort(boxed, (p, q) -> Double.compare(BlockPos.of(p).distSqr(origin), BlockPos.of(q).distSqr(origin)));
            this.pending = new LongArrayList(boxed.length);
            for (Long p : boxed) pending.add(p.longValue());
        }

        private LongArrayList left() {
            LongArrayList all = new LongArrayList(waiting);
            for (int i = next; i < pending.size(); i++) all.add(pending.getLong(i));
            return all;
        }

        /** 走一刻，全部消散完时返回 true。 */
        boolean step(ServerLevel level, long now) {
            if (now < start) return false;
            double radius = (now - start) * WAVE_SPEED;
            double reach = radius * radius;
            Long2ObjectOpenHashMap<Batch> byChunk = new Long2ObjectOpenHashMap<>();
            while (next < pending.size()) {
                BlockPos pos = BlockPos.of(pending.getLong(next));
                if (pos.distSqr(origin) > reach) break;
                next++;
                take(level, pos, byChunk);
            }
            if (!waiting.isEmpty() && now % 20 == 0) {
                LongArrayList again = new LongArrayList(waiting);
                waiting.clear();
                for (long p : again) take(level, BlockPos.of(p), byChunk);
            }
            for (Batch batch : byChunk.values()) {
                if (!batch.gone.isEmpty()) Dissolving.blocks(level, batch.gone, DissolveShape.noise(), ticks, FADE_COLOR);
                if (!batch.back.isEmpty()) Dissolving.replace(level, batch.back, batch.into, DissolveShape.noise(), ticks, FADE_COLOR);
            }
            return next >= pending.size() && waiting.isEmpty();
        }

        /** 同一区块这一刻扫到的方块：消失的一组，变成别的方块的一组。 */
        private record Batch(List<BlockPos> gone, List<BlockPos> back, List<BlockState> into) {
            Batch() { this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()); }
        }

        private void take(ServerLevel level, BlockPos pos, Long2ObjectOpenHashMap<Batch> byChunk) {
            if (!level.isLoaded(pos)) {
                waiting.add(pos.asLong());
                return;
            }
            BlockState state = level.getBlockState(pos);
            if (!Corruption.isNetworkBlock(state) && !state.is(Corruption.CORE.get())) return;
            Batch batch = byChunk.computeIfAbsent(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), k -> new Batch());
            BlockState original = state.is(Corruption.BLOCK.get()) ? restore.remove(pos.asLong()) : null;
            if (original == null && state.getFluidState().is(Fluids.WATER)) original = Blocks.WATER.defaultBlockState();
            if (original == null) {
                batch.gone.add(pos);
            } else {
                batch.back.add(pos);
                batch.into.add(original);
            }
        }
    }
}
