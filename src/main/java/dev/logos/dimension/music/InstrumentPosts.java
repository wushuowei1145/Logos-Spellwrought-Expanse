package dev.logos.dimension.music;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 乐器的工位（docs/ORCHESTRA.md 第 15 节）：大提琴琴后的凳子、低音提琴琴后、钟琴和管钟前面站的地方。
 * 坐（站）在工位上的人或生物就是这件乐器的演奏者，用 {@link InstrumentSeat} 留在那里（生物连同座位存盘）。
 * 玩家空手右键大提琴、低音提琴坐上去（{@link SeatedInstrumentBlock}）；生物用拴绳牵着右键乐器带上工位，潜行空手右键乐器让它离开。
 * 定音鼓不在这里：坐在鼓后关联的琴凳上（{@link StarbloomBenchBlock}）。
 */
public final class InstrumentPosts {
    private InstrumentPosts() {}

    /** 这种乐器有没有工位。 */
    public static boolean has(@Nullable InstrumentKind kind) {
        return kind == InstrumentKind.CELLO || kind == InstrumentKind.DOUBLE_BASS
            || kind == InstrumentKind.GLOCKENSPIEL || kind == InstrumentKind.TUBULAR_BELLS;
    }

    /** 站着演奏（腿伸直、不坐）。 */
    public static boolean standing(InstrumentKind kind) {
        return kind != InstrumentKind.CELLO;
    }

    /**
     * 演奏者双脚在朝北放置的方块模型里的位置（像素；x 向东、y 向上、z 向南）。大提琴、低音提琴的人在琴后（南面，面朝北），
     * 钟琴、管钟的人在前面（北面，面朝南）——同 tools/instrument_art.py 里各自的画法。
     */
    public static Vec3 feet(InstrumentKind kind) {
        return switch (kind) {
            case GLOCKENSPIEL -> new Vec3(8, 0, -6);
            case TUBULAR_BELLS -> new Vec3(8, 0, -5);
            default -> SeatedInstrumentBlock.feet(kind);
        };
    }

    /** 演奏者面朝的方向：大提琴、低音提琴同乐器的 FACING（琴正面朝北、人在南面）；钟琴、管钟相反（人在北面朝南）。 */
    public static Direction performerFacing(InstrumentKind kind, Direction facing) {
        return kind == InstrumentKind.GLOCKENSPIEL || kind == InstrumentKind.TUBULAR_BELLS ? facing.getOpposite() : facing;
    }

    /** 工位在世界里的位置：模型坐标按方块朝向转过去（同方块状态的 y 旋转）。 */
    public static Vec3 position(BlockPos main, Direction facing, InstrumentKind kind) {
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

    /** 工位上有人的座位；没人时为 null。 */
    public static @Nullable InstrumentSeat seat(Level level, BlockPos main) {
        for (InstrumentSeat s : level.getEntitiesOfClass(InstrumentSeat.class, new AABB(main).inflate(2),
                s -> s.instrument().equals(main) && !s.isRemoved() && !s.getPassengers().isEmpty())) {
            return s;
        }
        return null;
    }

    public static @Nullable Entity occupant(Level level, BlockPos main) {
        InstrumentSeat s = seat(level, main);
        return s == null ? null : s.getFirstPassenger();
    }

    /**
     * 让 who 到 main 处乐器的工位上（服务端）。生物到了工位不再走动、不会被刷掉；同伴以后找空工位时也调这里。
     * @return 到了为 true（不是有工位的乐器、已有人时为 false）
     */
    public static boolean take(Level level, BlockPos main, Entity who) {
        if (level.isClientSide()) return false;
        BlockState state = level.getBlockState(main);
        if (!(state.getBlock() instanceof InstrumentBlock block) || !block.isMain(state)) return false;
        InstrumentKind kind = block.kind(state);
        if (!has(kind) || occupant(level, main) != null) return false;
        if (who.isPassenger()) who.stopRiding();
        InstrumentSeat seat = Instruments.SEAT.get().create(level, EntitySpawnReason.TRIGGERED);
        if (seat == null) return false;
        Direction facing = state.getValue(InstrumentBlock.FACING);
        seat.bind(main, position(main, facing, kind), performerFacing(kind, facing).toYRot());
        level.addFreshEntity(seat);
        if (!who.startRiding(seat, true, true)) {
            seat.discard();
            return false;
        }
        if (who instanceof Mob mob) {
            mob.setPersistenceRequired();
            mob.getNavigation().stop();
        }
        return true;
    }

    /** 让工位上的生物离开（玩家自己潜行起身）；没有生物时为 false。 */
    public static boolean leave(Level level, BlockPos main) {
        Entity who = occupant(level, main);
        if (!(who instanceof Mob)) return false;
        who.stopRiding();
        return true;
    }

    /** 拿着拴绳右键乐器：拴在这个玩家手上、离他最近的生物到工位上，拴绳还给玩家。牵着生物时为 true（不管工位是否空着）。 */
    public static boolean leadTo(Level level, BlockPos main, Player player) {
        Mob led = StarbloomBenchBlock.nearestLed(player);
        if (led == null) return false;
        if (!level.isClientSide() && occupant(level, main) == null) {
            led.removeLeash();
            if (!player.getAbilities().instabuild) player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEAD));
            take(level, main, led);
        }
        return true;
    }
}
