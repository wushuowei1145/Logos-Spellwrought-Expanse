package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import dev.logos.dimension.gametest.TallTreeGameTests;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** DEV-only native feature gallery and naturally generated giant conifer forest. */
public abstract class TallTreeViews implements UIScenario {
    private BlockPos at;private int observedHeight;
    protected boolean natural(){return false;}
    @Override public void configure(ScenarioOptions o){o.tags("tall_trees").defaultTimeoutMs(240000).scenarioTimeoutMs(360000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("tree panorama camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.fov().set(60);mc.options.renderDistance().set(10);mc.options.broadcastOptions();})
            .server("choose native trees",ctx->{
                var l=ctx.server().getLevel(LogosDimension.CLOUDSEA);Vec3 camera;float yaw,pitch;
                if(natural()){
                    var tree=TallTreeGameTests.findNatural(l);at=tree.base();observedHeight=tree.height();camera=at.getBottomCenter().add(28,tree.height()*.55,35);yaw=141.34f;pitch=3;
                }else{
                    TallTreeGameTests.placeStudy(l);at=TallTreeGameTests.STUDY.below();observedHeight=0;camera=at.getBottomCenter().add(0,17,50);yaw=180;pitch=2;
                }
                l.clockManager().setTotalTicks(l.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),6000);l.getWeatherData().setRaining(false);l.getWeatherData().setThundering(false);
                DimensionTravel.teleport(ctx.player(),l,camera,yaw,pitch);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            }).waitUntil("native tree chunks visible",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.hasChunk(at.getX()>>4,at.getZ()>>4);}).ticks(160)
            .checkServer("world still uses native noise",ctx->NativeWorlds.vanilla(ctx.player().level()))
            .check("tree scene received",ctx->!Minecraft.getInstance().level.getBlockState(at).isAir())
            .checkServer("natural trees exceed ordinary fir height",ctx->!natural()||observedHeight>=22)
            .check("camera outside geometry",ctx->{var mc=Minecraft.getInstance();var p=BlockPos.containing(mc.player.getEyePosition());return mc.level.getBlockState(p).getCollisionShape(mc.level,p).isEmpty();})
            .screenshot(natural()?"natural_tall_fir_canopy":"native_tall_broadleaf_and_fir")
            .teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="tall_trees_gallery",group="tall_trees",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Gallery extends TallTreeViews{}
    @LDLRegisterClient(name="tall_trees_natural",group="tall_trees",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Natural extends TallTreeViews{protected boolean natural(){return true;}}
}
