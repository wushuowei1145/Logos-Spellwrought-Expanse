package dev.logos.dimension.item;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.longsword.LongswordCombat;
import dev.logos.dimension.longsword.ManaGuard;
import dev.logos.dimension.longsword.SwordCasting;
import dev.logos.item.WandItem;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;

/**
 * 黑钢长剑：长剑的三段连斩，同时是一根 Logos 法杖（{@link WandItem}）：潜行右键挂 6 张术式载页，R 打开术式轮盘，
 * 蓄力速度按 Logos 第 3 档法杖。右键竖剑格挡（原版格挡组件，0.3 秒举剑前摇，减伤与击退走原版流程）：这是一面魔力护盾，
 * 挡下的东西扣持剑者的魔力而不是耐久，正面的法术也挡，虚空一律穿透（见 {@link ManaGuard}）；
 * 格挡中左键开始施法蓄力，蓄满后松开右键施放（见 {@link SwordCasting}）。
 * 用它施放的术式里，魔力缠绕及其事件引发的符文花费 −30%、伤害 +30%（Logos 的 {@code wrap_cost}、{@code wrap_power} 属性）。
 * <p>
 * 法杖的构造会把物品的属性换成注入流量与汇集加速，剑的攻击伤害与攻速在 {@link #attributes} 里补回。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class BlacksteelLongswordItem extends WandItem implements CombatWeapon.Holder {
    /** Logos「法杖数值」第 3 档的注入流量与汇集加速。 */
    private static final int SLOTS = 6;
    private static final double FLOW = 70, SURGE = 130;
    /** 与原版钻石剑相同：伤害 3 + 材质 3，攻速 −2.4。 */
    private static final float DAMAGE = 3, SPEED = -2.4f;
    private static final double WRAP_COST = -0.3, WRAP_POWER = 0.3;
    private static final Identifier WRAP_COST_ATTRIBUTE = Identifier.fromNamespaceAndPath("logos", "wrap_cost");
    private static final Identifier WRAP_POWER_ATTRIBUTE = Identifier.fromNamespaceAndPath("logos", "wrap_power");
    /** 原版盾牌掉耐久的公式（伤害不到 3 不掉，否则 1 + 伤害取整）：{@link ManaGuard} 按它扣魔力，剑本身不掉耐久。 */
    private static final BlocksAttacks.ItemDamageFunction WEAR = new BlocksAttacks.ItemDamageFunction(3, 1, 1);
    private final CombatWeapon weapon;

    public BlacksteelLongswordItem(Properties p, CombatWeapon weapon) {
        super(p.sword(ToolMaterial.DIAMOND, DAMAGE, SPEED)
                .delayedComponent(DataComponents.BLOCKS_ATTACKS, context -> new BlocksAttacks(0.3f, 1,
                        List.of(new BlocksAttacks.DamageReduction(90, Optional.empty(), 0, 1)),
                        WEAR, Optional.of(context.getOrThrow(DamageTypeTags.BYPASSES_SHIELD)),
                        // 和 Logos 的炼成体一样：挡下时是紫水晶的敲击声，被斧头破防时是紫水晶簇碎裂声。
                        Optional.of(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.AMETHYST_BLOCK_HIT)),
                        Optional.of(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.AMETHYST_CLUSTER_BREAK))))
                .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("tooltip.logos_dimension.longsword.combo"),
                        Component.translatable("tooltip.logos_dimension.blacksteel_longsword.guard"),
                        Component.translatable("tooltip.logos_dimension.blacksteel_longsword.wrap")))),
                SLOTS, FLOW, SURGE);
        this.weapon = weapon;
    }

    @Override public CombatWeapon combatWeapon() { return weapon; }

    /** 潜行右键照法杖打开载页栏位；右键进入格挡（招式文件的 use_press）。 */
    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player.isShiftKeyDown()) return super.use(level, player, hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        if (player instanceof ServerPlayer server && !LongswordCombat.charge(server)) return InteractionResult.FAIL;
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (user instanceof ServerPlayer player && !SwordCasting.release(player)) LongswordCombat.release(player);
        return true;
    }

    @Override public boolean allowsAttack(ServerPlayer player, String move) { return SwordCasting.allowsAttack(player, move); }
    @Override public void combatTick(ServerPlayer player, String move) { SwordCasting.tick(player, move); }
    @Override public boolean manaGuard(ServerPlayer player, String move) { return SwordCasting.guarding(move); }

    @Override protected String usage() { return Component.translatable("tooltip.logos_dimension.blacksteel_longsword.cast").getString(); }

    /** 补回剑的伤害与攻速；装了带 wrap_cost、wrap_power 的 Logos 时再加缠绕的减费与增伤，没有就不加。 */
    @SubscribeEvent static void attributes(ItemAttributeModifierEvent event) {
        if (!(event.getItemStack().getItem() instanceof BlacksteelLongswordItem)) return;
        event.addModifier(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,
                DAMAGE + ToolMaterial.DIAMOND.attackDamageBonus(), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        event.addModifier(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, SPEED,
                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND);
        BuiltInRegistries.ATTRIBUTE.get(WRAP_COST_ATTRIBUTE).ifPresent(a -> event.addModifier(a, new AttributeModifier(
                LogosDimension.id("blacksteel_wrap_cost"), WRAP_COST, AttributeModifier.Operation.ADD_MULTIPLIED_BASE), EquipmentSlotGroup.MAINHAND));
        BuiltInRegistries.ATTRIBUTE.get(WRAP_POWER_ATTRIBUTE).ifPresent(a -> event.addModifier(a, new AttributeModifier(
                LogosDimension.id("blacksteel_wrap_power"), WRAP_POWER, AttributeModifier.Operation.ADD_MULTIPLIED_BASE), EquipmentSlotGroup.MAINHAND));
    }
}
