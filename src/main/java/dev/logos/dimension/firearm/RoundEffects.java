package dev.logos.dimension.firearm;

import dev.logos.dimension.corruption.mob.CorruptionMobs;
import dev.logos.dimension.mixin.FireworkRocketAccess;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 一发弹的外观（{@link Firearms#FINISH}）和改装弹的效果（docs/FIREARMS.md 第 10 节）。
 * 外观：刻了第几档术式是 {@code spell_1}–{@code spell_7}，改装弹是下面几种；物品模型按它换贴图，JEI 按它分条目。
 * 改装弹没有术式，是术式弹的下位替代，用物理手段做出某种效果：
 * <ul>
 *   <li>{@code sulfur} 燃烧弹（硫粉）：打中的生物着火 {@value #BURN_SECONDS} 秒。</li>
 *   <li>{@code mercury} 水银弹（水银瓶）：打亡灵和腐败生物时物理伤害 ×{@value #MERCURY_BANE}，并附加虚弱。</li>
 *   <li>{@code firework} 烟火弹（烟火之星）：落点炸开这颗烟火之星（形状、颜色跟着星），伤到周围。</li>
 * </ul>
 */
public final class RoundEffects {
    private RoundEffects() {}

    public static final String SULFUR = "sulfur", MERCURY = "mercury", FIREWORK = "firework";
    /** 改装的几种，按配方册里的顺序。 */
    public static final List<String> MODS = List.of(SULFUR, MERCURY, FIREWORK);
    public static final int BURN_SECONDS = 5;
    public static final double MERCURY_BANE = 1.5;
    /** 水银弹的虚弱：等级 II，5 秒。 */
    public static final int WEAKNESS_TICKS = 100, WEAKNESS_LEVEL = 1;

    /** 刻了第 {@code tier} 档术式的外观。 */
    public static String spell(int tier) {
        return "spell_" + tier;
    }

    public static @Nullable String finish(ItemStack round) {
        return round.get(Firearms.FINISH.get());
    }

    /** 还是一发普通弹：没刻术式、没改装过，铭刻和改装都只收这种。 */
    public static boolean plain(ItemStack round) {
        return !round.has(Firearms.FINISH.get()) && !round.has(Firearms.ETCHED.get());
    }

    /** {@code round} 用 {@code additive} 改装成 {@code finish} 的一发：烟火之星把星的样子带进弹里（落点照它炸开、贴图照它染色）。 */
    public static ItemStack modified(ItemStack round, ItemStack additive, String finish) {
        ItemStack out = round.copyWithCount(1);
        out.set(Firearms.FINISH.get(), finish);
        if (finish.equals(FIREWORK)) {
            var star = additive.get(DataComponents.FIREWORK_EXPLOSION);
            out.set(DataComponents.FIREWORK_EXPLOSION, star == null ? FireworkExplosion.DEFAULT : star);
        }
        return out;
    }

    /** 水银克制的：亡灵和腐败生物。 */
    static boolean bane(LivingEntity target) {
        return target.is(EntityTypeTags.UNDEAD) || CorruptionMobs.isCorrupted(target);
    }

    /** 这一发对 {@code target} 的物理伤害倍数（乘在弹头金属和铳的威力之后）。 */
    public static double factor(ItemStack round, LivingEntity target) {
        return MERCURY.equals(finish(round)) && bane(target) ? MERCURY_BANE : 1;
    }

    /** 打中 {@code target} 之后（物理伤害已经结算）：燃烧弹点火，水银弹给亡灵和腐败生物上虚弱。 */
    public static void hit(ItemStack round, LivingEntity shooter, LivingEntity target) {
        String finish = finish(round);
        if (SULFUR.equals(finish)) target.igniteForSeconds(BURN_SECONDS);
        else if (MERCURY.equals(finish) && bane(target)) {
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKNESS_TICKS, WEAKNESS_LEVEL), shooter);
        }
    }

    /** 这一发落定在 {@code at}（打中的第一个生物、打在方块上，或者飞到尽头）：烟火弹在那里炸开烟火之星。 */
    public static void landed(ItemStack round, ServerLevel level, LivingEntity shooter, Vec3 at) {
        if (!FIREWORK.equals(finish(round))) return;
        var star = round.get(DataComponents.FIREWORK_EXPLOSION);
        var rocket = new ItemStack(Items.FIREWORK_ROCKET);
        rocket.set(DataComponents.FIREWORKS, new Fireworks(0, List.of(star == null ? FireworkExplosion.DEFAULT : star)));
        // 一枚不飞的烟火：寿命 0，下一刻就在原地炸开（炸开的画面、伤害都是原版烟火的）。
        var firework = new FireworkRocketEntity(level, rocket, shooter, at.x, at.y, at.z, true);
        firework.setDeltaMovement(Vec3.ZERO);
        ((FireworkRocketAccess) firework).logos_dimension$setLifetime(0);
        level.addFreshEntity(firework);
    }
}
