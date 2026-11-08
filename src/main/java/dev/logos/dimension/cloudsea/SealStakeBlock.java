package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.fox.FoxSanctuaryStructure;
import dev.logos.dimension.fox.SanctuaryVaultPiece;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 封印杭：两格高的黑木桩，缠着草绳、贴着御札，上半截透出一点幽光。神狐聚落地下的封印之间里九根围成一圈，
 * 封着杀生石里的九尾狐；破坏一根，封印就少一道（剩几道会告诉在场的玩家）。不掉落、炸不坏、推不动。
 * 九尾狐首领战还没有做，最后一道断掉时现在只有声响与提示。
 */
public class SealStakeBlock extends Block {
    public static final MapCodec<SealStakeBlock> CODEC = simpleCodec(SealStakeBlock::new);
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    private static final VoxelShape SHAPE_LOWER = Block.box(4, 0, 4, 12, 16, 12);
    private static final VoxelShape SHAPE_UPPER = Block.box(5, 0, 5, 11, 10, 11);

    public SealStakeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    public MapCodec<SealStakeBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HALF);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? SHAPE_UPPER : SHAPE_LOWER;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() < level.getMaxY() && level.getBlockState(pos.above()).canBeReplaced(context)) return defaultBlockState();
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        level.setBlock(pos.above(), defaultBlockState().setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
                                     BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        DoubleBlockHalf half = state.getValue(HALF);
        if (direction.getAxis() == Direction.Axis.Y && half == DoubleBlockHalf.LOWER == (direction == Direction.UP)
            && (!neighborState.is(this) || neighborState.getValue(HALF) == half)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (half == DoubleBlockHalf.LOWER && direction == Direction.DOWN && !state.canSurvive(level, pos)) return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        if (level instanceof ServerLevel server) {
            Vec3 c = Vec3.atBottomCenterOf(lower);
            server.playSound(null, lower, SoundEvents.CHAIN_BREAK, SoundSource.BLOCKS, 1.2f, 0.55f);
            server.playSound(null, lower, SoundEvents.SOUL_ESCAPE.value(), SoundSource.BLOCKS, 1.4f, 0.8f);
            server.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 1.2, c.z, 40, 0.25, 0.7, 0.25, 0.03);
            server.sendParticles(ParticleTypes.SOUL, c.x, c.y + 1.4, c.z, 12, 0.3, 0.5, 0.3, 0.02);
            broken(server, lower);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** 清点同一处封印之间里还剩几根封印杭，告诉在场的玩家；最后一根断掉时杀生石那边有动静。 */
    private void broken(ServerLevel level, BlockPos lower) {
        SanctuaryVaultPiece vault = FoxSanctuaryStructure.vaultAt(level, lower);
        if (vault == null || !vault.seals().contains(lower)) return;
        int left = 0;
        for (BlockPos p : vault.seals()) if (!p.equals(lower) && level.getBlockState(p).is(this)) left++;
        Component message = left > 0
            ? Component.translatable("block.logos_dimension.journey_seal_stake.left", left)
            : Component.translatable("block.logos_dimension.journey_seal_stake.released");
        for (ServerPlayer player : level.players()) {
            if (vault.inArena(player.blockPosition()) || player.blockPosition().closerThan(lower, 64)) player.sendOverlayMessage(message);
        }
        if (left == 0) {
            BlockPos stone = vault.sealStone();
            Vec3 c = Vec3.atBottomCenterOf(stone);
            level.playSound(null, stone, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 2.0f, 0.5f);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 4, c.z, 160, 2.5, 4, 2.5, 0.05);
        }
    }
}
