package dev.logos.dimension.gametest;

import dev.logos.dimension.*;
import dev.logos.dimension.instance.*;
import dev.logos.dimension.world.AlchemicalChunkGenerator;
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
                level.getChunk(marker);level.setBlockAndUpdate(marker,Blocks.LODESTONE.defaultBlockState());
                var generator=(AlchemicalChunkGenerator)level.getChunkSource().getGenerator();
                IslandProgress.of(level).setDefeated(generator.terrain().layout().center(0,0),true);
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
                var generator=(AlchemicalChunkGenerator)level.getChunkSource().getGenerator();
                require(IslandProgress.of(level).defeated(generator.terrain().layout().center(0,0)),"Boss state was not saved in the UUID world");
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
