package dev.logos.dimension.firearm;

import dev.logos.dimension.DimensionBlocks;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 空弹头：铜弹壳、火药，弹头是一枚紫水晶，没有金属弹丸，打出去只有火光和一声响，不伤人。
 * 它是纯术式弹的弹体：以后在铸弹台上刻进术式，就是一发不带物理伤害、能注入更多魔力的术式弹（docs/FIREARMS.md 第 5 节）。
 */
public final class BlankRoundItem extends Item implements Ammo.Item {
    public static final DeferredItem<BlankRoundItem> ITEM = DimensionBlocks.ITEMS.registerItem("blank_round", BlankRoundItem::new);
    private static final Ammo AMMO = BlankRoundItem::fire;

    public BlankRoundItem(Properties properties) { super(properties.stacksTo(64)); }
    public static void init() {}
    @Override public Ammo ammo() { return AMMO; }

    private static Ammo.ShotResult fire(Ammo.Shot shot) {
        Vec3 at = shot.shooter().getEyePosition();
        shot.level().playSound(null, at.x, at.y, at.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, .7f, 1.3f);
        return Ammo.ShotResult.NONE;
    }
}
