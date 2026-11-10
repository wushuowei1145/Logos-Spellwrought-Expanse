package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.LogosDimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 远行大陆的群系普查：不生成区块，直接用群系源和气候采样器取点，写出群系编号和五个气候值，
 * 离线算每个群系连成片的大小、看哪个气候轴把群系切碎。两档：8192×8192 格每 16 格一点（看细节），
 * 32768×32768 格每 64 格一点（文件名带 _wide，看大尺度上一个群系平均多大、多久遇到一片）。
 * 云岫峰林里每四个点再按噪声算一次地表高度（不生成区块），写到 cloudsea_census_heights，看石柱底下是不是大山。
 */
@LDLRegisterClient(name = "cloudsea_biome_census", group = "cloud_peaks_census", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class CloudseaBiomeCensus implements UIScenario {
    private static final int SIZE = 512, Y = 100;

    @Override public void configure(ScenarioOptions options) {
        options.tags("cloud_peaks_census").defaultTimeoutMs(600000).scenarioTimeoutMs(900000);
    }

    @Override public void define(ScenarioBuilder scenario) {
        scenario.setGameMode(GameType.CREATIVE)
            .server("普查群系与气候", ctx -> {
                ServerLevel level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                census(level, 16, "");
                census(level, 64, "_wide");
            });
    }

    /** 以原点为中心、每 step 格取一点，取 SIZE×SIZE 点，写到游戏目录下。 */
    private static void census(ServerLevel level, int step, String suffix) {
        var source = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        Map<String, Integer> ids = new LinkedHashMap<>();
        var biomes = new StringBuilder();
        var climate = new StringBuilder();
        var heights = new StringBuilder();
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        int qy = QuartPos.fromBlock(Y);
        for (int iz = 0; iz < SIZE; iz++) {
            for (int ix = 0; ix < SIZE; ix++) {
                int qx = QuartPos.fromBlock((ix - SIZE / 2) * step), qz = QuartPos.fromBlock((iz - SIZE / 2) * step);
                String id = source.getNoiseBiome(qx, qy, qz, sampler).unwrapKey().map(k -> k.identifier().getPath()).orElse("?");
                biomes.append(ids.computeIfAbsent(id, k -> ids.size())).append(ix + 1 < SIZE ? "," : "");
                Climate.TargetPoint p = sampler.sample(qx, qy, qz);
                if (id.equals("cloudsea_cloud_peaks") && (ix + iz) % 4 == 0) {
                    int x = (ix - SIZE / 2) * step, z = (iz - SIZE / 2) * step;
                    heights.append(String.format(Locale.ROOT, "%d,%d,%d,%.3f,%.3f%n", x, z,
                        generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random),
                        Climate.unquantizeCoord(p.continentalness()), Climate.unquantizeCoord(p.erosion())));
                }
                climate.append(String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f %.3f",
                    Climate.unquantizeCoord(p.temperature()), Climate.unquantizeCoord(p.humidity()), Climate.unquantizeCoord(p.continentalness()),
                    Climate.unquantizeCoord(p.erosion()), Climate.unquantizeCoord(p.weirdness()))).append(ix + 1 < SIZE ? "," : "");
            }
            biomes.append(System.lineSeparator());
            climate.append(System.lineSeparator());
        }
        var legend = new StringBuilder();
        ids.forEach((name, index) -> legend.append(index).append(' ').append(name).append(System.lineSeparator()));
        try {
            Files.writeString(Path.of("cloudsea_biomes" + suffix + ".csv"), biomes);
            Files.writeString(Path.of("cloudsea_climate" + suffix + ".csv"), climate);
            Files.writeString(Path.of("cloudsea_legend" + suffix + ".txt"), legend);
            Files.writeString(Path.of("cloudsea_census_heights" + suffix + ".csv"), heights);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        System.out.println("CLOUDSEA census" + suffix + " " + ids.size() + " biomes written to " + Path.of("").toAbsolutePath());
    }
}
