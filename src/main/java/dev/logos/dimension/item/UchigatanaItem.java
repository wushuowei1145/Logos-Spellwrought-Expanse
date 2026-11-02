package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.longsword.LongswordCombat;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 神狐打刀（四套刀装）：左键连招；右键按住立即格挡，打断正在进行的连招。格挡用原版的格挡组件，
 * 只把盾的 0.25 秒举盾延迟改成 0，减伤、击退和耐久仍走原版流程。格挡开始 0.3 秒内被击中会弹刀（招式文件的 block 输入）。
 */
public final class UchigatanaItem extends Item implements CombatWeapon.Holder {
    private static final net.minecraft.resources.Identifier MOVESET = LogosDimension.id("uchigatana");
    public static final DeferredItem<UchigatanaItem> INK = register("ink", 2);
    public static final DeferredItem<UchigatanaItem> VERMILION = register("vermilion", 3);
    public static final DeferredItem<UchigatanaItem> INDIGO = register("indigo", 4);
    public static final DeferredItem<UchigatanaItem> WOOD = register("wood", 5);
    private final CombatWeapon weapon;

    private static DeferredItem<UchigatanaItem> register(String finish, int id) {
        String name = "fox_uchigatana_" + finish;
        var weapon = CombatWeapon.register(id, name, "uchigatana", MOVESET);
        return DimensionBlocks.ITEMS.registerItem(name, p -> new UchigatanaItem(p, weapon));
    }
    public UchigatanaItem(Properties p, CombatWeapon weapon) {
        super(p.sword(ToolMaterial.IRON, 3, -2.2f)
                .delayedComponent(DataComponents.BLOCKS_ATTACKS, context -> new BlocksAttacks(0, 1,
                        List.of(new BlocksAttacks.DamageReduction(90, Optional.empty(), 0, 1)),
                        new BlocksAttacks.ItemDamageFunction(3, 1, 1),
                        Optional.of(context.getOrThrow(DamageTypeTags.BYPASSES_SHIELD)),
                        Optional.of(SoundEvents.SHIELD_BLOCK), Optional.of(SoundEvents.SHIELD_BREAK)))
                .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("tooltip.logos_dimension.uchigatana.combo"),
                        Component.translatable("tooltip.logos_dimension.uchigatana.guard")))));
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
