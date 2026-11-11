package dev.logos.dimension.music;

import java.util.List;
import java.util.Map;
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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 一格宽、两格深的乐器（大键琴）：主块在键盘一侧，琴尾向 FACING 的反方向伸出一格。
 * 模型挂在主块上；拆任意一块整体拆掉，只有主块掉落。碰撞形状按 FACING=north 给出，前后两格各一个。
 */
public class LongInstrumentBlock extends InstrumentBlock {
    /** 是不是琴尾那一格。 */
    public static final BooleanProperty TAIL = BooleanProperty.create("tail");
    private final InstrumentKind kind;
    private final Map<Direction, VoxelShape> front;
    private final Map<Direction, VoxelShape> back;

    public LongInstrumentBlock(Properties properties, InstrumentKind kind, VoxelShape northFront, VoxelShape northBack) {
        super(properties);
        this.kind = kind;
        this.front = Shapes.rotateHorizontal(northFront);
        this.back = Shapes.rotateHorizontal(northBack);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TAIL, false));
    }

    @Override
    public InstrumentKind kind() {
        return kind;
    }

    @Override
    public boolean isMain(BlockState state) {
        return !state.getValue(TAIL);
    }

    @Override
    public BlockPos mainPos(BlockState state, BlockPos pos) {
        return isMain(state) ? pos : pos.relative(state.getValue(FACING));
    }

    @Override
    public Iterable<BlockPos> cells(BlockState mainState, BlockPos main) {
        return List.of(main, main.relative(mainState.getValue(FACING).getOpposite()));
    }

    @Override
    protected Vec3 centerOf(BlockState mainState, BlockPos main) {
        return Vec3.atCenterOf(main).add(Vec3.atLowerCornerOf(mainState.getValue(FACING).getOpposite().getUnitVec3i()).scale(0.5));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (state.getValue(TAIL) ? back : front).get(state.getValue(FACING));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos tail = context.getClickedPos().relative(facing.getOpposite());
        Level level = context.getLevel();
        if (!level.getBlockState(tail).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(tail)) return null;
        return defaultBlockState().setValue(FACING, facing).setValue(TAIL, false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        level.setBlock(pos.relative(state.getValue(FACING).getOpposite()), state.setValue(TAIL, true), Block.UPDATE_ALL);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        Direction facing = state.getValue(FACING);
        boolean tail = state.getValue(TAIL);
        Direction toSibling = tail ? facing : facing.getOpposite();
        if (direction == toSibling && !(neighborState.is(this) && neighborState.getValue(TAIL) != tail
                && neighborState.getValue(FACING) == facing)) {
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
        builder.add(FACING, TAIL);
    }
}
