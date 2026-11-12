package dev.logos.dimension.explosion;

import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Spell;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.ManaVessel;
import dev.logos.runtime.ManaVessels;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import org.jspecify.annotations.Nullable;

/**
 * 爆裂魔杖在 Logos 眼里是一根只有一张载页的法杖：载页是贤者级（容量 6400），里面的术式意图是攻击（一枚魔弹打中造成伤害），
 * 所以会按法杖施法的生物（车万女仆的「施法」工作模式）认得它、拿它攻击，一次注入 6400。
 *
 * <p>载页上的术式只是给它们看的：不是玩家的施法者拿着爆裂魔杖施放这张载页时，{@code mixin/ExplosionStaffCastMixin}
 * 在 Logos 跑术式之前接过来，注满 6400 就在目标处放出真正的爆裂魔法（规则和玩家放的一样，范围内全灭，包括施法者自己和她的主人），
 * 没注满就把注入的魔力退回它的魔力池。女仆魔力池里不到 6400 时不开始蓄力（{@code mixin/ExplosionMaidChargeMixin}）。
 * 玩家自己用时照旧是 {@link ExplosionMagic} 的吟唱，和这张载页无关。
 */
public final class ExplosionStaffPage {
    /** 载页上的术式。改它时 {@link #is} 认的也跟着变，旧魔杖上的载页就不再被接管，所以别随便改。 */
    public static final String SOURCE = """
            ; 爆裂魔杖的载页：给按法杖施法的生物看的。拿着爆裂魔杖施放时放出的是真正的爆裂魔法
            (spell :name "爆裂魔法" :intent strike :mana 6400 :trigger cast
              (projectile
                (on-hit (damage target 10 fire))))
            """;
    /** 载页的品级：容量正好 6400。 */
    public static final PageTier TIER = PageTier.SAGE;

    private ExplosionStaffPage() {}

    public static void register(IEventBus bus) {
        // 谁拿起爆裂魔杖就给它补上这张载页（女仆接过魔杖、存档里早就拿着的进世界后第一刻都算）。
        // 不做成物品的默认组件：26.1 构建默认组件时还不能新建物品，载页是 Logos 的物品
        NeoForge.EVENT_BUS.addListener((LivingEquipmentChangeEvent event) -> {
            if (event.getSlot() == EquipmentSlot.MAINHAND && !event.getEntity().level().isClientSide()) ensure(event.getTo());
        });
    }

    /** 爆裂魔杖还没有载页（新做的、旧存档里的）时补上；已经有了（哪怕被拿空了）不动。 */
    public static void ensure(ItemStack stack) {
        if (!stack.is(ExplosionStaffItem.ITEM.get()) || stack.has(DataComponents.CONTAINER)) return;
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(SpellPages.inscribed(TIER, SOURCE.stripIndent().strip() + "\n", null))));
    }

    /** {@code spell} 是不是爆裂魔杖的那张载页。 */
    public static boolean is(Spell spell) {
        return spell.source().strip().equals(SOURCE.stripIndent().strip());
    }

    /** 不是玩家的施法者拿着爆裂魔杖：施法时要被接过来。 */
    public static boolean holds(LivingEntity caster) {
        return !(caster instanceof Player) && caster.getMainHandItem().is(ExplosionStaffItem.ITEM.get());
    }

    /** 魔力池里不到 6400：先别开始蓄力。 */
    public static boolean waiting(LivingEntity caster, ManaVessel pool) {
        return holds(caster) && pool.mana() + 1e-6 < ExplosionMagic.MANA;
    }

    /**
     * 拿着爆裂魔杖的生物施放了这张载页（注入 {@code mana}）：注满就在 {@code target}（没有时 {@code here}，再没有就眼前）放出爆裂魔法，
     * 不够就退回魔力。返回 false 时不是这种情况，Logos 照常跑术式。
     */
    public static boolean cast(LivingEntity caster, Spell spell, double mana, @Nullable Entity target, @Nullable Vec3 here) {
        if (!holds(caster) || !is(spell) || !(caster.level() instanceof ServerLevel level)) return false;
        if (mana + 1e-6 < ExplosionMagic.MANA) {
            ManaVessel pool = ManaVessels.of(caster);
            double back = pool == null ? 0 : pool.receiveMana(mana);
            if (mana - back > 1e-6) FreeManaEntity.release(level, caster.getEyePosition(), mana - back);
            return true;
        }
        Vec3 at = target != null ? target.position() : here != null ? here : caster.getEyePosition().add(caster.getLookAngle().scale(16));
        ExplosionMagic.fire(level, at, null);
        return true;
    }
}
