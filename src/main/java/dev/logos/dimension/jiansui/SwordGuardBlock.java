package dev.logos.dimension.jiansui;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 扎住的巨剑剑格那一格：看不见，能踩（整格碰撞），选不中（没有轮廓）。剑本身由客户端画。 */
public class SwordGuardBlock extends Block {
    public static final MapCodec<SwordGuardBlock> CODEC = simpleCodec(SwordGuardBlock::new);

    public SwordGuardBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<SwordGuardBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}
