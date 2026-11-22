package dev.logos.dimension.jiansui;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.BossMoves;
import dev.logos.dimension.combat.BossStrike;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.combat.Parry;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.shiraume.ShiraumeCue;
import dev.logos.dimension.shiraume.ShiraumeDeflect;
import java.util.UUID;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 剑祟（docs/JIAN_SUI_BOSS.md）：四格高的鬼面剑客，太玄灵境雾底场地的 Boss。
 *
 * <p>招式由服务端的招式状态机（{@link MoveRunner}，招式文件 {@code jian_sui}）驱动，动作按名字同步给客户端的 combat 控制器
 * （动画名不加前缀）。招式里的 Boss 专用字段（出手前双眼闪光的时刻、被弹反怎么办、够多远、往前冲的一段）见 {@link BossMoves}。
 * 命中、格挡、弹反照 {@link BossStrike}，没挡下时是原版的物理伤害（生物近战，护甲照算）。
 *
 * <p>看人只转眼珠，头不跟着转（第 5.1 节）：盯着的人同步给客户端（{@link #lookTarget()}），渲染器把竖瞳在眼眶里平移过去；
 * 超出眼眶的角度靠整个身子转过去补。不迈步：两腿保持待势，整个身子贴着地面滑过去（第 6.1 节）。
 *
 * <p>前两个阶段不会死：血掉到 1 时停住，交给场地转阶段；每刻受到的伤害不超过当前这条血的四分之一（第 4 节）。
 */
public class JianSui extends PathfinderMob implements NpcAnimated, MoveRunner.Actor, Parry.Reactive {
    public static final Identifier ASSET = LogosDimension.id("jian_sui");
    public static final Identifier MOVESET = LogosDimension.id("jian_sui");
    /** 两条血：第一阶段、第二阶段（第三阶段不掉血，只剩一丝）。 */
    public static final float HEALTH_ONE = 450, HEALTH_TWO = 550;
    /** 每刻受到的伤害最多是当前这条血的几分之几。 */
    static final float CAP_PER_TICK = .25f;
    /** 失衡（隐藏数值，同白梅）：满值、独立招式被弹反、连续招式被弹反、每点伤害、多久没新增开始回落、每秒回落。 */
    public static final float IMBALANCE_MAX = 100;
    static final float IMBALANCE_SINGLE = 30, IMBALANCE_COMBO = 18, IMBALANCE_PER_DAMAGE = .35f, IMBALANCE_DECAY = 8;
    static final int IMBALANCE_GRACE = 50;
    /** 失衡跪地多久（刻），这段时间受到的伤害乘几。 */
    static final int STAGGER_TICKS = 60;
    static final float STAGGER_DAMAGE = 1.3f;
    /** 两招之间：收势后隔多久（刻）再出手。 */
    static final int GAP_MIN = 8, GAP_MAX = 16;
    /** 滑行的速度（格/刻）与「够得着」以外多远开始滑。 */
    static final double GLIDE_SPEED = .26;
    /** 命令召出来的（没有场地）在多远内找人。 */
    static final double FREE_RADIUS = 32;

    private static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(JianSui.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(JianSui.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SCRIPTED = SynchedEntityData.defineId(JianSui.class, EntityDataSerializers.BOOLEAN);
    /** 眼珠盯着的实体（-1 没有）。 */
    private static final EntityDataAccessor<Integer> LOOK = SynchedEntityData.defineId(JianSui.class, EntityDataSerializers.INT);
    /** 最近一次双眼闪光的游戏刻（客户端按它画琥珀光，不另发包）。 */
    private static final EntityDataAccessor<Integer> FLASH = SynchedEntityData.defineId(JianSui.class, EntityDataSerializers.INT);
    /** 藏起来的剑：第 0 位手中古剑，第 1–5 位背剑 0–4，第 6 位贯穿古剑。 */
    private static final EntityDataAccessor<Integer> HIDDEN = SynchedEntityData.defineId(JianSui.class, EntityDataSerializers.INT);
    public static final int HIDE_HELD = 1, HIDE_AXIAL = 1 << 6;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private @Nullable MoveRunner runner;
    private @Nullable ServerBossEvent bar;
    /** 所在场地（结构起点区块的 {@code ChunkPos.toLong}）；命令召出来的为 null。 */
    private @Nullable Long arena;
    private boolean parried;
    private float imbalance;
    private long imbalanceAt, nextAttack, staggerUntil;
    /** 这一刻已经受了多少伤（上限按当前这条血的四分之一）。 */
    private long damageTick;
    private float damageThisTick;
    private boolean barOnLoad;
    private String moveOnLoad = "";
    private int strafeSide = 1;
    private long strafeSwitch;
    /** 命令召出来的那只散架以后从哪一刻开始数（不存档：读档后重新数 3 秒再删）。 */
    private long collapseAt = -1;
    /** 追人时上一次量进度的位置和刻（被挡住 2 秒就斩碎挡路的方块，第 5.7 节）。 */
    private @Nullable Vec3 progressFrom;
    private long progressAt;
    // ---- 客户端
    private String moveFrom = "";
    private @Nullable CombatMove clientMove;

    public JianSui(EntityType<? extends JianSui> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, HEALTH_ONE).add(Attributes.MOVEMENT_SPEED, .3)
                .add(Attributes.FOLLOW_RANGE, 48).add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.ARMOR, 10)
                .add(Attributes.STEP_HEIGHT, 1.5).add(Attributes.ATTACK_DAMAGE, 1).add(Attributes.ATTACK_SPEED, 4);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MOVE, "");
        builder.define(PHASE, 1);
        builder.define(SCRIPTED, false);
        builder.define(LOOK, -1);
        builder.define(FLASH, -1000);
        builder.define(HIDDEN, 0);
    }

    // ---------------------------------------------------------------- 状态

    public int phase() { return entityData.get(PHASE); }

    public void setPhase(int phase) { entityData.set(PHASE, phase); }

    public boolean scripted() { return entityData.get(SCRIPTED); }

    public void setScripted(boolean scripted) {
        entityData.set(SCRIPTED, scripted);
        if (scripted) getNavigation().stop();
    }

    public @Nullable Long arena() { return arena; }

    public void setArena(@Nullable Long arena) { this.arena = arena; }

    /** 眼珠盯着的实体（客户端用）。 */
    public @Nullable Entity lookTarget() {
        int id = entityData.get(LOOK);
        return id < 0 ? null : level().getEntity(id);
    }

    /** 过场、客户端场景用：眼珠盯着 {@code target}（null 不盯）。平时由决策每刻更新。 */
    public void lookAt(@Nullable Entity target) {
        entityData.set(LOOK, target == null ? -1 : target.getId());
    }

    /** 最近一次双眼闪光的游戏刻（客户端用）。 */
    public int flashTick() { return entityData.get(FLASH); }

    public int hidden() { return entityData.get(HIDDEN); }

    public void setHidden(int mask) { entityData.set(HIDDEN, mask); }

    public float imbalance() { return imbalance; }

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

    /** 由场地或命令强制换一招（过场、测试）。 */
    public void play(String move) {
        // 场地已经摆了别的招式：读档带回来的那一招不再接着摆（不然第一刻会把场地刚摆的盖掉）
        moveOnLoad = "";
        MoveRunner r = runner();
        if (r != null && r.moveset().move(move) != null) r.force(move, level().getGameTime());
    }

    public String move() {
        return runner == null || runner.cleared() ? "" : runner.move().name();
    }

    private BossMoves.Extra extra(String move) {
        return level() instanceof ServerLevel level ? BossMoves.of(level.getServer(), MOVESET, move) : BossMoves.Extra.NONE;
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
        if (r != runner && runner != null) return;
        Moveset.Move m = r.move();
        entityData.set(MOVE, m.clip() + "|" + (long) r.start() + "|" + (float) r.rate() + "|" + (m.playback() == Moveset.Playback.LOOP));
        if (m.name().equals("stagger") && level() instanceof ServerLevel level) staggerUntil = level.getGameTime() + STAGGER_TICKS;
    }

    @Override
    public void contact(MoveRunner r) {
        if (!(level() instanceof ServerLevel level)) return;
        Moveset.Move move = r.move();
        String name = move.name();
        BossMoves.Extra extra = extra(name);
        double reach = extra.reach() > 0 ? extra.reach() : 2.8;
        double cone = move.cone() > 0 ? move.cone() : 120;
        // 原版的生物近战伤害（mob_attack）对玩家自己按难度缩放，这里不再乘一次
        float damage = (float) move.damage();
        // 回风：先横掠 8，再贴地反切 6（第 6.2 节）
        if (name.equals("huifeng") && r.contactIndex() == 1) damage = 6;
        boolean guardBreak = extra.guardBreak() && !(name.equals("huifeng") && r.contactIndex() == 1);
        for (LivingEntity target : BossStrike.targets(this, reach, cone, e -> e instanceof JianSui)) {
            BossStrike.Result result = strike(level, target, damage, level.damageSources().mobAttack(this), guardBreak, extra.purge());
            if (result == BossStrike.Result.BLOCKED || result == BossStrike.Result.PARRIED) {
                Vec3 at = target.position().add(0, target.getBbHeight() * .6, 0).add(position().subtract(target.position()).normalize().scale(.5));
                ShiraumeCue.near(level, at, 64, ShiraumeCue.spark(at, result == BossStrike.Result.PARRIED));
            }
            // 被弹开了：这一刀后面的人不再结算
            if (r != runner || r.move() != move) break;
        }
        level.playSound(null, getX(), getY() + 2, getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.4f, .6f + getRandom().nextFloat() * .15f);
        // 飞燕落地震地：半径 2 格（第 6.2 节）
        if (name.equals("feiyan")) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.MACE_SMASH_GROUND, SoundSource.HOSTILE, 1.2f, .8f);
        }
    }

    /** 剑祟的一下：没挡下时是原版的物理伤害（护甲照算），挡下、弹反照 {@link BossStrike}。 */
    BossStrike.Result strike(ServerLevel level, LivingEntity target, float damage, DamageSource melee, boolean guardBreak, int purge) {
        return BossStrike.strike(this, this::takeParried, target, damage, melee, (l, t, d) -> {
            t.hurtServer(l, melee, d);
            return !t.isAlive();
        }, guardBreak, purge, LOOK_OF);
    }

    /** 破盾、清增益的样子：同白梅的火花（颜色由客户端的火花定）。 */
    static final BossStrike.Look LOOK_OF = new BossStrike.Look() {
        @Override public void guardBreak(ServerLevel level, Vec3 at) { ShiraumeCue.near(level, at, 64, ShiraumeCue.guardBreak(at)); }

        @Override public void purge(ServerLevel level, LivingEntity target, float[] colours) {
            ShiraumeCue.near(level, target.position(), 64, ShiraumeCue.purge(target, colours));
        }
    };

    // ---------------------------------------------------------------- 弹反与失衡

    @Override
    public boolean parried(LivingEntity by) {
        if (runner == null) return false;
        // 第三阶段它不近战，能被弹反的只有巨剑：记下来，由场地把剑弹回去（docs/JIAN_SUI_BOSS.md 第 10.3 节）
        if (phase() == 3) {
            parried = true;
            return true;
        }
        String name = runner.move().name();
        BossMoves.Extra extra = extra(name);
        long now = level().getGameTime();
        switch (extra.parry()) {
            case SINGLE -> {
                gainImbalance(IMBALANCE_SINGLE, now);
                play(extra.parried() != null ? extra.parried() : "parried");
                getNavigation().stop();
            }
            case COMBO -> gainImbalance(IMBALANCE_COMBO, now);
            case NONE -> {
                return false;
            }
        }
        parried = true;
        level().playSound(null, getX(), getY() + 2, getZ(), SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, .5f, 1.6f);
        return true;
    }

    @Override
    public boolean staggered() {
        String m = move();
        return m.startsWith("parried") || m.startsWith("stagger");
    }

    public boolean takeParried() {
        boolean was = parried;
        parried = false;
        return was;
    }

    void gainImbalance(float amount, long now) {
        imbalance = Math.min(IMBALANCE_MAX * 1.5f, imbalance + amount);
        imbalanceAt = now;
    }

    // ---------------------------------------------------------------- 受伤

    /** 人站在伸手够得着它的地方（交互距离加一格余量，从眼睛量到它的碰撞箱）。 */
    private boolean withinMelee(Player p) {
        double reach = p.entityInteractionRange() + 1;
        return getBoundingBox().distanceToSqr(p.getEyePosition()) <= reach * reach;
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        // 残骸：被人打了就醒过来（第 3.2 节）；它本身打不坏
        if (phase() == 0) {
            if (source.getEntity() instanceof ServerPlayer p && !p.isSpectator()) JianSuiFight.wake(level, this);
            return false;
        }
        // 第三阶段不掉血：只有真正的近战（手里的东西直接打的，人在够得着的地方，不在术式里）会进终结（第 11 节）。
        // 惊鸿剑阵这类飞剑的伤害也记在玩家名下（直接来源就是玩家），所以另外看伤害类型和距离
        if (phase() == 3) {
            if (source.getEntity() instanceof ServerPlayer p && source.getDirectEntity() == p && !dev.logos.runtime.Effects.inSpell()
                    && !source.is(DamageTypeTags.IS_PROJECTILE) && !source.is(dev.logos.dimension.jinghong.SwordArray.DAMAGE) && withinMelee(p))
                JianSuiFight.finisherHit(level, this, p);
            return false;
        }
        if (scripted()) return false;
        if (source.getEntity() instanceof LivingEntity attacker && attacker != this) {
            if (getTarget() == null || attacker instanceof Player && getTarget() != attacker && getRandom().nextFloat() < .15f) setTarget(attacker);
        }
        // 看不到实体的远程（瞬发的枪、其他模组的远程法术）：空着、正面对着时挡掉
        boolean ranged = source.is(DamageTypeTags.IS_PROJECTILE) || source.getDirectEntity() != source.getEntity();
        if (ranged && !source.is(DamageTypeTags.IS_EXPLOSION) && canDeflect() && facing(source.getSourcePosition())) {
            deflected(level, source.getSourcePosition());
            return false;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) amount *= .5f;
        if (move().equals("stagger") || move().equals("stagger_hold")) amount *= STAGGER_DAMAGE;
        // 每刻的伤害有上限（处决、每刻补刀的也一样），过场不会被一下跳过
        long now = level.getGameTime();
        if (now != damageTick) {
            damageTick = now;
            damageThisTick = 0;
        }
        float cap = getMaxHealth() * CAP_PER_TICK;
        amount = Math.min(amount, Math.max(0, cap - damageThisTick));
        if (amount <= 0) return false;
        damageThisTick += amount;
        return super.hurtServer(level, source, amount);
    }

    @Override
    protected void actuallyHurt(ServerLevel level, DamageSource source, float amount) {
        float before = getHealth();
        super.actuallyHurt(level, source, amount);
        float dealt = before - getHealth();
        if (dealt > 0) gainImbalance(dealt * IMBALANCE_PER_DAMAGE, level.getGameTime());
    }

    /** 前两个阶段不会死：血掉到 1 就停住，交给场地转阶段（命令召出来的没有场地：回满血继续）。 */
    @Override
    public void setHealth(float health) {
        if (!level().isClientSide() && health < 1 && getHealth() > 0) {
            health = 1;
            int before = phase();
            if (level() instanceof ServerLevel level) JianSuiFight.barEmptied(level, this);
            // 换了下一条血：当场补满（不能排到下一刻，同一刻里接着来的伤害会把新的一条也打空）
            if (phase() != before && phase() < 3) health = getMaxHealth();
        }
        super.setHealth(health);
        if (!level().isClientSide() && bar != null && getMaxHealth() > 0) bar.setProgress(Mth.clamp(getHealth() / getMaxHealth(), 0, 1));
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

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    /** 残骸：右键它也会醒过来。 */
    @Override
    protected net.minecraft.world.InteractionResult mobInteract(Player player, net.minecraft.world.InteractionHand hand) {
        if (phase() == 0 && level() instanceof ServerLevel level) {
            JianSuiFight.wake(level, this);
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        return super.mobInteract(player, hand);
    }

    // ---------------------------------------------------------------- 弹射物

    /** 现在能不能拨开飞来的东西：空着（待势、滑行）的时候。 */
    boolean canDeflect() {
        if (scripted() || runner == null || phase() == 0) return false;
        String m = move();
        return m.equals("idle") || m.equals("deflect");
    }

    boolean facing(@Nullable Vec3 from) {
        if (from == null) return true;
        Vec3 to = from.subtract(position());
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        double flat = Math.sqrt(to.x * to.x + to.z * to.z);
        return flat < .5 || (to.x * forward.x + to.z * forward.z) / flat > Math.cos(Math.toRadians(100));
    }

    /** 原版弹射物打到它时先问这里：空着、正面朝着时手中剑一拨，一半弹回给射手，一半拨到旁边（第 5.4 节，同白梅）。 */
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
        Vec3 at = position().add(0, 2.4, 0).add(from == null ? Vec3.ZERO : from.subtract(position()).normalize().scale(1.2));
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, 1, 1.3f + getRandom().nextFloat() * .2f);
        ShiraumeCue.near(level, at, 64, ShiraumeCue.spark(at, false));
    }

    // ---------------------------------------------------------------- 每刻

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        ServerLevel level = (ServerLevel) level();
        long now = level.getGameTime();
        // 场地的记录没了、已经打败、或者记录里换了一只（读档、重置之后多出来的）：删掉自己
        if (tickCount % 20 == 0 && JianSuiFight.orphan(level, this)) {
            discard();
            return;
        }
        // 命令召出来的那只散架了：3 秒后删掉（读档后重新数，不会留下一副打不动的残骸）
        if (arena == null && phase() == 3) {
            if (collapseAt < 0) collapseAt = now;
            else if (now - collapseAt >= 60) {
                discard();
                return;
            }
        }
        MoveRunner r = runner();
        if (r != null) {
            double before = clipTime(now - 1), after;
            String name = r.move().name();
            r.tick(now);
            after = clipTime(now);
            if (r.move().name().equals(name)) motion(level, name, before, after);
        }
        if (now - imbalanceAt > IMBALANCE_GRACE) imbalance = Math.max(0, imbalance - IMBALANCE_DECAY / 20f);
        if (tickCount % 20 == 0) updateBar(level);
        if (!moveOnLoad.isEmpty()) {
            // 读档：接着摆存档时的那一招（残骸、插剑静立、散架这些停住的样子）
            play(moveOnLoad);
            moveOnLoad = "";
        }
        if (barOnLoad) {
            barOnLoad = false;
            showBar(true);
        }
    }

    /** 招式里按动作时间发生的事：双眼闪光、往前冲。 */
    private void motion(ServerLevel level, String name, double before, double after) {
        BossMoves.Extra extra = extra(name);
        if (extra.eyeFlash() >= 0 && before < extra.eyeFlash() && after >= extra.eyeFlash()) flash(level);
        if (extra.lunge() != null) {
            double[] l = extra.lunge();
            if (after > l[0] && before < l[1]) {
                Vec3 forward = forward();
                double v = l[2] / ((l[1] - l[0]) * 20);
                move(MoverType.SELF, new Vec3(forward.x * v, 0, forward.z * v));
            }
        }
        // 往后滑开（不迈步：贴着地面滑，上跳速度很小）
        if (extra.leap() != null && before < extra.leap()[0] && after >= extra.leap()[0]) {
            double[] l = extra.leap();
            Vec3 forward = forward();
            setDeltaMovement(-forward.x * l[1] / 10, l[2], -forward.z * l[1] / 10);
            hurtMarked = true;
        }
    }

    /** 出手前的最后一刻：竖瞳锁死在目标身上、双眼闪一次琥珀光、一声短促的剑鸣（第 5.1 节）。 */
    void flash(ServerLevel level) {
        entityData.set(FLASH, (int) level.getGameTime());
        level.playSound(null, getX(), getEyeY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 2.0f, 1.9f);
        level.playSound(null, getX(), getEyeY(), getZ(), SoundEvents.TRIDENT_RETURN, SoundSource.HOSTILE, 1.0f, 1.8f);
    }

    Vec3 forward() {
        float yaw = getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
    }

    // ---------------------------------------------------------------- 决策

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (scripted() || phase() == 0 || runner == null) {
            getNavigation().stop();
            return;
        }
        long now = level.getGameTime();
        LivingEntity target = JianSuiFight.pickTarget(level, this);
        if (target != getTarget()) setTarget(target);
        entityData.set(LOOK, target == null ? -1 : target.getId());
        String m = move();
        // 失衡跪地：跪满时间就站起来，起身时快速一剑（照样闪光），然后后撤（第 5.6 节）
        if (m.equals("stagger") || m.equals("stagger_hold")) {
            if (now >= staggerUntil) {
                imbalance = 0;
                face(target, 180);
                play("stagger_rise");
                nextAttack = now + 30;
            }
            return;
        }
        if (!m.equals("idle")) return;
        if (imbalance >= IMBALANCE_MAX) {
            getNavigation().stop();
            play("stagger");
            return;
        }
        if (target == null) {
            // 目标出了战斗范围：回到铁链旁插剑的那一点（第 5.7 节）
            Vec3 home = JianSuiFight.home(level, this);
            if (home != null && position().distanceToSqr(home) > 4) getNavigation().moveTo(home.x, home.y, home.z, 1.0);
            else getNavigation().stop();
            progressFrom = null;
            return;
        }
        face(target, 18);
        double dist = Math.sqrt(horizontalDistanceSqr(target));
        String pick = pick(dist);
        if (pick != null && now >= nextAttack) {
            getNavigation().stop();
            face(target, 180);
            play(pick);
            Moveset.Move move = runner.moveset().move(pick);
            nextAttack = now + Math.round(move.length() * 20) + GAP_MIN + getRandom().nextInt(GAP_MAX - GAP_MIN + 1);
            progressFrom = null;
            return;
        }
        if (pick != null) {
            // 够得着但还在收势后的空档里：绕着走
            strafe(level, target, now);
            progressFrom = null;
            return;
        }
        // 够不着：在活动范围里就贴着地面滑过去；目标在活动范围外不追（第 2.1、5.7 节，以后用飞剑打）
        if (!JianSuiFight.reachable(level, this, target.position())) {
            getNavigation().stop();
            progressFrom = null;
            return;
        }
        getNavigation().moveTo(target, 1.15);
        // 被挡住 2 秒（一直想过去却没挪动）：斩碎挡路的、玩家放的方块（第 5.7 节）
        if (progressFrom == null) {
            progressFrom = position();
            progressAt = now;
        } else if (now - progressAt >= 40) {
            if (position().distanceToSqr(progressFrom) < .36) JianSuiFight.blocked(level, this, target);
            progressFrom = position();
            progressAt = now;
        }
    }

    private double horizontalDistanceSqr(Entity e) {
        double dx = e.getX() - getX(), dz = e.getZ() - getZ();
        return dx * dx + dz * dz;
    }

    /** 按距离选一招：每招按自己够得着的距离出手（第 5.3 节）；都够不着时返回 null（先滑过去）。 */
    private @Nullable String pick(double dist) {
        java.util.List<String> fit = new java.util.ArrayList<>();
        for (String name : new String[] {"tafeng", "huifeng", "feiyan"}) {
            BossMoves.Extra extra = extra(name);
            double reach = extra.reach() > 0 ? extra.reach() : 2.8;
            // 往前冲的招式够得更远
            double lunge = extra.lunge() == null ? 0 : extra.lunge()[2];
            if (dist <= reach + lunge + .6) fit.add(name);
        }
        if (fit.isEmpty()) return null;
        return fit.get(getRandom().nextInt(fit.size()));
    }

    private void strafe(ServerLevel level, LivingEntity target, long now) {
        if (now >= strafeSwitch) {
            strafeSide = getRandom().nextBoolean() ? 1 : -1;
            strafeSwitch = now + 30 + getRandom().nextInt(30);
        }
        Vec3 to = target.position().subtract(position());
        Vec3 side = new Vec3(-to.z, 0, to.x).normalize().scale(strafeSide * 1.5);
        Vec3 dest = position().add(side);
        getNavigation().moveTo(dest.x, dest.y, dest.z, .8);
    }

    /** 整个身子转向 {@code target}，每刻最多转 {@code step} 度（头不另外转：看人只转眼珠）。 */
    void face(@Nullable Entity target, float step) {
        if (target == null) return;
        Vec3 to = target.position().subtract(position());
        float yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90;
        float turned = Mth.approachDegrees(getYRot(), yaw, step);
        setYRot(turned);
        setYBodyRot(turned);
        setYHeadRot(turned);
        yBodyRotO = yBodyRot;
    }

    // ---------------------------------------------------------------- 血条

    public void showBar(boolean shown) {
        if (bar == null) {
            bar = new ServerBossEvent(UUID.randomUUID(), Component.translatable("entity.logos_dimension.jian_sui.bar"),
                    BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
            bar.setDarkenScreen(false);
        }
        bar.setColor(switch (phase()) { case 2 -> BossEvent.BossBarColor.BLUE; case 3 -> BossEvent.BossBarColor.WHITE; default -> BossEvent.BossBarColor.YELLOW; });
        bar.setVisible(shown);
        bar.setProgress(getMaxHealth() <= 0 ? 0 : Mth.clamp(getHealth() / getMaxHealth(), 0, 1));
        if (shown && level() instanceof ServerLevel level) updateBar(level);
    }

    public boolean barShown() {
        return bar != null && bar.isVisible();
    }

    /** 血条进度（第三阶段只剩一丝时由场地设）。 */
    public void barProgress(float progress) {
        if (bar != null) bar.setProgress(Mth.clamp(progress, 0, 1));
    }

    private void updateBar(ServerLevel level) {
        if (bar == null) return;
        for (ServerPlayer p : java.util.List.copyOf(bar.getPlayers())) {
            if (!p.isAlive() || p.level() != level || !JianSuiFight.seesBar(level, this, p)) bar.removePlayer(p);
        }
        if (!bar.isVisible()) return;
        for (ServerPlayer p : level.players()) if (JianSuiFight.seesBar(level, this, p)) bar.addPlayer(p);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        if (bar != null) bar.removePlayer(player);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (bar != null) bar.removeAllPlayers();
        super.remove(reason);
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("Phase", phase());
        if (arena != null) output.putLong("Arena", arena);
        output.putBoolean("BarShown", barShown());
        output.putInt("Hidden", hidden());
        output.putBoolean("Scripted", scripted());
        if (!move().isEmpty()) output.putString("Move", move());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        setPhase(input.getIntOr("Phase", 1));
        arena = input.getLong("Arena").orElse(null);
        barOnLoad = input.getBooleanOr("BarShown", false);
        setHidden(input.getIntOr("Hidden", 0));
        setScripted(input.getBooleanOr("Scripted", false));
        moveOnLoad = input.getStringOr("Move", "");
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
