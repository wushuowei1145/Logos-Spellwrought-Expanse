package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.lingxiao.FlightRules;
import dev.logos.dimension.lingxiao.FlyingSword;
import dev.logos.dimension.lingxiao.LingxiaoFlight;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;

/** 凌霄：铁剑伤害和耐久，铁剑一点五倍攻速；右键起飞，潜行下剑。 */
public final class LingxiaoSwordItem extends Item implements CombatWeapon.Holder {
    public static final CombatWeapon WEAPON = CombatWeapon.register(11, "lingxiao_sword", "lingxiao", LogosDimension.id("lingxiao")).withDiamondHitbox();
    public static final DeferredItem<LingxiaoSwordItem> ITEM = DimensionBlocks.ITEMS.registerItem("lingxiao_sword", LingxiaoSwordItem::new);
    public LingxiaoSwordItem(Properties properties) {
        super(properties.sword(ToolMaterial.IRON, 3, FlightRules.ATTACK_SPEED - 4).component(DataComponents.LORE,
                new ItemLore(java.util.List.of(Component.translatable("tooltip.logos_dimension.lingxiao.attack"),
                        Component.translatable("tooltip.logos_dimension.lingxiao.flight"),Component.translatable("tooltip.logos_dimension.lingxiao.controls")))));
    }
    public static void init() {}
    @Override public CombatWeapon combatWeapon() { return WEAPON; }
    @Override public boolean allowsAttack(ServerPlayer player, String move) { return !(player.getVehicle() instanceof FlyingSword); }
    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        if (player instanceof ServerPlayer server && !LingxiaoFlight.start(server)) return InteractionResult.FAIL;
        return InteractionResult.CONSUME;
    }
}
