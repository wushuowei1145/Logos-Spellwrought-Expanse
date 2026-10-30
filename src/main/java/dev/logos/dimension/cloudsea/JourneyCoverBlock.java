package dev.logos.dimension.cloudsea;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 贴着地面的落瓣 / 落叶层，像原版的花瓣和落叶：没有碰撞、挡不住视线，四档厚度。
 *
 * <p>`AMOUNT` 1–4 决定铺开的数量与厚度，由世界生成时的加权状态挑选；
 * 手摆的话方块物品放下去是 1 档，用 `/setblock` 或调试棒可以改。
 */
public final class JourneyCoverBlock extends BushBlock {
    public static final IntegerProperty AMOUNT = IntegerProperty.create("amount", 1, 4);
    private static final VoxelShape[] SHAPES = new VoxelShape[]{
        box(0, 0, 0, 16, 0.5, 16),
        box(0, 0, 0, 16, 1, 16),
        box(0, 0, 0, 16, 1.5, 16),
        box(0, 0, 0, 16, 2, 16),
    };

    public JourneyCoverBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(AMOUNT, 1));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(AMOUNT) - 1];
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(AMOUNT);
    }
}
