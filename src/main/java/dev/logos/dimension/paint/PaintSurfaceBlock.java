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
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 漆面：贴在方块表面的薄油漆层，占相邻一格、无碰撞（只是视觉效果）。
 * FACING 表示漆层贴在目标方块的哪一面，COVER 表示按目标方块轮廓选出的形状变体
 * （整面、上下半砖、楼梯正面/侧面/上表面），由画刷在刷漆时按目标方块计算。
 */
public class PaintSurfaceBlock extends DirectionalBlock implements EntityBlock {
    /** 漆层形状：与 tools/paint_surface_models.py 生成的变体一一对应。 */
    public enum Cover implements StringRepresentable {
        FULL("full"),
        HALF_BOTTOM("half_bottom"),
        HALF_TOP("half_top"),
        FRONT_STEP("front_step"),
        FRONT_STEP_UD("front_step_ud"),
        SIDE_LEFT("side_left"),
        SIDE_RIGHT("side_right"),
        SIDE_LEFT_UD("side_left_ud"),
        SIDE_RIGHT_UD("side_right_ud"),
        STRIP_Y0("strip_y0"),
        STRIP_Y1("strip_y1"),
        STRIP_X0("strip_x0"),
        STRIP_X1("strip_x1");

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
        if (mirror != Mirror.NONE) {
            state = switch (state.getValue(COVER)) {
                case SIDE_LEFT -> state.setValue(COVER, Cover.SIDE_RIGHT);
                case SIDE_RIGHT -> state.setValue(COVER, Cover.SIDE_LEFT);
                case SIDE_LEFT_UD -> state.setValue(COVER, Cover.SIDE_RIGHT_UD);
                case SIDE_RIGHT_UD -> state.setValue(COVER, Cover.SIDE_LEFT_UD);
                case STRIP_X0 -> state.setValue(COVER, Cover.STRIP_X1);
                case STRIP_X1 -> state.setValue(COVER, Cover.STRIP_X0);
                default -> state;
            };
        }
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** 手动放置时按点击面定向；画刷生成时还会设置按目标方块轮廓计算的 COVER。 */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    /**
     * 按目标方块轮廓决定漆面变体：半砖取上下半，楼梯按被刷的是正面/背面/侧面/上表面
     * 选阶梯 L 形或半条，其余方块整面覆盖。
     */
    public static Cover coverFor(BlockState target, Direction face) {
        Direction surfaceFacing = face.getOpposite();
        if (target.getBlock() instanceof SlabBlock) {
            return switch (target.getValue(SlabBlock.TYPE)) {
                case DOUBLE -> Cover.FULL;
                case BOTTOM -> Cover.HALF_BOTTOM;
                case TOP -> Cover.HALF_TOP;
            };
        }
        if (target.getBlock() instanceof StairBlock) {
            Direction stairFacing = target.getValue(StairBlock.FACING);
            Direction high = stairFacing.getOpposite();
            boolean upsideDown = target.getValue(StairBlock.HALF) == Half.TOP;
            if (face == Direction.UP) {
                // 正常楼梯的上表面只有高侧台面在本格；倒置楼梯上表面是整面
                return upsideDown ? Cover.FULL : strip(high, surfaceFacing);
            }
            if (face == Direction.DOWN) {
                // 正常楼梯底面整面；倒置楼梯只有凸出的那一半在本格
                return upsideDown ? strip(high, surfaceFacing) : Cover.FULL;
            }
            if (face == stairFacing) {
                return upsideDown ? Cover.FRONT_STEP_UD : Cover.FRONT_STEP;
            }
            if (face == stairFacing.getOpposite()) {
                return Cover.FULL;
            }
            // 侧面：上半部分要落在高侧；局部 +x 方向由漆面方块自己的朝向决定
            boolean rightSide = localRight(surfaceFacing) == high;
            if (upsideDown) {
                return rightSide ? Cover.SIDE_RIGHT_UD : Cover.SIDE_LEFT_UD;
            }
            return rightSide ? Cover.SIDE_RIGHT : Cover.SIDE_LEFT;
        }
        return Cover.FULL;
    }

    /** 漆面模型局部 +x 在世界中的方向（与 blockstate 的旋转约定一致）。 */
    private static Direction localRight(Direction surfaceFacing) {
        return switch (surfaceFacing) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case EAST -> Direction.SOUTH;
            case WEST -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    /**
     * 上/下表面只盖高侧一半时选半条：漆面朝下（x=90）时局部 +y 指南，朝上（x=270）时指北。
     */
    private static Cover strip(Direction high, Direction surfaceFacing) {
        boolean localUpIsNorth = surfaceFacing == Direction.UP;
        return switch (high) {
            case NORTH -> localUpIsNorth ? Cover.STRIP_Y1 : Cover.STRIP_Y0;
            case SOUTH -> localUpIsNorth ? Cover.STRIP_Y0 : Cover.STRIP_Y1;
            case EAST -> Cover.STRIP_X1;
            default -> Cover.STRIP_X0;
        };
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
