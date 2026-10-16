package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import dev.logos.dimension.world.NaturalTreeShape;
import net.minecraft.core.*;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** Temperate surface, native cave/canyon carvers and mineshafts, with all ore feature passes empty. */
public final class CloudseaChunkGenerator extends ChunkGenerator {
    public static final MapCodec<CloudseaChunkGenerator> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
        BiomeSource.CODEC.fieldOf("biome_source").forGetter(CloudseaChunkGenerator::getBiomeSource),
        NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(g->g.settings)).apply(i,CloudseaChunkGenerator::new));
    private final CloudseaBiomeSource source;
    private final Holder<NoiseGeneratorSettings> settings;
    private final NoiseBasedChunkGenerator caveGenerator;
    private volatile CloudseaTerrain terrain;
    private volatile CloudseaSettlements settlements;
    public CloudseaChunkGenerator(BiomeSource source,Holder<NoiseGeneratorSettings> settings){
        super(source);if(!(source instanceof CloudseaBiomeSource s))throw new IllegalArgumentException("Cloudsea requires its biome source");
        this.source=s;this.settings=settings;caveGenerator=new NoiseBasedChunkGenerator(source,settings);
        terrain=s.terrain();settlements=new CloudseaSettlements(terrain);
    }
    public CloudseaTerrain terrain(){return terrain;}
    public CloudseaSettlements settlements(){return settlements;}
    @Override protected MapCodec<? extends ChunkGenerator> codec(){return CODEC;}
    @Override public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> sets,RandomState random,long seed){
        source.bindSeed(seed);terrain=source.terrain();settlements=new CloudseaSettlements(terrain);return super.createState(sets,random,seed);
    }
    @Override public void applyCarvers(WorldGenRegion level,long seed,RandomState random,BiomeManager biomes,StructureManager structures,ChunkAccess chunk){caveGenerator.applyCarvers(level,seed,random,biomes,structures,chunk);}
    @Override public void buildSurface(WorldGenRegion level,StructureManager structures,RandomState random,ChunkAccess chunk){}
    @Override public void spawnOriginalMobs(WorldGenRegion region){
        var pos=region.getCenter();var random=new WorldgenRandom(new LegacyRandomSource(region.getSeed()));
        random.setDecorationSeed(region.getSeed(),pos.getMinBlockX(),pos.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(region,region.getBiome(new BlockPos(pos.getMinBlockX(),100,pos.getMinBlockZ())),pos,random);
    }
    @Override public void applyBiomeDecoration(WorldGenLevel level,ChunkAccess chunk,StructureManager manager){
        super.applyBiomeDecoration(level,chunk,manager);
        var pos=chunk.getPos();var village=settlements.sample(pos.getMiddleBlockX(),pos.getMiddleBlockZ());if(village==null)return;
        for(int i=0;i<village.houses().size();i++){
            var house=village.houses().get(i);
            if(i%3!=0||Math.floorDiv(house.x(),16)!=pos.x()||Math.floorDiv(house.z(),16)!=pos.z())continue;
            var feet=new BlockPos(house.x(),house.y()+1,house.z());
            if(!level.isEmptyBlock(feet)||!level.isEmptyBlock(feet.above()))continue;
            var villager=net.minecraft.world.entity.EntityType.VILLAGER.create(level.getLevel(),net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
            if(villager!=null){villager.setPos(feet.getBottomCenter());villager.finalizeSpawn(level,level.getCurrentDifficultyAt(feet),net.minecraft.world.entity.EntitySpawnReason.STRUCTURE,null);level.addFreshEntity(villager);}
        }
    }
    @Override public int getMinY(){return CloudseaTerrain.MIN_Y;}
    @Override public int getGenDepth(){return CloudseaTerrain.HEIGHT;}
    @Override public int getSeaLevel(){return CloudseaTerrain.WATER_LEVEL;}
    private record Tree(int x,int z,int base,CloudseaBiome kind,NaturalTreeShape.Blueprint shape){}
    private List<Tree> trees(int minX,int minZ,int width){
        var result=new ArrayList<Tree>();
        for(int gz=Math.floorDiv(minZ-7,12);gz<=Math.floorDiv(minZ+width+7,12);gz++)for(int gx=Math.floorDiv(minX-7,12);gx<=Math.floorDiv(minX+width+7,12);gx++){
            long hash=CloudseaNoise.hash(gx,0,gz,terrain.seed()+491);
            int x=gx*12+2+(int)((hash>>>8)&7),z=gz*12+2+(int)((hash>>>12)&7);var c=terrain.column(x,z);
            if(c.ground()<=c.water()||CloudseaNoise.unit(hash)>c.biome().treeDensity()||c.ground()>225)continue;
            int slope=Math.abs(terrain.column(x+3,z).ground()-terrain.column(x-3,z).ground());if(slope>5)continue;
            var form=c.biome().conifer()?NaturalTreeShape.Form.CONIFER:NaturalTreeShape.Form.ROUND;
            int height=(c.biome().conifer()?10:c.biome().birch()?8:7)+(int)((hash>>>18)&3);
            result.add(new Tree(x,z,c.ground()+1,c.biome(),NaturalTreeShape.create(form,height,hash)));
        }
        return result;
    }
    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender blend,RandomState random,StructureManager structures,ChunkAccess chunk){
        var pos=new BlockPos.MutableBlockPos();var floor=chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);var surface=chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minX=chunk.getPos().getMinBlockX(),minZ=chunk.getPos().getMinBlockZ();var trees=trees(minX,minZ,16);
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            int wx=minX+x,wz=minZ+z;var c=terrain.column(wx,wz);var village=settlements.sample(wx,wz);
            int top=c.highest()+24;if(village!=null)for(var house:village.houses())top=Math.max(top,house.y()+35);
            for(int y=getMinY();y<=top;y++){
                var state=stateAt(wx,y,wz,c,trees,village);if(state.isAir())continue;
                chunk.setBlockState(pos.set(wx,y,wz),state);floor.update(x,y,z,state);surface.update(x,y,z,state);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }
    public BlockState stateAt(int x,int y,int z,CloudseaTerrain.Column c){return stateAt(x,y,z,c,trees(x,z,1),settlements.sample(x,z));}
    private BlockState stateAt(int x,int y,int z,CloudseaTerrain.Column c,List<Tree> trees,CloudseaSettlements.Village village){
        if(y<getMinY()||y>=getMinY()+getGenDepth())return Blocks.AIR.defaultBlockState();
        if(y<getMinY()+4)return Blocks.BEDROCK.defaultBlockState();
        var building=settlements.blockAt(village,x,y,z,c.ground());if(building!=null)return building;
        if(y>c.ground()){
            if(y<=c.water())return (c.water()>220&&y==c.water()?Blocks.ICE:Blocks.WATER).defaultBlockState();
            if(village==null)for(var tree:trees){
                var part=tree.shape.at(x-tree.x,y-tree.base,z-tree.z);
                if(part==NaturalTreeShape.Part.LEAF)return CloudseaBlocks.LEAVES.get(tree.kind).get().defaultBlockState();
                if(part!=NaturalTreeShape.Part.AIR){
                    var axis=part==NaturalTreeShape.Part.WOOD_X?Direction.Axis.X:part==NaturalTreeShape.Part.WOOD_Z?Direction.Axis.Z:Direction.Axis.Y;
                    Block log=tree.kind.birch()?Blocks.BIRCH_LOG:tree.kind.conifer()?Blocks.SPRUCE_LOG:tree.kind==CloudseaBiome.JADE_WHIRLS?CloudseaBlocks.STEM.get():Blocks.OAK_LOG;
                    return log.defaultBlockState().setValue(RotatedPillarBlock.AXIS,axis);
                }
            }
            if(y==c.ground()+1&&c.ground()>270&&!c.glacier())return Blocks.SNOW.defaultBlockState();
            if(y==c.ground()+1&&!c.biome().barren()&&c.ground()>c.water()){
                long hash=CloudseaNoise.hash(x,0,z,terrain.seed()+719);int roll=(int)(hash&255);
                if(c.river()&&roll<45)return CloudseaBlocks.REED.get().defaultBlockState();
                if(c.biome().treeDensity()>.4&&roll<27)return (roll%3==0?CloudseaBlocks.BRACKEN:CloudseaBlocks.FERN).get().defaultBlockState();
                if(roll<58)return CloudseaBlocks.MEADOW_GRASS.get().defaultBlockState();
                if(roll==70||roll==71)return CloudseaBlocks.BLUEBELL.get().defaultBlockState();
                if(roll==72)return CloudseaBlocks.FOXGLOVE.get().defaultBlockState();
                if(roll==73)return CloudseaBlocks.LUPINE.get().defaultBlockState();
                if(roll==74)return Blocks.OXEYE_DAISY.defaultBlockState();
                if(roll==75)return Blocks.DANDELION.defaultBlockState();
            }
            return Blocks.AIR.defaultBlockState();
        }
        if(terrain.cave(x,y,z,c))return (y<getMinY()+12?Blocks.LAVA:Blocks.AIR).defaultBlockState();
        if(y==c.ground()){
            if(c.ocean()||c.river())return (c.ocean()?Blocks.SAND:Blocks.GRAVEL).defaultBlockState();
            if(c.biome()==CloudseaBiome.OPAL_DUNES)return CloudseaBlocks.LIMESTONE.get().defaultBlockState();
            if(c.glacier())return Blocks.PACKED_ICE.defaultBlockState();
            if(c.ground()>282)return Blocks.SNOW_BLOCK.defaultBlockState();
            if(c.ground()>220)return Blocks.STONE.defaultBlockState();
            return CloudseaBlocks.GROUND.get(c.biome()).get().defaultBlockState();
        }
        if(c.glacier()&&y>c.ground()-14)return Blocks.PACKED_ICE.defaultBlockState();
        if(y>c.ground()-4&&c.ground()<221)return (c.ocean()?Blocks.SAND:Blocks.DIRT).defaultBlockState();
        if(c.biome()==CloudseaBiome.OPAL_DUNES&&y>c.ground()-18)return CloudseaBlocks.LIMESTONE.get().defaultBlockState();
        return (y<0?Blocks.DEEPSLATE:Blocks.STONE).defaultBlockState();
    }
    @Override public int getBaseHeight(int x,int z,Heightmap.Types type,LevelHeightAccessor heights,RandomState random){
        var c=terrain.column(x,z);var trees=trees(x,z,1);var village=settlements.sample(x,z);int top=Math.min(heights.getMaxY(),c.highest()+35);
        if(village!=null)for(var house:village.houses())top=Math.max(top,house.y()+35);
        for(int y=top;y>=heights.getMinY();y--)if(type.isOpaque().test(stateAt(x,y,z,c,trees,village)))return y+1;return heights.getMinY();
    }
    @Override public NoiseColumn getBaseColumn(int x,int z,LevelHeightAccessor heights,RandomState random){
        var c=terrain.column(x,z);var trees=trees(x,z,1);var village=settlements.sample(x,z);var states=new BlockState[heights.getHeight()];
        for(int i=0;i<states.length;i++)states[i]=stateAt(x,i+heights.getMinY(),z,c,trees,village);return new NoiseColumn(heights.getMinY(),states);
    }
    @Override public void addDebugScreenInfo(List<String> lines,RandomState random,BlockPos feet){lines.add("Journey: "+terrain.column(feet.getX(),feet.getZ()).biome().title);}
}
