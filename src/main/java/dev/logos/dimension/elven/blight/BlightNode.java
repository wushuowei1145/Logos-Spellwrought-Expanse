package dev.logos.dimension.elven.blight;

import dev.logos.dimension.corruption.CorruptionCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** 区域归属存于维度 SavedData，核心实体只保留原有客户端动画。 */
public class BlightNode extends CorruptionCoreBlockEntity {
    public BlightNode(BlockPos p, BlockState s) { super(ElvenBlight.NODE_ENTITY.get(), p, s); }
    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel world) BlightReclamation.get(world).register(world, worldPosition);
    }
}
