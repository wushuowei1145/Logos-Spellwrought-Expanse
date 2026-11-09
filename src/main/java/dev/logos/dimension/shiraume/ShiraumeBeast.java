package dev.logos.dimension.shiraume;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 九尾妖狐 白梅的狐形（第二阶段，docs/SHIRAUME_BOSS.md 第 6 节）：流火叼在嘴里，冲撞、大跳、尾扫、咬击。
 * 出招前照样双眼红光；攻击都不能弹反（盾能挡，弹刀的窗口里也只算挡下）。八分之一血以下每招之后从尾尖放出狐火飞弹，
 * 动作快三成、更喜欢拉开距离。招式文件 {@code shiraume_beast}，动画名加 {@code fox_} 前缀。
 */
public class ShiraumeBeast extends PathfinderMob implements NpcAnimated, MoveRunner.Actor, ShiraumeBoss {
    public static final Identifier ASSET = LogosDimension.id("shiraume_beast");
    public static final Identifier MOVESET = LogosDimension.id("shiraume_beast");
    public static final String PREFIX = "fox_";
    private static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(ShiraumeBeast.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> ENRAGED = SynchedEntityData.defineId(ShiraumeBeast.class, EntityDataSerializers.BOOLEAN);
    /** 八分之一血以下狂暴。 */
    static final float ENRAGE = 1 / 8f;
    static final float RAGE_SPEED = 1.3f;
    static final int CHARGE_TICKS = 14;
    static final double CHARGE_SPEED = 1.15;
    /** 大跳在空中的刻数（按落地时的预判算初速度）与落地冲击的半径。 */
    static final int POUNCE_AIR = 18;
    static final double POUNCE_RADIUS = 4.2;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private @Nullable MoveRunner runner;
    private @Nullable ServerBossEvent bar;
    private @Nullable Long arena;
    private boolean scripted, resting;
    private float imbalance, fillFrom = -1;
    private long imbalanceAt, nextAttack, chargeStart, airborneAt, stumbleUntil, fillStart;
    private @Nullable Vec3 chargeDirection;
    private final Set<UUID> chargeHits = new HashSet<>();
    private boolean airborne, volleyAfter;
    private @Nullable String lastAttack;
    private String moveFrom = "";
    private @Nullable CombatMove clientMove;

    public ShiraumeBeast(EntityType<? extends ShiraumeBeast> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 300).add(Attributes.MOVEMENT_SPEED, .34)
                .add(Attributes.FOLLOW_RANGE, 64).add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.ARMOR, 6)
                .add(Attributes.STEP_HEIGHT, 1.6).add(Attributes.ATTACK_DAMAGE, 1);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MOVE, "");
        builder.define(ENRAGED, false);
    }

    public boolean enraged() {
        return entityData.get(ENRAGED);
    }

    // ---------------------------------------------------------------- 招式

    private @Nullable MoveRunner runner() {
        if (runner == null && level() instanceof ServerLevel level) {
            Moveset set = Movesets.get(level.getServer(), MOVESET);
            if (set == null) return null;
            runner = new MoveRunner(set, this, level.getGameTime());
            moveStarted(runner);
        }
        return runner;
    }

    @Override
    public void play(String move) {
        MoveRunner r = runner();
        if (r != null && r.moveset().move(move) != null) r.force(move, level().getGameTime());
    }

    @Override
    public String move() {
        return runner == null || runner.cleared() ? "" : runner.move().name();
    }

    private ShiraumeMoves.Extra extra(String move) {
        return level() instanceof ServerLevel level ? ShiraumeMoves.of(level.getServer(), MOVESET, move) : ShiraumeMoves.Extra.NONE;
    }

    private double clipTime(long now) {
        return runner == null ? 0 : runner.move().seconds(now - runner.start(), runner.rate(), runner.offset());
    }

    @Override
    public double attackSpeed() {
        return enraged() ? RAGE_SPEED : 1;
    }

    @Override
    public void moveStarted(MoveRunner r) {
        if (r != runner) return;
        Moveset.Move m = r.move();
        entityData.set(MOVE, PREFIX + m.clip() + "|" + (long) r.start() + "|" + (float) r.rate() + "|" + (m.playback() == Moveset.Playback.LOOP));
        if (!(level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        switch (m.name()) {
            case "charge" -> {
                LivingEntity t = getTarget();
                Vec3 to = t == null ? forward() : t.position().subtract(position());
                chargeDirection = new Vec3(to.x, 0, to.z).normalize();
                chargeStart = now;
                chargeHits.clear();
                level.playSound(null, getX(), getY() + 1, getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.6f, 1.2f);
            }
            case "pounce_air" -> leap(level, now);
            case "stumble" -> stumbleUntil = now + 40;
            default -> { }
        }
    }

    @Override
    public void contact(MoveRunner r) {
        if (!(level() instanceof ServerLevel level)) return;
        Moveset.Move move = r.move();
        String name = move.name();
        float scale = Shiraume.difficultyScale(level);
        switch (name) {
            case "roar" -> {
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(7), e -> e != this && e.isAlive())) {
                    Vec3 away = e.position().subtract(position());
                    away = new Vec3(away.x, 0, away.z).normalize();
                    e.push(away.x * 1.4, .45, away.z * 1.4);
                    e.hurtMarked = true;
                }
                ShiraumeCue.near(level, position(), 64, ShiraumeCue.shake(position(), .5f));
                return;
            }
            case "tail_sweep" -> {
                // 九尾扫过身后与两侧：身前 90° 以外、6.5 格内
                for (LivingEntity e : ShiraumeStrike.targets(this, 6.5, 360)) {
                    Vec3 to = e.position().subtract(position());
                    Vec3 f = forward();
                    double flat = Math.sqrt(to.x * to.x + to.z * to.z);
                    if (flat > 1.5 && (to.x * f.x + to.z * f.z) / flat > Math.cos(Math.toRadians(45))) continue;
                    hit(level, e, (float) (move.damage() * scale), .9);
                }
                return;
            }
            case "tail_flick" -> {
                volley(level, enraged() ? 5 : 3);
                return;
            }
            default -> { }
        }
        ShiraumeMoves.Extra extra = extra(name);
        double reach = extra.reach() > 0 ? extra.reach() : 4.2;
        double cone = move.cone() > 0 ? move.cone() : 120;
        for (LivingEntity e : ShiraumeStrike.targets(this, reach, cone)) hit(level, e, (float) (move.damage() * scale), .6);
        level.playSound(null, getX(), getY() + 1.5, getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.4f, .6f);
    }

    /** 一下打到 {@code e}：不能弹反（格挡照常）；打中再推开一点。 */
    private void hit(ServerLevel level, LivingEntity e, float damage, double knock) {
        ShiraumeStrike.Result result = ShiraumeStrike.strike(this, this, e, damage, false);
        if (result == ShiraumeStrike.Result.HIT) {
            Vec3 away = e.position().subtract(position());
            away = new Vec3(away.x, 0, away.z).normalize();
            e.push(away.x * knock, .25, away.z * knock);
            e.hurtMarked = true;
        } else if (result == ShiraumeStrike.Result.BLOCKED) {
            Vec3 at = e.position().add(0, e.getBbHeight() * .6, 0);
            ShiraumeCue.near(level, at, 64, ShiraumeCue.spark(at, false));
        }
    }

    /** 从尾尖放出几颗狐火，追向目标。 */
    private void volley(ServerLevel level, int count) {
        LivingEntity target = getTarget();
        Vec3 back = forward().scale(-1.6).add(0, 3.2, 0);
        for (int i = 0; i < count; i++) {
            double spread = (i - (count - 1) / 2.0) * .55;
            Vec3 f = forward();
            Vec3 side = new Vec3(-f.z, 0, f.x);
            Vec3 from = position().add(back).add(side.scale(spread * 2));
            Vec3 dir = side.scale(spread).add(0, .8, 0).add(f.scale(.4));
            FoxfireBolt.launch(level, this, from, dir, target);
        }
        level.playSound(null, getX(), getY() + 3, getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1.2f, 1.6f);
    }

    /** 大跳起跳：按目标落地时的预判算初速度；地上提前出一圈落点标记。 */
    private void leap(ServerLevel level, long now) {
        LivingEntity t = getTarget();
        Vec3 land = t == null ? position().add(forward().scale(8)) : t.position().add(t.getDeltaMovement().multiply(POUNCE_AIR * .6, 0, POUNCE_AIR * .6));
        Vec3 d = land.subtract(position());
        double gravity = .08, drag = .98;
        double vy = gravity * POUNCE_AIR / 2 / drag;
        setDeltaMovement(d.x / POUNCE_AIR, vy, d.z / POUNCE_AIR);
        hurtMarked = true;
        airborne = true;
        airborneAt = now;
        ShiraumeCue.near(level, land, 96, ShiraumeCue.mark(land, (float) POUNCE_RADIUS, POUNCE_AIR, now));
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.FOX_SCREECH, SoundSource.HOSTILE, 2f, .7f);
    }

    /** 大跳落地：震地，半径内伤害并掀起，落灰。 */
    private void land(ServerLevel level) {
        airborne = false;
        play("pounce_land");
        float damage = (float) (14 * Shiraume.difficultyScale(level));
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(POUNCE_RADIUS, 1, POUNCE_RADIUS),
                e -> e != this && e.isAlive() && !(e instanceof ShiraumeBoss) && e.distanceToSqr(this) < (POUNCE_RADIUS + 1.3) * (POUNCE_RADIUS + 1.3))) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            ShiraumeStrike.Result r = ShiraumeStrike.strike(this, this, e, damage, false);
            if (r == ShiraumeStrike.Result.HIT) {
                e.push(0, .7, 0);
                e.hurtMarked = true;
            }
        }
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 2.5f, .7f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE_TILES.defaultBlockState()), getX(), getY() + .2, getZ(), 120, 3, .2, 3, .3);
        ShiraumeCue.near(level, position(), 64, ShiraumeCue.shake(position(), .55f));
    }

    // ---------------------------------------------------------------- 受伤

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && !scripted && !resting) return super.hurtServer(level, source, amount);
        if (scripted || resting || isDeadOrDying()) return false;
        if (source.getEntity() instanceof LivingEntity attacker && attacker != this) ShiraumeFight.joined(level, this, attacker);
        if (source.is(DamageTypeTags.IS_EXPLOSION)) amount *= .5f;
        if (move().equals("stumble")) amount *= Shiraume.PANT_DAMAGE;
        return super.hurtServer(level, source, amount);
    }

    @Override
    protected void actuallyHurt(ServerLevel level, DamageSource source, float amount) {
        float before = getHealth();
        super.actuallyHurt(level, source, amount);
        float dealt = before - getHealth();
        if (dealt > 0) {
            imbalance = Math.min(150, imbalance + dealt * Shiraume.IMBALANCE_PER_DAMAGE);
            imbalanceAt = level.getGameTime();
        }
        if (!enraged() && getHealth() <= getMaxHealth() * ENRAGE && getHealth() > 0) {
            entityData.set(ENRAGED, true);
            level.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.FOX_SCREECH, SoundSource.HOSTILE, 4f, .5f);
            ShiraumeCue.near(level, position(), 96, ShiraumeCue.aberration(.5f));
        }
        if (bar != null) bar.setProgress(Mth.clamp(getHealth() / getMaxHealth(), 0, 1));
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (level() instanceof ServerLevel level) {
            if (bar != null) bar.removeAllPlayers();
            ShiraumeFight.defeated(level, this);
        }
    }

    /** 倒下后由场地让它化作花瓣消散（{@link ShiraumeFight}）；场地没接手时十秒后自己消失。 */
    @Override
    protected void tickDeath() {
        deathTime++;
        if (deathTime >= 200 && !level().isClientSide() && !isRemoved()) remove(RemovalReason.KILLED);
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    /** 九条尾巴把飞来的东西拨开（只拨开，不弹回去）：没在出招、过场、踉跄时，一半的机会。 */
    @Override
    public ProjectileDeflection deflection(Projectile projectile) {
        if (level().isClientSide() || scripted || resting || !move().equals("idle_combat") || getRandom().nextFloat() < .5f) return ProjectileDeflection.NONE;
        if (level() instanceof ServerLevel level) {
            Vec3 at = projectile.position();
            level.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 1, 1.5f);
            ShiraumeCue.near(level, at, 64, ShiraumeCue.spark(at, false));
            ShiraumeDeflect.claim(projectile, this);
        }
        return (p, by, random) -> {
            Vec3 v = p.getDeltaMovement();
            Vec3 side = new Vec3(-v.z, 0, v.x).normalize().scale(random.nextBoolean() ? 1 : -1);
            p.setDeltaMovement(side.add(0, .4, 0).normalize().scale(Math.max(.4, v.length() * .5)));
            p.needsSync = true;
        };
    }

    // ---------------------------------------------------------------- 每刻

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide() || isDeadOrDying()) return;
        ServerLevel level = (ServerLevel) level();
        long now = level.getGameTime();
        MoveRunner r = runner();
        if (r != null) {
            double before = clipTime(now - 1);
            String name = r.move().name();
            r.tick(now);
            if (r.move().name().equals(name)) motion(level, name, before, clipTime(now), now);
        }
        if (now - imbalanceAt > Shiraume.IMBALANCE_GRACE) imbalance = Math.max(0, imbalance - Shiraume.IMBALANCE_DECAY / 20f);
        if (resting && tickCount % 20 == 0) heal(getMaxHealth() * .02f);
        if (bar != null && fillFrom >= 0) {
            float k = Mth.clamp((now - fillStart) / 40f, 0, 1);
            bar.setProgress(Mth.lerp(k, fillFrom, Mth.clamp(getHealth() / getMaxHealth(), 0, 1)));
            if (k >= 1) fillFrom = -1;
        }
        if (tickCount % 20 == 0) updateBar(level);
    }

    private void motion(ServerLevel level, String name, double before, double after, long now) {
        ShiraumeMoves.Extra extra = extra(name);
        if (extra.eyeFlash() >= 0 && before < extra.eyeFlash() && after >= extra.eyeFlash()) {
            ShiraumeCue.near(level, position(), 96, ShiraumeCue.eyes(this));
            level.playSound(null, getX(), getEyeY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 2.0f, 1.5f);
        }
        if (extra.leap() != null && before < extra.leap()[0] && after >= extra.leap()[0]) {
            Vec3 f = forward();
            double[] l = extra.leap();
            setDeltaMovement(-f.x * l[1] / 10, l[2], -f.z * l[1] / 10);
            hurtMarked = true;
        }
        if (name.equals("charge") && chargeDirection != null) {
            Vec3 before0 = position();
            Vec3 step = chargeDirection.scale(CHARGE_SPEED * (enraged() ? 1.15 : 1));
            move(MoverType.SELF, step);
            float damage = (float) (12 * Shiraume.difficultyScale(level));
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(.6), e -> e != this && e.isAlive() && !(e instanceof ShiraumeBoss))) {
                if (e instanceof Player p && (p.isCreative() || p.isSpectator()) || !chargeHits.add(e.getUUID())) continue;
                ShiraumeStrike.Result res = ShiraumeStrike.strike(this, this, e, damage, false);
                if (res == ShiraumeStrike.Result.HIT) {
                    e.push(chargeDirection.x * 1.6, .5, chargeDirection.z * 1.6);
                    e.hurtMarked = true;
                }
            }
            boolean stuck = position().distanceToSqr(before0) < step.lengthSqr() * .2;
            if (now - chargeStart >= CHARGE_TICKS || stuck) {
                if (stuck) {
                    level.playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.2f, .5f);
                    ShiraumeCue.near(level, position(), 64, ShiraumeCue.shake(position(), .4f));
                }
                play("charge_stop");
            }
        }
        if (name.equals("pounce_air") && airborne && now - airborneAt > 4 && onGround()) land(level);
        if (name.equals("pounce_air") && now - airborneAt > POUNCE_AIR * 3) {
            airborne = false;
            play("idle_combat");
        }
    }

    Vec3 forward() {
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
    }

    void face(@Nullable LivingEntity target, float rate) {
        if (target == null) return;
        double dx = target.getX() - getX(), dz = target.getZ() - getZ();
        float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
        float yaw = Mth.approachDegrees(getYRot(), want, rate);
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
    }

    // ---------------------------------------------------------------- 决策

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (scripted || resting || runner == null || isDeadOrDying()) {
            getNavigation().stop();
            return;
        }
        long now = level.getGameTime();
        LivingEntity target = ShiraumeFight.pickTarget(level, this);
        if (target != getTarget()) setTarget(target);
        String m = move();
        if (m.equals("stumble")) {
            if (now >= stumbleUntil) {
                imbalance = 0;
                play("idle_combat");
            }
            return;
        }
        if (!m.equals("idle_combat")) {
            ShiraumeMoves.Extra extra = extra(m);
            double t = clipTime(now);
            if (extra.eyeFlash() >= 0 && t < extra.eyeFlash()) face(target, 9);
            if (!m.equals("charge") && !m.equals("pounce_air")) getNavigation().stop();
            return;
        }
        // 狂暴：每招之后放一轮狐火
        if (volleyAfter && target != null) {
            volleyAfter = false;
            play("tail_flick");
            return;
        }
        if (target == null) {
            Vec3 home = ShiraumeFight.home(level, this);
            if (home != null && position().distanceToSqr(home) > 25) getNavigation().moveTo(home.x, home.y, home.z, .9);
            return;
        }
        if (imbalance >= Shiraume.IMBALANCE_MAX) {
            getNavigation().stop();
            play("stumble");
            return;
        }
        getLookControl().setLookAt(target, 30, 30);
        double d = Math.sqrt(Math.pow(target.getX() - getX(), 2) + Math.pow(target.getZ() - getZ(), 2));
        boolean rage = enraged();
        if (now < nextAttack) {
            if (rage && d < 7) {
                // 狂暴时更喜欢拉开距离
                Vec3 away = position().subtract(target.position()).normalize().scale(8).add(position());
                getNavigation().moveTo(away.x, away.y, away.z, 1.2);
            } else if (d > 5) getNavigation().moveTo(target, rage ? 1.3 : 1.1);
            else face(target, 12);
            return;
        }
        String attack;
        if (rage && d < 6 && getRandom().nextFloat() < .6f) attack = "back_leap";
        else if (d > 12) attack = getRandom().nextBoolean() ? "charge_windup" : "pounce_crouch";
        else if (d > 6) attack = getRandom().nextFloat() < .45f ? "pounce_crouch" : getRandom().nextFloat() < .7f ? "charge_windup" : "";
        else {
            long crowd = level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(4), p -> !p.isSpectator() && !p.isCreative()).size();
            if (crowd >= 2 && getRandom().nextFloat() < .35f) attack = "roar";
            else if (behind(target)) attack = "tail_sweep";
            else attack = pick(new String[] {"bite_slash", "paw_swipe", "tail_sweep", "bite_slash"});
        }
        if (attack.isEmpty()) {
            getNavigation().moveTo(target, 1.1);
            return;
        }
        getNavigation().stop();
        lastAttack = attack;
        play(attack);
        nextAttack = now + (rage ? 4 : 10) + getRandom().nextInt(rage ? 10 : 18);
        if (rage && !attack.equals("roar")) volleyAfter = true;
    }

    private boolean behind(LivingEntity target) {
        Vec3 to = target.position().subtract(position());
        Vec3 f = forward();
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        return flat > 1 && (to.x * f.x + to.z * f.z) / flat < -.3;
    }

    private String pick(String[] moves) {
        String m;
        int guard = 0;
        do m = moves[getRandom().nextInt(moves.length)]; while (m.equals(lastAttack) && guard++ < 4);
        return m;
    }

    // ---------------------------------------------------------------- 场地

    @Override public boolean takeParried() { return false; }
    @Override public @Nullable Long arena() { return arena; }
    @Override public void setArena(@Nullable Long arena) { this.arena = arena; }
    @Override public boolean scripted() { return scripted; }

    @Override
    public void setScripted(boolean scripted) {
        this.scripted = scripted;
        if (!scripted && !move().startsWith("reveal") && runner != null) return;
        if (!scripted) play("idle_combat");
        else getNavigation().stop();
    }

    @Override public boolean resting() { return resting; }

    @Override
    public void setResting(boolean resting) {
        if (this.resting == resting) return;
        this.resting = resting;
        play(resting ? "rest" : "idle_combat");
        showBar(!resting, 1);
    }

    @Override
    public void showBar(boolean shown, float fill) {
        if (bar == null) {
            bar = new ServerBossEvent(UUID.randomUUID(), Component.translatable("entity.logos_dimension.shiraume.bar"),
                    BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
        }
        bar.setVisible(shown);
        if (fill < 1) {
            fillFrom = fill;
            fillStart = level().getGameTime();
            bar.setProgress(fill);
        } else bar.setProgress(Mth.clamp(getHealth() / getMaxHealth(), 0, 1));
        if (shown && level() instanceof ServerLevel level) updateBar(level);
    }

    private void updateBar(ServerLevel level) {
        if (bar == null) return;
        for (ServerPlayer p : List.copyOf(bar.getPlayers())) {
            if (!p.isAlive() || p.level() != level || p.distanceToSqr(this) > 64 * 64) bar.removePlayer(p);
        }
        if (!bar.isVisible()) return;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(this) < 48 * 48 && (arena == null || ShiraumeFight.inArena(level, arena, p.blockPosition()))) bar.addPlayer(p);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (bar != null) bar.removeAllPlayers();
        super.remove(reason);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (arena != null) output.putLong("Arena", arena);
        output.putBoolean("Resting", resting);
        output.putBoolean("Enraged", enraged());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        arena = input.getLong("Arena").orElse(null);
        resting = input.getBooleanOr("Resting", false);
        entityData.set(ENRAGED, input.getBooleanOr("Enraged", false));
    }

    // ---------------------------------------------------------------- 动画

    @Override
    public Identifier npcAnimationAsset() {
        return ASSET;
    }

    @Override
    public CombatMove npcCombatMove() {
        String text = entityData.get(MOVE);
        if (!text.equals(moveFrom)) {
            moveFrom = text;
            clientMove = null;
            String[] p = text.split("\\|");
            if (p.length == 4) {
                try {
                    clientMove = new CombatMove(p[0], Long.parseLong(p[1]), Float.parseFloat(p[2]), Boolean.parseBoolean(p[3]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return isDeadOrDying() ? null : clientMove;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        NpcAnimations.registerControllers(this, controllers);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
