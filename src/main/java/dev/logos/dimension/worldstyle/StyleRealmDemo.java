package dev.logos.dimension.worldstyle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** {@code /alchemy realm demo} 用：在玩家面前搭一座带门的小院子并登记成画风区域，方便走进走出试效果。没有顶，好看到天空。 */
public final class StyleRealmDemo {
    private static final int HALF_WIDTH = 7, DEPTH = 16, HEIGHT = 7, DOOR_HALF_WIDTH = 1, DOOR_HEIGHT = 4;
    private static final Block[] PILLARS = {Blocks.RED_WOOL, Blocks.BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.MAGENTA_WOOL, Blocks.ORANGE_WOOL, Blocks.CYAN_WOOL};

    private StyleRealmDemo() {}

    public static void build(ServerPlayer player, WorldStyle style) {
        ServerLevel level = player.level();
        Direction forward = player.getDirection(), side = forward.getClockWise();
        BlockPos origin = player.blockPosition().relative(forward, 3);
        for (int d = 0; d <= DEPTH; d++) for (int s = -HALF_WIDTH; s <= HALF_WIDTH; s++) {
            BlockPos column = origin.relative(forward, d).relative(side, s);
            boolean wall = d == 0 || d == DEPTH || Math.abs(s) == HALF_WIDTH;
            level.setBlockAndUpdate(column.below(), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 0; y < HEIGHT; y++) {
                boolean opening = d == 0 && Math.abs(s) <= DOOR_HALF_WIDTH && y < DOOR_HEIGHT;
                boolean frame = d == 0 && Math.abs(s) <= DOOR_HALF_WIDTH + 1 && y <= DOOR_HEIGHT;
                Block block = !wall || opening ? Blocks.AIR : frame ? Blocks.GOLD_BLOCK : Blocks.STONE_BRICKS;
                level.setBlockAndUpdate(column.above(y), block.defaultBlockState());
            }
        }
        for (int i = 0; i < PILLARS.length; i++) for (int y = 0; y < 4; y++) {
            level.setBlockAndUpdate(origin.relative(forward, 5 + i * 2).relative(side, i % 2 == 0 ? -3 : 3).above(y), PILLARS[i].defaultBlockState());
        }
        StyleDoor door = StyleDoor.opening(origin.relative(side, -DOOR_HALF_WIDTH), origin.relative(side, DOOR_HALF_WIDTH).above(DOOR_HEIGHT - 1), forward.getOpposite());
        BlockPos near = origin.relative(side, -HALF_WIDTH), far = origin.relative(forward, DEPTH).relative(side, HALF_WIDTH).above(HEIGHT + 64);
        StyleRealms.add(level, StyleRealm.of(near, far, style, door));
    }
}
