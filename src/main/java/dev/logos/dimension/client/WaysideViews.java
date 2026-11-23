package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import dev.logos.dimension.gametest.WaysideGameTests;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;

/** 九个模板对照镜头与单独的自然勘察；搭建仅发生在测试存档。 */
public abstract class WaysideViews implements UIScenario {
    protected int kind(){return 0;}
    protected boolean natural(){return false;}
    private Vec3 eye,look;
    private BlockPos received;
    private int naturalPieces;
    @Override public void configure(ScenarioOptions o){o.tags("wayside_ruins").defaultTimeoutMs(240000).scenarioTimeoutMs(480000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("遗迹画面设置",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(8);mc.options.fov().set(55);mc.options.broadcastOptions();})
            .server(natural()?"寻找真实随机遗迹":"摆放遗迹对照模板",ctx->{
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),6000);
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                if(natural()){
                    enableStructures(ctx.server());
                    var found=level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE,LogosDimension.id("wayside_ruins")),new BlockPos(0,96,0),128,false);
                    if(found==null)throw new IllegalStateException("没有找到自然生成的小遗迹");
                    var chunk=level.getChunkAt(found);StructureStart start=null;
                    for(var entry:chunk.getAllStarts().entrySet()){
                        var key=level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(entry.getKey());
                        if(key!=null&&key.getPath().startsWith("wayside_")&&entry.getValue().isValid()){start=entry.getValue();break;}
                    }
                    if(start==null)throw new IllegalStateException("定位区块没有实际遗迹起点："+found);
                    var box=start.getBoundingBox();
                    for(int x=box.minX()>>4;x<=box.maxX()>>4;x++)for(int z=box.minZ()>>4;z<=box.maxZ()>>4;z++)level.getChunk(x,z);
                    naturalPieces=start.getPieces().size();var center=box.getCenter();
                    // 包围盒可能包含向下的石脚，观察主体只取原地表附近。
                    int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR,center.getX(),center.getZ());
                    look=new Vec3(center.getX()+.5,y+4,center.getZ()+.5);eye=look.add(15,10,19);received=new BlockPos(center.getX(),y,center.getZ());
                    System.out.println("WAYSIDE_NATURAL "+found+" pieces="+naturalPieces);
                }else{
                    var biome=new CloudseaBiome[]{CloudseaBiome.ROSE_DUNES,CloudseaBiome.FERNWOOD,CloudseaBiome.ANCIENT_BEECH,CloudseaBiome.FROST_SPIRES,CloudseaBiome.SILK_FOLDS,CloudseaBiome.OPAL_DUNES,CloudseaBiome.DREAM_BASINS,CloudseaBiome.HALO_SPIRES,CloudseaBiome.GLASS_LAGOONS}[kind()];
                    for(int x=-8;x<=24;x++)for(int z=-8;z<=24;z++){
                        level.setBlockAndUpdate(new BlockPos(x,179,z),Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(x,180,z),kind()==3?Blocks.SNOW_BLOCK.defaultBlockState():kind()==5?Blocks.SANDSTONE.defaultBlockState():CloudseaBlocks.GROUND.get(biome).get().defaultBlockState());
                        for(int y=181;y<=201;y++)level.setBlock(new BlockPos(x,y,z),kind()==8&&x>=-2&&x<=18&&z>=-2&&z<=18&&y<=186?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
                    }
                    WaysideGameTests.template(level,WaysideGameTests.KINDS.get(kind()),0,new BlockPos(0,176,0));
                    // 上一镜头的箱子被测试台清除时会掉出物品，避免这些测试残留混进下一张图。
                    level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(-10,175,-10,30,205,30))
                        .forEach(net.minecraft.world.entity.Entity::discard);
                    received=new BlockPos(4,180,4);look=new Vec3(kind()==4?7:6,kind()==3?186:kind()==7?186:kind()==8?182.5:184.5,6);
                    // 湖底镜头真正潜入水下，避免光影包的水面反射挡住遗迹。
                    eye=kind()==8?look.add(8,2,10):look.add(11,6,13);
                }
                var d=look.subtract(eye);float yaw=(float)Math.toDegrees(Math.atan2(-d.x,d.z));float pitch=(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z)));
                DimensionTravel.teleport(ctx.player(),level,eye.add(0,-ctx.player().getEyeHeight(),0),yaw,pitch);
                ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            })
            .waitUntil("遗迹区块抵达",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.hasChunk(received.getX()>>4,received.getZ()>>4);})
            .ticks(220)
            .checkServer("仍为原版噪声生成器",ctx->NativeWorlds.vanilla(ctx.player().level()))
            .checkServer("自然镜头确有原版拼接部件",ctx->!natural()||naturalPieces>1)
            .screenshot(natural()?"wayside_natural":WaysideGameTests.KINDS.get(kind()))
            .teardown("恢复界面",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    /** 测试器默认关闭结构生成；只在开发存档打开，生产存档仍遵循玩家的选项。 */
    private static void enableStructures(net.minecraft.server.MinecraftServer server){
        withStructures(server.getWorldGenSettings());
        for(var level:server.getAllLevels())withStructures(level.structureManager());
    }
    private static void withStructures(Object holder){
        try{
            for(Class<?> type=holder.getClass();type!=null;type=type.getSuperclass())for(var field:type.getDeclaredFields()){
                if(field.getType()!=net.minecraft.world.level.levelgen.WorldOptions.class)continue;
                field.setAccessible(true);
                field.set(holder,((net.minecraft.world.level.levelgen.WorldOptions)field.get(holder)).withStructures(true));return;
            }
        }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        throw new IllegalStateException("找不到测试世界生成选项："+holder.getClass());
    }
    @LDLRegisterClient(name="wayside_bloom",group="wayside_ruins",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Bloom extends WaysideViews{}
    @LDLRegisterClient(name="wayside_fern",group="wayside_ruins",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Fern extends WaysideViews{protected int kind(){return 1;}}
    @LDLRegisterClient(name="wayside_root",group="wayside_ruins",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Root extends WaysideViews{protected int kind(){return 2;}}
    @LDLRegisterClient(name="wayside_frost",group="wayside_ruins",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Frost extends WaysideViews{protected int kind(){return 3;}}
    @LDLRegisterClient(name="wayside_wind",group="wayside_ruins",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Wind extends WaysideViews{protected int kind(){return 4;}}
    @LDLRegisterClient(name="wayside_tide",group="wayside_ruins",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Tide extends WaysideViews{protected int kind(){return 5;}}
    @LDLRegisterClient(name="wayside_reed",group="wayside_ruins",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Reed extends WaysideViews{protected int kind(){return 6;}}
    @LDLRegisterClient(name="wayside_ravine",group="wayside_ruins",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Ravine extends WaysideViews{protected int kind(){return 7;}}
    @LDLRegisterClient(name="wayside_rain",group="wayside_ruins",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Rain extends WaysideViews{protected int kind(){return 8;}}
    @LDLRegisterClient(name="wayside_natural",group="wayside_ruins",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)public static class Natural extends WaysideViews{protected boolean natural(){return true;}}
}
