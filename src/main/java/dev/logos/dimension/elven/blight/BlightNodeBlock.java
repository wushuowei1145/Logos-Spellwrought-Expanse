package dev.logos.dimension.elven.blight;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.corruption.CorruptionCoreBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** 原版单方块 Feature 放置的区域核心；沿用普通核心的网络、挖掘、运镜与消散。 */
public class BlightNodeBlock extends CorruptionCoreBlock {
    public static final MapCodec<BlightNodeBlock> CODEC = simpleCodec(BlightNodeBlock::new);
    public BlightNodeBlock(Properties p) { super(p); }
    @Override protected MapCodec<BlightNodeBlock> codec() { return CODEC; }
    @Override protected void onPlace(BlockState s, Level level, BlockPos pos, BlockState old, boolean piston) {
        super.onPlace(s, level, pos, old, piston);
        if (level instanceof ServerLevel world && !old.is(this)) BlightReclamation.get(world).register(world, pos);
    }
    @Override public BlockEntity newBlockEntity(BlockPos p, BlockState s) { return new BlightNode(p, s); }
    @Override public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> t) {
        return t != ElvenBlight.NODE_ENTITY.get() ? null : (world, pos, state, be) -> {
            if (world.isClientSide()) ((BlightNode) be).clientTick();
        };
    }
    @Override protected void affectNeighborsAfterRemoval(BlockState s, ServerLevel level, BlockPos pos, boolean piston) {
        BlightReclamation.get(level).cleared(level, pos);
        super.affectNeighborsAfterRemoval(s, level, pos, piston);
    }
}
