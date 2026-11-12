package dev.logos.dimension.music;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 大提琴、低音提琴后面的位置：看不见的座位，演奏者骑在上面（大提琴坐在琴后的凳子上，低音提琴站在琴后）。
 * 身体一直朝着乐器，头能左右转 75°；没人坐了、乐器被拆了就消失。不存盘：重进存档时演奏者是站着的。
 */
public final class InstrumentSeat extends Entity {
    private static final EntityDataAccessor<BlockPos> INSTRUMENT = SynchedEntityData.defineId(InstrumentSeat.class, EntityDataSerializers.BLOCK_POS);

    public InstrumentSeat(EntityType<? extends InstrumentSeat> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    /** 坐到 main 处的乐器后面：位置与朝向由乐器的方块状态决定。 */
    public void bind(BlockPos main, Vec3 at, float yaw) {
        entityData.set(INSTRUMENT, main.immutable());
        snapTo(at.x, at.y, at.z, yaw, 0);
    }

    public BlockPos instrument() {
        return entityData.get(INSTRUMENT);
    }

    /** 这张座位前的乐器（方块不在了返回 null）。 */
    public @Nullable InstrumentKind kind() {
        var state = level().getBlockState(instrument());
        return state.getBlock() instanceof SeatedInstrumentBlock block ? block.kind() : null;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(INSTRUMENT, BlockPos.ZERO);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {}

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {}

    // 类型要允许序列化才能被玩家骑乘；临时座位自身不写进区块。
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean save(ValueOutput output) {
        return false;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean ignorePassenger) {
        return false;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty();
    }

    /** 座位的位置就是演奏者双脚的位置：抵消乘客自己的骑乘挂点（玩家是脚上方 0.6 格）。 */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        return passenger.getVehicleAttachmentPoint(this);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        // 站起来退到座位后面一点，落在地面上（座位本身比地面略低）
        return BenchSeat.dismountBehind(this, passenger, 0.7);
    }

    /**
     * 不在这里马上消失：原版下车时先移除乘客、再问座位人放哪；座位已经没了的话就把人留在坐着的位置，
     * 双脚比地面低 2.25 像素，人陷进地里一格。空座位在下一刻的 tick 里消失。
     */
    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        super.positionRider(passenger, move);
        clampRotation(passenger);
    }

    @Override
    public void onPassengerTurned(Entity passenger) {
        clampRotation(passenger);
    }

    /** 身体朝着乐器，头最多转 75°（同原版船上的做法）。 */
    private void clampRotation(Entity passenger) {
        passenger.setYBodyRot(getYRot());
        float diff = Mth.wrapDegrees(passenger.getYRot() - getYRot());
        float clamped = Mth.clamp(diff, -75.0f, 75.0f);
        passenger.yRotO += clamped - diff;
        passenger.setYRot(passenger.getYRot() + clamped - diff);
        passenger.setYHeadRot(passenger.getYRot());
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        if (getPassengers().isEmpty() || kind() == null) {
            ejectPassengers();
            discard();
        }
    }
}
