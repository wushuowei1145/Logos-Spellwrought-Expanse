package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBiome;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 草皮的骨粉：对季节草皮撒骨粉必须真的长出植被，而不是白耗一份骨粉。
 *
 * <p>只验证世界内行为（放方块 → 撒骨粉 → 上方出现植被），不验证具体长出哪种植物。
 */
public final class TurfGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);

    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("turf_bonemeal",TurfGameTests::bonemeal);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("turf"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),200,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }

    private static void bonemeal(GameTestHelper h){
        var level=h.getLevel();
        var turf=CloudseaBlocks.GROUND.get(CloudseaBiome.values()[0]).get();
        var pos=h.absolutePos(new BlockPos(4,3,4));
        level.setBlockAndUpdate(pos,turf.defaultBlockState());
        level.setBlockAndUpdate(pos.above(),Blocks.AIR.defaultBlockState());
        var state=level.getBlockState(pos);
        var block=(net.minecraft.world.level.block.BonemealableBlock)turf;
        h.assertTrue(block.isValidBonemealTarget(level,pos,state),
            "草皮上方是空气，却判定为不能撒骨粉");
        var random=level.getRandom();
        for(int i=0;i<4;i++)block.performBonemeal(level,random,pos,state);
        boolean grew=false;
        for(int x=-3;x<=3&&!grew;x++)for(int z=-3;z<=3&&!grew;z++)for(int y=1;y<=3&&!grew;y++)
            grew=!level.getBlockState(pos.offset(x,y,z)).isAir();
        h.assertTrue(grew,"撒了骨粉但草皮上方什么都没长出来");
        h.succeed();
    }

    private TurfGameTests(){}
}
