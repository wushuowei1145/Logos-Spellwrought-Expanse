package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.runtime.PlayerMagic;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 拉塞塔化身巨龙（接入方案第五节，docs/LACERTA_COMPANION.md 第 19 节）：同一个实体，同步的「巨龙」标记切换形态，碰撞箱变成
 * {@link #SIZE}，客户端换模型 {@code lacerta_dragon_beast}。
 * <ul>
 *   <li>生命上限、护甲、韧性翻倍（变身、变回都保持血量百分比），击退抗性 0.95，挨的每一下只受 {@link #TAKEN}。</li>
 *   <li>你出魔力维持：每秒 {@link #upkeep}（创造模式不扣）；不够了她变回人形。</li>
 *   <li>重爪裂击（{@code claw_attack}）、古龙龙炎吐息（{@code breath_start → breath_loop → breath_end}，叠在颈首上，地上空中都能喷）。</li>
 *   <li>变身要 {@link #ROOM_W}×{@link #ROOM_H}×{@link #ROOM_W} 的空地，你的魔力至少够维持 {@link #UPKEEP_RESERVE} 秒。</li>
 *   <li>以巨龙登场（{@code appear}）：落地、昂首一吼，{@link #TAUNT_RADIUS} 格内的敌人都冲她来。</li>
 * </ul>
 */
public final class LacertaDragon {
    /** 巨龙的碰撞箱（宽、高、眼高）。 */
    static final EntityDimensions SIZE = EntityDimensions.scalable(3.2f, 2.4f).withEyeHeight(1.85f);
    /** 变身要的空地（宽、高，格）。 */
    static final int ROOM_W = 5, ROOM_H = 4;
    /** 你的魔力至少够维持几秒才变身、才以巨龙登场。 */
    static final int UPKEEP_RESERVE = 5;
    /** 挨的每一下只受多少（同末影龙的躯干）；击退抗性。 */
    static final double TAKEN = .25, KNOCKBACK = .95;
    /** 登场（{@code appear} 2.8 秒）：第几刻落地震地、第几刻昂首一吼。 */
    static final int APPEAR_TICKS = 56, APPEAR_LAND = 14, APPEAR_ROAR = 25;
    /** 巨龙的嘲讽半径。 */
    static final double TAUNT_RADIUS = 24;
    /** 重爪裂击（{@code claw_attack} 1.4 秒，0.62 秒砍到）：多远、多宽、几倍攻击、往外推多猛；之后多久能再挥。 */
    static final int CLAW_TICKS = 28, CLAW_HIT = 12, CLAW_COOLDOWN = 36;
    static final double CLAW_REACH = 5.5, CLAW_CONE = 120, CLAW_MULT = 2.6, CLAW_PUSH = 1.2;
    /** 龙炎吐息：蓄压（{@code breath_start} 0.75 秒）后每几刻喷一下、喷多远、多宽（半角）、每一下几倍攻击；收（{@code breath_end} 0.65 秒）。 */
    public static final int BREATH_START = 15, BREATH_END = 13, BREATH_PULSE = 4;
    public static final double BREATH_RANGE = 18, BREATH_HALF = 22, BREATH_MULT = .25;
    /** 嘴在哪（她朝前、往上多少格：{@code breath_loop} 里颈首压低时 {@code MouthLocator} 的位置，2 倍模型缩 0.5 以后）。 */
    public static final double MOUTH_FORWARD = 4.6, MOUTH_UP = 1.5;
    /** 变身时额外乘上的属性（生命、护甲、韧性 ×2）。 */
    private static final net.minecraft.resources.Identifier BOOST = LogosDimension.id("lacerta_dragon");

    private final LacertaCompanion c;
    /** 这一次是她自己打起来变的（打完自己变回），不是你在对话里让她变的。 */
    private boolean auto;
    private long appearAt = -1, clawAt = -1, nextClaw, breathAt = -1, breathStop = -1;
    private @Nullable Predicate<LivingEntity> breathFoe;
    /** 龙炎往哪喷（每刻问一次：AI 对着目标，骑着时对着你的视线）。 */
    private @Nullable java.util.function.Supplier<Vec3> breathAim;

    LacertaDragon(LacertaCompanion c) {
        this.c = c;
    }

    boolean auto() {
        return auto;
    }

    /** 你每秒要出多少魔力维持巨龙：50 起，每枚鳞之契少 2，最少 20。 */
    static int upkeep(int seals) {
        return Math.max(20, 50 - 2 * Math.max(0, seals));
    }

    // ---------------------------------------------------------------- 变身、变回

    /** 她站的地方有没有 {@link #ROOM_W}×{@link #ROOM_H}×{@link #ROOM_W} 的空地（以她脚下为中心）。 */
    static boolean roomAt(ServerLevel level, Vec3 feet) {
        BlockPos base = BlockPos.containing(feet);
        int half = ROOM_W / 2;
        for (int x = -half; x <= half; x++)
            for (int y = 0; y < ROOM_H; y++)
                for (int z = -half; z <= half; z++) {
                    BlockPos at = base.offset(x, y, z);
                    if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return false;
                }
        return true;
    }

    /** 你的魔力够不够她维持 {@code seconds} 秒（创造模式总是够）。 */
    static boolean affordable(ServerPlayer p, int seals, int seconds) {
        return p.getAbilities().instabuild || PlayerMagic.of(p).mana >= upkeep(seals) * (double) seconds;
    }

    /** 变不了的原因（能变返回 null）：{@code room} 地方太挤、{@code mana} 你的魔力不够。 */
    @Nullable String cannotTransform(ServerLevel level, ServerPlayer p) {
        // 太古真身是第九印
        if (!LacertaSeals.has(c.bond(p).seals(), LacertaSeals.DRAGON)) return "seal";
        if (!roomAt(level, c.position())) return "room";
        if (!affordable(p, c.bond(p).seals(), UPKEEP_RESERVE)) return "mana";
        return null;
    }

    /** 化身巨龙：{@code auto} 是她自己打起来变的。成功返回真。 */
    boolean transform(ServerLevel level, ServerPlayer p, boolean auto) {
        if (c.isDragonForm() || c.withdrawing() || cannotTransform(level, p) != null) return false;
        this.auto = auto;
        c.blade.drop();
        c.seal.stop(level);
        c.rest.rise(level, true);
        c.flight.land();
        c.mood.cut();
        setForm(true);
        burst(level);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.NEUTRAL, 1f, 1.1f);
        p.setData(Companions.LACERTA_DAYS, LacertaDays.of(p).withDragonForm(!auto));
        transforms++;
        return true;
    }

    /** 变回人形（你让的、打完了、魔力不够、龙眠）：下次以人形登场。巨龙形态下收回、下线的不走这里（{@link LacertaCompanion#rememberForm}）。 */
    void revert(ServerLevel level, @Nullable ServerPlayer p) {
        if (!c.isDragonForm()) return;
        c.rest.rise(level, true);
        stopBreath(level);
        clawAt = -1;
        c.setMove("");
        c.ejectPassengers();
        setForm(false);
        burst(level);
        if (p != null) p.setData(Companions.LACERTA_DAYS, LacertaDays.of(p).withDragonForm(false));
        auto = false;
        LacertaMind mind = c.mind();
        if (mind != null) mind.deferAuto(level.getGameTime());
    }

    /** 换形态：碰撞箱、属性（保持血量百分比）。 */
    private void setForm(boolean dragon) {
        float ratio = c.getHealth() / Math.max(1, c.getMaxHealth());
        c.setDragonForm(dragon);
        boost(Attributes.MAX_HEALTH, dragon ? 1 : 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        boost(Attributes.ARMOR, dragon ? 1 : 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        boost(Attributes.ARMOR_TOUGHNESS, dragon ? 1 : 0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        boost(Attributes.KNOCKBACK_RESISTANCE, dragon ? KNOCKBACK - LacertaStats.KNOCKBACK : 0, AttributeModifier.Operation.ADD_VALUE);
        // 骑着时第三人称的镜头拉远（原版的坐骑镜头距离，快乐恶魂也用它），不然镜头埋在龙身里
        AttributeInstance camera = c.getAttribute(Attributes.CAMERA_DISTANCE);
        if (camera != null) boost(Attributes.CAMERA_DISTANCE, dragon ? LacertaRide.CAMERA - camera.getBaseValue() : 0, AttributeModifier.Operation.ADD_VALUE);
        c.setHealth(Math.max(1, ratio * c.getMaxHealth()));
    }

    private void boost(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double amount,
                       AttributeModifier.Operation op) {
        AttributeInstance i = c.getAttribute(attribute);
        if (i == null) return;
        if (amount == 0) i.removeModifier(BOOST);
        else i.addOrUpdateTransientModifier(new AttributeModifier(BOOST, amount, op));
    }

    /** 变身、变回的那一下：一大团樱粉赤金的龙炎遮住身形。 */
    private void burst(ServerLevel level) {
        Vec3 at = c.position().add(0, 1.2, 0);
        FoxfireCue.burst(level, at, FoxfireFlame.DRAGON, 2.6f);
        level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(LacertaCompanion.CORAL, 1.6f), at.x, at.y, at.z, 40, 1.4, 1, 1.4, 0);
        level.sendParticles(ParticleTypes.CLOUD, at.x, c.getY() + .2, at.z, 20, 1.6, .1, 1.6, .05);
    }

    // ---------------------------------------------------------------- 登场

    /** 以巨龙登场（召唤时）：变好、播 {@code appear}。 */
    void appear() {
        setForm(true);
        appearAt = c.level().getGameTime();
        c.mood.play("appear", APPEAR_TICKS);
    }

    boolean appearing() {
        return appearAt >= 0;
    }

    // ---------------------------------------------------------------- 每刻

    void tick(ServerLevel level, @Nullable ServerPlayer p, long now) {
        if (!c.isDragonForm()) return;
        if (appearAt >= 0) tickAppear(level, now - appearAt);
        if (p != null && !p.getAbilities().instabuild) {
            PlayerMagic m = PlayerMagic.of(p);
            double cost = upkeep(c.bond(p).seals()) / 20.0;
            if (m.mana < cost) {
                CompanionLines.say(c, p, "dragon_mana_out", 40);
                revert(level, p);
                return;
            }
            m.mana -= cost;
        }
        if (clawAt >= 0) tickClaw(level, now - clawAt);
        if (breathAt >= 0 || breathStop >= 0) tickBreath(level, now);
    }

    private void tickAppear(ServerLevel level, long t) {
        if (t == APPEAR_LAND) {
            level.sendParticles(ParticleTypes.CLOUD, c.getX(), c.getY() + .2, c.getZ(), 40, 2.2, .1, 2.2, .08);
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL, .6f, .7f);
        }
        if (t == APPEAR_ROAR) {
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.NEUTRAL, 1.2f, .9f);
            FoxfireCue.burst(level, mouth(), FoxfireFlame.DRAGON, 1.8f);
            LacertaMind mind = c.mind();
            if (mind != null) mind.taunt(level, level.getGameTime(), true);
        }
        if (t >= APPEAR_TICKS) appearAt = -1;
    }

    // ---------------------------------------------------------------- 重爪裂击

    /** 正在挥爪（{@code claw_attack} 播着）。 */
    boolean clawing() {
        return clawAt >= 0;
    }

    boolean clawReady(long now) {
        return clawAt < 0 && breathAt < 0 && now >= nextClaw && appearAt < 0;
    }

    /** 挥一爪（对着她身前）。 */
    boolean claw(long now) {
        if (!clawReady(now)) return false;
        clawAt = now;
        nextClaw = now + CLAW_TICKS + CLAW_COOLDOWN;
        c.setMove("claw_attack|" + now + "|1.0|false");
        claws++;
        return true;
    }

    private void tickClaw(ServerLevel level, long t) {
        if (t == CLAW_HIT) clawHit(level);
        if (t >= CLAW_TICKS) {
            clawAt = -1;
            c.setMove("");
        }
    }

    private void clawHit(ServerLevel level) {
        Vec3 fwd = Vec3.directionFromRotation(0, c.getYRot());
        Vec3 center = c.position().add(0, 1, 0);
        double damage = c.attack() * CLAW_MULT;
        boolean any = false;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(CLAW_REACH), this::foe)) {
            Vec3 to = e.position().add(0, e.getBbHeight() * .5, 0).subtract(center);
            double flat = Math.sqrt(to.x * to.x + to.z * to.z);
            if (LacertaBlade.nearest(e.getBoundingBox(), center).distanceTo(center) > CLAW_REACH) continue;
            if (flat > 1.2 && (to.x * fwd.x + to.z * fwd.z) / flat < Math.cos(Math.toRadians(CLAW_CONE / 2))) continue;
            float before = e.getHealth();
            e.invulnerableTime = 0;
            Dragonfire.deal(c, e, damage, true);
            if (e.getHealth() < before || !e.isAlive()) any = true;
            Vec3 push = flat < 1e-3 ? fwd : new Vec3(to.x / flat, 0, to.z / flat);
            e.push(push.x * CLAW_PUSH, .35, push.z * CLAW_PUSH);
            e.hurtMarked = true;
        }
        if (any) clawHits++;
        Vec3 at = center.add(fwd.scale(3));
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 3, 1.2, .3, 1.2, 0);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 1.2f, .6f);
        if (any) c.fought(level.getGameTime());
    }

    // ---------------------------------------------------------------- 龙炎吐息

    boolean breathing() {
        return breathAt >= 0;
    }

    boolean breathReady() {
        return breathAt < 0 && breathStop < 0 && clawAt < 0 && appearAt < 0;
    }

    /** 开始喷：{@code aim} 每刻给一次往哪喷（单位向量）。 */
    boolean startBreath(java.util.function.Supplier<Vec3> aim) {
        if (!breathReady()) return false;
        long now = c.level().getGameTime();
        breathAt = now;
        breathAim = aim;
        c.setBreathing(true, now);
        breaths++;
        if (c.level() instanceof ServerLevel level) {
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.NEUTRAL, .8f, 1.3f);
        }
        return true;
    }

    void stopBreath(ServerLevel level) {
        if (breathAt < 0) return;
        breathAt = -1;
        breathAim = null;
        long now = level.getGameTime();
        breathStop = now;
        c.setBreathing(false, now);
    }

    private void tickBreath(ServerLevel level, long now) {
        if (breathAt < 0) {
            if (now - breathStop >= BREATH_END) breathStop = -1;
            return;
        }
        long t = now - breathAt;
        if (t < BREATH_START) {
            if (t == BREATH_START / 3) FoxfireCue.burst(level, mouth(), FoxfireFlame.DRAGON, .5f);
            return;
        }
        Vec3 dir = breathAim == null ? Vec3.directionFromRotation(0, c.getYRot()) : breathAim.get();
        if (dir.lengthSqr() < 1e-4) return;
        dir = dir.normalize();
        // 身子转向喷的方向（骑着时她朝你的视线）；往哪喷同步给客户端，火由客户端每刻沿这道方向画（LacertaBreathClient）
        float yaw = (float) (Mth.atan2(-dir.x, dir.z) * Mth.RAD_TO_DEG);
        if (c.getControllingPassenger() == null) {
            c.setYRot(yaw);
            c.yBodyRot = yaw;
        }
        c.setYHeadRot(yaw);
        c.setBreathDir(dir);
        if ((t - BREATH_START) % BREATH_PULSE != 0) return;
        Vec3 mouth = mouth();
        if ((t - BREATH_START) % (BREATH_PULSE * 3) == 0) {
            level.playSound(null, mouth.x, mouth.y, mouth.z, SoundEvents.BLAZE_SHOOT, SoundSource.NEUTRAL, 1f, .6f);
        }
        double damage = c.attack() * BREATH_MULT;
        double cos = Math.cos(Math.toRadians(BREATH_HALF));
        AABB box = new AABB(mouth, mouth.add(dir.scale(BREATH_RANGE))).inflate(BREATH_RANGE * .45);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, this::foe)) {
            Vec3 to = e.position().add(0, e.getBbHeight() * .5, 0).subtract(mouth);
            double len = to.length();
            if (len > BREATH_RANGE + e.getBbWidth() || len < 1e-3 || to.dot(dir) / len < cos) continue;
            if (!c.hasLineOfSight(e)) continue;
            float before = e.getHealth();
            e.invulnerableTime = 0;
            Dragonfire.deal(c, e, damage, true);
            if (e.getHealth() < before || !e.isAlive()) breathHits++;
        }
        c.fought(now);
    }

    /** 嘴的位置：她朝前 {@link #MOUTH_FORWARD}、往上 {@link #MOUTH_UP}。 */
    Vec3 mouth() {
        return mouth(c.position(), c.yBodyRot);
    }

    /** 站在 {@code at}、身子朝 {@code bodyYaw} 时嘴在哪（客户端画龙炎也用它）。 */
    public static Vec3 mouth(Vec3 at, float bodyYaw) {
        return at.add(Vec3.directionFromRotation(0, bodyYaw).scale(MOUTH_FORWARD)).add(0, MOUTH_UP, 0);
    }

    /** 龙爪、龙炎打谁：她当作敌人的（不伤你、你的宠物和同伴）。 */
    private boolean foe(LivingEntity e) {
        return e != c && e.isAlive() && !c.hasPassenger(e) && c.canHarm(e) && c.hostileTo(e);
    }

    // ---------------------------------------------------------------- 测试用

    static int transforms, claws, clawHits, breaths, breathHits;
}
