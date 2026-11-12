package dev.logos.dimension.music;

import com.mojang.serialization.Codec;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 星辰花琴凳上的座位（docs/ORCHESTRA.md 第 14 节）：看不见，坐的人骑在上面，身体朝着琴凳的方向，头能左右转 75°。
 * 坐的是生物时连同生物一起存盘（重进存档还坐在那里）；坐的是玩家时不存（玩家不存在区块里）。
 * 没人坐了、琴凳被拆了就消失；每半秒按坐的人手里的东西刷新一次琴凳演奏什么。
 */
public final class BenchSeat extends Entity {
    private static final EntityDataAccessor<BlockPos> BENCH = SynchedEntityData.defineId(BenchSeat.class, EntityDataSerializers.BLOCK_POS);

    public BenchSeat(EntityType<? extends BenchSeat> type, Level level) {
        super(type, level);
        setNoGravity(true);
        noPhysics = true;
    }

    public void bind(BlockPos bench, Vec3 at, float yaw) {
        entityData.set(BENCH, bench.immutable());
        snapTo(at.x, at.y, at.z, yaw, 0);
    }

    /** 挪到 at、朝 yaw（琴凳前的乐器放上、拆掉时）；没变就不动。 */
    public void align(Vec3 at, float yaw) {
        if (position().distanceToSqr(at) > 1.0e-8 || Mth.wrapDegrees(getYRot() - yaw) != 0) snapTo(at.x, at.y, at.z, yaw, 0);
    }

    public BlockPos bench() {
        return entityData.get(BENCH);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(BENCH, BlockPos.ZERO);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        input.read("bench", Codec.LONG).ifPresent(p -> entityData.set(BENCH, BlockPos.of(p)));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.store("bench", Codec.LONG, bench().asLong());
    }

    /** 坐着生物时存盘（连同乘客）；空座位、坐着玩家时不存。 */
    @Override
    public boolean shouldBeSaved() {
        for (Entity passenger : getPassengers()) if (!(passenger instanceof Player)) return super.shouldBeSaved();
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

    /** 座位的位置就是坐的人双脚的位置：抵消乘客自己的骑乘挂点。 */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        return passenger.getVehicleAttachmentPoint(this);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        // 站起来退到凳子后面，落在地面上
        Vec3 back = Vec3.directionFromRotation(0, getYRot()).scale(-0.8);
        return new Vec3(getX() + back.x, Math.floor(getY() + 0.5), getZ() + back.z);
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide() && !isRemoved()) {
            discard();
            StarbloomBenchBlock.refresh(level(), bench());
        }
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

    /** 身体朝着琴凳的方向，头最多转 75°。 */
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
        if (getPassengers().isEmpty() || !(level().getBlockState(bench()).getBlock() instanceof StarbloomBenchBlock)) {
            ejectPassengers();
            discard();
            return;
        }
        if (tickCount % 10 == 0) StarbloomBenchBlock.refresh(level(), bench());
    }
}
