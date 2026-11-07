package dev.logos.dimension.corruption;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 腐败污秽：被脉络转化的方块，照搬原版幽匿块接电荷的办法，只是不长感测体和尖啸体，
 * 所以电荷在这里只会衰减：核心附近慢慢掉，离核心越远掉得越快。
 */
public class CorruptionBlock extends Block implements CorruptionBehaviour {
    public static final MapCodec<CorruptionBlock> CODEC = simpleCodec(CorruptionBlock::new);

    public CorruptionBlock(Properties properties) {
        super(properties);
    }

    @Override protected MapCodec<CorruptionBlock> codec() { return CODEC; }

    @Override
    public int attemptUseCharge(CorruptionSpreader.Cursor cursor, LevelAccessor level, BlockPos origin, RandomSource random, CorruptionSpreader spreader) {
        int charge = cursor.charge();
        if (charge == 0 || random.nextInt(CorruptionSpreader.CHARGE_DECAY_RATE) != 0) return charge;
        if (random.nextInt(CorruptionSpreader.ADDITIONAL_DECAY_RATE) != 0) return charge;
        boolean near = cursor.pos().closerThan(origin, CorruptionSpreader.NO_GROWTH_RADIUS);
        return charge - (near ? 1 : decayPenalty(cursor.pos(), origin, charge));
    }

    /** 原版 {@code SculkBlock.getDecayPenalty}，最远半径换成配置的 {@code growthRadius}。 */
    static int decayPenalty(BlockPos pos, BlockPos origin, int charge) {
        int inner = CorruptionSpreader.NO_GROWTH_RADIUS;
        float outer = Mth.square((float) Math.sqrt(pos.distSqr(origin)) - inner);
        int reach = Mth.square(ServerConfig.CORRUPTION_GROWTH_RADIUS.getAsInt() - inner);
        return Math.max(1, (int) (charge * Math.min(1, outer / reach) * .5f));
    }

    @Override
    public boolean canChangeBlockStateOnSpread() {
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
}
