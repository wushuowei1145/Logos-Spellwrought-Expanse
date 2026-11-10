package dev.logos.dimension.shiraume;

import dev.logos.dimension.companion.ShiraumeCompanion;
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
 *
 * <p>少女白梅作为同伴（docs/SHIRAUME_COMPANION.md）也放它：伤害按她的数值（{@link #companion}），不伤她不该伤的
 * （{@link ShiraumeCompanion#canHarm}）。她倒下时飞回主人身边的那缕魂火也是它（{@link #spirit}）：不伤人，追上主人就散。
 */
public class FoxfireBolt extends Projectile {
    /** 每刻飞多远、每刻最多转多少（弧度）、活多久。 */
    static final double SPEED = .42, TURN = Math.toRadians(5.5);
    static final int LIFE = 110;
    static final float DAMAGE = 5;
    private @Nullable UUID target;
    private int age;
    /** 打中时的伤害；Boss 放的按难度再乘一次。 */
    private float damage = DAMAGE;
    /** 魂火：不伤人，追上目标就散。 */
    private boolean spirit;
    /** 放出过几缕魂火（客户端场景要查）。 */
    private static int spirits;
    /** 魂火飞多久、每刻最多转多少。 */
    static final int SPIRIT_LIFE = 240;
    static final double SPIRIT_TURN = Math.toRadians(18);

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

    /** 同伴白梅放的一颗：伤害 {@code damage}，不按难度缩放。 */
    public static FoxfireBolt companion(ServerLevel level, ShiraumeCompanion owner, Vec3 from, Vec3 direction, LivingEntity target, float damage) {
        FoxfireBolt f = launch(level, owner, from, direction, target);
        f.damage = damage;
        return f;
    }

    /** 她倒下时飞回 {@code to} 身边的魂火：不伤人，追上就散。 */
    public static FoxfireBolt spirit(ServerLevel level, ShiraumeCompanion from, Vec3 at, LivingEntity to) {
        FoxfireBolt f = launch(level, from, at, new Vec3(0, 1, 0), to);
        f.spirit = true;
        spirits++;
        return f;
    }

    public static int spirits() {
        return spirits;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    public void tick() {
        super.tick();
        Vec3 v = getDeltaMovement();
        if (level() instanceof ServerLevel level) {
            if (++age > (spirit ? SPIRIT_LIFE : LIFE)) {
                fizzle(level);
                return;
            }
            Entity t = target == null ? null : level.getEntity(target);
            if (t instanceof LivingEntity living && living.isAlive() && age > (spirit ? 3 : 6)) {
                Vec3 center = living.position().add(0, living.getBbHeight() * .5, 0);
                if (spirit && center.distanceTo(position()) < 1) {
                    arrive(level, living);
                    return;
                }
                Vec3 want = center.subtract(position()).normalize();
                Vec3 have = v.lengthSqr() < 1e-6 ? want : v.normalize();
                double angle = Math.acos(Math.max(-1, Math.min(1, have.dot(want))));
                double turn = spirit ? SPIRIT_TURN : TURN;
                Vec3 dir = angle <= turn ? want : slerp(have, want, turn / angle);
                v = dir.scale(SPEED);
                setDeltaMovement(v);
            }
            // 魂火穿过方块和生物，一路飞到主人身边
            HitResult hit = spirit ? null : ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
            if (hit != null && hit.getType() != HitResult.Type.MISS) {
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
        if (spirit) return false;
        if (getOwner() instanceof ShiraumeCompanion companion) return super.canHitEntity(entity) && companion.canHarm(entity) && !(entity instanceof FoxfireBolt);
        return super.canHitEntity(entity) && !(entity instanceof ShiraumeBoss) && !(entity instanceof FoxfireBolt);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (!(level() instanceof ServerLevel level) || !(result.getEntity() instanceof LivingEntity victim)) return;
        Entity owner = getOwner();
        DamageSource probe = level.damageSources().mobProjectile(this, owner instanceof LivingEntity l ? l : null);
        if (ShiraumeStrike.blocks(victim, probe, damage)) {
            level.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 1, 1.2f);
        } else if (owner instanceof ShiraumeCompanion companion) {
            ShiraumeStrike.foxfire(companion, victim, damage);
        } else if (owner instanceof Mob mob) {
            ShiraumeStrike.foxfire(mob, victim, damage * Shiraume.difficultyScale(level));
        }
        fizzle(level);
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (result.getType() == HitResult.Type.BLOCK && level() instanceof ServerLevel level) fizzle(level);
    }

    /** 魂火追上了主人：化作一阵花瓣和一声轻响，回到主人身边的道具里。 */
    private void arrive(ServerLevel level, LivingEntity to) {
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + .3, getZ(), 18, .35, .35, .35, .03);
        level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.2f), getX(), getY() + .3, getZ(), 12, .25, .25, .25, 0);
        level.playSound(null, to.getX(), to.getY(), to.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1, 1.4f);
        discard();
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
