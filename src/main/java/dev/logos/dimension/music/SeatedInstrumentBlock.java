package dev.logos.dimension.music;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 坐着拉的弦乐器（大提琴、低音提琴）：两格高，琴后面是演奏者的位置（大提琴有凳子，低音提琴站着拉）。
 * 空手右键：坐到琴后面（{@link InstrumentSeat}）并打开乐器界面；已经有人坐着就只打开界面；潜行右键只打开界面不坐下。
 * 潜行起身。没人坐时它和其他方块乐器一样，红石、纸带照常演奏。
 */
public class SeatedInstrumentBlock extends TallInstrumentBlock {
    public SeatedInstrumentBlock(Properties properties, InstrumentKind kind, VoxelShape north) {
        super(properties, kind, north);
    }

    /**
     * 演奏者双脚在朝北放置的方块模型里的位置（像素；x 向东、y 向上、z 向南，琴的正面朝北）：
     * 大提琴的人坐在琴后的凳子上，脚底比地面略低（坐姿的大腿正好压在凳面上）；低音提琴的人站在琴后。
     * 与 tools/instrument_art.py 的 SEATED（"feet"）、客户端的 HeldRig.seated 一致。
     */
    public static Vec3 feet(InstrumentKind kind) {
        return kind == InstrumentKind.DOUBLE_BASS ? new Vec3(8, 0, 11.5) : new Vec3(8, -2.25, 12.5);
    }

    /** 座位在世界里的位置：模型坐标按方块朝向转过去（同方块状态的 y 旋转，从上往下看顺时针）。 */
    public static Vec3 seatPosition(BlockPos main, Direction facing, InstrumentKind kind) {
        Vec3 f = feet(kind);
        double dx = f.x / 16 - 0.5, dz = f.z / 16 - 0.5;
        double rx, rz;
        switch (facing) {
            case EAST -> { rx = -dz; rz = dx; }
            case SOUTH -> { rx = -dx; rz = -dz; }
            case WEST -> { rx = dz; rz = -dx; }
            default -> { rx = dx; rz = dz; }
        }
        return new Vec3(main.getX() + 0.5 + rx, main.getY() + f.y / 16, main.getZ() + 0.5 + rz);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos main = mainPos(state, pos);
        if (!level.isClientSide() && !player.isSecondaryUseActive()
            && !(player.getVehicle() instanceof InstrumentSeat seat && seat.instrument().equals(main))) {
            boolean taken = !level.getEntitiesOfClass(InstrumentSeat.class, new AABB(main).inflate(2), s -> s.instrument().equals(main)).isEmpty();
            BlockState mainState = level.getBlockState(main);
            if (!taken && mainState.getBlock() == this) {
                InstrumentSeat seat = Instruments.SEAT.get().create(level, EntitySpawnReason.TRIGGERED);
                if (seat != null) {
                    Direction facing = mainState.getValue(FACING);
                    seat.bind(main, seatPosition(main, facing, kind()), facing.toYRot());
                    level.addFreshEntity(seat);
                    player.startRiding(seat);
                }
            }
        }
        // 客户端照常打开乐器界面
        return super.useWithoutItem(state, level, pos, player, hit);
    }
}
