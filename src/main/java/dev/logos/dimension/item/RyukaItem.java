package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.ryuka.Ryuka;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 流火：完全由魔力凝成的打刀（docs/RYUKA.md）。左键五段连斩，右键按住立即格挡，格挡开始 0.5 秒内被击中弹刀，随后左键突刺。
 * 不造成近战伤害，每一刀都换成奥秘伤害（{@link Ryuka#strike}），并穿过盾牌；拿着每秒耗 {@link Ryuka#UPKEEP} 点魔力，
 * 每挥一刀耗 {@link Ryuka#SWING_COST} 点。没有耐久，没有刀鞘：拔刀、收刀是刀身凝聚、散成花瓣。
 * 格挡和黑钢长剑一样是魔力护盾（挡下的扣魔力，魔法也挡）；斧头、监守者这类破盾的攻击，弹刀不额外花魔力，
 * 普通格挡另付 {@link Ryuka#BREAK_COST} × 破盾秒数，付不起才破防（{@link Ryuka#guarded}）。
 */
public final class RyukaItem extends Item implements CombatWeapon.Holder {
    public static final CombatWeapon WEAPON = CombatWeapon.register(30, "ryuka", "ryuka", LogosDimension.id("ryuka")).withoutSheath();
    public static final DeferredItem<RyukaItem> ITEM = DimensionBlocks.ITEMS.registerItem("ryuka", RyukaItem::new);

    public RyukaItem(Properties p) {
        super(p.stacksTo(1).rarity(Rarity.EPIC)
                // 破盾倍率 0：斧头、监守者不按原版直接破防，由 Ryuka.guarded 看魔力决定（见 manaGuarded）
                .delayedComponent(DataComponents.BLOCKS_ATTACKS, context -> new BlocksAttacks(0, 0,
                        List.of(new BlocksAttacks.DamageReduction(90, Optional.empty(), 0, 1)),
                        new BlocksAttacks.ItemDamageFunction(3, 1, 1),
                        Optional.of(context.getOrThrow(DamageTypeTags.BYPASSES_SHIELD)),
                        Optional.of(net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.AMETHYST_BLOCK_HIT)), Optional.of(SoundEvents.SHIELD_BREAK)))
                .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("tooltip.logos_dimension.ryuka.mana"),
                        Component.translatable("tooltip.logos_dimension.ryuka.combo"),
                        Component.translatable("tooltip.logos_dimension.ryuka.guard"),
                        Component.translatable("tooltip.logos_dimension.ryuka.ward"),
                        Component.translatable("tooltip.logos_dimension.ryuka.marks"),
                        Component.translatable("tooltip.logos_dimension.ryuka.stance"),
                        Component.translatable("tooltip.logos_dimension.ryuka.blade")))));
    }

    public static void init() {}

    @Override public CombatWeapon combatWeapon() { return WEAPON; }

    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || Ryuka.faded(player.getItemInHand(hand))) return InteractionResult.FAIL;
        if (player instanceof ServerPlayer server && !LongswordCombat.charge(server)) return InteractionResult.FAIL;
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (user instanceof ServerPlayer player) LongswordCombat.release(player);
        return true;
    }

    @Override public boolean allowsAttack(ServerPlayer player, String move) { return Ryuka.canSwing(player); }

    @Override public void combatTick(ServerPlayer player, String move) {
        Ryuka.upkeep(player, player.getMainHandItem());
        dev.logos.dimension.ryuka.RyukaDash.tick(player, move);
    }

    @Override public boolean swing(ServerPlayer player, Moveset.Move move) { return Ryuka.swing(player, move); }

    /** 流火的格挡一直是魔力护盾：挡下的扣魔力，魔法和法术体也挡（{@link dev.logos.dimension.longsword.ManaGuard}）。 */
    @Override public boolean manaGuard(ServerPlayer player, String move) { return true; }

    @Override public void manaGuarded(ServerPlayer player, net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent event) { Ryuka.guarded(player, event); }

    @Override public boolean strike(ServerPlayer player, Entity target, Moveset.Move move) {
        if (target instanceof LivingEntity living) {
            Ryuka.strike(player, living, Ryuka.DAMAGE * move.damage());
            // 突进后的上挑：缠身着的处决，其余挑飞
            if (dev.logos.dimension.ryuka.RyukaDash.RISE_EFFECT.equals(move.effect())) dev.logos.dimension.ryuka.RyukaExecution.rise(player, living);
        }
        return true;
    }
}
