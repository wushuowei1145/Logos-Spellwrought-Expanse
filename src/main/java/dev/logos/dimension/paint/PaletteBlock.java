package dev.logos.dimension.paint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** 可放置的调色盘：盘面显示当前颜色，画刷右键它蘸色。颜色复用漆面的方块实体。 */
public class PaletteBlock extends Block implements EntityBlock {
    public PaletteBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PaintSurfaceBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
