package dev.logos.dimension.dragonfire;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.magic.HiddenWord;
import dev.logos.lang.Spell;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import org.jspecify.annotations.Nullable;

/**
 * 龙炎（docs/DRAGONFIRE.md）：龙族的火，伤及灵魂。奥秘的子类型（同狐火），结算照样走 Logos 的奥秘伤害：
 * 奥秘强化、奥秘抗性、法术抗性照常算。和原版的火不同，它没有火的那些弱点：
 * <ul>
 *   <li>伤害类型 {@link #DAMAGE} 不带 {@code is_fire}：天生不怕火的、抗火药水、火焰保护都挡不住，水浇不灭；</li>
 *   <li>护甲、保护类附魔都不减（{@code bypasses_armor}、{@code bypasses_enchantments}）；</li>
 *   <li>不在 {@code witch_resistant_to} 里：女巫（和神狐）对魔法的减免不算；</li>
 *   <li>打中掉魔力，点上很难灭掉的龙炎缠身（{@link DragonfireRules}）；龙族（{@link #KIN}）只受四分之一。</li>
 * </ul>
 * 结算和画法借用狐火的一整套（颜色 {@link FoxfireFlame#DRAGON}，见 {@link Foxfire}），这里是龙炎自己的入口。
 *
 * <p>术式里把元素写成 {@code dragonfire}（写 {@code arcane} 的地方）就是龙炎术式，同狐火：玩家没学会时写不了，
 * 学会了（{@link #teach}，龙炎之言）就和别的元素一样认得。现在还没有哪里教，留给以后的拉塞塔。
 */
public final class Dragonfire {
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("dragonfire"));
    /** 龙族：受到的龙炎只剩四分之一，不会龙炎缠身，也不掉魔力（数据标签，见 {@link DragonfireRules}）。 */
    public static final TagKey<EntityType<?>> KIN = TagKey.create(Registries.ENTITY_TYPE, LogosDimension.id("dragonfire_kin"));
    /** 术式里龙炎的词。 */
    public static final String WORD = "dragonfire";
    /** 龙炎之言：藏着的词 {@code dragonfire}。 */
    public static final HiddenWord HIDDEN = HiddenWord.register(WORD, DragonfireAttachments.KNOWN);
    /** 书灵的「搭档学会了什么」里，龙炎之言的键。 */
    public static final String UNLOCK = HIDDEN.unlock;

    private Dragonfire() {}

    public static void register(IEventBus bus) {
        DragonfireAttachments.TYPES.register(bus);
    }

    /** 生物（不是术式）打出的龙炎，同 {@code Effects.damage(attacker, target, amount, ARCANE)}：返回目标是否因此死亡。 */
    public static boolean deal(LivingEntity attacker, LivingEntity target, double amount) {
        return deal(attacker, target, amount, true);
    }

    /** 同上；{@code burn} 为假时打中不点龙炎缠身（照样掉魔力）。 */
    public static boolean deal(LivingEntity attacker, LivingEntity target, double amount, boolean burn) {
        return Foxfire.deal(attacker, target, amount, new Foxfire.Context(FoxfireFlame.DRAGON, true, burn));
    }

    /** 给 {@code holder} 的手缠上龙炎：Logos 的普通缠绕（奥秘），每下附带 {@code amount} 点龙炎，{@code seconds} 秒。没有点数或时间就是解开。 */
    public static void wrap(LivingEntity holder, double amount, double seconds) {
        FoxfireWraps.wrap(holder, FoxfireFlame.DRAGON, amount, seconds);
    }

    /** 让 {@code target} 龙炎缠身 {@code ticks} 刻（叠在还没烧完的上面，见 {@link DragonfireRules#burn}）。 */
    public static void burn(LivingEntity target, @Nullable Entity burner, int ticks) {
        DragonfireRules.burn(target, burner, ticks);
    }

    /** {@code e} 正在龙炎缠身。客户端也能用。 */
    public static boolean burning(LivingEntity e) {
        return FoxfireRules.burning(e) == FoxfireFlame.DRAGON;
    }

    /** 这条术式用过 {@code dragonfire}。 */
    public static boolean is(@Nullable Spell spell) {
        return HIDDEN.is(spell);
    }

    // ---------------------------------------------------------------- 龙炎之言（解锁的接口）

    public static boolean knows(@Nullable Entity p) {
        return HIDDEN.knows(p);
    }

    /** 教 {@code p} 龙炎之言：此后自己写的术式里可以写 {@code dragonfire}。已经会了返回假。 */
    public static boolean teach(ServerPlayer p) {
        return HIDDEN.teach(p);
    }

    public static void forget(ServerPlayer p) {
        HIDDEN.forget(p);
    }
}
