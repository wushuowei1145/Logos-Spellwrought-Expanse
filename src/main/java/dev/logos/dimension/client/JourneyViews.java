package dev.logos.dimension.client;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
/** Native generated woodland/glades plus a separately staged seasonal plant gallery. */
public abstract class JourneyViews implements UIScenario {
    protected int scene(){return 0;}protected int season(){return 0;}protected String shot(){return "native_woodland";}
    protected BlockPos received;protected Vec3 at;private long particlesBefore;private float yaw=180;
    @Override public void configure(ScenarioOptions o){o.tags("journey","native_worldgen").defaultTimeoutMs(180000).scenarioTimeoutMs(300000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(10);mc.options.fov().set(70);mc.options.broadcastOptions();})
            .step("record particle baseline",ctx->particlesBefore=JourneyAtmosphere.emitted())
            .server("native biome viewpoint",ctx->{
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);var p=ctx.player();
                String biome=scene()==1?CloudseaBiome.FROST_SPIRES.id():scene()==2?CloudseaBiome.OPAL_DUNES.id():scene()==7?CloudseaBiome.STARFLOWER_GLADE.id():CloudseaBiome.ANCIENT_BEECH.id();
                received=NativeWorlds.locateBiome(level,biome).below();
                at=received.getBottomCenter().add(0,18,0);
                if(scene()==0||scene()==6){
                    boolean foundAir=false;int bestView=0;var origin=received;
                    for(int dz=-16;dz<=16;dz+=2)for(int dx=-16;dx<=16;dx+=2){
                        int x=origin.getX()+dx,z=origin.getZ()+dz;
                        int y=level.getChunkSource().getGenerator().getBaseHeight(x,z,net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,level,level.getChunkSource().randomState());
                        var point=new Vec3(x+.5,y+.1,z+.5);
                        if(!level.getFluidState(BlockPos.containing(point)).isEmpty()||!level.noCollision(p,new net.minecraft.world.phys.AABB(point.x-.3,point.y,point.z-.3,point.x+.3,point.y+1.9,point.z+.3)))continue;
                        for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){
                            int clear=0;for(int d=1;d<=16;d++){
                                var eye=new BlockPos(x+direction.getStepX()*d,y+1,z+direction.getStepZ()*d);
                                if(!level.getBlockState(eye).getCollisionShape(level,eye).isEmpty())break;clear++;
                            }
                            if(clear>bestView){bestView=clear;at=point;received=new BlockPos(x,y-1,z);yaw=direction.toYRot();foundAir=true;}
                        }
                    }
                    if(!foundAir)at=received.getBottomCenter().add(0,23,0);
                }
                if(scene()==3||scene()==4){
                    var structure=level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.STRUCTURE,LogosDimension.id(scene()==3?"journey_hamlet":"journey_town")));
                    var found=level.getChunkSource().getGenerator().findNearestMapStructure(level,net.minecraft.core.HolderSet.direct(structure),BlockPos.ZERO,512,false);
                    if(found==null)throw new IllegalStateException("Native settlement not found");received=found.getFirst();level.getChunk(received);at=received.getBottomCenter().add(35,45,50);
                }
                if(scene()==5){
                    int x=received.getX(),z=received.getZ(),base=230;
                    for(int dx=-14;dx<=14;dx++)for(int dz=-5;dz<=10;dz++){level.setBlockAndUpdate(new BlockPos(x+dx,base,z+dz),net.minecraft.world.level.block.Blocks.MOSS_BLOCK.defaultBlockState());for(int y=1;y<=6;y++)level.removeBlock(new BlockPos(x+dx,base+y,z+dz),false);}
                    for(int i=0;i<CloudseaBlocks.SEASONAL_PLANTS.size();i++)level.setBlockAndUpdate(new BlockPos(x-11+i*3,base+1,z),CloudseaBlocks.SEASONAL_PLANTS.get(i).get().defaultBlockState().setValue(SeasonalLeavesBlock.SEASON,season()));
                    received=new BlockPos(x,base,z);at=new Vec3(x,base+2,z+9);
                }
                DimensionTravel.teleport(p,level,at,yaw,scene()==5?6:8);p.getAbilities().flying=true;p.onUpdateAbilities();
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),season()*JourneySeasons.SEASON_TICKS+(scene()==6?18000:6000));
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                if(scene()!=5){
                    for(int x=received.getX()-25;x<received.getX()+25;x++)for(int z=received.getZ()-25;z<received.getZ()+25;z++)for(int y=received.getY()-3;y<received.getY()+23;y++){
                        var pos=new BlockPos(x,y,z);var state=level.getBlockState(pos);if(state.hasProperty(SeasonalLeavesBlock.SEASON))level.setBlock(pos,state.setValue(SeasonalLeavesBlock.SEASON,season()),2);
                    }
                }
            })
            .waitUntil("native biome arrives",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.hasChunk(received.getX()>>4,received.getZ()>>4);})
            .ticks(180).checkServer("vanilla noise generator",ctx->NativeWorlds.vanilla(ctx.player().level()))
            .check("terrain received",ctx->!Minecraft.getInstance().level.getBlockState(received).isAir())
            .check("camera outside solid geometry",ctx->{var mc=Minecraft.getInstance();return mc.level.getBlockState(BlockPos.containing(mc.player.getEyePosition())).getCollisionShape(mc.level,BlockPos.containing(mc.player.getEyePosition())).isEmpty();})
            .check("local magical motes appear",ctx->scene()!=6||JourneyAtmosphere.emitted()>particlesBefore).screenshot(shot()).teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="journey_forest",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Plain extends JourneyViews{}
    @LDLRegisterClient(name="journey_glade",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Glade extends JourneyViews{protected int scene(){return 7;}protected String shot(){return "native_glade";}}
    public static class Mountain extends JourneyViews{protected int scene(){return 1;}}
    public static class Coast extends JourneyViews{protected int scene(){return 2;}}
    public static class Village extends JourneyViews{protected int scene(){return 3;}}
    public static class Town extends JourneyViews{protected int scene(){return 4;}}
    @LDLRegisterClient(name="journey_plants",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Plants extends JourneyViews{protected int scene(){return 5;}protected String shot(){return "seasonal_spring_plants";}}
    @LDLRegisterClient(name="journey_summer_plants",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class SummerPlants extends Plants{protected int season(){return 1;}protected String shot(){return "seasonal_summer_plants";}}
    @LDLRegisterClient(name="journey_autumn",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Autumn extends JourneyViews{protected int season(){return 2;}protected String shot(){return "native_autumn_forest";}}
    @LDLRegisterClient(name="journey_winter",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Winter extends JourneyViews{protected int season(){return 3;}protected String shot(){return "native_winter_forest";}}
    @LDLRegisterClient(name="journey_magic",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Magic extends JourneyViews{protected int scene(){return 6;}protected String shot(){return "native_forest_glimmers";}}
}
