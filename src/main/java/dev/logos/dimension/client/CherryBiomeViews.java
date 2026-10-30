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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** 灵狐樱野实机截图（本地开发用，不提交）：在云海维度里扫到该群系，传送到山脊拍一张。 */
@LDLRegisterClient(name = "cherry_biome_views", group = "cherry_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class CherryBiomeViews implements UIScenario {
    private static final ResourceKey<Biome> CHERRY =
        ResourceKey.create(Registries.BIOME, Identifier.parse("logos_dimension:cloudsea_white_cherry"));

    @Override public void configure(ScenarioOptions o) {
        o.tags("cherry_dev").defaultTimeoutMs(300000).scenarioTimeoutMs(420000);
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
                        var holder = level.getBiome(pos);
                        if (holder.is(CHERRY)) { found = pos; break outer; }
                    }
                }
                if (found == null) throw new IllegalStateException("没扫到灵狐樱野");
                // 找一片更高的山脊：同一群系里抓几个点，取地表最高的那个
                var best = found; int bestY = -999;
                for (int dx = -320; dx <= 320; dx += 64) for (int dz = -320; dz <= 320; dz += 64) {
                    var p = found.offset(dx, 0, dz);
                    var h = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, p.getX(), p.getZ());
                    if (!level.getBiome(p).is(CHERRY)) continue;
                    if (h > bestY) { bestY = h; best = p; }
                }
                System.out.println("[cherry] biome=" + found + " peak=" + best + " y=" + bestY);
                // 先站高空让区块生成，真正的地表高度要等区块好了才有意义
                var player = ctx.player();
                DimensionTravel.teleport(player, level,
                    new Vec3(best.getX() + .5, 220, best.getZ() + .5), 135f, 20f);
                player.setNoGravity(true);
                player.setDeltaMovement(Vec3.ZERO);
                player.setNoGravity(true);
                player.setDeltaMovement(Vec3.ZERO);
                System.out.println("[cherry] found biome at " + found);
            })
            .server("place", ctx -> {
                var level2 = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var pos = ctx.player().blockPosition();
                int h = level2.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
                DimensionTravel.teleport(ctx.player(), level2,
                    new Vec3(pos.getX() + .5, Math.min(h + 2.2, 300), pos.getZ() + .5), 135f, 22f);
            })
            .ticks(120)
            .ticks(240)
            .repeat(2, b -> b.ticks(60).screenshot("cherry_biome"))
            .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }
}
