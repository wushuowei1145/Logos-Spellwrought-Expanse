package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.ClientConfig;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBiome;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.cloudsea.JourneySeasons;
import dev.logos.dimension.wind.WindField;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** 固定近景对照草浪、接缝与拆除；不把这块测试台当作自然生成地形。 */
@LDLRegisterClient(name = "changfeng_grass_wind", group = "grass_wind", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class GrassWindView implements UIScenario {
    private static final int Y = 180;
    private boolean oldEnabled;
    private double oldAmount;
    private double coverage;
    @Override public void configure(ScenarioOptions options) { options.tags("grass_wind").defaultTimeoutMs(120000).scenarioTimeoutMs(240000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("准备草浪画面", ctx -> {
                var mc = Minecraft.getInstance();
                oldEnabled = ClientConfig.GRASS_WIND.getAsBoolean(); oldAmount = ClientConfig.GRASS_WIND_AMOUNT.getAsDouble();
                ClientConfig.GRASS_WIND.set(true); ClientConfig.GRASS_WIND_AMOUNT.set(1.0);
                WindField.setOverride(1f);
                mc.options.hideGui = true; mc.options.renderDistance().set(6); mc.options.fov().set(65); mc.options.broadcastOptions();
            })
            .server("布置长风草丛", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                for (int x = -24; x <= 24; x++) for (int z = -24; z <= 16; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, Y, z), CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get().defaultBlockState());
                    for (int y = 1; y <= 5; y++) level.setBlockAndUpdate(new BlockPos(x, Y + y, z), Blocks.AIR.defaultBlockState());

                }
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK), 6000);
                level.getWeatherData().setRaining(false); level.getWeatherData().setThundering(false);
                DimensionTravel.teleport(ctx.player(), level, new Vec3(0, Y + 3, 12), 180, 12);
                ctx.player().getAbilities().flying = true; ctx.player().onUpdateAbilities();
            })
            .runCommand("execute in logos_dimension:cloudsea run fillbiome -24 172 -24 24 179 24 logos_dimension:cloudsea_silk_folds")
            .runCommand("execute in logos_dimension:cloudsea run fillbiome -24 180 -24 24 191 24 logos_dimension:cloudsea_silk_folds")
            .server("用原版放置特征生成草地", ctx -> {
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var feature=level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.PLACED_FEATURE,LogosDimension.id("cloudsea_silk_folds_floor")));
                for(int x:new int[]{-16,0}) for(int z:new int[]{-16,0}) feature.value().placeWithBiomeCheck(level,level.getChunkSource().getGenerator(),net.minecraft.util.RandomSource.create(8931+x*31+z),new BlockPos(x,Y+1,z));
                int grass=0;
                for(int x=-16;x<16;x++) for(int z=-16;z<16;z++) {
                    var state=level.getBlockState(new BlockPos(x,Y+1,z));
                    if(state.is(Blocks.TALL_GRASS)||state.is(Blocks.SHORT_GRASS)||state.is(CloudseaBlocks.MEADOW_GRASS_YOUNG.get())) grass++;
                }
                coverage=grass/1024.0;
                System.out.println("CHANGFENG_COVERAGE "+coverage);
            })
            .checkServer("原版生成草的覆盖率约八成",ctx->coverage>=.75&&coverage<=.86)
            .waitUntil("收到长风草丛", ctx -> Minecraft.getInstance().level != null && Minecraft.getInstance().level.dimension().equals(LogosDimension.CLOUDSEA))
            .waitUntil("测试地面已完整到达", ctx -> {
                var level = Minecraft.getInstance().level;
                for (int x : new int[]{-20, 0, 20}) for (int z : new int[]{-20, 0, 12})
                    if (!level.getBlockState(new BlockPos(x, Y, z)).is(CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get())) return false;
                return true;
            }).ticks(300)
            .check("草浪已缓存并提交，面数未超过预算", ctx -> WindGrass.cachedSections() > 0 && WindGrass.lastQuads > 0 && WindGrass.lastQuads <= 12000)
            .screenshot("grass_wind_a").ticks(12).screenshot("grass_wind_b");
        for (int i=0;i<24;i++) s.ticks(1).screenshot("wind_frame_"+String.format(java.util.Locale.ROOT,"%02d",i));
        s.step("静风对照", ctx -> ClientConfig.GRASS_WIND_AMOUNT.set(0.0)).ticks(15).screenshot("grass_wind_zero")
            .server("挖去测试草", ctx -> { var p = new BlockPos(1, Y + 1, 0); ctx.player().level().removeBlock(p, false); ctx.player().level().removeBlock(p.above(), false); })
            .ticks(20)
            .check("拆除已同步且风动缓存没有残草", ctx -> {
                var p = new BlockPos(1, Y + 1, 0);
                return Minecraft.getInstance().level.getBlockState(p).isAir() && !WindGrass.cachedAt(p) && !WindGrass.cachedAt(p.above());
            })
            .step("切换静态模型", ctx -> ClientConfig.GRASS_WIND.set(false)).ticks(30)
            .check("关闭后撤销风动缓存", ctx -> WindGrass.cachedSections() == 0 && WindGrass.lastQuads == 0)
            .screenshot("grass_wind_off")
            .step("再次开启草浪", ctx -> { ClientConfig.GRASS_WIND.set(true); ClientConfig.GRASS_WIND_AMOUNT.set(1.0); }).ticks(100)
            .check("重新开启后恢复草浪", ctx -> WindGrass.cachedSections() > 0 && WindGrass.lastQuads > 0)
            .teardown("恢复画面与配置", ctx -> { ClientConfig.GRASS_WIND.set(oldEnabled); ClientConfig.GRASS_WIND_AMOUNT.set(oldAmount); WindField.setOverride(null); Minecraft.getInstance().options.hideGui = false; });
    }
}
