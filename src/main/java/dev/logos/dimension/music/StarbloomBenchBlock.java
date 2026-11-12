package dev.logos.dimension.music;

import dev.logos.dimension.music.orchestra.Orchestra;
import dev.logos.dimension.music.orchestra.PodiumBlockEntity;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 星辰花琴凳（docs/ORCHESTRA.md 第 14 节）。坐的人面朝 {@link #FACING}（放置时玩家面对的方向）。
 *
 * <ul>
 * <li><b>和乐器关联</b>：正前方一格是要坐着弹的方块乐器、并且琴凳在它的演奏那一面（钢琴、大键琴、钢片琴、管风琴演奏台在琴键那一面，
 *     竖琴在它背后），琴凳就和那件乐器是一体的：坐上去的人或生物就是那件乐器的演奏者，手里拿什么都不算；
 *     乐团成员是那件乐器，琴凳自己不算。乐器拆了、换了就不再关联（每次都按旁边的方块现算，不存）。</li>
 * <li><b>没有关联</b>：琴凳自己就是乐团里的一个座位，演奏的是坐在上面的人或生物主手里的手持乐器（记在 {@link #PLAYS} 上，
 *     客户端据此出声、分声部）；没人坐、手里不是乐器时休息，但仍可以留在乐团里。</li>
 * </ul>
 * 玩家右键坐下（已经坐在这张凳上时右键交给手里的东西，例如举起小提琴自己拉）；牵着生物右键让它坐下；潜行空手右键让坐着的生物站起来。
 */
public class StarbloomBenchBlock extends InstrumentBlock {
    public static final EnumProperty<MusicStandBlock.Holds> PLAYS = EnumProperty.create("plays", MusicStandBlock.Holds.class);
    /** 凳面高（像素），与 tools/instrument_art.py 的 bench_elements 一致。 */
    public static final float TOP = 10;
    /** 坐的人双脚在方块里的位置（像素，凳子中间）：双脚比地面低 2 像素，坐姿的大腿正好压在凳面上（同大提琴的凳子）。 */
    public static final Vec3 FEET = new Vec3(8, -2, 8);
    /** 关联了乐器的琴凳：坐的人往乐器那边挪这么多像素（离琴键近一些，手够得着）。 */
    public static final float LINKED_FORWARD = 2.5f;
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Block.box(1, 0, 3, 15, TOP, 13));

    public StarbloomBenchBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PLAYS, MusicStandBlock.Holds.NONE));
    }

    /** 占位值；能不能演奏、演奏什么看 {@link #kind(BlockState)}。 */
    @Override
    public InstrumentKind kind() {
        return InstrumentKind.VIOLIN;
    }

    @Override
    public @Nullable InstrumentKind kind(BlockState state) {
        return state.hasProperty(PLAYS) ? state.getValue(PLAYS).kind() : null;
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

    /** 声音从坐的人胸口一带发出。 */
    @Override
    protected Vec3 centerOf(BlockState mainState, BlockPos main) {
        return Vec3.atBottomCenterOf(main).add(0, 1.1, 0);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PLAYS);
    }

    // —— 关联 ——

    /** 坐着弹这件乐器的人面朝哪边；不是坐着弹的乐器（或不是本附属的方块乐器）为 null。 */
    public static @Nullable Direction performerFacing(@Nullable InstrumentKind kind, BlockState mainState) {
        if (kind == null || !mainState.hasProperty(FACING)) return null;
        Direction facing = mainState.getValue(FACING);
        return switch (kind) {
            // 方块乐器的 FACING 朝着放置它的人（琴键那一面）：弹的人面朝相反方向
            case GRAND_PIANO, HARPSICHORD, CELESTA, PIPE_ORGAN -> facing.getOpposite();
            // 竖琴同大提琴：人坐在琴后、和琴朝同一个方向
            case CONCERT_HARP -> facing;
            default -> null;
        };
    }

    /** 这张琴凳关联的乐器的主块；没有关联时为 null。 */
    public static @Nullable BlockPos linked(BlockGetter level, BlockPos bench, BlockState benchState) {
        if (!(benchState.getBlock() instanceof StarbloomBenchBlock)) return null;
        Direction facing = benchState.getValue(FACING);
        BlockPos front = bench.relative(facing);
        BlockState frontState = level.getBlockState(front);
        if (!(frontState.getBlock() instanceof InstrumentBlock block) || frontState.getBlock() instanceof StarbloomBenchBlock) return null;
        BlockPos main = block.mainPos(frontState, front);
        BlockState mainState = main.equals(front) ? frontState : level.getBlockState(main);
        if (!(mainState.getBlock() instanceof InstrumentBlock)) return null;
        return performerFacing(block.kind(mainState), mainState) == facing ? main.immutable() : null;
    }

    public static @Nullable BlockPos linked(BlockGetter level, BlockPos bench) {
        return linked(level, bench, level.getBlockState(bench));
    }

    /** 关联的乐器种类（给以后的同伴挑座位用，例如艾洛蒂找钢琴前的空凳子）；没有关联时为 null。 */
    public static @Nullable InstrumentKind linkedKind(BlockGetter level, BlockPos bench) {
        BlockPos main = linked(level, bench);
        if (main == null) return null;
        BlockState state = level.getBlockState(main);
        return state.getBlock() instanceof InstrumentBlock b ? b.kind(state) : null;
    }

    // —— 坐 ——

    /** 这张琴凳上有人坐着的座位；空着时为 null。 */
    private static @Nullable BenchSeat seatOf(Level level, BlockPos bench) {
        for (BenchSeat seat : level.getEntitiesOfClass(BenchSeat.class, new AABB(bench).inflate(0.5))) {
            if (seat.bench().equals(bench) && !seat.isRemoved() && !seat.getPassengers().isEmpty()) return seat;
        }
        return null;
    }

    /** 坐在这张琴凳上的人或生物；空着时为 null。 */
    public static @Nullable Entity occupant(Level level, BlockPos bench) {
        BenchSeat seat = seatOf(level, bench);
        return seat == null ? null : seat.getFirstPassenger();
    }

    public static boolean isFree(Level level, BlockPos bench) {
        return level.getBlockState(bench).getBlock() instanceof StarbloomBenchBlock && occupant(level, bench) == null;
    }

    /** 坐的人的双脚在世界里的位置：凳子中间；关联了乐器时往乐器那边挪 {@link #LINKED_FORWARD}。 */
    public static Vec3 seatPosition(BlockPos bench, Direction facing, boolean linked) {
        float forward = linked ? LINKED_FORWARD : 0;
        return new Vec3(bench.getX() + FEET.x / 16 + facing.getStepX() * forward / 16, bench.getY() + FEET.y / 16,
            bench.getZ() + FEET.z / 16 + facing.getStepZ() * forward / 16);
    }

    /** 坐的人双脚在琴凳坐标里的位置（像素；琴凳朝北：x 向东、y 向上、z 向南）。 */
    public static Vec3 feet(boolean linked) {
        return new Vec3(FEET.x, FEET.y, FEET.z - (linked ? LINKED_FORWARD : 0));
    }

    /**
     * 让 who 坐到这张琴凳上（服务端）。生物坐下后不再走动、不会被刷掉；同伴以后找到空凳子时也调这里。
     * @return 坐下了为 true（凳子不在、已有人坐时为 false）
     */
    public static boolean sit(Level level, BlockPos bench, Entity who) {
        if (level.isClientSide()) return false;
        BlockState state = level.getBlockState(bench);
        if (!(state.getBlock() instanceof StarbloomBenchBlock) || occupant(level, bench) != null) return false;
        if (who.isPassenger()) who.stopRiding();
        BenchSeat seat = Benches.SEAT.get().create(level, EntitySpawnReason.TRIGGERED);
        if (seat == null) return false;
        Direction facing = state.getValue(FACING);
        seat.bind(bench, seatPosition(bench, facing, linked(level, bench, state) != null), facing.toYRot());
        level.addFreshEntity(seat);
        if (!who.startRiding(seat, true, true)) {
            seat.discard();
            return false;
        }
        if (who instanceof Mob mob) {
            mob.setPersistenceRequired();
            mob.getNavigation().stop();
        }
        refresh(level, bench);
        return true;
    }

    /** 让坐着的生物站起来（玩家自己潜行起身）；没有生物坐着时为 false。 */
    public static boolean standUp(Level level, BlockPos bench) {
        Entity who = occupant(level, bench);
        if (!(who instanceof Mob)) return false;
        who.stopRiding();
        refresh(level, bench);
        return true;
    }

    /**
     * 按坐着的人手里的东西和旁边的乐器重算 {@link #PLAYS}（服务端）；变了就通知乐团重新分声部。
     * 坐的人也跟着挪：前面放上了关联的乐器就挪到琴键前，拆掉了就挪回凳子中间。
     * 坐下、起身、换手里的东西（座位每半秒查一次）、旁边的方块变了时调用。
     */
    public static void refresh(Level level, BlockPos bench) {
        if (level.isClientSide()) return;
        BlockState state = level.getBlockState(bench);
        if (!(state.getBlock() instanceof StarbloomBenchBlock)) return;
        boolean linked = linked(level, bench, state) != null;
        BenchSeat seat = seatOf(level, bench);
        if (seat != null) seat.align(seatPosition(bench, state.getValue(FACING), linked), state.getValue(FACING).toYRot());
        MusicStandBlock.Holds plays = MusicStandBlock.Holds.NONE;
        if (!linked && seat != null && seat.getFirstPassenger() instanceof LivingEntity who
                && who.getMainHandItem().getItem() instanceof HandheldInstrumentItem item) {
            plays = MusicStandBlock.Holds.of(item.kind());
        }
        if (state.getValue(PLAYS) == plays) return;
        level.setBlock(bench, state.setValue(PLAYS, plays), Block.UPDATE_ALL);
        if (level.getBlockEntity(bench) instanceof InstrumentBlockEntity be) PodiumBlockEntity.memberChanged(level, be.orchestra(), bench, false);
    }

    // —— 交互 ——

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                          InteractionHand hand, BlockHitResult hit) {
        if (stack.is(Orchestra.BATON.get())) {
            // 指挥棒：关联了乐器的凳子就是那件乐器
            BlockPos instrument = linked(level, pos, state);
            if (!level.isClientSide()) Orchestra.batonOnInstrument(stack, (ServerLevel) level, instrument != null ? instrument : pos, player);
            return InteractionResult.SUCCESS;
        }
        if (stack.is(Items.LEAD)) {
            if (level.isClientSide()) return InteractionResult.SUCCESS;
            Mob led = nearestLed(player);
            if (led != null) {
                if (occupant(level, pos) == null) {
                    led.removeLeash();
                    if (!player.getAbilities().instabuild) player.getInventory().add(new ItemStack(Items.LEAD));
                    sit(level, pos, led);
                }
                return InteractionResult.SUCCESS;
            }
        }
        // 已经坐在这张凳上：把右键交给手里的东西（例如举起小提琴自己拉）
        if (occupant(level, pos) == player) return InteractionResult.PASS;
        return seatPlayer(level, pos, player);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player.isSecondaryUseActive()) {
            if (!level.isClientSide()) standUp(level, pos);
            return InteractionResult.SUCCESS;
        }
        return seatPlayer(level, pos, player);
    }

    private static InteractionResult seatPlayer(Level level, BlockPos pos, Player player) {
        if (player.isPassenger()) return InteractionResult.PASS;
        if (!level.isClientSide()) sit(level, pos, player);
        return InteractionResult.SUCCESS;
    }

    /** 拴在这个玩家手上、离他最近的生物（拴绳能牵到的距离以内）。 */
    private static @Nullable Mob nearestLed(Player player) {
        Mob best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Leashable leashable : Leashable.leashableLeashedTo(player)) {
            if (!(leashable instanceof Mob mob)) continue;
            double d = mob.distanceToSqr(player);
            if (d < bestDistance) {
                best = mob;
                bestDistance = d;
            }
        }
        return best;
    }

    /** 前面的乐器放上、拆掉、换了：关联变了，重算演奏什么。 */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
        refresh(level, pos);
    }
}
