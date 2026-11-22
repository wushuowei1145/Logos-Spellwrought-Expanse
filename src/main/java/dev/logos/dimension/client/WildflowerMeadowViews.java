package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 对照花田使用真实放置特征；自然镜头不放置或整平任何地表方块。 */
public abstract class WildflowerMeadowViews implements UIScenario {
    private static final int Y=180;
    protected boolean natural(){return false;}
    private BlockPos at;
    private int flowers,grass;
    @Override public void configure(ScenarioOptions o){o.tags("wildflower_meadow").defaultTimeoutMs(180000).scenarioTimeoutMs(300000);}
    private static boolean flower(net.minecraft.world.level.block.state.BlockState state){return state.is(CloudseaBlocks.MEADOW_DAISIES.get())||state.is(CloudseaBlocks.MEADOW_BELLS.get());}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("花原镜头",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(8);mc.options.fov().set(65);mc.options.broadcastOptions();})
            .server("准备花原样地",ctx->{
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),6000);
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                if(natural())at=NativeWorlds.locateBiome(level,CloudseaBiome.ROSE_DUNES.id());
                else{
                    at=new BlockPos(0,Y+1,0);
                    for(int x=-24;x<=24;x++)for(int z=-24;z<=24;z++){
                        level.setBlockAndUpdate(new BlockPos(x,Y,z),CloudseaBlocks.GROUND.get(CloudseaBiome.ROSE_DUNES).get().defaultBlockState());
                        for(int y=1;y<=4;y++)level.setBlockAndUpdate(new BlockPos(x,Y+y,z),Blocks.AIR.defaultBlockState());
                    }
                }
                DimensionTravel.teleport(ctx.player(),level,at.getBottomCenter().add(0,3,12),180,17);
                ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            });
        if(!natural()){
            s.runCommand("execute in logos_dimension:cloudsea run fillbiome -24 172 -24 24 179 24 logos_dimension:cloudsea_rose_dunes")
                .runCommand("execute in logos_dimension:cloudsea run fillbiome -24 180 -24 24 187 24 logos_dimension:cloudsea_rose_dunes")
                .server("原版花带与草层放置",ctx->{
                    var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                    for(String feature:new String[]{"cloudsea_rose_dunes_floor","wildflower_understory"}){
                        var placed=level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE,LogosDimension.id(feature)));
                        for(int x:new int[]{-16,0})for(int z:new int[]{-16,0})placed.value().placeWithBiomeCheck(level,level.getChunkSource().getGenerator(),net.minecraft.util.RandomSource.create(602211L+x*31+z+feature.hashCode()),new BlockPos(x,Y+1,z));
                    }
                    flowers=grass=0;
                    for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
                        var state=level.getBlockState(new BlockPos(x,Y+1,z));
                        if(flower(state))flowers++;
                        if(state.is(Blocks.SHORT_GRASS)||state.is(CloudseaBlocks.MEADOW_GRASS_YOUNG.get())||state.is(CloudseaBlocks.MEADOW_GRASS.get()))grass++;
                    }
                    System.out.println("WILDFLOWER_SAMPLE flowers="+flowers+" grass="+grass);
                })
                .checkServer("花簇覆盖多于草且留有空隙",ctx->flowers>grass&&flowers>300&&grass>50&&flowers+grass<1024)
                .checkServer("花簇不能在石头上扎根",ctx->{
                    var level=ctx.player().level();var p=new BlockPos(22,Y+1,22);
                    level.setBlockAndUpdate(p.below(),Blocks.STONE.defaultBlockState());
                    boolean result=!CloudseaBlocks.MEADOW_DAISIES.get().defaultBlockState().canSurvive(level,p)&&!CloudseaBlocks.MEADOW_BELLS.get().defaultBlockState().canSurvive(level,p);
                    level.setBlockAndUpdate(p.below(),Blocks.DIRT.defaultBlockState());return result;
                });
        }
        s.waitUntil("花原地面抵达客户端",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&!mc.level.getBlockState(at.below()).isAir();})
            .ticks(300)
            .checkServer("保持原版噪声生成器",ctx->NativeWorlds.vanilla(ctx.player().level()))
            .check("身处野花草甸",ctx->Minecraft.getInstance().level.getBiome(at).is(LogosDimension.id(CloudseaBiome.ROSE_DUNES.id())))
            .screenshot(natural()?"wildflower_natural":"wildflower_native_feature_sample")
            .step("俯看花簇",ctx->Minecraft.getInstance().player.setXRot(32)).ticks(80)
            .screenshot(natural()?"wildflower_natural_overview":"wildflower_cluster_detail")
            .teardown("恢复界面",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="wildflower_sample",group="wildflower_meadow",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Sample extends WildflowerMeadowViews{}
    @LDLRegisterClient(name="wildflower_natural",group="wildflower_meadow",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Natural extends WildflowerMeadowViews{@Override protected boolean natural(){return true;}}
}
