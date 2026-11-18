package dev.logos.dimension.worldtree;

import dev.logos.dimension.item.LogosNatureBridge;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 世界树之弓的箭：原版箭的网格，命中时造成 Logos 生命伤害（不走原版箭的物理伤害）。
 * <ul>
 *   <li>虚影追踪箭（{@code phantom}）：锁定一个目标就有一支，飞向它、只打它；没有重力，6 秒后消散；不能捡。</li>
 *   <li>中间的实箭：笔直的高速箭，没有重力，4 秒后消散；命中施加 5 秒生命易伤 I；用掉的那支箭照原版规则可以捡回。</li>
 *   <li>左键横着拉弓射出的扇形箭（{@code pierce}）：不追踪，穿过并伤害路上碰到的每个目标（每个只伤一次）。</li>
 * </ul>
 * 箭身朝向每刻按速度定（不像原版那样慢慢转过去），追踪箭转向后立刻把速度发给客户端，箭头才一直指着飞的方向。
 */
public class WorldtreeArrow extends AbstractArrow {
    private static final EntityDataAccessor<Boolean> PHANTOM = SynchedEntityData.defineId(WorldtreeArrow.class, EntityDataSerializers.BOOLEAN);
    /** 追踪箭每刻最多转多少度、最终速度（格/刻）。 */
    public static final float TURN = 20, HOMING_SPEED = 2.2f;
    /** 实箭的速度（原版满弓 3）。 */
    public static final float STRAIGHT_SPEED = 5;
    public static final int HOMING_LIFE = 120, STRAIGHT_LIFE = 80, VULNERABILITY_TICKS = 100;
    /** 实箭的生命伤害。 */
    public static final float STRAIGHT_DAMAGE = 8;

    private int target = -1, life;
    private float damage;
    private boolean pierce;
    /** 穿透箭已经伤过的目标。 */
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet struck = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    public WorldtreeArrow(EntityType<? extends WorldtreeArrow> type, Level level) {
        super(type, level);
    }

    private WorldtreeArrow(Level level, LivingEntity owner, ItemStack pickup, ItemStack bow) {
        super(Worldtree.ARROW.get(), owner, level, pickup, bow);
        setNoGravity(true);
    }

    /** 飞向 target 的虚影箭。 */
    public static WorldtreeArrow homing(Level level, LivingEntity owner, ItemStack bow, LivingEntity target, float damage) {
        WorldtreeArrow arrow = new WorldtreeArrow(level, owner, new ItemStack(Items.ARROW), bow);
        arrow.entityData.set(PHANTOM, true);
        arrow.target = target.getId();
        arrow.damage = damage;
        arrow.pickup = Pickup.DISALLOWED;
        return arrow;
    }

    /** 中间的实箭；pickup 是用掉的那支箭。pierce 为真时穿透（左键横着拉弓）。 */
    public static WorldtreeArrow straight(Level level, LivingEntity owner, ItemStack bow, ItemStack pickup, boolean pierce) {
        WorldtreeArrow arrow = new WorldtreeArrow(level, owner, pickup, bow);
        arrow.damage = STRAIGHT_DAMAGE;
        arrow.pierce = pierce;
        return arrow;
    }

    /** 左键横着拉弓的扇形虚影箭：不追踪，穿透。 */
    public static WorldtreeArrow spread(Level level, LivingEntity owner, ItemStack bow, float damage) {
        WorldtreeArrow arrow = new WorldtreeArrow(level, owner, new ItemStack(Items.ARROW), bow);
        arrow.entityData.set(PHANTOM, true);
        arrow.damage = damage;
        arrow.pierce = true;
        arrow.pickup = Pickup.DISALLOWED;
        return arrow;
    }

    public boolean phantom() {
        return entityData.get(PHANTOM);
    }

    public int target() {
        return target;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(PHANTOM, false);
    }

    @Override
    public void tick() {
        if (!level().isClientSide()) {
            if (++life > (phantom() ? HOMING_LIFE : STRAIGHT_LIFE)) {
                LogosNatureBridge.trail(this, true);
                discard();
                return;
            }
            if (phantom() && !isInGround() && level().getEntity(target) instanceof LivingEntity aim && aim.isAlive()) steer(aim);
        }
        super.tick();
        if (!isRemoved() && !isInGround()) face();
        if (!level().isClientSide() && !isRemoved() && !isInGround()) LogosNatureBridge.trail(this, false);
    }

    /**
     * 箭身直接朝飞的方向（原版每刻只转过去两成，追踪箭拐弯时看起来是横着飞的）：服务端按速度；客户端按这一刻实际移动的方向，
     * 急转弯时客户端的位置还在插值追服务端，按速度摆箭身会和看到的移动方向差一截。
     */
    private void face() {
        Vec3 v = level().isClientSide() ? position().subtract(xo, yo, zo) : getDeltaMovement();
        if (v.lengthSqr() < 1e-6) v = getDeltaMovement();
        if (v.lengthSqr() < 1e-8) return;
        setYRot((float) (Math.atan2(v.x, v.z) * (180 / Math.PI)));
        setXRot((float) (Math.atan2(v.y, v.horizontalDistance()) * (180 / Math.PI)));
    }

    /** 朝目标转向（每刻最多 TURN 度），速度慢慢加到 HOMING_SPEED。 */
    private void steer(LivingEntity aim) {
        Vec3 v = getDeltaMovement();
        Vec3 to = aim.getBoundingBox().getCenter().subtract(position());
        if (to.lengthSqr() < 1e-6 || v.lengthSqr() < 1e-8) return;
        Vec3 dir = v.normalize(), want = to.normalize();
        double angle = Math.acos(Math.max(-1, Math.min(1, dir.dot(want))));
        double max = Math.toRadians(TURN);
        Vec3 next = angle <= max ? want : slerp(dir, want, max / angle);
        double speed = Math.min(HOMING_SPEED, v.length() + .12);
        setDeltaMovement(next.scale(speed));
        // 转向后立刻把速度发给客户端（hurtMarked 会让服务端发一次速度包）：客户端按自己的速度画箭身朝向，
        // 收不到就一直按离弦时散开的方向画，箭会横着飞
        needsSync = true;
        hurtMarked = true;
    }

    private static Vec3 slerp(Vec3 a, Vec3 b, double t) {
        double omega = Math.acos(Math.max(-1, Math.min(1, a.dot(b))));
        if (omega < 1e-4) return b;
        double s = Math.sin(omega);
        return a.scale(Math.sin((1 - t) * omega) / s).add(b.scale(Math.sin(t * omega) / s)).normalize();
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        // 精灵公主射的箭不伤主人、主人的宠物和使魔、别的同伴（穿透的扇形箭会扫过一路上的东西）
        if (getOwner() instanceof dev.logos.dimension.companion.CompanionEntity companion && !companion.canHarm(entity)) return false;
        // 追踪箭只打自己锁定的目标，穿过路上的其他东西；穿透箭不再伤同一个目标
        if (phantom() && target >= 0) return entity.getId() == target && super.canHitEntity(entity);
        if (pierce && struck.contains(entity.getId())) return false;
        return super.canHitEntity(entity);
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        if (!level().isClientSide() && hit.getEntity() instanceof LivingEntity living && getOwner() instanceof LivingEntity owner) {
            LogosNatureBridge.damage(owner, living, damage);
            if (!phantom() && living.isAlive())
                LogosNatureBridge.lifeVulnerability().ifPresent(effect -> living.addEffect(new MobEffectInstance(effect, VULNERABILITY_TICKS, 0), owner));
        }
        if (pierce) {
            struck.add(hit.getEntity().getId());
            return;
        }
        LogosNatureBridge.trail(this, true);
        discard();
    }

    @Override
    protected void onHitBlock(BlockHitResult hit) {
        if (phantom()) {
            LogosNatureBridge.trail(this, true);
            discard();
            return;
        }
        super.onHitBlock(hit);
        LogosNatureBridge.trail(this, true);
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(Items.ARROW);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putBoolean("Phantom", phantom());
        output.putFloat("LifeDamage", damage);
        output.putInt("Life", life);
        output.putBoolean("Pierce", pierce);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        entityData.set(PHANTOM, input.getBooleanOr("Phantom", false));
        damage = input.getFloatOr("LifeDamage", STRAIGHT_DAMAGE);
        life = input.getIntOr("Life", 0);
        pierce = input.getBooleanOr("Pierce", false);
    }
}
