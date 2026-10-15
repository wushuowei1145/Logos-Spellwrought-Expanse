package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** Close, labelled-by-order plates, eight independently authored plant models per frame. */
public abstract class IndividualModelViews implements UIScenario {
    protected abstract int page();
    @Override public void configure(ScenarioOptions options){options.tags("models").defaultTimeoutMs(45_000).scenarioTimeoutMs(90_000);}
    @Override public void define(ScenarioBuilder steps){
        steps.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("close model camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(8);mc.options.broadcastOptions();})
                .server("stage eight distinct registered leaf models",ctx->{
                    var level=ctx.server().overworld();
                    for(int x=-12;x<=12;x++)for(int z=-8;z<=12;z++){
                        level.setBlockAndUpdate(new BlockPos(x,99,z),CloudseaBlocks.PORCELAIN.get().defaultBlockState());
                        for(int y=100;y<=108;y++)level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    for(int i=0;i<8;i++){
                        int x=-6+i%4*4,z=-4+i/4*5;
                        level.setBlockAndUpdate(new BlockPos(x,100,z),CloudseaBlocks.IVORY_BRICKS.get().defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(x,101,z),CloudseaBlocks.LEAVES.get(CloudseaBiome.values()[page()*8+i]).get().defaultBlockState());
                    }
                    var p=ctx.player();DimensionTravel.teleport(p,level,new Vec3(.5,103.1,10.5),180,13);
                    p.getAbilities().flying=true;p.onUpdateAbilities();
                }).waitUntil("the plate reached the client",ctx->Minecraft.getInstance().level.getBlockState(new BlockPos(-6,101,-4)).is(CloudseaBlocks.LEAVES.get(CloudseaBiome.values()[page()*8]).get()))
                .ticks(60).check("eight plant identities remain separate",ctx->CloudseaBlocks.LEAVES.size()==24)
                .screenshot("individual_leaves_"+(page()+1)).teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="individual_leaves_1",group="individual_models",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class First extends IndividualModelViews {protected int page(){return 0;}}
    @LDLRegisterClient(name="individual_leaves_2",group="individual_models",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Second extends IndividualModelViews {protected int page(){return 1;}}
    @LDLRegisterClient(name="individual_leaves_3",group="individual_models",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Third extends IndividualModelViews {protected int page(){return 2;}}
}
