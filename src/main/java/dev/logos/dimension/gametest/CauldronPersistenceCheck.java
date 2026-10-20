package dev.logos.dimension.gametest;

import dev.logos.dimension.*;
import dev.logos.dimension.instance.*;

import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Opt-in development fixture: two real server processes share the same isolated test save. */
@EventBusSubscriber(modid=LogosDimension.ID)
public final class CauldronPersistenceCheck {
    @SubscribeEvent static void started(ServerStartedEvent event) {
        String stage=System.getProperty("logos_dimension.persistence_stage", "");
        if(FMLEnvironment.isProduction() || stage.isEmpty())return;
        var server=event.getServer();
        Path result=server.getWorldPath(LevelResource.ROOT).resolve("cauldron-persistence.properties");
        BlockPos pot=new BlockPos(0,100,0), marker=new BlockPos(9300,100,0);
        try {
            Properties values=new Properties();
            if(stage.equals("create")) {
                UUID id=UUID.randomUUID(), deleted=UUID.randomUUID();
                var level=CauldronWorlds.load(server,id);
                if(level==null)throw new IllegalStateException("Cannot create fixture UUID world");
                level.getChunk(0,0);
                for(int x=-40;x<=40;x+=8)for(int z=-40;z<=40;z+=8){
                    require(level.getBlockState(new BlockPos(x,NativeWorlds.ISLAND_TOP,z)).is(DimensionBlocks.ISLAND_STONE.get()),"Native island structure missing at "+x+","+z);
                    require(level.isEmptyBlock(new BlockPos(x,NativeWorlds.ISLAND_TOP+1,z)),"Reserved island was decorated at "+x+","+z);
                }
                require(server.getLevel(LogosDimension.CRUCIBLE)==null,"Retired fixed dimension loaded after upgrade");
                System.out.println("NATIVE_ISLAND_PLACEMENT PASS");
                var journey=server.getLevel(LogosDimension.CLOUDSEA);
                var structures=journey.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                var hamlet=structures.getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.STRUCTURE,LogosDimension.id("journey_hamlet")));
                var settlement=journey.getChunkSource().getGenerator().findNearestMapStructure(journey,net.minecraft.core.HolderSet.direct(hamlet),BlockPos.ZERO,256,false);
                require(settlement!=null,"Native hamlet could not be located");
                var location=settlement.getFirst();journey.getChunk(location);
                var start=journey.getChunk(location).getStartForStructure(hamlet.value());
                require(start!=null&&start.isValid(),"Native hamlet start missing");
                var box=start.getBoundingBox();int masonry=0;
                for(int x=box.minX();x<=box.maxX();x+=4)for(int z=box.minZ();z<=box.maxZ();z+=4)for(int y=box.minY();y<=box.maxY();y++)
                    if(journey.getBlockState(new BlockPos(x,y,z)).is(dev.logos.dimension.cloudsea.CloudseaBlocks.LIMESTONE.get()))masonry++;
                require(masonry>10,"Native hamlet has no actual masonry: "+masonry);
                System.out.println("NATIVE_HAMLET_PLACEMENT PASS blocks="+masonry);
                level.getChunk(marker);level.setBlockAndUpdate(marker,Blocks.LODESTONE.defaultBlockState());

                IslandProgress.of(level).setDefeated(dev.logos.dimension.NativeWorlds.center(),true);
                server.overworld().getChunk(pot);
                server.overworld().setBlockAndUpdate(pot,DimensionBlocks.ALCHEMICAL_CAULDRON.get().defaultBlockState());
                ((BoundCauldronBlockEntity)server.overworld().getBlockEntity(pot)).setWorldId(id);
                CauldronWorlds.load(server,deleted);
                CauldronWorlds.requestDestroy(server,deleted);CauldronWorlds.processPending(server);
                values.setProperty("uuid",id.toString());values.setProperty("deleted",deleted.toString());
                values.setProperty("seed",Long.toString(level.getSeed()));values.setProperty("created","true");
            } else if(stage.equals("verify")) {
                try(var input=Files.newInputStream(result)){values.load(input);}
                UUID id=UUID.fromString(values.getProperty("uuid")), deleted=UUID.fromString(values.getProperty("deleted"));
                var level=server.getLevel(CauldronWorlds.key(id));
                require(level!=null,"UUID world was not restored during server startup");
                require(level.getSeed()==Long.parseLong(values.getProperty("seed")),"UUID seed changed after restart");
                require(level.getBlockState(marker).is(Blocks.LODESTONE),"Saved UUID chunks were not restored");
                require(server.overworld().getBlockEntity(pot) instanceof BoundCauldronBlockEntity bound && id.equals(bound.worldId()),"Placed cauldron lost its binding after restart");

                require(IslandProgress.of(level).defeated(dev.logos.dimension.NativeWorlds.center()),"Boss state was not saved in the UUID world");
                require(Math.abs(level.getWorldBorder().getSize()-20_000)<.01,"Restored border has incorrect size");
                require(CauldronWorlds.isDestroyed(server,deleted)&&server.getLevel(CauldronWorlds.key(deleted))==null,"Deleted world resurrected after restart");
                require(!Files.exists(CauldronWorlds.directory(server,deleted)),"Deleted world files returned after restart");
                values.setProperty("verified","true");values.setProperty("checks","8");
            } else throw new IllegalArgumentException("Unknown persistence stage: "+stage);
            try(var output=Files.newOutputStream(result)){values.store(output,"Two-process cauldron persistence check");}
            System.out.println("CAULDRON_PERSISTENCE "+stage+" PASS");
        } catch(Exception error) {
            throw new IllegalStateException("Cauldron persistence check failed",error);
        } finally {
            server.halt(false);
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private CauldronPersistenceCheck(){}
}
