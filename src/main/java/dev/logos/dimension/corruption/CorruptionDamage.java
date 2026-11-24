package dev.logos.dimension.corruption;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.mob.CorruptionMobs;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import java.util.function.Supplier;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.jspecify.annotations.Nullable;

/**
 * 腐败伤害：生命伤害的子类型（同狐火之于奥秘，见 docs/CORRUPTION.md「腐败伤害」）。
 *
 * <ul>
 * <li>腐败一方（实体类型标签 {@code logos_dimension:corrupted}：腐化女王、污秽衍生物）打出的 Logos 生命伤害都是腐败伤害，
 * 伤害来源换成 {@link Corruption#DAMAGE}。结算同生命：生命强化、生命抗性、法术抗性照常算，护甲不减。
 * <li>打中腐败一方时改为治疗，同原版瞬间伤害对亡灵。
 * <li>玩家写不出腐败伤害（生命本来就不能写）。
 * </ul>
 * 换来源与命中特效挂在狐火已有的 {@code Effects} 注入点上（{@code FoxfireEffectsMixin}）；腐败法术自己的特效由
 * {@code CorruptionSpellMixin} 标出、{@code CorruptionVfxMixin} 换掉。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class CorruptionDamage {
    /** 正在结算的这一下是不是腐败伤害。 */
    private static final ThreadLocal<Boolean> CURRENT = new ThreadLocal<>();
    /** Logos 正在执行的符文、正在飞的法术形体是不是腐败一方放的（换特效用）。 */
    private static final ThreadLocal<Boolean> SPELL = new ThreadLocal<>();

    private CorruptionDamage() {}

    /** 这个生物是腐败一方：它的生命伤害是腐败伤害，挨腐败伤害反而回血。 */
    public static boolean aligned(@Nullable Entity entity) {
        return entity != null && CorruptionMobs.isCorrupted(entity);
    }

    public static boolean current() {
        return Boolean.TRUE.equals(CURRENT.get());
    }

    /** 生命特效要不要换成腐败的：正在结算腐败伤害，或者正在执行腐败一方的法术。 */
    public static boolean visual() {
        return current() || Boolean.TRUE.equals(SPELL.get());
    }

    /** 在 {@code corrupt} 里跑 {@code body}；false 盖住外面的，免得受伤时触发的别的生命伤害也算成腐败。 */
    public static <T> T within(boolean corrupt, Supplier<T> body) {
        Boolean outer = CURRENT.get();
        CURRENT.set(corrupt);
        try {
            return body.get();
        } finally {
            if (outer == null) CURRENT.remove();
            else CURRENT.set(outer);
        }
    }

    /** 标出一段属于腐败一方法术的执行（只为换特效，不改伤害）。 */
    public static void inSpell(boolean corrupt, Runnable body) {
        Boolean outer = SPELL.get();
        SPELL.set(corrupt);
        try {
            body.run();
        } finally {
            if (outer == null) SPELL.remove();
            else SPELL.set(outer);
        }
    }

    /** 腐败一方的一下打击：走 Logos 的生命伤害（强化、抗性、命中特效），来源换成腐败。返回目标是否因此死亡。 */
    public static boolean hit(LivingEntity attacker, LivingEntity target, double amount) {
        return within(true, () -> Effects.damage(attacker, target, amount, Element.LIFE));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void heal(LivingIncomingDamageEvent e) {
        if (!e.getSource().is(Corruption.DAMAGE) || !aligned(e.getEntity()) || e.getAmount() <= 0) return;
        LivingEntity living = e.getEntity();
        e.setCanceled(true);
        living.heal(e.getAmount());
        if (living.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, living.getX(), living.getY() + living.getBbHeight() * .6, living.getZ(), 6, .3, .4, .3, .01);
        }
    }
}
