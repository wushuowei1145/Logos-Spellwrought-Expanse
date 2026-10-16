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

/** Actual generated landscapes and the authored plant gallery, captured in a real client. */
public abstract class JourneyViews implements UIScenario {
    protected int scene(){return 0;}
    protected int season(){return 0;}
    protected String shot(){return "river_plain";}
    protected Vec3 at;protected BlockPos received;
    @Override public void configure(ScenarioOptions o){o.tags("journey").defaultTimeoutMs(180_000).scenarioTimeoutMs(240_000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("landscape camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(12);mc.options.fov().set(65);mc.options.broadcastOptions();})
            .server("find the new continent",ctx->{
                var player=ctx.player();var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);var gen=(CloudseaChunkGenerator)level.getChunkSource().getGenerator();
                int x=400,z=1600;float yaw=155,pitch=15;
                if(scene()==1){z=-6500;x=500;int top=0;for(int sx=-1500;sx<1500;sx+=48){int h=gen.terrain().column(sx,z).ground();if(h>top){top=h;x=sx;}}at=new Vec3(x+110,top+18,z+130);yaw=140;pitch=3;}
                else if(scene()==2){x=1400;z=(int)gen.terrain().coast(x);at=new Vec3(x-70,120,z-100);yaw=-40;pitch=15;}
                else if(scene()==3||scene()==4){
                    CloudseaSettlements.Village village=null;
                    for(int gz=-2;gz<=0&&village==null;gz++)for(int gx=-10;gx<=10&&village==null;gx++){var v=gen.settlements().village(gen.terrain().region(gx,gz));if(v!=null&&(scene()==3||v.town()))village=v;}
                    if(village==null)throw new IllegalStateException("No suitable village");x=village.x();z=village.z();int roof=village.houses().stream().mapToInt(h->h.y()+JourneyTemplates.get(h.template()).height()).max().orElse(village.floor()+16);at=new Vec3(x+62,roof+35,z+72);yaw=140;pitch=23;
                }else if(scene()==5){
                    x=400;z=1500;int base=210;
                    for(int dx=-12;dx<=12;dx++)for(int dz=-5;dz<=6;dz++)level.setBlockAndUpdate(new BlockPos(x+dx,base,z+dz),net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
                    var plants=java.util.List.of(CloudseaBlocks.FERN,CloudseaBlocks.BRACKEN,CloudseaBlocks.BLUEBELL,CloudseaBlocks.FOXGLOVE,CloudseaBlocks.LUPINE,CloudseaBlocks.MEADOW_GRASS,CloudseaBlocks.REED,CloudseaBlocks.GRAPEVINE);
                    for(int i=0;i<plants.size();i++)level.setBlockAndUpdate(new BlockPos(x-10+i*3,base+1,z),plants.get(i).get().defaultBlockState());
                    at=new Vec3(x+.5,base+5,z+16);yaw=180;pitch=13;
                }else if(scene()==6){
                    boolean found=false;
                    for(int gz=-2000;gz<=1800&&!found;gz+=120)for(int gx=-3000;gx<=3000&&!found;gx+=120){var c=gen.terrain().column(gx,gz);if(c.biome().treeDensity()>.6){x=gx;z=gz;found=true;}}
                    at=new Vec3(x+60,gen.terrain().column(x,z).ground()+28,z+65);yaw=140;pitch=13;
                }else {
                    boolean found=false;
                    for(int gz=500;gz<4500&&!found;gz+=130)for(int gx=-6000;gx<6000&&!found;gx+=120){var c=gen.terrain().column(gx,gz);if(c.biome().treeDensity()<=.05&&!c.biome().barren()&&gen.terrain().riverDistance(gx,gz)<500){x=gx;z=gz;found=true;}}
                    at=new Vec3(x+35,gen.terrain().column(x,z).ground()+16,z+55);yaw=155;pitch=9;
                }
                at=new Vec3(at.x,Math.max(at.y,gen.terrain().column((int)at.x,(int)at.z).highest()+24),at.z);
                received=new BlockPos(x,gen.terrain().column(x,z).ground()-1,z);level.getChunk(received);
                DimensionTravel.teleport(player,level,at,yaw,pitch);player.getAbilities().flying=true;player.onUpdateAbilities();
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),season()*JourneySeasons.SEASON_TICKS+6000);
                level.getWeatherData().setClearWeatherTime(60000);level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
            })
            .waitUntil("continent received",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.hasChunk(BlockPos.containing(at).getX()>>4,BlockPos.containing(at).getZ()>>4);})
            .ticks(160)
            .server("update seasonal canopy for capture",ctx->{
                if(season()==0)return;var level=ctx.player().level();int cx=ctx.player().blockPosition().getX(),cz=ctx.player().blockPosition().getZ();
                for(int x=cx-75;x<cx+75;x++)for(int z=cz-75;z<cz+75;z++){
                    var gen=(CloudseaChunkGenerator)level.getChunkSource().getGenerator();int ground=gen.terrain().column(x,z).ground();
                    for(int y=ground;y<ground+22;y++){var p=new BlockPos(x,y,z);var b=level.getBlockState(p);if(b.hasProperty(SeasonalLeavesBlock.SEASON))level.setBlock(p,b.setValue(SeasonalLeavesBlock.SEASON,season()),2);}
                }
            })
            .ticks(80)
            .checkServer("new continent floor exists",ctx->!ctx.player().level().getBlockState(received).isAir())
            .check("no volume cloud worker",ctx->Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("logos-cloud-mesh")))
            .screenshot(shot()).teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="journey_plain",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Plain extends JourneyViews{}
    @LDLRegisterClient(name="journey_mountain",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Mountain extends JourneyViews{protected int scene(){return 1;}protected String shot(){return "northern_range";}}
    @LDLRegisterClient(name="journey_coast",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Coast extends JourneyViews{protected int scene(){return 2;}protected String shot(){return "southern_coast";}}
    @LDLRegisterClient(name="journey_village",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Village extends JourneyViews{protected int scene(){return 3;}protected String shot(){return "river_hamlet";}}
    @LDLRegisterClient(name="journey_town",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Town extends JourneyViews{protected int scene(){return 4;}protected String shot(){return "walled_town";}}
    @LDLRegisterClient(name="journey_plants",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Plants extends JourneyViews{protected int scene(){return 5;}protected String shot(){return "plant_models";}}
    @LDLRegisterClient(name="journey_autumn",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Autumn extends JourneyViews{protected int scene(){return 6;}protected int season(){return 2;}protected String shot(){return "autumn_woods";}}
    @LDLRegisterClient(name="journey_winter",group="journey",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Winter extends Autumn{protected int season(){return 3;}protected String shot(){return "winter_woods";}}
}
