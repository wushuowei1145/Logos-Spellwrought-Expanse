package dev.logos.dimension.corruption.mob;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.level.Level;

/** 污秽骷髅：原版骷髅；站在腐败上白天不燃烧。箭射中时附带的腐败伤害在 {@link CorruptionMobs} 里加。 */
public class FilthSkeleton extends Skeleton {
    public FilthSkeleton(EntityType<? extends Skeleton> type, Level level) {
        super(type, level);
    }

    @Override
    public float getLightLevelDependentMagicValue() {
        return CorruptionMobs.onCorruption(level(), blockPosition()) ? 0 : super.getLightLevelDependentMagicValue();
    }
}
