package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.jinghong.SwordArray;
import dev.logos.dimension.lingxiao.FlightRules;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 惊鸿：照凌霄的规格做的轻剑，网格与贴图由 {@code tools/jinghong_sword.py} 生成。持剑时身后扇形浮着五把同样的剑（剑阵，
 * {@link SwordArray}），所以精度比凌霄低。左键是凌霄的四式连招，身后的剑会追击；右键横剑格挡，开头 0.3 秒弹剑；
 * 格挡中按左键掷剑，激活的剑协同出手，五把都激活时万剑归宗（docs/JINGHONG.md）。数值同凌霄：铁剑伤害和耐久，铁剑一点五倍攻速。
 */
public final class JinghongSwordItem extends Item implements CombatWeapon.Holder {
    public static final CombatWeapon WEAPON = CombatWeapon.register(40, "jinghong_sword", "lingxiao", LogosDimension.id("jinghong")).withDiamondHitbox();
    public static final DeferredItem<JinghongSwordItem> ITEM = DimensionBlocks.ITEMS.registerItem("jinghong_sword", JinghongSwordItem::new);
    /** 掷剑招式名：它的接触时刻就是剑出手（动作的 release 标记）。 */
    public static final String THROW = "throw";

    public JinghongSwordItem(Properties properties) {
        super(properties.sword(ToolMaterial.IRON, 3, FlightRules.ATTACK_SPEED - 4)
                // 原版格挡组件，只把盾的举盾延迟改成 0（同打刀）；减伤、击退和耐久走原版。
                .delayedComponent(DataComponents.BLOCKS_ATTACKS, context -> new BlocksAttacks(0, 1,
                        List.of(new BlocksAttacks.DamageReduction(90, Optional.empty(), 0, 1)),
                        new BlocksAttacks.ItemDamageFunction(3, 1, 1),
                        Optional.of(context.getOrThrow(DamageTypeTags.BYPASSES_SHIELD)),
                        Optional.of(SoundEvents.SHIELD_BLOCK), Optional.of(SoundEvents.SHIELD_BREAK)))
                .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("tooltip.logos_dimension.jinghong.attack"),
                        Component.translatable("tooltip.logos_dimension.jinghong.guard"),
                        Component.translatable("tooltip.logos_dimension.jinghong.throw")))));
    }
    public static void init() {}
    @Override public CombatWeapon combatWeapon() { return WEAPON; }
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
    /** 掷剑出手：不走近战判定，交给剑阵。 */
    @Override public boolean swing(ServerPlayer player, Moveset.Move move) {
        if (!THROW.equals(move.name())) return true;
        SwordArray.throwSword(player);
        return false;
    }
    /** 连招砍中：身后的剑追击这一刀的第一个目标。 */
    @Override public void hit(ServerPlayer player, Entity target, Moveset.Move move, boolean first) {
        if (first && move.name().startsWith("attack_")) SwordArray.followUp(player, target);
    }
    @Override public void blocked(ServerPlayer player, DamageSource source, boolean parried) { SwordArray.blocked(player, source, parried); }
}
