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
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 倒木特征的原版放置检查：在固定平台上直接放置 {@code fallen_tree_*}，
 * 断言确实生成了横向原木（而不是只有树桩或被空间条件全部拒绝）。
 *
 * <p>这里只验证特征本身能放置；自然生成里的稀疏度与选址由数据里的 placement 决定。
 */
public final class FallenLogGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS=DeferredRegister.create(Registries.TEST_FUNCTION,LogosDimension.ID);
    /** 独立展台，避开其它测试用的区域。 */
    public static final BlockPos STUDY=new BlockPos(1000100,245,1000000);

    public static void register(IEventBus bus){
        var tests=Map.<String,Consumer<GameTestHelper>>of("fallen_log_placement",FallenLogGameTests::placement);
        tests.forEach((name,test)->FUNCTIONS.register(name,()->test));FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event)->{
            var env=event.registerEnvironment(LogosDimension.id("fallen_logs"),new TestEnvironmentDefinition.AllOf());
            for(String name:tests.keySet())event.registerTest(LogosDimension.id(name),new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION,LogosDimension.id(name)),new TestData<>(env,Identifier.withDefaultNamespace("empty"),1600,0,true,Rotation.NONE,false,1,1,false,16)));
        });
    }

    private static void placement(GameTestHelper h){
        ServerLevel level=Objects.requireNonNull(h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA));
        // 一条 13×57 的土台，上方清空，给倒木留出横放空间。
        for(int x=-6;x<=6;x++)for(int z=-28;z<=28;z++){
            level.setBlock(STUDY.offset(x,-1,z),Blocks.DIRT.defaultBlockState(),Block.UPDATE_CLIENTS);
            for(int y=0;y<=4;y++)level.setBlock(STUDY.offset(x,y,z),Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS);
        }
        var feature=level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE)
            .getOrThrow(ResourceKey.create(Registries.CONFIGURED_FEATURE,LogosDimension.id("fallen_tree_cloudsea_ancient_beech"))).value();
        var log=CloudseaBlocks.TRUNKS.get(CloudseaBiome.ANCIENT_BEECH).get();
        boolean fallen=false,stump=false;
        for(int attempt=0;attempt<24&&!fallen;attempt++){
            feature.place(level,level.getChunkSource().getGenerator(),RandomSource.create(attempt),STUDY.offset(0,0,0));
            for(int x=-6;x<=6;x++)for(int z=-28;z<=28;z++)for(int y=0;y<=4;y++){
                var state=level.getBlockState(STUDY.offset(x,y,z));
                if(state.getBlock()!=log)continue;
                if(state.getValue(RotatedPillarBlock.AXIS)!=Direction.Axis.Y)fallen=true;else stump=true;
            }
        }
        h.assertTrue(stump,"只生成了树桩，没有树干：倒木特征没有正常放置");
        h.assertTrue(fallen,"没有生成横向原木：倒木没有真正倒下");
        h.succeed();
    }
}
