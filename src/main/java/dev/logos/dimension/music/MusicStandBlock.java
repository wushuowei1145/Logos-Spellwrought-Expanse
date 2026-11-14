package dev.logos.dimension.music;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 乐谱架：拿着手持乐器右键，把乐器放到架子上（小提琴靠着架子、长笛横在谱台边、小号搁在谱台上垂着王室旗）；
 * 之后它就是一件方块乐器：纸带槽、红石合奏、演奏页都和钢琴一样。潜行空手右键把乐器拿回来。
 * 放着什么乐器记在方块状态 {@link #HOLDS} 上（模型因此看得出来），乐器物品本身存在方块实体里。
 * <p>
 * 空架子正对着一张琴凳、凳子上坐着拿手持乐器的人或生物时（{@link #seatedPlayer}），架子就是那位乐手的谱台：
 * 右键打开那张琴凳的乐器界面（演奏页、纸带页），拿着纸带右键把纸带放进琴凳（docs/ORCHESTRA.md 第 19 节）。
 */
public class MusicStandBlock extends InstrumentBlock {
    public enum Holds implements StringRepresentable {
        NONE("none", null), VIOLIN("violin", InstrumentKind.VIOLIN), FLUTE("flute", InstrumentKind.FLUTE),
        TRUMPET("trumpet", InstrumentKind.TRUMPET), VIOLA("viola", InstrumentKind.VIOLA), OBOE("oboe", InstrumentKind.OBOE),
        BASSOON("bassoon", InstrumentKind.BASSOON);

        private final String name;
        private final @Nullable InstrumentKind kind;

        Holds(String name, @Nullable InstrumentKind kind) {
            this.name = name;
            this.kind = kind;
        }

        public @Nullable InstrumentKind kind() {
            return kind;
        }

        public static Holds of(@Nullable InstrumentKind kind) {
            for (Holds h : values()) if (h.kind == kind) return h;
            return NONE;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Holds> HOLDS = EnumProperty.create("holds", Holds.class);
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(
        Shapes.or(Block.box(6.5, 0, 6.5, 9.5, 12, 9.5), Block.box(2, 11, 6, 14, 16, 10), Block.box(3, 0, 3, 13, 1, 13)));

    public MusicStandBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HOLDS, Holds.NONE));
    }

    /** 空架子时没有乐器：这里只给一个占位值，判断能不能演奏用 {@link #kind(BlockState)}。 */
    @Override
    public InstrumentKind kind() {
        return InstrumentKind.VIOLIN;
    }

    @Override
    public @Nullable InstrumentKind kind(BlockState state) {
        return state.hasProperty(HOLDS) ? state.getValue(HOLDS).kind() : null;
    }

    @Override
    public boolean isMain(BlockState state) {
        return true;
    }

    @Override
    public BlockPos mainPos(BlockState state, BlockPos pos) {
        return pos;
    }

    @Override
    public Iterable<BlockPos> cells(BlockState mainState, BlockPos main) {
        return List.of(main);
    }

    @Override
    protected Vec3 centerOf(BlockState mainState, BlockPos main) {
        return Vec3.atCenterOf(main).add(0, 0.2, 0);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /**
     * 空架子正对着的琴凳（谱面朝着的那一格、凳子面朝架子），凳子上坐着拿手持乐器的人或生物、正在演奏它（琴凳的 {@code plays} 不是空的）时
     * 返回琴凳的主块；否则为 null。架子上自己放着乐器时也为 null（那时架子就是那件乐器）。
     */
    public static @Nullable BlockPos seatedPlayer(BlockGetter level, BlockPos stand, BlockState state) {
        if (!state.hasProperty(HOLDS) || state.getValue(HOLDS) != Holds.NONE) return null;
        Direction facing = state.getValue(FACING);
        BlockPos front = stand.relative(facing);
        if (!(level.getBlockState(front).getBlock() instanceof StarbloomBenchBlock)) return null;
        BlockPos bench = StarbloomBenchBlock.main(level, front);
        BlockState benchState = level.getBlockState(bench);
        if (!(benchState.getBlock() instanceof StarbloomBenchBlock b) || benchState.getValue(FACING) != facing.getOpposite()) return null;
        return b.kind(benchState) != null ? bench : null;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        InstrumentKind kind = Instruments.heldKind(stack);
        BlockPos bench = seatedPlayer(level, pos, state);
        if (kind == null && bench != null && Instruments.isRoll(stack)) {
            // 谱台前坐着乐手：纸带交给琴凳（乐手照着它演奏）
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            if (!(level.getBlockEntity(bench) instanceof InstrumentBlockEntity be)) return InteractionResult.PASS;
            ItemStack old = be.roll();
            be.setRoll(stack.copyWithCount(1));
            if (!player.getAbilities().instabuild) stack.shrink(1);
            if (!old.isEmpty() && !player.getInventory().add(old)) player.drop(old, false);
            return InteractionResult.SUCCESS;
        }
        if (kind == null) return super.useItemOn(stack, state, level, pos, player, hand, hit);
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof InstrumentBlockEntity be)) return InteractionResult.PASS;
        // 已经放着一件：换下来还给玩家
        ItemStack old = be.takeInstrument();
        be.setInstrument(stack.copyWithCount(1));
        level.setBlock(pos, state.setValue(HOLDS, Holds.of(kind)), Block.UPDATE_ALL);
        // 在乐团里：按换上的乐器重新分声部
        dev.logos.dimension.music.orchestra.PodiumBlockEntity.memberChanged(level, be.orchestra(), pos, false);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        if (!old.isEmpty() && !player.getInventory().add(old)) player.drop(old, false);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        Holds holds = state.getValue(HOLDS);
        BlockPos bench = seatedPlayer(level, pos, state);
        if (bench != null) {
            // 谱台前坐着乐手：打开那张琴凳的乐器界面（界面类只在客户端存在，经静态入口打开）
            if (level.isClientSide()) dev.logos.dimension.client.music.MusicClient.openInstrument(bench);
            return InteractionResult.SUCCESS;
        }
        if (holds == Holds.NONE) {
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                sp.sendSystemMessage(Component.translatable("message.logos_dimension.instrument.need_instrument"), true);
            }
            return InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide() && level.getBlockEntity(pos) instanceof InstrumentBlockEntity be) {
                ItemStack out = be.takeInstrument();
                level.setBlock(pos, state.setValue(HOLDS, Holds.NONE), Block.UPDATE_ALL);
                // 乐器拿走了：退出乐团
                dev.logos.dimension.music.orchestra.PodiumBlockEntity.memberChanged(level, be.orchestra(), pos, false);
                if (!out.isEmpty() && !player.getInventory().add(out)) player.drop(out, false);
            }
            return InteractionResult.SUCCESS;
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HOLDS);
    }
}
