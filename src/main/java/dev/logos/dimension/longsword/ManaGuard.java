package dev.logos.dimension.longsword;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.item.WandItem;
import dev.logos.lang.Element;
import dev.logos.runtime.ConstructObj;
import dev.logos.runtime.Constructs;
import dev.logos.runtime.Effects;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.SpellWard;
import dev.logos.runtime.Targeting;
import dev.logos.runtime.Vfx;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import org.jspecify.annotations.Nullable;

/**
 * 施法剑（黑钢长剑）的魔力格挡：竖剑格挡时剑前是一面魔力护盾，挡下的东西从持剑者的魔力里扣，不磨损剑的耐久。
 * <ul>
 * <li>原版攻击照原版格挡流程挡下。原版盾牌这一下会掉多少耐久，就扣多少魔力：用物品格挡组件的 {@code item_damage}，
 *     按原版盾牌写（伤害不到 3 不掉，否则掉 1 + 伤害取整）。魔力不够就挡不住，这一下照常打到身上，魔力不扣。</li>
 * <li>魔法伤害（原版 {@code magic}、{@code indirect_magic}；Logos 除物理、火以外的元素伤害都是它）原版盾牌挡不住，这里照样挡，
 *     同样只挡正面、同样扣魔力。</li>
 * <li>法术体借用 Logos 炼成体拦截法术的逻辑（{@link SpellWard}）：正面飞来的魔弹、光柱、游脉在护盾处停下，
 *     付炼成体同样的拦截费（魔弹每发 {@link ConstructObj#BLOCK_COST}，光柱每刻十分之一），再加上它命中时的伤害按上面的公式折成的魔力。
 *     炼成体从自己的魔力里付，这里从持剑者的魔力里付，付不起就挡不住。只挡把持剑者当敌人的术式（Logos 的 {@link Targeting#isEnemy}），
 *     自己的、友方的、不许作用于玩家的术式照常穿过。</li>
 * <li>虚空属性一律穿透：不比魔力和伤害谁大，虚空的法术体与虚空伤害都不挡。</li>
 * </ul>
 * 只有竖剑格挡的招式（过了举剑前摇）挡得住，见 {@link SwordCasting#guarding}。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class ManaGuard {
    /** 护盾离身体多远（碰撞箱外再扩这么多）：盖过魔弹默认的 0.3 与光柱默认的 0.5 半径，法术停下的位置大约在剑前那圈符文处。 */
    private static final double REACH = 0.6;

    private ManaGuard() {}

    /** 把护盾交给 Logos 的法术拦截（模组构造时调用一次）。 */
    public static void init() {
        Constructs.addWards(ManaGuard::near);
    }

    /** 正竖剑格挡（过了举剑前摇）的施法剑持有者，其剑的格挡组件；不在格挡时为 null。 */
    static @Nullable BlocksAttacks guard(ServerPlayer player) {
        ItemStack stack = player.getItemBlockingWith();
        if (stack == null || !(stack.getItem() instanceof WandItem) || !(stack.getItem() instanceof CombatWeapon.Holder)) return null;
        String move = LongswordCombat.move(player);
        return move != null && SwordCasting.guarding(move) ? stack.get(DataComponents.BLOCKS_ATTACKS) : null;
    }

    /** 从持剑者的魔力里扣 {@code mana}；不够时一点不扣，返回 false。 */
    static boolean pay(ServerPlayer player, double mana) {
        if (mana <= 0) return true;
        PlayerMagic m = PlayerMagic.of(player);
        if (m.mana < mana) return false;
        m.mana -= mana;
        return true;
    }

    /** 和原版格挡一样只看水平方向：来处在视线左右 90° 以内算正面。 */
    private static boolean inFront(ServerPlayer player, Vec3 from) {
        Vec3 to = from.subtract(player.position());
        Vec3 flat = new Vec3(to.x, 0, to.z);
        return flat.lengthSqr() > 1e-6 && flat.normalize().dot(Vec3.directionFromRotation(0, player.getYHeadRot())) >= 0;
    }

    private static List<Ward> near(ServerLevel level, AABB area) {
        List<Ward> wards = new ArrayList<>(0);
        for (ServerPlayer p : level.players()) {
            if (!p.getBoundingBox().inflate(REACH).intersects(area)) continue;
            BlocksAttacks guard = guard(p);
            if (guard != null) wards.add(new Ward(p, guard));
        }
        return wards;
    }

    private record Ward(ServerPlayer player, BlocksAttacks guard) implements SpellWard {
        @Override
        public @Nullable Vec3 wouldStop(@Nullable SpellInstance inst, Vec3 from, Vec3 to, Element element, double damage) {
            // 只挡会打到持剑者的：自己的、友方的、不许作用于玩家的术式本来就打不着他，穿过去不扣魔力。
            if (element == Element.VOID || inst != null && !Targeting.isEnemy(inst, player) || !inFront(player, from)) return null;
            AABB shield = player.getBoundingBox().inflate(REACH);
            return shield.contains(from) ? null : shield.clip(from, to).orElse(null);
        }

        @Override
        public boolean stop(Vec3 at, double cost, double damage) {
            if (!pay(player, cost + guard.itemDamage().apply((float) damage))) return false;
            // 光柱顶着护盾的每一刻都要付，只有魔弹撞上、光柱每次脉冲打中时才出声、迸光。
            if (cost >= ConstructObj.BLOCK_COST || damage > 0) {
                guard.onBlocked(player.level(), player);
                Vfx.burst(player.level(), at, Element.ARCANE, 6);
            }
            return true;
        }
    }

    /** 魔法伤害也挡，虚空一律穿透（在只认竖剑格挡的 {@link SwordCasting} 之后）。 */
    @SubscribeEvent
    static void magic(LivingShieldBlockEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || guard(player) == null) return;
        if (Effects.dealing() == Element.VOID) {
            event.setBlocked(false);
            return;
        }
        var source = event.getDamageSource();
        if (!event.getBlocked() && event.getBlockedDamage() > 0 && (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC))) {
            event.setBlocked(true);
        }
    }

    /** 最后结算：挡得住就把本该掉的耐久换成魔力扣掉，付不起就挡不住。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void cost(LivingShieldBlockEvent event) {
        if (!event.getBlocked() || !(event.getEntity() instanceof ServerPlayer player)) return;
        BlocksAttacks guard = guard(player);
        if (guard == null) return;
        int wear = event.shieldDamage() >= 0 ? event.shieldDamage() : guard.itemDamage().apply(event.getBlockedDamage());
        event.setShieldDamage(0);
        if (!pay(player, wear)) event.setBlocked(false);
    }
}
