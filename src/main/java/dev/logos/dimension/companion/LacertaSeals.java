package dev.logos.dimension.companion;

import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import org.jspecify.annotations.Nullable;

/**
 * 鳞之契（docs/LACERTA_COMPANION.md 第 4 节）：每盖一枚，第 {@code n} 枚解锁第 {@code n} 项本事。本来就会的（炽焰长剑、赤龙威压、真龙之鳞、
 * 掌心赤焰、展翼滑翔）不在这里；锁在契印后面的：
 * <ol>
 *   <li>{@link #SHELTER 绯翼庇护}：你在她身边挨打，她一侧巨翼拢过来，这一下的三成转到她身上（按她的护甲、魔咒算）。</li>
 *   <li>{@link #ARTS 龙骑武艺}：格挡、弹反和弹开后的破军龙牙（{@link LacertaBlade#tryBlock}）。</li>
 *   <li>{@link #GUST 烈风振翼}：被围住时双翼下扇，掀开身边的敌人。</li>
 *   <li>{@link #SKYFALL 陨星坠落}：炽翼坠星破、空中追击（{@link LacertaMind}）。</li>
 *   <li>{@link #CATCH 星穹怀抱}：你从高处掉下来时飞过来接住你，接住一次歇 5 分钟（{@link LacertaFlight}）。</li>
 *   <li>{@link #BREATH 赤穹龙息}：人形也能喷 2 秒的扇形龙炎，点燃扎堆的敌人。</li>
 *   <li>{@link #RISE 燃烬不屈}：致命的一下不倒，单膝撑地、龙炎绕身再站起来，回一半生命、5 秒减伤；每天一次。</li>
 *   <li>{@link #WRATH 触龙逆鳞}：你的生命跌破三成时，攻击 +30%、周身燃火 10 秒，先收拾伤你的那个。</li>
 *   <li>{@link #DRAGON 太古真身}：化身巨龙（{@link LacertaDragon}）。</li>
 * </ol>
 * 模型里还没有的动作先借现成的（第 13 节的 {@code wing_shelter}、{@code wing_gust}、{@code rise_again}、人形 {@code breath} 做好再换）：
 * 翼护 {@code act_guard}、振翼 {@code act_wing_cape} 后半段、不屈 {@code death} 跪下接 {@code summon} 落地起身那一段、龙息 {@code flame_throw} 推掌停住。
 */
public final class LacertaSeals {
    static final int SHELTER = 1, ARTS = 2, GUST = 3, SKYFALL = 4, CATCH = 5, BREATH = 6, RISE = 7, WRATH = 8, DRAGON = 9;

    /** 翼护：你离她多近（格，巨龙再远一些）、转过去几成；拢翅的样子多长、多久才再摆一次。 */
    static final double SHELTER_RANGE = 3, SHELTER_DRAGON_RANGE = 5.5, SHELTER_SHARE = .3;
    static final int SHELTER_POSE = 52, SHELTER_POSE_GAP = 40;
    /** 振翼：身边几格里、几个敌人以上才扇；掀多远、往上抬多少、伤害（攻击的几倍）；从 act_wing_cape 第几秒播起、第几刻扇中、播多久；冷却。 */
    static final double GUST_RADIUS = 4, GUST_PUSH = 1.3, GUST_LIFT = .35, GUST_MULT = .6, GUST_FROM = 1.25;
    static final int GUST_CROWD = 3, GUST_HIT = 11, GUST_TICKS = 31, GUST_COOLDOWN = 200;
    /** 龙息（人形）：多远、半角（度）、每下伤害（攻击的几倍）、花她多少魔力；推掌几刻后喷出、喷多久、几刻一下、冷却；锥里几个敌人以上才喷。 */
    public static final double BREATH_RANGE = 9, BREATH_HALF = 25, BREATH_MULT = .2, BREATH_COST = 40;
    /** 龙息时推掌停在 flame_throw 的第几秒（左臂推到最直，0.3–0.5 秒）。 */
    static final double BREATH_POSE = .35;
    /** 冲过来的敌人进了这么远（格）、在她朝它方向的这么宽（半角，度，同红莲横扫看的扇区）里扎着堆就喷：喷两秒，它们会走进火里。 */
    static final double BREATH_START_RANGE = 11, BREATH_START_HALF = 36;
    public static final int BREATH_START = 6, BREATH_TICKS = 40, BREATH_PULSE = 4, BREATH_COOLDOWN = 300, BREATH_CROWD = 2;
    /** 不屈：站起来回到几成生命、之后挨打只受几成；跪着多久（death 跪下）、站起来那一段多长（summon 从第 0.9 秒的落地播到完）、减伤多久。 */
    static final float RISE_HEAL = .5f, RISE_TAKEN = .5f;
    static final int RISE_KNEEL = 30, RISE_STAND = 56, RISE_GUARD = 100;
    static final double RISE_FROM = .9;
    /** 逆鳞：你的生命低于几成、攻击多几成、多久、冷却（刻）。 */
    static final float WRATH_BELOW = .3f, WRATH_ATTACK = .3f;
    static final int WRATH_TICKS = 200, WRATH_COOLDOWN = 1200;
    /** 接住你以后歇多久（5 分钟）。 */
    static final int CATCH_COOLDOWN = 6000;

    private final LacertaCompanion c;
    private long lastShelterPose = -1000, nextGust, gustAt = -1, breathAt = -1, nextBreath, riseAt = -1, riseGuardUntil = -1, wrathUntil = -1, nextWrath;
    private @Nullable LivingEntity breathTarget, wrathFoe;
    /** 在替你挡（她这一下不弹反、不挡）。 */
    boolean sheltering;

    LacertaSeals(LacertaCompanion c) {
        this.c = c;
    }

    static boolean has(int seals, int seal) {
        return CompanionBond.unlocked(seals, seal);
    }

    boolean has(int seal) {
        return has(c.seals(), seal);
    }

    /** 正在振翼、喷龙息（这几刻里不出剑、不走）。 */
    boolean acting() {
        return gustAt >= 0 || breathAt >= 0;
    }

    boolean breathing() {
        return breathAt >= 0;
    }

    /** 不屈：跪着、站起来的那几秒（打不到、不动）。 */
    boolean rising() {
        return riseAt >= 0;
    }

    boolean wrathful() {
        return wrathUntil >= 0;
    }

    /** 逆鳞时先打的那个（伤你的）。 */
    @Nullable LivingEntity wrathFoe() {
        return wrathful() && wrathFoe != null && wrathFoe.isAlive() ? wrathFoe : null;
    }

    /** 攻击乘几（逆鳞时多三成）。 */
    float attackScale() {
        return wrathful() ? 1 + WRATH_ATTACK : 1;
    }

    /** 挨打时再乘几（不屈站起来以后的减伤）。 */
    float takenScale(long now) {
        return now < riseGuardUntil ? RISE_TAKEN : 1;
    }

    void tick(ServerLevel level, @Nullable ServerPlayer p, long now) {
        if (gustAt >= 0) tickGust(level, now);
        if (breathAt >= 0) tickBreath(level, now);
        if (p != null) tickWrath(level, p, now);
    }

    /** 收回、倒下、变身时：手上的都停下。 */
    void stop(ServerLevel level) {
        gustAt = -1;
        stopBreath(level);
        wrathUntil = -1;
        wrathFoe = null;
    }

    // ---------------------------------------------------------------- 一 绯翼庇护

    /** 主人挨了打（护甲、魔咒算过以后）：她在身边、第一印盖了，就替你接下三成（按她的护甲再算一遍）。 */
    static void onOwnerHurt(LivingDamageEvent.Pre e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level) || e.getNewDamage() <= 0) return;
        DamageSource src = e.getSource();
        if (!shelterable(src) || src.getEntity() == p) return;
        LacertaCompanion c = Companions.lacerta(p);
        if (c == null || !c.seal.has(SHELTER) || !c.seal.canShelter(p) || src.getEntity() == c || src.getEntity() instanceof CompanionEntity) return;
        e.setNewDamage(e.getNewDamage() * (float) (1 - SHELTER_SHARE));
        c.seal.shelter(level, p, src, (float) (e.getOriginalDamage() * SHELTER_SHARE));
    }

    /** 翅膀挡得住的：打过来的（有出手的生物、弹射物，或者爆炸）。摔、淹、冻、饿、中毒这些，和绕过无敌的（虚空、指令）不挡。 */
    static boolean shelterable(DamageSource s) {
        if (s.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || s.is(DamageTypeTags.IS_FALL) || s.is(DamageTypeTags.IS_DROWNING)
                || s.is(DamageTypeTags.IS_FREEZING)) return false;
        return s.getEntity() != null || s.getDirectEntity() != null || s.is(DamageTypeTags.IS_EXPLOSION);
    }

    private boolean canShelter(ServerPlayer p) {
        if (c.withdrawing() || rising() || c.meeting() || c.leaving() || c.isPassenger() || c.hasPassenger(p) || c.flight.catching()) return false;
        return c.distanceTo(p) <= (c.isDragonForm() ? SHELTER_DRAGON_RANGE : SHELTER_RANGE);
    }

    private void shelter(ServerLevel level, ServerPlayer p, DamageSource src, float share) {
        sheltering = true;
        c.invulnerableTime = 0;
        c.hurtServer(level, src, share);
        sheltering = false;
        shelters++;
        long now = level.getGameTime();
        if (now - lastShelterPose < SHELTER_POSE_GAP) return;
        lastShelterPose = now;
        if (!c.isDragonForm()) c.playSeal("act_guard", 0, SHELTER_POSE, 0);
        Vec3 mid = c.position().add(p.position()).scale(.5).add(0, 1.1, 0);
        FoxfireCue.burst(level, mid, FoxfireFlame.DRAGON, .55f);
        level.sendParticles(new DustParticleOptions(LacertaCompanion.CORAL, 1f), mid.x, mid.y, mid.z, 8, .3, .3, .3, 0);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, .6f, 1.5f);
        CompanionLines.speakEvery(c, CompanionLines.name(LacertaCompanion.ID), CompanionLines.STYLE_COLOR, "line.logos_dimension.lacerta.shelter", 50, 900);
    }

    // ---------------------------------------------------------------- 三 烈风振翼

    /** 振翼好了：人形、站在地上（飞着、空中追击时不扇）。 */
    boolean gustReady(long now) {
        return has(GUST) && !c.isDragonForm() && c.onGround() && !c.isFlying() && now >= nextGust && !acting();
    }

    /** 双翼往下一扇（{@code act_wing_cape} 后半段），第 {@link #GUST_HIT} 刻掀开身边的。 */
    void gust(ServerLevel level, long now) {
        gustAt = now;
        nextGust = now + GUST_COOLDOWN;
        c.getNavigation().stop();
        c.playSeal("act_wing_cape", GUST_FROM, GUST_TICKS, 0);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, .9f, 1.2f);
        CompanionLines.speakEvery(c, CompanionLines.name(LacertaCompanion.ID), CompanionLines.STYLE_COLOR, "line.logos_dimension.lacerta.gust", 50, 900);
        gusts++;
    }

    private void tickGust(ServerLevel level, long now) {
        long t = now - gustAt;
        if (t == GUST_HIT) blast(level);
        if (t >= GUST_TICKS) gustAt = -1;
    }

    /** 振翼扇得到的：{@link #GUST_RADIUS} 格内（另加它的半宽）的敌人（判断要不要扇、扇中谁都用它）。 */
    private boolean inGust(LivingEntity e) {
        return foe(e) && e.distanceTo(c) <= GUST_RADIUS + e.getBbWidth() * .5;
    }

    /** 身边扇得到的敌人有几个。 */
    int around() {
        return c.level().getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(GUST_RADIUS + 1), this::inGust).size();
    }

    private void blast(ServerLevel level) {
        Vec3 at = c.position();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(GUST_RADIUS + 1), this::inGust)) {
            float before = e.getHealth();
            e.invulnerableTime = 0;
            Dragonfire.deal(c, e, c.attack() * GUST_MULT, false);
            double resist = Mth.clamp(e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1);
            e.knockback(GUST_PUSH, c.getX() - e.getX(), c.getZ() - e.getZ());
            Vec3 v = e.getDeltaMovement();
            e.setDeltaMovement(v.x, Math.max(v.y, GUST_LIFT * (1 - resist)), v.z);
            e.hurtMarked = true;
            if (e.getHealth() < before || !e.isAlive()) gustHits++;
        }
        // 脚下炸开一圈风
        for (int k = 0; k < 24; k++) {
            double a = k * Math.PI * 2 / 24;
            Vec3 v = new Vec3(Math.cos(a), 0, Math.sin(a));
            level.sendParticles(ParticleTypes.CLOUD, at.x + v.x * .8, at.y + .2, at.z + v.z * .8, 0, v.x * .6, .02, v.z * .6, 1);
        }
        FoxfireCue.burst(level, at.add(0, 1, 0), FoxfireFlame.DRAGON, .8f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.NEUTRAL, 1, .6f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, 1.1f, .9f);
        c.fought(level.getGameTime());
    }

    // ---------------------------------------------------------------- 六 赤穹龙息（人形）

    /** 龙息好了：人形、站在地上（飞着、空中追击时不喷）、魔力够、没在推掌放龙炎弹。 */
    boolean breathReady(long now) {
        return has(BREATH) && !c.isDragonForm() && c.onGround() && !c.isFlying() && now >= nextBreath && !acting() && c.mana() >= BREATH_COST
                && !c.throwing();
    }

    /** 左掌推出去（{@code flame_throw}，推到底的 {@link #BREATH_POSE} 秒定住），掌心喷出 2 秒扇形龙炎，跟着 {@code target} 扫。 */
    boolean breathe(ServerLevel level, LivingEntity target, long now) {
        if (!breathReady(now) || !c.spend(BREATH_COST)) return false;
        breathAt = now;
        breathTarget = target;
        nextBreath = now + BREATH_TICKS + BREATH_COOLDOWN;
        c.getNavigation().stop();
        c.setBreathDir(aim(target));
        c.setBreathing(true, now);
        c.playSeal("flame_throw", 0, BREATH_TICKS + 6, BREATH_POSE);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENDER_DRAGON_GROWL, SoundSource.NEUTRAL, .5f, 1.7f);
        CompanionLines.speakEvery(c, CompanionLines.name(LacertaCompanion.ID), CompanionLines.STYLE_COLOR, "line.logos_dimension.lacerta.breath", 50, 900);
        breaths++;
        return true;
    }

    private Vec3 aim(@Nullable LivingEntity t) {
        if (t == null || !t.isAlive()) return Vec3.directionFromRotation(0, c.yBodyRot);
        Vec3 d = t.position().add(0, t.getBbHeight() * .5, 0).subtract(palm(c.position(), c.yBodyRot));
        return d.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, c.yBodyRot) : d.normalize();
    }

    private void tickBreath(ServerLevel level, long now) {
        long t = now - breathAt;
        if (t >= BREATH_TICKS || c.withdrawing()) {
            stopBreath(level);
            return;
        }
        Vec3 dir = aim(breathTarget);
        float yaw = (float) (Mth.atan2(-dir.x, dir.z) * Mth.RAD_TO_DEG);
        c.setYRot(yaw);
        c.yBodyRot = yaw;
        c.setYHeadRot(yaw);
        c.setBreathDir(dir);
        if (t < BREATH_START || (t - BREATH_START) % BREATH_PULSE != 0) return;
        Vec3 from = palm(c.position(), yaw);
        if ((t - BREATH_START) % (BREATH_PULSE * 3) == 0) level.playSound(null, from.x, from.y, from.z, SoundEvents.BLAZE_SHOOT, SoundSource.NEUTRAL, .7f, .9f);
        double cos = Math.cos(Math.toRadians(BREATH_HALF));
        AABB box = new AABB(from, from.add(dir.scale(BREATH_RANGE))).inflate(BREATH_RANGE * .5);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, this::foe)) {
            Vec3 to = e.position().add(0, e.getBbHeight() * .5, 0).subtract(from);
            double len = to.length();
            if (len > BREATH_RANGE + e.getBbWidth() || len < 1e-3 || to.dot(dir) / len < cos || !c.hasLineOfSight(e)) continue;
            float before = e.getHealth();
            e.invulnerableTime = 0;
            Dragonfire.deal(c, e, c.attack() * BREATH_MULT, true);
            if (e.getHealth() < before || !e.isAlive()) breathHits++;
        }
        c.fought(now);
    }

    void stopBreath(ServerLevel level) {
        if (breathAt < 0) return;
        breathAt = -1;
        breathTarget = null;
        c.setBreathing(false, level.getGameTime());
        c.playSeal("", 0, 0, 0);
    }

    /** 站在 {@code at}、身子朝 {@code yaw} 时她左掌心在哪（推掌到底时，肩高往前半格多；客户端画龙炎也用它）。 */
    public static Vec3 palm(Vec3 at, float yaw) {
        Vec3 f = Vec3.directionFromRotation(0, yaw);
        Vec3 left = new Vec3(f.z, 0, -f.x);
        return at.add(f.scale(.55)).add(left.scale(.2)).add(0, 1.02, 0);
    }

    /** 要不要喷：从她朝 {@code toward} 的方向、{@link #BREATH_START_HALF} 度、{@link #BREATH_START_RANGE} 格内有几个敌人。 */
    int inCone(LivingEntity toward) {
        Vec3 from = palm(c.position(), c.yBodyRot);
        Vec3 dir = toward.position().add(0, toward.getBbHeight() * .5, 0).subtract(from);
        if (dir.lengthSqr() < 1e-4) return 0;
        dir = dir.normalize();
        double cos = Math.cos(Math.toRadians(BREATH_START_HALF));
        int n = 0;
        for (LivingEntity e : c.level().getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(BREATH_START_RANGE), this::foe)) {
            Vec3 to = e.position().add(0, e.getBbHeight() * .5, 0).subtract(from);
            double len = to.length();
            if (len <= BREATH_START_RANGE && len > 1e-3 && to.dot(dir) / len >= cos) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- 七 燃烬不屈

    /**
     * 致命的一下：第七印盖了、今天还没用过，就不坠入龙眠——挨下这一下、跪倒（{@code death} 到单膝着地），回头望你的那一刻龙炎绕身，
     * 站起来（{@code summon} 落地以后那一段），回一半生命、5 秒里挨打只受一半。巨龙先变回人形。站起来了返回真。
     */
    boolean tryRise(ServerLevel level) {
        ServerPlayer p = c.ownerPlayer();
        if (p == null || !has(RISE) || rising()) return false;
        long day = level.getOverworldClockTime() / 24000;
        LacertaDays d = LacertaDays.of(p);
        if (d.riseDay() == day) return false;
        p.setData(Companions.LACERTA_DAYS, d.withRiseDay(day));
        if (c.isDragonForm()) c.dragon.revert(level, p);
        stop(level);
        riseAt = level.getGameTime();
        c.setHealth(1);
        if (c.flight.catching()) c.flight.release(level, p, riseAt, false);
        c.flight.land();
        c.blade.drop();
        c.rest.rise(level, true);
        c.stopEating();
        c.setMove("");
        c.getNavigation().stop();
        c.setDeltaMovement(Vec3.ZERO);
        c.mood.cut();
        c.mood.play("death", RISE_KNEEL + 2);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENDER_DRAGON_HURT, SoundSource.NEUTRAL, .5f, 1.6f);
        rises++;
        return true;
    }

    /** 不屈的那几秒（{@link LacertaCompanion#tickSpecial} 每刻调）。 */
    void tickRise(ServerLevel level, long now) {
        c.getNavigation().stop();
        c.setDeltaMovement(c.getDeltaMovement().multiply(0, 1, 0));
        long t = now - riseAt;
        ServerPlayer p = c.ownerPlayer();
        if (p != null && t < RISE_KNEEL) c.getLookControl().setLookAt(p, 20, 20);
        if (t == RISE_KNEEL) {
            c.mood.cut();
            c.playSeal("summon", RISE_FROM, RISE_STAND, 0);
            c.setHealth(c.getMaxHealth() * RISE_HEAL);
            riseGuardUntil = now + RISE_GUARD;
            Vec3 at = c.position();
            FoxfireCue.burst(level, at.add(0, .8, 0), FoxfireFlame.DRAGON, 1.4f);
            for (int k = 0; k < 30; k++) {
                double a = k * .55, h = k * .07;
                level.sendParticles(new DustParticleOptions(k % 2 == 0 ? LacertaCompanion.CORAL : LacertaCompanion.GOLD, 1.1f),
                        at.x + Math.cos(a) * .7, at.y + h, at.z + Math.sin(a) * .7, 1, 0, 0, 0, 0);
            }
            level.sendParticles(ParticleTypes.FLAME, at.x, at.y + .6, at.z, 16, .4, .5, .4, .03);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, 1, .7f);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.NEUTRAL, .7f, 1.4f);
            if (p != null) CompanionLines.say(c, p, "rise", 80);
        }
        if (t >= RISE_KNEEL + RISE_STAND) riseAt = -1;
        c.mood.tick(now);
    }

    // ---------------------------------------------------------------- 八 触龙逆鳞

    private void tickWrath(ServerLevel level, ServerPlayer p, long now) {
        if (wrathUntil >= 0) {
            if (now >= wrathUntil || c.withdrawing()) {
                wrathUntil = -1;
                wrathFoe = null;
                return;
            }
            if (now % 4 == 0) {
                Vec3 at = c.position();
                level.sendParticles(ParticleTypes.FLAME, at.x, at.y + .7, at.z, 3, .35, .5, .35, .01);
                level.sendParticles(new DustParticleOptions(LacertaCompanion.GOLD, .9f), at.x, at.y + 1, at.z, 2, .3, .5, .3, 0);
            }
            return;
        }
        if (!has(WRATH) || now < nextWrath || c.withdrawing() || rising() || c.meeting() || !p.isAlive() || p.getHealth() >= p.getMaxHealth() * WRATH_BELOW) return;
        wrathUntil = now + WRATH_TICKS;
        nextWrath = wrathUntil + WRATH_COOLDOWN;
        LivingEntity by = p.getLastHurtByMob();
        wrathFoe = by != null && by.isAlive() && c.canHarm(by) && by.distanceTo(p) < 24 ? by : null;
        c.mood.talk("face_angry", now, WRATH_TICKS);
        Vec3 at = c.position().add(0, 1, 0);
        FoxfireCue.burst(level, at, FoxfireFlame.DRAGON, 1.3f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDER_DRAGON_GROWL, SoundSource.NEUTRAL, 1, 1.2f);
        CompanionLines.say(c, p, "wrath", 70);
        wraths++;
    }

    // ---------------------------------------------------------------- 别的

    private boolean foe(LivingEntity e) {
        return e != c && e.isAlive() && !c.hasPassenger(e) && c.canHarm(e) && c.hostileTo(e);
    }

    /** 测试用：翼护挡过几下；振翼扇过几次、扇中几个；龙息喷过几次、烧到几下；站起来过几次；逆鳞几次。 */
    static int shelters, gusts, gustHits, breaths, breathHits, rises, wraths;

    static int[] counts() {
        return new int[]{shelters, gusts, gustHits, breaths, breathHits, rises, wraths};
    }

    /** 测试用：立刻振翼、喷龙息（不看处境和冷却）。 */
    void gustNow(ServerLevel level) {
        nextGust = 0;
        gust(level, level.getGameTime());
    }

    boolean breatheNow(ServerLevel level, LivingEntity target) {
        nextBreath = 0;
        return breathe(level, target, level.getGameTime());
    }
}
