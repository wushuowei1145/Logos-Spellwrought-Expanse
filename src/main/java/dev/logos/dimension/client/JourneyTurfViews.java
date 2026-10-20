package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Four deliberately staged turf comparisons; NativeForest shows actual generated woodland. */
public abstract class JourneyTurfViews implements UIScenario {
    protected int scene(){return 0;}
    protected String shot(){return "turf_meadow";}
    private static final int Y=230;
    private int previousRandomTicks=3;
    private final List<BlockPos> samples=new ArrayList<>();
    @Override public void configure(ScenarioOptions o){o.tags("journey_turf").defaultTimeoutMs(120000).scenarioTimeoutMs(240000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("turf camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.fov().set(65);mc.options.renderDistance().set(8);mc.options.broadcastOptions();})
            .server("stage turf comparisons",ctx->{
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                previousRandomTicks=level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
                level.getGameRules().set(GameRules.RANDOM_TICK_SPEED,0,ctx.server());
                samples.clear();
                for(int x=-30;x<=30;x++)for(int z=-26;z<=26;z++){
                    level.setBlockAndUpdate(new BlockPos(x,Y,z),Blocks.SMOOTH_STONE.defaultBlockState());
                    for(int y=Y+1;y<=Y+8;y++)level.removeBlock(new BlockPos(x,y,z),false);
                }
                Vec3 camera;float pitch;
                if(scene()==0){
                    for(int x=-24;x<=24;x++)for(int z=-24;z<=12;z++){
                        int height=z<-8&&x<-5?2:1;
                        for(int dy=1;dy<=height;dy++)level.setBlockAndUpdate(new BlockPos(x,Y+dy,z),CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get().defaultBlockState());
                    }
                    samples.add(new BlockPos(0,Y+1,0));camera=new Vec3(1,Y+3.7,12);pitch=28;
                }else if(scene()==1){
                    for(int season=0;season<4;season++){
                        int start=-21+season*11;
                        for(int x=start;x<start+9;x++)for(int z=-7;z<=3;z++)level.setBlockAndUpdate(new BlockPos(x,Y+1,z),CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get().defaultBlockState().setValue(SeasonalLeavesBlock.SEASON,season));
                        samples.add(new BlockPos(start+4,Y+1,0));
                    }
                    camera=new Vec3(0,Y+12,23);pitch=30;
                }else if(scene()==2){
                    for(int x=-22;x<=22;x++)for(int z=-22;z<=12;z++){
                        var biome=x<-7?CloudseaBiome.ANCIENT_BEECH:x>7?CloudseaBiome.FIR_HEART:CloudseaBiome.MOSS_VALE;
                        level.setBlockAndUpdate(new BlockPos(x,Y+1,z),CloudseaBlocks.GROUND.get(biome).get().defaultBlockState());
                    }
                    for(int x:new int[]{-17,-11,0,6,14,20})for(int z:new int[]{-12,-3,5})if(Math.floorMod(x+z,3)==0)level.setBlockAndUpdate(new BlockPos(x,Y+2,z),CloudseaBlocks.FERN.get().defaultBlockState());
                    samples.add(new BlockPos(-12,Y+1,0));samples.add(new BlockPos(0,Y+1,0));samples.add(new BlockPos(12,Y+1,0));
                    camera=new Vec3(0,Y+7,16);pitch=25;
                }else{
                    int i=0;for(var biome:CloudseaBiome.values()){
                        int startX=-27+i%8*7,startZ=-21+i/8*8;
                        for(int x=startX;x<startX+5;x++)for(int z=startZ;z<startZ+6;z++)level.setBlockAndUpdate(new BlockPos(x,Y+1,z),CloudseaBlocks.GROUND.get(biome).get().defaultBlockState());
                        samples.add(new BlockPos(startX+2,Y+1,startZ+2));i++;
                    }
                    camera=new Vec3(0,Y+26,32);pitch=40;
                }
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),6000);
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                DimensionTravel.teleport(ctx.player(),level,camera,180,pitch);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            }).waitUntil("turf chunks arrive",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.hasChunk(0,0);})
            .ticks(100)
            .checkServer("staged turf has season and solid collision",ctx->samples.stream().allMatch(p->{var state=ctx.player().level().getBlockState(p);return state.hasProperty(SeasonalLeavesBlock.SEASON)&&!state.getCollisionShape(ctx.player().level(),p).isEmpty();}))
            .check("turf received by client",ctx->samples.stream().allMatch(p->Minecraft.getInstance().level.getBlockState(p).hasProperty(SeasonalLeavesBlock.SEASON)))
            .check("camera outside geometry",ctx->{var mc=Minecraft.getInstance();var p=BlockPos.containing(mc.player.getEyePosition());return mc.level.getBlockState(p).getCollisionShape(mc.level,p).isEmpty();})
            .screenshot(shot())
            .teardownServer("restore random ticks",ctx->ctx.server().getLevel(LogosDimension.CLOUDSEA).getGameRules().set(GameRules.RANDOM_TICK_SPEED,previousRandomTicks,ctx.server()))
            .teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="journey_turf_meadow",group="journey_turf",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Meadow extends JourneyTurfViews{}
    @LDLRegisterClient(name="journey_turf_seasons",group="journey_turf",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Seasons extends JourneyTurfViews{protected int scene(){return 1;}protected String shot(){return "turf_seasons";}}
    @LDLRegisterClient(name="journey_turf_floors",group="journey_turf",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Floors extends JourneyTurfViews{protected int scene(){return 2;}protected String shot(){return "turf_forest_floors";}}
    @LDLRegisterClient(name="journey_turf_catalogue",group="journey_turf",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Catalogue extends JourneyTurfViews{protected int scene(){return 3;}protected String shot(){return "turf_catalogue";}}
    @LDLRegisterClient(name="journey_turf_native",group="journey_turf",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class NativeForest extends JourneyViews{}
}
