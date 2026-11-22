package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.npc.ForestElf;
import dev.logos.dimension.npc.LogosMagicBridge;
import dev.logos.lang.Element;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Effects;
import dev.logos.runtime.ManaWrap;
import dev.logos.runtime.SpellModifiers;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 精灵公主的生命涌动（docs/ELF_PRINCESS_COMPANION.md 第 4 节）。数都乘她的法术强度（{@link ElfPrincessCompanion#spellPower}）。
 * 她是辅助：本事都花在照顾同伴上，打法上离敌人远远的（{@link PrincessMind}）。
 * <ul>
 *   <li>治疗（本来就会）：身边受伤的同伴——主人、主人的宠物和使魔、别的同伴、森林精灵、她自己——血最少的那个，回一口血；
 *       它身边 {@link #MEND_SPLASH} 格内别的受伤同伴也回一半。</li>
 *   <li>常青之息（本来就会）：每 {@link #AURA_EVERY} 刻，她身边 {@link #AURA_RANGE} 格内受伤的同伴都回一点血，不用施法动作。</li>
 *   <li>生命缠绕（第一印）：给打起来的主人的手缠上生命之力（Logos 魔力缠绕，生命元素），每下附带生命伤害。</li>
 *   <li>叶之护（第二印）：快撑不住、又被盯上的那位先给一层护盾（Logos 法术护盾，吸收伤害）。</li>
 *   <li>荆棘缠绕（第三印）：在强敌脚边用游脉留下一圈荆棘，碰到就受生命伤害、被往圈中间拉（Logos 载页 {@code companion/spells/thorn_snare.logos}）。</li>
 *   <li>万分之一（第四印）：主人快倒下时，哪怕只有万分之一的机会也要试——一下回主人一半的血，再给几秒生命恢复。每个游戏日一次。</li>
 * </ul>
 * 施法有动作（{@code cast_heal}、{@code cast_bless}、{@code cast_thorns}，走 combat 控制器），到出手的那一刻才生效。
 */
final class PrincessMagic {
    /** 治疗：多远、血低于几成才治、回多少（乘法强）、冷却（刻）；目标身边多远以内的也回一半。 */
    static final double MEND_RANGE = 14, MEND_BELOW = .85, MEND_HEAL = 5, MEND_SPLASH = 4;
    static final int MEND_COOLDOWN = 80;
    /** 常青之息：多远以内、每次回多少（乘法强）、每隔几刻。 */
    static final double AURA_RANGE = 8, AURA_HEAL = .5;
    static final int AURA_EVERY = 40;
    /** 生命缠绕：每下附带的生命伤害（乘法强）、缠多少秒、冷却。 */
    static final double BLESS_DAMAGE = 2.5, BLESS_SECONDS = 30;
    static final int BLESS_COOLDOWN = 900, BLESS_SEAL = 1;
    /** 叶之护：血低于几成、又被盯上时给；吸收多少（乘法强）、冷却。 */
    static final double SHIELD_BELOW = .6, SHIELD = 8;
    static final int SHIELD_COOLDOWN = 240, SHIELD_SEAL = 2;
    /** 荆棘缠绕：多远以内、冷却；载页里写的魔力（同玩家写的那一页）。 */
    static final double SNARE_RANGE = 20;
    static final int SNARE_COOLDOWN = 400, SNARE_SEAL = 3, SNARE_MANA = 500;
    /** 万分之一：主人血低于几成时；回主人生命上限的几成；生命恢复 II 几刻。 */
    static final float CHANCE_BELOW = .25f, CHANCE_HEAL = .5f;
    static final int CHANCE_REGEN = 100, CHANCE_SEAL = 4;
    /** 施法动作：多长、第几刻出手（同模型的 heal / bless / root 标记）。 */
    static final int HEAL_TICKS = 16, HEAL_AT = 7, BLESS_TICKS = 20, BLESS_AT = 10, THORN_TICKS = 26, THORN_AT = 10;

    enum Kind { MEND, SHIELD, BLESS, SNARE }

    private final ElfPrincessCompanion c;
    private @Nullable Kind casting;
    private @Nullable LivingEntity target;
    private long castStart = -1;
    private final Map<Kind, Long> ready = new HashMap<>();
    /** 测试用：治过几次、给过几次护盾、缠过几次、放过几次荆棘、万分之一用过几次、常青之息回过几次血。 */
    static int mends, shields, blesses, snares, chances, breaths;

    PrincessMagic(ElfPrincessCompanion c) {
        this.c = c;
    }

    boolean casting() {
        return casting != null;
    }

    boolean ready(Kind k, long now) {
        return now >= ready.getOrDefault(k, 0L);
    }

    long readyAt(Kind k) {
        return ready.getOrDefault(k, 0L);
    }

    boolean unlocked(int seal) {
        return CompanionBond.unlocked(c.seals(), seal);
    }

    void cancel() {
        if (casting == null) return;
        casting = null;
        target = null;
        castStart = -1;
        if (c.move().startsWith("cast_")) c.setMove("");
    }

    /** 开始施放 {@code k}，对着 {@code t}。手上拉着弓、在吃东西时不放。 */
    boolean start(Kind k, LivingEntity t) {
        if (casting() || c.bow.drawing() || c.eating() || !ready(k, c.level().getGameTime()) || !(c.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        casting = k;
        target = t;
        castStart = now;
        String clip = switch (k) {
            case MEND, SHIELD -> "cast_heal";
            case BLESS -> "cast_bless";
            case SNARE -> "cast_thorns";
        };
        c.setMove(clip + "|" + now + "|1|false");
        c.getNavigation().stop();
        c.getLookControl().setLookAt(t, 40, 40);
        if (k == Kind.SNARE || k == Kind.BLESS) c.fought(now);
        return true;
    }

    void tick(ServerLevel level, long now, @Nullable ServerPlayer p) {
        if (p != null) chance(level, now, p);
        if (p != null && now % AURA_EVERY == 0) breathe(level, p);
        if (casting == null) return;
        LivingEntity t = target;
        if (t == null || !t.isAlive()) {
            cancel();
            return;
        }
        c.getLookControl().setLookAt(t, 40, 40);
        int at = switch (casting) {
            case MEND, SHIELD -> HEAL_AT;
            case BLESS -> BLESS_AT;
            case SNARE -> THORN_AT;
        };
        int length = switch (casting) {
            case MEND, SHIELD -> HEAL_TICKS;
            case BLESS -> BLESS_TICKS;
            case SNARE -> THORN_TICKS;
        };
        long elapsed = now - castStart;
        if (elapsed == at) release(level, now, casting, t, p);
        if (elapsed >= length) {
            casting = null;
            target = null;
            castStart = -1;
            if (c.move().startsWith("cast_")) c.setMove("");
        }
    }

    private void release(ServerLevel level, long now, Kind k, LivingEntity t, @Nullable ServerPlayer p) {
        float power = c.spellPower();
        switch (k) {
            case MEND -> {
                t.heal((float) (MEND_HEAL * power));
                stream(level, t, 1);
                around(level, ParticleTypes.HAPPY_VILLAGER, t, 8);
                // 涌出来的生命之力溅到旁边：身边受伤的同伴也回一半
                if (p != null) {
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(MEND_SPLASH),
                            e -> e != t && ally(e, p) && e.getHealth() < e.getMaxHealth())) {
                        e.heal((float) (MEND_HEAL * power * .5));
                        around(level, ParticleTypes.HAPPY_VILLAGER, e, 4);
                    }
                }
                level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, .8f, 1.6f);
                ready.put(Kind.MEND, now + MEND_COOLDOWN);
                mends++;
            }
            case SHIELD -> {
                Effects.shield(t, SHIELD * power);
                stream(level, t, 1.2f);
                around(level, ParticleTypes.CHERRY_LEAVES, t, 14);
                level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, .9f, 1.3f);
                ready.put(Kind.SHIELD, now + SHIELD_COOLDOWN);
                // 护盾也算一次治疗的时机：别马上又去治同一个人
                ready.put(Kind.MEND, Math.max(readyAt(Kind.MEND), now + MEND_COOLDOWN / 2));
                shields++;
            }
            case BLESS -> {
                ManaWrap.wrap(t, Element.LIFE, blessAmount(power, SpellModifiers.of(t).damage(Element.LIFE)), BLESS_SECONDS);
                Vec3 hand = t.getEyePosition().add(0, -.6, 0);
                level.sendParticles(new DustParticleOptions(ElfPrincessCompanion.MINT, 1.3f), hand.x, hand.y, hand.z, 18, .25, .2, .25, 0);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, hand.x, hand.y, hand.z, 8, .3, .2, .3, 0);
                level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, .9f, 1.2f);
                if (p != null) CompanionLines.say(c, p, "bless", 50);
                ready.put(Kind.BLESS, now + BLESS_COOLDOWN);
                blesses++;
            }
            case SNARE -> {
                Spell spell = snare(level.getServer(), p != null && Companions.bond(p, c.def()).pvp());
                if (spell != null) {
                    Casting.castAs(c, spell, SNARE_MANA, 0, t, t.position(), true);
                    level.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 1, .7f);
                    if (p != null) CompanionLines.say(c, p, "thorns", 50);
                    snares++;
                }
                ready.put(Kind.SNARE, now + SNARE_COOLDOWN);
            }
        }
    }

    /**
     * 缠到主人手上的量：每一下只按她的法强算（维护者定的）。Logos 的魔力缠绕挥中时会再乘挥武器的人（主人）的生命法强
     * （{@code Effects.followUp}），所以这里先除掉主人此刻的那一份 {@code ownerLife}，挥中时乘回来正好是 {@code BLESS_DAMAGE × 她的法强}。
     */
    static double blessAmount(double power, double ownerLife) {
        return BLESS_DAMAGE * power / Math.max(.01, ownerLife);
    }

    /**
     * 治疗、护盾的粒子落在哪：生物在身上；玩家在脚边。
     */
    static Vec3 body(LivingEntity t) {
        return t.position().add(0, t instanceof Player ? .35 : t.getBbHeight() * .6, 0);
    }

    /**
     * 在 {@code t} 身上冒 {@code n} 个粒子。玩家的绕着脚边一圈、离开一格多（第一人称时贴着镜头的粒子会大得糊满屏幕，低头看也一样）。
     */
    static void around(ServerLevel level, net.minecraft.core.particles.ParticleOptions type, LivingEntity t, int n) {
        if (!(t instanceof Player)) {
            Vec3 at = body(t);
            level.sendParticles(type, at.x, at.y, at.z, n, .35, .35, .35, .02);
            return;
        }
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n + t.getRandom().nextDouble() * .3, r = 1.3 + t.getRandom().nextDouble() * .4;
            level.sendParticles(type, t.getX() + Math.cos(a) * r, t.getY() + .15 + t.getRandom().nextDouble() * .3, t.getZ() + Math.sin(a) * r, 1, 0, .05, 0, .01);
        }
    }

    /**
     * 从她手上流向 {@code t} 的一道淡绿光点。给玩家的不画：她多半就站在主人身边，这一道在第一人称里正好横在镜头前，
     * 糊成一大片（主人看自己的血条和脚边那一圈就知道了）。
     */
    private void stream(ServerLevel level, LivingEntity t, float size) {
        if (t instanceof Player) {
            Vec3 hand = c.getEyePosition().add(Vec3.directionFromRotation(0, c.yBodyRot - 30).scale(.4)).add(0, -.3, 0);
            level.sendParticles(new DustParticleOptions(ElfPrincessCompanion.MINT, .8f), hand.x, hand.y, hand.z, 6, .08, .08, .08, 0);
            return;
        }
        Vec3 from = c.getEyePosition().add(Vec3.directionFromRotation(0, c.yBodyRot - 30).scale(.4)).add(0, -.3, 0);
        Vec3 to = body(t);
        int n = (int) Math.max(4, from.distanceTo(to) * 3);
        for (int i = 0; i <= n; i++) {
            Vec3 p = from.lerp(to, i / (double) n);
            level.sendParticles(new DustParticleOptions(ElfPrincessCompanion.MINT, size), p.x, p.y, p.z, 1, .03, .03, .03, 0);
        }
    }

    /**
     * 常青之息：她身边 {@link #AURA_RANGE} 格内受伤的同伴每人回 {@link #AURA_HEAL}（乘法强）。不用施法动作、不打断别的，
     * 只在脚边冒两点绿光（给玩家的同样落在脚边一圈）。撤退时不放。
     */
    private void breathe(ServerLevel level, ServerPlayer p) {
        if (c.withdrawing()) return;
        float heal = (float) (AURA_HEAL * c.spellPower());
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(AURA_RANGE),
                e -> ally(e, p) && e.getHealth() < e.getMaxHealth() && e.distanceTo(c) <= AURA_RANGE)) {
            e.heal(heal);
            around(level, ParticleTypes.HAPPY_VILLAGER, e, 2);
            breaths++;
        }
    }

    // ---------------------------------------------------------------- 照顾谁

    /**
     * 她照顾的：主人、主人的宠物（包括车万女仆这类有主人的生物）、主人的使魔、主人别的同伴、森林精灵（没在和主人、她打的）、她自己。
     */
    boolean ally(LivingEntity e, ServerPlayer owner) {
        if (!e.isAlive() || e.isSpectator()) return false;
        if (e == owner || e == c) return true;
        if (e instanceof OwnableEntity o && o.getOwner() != null && o.getOwner().getUUID().equals(owner.getUUID())) return true;
        if (owner.getUUID().equals(LogosMagicBridge.masterOf(e))) return true;
        if (e instanceof CompanionEntity other) return owner.getUUID().equals(other.ownerId());
        if (e instanceof ForestElf elf) return elf.getTarget() != owner && elf.getTarget() != c;
        return false;
    }

    /** 身边血最少、低于 {@code below} 成的那位同伴（她自己只在别人都不急时才排上）。 */
    @Nullable LivingEntity wounded(ServerLevel level, ServerPlayer owner, double below) {
        LivingEntity best = null;
        double worst = below;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, c.getBoundingBox().inflate(MEND_RANGE), e -> ally(e, owner))) {
            double f = e.getHealth() / Math.max(1, e.getMaxHealth()) + (e == c ? .15 : 0);
            if (f < worst && c.hasLineOfSight(e)) {
                worst = f;
                best = e;
            }
        }
        return best;
    }

    /** 快撑不住、又被盯上（有敌人正冲着它）的同伴，还没有护盾的。 */
    @Nullable LivingEntity endangered(ServerLevel level, ServerPlayer owner) {
        LivingEntity w = wounded(level, owner, SHIELD_BELOW);
        if (w == null || Effects.shielded(w)) return null;
        boolean hunted = !level.getEntitiesOfClass(Mob.class, w.getBoundingBox().inflate(12), m -> m.isAlive() && m.getTarget() == w).isEmpty()
                || w == owner && OwnerWatch.of(owner).fighting(level.getGameTime());
        return hunted ? w : null;
    }

    /** 主人手上已经缠着东西了（她的、白梅的狐火、Logos 的魔力缠绕）。 */
    static boolean wrapped(ServerPlayer owner) {
        return ManaWrap.of(owner) != null || FoxfireWraps.flame(owner) != null || LogosMagicBridge.wrapped(owner);
    }

    // ---------------------------------------------------------------- 万分之一

    /**
     * 第四印：主人的血掉到 {@link #CHANCE_BELOW} 以下、在她身边 16 格内，今天还没用过——她一下子把生命之力全送过去：
     * 回主人生命上限的一半（乘法强，不超过上限），再给 {@link #CHANCE_REGEN} 刻生命恢复 II。不用施法动作，来不及。
     */
    private void chance(ServerLevel level, long now, ServerPlayer p) {
        if (!unlocked(CHANCE_SEAL) || c.withdrawing() || p.getHealth() >= p.getMaxHealth() * CHANCE_BELOW || p.distanceTo(c) > 16) return;
        var bond = Companions.bond(p, c.def());
        long day = level.getOverworldClockTime() / 24000;
        if (bond.guardDay() == day) return;
        Companions.setBond(p, c.def(), bond.withGuard(day));
        p.heal(Math.min(p.getMaxHealth(), p.getMaxHealth() * CHANCE_HEAL * Math.max(1, c.spellPower())));
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, CHANCE_REGEN, 1), c);
        stream(level, p, 1.6f);
        around(level, ParticleTypes.CHERRY_LEAVES, p, 24);
        around(level, ParticleTypes.HAPPY_VILLAGER, p, 12);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1.2f, 1.1f);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, .5f, 1.6f);
        CompanionLines.say(c, p, "chance", 70);
        c.mood.act("act_pray");
        chances++;
    }

    // ---------------------------------------------------------------- 荆棘缠绕的载页

    private static MinecraftServer server;
    private static final Map<Boolean, Optional<Spell>> SNARE = new HashMap<>();

    /**
     * 荆棘缠绕的载页（{@code data/logos_dimension/companion/spells/thorn_snare.logos}，可以直接改）。「攻击玩家」开着时加上
     * {@code :players true}，荆棘也缠和主人起冲突的玩家；关着时只缠生物。
     */
    static @Nullable Spell snare(MinecraftServer s, boolean players) {
        if (server != s) {
            server = s;
            SNARE.clear();
        }
        return SNARE.computeIfAbsent(players, pvp -> {
            try (var reader = s.getResourceManager().getResourceOrThrow(LogosDimension.id("companion/spells/thorn_snare.logos")).openAsReader()) {
                String source = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                if (pvp) source = source.replace(":trigger cast", ":trigger cast :players true");
                return Optional.of(Casting.compile(source));
            } catch (Exception error) {
                com.mojang.logging.LogUtils.getLogger().warn("精灵公主的荆棘缠绕载页不可用", error);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** 是强敌（荆棘、生命缠绕只对它们用）：生命上限到 {@link CompanionEntity#STRONG}，或者一下子围上来三个以上。 */
    static boolean strong(LivingEntity foe, int threats) {
        return foe.getMaxHealth() >= CompanionEntity.STRONG || threats >= 3 || foe instanceof Enemy && foe.getMaxHealth() >= 30 && threats >= 2;
    }

    String describe(long now) {
        return casting == null ? "-" : casting.name().toLowerCase() + " " + (now - castStart);
    }
}
