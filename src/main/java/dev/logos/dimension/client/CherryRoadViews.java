package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** 盘山路实机预览（本地开发用）：在樱野地表手动放一次结构，原地抬升俯瞰。 */
@LDLRegisterClient(name = "cherry_road_views", group = "cherry_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class CherryRoadViews implements UIScenario {
    private static final ResourceKey<Biome> CHERRY =
        ResourceKey.create(Registries.BIOME, Identifier.parse("logos_dimension:cloudsea_white_cherry"));

    @Override public void configure(ScenarioOptions o) {
        o.tags("cherry_dev").defaultTimeoutMs(300000).scenarioTimeoutMs(480000);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("difficulty peaceful").runCommand("time set noon")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.renderDistance().set(12);
                mc.options.fov().set(75);
            })
            .server("find", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, ctx.server());
                BlockPos found = null;
                outer:
                for (int r = 0; r <= 40000; r += 96) {
                    for (int a = 0; a < 24; a++) {
                        double ang = a * Math.PI * 2 / 24;
                        var pos = new BlockPos((int) (Math.cos(ang) * r), 120, (int) (Math.sin(ang) * r));
                        if (level.getBiome(pos).is(CHERRY)) { found = pos; break outer; }
                    }
                }
                if (found == null) throw new IllegalStateException("没扫到灵狐樱野");
                DimensionTravel.teleport(ctx.player(), level,
                    new Vec3(found.getX() + .5, 220, found.getZ() + .5), 0f, 0f);
                ctx.player().setNoGravity(true);
                System.out.println("[road] cherry at " + found);
            })
            .ticks(120)
            .server("place", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var pos = ctx.player().blockPosition();
                int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
                ctx.server().getCommands().performPrefixedCommand(
                    ctx.player().createCommandSourceStack(),
                    "place structure logos_dimension:cherry_road " + pos.getX() + " " + h + " " + pos.getZ());
                DimensionTravel.teleport(ctx.player(), level,
                    new Vec3(pos.getX() + .5, h + 14, pos.getZ() + .5), 0f, 55f);
                ctx.player().setNoGravity(true);
                System.out.println("[road] placed at y=" + h);
            })
            .ticks(80)
            .repeat(2, b -> b.ticks(40).screenshot("cherry_road"))
            .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }
}
