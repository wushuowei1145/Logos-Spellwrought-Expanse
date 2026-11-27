package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.longsword.LongswordCombat;
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
import net.minecraft.world.entity.Entity;
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
 * 炽焰长剑（Crimson Flame Longsword，docs/CRIMSON_FLAME_LONGSWORD.md）：大开大合的龙族大剑连招（横扫 → 反劈 → 升龙挑空 → 坠星裁决）；
 * 右键按住立即托脊横防，0.35 秒内挡下攻击会触发逆焰弹反，随后左键顺势打出破军龙牙突刺。
 * 伤害全是龙炎（docs/DRAGONFIRE.md）：每一剑按攻击力换成同样点数的龙炎，战技的游脉和落点也是龙炎。
 */
public final class CrimsonFlameLongswordItem extends Item implements CombatWeapon.Holder {
    private static final Identifier MOVESET = LogosDimension.id("crimson_flame_longsword");
    private static final int COMBAT_ID = 60;
    private static final double REACH = 0.5;
    private final CombatWeapon weapon;

    public static final DeferredItem<CrimsonFlameLongswordItem> ITEM = register();

    private static DeferredItem<CrimsonFlameLongswordItem> register() {
        var weapon = CombatWeapon.register(COMBAT_ID, "crimson_flame_longsword", "crimson_flame_longsword", MOVESET)
                .withCone(100)
                .withoutSheath();
        return DimensionBlocks.ITEMS.registerItem("crimson_flame_longsword", p -> new CrimsonFlameLongswordItem(p, weapon));
    }

    public CrimsonFlameLongswordItem(Properties p, CombatWeapon weapon) {
        super(p.sword(ToolMaterial.DIAMOND, 4, -2.5f)
                .attributes(ItemAttributeModifiers.builder()
                        .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID,
                                4 + ToolMaterial.DIAMOND.attackDamageBonus(), AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, -2.5,
                                AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ENTITY_INTERACTION_RANGE, new AttributeModifier(LogosDimension.id("crimson_flame_longsword_reach"),
                                REACH, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .build())
                .delayedComponent(DataComponents.BLOCKS_ATTACKS, context -> new BlocksAttacks(0, 1,
                        List.of(new BlocksAttacks.DamageReduction(90, Optional.empty(), 0, 1)),
                        new BlocksAttacks.ItemDamageFunction(3, 1, 1),
                        Optional.of(context.getOrThrow(DamageTypeTags.BYPASSES_SHIELD)),
                        Optional.of(SoundEvents.SHIELD_BLOCK), Optional.of(SoundEvents.SHIELD_BREAK)))
                .component(DataComponents.LORE, new ItemLore(List.of(
                        Component.translatable("tooltip.logos_dimension.crimson_flame_longsword.dragonfire"),
                        Component.translatable("tooltip.logos_dimension.crimson_flame_longsword.combo"),
                        Component.translatable("tooltip.logos_dimension.crimson_flame_longsword.guard"),
                        Component.translatable("tooltip.logos_dimension.crimson_flame_longsword.skills")))));
        this.weapon = weapon;
    }

    public static void init() {}

    @Override
    public CombatWeapon combatWeapon() { return weapon; }

    @Override
    public boolean allows(ServerPlayer player, String requirement) {
        return CrimsonFlameSkills.allows(player, requirement);
    }

    @Override
    public void combatTick(ServerPlayer player, String move) {
        CrimsonFlameSkills.combatTick(player, move);
    }

    @Override
    public boolean swing(ServerPlayer player, Moveset.Move move) {
        CrimsonFlameSkills.onSwing(player, move);
        return true;
    }

    /** 每一剑都换成龙炎（{@link CrimsonFlameSkills#strike}），不走原版近战。 */
    @Override
    public boolean strike(ServerPlayer player, Entity target, Moveset.Move move) {
        if (target instanceof LivingEntity living) CrimsonFlameSkills.strike(player, living, move);
        return true;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        if (player instanceof ServerPlayer server && !LongswordCombat.charge(server)) return InteractionResult.FAIL;
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (user instanceof ServerPlayer player) LongswordCombat.release(player);
        return true;
    }
}
