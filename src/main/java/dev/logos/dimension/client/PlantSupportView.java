package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Actual client right-click packets: stone rejection, soil acceptance and support loss. */
@LDLRegisterClient(name="plant_soil_interaction",group="plant_support",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public final class PlantSupportView implements UIScenario {
    private static final BlockPos GROUND=new BlockPos(0,230,0);
    @Override public void configure(ScenarioOptions o){o.tags("plant_support").defaultTimeoutMs(120000).scenarioTimeoutMs(240000);}
    private static void click(){
        var mc=Minecraft.getInstance();
        mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(GROUND.getCenter().add(0,.5,0),Direction.UP,GROUND,false));
    }
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.SURVIVAL).runCommand("difficulty peaceful")
            .server("prepare stone planting site",ctx->{
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                for(int x=-4;x<=4;x++)for(int z=-3;z<=5;z++){
                    level.setBlockAndUpdate(new BlockPos(x,230,z),Blocks.STONE.defaultBlockState());
                    for(int y=231;y<236;y++)level.removeBlock(new BlockPos(x,y,z),false);
                }
                ctx.player().getInventory().clearContent();ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(CloudseaBlocks.FERN.get()));
                DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,231,3.5),180,27);
            })
            .waitUntil("plant item arrives",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.player.getMainHandItem().is(CloudseaBlocks.FERN.get().asItem());})
            .ticks(30).step("try planting fern on stone",ctx->click()).serverTicks(5)
            .checkServer("stone rejects plant without consuming it",ctx->ctx.player().level().isEmptyBlock(GROUND.above())&&ctx.player().getMainHandItem().getCount()==1)
            .server("replace stone with soil",ctx->ctx.player().level().setBlockAndUpdate(GROUND,Blocks.DIRT.defaultBlockState()))
            .waitUntil("soil arrives",ctx->Minecraft.getInstance().level.getBlockState(GROUND).is(Blocks.DIRT))
            .step("plant fern on dirt",ctx->click())
            .waitUntil("planted fern arrives",ctx->Minecraft.getInstance().level.getBlockState(GROUND.above()).is(CloudseaBlocks.FERN.get()))
            .checkServer("dirt accepts and consumes one plant",ctx->ctx.player().getMainHandItem().isEmpty()&&ctx.player().level().getBlockState(GROUND.above()).is(CloudseaBlocks.FERN.get()))
            .screenshot("fern_on_soil")
            .server("replace its soil with stone",ctx->ctx.player().level().setBlockAndUpdate(GROUND,Blocks.STONE.defaultBlockState()))
            .waitUntil("unsupported fern disappears",ctx->Minecraft.getInstance().level.isEmptyBlock(GROUND.above()))
            .checkServer("support loss removes plant",ctx->ctx.player().level().isEmptyBlock(GROUND.above()));
    }
}
