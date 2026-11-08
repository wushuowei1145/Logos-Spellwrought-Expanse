package dev.logos.dimension.lingxiao;

import dev.logos.dimension.item.LingxiaoSwordItem;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.longsword.LongswordCombat;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** 只在御剑期间存在的服务端载具；不授予创造飞行，也不保存到世界。剑由现有战斗渲染层绘制。 */
public final class FlyingSword extends Entity {
    private final InterpolationHandler interpolation = new InterpolationHandler(this, 2);
    private UUID owner;
    private ItemStack held = ItemStack.EMPTY;
    private int slot;
    private int elapsed;

    public FlyingSword(EntityType<? extends FlyingSword> type, Level level) { super(type, level); setNoGravity(true); }

    public void bind(ServerPlayer player) {
        owner = player.getUUID(); held = player.getMainHandItem(); slot = player.getInventory().getSelectedSlot();
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
    @Override protected void readAdditionalSaveData(ValueInput input) {}
    @Override protected void addAdditionalSaveData(ValueOutput output) {}
    // 类型要允许序列化才能被玩家骑乘；临时实例自身不写区块或玩家的根载具记录。
    @Override public boolean shouldBeSaved() { return false; }
    @Override public boolean save(ValueOutput output) { return false; }
    @Override public boolean hurtServer(ServerLevel level, DamageSource source, float damage) { return false; }
    @Override public InterpolationHandler getInterpolation() { return interpolation; }
    @Override public boolean canUsePortal(boolean ignorePassenger) { return false; }
    @Override public Vec3 getDismountLocationForPassenger(net.minecraft.world.entity.LivingEntity passenger) {
        return new Vec3(getX(), getY() + FlightRules.SEAT_HEIGHT, getZ());
    }
    @Override protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty() && owner != null && owner.equals(passenger.getUUID());
    }
    @Override protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
        return new Vec3(0, FlightRules.SEAT_HEIGHT, 0).add(passenger.getVehicleAttachmentPoint(this));
    }
    @Override protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (!level().isClientSide() && passenger instanceof ServerPlayer player) {
            player.fallDistance = 0;
            if (player.isAlive() && player.getMainHandItem() == held) LongswordCombat.force(player, "flight_land");
            if (!isRemoved()) discard();
        }
    }

    public void stop() {
        for (var passenger : java.util.List.copyOf(getPassengers())) passenger.stopRiding();
        if (!isRemoved()) discard();
    }

    @Override public void tick() {
        super.tick();
        if (level().isClientSide()) { interpolation.interpolate(); return; }
        if (!(getFirstPassenger() instanceof ServerPlayer player) || owner == null || !owner.equals(player.getUUID())
                || !player.isAlive() || player.isSpectator() || player.level() != level()
                || !(held.getItem() instanceof LingxiaoSwordItem) || held.isEmpty()
                || player.getMainHandItem() != held || player.getInventory().getSelectedSlot() != slot
                || player.getLastClientInput().shift()) { stop(); return; }
        if (!player.hasInfiniteMaterials()) {
            double remaining = FlightRules.drain(LogosNatureBridge.mana(player));
            if (Double.isNaN(remaining)) {
                player.sendOverlayMessage(Component.translatable("message.logos_dimension.lingxiao.no_mana"));
                stop(); return;
            }
            LogosNatureBridge.mana(player, remaining);
        }
        Vec3 desired = FlightRules.movement(player.getLastClientInput(), player.getLookAngle(), player.getYRot());
        // 起飞时缓慢抬高一小段；之后松开方向键悬停，移动仍走原版方块碰撞。
        if (++elapsed <= 12) desired = new Vec3(desired.x, Math.max(.10, desired.y), desired.z);
        Vec3 velocity = getDeltaMovement().scale(.55).add(desired.scale(.45));
        Vec3 next = position().add(velocity);
        if (!level().hasChunkAt(BlockPos.containing(next)) || !level().getWorldBorder().isWithinBounds(BlockPos.containing(next))) velocity = Vec3.ZERO;
        setDeltaMovement(velocity);
        move(MoverType.SELF, velocity);
        if (horizontalCollision || verticalCollision) setDeltaMovement(Vec3.ZERO);
        setYRot(player.getYRot()); setXRot(0);
        player.fallDistance = 0;
    }
}
