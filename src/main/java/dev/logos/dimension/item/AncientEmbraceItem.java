package dev.logos.dimension.item;

import com.mojang.serialization.Codec;
import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** 原版盾操作，立即格挡；举盾前五刻成功格挡充两片叶，其余成功格挡充一片。 */
public final class AncientEmbraceItem extends ShieldItem {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<AncientEmbraceCharge>> CHARGE = COMPONENTS.registerComponentType(
            "ancient_embrace_charge", b -> b.persistent(AncientEmbraceCharge.CODEC).networkSynchronized(AncientEmbraceCharge.STREAM_CODEC));
    public static final Supplier<AttachmentType<Long>> COOLDOWN = NatureAttachments.TYPES.register("ancient_embrace_cooldown",
            () -> AttachmentType.builder(() -> 0L).serialize(Codec.LONG.fieldOf("until")).build());
    public static final DeferredItem<AncientEmbraceItem> ITEM = DimensionBlocks.ITEMS.registerItem("ancient_embrace", AncientEmbraceItem::new);
    public static final int PERFECT_TICKS = 5;
    public static final int COOLDOWN_TICKS = 160;
    public static final int MANA_COST = 5;
    public static final float HEAL = 4;

    public AncientEmbraceItem(Properties p) {
        super(p.durability(336).repairable(net.minecraft.tags.ItemTags.WOODEN_TOOL_MATERIALS)
                .equippableUnswappable(EquipmentSlot.OFFHAND)
                .component(DataComponents.BREAK_SOUND, net.minecraft.sounds.SoundEvents.SHIELD_BREAK)
                .component(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                        net.minecraft.network.chat.Component.translatable("item.logos_dimension.ancient_embrace.desc"))))
                .component(CHARGE.get(), AncientEmbraceCharge.EMPTY)
                .delayedComponent(DataComponents.BLOCKS_ATTACKS, context -> {
                    // 与当前原版盾逐项一致，只把五刻前摇改为零；原版延迟组件此时未必已初始化。
                    return new BlocksAttacks(0, 1, java.util.List.of(new BlocksAttacks.DamageReduction(90, java.util.Optional.empty(), 0, 1)),
                            new BlocksAttacks.ItemDamageFunction(3, 1, 1),
                            java.util.Optional.of(context.getOrThrow(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD)),
                            java.util.Optional.of(net.minecraft.sounds.SoundEvents.SHIELD_BLOCK),
                            java.util.Optional.of(net.minecraft.sounds.SoundEvents.SHIELD_BREAK));
                }));
    }

    public static void register(IEventBus bus) { COMPONENTS.register(bus); }

    /** 在原版及其它模组完成格挡结算后记录实际挡下的攻击，盾被禁用不会自动触发收盾治疗。 */
    public static void blocked(ServerPlayer player, ItemStack shield, int elapsedTicks) {
        long now = player.level().getGameTime();
        if (!shield.is(ITEM.get()) || !player.isAlive() || player.getData(COOLDOWN) > now) return;
        var before = shield.getOrDefault(CHARGE.get(), AncientEmbraceCharge.EMPTY);
        shield.set(CHARGE.get(), before.block(now, elapsedTicks < PERFECT_TICKS));
    }

    @Override public boolean releaseUsing(ItemStack shield, Level level, LivingEntity entity, int remainingTime) {
        if (entity instanceof ServerPlayer player && player.isAlive()) {
            long now = level.getGameTime();
            var charge = shield.getOrDefault(CHARGE.get(), AncientEmbraceCharge.EMPTY);
            if (charge.activeLeaves(now) != AncientEmbraceCharge.MAX || player.getData(COOLDOWN) > now
                    || player.getHealth() >= player.getMaxHealth()) return false;
            boolean free = player.hasInfiniteMaterials();
            double mana = free ? 0 : LogosNatureBridge.mana(player);
            if (!free && mana < MANA_COST) return false;
            float before = player.getHealth();
            player.heal(HEAL);
            // 治疗被其它效果取消时不消耗生机、魔力或冷却。
            if (player.getHealth() > before) {
                if (!free) LogosNatureBridge.mana(player, mana - MANA_COST);
                shield.set(CHARGE.get(), AncientEmbraceCharge.EMPTY);
                player.setData(COOLDOWN, now + COOLDOWN_TICKS);
                LogosNatureBridge.healBurst(player);
            }
        }
        return false;
    }

    @Override public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot) {
        var charge = stack.getOrDefault(CHARGE.get(), AncientEmbraceCharge.EMPTY);
        if (charge.leaves() > 0 && charge.activeLeaves(level.getGameTime()) == 0) stack.set(CHARGE.get(), AncientEmbraceCharge.EMPTY);
    }
}
