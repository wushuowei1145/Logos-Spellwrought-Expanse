package dev.logos.dimension.paint;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 漆面：贴在方块表面的薄油漆层，占相邻一格、无碰撞（只是视觉效果）。
 * 朝向 FACING 表示贴在目标方块的哪个面上；颜色存在方块实体里，客户端做半透明 tint。
 */
public class PaintSurfaceBlock extends DirectionalBlock implements EntityBlock {
    private static final VoxelShape UP = Block.box(0, 0, 0, 16, 0.25, 16);
    private static final VoxelShape DOWN = Block.box(0, 15.75, 0, 16, 16, 16);
    private static final VoxelShape NORTH = Block.box(0, 0, 0, 16, 16, 0.25);
    private static final VoxelShape SOUTH = Block.box(0, 0, 15.75, 16, 16, 16);
    private static final VoxelShape WEST = Block.box(0, 0, 0, 0.25, 16, 16);
    private static final VoxelShape EAST = Block.box(15.75, 0, 0, 16, 16, 16);

    public PaintSurfaceBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** 手动放置时按点击面定向；画刷生成时也会显式设置。 */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return switch (state.getValue(FACING)) {
            case UP -> UP;
            case DOWN -> DOWN;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PaintSurfaceBlockEntity(pos, state);
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return simpleCodec(PaintSurfaceBlock::new);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
