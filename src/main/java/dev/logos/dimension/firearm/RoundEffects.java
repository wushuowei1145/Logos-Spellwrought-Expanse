package dev.logos.dimension.firearm;

import dev.logos.dimension.corruption.mob.CorruptionMobs;
import dev.logos.dimension.mixin.FireworkRocketAccess;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import org.jspecify.annotations.Nullable;

/**
 * 一发弹的外观（{@link Firearms#FINISH}）和改装弹的效果（docs/FIREARMS.md 第 10 节）。
 * 外观：刻了第几档术式是 {@code spell_1}–{@code spell_7}，改装弹是下面几种；物品模型按它换贴图，JEI 按它分条目。
 * 改装弹没有术式，是术式弹的下位替代，用物理手段做出某种效果：
 * <ul>
 *   <li>{@code sulfur} 燃烧弹（硫粉）：打中的生物着火 {@value #BURN_SECONDS} 秒。</li>
 *   <li>{@code mercury} 水银弹（水银瓶）：打亡灵和腐败生物时物理伤害 ×{@value #MERCURY_BANE}，并附加虚弱。</li>
 *   <li>{@code firework} 烟火弹（烟火之星）：落点炸开这颗烟火之星（形状、颜色跟着星），伤到周围。</li>
 *   <li>{@code glowing} 荧光弹（荧石粉）：打中的生物发光，和光灵箭一样 10 秒。</li>
 *   <li>{@code ender} 末影弹（末影珍珠，一颗一发）：落在生物或方块上时，开枪的人传送过去，像末影珍珠那样受 {@value #ENDER_DAMAGE} 点伤害。</li>
 *   <li>{@code blast} 震爆弹（TNT，一块四发）：落点炸开，伤到、震开周围，不毁方块。</li>
 *   <li>{@code potion} 药水弹（药粉，一份一发）：打中的生物得到药粉里的药水效果（时长不打折），颜色也跟着药水。</li>
 * </ul>
 */
public final class RoundEffects {
    private RoundEffects() {}

    public static final String SULFUR = "sulfur", MERCURY = "mercury", FIREWORK = "firework",
            GLOWING = "glowing", ENDER = "ender", BLAST = "blast", POTION = "potion";
    /** 改装的几种，按配方册里的顺序。 */
    public static final List<String> MODS = List.of(SULFUR, MERCURY, FIREWORK, GLOWING, ENDER, BLAST, POTION);
    public static final int BURN_SECONDS = 5;
    public static final double MERCURY_BANE = 1.5;
    /** 水银弹的虚弱：等级 II，5 秒。 */
    public static final int WEAKNESS_TICKS = 100, WEAKNESS_LEVEL = 1;
    /** 荧光弹的发光：和光灵箭一样 10 秒。 */
    public static final int GLOW_TICKS = 200;
    /** 末影弹传送过去受的伤害，和末影珍珠落地一样。 */
    public static final float ENDER_DAMAGE = 5;
    /** 震爆弹炸开的威力（恶魂火球 1，苦力怕 3）。 */
    public static final float BLAST_POWER = 1.5f;

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

    /** {@code additive} 能不能做成 {@code finish}：药水弹要药粉里真有药水效果，别的只看配方的添加物。 */
    public static boolean accepts(String finish, ItemStack additive) {
        if (!POTION.equals(finish)) return true;
        var contents = additive.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.hasEffects();
    }

    /**
     * {@code round} 用 {@code additive} 改装成 {@code finish} 的一发：烟火之星把星的样子带进弹里（落点照它炸开、贴图照它染色），
     * 药粉把药水带进弹里（打中时照它上效果、贴图照它染色）。
     */
    public static ItemStack modified(ItemStack round, ItemStack additive, String finish) {
        ItemStack out = round.copyWithCount(1);
        out.set(Firearms.FINISH.get(), finish);
        if (finish.equals(FIREWORK)) {
            var star = additive.get(DataComponents.FIREWORK_EXPLOSION);
            out.set(DataComponents.FIREWORK_EXPLOSION, star == null ? FireworkExplosion.DEFAULT : star);
        } else if (finish.equals(POTION)) {
            var contents = additive.get(DataComponents.POTION_CONTENTS);
            if (contents != null) out.set(DataComponents.POTION_CONTENTS, contents);
        }
        return out;
    }

    /**
     * 配方册、JEI 里拿来示范的添加物：药水弹是每种有效果的药水各一份药粉（配方只写了药粉这件物品），
     * 别的就是配方的添加物本身。
     */
    public static List<ItemStack> samples(Ingredient additive, String finish) {
        var items = additive.items().map(ItemStack::new).toList();
        if (!POTION.equals(finish) || items.isEmpty()) return items;
        List<ItemStack> out = new ArrayList<>();
        BuiltInRegistries.POTION.listElements().forEach(potion -> {
            var contents = new PotionContents(potion);
            if (!contents.hasEffects()) return;
            var powder = items.getFirst().copy();
            powder.set(DataComponents.POTION_CONTENTS, contents);
            out.add(powder);
        });
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

    /** 打中 {@code target} 之后（物理伤害已经结算）：燃烧弹点火，水银弹给亡灵和腐败生物上虚弱，荧光弹让它发光，药水弹上药水效果。 */
    public static void hit(ItemStack round, LivingEntity shooter, LivingEntity target) {
        String finish = finish(round);
        if (SULFUR.equals(finish)) target.igniteForSeconds(BURN_SECONDS);
        else if (MERCURY.equals(finish) && bane(target)) {
            target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKNESS_TICKS, WEAKNESS_LEVEL), shooter);
        } else if (GLOWING.equals(finish)) target.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TICKS, 0), shooter);
        else if (POTION.equals(finish) && target.level() instanceof ServerLevel level) {
            var contents = round.get(DataComponents.POTION_CONTENTS);
            if (contents == null) return;
            // 和药水箭一样上效果，时长不打折（一份药粉只做一发）；治疗、伤害这类瞬间效果当场生效，算开枪的人的。
            // 子弹刚打过，目标还在受伤冷却里：像 Logos 的追加伤害那样清掉冷却，瞬间伤害才叠得上去。
            contents.forEachEffect(effect -> {
                var type = effect.getEffect().value();
                if (type.isInstantenous()) {
                    target.invulnerableTime = 0;
                    type.applyInstantenousEffect(level, shooter, shooter, target, effect.getAmplifier(), 1);
                } else target.addEffect(effect, shooter);
            }, 1);
        }
    }

    /**
     * 这一发落定在 {@code at}（打中的第一个生物、打在方块上，或者飞到尽头），{@code dir} 是它飞的方向，
     * {@code impact} 是不是真落在了生物或方块上（飞到尽头的不算）：烟火弹炸开烟火之星，震爆弹炸开，末影弹把开枪的人传送过去。
     */
    public static void landed(ItemStack round, ServerLevel level, LivingEntity shooter, Vec3 at, Vec3 dir, boolean impact) {
        String finish = finish(round);
        if (FIREWORK.equals(finish)) {
            var star = round.get(DataComponents.FIREWORK_EXPLOSION);
            var rocket = new ItemStack(Items.FIREWORK_ROCKET);
            rocket.set(DataComponents.FIREWORKS, new Fireworks(0, List.of(star == null ? FireworkExplosion.DEFAULT : star)));
            // 一枚不飞的烟火：寿命 0，下一刻就在原地炸开（炸开的画面、伤害都是原版烟火的）。
            var firework = new FireworkRocketEntity(level, rocket, shooter, at.x, at.y, at.z, true);
            firework.setDeltaMovement(Vec3.ZERO);
            ((FireworkRocketAccess) firework).logos_dimension$setLifetime(0);
            level.addFreshEntity(firework);
        } else if (BLAST.equals(finish)) {
            // 原版的爆炸：伤到、震开周围（开枪的人离得近也会被炸到），不毁方块、不起火。
            level.explode(shooter, at.x, at.y, at.z, BLAST_POWER, false, Level.ExplosionInteraction.NONE);
        } else if (ENDER.equals(finish) && impact) {
            teleport(round, level, shooter, at, dir);
        }
    }

    /** 末影弹：把开枪的人传送到落点前面一点（退回半格，卡在方块里就往下、往上找个放得下的高度），像末影珍珠那样受伤。 */
    private static void teleport(ItemStack round, ServerLevel level, LivingEntity shooter, Vec3 at, Vec3 dir) {
        Vec3 back = at.subtract(dir.normalize().scale(.5));
        Vec3 to = back;
        var size = shooter.getDimensions(shooter.getPose());
        for (double dy : new double[]{0, -size.height() * .5, -size.height(), .5}) {
            Vec3 c = back.add(0, dy, 0);
            if (level.noCollision(shooter, size.makeBoundingBox(c))) {
                to = c;
                break;
            }
        }
        // 保护领地之类的模组能拦下（NeoForge 的「用物品传送」事件）。
        var event = EventHooks.onItemConsumptionTeleport(shooter, round, to.x, to.y, to.z);
        if (event.isCanceled()) return;
        Vec3 from = shooter.position();
        Entity moved = shooter.teleport(new TeleportTransition(level, event.getTarget(), shooter.getDeltaMovement(),
                shooter.getYRot(), shooter.getXRot(), TeleportTransition.DO_NOTHING));
        if (moved == null) return;
        moved.resetFallDistance();
        if (moved instanceof LivingEntity living) living.resetCurrentImpulseContext();
        if (moved instanceof ServerPlayer player) player.hurtServer(level, level.damageSources().enderPearl(), ENDER_DAMAGE);
        for (Vec3 p : new Vec3[]{from, moved.position()}) {
            level.sendParticles(ParticleTypes.PORTAL, p.x, p.y + 1, p.z, 32, .3, .8, .3, .4);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS, 1, 1);
        }
    }
}
