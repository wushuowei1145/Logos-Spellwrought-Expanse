package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.cloudsea.JourneyYoungGrassBlock;
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
 * 幼年原野牧草：撒骨粉要逐级催熟，长满之后变成成年原野牧草。
 *
 * <p>只验证方块行为（年龄推进与换代），不验证随机刻的自然生长速度。
 */
public final class YoungGrassGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);

    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("young_grass_grows",YoungGrassGameTests::grows);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("young_grass"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),200,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }

    private static void grows(GameTestHelper h){
        var level=h.getLevel();
        var pos=h.absolutePos(new BlockPos(4,3,4));
        level.setBlockAndUpdate(pos.below(),Blocks.DIRT.defaultBlockState());
        var young=CloudseaBlocks.MEADOW_GRASS_YOUNG.get();
        level.setBlockAndUpdate(pos,young.defaultBlockState());
        var block=(JourneyYoungGrassBlock)young;
        var state=level.getBlockState(pos);
        h.assertTrue(state.getValue(JourneyYoungGrassBlock.AGE)==0,"幼年牧草应从 age=0 开始");

        block.performBonemeal(level,level.getRandom(),pos,state);
        h.assertTrue(level.getBlockState(pos).getValue(JourneyYoungGrassBlock.AGE)==1,"第一次骨粉没有推进到 age=1");

        var second=level.getBlockState(pos);
        block.performBonemeal(level,level.getRandom(),pos,second);
        h.assertTrue(level.getBlockState(pos).getValue(JourneyYoungGrassBlock.AGE)==2,"第二次骨粉没有推进到 age=2");

        var third=level.getBlockState(pos);
        block.performBonemeal(level,level.getRandom(),pos,third);
        h.assertTrue(level.getBlockState(pos).is(CloudseaBlocks.MEADOW_GRASS.get()),
            "长满之后没有变成成年原野牧草");
        h.succeed();
    }

    private YoungGrassGameTests(){}
}
