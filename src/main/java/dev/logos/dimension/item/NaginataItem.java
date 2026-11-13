package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.longsword.LongswordCombat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 神狐薙刀（三种刃型 × 四套刀装，共 12 把，数值相同）：平时右手单手立持；左键四刀连招（袈裟、逆袈裟、脛払い、面），
 * 出招时左手搭上来双手挥。右键按住立即格挡，打断正在进行的连招。格挡用原版的格挡组件，只把盾的举盾延迟改成 0，
 * 减伤、击退和耐久仍走原版流程。杆长约 2.2 格，攻击距离比原版多半格。
 */
public final class NaginataItem extends Item implements CombatWeapon.Holder {
    private static final Identifier MOVESET = LogosDimension.id("naginata");
    /** 第一个战斗武器编号；按刃型、刀装的顺序往后排，占 12–23（11 是凌霄，30 起是流火）。 */
    private static final int FIRST_ID = 12;
    private static final String[] HEADS = {"standard", "slender", "broad"};
    private static final String[] FINISHES = {"wood", "ink", "vermilion", "indigo"};
    /** 攻击距离加成（格）。 */
    static final double REACH = 0.5;
    /** 全部 12 把，按创造模式物品栏的顺序。 */
    public static final List<DeferredItem<NaginataItem>> ALL = registerAll();
    private final CombatWeapon weapon;

    private static List<DeferredItem<NaginataItem>> registerAll() {
        List<DeferredItem<NaginataItem>> out = new ArrayList<>();
        int id = FIRST_ID;
        for (var head : HEADS) for (var finish : FINISHES) {
            String name = "fox_naginata_" + head + "_" + finish;
            var weapon = CombatWeapon.register(id++, name, "naginata", MOVESET).withCone(110);
            out.add(DimensionBlocks.ITEMS.registerItem(name, p -> new NaginataItem(p, weapon)));
        }
        return List.copyOf(out);
    }
    public NaginataItem(Properties p, CombatWeapon weapon) {
        super(p.sword(ToolMaterial.IRON, 4, -2.7f)
                // 剑的属性只有伤害与攻速；薙刀另加攻击距离，整组覆盖。
                .attributes(ItemAttributeModifiers.builder()
                        .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,
                                4 + ToolMaterial.IRON.attackDamageBonus(), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, -2.7,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(LogosDimension.id("naginata_reach"), REACH,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .build())
                .delayedComponent(DataComponents.BLOCKS_ATTACKS, context -> new BlocksAttacks(0, 1,
                        List.of(new BlocksAttacks.DamageReduction(90, Optional.empty(), 0, 1)),
                        new BlocksAttacks.ItemDamageFunction(3, 1, 1),
                        Optional.of(context.getOrThrow(DamageTypeTags.BYPASSES_SHIELD)),
                        Optional.of(SoundEvents.SHIELD_BLOCK), Optional.of(SoundEvents.SHIELD_BREAK)))
                .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("tooltip.logos_dimension.naginata.combo"),
                        Component.translatable("tooltip.logos_dimension.naginata.guard")))));
        this.weapon = weapon;
    }
    public static void init() {}
    @Override public CombatWeapon combatWeapon() { return weapon; }
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
}
