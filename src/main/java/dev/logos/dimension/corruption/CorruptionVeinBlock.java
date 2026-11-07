package dev.logos.dimension.corruption;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.ServerConfig;
import java.util.Collection;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreadeableBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 腐败脉络：照搬原版幽匿脉络，贴在方块表面、能爬上墙和天花板；电荷经过时把贴着的方块变成腐败污秽
 * （{@code logos_dimension:corruption_replaceable} 里的方块，配置 {@code convertBlocks} 可以关掉这一步）。
 */
public class CorruptionVeinBlock extends MultifaceSpreadeableBlock implements CorruptionBehaviour {
    public static final MapCodec<CorruptionVeinBlock> CODEC = simpleCodec(CorruptionVeinBlock::new);
    private final MultifaceSpreader veinSpreader = new MultifaceSpreader(new VeinSpreaderConfig(MultifaceSpreader.DEFAULT_SPREAD_ORDER));
    private final MultifaceSpreader sameSpaceSpreader = new MultifaceSpreader(new VeinSpreaderConfig(MultifaceSpreader.SpreadType.SAME_POSITION));

    public CorruptionVeinBlock(Properties properties) {
        super(properties);
    }

    @Override public MapCodec<CorruptionVeinBlock> codec() { return CODEC; }

    @Override public MultifaceSpreader getSpreader() { return veinSpreader; }

    public MultifaceSpreader getSameSpaceSpreader() { return sameSpaceSpreader; }

    /** 在 {@code pos} 按 {@code faces} 重新长出脉络（原版 {@code SculkVeinBlock.regrow}）。 */
    public static boolean regrow(LevelAccessor level, BlockPos pos, BlockState existing, Collection<Direction> faces) {
        boolean any = false;
        BlockState state = Corruption.VEIN.get().defaultBlockState();
        for (Direction face : faces) {
            if (canAttachTo(level, pos, face)) {
                state = state.setValue(getFaceProperty(face), true);
                any = true;
            }
        }
        if (!any) return false;
        if (!existing.getFluidState().isEmpty()) state = state.setValue(MultifaceBlock.WATERLOGGED, true);
        level.setBlock(pos, state, Block.UPDATE_ALL);
        return true;
    }

    /** 贴着腐败污秽的那几面收掉，一面不剩就变回空气或水。 */
    @Override
    public void onDischarged(LevelAccessor level, BlockState state, BlockPos pos, RandomSource random) {
        if (!state.is(this)) return;
        for (Direction dir : DIRECTIONS) {
            BooleanProperty side = getFaceProperty(dir);
            if (state.getValue(side) && level.getBlockState(pos.relative(dir)).is(Corruption.BLOCK.get())) state = state.setValue(side, false);
        }
        if (!hasAnyFace(state)) state = (level.getFluidState(pos).isEmpty() ? Blocks.AIR : Blocks.WATER).defaultBlockState();
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    @Override
    public int attemptUseCharge(CorruptionSpreader.Cursor cursor, LevelAccessor level, BlockPos origin, RandomSource random, CorruptionSpreader spreader) {
        if (attemptConvert(level, cursor.pos(), random)) return cursor.charge() - 1;
        return random.nextInt(CorruptionSpreader.CHARGE_DECAY_RATE) == 0 ? Mth.floor(cursor.charge() * .5f) : cursor.charge();
    }

    /** 原版 {@code attemptPlaceSculk}：随便挑一面，贴着的方块能换就换成腐败污秽，再从它往外铺脉络。 */
    private boolean attemptConvert(LevelAccessor level, BlockPos pos, RandomSource random) {
        if (!ServerConfig.CORRUPTION_CONVERTS_BLOCKS.getAsBoolean()) return false;
        BlockState state = level.getBlockState(pos);
        for (Direction support : Direction.allShuffled(random)) {
            if (!hasFace(state, support)) continue;
            BlockPos supportPos = pos.relative(support);
            BlockState supportState = level.getBlockState(supportPos);
            if (!supportState.is(Corruption.REPLACEABLE)) continue;
            BlockState filth = Corruption.BLOCK.get().defaultBlockState();
            level.setBlock(supportPos, filth, Block.UPDATE_ALL);
            // 放下时已经记进网络了（onPlace），再记下原来是什么，核心没了以后变回去
            if (level instanceof ServerLevel server) CorruptionNetworks.get(server).remember(supportPos, supportState);
            Block.pushEntitiesUp(supportState, filth, level, supportPos);
            level.playSound(null, supportPos, SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.BLOCKS, 1, .8f);
            veinSpreader.spreadAll(filth, level, supportPos, false);
            Direction skip = support.getOpposite();
            for (Direction dir : DIRECTIONS) {
                if (dir == skip) continue;
                BlockPos veinPos = supportPos.relative(dir);
                BlockState vein = level.getBlockState(veinPos);
                if (vein.is(this)) onDischarged(level, vein, veinPos, random);
            }
            return true;
        }
        return false;
    }

    /** 这条脉络贴着还能被换掉的方块（光标优先往这样的地方走）。 */
    public static boolean hasSubstrateAccess(LevelAccessor level, BlockState state, BlockPos pos) {
        if (!state.is(Corruption.VEIN.get())) return false;
        for (Direction direction : DIRECTIONS) {
            if (hasFace(state, direction) && level.getBlockState(pos.relative(direction)).is(Corruption.REPLACEABLE)) return true;
        }
        return false;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean movedByPiston) {
        super.onPlace(state, level, pos, old, movedByPiston);
        if (level instanceof ServerLevel server && !old.is(this)) CorruptionNetworks.get(server).claim(server, pos);
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        CorruptionNetworks.get(level).release(pos);
    }

    private final class VeinSpreaderConfig extends MultifaceSpreader.DefaultSpreaderConfig {
        private final MultifaceSpreader.SpreadType[] spreadTypes;

        VeinSpreaderConfig(MultifaceSpreader.SpreadType... spreadTypes) {
            Objects.requireNonNull(CorruptionVeinBlock.this);
            super(CorruptionVeinBlock.this);
            this.spreadTypes = spreadTypes;
        }

        @Override
        public boolean stateCanBeReplaced(BlockGetter level, BlockPos source, BlockPos placement, Direction direction, BlockState existing) {
            BlockState against = level.getBlockState(placement.relative(direction));
            if (against.is(Corruption.BLOCK.get()) || against.is(Corruption.CORE.get()) || against.is(Blocks.MOVING_PISTON)) return false;
            if (source.distManhattan(placement) == 2) {
                BlockPos neighbour = source.relative(direction.getOpposite());
                if (level.getBlockState(neighbour).isFaceSturdy(level, neighbour, direction)) return false;
            }
            FluidState fluid = existing.getFluidState();
            if (!fluid.isEmpty() && !fluid.is(Fluids.WATER)) return false;
            if (existing.is(BlockTags.FIRE)) return false;
            return existing.canBeReplaced() || super.stateCanBeReplaced(level, source, placement, direction, existing);
        }

        @Override public MultifaceSpreader.SpreadType[] getSpreadTypes() { return spreadTypes; }

        @Override public boolean isOtherBlockValidAsSource(BlockState state) { return !state.is(Corruption.VEIN.get()); }
    }
}
