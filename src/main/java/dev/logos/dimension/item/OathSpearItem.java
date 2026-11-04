package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.longsword.LongswordCombat;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 群誓·狼牙长枪：左键四段连击（直刺、横扫、过肩刺、上挑），第四击把锥形里的目标挑上半空（{@link dev.logos.dimension.combat.Airborne}）；
 * 右键按住一秒八刺地连续快刺，伤害低，刺浮空的目标伤害翻倍并维持浮空；按住约三秒后转枪下砸，砸落浮空目标、受高额摔落伤害。
 * 枪长约 2.7 格，攻击距离比原版多 1 格。拔枪从背后转两圈拿出，收枪转一圈背回身后。
 */
public final class OathSpearItem extends Item implements CombatWeapon.Holder {
    private static final net.minecraft.resources.Identifier MOVESET = LogosDimension.id("oath_spear");
    /** 默认张角（招式没写 cone 时用）：长枪以刺为主，比剑窄。 */
    private static final CombatWeapon WEAPON = CombatWeapon.register(6, "pack_oath_spear", "oath_spear", MOVESET).withCone(50);
    public static final DeferredItem<OathSpearItem> PACK_OATH = DimensionBlocks.ITEMS.registerItem("pack_oath_spear", OathSpearItem::new);
    /** 攻击距离加成（格）。 */
    static final double REACH = 1.0;

    public OathSpearItem(Properties p) {
        super(p.sword(ToolMaterial.IRON, 4, -2.6f)
                // 剑的属性只有伤害与攻速；长枪另加攻击距离，整组覆盖。
                .attributes(ItemAttributeModifiers.builder()
                        .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,
                                4 + ToolMaterial.IRON.attackDamageBonus(), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, -2.6,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(LogosDimension.id("spear_reach"), REACH,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .build())
                .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("tooltip.logos_dimension.oath_spear.combo"),
                        Component.translatable("tooltip.logos_dimension.oath_spear.flurry")))));
    }
    public static void init() {}
    @Override public CombatWeapon combatWeapon() { return WEAPON; }
    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        // 招式缺失时按原版物品处理。按下的这一刻当前招式还不收右键（例如直刺还没刺中）也照样开始“使用”：
        // 服务端每刻都会补发 use_press，一到能接的时候就转入连刺，按住不放不会白按。
        if (player instanceof ServerPlayer server) {
            if (LongswordCombat.move(server) == null) return InteractionResult.FAIL;
            LongswordCombat.charge(server);
        }
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }
    /** 没有格挡、食物之类的组件时原版的使用时长是 0（立即结束）；按住右键连刺要一直“使用”。 */
    @Override public int getUseDuration(ItemStack stack, LivingEntity user) { return 72000; }
    @Override public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (user instanceof ServerPlayer player) LongswordCombat.release(player);
        return true;
    }
}
