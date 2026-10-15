package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import dev.logos.dimension.world.NaturalTreeShape;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.phys.Vec3;

/** Real placed full trees, rather than individual leaf ornaments on pedestals. */
public abstract class NaturalTreeViews implements UIScenario {
    protected abstract int page();
    @Override public void configure(ScenarioOptions o){o.tags("trees").defaultTimeoutMs(60_000).scenarioTimeoutMs(100_000);}
    @Override public void define(ScenarioBuilder s){
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful").runCommand("time set day")
            .step("tree camera",ctx->{var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(8);mc.options.broadcastOptions();})
            .server("place complete branch-supported trees",ctx->{
                var level=ctx.server().overworld();
                for(int x=-24;x<=24;x++)for(int z=-9;z<=12;z++){
                    level.setBlockAndUpdate(new BlockPos(x,99,z),DimensionBlocks.AETHER_TURF.get().defaultBlockState());
                    for(int y=100;y<=122;y++)level.removeBlock(new BlockPos(x,y,z),false);
                }
                for(int i=0;i<3;i++){
                    int x=(i-1)*15;var form=NaturalTreeShape.Form.values()[(i+page()*2)%4];
                    var tree=NaturalTreeShape.create(form,form==NaturalTreeShape.Form.CONIFER?12:8,719+i);
                    var wood=page()==0&&i==0?DimensionBlocks.FAN_TRUNK:CloudseaBlocks.STEM;
                    var leaf=page()==0&&i==0?DimensionBlocks.FAN_LEAVES:CloudseaBlocks.LEAVES.get(CloudseaBiome.values()[page()*8+i*3]);
                    for(var v:tree.voxels()){
                        var state=leaf.get().defaultBlockState();
                        if(v.part()!=NaturalTreeShape.Part.LEAF){
                            var axis=v.part()==NaturalTreeShape.Part.WOOD_X?Direction.Axis.X:v.part()==NaturalTreeShape.Part.WOOD_Z?Direction.Axis.Z:Direction.Axis.Y;
                            state=wood.get().defaultBlockState().setValue(RotatedPillarBlock.AXIS,axis);
                        }
                        level.setBlockAndUpdate(new BlockPos(x+v.x(),100+v.y(),v.z()),state);
                    }
                }
                var p=ctx.player();DimensionTravel.teleport(p,level,new Vec3(.5,105.8,37),180,4);p.getAbilities().flying=true;p.onUpdateAbilities();
            }).waitUntil("tree blocks received",ctx->Minecraft.getInstance().level.getBlockState(new BlockPos(0,100,0)).is(CloudseaBlocks.STEM.get()))
            .ticks(80).check("solid wood fills the trunk",ctx->Minecraft.getInstance().level.getBlockState(new BlockPos(0,101,0)).getCollisionShape(Minecraft.getInstance().level,new BlockPos(0,101,0)).max(Direction.Axis.X)==1)
            .screenshot("natural_trees_"+page()).teardown("restore HUD",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    @LDLRegisterClient(name="natural_trees_a",group="natural_trees",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class First extends NaturalTreeViews {protected int page(){return 0;}}
    @LDLRegisterClient(name="natural_trees_b",group="natural_trees",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
    public static class Second extends NaturalTreeViews {protected int page(){return 1;}}
}
