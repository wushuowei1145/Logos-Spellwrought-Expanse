package dev.logos.dimension.gate;

import com.mojang.serialization.MapCodec;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 传送面。没有碰撞，也选不中，所以隔着它还能点到地上的启门石、术式也能打穿；
 * 进入判定用一片和下界传送门一样厚的薄片，走到门中央才算进去。
 *
 * <p>门框有方块被打掉、变成空气时，和下界传送门一样连锁消失。楼梯、台阶、活板门留下的缝由
 * {@code EDGE_*} 控制模型多画一格来补（见 {@link GateShape#withEdges}）；被挡住的部分藏在实心部分后面。
 */
public final class GatePortalBlock extends Block implements Portal {
    public static final MapCodec<GatePortalBlock> CODEC = simpleCodec(GatePortalBlock::new);
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    /** 这格在 8×8 大贴图里的位置（列、行）：贴图横跨几格，整片门面的纹路连续，不会一格一格重复。 */
    public static final IntegerProperty COL = IntegerProperty.create("col", 0, 7), ROW = IntegerProperty.create("row", 0, 7);
    public static final BooleanProperty EDGE_UP = BooleanProperty.create("edge_up"), EDGE_DOWN = BooleanProperty.create("edge_down"),
            EDGE_NEGATIVE = BooleanProperty.create("edge_negative"), EDGE_POSITIVE = BooleanProperty.create("edge_positive");
    private static final Map<Direction.Axis, VoxelShape> INSIDE = Shapes.rotateHorizontalAxis(Block.column(4.0, 16.0, 0.0, 16.0));
    /** 玩家站进门里要等多久才传走（刻）；下界传送门是 80。创造模式立刻走。 */
    public static final int PLAYER_DELAY = 40;

    public GatePortalBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(COL, 0).setValue(ROW, 0).setValue(EDGE_UP, false).setValue(EDGE_DOWN, false)
                .setValue(EDGE_NEGATIVE, false).setValue(EDGE_POSITIVE, false));
    }

    /** 门洞里这个位置的状态：朝向，加上它在大贴图里的那一块。 */
    public BlockState at(BlockPos pos, Direction.Axis axis) {
        int along = axis == Direction.Axis.X ? pos.getX() : pos.getZ();
        return defaultBlockState().setValue(AXIS, axis).setValue(COL, Math.floorMod(along, 8)).setValue(ROW, Math.floorMod(pos.getY(), 8));
    }

    public static BooleanProperty edge(Direction direction) {
        if (direction == Direction.UP) return EDGE_UP;
        if (direction == Direction.DOWN) return EDGE_DOWN;
        return direction.getAxisDirection() == Direction.AxisDirection.NEGATIVE ? EDGE_NEGATIVE : EDGE_POSITIVE;
    }

    @Override public MapCodec<GatePortalBlock> codec() { return CODEC; }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Shapes.empty(); }

    @Override protected VoxelShape getEntityInsideCollisionShape(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        return INSIDE.get(state.getValue(AXIS));
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
            BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        boolean inPlane = direction.getAxis() == state.getValue(AXIS) || direction.getAxis() == Direction.Axis.Y;
        if (inPlane && !neighbourState.is(this) && GateShape.fillable(neighbourState)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbourState, random);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean precise) {
        if (entity.canUsePortal(false)) entity.setAsInsidePortal(this, pos);
    }

    @Override public int getPortalTransitionTime(ServerLevel level, Entity entity) {
        return entity instanceof Player player && !player.getAbilities().invulnerable ? PLAYER_DELAY : 0;
    }

    @Override public @Nullable TeleportTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos entry) {
        return GateTravel.destination(level, entity, entry);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(240) == 0) level.playLocalSound(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, SoundEvents.PORTAL_AMBIENT,
                SoundSource.BLOCKS, .25f, 1.3f + random.nextFloat() * .4f, false);
        if (random.nextInt(6) != 0) return;
        boolean alongX = state.getValue(AXIS) == Direction.Axis.X;
        double side = (random.nextBoolean() ? 1 : -1) * (.15 + random.nextDouble() * .4);
        double x = pos.getX() + (alongX ? random.nextDouble() : .5 + side), z = pos.getZ() + (alongX ? .5 + side : random.nextDouble());
        level.addParticle(net.minecraft.core.particles.ParticleTypes.GLOW, x, pos.getY() + random.nextDouble(), z, 0, .02, 0);
    }

    @Override protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) { return ItemStack.EMPTY; }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        if (rotation != Rotation.CLOCKWISE_90 && rotation != Rotation.COUNTERCLOCKWISE_90) return state;
        return state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, COL, ROW, EDGE_UP, EDGE_DOWN, EDGE_NEGATIVE, EDGE_POSITIVE);
    }
}
