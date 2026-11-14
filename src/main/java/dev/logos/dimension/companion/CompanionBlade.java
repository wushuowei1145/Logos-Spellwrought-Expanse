package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.combat.Parry;
import dev.logos.dimension.combat.StaggerGoal;
import dev.logos.dimension.execution.ExecutionShot;
import dev.logos.dimension.execution.ExecutionShots;
import dev.logos.dimension.execution.ExecutionTimeline;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import dev.logos.dimension.ryuka.FoxMarks;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 白梅的流火（docs/SHIRAUME_COMPANION.md 第 8.4 节）：敌人贴近、她血量充裕时从拳中凝出一把，招式由与玩家、Boss 共用的
 * 招式状态机驱动（招式文件 {@code shiraume_companion}，动作是 Boss 人形的编排烘焙到她骨架上的 {@code ryuka_*}）。
 * <ul>
 *   <li>刀伤按她继承后的攻击（{@link ShiraumeCompanion#foxfireDamage()}）× {@link #BLADE} × 招式的倍数；砍中留狐火印（谁的流火都算），
 *       照流火的规矩缠身 3 秒；
 *   <li>没在出招时，近战 {@link #BLOCK_MELEE}、弹射物 {@link #BLOCK_PROJECTILE} 挡下：近战是弹刀，对方进入被弹反的僵直；
 *   <li>目标在狐火印爆开后的窗口里（她或主人打出来的都算）：上挑接处决（不可交互的那种），旁人看到目标在半空被刀光砍。
 * </ul>
 * 刀看得见的样子（凝出来、散掉）同步给客户端：{@link ShiraumeCompanion#bladeShown()} 和变化的那一刻。
 */
public final class CompanionBlade implements MoveRunner.Actor {
    public static final Identifier MOVESET = LogosDimension.id("shiraume_companion");
    /** 动画名前缀（同 Boss）。 */
    public static final String PREFIX = "ryuka_";
    /**
     * 刀伤是她狐火弹的几倍（基础狐火 4 时一刀 9，同玩家手里流火的一刀 {@code Ryuka.DAMAGE}；她的刀是实打实的刀伤，吃护甲、会被弹开，
     * 玩家的流火是不吃护甲的狐火）；刀够得着多远（格，另加目标半宽）；攻速（攻击招式按 2.2 倍播：Boss 的起手和收势都长，
     * 原样用的话一刀要一秒，两只神狐都打不过）。
     */
    public static final double BLADE = 2.25, REACH = 2.4, ATTACK_SPEED = 2.2;
    /** 砍完最后一下再过这么多刻就能接下一刀，不等 Boss 那段长长的收势播完（连招中间那几段除外）。 */
    static final int RECOVER = 2;
    /**
     * 弹刀以后多少刻就能出刀；弹开近战之后，对方的僵直里（{@link #PUNISH_WINDOW} 刻，NPC 被弹反僵直 16 刻）她接一串反击，
     * 僵直里的对方挡不了。
     */
    static final int DEFLECT_RECOVER = 3, PUNISH_WINDOW = 14;
    public static final float BLOCK_MELEE = .85f, BLOCK_PROJECTILE = .9f;
    /** 凝出、散掉各要几刻（客户端按这个画）。 */
    public static final int MANIFEST = 8;
    /** 处决：几刀；每刀、重击、拍地各是她刀伤的几倍（玩家的流火是 11、11、25 点）。 */
    public static final int EXECUTION_SLASHES = 6;
    public static final double EXECUTION_SLASH = .9, EXECUTION_FINAL = 1.3, EXECUTION_FALL = 2.2;
    /** 普通一刀缠身多久（刻），同玩家的流火。 */
    static final int WRAP_TICKS = 60;
    /**
     * 往目标那边走（{@link #approach}）：突刺在冲的那一段里最多冲 {@link #LUNGE_MAX} 格，别的招式在起手到砍中之间最多带 {@link #STEP_MAX} 格；
     * 走到两人半宽之外再留 {@link #KEEP} 格就停。Boss 的突刺按她的个子冲不远，照搬会够不着、一记接一记地刺。
     */
    public static final double LUNGE_MAX = 3.0, STEP_MAX = .9, KEEP = .75;
    /**
     * 突刺往前冲的那一段（动作时间，秒）：Boss 招式文件 tsuki 的 lunge 是 .58–.76，比第一下接触（.64）晚完，她冲到三分之一就刺、
     * 够不着；这里提前到第一下接触时冲完，长度不变。
     */
    static final double LUNGE_FROM = .46, LUNGE_TO = .64;
    /** 起手到砍中之间每刻最多转多少度追着这一刀对着的那个（它绕着她走、被打退也砍得到）。 */
    static final float TRACK_TURN = 45;

    private final ShiraumeCompanion c;
    private @Nullable MoveRunner runner;
    /** 这一招对着谁（起手时转过去；突刺冲过去、出刀往前带一步，{@link #step} 为假时原地出刀）。 */
    private @Nullable LivingEntity aim;
    private boolean step;
    /** 刚被她弹开近战、还在僵直里的那个，到哪一刻为止。 */
    private @Nullable LivingEntity punish;
    private long punishUntil;
    private @Nullable LivingEntity executing;
    private final List<Scheduled> scheduled = new ArrayList<>();
    private long executionEnds = -1;

    private record Scheduled(long at, Runnable run) {}

    CompanionBlade(ShiraumeCompanion c) {
        this.c = c;
    }

    // ---------------------------------------------------------------- 状态

    /** 刀在手上（招式状态机在跑）。 */
    public boolean drawn() {
        return runner != null && !runner.cleared();
    }

    public Moveset.@Nullable Move move() {
        return drawn() ? runner.move() : null;
    }

    /** 正在出招：这一招有接触、还没砍完。出招时挡不了。 */
    public boolean attacking() {
        Moveset.Move m = move();
        if (m == null || !m.hasContact() || !(c.level() instanceof ServerLevel level)) return false;
        double age = runner.age(level.getGameTime());
        return age < m.lastContact() * 20 / runner.rate() + 2;
    }

    /** 在待机（可以走动、出下一招）。 */
    public boolean ready() {
        Moveset.Move m = move();
        return m != null && "idle".equals(m.name());
    }

    /**
     * 可以接下一刀：在待机；这一刀砍完已经过了 {@link #RECOVER} 刻、正在收势（连招中间那几段等它自己接完）；
     * 或者弹刀过了 {@link #DEFLECT_RECOVER} 刻（弹开就反击）。
     */
    public boolean canFollowUp() {
        Moveset.Move m = move();
        if (m == null) return false;
        if ("idle".equals(m.name())) return true;
        if (executing() || !(c.level() instanceof ServerLevel level)) return false;
        double age = runner.age(level.getGameTime());
        if ("deflect".equals(m.name())) return age >= DEFLECT_RECOVER;
        return m.hasContact() && "idle".equals(m.next()) && age >= m.lastContact() * 20 / runner.rate() + RECOVER;
    }

    /** 刚被她弹开近战、还在僵直里的那个（过了反击的时候、或者死了就是 null）。 */
    public @Nullable LivingEntity punishing(long now) {
        return punish != null && now <= punishUntil && punish.isAlive() ? punish : null;
    }

    /** 这一次反击的时候到哪一刻（换了一次弹开就变）。 */
    long punishUntil() {
        return punishUntil;
    }

    public boolean staggered() {
        return Parry.isStagger(move());
    }

    public boolean executing() {
        return executing != null;
    }

    // ---------------------------------------------------------------- 拔刀、收刀

    /** 凝出流火（已经在手上就不动）。 */
    public boolean draw() {
        if (drawn()) return true;
        if (!(c.level() instanceof ServerLevel level)) return false;
        Moveset set = Movesets.get(level.getServer(), MOVESET);
        if (set == null) return false;
        c.setBladeShown(true);
        runner = new MoveRunner(set, this, level.getGameTime());
        moveStarted(runner);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, .6f, 1.6f);
        return true;
    }

    /** 散刀：播收刀，播完刀化作花瓣散去。 */
    public void sheathe() {
        if (!drawn() || executing()) return;
        aim = null;
        String sheathe = runner.moveset().sheathe();
        if (sheathe != null && !sheathe.equals(runner.move().name())) runner.force(sheathe, c.level().getGameTime());
    }

    /** 凝刀一挥（格挡、截弹射物）：不管手上这一招播到哪里，直接换成弹刀。 */
    void parry() {
        if (!draw() || runner == null) return;
        aim = null;
        runner.force("deflect", c.level().getGameTime());
    }

    /** 立刻收掉（倒下、被收回）。 */
    void drop() {
        runner = null;
        aim = null;
        punish = null;
        executing = null;
        scheduled.clear();
        c.setMove("");
        c.setBladeShown(false);
    }

    /**
     * 出一招（待机时、或上一刀进了收势时接受，见 {@link #canFollowUp}）。{@code at}：对着谁，突刺冲过去、别的招式往前带一步
     * （连招的后几段也带）；null 时原地出刀。
     */
    public boolean attack(String name, @Nullable LivingEntity at) {
        return attack(name, at, true);
    }

    /** {@code step}：往 {@code at} 那边冲、带步；假时原地出刀，只转过去对着它。 */
    public boolean attack(String name, @Nullable LivingEntity at, boolean step) {
        if (!canFollowUp() || runner.moveset().move(name) == null) return false;
        aim = at;
        this.step = step;
        runner.force(name, c.level().getGameTime());
        started.merge(name, 1, Integer::sum);
        return true;
    }

    /** 测试用：每一招出过几次（{@link #attack} 起的头，连招自己接的后几段不算）。 */
    static final java.util.Map<String, Integer> started = new java.util.HashMap<>();

    public static int started(String move) {
        return started.getOrDefault(move, 0);
    }

    public static int startedTotal() {
        return started.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 测试用：砍中的、被对方挡下的、她被弹反僵直的、她挡下的次数。 */
    static int landed, foeBlocked, staggers, blocks;

    public static int landed() {
        return landed;
    }

    public static String tally() {
        return "cuts " + startedTotal() + " landed " + landed + " foe-blocked " + foeBlocked + " her-staggers " + staggers + " her-blocks " + blocks;
    }

    // ---------------------------------------------------------------- 每刻

    void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (!scheduled.isEmpty()) {
            for (Scheduled s : List.copyOf(scheduled)) {
                if (s.at() <= now) {
                    scheduled.remove(s);
                    s.run().run();
                }
            }
        }
        if (executing != null && (now >= executionEnds || !executing.isAlive())) endExecution(level);
        if (runner == null) return;
        // 先走、先转，再结算这一刻的接触：反过来的话，砍中那一刻的一步（突刺冲的那一段）要等刀落空以后才走
        Moveset.Move m = runner.move();
        if (m != null) {
            double rate = runner.rate();
            double t = runner.age(now) / 20 * rate;
            track(m, t);
            approach(m, t - rate / 20, t);
        }
        runner.tick(now);
        if (runner == null) return;
        m = runner.move();
        if (m == null) return;
        double t = runner.age(now) / 20 * runner.rate();
        // 后撤步往后跳（Boss 招式文件里的 leap，这里按她的个头缩小）
        if ("backstep".equals(m.name()) && Math.abs(t - .12) < .026) {
            Vec3 back = Vec3.directionFromRotation(0, c.getYRot()).scale(-.55);
            c.setDeltaMovement(back.x, .3, back.z);
            c.needsSync = true;
        }
    }

    /**
     * 往目标那边走（这一刻是动作时间 {@code before} 到 {@code after}，秒）：突刺在 {@link #LUNGE_FROM}–{@link #LUNGE_TO} 里冲，
     * 别的招式在起手到砍中之间带一步；按这一刻占那一段的比例分，走到离目标 {@link #KEEP} 格就停，不冲过头。
     */
    private void approach(Moveset.Move m, double before, double after) {
        LivingEntity at = aim;
        if (!step || at == null || !at.isAlive() || !m.hasContact()) return;
        boolean lunge = "tsuki".equals(m.name());
        double from = lunge ? LUNGE_FROM : 0, to = lunge ? LUNGE_TO : m.contact(), max = lunge ? LUNGE_MAX : STEP_MAX;
        double overlap = Math.min(after, to) - Math.max(before, from);
        if (to <= from || overlap <= 0) return;
        Vec3 gap = at.position().subtract(c.position()).multiply(1, 0, 1);
        double need = gap.length() - KEEP - (at.getBbWidth() + c.getBbWidth()) * .5;
        if (need <= .02) return;
        c.move(MoverType.SELF, gap.normalize().scale(Math.min(need, max * overlap / (to - from))));
    }

    /** 起手到最后一下接触之前（动作时间 {@code t}，秒）：身子追着这一刀对着的那个转，每刻最多 {@link #TRACK_TURN} 度。 */
    private void track(Moveset.Move m, double t) {
        LivingEntity at = aim;
        if (at == null || !at.isAlive() || !m.hasContact() || t > m.lastContact()) return;
        c.lookAt(at, TRACK_TURN, 30);
        c.yBodyRot = c.getYRot();
        c.setYHeadRot(c.getYRot());
    }

    /** 这一刀对着的那个（刀在手上、它还活着时）。 */
    @Nullable LivingEntity aimed() {
        return drawn() && aim != null && aim.isAlive() ? aim : null;
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
            c.setMove("");
            c.setBladeShown(false);
            return;
        }
        Moveset.Move m = r.move();
        c.setMove(PREFIX + m.clip() + "|" + (long) r.start() + "|" + (float) r.rate() + "|" + (m.playback() == Moveset.Playback.LOOP));
    }

    /** 刀的接触时刻：砍到身前扇形里的敌人；处决的上挑只认要处决的那一个。 */
    @Override
    public void contact(MoveRunner r) {
        if (!(c.level() instanceof ServerLevel level)) return;
        Moveset.Move move = r.move();
        if ("throw_kiriage".equals(move.name()) && executing != null) {
            launch(level, executing);
            return;
        }
        double damage = c.foxfireDamage() * BLADE * move.damage();
        double cone = move.cone() > 0 ? move.cone() : 110;
        Vec3 eye = c.position().add(0, c.getBbHeight() * .5, 0);
        float yaw = c.getYRot() * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        boolean any = false;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(REACH + 1), e -> e != c && e.isAlive() && c.canHarm(e))) {
            if (!c.hostileTo(e)) continue;
            // 够不够得着量到它身上最近的一点（高一点、矮一点的也砍得到），方向按它的中心
            if (nearest(e.getBoundingBox(), eye).distanceTo(eye) > REACH) continue;
            Vec3 to = e.position().add(0, e.getBbHeight() * .5, 0).subtract(eye);
            double flat = Math.sqrt(to.x * to.x + to.z * to.z);
            if (flat > .9 && (to.x * forward.x + to.z * forward.z) / flat < Math.cos(Math.toRadians(cone / 2))) continue;
            any |= hit(level, e, (float) damage, true);
            // 这一刀被弹开了（招式已经换成被弹反）：后面的不再结算
            if (r != runner || r.move() != move) break;
        }
        level.playSound(null, c.getX(), c.getY(), c.getZ(), any ? SoundEvents.PLAYER_ATTACK_SWEEP : SoundEvents.PLAYER_ATTACK_NODAMAGE,
                SoundSource.NEUTRAL, .7f, 1.3f + c.getRandom().nextFloat() * .2f);
    }

    /** 盒子 {@code box} 上离 {@code p} 最近的一点。 */
    static Vec3 nearest(net.minecraft.world.phys.AABB box, Vec3 p) {
        return new Vec3(Mth.clamp(p.x, box.minX, box.maxX), Mth.clamp(p.y, box.minY, box.maxY), Mth.clamp(p.z, box.minZ, box.maxZ));
    }

    /** 流火砍中一下：伤害、狐火印（满了爆开）、缠身。 */
    boolean hit(ServerLevel level, LivingEntity e, float damage, boolean mark) {
        e.invulnerableTime = 0;
        boolean hurt = e.hurtServer(level, c.damageSources().mobAttack(c), damage);
        if (hurt) landed++;
        else foeBlocked++;
        if (!hurt || !e.isAlive()) return hurt;
        if (mark && !FoxMarks.add(c, e)) FoxfireRules.burn(e, FoxfireFlame.SHIRAUME, c, WRAP_TICKS);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, e.getX(), e.getY() + e.getBbHeight() * .6, e.getZ(), 6, .2, .2, .2, .02);
        return true;
    }

    // ---------------------------------------------------------------- 格挡、被弹反

    /**
     * 有东西打她：没在出招、没在僵直里，近战和弹射物有很大机会挡下（挡下返回真，这一下不受伤）。
     * 近战挡下就是弹刀，对方（玩家或会做出反应的招式生物）进入被弹反的僵直；弹射物被一刀弹回去。
     */
    boolean tryBlock(ServerLevel level, DamageSource source) {
        if (attacking() || staggered() || executing() || c.casting()) return false;
        Entity direct = source.getDirectEntity();
        boolean projectile = direct instanceof Projectile;
        boolean melee = Parry.melee(source);
        if (!projectile && !melee) return false;
        if (c.getRandom().nextFloat() >= (projectile ? BLOCK_PROJECTILE : BLOCK_MELEE)) return false;
        blocks++;
        parry();
        if (source.getEntity() instanceof LivingEntity attacker) c.getLookControl().setLookAt(attacker, 60, 60);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, .35f, 1.9f);
        level.sendParticles(ParticleTypes.CRIT, c.getX(), c.getY() + .8, c.getZ(), 8, .2, .2, .2, .2);
        if (!melee) {
            deflect((Projectile) direct);
        } else if (Parry.stagger(source, c) && source.getEntity() instanceof LivingEntity attacker) {
            // 对方进了僵直：趁这一下反击
            punish = attacker;
            punishUntil = level.getGameTime() + PUNISH_WINDOW;
        }
        return true;
    }

    /** 把一发弹射物一刀弹回去：之后算她打的。 */
    void deflect(Projectile projectile) {
        projectile.deflect(ProjectileDeflection.REVERSE, c, EntityReference.<Entity>of(c), false);
        // 原版弹开后下一刻把主人改回原来的射手：下一刻再认领（同 Boss）
        dev.logos.dimension.shiraume.ShiraumeDeflect.claim(projectile, c);
    }

    /** 刚弹开过她的刀的那个，记到哪一刻（{@link #WARY} 刻）：对它少主动出刀，多守着等它出手再反击。 */
    static final int WARY = 100;
    private @Nullable LivingEntity parriedBy;
    private long waryUntil;

    /** {@code e} 最近弹开过她的刀。 */
    public boolean wary(LivingEntity e, long now) {
        return e == parriedBy && now <= waryUntil;
    }

    /** 她这一刀被 {@code by} 弹开了：进入 NPC 的僵直（{@code parried_npc}），记下是谁。 */
    boolean parried(LivingEntity by) {
        if (!drawn() || !Parry.staggerNpc(runner, c.level().getGameTime())) return false;
        parriedBy = by;
        waryUntil = c.level().getGameTime() + WARY;
        staggers++;
        StaggerGoal.interrupt(c, c.goalSelectorAccess());
        return true;
    }

    // ---------------------------------------------------------------- 处决

    /** 处决 {@code target}：上挑把它挑起来（接触时开始处决特写）。目标得在狐火印爆开后的窗口里。 */
    boolean execute(LivingEntity target) {
        if (executing() || !FoxMarks.executable(target) || !draw() || runner.moveset().move("throw_kiriage") == null) return false;
        aim = null;
        executing = target;
        executionEnds = Long.MAX_VALUE;
        c.lookAt(target, 360, 360);
        runner.force("throw_kiriage", c.level().getGameTime());
        return true;
    }

    /** 上挑砍中：处决特写开始，按时间轴一刀刀结算。 */
    private void launch(ServerLevel level, LivingEntity target) {
        if (!target.isAlive() || !FoxMarks.executable(target)) {
            executing = null;
            return;
        }
        FoxMarks.consume(target);
        ExecutionShot shot = ExecutionShot.of(c.getRandom().nextInt()).slashes(EXECUTION_SLASHES);
        int ticks = ExecutionShots.play(List.of(), null, target, shot);
        ExecutionTimeline tl = new ExecutionTimeline(shot);
        long now = level.getGameTime();
        executionEnds = now + ticks;
        float blade = (float) (c.foxfireDamage() * BLADE);
        for (ExecutionTimeline.Slash s : tl.slashes()) {
            scheduled.add(new Scheduled(now + Math.round(s.contact()), () -> {
                if (target.isAlive()) hit(level, target, blade * (float) EXECUTION_SLASH, false);
            }));
        }
        scheduled.add(new Scheduled(now + Math.round(tl.finalHit()), () -> {
            if (target.isAlive()) hit(level, target, blade * (float) EXECUTION_FINAL, false);
        }));
        scheduled.add(new Scheduled(now + Math.round(tl.impact()), () -> {
            if (!target.isAlive()) return;
            target.invulnerableTime = 0;
            target.resetFallDistance();
            target.hurtServer(level, target.damageSources().fall(), blade * (float) EXECUTION_FALL);
        }));
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.NEUTRAL, 1.2f, .6f);
        executions++;
    }

    private void endExecution(ServerLevel level) {
        executing = null;
        executionEnds = -1;
        scheduled.clear();
        if (runner != null && !runner.cleared()) runner.force("idle", level.getGameTime());
    }

    /** 测试用：处决过几次。 */
    static int executions;

    public static int executions() {
        return executions;
    }
}
