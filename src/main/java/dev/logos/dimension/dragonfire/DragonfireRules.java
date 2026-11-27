package dev.logos.dimension.dragonfire;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.CorruptionContact;
import dev.logos.dimension.foxfire.FoxfireAttachments;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.jspecify.annotations.Nullable;

/**
 * 龙炎打中之后的事（docs/DRAGONFIRE.md 第 3 节）：
 * <ul>
 *   <li><b>伤及灵魂</b>：每打进去一点龙炎，烧掉目标一点魔力（玩家的魔力池、魔力生命体、精灵、神狐）。</li>
 *   <li><b>龙炎缠身</b>：很难灭掉的火。不是原版的火：水、雨、细雪、抗火都没用，也不点燃方块。
 *       再被打中不是续上，而是往上叠时间（最多叠到 {@link #MAX_BURN_TICKS}）；燃着龙炎时点不上狐火。
 *       每秒烧一下龙炎，再烧掉一些魔力。</li>
 *   <li><b>龙族</b>（实体标签 {@code logos_dimension:dragonfire_kin}）：受到的龙炎只剩四分之一，不会缠身，也不掉魔力。</li>
 * </ul>
 * 缠身借用狐火缠身的那一份（{@link FoxfireAttachments#BURN}，颜色 {@link FoxfireFlame#DRAGON}），每秒的那一下在 {@link FoxfireRules} 里烧。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class DragonfireRules {
    /** 龙族受到的龙炎只剩这么多。 */
    public static final float KIN_TAKEN = .25f;
    /** 每打中一下往上叠多少刻缠身，最多叠到多少刻；每秒烧多少。 */
    public static final int BURN_TICKS = 100, MAX_BURN_TICKS = 300;
    public static final double BURN_DAMAGE = 1.5;
    /** 每点打进去的龙炎烧掉多少魔力；缠身每秒烧掉多少魔力。 */
    public static final double MANA_PER_DAMAGE = 1, BURN_MANA = 2;

    private DragonfireRules() {}

    /** 龙族：不怕龙炎。 */
    public static boolean kin(LivingEntity e) {
        return e.is(Dragonfire.KIN);
    }

    /** 龙炎打进去了 {@code amount} 点（{@code attacker} 是记在谁名下）。 */
    public static void landed(@Nullable Entity attacker, LivingEntity target, float amount, boolean burn) {
        if (target.level().isClientSide() || kin(target) || !target.isAlive()) return;
        CorruptionContact.drainMana(target, amount * MANA_PER_DAMAGE);
        if (burn) burn(target, attacker, BURN_TICKS);
    }

    /** 让 {@code target} 龙炎缠身：还没烧完的往上叠 {@code ticks} 刻，最多烧到 {@link #MAX_BURN_TICKS} 刻以后。 */
    public static void burn(LivingEntity target, @Nullable Entity burner, int ticks) {
        if (kin(target)) return;
        long now = target.level().getGameTime();
        long from = now;
        if (target.hasData(FoxfireAttachments.BURN)) {
            FoxfireAttachments.Burn old = target.getData(FoxfireAttachments.BURN);
            if (old.flame().dragon() && old.until() > now) from = old.until();
        }
        FoxfireRules.burnUntil(target, FoxfireFlame.DRAGON, burner, Math.min(from + ticks, now + MAX_BURN_TICKS));
    }

    /** 龙炎缠身烧了一秒（{@link FoxfireRules} 每秒的那一下之后）：再烧掉一些魔力。 */
    public static void scorched(LivingEntity target) {
        if (!kin(target)) CorruptionContact.drainMana(target, BURN_MANA);
    }

    @SubscribeEvent
    static void incoming(LivingIncomingDamageEvent event) {
        if (event.getSource().is(Dragonfire.DAMAGE) && kin(event.getEntity())) event.setAmount(event.getAmount() * KIN_TAKEN);
    }
}
