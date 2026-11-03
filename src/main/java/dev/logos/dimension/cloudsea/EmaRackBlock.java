package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 绘马挂：深木框与小瓦顶，框上本来就挂着一层层旧绘马。
 *
 * <p>像粉色花簇一样可以叠放 1–4 层，每多一层，框上的绘马更密。左右、上下并排放置会连成一个大架子。
 * 手持写好的绘马右键把它挂到指着的位置（最多三块），这三块总挂在最外面一排；右键翻看架上玩家写的绘马，
 * 潜行空手右键取下指着的那一块。旧存档里在铁砧上改过名的绘马仍把名字写在绘马上。
 */
public class EmaRackBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<EmaRackBlock> CODEC = simpleCodec(EmaRackBlock::new);
    public static final IntegerProperty LAYERS = IntegerProperty.create("layers", 1, 4);
    /** 同朝向的绘马挂在左、右、上方相连：只在两端立柱、只在最上一排盖顶，拼成一整个大架子。 */
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    private static final VoxelShape SHAPE_NS = Block.box(0, 0, 5, 16, 16, 11);
    private static final VoxelShape SHAPE_EW = Block.box(5, 0, 0, 11, 16, 16);

    public EmaRackBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LAYERS, 1)
            .setValue(LEFT, false).setValue(RIGHT, false).setValue(UP, false));
    }

    @Override
    public MapCodec<EmaRackBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LAYERS, LEFT, RIGHT, UP);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_EW : SHAPE_NS;
    }

    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        return !context.isSecondaryUseActive() && context.getItemInHand().is(this.asItem()) && state.getValue(LAYERS) < 4
            || super.canBeReplaced(state, context);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState existing = context.getLevel().getBlockState(context.getClickedPos());
        if (existing.is(this)) return existing.setValue(LAYERS, Math.min(4, existing.getValue(LAYERS) + 1));
        BlockState state = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        return connect(state, context.getLevel(), context.getClickedPos());
    }

    private BlockState connect(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        return state
            .setValue(LEFT, joins(level.getBlockState(pos.relative(facing.getCounterClockWise())), facing))
            .setValue(RIGHT, joins(level.getBlockState(pos.relative(facing.getClockWise())), facing))
            .setValue(UP, joins(level.getBlockState(pos.above()), facing));
    }

    private boolean joins(BlockState other, Direction facing) {
        return other.is(this) && other.getValue(FACING) == facing;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        return connect(state, level, pos);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // 空白绘马不挂：转去看架上的绘马，架上空着就落到物品本身，打开书写页
        if (!stack.is(FoxWildsBlocks.EMA.get()) || !EmaItem.isWritten(stack)) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!(level.getBlockEntity(pos) instanceof EmaRackBlockEntity rack)) return InteractionResult.PASS;
        if (!rack.hang(slotAt(state, pos, hit), stack.copyWithCount(1))) return InteractionResult.FAIL;
        if (!level.isClientSide()) {
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.BAMBOO_WOOD_HANGING_SIGN_PLACE, SoundSource.BLOCKS, 0.8f, 1.2f);
        }
        return InteractionResult.SUCCESS;
    }

    /** 右键看架上玩家写的绘马（从指着的那块翻起）；潜行空手右键取下指着的那一块。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof EmaRackBlockEntity rack) || rack.isEmpty()) return InteractionResult.PASS;
        int slot = slotAt(state, pos, hit);
        if (!player.isSecondaryUseActive()) {
            if (level.isClientSide()) dev.logos.dimension.client.EmaScreen.openRack(pos, slot);
            return InteractionResult.SUCCESS;
        }
        ItemStack taken = rack.take(slot);
        if (taken.isEmpty()) return InteractionResult.PASS;
        if (!level.isClientSide()) {
            if (!player.getInventory().add(taken)) player.drop(taken, false);
            level.playSound(null, pos, SoundEvents.BAMBOO_WOOD_HANGING_SIGN_BREAK, SoundSource.BLOCKS, 0.6f, 1.3f);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 指着的是哪一块：三块并排挂在框正面，左、中、右各占三分之一宽（与 EmaRackRenderer 的 SLOT_X 一致）。
     * 绘马的局部 +x 在世界里是朝向的逆时针方向。
     */
    static int slotAt(BlockState state, BlockPos pos, BlockHitResult hit) {
        Direction side = state.getValue(FACING).getCounterClockWise();
        double x = (hit.getLocation().x - pos.getX() - 0.5) * side.getStepX() + (hit.getLocation().z - pos.getZ() - 0.5) * side.getStepZ();
        return x < -0.15 ? 0 : x > 0.15 ? 2 : 1;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EmaRackBlockEntity(pos, state);
    }
}
