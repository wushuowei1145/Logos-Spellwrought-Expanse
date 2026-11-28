package dev.logos.dimension.companion;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 拉塞塔飞（接入方案第六节 6.1、第七节第 8 条；docs/LACERTA_COMPANION.md 第 18 节）。始终带着碰撞走（不穿方块、不破坏方块）：关掉重力，
 * 每刻定她的速度，由原版的移动按碰撞走。三件事：
 * <ul>
 *   <li><b>伴飞</b>：你在飞（创造飞行、鞘翅、离地 {@link #ALOFT_HEIGHT} 格以上浮着 {@link #ALOFT_TICKS} 刻）时，她展翅跟在你左侧后上方
 *       （{@link #escortPoint}），前面有东西挡着就往上抬。你落地她就收了重力，展翼滑下来（{@code LacertaCompanion.glide}）。</li>
 *   <li><b>追击</b>：够不着的敌人（天上的、高台上的），炽翼坠星破好了时飞到它头顶，接一记坠星（{@code LacertaMind.fight}）。</li>
 *   <li><b>护落</b>：你从高处掉下去、预计要摔掉三成以上生命或者会摔死时，她飞过来接住你（信赖以前 {@code catch_hand} 拉住手，信赖起
 *       {@code catch_embrace} 抱住），带你慢慢降下去。抓住以前的那一段掉落只算 {@link #REDUCE_HAND} / {@link #REDUCE_EMBRACE} 以外的，
 *       抓住以后不再累计；落地那一下最多摔到剩半颗心（{@link #onOwnerHurt}）。</li>
 * </ul>
 */
final class LacertaFlight {
    enum Mode { NONE, ESCORT, PURSUE, CATCH, CARRY }

    /** 伴飞的位置：在你身后、左边、比你的脚高多少格（巨龙离得远一些）。 */
    static final double ESCORT_BACK = 2.2, ESCORT_SIDE = 1.6, ESCORT_UP = 1.4;
    static final double DRAGON_BACK = 7, DRAGON_SIDE = 3, DRAGON_UP = 3.5;
    /** 巨龙够不着的敌人：悬停在它后方、上方多少格喷龙炎。 */
    static final double DRAGON_HOVER_BACK = 8, DRAGON_HOVER_UP = 4;
    /** 巨龙起飞、落地（{@code takeoff}、{@code land} 都是 1.8 秒）。 */
    static final int DRAGON_TAKEOFF = 36, DRAGON_LAND = 36;
    /** 你离地这么高、浮了这么多刻（又没在往下掉），算在飞。 */
    static final double ALOFT_HEIGHT = 2.5;
    static final int ALOFT_TICKS = 10;
    /** 往下掉得比这快（格/刻）不算飞，是在掉。 */
    static final double FALLING = -.4;
    /** 平时飞多快、最快多快（格/刻）；每刻往想要的速度靠多少。 */
    static final double CRUISE = .55, MAX_SPEED = 3.2, ACCEL = .35;
    /** 离伴飞点远过这么多就直接闪过去（地上的闪回会把她放到地上）。 */
    static final double FLY_BLINK = 20;
    /** 前面看多远有没有东西挡着；挡着时往上抬多快；撞到以后多少刻里只抬不降（翻过墙头再下去）。 */
    static final double LOOK_AHEAD = 2.5, CLIMB = .4;
    static final int CLIMB_HOLD = 12;
    /**
     * 追击：多远以内的够不着的敌人飞上去。炽翼坠星破是照玩家从地上跳起来砸做的：往上蹿、再往前下砸，砍中那一刻已经往前冲了约 5 格、
     * 比出手时低约 2.3 格，所以在它后方 {@link #PURSUE_BACK} 格、比它头顶高 {@link #PURSUE_ABOVE} 格出手，正好砸在它身上；
     * 离那一处差 {@link #STRIKE_SLACK} 格以内就出手。
     */
    static final double PURSUE_RANGE = 16, PURSUE_BACK = 4.8, PURSUE_ABOVE = 2.6, STRIKE_SLACK = 1;
    /** 主状态在飞的时候，水平每秒快过这么多播 {@code fly_move}，不然 {@code fly_hover}。 */
    static final double FLY_MOVE_SPEED = 2.5;
    /** 护落：离你多远以内来得及接；预计摔掉生命上限的这么多才接（会摔死的也接）；离地至少多高才接得住。 */
    static final double CATCH_RANGE = 32, CATCH_SHARE = .3, CATCH_MIN_HEIGHT = 4;
    /** 动画里抓住你的那一刻（{@code catch_hand} 的 catch 标记 0.5 秒、{@code catch_embrace} 的 0.56 秒）。 */
    static final int CATCH_HAND_AT = 10, CATCH_EMBRACE_AT = 11;
    /**
     * 带着你往下降每刻多快（格）。服务端的玩家这一刻还会再叠一次重力（0.08）才把速度发给客户端，所以设的时候先抵掉它，
     * 不然客户端那边降得快一倍多，她在画面上就跟不上你。
     */
    static final double CARRY_FALL = .18, PLAYER_GRAVITY = .08;
    /** 抓住以前那一段掉落减掉多少：拉住手 75%，抱住 80%。 */
    static final double REDUCE_HAND = .75, REDUCE_EMBRACE = .8;
    /** 最多带多久；接完以后多久才会再接；接住以后多久里摔伤保底（剩半颗心）。 */
    static final int CARRY_MAX = 400, CATCH_GAP = 40, SAFE_WINDOW = 60;

    /** 谁被她接住了、到哪一刻以前摔伤保底（主人的 UUID → 游戏刻）；在巨龙背上半空下来的，这一摔只受多少（UUID → 比例）。 */
    private static final Map<UUID, Long> SAFE = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> KEEP = new ConcurrentHashMap<>();
    /** 测试用：接过几次、保底挡下过几次。 */
    static int catches, saves;

    private final LacertaCompanion c;
    private Mode mode = Mode.NONE;
    private long steeredAt = -100, catchStart, carryStart, nextCatch, climbUntil;
    private int aloftTicks;
    private boolean embrace;
    private Vec3 from = Vec3.ZERO;
    /** 她在你哪一侧（yaw）。 */
    private float sideYaw;
    /** 巨龙在天上（收了重力往下滑也算），落地时播 {@code land}。 */
    private boolean dragonAloft;
    private double frozenFall, approachCap;
    /** 你上一刻在哪、这一刻挪了多少（服务端玩家的 xo/yo 跟实体更新的先后有关，靠不住，自己记）。 */
    private @Nullable Vec3 ownerPrev;
    private Vec3 ownerVel = Vec3.ZERO;

    LacertaFlight(LacertaCompanion c) {
        this.c = c;
    }

    boolean flying() {
        return c.isFlying();
    }

    Mode mode() {
        return mode;
    }

    boolean catching() {
        return mode == Mode.CATCH || mode == Mode.CARRY;
    }

    boolean carrying() {
        return mode == Mode.CARRY;
    }

    // ---------------------------------------------------------------- 起飞、落地

    /** 起得来：不在逼仄的地方（巨龙要一块空地）、水里、坐着、龙眠、睡着。 */
    boolean canTakeOff() {
        boolean room = c.isDragonForm() ? c.level() instanceof ServerLevel level && LacertaDragon.roomAt(level, c.position()) : !c.cramped();
        return room && !c.isInWater() && !c.isPassenger() && !c.withdrawing() && !c.curled();
    }

    void takeOff() {
        if (flying()) return;
        c.setFlying(true);
        c.setNoGravity(true);
        c.getNavigation().stop();
        c.setSitting(false);
        Vec3 v = c.getDeltaMovement();
        c.setDeltaMovement(v.x, Math.max(v.y, .3), v.z);
        if (c.isDragonForm()) {
            dragonAloft = true;
            if (c.onGround()) c.mood.play("takeoff", DRAGON_TAKEOFF);
        }
        if (c.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CLOUD, c.getX(), c.getY() + .1, c.getZ(), 6, .35, .05, .35, .03);
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, .45f, 1.6f);
        }
    }

    /** 收了重力：展翼滑下来（不摔伤）。 */
    void land() {
        if (mode == Mode.ESCORT || mode == Mode.PURSUE) mode = Mode.NONE;
        if (!flying()) return;
        c.setFlying(false);
        c.setNoGravity(false);
        // 在半空收了重力：马上展翼滑（不先掉 3 格）
        if (!c.onGround()) c.fallDistance = Math.max(c.fallDistance, LacertaCompanion.GLIDE_AFTER);
    }

    // ---------------------------------------------------------------- 每刻

    void tick(ServerLevel level, @Nullable ServerPlayer p, long now) {
        if (p == null) {
            if (catching()) release(level, null, now, false);
            land();
            return;
        }
        ownerVel = ownerPrev == null ? Vec3.ZERO : p.position().subtract(ownerPrev);
        ownerPrev = p.position();
        boolean air = !p.onGround() && !p.isInWater() && !p.onClimbable() && !p.isPassenger();
        aloftTicks = air ? aloftTicks + 1 : 0;
        if (catching()) {
            tickCatch(level, p, now);
            return;
        }
        // 这一刻心智没让她飞（伴飞、追击都是每刻调一次）：收了重力滑下来
        if (flying() && now - steeredAt > 2) land();
        // 巨龙落地：四足吸震
        if (dragonAloft && c.onGround()) {
            dragonAloft = false;
            if (c.isDragonForm() && !c.isVehicle()) c.mood.play("land", DRAGON_LAND);
        }
        // 接住你是第五印星穹怀抱
        if (now >= nextCatch && c.seal.has(LacertaSeals.CATCH) && shouldCatch(level, p)) startCatch(level, p, now);
    }

    /**
     * 往 {@code to} 飞，最快 {@code speed} 格/刻。前面有东西挡着就往上抬，之后 {@link #CLIMB_HOLD} 刻里只抬不降（翻过墙头再下去）；
     * 往斜下方飞的路上有东西也先不降。身子朝着飞的方向。
     */
    void steer(Vec3 to, double speed) {
        long now = c.level().getGameTime();
        Vec3 d = to.subtract(c.position());
        double len = d.length();
        Vec3 want = len < .05 ? Vec3.ZERO : d.scale(Math.min(speed, len * .35 + .02) / len);
        Vec3 flat = new Vec3(want.x, 0, want.z);
        if (flat.lengthSqr() > 1e-4 && blocked(flat.normalize())) {
            climbUntil = now + CLIMB_HOLD;
            want = new Vec3(want.x * .5, Math.max(want.y, CLIMB), want.z * .5);
        } else if (now < climbUntil || want.y < 0 && want.lengthSqr() > 1e-4 && blocked(want.normalize())) {
            want = new Vec3(want.x, Math.max(want.y, 0), want.z);
        }
        Vec3 v = c.getDeltaMovement();
        c.setDeltaMovement(v.add(want.subtract(v).scale(ACCEL)));
        c.getNavigation().stop();
        if (flat.length() > .12) {
            float yaw = (float) (Mth.atan2(-want.x, want.z) * Mth.RAD_TO_DEG);
            c.setYRot(yaw);
            c.yBodyRot = yaw;
        }
    }

    /** 往 {@code dir} 看 {@link #LOOK_AHEAD} 格（加上她的半宽），脚边、腰、头边（巨龙再加左右两边）有没有东西挡着。 */
    private boolean blocked(Vec3 dir) {
        double w = c.getBbWidth() * .45, h = c.getBbHeight();
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        double[] lateral = c.isDragonForm() ? new double[]{-w, 0, w} : new double[]{0};
        for (double y : new double[]{.3, h * .5, h - .25})
            for (double x : lateral) {
                Vec3 a = c.position().add(side.scale(x)).add(0, y, 0);
                Vec3 b = a.add(dir.scale(LOOK_AHEAD + c.getBbWidth() * .5));
                if (c.level().clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, c)).getType() != HitResult.Type.MISS) return true;
            }
        return false;
    }

    // ---------------------------------------------------------------- 伴飞

    /** 你在飞（不是在往下掉）。 */
    boolean ownerAloft(ServerPlayer p) {
        if (p.getAbilities().flying || p.isFallFlying()) return true;
        double vy = ownerVel.y;
        return aloftTicks >= ALOFT_TICKS && vy > FALLING && groundBelow(c.level(), p.position(), 16) >= ALOFT_HEIGHT;
    }

    /** 心智跟随时每刻调一次：你在飞就跟着飞（返回真），不然返回假照地上的走。 */
    boolean escort(ServerPlayer p, long now) {
        if (!ownerAloft(p)) return false;
        if (!flying()) {
            if (!canTakeOff()) return false;
            takeOff();
        }
        mode = Mode.ESCORT;
        steeredAt = now;
        double owner = ownerVel.length();
        Vec3 to = c.isDragonForm() ? point(p.position(), p.getYRot(), DRAGON_BACK, DRAGON_SIDE, DRAGON_UP) : escortPoint(p.position(), p.getYRot());
        steer(to, Math.min(MAX_SPEED, Math.max(CRUISE, owner * 1.3 + .15)));
        if (c.getDeltaMovement().horizontalDistance() < .12) c.getLookControl().setLookAt(p, 30, 30);
        return true;
    }

    /** 伴飞点：你（脚下 {@code at}、朝 {@code yaw}）身后 {@link #ESCORT_BACK}、左边 {@link #ESCORT_SIDE}、高 {@link #ESCORT_UP}。 */
    static Vec3 escortPoint(Vec3 at, float yaw) {
        return point(at, yaw, ESCORT_BACK, ESCORT_SIDE, ESCORT_UP);
    }

    /** 你（脚下 {@code at}、朝 {@code yaw}）身后 {@code back}、左边 {@code side}、高 {@code up} 的那一处。 */
    static Vec3 point(Vec3 at, float yaw, double back, double side, double up) {
        double r = Math.toRadians(yaw);
        Vec3 forward = new Vec3(-Math.sin(r), 0, Math.cos(r)), left = new Vec3(Math.cos(r), 0, Math.sin(r));
        return at.add(forward.scale(-back)).add(left.scale(side)).add(0, up, 0);
    }

    /** 飞着离你太远（你飞得太快、她被挡住）：直接闪到伴飞点（那里没东西挡着才闪）。返回真时这一刻不再做别的。 */
    boolean reflex(ServerLevel level, ServerPlayer p, double dist) {
        if (!flying() || mode != Mode.ESCORT || dist <= FLY_BLINK) return false;
        Vec3 to = c.isDragonForm() ? point(p.position(), p.getYRot(), DRAGON_BACK, DRAGON_SIDE, DRAGON_UP) : escortPoint(p.position(), p.getYRot());
        if (!level.noCollision(c, c.getBoundingBox().move(to.subtract(c.position())))) return false;
        c.puff(level, c.position().add(0, .7, 0));
        c.teleportTo(to.x, to.y, to.z);
        c.puff(level, to.add(0, .7, 0));
        c.blinkSound(level, to);
        return true;
    }

    // ---------------------------------------------------------------- 追击

    /** 够得着去追：多远以内、看得见、没在水里、比她高不过 12 格、她起得来。 */
    boolean canPursue(LivingEntity t) {
        return c.distanceTo(t) <= PURSUE_RANGE && !t.isInWater() && t.getY() - c.getY() <= 12 && c.hasLineOfSight(t) && (flying() || canTakeOff());
    }

    /** 飞到出手的那一处：{@code t} 后方（她这一侧）{@link #PURSUE_BACK} 格、比它头顶高 {@link #PURSUE_ABOVE} 格。 */
    void pursue(LivingEntity t, long now) {
        if (!flying()) takeOff();
        mode = Mode.PURSUE;
        steeredAt = now;
        steer(strikePoint(t), 1.1);
        c.getLookControl().setLookAt(t, 40, 40);
    }

    private Vec3 strikePoint(LivingEntity t) {
        Vec3 back = c.position().subtract(t.position()).multiply(1, 0, 1);
        back = back.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, c.getYRot()).scale(-1) : back.normalize();
        return t.position().add(back.scale(PURSUE_BACK)).add(0, t.getBbHeight() + PURSUE_ABOVE, 0);
    }

    /** 巨龙：飞到够不着的 {@code t} 后方 {@link #DRAGON_HOVER_BACK}、上方 {@link #DRAGON_HOVER_UP} 格悬停（在那里喷龙炎）。 */
    void dragonHover(LivingEntity t, long now) {
        if (!flying()) takeOff();
        mode = Mode.PURSUE;
        steeredAt = now;
        Vec3 back = c.position().subtract(t.position()).multiply(1, 0, 1);
        back = back.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, c.getYRot()).scale(-1) : back.normalize();
        steer(t.position().add(back.scale(DRAGON_HOVER_BACK)).add(0, DRAGON_HOVER_UP, 0), 1.2);
        c.getLookControl().setLookAt(t, 40, 40);
    }

    /** 飞到了出手的那一处（差 {@link #STRIKE_SLACK} 格以内）。 */
    boolean inStrikeRange(LivingEntity t) {
        return flying() && c.position().distanceTo(strikePoint(t)) <= STRIKE_SLACK;
    }

    // ---------------------------------------------------------------- 护落

    /** 原版的摔伤：掉了 {@code distance} 格、安全高度 {@code safe}、倍率 {@code multiplier}。 */
    static double fallDamage(double distance, double safe, double multiplier) {
        return Math.max(0, Math.ceil((distance - safe) * multiplier));
    }

    /** 值得接：预计的摔伤会摔死（剩不到 1 点），或者有生命上限的 {@link #CATCH_SHARE}。 */
    static boolean worthCatching(double predicted, double health, double maxHealth) {
        return predicted > 0 && (predicted >= health - 1 || predicted >= maxHealth * CATCH_SHARE);
    }

    /** 接你的样子：信赖以前拉住手，信赖起抱住。 */
    static String clipFor(CompanionBond.Stage stage) {
        return stage.ordinal() >= CompanionBond.Stage.TRUSTED.ordinal() ? "catch_embrace" : "catch_hand";
    }

    /** 抓住以前那一段掉落减掉以后还剩多少格。 */
    static double reducedFall(double fallDistance, boolean embrace) {
        return fallDistance * (1 - (embrace ? REDUCE_EMBRACE : REDUCE_HAND));
    }

    /** 从 {@code at} 往下多远是实心的地面（落在水里、岩浆里算没有地面：-1；{@code max} 格以内都没有：{@code max}）。 */
    static double groundBelow(net.minecraft.world.level.Level level, Vec3 at, double max) {
        var hit = level.clip(new ClipContext(at, at.subtract(0, max, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return max;
        if (!level.getFluidState(hit.getBlockPos()).isEmpty()) return -1;
        return at.y - hit.getLocation().y;
    }

    private boolean shouldCatch(ServerLevel level, ServerPlayer p) {
        if (c.isDragonForm()) return false;
        if (p.isCreative() || p.isSpectator() || p.getAbilities().flying || p.isFallFlying() || p.isPassenger() || p.isInWater() || p.onGround()
                || p.hasEffect(MobEffects.SLOW_FALLING) || p.isDeadOrDying()) return false;
        if (c.withdrawing() || c.isPassenger() || c.distanceTo(p) > CATCH_RANGE) return false;
        if (ownerVel.y > -.3) return false;
        double ground = groundBelow(level, p.position(), 128);
        if (ground < CATCH_MIN_HEIGHT) return false;
        double predicted = fallDamage(p.fallDistance + ground, p.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                p.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER));
        return worthCatching(predicted, p.getHealth() + p.getAbsorptionAmount(), p.getMaxHealth());
    }

    private void startCatch(ServerLevel level, ServerPlayer p, long now) {
        String clip = clipFor(CompanionBond.Stage.of(c.bond(p).affection()));
        embrace = clip.equals("catch_embrace");
        mode = Mode.CATCH;
        catchStart = now;
        from = c.position();
        Vec3 s = c.position().subtract(p.position()).multiply(1, 0, 1);
        sideYaw = s.lengthSqr() < .01 ? p.getYRot() : (float) (Mth.atan2(-s.x, s.z) * Mth.RAD_TO_DEG);
        double ground = groundBelow(level, p.position(), 128);
        approachCap = Mth.clamp((ground - 1.5) / catchAt(), .15, .7);
        c.blade.drop();
        c.rest.rise(level, true);
        c.mood.cut();
        c.mood.pose(clip);
        takeOff();
        catches++;
    }

    private int catchAt() {
        return embrace ? CATCH_EMBRACE_AT : CATCH_HAND_AT;
    }

    /** 抓住时她在哪（{@link LacertaCompanion#carryOffset}）。 */
    private Vec3 attachPoint(ServerPlayer p) {
        return p.position().add(LacertaCompanion.carryOffset(embrace, sideYaw));
    }

    private void tickCatch(ServerLevel level, ServerPlayer p, long now) {
        int t = (int) (now - catchStart);
        boolean free = p.getAbilities().flying || p.isFallFlying();
        if (!p.isAlive() || p.isRemoved() || p.onGround() || p.isInWater() || free || c.distanceTo(p) > CATCH_RANGE * 1.5) {
            release(level, p, now, p.isAlive() && (p.onGround() || p.isInWater()));
            return;
        }
        Vec3 attach = attachPoint(p);
        faceOwner(p);
        if (mode == Mode.CATCH) {
            double k = Math.min(1, (t + 1) / (double) catchAt());
            k = k * k * (3 - 2 * k);
            moveTo(from.lerp(attach, k));
            limitFall(p, ownerVel, approachCap);
            if (t + 1 < catchAt()) return;
            if (c.position().distanceTo(attach) > 1.2) {
                if (!level.noCollision(c, c.getBoundingBox().move(attach.subtract(c.position())))) {
                    // 被挡住了够不着：放弃（你照常掉）
                    release(level, p, now, false);
                    return;
                }
                c.setPos(attach);
            }
            mode = Mode.CARRY;
            carryStart = now;
            c.setCarry(p.getId(), embrace, sideYaw);
            frozenFall = reducedFall(p.fallDistance, embrace);
            SAFE.put(p.getUUID(), now + CARRY_MAX + SAFE_WINDOW);
            level.sendParticles(ParticleTypes.CLOUD, c.getX(), c.getY() + .8, c.getZ(), 10, .6, .3, .6, .05);
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, .8f, 1.3f);
            CompanionLines.say(c, p, embrace ? "catch_embrace" : "catch_hand", 40);
            return;
        }
        // 带着你往下降：你的掉落停在减过的那一截，不再累计
        moveTo(attach);
        p.fallDistance = frozenFall;
        p.setDeltaMovement(ownerVel.x * .6, -CARRY_FALL + PLAYER_GRAVITY, ownerVel.z * .6);
        p.hurtMarked = true;
        if (now - carryStart > CARRY_MAX || c.position().distanceTo(attach) > 3) release(level, p, now, false);
    }

    /** 带着碰撞挪到 {@code to}（挡着就停在挡住的地方）。 */
    private void moveTo(Vec3 to) {
        c.setDeltaMovement(to.subtract(c.position()));
        c.getNavigation().stop();
    }

    /** 接近的那几刻：你往下掉得别比 {@code cap} 快（她扇起的风托着你），好让她赶得上。 */
    private static void limitFall(ServerPlayer p, Vec3 vel, double cap) {
        if (vel.y >= -cap) return;
        p.setDeltaMovement(vel.x, -cap + PLAYER_GRAVITY, vel.z);
        p.hurtMarked = true;
    }

    private void faceOwner(ServerPlayer p) {
        float yaw = (float) (Mth.atan2(p.getX() - c.getX(), p.getZ() - c.getZ()) * -Mth.RAD_TO_DEG);
        c.setYRot(yaw);
        c.yBodyRot = yaw;
        c.setYHeadRot(yaw);
    }

    /** 放开你（落地了、你飞起来了、她够不着了）：落地时红着脸数落你一句。 */
    void release(ServerLevel level, @Nullable ServerPlayer p, long now, boolean landed) {
        boolean held = mode == Mode.CARRY;
        mode = Mode.NONE;
        c.setCarry(-1, false, 0);
        // 接住过你：歇 5 分钟（第 4 节第五印）；没接住（够不着、被挡）的过一会就能再接
        nextCatch = now + (held ? LacertaSeals.CATCH_COOLDOWN : CATCH_GAP);
        c.mood.pose(null);
        land();
        if (p == null) return;
        if (held) SAFE.put(p.getUUID(), now + SAFE_WINDOW);
        if (held && landed) {
            CompanionLines.say(c, p, embrace ? "catch_after_embrace" : "catch_after_hand", 40);
            c.mood.act("act_hmph");
        }
    }

    /** 在巨龙背上半空下来（{@link LacertaRide#dismounted}）：之后 {@link #CARRY_MAX} 刻里摔下去只受 {@code keep}，至少留半颗心。 */
    static void protectFall(ServerPlayer p, long now, double keep) {
        SAFE.put(p.getUUID(), now + CARRY_MAX);
        KEEP.put(p.getUUID(), keep);
    }

    /** 被她接住的那一摔：落地那一下最多摔到剩半颗心（从巨龙背上下来的再先打折）。 */
    static void onOwnerHurt(LivingDamageEvent.Pre e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !e.getSource().is(DamageTypeTags.IS_FALL)) return;
        Long until = SAFE.get(p.getUUID());
        if (until == null) return;
        if (p.level().getGameTime() > until) {
            SAFE.remove(p.getUUID());
            KEEP.remove(p.getUUID());
            return;
        }
        Double keep = KEEP.remove(p.getUUID());
        if (keep != null) e.setNewDamage((float) (e.getNewDamage() * keep));
        float max = Math.max(0, p.getHealth() - 1);
        if (e.getNewDamage() > max) {
            e.setNewDamage(max);
            saves++;
        }
    }

    String describe() {
        return mode.name().toLowerCase(java.util.Locale.ROOT) + (flying() ? " flying" : "");
    }
}
