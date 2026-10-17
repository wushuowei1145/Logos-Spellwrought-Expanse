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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.phys.Vec3;

/** Deliberately staged model galleries; NativeForest below shows untouched generated terrain. */
public abstract class JourneyArtViews implements UIScenario {
    protected int scene(){return 0;}
    protected String shot(){return "journey_materials";}
    private static final int Y=230;
    @Override public void configure(ScenarioOptions o){o.tags("journey_art").defaultTimeoutMs(120000).scenarioTimeoutMs(240000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("gallery camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.fov().set(65);mc.options.renderDistance().set(8);mc.options.broadcastOptions();})
            .server("stage material and botany models",ctx->{
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                for(int x=-18;x<=18;x++)for(int z=-20;z<=18;z++){
                    level.setBlockAndUpdate(new BlockPos(x,Y,z),CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get().defaultBlockState());
                    for(int y=Y+1;y<=Y+12;y++)level.removeBlock(new BlockPos(x,y,z),false);
                }
                Vec3 camera=new Vec3(0,Y+5,15);float pitch=9;
                if(scene()==0){
                    Block[] types={CloudseaBlocks.LIMESTONE.get(),CloudseaBlocks.PLASTER.get(),CloudseaBlocks.TIMBER_FRAME.get(),CloudseaBlocks.ROOF_TILE.get(),CloudseaBlocks.IVORY_BRICKS.get(),CloudseaBlocks.DARK_TIMBER.get(),CloudseaBlocks.SLATE.get(),CloudseaBlocks.PORCELAIN.get(),CloudseaBlocks.MANTLE.get(),CloudseaBlocks.DEMONSTONE.get(),CloudseaBlocks.WINDOW.get(),CloudseaBlocks.STEM.get()};
                    for(int i=0;i<types.length;i++)for(int dx=0;dx<2;dx++)for(int dy=0;dy<3;dy++)level.setBlockAndUpdate(new BlockPos(-9+i%6*3+dx,Y+1+i/6*4+dy,0),types[i].defaultBlockState());
                }else if(scene()==1){
                    int i=0;for(var biome:CloudseaBiome.values()){
                        int x=-11+i%8*3,z=-i/8*4;
                        level.setBlockAndUpdate(new BlockPos(x,Y+1,z),CloudseaBlocks.STEM.get().defaultBlockState());
                        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(Math.abs(dx)+Math.abs(dz)<=1)
                            level.setBlockAndUpdate(new BlockPos(x+dx,Y+2,z+dz),CloudseaBlocks.LEAVES.get(biome).get().defaultBlockState().setValue(SeasonalLeavesBlock.SEASON,i/8).setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true));
                        i++;
                    }
                    camera=new Vec3(0,Y+9,18);pitch=18;
                }else if(scene()==2){
                    for(int x=-5;x<=5;x++)for(int z=-4;z<=5;z++)level.setBlockAndUpdate(new BlockPos(x,Y+1,z),CloudseaBlocks.MEADOW_GRASS.get().defaultBlockState());
                    camera=new Vec3(0,Y+2,8);pitch=15;
                }else if(scene()==3){
                    var base=new Block[]{CloudseaBlocks.FERN.get(),CloudseaBlocks.BRACKEN.get(),CloudseaBlocks.BLUEBELL.get(),CloudseaBlocks.FOXGLOVE.get(),CloudseaBlocks.LUPINE.get(),CloudseaBlocks.REED.get(),CloudseaBlocks.ROOT.get(),CloudseaBlocks.LANTERN.get()};
                    for(int i=0;i<base.length;i++)level.setBlockAndUpdate(new BlockPos(-9+i*3,Y+1,5),base[i].defaultBlockState());
                    for(int season=0;season<4;season++)for(int i=0;i<CloudseaBlocks.SEASONAL_PLANTS.size();i++)level.setBlockAndUpdate(new BlockPos(-9+i*3,Y+1,2-season*3),CloudseaBlocks.SEASONAL_PLANTS.get(i).get().defaultBlockState().setValue(SeasonalLeavesBlock.SEASON,season));
                    camera=new Vec3(1,Y+5,13);pitch=13;
                }else if(scene()==4){
                    for(int x=-4;x<4;x++)for(int y=Y+1;y<Y+5;y++)level.setBlockAndUpdate(new BlockPos(x,y,0),CloudseaBlocks.LIMESTONE.get().defaultBlockState());
                    for(int x=-4;x<4;x++)for(int y=Y+1;y<Y+5;y++)level.setBlockAndUpdate(new BlockPos(x,y,1),CloudseaBlocks.GRAPEVINE.get().defaultBlockState().setValue(VineBlock.NORTH,true).setValue(GrapeVineBlock.AGE,Math.floorMod(x,4)).setValue(GrapeVineBlock.FOLIAGE,x!=-4));
                    camera=new Vec3(0,Y+2.5,6.5);pitch=0;
                }else{
                    for(int x=-4;x<=4;x++)for(int z=-3;z<=2;z++)for(int y=Y+1;y<=Y+3;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),CloudseaBlocks.LIMESTONE.get().defaultBlockState());
                    camera=new Vec3(3,Y+4,7);pitch=12;
                }
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),6000);
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                for(var item:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(-22,Y,-24,22,Y+20,22)))item.discard();
                DimensionTravel.teleport(ctx.player(),level,camera,180,pitch);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            })
            .waitUntil("gallery chunks received",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.hasChunk(0,0);})
            .ticks(100)
            .check("camera clear",ctx->{var mc=Minecraft.getInstance();var pos=BlockPos.containing(mc.player.getEyePosition());return mc.level.getBlockState(pos).getCollisionShape(mc.level,pos).isEmpty();})
            .checkServer("gallery blocks exist",ctx->!ctx.player().level().getBlockState(new BlockPos(0,Y,0)).isAir())
            .checkServer("grapes remain attached",ctx->scene()!=4||ctx.player().level().getBlockState(new BlockPos(0,Y+2,1)).canSurvive(ctx.player().level(),new BlockPos(0,Y+2,1)))
            .screenshot(shot());
        if(scene()==4){
            var ripe=new BlockPos(-1,Y+2,1);
            s.runCommand("difficulty normal").server("stand within reach and prepare hunger",ctx->{
                var p=ctx.player();p.setGameMode(GameType.SURVIVAL);p.getInventory().clearContent();p.getFoodData().setFoodLevel(10);p.getFoodData().setSaturation(0);
                DimensionTravel.teleport(p,p.level(),new Vec3(-.5,Y+1,4.5),180,0);
                p.level().setBlockAndUpdate(ripe,CloudseaBlocks.GRAPEVINE.get().defaultBlockState().setValue(VineBlock.NORTH,true).setValue(GrapeVineBlock.AGE,3));
            }).ticks(15).step("right click ripe grapes",ctx->{
                var mc=Minecraft.getInstance();mc.gameMode.useItemOn(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.phys.BlockHitResult(ripe.getCenter(),net.minecraft.core.Direction.SOUTH,ripe,false));
            }).waitUntil("picked grapes reach client inventory",ctx->Minecraft.getInstance().player.getMainHandItem().is(CloudseaBlocks.GRAPES.get()))
            .checkServer("actual click resets fruit",ctx->ctx.player().level().getBlockState(ripe).getValue(GrapeVineBlock.AGE)==0)
            .step("hold use to eat the harvested grape",ctx->{var mc=Minecraft.getInstance();mc.options.keyUse.setDown(true);mc.gameMode.useItem(mc.player,net.minecraft.world.InteractionHand.MAIN_HAND);})
            .ticks(40).step("release use",ctx->Minecraft.getInstance().options.keyUse.setDown(false))
            .checkServer("eating restores one icon",ctx->ctx.player().getFoodData().getFoodLevel()==12&&ctx.player().getMainHandItem().isEmpty());
        }
        s.teardownServer("restore peaceful gallery",ctx->ctx.server().setDifficulty(net.minecraft.world.Difficulty.PEACEFUL,true));
        s.teardown("restore HUD and keys",ctx->{Minecraft.getInstance().options.hideGui=false;Minecraft.getInstance().options.keyUse.setDown(false);});
    }
    @LDLRegisterClient(name="journey_art_materials",group="journey_art",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Materials extends JourneyArtViews{}
    @LDLRegisterClient(name="journey_art_leaves",group="journey_art",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Leaves extends JourneyArtViews{protected int scene(){return 1;}protected String shot(){return "journey_leaf_clusters";}}
    @LDLRegisterClient(name="journey_art_grass",group="journey_art",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Grass extends JourneyArtViews{protected int scene(){return 2;}protected String shot(){return "journey_dense_meadow";}}
    @LDLRegisterClient(name="journey_art_plants",group="journey_art",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Plants extends JourneyArtViews{protected int scene(){return 3;}protected String shot(){return "journey_botanical_gallery";}}
    @LDLRegisterClient(name="journey_art_grapes",group="journey_art",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Grapes extends JourneyArtViews{protected int scene(){return 4;}protected String shot(){return "journey_wall_grapes";}}
    @LDLRegisterClient(name="journey_art_limestone",group="journey_art",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Limestone extends JourneyArtViews{protected int scene(){return 5;}protected String shot(){return "journey_limestone_closeup";}}
    @LDLRegisterClient(name="journey_art_forest",group="journey_art",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class NativeForest extends JourneyViews{}
}
