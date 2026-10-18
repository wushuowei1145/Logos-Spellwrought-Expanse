package dev.logos.dimension.gametest;

import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Test-only native Feature placement on an isolated gallery plus real generated forest checks. */
public final class TallTreeGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    public static final BlockPos STUDY=new BlockPos(1000000,245,1000000);
    public record Observation(BlockPos base,int height){}
    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("tall_tree_shapes",TallTreeGameTests::shapes,"tall_tree_native_forest",TallTreeGameTests::natural);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("tall_trees"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),1600,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }
    public static void placeStudy(ServerLevel level){
        // A development/test gallery only. Production trees are placed solely by data features.
        for(int x=-30;x<=30;x++)for(int z=-14;z<=14;z++){
            level.setBlock(STUDY.offset(x,-1,z),Blocks.DIRT.defaultBlockState(),Block.UPDATE_CLIENTS);
            for(int y=0;y<=40;y++)level.setBlock(STUDY.offset(x,y,z),Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS);
        }
        var registry=level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
        for(var pair:Map.of("tall_trees_cloudsea_ancient_beech",-15,"tall_trees_cloudsea_fir_heart",15).entrySet()){
            var feature=registry.getOrThrow(ResourceKey.create(Registries.CONFIGURED_FEATURE,LogosDimension.id(pair.getKey()))).value();
            if(!feature.place(level,level.getChunkSource().getGenerator(),RandomSource.create(14),STUDY.offset(pair.getValue(),0,0)))throw new IllegalStateException("Native tree failed gallery placement: "+pair.getKey());
        }
    }
    private static void shapes(GameTestHelper h){
        var level=Objects.requireNonNull(h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA));placeStudy(level);
        int[] logs={0,0},leaves={0,0},top={0,0};
        for(int x=-28;x<=28;x++)for(int z=-12;z<=12;z++)for(int y=0;y<=40;y++){
            var state=level.getBlockState(STUDY.offset(x,y,z));int i=x<0?0:1;
            if(state.is(CloudseaBlocks.TRUNKS.get(i==0?CloudseaBiome.ANCIENT_BEECH:CloudseaBiome.FIR_HEART).get())){logs[i]++;top[i]=Math.max(top[i],y);}
            if(state.is(CloudseaBlocks.LEAVES.get(i==0?CloudseaBiome.ANCIENT_BEECH:CloudseaBiome.FIR_HEART).get()))leaves[i]++;
        }
        h.assertTrue(top[0]>=15&&logs[0]>25&&leaves[0]>200,"Broadleaf crown or tall branches missing: "+Arrays.toString(top)+"/"+Arrays.toString(logs));
        h.assertTrue(top[1]>=21&&logs[1]>=88&&leaves[1]>200,"Giant conifer does not have a tall 2x2 trunk");
        System.out.println("TALL_TREE_GALLERY tops="+Arrays.toString(top)+" logs="+Arrays.toString(logs)+" leaves="+Arrays.toString(leaves));h.succeed();
    }
    public static Observation findNatural(ServerLevel level){
        var at=NativeWorlds.locateBiome(level,CloudseaBiome.FIR_HEART.id());Observation best=new Observation(at,0);
        // Level.getHeight does not generate absent chunks. Load this bounded test sample first.
        for(int x=(at.getX()-48)>>4;x<=(at.getX()+47)>>4;x++)for(int z=(at.getZ()-48)>>4;z<=(at.getZ()+47)>>4;z++)level.getChunk(x,z);
        for(int x=at.getX()-48;x<at.getX()+48;x++)for(int z=at.getZ()-48;z<at.getZ()+48;z++){
            int top=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,run=0;
            for(int y=top;y>top-55&&level.getBlockState(new BlockPos(x,y,z)).is(CloudseaBlocks.TRUNKS.get(CloudseaBiome.FIR_HEART).get());y--)run++;
            if(run>best.height())best=new Observation(new BlockPos(x,top-run+1,z),run);
        }
        return best;
    }
    private static void natural(GameTestHelper h){
        var level=Objects.requireNonNull(h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA));var tree=findNatural(level);
        h.assertTrue(tree.height()>=22,"Real native conifer biome lacks tall trees: "+tree);System.out.println("TALL_NATIVE "+tree);h.succeed();
    }
    private TallTreeGameTests(){}
}
