package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import java.util.*;
import java.nio.file.*;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** 只在显式启用的独立开发存档中执行原版定位命令，不改动玩家存档。 */
@EventBusSubscriber(modid=LogosDimension.ID)
public final class JourneyLocateCheck {
    @SubscribeEvent static void started(ServerStartedEvent event){
        String stage=System.getProperty("logos_dimension.locate_stage","");
        if(FMLEnvironment.isProduction()||stage.isEmpty())return;
        if(!Set.of("fresh","upgrade","seed").contains(stage))throw new IllegalArgumentException("未知定位测试阶段");
        var server=event.getServer();var l=Objects.requireNonNull(server.getLevel(LogosDimension.CLOUDSEA));
        try{
            var available=l.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
            System.out.println("ELVEN_LOCATE_SOURCE stage="+stage+" seed="+l.getSeed()+" biomes="+available.size()+" elven="+available.stream().anyMatch(b->b.unwrapKey().orElseThrow().identifier().equals(LogosDimension.id("elven_forest")))+" structures="+server.getWorldGenSettings().options().generateStructures());
            List<String> lines=new ArrayList<>();boolean passed=true;
            for(int y:new int[]{96,320})for(String target:new String[]{"biome logos_dimension:elven_forest","structure logos_dimension:elven_world_tree"}){
                final boolean[] ok={false};
                CommandSource capture=new CommandSource(){
                    public void sendSystemMessage(Component message){System.out.println("ELVEN_LOCATE_MESSAGE "+message.getString());lines.add(message.getString());}
                    public boolean acceptsSuccess(){return true;}public boolean acceptsFailure(){return true;}public boolean shouldInformAdmins(){return false;}
                };
                var source=server.createCommandSourceStack().withSource(capture).withPosition(new Vec3(0,y,0)).withCallback((success,result)->{ok[0]=success;System.out.println("ELVEN_LOCATE_RESULT y="+y+" target="+target+" success="+success+" result="+result);});
                server.getCommands().performPrefixedCommand(source,"execute in logos_dimension:cloudsea run locate "+target);
                passed&=ok[0];
            }
            // 读取原版正常区块生成结果，不手动放置结构或绕过群系筛选。
            var holder=l.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.STRUCTURE,LogosDimension.id("elven_world_tree")));
            var found=l.getChunkSource().getGenerator().findNearestMapStructure(l,net.minecraft.core.HolderSet.direct(holder),net.minecraft.core.BlockPos.ZERO,100,false);
            if(found==null)throw new IllegalStateException("原版世界树定位没有结果");
            var start=l.getChunk(found.getFirst()).getStartForStructure(holder.value());
            if(start==null||!start.isValid())throw new IllegalStateException("实际区块没有世界树起始结构");
            var root=(net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece)start.getPieces().getFirst();
            var center=root.getPosition().offset(new net.minecraft.core.BlockPos(16,4,16).rotate(root.getRotation()));
            var tree=new ElvenForestGameTests.Tree(start,center,root.getRotation());
            var floor=tree.pos(0,1,0);l.getChunk(floor);
            var shelf=tree.pos(-6,2,9);l.getChunk(shelf);
            if(!l.getBlockState(floor).is(dev.logos.dimension.elven.ElvenBlocks.INLAY.get())||!l.getBlockState(shelf).is(dev.logos.dimension.elven.ElvenBlocks.BOOKCASE.get()))throw new IllegalStateException("自然世界树缺少实际地板或家具");
            System.out.println("ELVEN_LOCATE_ACTUAL center="+center+" pieces="+start.getPieces().size()+" floor="+l.getBlockState(floor)+" shelf="+l.getBlockState(shelf));
            Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("elven-locate-"+stage+".txt"),"passed="+passed+"\n"+String.join("\n",lines));
            if(!passed)throw new IllegalStateException("原版定位命令失败，查看 ELVEN_LOCATE_RESULT");
            System.out.println("ELVEN_LOCATE_CHECK "+stage+" PASS");
        }catch(Exception error){throw new IllegalStateException("精灵森林定位复现失败",error);}finally{server.halt(false);}
    }
    private JourneyLocateCheck(){}
}
