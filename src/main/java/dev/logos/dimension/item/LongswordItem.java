package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.longsword.LongswordCombat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;

/** 共用钻石剑的伤害、耐久与攻速；右键进入双手竖剑蓄力。 */
public final class LongswordItem extends Item implements CombatWeapon.Holder {
    private static final net.minecraft.resources.Identifier MOVESET = LogosDimension.id("longsword");
    private static final CombatWeapon DAWNVOW_WEAPON = CombatWeapon.register(0, "dawnvow", "longsword", MOVESET);
    private static final CombatWeapon BLACKSTEEL_WEAPON = CombatWeapon.register(1, "blacksteel_longsword", "longsword", MOVESET);
    public static final DeferredItem<LongswordItem> DAWNVOW = DimensionBlocks.ITEMS.registerItem("dawnvow", p -> new LongswordItem(p, DAWNVOW_WEAPON));
    public static final DeferredItem<LongswordItem> BLACKSTEEL = DimensionBlocks.ITEMS.registerItem("blacksteel_longsword", p -> new LongswordItem(p, BLACKSTEEL_WEAPON));
    private final CombatWeapon weapon;
    public LongswordItem(Properties p, CombatWeapon weapon) {
        super(p.sword(ToolMaterial.DIAMOND, 3, -2.4f).component(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                        net.minecraft.network.chat.Component.translatable("tooltip.logos_dimension.longsword.combo"),
                        net.minecraft.network.chat.Component.translatable("tooltip.logos_dimension.longsword.charge")))));
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
    @Override public int getUseDuration(ItemStack stack, LivingEntity user) { return 72000; }
    @Override public ItemUseAnimation getUseAnimation(ItemStack stack) { return ItemUseAnimation.NONE; }
    @Override public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (user instanceof ServerPlayer player) LongswordCombat.release(player);
        return true;
    }
}
