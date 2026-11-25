package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.firearm.Firearm;
import dev.logos.dimension.firearm.Firearms;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 铳斧：转轮步枪，枪管下一条长刃（docs/GUNAXE.md）。近战是单手抡长柄斧一样的四连击，第五下扣扳机（空膛只空响）；
 * 按住右键举枪瞄准，瞄准时左键开枪。六发转轮，装背包里的任何弹药（docs/FIREARMS.md）。
 */
public final class GunaxeItem extends Item implements CombatWeapon.Holder, Firearm {
    public static final CombatWeapon WEAPON = CombatWeapon.register(50, "gunaxe", "gunaxe", LogosDimension.id("gunaxe")).withoutSheath();
    public static final DeferredItem<GunaxeItem> ITEM = DimensionBlocks.ITEMS.registerItem("gunaxe", GunaxeItem::new);
    public static final int CAPACITY = 6;
    /** 近战：伤害 9（铁斧），攻速 1.0，长刃比剑多够 1 格。 */
    public static final double DAMAGE = 9, SPEED = 1.0, REACH = 1.0;

    public GunaxeItem(Properties properties) {
        super(properties.durability(750)
                .attributes(ItemAttributeModifiers.builder()
                        .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, DAMAGE - 1,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, SPEED - 4,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(LogosDimension.id("gunaxe_reach"), REACH,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .build())
                .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("tooltip.logos_dimension.gunaxe.combo"),
                        Component.translatable("tooltip.logos_dimension.gunaxe.aim"),
                        Component.translatable("tooltip.logos_dimension.gunaxe.reload")))));
    }
    public static void init() {}

    @Override public CombatWeapon combatWeapon() { return WEAPON; }
    @Override public int capacity() { return CAPACITY; }

    /** 右键按住举枪瞄准（招式文件的 use_press）；换弹、出招收势之外不接受时不举。 */
    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        if (player instanceof ServerPlayer server && !LongswordCombat.charge(server)) return InteractionResult.FAIL;
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }
    @Override public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (user instanceof ServerPlayer player) LongswordCombat.release(player);
        return true;
    }
    @Override public int getUseDuration(ItemStack stack, LivingEntity user) { return 72000; }
    @Override public ItemUseAnimation getUseAnimation(ItemStack stack) { return ItemUseAnimation.NONE; }

    @Override public void fire(ServerPlayer player, Moveset.Move move, int index) {
        Firearms.fire(player, player.getMainHandItem(), move.name().startsWith("aim"));
    }
    @Override public void feed(ServerPlayer player, Moveset.Move move) { Firearms.feed(player, player.getMainHandItem()); }
    @Override public boolean allows(ServerPlayer player, String requirement) {
        return Firearms.allows(player, player.getMainHandItem(), requirement);
    }
}
