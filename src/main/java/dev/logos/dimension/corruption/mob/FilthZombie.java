package dev.logos.dimension.corruption.mob;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.Level;

/** 污秽僵尸：原版僵尸，不会在水里变成溺尸；站在腐败上白天不燃烧。攻击附带的腐败伤害在 {@link CorruptionMobs} 里加。 */
public class FilthZombie extends Zombie {
    public FilthZombie(EntityType<? extends Zombie> type, Level level) {
        super(type, level);
    }

    @Override
    protected boolean convertsInWater() {
        return false;
    }

    /** 原版按这个亮度判断要不要在阳光下燃烧：站在腐败上时当成黑的。 */
    @Override
    public float getLightLevelDependentMagicValue() {
        return CorruptionMobs.onCorruption(level(), blockPosition()) ? 0 : super.getLightLevelDependentMagicValue();
    }
}
