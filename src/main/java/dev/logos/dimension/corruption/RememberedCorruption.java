package dev.logos.dimension.corruption;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 自己在方块状态里记着原材质的污秽（例如生成的腐败森林）。网络不再另记一份原方块，
 * 消散时直接从方块状态取回，存档只多一个编号。
 */
public interface RememberedCorruption {
    /** {@code state} 被腐败之前是什么。 */
    BlockState remembered(BlockState state);
}
