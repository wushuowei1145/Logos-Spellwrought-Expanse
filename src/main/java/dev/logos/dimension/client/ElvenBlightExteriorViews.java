package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.corruption.Corruption;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;

/** 在真实腐败群系观察地表，不用临时填块伪造覆盖。 */
@LDLRegisterClient(name="elven_blight_exterior",group="elven_blight",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public class ElvenBlightExteriorViews implements UIScenario {
    private net.minecraft.core.BlockPos at;
    @Override public void configure(ScenarioOptions o) { o.tags("elven_blight").defaultTimeoutMs(360000).scenarioTimeoutMs(600000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("difficulty peaceful")
            .step("观察污染林地", ctx -> { var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(10);mc.options.fov().set(80);mc.options.broadcastOptions(); })
            .server("定位实际腐败地表", ctx -> {
                var world=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                at=NativeWorlds.locateBiome(world,"blighted_elven_forest");
                DimensionTravel.teleport(ctx.player(),world,at.getBottomCenter().add(0,7,0),35,16);
                world.getWeatherData().setRaining(false);
            }).waitUntil("腐败方块到达客户端",ctx->{
                var mc=Minecraft.getInstance();
                return at!=null&&mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.getBlockState(at.below()).getBlock() instanceof dev.logos.dimension.elven.blight.BlightMemoryBlock;
            }).ticks(160)
            .checkServer("确为腐败群系",ctx->ctx.player().level().getBiome(at).is(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,LogosDimension.id("blighted_elven_forest"))))
            .screenshot("corruption_covered_forest")
            .teardown("恢复界面",ctx->Minecraft.getInstance().options.hideGui=false);
    }
}
