package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.CloudseaBiome;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** 灵狐樱野的树形预览（本地开发用，不提交）。 */
@LDLRegisterClient(name = "cherry_tree_views", group = "cherry_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class CherryTreeViews implements UIScenario {
    private static final int Y = 230;
    private static final BlockPos CENTER = new BlockPos(1000600, Y, 1000000);

    @Override public void configure(ScenarioOptions o) {
        o.tags("cherry_dev").defaultTimeoutMs(240000).scenarioTimeoutMs(360000);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("difficulty peaceful").runCommand("time set noon")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.renderDistance().set(8);
                mc.options.fov().set(70);
            })
            .server("stage", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, ctx.server());
                stage(level);
                var from = CENTER.offset(-30, -2, -30);
                var to = CENTER.offset(30, 30, 30);
                ctx.server().getCommands().performPrefixedCommand(
                    ctx.server().createCommandSourceStack().withLevel(level),
                    "fillbiome %d %d %d %d %d %d logos_dimension:cloudsea_white_cherry".formatted(
                        from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ()));
                var player = ctx.player();
                DimensionTravel.teleport(player, level,
                    new Vec3(CENTER.getX() + .5, Y + 9, CENTER.getZ() + 20.5), 180f, 14f);
                player.setNoGravity(true);
                player.setDeltaMovement(Vec3.ZERO);
            })
            .ticks(60)
            .screenshot("tree")
            .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    private void stage(ServerLevel level) {
        level.getChunkAt(CENTER);
        var turf = CloudseaBlocks.GROUND.get(CloudseaBiome.WHITE_CHERRY).get().defaultBlockState();
        var log = CloudseaBlocks.TRUNKS.get(CloudseaBiome.WHITE_CHERRY).get().defaultBlockState();
        var leaf = CloudseaBlocks.LEAVES.get(CloudseaBiome.WHITE_CHERRY).get().defaultBlockState();
        for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) {
            level.setBlock(CENTER.offset(x, 0, z), turf, Block.UPDATE_ALL);
            for (int y = 1; y <= 24; y++)
                level.setBlock(CENTER.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        // 树干：7 格，4-5 格处伸出两条斜枝
        for (int y = 1; y <= 7; y++) level.setBlock(CENTER.offset(0, y, 0), log, Block.UPDATE_ALL);
        int[][] branches = {{1, 5, 0}, {2, 6, 0}, {3, 7, 0}, {-1, 5, 0}, {-2, 6, 0}, {-3, 7, 0},
                            {0, 5, 1}, {0, 6, 2}, {0, 7, 3}, {0, 5, -1}, {0, 6, -2}, {0, 7, -3}};
        for (var b : branches) level.setBlock(CENTER.offset(b[0], b[1], b[2]), log, Block.UPDATE_ALL);
        // 树冠：樱花那种伞形，宽 9、高 5
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = 5; y <= 11; y++) {
            double dx = x, dz = z, dy = (y - 8) * 1.35;
            double r = Math.sqrt(dx * dx + dz * dz + dy * dy);
            if (r <= 5.1 + level.getRandom().nextDouble() * .5)
                level.setBlock(CENTER.offset(x, y, z), leaf, Block.UPDATE_ALL);
        }
    }
}
