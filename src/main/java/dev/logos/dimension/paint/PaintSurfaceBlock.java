package dev.logos.dimension.paint;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 漆面：贴在方块表面的薄油漆层，占相邻一格、无碰撞（只是视觉效果）。
 * FACING 表示漆层贴在目标方块的哪一面；COVER 区分整面与上/下半砖。
 * 楼梯、栅栏等非整面方块不支持刷漆（避免悬空色片）。
 */
public class PaintSurfaceBlock extends DirectionalBlock implements EntityBlock {
    public enum Cover implements StringRepresentable {
        FULL("full"),
        HALF_BOTTOM("half_bottom"),
        HALF_TOP("half_top");

        private final String name;

        Cover(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Cover> COVER = EnumProperty.create("cover", Cover.class);

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
        builder.add(FACING, COVER);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** 手动放置时按点击面定向；画刷生成时还会设置按目标方块轮廓计算的 COVER。 */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    /** 按目标方块轮廓选漆面变体：半砖取上/下半，其余整面。 */
    public static Cover coverFor(BlockState target) {
        if (target.getBlock() instanceof SlabBlock) {
            return switch (target.getValue(SlabBlock.TYPE)) {
                case DOUBLE -> Cover.FULL;
                case BOTTOM -> Cover.HALF_BOTTOM;
                case TOP -> Cover.HALF_TOP;
            };
        }
        return Cover.FULL;
    }

    /** 漆面依赖被刷的那一格；目标方块没了就一起消失，不留悬空色片。 */
    @Override
    protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        return !level.getBlockState(pos.relative(state.getValue(FACING))).isAir();
    }

    @Override
    protected void neighborChanged(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
                                   Block neighborBlock, net.minecraft.world.level.redstone.Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, orientation, movedByPiston);
        if (!canSurvive(state, level, pos)) {
            level.removeBlock(pos, false);
        }
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

    /** 漆面由方块实体渲染器用目标方块贴图染色绘制，不走静态模型。 */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}
