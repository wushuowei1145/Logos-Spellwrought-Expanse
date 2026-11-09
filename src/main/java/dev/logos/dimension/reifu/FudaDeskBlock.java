package dev.logos.dimension.reifu;

import java.util.Map;

import com.mojang.serialization.MapCodec;

import dev.logos.block.InscriptionTableBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 符札书案（ふだしょあん）：和风外观的法式编纂台。放载页、取载页、打开术式工坊、写入，全部沿用 Logos 的编纂台
 * （同一个方块实体类型，见 {@link Reifu}），只换了模型：一张矮书案，案上有笔、墨、纸、砚，载页平放在中间的毡垫上。
 */
public class FudaDeskBlock extends InscriptionTableBlock {
    public static final MapCodec<FudaDeskBlock> CODEC = simpleCodec(FudaDeskBlock::new);

    /** 书案连同案上的砚和墨：比编纂台矮，没有斜板；模型的朝前一面在南（+Z）。 */
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Block.box(0, 0, 1.5, 16, 14, 14.5));

    public FudaDeskBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }
}
