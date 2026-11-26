package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.JourneySeasons;
import dev.logos.dimension.gametest.WheatPlainsGameTests;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;

/** 只观察自然生成的麦田与怪圈；不在截图世界铺田或复制模板。 */
@LDLRegisterClient(name="wheat_plains_natural",group="wheat_plains",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public final class WheatPlainsViews implements UIScenario {
    private BlockPos center;
    private int floor;
    private int pieces;
    private float overheadYaw;
    @Override public void configure(ScenarioOptions o){o.tags("wheat_plains").defaultTimeoutMs(300000).scenarioTimeoutMs(600000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("麦田取景",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(12);mc.options.fov().set(75);mc.options.broadcastOptions();})
            .server("寻找自然麦田怪圈",ctx->{
                var server=ctx.server();var level=server.getLevel(LogosDimension.CLOUDSEA);
                enable(server.getWorldGenSettings());for(var world:server.getAllLevels())enable(world.structureManager());
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),6000);
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                var at=WheatPlainsGameTests.findInterior(level);
                // 群系边缘允许自然裁掉图案；预览在多个真实起点中选一个完整的，不修改地表。
                boolean complete=false;
                for(int attempt=0;attempt<8&&!complete;attempt++){
                  var found=level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE,LogosDimension.id("wheat_crop_circles")),at,32,true);
                  if(found==null)throw new IllegalStateException("稀有群系中没有自然怪圈");
                  var chunk=level.getChunkAt(found);
                  for(var entry:chunk.getAllStarts().entrySet()){
                    if(!LogosDimension.id("wheat_crop_circle").equals(level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(entry.getKey()))||!entry.getValue().isValid())continue;
                    var start=entry.getValue();center=start.getBoundingBox().getCenter();pieces=start.getPieces().size();
                    // 测试器不会自动补齐远处地面；预载整个取景范围，避免未收到区块的蓝底看起来像海岛。
                    var box=start.getBoundingBox();for(int x=(box.minX()>>4)-6;x<=(box.maxX()>>4)+6;x++)for(int z=(box.minZ()>>4)-6;z<=(box.maxZ()>>4)+6;z++)level.getChunk(x,z);
                    var piece=(net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece)start.getPieces().getFirst();
                    overheadYaw=switch(piece.getRotation()){
                        case NONE -> 180;case CLOCKWISE_90 -> 270;case CLOCKWISE_180 -> 0;case COUNTERCLOCKWISE_90 -> 90;
                    };
                    int marked=0;
                    for(int x=box.minX();x<=box.maxX();x++)for(int z=box.minZ();z<=box.maxZ();z++){
                        var p=new BlockPos(x,level.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z)-1,z);var state=level.getBlockState(p);
                        if(state.is(net.minecraft.world.level.block.Blocks.HAY_BLOCK)||state.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)||state.is(net.minecraft.world.level.block.Blocks.MUD))marked++;
                    }
                    System.out.println("WHEAT_CLIENT_CANDIDATE "+center+" marked="+marked);
                    complete=marked>=7800;
                    break;
                  }
                }
                if(!complete)throw new IllegalStateException("没有找到完整的自然怪圈预览样地");
                floor=level.getHeight(Heightmap.Types.OCEAN_FLOOR,center.getX(),center.getZ());
                System.out.println("WHEAT_CLIENT_NATURAL center="+center+" surface="+floor+" pieces="+pieces);
                DimensionTravel.teleport(ctx.player(),level,new net.minecraft.world.phys.Vec3(center.getX()+.5,floor+100,center.getZ()+.5),overheadYaw,90);
                ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            })
            .waitUntil("收到怪圈地表",ctx->{var mc=Minecraft.getInstance();return center!=null&&mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&!mc.level.getBlockState(new BlockPos(center.getX(),floor-1,center.getZ())).isAir();})
            .ticks(240)
            .checkServer("原版结构完整生成",ctx->pieces==1&&NativeWorlds.vanilla(ctx.player().level()))
            .screenshot("wheat_natural_crop_circle")
            .server("走入麦田",ctx->{var level=ctx.player().level();DimensionTravel.teleport(ctx.player(),level,new net.minecraft.world.phys.Vec3(center.getX()+.5,floor+3,center.getZ()+76.5),180,12);})
            .ticks(50)
            .checkServer("首次发现授予史诗成就",ctx->ctx.player().getAdvancements().getOrStartProgress(ctx.server().getAdvancements().get(LogosDimension.id("adventure/crop_circle"))).isDone())
            .screenshot("wheat_field_encounter")
            .teardown("恢复界面",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    /** 测试器默认禁止结构；只在专用开发存档打开。 */
    private static void enable(Object holder){
        try{
            for(Class<?> type=holder.getClass();type!=null;type=type.getSuperclass())for(var field:type.getDeclaredFields())if(field.getType()==WorldOptions.class){
                field.setAccessible(true);field.set(holder,((WorldOptions)field.get(holder)).withStructures(true));return;
            }
        }catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
        throw new IllegalStateException("没有找到测试世界选项");
    }
}
