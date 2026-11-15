package dev.logos.dimension.elven.blight;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jspecify.annotations.Nullable;

/** 世界树中央的巨型腐败核心；战斗标记不可在生存模式提前挖掉。 */
public class QueenHeartBlock extends Block implements EntityBlock {
    public static final net.minecraft.world.level.block.state.properties.EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
    public static final MapCodec<QueenHeartBlock> CODEC = simpleCodec(QueenHeartBlock::new);
    public static final BooleanProperty AWAKENED = BooleanProperty.create("awakened"), DEFEATED = BooleanProperty.create("defeated");
    public QueenHeartBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(AWAKENED, false).setValue(DEFEATED, false).setValue(FACING, Direction.SOUTH));
    }
    @Override protected MapCodec<QueenHeartBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(AWAKENED, DEFEATED, FACING); }
    // 击败标记保留用于阻止重生，但不留下看不见的悬空碰撞。
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState s, net.minecraft.world.level.BlockGetter world, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        return s.getValue(DEFEATED) ? net.minecraft.world.phys.shapes.Shapes.empty() : super.getShape(s, world, pos, context);
    }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(BlockState s, net.minecraft.world.level.BlockGetter world, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        return s.getValue(DEFEATED) ? net.minecraft.world.phys.shapes.Shapes.empty() : super.getCollisionShape(s, world, pos, context);
    }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.INVISIBLE; }
    @Override protected BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) { return rotate(state, mirror.getRotation(state.getValue(FACING))); }
    @Override public BlockEntity newBlockEntity(BlockPos p, BlockState s) { return new QueenHeart(p, s); }
    @Override public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> t) {
        return l.isClientSide() || t != ElvenBlight.HEART_ENTITY.get() ? null : (world, pos, state, be) -> ((QueenHeart) be).tick();
    }
}
