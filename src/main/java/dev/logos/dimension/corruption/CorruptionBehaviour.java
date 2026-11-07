package dev.logos.dimension.corruption;

import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * 腐败方块怎么接住、用掉、传走蔓延的电荷，照搬原版 {@code SculkBehaviour}。
 * 原版的那份把幽匿脉络写死了，所以这里另抄一份，指向腐败脉络。
 */
public interface CorruptionBehaviour {
    /** 光标停在不是腐败方块的地方（比如生物死掉的那一格）时的行为。 */
    CorruptionBehaviour DEFAULT = new CorruptionBehaviour() {
        @Override
        public boolean attemptSpreadVein(LevelAccessor level, BlockPos pos, BlockState state, @Nullable Collection<Direction> facings) {
            if (facings == null) {
                return Corruption.VEIN.get().getSameSpaceSpreader().spreadAll(level.getBlockState(pos), level, pos, false) > 0L;
            } else if (!facings.isEmpty()) {
                return (state.isAir() || state.getFluidState().is(Fluids.WATER)) && CorruptionVeinBlock.regrow(level, pos, state, facings);
            } else {
                return CorruptionBehaviour.super.attemptSpreadVein(level, pos, state, facings);
            }
        }

        @Override
        public int attemptUseCharge(CorruptionSpreader.Cursor cursor, LevelAccessor level, BlockPos origin, RandomSource random, CorruptionSpreader spreader) {
            return cursor.decayDelay() > 0 ? cursor.charge() : 0;
        }

        @Override
        public int updateDecayDelay(int age) {
            return Math.max(age - 1, 0);
        }
    };

    default int spreadDelay() {
        return 1;
    }

    default void onDischarged(LevelAccessor level, BlockState state, BlockPos pos, RandomSource random) {}

    default boolean attemptSpreadVein(LevelAccessor level, BlockPos pos, BlockState state, @Nullable Collection<Direction> facings) {
        return Corruption.VEIN.get().getSpreader().spreadAll(state, level, pos, false) > 0L;
    }

    default boolean canChangeBlockStateOnSpread() {
        return true;
    }

    default int updateDecayDelay(int age) {
        return 1;
    }

    int attemptUseCharge(CorruptionSpreader.Cursor cursor, LevelAccessor level, BlockPos origin, RandomSource random, CorruptionSpreader spreader);
}
