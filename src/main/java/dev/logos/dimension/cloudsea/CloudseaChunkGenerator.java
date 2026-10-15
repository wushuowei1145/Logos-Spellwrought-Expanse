package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import dev.logos.dimension.world.NaturalTreeShape;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.core.*;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Pure-magic surface and caves. Only decorative settlements are generated; there are no ore or mob passes. */
public final class CloudseaChunkGenerator extends ChunkGenerator {
    public static final MapCodec<CloudseaChunkGenerator> CODEC=RecordCodecBuilder.mapCodec(i->i.group(BiomeSource.CODEC.fieldOf("biome_source").forGetter(CloudseaChunkGenerator::getBiomeSource)).apply(i,CloudseaChunkGenerator::new));
    private final CloudseaBiomeSource source;private volatile CloudseaTerrain terrain;private volatile CloudseaSettlements settlements;
    public CloudseaChunkGenerator(BiomeSource source){super(source);if(!(source instanceof CloudseaBiomeSource s))throw new IllegalArgumentException("Cloudsea requires its biome source");this.source=s;terrain=s.terrain();settlements=new CloudseaSettlements(terrain);}
    public CloudseaTerrain terrain(){return terrain;}public CloudseaSettlements settlements(){return settlements;}
    @Override protected MapCodec<? extends ChunkGenerator> codec(){return CODEC;}
    @Override public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> sets,RandomState random,long seed){source.bindSeed(seed);terrain=source.terrain();settlements=new CloudseaSettlements(terrain);return super.createState(sets,random,seed);}
    @Override public void createStructures(net.minecraft.core.RegistryAccess access,ChunkGeneratorStructureState state,StructureManager manager,ChunkAccess chunk,net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates,net.minecraft.resources.ResourceKey<Level> dimension){}
    @Override public void applyCarvers(WorldGenRegion level,long seed,RandomState random,BiomeManager biomes,StructureManager structures,ChunkAccess chunk){}
    @Override public void buildSurface(WorldGenRegion level,StructureManager structures,RandomState random,ChunkAccess chunk){}
    @Override public void spawnOriginalMobs(WorldGenRegion region){}
    @Override public int getMinY(){return CloudseaTerrain.MIN_Y;}@Override public int getGenDepth(){return CloudseaTerrain.HEIGHT;}@Override public int getSeaLevel(){return CloudseaTerrain.WATER_LEVEL;}
    private record Plant(int x,int z,int base,CloudseaBiome kind,NaturalTreeShape.Blueprint shape){}
    private Plant plant(int x,int z){
        int gx=Math.floorDiv(x,16),gz=Math.floorDiv(z,16);long seed=CloudseaNoise.hash(gx,0,gz,terrain.seed()+491);
        int ax=gx*16+6+(int)((seed>>>8)&3),az=gz*16+6+(int)((seed>>>12)&3);var c=terrain.column(ax,az);
        double chance=CloudseaNoise.unit(seed);
        var form=switch(c.biome().form){case SPIRE,STEPS->NaturalTreeShape.Form.CONIFER;case RIBBON,DUNE->NaturalTreeShape.Form.UMBRELLA;case HANGING->NaturalTreeShape.Form.WEEPING;default->NaturalTreeShape.Form.ROUND;};
        int height=(form==NaturalTreeShape.Form.CONIFER?11:7)+(int)((seed>>>18)&3);
        var shape=chance<c.biome().flora&&c.ground()>=getSeaLevel()?NaturalTreeShape.create(form,height,seed):null;
        return new Plant(ax,az,c.highest()+1,c.biome(),shape);
    }
    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender blend,RandomState random,StructureManager structures,ChunkAccess chunk){
        var p=new BlockPos.MutableBlockPos();var floor=chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);var surface=chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        // The tree anchor and blueprint are shared by all 256 columns of this chunk.
        var plant=plant(chunk.getPos().getMinBlockX()+8,chunk.getPos().getMinBlockZ()+8);
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            int wx=chunk.getPos().getMinBlockX()+x,wz=chunk.getPos().getMinBlockZ()+z;var c=terrain.column(wx,wz);var village=settlements.sample(wx,wz);
            int top=Math.max(c.highest()+25,getSeaLevel());if(village!=null)for(var h:village.houses())top=Math.max(top,h.y()+27);
            for(int y=getMinY();y<=top;y++){var state=stateAt(wx,y,wz,c,plant,village);if(state.isAir())continue;chunk.setBlockState(p.set(wx,y,wz),state);floor.update(x,y,z,state);surface.update(x,y,z,state);}
        }
        return CompletableFuture.completedFuture(chunk);
    }
    public BlockState stateAt(int x,int y,int z,CloudseaTerrain.Column c){return stateAt(x,y,z,c,plant(x,z),settlements.sample(x,z));}
    private BlockState stateAt(int x,int y,int z,CloudseaTerrain.Column c,Plant p,CloudseaSettlements.Village village){
        if(y<getMinY()||y>=getMinY()+getGenDepth())return Blocks.AIR.defaultBlockState();
        if(y<getMinY()+4)return Blocks.BEDROCK.defaultBlockState();
        var structure=settlements.blockAt(village,x,y,z,c.ground());if(structure!=null)return structure;
        if(c.sheet(y))return y==c.lowerTop()||y==c.upperTop()?CloudseaBlocks.GROUND.get(c.biome()).get().defaultBlockState():CloudseaBlocks.PORCELAIN.get().defaultBlockState();
        if(y>c.ground()){
            if(c.biome().form==CloudseaBiome.Form.BASIN&&y<=getSeaLevel())return Blocks.WATER.defaultBlockState();
            if(p.shape!=null){
                int dx=x-p.x,dz=z-p.z,dy=y-p.base;
                var part=p.shape.at(dx,dy,dz);
                if(part==NaturalTreeShape.Part.LEAF)return CloudseaBlocks.LEAVES.get(p.kind).get().defaultBlockState();
                if(part!=NaturalTreeShape.Part.AIR){
                    var axis=part==NaturalTreeShape.Part.WOOD_X?Direction.Axis.X:part==NaturalTreeShape.Part.WOOD_Z?Direction.Axis.Z:Direction.Axis.Y;
                    return CloudseaBlocks.STEM.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS,axis);
                }
                if(p.kind.form==CloudseaBiome.Form.HANGING&&p.shape.at(dx,dy+1,dz)==NaturalTreeShape.Part.LEAF&&Math.floorMod(dx*7+dz*11,19)==0)
                    return CloudseaBlocks.LANTERN.get().defaultBlockState();
            }
            if(y==c.upperBottom()-1&&(CloudseaNoise.hash(x,y,z,terrain.seed())&7)==0)return CloudseaBlocks.ROOT.get().defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        }
        if(terrain.cave(x,y,z,c)){
            long salt=CloudseaNoise.hash(x,y,z,terrain.seed()+509);
            if((salt&31)<6&&!terrain.cave(x,y+1,z,c))return CloudseaBlocks.ROOT.get().defaultBlockState();
            if((salt&31)<4&&!terrain.cave(x,y-1,z,c))return (y<-65?CloudseaBlocks.DEMON_REED:CloudseaBlocks.EYE_FERN).get().defaultBlockState();
            return Blocks.AIR.defaultBlockState();
        }
        if(y<-65)return CloudseaBlocks.DEMONSTONE.get().defaultBlockState();
        return y==c.ground()?CloudseaBlocks.GROUND.get(c.biome()).get().defaultBlockState():y>c.ground()-5?CloudseaBlocks.PORCELAIN.get().defaultBlockState():CloudseaBlocks.MANTLE.get().defaultBlockState();
    }
    @Override public int getBaseHeight(int x,int z,Heightmap.Types type,LevelHeightAccessor heights,RandomState random){var c=terrain.column(x,z);var p=plant(x,z);var v=settlements.sample(x,z);int top=Math.min(heights.getMaxY(),c.highest()+30);if(v!=null)for(var h:v.houses())top=Math.max(top,h.y()+27);for(int y=top;y>=heights.getMinY();y--)if(type.isOpaque().test(stateAt(x,y,z,c,p,v)))return y+1;return heights.getMinY();}
    @Override public NoiseColumn getBaseColumn(int x,int z,LevelHeightAccessor heights,RandomState random){var c=terrain.column(x,z);var p=plant(x,z);var v=settlements.sample(x,z);var states=new BlockState[heights.getHeight()];for(int i=0;i<states.length;i++)states[i]=stateAt(x,i+heights.getMinY(),z,c,p,v);return new NoiseColumn(heights.getMinY(),states);}
    @Override public void addDebugScreenInfo(List<String> lines,RandomState random,BlockPos feet){lines.add("Cloudsea: "+terrain.nearest(feet.getX(),feet.getZ()).biome()+" / "+(feet.getY()<-65?"demon depths":feet.getY()<0?"root caves":"surface"));}
}
