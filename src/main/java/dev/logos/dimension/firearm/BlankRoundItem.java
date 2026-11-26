package dev.logos.dimension.firearm;

import dev.logos.dimension.DimensionBlocks;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 空弹头：铜弹壳、火药，弹头是一枚紫水晶，没有金属弹丸，打出去只有火光和一声响，不伤人。
 * 它是纯术式弹的弹体：在铸弹台上刻进术式之后，术式沿视线直射（步枪弹的射程），打中的第一个生物触发 {@code on-hit}，
 * 什么都没打中就看落在方块上还是飞到尽头；没有物理伤害，能注入的魔力是同档的两倍（docs/FIREARMS.md 第 5、9 节）。
 */
public final class BlankRoundItem extends Item implements Ammo.Item {
    public static final DeferredItem<BlankRoundItem> ITEM = DimensionBlocks.ITEMS.registerItem("blank_round", BlankRoundItem::new);
    private static final Ammo AMMO = BlankRoundItem::fire;

    public BlankRoundItem(Properties properties) { super(properties.stacksTo(64)); }
    public static void init() {}
    @Override public Ammo ammo() { return AMMO; }

    // 刻了术式的空弹头（SpellRounds）：名字带术式名、说明写档位和注入、带附魔光泽。
    @Override public Component getName(ItemStack stack) { return SpellRounds.name(stack, super.getName(stack)); }
    @Override public boolean isFoil(ItemStack stack) { return SpellRounds.etched(stack) || super.isFoil(stack); }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        SpellRounds.tooltip(stack, builder);
    }

    private static Ammo.ShotResult fire(Ammo.Shot shot) {
        var level = shot.level();
        var shooter = shot.shooter();
        Vec3 at = shooter.getEyePosition();
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, .7f, 1.3f);
        var carried = SpellRounds.carry(shot, SpellRounds.BLANK);
        if (carried == null) return Ammo.ShotResult.NONE;
        // 刻着术式：一道术式沿视线直射，碰到的第一个生物就停。
        var from = shot.origin();
        var dir = BuckshotItem.cone(shot.direction(), Rifle.SPREAD * shot.spread(), shooter.getRandom());
        var end = from.add(dir.scale(Rifle.RANGE));
        var block = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        var stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        var first = RifleRoundItem.struck(level, shooter, from, stop).stream().findFirst();
        SpellRounds.strike(carried, level, dir, first.map(h -> List.of(new SpellRounds.Struck(h.entity(), h.at()))).orElse(List.of()),
                block.getType() == HitResult.Type.MISS ? null : block, end);
        return Ammo.ShotResult.NONE;
    }
}
