package dev.logos.dimension.foxfire;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import dev.logos.dimension.LogosDimension;
import dev.logos.lang.Compiler;
import dev.logos.lang.Element;
import dev.logos.lang.LogosError;
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
 * <p>这些都靠注入 Logos（{@code mixin/Foxfire*Mixin}）。Logos 改了被注入的方法时注入不上，狐火退回普通的奥秘伤害，不会崩。
 */
public final class Foxfire {
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("foxfire"));
    /** 同族：基本不怕狐火，被腐化的反而更怕（数据标签，见 {@link FoxfireRules}）。 */
    public static final TagKey<EntityType<?>> KIN = TagKey.create(Registries.ENTITY_TYPE, LogosDimension.id("foxfire_kin"));
    /** 术式里狐火的词。 */
    public static final String WORD = "foxfire";

    /** 正在结算的这一下是狐火：什么颜色，打中后要不要附带效果（缠身每秒烧的那一下不再附带）。 */
    public record Context(FoxfireFlame flame, boolean rules) {}

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    /** Logos 正在执行的符文、正在触发的事件属于一条狐火术式时，它的颜色（换掉符文自己放的奥秘特效用）。 */
    private static final ThreadLocal<FoxfireFlame> SPELL = new ThreadLocal<>();
    /** {@link #deal} 留给紧接着的 {@code Effects.damage(attacker, …)} 的狐火。 */
    private static final ThreadLocal<Context> NEXT = new ThreadLocal<>();
    /** 大于 0 时正在编译玩家自己写的术式。 */
    private static final ThreadLocal<int[]> AUTHORING = ThreadLocal.withInitial(() -> new int[1]);
    /** 编译时把 foxfire 当元素用过的术式，按源码记（Logos 也按源码缓存编译结果）。 */
    private static final Set<String> SOURCES = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 512;
        }
    }));

    private Foxfire() {}

    public static void register(IEventBus bus) {
        FoxfireAttachments.TYPES.register(bus);
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(FoxfireCue.TYPE, FoxfireCue.CODEC));
    }

    // ---------------------------------------------------------------- 结算

    /** 正在结算的狐火，不是狐火时为 null。 */
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

    static boolean deal(LivingEntity attacker, LivingEntity target, double amount, Context context) {
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

    /** 这条术式是狐火术式时，它的狐火是什么颜色（看施法者）；不是时为 null。 */
    public static @Nullable FoxfireFlame flameOf(SpellInstance inst) {
        return is(inst.spell) ? flameOf(inst.caster) : null;
    }

    public static boolean is(@Nullable Spell spell) {
        return spell != null && SOURCES.contains(spell.source());
    }

    /** Logos 正在执行的是一条狐火术式时，它的颜色；不是时为 null。 */
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

    /** Logos 的这一下确实打进去了：附带狐火的效果（读当前的狐火）。 */
    public static void landed(DamageSource source, LivingEntity target) {
        Context context = CURRENT.get();
        if (context != null && context.rules()) FoxfireRules.landed(source.getEntity(), target, context.flame());
    }

    // ---------------------------------------------------------------- 术式里藏着的词

    /** 编译器碰到一个不认识的元素词时问：它能不能当狐火。 */
    public static boolean word(String word) {
        return WORD.equals(word) && AUTHORING.get()[0] == 0;
    }

    /** 编译完一条用过 foxfire 的术式。 */
    public static void compiled(@Nullable Spell spell) {
        if (spell != null) SOURCES.add(spell.source());
    }

    /** 编译玩家自己写的术式：这期间 foxfire 是不认识的词。 */
    public static <T> T authoring(Supplier<T> compile) {
        int[] depth = AUTHORING.get();
        depth[0]++;
        try {
            return compile.get();
        } finally {
            depth[0]--;
        }
    }

    /**
     * 同上，用于走 Logos 编译缓存的地方：缓存按源码记，里面可能是调试时编过的狐火术式。
     * 那就按玩家写的重编一次，报出和写错一个词一样的错。
     */
    public static Spell authoring(String src, Supplier<Spell> compile) {
        Spell spell = authoring(compile);
        if (!is(spell)) return spell;
        authoring(() -> Compiler.compile(src));
        throw new LogosError("未知的词 " + WORD, 0, 0);
    }
}
