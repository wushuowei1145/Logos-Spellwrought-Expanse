package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.neoforge.registries.DeferredItem;

/** 原版长矛模板：钻石戳刺伤害、木矛攻速、铁矛冲撞，衔接萌芽与生命易伤。 */
public final class ElfSpearItem extends Item {
    public static final int MARK_TICKS = 120;
    public static final int VULNERABILITY_TICKS = 80;
    public static final int MANA_COST = 5;
    public static final double LIFE_DAMAGE = 6;
    public static final DeferredItem<ElfSpearItem> ITEM = DimensionBlocks.ITEMS.registerItem("elf_spear", ElfSpearItem::new);

    public ElfSpearItem(Properties properties) { super(properties(properties)); }
    public static void init() {}

    /** 与古木之拥双持时让出右键，由原版双手交互继续选择盾，禁盾期间也不退回冲刺。 */
    @Override public net.minecraft.world.InteractionResult use(net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        if (SpearShieldGuard.paired(player)) return net.minecraft.world.InteractionResult.PASS;
        return super.use(level, player, hand);
    }

    private static Properties properties(Properties p) {
        return p.spear(ToolMaterial.IRON, .65f, .95f, .6f, 2.5f, 11f, 6.75f, 5.1f, 11.25f, 4.6f)
                .component(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
                        .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID, 3, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID, 1d / .65d - 4, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .build());
    }

    /** 只在原版物理命中成功后调用，挥空、盾牌完全格挡和伤害被拒绝不会种印或消耗资源。 */
    public static void afterPhysicalHit(ServerPlayer player, LivingEntity target, EquipmentSlot slot) {
        if (!player.getItemBySlot(slot).is(ITEM.get()) || !target.isAlive()) return;
        long now = target.level().getGameTime();
        boolean charging = player.isUsingItem() && player.getUsedItemHand().asEquipmentSlot() == slot;
        if (!charging) {
            target.setData(NatureAttachments.SPROUT_MARK, new SproutMark(player.getUUID(), now + MARK_TICKS));
            return;
        }
        if (!target.hasData(NatureAttachments.SPROUT_MARK)) return;
        var mark = target.getData(NatureAttachments.SPROUT_MARK);
        if (!mark.belongsTo(player.getUUID(), now)) return;
        var vulnerability = LogosNatureBridge.lifeVulnerability();
        // 同版本号的早期 Logos 尚无生命易伤：保留普通物理与印记，不扣魔力。
        if (vulnerability.isEmpty()) {
            player.sendOverlayMessage(Component.translatable("message.logos_dimension.elf_spear.old_logos"));
            return;
        }
        if (!player.hasInfiniteMaterials()) {
            double mana = LogosNatureBridge.mana(player);
            if (mana < MANA_COST) return;
            LogosNatureBridge.mana(player, mana - MANA_COST);
        }
        // 先清印，避免伤害事件中的其它效果重入；已有易伤正常参与本次伤害，新易伤在其后添加。
        target.setData(NatureAttachments.SPROUT_MARK, SproutMark.EMPTY);
        LogosNatureBridge.damage(player, target, LIFE_DAMAGE);
        if (target.isAlive()) target.addEffect(new MobEffectInstance(vulnerability.get(), VULNERABILITY_TICKS, 0), player);
    }
}
