package dev.logos.dimension.companion;

import dev.logos.item.PageTier;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Effects;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀的魔法（docs/KOHAKU_COMPANION.md 第 5、7 节）：
 * <ul>
 *   <li>琥珀护膜（本来就会）：一打起来先给自己罩一层护盾，生命上限的倍数（一倍起，每枚爪之契 +0.25），Logos 的法术护盾（吸收）。
 *       碎了 {@link #WARD_REFORM} 刻后才能重凝，凝一次花魔力上限的 {@link #WARD_COST}。</li>
 *   <li>她的术式（{@link KohakuSpells}）：她自己施放载页（{@code Casting.castAs}，施法者就是她），伤害按她的法术强度。
 *       注入量是页上写的和档位容量里小的那个；先用杖里存的（瞬发），不够再从她身上蓄。</li>
 *   <li>魔力少（{@link #LOW} 以下）时只剩护膜要紧：攻击只放最省的一页，不放支援。</li>
 * </ul>
 * 施法有动作（{@code cast_staff}、{@code cast_ward}，走 combat 控制器），到出手的那一刻才生效。
 */
final class KohakuMagic {
    /** 魔力少于上限的几成算「少」。 */
    static final double LOW = .3;
    /** 护膜：花魔力上限的几成；碎了多少刻后才能重凝；倍率（一倍起，每枚爪之契加多少）。 */
    static final double WARD_COST = .15, WARD_BASE = 1, WARD_STEP = .25;
    static final int WARD_REFORM = 120;
    /** 动作：多长、第几刻出手（同模型的 release / ward 标记）。 */
    static final int CAST_TICKS = 18, CAST_AT = 9, WARD_TICKS = 24, WARD_AT = 12;
    /** 从杖里瞬发时动作播多快。 */
    static final float QUICK = 1.25f;
    /** 一滴就够打多远。 */
    static final double RANGE = 22;

    enum Kind { WARD, PAGE }

    private final KohakuCompanion c;
    private @Nullable Kind casting;
    private @Nullable KohakuSpells page;
    private @Nullable LivingEntity target;
    private long castStart = -1;
    private int releaseAt, endAt;
    private double injection;
    private boolean fromStore;
    private long wardReadyAt;
    private boolean wardWasUp;
    private final Map<String, Long> ready = new HashMap<>();
    /** 测试用：凝过几次护膜、护膜碎过几次、放过几页术式。 */
    static int wards, wardBreaks, casts;

    KohakuMagic(KohakuCompanion c) {
        this.c = c;
    }

    boolean casting() {
        return casting != null;
    }

    /** 护膜是几倍生命上限。 */
    static double wardMultiplier(int seals) {
        return WARD_BASE + WARD_STEP * Math.clamp(seals, 0, KohakuCompanion.MAX_SEALS);
    }

    /** 护膜这一次有多厚。 */
    double wardAmount() {
        return c.getMaxHealth() * wardMultiplier(c.seals());
    }

    double wardCost() {
        return c.maxMana() * WARD_COST;
    }

    /** 护膜还在。 */
    boolean wardUp() {
        return Effects.shielded(c) && c.getAbsorptionAmount() > 0;
    }

    /** 能凝护膜：没有护膜、碎了以后歇够了、魔力够。 */
    boolean wardReady(long now) {
        return !wardUp() && now >= wardReadyAt && c.mana() >= wardCost() && !casting();
    }

    /** 魔力少了。 */
    boolean low() {
        return c.mana() < c.maxMana() * LOW;
    }

    boolean ready(KohakuSpells s, long now) {
        return now >= ready.getOrDefault(s.id(), 0L);
    }

    void cancel() {
        if (casting == null) return;
        casting = null;
        page = null;
        target = null;
        castStart = -1;
        if (c.move().startsWith("cast_")) c.setMove("");
    }

    /** 凝护膜：长杖往地上一拄。 */
    boolean startWard(long now) {
        if (!wardReady(now) || c.eating()) return false;
        c.mood.cut();
        casting = Kind.WARD;
        page = null;
        target = c;
        castStart = now;
        releaseAt = WARD_AT;
        endAt = WARD_TICKS;
        c.setMove("cast_ward|" + now + "|1|false");
        c.getNavigation().stop();
        c.fought(now);
        return true;
    }

    /**
     * 对着 {@code t} 放 {@code s}：注入量是页上写的和她手里那根杖的档位容量里小的那个。杖里存的够就瞬发，不然从她身上蓄（按杖的蓄力），
     * 身上也不够就不放（返回假，不说话也不显示文字）。
     */
    boolean startPage(KohakuSpells s, LivingEntity t, long now) {
        if (casting() || c.eating() || !s.unlocked(c.seals()) || !ready(s, now) || !(c.level() instanceof ServerLevel level)) return false;
        Spell spell = spell(level, s);
        if (spell == null) return false;
        PageTier tier = c.staff.pageTier();
        double mana = Casting.injection(spell, tier, 0).mana();
        int charge;
        if (c.staff.store() >= mana) {
            fromStore = true;
            charge = Math.round(CAST_AT / QUICK);
        } else if (c.mana() >= mana) {
            fromStore = false;
            charge = Math.max(CAST_AT, c.staff.chargeTicks(mana));
        } else {
            return false;
        }
        c.mood.cut();
        casting = Kind.PAGE;
        page = s;
        target = t;
        injection = mana;
        castStart = now;
        float speed = Mth.clamp((float) CAST_AT / charge, .25f, QUICK);
        releaseAt = Math.round(CAST_AT / speed);
        endAt = Math.round(CAST_TICKS / speed);
        c.setMove("cast_staff|" + now + "|" + speed + "|false");
        c.getNavigation().stop();
        c.fought(now);
        return true;
    }

    private @Nullable Spell spell(ServerLevel level, KohakuSpells s) {
        ServerPlayer p = c.ownerPlayer();
        return s.spell(level.getServer(), p != null && Companions.bond(p, c.def()).pvp());
    }

    void tick(ServerLevel level, long now) {
        boolean up = wardUp();
        if (wardWasUp && !up) {
            // 护膜碎了：一圈碎光，歇一会儿才能重凝
            wardReadyAt = now + WARD_REFORM;
            wardBreaks++;
            Vec3 at = c.position().add(0, .9, 0);
            level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, 1.1f), at.x, at.y, at.z, 20, .45, .55, .45, 0);
            level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 10, .4, .5, .4, .2);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.NEUTRAL, .9f, 1.3f);
            ((KohakuMood) c.mood).wardBroke(now);
        }
        wardWasUp = up;
        if (casting == null) return;
        LivingEntity t = target;
        if (t == null || !t.isAlive()) {
            cancel();
            return;
        }
        if (casting == Kind.PAGE) {
            c.getLookControl().setLookAt(t, 40, 40);
            KohakuMind.faceExactly(c, t);
        }
        long elapsed = now - castStart;
        if (elapsed == releaseAt) release(level, now);
        if (elapsed >= endAt) {
            casting = null;
            page = null;
            target = null;
            castStart = -1;
            if (c.move().startsWith("cast_")) c.setMove("");
        }
    }

    private void release(ServerLevel level, long now) {
        if (casting == Kind.WARD) {
            if (wardUp() || c.mana() < wardCost()) return;
            c.spendMana(wardCost());
            Effects.shield(c, wardAmount());
            wardWasUp = true;
            wards++;
            Vec3 at = c.position();
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2 * i / 16;
                level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, 1f), at.x + Math.cos(a) * .9, at.y + .1, at.z + Math.sin(a) * .9,
                        1, 0, .02, 0, 0);
            }
            level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 1, at.z, 8, .35, .5, .35, .01);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, .9f, 1.2f);
            return;
        }
        KohakuSpells s = page;
        LivingEntity t = target;
        if (s == null || t == null) return;
        Spell spell = spell(level, s);
        if (spell == null) return;
        double paid = fromStore ? Math.min(injection, c.staff.store()) : c.spendMana(injection);
        if (fromStore) c.staff.setStore(c.staff.store() - paid);
        if (paid < injection - 1e-6) {
            // 中途被掏空了（比如别的东西吸走了魔力）：退回去，不放
            c.receiveMana(paid);
            return;
        }
        KohakuMind.faceExactly(c, t);
        Casting.castAs(c, spell, injection, c.staff.pageTier().recovery(), t, null);
        ready.put(s.id(), now + s.cooldown());
        casts++;
    }

    String describe(long now) {
        String cast = casting == null ? "-" : casting.name().toLowerCase() + (page == null ? "" : ":" + page.id()) + " " + (now - castStart);
        return cast + " ward=" + (wardUp() ? String.format("%.0f", c.getAbsorptionAmount()) : now < wardReadyAt ? "reform" : "ready");
    }
}
