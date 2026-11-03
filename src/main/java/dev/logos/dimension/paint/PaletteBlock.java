package dev.logos.dimension.paint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** 可放置的调色盘：盘面显示当前颜色，画刷右键它蘸色。颜色复用漆面的方块实体。 */
public class PaletteBlock extends Block implements EntityBlock {
    /** 贴地的薄板（含两侧把手与颜料池），与模型一致；不能当整格，否则会剔掉下方方块的顶面。 */
    private static final VoxelShape BOARD = Block.box(0, 0, 2, 16, 1.2, 13);

    public PaletteBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return BOARD;
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
