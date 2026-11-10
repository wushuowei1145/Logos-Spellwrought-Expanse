package dev.logos.dimension.music;

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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 星辰花三角钢琴：2×2 占地。FACING 是键盘朝向（朝着放置的玩家）；主块（part 0）在键盘一侧，
 * part 的第 1 位表示向「侧边」（FACING 顺时针方向）偏一格，第 2 位表示向「琴尾」（FACING 反方向）偏一格。
 * 模型只挂在主块上，按 FACING 旋转后正好盖住四格；拆任意一块整体拆掉，只有主块掉落。
 */
public class GrandPianoBlock extends InstrumentBlock {
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 3);
    /** 琴身（键盘以上）与琴腿，各块一样：玩家不能穿过钢琴，但可以把东西放在琴盖上。 */
    private static final VoxelShape SHAPE = Shapes.or(Block.box(0, 9, 0, 16, 18, 16), Block.box(2, 0, 2, 14, 9, 14));

    public GrandPianoBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, 0));
    }

    @Override
    public InstrumentKind kind() {
        return InstrumentKind.GRAND_PIANO;
    }

    @Override
    public boolean isMain(BlockState state) {
        return state.getValue(PART) == 0;
    }

    private static Direction side(Direction facing) {
        return facing.getClockWise();
    }

    private static Direction back(Direction facing) {
        return facing.getOpposite();
    }

    private static BlockPos offset(BlockPos main, Direction facing, int part) {
        BlockPos p = main;
        if ((part & 1) != 0) p = p.relative(side(facing));
        if ((part & 2) != 0) p = p.relative(back(facing));
        return p;
    }

    @Override
    public BlockPos mainPos(BlockState state, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        int part = state.getValue(PART);
        BlockPos p = pos;
        if ((part & 1) != 0) p = p.relative(side(facing).getOpposite());
        if ((part & 2) != 0) p = p.relative(back(facing).getOpposite());
        return p;
    }

    @Override
    public Iterable<BlockPos> cells(BlockState mainState, BlockPos main) {
        Direction facing = mainState.getValue(FACING);
        return List.of(main, offset(main, facing, 1), offset(main, facing, 2), offset(main, facing, 3));
    }

    @Override
    protected Vec3 centerOf(BlockState mainState, BlockPos main) {
        Direction facing = mainState.getValue(FACING);
        Vec3 c = Vec3.atCenterOf(main).add(0, 0.2, 0);
        Vec3 s = Vec3.atLowerCornerOf(side(facing).getUnitVec3i()).scale(0.5);
        Vec3 b = Vec3.atLowerCornerOf(back(facing).getUnitVec3i()).scale(0.5);
        return c.add(s).add(b);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Level level = context.getLevel();
        BlockPos main = context.getClickedPos();
        for (int part = 1; part < 4; part++) {
            BlockPos p = offset(main, facing, part);
            if (!level.getBlockState(p).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(p)) return null;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, 0);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
        Direction facing = state.getValue(FACING);
        for (int part = 1; part < 4; part++) {
            level.setBlock(offset(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        Direction facing = state.getValue(FACING);
        int part = state.getValue(PART);
        int sibling = -1;
        if (direction == side(facing) && (part & 1) == 0) sibling = part | 1;
        else if (direction == side(facing).getOpposite() && (part & 1) != 0) sibling = part & ~1;
        else if (direction == back(facing) && (part & 2) == 0) sibling = part | 2;
        else if (direction == back(facing).getOpposite() && (part & 2) != 0) sibling = part & ~2;
        if (sibling >= 0 && !(neighborState.is(this) && neighborState.getValue(PART) == sibling
                && neighborState.getValue(FACING) == facing)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // 创造模式拆附属块时，先不掉落地拆掉主块，免得主块被连带拆掉时掉出钢琴
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
        builder.add(FACING, PART);
    }
}
