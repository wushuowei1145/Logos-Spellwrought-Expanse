package dev.logos.dimension.foxfire;

import dev.logos.dimension.foxfire.FoxfireAttachments.Wrap;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * 狐火的魔力缠绕。两种来路：狐火术式里的 {@code imbue}（Logos 的术式缠绕，打中时跑术式里的事件），
 * 和附属直接给的普通缠绕（{@link #wrap}，每下附带固定的狐火伤害；以后少女白梅给玩家缠的就是这种，颜色跟着她走）。
 * 两种都记在 {@link FoxfireAttachments#WRAP} 上，客户端据此把手上的缠绕画成那种颜色的狐火。
 */
public final class FoxfireWraps {
    private FoxfireWraps() {}

    /** 一条狐火术式的缠绕刚缠上手（Logos 的 {@code WrapObj.wear} 之后）。 */
    public static void shown(LivingEntity holder, FoxfireFlame flame) {
        ManaWrap wrap = ManaWrap.of(holder);
        if (wrap != null) holder.setData(FoxfireAttachments.WRAP, new Wrap(flame, wrap.until(), false));
    }

    /** 术式缠绕散了（Logos 的 {@code WrapObj.onEnded} 之后）；附属给的普通缠绕不动。 */
    public static void spellEnded(LivingEntity holder) {
        if (holder.hasData(FoxfireAttachments.WRAP) && !holder.getData(FoxfireAttachments.WRAP).plain()) holder.removeData(FoxfireAttachments.WRAP);
    }

    /**
     * 给 {@code holder} 的手缠上狐火：Logos 的普通缠绕（奥秘，每下附带 {@code amount}，{@code seconds} 秒），
     * 附带的那一下算成 {@code flame} 的狐火。没有点数或时间就是解开。
     */
    public static void wrap(LivingEntity holder, FoxfireFlame flame, double amount, double seconds) {
        ManaWrap.wrap(holder, Element.ARCANE, amount, seconds);
        ManaWrap wrap = ManaWrap.of(holder);
        if (wrap == null) holder.removeData(FoxfireAttachments.WRAP);
        else holder.setData(FoxfireAttachments.WRAP, new Wrap(flame, wrap.until(), true));
    }

    /** {@code attacker} 手上是附属给的狐火普通缠绕时，它附带的那一下是什么颜色的狐火。 */
    public static @Nullable FoxfireFlame plain(LivingEntity attacker) {
        ManaWrap wrap = ManaWrap.of(attacker);
        if (wrap == null || wrap.amount() <= 0 || !attacker.hasData(FoxfireAttachments.WRAP)) return null;
        Wrap fox = attacker.getData(FoxfireAttachments.WRAP);
        return fox.plain() && fox.until() == wrap.until() ? fox.flame() : null;
    }

    /** 手上的缠绕是不是狐火（两种都算），是的话什么颜色。客户端也能用（两样都同步）。 */
    public static @Nullable FoxfireFlame flame(LivingEntity holder) {
        if (!holder.hasData(FoxfireAttachments.WRAP)) return null;
        ManaWrap wrap = ManaWrap.of(holder);
        Wrap fox = holder.getData(FoxfireAttachments.WRAP);
        return wrap != null && fox.until() == wrap.until() ? fox.flame() : null;
    }
}
