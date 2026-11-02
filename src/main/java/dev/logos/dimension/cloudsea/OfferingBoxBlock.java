package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 赛钱箱：木箱顶面一排斜格栅。手持金粒、铁粒或绿宝石右键投进去，叮当一声。 */
public class OfferingBoxBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<OfferingBoxBlock> CODEC = simpleCodec(OfferingBoxBlock::new);
    private static final VoxelShape SHAPE_NS = Block.box(1, 0, 3, 15, 10, 13);
    private static final VoxelShape SHAPE_EW = Block.box(3, 0, 1, 13, 10, 15);

    public OfferingBoxBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<OfferingBoxBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
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
        boolean coin = stack.is(Items.GOLD_NUGGET) || stack.is(Items.IRON_NUGGET) || stack.is(Items.EMERALD) || stack.is(ItemTags.COPPER);
        if (!coin) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (level instanceof ServerLevel server) {
            stack.consume(1, player);
            server.playSound(null, pos, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.7f, 1.6f);
            server.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.5f, 1.4f);
            server.sendParticles(ParticleTypes.WAX_ON, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 4, 0.2, 0.05, 0.2, 0.01);
        }
        return InteractionResult.SUCCESS;
    }
}
