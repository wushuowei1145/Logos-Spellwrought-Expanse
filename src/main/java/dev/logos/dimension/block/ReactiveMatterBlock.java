package dev.logos.dimension.block;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.OpusPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** Only naturally generated matter reacts, protecting mined/replaced blocks and future player builds. */
public class ReactiveMatterBlock extends Block {
    public static final MapCodec<ReactiveMatterBlock> CODEC = simpleCodec(ReactiveMatterBlock::new);
    public static final BooleanProperty NATURAL = BooleanProperty.create("natural");
    public static final IntegerProperty PHASE = IntegerProperty.create("phase", 0, 3);

    public ReactiveMatterBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(NATURAL, false).setValue(PHASE, 0));
    }
    @Override public MapCodec<? extends ReactiveMatterBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(NATURAL, PHASE); }

    @Override protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.dimension().equals(LogosDimension.CRUCIBLE) || !state.getValue(NATURAL)) return;
        int phase = OpusPhase.of(level).ordinal();
        if (state.getValue(PHASE) != phase) level.setBlock(pos, state.setValue(PHASE, phase), Block.UPDATE_ALL);
        if (this == DimensionBlocks.SALT_CRUST.get() && phase == OpusPhase.ALBEDO.ordinal()
                && random.nextInt(18) == 0 && level.isEmptyBlock(pos.above())) {
            level.setBlock(pos.above(), DimensionBlocks.SALT_CRYSTAL.get().defaultBlockState()
                    .setValue(NATURAL, true).setValue(PHASE, phase), Block.UPDATE_ALL);
        }
        if (this == DimensionBlocks.SALT_CRYSTAL.get() && phase == OpusPhase.NIGREDO.ordinal()
                && random.nextInt(12) == 0) level.removeBlock(pos, false);
    }
}
