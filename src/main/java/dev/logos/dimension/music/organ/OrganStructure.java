package dev.logos.dimension.music.organ;

import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.Instruments;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 管风琴的结构识别：从演奏台的四格出发，沿相连的风箱找过去（上下左右前后都算，风箱可以一级级往上接到高处的管墙；离演奏台最远 24 格），
 * 再数每个风箱正上方叠着的音管（每根最高 32 格），总共最多 256 格。服务端和客户端各自识别，
 * 结果在方块实体里缓存 2 秒（见 {@code InstrumentBlockEntity#organ()}）。
 */
public record OrganStructure(int chests, int tin, int wood, int reed, Vec3 center) {
    public static final int MAX_BLOCKS = 256;
    public static final int RADIUS = 24;
    public static final int MAX_PIPE_HEIGHT = 32;

    public int pipes() {
        return tin + wood + reed;
    }

    /** 这套结构能开的音栓。 */
    public int availableStops() {
        return OrganStop.available(wood > 0, tin > 0, reed > 0);
    }

    /** 可闻距离（格）：16 + 每根音管 1 格，最多 64。 */
    public int range() {
        return Math.min(64, 16 + pipes());
    }

    public static OrganStructure scan(BlockGetter level, BlockState consoleState, BlockPos main) {
        Vec3 consoleCenter = InstrumentBlock.center(consoleState, main);
        if (!(consoleState.getBlock() instanceof InstrumentBlock console)) return new OrganStructure(0, 0, 0, 0, consoleCenter);
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos cell : console.cells(consoleState, main)) {
            seen.add(cell.immutable());
            for (Direction d : Direction.values()) queue.add(cell.relative(d));
        }
        int chests = 0, tin = 0, wood = 0, reed = 0, budget = MAX_BLOCKS;
        double sx = 0, sy = 0, sz = 0;
        while (!queue.isEmpty() && budget > 0) {
            BlockPos p = queue.poll();
            if (!seen.add(p)) continue;
            if (Math.abs(p.getX() - main.getX()) > RADIUS || Math.abs(p.getY() - main.getY()) > RADIUS
                    || Math.abs(p.getZ() - main.getZ()) > RADIUS) continue;
            if (!level.getBlockState(p).is(Instruments.ORGAN_WIND_CHEST.get())) continue;
            chests++;
            budget--;
            // 风箱正上方的音管
            BlockPos.MutableBlockPos up = p.mutable();
            for (int h = 0; h < MAX_PIPE_HEIGHT && budget > 0; h++) {
                up.move(Direction.UP);
                BlockState s = level.getBlockState(up);
                if (s.is(Instruments.ORGAN_TIN_PIPE.get())) tin++;
                else if (s.is(Instruments.ORGAN_WOOD_PIPE.get())) wood++;
                else if (s.is(Instruments.ORGAN_TRUMPET_PIPE.get())) reed++;
                else break;
                budget--;
                sx += up.getX() + 0.5;
                sy += up.getY() + 0.5;
                sz += up.getZ() + 0.5;
            }
            // 上面也要找：风箱叠起来往上接（音乐厅里从演奏台升到管墙底座的那一段）；音管不是风箱，找过去也不算
            for (Direction d : Direction.values()) queue.add(p.relative(d));
        }
        int pipes = tin + wood + reed;
        Vec3 center = pipes == 0 ? consoleCenter : new Vec3(sx / pipes, sy / pipes, sz / pipes);
        return new OrganStructure(chests, tin, wood, reed, center);
    }
}
