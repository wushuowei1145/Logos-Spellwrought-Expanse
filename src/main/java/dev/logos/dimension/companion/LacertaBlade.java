package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.combat.Parry;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.item.CrimsonFlameSkills;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔的炽焰长剑（docs/LACERTA_COMPANION.md 第 5 节，接入方案第三节）：与玩家共用的招式状态机（{@link MoveRunner}），
 * 招式文件 {@code lacerta_companion}，动作是模型里的 {@code crimson_*}（战斗层播）。
 * <ul>
 *   <li>每一剑都是龙炎（{@link Dragonfire#deal}）：她的攻击 + 剑的 {@link #SWORD} 点，乘招式的倍数；带火的招式缠身（同玩家的剑）。</li>
 *   <li>突刺、旋风、横扫、坠星的冲刺和起落照玩家的剑（{@link #dash}）；横扫的五道游脉、坠星的落点、突刺和旋风的一团龙炎都走
 *       {@link CrimsonFlameSkills#skill}，只打她当作敌人的。</li>
 *   <li>没在出招时挡得下近战和弹射物（托脊横防时几乎都挡得住）：近战弹开、对方僵直，她趁机接破军龙牙；弹射物一剑扫回去。</li>
 *   <li>拔剑时剑从柄往尖凝出来，收剑播完散掉（客户端照 {@link LacertaCompanion#swordShown} 画）。</li>
 * </ul>
 * 打哪一招由 {@link LacertaMind} 挑。
 */
final class LacertaBlade implements MoveRunner.Actor {
    static final Identifier MOVESET = LogosDimension.id("lacerta_companion");
    /** 模型里剑招动画的前缀。 */
    static final String PREFIX = "crimson_";
    /** 炽焰长剑的攻击（同玩家手里那把）：她的一剑 = 攻击 + 它，再乘招式倍数。 */
    static final float SWORD = 8;
    /** 剑够得着多远（格，另加目标半宽）：她握的是 0.85 倍的剑，全长约 1.8 格。 */
    static final double REACH = 2.9;
    /** 攻速：同玩家拿着这把剑（攻击招式按 1.5 / attack_speed_base 播）。 */
    static final double ATTACK_SPEED = 1.5;
    /** 一招砍完最后一下再过几刻能接下一招；弹开以后几刻能反击、反击的窗口多长（对方被弹反的僵直 16 刻）。 */
    static final int RECOVER = 2, PARRY_RECOVER = 3, PUNISH_WINDOW = 14;
    /** 挡下的概率：平时（没在出招）近战、弹射物；托脊横防时。 */
    static final float BLOCK_MELEE = .55f, BLOCK_PROJECTILE = .7f, BLOCK_GUARDING = .95f;
    /** 出招时往目标带一步：起手到砍中最多带多远；停在两人半宽之外再留多少。 */
    static final double STEP_MAX = .9, KEEP = .75;
    /** 起手到最后一下接触之间每刻最多转多少度追着目标。 */
    static final float TRACK_TURN = 45;
    /** 升龙挑把敌人往上挑多快（格/刻；Boss 和免疫浮空的不挑）。 */
    static final double LAUNCH = .5;
    /** 凝剑、散剑各要几刻（客户端按这个画）。 */
    static final int MANIFEST = 6;

    private final LacertaCompanion c;
    private @Nullable MoveRunner runner;
    private @Nullable LivingEntity aim;
    private boolean step;
    private @Nullable LivingEntity punish;
    private long punishUntil;
    private @Nullable Vec3 dashDir;

    LacertaBlade(LacertaCompanion c) {
        this.c = c;
    }

    // ---------------------------------------------------------------- 状态

    /** 剑在手上（招式状态机在跑）。 */
    boolean drawn() {
        return runner != null && !runner.cleared();
    }

    Moveset.@Nullable Move move() {
        return drawn() ? runner.move() : null;
    }

    private double age(long now) {
        return runner == null ? 0 : runner.age(now);
    }

    /** 正在出招：这一招有接触、还没砍完（出招时挡不了）。 */
    boolean attacking() {
        Moveset.Move m = move();
        if (m == null || !m.hasContact()) return false;
        return age(c.level().getGameTime()) < m.lastContact() * 20 / runner.rate() + 2;
    }

    /** 在待机（可以走动、出下一招）。 */
    boolean ready() {
        Moveset.Move m = move();
        return m != null && "idle".equals(m.name());
    }

    boolean guarding() {
        Moveset.Move m = move();
        return m != null && ("guard".equals(m.name()) || "guard_hit".equals(m.name()));
    }

    boolean staggered() {
        return Parry.isStagger(move());
    }

    /**
     * 可以接下一招：在待机或横防里；这一招砍完已经过了 {@link #RECOVER} 刻、在收势；弹开以后过了 {@link #PARRY_RECOVER} 刻。
     * 拔剑、收剑、僵直时不行。
     */
    boolean canFollowUp() {
        Moveset.Move m = move();
        if (m == null) return false;
        String n = m.name();
        if ("idle".equals(n) || "guard".equals(n)) return true;
        long now = c.level().getGameTime();
        if ("parry".equals(n)) return age(now) >= PARRY_RECOVER;
        return m.hasContact() && "idle".equals(m.next()) && age(now) >= m.lastContact() * 20 / runner.rate() + RECOVER;
    }

    /** 刚被她弹开、还在僵直里的那个（过了反击的时候、死了就是 null）。 */
    @Nullable LivingEntity punishing(long now) {
        return punish != null && now <= punishUntil && punish.isAlive() ? punish : null;
    }

    long punishUntil() {
        return punishUntil;
    }

    /** 这一招对着的那个（剑在手上、它还活着时）。 */
    @Nullable LivingEntity aimed() {
        return drawn() && aim != null && aim.isAlive() ? aim : null;
    }

    // ---------------------------------------------------------------- 拔剑、收剑

    /** 拔剑（已经在手上就不动）：先播绯焰风车的拔剑，剑从柄往尖凝出来。 */
    boolean draw() {
        if (drawn()) return true;
        if (!(c.level() instanceof ServerLevel level)) return false;
        Moveset set = Movesets.get(level.getServer(), MOVESET);
        if (set == null) return false;
        c.setSwordShown(true);
        runner = new MoveRunner(set, this, level.getGameTime());
        moveStarted(runner);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, .5f, 1.4f);
        draws++;
        return true;
    }

    /** 收剑：播收剑（剑花、驻剑致礼），播完剑散掉。 */
    void sheathe() {
        if (!drawn()) return;
        aim = null;
        String s = runner.moveset().sheathe();
        if (s != null && !s.equals(runner.move().name())) runner.force(s, c.level().getGameTime());
    }

    /** 立刻收掉（倒下、被收回）。 */
    void drop() {
        runner = null;
        aim = null;
        punish = null;
        dashDir = null;
        c.setMove("");
        c.setSwordShown(false);
    }

    /** 出一招（{@link #canFollowUp} 时接受）。{@code at}：对着谁；{@code step} 时往它那边冲、带步。 */
    boolean attack(String name, @Nullable LivingEntity at, boolean step) {
        if (!canFollowUp() || runner.moveset().move(name) == null) return false;
        aim = at;
        this.step = step;
        runner.force(name, c.level().getGameTime());
        STARTED.merge(name, 1, Integer::sum);
        return true;
    }

    /** 托脊横防（站住挡，挡下就弹开）。 */
    boolean guard() {
        if (!drawn()) return false;
        Moveset.Move m = move();
        if (m != null && ("guard".equals(m.name()) || "guard_hit".equals(m.name()))) return true;
        if (!canFollowUp()) return false;
        aim = null;
        runner.force("guard", c.level().getGameTime());
        return true;
    }

    /** 放下横防回到待机。 */
    void unguard() {
        if (guarding()) runner.force("idle", c.level().getGameTime());
    }

    // ---------------------------------------------------------------- 每刻

    void tick(ServerLevel level) {
        if (runner == null) return;
        long now = level.getGameTime();
        Moveset.Move m = runner.move();
        if (m != null) {
            double rate = runner.rate();
            double t = runner.age(now) / 20 * rate;
            track(m, t);
            if (!dash(m, runner.age(now) * rate)) approach(m, t - rate / 20, t);
        }
        runner.tick(now);
    }

    /**
     * 招式的冲刺和起落（同玩家的剑 {@code CrimsonFlameSkills.combatTick}，按动作刻算）：突刺第 2–5 刻每刻 1.15 格，旋风第 2–10 刻 0.78 格，
     * 横扫第 4–7 刻往前踏 0.55 格；坠星第 1–4 刻往上蹿、第 7–10 刻往前下砸。冲到目标跟前就停，不冲过头。返回这一刻是不是在冲。
     */
    private boolean dash(Moveset.Move m, double tick) {
        String n = m.name();
        double speed;
        if ("crimson_thrust".equals(n) && tick >= 2 && tick <= 5) speed = 1.15;
        else if ("vortex_rush".equals(n) && tick >= 2 && tick <= 10) speed = .78;
        else if ("flame_sweep".equals(n) && tick >= 4 && tick <= 7) speed = .55;
        else if ("skyfall_judgement".equals(n) && (tick >= 1 && tick <= 4 || tick >= 7 && tick <= 10)) speed = -1;
        else return false;
        Vec3 dir = dashDir != null ? dashDir : forward();
        Vec3 v = c.getDeltaMovement();
        if (speed < 0) {
            c.resetFallDistance();
            if (tick <= 4) c.setDeltaMovement(v.x * .8 + dir.x * .24, Math.max(v.y, .42), v.z * .8 + dir.z * .24);
            else c.setDeltaMovement(v.x * .4 + dir.x * .45, -1.15, v.z * .4 + dir.z * .45);
            c.hurtMarked = true;
            return true;
        }
        LivingEntity at = aim;
        if (at != null && at.isAlive()) {
            double gap = at.position().subtract(c.position()).multiply(1, 0, 1).length() - KEEP - (at.getBbWidth() + c.getBbWidth()) * .5;
            if (gap <= .05) return true;
            speed = Math.min(speed, gap);
        }
        if (c.horizontalCollision) return true;
        c.setDeltaMovement(dir.x * speed, Math.min(v.y, .08), dir.z * speed);
        c.hurtMarked = true;
        return true;
    }

    /** 别的招式在起手到砍中之间往目标带一步（目标被打退了也跟得上），走到两人半宽之外 {@link #KEEP} 就停。 */
    private void approach(Moveset.Move m, double before, double after) {
        LivingEntity at = aim;
        if (!step || at == null || !at.isAlive() || !m.hasContact()) return;
        double to = m.contact();
        double overlap = Math.min(after, to) - Math.max(before, 0);
        if (to <= 0 || overlap <= 0) return;
        Vec3 gap = at.position().subtract(c.position()).multiply(1, 0, 1);
        double need = gap.length() - KEEP - (at.getBbWidth() + c.getBbWidth()) * .5;
        if (need <= .02) return;
        c.move(MoverType.SELF, gap.normalize().scale(Math.min(need, STEP_MAX * overlap / to)));
    }

    /** 起手到最后一下接触之前（动作时间 {@code t} 秒）：身子追着目标转。 */
    private void track(Moveset.Move m, double t) {
        LivingEntity at = aim;
        if (at == null || !at.isAlive() || !m.hasContact() || t > m.lastContact()) return;
        c.lookAt(at, TRACK_TURN, 30);
        c.yBodyRot = c.getYRot();
        c.setYHeadRot(c.getYRot());
    }

    private Vec3 forward() {
        float yaw = c.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
    }

    // ---------------------------------------------------------------- 招式状态机

    @Override
    public double attackSpeed() {
        return ATTACK_SPEED;
    }

    @Override
    public void moveStarted(MoveRunner r) {
        if (r != runner) return;
        if (r.cleared()) {
            runner = null;
            aim = null;
            c.setMove("");
            c.setSwordShown(false);
            return;
        }
        Moveset.Move m = r.move();
        // 冲刺的方向在起手时定：对着目标，没有目标就朝前
        LivingEntity at = aim;
        Vec3 to = at != null && at.isAlive() ? at.position().subtract(c.position()).multiply(1, 0, 1) : null;
        dashDir = to != null && to.lengthSqr() > 1e-4 ? to.normalize() : forward();
        c.setMove(PREFIX + m.clip() + "|" + (long) r.start() + "|" + (float) r.rate() + "|" + (m.playback() == Moveset.Playback.LOOP));
    }

    /** 接触：砍到身前扇形里她当作敌人的，第一下接触时放这一招的战技（游脉、落点、一团龙炎）。 */
    @Override
    public void contact(MoveRunner r) {
        if (!(c.level() instanceof ServerLevel level)) return;
        Moveset.Move move = r.move();
        Predicate<LivingEntity> foe = e -> e != c && e.isAlive() && c.canHarm(e) && c.hostileTo(e);
        if (r.contactIndex() == 0) CrimsonFlameSkills.skill(level, c, forward(), move.name(), foe);
        double damage = (c.attack() + SWORD) * move.damage();
        double cone = move.cone() > 0 ? move.cone() : 110;
        Vec3 eye = c.position().add(0, c.getBbHeight() * .5, 0);
        Vec3 fwd = forward();
        boolean any = false;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(REACH + 1), foe)) {
            if (nearest(e.getBoundingBox(), eye).distanceTo(eye) > REACH) continue;
            Vec3 to = e.position().add(0, e.getBbHeight() * .5, 0).subtract(eye);
            double flat = Math.sqrt(to.x * to.x + to.z * to.z);
            if (flat > .9 && (to.x * fwd.x + to.z * fwd.z) / flat < Math.cos(Math.toRadians(cone / 2))) continue;
            any |= hit(level, e, (float) damage, CrimsonFlameSkills.burning(move.name()));
            if ("launch".equals(move.effect()) && e.isAlive() && !e.is(dev.logos.dimension.combat.Airborne.IMMUNE)
                    && e.getMaxHealth() < CompanionEntity.STRONG * 5) {
                Vec3 v = e.getDeltaMovement();
                e.setDeltaMovement(v.x, Math.max(v.y, LAUNCH), v.z);
                e.hurtMarked = true;
            }
            if (r != runner || r.move() != move) break;
        }
        level.playSound(null, c.getX(), c.getY(), c.getZ(), any ? SoundEvents.PLAYER_ATTACK_SWEEP : SoundEvents.PLAYER_ATTACK_NODAMAGE,
                SoundSource.NEUTRAL, .8f, .95f + c.getRandom().nextFloat() * .15f);
        if (any) c.fought(level.getGameTime());
    }

    static Vec3 nearest(AABB box, Vec3 p) {
        return new Vec3(Mth.clamp(p.x, box.minX, box.maxX), Mth.clamp(p.y, box.minY, box.maxY), Mth.clamp(p.z, box.minZ, box.maxZ));
    }

    /** 一剑龙炎：打进去了返回真（看血量和伤害吸收有没有掉，同玩家的剑）。 */
    private boolean hit(ServerLevel level, LivingEntity e, float damage, boolean burn) {
        float before = e.getHealth() + e.getAbsorptionAmount();
        e.invulnerableTime = 0;
        boolean died = Dragonfire.deal(c, e, damage, burn);
        boolean hurt = died || e.getHealth() + e.getAbsorptionAmount() < before;
        if (hurt) landed++;
        return hurt;
    }

    // ---------------------------------------------------------------- 格挡、被弹反

    /**
     * 有东西打她：没在出招、没在僵直里时有很大机会挡下（挡下返回真，这一下不受伤）。近战弹开，对方（玩家或会做出反应的招式生物）僵直，
     * 她记下来趁机反击；弹射物一剑扫回去。
     */
    boolean tryBlock(ServerLevel level, DamageSource source) {
        if (!drawn() || attacking() || staggered()) return false;
        Entity direct = source.getDirectEntity();
        boolean projectile = direct instanceof Projectile;
        boolean melee = Parry.melee(source);
        if (!projectile && !melee) return false;
        float chance = guarding() ? BLOCK_GUARDING : projectile ? BLOCK_PROJECTILE : BLOCK_MELEE;
        if (c.getRandom().nextFloat() >= chance) return false;
        blocks++;
        aim = null;
        runner.force("parry", level.getGameTime());
        if (source.getEntity() instanceof LivingEntity attacker) c.getLookControl().setLookAt(attacker, 60, 60);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, .35f, 1.7f);
        if (!melee) {
            Projectile p = (Projectile) direct;
            p.deflect(ProjectileDeflection.REVERSE, c, EntityReference.<Entity>of(c), false);
            dev.logos.dimension.shiraume.ShiraumeDeflect.claim(p, c);
        } else if (Parry.stagger(source, c) && source.getEntity() instanceof LivingEntity attacker) {
            punish = attacker;
            punishUntil = level.getGameTime() + PUNISH_WINDOW;
        }
        return true;
    }

    /** 她这一剑被 {@code by} 弹开：进入 NPC 的僵直。 */
    boolean parried(LivingEntity by) {
        if (!drawn() || !Parry.staggerNpc(runner, c.level().getGameTime())) return false;
        staggers++;
        return true;
    }

    // ---------------------------------------------------------------- 测试用

    /** 每一招出过几次（{@link #attack} 起的头）。 */
    static final Map<String, Integer> STARTED = new HashMap<>();
    static int landed, blocks, staggers, draws;

    static int started(String move) {
        return STARTED.getOrDefault(move, 0);
    }
}
