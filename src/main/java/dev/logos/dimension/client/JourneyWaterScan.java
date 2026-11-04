package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.LogosDimension;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;

/** 诊断用：在远行大陆按网格采样地表，统计河/湖/海等水体分布。 */
@LDLRegisterClient(name = "journey_water_scan", group = "diagnostics", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class JourneyWaterScan implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.defaultTimeoutMs(300000).scenarioTimeoutMs(600000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
            .server("scan surface water", ctx -> {
                ServerLevel level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var gen = level.getChunkSource().getGenerator();
                var random = level.getChunkSource().randomState();
                int step = 64, radius = 2048;
                int total = 0, water = 0, minY = 9999, maxY = -9999, minWaterY = 9999, maxWaterY = -9999;
                Map<String, Integer> byBiome = new TreeMap<>();
                StringBuilder samples = new StringBuilder();
                for (int x = -radius; x <= radius; x += step) {
                    for (int z = -radius; z <= radius; z += step) {
                        int surf = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                        int floor = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                        total++;
                        minY = Math.min(minY, surf);
                        maxY = Math.max(maxY, surf);
                        if (surf > floor) {
                            water++;
                            minWaterY = Math.min(minWaterY, floor);
                            maxWaterY = Math.max(maxWaterY, surf);
                            String biome = gen.getBiomeSource()
                                .getNoiseBiome(x >> 2, ((floor + surf) / 2) >> 2, z >> 2, random.sampler())
                                .unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                            byBiome.merge(biome, 1, Integer::sum);
                            if (samples.length() < 600) {
                                samples.append(" (").append(x).append(",").append(z).append(" y=")
                                    .append(floor).append("..").append(surf).append(" ").append(biome).append(")");
                            }
                        }
                    }
                }
                System.out.println("WATER_SCAN total=" + total + " water=" + water
                    + " surfaceY=[" + minY + "," + maxY + "] waterY=[" + minWaterY + "," + maxWaterY + "]");
                System.out.println("WATER_SCAN biomes=" + byBiome);
                System.out.println("WATER_SCAN samples=" + samples);
            })
            .check("scan finished", ctx -> true);
    }
}
