package dev.logos.dimension.music.organ;

import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 管风琴演奏台：3 宽 × 2 高（{@link #WIDE}），主块在下排正中，手键盘居中、两侧是放音栓拉钮的侧板，左右对称，
 * 琴凳放在正中那格前面。FACING 是键盘朝向（朝着放置的玩家）。
 * <p>
 * part 的位：1 = 向 FACING 顺时针方向偏一格，2 = 向上一格，4 = 向逆时针方向偏一格。
 * 旧存档里的演奏台是 2 宽（{@code wide=false}，读回来时缺的属性取默认值 false）：主块在左下、只往顺时针方向多一列，照旧能用，
 * 拆掉再放就是 3 宽的。模型挂在主块上；拆任意一块整体拆掉，只有主块掉落。音管与风箱见 {@link OrganStructure}。
 */
public class OrganConsoleBlock extends InstrumentBlock {
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 6);
    public static final BooleanProperty WIDE = BooleanProperty.create("wide");
    /** 3 宽演奏台各格的 part（下排正中、顺时针、逆时针，上排同样）。 */
    private static final int[] WIDE_PARTS = {0, 1, 4, 2, 3, 6};
    private static final int[] NARROW_PARTS = {0, 1, 2, 3};
    private static final java.util.Map<Direction, VoxelShape> LOWER = Shapes.rotateHorizontal(
        Shapes.or(Block.box(0, 0, 4, 16, 16, 16), Block.box(0, 0, 0, 16, 2, 4)));
    private static final java.util.Map<Direction, VoxelShape> UPPER = Shapes.rotateHorizontal(Block.box(0, 0, 7, 16, 16, 16));

    public OrganConsoleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, 0).setValue(WIDE, false));
    }

    @Override
    public InstrumentKind kind() {
        return InstrumentKind.PIPE_ORGAN;
    }

    @Override
    public boolean isMain(BlockState state) {
        return state.getValue(PART) == 0;
    }

    /** 放下时的样子：3 宽、键盘朝 facing（测试、场景直接放方块时用）。 */
    public BlockState placed(Direction facing) {
        return defaultBlockState().setValue(FACING, facing).setValue(PART, 0).setValue(WIDE, true);
    }

    private static int[] parts(BlockState state) {
        return state.getValue(WIDE) ? WIDE_PARTS : NARROW_PARTS;
    }

    private static BlockPos offset(BlockPos main, Direction facing, int part) {
        BlockPos p = main;
        if ((part & 1) != 0) p = p.relative(facing.getClockWise());
        if ((part & 4) != 0) p = p.relative(facing.getCounterClockWise());
        if ((part & 2) != 0) p = p.above();
        return p;
    }

    @Override
    public BlockPos mainPos(BlockState state, BlockPos pos) {
        int part = state.getValue(PART);
        Direction facing = state.getValue(FACING);
        BlockPos p = pos;
        if ((part & 1) != 0) p = p.relative(facing.getCounterClockWise());
        if ((part & 4) != 0) p = p.relative(facing.getClockWise());
        if ((part & 2) != 0) p = p.below();
        return p;
    }

    @Override
    public Iterable<BlockPos> cells(BlockState mainState, BlockPos main) {
        Direction facing = mainState.getValue(FACING);
        List<BlockPos> cells = new ArrayList<>(6);
        for (int part : parts(mainState)) cells.add(offset(main, facing, part));
        return cells;
    }

    @Override
    protected Vec3 centerOf(BlockState mainState, BlockPos main) {
        if (mainState.getValue(WIDE)) return Vec3.atCenterOf(main).add(0, 0.5, 0);
        Direction facing = mainState.getValue(FACING);
        return Vec3.atCenterOf(main).add(Vec3.atLowerCornerOf(facing.getClockWise().getUnitVec3i()).scale(0.5)).add(0, 0.5, 0);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return ((state.getValue(PART) & 2) == 0 ? LOWER : UPPER).get(state.getValue(FACING));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos main = context.getClickedPos();
        if (main.getY() >= level.getMaxY()) return null;
        for (int part : WIDE_PARTS) {
            if (part == 0) continue;
            BlockPos p = offset(main, facing, part);
            if (!level.getBlockState(p).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(p)) return null;
        }
        return placed(facing);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        Direction facing = state.getValue(FACING);
        for (int part : parts(state)) {
            if (part != 0) level.setBlock(offset(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
        }
    }

    /** 这一格朝 direction 那边挨着的同一台演奏台的格子应是哪个 part；那边是演奏台的边时为 -1。 */
    private static int sibling(BlockState state, Direction direction) {
        Direction facing = state.getValue(FACING);
        int part = state.getValue(PART);
        if (direction == facing.getClockWise()) {
            if ((part & 1) != 0) return -1;
            return (part & 4) != 0 ? part & ~4 : part | 1;
        }
        if (direction == facing.getCounterClockWise()) {
            if ((part & 4) != 0) return -1;
            if ((part & 1) != 0) return part & ~1;
            return state.getValue(WIDE) ? part | 4 : -1;
        }
        if (direction == Direction.UP) return (part & 2) == 0 ? part | 2 : -1;
        if (direction == Direction.DOWN) return (part & 2) != 0 ? part & ~2 : -1;
        return -1;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        int sibling = sibling(state, direction);
        if (sibling >= 0 && !(neighborState.is(this) && neighborState.getValue(PART) == sibling
                && neighborState.getValue(FACING) == state.getValue(FACING) && neighborState.getValue(WIDE) == state.getValue(WIDE))) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.isCreative() && !isMain(state)) {
            BlockPos main = mainPos(state, pos);
            BlockState mainState = level.getBlockState(main);
            if (mainState.is(this) && isMain(mainState)) {
                level.setBlock(main, Blocks.AIR.defaultBlockState(), Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_ALL);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, WIDE);
    }
}
