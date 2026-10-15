package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public abstract class CloudVolumeViews implements UIScenario {
    protected abstract Vec3 camera();
    protected float pitch(){return 24;}protected float yaw(){return 115;}
    protected abstract String shot();
    @Override public void configure(ScenarioOptions o){o.tags("clouds").defaultTimeoutMs(90_000).scenarioTimeoutMs(130_000);}
    @Override public void define(ScenarioBuilder steps){
        steps.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
                .step("set real cloud camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(12);mc.options.broadcastOptions();})
                .server("fly to world-anchored cloud layer",ctx->{var p=ctx.player();var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);DimensionTravel.teleport(p,level,camera(),yaw(),pitch());p.getAbilities().flying=true;p.onUpdateAbilities();})
                .waitUntil("cloud mesh background jobs completed",ctx->Minecraft.getInstance().level.dimension().equals(LogosDimension.CLOUDSEA)&&CloudseaCloudRenderer.readyMeshes()>=40)
                .ticks(50).checkServer("clouds added no solid block at camera",ctx->ctx.player().level().isEmptyBlock(BlockPos.containing(camera())))
                .screenshot(shot());
        extra(steps);
        steps.teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    protected void extra(ScenarioBuilder steps){}
    @LDLRegisterClient(name="cloud_volume_upper",group="cloud_volume",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Upper extends CloudVolumeViews {protected Vec3 camera(){return new Vec3(210,487,100);}protected String shot(){return "cloud_sea_above";}}
    @LDLRegisterClient(name="cloud_volume_inside",group="cloud_volume",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Inside extends CloudVolumeViews {
        protected Vec3 camera(){return new Vec3(320,408,80);}protected float pitch(){return 0;}protected String shot(){return "within_cloud";}
        protected void extra(ScenarioBuilder steps){steps.server("leave the permeable cloud",ctx->DimensionTravel.teleport(ctx.player(),ctx.player().level(),new Vec3(320,480,80),115,32)).ticks(40).checkServer("no cloud collision trapped the player",ctx->ctx.player().getY()>470).screenshot("outside_cloud");}
    }
    @LDLRegisterClient(name="cloud_volume_cleft",group="cloud_volume",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Cleft extends CloudseaViews.Sky {}
}
