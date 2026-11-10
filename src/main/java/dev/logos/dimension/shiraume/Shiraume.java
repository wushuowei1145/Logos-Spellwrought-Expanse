package dev.logos.dimension.shiraume;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.combat.Parry;
import dev.logos.dimension.execution.ExecutionShot;
import dev.logos.dimension.execution.ExecutionShots;
import dev.logos.dimension.execution.ExecutionTimeline;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.runtime.Effects;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.Difficulty;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 九尾妖狐 白梅的人形（第一阶段）：手持流火，和玩家拼刀（docs/SHIRAUME_BOSS.md 第 4 节）。
 *
 * <p>招式由服务端的招式状态机（{@link MoveRunner}，招式文件 {@code shiraume}）驱动，动作按名字同步给客户端的 combat 控制器
 * （动画名加 {@code ryuka_} 前缀）。AI 是 {@link #think} 里一个简单的决策：远了冲过去接连招，近了挑一招砍，中距离绕着走。
 * 招式里的 Boss 专用字段（红光、被弹反怎么办、够多远、往前冲）见 {@link ShiraumeMoves}。
 *
 * <p>第一阶段不会死：血掉到 1 时停住，交给场地开始变身（{@link ShiraumeFight#transform}）。
 */
public class Shiraume extends PathfinderMob implements NpcAnimated, MoveRunner.Actor, Parry.Reactive, ShiraumeBoss {
    public static final Identifier ASSET = LogosDimension.id("shiraume");
    public static final Identifier MOVESET = LogosDimension.id("shiraume");
    /** 动画文件里招式的前缀。 */
    public static final String PREFIX = "ryuka_";
    private static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(Shiraume.class, EntityDataSerializers.STRING);
    /** 背后的封印光环烧掉了几枚（每放一次投技烧一枚，共八枚）。 */
    private static final EntityDataAccessor<Integer> HALO = SynchedEntityData.defineId(Shiraume.class, EntityDataSerializers.INT);
    /** 过场里（客户端用来决定刀画不画：跪坐时还没有凝出来）。 */
    private static final EntityDataAccessor<Boolean> SCRIPTED = SynchedEntityData.defineId(Shiraume.class, EntityDataSerializers.BOOLEAN);
    public static final int HALO_SEALS = 8;
    /** 投技的血量刻度：每掉六分之一放一次，共五次（第六次是变身）。 */
    public static final int SPECIALS = 5;
    /** 失衡满值：到了就蹲下喘息。 */
    public static final float IMBALANCE_MAX = 100;
    static final float IMBALANCE_SINGLE = 30, IMBALANCE_COMBO = 18, IMBALANCE_PER_DAMAGE = .35f, IMBALANCE_DECAY = 8;
    /** 失衡多久没有新增才开始回落（刻）。 */
    static final int IMBALANCE_GRACE = 50;
    /** 喘息多久（刻），这段时间受到的伤害乘几。 */
    static final int PANT_TICKS = 70;
    static final float PANT_DAMAGE = 1.3f;
    /** 投技冲刺：最多几刻、每刻几格。 */
    static final int THROW_DASH_TICKS = 10;
    static final double THROW_DASH_SPEED = 1.6;
    /** 背后挨打时转身横扫的机会，隔多少刻才会再转，挨打后多少刻内（手上这招结束后）还会转。 */
    static final float TURN_CHANCE = .4f;
    /**
     * 一字斩没被弹开也没被挡下时顺着刀势挥出的剑气：一道竖着的淡粉光刃，每刻 {@link #WAVE_SPEED} 格贴地往前，
     * 走 {@link #WAVE_RANGE} 格或撞墙散掉；高 {@link #WAVE_HEIGHT}、左右各 {@link #WAVE_HALF_WIDTH} 格，往旁边闪就躲开，盾能挡。
     */
    public static final double WAVE_SPEED = 1.1, WAVE_RANGE = 24, WAVE_HEIGHT = 4.2, WAVE_HALF_WIDTH = .9;
    static final float WAVE_DAMAGE = 10;
    static final int TURN_AGAIN = 100, TURN_PENDING = 14;
    /** 读指令：玩家吃喝时的机会、隔多少刻才会再读；第一刀落在吃完之后几刻。 */
    static final float READ_CHANCE = .5f;
    static final int READ_AGAIN = 240, READ_AFTER = 5;
    /** 他吃完（或停下）超过这么多刻她才腾出手：这次不读了。 */
    static final int READ_STALE = 10;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private @Nullable MoveRunner runner;
    private @Nullable ServerBossEvent bar;
    private @Nullable Long arena;
    private boolean scripted, resting, parried;
    private float imbalance;
    private long imbalanceAt;
    /** 已经放过投技的刻度（第 i 位是第 i 次，1–5）。 */
    private int specials;
    private boolean specialPending;
    // ---- 决策的状态（只在服务端）
    private long nextAttack, holdUntil, pantUntil, followAt, dashStart, executionEnds;
    private @Nullable String follow, lastAttack;
    private @Nullable Vec3 dashDirection;
    private @Nullable LivingEntity dashTarget;
    private @Nullable ServerPlayer victim;
    private float dashDistance;
    /** 对付术师（{@link ShiraumeCounter}）。 */
    final ShiraumeCounter counter = new ShiraumeCounter();
    /** 正在飞的剑气：起点、方向、放出的刻、上一刻走到哪、已经打到的人。 */
    private record Wave(Vec3 from, Vec3 dir, long start, double[] last, java.util.Set<java.util.UUID> hit) {}
    private final java.util.List<Wave> waves = new java.util.ArrayList<>();
    /** 读档时血条是亮着的：下一刻重新亮起（血条不随实体存下来）。 */
    private boolean barOnLoad;
    /** 这一招（按开始的时刻认）已经清过谁的增益。 */
    private double purgedFor = Double.NaN;
    private final java.util.Set<java.util.UUID> purgedWho = new java.util.HashSet<>();

    /** 对付术师的那一份（盯上了谁、冷却），测试与调试读。 */
    public ShiraumeCounter counter() {
        return counter;
    }
    // ---- 转身横扫：背后打她的人、什么时候之前还会转、转向谁、下次能转的刻
    private @Nullable LivingEntity turnFrom, turnTarget;
    private long turnPending, turnAgain;
    // ---- 读指令：在吃喝的人、他什么时候吃完、下次能读的刻
    private @Nullable ServerPlayer readTarget;
    private long readEnds, readAgain;
    private int strafeSide = 1;
    private long strafeSwitch;
    // ---- 客户端
    private String moveFrom = "";
    private @Nullable CombatMove clientMove;

    public Shiraume(EntityType<? extends Shiraume> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 400).add(Attributes.MOVEMENT_SPEED, .32)
                .add(Attributes.FOLLOW_RANGE, 48).add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.ARMOR, 8)
                .add(Attributes.STEP_HEIGHT, 1.5).add(Attributes.ATTACK_DAMAGE, 1).add(Attributes.ATTACK_SPEED, 4);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MOVE, "");
        builder.define(HALO, 0);
        builder.define(SCRIPTED, false);
    }

    // ---------------------------------------------------------------- 招式状态机

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

    /** 当前招式已经播到的动作时间（秒）。 */
    private double clipTime(long now) {
        return runner == null ? 0 : runner.move().seconds(now - runner.start(), runner.rate(), runner.offset());
    }

    @Override
    public double attackSpeed() {
        return 1;
    }

    @Override
    public void moveStarted(MoveRunner r) {
        if (r != runner) return;
        Moveset.Move m = r.move();
        entityData.set(MOVE, PREFIX + m.clip() + "|" + (long) r.start() + "|" + (float) r.rate() + "|" + (m.playback() == Moveset.Playback.LOOP));
        if (level() instanceof ServerLevel level) onMoveStarted(m.name(), level.getGameTime());
    }

    /** 换招时：一字斩的停顿定好随机长短，冲刺记下方向。 */
    private void onMoveStarted(String name, long now) {
        switch (name) {
            case "ichimonji_hold", "ichimonji_hold_combo" -> holdUntil = now + 10 + getRandom().nextInt(21);
            case "throw_dash" -> {
                LivingEntity target = getTarget();
                Vec3 to = target == null ? getLookAngle() : target.position().subtract(position());
                dashDirection = new Vec3(to.x, 0, to.z).normalize();
                dashStart = now;
                victim = null;
                speed(target, THROW_DASH_TICKS + 4);
            }
            case "pant" -> pantUntil = now + PANT_TICKS;
            default -> { }
        }
    }

    @Override
    public void contact(MoveRunner r) {
        if (!(level() instanceof ServerLevel level)) return;
        Moveset.Move move = r.move();
        String name = move.name();
        if (name.equals("slam")) {
            ShiraumeFight.slammed(level, this);
            return;
        }
        if (name.equals("throw_kiriage")) {
            launch(level);
            return;
        }
        if (name.equals("kitsunebi")) {
            counter.cut(level, this, 5.5);
            level.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.6f, .6f);
            return;
        }
        ShiraumeMoves.Extra extra = extra(name);
        double reach = extra.reach() > 0 ? extra.reach() : 4.5;
        // 转身横扫一整圈（招式文件里的 cone 最大 180，这里按 360 算）
        double cone = name.equals("turn_sweep") ? 360 : move.cone() > 0 ? move.cone() : 110;
        float damage = (float) (move.damage() * difficultyScale(level));
        boolean stopped = false;
        java.util.Set<java.util.UUID> struck = new java.util.HashSet<>();
        for (LivingEntity target : ShiraumeStrike.targets(this, reach, cone)) {
            // 一招对一个人只清一次增益：突き前后两下都中也只清两个
            if (r.start() != purgedFor) {
                purgedFor = r.start();
                purgedWho.clear();
            }
            int purge = purgedWho.contains(target.getUUID()) ? 0 : extra.purge();
            ShiraumeStrike.Result result = ShiraumeStrike.strike(this, this, target, damage, true, extra.guardBreak(), purge);
            if (result == ShiraumeStrike.Result.HIT && purge > 0) purgedWho.add(target.getUUID());
            struck.add(target.getUUID());
            if (result == ShiraumeStrike.Result.BLOCKED || result == ShiraumeStrike.Result.PARRIED) {
                stopped = true;
                Vec3 at = target.position().add(0, target.getBbHeight() * .6, 0).add(position().subtract(target.position()).normalize().scale(.5));
                ShiraumeCue.near(level, at, 64, ShiraumeCue.spark(at, result == ShiraumeStrike.Result.PARRIED));
            }
            // 被弹开了：这一刀后面的人不再结算
            if (r != runner || r.move() != move) break;
        }
        level.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.4f, .7f + getRandom().nextFloat() * .2f);
        // 一字斩没人弹开、没人挡下：挥出一道剑气（刚砍中的人不再挨一次）
        if (name.startsWith("ichimonji_cut") && !stopped && r == runner && r.move() == move) wave(level, struck);
    }

    /** 一字斩挥出剑气：从身前顺着她面朝的方向出去。 */
    private void wave(ServerLevel level, java.util.Set<java.util.UUID> struck) {
        Vec3 dir = forward();
        Vec3 from = position().add(dir.scale(1.2));
        long now = level.getGameTime();
        waves.add(new Wave(from, dir, now, new double[] {0}, new java.util.HashSet<>(struck)));
        ShiraumeCue.near(level, from, 96, ShiraumeCue.wave(this, from, dir, now));
        level.playSound(null, from.x, from.y + 1.5, from.z, SoundEvents.TRIDENT_RIPTIDE_1.value(), SoundSource.HOSTILE, 1.6f, 1.4f);
    }

    /** 剑气这一刻扫过的人各挨一下（能挡、弹不开），撞墙或走到头就散掉。 */
    private void tickWaves(ServerLevel level, long now) {
        if (waves.isEmpty()) return;
        float damage = (float) (WAVE_DAMAGE * difficultyScale(level));
        for (var it = waves.iterator(); it.hasNext(); ) {
            Wave w = it.next();
            double from = w.last[0], dist = Math.min(WAVE_RANGE, WAVE_SPEED * (now - w.start));
            Vec3 a = w.from.add(w.dir.scale(from)).add(0, 1, 0), b = w.from.add(w.dir.scale(dist)).add(0, 1, 0);
            var block = level.clip(new net.minecraft.world.level.ClipContext(a, b, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, this));
            boolean wall = block.getType() != net.minecraft.world.phys.HitResult.Type.MISS;
            if (wall) dist = from + block.getLocation().distanceTo(a);
            Vec3 side = new Vec3(-w.dir.z, 0, w.dir.x);
            Vec3 p0 = w.from.add(w.dir.scale(from)), p1 = w.from.add(w.dir.scale(dist));
            var box = new net.minecraft.world.phys.AABB(p0, p1).inflate(WAVE_HALF_WIDTH + 1, 0, WAVE_HALF_WIDTH + 1).expandTowards(0, WAVE_HEIGHT, 0)
                    .expandTowards(0, -1, 0);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != this && e.isAlive() && !(e instanceof ShiraumeBoss))) {
                if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
                Vec3 rel = e.position().subtract(w.from);
                double along = rel.x * w.dir.x + rel.z * w.dir.z, across = Math.abs(rel.x * side.x + rel.z * side.z), half = e.getBbWidth() / 2;
                if (along < from - half - .3 || along > dist + half + .3 || across > WAVE_HALF_WIDTH + half) continue;
                if (e.getY() > w.from.y + WAVE_HEIGHT || e.getY() + e.getBbHeight() < w.from.y - .5 || !w.hit.add(e.getUUID())) continue;
                ShiraumeStrike.Result result = ShiraumeStrike.strike(this, this, e, damage, false, false, 0);
                if (result == ShiraumeStrike.Result.HIT) {
                    e.push(w.dir.x * .6, .2, w.dir.z * .6);
                    e.hurtMarked = true;
                } else if (result == ShiraumeStrike.Result.BLOCKED) {
                    Vec3 at = e.position().add(0, e.getBbHeight() * .6, 0);
                    ShiraumeCue.near(level, at, 64, ShiraumeCue.spark(at, false));
                }
            }
            w.last[0] = dist;
            if (dist >= WAVE_RANGE || wall) {
                if (wall) {
                    Vec3 at = block.getLocation();
                    ShiraumeCue.near(level, at, 64, ShiraumeCue.spark(at, true));
                    level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.4f, .7f);
                }
                it.remove();
            }
        }
    }

    /** 测试用：还在飞的剑气有几道。 */
    public int waves() {
        return waves.size();
    }

    /** 离她 6 格内、在她背后的玩家（最近的那个），没有为 null。 */
    private @Nullable LivingEntity playerBehind(ServerLevel level) {
        LivingEntity best = null;
        for (Player p : level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(6), p -> p.isAlive() && !p.isCreative() && !p.isSpectator())) {
            if (behind(p) && (best == null || p.distanceToSqr(this) < best.distanceToSqr(this))) best = p;
        }
        return best;
    }

    static float difficultyScale(ServerLevel level) {
        Difficulty d = level.getDifficulty();
        return d == Difficulty.EASY ? .75f : d == Difficulty.HARD ? 1.25f : 1f;
    }

    // ---------------------------------------------------------------- 弹反与失衡

    @Override
    public boolean parried(LivingEntity by) {
        if (runner == null || scripted) return false;
        String name = runner.move().name();
        ShiraumeMoves.Extra extra = extra(name);
        long now = level().getGameTime();
        switch (extra.parry()) {
            case SINGLE -> {
                gainImbalance(IMBALANCE_SINGLE, now);
                play(extra.parried() != null ? extra.parried() : "parried_high");
                getNavigation().stop();
            }
            case COMBO -> {
                // 连招被弹开不停：刀被弹回来，借这股劲直接起下一刀（不再有挥到底的那一停）
                gainImbalance(IMBALANCE_COMBO, now);
                if (extra.follow() != null) {
                    follow = extra.follow();
                    followAt = now + 2;
                }
            }
            case NONE -> {
                return false;
            }
        }
        parried = true;
        level().playSound(null, getX(), getY() + 2, getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, .5f, 1.8f);
        return true;
    }

    @Override
    public boolean staggered() {
        String m = move();
        return m.startsWith("parried") || m.startsWith("pant");
    }

    @Override
    public boolean takeParried() {
        boolean was = parried;
        parried = false;
        return was;
    }

    void gainImbalance(float amount, long now) {
        imbalance = Math.min(IMBALANCE_MAX * 1.5f, imbalance + amount);
        imbalanceAt = now;
    }

    public float imbalance() {
        return imbalance;
    }

    // ---------------------------------------------------------------- 受伤

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && !scripted && !resting) return super.hurtServer(level, source, amount);
        if (scripted || resting) return false;
        if (source.getEntity() instanceof LivingEntity attacker && attacker != this && !(attacker instanceof ShiraumeBoss)) {
            if (getTarget() == null || attacker instanceof Player && getTarget() != attacker && getRandom().nextFloat() < .15f) setTarget(attacker);
            ShiraumeFight.joined(level, this, attacker);
            long now = level.getGameTime();
            // 术式打中她：同一个人打得多了就盯上他（之后他一蓄力就冲过去斩）
            if (Effects.inSpell() && attacker instanceof ServerPlayer caster) counter.spellHit(level, this, caster, now);
            // 有人在身后近身砍她：有机会猛地转身横扫一圈
            if (attacker instanceof Player && source.getDirectEntity() == attacker && behind(attacker) && now >= turnAgain
                    && (alwaysTurn || getRandom().nextFloat() < TURN_CHANCE)) {
                turnFrom = attacker;
                turnPending = now + TURN_PENDING;
            }
        }
        // 看不到实体的远程（瞬发的枪、其他模组的远程法术）：空着、正面对着时挡掉
        boolean ranged = source.is(DamageTypeTags.IS_PROJECTILE) || source.getDirectEntity() != source.getEntity();
        if (ranged && !source.is(DamageTypeTags.IS_EXPLOSION) && canDeflect() && facing(source.getSourcePosition())) {
            deflected(level, source.getSourcePosition());
            return false;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) amount *= .5f;
        if (move().equals("pant")) amount *= PANT_DAMAGE;
        return super.hurtServer(level, source, amount);
    }

    @Override
    protected void actuallyHurt(ServerLevel level, DamageSource source, float amount) {
        float before = getHealth();
        super.actuallyHurt(level, source, amount);
        float dealt = before - getHealth();
        if (dealt > 0) gainImbalance(dealt * IMBALANCE_PER_DAMAGE, level.getGameTime());
    }

    /** 第一阶段不会死：血掉到 1 就停住，交给场地变身；每过一格六分之一的刻度记一次投技。 */
    @Override
    public void setHealth(float health) {
        if (!level().isClientSide() && health < 1 && getHealth() > 0) {
            health = 1;
            if (level() instanceof ServerLevel level) ShiraumeFight.transform(level, this);
        }
        super.setHealth(health);
        if (level().isClientSide() || getMaxHealth() <= 0) return;
        float fraction = getHealth() / getMaxHealth();
        for (int i = 1; i <= SPECIALS; i++) {
            if ((specials & (1 << i)) == 0 && fraction <= (6 - i) / 6f) {
                specials |= 1 << i;
                specialPending = true;
            }
        }
        if (bar != null) bar.setProgress(Mth.clamp(fraction, 0, 1));
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

    // ---------------------------------------------------------------- 弹射物

    /** 现在能不能弹开飞来的东西：没在出刀、僵直、喘息、投技、过场里。 */
    boolean canDeflect() {
        if (scripted || resting || runner == null) return false;
        String m = move();
        return m.equals("idle") || m.equals("deflect");
    }

    private boolean facing(@Nullable Vec3 from) {
        if (from == null) return true;
        Vec3 to = from.subtract(position());
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        return flat < .5 || (to.x * forward.x + to.z * forward.z) / flat > Math.cos(Math.toRadians(100));
    }

    /**
     * 原版弹射物打到实体时先问这里（旋风人就是这样弹箭的）：她空着、正面朝着时一刀把它弹开，
     * 一半照原路弹回给射它的人，一半拨到旁边。继承原版 {@link Projectile} 的模组弹射物（子弹、火箭弹）一样。
     */
    @Override
    public ProjectileDeflection deflection(Projectile projectile) {
        if (level().isClientSide() || !canDeflect() || !facing(projectile.position())) return ProjectileDeflection.NONE;
        ServerLevel level = (ServerLevel) level();
        Entity owner = projectile.getOwner();
        boolean back = owner != null && owner != this && owner.isAlive() && owner.distanceToSqr(this) < 48 * 48 && getRandom().nextBoolean();
        deflected(level, projectile.position());
        ShiraumeDeflect.claim(projectile, this);
        if (back) {
            return (p, by, random) -> {
                Vec3 aim = owner.getEyePosition().subtract(p.position()).normalize();
                double speed = Math.max(.8, p.getDeltaMovement().length()) * 1.15;
                p.setDeltaMovement(aim.scale(speed));
                p.needsSync = true;
            };
        }
        return (p, by, random) -> {
            Vec3 v = p.getDeltaMovement();
            double speed = Math.max(.4, v.length() * .6);
            Vec3 side = new Vec3(-v.z, 0, v.x).normalize().scale(random.nextBoolean() ? 1 : -1);
            p.setDeltaMovement(side.add(0, .35, 0).normalize().scale(speed));
            p.needsSync = true;
        };
    }

    private void deflected(ServerLevel level, @Nullable Vec3 from) {
        if (move().equals("idle")) play("deflect");
        if (from != null) getLookControl().setLookAt(from.x, from.y, from.z);
        Vec3 at = position().add(0, 2.2, 0).add(from == null ? Vec3.ZERO : from.subtract(position()).normalize().scale(1.2));
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 1, 1.4f + getRandom().nextFloat() * .2f);
        ShiraumeCue.near(level, at, 64, ShiraumeCue.spark(at, false));
    }

    // ---------------------------------------------------------------- 投技

    /** 斬り上げ的接触：冲刺撞到的第一个人被挑飞，进处决特写；重击那一刻结算（{@link #execute}）。 */
    private void launch(ServerLevel level) {
        ServerPlayer p = victim;
        if (p == null || !p.isAlive()) return;
        int seed = getRandom().nextInt();
        ExecutionShot shot = ExecutionShot.of(seed);
        int ticks = ExecutionShots.play(p, p, shot);
        long now = level.getGameTime();
        int hit = Math.round(new ExecutionTimeline(shot).finalHit());
        ShiraumeFight.later(level, now + hit, () -> execute(p));
        executionEnds = now + ticks;
        entityData.set(HALO, Math.min(HALO_SEALS, entityData.get(HALO) + 1));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1.5f, .6f);
    }

    /**
     * 处决的重击：不看护甲、附魔与抗性。一半血以上只剩 1 点（吸收心一起算进去），一半以下直接击杀；
     * 伤害不绕过无敌，不死图腾照常能挡，创造模式不受伤。
     */
    void execute(ServerPlayer p) {
        if (!(level() instanceof ServerLevel level) || !p.isAlive() || p.isCreative() || p.isSpectator()) return;
        float pool = p.getHealth() + p.getAbsorptionAmount();
        float amount = p.getHealth() > p.getMaxHealth() / 2 ? pool - 1 : pool + 100;
        if (amount <= 0) return;
        p.invulnerableTime = 0;
        p.hurtServer(level, new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(ShiraumeStrike.EXECUTION), this), amount);
    }

    public int haloBurned() {
        return entityData.get(HALO);
    }

    // ---------------------------------------------------------------- 每刻

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        ServerLevel level = (ServerLevel) level();
        long now = level.getGameTime();
        MoveRunner r = runner();
        if (r != null) {
            double before = clipTime(now - 1), after;
            String name = r.move().name();
            r.tick(now);
            after = clipTime(now);
            if (r.move().name().equals(name)) motion(level, name, before, after, now);
        }
        if (now - imbalanceAt > IMBALANCE_GRACE) imbalance = Math.max(0, imbalance - IMBALANCE_DECAY / 20f);
        if (resting && tickCount % 20 == 0) heal(getMaxHealth() * .02f);
        if (tickCount % 20 == 0) updateBar(level);
        counter.refresh(level, now);
        tickWaves(level, now);
        // 存档时血条亮着（区块卸载后又载回来）：载回来重新亮起，只有这一条
        if (barOnLoad) {
            barOnLoad = false;
            showBar(true, 1);
        }
    }

    /** 招式里按动作时间发生的事：双眼红光、往前冲、后跳、冲刺。 */
    private void motion(ServerLevel level, String name, double before, double after, long now) {
        ShiraumeMoves.Extra extra = extra(name);
        if (extra.eyeFlash() >= 0 && before < extra.eyeFlash() && after >= extra.eyeFlash()) eyes(level);
        Vec3 forward = forward();
        if (extra.lunge() != null) {
            double[] l = extra.lunge();
            if (after > l[0] && before < l[1]) {
                double v = l[2] / ((l[1] - l[0]) * 20);
                move(MoverType.SELF, new Vec3(forward.x * v, 0, forward.z * v));
            }
        }
        if (extra.leap() != null && before < extra.leap()[0] && after >= extra.leap()[0]) {
            double[] l = extra.leap();
            setDeltaMovement(-forward.x * l[1] / 10, l[2], -forward.z * l[1] / 10);
            hurtMarked = true;
        }
        if (name.equals("dash") && dashDirection != null) {
            double v = Math.min(2.2, dashDistance / 8);
            move(MoverType.SELF, dashDirection.scale(v));
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + 1.5, getZ(), 3, .4, .8, .4, 0);
        }
        if (name.equals("throw_dash") && dashDirection != null) throwDash(level, now);
        // 转身横扫：起手的一瞬间整个人拧过来对着背后打她的人
        if (name.equals("turn_sweep") && turnTarget != null && after > .06 && before < .36) face(turnTarget, 48);
        if (name.equals("kitsunebi") && after < .3) level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(
                dev.logos.dimension.foxfire.FoxfireFlame.SHIRAUME.core, 1.6f), getX(), getY() + 1.8, getZ(), 6, .5, .7, .5, 0);
    }

    void eyes(ServerLevel level) {
        ShiraumeCue.near(level, position(), 96, ShiraumeCue.eyes(this));
        level.playSound(null, getX(), getEyeY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 2.0f, 1.7f);
        level.playSound(null, getX(), getEyeY(), getZ(), SoundEvents.TRIDENT_RETURN, SoundSource.HOSTILE, 1.2f, 1.5f);
    }

    Vec3 forward() {
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
    }

    private void throwDash(ServerLevel level, long now) {
        Vec3 step = dashDirection.scale(THROW_DASH_SPEED);
        Vec3 before = position();
        move(MoverType.SELF, step);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + 1.2, getZ(), 6, .5, .9, .5, .02);
        for (Player p : level.getEntitiesOfClass(Player.class, getBoundingBox().inflate(.9), p -> p.isAlive() && !p.isSpectator() && !p.isCreative())) {
            if (p instanceof ServerPlayer sp) {
                victim = sp;
                play("throw_kiriage");
                return;
            }
        }
        boolean stuck = position().distanceToSqr(before) < step.lengthSqr() * .2;
        if (now - dashStart >= THROW_DASH_TICKS || stuck) play("throw_kiriage");
    }

    // ---------------------------------------------------------------- 决策

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (scripted || resting || runner == null) {
            getNavigation().stop();
            return;
        }
        long now = level.getGameTime();
        LivingEntity target = ShiraumeFight.pickTarget(level, this);
        if (target != getTarget()) setTarget(target);
        String m = move();
        // 连招被弹开：借弹回来的劲直接起下一刀
        if (follow != null && now >= followAt) {
            String next = follow;
            follow = null;
            if (!m.startsWith("parried")) play(next);
            return;
        }
        // 一字斩举刀、蓄力时有人绕到了背后：不等斩下，直接转身横扫
        if (m.startsWith("ichimonji_raise") || m.startsWith("ichimonji_hold")) {
            LivingEntity back = playerBehind(level);
            if (back != null) {
                turnTarget = back;
                turnAgain = now + TURN_AGAIN;
                getNavigation().stop();
                play("turn_sweep");
                return;
            }
        }
        if (free(m) && special(level, now)) return;
        switch (m) {
            case "ichimonji_hold", "ichimonji_hold_combo" -> {
                face(target, 6);
                if (now >= holdUntil) play(m.equals("ichimonji_hold") ? "ichimonji_cut" : "ichimonji_cut_combo");
                return;
            }
            case "pant" -> {
                if (now >= pantUntil) {
                    imbalance = 0;
                    play("recover_slash");
                }
                return;
            }
            case "throw_aim" -> {
                if (clipTime(now) < 1.1) face(target, 15);
                return;
            }
            case "throw_hold" -> {
                if (now >= executionEnds) play("backstep");
                return;
            }
            case "dash" -> {
                LivingEntity to = dashTarget != null && dashTarget.isAlive() ? dashTarget : target;
                if (to != null && (to.distanceTo(this) < 3.2 || clipTime(now) >= .4)) {
                    String next = lastAttack == null ? "tsubame_1" : lastAttack;
                    lastAttack = null;
                    dashTarget = null;
                    face(to, 180);
                    play(next);
                }
                return;
            }
            default -> { }
        }
        if (!m.equals("idle") && !m.equals("deflect")) {
            // 起手时慢慢转向目标，红光之后锁定方向（看准了能闪开）
            ShiraumeMoves.Extra extra = extra(m);
            double t = clipTime(now);
            double commit = extra.eyeFlash() >= 0 ? extra.eyeFlash() : contactTime(m) - .2;
            if (t < commit) face(target, 10);
            getNavigation().stop();
            return;
        }
        if (target == null) {
            wander(level);
            return;
        }
        // 失衡满了：蹲下喘息
        if (imbalance >= IMBALANCE_MAX) {
            getNavigation().stop();
            play("pant_down");
            return;
        }
        if (specialPending) {
            specialPending = false;
            getNavigation().stop();
            play("throw_aim");
            return;
        }
        double d = horizontal(target);
        getLookControl().setLookAt(target, 30, 30);
        if (now < nextAttack) {
            circle(target, d, now);
            return;
        }
        if (d > 9) {
            dashTo(target, pick(new String[] {"tsubame_1", "kesa", "sanren_1", "tsuki"}));
            return;
        }
        if (d > 5.2) {
            if (d < 7.5 && getRandom().nextFloat() < .35f) attack(getRandom().nextBoolean() ? "tsuki" : "tsuki_kiriage", now);
            else getNavigation().moveTo(target, 1.05);
            return;
        }
        attack(pick(new String[] {"kesa", "gyaku", "yoko", "ichimonji_raise", "tsubame_1", "sanren_1", "tsuki_kiriage", "kesa", "gyaku"}), now);
    }

    /** 她现在空着（站定、绕走、刚弹开箭）：可以被下面这些特别的应对打断。 */
    private static boolean free(String m) {
        return m.equals("idle") || m.equals("deflect");
    }

    /**
     * 空着的时候先看有没有要特别应对的：盯上的术师在蓄力（冲过去斩）、刚才有人在背后砍她（转身横扫）、
     * 有人在她面前吃喝（读指令：等他吃完那一刻砍下来）。应对了就返回真。
     */
    private boolean special(ServerLevel level, long now) {
        ServerPlayer caster = counter.charging(level, this, now);
        if (caster != null) {
            eyes(level);
            dashTo(caster, "kitsunebi");
            return true;
        }
        if (turnFrom != null) {
            LivingEntity from = turnFrom;
            turnFrom = null;
            if (now <= turnPending && from.isAlive() && from.distanceToSqr(this) < 7 * 7) {
                turnTarget = from;
                turnAgain = now + TURN_AGAIN;
                getNavigation().stop();
                play("turn_sweep");
                return true;
            }
        }
        if (readTarget != null) return read(level, now);
        return false;
    }

    /**
     * 有人在吃喝时（{@link #readEating}）：贴到他跟前、盯着他，算好时刻起刀，让第一刀正好落在他吃完之后
     * {@link #READ_AFTER} 刻——吃得完，吃完不马上举盾就挨两刀（第一刀清掉两个增益）。他中途停下就当场砍。
     */
    private boolean read(ServerLevel level, long now) {
        ServerPlayer p = readTarget;
        if (p == null || !p.isAlive() || p.level() != level || p.isSpectator() || horizontal(p) > 20) {
            readTarget = null;
            return false;
        }
        // 按他手上正在吃的这一口算（她忙完别的招才腾出手时，他可能已经在吃下一个了）；早就吃完了就不读了
        boolean eating = p.isUsingItem() && eats(p.getUseItem());
        if (eating) readEnds = now + p.getUseItemRemainingTicks();
        else if (now > readEnds + READ_STALE) {
            readTarget = null;
            return false;
        }
        if (horizontal(p) > 3.4) {
            dashTo(p, "idle");
            return true;
        }
        getNavigation().stop();
        face(p, 30);
        getLookControl().setLookAt(p, 30, 30);
        long lead = Math.round(contactTime("yomi_1") * 20) - READ_AFTER;
        if (!eating || now >= readEnds - lead) {
            readTarget = null;
            lastAttack = "yomi_1";
            play("yomi_1");
            nextAttack = now + 20;
        }
        return true;
    }

    static boolean eats(net.minecraft.world.item.ItemStack stack) {
        var anim = stack.getUseAnimation();
        return anim == net.minecraft.world.item.ItemUseAnimation.EAT || anim == net.minecraft.world.item.ItemUseAnimation.DRINK;
    }

    /** 有人开始吃喝（{@code ShiraumeFight} 的事件）：她有机会读这一下。 */
    public void readEating(ServerPlayer p, int duration) {
        if (!(level() instanceof ServerLevel level) || scripted || resting || readTarget != null) return;
        long now = level.getGameTime();
        if (now < readAgain || p.isCreative() || p.isSpectator() || horizontal(p) > 16) return;
        if (arena != null && !ShiraumeFight.inArena(level, arena, p.blockPosition())) return;
        String m = move();
        if (m.startsWith("throw") || m.startsWith("pant") || m.startsWith("kneel") || m.equals("slam")) return;
        if (!alwaysRead && getRandom().nextFloat() >= READ_CHANCE) return;
        readTarget = p;
        readEnds = now + duration;
        readAgain = now + READ_AGAIN;
    }

    /** 测试用：读指令、转身横扫必定发生。 */
    public static boolean alwaysRead, alwaysTurn;

    /** 背后：在她身后 140° 里。 */
    private boolean behind(Entity e) {
        Vec3 to = e.position().subtract(position());
        Vec3 f = forward();
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        return flat > .3 && (to.x * f.x + to.z * f.z) / flat < Math.cos(Math.toRadians(110));
    }

    /** 冲过去：冲向的那个人画面上速度线与动态模糊最强，旁边的人淡一些。 */
    private void speed(@Nullable LivingEntity to, int ticks) {
        if (!(level() instanceof ServerLevel level)) return;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(this) > 48 * 48) continue;
            ShiraumeCue.send(p, ShiraumeCue.speed(this, p == to ? 1f : .35f, ticks));
        }
    }

    private double contactTime(String move) {
        if (runner == null) return 0;
        Moveset.Move mv = runner.moveset().move(move);
        return mv == null || !mv.hasContact() ? 0 : mv.contact();
    }

    private String pick(String[] moves) {
        String m;
        int guard = 0;
        do m = moves[getRandom().nextInt(moves.length)]; while (m.equals(lastAttack) && guard++ < 4);
        return m;
    }

    private void attack(String move, long now) {
        getNavigation().stop();
        lastAttack = move;
        play(move);
        nextAttack = now + 6 + getRandom().nextInt(16);
    }

    /** 縮地：一瞬间贴到目标面前，接着出 {@code then}。 */
    private void dashTo(LivingEntity target, String then) {
        getNavigation().stop();
        dashTarget = target;
        speed(target, 12);
        Vec3 to = target.position().subtract(position());
        dashDirection = new Vec3(to.x, 0, to.z).normalize();
        dashDistance = (float) Math.max(0, Math.sqrt(to.x * to.x + to.z * to.z) - 2.6);
        face(target, 180);
        lastAttack = then;
        play("dash");
        level().playSound(null, getX(), getY() + 1, getZ(), SoundEvents.BREEZE_JUMP, SoundSource.HOSTILE, 1.2f, .8f);
    }

    /** 中距离持刀绕着目标侧移，像剑客对峙。 */
    private void circle(LivingEntity target, double d, long now) {
        if (now >= strafeSwitch) {
            strafeSide = getRandom().nextBoolean() ? 1 : -1;
            strafeSwitch = now + 30 + getRandom().nextInt(40);
        }
        Vec3 from = target.position();
        Vec3 off = position().subtract(from);
        double angle = Math.atan2(off.z, off.x) + strafeSide * .45;
        double keep = Mth.clamp(d, 3.2, 6.5);
        Vec3 dest = from.add(Math.cos(angle) * keep, 0, Math.sin(angle) * keep);
        getNavigation().moveTo(dest.x, dest.y, dest.z, .7);
    }

    private void wander(ServerLevel level) {
        Vec3 home = ShiraumeFight.home(level, this);
        if (home != null && position().distanceToSqr(home) > 16) getNavigation().moveTo(home.x, home.y, home.z, .8);
    }

    private double horizontal(Entity e) {
        double dx = e.getX() - getX(), dz = e.getZ() - getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 转向目标，每刻最多转 {@code rate} 度。 */
    void face(@Nullable LivingEntity target, float rate) {
        if (target == null) return;
        double dx = target.getX() - getX(), dz = target.getZ() - getZ();
        float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
        float yaw = Mth.approachDegrees(getYRot(), want, rate);
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
    }

    // ---------------------------------------------------------------- 场地

    @Override
    public @Nullable Long arena() {
        return arena;
    }

    @Override
    public void setArena(@Nullable Long arena) {
        this.arena = arena;
    }

    @Override
    public boolean scripted() {
        return scripted;
    }

    @Override
    public void setScripted(boolean scripted) {
        this.scripted = scripted;
        entityData.set(SCRIPTED, scripted);
        if (scripted) getNavigation().stop();
    }

    /** 客户端：过场里（刀是否已经凝出来由动作决定）。 */
    public boolean scriptedClient() {
        return entityData.get(SCRIPTED);
    }

    @Override
    public boolean resting() {
        return resting;
    }

    @Override
    public void setResting(boolean resting) {
        if (this.resting == resting) return;
        this.resting = resting;
        play(resting ? "seiza" : "rise");
        showBar(!resting, 1);
    }

    @Override
    public void showBar(boolean shown, float fill) {
        if (bar == null) {
            bar = new ServerBossEvent(UUID.randomUUID(), Component.translatable("entity.logos_dimension.shiraume.bar"),
                    BossEvent.BossBarColor.PINK, BossEvent.BossBarOverlay.NOTCHED_6);
            bar.setDarkenScreen(false);
        }
        bar.setVisible(shown);
        bar.setProgress(getMaxHealth() <= 0 ? 0 : Mth.clamp(getHealth() / getMaxHealth(), 0, 1));
        if (shown && level() instanceof ServerLevel level) updateBar(level);
    }

    private void updateBar(ServerLevel level) {
        if (bar == null) return;
        for (ServerPlayer p : java.util.List.copyOf(bar.getPlayers())) {
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

    /**
     * 不再被这个玩家看着（走远、死后在别处重生、换了维度、区块卸载）：从血条里拿掉。
     * 只在 {@link #remove} 里拿掉不够：区块卸载时不经过它，客户端的血条就一直留着，回来时又加一条新的。
     */
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

    // ---------------------------------------------------------------- 存档

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (arena != null) output.putLong("Arena", arena);
        output.putInt("Specials", specials);
        output.putInt("Halo", entityData.get(HALO));
        output.putBoolean("Resting", resting);
        output.putBoolean("BarShown", bar != null && bar.isVisible());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        arena = input.getLong("Arena").orElse(null);
        specials = input.getIntOr("Specials", 0);
        entityData.set(HALO, input.getIntOr("Halo", 0));
        resting = input.getBooleanOr("Resting", false);
        barOnLoad = input.getBooleanOr("BarShown", false);
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
        return clientMove;
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
