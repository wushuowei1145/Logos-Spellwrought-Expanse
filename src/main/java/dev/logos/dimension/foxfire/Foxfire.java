package dev.logos.dimension.foxfire;

import java.util.function.Supplier;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.dragonfire.DragonfireRules;
import dev.logos.dimension.magic.HiddenWord;
import dev.logos.lang.Element;
import dev.logos.lang.Spell;
import dev.logos.runtime.Effects;
import dev.logos.runtime.SpellInstance;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 狐火（docs/FOXFIRE.md）：神狐一族的伤害。本质就是 Logos 的奥秘伤害，结算照样走 Logos（{@link Effects}），
 * 奥秘强化、奥秘抗性、法术抗性都照常算；不同的只有：
 * <ul>
 *   <li>伤害类型是附属自己的 {@link #DAMAGE}（原版标签和间接魔法一样），死亡消息写「被狐火吞没」；</li>
 *   <li>不放 Logos 的奥秘命中特效，换成狐火颜色的一团火（{@link FoxfireCue#HIT}）；</li>
 *   <li>打中之后狐火缠身、惑，同族基本不怕（{@link FoxfireRules}）。</li>
 * </ul>
 * 颜色（{@link FoxfireFlame}）跟着这一下走，所以神狐的幽蓝和白梅的淡粉是同一种伤害。
 *
 * <p>术式里把元素写成 {@code foxfire}（写 {@code arcane} 的地方）就是狐火术式：整条术式的奥秘伤害、施法时脚下的光、
 * 魔力缠绕、游脉都换成狐火。这个词在编纂台里完全看不到（元素列表、补全、拼写提示里都没有），玩家在编纂台、书与笔、
 * 术式发射器、书灵里写它，都和写错一个词一样报错；只有调试（{@code /logos cast}、{@code /alchemy foxfire page}）
 * 和附属自己的生物（神狐的狐火缠绕）用得了。编辑器打开一张狐火术式页时它显示成奥秘，存回去就是普通的奥秘术式。
 *
 * <p>白梅的第三印教主人狐火之言（{@link #teach}）：学会的人自己写术式时 {@code foxfire} 和别的元素一样认得（编纂台、书与笔、
 * 发射器、书灵），编纂台的卡片库里也有它。狐火是奥秘的一支，开着符文解锁时还要学会奥秘才写得上。藏词的做法见 {@link HiddenWord}。
 *
 * <p>龙炎（docs/DRAGONFIRE.md）借用这一整套结算和画法：它是第三种颜色 {@link FoxfireFlame#DRAGON}，伤害类型、打中之后的事、
 * 同族、藏着的词归 {@link Dragonfire}。这里凡是按颜色走的（{@link Context}、缠身、缠绕、游脉、命中），颜色是龙炎时就是龙炎。
 *
 * <p>这些都靠注入 Logos（{@code mixin/Foxfire*Mixin}、{@code HiddenWord*Mixin}）。Logos 改了被注入的方法时注入不上，
 * 狐火退回普通的奥秘伤害，不会崩。
 */
public final class Foxfire {
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("foxfire"));
    /** 同族：基本不怕狐火，被腐化的反而更怕（数据标签，见 {@link FoxfireRules}）。 */
    public static final TagKey<EntityType<?>> KIN = TagKey.create(Registries.ENTITY_TYPE, LogosDimension.id("foxfire_kin"));
    /** 术式里狐火的词。 */
    public static final String WORD = "foxfire";
    /** 狐火之言：藏着的词 {@code foxfire}。 */
    public static final HiddenWord HIDDEN = HiddenWord.register(WORD, FoxfireAttachments.KNOWN);
    /** 书灵的「搭档学会了什么」里，狐火之言的键（同 Logos 的 {@code element:…}）。 */
    public static final String UNLOCK = HIDDEN.unlock;

    /**
     * 正在结算的这一下是狐火（或龙炎）：什么颜色，打中后要不要附带效果（缠身每秒烧的那一下不再附带）。
     * {@code burn} 为假时附带效果里不点缠身（流火平时的刀：缠身要靠狐火印攒满爆开，见 docs/RYUKA.md）。
     */
    public record Context(FoxfireFlame flame, boolean rules, boolean burn) {
        public Context(FoxfireFlame flame, boolean rules) {
            this(flame, rules, rules);
        }
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    /** Logos 正在执行的符文、正在触发的事件属于一条狐火（龙炎）术式时，它的颜色（换掉符文自己放的奥秘特效用）。 */
    private static final ThreadLocal<FoxfireFlame> SPELL = new ThreadLocal<>();
    /** {@link #deal} 留给紧接着的 {@code Effects.damage(attacker, …)} 的狐火。 */
    private static final ThreadLocal<Context> NEXT = new ThreadLocal<>();

    private Foxfire() {}

    public static void register(IEventBus bus) {
        FoxfireAttachments.TYPES.register(bus);
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(FoxfireCue.TYPE, FoxfireCue.CODEC));
    }

    // ---------------------------------------------------------------- 结算

    /** 正在结算的狐火（或龙炎），都不是时为 null。 */
    public static @Nullable Context current() {
        return CURRENT.get();
    }

    /** 在 {@code context} 里跑 {@code body}；null 表示这一段不是狐火（盖住外面的，免得受伤时触发的别的伤害也算成狐火）。 */
    public static <T> T within(@Nullable Context context, Supplier<T> body) {
        Context outer = CURRENT.get();
        if (context == null) CURRENT.remove();
        else CURRENT.set(context);
        try {
            return body.get();
        } finally {
            if (outer == null) CURRENT.remove();
            else CURRENT.set(outer);
        }
    }

    /**
     * 生物（不是术式）打出的狐火，同 Logos 的 {@code Effects.damage(attacker, target, amount, ARCANE)}，只是换成狐火：
     * 返回目标是否因此死亡。
     */
    public static boolean deal(LivingEntity attacker, LivingEntity target, double amount, FoxfireFlame flame) {
        return deal(attacker, target, amount, new Context(flame, true));
    }

    /** 同上，附带效果按 {@code context}（例如不点缠身）。 */
    public static boolean deal(LivingEntity attacker, LivingEntity target, double amount, Context context) {
        NEXT.set(context);
        try {
            return Effects.damage(attacker, target, amount, Element.ARCANE);
        } finally {
            NEXT.remove();
        }
    }

    /** {@code Effects.damage(attacker, …)} 一开头取走 {@link #deal} 留下的狐火；别处直接调用它时取到 null。 */
    public static @Nullable Context claim() {
        Context context = NEXT.get();
        NEXT.remove();
        return context;
    }

    /** 狐火的伤害来源（记在 {@code attacker} 名下）。 */
    public static DamageSource source(LivingEntity target, @Nullable Entity attacker) {
        return target.damageSources().source(DAMAGE, attacker, attacker);
    }

    /** 这个生物放出的狐火是什么颜色：白梅的淡粉，其余都是神狐一族的幽蓝。 */
    public static FoxfireFlame flameOf(@Nullable Entity caster) {
        return caster instanceof FoxfireBearer bearer ? bearer.foxfireFlame() : FoxfireFlame.KITSUNE;
    }

    /** 这条术式是狐火（龙炎）术式时，它的颜色；都不是时为 null。两个词都写了的按龙炎。 */
    public static @Nullable FoxfireFlame flameOf(@Nullable Spell spell, @Nullable Entity caster) {
        if (Dragonfire.is(spell)) return FoxfireFlame.DRAGON;
        return is(spell) ? flameOf(caster) : null;
    }

    /** 同上，按正在跑的术式和它的施法者。 */
    public static @Nullable FoxfireFlame flameOf(SpellInstance inst) {
        return flameOf(inst.spell, inst.caster);
    }

    public static boolean is(@Nullable Spell spell) {
        return HIDDEN.is(spell);
    }

    /** Logos 正在执行的是一条狐火（龙炎）术式时，它的颜色；不是时为 null。 */
    public static @Nullable FoxfireFlame spellFlame() {
        return SPELL.get();
    }

    /** Logos 执行一段符文（或触发一个事件）期间记下它属于哪种狐火术式（null 表示不是狐火术式，盖住外面的）。 */
    public static void inSpell(@Nullable FoxfireFlame flame, Runnable body) {
        FoxfireFlame outer = SPELL.get();
        if (flame == null) SPELL.remove();
        else SPELL.set(flame);
        try {
            body.run();
        } finally {
            if (outer == null) SPELL.remove();
            else SPELL.set(outer);
        }
    }

    /** Logos 的这一下确实打进去了（{@code amount} 点）：附带狐火或龙炎的效果（读当前的狐火）。 */
    public static void landed(DamageSource source, LivingEntity target, float amount) {
        Context context = CURRENT.get();
        if (context == null || !context.rules()) return;
        if (context.flame().dragon()) DragonfireRules.landed(source.getEntity(), target, amount, context.burn());
        else FoxfireRules.landed(source.getEntity(), target, context.flame(), context.burn());
    }

    // ---------------------------------------------------------------- 狐火之言

    /** {@code p} 学会了狐火之言没有（白梅第三印）。 */
    public static boolean knows(@Nullable Entity p) {
        return HIDDEN.knows(p);
    }

    /** 教 {@code p} 狐火之言；已经会了返回假。 */
    public static boolean teach(net.minecraft.server.level.ServerPlayer p) {
        return HIDDEN.teach(p);
    }

    /** 测试和命令用：忘掉狐火之言。 */
    public static void forget(net.minecraft.server.level.ServerPlayer p) {
        HIDDEN.forget(p);
    }

    /** 测试用：编译玩家自己写的术式，没学会狐火之言（别的藏词照常认得）。 */
    public static <T> T authoring(Supplier<T> compile) {
        return HiddenWord.authoring(w -> w != HIDDEN, compile);
    }

    /** 同上，走编译缓存的地方（见 {@link HiddenWord#authoring(java.util.function.Predicate, String, Supplier)}）。 */
    public static Spell authoring(String src, Supplier<Spell> compile) {
        return HiddenWord.authoring(w -> w != HIDDEN, src, compile);
    }
}
