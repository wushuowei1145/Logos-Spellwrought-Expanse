package dev.logos.dimension.corruption.mob;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.level.Level;

/** 污秽史莱姆：原版史莱姆，分裂出来的还是污秽史莱姆；碰到时附带腐败伤害（在 {@link CorruptionMobs} 里加）。 */
public class FilthSlime extends Slime {
    public FilthSlime(EntityType<? extends Slime> type, Level level) {
        super(type, level);
    }
}
