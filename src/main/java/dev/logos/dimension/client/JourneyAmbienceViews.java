package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;

/** Staged woodland pond; all particles must come from the normal environmental emitter. */
public abstract class JourneyAmbienceViews implements UIScenario {
    private BlockPos center;
    private ParticleStatus oldSetting;
    private boolean oldHud;
    private long before,beforeGlints,checkpoint;
    protected int scene(){return 0;}
    protected int season(){return scene()==2?3:1;}
    protected String shot(){return "summer_fireflies";}
    @Override public void configure(ScenarioOptions o){o.tags("journey_ambience").defaultTimeoutMs(180000).scenarioTimeoutMs(360000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("capture settings",ctx->{
                var mc=Minecraft.getInstance();oldSetting=mc.options.particles().get();oldHud=mc.options.hideGui;
                mc.options.particles().set(ParticleStatus.ALL);mc.options.hideGui=true;mc.options.fov().set(62);mc.options.renderDistance().set(8);mc.options.broadcastOptions();
                before=JourneyAtmosphere.emitted();beforeGlints=JourneyAmbientParticle.created(AmbientMotion.Kind.GLIMMER);
            }).server("stage woodland pond without spawning particles",ctx->{
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var at=NativeWorlds.locateBiome(level,CloudseaBiome.ANCIENT_BEECH.id());center=new BlockPos(at.getX(),at.getY(),at.getZ());
                int x0=center.getX(),z0=center.getZ(),base=center.getY();
                for(int x=-18;x<=18;x++)for(int z=-18;z<=18;z++){
                    for(int y=-3;y<=0;y++)level.setBlockAndUpdate(center.offset(x,y,z),CloudseaBlocks.GROUND.get(CloudseaBiome.MOSS_VALE).get().defaultBlockState().setValue(SeasonalLeavesBlock.SEASON,season()));
                    for(int y=1;y<=22;y++)level.removeBlock(center.offset(x,y,z),false);
                    if(x*x/16.0+z*z/9.0<1)level.setBlockAndUpdate(center.offset(x,0,z),(season()==3?Blocks.ICE:Blocks.WATER).defaultBlockState());
                    else if(Math.floorMod(x*31+z*17,7)<3){
                        var block=Math.floorMod(x-z,4)==0?CloudseaBlocks.MOONBELL.get():Math.floorMod(x+z,4)==0?CloudseaBlocks.GLOWCAP.get():season()==3?CloudseaBlocks.HELLEBORE.get():CloudseaBlocks.FERN.get();
                        var state=block.defaultBlockState();if(state.hasProperty(SeasonalLeavesBlock.SEASON))state=state.setValue(SeasonalLeavesBlock.SEASON,season());
                        level.setBlockAndUpdate(center.offset(x,1,z),state);
                    }
                }
                for(int[] tree:new int[][]{{-11,-7},{-9,6},{9,-4},{12,8},{1,-12}}){
                    for(int y=1;y<=6;y++)level.setBlockAndUpdate(center.offset(tree[0],y,tree[1]),CloudseaBlocks.STEM.get().defaultBlockState());
                    for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=5;y<=7;y++)if(x*x+z*z+(y-5)*(y-5)<13){
                        var leaf=CloudseaBlocks.LEAVES.get(CloudseaBiome.ANCIENT_BEECH).get().defaultBlockState().setValue(LeavesBlock.PERSISTENT,true).setValue(LeavesBlock.DISTANCE,1).setValue(SeasonalLeavesBlock.SEASON,season());
                        level.setBlockAndUpdate(center.offset(tree[0]+x,y,tree[1]+z),leaf);
                    }
                }
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),season()*JourneySeasons.SEASON_TICKS+(scene()==1?6000:15000));
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                for(var item:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(x0-24,base-6,z0-24,x0+24,base+30,z0+24)))item.discard();
                DimensionTravel.teleport(ctx.player(),level,new Vec3(x0+.5,base+1.1,z0+10.5),180,5);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            }).waitUntil("woodland reaches client",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.hasChunk(center.getX()>>4,center.getZ()>>4);})
            .ticks(240)
            .check("natural environmental particles are active",ctx->JourneyAmbientParticle.active()>0&&JourneyAtmosphere.emitted()>before+8)
            .check("all-particles budget respected",ctx->JourneyAmbientParticle.active()<=80)
            .check("fireflies follow time and season",ctx->scene()==1||scene()==2?JourneyAmbientParticle.active(AmbientMotion.Kind.FIREFLY)==0:JourneyAmbientParticle.active(AmbientMotion.Kind.FIREFLY)>6)
            .check("plant glimmers naturally appear",ctx->JourneyAmbientParticle.created(AmbientMotion.Kind.GLIMMER)>beforeGlints)
            .step("record actual particle population",ctx->System.out.println("AMBIENCE "+shot()+" total="+JourneyAmbientParticle.active()+" fireflies="+JourneyAmbientParticle.active(AmbientMotion.Kind.FIREFLY)+" motes="+JourneyAmbientParticle.active(AmbientMotion.Kind.MOTE)+" glimmers="+JourneyAmbientParticle.active(AmbientMotion.Kind.GLIMMER)))
            .screenshot(shot());
        if(scene()==0)s.repeat(8,b->b.ticks(8).screenshot("firefly_motion"));
        if(scene()==3){
            s.step("decreased particles",ctx->Minecraft.getInstance().options.particles().set(ParticleStatus.DECREASED)).ticks(10)
                .check("decreased population budget",ctx->JourneyAmbientParticle.active()>0&&JourneyAmbientParticle.active()<=28)
                .step("minimal particles",ctx->{Minecraft.getInstance().options.particles().set(ParticleStatus.MINIMAL);checkpoint=JourneyAtmosphere.emitted();}).ticks(40)
                .check("minimal disables ambience",ctx->JourneyAmbientParticle.active()==0&&JourneyAtmosphere.emitted()==checkpoint)
                .step("restore particles",ctx->Minecraft.getInstance().options.particles().set(ParticleStatus.ALL)).ticks(120)
                .check("ambience resumes",ctx->JourneyAmbientParticle.active()>0)
                .step("native engine clear",ctx->{Minecraft.getInstance().particleEngine.clearParticles();checkpoint=JourneyAtmosphere.emitted();}).ticks(140)
                .check("native particle reset does not exhaust slots",ctx->JourneyAmbientParticle.active()>0&&JourneyAtmosphere.emitted()>checkpoint)
                .server("leave dimension",ctx->{DimensionTravel.teleport(ctx.player(),ctx.server().overworld(),new Vec3(.5,250,.5),0,0);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();})
                .waitUntil("overworld arrives",ctx->Minecraft.getInstance().level.dimension().equals(Level.OVERWORLD)).ticks(10)
                .step("off-dimension baseline",ctx->checkpoint=JourneyAtmosphere.emitted()).ticks(40)
                .check("no ambience leaks into overworld",ctx->JourneyAmbientParticle.active()==0&&JourneyAtmosphere.emitted()==checkpoint);
        }
        s.teardown("restore player settings",ctx->{var mc=Minecraft.getInstance();mc.options.particles().set(oldSetting);mc.options.hideGui=oldHud;});
    }
    @LDLRegisterClient(name="journey_ambience_night",group="journey_ambience",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Night extends JourneyAmbienceViews{}
    @LDLRegisterClient(name="journey_ambience_day",group="journey_ambience",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Day extends JourneyAmbienceViews{protected int scene(){return 1;}protected String shot(){return "daylight_mana_motes";}}
    @LDLRegisterClient(name="journey_ambience_winter",group="journey_ambience",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Winter extends JourneyAmbienceViews{protected int scene(){return 2;}protected String shot(){return "winter_frost_glow";}}
    @LDLRegisterClient(name="journey_ambience_controls",group="journey_ambience",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Controls extends JourneyAmbienceViews{protected int scene(){return 3;}protected String shot(){return "ambience_budget_check";}}
}
