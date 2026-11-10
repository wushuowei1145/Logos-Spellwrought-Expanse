package dev.logos.dimension.music;

import com.mojang.serialization.Codec;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * 乐器主块的方块实体：纸带槽、声部选择、红石模式，以及服务端的播放状态和当前演奏者。
 * 播放状态不存盘（区块卸载或重进存档后停在开头），纸带槽和设置存盘并同步给客户端。
 */
public class InstrumentBlockEntity extends BlockEntity {
    /** 播放结束后多留一会儿，让最后的音放完。 */
    private static final int TAIL_MS = 1500;
    private static final int HEARTBEAT_TICKS = 100;

    private ItemStack roll = ItemStack.EMPTY;
    private int partMask = 0xFFFF;
    private boolean stopWithSignal;
    private boolean powered;
    private @Nullable UUID performer;
    /** 管风琴：开着的音栓（掩码）与混响档位。 */
    private int stops = dev.logos.dimension.music.organ.OrganStop.ALWAYS;
    private int reverbMode = dev.logos.dimension.music.organ.RoomAcoustics.AUTO;
    private dev.logos.dimension.music.organ.@Nullable OrganStructure organ;
    private long organAt = Long.MIN_VALUE;

    // 服务端播放状态
    private @Nullable UUID playing;
    private long playingHash;
    private int playingDuration;
    private boolean playingTemporary;
    private long startTick;
    private int startOffsetMs;
    private int pausedAt = -1;

    public InstrumentBlockEntity(BlockPos pos, BlockState state) {
        super(Instruments.INSTRUMENT_ENTITY.get(), pos, state);
    }

    public InstrumentKind kind() {
        return getBlockState().getBlock() instanceof InstrumentBlock b ? b.kind() : InstrumentKind.GRAND_PIANO;
    }

    public ItemStack roll() { return roll; }
    public int partMask() { return partMask; }
    public boolean stopWithSignal() { return stopWithSignal; }
    public @Nullable UUID performer() { return performer; }
    public int stops() { return stops; }
    public int reverbMode() { return reverbMode; }

    /** 管风琴的音管结构（缓存 2 秒）；不是管风琴时为 null。 */
    public dev.logos.dimension.music.organ.@Nullable OrganStructure organ() {
        if (kind() != InstrumentKind.PIPE_ORGAN || level == null) return null;
        long now = level.getGameTime();
        if (organ == null || now - organAt > 40 || now < organAt) {
            organ = dev.logos.dimension.music.organ.OrganStructure.scan(level, getBlockState(), worldPosition);
            organAt = now;
        }
        return organ;
    }

    /** 立即重新识别音管（不等 2 秒缓存过期）。 */
    public void rescanOrgan() {
        organ = null;
    }

    /** 此刻真正响的音栓：开着的、且结构里有对应音管的；一个都没有时用长笛 8′。 */
    public int effectiveStops() {
        var o = organ();
        int mask = o == null ? stops : stops & o.availableStops();
        return mask == 0 ? dev.logos.dimension.music.organ.OrganStop.ALWAYS : mask;
    }

    /** 实时演奏转发给附近玩家的半径。 */
    public int hearingRange() {
        var o = organ();
        return o == null ? kind().broadcastRange() : o.range();
    }

    public void setStops(int mask) {
        mask &= dev.logos.dimension.music.organ.OrganStop.ALL;
        var o = organ();
        if (o != null) mask &= o.availableStops();
        stops = mask == 0 ? dev.logos.dimension.music.organ.OrganStop.ALWAYS : mask;
        changed();
    }

    public void setReverbMode(int mode) {
        reverbMode = Math.max(0, Math.min(3, mode));
        changed();
    }

    /** 声音的中心：钢琴是 2×2 的中间，竖琴是两格高的中间。 */
    public Vec3 soundCenter() {
        return InstrumentBlock.center(getBlockState(), worldPosition);
    }

    /** 声音实际从哪里发出：管风琴是音管墙的中心，其他乐器同 {@link #soundCenter()}。 */
    public Vec3 voiceCenter() {
        var o = organ();
        return o == null ? soundCenter() : o.center();
    }

    // —— 纸带槽 ——

    public void setRoll(ItemStack stack) {
        if (!ItemStack.isSameItemSameComponents(stack, roll)) stopPlayback();
        roll = stack.copyWithCount(Math.min(1, stack.getCount()));
        changed();
    }

    public ItemStack takeRoll() {
        ItemStack out = roll;
        roll = ItemStack.EMPTY;
        stopPlayback();
        changed();
        return out;
    }

    public void setPartMask(int mask) {
        partMask = mask & 0xFFFF;
        changed();
        if (playing != null) broadcast();
    }

    public void setStopWithSignal(boolean value) {
        stopWithSignal = value;
        changed();
    }

    public void setPerformer(@Nullable UUID uuid) {
        performer = uuid;
        changed();
    }

    private void changed() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    // —— 播放（服务端）——

    public boolean isPlaying() {
        return playing != null && pausedAt < 0;
    }

    public int positionMs() {
        if (playing == null || level == null) return 0;
        if (pausedAt >= 0) return pausedAt;
        return startOffsetMs + (int) Math.min(Integer.MAX_VALUE / 2, (level.getGameTime() - startTick) * 50L);
    }

    /** 播放纸带槽里的纸带；暂停中则从暂停处继续。 */
    public boolean play() {
        if (level == null) return false;
        if (playing != null && pausedAt >= 0) {
            startOffsetMs = pausedAt;
            startTick = level.getGameTime();
            pausedAt = -1;
            broadcast();
            return true;
        }
        RollInfo info = roll.get(Instruments.MUSIC_ROLL.get());
        if (info == null || RollStore.load(info.id()) == null) return false;
        start(info.id(), info.hash(), info.durationMs(), false);
        return true;
    }

    public void playTemporary(UUID id, long hash, int durationMs) {
        stopPlayback();
        start(id, hash, durationMs, true);
    }

    private void start(UUID id, long hash, int durationMs, boolean temporary) {
        if (playing != null && playingTemporary && !playing.equals(id)) RollStore.dropTemporary(playing);
        playing = id;
        playingHash = hash;
        playingDuration = durationMs;
        playingTemporary = temporary;
        startTick = level.getGameTime();
        startOffsetMs = 0;
        pausedAt = -1;
        broadcast();
    }

    public void pause() {
        if (playing == null || pausedAt >= 0) return;
        pausedAt = positionMs();
        broadcast();
    }

    public void stopPlayback() {
        if (playing == null) return;
        if (playingTemporary) RollStore.dropTemporary(playing);
        playing = null;
        pausedAt = -1;
        broadcast();
    }

    /** 把当前播放状态发给追踪这个区块的玩家（只发给某个人时传 to）。 */
    public void broadcast() {
        sendState(null);
    }

    public void sendState(@Nullable ServerPlayer to) {
        if (!(level instanceof ServerLevel server)) return;
        MusicPayloads.Playback p = new MusicPayloads.Playback(worldPosition, playing, playingHash, positionMs(),
            playing != null && pausedAt < 0, partMask);
        if (to != null) {
            PacketDistributor.sendToPlayer(to, p);
        } else {
            PacketDistributor.sendToPlayersTrackingChunk(server, ChunkPos.containing(worldPosition), p);
        }
    }

    public boolean hasPlayback() {
        return playing != null;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, InstrumentBlockEntity be) {
        if (be.playing != null && be.pausedAt < 0) {
            if (be.positionMs() > be.playingDuration + TAIL_MS) {
                be.stopPlayback();
            } else if ((level.getGameTime() - be.startTick) % HEARTBEAT_TICKS == HEARTBEAT_TICKS - 1) {
                be.broadcast();
            }
        }
        if (be.performer != null && level.getGameTime() % 20 == 0) {
            ServerPlayer p = level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(be.performer);
            if (p == null || p.level() != level || !Instruments.inReach(p, be)) Instruments.release(be, p);
        }
    }

    /** 红石：上升沿开始播放；「信号停止时停止」打开时，失去信号就停。 */
    public void onSignal(boolean signal) {
        if (signal == powered) return;
        powered = signal;
        if (signal) {
            if (!isPlaying()) play();
        } else if (stopWithSignal) {
            stopPlayback();
        }
        setChanged();
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null && !roll.isEmpty()) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, roll);
            roll = ItemStack.EMPTY;
        }
        if (playing != null && playingTemporary) RollStore.dropTemporary(playing);
        playing = null;
        if (level instanceof ServerLevel) broadcast();
        super.preRemoveSideEffects(pos, state);
    }

    // —— 存盘与同步 ——

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        roll = input.read("roll", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY);
        partMask = input.read("part_mask", Codec.INT).orElse(0xFFFF);
        stopWithSignal = input.read("stop_with_signal", Codec.BOOL).orElse(false);
        powered = input.read("powered", Codec.BOOL).orElse(false);
        performer = input.read("performer", UUIDUtil.CODEC).orElse(null);
        stops = input.read("stops", Codec.INT).orElse(dev.logos.dimension.music.organ.OrganStop.ALWAYS);
        reverbMode = input.read("reverb", Codec.INT).orElse(0);
        organ = null;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!roll.isEmpty()) output.store("roll", ItemStack.OPTIONAL_CODEC, roll);
        output.store("part_mask", Codec.INT, partMask);
        output.store("stop_with_signal", Codec.BOOL, stopWithSignal);
        output.store("powered", Codec.BOOL, powered);
        if (kind() == InstrumentKind.PIPE_ORGAN) {
            output.store("stops", Codec.INT, stops);
            output.store("reverb", Codec.INT, reverbMode);
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveCustomOnly(registries);
        // 演奏者只同步、不存盘
        if (performer != null) tag.store("performer", UUIDUtil.CODEC, performer);
        return tag;
    }
}
