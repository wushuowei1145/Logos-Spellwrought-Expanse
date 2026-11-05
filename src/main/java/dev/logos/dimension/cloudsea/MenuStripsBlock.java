package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 居酒屋的菜单条：一根细木条下垂着三张纸条，写满看着像手写日文、其实谁也读不出来的墨迹。三种纸色。 */
public class MenuStripsBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<MenuStripsBlock> CODEC = simpleCodec(MenuStripsBlock::new);
    public static final IntegerProperty PAPER = IntegerProperty.create("paper", 0, 2);
    private static final VoxelShape SHAPE_NS = Block.box(0, 4, 7, 16, 16, 9);
    private static final VoxelShape SHAPE_EW = Block.box(7, 4, 0, 9, 16, 16);

    public MenuStripsBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PAPER, 0));
    }

    @Override
    public MapCodec<MenuStripsBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PAPER);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_EW : SHAPE_NS;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing).setValue(PAPER, Math.floorMod(context.getClickedPos().asLong(), 3));
    }
}
