package dev.logos.dimension.music;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 只占一格的乐器（钟琴、钢片琴）：FACING 朝着放置的玩家；碰撞形状按 FACING=north 给出，其余朝向自动旋转。 */
public class SingleInstrumentBlock extends InstrumentBlock {
    private final InstrumentKind kind;
    private final Map<Direction, VoxelShape> shapes;

    public SingleInstrumentBlock(Properties properties, InstrumentKind kind, VoxelShape north) {
        super(properties);
        this.kind = kind;
        this.shapes = Shapes.rotateHorizontal(north);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public InstrumentKind kind() {
        return kind;
    }

    @Override
    public boolean isMain(BlockState state) {
        return true;
    }

    @Override
    public BlockPos mainPos(BlockState state, BlockPos pos) {
        return pos;
    }

    @Override
    public Iterable<BlockPos> cells(BlockState mainState, BlockPos main) {
        return List.of(main);
    }

    @Override
    protected Vec3 centerOf(BlockState mainState, BlockPos main) {
        return Vec3.atCenterOf(main);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state.getValue(FACING));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
