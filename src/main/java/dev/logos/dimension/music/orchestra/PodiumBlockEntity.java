package dev.logos.dimension.music.orchestra;

import com.mojang.serialization.Codec;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicRoll;
import dev.logos.dimension.music.RollInfo;
import dev.logos.dimension.music.RollStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * 指挥台的方块实体（docs/ORCHESTRA.md 第 4–6 节）：总谱、成员乐器、声部分派、速度与各声部的响度 / 静音 / 独奏，
 * 以及乐团的播放时钟。成员、分派、设置存盘并同步给客户端（界面读它）；播放状态不存盘，由 {@link EnsemblePayloads.EnsembleState} 广播。
 */
public class PodiumBlockEntity extends BlockEntity {
    public static final int MAX_MEMBERS = 32;
    public static final double MAX_DISTANCE = 32;
    public static final double GATHER_RANGE = 16;
    /** 召集附近：台前方左右各 80° 的扇形。 */
    static final double GATHER_HALF_ANGLE = Math.toRadians(80);
    public static final int MIN_TEMPO = 50, MAX_TEMPO = 150;
    public static final int MIN_GAIN = -12, MAX_GAIN = 6;
    private static final int HEARTBEAT_TICKS = 100;
    /** 最后一个音之后再放这么久才停：尾音响完，指挥的收束和放下手臂做完（客户端 1.2 + 1.5 秒）。 */
    private static final int TAIL_MS = 3000;
    /** 状态包发给这么远之内的玩家（成员可以离台 32 格，听得到它们的人再远一些）。 */
    private static final double BROADCAST_RANGE = 96;

    private ItemStack score = ItemStack.EMPTY;
    private final List<BlockPos> members = new ArrayList<>();
    private final Map<BlockPos, Integer> masks = new HashMap<>();
    private final Set<BlockPos> locked = new HashSet<>();
    private int tempo = 100;
    private final byte[] gains = new byte[MusicRoll.MAX_PARTS];
    private int muteMask;
    private int soloMask;
    /** 全体的响度（dB）：指挥台上滚轮，没选中声部时调它。 */
    private int master;
    /** 节拍器：指挥打拍子时听得到按设定速度走的咔哒声（docs/ORCHESTRA.md 第 18 节）。 */
    private boolean metronome;
    private boolean powered;

    // 播放（服务端，不存盘）
    private @Nullable UUID playing;
    private long playingHash;
    private int durationMs;
    private long startNanos;
    private int startOffsetMs;
    private long startTick;
    /** 时钟此刻的速度（相对纸带原速）：设定的速度，或跟着指挥的拍子；延长时为 0。 */
    private double rate = 1;
    private @Nullable BeatMap beats;
    private int pendingTempo = -1;
    private int pendingBar;
    /** 正在打拍子的人（不存盘）；为空时乐团按设定的速度自己走。 */
    private net.minecraft.world.entity.player.@Nullable Player leader;
    /** 指挥手势给的力度（倍数）。 */
    private float expression = 1;
    private long lastFollowNanos;

    public PodiumBlockEntity(BlockPos pos, BlockState state) {
        super(Orchestra.PODIUM_ENTITY.get(), pos, state);
    }

    // —— 读 ——

    public ItemStack score() { return score; }
    public List<BlockPos> members() { return List.copyOf(members); }
    public int mask(BlockPos member) { return masks.getOrDefault(member, 0); }
    public boolean locked(BlockPos member) { return locked.contains(member); }
    public int tempo() { return tempo; }
    public int gain(int part) { return gains[part]; }
    public int muteMask() { return muteMask; }
    public int soloMask() { return soloMask; }
    public int master() { return master; }
    public boolean metronome() { return metronome; }
    public boolean isPlaying() { return playing != null; }
    /** 时钟此刻的速度（相对原速），延长时为 0。 */
    public double rate() { return rate; }
    public net.minecraft.world.entity.player.@Nullable Player leader() { return leader; }
    public boolean held() { return playing != null && rate == 0; }

    public Direction facing() {
        return getBlockState().getValue(PodiumBlock.FACING);
    }

    /** 指挥站的位置（台面中央）。 */
    public Vec3 standAt() {
        return PodiumBlock.standAt(getBlockState(), worldPosition);
    }

    /** 成员从指挥台看过去的座次：越小越靠指挥的左手边。 */
    public double seat(BlockPos member) {
        Direction r = PodiumBlock.right(facing());
        Vec3 d = Vec3.atCenterOf(member).subtract(standAt());
        return d.x * r.getStepX() + d.z * r.getStepZ();
    }

    /** 此刻的播放位置（毫秒，起奏前的预备拍里为负）。 */
    public int positionMs() {
        if (playing == null) return 0;
        return startOffsetMs + (int) Math.min(Integer.MAX_VALUE / 2, (System.nanoTime() - startNanos) / 1_000_000.0 * rate);
    }

    private @Nullable InstrumentBlockEntity instrument(BlockPos pos) {
        return level == null || !level.isLoaded(pos) ? null : InstrumentBlock.entityAt(level, pos);
    }

    private @Nullable MusicRoll scoreRoll() {
        RollInfo info = score.get(Instruments.MUSIC_ROLL.get());
        byte[] data = info == null ? null : RollStore.load(info.id());
        if (data == null) return null;
        try {
            return MusicRoll.decode(data);
        } catch (MusicRoll.FormatException e) {
            return null;
        }
    }

    // —— 成员 ——

    /**
     * 指挥棒点选：不在乐团里就加入，在就退出。返回给玩家的提示（翻译键的后半）。
     * 一件乐器只属于一个乐团：加入这里时从原来的乐团退出。
     */
    public String toggle(BlockPos main) {
        if (level != null) {
            BlockPos instrument = dev.logos.dimension.music.StarbloomBenchBlock.linked(level, main);
            if (instrument != null) main = instrument;
        }
        if (members.contains(main)) {
            forget(main);
            InstrumentBlockEntity be = instrument(main);
            if (be != null && worldPosition.equals(be.orchestra())) be.setOrchestra(null);
            return "left";
        }
        InstrumentBlockEntity be = instrument(main);
        if (be == null) return "not_instrument";
        if (members.size() >= MAX_MEMBERS) return "full";
        if (Vec3.atCenterOf(main).distanceTo(standAt()) > MAX_DISTANCE) return "too_far";
        join(main, be);
        return "joined";
    }

    private void join(BlockPos main, InstrumentBlockEntity be) {
        BlockPos old = be.orchestra();
        if (old != null && !old.equals(worldPosition) && level != null && level.isLoaded(old)
                && level.getBlockEntity(old) instanceof PodiumBlockEntity other) {
            other.forget(main);
        }
        be.setOrchestra(worldPosition);
        members.add(main.immutable());
        reassign();
    }

    /** 从乐团里去掉（不碰乐器自己的记录）。 */
    void forget(BlockPos main) {
        if (!members.remove(main)) return;
        masks.remove(main);
        locked.remove(main);
        reassign();
    }

    /**
     * 成员乐器变了：被拆掉（gone）时从乐团里去掉；乐谱架上的乐器被拿走、换了一件时，去掉演奏不了的成员、按新乐器重新分派。
     * 在演奏时马上告诉客户端（退出的乐器止音）。
     */
    public static void memberChanged(Level level, @Nullable BlockPos podium, BlockPos member, boolean gone) {
        if (podium == null || level.isClientSide() || !level.isLoaded(podium) || !(level.getBlockEntity(podium) instanceof PodiumBlockEntity p)) return;
        if (!p.members.contains(member)) return;
        if (gone) {
            p.forget(member);
        } else {
            p.validate();
            p.reassign();
        }
        if (p.isPlaying()) p.broadcast();
    }

    /**
     * 这件乐器真的属于别的乐团：它记着的指挥台还在、并且把它算作成员（那里没加载时当作还在）。
     * 结构被旋转着放下、只复制了乐器时，记着的位置会指向别处，这样的当作没有乐团、可以召集。
     */
    private boolean claimedElsewhere(BlockPos main, InstrumentBlockEntity be) {
        BlockPos old = be.orchestra();
        if (old == null || old.equals(worldPosition) || level == null) return false;
        if (!level.isLoaded(old)) return true;
        return level.getBlockEntity(old) instanceof PodiumBlockEntity other && other.members.contains(main);
    }

    /** 召集附近：台前方 16 格扇形里的乐器都加进来（满 32 件为止）。返回新加入的件数。 */
    public int gather() {
        if (level == null) return 0;
        Vec3 at = standAt();
        Direction f = facing();
        int added = 0;
        int r = (int) GATHER_RANGE;
        BlockPos base = BlockPos.containing(at);
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-r, -4, -r), base.offset(r, 6, r))) {
            if (members.size() >= MAX_MEMBERS) break;
            BlockState state = level.getBlockState(p);
            if (!(state.getBlock() instanceof InstrumentBlock b) || !b.isMain(state)) continue;
            if (dev.logos.dimension.music.StarbloomBenchBlock.linked(level, p, state) != null) continue;
            BlockPos main = p.immutable();
            if (members.contains(main)) continue;
            Vec3 d = Vec3.atCenterOf(main).subtract(at);
            double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
            if (horizontal > GATHER_RANGE || horizontal < 0.5) continue;
            double cos = (d.x * f.getStepX() + d.z * f.getStepZ()) / horizontal;
            if (cos < Math.cos(GATHER_HALF_ANGLE)) continue;
            InstrumentBlockEntity be = instrument(main);
            if (be == null) continue;
            if (claimedElsewhere(main, be)) continue;   // 已经属于别的乐团的不抢
            be.setOrchestra(worldPosition);
            members.add(main);
            added++;
        }
        if (added > 0) reassign();
        return added;
    }

    /** 去掉已经不在的成员（乐器被拆了、乐谱架上的乐器被拿走、被别的乐团收走）。 */
    void validate() {
        for (BlockPos m : new ArrayList<>(members)) {
            InstrumentBlockEntity be = instrument(m);
            boolean bench = level != null && level.getBlockState(m).getBlock() instanceof dev.logos.dimension.music.StarbloomBenchBlock;
            boolean ok = be != null && worldPosition.equals(be.orchestra())
                && (bench ? dev.logos.dimension.music.StarbloomBenchBlock.linked(level, m) == null : be.playable() && be.kind() != null);
            if (!ok && (level != null && level.isLoaded(m))) {
                members.remove(m);
                masks.remove(m);
                locked.remove(m);
            }
        }
    }

    // —— 分派 ——

    /** 按总谱重新自动分派（手动改过、锁定的不动）。没有总谱时全部休息。 */
    public void reassign() {
        MusicRoll roll = scoreRoll();
        if (roll == null) {
            for (BlockPos m : members) if (!locked.contains(m)) masks.put(m, 0);
            changed();
            return;
        }
        List<OrchestraArranger.Member> list = new ArrayList<>();
        List<BlockPos> order = new ArrayList<>();
        for (BlockPos m : members) {
            InstrumentBlockEntity be = instrument(m);
            InstrumentKind kind = be == null || !be.playable() ? null : be.kind();
            if (kind == null) {
                if (!locked.contains(m)) masks.put(m, 0);
                continue;
            }
            list.add(new OrchestraArranger.Member(kind, seat(m), locked.contains(m) ? masks.getOrDefault(m, 0) : -1));
            order.add(m);
        }
        int[] result = OrchestraArranger.assign(roll, list);
        for (int i = 0; i < order.size(); i++) masks.put(order.get(i), result[i]);
        changed();
    }

    /** 手动改派：这件乐器演奏这些声部，并锁定。 */
    public void assign(int index, int mask) {
        if (index < 0 || index >= members.size()) return;
        BlockPos m = members.get(index);
        masks.put(m, mask & 0xFFFF);
        locked.add(m);
        changed();
        if (isPlaying()) broadcast();
    }

    public void unlock(int index) {
        if (index < 0 || index >= members.size()) return;
        locked.remove(members.get(index));
        reassign();
        if (isPlaying()) broadcast();
    }

    public void remove(int index) {
        if (index < 0 || index >= members.size()) return;
        BlockPos m = members.get(index);
        forget(m);
        InstrumentBlockEntity be = instrument(m);
        if (be != null && worldPosition.equals(be.orchestra())) {
            be.setOrchestra(null);
            be.setConducted(null);
        }
        if (isPlaying()) broadcast();
    }

    // —— 总谱 ——

    public void setScore(ItemStack stack) {
        score = stack;
        showScore();
        reassign();
    }

    public ItemStack takeScore() {
        ItemStack out = score;
        score = ItemStack.EMPTY;
        showScore();
        reassign();
        return out;
    }

    /** 谱架上摊开 / 收起谱子（方块状态，模型跟着换）。 */
    private void showScore() {
        if (level == null || level.isClientSide()) return;
        BlockState state = level.getBlockState(worldPosition);
        boolean has = !score.isEmpty();
        if (state.getBlock() instanceof PodiumBlock && state.getValue(PodiumBlock.SCORE) != has) {
            level.setBlock(worldPosition, state.setValue(PodiumBlock.SCORE, has), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
    }

    // —— 演奏 ——

    /** 从第 fromBar 小节起奏（先有一拍预备），按设定的速度。没有总谱、没有成员时不开始。 */
    public boolean start(int fromBar) {
        return start(fromBar, 0, null);
    }

    /**
     * 从第 fromBar 小节起奏。countInMs > 0 时是指挥起奏前打的两拍之间隔：预备拍就按这个速度，乐团在下一拍进来，
     * 打拍子的人（who）就是指挥，乐团此后跟着他的拍子。
     */
    public boolean start(int fromBar, int countInMs, net.minecraft.world.entity.player.@Nullable Player who) {
        if (!(level instanceof ServerLevel)) return false;
        RollInfo info = score.get(Instruments.MUSIC_ROLL.get());
        MusicRoll roll = scoreRoll();
        if (info == null || roll == null) return false;
        validate();
        if (members.isEmpty()) return false;
        for (BlockPos m : members) {
            InstrumentBlockEntity be = instrument(m);
            if (be == null) continue;
            be.stopPlayback();
            be.setConducted(worldPosition);
        }
        beats = BeatMap.of(roll);
        double from = beats.barStartMs(Math.max(0, fromBar));
        double prep = ConductorPattern.conduct(beats.at(from)).beatMs();
        rate = countInMs > 0 ? BeatFollower.countIn(prep, countInMs) : tempo / 100.0;
        leader = countInMs > 0 ? who : null;
        expression = 1;
        playing = info.id();
        playingHash = info.hash();
        durationMs = roll.durationMs();
        startOffsetMs = (int) Math.round(from - prep);
        startNanos = System.nanoTime();
        startTick = level.getGameTime();
        pendingTempo = -1;
        broadcast();
        return true;
    }

    public void stop() {
        if (playing == null) return;
        playing = null;
        beats = null;
        pendingTempo = -1;
        leader = null;
        expression = 1;
        for (BlockPos m : members) {
            InstrumentBlockEntity be = instrument(m);
            if (be != null && worldPosition.equals(be.conductedBy())) be.setConducted(null);
        }
        broadcast();
    }

    /** 改速度：在演奏时从下一小节起生效。 */
    public void setTempo(int percent) {
        int v = Math.max(MIN_TEMPO, Math.min(MAX_TEMPO, percent));
        if (playing != null && beats != null && leader != null) {
            tempo = v;   // 正有人打拍子：速度听他的，设定的留到他不打了再用
        } else if (playing != null && beats != null) {
            pendingTempo = v;
            pendingBar = beats.at(positionMs()).bar() + 1;
        } else {
            tempo = v;
        }
        changed();
    }

    /** 把速度换成 v，换的那一刻在第 bar 小节的开头：时钟从小节线起按新速度走，位置不跳。 */
    private void applyTempo(int v, double barMs) {
        long now = System.nanoTime();
        double nanosSinceBar = (positionMs() - barMs) / Math.max(1e-6, rate) * 1_000_000.0;
        startOffsetMs = (int) Math.round(barMs);
        startNanos = now - (long) nanosSinceBar;
        tempo = v;
        rate = v / 100.0;
        pendingTempo = -1;
        changed();
        broadcast();
    }

    public void setGain(int part, int db) {
        if (part < 0 || part >= gains.length) return;
        gains[part] = (byte) Math.max(MIN_GAIN, Math.min(MAX_GAIN, db));
        changed();
        if (isPlaying()) broadcast();
    }

    public void setMaster(int db) {
        master = Math.max(MIN_GAIN, Math.min(MAX_GAIN, db));
        changed();
        if (isPlaying()) broadcast();
    }

    /**
     * 站在台上的人打了一拍（docs/ORCHESTRA.md 第 17 节）：此刻乐团在 positionMs、此后按 clockRate 走（held 时停住），
     * 手势的力度 expression。他就成了指挥，直到走下指挥台、放下指挥棒。太密的（30 毫秒内）只记下、不另发包，延长、接着走总是马上发。
     */
    public void follow(net.minecraft.world.entity.player.Player who, int positionMs, double clockRate, float expression, boolean held) {
        if (playing == null) return;
        boolean wasHeld = rate == 0;
        startOffsetMs = positionMs;
        startNanos = System.nanoTime();
        rate = held ? 0 : Math.max(BeatFollower.MIN_RATE * 0.6, Math.min(BeatFollower.MAX_RATE * 1.5, clockRate));
        this.expression = Math.max(0.3f, Math.min(1.6f, expression));
        leader = who;
        pendingTempo = -1;
        long now = startNanos;
        if (wasHeld != held || now - lastFollowNanos > 30_000_000L) {
            lastFollowNanos = now;
            broadcast();
        }
    }

    /** 指挥走下指挥台、放下指挥棒、下线：乐团从此刻的位置按设定的速度接着自己走。 */
    private void releaseLeader() {
        int pos = positionMs();
        leader = null;
        expression = 1;
        startOffsetMs = pos;
        startNanos = System.nanoTime();
        rate = tempo / 100.0;
        broadcast();
    }

    public void toggleMetronome() {
        metronome = !metronome;
        changed();
    }

    public void toggleMute(int part) {
        if (part < 0 || part >= MusicRoll.MAX_PARTS) return;
        muteMask ^= 1 << part;
        changed();
        if (isPlaying()) broadcast();
    }

    public void toggleSolo(int part) {
        if (part < 0 || part >= MusicRoll.MAX_PARTS) return;
        soloMask ^= 1 << part;
        changed();
        if (isPlaying()) broadcast();
    }

    public void onSignal(boolean signal) {
        if (signal == powered) return;
        powered = signal;
        if (signal && !isPlaying()) start(0);
        setChanged();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, PodiumBlockEntity be) {
        if (be.playing == null) return;
        int position = be.positionMs();
        if (position > be.durationMs + TAIL_MS) {
            be.stop();
            return;
        }
        if (be.leader != null && (be.leader.isRemoved() || !PodiumBlock.conducting(be.leader) || !pos.equals(PodiumBlock.under(be.leader)))) {
            be.releaseLeader();
        }
        if (be.pendingTempo >= 0 && be.beats != null) {
            double barMs = be.beats.barStartMs(be.pendingBar);
            if (position >= barMs) be.applyTempo(be.pendingTempo, barMs);
        }
        if ((level.getGameTime() - be.startTick) % HEARTBEAT_TICKS == HEARTBEAT_TICKS - 1) be.broadcast();
    }

    /** 把乐团的播放状态发给附近的玩家（只发给某个人时传 to）。 */
    public void broadcast() {
        sendState(null);
    }

    public void sendState(net.minecraft.server.level.@Nullable ServerPlayer to) {
        if (!(level instanceof ServerLevel server)) return;
        List<EnsemblePayloads.MemberPart> list = new ArrayList<>();
        for (BlockPos m : members) list.add(new EnsemblePayloads.MemberPart(m, masks.getOrDefault(m, 0)));
        var p = new EnsemblePayloads.EnsembleState(worldPosition, playing, playingHash, positionMs(), durationMs, tempo, list,
            gains.clone(), muteMask, soloMask, (int) Math.round(rate * 1000), Math.round(expression * 1000), master,
            leader == null ? null : leader.getUUID());
        if (to != null) {
            PacketDistributor.sendToPlayer(to, p);
        } else {
            Vec3 at = standAt();
            PacketDistributor.sendToPlayersNear(server, null, at.x, at.y, at.z, BROADCAST_RANGE, p);
        }
    }

    private void changed() {
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null && !level.isClientSide()) {
            stop();
            for (BlockPos m : members) {
                InstrumentBlockEntity be = instrument(m);
                if (be != null && worldPosition.equals(be.orchestra())) be.setOrchestra(null);
            }
            if (!score.isEmpty()) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, score);
                score = ItemStack.EMPTY;
            }
        }
        super.preRemoveSideEffects(pos, state);
    }

    // —— 存盘与同步 ——

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        score = input.read("score", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        members.clear();
        masks.clear();
        locked.clear();
        List<Long> ms = input.read("members", Codec.LONG.listOf()).orElse(List.of());
        List<Integer> mk = input.read("masks", Codec.INT.listOf()).orElse(List.of());
        List<Integer> lk = input.read("locked", Codec.INT.listOf()).orElse(List.of());
        // 整座搬走（结构方块、蓝图、世界生成把乐团放到别处）：成员跟着平移
        BlockPos shift = shift(input, worldPosition);
        for (int i = 0; i < ms.size() && i < MAX_MEMBERS; i++) {
            BlockPos m = BlockPos.of(ms.get(i)).offset(shift);
            members.add(m);
            masks.put(m, i < mk.size() ? mk.get(i) : 0);
            if (i < lk.size() && lk.get(i) != 0) locked.add(m);
        }
        tempo = Math.max(MIN_TEMPO, Math.min(MAX_TEMPO, input.read("tempo", Codec.INT).orElse(100)));
        List<Integer> g = input.read("gains", Codec.INT.listOf()).orElse(List.of());
        for (int i = 0; i < gains.length; i++) gains[i] = (byte) (i < g.size() ? Math.max(MIN_GAIN, Math.min(MAX_GAIN, g.get(i))) : 0);
        muteMask = input.read("mute", Codec.INT).orElse(0);
        soloMask = input.read("solo", Codec.INT).orElse(0);
        master = Math.max(MIN_GAIN, Math.min(MAX_GAIN, input.read("master", Codec.INT).orElse(0)));
        metronome = input.read("metronome", Codec.BOOL).orElse(false);
        powered = input.read("powered", Codec.BOOL).orElse(false);
    }

    /**
     * 存盘时记着自己在哪（{@code origin}）；读回来的地方不一样（被结构方块、蓝图、世界生成搬到别处）时，返回要平移的量，
     * 记着的成员、乐团位置都按它平移。没有 origin 的旧存档不平移。只处理平移，不处理旋转。
     */
    public static BlockPos shift(ValueInput input, BlockPos here) {
        return input.read("origin", Codec.LONG).map(o -> here.subtract(BlockPos.of(o))).orElse(BlockPos.ZERO);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("origin", Codec.LONG, worldPosition.asLong());
        if (!score.isEmpty()) output.store("score", ItemStack.OPTIONAL_CODEC, score);
        List<Long> ms = new ArrayList<>();
        List<Integer> mk = new ArrayList<>();
        List<Integer> lk = new ArrayList<>();
        for (BlockPos m : members) {
            ms.add(m.asLong());
            mk.add(masks.getOrDefault(m, 0));
            lk.add(locked.contains(m) ? 1 : 0);
        }
        output.store("members", Codec.LONG.listOf(), ms);
        output.store("masks", Codec.INT.listOf(), mk);
        output.store("locked", Codec.INT.listOf(), lk);
        output.store("tempo", Codec.INT, tempo);
        List<Integer> g = new ArrayList<>();
        for (byte b : gains) g.add((int) b);
        output.store("gains", Codec.INT.listOf(), g);
        output.store("mute", Codec.INT, muteMask);
        output.store("solo", Codec.INT, soloMask);
        output.store("master", Codec.INT, master);
        output.store("metronome", Codec.BOOL, metronome);
        output.store("powered", Codec.BOOL, powered);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }
}
