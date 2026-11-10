package dev.logos.dimension.music.organ;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 锡音管、木音管：往上叠自动接成一根长管——单独一格是短管（管口 + 收口），叠起来时最下一格画管口、
 * 最上一格收口、中间是管身。管口朝 FACING（朝着放置的玩家）；叠在同种音管上时沿用下面那格的朝向。
 * 立在风箱上才算进管风琴（{@link OrganStructure}），单独放着就是装饰。
 */
public class OrganPipeBlock extends Block {
    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<Shape> SHAPE = EnumProperty.create("shape", Shape.class);

    public enum Shape implements StringRepresentable {
        SINGLE("single"), BOTTOM("bottom"), MIDDLE("middle"), TOP("top");

        private final String name;

        Shape(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    private final VoxelShape shape;

    public OrganPipeBlock(Properties properties, double halfWidth) {
        super(properties);
        double a = 8 - halfWidth, b = 8 + halfWidth;
        this.shape = Block.box(a, 0, a, b, 16, b);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SHAPE, Shape.SINGLE));
    }

    /** 按上下邻格重新算形状（放置与邻格变化时用；测试也直接调）。 */
    public BlockState withShape(BlockState state, LevelReader level, BlockPos pos) {
        return state.setValue(SHAPE, shapeAt(level, pos));
    }

    private Shape shapeAt(LevelReader level, BlockPos pos) {
        boolean below = level.getBlockState(pos.below()).is(this);
        boolean above = level.getBlockState(pos.above()).is(this);
        return below ? (above ? Shape.MIDDLE : Shape.TOP) : (above ? Shape.BOTTOM : Shape.SINGLE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState below = context.getLevel().getBlockState(context.getClickedPos().below());
        Direction facing = below.is(this) ? below.getValue(FACING) : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing).setValue(SHAPE, shapeAt(context.getLevel(), context.getClickedPos()));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (direction.getAxis() == Direction.Axis.Y) return state.setValue(SHAPE, shapeAt(level, pos));
        return state;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAPE);
    }
}
