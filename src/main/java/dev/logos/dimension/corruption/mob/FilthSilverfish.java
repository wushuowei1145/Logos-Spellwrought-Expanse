package dev.logos.dimension.corruption.mob;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.level.Level;

/** 污秽蠹虫：原版蠹虫，咬人时附带腐败伤害（在 {@link CorruptionMobs} 里加）。 */
public class FilthSilverfish extends Silverfish {
    public FilthSilverfish(EntityType<? extends Silverfish> type, Level level) {
        super(type, level);
    }
}
