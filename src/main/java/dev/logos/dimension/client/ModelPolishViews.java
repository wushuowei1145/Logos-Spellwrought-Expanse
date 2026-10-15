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

/** Fixed, actual block placements make model silhouettes and missing assets visible in screenshots. */
public abstract class ModelPolishViews implements UIScenario {
    protected abstract boolean flora();
    @Override public void configure(ScenarioOptions options) {
        options.tags("models").defaultTimeoutMs(45_000).scenarioTimeoutMs(90_000);
    }
    @Override public void define(ScenarioBuilder steps) {
        steps.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful").runCommand("time set day")
                .step("frame model gallery", context -> {
                    var mc=Minecraft.getInstance();mc.options.hideGui=true;
                    mc.options.renderDistance().set(8);mc.options.broadcastOptions();
                }).server("place registered blocks", context -> {
                    var level=context.server().overworld();
                    for(int x=-14;x<=14;x++)for(int z=-9;z<=14;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z),CloudseaBlocks.PORCELAIN.get().defaultBlockState());
                        for(int y=100;y<112;y++)level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    if(flora()) {
                        for(int i=0;i<8;i++) {
                            int x=-7+(i%4)*5,z=-3+(i/4)*6;
                            level.setBlockAndUpdate(new BlockPos(x,100,z),CloudseaBlocks.IVORY_BRICKS.get().defaultBlockState());
                            level.setBlockAndUpdate(new BlockPos(x,101,z),CloudseaBlocks.LEAVES.get(CloudseaBiome.values()[i]).get().defaultBlockState());
                        }
                        var plants=java.util.List.of(CloudseaBlocks.LANTERN,CloudseaBlocks.EYE_FERN,CloudseaBlocks.DEMON_REED,CloudseaBlocks.ROOT,CloudseaBlocks.STEM);
                        for(int i=0;i<plants.size();i++) {
                            int x=-8+i*4;
                            level.setBlockAndUpdate(new BlockPos(x,100,8),CloudseaBlocks.IVORY_BRICKS.get().defaultBlockState());
                            level.setBlockAndUpdate(new BlockPos(x,101,8),plants.get(i).get().defaultBlockState());
                        }
                    } else {
                        for(int i=0;i<24;i++) {
                            int x=-11+(i%8)*3,z=-4+(i/8)*4;
                            var state=CloudseaBlocks.GROUND.get(CloudseaBiome.values()[i]).get().defaultBlockState();
                            for(int dx=0;dx<2;dx++)for(int dz=0;dz<2;dz++)level.setBlockAndUpdate(new BlockPos(x+dx,100,z+dz),state);
                        }
                        var building=java.util.List.of(CloudseaBlocks.IVORY_BRICKS,CloudseaBlocks.DARK_TIMBER,CloudseaBlocks.SLATE,CloudseaBlocks.WINDOW,CloudseaBlocks.DEMONSTONE,CloudseaBlocks.PORCELAIN,CloudseaBlocks.MANTLE);
                        for(int i=0;i<building.size();i++)for(int y=100;y<=102;y++)level.setBlockAndUpdate(new BlockPos(-9+i*3,y,10),building.get(i).get().defaultBlockState());
                    }
                    var player=context.player();
                    DimensionTravel.teleport(player,level,new Vec3(1,flora()?106:111,flora()?19:24),180,flora()?22:34);
                    player.getAbilities().flying=true;player.onUpdateAbilities();
                }).waitUntil("models received", context -> Minecraft.getInstance().level.getBlockState(new BlockPos(0,99,0)).is(CloudseaBlocks.PORCELAIN.get()))
                .ticks(70).check("gallery is in the real client", context -> Minecraft.getInstance().level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD))
                .screenshot(flora()?"sculpted_flora":"material_collection")
                .teardown("restore HUD",context -> Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="polished_plants",group="model_polish",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Plants extends ModelPolishViews {protected boolean flora(){return true;}}
    @LDLRegisterClient(name="polished_materials",group="model_polish",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Materials extends ModelPolishViews {protected boolean flora(){return false;}}
    @LDLRegisterClient(name="polished_astrolabe",group="model_polish",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Sky extends AlchemicalViews.Sky {}
    @LDLRegisterClient(name="polished_cauldron",group="model_polish",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Cauldron extends CauldronView {}
}
