package dev.logos.dimension.combat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 同伴手里的战斗武器（docs/WEAPON_AI.md 第 2.2 节）：跑玩家那一份招式文件（角色有自己的就用自己的），对应玩家的 {@code LongswordCombat}。
 * 同伴「按键」（{@link #press}、{@link #hold}、{@link #intend}），接触时和玩家一样结算（{@link CombatHits}、{@link CombatEffects}），
 * 武器自己的规矩走 {@link CombatWeapon.Holder} 的钩子。挑哪一招、怎么按交给 {@link WeaponPilot}。
 */
public final class NpcWeapon implements MoveRunner.Actor {
    /** 拿着它的同伴：除了 {@link Wielder} 的那些，再告诉它动作前缀、招式开始和打中了谁。 */
    public interface Owner extends Wielder {
        /** 角色名：先找 {@code combat/movesets/<角色>/<武器>.json}（docs/WEAPON_AI.md 第 4 节）。 */
        String character();

        /** 同步动作：{@code clip} 为 null 时动作结束（收剑播完、被收回）。 */
        void showMove(@Nullable String clip, long start, float rate, boolean loop);

        /** 一刀打进去了（{@code target} 掉了血）。 */
        default void landed(LivingEntity target) {}
    }

    /** 招式里往目标带一步：起手到第一下接触最多带多远；停在两人半宽之外再留多少。 */
    public static final double STEP_MAX = .9, KEEP = .75;
    /** 起手到最后一下接触之间每刻最多转多少度追着目标。 */
    public static final float TRACK_TURN = 45;
    /** 一招砍完最后一下再过几刻能接下一招。 */
    public static final int RECOVER = 2;

    private final Owner w;
    private @Nullable MoveRunner runner;
    private @Nullable CombatWeapon weapon;
    private WeaponAi ai = WeaponAi.NONE;
    private String prefix = "";
    private boolean using;
    private final Set<String> intents = new HashSet<>();
    private final CombatHits.Combo combo = new CombatHits.Combo();
    private @Nullable Consumer<Moveset.Move> onMove;
    private @Nullable LivingEntity parriedFoe;
    private long parriedUntil;

    public NpcWeapon(Owner owner) {
        this.w = owner;
    }

    /** 每一招开始时告诉谁（驾驶员记下刚放过哪一招）。 */
    public void onMove(Consumer<Moveset.Move> listener) {
        onMove = listener;
    }

    // ---------------------------------------------------------------- 状态

    public Owner wielder() {
        return w;
    }

    public boolean drawn() {
        return runner != null && !runner.cleared();
    }

    public @Nullable MoveRunner runner() {
        return drawn() ? runner : null;
    }

    public Moveset.@Nullable Move move() {
        return drawn() ? runner.move() : null;
    }

    public WeaponAi ai() {
        return ai;
    }

    public boolean using() {
        return using;
    }

    private long now() {
        return w.entity().level().getGameTime();
    }

    /** 当前招式播了多少刻。 */
    public double age() {
        return runner == null ? 0 : runner.age(now());
    }

    /** 正在出招：这一招有接触、还没砍完。 */
    public boolean attacking() {
        Moveset.Move m = move();
        return m != null && m.hasContact() && age() < m.lastContact() * 20 / runner.rate() + RECOVER;
    }

    public boolean staggered() {
        return Parry.isStagger(move());
    }

    /** 可以按下一招：没在僵直、没在收剑；有接触的招砍完最后一下过了 {@link #RECOVER} 刻。 */
    public boolean canFollowUp() {
        Moveset.Move m = move();
        if (m == null || staggered() || m.name().equals(runner.moveset().sheathe())) return false;
        return !m.hasContact() || age() >= m.lastContact() * 20 / runner.rate() + RECOVER;
    }

    /** 刚被她弹开、还在僵直里的那个（过了、死了就是 null）。 */
    public @Nullable LivingEntity parriedFoe() {
        return parriedFoe != null && now() <= parriedUntil && parriedFoe.isAlive() ? parriedFoe : null;
    }

    public long parriedUntil() {
        return parriedUntil;
    }

    // ---------------------------------------------------------------- 拔剑、收剑

    /** 拔剑：在招式文件的 {@code start} 起跑（已经在手上就不动）。武器不是战斗武器、招式读不到时返回假。 */
    public boolean draw() {
        if (drawn()) return true;
        if (!(w.entity().level() instanceof ServerLevel level)) return false;
        weapon = CombatWeapon.of(w.weapon());
        if (weapon == null) return false;
        Identifier id = Movesets.forCharacter(level.getServer(), w.character(), weapon.moveset());
        Moveset set = Movesets.get(level.getServer(), id);
        if (set == null) return false;
        ai = Movesets.ai(level.getServer(), id);
        prefix = ai.clips() == null ? "" : ai.clips() + "_";
        using = false;
        intents.clear();
        runner = new MoveRunner(set, this, level.getGameTime());
        // 构造时第一招已经开始，那时 runner 还没赋上
        moveStarted(runner);
        return true;
    }

    /** 收剑：播招式文件的 {@code sheathe}，播完动作结束。 */
    public void sheathe() {
        if (!drawn()) return;
        using = false;
        String s = runner.moveset().sheathe();
        if (s != null && !s.equals(runner.move().name())) runner.force(s, now());
        else if (s == null) drop();
    }

    /** 立刻收掉（倒下、被收回）。 */
    public void drop() {
        boolean had = runner != null;
        runner = null;
        using = false;
        intents.clear();
        parriedFoe = null;
        if (had) w.showMove(null, 0, 1, false);
    }

    // ---------------------------------------------------------------- 按键

    /** 按一下（左键、松开左键、换弹……）；接受（立即切换或缓冲）时返回真。按完身法开关就关上。 */
    public boolean press(Moveset.Input input) {
        if (!drawn()) return false;
        long now = now();
        boolean ok;
        if (input == Moveset.Input.ATTACK && w.weapon().getItem() instanceof CombatWeapon.Holder h && !h.allowsAttack(w, runner.move().name())) ok = false;
        else ok = runner.input(input, now);
        intents.clear();
        return ok;
    }

    /** 按住 / 松开右键：按住时每刻补一次 {@code use_press}，松开时每刻补一次 {@code use_release}（同玩家）。 */
    public void hold(boolean on) {
        if (using == on) return;
        using = on;
        if (drawn()) {
            runner.input(on ? Moveset.Input.USE_PRESS : Moveset.Input.USE_RELEASE, now());
            intents.clear();
        }
    }

    /** 这一下想走的身法（{@code sneak}、{@code sprint}），下一次按键用完就关。 */
    public void intend(String flag) {
        intents.add(flag);
    }

    public boolean intends(String flag) {
        return intents.contains(flag);
    }

    /** 不经转移直接切（角色自己的本事，例如拉塞塔的龙骑武艺随手弹开）。招式文件里没有这一招时不动。 */
    public void force(String move) {
        if (drawn()) runner.force(move, now());
    }

    // ---------------------------------------------------------------- 每刻

    public void tick() {
        if (runner == null) return;
        long now = now();
        Moveset.Move m = runner.move();
        double t = runner.time(now);
        track(m, t);
        runner.tick(now);
        if (runner == null || runner.cleared()) return;
        runner.input(using ? Moveset.Input.USE_PRESS : Moveset.Input.USE_RELEASE, now);
        boolean propelled = w.weapon().getItem() instanceof CombatWeapon.Holder h && h.combatTick(w, runner.move().name());
        if (!propelled && runner.move() == m) approach(m, t - runner.rate() / 20, runner.time(now) + 1e-9);
    }

    /** 起手到最后一下接触之前（动作时间 {@code t} 秒）：身子追着目标转。 */
    private void track(Moveset.Move m, double t) {
        LivingEntity at = w.aimed();
        if (!(w.entity() instanceof Mob mob) || at == null || !at.isAlive() || !m.hasContact() || t > m.lastContact()) return;
        mob.lookAt(at, TRACK_TURN, 30);
        mob.yBodyRot = mob.getYRot();
        mob.setYHeadRot(mob.getYRot());
    }

    /** 起手到第一下接触之间往目标带一步（目标被打退了也跟得上），走到两人半宽之外 {@link #KEEP} 就停。 */
    private void approach(Moveset.Move m, double before, double after) {
        LivingEntity at = w.aimed();
        LivingEntity self = w.entity();
        if (at == null || !at.isAlive() || !m.hasContact()) return;
        double to = m.contact();
        double overlap = Math.min(after, to) - Math.max(before, 0);
        if (to <= 0 || overlap <= 0) return;
        Vec3 gap = at.position().subtract(self.position()).multiply(1, 0, 1);
        double need = gap.length() - KEEP - (at.getBbWidth() + self.getBbWidth()) * .5;
        if (need <= .02) return;
        self.move(MoverType.SELF, gap.normalize().scale(Math.min(need, STEP_MAX * overlap / to)));
    }

    // ---------------------------------------------------------------- 招式状态机

    @Override
    public double attackSpeed() {
        return w.attackSpeed();
    }

    @Override
    public void moveStarted(MoveRunner r) {
        if (r != runner) return;
        if (r.cleared()) {
            runner = null;
            using = false;
            w.showMove(null, 0, 1, false);
            return;
        }
        Moveset.Move m = r.move();
        w.showMove(prefix + m.clip(), (long) r.start(), (float) r.rate(), m.playback() == Moveset.Playback.LOOP);
        if (onMove != null) onMove.accept(m);
    }

    @Override
    public boolean allows(String requirement) {
        return w.weapon().getItem() instanceof CombatWeapon.Holder h && h.allows(w, requirement);
    }

    @Override
    public void fire(MoveRunner r) {
        if (w.weapon().getItem() instanceof CombatWeapon.Holder h) h.fire(w, r.move(), r.fireIndex());
    }

    @Override
    public void feed(MoveRunner r) {
        if (w.weapon().getItem() instanceof CombatWeapon.Holder h) h.feed(w, r.move());
    }

    /** 接触：同玩家（锥形、对着的那个排第一、招式倍数、附加效果、连招自己的受击冷却），伤害先问武器，武器不管就是生物近战伤害。 */
    @Override
    public void contact(MoveRunner r) {
        LivingEntity self = w.entity();
        if (!(self.level() instanceof ServerLevel level) || weapon == null) return;
        Moveset.Move move = r.move();
        CombatWeapon.Holder holder = w.weapon().getItem() instanceof CombatWeapon.Holder h ? h : null;
        if (holder != null && !holder.swing(w, move)) return;
        List<Entity> targets = targets(level, move);
        CombatEffects.contact(w, move, targets);
        if (targets.isEmpty()) {
            if (!CombatEffects.quietWhiff(move))
                level.playSound(null, self.getX(), self.getY(), self.getZ(), SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.NEUTRAL, .8f, 1);
            return;
        }
        long now = level.getGameTime();
        combo.forget(now);
        boolean first = true, any = false;
        for (Entity target : targets) {
            if (!self.isAlive() || r != runner) break;
            combo.clearOwn(target, self);
            float before = target instanceof LivingEntity l ? l.getHealth() + l.getAbsorptionAmount() : 0;
            CombatHits.begin(move, target);
            try {
                if (holder == null || !holder.strike(w, target, move)) target.hurtServer(level, self.damageSources().mobAttack(self), (float) w.attackDamage());
            } finally {
                CombatHits.end();
            }
            combo.record(target, self, now);
            if (target instanceof LivingEntity l && (!l.isAlive() || l.getHealth() + l.getAbsorptionAmount() < before)) {
                any = true;
                w.landed(l);
            }
            // 这一下被弹开（招式已经换成被弹反）：后面的不再结算
            if (runner == null || runner.move() != move) break;
            CombatEffects.hit(w, target, move);
            if (holder != null) holder.hit(w, target, move, first);
            first = false;
        }
        level.playSound(null, self.getX(), self.getY(), self.getZ(), any ? SoundEvents.PLAYER_ATTACK_SWEEP : SoundEvents.PLAYER_ATTACK_NODAMAGE,
                SoundSource.NEUTRAL, .8f, .95f + self.getRandom().nextFloat() * .15f);
    }

    /** 这一刀打到谁：她对着的那个（够得着、看得见）排第一，其余是锥形里的。 */
    private List<Entity> targets(ServerLevel level, Moveset.Move move) {
        LivingEntity self = w.entity();
        Vec3 from = self.getEyePosition(), look = w.aim();
        double reach = w.reach();
        double degrees = move.cone() > 0 ? move.cone() : weapon.cone();
        LivingEntity aimed = w.aimed();
        if (aimed != null) {
            Vec3 near = CombatHits.nearest(aimed.getBoundingBox(), from, look, reach);
            Vec3 d = near.subtract(from);
            double dist = d.length();
            boolean inside = dist <= reach && (dist < 1e-4 || d.dot(look) / dist >= Math.cos(Math.toRadians(degrees / 2)));
            if (!inside || !aimed.isAlive() || !w.mayHit(aimed) || !self.hasLineOfSight(aimed)) aimed = null;
        }
        return CombatHits.cone(level, self, from, look, reach, degrees, aimed, w::mayHit);
    }

    // ---------------------------------------------------------------- 格挡、被弹反

    /**
     * 有东西打她：在接受 {@code block} 的招式里（横防）、正面来的近战或弹射物，挡下（返回真，这一下不受伤），交给招式文件的 {@code block}；
     * 转进带 {@code effect: parry} 的招式就弹开，近战的对方僵直（记在 {@link #parriedFoe}）。弹不开的攻击只算挡下。
     */
    public boolean block(DamageSource source) {
        Moveset.Move m = move();
        if (m == null || !m.accepts(Moveset.Input.BLOCK)) return false;
        boolean projectile = source.getDirectEntity() instanceof Projectile;
        if (!projectile && !Parry.melee(source)) return false;
        Vec3 from = source.getSourcePosition();
        LivingEntity self = w.entity();
        if (from != null) {
            Vec3 to = from.subtract(self.position()).multiply(1, 0, 1);
            if (to.lengthSqr() > 1e-4 && to.normalize().dot(Vec3.directionFromRotation(0, self.getYRot())) < 0) return false;
        }
        long now = now();
        boolean parryable = !source.is(BossStrike.UNPARRYABLE);
        boolean parried = false;
        if (runner.input(Moveset.Input.BLOCK, now) && parryable && !projectile && CombatEffects.PARRY.equals(runner.move().effect())
                && Parry.stagger(source, self)) {
            parried = true;
            if (source.getEntity() instanceof LivingEntity attacker) remember(attacker, now);
        }
        if (w.weapon().getItem() instanceof CombatWeapon.Holder h) h.blocked(w, source, parried);
        return true;
    }

    /** 记下刚被她弹开的那个（之后 {@code 14} 刻里驾驶员会反击它，对方僵直 16 刻）。 */
    public void remember(LivingEntity parriedFoe, long now) {
        this.parriedFoe = parriedFoe;
        this.parriedUntil = now + 14;
    }

    /** 她这一刀被弹开：进 NPC 的僵直（招式文件的 {@code parried_npc}），放下横防。 */
    public boolean parried() {
        if (!drawn() || !Parry.staggerNpc(runner, now())) return false;
        using = false;
        return true;
    }

    // ---------------------------------------------------------------- 工具

    /** 空手的玩家拿着这把武器一下打多少：1 + 主手的攻击力修饰（加法的那些）。同伴的一下 = 她的攻击 + 这个。 */
    public static double handDamage(ItemStack stack) {
        double d = 1;
        ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (var e : mods.modifiers())
            if (e.attribute().is(Attributes.ATTACK_DAMAGE) && e.slot().test(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                    && e.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) d += e.modifier().amount();
        return d;
    }
}
