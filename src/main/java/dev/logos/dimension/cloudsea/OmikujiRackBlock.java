package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 结签架：两根细柱之间拉两道绳。把签纸系上去，绳上的白纸越来越多（0–3 档），身上的霉运也随之解掉。 */
public class OmikujiRackBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<OmikujiRackBlock> CODEC = simpleCodec(OmikujiRackBlock::new);
    public static final IntegerProperty TIED = IntegerProperty.create("tied", 0, 3);
    private static final VoxelShape SHAPE_NS = Block.box(0, 0, 6, 16, 15, 10);
    private static final VoxelShape SHAPE_EW = Block.box(6, 0, 0, 10, 15, 16);

    public OmikujiRackBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(TIED, 0));
    }

    @Override
    public MapCodec<OmikujiRackBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TIED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_EW : SHAPE_NS;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(FoxWildsBlocks.OMIKUJI.get())) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (!level.isClientSide()) {
            stack.consume(1, player);
            level.setBlock(pos, state.setValue(TIED, Math.min(3, state.getValue(TIED) + 1)), Block.UPDATE_CLIENTS);
            player.removeEffect(MobEffects.UNLUCK);
            level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, 0.8f, 0.9f);
        }
        return InteractionResult.SUCCESS;
    }
}
