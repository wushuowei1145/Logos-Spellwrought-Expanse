package dev.logos.dimension.dawnvow;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.item.LongswordItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * 石中剑的石座。插着剑（{@link Dawnvow#STONE}）时按住右键拔剑，见 {@link SwordStoneBlockEntity#tug}；
 * 空石座（{@link Dawnvow#EMPTY_STONE}）手持曦誓右键把剑插回去。朝向是剑面朝的方向。
 */
public class SwordStoneBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final MapCodec<SwordStoneBlock> HELD_CODEC = simpleCodec(p -> new SwordStoneBlock(p, true));
    private static final MapCodec<SwordStoneBlock> EMPTY_CODEC = simpleCodec(p -> new SwordStoneBlock(p, false));
    /** 一块不规则的石头，顶上一圈小石冠，剑从石冠中央插下去；剑身那一截也能点中。 */
    private static final VoxelShape SHAPE = Shapes.or(Block.box(1, 0, 1, 15, 8, 15), Block.box(4, 8, 4, 12, 10, 12));
    private static final VoxelShape OUTLINE = Shapes.or(SHAPE, Block.box(6.5, 10, 6.5, 9.5, 16, 9.5));
    private final boolean held;

    public SwordStoneBlock(Properties properties, boolean held) {
        super(properties);
        this.held = held;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public boolean held() {
        return held;
    }

    @Override
    public MapCodec<SwordStoneBlock> codec() {
        return held ? HELD_CODEC : EMPTY_CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return held ? OUTLINE : SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (held || !stack.is(LongswordItem.DAWNVOW.get())) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        // 剑插回石中：保留这把剑本身（附魔、耐久、名字）
        ItemStack sword = stack.copyWithCount(1);
        stack.consume(1, player);
        level.setBlock(pos, Dawnvow.STONE.get().defaultBlockState().setValue(FACING, state.getValue(FACING)), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof SwordStoneBlockEntity stone) stone.setSword(sword);
        level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.5f, 1.6f);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1f, 0.8f);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!held || !(level.getBlockEntity(pos) instanceof SwordStoneBlockEntity stone)) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server) stone.tug(server);
        // 按住右键时原版每 4 刻再点一次：挥手就是在使劲往上拔
        return InteractionResult.SUCCESS;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return held ? new SwordStoneBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!held || type != Dawnvow.STONE_ENTITY.get()) return null;
        return level.isClientSide()
            ? (l, p, s, be) -> ((SwordStoneBlockEntity) be).clientTick()
            : (l, p, s, be) -> ((SwordStoneBlockEntity) be).serverTick();
    }
}
