package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.*;

/** Staged growth and seasonal comparison plus actual client harvest/bonemeal packets. */
@LDLRegisterClient(name="woodland_blue_berries",group="blue_berries",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public final class BerryBushView implements UIScenario {
    private static final int Y=230;private static final BlockPos RIPE=new BlockPos(0,Y+1,3);private int oldRandomTicks;
    @Override public void configure(ScenarioOptions o){o.tags("blue_berries").defaultTimeoutMs(120000).scenarioTimeoutMs(240000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("berry camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.fov().set(55);mc.options.renderDistance().set(8);mc.options.broadcastOptions();})
            .server("stage berry growth",ctx->{
                var l=ctx.server().getLevel(LogosDimension.CLOUDSEA);oldRandomTicks=l.getGameRules().get(GameRules.RANDOM_TICK_SPEED);l.getGameRules().set(GameRules.RANDOM_TICK_SPEED,0,ctx.server());
                for(int x=-12;x<=12;x++)for(int z=-7;z<=13;z++){
                    l.setBlockAndUpdate(new BlockPos(x,Y,z),CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get().defaultBlockState());
                    for(int y=1;y<7;y++)l.removeBlock(new BlockPos(x,Y+y,z),false);
                }
                for(int age=0;age<4;age++)l.setBlockAndUpdate(new BlockPos(-5+age*3,Y+1,0),CloudseaBlocks.BRAMBLE.get().defaultBlockState().setValue(SweetBerryBushBlock.AGE,age).setValue(SeasonalLeavesBlock.SEASON,1));
                for(int season=0;season<4;season++)l.setBlockAndUpdate(new BlockPos(-5+season*3,Y+1,-4),CloudseaBlocks.BRAMBLE.get().defaultBlockState().setValue(SweetBerryBushBlock.AGE,3).setValue(SeasonalLeavesBlock.SEASON,season));
                l.setBlockAndUpdate(RIPE,CloudseaBlocks.BRAMBLE.get().defaultBlockState().setValue(SweetBerryBushBlock.AGE,3).setValue(SeasonalLeavesBlock.SEASON,1));
                l.setBlockAndUpdate(RIPE.west(3),Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE,3));
                l.clockManager().setTotalTicks(l.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),JourneySeasons.SEASON_TICKS+6000);l.getWeatherData().setRaining(false);
                DimensionTravel.teleport(ctx.player(),l,new Vec3(.5,Y+4.5,11),180,14);ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            }).waitUntil("berry chunks received",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.getBlockState(RIPE).is(CloudseaBlocks.BRAMBLE.get());}).ticks(80)
            .check("native berry behavior",ctx->Minecraft.getInstance().level.getBlockState(RIPE).getBlock() instanceof SweetBerryBushBlock)
            .checkServer("mature silhouette larger than vanilla",ctx->{var l=ctx.player().level();return l.getBlockState(RIPE).getShape(l,RIPE).bounds().getYsize()>1.3;})
            .screenshot("blueberry_growth_seasons_vanilla_comparison")
            .server("close berry camera",ctx->DimensionTravel.teleport(ctx.player(),ctx.player().level(),new Vec3(.5,Y+1.5,7),180,23)).ticks(25)
            .screenshot("large_blueberry_bush_closeup")
            .step("right click blue berries",ctx->{var mc=Minecraft.getInstance();mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(RIPE.getCenter(),Direction.SOUTH,RIPE,false));})
            .waitUntil("harvest reaches client",ctx->Minecraft.getInstance().level.getBlockState(RIPE).getValue(SweetBerryBushBlock.AGE)==1)
            .checkServer("click harvest resets age",ctx->ctx.player().level().getBlockState(RIPE).getValue(SweetBerryBushBlock.AGE)==1)
            .server("prepare bone meal",ctx->ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BONE_MEAL,2))).ticks(10)
            .step("bone meal first growth",ctx->{var mc=Minecraft.getInstance();mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(RIPE.getCenter(),Direction.SOUTH,RIPE,false));}).ticks(10)
            .step("bone meal mature growth",ctx->{var mc=Minecraft.getInstance();mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(RIPE.getCenter(),Direction.SOUTH,RIPE,false));})
            .waitUntil("regrowth reaches client",ctx->Minecraft.getInstance().level.getBlockState(RIPE).getValue(SweetBerryBushBlock.AGE)==3)
            .checkServer("actual bone meal regrows berries",ctx->ctx.player().level().getBlockState(RIPE).getValue(SweetBerryBushBlock.AGE)==3)
            .teardownServer("restore random ticks",ctx->ctx.server().getLevel(LogosDimension.CLOUDSEA).getGameRules().set(GameRules.RANDOM_TICK_SPEED,oldRandomTicks,ctx.server()))
            .teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
}
