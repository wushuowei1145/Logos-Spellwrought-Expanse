package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** The dimension remains, with no generated cloud meshes, cloud worker or cloud fog. */
public abstract class OpenSkyViews implements UIScenario {
    protected abstract Vec3 camera();
    protected abstract String shot();
    @Override public void configure(ScenarioOptions o){o.tags("open_sky").defaultTimeoutMs(60_000).scenarioTimeoutMs(90_000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("sky camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(10);mc.options.broadcastOptions();})
            .server("enter the preserved dimension",ctx->{var p=ctx.player();var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);DimensionTravel.teleport(p,level,camera(),115,24);p.getAbilities().flying=true;p.onUpdateAbilities();})
            .waitUntil("dimension received",ctx->Minecraft.getInstance().level.dimension().equals(LogosDimension.CLOUDSEA))
            .ticks(100).checkServer("former cloud layer stays empty",ctx->ctx.player().level().isEmptyBlock(BlockPos.containing(camera())))
            .check("no cloud mesh worker",ctx->Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("logos-cloud-mesh")))
            .screenshot(shot()).teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="open_sky_above",group="open_sky",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Upper extends OpenSkyViews {protected Vec3 camera(){return new Vec3(210,487,100);}protected String shot(){return "sky_without_clouds";}}
    @LDLRegisterClient(name="open_sky_middle",group="open_sky",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Middle extends OpenSkyViews {protected Vec3 camera(){return new Vec3(320,408,80);}protected String shot(){return "former_cloud_layer";}}
    @LDLRegisterClient(name="open_sky_whale",group="open_sky",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Whale extends CloudseaViews.Sky {}
}
