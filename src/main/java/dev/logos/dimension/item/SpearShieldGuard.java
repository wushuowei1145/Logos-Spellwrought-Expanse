package dev.logos.dimension.item;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** 精灵矛与古木盾的限定双持联动，戳刺期间保留原有举盾状态。 */
public final class SpearShieldGuard {
    public static boolean paired(Player player) {
        return (player.getMainHandItem().is(ElfSpearItem.ITEM.get()) && player.getOffhandItem().is(AncientEmbraceItem.ITEM.get()))
                || (player.getOffhandItem().is(ElfSpearItem.ITEM.get()) && player.getMainHandItem().is(AncientEmbraceItem.ITEM.get()));
    }
    public static boolean guarding(Player player) {
        return paired(player) && player.isAlive() && !player.isSpectator()
                && player.isBlocking() && player.getUseItem().is(AncientEmbraceItem.ITEM.get());
    }
    public static boolean reversed(Player player) {
        return paired(player) && spearHand(player) == InteractionHand.OFF_HAND;
    }
    public static boolean kineticActive(Player player) {
        return reversed(player) && guarding(player) && player.getUsedItemHand() == InteractionHand.MAIN_HAND;
    }
    public static InteractionHand spearHand(Player player) {
        return player.getMainHandItem().is(ElfSpearItem.ITEM.get()) ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
    }

    /** 副手矛仅在这一次戳刺与冷却计算中应用武器属性，不给持盾普攻或其它主手物品常驻加成。 */
    public static void withSpearAttributes(Player player, Runnable attack) {
        if (spearHand(player) == InteractionHand.MAIN_HAND) { attack.run(); return; }
        record Added(AttributeInstance attribute, Identifier id) {}
        var added = new ArrayList<Added>();
        try {
            player.getOffhandItem().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                    .forEach(EquipmentSlot.MAINHAND, (holder, modifier) -> {
                        var attribute = player.getAttribute(holder);
                        var id = LogosDimension.id("guarded_spear/" + modifier.id().toString().replace(':', '/'));
                        if (attribute != null && attribute.getModifier(id) == null) {
                            attribute.addTransientModifier(new AttributeModifier(id, modifier.amount(), modifier.operation()));
                            added.add(new Added(attribute, id));
                        }
                    });
            attack.run();
        } finally {
            for (var entry : added) entry.attribute().removeModifier(entry.id());
        }
    }

    /** 接收原版 STAB 包时处理反手搭配；仍使用原版长矛射线、命中、附魔与耐久路径。 */
    public static void offhandAttack(Player player) {
        if (!reversed(player) || player.isUsingItem() || !player.isAlive() || player.isSpectator()) return;
        withSpearAttributes(player, () -> {
            var stack = player.getOffhandItem();
            if (player.cannotAttackWithItem(stack, 5)) return;
            var piercing = stack.get(DataComponents.PIERCING_WEAPON);
            if (piercing != null) piercing.attack(player, EquipmentSlot.OFFHAND);
        });
    }
    private SpearShieldGuard() {}
}
