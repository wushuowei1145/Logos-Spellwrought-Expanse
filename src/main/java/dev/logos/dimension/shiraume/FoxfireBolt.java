package dev.logos.dimension.shiraume;

import dev.logos.dimension.foxfire.FoxfireFlame;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 狐火飞弹（docs/SHIRAUME_BOSS.md 第 6 节）：狐形八分之一血以下，每招之后从尾尖放出几颗，慢慢追向玩家。
 * 飞得不快、拐弯有上限，走位能甩开；盾也挡得住。打中是她独一份的淡粉狐火（docs/FOXFIRE.md）：炸开一团淡粉的火，之后狐火缠身。
 */
public class FoxfireBolt extends Projectile {
    /** 每刻飞多远、每刻最多转多少（弧度）、活多久。 */
    static final double SPEED = .42, TURN = Math.toRadians(5.5);
    static final int LIFE = 110;
    static final float DAMAGE = 5;
    private @Nullable UUID target;
    private int age;

    public FoxfireBolt(EntityType<? extends FoxfireBolt> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    /** 从 {@code from} 放出一颗，起始方向 {@code direction}，追 {@code target}。 */
    public static FoxfireBolt launch(ServerLevel level, Mob owner, Vec3 from, Vec3 direction, @Nullable LivingEntity target) {
        FoxfireBolt f = new FoxfireBolt(ShiraumeEntities.FOXFIRE.get(), level);
        f.setOwner(owner);
        f.setPos(from.x, from.y, from.z);
        f.setDeltaMovement(direction.normalize().scale(SPEED));
        f.target = target == null ? null : target.getUUID();
        level.addFreshEntity(f);
        return f;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    public void tick() {
        super.tick();
        Vec3 v = getDeltaMovement();
        if (level() instanceof ServerLevel level) {
            if (++age > LIFE) {
                fizzle(level);
                return;
            }
            Entity t = target == null ? null : level.getEntity(target);
            if (t instanceof LivingEntity living && living.isAlive() && age > 6) {
                Vec3 want = living.position().add(0, living.getBbHeight() * .5, 0).subtract(position()).normalize();
                Vec3 have = v.lengthSqr() < 1e-6 ? want : v.normalize();
                double angle = Math.acos(Math.max(-1, Math.min(1, have.dot(want))));
                Vec3 dir = angle <= TURN ? want : slerp(have, want, TURN / angle);
                v = dir.scale(SPEED);
                setDeltaMovement(v);
            }
            HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
            if (hit.getType() != HitResult.Type.MISS) {
                hitTargetOrDeflectSelf(hit);
                if (isRemoved()) return;
            }
        } else {
            level().addParticle(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.2f), getX(), getY() + .3, getZ(), 0, 0, 0);
            if (tickCount % 2 == 0) level().addParticle(ParticleTypes.CHERRY_LEAVES, getX(), getY() + .3, getZ(), 0, 0, 0);
        }
        setPos(getX() + v.x, getY() + v.y, getZ() + v.z);
    }

    private static Vec3 slerp(Vec3 a, Vec3 b, double t) {
        double dot = Math.max(-1, Math.min(1, a.dot(b)));
        double theta = Math.acos(dot) * t;
        Vec3 rel = b.subtract(a.scale(dot));
        if (rel.lengthSqr() < 1e-8) return a;
        return a.scale(Math.cos(theta)).add(rel.normalize().scale(Math.sin(theta))).normalize();
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return super.canHitEntity(entity) && !(entity instanceof ShiraumeBoss) && !(entity instanceof FoxfireBolt);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (!(level() instanceof ServerLevel level) || !(result.getEntity() instanceof LivingEntity victim)) return;
        Entity owner = getOwner();
        DamageSource probe = level.damageSources().mobProjectile(this, owner instanceof LivingEntity l ? l : null);
        if (ShiraumeStrike.blocks(victim, probe, DAMAGE)) {
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 1, 1.2f);
        } else if (owner instanceof Mob mob) {
            ShiraumeStrike.foxfire(mob, victim, DAMAGE * Shiraume.difficultyScale(level));
        }
        fizzle(level);
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (result.getType() == HitResult.Type.BLOCK && level() instanceof ServerLevel level) fizzle(level);
    }

    private void fizzle(ServerLevel level) {
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + .3, getZ(), 12, .3, .3, .3, .02);
        level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.4f), getX(), getY() + .3, getZ(), 10, .25, .25, .25, 0);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, .6f, 1.6f);
        discard();
    }

    @Override
    public boolean isOnFire() {
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
    }
}
