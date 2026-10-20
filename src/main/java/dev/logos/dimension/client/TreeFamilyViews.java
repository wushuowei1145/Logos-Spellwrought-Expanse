package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Staged bark/end-grain and leaf-volume comparisons; NativeForest uses generated trees. */
public abstract class TreeFamilyViews implements UIScenario {
    private static final int Y=230;private int oldRandomTicks=3;
    protected int scene(){return 0;}
    protected String shot(){return "all_named_log_families";}
    @Override public void configure(ScenarioOptions o){o.tags("tree_families").defaultTimeoutMs(180000).scenarioTimeoutMs(300000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("tree camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(8);mc.options.fov().set(60);mc.options.broadcastOptions();})
            .server("stage tree families",ctx->{
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);oldRandomTicks=level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);level.getGameRules().set(GameRules.RANDOM_TICK_SPEED,0,ctx.server());
                for(int x=-21;x<=21;x++)for(int z=-20;z<=16;z++){
                    level.setBlockAndUpdate(new BlockPos(x,Y,z),CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get().defaultBlockState());
                    for(int y=1;y<=11;y++)level.removeBlock(new BlockPos(x,Y+y,z),false);
                }
                Vec3 camera;float pitch;
                if(scene()==0){
                    int i=0;for(var b:CloudseaBiome.values()){
                        int x=-14+i%8*4,z=-14+i/8*5;i++;
                        for(int y=1;y<=3;y++)level.setBlockAndUpdate(new BlockPos(x,Y+y,z),CloudseaBlocks.TRUNKS.get(b).get().defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(x,Y+4,z),CloudseaBlocks.LEAVES.get(b).get().defaultBlockState().setValue(LeavesBlock.PERSISTENT,true));
                    }
                    camera=new Vec3(0,Y+11,19);pitch=20;
                }else if(scene()==3){
                    var types=new CloudseaBiome[]{CloudseaBiome.ANCIENT_BEECH,CloudseaBiome.MOON_BIRCH,CloudseaBiome.FIR_HEART,CloudseaBiome.VERDANT_WHIRLS,CloudseaBiome.MOSS_VALE,CloudseaBiome.COPPER_COPSE};
                    for(int i=0;i<types.length;i++){
                        var leaf=CloudseaBlocks.LEAVES.get(types[i]).get().defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
                        for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)level.setBlockAndUpdate(new BlockPos(-8+i*3+dx,Y+1+dy,0),leaf);
                    }
                    camera=new Vec3(0,Y+2.4,8);pitch=0;
                }else{
                    var types=scene()==1?new CloudseaBiome[]{CloudseaBiome.ANCIENT_BEECH,CloudseaBiome.MOON_BIRCH,CloudseaBiome.FIR_HEART,CloudseaBiome.VERDANT_WHIRLS}:new CloudseaBiome[]{CloudseaBiome.MOSS_VALE,CloudseaBiome.COPPER_COPSE,CloudseaBiome.SILK_FOLDS,CloudseaBiome.OPAL_DUNES};
                    for(int i=0;i<types.length;i++){
                        int x=-6+i*4;var log=CloudseaBlocks.TRUNKS.get(types[i]).get();
                        for(int y=1;y<=4;y++)level.setBlockAndUpdate(new BlockPos(x,Y+y,0),log.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(x+1,Y+1,1),log.defaultBlockState().setValue(RotatedPillarBlock.AXIS,Direction.Axis.Z));
                        level.setBlockAndUpdate(new BlockPos(x,Y+5,0),CloudseaBlocks.LEAVES.get(types[i]).get().defaultBlockState().setValue(LeavesBlock.PERSISTENT,true));
                    }
                    camera=new Vec3(0,Y+3.2,12);pitch=4;
                }
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),6000);level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                for(var item:level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(-24,Y-1,-23,24,Y+14,20)))item.discard();
                DimensionTravel.teleport(ctx.player(),level,camera,180,pitch);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            }).waitUntil("tree chunks arrive",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.hasChunk(0,0);}).ticks(100)
            .checkServer("all named trunks registered",ctx->CloudseaBlocks.TRUNKS.size()==32)
            .check("tree materials received",ctx->!Minecraft.getInstance().level.getBlockState(new BlockPos(0,Y,0)).isAir())
            .check("camera clear",ctx->{var mc=Minecraft.getInstance();var pos=BlockPos.containing(mc.player.getEyePosition());return mc.level.getBlockState(pos).getCollisionShape(mc.level,pos).isEmpty();})
            .screenshot(shot())
            .teardownServer("restore random ticks",ctx->ctx.server().getLevel(LogosDimension.CLOUDSEA).getGameRules().set(GameRules.RANDOM_TICK_SPEED,oldRandomTicks,ctx.server()))
            .teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="tree_families_catalogue",group="tree_families",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Catalogue extends TreeFamilyViews{}
    @LDLRegisterClient(name="tree_families_classic",group="tree_families",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Classic extends TreeFamilyViews{protected int scene(){return 1;}protected String shot(){return "beech_birch_fir_oak";}}
    @LDLRegisterClient(name="tree_families_special",group="tree_families",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Special extends TreeFamilyViews{protected int scene(){return 2;}protected String shot(){return "moss_copper_glade_coast";}}
    @LDLRegisterClient(name="tree_families_leaf_volume",group="tree_families",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class LeafVolume extends TreeFamilyViews{protected int scene(){return 3;}protected String shot(){return "filled_leaf_centers";}}
    @LDLRegisterClient(name="tree_families_native",group="tree_families",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class NativeForest extends JourneyViews{}
}
