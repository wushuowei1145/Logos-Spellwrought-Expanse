package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Close views of staged plants/water. Only normal proximity logic emits particles. */
public abstract class JourneyMagicDetailViews implements UIScenario {
    private static final int Y=230;private ParticleStatus oldParticles;private int oldRandomTicks;private long before,reactions,chimes,checkpoint;
    protected int scene(){return 0;}
    @Override public void configure(ScenarioOptions o){o.tags("magic_details").defaultTimeoutMs(180000).scenarioTimeoutMs(300000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("close-up settings",ctx->{var mc=Minecraft.getInstance();oldParticles=mc.options.particles().get();mc.options.particles().set(ParticleStatus.ALL);mc.options.hideGui=true;mc.options.fov().set(56);mc.options.renderDistance().set(8);mc.options.broadcastOptions();})
            .server("stage a woodland detail study",ctx->{
                var l=ctx.server().getLevel(LogosDimension.CLOUDSEA);oldRandomTicks=l.getGameRules().get(GameRules.RANDOM_TICK_SPEED);l.getGameRules().set(GameRules.RANDOM_TICK_SPEED,0,ctx.server());
                for(int x=-15;x<=15;x++)for(int z=-16;z<=10;z++){
                    for(int y=-1;y<=0;y++)l.setBlockAndUpdate(new BlockPos(x,Y+y,z),CloudseaBlocks.GROUND.get(CloudseaBiome.MOSS_VALE).get().defaultBlockState().setValue(SeasonalLeavesBlock.SEASON,1));
                    for(int y=1;y<=14;y++)l.removeBlock(new BlockPos(x,Y+y,z),false);
                }
                for(int[] tree:new int[][]{{-6,-4},{5,-6},{0,-11},{10,-10},{-11,-10}}){
                    for(int y=1;y<=5;y++)l.setBlockAndUpdate(new BlockPos(tree[0],Y+y,tree[1]),CloudseaBlocks.TRUNKS.get(CloudseaBiome.MOSS_VALE).get().defaultBlockState());
                    for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=4;y<=6;y++)if(x*x+z*z<7)l.setBlockAndUpdate(new BlockPos(tree[0]+x,Y+y,tree[1]+z),CloudseaBlocks.LEAVES.get(CloudseaBiome.MOSS_VALE).get().defaultBlockState().setValue(LeavesBlock.PERSISTENT,true).setValue(SeasonalLeavesBlock.SEASON,1));
                }
                if(scene()==1){
                    for(int x=-3;x<=3;x++)for(int z=-4;z<=3;z++)l.setBlockAndUpdate(new BlockPos(x,Y,z),Blocks.WATER.defaultBlockState());
                }else{
                    l.setBlockAndUpdate(new BlockPos(1,Y+1,3),CloudseaBlocks.MOONBELL.get().defaultBlockState().setValue(SeasonalLeavesBlock.SEASON,1));
                    if(scene()==0){
                        l.setBlockAndUpdate(new BlockPos(-2,Y+1,4),CloudseaBlocks.LUMEN_FERN.get().defaultBlockState());
                        l.setBlockAndUpdate(new BlockPos(1,Y+1,3),CloudseaBlocks.STARDEW.get().defaultBlockState());
                        l.setBlockAndUpdate(new BlockPos(2,Y+1,0),CloudseaBlocks.GLOWCAP.get().defaultBlockState().setValue(SeasonalLeavesBlock.SEASON,1));
                        for(int x=-5;x<=5;x++)for(int z=-7;z<=0;z++)if(Math.floorMod(x*7+z*3,5)==0)l.setBlockAndUpdate(new BlockPos(x,Y+1,z),CloudseaBlocks.FERN.get().defaultBlockState());
                    }
                }
                l.clockManager().setTotalTicks(l.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),JourneySeasons.SEASON_TICKS+13000);l.getWeatherData().setRaining(false);l.getWeatherData().setThundering(false);
                DimensionTravel.teleport(ctx.player(),l,new Vec3(.5,Y+1,5.5),180,25);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            }).waitUntil("detail chunks arrive",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.getBlockState(new BlockPos(0,Y,5)).is(CloudseaBlocks.GROUND.get(CloudseaBiome.MOSS_VALE).get());})
            .step("fresh reaction baseline",ctx->{JourneyMagicDetails.clear();JourneyAmbientParticle.clear();before=JourneyAmbientParticle.created();reactions=JourneyMagicDetails.reactions();chimes=JourneyMagicDetails.sounds();})
            .waitUntil("normal environmental response",ctx->scene()==1?JourneyAmbientParticle.active(AmbientMotion.Kind.RIPPLE)>0&&JourneyAmbientParticle.active(AmbientMotion.Kind.WISP)>0:JourneyAmbientParticle.active(AmbientMotion.Kind.SIGIL)>0&&(scene()!=0||JourneyAmbientParticle.active(AmbientMotion.Kind.SPORE)>0))
            .ticks(20)
            .server("frame both luminous plants",ctx->{if(scene()==0)DimensionTravel.teleport(ctx.player(),ctx.player().level(),new Vec3(.5,Y+1,7),180,20);}).ticks(10)
            .check("details came from normal emitter",ctx->JourneyAmbientParticle.created()>before)
            .check("shared budget stays bounded",ctx->JourneyAmbientParticle.active()<=80)
            .check("surface details are active",ctx->JourneyAmbientParticle.active(scene()==1?AmbientMotion.Kind.RIPPLE:AmbientMotion.Kind.SIGIL)>0)
            .screenshot(scene()==1?"silver_water_and_shy_wisp":"woodland_plant_response");
        if(scene()==1)s.repeat(4,b->b.ticks(6).screenshot("water_ring_motion"));
        if(scene()==2){
            s.ticks(120).check("standing still does not retrigger one flower",ctx->JourneyMagicDetails.reactions()==reactions+1)
                .check("chimes are rate limited",ctx->JourneyMagicDetails.sounds()<=chimes+1)
                .step("disable decorative particles",ctx->{Minecraft.getInstance().options.particles().set(ParticleStatus.MINIMAL);checkpoint=JourneyAmbientParticle.created();}).ticks(40)
                .check("minimal particles disables every detail",ctx->JourneyAmbientParticle.active()==0&&JourneyAmbientParticle.created()==checkpoint)
                .step("resume normal settings",ctx->Minecraft.getInstance().options.particles().set(ParticleStatus.ALL));
        }
        s.teardownServer("restore random ticks",ctx->ctx.server().getLevel(LogosDimension.CLOUDSEA).getGameRules().set(GameRules.RANDOM_TICK_SPEED,oldRandomTicks,ctx.server()))
            .teardown("restore view settings",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=false;mc.options.particles().set(oldParticles);});
    }
    @LDLRegisterClient(name="magic_details_forest",group="magic_details",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Forest extends JourneyMagicDetailViews{}
    @LDLRegisterClient(name="magic_details_water",group="magic_details",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Water extends JourneyMagicDetailViews{protected int scene(){return 1;}}
    @LDLRegisterClient(name="magic_details_reaction",group="magic_details",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Reaction extends JourneyMagicDetailViews{protected int scene(){return 2;}}
}
