package dev.logos.dimension.companion;

import dev.logos.dimension.npc.LogosMagicBridge;
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
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀的魔法（docs/KOHAKU_COMPANION.md 第 5、7 节）：
 * <ul>
 *   <li>琥珀护膜（本来就会）：一打起来先给自己罩一层护盾，生命上限的倍数（一倍起，每枚爪之契 +0.25），Logos 的法术护盾（吸收）。
 *       碎了 {@link #WARD_REFORM} 刻后才能重凝，凝一次花魔力上限的 {@link #WARD_COST}。</li>
 *   <li>术式：她自己的（{@link KohakuSpells}）和你给的那根杖上挂着的页（{@link KohakuSpells.Yours}），都是她自己施放（{@code Casting.castAs}，
 *       施法者就是她），伤害按她的法术强度。她自己的按手里那根杖的档位注入，你的按那一页自己的档位；先用杖里存的（瞬发），
 *       不够再从她身上蓄。</li>
 *   <li>魔力少（{@link #LOW} 以下）时只剩护膜要紧：攻击只放最省的一页，不放支援（{@link KohakuMind} 管）。</li>
 * </ul>
 * 施法有动作，到出手的那一刻才生效：对着敌人的点杖（{@code cast_staff}），给同伴的、护膜拄杖（{@code cast_ward}）。
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
    /** 对着敌人的术式打多远。 */
    static final double RANGE = 22;

    enum Kind { WARD, PAGE }

    private final KohakuCompanion c;
    private @Nullable Kind casting;
    /** 正在放的这一页：术式、档位、怎么放、冷却记在哪、冷却多久、是不是你给的、她自己的那一页（你的为 null）。 */
    private @Nullable Spell spell;
    private PageTier tier = PageTier.BASIC;
    private KohakuSpells.Aim aim = KohakuSpells.Aim.FOE;
    private String key = "";
    private int cooldown;
    private boolean yours;
    private @Nullable KohakuSpells own;
    private @Nullable LivingEntity target;
    private @Nullable Vec3 here;
    private long castStart = -1;
    private int releaseAt, endAt;
    private double injection;
    /** 这一次从杖里出多少（其余从她身上出）。 */
    private double fromStore;
    private long wardReadyAt;
    private boolean wardWasUp;
    private final Map<String, Long> ready = new HashMap<>();
    /** 测试用：凝过几次护膜、护膜碎过几次、放过几页术式（其中你给的几页）、每一页她自己的放过几次。 */
    static int wards, wardBreaks, casts, yoursCasts;
    static final Map<String, Integer> CAST_COUNT = new HashMap<>();

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

    /** 魔力少了：身上的加杖里存的，不到上限的三成。 */
    boolean low() {
        return c.available() < c.maxMana() * LOW;
    }

    boolean ready(KohakuSpells s, long now) {
        return now >= ready.getOrDefault(s.id(), 0L);
    }

    boolean ready(KohakuSpells.Yours y, long now) {
        return now >= ready.getOrDefault(y.key(), 0L);
    }

    /** 她自己的这一页会了、冷却好了。 */
    boolean usable(KohakuSpells s, long now) {
        return s.unlocked(c.seals()) && ready(s, now);
    }

    void cancel() {
        if (casting == null) return;
        casting = null;
        spell = null;
        own = null;
        target = null;
        castStart = -1;
        if (c.move().startsWith("cast_")) c.setMove("");
    }

    /** 凝护膜：长杖往地上一拄。 */
    boolean startWard(long now) {
        if (!wardReady(now) || c.eating() || c.moves.busy()) return false;
        c.mood.cut();
        casting = Kind.WARD;
        spell = null;
        own = null;
        target = c;
        castStart = now;
        releaseAt = WARD_AT;
        endAt = WARD_TICKS;
        c.setMove("cast_ward|" + now + "|1|false");
        c.getNavigation().stop();
        c.fought(now);
        return true;
    }

    /** 放她自己的 {@code s}：对着（或给）{@code t}，落点 {@code at}（{@code CLUSTER}、{@code ALLY} 用，别的可以为 null）。 */
    boolean startPage(KohakuSpells s, LivingEntity t, @Nullable Vec3 at, long now) {
        if (!usable(s, now) || !(c.level() instanceof ServerLevel level)) return false;
        Spell sp = spell(level, s);
        if (sp == null) return false;
        if (!start(sp, c.staff.pageTier(), s.aim(), s.id(), s.cooldown(), t, at, now)) return false;
        own = s;
        yours = false;
        return true;
    }

    boolean startPage(KohakuSpells s, LivingEntity t, long now) {
        return startPage(s, t, null, now);
    }

    /** 放你给的那一页 {@code y}。 */
    boolean startYours(KohakuSpells.Yours y, LivingEntity t, @Nullable Vec3 at, long now) {
        if (!ready(y, now)) return false;
        if (!start(y.spell(), y.tier(), y.aim(), y.key(), y.cooldown(), t, at, now)) return false;
        own = null;
        yours = true;
        return true;
    }

    /**
     * 开始放一页：注入量是页上写的和档位容量里小的那个。先用杖里存的：够就瞬发；不够的从她身上补，补的那部分按杖的蓄力算时间
     * （大术式要先把杖充满才放得出）；加起来也不够就不放（返回假，不说话也不显示文字）。
     */
    private boolean start(Spell sp, PageTier t, KohakuSpells.Aim a, String k, int cd, LivingEntity who, @Nullable Vec3 at, long now) {
        if (casting() || c.eating() || c.moves.busy()) return false;
        double mana = Casting.injection(sp, t, 0).mana();
        boolean ally = a == KohakuSpells.Aim.ALLY || a == KohakuSpells.Aim.AT || a == KohakuSpells.Aim.SELF;
        int at0 = ally ? WARD_AT : CAST_AT, length = ally ? WARD_TICKS : CAST_TICKS;
        int charge;
        double store = c.staff.store();
        if (store >= mana) {
            fromStore = mana;
            charge = Math.round(at0 / QUICK);
        } else if (store + c.mana() >= mana) {
            fromStore = store;
            charge = Math.max(at0, c.staff.chargeTicks(mana - store));
        } else {
            return false;
        }
        c.mood.cut();
        casting = Kind.PAGE;
        spell = sp;
        tier = t;
        aim = a;
        key = k;
        cooldown = cd;
        target = who;
        here = at;
        injection = mana;
        castStart = now;
        float speed = Mth.clamp((float) at0 / charge, .25f, QUICK);
        releaseAt = Math.round(at0 / speed);
        endAt = Math.round(length / speed);
        c.setMove((ally ? "cast_ward|" : "cast_staff|") + now + "|" + speed + "|false");
        c.getNavigation().stop();
        if (!ally) c.fought(now);
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
        if (casting == Kind.PAGE && t != c) {
            c.getLookControl().setLookAt(t, 40, 40);
            if (aim == KohakuSpells.Aim.FOE || aim == KohakuSpells.Aim.AT) KohakuMind.faceExactly(c, t);
        }
        long elapsed = now - castStart;
        if (elapsed == releaseAt) release(level, now);
        if (elapsed >= endAt) {
            casting = null;
            spell = null;
            own = null;
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
        Spell sp = spell;
        LivingEntity t = target;
        if (sp == null || t == null) return;
        double fromStaff = Math.min(fromStore, c.staff.store());
        if (fromStaff < fromStore - 1e-6 || c.mana() < injection - fromStaff - 1e-6) return;  // 中途被掏空了（别的东西吸走了魔力）：不放
        c.staff.setStore(c.staff.store() - fromStaff);
        c.spendMana(injection - fromStaff);
        Vec3 at = here;
        switch (aim) {
            case FOE, AT -> {
                if (t != c) KohakuMind.faceExactly(c, t);
                at = null;
            }
            case ARC -> {
                lob(at != null ? at : t.position().add(0, t.getBbHeight() * .4, 0));
                at = null;
            }
            case CLUSTER, ALLY -> {
                if (at == null) at = t.position();
            }
            case SELF -> at = null;
        }
        Casting.castAs(c, sp, injection, tier.recovery(), t, at);
        ready.put(key, now + cooldown);
        casts++;
        if (yours) yoursCasts++;
        if (own != null) CAST_COUNT.merge(own.id(), 1, Integer::sum);
    }

    /**
     * 抛出去（沸腾烧瓶）：转过去对着 {@code to}，按 Logos 抛物线弹射物的速度和下坠算好仰角（低的那条弹道）；够不着就 45°。
     */
    private void lob(Vec3 to) {
        Vec3 eye = c.getEyePosition(), d = to.subtract(eye);
        double x = Math.sqrt(d.x * d.x + d.z * d.z), y = d.y, v = KohakuSpells.FLASK_SPEED, g = KohakuSpells.ARC_GRAVITY;
        double disc = v * v * v * v - g * (g * x * x + 2 * y * v * v);
        double theta = disc < 0 || x < 1e-3 ? Math.PI / 4 : Math.atan((v * v - Math.sqrt(disc)) / (g * x));
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90;
        c.setYRot(yaw);
        c.yBodyRot = yaw;
        c.setYHeadRot(yaw);
        c.setXRot((float) -Math.toDegrees(theta));
    }

    // ---------------------------------------------------------------- 照顾谁

    /**
     * 她照顾的（第 7.3 节）：召唤她的玩家、这位玩家的宠物（包括车万女仆这类有主人的生物）和使魔、别的同伴、她自己。
     */
    static boolean ally(LivingEntity e, ServerPlayer owner, KohakuCompanion c) {
        if (!e.isAlive() || e.isSpectator()) return false;
        if (e == owner || e == c) return true;
        if (e instanceof OwnableEntity o && o.getOwner() != null && o.getOwner().getUUID().equals(owner.getUUID())) return true;
        if (owner.getUUID().equals(LogosMagicBridge.masterOf(e))) return true;
        return e instanceof CompanionEntity other && owner.getUUID().equals(other.ownerId());
    }

    String describe(long now) {
        String cast = casting == null ? "-" : casting.name().toLowerCase() + (key.isEmpty() || casting == Kind.WARD ? "" : ":" + key) + " " + (now - castStart);
        return cast + " ward=" + (wardUp() ? String.format("%.0f", c.getAbsorptionAmount()) : now < wardReadyAt ? "reform" : "ready");
    }
}
