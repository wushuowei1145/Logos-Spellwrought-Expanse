package dev.logos.dimension;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.core.BlockPos;

/** Runtime instances load the same vanilla data format as a dimension, without registering a public template world. */
public final class NativeWorlds {
    public static final int ISLAND_X=0,ISLAND_Z=0,ISLAND_TOP=320,ISLAND_RADIUS=72;
    public static LevelStem crucible(MinecraftServer server){
        var resource=server.getResourceManager().getResource(LogosDimension.id("cauldron/crucible.json")).orElseThrow(()->new IllegalStateException("Missing cauldron dimension template"));
        try(var reader=resource.openAsReader()){
            var stem=LevelStem.CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),JsonParser.parseReader(reader)).getOrThrow();
            if(!(stem.generator() instanceof NoiseBasedChunkGenerator))throw new IllegalStateException("Cauldron template must use minecraft:noise");
            return stem;
        }catch(java.io.IOException error){throw new IllegalStateException("Cannot read cauldron dimension template",error);}
    }
    public static boolean vanilla(ServerLevel level){return level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator;}
    public static dev.logos.dimension.world.AlchemicalLayout.Center center(){return new dev.logos.dimension.world.AlchemicalLayout.Center(0,0,0,0,0);}
    public static BlockPos locateBiome(ServerLevel level,String id){
        var target=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,LogosDimension.id(id));
        var result=level.getChunkSource().getGenerator().getBiomeSource().findBiomeHorizontal(0,96,0,14000,32,b->b.is(target),net.minecraft.util.RandomSource.create(level.getSeed()),true,level.getChunkSource().randomState().sampler());
        if(result==null)throw new IllegalStateException("Biome not found: "+id);
        var at=result.getFirst();var source=level.getChunkSource().getGenerator().getBiomeSource();var sampler=level.getChunkSource().randomState().sampler();
        search:for(int dz=-256;dz<=256;dz+=64)for(int dx=-256;dx<=256;dx+=64){
            int x=at.getX()+dx,z=at.getZ()+dz;boolean inside=true;
            for(int oz:new int[]{-24,0,24})for(int ox:new int[]{-24,0,24})if(!source.getNoiseBiome((x+ox)>>2,24,(z+oz)>>2,sampler).is(target))inside=false;
            if(inside){at=new BlockPos(x,96,z);break search;}
        }
        level.getChunk(at);
        return new BlockPos(at.getX(),level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,at.getX(),at.getZ()),at.getZ());
    }
    private NativeWorlds(){}
}
