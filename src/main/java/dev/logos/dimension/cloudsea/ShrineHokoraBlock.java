package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 路边小石龛（Hokora）：供奉狐仙的迷你石龛神社。
 *
 * <p>放在神狐聚落的神社境内时激活（{@link #ACTIVE} 为 true），门里透出灯火、发 10 级光；境外不激活，不发光。
 * 聚落生成时境内的石龛直接带着激活状态，玩家放置时由 {@code FoxSanctuaryStructure.inShrine} 判定。
 */
public class ShrineHokoraBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<ShrineHokoraBlock> CODEC = simpleCodec(ShrineHokoraBlock::new);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    /** 激活时的亮度。 */
    public static final int ACTIVE_LIGHT = 10;
    private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);

    @Override
    public MapCodec<ShrineHokoraBlock> codec() {
        return CODEC;
    }

    public ShrineHokoraBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, false));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 客户端只做预测，是否在境内以服务端为准
        boolean active = context.getLevel() instanceof ServerLevel level
            && dev.logos.dimension.fox.FoxSanctuaryStructure.inShrine(level, context.getClickedPos());
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(ACTIVE, active);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }
}
