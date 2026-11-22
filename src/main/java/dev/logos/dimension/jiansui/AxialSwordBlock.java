package dev.logos.dimension.jiansui;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 遗剑（docs/JIAN_SUI_BOSS.md 第 12 节）：剑祟被撬出来的贯穿古剑，插在它当初插剑的地方。方块本身不画，
 * 剑由 {@code AxialSwordRenderer} 用剑祟那份网格画；打不坏、拿不走。以后做成奖励时照石中剑改成能拔。
 */
public class AxialSwordBlock extends Block implements EntityBlock {
    public static final MapCodec<AxialSwordBlock> CODEC = simpleCodec(AxialSwordBlock::new);
    /** 露在地面上的那一截剑身：细细一根，一格高（剑格在这一格之上，不挡人）。 */
    private static final VoxelShape SHAPE = Block.box(6.5, 0, 6.5, 9.5, 16, 9.5);

    public AxialSwordBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<AxialSwordBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AxialSwordBlockEntity(pos, state);
    }
}
