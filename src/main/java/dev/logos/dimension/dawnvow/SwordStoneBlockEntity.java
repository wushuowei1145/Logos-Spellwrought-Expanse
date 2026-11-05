package dev.logos.dimension.dawnvow;

import dev.logos.dimension.item.LongswordItem;
import dev.logos.runtime.PillarBurst;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 石座里的那把剑与拔剑的进度。生成的石座里是一把新的曦誓；玩家插回去的剑原样保存。
 * 拔剑进度只在服务端记，同步给客户端的只有剑被拔起的高度 {@link #lift}，客户端据此把剑往上提、让它颤动。
 */
public class SwordStoneBlockEntity extends BlockEntity {
    /** 客户端已加载的石座，斜光逐帧从这里取；卸载后由弱引用自然丢掉。 */
    public static final Set<SwordStoneBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());
    private ItemStack sword = new ItemStack(LongswordItem.DAWNVOW.get());
    /** 剑被拔起的比例（0–1），服务端算，同步到客户端。 */
    private float lift;
    /** 客户端画出来的高度，逐刻追向 {@link #lift}。 */
    private float shown, shownBefore;
    private @Nullable UUID puller;
    private int progress;
    private long lastTug, restUntil;

    public SwordStoneBlockEntity(BlockPos pos, BlockState state) {
        super(Dawnvow.STONE_ENTITY.get(), pos, state);
    }

    public ItemStack sword() {
        return sword;
    }

    void setSword(ItemStack sword) {
        this.sword = sword;
        sync();
    }

    /** 画剑时的拔起高度，按帧插值。 */
    public float shownLift(float partialTick) {
        return shownBefore + (shown - shownBefore) * partialTick;
    }

    /** 有人正在拔（客户端：剑在颤）。 */
    public boolean straining() {
        return lift > 0;
    }

    /** 玩家按住右键时每 4 刻调用一次。 */
    void tug(ServerPlayer player) {
        ServerLevel level = (ServerLevel) this.level;
        long now = level.getGameTime();
        if (now < restUntil) return;
        boolean fresh = !player.getUUID().equals(puller) || now - lastTug > DawnvowTrial.LET_GO_TICKS;
        if (fresh) {
            puller = player.getUUID();
            progress = 0;
        } else {
            progress += (int) Math.min(now - lastTug, 6);
        }
        lastTug = now;
        boolean dawn = DawnvowTrial.dawn(level);
        Vec3 c = worldPosition.getCenter();
        if (!dawn && progress >= DawnvowTrial.RESIST_TICKS) {
            // 剑颤了一下又沉回石中
            player.sendOverlayMessage(Component.translatable("message.logos_dimension.dawnvow.unmoved"));
            level.playSound(null, worldPosition, SoundEvents.STONE_HIT, SoundSource.BLOCKS, 1f, 0.6f);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), c.x, c.y + 0.2, c.z, 12, 0.2, 0.05, 0.2, 0.05);
            progress = 0;
            restUntil = now + 40;
            setLift(0);
            return;
        }
        if (dawn && progress >= DawnvowTrial.PULL_TICKS) {
            draw(level, player);
            return;
        }
        float k = (float) progress / DawnvowTrial.PULL_TICKS;
        setLift(dawn ? k : Math.min(k, 0.08f));
        level.playSound(null, worldPosition, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.25f, dawn ? 0.6f + k * 0.6f : 0.5f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()), c.x, c.y + 0.15, c.z, 3, 0.12, 0.02, 0.12, 0.02);
        if (dawn) level.sendParticles(ParticleTypes.END_ROD, c.x, c.y + 0.6 + k, c.z, 1, 0.15, 0.3, 0.15, 0.01);
    }

    /** 拔出来了：剑交给玩家，石座变空，金色光柱从石中冲起。 */
    private void draw(ServerLevel level, ServerPlayer player) {
        BlockPos pos = worldPosition;
        ItemStack out = sword.copy();
        BlockState empty = Dawnvow.EMPTY_STONE.get().defaultBlockState().setValue(SwordStoneBlock.FACING, getBlockState().getValue(SwordStoneBlock.FACING));
        level.setBlock(pos, empty, Block.UPDATE_ALL);
        if (!player.getInventory().add(out)) player.drop(out, false);
        level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable("message.logos_dimension.dawnvow.drawn", player.getDisplayName()), false);
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.2f, 1.4f);
        level.playSound(null, pos, SoundEvents.BELL_RESONATE, SoundSource.BLOCKS, 1f, 1.2f);
        PillarBurst.play(level, Vec3.atBottomCenterOf(pos), 0.7f);
    }

    void serverTick() {
        // 松手后剑慢慢沉回去
        if (lift > 0 && level.getGameTime() - lastTug > DawnvowTrial.LET_GO_TICKS) {
            progress = 0;
            setLift(0);
        }
    }

    void clientTick() {
        shownBefore = shown;
        // 提起时跟得快，落回时慢慢沉下
        shown += (lift - shown) * (lift > shown ? 0.5f : 0.15f);
    }

    private void setLift(float value) {
        if (value == lift) return;
        lift = value;
        sync();
    }

    private void sync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) LOADED.add(this);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        sword = input.read("Sword", ItemStack.CODEC).orElseGet(() -> new ItemStack(LongswordItem.DAWNVOW.get()));
        lift = input.getFloatOr("Lift", 0);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("Sword", ItemStack.CODEC, sword);
        if (lift > 0) output.putFloat("Lift", lift);
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
