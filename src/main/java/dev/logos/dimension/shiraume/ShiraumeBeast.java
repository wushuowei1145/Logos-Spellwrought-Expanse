package dev.logos.dimension.shiraume;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.combat.Parry;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import dev.logos.runtime.Effects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 九尾妖狐 白梅的狐形（第二阶段，docs/SHIRAUME_BOSS.md 第 6 节）：流火叼在嘴里，冲撞、大跳、尾扫、咬击，贴身时先往一侧跳闪再出招。
 * 出招前照样双眼红光。冲撞、大跳、尾扫不能弹反（盾能挡，弹刀的窗口里也只算挡下）；咬击甩刀和前爪能弹反（被弹开仰头一晃），
 * 只格挡、没弹反就破盾。八分之一血以下每招之后从尾尖放出狐火飞弹，动作快三成、更喜欢拉开距离。
 * 大跳、跳闪按弧线走（{@link #followArc}），一定落在起跳时定下的地方。招式文件 {@code shiraume_beast}，动画名加 {@code fox_} 前缀。
 */
public class ShiraumeBeast extends PathfinderMob implements NpcAnimated, MoveRunner.Actor, Parry.Reactive, ShiraumeBoss {
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
    /** 大跳在空中的刻数、弧线最高处比起落点高几格（头顶不够就压低）、落地冲击的半径。 */
    static final int POUNCE_AIR = 18;
    static final double POUNCE_HEIGHT = 5, POUNCE_RADIUS = 4.2;
    /** 跳闪：在空中的刻数、往旁边跳多远、多高，跳过之后隔多少刻才会再跳。 */
    static final int HOP_TICKS = 8, HOP_AGAIN = 40;
    /**
     * 狂暴时大跳落地放出的狐火冲击：一圈贴地的淡粉狐火从落点往外扩散，起初很慢、越来越快（半径见 {@link #ringRadius}），
     * 到 {@link #RING_MAX} 格散掉。脚离地 {@link #RING_HEIGHT} 格以上（跳起来）就躲过，盾挡不住（狐火不看盾）。
     */
    public static final double RING_START = 1.5, RING_SPEED = .02, RING_ACCEL = .012, RING_MAX = 24, RING_HEIGHT = .9, RING_HALF_WIDTH = .45;
    static final float RING_DAMAGE = 8;
    static final double HOP_DISTANCE = 4.5, HOP_HEIGHT = 1.4;
    /** 斩法的扑击：每刻最多冲几格，最多冲几刻（够不着也咬下去）。 */
    static final double RUSH_SPEED = 1.8;
    static final int RUSH_TICKS = 16;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private @Nullable MoveRunner runner;
    private @Nullable ServerBossEvent bar;
    private @Nullable Long arena;
    private boolean scripted, resting;
    private float imbalance, fillFrom = -1;
    private long imbalanceAt, nextAttack, chargeStart, airborneAt, stumbleUntil, fillStart;
    private @Nullable Vec3 chargeDirection;
    private final Set<UUID> chargeHits = new HashSet<>();
    private boolean airborne, volleyAfter, parried;
    private @Nullable String lastAttack;
    /** 对付术师（{@link ShiraumeCounter}）：变身时从人形接过来。 */
    final ShiraumeCounter counter = new ShiraumeCounter();
    /** 读档时血条是亮着的：下一刻重新亮起（血条不随实体存下来）。 */
    private boolean barOnLoad;

    /** 对付术师的那一份（盯上了谁、冷却），测试与调试读。 */
    public ShiraumeCounter counter() {
        return counter;
    }
    // 按弧线走（大跳、跳闪）：起点、落点、起跳的刻、在空中几刻、多高；跳闪定好的落点
    private @Nullable Vec3 arcFrom, arcTo, hopTo;
    private long arcStart, hopAgain, rushStart;
    /** 正在扩散的狐火冲击：圆心、放出的刻、上一刻的半径、已经打到的人。 */
    private record Ring(Vec3 center, long start, double[] last, Set<UUID> hit) {}
    private final List<Ring> rings = new java.util.ArrayList<>();
    private int arcTicks;
    private double arcHeight;
    private String moveFrom = "";
    private @Nullable CombatMove clientMove;

    public ShiraumeBeast(EntityType<? extends ShiraumeBeast> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 500).add(Attributes.MOVEMENT_SPEED, .34)
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
            case "hop_left", "hop_right" -> {
                if (hopTo != null) startArc(hopTo, (int) Math.round(HOP_TICKS / attackSpeed()), Math.max(.6, Math.min(HOP_HEIGHT, headroom(level, position(), hopTo))));
                hopTo = null;
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.FOX_TELEPORT, SoundSource.HOSTILE, 1.2f, .8f);
            }
            case "kitsunebi_rush" -> {
                rushStart = now;
                ShiraumeCue.near(level, position(), 96, ShiraumeCue.eyes(this));
                level.playSound(null, getX(), getY() + 1, getZ(), SoundEvents.FOX_SCREECH, SoundSource.HOSTILE, 2f, 1.3f);
                speed(counter.target(), RUSH_TICKS);
            }
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
                int purge = extra(name).purge();
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(7), e -> e != this && e.isAlive())) {
                    Vec3 away = e.position().subtract(position());
                    away = new Vec3(away.x, 0, away.z).normalize();
                    e.push(away.x * 1.4, .45, away.z * 1.4);
                    e.hurtMarked = true;
                    // 一声长嚎震散身上的增益
                    if (purge > 0 && e instanceof Player p && !p.isCreative() && !p.isSpectator()) ShiraumeStrike.purge(level, p, purge);
                }
                ShiraumeCue.near(level, position(), 64, ShiraumeCue.shake(position(), .5f));
                return;
            }
            case "kitsunebi_bite" -> {
                counter.cut(level, this, 5.5);
                level.playSound(null, getX(), getY() + 1.5, getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.6f, .55f);
                return;
            }
            case "tail_sweep" -> {
                // 九尾扫过身后与两侧：身前 90° 以外、6.5 格内
                for (LivingEntity e : ShiraumeStrike.targets(this, 6.5, 360)) {
                    Vec3 to = e.position().subtract(position());
                    Vec3 f = forward();
                    double flat = Math.sqrt(to.x * to.x + to.z * to.z);
                    if (flat > 1.5 && (to.x * f.x + to.z * f.z) / flat > Math.cos(Math.toRadians(45))) continue;
                    hit(level, e, (float) (move.damage() * scale), .9, extra(name));
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
        for (LivingEntity e : ShiraumeStrike.targets(this, reach, cone)) {
            hit(level, e, (float) (move.damage() * scale), .6, extra);
            // 被弹开了：这一下后面的人不再结算
            if (r.move() != move) break;
        }
        level.playSound(null, getX(), getY() + 1.5, getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.4f, .6f);
    }

    /**
     * 一下打到 {@code e}：招式文件写了能弹反的（咬击甩刀、前爪）照常能弹反，格挡下来却没弹反就破盾；其余只能挡。
     * 打中再推开一点。
     */
    private void hit(ServerLevel level, LivingEntity e, float damage, double knock, ShiraumeMoves.Extra extra) {
        boolean parryable = extra.parry() != ShiraumeMoves.ParryKind.NONE;
        ShiraumeStrike.Result result = ShiraumeStrike.strike(this, this, e, damage, parryable, extra.guardBreak(), extra.purge());
        if (result == ShiraumeStrike.Result.HIT) {
            Vec3 away = e.position().subtract(position());
            away = new Vec3(away.x, 0, away.z).normalize();
            e.push(away.x * knock, .25, away.z * knock);
            e.hurtMarked = true;
        } else if (result == ShiraumeStrike.Result.BLOCKED || result == ShiraumeStrike.Result.PARRIED) {
            Vec3 at = e.position().add(0, e.getBbHeight() * .6, 0);
            ShiraumeCue.near(level, at, 64, ShiraumeCue.spark(at, result == ShiraumeStrike.Result.PARRIED));
        }
    }

    // ---------------------------------------------------------------- 被弹反

    /** 能弹反的一下被 {@code by} 弹开：仰头一晃（{@code recoil}），失衡涨一截，攒满了照样踉跄。 */
    @Override
    public boolean parried(LivingEntity by) {
        if (runner == null || scripted || resting) return false;
        ShiraumeMoves.Extra extra = extra(move());
        if (extra.parry() == ShiraumeMoves.ParryKind.NONE) return false;
        imbalance = Math.min(Shiraume.IMBALANCE_MAX * 1.5f, imbalance + Shiraume.IMBALANCE_SINGLE);
        imbalanceAt = level().getGameTime();
        play(extra.parried() != null ? extra.parried() : "recoil");
        getNavigation().stop();
        parried = true;
        level().playSound(null, getX(), getY() + 2, getZ(), SoundEvents.FOX_HURT, SoundSource.HOSTILE, 1.6f, .6f);
        return true;
    }

    @Override
    public boolean staggered() {
        String m = move();
        return m.equals("recoil") || m.equals("stumble");
    }

    /** 冲过去时的速度线：冲向的那个人看得最清楚，旁边的人淡一些。 */
    private void speed(@Nullable LivingEntity to, int ticks) {
        if (!(level() instanceof ServerLevel level)) return;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(this) > 48 * 48) continue;
            ShiraumeCue.send(p, ShiraumeCue.speed(this, p == to ? 1f : .35f, ticks));
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

    /**
     * 大跳起跳：落点按目标落地时的预判定好（贴到地面上；在场地外就落在目标脚下），地上提前出一圈落点标记，
     * 之后每刻按弧线把她放到该在的地方，所以一定落在圈里。以前是给一个初速度让她自己飞，空中的阻力让她只飞到半路。
     */
    private void leap(ServerLevel level, long now) {
        LivingEntity t = getTarget();
        Vec3 aim = t == null ? position().add(forward().scale(8)) : t.position().add(t.getDeltaMovement().multiply(POUNCE_AIR * .6, 0, POUNCE_AIR * .6));
        Vec3 land = ground(level, aim);
        if (land == null || arena != null && !ShiraumeFight.inArena(level, arena, BlockPos.containing(land))) land = t == null ? null : ground(level, t.position());
        if (land == null) land = t == null ? aim : t.position();
        int air = (int) Math.round(POUNCE_AIR / attackSpeed());
        double dx = land.x - getX(), dz = land.z - getZ();
        if (dx * dx + dz * dz > .25) {
            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
            setYRot(yaw);
            setYBodyRot(yaw);
            setYHeadRot(yaw);
        }
        startArc(land, air, Math.max(1.5, Math.min(POUNCE_HEIGHT, headroom(level, position(), land))));
        airborne = true;
        airborneAt = now;
        ShiraumeCue.near(level, land, 96, ShiraumeCue.mark(land, (float) POUNCE_RADIUS, air, now));
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.FOX_SCREECH, SoundSource.HOSTILE, 2f, .7f);
    }

    /**
     * 跳闪的落点：目标的左边或右边（以她看向目标为准）{@link #HOP_DISTANCE} 格、稍稍退后，要在场地里、有地面、
     * 放得下她、中间没有墙；不行返回 null。
     */
    private @Nullable Vec3 hopSpot(ServerLevel level, LivingEntity t, boolean left) {
        Vec3 f = new Vec3(t.getX() - getX(), 0, t.getZ() - getZ());
        f = f.lengthSqr() < 1e-4 ? forward() : f.normalize();
        Vec3 side = new Vec3(f.z, 0, -f.x).scale(left ? 1 : -1);
        Vec3 land = ground(level, position().add(side.scale(HOP_DISTANCE)).add(f.scale(-.8)));
        if (land == null || Math.abs(land.y - getY()) > 2.5) return null;
        if (arena != null && !ShiraumeFight.inArena(level, arena, BlockPos.containing(land))) return null;
        if (!level.noCollision(this, getDimensions(getPose()).makeBoundingBox(land))) return null;
        Vec3 from = position().add(0, 1.2, 0), to = land.add(0, 1.2, 0);
        if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() != HitResult.Type.MISS) return null;
        return land;
    }

    /** {@code at} 这一列上能站的地面（从上面三格往下找十格），找不到返回 null。 */
    private static @Nullable Vec3 ground(ServerLevel level, Vec3 at) {
        BlockPos.MutableBlockPos m = BlockPos.containing(at.x, at.y + 3, at.z).mutable();
        for (int i = 0; i < 10; i++) {
            BlockPos below = m.below();
            var under = level.getBlockState(below).getCollisionShape(level, below);
            if (level.getBlockState(m).getCollisionShape(level, m).isEmpty() && !under.isEmpty())
                return new Vec3(at.x, below.getY() + under.max(Direction.Axis.Y), at.z);
            m.move(0, -1, 0);
        }
        return null;
    }

    /** 弧线最高处（起落点连线的中点）头顶还剩几格：撞上顶就把弧线压低。 */
    private double headroom(ServerLevel level, Vec3 from, Vec3 to) {
        Vec3 mid = from.lerp(to, .5);
        double base = Math.max(from.y, to.y);
        BlockPos.MutableBlockPos m = BlockPos.containing(mid.x, base + getBbHeight(), mid.z).mutable();
        for (int i = 0; i < 12; i++) {
            if (!level.getBlockState(m).getCollisionShape(level, m).isEmpty()) return m.getY() - base - getBbHeight() - .3;
            m.move(0, 1, 0);
        }
        return 12;
    }

    private void startArc(Vec3 to, int ticks, double height) {
        arcFrom = position();
        arcTo = to;
        arcTicks = Math.max(1, ticks);
        arcHeight = height;
        arcStart = level().getGameTime();
        setNoGravity(true);
        setDeltaMovement(Vec3.ZERO);
        getNavigation().stop();
    }

    /** 每刻把她放到弧线上该在的地方（撞到墙就停在墙前）；走完了返回真。 */
    private boolean followArc(long now) {
        if (arcFrom == null || arcTo == null) return true;
        double s = Math.min(1, (now - arcStart) / (double) arcTicks);
        Vec3 want = arcFrom.lerp(arcTo, s).add(0, arcHeight * 4 * s * (1 - s), 0);
        move(MoverType.SELF, want.subtract(position()));
        setDeltaMovement(Vec3.ZERO);
        if (s < 1) return false;
        arcFrom = arcTo = null;
        setNoGravity(false);
        return true;
    }

    /** 过场、倒下时停下弧线，交还重力。 */
    private void cancelArc() {
        if (arcFrom == null && !airborne && !isNoGravity()) return;
        arcFrom = arcTo = null;
        airborne = false;
        setNoGravity(false);
    }

    /** 大跳落地：震地，半径内伤害并掀起，落灰。 */
    /**
     * 大跳落地：震地，半径内伤害并掀起，落灰。能弹反（招式文件 {@code pounce_land}：被弹开仰头一晃），
     * 格挡下来却没弹反就破盾。狂暴时还从落点放出一圈狐火冲击（{@link #ringRadius}）。
     */
    private void land(ServerLevel level) {
        airborne = false;
        play("pounce_land");
        MoveRunner r = runner;
        Moveset.Move move = r == null ? null : r.move();
        ShiraumeMoves.Extra extra = extra("pounce_land");
        boolean parryable = extra.parry() != ShiraumeMoves.ParryKind.NONE;
        float damage = (float) (14 * Shiraume.difficultyScale(level));
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(POUNCE_RADIUS, 1, POUNCE_RADIUS),
                e -> e != this && e.isAlive() && !(e instanceof ShiraumeBoss) && e.distanceToSqr(this) < (POUNCE_RADIUS + 1.3) * (POUNCE_RADIUS + 1.3))) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            ShiraumeStrike.Result result = ShiraumeStrike.strike(this, this, e, damage, parryable, extra.guardBreak(), extra.purge());
            if (result == ShiraumeStrike.Result.HIT) {
                e.push(0, .7, 0);
                e.hurtMarked = true;
            } else if (result == ShiraumeStrike.Result.BLOCKED || result == ShiraumeStrike.Result.PARRIED) {
                Vec3 at = e.position().add(0, e.getBbHeight() * .6, 0);
                ShiraumeCue.near(level, at, 64, ShiraumeCue.spark(at, result == ShiraumeStrike.Result.PARRIED));
            }
            // 被弹开了：后面的人不再结算
            if (r != null && r.move() != move) break;
        }
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.HOSTILE, 2.5f, .7f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE_TILES.defaultBlockState()), getX(), getY() + .2, getZ(), 120, 3, .2, 3, .3);
        ShiraumeCue.near(level, position(), 64, ShiraumeCue.shake(position(), .55f));
        if (enraged()) {
            long now = level.getGameTime();
            rings.add(new Ring(position(), now, new double[] {RING_START}, new java.util.HashSet<>()));
            ShiraumeCue.near(level, position(), 96, ShiraumeCue.ring(position(), now));
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 2.5f, .5f);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 2f, .6f);
        }
    }

    /** 狐火冲击放出 {@code age} 刻后的半径：起初每刻只走 {@link #RING_SPEED} 格，每刻再快 {@link #RING_ACCEL}。客户端照这个画。 */
    public static double ringRadius(double age) {
        double t = Math.max(0, age);
        return RING_START + RING_SPEED * t + RING_ACCEL * t * t / 2;
    }

    /** 冲击这一刻扫过的人：在上一刻与这一刻的半径之间、脚还贴着地，各打一次（狐火，淡粉），往外推开。 */
    private void tickRings(ServerLevel level, long now) {
        if (rings.isEmpty()) return;
        float damage = (float) (RING_DAMAGE * Shiraume.difficultyScale(level));
        for (var it = rings.iterator(); it.hasNext(); ) {
            Ring ring = it.next();
            double radius = ringRadius(now - ring.start);
            double inner = ring.last[0] - RING_HALF_WIDTH, outer = radius + RING_HALF_WIDTH;
            Vec3 c = ring.center;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(c.x - outer, c.y - 2, c.z - outer,
                    c.x + outer, c.y + 4, c.z + outer), e -> e != this && e.isAlive() && !(e instanceof ShiraumeBoss))) {
                if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
                double dx = e.getX() - c.x, dz = e.getZ() - c.z, d = Math.sqrt(dx * dx + dz * dz);
                if (d < inner || d > outer || e.getY() - c.y >= RING_HEIGHT || !ring.hit.add(e.getUUID())) continue;
                e.invulnerableTime = 0;
                dev.logos.dimension.foxfire.Foxfire.deal(this, e, damage, FoxfireFlame.SHIRAUME);
                Vec3 away = d < 1e-3 ? forward() : new Vec3(dx / d, 0, dz / d);
                e.push(away.x * .9, .35, away.z * .9);
                e.hurtMarked = true;
            }
            ring.last[0] = radius;
            if (radius > RING_MAX) it.remove();
        }
    }

    /** 测试用：还在扩散的狐火冲击有几圈。 */
    public int rings() {
        return rings.size();
    }

    // ---------------------------------------------------------------- 受伤

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && !scripted && !resting) return super.hurtServer(level, source, amount);
        if (scripted || resting || isDeadOrDying()) return false;
        if (source.getEntity() instanceof LivingEntity attacker && attacker != this) {
            ShiraumeFight.joined(level, this, attacker);
            // 术式打来的：记在这个人头上，打够了就盯上他
            if (Effects.inSpell() && attacker instanceof ServerPlayer caster) counter.spellHit(level, this, caster, level.getGameTime());
        }
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
        cancelArc();
        super.die(source);
        if (level() instanceof ServerLevel level) {
            if (bar != null) bar.removeAllPlayers();
            counter.release(level);
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

    /** 不再被这个玩家看着（走远、死后在别处重生、换了维度、区块卸载）：从血条里拿掉，免得客户端留着一条又一条。 */
    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        if (bar != null) bar.removePlayer(player);
    }

    /** 栓绳拴不住她。 */
    @Override
    public boolean canBeLeashed() {
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
        // 大跳、跳闪按弧线走；中途换了招也照样走完
        if (arcFrom != null && followArc(now) && airborne) land(level);
        tickRings(level, now);
        if (now - imbalanceAt > Shiraume.IMBALANCE_GRACE) imbalance = Math.max(0, imbalance - Shiraume.IMBALANCE_DECAY / 20f);
        if (resting && tickCount % 20 == 0) heal(getMaxHealth() * .02f);
        if (bar != null && fillFrom >= 0) {
            float k = Mth.clamp((now - fillStart) / 40f, 0, 1);
            bar.setProgress(Mth.lerp(k, fillFrom, Mth.clamp(getHealth() / getMaxHealth(), 0, 1)));
            if (k >= 1) fillFrom = -1;
        }
        if (tickCount % 20 == 0) updateBar(level);
        counter.refresh(level, now);
        // 存档时血条亮着（区块卸载后又载回来）：载回来重新亮起，只有这一条
        if (barOnLoad) {
            barOnLoad = false;
            showBar(true, 1);
        }
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
        if (name.equals("pounce_air") && now - airborneAt > POUNCE_AIR * 3) {
            cancelArc();
            play("idle_combat");
        }
        if (name.equals("kitsunebi_rush")) rush(level, now);
    }

    /** 斩法的扑击：直扑盯上的术师，到了跟前（或冲够了）就咬下去（{@link ShiraumeCounter#cut}）。 */
    private void rush(ServerLevel level, long now) {
        ServerPlayer p = counter.target();
        if (p == null || !p.isAlive() || p.level() != level) {
            play("idle_combat");
            return;
        }
        Vec3 to = p.position().subtract(position());
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        face(p, 40);
        if (flat > 3.2) move(MoverType.SELF, new Vec3(to.x, 0, to.z).scale(Math.min(RUSH_SPEED, flat - 3) / flat));
        level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.8f), getX(), getY() + 1.6, getZ(), 6, .9, .8, .9, 0);
        if (flat <= 3.4 || now - rushStart >= RUSH_TICKS) play("kitsunebi_bite");
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
            // 跳闪时一直看着目标，落地就能出招
            if (m.startsWith("hop_")) face(target, 30);
            if (!m.equals("charge")) getNavigation().stop();
            return;
        }
        // 盯上的术师在蓄力：扑过去斩
        ServerPlayer caster = counter.charging(level, this, now);
        if (caster != null) {
            getNavigation().stop();
            face(caster, 180);
            play("kitsunebi_rush");
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
                getNavigation().moveTo(away.x, away.y, away.z, 1.35);
            } else if (d > 5) getNavigation().moveTo(target, rage ? 1.45 : 1.25);
            else face(target, 16);
            return;
        }
        // 贴身时有时先往一侧跳闪，落地再出招，不总是正面硬拼
        if (d < 7 && now >= hopAgain && getRandom().nextFloat() < (rage ? .45f : .3f)) {
            hopAgain = now + HOP_AGAIN + getRandom().nextInt(24);
            boolean left = getRandom().nextBoolean();
            Vec3 spot = hopSpot(level, target, left);
            if (spot == null) {
                left = !left;
                spot = hopSpot(level, target, left);
            }
            if (spot != null) {
                hopTo = spot;
                getNavigation().stop();
                play(left ? "hop_left" : "hop_right");
                nextAttack = now;
                return;
            }
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
        nextAttack = now + (rage ? 3 : 8) + getRandom().nextInt(rage ? 9 : 14);
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

    @Override
    public boolean takeParried() {
        boolean was = parried;
        parried = false;
        return was;
    }

    @Override public @Nullable Long arena() { return arena; }
    @Override public void setArena(@Nullable Long arena) { this.arena = arena; }
    @Override public boolean scripted() { return scripted; }

    @Override
    public void setScripted(boolean scripted) {
        this.scripted = scripted;
        if (scripted) cancelArc();
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
        output.putBoolean("BarShown", bar != null && bar.isVisible());
        output.putBoolean("Enraged", enraged());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        arena = input.getLong("Arena").orElse(null);
        resting = input.getBooleanOr("Resting", false);
        barOnLoad = input.getBooleanOr("BarShown", false);
        entityData.set(ENRAGED, input.getBooleanOr("Enraged", false));
        // 存档时正在半空的弧线上：读回来交还重力
        setNoGravity(false);
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
